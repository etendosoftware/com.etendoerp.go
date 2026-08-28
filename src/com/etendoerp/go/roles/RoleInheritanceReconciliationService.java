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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.hibernate.Session;
import org.hibernate.criterion.Restrictions;
import org.hibernate.query.NativeQuery;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.access.Role;
import org.openbravo.model.ad.access.RoleInheritance;
import org.openbravo.model.ad.access.WindowAccess;

/**
 * ETP-5019 — extracted out of {@link UserRoleCompositionService} (SonarQube S1448, that class was
 * over the 35-method limit) to keep this one responsibility — reconciling a personal role's
 * {@code AD_Role_Inheritance} rows and the {@code AD_Window_Access} side effects that go with
 * them — separate from the identity/authorization concerns (owner protection, admin
 * promotion/demotion) the rest of that class handles. Purely behavior-preserving: every method
 * here is the exact body moved verbatim from {@code UserRoleCompositionService}, called from the
 * same two call sites ({@code UserRoleCompositionService#assignTemplateRoles}'s 4-arg overload
 * and {@code #getAppliedTemplateRoleIds(String, Role)}), with no logic changes.
 *
 * <p>Covers two cohesive pieces of composing a personal role from templates: (1) reconciling
 * {@code AD_Role_Inheritance} itself ({@link #reconcileInheritances}); (2) the
 * {@code AD_Window_Access} corrections that reconciliation requires — overlap-corruption
 * prevention before an add, and a most-permissive-wins pass after ({@link
 * #preventWindowAccessOverlapCorruption}, {@link #reconcileWindowAccessAfterComposition}) — plus
 * the read helpers both of those and the two call sites above share.</p>
 */
class RoleInheritanceReconciliationService {

  private static final long SEQNO_STEP = 10L;

  /**
   * Reconciles {@code personalRole}'s {@code AD_Role_Inheritance} rows to match {@code
   * templates} exactly — adds missing ones, removes no-longer-requested ones. Every add/remove
   * goes through a real {@code OBDal.save}/{@code OBDal.remove} (never native SQL) specifically
   * so core's {@code RoleInheritanceEventHandler} fires and propagates/retracts the template's
   * accesses — see the class javadoc.
   *
   * <p><b>Deliberately queries fresh via {@code OBCriteria} instead of {@code
   * personalRole.getADRoleInheritanceList()}.</b> The entity's own collection property is NOT
   * reliably refreshed by a sibling {@code OBDal.save(newInheritance)} within the same session —
   * a brand-new {@link Role} starts with the plain default {@code ArrayList} its constructor set
   * ({@code setDefaultValue(PROPERTY_ADROLEINHERITANCELIST, new ArrayList&lt;&gt;())}), and
   * nothing re-fetches or appends to it after an insert, so a second call against the SAME
   * in-session {@code Role} instance would see a stale, empty list and try to re-insert a row
   * that already exists — hitting {@code ad_role_inheritance_role_un}'s
   * {@code UNIQUE(ad_role_id, inherit_from)} constraint. A fresh criteria query has no such
   * staleness. This mirrors core's own {@code RoleInheritanceManager#getRoleInheritancesList},
   * which also always queries fresh rather than trusting {@code role.getADRoleInheritanceList()}.
   * </p>
   *
   * @return {@code {addedCount, removedCount}}
   */
  int[] reconcileInheritances(Role personalRole, List<Role> templates) {
    Set<String> desiredIds = new LinkedHashSet<>();
    for (Role template : templates) {
      desiredIds.add(template.getId());
    }

    List<RoleInheritance> existing = findExistingInheritances(personalRole);
    Set<String> existingIds = new LinkedHashSet<>();
    long maxSeqno = 0L;
    for (RoleInheritance inheritance : existing) {
      existingIds.add(inheritance.getInheritFrom().getId());
      if (inheritance.getSequenceNumber() != null
          && inheritance.getSequenceNumber() > maxSeqno) {
        maxSeqno = inheritance.getSequenceNumber();
      }
    }

    int removed = 0;
    for (RoleInheritance inheritance : existing) {
      if (!desiredIds.contains(inheritance.getInheritFrom().getId())) {
        OBDal.getInstance().remove(inheritance);
        // Same core RoleInheritanceEventHandler fan-out as the ADD loop below (see its own
        // comment) — deleting this row triggers RoleInheritanceManager#applyRemoveInheritance,
        // which retracts every AccessTypeInjector's propagated rows (window, tab, field, process,
        // OBUIAPP process, ...) for this role. Core's own deleteRoleAccess wraps ITS internal
        // remove() calls in an admin-mode bypass, but that bypass is popped before THIS flush
        // runs, so a still-client-"0" child row (from a system-level template) fails the same
        // ClientList check the ADD loop already guards against.
        OBContext.setAdminMode(false);
        try {
          OBDal.getInstance().flush();
        } finally {
          OBContext.restorePreviousMode();
        }
        removed++;
      }
    }

    int added = 0;
    for (Role template : templates) {
      if (existingIds.contains(template.getId())) {
        continue;
      }
      preventWindowAccessOverlapCorruption(personalRole, template);
      maxSeqno += SEQNO_STEP;
      RoleInheritance inheritance = OBProvider.getInstance().get(RoleInheritance.class);
      inheritance.setNewOBObject(true);
      inheritance.setClient(personalRole.getClient());
      inheritance.setOrganization(personalRole.getOrganization());
      inheritance.setActive(true);
      inheritance.setRole(personalRole);
      inheritance.setInheritFrom(template);
      inheritance.setSequenceNumber(maxSeqno);
      OBDal.getInstance().save(inheritance);
      // Saving this AD_Role_Inheritance row fires core's RoleInheritanceEventHandler, which
      // fans out through EVERY registered AccessTypeInjector (window, tab, field, process,
      // OBUIAPP process, ...) to copy the template's accesses onto personalRole. Each injector's
      // own copyRoleAccess() bypasses the client/org check while it saves (OBContext.setAdminMode
      // (false)), but that bypass is popped again before this flush runs, so anything it left
      // dirty/pending gets re-checked HERE under the caller's normal context. That's harmless for
      // window access (reconcileWindowAccessAfterComposition below re-pins its client/org right
      // after), but a system-level template (AD_Client_ID = '0', see
      // EnsureSystemRoleTemplatesScript) that also grants process/report access has nothing
      // equivalent for those rows, so the copy — still carrying the template's client "0" — fails
      // this flush with OBSecurityException as soon as a template actually has any (ETP-4830's
      // own EnsureSystemRoleTemplatesScript#reconcileProcessAccess started seeding those rows).
      // Same bypass RoleInheritanceManager's own internal saves use, scoped to just this flush.
      OBContext.setAdminMode(false);
      try {
        OBDal.getInstance().flush();
      } finally {
        OBContext.restorePreviousMode();
      }
      added++;
    }

    if (added > 0) {
      reconcileWindowAccessAfterComposition(personalRole, templates);
    }
    syncShowAccountingFieldsFlag(personalRole, templates);
    return new int[] { added, removed };
  }

  /**
   * ETP-4877 — keeps {@code AD_Role.EM_ETGO_Show_Acct_Fields} (ETP-4520; gates the
   * {@code showAccountingFields} capability {@code SFWindowAccessMap} exposes) in sync with
   * whether {@code personalRole} currently inherits from the system Finance template ({@link
   * SystemRoleTemplates#FINANCE_ROLE_ID}). {@code 'Y'} when {@code templates} — the FULL desired
   * set this call is reconciling to, not merely what changed — contains the Finance template;
   * {@code 'N'} otherwise (including when Finance is being removed on this very call).
   *
   * <p>This is the "going forward" half of a two-front fix: {@code
   * SFWindowAccessMap#resolveShowAccountingFields} reads the column as a flat stored value with
   * no join to {@code AD_Role_Inheritance} at read time, so the column is a DERIVED fact, not an
   * independent one — whoever last changed a role's template inheritance is responsible for
   * keeping it in sync, and this was the one gap in that chain: nothing previously wrote this
   * column when {@code UserRoleCompositionService#assignTemplateRoles} changed a personal role's
   * templates (only {@code EnsureSystemRoleTemplatesScript}/R16/R23 ever set it, once, at
   * role-clone time, for the old per-client-clone model). Called unconditionally at the end of
   * every {@link #reconcileInheritances(Role, List)} call — including a no-op reconciliation — so
   * this is self-healing on every {@code assignTemplateRoles} call, not only when something
   * actually changed. The retroactive half (every PRE-EXISTING personal role, not touched by a
   * live composition call) is the sibling {@code R26-tenant-owner-and-personal-role-retrofit.sql}
   * data-fix's Step 8b, in {@code etendo_schema_forge} — same predicate, kept in lockstep; a
   * change to one must be mirrored in the other.</p>
   *
   * <p>Native SQL, not a DAL property — same reasoning {@code OwnerSupport} and {@code
   * SFWindowAccessMap#resolveShowAccountingFields} document for this exact column: it was added
   * straight to the physical table (ETP-4520) and is not mapped as a typed entity property.
   * Guarded to a no-op UPDATE when the value already matches, so a call that changes nothing here
   * costs one cheap, index-backed statement.</p>
   */
  private void syncShowAccountingFieldsFlag(Role personalRole, List<Role> templates) {
    boolean shouldShowAcctFields = templates.stream()
        .anyMatch(template -> SystemRoleTemplates.FINANCE_ROLE_ID.equals(template.getId()));
    String desired = shouldShowAcctFields ? "Y" : "N";
    Session session = OBDal.getInstance().getSession();
    NativeQuery<?> update = session.createNativeQuery(
        "UPDATE ad_role SET em_etgo_show_acct_fields = :desired, updated = now(), updatedby = '0' "
            + "WHERE ad_role_id = :roleId AND em_etgo_show_acct_fields <> :desired");
    update.setParameter("desired", desired);
    update.setParameter("roleId", personalRole.getId());
    update.executeUpdate();
  }

  /**
   * Removes {@code personalRole}'s existing active {@code AD_Window_Access} row for every window
   * {@code template} also grants, BEFORE the caller saves the new {@code AD_Role_Inheritance} —
   * see the class javadoc for why this avoids core's corrupting UPDATE path entirely (it forces
   * every one of {@code template}'s windows through the safe CREATE path instead). A no-op when
   * {@code template} grants no windows the personal role doesn't already have from elsewhere.
   *
   * <p>Uses the SAME {@code OBContext.setAdminMode(false)} bypass core's own {@code
   * deleteRoleAccess} uses for removing a cross-client-owned inherited access row — scoped to
   * just this removal, not the whole method.</p>
   */
  private void preventWindowAccessOverlapCorruption(Role personalRole, Role template) {
    Set<String> templateWindowIds = activeWindowIdsFor(template);
    if (templateWindowIds.isEmpty()) {
      return;
    }
    List<WindowAccess> overlapping = new ArrayList<>();
    for (WindowAccess access : findActiveWindowAccess(personalRole)) {
      if (templateWindowIds.contains(access.getWindow().getId())) {
        overlapping.add(access);
      }
    }
    if (overlapping.isEmpty()) {
      return;
    }
    OBContext.setAdminMode(false);
    try {
      for (WindowAccess access : overlapping) {
        // Core's own InheritedAccessEnabledEventHandler#doAction rejects deleting a row whose
        // inheritedFrom is still set ("NotDeleteInheritedAccess") — mirrors the exact sequence
        // core's own deleteRoleAccess/propagateDeletedAccess use: null the field on the in-memory
        // object FIRST, so the interceptor's delete-time check sees it already cleared.
        access.setInheritedFrom(null);
        OBDal.getInstance().remove(access);
      }
      OBDal.getInstance().flush();
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /**
   * Final pass over {@code personalRole}'s inherited {@code AD_Window_Access} rows, run once
   * after the whole add/remove loop in {@link #reconcileInheritances(Role, List)} — see the class
   * javadoc for the full rationale. For every row whose {@code inheritedFrom} is set (i.e.
   * template-derived, never a manually-granted one): (1) pins {@code client}/{@code
   * organization} back to {@code personalRole}'s own values if they differ; (2) widens it to full
   * ("✓") access if {@code templates} contains ANY role that grants that window full access,
   * even if the row core's propagation happens to have left behind reflects only a read-only
   * ("R") template — the most-permissive-wins union the ticket requires. Never narrows a row from
   * full to read-only (a full grant, once resolved, always wins).
   */
  private void reconcileWindowAccessAfterComposition(Role personalRole, List<Role> templates) {
    Map<String, Boolean> mostPermissiveByWindowId = mostPermissiveWindowAccess(templates);
    if (mostPermissiveByWindowId.isEmpty()) {
      return;
    }
    List<WindowAccess> corrected = new ArrayList<>();
    for (WindowAccess access : findActiveWindowAccess(personalRole)) {
      if (access.getInheritedFrom() == null) {
        continue;
      }
      boolean changed = false;
      if (!sameId(access.getClient(), personalRole.getClient())) {
        access.setClient(personalRole.getClient());
        changed = true;
      }
      if (!sameId(access.getOrganization(), personalRole.getOrganization())) {
        access.setOrganization(personalRole.getOrganization());
        changed = true;
      }
      Boolean shouldBeFull = mostPermissiveByWindowId.get(access.getWindow().getId());
      if (Boolean.TRUE.equals(shouldBeFull) && !Boolean.TRUE.equals(access.isEditableField())) {
        access.setEditableField(true);
        changed = true;
      }
      if (changed) {
        corrected.add(access);
      }
    }
    if (corrected.isEmpty()) {
      return;
    }
    OBContext.setAdminMode(false);
    try {
      for (WindowAccess access : corrected) {
        OBDal.getInstance().save(access);
      }
      OBDal.getInstance().flush();
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /**
   * Computes, per window id, whether ANY of {@code templates} grants that window full ("✓")
   * access — the independent source of truth {@link #reconcileWindowAccessAfterComposition} uses
   * to resolve the most-permissive-wins union, deliberately computed from the templates' OWN
   * current {@code AD_Window_Access} rows rather than trusting whatever single row core's
   * per-window propagation happened to leave on the personal role.
   */
  private Map<String, Boolean> mostPermissiveWindowAccess(List<Role> templates) {
    Map<String, Boolean> result = new LinkedHashMap<>();
    for (Role template : templates) {
      for (WindowAccess access : findActiveWindowAccess(template)) {
        String windowId = access.getWindow().getId();
        boolean full = Boolean.TRUE.equals(access.isEditableField());
        result.merge(windowId, full, (a, b) -> a || b);
      }
    }
    return result;
  }

  private Set<String> activeWindowIdsFor(Role role) {
    Set<String> windowIds = new LinkedHashSet<>();
    for (WindowAccess access : findActiveWindowAccess(role)) {
      windowIds.add(access.getWindow().getId());
    }
    return windowIds;
  }

  /**
   * Queries {@code AD_Window_Access} fresh for {@code role} — deliberately NOT {@code
   * role.getADWindowAccessList()}, for the same staleness reason {@link
   * #findExistingInheritances(Role)} queries {@code AD_Role_Inheritance} fresh instead of
   * trusting the entity's own collection property (see that method's javadoc).
   */
  @SuppressWarnings("unchecked")
  private List<WindowAccess> findActiveWindowAccess(Role role) {
    OBCriteria<WindowAccess> criteria = OBDal.getInstance().createCriteria(WindowAccess.class);
    criteria.add(Restrictions.eq(WindowAccess.PROPERTY_ROLE, role));
    criteria.add(Restrictions.eq(WindowAccess.PROPERTY_ACTIVE, true));
    return criteria.list();
  }

  private static boolean sameId(BaseOBObject a, BaseOBObject b) {
    String idA = a == null ? null : (String) a.getId();
    String idB = b == null ? null : (String) b.getId();
    return idA != null && idA.equals(idB);
  }

  /**
   * Queries {@code AD_Role_Inheritance} fresh for {@code personalRole}, ordered by {@code
   * Seqno} ascending — deliberately NOT {@code personalRole.getADRoleInheritanceList()}; see
   * the javadoc on {@link #reconcileInheritances(Role, List)} for why. Mirrors core's own
   * {@code RoleInheritanceManager#getRoleInheritancesList(Role, Role, boolean)}.
   */
  @SuppressWarnings("unchecked")
  List<RoleInheritance> findExistingInheritances(Role personalRole) {
    OBCriteria<RoleInheritance> criteria = OBDal.getInstance()
        .createCriteria(RoleInheritance.class);
    criteria.add(Restrictions.eq(RoleInheritance.PROPERTY_ROLE, personalRole));
    criteria.addOrderBy(RoleInheritance.PROPERTY_SEQUENCENUMBER, true);
    return criteria.list();
  }

  /**
   * ETP-4906 — filters {@code inheritances} down to the {@code InheritFrom} ids that are
   * themselves still active templates, in {@code Seqno} order. Shared by {@code
   * UserRoleCompositionService#getAppliedTemplateRoleIds(String, Role)} and (in its bulk form,
   * {@code UserRoleCompositionService#findActiveTemplateIdsByPersonalRoleId(Set)}) {@code
   * #getAppliedTemplateRoleIdsForClient(String)} — a personal role can retain a stale {@code
   * AD_Role_Inheritance} row pointing at a template that was later deactivated or un-templated
   * (the trigger documented in {@code UserRoleCompositionService}'s own javadoc only blocks
   * deactivation WHILE an inheritance depends on it, not un-linking the inheritance itself
   * first), so this is not a redundant check.
   */
  List<String> activeTemplateIds(List<RoleInheritance> inheritances) {
    List<String> ids = new ArrayList<>();
    for (RoleInheritance inheritance : inheritances) {
      Role template = inheritance.getInheritFrom();
      if (template != null && Boolean.TRUE.equals(template.isActive())
          && Boolean.TRUE.equals(template.isTemplate())) {
        ids.add(template.getId());
      }
    }
    return ids;
  }
}
