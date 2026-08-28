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
 * All portions are Copyright (C) 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */
package com.etendoerp.go.schemaforge.webhooks;

import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.core.OBContext;
import org.openbravo.model.ad.access.Role;

import com.etendoerp.go.rest.CompanyInvitationService;
import com.etendoerp.go.schemaforge.util.NeoAccessHelper;
import com.etendoerp.webhookevents.services.BaseWebhookService;

/**
 * ETP-4830 (item #2) — admin-triggered "Resend invitation" webhook, backing the detail-header
 * button next to the invitation status pill on the {@code user} window. Re-issues a fresh
 * invitation for the given {@code AD_User_ID} regardless of whether the current one is still
 * valid; see {@link CompanyInvitationService#resendInvitation} for the full eligibility and
 * revoke-then-reissue rules. All logic lives there (this class is only a parameter-marshalling +
 * access-gating shim, the same split every other Etendo-GO-authored webhook on this bridge uses).
 *
 * <p><b>Reached through the NEO pseudo-spec bridge</b> ({@code docs/neo-headless.md} §4.10–4.11),
 * not the Webhooks module's {@code SMFWHE_DEFINEDWEBHOOK_ROLE} grant table — same reasoning as
 * every other Etendo-GO-authored webhook on this bridge.</p>
 *
 * <p>Unlike {@code SFDebugInvitationBypass}, this is a real, always-on production feature — no
 * dev-only feature flag. The {@link #get} method's own admin/client-admin role check below is the
 * real access boundary; {@link CompanyInvitationService#resendInvitation} additionally scopes the
 * target user to the caller's own client server-side.</p>
 *
 * <p><b>Endpoint:</b> {@code GET /sws/neo/resendinvitation?AdUserId=...} (a write action reached
 * as {@code GET} with query parameters — the same convention every other Etendo-GO-authored
 * configuration webhook already uses, e.g. {@code SFAssignUserRoles}).</p>
 *
 * <pre>{@code
 * // success:
 * {"status": "success", "invitation": {"id": "...", "email": "...", "status": "SENT", "expiresAt": "..."}}
 * // validation failure (still HTTP 200, matching SFAssignUserRoles's own
 * // "don't 500 a validation rejection" convention):
 * {"error": true, "code": "...", "message": "...", "httpStatus": 400}
 * }</pre>
 */
public class SFResendInvitation extends BaseWebhookService {

  private static final Logger log = LogManager.getLogger(SFResendInvitation.class);

  private static final String PARAM_AD_USER_ID = "AdUserId";
  /**
   * Operator locale. Optional: without it the email falls back to Spanish, which is right for most
   * tenants but ignores the locale the operator is actually working in (ETP-5003).
   */
  private static final String PARAM_LANGUAGE = "Language";

  private static final String RESPONSE_VAR_RESULT = "result";
  private static final String FIELD_ERROR = "error";
  private static final String FIELD_MESSAGE = "message";

  private final CompanyInvitationService service;

  /**
   * No-arg constructor the webhook engine actually instantiates in production; delegates to the
   * package-private constructor below with a real {@link CompanyInvitationService} so tests can
   * inject a mock instead.
   */
  public SFResendInvitation() {
    this(new CompanyInvitationService());
  }

  SFResendInvitation(CompanyInvitationService service) {
    this.service = service;
  }

  @Override
  public void get(Map<String, String> parameter, Map<String, String> responseVars) {
    Role currentRole = NeoAccessHelper.resolveCurrentRole();
    if (currentRole == null || !NeoAccessHelper.isAdminOrClientAdmin(currentRole)) {
      responseVars.put(RESPONSE_VAR_RESULT, denied().toString());
      return;
    }

    String userId = StringUtils.trimToEmpty(parameter.get(PARAM_AD_USER_ID));
    try {
      OBContext obContext = OBContext.getOBContext();
      String language = StringUtils.trimToNull(parameter.get(PARAM_LANGUAGE));
      JSONObject result = service.resendInvitation(obContext, userId, null, language);
      responseVars.put(RESPONSE_VAR_RESULT, result.toString());
    } catch (JSONException e) {
      log.error("Error building SFResendInvitation response for AdUserId {}", userId, e);
      responseVars.put(FIELD_ERROR, e.getMessage());
    } catch (RuntimeException e) {
      log.error("Unexpected error in SFResendInvitation for AdUserId {}", userId, e);
      responseVars.put(FIELD_ERROR, e.getMessage());
    }
  }

  private JSONObject denied() {
    try {
      JSONObject result = new JSONObject();
      result.put(FIELD_ERROR, true);
      result.put(FIELD_MESSAGE, "Not authorized");
      return result;
    } catch (JSONException e) {
      throw new IllegalStateException("Unable to build denied result", e);
    }
  }
}
