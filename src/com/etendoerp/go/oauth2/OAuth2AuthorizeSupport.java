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
package com.etendoerp.go.oauth2;

import java.io.IOException;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;

final class OAuth2AuthorizeSupport {

  static final class AuthorizeRequestData {
    final String jwtToken;
    final String clientId;
    final String redirectUri;
    final String codeChallenge;
    final String state;
    final String scope;

    AuthorizeRequestData(String jwtToken, String clientId, String redirectUri,
        String codeChallenge, String state, String scope) {
      this.jwtToken = jwtToken;
      this.clientId = clientId;
      this.redirectUri = redirectUri;
      this.codeChallenge = codeChallenge;
      this.state = state;
      this.scope = scope;
    }
  }

  interface JsonBodyParser {
    JSONObject parse(HttpServletRequest request) throws IOException, JSONException;
  }

  private OAuth2AuthorizeSupport() {
  }

  static AuthorizeRequestData parseAuthorizeRequest(HttpServletRequest request,
      String applicationJson, String fieldToken, String fieldClientId, String fieldRedirectUri,
      String fieldCodeChallenge, String fieldState, String fieldScope, JsonBodyParser parser)
      throws IOException, JSONException {
    String contentType = request.getContentType();
    if (contentType != null && contentType.contains(applicationJson)) {
      JSONObject body = parser.parse(request);
      return new AuthorizeRequestData(
          body.optString(fieldToken, null),
          body.optString(fieldClientId, null),
          body.optString(fieldRedirectUri, null),
          body.optString(fieldCodeChallenge, null),
          body.optString(fieldState, null),
          body.optString(fieldScope, null));
    }
    return new AuthorizeRequestData(
        request.getParameter(fieldToken),
        request.getParameter(fieldClientId),
        request.getParameter(fieldRedirectUri),
        request.getParameter(fieldCodeChallenge),
        request.getParameter(fieldState),
        request.getParameter(fieldScope));
  }

  static void writeAuthorizeSuccess(HttpServletResponse response, String redirectUri,
      String state, String authCode, OAuth2Servlet servlet) throws IOException, JSONException {
    StringBuilder redirect = new StringBuilder(redirectUri);
    redirect.append(redirectUri.contains("?") ? "&" : "?");
    redirect.append("code=").append(authCode);
    if (state != null && !state.isEmpty()) {
      redirect.append("&state=").append(state);
    }
    JSONObject result = new JSONObject();
    result.put("redirect_url", redirect.toString());
    servlet.writeJsonResponse(response, HttpServletResponse.SC_OK, result);
  }
  static OAuth2Servlet.AuthCodeData buildAuthCodeData(AuthorizeRequestData authorizeRequest,
      String userId, String roleId, java.util.Set<String> requestedScopes,
      java.util.Set<String> allowedScopes, long authCodeExpiryMs) {
    OAuth2Servlet.AuthCodeData codeData = new OAuth2Servlet.AuthCodeData();
    codeData.clientId = authorizeRequest.clientId;
    codeData.userId = userId;
    codeData.roleId = roleId;
    codeData.redirectUri = authorizeRequest.redirectUri;
    codeData.codeChallenge = authorizeRequest.codeChallenge;
    java.util.Set<String> grantedScopes = requestedScopes.isEmpty() ? allowedScopes : requestedScopes;
    codeData.scopes = String.join(" ", grantedScopes);
    codeData.expiresAt = System.currentTimeMillis() + authCodeExpiryMs;
    codeData.used = false;
    return codeData;
  }


}
