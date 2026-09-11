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

import java.util.List;

import javax.inject.Named;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.base.exception.OBException;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.core.SessionHandler;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.enterprise.DocumentType;
import org.openbravo.model.common.enterprise.Locator;
import org.openbravo.model.common.order.Order;
import org.openbravo.model.materialmgmt.transaction.ShipmentInOut;

/**
 * NeoHandler that creates a Goods Shipment (ShipmentInOut) in Draft status
 * from a Sales Order. Invoked as an ACTION endpoint via:
 *   POST /sws/neo/sales-order/header/{recordId}/action/createShipment
 */
@Named("createShipmentHandler")
public class CreateShipmentHandler implements NeoHandler {

  private static final Logger log = LogManager.getLogger(CreateShipmentHandler.class);
  private static final String ACTION_NAME = "createShipment";
  private static final String SPEC_SALES_ORDER = "sales-order";

  @Override
  public NeoResponse handle(NeoContext context) {
    if (!NeoEndpointType.ACTION.equals(context.getEndpointType())) {
      return null;
    }

    if (!ACTION_NAME.equals(context.getFieldName()) || !"POST".equals(context.getHttpMethod())) {
      return null;
    }

    if (!SPEC_SALES_ORDER.equals(context.getSpecName())) {
      return null;
    }

    String recordId = context.getRecordId();
    if (StringUtils.isBlank(recordId)) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, "Record ID is required");
    }

    try {
      OBContext.setAdminMode(true);
      try {
        Order order = OBDal.getInstance().get(Order.class, recordId);
        if (order == null) {
          return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST,
              "Order not found: " + recordId);
        }

        // ETP-5276: validate BEFORE creating/persisting anything. Doing this after
        // OBDal.save(shipment) left an empty, orphaned shipment behind whenever the
        // order had zero pending lines (Hibernate flushes the managed header on
        // commit regardless of the 400 this method returns).
        InOutLineFromOrderFactory.PendingLinesResult result =
            InOutLineFromOrderFactory.resolvePendingLinesAndLocator(order,
                "No hay líneas pendientes de entrega en este pedido", this::findDefaultLocator);

        ShipmentInOut shipment = createShipmentHeader(order);
        OBDal.getInstance().save(shipment);

        createShipmentLines(shipment, result.getPendingLines(), result.getLocator());

        OBDal.getInstance().flush();

        JSONObject data = new JSONObject();
        data.put("id", shipment.getId());
        data.put("documentNo", shipment.getDocumentNo());
        data.put("grandTotal", JSONObject.NULL);

        JSONObject responseData = new JSONObject();
        responseData.put("data", data);

        JSONObject wrapper = new JSONObject();
        wrapper.put("response", responseData);

        return NeoResponse.created(wrapper);
      } finally {
        OBContext.restorePreviousMode();
      }
    } catch (OBException e) {
      SessionHandler.getInstance().rollback();
      log.warn("Error creating shipment from order {}: {}", recordId, e.getMessage());
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, e.getMessage());
    } catch (Exception e) {
      SessionHandler.getInstance().rollback();
      log.error("Error creating shipment from order {}: {}", recordId, e.getMessage(), e);
      return NeoResponse.error(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
          "An internal error occurred while creating the shipment");
    }
  }

  private ShipmentInOut createShipmentHeader(Order order) {
    DocumentType docType = findShipmentDocType(order);
    if (docType == null) {
      throw new OBException("No Goods Shipment document type found (docBaseType=MMS, isSOTrx=true)");
    }

    return NeoCommercialDocumentFactory.createShipmentReceiptHeader(
        order,
        docType,
        true,
        "C-");
  }

  protected void createShipmentLines(ShipmentInOut shipment,
      List<InOutLineFromOrderFactory.PendingOrderLine> pendingLines, Locator defaultLocator) {
    long lineNo = 10;
    for (InOutLineFromOrderFactory.PendingOrderLine pendingLine : pendingLines) {
      InOutLineFromOrderFactory.createAndLinkLine(shipment, pendingLine.getOrderLine(),
          defaultLocator, lineNo, pendingLine.getPendingQty());
      lineNo += 10;
    }
  }

  private DocumentType findShipmentDocType(Order order) {
    return NeoCommercialDocumentFactory.findShipmentDocType(order.getClient());
  }

  protected Locator findDefaultLocator(Order order) {
    if (order.getWarehouse() == null) {
      return null;
    }
    return NeoCommercialDocumentFactory.findDefaultLocator(order.getWarehouse());
  }
}
