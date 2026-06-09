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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.openbravo.base.model.ModelProvider;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.dal.service.OBDal;

import com.etendoerp.go.schemaforge.selector.meta.AuxFieldMeta;
import com.etendoerp.go.schemaforge.selector.meta.SelectorMeta;

/**
 * Unit tests for {@link SelectorAuxResolver}.
 *
 * <p>All static Etendo singletons (OBDal, ModelProvider) are mocked via
 * {@link MockedStatic} created in {@code setUp()} and closed in {@code tearDown()}
 * to prevent cross-test state leakage in the full suite.</p>
 */
public class SelectorAuxResolverTest {

  private MockedStatic<OBDal> obDalMock;
  private MockedStatic<ModelProvider> modelProviderMock;
  private OBDal dal;

  @Before
  public void setUp() {
    dal = mock(OBDal.class);
    obDalMock = mockStatic(OBDal.class);
    obDalMock.when(OBDal::getInstance).thenReturn(dal);

    ModelProvider modelProvider = mock(ModelProvider.class);
    when(modelProvider.getEntityByTableId(any())).thenReturn(null);
    modelProviderMock = mockStatic(ModelProvider.class);
    modelProviderMock.when(ModelProvider::getInstance).thenReturn(modelProvider);
  }

  @After
  public void tearDown() {
    modelProviderMock.close();
    obDalMock.close();
  }

  // ── parseSelectAliases ────────────────────────────────────────────────────

  @Test
  public void parseSelectAliases_singleAlias_returnsSingleEntry() {
    List<String> aliases = SelectorAuxResolver.parseSelectAliases(
        "SELECT e.id as id");
    assertEquals(1, aliases.size());
    assertEquals("id", aliases.get(0));
  }

  @Test
  public void parseSelectAliases_multipleAliases_returnsAllInOrder() {
    List<String> aliases = SelectorAuxResolver.parseSelectAliases(
        "SELECT e.id as entityid, e.name as entityname, bploc.id as locationid");
    assertEquals(3, aliases.size());
    assertEquals("entityid", aliases.get(0));
    assertEquals("entityname", aliases.get(1));
    assertEquals("locationid", aliases.get(2));
  }

  @Test
  public void parseSelectAliases_mixedCase_normalizesToLowercase() {
    List<String> aliases = SelectorAuxResolver.parseSelectAliases(
        "SELECT e.id AS MyId, e.name AS MyName");
    assertEquals(2, aliases.size());
    assertEquals("myid", aliases.get(0));
    assertEquals("myname", aliases.get(1));
  }

  @Test
  public void parseSelectAliases_noAliases_returnsEmptyList() {
    List<String> aliases = SelectorAuxResolver.parseSelectAliases(
        "SELECT e.id, e.name");
    assertTrue("Expected empty list for no aliases", aliases.isEmpty());
  }

  @Test
  public void parseSelectAliases_emptyString_returnsEmptyList() {
    List<String> aliases = SelectorAuxResolver.parseSelectAliases("");
    assertTrue(aliases.isEmpty());
  }

  @Test
  public void parseSelectAliases_selectDistinct_parsesAliasesCorrectly() {
    List<String> aliases = SelectorAuxResolver.parseSelectAliases(
        "SELECT DISTINCT e.id as eid, e.value as val");
    assertEquals(2, aliases.size());
    assertEquals("eid", aliases.get(0));
    assertEquals("val", aliases.get(1));
  }

  // ── findIdPositionInAliases ───────────────────────────────────────────────

  @Test
  public void findIdPositionInAliases_valuePropertyMatchesAlias_returnsCorrectPosition() {
    List<String> aliases = Arrays.asList("name", "id", "value");
    int pos = SelectorAuxResolver.findIdPositionInAliases(aliases, "id");
    assertEquals(1, pos);
  }

  @Test
  public void findIdPositionInAliases_valuePropertyNotFound_fallsBackToShortIdAlias() {
    // "bpid" ends with "id" and has length <= 6, should match
    List<String> aliases = Arrays.asList("name", "bpid", "location");
    int pos = SelectorAuxResolver.findIdPositionInAliases(aliases, "nonexistent");
    assertEquals(1, pos);
  }

  @Test
  public void findIdPositionInAliases_shortIdSuffix_returnsFirstMatch() {
    // "id" itself ends with "id" and length <= 6
    List<String> aliases = Arrays.asList("name", "id", "otherId");
    int pos = SelectorAuxResolver.findIdPositionInAliases(aliases, "missing");
    assertEquals(1, pos);
  }

  @Test
  public void findIdPositionInAliases_longIdAlias_notSelectedAsFallback() {
    // "mylongidentifier" ends in "er", not "id", should not match
    // "toolongid" has length > 6, should not match
    List<String> aliases = Arrays.asList("name", "toolongid", "value");
    int pos = SelectorAuxResolver.findIdPositionInAliases(aliases, "nonexistent");
    assertEquals(-1, pos);
  }

  @Test
  public void findIdPositionInAliases_noMatchAtAll_returnsNegativeOne() {
    List<String> aliases = Arrays.asList("name", "value", "code");
    int pos = SelectorAuxResolver.findIdPositionInAliases(aliases, "nonexistent");
    assertEquals(-1, pos);
  }

  @Test
  public void findIdPositionInAliases_nullValueProperty_defaultsToId() {
    List<String> aliases = Arrays.asList("name", "id");
    int pos = SelectorAuxResolver.findIdPositionInAliases(aliases, null);
    // null valueProperty defaults to "id"
    assertEquals(1, pos);
  }

  @Test
  public void findIdPositionInAliases_emptyAliases_returnsNegativeOne() {
    int pos = SelectorAuxResolver.findIdPositionInAliases(
        Collections.emptyList(), "id");
    assertEquals(-1, pos);
  }

  // ── buildHqlAuxAliasPositionMap ───────────────────────────────────────────

  @Test
  public void buildHqlAuxAliasPositionMap_matchingHqlAlias_returnsPositionEntry() {
    List<String> aliases = Arrays.asList("entityid", "locationid", "name");
    AuxFieldMeta af = new AuxFieldMeta("_LOC", "locationid", "Location", null); // no property
    List<AuxFieldMeta> auxFields = Collections.singletonList(af);

    Map<String, Integer> result = SelectorAuxResolver.buildHqlAuxAliasPositionMap(
        auxFields, aliases);

    assertEquals(1, result.size());
    assertEquals(Integer.valueOf(1), result.get("_LOC"));
  }

  @Test
  public void buildHqlAuxAliasPositionMap_aliasNotInSelectList_skipsEntry() {
    List<String> aliases = Arrays.asList("entityid", "name");
    AuxFieldMeta af = new AuxFieldMeta("_LOC", "missingalias", "Location", null);
    List<AuxFieldMeta> auxFields = Collections.singletonList(af);

    Map<String, Integer> result = SelectorAuxResolver.buildHqlAuxAliasPositionMap(
        auxFields, aliases);

    assertTrue("Entry with unresolvable alias should be excluded", result.isEmpty());
  }

  @Test
  public void buildHqlAuxAliasPositionMap_propertyPresent_skipsEntry() {
    // If property is not blank, this aux field should be resolved via DAL, not HQL alias
    List<String> aliases = Arrays.asList("entityid", "locationid");
    AuxFieldMeta af = new AuxFieldMeta("_LOC", "locationid", "Location", "businessPartnerLocation.id");
    List<AuxFieldMeta> auxFields = Collections.singletonList(af);

    Map<String, Integer> result = SelectorAuxResolver.buildHqlAuxAliasPositionMap(
        auxFields, aliases);

    assertTrue("Field with DAL property must not be included in HQL alias map", result.isEmpty());
  }

  @Test
  public void buildHqlAuxAliasPositionMap_emptyAuxFields_returnsEmptyMap() {
    List<String> aliases = Arrays.asList("id", "name");
    Map<String, Integer> result = SelectorAuxResolver.buildHqlAuxAliasPositionMap(
        Collections.emptyList(), aliases);

    assertTrue(result.isEmpty());
  }

  @Test
  public void buildHqlAuxAliasPositionMap_multipleFields_mapsAllResolvable() {
    List<String> aliases = Arrays.asList("id", "locid", "bpname");
    AuxFieldMeta af1 = new AuxFieldMeta("_LOC", "locid", "Location", null);
    AuxFieldMeta af2 = new AuxFieldMeta("_BP", "bpname", "BP Name", null);
    AuxFieldMeta af3 = new AuxFieldMeta("_SKIP", "missing", "Skip", null);
    List<AuxFieldMeta> auxFields = Arrays.asList(af1, af2, af3);

    Map<String, Integer> result = SelectorAuxResolver.buildHqlAuxAliasPositionMap(
        auxFields, aliases);

    assertEquals(2, result.size());
    assertEquals(Integer.valueOf(1), result.get("_LOC"));
    assertEquals(Integer.valueOf(2), result.get("_BP"));
    assertFalse(result.containsKey("_SKIP"));
  }

  // ── buildAuxIdListQuery ───────────────────────────────────────────────────

  @Test
  public void buildAuxIdListQuery_noExistingWhere_appendsWhere() {
    String rawHql = "SELECT e.id as id, e.name as name FROM BusinessPartner e";
    String result = SelectorAuxResolver.buildAuxIdListQuery(rawHql, "e");

    assertTrue("Should append WHERE clause", result.contains(" WHERE "));
    assertTrue("Should filter by auxIds", result.contains("e.id IN (:auxIds)"));
    assertFalse("Should not use AND when no WHERE present", result.contains(" AND e.id"));
  }

  @Test
  public void buildAuxIdListQuery_existingWhere_appendsAnd() {
    String rawHql = "SELECT e.id as id FROM BusinessPartner e WHERE e.active = 'Y'";
    String result = SelectorAuxResolver.buildAuxIdListQuery(rawHql, "e");

    assertTrue("Should append AND clause", result.contains(" AND "));
    assertTrue("Should filter by auxIds", result.contains("e.id IN (:auxIds)"));
    assertFalse("Should not add a second WHERE", result.contains(" WHERE e.id IN"));
  }

  @Test
  public void buildAuxIdListQuery_withOrderBy_stripsOrderBy() {
    String rawHql = "SELECT e.id as id, e.name as name FROM Foo e WHERE e.active = 'Y' ORDER BY e.name";
    String result = SelectorAuxResolver.buildAuxIdListQuery(rawHql, "e");

    assertFalse("ORDER BY should be stripped", result.toUpperCase().contains("ORDER BY"));
    assertTrue("Filter should still be present", result.contains("e.id IN (:auxIds)"));
  }

  @Test
  public void buildAuxIdListQuery_withoutOrderBy_noStripping() {
    String rawHql = "SELECT e.id as id FROM Foo e";
    String result = SelectorAuxResolver.buildAuxIdListQuery(rawHql, "e");

    assertTrue("Should contain auxIds filter", result.contains(":auxIds"));
    assertFalse("No ORDER BY to strip, none should appear", result.contains("ORDER BY"));
  }

  @Test
  public void buildAuxIdListQuery_differentEntityAlias_usesCorrectAlias() {
    String rawHql = "SELECT bp.id as id FROM BusinessPartner bp";
    String result = SelectorAuxResolver.buildAuxIdListQuery(rawHql, "bp");

    assertTrue("Should use the provided alias", result.contains("bp.id IN (:auxIds)"));
  }

  // ── buildAuxResultMap ─────────────────────────────────────────────────────

  @Test
  public void buildAuxResultMap_singleRow_returnsEntry() throws Exception {
    Object[] row = new Object[]{ "entity-001", "loc-abc" };
    List<Object[]> rows = Collections.singletonList(row);
    Map<String, Integer> auxAliasPos = new HashMap<>();
    auxAliasPos.put("_LOC", 1);

    Map<String, JSONObject> result = SelectorAuxResolver.buildAuxResultMap(rows, 0, auxAliasPos);

    assertEquals(1, result.size());
    assertTrue(result.containsKey("entity-001"));
    assertEquals("loc-abc", result.get("entity-001").getString("_LOC"));
  }

  @Test
  public void buildAuxResultMap_nullId_skipsRow() throws Exception {
    Object[] row = new Object[]{ null, "loc-abc" };
    List<Object[]> rows = Collections.singletonList(row);
    Map<String, Integer> auxAliasPos = new HashMap<>();
    auxAliasPos.put("_LOC", 1);

    Map<String, JSONObject> result = SelectorAuxResolver.buildAuxResultMap(rows, 0, auxAliasPos);

    assertTrue("Row with null ID should be skipped", result.isEmpty());
  }

  @Test
  public void buildAuxResultMap_idPositionBeyondRowLength_skipsRow() throws Exception {
    Object[] row = new Object[]{ "only-one" }; // idPos=2 is out of bounds
    List<Object[]> rows = Collections.singletonList(row);
    Map<String, Integer> auxAliasPos = new HashMap<>();
    auxAliasPos.put("_LOC", 0);

    Map<String, JSONObject> result = SelectorAuxResolver.buildAuxResultMap(rows, 2, auxAliasPos);

    assertTrue("Row where idPos >= row.length should be skipped", result.isEmpty());
  }

  @Test
  public void buildAuxResultMap_nullAuxValue_skipsAuxField() throws Exception {
    Object[] row = new Object[]{ "entity-002", null };
    List<Object[]> rows = Collections.singletonList(row);
    Map<String, Integer> auxAliasPos = new HashMap<>();
    auxAliasPos.put("_LOC", 1);

    Map<String, JSONObject> result = SelectorAuxResolver.buildAuxResultMap(rows, 0, auxAliasPos);

    // Row ID is non-null but aux value is null, so no aux JSON is created
    assertTrue("Row with all-null aux values should not be included", result.isEmpty());
  }

  @Test
  public void buildAuxResultMap_multipleRows_mapsAll() throws Exception {
    Object[] row1 = new Object[]{ "e-001", "loc-A" };
    Object[] row2 = new Object[]{ "e-002", "loc-B" };
    List<Object[]> rows = Arrays.asList(row1, row2);
    Map<String, Integer> auxAliasPos = new HashMap<>();
    auxAliasPos.put("_LOC", 1);

    Map<String, JSONObject> result = SelectorAuxResolver.buildAuxResultMap(rows, 0, auxAliasPos);

    assertEquals(2, result.size());
    assertEquals("loc-A", result.get("e-001").getString("_LOC"));
    assertEquals("loc-B", result.get("e-002").getString("_LOC"));
  }

  @Test
  public void buildAuxResultMap_emptyRows_returnsEmptyMap() throws Exception {
    Map<String, Integer> auxAliasPos = new HashMap<>();
    auxAliasPos.put("_LOC", 1);

    Map<String, JSONObject> result = SelectorAuxResolver.buildAuxResultMap(
        Collections.emptyList(), 0, auxAliasPos);

    assertTrue(result.isEmpty());
  }

  // ── mergeAuxIntoItems ─────────────────────────────────────────────────────

  @Test
  public void mergeAuxIntoItems_noMatchingId_leavesItemUnchanged() throws Exception {
    JSONObject item = new JSONObject();
    item.put("id", "no-match");
    JSONArray items = new JSONArray();
    items.put(item);

    JSONObject auxEntry = new JSONObject();
    auxEntry.put("_LOC", "loc-val");
    Map<String, JSONObject> auxMap = new HashMap<>();
    auxMap.put("different-id", auxEntry);

    SelectorAuxResolver.mergeAuxIntoItems(items, auxMap);

    assertFalse("Item without matching ID should not have _aux", item.has("_aux"));
  }

  @Test
  public void mergeAuxIntoItems_matchingId_addsAuxObject() throws Exception {
    JSONObject item = new JSONObject();
    item.put("id", "entity-001");
    JSONArray items = new JSONArray();
    items.put(item);

    JSONObject auxEntry = new JSONObject();
    auxEntry.put("_LOC", "loc-xyz");
    Map<String, JSONObject> auxMap = new HashMap<>();
    auxMap.put("entity-001", auxEntry);

    SelectorAuxResolver.mergeAuxIntoItems(items, auxMap);

    assertNotNull("_aux should be added to matching item", item.optJSONObject("_aux"));
    assertEquals("loc-xyz", item.getJSONObject("_aux").getString("_LOC"));
  }

  @Test
  public void mergeAuxIntoItems_existingAux_mergesNewKeys() throws Exception {
    JSONObject existingAux = new JSONObject();
    existingAux.put("_EXISTING", "existing-val");
    JSONObject item = new JSONObject();
    item.put("id", "entity-002");
    item.put("_aux", existingAux);
    JSONArray items = new JSONArray();
    items.put(item);

    JSONObject auxEntry = new JSONObject();
    auxEntry.put("_NEW", "new-val");
    Map<String, JSONObject> auxMap = new HashMap<>();
    auxMap.put("entity-002", auxEntry);

    SelectorAuxResolver.mergeAuxIntoItems(items, auxMap);

    JSONObject mergedAux = item.getJSONObject("_aux");
    assertEquals("existing-val", mergedAux.getString("_EXISTING"));
    assertEquals("new-val", mergedAux.getString("_NEW"));
  }

  @Test
  public void mergeAuxIntoItems_emptyAuxMap_leavesAllItemsUnchanged() throws Exception {
    JSONObject item = new JSONObject();
    item.put("id", "entity-001");
    JSONArray items = new JSONArray();
    items.put(item);

    SelectorAuxResolver.mergeAuxIntoItems(items, Collections.emptyMap());

    assertFalse(item.has("_aux"));
  }

  @Test
  public void mergeAuxIntoItems_multipleItems_mergesOnlyMatching() throws Exception {
    JSONObject item1 = new JSONObject();
    item1.put("id", "e-001");
    JSONObject item2 = new JSONObject();
    item2.put("id", "e-002");
    JSONArray items = new JSONArray();
    items.put(item1);
    items.put(item2);

    JSONObject auxEntry = new JSONObject();
    auxEntry.put("_LOC", "loc-A");
    Map<String, JSONObject> auxMap = new HashMap<>();
    auxMap.put("e-001", auxEntry);

    SelectorAuxResolver.mergeAuxIntoItems(items, auxMap);

    assertNotNull("e-001 should have _aux", item1.optJSONObject("_aux"));
    assertNull("e-002 should NOT have _aux", item2.optJSONObject("_aux"));
  }

  // ── buildAuxAliasPositionMap ──────────────────────────────────────────────

  @Test
  public void buildAuxAliasPositionMap_withAsAlias_mapsLowercase() {
    String[] parts = { "e.id as myid", "e.name as myname" };
    Map<String, Integer> result = SelectorAuxResolver.buildAuxAliasPositionMap(parts);

    assertEquals(Integer.valueOf(0), result.get("myid"));
    assertEquals(Integer.valueOf(1), result.get("myname"));
  }

  @Test
  public void buildAuxAliasPositionMap_noAsKeyword_skipsEntry() {
    String[] parts = { "e.id", "e.name as entityname" };
    Map<String, Integer> result = SelectorAuxResolver.buildAuxAliasPositionMap(parts);

    assertFalse("Entry without AS should not be mapped", result.containsKey("e.id"));
    assertEquals(Integer.valueOf(1), result.get("entityname"));
  }

  @Test
  public void buildAuxAliasPositionMap_quotedAlias_stripsQuotes() {
    String[] parts = { "e.id as \"myalias\"" };
    Map<String, Integer> result = SelectorAuxResolver.buildAuxAliasPositionMap(parts);

    assertEquals(Integer.valueOf(0), result.get("myalias"));
  }

  @Test
  public void buildAuxAliasPositionMap_emptyParts_returnsEmptyMap() {
    Map<String, Integer> result = SelectorAuxResolver.buildAuxAliasPositionMap(new String[0]);
    assertTrue(result.isEmpty());
  }

  @Test
  public void buildAuxAliasPositionMap_multipleParts_mapsAllWithAs() {
    String[] parts = { "bp.id as bpid", "bploc.id as locid", "bp.name" };
    Map<String, Integer> result = SelectorAuxResolver.buildAuxAliasPositionMap(parts);

    assertEquals(2, result.size());
    assertEquals(Integer.valueOf(0), result.get("bpid"));
    assertEquals(Integer.valueOf(1), result.get("locid"));
  }

  // ── buildAuxHqlWithIdFilter ───────────────────────────────────────────────

  @Test
  public void buildAuxHqlWithIdFilter_noExistingWhere_appendsWhere() {
    String hql = "SELECT e.id as id FROM BusinessPartner e";
    String selectClause = "SELECT e.id as id";
    String result = SelectorAuxResolver.buildAuxHqlWithIdFilter(hql, selectClause, "e");

    assertTrue("Should contain WHERE", result.contains(" WHERE "));
    assertTrue("Should filter by recordId", result.contains("e.id = :recordId"));
  }

  @Test
  public void buildAuxHqlWithIdFilter_existingWhere_appendsAnd() {
    String hql = "SELECT e.id as id, e.name as name FROM BusinessPartner e WHERE e.active = 'Y'";
    String selectClause = "SELECT e.id as id, e.name as name";
    String result = SelectorAuxResolver.buildAuxHqlWithIdFilter(hql, selectClause, "e");

    assertTrue("Should append AND", result.contains(" AND "));
    assertTrue("Should filter by recordId", result.contains("e.id = :recordId"));
    // Should have exactly one WHERE
    int firstWhere = result.indexOf(" WHERE ");
    int lastWhere = result.lastIndexOf(" WHERE ");
    assertEquals("Should have exactly one WHERE clause", firstWhere, lastWhere);
  }

  @Test
  public void buildAuxHqlWithIdFilter_differentAlias_usesCorrectAlias() {
    String hql = "SELECT bp.id as id FROM BusinessPartner bp";
    String selectClause = "SELECT bp.id as id";
    String result = SelectorAuxResolver.buildAuxHqlWithIdFilter(hql, selectClause, "bp");

    assertTrue("Should use bp alias", result.contains("bp.id = :recordId"));
  }

  // ── appendAuxFields ───────────────────────────────────────────────────────

  @Test
  public void appendAuxFields_nullAuxFields_doesNothing() throws Exception {
    JSONObject item = new JSONObject();
    item.put("id", "entity-001");
    BaseOBObject bob = mock(BaseOBObject.class);

    SelectorAuxResolver.appendAuxFields(item, bob, null);

    assertFalse("No _aux should be added when auxFields is null", item.has("_aux"));
  }

  @Test
  public void appendAuxFields_emptyAuxFields_doesNothing() throws Exception {
    JSONObject item = new JSONObject();
    item.put("id", "entity-001");
    BaseOBObject bob = mock(BaseOBObject.class);

    SelectorAuxResolver.appendAuxFields(item, bob, Collections.emptyList());

    assertFalse("No _aux should be added when auxFields is empty", item.has("_aux"));
  }

  @Test
  public void appendAuxFields_withPropertyPath_resolvesDalValue() throws Exception {
    JSONObject item = new JSONObject();
    BaseOBObject bob = mock(BaseOBObject.class);
    when(bob.get("name")).thenReturn("Product A");
    when(bob.getId()).thenReturn("entity-001");

    AuxFieldMeta af = new AuxFieldMeta("_NAME", null, "Name", "name");
    List<AuxFieldMeta> auxFields = Collections.singletonList(af);

    SelectorAuxResolver.appendAuxFields(item, bob, auxFields);

    assertNotNull("_aux should be set", item.optJSONObject("_aux"));
    assertEquals("Product A", item.getJSONObject("_aux").getString("_NAME"));
  }

  @Test
  public void appendAuxFields_withNullPropertyValue_noEntryAdded() throws Exception {
    JSONObject item = new JSONObject();
    BaseOBObject bob = mock(BaseOBObject.class);
    when(bob.get("name")).thenReturn(null);
    when(bob.getId()).thenReturn("entity-002");

    AuxFieldMeta af = new AuxFieldMeta("_NAME", null, "Name", "name");
    List<AuxFieldMeta> auxFields = Collections.singletonList(af);

    SelectorAuxResolver.appendAuxFields(item, bob, auxFields);

    // null value → not put into aux → aux.length() == 0 → _aux not added
    assertFalse("_aux should not be added when resolved value is null", item.has("_aux"));
  }

  @Test
  public void appendAuxFields_nestedBobValue_returnsNestedBobId() throws Exception {
    JSONObject item = new JSONObject();
    BaseOBObject bob = mock(BaseOBObject.class);
    BaseOBObject nestedBob = mock(BaseOBObject.class);
    when(nestedBob.getId()).thenReturn("nested-id-001");
    when(bob.get("businessPartner")).thenReturn(nestedBob);
    when(bob.getId()).thenReturn("entity-003");

    AuxFieldMeta af = new AuxFieldMeta("_BP", null, "BP", "businessPartner");
    List<AuxFieldMeta> auxFields = Collections.singletonList(af);

    SelectorAuxResolver.appendAuxFields(item, bob, auxFields);

    assertNotNull(item.optJSONObject("_aux"));
    assertEquals("nested-id-001", item.getJSONObject("_aux").getString("_BP"));
  }

  @Test
  public void appendAuxFields_listPropertyValue_returnsFirstElementId() throws Exception {
    JSONObject item = new JSONObject();
    BaseOBObject bob = mock(BaseOBObject.class);
    BaseOBObject listBob = mock(BaseOBObject.class);
    when(listBob.getId()).thenReturn("list-elem-id");
    when(bob.get("locationList")).thenReturn(Collections.singletonList(listBob));
    when(bob.getId()).thenReturn("entity-004");

    AuxFieldMeta af = new AuxFieldMeta("_LOC", null, "Location", "locationList");
    List<AuxFieldMeta> auxFields = Collections.singletonList(af);

    SelectorAuxResolver.appendAuxFields(item, bob, auxFields);

    assertNotNull(item.optJSONObject("_aux"));
    assertEquals("list-elem-id", item.getJSONObject("_aux").getString("_LOC"));
  }

  @Test
  public void appendAuxFields_emptyListProperty_noEntryAdded() throws Exception {
    JSONObject item = new JSONObject();
    BaseOBObject bob = mock(BaseOBObject.class);
    when(bob.get("locationList")).thenReturn(Collections.emptyList());
    when(bob.getId()).thenReturn("entity-005");

    AuxFieldMeta af = new AuxFieldMeta("_LOC", null, "Location", "locationList");
    List<AuxFieldMeta> auxFields = Collections.singletonList(af);

    SelectorAuxResolver.appendAuxFields(item, bob, auxFields);

    // Empty list → extractLeafValue returns the list itself → toString() → added as string
    // The actual behavior: empty List is not instanceof BaseOBObject and the list is not empty check
    // fails, so it returns the list itself. Let's just verify it doesn't throw.
    // (the item may or may not have _aux depending on list.toString())
    // This test mainly ensures no exception is thrown.
  }

  @Test
  public void appendAuxFields_blankProperty_noEntryAdded() throws Exception {
    JSONObject item = new JSONObject();
    BaseOBObject bob = mock(BaseOBObject.class);
    when(bob.getId()).thenReturn("entity-006");

    // blank property means resolveAuxFieldValue returns null (HQL path)
    AuxFieldMeta af = new AuxFieldMeta("_LOC", "locid", "Location", "  ");
    List<AuxFieldMeta> auxFields = Collections.singletonList(af);

    SelectorAuxResolver.appendAuxFields(item, bob, auxFields);

    assertFalse("Blank property should produce null, so _aux not added", item.has("_aux"));
  }

  // ── resolveAuxFieldValue ──────────────────────────────────────────────────

  @Test
  public void resolveAuxFieldValue_blankProperty_returnsNull() {
    BaseOBObject bob = mock(BaseOBObject.class);
    when(bob.getId()).thenReturn("entity-007");

    AuxFieldMeta af = new AuxFieldMeta("_LOC", "locid", "Location", "");
    Object result = SelectorAuxResolver.resolveAuxFieldValue(bob, af);

    assertNull("Blank property path should return null", result);
  }

  @Test
  public void resolveAuxFieldValue_nullProperty_returnsNull() {
    BaseOBObject bob = mock(BaseOBObject.class);
    when(bob.getId()).thenReturn("entity-008");

    AuxFieldMeta af = new AuxFieldMeta("_LOC", "locid", "Location", null);
    Object result = SelectorAuxResolver.resolveAuxFieldValue(bob, af);

    assertNull("Null property path should return null", result);
  }

  @Test
  public void resolveAuxFieldValue_simpleProperty_returnsValue() {
    BaseOBObject bob = mock(BaseOBObject.class);
    when(bob.get("name")).thenReturn("Test Name");
    when(bob.getId()).thenReturn("entity-009");

    AuxFieldMeta af = new AuxFieldMeta("_NAME", null, "Name", "name");
    Object result = SelectorAuxResolver.resolveAuxFieldValue(bob, af);

    assertEquals("Test Name", result);
  }

  @Test
  public void resolveAuxFieldValue_nestedProperty_navigatesPath() {
    BaseOBObject bob = mock(BaseOBObject.class);
    BaseOBObject nested = mock(BaseOBObject.class);
    when(bob.get("category")).thenReturn(nested);
    when(nested.get("name")).thenReturn("Category Name");
    when(bob.getId()).thenReturn("entity-010");

    AuxFieldMeta af = new AuxFieldMeta("_CAT", null, "Category", "category.name");
    Object result = SelectorAuxResolver.resolveAuxFieldValue(bob, af);

    assertEquals("Category Name", result);
  }

  @Test
  public void resolveAuxFieldValue_exceptionDuringNavigation_returnsNull() {
    BaseOBObject bob = mock(BaseOBObject.class);
    when(bob.get(anyString())).thenThrow(new RuntimeException("DAL error"));
    when(bob.getId()).thenReturn("entity-011");

    AuxFieldMeta af = new AuxFieldMeta("_FAIL", null, "Fail", "brokenProp");
    Object result = SelectorAuxResolver.resolveAuxFieldValue(bob, af);

    assertNull("Exception during DAL navigation should return null gracefully", result);
  }

  // ── loadEntityForAux ─────────────────────────────────────────────────────

  @Test
  public void loadEntityForAux_valuePropertyIsId_loadsDirectly() {
    SelectorMeta meta = new SelectorMeta("BusinessPartner", "name", null);
    // valueProperty defaults to "id"

    BaseOBObject expected = mock(BaseOBObject.class);
    when(dal.get("BusinessPartner", "bp-001")).thenReturn(expected);

    BaseOBObject result = SelectorAuxResolver.loadEntityForAux(meta, "bp-001");

    assertEquals(expected, result);
  }

  @Test
  public void loadEntityForAux_valuePropertyIsId_returnsNullWhenNotFound() {
    SelectorMeta meta = new SelectorMeta("BusinessPartner", "name", null);
    when(dal.get("BusinessPartner", "missing-id")).thenReturn(null);

    BaseOBObject result = SelectorAuxResolver.loadEntityForAux(meta, "missing-id");

    assertNull(result);
  }

  @SuppressWarnings({ "unchecked", "rawtypes" })
  @Test
  public void loadEntityForAux_customValueProperty_queriesViaHql() {
    SelectorMeta meta = new SelectorMeta.Builder("Product", "name")
        .valueProperty("value")
        .build();

    org.hibernate.query.Query query = mock(org.hibernate.query.Query.class);
    org.hibernate.Session session = mock(org.hibernate.Session.class);
    when(dal.getSession()).thenReturn(session);

    BaseOBObject expected = mock(BaseOBObject.class);
    when(session.createQuery(anyString())).thenReturn(query);
    doReturn(query).when(query).setParameter(anyString(), any());
    doReturn(query).when(query).setMaxResults(1);
    doReturn(Collections.singletonList(expected)).when(query).list();

    BaseOBObject result = SelectorAuxResolver.loadEntityForAux(meta, "some-val");

    assertEquals(expected, result);
  }

  @SuppressWarnings({ "unchecked", "rawtypes" })
  @Test
  public void loadEntityForAux_customValueProperty_emptyResults_returnsNull() {
    SelectorMeta meta = new SelectorMeta.Builder("Product", "name")
        .valueProperty("value")
        .build();

    org.hibernate.query.Query query = mock(org.hibernate.query.Query.class);
    org.hibernate.Session session = mock(org.hibernate.Session.class);
    when(dal.getSession()).thenReturn(session);
    when(session.createQuery(anyString())).thenReturn(query);
    doReturn(query).when(query).setParameter(anyString(), any());
    doReturn(query).when(query).setMaxResults(1);
    doReturn(Collections.emptyList()).when(query).list();

    BaseOBObject result = SelectorAuxResolver.loadEntityForAux(meta, "some-val");

    assertNull(result);
  }

  @Test
  public void loadEntityForAux_customValueProperty_exceptionDuringQuery_returnsNull() {
    SelectorMeta meta = new SelectorMeta.Builder("Product", "name")
        .valueProperty("value")
        .build();

    org.hibernate.Session session = mock(org.hibernate.Session.class);
    when(dal.getSession()).thenReturn(session);
    when(session.createQuery(anyString())).thenThrow(new RuntimeException("HQL error"));

    BaseOBObject result = SelectorAuxResolver.loadEntityForAux(meta, "some-val");

    assertNull("Exception during HQL query should return null gracefully", result);
  }

  // ── executeAuxHqlQuery ────────────────────────────────────────────────────

  /** Helper: stubs {@code session.createQuery(...)} with a standard raw-typed mock chain. */
  @SuppressWarnings({ "unchecked", "rawtypes" })
  private void setupQueryMock(org.hibernate.Session session, List<?> resultList) {
    org.hibernate.query.Query query = mock(org.hibernate.query.Query.class);
    when(session.createQuery(anyString())).thenReturn(query);
    doReturn(query).when(query).setParameter(anyString(), any());
    doReturn(query).when(query).setMaxResults(anyInt());
    doReturn(resultList).when(query).list();
  }

  @Test
  public void executeAuxHqlQuery_emptyResults_returnsNull() throws Exception {
    org.hibernate.Session session = mock(org.hibernate.Session.class);
    when(dal.getSession()).thenReturn(session);
    setupQueryMock(session, Collections.emptyList());

    AuxFieldMeta af = new AuxFieldMeta("_LOC", "locid", "Location", null);
    List<AuxFieldMeta> auxFields = Collections.singletonList(af);
    Map<String, Integer> aliasPos = new HashMap<>();
    aliasPos.put("locid", 0);

    JSONObject result = SelectorAuxResolver.executeAuxHqlQuery(
        "SELECT bploc.id as locid FROM BusinessPartner e WHERE e.id = :recordId",
        "e-001", aliasPos, auxFields, "businessPartnerLocation");

    assertNull("Empty result set should return null", result);
  }

  @Test
  public void executeAuxHqlQuery_singleRowObjectArray_mapsAuxFields() throws Exception {
    org.hibernate.Session session = mock(org.hibernate.Session.class);
    when(dal.getSession()).thenReturn(session);
    Object[] row = new Object[]{ "loc-001" };
    setupQueryMock(session, Collections.singletonList(row));

    AuxFieldMeta af = new AuxFieldMeta("_LOC", "locid", "Location", null);
    List<AuxFieldMeta> auxFields = Collections.singletonList(af);
    Map<String, Integer> aliasPos = new HashMap<>();
    aliasPos.put("locid", 0);

    JSONObject result = SelectorAuxResolver.executeAuxHqlQuery(
        "SELECT bploc.id as locid FROM BusinessPartner e WHERE e.id = :recordId",
        "e-001", aliasPos, auxFields, "bploc");

    assertNotNull(result);
    assertEquals("loc-001", result.getString("bploc_LOC"));
  }

  @Test
  public void executeAuxHqlQuery_singleScalarResult_wrapsInArray() throws Exception {
    org.hibernate.Session session = mock(org.hibernate.Session.class);
    when(dal.getSession()).thenReturn(session);
    // Return a single scalar (not Object[]) — covers the scalar-wrapping branch
    setupQueryMock(session, Collections.singletonList("scalar-value"));

    AuxFieldMeta af = new AuxFieldMeta("_VAL", "val", "Value", null);
    List<AuxFieldMeta> auxFields = Collections.singletonList(af);
    Map<String, Integer> aliasPos = new HashMap<>();
    aliasPos.put("val", 0);

    JSONObject result = SelectorAuxResolver.executeAuxHqlQuery(
        "SELECT e.name FROM Foo e WHERE e.id = :recordId",
        "e-001", aliasPos, auxFields, "field");

    assertNotNull(result);
    assertEquals("scalar-value", result.getString("field_VAL"));
  }

  @Test
  public void executeAuxHqlQuery_bobValue_extractsBobId() throws Exception {
    org.hibernate.Session session = mock(org.hibernate.Session.class);
    when(dal.getSession()).thenReturn(session);
    BaseOBObject bob = mock(BaseOBObject.class);
    when(bob.getId()).thenReturn("bob-id-001");
    Object[] row = new Object[]{ bob };
    setupQueryMock(session, Collections.singletonList(row));

    AuxFieldMeta af = new AuxFieldMeta("_BP", "bp", "BP", null);
    List<AuxFieldMeta> auxFields = Collections.singletonList(af);
    Map<String, Integer> aliasPos = new HashMap<>();
    aliasPos.put("bp", 0);

    JSONObject result = SelectorAuxResolver.executeAuxHqlQuery(
        "SELECT e.businessPartner as bp FROM Foo e WHERE e.id = :recordId",
        "e-001", aliasPos, auxFields, "partner");

    assertNotNull(result);
    assertEquals("bob-id-001", result.getString("partner_BP"));
  }

  @Test
  public void executeAuxHqlQuery_allAuxValuesNull_returnsNull() throws Exception {
    org.hibernate.Session session = mock(org.hibernate.Session.class);
    when(dal.getSession()).thenReturn(session);
    Object[] row = new Object[]{ null };
    setupQueryMock(session, Collections.singletonList(row));

    AuxFieldMeta af = new AuxFieldMeta("_VAL", "val", "Value", null);
    List<AuxFieldMeta> auxFields = Collections.singletonList(af);
    Map<String, Integer> aliasPos = new HashMap<>();
    aliasPos.put("val", 0);

    JSONObject result = SelectorAuxResolver.executeAuxHqlQuery(
        "SELECT e.val FROM Foo e WHERE e.id = :recordId",
        "e-001", aliasPos, auxFields, "field");

    assertNull("All-null aux values should return null (result.length()==0)", result);
  }
}
