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
 * All portions are Copyright (C) 2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.schemaforge;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import javax.servlet.ReadListener;
import javax.servlet.ServletInputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.openbravo.dal.core.OBContext;
import org.openbravo.model.ad.access.Role;
import org.openbravo.model.ad.access.User;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.enterprise.Organization;

import com.etendoerp.go.usageevents.UsageEvent;
import com.etendoerp.go.usageevents.UsageEventTypes;

/**
 * Unit specs for {@link NeoUsageEventEndpoint} — {@code POST /sws/neo/usage} (ETP-5462, contract in
 * {@code docs/neo-headless.md} §4.15).
 *
 * <p>The endpoint is built over its two seams: a capturing sink instead of
 * {@code UsageEventRecorder.record}, and a settable clock, so clamping and the rate-limit window are
 * asserted exactly and nothing reaches a writer thread. {@code OBContext} is mocked statically (null
 * by default), because the who-columns must come from it and never from the body.</p>
 *
 * <p>The contract under test is "drop, don't fail": only a body that is not an object with an
 * {@code events} array is a 400 (and an oversized one a 413); every per-event or per-property
 * problem drops just that event or property and the answer is still {@code 202}.</p>
 */
@SuppressWarnings("java:S2187") // test methods live in the @Nested classes
class NeoUsageEventEndpointTest {

  private static final long NOW = Instant.parse("2026-09-23T12:00:00Z").toEpochMilli();
  private static final String KNOWN = UsageEventTypes.AI_AGENT_MESSAGE;

  private static final String CLIENT_ID = "A1B2C3D4E5F60718293A4B5C6D7E8F90";
  private static final String ORG_ID = "0A1B2C3D4E5F60718293A4B5C6D7E8F9";
  private static final String USER_ID = "F0E1D2C3B4A5968778695A4B3C2D1E0F";
  private static final String ROLE_ID = "11112222333344445555666677778888";

  private final List<UsageEvent> sunk = new ArrayList<>();
  private final AtomicLong clock = new AtomicLong(NOW);
  private NeoUsageEventEndpoint endpoint;
  private MockedStatic<OBContext> obContextStatic;

  @BeforeEach
  void setUp() {
    obContextStatic = mockStatic(OBContext.class);
    obContextStatic.when(OBContext::getOBContext).thenReturn(null);
    endpoint = new NeoUsageEventEndpoint(sunk::add, clock::get);
  }

  @AfterEach
  void tearDown() {
    obContextStatic.close();
  }

  // ── helpers ───────────────────────────────────────────────────────────

  static Stream<String> stackBreakingBodies() {
    return Stream.of("{\"events\":[", "{\"a\":[",
        "{\"events\":" + "[".repeat(20_000) + "]".repeat(20_000) + "}");
  }

  private static final class BytesInputStream extends ServletInputStream {
    private final ByteArrayInputStream in;

    BytesInputStream(byte[] bytes) {
      this.in = new ByteArrayInputStream(bytes);
    }

    @Override
    public int read() {
      return in.read();
    }

    @Override
    public int read(byte[] b, int off, int len) {
      return in.read(b, off, len);
    }

    @Override
    public boolean isFinished() {
      return in.available() == 0;
    }

    @Override
    public boolean isReady() {
      return true;
    }

    @Override
    public void setReadListener(ReadListener listener) {
      // not used: the endpoint reads synchronously
    }
  }

  private static HttpServletRequest request(byte[] body, long contentLength) throws IOException {
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getContentLengthLong()).thenReturn(contentLength);
    when(request.getInputStream()).thenReturn(new BytesInputStream(body));
    return request;
  }

  private NeoResponse post(String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    return endpoint.handle(request(bytes, bytes.length));
  }

  private NeoResponse postEvents(JSONObject... events) throws Exception {
    JSONArray array = new JSONArray();
    for (JSONObject e : events) {
      array.put(e);
    }
    return post(new JSONObject().put("events", array).toString());
  }

  private NeoResponse postEvents(JSONArray events) throws Exception {
    return post(new JSONObject().put("events", events).toString());
  }

  private static JSONObject event(String type) throws Exception {
    return new JSONObject().put("eventType", type);
  }

  private static JSONObject known() throws Exception {
    return event(KNOWN);
  }

  private static void assertAccepted(NeoResponse response, int accepted, int dropped)
      throws Exception {
    assertEquals(HttpServletResponse.SC_ACCEPTED, response.getHttpStatus());
    assertEquals(accepted, response.getBody().getInt("accepted"), "accepted");
    assertEquals(dropped, response.getBody().getInt("dropped"), "dropped");
  }

  private UsageEvent single(JSONObject json) throws Exception {
    postEvents(json);
    assertEquals(1, sunk.size());
    return sunk.get(0);
  }

  // ── body shape ────────────────────────────────────────────────────────

  @Nested
  @DisplayName("body shape")
  class BodyShape {

    @ParameterizedTest
    @ValueSource(strings = { "", "   ", "not json", "[{\"eventType\":\"ai.agent.message\"}]",
        "{}", "{\"events\":{}}", "{\"events\":\"x\"}", "{\"events\":null}", "{\"events\":1}",
        "{\"events\":[{", "{\"events\":[1,2" })
    void aBodyThatIsNotAnObjectWithAnEventsArrayIs400(String body) throws Exception {
      NeoResponse response = post(body);
      assertEquals(HttpServletResponse.SC_BAD_REQUEST, response.getHttpStatus());
      assertTrue(sunk.isEmpty());
    }

    /**
     * Regression (ETP-5462): jettison 1.3 recurses without end on a body that stops inside an array,
     * and once per level on deep nesting, so both end in StackOverflowError — an Error the servlet's
     * catch (Exception) never sees. The nested case is a valid ~40 KB body, well under the cap.
     */
    @ParameterizedTest
    @MethodSource("com.etendoerp.go.schemaforge.NeoUsageEventEndpointTest#stackBreakingBodies")
    void aBodyThatBreaksTheParserStackIs400(String body) throws Exception {
      NeoResponse response = post(body);
      assertEquals(HttpServletResponse.SC_BAD_REQUEST, response.getHttpStatus());
      assertTrue(sunk.isEmpty());
    }

    @Test
    void aDeclaredContentLengthOverTheCapIs413WithoutReading() throws Exception {
      HttpServletRequest request = mock(HttpServletRequest.class);
      when(request.getContentLengthLong())
          .thenReturn((long) NeoUsageEventEndpoint.MAX_BODY_BYTES + 1);

      NeoResponse response = endpoint.handle(request);

      assertEquals(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE, response.getHttpStatus());
      verify(request, never()).getInputStream();
      assertTrue(sunk.isEmpty());
    }

    @Test
    void anUndeclaredBodyOverTheCapIs413() throws Exception {
      byte[] big = new byte[NeoUsageEventEndpoint.MAX_BODY_BYTES + 1];
      Arrays.fill(big, (byte) ' ');
      NeoResponse response = endpoint.handle(request(big, -1L));
      assertEquals(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE, response.getHttpStatus());
      assertTrue(sunk.isEmpty());
    }

    @Test
    void aBodyOfExactlyTheCapIsReadAndParsed() throws Exception {
      String prefix = "{\"events\":[{\"eventType\":\"" + KNOWN + "\"}]}";
      byte[] body = (prefix + " ".repeat(NeoUsageEventEndpoint.MAX_BODY_BYTES - prefix.length()))
          .getBytes(StandardCharsets.UTF_8);
      assertEquals(NeoUsageEventEndpoint.MAX_BODY_BYTES, body.length);

      assertAccepted(endpoint.handle(request(body, -1L)), 1, 0);
    }

    @Test
    void anEmptyEventsArrayIs202ZeroZero() throws Exception {
      assertAccepted(post("{\"events\":[]}"), 0, 0);
      assertTrue(sunk.isEmpty());
    }
  }

  // ── mapping ───────────────────────────────────────────────────────────

  @Nested
  @DisplayName("event mapping")
  class Mapping {

    @Test
    void aValidEventIsAcceptedWithEveryFieldMapped() throws Exception {
      JSONObject json = known()
          .put("source", "ai-bff").put("target", "sales-order").put("action", "complete")
          .put("outcome", "error").put("errorCode", "E42").put("durationMs", 1234)
          .put("occurredAt", "2026-09-23T11:59:00Z").put("sessionKey", "conv-1")
          .put("appVersion", "3.1.0")
          .put("properties", new JSONObject().put("model", "kimi").put("inputTokens", 1200));

      NeoResponse response = postEvents(json);

      assertAccepted(response, 1, 0);
      UsageEvent e = sunk.get(0);
      assertAll(
          () -> assertEquals(KNOWN, e.eventType()),
          () -> assertEquals(UsageEvent.SOURCE_AI_BFF, e.source()),
          () -> assertEquals("sales-order", e.target()),
          () -> assertEquals("complete", e.action()),
          () -> assertEquals(UsageEvent.OUTCOME_ERROR, e.outcome()),
          () -> assertEquals("E42", e.errorCode()),
          () -> assertEquals(1234L, e.durationMs()),
          () -> assertEquals(Instant.parse("2026-09-23T11:59:00Z"), e.occurredAt()),
          () -> assertEquals("conv-1", e.sessionKey()),
          () -> assertEquals("3.1.0", e.appVersion()),
          () -> assertEquals("{\"model\":\"kimi\",\"inputTokens\":1200}", e.properties()));
    }

    @Test
    void theEventTypeIsTrimmed() throws Exception {
      assertAccepted(postEvents(event("  " + KNOWN + " ")), 1, 0);
      assertEquals(KNOWN, sunk.get(0).eventType());
    }

    @Test
    void whoColumnsInTheBodyAreIgnoredWithoutAContext() throws Exception {
      UsageEvent e = single(known().put("clientId", CLIENT_ID).put("orgId", ORG_ID)
          .put("userId", USER_ID).put("roleId", ROLE_ID));
      assertAll(
          () -> assertEquals("0", e.clientId()),
          () -> assertEquals("0", e.orgId()),
          () -> assertEquals("100", e.userId()),
          () -> assertNull(e.roleId()));
    }

    @Test
    void whoColumnsComeFromTheContext() throws Exception {
      OBContext context = mock(OBContext.class);
      Client client = mock(Client.class);
      Organization org = mock(Organization.class);
      User user = mock(User.class);
      Role role = mock(Role.class);
      when(client.getId()).thenReturn(CLIENT_ID);
      when(org.getId()).thenReturn(ORG_ID);
      when(user.getId()).thenReturn(USER_ID);
      when(role.getId()).thenReturn(ROLE_ID);
      when(context.getCurrentClient()).thenReturn(client);
      when(context.getCurrentOrganization()).thenReturn(org);
      when(context.getUser()).thenReturn(user);
      when(context.getRole()).thenReturn(role);
      obContextStatic.when(OBContext::getOBContext).thenReturn(context);

      UsageEvent e = single(known().put("clientId", "EVIL").put("orgId", "EVIL")
          .put("userId", "EVIL").put("roleId", "EVIL"));

      assertAll(
          () -> assertEquals(CLIENT_ID, e.clientId()),
          () -> assertEquals(ORG_ID, e.orgId()),
          () -> assertEquals(USER_ID, e.userId()),
          () -> assertEquals(ROLE_ID, e.roleId()));
    }

    @ParameterizedTest
    @ValueSource(strings = { "ui", "ai-bff" })
    void theTwoCallerSourcesAreKept(String source) throws Exception {
      assertEquals(source, single(known().put("source", source)).source());
    }

    @ParameterizedTest
    @ValueSource(strings = { "backend", "mcp", "garbage", "", "UI", "AI-BFF" })
    void anyOtherSourceBecomesUi(String source) throws Exception {
      assertEquals(UsageEvent.SOURCE_UI, single(known().put("source", source)).source());
    }

    @Test
    void anAbsentSourceBecomesUi() throws Exception {
      assertEquals(UsageEvent.SOURCE_UI, single(known()).source());
    }

    @Test
    void anUnknownTypeIsForwardedButCountedDropped() throws Exception {
      assertAccepted(postEvents(event("not.agreed")), 0, 1);
      assertEquals(1, sunk.size(), "the recorder is where an unknown type is dropped and logged");
      assertEquals("not.agreed", sunk.get(0).eventType());
    }

    @Test
    void anAbsentTypeIsForwardedButCountedDropped() throws Exception {
      assertAccepted(postEvents(new JSONObject().put("target", "x")), 0, 1);
      assertEquals(1, sunk.size());
      assertNull(sunk.get(0).eventType());
    }

    @Test
    void nonObjectElementsAreDroppedAndNeverSunk() throws Exception {
      JSONArray events = new JSONArray().put("text").put(42).put(new JSONArray())
          .put(JSONObject.NULL).put(known());
      assertAccepted(postEvents(events), 1, 4);
      assertEquals(1, sunk.size());
    }

    @Test
    void stringFieldsSentAsNumbersBecomeNull() throws Exception {
      UsageEvent e = single(new JSONObject().put("eventType", 5).put("source", 1)
          .put("target", 123).put("action", true).put("outcome", 1).put("errorCode", 500)
          .put("sessionKey", 9).put("appVersion", 3.1).put("occurredAt", 1_700_000_000_000L));
      assertAll(
          () -> assertNull(e.eventType()),
          () -> assertEquals(UsageEvent.SOURCE_UI, e.source()),
          () -> assertNull(e.target()),
          () -> assertNull(e.action()),
          () -> assertNull(e.outcome()),
          () -> assertNull(e.errorCode()),
          () -> assertNull(e.sessionKey()),
          () -> assertNull(e.appVersion()),
          () -> assertEquals(Instant.ofEpochMilli(NOW), e.occurredAt()));
    }

    @ParameterizedTest
    @ValueSource(strings = { "ok", "error" })
    void knownOutcomesAreKept(String outcome) throws Exception {
      assertEquals(outcome, single(known().put("outcome", outcome)).outcome());
    }

    @ParameterizedTest
    @ValueSource(strings = { "OK", "fail", "", "success" })
    void otherOutcomesBecomeNull(String outcome) throws Exception {
      assertNull(single(known().put("outcome", outcome)).outcome());
    }
  }

  // ── batch size ────────────────────────────────────────────────────────

  @Nested
  @DisplayName("per-request cap")
  class PerRequestCap {

    private JSONArray many(int n) throws Exception {
      JSONArray events = new JSONArray();
      for (int i = 0; i < n; i++) {
        events.put(known().put("target", "t" + i));
      }
      return events;
    }

    @Test
    void exactlyTheCapIsAllAccepted() throws Exception {
      assertAccepted(postEvents(many(NeoUsageEventEndpoint.MAX_EVENTS_PER_REQUEST)), 50, 0);
    }

    @ParameterizedTest
    @ValueSource(ints = { 51, 60 })
    void eventsPastTheCapAreDroppedAndCounted(int total) throws Exception {
      NeoResponse response = postEvents(many(total));
      int max = NeoUsageEventEndpoint.MAX_EVENTS_PER_REQUEST;
      assertAccepted(response, max, total - max);
      assertEquals(max, sunk.size());
      assertEquals("t0", sunk.get(0).target());
      assertEquals("t" + (max - 1), sunk.get(max - 1).target(), "the first 50 are the ones kept");
    }
  }

  // ── occurredAt ────────────────────────────────────────────────────────

  @Nested
  @DisplayName("occurredAt")
  class OccurredAt {

    private Instant at(String value) {
      return NeoUsageEventEndpoint.occurredAt(value, NOW);
    }

    @Test
    void utcAndOffsetFormsAreParsed() {
      assertEquals(Instant.parse("2026-09-23T11:00:00Z"), at("2026-09-23T11:00:00Z"));
      assertEquals(Instant.parse("2026-09-23T11:00:00Z"), at("  2026-09-23T11:00:00Z  "));
      // Instant.parse accepts an offset since Java 12.
      assertEquals(Instant.parse("2026-09-23T11:00:00Z"), at("2026-09-23T08:00:00-03:00"));
    }

    @Test
    void theFarPastIsClampedTo24Hours() {
      assertEquals(Instant.ofEpochMilli(NOW - NeoUsageEventEndpoint.MAX_PAST_MS),
          at("2020-01-01T00:00:00Z"));
      Instant edge = Instant.ofEpochMilli(NOW - NeoUsageEventEndpoint.MAX_PAST_MS);
      assertEquals(edge, at(edge.toString()), "exactly 24h back is kept");
    }

    @Test
    void theFutureIsClampedTo5Minutes() {
      assertEquals(Instant.ofEpochMilli(NOW + NeoUsageEventEndpoint.MAX_FUTURE_MS),
          at("2030-01-01T00:00:00Z"));
      Instant inside = Instant.ofEpochMilli(NOW + 60_000L);
      assertEquals(inside, at(inside.toString()));
    }

    @ParameterizedTest
    @ValueSource(strings = { "", "yesterday", "2026-09-23", "2026-13-01T00:00:00Z",
        "1700000000000" })
    void unparseableMeansNow(String value) {
      assertEquals(Instant.ofEpochMilli(NOW), at(value));
    }

    @Test
    void absentMeansNow() {
      assertEquals(Instant.ofEpochMilli(NOW), at(null));
    }

    @Test
    void anInstantPastTheEpochMilliRangeDoesNotBreak() {
      assertEquals(Instant.ofEpochMilli(NOW), at("+1000000000-12-31T23:59:59Z"));
      assertEquals(Instant.ofEpochMilli(NOW), at("-1000000000-01-01T00:00:00Z"));
    }

    @Test
    void theClampUsesTheInjectedClock() throws Exception {
      clock.set(NOW + 3_600_000L);
      UsageEvent e = single(known());
      assertEquals(Instant.ofEpochMilli(NOW + 3_600_000L), e.occurredAt());
    }
  }

  // ── durationMs ────────────────────────────────────────────────────────

  @Nested
  @DisplayName("durationMs")
  class DurationMs {

    @Test
    void nonNegativeNumbersAreKeptAndDecimalsTruncated() {
      assertEquals(0L, NeoUsageEventEndpoint.durationMs(0));
      assertEquals(1234L, NeoUsageEventEndpoint.durationMs(1234));
      assertEquals(12L, NeoUsageEventEndpoint.durationMs(12.9d));
      assertEquals(0L, NeoUsageEventEndpoint.durationMs(-0.5d), "-0.5 truncates to 0 first");
      assertEquals(9_999_999_999_999L, NeoUsageEventEndpoint.durationMs(9_999_999_999_999L));
    }

    @Test
    void negativeStringAndAbsentBecomeNull() {
      assertNull(NeoUsageEventEndpoint.durationMs(-1));
      assertNull(NeoUsageEventEndpoint.durationMs("100"));
      assertNull(NeoUsageEventEndpoint.durationMs(null));
      assertNull(NeoUsageEventEndpoint.durationMs(true));
    }

    @Test
    void theBodyValueIsMapped() throws Exception {
      assertEquals(15L, single(known().put("durationMs", 15.7)).durationMs());
      sunk.clear();
      assertNull(single(known().put("durationMs", "15")).durationMs());
    }
  }

  // ── properties ────────────────────────────────────────────────────────

  @Nested
  @DisplayName("properties")
  class Properties {

    @Test
    void scalarsAreKeptWithTheirType() throws Exception {
      Map<String, Object> p = NeoUsageEventEndpoint.properties(new JSONObject()
          .put("s", "x").put("i", 3).put("d", 1.5).put("b", false));
      assertEquals("x", p.get("s"));
      assertEquals(3, p.get("i"));
      assertEquals(1.5, p.get("d"));
      assertEquals(Boolean.FALSE, p.get("b"));
      assertEquals(4, p.size());
    }

    @Test
    void nestedArraysNullsAndBadKeysDropOnlyThatProperty() throws Exception {
      String key64 = "k".repeat(NeoUsageEventEndpoint.MAX_PROPERTY_KEY);
      Map<String, Object> p = NeoUsageEventEndpoint.properties(new JSONObject()
          .put("nested", new JSONObject().put("a", 1))
          .put("array", new JSONArray().put(1))
          .put("nil", JSONObject.NULL)
          .put(key64 + "x", "too long key")
          .put(key64, "kept")
          .put("", "empty key")
          .put("ok", 1));
      assertEquals(Map.of(key64, "kept", "ok", 1), p);
    }

    @Test
    void stringsAreClipped() throws Exception {
      Map<String, Object> p = NeoUsageEventEndpoint.properties(new JSONObject()
          .put("long", "v".repeat(1_000))
          .put("exact", "e".repeat(NeoUsageEventEndpoint.MAX_PROPERTY_STRING)));
      assertEquals(NeoUsageEventEndpoint.MAX_PROPERTY_STRING, ((String) p.get("long")).length());
      assertEquals("e".repeat(NeoUsageEventEndpoint.MAX_PROPERTY_STRING), p.get("exact"));
    }

    @Test
    void aNonObjectGivesNoProperties() {
      assertTrue(NeoUsageEventEndpoint.properties(null).isEmpty());
      assertTrue(NeoUsageEventEndpoint.properties("x").isEmpty());
      assertTrue(NeoUsageEventEndpoint.properties(new JSONArray()).isEmpty());
      assertTrue(NeoUsageEventEndpoint.properties(JSONObject.NULL).isEmpty());
    }

    @Test
    void aNonObjectInTheBodyStillAcceptsTheEvent() throws Exception {
      NeoResponse response = postEvents(known().put("properties", new JSONArray().put(1)));
      assertAccepted(response, 1, 0);
      assertNull(sunk.get(0).properties());
    }

    @Test
    void propertiesOverTheCeilingBecomeTheMarker() throws Exception {
      JSONObject props = new JSONObject();
      for (int i = 0; i < 20; i++) {
        props.put("p" + i, "v".repeat(NeoUsageEventEndpoint.MAX_PROPERTY_STRING));
      }
      UsageEvent e = single(known().put("properties", props));
      assertEquals("{\"_truncated\":true}", e.properties());
    }
  }

  // ── rate limit ────────────────────────────────────────────────────────

  @Nested
  @DisplayName("rate limit")
  class RateLimit {

    private NeoUsageEventEndpoint.RateLimiter limiter(int limit, int maxKeys) {
      return new NeoUsageEventEndpoint.RateLimiter(limit, NeoUsageEventEndpoint.RATE_WINDOW_MS,
          maxKeys, clock::get);
    }

    @Test
    void theLimitPassesThenTheNextIsDroppedUntilTheWindowResets() {
      NeoUsageEventEndpoint.RateLimiter limiter = limiter(
          NeoUsageEventEndpoint.RATE_LIMIT_EVENTS, 100);
      for (int i = 0; i < NeoUsageEventEndpoint.RATE_LIMIT_EVENTS; i++) {
        assertTrue(limiter.tryAcquire("k"), "event " + (i + 1));
      }
      assertFalse(limiter.tryAcquire("k"), "601st inside the window");

      clock.addAndGet(NeoUsageEventEndpoint.RATE_WINDOW_MS - 1);
      assertFalse(limiter.tryAcquire("k"), "still the same window");
      clock.addAndGet(1);
      assertTrue(limiter.tryAcquire("k"), "a new window starts");
    }

    @Test
    void keysAreIndependent() {
      NeoUsageEventEndpoint.RateLimiter limiter = limiter(1, 100);
      assertTrue(limiter.tryAcquire("a"));
      assertFalse(limiter.tryAcquire("a"));
      assertTrue(limiter.tryAcquire("b"));
    }

    @Test
    void atTheKeyCapExpiredWindowsAreEvictedAndLiveOnesKept() {
      NeoUsageEventEndpoint.RateLimiter limiter = limiter(2, 2);
      assertTrue(limiter.tryAcquire("old"));
      clock.addAndGet(30_000L);
      assertTrue(limiter.tryAcquire("live")); // live: count 1
      clock.addAndGet(30_000L); // "old" has expired, "live" has not

      assertTrue(limiter.tryAcquire("new"));

      assertEquals(2, limiter.size(), "only the expired window was evicted");
      assertTrue(limiter.tryAcquire("live")); // count 2: its window survived
      assertFalse(limiter.tryAcquire("live"), "the live window kept its count");
    }

    @Test
    void atTheKeyCapWithEveryWindowLiveTheMapIsCleared() {
      NeoUsageEventEndpoint.RateLimiter limiter = limiter(1, 2);
      assertTrue(limiter.tryAcquire("a"));
      assertTrue(limiter.tryAcquire("b"));
      assertFalse(limiter.tryAcquire("a"));

      assertTrue(limiter.tryAcquire("c"));

      assertEquals(1, limiter.size(), "cleared, then only the new key");
      assertTrue(limiter.tryAcquire("a"), "a starts over after the clear");
    }

    @Test
    void anExistingKeyAtTheCapDoesNotEvict() {
      NeoUsageEventEndpoint.RateLimiter limiter = limiter(5, 2);
      limiter.tryAcquire("a");
      limiter.tryAcquire("b");
      clock.addAndGet(NeoUsageEventEndpoint.RATE_WINDOW_MS);
      assertTrue(limiter.tryAcquire("a"));
      assertEquals(2, limiter.size());
    }

    @Test
    void theEndpointDropsPastTheLimitPerUserAndSession() throws Exception {
      int perRequest = NeoUsageEventEndpoint.MAX_EVENTS_PER_REQUEST;
      JSONArray batch = new JSONArray();
      for (int i = 0; i < perRequest; i++) {
        batch.put(known().put("sessionKey", "s1"));
      }
      int requests = NeoUsageEventEndpoint.RATE_LIMIT_EVENTS / perRequest;
      for (int r = 0; r < requests; r++) {
        assertAccepted(postEvents(batch), perRequest, 0);
      }

      assertAccepted(postEvents(batch), 0, perRequest);
      assertAccepted(postEvents(known().put("sessionKey", "s2")), 1, 0);
      assertEquals(NeoUsageEventEndpoint.RATE_LIMIT_EVENTS + 1, sunk.size(),
          "rate-limited events never reach the sink");

      clock.addAndGet(NeoUsageEventEndpoint.RATE_WINDOW_MS);
      assertAccepted(postEvents(known().put("sessionKey", "s1")), 1, 0);
    }
  }

  // ── failures ──────────────────────────────────────────────────────────

  @Test
  void aSinkThatThrowsMidBatchStill202sWithTheRestDropped() throws Exception {
    List<UsageEvent> seen = new ArrayList<>();
    NeoUsageEventEndpoint failing = new NeoUsageEventEndpoint(e -> {
      if (seen.size() == 2) {
        throw new IllegalStateException("recorder down");
      }
      seen.add(e);
    }, clock::get);
    JSONArray events = new JSONArray();
    for (int i = 0; i < 5; i++) {
      events.put(known());
    }
    byte[] body = new JSONObject().put("events", events).toString()
        .getBytes(StandardCharsets.UTF_8);

    NeoResponse response = failing.handle(request(body, body.length));

    assertAccepted(response, 2, 3);
    assertEquals(2, seen.size());
  }
}
