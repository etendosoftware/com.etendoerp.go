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
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.inject.Named;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.invoice.Invoice;
import org.openbravo.model.materialmgmt.transaction.ShipmentInOut;
import org.openbravo.model.common.invoice.InvoiceLine;
import org.openbravo.model.materialmgmt.transaction.ShipmentInOutLine;

import com.etendoerp.go.schemaforge.util.NeoActionContract;

/**
 * Imports the pending lines of a completed goods receipt (purchase invoice) or goods shipment (sales
 * invoice) into a DRAFT invoice (ETP-5713), and lists what can be imported. Purchase:
 * {@code availableReceipts}, {@code availableReceiptLines}, {@code importReceiptLines}. Sales:
 * {@code availableShipments}, {@code availableShipmentLines}, {@code importShipmentLines}. The
 * direction comes from the action name and is checked against the invoice, never read from the
 * entity.
 *
 * <p><b>The cap</b> is {@link NeoInvoiceSupport#computePendingQtyPerLineOrThrow} with drafts
 * included: the movement quantity minus what completed invoices, match rows and DRAFT invoices
 * (the target's own lines too) already cover. The window's modal offered the full movement
 * quantity; the server applies the real pending, so a line is never invoiced twice. The movement
 * lines are locked ({@code FOR UPDATE}) before the total is read.
 *
 * <p>Lines are created by the native process with {@code ShipmentInOutLine} as the source, the path
 * the multi-receipt / multi-shipment one-step actions use: prices, tax and UOM come from the linked
 * order line (or the invoice's price list for a line without one), and the quantity is exactly the
 * one asked for. Return documents are not importable here (their own flow credits them).
 */
@Named("importInOutLinesIntoInvoiceHandler")
public class ImportInOutLinesIntoInvoiceHandler extends AbstractInvoiceLineImportHandler {

  static final String ACTION_AVAILABLE_RECEIPTS = "availableReceipts";
  static final String ACTION_AVAILABLE_RECEIPT_LINES = "availableReceiptLines";
  static final String ACTION_IMPORT_RECEIPT_LINES = "importReceiptLines";
  static final String ACTION_AVAILABLE_SHIPMENTS = "availableShipments";
  static final String ACTION_AVAILABLE_SHIPMENT_LINES = "availableShipmentLines";
  static final String ACTION_IMPORT_SHIPMENT_LINES = "importShipmentLines";

  private static final String PARAM_RECEIPT_ID = "receiptId";
  private static final String PARAM_SHIPMENT_ID = "shipmentId";
  private static final Set<String> ACTIONS = Set.of(ACTION_AVAILABLE_RECEIPTS,
      ACTION_AVAILABLE_RECEIPT_LINES, ACTION_IMPORT_RECEIPT_LINES, ACTION_AVAILABLE_SHIPMENTS,
      ACTION_AVAILABLE_SHIPMENT_LINES, ACTION_IMPORT_SHIPMENT_LINES);
  private static final Set<String> SALES_ACTIONS = Set.of(ACTION_AVAILABLE_SHIPMENTS,
      ACTION_AVAILABLE_SHIPMENT_LINES, ACTION_IMPORT_SHIPMENT_LINES);
  private static final Set<String> LIST_ACTIONS =
      Set.of(ACTION_AVAILABLE_RECEIPTS, ACTION_AVAILABLE_SHIPMENTS);
  private static final Set<String> LINES_ACTIONS =
      Set.of(ACTION_AVAILABLE_RECEIPT_LINES, ACTION_AVAILABLE_SHIPMENT_LINES);
  private static final int MAX_CANDIDATES = 200;
  private static final int MAX_DOCUMENTS = 50;

  /**
   * The three contracts of the invoice this delegate is injected in, in flow order.
   *
   * @param sales {@code true} for a sales invoice (shipments), {@code false} for a purchase invoice
   *              (receipts)
   */
  static Map<String, NeoActionContract> contracts(boolean sales) {
    String inout = sales ? "goods shipment" : "goods receipt";
    String doc = sales ? "sales invoice" : "purchase invoice";
    String list = sales ? ACTION_AVAILABLE_SHIPMENTS : ACTION_AVAILABLE_RECEIPTS;
    String listLines = sales ? ACTION_AVAILABLE_SHIPMENT_LINES : ACTION_AVAILABLE_RECEIPT_LINES;
    String imp = sales ? ACTION_IMPORT_SHIPMENT_LINES : ACTION_IMPORT_RECEIPT_LINES;
    String param = sales ? PARAM_SHIPMENT_ID : PARAM_RECEIPT_ID;
    String oneStep = sales ? "createDraftInvoice on the goods shipment"
        : "createPurchaseInvoice on the goods receipt";
    String flow = " Flow: create the draft " + doc + " header with etendo_create (business partner "
        + "and the rest defaulted; etendo_schema view:\"create\" is the authority), call " + list
        + ", then " + listLines + ", then " + imp + " on that header, and complete it with "
        + "documentAction (CO). To invoice a whole " + inout + " in ONE step (created AND "
        + "completed, no draft to fill) call " + oneStep + " instead.";
    Map<String, NeoActionContract> contracts = new LinkedHashMap<>();
    contracts.put(list, NeoActionContract.read(list,
        "Lists the completed " + inout + "s of this " + doc + "'s business partner and currency "
            + "that still have quantity to invoice: {data:[{id, documentNo, date, businessPartner, "
            + "businessPartner$_identifier, currency, pendingLines}], hasMore, limit, scanLimit}. hasMore true means the list may be incomplete: it looks at the scanLimit most recent candidates and returns at most limit documents, so a document it does not list may still be importable when you know its id. Pending is what completed "
            + "AND draft invoices have not covered. Step 1." + flow)
        .withIdDescription(DocumentLineImport.ID_OF_THE_DRAFT + doc + " the lines would be imported into.")
        .availableWhen(DocumentLineImport.TARGET_IS_DRAFT));
    contracts.put(listLines, NeoActionContract.read(listLines,
        "Lists the lines of a " + inout + " that can still be invoiced here: {data:[{id, lineNo, "
            + "product, product$_identifier, uOM, pendingQuantity}], hasMore:false}, where pendingQuantity is "
            + "what no completed or draft invoice (this one included) covers, and the id is the "
            + "sourceLineId " + imp + " takes. Step 2." + flow,
        NeoActionContract.Param.required(param, NeoActionContract.TYPE_STRING,
            "Id of a document returned by " + list + "."))
        .withIdDescription(DocumentLineImport.ID_OF_THE_DRAFT + doc + " the lines would be imported into.")
        .availableWhen(DocumentLineImport.TARGET_IS_DRAFT));
    contracts.put(imp, NeoActionContract.write(imp,
        "Adds lines of a completed " + inout + " to this DRAFT " + doc + " (product, unit of "
            + "measure, tax, price and discount from its order line, linked to the " + inout
            + " line), in ONE transaction, WITHOUT completing the invoice. Without lines it imports "
            + "everything still pending; with lines, exactly those. More than what is pending on a "
            + "line (completed and draft invoices counted, this invoice's own lines too; the same "
            + "line listed twice counts both) answers 422 importQuantityExceedsPending naming the "
            + "product, the requested and the pending quantity, and creates NOTHING. The invoice's "
            + "order reference is set when every line comes from one order and left empty when "
            + "several are mixed. Refused (422) when the invoice is not a draft, the " + inout
            + " is not completed or is a return, or business partner, currency or direction "
            + "differ. Returns {data:{importedCount, lines:[{id, sourceLineId, product, "
            + "productName, quantity}], headerLinked, salesOrder}} (salesOrder is the order the header points at now; headerLinked is true only when an imported line belongs to it). Step 3." + flow,
        NeoActionContract.Param.required(param, NeoActionContract.TYPE_STRING,
            "Id of the " + inout + " to import from, returned by " + list + "."),
        NeoActionContract.Param.array(DocumentLineImport.KEY_LINES,
            NeoActionContract.TYPE_OBJECT, false,
            "Each {sourceLineId, quantity}: sourceLineId is the id of a line from " + listLines
                + ", quantity a number not above its pendingQuantity. Omit to import every "
                + "pending line in full."))
        .withIdDescription(DocumentLineImport.ID_OF_THE_DRAFT + doc + " the lines are added to.")
        .availableWhen(DocumentLineImport.TARGET_IS_DRAFT));
    return contracts;
  }

  @Override
  protected int scanLimit() {
    return MAX_CANDIDATES;
  }

  @Override
  protected int rowLimit() {
    return MAX_DOCUMENTS;
  }

  @Override
  protected Set<String> actions() {
    return ACTIONS;
  }

  @Override
  protected NeoResponse execute(NeoContext context, String action) throws Exception {
    boolean sales = SALES_ACTIONS.contains(action);
    JSONObject body = context.getRequestBody();
    String idParam = sales ? PARAM_SHIPMENT_ID : PARAM_RECEIPT_ID;
    if (LIST_ACTIONS.contains(action)) {
      return availableDocuments(requireInvoiceOfDirection(context.getRecordId(), sales, false),
          sales);
    }
    if (LINES_ACTIONS.contains(action)) {
      Invoice invoice = requireInvoiceOfDirection(context.getRecordId(), sales, false);
      return availableLines(requireSource(invoice, requireParam(body, idParam)));
    }
    return importLines(requireInvoiceOfDirection(context.getRecordId(), sales, true), body,
        idParam);
  }

  // ---------------------------------------------------------------------------
  // import
  // ---------------------------------------------------------------------------

  private NeoResponse importLines(Invoice invoice, JSONObject body, String idParam)
      throws SQLException, JSONException {
    Map<String, BigDecimal> requested = DocumentLineImport.parseRequestedLines(body);
    ShipmentInOut source = requireSource(invoice, requireParam(body, idParam));

    Map<String, ShipmentInOutLine> linesById = new LinkedHashMap<>();
    Map<String, String> documentLines = new LinkedHashMap<>();
    for (ShipmentInOutLine line : source.getMaterialMgmtShipmentInOutLineList()) {
      if (line.isActive()) {
        linesById.put(line.getId(), line);
        documentLines.put(line.getId(), label(line));
      }
    }
    lockInOutLines(linesById.keySet());
    Map<String, BigDecimal> pendingMagnitudes = pendingQuantities(source.getId());
    Map<String, BigDecimal> pending = signedPending(linesById, pendingMagnitudes);
    Map<String, BigDecimal> planned = DocumentLineImport.plan(requested, documentLines, pending);

    JSONArray selected = MultiDocumentInvoiceSupport.buildInOutLineSelection(List.of(source),
        planned, pendingMagnitudes, log);
    List<DocumentLineImport.ImportedLine> created = createLines(invoice, selected,
        ShipmentInOutLine.class, planned, line -> line.getGoodsShipmentLine());
    requireQuotaAllowed(InvoiceLine.ENTITY_NAME, created);
    return DocumentLineImport.ok(created, invoice.getSalesOrder() == null ? null
        : invoice.getSalesOrder().getId());
  }

  /**
   * The pending magnitudes of {@link NeoInvoiceSupport} given the sign of each movement line (a
   * return-style line is negative) and never above what the line moved.
   */
  static Map<String, BigDecimal> signedPending(Map<String, ShipmentInOutLine> lines,
      Map<String, BigDecimal> magnitudes) {
    Map<String, BigDecimal> pending = new LinkedHashMap<>();
    for (Map.Entry<String, ShipmentInOutLine> entry : lines.entrySet()) {
      BigDecimal movement = entry.getValue().getMovementQuantity();
      BigDecimal magnitude = magnitudes.get(entry.getKey());
      if (movement == null || movement.signum() == 0 || magnitude == null) {
        continue;
      }
      BigDecimal capped = magnitude.min(movement.abs());
      if (capped.signum() > 0) {
        pending.put(entry.getKey(), movement.signum() < 0 ? capped.negate() : capped);
      }
    }
    return pending;
  }

  // ---------------------------------------------------------------------------
  // available documents / lines
  // ---------------------------------------------------------------------------

  private NeoResponse availableDocuments(Invoice invoice, boolean sales) throws JSONException {
    String partnerId = invoice.getBusinessPartner() == null ? null
        : invoice.getBusinessPartner().getId();
    String currencyId = invoice.getCurrency() == null ? null : invoice.getCurrency().getId();
    return discover(findCandidateDocuments(partnerId, sales, currencyId), doc -> {
      int pendingLines = isReturn(doc) ? 0 : pendingQuantities(doc.getId()).size();
      if (pendingLines == 0) {
        return null;
      }
      String docCurrency = doc.getEtgoCurrency() == null ? null : doc.getEtgoCurrency().getId();
      return documentRow(doc.getId(), doc.getDocumentNo(), day(doc.getMovementDate()),
          partnerId, doc.getBusinessPartner().getName(), docCurrency, pendingLines);
    });
  }

  private NeoResponse availableLines(ShipmentInOut source) throws JSONException {
    Map<String, ShipmentInOutLine> linesById = new LinkedHashMap<>();
    for (ShipmentInOutLine line : source.getMaterialMgmtShipmentInOutLineList()) {
      if (line.isActive()) {
        linesById.put(line.getId(), line);
      }
    }
    Map<String, BigDecimal> pending = signedPending(linesById, pendingQuantities(source.getId()));
    JSONArray rows = new JSONArray();
    for (ShipmentInOutLine line : linesById.values()) {
      BigDecimal left = pending.get(line.getId());
      if (left != null) {
        rows.put(DocumentLineImport.lineRow(line.getId(), line.getLineNo(),
            line.getProduct() == null ? null : line.getProduct().getId(),
            line.getProduct() == null ? null : line.getProduct().getName(),
            line.getUOM() == null ? null : line.getUOM().getId(), left));
      }
    }
    return completePage(rows);
  }

  // ---------------------------------------------------------------------------
  // Loading and validating
  // ---------------------------------------------------------------------------

  /**
   * The target invoice of the direction the action belongs to. A write needs a draft with a price
   * list; a read only needs the invoice.
   */
  private Invoice requireInvoiceOfDirection(String id, boolean sales, boolean write) {
    Invoice invoice = write ? requireDraftInvoice(id) : requireExistingInvoice(id);
    if (Boolean.TRUE.equals(invoice.isSalesTransaction()) != sales) {
      throw DocumentLineImport.refusal(DocumentLineImport.ERROR_DIRECTION_MISMATCH,
          "This is a " + (sales ? "purchase" : "sales") + " invoice: use "
              + (sales ? ACTION_IMPORT_RECEIPT_LINES : ACTION_IMPORT_SHIPMENT_LINES) + " for it");
    }
    return invoice;
  }

  private ShipmentInOut requireSource(Invoice invoice, String sourceId) {
    ShipmentInOut source = loadSource(sourceId);
    if (source == null) {
      throw DocumentLineImport.notFound("Document", sourceId);
    }
    requireOwnClient("Document", sourceId, source.getClient());
    DocumentLineImport.requireCompletedSource(source.getDocumentNo(), source.getDocumentStatus());
    DocumentLineImport.requireSameDirection(Boolean.TRUE.equals(invoice.isSalesTransaction()),
        Boolean.TRUE.equals(source.isSalesTransaction()), source.getDocumentNo());
    DocumentLineImport.requireSameBusinessPartner(
        invoice.getBusinessPartner() == null ? null : invoice.getBusinessPartner().getId(),
        source.getBusinessPartner() == null ? null : source.getBusinessPartner().getId(),
        source.getDocumentNo());
    DocumentLineImport.requireSameCurrency(
        invoice.getCurrency() == null ? null : invoice.getCurrency().getId(),
        source.getEtgoCurrency() == null ? null : source.getEtgoCurrency().getId(),
        source.getDocumentNo());
    if (isReturn(source)) {
      throw DocumentLineImport.refusal(DocumentLineImport.ERROR_DIRECTION_MISMATCH,
          "Document " + source.getDocumentNo() + " is a return: it is credited through its own "
              + "return flow, not imported here");
    }
    return source;
  }

  private static boolean isReturn(ShipmentInOut doc) {
    return doc.getDocumentType() != null && Boolean.TRUE.equals(doc.getDocumentType().isReturn());
  }

  private static String label(ShipmentInOutLine line) {
    return line.getProduct() != null && line.getProduct().getName() != null
        ? line.getProduct().getName() : line.getId();
  }

  // ---------------------------------------------------------------------------
  // Seams (DAL) — overridden by the unit tests
  // ---------------------------------------------------------------------------

  protected ShipmentInOut loadSource(String id) {
    return OBDal.getInstance().get(ShipmentInOut.class, id);
  }

  protected void lockInOutLines(Collection<String> ids) throws SQLException {
    DocumentLineImport.lockInOutLines(ids);
  }

  /** Pending MAGNITUDE per movement line, drafts included, a DB failure surfacing as an error. */
  protected Map<String, BigDecimal> pendingQuantities(String inOutId) {
    return NeoInvoiceSupport.computePendingQtyPerLineOrThrow(inOutId, true);
  }

  /**
   * Completed movements of the partner in the session's client, newest first; a bounded scan.
   * Returns are left out by the discovery row builder rather than in HQL, where {@code return} is
   * an awkward property name, so the scan limit counts what was really read.
   */
  protected List<ShipmentInOut> findCandidateDocuments(String partnerId, boolean sales,
      String currencyId) {
    StringBuilder hql = new StringBuilder("from MaterialMgmtShipmentInOut io "
        + "where io.documentStatus = 'CO' and io.salesTransaction = :sot "
        + "and io.businessPartner.id = :bp and io.active = true and io.client.id = :client");
    if (currencyId != null) {
      hql.append(" and io.etgoCurrency.id = :cur");
    }
    hql.append(" order by io.movementDate desc, io.documentNo desc");
    org.hibernate.query.Query<ShipmentInOut> query = OBDal.getInstance().getSession()
        .createQuery(hql.toString(), ShipmentInOut.class).setParameter("sot", sales)
        .setParameter("bp", partnerId).setParameter("client", sessionClientId())
        .setMaxResults(scanLimit());
    if (currencyId != null) {
      query.setParameter("cur", currencyId);
    }
    return new ArrayList<>(query.list());
  }
}
