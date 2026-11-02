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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.openbravo.dal.core.OBContext;
import org.openbravo.model.ad.access.Role;
import org.openbravo.model.ad.access.User;
import org.openbravo.model.ad.datamodel.Column;
import org.openbravo.model.ad.datamodel.Table;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.ad.system.Language;
import org.openbravo.model.ad.ui.Tab;
import org.openbravo.model.common.enterprise.Organization;

import com.etendoerp.go.schemaforge.data.SFEntity;
import com.etendoerp.go.schemaforge.data.SFSpec;
import com.etendoerp.go.schemaforge.util.NeoButtonActionHelper;

/**
 * ETP-6013 — the server-side render's executor: which MCP document actions queue a render, the
 * context it runs in, and the per-document serialization (a render in flight is joined, never
 * duplicated). The worker's own run (lock wait, render, store) needs a database and is verified live;
 * this pins what happens on the caller's thread, which must never throw.
 *
 * @covers com.etendoerp.go.schemaforge.NeoPrintAfterComplete
 */
@DisplayName("ETP-6013 — NeoPrintAfterComplete")
class NeoPrintAfterCompleteTest {

  private static final String RECORD = "BF1D5905BBF344B1BC7CB8FEB0DF9CB4";
  private static final String PRINTED = "{\"pdf\":{\"source\":\"rendered\","
      + "\"print\":{\"document\":\"sales-invoice\"},\"reason\":\"server-side render\"}}";

  private SFEntity entity;
  private OBContext context;

  @BeforeEach
  void setUp() {
    System.setProperty(NeoJsreportClient.URL_PROPERTY, "http://localhost:5488");
    System.setProperty(NeoPrintableTemplates.URL_PROPERTY, "http://localhost:3100/printables");
    SFSpec spec = mock(SFSpec.class);
    when(spec.getName()).thenReturn("sales-invoice");
    Table table = mock(Table.class);
    when(table.getDBTableName()).thenReturn("C_Invoice");
    Tab tab = mock(Tab.class);
    when(tab.getTable()).thenReturn(table);
    entity = mock(SFEntity.class);
    when(entity.getId()).thenReturn("ENTITY1");
    when(entity.getMCPConfig()).thenReturn(PRINTED);
    when(entity.getETGOSFSpec()).thenReturn(spec);
    when(entity.getADTab()).thenReturn(tab);
    User user = mock(User.class);
    when(user.getId()).thenReturn("USER1");
    Role role = mock(Role.class);
    when(role.getId()).thenReturn("ROLE1");
    Client client = mock(Client.class);
    when(client.getId()).thenReturn("CLIENT1");
    Organization org = mock(Organization.class);
    when(org.getId()).thenReturn("ORG1");
    Language language = mock(Language.class);
    when(language.getLanguage()).thenReturn("es_ES");
    context = mock(OBContext.class);
    when(context.getUser()).thenReturn(user);
    when(context.getRole()).thenReturn(role);
    when(context.getCurrentClient()).thenReturn(client);
    when(context.getCurrentOrganization()).thenReturn(org);
    when(context.getLanguage()).thenReturn(language);
  }

  @AfterEach
  void tearDown() {
    System.clearProperty(NeoJsreportClient.URL_PROPERTY);
    System.clearProperty(NeoPrintableTemplates.URL_PROPERTY);
    NeoPrintAfterComplete.IN_FLIGHT.clear();
  }

  @Test
  @DisplayName("a job carries the caller's context and the document's serialization key")
  void jobCarriesTheCaller() {
    try (MockedStatic<OBContext> contextMock = mockStatic(OBContext.class)) {
      contextMock.when(OBContext::getOBContext).thenReturn(context);

      NeoPrintAfterComplete.Job job = NeoPrintAfterComplete.jobFor(entity, RECORD, null);

      assertNotNull(job);
      assertEquals("sales-invoice", job.specName);
      assertEquals("ENTITY1", job.entityId);
      assertEquals("C_Invoice/" + RECORD, job.key());
      assertEquals("USER1", job.userId);
      assertEquals("ROLE1", job.roleId);
      assertEquals("CLIENT1", job.clientId);
      assertEquals("ORG1", job.orgId);
      assertEquals("es_ES", job.language, "no language passed: the context's");
      assertEquals("en_US", NeoPrintAfterComplete.jobFor(entity, RECORD, "en_US").language,
          "the language the MCP passes wins over the context's");
    }
  }

  @Test
  @DisplayName("no job: blank id, no entity, no pdf.print, or no user/role (never an admin fallback)")
  void nothingToQueue() {
    try (MockedStatic<OBContext> contextMock = mockStatic(OBContext.class)) {
      contextMock.when(OBContext::getOBContext).thenReturn(context);

      assertNull(NeoPrintAfterComplete.jobFor(entity, " ", null));
      assertNull(NeoPrintAfterComplete.jobFor(null, RECORD, null));
      assertNull(NeoPrintAfterComplete.jobFor(mock(SFEntity.class), RECORD, null));
      when(context.getRole()).thenReturn(null);
      assertNull(NeoPrintAfterComplete.jobFor(entity, RECORD, null));
    }
  }

  @Test
  @DisplayName("only the DocAction button counts: by column, by field, or a name that resolves to it")
  void onlyTheDocumentActionButton() {
    Column docAction = mock(Column.class);
    when(docAction.getDBColumnName()).thenReturn("DocAction");
    Column posted = mock(Column.class);
    when(posted.getDBColumnName()).thenReturn("Posted");
    try (MockedStatic<NeoButtonActionHelper> buttons = mockStatic(NeoButtonActionHelper.class)) {
      buttons.when(() -> NeoButtonActionHelper.findButtonColumn("ENTITY1", "docaction"))
          .thenReturn(docAction);
      buttons.when(() -> NeoButtonActionHelper.findButtonColumn("ENTITY1", "posted"))
          .thenReturn(posted);

      assertTrue(NeoPrintAfterComplete.isDocActionButton(entity, "DocAction"));
      assertTrue(NeoPrintAfterComplete.isDocActionButton(entity, "documentAction"));
      assertTrue(NeoPrintAfterComplete.isDocActionButton(entity, "docaction"));
      assertFalse(NeoPrintAfterComplete.isDocActionButton(entity, "posted"));
      assertFalse(NeoPrintAfterComplete.isDocActionButton(entity, "unknown"));
      assertFalse(NeoPrintAfterComplete.isDocActionButton(entity, " "));
    }
  }

  @Test
  @DisplayName("a render already in flight for the document is joined, never queued twice")
  void inFlightRenderIsJoined() {
    CompletableFuture<NeoPrintableRenderer.Outcome> running = new CompletableFuture<>();
    NeoPrintAfterComplete.IN_FLIGHT.put("C_Invoice/" + RECORD, running);
    NeoPrintAfterComplete.Job job = new NeoPrintAfterComplete.Job("sales-invoice", "ENTITY1",
        "C_Invoice", RECORD,
        new NeoPrintAfterComplete.Caller("USER1", "ROLE1", "CLIENT1", "ORG1", "es_ES"));

    assertSame(running, NeoPrintAfterComplete.submit(job));
  }

  @Test
  @DisplayName("renderAndWait joins the document's render in flight and answers its outcome")
  void renderAndWaitJoinsTheRenderInFlight() {
    NeoPrintAfterComplete.IN_FLIGHT.put("C_Invoice/" + RECORD,
        CompletableFuture.completedFuture(NeoPrintableRenderer.Outcome.STORED));
    try (MockedStatic<OBContext> contextMock = mockStatic(OBContext.class)) {
      contextMock.when(OBContext::getOBContext).thenReturn(context);

      assertEquals(NeoPrintableRenderer.Outcome.STORED,
          NeoPrintAfterComplete.renderAndWait(entity, RECORD, "es_ES"));
      assertNull(NeoPrintAfterComplete.renderAndWait(mock(SFEntity.class), RECORD, "es_ES"),
          "nothing to render: nothing queued, nothing waited for");
    }
    assertNull(NeoPrintAfterComplete.keyOf(mock(SFEntity.class), RECORD), "no table: no key");
    assertEquals("C_Invoice/" + RECORD, NeoPrintAfterComplete.keyOf(entity, RECORD));
  }

  @Test
  @DisplayName("afterAction never throws into the action, whatever the entity")
  void afterActionNeverThrows() {
    SFEntity broken = mock(SFEntity.class);
    when(broken.getMCPConfig()).thenThrow(new IllegalStateException("detached"));

    assertDoesNotThrow(
        () -> NeoPrintAfterComplete.afterAction(broken, RECORD, "DocAction", true, "es_ES"));
    assertDoesNotThrow(() -> NeoPrintAfterComplete.afterAction(null, null, null, false, null));
  }
  @Test
  @DisplayName("the advisory lock casts its void result, which Hibernate cannot map (live failure)")
  void lockSqlCastsTheVoidResult() {
    String sql = NeoPrintAfterComplete.LOCK_DOCUMENT_SQL;

    assertTrue(sql.matches("(?i)SELECT\\s+CAST\\(\\s*pg_advisory_xact_lock\\(.*\\)\\s+AS\\s+text\\s*\\)"),
        sql);
  }

  @Test
  @DisplayName("a render failure is logged as its class and its root cause's class, never a message")
  void causeChainNamesClassesOnly() {
    Exception wrapped = new IllegalStateException("record data 123",
        new RuntimeException("more", new java.sql.SQLException("SELECT secret")));

    assertEquals("IllegalStateException <- SQLException", NeoPrintAfterComplete.causeChain(wrapped));
    assertEquals("IllegalStateException",
        NeoPrintAfterComplete.causeChain(new IllegalStateException("x")));
  }

  @Test
  @DisplayName("inside an MCP call the render is held until the call's work is done; a failed call drops it")
  void rendersAreHeldUntilTheCallEnds() {
    try (MockedStatic<OBContext> contextMock = mockStatic(OBContext.class)) {
      contextMock.when(OBContext::getOBContext).thenReturn(context);

      NeoPrintAfterComplete.Deferral deferral = NeoPrintAfterComplete.deferUntilEnd();
      NeoPrintAfterComplete.afterAction(entity, RECORD, "DocAction", true, "es_ES");

      assertTrue(NeoPrintAfterComplete.IN_FLIGHT.isEmpty(),
          "nothing submitted while the call may still change the document");
      deferral.close();
      assertTrue(NeoPrintAfterComplete.IN_FLIGHT.isEmpty(), "never released: dropped, not run");
    }
  }

}
