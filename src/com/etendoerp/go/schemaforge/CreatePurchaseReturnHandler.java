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
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;

import javax.inject.Named;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.time.DateUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openbravo.base.exception.OBException;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.access.User;
import org.openbravo.model.common.enterprise.DocumentType;
import org.openbravo.model.materialmgmt.transaction.ShipmentInOut;
import org.openbravo.model.materialmgmt.transaction.ShipmentInOutLine;

import com.etendoerp.go.schemaforge.util.NeoActionContract;

/**
 * NeoHandler that creates a draft Purchase Return Receipt (M_InOut with movementType='V-') from a
 * completed Goods Receipt. Invoked via:
 *   POST /sws/neo/goods-receipt/goodsReceipt/{recordId}/action/createPurchaseReturn
 *
 * <p>Request body (ETP-5711): {@code { "lines": [{ "lineId": "...", "returnQuantity": N }],
 * "reason": "..." }}. {@code lines} is optional: omitted (or empty) returns everything still
 * returnable of every line; given, exactly those lines. The quantities are checked against what is
 * still returnable before anything is created ({@link ReturnableQuantity}), and the return is
 * always left in DRAFT.
 * <p>Response: {@code { "response": { "data": { "id", "documentNo", "documentStatus", "lines": [
 * { "id", "product", "productName", "returnedQuantity", "originalQuantity", "sourceLineId" }] } } }}
 */
@Named("createPurchaseReturnHandler")
public class CreatePurchaseReturnHandler implements NeoHandler {

  private static final Logger log = LogManager.getLogger(CreatePurchaseReturnHandler.class);
  private static final String ACTION_NAME = "createPurchaseReturn";
  private static final String MOVEMENT_TYPE_PURCHASE_RETURN = "V-";

  /**
   * The contract published to agents for {@link #ACTION_NAME} (ETP-5711), referenced explicitly
   * from {@link GoodsReceiptHeaderHandler#actionContracts()}. REST and the SPA do not judge a call
   * against it; the MCP does.
   */
  static final NeoActionContract CONTRACT = ReturnFromDocumentSupport.contract(ACTION_NAME,
      "goods receipt", "return to vendor shipment",
      "complete it with documentAction (docAction CO) to book the stock movement, or add and "
          + "edit lines first through the return-to-vendor-shipment window.");

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

        ReturnFromDocumentSupport.Plan plan =
            ReturnFromDocumentSupport.plan(original, context.getRequestBody());
        if (plan.refusal != null) {
          return plan.refusal;
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
        DocumentType returnDocType = ReturnShipmentUtils.findReturnDocTypeForOrg(
            original.getClient(), original.getOrganization().getId(), "MMR", false, true, false);
        if (returnDocType == null) {
          return NeoResponse.error(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
              "No RTV Shipment document type (MMR, isReturn=Y) found for this organization");
        }
        returnReceipt.setDocumentType(returnDocType);
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
        // ETP-4028: EM_Etgo_Currency_ID is mandatory on M_InOut — every new record must carry it.
        returnReceipt.setEtgoCurrency(original.getEtgoCurrency());
        ReturnFromDocumentSupport.applyReason(returnReceipt, context.getRequestBody());
        returnReceipt.setCreatedBy(currentUser);
        returnReceipt.setUpdatedBy(currentUser);
        returnReceipt.setCreationDate(new Date());
        returnReceipt.setUpdated(new Date());

        OBDal.getInstance().save(returnReceipt);
        OBDal.getInstance().flush();

        List<ReturnFromDocumentSupport.CreatedLine> created =
            buildReturnLines(original, returnReceipt, plan.requests, currentUser);

        OBDal.getInstance().flush();
        OBDal.getInstance().refresh(returnReceipt);

        ensureDocumentNo(returnReceipt);
        // ETP-5051: the plan-quota check of the created return and its lines.
        NeoQuotaCreateCheck.requireAllowedDocument(returnReceipt);

        return NeoResponse.createdWithData(ReturnFromDocumentSupport.describe(returnReceipt, created));

      } finally {
        OBContext.restorePreviousMode();
      }
    } catch (NeoQuotaCreateCheck.Refused e) {
      return e.toResponse();
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

  private List<ReturnFromDocumentSupport.CreatedLine> buildReturnLines(ShipmentInOut original,
      ShipmentInOut returnReceipt, ReturnableQuantity.Requests requests, User currentUser) {
    List<ReturnFromDocumentSupport.CreatedLine> created = new ArrayList<>();
    long lineNo = 10L;
    for (ShipmentInOutLine originalLine : original.getMaterialMgmtShipmentInOutLineList()) {
      BigDecimal returnQty = requests.quantity(originalLine.getId());
      if (returnQty != null) {
        ShipmentInOutLine returnLine = addReturnLine(originalLine, returnReceipt, returnQty,
            currentUser, lineNo);
        created.add(new ReturnFromDocumentSupport.CreatedLine(returnLine, originalLine, returnQty));
        lineNo += 10L;
      }
    }
    return created;
  }

  private ShipmentInOutLine addReturnLine(ShipmentInOutLine originalLine,
      ShipmentInOut returnReceipt, BigDecimal returnQty, User currentUser, long lineNo) {
    ShipmentInOutLine returnLine = NeoReturnReceiptService.createReturnLineShell(
        returnReceipt, originalLine, lineNo);
    // ETP-5313: same stored-negative rule as every other return write path.
    returnLine.setMovementQuantity(ReturnLineQuantityPolicy.toStoredQuantity(returnQty));
    returnLine.setCreatedBy(currentUser);
    returnLine.setUpdatedBy(currentUser);
    returnLine.setCreationDate(new Date());
    returnLine.setUpdated(new Date());
    returnReceipt.getMaterialMgmtShipmentInOutLineList().add(returnLine);
    OBDal.getInstance().save(returnLine);
    return returnLine;
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
}
