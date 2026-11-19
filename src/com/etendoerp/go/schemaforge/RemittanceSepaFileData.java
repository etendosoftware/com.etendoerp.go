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

import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.model.financialmgmt.payment.FIN_FinancialAccount;

import com.etendoerp.go.schemaforge.util.NeoActionContract;
import com.etendoerp.go.schemaforge.util.NeoActionContract.Param;

/**
 * ETP-6148 — «Datos para el fichero SEPA»: what the SEPA file of a remittance will carry about the
 * creditor, shown read-only on the remittance header. Declared read action {@value #ACTION} of
 * {@link RemittanceHeaderHandler}; it reads the chosen account, not the remittance, so it also
 * answers for a remittance that is not saved yet.
 *
 * <ul>
 *   <li>{@code iban} / {@code ibanValid}: the effective IBAN (ETP-6247: the remittance's own copy,
 *       else the account's; {@code remittanceIban}, {@code accountIban}, {@code ibanSource}) and
 *       whether it is valid; for the account's, valid for its country ({@link RemittanceHeaderHandler#hasValidIban}, the account form's pair
 *       check). No BIC: the SEPA files do not need it.</li>
 *   <li>ETP-6247 — the creditor identifier: the remittance keeps its own copy
 *       ({@link RemittanceCreditorId}), defaulted from the account. {@code remittanceCreditorId}
 *       (the remittance's, only when {@code remittanceId} is a saved remittance of that account and
 *       the value is valid), {@code accountCreditorId} (the account's default, when valid),
 *       {@code suggestedCreditorId} (the computed one below), {@code creditorIdSource}
 *       ({@value #SOURCE_REMITTANCE}, {@value #SOURCE_ACCOUNT}, {@value #SOURCE_SUGGESTED} when
 *       none is saved but one can be computed, {@value #SOURCE_MISSING}) and {@code creditorId}, the
 *       effective one. {@code storedCreditorId} / {@code storedCreditorIdValid} stay as aliases of
 *       the account's. The write is {@value #ACTION_SAVE_CREDITOR_ID}.</li>
 *   <li>{@code suggestedCreditorId}, formerly {@code creditorId}: the SEPA creditor identifier (AT-02), computed the way Classic's
 *       {@code SEPAUtil.getAT02Identification} computes it when it writes the file
 *       ({@link SepaCreditorId#build}): the organization's country ISO code, the mod-97 check
 *       digits, the creditor business code and the organization's tax ID. The business code is
 *       the {@code Creditor_Business_Code} parameter of the client's provisioned collection type
 *       ({@link RemittanceSepaProvisioning#creditorBusinessCode}), normalized as Classic does —
 *       whitespace removed, first three characters — and {@value SepaCreditorId#DEFAULT_SUFFIX}
 *       when it is missing or blank ({@link #suffixOf}), so the block shows what the file will
 *       contain.
 *       The organization's country and tax ID are read where Classic reads them
 *       ({@code SEPA_RemittanceCreateFile}): its own organization information, no inheritance from
 *       a parent organization.</li>
 *   <li>{@code missing}: what keeps the file from being generated — {@value #MISSING_IBAN} (no
 *       valid IBAN; the user can type one, which the SPA saves on the account through the
 *       financial-account spec), {@value #MISSING_TAX_ID} and {@value #MISSING_COUNTRY} (the
 *       organization's, fixed in the organization window), and {@value #MISSING_BUSINESS_CODE}
 *       when the stored creditor business code is not three letters or digits.</li>
 * </ul>
 *
 * <p>Plain static utility: the DAL reads are the handler's seams.
 */
final class RemittanceSepaFileData {

  static final String ACTION = "sepaFileData";
  /** ETP-6247: saves the SEPA creditor identifier on the account ({@link #saveContract}). */
  static final String ACTION_SAVE_CREDITOR_ID = "saveCreditorId";
  static final String P_CREDITOR_ID = "creditorId";
  static final String P_REMITTANCE_ID = "remittanceId";
  static final String P_SAVE_TO_ACCOUNT = "saveToAccount";
  /** {@code creditorIdSource}: the identifier is the one saved on the remittance (its snapshot). */
  static final String SOURCE_REMITTANCE = "remittance";
  /** {@code creditorIdSource}: none on the remittance; the account's default is used. */
  static final String SOURCE_ACCOUNT = "account";
  /** {@code creditorIdSource}: none saved; the one computed from the organization is offered. */
  static final String SOURCE_SUGGESTED = "suggested";
  /** {@code creditorIdSource}: none saved and none can be computed. */
  static final String SOURCE_MISSING = "missing";
  static final String P_ACCOUNT_ID = "accountId";
  static final String P_ORGANIZATION_ID = "organizationId";

  static final String MISSING_IBAN = "iban";
  static final String MISSING_TAX_ID = "orgTaxId";
  static final String MISSING_COUNTRY = "country";
  /** Response key of the organization's country (the same word as {@link #MISSING_COUNTRY}). */
  private static final String KEY_COUNTRY = MISSING_COUNTRY;
  static final String MISSING_BUSINESS_CODE = "creditorBusinessCode";

  private static final int BUSINESS_CODE_LENGTH = 3;

  private RemittanceSepaFileData() {
  }

  /** Declares {@value #ACTION} on the remittance header. */
  static void addContract(Map<String, NeoActionContract> contracts) {
    contracts.put(ACTION, NeoActionContract.read(ACTION,
        "The data the SEPA file of a remittance carries about the creditor, for the account the "
            + "remittance uses (it may not be saved yet): returns accountId, accountName, iban "
            + "(the account's, or null), ibanValid (valid for the account's country), "
            + "organizationId, country (ISO code of the organization), creditorId (the SEPA "
            + "creditor identifier, AT-02, computed from the organization's country and tax ID "
            + "with the client's creditor business code, 000 by default; null when it cannot be "
            + "computed), creditorIdAvailable and missing (what is still needed: iban, orgTaxId, "
            + "country; orgTaxId also when the tax ID cannot form an identifier, "
            + "creditorBusinessCode when the stored code is not 3 letters or digits). ETP-6247: the "
            + "creditor identifier is the remittance's own copy, defaulted from the account: "
            + "remittanceCreditorId (null when none, invalid, or the remittance is on another "
            + "account), accountCreditorId (the account's default, null when none or invalid), "
            + "suggestedCreditorId (the one computed from the organization, as above), "
            + "creditorIdSource (remittance | account | suggested | missing) and creditorId, the "
            + "effective one; storedCreditorId / storedCreditorIdValid are the account's. An account that is not a Bank "
            + "account with the remittance payment methods is refused. To save an IBAN, update "
            + "the account (spec financial-account, field iBAN).",
        Param.required(P_ACCOUNT_ID, NeoActionContract.TYPE_STRING,
            "Id of the financial account (the remittance's financialAccount)."),
        Param.optional(P_ORGANIZATION_ID, NeoActionContract.TYPE_STRING,
            "Id of the remittance's organization. Default: the organization of the saved "
                + "remittance, else the account's."),
        Param.optional(P_REMITTANCE_ID, NeoActionContract.TYPE_STRING,
            "Id of the saved remittance whose own creditor identifier is wanted. Default: the "
                + "record id, when it is not new."))
        .withIdDescription("the id of the remittance, or new for one not saved yet"));
    contracts.put(ACTION_SAVE_CREDITOR_ID, saveContract());
  }

  /** Declares {@value #ACTION_SAVE_CREDITOR_ID}, the write that saves the identifier. */
  private static NeoActionContract saveContract() {
    return NeoActionContract.write(ACTION_SAVE_CREDITOR_ID,
        "Saves the SEPA creditor identifier (AT-02). With a saved, not yet confirmed collection "
            + "remittance (remittanceId, or the record id) it is saved on THAT remittance only: its "
            + "file, its regenerations and later account edits use this copy. The financial "
            + "account's default changes only when saveToAccount is true, which is also the only "
            + "write when no remittance is given (record id new): the account form. Upper-cased "
            + "and stripped of spaces and separators, then validated: country code, two mod-97 "
            + "check digits, a three-character business code and the national identifier, at "
            + "most 35 characters (422 ETGO_SepaCreditorIdRequired / InvalidFormat / "
            + "InvalidCheckDigits, with expectedCheckDigits for the last). The account must be a "
            + "Bank account with the remittance payment methods. Returns the sepaFileData answer "
            + "for the account and remittance.",
        Param.required(P_ACCOUNT_ID, NeoActionContract.TYPE_STRING,
            "Id of the financial account (the remittance's financialAccount)."),
        Param.required(P_CREDITOR_ID, NeoActionContract.TYPE_STRING,
            "The creditor identifier, as typed or as computed."),
        Param.optional(P_REMITTANCE_ID, NeoActionContract.TYPE_STRING,
            "Id of the saved remittance to save it on. Default: the record id, when it is not "
                + "new."),
        Param.optional(P_SAVE_TO_ACCOUNT, NeoActionContract.TYPE_BOOLEAN,
            "Also save it as the financial account's default. Required (true) when no "
                + "remittance is given."),
        Param.optional(P_ORGANIZATION_ID, NeoActionContract.TYPE_STRING,
            "Id of the remittance's organization, for the computed identifier of the answer."))
        .withIdDescription("the id of the remittance, or new for the account form");
  }

  /**
   * The creditor business code the file carries, normalized as Classic's {@code CoreDirectDebit}
   * normalizes it: {@value SepaCreditorId#DEFAULT_SUFFIX} when missing or blank, otherwise the
   * value without whitespace, cut to its first three characters.
   *
   * @param businessCode the stored {@code Creditor_Business_Code}, raw
   * @return the suffix to build the creditor identifier with
   */
  static String suffixOf(String businessCode) {
    if (StringUtils.isBlank(businessCode)) {
      return SepaCreditorId.DEFAULT_SUFFIX;
    }
    return StringUtils.left(StringUtils.deleteWhitespace(businessCode), BUSINESS_CODE_LENGTH);
  }

  /**
   * What keeps the file from being generated, in this order: {@value #MISSING_IBAN},
   * {@value #MISSING_TAX_ID}, {@value #MISSING_COUNTRY}, then — when both organization values are
   * present but no identifier could be formed — {@value #MISSING_BUSINESS_CODE} if the stored
   * business code is what cannot form one, otherwise {@value #MISSING_TAX_ID} (a tax ID longer
   * than 28 characters once normalized, or a country code that is not two letters: the
   * organization data must be fixed, reported as the tax ID, the only one of the two a user types).
   */
  private static JSONArray missingOf(boolean ibanValid, String country, String tax,
      String creditorId) {
    JSONArray missing = new JSONArray();
    if (!ibanValid) {
      missing.put(MISSING_IBAN);
    }
    if (tax == null) {
      missing.put(MISSING_TAX_ID);
    }
    if (country == null) {
      missing.put(MISSING_COUNTRY);
    }
    if (creditorId == null && country != null && tax != null) {
      missing.put(SepaCreditorId.build(country, SepaCreditorId.DEFAULT_SUFFIX, tax) != null
          ? MISSING_BUSINESS_CODE
          : MISSING_TAX_ID);
    }
    return missing;
  }

  static boolean serves(String action) {
    return ACTION.equals(action) || ACTION_SAVE_CREDITOR_ID.equals(action);
  }

  /**
   * Builds the answer.
   *
   * @param account        the financial account
   * @param ibanValid      whether its IBAN is valid for its country
   * @param organizationId the organization the creditor identifier is computed for
   * @param countryIso     that organization's country ISO code, or {@code null}
   * @param taxId          that organization's tax ID, or {@code null}
   * @param businessCode   the stored creditor business code, raw, or {@code null}
   * @return the response body
   * @throws JSONException if the JSON cannot be built
   */
  static JSONObject build(FIN_FinancialAccount account, boolean ibanValid, String organizationId,
      String countryIso, String taxId, String businessCode) throws JSONException {
    return build(account, ibanValid, organizationId, countryIso, taxId, businessCode, null);
  }

  /**
   * Builds the answer with the account's default creditor identifier (ETP-6247), no remittance.
   *
   * @see #build(FIN_FinancialAccount, boolean, String, String, String, String, String, String)
   */
  static JSONObject build(FIN_FinancialAccount account, boolean ibanValid, String organizationId,
      String countryIso, String taxId, String businessCode, String accountCreditorId)
      throws JSONException {
    return build(account, ibanValid, organizationId, countryIso, taxId, businessCode,
        accountCreditorId, null);
  }

  /**
   * Builds the answer with the creditor identifier saved on the remittance and on the account
   * (ETP-6247). The effective one is the remittance's when it has a valid one; a remittance value
   * that is present but invalid is not replaced by the account's (the file would be refused, as
   * {@code fileReadiness} says); with none on the remittance, the account's valid one; else the
   * computed one is only suggested.
   *
   * @param accountCreditorId    the account's saved identifier, normalized, valid or not; or
   *                             {@code null} when none
   * @param remittanceCreditorId the remittance's saved identifier (only when it is on that
   *                             account), normalized, valid or not; or {@code null} when none
   * @see #build(FIN_FinancialAccount, boolean, String, String, String, String)
   */
  static JSONObject build(FIN_FinancialAccount account, boolean ibanValid, String organizationId,
      String countryIso, String taxId, String businessCode, String accountCreditorId,
      String remittanceCreditorId) throws JSONException {
    return build(account, ibanValid, organizationId, countryIso, taxId, businessCode,
        accountCreditorId, remittanceCreditorId, null);
  }

  /**
   * Builds the answer with the IBAN saved on the remittance too (ETP-6247). The IBAN of the file
   * is the remittance's own when it has a valid one; a copy that is present but invalid is not
   * replaced by the account's (the file would be refused); with none, the account's valid one.
   * {@code iban} / {@code ibanValid} are the effective ones; the account's own stay as
   * {@code accountIban} / {@code accountIbanValid}.
   *
   * @param ibanValid      whether the ACCOUNT's IBAN is valid for its country
   * @param remittanceIban the remittance's IBAN (only when it is on that account), normalized,
   *                       valid or not; or {@code null} when none
   * @see #build(FIN_FinancialAccount, boolean, String, String, String, String, String, String)
   */
  static JSONObject build(FIN_FinancialAccount account, boolean ibanValid, String organizationId,
      String countryIso, String taxId, String businessCode, String accountCreditorId,
      String remittanceCreditorId, String remittanceIban) throws JSONException {
    String country = StringUtils.trimToNull(countryIso);
    String tax = StringUtils.trimToNull(taxId);
    String creditorId = country != null && tax != null
        ? SepaCreditorId.build(country, suffixOf(businessCode), tax)
        : null;
    String fromAccount = StringUtils.trimToNull(accountCreditorId);
    boolean accountValid = fromAccount != null && SepaCreditorId.isValid(fromAccount);
    String fromRemittance = StringUtils.trimToNull(remittanceCreditorId);
    boolean remittanceValid = fromRemittance != null && SepaCreditorId.isValid(fromRemittance);
    String source;
    String effective;
    if (remittanceValid) {
      source = SOURCE_REMITTANCE;
      effective = fromRemittance;
    } else if (fromRemittance == null && accountValid) {
      source = SOURCE_ACCOUNT;
      effective = fromAccount;
    } else {
      source = creditorId != null ? SOURCE_SUGGESTED : SOURCE_MISSING;
      effective = creditorId;
    }
    String accountIban = StringUtils.trimToNull(account.getIBAN());
    String fromRemittance2 = StringUtils.trimToNull(remittanceIban);
    boolean remittanceIbanValid = fromRemittance2 != null && RemittanceIban.isValid(fromRemittance2);
    String ibanSource;
    String iban;
    boolean effectiveIbanValid;
    if (fromRemittance2 != null) {
      iban = fromRemittance2;
      effectiveIbanValid = remittanceIbanValid;
      ibanSource = remittanceIbanValid ? SOURCE_REMITTANCE : SOURCE_MISSING;
    } else {
      iban = accountIban;
      effectiveIbanValid = ibanValid;
      ibanSource = ibanValid ? SOURCE_ACCOUNT : SOURCE_MISSING;
    }
    JSONArray missing = missingOf(effectiveIbanValid, country, tax, creditorId);

    JSONObject body = new JSONObject();
    body.put(P_ACCOUNT_ID, account.getId());
    body.put("accountName", account.getName());
    body.put("iban", iban != null ? iban : JSONObject.NULL);
    body.put("ibanValid", effectiveIbanValid);
    body.put("ibanSource", ibanSource);
    body.put("remittanceIban", remittanceIbanValid ? fromRemittance2 : JSONObject.NULL);
    body.put("remittanceIbanValid",
        fromRemittance2 != null ? (Object) remittanceIbanValid : JSONObject.NULL);
    body.put("accountIban", accountIban != null ? accountIban : JSONObject.NULL);
    body.put("accountIbanValid", ibanValid);
    body.put(P_ORGANIZATION_ID, organizationId != null ? organizationId : JSONObject.NULL);
    body.put(KEY_COUNTRY, country != null ? country : JSONObject.NULL);
    body.put("remittanceCreditorId", remittanceValid ? fromRemittance : JSONObject.NULL);
    body.put("remittanceCreditorIdValid",
        fromRemittance != null ? (Object) remittanceValid : JSONObject.NULL);
    body.put("accountCreditorId", accountValid ? fromAccount : JSONObject.NULL);
    body.put("accountCreditorIdValid",
        fromAccount != null ? (Object) accountValid : JSONObject.NULL);
    // Aliases of the account's value, from before the remittance kept its own copy.
    body.put("storedCreditorId", accountValid ? fromAccount : JSONObject.NULL);
    body.put("storedCreditorIdValid", fromAccount != null ? (Object) accountValid : JSONObject.NULL);
    body.put("suggestedCreditorId", creditorId != null ? creditorId : JSONObject.NULL);
    body.put("creditorIdSource", source);
    body.put("creditorId", effective != null ? effective : JSONObject.NULL);
    body.put("creditorIdAvailable", effective != null);
    body.put("missing", missing);
    return body;
  }
}
