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

package com.etendoerp.go.schemaforge.telemetry;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.ArrayList;
import java.util.HashMap;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openbravo.dal.core.OBContext;
import org.openbravo.model.ad.access.Role;
import org.openbravo.model.ad.access.User;

import com.etendoerp.go.rest.EtendoGoJwtSupport;

/**
 * ETP-6247 — {@code role_groups} for a backend event: the Java port of the SPA's
 * {@code resolveRoleGroups} ({@code tools/app-shell/src/lib/observability/roleGroups.js},
 * ETP-6043), so a backend KPI segments by role exactly as the frontend events do.
 *
 * <p>The groups come from the system role TEMPLATES composed into the user's personal role
 * ({@link EtendoGoJwtSupport#resolveEffectiveRoleNames}), never from the role's own name, which is
 * free text per tenant. A client-admin role is {@code [admin]}; a role with no known template is
 * {@code [other]}. As in the SPA, the template names only apply while the active role is the
 * user's default (personal) role: the session's {@code effectiveRoleNames} are attached to that
 * role alone, so any other active role resolves to {@code [other]}.
 */
public final class NeoRoleGroups {

  private static final Logger log = LogManager.getLogger(NeoRoleGroups.class);

  static final String ADMIN = "admin";
  static final String OTHER = "other";

  private static final Map<String, String> TEMPLATE_ROLE_GROUPS = templateGroups();

  private NeoRoleGroups() {
  }

  /**
   * The role groups of the running request's role.
   *
   * @return the sorted groups, or an empty list when there is no role to read (the caller then
   *         leaves the property off instead of guessing)
   */
  public static List<String> current() {
    try {
      OBContext context = OBContext.getOBContext();
      Role role = context != null ? context.getRole() : null;
      User user = context != null ? context.getUser() : null;
      if (role == null || user == null) {
        return Collections.emptyList();
      }
      boolean defaultRole = user.getDefaultRole() != null
          && role.getId().equals(user.getDefaultRole().getId());
      List<String> names = defaultRole
          ? EtendoGoJwtSupport.resolveEffectiveRoleNames(user.getId())
          : Collections.emptyList();
      return resolve(Boolean.TRUE.equals(role.isClientAdmin()), names);
    } catch (RuntimeException e) {
      log.debug("role_groups not resolved: {}", e.getMessage());
      return Collections.emptyList();
    }
  }

  /**
   * The SPA's {@code resolveRoleGroups}, for a known role.
   *
   * @param clientAdmin        whether the role is the tenant's admin role
   * @param effectiveRoleNames the template role names composed into it, may be {@code null}
   * @return the sorted groups, never empty
   */
  public static List<String> resolve(boolean clientAdmin, List<String> effectiveRoleNames) {
    if (clientAdmin) {
      return Collections.singletonList(ADMIN);
    }
    TreeSet<String> groups = new TreeSet<>();
    if (effectiveRoleNames != null) {
      for (String name : effectiveRoleNames) {
        String group = name != null ? TEMPLATE_ROLE_GROUPS.get(name) : null;
        if (group != null) {
          groups.add(group);
        }
      }
    }
    return groups.isEmpty() ? Collections.singletonList(OTHER)
        : Collections.unmodifiableList(new ArrayList<>(groups));
  }

  private static Map<String, String> templateGroups() {
    Map<String, String> groups = new HashMap<>();
    groups.put("Inventory", "warehouse");
    groups.put("Sales", "sales");
    groups.put("Purchasing", "purchasing");
    groups.put("Finance", "finance");
    return Collections.unmodifiableMap(groups);
  }
}
