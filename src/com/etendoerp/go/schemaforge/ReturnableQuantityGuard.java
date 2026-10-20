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
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.service.OBDal;

/**
 * Refuses a return that would send back more than was shipped (ETP-5729 CP-12).
 *
 * <p>For every source shipment line the returnable quantity is
 * {@code |shipped| − Σ |qty of every other active, non-voided return line pointing at it|}. Draft
 * returns count as consumed exactly like completed ones — the same rule as
 * {@code GoodsShipmentHeaderHandler.CAN_RETURN_SQL} (the {@code canCreateReturn} flag) and the
 * {@code availableShipmentLines} action the UI wizard reads, so the backend now enforces what the
 * UI already displays. Voided returns never count.
 *
 * <p>Called explicitly by the customizations of the sales-return entities that can write a line
 * linked to a shipment line ({@code Canceled_InOutLine_ID}): the goods-shipment
 * {@code createReturn} action ({@link NeoReturnReceiptService}), the return-material-receipt
 * {@code importShipmentLines} action and the return-material-receipt line CRUD
 * ({@link ReturnMaterialReceiptLineHandler}). Every channel (REST single, batch, MCP) reaches those
 * customizations, so it reaches this check too.
 *
 * <p>Concurrency: the requested source lines are row-locked ({@code FOR UPDATE}) before the
 * returnable quantity is read, so two concurrent returns of the same shipment line are checked one
 * after the other, never both against the same "already returned" figure. The lock lives until the
 * business transaction ends, i.e. after the caller has written its return lines.
 *
 * <p>Fails CLOSED: a DB error propagates, so the caller answers 500 instead of letting an
 * unchecked return through.
 */
final class ReturnableQuantityGuard {

  static final String RETURN_QTY_EXCEEDED_CODE = "RETURN_QTY_EXCEEDS_SHIPPED";
  static final String RETURN_QTY_EXCEEDED_KEY = "ETGO_ReturnQtyExceedsShipped";

  /**
   * One row per requested source line: product name, shipped magnitude, and the magnitude already
   * taken by other non-voided returns. The {@code <> ?} excludes the return line being edited, so
   * re-saving a line with its own quantity is never counted twice. Same filters as
   * {@code GoodsShipmentHeaderHandler.CAN_RETURN_SQL}: an INACTIVE source line has nothing
   * returnable. It is expressed as "shipped = 0" rather than as {@code sil.isactive = 'Y'} in the
   * WHERE so the row — and the product name the refusal shows — is still there; an unknown id,
   * which yields no row at all, is refused the same way, see {@link #findExcess}.
   */
  private static final String RETURNABLE_SQL_PREFIX =
      "SELECT sil.m_inoutline_id, p.name,"
      + " CASE WHEN sil.isactive = 'Y' THEN ABS(sil.movementqty) ELSE 0 END,"
      + " COALESCE((SELECT SUM(ABS(rl.movementqty)) FROM m_inoutline rl"
      + "   JOIN m_inout r ON r.m_inout_id = rl.m_inout_id"
      + "   WHERE rl.canceled_inoutline_id = sil.m_inoutline_id"
      + "     AND rl.isactive = 'Y' AND r.isactive = 'Y' AND r.docstatus <> 'VO'"
      + "     AND rl.m_inoutline_id <> ?), 0)"
      + " FROM m_inoutline sil"
      + " LEFT JOIN m_product p ON p.m_product_id = sil.m_product_id"
      + " WHERE sil.m_inoutline_id IN (";

  /**
   * Row-locks the requested source lines for the rest of the business transaction, so two
   * concurrent returns of the same shipment line are serialised: the second one waits here until
   * the first commits (or rolls back). Ordered by id so two requests locking overlapping sets
   * always take the locks in the same order and cannot deadlock each other.
   *
   * <p>It is a SEPARATE statement on purpose, not a {@code FOR UPDATE} on the returnable query:
   * under READ COMMITTED each statement takes its own snapshot, and a locking query that waited
   * re-checks only the locked row, not the {@code SUM} sub-select over the return lines. Only a
   * statement that STARTS after the lock was granted sees the return lines the other transaction
   * just committed.
   */
  private static final String LOCK_SOURCE_LINES_SQL_PREFIX =
      "SELECT m_inoutline_id FROM m_inoutline WHERE m_inoutline_id IN (";
  private static final String LOCK_SOURCE_LINES_SQL_SUFFIX = ") ORDER BY m_inoutline_id FOR UPDATE";

  private static final String STORED_LINE_SQL =
      "SELECT canceled_inoutline_id, ABS(movementqty) FROM m_inoutline WHERE m_inoutline_id = ?";

  private ReturnableQuantityGuard() {
  }

  /** A requested quantity that exceeds what is still returnable on its source line. */
  static final class Excess {
    final String sourceLineId;
    final String productName;
    final BigDecimal shipped;
    final BigDecimal alreadyReturned;
    final BigDecimal requested;

    Excess(String sourceLineId, String productName, BigDecimal shipped,
        BigDecimal alreadyReturned, BigDecimal requested) {
      this.sourceLineId = sourceLineId;
      this.productName = productName;
      this.shipped = shipped;
      this.alreadyReturned = alreadyReturned;
      this.requested = requested;
    }

    BigDecimal available() {
      return shipped.subtract(alreadyReturned).max(BigDecimal.ZERO);
    }
  }

  /**
   * Checks the requested quantities and builds the refusal when one exceeds what is returnable.
   *
   * @param requestedBySourceLine positive magnitudes keyed by source {@code M_InOutLine_ID}; an
   *                              empty map is a no-op
   * @param editedReturnLineId    the return line being edited (excluded from the sum), or
   *                              {@code null} when only new lines are written
   * @return the 409 response, or {@code null} when every quantity fits
   * @throws SQLException when the returnable quantities cannot be read (fail closed)
   */
  static NeoResponse refuseIfExceeded(Map<String, BigDecimal> requestedBySourceLine,
      String editedReturnLineId) throws SQLException {
    Excess excess = findExcess(requestedBySourceLine, editedReturnLineId);
    return excess == null ? null : exceededResponse(excess);
  }

  // The IN list is built from '?' placeholders only; every value is bound.
  @SuppressWarnings("java:S2077")
  static Excess findExcess(Map<String, BigDecimal> requestedBySourceLine,
      String editedReturnLineId) throws SQLException {
    if (requestedBySourceLine == null || requestedBySourceLine.isEmpty()) {
      return null;
    }
    String[] ids = requestedBySourceLine.keySet().toArray(new String[0]);
    String placeholders = String.join(",", Collections.nCopies(ids.length, "?"));
    Connection conn = OBDal.getInstance().getConnection();
    lockSourceLines(conn, placeholders, ids);
    Set<String> seen = new HashSet<>();
    try (PreparedStatement ps = conn.prepareStatement(RETURNABLE_SQL_PREFIX + placeholders + ")")) {
      ps.setString(1, StringUtils.defaultString(editedReturnLineId));
      for (int i = 0; i < ids.length; i++) {
        ps.setString(i + 2, ids[i]);
      }
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          seen.add(rs.getString(1));
          Excess candidate = toExcessIfOver(rs, requestedBySourceLine);
          if (candidate != null) {
            return candidate;
          }
        }
      }
    }
    return nothingReturnableOnUnseenLine(requestedBySourceLine, seen);
  }

  // Placeholders only; every value is bound.
  @SuppressWarnings("java:S2077")
  private static void lockSourceLines(Connection conn, String placeholders, String[] ids)
      throws SQLException {
    String sql = LOCK_SOURCE_LINES_SQL_PREFIX + placeholders + LOCK_SOURCE_LINES_SQL_SUFFIX;
    try (PreparedStatement ps = conn.prepareStatement(sql)) {
      for (int i = 0; i < ids.length; i++) {
        ps.setString(i + 1, ids[i]);
      }
      // execute(), not executeQuery(): the rows are not needed, only the locks the statement takes.
      ps.execute();
    }
  }

  /**
   * A requested source line the returnable query did not return — not a shipment line at all —
   * has nothing returnable. Without this, an unknown id would pass the check silently instead of
   * being refused.
   */
  private static Excess nothingReturnableOnUnseenLine(Map<String, BigDecimal> requested,
      Set<String> seen) {
    for (Map.Entry<String, BigDecimal> e : requested.entrySet()) {
      if (!seen.contains(e.getKey())) {
        return new Excess(e.getKey(), "", BigDecimal.ZERO, BigDecimal.ZERO, e.getValue());
      }
    }
    return null;
  }

  private static Excess toExcessIfOver(ResultSet rs, Map<String, BigDecimal> requested)
      throws SQLException {
    String sourceLineId = rs.getString(1);
    BigDecimal qty = requested.get(sourceLineId);
    BigDecimal shipped = zeroIfNull(rs.getBigDecimal(3));
    BigDecimal alreadyReturned = zeroIfNull(rs.getBigDecimal(4));
    if (qty == null || alreadyReturned.add(qty).compareTo(shipped) <= 0) {
      return null;
    }
    return new Excess(sourceLineId, StringUtils.defaultString(rs.getString(2)), shipped,
        alreadyReturned, qty);
  }

  /** What a persisted return line carries: its source shipment line and its quantity magnitude. */
  static final class StoredReturnLine {
    final String sourceLineId;
    final BigDecimal quantity;

    StoredReturnLine(String sourceLineId, BigDecimal quantity) {
      this.sourceLineId = sourceLineId;
      this.quantity = quantity;
    }
  }

  /**
   * The source shipment line and the quantity magnitude of a persisted return line, or
   * {@code null} when the id is blank or no such line exists. {@code sourceLineId} is {@code null}
   * when the line is not linked to a shipment line.
   */
  static StoredReturnLine findStoredReturnLine(String returnLineId) throws SQLException {
    if (StringUtils.isBlank(returnLineId)) {
      return null;
    }
    Connection conn = OBDal.getInstance().getConnection();
    try (PreparedStatement ps = conn.prepareStatement(STORED_LINE_SQL)) {
      ps.setString(1, returnLineId);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? new StoredReturnLine(rs.getString(1), rs.getBigDecimal(2)) : null;
      }
    }
  }

  /**
   * The 409 for an over-return, in the structured NEO error shape ({@code code},
   * {@code messageKeys}, {@code messageParams}): the SPA renders its own locale from
   * {@code messageKeys} + {@code messageParams}; the English {@code message} is what REST and MCP
   * callers read, and it carries the numbers an agent needs to correct the request.
   */
  static NeoResponse exceededResponse(Excess excess) {
    String product = excess.productName;
    String message = "Cannot return " + plain(excess.requested) + " of " + product
        + ": only " + plain(excess.available()) + " of the " + plain(excess.shipped)
        + " shipped can still be returned (draft and completed returns count).";
    try {
      JSONObject params = new JSONObject();
      params.put("product", product);
      params.put("requested", plain(excess.requested));
      params.put("available", plain(excess.available()));
      params.put("shipped", plain(excess.shipped));
      params.put("sourceLineId", excess.sourceLineId);

      JSONObject error = new JSONObject();
      error.put("status", HttpServletResponse.SC_CONFLICT);
      error.put("code", RETURN_QTY_EXCEEDED_CODE);
      error.put("message", message);
      error.put("messageKeys", new JSONArray().put(RETURN_QTY_EXCEEDED_KEY));
      error.put("messageParams", params);

      JSONObject body = new JSONObject();
      body.put("error", error);
      return NeoResponse.error(HttpServletResponse.SC_CONFLICT, body);
    } catch (JSONException e) {
      return NeoResponse.error(HttpServletResponse.SC_CONFLICT, message);
    }
  }

  private static BigDecimal zeroIfNull(BigDecimal value) {
    return value == null ? BigDecimal.ZERO : value;
  }

  private static String plain(BigDecimal value) {
    return value.signum() == 0 ? "0" : value.stripTrailingZeros().toPlainString();
  }
}
