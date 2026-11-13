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

import org.apache.commons.lang3.StringUtils;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.model.common.currency.Currency;
import org.openbravo.model.financialmgmt.payment.FIN_FinancialAccount;

import com.etendoerp.psd2.bank.integration.utils.BankIntegrationConstants;

/**
 * Builds the pieces of the {@code GET accounts} payload of {@link
 * FinancialAccountBankConnectionHandler}: the rows of the linkable bank accounts, the bank's logo
 * and history limit, and the diagnosis of an empty list.
 *
 * <p>Split out of the handler to keep its method count under the Sonar limit (S1448). Stateless:
 * every method is static and works only on its arguments.
 */
final class FinancialAccountBankAccountsPayload {

  // Response JSON keys. The handler aliases the ones it also uses elsewhere.
  static final String KEY_SALT_EDGE_ACCOUNT_ID = "saltEdgeAccountId";
  static final String KEY_NAME = "name";
  static final String KEY_MAX_FETCH_INTERVAL = "maxFetchInterval";
  private static final String KEY_IBAN = "iban";
  private static final String KEY_CURRENCY = "currency";
  private static final String KEY_NATURE = "nature";
  private static final String KEY_PROVIDER_LOGO = "providerLogoUrl";

  // ETP-5179. Diagnosis of an empty `accounts` list, as a machine-readable CODE rather than an
  // English sentence. The obvious alternative — an AD_Message from
  // com.etendoerp.psd2.bank.integration — cannot be used: those rows ship with istranslated='N',
  // so Core resolves them to their English text unless the environment happened to import the
  // translation pack, and their `%s` templates never interpolate either (OBMessageUtils
  // .getI18NMessage only substitutes `%0`). A code is translated by the SPA in all three shipped
  // locales, with its parameter, without depending on Core provisioning.
  private static final String KEY_EMPTY_REASON = "emptyReason";
  private static final String KEY_ACCOUNT_CURRENCY = "accountCurrency";
  private static final String REASON_NO_ACCOUNTS = "noAccounts";
  private static final String REASON_TYPE_MISMATCH = "typeMismatch";
  private static final String REASON_ALL_LINKED = "allLinked";
  private static final String REASON_CURRENCY_MISMATCH = "currencyMismatch";

  private FinancialAccountBankAccountsPayload() {
  }

  /**
   * Maps the Salt Edge accounts that survived the filters to the rows the SPA selection modal
   * renders: {@code saltEdgeAccountId}, {@code name}, {@code currency}, {@code nature} and
   * {@code iban}, each defaulting to an empty string. Entries that are not JSON objects are skipped.
   *
   * @param accounts the filtered Salt Edge accounts
   * @return one row per account, in the same order
   * @throws JSONException never in practice
   */
  static JSONArray toAccountRows(JSONArray accounts) throws JSONException {
    JSONArray out = new JSONArray();
    for (int i = 0; i < accounts.length(); i++) {
      JSONObject node = accounts.optJSONObject(i);
      if (node == null) {
        continue;
      }
      JSONObject row = new JSONObject();
      row.put(KEY_SALT_EDGE_ACCOUNT_ID, node.optString(BankIntegrationConstants.ID, ""));
      row.put(KEY_NAME, node.optString(BankIntegrationConstants.NAME, ""));
      row.put(KEY_CURRENCY, node.optString(BankIntegrationConstants.CURRENCY_CODE, ""));
      row.put(KEY_NATURE, node.optString(BankIntegrationConstants.NATURE, ""));
      row.put(KEY_IBAN, FinancialAccountBankConnectionSupport.ibanOf(node));
      out.put(row);
    }
    return out;
  }

  /**
   * Puts the bank's {@code providerLogoUrl} and, with the PSD2 import settings flag on,
   * {@code maxFetchInterval} into the accounts payload. One Salt Edge call serves both the logo
   * and, when the provider is not registered here yet, the history limit (ETP-6151). The limit is
   * omitted (not null) when unknown, so the SPA reads {@code undefined} and stays silent. With the
   * flag off only the logo is put, through the single call it always was.
   *
   * @param data         the accounts payload being built
   * @param providerCode the Salt Edge provider code of the connection, not blank
   * @param apiKey       the PSD2 API key the call runs under
   * @throws JSONException never in practice
   */
  static void putProviderDisplay(JSONObject data, String providerCode, String apiKey)
      throws JSONException {
    if (Psd2ImportSettingsFlag.isEnabled()) {
      FinancialAccountBankConnectionSupport.ProviderDisplay provider =
          FinancialAccountBankConnectionSupport.fetchProviderDisplay(providerCode, apiKey);
      data.put(KEY_PROVIDER_LOGO, provider.getLogoUrl());
      if (provider.getMaxFetchInterval() != null) {
        data.put(KEY_MAX_FETCH_INTERVAL, provider.getMaxFetchInterval().intValue());
      }
    } else {
      data.put(KEY_PROVIDER_LOGO,
          FinancialAccountBankConnectionSupport.fetchProviderLogo(providerCode, apiKey));
    }
  }

  /**
   * Explains an empty {@code accounts} list by naming the FIRST filtering stage that emptied it,
   * so the SPA can raise a toast that states the actual cause instead of one generic message.
   *
   * <p>The reason is a code, not a sentence — see {@link #KEY_EMPTY_REASON} for why the wording is
   * left to the SPA. {@link #KEY_ACCOUNT_CURRENCY} is added only for a currency mismatch, where the
   * Financial Account's own ISO code is the single piece of data the message needs; the other
   * reasons take no parameter, and the SPA treats the field's presence as meaningful.
   *
   * <p>Kept out of {@code handleAccounts} to hold that method's cognitive complexity down.
   *
   * @param data           response payload being built
   * @param fromBank       accounts as the bank returned them, before any filtering
   * @param typeFiltered   accounts left after the Financial Account type filter
   * @param unlinked       accounts left after discarding the ones already linked elsewhere
   * @param finAcc         the Financial Account being connected, or {@code null} in the create flow
   */
  static void putEmptyDiagnosis(JSONObject data, JSONArray fromBank, JSONArray typeFiltered,
      JSONArray unlinked, FIN_FinancialAccount finAcc) throws JSONException {
    String reason = emptyReasonOf(fromBank, typeFiltered, unlinked);
    data.put(KEY_EMPTY_REASON, reason);
    if (!REASON_CURRENCY_MISMATCH.equals(reason)) {
      return;
    }
    // filterAccountsByCurrency short-circuits on a currency-less FA, so reaching this branch means
    // there is one — read it defensively all the same.
    Currency currency = finAcc != null ? finAcc.getCurrency() : null;
    String isoCode = currency != null ? currency.getISOCode() : null;
    if (StringUtils.isNotBlank(isoCode)) {
      data.put(KEY_ACCOUNT_CURRENCY, isoCode);
    }
  }

  /** The first filtering stage that emptied the list; the currency filter is the last resort. */
  private static String emptyReasonOf(JSONArray fromBank, JSONArray typeFiltered,
      JSONArray unlinked) {
    if (fromBank.length() == 0) {
      return REASON_NO_ACCOUNTS;
    }
    if (typeFiltered.length() == 0) {
      return REASON_TYPE_MISMATCH;
    }
    if (unlinked.length() == 0) {
      return REASON_ALL_LINKED;
    }
    return REASON_CURRENCY_MISMATCH;
  }
}
