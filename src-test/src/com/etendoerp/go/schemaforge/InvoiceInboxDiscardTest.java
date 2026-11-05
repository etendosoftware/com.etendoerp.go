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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Set;

import org.hibernate.Session;
import org.hibernate.query.NativeQuery;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.erpCommon.utility.OBMessageUtils;
import org.openbravo.model.ad.access.User;

/**
 * The races and the tenant scope of {@link InvoiceInboxDiscard}, with a scripted session: what the
 * read sees, how many rows the conditional {@code UPDATE} moves, and what the answer is then.
 * The real SQL is covered by {@code InvoiceInboxDiscardIntegrationTest}.
 *
 * @covers com.etendoerp.go.schemaforge.InvoiceInboxDiscard
 */
class InvoiceInboxDiscardTest {

  private static final String ORG = "ORG-ID-000000000000000000000001";

  private MockedStatic<OBDal> dal;
  private MockedStatic<OBContext> obContext;
  private MockedStatic<OBMessageUtils> messages;
  private OBDal obDal;
  private NativeQuery<?> updateQuery;
  private NativeQuery<?> selectQuery;
  /** What successive {@code SELECT status, ad_org_id} reads answer. */
  private final Deque<Object[]> reads = new ArrayDeque<>();

  @BeforeEach
  @SuppressWarnings({ "rawtypes", "unchecked" })
  void open() {
    // All mocks are built before any static stubbing starts.
    Session session = mock(Session.class);
    obDal = mock(OBDal.class);
    when(obDal.getSession()).thenReturn(session);
    selectQuery = mock(NativeQuery.class, RETURNS_SELF);
    updateQuery = mock(NativeQuery.class, RETURNS_SELF);
    User user = mock(User.class);
    when(user.getId()).thenReturn("USER-1");
    OBContext context = mock(OBContext.class);
    when(context.getUser()).thenReturn(user);
    when(context.getReadableClients()).thenReturn(new String[] { "CLIENT-1" });
    when(context.getWritableOrganizations()).thenReturn(Set.of(ORG));
    when(session.createNativeQuery(anyString())).thenAnswer(invocation -> {
      String sql = invocation.getArgument(0);
      return sql.startsWith("UPDATE") ? updateQuery : selectQuery;
    });
    doAnswer(invocation -> reads.poll()).when(selectQuery).uniqueResult();
    dal = mockStatic(OBDal.class);
    dal.when(OBDal::getInstance).thenReturn(obDal);
    obContext = mockStatic(OBContext.class);
    obContext.when(OBContext::getOBContext).thenReturn(context);
    messages = mockStatic(OBMessageUtils.class);
  }

  @AfterEach
  void close() {
    messages.close();
    obContext.close();
    dal.close();
  }

  private void rowRead(String status) {
    reads.add(new Object[] { status, ORG });
  }

  @Test
  void aLostRaceToConfirmAnswers409WithTheNewStatusAndOverwritesNothing() throws Exception {
    rowRead("PENDING_REVIEW");   // the read before the update
    when(updateQuery.executeUpdate()).thenReturn(0);   // the row moved to CONFIRMED meanwhile
    rowRead("CONFIRMED");        // the read that judges the loss

    NeoResponse response = InvoiceInboxDiscard.discard("ID-1");

    assertEquals(409, response.getHttpStatus());
    assertEquals("not_discardable", response.getBody().getJSONObject("error").getString("code"));
    assertTrue(response.getBody().getJSONObject("error").getString("message").contains("CONFIRMED"));
    verify(updateQuery, times(1)).executeUpdate();
    verify(obDal, never()).flush();
  }

  @Test
  void aRowDiscardedInBetweenIsAnIdempotentSuccess() throws Exception {
    rowRead("READY");
    when(updateQuery.executeUpdate()).thenReturn(0);
    rowRead("DISCARDED");
    // doAnswer: `when(mock.call())` would invoke the installed answer and pop a queued read.
    doAnswer(invocation -> {
      Object[] next = reads.poll();
      return next != null ? next : "2026-01-01 10:00:00";   // the discarded_at read
    }).when(selectQuery).uniqueResult();

    NeoResponse response = InvoiceInboxDiscard.discard("ID-1");

    assertEquals(200, response.getHttpStatus());
    assertTrue(response.getBody().getBoolean("alreadyDiscarded"));
  }

  @Test
  void aRowDeletedInBetweenIs404() throws Exception {
    rowRead("READY");
    when(updateQuery.executeUpdate()).thenReturn(0);
    // the second read finds nothing

    NeoResponse response = InvoiceInboxDiscard.discard("ID-1");

    assertEquals(404, response.getHttpStatus());
  }

  @Test
  void theReadAndTheUpdateAreScopedToTheReadableClientsAndNothingRunsForAForeignRow()
      throws Exception {
    // A row of another client is invisible to the tenant-scoped read: it reads as null.
    NeoResponse response = InvoiceInboxDiscard.discard("FOREIGN-ID");

    assertEquals(404, response.getHttpStatus());
    verify(selectQuery).setParameterList("clients", new String[] { "CLIENT-1" });
    verify(updateQuery, never()).executeUpdate();
  }

  @Test
  void theUpdateCarriesTheStatusGuardTheTenantAndTheUser() {
    rowRead("ERROR");
    when(updateQuery.executeUpdate()).thenReturn(1);
    doAnswer(invocation -> {
      Object[] next = reads.poll();
      return next != null ? next : "2026-01-01 10:00:00";
    }).when(selectQuery).uniqueResult();

    NeoResponse response = InvoiceInboxDiscard.discard("ID-1");

    assertEquals(200, response.getHttpStatus());
    verify(updateQuery).setParameterList("allowed", InvoiceInboxDiscard.DISCARDABLE);
    verify(updateQuery).setParameterList("clients", new String[] { "CLIENT-1" });
    verify(updateQuery).setParameter("user", "USER-1");
    verify(obDal).flush();
  }
}
