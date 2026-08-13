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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hibernate.criterion.Restrictions;
import org.openbravo.base.exception.OBException;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.access.Role;
import org.openbravo.model.ad.access.RoleInheritance;
import org.openbravo.model.ad.access.User;
import org.openbravo.model.ad.access.UserRoles;
import org.openbravo.model.common.enterprise.Organization;

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
   */
  public AssignmentResult assignTemplateRoles(String userId, List<String> templateRoleIds,
      Role callerRole) {
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
    Role candidate = user.getDefaultRole();
    if (candidate != null && isReusablePersonalRole(user, candidate)) {
      return candidate;
    }
    return createPersonalRole(user);
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
    return new int[] { added, removed };
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
}
