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
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.criterion.Restrictions;
import org.openbravo.base.exception.OBException;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.erpCommon.businessUtility.Tax;
import org.openbravo.model.common.businesspartner.BusinessPartner;
import org.openbravo.model.common.enterprise.DocumentType;
import org.openbravo.model.common.enterprise.Locator;
import org.openbravo.model.common.enterprise.Warehouse;
import org.openbravo.model.common.invoice.Invoice;
import org.openbravo.model.common.invoice.InvoiceLine;
import org.openbravo.model.financialmgmt.tax.TaxRate;
import org.openbravo.model.materialmgmt.transaction.ShipmentInOut;
import org.openbravo.model.materialmgmt.transaction.ShipmentInOutLine;
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
   * belonging to {@code doc}'s OWN warehouse. Called from
   * {@code ReturnMaterialReceiptHeaderHandler.fillMissingStorageBins} and its
   * {@code ReturnToVendorShipmentHeaderHandler} twin, after the lines exist.
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
      Locator candidate = current;
      if (candidate == null) {
        ShipmentInOutLine origLine = line.getCanceledInoutLine();
        candidate = (origLine != null) ? origLine.getStorageBin() : null;
      }

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

      boolean changed = (target == null)
          ? current != null
          : (current == null || !target.getId().equals(current.getId()));
      if (changed) {
        line.setStorageBin(target);
        OBDal.getInstance().save(line);
      }
    }
    OBDal.getInstance().flush();
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
    return invoice;
  }

  private static void applyBusinessPartnerFinancials(Invoice invoice, BusinessPartner bp, boolean isSales) {
    if (isSales) {
      invoice.setPriceList(bp.getPriceList());
      if (bp.getPriceList() != null) {
        invoice.setCurrency(bp.getPriceList().getCurrency());
      }
      if (bp.getPaymentTerms() == null || bp.getPaymentMethod() == null) {
        throw new OBException("Business Partner is missing mandatory Payment Terms or Payment Method");
      }
      invoice.setPaymentTerms(bp.getPaymentTerms());
      invoice.setPaymentMethod(bp.getPaymentMethod());
    } else {
      invoice.setPriceList(bp.getPurchasePricelist());
      if (bp.getPurchasePricelist() != null) {
        invoice.setCurrency(bp.getPurchasePricelist().getCurrency());
      }
      if (bp.getPOPaymentTerms() == null || bp.getPOPaymentMethod() == null) {
        throw new OBException("Business Partner is missing mandatory PO Payment Terms or PO Payment Method");
      }
      invoice.setPaymentTerms(bp.getPOPaymentTerms());
      invoice.setPaymentMethod(bp.getPOPaymentMethod());
    }
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

  static NeoResponse finalizeReturnInvoice(Invoice invoice, List<ShipmentInOutLine> lines,
      CreateDraftInvoiceHandler createDraftInvoiceHandler) throws Exception {
    addReturnInvoiceLines(invoice, lines, createDraftInvoiceHandler);
    OBDal.getInstance().flush();
    OBDal.getInstance().getSession().refresh(invoice);
    createDraftInvoiceHandler.ensureDocumentNo(invoice);
    createDraftInvoiceHandler.getSupport().ensureLineGrossAmounts(invoice);
    createDraftInvoiceHandler.recalculateTotals(invoice);
    OBDal.getInstance().flush();
    JSONObject data = new JSONObject();
    data.put("id", invoice.getId());
    data.put(FIELD_DOCUMENT_NO, invoice.getDocumentNo());
    return wrapOkData(data);
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
