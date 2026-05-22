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
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import javax.inject.Named;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.time.DateUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.base.exception.OBException;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.access.User;
import org.openbravo.model.materialmgmt.transaction.ShipmentInOut;
import org.openbravo.model.materialmgmt.transaction.ShipmentInOutLine;

/**
 * NeoHandler that creates a draft Purchase Return Receipt (M_InOut with movementType='V-') from a
 * completed Goods Receipt. Invoked via:
 *   POST /sws/neo/goods-receipt/goodsReceipt/{recordId}/action/createPurchaseReturn
 *
 * <p>Request body: {@code { "lines": [{ "lineId": "...", "returnQuantity": N }], "reason": "..." }}
 * <p>Response: {@code { "response": { "data": { "id": "...", "documentNo": "..." } } }}
 */
@Named("createPurchaseReturnHandler")
public class CreatePurchaseReturnHandler implements NeoHandler {

  private static final Logger log = LogManager.getLogger(CreatePurchaseReturnHandler.class);
  private static final String ACTION_NAME = "createPurchaseReturn";
  private static final String MOVEMENT_TYPE_PURCHASE_RETURN = "V-";

  @Override
  public NeoResponse handle(NeoContext context) {
    if (!NeoEndpointType.ACTION.equals(context.getEndpointType())) {
      return null;
    }
    if (!ACTION_NAME.equals(context.getFieldName()) || !"POST".equals(context.getHttpMethod())) {
      return null;
    }

    String recordId = context.getRecordId();
    if (StringUtils.isBlank(recordId)) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, "Record ID is required");
    }

    try {
      OBContext.setAdminMode(true);
      try {
        ShipmentInOut original = OBDal.getInstance().get(ShipmentInOut.class, recordId);
        if (original == null) {
          return NeoResponse.error(HttpServletResponse.SC_NOT_FOUND,
              "Goods receipt not found: " + recordId);
        }

        Map<String, BigDecimal> lineQtyMap = parseLinePayload(context.getRequestBody());
        if (lineQtyMap.isEmpty()) {
          return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST,
              "No lines specified for the return");
        }

        User currentUser = OBContext.getOBContext().getUser();
        Date today = DateUtils.truncate(new Date(), Calendar.DATE);

        ShipmentInOut returnReceipt = OBProvider.getInstance().get(ShipmentInOut.class);
        returnReceipt.setClient(original.getClient());
        returnReceipt.setOrganization(original.getOrganization());
        returnReceipt.setBusinessPartner(original.getBusinessPartner());
        if (original.getPartnerAddress() != null) {
          returnReceipt.setPartnerAddress(original.getPartnerAddress());
        }
        returnReceipt.setWarehouse(original.getWarehouse());
        returnReceipt.setDocumentType(original.getDocumentType());
        returnReceipt.setMovementType(MOVEMENT_TYPE_PURCHASE_RETURN);
        returnReceipt.setSalesTransaction(false);
        returnReceipt.setDocumentStatus("DR");
        returnReceipt.setDocumentAction("CO");
        returnReceipt.setPosted("N");
        returnReceipt.setProcessed(false);
        returnReceipt.setDocumentNo("<*>");
        returnReceipt.setMovementDate(today);
        returnReceipt.setAccountingDate(today);
        returnReceipt.setCompletelyInvoiced(false);
        returnReceipt.setCreatedBy(currentUser);
        returnReceipt.setUpdatedBy(currentUser);
        returnReceipt.setCreationDate(new Date());
        returnReceipt.setUpdated(new Date());

        OBDal.getInstance().save(returnReceipt);
        OBDal.getInstance().flush();

        buildReturnLines(original, returnReceipt, lineQtyMap, currentUser);

        OBDal.getInstance().flush();
        OBDal.getInstance().refresh(returnReceipt);

        ensureDocumentNo(returnReceipt);

        JSONObject data = new JSONObject();
        data.put("id", returnReceipt.getId());
        data.put("documentNo",
            returnReceipt.getDocumentNo() != null ? returnReceipt.getDocumentNo() : "");

        return NeoResponse.createdWithData(data);

      } finally {
        OBContext.restorePreviousMode();
      }
    } catch (OBException e) {
      OBDal.getInstance().rollbackAndClose();
      log.warn("Error creating purchase return from receipt {}: {}", recordId, e.getMessage());
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, e.getMessage());
    } catch (Exception e) {
      OBDal.getInstance().rollbackAndClose();
      log.error("Error creating purchase return from receipt {}: {}", recordId, e.getMessage(), e);
      return NeoResponse.error(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
          "An internal error occurred while creating the purchase return");
    }
  }

  private void buildReturnLines(ShipmentInOut original, ShipmentInOut returnReceipt,
      Map<String, BigDecimal> lineQtyMap, User currentUser) {
    long lineNo = 10L;
    for (ShipmentInOutLine originalLine : original.getMaterialMgmtShipmentInOutLineList()) {
      BigDecimal returnQty = resolveReturnQty(originalLine, lineQtyMap);
      if (returnQty != null) {
        addReturnLine(original, originalLine, returnReceipt, returnQty, currentUser, lineNo);
        lineNo += 10L;
      }
    }
  }

  private BigDecimal resolveReturnQty(ShipmentInOutLine originalLine,
      Map<String, BigDecimal> lineQtyMap) {
    if (!originalLine.isActive() || originalLine.getProduct() == null) {
      return null;
    }
    BigDecimal qty = lineQtyMap.get(originalLine.getId());
    return (qty == null || qty.compareTo(BigDecimal.ZERO) <= 0) ? null : qty;
  }

  private void addReturnLine(ShipmentInOut original, ShipmentInOutLine originalLine,
      ShipmentInOut returnReceipt, BigDecimal returnQty, User currentUser, long lineNo) {
    ShipmentInOutLine returnLine = OBProvider.getInstance().get(ShipmentInOutLine.class);
    returnLine.setClient(original.getClient());
    returnLine.setOrganization(original.getOrganization());
    returnLine.setShipmentReceipt(returnReceipt);
    returnLine.setLineNo(lineNo);
    returnLine.setProduct(originalLine.getProduct());
    returnLine.setUOM(originalLine.getUOM());
    if (originalLine.getStorageBin() != null) {
      returnLine.setStorageBin(originalLine.getStorageBin());
    }
    returnLine.setMovementQuantity(returnQty.negate());
    returnLine.setCanceledInoutLine(originalLine);
    returnLine.setCreatedBy(currentUser);
    returnLine.setUpdatedBy(currentUser);
    returnLine.setCreationDate(new Date());
    returnLine.setUpdated(new Date());
    returnReceipt.getMaterialMgmtShipmentInOutLineList().add(returnLine);
    OBDal.getInstance().save(returnLine);
  }

  private void ensureDocumentNo(ShipmentInOut returnReceipt) {
    String current = returnReceipt.getDocumentNo();
    if (StringUtils.isNotBlank(current) && !current.startsWith("<")) {
      return;
    }
    String clientId = returnReceipt.getClient().getId();
    String docTypeId = returnReceipt.getDocumentType() != null
        ? returnReceipt.getDocumentType().getId() : "";
    String docNo = NeoHandlerUtils.fetchDocumentNo(docTypeId, clientId, "M_InOut", log);
    if (StringUtils.isNotBlank(docNo)) {
      returnReceipt.setDocumentNo(docNo);
      OBDal.getInstance().save(returnReceipt);
      OBDal.getInstance().flush();
    } else {
      log.warn("Could not generate documentNo for purchase return receipt {}",
          returnReceipt.getId());
    }
  }

  private Map<String, BigDecimal> parseLinePayload(JSONObject body) {
    Map<String, BigDecimal> result = new HashMap<>();
    if (body == null) {
      return result;
    }
    JSONArray linesArr = body.optJSONArray("lines");
    if (linesArr == null) {
      return result;
    }
    for (int i = 0; i < linesArr.length(); i++) {
      try {
        JSONObject entry = linesArr.getJSONObject(i);
        String lineId = entry.optString("lineId", null);
        Object qtyVal = entry.opt("returnQuantity");
        if (StringUtils.isBlank(lineId) || qtyVal == null) {
          continue;
        }
        BigDecimal qty = new BigDecimal(qtyVal.toString());
        if (qty.compareTo(BigDecimal.ZERO) > 0) {
          result.put(lineId, qty);
        }
      } catch (Exception e) {
        log.warn("Failed to parse return line at index {}: {}", i, e.getMessage());
      }
    }
    return result;
  }
}
