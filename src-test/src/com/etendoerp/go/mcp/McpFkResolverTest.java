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
 * Unit tests for {@link McpFkResolver}'s DAL-free static logic (IMP-4): id-vs-search-string
 * detection and the match-count-to-outcome decision. The DAL-bound {@code resolveFkNames} path
 * (selector lookup, body mutation) needs a live instance and is exercised manually/via the MCP
 * validation bot instead.
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
}
