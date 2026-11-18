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
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.anyString;
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
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.access.User;

/**
 * The failed confirmation is recorded in a new transaction by a conditional UPDATE that only moves a
 * row that can still be confirmed, with the reason capped to its column; it never throws and a
 * failure rolls back.
 *
 * @covers com.etendoerp.go.schemaforge.InvoiceInboxConfirmFailure
 */
class InvoiceInboxConfirmFailureTest {

  private MockedStatic<OBDal> dalStatic;
  private MockedStatic<OBContext> contextStatic;
  private OBDal dal;
  private NativeQuery<?> query;

  @BeforeEach
  @SuppressWarnings({ "unchecked", "rawtypes" })
  void setUp() {
    dal = mock(OBDal.class);
    Session session = mock(Session.class);
    query = mock(NativeQuery.class);
    when(dal.getSession()).thenReturn(session);
    when(session.createNativeQuery(anyString())).thenReturn((NativeQuery) query);
    when(query.setParameter(anyString(), org.mockito.ArgumentMatchers.any()))
        .thenReturn((NativeQuery) query);
    when(query.setParameterList(anyString(), org.mockito.ArgumentMatchers.<Object[]>any()))
        .thenReturn((NativeQuery) query);
    dalStatic = mockStatic(OBDal.class);
    dalStatic.when(OBDal::getInstance).thenReturn(dal);
    contextStatic = mockStatic(OBContext.class);
    OBContext context = mock(OBContext.class);
    User user = mock(User.class);
    when(user.getId()).thenReturn("USER-1");
    when(context.getUser()).thenReturn(user);
    when(context.getReadableClients()).thenReturn(new String[] { "C1", "0" });
    contextStatic.when(OBContext::getOBContext).thenReturn(context);
  }

  @AfterEach
  void tearDown() {
    contextStatic.close();
    dalStatic.close();
  }

  @Test
  @DisplayName("the row becomes ERROR / CONFIRM_FAILED with the reason, scoped to the readable clients, and commits")
  void recordsTheFailureAndCommits() {
    InvoiceInboxFailureDetail.Detail reason = InvoiceInboxFailureDetail.refused(
        "The invoice could not be created: x");
    InvoiceInboxConfirmFailure.recordFailure("ROW-1", reason);

    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    verify(dal.getSession()).createNativeQuery(sql.capture());
    assertEquals(true, sql.getValue().contains("status = 'ERROR'"));
    assertEquals(true, sql.getValue().contains("error_code = 'CONFIRM_FAILED'"));
    assertEquals(true, sql.getValue().contains("status IN ('PENDING_REVIEW', 'READY', 'ERROR')"));
    verify(query).setParameter("detail", reason.stored());
    assertEquals("{\"v\":1,\"code\":\"refused\",\"params\":{\"message\":"
        + "\"The invoice could not be created: x\"},\"text\":\"The invoice could not be created: x\"}",
        reason.stored());
    verify(query).setParameter("user", "USER-1");
    verify(query).setParameter("id", "ROW-1");
    verify(query).setParameterList(eq("clients"), eq(new String[] { "C1", "0" }));
    verify(query).executeUpdate();
    verify(dal).commitAndClose();
    verify(dal, never()).rollbackAndClose();
  }

  @Test
  @DisplayName("a reason longer than the 2000-character column is shortened in its text and params, never cut as JSON; null is an empty message")
  void theReasonIsCappedWithoutBreakingTheJson() throws Exception {
    InvoiceInboxConfirmFailure.recordFailure("ROW-1",
        InvoiceInboxFailureDetail.refused("x".repeat(5000)));
    InvoiceInboxConfirmFailure.recordFailure("ROW-2", InvoiceInboxFailureDetail.refused(null));

    ArgumentCaptor<Object> detail = ArgumentCaptor.forClass(Object.class);
    verify(query, org.mockito.Mockito.atLeastOnce()).setParameter(eq("detail"), detail.capture());
    String first = (String) detail.getAllValues().get(0);
    assertEquals(true, first.length() <= 2000, "length " + first.length());
    org.codehaus.jettison.json.JSONObject parsed = new org.codehaus.jettison.json.JSONObject(first);
    assertEquals("refused", parsed.getString("code"));
    assertEquals(true, parsed.getString("text").endsWith("..."));
    assertEquals(true, parsed.getJSONObject("params").getString("message").endsWith("..."));
    String second = (String) detail.getAllValues().get(1);
    assertEquals("", new org.codehaus.jettison.json.JSONObject(second).getString("text"));
  }

  @Test
  @DisplayName("a failure recording the failure never throws and rolls back")
  void itNeverThrows() {
    when(query.executeUpdate()).thenThrow(new IllegalStateException("db down"));

    assertDoesNotThrow(() -> InvoiceInboxConfirmFailure.recordFailure("ROW-1",
        InvoiceInboxFailureDetail.refused("reason")));

    verify(dal).rollbackAndClose();
    verify(dal, never()).commitAndClose();
  }
}
