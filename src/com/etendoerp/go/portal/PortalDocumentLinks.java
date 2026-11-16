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

package com.etendoerp.go.portal;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.hibernate.query.Query;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.dal.service.OBDal;

/**
 * The documents related to one portal document, "like the app" (ETP-6209, plan §B.6).
 *
 * <p>The links are the same data links the app's related-documents chips follow
 * ({@code salesRelatedDocs.js}): the quotation of an order ({@code C_Order.Quotation_ID}), the
 * order a document was made from (header {@code C_Order_ID} and line {@code C_OrderLine_ID}), the
 * shipment an invoice line delivers ({@code M_InOutLine_ID}), and the official
 * {@code C_Invoice_Reverse} link between a credit note and the invoice it rectifies, in both
 * directions. Nothing is deduced beyond those links.
 *
 * <p><b>Read here with plain HQL, not through the NEO enrichers</b>: the portal stays isolated from
 * NEO (see {@link PortalServlet}). <b>Every candidate is then re-read through
 * {@link PortalDocumentQuery#findAllInScope}</b>, so a link to another Business Partner's document
 * (a rectificativa across partners, which Verifactu organizations allow) or to a hidden status is
 * dropped silently — the customer never learns it exists.
 */
final class PortalDocumentLinks {

  private static final String P_ID = "id";

  private PortalDocumentLinks() {
  }

  /**
   * @param session the validated scope
   * @param kind the document's kind
   * @param documentId the document
   * @return the related documents in scope, grouped by kind in a stable order, never the document
   *     itself
   */
  static List<BaseOBObject> related(PortalSession session, PortalDocumentKind kind,
      String documentId) {
    Map<PortalDocumentKind, Set<String>> candidates = new EnumMap<>(PortalDocumentKind.class);
    switch (kind) {
      case QUOTATION:
        add(candidates, PortalDocumentKind.ORDER,
            ids("select o.id from Order o where o.quotation.id = :id", documentId));
        add(candidates, PortalDocumentKind.INVOICE,
            ids("select i.id from Invoice i where i.salesOrder.id = :id", documentId));
        add(candidates, PortalDocumentKind.SHIPMENT,
            ids("select s.id from MaterialMgmtShipmentInOut s where s.salesOrder.id = :id",
                documentId));
        break;
      case ORDER:
        add(candidates, PortalDocumentKind.QUOTATION,
            ids("select o.quotation.id from Order o where o.id = :id", documentId));
        add(candidates, PortalDocumentKind.SHIPMENT,
            ids("select s.id from MaterialMgmtShipmentInOut s where s.salesOrder.id = :id",
                documentId));
        add(candidates, PortalDocumentKind.SHIPMENT,
            ids("select l.shipmentReceipt.id from MaterialMgmtShipmentInOutLine l"
                + " where l.salesOrderLine.salesOrder.id = :id", documentId));
        add(candidates, PortalDocumentKind.INVOICE,
            ids("select i.id from Invoice i where i.salesOrder.id = :id", documentId));
        add(candidates, PortalDocumentKind.INVOICE,
            ids("select l.invoice.id from InvoiceLine l where l.salesOrderLine.salesOrder.id = :id",
                documentId));
        break;
      case SHIPMENT:
        add(candidates, PortalDocumentKind.ORDER,
            ids("select s.salesOrder.id from MaterialMgmtShipmentInOut s where s.id = :id",
                documentId));
        add(candidates, PortalDocumentKind.ORDER,
            ids("select l.salesOrderLine.salesOrder.id from MaterialMgmtShipmentInOutLine l"
                + " where l.shipmentReceipt.id = :id", documentId));
        add(candidates, PortalDocumentKind.INVOICE,
            ids("select l.invoice.id from InvoiceLine l"
                + " where l.goodsShipmentLine.shipmentReceipt.id = :id", documentId));
        break;
      default:
        add(candidates, PortalDocumentKind.ORDER,
            ids("select i.salesOrder.id from Invoice i where i.id = :id", documentId));
        add(candidates, PortalDocumentKind.ORDER,
            ids("select l.salesOrderLine.salesOrder.id from InvoiceLine l where l.invoice.id = :id",
                documentId));
        add(candidates, PortalDocumentKind.SHIPMENT,
            ids("select l.goodsShipmentLine.shipmentReceipt.id from InvoiceLine l"
                + " where l.invoice.id = :id", documentId));
        add(candidates, PortalDocumentKind.INVOICE,
            ids("select r.reversedInvoice.id from ReversedInvoices r where r.invoice.id = :id",
                documentId));
        add(candidates, PortalDocumentKind.INVOICE,
            ids("select r.invoice.id from ReversedInvoices r where r.reversedInvoice.id = :id",
                documentId));
        break;
    }
    // A header C_Order_ID may point at a quotation (a direct invoice from a quotation): the order
    // candidates are also tried as quotations, and whichever scope accepts them wins.
    if (candidates.containsKey(PortalDocumentKind.ORDER)) {
      add(candidates, PortalDocumentKind.QUOTATION, candidates.get(PortalDocumentKind.ORDER));
    }
    List<BaseOBObject> related = new ArrayList<>();
    for (Map.Entry<PortalDocumentKind, Set<String>> entry : candidates.entrySet()) {
      Set<String> ids = entry.getValue();
      ids.remove(documentId);
      related.addAll(PortalDocumentQuery.findAllInScope(session, entry.getKey(), ids));
    }
    return related;
  }

  private static void add(Map<PortalDocumentKind, Set<String>> candidates,
      PortalDocumentKind kind, Collection<String> ids) {
    if (ids.isEmpty()) {
      return;
    }
    candidates.computeIfAbsent(kind, k -> new LinkedHashSet<>()).addAll(ids);
  }

  private static List<String> ids(String hql, String documentId) {
    Query<String> query = OBDal.getInstance().getSession().createQuery(hql, String.class);
    query.setParameter(P_ID, documentId);
    List<String> result = new ArrayList<>();
    for (String id : query.list()) {
      if (id != null) {
        result.add(id);
      }
    }
    return result;
  }
}
