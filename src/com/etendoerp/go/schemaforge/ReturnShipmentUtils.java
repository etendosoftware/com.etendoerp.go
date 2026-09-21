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
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.criterion.Restrictions;
import org.openbravo.base.exception.OBException;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.erpCommon.businessUtility.Tax;
import org.openbravo.erpCommon.utility.OBCurrencyUtils;
import org.openbravo.model.common.businesspartner.BusinessPartner;
import org.openbravo.model.common.currency.Currency;
import org.openbravo.model.common.enterprise.DocumentType;
import org.openbravo.model.common.enterprise.Locator;
import org.openbravo.model.common.enterprise.Warehouse;
import org.openbravo.model.common.invoice.Invoice;
import org.openbravo.model.common.invoice.InvoiceLine;
import org.openbravo.model.common.invoice.ReversedInvoice;
import org.openbravo.model.financialmgmt.tax.TaxRate;
import org.openbravo.model.materialmgmt.transaction.ShipmentInOut;
import org.openbravo.model.materialmgmt.transaction.ShipmentInOutLine;
import org.openbravo.model.pricing.pricelist.PriceList;
import org.openbravo.service.db.DalConnectionProvider;

/**
 * Shared static helpers for return shipment / return receipt handler pairs.
 * Methods here are identical across {@link ReturnToVendorShipmentHeaderHandler} and
 * {@link ReturnMaterialReceiptHeaderHandler}.
 */
final class ReturnShipmentUtils {

  private static final Logger log = LogManager.getLogger(ReturnShipmentUtils.class);

  private static final String KEY_RESPONSE = "response";
  private static final String FIELD_DOCUMENT_NO = "documentNo";
  private static final String FIELD_DOCUMENT_STATUS = "documentStatus";
  /** Request-body key carrying the ids of the invoices to rectify (ETP-5381). */
  static final String PARAM_ORIGIN_INVOICES = "originInvoices";
  /**
   * Rows per batch of the {@code rectifiableInvoices} picker when the caller does not say
   * (ETP-5381). 80 rather than {@code useEntity}'s 75 only because that is the figure the window
   * was specified with; nothing depends on the two matching.
   */
  static final int DEFAULT_RECTIFIABLE_PAGE_SIZE = 80;
  // English literal on purpose: localized by tools/app-shell/src/lib/backendErrors.js.
  static final String ERR_RECTIFIED_INVOICE_REQUIRED =
      "Select at least one invoice to rectify: a rectificative invoice cannot be confirmed "
          + "without it.";
  private static final String FIELD_INVOICE_STATUS = "invoiceStatus";

  private ReturnShipmentUtils() {}

  // ---------------------------------------------------------------------------
  // Response wrapper
  // ---------------------------------------------------------------------------

  static NeoResponse wrapOkData(Object data) throws Exception {
    JSONObject responseData = new JSONObject();
    responseData.put("data", data);
    JSONObject wrapper = new JSONObject();
    wrapper.put(KEY_RESPONSE, responseData);
    return NeoResponse.ok(wrapper);
  }

  // ---------------------------------------------------------------------------
  // Line data – shared between both return line handlers
  // ---------------------------------------------------------------------------

  static final class LineData {
    final BigDecimal qty;
    final String productCode;
    LineData(BigDecimal qty, String productCode) {
      this.qty = qty;
      this.productCode = productCode;
    }
  }

  @SuppressWarnings("java:S2077")
  static Map<String, LineData> fetchLineData(List<String> lineIds, Logger callerLog) {
    Map<String, LineData> result = new HashMap<>();
    if (lineIds.isEmpty()) {
      return result;
    }
    String placeholders = lineIds.stream().map(id -> "?").collect(Collectors.joining(","));
    String sql =
        "SELECT l.M_InOutLine_ID, COALESCE(orig.MovementQty, l.QuantityOrder) AS effective_qty, " +
        "  p.Value AS product_code " +
        "FROM M_InOutLine l " +
        "LEFT JOIN M_InOutLine orig ON orig.M_InOutLine_ID = l.Canceled_Inoutline_ID " +
        "LEFT JOIN M_Product p ON p.M_Product_ID = l.M_Product_ID " +
        "WHERE l.M_InOutLine_ID IN (" + placeholders + ")";
    Connection conn = OBDal.getInstance().getConnection();
    try (PreparedStatement ps = conn.prepareStatement(sql)) {
      for (int i = 0; i < lineIds.size(); i++) {
        ps.setString(i + 1, lineIds.get(i));
      }
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          result.put(rs.getString(1), new LineData(rs.getBigDecimal(2), rs.getString(3)));
        }
      }
    } catch (Exception e) {
      callerLog.warn("Error fetching line data: {}", e.getMessage());
    }
    return result;
  }

  // ---------------------------------------------------------------------------
  // Storage bin fill – shared between both return header handlers
  // ---------------------------------------------------------------------------

  /**
   * Header-level safety net that guarantees every line of {@code doc} carries a storage bin
   * belonging to {@code doc}'s OWN warehouse. Called via
   * {@code NeoHandlerUtils.reanchorLinesToHeaderWarehouse} from the {@code documentAction}/POST
   * pre-hook of all four completable {@code M_InOut}-based header handlers —
   * {@code GoodsShipmentHeaderHandler}, {@code GoodsReceiptHeaderHandler},
   * {@code ReturnMaterialReceiptHeaderHandler}, and {@code ReturnToVendorShipmentHeaderHandler}
   * — after the lines exist.
   *
   * <p><b>ETP-4863 — this method WAS the live bug.</b> It used to give the SOURCE document's bin
   * ({@code line.getCanceledInoutLine().getStorageBin()}) precedence over the line's own value.
   * A return line references its source line even when the user typed it by hand in the window,
   * so this ran on every line and silently overwrote the correct bin that the line
   * {@code NeoHandler} had just set from the header's warehouse with a bin from the SOURCE
   * document's warehouse. Confirmed in production on RFC Receipts 1000057/1000059/1000061/1000063:
   * header in "Almacen GO", lines rewritten to {@code AS-0-0-0} of "Almacén Secundario", and the
   * whole stock movement followed the bin into the wrong warehouse.
   *
   * <p>Corrected precedence: the LINE'S OWN bin wins; the source document's bin is only a
   * fallback for a line that has none; and whatever comes out of that must belong to the header's
   * warehouse or be replaced. A line already holding a valid bin is left untouched — no write, no
   * save.
   *
   * <p>A bin that cannot be anchored (the header warehouse has no active locator at all) is
   * written as {@code null}, NOT skipped. Skipping would leave the line pointing at the wrong
   * warehouse's bin — the exact silent-corruption failure mode this method exists to prevent —
   * so it fails loudly at {@code M_INOUT_POST} instead, matching what every other anchored write
   * path does.
   *
   * <p>The warehouse's fallback bin is resolved at most once per document rather than once per
   * line: it depends only on {@code doc}'s warehouse, and Hibernate's L1 cache does not
   * deduplicate criteria queries.
   */
  static void assignBinsToLines(ShipmentInOut doc) {
    Warehouse headerWarehouse = doc.getWarehouse();
    Locator warehouseAnchorBin = null;
    boolean anchorBinResolved = false;

    for (ShipmentInOutLine line : doc.getMaterialMgmtShipmentInOutLineList()) {
      Locator current = line.getStorageBin();
      Locator candidate = resolveCandidateBin(line);

      Locator target;
      if (headerWarehouse == null || headerWarehouse.getId() == null
          || NeoHandlerUtils.locatorBelongsToWarehouse(candidate, headerWarehouse)) {
        target = candidate;
      } else {
        if (!anchorBinResolved) {
          warehouseAnchorBin = NeoHandlerUtils.resolveWarehouseAnchorBin(headerWarehouse, log);
          anchorBinResolved = true;
        }
        target = warehouseAnchorBin;
      }

      applyStorageBinIfChanged(line, current, target);
    }
    OBDal.getInstance().flush();
  }

  /**
   * Returns the line's own bin, or — when the line has none — the bin carried by the source
   * ({@code canceledInoutLine}) line. Same evaluation order as before the extraction.
   */
  private static Locator resolveCandidateBin(ShipmentInOutLine line) {
    Locator current = line.getStorageBin();
    if (current != null) {
      return current;
    }
    ShipmentInOutLine origLine = line.getCanceledInoutLine();
    return (origLine != null) ? origLine.getStorageBin() : null;
  }

  /**
   * Writes {@code target} onto {@code line} only when it differs from {@code current}, saving the
   * line in that case. Same "changed" ternary as before the extraction.
   */
  private static void applyStorageBinIfChanged(ShipmentInOutLine line, Locator current, Locator target) {
    boolean changed = (target == null)
        ? current != null
        : (current == null || !target.getId().equals(current.getId()));
    if (changed) {
      line.setStorageBin(target);
      OBDal.getInstance().save(line);
    }
  }

  // ---------------------------------------------------------------------------
  // Document type lookup – shared between both return header handlers
  // ---------------------------------------------------------------------------

  /**
   * Resolves the document type to use for an auto-generated return invoice, scoped to the
   * given organization (falling back to org {@code '0'}, then to the first active candidate).
   *
   * @param orgId               the shipment/receipt's organization id
   * @param docCategory         the {@code DocBaseType} to match (e.g. {@code "ARC"}, {@code "APC"})
   * @param isSales             whether to match sales-side ({@code true}) or purchase-side document types
   * @param requireReturn       whether the doc type must have {@code IsReturn = requireReturn}
   * @param requireRectificative
   *     when {@code true}, additionally requires {@code EM_Etsg_Isrectificative = 'Y'} (ETP-4737
   *     unified "Factura Rectificativa" type) — this is how the new rectificative doc type is
   *     distinguished from a legacy doc type sharing the same {@code docCategory}. Silently
   *     ignored (no-op) when the column is not present in this database (SIF General module not
   *     installed — see {@link RectificativeSupport#isColumnPresent()}), so the lookup degrades
   *     gracefully to the plain category-based match in deployments without the module.
   */
  static DocumentType findReturnDocTypeForOrg(String orgId, String docCategory,
      boolean isSales, boolean requireReturn, boolean requireRectificative) {
    OBCriteria<DocumentType> crit = OBDal.getInstance().createCriteria(DocumentType.class)
        .add(Restrictions.eq(DocumentType.PROPERTY_DOCUMENTCATEGORY, docCategory))
        .add(Restrictions.eq(DocumentType.PROPERTY_SALESTRANSACTION, isSales))
        .add(Restrictions.eq(DocumentType.PROPERTY_ACTIVE, true));
    crit.add(Restrictions.eq(DocumentType.PROPERTY_RETURN, requireReturn));
    if (requireRectificative && RectificativeSupport.isColumnPresent()) {
      crit.add(Restrictions.eq(DocumentType.PROPERTY_ETSGISRECTIFICATIVE, true));
    }
    crit.addOrderBy(DocumentType.PROPERTY_DEFAULT, false);
    List<DocumentType> candidates = crit.list();
    for (DocumentType dt : candidates) {
      if (orgId.equals(dt.getOrganization().getId())) return dt;
    }
    for (DocumentType dt : candidates) {
      if ("0".equals(dt.getOrganization().getId())) return dt;
    }
    return candidates.isEmpty() ? null : candidates.get(0);
  }

  // ---------------------------------------------------------------------------
  // Return invoice header – shared between both return header handlers
  // ---------------------------------------------------------------------------

  static Invoice buildReturnInvoiceHeader(ShipmentInOut doc, DocumentType docType,
      Invoice sourceInvoice, boolean isSales) {
    BusinessPartner bp = doc.getBusinessPartner();
    Invoice invoice = OBProvider.getInstance().get(Invoice.class);
    invoice.setClient(doc.getClient());
    invoice.setOrganization(doc.getOrganization());
    invoice.setDocumentType(docType);
    invoice.setTransactionDocument(docType);
    invoice.setDocumentStatus("DR");
    invoice.setDocumentAction("CO");
    invoice.setSalesTransaction(isSales);
    invoice.setInvoiceDate(new Date());
    invoice.setAccountingDate(new Date());
    invoice.setBusinessPartner(bp);
    invoice.setPartnerAddress(doc.getPartnerAddress());
    invoice.setDocumentNo("<*>");
    invoice.setSummedLineAmount(BigDecimal.ZERO);
    invoice.setGrandTotalAmount(BigDecimal.ZERO);
    invoice.setWithholdingamount(BigDecimal.ZERO);
    if (sourceInvoice != null) {
      invoice.setCurrency(sourceInvoice.getCurrency());
      invoice.setPriceList(sourceInvoice.getPriceList());
      invoice.setPaymentTerms(sourceInvoice.getPaymentTerms());
      invoice.setPaymentMethod(sourceInvoice.getPaymentMethod());
    } else {
      applyBusinessPartnerFinancials(invoice, bp, isSales);
    }
    // ETP-4888: this header is built directly via OBProvider, bypassing the normal NEO CRUD
    // "new record" HTTP path that would otherwise resolve every declared contract.json
    // derivation (e.g. SII/SIF fields like etsgDateOperation/aeatsiiFechaRegCont). Fields
    // already set above are never overwritten — only properties still blank are filled in.
    // 4th OBProvider-direct invoice-header path fixed today alongside
    // NeoCommercialDocumentFactory#createInvoiceFromOrderHeader/#createInvoiceFromReceiptHeader
    // and CreateDraftInvoiceHandler#createInvoiceHeaderFromShipment. `doc` (the return
    // shipment/receipt this credit note is generated from) plays the same "parentId" role as
    // `order`/`receipt` at those sites.
    NeoBackgroundDefaultsService.applyDeclaredDefaultsToBackgroundEntity(
        isSales ? "sales-invoice" : "purchase-invoice", "header", invoice, doc.getId());
    return invoice;
  }

  private static void applyBusinessPartnerFinancials(Invoice invoice, BusinessPartner bp, boolean isSales) {
    if (isSales) {
      if (bp.getPaymentTerms() == null || bp.getPaymentMethod() == null) {
        throw new OBException("Business Partner is missing mandatory Payment Terms or Payment Method");
      }
      invoice.setPriceList(bp.getPriceList());
      invoice.setCurrency(resolveInvoiceCurrency(bp.getPriceList(), invoice));
      invoice.setPaymentTerms(bp.getPaymentTerms());
      invoice.setPaymentMethod(bp.getPaymentMethod());
    } else {
      if (bp.getPOPaymentTerms() == null || bp.getPOPaymentMethod() == null) {
        throw new OBException("Business Partner is missing mandatory PO Payment Terms or PO Payment Method");
      }
      invoice.setPriceList(bp.getPurchasePricelist());
      invoice.setCurrency(resolveInvoiceCurrency(bp.getPurchasePricelist(), invoice));
      invoice.setPaymentTerms(bp.getPOPaymentTerms());
      invoice.setPaymentMethod(bp.getPOPaymentMethod());
    }
    if (invoice.getCurrency() == null) {
      throw new OBException("Business Partner is missing mandatory "
          + (isSales ? "Price List" : "Purchase Price List")
          + " (or its Currency) required to create a " + (isSales ? "Sales" : "Purchase") + " invoice");
    }
  }

  /**
   * ETP-4737: {@code applyBusinessPartnerFinancials} used to set the invoice currency ONLY from
   * the BP's (sales/purchase) price list, with no fallback — a vendor/customer with no price list
   * (or a price list with no currency) left {@code Invoice.currency} {@code null}, which Postgres
   * then rejected with a raw {@code NOT NULL} violation on {@code c_invoice.c_currency_id} at save
   * time instead of a clean validation message.
   *
   * <p>Falls back, in order, to: the invoice organization's own currency, its legal entity's
   * currency, then the client's base currency — the same resolution chain core already uses for
   * "what currency applies to this organization" (see
   * {@link OBCurrencyUtils#getOrgCurrency(String)}), so a BP that is merely missing its
   * price-list-specific currency still gets a sensible working default instead of a hard failure.
   * Returns {@code null} only if that chain also fails to resolve anything, which the caller turns
   * into an {@link OBException} instead of letting a null propagate to the DB save.
   */
  private static Currency resolveInvoiceCurrency(PriceList priceList, Invoice invoice) {
    if (priceList != null && priceList.getCurrency() != null) {
      return priceList.getCurrency();
    }
    String orgCurrencyId = OBCurrencyUtils.getOrgCurrency(invoice.getOrganization().getId());
    return orgCurrencyId != null ? OBDal.getInstance().get(Currency.class, orgCurrencyId) : null;
  }

  // ---------------------------------------------------------------------------
  // Return shipment line builder – shared between both return header handlers
  // ---------------------------------------------------------------------------

  /**
   * Imports {@code sourceLine} into {@code doc} as a return line and persists it.
   *
   * <p>ETP-4863: the line shell (including the header-warehouse anchoring of the storage bin) is
   * built by {@link NeoReturnReceiptService#createReturnLineShell} — the two implementations were
   * byte-for-byte identical, so the shell now lives in exactly one place and there is a single
   * spot where the locator rule can drift. Only the quantity handling stays here: this flow takes
   * a caller-computed {@code qty} and deliberately does NOT apply the proportional
   * order-UOM/order-quantity projection that {@code NeoReturnReceiptService}'s own wrapper does.
   */
  static void buildAndSaveReturnLine(ShipmentInOut doc, ShipmentInOutLine sourceLine,
      long lineNo, BigDecimal qty) {
    ShipmentInOutLine retLine =
        NeoReturnReceiptService.createReturnLineShell(doc, sourceLine, lineNo);
    retLine.setMovementQuantity(qty);
    OBDal.getInstance().save(retLine);
  }

  // ---------------------------------------------------------------------------
  // Available document / line row builders – shared result-set mappers
  // ---------------------------------------------------------------------------

  static JSONObject buildAvailableDocumentRow(ResultSet rs) throws Exception {
    JSONObject row = new JSONObject();
    row.put("id", rs.getString(1));
    row.put(FIELD_DOCUMENT_NO, rs.getString(2));
    row.put("movementDate", rs.getString(3));
    row.put("businessPartner$_identifier", rs.getString(4));
    row.put("businessPartner", rs.getString(5));
    return row;
  }

  static JSONObject buildAvailableLineRow(ResultSet rs) throws Exception {
    JSONObject row = new JSONObject();
    row.put("id", rs.getString(1));
    row.put("product", rs.getString(2));
    row.put("product$_identifier", rs.getString(3));
    row.put("uOM", rs.getString(4));
    row.put("movementQuantity", rs.getBigDecimal(5));
    return row;
  }

  // ---------------------------------------------------------------------------
  // SQL helpers – source documents (shipments or receipts – same query)
  // ---------------------------------------------------------------------------

  @SuppressWarnings("java:S2077")
  static Map<String, List<JSONObject>> fetchSourceDocuments(List<String> ids) {
    Map<String, List<JSONObject>> result = new HashMap<>();
    if (ids.isEmpty()) return result;
    String placeholders = ids.stream().map(id -> "?").collect(Collectors.joining(","));
    String sql =
        "SELECT DISTINCT l.M_InOut_ID, src.M_InOut_ID, src.DocumentNo, src.DocStatus " +
        "FROM M_InOutLine l " +
        "JOIN M_InOutLine orig ON orig.M_InOutLine_ID = l.Canceled_Inoutline_ID " +
        "JOIN M_InOut src ON src.M_InOut_ID = orig.M_InOut_ID " +
        "WHERE l.M_InOut_ID IN (" + placeholders + ") " +
        "  AND l.Canceled_Inoutline_ID IS NOT NULL";
    Connection conn = OBDal.getInstance().getConnection();
    try (PreparedStatement ps = conn.prepareStatement(sql)) {
      for (int i = 0; i < ids.size(); i++) ps.setString(i + 1, ids.get(i));
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          addSourceDocToMap(result, rs);
        }
      }
    } catch (Exception e) {
      log.warn("Error fetching source documents: {}", e.getMessage());
    }
    return result;
  }

  private static void addSourceDocToMap(Map<String, List<JSONObject>> result, ResultSet rs) {
    try {
      String ownerId = rs.getString(1);
      JSONObject doc = new JSONObject();
      doc.put("id", rs.getString(2));
      doc.put(FIELD_DOCUMENT_NO, rs.getString(3));
      doc.put(FIELD_DOCUMENT_STATUS, rs.getString(4));
      result.computeIfAbsent(ownerId, k -> new ArrayList<>()).add(doc);
    } catch (Exception je) {
      log.warn("Error building source document JSON: {}", je.getMessage());
    }
  }

  // ---------------------------------------------------------------------------
  // SQL helpers – return invoices
  // ---------------------------------------------------------------------------

  @SuppressWarnings("java:S2077")
  static Map<String, List<JSONObject>> fetchReturnInvoices(List<String> ids) {
    Map<String, List<JSONObject>> result = new HashMap<>();
    if (ids.isEmpty()) return result;
    String placeholders = ids.stream().map(id -> "?").collect(Collectors.joining(","));
    String sql =
        "SELECT DISTINCT l.M_InOut_ID, i.C_Invoice_ID, i.DocumentNo, i.DocStatus, " +
        "  i.GrandTotal, cur.ISO_Code " +
        "FROM M_InOutLine l " +
        "JOIN C_InvoiceLine il ON il.M_InOutLine_ID = l.M_InOutLine_ID " +
        "JOIN C_Invoice i ON i.C_Invoice_ID = il.C_Invoice_ID " +
        "LEFT JOIN C_Currency cur ON cur.C_Currency_ID = i.C_Currency_ID " +
        "WHERE l.M_InOut_ID IN (" + placeholders + ") " +
        "  AND i.DocStatus != 'VO'";
    Connection conn = OBDal.getInstance().getConnection();
    try (PreparedStatement ps = conn.prepareStatement(sql)) {
      for (int i = 0; i < ids.size(); i++) ps.setString(i + 1, ids.get(i));
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          addInvoiceToMap(result, rs);
        }
      }
    } catch (Exception e) {
      log.warn("Error fetching return invoices: {}", e.getMessage());
    }
    return result;
  }

  private static void addInvoiceToMap(Map<String, List<JSONObject>> result, ResultSet rs) {
    try {
      String ownerId = rs.getString(1);
      JSONObject inv = new JSONObject();
      inv.put("id", rs.getString(2));
      inv.put(FIELD_DOCUMENT_NO, rs.getString(3));
      inv.put(FIELD_DOCUMENT_STATUS, rs.getString(4));
      BigDecimal total = rs.getBigDecimal(5);
      inv.put("grandTotalAmount", total != null ? total : JSONObject.NULL);
      String iso = rs.getString(6);
      inv.put("currency$_identifier", iso != null ? iso : JSONObject.NULL);
      result.computeIfAbsent(ownerId, k -> new ArrayList<>()).add(inv);
    } catch (Exception je) {
      log.warn("Error building returnInvoice JSON: {}", je.getMessage());
    }
  }

  // ---------------------------------------------------------------------------
  // SQL helpers – line counts and max line number
  // ---------------------------------------------------------------------------

  /**
   * Shared shape for a batch "one int per M_InOut id" query: builds the {@code IN (?,?,...)}
   * placeholder list, binds the ids, and collects column 1 (id) / column 2 (int value) into
   * a map. {@code sqlTemplate} must contain exactly one {@code %s} for the placeholder list.
   */
  @SuppressWarnings("java:S2077")
  private static Map<String, Integer> fetchIntByInOutIds(List<String> ids, String sqlTemplate, String errorContext) {
    Map<String, Integer> result = new HashMap<>();
    if (ids.isEmpty()) return result;
    String placeholders = ids.stream().map(id -> "?").collect(Collectors.joining(","));
    String sql = String.format(sqlTemplate, placeholders);
    Connection conn = OBDal.getInstance().getConnection();
    try (PreparedStatement ps = conn.prepareStatement(sql)) {
      for (int i = 0; i < ids.size(); i++) ps.setString(i + 1, ids.get(i));
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) result.put(rs.getString(1), rs.getInt(2));
      }
    } catch (Exception e) {
      log.warn("Error {}: {}", errorContext, e.getMessage());
    }
    return result;
  }

  static Map<String, Integer> fetchLineCounts(List<String> ids) {
    return fetchIntByInOutIds(ids,
        "SELECT M_InOut_ID, COUNT(M_InOutLine_ID) FROM M_InOutLine WHERE M_InOut_ID IN (%s) GROUP BY M_InOut_ID",
        "fetching line counts");
  }

  /**
   * Batch-computes {@code invoiceStatus} (0-100) for a set of shipment/receipt ids using
   * the core {@code C_GETINVOICESTATUSFROMSHIPMENT} DB function — the same function the
   * {@code ShipmentInOut} Hibernate entity uses for its computed-column mapping, called
   * explicitly here because list/grid responses don't hydrate full entities.
   */
  static Map<String, Integer> fetchInvoiceStatuses(List<String> ids) {
    return fetchIntByInOutIds(ids,
        "SELECT M_InOut_ID, C_GETINVOICESTATUSFROMSHIPMENT(M_InOut_ID) FROM M_InOut WHERE M_InOut_ID IN (%s)",
        "fetching invoice statuses");
  }

  @SuppressWarnings("java:S2077")
  static long fetchMaxLineNo(String inoutId) {
    String sql = "SELECT COALESCE(MAX(Line), 0) FROM M_InOutLine WHERE M_InOut_ID = ?";
    Connection conn = OBDal.getInstance().getConnection();
    try (PreparedStatement ps = conn.prepareStatement(sql)) {
      ps.setString(1, inoutId);
      try (ResultSet rs = ps.executeQuery()) {
        if (rs.next()) return rs.getLong(1);
      }
    } catch (Exception e) {
      log.warn("Could not fetch max lineNo for inout {}: {}", inoutId, e.getMessage());
    }
    return 0;
  }

  // ---------------------------------------------------------------------------
  // afterHandle enrichment – shared between both return header handlers
  // ---------------------------------------------------------------------------

  static void enrichReturnRecord(JSONObject rec, String id,
      Map<String, List<JSONObject>> sourceDocsMap, String sourceDocsField, String sourceDocNoField,
      Map<String, List<JSONObject>> returnInvoicesMap,
      Map<String, Integer> lineCountMap,
      Map<String, Integer> invoiceStatusMap) throws Exception {
    List<JSONObject> sourceDocs = sourceDocsMap.getOrDefault(id, Collections.emptyList());
    JSONArray sourceDocsArr = new JSONArray();
    for (JSONObject d : sourceDocs) {
      sourceDocsArr.put(d);
    }
    rec.put(sourceDocsField, sourceDocsArr);
    if (!sourceDocs.isEmpty()) {
      String combined = sourceDocs.stream()
          .map(d -> d.optString(FIELD_DOCUMENT_NO, ""))
          .filter(s -> !s.isEmpty())
          .collect(Collectors.joining(", "));
      if (!combined.isEmpty()) {
        rec.put(sourceDocNoField, combined);
      }
    }
    List<JSONObject> invoices = returnInvoicesMap.getOrDefault(id, Collections.emptyList());
    JSONArray invoicesArr = new JSONArray();
    for (JSONObject inv : invoices) {
      invoicesArr.put(inv);
    }
    rec.put("returnInvoices", invoicesArr);
    rec.put("hasReturnInvoice", !invoices.isEmpty());
    rec.put("linesCount", lineCountMap.getOrDefault(id, 0));
    rec.put(FIELD_INVOICE_STATUS, invoiceStatusMap.getOrDefault(id, 0));
  }

  // ---------------------------------------------------------------------------
  // Invoice finalization – shared between both return header handlers
  // ---------------------------------------------------------------------------

  /**
   * Builds the rectificative invoice's lines, links it to the invoice(s) it rectifies, and
   * completes it — in that order (ETP-5381).
   *
   * <p><b>The order is not stylistic.</b> The {@code C_INVOICE_REVERSE_TRG} database trigger
   * rejects any insert into {@code C_Invoice_Reverse} once the invoice is {@code Processed='Y'},
   * so the link can only be created while the invoice is still a draft. And the link must exist
   * before completing, because {@code ETSG_CHECK_RECTIF_INV_DOC} rejects a rectificative document
   * type with no rectified invoices attached ("El tipo de documento es rectificativo, pero no se
   * han asociado facturas a rectificar"). Complete first and the invoice can never be confirmed
   * nor linked — a permanently stuck document.
   *
   * @param originInvoiceIds ids of the invoices being rectified; must not be empty
   */
  static NeoResponse finalizeReturnInvoice(Invoice invoice, List<ShipmentInOutLine> lines,
      CreateDraftInvoiceHandler createDraftInvoiceHandler, List<String> originInvoiceIds,
      OBContext obContext) throws Exception {
    addReturnInvoiceLines(invoice, lines, createDraftInvoiceHandler);
    OBDal.getInstance().flush();
    OBDal.getInstance().getSession().refresh(invoice);
    createDraftInvoiceHandler.ensureDocumentNo(invoice);
    createDraftInvoiceHandler.getSupport().ensureLineGrossAmounts(invoice);
    createDraftInvoiceHandler.recalculateTotals(invoice);
    linkRectifiedInvoices(invoice, originInvoiceIds);
    OBDal.getInstance().flush();

    String invoiceId = invoice.getId();
    InvoiceCompletionService.completeInvoiceOrThrow(invoiceId, obContext);
    Invoice completed = OBDal.getInstance().get(Invoice.class, invoiceId);

    JSONObject data = new JSONObject();
    data.put("id", invoiceId);
    data.put(FIELD_DOCUMENT_NO, completed.getDocumentNo());
    data.put(FIELD_DOCUMENT_STATUS, completed.getDocumentStatus());
    return wrapOkData(data);
  }

  /**
   * Creates the {@code C_Invoice_Reverse} rows tying a rectificative invoice to the invoices it
   * rectifies, skipping any link that already exists.
   *
   * <p>Note the DB trigger also requires both invoices to share a business partner; candidates are
   * sourced from the return document's own origin invoices, so that holds by construction.
   */
  static void linkRectifiedInvoices(Invoice invoice, List<String> originInvoiceIds) {
    if (originInvoiceIds == null || originInvoiceIds.isEmpty()) {
      throw new OBException(ERR_RECTIFIED_INVOICE_REQUIRED);
    }
    for (String originId : originInvoiceIds) {
      Invoice origin = OBDal.getInstance().get(Invoice.class, originId);
      if (origin == null) {
        throw new OBException("Invoice to rectify not found: " + originId);
      }
      OBCriteria<ReversedInvoice> existing = OBDal.getInstance().createCriteria(ReversedInvoice.class);
      existing.add(Restrictions.eq(ReversedInvoice.PROPERTY_INVOICE, invoice));
      existing.add(Restrictions.eq(ReversedInvoice.PROPERTY_REVERSEDINVOICE, origin));
      if (!existing.list().isEmpty()) {
        continue;
      }
      ReversedInvoice link = OBProvider.getInstance().get(ReversedInvoice.class);
      link.setClient(invoice.getClient());
      link.setOrganization(invoice.getOrganization());
      link.setInvoice(invoice);
      link.setReversedInvoice(origin);
      OBDal.getInstance().save(link);
    }
  }

  /**
   * Resolves which invoices a rectificative invoice will rectify: the ones the caller explicitly
   * picked, or — when the caller picked none — the newest invoice auto-detected from the return
   * lines.
   *
   * <p>The fallback reads from {@link #fetchAutoDetectedInvoices}, NOT from the broader selectable
   * list: that list is every confirmed invoice of the flow, so falling back to its first entry
   * would silently rectify an arbitrary unrelated invoice. Only the document chain
   * (return line → original line → its invoice) is a safe automatic answer.
   *
   * <p>Never returns empty: a rectificative invoice with no rectified invoice cannot be confirmed,
   * so failing here — before anything is written — is strictly better than creating a document
   * that is stuck in draft forever.
   */
  static List<String> resolveRectifiedInvoiceIds(JSONObject body, String inOutId) {
    List<String> ids = new ArrayList<>();
    JSONArray requested = body != null ? body.optJSONArray(PARAM_ORIGIN_INVOICES) : null;
    if (requested != null) {
      for (int i = 0; i < requested.length(); i++) {
        String id = requested.optString(i, null);
        if (id != null && !id.isBlank() && !ids.contains(id)) {
          ids.add(id);
        }
      }
    }
    if (ids.isEmpty()) {
      List<JSONObject> detected = fetchAutoDetectedInvoices(inOutId);
      if (!detected.isEmpty()) {
        ids.add(detected.get(0).optString("id"));
      }
    }
    if (ids.isEmpty()) {
      throw new OBException(ERR_RECTIFIED_INVOICE_REQUIRED);
    }
    return ids;
  }

  /**
   * Request-facing overload: reads the paging window off the request body and delegates.
   *
   * <p><b>Read from the REQUEST BODY, not the query string</b>, even though the list windows page
   * with {@code _startRow}/{@code _endRow} query parameters. An action endpoint reaches its handler
   * through {@code NeoHookDispatcher#buildHookContext}, which populates {@code recordId} and
   * {@code requestBody} but NOT {@code queryParams} — only {@code NeoRequestRouter} does that. So
   * {@code context.getQueryParams()} is null here, and a query-string implementation would have
   * silently served the first batch forever: no error, no log, just a picker that never paged.
   * The action is already a POST carrying a JSON body, so the body is where the window belongs.
   *
   * <p>Absent keys mean "first batch", which keeps any caller that sends {@code {}} working.
   *
   * @param context the action request, read for {@code startRow}, {@code pageSize} and
   *                {@code search}
   * @param inOutId the return document
   */
  static NeoResponse buildRectifiableInvoicesResponse(NeoContext context, String inOutId)
      throws Exception {
    JSONObject body = context != null ? context.getRequestBody() : null;
    int startRow = parseIntFromBody(body, "startRow", 0);
    int pageSize = parseIntFromBody(body, "pageSize", DEFAULT_RECTIFIABLE_PAGE_SIZE);
    String search = body != null ? body.optString("search", null) : null;
    return buildRectifiableInvoicesResponse(inOutId, startRow, pageSize, search);
  }

  /**
   * Reads a non-negative integer from the request body, falling back to {@code fallback} when it is
   * absent or not a number. A malformed value is treated as absent rather than as an error: the
   * caller is asking for a page of a picker, and failing the whole action over a bad offset would
   * hide the list instead of showing its first batch.
   *
   * <p>Reads through {@code optString} rather than {@code optInt} so a value sent as a JSON string
   * ({@code "80"}) is accepted too — the frontend builds this body from state that may hold either.
   */
  private static int parseIntFromBody(JSONObject body, String name, int fallback) {
    String raw = body != null ? body.optString(name, null) : null;
    if (raw == null || raw.isBlank()) {
      return fallback;
    }
    try {
      return Math.max(0, Integer.parseInt(raw.trim()));
    } catch (NumberFormatException e) {
      return fallback;
    }
  }

  /**
   * Builds the {@code rectifiableInvoices} action payload: the candidate invoices, which one was
   * auto-detected (so the UI can preselect it), and whether this return document already has an
   * invoice (so the UI can disable the option instead of letting the user hit a 409).
   *
   * <p>Shared by both return header handlers — the logic is identical on the sales and purchase
   * sides, only the document type differs, and that is resolved elsewhere.
   *
   * <p><b>Paged and searched server-side (ETP-5381)</b>, mirroring how the invoice list window
   * pages through {@code useEntity}: the caller asks for a window of rows and the response says
   * whether more exist. A slow connection should not wait for the whole candidate set before the
   * picker can open.
   *
   * <p>Two invariants the paging must not break:
   * <ul>
   *   <li><b>The detected rows ride on the FIRST batch only.</b> They are preselected, so a
   *       suggested id whose row has not been fetched yet would render as a chip with no matching
   *       row. Merging them on every batch instead would duplicate them down the list.</li>
   *   <li><b>{@code suggestedInvoiceIds} ships on every batch.</b> It is small, it is the same
   *       answer each time, and the client must not have to remember which batch defined it.</li>
   * </ul>
   *
   * @param inOutId  the return document
   * @param startRow first row to return, 0-based
   * @param pageSize how many rows to return
   * @param search   optional case-insensitive fragment matched against document number and partner
   *                 name; blank means no filter. Applied in SQL, never client-side — the client
   *                 only holds the batches it fetched, so filtering there would silently search a
   *                 subset and report "no matches" for a row that exists further down.
   */
  static NeoResponse buildRectifiableInvoicesResponse(String inOutId, int startRow, int pageSize,
      String search) throws Exception {
    boolean firstBatch = startRow <= 0;
    // The chain-detected rows are flagged so the UI can float them to the top and preselect. The
    // chain only limits the SUGGESTION, never the CHOICE: a return created standalone has no chain
    // at all, and one covering two invoiced shipments needs both.
    List<JSONObject> detected = firstBatch
        ? fetchAutoDetectedInvoices(inOutId)
        : Collections.emptyList();
    Set<String> detectedIds = new HashSet<>();
    for (JSONObject inv : detected) {
      detectedIds.add(inv.optString("id"));
    }
    List<JSONObject> selectable = fetchSelectableInvoices(inOutId, startRow, pageSize, search);
    // A full batch means "there may be more"; a short one is the end of the set. Same signal
    // useEntity reads (`rows.length < BATCH_SIZE` → no more), so the client needs no total count
    // and we avoid a second COUNT(*) per scroll.
    boolean hasMore = selectable.size() >= pageSize;
    Set<String> selectableIds = new HashSet<>();
    for (JSONObject inv : selectable) {
      selectableIds.add(inv.optString("id"));
    }
    // A detected invoice outside the first page must still be offered: otherwise its id ships in
    // suggestedInvoiceIds, the frontend drops it for having no matching row, and the user sees an
    // empty preselection with no error and no way to reach it from the picker.
    JSONArray arr = new JSONArray();
    for (JSONObject inv : detected) {
      if (!selectableIds.contains(inv.optString("id"))) {
        inv.put("suggested", true);
        arr.put(inv);
      }
    }
    for (JSONObject inv : selectable) {
      inv.put("suggested", detectedIds.contains(inv.optString("id")));
      arr.put(inv);
    }
    JSONObject data = new JSONObject();
    data.put("invoices", arr);
    data.put("hasMore", hasMore);
    data.put("startRow", Math.max(0, startRow));
    data.put("hasReturnInvoice", hasNonVoidedReturnInvoice(inOutId));
    // EVERY chain-detected invoice is reported, not just the newest — what the client does with
    // them is the client's call, and this field is also what badges the rows as related.
    //
    // The client no longer preselects them all (ETP-5381): one detected invoice is preselected,
    // two or more are not. An order billed across two invoices and returned once makes the chain
    // find both, and the chain does not know which the user means to rectify. That decision lives
    // in `useRectifiableInvoices`, deliberately — the backend reports what it found, it does not
    // decide what gets rectified. Recomputed rather than carried on the first batch only, so the
    // client never depends on batch order.
    JSONArray suggestedIds = new JSONArray();
    for (JSONObject inv : firstBatch ? detected : fetchAutoDetectedInvoices(inOutId)) {
      suggestedIds.put(inv.optString("id"));
    }
    data.put("suggestedInvoiceIds", suggestedIds);
    return wrapOkData(data);
  }

  /**
   * ETP-5381 (guard P5): true when the return document already has a non-voided invoice.
   *
   * <p>Deliberately shares its predicate with {@link #fetchReturnInvoices} — the same join, the
   * same {@code DocStatus != 'VO'} filter — so this guard and the {@code hasReturnInvoice} flag
   * the UI hides its button with can never disagree.
   */
  @SuppressWarnings("java:S2077")
  static boolean hasNonVoidedReturnInvoice(String inOutId) {
    String sql =
        "SELECT 1 " +
        "FROM M_InOutLine l " +
        "JOIN C_InvoiceLine il ON il.M_InOutLine_ID = l.M_InOutLine_ID " +
        "JOIN C_Invoice i ON i.C_Invoice_ID = il.C_Invoice_ID " +
        "WHERE l.M_InOut_ID = ? " +
        "  AND i.DocStatus != 'VO' " +
        "LIMIT 1";
    // getConnection() inside the try on purpose: acquiring the connection is exactly the step
    // that fails when the DB is down, and leaving it outside would let that escape as a raw
    // RuntimeException instead of the OBException this method's contract promises.
    try (PreparedStatement ps = OBDal.getInstance().getConnection().prepareStatement(sql)) {
      ps.setString(1, inOutId);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next();
      }
    } catch (Exception e) {
      // Propagate: silently answering "no invoice" would let a duplicate through, which is the
      // exact failure this guard exists to prevent.
      log.error("Error checking existing return invoices for {}: {}", inOutId, e.getMessage(), e);
      throw new OBException("Could not verify existing invoices for this return document", e);
    }
  }

  /**
   * Lists the invoices auto-detected from the return document's own chain: walks
   * {@code M_InOutLine.Canceled_Inoutline_ID} back to the original shipment/receipt line and from
   * there to the invoices that billed it — the same navigation
   * {@code SalesInvoiceHeaderHandler.enrichSourceInvoice} uses, only starting from the return
   * document instead of the invoice. There is no header-level link between a return and its
   * original document, so this has to go line by line.
   *
   * <p>These are only a <b>suggestion</b>. The chain exists just for returns created from a
   * shipment that was itself invoiced; a hand-made return has none, and a return covering two
   * shipments billed on two invoices has more than one. Never use this as the selectable list —
   * see {@link #fetchSelectableInvoices}.
   *
   * <p>Only {@code CO} invoices qualify: a draft cannot be rectified, and a voided one has
   * nothing left to rectify.
   */
  @SuppressWarnings("java:S2077")
  static List<JSONObject> fetchAutoDetectedInvoices(String inOutId) {
    String sql =
        "SELECT DISTINCT i.C_Invoice_ID, i.DocumentNo, i.DateInvoiced, i.GrandTotal, " +
        "  cur.ISO_Code, bp.Name " +
        "FROM M_InOutLine rl " +
        "JOIN M_InOutLine ol ON ol.M_InOutLine_ID = rl.Canceled_Inoutline_ID " +
        "JOIN C_InvoiceLine il ON il.M_InOutLine_ID = ol.M_InOutLine_ID " +
        "JOIN C_Invoice i ON i.C_Invoice_ID = il.C_Invoice_ID " +
        "LEFT JOIN C_Currency cur ON cur.C_Currency_ID = i.C_Currency_ID " +
        "LEFT JOIN C_BPartner bp ON bp.C_BPartner_ID = i.C_BPartner_ID " +
        "WHERE rl.M_InOut_ID = ? " +
        "  AND rl.Canceled_Inoutline_ID IS NOT NULL " +
        "  AND i.DocStatus = 'CO' " +
        "ORDER BY i.DateInvoiced DESC";
    return runInvoiceQuery(sql, Collections.singletonList(inOutId), "auto-detected",
        "Could not load the invoices detected for this return");
  }

  /**
   * Lists one batch of the invoices the user may pick to rectify: the confirmed invoices of the
   * same flow (sales or purchase) AND the same business partner as the return document, ordered by
   * document number then invoice date, newest first, windowed by {@code startRow}/{@code pageSize}
   * and optionally narrowed by {@code search}.
   *
   * <p>Deliberately NOT restricted to the return document's own chain. A return can be created
   * standalone — no order, no source invoice — and then the chain yields nothing even though
   * rectifying is perfectly legitimate; and a return covering two shipments billed on two separate
   * invoices has to be able to name both. The chain still drives the suggestion, but it must not
   * limit the choice.
   *
   * <p><b>Business partner IS filtered</b> — only invoices of the return document's own partner.
   * An earlier revision of this method deliberately did NOT filter, on the stated grounds that
   * "{@code C_Invoice_Reverse} enforces same-BP only where it applies (Verifactu orgs permit
   * cross-BP rectifications)" and that filtering "would hide rows the database would have
   * accepted". <b>That rationale was wrong.</b> Read the trigger: its own title is "Check the
   * introduced BP is the same as the Invoice", and the check
   * ({@code IF v_bpheader_id <> v_bpreversed_id THEN RAISE_APPLICATION_ERROR('@NotEqualBPartner@')})
   * is unconditional — Openbravo core, no Verifactu branch, no module gate. So the unfiltered list
   * did the opposite of what the comment claimed: it offered rows the database ALWAYS rejects, and
   * the user only found out on save. Filtering removes impossible choices, it does not remove
   * legitimate ones.
   *
   * <p>The same reasoning applies to {@link #fetchAutoDetectedInvoices}, which is NOT filtered here
   * because its chain (return line → cancelled shipment line → invoice line) can only reach another
   * partner's invoice through anomalous data. If that ever happens the suggestion would preselect an
   * invoice whose link insert is guaranteed to fail — worth revisiting if it is ever observed.
   *
   * <p>Organization IS filtered, unlike business partner. This is raw JDBC, so none of DAL's
   * implicit org scoping applies; without the clause the picker would offer invoices belonging to
   * organizations the current role cannot even read, and the rejection would arrive late (on the
   * link insert) or not at all.
   */
  @SuppressWarnings("java:S2077")
  static List<JSONObject> fetchSelectableInvoices(String inOutId, int startRow, int pageSize,
      String search) {
    // No context means we are outside a request and have nothing to scope by, so the clause is
    // dropped rather than guessed. A context WITH no readable organizations is a different case
    // and stays fail-closed: an empty IN () matches nothing, which is the correct answer for a
    // role that may read none.
    OBContext obContext = OBContext.getOBContext();
    String[] readableOrgs = obContext != null ? obContext.getReadableOrganizations() : null;
    String orgFilter = "";
    if (readableOrgs != null) {
      String placeholders = readableOrgs.length == 0
          ? "''"
          : String.join(",", Collections.nCopies(readableOrgs.length, "?"));
      orgFilter = "  AND i.AD_Org_ID IN (" + placeholders + ") ";
    }
    // Search runs in SQL because the client only holds the batches it has fetched: filtering there
    // would search a subset and answer "no matches" for an invoice that exists further down the
    // set. Matched against the two fields the picker actually renders as text — document number and
    // partner name — so what the user types corresponds to what they can see.
    boolean hasSearch = search != null && !search.isBlank();
    String searchParam = hasSearch ? "%" + search.trim().toLowerCase() + "%" : null;
    String searchFilter = hasSearch
        ? "  AND (LOWER(i.DocumentNo) LIKE ? OR LOWER(COALESCE(bp.Name, '')) LIKE ?) "
        : "";
    // A non-positive page size would turn LIMIT into a silent empty result, so it is clamped rather
    // than trusted; the ceiling keeps a hand-crafted request from asking for the whole table.
    int safePageSize = Math.min(Math.max(1, pageSize), 500);
    String sql =
        "SELECT i.C_Invoice_ID, i.DocumentNo, i.DateInvoiced, i.GrandTotal, " +
        "  cur.ISO_Code, bp.Name " +
        "FROM C_Invoice i " +
        "JOIN M_InOut ret ON ret.M_InOut_ID = ? " +
        "LEFT JOIN C_Currency cur ON cur.C_Currency_ID = i.C_Currency_ID " +
        "LEFT JOIN C_BPartner bp ON bp.C_BPartner_ID = i.C_BPartner_ID " +
        "WHERE i.DocStatus = 'CO' " +
        orgFilter +
        "  AND i.IsSOTrx = ret.IsSOTrx " +
        "  AND i.AD_Client_ID = ret.AD_Client_ID " +
        "  AND i.C_BPartner_ID = ret.C_BPartner_ID " +
        "  AND i.IsActive = 'Y' " +
        searchFilter +
        // Document number first, invoice date second, as the window asks. Note DocumentNo is a
        // VARCHAR, so this is a STRING sort: '9999' sorts after '10000099'. Within one partner and
        // one numbering series the widths match and the order reads naturally, which is the case
        // this picker is for; across series of different widths it can look odd. Kept DESC on both
        // so the newest invoice stays at the top, which is what the list has always done.
        //
        // The ORDER BY is what makes paging correct, not just pretty: OFFSET/LIMIT over an
        // unordered set may repeat or skip rows between batches. C_Invoice_ID is appended as a
        // tie-break so two invoices sharing a number and a date can never straddle a batch
        // boundary in a different order each time.
        "ORDER BY i.DocumentNo DESC, i.DateInvoiced DESC, i.C_Invoice_ID DESC " +
        "LIMIT ? OFFSET ?";
    List<Object> params = new ArrayList<>();
    params.add(inOutId);
    if (readableOrgs != null) {
      params.addAll(Arrays.asList(readableOrgs));
    }
    if (hasSearch) {
      params.add(searchParam);
      params.add(searchParam);
    }
    // Integers, NOT String.valueOf(...): see the binding loop in runInvoiceQuery — a stringified
    // LIMIT/OFFSET is rejected by PostgreSQL outright.
    params.add(safePageSize);
    params.add(Math.max(0, startRow));
    return runInvoiceQuery(sql, params, "selectable", "Could not load the invoices available to rectify");
  }

  /**
   * Shared execution + row mapping for the two invoice-candidate queries above.
   *
   * @param queryName short label identifying which query failed — the two share this method, so a
   *     single generic log line would not say which one blew up
   */
  @SuppressWarnings("java:S2077")
  private static List<JSONObject> runInvoiceQuery(String sql, List<Object> params, String queryName,
      String errorMessage) {
    List<JSONObject> result = new ArrayList<>();
    // getConnection() inside the try — see hasNonVoidedReturnInvoice for why.
    try (PreparedStatement ps = OBDal.getInstance().getConnection().prepareStatement(sql)) {
      for (int i = 0; i < params.size(); i++) {
        Object p = params.get(i);
        // Bind by TYPE, not everything as a string. PostgreSQL types a `setString` parameter as
        // varchar, and `LIMIT`/`OFFSET` demand bigint: binding "80" there fails the whole statement
        // with "argument of OFFSET must be type bigint, not type character varying", before a
        // single row is read. That is not an edge case — the paging clause is unconditional, so it
        // broke every call until this loop learned to bind an Integer with setInt.
        if (p instanceof Integer) {
          ps.setInt(i + 1, (Integer) p);
        } else {
          ps.setString(i + 1, (String) p);
        }
      }
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          JSONObject inv = new JSONObject();
          inv.put("id", rs.getString(1));
          inv.put(FIELD_DOCUMENT_NO, rs.getString(2));
          inv.put("invoiceDate", rs.getDate(3) != null
              ? new SimpleDateFormat("yyyy-MM-dd").format(rs.getDate(3)) : null);
          inv.put("grandTotalAmount", rs.getBigDecimal(4));
          inv.put("currency", rs.getString(5));
          inv.put("businessPartner", rs.getString(6));
          result.add(inv);
        }
      }
    } catch (Exception e) {
      log.error("Error running the {} rectifiable-invoice query for {}: {}",
          queryName, params.isEmpty() ? "?" : params.get(0), e.getMessage(), e);
      throw new OBException(errorMessage, e);
    }
    return result;
  }

  // ---------------------------------------------------------------------------
  // Invoice building helpers
  // ---------------------------------------------------------------------------

  static Invoice findSourceInvoice(List<ShipmentInOutLine> lines) {
    for (ShipmentInOutLine retLine : lines) {
      ShipmentInOutLine origLine = retLine.getCanceledInoutLine();
      if (origLine == null) continue;
      String hql = "SELECT il.invoice FROM InvoiceLine il " +
          "WHERE il.goodsShipmentLine.id = :lineId AND il.invoice.documentStatus != 'VO'";
      List<Invoice> results = OBDal.getInstance().getSession()
          .createQuery(hql, Invoice.class)
          .setParameter("lineId", origLine.getId())
          .setMaxResults(1)
          .list();
      if (!results.isEmpty()) return results.get(0);
    }
    return null;
  }

  static void addReturnInvoiceLines(Invoice invoice, List<ShipmentInOutLine> lines,
      CreateDraftInvoiceHandler createDraftInvoiceHandler) {
    int precision = invoice.getCurrency() != null
        ? invoice.getCurrency().getStandardPrecision().intValue() : 2;
    long lineNo = 10;
    for (ShipmentInOutLine retLine : lines) {
      // ETP-4737: the rectificative invoice line must always come out negative regardless of
      // which sign convention the SOURCE return document uses for movementQuantity — Sales
      // (RFC Receipt) stores it positive, but Purchases (RTV Shipment) already stores it
      // negative, so a blind .negate() flipped Purchase-side rectificativas back to positive
      // (e.g. REC-1000007). abs().negate() forces the correct sign either way without touching
      // the return document's own movementQuantity/convention.
      BigDecimal qty = retLine.getMovementQuantity() != null
          ? retLine.getMovementQuantity().abs().negate() : BigDecimal.ZERO;
      if (retLine.getProduct() == null || qty.compareTo(BigDecimal.ZERO) == 0) continue;
      buildAndSaveInvoiceLine(invoice, retLine, qty, precision, lineNo, createDraftInvoiceHandler);
      lineNo += 10;
    }
  }

  static void buildAndSaveInvoiceLine(Invoice invoice, ShipmentInOutLine retLine,
      BigDecimal qty, int precision, long lineNo, CreateDraftInvoiceHandler createDraftInvoiceHandler) {
    ShipmentInOutLine origLine = retLine.getCanceledInoutLine();
    ShipmentInOutLine pricingLine = (origLine != null) ? origLine : retLine;
    InvoiceLine il = createDraftInvoiceHandler.createShipmentInvoiceLine(invoice, pricingLine, qty, lineNo);
    il.setGoodsShipmentLine(retLine);

    BigDecimal unitPrice = il.getUnitPrice() != null ? il.getUnitPrice() : BigDecimal.ZERO;
    if (unitPrice.compareTo(BigDecimal.ZERO) == 0 && origLine != null) {
      unitPrice = resolvePriceFromSourceInvoiceLine(origLine.getId(), "unitPrice", BigDecimal.ZERO);
      if (unitPrice.compareTo(BigDecimal.ZERO) != 0) {
        BigDecimal listPrice = resolvePriceFromSourceInvoiceLine(origLine.getId(), "listPrice", unitPrice);
        il.setUnitPrice(unitPrice);
        il.setListPrice(listPrice);
        il.setLineNetAmount(qty.multiply(unitPrice).setScale(precision, RoundingMode.HALF_UP));
      }
    }
    if (unitPrice.compareTo(BigDecimal.ZERO) == 0
        && retLine.getProduct() != null && invoice.getPriceList() != null) {
      String productId = retLine.getProduct().getId();
      String priceListId = invoice.getPriceList().getId();
      unitPrice = resolvePriceFromPriceList(productId, priceListId, "standardPrice", BigDecimal.ZERO);
      BigDecimal listPrice = resolvePriceFromPriceList(productId, priceListId, "listPrice", unitPrice);
      il.setUnitPrice(unitPrice);
      il.setListPrice(listPrice);
      il.setLineNetAmount(qty.multiply(unitPrice).setScale(precision, RoundingMode.HALF_UP));
    }

    if (il.getTax() == null && retLine.getProduct() != null) {
      il.setTax(resolveApplicableTax(invoice, retLine));
    }
    if (il.getTax() == null && unitPrice.compareTo(BigDecimal.ZERO) != 0) {
      throw new OBException("Cannot determine tax rate for product '"
          + retLine.getProduct().getName() + "'.");
    }

    OBDal.getInstance().save(il);
  }

  static TaxRate resolveApplicableTax(Invoice invoice, ShipmentInOutLine retLine) {
    try {
      String productId = retLine.getProduct().getId();
      String orgId = invoice.getOrganization().getId();
      String warehouseId = retLine.getShipmentReceipt() != null
          && retLine.getShipmentReceipt().getWarehouse() != null
          ? retLine.getShipmentReceipt().getWarehouse().getId() : "";
      String bpLocId = invoice.getPartnerAddress() != null
          ? invoice.getPartnerAddress().getId() : "";
      Date invoiceDate = invoice.getInvoiceDate() != null ? invoice.getInvoiceDate() : new Date();
      String strDate = new SimpleDateFormat("dd-MM-yyyy").format(invoiceDate);
      boolean isSOTrx = Boolean.TRUE.equals(invoice.isSalesTransaction());
      String taxId = Tax.get(new DalConnectionProvider(), productId, strDate, orgId,
          warehouseId, bpLocId, bpLocId, "", isSOTrx);
      if (taxId != null && !taxId.isEmpty()) {
        return OBDal.getInstance().get(TaxRate.class, taxId);
      }
    } catch (Exception e) {
      log.warn("Tax.get() fallback failed for product {}: {}", retLine.getProduct().getId(),
          e.getMessage());
    }
    return null;
  }

  static BigDecimal resolvePriceFromSourceInvoiceLine(String shipmentLineId,
      String priceField, BigDecimal fallback) {
    if (shipmentLineId == null) return fallback;
    try {
      String hql =
          "SELECT il." + priceField + " FROM InvoiceLine il " +
          "WHERE il.goodsShipmentLine.id = :lineId " +
          "AND il.active = true " +
          "AND il.invoice.documentStatus = 'CO' " +
          "ORDER BY il.invoice.invoiceDate DESC";
      List<BigDecimal> rows = OBDal.getInstance().getSession()
          .createQuery(hql, BigDecimal.class)
          .setParameter("lineId", shipmentLineId)
          .setMaxResults(1)
          .list();
      if (!rows.isEmpty()) {
        BigDecimal price = rows.get(0);
        return (price != null && price.compareTo(BigDecimal.ZERO) > 0) ? price : fallback;
      }
      log.warn("No completed invoice line found for shipment line {} ({})", shipmentLineId, priceField);
    } catch (Exception e) {
      log.warn("Could not resolve {} from source invoice line {}: {}",
          priceField, shipmentLineId, e.getMessage());
    }
    return fallback;
  }

  static BigDecimal resolvePriceFromPriceList(String productId, String priceListId,
      String priceProperty, BigDecimal fallback) {
    if (productId == null || priceListId == null) return fallback;
    try {
      String hql =
          "SELECT pp." + priceProperty + " FROM PricingProductPrice pp " +
          "WHERE pp.product.id = :productId " +
          "AND pp.priceListVersion.priceList.id = :priceListId " +
          "AND pp.priceListVersion.active = true " +
          "AND pp.active = true " +
          "AND pp.priceListVersion.validFromDate <= :today " +
          "ORDER BY pp.priceListVersion.validFromDate DESC";
      List<BigDecimal> rows = OBDal.getInstance().getSession()
          .createQuery(hql, BigDecimal.class)
          .setParameter("productId", productId)
          .setParameter("priceListId", priceListId)
          .setParameter("today", new Date())
          .setMaxResults(1)
          .list();
      if (!rows.isEmpty()) {
        BigDecimal price = rows.get(0);
        return (price != null && price.compareTo(BigDecimal.ZERO) > 0) ? price : fallback;
      }
      log.warn("No price list entry for product {} in price list {} ({})",
          productId, priceListId, priceProperty);
    } catch (Exception e) {
      log.warn("Could not resolve {} for product {} from price list {}: {}",
          priceProperty, productId, priceListId, e.getMessage());
    }
    return fallback;
  }
}
