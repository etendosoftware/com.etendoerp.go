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

import java.sql.SQLException;

import javax.servlet.http.HttpServletResponse;

import org.openbravo.dal.core.OBContext;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.financialmgmt.payment.FIN_FinancialAccount;

/**
 * ETP-6148 — a new Bank financial account gets the two SEPA remittance payment methods, while the
 * {@code sepa-remittances} flag is on for the acting user. There is no per-account switch: every
 * Bank account created with the flag on is a remittance account, Cash and Card accounts never are.
 * Accounts created before, or with the flag off, are not revisited (no backfill).
 *
 * <p>Entity behaviour of the account creation flows, so it lives with them and nowhere shared.
 * Two callers, both evaluating the flag at request entry, as the account owner:
 * <ul>
 *   <li>{@link FinancialAccountHandler} (REST single and batch, MCP). The pre-hook runs
 *       {@link #prepareClient}: the client setup ({@link RemittanceSepaProvisioning#ensureClient})
 *       runs in the same transaction as the account insert, so a failure refuses the create and
 *       leaves nothing behind. The post-hook runs {@link #linkNewAccount} once the account row
 *       exists: the links need its id.</li>
 *   <li>{@link FinancialAccountBankConnectionHandler#handleCreateAndLink}, which creates the account
 *       itself inside its pre-hook and so runs {@link #provisionNewAccount} — setup and links — in
 *       that one transaction.</li>
 * </ul>
 *
 * <p><b>Type change.</b> A Bank account with open remittances cannot leave Bank
 * ({@link #checkTypeChange}): a SEPA remittance settles through a bank account, and Classic would
 * then process or settle it against a cash or card one. With none open the change goes ahead and,
 * once the update succeeded, the post-hook deactivates the account's remittance-method links
 * ({@link #deactivateAfterLeavingBank}); a refused update keeps them. Outside a batch the update is
 * already committed then, so a failure there is logged and the account keeps active links (the
 * same gap as a failed link on create). Becoming a Bank account again does not reactivate them. Not gated by the flag: the remittances and links exist whatever the flag
 * says now (it may have been turned off for the user since), and both only concern the provisioned
 * types and methods, which exist only where the flag was on.
 *
 * <p>The onboarding sample-data «Cuenta de Banco» is covered by
 * {@code OnboardingRemittanceSetupService}, which reads the flag for the account receiving the
 * tenant (the onboarding runs as System).
 *
 * <p>Package-private seams ({@link #isFlagEnabled}, {@link #ensureClient}, …) let unit tests run
 * the rules without the DAL.
 */
class FinancialAccountRemittanceSupport {

  static final String MSG_PROVISIONING_CONFLICT = "ETGO_RemittanceProvisioningConflict";
  static final String MSG_TYPE_CHANGE_BLOCKED = "ETGO_RemittanceAccountTypeChangeBlocked";

  private static final int SC_UNPROCESSABLE = 422;

  private static final String SQLSTATE_UNIQUE = "23505";

  /**
   * Whether an account of {@code type} created now gets the remittance payment methods: a Bank
   * account while the flag is on. Evaluated at request entry.
   *
   * @param type the account type ({@code B}, {@code C}, {@code CA})
   * @return {@code true} for a Bank account with the flag on
   */
  boolean appliesTo(String type) {
    return FinancialAccountHandler.TYPE_BANK.equals(type) && isFlagEnabled();
  }

  /**
   * Pre-hook of a create that {@link #appliesTo} accepts: provisions the client in the
   * transaction that will insert the account.
   *
   * @return a 409 response on a provisioning conflict, or {@code null} to continue
   */
  NeoResponse prepareClient() {
    return provision(() -> ensureClient(currentClient()));
  }

  /**
   * Post-hook of the same create: links the new account to the two remittance methods. Runs after
   * the generic CRUD inserted (and, outside a batch, committed) the account; the client setup it
   * relies on was committed with it by {@link #prepareClient}.
   *
   * @param account the persisted account; {@code null}, a non-Bank account or the flag off is a
   *     no-op
   */
  void linkNewAccount(FIN_FinancialAccount account) {
    if (account == null || !appliesTo(account.getType())) {
      return;
    }
    ensureAccountLinks(account);
  }

  /**
   * Setup and links in one go, for a flow that creates the account itself inside its own
   * transaction ({@link FinancialAccountBankConnectionHandler#handleCreateAndLink}).
   *
   * @param account the created and flushed account
   * @return a 409 response on a provisioning conflict (the transaction is rolled back), or
   *     {@code null} when it was provisioned or does not apply
   */
  NeoResponse provisionNewAccount(FIN_FinancialAccount account) {
    if (account == null || !appliesTo(account.getType())) {
      return null;
    }
    return provision(() -> provisionAccount(account));
  }

  /**
   * Runs a provisioning step. A unique violation there — a document type with the same name
   * inserted at the same instant by a writer outside {@link RemittanceSepaProvisioning}, which
   * serializes its own runs — leaves the transaction unusable, so it is rolled back (unless a batch
   * owns the transaction, which then rolls back the whole set) and answered
   * 409 {@value #MSG_PROVISIONING_CONFLICT} (retry), never a 500. Any other failure propagates and
   * fails the create.
   */
  private NeoResponse provision(Runnable step) {
    try {
      step.run();
      return null;
    } catch (RuntimeException e) {
      if (!isUniqueViolation(e)) {
        throw e;
      }
      rollback();
      return NeoMessageText.error(HttpServletResponse.SC_CONFLICT, MSG_PROVISIONING_CONFLICT);
    }
  }

  /**
   * Update pre-hook of a change that takes a Bank account to another type: refuses it while the
   * account has open remittances (REM status pending, processed, partially collected or partially
   * paid), with 422 {@value #MSG_TYPE_CHANGE_BLOCKED} carrying the count. Writes nothing: the
   * links are deactivated by {@link #deactivateAfterLeavingBank}, from the post-hook, once the
   * update succeeded. Runs only when the type really leaves Bank, so an ordinary update never
   * queries.
   *
   * @param stored  the account as stored, {@code null} when unknown (the generic CRUD answers it)
   * @param newType the type the update leaves, already normalized
   * @return the refusal, or {@code null} to continue
   */
  NeoResponse checkTypeChange(FIN_FinancialAccount stored, String newType) {
    if (!leavesBank(stored, newType)) {
      return null;
    }
    long open = countOpenRemittances(stored);
    return open > 0 ? NeoMessageText.error(SC_UNPROCESSABLE, MSG_TYPE_CHANGE_BLOCKED, open) : null;
  }

  /**
   * Whether the update takes a stored Bank account to another type.
   *
   * @param stored  the account as stored, may be {@code null}
   * @param newType the type the update leaves, already normalized
   * @return {@code true} when the account is Bank today and will not be after the update
   */
  boolean leavesBank(FIN_FinancialAccount stored, String newType) {
    return stored != null && FinancialAccountHandler.TYPE_BANK.equals(stored.getType())
        && !FinancialAccountHandler.TYPE_BANK.equals(newType);
  }

  /**
   * Update post-hook of a change that took a Bank account to another type: deactivates its
   * remittance-method links ({@link RemittanceSepaProvisioning#deactivateAccountLinks}). Called
   * only after the generic CRUD answered the update with success, so a refused update
   * ({@code missing_updated}, a stale record, any validation) never touches them. The account is
   * re-read: nothing is done unless it really is no longer a Bank account. Becoming a Bank account
   * again does not reactivate them.
   *
   * @param account the account, re-read after the update; {@code null} is a no-op
   */
  void deactivateAfterLeavingBank(FIN_FinancialAccount account) {
    if (account == null || FinancialAccountHandler.TYPE_BANK.equals(account.getType())) {
      return;
    }
    deactivateAccountLinks(account);
  }

  static boolean isUniqueViolation(Throwable error) {
    for (Throwable t = error; t != null; t = t.getCause() == t ? null : t.getCause()) {
      for (SQLException sql = t instanceof SQLException ? (SQLException) t : null; sql != null;
          sql = sql.getNextException()) {
        if (SQLSTATE_UNIQUE.equals(sql.getSQLState())) {
          return true;
        }
      }
    }
    return false;
  }

  // ---------------------------------------------------------------------------
  // Seams
  // ---------------------------------------------------------------------------

  boolean isFlagEnabled() {
    return SepaRemittancesFlag.isEnabled();
  }

  long countOpenRemittances(FIN_FinancialAccount account) {
    return RemittanceSepaProvisioning.countOpenRemittances(account);
  }

  void ensureClient(Client client) {
    RemittanceSepaProvisioning.ensureClient(client);
  }

  void ensureAccountLinks(FIN_FinancialAccount account) {
    RemittanceSepaProvisioning.ensureAccountLinks(account);
  }

  void provisionAccount(FIN_FinancialAccount account) {
    RemittanceSepaProvisioning.provisionAccount(account);
  }

  void deactivateAccountLinks(FIN_FinancialAccount account) {
    RemittanceSepaProvisioning.deactivateAccountLinks(account);
  }

  /**
   * Rolls the failed provisioning back, unless a batch ({@code etendo_batch}, {@code /sws/neo/batch})
   * owns the transaction: the batch rolls the whole set back itself, and closing its session here
   * would break its bookkeeping.
   */
  void rollback() {
    NeoErrorResponses.rollbackUnlessBatchOwned();
  }

  Client currentClient() {
    return OBContext.getOBContext().getCurrentClient();
  }
}
