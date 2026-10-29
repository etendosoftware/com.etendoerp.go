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

import org.hibernate.criterion.Restrictions;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.invoice.Invoice;
import org.openbravo.model.common.invoice.ReversedInvoice;

/**
 * The {@code C_Invoice_Reverse} link between a rectificative invoice and an invoice it rectifies
 * (the "Rectificaciones" tab), written by the origin invoice import
 * ({@link ImportOriginInvoiceLinesHandler}, ETP-5876) with the same dedupe as the header's
 * {@code originInvoices} write ({@code AbstractInvoiceHeaderHandler#persistOriginInvoice}, ETP-4919),
 * which is left untouched.
 */
final class OriginInvoiceLinks {

  private OriginInvoiceLinks() {
  }

  /**
   * Links {@code invoice} to {@code origin} unless the pair is already linked: importing from the
   * same origin twice never duplicates the row, and importing from a new origin never removes the
   * link to a previous one.
   *
   * @return {@code true} when a row was created
   */
  static boolean linkIfMissing(Invoice invoice, Invoice origin) {
    List<ReversedInvoice> existing = OBDal.getInstance()
        .createCriteria(ReversedInvoice.class)
        .add(Restrictions.eq(ReversedInvoice.PROPERTY_INVOICE, invoice))
        .add(Restrictions.eq(ReversedInvoice.PROPERTY_REVERSEDINVOICE, origin))
        .list();
    if (!existing.isEmpty()) {
      return false;
    }
    ReversedInvoice link = OBProvider.getInstance().get(ReversedInvoice.class);
    link.setClient(invoice.getClient());
    link.setOrganization(invoice.getOrganization());
    link.setInvoice(invoice);
    link.setReversedInvoice(origin);
    OBDal.getInstance().save(link);
    return true;
  }
}
