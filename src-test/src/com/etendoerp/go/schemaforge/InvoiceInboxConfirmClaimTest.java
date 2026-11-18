/*
 *************************************************************************
 * The contents of this file are subject to the Etendo License
 * (the "License"), you may not use this file except in compliance
 * with the License.
 * You may obtain a copy of the License at
 * https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * Software distributed under the License is distributed on an
 * "AS IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
 * implied. See the License for the specific language governing rights
 * and limitations under the License.
 * All portions are Copyright (C) 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 *************************************************************************
 */

package com.etendoerp.go.schemaforge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.hibernate.Session;
import org.hibernate.query.NativeQuery;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.erpCommon.utility.OBMessageUtils;

import com.etendoerp.go.schemaforge.data.IncomingDocument;
import com.etendoerp.go.schemaforge.util.NeoAccessHelper;

/**
 * The guards and the claim of {@code confirm}, in order: the window access and the organization of
 * the user before anything else, the row locked and judged again, the states a document is
 * confirmed from, the optional token. A document already confirmed is not an error.
 *
 * @covers com.etendoerp.go.schemaforge.InvoiceInboxConfirmClaim
 */
class InvoiceInboxConfirmClaimTest {

  private MockedStatic<OBDal> dalStatic;
  private MockedStatic<OBContext> contextStatic;
  private MockedStatic<OBMessageUtils> messages;
  private MockedStatic<NeoAccessHelper> access;
  private MockedStatic<InvoiceInboxUploadEndpoint> upload;
  private MockedStatic<InvoiceInboxEditGate> gate;
  private OBDal dal;
  private Session session;
  private NativeQuery<?> plain;
  private NativeQuery<?> locked;
  private IncomingDocument loaded;

  @BeforeEach
  @SuppressWarnings({ "unchecked", "rawtypes" })
  void setUp() {
    dal = mock(OBDal.class);
    session = mock(Session.class);
    when(dal.getSession()).thenReturn(session);
    plain = query();
    locked = query();
    when(session.createNativeQuery(argThat((String sql) -> sql != null
        && !sql.endsWith(" FOR UPDATE")))).thenReturn((NativeQuery) plain);
    when(session.createNativeQuery(argThat((String sql) -> sql != null
        && sql.endsWith(" FOR UPDATE")))).thenReturn((NativeQuery) locked);
    loaded = mock(IncomingDocument.class);
    when(dal.get(IncomingDocument.class, "ROW-1")).thenReturn(loaded);
    dalStatic = mockStatic(OBDal.class);
    dalStatic.when(OBDal::getInstance).thenReturn(dal);
    contextStatic = mockStatic(OBContext.class);
    OBContext context = mock(OBContext.class);
    when(context.getReadableClients()).thenReturn(new String[] { "C1", "0" });
    contextStatic.when(OBContext::getOBContext).thenReturn(context);
    messages = mockStatic(OBMessageUtils.class);
    access = mockStatic(NeoAccessHelper.class);
    access.when(() -> NeoAccessHelper.hasWindowAccess(anyString(), anyString())).thenReturn(true);
    upload = mockStatic(InvoiceInboxUploadEndpoint.class, org.mockito.Mockito.CALLS_REAL_METHODS);
    upload.when(() -> InvoiceInboxUploadEndpoint.isWritableOrganization(any(), anyString()))
        .thenReturn(true);
    gate = mockStatic(InvoiceInboxEditGate.class, org.mockito.Mockito.CALLS_REAL_METHODS);
    gate.when(() -> InvoiceInboxEditGate.isStale(any(), anyString())).thenReturn(false);
  }

  @AfterEach
  void tearDown() {
    gate.close();
    upload.close();
    access.close();
    messages.close();
    contextStatic.close();
    dalStatic.close();
  }

  @SuppressWarnings({ "unchecked", "rawtypes" })
  private static NativeQuery<?> query() {
    NativeQuery<?> query = mock(NativeQuery.class);
    when(query.setParameter(anyString(), any())).thenReturn((NativeQuery) query);
    when(query.setParameterList(anyString(), org.mockito.ArgumentMatchers.<Object[]>any()))
        .thenReturn((NativeQuery) query);
    return query;
  }

  /** The row both reads answer: status, organization, error code. */
  @SuppressWarnings({ "unchecked", "rawtypes" })
  private void row(String status, String errorCode) {
    Object[] row = new Object[] { status, "ORG-1", errorCode };
    when(((NativeQuery) plain).uniqueResult()).thenReturn(row);
    when(((NativeQuery) locked).uniqueResult()).thenReturn(row);
  }

  private static void assertRefused(InvoiceInboxConfirmClaim.Opened opened, int status,
      String code) throws Exception {
    assertNull(opened.row());
    assertEquals(status, opened.response().getHttpStatus());
    assertEquals(code, opened.response().getBody().getJSONObject("error").getString("code"));
  }

  @Test
  @DisplayName("no window access is 403 access_denied before the row is read")
  void noWindowAccessIsRefusedFirst() throws Exception {
    access.when(() -> NeoAccessHelper.hasWindowAccess(anyString(), anyString())).thenReturn(false);

    assertRefused(InvoiceInboxConfirmClaim.open("ROW-1", ""), 403, "access_denied");

    verify(session, never()).createNativeQuery(anyString());
  }

  @Test
  @DisplayName("a row the user cannot read is 404, also when it vanishes between the two reads")
  void aMissingRowIsNotFound() throws Exception {
    assertRefused(InvoiceInboxConfirmClaim.open("ROW-1", ""), 404, "not_found");

    @SuppressWarnings({ "unchecked", "rawtypes" })
    Object[] visible = new Object[] { "PENDING_REVIEW", "ORG-1", null };
    org.mockito.Mockito.when(((NativeQuery) plain).uniqueResult()).thenReturn(visible);
    assertRefused(InvoiceInboxConfirmClaim.open("ROW-1", ""), 404, "not_found");
  }

  @Test
  @DisplayName("an organization the user cannot write is 403 org_not_allowed, before the lock")
  void anOrganizationTheUserCannotWriteIsRefused() throws Exception {
    row("PENDING_REVIEW", null);
    upload.when(() -> InvoiceInboxUploadEndpoint.isWritableOrganization(any(), eq("ORG-1")))
        .thenReturn(false);

    assertRefused(InvoiceInboxConfirmClaim.open("ROW-1", ""), 403, "org_not_allowed");

    verify(locked, never()).uniqueResult();
  }

  @Test
  @DisplayName("the row is read for the readable clients, then locked FOR UPDATE and judged again")
  void theRowIsLockedAndJudgedAgain() {
    row("PENDING_REVIEW", null);

    InvoiceInboxConfirmClaim.Opened opened = InvoiceInboxConfirmClaim.open("ROW-1", "");

    verify(plain).setParameter("id", "ROW-1");
    verify(plain).setParameterList(eq("clients"), eq(new String[] { "C1", "0" }));
    verify(locked).uniqueResult();
    verify(dal).refresh(loaded);
    assertSame(loaded, opened.row());
    assertNull(opened.response());
    assertFalse(opened.alreadyConfirmed());
  }

  @Test
  @DisplayName("a confirm that won while this request waited for the lock is judged on the locked status")
  @SuppressWarnings({ "unchecked", "rawtypes" })
  void theLockedStatusDecides() throws Exception {
    when(((NativeQuery) plain).uniqueResult()).thenReturn(
        new Object[] { "PENDING_REVIEW", "ORG-1", null });
    when(((NativeQuery) locked).uniqueResult()).thenReturn(
        new Object[] { "DISCARDED", "ORG-1", null });

    InvoiceInboxConfirmClaim.Opened opened = InvoiceInboxConfirmClaim.open("ROW-1", "");

    assertRefused(opened, 409, "not_confirmable");
    assertEquals("DISCARDED", opened.response().getBody().getJSONObject("error")
        .getString("documentStatus"));
  }

  @Test
  @DisplayName("PENDING_REVIEW, READY and the retryable errors are confirmable; every other state is 409")
  void theConfirmableStates() throws Exception {
    for (String[] state : new String[][] { { "PENDING_REVIEW", null }, { "READY", null },
        { "ERROR", "CONFIRM_FAILED" }, { "ERROR", "MISSING_FIELDS" } }) {
      row(state[0], state[1]);
      InvoiceInboxConfirmClaim.Opened opened = InvoiceInboxConfirmClaim.open("ROW-1", "");
      assertNull(opened.response(), state[0] + "/" + state[1]);
      assertSame(loaded, opened.row());
    }
    for (String[] state : new String[][] { { "DISCARDED", null }, { "PROCESSING", null },
        { "ERROR", "UNREADABLE" }, { "ERROR", "SERVICE_FAILURE" }, { "ERROR", "FOREIGN_RECEIVER" },
        { "ERROR", "REFERENCE_TOO_LONG" } }) {
      row(state[0], state[1]);
      assertRefused(InvoiceInboxConfirmClaim.open("ROW-1", ""), 409, "not_confirmable");
    }
  }

  @Test
  @DisplayName("the confirmable table, on its own")
  void isConfirmable() {
    assertTrue(InvoiceInboxConfirmClaim.isConfirmable("PENDING_REVIEW", null));
    assertTrue(InvoiceInboxConfirmClaim.isConfirmable("READY", "ANYTHING"));
    assertTrue(InvoiceInboxConfirmClaim.isConfirmable("ERROR", "CONFIRM_FAILED"));
    assertTrue(InvoiceInboxConfirmClaim.isConfirmable("ERROR", "MISSING_FIELDS"));
    assertFalse(InvoiceInboxConfirmClaim.isConfirmable("ERROR", "UNREADABLE"));
    assertFalse(InvoiceInboxConfirmClaim.isConfirmable("CONFIRMED", null));
  }

  @Test
  @DisplayName("nullable columns: a null status is never confirmable, an ERROR row needs a known retryable code")
  void isConfirmableIsNullSafe() {
    assertFalse(InvoiceInboxConfirmClaim.isConfirmable(null, null));
    assertFalse(InvoiceInboxConfirmClaim.isConfirmable(null, "CONFIRM_FAILED"));
    assertFalse(InvoiceInboxConfirmClaim.isConfirmable("ERROR", null));
    assertTrue(InvoiceInboxConfirmClaim.isConfirmable("ERROR", "CONFIRM_FAILED"));
    assertTrue(InvoiceInboxConfirmClaim.isConfirmable("ERROR", "MISSING_FIELDS"));
    assertFalse(InvoiceInboxConfirmClaim.isConfirmable("ERROR", "FOREIGN_RECEIVER"));
    assertTrue(InvoiceInboxConfirmClaim.isConfirmable("PENDING_REVIEW", null));
    assertTrue(InvoiceInboxConfirmClaim.isConfirmable("READY", null));
  }

  @Test
  @DisplayName("an ERROR row with a null error code is 409 not_confirmable with its status")
  void anErrorRowWithoutACodeIsNotConfirmable() throws Exception {
    row("ERROR", null);

    InvoiceInboxConfirmClaim.Opened opened = InvoiceInboxConfirmClaim.open("ROW-1", "");

    assertRefused(opened, 409, "not_confirmable");
    assertEquals("ERROR", opened.response().getBody().getJSONObject("error")
        .getString("documentStatus"));
  }

  @Test
  @DisplayName("an already confirmed document opens as such, with its row, whatever the token")
  void anAlreadyConfirmedDocument() {
    row("CONFIRMED", null);

    InvoiceInboxConfirmClaim.Opened opened = InvoiceInboxConfirmClaim.open("ROW-1", "STALE");

    assertTrue(opened.alreadyConfirmed());
    assertSame(loaded, opened.row());
    assertNull(opened.response());
    gate.verify(() -> InvoiceInboxEditGate.isStale(any(), anyString()), never());
  }

  @Test
  @DisplayName("a token older than the stored row is 409 stale_document; no token is never stale")
  void theToken() throws Exception {
    row("PENDING_REVIEW", null);
    gate.when(() -> InvoiceInboxEditGate.isStale(loaded, "OLD")).thenReturn(true);

    assertRefused(InvoiceInboxConfirmClaim.open("ROW-1", "OLD"), 409, "stale_document");
    assertNull(InvoiceInboxConfirmClaim.open("ROW-1", "FRESH").response());
    assertNull(InvoiceInboxConfirmClaim.open("ROW-1", "").response());
    gate.verify(() -> InvoiceInboxEditGate.isStale(loaded, ""), never());
  }
}
