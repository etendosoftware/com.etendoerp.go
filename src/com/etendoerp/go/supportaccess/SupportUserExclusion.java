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
package com.etendoerp.go.supportaccess;

import java.util.Collection;
import java.util.List;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;
import org.openbravo.dal.core.OBContext;

/**
 * ETP-5351 (T6) — the single way to keep the tenant's "Soporte Etendo" user out of everything the
 * tenant sees: user lists, selectors, admin counts, role-card counts.
 *
 * <p>Recognition is {@link SupportAccessGuard#isSupportUser(String, String)}: the user's id is
 * derived from the client id, so every exclusion here needs the client and nothing else — no
 * query, and no DAL property for {@code EM_ETGO_IS_SUPPORT} (the generated entities do not have
 * it until they are regenerated). Three shapes, pick the one the call site can use:</p>
 * <ul>
 *   <li>{@link #removeFrom(Collection, String)} — drop the id from a result already in memory
 *       (admin sets, assignee sets, per-user maps). Preferred: no query change at all;</li>
 *   <li>{@link #HQL_PARAM} + {@link #supportUserIdOrNull(String)} — a bind parameter, for HQL the
 *       call site builds itself ({@code alias.id <> :etgoSupportUserId});</li>
 *   <li>{@link #hqlNotSupportUser(String, String)} — a literal predicate, only for the predicate
 *       channels that have no bind mechanism ({@code NeoHandler#readPredicates}). The literal is
 *       the server-derived hex id, re-validated before it is returned, never request input.</li>
 * </ul>
 */
public final class SupportUserExclusion {

  /** Named HQL parameter carrying the current client's support user id. */
  public static final String HQL_PARAM = "etgoSupportUserId";

  /** Shape of an id {@link SupportAccessGuard#supportUserIdFor(String)} produces. */
  private static final Pattern DERIVED_ID = Pattern.compile("^[0-9A-F]{32}$");

  /** Alias NEO gives the entity of a list read ({@code NeoHandler#readPredicates} contract). */
  private static final String ENTITY_ALIAS = "e";

  private SupportUserExclusion() {
  }

  /**
   * The support user id of the client, or {@code null} when there is no client to derive it from.
   *
   * @param clientId the tenant's {@code AD_Client_ID}, may be null or blank
   * @return the derived {@code AD_User_ID}, or {@code null}
   */
  public static String supportUserIdOrNull(String clientId) {
    return StringUtils.isBlank(clientId) ? null : SupportAccessGuard.supportUserIdFor(clientId);
  }

  /**
   * The {@code AD_Client_ID} of the current {@link OBContext}, or {@code null} when there is no
   * context or no current client. Never throws.
   *
   * @return the current client id, or {@code null}
   */
  public static String currentClientId() {
    try {
      OBContext context = OBContext.getOBContext();
      return context != null && context.getCurrentClient() != null
          ? context.getCurrentClient().getId()
          : null;
    } catch (RuntimeException e) {
      return null;
    }
  }

  /**
   * Removes the client's support user id from {@code userIds}, in place.
   *
   * @param userIds  a mutable collection of {@code AD_User_ID}s, may be null
   * @param clientId the tenant the ids belong to; nothing is removed when it is blank
   * @return {@code true} when the support user was in the collection
   */
  public static boolean removeFrom(Collection<String> userIds, String clientId) {
    String supportUserId = supportUserIdOrNull(clientId);
    return userIds != null && supportUserId != null && userIds.remove(supportUserId);
  }

  /**
   * The {@code NeoHandler#readPredicates} of an entity over {@code AD_User}: hides the client's
   * support user from every list read (REST list and count, {@code ?_distinct=}, MCP
   * {@code etendo_list}).
   *
   * @param obContext the read's context; when null or without a client, the current
   *                  {@link OBContext} is used
   * @return a one-element list with the exclusion over alias {@code e}, or an empty list when no
   *     client can be resolved
   */
  public static List<String> userReadPredicates(OBContext obContext) {
    String clientId = obContext != null && obContext.getCurrentClient() != null
        ? obContext.getCurrentClient().getId()
        : currentClientId();
    String predicate = hqlNotSupportUser(ENTITY_ALIAS, clientId);
    return predicate != null ? List.of(predicate) : List.of();
  }

  /**
   * An HQL predicate that excludes the client's support user from a read over {@code AD_User}.
   * For predicate channels without bind parameters only; everywhere else bind {@link #HQL_PARAM}.
   *
   * @param alias    the HQL alias of the {@code AD_User} being read (for example {@code e})
   * @param clientId the tenant being read
   * @return {@code <alias>.id <> '<id>'}, or {@code null} when the client is unknown
   */
  public static String hqlNotSupportUser(String alias, String clientId) {
    String supportUserId = supportUserIdOrNull(clientId);
    if (supportUserId == null || StringUtils.isBlank(alias)
        || !DERIVED_ID.matcher(supportUserId).matches()) {
      return null;
    }
    return alias + ".id <> '" + supportUserId + "'";
  }
}
