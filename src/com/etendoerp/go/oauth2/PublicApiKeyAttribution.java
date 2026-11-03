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

package com.etendoerp.go.oauth2;

import java.util.Map;

import javax.servlet.http.HttpServletRequest;

import org.apache.commons.lang3.StringUtils;

import com.etendoerp.go.usageevents.PublicApiCaller;

/**
 * ETP-6057 — tells which public API key (and tenant) a request belongs to, for usage counting only.
 * Nothing here authenticates or authorizes anything.
 *
 * <p>A request is a public API request when its OAuth2 token belongs to a user-owned public API key:
 * the key row's stored scopes carry {@code neo:public-api-key} ({@link PublicApiKeyPolicy}). That is
 * structure — a scope — and names no key, tenant, spec or entity. The token's own scopes cannot be
 * used for it: the token exchange keeps only grantable scopes, so the marker never reaches a token.</p>
 *
 * <p>The attribution is stored in the request attribute {@link #REQUEST_ATTRIBUTE} by the shared
 * authentication pipeline, before any refusal, so a request refused for scope, for commercial access
 * or with an expired or revoked token is attributed as well as an accepted one.</p>
 *
 * <p><b>The tenant</b> is the client of the key's owner organization (the token's
 * {@code AD_Org_ID}); when that organization is {@code *} (client {@code 0}) it is the client of the
 * key's role, as {@code EffectiveClientResolver} resolves it for the request itself.</p>
 */
public final class PublicApiKeyAttribution {

  /** Request attribute holding the {@link PublicApiCaller} of a public API request. */
  public static final String REQUEST_ATTRIBUTE = "com.etendoerp.go.publicapi.caller";

  private static final String SYSTEM_CLIENT = "0";

  private PublicApiKeyAttribution() {
  }

  /**
   * The caller of a token identity, as {@link OAuth2Filter#checkToken} answers it — the identity of
   * a usable token or the refused identity of a refused one.
   *
   * @param identity the identity attributes, possibly null
   * @return the caller when the token belongs to a public API key, null otherwise
   */
  public static PublicApiCaller fromIdentity(Map<String, String> identity) {
    if (identity == null || !Boolean.parseBoolean(identity.get(OAuth2Filter.ATTR_PUBLIC_API_KEY))) {
      return null;
    }
    return new PublicApiCaller(
        tenant(identity.get(OAuth2Filter.ATTR_CLIENT_ID),
            identity.get(OAuth2Filter.ATTR_ROLE_CLIENT_ID)),
        identity.get(OAuth2Filter.ATTR_ORG_ID),
        identity.get(OAuth2Filter.ATTR_USER_ID),
        identity.get(OAuth2Filter.ATTR_ROLE_ID),
        identity.get(OAuth2Filter.ATTR_KEY_ID));
  }

  /**
   * Store the caller on the request for the NEO usage counter. Null-safe; never throws.
   *
   * @param request the request
   * @param caller  the caller, or null to store nothing
   */
  public static void attach(HttpServletRequest request, PublicApiCaller caller) {
    if (request != null && caller != null) {
      request.setAttribute(REQUEST_ATTRIBUTE, caller);
    }
  }

  /**
   * The caller stored by {@link #attach}.
   *
   * @param request the request, possibly null
   * @return the caller, or null when the request is not a public API request
   */
  public static PublicApiCaller of(HttpServletRequest request) {
    Object value = request == null ? null : request.getAttribute(REQUEST_ATTRIBUTE);
    return value instanceof PublicApiCaller ? (PublicApiCaller) value : null;
  }

  /**
   * The caller of a key row at the token exchange, where no token exists yet. Pure: the clients come
   * with the key row ({@code OAuth2Servlet.findClient}), so nothing is read here.
   *
   * @param keyId            the key's public client_identifier
   * @param scopes           the key row's stored scopes
   * @param ownerOrgId       the key's owner organization
   * @param ownerOrgClientId the client of that organization
   * @param roleClientId     the client of the key's role
   * @param userId           the key's user
   * @param roleId           the key's role
   * @return the caller when the row is a public API key, null otherwise
   */
  static PublicApiCaller ofKeyRow(String keyId, String scopes, String ownerOrgId,
      String ownerOrgClientId, String roleClientId, String userId, String roleId) {
    if (!PublicApiKeyPolicy.isPublicApiKey(scopes)) {
      return null;
    }
    return new PublicApiCaller(tenant(ownerOrgClientId, roleClientId), ownerOrgId, userId, roleId,
        keyId);
  }

  /**
   * The one tenant fallback chain of a public API key, used by both the token path
   * ({@link #fromIdentity}) and the key-row path ({@link #ofKeyRow}): the owner organization's
   * client, else (when that is {@code *}'s client {@code 0} or unknown) the key role's client.
   *
   * @return the tenant, or null when the chain ends on no tenant or on the System client {@code 0}
   */
  static String tenant(String orgClientId, String roleClientId) {
    String resolved = isTenant(orgClientId) ? orgClientId : roleClientId;
    return isTenant(resolved) ? resolved : null;
  }

  private static boolean isTenant(String clientId) {
    return StringUtils.isNotBlank(clientId) && !SYSTEM_CLIENT.equals(clientId);
  }

}
