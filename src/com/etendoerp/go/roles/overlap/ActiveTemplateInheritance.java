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
 * {@code WindowAccessOverlapCorruptionGuard}, {@code ProcessAccessOverlapCorruptionGuard}, and
 * {@code ObuiappProcessAccessOverlapCorruptionGuard} instead of being duplicated.
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
 *
 * <p>Also home to {@link #findActiveDependentRoles(Role)} and both {@code sameId} overloads
 * ({@link #sameId(BaseOBObject, BaseOBObject)}, {@link #sameId(BaseOBObject, Object)}) — same
 * criterion as above: pure {@code Role}/{@code RoleInheritance} logic with zero {@code
 * WindowAccess}/{@code ProcessAccess}/{@code OBUIAPP_Process_Access}-specific content, previously
 * triplicated across all 3 guard classes (ETP-4830 final-review Finding 2).
 */
public final class ActiveTemplateInheritance {

  private ActiveTemplateInheritance() {
    // static utility
  }

  /**
   * Every ACTIVE template {@code dependent} currently inherits from, ordered by {@code
   * AD_Role_Inheritance.SeqNo} DESCENDING — see this class's own javadoc for the full tie-break
   * rationale this ordering supports.
   *
   * @param dependent
   *          the role whose active template inheritances are being looked up
   * @param excludedInheritanceId
   *          the id of one {@code AD_Role_Inheritance} row to exclude from the result (typically
   *          the row about to be deleted), or {@code null} to include all of them
   * @return every active template {@code dependent} inherits from, excluding {@code
   *         excludedInheritanceId} and any template {@link TemplateRemovalTracker} currently
   *         reports as being removed, ordered by {@code SeqNo} descending
   */
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
   * Every role that currently, actively inherits from {@code template} — extracted from the
   * identical private copy previously duplicated across {@code
   * WindowAccessOverlapCorruptionGuard}, {@code ProcessAccessOverlapCorruptionGuard}, and {@code
   * ObuiappProcessAccessOverlapCorruptionGuard} (ETP-4830 final-review Finding 2). See {@link
   * #crossClientCriteria(Class)} for why the query must disable readable-client/organization
   * filtering.
   *
   * @param template
   *          the template role to find active dependents of
   * @return every role currently, actively inheriting from {@code template}
   */
  @SuppressWarnings("unchecked")
  public static List<Role> findActiveDependentRoles(Role template) {
    OBCriteria<RoleInheritance> criteria = crossClientCriteria(RoleInheritance.class);
    criteria.add(Restrictions.eq(RoleInheritance.PROPERTY_INHERITFROM, template));
    criteria.add(Restrictions.eq(RoleInheritance.PROPERTY_ACTIVE, true));
    List<Role> dependents = new ArrayList<>();
    Set<String> seenRoleIds = new LinkedHashSet<>();
    for (RoleInheritance inheritance : (List<RoleInheritance>) criteria.list()) {
      Role dependent = inheritance.getRole();
      if (dependent != null && seenRoleIds.add(dependent.getId())) {
        dependents.add(dependent);
      }
    }
    return dependents;
  }

  /**
   * Disables {@code OBCriteria}'s implicit client/organization filtering — REQUIRED here: a
   * template role is typically system client {@code "0"} while its dependents are real tenant
   * clients, so without this the query would silently return zero rows whenever the ambient
   * {@code OBContext}'s role does not have both clients in its own readable-clients list.
   * {@code public} (not private) so every method in this class can reuse it AND so the 3 guard
   * classes (a different package, {@code com.etendoerp.go.roles}) can call it directly for their
   * own {@code WindowAccess}/{@code ProcessAccess}-specific queries.
   *
   * @param <T>
   *          the entity type being queried
   * @param clazz
   *          the entity class to build a criteria for
   * @return a fresh {@link OBCriteria} for {@code clazz} with readable-client/organization
   *         filtering disabled
   */
  public static <T extends BaseOBObject> OBCriteria<T> crossClientCriteria(Class<T> clazz) {
    OBCriteria<T> criteria = OBDal.getInstance().createCriteria(clazz);
    criteria.setFilterOnReadableClients(false);
    criteria.setFilterOnReadableOrganization(false);
    return criteria;
  }

  /**
   * Extracted from the identical private copy previously duplicated across all 3 guard classes
   * (ETP-4830 final-review Finding 2).
   *
   * @param a
   *          the first entity to compare, or {@code null}
   * @param b
   *          the second entity to compare, or {@code null}
   * @return {@code true} when both are non-{@code null} and share the same id
   */
  public static boolean sameId(BaseOBObject a, BaseOBObject b) {
    if (a == null || b == null) {
      return false;
    }
    String idA = (String) a.getId();
    String idB = (String) b.getId();
    return idA != null && idA.equals(idB);
  }

  /**
   * Overload for comparing against an {@code EntityPersistenceEvent#getCurrentState(Property)}
   * result, which is declared {@code Object} — defensively checks the runtime type rather than
   * casting. Extracted from the identical private copy previously duplicated across all 3 guard
   * classes (ETP-4830 final-review Finding 2).
   *
   * @param expected
   *          the entity the current state is expected to reference
   * @param actualState
   *          the raw {@code getCurrentState(Property)} result to compare against
   * @return {@code true} when {@code actualState} is a {@link BaseOBObject} sharing {@code
   *         expected}'s id
   */
  public static boolean sameId(BaseOBObject expected, Object actualState) {
    if (!(actualState instanceof BaseOBObject)) {
      return false;
    }
    return sameId(expected, (BaseOBObject) actualState);
  }
}
