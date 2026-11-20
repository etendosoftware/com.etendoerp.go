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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
import java.time.Instant;
import java.util.List;

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
  void theRegisterCountryIsKeptAsAnUpperCaseIsoCodeAndAnythingElseIsDropped() {
    // ETP-6363: a marketing field, so an odd value is dropped rather than refusing the sign-up.
    assertEquals("AR", HubSpotOutbox.countryCode(" ar "));
    assertEquals("ES", HubSpotOutbox.countryCode("ES"));
    assertNull(HubSpotOutbox.countryCode("ZZ"));
    assertNull(HubSpotOutbox.countryCode("ESP"));
    assertNull(HubSpotOutbox.countryCode(""));
    assertNull(HubSpotOutbox.countryCode(null));
  }

  @Test
  void theLeadAttributionKeepsOnlyWhatMayReachHubSpot() throws Exception {
    // ETP-6363: the visitor cookie only when it is one, a web address, a bounded title.
    JSONObject kept = new JSONObject(HubSpotOutbox.signupContext(new JSONObject()
        .put("hutk", " 0123456789ABCDEF0123456789ABCDEF ")
        .put("pageUri", "https://app.etendo.ai/?utm_source=google")
        .put("pageName", "Etendo")
        .put("email", "ana@empresa.es")));

    assertEquals("0123456789abcdef0123456789abcdef", kept.getString("hutk"));
    assertEquals("https://app.etendo.ai/?utm_source=google", kept.getString("pageUri"));
    assertEquals("Etendo", kept.getString("pageName"));
    assertFalse(kept.has("email"));
  }

  @Test
  void aLeadAttributionWithNothingUsableIsNotKept() throws Exception {
    assertNull(HubSpotOutbox.signupContext(null));
    assertNull(HubSpotOutbox.signupContext(new JSONObject()));
    assertNull(HubSpotOutbox.signupContext(new JSONObject().put("hutk", "not-a-cookie")
        .put("pageUri", "javascript:alert(1)").put("pageName", "  ")));
    assertEquals(255, new JSONObject(HubSpotOutbox.signupContext(new JSONObject()
        .put("pageName", "x".repeat(400)))).getString("pageName").length());
  }

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
    assertFalse(payload.has(HubSpotOutbox.FIELD_DEMO_DEAL));
  }

  @Test
  void aBookedDemoCarriesItsDeal() throws Exception {
    JSONObject payload = new JSONObject(HubSpotOutbox.demoPayload("Empresa SL", "España", "ES",
        " 555 "));

    assertEquals("555", payload.getString(HubSpotOutbox.FIELD_DEMO_DEAL));
  }

  @Test
  void aBookedDealWithoutAccountOrIdRecordsNothing() {
    try (MockedStatic<OBDal> dal = mockStatic(OBDal.class)) {
      HubSpotOutbox.demoDeal(null, "555");
      HubSpotOutbox.demoDeal("A1", " ");

      dal.verifyNoInteractions();
    }
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

  @Test
  void theFirstKeyActionIsTheEarliestMilestoneReached() {
    List<String[]> rows = List.of(
        new String[] { HubSpotOutboxStore.MILESTONE_INVOICE, "{\"at\":\"2026-11-03T10:00:00Z\"}" },
        new String[] { HubSpotOutboxStore.MILESTONE_PRODUCT, "{\"at\":\"2026-11-02T09:30:00.250Z\"}" },
        new String[] { HubSpotOutboxStore.MILESTONE_ORDER, "{\"at\":\"2026-11-02T11:00:00Z\"}" });

    HubSpotOutbox.FirstKeyAction first = HubSpotOutbox.earliestMilestone(rows).orElseThrow();

    assertEquals(HubSpotOutboxStore.MILESTONE_PRODUCT, first.action());
    assertEquals(Instant.parse("2026-11-02T09:30:00.250Z"), first.at());
  }

  @Test
  void noFirstKeyActionWithoutAKnownMilestoneAndAReadableDate() {
    List<String[]> rows = List.of(
        new String[] { "unknown", "{\"at\":\"2026-11-01T00:00:00Z\"}" },
        new String[] { HubSpotOutboxStore.MILESTONE_PRODUCT, "not json" },
        new String[] { HubSpotOutboxStore.MILESTONE_ORDER, "{\"at\":\"yesterday\"}" },
        new String[] { HubSpotOutboxStore.MILESTONE_INVOICE, null });

    assertTrue(HubSpotOutbox.earliestMilestone(rows).isEmpty());
    assertTrue(HubSpotOutbox.earliestMilestone(List.of()).isEmpty());
  }

  @Test
  void anUnreadableFirstKeyActionAnswersNothingAndDoesNotFailTheCaller() {
    OBDal dal = mock(OBDal.class);
    when(dal.getSession()).thenThrow(new IllegalStateException("no session"));

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      obDal.when(OBDal::getInstance).thenReturn(dal);

      assertTrue(HubSpotOutbox.firstKeyAction("A1").isEmpty());
      assertTrue(HubSpotOutbox.firstKeyAction(" ").isEmpty());
    }
  }
}
