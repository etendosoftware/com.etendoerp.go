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

package com.etendoerp.go.rest;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.util.Date;
import java.util.UUID;

import org.hibernate.Session;
import org.hibernate.query.NativeQuery;
import org.junit.After;
import org.junit.Test;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.access.User;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.test.base.OBBaseTest;

import com.etendoerp.go.schemaforge.data.Invitation;

/**
 * ETP-4830 — real-DB proof that {@code ETGO_INVITATION_USER_FK} now cascades: deleting an
 * {@code AD_User} that owns an {@code ETGO_INVITATION} row must delete the invitation with it,
 * not fail with the classic Etendo "this record cannot be deleted, it is related to other
 * existing elements" 500.
 *
 * <p>Confirmed manually and via {@code pg_constraint} before this fix: {@code AD_USER_ID} on
 * {@code ETGO_INVITATION} referenced {@code AD_USER} with no {@code ON DELETE} behavior (plain
 * {@code NO ACTION}), a pre-existing gap in ETP-4894's table design that only surfaced once
 * ETP-4830 made admin-created-user invitations part of the routine user-create flow — see
 * {@code docs/neo-headless.md} §"Real-world example — `UserRoleAssignmentHandler`'s
 * admin-created-user invitation" for the full narrative. The fix adds
 * {@code onDelete="cascade"} to {@code ETGO_INVITATION_USER_FK} in
 * {@code src-db/database/model/tables/ETGO_INVITATION.xml}.</p>
 *
 * <p>This is a DB-level FK behavior, not application logic, so the assertion that matters is
 * that {@link OBDal#remove(org.openbravo.base.structure.BaseOBObject)} +
 * {@link OBDal#flush()} on the {@code User} does NOT throw, and that the {@code ETGO_INVITATION}
 * row is physically gone afterward. The post-delete check uses a native SQL count (not an HQL
 * query on {@link Invitation}) so it hits the database directly instead of Hibernate's
 * first-level session cache, which has no way to know the DB engine cascaded the delete out from
 * under it.</p>
 *
 * <p>Nothing here is ever committed — the whole scenario (throwaway user, its invitation, and
 * the delete itself) is rolled back in {@link #rollbackChanges()}, mirroring
 * {@code TbaiSyncStatusInjectorIntegrationTest}'s convention.</p>
 */
public class EtgoInvitationUserCascadeDeleteIntegrationTest extends OBBaseTest {

  @After
  public void rollbackChanges() {
    while (OBContext.getOBContext() != null
        && OBContext.getOBContext().isInAdministratorMode()) {
      OBContext.restorePreviousMode();
    }
    OBDal.getInstance().rollbackAndClose();
  }

  @Test
  public void testDeletingUserCascadesToItsInvitation() throws Exception {
    setTestUserContext();
    OBContext.setAdminMode(true);
    try {
      // Scoped to the test context's OWN client/org (matches TbaiSyncStatusInjectorIntegrationTest's
      // convention of reusing an already-accessible entity's client) rather than a hardcoded System
      // client "0" — setTestUserContext() logs in as TEST_CLIENT_ID, so a brand-new AD_User created
      // under client "0" would fail SecurityChecker's ClientList check on this method's own flush(es).
      Client testClient = OBDal.getInstance().get(Client.class, TEST_CLIENT_ID);
      Organization testOrg = OBDal.getInstance().get(Organization.class, TEST_ORG_ID);
      String unique = UUID.randomUUID().toString().replace("-", "");

      User user = OBProvider.getInstance().get(User.class);
      user.setClient(testClient);
      user.setOrganization(testOrg);
      user.setName("ETP-4830 cascade test user");
      user.setUsername("etp4830-cascade-test-" + unique);
      user.setPassword("x");
      OBDal.getInstance().save(user);
      OBDal.getInstance().flush();
      String userId = user.getId();

      Invitation invitation = OBProvider.getInstance().get(Invitation.class);
      invitation.setClient(testClient);
      invitation.setOrganization(testOrg);
      invitation.setUser(user);
      invitation.setEmail("etp4830-cascade-test-" + unique + "@example.com");
      invitation.setTokenHash(unique);
      invitation.setStatus("PENDING");
      invitation.setExpiresAt(new Date(System.currentTimeMillis() + 7L * 24 * 60 * 60 * 1000));
      OBDal.getInstance().save(invitation);
      OBDal.getInstance().flush();
      String invitationId = invitation.getId();
      assertNotNull("Fixture invitation must have been persisted before the delete", invitationId);

      // This is the exact repro of the reported bug: deleting the AD_User that owns an
      // ETGO_INVITATION row. Before the ETGO_INVITATION_USER_FK cascade fix, this flush() threw
      // an OBException ("this record cannot be deleted, it is related to other existing
      // elements") because the FK had no ON DELETE behavior.
      OBDal.getInstance().remove(user);
      OBDal.getInstance().flush();

      Session session = OBDal.getInstance().getSession();
      @SuppressWarnings("rawtypes")
      NativeQuery countQuery = session.createNativeQuery(
          "SELECT COUNT(*) FROM ETGO_INVITATION WHERE ETGO_INVITATION_ID = :invitationId");
      countQuery.setParameter("invitationId", invitationId);
      Number remaining = (Number) countQuery.uniqueResult();

      assertEquals(
          "ETGO_INVITATION_USER_FK must cascade-delete the invitation when its AD_User is "
              + "deleted — a dangling invitation for a user that no longer exists can never be "
              + "accepted",
          0L, remaining.longValue());

      @SuppressWarnings("rawtypes")
      NativeQuery userCountQuery = session
          .createNativeQuery("SELECT COUNT(*) FROM AD_USER WHERE AD_USER_ID = :userId");
      userCountQuery.setParameter("userId", userId);
      Number remainingUsers = (Number) userCountQuery.uniqueResult();
      assertEquals("Sanity check: AD_User itself must actually be gone too", 0L,
          remainingUsers.longValue());
    } finally {
      OBContext.restorePreviousMode();
    }
  }
}
