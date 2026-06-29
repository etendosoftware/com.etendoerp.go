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
 * All portions are Copyright © 2021–2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.schemaforge.handlers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.HashMap;
import java.util.Map;

import org.codehaus.jettison.json.JSONObject;
import org.hibernate.query.NativeQuery;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.junit.MockitoJUnitRunner;

/**
 * Unit tests for {@link ChartOfAccountsCriteria}.
 *
 * <p>Tests cover the SQL-building operators not exercised by {@link ChartOfAccountsHandlerTest}:
 * {@code isNull}, {@code notNull}, {@code isNotNull}, {@code iNotContains}, {@code iEquals},
 * {@code iNotEqual}, {@code notEqual}, {@code inSet} (text and enum), boolean criterion paths,
 * {@code name} field routing, and {@link ChartOfAccountsCriteria#resolveLeafAccountOrderBy} edges.
 */
@RunWith(MockitoJUnitRunner.Silent.class)
public class ChartOfAccountsCriteriaTest {

  // ── buildLeafAccountWhereClause — null / empty / whitespace ──────────────

  @Test
  public void buildWhereClauseReturnsEmptyForNull() throws Exception {
    assertEquals("", ChartOfAccountsCriteria.buildLeafAccountWhereClause(null, new HashMap<>()));
  }

  @Test
  public void buildWhereClauseReturnsEmptyForEmptyString() throws Exception {
    assertEquals("", ChartOfAccountsCriteria.buildLeafAccountWhereClause("", new HashMap<>()));
  }

  @Test
  public void buildWhereClauseReturnsEmptyForWhitespace() throws Exception {
    assertEquals("", ChartOfAccountsCriteria.buildLeafAccountWhereClause("  ", new HashMap<>()));
  }

  // ── Text field (searchKey) — isNull / notNull / isNotNull ─────────────────

  @Test
  public void buildWhereClauseSearchKeyIsNull() throws Exception {
    Map<String, Object> params = new HashMap<>();
    String result = ChartOfAccountsCriteria.buildLeafAccountWhereClause(
        "{\"fieldName\":\"searchKey\",\"operator\":\"isNull\"}", params);
    assertTrue(result.contains("IS NULL"));
    assertTrue(params.isEmpty());
  }

  @Test
  public void buildWhereClauseSearchKeyIsNotNull() throws Exception {
    Map<String, Object> params = new HashMap<>();
    String result = ChartOfAccountsCriteria.buildLeafAccountWhereClause(
        "{\"fieldName\":\"searchKey\",\"operator\":\"isNotNull\"}", params);
    assertTrue(result.contains("IS NOT NULL"));
    assertTrue(params.isEmpty());
  }

  @Test
  public void buildWhereClauseSearchKeyNotNull() throws Exception {
    Map<String, Object> params = new HashMap<>();
    String result = ChartOfAccountsCriteria.buildLeafAccountWhereClause(
        "{\"fieldName\":\"searchKey\",\"operator\":\"notNull\"}", params);
    assertTrue(result.contains("IS NOT NULL"));
    assertTrue(params.isEmpty());
  }

  // ── Text field — iNotContains ─────────────────────────────────────────────

  @Test
  public void buildWhereClauseSearchKeyINotContains() throws Exception {
    Map<String, Object> params = new HashMap<>();
    String result = ChartOfAccountsCriteria.buildLeafAccountWhereClause(
        "{\"fieldName\":\"searchKey\",\"operator\":\"iNotContains\",\"value\":\"43\"}", params);
    assertTrue(result.contains("NOT ILIKE"));
    assertEquals("%43%", params.get("filter1"));
  }

  // ── Text field — iEquals / iNotEqual / notEqual ───────────────────────────

  @Test
  public void buildWhereClauseSearchKeyIEquals() throws Exception {
    Map<String, Object> params = new HashMap<>();
    String result = ChartOfAccountsCriteria.buildLeafAccountWhereClause(
        "{\"fieldName\":\"searchKey\",\"operator\":\"iEquals\",\"value\":\"TEST\"}", params);
    assertTrue(result.contains("LOWER(COALESCE("));
    assertTrue(result.contains(") = :"));
    assertEquals("test", params.get("filter1"));
  }

  @Test
  public void buildWhereClauseSearchKeyINotEqual() throws Exception {
    Map<String, Object> params = new HashMap<>();
    String result = ChartOfAccountsCriteria.buildLeafAccountWhereClause(
        "{\"fieldName\":\"searchKey\",\"operator\":\"iNotEqual\",\"value\":\"TEST\"}", params);
    assertTrue(result.contains("<>"));
    assertEquals("test", params.get("filter1"));
  }

  @Test
  public void buildWhereClauseSearchKeyNotEqual() throws Exception {
    Map<String, Object> params = new HashMap<>();
    String result = ChartOfAccountsCriteria.buildLeafAccountWhereClause(
        "{\"fieldName\":\"searchKey\",\"operator\":\"notEqual\",\"value\":\"TEST\"}", params);
    assertTrue(result.contains("<>"));
    assertEquals("test", params.get("filter1"));
  }

  // ── Text field — inSet ────────────────────────────────────────────────────

  @Test
  public void buildWhereClauseSearchKeyInSetCsv() throws Exception {
    Map<String, Object> params = new HashMap<>();
    String result = ChartOfAccountsCriteria.buildLeafAccountWhereClause(
        "{\"fieldName\":\"searchKey\",\"operator\":\"inSet\",\"value\":\"A,B\"}", params);
    assertTrue(result.contains("IN ("));
    assertEquals("a", params.get("filter1"));
    assertEquals("b", params.get("filter2"));
  }

  @Test
  public void buildWhereClauseSearchKeyInSetJsonArray() throws Exception {
    Map<String, Object> params = new HashMap<>();
    String result = ChartOfAccountsCriteria.buildLeafAccountWhereClause(
        "{\"fieldName\":\"searchKey\",\"operator\":\"inSet\",\"value\":[\"A\",\"B\"]}", params);
    assertTrue(result.contains("IN ("));
    assertEquals(2, params.size());
  }

  // ── Name field ────────────────────────────────────────────────────────────

  @Test
  public void buildWhereClauseNameFieldIContains() throws Exception {
    Map<String, Object> params = new HashMap<>();
    String result = ChartOfAccountsCriteria.buildLeafAccountWhereClause(
        "{\"fieldName\":\"name\",\"operator\":\"iContains\",\"value\":\"caja\"}", params);
    assertTrue(result.contains("name ILIKE"));
    assertEquals("%caja%", params.get("filter1"));
  }

  @Test
  public void buildWhereClauseNameFieldIsNull() throws Exception {
    Map<String, Object> params = new HashMap<>();
    String result = ChartOfAccountsCriteria.buildLeafAccountWhereClause(
        "{\"fieldName\":\"name\",\"operator\":\"isNull\"}", params);
    assertTrue(result.contains("IS NULL"));
    assertTrue(params.isEmpty());
  }

  @Test
  public void buildWhereClauseNameFieldIEquals() throws Exception {
    Map<String, Object> params = new HashMap<>();
    String result = ChartOfAccountsCriteria.buildLeafAccountWhereClause(
        "{\"fieldName\":\"name\",\"operator\":\"iEquals\",\"value\":\"Tesorería\"}", params);
    assertTrue(result.contains("LOWER(COALESCE("));
    assertFalse(params.isEmpty());
  }

  // ── Enum field (accountType) ──────────────────────────────────────────────

  @Test
  public void buildWhereClauseAccountTypeIsNull() throws Exception {
    Map<String, Object> params = new HashMap<>();
    String result = ChartOfAccountsCriteria.buildLeafAccountWhereClause(
        "{\"fieldName\":\"accountType\",\"operator\":\"isNull\"}", params);
    assertTrue(result.contains("IS NULL"));
    assertTrue(params.isEmpty());
  }

  @Test
  public void buildWhereClauseAccountTypeIsNotNull() throws Exception {
    Map<String, Object> params = new HashMap<>();
    String result = ChartOfAccountsCriteria.buildLeafAccountWhereClause(
        "{\"fieldName\":\"accountType\",\"operator\":\"isNotNull\"}", params);
    assertTrue(result.contains("IS NOT NULL"));
    assertTrue(params.isEmpty());
  }

  @Test
  public void buildWhereClauseAccountTypeNotNull() throws Exception {
    Map<String, Object> params = new HashMap<>();
    String result = ChartOfAccountsCriteria.buildLeafAccountWhereClause(
        "{\"fieldName\":\"accountType\",\"operator\":\"notNull\"}", params);
    assertTrue(result.contains("IS NOT NULL"));
  }

  @Test
  public void buildWhereClauseAccountTypeIEquals() throws Exception {
    Map<String, Object> params = new HashMap<>();
    String result = ChartOfAccountsCriteria.buildLeafAccountWhereClause(
        "{\"fieldName\":\"accountType\",\"operator\":\"iEquals\",\"value\":\"A\"}", params);
    assertTrue(result.contains("accounttype"));
    assertTrue(result.contains("= :"));
    assertEquals("A", params.get("filter1"));
  }

  @Test
  public void buildWhereClauseAccountTypeNotEqual() throws Exception {
    Map<String, Object> params = new HashMap<>();
    String result = ChartOfAccountsCriteria.buildLeafAccountWhereClause(
        "{\"fieldName\":\"accountType\",\"operator\":\"notEqual\",\"value\":\"E\"}", params);
    assertTrue(result.contains("<>"));
    assertEquals("E", params.get("filter1"));
  }

  @Test
  public void buildWhereClauseAccountTypeINotEqual() throws Exception {
    Map<String, Object> params = new HashMap<>();
    String result = ChartOfAccountsCriteria.buildLeafAccountWhereClause(
        "{\"fieldName\":\"accountType\",\"operator\":\"iNotEqual\",\"value\":\"L\"}", params);
    assertTrue(result.contains("<>"));
    assertEquals("L", params.get("filter1"));
  }

  @Test
  public void buildWhereClauseAccountTypeInSetCsv() throws Exception {
    Map<String, Object> params = new HashMap<>();
    String result = ChartOfAccountsCriteria.buildLeafAccountWhereClause(
        "{\"fieldName\":\"accountType\",\"operator\":\"inSet\",\"value\":\"A,E\"}", params);
    assertTrue(result.contains("accounttype"));
    assertTrue(result.contains("IN ("));
    assertEquals(2, params.size());
  }

  @Test
  public void buildWhereClauseAccountTypeInSetJsonArray() throws Exception {
    Map<String, Object> params = new HashMap<>();
    String result = ChartOfAccountsCriteria.buildLeafAccountWhereClause(
        "{\"fieldName\":\"accountType\",\"operator\":\"inSet\",\"value\":[\"A\",\"E\",\"L\"]}", params);
    assertTrue(result.contains("IN ("));
    assertEquals(3, params.size());
  }

  // ── Boolean field (active) ────────────────────────────────────────────────

  @Test
  public void buildWhereClauseActiveBooleanEqualsFalse() throws Exception {
    Map<String, Object> params = new HashMap<>();
    String result = ChartOfAccountsCriteria.buildLeafAccountWhereClause(
        "{\"fieldName\":\"active\",\"operator\":\"equals\",\"value\":false}", params);
    assertTrue(result.contains("isactive"));
    assertEquals("N", params.get("filter1"));
  }

  @Test
  public void buildWhereClauseActiveBooleanNotEqual() throws Exception {
    Map<String, Object> params = new HashMap<>();
    String result = ChartOfAccountsCriteria.buildLeafAccountWhereClause(
        "{\"fieldName\":\"active\",\"operator\":\"notEqual\",\"value\":true}", params);
    assertTrue(result.contains("<>"));
    assertEquals("Y", params.get("filter1"));
  }

  @Test
  public void buildWhereClauseActiveBooleanUnsupportedOperatorIsSkipped() throws Exception {
    Map<String, Object> params = new HashMap<>();
    String result = ChartOfAccountsCriteria.buildLeafAccountWhereClause(
        "{\"fieldName\":\"active\",\"operator\":\"iContains\",\"value\":true}", params);
    assertEquals("", result);
    assertTrue(params.isEmpty());
  }

  @Test
  public void buildWhereClauseActiveBooleanUnrecognizedStringValueIsSkipped() throws Exception {
    Map<String, Object> params = new HashMap<>();
    String result = ChartOfAccountsCriteria.buildLeafAccountWhereClause(
        "{\"fieldName\":\"active\",\"operator\":\"equals\",\"value\":\"maybe\"}", params);
    assertEquals("", result);
    assertTrue(params.isEmpty());
  }

  // ── Unsupported field ─────────────────────────────────────────────────────

  @Test
  public void buildWhereClauseUnsupportedFieldYtdBalanceIsIgnored() throws Exception {
    Map<String, Object> params = new HashMap<>();
    String result = ChartOfAccountsCriteria.buildLeafAccountWhereClause(
        "{\"fieldName\":\"ytdBalance\",\"operator\":\"greaterThan\",\"value\":10}", params);
    assertEquals("", result);
    assertTrue(params.isEmpty());
  }

  // ── JSON Array root payload ────────────────────────────────────────────────

  @Test
  public void buildWhereClauseJsonArrayRootPayload() throws Exception {
    Map<String, Object> params = new HashMap<>();
    String result = ChartOfAccountsCriteria.buildLeafAccountWhereClause(
        "[{\"fieldName\":\"searchKey\",\"operator\":\"iContains\",\"value\":\"10\"}]", params);
    assertTrue(result.contains("ILIKE"));
    assertEquals("%10%", params.get("filter1"));
  }

  // ── AND group where all criteria are unsupported → empty ─────────────────

  @Test
  public void buildWhereClauseGroupWithAllUnsupportedCriteriaIsEmpty() throws Exception {
    Map<String, Object> params = new HashMap<>();
    String result = ChartOfAccountsCriteria.buildLeafAccountWhereClause(
        "{\"_constructor\":\"AdvancedCriteria\",\"operator\":\"and\",\"criteria\":["
            + "{\"fieldName\":\"ytdBalance\",\"operator\":\"greaterThan\",\"value\":10},"
            + "{\"fieldName\":\"ytdCredit\",\"operator\":\"lessThan\",\"value\":5}]}",
        params);
    assertEquals("", result);
    assertTrue(params.isEmpty());
  }

  // ── resolveLeafAccountOrderBy ─────────────────────────────────────────────

  @Test
  public void resolveOrderByEmptyStringReturnsDefault() {
    assertEquals("value ASC", ChartOfAccountsCriteria.resolveLeafAccountOrderBy(""));
  }

  @Test
  public void resolveOrderByWhitespaceReturnsDefault() {
    assertEquals("value ASC", ChartOfAccountsCriteria.resolveLeafAccountOrderBy("   "));
  }

  @Test
  public void resolveOrderByAccountTypeAscending() {
    assertEquals("accounttype ASC", ChartOfAccountsCriteria.resolveLeafAccountOrderBy("accountType"));
  }

  @Test
  public void resolveOrderByAccountTypeDescending() {
    assertEquals("accounttype DESC",
        ChartOfAccountsCriteria.resolveLeafAccountOrderBy("accountType DESC"));
  }

  @Test
  public void resolveOrderByActiveAscending() {
    assertEquals("isactive ASC", ChartOfAccountsCriteria.resolveLeafAccountOrderBy("active"));
  }

  @Test
  public void resolveOrderByActiveDescendingNegativePrefix() {
    assertEquals("isactive DESC", ChartOfAccountsCriteria.resolveLeafAccountOrderBy("-active"));
  }

  @Test
  public void resolveOrderByNameDescendingNegativePrefix() {
    assertEquals("name DESC", ChartOfAccountsCriteria.resolveLeafAccountOrderBy("-name"));
  }

  @Test
  public void resolveOrderByUnsupportedFieldFallsBackToDefault() {
    assertEquals("value ASC", ChartOfAccountsCriteria.resolveLeafAccountOrderBy("ytdBalance"));
  }

  // ── Text default operator (unsupported) ───────────────────────────────────

  @Test
  public void buildWhereClauseTextUnsupportedOperatorIsSkipped() throws Exception {
    Map<String, Object> params = new HashMap<>();
    String result = ChartOfAccountsCriteria.buildLeafAccountWhereClause(
        "{\"fieldName\":\"searchKey\",\"operator\":\"startsWith\",\"value\":\"10\"}", params);
    assertEquals("", result);
    assertTrue(params.isEmpty());
  }

  // ── Enum default operator (unsupported) ──────────────────────────────────

  @Test
  public void buildWhereClauseEnumUnsupportedOperatorIsSkipped() throws Exception {
    Map<String, Object> params = new HashMap<>();
    String result = ChartOfAccountsCriteria.buildLeafAccountWhereClause(
        "{\"fieldName\":\"accountType\",\"operator\":\"greaterThan\",\"value\":\"A\"}", params);
    assertEquals("", result);
    assertTrue(params.isEmpty());
  }

  // ── Text bind helpers — missing value key ─────────────────────────────────

  @Test
  public void buildWhereClauseIContainsWithMissingValueIsSkipped() throws Exception {
    Map<String, Object> params = new HashMap<>();
    // No "value" key → normalizeTextValue returns null → bindLowercaseLike returns null
    String result = ChartOfAccountsCriteria.buildLeafAccountWhereClause(
        "{\"fieldName\":\"searchKey\",\"operator\":\"iContains\"}", params);
    assertEquals("", result);
    assertTrue(params.isEmpty());
  }

  @Test
  public void buildWhereClauseIEqualsWithMissingValueIsSkipped() throws Exception {
    Map<String, Object> params = new HashMap<>();
    String result = ChartOfAccountsCriteria.buildLeafAccountWhereClause(
        "{\"fieldName\":\"searchKey\",\"operator\":\"iEquals\"}", params);
    assertEquals("", result);
    assertTrue(params.isEmpty());
  }

  @Test
  public void buildWhereClauseInSetWithEmptyValueIsSkipped() throws Exception {
    Map<String, Object> params = new HashMap<>();
    // "value":"" → splitCriterionValues → raw == "" → normalizePlainValue returns null → empty list
    String result = ChartOfAccountsCriteria.buildLeafAccountWhereClause(
        "{\"fieldName\":\"searchKey\",\"operator\":\"inSet\",\"value\":\"\"}", params);
    assertEquals("", result);
    assertTrue(params.isEmpty());
  }

  // ── Enum bind helpers — missing/empty value ───────────────────────────────

  @Test
  public void buildWhereClauseEnumEqualsWithMissingValueIsSkipped() throws Exception {
    Map<String, Object> params = new HashMap<>();
    // No "value" key → normalizePlainValue returns null → bindPlainEquals returns null
    String result = ChartOfAccountsCriteria.buildLeafAccountWhereClause(
        "{\"fieldName\":\"accountType\",\"operator\":\"equals\"}", params);
    assertEquals("", result);
    assertTrue(params.isEmpty());
  }

  @Test
  public void buildWhereClauseEnumInSetWithEmptyValueIsSkipped() throws Exception {
    Map<String, Object> params = new HashMap<>();
    String result = ChartOfAccountsCriteria.buildLeafAccountWhereClause(
        "{\"fieldName\":\"accountType\",\"operator\":\"inSet\",\"value\":\"\"}", params);
    assertEquals("", result);
    assertTrue(params.isEmpty());
  }

  // ── criteria payload edge cases ───────────────────────────────────────────

  @Test
  public void buildWhereClauseNonObjectArrayElementIsSkipped() throws Exception {
    // An integer in the root JSONArray → buildCriteriaSql receives non-JSONObject/JSONArray → null
    Map<String, Object> params = new HashMap<>();
    String result = ChartOfAccountsCriteria.buildLeafAccountWhereClause("[42]", params);
    assertEquals("", result);
    assertTrue(params.isEmpty());
  }

  @Test
  public void buildWhereClauseGroupWithCriteriaKeyNotArrayIsSkipped() throws Exception {
    // "criteria" key exists but is not a JSONArray → optJSONArray returns null → return null
    Map<String, Object> params = new HashMap<>();
    String result = ChartOfAccountsCriteria.buildLeafAccountWhereClause(
        "{\"_constructor\":\"AdvancedCriteria\",\"operator\":\"and\",\"criteria\":\"invalid\"}",
        params);
    assertEquals("", result);
    assertTrue(params.isEmpty());
  }

  // ── toYesNoFlag string paths ──────────────────────────────────────────────

  @Test
  public void buildWhereClauseActiveBooleanStringYesIsYFlag() throws Exception {
    Map<String, Object> params = new HashMap<>();
    String result = ChartOfAccountsCriteria.buildLeafAccountWhereClause(
        "{\"fieldName\":\"active\",\"operator\":\"equals\",\"value\":\"yes\"}", params);
    assertFalse(result.isEmpty());
    assertEquals("Y", params.get("filter1"));
  }

  @Test
  public void buildWhereClauseActiveBooleanStringYIsYFlag() throws Exception {
    Map<String, Object> params = new HashMap<>();
    String result = ChartOfAccountsCriteria.buildLeafAccountWhereClause(
        "{\"fieldName\":\"active\",\"operator\":\"equals\",\"value\":\"y\"}", params);
    assertFalse(result.isEmpty());
    assertEquals("Y", params.get("filter1"));
  }

  @Test
  public void buildWhereClauseActiveBooleanStringFalseIsNFlag() throws Exception {
    Map<String, Object> params = new HashMap<>();
    String result = ChartOfAccountsCriteria.buildLeafAccountWhereClause(
        "{\"fieldName\":\"active\",\"operator\":\"equals\",\"value\":\"false\"}", params);
    assertFalse(result.isEmpty());
    assertEquals("N", params.get("filter1"));
  }

  @Test
  public void buildWhereClauseActiveBooleanStringNoIsNFlag() throws Exception {
    Map<String, Object> params = new HashMap<>();
    String result = ChartOfAccountsCriteria.buildLeafAccountWhereClause(
        "{\"fieldName\":\"active\",\"operator\":\"equals\",\"value\":\"no\"}", params);
    assertFalse(result.isEmpty());
    assertEquals("N", params.get("filter1"));
  }

  @Test
  public void buildWhereClauseActiveBooleanStringNIsNFlag() throws Exception {
    Map<String, Object> params = new HashMap<>();
    String result = ChartOfAccountsCriteria.buildLeafAccountWhereClause(
        "{\"fieldName\":\"active\",\"operator\":\"equals\",\"value\":\"n\"}", params);
    assertFalse(result.isEmpty());
    assertEquals("N", params.get("filter1"));
  }

  // ── Boolean JSON-null value ───────────────────────────────────────────────

  @Test
  public void buildWhereClauseActiveBooleanJsonNullValueIsSkipped() throws Exception {
    // value: null in JSON → JSONObject.NULL → normalizePlainValue returns null → toYesNoFlag null
    Map<String, Object> params = new HashMap<>();
    String result = ChartOfAccountsCriteria.buildLeafAccountWhereClause(
        "{\"fieldName\":\"active\",\"operator\":\"equals\",\"value\":null}", params);
    assertEquals("", result);
    assertTrue(params.isEmpty());
  }

  // ── applySqlParameters ────────────────────────────────────────────────────

  @SuppressWarnings("unchecked")
  @Test
  public void applySqlParametersSetsAllParams() {
    NativeQuery<Object> query = mock(NativeQuery.class);
    // setParameter return value is not used — no stub needed; verify invocations directly

    Map<String, Object> params = new HashMap<>();
    params.put("filter1", "%caja%");
    params.put("filter2", "Y");

    ChartOfAccountsCriteria.applySqlParameters(query, params);

    verify(query).setParameter("filter1", "%caja%");
    verify(query).setParameter("filter2", "Y");
  }
}
