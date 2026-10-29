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
 * All portions are Copyright (C) 2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */
package com.etendoerp.go.supportaccess.classic;

import java.util.Map;
import java.util.function.Supplier;

import javax.servlet.http.HttpServletRequest;

import org.apache.commons.lang3.StringUtils;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.client.kernel.KernelConstants;

import com.etendoerp.go.common.PublicUrlResolver;
import com.etendoerp.go.supportaccess.IssuedSupportTicket;
import com.etendoerp.go.supportaccess.SupportAccessException;
import com.etendoerp.go.supportaccess.SupportAccessService;

/**
 * ETP-5351 — "Access as Support" ("Acceder como soporte"): opens a support access to the selected
 * company and makes Classic open Etendo GO, with the single-use pass, in a new tab.
 *
 * <p><b>How the tab opens.</b> Process definitions have no built-in "open URL" response action.
 * The response uses the core {@code custom} client action ({@code ob-utilities-action-def.js}),
 * which runs a script: {@code window.open(url, '_blank', 'noopener,noreferrer')}. The same
 * response shows a success message with the link, in case the browser blocks the pop-up. The URL
 * is checked to be plain {@code http(s)} and is JSON-quoted and HTML-escaped before it is
 * embedded.</p>
 *
 * <p>Nothing is written when the GO app URL is not configured: without it the pass would be
 * useless and the company would stay held until the pass expires.</p>
 */
public class SupportAccessIssueActionHandler extends SupportAccessActionHandler {

  private static final String HEADER_USER_AGENT = "User-Agent";

  /** Production wiring. */
  public SupportAccessIssueActionHandler() {
    super();
  }

  /**
   * Explicit wiring, for tests.
   *
   * @param serviceFactory creates the service used by one execution
   */
  SupportAccessIssueActionHandler(Supplier<SupportAccessService> serviceFactory) {
    super(serviceFactory);
  }

  @Override
  protected JSONObject handle(SupportAccessService service, JSONObject request,
      Map<String, Object> parameters) throws JSONException {
    JSONObject params = request.optJSONObject(PARAMS);
    String reason = stringParam(params, SupportAccessClassicMetadata.PARAM_REASON);
    Integer minutes = durationParam(params);
    String clientId = selectedClientId(request);
    if (clientId == null) {
      throw new SupportAccessException(SupportAccessException.CODE_TARGET_NOT_ELIGIBLE,
          "No company selected");
    }
    if (!isAppUrlConfigured()) {
      return error(translate(SupportAccessMessages.MSG_APP_URL_MISSING));
    }

    HttpServletRequest http = httpRequest(parameters);
    String operatorId = currentUserId();
    IssuedSupportTicket ticket = service.issue(operatorId, clientId, reason, minutes,
        http == null ? null : http.getRemoteAddr(),
        http == null ? null : http.getHeader(HEADER_USER_AGENT));
    String url = ticket.getUrl();
    if (!SupportAccessMessages.isSafeHttpUrl(url)) {
      // The URL setting vanished or is not http(s) between the check and the issue: free the
      // company now instead of leaving it held by a pass nobody can use.
      service.revoke(ticket.getAccessId(), operatorId);
      commit();
      return error(translate(SupportAccessMessages.MSG_APP_URL_MISSING));
    }
    commit();

    String link = SupportAccessMessages.link(url, translate(SupportAccessMessages.MSG_ACCESS_LINK,
        String.valueOf(SupportAccessService.TICKET_TTL_SECONDS)));
    String text = translate(SupportAccessMessages.MSG_ACCESS_ISSUED,
        String.valueOf(ticket.getDurationMinutes()), link);
    JSONObject openTab = new JSONObject();
    openTab.put(SupportAccessMessages.CLIENT_ACTION_FUNCTION,
        SupportAccessMessages.openInNewTabScript(url));
    // Open the tab first, then show the message with the fallback link.
    return success(getResponseBuilder().addCustomResponseAction(
        SupportAccessMessages.CLIENT_ACTION_CUSTOM, openTab), text).build();
  }

  /**
   * Whether the public GO app URL is configured ({@code etendo.go.app.baseUrl} or
   * {@code ETGO_APP_BASE_URL}).
   *
   * @return {@code true} when a handoff URL can be built
   */
  protected boolean isAppUrlConfigured() {
    return PublicUrlResolver.resolveConfiguredAppBaseUrl() != null;
  }

  private static HttpServletRequest httpRequest(Map<String, Object> parameters) {
    Object request = parameters == null ? null : parameters.get(KernelConstants.HTTP_REQUEST);
    return request instanceof HttpServletRequest ? (HttpServletRequest) request : null;
  }

  /**
   * A text parameter, empty when absent or JSON null.
   *
   * @param params the {@code _params} object, may be null
   * @param name   the parameter column name
   * @return the value, never null
   */
  static String stringParam(JSONObject params, String name) {
    if (params == null || params.isNull(name)) {
      return "";
    }
    return params.optString(name, "");
  }

  /**
   * The "Duration" parameter.
   *
   * @param params the {@code _params} object, may be null
   * @return the minutes, or {@code null} to take the configured default
   * @throws SupportAccessException {@code SUPPORT_DURATION_INVALID} when it is not a number
   */
  static Integer durationParam(JSONObject params) {
    String raw = StringUtils.trimToNull(
        stringParam(params, SupportAccessClassicMetadata.PARAM_DURATION));
    if (raw == null) {
      return null;
    }
    try {
      return Integer.valueOf(raw);
    } catch (NumberFormatException e) {
      throw new SupportAccessException(SupportAccessException.CODE_DURATION_INVALID,
          "The duration is not a number");
    }
  }
}
