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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hibernate.criterion.Restrictions;
import org.openbravo.base.exception.OBException;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.access.Role;
import org.openbravo.model.ad.access.RoleInheritance;
import org.openbravo.model.ad.access.User;
import org.openbravo.model.ad.access.UserRoles;
import org.openbravo.model.ad.access.WindowAccess;
import org.openbravo.model.common.enterprise.Organization;

import com.etendoerp.go.schemaforge.util.OwnerSupport;
import com.etendoerp.go.schemaforge.util.UserRoleSyncSupport;

/**
 * ETP-4852 — server-side mechanism behind "compose a user's access from 1+ system-level
 * template roles" (the rework of ETP-4512's single-shared-role assign UI). Called by the
 * {@code SFAssignUserRoles} webhook; kept as a standalone, webhook-agnostic service so the
 * webhook layer stays a thin parameter-marshalling shim.
 *
 * <p><b>The mechanism, end to end:</b></p>
 * <ol>
 *   <li>Each requested template must be an ACTIVE role with {@code IsTemplate = 'Y'} — core's
 *   own {@code RoleInheritanceEventHandler} enforces this too (it would reject otherwise), but
 *   this class checks it first for a clear {@link OBException} instead of core's generic
 *   translated-message exception.</li>
 *   <li>The user gets (or keeps) exactly ONE personal, non-template, non-client-admin role —
 *   see {@link #resolveOrCreatePersonalRole(User)} for how "personal" is detected/created.</li>
 *   <li>The personal role's {@code AD_Role_Inheritance} rows are reconciled to match the
 *   requested template set exactly: missing ones are added (each via a real {@code OBDal.save}
 *   so core's {@code RoleInheritanceEventHandler}/{@code RoleInheritanceManager} propagate the
 *   template's {@code AD_Window_Access} — and every other inheritable access type — onto the
 *   personal role automatically, with zero hand-rolled copying here); no-longer-requested ones
 *   are removed (each via a real {@code OBDal.remove}, so core retracts the accesses it had
 *   propagated). This is a set-reconciliation, not an additive grow-only list.</li>
 *   <li>{@code AD_User_Roles} is synced to exactly one active row for the personal role (via
 *   {@link UserRoleSyncSupport}, shared with the older single-role assign path), and {@code
 *   AD_User.Default_Ad_Role_ID} is updated to match — both are what real login/window-access
 *   checks and the classic role-switcher UI read.</li>
 * </ol>
 *
 * <p><b>Never touches the "Admin" role.</b> The client-level {@code is_client_admin = 'Y'} role
 * is excluded from every path here — it is neither a valid personal-role candidate (excluded in
 * {@link #resolveOrCreatePersonalRole}) nor a valid template (excluded in {@link
 * #resolveAndValidateTemplates}, on top of the {@code IsTemplate} check already excluding it in
 * this fleet's data).</p>
 *
 * <p>Callers MUST already be running inside an authenticated, admin/client-admin-checked
 * {@code OBContext} (the webhook layer's job — this class never itself decides WHO may call
 * it AT ALL). {@link #assignTemplateRoles(String, List)} enters {@link
 * OBContext#setAdminMode(boolean)} for its own duration purely to bypass row-level security
 * while writing across clients (the personal role's client vs. the template's, which may be
 * {@code '0'}), never to decide access — mirroring every other write in this module (e.g.
 * {@code UserRoleAssignmentHandler}). Openbravo's admin-mode flag is stack-based, so an
 * already-admin-mode caller nesting into this method is safe (push/pop), not a double-entry
 * bug.</p>
 *
 * <p><b>The tenant BOUNDARY of the target user, however, IS this class's own job</b> (REVIEW
 * cycle 1 finding, ETP-4852) — {@code NeoAccessHelper#isAdminOrClientAdmin}, the webhook's only
 * gate, treats a per-tenant client-admin the same as the literal System Administrator, so
 * without an explicit check here a client-admin for Tenant A could target any {@code userId} in
 * Tenant B. {@link #assignTemplateRoles(String, List, Role)} takes the caller's already-resolved
 * {@link Role} and calls {@link #enforceCallerClientBoundary(User, Role)} right after resolving
 * {@code user}, before any template validation or write. The 2-arg {@link
 * #assignTemplateRoles(String, List)} overload passes a {@code null} caller (no boundary to
 * enforce) — for plain unit tests and any other caller with no per-request identity to check
 * against; real webhook callers MUST use the 3-arg overload with their own resolved role, as
 * {@code SFAssignUserRoles} does.</p>
 *
 * <p><b>Template-role lifecycle — deactivation while depended-upon is a DB-level non-issue, not
 * an application-level one</b> (QA finding, ETP-4852, confirmed live against Postgres): core's own
 * {@code AD_ROLE_CHECK_TRG} trigger (a {@code BEFORE UPDATE} on {@code AD_ROLE} — see {@code
 * src-db/database/model/triggers/AD_ROLE_CHECK_TRG.xml}) already refuses to set {@code
 * IsActive='N'} (or unset {@code IsTemplate}) on any role that an {@code AD_Role_Inheritance} row
 * still points {@code InheritFrom} — for EVERY writer, {@code OBDal} or raw SQL alike, unless
 * triggers are explicitly disabled via core's own {@code AD_isTriggerEnabled()} bypass (the
 * mechanism data-import/migration tooling uses). So "a personal role keeps inheriting from a
 * template that went inactive behind its back" cannot occur through any normal write path — this
 * class deliberately has no defensive code for it, and {@code UserRoleCompositionServiceIntegrationTest}
 * documents (rather than simulates) this instead of faking the state past the trigger. <b>Relevant
 * for ETP-4877's bulk retrofit:</b> template deactivation-while-depended-upon is not something that
 * retrofit's own code needs to guard against either — the DB already refuses it outright.</p>
 *
 * <p><b>Cross-template {@code AD_Window_Access} overlap — self-contained fix for a latent core
 * bug (found via ETP-4878's overlapping matrix, QA/Sentinel; fixed here, not in core, per an
 * explicit human decision).</b> Composing a personal role from 2+ templates that grant the SAME
 * window used to throw {@code OBSecurityException} and roll back the whole call. Root cause,
 * traced into {@code org.openbravo.role.inheritance}: {@code WindowAccessInjector} never
 * overrides {@code AccessTypeInjector#getSkippedProperties()} (the base default only skips
 * {@code creationDate}/{@code createdBy}), so when a SECOND template's inheritance propagates to
 * a window a FIRST template already covered, {@code RoleInheritanceManager#handleAccess} takes
 * the UPDATE path ({@code updateRoleAccess} → {@code DalUtil.copyToTarget}), which overwrites the
 * personal (tenant-client) role's existing {@code AD_Window_Access} row with the template's OWN
 * {@code client}/{@code organization} (system client {@code "0"}) — the very next flush then
 * fails {@code SecurityChecker.checkWriteAccess} under the tenant-scoped {@code OBContext}. The
 * CREATE path ({@code copyRoleAccess}) does not hit this, because {@link
 * org.openbravo.dal.core.OBContext#setAdminMode(boolean)}'s bypass around it is still active
 * when {@code Session.save()}'s interceptor callback fires (new-entity saves are checked
 * immediately, before the bypass is popped); the UPDATE path's dirty-check-driven callback fires
 * later, at the CALLER's own flush, by which point that inner bypass has already been restored.
 * </p>
 *
 * <p>{@link #reconcileInheritances(Role, List)} therefore does two things beyond core's own
 * mechanism, both scoped to {@code WindowAccess} only (the reported access type — not a generic
 * fix for every {@code AccessTypeInjector}): (1) {@link
 * #preventWindowAccessOverlapCorruption(Role, Role)}, called right before a new template's
 * {@code AD_Role_Inheritance} is saved, removes the personal role's existing active {@code
 * WindowAccess} row for every window the about-to-be-added template also grants — so core's
 * propagation finds no existing row and takes the safe CREATE path for every one of that
 * template's windows, overlapping or not; (2) {@link #reconcileWindowAccessAfterComposition(Role,
 * List)}, run once after the whole add/remove loop, pins {@code client}/{@code organization} on
 * every inherited row back to the personal role's OWN values (belt-and-braces — defends the
 * CREATE path too, even though it has not been observed to corrupt it) and resolves the
 * ticket-required "most-permissive wins" union: a window ends up full ("✓") access if ANY
 * currently-applied template grants it full, read-only ("R") only if ALL of them do. Both helpers
 * use the SAME narrow, method-scoped {@code OBContext.setAdminMode(false)} bypass core's own
 * {@code copyRoleAccess}/{@code updateRoleAccess}/{@code deleteRoleAccess} use for exactly this
 * kind of cross-client write — never the outer, method-wide bypass, which stays at {@code
 * setAdminMode(true)} to keep {@link #enforceCallerClientBoundary(User, Role)}'s tenant-boundary
 * guarantee intact for the rest of this class.</p>
 *
 * <p><b>The above two helpers only ever protect the ONE {@code personalRole} passed into a given
 * {@link #assignTemplateRoles(String, List)} call — widened beyond that by {@link
 * com.etendoerp.go.roles.WindowAccessOverlapCorruptionGuard} (ETP-4906, Task B6).</b> Core's own
 * propagation is not scoped to one role either — it fires for EVERY role that inherits from
 * whichever template's {@code AD_Window_Access} just changed, so any OTHER already-existing role
 * inheriting from a touched template was getting swept into the same corrupting write with zero
 * protection, whether the trigger was a DIFFERENT user's {@code assignTemplateRoles} call or (live-
 * reproduced by {@code UserRoleCompositionServiceOverlapIntegrationTest}, AND by a live Etendo
 * Classic UI reproduction — see the ETP-4906 plan doc's "B6 Findings — Root Cause") a completely
 * unrelated direct edit to a template's own {@code AD_Window_Access}, with no {@code
 * UserRoleCompositionService} code anywhere in the call stack. That system-wide case cannot be
 * closed from inside this class at all — see {@code WindowAccessOverlapCorruptionGuard}'s own
 * class javadoc for the full write-up, including why its FIRST design (a reactive, correction-
 * based observer) was structurally impossible and had to be replaced with a prevention-based one
 * before it actually worked. The two helpers here are left as-is (still valuable as an eager,
 * pre-emptive defense for the role this class is actively composing) — {@code
 * WindowAccessOverlapCorruptionGuard} is the belt-and-braces net for every role this class never
 * even knows about.</p>
 */
public class UserRoleCompositionService {

  private static final Logger log = LogManager.getLogger(UserRoleCompositionService.class);

  /** {@code AD_Role.UserLevel} shared by every fixed role in this fleet (client + org). */
  private static final String FIXED_ROLE_USER_LEVEL = SystemRoleTemplates.FIXED_ROLE_USER_LEVEL;

  /** Personal-role name prefix — see {@link #buildPersonalRoleName(User)}. */
  private static final String PERSONAL_ROLE_NAME_PREFIX = "Personal – ";

  /** Increment used when minting a fresh {@code AD_Role_Inheritance.Seqno}. */
  private static final long SEQNO_STEP = 10L;

  /**
   * The literal System Administrator {@code AD_Role_ID} — the ONLY role id that bypasses
   * {@link #enforceCallerClientBoundary(User, Role)}. Mirrors the same literal id
   * {@code NeoAccessHelper#isAdminOrClientAdmin} checks at the webhook-gating layer, but
   * deliberately NOT that method itself: {@code isAdminOrClientAdmin} also returns {@code true}
   * for a mere {@code isClientAdmin()} role, which must NOT bypass a tenant-boundary check.
   */
  private static final String SYSTEM_ADMINISTRATOR_ROLE_ID = "0";

  /**
   * Result of {@link #assignTemplateRoles(String, List)} — everything the webhook needs to
   * build its JSON response.
   */
  public static final class AssignmentResult {
    /** The {@code AD_User_ID} the roles were composed for. */
    public final String userId;
    /** The {@code AD_Role_ID} of the user's personal role (found or newly created). */
    public final String personalRoleId;
    /** The FULL set of template role ids now applied to the personal role, in request order. */
    public final List<String> appliedTemplateRoleIds;
    /** How many {@code AD_Role_Inheritance} rows were newly added by this call. */
    public final int addedCount;
    /** How many {@code AD_Role_Inheritance} rows were removed by this call. */
    public final int removedCount;

    /**
     * Creates an immutable summary of one {@link #assignTemplateRoles(String, List)} call.
     *
     * @param userId the {@code AD_User_ID} the roles were composed for
     * @param personalRoleId the {@code AD_Role_ID} of the user's personal role
     * @param appliedTemplateRoleIds the FULL set of template role ids now applied
     * @param addedCount how many {@code AD_Role_Inheritance} rows were newly added
     * @param removedCount how many {@code AD_Role_Inheritance} rows were removed
     */
    public AssignmentResult(String userId, String personalRoleId,
        List<String> appliedTemplateRoleIds, int addedCount, int removedCount) {
      this.userId = userId;
      this.personalRoleId = personalRoleId;
      this.appliedTemplateRoleIds = appliedTemplateRoleIds;
      this.addedCount = addedCount;
      this.removedCount = removedCount;
    }
  }

  /**
   * Composes {@code userId}'s access from exactly the template roles in {@code
   * templateRoleIds} — see the class javadoc for the full mechanism. Idempotent: calling it
   * again with the same set is a no-op reconciliation (0 added, 0 removed) once the first call
   * has run.
   *
   * @param userId the {@code AD_User_ID} to compose roles for
   * @param templateRoleIds the desired FULL set of template role ids (order-insensitive,
   *     duplicates ignored); an empty list revokes every template-derived access, leaving the
   *     user's personal role in place but with no inheritances
   * @return a summary of what changed
   * @throws OBException if {@code userId} is missing/unresolvable, {@code templateRoleIds} is
   *     {@code null}, or any requested id is not an active, non-admin template role
   * @see #assignTemplateRoles(String, List, Role) the overload that also enforces the caller's
   *     tenant boundary against {@code userId} — use that one for any real webhook/request path
   */
  public AssignmentResult assignTemplateRoles(String userId, List<String> templateRoleIds) {
    return assignTemplateRoles(userId, templateRoleIds, null);
  }

  /**
   * Same as {@link #assignTemplateRoles(String, List, Role)}, but also enforces the ETP-4830
   * owner-protection rule against {@code userId} — see {@link #enforceOwnerProtection(User,
   * String)}. Real webhook callers (e.g. {@code SFAssignUserRoles}) MUST use this overload,
   * passing the caller's own {@code AD_User_ID}. A {@code null} {@code callerUserId} skips the
   * check entirely, mirroring this class's existing {@code callerRole=null} convention — kept for
   * plain unit tests and any other caller with no per-request identity to check against.
   *
   * @param userId the {@code AD_User_ID} to compose roles for
   * @param templateRoleIds the desired FULL set of template role ids — see {@link
   *     #assignTemplateRoles(String, List)}
   * @param callerRole the role making this request — see {@link #assignTemplateRoles(String,
   *     List, Role)}
   * @param callerUserId the {@code AD_User_ID} making this request, already resolved by the
   *     caller BEFORE entering admin mode, or {@code null} to skip the owner-protection check
   * @return a summary of what changed
   * @throws OBException if {@code userId} is missing/unresolvable, {@code templateRoleIds} is
   *     {@code null}, any requested id is not an active, non-admin template role, {@code
   *     callerRole} is a non-system role whose client differs from {@code userId}'s, or {@code
   *     userId} is flagged as its client's owner and {@code callerUserId} is not that same user
   */
  public AssignmentResult assignTemplateRoles(String userId, List<String> templateRoleIds,
      Role callerRole, String callerUserId) {
    if (StringUtils.isBlank(userId)) {
      throw new OBException("Missing user id for role composition");
    }
    if (templateRoleIds == null) {
      throw new OBException("Missing template role id list for role composition");
    }
    User user = OBDal.getInstance().get(User.class, userId);
    if (user == null) {
      throw new OBException("User not found: " + userId);
    }
    enforceCallerClientBoundary(user, callerRole);
    enforceOwnerProtection(user, callerUserId);

    List<Role> templates = resolveAndValidateTemplates(templateRoleIds);

    OBContext.setAdminMode(true);
    try {
      Role personalRole = resolveOrCreatePersonalRole(user);
      personalRole = discardStaleSessionState(personalRole);
      int[] counters = reconcileInheritances(personalRole, templates);

      user.setDefaultRole(personalRole);
      OBDal.getInstance().save(user);
      OBDal.getInstance().flush();

      UserRoleSyncSupport.syncSingleActiveUserRole(user, personalRole);

      List<String> appliedIds = new ArrayList<>();
      for (Role template : templates) {
        appliedIds.add(template.getId());
      }
      log.info("Composed user {} personal role {} from templates {} (+{} / -{})", userId,
          personalRole.getId(), appliedIds, counters[0], counters[1]);
      return new AssignmentResult(userId, personalRole.getId(), appliedIds, counters[0],
          counters[1]);
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /**
   * Rejects reassigning the OWNER's role composition from anyone other than the owner
   * themselves (ETP-4830) — the role-assignment-endpoint counterpart to {@code
   * UserRoleAssignmentHandler#rejectNonOwnerEditingOwner}'s generic {@code AD_User} PUT/PATCH
   * guard. Both must independently cover the owner protection: an admin reassigning the owner's
   * role through THIS endpoint never goes through {@code UserRoleAssignmentHandler}'s write path
   * at all, so that guard alone would not close this gap.
   *
   * <p>A no-op — same "nothing to enforce" convention {@link #enforceCallerClientBoundary} uses
   * for a {@code null} {@code callerRole} — when {@code callerUserId} is {@code null} (no caller
   * identity supplied), or when {@code user} is not flagged as owner at all (every pre-existing
   * user until a separate, human-reviewed backfill data-fix runs). When {@code user} IS the owner
   * and {@code callerUserId} is that same user (the owner recomposing their own access), this is
   * also a no-op — only a DIFFERENT caller targeting the owner is rejected.</p>
   *
   * @param user the already-resolved target user
   * @param callerUserId the {@code AD_User_ID} making this request, or {@code null} to skip
   * @throws OBException if {@code user} is flagged as owner and {@code callerUserId} is not that
   *     same user
   */
  private void enforceOwnerProtection(User user, String callerUserId) {
    if (callerUserId == null) {
      return;
    }
    if (!OwnerSupport.isOwner(user.getId())) {
      return;
    }
    if (callerUserId.equals(user.getId())) {
      return;
    }
    throw new OBException(
        "This user is the tenant owner — only the owner can reassign their own roles: "
            + user.getId());
  }

  /**
   * Same as {@link #assignTemplateRoles(String, List)}, but also enforces that {@code
   * callerRole} may target {@code userId} at all — see {@link
   * #enforceCallerClientBoundary(User, Role)} and the class javadoc for why this matters
   * (REVIEW cycle 1, ETP-4852: a client-admin must never be able to reassign or strip another
   * tenant's user). Real webhook callers (e.g. {@code SFAssignUserRoles}) MUST use this
   * overload, passing the role they already resolved for the current request.
   *
   * @param userId the {@code AD_User_ID} to compose roles for
   * @param templateRoleIds the desired FULL set of template role ids — see {@link
   *     #assignTemplateRoles(String, List)}
   * @param callerRole the role making this request, already resolved by the caller BEFORE
   *     entering admin mode — {@code null} means "no per-request identity to check" (skips the
   *     boundary check entirely; see {@link #enforceCallerClientBoundary(User, Role)})
   * @return a summary of what changed
   * @throws OBException if {@code userId} is missing/unresolvable, {@code templateRoleIds} is
   *     {@code null}, any requested id is not an active, non-admin template role, or {@code
   *     callerRole} is a non-system role whose client differs from {@code userId}'s
   * @see #assignTemplateRoles(String, List, Role, String) the overload that ALSO enforces the
   *     ETP-4830 owner-protection rule — real webhook callers MUST use that one instead
   */
  public AssignmentResult assignTemplateRoles(String userId, List<String> templateRoleIds,
      Role callerRole) {
    return assignTemplateRoles(userId, templateRoleIds, callerRole, null);
  }

  /**
   * ETP-4906 — read-only counterpart to {@link #assignTemplateRoles(String, List)}: "which
   * template roles does {@code userId} currently have applied", with NO caller-boundary check
   * (see {@link #getAppliedTemplateRoleIds(String, Role)} for the overload real webhook callers
   * MUST use instead). Delegates with {@code callerRole=null} — kept for plain unit tests and any
   * other caller with no per-request identity to check against, mirroring
   * {@link #assignTemplateRoles(String, List)}'s own 2-arg/3-arg split.
   *
   * @param userId the {@code AD_User_ID} to look up
   * @return the FULL set of active-template role ids currently applied to {@code userId}'s
   *     personal role, in {@code AD_Role_Inheritance.Seqno} order; an empty list if {@code
   *     userId} has no personal role yet (never creates one as a side effect of a read)
   * @throws OBException if {@code userId} is missing/unresolvable
   */
  public List<String> getAppliedTemplateRoleIds(String userId) {
    return getAppliedTemplateRoleIds(userId, null);
  }

  /**
   * Same as {@link #getAppliedTemplateRoleIds(String)}, but also enforces that {@code
   * callerRole} may target {@code userId} at all — see {@link
   * #enforceCallerClientBoundary(User, Role)}. Real webhook callers (e.g. {@code
   * SFUserRoleAssignments}) MUST use this overload, passing the role they already resolved for
   * the current request, for the exact same reason {@code SFAssignUserRoles} passes its own
   * {@code currentRole} into {@link #assignTemplateRoles(String, List, Role)}: {@code
   * NeoAccessHelper#isAdminOrClientAdmin} alone does not stop a client-admin from targeting
   * another tenant's user.
   *
   * <p>Deliberately does NOT call {@link #resolveOrCreatePersonalRole(User)} — a user with no
   * personal role yet simply has zero applied templates; minting one as a side effect of a READ
   * would be a surprising, unnecessary write.</p>
   *
   * @param userId the {@code AD_User_ID} to look up
   * @param callerRole the role making this request, already resolved by the caller BEFORE
   *     entering admin mode — {@code null} means "no per-request identity to check" (skips the
   *     boundary check entirely)
   * @return the FULL set of active-template role ids currently applied to {@code userId}'s
   *     personal role, in {@code AD_Role_Inheritance.Seqno} order; an empty list if {@code
   *     userId} has no personal role yet
   * @throws OBException if {@code userId} is missing/unresolvable, or {@code callerRole} is a
   *     non-system role whose client differs from {@code userId}'s
   */
  public List<String> getAppliedTemplateRoleIds(String userId, Role callerRole) {
    if (StringUtils.isBlank(userId)) {
      throw new OBException("Missing user id for role assignment lookup");
    }
    User user = OBDal.getInstance().get(User.class, userId);
    if (user == null) {
      throw new OBException("User not found: " + userId);
    }
    enforceCallerClientBoundary(user, callerRole);

    OBContext.setAdminMode(true);
    try {
      Role personalRole = findExistingPersonalRole(user);
      if (personalRole == null) {
        return new ArrayList<>();
      }
      return activeTemplateIds(findExistingInheritances(personalRole));
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /**
   * ETP-4906 — bulk grid counterpart to {@link #getAppliedTemplateRoleIds(String, Role)}: every
   * user's applied template ids for the WHOLE client, in one query pass — deliberately NOT a loop
   * calling the single-user method once per user (that would be 4-6 queries per row on a
   * potentially large grid). Every user of {@code clientId} gets an entry (empty list if they
   * have no qualifying personal role), so callers never need a separate "does this user have an
   * entry at all" check.
   *
   * <p>Applies the exact same "is this actually a personal role" identity check {@link
   * #isReusablePersonalRole(User, Role)} does for the write path — active, non-template,
   * non-client-admin, same client, not itself the {@code InheritFrom} target of some OTHER role's
   * inheritance, and exclusively assigned (via {@code AD_User_Roles}) to the ONE user whose {@code
   * Default_Ad_Role_ID} points at it — but re-expressed as a handful of {@code
   * Restrictions.in(...)} bulk queries instead of one query per candidate role, so the two
   * definitions must be kept in lockstep (see {@link #isReusablePersonalRole(User, Role)}'s own
   * javadoc for what each check defends against). This guarantees the grid's initial chip state
   * exactly matches what a subsequent no-op save through {@link #assignTemplateRoles(String,
   * List, Role)} would reconcile to (0 added, 0 removed).</p>
   *
   * <p>No caller-boundary check here — unlike the single-user overload, {@code clientId} itself
   * IS the boundary: real callers (e.g. {@code SFUserRoleAssignments}) must pass their OWN
   * resolved {@code currentRole.getClient().getId()}, never a caller-supplied client id (no such
   * parameter is exposed), mirroring {@code SFRolesOverview}'s identical "always the caller's own
   * client" convention.</p>
   *
   * @param clientId the {@code AD_Client_ID} whose users should be resolved
   * @return every user of {@code clientId} mapped to their FULL set of active-template role ids
   *     (insertion order, possibly empty per user); never {@code null}
   * @throws OBException if {@code clientId} is missing
   */
  public Map<String, List<String>> getAppliedTemplateRoleIdsForClient(String clientId) {
    if (StringUtils.isBlank(clientId)) {
      throw new OBException("Missing client id for bulk role assignment lookup");
    }
    Map<String, List<String>> result = new LinkedHashMap<>();

    OBContext.setAdminMode(true);
    try {
      List<User> users = findUsersForClient(clientId);
      for (User user : users) {
        result.put(user.getId(), new ArrayList<>());
      }
      if (users.isEmpty()) {
        return result;
      }

      Map<String, Role> candidateRolesById = fetchCandidateDefaultRoles(users);
      if (candidateRolesById.isEmpty()) {
        return result;
      }

      Set<String> inheritFromTargetRoleIds =
          findRoleIdsUsedAsInheritFromTarget(candidateRolesById.keySet());
      Map<String, List<String>> assignedUserIdsByRoleId =
          findActiveAssignedUserIdsByRoleId(candidateRolesById.keySet());

      Set<String> confirmedPersonalRoleIds = new LinkedHashSet<>();
      Map<String, String> personalRoleIdByUserId = new LinkedHashMap<>();
      for (User user : users) {
        confirmPersonalRoleForUser(user, candidateRolesById, inheritFromTargetRoleIds,
            assignedUserIdsByRoleId, confirmedPersonalRoleIds, personalRoleIdByUserId);
      }

      if (confirmedPersonalRoleIds.isEmpty()) {
        return result;
      }
      Map<String, List<String>> templateIdsByPersonalRoleId =
          findActiveTemplateIdsByPersonalRoleId(confirmedPersonalRoleIds);
      for (Map.Entry<String, String> entry : personalRoleIdByUserId.entrySet()) {
        result.put(entry.getKey(),
            templateIdsByPersonalRoleId.getOrDefault(entry.getValue(), Collections.emptyList()));
      }
      return result;
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /**
   * Per-user classification step of {@link #getAppliedTemplateRoleIdsForClient(String)} —
   * extracted purely to keep that method's own cognitive complexity and per-loop break/continue
   * count within the SonarQube gate; carries no behavior of its own beyond what the inline loop
   * body already did. Applies the exact same "is this actually a personal role" identity check
   * {@link #isReusablePersonalRole(User, Role)} does for the write path (active, non-template,
   * non-client-admin, same client, not itself the {@code InheritFrom} target of some OTHER
   * role's inheritance, and exclusively assigned via {@code AD_User_Roles} to {@code user}) —
   * see that method's own javadoc for what each check defends against; the two definitions must
   * be kept in lockstep. On a match, records {@code candidate}'s id into both {@code
   * confirmedPersonalRoleIds} and {@code personalRoleIdByUserId} (mutated in place); a no-match
   * is a silent no-op, exactly like the {@code continue} it replaces.
   */
  private void confirmPersonalRoleForUser(User user, Map<String, Role> candidateRolesById,
      Set<String> inheritFromTargetRoleIds, Map<String, List<String>> assignedUserIdsByRoleId,
      Set<String> confirmedPersonalRoleIds, Map<String, String> personalRoleIdByUserId) {
    Role candidate = user.getDefaultRole();
    if (candidate == null) {
      return;
    }
    String roleId = candidate.getId();
    if (!candidateRolesById.containsKey(roleId) || inheritFromTargetRoleIds.contains(roleId)) {
      return;
    }
    List<String> assignedUserIds =
        assignedUserIdsByRoleId.getOrDefault(roleId, Collections.emptyList());
    boolean exclusive = assignedUserIds.isEmpty()
        || (assignedUserIds.size() == 1 && assignedUserIds.get(0).equals(user.getId()));
    if (!exclusive) {
      return;
    }
    confirmedPersonalRoleIds.add(roleId);
    personalRoleIdByUserId.put(user.getId(), roleId);
  }

  /**
   * Rejects targeting {@code user} across a tenant boundary — closes a real cross-tenant
   * privilege-escalation gap found in REVIEW cycle 1 (ETP-4852): {@code SFAssignUserRoles}'s
   * only access gate, {@code NeoAccessHelper#isAdminOrClientAdmin}, treats a per-tenant
   * client-admin the same as the literal System Administrator, so without this check a
   * client-admin for Tenant A could call this service against any {@code userId} in Tenant B
   * and reassign or completely strip (empty {@code templateRoleIds}) that user's access.
   *
   * <p>Bypassed ONLY for the literal System Administrator role ({@link
   * #SYSTEM_ADMINISTRATOR_ROLE_ID}) — never for a mere {@code isClientAdmin()} role, however
   * privileged within its own tenant. Also a no-op when {@code callerRole} is {@code null}: per
   * this class's own javadoc, it never itself decides WHO may call it AT ALL — with no caller
   * identity supplied (the 2-arg {@link #assignTemplateRoles(String, List)} overload, used by
   * plain unit tests and any other caller with no per-request identity to check), there is
   * nothing to enforce a boundary against; that remains the caller's own responsibility.</p>
   *
   * @param user the already-resolved target user
   * @param callerRole the role making this request, or {@code null} to skip this check
   * @throws OBException if {@code callerRole} is a non-system role whose client differs from
   *     {@code user}'s (or either has no client at all)
   */
  private void enforceCallerClientBoundary(User user, Role callerRole) {
    if (callerRole == null || SYSTEM_ADMINISTRATOR_ROLE_ID.equals(callerRole.getId())) {
      return;
    }
    String callerClientId = callerRole.getClient() != null ? callerRole.getClient().getId() : null;
    String userClientId = user.getClient() != null ? user.getClient().getId() : null;
    if (callerClientId == null || !callerClientId.equals(userClientId)) {
      throw new OBException(
          "User belongs to a different client, cannot be targeted: " + user.getId());
    }
  }

  /**
   * Resolves every requested id to an active template role, deduplicating while preserving
   * first-seen order, and rejecting anything that is not a genuine composable template.
   *
   * <p><b>By design, this accepts ANY active {@code IsTemplate = 'Y'} role</b> — not only the 4
   * system-level ones {@code SystemRoleTemplates}/{@code EnsureSystemRoleTemplatesScript} seed
   * (Finance/Sales/Purchasing/Inventory). Confirmed intentional, not an oversight (W3, REVIEW
   * cycle 1, ETP-4852): a future, tenant-authored template role is meant to be composable the
   * same way, and {@link #enforceCallerClientBoundary(User, Role)} plus core's own
   * {@code RoleInheritanceEventHandler} same-{@code UserLevel} check already bound what this
   * can actually reach.</p>
   */
  private List<Role> resolveAndValidateTemplates(List<String> templateRoleIds) {
    Set<String> seen = new LinkedHashSet<>();
    List<Role> templates = new ArrayList<>();
    for (String rawId : templateRoleIds) {
      String id = StringUtils.trimToNull(rawId);
      if (id == null || !seen.add(id)) {
        continue;
      }
      Role role = OBDal.getInstance().get(Role.class, id);
      if (role == null || !Boolean.TRUE.equals(role.isActive())) {
        throw new OBException("Template role not found or inactive: " + id);
      }
      if (!Boolean.TRUE.equals(role.isTemplate())) {
        throw new OBException("Role is not a template, cannot be composed: " + id);
      }
      if (Boolean.TRUE.equals(role.isClientAdmin())) {
        throw new OBException("The Admin role can never be composed: " + id);
      }
      templates.add(role);
    }
    return templates;
  }

  /**
   * Finds the user's existing personal role, or creates one.
   *
   * <p>"Personal" (i.e. safe to reuse/reconfigure for this exact user) means: {@code
   * user.getDefaultRole()} is set, lives at the SAME client as the user, is not a template, is
   * not the client-admin "Admin" role, AND is assigned — via {@code AD_User_Roles} — to this
   * ONE user and no other (checked directly, not inferred from the name prefix alone, since an
   * admin could rename a role in the classic UI). Any mismatch is treated as "this user has no
   * personal role yet" and a brand-new one is minted, rather than risk repurposing a role
   * something else still depends on — including a role something else INHERITS FROM (see
   * {@link #isInheritFromTargetOfAnyInheritance(Role)}; W1, REVIEW cycle 1, ETP-4852): a role
   * can be "exclusively assigned to user U" via {@code AD_User_Roles} AND STILL be a parent
   * some OTHER role's {@code AD_Role_Inheritance} points at — repurposing it as U's personal
   * role would then let {@link #reconcileInheritances} mutate an inheritance set that isn't
   * only U's, silently changing access for whatever inherits from it.</p>
   */
  private Role resolveOrCreatePersonalRole(User user) {
    Role existing = findExistingPersonalRole(user);
    return existing != null ? existing : createPersonalRole(user);
  }

  /**
   * Read-only half of {@link #resolveOrCreatePersonalRole(User)} — used by {@link
   * #getAppliedTemplateRoleIds(String, Role)} (ETP-4906), which must NEVER mint a personal role
   * as a side effect of a read. Returns {@code null} instead of falling through to {@link
   * #createPersonalRole(User)} when {@code user} has no reusable candidate yet.
   */
  private Role findExistingPersonalRole(User user) {
    Role candidate = user.getDefaultRole();
    return (candidate != null && isReusablePersonalRole(user, candidate)) ? candidate : null;
  }

  /**
   * Evicts {@code personalRole} from the DAL session and re-fetches it by id, so it starts this
   * call with every mapped collection back to a genuinely UNINITIALIZED state — matching what a
   * fresh per-HTTP-request session gets for free.
   *
   * <p><b>Why this is needed:</b> core's {@code WindowAccessInjector#setParent} (invoked while
   * propagating a template's {@code AD_Window_Access} during {@link #reconcileInheritances}'s
   * ADD step) explicitly does {@code role.getADWindowAccessList().add(newAccess)} — it maintains
   * BOTH sides of the association by hand. Its sibling {@code TabAccessInjector} does the same
   * for {@code WindowAccess.getADTabAccessList()} AND correctly overrides {@code
   * AccessTypeInjector#removeReferenceInParentList} to strip the reference back out on removal.
   * {@code WindowAccessInjector} does NOT override it (confirmed by reading every {@code
   * AccessTypeInjector} subclass in core — only {@code FieldAccessInjector} and {@code
   * TabAccessInjector} do). So once {@code personalRole.getADWindowAccessList()} has been
   * initialized by an ADD (this call or an earlier one against the SAME {@code Role} instance),
   * it keeps holding a reference to a propagated {@code WindowAccess} row even after a LATER
   * {@code AD_Role_Inheritance} removal deletes that row — Hibernate's flush then throws {@code
   * EntityNotFoundException("deleted object would be re-saved by cascade")}, because a
   * cascade-eligible collection still references an object pending delete.</p>
   *
   * <p>Production NEO webhook calls never hit this: {@code HttpBaseServlet} gives every HTTP
   * request its own fresh DAL session, so {@code personalRole}'s collections start uninitialized
   * regardless of what an earlier, separate request did. This defends the service itself against
   * being invoked twice for the same {@code Role} within one long-lived session — e.g. a future
   * bulk-retrofit/batch caller (ETP-4877) reusing a single session across many users — where the
   * staleness would otherwise be real.</p>
   *
   * <p><b>Why not just {@code OBDal.refresh(personalRole)}:</b> {@code OBDal#refresh} explicitly
   * reloads already-initialized collections from CURRENT database state (see its javadoc) — but
   * at the point this runs, the stale {@code WindowAccess} row hasn't been deleted yet, so a
   * refresh would simply reload the exact same soon-to-be-conflicting reference. Only a full
   * evict, forcing a brand-new proxy on the next {@code get}, actually resets the collection to
   * an uninitialized state that core's flush-time cascade processing skips until something
   * touches it again.</p>
   */
  private Role discardStaleSessionState(Role personalRole) {
    String personalRoleId = personalRole.getId();
    OBDal.getInstance().getSession().evict(personalRole);
    return OBDal.getInstance().get(Role.class, personalRoleId);
  }

  private boolean isReusablePersonalRole(User user, Role candidate) {
    if (!Boolean.TRUE.equals(candidate.isActive())) {
      return false;
    }
    if (Boolean.TRUE.equals(candidate.isTemplate())) {
      return false;
    }
    if (Boolean.TRUE.equals(candidate.isClientAdmin())) {
      return false;
    }
    if (user.getClient() == null || candidate.getClient() == null
        || !user.getClient().getId().equals(candidate.getClient().getId())) {
      return false;
    }
    if (isInheritFromTargetOfAnyInheritance(candidate)) {
      return false;
    }
    return isExclusivelyAssignedTo(candidate, user);
  }

  /**
   * True when {@code role} has exactly one active {@code AD_User_Roles} row, and it is {@code
   * user}'s. A role with zero rows (never actually assigned) or with some OTHER user's row is
   * not exclusively this user's yet/anymore, and must not be silently repurposed.
   */
  @SuppressWarnings("unchecked")
  private boolean isExclusivelyAssignedTo(Role role, User user) {
    OBCriteria<UserRoles> criteria = OBDal.getInstance().createCriteria(UserRoles.class);
    criteria.add(Restrictions.eq(UserRoles.PROPERTY_ROLE, role));
    List<UserRoles> rows = criteria.list();
    if (rows.isEmpty()) {
      // Never assigned via AD_User_Roles yet (e.g. only Default_Ad_Role_ID was ever set) — still
      // safe to treat as "this user's own", nothing else can be depending on it.
      return true;
    }
    return rows.size() == 1 && user.getId().equals(rows.get(0).getUserContact().getId());
  }

  /**
   * True when some OTHER role's {@code AD_Role_Inheritance} row has {@code candidate} as its
   * {@code InheritFrom} target — i.e. {@code candidate} is itself a parent/template that
   * something else's access derives from, so it must never be repurposed as anyone's personal
   * role (W1, REVIEW cycle 1, ETP-4852). {@link #isExclusivelyAssignedTo} alone only checks
   * {@code AD_User_Roles} (who the role is DIRECTLY assigned to) — this is the complementary
   * check on the other side of the {@code AD_Role_Inheritance} relationship.
   */
  @SuppressWarnings("unchecked")
  private boolean isInheritFromTargetOfAnyInheritance(Role candidate) {
    OBCriteria<RoleInheritance> criteria = OBDal.getInstance()
        .createCriteria(RoleInheritance.class);
    criteria.add(Restrictions.eq(RoleInheritance.PROPERTY_INHERITFROM, candidate));
    criteria.setMaxResults(1);
    return !criteria.list().isEmpty();
  }

  private Role createPersonalRole(User user) {
    Organization starOrg = OBDal.getInstance().get(Organization.class, "0");
    Role role = OBProvider.getInstance().get(Role.class);
    role.setNewOBObject(true);
    role.setClient(user.getClient());
    role.setOrganization(starOrg);
    role.setActive(true);
    role.setName(buildPersonalRoleName(user));
    role.setDescription("Personal composition role (ETP-4852) — access derives from its "
        + "template inheritances; do not edit directly.");
    role.setUserLevel(FIXED_ROLE_USER_LEVEL);
    role.setManual(true);
    role.setTemplate(false);
    role.setClientAdmin(false);
    OBDal.getInstance().save(role);
    OBDal.getInstance().flush();
    log.info("Created personal composition role {} for user {}", role.getId(), user.getId());
    return role;
  }

  /**
   * {@code AD_Role.Name} is unique per {@code (AD_Client_ID, Name)} — the user's display name
   * (falling back to username, then id) is unique enough in practice, but a numeric suffix is
   * appended on an actual collision rather than failing the whole composition over a duplicate
   * display name.
   */
  private String buildPersonalRoleName(User user) {
    String base = StringUtils.trimToNull(user.getName());
    if (base == null) {
      base = StringUtils.trimToNull(user.getUsername());
    }
    if (base == null) {
      base = user.getId();
    }
    String candidate = PERSONAL_ROLE_NAME_PREFIX + base;
    String name = truncate(candidate, 60);
    int suffix = 2;
    while (roleNameExists(user, name)) {
      String suffixed = candidate + " (" + suffix + ")";
      name = truncate(suffixed, 60);
      suffix++;
    }
    return name;
  }

  private boolean roleNameExists(User user, String name) {
    OBCriteria<Role> criteria = OBDal.getInstance().createCriteria(Role.class);
    criteria.add(Restrictions.eq(Role.PROPERTY_CLIENT + ".id", user.getClient().getId()));
    criteria.add(Restrictions.eq(Role.PROPERTY_NAME, name));
    criteria.setMaxResults(1);
    return criteria.uniqueResult() != null;
  }

  private static String truncate(String value, int maxLength) {
    return value.length() <= maxLength ? value : value.substring(0, maxLength);
  }

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
  private int[] reconcileInheritances(Role personalRole, List<Role> templates) {
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
        OBDal.getInstance().flush();
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
      OBDal.getInstance().flush();
      added++;
    }

    if (added > 0) {
      reconcileWindowAccessAfterComposition(personalRole, templates);
    }
    return new int[] { added, removed };
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
   * trusting the entity's own collection property (see that method's javadoc, and {@link
   * #discardStaleSessionState(Role)}).
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
  private List<RoleInheritance> findExistingInheritances(Role personalRole) {
    OBCriteria<RoleInheritance> criteria = OBDal.getInstance()
        .createCriteria(RoleInheritance.class);
    criteria.add(Restrictions.eq(RoleInheritance.PROPERTY_ROLE, personalRole));
    criteria.addOrderBy(RoleInheritance.PROPERTY_SEQUENCENUMBER, true);
    return criteria.list();
  }

  /**
   * ETP-4906 — filters {@code inheritances} down to the {@code InheritFrom} ids that are
   * themselves still active templates, in {@code Seqno} order. Shared by {@link
   * #getAppliedTemplateRoleIds(String, Role)} and (in its bulk form, {@link
   * #findActiveTemplateIdsByPersonalRoleId(Set)}) {@link #getAppliedTemplateRoleIdsForClient
   * (String)} — a personal role can retain a stale {@code AD_Role_Inheritance} row pointing at a
   * template that was later deactivated or un-templated (the trigger documented in this class's
   * own javadoc only blocks deactivation WHILE an inheritance depends on it, not un-linking the
   * inheritance itself first), so this is not a redundant check.
   */
  private List<String> activeTemplateIds(List<RoleInheritance> inheritances) {
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

  /**
   * Queries every {@code AD_User} of {@code clientId} — used only by {@link
   * #getAppliedTemplateRoleIdsForClient(String)} (ETP-4906) to seed a "every user gets an entry"
   * result map before any personal-role resolution.
   */
  @SuppressWarnings("unchecked")
  private List<User> findUsersForClient(String clientId) {
    OBCriteria<User> criteria = OBDal.getInstance().createCriteria(User.class);
    criteria.setFilterOnReadableClients(false);
    criteria.setFilterOnReadableOrganization(false);
    criteria.add(Restrictions.eq(User.PROPERTY_CLIENT + ".id", clientId));
    return criteria.list();
  }

  /**
   * Bulk-fetches, in ONE query, the distinct {@code Default_Ad_Role_ID} roles referenced by
   * {@code users} and keeps only the ones passing the same basic identity checks {@link
   * #isReusablePersonalRole(User, Role)} applies first (active, non-template, non-client-admin)
   * — deliberately NOT the two write-safety-only checks ({@code isInheritFromTargetOfAnyInheritance}
   * / {@code isExclusivelyAssignedTo}), which {@link #getAppliedTemplateRoleIdsForClient(String)}
   * applies separately, in bulk, over the ids this method returns.
   *
   * <p>Reading {@code user.getDefaultRole().getId()} does not itself trigger a query — a Hibernate
   * proxy's identifier is already known from the owning row's FK column — so collecting the
   * distinct ids to bulk-fetch is free; only this one {@code Restrictions.in} query actually hits
   * the database.</p>
   */
  @SuppressWarnings("unchecked")
  private Map<String, Role> fetchCandidateDefaultRoles(List<User> users) {
    Set<String> roleIds = new LinkedHashSet<>();
    for (User user : users) {
      Role defaultRole = user.getDefaultRole();
      if (defaultRole != null) {
        roleIds.add(defaultRole.getId());
      }
    }
    if (roleIds.isEmpty()) {
      return Collections.emptyMap();
    }
    OBCriteria<Role> criteria = OBDal.getInstance().createCriteria(Role.class);
    criteria.setFilterOnReadableClients(false);
    criteria.setFilterOnReadableOrganization(false);
    criteria.add(Restrictions.in(Role.PROPERTY_ID, roleIds));
    Map<String, Role> byId = new LinkedHashMap<>();
    for (Role role : (List<Role>) criteria.list()) {
      if (Boolean.TRUE.equals(role.isActive()) && !Boolean.TRUE.equals(role.isTemplate())
          && !Boolean.TRUE.equals(role.isClientAdmin())) {
        byId.put(role.getId(), role);
      }
    }
    return byId;
  }

  /**
   * Bulk form of {@link #isInheritFromTargetOfAnyInheritance(Role)}: the subset of {@code
   * roleIds} that some OTHER role's {@code AD_Role_Inheritance} row points at as its {@code
   * InheritFrom} — i.e. roles that are themselves depended upon as a template/parent, and must
   * never be treated as anyone's personal role.
   */
  @SuppressWarnings("unchecked")
  private Set<String> findRoleIdsUsedAsInheritFromTarget(Set<String> roleIds) {
    OBCriteria<RoleInheritance> criteria = OBDal.getInstance()
        .createCriteria(RoleInheritance.class);
    criteria.setFilterOnReadableClients(false);
    criteria.setFilterOnReadableOrganization(false);
    criteria.add(Restrictions.in(RoleInheritance.PROPERTY_INHERITFROM + ".id", roleIds));
    Set<String> targets = new LinkedHashSet<>();
    for (RoleInheritance inheritance : (List<RoleInheritance>) criteria.list()) {
      targets.add(inheritance.getInheritFrom().getId());
    }
    return targets;
  }

  /**
   * Bulk form of {@link #isExclusivelyAssignedTo(Role, User)}: for every role in {@code
   * roleIds}, the {@code AD_User_ID}s of its active {@code AD_User_Roles} rows (NOT deduplicated
   * — mirrors {@link #isExclusivelyAssignedTo(Role, User)}'s own {@code rows.size() == 1} check,
   * which would also fail on two rows for the same user). A role id absent from the returned map
   * has zero active rows — {@link #getAppliedTemplateRoleIdsForClient(String)} treats that the
   * same way {@link #isExclusivelyAssignedTo(Role, User)} does: "never assigned yet, still safe".
   */
  @SuppressWarnings("unchecked")
  private Map<String, List<String>> findActiveAssignedUserIdsByRoleId(Set<String> roleIds) {
    OBCriteria<UserRoles> criteria = OBDal.getInstance().createCriteria(UserRoles.class);
    criteria.setFilterOnReadableClients(false);
    criteria.setFilterOnReadableOrganization(false);
    criteria.add(Restrictions.in(UserRoles.PROPERTY_ROLE + ".id", roleIds));
    criteria.add(Restrictions.eq(UserRoles.PROPERTY_ACTIVE, true));
    Map<String, List<String>> byRoleId = new LinkedHashMap<>();
    for (UserRoles row : (List<UserRoles>) criteria.list()) {
      if (row.getUserContact() == null) {
        continue;
      }
      byRoleId.computeIfAbsent(row.getRole().getId(), k -> new ArrayList<>())
          .add(row.getUserContact().getId());
    }
    return byRoleId;
  }

  /**
   * Bulk form of {@link #activeTemplateIds(List)}: for every confirmed personal role in {@code
   * personalRoleIds}, its active-template {@code InheritFrom} ids, in {@code Seqno} order.
   */
  @SuppressWarnings("unchecked")
  private Map<String, List<String>> findActiveTemplateIdsByPersonalRoleId(
      Set<String> personalRoleIds) {
    OBCriteria<RoleInheritance> criteria = OBDal.getInstance()
        .createCriteria(RoleInheritance.class);
    criteria.setFilterOnReadableClients(false);
    criteria.setFilterOnReadableOrganization(false);
    criteria.add(Restrictions.in(RoleInheritance.PROPERTY_ROLE + ".id", personalRoleIds));
    criteria.addOrderBy(RoleInheritance.PROPERTY_SEQUENCENUMBER, true);
    Map<String, List<String>> byPersonalRoleId = new LinkedHashMap<>();
    for (RoleInheritance inheritance : (List<RoleInheritance>) criteria.list()) {
      Role template = inheritance.getInheritFrom();
      if (template == null || !Boolean.TRUE.equals(template.isActive())
          || !Boolean.TRUE.equals(template.isTemplate())) {
        continue;
      }
      byPersonalRoleId.computeIfAbsent(inheritance.getRole().getId(), k -> new ArrayList<>())
          .add(template.getId());
    }
    return byPersonalRoleId;
  }
}
