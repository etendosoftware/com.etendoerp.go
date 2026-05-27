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

import javax.inject.Named;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.order.OrderLine;
import org.openbravo.model.materialmgmt.transaction.ShipmentInOut;

/**
 * NeoHandler for the Goods Shipment line entity.
 *
 * Extends {@link AbstractInOutLineHandler} with a pre-hook that links the parent
 * shipment header to its sales order when a line is imported from an order line.
 * This mirrors the behaviour of {@code CreateShipmentHandler}, which sets the
 * {@code C_Order_ID} on the header at creation time.
 */
@Named("goodsShipmentLineHandler")
public class GoodsShipmentLineHandler extends AbstractInOutLineHandler {

  private static final Logger log = LogManager.getLogger(GoodsShipmentLineHandler.class);

  @Override
  public NeoResponse handle(NeoContext context) {
    if (!"POST".equals(context.getHttpMethod())) {
      return null;
    }
    JSONObject body = context.getRequestBody();
    if (body == null) {
      return null;
    }
    String orderLineId = body.optString("salesOrderLine", null);
    String parentId = body.optString("parentId", null);
    if (StringUtils.isBlank(orderLineId) || StringUtils.isBlank(parentId)) {
      return null;
    }
    try {
      OBContext.setAdminMode(true);
      OrderLine orderLine = OBDal.getInstance().get(OrderLine.class, orderLineId);
      ShipmentInOut shipment = OBDal.getInstance().get(ShipmentInOut.class, parentId);
      if (orderLine != null && shipment != null
          && shipment.getSalesOrder() == null
          && orderLine.getSalesOrder() != null) {
        shipment.setSalesOrder(orderLine.getSalesOrder());
        OBDal.getInstance().save(shipment);
      }
    } catch (Exception e) {
      log.warn("Could not link salesOrder on shipment {} from line {}: {}", parentId, orderLineId,
          e.getMessage());
    } finally {
      OBContext.restorePreviousMode();
    }
    return null;
  }
}
