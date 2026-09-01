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

/**
 * Helper for creating {@link FIN_FinancialAccount} records programmatically, outside the generic
 * CRUD path. Used by the bank connection bridge ({@link FinancialAccountBankConnectionHandler}) for the "connect first,
 * create after" flow (case 2): the account is materialized only once the user has authenticated
 * with their bank and chosen which Salt Edge account to link, so its name and currency are taken
 * from that Salt Edge account.
 *
 * <p>The mandatory-field set mirrors the proven programmatic financial-account creation used
 * during onboarding seed provisioning (balances and credit limit default to zero), plus a default
 * matching algorithm so reconciliation has one to work with.
 * No accounting account is set on purpose — the user configures it later from Edit account; the
 * account is usable for import and reconciliation without it.
 */
final class FinancialAccountSupport {

  private static final Logger log = LogManager.getLogger(FinancialAccountSupport.class);

  // Default payment methods seeded by the onboarding dataset (GOClient sampledata),
  // matched by name. it1 has no payment-method management screen and exactly these
  // four fixed methods, so name matching is acceptable; if methods ever become
  // localizable or renameable this must migrate to a stable key.
  // METHOD_RECEIPT replaced the former "Cheque" method: the name lives in the sampledata
  // template for new tenants and is repaired on existing ones by the R24 data-fix, so a
  // tenant that has not run that fix yet simply gets no link (see assignDefaultPaymentMethods).
  private static final String METHOD_CASH = "Efectivo";
  private static final String METHOD_TRANSFER = "Transferencia bancaria";
  private static final String METHOD_TRANSFER_SHORT = "Transferencia";
  private static final String METHOD_TRANSFER_EN = "Wire Transfer";
  private static final String METHOD_RECEIPT = "Recibo";
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
    map.put("B", Arrays.asList(METHOD_TRANSFER, METHOD_RECEIPT, METHOD_CARD));
    map.put("CA", Arrays.asList(METHOD_CARD, METHOD_RECEIPT));
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
   * creation) and {@link FinancialAccountBankConnectionHandler#handleCreateAndLink} (Salt Edge
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
    // matching the onboarding sampledata. ETP-5084 removed the bank-transfer exception that used to
    // clear these two flags when the account was connected to its bank: a PIS transfer converts the
    // invoice amount to the account currency and instructs the bank in that currency, so a
    // cross-currency transfer is a supported operation and the transfer link is multicurrency like
    // every other payment method. Data-fix R29 re-enables it on already-connected accounts.
    link.setPayinIsMulticurrency(true);
    link.setPayoutIsMulticurrency(true);
    // The PSD2 "is bank transfer" identity flag has no sane column default (falls to 'N') and
    // is never copied from the payment method master by the entity, so runtime-created links
    // must set it explicitly here or the transfer link is silently unidentifiable downstream
    // (both this class's own bank-connection exception check and the checkbox on the account UI).
    if (isBankTransferMethod(method)) {
      link.setPSD2IsBankTransfer(true);
    }
    // payinAllow/payoutAllow (true) and execution type ("M") come from the entity's
    // column defaults — Manual, allowing both receipts and payments. The reconcile/
    // automatic-use fields below do NOT have a sane default on their own (they default
    // to "unchecked"/empty), so they must be copied from the payment method master —
    // otherwise every new account's transaction handling silently diverges from what
    // the payment method itself is configured to do.
    link.setUponDepositUse(method.getUponDepositUse());
    link.setUponWithdrawalUse(method.getUponWithdrawalUse());
    link.setINUponClearingUse(method.getINUponClearingUse());
    link.setOUTUponClearingUse(method.getOUTUponClearingUse());
    link.setAutomaticDeposit(method.isAutomaticDeposit());
    // ETP-4891: the bank-transfer method NEVER auto-withdraws. Etendo Go pays transfers over PIS,
    // where the FIN_Finacc_Transaction is created by the Salt Edge callback once the bank reports
    // execution — auto-creating it here too would double it. This is now an invariant of the
    // method, not a consequence of the account having a bank connection (the connect/disconnect
    // toggling in FinancialAccountBankConnectionHandler was removed with this change), so it is
    // enforced here rather than copied from the template: sampledata seeds the template with 'N'
    // and R24 repairs existing tenants, but a legacy template still on 'Y' — or a transfer method
    // created by hand — must not be able to propagate it to the link.
    link.setAutomaticWithdrawn(!isBankTransferMethod(method) && method.isAutomaticWithdrawn());
    OBDal.getInstance().save(link);
  }

  /**
   * Whether {@code method} is the bank-transfer payment method: the extension flag
   * {@code EM_PSD2_Is_Bank_Transfer='Y'} first, then a name fallback (Spanish and English
   * variants). Mirrors the R14/R15 data-fix predicates.
   *
   * <p>Package-private (ETP-4891) so {@link PaymentRegistrationService} can tag the payment
   * methods it lists with the same predicate the runtime uses. The payment modal's transfer gate
   * now BLOCKS a payment instead of merely offering an extra section, so it must not keep guessing
   * from the method name on its own.
   */
  static boolean isBankTransferMethod(FIN_PaymentMethod method) {
    if (method == null) {
      return false;
    }
    if (Boolean.TRUE.equals(method.isPSD2IsBankTransfer())) {
      return true;
    }
    String name = method.getName();
    return METHOD_TRANSFER.equals(name) || METHOD_TRANSFER_SHORT.equals(name)
        || METHOD_TRANSFER_EN.equals(name);
  }
}
