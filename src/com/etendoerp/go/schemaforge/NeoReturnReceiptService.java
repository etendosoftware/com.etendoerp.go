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
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.criterion.Restrictions;
import org.openbravo.base.exception.OBException;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.model.common.plm.ProductUOM;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.erpCommon.utility.Utility;
import org.openbravo.model.common.enterprise.DocumentType;
import org.openbravo.model.common.enterprise.Locator;
import org.openbravo.model.materialmgmt.transaction.ShipmentInOut;
import org.openbravo.model.materialmgmt.transaction.ShipmentInOutLine;
import org.openbravo.service.db.DalConnectionProvider;

/**
 * Shared logic for creating return shipments/receipts from an existing M_InOut document.
 *
 * Used by:
 * - {@link CreateReturnReceiptHandler}: sales return (C-, salesTransaction=true)
 * - CreatePurchaseReturnHandler (future): purchase return (V-, salesTransaction=false)
 */
final class NeoReturnReceiptService {

  private static final Logger log = LogManager.getLogger(NeoReturnReceiptService.class);
  private static final String ACTION_NAME = "createReturn";

  private NeoReturnReceiptService() {
  }

  /**
   * Entry point called by NeoHandler implementations.
   *
   * @param context          the NEO request context
   * @param expectedSpecName the spec that owns this action (e.g. "goods-shipment")
   * @param salesTransaction true for sales returns (C-), false for purchase returns (V-)
   * @param movementType     "C-" (customer return) or "V-" (vendor return)
   */
  static NeoResponse createReturn(NeoContext context, String expectedSpecName,
      boolean salesTransaction, String movementType) {
    if (!NeoEndpointType.ACTION.equals(context.getEndpointType())) {
      return null;
    }
    if (!ACTION_NAME.equals(context.getFieldName()) || !"POST".equals(context.getHttpMethod())) {
      return null;
    }
    if (!expectedSpecName.equals(context.getSpecName())) {
      return null;
    }

    String shipmentId = context.getRecordId();
    if (StringUtils.isBlank(shipmentId)) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, "Record ID is required");
    }

    try {
      OBContext.setAdminMode(true);
      try {
        ShipmentInOut source = OBDal.getInstance().get(ShipmentInOut.class, shipmentId);
        if (source == null) {
          return NeoResponse.error(HttpServletResponse.SC_NOT_FOUND,
              "Shipment not found: " + shipmentId);
        }

        JSONObject body = context.getRequestBody();
        JSONArray requestedLines = body != null ? body.optJSONArray("lines") : null;
        if (requestedLines == null || requestedLines.length() == 0) {
          return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, "No lines specified");
        }

        if (hasExistingReturn(source)) {
          return NeoResponse.error(HttpServletResponse.SC_CONFLICT,
              "A return already exists for this shipment");
        }

        DocumentType docType = findReturnDocType(source, salesTransaction);
        if (docType == null) {
          throw new OBException(
              "No return document type found (salesTransaction=" + salesTransaction + ", isReturn=true)");
        }

        ShipmentInOut returnDoc = NeoCommercialDocumentFactory.createShipmentReceiptHeader(
            source, docType, salesTransaction, movementType);
        OBDal.getInstance().save(returnDoc);

        int added = createReturnLines(returnDoc, source, requestedLines);
        if (added == 0) {
          throw new OBException("No valid lines to return");
        }

        OBDal.getInstance().flush();
        ensureDocumentNo(returnDoc);

        JSONObject data = new JSONObject();
        data.put("id", returnDoc.getId());
        data.put("documentNo", returnDoc.getDocumentNo());

        JSONObject responseData = new JSONObject();
        responseData.put("data", data);

        JSONObject wrapper = new JSONObject();
        wrapper.put("response", responseData);

        return NeoResponse.created(wrapper);

      } finally {
        OBContext.restorePreviousMode();
      }
    } catch (OBException e) {
      log.warn("Error creating return from {}: {}", shipmentId, e.getMessage());
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, e.getMessage());
    } catch (Exception e) {
      log.error("Error creating return from {}: {}", shipmentId, e.getMessage(), e);
      return NeoResponse.error(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
          "An internal error occurred while creating the return");
    }
  }

  private static int createReturnLines(ShipmentInOut returnDoc, ShipmentInOut source,
      JSONArray requestedLines) throws Exception {
    Map<String, BigDecimal> qtyByLineId = parseReturnQtyMap(requestedLines);
    long lineNo = 10;
    int added = 0;
    for (ShipmentInOutLine sourceLine : source.getMaterialMgmtShipmentInOutLineList()) {
      BigDecimal returnQty = qtyByLineId.get(sourceLine.getId());
      if (returnQty == null) {
        continue;
      }
      buildAndSaveReturnLine(returnDoc, sourceLine, returnQty, lineNo);
      lineNo += 10;
      added++;
    }
    return added;
  }

  private static Map<String, BigDecimal> parseReturnQtyMap(JSONArray requestedLines) throws Exception {
    Map<String, BigDecimal> qtyByLineId = new HashMap<>();
    for (int i = 0; i < requestedLines.length(); i++) {
      JSONObject req = requestedLines.getJSONObject(i);
      String lineId = req.getString("lineId");
      BigDecimal qty = new BigDecimal(req.optString("returnQuantity", "0"));
      if (qty.compareTo(BigDecimal.ZERO) > 0) {
        qtyByLineId.put(lineId, qty);
      }
    }
    return qtyByLineId;
  }

  static ShipmentInOutLine createReturnLineShell(ShipmentInOut returnDoc,
      ShipmentInOutLine sourceLine, long lineNo) {
    ShipmentInOutLine line = OBProvider.getInstance().get(ShipmentInOutLine.class);
    line.setClient(returnDoc.getClient());
    line.setOrganization(returnDoc.getOrganization());
    line.setShipmentReceipt(returnDoc);
    line.setLineNo(lineNo);
    line.setProduct(sourceLine.getProduct());
    line.setUOM(sourceLine.getUOM());
    if (sourceLine.getStorageBin() != null) {
      line.setStorageBin(sourceLine.getStorageBin());
    }
    line.setCanceledInoutLine(sourceLine);
    return line;
  }

  private static void buildAndSaveReturnLine(ShipmentInOut returnDoc, ShipmentInOutLine sourceLine,
      BigDecimal returnQty, long lineNo) {
    ShipmentInOutLine retLine = createReturnLineShell(returnDoc, sourceLine, lineNo);
    retLine.setMovementQuantity(returnQty);
    applyOrderUOM(retLine, sourceLine, returnQty);
    OBDal.getInstance().save(retLine);
  }

  private static void applyOrderUOM(ShipmentInOutLine retLine, ShipmentInOutLine sourceLine,
      BigDecimal returnQty) {
    ProductUOM productUOM = sourceLine.getOrderUOM() != null
        ? sourceLine.getOrderUOM()
        : findProductUOM(sourceLine);
    if (productUOM == null) {
      return;
    }
    BigDecimal sourceMovQty = sourceLine.getMovementQuantity();
    BigDecimal sourceOrderQty = sourceLine.getOrderQuantity() != null
        ? sourceLine.getOrderQuantity()
        : sourceMovQty;
    BigDecimal proportionalOrderQty = sourceMovQty != null && sourceMovQty.compareTo(BigDecimal.ZERO) != 0
        ? sourceOrderQty.multiply(returnQty).divide(sourceMovQty, 10, java.math.RoundingMode.HALF_UP)
        : returnQty;
    retLine.setOrderQuantity(proportionalOrderQty);
    retLine.setOrderUOM(productUOM);
  }

  private static ProductUOM findProductUOM(ShipmentInOutLine sourceLine) {
    List<ProductUOM> results = OBDal.getInstance().createCriteria(ProductUOM.class)
        .add(Restrictions.eq(ProductUOM.PROPERTY_PRODUCT, sourceLine.getProduct()))
        .add(Restrictions.eq(ProductUOM.PROPERTY_UOM, sourceLine.getUOM()))
        .add(Restrictions.eq(ProductUOM.PROPERTY_ACTIVE, true))
        .setMaxResults(1)
        .list();
    return results.isEmpty() ? null : results.get(0);
  }

  @SuppressWarnings("java:S2077")
  private static boolean hasExistingReturn(ShipmentInOut source) {
    String sql =
        "SELECT COUNT(*) FROM m_inoutline ril " +
        "JOIN m_inout rio ON rio.m_inout_id = ril.m_inout_id " +
        "WHERE ril.canceled_inoutline_id IN (" +
        "  SELECT sil.m_inoutline_id FROM m_inoutline sil " +
        "  WHERE sil.m_inout_id = ? AND sil.isactive = 'Y'" +
        ") AND ril.isactive = 'Y' AND rio.isactive = 'Y' AND rio.docstatus NOT IN ('VO', 'CL')";
    Connection conn = OBDal.getInstance().getConnection();
    try (PreparedStatement ps = conn.prepareStatement(sql)) {
      ps.setString(1, source.getId());
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() && rs.getInt(1) > 0;
      }
    } catch (Exception e) {
      log.warn("Error checking existing return for {}: {}", source.getId(), e.getMessage());
      return false;
    }
  }

  private static DocumentType findReturnDocType(ShipmentInOut source, boolean salesTransaction) {
    List<DocumentType> results = OBDal.getInstance().createCriteria(DocumentType.class)
        .add(Restrictions.eq(DocumentType.PROPERTY_CLIENT, source.getClient()))
        .add(Restrictions.eq(DocumentType.PROPERTY_SALESTRANSACTION, salesTransaction))
        .add(Restrictions.eq(DocumentType.PROPERTY_RETURN, true))
        .add(Restrictions.eq(DocumentType.PROPERTY_ACTIVE, true))
        .add(Restrictions.in(DocumentType.PROPERTY_DOCUMENTCATEGORY,
            Arrays.asList("MMS", "MMR")))
        .addOrderBy(DocumentType.PROPERTY_DEFAULT, false)
        .setMaxResults(1)
        .list();
    return results.isEmpty() ? null : results.get(0);
  }

  static void ensureDocumentNo(ShipmentInOut receipt) {
    String current = receipt.getDocumentNo();
    if (StringUtils.isNotBlank(current) && !current.startsWith("<")) {
      return;
    }
    String docNo = Utility.getDocumentNoConnection(
        OBDal.getInstance().getConnection(false),
        new DalConnectionProvider(false),
        receipt.getClient().getId(),
        "M_InOut",
        true);
    if (StringUtils.isBlank(docNo)) {
      log.warn("Could not generate documentNo for return {} (docType={}, client={})",
          receipt.getId(),
          receipt.getDocumentType() != null ? receipt.getDocumentType().getName() : "null",
          receipt.getClient().getId());
      return;
    }
    receipt.setDocumentNo(docNo);
    OBDal.getInstance().save(receipt);
    OBDal.getInstance().flush();
  }
}
