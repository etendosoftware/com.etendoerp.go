/*
 * *************************************************************************
 * The contents of this file are subject to the Etendo License
 * (the "License"), you may not use this file except in compliance with
 * the License.
 * You may obtain a copy of the License at
 * https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * Software distributed under the License is distributed on an
 * "AS IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
 * implied. See the License for the specific language governing rights
 * and limitations under the License.
 * All portions are Copyright (C) 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */
package com.etendoerp.go.schemaforge;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.materialmgmt.transaction.ShipmentInOutLine;

/**
 * The one rule of how much of a receipt / shipment line a return may still take (ETP-5711), shared
 * by every path that writes a return line: create the return from the document, import lines into
 * an existing draft return, and edit the quantity of a return line. Each per-entity code path calls
 * it explicitly; it names no entity.
 *
 * <p><b>"Already returned"</b> is every return line linked to the source line
 * ({@code Canceled_Inoutline_ID}) whose header is not voided, <b>drafts included</b>. It is the same
 * question the window's own pickers ask ({@code availableReceiptLines} / {@code availableShipmentLines}
 * and their sibling document lists), so what the UI offers and what the server accepts cannot
 * disagree. What is left is the source line's movement quantity minus that total.
 *
 * <p>A violation is a <b>422</b> whose {@code error} object carries {@code code} (one of
 * {@link #ERROR_EXCEEDS_AVAILABLE}, {@link #ERROR_NOTHING_LEFT}, {@link #ERROR_LINE_NOT_IN_DOCUMENT}),
 * a plain-English {@code message} and, for the over-return, the structured {@code product},
 * {@code requested}, {@code available}, {@code sourceLineId}.
 */
final class ReturnableQuantity {

  /** More than what is left of a source line was asked for. */
  static final String ERROR_EXCEEDS_AVAILABLE = "returnQuantityExceedsAvailable";
  /** The document has nothing left to return at all. */
  static final String ERROR_NOTHING_LEFT = "nothingLeftToReturn";
  /** A line id that is not a line of the source document. */
  static final String ERROR_LINE_NOT_IN_DOCUMENT = "returnLineNotInDocument";
  /** The source document is not completed. */
  static final String ERROR_SOURCE_NOT_COMPLETED = "sourceNotCompleted";

  /** A return line POSTed directly instead of through the import / create-from-document actions. */
  static final String ERROR_LINE_REQUIRES_IMPORT = "returnLineRequiresImport";

  static final int SC_UNPROCESSABLE = 422;

  private static final String FIELD_ERROR = "error";
  private static final String HTTP_PATCH = "PATCH";
  private static final String HTTP_PUT = "PUT";

  /**
   * The return lines that count as "already returned": the line ({@code rl}) joined to its header
   * ({@code rh}). Shared with every query that totals returns per source line, so they cannot
   * drift from {@link #alreadyReturned}.
   */
  static final String COUNTED_RETURN_LINES_FROM =
      "M_InOutLine rl JOIN M_InOut rh ON rh.M_InOut_ID = rl.M_InOut_ID";
  /** Which of those count: every non-voided header, drafts included (the pickers' own filter). */
  static final String COUNTED_RETURN_LINES_FILTER = "rh.DocStatus NOT IN ('VO')";
  /**
   * Sum of the quantities of the non-voided return lines pointing at one source line. The
   * {@code DocStatus} filter is the one of the window's {@code availableX} lists, verbatim.
   */
  private static final String ALREADY_RETURNED_SQL =
      "SELECT COALESCE(SUM(ABS(rl.MovementQty)), 0) FROM " + COUNTED_RETURN_LINES_FROM + " "
          + "WHERE rl.Canceled_Inoutline_ID = ? AND " + COUNTED_RETURN_LINES_FILTER;
  /**
   * Row lock on one source line ({@code FOR NO KEY UPDATE}: a foreign-key insert that points at
   * the line, an invoice line or a match row, takes {@code FOR KEY SHARE} on it and must not wait
   * for a return — ETP-5713). Taken BEFORE the returned total is read and held to the end of the
   * transaction, so a second request on the same source line waits here, then reads a total that
   * includes the first one's lines (READ COMMITTED: each statement sees what was committed).
   */
  private static final String LOCK_SOURCE_LINE_SQL =
      "SELECT M_InOutLine_ID FROM M_InOutLine WHERE M_InOutLine_ID = ? FOR NO KEY UPDATE";
  private static final String EXCLUDING_LINE_SQL = " AND rl.M_InOutLine_ID <> ?";
  /** The returns that hold units of one source line, one row per return, same counting rule. */
  static final String RETURNS_HOLDING_SQL =
      "SELECT rh.M_InOut_ID, rh.DocumentNo, rh.DocStatus, SUM(ABS(rl.MovementQty)) FROM "
          + COUNTED_RETURN_LINES_FROM + " WHERE rl.Canceled_Inoutline_ID = ? AND "
          + COUNTED_RETURN_LINES_FILTER + " AND rh.M_InOut_ID <> ? "
          + "GROUP BY rh.M_InOut_ID, rh.DocumentNo, rh.DocStatus ORDER BY rh.DocumentNo";

  private ReturnableQuantity() {
  }

  /** The quantities asked of each source line within ONE request, summed per source line. */
  static final class Requests {
    private final Map<String, ShipmentInOutLine> lines = new LinkedHashMap<>();
    private final Map<String, BigDecimal> quantities = new LinkedHashMap<>();

    /** Adds {@code quantity} to what is asked of {@code source}; the same line twice accumulates. */
    void add(ShipmentInOutLine source, BigDecimal quantity) {
      lines.putIfAbsent(source.getId(), source);
      quantities.merge(source.getId(), quantity.abs(), BigDecimal::add);
    }

    boolean isEmpty() {
      return quantities.isEmpty();
    }

    Collection<String> sourceLineIds() {
      return quantities.keySet();
    }

    ShipmentInOutLine sourceLine(String id) {
      return lines.get(id);
    }

    BigDecimal quantity(String id) {
      return quantities.get(id);
    }
  }

  /**
   * Locks the given source lines ({@code SELECT ... FOR NO KEY UPDATE}) in ascending id order, so two
   * requests touching overlapping lines can never wait on each other in opposite orders. It uses the
   * DAL's own connection: the lock lives in the very transaction that goes on to insert the return
   * lines, and is released when it ends. Call it BEFORE reading {@link #alreadyReturned}.
   */
  static void lockSourceLines(Collection<String> sourceLineIds) throws SQLException {
    if (sourceLineIds == null || sourceLineIds.isEmpty()) {
      return;
    }
    List<String> sorted = new ArrayList<>(new TreeSet<>(sourceLineIds));
    Connection conn = OBDal.getInstance().getConnection();
    try (PreparedStatement ps = conn.prepareStatement(LOCK_SOURCE_LINE_SQL)) {
      for (String id : sorted) {
        ps.setString(1, id);
        try (ResultSet rs = ps.executeQuery()) {
          rs.next();
        }
      }
    }
  }

  /**
   * How much of {@code sourceLineId} the non-voided returns already hold.
   *
   * @param excludedReturnLineId a return line left out of the total (the one being edited), or
   *                             {@code null} to count all of them
   */
  static BigDecimal alreadyReturned(String sourceLineId, String excludedReturnLineId)
      throws SQLException {
    boolean excluding = StringUtils.isNotBlank(excludedReturnLineId);
    String sql = excluding ? ALREADY_RETURNED_SQL + EXCLUDING_LINE_SQL : ALREADY_RETURNED_SQL;
    Connection conn = OBDal.getInstance().getConnection();
    try (PreparedStatement ps = conn.prepareStatement(sql)) {
      ps.setString(1, sourceLineId);
      if (excluding) {
        ps.setString(2, excludedReturnLineId);
      }
      try (ResultSet rs = ps.executeQuery()) {
        BigDecimal total = rs.next() ? rs.getBigDecimal(1) : null;
        return total == null ? BigDecimal.ZERO : total;
      }
    }
  }

  /** A return that holds units of a source line: its id, number, status and those units. */
  static final class HoldingReturn {
    final String id;
    final String documentNo;
    final String documentStatus;
    final BigDecimal quantity;

    HoldingReturn(String id, String documentNo, String documentStatus, BigDecimal quantity) {
      this.id = id;
      this.documentNo = documentNo;
      this.documentStatus = documentStatus;
      this.quantity = quantity == null ? BigDecimal.ZERO : quantity;
    }
  }

  /**
   * The returns that already hold units of {@code sourceLineId}, with the same counting rule as
   * {@link #alreadyReturned} (non-voided, drafts included), leaving out the return document
   * {@code excludedReturnId} (the one just created).
   *
   * @param sourceLineId     the source receipt / shipment line
   * @param excludedReturnId the return document left out, or {@code null} to list all of them
   * @return one entry per holding return, ordered by document number
   * @throws SQLException when the query fails
   */
  static List<HoldingReturn> returnsHolding(String sourceLineId, String excludedReturnId)
      throws SQLException {
    List<HoldingReturn> result = new ArrayList<>();
    Connection conn = OBDal.getInstance().getConnection();
    // A failed statement aborts a PostgreSQL transaction; JdbcSavepoints rolls back to its savepoint
    // (so a caller that swallows the failure keeps a usable transaction) and releases it on success.
    JdbcSavepoints.run(conn, () -> {
      try (PreparedStatement ps = conn.prepareStatement(RETURNS_HOLDING_SQL)) {
        ps.setString(1, sourceLineId);
        ps.setString(2, excludedReturnId == null ? "" : excludedReturnId);
        try (ResultSet rs = ps.executeQuery()) {
          while (rs.next()) {
            result.add(new HoldingReturn(rs.getString(1), rs.getString(2), rs.getString(3),
                rs.getBigDecimal(4)));
          }
        }
      }
    });
    return result;
  }

  /** What is left of {@code source} to return: its movement quantity minus {@link #alreadyReturned}. */
  static BigDecimal available(ShipmentInOutLine source, String excludedReturnLineId)
      throws SQLException {
    BigDecimal moved = source.getMovementQuantity() == null ? BigDecimal.ZERO
        : source.getMovementQuantity().abs();
    return moved.subtract(alreadyReturned(source.getId(), excludedReturnLineId));
  }

  /**
   * Checks every source line of one request against what is left of it, the quantities of the same
   * line summed. Nothing is written by the caller until this returns {@code null}.
   *
   * @return {@code null} when every quantity fits, else the 422 for the first line that does not
   */
  static NeoResponse refuseIfExceeds(Requests requests) throws SQLException {
    lockSourceLines(requests.sourceLineIds());
    for (String id : requests.sourceLineIds()) {
      ShipmentInOutLine source = requests.sourceLine(id);
      BigDecimal requested = requests.quantity(id);
      BigDecimal available = available(source, null);
      if (requested.compareTo(available) > 0) {
        return exceeds(source, requested, available);
      }
    }
    return null;
  }

  /**
   * The cap on a quantity edit of an existing return line: a PATCH or PUT of the CRUD endpoint that
   * carries {@code movementQuantity}. The line's own current quantity does not count against it.
   * Every other request, a line without a source link (nothing to cap against) included, answers
   * {@code null}.
   *
   * @return {@code null} to proceed; a 422 when the quantity exceeds what is left; a 500 when the
   *         check itself cannot run (a cap that cannot be verified is not skipped)
   */
  static NeoResponse refuseQuantityEdit(NeoContext context, Logger log) {
    if (!isQuantityEdit(context)) {
      return null;
    }
    BigDecimal requested = parseQuantity(
        context.getRequestBody().opt(ReturnLineQuantityPolicy.FIELD_MOVEMENT_QUANTITY));
    if (requested == null) {
      return null;
    }
    String returnLineId = context.getRecordId();
    try {
      OBContext.setAdminMode(true);
      try {
        ShipmentInOutLine returnLine = OBDal.getInstance().get(ShipmentInOutLine.class, returnLineId);
        ShipmentInOutLine source = returnLine == null ? null : returnLine.getCanceledInoutLine();
        if (source == null) {
          return null;
        }
        lockSourceLines(List.of(source.getId()));
        BigDecimal available = available(source, returnLineId);
        return requested.abs().compareTo(available) > 0
            ? exceeds(source, requested.abs(), available) : null;
      } finally {
        OBContext.restorePreviousMode();
      }
    } catch (Exception e) {
      log.error("Could not verify the returnable quantity of return line {}: {}", returnLineId,
          e.getMessage(), e);
      return NeoResponse.error(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
          "The returnable quantity of this line could not be verified");
    }
  }

  /**
   * Refuses the creation of a return line through the CRUD endpoint (a {@code POST}), on every
   * channel. A return line only enters a return through the document actions, which link it to the
   * line it returns and cap it; a loose one would be neither. Each return line customization calls
   * this explicitly with the two ways in that make sense for its own direction.
   *
   * @param importAction the action of the return that imports lines ({@code importReceiptLines})
   * @param createAction the action of the source document that creates the whole return
   * @return the 422, or {@code null} when the request is not a CRUD create
   */
  static NeoResponse refuseDirectCreate(NeoContext context, String importAction,
      String createAction) {
    if (context == null || !NeoEndpointType.CRUD.equals(context.getEndpointType())
        || !"POST".equalsIgnoreCase(context.getHttpMethod())) {
      return null;
    }
    return refusal(ERROR_LINE_REQUIRES_IMPORT,
        "Return lines cannot be created directly. Add them to the return with " + importAction
            + ", or create the whole return from the source document with " + createAction + ".");
  }

  private static boolean isQuantityEdit(NeoContext context) {
    if (context == null || !NeoEndpointType.CRUD.equals(context.getEndpointType())
        || StringUtils.isBlank(context.getRecordId()) || context.getRequestBody() == null) {
      return false;
    }
    String method = context.getHttpMethod();
    return (HTTP_PATCH.equals(method) || HTTP_PUT.equals(method))
        && context.getRequestBody().has(ReturnLineQuantityPolicy.FIELD_MOVEMENT_QUANTITY);
  }

  /** A body value as a number, or {@code null} when it is absent, null or not numeric. */
  static BigDecimal parseQuantity(Object raw) {
    if (raw == null || JSONObject.NULL.equals(raw)) {
      return null;
    }
    try {
      return new BigDecimal(raw.toString().trim());
    } catch (NumberFormatException e) {
      return null;
    }
  }

  /** The 422 for a quantity above what is left, naming the product and both quantities. */
  static NeoResponse exceeds(ShipmentInOutLine source, BigDecimal requested, BigDecimal available) {
    String product = productName(source);
    BigDecimal shownAvailable = available.max(BigDecimal.ZERO);
    String message = "Cannot return " + plain(requested) + " of " + product + ": "
        + (shownAvailable.signum() == 0 ? "nothing is left to return from this line"
            : "only " + plain(shownAvailable) + " can still be returned from this line")
        + " (" + plain(source.getMovementQuantity() == null ? BigDecimal.ZERO
            : source.getMovementQuantity().abs())
        + " on the source document).";
    try {
      JSONObject error = errorObject(ERROR_EXCEEDS_AVAILABLE, message);
      error.put("product", product);
      error.put("requested", requested);
      error.put("available", shownAvailable);
      error.put("sourceLineId", source.getId());
      return unprocessable(error);
    } catch (JSONException e) {
      return NeoResponse.error(SC_UNPROCESSABLE, message);
    }
  }

  /** A 422 carrying {@code code} and {@code message}, in the module's {@code {"error":{...}}} shape. */
  static NeoResponse refusal(String code, String message) {
    try {
      return unprocessable(errorObject(code, message));
    } catch (JSONException e) {
      return NeoResponse.error(SC_UNPROCESSABLE, message);
    }
  }

  private static JSONObject errorObject(String code, String message) throws JSONException {
    JSONObject error = new JSONObject();
    error.put("status", SC_UNPROCESSABLE);
    error.put("code", code);
    error.put("message", message);
    return error;
  }

  private static NeoResponse unprocessable(JSONObject error) throws JSONException {
    return NeoResponse.error(SC_UNPROCESSABLE, new JSONObject().put(FIELD_ERROR, error));
  }

  /** The product's name, or the line id when the line has no product to name. */
  static String productName(ShipmentInOutLine source) {
    if (source.getProduct() != null && StringUtils.isNotBlank(source.getProduct().getName())) {
      return source.getProduct().getName();
    }
    return source.getId();
  }

  private static String plain(BigDecimal value) {
    BigDecimal stripped = value.stripTrailingZeros();
    return (stripped.scale() < 0 ? stripped.setScale(0) : stripped).toPlainString();
  }
}
