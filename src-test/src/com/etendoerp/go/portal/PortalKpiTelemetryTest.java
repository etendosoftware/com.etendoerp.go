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
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

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
import org.openbravo.base.session.OBPropertiesProvider;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.core.SessionHandler;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.enterprise.DocumentType;
import org.openbravo.model.common.invoice.Invoice;
import org.openbravo.model.common.order.Order;

import com.etendoerp.go.schemaforge.QuotationDecisions;
import com.etendoerp.go.schemaforge.email.DocumentDownloadTokenService;
import com.etendoerp.go.schemaforge.telemetry.NeoTelemetryEvents;
import com.etendoerp.go.schemaforge.telemetry.NeoTelemetryIdentity;
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
  private static final String KEY_THIRD_PARTY = "third_party";
  private static final String DECISION_ID = "DEC-1";
  private static final String EMAILED_SQL = "SELECT count(*)";
  private static final String FIRST_DECISION_SQL = "SELECT etgo_portal_decision_id";
  private static final String KEY_PIECE = "piece";

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
  /** The quotation's first confirmed decision id, or {@code null} when none is confirmed. */
  private String firstConfirmed;
  /** A failure every read ({@code list}) throws, or {@code null}. */
  private RuntimeException readFailure;
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

  // ── identity hand-off (ETP-6386) ────────────────────────────────────────────

  private static final NeoTelemetryIdentity APP_USER =
      new NeoTelemetryIdentity("U1", CLIENT_ID, "OWNER-A", "productive");

  /**
   * Stubs the captured identity from the session of the CALLING thread: a GO user context gives
   * {@link #APP_USER}, no context (the customer-facing portal runs in admin mode) gives none.
   * Records the capture and emit threads and the identity handed to the sender.
   */
  private static final class IdentityProbe {
    final AtomicReference<Thread> captureThread = new AtomicReference<>();
    final AtomicReference<Thread> emitThread = new AtomicReference<>();
    final AtomicReference<NeoTelemetryIdentity> handedOff = new AtomicReference<>();
    final CountDownLatch emitted = new CountDownLatch(1);

    IdentityProbe(NeoTelemetryService service) {
      when(service.captureIdentity(any(), any())).thenAnswer(inv -> {
        captureThread.set(Thread.currentThread());
        return OBContext.getOBContext() == null ? NeoTelemetryIdentity.NONE : APP_USER;
      });
      org.mockito.Mockito.doAnswer(inv -> {
        emitThread.set(Thread.currentThread());
        handedOff.set(NeoTelemetryIdentity.handedOff());
        emitted.countDown();
        return null;
      }).when(service).emit(anyString(), anyMap(), any());
    }

    void awaitEmit() throws InterruptedException {
      org.junit.Assert.assertTrue(emitted.await(WAIT_MS, TimeUnit.MILLISECONDS));
    }
  }

  @Test
  public void aCustomerTriggeredEventCarriesNoUser() throws Exception {
    IdentityProbe probe = new IdentityProbe(telemetry);
    obContext.when(OBContext::getOBContext).thenReturn(null);

    PortalKpiTelemetry.portalQuotationDecided(true, CLIENT_ID);

    probe.awaitEmit();
    assertSame(Thread.currentThread(), probe.captureThread.get());
    assertNotSame(Thread.currentThread(), probe.emitThread.get());
    assertSame(NeoTelemetryIdentity.NONE, probe.handedOff.get());
  }

  @Test
  public void anAppSideDecisionCarriesTheUserCapturedOnTheCallingThread() throws Exception {
    IdentityProbe probe = new IdentityProbe(telemetry);
    obContext.when(OBContext::getOBContext).thenReturn(mock(OBContext.class));
    Transaction transaction = mock(Transaction.class);
    when(transaction.isActive()).thenReturn(true);
    when(session.getTransaction()).thenReturn(transaction);

    PortalKpiTelemetry.appQuotationDecided(true, quotation());
    // The commit callback runs on another thread, after the request's session is gone.
    ArgumentCaptor<Synchronization> sync = ArgumentCaptor.forClass(Synchronization.class);
    verify(transaction).registerSynchronization(sync.capture());
    obContext.when(OBContext::getOBContext).thenReturn(null);
    Thread commit = new Thread(() -> sync.getValue().afterCompletion(Status.STATUS_COMMITTED));
    commit.start();
    commit.join();

    probe.awaitEmit();
    assertSame(Thread.currentThread(), probe.captureThread.get());
    assertSame(APP_USER, probe.handedOff.get());
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

  // ── ETP-6209: the portal decision KPI gate ──────────────────────────────────

  /** {@code earlierSends} is what the "emailed with a confirmed SEND link" count answers. */
  @Test
  public void theFirstConfirmedDecisionOfAnEmailedQuotationIsCountedOnce() {
    earlierSends = 1;
    firstConfirmed = DECISION_ID;
    Object[][] cases = { { true, PortalKpiTelemetry.DECISION_ACCEPTED },
        { false, PortalKpiTelemetry.DECISION_REJECTED } };
    for (Object[] c : cases) {
      NeoTelemetryService captured = mock(NeoTelemetryService.class);
      PortalKpiTelemetry.setTelemetryService(captured);

      PortalKpiTelemetry.portalQuotationConfirmed((Boolean) c[0], CLIENT_ID, DECISION_ID,
          QUOTATION_ID);

      Map<String, Object> properties = awaitEvent(captured,
          NeoTelemetryEvents.BACKEND_QUOTATION_DECIDED);
      assertEquals(c[1], properties.get(KEY_DECISION));
      assertEquals(PortalKpiTelemetry.SOURCE_PORTAL, properties.get(KEY_SOURCE));
    }
    for (String prefix : new String[] { EMAILED_SQL, FIRST_DECISION_SQL }) {
      Map<String, Object> params = onlyStatement(prefix);
      assertEquals(prefix, CLIENT_ID, params.get("client"));
      assertEquals(prefix, QUOTATION_ID, params.get("record"));
    }
  }

  @Test
  public void aDecisionIsNotCountedWithoutAnEmailedLinkOrWhenAnotherDecisionWasFirst() {
    // No confirmed SEND link: only a SHARED one, or a send whose email never left (sent_at null).
    earlierSends = 0;
    firstConfirmed = DECISION_ID;
    PortalKpiTelemetry.portalQuotationConfirmed(true, CLIENT_ID, DECISION_ID, QUOTATION_ID);
    String emailedSql = statements.keySet().stream().filter(sql -> sql.startsWith(EMAILED_SQL))
        .findFirst().orElse("");
    assertTrue(emailedSql, emailedSql.contains("link_origin = '" + PortalLink.ORIGIN_SEND + "'")
        && emailedSql.contains("sent_at IS NOT NULL"));
    assertFalse("the first-decision read is skipped", statements.keySet().stream()
        .anyMatch(sql -> sql.startsWith(FIRST_DECISION_SQL)));
    // A re-opened quotation decided again: another decision was the first confirmed one.
    earlierSends = 1;
    firstConfirmed = "DEC-0";
    PortalKpiTelemetry.portalQuotationConfirmed(true, CLIENT_ID, DECISION_ID, QUOTATION_ID);
    // No confirmed decision found at all.
    firstConfirmed = null;
    PortalKpiTelemetry.portalQuotationConfirmed(false, CLIENT_ID, DECISION_ID, QUOTATION_ID);

    assertNothingEmittedBeforeTheSentinel();
  }

  @Test
  public void aBlankIdIsNotCountedAndRunsNoQuery() {
    earlierSends = 1;
    firstConfirmed = DECISION_ID;
    String[][] cases = { { null, DECISION_ID, QUOTATION_ID }, { CLIENT_ID, " ", QUOTATION_ID },
        { CLIENT_ID, DECISION_ID, "" } };
    for (String[] c : cases) {
      PortalKpiTelemetry.portalQuotationConfirmed(true, c[0], c[1], c[2]);
    }

    assertTrue(statements.keySet().toString(), statements.isEmpty());
    assertNothingEmittedBeforeTheSentinel();
  }

  @Test
  public void aFailedCheckCountsNothingNeverThrowsAndDiscardsTheReadSession() {
    readFailure = new IllegalStateException("connection lost");

    PortalKpiTelemetry.portalQuotationConfirmed(true, CLIENT_ID, DECISION_ID, QUOTATION_ID);

    verify(obDal).rollbackAndClose();
    assertNothingEmittedBeforeTheSentinel();
  }

  // ── ETP-6209: portal visits and portal emails ─────────────────────────────

  @Test
  public void aVisitOpensOnlyAfterThirtyMinutesWithoutAValidMeOfTheSameLink() {
    String linkId = uniqueLinkId();
    long start = 1_000_000L;
    long minute = TimeUnit.MINUTES.toMillis(1);

    assertTrue("first", PortalKpiTelemetry.isNewVisit(linkId, start));
    assertFalse("29 min later", PortalKpiTelemetry.isNewVisit(linkId, start + 29 * minute));
    assertFalse("the window slid: 58 min after the first, 29 after the last",
        PortalKpiTelemetry.isNewVisit(linkId, start + 58 * minute));
    assertTrue("31 min without one", PortalKpiTelemetry.isNewVisit(linkId, start + 89 * minute));
    for (String blank : new String[] { null, "", "  " }) {
      assertFalse("blank id " + blank, PortalKpiTelemetry.isNewVisit(blank, start));
    }
  }

  @Test
  public void reachingTheCapSweepsTheLinksWhoseVisitEnded() throws Exception {
    Map<String, Long> tracked = trackedOpens();
    String prefix = uniqueLinkId() + "-";
    long now = TimeUnit.HOURS.toMillis(2);
    try {
      for (int i = 0; i < 10_000; i++) {
        tracked.put(prefix + i, 0L);
      }
      tracked.put(prefix + "recent", now - TimeUnit.MINUTES.toMillis(1));

      assertTrue(PortalKpiTelemetry.isNewVisit(prefix + "new", now));

      assertFalse("ended visits are swept", tracked.containsKey(prefix + 0));
      assertFalse(tracked.containsKey(prefix + 9_999));
      assertFalse("a visit still open is kept",
          PortalKpiTelemetry.isNewVisit(prefix + "recent", now));
    } finally {
      tracked.keySet().removeIf(key -> key.startsWith(prefix));
    }
  }

  @Test
  public void anOpenedVisitNamesTheEntryTypeTheOriginAndAStablePseudonymOfTheCustomer() {
    Invoice creditNote = creditNote();
    when(obDal.get(Invoice.class, RECORD_ID)).thenReturn(creditNote);
    withSigningSecret(() -> {
      Map<String, Object> send = opened(PortalLink.ORIGIN_SEND, PortalDocumentKind.INVOICE, "BP-1");
      Map<String, Object> renewal = opened(PortalLink.ORIGIN_RENEWAL,
          PortalDocumentKind.QUOTATION, "BP-1");
      Map<String, Object> otherCustomer = opened(PortalLink.ORIGIN_SEND,
          PortalDocumentKind.QUOTATION, "BP-2");

      assertEquals(new HashSet<>(Arrays.asList(KEY_ACCOUNT, KEY_THIRD_PARTY, "entry_doc_type",
          "origin")), send.keySet());
      assertEquals(CLIENT_ID, send.get(KEY_ACCOUNT));
      assertEquals("credit_note", send.get("entry_doc_type"));
      assertEquals("send", send.get("origin"));
      assertEquals("quotation", renewal.get("entry_doc_type"));
      assertEquals("renewal", renewal.get("origin"));
      assertEquals("same customer, same pseudonym", send.get(KEY_THIRD_PARTY),
          renewal.get(KEY_THIRD_PARTY));
      assertNotEquals(send.get(KEY_THIRD_PARTY), otherCustomer.get(KEY_THIRD_PARTY));
      for (Map<String, Object> properties : Arrays.asList(send, renewal, otherCustomer)) {
        for (Object value : properties.values()) {
          String text = String.valueOf(value);
          for (String identifier : Arrays.asList(RECORD_ID, "BP-1", "BP-2", "LINK-",
              "a@customer.test", "Customer SL")) {
            assertFalse(text + " leaks " + identifier, text.contains(identifier));
          }
        }
      }
    });
  }

  @Test
  public void withoutTheSigningSecretAnOpenedVisitCarriesNoThirdParty() {
    withoutSigningSecret(() -> assertEquals(new HashSet<>(Arrays.asList(KEY_ACCOUNT,
        "entry_doc_type", "origin")),
        opened(PortalLink.ORIGIN_SEND, PortalDocumentKind.ORDER, "BP-1").keySet()));
  }

  @Test
  public void aPortalEmailIsCountedByPieceAndOnlyADocumentSendNamesItsType() {
    Map<String, Object> send = emittedBy(
        () -> PortalKpiTelemetry.linkEmailSent(link(PortalLink.ORIGIN_SEND)));
    Map<String, Object> renewal = emittedBy(
        () -> PortalKpiTelemetry.linkEmailSent(link(PortalLink.ORIGIN_RENEWAL)));
    Map<String, Object> accept = emittedBy(
        () -> PortalKpiTelemetry.decisionEmailSent(true, CLIENT_ID));
    Map<String, Object> reject = emittedBy(
        () -> PortalKpiTelemetry.decisionEmailSent(false, CLIENT_ID));

    assertEquals(properties(KEY_PIECE, PortalKpiTelemetry.PIECE_DOCUMENT_SEND, "doc_type",
        "invoice"), send);
    assertEquals(properties(KEY_PIECE, PortalKpiTelemetry.PIECE_LINK_RENEWAL), renewal);
    assertEquals(properties(KEY_PIECE, PortalKpiTelemetry.PIECE_DECISION_ACCEPT), accept);
    assertEquals(properties(KEY_PIECE, PortalKpiTelemetry.PIECE_DECISION_REJECT), reject);
  }

  @Test
  public void aDecisionOrSharedLinkEmailIsNotCountedAsAPortalEmail() {
    PortalKpiTelemetry.linkEmailSent(link(PortalLink.ORIGIN_DECISION));
    PortalKpiTelemetry.linkEmailSent(link(PortalLink.ORIGIN_SHARED));
    PortalKpiTelemetry.linkEmailSent(null);

    assertNothingEmittedBeforeTheSentinel();
  }

  /** Opens a visit of a fresh link and returns its {@code backend_portal_opened} properties. */
  // ── fixtures ────────────────────────────────────────────────────────────────

  private static Map<String, Object> opened(String origin, PortalDocumentKind kind,
      String businessPartnerId) {
    PortalLink link = new PortalLink(uniqueLinkId(), CLIENT_ID, "Acme", "ORG-1",
        businessPartnerId, "Customer SL", kind, RECORD_ID, origin, null, LINK_ID,
        "sales-invoice-send", "a@customer.test", null, "es_ES", new Date(0L), new Date(1L), true,
        false);
    NeoTelemetryService captured = mock(NeoTelemetryService.class);
    PortalKpiTelemetry.setTelemetryService(captured);
    PortalKpiTelemetry.portalOpened(link);
    return awaitEvent(captured, NeoTelemetryEvents.BACKEND_PORTAL_OPENED);
  }

  /** Runs a call against a fresh service and returns its {@code backend_portal_email_sent}. */
  private static Map<String, Object> emittedBy(Runnable call) {
    NeoTelemetryService captured = mock(NeoTelemetryService.class);
    PortalKpiTelemetry.setTelemetryService(captured);
    call.run();
    return awaitEvent(captured, NeoTelemetryEvents.BACKEND_PORTAL_EMAIL_SENT);
  }

  /** The tenant's account plus the given key/value pairs. */
  private static Map<String, Object> properties(String... pairs) {
    Map<String, Object> expected = new HashMap<>();
    expected.put(KEY_ACCOUNT, CLIENT_ID);
    for (int i = 0; i < pairs.length; i += 2) {
      expected.put(pairs[i], pairs[i + 1]);
    }
    return expected;
  }

  /** A link id no other test used: the visit tracker is static and outlives a test. */
  private static String uniqueLinkId() {
    return "LINK-" + UUID.randomUUID();
  }

  private static Map<String, Long> trackedOpens() throws Exception {
    Field field = PortalKpiTelemetry.class.getDeclaredField("LAST_OPEN");
    field.setAccessible(true);
    return (Map<String, Long>) field.get(null);
  }

  private static Invoice creditNote() {
    DocumentType type = mock(DocumentType.class);
    when(type.getDocumentCategory()).thenReturn("ARC");
    Invoice invoice = mock(Invoice.class);
    when(invoice.getTransactionDocument()).thenReturn(type);
    return invoice;
  }

  /** The {@code third_party} pseudonym is signed with the email signing secret. */
  private static void withSigningSecret(Runnable body) {
    String previous = System.getProperty(DocumentDownloadTokenService.PROP_TOKEN_SECRET);
    System.setProperty(DocumentDownloadTokenService.PROP_TOKEN_SECRET, "kpi-secret");
    try {
      body.run();
    } finally {
      restoreSecret(previous);
    }
  }

  /** No secret anywhere: property cleared, Openbravo.properties empty (env assumed absent). */
  private static void withoutSigningSecret(Runnable body) {
    String previous = System.getProperty(DocumentDownloadTokenService.PROP_TOKEN_SECRET);
    OBPropertiesProvider provider = mock(OBPropertiesProvider.class);
    when(provider.getOpenbravoProperties()).thenReturn(new Properties());
    try (MockedStatic<OBPropertiesProvider> properties = mockStatic(OBPropertiesProvider.class)) {
      properties.when(OBPropertiesProvider::getInstance).thenReturn(provider);
      System.clearProperty(DocumentDownloadTokenService.PROP_TOKEN_SECRET);
      body.run();
    } finally {
      restoreSecret(previous);
    }
  }

  private static void restoreSecret(String previous) {
    if (previous == null) {
      System.clearProperty(DocumentDownloadTokenService.PROP_TOKEN_SECRET);
    } else {
      System.setProperty(DocumentDownloadTokenService.PROP_TOKEN_SECRET, previous);
    }
  }

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
    // ETP-6386: the identity is captured on the calling thread at the entry point, so a decision
    // that later rolls back was captured too (a cached lookup), though never emitted.
    verify(watched, atLeastOnce()).captureIdentity(anyString(), any());
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
        if (readFailure != null) {
          throw readFailure;
        }
        if (sql.startsWith(FIRST_DECISION_SQL)) {
          return firstConfirmed == null ? Collections.emptyList()
              : Collections.singletonList(firstConfirmed);
        }
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
