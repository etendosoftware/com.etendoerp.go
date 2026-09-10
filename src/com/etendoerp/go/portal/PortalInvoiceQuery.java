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

import java.util.List;

import org.openbravo.dal.service.OBDal;
import org.openbravo.dal.service.OBQuery;
import org.openbravo.model.common.invoice.Invoice;

/**
 * The only way the portal reads invoices.
 *
 * <p><b>Every query in this class carries the same three filters — tenant, Business Partner, and
 * {@code docstatus = 'CO'} — and takes all three from a {@link PortalSession}.</b> That is security
 * invariant 3 of the plan, and it is centralised here so there is exactly one place to read to
 * confirm it, and no endpoint can compose its own invoice query.
 *
 * <p>{@code issotrx = 'Y'} is filtered too, though the plan does not name it: without it a purchase
 * invoice issued <em>to</em> the same Business Partner (a customer who is also a supplier — common)
 * would appear in their portal. That is the tenant's own cost data and was never in scope.
 *
 * <p>Drafts are excluded rather than shown as pending. A draft invoice is not yet a claim on the
 * customer, and showing one would tell them about a document the tenant has not issued.
 */
final class PortalInvoiceQuery {

  /** Completed. The only document status the portal ever shows. */
  private static final String DOCSTATUS_COMPLETED = "CO";

  private static final String PARAM_CLIENT_ID = "clientId";
  private static final String PARAM_BPARTNER_ID = "bpartnerId";
  private static final String PARAM_DOCSTATUS = "docStatus";
  private static final String PARAM_INVOICE_ID = "invoiceId";

  /** The three-filter scope every portal read shares, as an HQL fragment. */
  private static final String SCOPE_CLAUSE =
      " i.client.id = :" + PARAM_CLIENT_ID
      + " and i.businessPartner.id = :" + PARAM_BPARTNER_ID
      + " and i.documentStatus = :" + PARAM_DOCSTATUS
      + " and i.salesTransaction = true"
      + " and i.active = true";

  private PortalInvoiceQuery() {
  }

  /**
   * Lists the Business Partner's completed sales invoices, newest first.
   *
   * @param session the validated token scope
   * @return the invoices, never {@code null}
   */
  static List<Invoice> list(PortalSession session) {
    OBQuery<Invoice> query = OBDal.getInstance().createQuery(Invoice.class,
        "as i where" + SCOPE_CLAUSE + " order by i.invoiceDate desc, i.documentNo desc");
    applyScope(query, session);
    return query.list();
  }

  /**
   * Resolves one invoice <em>within the session's scope</em>.
   *
   * <p>An id outside that scope resolves to {@code null}, which the caller turns into a {@code 404}
   * — never a {@code 403}, which would confirm the record exists (plan §6).
   *
   * @param session the validated token scope
   * @param invoiceId the requested invoice
   * @return the invoice, or {@code null} when it is not this Business Partner's completed sales
   *     invoice
   */
  static Invoice findInScope(PortalSession session, String invoiceId) {
    OBQuery<Invoice> query = OBDal.getInstance().createQuery(Invoice.class,
        "as i where" + SCOPE_CLAUSE + " and i.id = :" + PARAM_INVOICE_ID);
    applyScope(query, session);
    query.setNamedParameter(PARAM_INVOICE_ID, invoiceId);
    query.setMaxResult(1);
    return query.uniqueResult();
  }

  /**
   * Applies the scope filters and switches off DAL's own client/organization filtering.
   *
   * <p>The latter is required, not a shortcut: a portal request has no session, so DAL would filter
   * against the SYSTEM bootstrap context and match nothing (see {@link PortalAccessDal}). The scope
   * that replaces it is stated explicitly above and comes only from the validated token row.
   */
  private static void applyScope(OBQuery<Invoice> query, PortalSession session) {
    query.setNamedParameter(PARAM_CLIENT_ID, session.getClientId());
    query.setNamedParameter(PARAM_BPARTNER_ID, session.getBusinessPartnerId());
    query.setNamedParameter(PARAM_DOCSTATUS, DOCSTATUS_COMPLETED);
    query.setFilterOnReadableClients(false);
    query.setFilterOnReadableOrganization(false);
  }
}
