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
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hibernate.criterion.Restrictions;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.currency.Currency;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.financialmgmt.payment.FIN_FinancialAccount;
import org.openbravo.model.financialmgmt.payment.FIN_PaymentMethod;
import org.openbravo.model.financialmgmt.payment.FinAccPaymentMethod;
import org.openbravo.model.financialmgmt.payment.MatchingAlgorithm;

import com.etendoerp.psd2.bank.integration.utils.BankIntegrationConstants;

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

  private static final Logger log = LogManager.getLogger(FinancialAccountSupport.class);

  // Default payment methods seeded by the onboarding dataset (GOClient sampledata),
  // matched by name. it1 has no payment-method management screen and exactly these
  // four fixed methods, so name matching is acceptable; if methods ever become
  // localizable or renameable this must migrate to a stable key.
  private static final String METHOD_CASH = "Efectivo";
  private static final String METHOD_TRANSFER = "Transferencia bancaria";
  private static final String METHOD_TRANSFER_SHORT = "Transferencia";
  private static final String METHOD_CHECK = "Cheque";
  private static final String METHOD_CARD = "Tarjeta";

  /**
   * Maps each financial-account type to the payment methods that must be auto-assigned
   * on creation. The first method in each list becomes the account's default. Mirrors
   * the static links shipped in the onboarding dataset for the seeded accounts.
   */
  private static final Map<String, List<String>> PAYMENT_METHODS_BY_TYPE = buildMethodsByType();

  private static Map<String, List<String>> buildMethodsByType() {
    Map<String, List<String>> map = new LinkedHashMap<>();
    map.put("C", Arrays.asList(METHOD_CASH));
    map.put("B", Arrays.asList(METHOD_TRANSFER, METHOD_CHECK, METHOD_CARD));
    map.put("CA", Arrays.asList(METHOD_CARD));
    return map;
  }

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

  /**
   * Links the default payment methods that correspond to {@code account}'s type
   * ({@link #PAYMENT_METHODS_BY_TYPE}), so a Cash/Bank/Card account is usable for
   * receipts/payments without manual setup. Idempotent: existing links are left
   * untouched. Failures never propagate — callers persist the account first, so the
   * assignment is always best-effort on top of an already-committed record.
   *
   * <p>Shared by {@link FinancialAccountHandler#afterHandle} (manual "sin conexión"
   * creation) and {@link FinancialAccountPsd2Handler#handleCreateAndLink} (Salt Edge
   * "create and link" flow), so every financial account gets the same treatment
   * regardless of how it was created.
   */
  static void assignDefaultPaymentMethods(FIN_FinancialAccount account) {
    List<String> methodNames = PAYMENT_METHODS_BY_TYPE.get(account.getType());
    if (methodNames == null || methodNames.isEmpty()) {
      return;
    }
    boolean created = false;
    for (int i = 0; i < methodNames.size(); i++) {
      String methodName = methodNames.get(i);
      FIN_PaymentMethod method = findPaymentMethodByName(methodName);
      if (method == null) {
        log.warn("assignDefaultPaymentMethods: payment method '{}' not found; skipping", methodName);
      } else if (!linkExists(account, method)) {
        createLink(account, method, i == 0);
        created = true;
      }
    }
    if (created) {
      OBDal.getInstance().flush();
    }
  }

  private static FIN_PaymentMethod findPaymentMethodByName(String name) {
    OBCriteria<FIN_PaymentMethod> criteria =
        OBDal.getInstance().createCriteria(FIN_PaymentMethod.class);
    criteria.add(Restrictions.eq(FIN_PaymentMethod.PROPERTY_NAME, name));
    criteria.add(Restrictions.eq(FIN_PaymentMethod.PROPERTY_ACTIVE, true));
    criteria.setMaxResults(1);
    return (FIN_PaymentMethod) criteria.uniqueResult();
  }

  private static boolean linkExists(FIN_FinancialAccount account, FIN_PaymentMethod method) {
    OBCriteria<FinAccPaymentMethod> criteria =
        OBDal.getInstance().createCriteria(FinAccPaymentMethod.class);
    criteria.add(Restrictions.eq(FinAccPaymentMethod.PROPERTY_ACCOUNT, account));
    criteria.add(Restrictions.eq(FinAccPaymentMethod.PROPERTY_PAYMENTMETHOD, method));
    criteria.setMaxResults(1);
    return criteria.uniqueResult() != null;
  }

  private static void createLink(FIN_FinancialAccount account, FIN_PaymentMethod method,
      boolean isDefault) {
    FinAccPaymentMethod link = OBProvider.getInstance().get(FinAccPaymentMethod.class);
    link.setClient(account.getClient());
    link.setOrganization(account.getOrganization());
    link.setAccount(account);
    link.setPaymentMethod(method);
    link.setDefault(isDefault);
    // Multicurrency ON by default (ETP-4503): runtime-created links are born multicurrency-ON,
    // matching the onboarding sampledata. The PSD2 bank-transfer exception (a Bank account with an
    // active PSD2 connection) is applied afterwards by FinancialAccountPsd2Handler through
    // disableMulticurrencyForBankTransfer, so ordinary accounts keep multicurrency enabled.
    link.setPayinIsMulticurrency(true);
    link.setPayoutIsMulticurrency(true);
    // payinAllow/payoutAllow (true) and execution type ("M") come from the entity's
    // column defaults — Manual, allowing both receipts and payments. The reconcile/
    // automatic-use fields below do NOT have a sane default on their own (they default
    // to "unchecked"/empty), so they must be copied from the payment method master —
    // otherwise every new account's transaction handling silently diverges from what
    // the payment method itself is configured to do.
    link.setUponDepositUse(method.getUponDepositUse());
    link.setUponWithdrawalUse(method.getUponWithdrawalUse());
    link.setAutomaticDeposit(method.isAutomaticDeposit());
    link.setAutomaticWithdrawn(method.isAutomaticWithdrawn());
    OBDal.getInstance().save(link);
  }

  /**
   * Disables multicurrency (both pay-in and pay-out) on the bank-transfer payment-method link of
   * {@code account}, implementing the PSD2 exception to the "multicurrency ON by default" rule
   * (ETP-4503): a PSD2 transfer is executed by the bank in the account's own currency, so
   * multicurrency on that link is misleading.
   *
   * <p>Called from {@link FinancialAccountPsd2Handler} right after a Bank account is connected to
   * PSD2 (the create-and-link and link paths). The account-type gate lives here — the method only
   * acts on Bank accounts ({@link BankIntegrationConstants#FA_TYPE_BANK}) — so the call site can
   * invoke it unconditionally. The active-PSD2-connection condition is guaranteed by construction:
   * the call sites are exactly the points where a connection has just been established.
   *
   * <p>The transfer link is identified the same way as the corrective R14 data-fix: the PSD2
   * extension flag {@code EM_PSD2_Is_Bank_Transfer='Y'} first, with a name fallback
   * ({@code "Transferencia bancaria"} / {@code "Transferencia"}) because the live flag diverges
   * from the seeded value on existing tenants.
   *
   * <p>Idempotent (only touches links still multicurrency-ON) and best-effort: the account is
   * already persisted, so any failure here is logged and swallowed rather than propagated —
   * mirroring {@link #assignDefaultPaymentMethods}.
   */
  static void disableMulticurrencyForBankTransfer(FIN_FinancialAccount account) {
    if (account == null || !BankIntegrationConstants.FA_TYPE_BANK.equals(account.getType())) {
      return;
    }
    try {
      OBCriteria<FinAccPaymentMethod> criteria =
          OBDal.getInstance().createCriteria(FinAccPaymentMethod.class);
      criteria.add(Restrictions.eq(FinAccPaymentMethod.PROPERTY_ACCOUNT, account));
      boolean changed = false;
      for (FinAccPaymentMethod link : criteria.list()) {
        if (isBankTransferMethod(link.getPaymentMethod())
            && (Boolean.TRUE.equals(link.isPayinIsMulticurrency())
                || Boolean.TRUE.equals(link.isPayoutIsMulticurrency()))) {
          link.setPayinIsMulticurrency(false);
          link.setPayoutIsMulticurrency(false);
          OBDal.getInstance().save(link);
          changed = true;
        }
      }
      if (changed) {
        OBDal.getInstance().flush();
        log.info("disableMulticurrencyForBankTransfer: disabled multicurrency on the transfer "
            + "link(s) of PSD2-connected Bank account {}", account.getId());
      }
    } catch (Exception e) {
      log.warn("disableMulticurrencyForBankTransfer: skipped for account {} ({})",
          account.getId(), e.getMessage());
    }
  }

  /**
   * Whether {@code method} is the bank-transfer payment method: the PSD2 extension flag
   * {@code EM_PSD2_Is_Bank_Transfer='Y'} first, then a name fallback. Mirrors the R14 data-fix
   * predicate.
   */
  private static boolean isBankTransferMethod(FIN_PaymentMethod method) {
    if (method == null) {
      return false;
    }
    if (Boolean.TRUE.equals(method.isPSD2IsBankTransfer())) {
      return true;
    }
    String name = method.getName();
    return METHOD_TRANSFER.equals(name) || METHOD_TRANSFER_SHORT.equals(name);
  }
}
