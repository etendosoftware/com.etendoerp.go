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

import javax.inject.Inject;
import javax.inject.Named;
import javax.servlet.http.HttpServletResponse;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.criterion.Restrictions;
import org.openbravo.base.exception.OBException;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.erpCommon.businessUtility.Tax;
import org.openbravo.model.common.businesspartner.BusinessPartner;
import org.openbravo.model.common.enterprise.DocumentType;
import org.openbravo.model.common.enterprise.Locator;
import org.openbravo.model.common.invoice.Invoice;
import org.openbravo.model.common.invoice.InvoiceLine;
import org.openbravo.model.financialmgmt.tax.TaxRate;
import org.openbravo.model.materialmgmt.transaction.ShipmentInOut;
import org.openbravo.model.materialmgmt.transaction.ShipmentInOutLine;
import org.openbravo.service.db.DalConnectionProvider;

/**
 * NeoHandler for the Return to Vendor Shipment header entity.
 *
 * <p>Handles custom actions: {@code availableReceipts}, {@code availableReceiptLines},
 * {@code importReceiptLines}, {@code createReturnInvoice}, and {@code cloneRecord}.
 *
 * <p>Injects {@code sourceReceiptDocNo}, {@code sourceReceipts}, {@code returnInvoices},
 * and {@code linesCount} into every GET response via {@code afterHandle}.
 */
@Named("returnToVendorShipmentHeaderHandler")
public class ReturnToVendorShipmentHeaderHandler implements NeoHandler {

  private static final Logger log = LogManager.getLogger(ReturnToVendorShipmentHeaderHandler.class);

  @Inject
  CreateDraftInvoiceHandler createDraftInvoiceHandler;

  @Inject
  NeoCloneRecordHandler cloneRecordHandler;

  private static final String FIELD_SOURCE_RECEIPT_DOC_NO = "sourceReceiptDocNo";
  private static final String FIELD_SOURCE_RECEIPTS = "sourceReceipts";
  private static final String FIELD_DOCUMENT_NO = "documentNo";
  private static final String FIELD_DOCUMENT_STATUS = "documentStatus";
  private static final String KEY_RESPONSE = "response";
  private static final String ACTION_IMPORT_LINES = "importReceiptLines";
  private static final String ACTION_AVAILABLE_RECEIPTS = "availableReceipts";
  private static final String ACTION_AVAILABLE_LINES = "availableReceiptLines";
  private static final String ACTION_CREATE_RETURN_INVOICE = "createReturnInvoice";
  private static final String ACTION_DOCUMENT_ACTION = "documentAction";

  @Override
  public NeoResponse handle(NeoContext context) {
    if (NeoEndpointType.CRUD.equals(context.getEndpointType())
        && "POST".equals(context.getHttpMethod())
        && context.getRecordId() == null) {
      NeoHandlerUtils.injectReturnDocType(context, "MMR", false);
    }

    if (!NeoEndpointType.ACTION.equals(context.getEndpointType())) {
      return null;
    }
    NeoResponse cloneResponse = cloneRecordHandler.handle(context);
    if (cloneResponse != null) return cloneResponse;

    String action = context.getFieldName();
    String method = context.getHttpMethod();
    if (ACTION_IMPORT_LINES.equals(action) && "POST".equals(method)) {
      return handleImportReceiptLines(context);
    }
    if (ACTION_AVAILABLE_RECEIPTS.equals(action) && "POST".equals(method)) {
      return handleAvailableReceipts(context);
    }
    if (ACTION_AVAILABLE_LINES.equals(action) && "POST".equals(method)) {
      return handleAvailableReceiptLines(context);
    }
    if (ACTION_CREATE_RETURN_INVOICE.equals(action) && "POST".equals(method)) {
      return handleCreateReturnInvoice(context);
    }
    if (ACTION_DOCUMENT_ACTION.equals(action) && "POST".equals(method)) {
      fillMissingStorageBins(context.getRecordId());
      return null; // let NEO native process handle completion
    }
    return null;
  }

  // ---------------------------------------------------------------------------
  // Action: importReceiptLines
  // ---------------------------------------------------------------------------

  private NeoResponse handleImportReceiptLines(NeoContext context) {
    String returnId = context.getRecordId();
    if (returnId == null || returnId.isBlank()) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, "Record ID is required");
    }
    try {
      OBContext.setAdminMode(true);
      try {
        ShipmentInOut returnDoc = OBDal.getInstance().get(ShipmentInOut.class, returnId);
        if (returnDoc == null) {
          return NeoResponse.error(HttpServletResponse.SC_NOT_FOUND,
              "Return to vendor shipment not found: " + returnId);
        }

        JSONObject body = context.getRequestBody();
        JSONArray requestedLines = body != null ? body.optJSONArray("lines") : null;
        if (requestedLines == null || requestedLines.length() == 0) {
          return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, "No lines specified");
        }

        long nextLineNo = fetchMaxLineNo(returnId) + 10;
        int imported = 0;

        for (int i = 0; i < requestedLines.length(); i++) {
          JSONObject req = requestedLines.getJSONObject(i);
          String sourceLineId = req.optString("sourceLineId", null);
          BigDecimal qty = BigDecimal.valueOf(req.optDouble("returnQuantity", 0));
          ShipmentInOutLine sourceLine = sourceLineId != null
              ? OBDal.getInstance().get(ShipmentInOutLine.class, sourceLineId) : null;
          if (sourceLineId == null || qty.compareTo(BigDecimal.ZERO) <= 0 || sourceLine == null) continue;

          ShipmentInOutLine retLine = OBProvider.getInstance().get(ShipmentInOutLine.class);
          retLine.setClient(returnDoc.getClient());
          retLine.setOrganization(returnDoc.getOrganization());
          retLine.setShipmentReceipt(returnDoc);
          retLine.setLineNo(nextLineNo);
          retLine.setProduct(sourceLine.getProduct());
          retLine.setUOM(sourceLine.getUOM());
          retLine.setMovementQuantity(qty.negate());
          retLine.setCanceledInoutLine(sourceLine);
          if (sourceLine.getStorageBin() != null) {
            retLine.setStorageBin(sourceLine.getStorageBin());
          }
          OBDal.getInstance().save(retLine);
          nextLineNo += 10;
          imported++;
        }

        OBDal.getInstance().flush();

        JSONObject data = new JSONObject();
        data.put("importedCount", imported);
        return wrapOkData(data);

      } finally {
        OBContext.restorePreviousMode();
      }
    } catch (Exception e) {
      log.error("Error importing receipt lines into return shipment {}: {}", returnId, e.getMessage(), e);
      return NeoResponse.error(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
          "An internal error occurred while importing lines");
    }
  }

  // ---------------------------------------------------------------------------
  // Action: availableReceipts
  // ---------------------------------------------------------------------------

  @SuppressWarnings("java:S2077")
  private NeoResponse handleAvailableReceipts(NeoContext context) {
    JSONObject body = context.getRequestBody();
    String bpId = body != null ? body.optString("businessPartner", null) : null;
    if (bpId == null || bpId.isBlank()) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, "businessPartner param is required");
    }
    try {
      // Select completed purchase receipts (V+, IsSOTrx='N') that still have
      // unreturned quantities available for this vendor.
      String sql =
          "SELECT DISTINCT h.M_InOut_ID, h.DocumentNo, h.MovementDate, " +
          "  bp.Name AS bp_name, h.C_BPartner_ID " +
          "FROM M_InOut h " +
          "JOIN C_BPartner bp ON bp.C_BPartner_ID = h.C_BPartner_ID " +
          "JOIN C_DocType dt ON dt.C_DocType_ID = h.C_DocType_ID " +
          "JOIN M_InOutLine l ON l.M_InOut_ID = h.M_InOut_ID " +
          "LEFT JOIN ( " +
          "  SELECT rl.Canceled_Inoutline_ID, SUM(ABS(rl.MovementQty)) AS ret_qty " +
          "  FROM M_InOutLine rl " +
          "  JOIN M_InOut rh ON rh.M_InOut_ID = rl.M_InOut_ID " +
          "  WHERE rl.Canceled_Inoutline_ID IS NOT NULL AND rh.DocStatus NOT IN ('VO')" +
          "  GROUP BY rl.Canceled_Inoutline_ID " +
          ") ret ON ret.Canceled_Inoutline_ID = l.M_InOutLine_ID " +
          "WHERE h.C_BPartner_ID = ? " +
          "AND h.DocStatus = 'CO' " +
          "AND dt.IsSOTrx = 'N' AND dt.IsReturn = 'N' " +
          "AND l.MovementQty > COALESCE(ret.ret_qty, 0) " +
          "ORDER BY h.MovementDate DESC";

      JSONArray data = new JSONArray();
      Connection conn = OBDal.getInstance().getConnection();
      try (PreparedStatement ps = conn.prepareStatement(sql)) {
        ps.setString(1, bpId);
        try (ResultSet rs = ps.executeQuery()) {
          while (rs.next()) {
            JSONObject row = new JSONObject();
            row.put("id", rs.getString(1));
            row.put(FIELD_DOCUMENT_NO, rs.getString(2));
            row.put("movementDate", rs.getString(3));
            row.put("businessPartner$_identifier", rs.getString(4));
            row.put("businessPartner", rs.getString(5));
            data.put(row);
          }
        }
      }
      return wrapOkData(data);
    } catch (Exception e) {
      log.error("Error fetching available receipts for BP {}: {}", bpId, e.getMessage(), e);
      return NeoResponse.error(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
          "An internal error occurred while fetching available receipts");
    }
  }

  // ---------------------------------------------------------------------------
  // Action: availableReceiptLines
  // ---------------------------------------------------------------------------

  @SuppressWarnings("java:S2077")
  private NeoResponse handleAvailableReceiptLines(NeoContext context) {
    JSONObject body = context.getRequestBody();
    String receiptId = body != null ? body.optString("receiptId", null) : null;
    String bpId = body != null ? body.optString("businessPartner", null) : null;
    if (receiptId == null || receiptId.isBlank()) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, "receiptId param is required");
    }
    if (bpId == null || bpId.isBlank()) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, "businessPartner param is required");
    }
    try {
      String sql =
          "SELECT l.M_InOutLine_ID, l.M_Product_ID, p.Name AS product_name, l.C_UOM_ID, " +
          "  l.MovementQty - COALESCE(ret.ret_qty, 0) AS available_qty " +
          "FROM M_InOutLine l " +
          "JOIN M_InOut h ON h.M_InOut_ID = l.M_InOut_ID " +
          "JOIN M_Product p ON p.M_Product_ID = l.M_Product_ID " +
          "LEFT JOIN ( " +
          "  SELECT rl.Canceled_Inoutline_ID, SUM(ABS(rl.MovementQty)) AS ret_qty " +
          "  FROM M_InOutLine rl " +
          "  JOIN M_InOut rh ON rh.M_InOut_ID = rl.M_InOut_ID " +
          "  WHERE rl.Canceled_Inoutline_ID IS NOT NULL AND rh.DocStatus NOT IN ('VO')" +
          "  GROUP BY rl.Canceled_Inoutline_ID " +
          ") ret ON ret.Canceled_Inoutline_ID = l.M_InOutLine_ID " +
          "WHERE l.M_InOut_ID = ? " +
          "AND h.C_BPartner_ID = ? " +
          "AND l.MovementQty > COALESCE(ret.ret_qty, 0) " +
          "ORDER BY l.Line";

      JSONArray data = new JSONArray();
      Connection conn = OBDal.getInstance().getConnection();
      try (PreparedStatement ps = conn.prepareStatement(sql)) {
        ps.setString(1, receiptId);
        ps.setString(2, bpId);
        try (ResultSet rs = ps.executeQuery()) {
          while (rs.next()) {
            JSONObject row = new JSONObject();
            row.put("id", rs.getString(1));
            row.put("product", rs.getString(2));
            row.put("product$_identifier", rs.getString(3));
            row.put("uOM", rs.getString(4));
            row.put("movementQuantity", rs.getBigDecimal(5));
            data.put(row);
          }
        }
      }
      return wrapOkData(data);
    } catch (Exception e) {
      log.error("Error fetching available lines for receipt {}: {}", receiptId, e.getMessage(), e);
      return NeoResponse.error(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
          "An internal error occurred while fetching available receipt lines");
    }
  }

  // ---------------------------------------------------------------------------
  // Action: createReturnInvoice  (AP Credit Memo — APC)
  // ---------------------------------------------------------------------------

  private NeoResponse handleCreateReturnInvoice(NeoContext context) {
    String returnId = context.getRecordId();
    if (returnId == null || returnId.isBlank()) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, "Record ID is required");
    }
    try {
      OBContext.setAdminMode(true);
      try {
        ShipmentInOut returnDoc = OBDal.getInstance().get(ShipmentInOut.class, returnId);
        if (returnDoc == null) {
          return NeoResponse.error(HttpServletResponse.SC_NOT_FOUND, "Return shipment not found");
        }
        if (!"CO".equals(returnDoc.getDocumentStatus())) {
          return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST,
              "Return shipment must be completed before creating a return invoice");
        }

        List<ShipmentInOutLine> lines = returnDoc.getMaterialMgmtShipmentInOutLineList()
            .stream().filter(l -> l.getProduct() != null).collect(Collectors.toList());
        if (lines.isEmpty()) {
          return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST,
              "No product lines in this return shipment");
        }

        DocumentType docType = findApcDocType(returnDoc.getOrganization().getId());
        if (docType == null) {
          return NeoResponse.error(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
              "No AP credit memo document type (APC) found for this organization");
        }

        Invoice sourceInvoice = findSourceInvoice(lines);
        Invoice invoice = buildReturnInvoiceHeader(returnDoc, docType, sourceInvoice);
        OBDal.getInstance().save(invoice);
        OBDal.getInstance().flush();

        addReturnInvoiceLines(invoice, lines);
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

      } finally {
        OBContext.restorePreviousMode();
      }
    } catch (OBException e) {
      log.warn("Return invoice creation rejected for shipment {}: {}", returnId, e.getMessage());
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, e.getMessage());
    } catch (Exception e) {
      log.error("Error creating return invoice for shipment {}: {}", returnId, e.getMessage(), e);
      return NeoResponse.error(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
          "An internal error occurred while creating the return invoice");
    }
  }

  private DocumentType findApcDocType(String orgId) {
    List<DocumentType> candidates = OBDal.getInstance().createCriteria(DocumentType.class)
        .add(Restrictions.eq(DocumentType.PROPERTY_DOCUMENTCATEGORY, "APC"))
        .add(Restrictions.eq(DocumentType.PROPERTY_SALESTRANSACTION, false))
        .add(Restrictions.eq(DocumentType.PROPERTY_ACTIVE, true))
        .addOrderBy(DocumentType.PROPERTY_DEFAULT, false)
        .list();
    for (DocumentType dt : candidates) {
      if (orgId.equals(dt.getOrganization().getId())) return dt;
    }
    for (DocumentType dt : candidates) {
      if ("0".equals(dt.getOrganization().getId())) return dt;
    }
    return candidates.isEmpty() ? null : candidates.get(0);
  }

  private Invoice buildReturnInvoiceHeader(ShipmentInOut returnDoc, DocumentType docType,
      Invoice sourceInvoice) {
    BusinessPartner bp = returnDoc.getBusinessPartner();

    Invoice invoice = OBProvider.getInstance().get(Invoice.class);
    invoice.setClient(returnDoc.getClient());
    invoice.setOrganization(returnDoc.getOrganization());
    invoice.setDocumentType(docType);
    invoice.setTransactionDocument(docType);
    invoice.setDocumentStatus("DR");
    invoice.setDocumentAction("CO");
    invoice.setSalesTransaction(false); // AP (purchase) credit memo
    invoice.setInvoiceDate(new Date());
    invoice.setAccountingDate(new Date());
    invoice.setBusinessPartner(bp);
    invoice.setPartnerAddress(returnDoc.getPartnerAddress());
    invoice.setSummedLineAmount(BigDecimal.ZERO);
    invoice.setGrandTotalAmount(BigDecimal.ZERO);
    invoice.setWithholdingamount(BigDecimal.ZERO);

    if (sourceInvoice != null) {
      invoice.setCurrency(sourceInvoice.getCurrency());
      invoice.setPriceList(sourceInvoice.getPriceList());
      invoice.setPaymentTerms(sourceInvoice.getPaymentTerms());
      invoice.setPaymentMethod(sourceInvoice.getPaymentMethod());
    } else {
      invoice.setPriceList(bp.getPurchasePricelist());
      if (bp.getPurchasePricelist() != null) {
        invoice.setCurrency(bp.getPurchasePricelist().getCurrency());
      }
      if (bp.getPaymentTerms() == null || bp.getPaymentMethod() == null) {
        throw new OBException("Business Partner is missing mandatory Payment Terms or Payment Method");
      }
      invoice.setPaymentTerms(bp.getPaymentTerms());
      invoice.setPaymentMethod(bp.getPaymentMethod());
    }

    return invoice;
  }

  private Invoice findSourceInvoice(List<ShipmentInOutLine> lines) {
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

  private void addReturnInvoiceLines(Invoice invoice, List<ShipmentInOutLine> lines) {
    int precision = invoice.getCurrency() != null
        ? invoice.getCurrency().getStandardPrecision().intValue() : 2;
    long lineNo = 10;
    for (ShipmentInOutLine retLine : lines) {
      BigDecimal qty = retLine.getMovementQuantity() != null
          ? retLine.getMovementQuantity().negate() : BigDecimal.ZERO;
      if (retLine.getProduct() == null || qty.compareTo(BigDecimal.ZERO) == 0) continue;
      buildAndSaveInvoiceLine(invoice, retLine, qty, precision, lineNo);
      lineNo += 10;
    }
  }

  private void buildAndSaveInvoiceLine(Invoice invoice, ShipmentInOutLine retLine,
      BigDecimal qty, int precision, long lineNo) {
    ShipmentInOutLine origLine = retLine.getCanceledInoutLine();
    ShipmentInOutLine pricingLine = (origLine != null) ? origLine : retLine;
    InvoiceLine il = createDraftInvoiceHandler.createShipmentInvoiceLine(invoice, pricingLine, qty, lineNo);
    il.setGoodsShipmentLine(retLine);

    BigDecimal unitPrice = il.getUnitPrice() != null ? il.getUnitPrice() : BigDecimal.ZERO;
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

  private TaxRate resolveApplicableTax(Invoice invoice, ShipmentInOutLine retLine) {
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

  private BigDecimal resolvePriceFromPriceList(String productId, String priceListId,
      String priceProperty, BigDecimal fallback) {
    if (productId == null || priceListId == null) return fallback;
    try {
      String hql =
          "SELECT pp." + priceProperty + " FROM ProductPrice pp " +
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

  // ---------------------------------------------------------------------------
  // Storage bin fill (pre-completion safety net)
  // ---------------------------------------------------------------------------

  private void fillMissingStorageBins(String returnId) {
    if (returnId == null) return;
    try {
      OBContext.setAdminMode(true);
      try {
        ShipmentInOut returnDoc = OBDal.getInstance().get(ShipmentInOut.class, returnId);
        if (returnDoc == null) return;
        Locator defaultLocator = null;
        for (ShipmentInOutLine line : returnDoc.getMaterialMgmtShipmentInOutLineList()) {
          ShipmentInOutLine origLine = line.getCanceledInoutLine();
          Locator target = (origLine != null && origLine.getStorageBin() != null)
              ? origLine.getStorageBin()
              : line.getStorageBin();
          if (target == null) {
            if (defaultLocator == null) {
              defaultLocator = findDefaultLocator(returnDoc.getWarehouse().getId());
            }
            target = defaultLocator;
          }
          if (target != null && !target.equals(line.getStorageBin())) {
            line.setStorageBin(target);
            OBDal.getInstance().save(line);
          }
        }
        OBDal.getInstance().flush();
      } finally {
        OBContext.restorePreviousMode();
      }
    } catch (Exception e) {
      log.warn("Could not fill missing storage bins for return shipment {}: {}",
          returnId, e.getMessage());
    }
  }

  @SuppressWarnings("java:S2077")
  private Locator findDefaultLocator(String warehouseId) {
    String sql = "SELECT m_locator_id FROM m_locator " +
        "WHERE m_warehouse_id = ? AND isdefault = 'Y' AND isactive = 'Y' LIMIT 1";
    Connection conn = OBDal.getInstance().getConnection();
    try (PreparedStatement ps = conn.prepareStatement(sql)) {
      ps.setString(1, warehouseId);
      try (ResultSet rs = ps.executeQuery()) {
        if (rs.next()) {
          return OBDal.getInstance().get(Locator.class, rs.getString(1));
        }
      }
    } catch (Exception e) {
      log.warn("Could not find default locator for warehouse {}: {}", warehouseId, e.getMessage());
    }
    return null;
  }

  // ---------------------------------------------------------------------------
  // afterHandle: inject sourceReceipts, sourceReceiptDocNo, returnInvoices,
  //              linesCount into every GET response
  // ---------------------------------------------------------------------------

  @Override
  public NeoResponse afterHandle(NeoContext context) {
    try {
      JSONArray dataArr = NeoHandlerUtils.extractGetDataArray(context);
      if (dataArr == null) {
        return null;
      }
      JSONObject body = context.getPreviousResult().getBody();
      List<String> ids = NeoHandlerUtils.collectIds(dataArr);

      Map<String, List<JSONObject>> receiptsMap = fetchSourceReceipts(ids);
      Map<String, List<JSONObject>> returnInvoicesMap = fetchReturnInvoices(ids);
      Map<String, Integer> lineCountMap = fetchLineCounts(ids);

      for (int i = 0; i < dataArr.length(); i++) {
        JSONObject rec = dataArr.getJSONObject(i);
        String id = rec.optString("id", null);

        List<JSONObject> receipts = receiptsMap.getOrDefault(id, Collections.emptyList());
        JSONArray receiptsArr = new JSONArray();
        for (JSONObject r : receipts) {
          receiptsArr.put(r);
        }
        rec.put(FIELD_SOURCE_RECEIPTS, receiptsArr);

        if (!receipts.isEmpty()) {
          String combined = receipts.stream()
              .map(r -> r.optString(FIELD_DOCUMENT_NO, ""))
              .filter(s -> !s.isEmpty())
              .collect(Collectors.joining(", "));
          if (!combined.isEmpty()) {
            rec.put(FIELD_SOURCE_RECEIPT_DOC_NO, combined);
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
      }
      return NeoResponse.ok(body);
    } catch (Exception e) {
      log.error("Error enriching return-to-vendor-shipment header", e);
      return null;
    }
  }

  @SuppressWarnings("java:S2077")
  private Map<String, List<JSONObject>> fetchSourceReceipts(List<String> returnIds) {
    Map<String, List<JSONObject>> result = new HashMap<>();
    if (returnIds.isEmpty()) return result;
    String placeholders = returnIds.stream().map(id -> "?").collect(Collectors.joining(","));
    String sql =
        "SELECT DISTINCT l.M_InOut_ID, src.M_InOut_ID, src.DocumentNo, src.DocStatus " +
        "FROM M_InOutLine l " +
        "JOIN M_InOutLine orig ON orig.M_InOutLine_ID = l.Canceled_Inoutline_ID " +
        "JOIN M_InOut src ON src.M_InOut_ID = orig.M_InOut_ID " +
        "WHERE l.M_InOut_ID IN (" + placeholders + ") " +
        "  AND l.Canceled_Inoutline_ID IS NOT NULL";
    Connection conn = OBDal.getInstance().getConnection();
    try (PreparedStatement ps = conn.prepareStatement(sql)) {
      for (int i = 0; i < returnIds.size(); i++) ps.setString(i + 1, returnIds.get(i));
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          addReceiptToMap(result, rs);
        }
      }
    } catch (Exception e) {
      log.warn("Error fetching source receipts: {}", e.getMessage());
    }
    return result;
  }

  private void addReceiptToMap(Map<String, List<JSONObject>> result, ResultSet rs) {
    try {
      String returnId = rs.getString(1);
      JSONObject receipt = new JSONObject();
      receipt.put("id", rs.getString(2));
      receipt.put(FIELD_DOCUMENT_NO, rs.getString(3));
      receipt.put(FIELD_DOCUMENT_STATUS, rs.getString(4));
      result.computeIfAbsent(returnId, k -> new ArrayList<>()).add(receipt);
    } catch (Exception je) {
      log.warn("Error building sourceReceipt JSON: {}", je.getMessage());
    }
  }

  @SuppressWarnings("java:S2077")
  private Map<String, List<JSONObject>> fetchReturnInvoices(List<String> returnIds) {
    Map<String, List<JSONObject>> result = new HashMap<>();
    if (returnIds.isEmpty()) return result;
    String placeholders = returnIds.stream().map(id -> "?").collect(Collectors.joining(","));
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
      for (int i = 0; i < returnIds.size(); i++) ps.setString(i + 1, returnIds.get(i));
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          addInvoiceToMap(result, rs);
        }
      }
    } catch (Exception e) {
      log.warn("Error fetching return invoices for return shipments: {}", e.getMessage());
    }
    return result;
  }

  private void addInvoiceToMap(Map<String, List<JSONObject>> result, ResultSet rs) {
    try {
      String returnId = rs.getString(1);
      JSONObject inv = new JSONObject();
      inv.put("id", rs.getString(2));
      inv.put(FIELD_DOCUMENT_NO, rs.getString(3));
      inv.put(FIELD_DOCUMENT_STATUS, rs.getString(4));
      BigDecimal total = rs.getBigDecimal(5);
      inv.put("grandTotalAmount", total != null ? total : JSONObject.NULL);
      String iso = rs.getString(6);
      inv.put("currency$_identifier", iso != null ? iso : JSONObject.NULL);
      result.computeIfAbsent(returnId, k -> new ArrayList<>()).add(inv);
    } catch (Exception je) {
      log.warn("Error building returnInvoice JSON: {}", je.getMessage());
    }
  }

  @SuppressWarnings("java:S2077")
  private Map<String, Integer> fetchLineCounts(List<String> ids) {
    Map<String, Integer> result = new HashMap<>();
    if (ids.isEmpty()) return result;
    String placeholders = ids.stream().map(id -> "?").collect(Collectors.joining(","));
    String sql = "SELECT M_InOut_ID, COUNT(M_InOutLine_ID) FROM M_InOutLine " +
        "WHERE M_InOut_ID IN (" + placeholders + ") GROUP BY M_InOut_ID";
    Connection conn = OBDal.getInstance().getConnection();
    try (PreparedStatement ps = conn.prepareStatement(sql)) {
      for (int i = 0; i < ids.size(); i++) ps.setString(i + 1, ids.get(i));
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) result.put(rs.getString(1), rs.getInt(2));
      }
    } catch (Exception e) {
      log.warn("Error fetching line counts: {}", e.getMessage());
    }
    return result;
  }

  @SuppressWarnings("java:S2077")
  private long fetchMaxLineNo(String returnId) {
    String sql = "SELECT COALESCE(MAX(Line), 0) FROM M_InOutLine WHERE M_InOut_ID = ?";
    Connection conn = OBDal.getInstance().getConnection();
    try (PreparedStatement ps = conn.prepareStatement(sql)) {
      ps.setString(1, returnId);
      try (ResultSet rs = ps.executeQuery()) {
        if (rs.next()) return rs.getLong(1);
      }
    } catch (Exception e) {
      log.warn("Could not fetch max lineNo for return shipment {}: {}", returnId, e.getMessage());
    }
    return 0;
  }

  private static NeoResponse wrapOkData(Object data) throws Exception {
    JSONObject responseData = new JSONObject();
    responseData.put("data", data);
    JSONObject wrapper = new JSONObject();
    wrapper.put(KEY_RESPONSE, responseData);
    return NeoResponse.ok(wrapper);
  }
}
