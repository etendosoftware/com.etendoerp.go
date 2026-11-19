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

import java.util.List;
import java.util.Map;

import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.core.OBContext;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.common.enterprise.OrganizationInformation;
import org.openbravo.model.common.geography.Location;
import org.openbravo.model.financialmgmt.payment.FIN_FinancialAccount;
import org.openbravo.module.remittance.Remittance;

import com.etendoerp.go.schemaforge.util.NeoActionContract;

/**
 * ETP-6148 — the run of the remittance header's {@code sepaFileData} read action
 * ({@link RemittanceSepaFileData} builds the answer). Split out of
 * {@link RemittanceHeaderHandler}; the account, remittance and organization lookups, the account
 * rule and the creditor business code are still read through the handler's seams.
 */
final class RemittanceSepaFileDataAction {

  private static final Logger log = LogManager.getLogger(RemittanceSepaFileDataAction.class);

  private static final int SC_UNPROCESSABLE = 422;
  /** Save on the account form without {@code saveToAccount}: there is nothing else to write. */
  static final String MSG_NEEDS_ACCOUNT = "ETGO_RemittanceCreditorIdNeedsAccount";
  /** Save for a remittance that is not a collection or is on another account. */
  static final String MSG_NOT_APPLICABLE = "ETGO_RemittanceCreditorIdNotApplicable";

  private RemittanceSepaFileDataAction() {
  }

  /**
   * «Datos para el fichero SEPA»: reads the account named by the {@code accountId} parameter,
   * and reads a matching saved remittance for its snapshot. It also answers for a remittance that is not saved yet (record id
   * {@code new}). 404 for an account or organization the tenant does not own; 422 with the
   * account's refusal when it is not a Bank account with the remittance payment methods. An
   * account without a valid IBAN is answered, with {@code iban} in {@code missing}: the block is
   * where the user gives it one.
   *
   * @param handler the remittance header customization (its seams)
   * @param context the action request
   * @return the answer
   */
  static NeoResponse handle(RemittanceHeaderHandler handler, NeoContext context) {
    String action = context.getFieldName();
    boolean save = RemittanceSepaFileData.ACTION_SAVE_CREDITOR_ID.equals(action);
    if (save && !"POST".equals(context.getHttpMethod())) {
      return NeoResponse.error(HttpServletResponse.SC_METHOD_NOT_ALLOWED,
          action + " only supports POST");
    }
    OBContext.setAdminMode(true);
    try {
      JSONObject params = context.getRequestBody() != null ? context.getRequestBody()
          : paramsFromQuery(RemittanceRequestParams.of(context, handler::currentRequest,
              RemittanceSepaFileData.P_ACCOUNT_ID, RemittanceSepaFileData.P_ORGANIZATION_ID,
              RemittanceSepaFileData.P_REMITTANCE_ID));
      NeoResponse invalid = NeoActionContract.validate(handler.actionContracts(), action, params);
      if (invalid != null) {
        return invalid;
      }
      FIN_FinancialAccount account =
          handler.loadAccount(params.optString(RemittanceSepaFileData.P_ACCOUNT_ID, null));
      if (account == null) {
        return NeoResponse.error(HttpServletResponse.SC_NOT_FOUND, "Financial account not found");
      }
      String refusal = handler.eligibilityRefusal(account);
      if (refusal != null) {
        return NeoMessageText.error(SC_UNPROCESSABLE, refusal);
      }
      String remittanceRef = remittanceRef(context, params);
      Remittance remittance = remittanceRef != null ? handler.loadRemittance(remittanceRef) : null;
      boolean saveToAccount = params.optBoolean(RemittanceSepaFileData.P_SAVE_TO_ACCOUNT, false);
      if (save) {
        NeoResponse refusal2 = saveRefusal(handler, remittanceRef, remittance, account,
            saveToAccount);
        if (refusal2 != null) {
          return refusal2;
        }
      }
      // The remittance's own copy only means something on the account it is on.
      boolean sameAccount = remittance != null && account.getId().equals(
          RemittanceHeaderHandler.idOf(remittance.getFinancialAccount()));
      OrganizationChoice choice = resolveOrganization(handler, remittance, params, account);
      if (choice.unknown) {
        return NeoResponse.error(HttpServletResponse.SC_NOT_FOUND, "Organization not found");
      }
      if (save) {
        NeoResponse refused = saveCreditorId(handler, remittance, account, saveToAccount,
            params.optString(RemittanceSepaFileData.P_CREDITOR_ID, null));
        if (refused != null) {
          return refused;
        }
      }
      return NeoResponse.ok(body(handler, account, choice.organization, remittance, sameAccount));
    } catch (JSONException | RuntimeException e) {
      log.error("remittance action {} failed for account", action, e);
      if (save) {
        NeoErrorResponses.rollbackUnlessBatchOwned();
      }
      return NeoResponse.error(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
          "Could not run " + action + ". Please check logs for details.");
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /**
   * The saved remittance a call is about: the {@code remittanceId} parameter, else the record id
   * unless it is the {@code new} sentinel; {@code null} for none.
   */
  private static String remittanceRef(NeoContext context, JSONObject params) {
    String ref = StringUtils.trimToNull(
        params.optString(RemittanceSepaFileData.P_REMITTANCE_ID, null));
    if (ref != null) {
      return ref;
    }
    String record = StringUtils.trimToNull(context.getRecordId());
    return record == null || RemittanceHeaderHandler.NEW_RECORD_ID.equals(record) ? null : record;
  }

  /**
   * The refusal of a {@value RemittanceSepaFileData#ACTION_SAVE_CREDITOR_ID} before anything is
   * written, or {@code null}. Without a remittance (the account form) the write is the account's
   * and must be asked for ({@code saveToAccount}); with one, it must exist (404), be a collection
   * still not processed (its file and regenerations use the saved copy) and be on that account.
   * The row is locked first so the check and the write see the same remittance.
   */
  private static NeoResponse saveRefusal(RemittanceHeaderHandler handler, String remittanceRef,
      Remittance remittance, FIN_FinancialAccount account, boolean saveToAccount) {
    if (remittanceRef == null) {
      return saveToAccount ? null
          : NeoMessageText.error(SC_UNPROCESSABLE, MSG_NEEDS_ACCOUNT);
    }
    if (remittance == null) {
      return NeoResponse.error(HttpServletResponse.SC_NOT_FOUND, "Remittance not found");
    }
    handler.lockRemittance(remittance);
    if (Boolean.TRUE.equals(remittance.isProcessed())) {
      return NeoMessageText.error(SC_UNPROCESSABLE, RemittanceHeaderHandler.MSG_PROCESSED);
    }
    boolean sameAccount = account.getId().equals(
        RemittanceHeaderHandler.idOf(remittance.getFinancialAccount()));
    return sameAccount && isCollection(remittance) ? null
        : NeoMessageText.error(SC_UNPROCESSABLE, MSG_NOT_APPLICABLE);
  }

  /**
   * ETP-6247 — {@value RemittanceSepaFileData#ACTION_SAVE_CREDITOR_ID}: validates the identifier
   * ({@link SepaCreditorId#validate}: upper-cased, separators removed, country + two mod-97 check
   * digits + three-character business code + national identifier, at most 35 characters) and saves
   * it on the remittance and, on explicit request, the account.
   *
   * @return the 422 refusal, or {@code null} once saved
   */
  private static NeoResponse saveCreditorId(RemittanceHeaderHandler handler,
      Remittance remittance, FIN_FinancialAccount account, boolean saveToAccount, String raw) throws JSONException {
    SepaCreditorId.Result result = SepaCreditorId.validate(raw);
    if (!result.isValid()) {
      NeoResponse refusal = NeoMessageText.error(SC_UNPROCESSABLE,
          result.getReason().getMessageKey());
      JSONObject error = refusal.getBody() != null ? refusal.getBody().optJSONObject("error")
          : null;
      if (error != null && result.getExpectedCheckDigits() != null) {
        error.put("expectedCheckDigits", result.getExpectedCheckDigits());
      }
      return refusal;
    }
    if (remittance != null) {
      handler.storeRemittanceCreditorId(remittance, result.getNormalized());
    }
    if (saveToAccount || remittance == null) {
      handler.storeCreditorId(account, result.getNormalized());
    }
    return null;
  }

  /**
   * The organization the creditor identifier is computed for: the {@code organizationId}
   * parameter when sent (unknown to the tenant: {@link OrganizationChoice#unknown}), otherwise the
   * saved remittance's, otherwise the account's.
   */
  private static OrganizationChoice resolveOrganization(RemittanceHeaderHandler handler,
      Remittance stored, JSONObject params, FIN_FinancialAccount account) {
    String orgId = StringUtils.trimToNull(
        params.optString(RemittanceSepaFileData.P_ORGANIZATION_ID, null));
    if (orgId != null) {
      Organization organization = handler.loadOrganization(orgId);
      return organization != null ? new OrganizationChoice(organization, false)
          : new OrganizationChoice(null, true);
    }
    return new OrganizationChoice(
        stored != null ? stored.getOrganization() : account.getOrganization(), false);
  }

  /** The answer for {@code account} and {@code organization} (may be null). */
  private static JSONObject body(RemittanceHeaderHandler handler, FIN_FinancialAccount account,
      Organization organization, Remittance remittance, boolean sameAccount) throws JSONException {
    OrganizationInformation info = organization != null
        ? handler.loadOrganizationInfo(organization)
        : null;
    JSONObject response = RemittanceSepaFileData.build(account,
        RemittanceHeaderHandler.hasValidIban(account),
        organization != null ? organization.getId() : null, countryIsoOf(info),
        info != null ? info.getTaxID() : null, handler.creditorBusinessCode(account),
        handler.storedCreditorId(account),
        sameAccount ? RemittanceCreditorId.snapshot(remittance) : null,
        sameAccount ? RemittanceIban.stored(remittance) : null);
    response.put("collection", remittance == null ? JSONObject.NULL : isCollection(remittance));
    boolean collection = remittance != null && isCollection(remittance);
    response.put(RemittanceHeaderHandler.FIELD_SCHEME,
        collection ? RemittanceSepaOptions.scheme(remittance) : JSONObject.NULL);
    response.put(RemittanceHeaderHandler.FIELD_FINANCED,
        collection && RemittanceSepaOptions.financed(remittance));
    return response;
  }

  private static boolean isCollection(Remittance remittance) {
    return RemittanceSepaProvisioning.SCHEME_CORE.equals(
        RemittanceSepaFileSetup.schemeOf(remittance.getRemittanceType()));
  }

  private static JSONObject paramsFromQuery(Map<String, String> query) throws JSONException {
    JSONObject params = new JSONObject();
    for (String key : List.of(RemittanceSepaFileData.P_ACCOUNT_ID,
        RemittanceSepaFileData.P_ORGANIZATION_ID, RemittanceSepaFileData.P_REMITTANCE_ID)) {
      String value = query != null ? StringUtils.trimToNull(query.get(key)) : null;
      if (value != null) {
        params.put(key, value);
      }
    }
    return params;
  }

  /**
   * The organization's country ISO code, where Classic's SEPA file reads it, or {@code null}.
   *
   * @param info the organization's own information, may be {@code null}
   * @return the country ISO code, or {@code null}
   */
  static String countryIsoOf(OrganizationInformation info) {
    Location location = info != null ? info.getLocationAddress() : null;
    return location != null && location.getCountry() != null
        ? location.getCountry().getISOCountryCode()
        : null;
  }

  /** The outcome of {@link #resolveOrganization}: the organization, or an unknown one asked for. */
  static final class OrganizationChoice {
    final Organization organization;
    final boolean unknown;

    OrganizationChoice(Organization organization, boolean unknown) {
      this.organization = organization;
      this.unknown = unknown;
    }
  }
}
