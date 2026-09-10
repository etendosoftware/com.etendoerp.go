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

import com.etendoerp.go.portal.PortalAccessService;
import com.etendoerp.go.schemaforge.util.NeoAccessHelper;
import com.etendoerp.webhookevents.services.BaseWebhookService;

/**
 * ETP-5267 — the internal-user side of the Business Partner self-service portal: read whether a
 * Business Partner currently has a live portal link, and revoke it.
 *
 * <p><b>Revocation is the MVP's only kill switch for a leaked link</b> (plan §1), which is why it
 * ships in the MVP and why it is not behind the gate of plan §2.5: a tenant must be able to kill a
 * link that is already out, whatever that gate says. The gate decides whether NEW links go out, not
 * whether existing ones can be stopped.
 *
 * <p><b>Reached through the NEO pseudo-spec bridge</b> ({@code docs/neo-headless.md} §4.10–4.11),
 * not the Webhooks module's {@code SMFWHE_DEFINEDWEBHOOK_ROLE} grant table — that table is reset to
 * its XML-only baseline by {@code update.database}, silently wiping any tenant-specific grant, and
 * the bridge needs only a valid NEO bearer token. Same reasoning as every other
 * Etendo-GO-authored webhook on the bridge. No access check is skipped: the admin/client-admin gate
 * below is this webhook's real boundary either way.
 *
 * <p><b>The tenant is never a parameter.</b> It is read from {@link OBContext}, and every lookup is
 * filtered by it, so an admin of one tenant cannot revoke another tenant's link even by guessing a
 * Business Partner id — a foreign id simply resolves no row and answers
 * {@code {"revoked": false}}. That mirrors the portal's own invariant 1: the scope comes from the
 * validated session, never from the request.
 *
 * <p><b>The token is never returned</b>, not even to an authorised admin. It belongs in the
 * customer's email; putting it in an internal API response would put it in logs, screenshots and
 * browser history. {@code hasActiveLink} is all an operator needs to decide whether there is
 * anything to revoke.
 *
 * <p><b>Endpoint:</b> {@code GET /sws/neo/portalaccess?CBpartnerId=...[&Action=revoke]} — a write
 * action reached as {@code GET} with query parameters, the convention every other
 * Etendo-GO-authored configuration webhook already uses (e.g. {@code SFAssignUserRoles},
 * {@code SFResendInvitation}).
 *
 * <pre>{@code
 * // Action omitted or "status":
 * {"hasActiveLink": true}
 * // Action=revoke:
 * {"hasActiveLink": false, "revoked": true}
 * // not authorised, or no business partner given (still HTTP 200, matching this family's
 * // "don't 500 a validation rejection" convention):
 * {"error": true, "message": "..."}
 * }</pre>
 */
public class SFPortalAccess extends BaseWebhookService {

  private static final Logger log = LogManager.getLogger(SFPortalAccess.class);

  private static final String PARAM_BPARTNER_ID = "CBpartnerId";
  private static final String PARAM_ACTION = "Action";
  private static final String ACTION_REVOKE = "revoke";

  private static final String RESPONSE_VAR_RESULT = "result";
  private static final String FIELD_ERROR = "error";
  private static final String FIELD_MESSAGE = "message";
  private static final String FIELD_HAS_ACTIVE_LINK = "hasActiveLink";
  private static final String FIELD_REVOKED = "revoked";

  private final PortalAccessService service;

  /**
   * No-arg constructor the webhook engine instantiates in production; delegates to the
   * package-private one so a test can inject a mock service.
   */
  public SFPortalAccess() {
    this(new PortalAccessService());
  }

  SFPortalAccess(PortalAccessService service) {
    this.service = service;
  }

  @Override
  public void get(Map<String, String> parameter, Map<String, String> responseVars) {
    Role currentRole = NeoAccessHelper.resolveCurrentRole();
    if (currentRole == null || !NeoAccessHelper.isAdminOrClientAdmin(currentRole)) {
      responseVars.put(RESPONSE_VAR_RESULT, rejected("Not authorized").toString());
      return;
    }
    String bpartnerId = StringUtils.trimToNull(parameter.get(PARAM_BPARTNER_ID));
    if (bpartnerId == null) {
      responseVars.put(RESPONSE_VAR_RESULT,
          rejected(PARAM_BPARTNER_ID + " is required").toString());
      return;
    }
    String clientId = resolveCurrentClientId();
    if (clientId == null) {
      responseVars.put(RESPONSE_VAR_RESULT, rejected("No tenant in context").toString());
      return;
    }
    try {
      boolean revoke = ACTION_REVOKE.equalsIgnoreCase(
          StringUtils.trimToEmpty(parameter.get(PARAM_ACTION)));
      JSONObject result = new JSONObject();
      if (revoke) {
        result.put(FIELD_REVOKED, service.revoke(clientId, bpartnerId));
      }
      result.put(FIELD_HAS_ACTIVE_LINK, service.hasActiveAccess(clientId, bpartnerId));
      responseVars.put(RESPONSE_VAR_RESULT, result.toString());
    } catch (JSONException e) {
      log.error("Error building SFPortalAccess response for business partner {}", bpartnerId, e);
      responseVars.put(FIELD_ERROR, e.getMessage());
    } catch (RuntimeException e) {
      log.error("Unexpected error in SFPortalAccess for business partner {}", bpartnerId, e);
      responseVars.put(FIELD_ERROR, e.getMessage());
    }
  }

  private static String resolveCurrentClientId() {
    OBContext ctx = OBContext.getOBContext();
    if (ctx == null || ctx.getCurrentClient() == null) {
      return null;
    }
    return ctx.getCurrentClient().getId();
  }

  private static JSONObject rejected(String message) {
    try {
      JSONObject result = new JSONObject();
      result.put(FIELD_ERROR, true);
      result.put(FIELD_MESSAGE, message);
      return result;
    } catch (JSONException e) {
      throw new IllegalStateException("Unable to build rejected result", e);
    }
  }
}
