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
import java.util.Date;
import java.util.List;

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
import org.openbravo.base.weld.WeldUtils;
import org.openbravo.common.actionhandler.createlinesfromprocess.CreateInvoiceLinesFromProcess;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.erpCommon.utility.Utility;
import org.openbravo.model.common.enterprise.DocumentType;
import org.openbravo.model.common.invoice.Invoice;
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
        // Refresh to pick up trigger-generated documentNo and totals set by CreateInvoiceLinesFromProcess.
        OBDal.getInstance().getSession().refresh(invoice);
        ensureDocumentNo(invoice);

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

  /**
   * Defensive fallback when DocumentNoHandlerLegacy fails to resolve the
   * sequence. The stock {@code AP Invoice} document type ships with
   * {@code IsDocNoControlled='N'} and no {@code DocNoSequence_ID}, so the
   * listener returns an empty string. Resolves the next number directly from
   * the table-level {@code DocumentNo_C_Invoice} sequence using the invoice's
   * own client (not {@code vars.getClient()}, which can differ under
   * {@code OBContext.setAdminMode(true)} + NEO Headless), and reuses the OBDal
   * JDBC connection so the sequence advance stays in the same transaction.
   */
  protected void ensureDocumentNo(Invoice invoice) {
    String current = invoice.getDocumentNo();
    if (StringUtils.isNotBlank(current) && !current.startsWith("<")) {
      return;
    }
    String docNo = Utility.getDocumentNoConnection(
        OBDal.getInstance().getConnection(false),
        new DalConnectionProvider(false),
        invoice.getClient().getId(),
        "C_Invoice",
        true);
    if (StringUtils.isBlank(docNo)) {
      log.warn(
          "Could not generate documentNo for purchase invoice {} (docType={}, client={}). "
              + "Configure DocNoSequence_ID on the document type or activate "
              + "AD_Sequence 'DocumentNo_C_Invoice' for the client.",
          invoice.getId(),
          invoice.getDocumentType() != null ? invoice.getDocumentType().getName() : "null",
          invoice.getClient().getId());
      return;
    }
    log.info("Generated documentNo='{}' for purchase invoice {}", docNo, invoice.getId());
    invoice.setDocumentNo(docNo);
    OBDal.getInstance().save(invoice);
    OBDal.getInstance().flush();
  }

  protected Invoice createFromOrder(String orderId) {
    Order order = OBDal.getInstance().get(Order.class, orderId);
    if (order == null) {
      throw new OBException("Purchase order not found: " + orderId);
    }

    JSONArray selectedLines = buildSelectedLines(order);
    if (selectedLines.length() == 0) {
      throw new OBException("No pending lines to invoice in this purchase order");
    }

    DocumentType invoiceDocType = resolveAPInvoiceDocType(order);

    Invoice invoice = NeoCommercialDocumentFactory.createInvoiceFromOrderHeader(
        order,
        invoiceDocType,
        false);

    OBDal.getInstance().save(invoice);
    OBDal.getInstance().flush();

    // Delegate line creation to native Etendo process — handles taxes, gross prices, IVA-included, etc.
    CreateInvoiceLinesFromProcess proc =
        WeldUtils.getInstanceFromStaticBeanManager(CreateInvoiceLinesFromProcess.class);
    proc.createInvoiceLinesFromDocumentLines(selectedLines, invoice, OrderLine.class);

    OBDal.getInstance().flush();

    InvoiceLineLinker.linkInvoiceLinesToExistingInouts(invoice.getId());

    return invoice;
  }

  protected JSONArray buildSelectedLines(Order order) {
    JSONArray selectedLines = new JSONArray();
    for (OrderLine ol : order.getOrderLineList()) {
      BigDecimal pending = getPendingQuantity(ol);
      if (pending == null) continue;
      try {
        JSONObject entry = new JSONObject();
        entry.put("id", ol.getId());
        entry.put("orderedQuantity", pending.toPlainString());
        selectedLines.put(entry);
      } catch (Exception e) {
        log.warn("Failed to add line {}: {}", ol.getId(), e.getMessage());
      }
    }
    return selectedLines;
  }

  private BigDecimal getPendingQuantity(OrderLine ol) {
    if (!ol.isActive() || ol.getProduct() == null) return null;
    BigDecimal ordered  = ol.getOrderedQuantity()  != null ? ol.getOrderedQuantity()  : BigDecimal.ZERO;
    BigDecimal invoiced = ol.getInvoicedQuantity() != null ? ol.getInvoicedQuantity() : BigDecimal.ZERO;
    BigDecimal pending  = ordered.subtract(invoiced);
    return pending.compareTo(BigDecimal.ZERO) > 0 ? pending : null;
  }

  // Discard the placeholder doc type ('0' = "** New **", docBasetype="---")
  // which is stored when no invoice doc type is linked to the PO doc type.
  protected DocumentType resolveAPInvoiceDocType(Order order) {
    DocumentType orderDocType = order.getTransactionDocument();
    DocumentType invoiceDocType = orderDocType != null
        ? orderDocType.getDocumentTypeForInvoice()
        : null;
    if (invoiceDocType != null && !"API".equals(invoiceDocType.getDocumentCategory())) {
      invoiceDocType = null;
    }
    if (invoiceDocType == null) {
      invoiceDocType = findAPInvoiceDocType(order.getClient().getId());
    }
    if (invoiceDocType == null) {
      throw new OBException("No AP Invoice document type found");
    }
    return invoiceDocType;
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
