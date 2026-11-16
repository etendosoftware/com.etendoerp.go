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

  private RemittanceSepaFileDataAction() {
  }

  /**
   * «Datos para el fichero SEPA»: reads the account named by the {@code accountId} parameter,
   * never the remittance, so it answers for a remittance that is not saved yet (record id
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
    OBContext.setAdminMode(true);
    try {
      JSONObject params = context.getRequestBody() != null ? context.getRequestBody()
          : paramsFromQuery(RemittanceRequestParams.of(context, handler::currentRequest,
              RemittanceSepaFileData.P_ACCOUNT_ID, RemittanceSepaFileData.P_ORGANIZATION_ID));
      NeoResponse invalid = NeoActionContract.validate(handler.actionContracts(),
          RemittanceSepaFileData.ACTION, params);
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
      OrganizationChoice choice = resolveOrganization(handler, context, params, account);
      if (choice.unknown) {
        return NeoResponse.error(HttpServletResponse.SC_NOT_FOUND, "Organization not found");
      }
      return NeoResponse.ok(body(handler, account, choice.organization));
    } catch (JSONException | RuntimeException e) {
      log.error("remittance action {} failed for account", RemittanceSepaFileData.ACTION, e);
      return NeoResponse.error(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
          "Could not run " + RemittanceSepaFileData.ACTION + ". Please check logs for details.");
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /**
   * The organization the creditor identifier is computed for: the {@code organizationId}
   * parameter when sent (unknown to the tenant: {@link OrganizationChoice#unknown}), otherwise the
   * saved remittance's, otherwise the account's.
   */
  private static OrganizationChoice resolveOrganization(RemittanceHeaderHandler handler,
      NeoContext context, JSONObject params, FIN_FinancialAccount account) {
    String orgId = StringUtils.trimToNull(
        params.optString(RemittanceSepaFileData.P_ORGANIZATION_ID, null));
    if (orgId != null) {
      Organization organization = handler.loadOrganization(orgId);
      return organization != null ? new OrganizationChoice(organization, false)
          : new OrganizationChoice(null, true);
    }
    // The SPA sends the sentinel id "new" for a remittance that is not saved yet.
    Remittance stored = RemittanceHeaderHandler.NEW_RECORD_ID.equals(context.getRecordId())
        ? null
        : handler.loadRemittance(context.getRecordId());
    return new OrganizationChoice(
        stored != null ? stored.getOrganization() : account.getOrganization(), false);
  }

  /** The answer for {@code account} and {@code organization} (may be null). */
  private static JSONObject body(RemittanceHeaderHandler handler, FIN_FinancialAccount account,
      Organization organization) throws JSONException {
    OrganizationInformation info = organization != null
        ? handler.loadOrganizationInfo(organization)
        : null;
    return RemittanceSepaFileData.build(account, RemittanceHeaderHandler.hasValidIban(account),
        organization != null ? organization.getId() : null, countryIsoOf(info),
        info != null ? info.getTaxID() : null, handler.creditorBusinessCode(account));
  }

  private static JSONObject paramsFromQuery(Map<String, String> query) throws JSONException {
    JSONObject params = new JSONObject();
    for (String key : List.of(RemittanceSepaFileData.P_ACCOUNT_ID,
        RemittanceSepaFileData.P_ORGANIZATION_ID)) {
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
