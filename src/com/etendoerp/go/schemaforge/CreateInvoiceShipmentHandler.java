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
import org.openbravo.model.common.enterprise.DocumentType;
import org.openbravo.model.common.enterprise.Locator;
import org.openbravo.model.common.enterprise.Warehouse;
import org.openbravo.model.common.invoice.Invoice;
import org.openbravo.model.common.invoice.InvoiceLine;
import org.openbravo.model.materialmgmt.transaction.ShipmentInOut;
import org.openbravo.model.materialmgmt.transaction.ShipmentInOutLine;

/**
 * NeoHandler that creates a Goods Shipment (ShipmentInOut) in Draft status from a Sales Invoice.
 *
 * <p>Invoked as an ACTION endpoint via:
 *   POST /sws/neo/sales-invoice/header/{recordId}/action/createShipment
 *
 * <p>Only applies to standard invoices (FAC subtype — docBaseType ARI). The frontend guards this
 * by only offering the "¿Gestionar envío?" dialog for non-credit-memo, non-return invoices.
 *
 * <p>Warehouse resolution order:
 * <ol>
 *   <li>Invoice's linked salesOrder warehouse (if the invoice was created from an order)</li>
 *   <li>First active invoice line's salesOrderLine.salesOrder warehouse</li>
 *   <li>Default locator's warehouse for the invoice's organization</li>
 * </ol>
 */
@Named("createInvoiceShipmentHandler")
public class CreateInvoiceShipmentHandler implements NeoHandler {

  private static final Logger log = LogManager.getLogger(CreateInvoiceShipmentHandler.class);
  private static final String ACTION_NAME = "createShipment";
  private static final String SPEC_SALES_INVOICE = "sales-invoice";

  @Override
  public NeoResponse handle(NeoContext context) {
    if (!NeoEndpointType.ACTION.equals(context.getEndpointType())) {
      return null;
    }
    if (!ACTION_NAME.equals(context.getFieldName()) || !"POST".equals(context.getHttpMethod())) {
      return null;
    }
    if (!SPEC_SALES_INVOICE.equals(context.getSpecName())) {
      return null;
    }

    String recordId = context.getRecordId();
    if (StringUtils.isBlank(recordId)) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, "Record ID is required");
    }

    try {
      OBContext.setAdminMode(true);
      try {
        Invoice invoice = OBDal.getInstance().get(Invoice.class, recordId);
        if (invoice == null) {
          return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, "Invoice not found: " + recordId);
        }

        Warehouse warehouse = resolveWarehouse(invoice);
        if (warehouse == null) {
          return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST,
              "No se pudo determinar el almacén para crear el albarán");
        }

        DocumentType docType = findShipmentDocType(invoice);
        if (docType == null) {
          return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST,
              "No Goods Shipment document type found (docBaseType=MMS, isSOTrx=true)");
        }

        ShipmentInOut shipment = NeoCommercialDocumentFactory.createShipmentFromInvoiceHeader(
            invoice, docType, true, "C-", warehouse);
        OBDal.getInstance().save(shipment);

        Locator locator = resolveDefaultLocator(warehouse);
        if (locator == null) {
          throw new OBException("No storage locator found for warehouse: " + warehouse.getName());
        }

        createShipmentLines(shipment, invoice, locator);
        OBDal.getInstance().flush();

        JSONObject data = new JSONObject();
        data.put("id", shipment.getId());
        data.put("documentNo", shipment.getDocumentNo());

        JSONObject responseData = new JSONObject();
        responseData.put("data", data);

        JSONObject wrapper = new JSONObject();
        wrapper.put("response", responseData);

        return NeoResponse.created(wrapper);
      } finally {
        OBContext.restorePreviousMode();
      }
    } catch (OBException e) {
      log.warn("Error creating shipment from invoice {}: {}", recordId, e.getMessage());
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, e.getMessage());
    } catch (Exception e) {
      log.error("Error creating shipment from invoice {}: {}", recordId, e.getMessage(), e);
      return NeoResponse.error(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
          "An internal error occurred while creating the shipment");
    }
  }

  private Warehouse resolveWarehouse(Invoice invoice) {
    // 1. Invoice-level sales order
    if (invoice.getSalesOrder() != null && invoice.getSalesOrder().getWarehouse() != null) {
      return invoice.getSalesOrder().getWarehouse();
    }
    // 2. First invoice line's order line
    for (InvoiceLine line : invoice.getInvoiceLineList()) {
      if (line.getSalesOrderLine() != null
          && line.getSalesOrderLine().getSalesOrder() != null
          && line.getSalesOrderLine().getSalesOrder().getWarehouse() != null) {
        return line.getSalesOrderLine().getSalesOrder().getWarehouse();
      }
    }
    // 3. Default warehouse for the organization
    return findDefaultWarehouseForOrg(invoice);
  }

  private Warehouse findDefaultWarehouseForOrg(Invoice invoice) {
    List<Warehouse> results = OBDal.getInstance().createCriteria(Warehouse.class)
        .add(Restrictions.eq(Warehouse.PROPERTY_ORGANIZATION, invoice.getOrganization()))
        .add(Restrictions.eq(Warehouse.PROPERTY_ACTIVE, true))
        .setMaxResults(1)
        .list();
    return results.isEmpty() ? null : results.get(0);
  }

  private DocumentType findShipmentDocType(Invoice invoice) {
    return NeoCommercialDocumentFactory.findShipmentDocType(invoice.getClient());
  }

  private Locator resolveDefaultLocator(Warehouse warehouse) {
    return NeoCommercialDocumentFactory.findDefaultLocator(warehouse);
  }

  private void createShipmentLines(ShipmentInOut shipment, Invoice invoice, Locator locator) {
    long lineNo = 10;
    int added = 0;
    for (InvoiceLine invLine : invoice.getInvoiceLineList()) {
      BigDecimal qty = invLine.getInvoicedQuantity();
      if (invLine.getProduct() == null || invLine.getUOM() == null
          || qty == null || qty.compareTo(BigDecimal.ZERO) <= 0) {
        continue;
      }

      ShipmentInOutLine shipLine = OBProvider.getInstance().get(ShipmentInOutLine.class);
      shipLine.setClient(invoice.getClient());
      shipLine.setOrganization(invoice.getOrganization());
      shipLine.setShipmentReceipt(shipment);
      shipLine.setLineNo(lineNo);
      shipLine.setProduct(invLine.getProduct());
      shipLine.setUOM(invLine.getUOM());
      // ETP-4863: anchor to the shipment's OWN warehouse, not just whatever warehouse `locator`
      // was originally resolved for — same guarantee every other M_InOutLine write path in the
      // module makes. See InOutLineFromOrderFactory#createAndLinkLine for the reference pattern.
      shipLine.setStorageBin(
          NeoHandlerUtils.anchorLocatorToWarehouse(locator, shipment.getWarehouse(), log));
      shipLine.setMovementQuantity(qty);
      if (invLine.getSalesOrderLine() != null) {
        shipLine.setSalesOrderLine(invLine.getSalesOrderLine());
      }
      OBDal.getInstance().save(shipLine);
      lineNo += 10;
      added++;
    }
    if (added == 0) {
      throw new OBException("No hay líneas con producto en esta factura");
    }
  }
}
