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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.dal.core.OBContext;
import org.openbravo.model.ad.access.User;
import org.openbravo.model.ad.system.Client;

import com.etendoerp.go.usage.IsolatedDalRead;

/**
 * ETP-6228: the common identity of backend events.
 *
 * @covers com.etendoerp.go.schemaforge.telemetry.NeoTelemetryIdentity
 * @covers com.etendoerp.go.schemaforge.telemetry.RuntimeNeoTelemetryIdentityResolver
 * @covers com.etendoerp.go.schemaforge.telemetry.NeoTelemetryService
 * @covers com.etendoerp.go.schemaforge.telemetry.MixpanelNeoTelemetrySink
 * @covers com.etendoerp.go.usage.IsolatedDalRead
 */
class NeoTelemetryIdentityTest {

  private static final NeoTelemetryIdentity SESSION =
      new NeoTelemetryIdentity("U1", "C1", "OWNER-A", "productive");

  private static final MixpanelNeoTelemetrySink SINK = new MixpanelNeoTelemetrySink(
      new MixpanelNeoTelemetryConfig(true, "token-123", null, null, 1000));

  /** (a) An event built inside a session carries every id, and account_id with ad_client_id. */
  @Test
  void anEventBuiltInASessionCarriesTheIdentity() throws Exception {
    RecordingSink sink = new RecordingSink();
    NeoTelemetryService service = service(sink, (client, user) -> SESSION);

    service.emit(NeoTelemetryEvents.BACKEND_WRITE_OPERATION_COMPLETED, props("status", "success"));

    JSONObject sent = sent(sink.events.get(0));
    assertEquals("U1", sent.getString("ad_user_id"));
    assertEquals("C1", sent.getString("ad_client_id"));
    assertEquals("C1", sent.getString("account_id"));
    assertEquals("OWNER-A", sent.getString("etgo_account_id"));
    assertEquals("productive", sent.getString("tenant_type"));
    assertEquals("success", sent.getString("status"));
  }

  /** (b) An event with no session carries none of them. */
  @Test
  void anEventWithoutASessionCarriesNoIdentity() throws Exception {
    RecordingSink sink = new RecordingSink();
    NeoTelemetryService service = service(sink, NeoTelemetryIdentityResolver.none());

    service.emit(NeoTelemetryEvents.BACKEND_WRITE_OPERATION_COMPLETED, props("status", "success"));

    JSONObject sent = sent(sink.events.get(0));
    for (String key : new String[] {"ad_user_id", "ad_client_id", "account_id", "etgo_account_id",
        "tenant_type"}) {
      assertFalse(sent.has(key), key);
    }
  }

  /** (c) The identity captured on the calling thread survives the hand-off to a sender thread. */
  @Test
  void theIdentityIsHandedOffToTheSenderThread() throws Exception {
    RecordingSink sink = new RecordingSink();
    NeoTelemetryService service = service(sink, NeoTelemetryIdentityResolver.none());
    AtomicReference<Throwable> failure = new AtomicReference<>();

    Thread sender = new Thread(() -> {
      try {
        NeoTelemetryIdentity.runWith(SESSION, () -> service.emit(
            NeoTelemetryEvents.BACKEND_CONTACT_CREATED, props("channel", "api"), "C1"));
        // Outside runWith the thread has no session again.
        service.emit(NeoTelemetryEvents.BACKEND_CONTACT_CREATED, props("channel", "api"), "C1");
      } catch (Throwable t) { // NOSONAR -- reported to the test thread
        failure.set(t);
      }
    });
    sender.start();
    sender.join();

    assertNull(failure.get());
    assertSame(SESSION, sink.events.get(0).getIdentity());
    assertSame(NeoTelemetryIdentity.NONE, sink.events.get(1).getIdentity());
    assertNull(NeoTelemetryIdentity.handedOff());
  }

  /** (d) A caller property with an identity key cannot replace the resolved value. */
  @Test
  void aCallerPropertyCannotReplaceTheIdentity() throws Exception {
    Map<String, Object> properties = props("ad_client_id", "OTHER-CLIENT");
    properties.put("etgo_account_id", "OTHER-ACCOUNT");
    properties.put("tenant_type", "demo");
    NeoTelemetryEvent event = new NeoTelemetryEvent(
        NeoTelemetryEvents.BACKEND_WRITE_OPERATION_COMPLETED, properties, Instant.now(), "C1",
        SESSION);

    JSONObject sent = sent(event);

    assertEquals("C1", sent.getString("ad_client_id"));
    assertEquals("OWNER-A", sent.getString("etgo_account_id"));
    assertEquals("productive", sent.getString("tenant_type"));
  }

  /** (e) The client facts are cached for a short TTL only: an owner change shows up after it. */
  @Test
  void aChangedOwnerShowsUpAfterTheTtl() {
    AtomicReference<String> owner = new AtomicReference<>("OWNER-A");
    AtomicInteger lookups = new AtomicInteger();
    AtomicLong now = new AtomicLong(1_000L);
    RuntimeNeoTelemetryIdentityResolver resolver = new RuntimeNeoTelemetryIdentityResolver(
        client -> {
          lookups.incrementAndGet();
          return new RuntimeNeoTelemetryIdentityResolver.ClientFacts(owner.get(), "demo");
        }, now::get);

    assertEquals("OWNER-A", property(resolver.resolve("C1", "U1"), "etgo_account_id"));
    owner.set("OWNER-B");
    assertEquals("OWNER-A", property(resolver.resolve("C1", "U1"), "etgo_account_id"));
    assertEquals(1, lookups.get());

    now.addAndGet(RuntimeNeoTelemetryIdentityResolver.TTL_MILLIS + 1);
    assertEquals("OWNER-B", property(resolver.resolve("C1", "U1"), "etgo_account_id"));
    assertEquals(2, lookups.get());
  }

  /** The three facts of an event come from the same client, never one cached and one fresh. */
  @Test
  void theFactsOfAnotherClientAreNeverMixed() {
    RuntimeNeoTelemetryIdentityResolver resolver = new RuntimeNeoTelemetryIdentityResolver(
        client -> new RuntimeNeoTelemetryIdentityResolver.ClientFacts("OWNER-" + client,
            "C1".equals(client) ? "demo" : "productive"), System::currentTimeMillis);

    NeoTelemetryIdentity first = resolver.resolve("C1", "U1");
    NeoTelemetryIdentity second = resolver.resolve("C2", "U1");

    assertEquals("OWNER-C1", property(first, "etgo_account_id"));
    assertEquals("demo", property(first, "tenant_type"));
    assertEquals("OWNER-C2", property(second, "etgo_account_id"));
    assertEquals("productive", property(second, "tenant_type"));
  }

  @Test
  void aFailedLookupLeavesTheFactsOutAndIsRetried() {
    AtomicInteger lookups = new AtomicInteger();
    RuntimeNeoTelemetryIdentityResolver resolver = new RuntimeNeoTelemetryIdentityResolver(
        client -> {
          lookups.incrementAndGet();
          throw new IllegalStateException("db down");
        }, System::currentTimeMillis);

    NeoTelemetryIdentity identity = resolver.resolve("C1", "U1");
    resolver.resolve("C1", "U1");

    assertEquals("C1", property(identity, "ad_client_id"));
    assertNull(property(identity, "etgo_account_id"));
    assertNull(property(identity, "tenant_type"));
    assertEquals(2, lookups.get());
  }

  /**
   * ETP-6386: the runtime lookup runs in its own DAL session with a 5 s wait, never on the
   * emitting thread's session (a request's transaction, or a sender thread that never closes it).
   */
  @Test
  void theRuntimeLookupRunsInAnIsolatedRead() {
    try (MockedStatic<IsolatedDalRead> isolated = Mockito.mockStatic(IsolatedDalRead.class)) {
      isolated.when(() -> IsolatedDalRead.call(Mockito.<Supplier<Object>>any(), Mockito.eq(5_000L)))
          .thenReturn(new RuntimeNeoTelemetryIdentityResolver.ClientFacts("OWNER-A", "demo"));
      RuntimeNeoTelemetryIdentityResolver resolver = new RuntimeNeoTelemetryIdentityResolver(
          RuntimeNeoTelemetryIdentityResolver::lookupClientFacts, System::currentTimeMillis);

      NeoTelemetryIdentity identity = resolver.resolve("C-ISOLATED", "U1");

      assertEquals("OWNER-A", property(identity, "etgo_account_id"));
      assertEquals("demo", property(identity, "tenant_type"));
      isolated.verify(() -> IsolatedDalRead.call(Mockito.<Supplier<Object>>any(),
          Mockito.eq(5_000L)));
    }
  }

  /** ETP-6386: a timed-out isolated read yields no facts, is not cached, and the event still goes. */
  @Test
  void aTimedOutIsolatedLookupLeavesTheFactsOutAndIsRetried() {
    try (MockedStatic<IsolatedDalRead> isolated = Mockito.mockStatic(IsolatedDalRead.class)) {
      isolated.when(() -> IsolatedDalRead.call(Mockito.<Supplier<Object>>any(), Mockito.anyLong()))
          .thenThrow(new IllegalStateException("Isolated read timed out after 5000 ms"));
      RuntimeNeoTelemetryIdentityResolver resolver = new RuntimeNeoTelemetryIdentityResolver(
          RuntimeNeoTelemetryIdentityResolver::lookupClientFacts, System::currentTimeMillis);

      NeoTelemetryIdentity identity = resolver.resolve("C-SLOW", "U1");
      resolver.resolve("C-SLOW", "U1");

      assertEquals("C-SLOW", property(identity, "ad_client_id"));
      assertNull(property(identity, "etgo_account_id"));
      isolated.verify(() -> IsolatedDalRead.call(Mockito.<Supplier<Object>>any(),
          Mockito.anyLong()), Mockito.times(2));
    }
  }

  @Test
  void theSystemClientAndUserAreNoSession() {
    RuntimeNeoTelemetryIdentityResolver resolver = new RuntimeNeoTelemetryIdentityResolver(
        client -> new RuntimeNeoTelemetryIdentityResolver.ClientFacts("OWNER", "demo"),
        System::currentTimeMillis);

    assertSame(NeoTelemetryIdentity.NONE, resolver.resolve("0", "0"));
  }

  @Test
  void theCacheIsBounded() {
    AtomicInteger lookups = new AtomicInteger();
    RuntimeNeoTelemetryIdentityResolver resolver = new RuntimeNeoTelemetryIdentityResolver(
        client -> {
          lookups.incrementAndGet();
          return new RuntimeNeoTelemetryIdentityResolver.ClientFacts("OWNER", "demo");
        }, () -> 0L);

    for (int i = 0; i <= RuntimeNeoTelemetryIdentityResolver.MAX_ENTRIES; i++) {
      resolver.resolve("C" + i, "U1");
    }
    resolver.resolve("C0", "U1");

    // C0 was evicted by the bound, so it is looked up again.
    assertEquals(RuntimeNeoTelemetryIdentityResolver.MAX_ENTRIES + 2, lookups.get());
  }

  /** ETP-6386: the lookup runs outside the lock, so a slow miss never blocks another client. */
  @Test
  void aSlowMissForOneClientDoesNotBlockAnotherClient() throws Exception {
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    RuntimeNeoTelemetryIdentityResolver resolver = new RuntimeNeoTelemetryIdentityResolver(
        client -> {
          if ("SLOW".equals(client)) {
            entered.countDown();
            try {
              release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
          }
          return new RuntimeNeoTelemetryIdentityResolver.ClientFacts("OWNER-" + client, "demo");
        }, System::currentTimeMillis);
    Thread slow = new Thread(() -> resolver.resolve("SLOW", "U1"));
    slow.start();
    try {
      assertTrue(entered.await(5, TimeUnit.SECONDS));

      NeoTelemetryIdentity fast = org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(
          Duration.ofSeconds(5), () -> resolver.resolve("FAST", "U1"));

      assertEquals("OWNER-FAST", property(fast, "etgo_account_id"));
    } finally {
      release.countDown();
      slow.join(5_000);
    }
  }

  /** ETP-6386: access order, so a recently used client survives the 256 bound. */
  @Test
  void aRecentlyUsedClientSurvivesTheBound() {
    AtomicInteger lookups = new AtomicInteger();
    RuntimeNeoTelemetryIdentityResolver resolver = new RuntimeNeoTelemetryIdentityResolver(
        client -> {
          lookups.incrementAndGet();
          return new RuntimeNeoTelemetryIdentityResolver.ClientFacts("OWNER", "demo");
        }, () -> 0L);
    int max = RuntimeNeoTelemetryIdentityResolver.MAX_ENTRIES;
    for (int i = 0; i < max; i++) {
      resolver.resolve("C" + i, "U1");
    }
    resolver.resolve("C0", "U1"); // a hit: C0 is now the most recently used
    resolver.resolve("C" + max, "U1"); // overflows the bound: evicts C1, not C0
    assertEquals(max + 1, lookups.get());

    resolver.resolve("C0", "U1");
    assertEquals(max + 1, lookups.get(), "C0 was kept");
    resolver.resolve("C1", "U1");
    assertEquals(max + 2, lookups.get(), "C1 was the least recently used and was evicted");
  }

  /** ETP-6386: a system user never becomes ad_user_id, even for a real client. */
  @Test
  void aSystemUserOfARealClientCarriesNoUserId() {
    NeoTelemetryIdentity identity = factsResolver().resolve("C1", "0");

    assertNull(property(identity, "ad_user_id"));
    assertEquals("C1", property(identity, "ad_client_id"));
  }

  /**
   * ETP-6386: after commitAndClose the thread has no session (no OBContext); the facts still come
   * from the isolated read, so the event carries etgo_account_id and tenant_type.
   */
  @Test
  void anEmissionWithNoSessionStillCarriesTheClientFacts() {
    try (MockedStatic<IsolatedDalRead> isolated = Mockito.mockStatic(IsolatedDalRead.class);
        MockedStatic<OBContext> ob = Mockito.mockStatic(OBContext.class)) {
      ob.when(OBContext::getOBContext).thenReturn(null);
      isolated.when(() -> IsolatedDalRead.call(Mockito.<Supplier<Object>>any(), Mockito.eq(5_000L)))
          .thenReturn(new RuntimeNeoTelemetryIdentityResolver.ClientFacts("OWNER-A", "productive"));
      RuntimeNeoTelemetryIdentityResolver resolver = new RuntimeNeoTelemetryIdentityResolver(
          RuntimeNeoTelemetryIdentityResolver::lookupClientFacts, System::currentTimeMillis);

      NeoTelemetryIdentity identity = resolver.resolve("C-AFTER-COMMIT", null);

      assertEquals("OWNER-A", property(identity, "etgo_account_id"));
      assertEquals("productive", property(identity, "tenant_type"));
      assertNull(property(identity, "ad_user_id"));
    }
  }

  // ── doResolve: the session from OBContext ─────────────────────────────────────────────────

  private static RuntimeNeoTelemetryIdentityResolver factsResolver() {
    return new RuntimeNeoTelemetryIdentityResolver(
        client -> new RuntimeNeoTelemetryIdentityResolver.ClientFacts("OWNER-" + client, "demo"),
        System::currentTimeMillis);
  }

  private static OBContext sessionContext(String clientId, String userId) {
    OBContext context = Mockito.mock(OBContext.class);
    if (clientId != null) {
      Client client = Mockito.mock(Client.class);
      Mockito.when(client.getId()).thenReturn(clientId);
      Mockito.when(context.getCurrentClient()).thenReturn(client);
    }
    if (userId != null) {
      User user = Mockito.mock(User.class);
      Mockito.when(user.getId()).thenReturn(userId);
      Mockito.when(context.getUser()).thenReturn(user);
    }
    return context;
  }

  @Test
  void theSessionSuppliesTheUserAndClientWhenTheEventNamesNone() {
    try (MockedStatic<OBContext> ob = Mockito.mockStatic(OBContext.class)) {
      OBContext session = sessionContext("C1", "U1");
      ob.when(OBContext::getOBContext).thenReturn(session);

      NeoTelemetryIdentity identity = factsResolver().resolve(null, null);

      assertEquals("U1", property(identity, "ad_user_id"));
      assertEquals("C1", property(identity, "ad_client_id"));
      assertEquals("OWNER-C1", property(identity, "etgo_account_id"));
    }
  }

  @Test
  void theSessionUserIsNotUsedForAnotherClient() {
    try (MockedStatic<OBContext> ob = Mockito.mockStatic(OBContext.class)) {
      OBContext session = sessionContext("C1", "U1");
      ob.when(OBContext::getOBContext).thenReturn(session);

      NeoTelemetryIdentity named = factsResolver().resolve("C2", null);
      NeoTelemetryIdentity sameClient = factsResolver().resolve("C1", null);
      NeoTelemetryIdentity explicitUser = factsResolver().resolve("C2", "U2");

      assertEquals("C2", property(named, "ad_client_id"));
      assertNull(property(named, "ad_user_id"));
      assertEquals("OWNER-C2", property(named, "etgo_account_id"));
      assertEquals("U1", property(sameClient, "ad_user_id"));
      assertEquals("U2", property(explicitUser, "ad_user_id"));
    }
  }

  @Test
  void aSystemSessionOrNoSessionCarriesNothing() {
    try (MockedStatic<OBContext> ob = Mockito.mockStatic(OBContext.class)) {
      OBContext session = sessionContext("0", "0");
      ob.when(OBContext::getOBContext).thenReturn(session);
      assertSame(NeoTelemetryIdentity.NONE, factsResolver().resolve(null, null));

      ob.when(OBContext::getOBContext).thenReturn(null);
      assertSame(NeoTelemetryIdentity.NONE, factsResolver().resolve(null, null));
    }
  }

  private static String property(NeoTelemetryIdentity identity, String key) {
    Object value = identity.toProperties().get(key);
    return value == null ? null : value.toString();
  }

  private static NeoTelemetryService service(NeoTelemetrySink sink,
      NeoTelemetryIdentityResolver resolver) {
    return new NeoTelemetryService(sink, System::nanoTime, java.time.Clock.systemUTC(), resolver);
  }

  private static JSONObject sent(NeoTelemetryEvent event) throws Exception {
    String body = SINK.buildRequestBody(event);
    String decoded = URLDecoder.decode(body.substring("data=".length()),
        StandardCharsets.UTF_8.name());
    return new JSONArray(decoded).getJSONObject(0).getJSONObject("properties");
  }

  private static Map<String, Object> props(String key, Object value) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put(key, value);
    return map;
  }

  private static final class RecordingSink implements NeoTelemetrySink {
    private final List<NeoTelemetryEvent> events = new ArrayList<>();

    @Override
    public void emit(NeoTelemetryEvent event) {
      events.add(event);
    }
  }
}
