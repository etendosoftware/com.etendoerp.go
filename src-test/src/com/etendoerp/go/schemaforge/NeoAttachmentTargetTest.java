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
 * All portions are Copyright (C) 2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */
package com.etendoerp.go.schemaforge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.ModelProvider;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.datamodel.Table;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.ad.ui.Tab;
import org.openbravo.model.ad.utility.Attachment;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.common.order.Order;

import com.etendoerp.go.schemaforge.data.SFEntity;
import com.etendoerp.go.schemaforge.data.SFSpec;
import com.etendoerp.go.schemaforge.util.NeoAccessHelper;

/**
 * ETP-5715 — the document an MCP attachment operation is about is authorized before anything is
 * read, written or deleted: role tier, tenant, existence, and the attachment-belongs-to-the-record
 * rule. The DAL and the role checks are mocked at their edges; {@link TenantOwnership} runs for
 * real, so the client/organization rule is the production one.
 *
 * @covers com.etendoerp.go.schemaforge.NeoAttachmentTarget
 */
@DisplayName("ETP-5715 — NeoAttachmentTarget")
class NeoAttachmentTargetTest {

  private static final String OWN_CLIENT = "client-own";
  private static final String OTHER_CLIENT = "client-other";
  private static final String OWN_ORG = "org-own";
  private static final String OTHER_ORG = "org-other";
  private static final String READ_ONLY_ORG = "org-read-only";
  private static final String TABLE_ID = "TABLE_C_ORDER";
  private static final String RECORD = "order-1";
  private static final String ATTACHMENT = "file-1";

  private MockedStatic<OBDal> dalMock;
  private MockedStatic<OBContext> contextMock;
  private MockedStatic<ModelProvider> modelMock;
  private MockedStatic<NeoAccessHelper> accessMock;
  private MockedStatic<NeoAttachmentAuthorizer> authorizerMock;
  private OBDal dal;
  private OBContext context;
  private ModelProvider model;
  private SFSpec spec;

  @BeforeEach
  void setUp() {
    dal = mock(OBDal.class);
    dalMock = mockStatic(OBDal.class);
    dalMock.when(OBDal::getInstance).thenReturn(dal);
    context = mock(OBContext.class);
    when(context.getReadableClients()).thenReturn(new String[] { OWN_CLIENT });
    when(context.getReadableOrganizations()).thenReturn(new String[] { OWN_ORG, READ_ONLY_ORG });
    when(context.getWritableOrganizations()).thenReturn(Set.of(OWN_ORG));
    contextMock = mockStatic(OBContext.class);
    contextMock.when(OBContext::getOBContext).thenReturn(context);
    model = mock(ModelProvider.class);
    modelMock = mockStatic(ModelProvider.class);
    modelMock.when(ModelProvider::getInstance).thenReturn(model);
    spec = mock(SFSpec.class);
    when(spec.getName()).thenReturn("purchase-order");
    accessMock = mockStatic(NeoAccessHelper.class);
    accessMock.when(() -> NeoAccessHelper.hasWindowAccessForSpec(spec, "GET")).thenReturn(true);
    accessMock.when(() -> NeoAccessHelper.hasWindowAccessForSpec(spec, "POST")).thenReturn(true);
    authorizerMock = mockStatic(NeoAttachmentAuthorizer.class);
    authorizerMock.when(() -> NeoAttachmentAuthorizer.checkWrite(anyString())).thenReturn(null);
  }

  @AfterEach
  void tearDown() {
    authorizerMock.close();
    accessMock.close();
    modelMock.close();
    contextMock.close();
    dalMock.close();
  }

  /** The spec's entity, whose AD tab sits at {@code level} on the order table. */
  private SFEntity entityAtLevel(long level) {
    Table table = mock(Table.class);
    when(table.getId()).thenReturn(TABLE_ID);
    when(table.getDBTableName()).thenReturn("C_Order");
    Tab tab = mock(Tab.class);
    when(tab.getTable()).thenReturn(table);
    when(tab.getId()).thenReturn("TAB1");
    when(tab.getTabLevel()).thenReturn(level);
    SFEntity entity = mock(SFEntity.class);
    when(entity.getName()).thenReturn("header");
    when(entity.getADTab()).thenReturn(tab);
    Entity dalEntity = mock(Entity.class);
    when(dalEntity.getName()).thenReturn(Order.ENTITY_NAME);
    when(model.getEntityByTableId(TABLE_ID)).thenReturn(dalEntity);
    return entity;
  }

  private Order order(String clientId, String orgId) {
    Client client = mock(Client.class);
    when(client.getId()).thenReturn(clientId);
    Organization org = mock(Organization.class);
    when(org.getId()).thenReturn(orgId);
    Order order = mock(Order.class);
    when(order.getClient()).thenReturn(client);
    when(order.getOrganization()).thenReturn(org);
    when(dal.get(Order.ENTITY_NAME, RECORD)).thenReturn(order);
    return order;
  }

  private static NeoAttachmentTarget.Refused refusalOf(Runnable call) {
    return assertThrows(NeoAttachmentTarget.Refused.class, call::run);
  }

  @Test
  @DisplayName("an own record resolves to its table, tab and id, for reading and for writing")
  void ownRecordResolves() {
    SFEntity entity = entityAtLevel(0L);
    order(OWN_CLIENT, OWN_ORG);

    NeoAttachmentTarget read = NeoAttachmentTarget.resolve(spec, entity, RECORD, false);
    NeoAttachmentTarget write = NeoAttachmentTarget.resolve(spec, entity, RECORD, true);

    assertEquals("C_Order", read.getTableName());
    assertEquals(TABLE_ID, read.getTableId());
    assertEquals("TAB1", write.getTabId());
    assertEquals(RECORD, write.getRecordId());
    assertEquals("purchase-order", write.getSpecName());
  }

  @Test
  @DisplayName("a completed document is not refused: the SPA attaches to any status")
  void documentStatusIsNeverConsulted() {
    SFEntity entity = entityAtLevel(0L);
    Order completed = order(OWN_CLIENT, OWN_ORG);
    when(completed.getDocumentStatus()).thenReturn("CO");
    when(completed.isProcessed()).thenReturn(true);

    assertNotNull(NeoAttachmentTarget.resolve(spec, entity, RECORD, true));
  }

  @Test
  @DisplayName("a missing record and another client's record answer the SAME 404")
  void missingAndForeignAreIndistinguishable() {
    SFEntity entity = entityAtLevel(0L);
    NeoAttachmentTarget.Refused missing = refusalOf(
        () -> NeoAttachmentTarget.resolve(spec, entity, "nope", false));
    order(OTHER_CLIENT, OWN_ORG);
    NeoAttachmentTarget.Refused foreign = refusalOf(
        () -> NeoAttachmentTarget.resolve(spec, entity, RECORD, false));

    assertEquals(404, missing.getStatus());
    assertEquals(404, foreign.getStatus());
    assertEquals(missing.getMessage(), foreign.getMessage());
    assertEquals(NeoAttachmentTarget.MSG_RECORD_NOT_FOUND, foreign.getMessage());
  }

  @Test
  @DisplayName("a record of a client the session reads but of an organization it cannot is a 404")
  void foreignOrganizationIsRefused() {
    SFEntity entity = entityAtLevel(0L);
    order(OWN_CLIENT, OTHER_ORG);

    assertEquals(404,
        refusalOf(() -> NeoAttachmentTarget.resolve(spec, entity, RECORD, true)).getStatus());
  }

  @Test
  @DisplayName("a table the DAL has no entity for cannot prove ownership, so it is a 404")
  void tableWithoutDalEntityIsRefused() {
    SFEntity entity = entityAtLevel(0L);
    when(model.getEntityByTableId(TABLE_ID)).thenReturn(null);

    assertEquals(404,
        refusalOf(() -> NeoAttachmentTarget.resolve(spec, entity, RECORD, false)).getStatus());
  }

  @Test
  @DisplayName("a role without access to the spec is a 403 before the record is looked at")
  void roleWithoutSpecAccessIsForbidden() {
    SFEntity entity = entityAtLevel(0L);
    accessMock.when(() -> NeoAccessHelper.hasWindowAccessForSpec(spec, "GET")).thenReturn(false);

    NeoAttachmentTarget.Refused refused = refusalOf(
        () -> NeoAttachmentTarget.resolve(spec, entity, RECORD, false));

    assertEquals(403, refused.getStatus());
    verify(dal, never()).get(anyString(), anyString());
  }

  @Test
  @DisplayName("a read-only role may read the attachments but is refused to write them")
  void readOnlyRoleCannotWrite() {
    SFEntity entity = entityAtLevel(0L);
    order(OWN_CLIENT, OWN_ORG);
    accessMock.when(() -> NeoAccessHelper.hasWindowAccessForSpec(spec, "POST")).thenReturn(false);

    assertNotNull(NeoAttachmentTarget.resolve(spec, entity, RECORD, false));
    assertEquals(403,
        refusalOf(() -> NeoAttachmentTarget.resolve(spec, entity, RECORD, true)).getStatus());
  }

  @Test
  @DisplayName("the table-level write rule of REST (checkWrite) applies to writes too")
  void tableLevelWriteRuleIsApplied() {
    SFEntity entity = entityAtLevel(0L);
    order(OWN_CLIENT, OWN_ORG);
    authorizerMock.when(() -> NeoAttachmentAuthorizer.checkWrite(TABLE_ID))
        .thenReturn(NeoResponse.error(403, NeoAttachmentAuthorizer.ACCESS_DENIED));

    assertEquals(403,
        refusalOf(() -> NeoAttachmentTarget.resolve(spec, entity, RECORD, true)).getStatus());
    assertNotNull(NeoAttachmentTarget.resolve(spec, entity, RECORD, false));
  }

  @Test
  @DisplayName("a line entity is refused: attachments belong to the document itself")
  void lineEntityIsRefused() {
    SFEntity lines = entityAtLevel(1L);
    order(OWN_CLIENT, OWN_ORG);

    assertEquals(422,
        refusalOf(() -> NeoAttachmentTarget.resolve(spec, lines, RECORD, false)).getStatus());
  }

  @Test
  @DisplayName("an entity without tab, and a blank id, are refused as not attachable")
  void entityWithoutTabOrBlankIdIsRefused() {
    SFEntity noTab = mock(SFEntity.class);
    SFEntity entity = entityAtLevel(0L);

    assertEquals(422, refusalOf(() -> NeoAttachmentTarget.resolve(spec, noTab, RECORD, false))
        .getStatus());
    assertEquals(422, refusalOf(() -> NeoAttachmentTarget.resolve(spec, entity, " ", false))
        .getStatus());
  }

  private Attachment attachment(String tableId, String recordId) {
    Table table = mock(Table.class);
    when(table.getId()).thenReturn(tableId);
    Attachment attachment = mock(Attachment.class);
    when(attachment.getTable()).thenReturn(table);
    when(attachment.getRecord()).thenReturn(recordId);
    when(dal.get(Attachment.class, ATTACHMENT)).thenReturn(attachment);
    return attachment;
  }

  @Test
  @DisplayName("an attachment of THIS record is returned")
  void ownAttachmentIsReturned() {
    SFEntity entity = entityAtLevel(0L);
    order(OWN_CLIENT, OWN_ORG);
    Attachment own = attachment(TABLE_ID, RECORD);

    assertSame(own, NeoAttachmentTarget.resolve(spec, entity, RECORD, true)
        .requireOwnAttachment(ATTACHMENT));
  }

  @Test
  @DisplayName("an attachment id of ANOTHER record is a 404, same as an unknown id (no bare-id IDOR)")
  void attachmentOfAnotherRecordIsRefused() {
    SFEntity entity = entityAtLevel(0L);
    order(OWN_CLIENT, OWN_ORG);
    attachment(TABLE_ID, "another-order");
    NeoAttachmentTarget target = NeoAttachmentTarget.resolve(spec, entity, RECORD, true);

    NeoAttachmentTarget.Refused foreign = refusalOf(() -> target.requireOwnAttachment(ATTACHMENT));
    NeoAttachmentTarget.Refused unknown = refusalOf(() -> target.requireOwnAttachment("nope"));

    assertEquals(404, foreign.getStatus());
    assertEquals(unknown.getMessage(), foreign.getMessage());
  }

  @Test
  @DisplayName("an attachment of the same record id but ANOTHER table is a 404")
  void attachmentOfAnotherTableIsRefused() {
    SFEntity entity = entityAtLevel(0L);
    order(OWN_CLIENT, OWN_ORG);
    attachment("TABLE_C_INVOICE", RECORD);
    NeoAttachmentTarget target = NeoAttachmentTarget.resolve(spec, entity, RECORD, true);

    assertEquals(404, refusalOf(() -> target.requireOwnAttachment(ATTACHMENT)).getStatus());
    assertEquals(404, refusalOf(() -> target.requireOwnAttachment(null)).getStatus());
  }

  @Test
  @DisplayName("W1: writing needs the record's organization to be WRITABLE; the refusal is the same 404")
  void writeNeedsAWritableOrganization() {
    SFEntity entity = entityAtLevel(0L);
    order(OWN_CLIENT, READ_ONLY_ORG);

    NeoAttachmentTarget.Refused refused = refusalOf(
        () -> NeoAttachmentTarget.resolve(spec, entity, RECORD, true));

    assertEquals(404, refused.getStatus());
    assertEquals(NeoAttachmentTarget.MSG_RECORD_NOT_FOUND, refused.getMessage());
    assertNotNull(NeoAttachmentTarget.resolve(spec, entity, RECORD, false),
        "a readable organization still lists");
  }

  @Test
  @DisplayName("a flagged document type is refused 405 BEFORE any record lookup, for reading and writing")
  void flaggedEntityIsRefusedBeforeTheRecordLookup() {
    SFEntity entity = entityAtLevel(0L);
    when(entity.getMCPConfig())
        .thenReturn("{\"attachments\":{\"enabled\":false,\"reason\":\"no tab in the app\"}}");
    order(OWN_CLIENT, OWN_ORG);

    for (boolean write : new boolean[] {false, true}) {
      NeoAttachmentTarget.Refused refused = refusalOf(
          () -> NeoAttachmentTarget.resolve(spec, entity, RECORD, write));

      assertEquals(405, refused.getStatus());
      assertTrue(refused.getMessage().startsWith("Attachments are not available for this document type"));
    }
    verify(dal, never()).get(anyString(), anyString());
  }

  @Test
  @DisplayName("an unflagged document type still resolves")
  void unflaggedEntityResolves() {
    SFEntity entity = entityAtLevel(0L);
    when(entity.getMCPConfig()).thenReturn("{\"verbs\":{\"create\":false,\"reason\":\"x\"}}");
    order(OWN_CLIENT, OWN_ORG);

    assertNotNull(NeoAttachmentTarget.resolve(spec, entity, RECORD, true));
  }
}
