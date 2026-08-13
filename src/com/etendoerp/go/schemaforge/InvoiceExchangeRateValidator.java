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
import org.openbravo.erpCommon.utility.OBMessageUtils;
import org.openbravo.financial.FinancialUtils;
import org.openbravo.model.common.currency.ConversionRateDoc;
import org.openbravo.model.common.currency.Currency;
import org.openbravo.model.common.invoice.Invoice;

/**
 * Checks that an exchange rate exists to convert an invoice's currency to the org's functional
 * currency, used to block completion when none is available (ETP-4030). Invoked from
 * {@link InvoiceCompletionRateHook} to cover the classic backoffice UI completion path.
 *
 * <p>The NEO/headless completion path no longer calls this validator: ETP-4027 moves the
 * exchange-rate check to the frontend, triggered when the user changes the document currency.
 *
 * <p>Precedence mirrors core: the document-level rate ({@link ConversionRateDoc}, manual or synced)
 * wins over the general {@code C_Conversion_Rate} spot table.
 */
public final class InvoiceExchangeRateValidator {

  private InvoiceExchangeRateValidator() {
    // utility class — no instances
  }

  /**
   * Checks whether the given invoice can be completed with respect to currency conversion. Returns
   * a user-facing error message when completion must be blocked — the document currency differs
   * from the organization currency and no exchange rate is available — or {@code null} when it may
   * proceed.
   *
   * @param invoice the invoice about to be completed
   * @return a resolved, user-facing error message when completion must be blocked (currencies
   *         differ and no rate is available), or {@code null} when completion may proceed (same
   *         currency, no functional currency configured, or a rate exists).
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
      if (hasDocumentRate(invoice, from, to) || hasGeneralRate(invoice, from, to)) {
        return null;
      }
      return OBMessageUtils.messageBD("SMFCR_NoRateOnComplete") + " " + from.getISOCode() + " → "
          + (to != null ? to.getISOCode() : baseCurrencyId);
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

  /** True if a general {@code C_Conversion_Rate} exists for the invoice date (core lookup). */
  private static boolean hasGeneralRate(Invoice invoice, Currency from, Currency to) {
    if (to == null || invoice.getInvoiceDate() == null) {
      return false;
    }
    return FinancialUtils.getConversionRate(invoice.getInvoiceDate(), from, to,
        invoice.getOrganization(), invoice.getClient()) != null;
  }
}
