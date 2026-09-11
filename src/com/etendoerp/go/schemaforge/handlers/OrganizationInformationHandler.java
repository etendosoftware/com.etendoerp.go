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

package com.etendoerp.go.schemaforge.handlers;

import javax.inject.Named;

import org.apache.commons.lang3.StringUtils;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.enterprise.Organization;

import com.etendoerp.go.common.SpanishTaxIdValidator;
import com.etendoerp.go.schemaforge.NeoContext;
import com.etendoerp.go.schemaforge.NeoEndpointType;
import com.etendoerp.go.schemaforge.NeoHandler;
import com.etendoerp.go.schemaforge.NeoResponse;

/**
 * NeoHandler for the {@code organization} spec's {@code information} entity (table
 * {@code AD_OrgInfo}) that rejects a malformed tax identifier before it is stored (ETP-5190).
 *
 * <p>This is the second of the two moments a tenant sets its own fiscal identifier — the first
 * is the signup wizard, guarded in {@code EtendoGoJwtServlet#parseOnboardingRequest}. Both run
 * {@link SpanishTaxIdValidator}, so the same value is judged identically wherever it is
 * entered, and {@code lib/taxIdValidation.js} runs the same three rules in the browser so the
 * user is told before the round trip rather than after it.
 *
 * <p><b>Why the window needs its own guard at all.</b> Nothing in classic validates
 * {@code AD_OrgInfo.TaxID} — see {@link SpanishTaxIdValidator} for what the localization
 * modules do and do not check. Without this, a NIF corrected on this screen to a wrong value
 * would be stored silently and only surface when the tenant next tried to complete an invoice.
 *
 * <p><b>PATCH matters here.</b> The Organization screen saves through
 * {@code PATCH /sws/neo/organization/information/{orgId}} (see {@code useOrganizationData.js}),
 * not POST or PUT, so a guard that only covered the create verbs would never run on the one
 * path a user actually takes.
 *
 * <p>Applied only when the organization's country is Spain ({@link OrganizationCountrySupport}),
 * for the same reason as the invoice-prefix rules: these are localization rules, and a tenant
 * whose country cannot be established must not have them imposed on it.
 *
 * <p>{@code @Named} only — never a normal CDI scope. See CLAUDE.md §NeoHandler Pattern and
 * {@code docs/neo-headless-extensibility.md} §2.2: a scoped bean resolves to a Weld client
 * proxy whose subclass does not carry the (non-{@code @Inherited}) annotation, so the qualifier
 * silently stops being discovered.
 */
@Named("organization-information")
public class OrganizationInformationHandler implements NeoHandler {

  /** Contract field name of {@code AD_OrgInfo.TaxID} (see the window's decisions.json). */
  static final String FIELD_TAX_ID = "taxID";

  private static final String METHOD_POST = "POST";
  private static final String METHOD_PUT = "PUT";
  private static final String METHOD_PATCH = "PATCH";

  /**
   * Pre-hook: validates the submitted tax identifier on create/update and short-circuits with
   * a {@code 400} when it is malformed.
   *
   * @param context the NEO request context
   * @return an error {@link NeoResponse} when the value is rejected, {@code null} to continue
   *     with the default CRUD
   */
  @Override
  public NeoResponse handle(NeoContext context) {
    if (context == null || context.getEndpointType() != NeoEndpointType.CRUD) {
      return null;
    }
    if (!isWrite(context.getHttpMethod())) {
      return null;
    }
    JSONObject body = context.getRequestBody();
    if (body == null || !body.has(FIELD_TAX_ID)) {
      // A PATCH that only touches the address must not be re-validated against a tax ID it is
      // not sending — otherwise an already-stored bad value would block every later edit,
      // including the edit that fixes it.
      return null;
    }
    String taxId = StringUtils.trimToNull(body.optString(FIELD_TAX_ID, null));
    if (taxId == null) {
      // Clearing the field is the `required` mechanism's business, not the format rules'.
      return null;
    }
    if (!OrganizationCountrySupport.isSpain(resolveOrganization(context))) {
      return null;
    }
    switch (SpanishTaxIdValidator.validate(taxId)) {
      case BAD_FORMAT:
        return NeoResponse.error(400, SpanishTaxIdValidator.ERR_FORMAT);
      case BAD_CHECK_DIGIT:
        return NeoResponse.error(400, SpanishTaxIdValidator.ERR_CHECK_DIGIT);
      default:
        return null;
    }
  }

  /**
   * The organization whose country decides whether the rules apply: the one being edited, not
   * the session's. `AD_OrgInfo` is a 1:1 extension of `AD_Org` keyed by `AD_Org_ID`, so the
   * record id in `PATCH /information/{orgId}` IS the organization id.
   *
   * <p>Falls back to the session organization when there is no record id (a create), which is
   * the only organization a create could be for anyway.
   */
  private Organization resolveOrganization(NeoContext context) {
    String recordId = StringUtils.trimToNull(context.getRecordId());
    if (recordId != null) {
      OBContext.setAdminMode(true);
      try {
        Organization organization = OBDal.getInstance().get(Organization.class, recordId);
        if (organization != null) {
          return organization;
        }
      } finally {
        OBContext.restorePreviousMode();
      }
    }
    OBContext obContext = context.getObContext();
    return obContext == null ? null : obContext.getCurrentOrganization();
  }

  private static boolean isWrite(String method) {
    return METHOD_POST.equalsIgnoreCase(method)
        || METHOD_PUT.equalsIgnoreCase(method)
        || METHOD_PATCH.equalsIgnoreCase(method);
  }
}
