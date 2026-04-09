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

import java.util.Objects;

final class OAuth2AuthorizationCodeSupport {

  private OAuth2AuthorizationCodeSupport() {
  }

  static String validateAuthorizationCode(OAuth2Servlet.AuthCodeData codeData,
      String codeVerifier, String redirectUri) {
    if (codeData == null) {
      return "Authorization code not found or expired";
    }
    if (codeData.used) {
      return "Authorization code already used";
    }
    if (System.currentTimeMillis() > codeData.expiresAt) {
      return "Authorization code expired";
    }
    if (!OAuth2Utils.verifyCodeChallenge(codeVerifier, codeData.codeChallenge)) {
      return "PKCE verification failed";
    }
    if (!Objects.equals(redirectUri, codeData.redirectUri)) {
      return "redirect_uri mismatch";
    }
    return null;
  }
}
