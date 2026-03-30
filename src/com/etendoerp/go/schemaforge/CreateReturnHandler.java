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
 * All portions are Copyright © 2021–2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.schemaforge;

import java.math.BigDecimal;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import javax.inject.Named;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.query.Query;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.process.ProcessInstance;
import org.openbravo.model.ad.ui.Process;
import org.openbravo.model.common.businesspartner.BusinessPartner;
import org.openbravo.model.common.businesspartner.Location;
import org.openbravo.model.common.enterprise.DocumentType;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.common.enterprise.Warehouse;
import org.openbravo.model.common.order.Order;
import org.openbravo.model.common.order.OrderLine;
import org.openbravo.model.common.plm.Product;
import org.openbravo.model.common.uom.UOM;
import org.openbravo.model.materialmgmt.transaction.ShipmentInOut;
import org.openbravo.model.materialmgmt.transaction.ShipmentInOutLine;
import org.openbravo.model.pricing.pricelist.PriceList;
import org.openbravo.service.db.CallProcess;

/**
 * NeoHandler for creating a Return from Customer order based on shipment lines.
 *
 * Intercepts ACTION endpoint with fieldName "createReturn" on POST.
 * Expects request body:
 * <pre>
 * {
 *   "lines": [{"lineId": "...", "returnQuantity": 5}, ...],
 *   "reason": "Defective items"
 * }
 * </pre>
 *
 * Creates a C_Order with DocBaseType SOO + isReturn=Y, adds lines with negative
 * quantities, and processes the order to Complete.
 */
@Named("createReturn")
public class CreateReturnHandler implements NeoHandler {

  private static final Logger log = LogManager.getLogger(CreateReturnHandler.class);

  private static final String C_ORDER_POST_ID = "104";
  private static final String DOC_ACTION_COMPLETE = "CO";
  private static final String DOC_STATUS_COMPLETED = "CO";

  @Override
  public NeoResponse handle(NeoContext context) {
    // Only intercept ACTION + createReturn + POST
    if (context.getEndpointType() != NeoEndpointType.ACTION
        || !"createReturn".equals(context.getFieldName())
        || !"POST".equals(context.getHttpMethod())) {
      return null;
    }

    return createReturn(context);
  }

  private NeoResponse createReturn(NeoContext ctx) {
    boolean adminMode = false;
    try {
      OBContext.setAdminMode(true);
      adminMode = true;

      JSONObject body = ctx.getRequestBody();
      if (body == null) {
        return NeoResponse.error(400, "Request body is required");
      }

      // Parse request
      JSONArray linesArray = body.optJSONArray("lines");
      if (linesArray == null || linesArray.length() == 0) {
        return NeoResponse.error(400, "At least one line is required in 'lines' array");
      }
      String reason = body.optString("reason", "");

      // Load the shipment from the recordId in the URL
      String shipmentId = ctx.getRecordId();
      if (shipmentId == null || shipmentId.isBlank()) {
        return NeoResponse.error(400, "Record ID (shipment ID) is required");
      }

      ShipmentInOut shipment = OBDal.getInstance().get(ShipmentInOut.class, shipmentId);
      if (shipment == null) {
        return NeoResponse.error(404, "Shipment not found: " + shipmentId);
      }

      // Validate shipment is completed
      if (!DOC_STATUS_COMPLETED.equals(shipment.getDocumentStatus())) {
        return NeoResponse.error(400,
            "Shipment must be Completed (CO). Current status: " + shipment.getDocumentStatus());
      }

      // Build a map of shipment line IDs for validation
      Map<String, ShipmentInOutLine> shipmentLineMap = new HashMap<>();
      for (Object lineObj : shipment.getMaterialMgmtShipmentInOutLineList()) {
        ShipmentInOutLine sLine = (ShipmentInOutLine) lineObj;
        shipmentLineMap.put(sLine.getId(), sLine);
      }

      // Parse and validate requested lines
      Map<ShipmentInOutLine, BigDecimal> linesToReturn = new HashMap<>();
      for (int i = 0; i < linesArray.length(); i++) {
        JSONObject lineReq = linesArray.getJSONObject(i);
        String lineId = lineReq.optString("lineId", "");
        BigDecimal returnQty = new BigDecimal(lineReq.optString("returnQuantity", "0"));

        if (lineId.isBlank()) {
          return NeoResponse.error(400, "lineId is required for each line (index " + i + ")");
        }

        ShipmentInOutLine sLine = shipmentLineMap.get(lineId);
        if (sLine == null) {
          return NeoResponse.error(400,
              "Line " + lineId + " does not belong to shipment " + shipmentId);
        }

        if (returnQty.compareTo(BigDecimal.ZERO) <= 0) {
          return NeoResponse.error(400,
              "returnQuantity must be > 0 for line " + lineId);
        }

        if (returnQty.compareTo(sLine.getMovementQuantity()) > 0) {
          return NeoResponse.error(400,
              "returnQuantity (" + returnQty + ") exceeds original movementQty ("
                  + sLine.getMovementQuantity() + ") for line " + lineId);
        }

        linesToReturn.put(sLine, returnQty);
      }

      // Find Return from Customer document type
      DocumentType returnDocType = findReturnDocType(shipment.getOrganization());
      if (returnDocType == null) {
        return NeoResponse.error(500,
            "No 'Return from Customer' document type found for organization "
                + shipment.getOrganization().getName());
      }

      // Create the return order
      Order returnOrder = OBProvider.getInstance().get(Order.class);
      returnOrder.setOrganization(shipment.getOrganization());
      returnOrder.setClient(shipment.getClient());
      returnOrder.setDocumentType(returnDocType);
      returnOrder.setTransactionDocument(returnDocType);
      returnOrder.setBusinessPartner(shipment.getBusinessPartner());
      returnOrder.setPartnerAddress(shipment.getPartnerAddress());
      returnOrder.setWarehouse(shipment.getWarehouse());
      returnOrder.setOrderDate(new Date());
      returnOrder.setAccountingDate(new Date());
      returnOrder.setDescription(reason);
      returnOrder.setSalesTransaction(true);
      returnOrder.setDocumentStatus("DR");
      returnOrder.setDocumentAction(DOC_ACTION_COMPLETE);
      returnOrder.setProcessed(false);

      // Copy currency and price list from original sales order if traceable
      OrderLine sampleOrigLine = findOriginalOrderLine(linesToReturn.keySet());
      if (sampleOrigLine != null && sampleOrigLine.getSalesOrder() != null) {
        Order origOrder = sampleOrigLine.getSalesOrder();
        returnOrder.setCurrency(origOrder.getCurrency());
        returnOrder.setPriceList(origOrder.getPriceList());
        returnOrder.setPaymentTerms(origOrder.getPaymentTerms());
        returnOrder.setPaymentMethod(origOrder.getPaymentMethod());
        returnOrder.setInvoiceTerms(origOrder.getInvoiceTerms());
      } else {
        // Fallback: use business partner defaults
        BusinessPartner bp = shipment.getBusinessPartner();
        if (bp.getPriceList() != null) {
          returnOrder.setPriceList(bp.getPriceList());
          returnOrder.setCurrency(bp.getPriceList().getCurrency());
        }
        if (bp.getPaymentTerms() != null) {
          returnOrder.setPaymentTerms(bp.getPaymentTerms());
        }
        if (bp.getPaymentMethod() != null) {
          returnOrder.setPaymentMethod(bp.getPaymentMethod());
        }
      }

      OBDal.getInstance().save(returnOrder);

      // Create order lines
      long lineNo = 10;
      JSONArray createdLines = new JSONArray();

      for (Map.Entry<ShipmentInOutLine, BigDecimal> entry : linesToReturn.entrySet()) {
        ShipmentInOutLine sLine = entry.getKey();
        BigDecimal returnQty = entry.getValue();

        OrderLine orderLine = OBProvider.getInstance().get(OrderLine.class);
        orderLine.setOrganization(returnOrder.getOrganization());
        orderLine.setClient(returnOrder.getClient());
        orderLine.setSalesOrder(returnOrder);
        orderLine.setLineNo(lineNo);
        orderLine.setProduct(sLine.getProduct());
        orderLine.setUOM(sLine.getUOM());

        // Return quantities are negative in Etendo
        orderLine.setOrderedQuantity(returnQty.negate());

        // Link to original shipment line
        orderLine.setGoodsShipmentLine(sLine);

        // Copy warehouse
        if (sLine.getStorageBin() != null) {
          orderLine.setWarehouse(sLine.getStorageBin().getWarehouse());
        } else {
          orderLine.setWarehouse(returnOrder.getWarehouse());
        }

        // Trace price from original sales order line if available
        OrderLine origOrderLine = sLine.getSalesOrderLine();
        if (origOrderLine != null) {
          orderLine.setUnitPrice(origOrderLine.getUnitPrice());
          orderLine.setListPrice(origOrderLine.getListPrice());
          orderLine.setPriceLimit(origOrderLine.getPriceLimit());
          orderLine.setTax(origOrderLine.getTax());
          orderLine.setLineNetAmount(
              origOrderLine.getUnitPrice().multiply(returnQty).negate());
        } else {
          orderLine.setUnitPrice(BigDecimal.ZERO);
          orderLine.setListPrice(BigDecimal.ZERO);
          orderLine.setLineNetAmount(BigDecimal.ZERO);
        }

        OBDal.getInstance().save(orderLine);

        JSONObject lineInfo = new JSONObject();
        lineInfo.put("orderLineId", orderLine.getId());
        lineInfo.put("shipmentLineId", sLine.getId());
        lineInfo.put("product", sLine.getProduct() != null ? sLine.getProduct().getName() : "");
        lineInfo.put("returnQuantity", returnQty);
        createdLines.put(lineInfo);

        lineNo += 10;
      }

      // Flush before processing
      OBDal.getInstance().flush();

      // Process the return order (Complete)
      NeoResponse processResult = processReturnOrder(returnOrder);
      OBDal.getInstance().refresh(returnOrder);

      // Build response
      JSONObject response = new JSONObject();
      JSONObject data = new JSONObject();
      data.put("returnOrderId", returnOrder.getId());
      data.put("documentNo", returnOrder.getDocumentNo());
      data.put("docStatus", returnOrder.getDocumentStatus());
      data.put("lines", createdLines);
      data.put("message", "Return order created successfully");

      // Include process result if there were issues
      if (processResult != null && processResult.getHttpStatus() != 200) {
        data.put("processWarning", processResult.getBody());
      }

      // TODO: Credit note creation skipped — use RM_CreateInvoice action on the
      // return order separately if needed.

      response.put("response", data);
      return NeoResponse.ok(response);

    } catch (Exception e) {
      log.error("Error creating return from shipment", e);
      try {
        OBDal.getInstance().rollbackAndClose();
      } catch (Exception rollbackEx) {
        log.error("Error during rollback", rollbackEx);
      }
      return NeoResponse.error(500, "Failed to create return: " + e.getMessage());
    } finally {
      if (adminMode) {
        OBContext.restorePreviousMode();
      }
    }
  }

  /**
   * Find the Return from Customer document type for the given organization.
   * DocBaseType = 'SOO', isReturn = Y, isSoTrx = Y.
   */
  private DocumentType findReturnDocType(Organization org) {
    try {
      Set<String> orgTree = OBContext.getOBContext()
          .getOrganizationStructureProvider(org.getClient().getId())
          .getNaturalTree(org.getId());

      String hql = "SELECT dt FROM DocumentType dt "
          + "WHERE dt.documentCategory = 'SOO' "
          + "AND dt.return = true "
          + "AND dt.salesTransaction = true "
          + "AND dt.organization.id IN (:orgIds) "
          + "AND dt.active = true "
          + "ORDER BY dt.default DESC";

      Query<DocumentType> query = OBDal.getInstance().getSession()
          .createQuery(hql, DocumentType.class);
      query.setParameterList("orgIds", orgTree);
      query.setMaxResults(1);

      return query.uniqueResult();
    } catch (Exception e) {
      log.error("Error finding return document type", e);
      return null;
    }
  }

  /**
   * Find any original sales order line from the shipment lines to copy header defaults.
   */
  private OrderLine findOriginalOrderLine(Set<ShipmentInOutLine> shipmentLines) {
    for (ShipmentInOutLine sLine : shipmentLines) {
      OrderLine origLine = sLine.getSalesOrderLine();
      if (origLine != null) {
        return origLine;
      }
    }
    return null;
  }

  /**
   * Process the return order using C_Order_Post (process ID 104).
   */
  private NeoResponse processReturnOrder(Order returnOrder) {
    try {
      // Set DocAction to Complete
      returnOrder.setDocumentAction(DOC_ACTION_COMPLETE);
      OBDal.getInstance().save(returnOrder);
      OBDal.getInstance().flush();

      Process orderPost = OBDal.getInstance().get(Process.class, C_ORDER_POST_ID);
      if (orderPost == null) {
        return NeoResponse.error(500, "C_Order_Post process not found (ID: " + C_ORDER_POST_ID + ")");
      }

      ProcessInstance pInstance = CallProcess.getInstance()
          .call(orderPost, returnOrder.getId(), null);

      OBDal.getInstance().getSession().refresh(pInstance);

      if (pInstance.getResult() == 0L) {
        String errorMsg = pInstance.getErrorMsg();
        log.warn("C_Order_Post returned error for return order {}: {}",
            returnOrder.getId(), errorMsg);
        return NeoResponse.error(400, "Order processing failed: " + errorMsg);
      }

      return NeoResponse.ok(new JSONObject());
    } catch (Exception e) {
      log.error("Error processing return order", e);
      return NeoResponse.error(500, "Order processing error: " + e.getMessage());
    }
  }
}
