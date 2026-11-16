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

package com.etendoerp.go.portal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import javax.transaction.Status;
import javax.transaction.Synchronization;

import org.hibernate.Session;
import org.hibernate.Transaction;
import org.hibernate.query.NativeQuery;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.core.SessionHandler;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.order.Order;

import com.etendoerp.go.schemaforge.QuotationDecisions;
import com.etendoerp.go.schemaforge.telemetry.NeoTelemetryEvents;
import com.etendoerp.go.schemaforge.telemetry.NeoTelemetryService;

/**
 * Unit tests for {@link PortalKpiTelemetry}, the customer portal's three KPI events.
 *
 * <p>Events are emitted on a single background thread, so they are captured through the class's
 * {@code setTelemetryService} seam and awaited with {@code timeout}. To prove that nothing was
 * emitted, a test emits a sentinel event afterwards and waits for it: the sender is one FIFO
 * thread, so once the sentinel arrived, anything emitted before it has arrived too.
 *
 * @covers com.etendoerp.go.portal.PortalKpiTelemetry
 */
@SuppressWarnings({ "rawtypes", "unchecked" })
public class PortalKpiTelemetryTest {

  private static final long WAIT_MS = 2_000L;
  private static final String CLIENT_ID = "CLIENT-1";
  private static final String SENTINEL_CLIENT = "SENTINEL";
  private static final String RECORD_ID = "INV-1";
  private static final String QUOTATION_ID = "Q-1";
  private static final String LINK_ID = "LINK-1";
  private static final String KEY_ACCOUNT = "account_id";
  private static final String KEY_TYPE = "type";
  private static final String KEY_DECISION = "decision";
  private static final String KEY_SOURCE = "source";

  private MockedStatic<OBDal> obDalStatic;
  private MockedStatic<OBContext> obContext;
  private MockedStatic<SessionHandler> sessionHandlerStatic;
  private OBDal obDal;
  private Session session;
  private SessionHandler sessionHandler;
  private NeoTelemetryService telemetry;

  /** What the earlier-send count answers. */
  private long earlierSends;
  /** What the first-download stamp answers: rows updated, or a failure to throw. */
  private Object stampAnswer = 1;
  /** The parameters each native statement was given, by SQL. */
  private final Map<String, Map<String, Object>> statements = new HashMap<>();

  @Before
  public void setUp() {
    telemetry = mock(NeoTelemetryService.class);
    PortalKpiTelemetry.setTelemetryService(telemetry);
    obDal = mock(OBDal.class);
    obDalStatic = mockStatic(OBDal.class);
    obDalStatic.when(OBDal::getInstance).thenReturn(obDal);
    session = mock(Session.class);
    when(obDal.getSession()).thenReturn(session);
    when(session.createNativeQuery(anyString())).thenAnswer(inv -> nativeQuery(inv.getArgument(0)));
    obContext = mockStatic(OBContext.class);
    sessionHandler = mock(SessionHandler.class);
    sessionHandlerStatic = mockStatic(SessionHandler.class);
    sessionHandlerStatic.when(SessionHandler::getInstance).thenReturn(sessionHandler);
  }

  @After
  public void tearDown() {
    PortalKpiTelemetry.setTelemetryService(null);
    sessionHandlerStatic.close();
    obContext.close();
    obDalStatic.close();
  }

  // ── sent ────────────────────────────────────────────────────────────────────

  @Test
  public void linkSentEmitsForTheFirstSentEmailSendOfADocument() {
    PortalKpiTelemetry.linkSent(link(PortalLink.ORIGIN_SEND));

    Map<String, Object> properties = awaitEvent(NeoTelemetryEvents.BACKEND_PORTAL_DOCUMENT_SENT);
    assertEquals("invoice", properties.get(KEY_TYPE));
    assertEquals(CLIENT_ID, properties.get(KEY_ACCOUNT));
    // The earlier-send count is scoped to the tenant and the document, and excludes this link.
    Map<String, Object> params = onlyStatement("SELECT count(*)");
    assertEquals(CLIENT_ID, params.get("client"));
    assertEquals(RECORD_ID, params.get("record"));
    assertEquals(LINK_ID, params.get("link"));
  }

  @Test
  public void linkSentEmitsNothingForARenewalADecisionLinkOrASecondSend() {
    PortalKpiTelemetry.linkSent(link(PortalLink.ORIGIN_RENEWAL));
    PortalKpiTelemetry.linkSent(link(PortalLink.ORIGIN_DECISION));
    PortalKpiTelemetry.linkSent(null);
    earlierSends = 1;
    PortalKpiTelemetry.linkSent(link(PortalLink.ORIGIN_SEND));

    assertNothingEmittedBeforeTheSentinel();
  }

  // ── downloaded ──────────────────────────────────────────────────────────────

  @Test
  public void documentDownloadedEmitsOnlyWhenTheStampUpdatedARow() {
    PortalKpiTelemetry.documentDownloaded(new PortalSession(link(PortalLink.ORIGIN_SEND)),
        PortalDocumentKind.QUOTATION, QUOTATION_ID);

    Map<String, Object> properties =
        awaitEvent(NeoTelemetryEvents.BACKEND_PORTAL_DOCUMENT_DOWNLOADED);
    assertEquals("quotation", properties.get(KEY_TYPE));
    Map<String, Object> params = onlyStatement("UPDATE etgo_portal_link");
    assertEquals(CLIENT_ID, params.get("client"));
    assertEquals(QUOTATION_ID, params.get("record"));
    verify(sessionHandler).commitAndStart();
  }

  @Test
  public void documentDownloadedEmitsNothingWhenNoRowWasStampedOrTheStampFailed() {
    PortalSession portal = new PortalSession(link(PortalLink.ORIGIN_SEND));
    stampAnswer = 0;
    PortalKpiTelemetry.documentDownloaded(portal, PortalDocumentKind.INVOICE, RECORD_ID);
    stampAnswer = new IllegalStateException("lock timeout");
    PortalKpiTelemetry.documentDownloaded(portal, PortalDocumentKind.INVOICE, RECORD_ID);

    assertNothingEmittedBeforeTheSentinel();
    // The failed stamp is rolled back, never left in the request's transaction.
    verify(obDal).rollbackAndClose();
  }

  // ── decided ─────────────────────────────────────────────────────────────────

  @Test
  public void portalQuotationDecidedSendsTheDecisionWithSourcePortal() {
    // Columns: accepted, expected decision.
    Object[][] cases = { { true, PortalKpiTelemetry.DECISION_ACCEPTED },
        { false, PortalKpiTelemetry.DECISION_REJECTED } };
    for (Object[] c : cases) {
      NeoTelemetryService captured = mock(NeoTelemetryService.class);
      PortalKpiTelemetry.setTelemetryService(captured);

      PortalKpiTelemetry.portalQuotationDecided((Boolean) c[0], CLIENT_ID);

      Map<String, Object> properties = awaitEvent(captured,
          NeoTelemetryEvents.BACKEND_QUOTATION_DECIDED);
      assertEquals(c[1], properties.get(KEY_DECISION));
      assertEquals(PortalKpiTelemetry.SOURCE_PORTAL, properties.get(KEY_SOURCE));
    }
  }

  @Test
  public void appQuotationDecidedWaitsForTheCommitAndNeverSendsOnRollback() {
    Transaction transaction = mock(Transaction.class);
    when(transaction.isActive()).thenReturn(true);
    when(session.getTransaction()).thenReturn(transaction);
    Order quotation = quotation();

    PortalKpiTelemetry.appQuotationDecided(false, quotation);
    PortalKpiTelemetry.appQuotationDecided(true, quotation);

    ArgumentCaptor<Synchronization> synchronizations =
        ArgumentCaptor.forClass(Synchronization.class);
    verify(transaction, times(2))
        .registerSynchronization(synchronizations.capture());
    // The first write rolls back, the second commits: only the second is sent.
    synchronizations.getAllValues().get(0).afterCompletion(Status.STATUS_ROLLEDBACK);
    synchronizations.getAllValues().get(1).afterCompletion(Status.STATUS_COMMITTED);

    Map<String, Object> properties = awaitEvent(NeoTelemetryEvents.BACKEND_QUOTATION_DECIDED);
    assertEquals(PortalKpiTelemetry.DECISION_ACCEPTED, properties.get(KEY_DECISION));
    assertEquals(PortalKpiTelemetry.SOURCE_APP, properties.get(KEY_SOURCE));
    assertNothingEmittedBeforeTheSentinel();
  }

  @Test
  public void appQuotationConvertedSkipsAQuotationThePortalAlreadyAccepted() {
    // No active transaction: the event is sent at once.
    PortalKpiTelemetry.appQuotationConverted(QuotationDecisions.STATUS_ACCEPTED, quotation());
    assertNothingEmittedBeforeTheSentinel();

    PortalKpiTelemetry.appQuotationConverted(QuotationDecisions.STATUS_UNDER_EVALUATION,
        quotation());
    Map<String, Object> properties = awaitEvent(NeoTelemetryEvents.BACKEND_QUOTATION_DECIDED);
    assertEquals(PortalKpiTelemetry.DECISION_ACCEPTED, properties.get(KEY_DECISION));
    assertEquals(PortalKpiTelemetry.SOURCE_APP, properties.get(KEY_SOURCE));
  }

  // ── payload ─────────────────────────────────────────────────────────────────

  @Test
  public void typeOfMapsEveryKindToItsLowerCaseName() {
    Map<PortalDocumentKind, String> expected = new EnumMap<>(PortalDocumentKind.class);
    expected.put(PortalDocumentKind.QUOTATION, "quotation");
    expected.put(PortalDocumentKind.ORDER, "order");
    expected.put(PortalDocumentKind.SHIPMENT, "shipment");
    expected.put(PortalDocumentKind.INVOICE, "invoice");

    assertEquals("every kind has a type", PortalDocumentKind.values().length, expected.size());
    for (Map.Entry<PortalDocumentKind, String> entry : expected.entrySet()) {
      assertEquals(entry.getValue(), PortalKpiTelemetry.typeOf(entry.getKey()));
    }
  }

  @Test
  public void thePayloadCarriesNoRecordIdentifier() {
    PortalKpiTelemetry.linkSent(link(PortalLink.ORIGIN_SEND));
    Map<String, Object> sent = awaitEvent(NeoTelemetryEvents.BACKEND_PORTAL_DOCUMENT_SENT);
    PortalKpiTelemetry.appQuotationDecided(false, quotation());
    Map<String, Object> decided = awaitEvent(NeoTelemetryEvents.BACKEND_QUOTATION_DECIDED);

    assertEquals(new HashSet<>(Arrays.asList(KEY_ACCOUNT, KEY_TYPE)), sent.keySet());
    assertEquals(new HashSet<>(Arrays.asList(KEY_ACCOUNT, KEY_DECISION, KEY_SOURCE)),
        decided.keySet());
    assertFalse(sent.containsValue(RECORD_ID) || sent.containsValue(LINK_ID));
    assertFalse(decided.containsValue(QUOTATION_ID));
  }

  // ── fixtures ────────────────────────────────────────────────────────────────

  private Map<String, Object> awaitEvent(String event) {
    return awaitEvent(telemetry, event);
  }

  private static Map<String, Object> awaitEvent(NeoTelemetryService service, String event) {
    ArgumentCaptor<Map> properties = ArgumentCaptor.forClass(Map.class);
    verify(service, timeout(WAIT_MS)).emit(eq(event), properties.capture(), any());
    return properties.getValue();
  }

  /** Emits a sentinel and, once it arrived, checks it was the only event. */
  private void assertNothingEmittedBeforeTheSentinel() {
    NeoTelemetryService watched = telemetry;
    PortalKpiTelemetry.portalQuotationDecided(true, SENTINEL_CLIENT);
    verify(watched, timeout(WAIT_MS)).emit(anyString(), anyMap(), eq(SENTINEL_CLIENT));
    verifyNoMoreInteractions(watched);
    PortalKpiTelemetry.setTelemetryService(telemetry = mock(NeoTelemetryService.class));
  }

  private Map<String, Object> onlyStatement(String prefix) {
    List<Map<String, Object>> matching = new ArrayList<>();
    for (Map.Entry<String, Map<String, Object>> statement : statements.entrySet()) {
      if (statement.getKey().startsWith(prefix)) {
        matching.add(statement.getValue());
      }
    }
    assertEquals(prefix, 1, matching.size());
    return matching.get(0);
  }

  private NativeQuery nativeQuery(String sql) {
    Map<String, Object> params = new HashMap<>();
    statements.put(sql, params);
    return mock(NativeQuery.class, inv -> {
      String method = inv.getMethod().getName();
      if ("setParameter".equals(method)) {
        params.put(inv.getArgument(0), inv.getArgument(1));
        return inv.getMock();
      }
      if ("list".equals(method)) {
        return Collections.singletonList(earlierSends);
      }
      if ("executeUpdate".equals(method)) {
        if (stampAnswer instanceof RuntimeException) {
          throw (RuntimeException) stampAnswer;
        }
        return stampAnswer;
      }
      return null;
    });
  }

  private static Order quotation() {
    Client client = mock(Client.class);
    when(client.getId()).thenReturn(CLIENT_ID);
    Order quotation = mock(Order.class);
    when(quotation.getId()).thenReturn(QUOTATION_ID);
    when(quotation.getClient()).thenReturn(client);
    return quotation;
  }

  private static PortalLink link(String origin) {
    return new PortalLink(LINK_ID, CLIENT_ID, "Acme", "ORG-1", "BP-1", "Customer SL",
        PortalDocumentKind.INVOICE, RECORD_ID, origin, null, LINK_ID, "sales-invoice-send",
        "a@customer.test", null, "es_ES", new Date(0L), new Date(1L), true, false);
  }
}
