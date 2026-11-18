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

package com.etendoerp.go.schemaforge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Date;

import org.codehaus.jettison.json.JSONObject;
import org.hibernate.Session;
import org.hibernate.query.NativeQuery;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.mockito.MockedStatic;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.erpCommon.utility.OBMessageUtils;

import com.etendoerp.go.schemaforge.data.IncomingDocument;
import com.etendoerp.go.schemaforge.util.NeoAccessHelper;
import com.etendoerp.go.schemaforge.util.NeoDateFormat;
import com.etendoerp.go.schemaforge.util.NeoRecordVersion;

/**
 * The guards the {@code apply} and {@code mark-reviewed} actions share (ETP-6050): window access,
 * tenant, organization, {@code processing}, {@code not_editable}, the row lock with a second
 * judgement of the state, and the optimistic token (exact string, millisecond comparison, a
 * malformed token fails closed). The session and the lookups are mocked; the real concurrency
 * against a discard is covered by the integration tests.
 *
 * @covers com.etendoerp.go.schemaforge.InvoiceInboxEditGate
 */
class InvoiceInboxEditGateTest {

  private static final Date UPDATED = new Date(1_700_000_000_123L);

  private MockedStatic<OBDal> dalStatic;
  private MockedStatic<OBContext> contextStatic;
  private MockedStatic<OBMessageUtils> messages;
  private MockedStatic<NeoAccessHelper> access;
  private MockedStatic<NeoRecordVersion> version;
  private MockedStatic<InvoiceInboxUploadEndpoint> upload;
  private OBDal dal;
  private Session session;
  private IncomingDocument loaded;
  private Object[] unlockedRow;
  private Object[] lockedRow;
  private NativeQuery<?> unlockedQuery;
  private NativeQuery<?> lockedQuery;

  @BeforeEach
  void setUp() {
    dal = mock(OBDal.class);
    session = mock(Session.class);
    when(dal.getSession()).thenReturn(session);
    loaded = mock(IncomingDocument.class);
    when(loaded.getUpdated()).thenReturn(UPDATED);
    when(loaded.getId()).thenReturn("R1");
    when(dal.get(IncomingDocument.class, "R1")).thenReturn(loaded);
    dalStatic = mockStatic(OBDal.class);
    dalStatic.when(OBDal::getInstance).thenReturn(dal);
    OBContext obContext = mock(OBContext.class);
    when(obContext.getReadableClients()).thenReturn(new String[] { "C1" });
    contextStatic = mockStatic(OBContext.class);
    contextStatic.when(OBContext::getOBContext).thenReturn(obContext);
    messages = mockStatic(OBMessageUtils.class);
    access = mockStatic(NeoAccessHelper.class);
    access.when(() -> NeoAccessHelper.hasWindowAccess(anyString(), eq("POST"))).thenReturn(true);
    version = mockStatic(NeoRecordVersion.class);
    upload = mockStatic(InvoiceInboxUploadEndpoint.class, Mockito.CALLS_REAL_METHODS);
    upload.when(() -> InvoiceInboxUploadEndpoint.isWritableOrganization(any(), anyString()))
        .thenReturn(true);
    unlockedRow = new Object[] { "PENDING_REVIEW", "ORG1", null };
    lockedRow = unlockedRow;
    unlockedQuery = query(() -> unlockedRow);
    lockedQuery = query(() -> lockedRow);
    when(session.createNativeQuery(anyString())).thenAnswer(inv ->
        ((String) inv.getArgument(0)).endsWith("FOR UPDATE") ? lockedQuery : unlockedQuery);
  }

  @AfterEach
  void tearDown() {
    upload.close();
    version.close();
    access.close();
    messages.close();
    contextStatic.close();
    dalStatic.close();
  }

  @SuppressWarnings({ "rawtypes", "unchecked" })
  private static NativeQuery<?> query(java.util.function.Supplier<Object[]> row) {
    NativeQuery query = mock(NativeQuery.class, Mockito.RETURNS_SELF);
    when(query.uniqueResult()).thenAnswer(inv -> row.get());
    return query;
  }

  private static String currentToken() {
    return NeoDateFormat.toAuditToken(UPDATED);
  }

  private static void assertRefusal(InvoiceInboxEditGate.Opened opened, int status, String code)
      throws Exception {
    assertNull(opened.row());
    NeoResponse refusal = opened.refusal();
    assertNotNull(refusal);
    assertEquals(status, refusal.getHttpStatus());
    assertEquals(code, refusal.getBody().getJSONObject("error").getString("code"));
  }

  @Test
  void aRowWithTheCurrentTokenIsOpenedLockedAndRefreshed() {
    InvoiceInboxEditGate.Opened opened = InvoiceInboxEditGate.open("R1", currentToken());

    assertNull(opened.refusal());
    assertSame(loaded, opened.row());
    verify(dal).refresh(loaded);
    version.verifyNoInteractions();
  }

  @Test
  void theRowIsReadFilteredByTheReadableClientsThenLockedForUpdate() {
    InvoiceInboxEditGate.open("R1", currentToken());

    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    verify(session, times(2)).createNativeQuery(sql.capture());
    assertFalse(sql.getAllValues().get(0).contains("FOR UPDATE"));
    assertTrue(sql.getAllValues().get(1).endsWith(" FOR UPDATE"));
    assertTrue(sql.getAllValues().get(0).contains("ad_client_id IN (:clients)"));
    verify(lockedQuery).setParameterList("clients", new String[] { "C1" });
    verify(lockedQuery).setParameter("id", "R1");
  }

  @Test
  void aUserWithoutWriteAccessToTheWindowIsRefusedBeforeAnyRead() throws Exception {
    access.when(() -> NeoAccessHelper.hasWindowAccess(anyString(), eq("POST"))).thenReturn(false);

    assertRefusal(InvoiceInboxEditGate.open("R1", currentToken()), 403, "access_denied");
    verify(session, never()).createNativeQuery(anyString());
  }

  @Test
  void aRowOfAnotherTenantOrMissingIsNotFound() throws Exception {
    unlockedRow = null;

    assertRefusal(InvoiceInboxEditGate.open("R1", currentToken()), 404, "not_found");
    verify(session, times(1)).createNativeQuery(anyString());
  }

  @Test
  void anOrganizationTheRoleCannotWriteIsRefused() throws Exception {
    upload.when(() -> InvoiceInboxUploadEndpoint.isWritableOrganization(any(), eq("ORG1")))
        .thenReturn(false);

    assertRefusal(InvoiceInboxEditGate.open("R1", currentToken()), 403, "org_not_allowed");
  }

  @Test
  void aProcessingRowIsRefusedBeforeTheLock() throws Exception {
    unlockedRow = new Object[] { "PROCESSING", "ORG1", null };

    assertRefusal(InvoiceInboxEditGate.open("R1", currentToken()), 409, "processing");
    verify(session, times(1)).createNativeQuery(anyString());
  }

  @Test
  void aNotEditableStatusIsRefusedWithItsStatusInTheError() throws Exception {
    for (String status : new String[] { "CONFIRMED", "DISCARDED" }) {
      unlockedRow = new Object[] { status, "ORG1", null };
      InvoiceInboxEditGate.Opened opened = InvoiceInboxEditGate.open("R1", currentToken());

      assertRefusal(opened, 409, "not_editable");
      assertEquals(status,
          opened.refusal().getBody().getJSONObject("error").getString("documentStatus"));
    }
  }

  @Test
  void aForeignReceiverRowIsNotEditable() throws Exception {
    unlockedRow = new Object[] { "ERROR", "ORG1", "FOREIGN_RECEIVER" };

    assertRefusal(InvoiceInboxEditGate.open("R1", currentToken()), 409, "not_editable");
  }

  @Test
  void anErrorRowWithoutACodeIsEditable() {
    unlockedRow = new Object[] { "ERROR", "ORG1", null };
    lockedRow = unlockedRow;

    assertNull(InvoiceInboxEditGate.open("R1", currentToken()).refusal());
  }

  @Test
  void everyEditableStatusIsOpened() {
    for (String[] state : new String[][] { { "PENDING_REVIEW", null }, { "READY", null },
        { "ERROR", "UNREADABLE" }, { "ERROR", "MISSING_FIELDS" } }) {
      unlockedRow = new Object[] { state[0], "ORG1", state[1] };
      lockedRow = unlockedRow;

      assertNull(InvoiceInboxEditGate.open("R1", currentToken()).refusal(), state[0]);
    }
  }

  @Test
  void aDiscardThatWinsTheLockIsJudgedAgainAndRefusesTheEdit() throws Exception {
    lockedRow = new Object[] { "DISCARDED", "ORG1", null };

    InvoiceInboxEditGate.Opened opened = InvoiceInboxEditGate.open("R1", currentToken());

    assertRefusal(opened, 409, "not_editable");
    assertEquals("DISCARDED",
        opened.refusal().getBody().getJSONObject("error").getString("documentStatus"));
    verify(dal, never()).get(eq(IncomingDocument.class), anyString());
  }

  @Test
  void aRowDeletedBeforeTheLockIsNotFound() throws Exception {
    lockedRow = null;

    assertRefusal(InvoiceInboxEditGate.open("R1", currentToken()), 404, "not_found");
  }

  @Test
  void aDifferentTokenOfTheSameShapeIsAskedToTheRecordVersion() throws Exception {
    version.when(() -> NeoRecordVersion.isStale(eq(IncomingDocument.ENTITY_NAME), eq("R1"),
        anyString())).thenReturn(true);

    assertRefusal(InvoiceInboxEditGate.open("R1", "2020-01-01T00:00:00.123+00:00"), 409,
        "stale_document");
    version.verify(() -> NeoRecordVersion.isStale(IncomingDocument.ENTITY_NAME, "R1",
        "2020-01-01T00:00:00.123+00:00"));
  }

  @Test
  void theSameInstantInAnotherSpellingIsNotStaleWhenTheVersionAgrees() {
    version.when(() -> NeoRecordVersion.isStale(eq(IncomingDocument.ENTITY_NAME), eq("R1"),
        anyString())).thenReturn(false);

    InvoiceInboxEditGate.Opened opened = InvoiceInboxEditGate.open("R1",
        "2023-11-14T22:13:20.123Z");

    assertNull(opened.refusal());
    version.verify(() -> NeoRecordVersion.isStale(IncomingDocument.ENTITY_NAME, "R1",
        "2023-11-14T22:13:20.123Z"));
  }

  @Test
  void aMalformedTokenFailsClosedWithoutAskingTheVersion() throws Exception {
    for (String token : new String[] { "yesterday", "null", "123", "2023-11-14", "x" }) {
      assertRefusal(InvoiceInboxEditGate.open("R1", token), 409, "stale_document");
    }
    version.verifyNoInteractions();
  }

  @Test
  void aStaleDocumentRefusalDoesNotTouchTheRow() throws Exception {
    InvoiceInboxEditGate.open("R1", "garbage");

    verify(loaded, never()).setStatus(anyString());
  }

  @Test
  void refuseBuildsTheEnvelopeWithTheDetailsMergedIntoTheError() throws Exception {
    NeoResponse response = InvoiceInboxEditGate.refuse(409, "not_editable",
        InvoiceInboxMessages.NOT_EDITABLE,
        InvoiceInboxEditGate.details("documentStatus", "CONFIRMED"), "status", "CONFIRMED");

    JSONObject error = response.getBody().getJSONObject("error");
    assertEquals(409, response.getHttpStatus());
    assertEquals("not_editable", error.getString("code"));
    assertEquals("CONFIRMED", error.getString("documentStatus"));
    assertTrue(error.getString("message").contains("CONFIRMED"));
  }
}
