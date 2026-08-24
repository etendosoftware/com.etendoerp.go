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
package com.etendoerp.go.roles.overlap;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.hibernate.criterion.Restrictions;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.access.Role;
import org.openbravo.model.ad.access.RoleInheritance;

/**
 * Every ACTIVE template a role currently inherits from — extracted from {@code
 * com.etendoerp.go.roles.WindowAccessOverlapCorruptionGuard#findActiveTemplatesFor(Role, String)}
 * (ETP-4830 item 7) because that method itself has zero {@code WindowAccess}-specific logic in
 * it: it only ever touches {@code Role}/{@code RoleInheritance}, so it is shared verbatim by
 * {@code ProcessAccessOverlapCorruptionGuard} instead of being duplicated.
 *
 * <p>Ordered by {@code AD_Role_Inheritance.SeqNo} DESCENDING — mirrors core's own {@code
 * RoleInheritanceManager#getRoleInheritancesList(Role, Role, boolean)} call from {@code
 * propagateDeletedAccess} (also descending), the tie-break authority {@link
 * OverlapReconciliationCore#computeWinner(java.util.List)} deliberately reuses. Excludes by id,
 * not by DB-visible state, mirroring core's own {@code
 * RoleInheritanceManager#getUpdatedRoleInheritancesList(RoleInheritance, boolean)} (the excluded
 * row may still be physically present at this point in the flush). ALSO excludes every template
 * {@link TemplateRemovalTracker#isBeingRemoved(String)} currently reports — see that class's own
 * javadoc for the exact same-flush-visibility race this closes.
 */
public final class ActiveTemplateInheritance {

  private ActiveTemplateInheritance() {
    // static utility
  }

  @SuppressWarnings("unchecked")
  public static List<Role> findActiveTemplatesFor(Role dependent, String excludedInheritanceId) {
    OBCriteria<RoleInheritance> criteria = crossClientCriteria(RoleInheritance.class);
    criteria.add(Restrictions.eq(RoleInheritance.PROPERTY_ROLE, dependent));
    criteria.add(Restrictions.eq(RoleInheritance.PROPERTY_ACTIVE, true));
    if (excludedInheritanceId != null) {
      criteria.add(Restrictions.ne(RoleInheritance.PROPERTY_ID, excludedInheritanceId));
    }
    criteria.addOrderBy(RoleInheritance.PROPERTY_SEQUENCENUMBER, false);
    List<Role> templates = new ArrayList<>();
    Set<String> seenTemplateIds = new LinkedHashSet<>();
    for (RoleInheritance ri : (List<RoleInheritance>) criteria.list()) {
      Role template = ri.getInheritFrom();
      if (template != null && Boolean.TRUE.equals(template.isTemplate())
          && !TemplateRemovalTracker.isBeingRemoved(template.getId())
          && seenTemplateIds.add(template.getId())) {
        templates.add(template);
      }
    }
    return templates;
  }

  /**
   * Disables {@code OBCriteria}'s implicit client/organization filtering — REQUIRED here, same
   * reasoning as {@code WindowAccessOverlapCorruptionGuard}'s own private {@code
   * crossClientCriteria}: a template role is typically system client {@code "0"} while its
   * dependents are real tenant clients, so without this the query would silently return zero rows
   * whenever the ambient {@code OBContext}'s role does not have both clients in its own
   * readable-clients list.
   */
  private static <T extends BaseOBObject> OBCriteria<T> crossClientCriteria(Class<T> clazz) {
    OBCriteria<T> criteria = OBDal.getInstance().createCriteria(clazz);
    criteria.setFilterOnReadableClients(false);
    criteria.setFilterOnReadableOrganization(false);
    return criteria;
  }
}
