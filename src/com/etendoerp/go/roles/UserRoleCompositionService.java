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
 * it). {@link #assignTemplateRoles(String, List)} enters {@link OBContext#setAdminMode(boolean)}
 * for its own duration purely to bypass row-level security while writing across clients (the
 * personal role's client vs. the template's, which may be {@code '0'}), never to decide access —
 * mirroring every other write in this module (e.g. {@code UserRoleAssignmentHandler}). Openbravo's
 * admin-mode flag is stack-based, so an already-admin-mode caller nesting into this method is
 * safe (push/pop), not a double-entry bug.</p>
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
   * Result of {@link #assignTemplateRoles(String, List)} — everything the webhook needs to
   * build its JSON response.
   */
  public static final class AssignmentResult {
    public final String userId;
    public final String personalRoleId;
    public final List<String> appliedTemplateRoleIds;
    public final int addedCount;
    public final int removedCount;

    AssignmentResult(String userId, String personalRoleId, List<String> appliedTemplateRoleIds,
        int addedCount, int removedCount) {
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
   */
  public AssignmentResult assignTemplateRoles(String userId, List<String> templateRoleIds) {
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

    List<Role> templates = resolveAndValidateTemplates(templateRoleIds);

    OBContext.setAdminMode(true);
    try {
      Role personalRole = resolveOrCreatePersonalRole(user);
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
   * Resolves every requested id to an active template role, deduplicating while preserving
   * first-seen order, and rejecting anything that is not a genuine composable template.
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
   * something else still depends on.</p>
   */
  private Role resolveOrCreatePersonalRole(User user) {
    Role candidate = user.getDefaultRole();
    if (candidate != null && isReusablePersonalRole(user, candidate)) {
      return candidate;
    }
    return createPersonalRole(user);
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
   * @return {@code {addedCount, removedCount}}
   */
  private int[] reconcileInheritances(Role personalRole, List<Role> templates) {
    Set<String> desiredIds = new LinkedHashSet<>();
    for (Role template : templates) {
      desiredIds.add(template.getId());
    }

    List<RoleInheritance> existing = new ArrayList<>(personalRole.getADRoleInheritanceList());
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
}
