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
import org.openbravo.erpCommon.utility.OBCurrencyUtils;
import org.openbravo.model.common.currency.Currency;

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
 * Currency resolution order (via {@code OBCurrencyUtils.getOrgCurrency()}):
 * <ol>
 *   <li>{@code AD_Org.C_Currency_ID} — currency set directly on the organization</li>
 *   <li>Legal entity currency — if the org has no direct currency</li>
 *   <li>Client base currency — ultimate DB fallback</li>
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
   * Resolves the currency ISO code using the same fallback chain as the rest of the ERP.
   *
   * <p>Delegates to {@code OBCurrencyUtils.getOrgCurrency()} which covers:</p>
   * <ol>
   *   <li>{@code AD_Org.C_Currency_ID} — currency set directly on the org</li>
   *   <li>Legal entity currency — if the org has no direct currency</li>
   *   <li>Client base currency — ultimate DB fallback</li>
   * </ol>
   *
   * @param orgId the current organization ID from OBContext
   * @return ISO 4217 code, never null
   */
  private static String resolveCurrencyCode(String orgId) {
    String currencyId = OBCurrencyUtils.getOrgCurrency(orgId);
    if (currencyId == null) {
      log.warn("No currency found for org {} via OBCurrencyUtils — using fallback {}", orgId, FALLBACK_CURRENCY);
      return FALLBACK_CURRENCY;
    }
    Currency currency = OBDal.getInstance().get(Currency.class, currencyId);
    if (currency != null) {
      return currency.getISOCode();
    }
    log.warn("Currency ID {} not found for org {} — using fallback {}", currencyId, orgId, FALLBACK_CURRENCY);
    return FALLBACK_CURRENCY;
  }
}
