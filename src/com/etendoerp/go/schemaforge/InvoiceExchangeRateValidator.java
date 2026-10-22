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

import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.hibernate.criterion.Restrictions;
import org.openbravo.erpCommon.utility.OBCurrencyUtils;
import org.openbravo.model.common.currency.ConversionRateDoc;
import org.openbravo.model.common.currency.Currency;
import org.openbravo.model.common.invoice.Invoice;

/**
 * Decides whether an invoice may be completed with respect to its exchange rate (ETP-4030, aligned
 * with the order-family rule by ETP-5840): when the invoice currency differs from its
 * organization's, the invoice must carry its OWN rate — the header {@code EM_ETGO_Currency_Rate},
 * or (for documents edited in the classic backoffice) a non-zero {@link ConversionRateDoc} row for
 * the exact pair. The general {@code C_Conversion_Rate} table no longer satisfies the check: the
 * rate is the document's, fixed on its accounting date when it was saved.
 *
 * <p>Invoked from {@link InvoiceCompletionRateHook}, the {@code ProcessInvoiceHook} that
 * {@code ProcessInvoiceUtil} runs — i.e. on EVERY completion path: the NEO/headless one
 * ({@code InvoiceCompletionService}, REST and MCP) as well as the classic backoffice. The NEO
 * header pre-hooks refuse earlier with the same text ({@link DocumentCurrencyRate}).
 */
public final class InvoiceExchangeRateValidator {

  private InvoiceExchangeRateValidator() {
    // utility class — no instances
  }

  /**
   * Checks whether the given invoice can be completed with respect to its exchange rate.
   *
   * @param invoice the invoice about to be completed
   * @return the translated refusal ({@code ETGO_ExchangeRateMissingOnComplete}) when the currencies
   *         differ and the invoice has no rate of its own, or {@code null} when completion may
   *         proceed (same currency, no functional currency configured, or a document rate exists)
   */
  public static String checkRateForCompletion(Invoice invoice) {
    if (invoice == null) {
      return null;
    }
    final Currency from = invoice.getCurrency();
    if (from == null || invoice.getOrganization() == null) {
      return null;
    }
    OBContext.setAdminMode(true);
    try {
      final String baseCurrencyId = OBCurrencyUtils.getOrgCurrency(invoice.getOrganization().getId());
      if (baseCurrencyId == null || from.getId().equals(baseCurrencyId)) {
        // No functional currency to convert to, or same currency: no rate needed.
        return null;
      }
      final Currency to = OBDal.getInstance().get(Currency.class, baseCurrencyId);
      if (hasDocumentRate(invoice, from, to)) {
        return null;
      }
      return DocumentCurrencyRate.missingRateOnCompletion(invoice,
          Invoice.PROPERTY_ETGOCURRENCYRATE);
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /**
   * True if the invoice has a document-level rate (manual or synced) for the exact {@code from -> to}
   * currency pair with a non-zero value. Restricting to the pair prevents a rate for an unrelated
   * currency from satisfying the check.
   */
  private static boolean hasDocumentRate(Invoice invoice, Currency from, Currency to) {
    if (from == null || to == null) {
      return false;
    }
    final OBCriteria<ConversionRateDoc> crit = OBDal.getInstance()
        .createCriteria(ConversionRateDoc.class);
    crit.add(Restrictions.eq(ConversionRateDoc.PROPERTY_INVOICE, invoice));
    crit.add(Restrictions.eq(ConversionRateDoc.PROPERTY_CURRENCY, from));
    crit.add(Restrictions.eq(ConversionRateDoc.PROPERTY_TOCURRENCY, to));
    crit.setFilterOnReadableClients(false);
    crit.setFilterOnReadableOrganization(false);
    for (ConversionRateDoc rateDoc : crit.list()) {
      final BigDecimal rate = rateDoc.getRate();
      if (rate != null && rate.signum() != 0) {
        return true;
      }
    }
    return false;
  }
}
