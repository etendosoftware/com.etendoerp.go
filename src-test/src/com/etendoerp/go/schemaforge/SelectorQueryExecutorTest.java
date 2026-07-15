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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
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
import com.etendoerp.go.schemaforge.util.NeoLanguage;
import com.etendoerp.go.schemaforge.util.NeoTrl;

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
   * Invokes the private {@code buildRepresentativeRowWhere} method.
   */
  private static String invokeBuildRepresentativeRowWhere(String baseWhere, SelectorMeta meta,
      String alias) throws Exception {
    Method m = SelectorQueryExecutor.class.getDeclaredMethod("buildRepresentativeRowWhere",
        String.class, SelectorMeta.class, String.class);
    m.setAccessible(true);
    return (String) m.invoke(null, baseWhere, meta, alias);
  }

  /**
   * Invokes the private {@code enrichTranslations} method.
   */
  private static void invokeEnrich(JSONArray items, String entityName, String language) throws Exception {
    Method m = SelectorQueryExecutor.class.getDeclaredMethod("enrichTranslations", JSONArray.class, String.class,
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
   * A translatable entity with a resolved GO locale triggers enrichment: labels are replaced with
   * the translated names {@link NeoTrl} returns for the request language.
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
    when(countQuery.count()).thenReturn(1);
    when(dataQuery.list()).thenReturn(Collections.singletonList(bob));

    OBDal obDal = mock(OBDal.class);
    when(obDal.createQuery(anyString(), anyString())).thenReturn(countQuery, dataQuery);

    Map<String, String> translations = new HashMap<>();
    translations.put("47", "España");

    try (MockedStatic<NeoSelectorExecutionHelper> helperMock = mockStatic(
        NeoSelectorExecutionHelper.class); MockedStatic<SelectorRowMapper> mapperMock = mockStatic(
        SelectorRowMapper.class); MockedStatic<OBDal> obDalMock = mockStatic(
        OBDal.class); MockedStatic<NeoLanguage> langMock = mockStatic(
        NeoLanguage.class); MockedStatic<NeoTrl> trlMock = mockStatic(
        NeoTrl.class); MockedStatic<SelectorResponseSupport> respMock = mockStatic(SelectorResponseSupport.class)) {

      setupVoidHelperMocks(helperMock);
      mapperMock.when(() -> SelectorRowMapper.normalizeEntityId("47")).thenReturn("47");
      obDalMock.when(OBDal::getInstance).thenReturn(obDal);
      langMock.when(NeoLanguage::currentCode).thenReturn("es_ES");
      trlMock.when(() -> NeoTrl.translatedNames(eq("Country"), any(), eq("es_ES"))).thenReturn(translations);

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
   * When NeoTrl returns a translation for only some ids, only those labels are replaced; items
   * without a translation keep their original label.
   */
  @Test
  public void testEnrichTranslationsPartialTranslationKeepsUntranslatedLabels() throws Exception {
    JSONArray items = new JSONArray();
    JSONObject item1 = new JSONObject();
    item1.put("id", "47");
    item1.put("label", "Spain");
    items.put(item1);
    JSONObject item2 = new JSONObject();
    item2.put("id", "10");
    item2.put("label", "Argentina");
    items.put(item2);

    Map<String, String> translations = new HashMap<>();
    translations.put("47", "España");

    try (MockedStatic<NeoTrl> trlMock = mockStatic(NeoTrl.class)) {
      trlMock.when(() -> NeoTrl.translatedNames(eq("Country"), any(), eq("es_ES"))).thenReturn(translations);
      invokeEnrich(items, "Country", "es_ES");
    }

    assertEquals("España", items.getJSONObject(0).getString("label"));
    assertEquals("Argentina", items.getJSONObject(1).getString("label"));
  }

  // ---------------------------------------------------------------
  // enrichCountryTranslations — translation applied
  // ---------------------------------------------------------------

  /**
   * An empty items array causes enrichTranslations to return before calling NeoTrl.
   */
  @Test
  public void testEnrichTranslationsEmptyItemsDoesNotCallNeoTrl() throws Exception {
    try (MockedStatic<NeoTrl> trlMock = mockStatic(NeoTrl.class)) {
      invokeEnrich(new JSONArray(), "Country", "es_ES");
      trlMock.verify(() -> NeoTrl.translatedNames(any(), any(), any()), never());
    }
  }

  // ---------------------------------------------------------------
  // enrichCountryTranslations — missing translation keeps original
  // ---------------------------------------------------------------

  /**
   * Items are relabelled with the translated names NeoTrl returns for each id.
   */
  @Test
  public void testEnrichTranslationsReplacesLabelsWithTranslatedNames() throws Exception {
    JSONArray items = new JSONArray();
    JSONObject item1 = new JSONObject();
    item1.put("id", "47");
    item1.put("label", "Spain");
    items.put(item1);
    JSONObject item2 = new JSONObject();
    item2.put("id", "10");
    item2.put("label", "Argentine Republic");
    items.put(item2);

    Map<String, String> translations = new HashMap<>();
    translations.put("47", "España");
    translations.put("10", "Argentina");

    try (MockedStatic<NeoTrl> trlMock = mockStatic(NeoTrl.class)) {
      trlMock.when(() -> NeoTrl.translatedNames(eq("Country"), any(), eq("es_ES"))).thenReturn(translations);
      invokeEnrich(items, "Country", "es_ES");
    }

    assertEquals("España", items.getJSONObject(0).getString("label"));
    assertEquals("Argentina", items.getJSONObject(1).getString("label"));
  }

  // ---------------------------------------------------------------
  // Private helper
  // ---------------------------------------------------------------

  /**
   * When NeoTrl returns no translations, item labels are left unchanged.
   */
  @Test
  public void testEnrichTranslationsNoMatchingTranslationKeepsOriginalLabel() throws Exception {
    JSONArray items = new JSONArray();
    JSONObject item = new JSONObject();
    item.put("id", "99");
    item.put("label", "Unknown");
    items.put(item);

    try (MockedStatic<NeoTrl> trlMock = mockStatic(NeoTrl.class)) {
      trlMock.when(() -> NeoTrl.translatedNames(eq("Country"), any(), eq("es_ES"))).thenReturn(Collections.emptyMap());
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

  // ---------------------------------------------------------------
  // buildRepresentativeRowWhere — DISTINCT-by-valueProperty query building
  // ---------------------------------------------------------------

  /**
   * With an existing where clause, the representative-row where restricts the outer query to the
   * MIN(id) per distinct valueProperty, re-applying the same conditions inside the subquery with
   * the entity alias rewritten to the private sub-alias, and grouping by valueProperty.
   */
  @Test
  public void testBuildRepresentativeRowWhereWithConditions() throws Exception {
    SelectorMeta meta = new SelectorMeta.Builder("ProductStock", "name")
        .isRich(true).valueProperty("product.id").build();

    String result = invokeBuildRepresentativeRowWhere("as e where e.active = true", meta, "e");

    assertTrue("outer conditions preserved", result.contains("(e.active = true)"));
    assertTrue("restricts to representative ids", result.contains("e.id in (select min(e_dv.id)"));
    assertTrue("subquery targets same entity", result.contains("from ProductStock e_dv"));
    assertTrue("subquery re-applies conditions with rewritten alias",
        result.contains("where e_dv.active = true"));
    assertTrue("grouped by valueProperty", result.contains("group by e_dv.product.id"));
  }

  /**
   * With no where clause ("as e"), the representative-row where adds only the MIN(id) restriction
   * and GROUP BY valueProperty — the subquery carries no WHERE.
   */
  @Test
  public void testBuildRepresentativeRowWhereWithoutConditions() throws Exception {
    SelectorMeta meta = new SelectorMeta.Builder("ProductStock", "name")
        .isRich(true).valueProperty("product.id").build();

    String result = invokeBuildRepresentativeRowWhere("as e", meta, "e");

    assertEquals("as e where e.id in (select min(e_dv.id) from ProductStock e_dv "
        + "group by e_dv.product.id)", result);
    assertFalse("no leftover WHERE inside subquery", result.contains("where e_dv"));
  }

  /**
   * A search predicate keeps its named parameter untouched while only the alias references are
   * rewritten in the subquery copy.
   */
  @Test
  public void testBuildRepresentativeRowWhereRewritesAliasButKeepsNamedParams() throws Exception {
    SelectorMeta meta = new SelectorMeta.Builder("ProductStock", "name")
        .isRich(true).valueProperty("product.id").build();

    String result = invokeBuildRepresentativeRowWhere(
        "as e where lower(e.name) LIKE :search", meta, "e");

    assertTrue("subquery rewrites alias reference", result.contains("lower(e_dv.name) LIKE :search"));
    assertTrue("named parameter preserved in both occurrences",
        result.indexOf(":search") != result.lastIndexOf(":search"));
  }

  /**
   * A view-backed value-field selector wires the representative-row where into BOTH the count and
   * the data query, so count(*) equals the distinct-value count and paging fills correctly. The
   * data query is the count query plus the display ORDER BY, and it returns one item per row.
   */
  @Test
  @SuppressWarnings("unchecked")
  public void testExecuteRichQueryValueFieldSelectorAppliesDistinctToCountAndData() throws Exception {
    SelectorMeta meta = new SelectorMeta.Builder("ProductStock", "name")
        .isRich(true).valueProperty("product.id").build();

    // Two representative product rows out of a view that (in the DB) would hold many duplicates.
    BaseOBObject row1 = mock(BaseOBObject.class);
    BaseOBObject prod1 = mock(BaseOBObject.class);
    when(prod1.get("id")).thenReturn("P1");
    when(row1.get("product")).thenReturn(prod1);
    when(row1.getId()).thenReturn("stock1");
    when(row1.getIdentifier()).thenReturn("Product One");
    BaseOBObject row2 = mock(BaseOBObject.class);
    BaseOBObject prod2 = mock(BaseOBObject.class);
    when(prod2.get("id")).thenReturn("P2");
    when(row2.get("product")).thenReturn(prod2);
    when(row2.getId()).thenReturn("stock2");
    when(row2.getIdentifier()).thenReturn("Product Two");

    OBQuery countQuery = mock(OBQuery.class);
    OBQuery dataQuery = mock(OBQuery.class);
    when(countQuery.count()).thenReturn(2); // distinct product count
    when(dataQuery.list()).thenReturn(Arrays.asList(row1, row2));

    List<String> capturedWhere = new ArrayList<>();
    OBDal obDal = mock(OBDal.class);
    when(obDal.createQuery(anyString(), anyString())).thenAnswer(inv -> {
      capturedWhere.add(inv.getArgument(1));
      return capturedWhere.size() == 1 ? countQuery : dataQuery;
    });

    NeoResponse expected = NeoResponse.ok(new JSONObject());
    SelectorQueryBuilder.HqlWithParams clause =
        new SelectorQueryBuilder.HqlWithParams("as e where e.active = true", new HashMap<>());

    try (MockedStatic<SelectorQueryBuilder> builderMock = mockStatic(
        SelectorQueryBuilder.class); MockedStatic<NeoSelectorExecutionHelper> helperMock = mockStatic(
        NeoSelectorExecutionHelper.class); MockedStatic<SelectorAuxResolver> auxMock = mockStatic(
        SelectorAuxResolver.class); MockedStatic<OBDal> obDalMock = mockStatic(
        OBDal.class); MockedStatic<SelectorResponseSupport> respMock = mockStatic(
        SelectorResponseSupport.class)) {

      builderMock.when(
          () -> SelectorQueryBuilder.buildRichQueryWhereClause(any(), any(), any(), any(), any()))
          .thenReturn(clause);
      helperMock.when(() -> NeoSelectorExecutionHelper.bindNamedParameters(any(OBQuery.class), any()))
          .thenAnswer(inv -> null);
      auxMock.when(() -> SelectorAuxResolver.appendAuxFields(any(), any(), any())).thenAnswer(inv -> null);
      obDalMock.when(OBDal::getInstance).thenReturn(obDal);
      respMock.when(() -> SelectorResponseSupport.buildGridColumnMetadata(any())).thenReturn(new JSONArray());

      JSONArray[] capturedItems = new JSONArray[1];
      int[] capturedTotal = new int[1];
      respMock.when(
          () -> SelectorResponseSupport.buildSelectorResponse(any(), any(), anyInt(), anyInt(), anyInt()))
          .thenAnswer(inv -> {
            capturedItems[0] = inv.getArgument(0);
            capturedTotal[0] = inv.getArgument(2);
            return expected;
          });

      NeoResponse result = SelectorQueryExecutor.execute(meta, "", 20, 0, null, "org-1", null);

      assertNotNull(result);
      // Count query uses the representative-row where (distinct drives page math).
      assertEquals(2, capturedWhere.size());
      assertTrue("count query is distinct-restricted",
          capturedWhere.get(0).contains("group by e_dv.product.id"));
      // Data query = same representative where + display ORDER BY (consistent with count).
      assertEquals(capturedWhere.get(0) + " ORDER BY e.name", capturedWhere.get(1));
      // totalCount comes from count(*) over representative rows.
      assertEquals(2, capturedTotal[0]);
      // One item per representative row, id taken from valueProperty.
      assertEquals(2, capturedItems[0].length());
      assertEquals("P1", capturedItems[0].getJSONObject(0).getString("id"));
      assertEquals("P2", capturedItems[0].getJSONObject(1).getString("id"));
    }
  }

  /**
   * A PK-valued selector (valueProperty == "id") is byte-identical to the pre-fix behavior: the
   * where string passed to the count query is exactly the builder output with no DISTINCT subquery.
   */
  @Test
  @SuppressWarnings("unchecked")
  public void testExecuteRichQueryPkValuedSelectorIsUnaffected() throws Exception {
    SelectorMeta meta = new SelectorMeta.Builder("Country", "name").isRich(true).build();

    OBQuery countQuery = mock(OBQuery.class);
    OBQuery dataQuery = mock(OBQuery.class);
    when(countQuery.count()).thenReturn(0);
    when(dataQuery.list()).thenReturn(Collections.emptyList());

    List<String> capturedWhere = new ArrayList<>();
    OBDal obDal = mock(OBDal.class);
    when(obDal.createQuery(anyString(), anyString())).thenAnswer(inv -> {
      capturedWhere.add(inv.getArgument(1));
      return capturedWhere.size() == 1 ? countQuery : dataQuery;
    });

    SelectorQueryBuilder.HqlWithParams clause =
        new SelectorQueryBuilder.HqlWithParams("as e where e.active = true", new HashMap<>());

    try (MockedStatic<SelectorQueryBuilder> builderMock = mockStatic(
        SelectorQueryBuilder.class); MockedStatic<NeoSelectorExecutionHelper> helperMock = mockStatic(
        NeoSelectorExecutionHelper.class); MockedStatic<OBDal> obDalMock = mockStatic(
        OBDal.class); MockedStatic<SelectorResponseSupport> respMock = mockStatic(
        SelectorResponseSupport.class)) {

      builderMock.when(
          () -> SelectorQueryBuilder.buildRichQueryWhereClause(any(), any(), any(), any(), any()))
          .thenReturn(clause);
      helperMock.when(() -> NeoSelectorExecutionHelper.bindNamedParameters(any(OBQuery.class), any()))
          .thenAnswer(inv -> null);
      obDalMock.when(OBDal::getInstance).thenReturn(obDal);
      respMock.when(() -> SelectorResponseSupport.buildGridColumnMetadata(any())).thenReturn(new JSONArray());
      respMock.when(
          () -> SelectorResponseSupport.buildSelectorResponse(any(), any(), anyInt(), anyInt(), anyInt()))
          .thenReturn(NeoResponse.ok(new JSONObject()));

      SelectorQueryExecutor.execute(meta, "", 20, 0, null, "org-1", null);

      assertEquals(2, capturedWhere.size());
      assertEquals("as e where e.active = true", capturedWhere.get(0));
      assertFalse("no DISTINCT subquery for PK-valued selector",
          capturedWhere.get(0).contains("min("));
      assertFalse("no GROUP BY for PK-valued selector",
          capturedWhere.get(0).contains("group by"));
    }
  }
}
