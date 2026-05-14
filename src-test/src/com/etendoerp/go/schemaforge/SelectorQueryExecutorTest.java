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
package com.etendoerp.go.schemaforge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.hibernate.Session;
import org.hibernate.query.Query;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.ModelProvider;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.dal.service.OBDal;
import org.openbravo.dal.service.OBQuery;

import com.etendoerp.go.schemaforge.selector.meta.SelectorMeta;

/**
 * Unit tests for {@link SelectorQueryExecutor} covering routing, property resolution,
 * language extraction, and country-translation enrichment.
 */
public class SelectorQueryExecutorTest {

  // ---------------------------------------------------------------
  // Reflection helpers
  // ---------------------------------------------------------------

  /**
   * Invokes the private {@code resolvePropertyValue} method.
   */
  private static Object invokeResolveProperty(BaseOBObject bob, String path) throws Exception {
    Method m = SelectorQueryExecutor.class.getDeclaredMethod("resolvePropertyValue", BaseOBObject.class, String.class);
    m.setAccessible(true);
    return m.invoke(null, bob, path);
  }

  /**
   * Invokes the private {@code resolveRichItemId} method.
   */
  private static String invokeResolveRichItemId(BaseOBObject bob, SelectorMeta meta) throws Exception {
    Method m = SelectorQueryExecutor.class.getDeclaredMethod("resolveRichItemId", BaseOBObject.class,
        SelectorMeta.class);
    m.setAccessible(true);
    return (String) m.invoke(null, bob, meta);
  }

  /**
   * Invokes the private {@code enrichCountryTranslations} method.
   */
  private static void invokeEnrich(JSONArray items, String entityName, String language) throws Exception {
    Method m = SelectorQueryExecutor.class.getDeclaredMethod("enrichCountryTranslations", JSONArray.class, String.class,
        String.class);
    m.setAccessible(true);
    m.invoke(null, items, entityName, language);
  }

  // ---------------------------------------------------------------
  // resolvePropertyValue
  // ---------------------------------------------------------------

  /**
   * Sets up the void NeoSelectorExecutionHelper static mocks needed by executeQuery.
   */
  @SuppressWarnings("unchecked")
  private static void setupVoidHelperMocks(MockedStatic<NeoSelectorExecutionHelper> helperMock) {
    helperMock.when(() -> NeoSelectorExecutionHelper.appendResolvedWhereClause(any(), any(), any())).thenAnswer(
        inv -> null);
    helperMock.when(() -> NeoSelectorExecutionHelper.appendLiteralFilter(any(), any())).thenAnswer(inv -> null);
    helperMock.when(
        () -> NeoSelectorExecutionHelper.appendSelectorOrganizationFilter(any(), any(), any(), any())).thenAnswer(
        inv -> null);
    helperMock.when(() -> NeoSelectorExecutionHelper.appendSimpleSearchFilter(any(), any(), any())).thenAnswer(
        inv -> null);
    helperMock.when(() -> NeoSelectorExecutionHelper.buildSimpleWhereClause(any())).thenReturn("");
    helperMock.when(() -> NeoSelectorExecutionHelper.bindNamedParameters(any(OBQuery.class), any())).thenAnswer(
        inv -> null);
  }

  /**
   * A single-segment path returns the value stored in the BOB for that property.
   */
  @Test
  public void testResolvePropertyValueSimplePropertyReturnsValue() throws Exception {
    BaseOBObject bob = mock(BaseOBObject.class);
    when(bob.get("name")).thenReturn("España");

    assertEquals("España", invokeResolveProperty(bob, "name"));
  }

  /**
   * A dotted path traverses nested BaseOBObjects and returns the leaf primitive value.
   */
  @Test
  public void testResolvePropertyValueDottedPathTraversesNestedObject() throws Exception {
    BaseOBObject root = mock(BaseOBObject.class);
    BaseOBObject nested = mock(BaseOBObject.class);
    when(root.get("address")).thenReturn(nested);
    when(nested.get("city")).thenReturn("Madrid");

    assertEquals("Madrid", invokeResolveProperty(root, "address.city"));
  }

  /**
   * A null value mid-path causes traversal to stop and return null.
   */
  @Test
  public void testResolvePropertyValueNullMidPathReturnsNull() throws Exception {
    BaseOBObject bob = mock(BaseOBObject.class);
    when(bob.get("address")).thenReturn(null);

    assertNull(invokeResolveProperty(bob, "address.city"));
  }

  /**
   * When a mid-path value is not a BOB, traversal stops and that value is returned.
   */
  @Test
  public void testResolvePropertyValueNonBobValueReturnsEarly() throws Exception {
    BaseOBObject bob = mock(BaseOBObject.class);
    when(bob.get("code")).thenReturn("EUR");

    assertEquals("EUR", invokeResolveProperty(bob, "code.symbol"));
  }

  /**
   * A leaf that is itself a BOB returns its identifier string rather than the object.
   */
  @Test
  public void testResolvePropertyValueFinalBobReturnsIdentifier() throws Exception {
    BaseOBObject root = mock(BaseOBObject.class);
    BaseOBObject leaf = mock(BaseOBObject.class);
    when(root.get("currency")).thenReturn(leaf);
    when(leaf.getIdentifier()).thenReturn("EUR - Euro");

    assertEquals("EUR - Euro", invokeResolveProperty(root, "currency"));
  }

  // ---------------------------------------------------------------
  // resolveRichItemId
  // ---------------------------------------------------------------

  /**
   * An exception thrown during property access is caught and null is returned.
   */
  @Test
  public void testResolvePropertyValueExceptionReturnsNull() throws Exception {
    BaseOBObject bob = mock(BaseOBObject.class);
    when(bob.get("name")).thenThrow(new RuntimeException("access denied"));

    assertNull(invokeResolveProperty(bob, "name"));
  }

  /**
   * When valueProperty is "id" the method falls back to the normalized entity ID.
   */
  @Test
  @SuppressWarnings("unchecked")
  public void testResolveRichItemIdDefaultIdPropertyFallsBackToNormalizedId() throws Exception {
    BaseOBObject bob = mock(BaseOBObject.class);
    when(bob.getId()).thenReturn("47");

    SelectorMeta meta = new SelectorMeta.Builder("Country", "name").build();

    try (MockedStatic<SelectorRowMapper> mapperMock = mockStatic(SelectorRowMapper.class)) {
      mapperMock.when(() -> SelectorRowMapper.normalizeEntityId("47")).thenReturn("47");
      assertEquals("47", invokeResolveRichItemId(bob, meta));
    }
  }

  /**
   * When valueProperty is null the method falls back to the normalized entity ID.
   */
  @Test
  @SuppressWarnings("unchecked")
  public void testResolveRichItemIdNullValuePropertyFallsBackToNormalizedId() throws Exception {
    BaseOBObject bob = mock(BaseOBObject.class);
    when(bob.getId()).thenReturn("47");

    SelectorMeta meta = new SelectorMeta.Builder("Country", "name").valueProperty(null).build();

    try (MockedStatic<SelectorRowMapper> mapperMock = mockStatic(SelectorRowMapper.class)) {
      mapperMock.when(() -> SelectorRowMapper.normalizeEntityId("47")).thenReturn("47");
      assertEquals("47", invokeResolveRichItemId(bob, meta));
    }
  }

  /**
   * When valueProperty is a custom property with a non-null value, that value is returned.
   */
  @Test
  public void testResolveRichItemIdCustomPropertyReturnsPropertyValue() throws Exception {
    BaseOBObject bob = mock(BaseOBObject.class);
    when(bob.get("searchKey")).thenReturn("BP-001");
    when(bob.getId()).thenReturn("47");

    SelectorMeta meta = new SelectorMeta.Builder("BusinessPartner", "name").valueProperty("searchKey").build();

    assertEquals("BP-001", invokeResolveRichItemId(bob, meta));
  }

  // ---------------------------------------------------------------
  // execute — routing based on meta.isRich
  // ---------------------------------------------------------------

  /**
   * When a custom valueProperty resolves to null, the method falls back to the normalized entity ID.
   */
  @Test
  @SuppressWarnings("unchecked")
  public void testResolveRichItemIdNullCustomPropertyFallsBackToNormalizedId() throws Exception {
    BaseOBObject bob = mock(BaseOBObject.class);
    when(bob.get("searchKey")).thenReturn(null);
    when(bob.getId()).thenReturn("47");

    SelectorMeta meta = new SelectorMeta.Builder("BusinessPartner", "name").valueProperty("searchKey").build();

    try (MockedStatic<SelectorRowMapper> mapperMock = mockStatic(SelectorRowMapper.class)) {
      mapperMock.when(() -> SelectorRowMapper.normalizeEntityId("47")).thenReturn("47");
      assertEquals("47", invokeResolveRichItemId(bob, meta));
    }
  }

  /**
   * A non-rich selector meta routes through executeQuery and returns a valid response.
   */
  @Test
  @SuppressWarnings("unchecked")
  public void testExecuteNonRichMetaRoutesToExecuteQuery() throws Exception {
    SelectorMeta meta = new SelectorMeta("Country", "name", null);

    OBQuery countQuery = mock(OBQuery.class);
    OBQuery dataQuery = mock(OBQuery.class);
    when(countQuery.count()).thenReturn(0);
    when(dataQuery.list()).thenReturn(Collections.emptyList());

    OBDal obDal = mock(OBDal.class);
    when(obDal.createQuery(anyString(), anyString())).thenReturn(countQuery, dataQuery);

    NeoResponse expected = NeoResponse.ok(new JSONObject());

    try (MockedStatic<NeoSelectorExecutionHelper> helperMock = mockStatic(
        NeoSelectorExecutionHelper.class); MockedStatic<OBDal> obDalMock = mockStatic(
        OBDal.class); MockedStatic<SelectorResponseSupport> respMock = mockStatic(SelectorResponseSupport.class)) {

      helperMock.when(() -> NeoSelectorExecutionHelper.appendResolvedWhereClause(any(), any(), any())).thenAnswer(
          inv -> null);
      helperMock.when(() -> NeoSelectorExecutionHelper.appendLiteralFilter(any(), any())).thenAnswer(inv -> null);
      helperMock.when(
          () -> NeoSelectorExecutionHelper.appendSelectorOrganizationFilter(any(), any(), any(), any())).thenAnswer(
          inv -> null);
      helperMock.when(() -> NeoSelectorExecutionHelper.appendSimpleSearchFilter(any(), any(), any())).thenAnswer(
          inv -> null);
      helperMock.when(() -> NeoSelectorExecutionHelper.buildSimpleWhereClause(any())).thenReturn("");
      helperMock.when(() -> NeoSelectorExecutionHelper.bindNamedParameters(any(OBQuery.class), any())).thenAnswer(
          inv -> null);
      obDalMock.when(OBDal::getInstance).thenReturn(obDal);
      respMock.when(
          () -> SelectorResponseSupport.buildSelectorResponse(any(), any(), anyInt(), anyInt(), anyInt())).thenReturn(
          expected);

      NeoResponse result = SelectorQueryExecutor.execute(meta, "", 20, 0, null, "org-1", null);
      assertNotNull(result);
      assertEquals(expected, result);
    }
  }

  /**
   * A rich (non-custom) selector meta routes through executeRichQuery and returns a valid response.
   */
  @Test
  @SuppressWarnings("unchecked")
  public void testExecuteRichMetaRoutesToExecuteRichQuery() throws Exception {
    SelectorMeta meta = new SelectorMeta.Builder("Country", "name").isRich(true).build();

    OBQuery countQuery = mock(OBQuery.class);
    OBQuery dataQuery = mock(OBQuery.class);
    when(countQuery.count()).thenReturn(0);
    when(dataQuery.list()).thenReturn(Collections.emptyList());

    OBDal obDal = mock(OBDal.class);
    when(obDal.createQuery(anyString(), anyString())).thenReturn(countQuery, dataQuery);

    NeoResponse expected = NeoResponse.ok(new JSONObject());
    SelectorQueryBuilder.HqlWithParams emptyClause = SelectorQueryBuilder.HqlWithParams.empty();

    try (MockedStatic<SelectorQueryBuilder> builderMock = mockStatic(
        SelectorQueryBuilder.class); MockedStatic<NeoSelectorExecutionHelper> helperMock = mockStatic(
        NeoSelectorExecutionHelper.class); MockedStatic<OBDal> obDalMock = mockStatic(
        OBDal.class); MockedStatic<SelectorResponseSupport> respMock = mockStatic(SelectorResponseSupport.class)) {

      builderMock.when(
          () -> SelectorQueryBuilder.buildRichQueryWhereClause(any(), any(), any(), any(), any())).thenReturn(
          emptyClause);
      helperMock.when(() -> NeoSelectorExecutionHelper.bindNamedParameters(any(OBQuery.class), any())).thenAnswer(
          inv -> null);
      obDalMock.when(OBDal::getInstance).thenReturn(obDal);
      respMock.when(() -> SelectorResponseSupport.buildGridColumnMetadata(any())).thenReturn(new JSONArray());
      respMock.when(
          () -> SelectorResponseSupport.buildSelectorResponse(any(), any(), anyInt(), anyInt(), anyInt())).thenReturn(
          expected);

      NeoResponse result = SelectorQueryExecutor.execute(meta, "", 20, 0, null, "org-1", null);
      assertNotNull(result);
      assertEquals(expected, result);
    }
  }

  // ---------------------------------------------------------------
  // executeQuery — language handling
  // ---------------------------------------------------------------

  /**
   * A rich custom selector meta with HQL that lacks a FROM clause throws IllegalArgumentException.
   */
  @Test
  public void testExecuteCustomHqlQueryMissingFromClauseThrowsIllegalArgument() {
    SelectorMeta meta = new SelectorMeta.Builder("Country", "name").isRich(true).isCustomQuery(true).customHql(
        "SELECT e.id, e.name").build();

    try {
      SelectorQueryExecutor.execute(meta, "", 20, 0, null, "org-1", null);
    } catch (Exception e) {
      Throwable cause = (e instanceof InvocationTargetException) ? ((InvocationTargetException) e).getTargetException() : e;
      assertTrue("Expected IllegalArgumentException but got: " + cause.getClass().getName(),
          cause instanceof IllegalArgumentException);
      assertTrue(cause.getMessage().contains("FROM"));
      return;
    }
    throw new AssertionError("Expected an exception but none was thrown");
  }

  /**
   * Passing language=en_US skips enrichCountryTranslations — no CountryTrl OBDal query is made.
   */
  @Test
  @SuppressWarnings("unchecked")
  public void testExecuteQueryEnUsLanguageSkipsEnrichment() throws Exception {
    SelectorMeta meta = new SelectorMeta("Country", "name", null);
    Map<String, Object> params = new HashMap<>();
    params.put("language", "en_US");

    OBQuery countQuery = mock(OBQuery.class);
    OBQuery dataQuery = mock(OBQuery.class);
    when(countQuery.count()).thenReturn(0);
    when(dataQuery.list()).thenReturn(Collections.emptyList());

    OBDal obDal = mock(OBDal.class);
    // Only 2 calls expected (count + data) — any 3rd call would fail without a stub
    when(obDal.createQuery(anyString(), anyString())).thenReturn(countQuery, dataQuery);

    try (MockedStatic<NeoSelectorExecutionHelper> helperMock = mockStatic(
        NeoSelectorExecutionHelper.class); MockedStatic<OBDal> obDalMock = mockStatic(
        OBDal.class); MockedStatic<SelectorResponseSupport> respMock = mockStatic(SelectorResponseSupport.class)) {

      setupVoidHelperMocks(helperMock);
      obDalMock.when(OBDal::getInstance).thenReturn(obDal);
      respMock.when(
          () -> SelectorResponseSupport.buildSelectorResponse(any(), any(), anyInt(), anyInt(), anyInt())).thenReturn(
          NeoResponse.ok(new JSONObject()));

      NeoResponse result = SelectorQueryExecutor.execute(meta, "", 20, 0, null, "org-1", params);
      assertNotNull(result);
    }
  }

  /**
   * Passing language=null skips enrichCountryTranslations — no CountryTrl OBDal query is made.
   */
  @Test
  @SuppressWarnings("unchecked")
  public void testExecuteQueryNullLanguageSkipsEnrichment() throws Exception {
    SelectorMeta meta = new SelectorMeta("Country", "name", null);

    OBQuery countQuery = mock(OBQuery.class);
    OBQuery dataQuery = mock(OBQuery.class);
    when(countQuery.count()).thenReturn(0);
    when(dataQuery.list()).thenReturn(Collections.emptyList());

    OBDal obDal = mock(OBDal.class);
    when(obDal.createQuery(anyString(), anyString())).thenReturn(countQuery, dataQuery);

    try (MockedStatic<NeoSelectorExecutionHelper> helperMock = mockStatic(
        NeoSelectorExecutionHelper.class); MockedStatic<OBDal> obDalMock = mockStatic(
        OBDal.class); MockedStatic<SelectorResponseSupport> respMock = mockStatic(SelectorResponseSupport.class)) {

      setupVoidHelperMocks(helperMock);
      obDalMock.when(OBDal::getInstance).thenReturn(obDal);
      respMock.when(
          () -> SelectorResponseSupport.buildSelectorResponse(any(), any(), anyInt(), anyInt(), anyInt())).thenReturn(
          NeoResponse.ok(new JSONObject()));

      NeoResponse result = SelectorQueryExecutor.execute(meta, "", 20, 0, null, "org-1", null);
      assertNotNull(result);
    }
  }

  /**
   * language=es_ES with a Country entity triggers enrichment and replaces item labels.
   */
  @Test
  @SuppressWarnings("unchecked")
  public void testExecuteQueryEsEsLanguageEnrichesCountryLabels() throws Exception {
    SelectorMeta meta = new SelectorMeta("Country", "name", null);
    Map<String, Object> params = new HashMap<>();
    params.put("language", "es_ES");

    BaseOBObject bob = mock(BaseOBObject.class);
    when(bob.getId()).thenReturn("47");
    when(bob.getIdentifier()).thenReturn("Spain");

    OBQuery countQuery = mock(OBQuery.class);
    OBQuery dataQuery = mock(OBQuery.class);
    OBQuery trlQuery = mock(OBQuery.class);
    when(countQuery.count()).thenReturn(1);
    when(dataQuery.list()).thenReturn(Collections.singletonList(bob));

    BaseOBObject country = mock(BaseOBObject.class);
    when(country.getId()).thenReturn("47");
    BaseOBObject trl = mock(BaseOBObject.class);
    when(trl.get("country")).thenReturn(country);
    when(trl.get("name")).thenReturn("España");
    when(trlQuery.list()).thenReturn(Collections.singletonList(trl));

    OBDal obDal = mock(OBDal.class);
    when(obDal.createQuery(anyString(), anyString())).thenReturn(countQuery, dataQuery, trlQuery);

    try (MockedStatic<NeoSelectorExecutionHelper> helperMock = mockStatic(
        NeoSelectorExecutionHelper.class); MockedStatic<SelectorRowMapper> mapperMock = mockStatic(
        SelectorRowMapper.class); MockedStatic<OBDal> obDalMock = mockStatic(
        OBDal.class); MockedStatic<SelectorResponseSupport> respMock = mockStatic(SelectorResponseSupport.class)) {

      setupVoidHelperMocks(helperMock);
      mapperMock.when(() -> SelectorRowMapper.normalizeEntityId("47")).thenReturn("47");
      obDalMock.when(OBDal::getInstance).thenReturn(obDal);

      JSONArray[] capturedItems = new JSONArray[1];
      respMock.when(
          () -> SelectorResponseSupport.buildSelectorResponse(any(), any(), anyInt(), anyInt(), anyInt())).thenAnswer(
          inv -> {
            capturedItems[0] = inv.getArgument(0);
            return NeoResponse.ok(new JSONObject());
          });

      SelectorQueryExecutor.execute(meta, "", 20, 0, null, "org-1", params);

      assertNotNull(capturedItems[0]);
      assertEquals("España", capturedItems[0].getJSONObject(0).getString("label"));
    }
  }

  // ---------------------------------------------------------------
  // enrichCountryTranslations — entity guard
  // ---------------------------------------------------------------

  /**
   * language is removed from extraFilterParams before it is passed to HQL binding.
   */
  @Test
  @SuppressWarnings("unchecked")
  public void testExecuteQueryLanguageNotLeakedToHqlParams() throws Exception {
    SelectorMeta meta = new SelectorMeta("BusinessPartner", "name", null);
    Map<String, Object> params = new HashMap<>();
    params.put("language", "es_ES");
    params.put("someHqlParam", "value");

    OBQuery countQuery = mock(OBQuery.class);
    OBQuery dataQuery = mock(OBQuery.class);
    when(countQuery.count()).thenReturn(0);
    when(dataQuery.list()).thenReturn(Collections.emptyList());

    OBDal obDal = mock(OBDal.class);
    when(obDal.createQuery(anyString(), anyString())).thenReturn(countQuery, dataQuery);

    Map<String, Object>[] capturedParams = new Map[1];

    try (MockedStatic<NeoSelectorExecutionHelper> helperMock = mockStatic(
        NeoSelectorExecutionHelper.class); MockedStatic<OBDal> obDalMock = mockStatic(
        OBDal.class); MockedStatic<SelectorResponseSupport> respMock = mockStatic(SelectorResponseSupport.class)) {

      setupVoidHelperMocks(helperMock);
      helperMock.when(() -> NeoSelectorExecutionHelper.bindNamedParameters(any(OBQuery.class), any())).thenAnswer(
          inv -> {
            Map<String, Object> p = inv.getArgument(1);
            if (capturedParams[0] == null && p != null) capturedParams[0] = p;
            return null;
          });
      obDalMock.when(OBDal::getInstance).thenReturn(obDal);
      respMock.when(
          () -> SelectorResponseSupport.buildSelectorResponse(any(), any(), anyInt(), anyInt(), anyInt())).thenReturn(
          NeoResponse.ok(new JSONObject()));

      SelectorQueryExecutor.execute(meta, "", 20, 0, null, "org-1", params);
    }

    assertNotNull(capturedParams[0]);
    assertFalse("language must not be in HQL params", capturedParams[0].containsKey("language"));
    assertTrue("other params must still be present", capturedParams[0].containsKey("someHqlParam"));
  }

  // ---------------------------------------------------------------
  // enrichCountryTranslations — empty items guard
  // ---------------------------------------------------------------

  /**
   * A non-Country entity causes enrichCountryTranslations to return early without modifying items.
   */
  @Test
  public void testEnrichCountryTranslationsNonCountryEntityIsNoOp() throws Exception {
    JSONArray items = new JSONArray();
    JSONObject item = new JSONObject();
    item.put("id", "1");
    item.put("label", "Spain");
    items.put(item);

    invokeEnrich(items, "BusinessPartner", "es_ES");

    assertEquals("Spain", items.getJSONObject(0).getString("label"));
  }

  // ---------------------------------------------------------------
  // enrichCountryTranslations — translation applied
  // ---------------------------------------------------------------

  /**
   * An empty items array causes enrichCountryTranslations to return before reaching OBDal.
   */
  @Test
  public void testEnrichCountryTranslationsEmptyItemsDoesNotCallOBDal() throws Exception {
    // If OBDal were reached, it would throw without a mock.
    // No exception here confirms the early-return before the query.
    invokeEnrich(new JSONArray(), "Country", "es_ES");
  }

  // ---------------------------------------------------------------
  // enrichCountryTranslations — missing translation keeps original
  // ---------------------------------------------------------------

  /**
   * Country items are relabelled with translated names from CountryTrl when a translation exists.
   */
  @Test
  @SuppressWarnings("unchecked")
  public void testEnrichCountryTranslationsReplacesLabelsWithTranslatedNames() throws Exception {
    JSONArray items = new JSONArray();
    JSONObject item1 = new JSONObject();
    item1.put("id", "47");
    item1.put("label", "Spain");
    items.put(item1);
    JSONObject item2 = new JSONObject();
    item2.put("id", "10");
    item2.put("label", "Argentina");
    items.put(item2);

    BaseOBObject country1 = mock(BaseOBObject.class);
    when(country1.getId()).thenReturn("47");
    BaseOBObject trl1 = mock(BaseOBObject.class);
    when(trl1.get("country")).thenReturn(country1);
    when(trl1.get("name")).thenReturn("España");

    BaseOBObject country2 = mock(BaseOBObject.class);
    when(country2.getId()).thenReturn("10");
    BaseOBObject trl2 = mock(BaseOBObject.class);
    when(trl2.get("country")).thenReturn(country2);
    when(trl2.get("name")).thenReturn("Argentina");

    OBQuery<BaseOBObject> trlQuery = mock(OBQuery.class);
    when(trlQuery.list()).thenReturn(Arrays.asList(trl1, trl2));

    OBDal obDal = mock(OBDal.class);
    when(obDal.createQuery(anyString(), anyString())).thenReturn(trlQuery);

    try (MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class)) {
      obDalMock.when(OBDal::getInstance).thenReturn(obDal);
      invokeEnrich(items, "Country", "es_ES");
    }

    assertEquals("España", items.getJSONObject(0).getString("label"));
    assertEquals("Argentina", items.getJSONObject(1).getString("label"));
  }

  // ---------------------------------------------------------------
  // Private helper
  // ---------------------------------------------------------------

  /**
   * A country with no CountryTrl row keeps its original label unchanged.
   */
  @Test
  @SuppressWarnings("unchecked")
  public void testEnrichCountryTranslationsNoMatchingTranslationKeepsOriginalLabel() throws Exception {
    JSONArray items = new JSONArray();
    JSONObject item = new JSONObject();
    item.put("id", "99");
    item.put("label", "Unknown");
    items.put(item);

    OBQuery<BaseOBObject> trlQuery = mock(OBQuery.class);
    when(trlQuery.list()).thenReturn(Collections.emptyList());

    OBDal obDal = mock(OBDal.class);
    when(obDal.createQuery(anyString(), anyString())).thenReturn(trlQuery);

    try (MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class)) {
      obDalMock.when(OBDal::getInstance).thenReturn(obDal);
      invokeEnrich(items, "Country", "es_ES");
    }

    assertEquals("Unknown", items.getJSONObject(0).getString("label"));
  }

  // ---------------------------------------------------------------
  // executeRichQuery — result loop
  // ---------------------------------------------------------------

  /**
   * When the data query returns one BOB, the result loop populates items with id and label
   * and passes them to buildSelectorResponse.
   */
  @Test
  @SuppressWarnings("unchecked")
  public void testExecuteRichQueryWithResultsBuildsItemsCorrectly() throws Exception {
    SelectorMeta meta = new SelectorMeta.Builder("Country", "name").isRich(true).build();

    BaseOBObject bob = mock(BaseOBObject.class);
    when(bob.getId()).thenReturn("47");
    when(bob.getIdentifier()).thenReturn("Spain");

    OBQuery countQuery = mock(OBQuery.class);
    OBQuery dataQuery = mock(OBQuery.class);
    when(countQuery.count()).thenReturn(1);
    when(dataQuery.list()).thenReturn(Collections.singletonList(bob));

    OBDal obDal = mock(OBDal.class);
    when(obDal.createQuery(anyString(), anyString())).thenReturn(countQuery, dataQuery);

    NeoResponse expected = NeoResponse.ok(new JSONObject());
    SelectorQueryBuilder.HqlWithParams emptyClause = SelectorQueryBuilder.HqlWithParams.empty();

    try (MockedStatic<SelectorQueryBuilder> builderMock = mockStatic(
        SelectorQueryBuilder.class); MockedStatic<NeoSelectorExecutionHelper> helperMock = mockStatic(
        NeoSelectorExecutionHelper.class); MockedStatic<SelectorRowMapper> mapperMock = mockStatic(
        SelectorRowMapper.class); MockedStatic<SelectorAuxResolver> auxMock = mockStatic(
        SelectorAuxResolver.class); MockedStatic<OBDal> obDalMock = mockStatic(
        OBDal.class); MockedStatic<SelectorResponseSupport> respMock = mockStatic(SelectorResponseSupport.class)) {

      builderMock.when(
          () -> SelectorQueryBuilder.buildRichQueryWhereClause(any(), any(), any(), any(), any())).thenReturn(
          emptyClause);
      helperMock.when(() -> NeoSelectorExecutionHelper.bindNamedParameters(any(OBQuery.class), any())).thenAnswer(
          inv -> null);
      mapperMock.when(() -> SelectorRowMapper.normalizeEntityId("47")).thenReturn("47");
      auxMock.when(() -> SelectorAuxResolver.appendAuxFields(any(), any(), any())).thenAnswer(inv -> null);
      obDalMock.when(OBDal::getInstance).thenReturn(obDal);
      respMock.when(() -> SelectorResponseSupport.buildGridColumnMetadata(any())).thenReturn(new JSONArray());

      JSONArray[] capturedItems = new JSONArray[1];
      respMock.when(
          () -> SelectorResponseSupport.buildSelectorResponse(any(), any(), anyInt(), anyInt(), anyInt())).thenAnswer(
          inv -> {
            capturedItems[0] = inv.getArgument(0);
            return expected;
          });

      NeoResponse result = SelectorQueryExecutor.execute(meta, "", 20, 0, null, "org-1", null);

      assertNotNull(result);
      assertEquals(expected, result);
      assertNotNull(capturedItems[0]);
      assertEquals(1, capturedItems[0].length());
      assertEquals("47", capturedItems[0].getJSONObject(0).getString("id"));
      assertEquals("Spain", capturedItems[0].getJSONObject(0).getString("label"));
    }
  }

  // ---------------------------------------------------------------
  // executeCustomHqlQuery — full flow
  // ---------------------------------------------------------------

  /**
   * A custom HQL selector with a valid FROM clause executes count and data queries via Hibernate
   * and returns a selector response populated with extracted record ids and display labels.
   */
  @Test
  @SuppressWarnings("unchecked")
  public void testExecuteCustomHqlQueryFullFlowReturnsResponse() throws Exception {
    SelectorMeta meta = new SelectorMeta.Builder("Country", "name").isRich(true).isCustomQuery(true).customHql(
        "SELECT e.id, e.name FROM Country e").build();

    Query countHibQuery = mock(Query.class);
    when(countHibQuery.uniqueResult()).thenReturn(1L);

    Query dataHibQuery = mock(Query.class);
    when(dataHibQuery.list()).thenReturn(Collections.singletonList("47"));

    Session session = mock(Session.class);
    when(session.createQuery(anyString(), eq(Long.class))).thenReturn(countHibQuery);
    when(session.createQuery(anyString())).thenReturn(dataHibQuery);

    OBDal obDal = mock(OBDal.class);
    when(obDal.getSession()).thenReturn(session);

    Entity entityDef = mock(Entity.class);
    ModelProvider modelProvider = mock(ModelProvider.class);
    when(modelProvider.getEntity(anyString())).thenReturn(entityDef);

    NeoResponse expected = NeoResponse.ok(new JSONObject());
    SelectorQueryBuilder.HqlWithParams emptyClause = SelectorQueryBuilder.HqlWithParams.empty();

    try (MockedStatic<SelectorQueryBuilder> builderMock = mockStatic(
        SelectorQueryBuilder.class); MockedStatic<NeoSelectorExecutionHelper> helperMock = mockStatic(
        NeoSelectorExecutionHelper.class); MockedStatic<SelectorRowMapper> mapperMock = mockStatic(
        SelectorRowMapper.class); MockedStatic<OBDal> obDalMock = mockStatic(
        OBDal.class); MockedStatic<ModelProvider> providerMock = mockStatic(
        ModelProvider.class); MockedStatic<SelectorResponseSupport> respMock = mockStatic(
        SelectorResponseSupport.class)) {

      builderMock.when(
          () -> SelectorQueryBuilder.buildCustomHqlFromClause(any(), any(), any(), any(), any(), any())).thenReturn(
          emptyClause);
      helperMock.when(() -> NeoSelectorExecutionHelper.bindNamedParameters(any(Query.class), any())).thenAnswer(
          inv -> null);
      mapperMock.when(() -> SelectorRowMapper.buildSelectColumnIndexMap(any())).thenReturn(new HashMap<>());
      mapperMock.when(() -> SelectorRowMapper.resolveIdColumnIndex(any(), any(), any(), any())).thenReturn(0);
      mapperMock.when(() -> SelectorRowMapper.extractDisplayLabel(any(), any(), any(), any(), any())).thenReturn(
          "España");
      mapperMock.when(() -> SelectorRowMapper.mapGridFieldsToItem(any(), any(), any(), any())).thenAnswer(inv -> null);
      obDalMock.when(OBDal::getInstance).thenReturn(obDal);
      providerMock.when(ModelProvider::getInstance).thenReturn(modelProvider);
      respMock.when(() -> SelectorResponseSupport.buildGridColumnMetadata(any())).thenReturn(new JSONArray());
      respMock.when(() -> SelectorResponseSupport.extractRecordId(any(), any())).thenReturn("47");

      JSONArray[] capturedItems = new JSONArray[1];
      respMock.when(
          () -> SelectorResponseSupport.buildSelectorResponse(any(), any(), anyInt(), anyInt(), anyInt())).thenAnswer(
          inv -> {
            capturedItems[0] = inv.getArgument(0);
            return expected;
          });

      NeoResponse result = SelectorQueryExecutor.execute(meta, "", 20, 0, null, "org-1", null);

      assertNotNull(result);
      assertEquals(expected, result);
      assertNotNull(capturedItems[0]);
      assertEquals(1, capturedItems[0].length());
      assertEquals("47", capturedItems[0].getJSONObject(0).getString("id"));
      assertEquals("España", capturedItems[0].getJSONObject(0).getString("label"));
    }
  }
}
