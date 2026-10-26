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
package com.etendoerp.go.hubspot;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Savepoint;

import org.codehaus.jettison.json.JSONObject;
import org.hibernate.Session;
import org.hibernate.jdbc.Work;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.openbravo.dal.service.OBDal;

import com.etendoerp.go.schemaforge.data.Account;

/**
 * Reporting an account or billing event: written in the caller's transaction, and never able
 * to fail it.
 *
 * @covers com.etendoerp.go.hubspot.HubSpotOutbox
 */
class HubSpotOutboxTest {

  @Test
  void anAccountTheSignupApiCreatedIsNotReported() {
    Account account = mock(Account.class);
    when(account.getSignupChannel()).thenReturn("agent");

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      HubSpotOutbox.accountCreated(account);

      obDal.verifyNoInteractions();
    }
  }

  @Test
  void aFailedWriteIsRolledBackToItsSavepointAndSwallowed() throws Exception {
    Connection connection = mock(Connection.class);
    Savepoint savepoint = mock(Savepoint.class);
    when(connection.setSavepoint()).thenReturn(savepoint);
    PreparedStatement statement = mock(PreparedStatement.class);
    when(connection.prepareStatement(HubSpotOutbox.ENQUEUE_SQL)).thenReturn(statement);
    when(statement.executeUpdate()).thenThrow(new SQLException("relation does not exist"));
    Session session = mock(Session.class);
    doAnswer(invocation -> {
      invocation.<Work>getArgument(0).execute(connection);
      return null;
    }).when(session).doWork(any());
    OBDal dal = mock(OBDal.class);
    when(dal.getSession()).thenReturn(session);
    Account account = mock(Account.class);
    when(account.getId()).thenReturn("A1");

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      obDal.when(OBDal::getInstance).thenReturn(dal);

      assertDoesNotThrow(() -> HubSpotOutbox.accountCreated(account));
    }
    verify(session).flush();
    verify(connection).rollback(savepoint);
  }

  @Test
  void noSessionAtAllDoesNotFailTheCaller() {
    OBDal dal = mock(OBDal.class);
    Session session = mock(Session.class);
    when(dal.getSession()).thenReturn(session);
    doThrow(new IllegalStateException("closed")).when(session).flush();

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      obDal.when(OBDal::getInstance).thenReturn(dal);

      assertDoesNotThrow(() -> HubSpotOutbox.demoReady("ana@empresa.es", "C1", "Empresa SL",
          "ES"));
    }
  }

  @Test
  void aDemoEventCarriesTheCompanyAndItsCountry() throws Exception {
    JSONObject payload = new JSONObject(HubSpotOutbox.demoPayload(" Empresa SL ", null));

    assertEquals("Empresa SL", payload.getString("companyName"));
    assertEquals("", payload.getString("country"));
  }

  @Test
  void aStripeEventHubSpotDoesNotHearAboutTouchesNothing() throws Exception {
    JSONObject event = new JSONObject().put("data", new JSONObject()
        .put("object", new JSONObject().put("id", "cs_1")));

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      HubSpotOutbox.stripeEvent("checkout.session.completed", event);
      HubSpotOutbox.stripeEvent("invoice.paid", null);

      obDal.verifyNoInteractions();
    }
  }

  @Test
  void aStripeEventThatCannotBeRecordedDoesNotFailTheWebhook() throws Exception {
    OBDal dal = mock(OBDal.class);
    when(dal.getSession()).thenThrow(new IllegalStateException("no session"));
    JSONObject event = new JSONObject().put("id", "evt_1").put("data", new JSONObject()
        .put("object", new JSONObject().put("id", "in_1").put("subscription", "sub_1")));

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      obDal.when(OBDal::getInstance).thenReturn(dal);

      assertDoesNotThrow(() -> HubSpotOutbox.stripeEvent("invoice.paid", event));
    }
  }
}
