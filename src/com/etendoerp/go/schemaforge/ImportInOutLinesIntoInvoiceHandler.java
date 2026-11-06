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
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.inject.Named;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.base.exception.OBException;
import org.openbravo.dal.core.OBContext;
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
 * one asked for. Return documents are not importable through those three actions: a normal invoice
 * takes no lines from a return.
 *
 * <p><b>Returns (ETP-5876).</b> A DRAFT rectificative invoice takes its lines from a completed return
 * of the same business partner through {@code availableReturns}, {@code availableReturnLines} and
 * {@code importReturnLines}, the actions the window's "Import from return" modal stands for. The
 * direction comes from the invoice (a customer return into a sales rectificative, a return to a
 * vendor into a purchase one). The lines come in NEGATIVE, linked to the return line, with the
 * price of the invoice's CURRENT price list (a return alone has no relation to the price it was
 * sold at: {@code createReturnInvoice} is the way to credit at the original price), and the cap and
 * the candidate rules are each window modal's own, neither stricter nor laxer ({@link
 * InOutInvoiceListing#returnPending}): sales nets every invoice line linked to the return line
 * whatever the status of its invoice, and compares the currency of the return's sales order; purchase offers the return
 * line's quantity, skips only a line this invoice already holds and compares no currency; both list
 * only returns whose {@code invoiceStatus} is below 100. On the SALES side the rectificative is
 * linked in {@code C_Invoice_Reverse} to the completed invoices the imported return lines rectify
 * ({@link ReturnLineRectifiedInvoices}, ETP-6002), as the window's line create does; the purchase
 * side writes no link. The header's total discount is not carried. The actions are only offered to a rectificative:
 * {@link RectificativeImportGate} refuses them on a normal invoice, and refuses the order and
 * goods actions on a rectificative.
 */
@Named("importInOutLinesIntoInvoiceHandler")
public class ImportInOutLinesIntoInvoiceHandler extends AbstractInvoiceLineImportHandler {

  static final String ACTION_AVAILABLE_RECEIPTS = "availableReceipts";
  static final String ACTION_AVAILABLE_RECEIPT_LINES = "availableReceiptLines";
  static final String ACTION_IMPORT_RECEIPT_LINES = "importReceiptLines";
  static final String ACTION_AVAILABLE_SHIPMENTS = "availableShipments";
  static final String ACTION_AVAILABLE_SHIPMENT_LINES = "availableShipmentLines";
  static final String ACTION_IMPORT_SHIPMENT_LINES = "importShipmentLines";

  static final String ACTION_AVAILABLE_RETURNS = "availableReturns";
  static final String ACTION_AVAILABLE_RETURN_LINES = "availableReturnLines";
  static final String ACTION_IMPORT_RETURN_LINES = "importReturnLines";
  /** Response key of the invoices a sales return import linked the rectificative to (ETP-6002). */
  static final String KEY_RECTIFIED_INVOICES = "rectifiedInvoices";

  /**
   * The rectification-link step of a sales return import (ETP-6002). A field, not a CDI bean: it
   * holds no state, and the unit test swaps in a recording double.
   */
  ReturnImportRectificationLinks rectificationLinks = new ReturnImportRectificationLinks();

  /** Joins the steps of the flow sentence of a contract. */
  private static final String THEN = ", then ";
  private static final String PARAM_CLIENT = "client";
  private static final String PARAM_RECEIPT_ID = "receiptId";
  private static final String PARAM_SHIPMENT_ID = "shipmentId";
  private static final String PARAM_RETURN_ID = "returnId";
  /** The goods shipment / goods receipt actions of a normal invoice (ETP-5713). */
  static final Set<String> STANDARD_ACTIONS = Set.of(ACTION_AVAILABLE_RECEIPTS,
      ACTION_AVAILABLE_RECEIPT_LINES, ACTION_IMPORT_RECEIPT_LINES, ACTION_AVAILABLE_SHIPMENTS,
      ACTION_AVAILABLE_SHIPMENT_LINES, ACTION_IMPORT_SHIPMENT_LINES);
  /** The return actions of a rectificative invoice (ETP-5876). */
  static final Set<String> RETURN_ACTIONS = Set.of(ACTION_AVAILABLE_RETURNS,
      ACTION_AVAILABLE_RETURN_LINES, ACTION_IMPORT_RETURN_LINES);
  private static final Set<String> ACTIONS = Stream.concat(STANDARD_ACTIONS.stream(),
      RETURN_ACTIONS.stream()).collect(Collectors.toUnmodifiableSet());
  private static final Set<String> SALES_ACTIONS = Set.of(ACTION_AVAILABLE_SHIPMENTS,
      ACTION_AVAILABLE_SHIPMENT_LINES, ACTION_IMPORT_SHIPMENT_LINES);
  private static final Set<String> LIST_ACTIONS =
      Set.of(ACTION_AVAILABLE_RECEIPTS, ACTION_AVAILABLE_SHIPMENTS, ACTION_AVAILABLE_RETURNS);
  private static final Set<String> LINES_ACTIONS = Set.of(ACTION_AVAILABLE_RECEIPT_LINES,
      ACTION_AVAILABLE_SHIPMENT_LINES, ACTION_AVAILABLE_RETURN_LINES);
  private static final int MAX_CANDIDATES = 200;
  private static final int MAX_DOCUMENTS = 50;

  /** A sales return line whose order line is already invoiced through a movement line or here. */
  static final String REASON_ORDER_LINE_INVOICED = "orderLineInvoiced";
  /** A sales return line the invoice lines linked to it already credit in full. */
  static final String REASON_CREDITED_IN_FULL = "creditedInFull";
  /** A purchase return line this invoice already holds. */
  static final String REASON_IMPORTED_HERE = "importedHere";
  /** The paging sentence of every list contract of this delegate (ETP-6099). */
  static final String LISTING_NOTE = "limit (1 to " + ImportListing.MAX_LIMIT + "), offset, "
      + "includeCovered and detail are optional; with limit or offset the answer adds offset and "
      + "nextOffset (page on while hasMore).";
  static final String STANDARD_DOC_DETAIL = "creationDate, order (the header's order id or "
      + "null) and orderDocumentNo";
  static final String STANDARD_LINE_DETAIL = "movementQuantity, orderLine (its order line id or "
      + "null), orderLineDiscount (that order line's discount %), importedHere (this invoice has a "
      + "line linked to this movement line or to its order line) and invoicedElsewhere (an active "
      + "line of another invoice, any status, is linked to this movement line)";
  static final String RETURN_DOC_DETAIL = "creationDate, order (the header's order id or null) "
      + "and "
      + "sourceDocumentNo (the shipments / receipts the return comes from, comma separated)";
  static final String RETURN_LINE_DETAIL = "movementQuantity (positive), orderLine, "
      + "orderLineDiscount and blockedReason (null when offered, else " + REASON_ORDER_LINE_INVOICED
      + ", " + REASON_CREDITED_IN_FULL + " or " + REASON_IMPORTED_HERE + ")";

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
        + THEN + listLines + THEN + imp + " on that header, and complete it with "
        + "documentAction (CO). To invoice a whole " + inout + " in ONE step (created AND "
        + "completed, no draft to fill) call " + oneStep + " instead. A rectificative invoice "
        + "answers 422 importNotAvailableOnRectificative: its lines come from a return or from the "
        + "invoice it rectifies.";
    Map<String, NeoActionContract> contracts = new LinkedHashMap<>();
    contracts.put(list, NeoActionContract.read(list,
        "Lists the completed " + inout + "s of this " + doc + "'s business partner and currency "
            + "that still have quantity to invoice: {data:[{id, documentNo, date, businessPartner, "
            + "businessPartner$_identifier, currency, pendingLines}], hasMore, limit, scanLimit}. hasMore true means the list may be incomplete: it looks at the scanLimit most recent candidates and returns at most limit documents, so a document it does not list may still be importable when you know its id. Pending is what completed "
            + "AND draft invoices have not covered. " + LISTING_NOTE + " includeCovered also lists "
            + "the " + inout + "s whose pending lines are all held by draft invoices; detail adds "
            + STANDARD_DOC_DETAIL + ". Step 1." + flow,
        ImportListing.listParams(MAX_DOCUMENTS, STANDARD_DOC_DETAIL))
        .withIdDescription(DocumentLineImport.ID_OF_THE_DRAFT + doc + " the lines would be imported into.")
        .availableWhen(DocumentLineImport.TARGET_IS_DRAFT));
    contracts.put(listLines, NeoActionContract.read(listLines,
        "Lists the lines of a " + inout + " that can still be invoiced here: {data:[{id, lineNo, "
            + "product, product$_identifier, uOM, pendingQuantity}], hasMore:false}, where pendingQuantity is "
            + "what no completed or draft invoice (this one included) covers, and the id is the "
            + "sourceLineId " + imp + " takes. includeCovered also lists the lines with nothing "
            + "left at pendingQuantity 0; detail adds " + STANDARD_LINE_DETAIL + ". Step 2." + flow,
        ImportListing.linesParams(STANDARD_LINE_DETAIL,
            NeoActionContract.Param.required(param, NeoActionContract.TYPE_STRING,
                "Id of a document returned by " + list + ".")))
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

  /**
   * The three return contracts of the invoice this delegate is injected in (ETP-5876), in flow
   * order. Declared for a rectificative; the gate refuses them on any other invoice.
   *
   * @param sales {@code true} for a sales invoice (customer returns), {@code false} for a purchase
   *              invoice (returns to a vendor)
   */
  static Map<String, NeoActionContract> returnContracts(boolean sales) {
    String ret = sales ? "customer return (return material receipt)" : "return to vendor";
    String doc = sales ? "sales" : "purchase";
    // The cap is each window modal's own (ETP-5876): the sales one nets every invoice line linked to
    // the return line, the purchase one offers the return line's quantity and only skips a line this
    // invoice already holds.
    String credited = sales
        ? "what is left of the return line after every invoice line linked to it, whatever the "
            + "status of its invoice (voided ones too, this invoice's own lines among them)"
        : "the quantity of the return line; a line this invoice already holds is not offered again, "
            + "and what other invoices hold is NOT deducted (the window does not either)";
    String flow = " Flow, on a DRAFT rectificative " + doc + " invoice (the invoice's document type "
        + "is a rectificative one; any other invoice answers 422 importRequiresRectificative): "
        + ACTION_AVAILABLE_RETURNS + THEN + ACTION_AVAILABLE_RETURN_LINES + THEN
        + ACTION_IMPORT_RETURN_LINES + ", and complete it with documentAction (CO). To credit a "
        + "return at the price of the invoice it came from, in ONE step, call createReturnInvoice "
        + "on the return instead.";
    String on = DocumentLineImport.ID_OF_THE_DRAFT + "rectificative " + doc
        + " invoice the lines would be imported into.";
    Map<String, NeoActionContract> contracts = new LinkedHashMap<>();
    contracts.put(ACTION_AVAILABLE_RETURNS, NeoActionContract.read(ACTION_AVAILABLE_RETURNS,
        "Lists the completed " + ret + "s of this invoice's business partner and currency that "
            + "still have quantity to credit: {data:[{id, documentNo, date, businessPartner, "
            + "businessPartner$_identifier, currency, pendingLines}], hasMore, limit, scanLimit}. "
            + "hasMore true means the list may be incomplete: it looks at the scanLimit most recent "
            + "candidates and returns at most limit documents, so a return it does not list may "
            + "still be importable when you know its id. A return already invoiced in full is not "
            + "listed. " + LISTING_NOTE + " includeCovered also lists the returns not invoiced in "
            + "full that have no line left to credit; detail adds " + RETURN_DOC_DETAIL
            + ". Step 1." + flow,
        ImportListing.listParams(MAX_DOCUMENTS, RETURN_DOC_DETAIL))
        .withIdDescription(on).availableWhen(DocumentLineImport.TARGET_IS_DRAFT));
    contracts.put(ACTION_AVAILABLE_RETURN_LINES, NeoActionContract.read(
        ACTION_AVAILABLE_RETURN_LINES, "Lists the lines of a " + ret + " that can still be credited "
            + "here: {data:[{id, lineNo, product, product$_identifier, uOM, pendingQuantity}], "
            + "hasMore:false}, where pendingQuantity is a positive number: " + credited
            + ", and the id is the sourceLineId "
            + ACTION_IMPORT_RETURN_LINES + " takes. includeCovered also lists the lines that "
            + "cannot be credited at pendingQuantity 0; detail adds " + RETURN_LINE_DETAIL
            + ". Step 2." + flow,
        ImportListing.linesParams(RETURN_LINE_DETAIL,
            NeoActionContract.Param.required(PARAM_RETURN_ID, NeoActionContract.TYPE_STRING,
                "Id of a return returned by " + ACTION_AVAILABLE_RETURNS + ".")))
        .withIdDescription(on).availableWhen(DocumentLineImport.TARGET_IS_DRAFT));
    contracts.put(ACTION_IMPORT_RETURN_LINES, NeoActionContract.write(ACTION_IMPORT_RETURN_LINES,
        "Adds lines of a completed " + ret + " to this DRAFT rectificative " + doc + " invoice, in "
            + "ONE transaction, WITHOUT completing it. The lines come in NEGATIVE (a credit), "
            + "linked to the return line, with the price and tax of the invoice's CURRENT price "
            + "list: a return alone has no relation to the price it was sold at (use "
            + "createReturnInvoice on the return to credit at the original price). Without lines "
            + "it imports everything still pending; with lines, exactly those, quantity a positive "
            + "number (the sign is applied). More than what is pending on a line (" + credited
            + "; the same line listed twice counts both) answers 422 importQuantityExceedsPending naming the product, the "
            + "requested and the pending quantity, and creates NOTHING. Refused (422) when the "
            + "invoice is not a draft rectificative, the document is not a completed return, or "
            + "business partner or direction differ (currency too, on a sales invoice, when the "
            + "return has a sales order). "
            + (sales ? "It links the rectificative, in the Rectificaciones tab, to every COMPLETED "
                + "invoice of the same customer that billed the shipment line each imported return "
                + "line returns (once per invoice, never duplicated), as the window does; a refusal "
                + "of that link (e.g. a processed invoice) imports nothing. "
                : "It does not link the invoice to an invoice being rectified (that is "
                + "importOriginInvoiceLines). ")
            + "It does not carry any total discount. Returns {data:{importedCount, lines:[{id, "
            + "sourceLineId, product, productName, quantity}], headerLinked, salesOrder"
            + (sales ? ", rectifiedInvoices:[{id, documentNo}]" : "")
            + "}} (quantity is negative). Step 3."
            + flow,
        NeoActionContract.Param.required(PARAM_RETURN_ID, NeoActionContract.TYPE_STRING,
            "Id of the return to import from, returned by " + ACTION_AVAILABLE_RETURNS + "."),
        NeoActionContract.Param.array(DocumentLineImport.KEY_LINES,
            NeoActionContract.TYPE_OBJECT, false,
            "Each {sourceLineId, quantity}: sourceLineId is the id of a line from "
                + ACTION_AVAILABLE_RETURN_LINES + ", quantity a positive number not above its "
                + "pendingQuantity. Omit to import every pending line in full."))
        .withIdDescription(DocumentLineImport.ID_OF_THE_DRAFT + "rectificative " + doc
            + " invoice the lines are added to.")
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

  /**
   * The two receipt listings that take {@code pendingBasis: "classic"} where the header's policy
   * offers it (ETP-6099 follow-up: the purchase invoice,
   * {@link RectificativeImportPolicy#offersClassicPending}). The shipment and return listings and
   * every import keep the server rule only.
   */
  static final Set<String> CLASSIC_PENDING_ACTIONS =
      Set.of(ACTION_AVAILABLE_RECEIPTS, ACTION_AVAILABLE_RECEIPT_LINES);

  @Override
  protected boolean classicPendingOffered(NeoContext context, String action) {
    return CLASSIC_PENDING_ACTIONS.contains(action)
        && RectificativeImportPolicy.from(context).offersClassicPending();
  }

  @Override
  protected NeoResponse execute(NeoContext context, String action) throws Exception {
    boolean returns = RETURN_ACTIONS.contains(action);
    // A return action takes its direction from the invoice, the others from their own name.
    Boolean sales = returns ? null : SALES_ACTIONS.contains(action);
    JSONObject body = context.getRequestBody();
    String idParam = idParamOf(returns, sales);
    RectificativeImportPolicy policy = RectificativeImportPolicy.from(context);
    boolean classic = classicPending(context);
    InOutInvoiceSourceRules rules = new InOutInvoiceSourceRules(this);
    InOutInvoiceListing listings = new InOutInvoiceListing(this);
    if (LIST_ACTIONS.contains(action)) {
      Invoice invoice = rules.requireInvoiceOfDirection(context.getRecordId(), sales, false);
      return classic ? listings.classicAvailableDocuments(invoice, listRequest(body))
          : listings.availableDocuments(invoice, InOutInvoiceSourceRules.isSales(invoice),
              returns, policy, listRequest(body));
    }
    if (LINES_ACTIONS.contains(action)) {
      Invoice invoice = rules.requireInvoiceOfDirection(context.getRecordId(), sales, false);
      ShipmentInOut source = rules.requireSource(invoice, requireParam(body, idParam), returns,
          policy);
      InOutInvoiceListing.LineListing listing = new InOutInvoiceListing.LineListing(
          ImportListing.includeCovered(body), ImportListing.detail(body));
      return classic ? listings.classicAvailableLines(invoice, source, listing)
          : listings.availableLines(invoice, source, returns, policy, listing);
    }
    return importLines(rules.requireInvoiceOfDirection(context.getRecordId(), sales, true), body,
        idParam, returns, policy);
  }

  private static String idParamOf(boolean returns, Boolean sales) {
    if (returns) {
      return PARAM_RETURN_ID;
    }
    return Boolean.TRUE.equals(sales) ? PARAM_SHIPMENT_ID : PARAM_RECEIPT_ID;
  }

  // ---------------------------------------------------------------------------
  // import
  // ---------------------------------------------------------------------------

  private NeoResponse importLines(Invoice invoice, JSONObject body, String idParam,
      boolean returns, RectificativeImportPolicy policy) throws SQLException, JSONException {
    Map<String, BigDecimal> requested = DocumentLineImport.parseRequestedLines(body);
    if (returns) {
      // The quantity of a return line is a positive number for the caller; it is credited.
      requested.replaceAll((id, quantity) -> quantity.abs());
    }
    ShipmentInOut source = new InOutInvoiceSourceRules(this).requireSource(invoice,
        requireParam(body, idParam), returns, policy);

    Map<String, ShipmentInOutLine> linesById = new LinkedHashMap<>();
    Map<String, String> documentLines = new LinkedHashMap<>();
    for (ShipmentInOutLine line : source.getMaterialMgmtShipmentInOutLineList()) {
      if (line.isActive()) {
        linesById.put(line.getId(), line);
        documentLines.put(line.getId(), label(line));
      }
    }
    lockInOutLines(linesById.keySet());
    Map<String, BigDecimal> pendingMagnitudes = returns ? Map.of()
        : pendingQuantities(source.getId());
    Map<String, BigDecimal> pending = returns
        ? new InOutInvoiceListing(this).returnPending(invoice, source, linesById, policy)
        : signedPending(linesById, pendingMagnitudes);
    Map<String, BigDecimal> planned = DocumentLineImport.plan(requested, documentLines, pending);
    if (returns) {
      // What is written is a credit: NEGATIVE, whatever sign the return stored.
      planned.replaceAll((id, quantity) -> quantity.negate());
    }

    // ETP-6002: a SALES return import links the rectificative to the invoices the imported return
    // lines rectify, as the window's line create does — before the lines, in this transaction.
    JSONArray rectified = null;
    if (returns && policy.linksReturnsToRectifiedInvoices()) {
      try {
        rectified = rectificationLinks.link(invoice, linesById, planned.keySet());
      } catch (RuntimeException e) {
        // A C_INVOICE_REVERSE_TRG refusal (another partner, a processed invoice) is answered
        // classified, as on the lines CRUD, and rolled back: nothing is imported.
        return NeoErrorResponses.fromException(e, "POST", "importReturnLines link", null);
      }
    }

    JSONArray selected = returns ? creditSelection(planned)
        : MultiDocumentInvoiceSupport.buildInOutLineSelection(List.of(source), planned,
            pendingMagnitudes, log);
    List<DocumentLineImport.ImportedLine> created = createLines(invoice, selected,
        ShipmentInOutLine.class, planned, line -> line.getGoodsShipmentLine());
    if (returns) {
      Set<String> createdIds = new java.util.HashSet<>();
      created.forEach(line -> createdIds.add(line.id));
      ReturnLineCredit.clearReferencePrices(invoice, createdIds);
    }
    requireQuotaAllowed(InvoiceLine.ENTITY_NAME, created);
    String headerOrderId = invoice.getSalesOrder() == null ? null : invoice.getSalesOrder().getId();
    if (rectified == null) {
      return DocumentLineImport.ok(created, headerOrderId);
    }
    Map<String, Object> extra = new LinkedHashMap<>();
    extra.put(KEY_RECTIFIED_INVOICES, rectified);
    return DocumentLineImport.ok(created, headerOrderId, extra);
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

  /**
   * The native selection of a credit: one entry per planned line, at the planned (negative)
   * quantity. Built here and not by {@code MultiDocumentInvoiceSupport}, which gives the quantity
   * the sign of the movement line and would turn the credit of a positive-stored return positive.
   */
  private static JSONArray creditSelection(Map<String, BigDecimal> planned) throws JSONException {
    JSONArray selected = new JSONArray();
    for (Map.Entry<String, BigDecimal> entry : planned.entrySet()) {
      selected.put(new JSONObject().put("id", entry.getKey()).put("orderedQuantity",
          entry.getValue().toPlainString()));
    }
    return selected;
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
    return findCandidateDocuments(partnerId, sales, currencyId, 0, scanLimit());
  }

  /** {@link #findCandidateDocuments(String, boolean, String)} paged (ETP-6099). */
  protected List<ShipmentInOut> findCandidateDocuments(String partnerId, boolean sales,
      String currencyId, int offset, int scan) {
    StringBuilder hql = new StringBuilder("from MaterialMgmtShipmentInOut io "
        + "where io.documentStatus = 'CO' and io.salesTransaction = :sot "
        + "and io.businessPartner.id = :bp and io.active = true and io.client.id = :client");
    if (currencyId != null) {
      hql.append(" and io.etgoCurrency.id = :cur");
    }
    hql.append(" order by io.movementDate desc, io.documentNo desc, io.id desc");
    org.hibernate.query.Query<ShipmentInOut> query = OBDal.getInstance().getSession()
        .createQuery(hql.toString(), ShipmentInOut.class).setParameter("sot", sales)
        .setParameter("bp", partnerId).setParameter(PARAM_CLIENT, sessionClientId())
        .setFirstResult(offset).setMaxResults(scan);
    if (currencyId != null) {
      query.setParameter("cur", currencyId);
    }
    return new ArrayList<>(query.list());
  }

  /**
   * The HQL behind {@link #findClassicCandidateDocuments}: the completed, active movements of one
   * direction and partner in the session's client, newest created first; with a currency, only the
   * ones whose header order is in it or that have no order (the old popup's comparison). Exposed so
   * a test parses it against the real model.
   */
  static String classicCandidateDocumentsHql(boolean withCurrency) {
    // An explicit LEFT join: an implicit io.salesOrder.currency path would be an inner join and
    // silently drop the receipts without an order, which the popup kept.
    return "select io from MaterialMgmtShipmentInOut io left join io.salesOrder so "
        + "where io.documentStatus = 'CO' and io.salesTransaction = :sot "
        + "and io.businessPartner.id = :bp and io.active = true and io.client.id = :client"
        + (withCurrency ? " and (so.id is null or so.currency.id = :cur)" : "")
        + " order by io.creationDate desc, io.documentNo desc, io.id desc";
  }

  /** The candidates of the classic {@code availableReceipts}, from {@code offset}; bounded. */
  protected List<ShipmentInOut> findClassicCandidateDocuments(String partnerId, boolean sales,
      String currencyId, int offset, int scan) {
    org.hibernate.query.Query<ShipmentInOut> query = OBDal.getInstance().getSession()
        .createQuery(classicCandidateDocumentsHql(currencyId != null), ShipmentInOut.class)
        .setParameter("sot", sales).setParameter("bp", partnerId)
        .setParameter(PARAM_CLIENT, sessionClientId())
        .setFirstResult(offset).setMaxResults(scan);
    if (currencyId != null) {
      query.setParameter("cur", currencyId);
    }
    return new ArrayList<>(query.list());
  }

  /**
   * The HQL behind {@link #findCandidateReturns}: the completed return documents of one direction
   * and business partner, in the session's client and in an organization the role reads. No
   * currency filter: the windows' modals compare it (sales) or not (purchase) per document, see
   * {@link InOutInvoiceSourceRules#returnCurrencyMismatch}. Exposed so a test parses it against the real model.
   */
  static String candidateReturnsHql() {
    return "from MaterialMgmtShipmentInOut io "
        + "where io.documentStatus = 'CO' and io.salesTransaction = :sot "
        + "and io.documentType.return = true "
        + "and io.businessPartner.id = :bp and io.active = true and io.client.id = :client "
        + "and io.organization.id in (:orgs)"
        + " order by io.movementDate desc, io.documentNo desc, io.id desc";
  }

  /** Completed returns of the partner, newest first; a bounded scan (see {@link #scanLimit()}). */
  protected List<ShipmentInOut> findCandidateReturns(String partnerId, boolean sales) {
    return findCandidateReturns(partnerId, sales, 0, scanLimit());
  }

  /** {@link #findCandidateReturns(String, boolean)} paged (ETP-6099). */
  protected List<ShipmentInOut> findCandidateReturns(String partnerId, boolean sales, int offset,
      int scan) {
    return new ArrayList<>(OBDal.getInstance().getSession()
        .createQuery(candidateReturnsHql(), ShipmentInOut.class)
        .setParameter("sot", sales).setParameter("bp", partnerId)
        .setParameter(PARAM_CLIENT, sessionClientId())
        .setParameterList("orgs", Arrays.asList(OBContext.getOBContext().getReadableOrganizations()))
        .setFirstResult(offset).setMaxResults(scan).list());
  }

  /** Pending MAGNITUDE per movement line BEFORE drafts (ETP-6099, {@code includeCovered}). */
  protected Map<String, BigDecimal> pendingBeforeDrafts(String inOutId) {
    return NeoInvoiceSupport.computePendingQtyPerLineOrThrow(inOutId, false);
  }

  /** The numbers of the shipments / receipts a return comes from, comma separated, or null. */
  protected String sourceDocumentNos(String returnId) {
    List<JSONObject> sources = ReturnShipmentUtils.fetchSourceDocuments(List.of(returnId))
        .getOrDefault(returnId, List.of());
    String joined = sources.stream().map(d -> d.optString("documentNo", ""))
        .filter(no -> !no.isEmpty()).collect(Collectors.joining(", "));
    return joined.isEmpty() ? null : joined;
  }

  /**
   * Per movement line of the document, {@code [importedHere, invoicedElsewhere]} (ETP-6099): this
   * invoice has an active line linked to the movement line or to its order line; an active line of
   * ANOTHER invoice, whatever its status, is linked to the movement line. The two sets the window's
   * modals greyed lines out with.
   */
  @SuppressWarnings("java:S2077")
  protected Map<String, boolean[]> invoiceLinkStates(String inOutId, String invoiceId) {
    String sql = "SELECT ml.m_inoutline_id, "
        + "MAX(CASE WHEN il.c_invoice_id = ? THEN 1 ELSE 0 END), "
        + "MAX(CASE WHEN il.c_invoice_id <> ? AND il.m_inoutline_id = ml.m_inoutline_id "
        + "THEN 1 ELSE 0 END) "
        + "FROM m_inoutline ml JOIN c_invoiceline il ON il.isactive = 'Y' "
        + "AND (il.m_inoutline_id = ml.m_inoutline_id OR (il.c_invoice_id = ? "
        + "AND ml.c_orderline_id IS NOT NULL AND il.c_orderline_id = ml.c_orderline_id)) "
        + "WHERE ml.m_inout_id = ? GROUP BY ml.m_inoutline_id";
    Map<String, boolean[]> states = new LinkedHashMap<>();
    try (PreparedStatement ps = OBDal.getInstance().getConnection().prepareStatement(sql)) {
      ps.setString(1, invoiceId);
      ps.setString(2, invoiceId);
      ps.setString(3, invoiceId);
      ps.setString(4, inOutId);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          states.put(rs.getString(1), new boolean[] { rs.getInt(2) == 1, rs.getInt(3) == 1 });
        }
      }
    } catch (SQLException e) {
      throw new OBException("Could not determine the invoice lines linked to the document", e);
    }
    return states;
  }

  /**
   * Quantity already invoiced per return line: the magnitude of EVERY active invoice line linked to
   * it, whatever the status of its invoice (the sales modal sums them all, voided ones too).
   */
  @SuppressWarnings("java:S2077")
  protected Map<String, BigDecimal> creditedQuantities(String inOutId) {
    String sql = "SELECT il.m_inoutline_id, SUM(ABS(il.qtyinvoiced)) FROM c_invoiceline il "
        + "WHERE il.isactive = 'Y' AND il.m_inoutline_id IN "
        + "(SELECT m_inoutline_id FROM m_inoutline WHERE m_inout_id = ?) "
        + "GROUP BY il.m_inoutline_id";
    Map<String, BigDecimal> credited = new LinkedHashMap<>();
    try (PreparedStatement ps = OBDal.getInstance().getConnection().prepareStatement(sql)) {
      ps.setString(1, inOutId);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          credited.put(rs.getString(1), rs.getBigDecimal(2));
        }
      }
    } catch (SQLException e) {
      throw new OBException("Could not determine the quantities already invoiced", e);
    }
    return credited;
  }

  /**
   * The order lines of the return's lines that the sales modal treats as taken: one that an invoice
   * line linked to a movement line already points at, or that this invoice points at.
   */
  @SuppressWarnings("java:S2077")
  protected Set<String> blockedOrderLines(String inOutId, String invoiceId) {
    String sql = "SELECT DISTINCT il.c_orderline_id FROM c_invoiceline il WHERE il.isactive = 'Y' "
        + "AND il.c_orderline_id IN (SELECT c_orderline_id FROM m_inoutline "
        + "WHERE m_inout_id = ? AND c_orderline_id IS NOT NULL) "
        + "AND (il.m_inoutline_id IS NOT NULL OR il.c_invoice_id = ?)";
    Set<String> blocked = new java.util.HashSet<>();
    try (PreparedStatement ps = OBDal.getInstance().getConnection().prepareStatement(sql)) {
      ps.setString(1, inOutId);
      ps.setString(2, invoiceId);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          blocked.add(rs.getString(1));
        }
      }
    } catch (SQLException e) {
      throw new OBException("Could not determine the order lines already invoiced", e);
    }
    return blocked;
  }

  /** The window's {@code invoiceStatus} of the document: percent invoiced by completed invoices. */
  @SuppressWarnings("java:S2077")
  protected BigDecimal invoiceStatusOf(String inOutId) {
    try (PreparedStatement ps = OBDal.getInstance().getConnection()
        .prepareStatement("SELECT c_getinvoicestatusfromshipment(?)")) {
      ps.setString(1, inOutId);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? rs.getBigDecimal(1) : null;
      }
    } catch (SQLException e) {
      throw new OBException("Could not determine the invoice status of the return", e);
    }
  }
}
