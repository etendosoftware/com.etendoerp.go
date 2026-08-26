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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.openbravo.base.exception.OBException;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.access.Role;
import org.openbravo.model.ad.access.RoleInheritance;
import org.openbravo.model.ad.access.RoleOrganization;
import org.openbravo.model.ad.access.User;
import org.openbravo.model.ad.access.UserRoles;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.common.enterprise.Warehouse;

import com.etendoerp.go.schemaforge.util.OwnerSupport;

/**
 * Unit tests for {@link UserRoleCompositionService}'s input-validation guard clauses — the slice
 * that fails before any persistence side effect, so it is safely mockable without a real DB —
 * PLUS (ETP-4830) {@link UserRoleCompositionService#ensurePersonalRole}'s get-or-create/identity
 * logic, which is plain deterministic Java with no event-driven propagation and is therefore also
 * safely mockable (unlike {@code assignTemplateRoles}'s {@code AD_Role_Inheritance}
 * add/remove/propagation reconciliation, which genuinely needs core's real {@code
 * RoleInheritanceEventHandler} DAL events — that part is deliberately NOT re-mocked here and is
 * instead covered end-to-end against a real DB by {@link UserRoleCompositionServiceIntegrationTest}).
 */
@MockitoSettings(strictness = Strictness.LENIENT)
class UserRoleCompositionServiceTest {

  private MockedStatic<OBDal> obDalMock;
  private OBDal mockDal;
  private UserRoleCompositionService service;

  @BeforeEach
  void setUp() {
    obDalMock = mockStatic(OBDal.class);
    mockDal = mock(OBDal.class);
    obDalMock.when(OBDal::getInstance).thenReturn(mockDal);
    service = new UserRoleCompositionService();
  }

  @AfterEach
  void tearDown() {
    obDalMock.close();
  }

  @Test
  void rejectsBlankUserId() {
    OBException e = assertThrows(OBException.class,
        () -> service.assignTemplateRoles(" ", Collections.emptyList()));
    assertTrue(e.getMessage().contains("Missing user id"));
  }

  @Test
  void rejectsNullTemplateRoleIdList() {
    User user = mock(User.class);
    when(mockDal.get(User.class, "user-1")).thenReturn(user);

    OBException e = assertThrows(OBException.class,
        () -> service.assignTemplateRoles("user-1", null));
    assertTrue(e.getMessage().contains("Missing template role id list"));
  }

  @Test
  void rejectsUnknownUser() {
    when(mockDal.get(User.class, "missing-user")).thenReturn(null);

    OBException e = assertThrows(OBException.class,
        () -> service.assignTemplateRoles("missing-user", Collections.emptyList()));
    assertTrue(e.getMessage().contains("User not found"));
  }

  @Test
  void rejectsUnknownTemplateRoleId() {
    User user = mock(User.class);
    when(mockDal.get(User.class, "user-1")).thenReturn(user);
    when(mockDal.get(Role.class, "missing-role")).thenReturn(null);

    OBException e = assertThrows(OBException.class,
        () -> service.assignTemplateRoles("user-1", List.of("missing-role")));
    assertTrue(e.getMessage().contains("Template role not found or inactive"));
  }

  @Test
  void rejectsInactiveTemplateRole() {
    User user = mock(User.class);
    when(mockDal.get(User.class, "user-1")).thenReturn(user);
    Role inactive = mock(Role.class);
    when(inactive.isActive()).thenReturn(false);
    when(mockDal.get(Role.class, "inactive-role")).thenReturn(inactive);

    OBException e = assertThrows(OBException.class,
        () -> service.assignTemplateRoles("user-1", List.of("inactive-role")));
    assertTrue(e.getMessage().contains("Template role not found or inactive"));
  }

  @Test
  void rejectsRoleThatIsNotATemplate() {
    User user = mock(User.class);
    when(mockDal.get(User.class, "user-1")).thenReturn(user);
    Role notTemplate = mock(Role.class);
    when(notTemplate.isActive()).thenReturn(true);
    when(notTemplate.isTemplate()).thenReturn(false);
    when(mockDal.get(Role.class, "plain-role")).thenReturn(notTemplate);

    OBException e = assertThrows(OBException.class,
        () -> service.assignTemplateRoles("user-1", List.of("plain-role")));
    assertTrue(e.getMessage().contains("is not a template"));
  }

  @Test
  void rejectsTheClientAdminRoleEvenIfSomehowMarkedAsTemplate() {
    User user = mock(User.class);
    when(mockDal.get(User.class, "user-1")).thenReturn(user);
    Role adminLike = mock(Role.class);
    when(adminLike.isActive()).thenReturn(true);
    when(adminLike.isTemplate()).thenReturn(true);
    when(adminLike.isClientAdmin()).thenReturn(true);
    when(mockDal.get(Role.class, "admin-role")).thenReturn(adminLike);

    OBException e = assertThrows(OBException.class,
        () -> service.assignTemplateRoles("user-1", List.of("admin-role")));
    assertTrue(e.getMessage().contains("Admin role can never be composed"));
  }

  @Test
  void deduplicatesRequestedTemplateIdsBeforeValidating() {
    User user = mock(User.class);
    when(mockDal.get(User.class, "user-1")).thenReturn(user);
    // The single distinct id resolves to an Admin-like role, which fails validation and throws
    // — cleanly stopping BEFORE OBContext.setAdminMode() is ever reached (this plain Mockito
    // unit test never mocks OBContext, so it must never call the real static one). Verifying
    // the lookup ran exactly once, despite the id appearing three times (with whitespace noise)
    // in the input, proves dedup happens before the per-id validation loop.
    Role adminLike = mock(Role.class);
    when(adminLike.isActive()).thenReturn(true);
    when(adminLike.isTemplate()).thenReturn(true);
    when(adminLike.isClientAdmin()).thenReturn(true);
    when(mockDal.get(Role.class, "tpl-1")).thenReturn(adminLike);

    assertThrows(OBException.class,
        () -> service.assignTemplateRoles("user-1", List.of("tpl-1", " tpl-1", "tpl-1 ")));

    org.mockito.Mockito.verify(mockDal, org.mockito.Mockito.times(1)).get(Role.class, "tpl-1");
  }

  /**
   * REVIEW cycle 1 blocker (B1, ETP-4852): {@code SFAssignUserRoles}'s only access gate,
   * {@code NeoAccessHelper#isAdminOrClientAdmin}, treats a per-tenant client-admin the same as
   * the literal System Administrator — so without {@link
   * UserRoleCompositionService#enforceCallerClientBoundary}, a client-admin for one client could
   * target ANY user in a different client. This proves the 3-arg {@link
   * UserRoleCompositionService#assignTemplateRoles(String, List, Role)} overload rejects that,
   * before any template validation, admin-mode entry, or write.
   */
  @Test
  void rejectsCrossClientTarget() {
    User user = mock(User.class);
    Client targetClient = mock(Client.class);
    when(targetClient.getId()).thenReturn("client-B");
    when(user.getClient()).thenReturn(targetClient);
    when(user.getId()).thenReturn("user-1");
    when(mockDal.get(User.class, "user-1")).thenReturn(user);

    Role callerClientAdmin = mock(Role.class);
    when(callerClientAdmin.getId()).thenReturn("caller-role-id");
    Client callerClient = mock(Client.class);
    when(callerClient.getId()).thenReturn("client-A");
    when(callerClientAdmin.getClient()).thenReturn(callerClient);

    OBException e = assertThrows(OBException.class, () -> service
        .assignTemplateRoles("user-1", Collections.emptyList(), callerClientAdmin));
    assertTrue(e.getMessage().contains("different client"));
  }

  /**
   * The bypass is the LITERAL System Administrator role id ({@code "0"}), never a mere
   * {@code isClientAdmin()} role (see {@link #rejectsCrossClientTarget}). Deliberately never
   * stubs {@code systemAdmin.getClient()} — reaching the TEMPLATE validation error (not the
   * boundary one, and not an NPE) proves {@code enforceCallerClientBoundary} short-circuits on
   * the id check before ever comparing clients.
   */
  @Test
  void systemAdministratorCallerBypassesClientBoundaryCheck() {
    User user = mock(User.class);
    Client targetClient = mock(Client.class);
    when(targetClient.getId()).thenReturn("client-B");
    when(user.getClient()).thenReturn(targetClient);
    when(mockDal.get(User.class, "user-1")).thenReturn(user);
    when(mockDal.get(Role.class, "missing-role")).thenReturn(null);

    Role systemAdmin = mock(Role.class);
    when(systemAdmin.getId()).thenReturn("0");

    OBException e = assertThrows(OBException.class, () -> service
        .assignTemplateRoles("user-1", List.of("missing-role"), systemAdmin));
    assertTrue(e.getMessage().contains("Template role not found or inactive"));
  }

  // ── ETP-4830: owner-protection guard on the write path ──────────────────

  /**
   * The role-assignment-endpoint counterpart to {@code
   * UserRoleAssignmentHandlerTest#handleBlanketRejectsNonOwnerPatchOnOwnerRecord_*} — the SAME
   * owner-protection rule enforced on a genuinely separate write path (see {@link
   * UserRoleCompositionService#enforceOwnerProtection}'s class javadoc for why closing this gap
   * on the generic {@code AD_User} PUT/PATCH alone is not enough). Rejects BEFORE {@code
   * resolveAndValidateTemplates} even runs — an empty template list (which would otherwise be a
   * trivially valid "strip all access" request) is still blocked.
   */
  @Test
  void rejectsOwnerRoleReassignmentByNonOwner() {
    User user = mock(User.class);
    when(user.getId()).thenReturn("owner-user-2");
    when(mockDal.get(User.class, "owner-user-2")).thenReturn(user);

    try (MockedStatic<OwnerSupport> ownerMock = mockStatic(OwnerSupport.class)) {
      ownerMock.when(() -> OwnerSupport.isOwner("owner-user-2")).thenReturn(true);

      OBException e = assertThrows(OBException.class, () -> service
          .assignTemplateRoles("owner-user-2", Collections.emptyList(), null, "some-other-admin"));
      assertTrue(e.getMessage().toLowerCase().contains("owner"));
    }
  }

  /**
   * ETP-5019 regression guard — inverts the pre-fix behavior this test used to assert. The owner
   * recomposing their OWN roles is NO LONGER a no-op: {@link
   * UserRoleCompositionService#enforceOwnerProtection} used to special-case {@code
   * callerUserId.equals(user.getId())}, but that self-service exception is exactly what let the
   * owner silently overwrite their own "Admin" role with a fresh empty personal role (see the
   * method's own javadoc). Self-targeting must now be rejected exactly like any other caller,
   * BEFORE {@code resolveAndValidateTemplates} ever runs (reachable-but-unrelated {@code
   * "missing-role"} template id proves the owner-protection guard is what fired) — and no
   * persistence side effect (personal-role creation, {@code Default_Ad_Role_ID} rewrite) must
   * happen as a result.
   */
  @Test
  void ownerReassigningTheirOwnRolesIsRejectedByOwnerProtectionCheck() {
    User user = mock(User.class);
    when(user.getId()).thenReturn("owner-user-1");
    when(mockDal.get(User.class, "owner-user-1")).thenReturn(user);
    when(mockDal.get(Role.class, "missing-role")).thenReturn(null);

    try (MockedStatic<OwnerSupport> ownerMock = mockStatic(OwnerSupport.class)) {
      ownerMock.when(() -> OwnerSupport.isOwner("owner-user-1")).thenReturn(true);

      OBException e = assertThrows(OBException.class, () -> service
          .assignTemplateRoles("owner-user-1", List.of("missing-role"), null, "owner-user-1"));
      assertTrue(e.getMessage().toLowerCase().contains("owner"));
      assertTrue(e.getMessage().toLowerCase().contains("admin"));
    }

    verify(mockDal, never()).save(any());
  }

  /**
   * ETP-5019: a user NOT flagged {@code EM_ETGO_Is_Owner} but whose {@code
   * user.getDefaultRole().isClientAdmin()} reads {@code true} (e.g. a second user manually
   * granted the classic "Admin" role via the core UI) must also be rejected — the {@code
   * isOwner} flag and the client-admin default-role check are OR'd in {@link
   * UserRoleCompositionService#enforceOwnerProtection}. Covers self-targeting.
   */
  @Test
  void clientAdminRoleHolderTargetingSelfIsRejectedEvenWhenNotFlaggedAsOwner() {
    User user = mock(User.class);
    when(user.getId()).thenReturn("admin-holder-1");
    when(mockDal.get(User.class, "admin-holder-1")).thenReturn(user);
    when(mockDal.get(Role.class, "missing-role")).thenReturn(null);

    Role currentAdminRole = mock(Role.class);
    when(currentAdminRole.isClientAdmin()).thenReturn(true);
    when(user.getDefaultRole()).thenReturn(currentAdminRole);

    try (MockedStatic<OwnerSupport> ownerMock = mockStatic(OwnerSupport.class)) {
      ownerMock.when(() -> OwnerSupport.isOwner("admin-holder-1")).thenReturn(false);

      OBException e = assertThrows(OBException.class, () -> service
          .assignTemplateRoles("admin-holder-1", List.of("missing-role"), null, "admin-holder-1"));
      assertTrue(e.getMessage().toLowerCase().contains("owner"));
    }

    verify(mockDal, never()).save(any());
  }

  /**
   * Same as {@link #clientAdminRoleHolderTargetingSelfIsRejectedEvenWhenNotFlaggedAsOwner} but
   * with a DIFFERENT caller targeting the client-admin-role holder — proves the OR'd check
   * rejects regardless of who is asking, matching {@link #rejectsOwnerRoleReassignmentByNonOwner}
   * for the {@code isOwner=true} signal.
   */
  @Test
  void clientAdminRoleHolderTargetedByAnotherCallerIsRejectedEvenWhenNotFlaggedAsOwner() {
    User user = mock(User.class);
    when(user.getId()).thenReturn("admin-holder-2");
    when(mockDal.get(User.class, "admin-holder-2")).thenReturn(user);
    when(mockDal.get(Role.class, "missing-role")).thenReturn(null);

    Role currentAdminRole = mock(Role.class);
    when(currentAdminRole.isClientAdmin()).thenReturn(true);
    when(user.getDefaultRole()).thenReturn(currentAdminRole);

    try (MockedStatic<OwnerSupport> ownerMock = mockStatic(OwnerSupport.class)) {
      ownerMock.when(() -> OwnerSupport.isOwner("admin-holder-2")).thenReturn(false);

      OBException e = assertThrows(OBException.class, () -> service
          .assignTemplateRoles("admin-holder-2", List.of("missing-role"), null, "some-other-admin"));
      assertTrue(e.getMessage().toLowerCase().contains("owner"));
    }

    verify(mockDal, never()).save(any());
  }

  /**
   * Edge case: {@code user.getDefaultRole()} returns {@code null} (e.g. a brand-new {@code
   * AD_User} with no role assigned yet) — {@link UserRoleCompositionService#enforceOwnerProtection}
   * must not NPE on {@code currentRole.isClientAdmin()} and must fall through to evaluating the
   * {@code isOwner} flag alone. Here {@code isOwner=true}, so the guard still rejects.
   */
  @Test
  void nullDefaultRoleDoesNotNpeAndFallsThroughToOwnerFlagCheck() {
    User user = mock(User.class);
    when(user.getId()).thenReturn("owner-user-4");
    when(mockDal.get(User.class, "owner-user-4")).thenReturn(user);
    when(mockDal.get(Role.class, "missing-role")).thenReturn(null);
    when(user.getDefaultRole()).thenReturn(null);

    try (MockedStatic<OwnerSupport> ownerMock = mockStatic(OwnerSupport.class)) {
      ownerMock.when(() -> OwnerSupport.isOwner("owner-user-4")).thenReturn(true);

      OBException e = assertThrows(OBException.class, () -> service
          .assignTemplateRoles("owner-user-4", List.of("missing-role"), null, "some-other-admin"));
      assertTrue(e.getMessage().toLowerCase().contains("owner"));
    }

    verify(mockDal, never()).save(any());
  }

  /**
   * Baseline (every pre-existing user until the ETP-4830 backfill data-fix runs): {@code
   * is_owner=false/unset} AND a non-client-admin (or absent) default role means the guard never
   * triggers at all, regardless of who the caller is — reaching the template-validation error
   * (not an owner-protection rejection) proves it. {@code user.getDefaultRole()} is explicitly
   * stubbed to {@code null} here (rather than relying on Mockito's default null-return) to make
   * explicit that the OR'd client-admin signal is also false for this baseline case.
   */
  @Test
  void ownerProtectionIsNoOpWhenTargetIsNotFlaggedAsOwner() {
    User user = mock(User.class);
    when(user.getId()).thenReturn("regular-user-1");
    when(mockDal.get(User.class, "regular-user-1")).thenReturn(user);
    when(mockDal.get(Role.class, "missing-role")).thenReturn(null);
    when(user.getDefaultRole()).thenReturn(null);

    try (MockedStatic<OwnerSupport> ownerMock = mockStatic(OwnerSupport.class)) {
      ownerMock.when(() -> OwnerSupport.isOwner("regular-user-1")).thenReturn(false);

      OBException e = assertThrows(OBException.class, () -> service
          .assignTemplateRoles("regular-user-1", List.of("missing-role"), null, "some-other-admin"));
      assertTrue(e.getMessage().contains("Template role not found or inactive"));
    }
  }

  /**
   * A {@code null} {@code callerUserId} (the 2-arg/3-arg overloads' convention) skips the
   * owner-protection check entirely — same "nothing to enforce" convention {@code
   * enforceCallerClientBoundary} uses for a {@code null} {@code callerRole}.
   */
  @Test
  void nullCallerUserIdSkipsOwnerProtectionCheckEvenForAnOwner() {
    User user = mock(User.class);
    when(user.getId()).thenReturn("owner-user-3");
    when(mockDal.get(User.class, "owner-user-3")).thenReturn(user);
    when(mockDal.get(Role.class, "missing-role")).thenReturn(null);

    // OwnerSupport is deliberately NOT mocked here: if enforceOwnerProtection's null-check ever
    // regressed and called OwnerSupport.isOwner anyway, the real implementation would try to use
    // OBDal's (unstubbed) getSession() and blow up with an NPE instead of the expected
    // template-validation OBException — turning a silent behavior change into a loud test failure.
    OBException e = assertThrows(OBException.class, () -> service
        .assignTemplateRoles("owner-user-3", List.of("missing-role"), null));
    assertTrue(e.getMessage().contains("Template role not found or inactive"));
  }

  // ── ETP-4906: getAppliedTemplateRoleIds (read path) ─────────────────────

  @Test
  void rejectsBlankUserIdForReadPath() {
    OBException e = assertThrows(OBException.class,
        () -> service.getAppliedTemplateRoleIds(" "));
    assertTrue(e.getMessage().contains("Missing user id"));
  }

  @Test
  void getAppliedTemplateRoleIdsRejectsUnknownUser() {
    when(mockDal.get(User.class, "missing-user")).thenReturn(null);

    OBException e = assertThrows(OBException.class,
        () -> service.getAppliedTemplateRoleIds("missing-user"));
    assertTrue(e.getMessage().contains("User not found"));
  }

  /**
   * A user who never went through {@link UserRoleCompositionService#assignTemplateRoles(String,
   * List)} has no {@code Default_Ad_Role_ID} yet — this must return an empty list, and must
   * NEVER mint a personal role as a side effect of a read (that would be a surprising write
   * hiding inside a GET-shaped lookup).
   */
  @Test
  void noDefaultRoleAtAllReturnsEmptyList() {
    User user = mock(User.class);
    when(user.getDefaultRole()).thenReturn(null);
    when(mockDal.get(User.class, "user-1")).thenReturn(user);

    try (MockedStatic<OBContext> obContextMock = mockStatic(OBContext.class)) {
      List<String> ids = service.getAppliedTemplateRoleIds("user-1");
      assertTrue(ids.isEmpty());
      // The two OBContext admin-mode calls happened (read still bypasses row-level security the
      // same way the write path does), but nothing else — no OBException, no NPE.
      obContextMock.verify(() -> OBContext.setAdminMode(true));
    }
  }

  /**
   * The core happy path: a personal role (reusable — active, non-template, non-admin, same
   * client, exclusively assigned to this user, not itself an inheritance target) with 2 active
   * {@code AD_Role_Inheritance} rows returns both {@code InheritFrom} ids, in {@code Seqno}
   * order.
   */
  @Test
  @SuppressWarnings("unchecked")
  void personalRoleWithTwoAppliedTemplatesReturnsBothIds() {
    Client userClient = mock(Client.class);
    when(userClient.getId()).thenReturn("client-A");

    User user = mock(User.class);
    when(user.getId()).thenReturn("user-1");
    when(user.getClient()).thenReturn(userClient);
    when(mockDal.get(User.class, "user-1")).thenReturn(user);

    Role personalRole = mock(Role.class);
    when(personalRole.getId()).thenReturn("personal-role-1");
    when(personalRole.isActive()).thenReturn(true);
    when(personalRole.isTemplate()).thenReturn(false);
    when(personalRole.isClientAdmin()).thenReturn(false);
    when(personalRole.getClient()).thenReturn(userClient);
    when(user.getDefaultRole()).thenReturn(personalRole);

    // isExclusivelyAssignedTo: zero AD_User_Roles rows -> "never assigned yet, still safe".
    OBCriteria<UserRoles> userRolesCriteria = mock(OBCriteria.class);
    when(mockDal.createCriteria(UserRoles.class)).thenReturn(userRolesCriteria);
    when(userRolesCriteria.list()).thenReturn(Collections.emptyList());

    Role template1 = mock(Role.class);
    when(template1.getId()).thenReturn("tpl-finance");
    when(template1.isActive()).thenReturn(true);
    when(template1.isTemplate()).thenReturn(true);
    Role template2 = mock(Role.class);
    when(template2.getId()).thenReturn("tpl-sales");
    when(template2.isActive()).thenReturn(true);
    when(template2.isTemplate()).thenReturn(true);

    RoleInheritance inheritance1 = mock(RoleInheritance.class);
    when(inheritance1.getInheritFrom()).thenReturn(template1);
    RoleInheritance inheritance2 = mock(RoleInheritance.class);
    when(inheritance2.getInheritFrom()).thenReturn(template2);

    // The SAME RoleInheritance criteria mock backs two different calls within one invocation:
    // first isInheritFromTargetOfAnyInheritance's check (empty -> not an inheritance target),
    // then findExistingInheritances' own fetch (the 2 applied templates) — consecutive stubbing.
    OBCriteria<RoleInheritance> roleInheritanceCriteria = mock(OBCriteria.class);
    when(mockDal.createCriteria(RoleInheritance.class)).thenReturn(roleInheritanceCriteria);
    when(roleInheritanceCriteria.list()).thenReturn(
        Collections.emptyList(),
        List.of(inheritance1, inheritance2));

    try (MockedStatic<OBContext> obContextMock = mockStatic(OBContext.class)) {
      List<String> ids = service.getAppliedTemplateRoleIds("user-1");
      assertEquals(List.of("tpl-finance", "tpl-sales"), ids);
    }
  }

  /**
   * REVIEW-parity check for the read path (mirrors {@link #rejectsCrossClientTarget} for the
   * write path): {@link UserRoleCompositionService#getAppliedTemplateRoleIds(String, Role)} MUST
   * enforce the exact same {@code enforceCallerClientBoundary} check the write path uses — a
   * client-admin must never be able to read another tenant's user's applied template roles.
   */
  @Test
  void getAppliedTemplateRoleIdsRejectsCrossClientTarget() {
    User user = mock(User.class);
    Client targetClient = mock(Client.class);
    when(targetClient.getId()).thenReturn("client-B");
    when(user.getClient()).thenReturn(targetClient);
    when(user.getId()).thenReturn("user-1");
    when(mockDal.get(User.class, "user-1")).thenReturn(user);

    Role callerClientAdmin = mock(Role.class);
    when(callerClientAdmin.getId()).thenReturn("caller-role-id");
    Client callerClient = mock(Client.class);
    when(callerClient.getId()).thenReturn("client-A");
    when(callerClientAdmin.getClient()).thenReturn(callerClient);

    OBException e = assertThrows(OBException.class,
        () -> service.getAppliedTemplateRoleIds("user-1", callerClientAdmin));
    assertTrue(e.getMessage().contains("different client"));
  }

  /**
   * Mirrors {@link #systemAdministratorCallerBypassesClientBoundaryCheck}: the literal System
   * Administrator role id ({@code "0"}) bypasses the boundary check on the read path too.
   */
  @Test
  void systemAdministratorCallerBypassesClientBoundaryCheckOnReadPath() {
    User user = mock(User.class);
    Client targetClient = mock(Client.class);
    when(targetClient.getId()).thenReturn("client-B");
    when(user.getClient()).thenReturn(targetClient);
    when(user.getDefaultRole()).thenReturn(null);
    when(mockDal.get(User.class, "user-1")).thenReturn(user);

    Role systemAdmin = mock(Role.class);
    when(systemAdmin.getId()).thenReturn("0");

    try (MockedStatic<OBContext> obContextMock = mockStatic(OBContext.class)) {
      List<String> ids = service.getAppliedTemplateRoleIds("user-1", systemAdmin);
      assertTrue(ids.isEmpty());
    }
  }

  // ── ETP-4830: ensurePersonalRole (get-or-create, no template composition) ──

  @Test
  void ensurePersonalRoleRejectsNullUser() {
    OBException e = assertThrows(OBException.class, () -> service.ensurePersonalRole(null));
    assertTrue(e.getMessage().contains("Missing user"));
  }

  /**
   * The "genuinely theirs" happy path (ETP-4830 human-directed requirement): a candidate {@code
   * user.getDefaultRole()} that satisfies EVERY {@link
   * UserRoleCompositionService#isReusablePersonalRole} check (active, non-template,
   * non-client-admin, same client as the user, not itself an {@code AD_Role_Inheritance}
   * {@code InheritFrom} target, and exclusively assigned to this one user via {@code
   * AD_User_Roles}) must be returned AS-IS — the exact same reference, no new role minted. Mirrors
   * {@link #personalRoleWithTwoAppliedTemplatesReturnsBothIds}'s identical fixture shape for the
   * read path, since both must apply the SAME identity definition.
   */
  @Test
  @SuppressWarnings("unchecked")
  void ensurePersonalRoleReusesExistingRoleWhenIdentityCheckIsSatisfied() {
    Client userClient = mock(Client.class);
    when(userClient.getId()).thenReturn("client-A");

    User user = mock(User.class);
    when(user.getId()).thenReturn("user-1");
    when(user.getClient()).thenReturn(userClient);

    Role existingPersonalRole = mock(Role.class);
    when(existingPersonalRole.getId()).thenReturn("personal-role-1");
    when(existingPersonalRole.isActive()).thenReturn(true);
    when(existingPersonalRole.isTemplate()).thenReturn(false);
    when(existingPersonalRole.isClientAdmin()).thenReturn(false);
    when(existingPersonalRole.getClient()).thenReturn(userClient);
    when(user.getDefaultRole()).thenReturn(existingPersonalRole);

    // Not an AD_Role_Inheritance InheritFrom target of anything else.
    OBCriteria<RoleInheritance> roleInheritanceCriteria = mock(OBCriteria.class);
    when(mockDal.createCriteria(RoleInheritance.class)).thenReturn(roleInheritanceCriteria);
    when(roleInheritanceCriteria.list()).thenReturn(Collections.emptyList());

    // Exclusively assigned to this one user via AD_User_Roles.
    UserRoles ownRow = mock(UserRoles.class);
    when(ownRow.getUserContact()).thenReturn(user);
    OBCriteria<UserRoles> userRolesCriteria = mock(OBCriteria.class);
    when(mockDal.createCriteria(UserRoles.class)).thenReturn(userRolesCriteria);
    when(userRolesCriteria.list()).thenReturn(List.of(ownRow));

    try (MockedStatic<OBContext> obContextMock = mockStatic(OBContext.class)) {
      Role result = service.ensurePersonalRole(user);

      assertSame(existingPersonalRole, result);
      verify(mockDal, never()).save(any(Role.class));
      obContextMock.verify(() -> OBContext.setAdminMode(true));
      obContextMock.verify(OBContext::restorePreviousMode);
    }
  }

  /**
   * No default role at all yet (a genuinely new {@code AD_User}) — a brand-new, empty personal
   * role must be minted via {@link UserRoleCompositionService#createPersonalRole}, matching what
   * {@code createPersonalRole}'s own javadoc documents: manual, non-template, non-client-admin,
   * scoped to the user's client and the {@code "0"} organization.
   */
  @Test
  @SuppressWarnings("unchecked")
  void ensurePersonalRoleCreatesNewRoleWhenNoDefaultRoleYet() {
    Client userClient = mock(Client.class);
    when(userClient.getId()).thenReturn("client-A");

    User user = mock(User.class);
    when(user.getId()).thenReturn("user-1");
    when(user.getClient()).thenReturn(userClient);
    when(user.getName()).thenReturn("Jane Doe");
    when(user.getDefaultRole()).thenReturn(null);

    Organization starOrg = mock(Organization.class);
    when(mockDal.get(Organization.class, "0")).thenReturn(starOrg);

    OBCriteria<Role> nameUniquenessCriteria = mock(OBCriteria.class);
    when(mockDal.createCriteria(Role.class)).thenReturn(nameUniquenessCriteria);
    when(nameUniquenessCriteria.uniqueResult()).thenReturn(null);

    Role newRole = mock(Role.class);

    try (MockedStatic<OBContext> obContextMock = mockStatic(OBContext.class);
        MockedStatic<OBProvider> obProviderMock = mockStatic(OBProvider.class)) {
      OBProvider obProvider = mock(OBProvider.class);
      obProviderMock.when(OBProvider::getInstance).thenReturn(obProvider);
      when(obProvider.get(Role.class)).thenReturn(newRole);
      when(obProvider.get(RoleOrganization.class)).thenAnswer(inv -> mock(RoleOrganization.class));

      Role result = service.ensurePersonalRole(user);

      assertSame(newRole, result);
      verify(newRole).setNewOBObject(true);
      verify(newRole).setClient(userClient);
      verify(newRole).setOrganization(starOrg);
      verify(newRole).setActive(true);
      verify(newRole).setManual(true);
      verify(newRole).setTemplate(false);
      verify(newRole).setClientAdmin(false);
      verify(mockDal).save(newRole);
      obContextMock.verify(() -> OBContext.setAdminMode(true));
      obContextMock.verify(OBContext::restorePreviousMode);
    }
  }

  /**
   * A {@code user.getDefaultRole()} that is active/non-template/non-client-admin/same-client but
   * is ALSO assigned (via {@code AD_User_Roles}) to a DIFFERENT user entirely fails {@link
   * UserRoleCompositionService#isExclusivelyAssignedTo} — it is not genuinely this user's own
   * role, so a brand-new one must be minted instead of silently repurposing it (the exact
   * REVIEW-cycle-1 concern {@code isReusablePersonalRole}'s own javadoc documents).
   */
  @Test
  @SuppressWarnings("unchecked")
  void ensurePersonalRoleCreatesNewRoleWhenExistingCandidateBelongsToSomeoneElse() {
    Client userClient = mock(Client.class);
    when(userClient.getId()).thenReturn("client-A");

    User user = mock(User.class);
    when(user.getId()).thenReturn("user-1");
    when(user.getClient()).thenReturn(userClient);
    when(user.getName()).thenReturn("Jane Doe");

    Role notActuallyTheirs = mock(Role.class);
    when(notActuallyTheirs.isActive()).thenReturn(true);
    when(notActuallyTheirs.isTemplate()).thenReturn(false);
    when(notActuallyTheirs.isClientAdmin()).thenReturn(false);
    when(notActuallyTheirs.getClient()).thenReturn(userClient);
    when(user.getDefaultRole()).thenReturn(notActuallyTheirs);

    OBCriteria<RoleInheritance> roleInheritanceCriteria = mock(OBCriteria.class);
    when(mockDal.createCriteria(RoleInheritance.class)).thenReturn(roleInheritanceCriteria);
    when(roleInheritanceCriteria.list()).thenReturn(Collections.emptyList());

    User someoneElse = mock(User.class);
    when(someoneElse.getId()).thenReturn("user-2");
    UserRoles someoneElsesRow = mock(UserRoles.class);
    when(someoneElsesRow.getUserContact()).thenReturn(someoneElse);
    OBCriteria<UserRoles> userRolesCriteria = mock(OBCriteria.class);
    when(mockDal.createCriteria(UserRoles.class)).thenReturn(userRolesCriteria);
    when(userRolesCriteria.list()).thenReturn(List.of(someoneElsesRow));

    Organization starOrg = mock(Organization.class);
    when(mockDal.get(Organization.class, "0")).thenReturn(starOrg);
    OBCriteria<Role> nameUniquenessCriteria = mock(OBCriteria.class);
    when(mockDal.createCriteria(Role.class)).thenReturn(nameUniquenessCriteria);
    when(nameUniquenessCriteria.uniqueResult()).thenReturn(null);

    Role newRole = mock(Role.class);

    try (MockedStatic<OBContext> obContextMock = mockStatic(OBContext.class);
        MockedStatic<OBProvider> obProviderMock = mockStatic(OBProvider.class)) {
      OBProvider obProvider = mock(OBProvider.class);
      obProviderMock.when(OBProvider::getInstance).thenReturn(obProvider);
      when(obProvider.get(Role.class)).thenReturn(newRole);
      when(obProvider.get(RoleOrganization.class)).thenAnswer(inv -> mock(RoleOrganization.class));

      Role result = service.ensurePersonalRole(user);

      assertNotNull(result);
      assertSame(newRole, result, "A role belonging to someone else must never be reused, even "
          + "though every other field-level check passes");
      verify(mockDal).save(newRole);
    }
  }

  // ── ETP-4830 bug fix: createFreshPersonalRole (never reuses Default_Ad_Role_ID) ──

  @Test
  void createFreshPersonalRoleRejectsNullUser() {
    OBException e = assertThrows(OBException.class, () -> service.createFreshPersonalRole(null));
    assertTrue(e.getMessage().contains("Missing user"));
  }

  /**
   * The exact repro this method was added to fix: a candidate {@code user.getDefaultRole()} that
   * satisfies EVERY {@link UserRoleCompositionService#isReusablePersonalRole} check — including a
   * REAL, exclusively-assigned {@code AD_User_Roles} row, the strongest possible "genuinely
   * theirs" evidence, the same fixture {@link
   * #ensurePersonalRoleReusesExistingRoleWhenIdentityCheckIsSatisfied} uses to prove {@link
   * UserRoleCompositionService#ensurePersonalRole} DOES reuse it — must still NEVER be returned by
   * {@link UserRoleCompositionService#createFreshPersonalRole}: a brand-new role must always be
   * minted instead. This proves {@code createFreshPersonalRole} does not merely have a narrower
   * reuse check — it never even reads {@code user.getDefaultRole()}, since even a candidate this
   * strong is bypassed entirely.
   */
  @Test
  @SuppressWarnings("unchecked")
  void createFreshPersonalRoleNeverReusesEvenAFullyQualifyingExistingRole() {
    Client userClient = mock(Client.class);
    when(userClient.getId()).thenReturn("client-A");

    User user = mock(User.class);
    when(user.getId()).thenReturn("user-1");
    when(user.getClient()).thenReturn(userClient);
    when(user.getName()).thenReturn("Jane Doe");

    Role existingPersonalRole = mock(Role.class);
    when(existingPersonalRole.getId()).thenReturn("personal-role-1");
    when(existingPersonalRole.isActive()).thenReturn(true);
    when(existingPersonalRole.isTemplate()).thenReturn(false);
    when(existingPersonalRole.isClientAdmin()).thenReturn(false);
    when(existingPersonalRole.getClient()).thenReturn(userClient);
    when(user.getDefaultRole()).thenReturn(existingPersonalRole);

    UserRoles ownRow = mock(UserRoles.class);
    when(ownRow.getUserContact()).thenReturn(user);
    OBCriteria<UserRoles> userRolesCriteria = mock(OBCriteria.class);
    when(mockDal.createCriteria(UserRoles.class)).thenReturn(userRolesCriteria);
    when(userRolesCriteria.list()).thenReturn(List.of(ownRow));

    Organization starOrg = mock(Organization.class);
    when(mockDal.get(Organization.class, "0")).thenReturn(starOrg);
    OBCriteria<Role> nameUniquenessCriteria = mock(OBCriteria.class);
    when(mockDal.createCriteria(Role.class)).thenReturn(nameUniquenessCriteria);
    when(nameUniquenessCriteria.uniqueResult()).thenReturn(null);

    Role newRole = mock(Role.class);

    try (MockedStatic<OBContext> obContextMock = mockStatic(OBContext.class);
        MockedStatic<OBProvider> obProviderMock = mockStatic(OBProvider.class)) {
      OBProvider obProvider = mock(OBProvider.class);
      obProviderMock.when(OBProvider::getInstance).thenReturn(obProvider);
      when(obProvider.get(Role.class)).thenReturn(newRole);
      when(obProvider.get(RoleOrganization.class)).thenAnswer(inv -> mock(RoleOrganization.class));

      Role result = service.createFreshPersonalRole(user);

      assertSame(newRole, result, "createFreshPersonalRole must always mint a brand-new role, "
          + "never reuse Default_Ad_Role_ID even when it would otherwise pass every reuse check");
      verify(mockDal).save(newRole);
      verify(user, never()).getDefaultRole();
      obContextMock.verify(() -> OBContext.setAdminMode(true));
      obContextMock.verify(OBContext::restorePreviousMode);
    }
  }

  /**
   * Same "get(Organization, '0')"/uniqueness-check plumbing as {@link
   * #ensurePersonalRoleCreatesNewRoleWhenNoDefaultRoleYet}, confirming {@code
   * createFreshPersonalRole} produces the exact same shape of role {@link
   * UserRoleCompositionService#createPersonalRole} always builds (manual, non-template,
   * non-client-admin, scoped to the user's client and the {@code "0"} organization) — this method
   * is a thin admin-mode wrapper around it, not a separate implementation.
   */
  @Test
  @SuppressWarnings("unchecked")
  void createFreshPersonalRoleBuildsRoleWithExpectedShape() {
    Client userClient = mock(Client.class);
    when(userClient.getId()).thenReturn("client-A");

    User user = mock(User.class);
    when(user.getId()).thenReturn("user-1");
    when(user.getClient()).thenReturn(userClient);
    when(user.getName()).thenReturn("Jane Doe");

    Organization starOrg = mock(Organization.class);
    when(mockDal.get(Organization.class, "0")).thenReturn(starOrg);

    OBCriteria<Role> nameUniquenessCriteria = mock(OBCriteria.class);
    when(mockDal.createCriteria(Role.class)).thenReturn(nameUniquenessCriteria);
    when(nameUniquenessCriteria.uniqueResult()).thenReturn(null);

    Role newRole = mock(Role.class);

    try (MockedStatic<OBContext> obContextMock = mockStatic(OBContext.class);
        MockedStatic<OBProvider> obProviderMock = mockStatic(OBProvider.class)) {
      OBProvider obProvider = mock(OBProvider.class);
      obProviderMock.when(OBProvider::getInstance).thenReturn(obProvider);
      when(obProvider.get(Role.class)).thenReturn(newRole);
      when(obProvider.get(RoleOrganization.class)).thenAnswer(inv -> mock(RoleOrganization.class));

      Role result = service.createFreshPersonalRole(user);

      assertSame(newRole, result);
      verify(newRole).setNewOBObject(true);
      verify(newRole).setClient(userClient);
      verify(newRole).setOrganization(starOrg);
      verify(newRole).setActive(true);
      verify(newRole).setManual(true);
      verify(newRole).setTemplate(false);
      verify(newRole).setClientAdmin(false);
      verify(mockDal).save(newRole);
    }
  }

  // ── ETP-4830 item #6.1/#6.2: org access + user defaults on a freshly-minted personal role ──

  /**
   * Confirmed against real tenant data before this fix: a freshly-minted personal role had ZERO
   * {@code AD_Role_OrgAccess} rows, and the user's {@code Default_Ad_Client_ID}/
   * {@code Default_Ad_Org_ID}/{@code EM_SMFSWS_Default_WS_Role_ID} were left at whatever generic
   * {@code AD_User} defaulting produced — NOT tenant-scoped (one test user's
   * {@code Default_Ad_Client_ID} pointed at a completely different tenant's client). This test
   * locks in the fix: two {@code AD_Role_OrgAccess} rows (the user's own organization + the
   * wildcard {@code '*'}), and the user's default client/organization/warehouse/web-services-role
   * all set to real, tenant-scoped values.
   */
  @Test
  @SuppressWarnings("unchecked")
  void createFreshPersonalRoleGrantsOrgAccessAndSetsUserDefaults() {
    Client userClient = mock(Client.class);
    when(userClient.getId()).thenReturn("client-A");

    Organization userOrg = mock(Organization.class);
    when(userOrg.getId()).thenReturn("org-real");

    User user = mock(User.class);
    when(user.getId()).thenReturn("user-1");
    when(user.getClient()).thenReturn(userClient);
    when(user.getName()).thenReturn("Jane Doe");
    when(user.getOrganization()).thenReturn(userOrg);

    Organization starOrg = mock(Organization.class);
    when(starOrg.getId()).thenReturn("0");
    when(mockDal.get(Organization.class, "0")).thenReturn(starOrg);

    OBCriteria<Role> nameUniquenessCriteria = mock(OBCriteria.class);
    when(mockDal.createCriteria(Role.class)).thenReturn(nameUniquenessCriteria);
    when(nameUniquenessCriteria.uniqueResult()).thenReturn(null);

    Warehouse warehouse = mock(Warehouse.class);
    OBCriteria<Warehouse> warehouseCriteria = mock(OBCriteria.class);
    when(mockDal.createCriteria(Warehouse.class)).thenReturn(warehouseCriteria);
    when(warehouseCriteria.uniqueResult()).thenReturn(warehouse);

    Role newRole = mock(Role.class);
    when(newRole.getClient()).thenReturn(userClient);
    RoleOrganization userOrgAccess = mock(RoleOrganization.class);
    RoleOrganization starOrgAccess = mock(RoleOrganization.class);

    try (MockedStatic<OBContext> obContextMock = mockStatic(OBContext.class);
        MockedStatic<OBProvider> obProviderMock = mockStatic(OBProvider.class)) {
      OBProvider obProvider = mock(OBProvider.class);
      obProviderMock.when(OBProvider::getInstance).thenReturn(obProvider);
      when(obProvider.get(Role.class)).thenReturn(newRole);
      when(obProvider.get(RoleOrganization.class)).thenReturn(userOrgAccess, starOrgAccess);

      Role result = service.createFreshPersonalRole(user);

      assertSame(newRole, result);

      // Two org-access rows: the user's real organization, then the wildcard.
      verify(userOrgAccess).setNewOBObject(true);
      verify(userOrgAccess).setClient(userClient);
      verify(userOrgAccess).setOrganization(userOrg);
      verify(userOrgAccess).setRole(newRole);
      verify(userOrgAccess).setActive(true);
      verify(userOrgAccess).setOrgAdmin(false);
      verify(mockDal).save(userOrgAccess);

      verify(starOrgAccess).setNewOBObject(true);
      verify(starOrgAccess).setClient(userClient);
      verify(starOrgAccess).setOrganization(starOrg);
      verify(starOrgAccess).setRole(newRole);
      verify(mockDal).save(starOrgAccess);

      // User defaults: client, organization, warehouse, and web-services role are all set here.
      // Default_Ad_Role_ID is deliberately NOT this method's job (see its own javadoc).
      verify(user).setDefaultClient(userClient);
      verify(user).setDefaultOrganization(userOrg);
      verify(user).setDefaultWarehouse(warehouse);
      verify(user).setSmfswsDefaultWsRole(newRole);
      verify(mockDal).save(user);
    }
  }

  /**
   * {@code user.getOrganization()} is {@code '*'} itself (a real, if unusual, case) — the
   * user-organization access row must not be created a second time on top of the wildcard row.
   */
  @Test
  @SuppressWarnings("unchecked")
  void createFreshPersonalRoleSkipsDuplicateOrgAccessWhenUserOrgIsAlreadyWildcard() {
    Client userClient = mock(Client.class);
    when(userClient.getId()).thenReturn("client-A");

    Organization starOrg = mock(Organization.class);
    when(starOrg.getId()).thenReturn("0");
    when(mockDal.get(Organization.class, "0")).thenReturn(starOrg);

    User user = mock(User.class);
    when(user.getId()).thenReturn("user-1");
    when(user.getClient()).thenReturn(userClient);
    when(user.getName()).thenReturn("Jane Doe");
    when(user.getOrganization()).thenReturn(starOrg);

    OBCriteria<Role> nameUniquenessCriteria = mock(OBCriteria.class);
    when(mockDal.createCriteria(Role.class)).thenReturn(nameUniquenessCriteria);
    when(nameUniquenessCriteria.uniqueResult()).thenReturn(null);

    OBCriteria<Warehouse> warehouseCriteria = mock(OBCriteria.class);
    when(mockDal.createCriteria(Warehouse.class)).thenReturn(warehouseCriteria);
    when(warehouseCriteria.uniqueResult()).thenReturn(null);

    Role newRole = mock(Role.class);
    when(newRole.getClient()).thenReturn(userClient);
    RoleOrganization starOrgAccess = mock(RoleOrganization.class);

    try (MockedStatic<OBContext> obContextMock = mockStatic(OBContext.class);
        MockedStatic<OBProvider> obProviderMock = mockStatic(OBProvider.class)) {
      OBProvider obProvider = mock(OBProvider.class);
      obProviderMock.when(OBProvider::getInstance).thenReturn(obProvider);
      when(obProvider.get(Role.class)).thenReturn(newRole);
      when(obProvider.get(RoleOrganization.class)).thenReturn(starOrgAccess);

      service.createFreshPersonalRole(user);

      verify(obProvider, times(1)).get(RoleOrganization.class);
      verify(mockDal, times(1)).save(starOrgAccess);
    }
  }

  /** No organization at all on the user (edge case) — default org/warehouse are simply skipped. */
  @Test
  @SuppressWarnings("unchecked")
  void createFreshPersonalRoleSkipsDefaultOrgAndWarehouseWhenUserHasNoOrganization() {
    Client userClient = mock(Client.class);
    when(userClient.getId()).thenReturn("client-A");

    User user = mock(User.class);
    when(user.getId()).thenReturn("user-1");
    when(user.getClient()).thenReturn(userClient);
    when(user.getName()).thenReturn("Jane Doe");
    when(user.getOrganization()).thenReturn(null);

    Organization starOrg = mock(Organization.class);
    when(starOrg.getId()).thenReturn("0");
    when(mockDal.get(Organization.class, "0")).thenReturn(starOrg);

    OBCriteria<Role> nameUniquenessCriteria = mock(OBCriteria.class);
    when(mockDal.createCriteria(Role.class)).thenReturn(nameUniquenessCriteria);
    when(nameUniquenessCriteria.uniqueResult()).thenReturn(null);

    Role newRole = mock(Role.class);

    try (MockedStatic<OBContext> obContextMock = mockStatic(OBContext.class);
        MockedStatic<OBProvider> obProviderMock = mockStatic(OBProvider.class)) {
      OBProvider obProvider = mock(OBProvider.class);
      obProviderMock.when(OBProvider::getInstance).thenReturn(obProvider);
      when(obProvider.get(Role.class)).thenReturn(newRole);
      when(obProvider.get(RoleOrganization.class)).thenAnswer(inv -> mock(RoleOrganization.class));

      service.createFreshPersonalRole(user);

      verify(user, never()).setDefaultOrganization(any());
      verify(user, never()).setDefaultWarehouse(any());
      verify(user).setDefaultClient(userClient);
      verify(user).setSmfswsDefaultWsRole(newRole);
    }
  }
}
