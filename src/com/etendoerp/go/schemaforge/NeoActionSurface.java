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

package com.etendoerp.go.schemaforge;

import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.etendoerp.go.schemaforge.data.SFEntity;

/**
 * Reports whether an entity exposes an {@code /action} route
 * ({@code POST /{spec}/{entity}/{id}/action/{name}}).
 *
 * <p><b>Why a CDI probe and not a query.</b> {@code ETGO_SF_ENTITY} carries no action
 * metadata — its columns are the method flags, {@code AD_TAB_ID}, {@code JAVA_QUALIFIER},
 * {@code PRECONDITIONS} and audit. Action routes are dispatched by
 * {@code NeoSubEndpointDispatcher} straight to the entity's {@link NeoHandler}, which decides
 * per action name whether it answers. So the handler is the only authority, and it declares
 * itself through {@link NeoHandler#servesActions()}.</p>
 *
 * <p><b>Who asks (ETP-4254).</b> Only the MCP catalog, and only for the tab-less spec shape:
 * {@code McpToolRouterSupport#isCatalogExcludedSpec} hides a spec whose entities are all
 * handler-backed <em>and</em> actionless (the dashboard's widgets), while keeping one that
 * still has a real action surface ({@code not-posted-documents}' {@code post} /
 * {@code bulk-post}) reachable through {@code neo_discover} and {@code neo_action}.</p>
 *
 * <p><b>Fail-open.</b> A missing qualifier, an unregistered handler or a CDI failure all
 * answer {@code true} — "assume there is a surface". The catalog predicates are advisory
 * (the authoritative CRUD refusal is {@code NeoMethodPolicy}/{@code requireMethodEnabled}),
 * so the safe direction on no evidence is to keep a spec visible rather than silently hide a
 * working window.</p>
 */
public final class NeoActionSurface {

  private static final Logger log = LogManager.getLogger(NeoActionSurface.class);

  private NeoActionSurface() {
  }

  /**
   * Reports whether the entity's handler declares that it answers ACTION requests.
   *
   * @param entity the SF entity to probe (may be {@code null})
   * @return {@code true} when the entity has (or may have) an {@code /action} route;
   *         {@code false} only on positive evidence that its handler serves none
   */
  public static boolean hasActionSurface(SFEntity entity) {
    if (entity == null) {
      return true;
    }
    String qualifier = entity.getJavaQualifier();
    if (StringUtils.isBlank(qualifier)) {
      // No qualifier means no handler, so no handler-served action route. A tab-backed entity
      // reaches its AD process actions through the generic path instead — but this class is
      // only ever asked about tab-less entities, where no AD process exists.
      return false;
    }
    try {
      NeoHandler handler = NeoServletSupport.lookupHandler(qualifier);
      if (handler == null) {
        log.warn("No NeoHandler registered for qualifier '{}' — assuming an action surface",
            qualifier);
        return true;
      }
      return handler.servesActions();
    } catch (Exception e) {
      log.warn("Could not probe the action surface of qualifier '{}': {}", qualifier,
          e.getMessage());
      return true;
    }
  }

  /**
   * Reports whether ANY entity in the list exposes an {@code /action} route.
   *
   * @param entities the spec's active, included entities (may be {@code null} or empty)
   * @return {@code true} when at least one entity has (or may have) an action route
   */
  public static boolean hasActionSurface(List<SFEntity> entities) {
    if (entities == null || entities.isEmpty()) {
      return false;
    }
    return entities.stream().anyMatch(NeoActionSurface::hasActionSurface);
  }
}
