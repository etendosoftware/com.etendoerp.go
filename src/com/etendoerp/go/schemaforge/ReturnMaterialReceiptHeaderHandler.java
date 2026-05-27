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
import org.openbravo.model.common.businesspartner.BusinessPartner;
import org.openbravo.model.common.enterprise.DocumentType;
import org.openbravo.model.common.enterprise.Locator;
import org.openbravo.model.common.invoice.Invoice;
import org.openbravo.model.common.invoice.InvoiceLine;
import org.openbravo.model.financialmgmt.tax.TaxRate;
import org.openbravo.model.materialmgmt.transaction.ShipmentInOut;
import org.openbravo.model.materialmgmt.transaction.ShipmentInOutLine;

/**
 * Post-hook for the Return Material Receipt header entity.
 *
 * Injects {@code sourceShipmentDocNo} and {@code sourceShipments} into every
 * GET response, and handles the {@code importShipmentLines} action.
 */
@Named("returnMaterialReceiptHeaderHandler")
public class ReturnMaterialReceiptHeaderHandler implements NeoHandler {

  private static final Logger log = LogManager.getLogger(ReturnMaterialReceiptHeaderHandler.class);

  @Inject
  CreateDraftInvoiceHandler createDraftInvoiceHandler;

  @Inject
  NeoCloneRecordHandler cloneRecordHandler;

  private static final String FIELD_SOURCE_SHIPMENT_DOC_NO = "sourceShipmentDocNo";
  private static final String FIELD_SOURCE_SHIPMENTS = "sourceShipments";
  private static final String FIELD_DOCUMENT_NO = "documentNo";
  private static final String KEY_RESPONSE = "response";
  private static final String ACTION_IMPORT_LINES = "importShipmentLines";
  private static final String ACTION_AVAILABLE_SHIPMENTS = "availableShipments";
  private static final String ACTION_AVAILABLE_LINES = "availableShipmentLines";
  private static final String ACTION_CREATE_RETURN_INVOICE = "createReturnInvoice";
  private static final String ACTION_DOCUMENT_ACTION = "documentAction";

  @Override
  public NeoResponse handle(NeoContext context) {
    if (!NeoEndpointType.ACTION.equals(context.getEndpointType())) {
      return null;
    }
    NeoResponse cloneResponse = cloneRecordHandler.handle(context);
    if (cloneResponse != null) return cloneResponse;

    String action = context.getFieldName();
    String method = context.getHttpMethod();
    if (ACTION_IMPORT_LINES.equals(action) && "POST".equals(method)) {
      return handleImportShipmentLines(context);
    }
    if (ACTION_AVAILABLE_SHIPMENTS.equals(action) && "POST".equals(method)) {
      return handleAvailableShipments(context);
    }
    if (ACTION_AVAILABLE_LINES.equals(action) && "POST".equals(method)) {
      return handleAvailableShipmentLines(context);
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

  private void fillMissingStorageBins(String receiptId) {
    if (receiptId == null) return;
    try {
      OBContext.setAdminMode(true);
      try {
        ShipmentInOut receipt = OBDal.getInstance().get(ShipmentInOut.class, receiptId);
        if (receipt == null) return;
        Locator defaultLocator = null;
        for (ShipmentInOutLine line : receipt.getMaterialMgmtShipmentInOutLineList()) {
          ShipmentInOutLine origLine = line.getCanceledInoutLine();
          Locator target = (origLine != null && origLine.getStorageBin() != null)
              ? origLine.getStorageBin()
              : line.getStorageBin();
          if (target == null) {
            if (defaultLocator == null) {
              defaultLocator = findDefaultLocator(receipt.getWarehouse().getId());
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
      log.warn("Could not fill missing storage bins for receipt {}: {}", receiptId, e.getMessage());
    }
  }

  @SuppressWarnings("java:S2077")
  private Locator findDefaultLocator(String warehouseId) {
    String sql = "SELECT m_locator_id FROM m_locator WHERE m_warehouse_id = ? AND isdefault = 'Y' AND isactive = 'Y' LIMIT 1";
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

  private NeoResponse handleImportShipmentLines(NeoContext context) {
    String receiptId = context.getRecordId();
    if (receiptId == null || receiptId.isBlank()) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, "Record ID is required");
    }
    try {
      OBContext.setAdminMode(true);
      try {
        ShipmentInOut receipt = OBDal.getInstance().get(ShipmentInOut.class, receiptId);
        if (receipt == null) {
          return NeoResponse.error(HttpServletResponse.SC_NOT_FOUND, "Return receipt not found: " + receiptId);
        }

        JSONObject body = context.getRequestBody();
        JSONArray requestedLines = body != null ? body.optJSONArray("lines") : null;
        if (requestedLines == null || requestedLines.length() == 0) {
          return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, "No lines specified");
        }

        long nextLineNo = fetchMaxLineNo(receiptId) + 10;
        int imported = 0;

        for (int i = 0; i < requestedLines.length(); i++) {
          JSONObject req = requestedLines.getJSONObject(i);
          String sourceLineId = req.optString("sourceLineId", null);
          BigDecimal qty = BigDecimal.valueOf(req.optDouble("returnQuantity", 0));
          ShipmentInOutLine sourceLine = sourceLineId != null
              ? OBDal.getInstance().get(ShipmentInOutLine.class, sourceLineId) : null;
          if (sourceLineId == null || qty.compareTo(BigDecimal.ZERO) <= 0 || sourceLine == null) continue;

          ShipmentInOutLine retLine = OBProvider.getInstance().get(ShipmentInOutLine.class);
          retLine.setClient(receipt.getClient());
          retLine.setOrganization(receipt.getOrganization());
          retLine.setShipmentReceipt(receipt);
          retLine.setLineNo(nextLineNo);
          retLine.setProduct(sourceLine.getProduct());
          retLine.setUOM(sourceLine.getUOM());
          retLine.setMovementQuantity(qty);
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
      log.error("Error importing shipment lines into return receipt {}: {}", receiptId, e.getMessage(), e);
      return NeoResponse.error(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
          "An internal error occurred while importing lines");
    }
  }

  @SuppressWarnings("java:S2077")
  private NeoResponse handleAvailableShipments(NeoContext context) {
    JSONObject body = context.getRequestBody();
    String bpId = body != null ? body.optString("businessPartner", null) : null;
    if (bpId == null || bpId.isBlank()) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, "businessPartner param is required");
    }
    try {
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
          "  WHERE rl.Canceled_Inoutline_ID IS NOT NULL AND rh.DocStatus = 'CO' " +
          "  GROUP BY rl.Canceled_Inoutline_ID " +
          ") ret ON ret.Canceled_Inoutline_ID = l.M_InOutLine_ID " +
          "WHERE h.C_BPartner_ID = ? " +
          "AND h.DocStatus = 'CO' " +
          "AND dt.IsSOTrx = 'Y' AND dt.IsReturn = 'N' " +
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
      log.error("Error fetching available shipments for BP {}: {}", bpId, e.getMessage(), e);
      return NeoResponse.error(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
          "An internal error occurred while fetching available shipments");
    }
  }

  @SuppressWarnings("java:S2077")
  private NeoResponse handleAvailableShipmentLines(NeoContext context) {
    JSONObject body = context.getRequestBody();
    String shipmentId = body != null ? body.optString("shipmentId", null) : null;
    if (shipmentId == null || shipmentId.isBlank()) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, "shipmentId param is required");
    }
    try {
      String sql =
          "SELECT l.M_InOutLine_ID, l.M_Product_ID, p.Name AS product_name, l.C_UOM_ID, " +
          "  l.MovementQty - COALESCE(ret.ret_qty, 0) AS available_qty " +
          "FROM M_InOutLine l " +
          "JOIN M_Product p ON p.M_Product_ID = l.M_Product_ID " +
          "LEFT JOIN ( " +
          "  SELECT rl.Canceled_Inoutline_ID, SUM(ABS(rl.MovementQty)) AS ret_qty " +
          "  FROM M_InOutLine rl " +
          "  JOIN M_InOut rh ON rh.M_InOut_ID = rl.M_InOut_ID " +
          "  WHERE rl.Canceled_Inoutline_ID IS NOT NULL AND rh.DocStatus = 'CO' " +
          "  GROUP BY rl.Canceled_Inoutline_ID " +
          ") ret ON ret.Canceled_Inoutline_ID = l.M_InOutLine_ID " +
          "WHERE l.M_InOut_ID = ? " +
          "AND l.MovementQty > COALESCE(ret.ret_qty, 0) " +
          "ORDER BY l.Line";

      JSONArray data = new JSONArray();
      Connection conn = OBDal.getInstance().getConnection();
      try (PreparedStatement ps = conn.prepareStatement(sql)) {
        ps.setString(1, shipmentId);
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
      log.error("Error fetching available lines for shipment {}: {}", shipmentId, e.getMessage(), e);
      return NeoResponse.error(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
          "An internal error occurred while fetching available lines");
    }
  }

  private NeoResponse handleCreateReturnInvoice(NeoContext context) {
    String receiptId = context.getRecordId();
    if (receiptId == null || receiptId.isBlank()) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, "Record ID is required");
    }
    try {
      OBContext.setAdminMode(true);
      try {
        ShipmentInOut receipt = OBDal.getInstance().get(ShipmentInOut.class, receiptId);
        if (receipt == null) {
          return NeoResponse.error(HttpServletResponse.SC_NOT_FOUND, "Receipt not found");
        }
        if (!"CO".equals(receipt.getDocumentStatus())) {
          return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST,
              "Receipt must be completed before creating a return invoice");
        }

        List<ShipmentInOutLine> lines = receipt.getMaterialMgmtShipmentInOutLineList()
            .stream().filter(l -> l.getProduct() != null).collect(Collectors.toList());
        if (lines.isEmpty()) {
          return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, "No product lines in this receipt");
        }

        DocumentType docType = findAriRmDocType(receipt.getOrganization().getId());
        if (docType == null) {
          return NeoResponse.error(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
              "No return invoice document type (ARI_RM) found for this organization");
        }

        Invoice invoice = buildReturnInvoiceHeader(receipt, docType, lines);
        OBDal.getInstance().save(invoice);
        OBDal.getInstance().flush();

        addReturnInvoiceLines(invoice, lines);
        OBDal.getInstance().flush();
        OBDal.getInstance().getSession().refresh(invoice);

        createDraftInvoiceHandler.ensureDocumentNo(invoice);
        createDraftInvoiceHandler.ensureLineGrossAmounts(invoice);
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
      log.warn("Return invoice creation rejected for receipt {}: {}", receiptId, e.getMessage());
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, e.getMessage());
    } catch (Exception e) {
      log.error("Error creating return invoice for receipt {}: {}", receiptId, e.getMessage(), e);
      return NeoResponse.error(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
          "An internal error occurred while creating the return invoice");
    }
  }

  private DocumentType findAriRmDocType(String orgId) {
    List<DocumentType> candidates = OBDal.getInstance().createCriteria(DocumentType.class)
        .add(Restrictions.eq(DocumentType.PROPERTY_DOCUMENTCATEGORY, "ARI_RM"))
        .add(Restrictions.eq(DocumentType.PROPERTY_SALESTRANSACTION, true))
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

  private Invoice buildReturnInvoiceHeader(ShipmentInOut receipt, DocumentType docType,
      List<ShipmentInOutLine> lines) {
    Invoice sourceInvoice = findSourceInvoice(lines);
    BusinessPartner bp = receipt.getBusinessPartner();

    Invoice invoice = OBProvider.getInstance().get(Invoice.class);
    invoice.setClient(receipt.getClient());
    invoice.setOrganization(receipt.getOrganization());
    invoice.setDocumentType(docType);
    invoice.setTransactionDocument(docType);
    invoice.setDocumentStatus("DR");
    invoice.setDocumentAction("CO");
    invoice.setSalesTransaction(true);
    invoice.setInvoiceDate(new Date());
    invoice.setAccountingDate(new Date());
    invoice.setBusinessPartner(bp);
    invoice.setPartnerAddress(receipt.getPartnerAddress());
    invoice.setSummedLineAmount(BigDecimal.ZERO);
    invoice.setGrandTotalAmount(BigDecimal.ZERO);
    invoice.setWithholdingamount(BigDecimal.ZERO);

    if (sourceInvoice != null) {
      invoice.setCurrency(sourceInvoice.getCurrency());
      invoice.setPriceList(sourceInvoice.getPriceList());
      invoice.setPaymentTerms(sourceInvoice.getPaymentTerms());
      invoice.setPaymentMethod(sourceInvoice.getPaymentMethod());
    } else {
      invoice.setPriceList(bp.getPriceList());
      if (bp.getPriceList() != null) {
        invoice.setCurrency(bp.getPriceList().getCurrency());
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
    BigDecimal unitPrice = BigDecimal.ZERO;
    BigDecimal listPrice = BigDecimal.ZERO;
    TaxRate tax = null;
    ShipmentInOutLine origLine = retLine.getCanceledInoutLine();
    if (origLine != null) {
      Object[] prices = findPricesAndTaxForShipmentLine(origLine.getId());
      if (prices != null) {
        unitPrice = prices[0] != null ? (BigDecimal) prices[0] : BigDecimal.ZERO;
        listPrice = prices[1] != null ? (BigDecimal) prices[1] : BigDecimal.ZERO;
        tax = (TaxRate) prices[2];
      }
    }
    InvoiceLine il = OBProvider.getInstance().get(InvoiceLine.class);
    il.setOrganization(retLine.getOrganization());
    il.setInvoice(invoice);
    il.setLineNo(lineNo);
    il.setProduct(retLine.getProduct());
    il.setInvoicedQuantity(qty);
    il.setUOM(retLine.getUOM());
    il.setGoodsShipmentLine(retLine);
    il.setUnitPrice(unitPrice);
    il.setListPrice(listPrice);
    il.setLineNetAmount(qty.multiply(unitPrice).setScale(precision, RoundingMode.HALF_UP));
    if (tax != null) {
      il.setTax(tax);
    }
    OBDal.getInstance().save(il);
  }

  private Object[] findPricesAndTaxForShipmentLine(String origShipmentLineId) {
    String hql = "SELECT il.unitPrice, il.listPrice, il.tax FROM InvoiceLine il " +
        "WHERE il.goodsShipmentLine.id = :lineId AND il.invoice.documentStatus != 'VO' " +
        "ORDER BY il.invoice.invoiceDate DESC";
    List<Object[]> rows = OBDal.getInstance().getSession()
        .createQuery(hql, Object[].class)
        .setParameter("lineId", origShipmentLineId)
        .setMaxResults(1)
        .list();
    return rows.isEmpty() ? null : rows.get(0);
  }

  @Override
  public NeoResponse afterHandle(NeoContext context) {
    try {
      JSONArray dataArr = NeoHandlerUtils.extractGetDataArray(context);
      if (dataArr == null) {
        return null;
      }
      JSONObject body = context.getPreviousResult().getBody();
      List<String> ids = NeoHandlerUtils.collectIds(dataArr);

      Map<String, List<JSONObject>> shipmentsMap = fetchSourceShipments(ids);
      Map<String, List<JSONObject>> returnInvoicesMap = fetchReturnInvoices(ids);

      for (int i = 0; i < dataArr.length(); i++) {
        JSONObject rec = dataArr.getJSONObject(i);
        String id = rec.optString("id", null);

        List<JSONObject> shipments = shipmentsMap.getOrDefault(id, Collections.emptyList());
        JSONArray shipmentsArr = new JSONArray();
        for (JSONObject s : shipments) {
          shipmentsArr.put(s);
        }
        rec.put(FIELD_SOURCE_SHIPMENTS, shipmentsArr);

        if (!shipments.isEmpty()) {
          String combined = shipments.stream()
              .map(s -> s.optString(FIELD_DOCUMENT_NO, ""))
              .filter(s -> !s.isEmpty())
              .collect(Collectors.joining(", "));
          if (!combined.isEmpty()) {
            rec.put(FIELD_SOURCE_SHIPMENT_DOC_NO, combined);
          }
        }

        List<JSONObject> invoices = returnInvoicesMap.getOrDefault(id, Collections.emptyList());
        JSONArray invoicesArr = new JSONArray();
        for (JSONObject inv : invoices) {
          invoicesArr.put(inv);
        }
        rec.put("returnInvoices", invoicesArr);
        rec.put("hasReturnInvoice", !invoices.isEmpty());
      }
      return NeoResponse.ok(body);
    } catch (Exception e) {
      log.error("Error enriching return-material-receipt header", e);
      return null;
    }
  }

  @SuppressWarnings("java:S2077")
  private Map<String, List<JSONObject>> fetchSourceShipments(List<String> receiptIds) {
    Map<String, List<JSONObject>> result = new HashMap<>();
    if (receiptIds.isEmpty()) return result;
    String placeholders = receiptIds.stream().map(id -> "?").collect(Collectors.joining(","));
    String sql =
        "SELECT DISTINCT l.M_InOut_ID, src.M_InOut_ID, src.DocumentNo " +
        "FROM M_InOutLine l " +
        "JOIN M_InOutLine orig ON orig.M_InOutLine_ID = l.Canceled_Inoutline_ID " +
        "JOIN M_InOut src ON src.M_InOut_ID = orig.M_InOut_ID " +
        "WHERE l.M_InOut_ID IN (" + placeholders + ") " +
        "  AND l.Canceled_Inoutline_ID IS NOT NULL";
    Connection conn = OBDal.getInstance().getConnection();
    try (PreparedStatement ps = conn.prepareStatement(sql)) {
      for (int i = 0; i < receiptIds.size(); i++) ps.setString(i + 1, receiptIds.get(i));
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          addShipmentToMap(result, rs);
        }
      }
    } catch (Exception e) {
      log.warn("Error fetching source shipments: {}", e.getMessage());
    }
    return result;
  }

  private void addShipmentToMap(Map<String, List<JSONObject>> result, ResultSet rs) {
    try {
      String receiptId = rs.getString(1);
      JSONObject ship = new JSONObject();
      ship.put("id", rs.getString(2));
      ship.put(FIELD_DOCUMENT_NO, rs.getString(3));
      result.computeIfAbsent(receiptId, k -> new ArrayList<>()).add(ship);
    } catch (Exception je) {
      log.warn("Error building sourceShipment JSON: {}", je.getMessage());
    }
  }

  @SuppressWarnings("java:S2077")
  private Map<String, List<JSONObject>> fetchReturnInvoices(List<String> receiptIds) {
    Map<String, List<JSONObject>> result = new HashMap<>();
    if (receiptIds.isEmpty()) return result;
    String placeholders = receiptIds.stream().map(id -> "?").collect(Collectors.joining(","));
    String sql =
        "SELECT DISTINCT l.M_InOut_ID, i.C_Invoice_ID, i.DocumentNo " +
        "FROM M_InOutLine l " +
        "JOIN C_InvoiceLine il ON il.M_InOutLine_ID = l.M_InOutLine_ID " +
        "JOIN C_Invoice i ON i.C_Invoice_ID = il.C_Invoice_ID " +
        "WHERE l.M_InOut_ID IN (" + placeholders + ") " +
        "  AND i.DocStatus != 'VO'";
    Connection conn = OBDal.getInstance().getConnection();
    try (PreparedStatement ps = conn.prepareStatement(sql)) {
      for (int i = 0; i < receiptIds.size(); i++) ps.setString(i + 1, receiptIds.get(i));
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          addInvoiceToMap(result, rs);
        }
      }
    } catch (Exception e) {
      log.warn("Error fetching return invoices for receipts: {}", e.getMessage());
    }
    return result;
  }

  private void addInvoiceToMap(Map<String, List<JSONObject>> result, ResultSet rs) {
    try {
      String receiptId = rs.getString(1);
      JSONObject inv = new JSONObject();
      inv.put("id", rs.getString(2));
      inv.put(FIELD_DOCUMENT_NO, rs.getString(3));
      result.computeIfAbsent(receiptId, k -> new ArrayList<>()).add(inv);
    } catch (Exception je) {
      log.warn("Error building returnInvoice JSON: {}", je.getMessage());
    }
  }

  @SuppressWarnings("java:S2077")
  private long fetchMaxLineNo(String receiptId) {
    String sql = "SELECT COALESCE(MAX(Line), 0) FROM M_InOutLine WHERE M_InOut_ID = ?";
    Connection conn = OBDal.getInstance().getConnection();
    try (PreparedStatement ps = conn.prepareStatement(sql)) {
      ps.setString(1, receiptId);
      try (ResultSet rs = ps.executeQuery()) {
        if (rs.next()) return rs.getLong(1);
      }
    } catch (Exception e) {
      log.warn("Could not fetch max lineNo for receipt {}: {}", receiptId, e.getMessage());
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
