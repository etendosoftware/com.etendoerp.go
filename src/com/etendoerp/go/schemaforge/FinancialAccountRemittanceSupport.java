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

import org.apache.commons.lang3.StringUtils;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.core.OBContext;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.geography.Country;
import org.openbravo.model.financialmgmt.payment.FIN_FinancialAccount;

/**
 * ETP-6148 — the «Admite remesas» rules of a financial account ({@value #FIELD_ALLOW},
 * {@code FIN_FINANCIAL_ACCOUNT.EM_ETGO_ALLOW_REMITTANCE}), called by {@link FinancialAccountHandler}
 * on create and update. Entity behaviour of the {@code financial-account} spec, so it lives with
 * that spec's customization and nowhere shared.
 *
 * <p><b>Pre-hook</b> ({@link #checkWrite}), on the <em>effective</em> state — the body's value
 * where the body carries one, the stored account's otherwise:
 * <ul>
 *   <li><b>Flag.</b> With {@code sepa-remittances} off, a body that <em>changes</em> the flag
 *       answers 403 {@code feature_disabled}. A body that echoes the stored value (a client that
 *       reads the record and writes it back whole) is let through: it changes nothing.</li>
 *   <li><b>On</b> requires a Bank account ({@code ETGO_RemittanceAccountNotBank}) with a valid
 *       IBAN ({@code ETGO_RemittanceAccountIbanRequired}); the BIC stays optional. The same two
 *       checks refuse, while the flag is on, changing the type away from Bank or clearing the
 *       IBAN. Both 400, like the handler's other input checks.</li>
 *   <li><b>Turning on</b> runs {@link RemittanceSepaProvisioning#ensureClient}. It runs in the
 *       pre-hook on purpose: a failure there aborts the save instead of leaving an account that
 *       allows remittances with nothing behind it.</li>
 *   <li><b>Turning off</b>, or archiving an account that allows remittances, is refused while it
 *       has open remittances: 422 {@code ETGO_RemittanceAccountHasOpenRemittances}, with the
 *       count.</li>
 * </ul>
 *
 * <p><b>Post-hook</b> ({@link #afterWrite}): links or unlinks the two remittance payment methods
 * through {@link RemittanceSepaProvisioning#ensureAccountLinks}, once the account row carries the
 * new value — {@link RemittanceMethodLinkObserver} reads it.
 *
 * <p>Package-private seams ({@link #isFlagEnabled}, {@link #countOpenRemittances}, …) let unit tests
 * run the rules without the DAL.
 */
class FinancialAccountRemittanceSupport {

  /** NEO field key (DAL property) of {@code EM_ETGO_ALLOW_REMITTANCE}. */
  static final String FIELD_ALLOW = "eTGOAllowRemittance";

  static final String MSG_NOT_BANK = "ETGO_RemittanceAccountNotBank";
  static final String MSG_IBAN_REQUIRED = "ETGO_RemittanceAccountIbanRequired";
  static final String MSG_OPEN_REMITTANCES = "ETGO_RemittanceAccountHasOpenRemittances";
  static final String MSG_PROVISIONING_CONFLICT = "ETGO_RemittanceProvisioningConflict";

  private static final String SQLSTATE_UNIQUE = "23505";

  private static final int SC_UNPROCESSABLE = 422;

  /**
   * Whether the update body can affect the remittance rules, so the handler loads the stored
   * account only then.
   *
   * @param body the request body
   * @return {@code true} when it carries the flag, the type, the IBAN or the country, or archives
   */
  boolean isRelevant(JSONObject body) {
    return body != null && (body.has(FIELD_ALLOW) || body.has(FinancialAccountHandler.FIELD_TYPE)
        || body.has(FinancialAccountHandler.FIELD_IBAN) || body.has("country")
        || FinancialAccountSupport.isArchivingRequest(body));
  }

  /**
   * Validates a create or update against the remittance rules and provisions the client when the
   * write turns remittances on.
   *
   * @param body          the request body; the flag is normalized to a boolean when present
   * @param stored        the account on update, {@code null} on create
   * @param effectiveType the account type the write leaves ({@code B}, {@code C}, {@code CA})
   * @return an error response, or {@code null} to continue
   * @throws JSONException never in practice, from the body accessors
   */
  NeoResponse checkWrite(JSONObject body, FIN_FinancialAccount stored, String effectiveType)
      throws JSONException {
    boolean storedAllow = stored != null && Boolean.TRUE.equals(stored.isETGOAllowRemittance());
    boolean bodyHasAllow = body.has(FIELD_ALLOW);
    boolean effectiveAllow = storedAllow;
    if (bodyHasAllow) {
      effectiveAllow = toBoolean(body.opt(FIELD_ALLOW));
      body.put(FIELD_ALLOW, effectiveAllow);
      if (effectiveAllow != storedAllow && !isFlagEnabled()) {
        return SepaRemittancesFlag.featureDisabled();
      }
    }

    if (effectiveAllow) {
      NeoResponse accountError = validateAllowedAccount(body, stored, effectiveType);
      if (accountError != null) {
        return accountError;
      }
    }

    boolean turningOff = storedAllow && !effectiveAllow;
    boolean archiving = storedAllow && FinancialAccountSupport.isArchivingRequest(body);
    if (turningOff || archiving) {
      long open = countOpenRemittances(stored);
      if (open > 0) {
        return NeoMessageText.error(SC_UNPROCESSABLE, MSG_OPEN_REMITTANCES, open);
      }
    }

    if (effectiveAllow && !storedAllow) {
      return provision(stored != null ? stored.getClient() : currentClient());
    }
    return null;
  }

  /**
   * Runs the client provisioning. A unique violation there — a document type with the same name
   * inserted at the same instant by a writer outside {@link RemittanceSepaProvisioning}, which
   * serializes its own runs — leaves the transaction unusable, so it is rolled back (unless a batch
   * owns the transaction, which then rolls back the whole set) and answered
   * 409 {@value #MSG_PROVISIONING_CONFLICT} (retry), never a 500.
   */
  private NeoResponse provision(Client client) {
    try {
      ensureClient(client);
      return null;
    } catch (RuntimeException e) {
      if (!isUniqueViolation(e)) {
        throw e;
      }
      rollback();
      return NeoMessageText.error(HttpServletResponse.SC_CONFLICT, MSG_PROVISIONING_CONFLICT);
    }
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

  /**
   * Links or unlinks the remittance payment methods after a successful write that carried the
   * flag. Runs only while the flag is on: with it off nothing could have changed.
   *
   * @param account the persisted account
   */
  void afterWrite(FIN_FinancialAccount account) {
    if (account == null || !isFlagEnabled()) {
      return;
    }
    ensureAccountLinks(account, Boolean.TRUE.equals(account.isETGOAllowRemittance()));
  }

  private NeoResponse validateAllowedAccount(JSONObject body, FIN_FinancialAccount stored,
      String effectiveType) throws JSONException {
    if (!FinancialAccountHandler.TYPE_BANK.equals(effectiveType)) {
      return NeoMessageText.error(HttpServletResponse.SC_BAD_REQUEST, MSG_NOT_BANK);
    }
    boolean bodyHasIban = body.has(FinancialAccountHandler.FIELD_IBAN);
    String iban = FinancialAccountCountrySupport.effectiveIban(body, bodyHasIban, stored);
    if (StringUtils.isBlank(iban)) {
      return NeoMessageText.error(HttpServletResponse.SC_BAD_REQUEST, MSG_IBAN_REQUIRED);
    }
    // A body that sent the IBAN or the country already had the pair validated by
    // FinancialAccountHandler#validateCountryAndIban; a stored pair (turning the flag on for an
    // account saved long ago) was not, and a legacy row may hold an inconsistent one.
    if (!bodyHasIban && !body.has("country")) {
      Country country = FinancialAccountCountrySupport.storedCountry(stored);
      String normalized = FinancialAccountCountrySupport.normalizeIban(iban);
      if (FinancialAccountCountrySupport.validateIbanCountryPair(normalized, country) != null) {
        return NeoMessageText.error(HttpServletResponse.SC_BAD_REQUEST, MSG_IBAN_REQUIRED);
      }
    }
    return null;
  }

  private static boolean toBoolean(Object value) {
    if (value instanceof Boolean) {
      return (Boolean) value;
    }
    if (value == null || JSONObject.NULL.equals(value)) {
      return false;
    }
    String text = value.toString().trim();
    return "true".equalsIgnoreCase(text) || "Y".equalsIgnoreCase(text);
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

  void ensureAccountLinks(FIN_FinancialAccount account, boolean enabled) {
    RemittanceSepaProvisioning.ensureAccountLinks(account, enabled);
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
