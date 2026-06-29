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
 * All portions are Copyright © 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.schemaforge;

import java.math.BigDecimal;

import org.apache.commons.lang3.StringUtils;
import org.hibernate.criterion.Restrictions;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.currency.Currency;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.financialmgmt.payment.FIN_FinancialAccount;
import org.openbravo.model.financialmgmt.payment.MatchingAlgorithm;

/**
 * Helper for creating {@link FIN_FinancialAccount} records programmatically, outside the generic
 * CRUD path. Used by the PSD2 bridge ({@link FinancialAccountPsd2Handler}) for the "connect first,
 * create after" flow (case 2): the account is materialized only once the user has authenticated
 * with their bank and chosen which Salt Edge account to link, so its name and currency are taken
 * from that Salt Edge account.
 *
 * <p>The mandatory-field set mirrors the proven programmatic creation in
 * {@code com.etendoerp.go.onboarding.steps.SeedReferenceDataStep} (balances and credit limit
 * default to zero), plus a default matching algorithm so reconciliation has one to work with.
 * No accounting account is set on purpose — the user configures it later from Edit account; the
 * account is usable for import and reconciliation without it.
 */
final class FinancialAccountSupport {

  private FinancialAccountSupport() {
  }

  /**
   * Creates and persists a {@link FIN_FinancialAccount} with the minimal mandatory fields.
   * The IBAN, country and provider are NOT set here — they are populated by
   * {@code SaltEdgeAccountLinkHelper.linkAccountToFinancialAccount} when the Salt Edge account is
   * linked right after creation.
   *
   * @param client
   *     the owning client (typically the session client)
   * @param org
   *     the owning organization (typically the session organization)
   * @param currency
   *     the account currency, resolved from the chosen Salt Edge account's currency code
   * @param name
   *     the account name, taken from the chosen Salt Edge account
   * @param type
   *     the account type ('B' Bank or 'CA' Card), chosen in the Etendo Go modal
   * @return the persisted financial account
   */
  static FIN_FinancialAccount createAccount(Client client, Organization org, Currency currency,
      String name, String type) {
    FIN_FinancialAccount account = OBProvider.getInstance().get(FIN_FinancialAccount.class);
    account.setNewOBObject(true);
    account.setClient(client);
    account.setOrganization(org);
    account.setName(name);
    account.setCurrency(currency);
    account.setType(type);
    account.setCurrentBalance(BigDecimal.ZERO);
    account.setInitialBalance(BigDecimal.ZERO);
    account.setCreditLimit(BigDecimal.ZERO);
    account.setDefault(false);
    MatchingAlgorithm algorithm = defaultMatchingAlgorithm();
    if (algorithm != null) {
      account.setMatchingAlgorithm(algorithm);
    }
    OBDal.getInstance().save(account);
    OBDal.getInstance().flush();
    return account;
  }

  /**
   * Resolves an active {@link Currency} by its ISO code (e.g. {@code EUR}), as returned by Salt
   * Edge in the account's {@code currency_code} field. Currencies are standard master data, so the
   * readable client/org filters are disabled to avoid an empty lookup in a specific context.
   *
   * @param isoCode
   *     the ISO currency code
   * @return the matching currency, or {@code null} if none exists
   */
  static Currency findCurrencyByIsoCode(String isoCode) {
    if (StringUtils.isBlank(isoCode)) {
      return null;
    }
    OBCriteria<Currency> criteria = OBDal.getInstance().createCriteria(Currency.class);
    criteria.setFilterOnReadableClients(false);
    criteria.setFilterOnReadableOrganization(false);
    criteria.add(Restrictions.eq(Currency.PROPERTY_ISOCODE, isoCode.trim().toUpperCase()));
    criteria.add(Restrictions.eq(Currency.PROPERTY_ACTIVE, true));
    criteria.setMaxResults(1);
    return (Currency) criteria.uniqueResult();
  }

  private static MatchingAlgorithm defaultMatchingAlgorithm() {
    OBCriteria<MatchingAlgorithm> criteria =
        OBDal.getInstance().createCriteria(MatchingAlgorithm.class);
    criteria.add(Restrictions.eq(MatchingAlgorithm.PROPERTY_ACTIVE, true));
    criteria.addOrderBy(MatchingAlgorithm.PROPERTY_NAME, true);
    criteria.setMaxResults(1);
    return (MatchingAlgorithm) criteria.uniqueResult();
  }
}
