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
import static org.mockito.AdditionalMatchers.aryEq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
import org.openbravo.base.model.Property;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.dal.core.DalUtil;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.dal.service.OBQuery;

import com.etendoerp.go.schemaforge.selector.meta.AuxFieldMeta;
import com.etendoerp.go.schemaforge.selector.meta.SearchableFragment;
import com.etendoerp.go.schemaforge.selector.meta.SelectorMeta;
import com.etendoerp.go.schemaforge.util.NeoLanguage;
import com.etendoerp.go.schemaforge.util.NeoTrl;

/**
 * Unit tests for {@link SelectorQueryExecutor} covering routing, property resolution,
 * language extraction, and country-translation enrichment.
 *
 * @covers com.etendoerp.go.schemaforge.SelectorQueryExecutor
 * @covers com.etendoerp.go.schemaforge.ValuePagedRichQuery
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
   * Invokes {@code ValuePagedRichQuery.andCondition} (package-visible).
   */
  private static String invokeAndCondition(String baseWhere, String condition) throws Exception {
    Method m = ValuePagedRichQuery.class.getDeclaredMethod("andCondition", String.class,
        String.class);
    m.setAccessible(true);
    return (String) m.invoke(null, baseWhere, condition);
  }

  /**
   * Invokes the private {@code shouldDistinctByValue} method (the de-dup guard decision).
   */
  private static boolean invokeShouldDistinctByValue(SelectorMeta meta) throws Exception {
    Method m = SelectorQueryExecutor.class.getDeclaredMethod("shouldDistinctByValue",
        SelectorMeta.class);
    m.setAccessible(true);
    return (boolean) m.invoke(null, meta);
  }

  /**
   * Invokes the private {@code isStockBreakdownSelector} method.
   */
  private static boolean invokeIsStockBreakdownSelector(SelectorMeta meta) throws Exception {
    Method m = SelectorQueryExecutor.class.getDeclaredMethod("isStockBreakdownSelector",
        SelectorMeta.class);
    m.setAccessible(true);
    return (boolean) m.invoke(null, meta);
  }

  /**
   * Builds an out-field aux metadata with only a response suffix (the shape resolved for
   * {@code M_Product_Stock_V} out-fields such as {@code _QTY} / {@code _LOC}).
   */
  private static AuxFieldMeta auxWithSuffix(String suffix) {
    return new AuxFieldMeta(suffix, null, suffix, null);
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

  /**
   * ETP-5703 repro: a custom HQL selector joining a child table (the product selector, one row per
   * price-list version) returned the same product twice and counted rows, not records. Rows of one
   * record must collapse into one item, and the count must be COUNT(DISTINCT &lt;id expression&gt;).
   */
  @Test
  @SuppressWarnings("unchecked")
  public void testExecuteCustomHqlQueryCollapsesRowsOfOneRecordAndCountsDistinct()
      throws Exception {
    SelectorMeta meta = new SelectorMeta.Builder("Product", "name").isRich(true)
        .isCustomQuery(true).customHql("SELECT e.id as id, e.name as name FROM Product e").build();

    Query countHibQuery = mock(Query.class);
    when(countHibQuery.uniqueResult()).thenReturn(1L);
    Query dataHibQuery = mock(Query.class);
    List<Object> twoRowsOfOneProduct = Arrays.asList("47", "47");
    when(dataHibQuery.list()).thenReturn(twoRowsOfOneProduct);

    String[] countHql = new String[1];
    Session session = mock(Session.class);
    when(session.createQuery(anyString(), eq(Long.class))).thenAnswer(inv -> {
      countHql[0] = inv.getArgument(0);
      return countHibQuery;
    });
    when(session.createQuery(anyString())).thenReturn(dataHibQuery);
    OBDal obDal = mock(OBDal.class);
    when(obDal.getSession()).thenReturn(session);
    Entity entityDef = mock(Entity.class);
    // Record paging needs a plain display property: "name" is a primitive of the entity.
    org.openbravo.base.model.Property nameProperty = mock(org.openbravo.base.model.Property.class);
    when(nameProperty.isPrimitive()).thenReturn(true);
    when(entityDef.getProperty("name", false)).thenReturn(nameProperty);
    ModelProvider modelProvider = mock(ModelProvider.class);
    when(modelProvider.getEntity(anyString())).thenReturn(entityDef);
    SelectorQueryBuilder.HqlWithParams emptyClause = SelectorQueryBuilder.HqlWithParams.empty();
    NeoResponse expected = NeoResponse.ok(new JSONObject());

    try (MockedStatic<SelectorQueryBuilder> builderMock = mockStatic(SelectorQueryBuilder.class);
        MockedStatic<NeoSelectorExecutionHelper> helperMock =
            mockStatic(NeoSelectorExecutionHelper.class);
        MockedStatic<SelectorRowMapper> mapperMock = mockStatic(SelectorRowMapper.class);
        MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class);
        MockedStatic<ModelProvider> providerMock = mockStatic(ModelProvider.class);
        MockedStatic<SelectorResponseSupport> respMock = mockStatic(SelectorResponseSupport.class)) {
      builderMock.when(() -> SelectorQueryBuilder.buildCustomHqlFromClause(any(), any(), any(),
          any(), any(), any())).thenReturn(emptyClause);
      helperMock.when(() -> NeoSelectorExecutionHelper.bindNamedParameters(any(Query.class), any()))
          .thenAnswer(inv -> null);
      mapperMock.when(() -> SelectorRowMapper.buildSelectColumnIndexMap(any()))
          .thenReturn(new HashMap<>());
      mapperMock.when(() -> SelectorRowMapper.resolveIdColumnIndex(any(), any(), any(), any()))
          .thenReturn(0);
      mapperMock.when(() -> SelectorRowMapper.extractDisplayLabel(any(), any(), any(), any(), any()))
          .thenReturn("Agua");
      mapperMock.when(() -> SelectorRowMapper.mapGridFieldsToItem(any(), any(), any(), any()))
          .thenAnswer(inv -> null);
      obDalMock.when(OBDal::getInstance).thenReturn(obDal);
      providerMock.when(ModelProvider::getInstance).thenReturn(modelProvider);
      respMock.when(() -> SelectorResponseSupport.buildGridColumnMetadata(any()))
          .thenReturn(new JSONArray());
      respMock.when(() -> SelectorResponseSupport.extractRecordId(any(), any())).thenReturn("47");
      JSONArray[] capturedItems = new JSONArray[1];
      respMock.when(() -> SelectorResponseSupport.buildSelectorResponse(any(), any(), anyInt(),
          anyInt(), anyInt())).thenAnswer(inv -> {
            capturedItems[0] = inv.getArgument(0);
            return expected;
          });

      SelectorQueryExecutor.execute(meta, "", 20, 0, null, "org-1", null);

      assertEquals(1, capturedItems[0].length());
      assertEquals("47", capturedItems[0].getJSONObject(0).getString("id"));
      assertTrue(countHql[0], countHql[0].startsWith("SELECT COUNT(DISTINCT e.id)"));
    }
  }

  /**
   * ETP-5703 review B1: with more rows than {@code limit} and {@code offset > 0}, a custom HQL
   * selector whose rows repeat records must page by RECORD — the page of distinct ids carries the
   * offset and limit, the data rows of those records come back unlimited and collapse to one item
   * each in page order, and the count is records, so hasMore and the count use the same unit.
   */
  @Test
  @SuppressWarnings("unchecked")
  public void testExecuteCustomHqlQueryPagesByRecordNotByRow() throws Exception {
    SelectorMeta meta = new SelectorMeta.Builder("Product", "name").isRich(true)
        .isCustomQuery(true).customHql("SELECT e.id as id, e.name as name FROM Product e").build();

    Query countHibQuery = mock(Query.class);
    when(countHibQuery.uniqueResult()).thenReturn(5L);
    Query pageQuery = mock(Query.class);
    List<Object> pageOfIds = Arrays.asList("P3", "P4");
    when(pageQuery.list()).thenReturn(pageOfIds);
    Query dataQuery = mock(Query.class);
    // Rows of the two page records, each repeated (one per price-list version), interleaved.
    List<Object> rowsOfPage = new ArrayList<>();
    for (String id : new String[] { "P3", "P4", "P3", "P4" }) {
      rowsOfPage.add(new Object[] { id });
    }
    when(dataQuery.list()).thenReturn(rowsOfPage);

    String[] hqls = new String[2];
    Session session = mock(Session.class);
    when(session.createQuery(anyString(), eq(Long.class))).thenReturn(countHibQuery);
    when(session.createQuery(anyString())).thenAnswer(inv -> {
      String hql = inv.getArgument(0);
      if (hql.contains(" GROUP BY ")) {
        hqls[0] = hql;
        return pageQuery;
      }
      hqls[1] = hql;
      return dataQuery;
    });
    OBDal obDal = mock(OBDal.class);
    when(obDal.getSession()).thenReturn(session);
    Entity entityDef = mock(Entity.class);
    // Record paging needs a plain display property: "name" is a primitive of the entity.
    org.openbravo.base.model.Property nameProperty = mock(org.openbravo.base.model.Property.class);
    when(nameProperty.isPrimitive()).thenReturn(true);
    when(entityDef.getProperty("name", false)).thenReturn(nameProperty);
    ModelProvider modelProvider = mock(ModelProvider.class);
    when(modelProvider.getEntity(anyString())).thenReturn(entityDef);
    SelectorQueryBuilder.HqlWithParams emptyClause = SelectorQueryBuilder.HqlWithParams.empty();
    NeoResponse expected = NeoResponse.ok(new JSONObject());

    try (MockedStatic<SelectorQueryBuilder> builderMock = mockStatic(SelectorQueryBuilder.class);
        MockedStatic<NeoSelectorExecutionHelper> helperMock =
            mockStatic(NeoSelectorExecutionHelper.class);
        MockedStatic<SelectorRowMapper> mapperMock = mockStatic(SelectorRowMapper.class);
        MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class);
        MockedStatic<ModelProvider> providerMock = mockStatic(ModelProvider.class);
        MockedStatic<SelectorResponseSupport> respMock = mockStatic(SelectorResponseSupport.class)) {
      builderMock.when(() -> SelectorQueryBuilder.buildCustomHqlFromClause(any(), any(), any(),
          any(), any(), any())).thenReturn(emptyClause);
      helperMock.when(() -> NeoSelectorExecutionHelper.bindNamedParameters(any(Query.class), any()))
          .thenAnswer(inv -> null);
      mapperMock.when(() -> SelectorRowMapper.buildSelectColumnIndexMap(any()))
          .thenReturn(new HashMap<>());
      mapperMock.when(() -> SelectorRowMapper.resolveIdColumnIndex(any(), any(), any(), any()))
          .thenReturn(0);
      mapperMock.when(() -> SelectorRowMapper.extractDisplayLabel(any(), any(), any(), any(), any()))
          .thenReturn("Agua");
      mapperMock.when(() -> SelectorRowMapper.mapGridFieldsToItem(any(), any(), any(), any()))
          .thenAnswer(inv -> null);
      obDalMock.when(OBDal::getInstance).thenReturn(obDal);
      providerMock.when(ModelProvider::getInstance).thenReturn(modelProvider);
      respMock.when(() -> SelectorResponseSupport.buildGridColumnMetadata(any()))
          .thenReturn(new JSONArray());
      respMock.when(() -> SelectorResponseSupport.extractRecordId(any(), any()))
          .thenAnswer(inv -> ((Object[]) inv.getArgument(0))[0]);
      JSONArray[] capturedItems = new JSONArray[1];
      int[] capturedCount = new int[1];
      respMock.when(() -> SelectorResponseSupport.buildSelectorResponse(any(), any(), anyInt(),
          anyInt(), anyInt())).thenAnswer(inv -> {
            capturedItems[0] = inv.getArgument(0);
            capturedCount[0] = inv.getArgument(2);
            return expected;
          });

      SelectorQueryExecutor.execute(meta, "", 2, 2, null, "org-1", null);

      org.mockito.Mockito.verify(pageQuery).setFirstResult(2);
      org.mockito.Mockito.verify(pageQuery).setMaxResults(2);
      org.mockito.Mockito.verify(dataQuery, never()).setMaxResults(anyInt());
      assertTrue(hqls[1], hqls[1].contains("e.id IN (:"));
      assertEquals(5, capturedCount[0]);
      assertEquals(2, capturedItems[0].length());
      assertEquals("P3", capturedItems[0].getJSONObject(0).getString("id"));
      assertEquals("P4", capturedItems[0].getJSONObject(1).getString("id"));
    }
  }

  /**
   * ETP-5703 review: record paging is decided up front and structurally. An id column that is not a
   * {@code .id} path, or a display property that is an association, keeps the row-paged flow it
   * always had — COUNT(alias), offset and limit on the data query, no GROUP BY — and nothing is
   * caught or retried.
   */
  @Test
  public void testExecuteCustomHqlQueryNonIdPathKeepsRowPaging() throws Exception {
    assertRowPaging("SELECT e.searchKey as id, e.name as name FROM Product e", true);
  }

  @Test
  public void testExecuteCustomHqlQueryAssociationDisplayKeepsRowPaging() throws Exception {
    assertRowPaging("SELECT e.id as id, e.name as name FROM Product e", false);
  }

  @SuppressWarnings("unchecked")
  private void assertRowPaging(String customHql, boolean displayPrimitive) throws Exception {
    SelectorMeta meta = new SelectorMeta.Builder("Product", "name").isRich(true)
        .isCustomQuery(true).customHql(customHql).build();

    Query countHibQuery = mock(Query.class);
    when(countHibQuery.uniqueResult()).thenReturn(3L);
    Query dataQuery = mock(Query.class);
    List<Object> rows = Arrays.asList("A", "B");
    when(dataQuery.list()).thenReturn(rows);
    String[] countHql = new String[1];
    List<String> dataHqls = new ArrayList<>();
    Session session = mock(Session.class);
    when(session.createQuery(anyString(), eq(Long.class))).thenAnswer(inv -> {
      countHql[0] = inv.getArgument(0);
      return countHibQuery;
    });
    when(session.createQuery(anyString())).thenAnswer(inv -> {
      dataHqls.add(inv.getArgument(0));
      return dataQuery;
    });
    OBDal obDal = mock(OBDal.class);
    when(obDal.getSession()).thenReturn(session);
    Entity entityDef = mock(Entity.class);
    org.openbravo.base.model.Property nameProperty = mock(org.openbravo.base.model.Property.class);
    when(nameProperty.isPrimitive()).thenReturn(displayPrimitive);
    when(entityDef.getProperty("name", false)).thenReturn(nameProperty);
    ModelProvider modelProvider = mock(ModelProvider.class);
    when(modelProvider.getEntity(anyString())).thenReturn(entityDef);
    SelectorQueryBuilder.HqlWithParams emptyClause = SelectorQueryBuilder.HqlWithParams.empty();
    NeoResponse expected = NeoResponse.ok(new JSONObject());

    try (MockedStatic<SelectorQueryBuilder> builderMock = mockStatic(SelectorQueryBuilder.class);
        MockedStatic<NeoSelectorExecutionHelper> helperMock =
            mockStatic(NeoSelectorExecutionHelper.class);
        MockedStatic<SelectorRowMapper> mapperMock = mockStatic(SelectorRowMapper.class);
        MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class);
        MockedStatic<ModelProvider> providerMock = mockStatic(ModelProvider.class);
        MockedStatic<SelectorResponseSupport> respMock = mockStatic(SelectorResponseSupport.class)) {
      builderMock.when(() -> SelectorQueryBuilder.buildCustomHqlFromClause(any(), any(), any(),
          any(), any(), any())).thenReturn(emptyClause);
      helperMock.when(() -> NeoSelectorExecutionHelper.bindNamedParameters(any(Query.class), any()))
          .thenAnswer(inv -> null);
      mapperMock.when(() -> SelectorRowMapper.buildSelectColumnIndexMap(any()))
          .thenReturn(new HashMap<>());
      mapperMock.when(() -> SelectorRowMapper.resolveIdColumnIndex(any(), any(), any(), any()))
          .thenReturn(0);
      mapperMock.when(() -> SelectorRowMapper.extractDisplayLabel(any(), any(), any(), any(), any()))
          .thenReturn("x");
      mapperMock.when(() -> SelectorRowMapper.mapGridFieldsToItem(any(), any(), any(), any()))
          .thenAnswer(inv -> null);
      obDalMock.when(OBDal::getInstance).thenReturn(obDal);
      providerMock.when(ModelProvider::getInstance).thenReturn(modelProvider);
      respMock.when(() -> SelectorResponseSupport.buildGridColumnMetadata(any()))
          .thenReturn(new JSONArray());
      respMock.when(() -> SelectorResponseSupport.extractRecordId(any(), any()))
          .thenAnswer(inv -> ((Object[]) inv.getArgument(0))[0]);
      JSONArray[] capturedItems = new JSONArray[1];
      respMock.when(() -> SelectorResponseSupport.buildSelectorResponse(any(), any(), anyInt(),
          anyInt(), anyInt())).thenAnswer(inv -> {
            capturedItems[0] = inv.getArgument(0);
            return expected;
          });

      SelectorQueryExecutor.execute(meta, "", 2, 4, null, "org-1", null);

      assertTrue(countHql[0], countHql[0].startsWith("SELECT COUNT(e)"));
      assertEquals(1, dataHqls.size());
      assertFalse(dataHqls.get(0), dataHqls.get(0).contains("GROUP BY"));
      // ETP-5903: the id breaks display ties so no row sits on two pages or on none.
      assertTrue(dataHqls.get(0), dataHqls.get(0).endsWith(" ORDER BY e.name, e.id"));
      org.mockito.Mockito.verify(dataQuery).setFirstResult(4);
      org.mockito.Mockito.verify(dataQuery).setMaxResults(2);
      org.mockito.Mockito.verify(obDal, never()).getConnection();
      assertEquals(2, capturedItems[0].length());
    }
  }

  /** ETP-5903: the row-paged ORDER BY ends with the alias id, so equal display values page stably. */
  @Test
  public void testRowPageOrderAddsIdTiebreaker() {
    assertEquals("p.name, p.id",
        SelectorQueryExecutor.rowPageOrder("p", "SELECT p.id, p.name", " FROM Product p", "p.name"));
  }

  /**
   * ETP-5903: PostgreSQL rejects an ORDER BY item outside a DISTINCT select list or a GROUP BY, and
   * the failure would mark the transaction rollback-only — those queries keep the display order only.
   */
  @Test
  public void testRowPageOrderSkipsTiebreakerOnDistinctOrGroupBy() {
    assertEquals("p.name", SelectorQueryExecutor.rowPageOrder("p",
        "select distinct p.name", " FROM Product p", "p.name"));
    assertEquals("p.name", SelectorQueryExecutor.rowPageOrder("p",
        "SELECT p.name, count(l)", " FROM Product p join p.lines l group  by p.name", "p.name"));
  }

  // ---------------------------------------------------------------
  // Value-paged rich flow (ETP-5707) — view-backed selectors, Value Field is not the PK
  // ---------------------------------------------------------------

  /** Everything one run of the value-paged flow handed to the DAL, and what it answered. */
  private static final class PagedRun {
    final List<String> where = new ArrayList<>();
    final List<String> selectClauses = new ArrayList<>();
    final List<String> hql = new ArrayList<>();
    Session session;
    Query<Long> countQuery;
    Query<?> pageQuery;
    Query<?> rowsQuery;
    JSONArray items;
    int total;
    int limit;
    int offset;
  }

  /** A view row: its own id, the product it points at (the Value Field) and its label. */
  private static BaseOBObject valueRow(String rowId, String productId, String label) {
    BaseOBObject row = mock(BaseOBObject.class);
    BaseOBObject product = mock(BaseOBObject.class);
    when(product.get("id")).thenReturn(productId);
    when(row.get("product")).thenReturn(product);
    when(row.getId()).thenReturn(rowId);
    when(row.getIdentifier()).thenReturn(label);
    return row;
  }

  /** The product-by-price selector shape with a plain display property. */
  private static SelectorMeta valueFieldSelector() {
    return new SelectorMeta.Builder("ProductByPriceAndWarehouse", "name")
        .isRich(true).valueProperty("product.id").build();
  }

  /**
   * Runs the value-paged flow through the REAL {@link SelectorQueryBuilder} and
   * {@link NeoSelectorExecutionHelper} (so the where text and the parameter binding are the
   * production ones); only the DAL, the model and the response are mocked. The OBQuery mock
   * answers the HQL {@code select <clause|e> from <entity> <where>} and a readable-organization
   * filter held as a {@code String[]}, as the real one does.
   *
   * @param count       what {@code count(distinct value)} answers
   * @param pageValues  what the page-of-values query answers
   * @param rows        what the rows query answers
   */
  private static PagedRun runPaged(SelectorMeta meta, String search, int limit, int offset,
      String validationFilter, Long count, List<Object> pageValues, List<BaseOBObject> rows)
      throws Exception {
    return runPaged(meta, search, limit, offset, validationFilter, count, pageValues, rows, null);
  }

  /**
   * Same as above; a non-null {@code resolvedDisplay} makes the model know the entity and resolve
   * the display property path to it, which is how the flow tells a primitive display from an
   * association. Null leaves the entity unknown, so the display is not primitive.
   */
  @SuppressWarnings({ "unchecked", "rawtypes" })
  private static PagedRun runPaged(SelectorMeta meta, String search, int limit, int offset,
      String validationFilter, Long count, List<Object> pageValues, List<BaseOBObject> rows,
      Property resolvedDisplay) throws Exception {
    PagedRun run = new PagedRun();
    run.limit = limit;
    run.offset = offset;
    run.session = mock(Session.class);
    run.countQuery = mock(Query.class);
    Query pageQuery = mock(Query.class);
    Query rowsQuery = mock(Query.class);
    run.pageQuery = pageQuery;
    run.rowsQuery = rowsQuery;
    when(run.countQuery.uniqueResult()).thenReturn(count);
    when(pageQuery.list()).thenReturn(pageValues);
    when(rowsQuery.list()).thenReturn(rows);

    when(run.session.createQuery(anyString(), eq(Long.class))).thenAnswer(inv -> {
      run.hql.add(inv.getArgument(0));
      return run.countQuery;
    });
    int[] sessionCalls = new int[1];
    when(run.session.createQuery(anyString())).thenAnswer(inv -> {
      run.hql.add(inv.getArgument(0));
      return sessionCalls[0]++ == 0 ? pageQuery : rowsQuery;
    });

    OBDal obDal = mock(OBDal.class);
    when(obDal.getSession()).thenReturn(run.session);
    when(obDal.createQuery(anyString(), anyString())).thenAnswer(inv -> {
      String where = inv.getArgument(1);
      run.where.add(where);
      String[] select = new String[1];
      OBQuery obQuery = mock(OBQuery.class);
      when(obQuery.setSelectClause(anyString())).thenAnswer(sel -> {
        select[0] = sel.getArgument(0);
        run.selectClauses.add(select[0]);
        return obQuery;
      });
      Query hibernate = mock(Query.class);
      when(hibernate.getQueryString()).thenAnswer(q -> "select " + (select[0] != null ? select[0]
          : "e") + " from " + inv.getArgument(0) + " " + where);
      when(obQuery.createQuery(Object.class)).thenReturn(hibernate);
      // like the real OBQuery: what was bound is what getNamedParameters() answers, plus its own
      // readable-organization filter as a String[]
      Map<String, Object> named = new HashMap<>();
      named.put("sfOrgs", new String[] { "0", "ORG1" });
      org.mockito.Mockito.doAnswer(bind -> {
        named.put(bind.getArgument(0), bind.getArgument(1));
        return null;
      }).when(obQuery).setNamedParameter(anyString(), any());
      when(obQuery.getNamedParameters()).thenReturn(named);
      return obQuery;
    });

    ModelProvider modelProvider = mock(ModelProvider.class);
    when(modelProvider.getEntity(anyString()))
        .thenReturn(resolvedDisplay != null ? mock(Entity.class) : null);

    try (MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class);
        MockedStatic<ModelProvider> mpMock = mockStatic(ModelProvider.class);
        MockedStatic<DalUtil> dalUtilMock = mockStatic(DalUtil.class);
        MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
        MockedStatic<SelectorAuxResolver> auxMock = mockStatic(SelectorAuxResolver.class);
        MockedStatic<SelectorResponseSupport> respMock = mockStatic(
            SelectorResponseSupport.class)) {
      obDalMock.when(OBDal::getInstance).thenReturn(obDal);
      mpMock.when(ModelProvider::getInstance).thenReturn(modelProvider);
      dalUtilMock.when(() -> DalUtil.getPropertyFromPath(any(Entity.class), anyString()))
          .thenReturn(resolvedDisplay);
      ctxMock.when(OBContext::getOBContext).thenReturn(null);
      auxMock.when(() -> SelectorAuxResolver.appendAuxFields(any(), any(), any()))
          .thenAnswer(inv -> null);
      respMock.when(() -> SelectorResponseSupport.buildGridColumnMetadata(any()))
          .thenReturn(new JSONArray());
      respMock.when(
          () -> SelectorResponseSupport.buildSelectorResponse(any(), any(), anyInt(), anyInt(), anyInt()))
          .thenAnswer(inv -> {
            run.items = inv.getArgument(0);
            run.total = inv.getArgument(2);
            return NeoResponse.ok(new JSONObject());
          });

      assertNotNull(SelectorQueryExecutor.execute(meta, search, limit, offset, validationFilter,
          null, null));
    }
    return run;
  }

  private static List<String> ids(JSONArray items) throws Exception {
    List<String> ids = new ArrayList<>();
    for (int i = 0; i < items.length(); i++) {
      ids.add(items.getJSONObject(i).getString("id"));
    }
    return ids;
  }

  // --- andCondition ---

  /** Without a where clause the condition opens one. */
  @Test
  public void testAndConditionWithoutWhereAddsWhere() throws Exception {
    assertEquals("as e where e.product.id is not null",
        invokeAndCondition("as e", "e.product.id is not null"));
  }

  /** With a where clause the existing conditions are kept, parenthesised, and the new one ANDed. */
  @Test
  public void testAndConditionWithWhereParenthesisesExistingConditions() throws Exception {
    assertEquals("as e where (e.active = true) AND e.product.id is not null",
        invokeAndCondition("as e where e.active = true", "e.product.id is not null"));
  }

  /** An {@code or} inside the existing conditions cannot swallow the new condition. */
  @Test
  public void testAndConditionParenthesisesAnOrInsideTheConditions() throws Exception {
    assertEquals("as e where (e.a = 1 or e.b = 2) AND e.v is not null",
        invokeAndCondition("as e where e.a = 1 or e.b = 2", "e.v is not null"));
  }

  /** The where keyword is found whatever its case. */
  @Test
  public void testAndConditionFindsWhereIgnoringCase() throws Exception {
    assertEquals("as e WHERE (e.a = 1) AND e.v is not null",
        invokeAndCondition("as e WHERE e.a = 1", "e.v is not null"));
  }

  // --- displayIsPrimitivePath / the page-of-values ORDER BY (through the value-paged flow) ---

  private static boolean displayIsPrimitive(String displayProperty, Property resolved,
      boolean entityKnown, RuntimeException failure) {
    SelectorMeta meta = new SelectorMeta.Builder("ProductByPriceAndWarehouse", displayProperty)
        .isRich(true).valueProperty("product.id").build();
    ModelProvider modelProvider = mock(ModelProvider.class);
    when(modelProvider.getEntity("ProductByPriceAndWarehouse"))
        .thenReturn(entityKnown ? mock(Entity.class) : null);
    try (MockedStatic<ModelProvider> mpMock = mockStatic(ModelProvider.class);
        MockedStatic<DalUtil> dalUtilMock = mockStatic(DalUtil.class)) {
      mpMock.when(ModelProvider::getInstance).thenReturn(modelProvider);
      if (failure != null) {
        dalUtilMock.when(() -> DalUtil.getPropertyFromPath(any(Entity.class), anyString()))
            .thenThrow(failure);
      } else {
        dalUtilMock.when(() -> DalUtil.getPropertyFromPath(any(Entity.class), anyString()))
            .thenReturn(resolved);
      }
      return ValuePagedRichQuery.displayIsPrimitivePath(meta);
    }
  }

  private static Property property(boolean primitive) {
    Property p = mock(Property.class);
    when(p.isPrimitive()).thenReturn(primitive);
    return p;
  }

  /** A primitive display property (name) can be aggregated. */
  @Test
  public void testDisplayIsPrimitivePathTrueForPrimitiveProperty() {
    assertTrue(displayIsPrimitive("name", property(true), true, null));
  }

  /** A dotted path that ends in a primitive property (product.name) can be aggregated. */
  @Test
  public void testDisplayIsPrimitivePathTrueForDottedPrimitivePath() {
    assertTrue(displayIsPrimitive("product.name", property(true), true, null));
  }

  /** An association (the identifier of the price view is the product itself) cannot. */
  @Test
  public void testDisplayIsPrimitivePathFalseForAssociation() {
    assertFalse(displayIsPrimitive("product", property(false), true, null));
  }

  /** A blank or absent display property is never primitive and never touches the model. */
  @Test
  public void testDisplayIsPrimitivePathFalseForBlankDisplay() {
    assertFalse(displayIsPrimitive("", property(true), true, null));
    assertFalse(displayIsPrimitive("  ", property(true), true, null));
    assertFalse(ValuePagedRichQuery.displayIsPrimitivePath(null));
  }

  /** An entity the model does not know, or a path it cannot resolve, falls back to false. */
  @Test
  public void testDisplayIsPrimitivePathFalseWhenUnresolvable() {
    assertFalse("unknown entity", displayIsPrimitive("name", property(true), false, null));
    assertFalse("null property", displayIsPrimitive("name", null, true, null));
    assertFalse("path that does not resolve",
        displayIsPrimitive("no.such", null, true, new IllegalArgumentException("no property")));
  }

  /** A primitive display orders the page of values by min(display) and then the value. */
  @Test
  public void testValuePageOrderUsesMinOfPrimitiveDisplay() throws Exception {
    SelectorMeta meta = new SelectorMeta.Builder("ProductByPriceAndWarehouse", "product.name")
        .isRich(true).valueProperty("product.id").build();
    // built before any stubbing starts: property() stubs the mock itself
    Property resolved = property(true);

    PagedRun run = runPaged(meta, "", 20, 0, null, 1L, Arrays.asList((Object) "P1"),
        Arrays.asList(valueRow("idA", "P1", "A")), resolved);

    assertTrue(run.hql.get(1), run.hql.get(1)
        .endsWith(" group by e.product.id ORDER BY min(e.product.name), e.product.id"));
  }

  /** A display that is not primitive orders by the value alone (min() would not be valid HQL). */
  @Test
  public void testValuePageOrderIsTheValueAloneForAnAssociationDisplay() throws Exception {
    SelectorMeta meta = new SelectorMeta.Builder("ProductByPriceAndWarehouse", "product")
        .isRich(true).valueProperty("product.id").build();
    // built before any stubbing starts: property() stubs the mock itself
    Property resolved = property(false);

    PagedRun run = runPaged(meta, "", 20, 0, null, 1L, Arrays.asList((Object) "P1"),
        Arrays.asList(valueRow("idA", "P1", "A")), resolved);

    assertTrue(run.hql.get(1),
        run.hql.get(1).endsWith(" group by e.product.id ORDER BY e.product.id"));
  }

  // --- count / page / rows flow ---

  /**
   * One item per value, in the order of the page of values, each from the FIRST row of its value
   * (the lowest id, the row the old min(id) picked); totalCount is the distinct-value count; the
   * three steps share the where and the page is bound as a list.
   */
  @Test
  @SuppressWarnings("unchecked")
  public void testValuePagedFlowReturnsOneItemPerValueKeepingTheFirstRow() throws Exception {
    // rows arrive ordered by value then id; P1 has two rows (A first), P2 one (C)
    List<BaseOBObject> rows = Arrays.asList(valueRow("idA", "P1", "A"),
        valueRow("idB", "P1", "B"), valueRow("idC", "P2", "C"));

    PagedRun run = runPaged(valueFieldSelector(), "", 20, 0, "e.active = true", 2L,
        Arrays.asList((Object) "P2", "P1"), rows);

    assertEquals(2, run.total);
    assertEquals("page order, not row order", Arrays.asList("P2", "P1"), ids(run.items));
    assertEquals("C", run.items.getJSONObject(0).getString("label"));
    assertEquals("first row of P1, not the second", "A",
        run.items.getJSONObject(1).getString("label"));
    assertEquals(3, run.hql.size());
    assertTrue(run.hql.get(0), run.hql.get(0)
        .startsWith("select count(distinct e.product.id) from ProductByPriceAndWarehouse"));
    assertTrue(run.hql.get(1), run.hql.get(1).endsWith(
        " group by e.product.id ORDER BY e.product.id"));
    assertTrue(run.hql.get(2), run.hql.get(2).endsWith(
        " AND e.product.id in (:sfPageValues) ORDER BY e.product.id, e.id"));
    verify(run.rowsQuery).setParameterList("sfPageValues", Arrays.asList("P2", "P1"));
    assertEquals(Arrays.asList("count(distinct e.product.id)", "e.product.id"),
        run.selectClauses.subList(0, 2));
  }

  /** The page query carries the caller's limit and offset; totalCount is not the page size. */
  @Test
  public void testValuePagedFlowPagesTheValuesWithLimitAndOffset() throws Exception {
    PagedRun run = runPaged(valueFieldSelector(), "", 3, 6, null, 7L,
        Arrays.asList((Object) "P7"), Arrays.asList(valueRow("idG", "P7", "G")));

    verify(run.pageQuery).setFirstResult(6);
    verify(run.pageQuery).setMaxResults(3);
    assertEquals("totalCount is the distinct values, not what the page holds", 7, run.total);
    assertEquals(Arrays.asList("P7"), ids(run.items));
  }

  /** An offset at or beyond the total answers no items and never runs the page or rows query. */
  @Test
  public void testValuePagedFlowOffsetBeyondTotalReturnsNoItemsAndSkipsThePage() throws Exception {
    PagedRun run = runPaged(valueFieldSelector(), "", 20, 2, null, 2L,
        Collections.emptyList(), Collections.emptyList());

    assertEquals(2, run.total);
    assertEquals(0, run.items.length());
    verify(run.session, never()).createQuery(anyString());
  }

  /** No matching values: totalCount 0, no items, and only the count query runs. */
  @Test
  public void testValuePagedFlowEmptyResultOnlyRunsTheCount() throws Exception {
    PagedRun run = runPaged(valueFieldSelector(), "", 20, 0, null, 0L,
        Collections.emptyList(), Collections.emptyList());

    assertEquals(0, run.total);
    assertEquals(0, run.items.length());
    verify(run.session, never()).createQuery(anyString());
  }

  /** A null count (no row) is read as zero. */
  @Test
  public void testValuePagedFlowNullCountIsZero() throws Exception {
    PagedRun run = runPaged(valueFieldSelector(), "", 20, 0, null, null,
        Collections.emptyList(), Collections.emptyList());

    assertEquals(0, run.total);
    assertEquals(0, run.items.length());
  }

  /**
   * Rows with a null value are left out of count, page and rows alike, and the existing conditions
   * are parenthesised so the exclusion cannot be swallowed by an {@code or}.
   */
  @Test
  public void testValuePagedFlowExcludesNullValuesFromEveryStep() throws Exception {
    PagedRun run = runPaged(valueFieldSelector(), "", 20, 0, "e.a = 1 or e.b = 2", 1L,
        Arrays.asList((Object) "P1"), Arrays.asList(valueRow("idA", "P1", "A")));

    assertEquals(3, run.where.size());
    for (String where : run.where) {
      assertEquals("as e where (e.a = 1 or e.b = 2) AND e.product.id is not null", where);
    }
  }

  /** With no other condition the null-value exclusion is the whole where. */
  @Test
  public void testValuePagedFlowNullValueExclusionAloneWhenThereIsNoFilter() throws Exception {
    PagedRun run = runPaged(valueFieldSelector(), "", 20, 0, null, 1L,
        Arrays.asList((Object) "P1"), Arrays.asList(valueRow("idA", "P1", "A")));

    assertEquals("as e where e.product.id is not null", run.where.get(0));
  }

  /**
   * The search text is bound (lower-cased, wrapped in %) on the count, the page and the rows query,
   * and every property the search touches belongs to the one alias {@code e}: the old copy into a
   * sub-alias is gone, so there is nothing for a bare {@code product.} to bind to.
   */
  @Test
  public void testValuePagedFlowBindsTheSearchOnEveryQueryWithAliasQualifiedProperties()
      throws Exception {
    SelectorMeta meta = productByPriceSelector().build();

    PagedRun run = runPaged(meta, "Ab", 20, 0, "e.active = true", 1L,
        Arrays.asList((Object) "P1"), Arrays.asList(valueRow("idA", "P1", "A")));

    verify(run.countQuery).setParameter("search", "%ab%");
    verify(run.pageQuery).setParameter("search", "%ab%");
    verify(run.rowsQuery).setParameter("search", "%ab%");
    String where = run.where.get(0);
    assertTrue(where, where.contains("cast(e.product.name as string)"));
    assertTrue(where, where.contains("cast(e.product.searchKey as string)"));
    assertFalse("no bare relative path: " + where,
        java.util.regex.Pattern.compile("(?<![\\w.])product\\.").matcher(where).find());
    assertFalse("no sub-alias any more", where.contains("e_dv"));
  }

  /**
   * The readable-organization filter OBQuery keeps as a {@code String[]} is bound as a list on all
   * three queries; binding it as a single value was the 500 of every affected selector.
   */
  @Test
  public void testValuePagedFlowBindsTheArrayOrganizationFilterAsAList() throws Exception {
    PagedRun run = runPaged(valueFieldSelector(), "", 20, 0, null, 1L,
        Arrays.asList((Object) "P1"), Arrays.asList(valueRow("idA", "P1", "A")));

    String[] orgs = { "0", "ORG1" };
    verify(run.countQuery).setParameterList(eq("sfOrgs"), aryEq(orgs));
    verify(run.pageQuery).setParameterList(eq("sfOrgs"), aryEq(orgs));
    verify(run.rowsQuery).setParameterList(eq("sfOrgs"), aryEq(orgs));
    verify(run.countQuery, never()).setParameter(eq("sfOrgs"), any());
  }

  /** A value whose rows the rows query did not return yields no item rather than a null one. */
  @Test
  public void testValuePagedFlowSkipsAPageValueWithNoRow() throws Exception {
    PagedRun run = runPaged(valueFieldSelector(), "", 20, 0, null, 2L,
        Arrays.asList((Object) "P1", "P2"), Arrays.asList(valueRow("idB", "P2", "B")));

    assertEquals(Arrays.asList("P2"), ids(run.items));
  }

  /** A page value that is a BaseOBObject is read by its id. */
  @Test
  public void testValuePagedFlowReadsAnEntityPageValueById() throws Exception {
    BaseOBObject valueObject = mock(BaseOBObject.class);
    when(valueObject.getId()).thenReturn("P1");

    PagedRun run = runPaged(valueFieldSelector(), "", 20, 0, null, 1L,
        Arrays.asList((Object) valueObject), Arrays.asList(valueRow("idA", "P1", "A")));

    verify(run.rowsQuery).setParameterList("sfPageValues", Arrays.asList("P1"));
    assertEquals(Arrays.asList("P1"), ids(run.items));
  }

  /** The stock-breakdown selector keeps the row-paged flow: no session query is ever built. */
  @Test
  @SuppressWarnings({ "unchecked", "rawtypes" })
  public void testStockBreakdownSelectorStaysOnTheRowPagedFlow() throws Exception {
    SelectorMeta meta = new SelectorMeta.Builder("MaterialMgmtProductStock", "name")
        .isRich(true).valueProperty("product.id")
        .auxFields(Arrays.asList(auxWithSuffix("_QTY"), auxWithSuffix("_LOC"))).build();
    OBQuery countQuery = mock(OBQuery.class);
    OBQuery dataQuery = mock(OBQuery.class);
    when(countQuery.count()).thenReturn(0);
    when(dataQuery.list()).thenReturn(Collections.emptyList());
    OBDal obDal = mock(OBDal.class);
    List<String> capturedWhere = new ArrayList<>();
    when(obDal.createQuery(anyString(), anyString())).thenAnswer(inv -> {
      capturedWhere.add(inv.getArgument(1));
      return capturedWhere.size() == 1 ? countQuery : dataQuery;
    });
    try (MockedStatic<SelectorQueryBuilder> builderMock = mockStatic(SelectorQueryBuilder.class);
        MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class);
        MockedStatic<SelectorResponseSupport> respMock = mockStatic(
            SelectorResponseSupport.class)) {
      builderMock.when(
          () -> SelectorQueryBuilder.buildRichQueryWhereClause(any(), any(), any(), any(), any()))
          .thenReturn(new SelectorQueryBuilder.HqlWithParams("as e where e.active = true",
              new HashMap<>()));
      obDalMock.when(OBDal::getInstance).thenReturn(obDal);
      respMock.when(() -> SelectorResponseSupport.buildGridColumnMetadata(any()))
          .thenReturn(new JSONArray());
      respMock.when(
          () -> SelectorResponseSupport.buildSelectorResponse(any(), any(), anyInt(), anyInt(), anyInt()))
          .thenReturn(NeoResponse.ok(new JSONObject()));

      SelectorQueryExecutor.execute(meta, "", 20, 0, null, "org-1", null);

      assertEquals(Arrays.asList("as e where e.active = true",
          "as e where e.active = true ORDER BY e.name, e.id"), capturedWhere);
      verify(obDal, never()).getSession();
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
      org.mockito.Mockito.verify(obDal, never()).getSession();
      assertEquals("as e where e.active = true", capturedWhere.get(0));
      assertFalse("no DISTINCT subquery for PK-valued selector",
          capturedWhere.get(0).contains("min("));
      assertFalse("no GROUP BY for PK-valued selector",
          capturedWhere.get(0).contains("group by"));
    }
  }

  // ---------------------------------------------------------------
  // Stock-breakdown selectors — the ETP-4429 de-dup exception
  // ---------------------------------------------------------------

  /**
   * A selector exposing a per-row {@code _QTY} out-field is a stock-breakdown selector.
   */
  @Test
  public void testIsStockBreakdownSelectorDetectsQtyAuxField() throws Exception {
    SelectorMeta meta = new SelectorMeta.Builder("MaterialMgmtProductStock", "product$_identifier")
        .isRich(true).valueProperty("product.id")
        .auxFields(Collections.singletonList(auxWithSuffix("_QTY"))).build();

    assertTrue(invokeIsStockBreakdownSelector(meta));
  }

  /**
   * A selector exposing a per-row {@code _LOC} out-field is a stock-breakdown selector, and the
   * suffix match is case-insensitive.
   */
  @Test
  public void testIsStockBreakdownSelectorDetectsLocAuxFieldCaseInsensitive() throws Exception {
    SelectorMeta meta = new SelectorMeta.Builder("MaterialMgmtProductStock", "product$_identifier")
        .isRich(true).valueProperty("product.id")
        .auxFields(Collections.singletonList(auxWithSuffix("_loc"))).build();

    assertTrue(invokeIsStockBreakdownSelector(meta));
  }

  /**
   * A classic view-backed FK selector with only unrelated out-fields (no {@code _QTY}/{@code _LOC})
   * is NOT a stock-breakdown selector, so the ETP-4429 de-dup must still apply to it.
   */
  @Test
  public void testIsStockBreakdownSelectorIgnoresUnrelatedAuxFields() throws Exception {
    SelectorMeta meta = new SelectorMeta.Builder("MaterialMgmtProductStock", "product$_identifier")
        .isRich(true).valueProperty("product.id")
        .auxFields(Arrays.asList(auxWithSuffix("_UOM"), auxWithSuffix("_PRICE"))).build();

    assertFalse(invokeIsStockBreakdownSelector(meta));
  }

  /**
   * The de-dup guard is OFF for a stock-breakdown selector (all per-locator rows are kept), even
   * though its valueProperty is a non-PK path.
   */
  @Test
  public void testShouldDistinctByValueFalseForStockBreakdownSelector() throws Exception {
    SelectorMeta meta = new SelectorMeta.Builder("MaterialMgmtProductStock", "product$_identifier")
        .isRich(true).valueProperty("product.id")
        .auxFields(Arrays.asList(auxWithSuffix("_QTY"), auxWithSuffix("_LOC"))).build();

    assertFalse(invokeShouldDistinctByValue(meta));
  }

  /**
   * The de-dup guard stays ON for a classic view-backed FK selector (non-PK valueProperty, no
   * stock-breakdown out-fields) — the ETP-4429 duplicate-row fix is preserved.
   */
  @Test
  public void testShouldDistinctByValueTrueForClassicViewBackedSelector() throws Exception {
    SelectorMeta meta = new SelectorMeta.Builder("MaterialMgmtProductStock", "product$_identifier")
        .isRich(true).valueProperty("product.id")
        .auxFields(Collections.singletonList(auxWithSuffix("_UOM"))).build();

    assertTrue(invokeShouldDistinctByValue(meta));
  }

  /**
   * End-to-end: a stock-breakdown selector (aux {@code _QTY}/{@code _LOC}, non-PK valueProperty)
   * must NOT collapse rows. Every per-locator row the query returns for the same product reaches the
   * response, and the where string passed to the query carries no representative-row subquery.
   */
  @Test
  @SuppressWarnings("unchecked")
  public void testExecuteRichQueryStockBreakdownSelectorReturnsAllRows() throws Exception {
    SelectorMeta meta = new SelectorMeta.Builder("MaterialMgmtProductStock", "name")
        .isRich(true).valueProperty("product.id")
        .auxFields(Arrays.asList(auxWithSuffix("_QTY"), auxWithSuffix("_LOC"))).build();

    // Three view rows for the SAME product: the synthetic zero-stock generic row (id == product id,
    // the string-MIN prefix that used to win) plus two real per-locator rows. All must survive.
    BaseOBObject prod = mock(BaseOBObject.class);
    when(prod.get("id")).thenReturn("PROD1");

    BaseOBObject generic = mock(BaseOBObject.class);
    when(generic.get("product")).thenReturn(prod);
    when(generic.getId()).thenReturn("PROD1");
    when(generic.getIdentifier()).thenReturn("Product One");
    BaseOBObject loc1 = mock(BaseOBObject.class);
    when(loc1.get("product")).thenReturn(prod);
    when(loc1.getId()).thenReturn("PROD1SD01");
    when(loc1.getIdentifier()).thenReturn("Product One");
    BaseOBObject loc2 = mock(BaseOBObject.class);
    when(loc2.get("product")).thenReturn(prod);
    when(loc2.getId()).thenReturn("PROD1SD02");
    when(loc2.getIdentifier()).thenReturn("Product One");

    OBQuery countQuery = mock(OBQuery.class);
    OBQuery dataQuery = mock(OBQuery.class);
    when(countQuery.count()).thenReturn(3);
    when(dataQuery.list()).thenReturn(Arrays.asList(generic, loc1, loc2));

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
      // No representative-row de-dup: the where is byte-identical to the builder output.
      assertEquals(2, capturedWhere.size());
      assertEquals("as e where e.active = true", capturedWhere.get(0));
      assertFalse("stock-breakdown selector must not be de-duplicated",
          capturedWhere.get(0).contains("group by"));
      assertFalse("stock-breakdown selector must not restrict to MIN(id)",
          capturedWhere.get(0).contains("min("));
      // All three per-locator rows survive to the response.
      assertEquals(3, capturedTotal[0]);
      assertEquals(3, capturedItems[0].length());
    }
  }

  // ---------------------------------------------------------------
  // Search of view-backed selectors (ETP-5670, value-paged since ETP-5707)
  // ---------------------------------------------------------------

  /**
   * Runs a rich selector query end to end through the REAL {@link SelectorQueryBuilder} (only the
   * DAL, the org model and the response are mocked) and returns the where strings handed to the
   * count and the data query. No OBContext and an unknown entity mean no org filter is added.
   */
  @SuppressWarnings("unchecked")
  private static List<String> captureRichWhere(SelectorMeta meta, String search,
      String validationFilter) throws Exception {
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
    ModelProvider modelProvider = mock(ModelProvider.class);
    when(modelProvider.getEntity(anyString())).thenReturn(null);

    try (MockedStatic<NeoSelectorExecutionHelper> helperMock = mockStatic(
        NeoSelectorExecutionHelper.class); MockedStatic<OBDal> obDalMock = mockStatic(
        OBDal.class); MockedStatic<ModelProvider> mpMock = mockStatic(
        ModelProvider.class); MockedStatic<OBContext> ctxMock = mockStatic(
        OBContext.class); MockedStatic<SelectorResponseSupport> respMock = mockStatic(
        SelectorResponseSupport.class)) {

      helperMock.when(() -> NeoSelectorExecutionHelper.bindNamedParameters(any(OBQuery.class), any()))
          .thenAnswer(inv -> null);
      obDalMock.when(OBDal::getInstance).thenReturn(obDal);
      mpMock.when(ModelProvider::getInstance).thenReturn(modelProvider);
      ctxMock.when(OBContext::getOBContext).thenReturn(null);
      respMock.when(() -> SelectorResponseSupport.buildGridColumnMetadata(any())).thenReturn(new JSONArray());
      respMock.when(
          () -> SelectorResponseSupport.buildSelectorResponse(any(), any(), anyInt(), anyInt(), anyInt()))
          .thenReturn(NeoResponse.ok(new JSONObject()));

      SelectorQueryExecutor.execute(meta, search, 20, 0, validationFilter, null, null);
    }
    return capturedWhere;
  }

  /** The product-by-price selector shape: view-backed, Value Field product.id, dotted search. */
  private static SelectorMeta.Builder productByPriceSelector() {
    return new SelectorMeta.Builder("ProductByPriceAndWarehouse", "product.name")
        .isRich(true).valueProperty("product.id")
        .searchableProperties(Arrays.asList(SearchableFragment.ofRelativePath("product.id"),
            SearchableFragment.ofRelativePath("product.name"),
            SearchableFragment.ofRelativePath("product.searchKey")));
  }

  /**
   * The search of a view-backed selector over dotted DAL paths ({@code product.name}) reaches the
   * value-paged queries alias-qualified: every property belongs to {@code e}, so Hibernate cannot
   * bind a bare relative path to the wrong row (the ETP-5670 trap of the old sub-alias copy).
   */
  @Test
  public void testValuePagedSearchWhereIsAliasQualifiedAndHasNoSubAlias() throws Exception {
    PagedRun run = runPaged(productByPriceSelector().build(), "p", 20, 0, "e.active = true", 1L,
        Arrays.asList((Object) "P1"), Arrays.asList(valueRow("idA", "P1", "A")));

    assertEquals(3, run.where.size());
    for (String where : run.where) {
      assertTrue("search alias-qualified: " + where, where.contains("cast(e.product.name as string)"));
      assertTrue("searchKey alias-qualified: " + where,
          where.contains("cast(e.product.searchKey as string)"));
      assertTrue("null values excluded: " + where, where.endsWith("AND e.product.id is not null"));
      assertFalse("no sub-alias: " + where, where.contains("e_dv"));
      assertFalse("no bare relative path: " + where,
          java.util.regex.Pattern.compile("(?<![\\w.])product\\.").matcher(where).find());
    }
  }

  /**
   * A PK-valued selector is not value-paged; with a search over bare properties its where is
   * byte-identical to the pre-ETP-5670 output.
   */
  @Test
  public void testPkValuedSelectorSearchWhereIsUnchanged() throws Exception {
    SelectorMeta meta = new SelectorMeta.Builder("Country", "name").isRich(true)
        .searchableProperties(Arrays.asList(SearchableFragment.ofRelativePath("name"),
            SearchableFragment.ofRelativePath("iSOCountryCode")))
        .build();

    List<String> where = captureRichWhere(meta, "es", "e.active = true");

    assertEquals("as e where e.active = true AND ("
        + "lower(COALESCE(cast(e.name as string), '')) LIKE :search"
        + " OR lower(COALESCE(cast(e.iSOCountryCode as string), '')) LIKE :search)", where.get(0));
  }

  /**
   * A stock-breakdown selector keeps every per-locator row: not value-paged even with a search,
   * and the where is exactly the builder output.
   */
  @Test
  public void testStockBreakdownSelectorSearchKeepsTheRowPagedWhere() throws Exception {
    SelectorMeta meta = new SelectorMeta.Builder("MaterialMgmtProductStock", "product.name")
        .isRich(true).valueProperty("product.id")
        .auxFields(Arrays.asList(auxWithSuffix("_QTY"), auxWithSuffix("_LOC")))
        .searchableProperties(Arrays.asList(SearchableFragment.ofRelativePath("product.name"),
            SearchableFragment.ofRelativePath("product.searchKey")))
        .build();

    List<String> where = captureRichWhere(meta, "p", "e.active = true");

    assertEquals("as e where e.active = true AND ("
        + "lower(COALESCE(cast(e.product.name as string), '')) LIKE :search"
        + " OR lower(COALESCE(cast(e.product.searchKey as string), '')) LIKE :search)", where.get(0));
    assertFalse("stock-breakdown selector must not be de-duplicated", where.get(0).contains("not null"));
  }
}
