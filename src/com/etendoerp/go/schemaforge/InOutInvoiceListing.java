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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.model.common.invoice.Invoice;
import org.openbravo.model.common.order.Order;
import org.openbravo.model.common.order.OrderLine;
import org.openbravo.model.materialmgmt.transaction.ShipmentInOut;
import org.openbravo.model.materialmgmt.transaction.ShipmentInOutLine;

/**
 * The listing actions of {@link ImportInOutLinesIntoInvoiceHandler} (the documents and lines of the
 * goods shipment / receipt actions and of the return actions, server and
 * {@code pendingBasis: "classic"} rules), moved unchanged out of the handler (ETP-6099, the handler
 * was past the method count Sonar accepts). Every DAL read goes back through the handler's seams,
 * so what a test overrides on the handler is still what runs.
 */
final class InOutInvoiceListing {

  private static final String KEY_ORDER = "order";
  private static final String KEY_ORDER_LINE = "orderLine";
  private static final String KEY_MOVEMENT_QUANTITY = "movementQuantity";

  private final ImportInOutLinesIntoInvoiceHandler handler;

  InOutInvoiceListing(ImportInOutLinesIntoInvoiceHandler handler) {
    this.handler = handler;
  }

  /** What a lines call asked for (ETP-6099). */
  static final class LineListing {
    final boolean includeCovered;
    final boolean detail;

    LineListing(boolean includeCovered, boolean detail) {
      this.includeCovered = includeCovered;
      this.detail = detail;
    }
  }

  /** What the {@code detail} keys of the lines of one document are read from. */
  static final class LineDetail {
    /** Standard: per movement line, {@code [importedHere, invoicedElsewhere]}. */
    final Map<String, boolean[]> links;
    /** Returns: the movement lines this invoice holds. */
    final Set<String> inTarget;
    /** Returns (sales): the order lines already invoiced through a movement line or here. */
    final Set<String> blocked;
    final boolean presenceOnly;

    LineDetail(Map<String, boolean[]> links, Set<String> inTarget, Set<String> blocked,
        boolean presenceOnly) {
      this.links = links;
      this.inTarget = inTarget;
      this.blocked = blocked;
      this.presenceOnly = presenceOnly;
    }
  }

  /**
   * What a return line can still be credited, as a POSITIVE magnitude, computed as the modal of the
   * invoice's side does (ETP-5876), whichever sign the return stored:
   * <ul>
   *   <li><b>purchase</b> ({@code ImportFromGoodsReturnModal}): the movement quantity, and a line
   *       is left out only when THIS invoice already holds a line for it; drafts and completed
   *       invoices of other documents are not counted;</li>
   *   <li><b>sales</b> ({@code ImportFromReturnShipmentModal}): the movement quantity minus the
   *       quantity of EVERY invoice line linked to the return line, whatever the status of its
   *       invoice (voided ones included), this invoice's own lines among them; and a line whose
   *       order line is already on an invoice line that is linked to a movement line, or is on this
   *       invoice, is not offered at all.</li>
   * </ul>
   */
  Map<String, BigDecimal> returnPending(Invoice invoice, ShipmentInOut source,
      Map<String, ShipmentInOutLine> lines, RectificativeImportPolicy policy) {
    boolean presenceOnly = policy.capsReturnsByPresence();
    Map<String, BigDecimal> credited = presenceOnly ? Map.of()
        : handler.creditedQuantities(source.getId());
    Set<String> blocked = presenceOnly ? Set.of()
        : handler.blockedOrderLines(source.getId(), invoice.getId());
    return ReturnLineCredit.pending(lines, ReturnLineCredit.movementLineIdsOf(invoice),
        presenceOnly, credited, blocked);
  }

  // ---------------------------------------------------------------------------
  // available documents / lines
  // ---------------------------------------------------------------------------

  NeoResponse availableDocuments(Invoice invoice, boolean sales, boolean returns,
      RectificativeImportPolicy policy, ImportListing.ListRequest request) throws JSONException {
    String partnerId = invoice.getBusinessPartner() == null ? null
        : invoice.getBusinessPartner().getId();
    String currencyId = invoice.getCurrency() == null ? null : invoice.getCurrency().getId();
    List<ShipmentInOut> candidates = candidates(partnerId, sales, currencyId, returns, request);
    return handler.discover(candidates,
        doc -> availableDocumentRow(invoice, doc, returns, policy, request, partnerId), request);
  }

  /** One row of {@link #availableDocuments}, or {@code null} when the document is not listed. */
  private JSONObject availableDocumentRow(Invoice invoice, ShipmentInOut doc, boolean returns,
      RectificativeImportPolicy policy, ImportListing.ListRequest request, String partnerId)
      throws JSONException {
    int pendingLines = listedPendingLines(invoice, doc, returns, policy, request);
    if (pendingLines == 0 && !(request.includeCovered
        && InOutInvoiceSourceRules.isReturn(doc) == returns
        && isCovered(invoice, doc, returns, policy))) {
      return null;
    }
    return documentRowOf(doc, partnerId, pendingLines, returns, request);
  }

  /**
   * The {@code pendingLines} of one candidate: 0 for a document of the other kind (a return in a
   * shipment / receipt listing or the reverse); a return's offered lines; a shipment / receipt's
   * pending lines, read once per candidate and, only with {@code detail} (ETP-6099), narrowed to
   * what the popup offers once expanded, so a listed document never turns out empty.
   */
  private int listedPendingLines(Invoice invoice, ShipmentInOut doc, boolean returns,
      RectificativeImportPolicy policy, ImportListing.ListRequest request) {
    if (InOutInvoiceSourceRules.isReturn(doc) != returns) {
      return 0;
    }
    if (returns) {
      return offeredReturnLineCount(invoice, doc, policy);
    }
    Map<String, BigDecimal> magnitudes = handler.pendingQuantities(doc.getId());
    int pendingLines = magnitudes.size();
    if (request.detail && pendingLines > 0) {
      pendingLines = selectableLineCount(invoice, doc, magnitudes, policy);
    }
    return pendingLines;
  }

  /** The row of a listed document, both rules: with {@code detail}, its detail keys. */
  private JSONObject documentRowOf(ShipmentInOut doc, String partnerId, int pendingLines,
      boolean returns, ImportListing.ListRequest request) throws JSONException {
    String docCurrency = doc.getEtgoCurrency() == null ? null : doc.getEtgoCurrency().getId();
    JSONObject row = AbstractLineImportHandler.documentRow(doc.getId(), doc.getDocumentNo(),
        AbstractLineImportHandler.day(doc.getMovementDate()), partnerId,
        doc.getBusinessPartner().getName(), docCurrency, pendingLines);
    if (request.detail) {
      putDocumentDetail(row, doc, returns);
    }
    return row;
  }

  /**
   * The candidates of one list call: the delegate's own seams on a first page that asked for no
   * longer scan (what the unit tests override), the paged reads otherwise (ETP-6099).
   */
  private List<ShipmentInOut> candidates(String partnerId, boolean sales, String currencyId,
      boolean returns, ImportListing.ListRequest request) {
    if (request.isDefault(handler.scanLimit())) {
      return returns ? handler.findCandidateReturns(partnerId, sales)
          : handler.findCandidateDocuments(partnerId, sales, currencyId);
    }
    return returns ? handler.findCandidateReturns(partnerId, sales, request.offset, request.scan)
        : handler.findCandidateDocuments(partnerId, sales, currencyId, request.offset,
            request.scan);
  }

  /**
   * {@code includeCovered} (ETP-6099): a document with no line left that the window's popup still
   * lists. A shipment / receipt with something pending before drafts (drafts hold all of it); a
   * return not invoiced in full and, where the side compares it, in the invoice's currency.
   */
  private boolean isCovered(Invoice invoice, ShipmentInOut doc, boolean returns,
      RectificativeImportPolicy policy) {
    if (!returns) {
      return !handler.pendingBeforeDrafts(doc.getId()).isEmpty();
    }
    return !new InOutInvoiceSourceRules(handler).isInvoicedInFull(doc)
        && !InOutInvoiceSourceRules.returnCurrencyMismatch(invoice, doc, policy);
  }

  /**
   * The lines of a shipment / receipt the popup leaves selectable (ETP-6099, {@code detail}):
   * something pending, not already held by THIS invoice (through the movement line or its order
   * line) and, where the side greys them ({@link RectificativeImportPolicy
   * #excludesLinesInvoicedElsewhere}), not linked to another invoice.
   */
  private int selectableLineCount(Invoice invoice, ShipmentInOut doc,
      Map<String, BigDecimal> magnitudes, RectificativeImportPolicy policy) {
    Map<String, boolean[]> links = handler.invoiceLinkStates(doc.getId(), invoice.getId());
    boolean excludesElsewhere = policy.excludesLinesInvoicedElsewhere();
    int count = 0;
    for (String lineId : ImportInOutLinesIntoInvoiceHandler.signedPending(activeLines(doc),
        magnitudes).keySet()) {
      boolean[] state = links.getOrDefault(lineId, new boolean[2]);
      if (!state[0] && !(excludesElsewhere && state[1])) {
        count++;
      }
    }
    return count;
  }

  /** The {@code detail} keys of a document row (ETP-6099). */
  private void putDocumentDetail(JSONObject row, ShipmentInOut doc, boolean returns)
      throws JSONException {
    ImportListing.putCreationDate(row, doc.getCreationDate());
    Order order = doc.getSalesOrder();
    ImportListing.putNullable(row, KEY_ORDER, order == null ? null : order.getId());
    if (returns) {
      ImportListing.putNullable(row, "sourceDocumentNo", handler.sourceDocumentNos(doc.getId()));
    } else {
      ImportListing.putNullable(row, "orderDocumentNo",
          order == null ? null : order.getDocumentNo());
    }
  }

  /**
   * How many lines of a return the import offers, judged as the window's modal judges it: not
   * already invoiced in full, in the invoice's currency when the side compares it, and with a line
   * left to credit ({@link #returnPending}). A shipment / receipt counts its
   * {@link ImportInOutLinesIntoInvoiceHandler#pendingQuantities} in the list action itself, read
   * once per candidate.
   */
  private int offeredReturnLineCount(Invoice invoice, ShipmentInOut doc,
      RectificativeImportPolicy policy) {
    if (new InOutInvoiceSourceRules(handler).isInvoicedInFull(doc)
        || InOutInvoiceSourceRules.returnCurrencyMismatch(invoice, doc, policy)) {
      return 0;
    }
    return returnPending(invoice, doc, activeLines(doc), policy).size();
  }

  static Map<String, ShipmentInOutLine> activeLines(ShipmentInOut doc) {
    Map<String, ShipmentInOutLine> lines = new LinkedHashMap<>();
    for (ShipmentInOutLine line : doc.getMaterialMgmtShipmentInOutLineList()) {
      if (line.isActive()) {
        lines.put(line.getId(), line);
      }
    }
    return lines;
  }

  NeoResponse availableLines(Invoice invoice, ShipmentInOut source, boolean returns,
      RectificativeImportPolicy policy, LineListing listing) throws JSONException {
    Map<String, ShipmentInOutLine> linesById = activeLines(source);
    Map<String, BigDecimal> pending = returns
        ? returnPending(invoice, source, linesById, policy)
        : ImportInOutLinesIntoInvoiceHandler.signedPending(linesById,
            handler.pendingQuantities(source.getId()));
    LineDetail detail = listing.detail ? lineDetail(invoice, source, returns, policy) : null;
    JSONArray rows = new JSONArray();
    for (ShipmentInOutLine line : linesById.values()) {
      BigDecimal left = listedLeft(line, pending, listing.includeCovered);
      if (left != null) {
        rows.put(lineRowOf(line, left, returns, detail));
      }
    }
    return handler.completePage(rows);
  }

  /**
   * What a line row shows as pending: its pending, or 0 for a line with a quantity and nothing left
   * when {@code includeCovered}; {@code null} when the line is not listed.
   */
  private static BigDecimal listedLeft(ShipmentInOutLine line, Map<String, BigDecimal> pending,
      boolean includeCovered) {
    BigDecimal left = pending.get(line.getId());
    if (left == null && includeCovered && hasQuantity(line)) {
      return BigDecimal.ZERO;
    }
    return left;
  }

  /** The row of a listed line, both rules: the {@code detail} keys when {@code detail} is set. */
  private static JSONObject lineRowOf(ShipmentInOutLine line, BigDecimal left, boolean returns,
      LineDetail detail) throws JSONException {
    JSONObject row = DocumentLineImport.lineRow(line.getId(), line.getLineNo(),
        line.getProduct() == null ? null : line.getProduct().getId(),
        line.getProduct() == null ? null : line.getProduct().getName(),
        line.getUOM() == null ? null : line.getUOM().getId(), left);
    if (detail != null) {
      putLineDetail(row, line, left, returns, detail);
    }
    return row;
  }

  private static boolean hasQuantity(ShipmentInOutLine line) {
    return line.getMovementQuantity() != null && line.getMovementQuantity().signum() != 0;
  }

  private LineDetail lineDetail(Invoice invoice, ShipmentInOut source, boolean returns,
      RectificativeImportPolicy policy) {
    if (!returns) {
      return new LineDetail(handler.invoiceLinkStates(source.getId(), invoice.getId()), Set.of(),
          Set.of(), false);
    }
    boolean presenceOnly = policy.capsReturnsByPresence();
    return new LineDetail(Map.of(), ReturnLineCredit.movementLineIdsOf(invoice),
        presenceOnly ? Set.of() : handler.blockedOrderLines(source.getId(), invoice.getId()),
        presenceOnly);
  }

  /** The {@code detail} keys of one line row (ETP-6099). */
  private static void putLineDetail(JSONObject row, ShipmentInOutLine line, BigDecimal left,
      boolean returns, LineDetail detail) throws JSONException {
    OrderLine orderLine = line.getSalesOrderLine();
    BigDecimal movement = line.getMovementQuantity();
    ImportListing.putNullable(row, KEY_MOVEMENT_QUANTITY,
        returns && movement != null ? movement.abs() : movement);
    ImportListing.putNullable(row, KEY_ORDER_LINE, orderLine == null ? null : orderLine.getId());
    row.put("orderLineDiscount", ImportListing.trimmed(orderLine == null ? null
        : orderLine.getDiscount()));
    if (!returns) {
      boolean[] state = detail.links.getOrDefault(line.getId(), new boolean[2]);
      row.put(ImportInOutLinesIntoInvoiceHandler.REASON_IMPORTED_HERE, state[0]);
      row.put("invoicedElsewhere", state[1]);
      return;
    }
    ImportListing.putNullable(row, "blockedReason",
        left.signum() != 0 ? null : blockedReason(line, orderLine, detail));
  }

  /** Why a return line is not offered, as the window's modal of the side would judge it. */
  private static String blockedReason(ShipmentInOutLine line, OrderLine orderLine,
      LineDetail detail) {
    if (detail.presenceOnly) {
      return detail.inTarget.contains(line.getId())
          ? ImportInOutLinesIntoInvoiceHandler.REASON_IMPORTED_HERE : null;
    }
    if (orderLine != null && detail.blocked.contains(orderLine.getId())) {
      return ImportInOutLinesIntoInvoiceHandler.REASON_ORDER_LINE_INVOICED;
    }
    return ImportInOutLinesIntoInvoiceHandler.REASON_CREDITED_IN_FULL;
  }

  // ---------------------------------------------------------------------------
  // pendingBasis "classic" (ETP-6099 follow-up)
  // ---------------------------------------------------------------------------

  /**
   * {@code availableReceipts} with the purchase popup's own rule from before ETP-6099
   * ({@code ImportFromGoodsReceiptModal.jsx}, {@code fetchDocuments}): the partner's completed
   * receipts, newest created first, whose header order is in the invoice's currency (a receipt
   * without an order is never excluded by currency, the popup could not compare it); returns are
   * left out (never importable here). NOTHING deducts what other invoices hold, completed or draft:
   * a receipt invoiced elsewhere is listed again, as it was. {@code pendingLines} counts the lines
   * the popup leaves selectable: a quantity, and not already held by THIS invoice (through the
   * receipt line or its order line); a receipt left at 0 is listed only with {@code includeCovered}.
   */
  NeoResponse classicAvailableDocuments(Invoice invoice, ImportListing.ListRequest request)
      throws JSONException {
    String partnerId = invoice.getBusinessPartner() == null ? null
        : invoice.getBusinessPartner().getId();
    String currencyId = invoice.getCurrency() == null ? null : invoice.getCurrency().getId();
    List<ShipmentInOut> candidates = handler.findClassicCandidateDocuments(partnerId,
        InOutInvoiceSourceRules.isSales(invoice), currencyId, request.offset, request.scan);
    return handler.discover(candidates, doc -> {
      if (InOutInvoiceSourceRules.isReturn(doc)) {
        return null;
      }
      int pendingLines = countClassicPendingLines(doc,
          handler.invoiceLinkStates(doc.getId(), invoice.getId()));
      if (pendingLines == 0 && !request.includeCovered) {
        return null;
      }
      return documentRowOf(doc, partnerId, pendingLines, false, request);
    }, request);
  }

  /**
   * {@code availableReceiptLines} with the old popup's rule ({@code fetchLines}): every active line
   * at its FULL movement quantity, unless THIS invoice already holds it (an active line linked to
   * the receipt line or to its order line: {@code importedHere}, pending 0). Nothing deducts other
   * invoices. A line at 0 is listed only with {@code includeCovered}; {@code detail} adds the
   * standard keys ({@code movementQuantity}, {@code importedHere}, ...).
   */
  NeoResponse classicAvailableLines(Invoice invoice, ShipmentInOut source, LineListing listing)
      throws JSONException {
    Map<String, boolean[]> links = handler.invoiceLinkStates(source.getId(), invoice.getId());
    LineDetail detail = new LineDetail(links, Set.of(), Set.of(), false);
    JSONArray rows = new JSONArray();
    for (ShipmentInOutLine line : activeLines(source).values()) {
      BigDecimal left = classicPendingOf(line, links);
      if (left.signum() == 0 && !listing.includeCovered) {
        continue;
      }
      rows.put(lineRowOf(line, left, false, listing.detail ? detail : null));
    }
    return handler.completePage(rows);
  }

  /** How many active lines of {@code doc} keep a classic pending other than 0. */
  private static int countClassicPendingLines(ShipmentInOut doc, Map<String, boolean[]> links) {
    int pendingLines = 0;
    for (ShipmentInOutLine line : activeLines(doc).values()) {
      pendingLines += classicPendingOf(line, links).signum() != 0 ? 1 : 0;
    }
    return pendingLines;
  }

  /** The classic pending of a receipt line: its movement quantity, or 0 when held here. */
  private static BigDecimal classicPendingOf(ShipmentInOutLine line,
      Map<String, boolean[]> links) {
    boolean[] state = links.get(line.getId());
    boolean importedHere = state != null && state[0];
    return importedHere || !hasQuantity(line) ? BigDecimal.ZERO : line.getMovementQuantity();
  }
}
