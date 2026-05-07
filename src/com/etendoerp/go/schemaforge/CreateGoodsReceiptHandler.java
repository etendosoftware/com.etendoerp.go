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

import java.util.Date;
import java.util.List;

import javax.inject.Named;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.criterion.Restrictions;
import org.openbravo.base.exception.OBException;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.erpCommon.utility.Utility;
import org.openbravo.model.common.enterprise.DocumentType;
import org.openbravo.model.common.enterprise.Locator;
import org.openbravo.model.common.order.Order;
import org.openbravo.model.common.order.OrderLine;
import org.openbravo.model.materialmgmt.transaction.ShipmentInOut;
import org.openbravo.model.materialmgmt.transaction.ShipmentInOutLine;
import org.openbravo.service.db.DalConnectionProvider;

/**
 * NeoHandler that creates a Goods Receipt (ShipmentInOut) in Draft status
 * from a Purchase Order. Invoked as an ACTION endpoint via:
 *   POST /sws/neo/purchase-order/header/{recordId}/action/createGoodsReceipt
 */
@Named("createGoodsReceiptHandler")
public class CreateGoodsReceiptHandler implements NeoHandler {

  private static final Logger log = LogManager.getLogger(CreateGoodsReceiptHandler.class);
  private static final String ACTION_NAME = "createGoodsReceipt";
  private static final String SPEC_PURCHASE_ORDER = "purchase-order";

  @Override
  public NeoResponse handle(NeoContext context) {
    if (!NeoEndpointType.ACTION.equals(context.getEndpointType())) {
      return null;
    }
    if (!ACTION_NAME.equals(context.getFieldName()) || !"POST".equals(context.getHttpMethod())) {
      return null;
    }
    if (!SPEC_PURCHASE_ORDER.equals(context.getSpecName())) {
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
              "Purchase order not found: " + recordId);
        }

        ShipmentInOut receipt = createReceiptHeader(order);
        OBDal.getInstance().save(receipt);
        createReceiptLines(receipt, order);
        OBDal.getInstance().flush();
        ensureDocumentNo(receipt);

        JSONObject data = new JSONObject();
        data.put("id", receipt.getId());
        data.put("documentNo", receipt.getDocumentNo());

        JSONObject responseData = new JSONObject();
        responseData.put("data", data);

        JSONObject wrapper = new JSONObject();
        wrapper.put("response", responseData);

        return NeoResponse.created(wrapper);
      } finally {
        OBContext.restorePreviousMode();
      }
    } catch (OBException e) {
      log.warn("Error creating goods receipt from order {}: {}", recordId, e.getMessage());
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, e.getMessage());
    } catch (Exception e) {
      log.error("Error creating goods receipt from order {}: {}", recordId, e.getMessage(), e);
      return NeoResponse.error(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
          "An internal error occurred while creating the goods receipt");
    }
  }

  /**
   * Defensive fallback when DocumentNoHandlerLegacy fails to resolve the
   * sequence. Goods Receipt document types are often configured with
   * {@code IsDocNoControlled='N'} and no {@code DocNoSequence_ID}, so the
   * listener returns an empty string. Resolves the next number directly from
   * the table-level {@code DocumentNo_M_InOut} sequence using the receipt's
   * own client (not {@code vars.getClient()}, which can differ under
   * {@code OBContext.setAdminMode(true)} + NEO Headless), and reuses the OBDal
   * JDBC connection so the sequence advance stays in the same transaction.
   */
  protected void ensureDocumentNo(ShipmentInOut receipt) {
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
      log.warn(
          "Could not generate documentNo for goods receipt {} (docType={}, client={}). "
              + "Configure DocNoSequence_ID on the document type or activate "
              + "AD_Sequence 'DocumentNo_M_InOut' for the client.",
          receipt.getId(),
          receipt.getDocumentType() != null ? receipt.getDocumentType().getName() : "null",
          receipt.getClient().getId());
      return;
    }
    log.info("Generated documentNo='{}' for goods receipt {}", docNo, receipt.getId());
    receipt.setDocumentNo(docNo);
    OBDal.getInstance().save(receipt);
    OBDal.getInstance().flush();
  }

  private ShipmentInOut createReceiptHeader(Order order) {
    DocumentType docType = findReceiptDocType(order);
    if (docType == null) {
      throw new OBException(
          "No Goods Receipt document type found (docBaseType=MMR, isSOTrx=false)");
    }

    return NeoCommercialDocumentFactory.createShipmentReceiptHeader(
        order,
        docType,
        false,
        "V+");
  }

  private void createReceiptLines(ShipmentInOut receipt, Order order) {
    Locator defaultLocator = findDefaultLocator(order);
    if (defaultLocator == null) {
      String warehouseName = order.getWarehouse() != null
          ? order.getWarehouse().getName() : "unknown";
      throw new OBException("No storage locator found for warehouse: " + warehouseName);
    }

    long lineNo = 10;
    int addedLines = 0;
    for (OrderLine orderLine : order.getOrderLineList()) {
      java.math.BigDecimal orderedQty = orderLine.getOrderedQuantity();
      java.math.BigDecimal deliveredQty = orderLine.getDeliveredQuantity() != null
          ? orderLine.getDeliveredQuantity() : java.math.BigDecimal.ZERO;
      java.math.BigDecimal pendingQty = orderedQty != null
          ? orderedQty.subtract(deliveredQty) : java.math.BigDecimal.ZERO;
      if (!orderLine.isActive() || orderLine.getProduct() == null
          || orderLine.getUOM() == null
          || pendingQty.compareTo(java.math.BigDecimal.ZERO) <= 0) {
        continue;
      }

      ShipmentInOutLine line = OBProvider.getInstance().get(ShipmentInOutLine.class);
      line.setClient(orderLine.getClient());
      line.setOrganization(orderLine.getOrganization());
      line.setShipmentReceipt(receipt);
      line.setLineNo(lineNo);
      line.setProduct(orderLine.getProduct());
      line.setUOM(orderLine.getUOM());
      line.setStorageBin(defaultLocator);
      line.setMovementQuantity(pendingQty);
      line.setSalesOrderLine(orderLine);
      line.setDescription(orderLine.getDescription());

      OBDal.getInstance().save(line);
      lineNo += 10;
      addedLines++;
    }
    if (addedLines == 0) {
      throw new OBException("No pending lines to receive in this purchase order");
    }
  }

  private DocumentType findReceiptDocType(Order order) {
    List<DocumentType> results = OBDal.getInstance().createCriteria(DocumentType.class)
        .add(Restrictions.eq(DocumentType.PROPERTY_CLIENT, order.getClient()))
        .add(Restrictions.eq(DocumentType.PROPERTY_DOCUMENTCATEGORY, "MMR"))
        .add(Restrictions.eq(DocumentType.PROPERTY_SALESTRANSACTION, false))
        .add(Restrictions.eq(DocumentType.PROPERTY_ACTIVE, true))
        .addOrderBy(DocumentType.PROPERTY_DEFAULT, false)
        .setMaxResults(1)
        .list();
    return results.isEmpty() ? null : results.get(0);
  }

  private Locator findDefaultLocator(Order order) {
    if (order.getWarehouse() == null) {
      return null;
    }
    List<Locator> defaults = OBDal.getInstance().createCriteria(Locator.class)
        .add(Restrictions.eq(Locator.PROPERTY_WAREHOUSE, order.getWarehouse()))
        .add(Restrictions.eq(Locator.PROPERTY_DEFAULT, true))
        .add(Restrictions.eq(Locator.PROPERTY_ACTIVE, true))
        .setMaxResults(1)
        .list();
    if (!defaults.isEmpty()) {
      return defaults.get(0);
    }
    List<Locator> any = OBDal.getInstance().createCriteria(Locator.class)
        .add(Restrictions.eq(Locator.PROPERTY_WAREHOUSE, order.getWarehouse()))
        .add(Restrictions.eq(Locator.PROPERTY_ACTIVE, true))
        .setMaxResults(1)
        .list();
    return any.isEmpty() ? null : any.get(0);
  }
}
