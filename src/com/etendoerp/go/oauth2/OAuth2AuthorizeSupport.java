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
    /**
     * Parse the authorize request payload from an HTTP request body.
     *
     * @param request HTTP request whose body should be parsed
     * @return parsed JSON payload
     * @throws IOException when the request body cannot be read
     * @throws JSONException when the request body is not valid JSON
     */
    JSONObject parse(HttpServletRequest request) throws IOException, JSONException;
  }

  private OAuth2AuthorizeSupport() {
  }

  static AuthorizeRequestData parseAuthorizeRequest(HttpServletRequest request,
      String applicationJson, JsonBodyParser parser)
      throws IOException, JSONException {
    String contentType = request.getContentType();
    if (contentType != null && contentType.contains(applicationJson)) {
      JSONObject body = parser.parse(request);
      return new AuthorizeRequestData(
          body.optString("token", null),
          body.optString("client_id", null),
          body.optString("redirect_uri", null),
          body.optString("code_challenge", null),
          body.optString("state", null),
          body.optString("scope", null));
    }
    return new AuthorizeRequestData(
        request.getParameter("token"),
        request.getParameter("client_id"),
        request.getParameter("redirect_uri"),
        request.getParameter("code_challenge"),
        request.getParameter("state"),
        request.getParameter("scope"));
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
