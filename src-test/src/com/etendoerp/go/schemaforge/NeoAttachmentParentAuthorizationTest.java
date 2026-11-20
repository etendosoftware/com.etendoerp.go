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
 * All portions are Copyright © 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.schemaforge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.withSettings;
import static org.mockito.Mockito.when;

import java.util.Set;

import org.junit.After;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.base.exception.OBException;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.ModelProvider;
import org.openbravo.base.structure.ActiveEnabled;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.base.structure.ClientEnabled;
import org.openbravo.base.structure.OrganizationEnabled;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.datamodel.Table;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.ad.utility.Attachment;
import org.openbravo.model.common.enterprise.Organization;

import com.etendoerp.go.schemaforge.NeoAttachmentAuthorizer.Access;

/**
 * ETP-4570 — SEC-11b parent-record authorization matrix (ADR-0003 scenarios S1–S8) for
 * {@link NeoAttachmentAuthorizer#authorizeById} and
 * {@link NeoAttachmentAuthorizer#authorizeByRecord}.
 *
 * <p>Declared coverage: S1–S7 are exercised here for the authorizer, which is the single decision
 * point shared by list, count, main, upload, download one, download all (zip), delete, update
 * description and mark-main. S8 is S1 under a role whose readable-organization set is broad —
 * there is no administrator branch (ADR-0003 D4). The dispatcher wiring is covered by
 * {@code NeoBuiltInEndpointHandlerTest}.</p>
 *
 * @covers com.etendoerp.go.schemaforge.NeoAttachmentAuthorizer
 */
public class NeoAttachmentParentAuthorizationTest {

  private static final String ATT_ID = "ATT1";
  private static final String TABLE_ID = "259";
  private static final String TABLE_NAME = "C_Order";
  private static final String ENTITY_NAME = "Order";
  private static final String RECORD_ID = "REC1";
  private static final String OWN_CLIENT = "client-own";
  private static final String OWN_ORG = "org-own";
  private static final String OTHER_ORG = "org-other";
  private static final String OTHER_CLIENT = "client-other";

  /** Releases inline mocks, like the neighbouring tests. */
  @After
  public void clearMocks() {
    Mockito.framework().clearInlineMocks();
  }

  // ── S1 / S8 ───────────────────────────────────────────────────────────────

  @Test
  public void s1OwnerIsAllowedForReadAndWrite() {
    try (Env env = new Env(parent(OWN_CLIENT, OWN_ORG, true))) {
      assertNotNull(NeoAttachmentAuthorizer.authorizeById(ATT_ID, Access.READ));
      assertNotNull(NeoAttachmentAuthorizer.authorizeById(ATT_ID, Access.WRITE));
      assertTrue(NeoAttachmentAuthorizer.authorizeByRecord(TABLE_NAME, RECORD_ID, Access.READ));
      assertTrue(NeoAttachmentAuthorizer.authorizeByRecord(TABLE_NAME, RECORD_ID, Access.WRITE));
    }
  }

  @Test
  public void s8MultiOrgRoleIsAllowedThroughTheNormalPermissionModel() {
    try (Env env = new Env(parent(OWN_CLIENT, OTHER_ORG, true))) {
      env.readableOrgs = new String[] {OWN_ORG, OTHER_ORG};
      env.writableOrgs = Set.of(OWN_ORG, OTHER_ORG);
      assertNotNull(NeoAttachmentAuthorizer.authorizeById(ATT_ID, Access.READ));
      assertNotNull(NeoAttachmentAuthorizer.authorizeById(ATT_ID, Access.WRITE));
    }
  }

  // ── S2 same-org, no entity access ──────────────────────────────────────────

  @Test
  public void s2SameOrgRoleWithoutEntityAccessIsRejected() {
    try (Env env = new Env(parent(OWN_CLIENT, OWN_ORG, true))) {
      env.entityReadable = false;
      env.entityWritable = false;
      assertNull(NeoAttachmentAuthorizer.authorizeById(ATT_ID, Access.READ));
      assertNull(NeoAttachmentAuthorizer.authorizeById(ATT_ID, Access.WRITE));
      assertFalse(NeoAttachmentAuthorizer.authorizeByRecord(TABLE_NAME, RECORD_ID, Access.READ));
    }
  }

  @Test
  public void readOnlyEntityAccessCannotWrite() {
    try (Env env = new Env(parent(OWN_CLIENT, OWN_ORG, true))) {
      env.entityWritable = false;
      assertNotNull(NeoAttachmentAuthorizer.authorizeById(ATT_ID, Access.READ));
      assertNull(NeoAttachmentAuthorizer.authorizeById(ATT_ID, Access.WRITE));
    }
  }

  // ── S3 non-readable org, S4 cross-client ───────────────────────────────────

  @Test
  public void s3ParentInNonReadableOrganizationIsRejected() {
    try (Env env = new Env(parent(OWN_CLIENT, OTHER_ORG, true))) {
      assertNull(NeoAttachmentAuthorizer.authorizeById(ATT_ID, Access.READ));
      assertFalse(NeoAttachmentAuthorizer.authorizeByRecord(TABLE_NAME, RECORD_ID, Access.READ));
    }
  }

  @Test
  public void s4CrossClientParentIsRejected() {
    try (Env env = new Env(parent(OTHER_CLIENT, OWN_ORG, true))) {
      assertNull(NeoAttachmentAuthorizer.authorizeById(ATT_ID, Access.READ));
      assertNull(NeoAttachmentAuthorizer.authorizeById(ATT_ID, Access.WRITE));
    }
  }

  @Test
  public void readableButNotWritableOrganizationCannotWrite() {
    try (Env env = new Env(parent(OWN_CLIENT, OWN_ORG, true))) {
      env.writableOrgs = Set.of();
      assertNotNull(NeoAttachmentAuthorizer.authorizeById(ATT_ID, Access.READ));
      assertNull(NeoAttachmentAuthorizer.authorizeById(ATT_ID, Access.WRITE));
    }
  }

  // ── S5 inactive, S6 missing ────────────────────────────────────────────────

  @Test
  public void s5InactiveParentRecordIsRejected() {
    try (Env env = new Env(parent(OWN_CLIENT, OWN_ORG, false))) {
      assertNull(NeoAttachmentAuthorizer.authorizeById(ATT_ID, Access.READ));
      assertFalse(NeoAttachmentAuthorizer.authorizeByRecord(TABLE_NAME, RECORD_ID, Access.READ));
    }
  }

  @Test
  public void s6MissingAttachmentMissingParentAndUnauthorizedAreIndistinguishable() {
    try (Env env = new Env(null)) {
      env.attachmentPresent = false;
      assertNull(NeoAttachmentAuthorizer.authorizeById(ATT_ID, Access.READ));
      assertFalse(NeoAttachmentAuthorizer.authorizeByRecord(TABLE_NAME, RECORD_ID, Access.READ));
    }
    NeoResponse notFound = NeoAttachmentAuthorizer.notFound();
    assertEquals(404, notFound.getHttpStatus());
    assertEquals(NeoResponse.error(404, NeoAttachmentsHelper.ERR_ATTACHMENT_NOT_FOUND)
        .getBody().toString(), notFound.getBody().toString());
  }

  // ── S7 parent mismatch ─────────────────────────────────────────────────────

  @Test
  public void s7SuppliedContextNotMatchingTheRealParentIsRejected() {
    try (Env env = new Env(parent(OWN_CLIENT, OWN_ORG, true))) {
      assertNotNull(NeoAttachmentAuthorizer.authorizeById(ATT_ID, TABLE_NAME, RECORD_ID,
          Access.READ));
      assertNull(NeoAttachmentAuthorizer.authorizeById(ATT_ID, TABLE_NAME, "OTHER-RECORD",
          Access.READ));
      assertNull(NeoAttachmentAuthorizer.authorizeById(ATT_ID, "AD_User", RECORD_ID,
          Access.READ));
    }
  }

  // ── admin mode parity, entity-less tables ──────────────────────────────────

  /**
   * Mock-level only: it stubs {@code isInAdministratorMode} and the entity-access seam, so it cannot
   * detect a real admin-mode dependency. The real parity proof is
   * {@code NeoAttachmentAuthorizationIntegrationTest}, which runs with and without admin mode.
   */
  @Test
  public void decisionIsTheSameWithAdminModeOnAndOffAtEntry() {
    for (boolean adminMode : new boolean[] {true, false}) {
      try (Env env = new Env(parent(OWN_CLIENT, OWN_ORG, true))) {
        when(env.context.isInAdministratorMode()).thenReturn(adminMode);
        assertNotNull(NeoAttachmentAuthorizer.authorizeById(ATT_ID, Access.READ));
        env.entityReadable = false;
        assertNull(NeoAttachmentAuthorizer.authorizeById(ATT_ID, Access.READ));
      }
      try (Env env = new Env(parent(OTHER_CLIENT, OWN_ORG, true))) {
        when(env.context.isInAdministratorMode()).thenReturn(adminMode);
        assertNull(NeoAttachmentAuthorizer.authorizeById(ATT_ID, Access.READ));
      }
    }
  }

  /**
   * A table with no DAL entity has no record to judge, so it is allowed here. WRITE is then still
   * subject to the window tier; READ (list, count, main, zip, download) has no further check for
   * such a table. Accepted: the platform's attach manager does not handle entity-less tables.
   */
  @Test
  public void tableWithoutDalEntityIsLeftToTheWindowTier() {
    try (Env env = new Env(null)) {
      when(ModelProvider.getInstance().getEntityByTableId(TABLE_ID)).thenReturn(null);
      assertTrue(NeoAttachmentAuthorizer.authorizeByRecord(TABLE_NAME, RECORD_ID, Access.READ));
    }
  }

  /** Blank context and unknown tables are answered by the operation itself, not authorized here. */
  @Test
  public void blankOrUnknownTableIsLeftToTheOperation() {
    try (Env env = new Env(null)) {
      env.helper.when(() -> NeoAttachmentsHelper.resolveTableId("No_Such_Table"))
          .thenThrow(new OBException("Unknown table"));
      assertTrue(NeoAttachmentAuthorizer.authorizeByRecord("No_Such_Table", RECORD_ID, Access.READ));
      assertTrue(NeoAttachmentAuthorizer.authorizeByRecord("", RECORD_ID, Access.READ));
      assertTrue(NeoAttachmentAuthorizer.authorizeByRecord(TABLE_NAME, "", Access.READ));
    }
  }

  // ── helpers ────────────────────────────────────────────────────────────────

  static BaseOBObject parent(String clientId, String orgId, boolean active) {
    BaseOBObject row = mock(BaseOBObject.class, withSettings()
        .extraInterfaces(ClientEnabled.class, OrganizationEnabled.class, ActiveEnabled.class));
    Client client = mock(Client.class);
    when(client.getId()).thenReturn(clientId);
    when(((ClientEnabled) row).getClient()).thenReturn(client);
    Organization org = mock(Organization.class);
    when(org.getId()).thenReturn(orgId);
    when(((OrganizationEnabled) row).getOrganization()).thenReturn(org);
    when(((ActiveEnabled) row).isActive()).thenReturn(active);
    return row;
  }

  /** Mocks the DAL, model and session for one scenario. */
  static final class Env implements AutoCloseable {
    final MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
    final MockedStatic<OBContext> obContext = mockStatic(OBContext.class);
    final MockedStatic<ModelProvider> model = mockStatic(ModelProvider.class);
    final MockedStatic<NeoAttachmentsHelper> helper =
        mockStatic(NeoAttachmentsHelper.class, Mockito.CALLS_REAL_METHODS);
    final OBDal dal = mock(OBDal.class);
    final OBContext context = mock(OBContext.class);
    final MockedStatic<NeoAttachmentAuthorizer> authorizer =
        mockStatic(NeoAttachmentAuthorizer.class, Mockito.CALLS_REAL_METHODS);
    final Entity entity = mock(Entity.class);
    final Attachment attachment = mock(Attachment.class);
    boolean attachmentPresent = true;
    boolean entityReadable = true;
    boolean entityWritable = true;
    String[] readableOrgs = {OWN_ORG};
    Set<String> writableOrgs = Set.of(OWN_ORG);

    Env(BaseOBObject parent) {
      obDal.when(OBDal::getInstance).thenReturn(dal);
      obContext.when(OBContext::getOBContext).thenReturn(context);
      ModelProvider provider = mock(ModelProvider.class);
      model.when(ModelProvider::getInstance).thenReturn(provider);
      when(provider.getEntityByTableId(TABLE_ID)).thenReturn(entity);
      when(entity.getName()).thenReturn(ENTITY_NAME);
      helper.when(() -> NeoAttachmentsHelper.resolveTableId(anyString())).thenAnswer(inv ->
          TABLE_NAME.equalsIgnoreCase(inv.getArgument(0)) ? TABLE_ID : "OTHER-TABLE");
      Table table = mock(Table.class);
      when(table.getId()).thenReturn(TABLE_ID);
      when(table.getDBTableName()).thenReturn(TABLE_NAME);
      when(attachment.getTable()).thenReturn(table);
      when(attachment.getRecord()).thenReturn(RECORD_ID);
      Client c = mock(Client.class);
      when(c.getId()).thenReturn(OWN_CLIENT);
      Organization o = mock(Organization.class);
      when(o.getId()).thenReturn(OWN_ORG);
      when(attachment.getClient()).thenReturn(c);
      when(attachment.getOrganization()).thenReturn(o);
      when(dal.get(ENTITY_NAME, RECORD_ID)).thenReturn(parent);
      when(dal.get(Attachment.class, ATT_ID)).thenAnswer(inv -> attachmentPresent ? attachment : null);
      when(context.getReadableClients()).thenReturn(new String[] {OWN_CLIENT});
      when(context.getCurrentClient()).thenReturn(c);
      when(context.getReadableOrganizations()).thenAnswer(inv -> readableOrgs);
      when(context.getWritableOrganizations()).thenAnswer(inv -> writableOrgs);
      authorizer.when(() -> NeoAttachmentAuthorizer.hasEntityAccess(any(), any(), any()))
          .thenAnswer(inv -> inv.getArgument(2) == Access.WRITE ? entityWritable : entityReadable);
    }

    @Override
    public void close() {
      authorizer.close();
      helper.close();
      model.close();
      obContext.close();
      obDal.close();
    }
  }
}
