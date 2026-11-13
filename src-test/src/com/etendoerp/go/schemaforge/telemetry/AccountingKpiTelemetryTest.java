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

package com.etendoerp.go.schemaforge.telemetry;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.openbravo.dal.core.OBContext;
import org.openbravo.database.ExternalConnectionPool;
import org.openbravo.model.ad.system.Client;

/**
 * Behavior of the accounting-calendar KPI telemetry: the allowlisted props, the internal-domain
 * rule, the review-required gate, the once-only markers, failure isolation, the first-document
 * probe and the period-refusal detection.
 *
 * <p>The sender thread does the work, so the {@link ExternalConnectionPool} static mock is
 * installed <em>on that thread</em> (Mockito static mocks are thread-bound) and each test drains
 * the single-thread queue before asserting.</p>
 *
 * @covers com.etendoerp.go.schemaforge.telemetry.AccountingKpiTelemetry
 */
public class AccountingKpiTelemetryTest {

  private static final String PERIOD_KEY = "PeriodNotAvailable";
  private static final String OTHER_PERIOD_KEY = "PeriodNotValid";
  private static final AtomicInteger IDS = new AtomicInteger();
  private static final AtomicReference<ExternalConnectionPool> POOL = new AtomicReference<>();

  private final List<NeoTelemetryEvent> events = new CopyOnWriteArrayList<>();
  private final FakeDb db = new FakeDb();
  private MockedStatic<ExternalConnectionPool> poolStatic;
  private String client;

  @Before
  public void setUp() throws Exception {
    client = "KPI-TEST-" + IDS.incrementAndGet();
    ExternalConnectionPool pool = mock(ExternalConnectionPool.class);
    when(pool.getConnection()).thenAnswer(i -> {
      if (db.failConnection) {
        throw new SQLException("pool down");
      }
      return db.connection();
    });
    POOL.set(pool);
    onSender(() -> {
      poolStatic = mockStatic(ExternalConnectionPool.class);
      poolStatic.when(ExternalConnectionPool::getInstance).thenAnswer(i -> POOL.get());
    });
    AccountingKpiTelemetry.resetStateForTests();
    AccountingKpiTelemetry.setProbeDelayMillisForTests(0);
    AccountingKpiTelemetry.setTelemetryService(
        new NeoTelemetryService(events::add, System::nanoTime));
  }

  @After
  public void tearDown() throws Exception {
    drain();
    onSender(() -> poolStatic.close());
    AccountingKpiTelemetry.setTelemetryService(null);
    AccountingKpiTelemetry.resetStateForTests();
    AccountingKpiTelemetry.endRestRequest();
    System.clearProperty(AccountingKpiTelemetry.PROP_INTERNAL_DOMAINS);
  }

  // ── isInternalDomain ─────────────────────────────────────────────────────────────────────────

  @Test
  public void isInternalDomainIsTrueForTheThreeDefaultDomains() {
    assertTrue(AccountingKpiTelemetry.isInternalDomain("a@etendo.software"));
    assertTrue(AccountingKpiTelemetry.isInternalDomain("a@etendo.ai"));
    assertTrue(AccountingKpiTelemetry.isInternalDomain("  A@SmfConsulting.ES "));
  }

  @Test
  public void isInternalDomainIsFalseForLookalikesAndBlanks() {
    assertFalse(AccountingKpiTelemetry.isInternalDomain("a@notetendo.ai"));
    assertFalse(AccountingKpiTelemetry.isInternalDomain("a@etendo.ai.evil.com"));
    assertFalse(AccountingKpiTelemetry.isInternalDomain("etendo.ai@gmail.com"));
    assertFalse(AccountingKpiTelemetry.isInternalDomain("no-at-sign"));
    assertFalse(AccountingKpiTelemetry.isInternalDomain(""));
    assertFalse(AccountingKpiTelemetry.isInternalDomain(null));
  }

  @Test
  public void theSystemPropertyReplacesTheDefaultList() {
    System.setProperty(AccountingKpiTelemetry.PROP_INTERNAL_DOMAINS, "corp.example, other.example");

    assertTrue(AccountingKpiTelemetry.isInternalDomain("a@corp.example"));
    assertTrue(AccountingKpiTelemetry.isInternalDomain("a@other.example"));
    assertFalse(AccountingKpiTelemetry.isInternalDomain("a@etendo.ai"));
  }

  // ── Props and privacy ────────────────────────────────────────────────────────────────────────

  @Test
  public void aReviewEventCarriesAccountIdAndTheInternalBooleanButNeverTheEmail() throws Exception {
    db.reviewRequired = true;
    db.ownerEmail = "owner@etendo.ai";

    AccountingKpiTelemetry.calendarReviewCompleted(client,
        AccountingKpiTelemetry.REVIEW_FIRST_STEPS, AccountingKpiTelemetry.TYPE_MODIFY,
        AccountingKpiTelemetry.SOURCE_FIRST_STEPS);
    drain();

    assertEquals(1, events.size());
    NeoTelemetryEvent event = events.get(0);
    assertEquals(NeoTelemetryEvents.BACKEND_CALENDAR_REVIEW_COMPLETED, event.getName());
    Map<String, Object> props = event.getProperties();
    assertEquals(Set.of("type", "source", "account_id", "internal"), props.keySet());
    assertEquals(client, props.get("account_id"));
    assertEquals(Boolean.TRUE, props.get("internal"));
    assertEquals("modify", props.get("type"));
    assertEquals("first_steps", props.get("source"));
    assertNoEmail(props);
  }

  @Test
  public void anExternalOwnerIsNotInternal() throws Exception {
    db.reviewRequired = true;
    db.ownerEmail = "owner@customer.com";

    AccountingKpiTelemetry.productiveEnvironmentCreated(client);
    drain();

    assertEquals(NeoTelemetryEvents.BACKEND_PRODUCTIVE_ENVIRONMENT_CREATED, events.get(0).getName());
    assertEquals(Boolean.FALSE, events.get(0).getProperties().get("internal"));
    assertNoEmail(events.get(0).getProperties());
  }

  // ── Gate ─────────────────────────────────────────────────────────────────────────────────────

  @Test
  public void nothingIsEmittedForATenantThatDoesNotRequireTheReview() throws Exception {
    db.reviewRequired = false;
    db.processedDocument = true;

    AccountingKpiTelemetry.calendarReviewCompleted(client,
        AccountingKpiTelemetry.REVIEW_FIRST_STEPS, AccountingKpiTelemetry.TYPE_CONFIRM,
        AccountingKpiTelemetry.SOURCE_FIRST_STEPS);
    AccountingKpiTelemetry.enqueueFirstDocumentProbe(client, "spa", "sales-invoice", "header",
        "complete");
    drain();

    assertTrue(events.toString(), events.isEmpty());
    assertTrue("no marker may be written for a gated tenant", db.markers.isEmpty());
  }

  @Test
  public void aPeriodRefusalIsEmittedForEveryTenantEvenWithoutTheReviewRequirement()
      throws Exception {
    db.reviewRequired = false;

    AccountingKpiTelemetry.observeRefusal(client, "mcp", "sales-invoice", "header", "complete",
        new JSONObject().put("messageKeys", new JSONArray().put(PERIOD_KEY)), null);
    drain();

    assertEquals(1, events.size());
    assertEquals(NeoTelemetryEvents.BACKEND_PERIOD_REFUSED, events.get(0).getName());
    Map<String, Object> props = events.get(0).getProperties();
    assertEquals(PERIOD_KEY, props.get("errorCode"));
    assertEquals("mcp", props.get("source"));
    assertEquals("sales-invoice", props.get("specName"));
    assertEquals("header", props.get("entity"));
    assertEquals("complete", props.get("operation"));
    assertEquals(client, props.get("account_id"));
  }

  // ── Once-only markers ────────────────────────────────────────────────────────────────────────

  @Test
  public void eachReviewKindEmitsOnceAndHasItsOwnMarker() throws Exception {
    db.reviewRequired = true;

    for (int i = 0; i < 2; i++) {
      AccountingKpiTelemetry.calendarReviewCompleted(client,
          AccountingKpiTelemetry.REVIEW_FIRST_STEPS, AccountingKpiTelemetry.TYPE_CONFIRM,
          AccountingKpiTelemetry.SOURCE_FIRST_STEPS);
      AccountingKpiTelemetry.calendarReviewCompleted(client,
          AccountingKpiTelemetry.REVIEW_MANUAL_OPEN, AccountingKpiTelemetry.TYPE_CONFIRM,
          AccountingKpiTelemetry.SOURCE_CALENDAR_WINDOW);
      drain();
    }

    assertEquals(events.toString(), 2, events.size());
    assertEquals("first_steps", events.get(0).getProperties().get("source"));
    assertEquals("calendar_window", events.get(1).getProperties().get("source"));
    assertEquals(Set.of(AccountingKpiTelemetry.MARKER_REVIEW_FIRST_STEPS,
        AccountingKpiTelemetry.MARKER_REVIEW_MANUAL_OPEN), db.markers);
  }

  @Test
  public void theFirstDocumentIsReportedOnceAndSetsItsMarker() throws Exception {
    db.reviewRequired = true;
    db.processedDocument = true;

    AccountingKpiTelemetry.enqueueFirstDocumentProbe(client, "spa", "sales-invoice", "header",
        "complete");
    drain();
    AccountingKpiTelemetry.enqueueFirstDocumentProbe(client, "spa", "sales-invoice", "header",
        "complete");
    drain();

    assertEquals(events.toString(), 1, events.size());
    assertEquals(NeoTelemetryEvents.BACKEND_FIRST_DOCUMENT_PROCESSED, events.get(0).getName());
    assertEquals(Set.of(AccountingKpiTelemetry.MARKER_FIRST_DOC), db.markers);
  }

  @Test
  public void aTenantWhoseMarkerAlreadyExistsEmitsNoFirstDocument() throws Exception {
    db.reviewRequired = true;
    db.processedDocument = true;
    db.markers.add(AccountingKpiTelemetry.MARKER_FIRST_DOC);

    AccountingKpiTelemetry.enqueueFirstDocumentProbe(client, "spa", null, null, "complete");
    drain();

    assertTrue(events.toString(), events.isEmpty());
  }

  @Test
  public void noEventIsEmittedWhileNoDocumentIsProcessed() throws Exception {
    db.reviewRequired = true;
    db.processedDocument = false;

    AccountingKpiTelemetry.enqueueFirstDocumentProbe(client, "spa", null, null, "complete");
    drain();

    assertTrue(events.isEmpty());
    assertTrue(db.markers.isEmpty());
  }

  // ── Failure isolation ────────────────────────────────────────────────────────────────────────

  @Test
  public void aConnectionFailureNeverReachesTheCallerAndEmitsNothing() throws Exception {
    db.reviewRequired = true;
    db.processedDocument = true;
    db.failConnection = true;

    AccountingKpiTelemetry.calendarReviewCompleted(client,
        AccountingKpiTelemetry.REVIEW_FIRST_STEPS, AccountingKpiTelemetry.TYPE_CONFIRM, "mcp");
    AccountingKpiTelemetry.enqueueFirstDocumentProbe(client, "spa", null, null, "complete");
    AccountingKpiTelemetry.productiveEnvironmentCreated(client);
    AccountingKpiTelemetry.observeRefusal(client, "spa", null, null, null,
        new JSONObject().put("messageKeys", new JSONArray().put(PERIOD_KEY)), null);
    drain();

    assertTrue(events.isEmpty());
  }

  @Test
  public void aMarkerWriteFailureIsSwallowedAfterTheEventWasEmitted() throws Exception {
    db.reviewRequired = true;
    db.failMarkerInsert = true;

    AccountingKpiTelemetry.calendarReviewCompleted(client,
        AccountingKpiTelemetry.REVIEW_MANUAL_OPEN, AccountingKpiTelemetry.TYPE_CONFIRM, "mcp");
    drain();

    assertEquals(1, events.size());
    assertTrue(db.markers.isEmpty());
  }

  @Test
  public void aMissingPoolIsHandledWithoutEmitting() throws Exception {
    db.reviewRequired = true;
    POOL.set(null);

    AccountingKpiTelemetry.calendarReviewCompleted(client,
        AccountingKpiTelemetry.REVIEW_FIRST_STEPS, AccountingKpiTelemetry.TYPE_CONFIRM, "mcp");
    drain();

    assertTrue(events.isEmpty());
  }

  @Test
  public void hooksIgnoreABlankTenant() throws Exception {
    AccountingKpiTelemetry.calendarReviewCompleted(" ", AccountingKpiTelemetry.REVIEW_FIRST_STEPS,
        AccountingKpiTelemetry.TYPE_CONFIRM, "mcp");
    AccountingKpiTelemetry.enqueueFirstDocumentProbe(null, "spa", null, null, "complete");
    AccountingKpiTelemetry.observeRefusal(null, "spa", null, null, null,
        new JSONObject().put("messageKeys", new JSONArray().put(PERIOD_KEY)), null);
    drain();

    assertTrue(events.isEmpty());
    assertEquals("no connection may be opened", 0, db.connectionsOpened);
  }

  // ── Operation mapping ────────────────────────────────────────────────────────────────────────

  @Test
  public void theProbeMapsPostToPostAndAnythingElseToComplete() throws Exception {
    assertEquals("post", probeOperation("post"));
    assertEquals("post", probeOperation("POST"));
    assertEquals("complete", probeOperation("complete"));
    assertEquals("complete", probeOperation("create"));
    assertEquals("complete", probeOperation(null));
  }

  private String probeOperation(String hint) throws Exception {
    events.clear();
    client = "KPI-TEST-" + IDS.incrementAndGet();
    db.reviewRequired = true;
    db.processedDocument = true;
    db.markers.clear();
    AccountingKpiTelemetry.enqueueFirstDocumentProbe(client, "mcp", "sales-invoice", "header",
        hint);
    drain();
    assertEquals(events.toString(), 1, events.size());
    assertEquals("mcp", events.get(0).getProperties().get("source"));
    return (String) events.get(0).getProperties().get("operation");
  }

  @Test
  public void aRestPostWithoutAnActionIsReportedAsComplete() throws Exception {
    db.reviewRequired = true;
    db.processedDocument = true;
    try (MockedStatic<OBContext> ctx = clientContext()) {
      AccountingKpiTelemetry.beginRestRequest("sales-invoice", "header", "POST", null);
      AccountingKpiTelemetry.restWriteSucceeded(201);
      drain();
    }

    assertEquals(events.toString(), 1, events.size());
    assertEquals("complete", events.get(0).getProperties().get("operation"));
  }

  @Test
  public void aRefusalWithoutAnActionReportsTheHttpMethod() throws Exception {
    JSONObject body = new JSONObject().put("messageKeys", new JSONArray().put(PERIOD_KEY));
    try (MockedStatic<OBContext> ctx = clientContext()) {
      AccountingKpiTelemetry.beginRestRequest("sales-invoice", "header", "POST", null);
      AccountingKpiTelemetry.observeRestResponse(409, body);
      drain();
    }

    assertEquals(events.toString(), 1, events.size());
    assertEquals("post", events.get(0).getProperties().get("operation"));
  }

  // ── Throttle, negative cache and delay ───────────────────────────────────────────────────────

  @Test
  public void aSecondProbeForTheSameTenantWithinTheIntervalIsNotRun() throws Exception {
    db.reviewRequired = true;
    db.processedDocument = false;

    AccountingKpiTelemetry.enqueueFirstDocumentProbe(client, "spa", null, null, null);
    drain();
    int afterFirst = db.connectionsOpened;
    AccountingKpiTelemetry.enqueueFirstDocumentProbe(client, "spa", null, null, null);
    drain();

    assertEquals(1, afterFirst);
    assertEquals("the throttled probe must not open a connection", afterFirst,
        db.connectionsOpened);
  }

  @Test
  public void aDifferentTenantIsNotThrottledByAnother() throws Exception {
    db.reviewRequired = true;
    db.processedDocument = false;
    String other = client + "-B";

    AccountingKpiTelemetry.enqueueFirstDocumentProbe(client, "spa", null, null, null);
    AccountingKpiTelemetry.enqueueFirstDocumentProbe(other, "spa", null, null, null);
    drain();

    assertEquals(2, db.connectionsOpened);
  }

  @Test
  public void aProbeIsRunAgainOnceTheIntervalElapsed() throws Exception {
    db.reviewRequired = true;
    db.processedDocument = false;
    AccountingKpiTelemetry.enqueueFirstDocumentProbe(client, "spa", null, null, null);
    drain();

    Map<String, Long> last = staticField("PROBE_LAST");
    last.put(client, System.currentTimeMillis() - AccountingKpiTelemetry.PROBE_MIN_INTERVAL_MS - 1);
    AccountingKpiTelemetry.enqueueFirstDocumentProbe(client, "spa", null, null, null);
    drain();

    assertEquals(2, db.connectionsOpened);
  }

  @Test
  public void aTenantNotRequiringReviewIsNotProbedAgainWithinTheTtl() throws Exception {
    db.reviewRequired = false;
    AccountingKpiTelemetry.enqueueFirstDocumentProbe(client, "spa", null, null, null);
    drain();
    Map<String, Long> last = staticField("PROBE_LAST");
    last.remove(client); // isolate the negative cache from the throttle

    AccountingKpiTelemetry.enqueueFirstDocumentProbe(client, "spa", null, null, null);
    drain();

    assertEquals(1, db.connectionsOpened);
  }

  @Test
  public void aTenantNotRequiringReviewIsProbedAgainAfterTheTtl() throws Exception {
    db.reviewRequired = false;
    AccountingKpiTelemetry.enqueueFirstDocumentProbe(client, "spa", null, null, null);
    drain();
    Map<String, Long> last = staticField("PROBE_LAST");
    Map<String, Long> notRequired = staticField("NOT_REQUIRED_UNTIL");
    last.remove(client);
    notRequired.put(client, System.currentTimeMillis() - 1); // the TTL has elapsed

    db.reviewRequired = true;
    db.processedDocument = true;
    AccountingKpiTelemetry.enqueueFirstDocumentProbe(client, "spa", null, null, null);
    drain();

    assertEquals(2, db.connectionsOpened);
    assertEquals(events.toString(), 1, events.size());
  }

  private long ownerReads() {
    return db.sqls.stream().filter(q -> q.contains("FROM ad_user")).count();
  }

  @Test
  public void aFalseInternalAnswerIsReReadAfterItsTtlButATrueOneNever() throws Exception {
    db.reviewRequired = true;
    db.ownerEmail = "owner@customer.com";
    AccountingKpiTelemetry.productiveEnvironmentCreated(client);
    drain();
    AccountingKpiTelemetry.productiveEnvironmentCreated(client);
    drain();
    assertEquals("false is cached within the TTL", 1, ownerReads());

    Map<String, Long> until = staticField("INTERNAL_UNTIL");
    until.put(client, System.currentTimeMillis() - 1); // the 1 h TTL has elapsed
    db.ownerEmail = "owner@etendo.ai";
    AccountingKpiTelemetry.productiveEnvironmentCreated(client);
    drain();
    assertEquals("false is re-read after the TTL", 2, ownerReads());
    assertEquals(Boolean.TRUE, events.get(2).getProperties().get("internal"));

    until.put(client, 0L);
    AccountingKpiTelemetry.productiveEnvironmentCreated(client);
    drain();
    assertEquals("true is never re-read", 2, ownerReads());
    assertEquals(Boolean.TRUE, events.get(3).getProperties().get("internal"));
  }

  @Test
  public void theSystemClientIsProbedAtMostOncePer60sAndThenNegativelyCached() throws Exception {
    db.reviewRequired = false;
    AccountingKpiTelemetry.enqueueFirstDocumentProbe("0", "spa", null, null, null);
    AccountingKpiTelemetry.enqueueFirstDocumentProbe("0", "spa", null, null, null);
    drain();
    assertEquals("throttled within 60 s", 1, db.connectionsOpened);

    Map<String, Long> last = staticField("PROBE_LAST");
    last.remove("0");
    AccountingKpiTelemetry.enqueueFirstDocumentProbe("0", "spa", null, null, null);
    drain();

    assertEquals("negatively cached after the first probe", 1, db.connectionsOpened);
    assertTrue(events.isEmpty());
  }

  @Test
  public void aRefusalForTheSystemClientDoesNotThrow() throws Exception {
    JSONObject body = new JSONObject().put("messageKeys", new JSONArray().put(PERIOD_KEY));

    AccountingKpiTelemetry.observeRefusal("0", "spa", null, null, null, body, null);
    drain();

    assertTrue(events.size() <= 1);
  }

  @Test
  public void theProbeRunsOnlyAfterTheConfiguredDelay() throws Exception {
    db.reviewRequired = true;
    db.processedDocument = true;
    AccountingKpiTelemetry.setProbeDelayMillisForTests(60_000);

    AccountingKpiTelemetry.enqueueFirstDocumentProbe(client, "spa", null, null, null);
    delayer().submit(() -> { }).get(10, TimeUnit.SECONDS);
    drain();
    assertEquals("not run immediately", 0, db.connectionsOpened);
    assertTrue(events.isEmpty());

    AccountingKpiTelemetry.setProbeDelayMillisForTests(0);
    String other = client + "-B";
    AccountingKpiTelemetry.enqueueFirstDocumentProbe(other, "spa", null, null, null);
    drain();
    assertEquals("runs once the delay is 0", 1, db.connectionsOpened);
  }

  // ── Connection handling ──────────────────────────────────────────────────────────────────────

  @Test
  public void internalIsNotCachedWhenThereIsNoConnection() throws Exception {
    db.reviewRequired = true;
    db.ownerEmail = "owner@etendo.ai";
    POOL.set(null);
    AccountingKpiTelemetry.productiveEnvironmentCreated(client);
    drain();
    assertEquals(Boolean.FALSE, events.get(0).getProperties().get("internal"));

    POOL.set(poolFor(db));
    AccountingKpiTelemetry.productiveEnvironmentCreated(client);
    drain();

    assertEquals(events.toString(), 2, events.size());
    assertEquals("the no-connection answer must not have been cached", Boolean.TRUE,
        events.get(1).getProperties().get("internal"));
  }

  @Test
  public void onceInternalIsCachedANewEmitOpensNoConnection() throws Exception {
    db.ownerEmail = "owner@customer.com";
    AccountingKpiTelemetry.productiveEnvironmentCreated(client);
    drain();
    int opened = db.connectionsOpened;

    AccountingKpiTelemetry.productiveEnvironmentCreated(client);
    drain();

    assertEquals(2, events.size());
    assertEquals(opened, db.connectionsOpened);
  }

  @Test
  public void theConnectionIsClosedWhenSetAutoCommitThrows() throws Exception {
    Connection conn = mock(Connection.class);
    org.mockito.Mockito.doThrow(new SQLException("no autocommit")).when(conn).setAutoCommit(true);
    ExternalConnectionPool pool = mock(ExternalConnectionPool.class);
    when(pool.getConnection()).thenReturn(conn);
    POOL.set(pool);

    AccountingKpiTelemetry.productiveEnvironmentCreated(client);
    drain();

    org.mockito.Mockito.verify(conn).close();
    assertTrue(events.isEmpty());
  }

  @Test
  public void theOwnerQueryOrdersByCreatedThenUserId() throws Exception {
    db.reviewRequired = true;
    db.ownerEmail = "owner@etendo.ai";

    AccountingKpiTelemetry.productiveEnvironmentCreated(client);
    drain();

    assertTrue(db.sqls.toString(), db.sqls.stream()
        .anyMatch(sql -> sql.contains("ORDER BY created, ad_user_id")));
  }

  private ExternalConnectionPool poolFor(FakeDb fake) throws Exception {
    ExternalConnectionPool pool = mock(ExternalConnectionPool.class);
    when(pool.getConnection()).thenAnswer(i -> fake.connection());
    return pool;
  }

  // ── REST hooks ───────────────────────────────────────────────────────────────────────────────

  @Test
  public void aSuccessfulRestWriteQueuesTheProbeWithTheRequestCoordinates() throws Exception {
    db.reviewRequired = true;
    db.processedDocument = true;
    try (MockedStatic<OBContext> ctx = clientContext()) {
      AccountingKpiTelemetry.beginRestRequest("sales-invoice", "header", "POST", "post");
      AccountingKpiTelemetry.restWriteSucceeded(200);
      drain();
    }

    assertEquals(events.toString(), 1, events.size());
    Map<String, Object> props = events.get(0).getProperties();
    assertEquals("spa", props.get("source"));
    assertEquals("post", props.get("operation"));
    assertEquals("sales-invoice", props.get("specName"));
    assertEquals("header", props.get("entity"));
  }

  @Test
  public void aBatchEndpointReportsSourceBatchWithoutEntityCoordinates() throws Exception {
    db.reviewRequired = true;
    db.processedDocument = true;
    try (MockedStatic<OBContext> ctx = clientContext()) {
      AccountingKpiTelemetry.beginRestRequest("sales-invoice", "header", "POST", null);
      AccountingKpiTelemetry.markRestBatch();
      AccountingKpiTelemetry.restWriteSucceeded(200);
      drain();
    }

    Map<String, Object> props = events.get(0).getProperties();
    assertEquals("batch", props.get("source"));
    assertFalse(props.containsKey("specName"));
    assertFalse(props.containsKey("entity"));
  }

  @Test
  public void readsFailuresAndUnnotedRequestsDoNotQueueTheProbe() throws Exception {
    db.reviewRequired = true;
    db.processedDocument = true;
    try (MockedStatic<OBContext> ctx = clientContext()) {
      AccountingKpiTelemetry.restWriteSucceeded(200); // no request noted
      AccountingKpiTelemetry.beginRestRequest("s", "e", "GET", null);
      AccountingKpiTelemetry.restWriteSucceeded(200);
      AccountingKpiTelemetry.beginRestRequest("s", "e", "POST", null);
      AccountingKpiTelemetry.restWriteSucceeded(500);
      AccountingKpiTelemetry.endRestRequest();
      AccountingKpiTelemetry.restWriteSucceeded(200); // forgotten
      drain();
    }

    assertTrue(events.toString(), events.isEmpty());
    assertEquals(0, db.connectionsOpened);
  }

  @Test
  public void aRefusedRestResponseIsObservedWithTheRequestCoordinates() throws Exception {
    JSONObject body = new JSONObject().put("error",
        new JSONObject().put("messageKeys", new JSONArray().put(OTHER_PERIOD_KEY)));
    try (MockedStatic<OBContext> ctx = clientContext()) {
      AccountingKpiTelemetry.beginRestRequest("sales-invoice", "header", "PUT", "complete");
      AccountingKpiTelemetry.observeRestResponse(409, body);
      AccountingKpiTelemetry.observeRestResponse(200, body); // not an error: ignored
      AccountingKpiTelemetry.observeRestResponse(409, null);
      drain();
    }

    assertEquals(events.toString(), 1, events.size());
    Map<String, Object> props = events.get(0).getProperties();
    assertEquals(OTHER_PERIOD_KEY, props.get("errorCode"));
    assertEquals("spa", props.get("source"));
    assertEquals("complete", props.get("operation"));
    assertEquals("sales-invoice", props.get("specName"));
  }

  // ── Async batch rows ─────────────────────────────────────────────────────────────────────────

  @Test
  public void anAsyncBatchRowWithAPeriodRefusalEmitsWithSourceBatch() throws Exception {
    JSONObject row = new JSONObject().put("committed", false).put("error", new JSONObject()
        .put("messageKeys", new JSONArray().put(PERIOD_KEY)));

    AccountingKpiTelemetry.observeBatchRow(row, client);
    AccountingKpiTelemetry.observeBatchRow(new JSONObject().put("committed", true)
        .put("error", new JSONObject().put("messageKeys", new JSONArray().put(PERIOD_KEY))),
        client);
    AccountingKpiTelemetry.observeBatchRow(null, client);
    drain();

    assertEquals(events.toString(), 1, events.size());
    assertEquals("batch", events.get(0).getProperties().get("source"));
    assertEquals(PERIOD_KEY, events.get(0).getProperties().get("errorCode"));
  }

  // ── periodRefusalKeys ────────────────────────────────────────────────────────────────────────

  @Test
  public void periodRefusalKeysFindsTheKeyInAFlatBody() throws Exception {
    JSONObject body = new JSONObject().put("messageKeys", new JSONArray().put("Other")
        .put(PERIOD_KEY));
    assertEquals(List.of(PERIOD_KEY), AccountingKpiTelemetry.periodRefusalKeys(body));
  }

  @Test
  public void periodRefusalKeysFindsTheKeyUnderError() throws Exception {
    JSONObject body = new JSONObject().put("error",
        new JSONObject().put("messageKeys", new JSONArray().put(OTHER_PERIOD_KEY)));
    assertEquals(List.of(OTHER_PERIOD_KEY), AccountingKpiTelemetry.periodRefusalKeys(body));
  }

  @Test
  public void periodRefusalKeysFindsTheKeyUnderTheBatchEnvelope() throws Exception {
    JSONObject body = new JSONObject().put("error", new JSONObject().put("detail",
        new JSONObject().put("error",
            new JSONObject().put("messageKeys", new JSONArray().put(PERIOD_KEY)))));
    assertEquals(List.of(PERIOD_KEY), AccountingKpiTelemetry.periodRefusalKeys(body));
  }

  @Test
  public void periodRefusalKeysReturnsOneKeyPerFailedItemOfAnActionBatch() throws Exception {
    JSONObject body = new JSONObject().put("results", new JSONArray()
        .put(new JSONObject().put("status", "ok"))
        .put(new JSONObject().put("error",
            new JSONObject().put("messageKeys", new JSONArray().put(PERIOD_KEY))))
        .put(new JSONObject().put("error",
            new JSONObject().put("messageKeys", new JSONArray().put(OTHER_PERIOD_KEY))))
        .put(new JSONObject().put("error",
            new JSONObject().put("messageKeys", new JSONArray().put("Unrelated")))));
    assertEquals(List.of(PERIOD_KEY, OTHER_PERIOD_KEY),
        AccountingKpiTelemetry.periodRefusalKeys(body));
  }

  @Test
  public void periodRefusalKeysIsEmptyWithoutAPeriodKey() throws Exception {
    assertTrue(AccountingKpiTelemetry.periodRefusalKeys(new JSONObject()).isEmpty());
    assertTrue(AccountingKpiTelemetry.periodRefusalKeys(
        new JSONObject().put("messageKeys", new JSONArray().put("Unrelated"))).isEmpty());
  }

  // ── Helpers ──────────────────────────────────────────────────────────────────────────────────

  private MockedStatic<OBContext> clientContext() {
    MockedStatic<OBContext> ctx = mockStatic(OBContext.class);
    OBContext context = mock(OBContext.class);
    Client current = mock(Client.class);
    when(current.getId()).thenReturn(client);
    when(context.getCurrentClient()).thenReturn(current);
    ctx.when(OBContext::getOBContext).thenReturn(context);
    return ctx;
  }

  private static void assertNoEmail(Map<String, Object> props) {
    for (Object value : props.values()) {
      assertFalse("an e-mail leaked into the props: " + value, String.valueOf(value).contains("@"));
    }
  }

  private static ThreadPoolExecutor sender() throws Exception {
    Field field = AccountingKpiTelemetry.class.getDeclaredField("SENDER");
    field.setAccessible(true);
    return (ThreadPoolExecutor) field.get(null);
  }

  /** Waits until everything queued so far has run (the sender is a single FIFO thread). */
  private static void drain() throws Exception {
    delayer().submit(() -> { }).get(10, TimeUnit.SECONDS);
    sender().submit(() -> { }).get(10, TimeUnit.SECONDS);
  }

  private static ScheduledThreadPoolExecutor delayer() throws Exception {
    Field field = AccountingKpiTelemetry.class.getDeclaredField("DELAYER");
    field.setAccessible(true);
    return (ScheduledThreadPoolExecutor) field.get(null);
  }

  @SuppressWarnings("unchecked")
  private static <T> T staticField(String name) throws Exception {
    Field field = AccountingKpiTelemetry.class.getDeclaredField(name);
    field.setAccessible(true);
    return (T) field.get(null);
  }

  private static void onSender(Runnable work) throws Exception {
    sender().submit(work).get(10, TimeUnit.SECONDS);
  }

  /** A scripted database: answers by the SQL the telemetry runs. */
  private static final class FakeDb {
    boolean reviewRequired;
    boolean processedDocument;
    String ownerEmail;
    boolean failConnection;
    boolean failMarkerInsert;
    volatile int connectionsOpened;
    final List<String> sqls = new CopyOnWriteArrayList<>();
    final Set<String> markers = java.util.Collections.synchronizedSet(new HashSet<>());

    Connection connection() throws SQLException {
      connectionsOpened++;
      Connection conn = mock(Connection.class);
      when(conn.prepareStatement(anyString())).thenAnswer(i -> statement(i.getArgument(0)));
      return conn;
    }

    private PreparedStatement statement(String sql) throws SQLException {
      sqls.add(sql);
      PreparedStatement ps = mock(PreparedStatement.class);
      Map<Integer, String> params = new HashMap<>();
      org.mockito.Mockito.doAnswer(i -> params.put(i.getArgument(0), i.getArgument(1)))
          .when(ps).setString(org.mockito.ArgumentMatchers.anyInt(), anyString());
      when(ps.executeUpdate()).thenAnswer(i -> {
        if (failMarkerInsert) {
          throw new SQLException("insert refused");
        }
        markers.add(params.get(2));
        return 1;
      });
      when(ps.executeQuery()).thenAnswer(i -> result(sql, params));
      return ps;
    }

    private ResultSet result(String sql, Map<Integer, String> params) throws SQLException {
      ResultSet rs = mock(ResultSet.class);
      boolean hit;
      if (sql.contains("FROM ad_user")) {
        hit = ownerEmail != null;
        when(rs.getString(1)).thenReturn(ownerEmail);
      } else if (sql.contains("value = 'Y'")) {
        hit = reviewRequired;
      } else if (sql.contains("FROM ad_preference")) {
        hit = markers.contains(params.get(1));
      } else {
        hit = processedDocument;
      }
      when(rs.next()).thenReturn(hit);
      return rs;
    }
  }
}
