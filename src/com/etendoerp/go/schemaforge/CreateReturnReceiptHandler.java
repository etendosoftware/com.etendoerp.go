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
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.inject.Named;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.criterion.Restrictions;
import org.openbravo.base.exception.OBException;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.erpCommon.utility.Utility;
import org.openbravo.model.common.enterprise.DocumentType;
import org.openbravo.model.common.enterprise.Locator;
import org.openbravo.model.materialmgmt.transaction.ShipmentInOut;
import org.openbravo.model.materialmgmt.transaction.ShipmentInOutLine;
import org.openbravo.service.db.DalConnectionProvider;

/**
 * NeoHandler that creates a Return Material Receipt (C-) from a completed Goods Shipment.
 * Invoked as:
 *   POST /sws/neo/goods-shipment/goodsShipment/{shipmentId}/action/createReturn
 *
 * Request body: { "lines": [{ "lineId": "...", "returnQuantity": 3 }] }
 * Response:     { "response": { "data": { "id": "...", "documentNo": "..." } } }
 *
 * Each return line has its {@code canceledInoutLine} set to the original shipment line,
 * which allows {@link ReturnMaterialReceiptHeaderHandler} to derive the source shipment
 * document number without storing it in a separate field.
 */
@Named("createReturnReceiptHandler")
public class CreateReturnReceiptHandler implements NeoHandler {

  private static final Logger log = LogManager.getLogger(CreateReturnReceiptHandler.class);
  private static final String ACTION_NAME = "createReturn";
  private static final String SPEC_GOODS_SHIPMENT = "goods-shipment";

  @Override
  public NeoResponse handle(NeoContext context) {
    if (!NeoEndpointType.ACTION.equals(context.getEndpointType())) {
      return null;
    }
    if (!ACTION_NAME.equals(context.getFieldName()) || !"POST".equals(context.getHttpMethod())) {
      return null;
    }
    if (!SPEC_GOODS_SHIPMENT.equals(context.getSpecName())) {
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

        DocumentType docType = findReturnDocType(source);
        if (docType == null) {
          throw new OBException(
              "No Return Material Receipt document type found (IsSOTrx=true, IsReturn=true)");
        }

        ShipmentInOut returnReceipt = NeoCommercialDocumentFactory.createReturnReceiptHeader(source, docType);
        OBDal.getInstance().save(returnReceipt);

        int added = createReturnLines(returnReceipt, source, requestedLines);
        if (added == 0) {
          throw new OBException("No valid lines to return");
        }

        OBDal.getInstance().flush();
        ensureDocumentNo(returnReceipt);

        JSONObject data = new JSONObject();
        data.put("id", returnReceipt.getId());
        data.put("documentNo", returnReceipt.getDocumentNo());

        JSONObject responseData = new JSONObject();
        responseData.put("data", data);

        JSONObject wrapper = new JSONObject();
        wrapper.put("response", responseData);

        return NeoResponse.created(wrapper);

      } finally {
        OBContext.restorePreviousMode();
      }
    } catch (OBException e) {
      log.warn("Error creating return receipt from shipment {}: {}", shipmentId, e.getMessage());
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, e.getMessage());
    } catch (Exception e) {
      log.error("Error creating return receipt from shipment {}: {}", shipmentId, e.getMessage(), e);
      return NeoResponse.error(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
          "An internal error occurred while creating the return receipt");
    }
  }

  @Override
  public NeoResponse afterHandle(NeoContext context) {
    return null;
  }

  private int createReturnLines(ShipmentInOut returnReceipt, ShipmentInOut source,
      JSONArray requestedLines) throws Exception {
    Map<String, BigDecimal> qtyByLineId = new HashMap<>();
    for (int i = 0; i < requestedLines.length(); i++) {
      JSONObject req = requestedLines.getJSONObject(i);
      String lineId = req.getString("lineId");
      BigDecimal qty = BigDecimal.valueOf(req.optDouble("returnQuantity", 0));
      if (qty.compareTo(BigDecimal.ZERO) > 0) {
        qtyByLineId.put(lineId, qty);
      }
    }

    long lineNo = 10;
    int added = 0;
    for (ShipmentInOutLine sourceLine : source.getMaterialMgmtShipmentInOutLineList()) {
      BigDecimal returnQty = qtyByLineId.get(sourceLine.getId());
      if (returnQty == null) {
        continue;
      }
      ShipmentInOutLine retLine = OBProvider.getInstance().get(ShipmentInOutLine.class);
      retLine.setClient(returnReceipt.getClient());
      retLine.setOrganization(returnReceipt.getOrganization());
      retLine.setShipmentReceipt(returnReceipt);
      retLine.setLineNo(lineNo);
      retLine.setProduct(sourceLine.getProduct());
      retLine.setUOM(sourceLine.getUOM());
      retLine.setMovementQuantity(returnQty);
      Locator bin = sourceLine.getStorageBin();
      if (bin != null) {
        retLine.setStorageBin(bin);
      }
      retLine.setCanceledInoutLine(sourceLine);
      OBDal.getInstance().save(retLine);
      lineNo += 10;
      added++;
    }
    return added;
  }

  private DocumentType findReturnDocType(ShipmentInOut source) {
    List<DocumentType> results = OBDal.getInstance().createCriteria(DocumentType.class)
        .add(Restrictions.eq(DocumentType.PROPERTY_CLIENT, source.getClient()))
        .add(Restrictions.eq(DocumentType.PROPERTY_SALESTRANSACTION, true))
        .add(Restrictions.eq(DocumentType.PROPERTY_RETURN, true))
        .add(Restrictions.eq(DocumentType.PROPERTY_ACTIVE, true))
        .add(Restrictions.in(DocumentType.PROPERTY_DOCUMENTCATEGORY,
            Arrays.asList("MMS", "MMR")))
        .addOrderBy(DocumentType.PROPERTY_DEFAULT, false)
        .setMaxResults(1)
        .list();
    return results.isEmpty() ? null : results.get(0);
  }

  void ensureDocumentNo(ShipmentInOut receipt) {
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
      log.warn("Could not generate documentNo for return receipt {} (docType={}, client={})",
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
