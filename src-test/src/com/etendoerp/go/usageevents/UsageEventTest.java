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

package com.etendoerp.go.usageevents;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.openbravo.dal.core.OBContext;
import org.openbravo.model.ad.access.Role;
import org.openbravo.model.ad.access.User;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.enterprise.Organization;

/**
 * Unit specs for {@link UsageEvent} and its builder (ETP-5462).
 *
 * <p>Two contracts matter most. {@link UsageEvent.Builder#fromContext()} is <b>total</b>: whatever
 * the {@code OBContext} does, the caller gets a usable event and never an exception. And it runs
 * <b>on the calling thread</b>: the writer thread has no {@code OBContext}, so the ids must already
 * be inside the immutable record when it crosses over. {@code OBContext} is mocked statically, and
 * Mockito's static mocks are thread-local — which is precisely what makes the cross-thread spec
 * meaningful: a lazy read on the other thread would see no mock at all.</p>
 */
@SuppressWarnings("java:S2187") // test methods live in the @Nested classes
class UsageEventTest {

  private static final String CLIENT_ID = "A1B2C3D4E5F60718293A4B5C6D7E8F90";
  private static final String ORG_ID = "0A1B2C3D4E5F60718293A4B5C6D7E8F9";
  private static final String USER_ID = "F0E1D2C3B4A5968778695A4B3C2D1E0F";
  private static final String ROLE_ID = "11112222333344445555666677778888";

  @Nested
  @DisplayName("builder defaults")
  class BuilderDefaults {

    @Test
    void sourceDefaultsToBackendAndOccurredAtToNow() {
      Instant before = Instant.now();
      UsageEvent event = UsageEvent.builder().eventType(UsageEventTypes.AI_AGENT_MESSAGE).build();
      Instant after = Instant.now();

      assertEquals(UsageEvent.SOURCE_BACKEND, event.source());
      assertNotNull(event.occurredAt());
      assertFalse(event.occurredAt().isBefore(before));
      assertFalse(event.occurredAt().isAfter(after));
      assertNull(event.properties());
    }

    @Test
    void everySetterLandsInItsOwnComponent() {
      Instant at = Instant.parse("2026-09-23T10:00:00Z");
      UsageEvent event = UsageEvent.builder()
          .clientId("c").orgId("o").userId("u").roleId("r")
          .eventType("t").source(UsageEvent.SOURCE_UI).sessionKey("s")
          .target("spec").action("create").outcome(UsageEvent.OUTCOME_ERROR)
          .errorCode("E1").durationMs(42L).occurredAt(at).appVersion("1.2.3")
          .build();

      assertAll(
          () -> assertEquals("c", event.clientId()),
          () -> assertEquals("o", event.orgId()),
          () -> assertEquals("u", event.userId()),
          () -> assertEquals("r", event.roleId()),
          () -> assertEquals("t", event.eventType()),
          () -> assertEquals(UsageEvent.SOURCE_UI, event.source()),
          () -> assertEquals("s", event.sessionKey()),
          () -> assertEquals("spec", event.target()),
          () -> assertEquals("create", event.action()),
          () -> assertEquals(UsageEvent.OUTCOME_ERROR, event.outcome()),
          () -> assertEquals("E1", event.errorCode()),
          () -> assertEquals(42L, event.durationMs()),
          () -> assertEquals(at, event.occurredAt()),
          () -> assertEquals("1.2.3", event.appVersion()));
    }

    @Test
    void isValidSourceAcceptsOnlyTheConstrainedValues() {
      assertTrue(UsageEvent.isValidSource(UsageEvent.SOURCE_BACKEND));
      assertTrue(UsageEvent.isValidSource(UsageEvent.SOURCE_UI));
      assertTrue(UsageEvent.isValidSource(UsageEvent.SOURCE_AI_BFF));
      assertTrue(UsageEvent.isValidSource(UsageEvent.SOURCE_MCP));
      assertFalse(UsageEvent.isValidSource(null));
      assertFalse(UsageEvent.isValidSource("UI"));
      assertFalse(UsageEvent.isValidSource("browser"));
    }
  }

  @Nested
  @DisplayName("fromContext")
  class FromContext {

    private MockedStatic<OBContext> obContextStatic;

    @BeforeEach
    void mockContext() {
      obContextStatic = mockStatic(OBContext.class);
    }

    @AfterEach
    void closeMock() {
      obContextStatic.close();
    }

    private OBContext fullContext() {
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
      return context;
    }

    private void assertDefaults(UsageEvent event) {
      assertAll(
          () -> assertEquals(UsageEvent.DEFAULT_CLIENT, event.clientId()),
          () -> assertEquals(UsageEvent.DEFAULT_ORG, event.orgId()),
          () -> assertEquals(UsageEvent.SYSTEM_USER, event.userId()),
          () -> assertNull(event.roleId()));
    }

    @Test
    void noContextGivesTheDefaults() {
      obContextStatic.when(OBContext::getOBContext).thenReturn(null);
      assertDefaults(UsageEvent.builder().fromContext().build());
    }

    @Test
    void contextWithNoEntitiesGivesTheDefaults() {
      OBContext empty = mock(OBContext.class);
      obContextStatic.when(OBContext::getOBContext).thenReturn(empty);
      assertDefaults(UsageEvent.builder().fromContext().build());
    }

    @Test
    void getOBContextThrowingGivesTheDefaults() {
      obContextStatic.when(OBContext::getOBContext).thenThrow(new IllegalStateException("boom"));
      UsageEvent event = assertDoesNotThrow(() -> UsageEvent.builder().fromContext().build());
      assertDefaults(event);
    }

    @Test
    void capturesTheContextIds() {
      OBContext context = fullContext();
      obContextStatic.when(OBContext::getOBContext).thenReturn(context);

      UsageEvent event = UsageEvent.builder().fromContext().build();

      assertAll(
          () -> assertEquals(CLIENT_ID, event.clientId()),
          () -> assertEquals(ORG_ID, event.orgId()),
          () -> assertEquals(USER_ID, event.userId()),
          () -> assertEquals(ROLE_ID, event.roleId()));
    }

    /**
     * {@code getUser()} throwing must not reach the caller: the user falls back to the system user
     * and the role is left unset. Client and org were already read successfully before the
     * failure, so they keep the context values — the defaults are per component, not all-or-none.
     */
    @Test
    void getUserThrowingFallsBackWithoutFailing() {
      OBContext context = fullContext();
      when(context.getUser()).thenThrow(new IllegalStateException("user not loaded"));
      obContextStatic.when(OBContext::getOBContext).thenReturn(context);

      UsageEvent event = assertDoesNotThrow(() -> UsageEvent.builder().fromContext().build());

      assertEquals(UsageEvent.SYSTEM_USER, event.userId());
      assertNull(event.roleId());
      assertEquals(CLIENT_ID, event.clientId());
      assertEquals(ORG_ID, event.orgId());
    }

    @Test
    void anErrorIsAlsoSwallowed() {
      OBContext context = fullContext();
      when(context.getCurrentClient()).thenThrow(new NoClassDefFoundError("dal not ready"));
      obContextStatic.when(OBContext::getOBContext).thenReturn(context);

      UsageEvent event = assertDoesNotThrow(() -> UsageEvent.builder().fromContext().build());
      assertDefaults(event);
    }

    @Test
    void explicitSettersAfterFromContextWin() {
      OBContext context = fullContext();
      obContextStatic.when(OBContext::getOBContext).thenReturn(context);
      UsageEvent event = UsageEvent.builder().fromContext().userId("other").build();
      assertEquals("other", event.userId());
      assertEquals(CLIENT_ID, event.clientId());
    }

    @Test
    void idsAreCapturedOnTheCallingThread() throws Exception {
      OBContext context = fullContext();
      obContextStatic.when(OBContext::getOBContext).thenReturn(context);

      UsageEvent event = UsageEvent.builder().fromContext().build();
      // The static mock is thread-local: another thread sees no context. The record must already
      // carry the ids, so reading it there gives exactly what was captured here.
      List<String> seen = CompletableFuture
          .supplyAsync(() -> List.of(event.clientId(), event.orgId(), event.userId(),
              event.roleId()))
          .get(10, TimeUnit.SECONDS);

      assertEquals(List.of(CLIENT_ID, ORG_ID, USER_ID, ROLE_ID), seen);
    }
  }

  @Nested
  @DisplayName("properties serialization")
  class Properties {

    @Test
    void emptyOrNullGivesNull() {
      assertNull(UsageEvent.serializeProperties(null));
      assertNull(UsageEvent.serializeProperties(new LinkedHashMap<>()));
      assertNull(UsageEvent.builder().build().properties());
    }

    @Test
    void scalarsAreStoredWithTheirJsonType() throws Exception {
      UsageEvent event = UsageEvent.builder()
          .property("model", "kimi-k2.6")
          .property("inputTokens", 1200)
          .property("cached", true)
          .property("ratio", 0.5d)
          .build();

      JSONObject json = new JSONObject(event.properties());
      assertEquals("kimi-k2.6", json.get("model"));
      assertEquals(1200, json.getInt("inputTokens"));
      assertTrue(json.get("inputTokens") instanceof Number, "numbers must stay numbers");
      assertEquals(Boolean.TRUE, json.get("cached"));
      assertEquals(0.5d, json.getDouble("ratio"));
    }

    @Test
    void nonScalarsAreStoredAsTheirStringForm() throws Exception {
      Instant at = Instant.parse("2026-09-23T10:00:00Z");
      UsageEvent event = UsageEvent.builder()
          .property("list", List.of(1, 2))
          .property("at", at)
          .build();

      JSONObject json = new JSONObject(event.properties());
      assertEquals("[1, 2]", json.get("list"));
      assertEquals(at.toString(), json.get("at"));
    }

    @Test
    void nullKeysAndValuesAreIgnored() throws Exception {
      Map<String, Object> withNulls = new HashMap<>();
      withNulls.put("kept", "x");
      withNulls.put("dropped", null);
      withNulls.put(null, "y");

      UsageEvent event = UsageEvent.builder()
          .property(null, "v")
          .property("k", null)
          .properties(null)
          .properties(withNulls)
          .build();

      JSONObject json = new JSONObject(event.properties());
      assertEquals(1, json.length());
      assertEquals("x", json.get("kept"));
    }

    @Test
    void onlyNullEntriesGiveNull() {
      assertNull(UsageEvent.builder().property("k", null).property(null, "v").build()
          .properties());
    }

    @Test
    void oversizedPropertiesAreReplacedByTheMarker() throws Exception {
      UsageEvent event = UsageEvent.builder().property("blob", "x".repeat(5_000)).build();
      assertEquals(UsageEvent.PROPERTIES_OVERSIZE_MARKER, event.properties());
      // The marker must itself be valid JSON: every aggregation goes through properties::jsonb.
      assertTrue(new JSONObject(event.properties()).getBoolean("_truncated"));
    }

    @Test
    void theBoundIsInclusiveAndMeasuredInBytes() {
      // {"k":"<n chars>"} is 8 bytes of structure plus the value.
      int fitting = UsageEvent.PROPERTIES_MAX_BYTES - 8;
      Map<String, Object> exact = new LinkedHashMap<>();
      exact.put("k", "a".repeat(fitting));
      String text = UsageEvent.serializeProperties(exact);
      assertEquals(UsageEvent.PROPERTIES_MAX_BYTES, text.getBytes(StandardCharsets.UTF_8).length);

      Map<String, Object> oneOver = new LinkedHashMap<>();
      oneOver.put("k", "a".repeat(fitting + 1));
      assertEquals(UsageEvent.PROPERTIES_OVERSIZE_MARKER, UsageEvent.serializeProperties(oneOver));

      // Fewer characters than the bound, but more UTF-8 bytes: still oversized.
      Map<String, Object> multibyte = new LinkedHashMap<>();
      multibyte.put("k", "é".repeat(3_000));
      assertEquals(UsageEvent.PROPERTIES_OVERSIZE_MARKER,
          UsageEvent.serializeProperties(multibyte));
    }

    @Test
    void aValueJsonRejectsGivesNullWithoutThrowing() {
      UsageEvent event = assertDoesNotThrow(
          () -> UsageEvent.builder().property("bad", Double.NaN).build());
      assertNull(event.properties());
    }
  }
}
