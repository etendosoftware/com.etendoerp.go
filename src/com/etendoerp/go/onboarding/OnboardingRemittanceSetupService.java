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
package com.etendoerp.go.onboarding;

import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.dal.service.OBQuery;
import org.openbravo.model.financialmgmt.payment.FIN_FinancialAccount;

import com.etendoerp.go.schemaforge.RemittanceSepaProvisioning;
import com.etendoerp.go.schemaforge.SepaRemittancesFlag;

/**
 * ETP-6148 — gives the Bank financial accounts a new tenant is born with the two SEPA remittance
 * payment methods, only while the {@code sepa-remittances} flag is on for the account the tenant is
 * handed to. The onboarding counterpart of what a Bank account created through NEO gets
 * ({@code FinancialAccountRemittanceSupport}).
 *
 * <p><b>Which accounts.</b> The base dataset gives a tenant no financial account
 * ({@code OnboardingDemoMasterData#FINANCIAL_ACCOUNT_IDS}); only the opt-in sample data brings
 * «Cuenta de Banco» (type Bank, no IBAN). So the step runs after the sample-data import, on the
 * tenant's active Bank accounts at that moment — all of them new. Without sample data it finds
 * none and does nothing, not even the client provisioning, which stays lazy. «Cuenta de Banco» has
 * no IBAN, so the remittance window offers it only once the user gives it a valid one.
 *
 * <p><b>Why a step and not the dataset or a DAL observer.</b> The flag is per account and the
 * onboarding runs as System, so it is read here for the receiving account, passed in explicitly,
 * after the onboarding commit, on the path a classic and a pooled tenant share (the same rule as
 * {@link OnboardingDefaultContactCategoryService}). A tenant built in the pool with the flag off and
 * claimed with it on still gets the methods.
 *
 * <p><b>Idempotent.</b> {@link RemittanceSepaProvisioning#provisionAccount} reuses what exists.
 *
 * <p><b>Transaction contract.</b> Neither commits nor rolls back; the caller does, as for the other
 * post-commit onboarding steps. The caller treats it as best effort: a failure here must never undo
 * a committed tenant, it only leaves the sample bank account without the remittance methods.
 */
public class OnboardingRemittanceSetupService {

  private static final Logger log = LogManager.getLogger(OnboardingRemittanceSetupService.class);

  /** {@code FIN_FINANCIAL_ACCOUNT.TYPE} of a bank account. */
  static final String TYPE_BANK = "B";

  /**
   * Provisions the tenant's Bank accounts for remittances when the flag is on for the receiving
   * account.
   *
   * @param clientId the tenant just committed
   * @param accountEmail the {@code ETGO_ACCOUNT} email of the account receiving the tenant
   * @return {@code true} when at least one account was provisioned (the caller commits),
   *     {@code false} when there was nothing to do: flag off or no Bank account
   */
  public boolean applyIfEnabled(String clientId, String accountEmail) {
    if (clientId == null || !isFlagEnabled(clientId, accountEmail)) {
      return false;
    }
    OBContext.setAdminMode(true);
    try {
      List<FIN_FinancialAccount> accounts = loadBankAccounts(clientId);
      for (FIN_FinancialAccount account : accounts) {
        provisionAccount(account);
      }
      if (!accounts.isEmpty()) {
        log.info("ETP-6148 client {}: {} bank account(s) linked to the SEPA remittance methods",
            clientId, accounts.size());
      }
      return !accounts.isEmpty();
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /** Seam for tests: the flag read for the receiving account and the tenant being handed over. */
  protected boolean isFlagEnabled(String clientId, String accountEmail) {
    return SepaRemittancesFlag.isEnabled(accountEmail, clientId);
  }

  /** The tenant's active Bank accounts. */
  protected List<FIN_FinancialAccount> loadBankAccounts(String clientId) {
    OBQuery<FIN_FinancialAccount> query = OBDal.getInstance().createQuery(
        FIN_FinancialAccount.class,
        "as a where a.client.id = :client and a.type = :bank order by a.id");
    query.setNamedParameter("client", clientId);
    query.setNamedParameter("bank", TYPE_BANK);
    query.setFilterOnReadableClients(false);
    query.setFilterOnReadableOrganization(false);
    return query.list();
  }

  /** Seam for tests: the client setup and the account's remittance links. */
  protected void provisionAccount(FIN_FinancialAccount account) {
    RemittanceSepaProvisioning.provisionAccount(account);
  }
}
