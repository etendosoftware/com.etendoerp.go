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
import org.openbravo.model.ad.system.ClientInformation;
import org.openbravo.model.ad.utility.Image;
import org.openbravo.model.common.currency.Currency;
import org.openbravo.model.common.enterprise.OrganizationInformation;
import org.openbravo.model.common.geography.Location;

import com.etendoerp.go.schemaforge.util.NeoAddressHelper;

/**
 * Resolves org-level session defaults for the current OBContext.
 *
 * Exposed at: GET /sws/neo/session
 *
 * Response body:
 * <pre>
 * {
 *   "currencyCode": "EUR",
 *   "yourCompanyDocumentImageId": "A1B2C3...",
 *   "organization": {
 *     "name":     "F&amp;B España, S.A",
 *     "taxId":    "B81639719",
 *     "address1": "Pg. de Gracia, 123 2º-1ª",
 *     "address2": null,
 *     "cityLine": "08009 - Barcelona (BARCELONA)"
 *   }
 * }
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
  private static final String KEY_YOUR_COMPANY_DOCUMENT_IMAGE_ID = "yourCompanyDocumentImageId";
  private static final String KEY_ORGANIZATION = "organization";
  private static final String KEY_ORG_NAME = "name";
  private static final String KEY_ORG_TAXID = "taxId";
  private static final String KEY_ORG_ADDRESS1 = "address1";
  private static final String KEY_ORG_ADDRESS2 = "address2";
  private static final String KEY_ORG_CITY_LINE = "cityLine";

  private NeoSessionService() {
    // utility class — no instances
  }

  /**
   * Returns a NeoResponse containing the functional currency ISO code for the
   * current organization plus lightweight client branding metadata.
   *
   * @return 200 with session defaults for the current org/client context
   */
  public static NeoResponse resolveSession() {
    String orgId = OBContext.getOBContext().getCurrentOrganization().getId();
    String clientId = OBContext.getOBContext().getCurrentClient().getId();
    String currencyCode = FALLBACK_CURRENCY;
    String yourCompanyDocumentImageId = null;
    JSONObject organization = null;

    try {
      OBContext.setAdminMode(true);
      currencyCode = resolveCurrencyCode(orgId);
      yourCompanyDocumentImageId = resolveYourCompanyDocumentImageId(clientId);
      organization = resolveOrganization(orgId);
    } catch (Exception e) {
      log.warn("Could not resolve session defaults for org {} client {}: {}",
          orgId, clientId, e.getMessage());
    } finally {
      OBContext.restorePreviousMode();
    }

    try {
      JSONObject body = new JSONObject();
      body.put(KEY_CURRENCY_CODE, currencyCode);
      body.put(KEY_YOUR_COMPANY_DOCUMENT_IMAGE_ID,
          yourCompanyDocumentImageId != null ? yourCompanyDocumentImageId : JSONObject.NULL);
      body.put(KEY_ORGANIZATION, organization != null ? organization : JSONObject.NULL);
      return NeoResponse.ok(body);
    } catch (JSONException e) {
      log.error("Failed to serialize session response", e);
      return NeoResponse.error(500, "Failed to serialize session response");
    }
  }

  /**
   * Resolves the issuer organization identity (name, tax ID, and formatted
   * address) from the current organization and its {@link OrganizationInformation}.
   *
   * <p>The {@code cityLine} replicates the format produced by Etendo Classic's
   * {@code C_Location_Description} PL/SQL function:
   * {@code "<POSTAL> - <CITY> (<REGION>)"} — with each piece omitted when absent.</p>
   *
   * @param orgId the current organization ID
   * @return a JSON object with name/taxId/address1/address2/cityLine, or {@code null}
   *         when the organization cannot be resolved
   * @throws JSONException on serialization failure
   */
  private static JSONObject resolveOrganization(String orgId) throws JSONException {
    // AD_ORGINFO shares its PK with AD_ORG — direct get avoids a lazy collection load
    OrganizationInformation info = OBDal.getReadOnlyInstance().get(OrganizationInformation.class, orgId);
    if (info == null) {
      return null;
    }

    String taxId = info.getTaxID();
    String address1 = null;
    String address2 = null;
    String cityLine = null;

    Location loc = info.getLocationAddress();
    if (loc != null) {
      address1 = loc.getAddressLine1();
      address2 = loc.getAddressLine2();
      cityLine = NeoAddressHelper.formatCityLine(loc);
    }

    String orgName = info.getOrganization() != null ? info.getOrganization().getName() : null;

    JSONObject out = new JSONObject();
    out.put(KEY_ORG_NAME, orgName != null ? orgName : JSONObject.NULL);
    out.put(KEY_ORG_TAXID, taxId != null ? taxId : JSONObject.NULL);
    out.put(KEY_ORG_ADDRESS1, address1 != null ? address1 : JSONObject.NULL);
    out.put(KEY_ORG_ADDRESS2, address2 != null ? address2 : JSONObject.NULL);
    out.put(KEY_ORG_CITY_LINE, cityLine != null ? cityLine : JSONObject.NULL);
    return out;
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
    Currency currency = OBDal.getReadOnlyInstance().get(Currency.class, currencyId);
    if (currency != null) {
      return currency.getISOCode();
    }
    log.warn("Currency ID {} not found for org {} — using fallback {}", currencyId, orgId, FALLBACK_CURRENCY);
    return FALLBACK_CURRENCY;
  }

  private static String resolveYourCompanyDocumentImageId(String clientId) {
    if (clientId == null || clientId.isBlank()) {
      return null;
    }
    ClientInformation clientInformation = OBDal.getReadOnlyInstance().get(ClientInformation.class, clientId);
    if (clientInformation == null) {
      log.warn("Client information not found for client {}", clientId);
      return null;
    }
    Image documentImage = clientInformation.getYourCompanyDocumentImage();
    return documentImage != null ? documentImage.getId() : null;
  }
}
