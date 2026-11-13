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
 *   <li>{@code iban} / {@code ibanValid}: the account's stored IBAN and whether it is valid for the
 *       account's country ({@link RemittanceHeaderHandler#hasValidIban}, the account form's pair
 *       check). No BIC: the SEPA files do not need it.</li>
 *   <li>{@code creditorId}: the SEPA creditor identifier (AT-02), computed the way Classic's
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
            + "creditorBusinessCode when the stored code is not 3 letters or digits). An account that is not a Bank "
            + "account with the remittance payment methods is refused. To save an IBAN, update "
            + "the account (spec financial-account, field iBAN).",
        Param.required(P_ACCOUNT_ID, NeoActionContract.TYPE_STRING,
            "Id of the financial account (the remittance's financialAccount)."),
        Param.optional(P_ORGANIZATION_ID, NeoActionContract.TYPE_STRING,
            "Id of the remittance's organization. Default: the organization of the saved "
                + "remittance, else the account's."))
        .withIdDescription("the id of the remittance, or new for one not saved yet"));
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
    return ACTION.equals(action);
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
    String country = StringUtils.trimToNull(countryIso);
    String tax = StringUtils.trimToNull(taxId);
    String creditorId = country != null && tax != null
        ? SepaCreditorId.build(country, suffixOf(businessCode), tax)
        : null;
    JSONArray missing = missingOf(ibanValid, country, tax, creditorId);
    String iban = StringUtils.trimToNull(account.getIBAN());

    JSONObject body = new JSONObject();
    body.put(P_ACCOUNT_ID, account.getId());
    body.put("accountName", account.getName());
    body.put("iban", iban != null ? iban : JSONObject.NULL);
    body.put("ibanValid", ibanValid);
    body.put(P_ORGANIZATION_ID, organizationId != null ? organizationId : JSONObject.NULL);
    body.put(KEY_COUNTRY, country != null ? country : JSONObject.NULL);
    body.put("creditorId", creditorId != null ? creditorId : JSONObject.NULL);
    body.put("creditorIdAvailable", creditorId != null);
    body.put("missing", missing);
    return body;
  }
}
