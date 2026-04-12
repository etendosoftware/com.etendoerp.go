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
import org.openbravo.model.common.invoice.Invoice;
import org.openbravo.model.common.invoice.InvoiceLine;
import org.openbravo.model.common.order.Order;
import org.openbravo.model.common.order.OrderLine;
import org.openbravo.service.db.DalConnectionProvider;

/**
 * NeoHandler that creates a draft Purchase Invoice from a Purchase Order.
 * Invoked as an ACTION endpoint via:
 *   POST /sws/neo/purchase-order/header/{recordId}/action/createPurchaseInvoice
 */
@Named("createPurchaseInvoiceHandler")
public class CreatePurchaseInvoiceHandler implements NeoHandler {

  private static final Logger log = LogManager.getLogger(CreatePurchaseInvoiceHandler.class);
  private static final String ACTION_NAME = "createPurchaseInvoice";
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
        Invoice invoice = createFromOrder(recordId);
        OBDal.getInstance().flush();
        // Refresh to pick up trigger-generated documentNo.
        OBDal.getInstance().getSession().refresh(invoice);

        JSONObject data = new JSONObject();
        data.put("id", invoice.getId());
        data.put("documentNo", invoice.getDocumentNo());

        JSONObject responseData = new JSONObject();
        responseData.put("data", data);

        JSONObject wrapper = new JSONObject();
        wrapper.put("response", responseData);

        return NeoResponse.created(wrapper);
      } finally {
        OBContext.restorePreviousMode();
      }
    } catch (OBException e) {
      log.warn("Error creating purchase invoice from order {}: {}", recordId, e.getMessage());
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, e.getMessage());
    } catch (Exception e) {
      log.error("Error creating purchase invoice from order {}: {}", recordId, e.getMessage(), e);
      return NeoResponse.error(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
          "An internal error occurred while creating the purchase invoice");
    }
  }

  private Invoice createFromOrder(String orderId) {
    Order order = OBDal.getInstance().get(Order.class, orderId);
    if (order == null) {
      throw new OBException("Purchase order not found: " + orderId);
    }

    // Pre-validate: check pending lines exist before creating the invoice header
    // to prevent zombie invoice headers if addOrderLinesToInvoice throws.
    boolean hasPending = false;
    for (OrderLine ol : order.getOrderLineList()) {
      if (!ol.isActive() || ol.getProduct() == null) {
        continue;
      }
      BigDecimal ordered  = ol.getOrderedQuantity()  != null ? ol.getOrderedQuantity()  : BigDecimal.ZERO;
      BigDecimal invoiced = ol.getInvoicedQuantity() != null ? ol.getInvoicedQuantity() : BigDecimal.ZERO;
      if (ordered.subtract(invoiced).compareTo(BigDecimal.ZERO) > 0) {
        hasPending = true;
        break;
      }
    }
    if (!hasPending) {
      throw new OBException("No pending lines to invoice in this purchase order");
    }

    DocumentType orderDocType = order.getTransactionDocument();
    DocumentType invoiceDocType = orderDocType != null
        ? orderDocType.getDocumentTypeForInvoice()
        : null;
    // Discard the placeholder doc type ('0' = "** New **", docBasetype="---")
    // which is stored when no invoice doc type is linked to the PO doc type.
    if (invoiceDocType != null && !"API".equals(invoiceDocType.getDocumentCategory())) {
      invoiceDocType = null;
    }
    if (invoiceDocType == null) {
      invoiceDocType = findAPInvoiceDocType(order.getClient().getId());
    }
    if (invoiceDocType == null) {
      throw new OBException("No AP Invoice document type found");
    }

    Invoice invoice = OBProvider.getInstance().get(Invoice.class);
    invoice.setClient(order.getClient());
    invoice.setOrganization(order.getOrganization());
    invoice.setDocumentType(invoiceDocType);
    invoice.setTransactionDocument(invoiceDocType);
    invoice.setDocumentStatus("DR");
    invoice.setDocumentAction("CO");
    invoice.setSalesTransaction(false);
    invoice.setInvoiceDate(new Date());
    invoice.setAccountingDate(new Date());
    invoice.setBusinessPartner(order.getBusinessPartner());
    invoice.setPartnerAddress(order.getPartnerAddress());
    invoice.setPriceList(order.getPriceList());
    invoice.setCurrency(order.getCurrency());
    invoice.setPaymentTerms(order.getPaymentTerms());
    invoice.setPaymentMethod(order.getPaymentMethod());
    invoice.setSummedLineAmount(BigDecimal.ZERO);
    invoice.setGrandTotalAmount(BigDecimal.ZERO);
    invoice.setWithholdingamount(BigDecimal.ZERO);
    invoice.setSalesOrder(order);

    String documentNo = Utility.getDocumentNo(
        new DalConnectionProvider(false), order.getClient().getId(), "C_Invoice", true);
    invoice.setDocumentNo(StringUtils.isNotBlank(documentNo) ? documentNo : "*");

    OBDal.getInstance().save(invoice);
    OBDal.getInstance().flush();

    addOrderLinesToInvoice(invoice, order);

    return invoice;
  }

  private void addOrderLinesToInvoice(Invoice invoice, Order order) {
    long lineNo = 10;
    int addedLines = 0;
    for (OrderLine ol : order.getOrderLineList()) {
      if (!ol.isActive() || ol.getProduct() == null) {
        continue;
      }
      BigDecimal ordered = ol.getOrderedQuantity() != null ? ol.getOrderedQuantity() : BigDecimal.ZERO;
      BigDecimal invoiced = ol.getInvoicedQuantity() != null ? ol.getInvoicedQuantity() : BigDecimal.ZERO;
      BigDecimal pending = ordered.subtract(invoiced);
      if (pending.compareTo(BigDecimal.ZERO) <= 0) {
        continue;
      }

      InvoiceLine il = OBProvider.getInstance().get(InvoiceLine.class);
      il.setOrganization(ol.getOrganization());
      il.setInvoice(invoice);
      il.setLineNo(lineNo);
      il.setProduct(ol.getProduct());
      il.setInvoicedQuantity(pending);
      il.setUOM(ol.getUOM());
      il.setUnitPrice(ol.getUnitPrice());
      il.setListPrice(ol.getListPrice());
      il.setPriceLimit(ol.getPriceLimit());
      int precision = invoice.getCurrency().getStandardPrecision().intValue();
      il.setLineNetAmount(
          pending.multiply(ol.getUnitPrice()).setScale(precision, RoundingMode.HALF_UP));
      il.setTax(ol.getTax());
      il.setSalesOrderLine(ol);
      OBDal.getInstance().save(il);
      lineNo += 10;
      addedLines++;
    }
    if (addedLines == 0) {
      throw new OBException("No pending lines to invoice in this purchase order");
    }
  }

  private DocumentType findAPInvoiceDocType(String clientId) {
    org.openbravo.model.ad.system.Client client =
        OBDal.getInstance().get(org.openbravo.model.ad.system.Client.class, clientId);
    List<DocumentType> results = OBDal.getInstance().createCriteria(DocumentType.class)
        .add(Restrictions.eq(DocumentType.PROPERTY_CLIENT, client))
        .add(Restrictions.eq(DocumentType.PROPERTY_DOCUMENTCATEGORY, "API"))
        .add(Restrictions.eq(DocumentType.PROPERTY_SALESTRANSACTION, false))
        .add(Restrictions.eq(DocumentType.PROPERTY_ACTIVE, true))
        .addOrderBy(DocumentType.PROPERTY_DEFAULT, false)
        .setMaxResults(1)
        .list();
    return results.isEmpty() ? null : results.get(0);
  }
}
