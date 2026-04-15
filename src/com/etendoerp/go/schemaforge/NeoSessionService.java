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
 * All portions are Copyright © 2021–2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.schemaforge;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.currency.Currency;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.financialmgmt.accounting.coa.AcctSchema;

/**
 * Resolves org-level session defaults for the current OBContext.
 *
 * Exposed at: GET /sws/neo/session
 *
 * Response body:
 * <pre>
 * { "currencyCode": "EUR" }
 * </pre>
 *
 * Currency resolution order:
 * <ol>
 *   <li>{@code AD_Org.C_Currency_ID} — the currency set directly on the organization
 *       (this is what users see and configure in the Organization window)</li>
 *   <li>{@code OrganizationAcctSchema → AcctSchema.C_Currency_ID} — the functional
 *       currency from the accounting schema, used as fallback for orgs without a
 *       direct currency assignment</li>
 *   <li>{@code "USD"} — ultimate fallback</li>
 * </ol>
 *
 * Frontend components that do not have a document record (dashboards, sidebars) should
 * call this endpoint once per session to resolve the org's functional currency, rather
 * than parsing it from document-type defaults endpoints.
 */
public class NeoSessionService {

  private static final Logger log = LogManager.getLogger(NeoSessionService.class);
  private static final String FALLBACK_CURRENCY = "USD";
  private static final String KEY_CURRENCY_CODE = "currencyCode";

  private NeoSessionService() {
    // utility class — no instances
  }

  /**
   * Returns a NeoResponse containing the functional currency ISO code for the
   * current organization.
   *
   * @return 200 with {@code {"currencyCode":"EUR"}} (or fallback if unresolvable)
   */
  public static NeoResponse resolveSession() {
    String orgId = OBContext.getOBContext().getCurrentOrganization().getId();
    String currencyCode = FALLBACK_CURRENCY;

    try {
      OBContext.setAdminMode(true);
      currencyCode = resolveCurrencyCode(orgId);
    } catch (Exception e) {
      log.warn("Could not resolve currency for org {}: {}", orgId, e.getMessage());
    } finally {
      OBContext.restorePreviousMode();
    }

    try {
      JSONObject body = new JSONObject();
      body.put(KEY_CURRENCY_CODE, currencyCode);
      return NeoResponse.ok(body);
    } catch (JSONException e) {
      log.error("Failed to serialize session response", e);
      return NeoResponse.error(500, "Failed to serialize session response");
    }
  }

  /**
   * Resolves the currency ISO code using the priority order documented on the class.
   *
   * @param orgId the current organization ID from OBContext
   * @return ISO 4217 code, never null
   */
  private static String resolveCurrencyCode(String orgId) {
    // 1. Direct org currency — AD_Org.C_Currency_ID (shown in Organization window)
    Organization org = OBDal.getInstance().get(Organization.class, orgId);
    if (org != null) {
      Currency orgCurrency = org.getCurrency();
      if (orgCurrency != null) {
        return orgCurrency.getISOCode();
      }
    }

    // 2. Fallback: functional currency from the accounting schema
    AcctSchema schema = OBDal.getInstance()
        .createQuery(AcctSchema.class,
            "exists (from OrganizationAcctSchema oas"
                + " where oas.accountingSchema = this"
                + " and oas.organization.id = :orgId"
                + " and oas.active = true)"
                + " and active = true")
        .setNamedParameter("orgId", orgId)
        .setMaxResult(1)
        .uniqueResult();

    if (schema != null && schema.getCurrency() != null) {
      return schema.getCurrency().getISOCode();
    }

    // 3. Ultimate fallback
    log.warn("No currency found for org {} — using fallback {}", orgId, FALLBACK_CURRENCY);
    return FALLBACK_CURRENCY;
  }
}
