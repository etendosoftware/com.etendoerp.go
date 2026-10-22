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
package com.etendoerp.go.roles;

import java.util.ArrayList;
import java.util.List;

import org.openbravo.base.structure.ActiveEnabled;
import org.openbravo.base.structure.InheritedAccessEnabled;
import org.openbravo.model.ad.access.Role;

/**
 * ETP-5830 — keeps core's role inheritance from copying a system template's soft-deleted access
 * rows. Core reads a template's rows with {@code setFilterOnActive(false)} and copies the inactive
 * ones too. {@code EnsureSystemRoleTemplatesScript} soft-deletes a removed grant (never DELETE, see
 * ETP-5565), so when two or more templates share one, removing a template makes core recalculate
 * the remaining ones in a single flush and INSERT the same inactive copy twice
 * ({@code ad_window_access_un_key}). An inactive template row grants nothing, so it is dropped
 * from the list core inherits from.
 *
 * <p>Applied only to system templates ({@code AD_Client_ID = '0'}, {@code IsTemplate = 'Y'}), the
 * ones this module manages; any other role keeps core's behaviour. Only the template-side read
 * ({@code calculateAccesses}) is affected: core reads a dependent role's own rows through the same
 * method, and those are not templates.</p>
 */
final class SystemTemplateAccessRows {

  private static final String SYSTEM_CLIENT_ID = "0";

  private SystemTemplateAccessRows() {
  }

  static List<? extends InheritedAccessEnabled> withoutInactive(Role role,
      List<? extends InheritedAccessEnabled> rows) {
    if (!isSystemTemplate(role)) {
      return rows;
    }
    // A mutable list, as core's own: deleteAllAccesses removes from it.
    List<InheritedAccessEnabled> active = new ArrayList<>(rows.size());
    for (InheritedAccessEnabled row : rows) {
      if (!(row instanceof ActiveEnabled)
          || Boolean.TRUE.equals(((ActiveEnabled) row).isActive())) {
        active.add(row);
      }
    }
    return active;
  }

  static boolean isSystemTemplate(Role role) {
    return Boolean.TRUE.equals(role.isTemplate())
        && SYSTEM_CLIENT_ID.equals(role.getClient().getId());
  }
}
