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

package com.etendoerp.go.schemaforge;

import static com.etendoerp.go.schemaforge.NeoPropertyPathsTestSupport.KEY_AMOUNT;
import static com.etendoerp.go.schemaforge.NeoPropertyPathsTestSupport.KEY_CREDIT;
import static com.etendoerp.go.schemaforge.NeoPropertyPathsTestSupport.KEY_PARTNER;
import static com.etendoerp.go.schemaforge.NeoPropertyPathsTestSupport.PATH_AMOUNT;
import static com.etendoerp.go.schemaforge.NeoPropertyPathsTestSupport.PATH_CREDIT;
import static com.etendoerp.go.schemaforge.NeoPropertyPathsTestSupport.PATH_PARTNER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mockito;
import org.openbravo.base.model.Entity;
import org.openbravo.service.json.JsonConstants;

/**
 * Unit tests for {@link NeoPropertyPaths}, the read-only fields whose value lives on a related
 * record reached through a dotted DAL path (ETP-6148): validation of a declared row, the rewriting
 * of a read's datasource parameters ({@code _extraProperties}, {@code criteria}, {@code _sortBy},
 * {@code _distinct}, {@code _summary}), and the capture / restore of the values core writes into
 * a response record.
 *
 * <p>A class of its own: {@code NeoPropertyPaths} is a new production class. The model is the
 * mocked remittance-line graph of {@link NeoPropertyPathsTestSupport}.
 *
 * @covers com.etendoerp.go.schemaforge.NeoPropertyPaths
 */
class NeoPropertyPathsTest {

  private static final String JOINED_PARTNER = "paymentScheduleDetail$businessPartner";
  private static final String JOINED_AMOUNT = "paymentScheduleDetail$amount";

  @AfterEach
  void tearDown() {
    Mockito.framework().clearInlineMocks();
  }

  // ---------------------------------------------------------------- validation

  @Nested
  class Validation {

    private final Entity root = NeoPropertyPathsTestSupport.remittanceLineModel();

    @Test
    void aPathOfReferencesEndingOnAPropertyIsAdded() {
      NeoPropertyPaths.Builder builder = NeoPropertyPaths.builder(root);

      assertTrue(builder.add(KEY_PARTNER, PATH_PARTNER));
      assertTrue(builder.add(" " + KEY_AMOUNT + " ", " " + PATH_AMOUNT + " "), "both are trimmed");
      NeoPropertyPaths paths = builder.build(List.of());

      assertEquals(2, paths.fields().size());
      NeoPropertyPaths.PathField partner = paths.get(KEY_PARTNER);
      assertEquals(PATH_PARTNER, partner.getPath());
      assertEquals("businessPartner", partner.getTarget().getName());
      assertTrue(partner.isReference());
      assertFalse(paths.get(KEY_AMOUNT).isReference());
      assertEquals(PATH_AMOUNT, paths.get(KEY_AMOUNT).getPath());
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({
        "a missing step, paymentScheduleDetail.nope",
        "a missing first step, nope.businessPartner",
        "a step past a primitive, amount.scale",
        "a step past a primitive further down, paymentScheduleDetail.amount.scale",
        "a collection step, cancels",
        "a collection on the way, cancels.amount",
        "an empty step, paymentScheduleDetail..businessPartner",
    })
    void aPathThatDoesNotResolveIsSkipped(String reason, String path) {
      NeoPropertyPaths.Builder builder = NeoPropertyPaths.builder(root);

      assertFalse(builder.add("someKey", path), reason);
      assertTrue(builder.build(List.of()).isEmpty());
    }

    @Test
    void aBlankKeyOrPathIsSkipped() {
      NeoPropertyPaths.Builder builder = NeoPropertyPaths.builder(root);

      assertFalse(builder.add(null, PATH_PARTNER));
      assertFalse(builder.add("  ", PATH_PARTNER));
      assertFalse(builder.add(KEY_PARTNER, null));
      assertFalse(builder.add(KEY_PARTNER, " "));
      assertTrue(builder.build(List.of()).isEmpty());
    }

    @Test
    void aKeyThatIsAPropertyOfTheEntityOrAlreadyTakenIsSkipped() {
      NeoPropertyPaths.Builder builder = NeoPropertyPaths.builder(root);

      assertFalse(builder.add("amount", PATH_AMOUNT), "'amount' is the line's own property");
      assertTrue(builder.add(KEY_PARTNER, PATH_PARTNER));
      assertFalse(builder.add(KEY_PARTNER, PATH_AMOUNT), "a duplicate key keeps the first");

      NeoPropertyPaths paths = builder.build(List.of());
      assertEquals(1, paths.fields().size());
      assertEquals(PATH_PARTNER, paths.get(KEY_PARTNER).getPath());
    }

    @Test
    void aKeyAColumnFieldAlreadyUsesIsDropped() {
      NeoPropertyPaths.Builder builder = NeoPropertyPaths.builder(root);
      builder.add(KEY_PARTNER, PATH_PARTNER);
      builder.add(KEY_AMOUNT, PATH_AMOUNT);

      NeoPropertyPaths paths = builder.build(List.of("unitPrice", KEY_AMOUNT));

      assertEquals(Set.of(KEY_PARTNER), keys(paths));
    }

    @Test
    void anEntityWithoutAnyPathIsTheSharedEmptyInstance() {
      NeoPropertyPaths paths = NeoPropertyPaths.builder(root).build(List.of());

      assertTrue(paths.isEmpty());
      assertSame(NeoPropertyPaths.empty(), paths);
      assertTrue(paths.responseKeys().isEmpty());
      assertFalse(paths.isPathKey(KEY_PARTNER));
    }
  }

  // ---------------------------------------------------------------- lookups

  @Test
  void lookupsByKeyPathAndResponseKey() {
    NeoPropertyPaths paths = NeoPropertyPathsTestSupport.allPaths();

    assertNull(paths.get(null));
    assertNull(paths.get("unknown"));
    assertEquals(KEY_AMOUNT, paths.byPath(PATH_AMOUNT).getApiKey());
    assertNull(paths.byPath("paymentScheduleDetail"));
    assertTrue(paths.isPathKey(KEY_PARTNER));
    assertTrue(paths.isPathKey(KEY_PARTNER + "$_identifier"), "a reference's identifier key");
    assertFalse(paths.isPathKey(KEY_AMOUNT + "$_identifier"), "a value has no identifier key");
    assertFalse(paths.isPathKey("documentNo"));
    assertFalse(paths.isPathKey(null));
    assertEquals(new LinkedHashSet<>(List.of(KEY_PARTNER, KEY_PARTNER + "$_identifier", KEY_AMOUNT,
        KEY_CREDIT)), paths.responseKeys());
    assertEquals(JOINED_PARTNER, paths.get(KEY_PARTNER).joinedPath());
  }

  @Test
  void ofAFilterNeverAnswersNull() {
    NeoFieldFilter withoutPaths = mock(NeoFieldFilter.class);
    NeoFieldFilter withPaths = mock(NeoFieldFilter.class);
    NeoPropertyPaths paths = NeoPropertyPathsTestSupport.allPaths();
    when(withPaths.propertyPaths()).thenReturn(paths);

    assertSame(NeoPropertyPaths.empty(), NeoPropertyPaths.of(null));
    assertSame(NeoPropertyPaths.empty(), NeoPropertyPaths.of(withoutPaths),
        "a filter stubbed without paths answers none");
    assertSame(paths, NeoPropertyPaths.of(withPaths));
  }

  @Test
  void hqlPathGoesThroughTheComputedColumnsProxyOnlyForAComputedTarget() {
    NeoPropertyPaths paths = NeoPropertyPathsTestSupport.allPaths();

    assertEquals("e.paymentScheduleDetail.businessPartner", paths.get(KEY_PARTNER).hqlPath("e"));
    assertEquals("e.paymentScheduleDetail.amount", paths.get(KEY_AMOUNT).hqlPath("e"));
    assertEquals("e.paymentScheduleDetail.businessPartner."
        + Entity.COMPUTED_COLUMNS_PROXY_PROPERTY + ".creditUsed",
        paths.get(KEY_CREDIT).hqlPath("e"));
  }

  // ---------------------------------------------------------------- read params

  @Nested
  class ReadParams {

    private final NeoPropertyPaths paths = NeoPropertyPathsTestSupport.allPaths();

    @Test
    void thePathsAreAddedToExtraProperties() {
      Map<String, String> params = new HashMap<>();

      paths.applyToReadParams(params);

      assertEquals(PATH_PARTNER + "," + PATH_AMOUNT + "," + PATH_CREDIT,
          params.get(JsonConstants.ADDITIONAL_PROPERTIES_PARAMETER));
    }

    @Test
    void theClientsExtraPropertiesAreKeptAndMergedWithoutDuplicates() {
      Map<String, String> params = new HashMap<>();
      params.put(JsonConstants.ADDITIONAL_PROPERTIES_PARAMETER,
          " invoice.documentNo ,," + PATH_AMOUNT);

      paths.applyToReadParams(params);

      assertEquals("invoice.documentNo," + PATH_AMOUNT + "," + PATH_PARTNER + "," + PATH_CREDIT,
          params.get(JsonConstants.ADDITIONAL_PROPERTIES_PARAMETER));
    }

    @Test
    void aDistinctFetchGetsNoExtraPropertiesAndItsFieldIsMapped() {
      Map<String, String> params = new HashMap<>();
      params.put(JsonConstants.DISTINCT_PARAMETER, KEY_PARTNER);

      paths.applyToReadParams(params);

      assertFalse(params.containsKey(JsonConstants.ADDITIONAL_PROPERTIES_PARAMETER));
      assertEquals(JOINED_PARTNER, params.get(JsonConstants.DISTINCT_PARAMETER));
    }

    @Test
    void aDistinctOnAnOrdinaryFieldIsLeftAlone() {
      Map<String, String> params = new HashMap<>();
      params.put(JsonConstants.DISTINCT_PARAMETER, "documentNo");

      paths.applyToReadParams(params);

      assertEquals("documentNo", params.get(JsonConstants.DISTINCT_PARAMETER));
    }

    @Test
    void aSummaryFetchGetsNoExtraPropertiesAndItsKeysAreMapped() throws Exception {
      Map<String, String> params = new HashMap<>();
      params.put(JsonConstants.SUMMARY_PARAMETER,
          new JSONObject().put(KEY_AMOUNT, "sum").put("amount", "sum").toString());

      paths.applyToReadParams(params);

      assertFalse(params.containsKey(JsonConstants.ADDITIONAL_PROPERTIES_PARAMETER));
      JSONObject summary = new JSONObject(params.get(JsonConstants.SUMMARY_PARAMETER));
      assertEquals("sum", summary.getString(JOINED_AMOUNT));
      assertEquals("sum", summary.getString("amount"), "the entity's own field is untouched");
      assertFalse(summary.has(KEY_AMOUNT));
    }

    @Test
    void aSummaryWithoutAPathKeyOrNotAnObjectIsLeftAsSent() {
      Map<String, String> params = new HashMap<>();
      params.put(JsonConstants.SUMMARY_PARAMETER, "{\"amount\":\"sum\"}");
      paths.applyToReadParams(params);
      assertEquals("{\"amount\":\"sum\"}", params.get(JsonConstants.SUMMARY_PARAMETER));

      params.put(JsonConstants.SUMMARY_PARAMETER, "amount");
      paths.applyToReadParams(params);
      assertEquals("amount", params.get(JsonConstants.SUMMARY_PARAMETER));
    }

    @Test
    void criteriaFieldNamesAreRewrittenInAnArrayAnObjectAndAtAnyNesting() throws Exception {
      JSONObject nested = new JSONObject().put("operator", "or").put("criteria", new JSONArray()
          .put(new JSONObject().put("fieldName", KEY_PARTNER + "$_identifier")
              .put("operator", "iContains").put("value", "acme"))
          .put(new JSONObject().put("fieldName", "documentNo").put("value", "1")));
      JSONArray criteria = new JSONArray()
          .put(new JSONObject().put("fieldName", KEY_PARTNER).put("value", "BP-1"))
          .put(nested);
      Map<String, String> params = new HashMap<>();
      params.put("criteria", criteria.toString());

      paths.applyToReadParams(params);

      JSONArray rewritten = new JSONArray(params.get("criteria"));
      assertEquals(JOINED_PARTNER, rewritten.getJSONObject(0).getString("fieldName"));
      JSONArray inner = rewritten.getJSONObject(1).getJSONArray("criteria");
      assertEquals(JOINED_PARTNER + "$_identifier", inner.getJSONObject(0).getString("fieldName"));
      assertEquals("documentNo", inner.getJSONObject(1).getString("fieldName"));

      Map<String, String> single = new HashMap<>();
      single.put("criteria", new JSONObject().put("fieldName", KEY_AMOUNT).put("value", 5)
          .toString());
      paths.applyToReadParams(single);
      assertEquals(JOINED_AMOUNT, new JSONObject(single.get("criteria")).getString("fieldName"));
    }

    @Test
    void criteriaWithoutAPathKeyOrMalformedIsLeftAsSent() {
      Map<String, String> params = new HashMap<>();
      String plain = "[{\"fieldName\":\"documentNo\",\"value\":\"1\"}]";
      params.put("criteria", plain);
      paths.applyToReadParams(params);
      assertEquals(plain, params.get("criteria"));

      String malformed = "[{\"fieldName\":\"" + KEY_PARTNER + "\"";
      params.put("criteria", malformed);
      paths.applyToReadParams(params);
      assertEquals(malformed, params.get("criteria"), "core answers malformed criteria itself");

      params.put("criteria", "not json");
      paths.applyToReadParams(params);
      assertEquals("not json", params.get("criteria"));
    }

    @Test
    void sortByKeepsTheDirectionAndAReferenceSortsByItsIdentifier() {
      Map<String, String> params = new HashMap<>();
      params.put(JsonConstants.SORTBY_PARAMETER,
          "-" + KEY_PARTNER + ", " + KEY_AMOUNT + " desc,documentNo");

      paths.applyToReadParams(params);

      assertEquals("-" + JOINED_PARTNER + "$_identifier," + JOINED_AMOUNT + " desc,documentNo",
          params.get(JsonConstants.SORTBY_PARAMETER));
    }

    @Test
    void aSortWithoutAPathKeyIsLeftAsSent() {
      Map<String, String> params = new HashMap<>();
      params.put(JsonConstants.SORTBY_PARAMETER, "-documentNo, amount");

      paths.applyToReadParams(params);

      assertEquals("-documentNo, amount", params.get(JsonConstants.SORTBY_PARAMETER));
    }

    // ---- _use_alias: core LEFT-joins a path only in alias mode (an MCP list without filters
    // ---- had no alias, so a sort on a path key dropped every row whose path is null)

    @Test
    void anyRewriteToAPathTurnsOnAliasMode() throws Exception {
      Map<String, Map<String, String>> cases = new java.util.LinkedHashMap<>();
      cases.put("sort", new HashMap<>(Map.of(JsonConstants.SORTBY_PARAMETER, KEY_PARTNER)));
      cases.put("sort identifier", new HashMap<>(Map.of(JsonConstants.SORTBY_PARAMETER,
          "-" + KEY_PARTNER + "$_identifier")));
      cases.put("criteria", new HashMap<>(Map.of("criteria",
          new JSONObject().put("fieldName", KEY_AMOUNT).put("value", 1).toString())));
      cases.put("distinct", new HashMap<>(Map.of(JsonConstants.DISTINCT_PARAMETER, KEY_PARTNER)));
      cases.put("summary", new HashMap<>(Map.of(JsonConstants.SUMMARY_PARAMETER,
          new JSONObject().put(KEY_AMOUNT, "sum").toString())));

      for (Map.Entry<String, Map<String, String>> entry : cases.entrySet()) {
        paths.applyToReadParams(entry.getValue());

        assertEquals("true", entry.getValue().get(JsonConstants.USE_ALIAS), entry.getKey());
      }
    }

    @Test
    void aliasModeIsLeftAloneWhenNothingNamesAPath() {
      Map<String, String> realSort = new HashMap<>();
      realSort.put(JsonConstants.SORTBY_PARAMETER, "-documentNo,amount");
      realSort.put("criteria", "[{\"fieldName\":\"documentNo\",\"value\":\"1\"}]");
      paths.applyToReadParams(realSort);
      assertFalse(realSort.containsKey(JsonConstants.USE_ALIAS),
          "only real properties named: _extraProperties alone needs no alias");
      assertTrue(realSort.containsKey(JsonConstants.ADDITIONAL_PROPERTIES_PARAMETER));

      Map<String, String> noPaths = new HashMap<>();
      noPaths.put(JsonConstants.SORTBY_PARAMETER, KEY_PARTNER);
      NeoPropertyPaths.empty().applyToReadParams(noPaths);
      assertFalse(noPaths.containsKey(JsonConstants.USE_ALIAS), "an entity without path fields");
    }

    @Test
    void nothingChangesWithoutPathsOrWithoutParams() {
      Map<String, String> params = new HashMap<>();
      params.put(JsonConstants.SORTBY_PARAMETER, KEY_PARTNER);

      NeoPropertyPaths.empty().applyToReadParams(params);
      paths.applyToReadParams(null);

      assertEquals(Map.of(JsonConstants.SORTBY_PARAMETER, KEY_PARTNER), params);
    }
  }

  // ---------------------------------------------------------------- capture / restore

  @Nested
  class CaptureAndRestore {

    private final NeoPropertyPaths paths = NeoPropertyPathsTestSupport.allPaths();

    @Test
    void aReferenceIdUnderTheDottedKeyAndItsIdentifierAreHandedBackUnderTheApiKey()
        throws Exception {
      JSONObject row = new JSONObject().put("id", "LINE-1")
          .put(PATH_PARTNER, "BP-1")
          .put(JOINED_PARTNER + "$_identifier", "Acme");

      Map<String, Object> captured = paths.capture(row);
      JSONObject filtered = new JSONObject().put("id", "LINE-1");
      paths.restore(filtered, captured);

      assertEquals("BP-1", filtered.getString(KEY_PARTNER));
      assertEquals("Acme", filtered.getString(KEY_PARTNER + "$_identifier"));
      assertFalse(filtered.has(PATH_PARTNER));
    }

    @Test
    void aNullReferenceUnderTheJoinedKeyIsHandedBackAsNull() throws Exception {
      JSONObject row = new JSONObject().put(JOINED_PARTNER, JSONObject.NULL);

      JSONObject filtered = new JSONObject();
      paths.restore(filtered, paths.capture(row));

      assertTrue(filtered.has(KEY_PARTNER));
      assertTrue(filtered.isNull(KEY_PARTNER));
    }

    @Test
    void aPrimitiveValueIsHandedBackWithoutIdentifier() throws Exception {
      JSONObject row = new JSONObject().put(JOINED_AMOUNT, 12.5);

      Map<String, Object> captured = paths.capture(row);

      assertEquals(Map.of(KEY_AMOUNT, 12.5), captured);
    }

    @Test
    void aMissingValueAddsNothing() throws Exception {
      JSONObject row = new JSONObject().put("id", "LINE-1").put("documentNo", "1");

      assertTrue(paths.capture(row).isEmpty(), "a write echo did not ask for the paths");
      JSONObject filtered = new JSONObject().put("id", "LINE-1");
      paths.restore(filtered, paths.capture(row));
      assertEquals(1, filtered.length());
    }

    @Test
    void nullsAndEmptyPathsAreNoOps() {
      assertTrue(paths.capture(null).isEmpty());
      assertTrue(NeoPropertyPaths.empty().capture(new JSONObject()).isEmpty());
      paths.restore(null, Map.of(KEY_AMOUNT, 1));
    }
  }

  private static Set<String> keys(NeoPropertyPaths paths) {
    Set<String> keys = new LinkedHashSet<>();
    for (NeoPropertyPaths.PathField field : paths.fields()) {
      keys.add(field.getApiKey());
    }
    return keys;
  }
}
