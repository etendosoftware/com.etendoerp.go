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

import javax.inject.Named;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.base.exception.OBException;
import org.openbravo.dal.core.DalUtil;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.erpCommon.utility.Utility;
import org.openbravo.model.common.order.Order;
import org.openbravo.model.common.order.OrderLine;
import org.openbravo.service.db.DalConnectionProvider;

/**
 * NeoHandler that clones a Sales Order or Purchase Order into a new draft.
 *
 * Copies the order header and all active lines, resetting statuses, quantities
 * and dates. The clone is always created in Draft (DR) status regardless of the
 * source order status, mirroring classic Etendo behaviour.
 *
 * Invoked via:
 *   POST /sws/neo/sales-order/header/{recordId}/action/cloneOrder
 *   POST /sws/neo/purchase-order/header/{recordId}/action/cloneOrder
 */
@Named("cloneOrderHandler")
public class CloneOrderHandler implements NeoHandler {

  private static final Logger log = LogManager.getLogger(CloneOrderHandler.class);
  private static final String ACTION_NAME = "cloneOrder";

  @Override
  public NeoResponse handle(NeoContext context) {
    if (!NeoEndpointType.ACTION.equals(context.getEndpointType())) {
      return null;
    }
    if (!ACTION_NAME.equals(context.getFieldName()) || !"POST".equals(context.getHttpMethod())) {
      return null;
    }
    String specName = context.getSpecName();
    if (!"sales-order".equals(specName) && !"purchase-order".equals(specName)) {
      return null;
    }

    String recordId = context.getRecordId();
    if (StringUtils.isBlank(recordId)) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, "Record ID is required");
    }

    try {
      OBContext.setAdminMode(true);
      try {
        Order source = OBDal.getInstance().get(Order.class, recordId);
        if (source == null) {
          return NeoResponse.error(HttpServletResponse.SC_NOT_FOUND,
              "Order not found: " + recordId);
        }

        Order clone = cloneOrder(source);
        OBDal.getInstance().flush();
        OBDal.getInstance().refresh(clone);

        JSONObject data = new JSONObject();
        data.put("id", clone.getId());
        data.put("documentNo", clone.getDocumentNo() != null ? clone.getDocumentNo() : "");

        return NeoResponse.createdWithData(data);
      } finally {
        OBContext.restorePreviousMode();
      }
    } catch (OBException e) {
      log.warn("Error cloning order {}: {}", recordId, e.getMessage());
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, e.getMessage());
    } catch (Exception e) {
      log.error("Error cloning order {}: {}", recordId, e.getMessage(), e);
      return NeoResponse.error(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
          "An internal error occurred while cloning the order");
    }
  }

  private Order cloneOrder(Order source) {
    Order clone = (Order) DalUtil.copy(source, false);

    Date today = todayMidnight();

    clone.setDocumentNo(generateDocumentNo(source));
    clone.setDocumentStatus("DR");
    clone.setDocumentAction("CO");
    clone.setProcessed(false);
    clone.setDelivered(false);
    clone.setPosted("N");
    clone.setReservationStatus(null);
    clone.setOrderDate(today);
    clone.setScheduledDeliveryDate(today);
    clone.setGrandTotalAmount(BigDecimal.ZERO);
    clone.setSummedLineAmount(BigDecimal.ZERO);
    clone.setCreationDate(new Date());
    clone.setUpdated(new Date());

    OBDal.getInstance().save(clone);

    for (OrderLine sourceLine : source.getOrderLineList()) {
      if (!sourceLine.isActive()) {
        continue;
      }
      OrderLine clonedLine = (OrderLine) DalUtil.copy(sourceLine, false);
      clonedLine.setSalesOrder(clone);
      clonedLine.setDeliveredQuantity(BigDecimal.ZERO);
      clonedLine.setInvoicedQuantity(BigDecimal.ZERO);
      clonedLine.setReservedQuantity(BigDecimal.ZERO);
      clonedLine.setReservationStatus(null);
      clonedLine.setOrderDate(today);
      clonedLine.setScheduledDeliveryDate(today);
      clonedLine.setCreationDate(new Date());
      clonedLine.setUpdated(new Date());
      clone.getOrderLineList().add(clonedLine);
      OBDal.getInstance().save(clonedLine);
    }

    return clone;
  }

  private String generateDocumentNo(Order source) {
    try {
      String docNo = Utility.getDocumentNo(
          new DalConnectionProvider(false), source.getClient().getId(), "C_Order", true);
      return StringUtils.isNotBlank(docNo) ? docNo : "*";
    } catch (Exception e) {
      log.warn("Could not generate document number for cloned order, using '*': {}", e.getMessage());
      return "*";
    }
  }

  private Date todayMidnight() {
    Calendar cal = Calendar.getInstance();
    cal.set(Calendar.HOUR_OF_DAY, 0);
    cal.set(Calendar.MINUTE, 0);
    cal.set(Calendar.SECOND, 0);
    cal.set(Calendar.MILLISECOND, 0);
    return cal.getTime();
  }
}
