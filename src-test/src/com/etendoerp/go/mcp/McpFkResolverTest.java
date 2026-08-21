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
package com.etendoerp.go.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.Property;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.datamodel.Column;
import org.openbravo.model.ad.ui.Tab;

import com.etendoerp.go.schemaforge.NeoResponse;
import com.etendoerp.go.schemaforge.NeoSelectorService;

/**
 * Unit tests for {@link McpFkResolver}: the DAL-free static logic (IMP-4) — id-vs-search-string
 * detection and the match-count-to-outcome decision — plus the {@code resolveFkNames} entry point,
 * whose DAL and selector calls are mocked statically.
 * <p>
 * What these tests cannot show is that the selector <i>agrees with</i> the live AD data, so the
 * end-to-end claim still rests on a probe against a real instance; what they do pin down is the
 * resolver's own contract: which values short-circuit, which reach the selector, and — for IMP-22 —
 * <b>what context each selector call is given</b>.
 */
// Test methods live in the @Nested inner classes below; S2187 only inspects
// the outer class for @Test methods, hence the suppression.
@SuppressWarnings("java:S2187")
@DisplayName("McpFkResolver")
class McpFkResolverTest {

  @Nested
  @DisplayName("looksLikeId")
  class LooksLikeId {

    @Test
    @DisplayName("a 32-char hex string (upper, lower, or mixed case) is treated as an id")
    void hexIdsAreIds() {
      assertTrue(McpFkResolver.looksLikeId("95E2A8B50A254B2AAE6774B8C2F28120"));
      assertTrue(McpFkResolver.looksLikeId("95e2a8b50a254b2aae6774b8c2f28120"));
      assertTrue(McpFkResolver.looksLikeId("95E2a8b50A254b2AAe6774b8C2f28120"));
    }

    @Test
    @DisplayName("a human search string, a short id, a non-hex string, or null is not an id")
    void nonIdsAreNotIds() {
      assertFalse(McpFkResolver.looksLikeId("Acme Corp"));
      assertFalse(McpFkResolver.looksLikeId("95E2A8B50A254B2AAE6774B8C2F281")); // 31 chars
      assertFalse(McpFkResolver.looksLikeId("95E2A8B50A254B2AAE6774B8C2F2812Z")); // non-hex char
      assertFalse(McpFkResolver.looksLikeId(""));
      assertFalse(McpFkResolver.looksLikeId(null));
    }
  }

  @Nested
  @DisplayName("decideOutcome")
  class DecideOutcome {

    @Test
    @DisplayName("zero matches is NOT_FOUND")
    void zeroIsNotFound() {
      assertEquals(McpFkResolver.Outcome.NOT_FOUND, McpFkResolver.decideOutcome(0));
    }

    @Test
    @DisplayName("exactly one match is RESOLVED")
    void oneIsResolved() {
      assertEquals(McpFkResolver.Outcome.RESOLVED, McpFkResolver.decideOutcome(1));
    }

    @Test
    @DisplayName("more than one match is AMBIGUOUS")
    void manyIsAmbiguous() {
      assertEquals(McpFkResolver.Outcome.AMBIGUOUS, McpFkResolver.decideOutcome(2));
      assertEquals(McpFkResolver.Outcome.AMBIGUOUS, McpFkResolver.decideOutcome(10));
    }
  }

  /**
   * The value-format matrix required by IMP-15: one FK field must accept a UUID, a legacy numeric
   * record id and a display name, and both write verbs share this resolver — so covering it here
   * covers {@code neo_create}, {@code neo_update} and {@code neo_batch} at once.
   */
  @Nested
  @DisplayName("resolveFkNames — value-format matrix (IMP-15)")
  class ResolveFkNames {

    private static final String KEY = "currency";
    private static final String UUID_ID = "95E2A8B50A254B2AAE6774B8C2F28120";
    private static final String LEGACY_ID = "102";
    private static final String TARGET_ENTITY = "Currency";

    private Entity dalEntity;
    private Tab adTab;
    private Logger log;
    private OBDal obDalInstance;

    @BeforeEach
    void setUp() {
      Entity targetEntity = mock(Entity.class);
      when(targetEntity.getName()).thenReturn(TARGET_ENTITY);
      Property prop = mock(Property.class);
      when(prop.isPrimitive()).thenReturn(false);
      when(prop.getTargetEntity()).thenReturn(targetEntity);
      dalEntity = mock(Entity.class);
      when(dalEntity.getProperty(KEY, false)).thenReturn(prop);
      adTab = mock(Tab.class);
      log = mock(Logger.class);
      obDalInstance = mock(OBDal.class);
    }

    private JSONObject bodyWith(String value) throws Exception {
      JSONObject body = new JSONObject();
      body.put(KEY, value);
      return body;
    }

    private NeoResponse selectorHits(String... ids) throws Exception {
      JSONArray items = new JSONArray();
      for (String id : ids) {
        JSONObject item = new JSONObject();
        item.put("id", id);
        items.put(item);
      }
      JSONObject payload = new JSONObject();
      payload.put("items", items);
      return NeoResponse.ok(payload);
    }

    @Test
    @DisplayName("a 32-char hex id resolves on shape alone — no DAL probe, no selector call")
    void uuidShortCircuits() throws Exception {
      JSONObject body = bodyWith(UUID_ID);
      try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
          MockedStatic<NeoSelectorService> selector = mockStatic(NeoSelectorService.class)) {
        assertNull(McpFkResolver.resolveFkNames(body, dalEntity, adTab, Map.of(), log));
        assertEquals(UUID_ID, body.getString(KEY));
        obDal.verifyNoInteractions();
        selector.verifyNoInteractions();
      }
    }

    @Test
    @DisplayName("a legacy numeric id resolves via the id probe, never reaching the selector")
    void legacyNumericIdResolves() throws Exception {
      JSONObject body = bodyWith(LEGACY_ID);
      when(obDalInstance.<BaseOBObject>get(TARGET_ENTITY, LEGACY_ID))
          .thenReturn(mock(BaseOBObject.class));
      try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
          MockedStatic<NeoSelectorService> selector = mockStatic(NeoSelectorService.class)) {
        obDal.when(OBDal::getInstance).thenReturn(obDalInstance);
        assertNull(McpFkResolver.resolveFkNames(body, dalEntity, adTab, Map.of(), log));
        // Untouched: it was already the id, which is exactly what neo_defaults hands back.
        assertEquals(LEGACY_ID, body.getString(KEY));
        selector.verifyNoInteractions();
      }
    }

    @Test
    @DisplayName("a display name falls through to the selector and is replaced by the matched id")
    void displayNameResolvesViaSelector() throws Exception {
      JSONObject body = bodyWith("EUR");
      Column column = mock(Column.class);
      try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
          MockedStatic<NeoSelectorService> selector = mockStatic(NeoSelectorService.class);
          MockedStatic<McpSchemaFieldBuilder> fields = mockStatic(McpSchemaFieldBuilder.class)) {
        obDal.when(OBDal::getInstance).thenReturn(obDalInstance);
        fields.when(() -> McpSchemaFieldBuilder.findColumn(adTab, KEY, dalEntity)).thenReturn(column);
        selector.when(() -> NeoSelectorService.querySelectorByColumn(eq(column), eq(KEY), eq("EUR"),
            anyInt(), anyInt(), any())).thenReturn(selectorHits(UUID_ID));

        assertNull(McpFkResolver.resolveFkNames(body, dalEntity, adTab, Map.of(), log));
        assertEquals(UUID_ID, body.getString(KEY));
      }
    }

    @Test
    @DisplayName("an unresolvable value no longer advises passing the exact record id")
    void notFoundDoesNotAdviseTheIdTheAgentAlreadyTried() throws Exception {
      JSONObject body = bodyWith("Ünknown");
      Column column = mock(Column.class);
      try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
          MockedStatic<NeoSelectorService> selector = mockStatic(NeoSelectorService.class);
          MockedStatic<McpSchemaFieldBuilder> fields = mockStatic(McpSchemaFieldBuilder.class)) {
        obDal.when(OBDal::getInstance).thenReturn(obDalInstance);
        fields.when(() -> McpSchemaFieldBuilder.findColumn(adTab, KEY, dalEntity)).thenReturn(column);
        selector.when(() -> NeoSelectorService.querySelectorByColumn(any(), anyString(), anyString(),
            anyInt(), anyInt(), any())).thenReturn(selectorHits());

        JSONObject error = McpFkResolver.resolveFkNames(body, dalEntity, adTab, Map.of(), log);
        assertNotNull(error);
        assertEquals(McpConstants.STATUS_UNPROCESSABLE, error.getInt(McpConstants.KEY_STATUS));
        assertEquals(McpConstants.ERROR_NOT_FOUND, error.getString(McpConstants.KEY_ERROR));
        assertEquals(KEY, error.getString("field"));
        // The id path already ran, so this advice would send the agent back to what it just did.
        assertFalse(error.getString(McpConstants.KEY_DETAIL).contains("exact record id"));
        assertTrue(error.getString(McpConstants.KEY_DETAIL).contains("neo_selectors"));
      }
    }

    @Test
    @DisplayName("a skipped value ('$ref:' placeholder) is left alone without any lookup")
    void skippedValueIsUntouched() throws Exception {
      JSONObject body = bodyWith("$ref:h1");
      try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
          MockedStatic<NeoSelectorService> selector = mockStatic(NeoSelectorService.class)) {
        assertNull(McpFkResolver.resolveFkNames(body, dalEntity, adTab, Map.of(), log,
            value -> value.startsWith("$ref:")));
        assertEquals("$ref:h1", body.getString(KEY));
        obDal.verifyNoInteractions();
        selector.verifyNoInteractions();
      }
    }
  }

  /**
   * IMP-22: a selector whose candidate set only exists relative to a sibling field.
   * <p>
   * The defect these guard is specific and was measured, not imagined: {@code neo_create} rejected
   * the byte-identical {@code $_identifier} that {@code neo_selectors} returned for the same column
   * with a {@code recordContext}. So the assertions are about <b>what context the selector was
   * called with</b>, not merely about the end result — a test that only checked the resolved id would
   * pass against a resolver that guessed right for the wrong reason.
   */
  @Nested
  @DisplayName("resolveFkNames — context-dependent selectors (IMP-22)")
  class ContextDependentSelectors {

    private static final String BP_KEY = "businessPartner";
    private static final String ADDR_KEY = "partnerAddress";
    private static final String BP_PARAM = "C_BPartner_ID";
    private static final String BP_ID = "6BD084B9C1744044B9691AD373F96A93";
    private static final String ADDR_ID = "20363AD155354047AD5E52D8A93D9465";
    private static final String BP_NAME = "Tercero España";
    private static final String ADDR_NAME = "San Sebastian, C/ EUSTASIO AMILIBIA 10";

    private Entity dalEntity;
    private Tab adTab;
    private Logger log;
    private OBDal obDalInstance;
    private Column column;

    @BeforeEach
    void setUp() {
      // Both properties are built before any stubbing starts: fkProperty() mocks internally, and
      // Mockito rejects a nested mock created inside a thenReturn() argument.
      Property bpProperty = fkProperty("BusinessPartner");
      Property addrProperty = fkProperty("BusinessPartnerLocation");
      dalEntity = mock(Entity.class);
      when(dalEntity.getProperty(BP_KEY, false)).thenReturn(bpProperty);
      when(dalEntity.getProperty(ADDR_KEY, false)).thenReturn(addrProperty);
      adTab = mock(Tab.class);
      log = mock(Logger.class);
      obDalInstance = mock(OBDal.class);
      column = mock(Column.class);
    }

    private Property fkProperty(String targetEntityName) {
      Entity target = mock(Entity.class);
      when(target.getName()).thenReturn(targetEntityName);
      Property prop = mock(Property.class);
      when(prop.isPrimitive()).thenReturn(false);
      when(prop.getTargetEntity()).thenReturn(target);
      return prop;
    }

    private NeoResponse hits(String... ids) throws Exception {
      JSONArray items = new JSONArray();
      for (String id : ids) {
        JSONObject item = new JSONObject();
        item.put("id", id);
        items.put(item);
      }
      JSONObject payload = new JSONObject();
      payload.put("items", items);
      return NeoResponse.ok(payload);
    }

    @Test
    @DisplayName("a dependent FK is looked up with the sibling id the body already carries")
    void dependentFkGetsTheSiblingAsContext() throws Exception {
      JSONObject body = new JSONObject();
      body.put(BP_KEY, BP_ID);          // already an id, as neo_selectors would have returned it
      body.put(ADDR_KEY, ADDR_NAME);    // the $_identifier that used to come back as a 422
      List<Map<String, String>> addressContexts = new ArrayList<>();

      try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
          MockedStatic<NeoSelectorService> selector = mockStatic(NeoSelectorService.class);
          MockedStatic<McpSchemaFieldBuilder> fields = mockStatic(McpSchemaFieldBuilder.class)) {
        obDal.when(OBDal::getInstance).thenReturn(obDalInstance);
        fields.when(() -> McpSchemaFieldBuilder.findColumn(any(), anyString(), any()))
            .thenReturn(column);
        selector.when(() -> NeoSelectorService.querySelectorByColumn(any(), anyString(), anyString(),
            anyInt(), anyInt(), any())).thenAnswer(invocation -> {
              addressContexts.add(invocation.getArgument(5));
              return hits(ADDR_ID);
            });

        assertNull(McpFkResolver.resolveFkNames(body, dalEntity, adTab, Map.of(), log));
        assertEquals(ADDR_ID, body.getString(ADDR_KEY));
        assertEquals(BP_ID, body.getString(BP_KEY));
        // The point of the item: the parent id reached the selector as context.
        assertEquals(1, addressContexts.size());
        assertEquals(BP_ID, addressContexts.get(0).get(BP_PARAM));
      }
    }

    @Test
    @DisplayName("both fields as display names resolve in dependency order across two passes")
    void bothAsNamesResolveInDependencyOrder() throws Exception {
      JSONObject body = new JSONObject();
      body.put(BP_KEY, BP_NAME);
      body.put(ADDR_KEY, ADDR_NAME);
      List<Map<String, String>> addressContexts = new ArrayList<>();

      try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
          MockedStatic<NeoSelectorService> selector = mockStatic(NeoSelectorService.class);
          MockedStatic<McpSchemaFieldBuilder> fields = mockStatic(McpSchemaFieldBuilder.class)) {
        obDal.when(OBDal::getInstance).thenReturn(obDalInstance);
        fields.when(() -> McpSchemaFieldBuilder.findColumn(any(), anyString(), any()))
            .thenReturn(column);
        // The address selector behaves like the real one: it only has candidates once it knows the
        // partner. Without that context it returns nothing, which is what produced the false 422.
        selector.when(() -> NeoSelectorService.querySelectorByColumn(any(), anyString(), anyString(),
            anyInt(), anyInt(), any())).thenAnswer(invocation -> {
              String field = invocation.getArgument(1);
              Map<String, String> context = invocation.getArgument(5);
              if (BP_KEY.equals(field)) {
                return hits(BP_ID);
              }
              addressContexts.add(context);
              return BP_ID.equals(context.get(BP_PARAM)) ? hits(ADDR_ID) : hits();
            });

        assertNull(McpFkResolver.resolveFkNames(body, dalEntity, adTab, Map.of(), log));
        assertEquals(BP_ID, body.getString(BP_KEY));
        assertEquals(ADDR_ID, body.getString(ADDR_KEY));
        // Whichever order the body happened to iterate in, no attempt was ever made with the raw
        // search string as the parent id — copying an unresolved name into C_BPartner_ID would
        // narrow the candidate set to nothing and turn a resolvable field into a not_found.
        assertFalse(addressContexts.stream().anyMatch(c -> BP_NAME.equals(c.get(BP_PARAM))));
        assertTrue(addressContexts.stream().anyMatch(c -> BP_ID.equals(c.get(BP_PARAM))));
      }
    }

    @Test
    @DisplayName("a genuinely unresolvable dependent FK reports not_found instead of looping")
    void noProgressTerminatesWithTheError() throws Exception {
      JSONObject body = new JSONObject();
      body.put(BP_KEY, BP_NAME);
      body.put(ADDR_KEY, ADDR_NAME);

      try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
          MockedStatic<NeoSelectorService> selector = mockStatic(NeoSelectorService.class);
          MockedStatic<McpSchemaFieldBuilder> fields = mockStatic(McpSchemaFieldBuilder.class)) {
        obDal.when(OBDal::getInstance).thenReturn(obDalInstance);
        fields.when(() -> McpSchemaFieldBuilder.findColumn(any(), anyString(), any()))
            .thenReturn(column);
        selector.when(() -> NeoSelectorService.querySelectorByColumn(any(), anyString(), anyString(),
            anyInt(), anyInt(), any())).thenAnswer(invocation -> hits());

        JSONObject error = McpFkResolver.resolveFkNames(body, dalEntity, adTab, Map.of(), log);
        assertNotNull(error);
        assertEquals(McpConstants.ERROR_NOT_FOUND, error.getString(McpConstants.KEY_ERROR));
        // Deferring failures must not cost termination: a pass that resolves nothing ends the loop.
      }
    }
  }
}
