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
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

import org.apache.commons.lang3.StringUtils;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.base.exception.OBException;
import org.openbravo.dal.service.OBDal;

import com.etendoerp.go.schemaforge.util.DocumentStatusGuard;
import com.etendoerp.go.schemaforge.util.NeoActionAvailability;

/**
 * The rules every "import lines from a source document into a DRAFT document" action shares
 * (ETP-5713): goods receipt / goods shipment from an order, purchase / sales invoice from an order
 * and from a receipt / shipment. Each per-entity delegate calls it explicitly; it names no entity.
 *
 * <p>What it owns, in the order a request meets it:
 * <ol>
 *   <li><b>the request</b> — {@link #parseRequestedLines}: {@code lines} is optional ("everything
 *       still pending"); when given it is exactly {@code [{sourceLineId, quantity}]}, the same line
 *       twice summed, an entry that is not understood refused (never silently skipped);</li>
 *   <li><b>the preconditions</b> — {@link #requireDraftTarget}, {@link #requireCompletedSource},
 *       {@link #requireSameBusinessPartner}, {@link #requireSameCurrency},
 *       {@link #requireSameDirection}: each a 422 with its own {@code error.code};</li>
 *   <li><b>the cap</b> — {@link #plan}: validates EVERY requested line against what is pending
 *       (drafts included, the target's own lines too) before the caller writes anything, so an
 *       over-cap request creates nothing;</li>
 *   <li><b>the header link</b> — {@link #resolveHeaderLink}: the target points at the source order
 *       only when every imported line comes from that one order;</li>
 *   <li><b>the response</b> — {@link #ok}.</li>
 * </ol>
 *
 * <p>A refusal is thrown as {@link ImportRefusal} so each delegate stays linear; the delegate's
 * {@code handle} turns it into the HTTP response. Every refusal carries
 * {@code error = {status, code, message, ...}}, the module's own shape (see
 * {@link ReturnableQuantity}).
 */
final class DocumentLineImport {

  /** The target document is not a draft. */
  static final String ERROR_TARGET_NOT_DRAFT = "importTargetNotDraft";
  /** The source document is not completed. */
  static final String ERROR_SOURCE_NOT_COMPLETED = "importSourceNotCompleted";
  /** Source and target belong to different business partners. */
  static final String ERROR_PARTNER_MISMATCH = "importBusinessPartnerMismatch";
  /** Source and target are in different currencies. */
  static final String ERROR_CURRENCY_MISMATCH = "importCurrencyMismatch";
  /** Source and target are on opposite sides (sales vs purchase) or the source is a return. */
  static final String ERROR_DIRECTION_MISMATCH = "importDirectionMismatch";
  /** More than what is still pending of a source line. */
  static final String ERROR_EXCEEDS_PENDING = "importQuantityExceedsPending";
  /** A line id that is not a line of the source document. */
  static final String ERROR_LINE_NOT_IN_DOCUMENT = "importLineNotInDocument";
  /** The source has nothing left to import. */
  static final String ERROR_NOTHING_PENDING = "importNothingPending";
  /** An entry of {@code lines} that cannot be understood. */
  static final String ERROR_INVALID_LINE = "importInvalidLine";
  /** The source or the target does not exist. */
  static final String ERROR_NOT_FOUND = "importDocumentNotFound";
  /** The target has no price list to price the imported lines with. */
  static final String ERROR_PRICE_LIST_REQUIRED = "importPriceListRequired";
  /** A stockable line was planned but the target's warehouse has no storage bin. */
  static final String ERROR_NO_STORAGE_BIN = "importNoStorageBin";
  /** The lines created do not add up to what was planned; everything is rolled back. */
  static final String ERROR_QUANTITY_NOT_HONOURED = "importQuantityNotHonoured";

  static final int SC_UNPROCESSABLE = 422;
  static final int SC_NOT_FOUND = 404;

  static final String KEY_LINES = "lines";
  static final String KEY_SOURCE_LINE_ID = "sourceLineId";
  static final String KEY_QUANTITY = "quantity";
  static final String KEY_PRODUCT = "product";
  /** Start of the {@code etendo_action} id description of every import action. */
  static final String ID_OF_THE_DRAFT = "The id of the draft ";
  /**
   * What every import action of a draft target declares (ETP-5714): it applies while the target
   * document is a draft. Advisory — {@code importXxx} still refuses a non-draft target itself.
   */
  static final NeoActionAvailability TARGET_IS_DRAFT = DocumentAvailability.WHEN_DRAFT;
  private static final String DOCUMENT = "Document ";

  private static final String KEY_RESPONSE = "response";
  private static final String KEY_ERROR = "error";
  private static final String DRAFT = "DR";

  /**
   * Row lock on one order line, taken before the pending total is read. {@code FOR NO KEY UPDATE}
   * and not {@code FOR UPDATE}: two imports of the same line still wait for each other, but the
   * lock no longer conflicts with the {@code FOR KEY SHARE} that a foreign-key insert (an invoice
   * line pointing at the order line) takes on the same row — with {@code FOR UPDATE} an import
   * that locks order lines and one that locks movement lines could wait for each other in a cycle.
   */
  private static final String LOCK_ORDER_LINE_SQL =
      "SELECT C_OrderLine_ID FROM C_OrderLine WHERE C_OrderLine_ID = ? FOR NO KEY UPDATE";
  /** Row lock on one movement line, taken before the pending total is read (see above). */
  private static final String LOCK_INOUT_LINE_SQL =
      "SELECT M_InOutLine_ID FROM M_InOutLine WHERE M_InOutLine_ID = ? FOR NO KEY UPDATE";

  private DocumentLineImport() {
  }

  // ---------------------------------------------------------------------------
  // Refusals
  // ---------------------------------------------------------------------------

  /** A refusal of the request: carries the HTTP response the delegate returns. */
  static final class ImportRefusal extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final transient NeoResponse response;
    private final String code;

    ImportRefusal(int status, String code, String message, Map<String, Object> extra) {
      super(message);
      this.code = code;
      this.response = build(status, code, message, extra);
    }

    NeoResponse response() {
      return response;
    }

    String code() {
      return code;
    }

    private static NeoResponse build(int status, String code, String message,
        Map<String, Object> extra) {
      try {
        JSONObject error = new JSONObject();
        error.put("status", status);
        error.put("code", code);
        error.put("message", message);
        if (extra != null) {
          for (Map.Entry<String, Object> entry : extra.entrySet()) {
            error.put(entry.getKey(), entry.getValue());
          }
        }
        return NeoResponse.error(status, new JSONObject().put(KEY_ERROR, error));
      } catch (JSONException e) {
        return NeoResponse.error(status, message);
      }
    }
  }

  /** A 422 carrying {@code code} and {@code message}. */
  static ImportRefusal refusal(String code, String message) {
    return new ImportRefusal(SC_UNPROCESSABLE, code, message, null);
  }

  /** A 404 for a source or target that does not exist. */
  static ImportRefusal notFound(String what, String id) {
    return new ImportRefusal(SC_NOT_FOUND, ERROR_NOT_FOUND, what + " not found: " + id, null);
  }

  // ---------------------------------------------------------------------------
  // Request
  // ---------------------------------------------------------------------------

  /**
   * The optional {@code lines} of the request, summed per source line in request order.
   *
   * @return an EMPTY map when {@code lines} is absent or empty (meaning "everything still
   *         pending"); otherwise {@code sourceLineId -> quantity}
   * @throws ImportRefusal when an entry has no {@code sourceLineId}, or a {@code quantity} that is
   *         missing, not a number or zero
   */
  static Map<String, BigDecimal> parseRequestedLines(JSONObject body) {
    JSONArray lines = body == null ? null : body.optJSONArray(KEY_LINES);
    Map<String, BigDecimal> requested = new LinkedHashMap<>();
    if (lines == null || lines.length() == 0) {
      return requested;
    }
    for (int i = 0; i < lines.length(); i++) {
      JSONObject entry = lines.optJSONObject(i);
      String sourceLineId = entry == null ? null : entry.optString(KEY_SOURCE_LINE_ID, null);
      BigDecimal quantity = entry == null ? null : ReturnableQuantity.parseQuantity(
          entry.opt(KEY_QUANTITY));
      if (StringUtils.isBlank(sourceLineId) || quantity == null || quantity.signum() == 0) {
        throw refusal(ERROR_INVALID_LINE, "lines[" + i + "] needs a sourceLineId and a quantity "
            + "other than zero");
      }
      requested.merge(sourceLineId, quantity, BigDecimal::add);
    }
    return requested;
  }

  /**
   * A body value as text, or {@code null} when absent, JSON null or blank. Jettison's
   * {@code optString} turns a JSON null into the text {@code "null"}, so it is checked first.
   */
  static String text(JSONObject body, String key) {
    String value = body == null || body.isNull(key) ? null : body.optString(key, null);
    return StringUtils.isBlank(value) ? null : value;
  }

  // ---------------------------------------------------------------------------
  // Preconditions
  // ---------------------------------------------------------------------------

  /** The target must be a draft: lines are only added to a document that is still open. */
  static void requireDraftTarget(String documentNo, String documentStatus) {
    try {
      DocumentStatusGuard.requireStatus(documentNo, documentStatus, DRAFT,
          "Document %s is not a draft (status %s): lines can only be imported into a draft");
    } catch (OBException e) {
      throw refusal(ERROR_TARGET_NOT_DRAFT, e.getMessage());
    }
  }

  /** The source must be completed: a draft or voided document commits nothing to import. */
  static void requireCompletedSource(String documentNo, String documentStatus) {
    try {
      DocumentStatusGuard.requireCompleted(documentNo, documentStatus,
          "Document %s is not completed (status %s): complete it before importing its lines");
    } catch (OBException e) {
      throw refusal(ERROR_SOURCE_NOT_COMPLETED, e.getMessage());
    }
  }

  static void requireSameBusinessPartner(String targetPartnerId, String sourcePartnerId,
      String sourceDocumentNo) {
    if (!Objects.equals(targetPartnerId, sourcePartnerId)) {
      throw refusal(ERROR_PARTNER_MISMATCH, DOCUMENT + sourceDocumentNo
          + " belongs to another business partner than the document it is imported into");
    }
  }

  /** Compared only when both documents carry a currency. */
  static void requireSameCurrency(String targetCurrencyId, String sourceCurrencyId,
      String sourceDocumentNo) {
    if (targetCurrencyId != null && sourceCurrencyId != null
        && !targetCurrencyId.equals(sourceCurrencyId)) {
      throw refusal(ERROR_CURRENCY_MISMATCH, DOCUMENT + sourceDocumentNo
          + " is in another currency than the document it is imported into");
    }
  }

  /** A sales source into a purchase target (or the reverse) is never valid. */
  static void requireSameDirection(boolean targetIsSales, boolean sourceIsSales,
      String sourceDocumentNo) {
    if (targetIsSales != sourceIsSales) {
      throw refusal(ERROR_DIRECTION_MISMATCH, DOCUMENT + sourceDocumentNo + " is a "
          + (sourceIsSales ? "sales" : "purchase") + " document and cannot be imported into a "
          + (targetIsSales ? "sales" : "purchase") + " document");
    }
  }

  // ---------------------------------------------------------------------------
  // The cap
  // ---------------------------------------------------------------------------

  /**
   * Turns the request into what will be created, validating every line first. Nothing is written by
   * the caller until this returns, so a refusal leaves no trace.
   *
   * @param requested    {@link #parseRequestedLines}; empty (or {@code null}) means every pending line in full
   * @param documentLines every line of the source document, {@code id -> product name}, so a refusal
   *                      can name a line that has nothing pending
   * @param pending      what is still importable per line, SIGNED like the line (a negative
   *                      line has a negative pending); lines with nothing pending are absent. Drafts,
   *                      the target's own lines included, are already deducted
   * @return {@code sourceLineId -> quantity to create}, signed like the pending
   */
  static Map<String, BigDecimal> plan(Map<String, BigDecimal> requested,
      Map<String, String> documentLines, Map<String, BigDecimal> pending) {
    return plan(requested, documentLines, pending, null, null);
  }

  /**
   * {@link #plan(Map, Map, Map)} for a request that names several source documents: the
   * {@code importNothingPending} and {@code importLineNotInDocument} refusals name the source
   * ({@code sourceName}, e.g. {@code "order 1000123"}, in place of "this / the source document")
   * and carry {@code sourceKeys} as extra keys, so the caller knows which document failed.
   *
   * @param sourceName the source as the message names it, or {@code null} for the generic wording
   * @param sourceKeys extra keys of those two refusals (e.g. {@code orderId}, {@code documentNo}),
   *                   or {@code null}
   */
  static Map<String, BigDecimal> plan(Map<String, BigDecimal> requested,
      Map<String, String> documentLines, Map<String, BigDecimal> pending, String sourceName,
      Map<String, Object> sourceKeys) {
    Map<String, BigDecimal> planned = new LinkedHashMap<>();
    if (requested == null || requested.isEmpty()) {
      if (pending.isEmpty()) {
        throw new ImportRefusal(SC_UNPROCESSABLE, ERROR_NOTHING_PENDING, "Nothing is left to "
            + "import from " + (sourceName == null ? "this document" : sourceName)
            + ": every line is already covered", sourceKeys);
      }
      planned.putAll(pending);
      return planned;
    }
    for (Map.Entry<String, BigDecimal> entry : requested.entrySet()) {
      String id = entry.getKey();
      if (!documentLines.containsKey(id)) {
        throw new ImportRefusal(SC_UNPROCESSABLE, ERROR_LINE_NOT_IN_DOCUMENT, "Line " + id
            + " is not a line of " + (sourceName == null ? "the source document" : sourceName),
            sourceKeys);
      }
      BigDecimal available = pending.getOrDefault(id, BigDecimal.ZERO);
      if (!fits(entry.getValue(), available)) {
        throw exceeds(id, documentLines.get(id), entry.getValue(), available);
      }
      planned.put(id, entry.getValue());
    }
    return planned;
  }

  /** True when {@code requested} has the sign of {@code available} and does not exceed it. */
  static boolean fits(BigDecimal requested, BigDecimal available) {
    return available.signum() != 0 && requested.signum() == available.signum()
        && requested.abs().compareTo(available.abs()) <= 0;
  }

  /** The 422 for a quantity above what is pending, naming the product and both quantities. */
  static ImportRefusal exceeds(String sourceLineId, String product, BigDecimal requested,
      BigDecimal pending) {
    BigDecimal shown = pending.signum() == 0 ? BigDecimal.ZERO : pending;
    String message = "Cannot import " + plain(requested) + " of " + product + ": "
        + (shown.signum() == 0 ? "nothing is pending on this line"
            : "only " + plain(shown) + " is still pending on this line")
        + " (quantities already in draft documents count as taken).";
    Map<String, Object> extra = new LinkedHashMap<>();
    extra.put(KEY_PRODUCT, product);
    extra.put("requested", requested);
    extra.put("pending", shown);
    extra.put(KEY_SOURCE_LINE_ID, sourceLineId);
    return new ImportRefusal(SC_UNPROCESSABLE, ERROR_EXCEEDS_PENDING, message, extra);
  }

  /** The subset of {@code all} that {@code planned} selects, in {@code planned} order. */
  static <T> List<T> selectPlanned(Map<String, BigDecimal> planned, Map<String, T> all) {
    List<T> selected = new ArrayList<>();
    for (String id : planned.keySet()) {
      T item = all.get(id);
      if (item != null) {
        selected.add(item);
      }
    }
    return selected;
  }

  // ---------------------------------------------------------------------------
  // Locks
  // ---------------------------------------------------------------------------

  /**
   * Locks the order lines ({@code SELECT ... FOR NO KEY UPDATE}, ascending id order) BEFORE the pending
   * totals are read, on the DAL's own connection: two concurrent imports of the same order line
   * serialise here, and the second reads a total that includes the first one's lines.
   */
  static void lockOrderLines(Collection<String> ids) throws SQLException {
    lock(LOCK_ORDER_LINE_SQL, ids);
  }

  /** Same lock on movement (receipt / shipment) lines. */
  static void lockInOutLines(Collection<String> ids) throws SQLException {
    lock(LOCK_INOUT_LINE_SQL, ids);
  }

  private static void lock(String sql, Collection<String> ids) throws SQLException {
    if (ids == null || ids.isEmpty()) {
      return;
    }
    Connection conn = OBDal.getInstance().getConnection();
    try (PreparedStatement ps = conn.prepareStatement(sql)) {
      for (String id : new TreeSet<>(ids)) {
        ps.setString(1, id);
        try (ResultSet rs = ps.executeQuery()) {
          rs.next();
        }
      }
    }
  }

  // ---------------------------------------------------------------------------
  // Header link
  // ---------------------------------------------------------------------------

  /** What to do with the target header's order link once the lines are in. */
  static final class HeaderLink {
    static final HeaderLink KEEP = new HeaderLink(Action.KEEP, null);
    static final HeaderLink CLEAR = new HeaderLink(Action.CLEAR, null);

    enum Action { KEEP, LINK, CLEAR }

    private final Action action;
    private final String orderId;

    private HeaderLink(Action action, String orderId) {
      this.action = action;
      this.orderId = orderId;
    }

    static HeaderLink link(String orderId) {
      return new HeaderLink(Action.LINK, orderId);
    }

    Action action() {
      return action;
    }

    String orderId() {
      return orderId;
    }
  }

  /**
   * The header points at the source order only while every line of the target comes from that ONE
   * order, as the window does when one order was imported. With one order the link is set when the
   * header has none or the same one; with several the header is left empty, so a link this feature
   * set earlier is cleared once a second order joins; a link to an order none of the lines comes
   * from is somebody else's and is kept.
   *
   * @param currentOrderId  the header's order now, or {@code null}
   * @param lineOrderIds    the distinct orders of every active line of the target, after the import
   */
  static HeaderLink resolveHeaderLink(String currentOrderId, Set<String> lineOrderIds) {
    if (lineOrderIds.size() == 1) {
      String only = lineOrderIds.iterator().next();
      if (currentOrderId == null || currentOrderId.equals(only)) {
        return HeaderLink.link(only);
      }
      return HeaderLink.KEEP;
    }
    if (lineOrderIds.size() > 1 && currentOrderId != null
        && lineOrderIds.contains(currentOrderId)) {
      return HeaderLink.CLEAR;
    }
    return HeaderLink.KEEP;
  }

  // ---------------------------------------------------------------------------
  // Response
  // ---------------------------------------------------------------------------

  /** One line created by an import, as the response reports it. */
  static final class ImportedLine {
    final String id;
    final String sourceLineId;
    final String productId;
    final String productName;
    final BigDecimal quantity;
    /** The order the line belongs to (through its order line), or {@code null}. */
    final String orderId;

    ImportedLine(String id, String sourceLineId, String productId, String productName,
        BigDecimal quantity, String orderId) {
      this.orderId = orderId;
      this.id = id;
      this.sourceLineId = sourceLineId;
      this.productId = productId;
      this.productName = productName;
      this.quantity = quantity;
    }
  }

  /**
   * {@code {response:{data:{importedCount, lines:[{id, sourceLineId, product, productName,
   * quantity}], headerLinked, salesOrder?}}}} — the envelope of the module's other import actions.
   *
   * <p>The header link is reported as it is: {@code salesOrder} is the order the header points at
   * NOW (when it has one) and {@code headerLinked} is {@code true} only when an imported line
   * belongs to that order, so the answer never claims a link to an order none of the lines is from.
   *
   * @param headerOrderId the order the target's header points at after the import, or {@code null}
   */
  static NeoResponse ok(List<ImportedLine> lines, String headerOrderId) throws JSONException {
    JSONArray array = new JSONArray();
    boolean linked = false;
    for (ImportedLine line : lines) {
      JSONObject row = new JSONObject();
      row.put("id", line.id);
      row.put(KEY_SOURCE_LINE_ID, line.sourceLineId);
      row.put(KEY_PRODUCT, line.productId);
      row.put("productName", line.productName);
      row.put(KEY_QUANTITY, line.quantity);
      array.put(row);
      linked |= headerOrderId != null && headerOrderId.equals(line.orderId);
    }
    JSONObject data = new JSONObject();
    data.put("importedCount", lines.size());
    data.put(KEY_LINES, array);
    data.put("headerLinked", linked);
    if (headerOrderId != null) {
      data.put("salesOrder", headerOrderId);
    }
    return wrap(data);
  }

  /**
   * A discovery page: {@code {response:{data:[...], hasMore, limit, scanLimit}}}. A discovery scans
   * a bounded number of candidates (newest first) and returns a bounded number of rows, so what it
   * lists is not always everything; {@code hasMore} says whether something may have been left out
   * and the limits say how far it looked.
   */
  static NeoResponse wrapPage(JSONArray rows, boolean hasMore, int limit, int scanLimit)
      throws JSONException {
    JSONObject responseData = new JSONObject();
    responseData.put("data", rows);
    responseData.put("hasMore", hasMore);
    responseData.put("limit", limit);
    responseData.put("scanLimit", scanLimit);
    return NeoResponse.ok(new JSONObject().put(KEY_RESPONSE, responseData));
  }

  /** {@code {response:{data:<data>}}} with a 200. */
  static NeoResponse wrap(Object data) throws JSONException {
    JSONObject responseData = new JSONObject();
    responseData.put("data", data);
    return NeoResponse.ok(new JSONObject().put(KEY_RESPONSE, responseData));
  }

  /** One row of an {@code available*Lines} discovery answer. */
  static JSONObject lineRow(String id, Long lineNo, String productId, String productName,
      String uomId, BigDecimal pending) throws JSONException {
    JSONObject row = new JSONObject();
    row.put("id", id);
    row.put("lineNo", lineNo == null ? JSONObject.NULL : lineNo);
    row.put(KEY_PRODUCT, productId == null ? JSONObject.NULL : productId);
    row.put("product$_identifier", productName == null ? JSONObject.NULL : productName);
    row.put("uOM", uomId == null ? JSONObject.NULL : uomId);
    row.put("pendingQuantity", pending);
    return row;
  }

  private static String plain(BigDecimal value) {
    BigDecimal stripped = value.stripTrailingZeros();
    return (stripped.scale() < 0 ? stripped.setScale(0) : stripped).toPlainString();
  }

  /** {@code true} when {@code status} is the draft status. */
  static boolean isDraft(String status) {
    return DRAFT.equals(status);
  }
}
