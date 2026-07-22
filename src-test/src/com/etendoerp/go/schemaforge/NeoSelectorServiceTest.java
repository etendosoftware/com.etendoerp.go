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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.ModelProvider;
import org.openbravo.base.model.Property;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.datamodel.Column;
import org.openbravo.model.ad.datamodel.Table;

import com.etendoerp.go.schemaforge.data.SFField;
import com.etendoerp.go.schemaforge.selector.meta.SelectorMeta;
import com.etendoerp.go.schemaforge.selector.policy.NeoSelectorPolicy;

/**
 * Unit tests for {@link NeoSelectorService} utility methods.
 */
class NeoSelectorServiceTest {

  private static final String FILTER_A = "a.id='X'";
  private static final String FILTER_B = "b.org='Y'";

  /** Combining two null filters returns null. */
  @Test
  void testCombineFiltersBothNullReturnsNull() {
    assertNull(NeoSelectorService.combineFilters(null, null));
  }

  /** Combining two blank filters returns null. */
  @Test
  void testCombineFiltersBothBlankReturnsNull() {
    assertNull(NeoSelectorService.combineFilters("", "  "));
  }

  /** A single non-blank filter is returned as-is. */
  @Test
  void testCombineFiltersSingleNonBlankReturnsThat() {
    assertEquals(FILTER_A, NeoSelectorService.combineFilters(null, FILTER_A));
  }

  /** Two non-blank filters are joined with AND. */
  @Test
  void testCombineFiltersTwoNonBlankJoinsWithAnd() {
    String result = NeoSelectorService.combineFilters(FILTER_A, FILTER_B);
    assertEquals(FILTER_A + " AND " + FILTER_B, result);
  }

  /** Blank entries in the middle are skipped. */
  @Test
  void testCombineFiltersSkipsBlanksInMiddle() {
    String result = NeoSelectorService.combineFilters("x=1", "", "y=2");
    assertEquals("x=1 AND y=2", result);
  }

  /** Calling with no arguments returns null. */
  @Test
  void testCombineFiltersNoArgsReturnsNull() {
    assertNull(NeoSelectorService.combineFilters());
  }

  // --------------------------------------------------------------------
  // resolveSearchableFragment — custom-HQL selectors with blank property
  // --------------------------------------------------------------------

  /** Standard selector: non-blank property is returned verbatim. */
  @Test
  @DisplayName("resolveSearchableFragment prefers non-blank property")
  void testResolveFragmentPrefersProperty() {
    assertEquals("name",
        NeoSelectorService.resolveSearchableFragment("name", "bp.name"));
  }

  /** Custom HQL selector: blank property + safe dotted clause_left_part. */
  @Test
  @DisplayName("resolveSearchableFragment accepts dotted clause_left_part when property is blank")
  void testResolveFragmentFallsBackToClauseLeftPart() {
    assertEquals("bp.name",
        NeoSelectorService.resolveSearchableFragment("", "bp.name"));
    assertEquals("bp.searchKey",
        NeoSelectorService.resolveSearchableFragment(null, "bp.searchKey"));
  }

  /** Deep dotted path (contact.businessPartner.name) is safe. */
  @Test
  @DisplayName("resolveSearchableFragment accepts multi-segment dotted paths")
  void testResolveFragmentDeepPath() {
    assertEquals("contact.businessPartner.name",
        NeoSelectorService.resolveSearchableFragment("", "contact.businessPartner.name"));
  }

  /** Whitespace around a safe clause is stripped. */
  @Test
  @DisplayName("resolveSearchableFragment trims safe clause_left_part")
  void testResolveFragmentTrimsClauseLeftPart() {
    assertEquals("bp.name",
        NeoSelectorService.resolveSearchableFragment("", "  bp.name  "));
  }

  /** Complex expressions (COALESCE, arithmetic, quotes, etc.) are rejected. */
  @Test
  @DisplayName("resolveSearchableFragment rejects complex HQL expressions")
  void testResolveFragmentRejectsComplexExpressions() {
    assertNull(NeoSelectorService.resolveSearchableFragment(
        "", "COALESCE(contact.name, usercontact.name)"));
    assertNull(NeoSelectorService.resolveSearchableFragment(
        "", "bp.creditLimit - bp.creditUsed"));
    assertNull(NeoSelectorService.resolveSearchableFragment(
        "", "bp.name || ' ' || bp.searchKey"));
    assertNull(NeoSelectorService.resolveSearchableFragment(
        "", "(select x from Foo f)"));
    assertNull(NeoSelectorService.resolveSearchableFragment(
        "", "bp.name = 'x'"));
  }

  /** Both blank returns null — no search applied. */
  @Test
  @DisplayName("resolveSearchableFragment returns null when both property and clause are blank")
  void testResolveFragmentBothBlank() {
    assertNull(NeoSelectorService.resolveSearchableFragment(null, null));
    assertNull(NeoSelectorService.resolveSearchableFragment("", ""));
    assertNull(NeoSelectorService.resolveSearchableFragment("  ", "  "));
  }

  // --------------------------------------------------------------------
  // executeSelectorQuery — language propagation to SelectorQueryExecutor
  // --------------------------------------------------------------------

  /** A non-blank language in contextParams is forwarded to SelectorQueryExecutor extraFilterParams. */
  @Test
  @DisplayName("language in contextParams is forwarded to SelectorQueryExecutor extraFilterParams")
  @SuppressWarnings("unchecked")
  void testLanguagePropagatedToExecutor() throws Exception {
    SelectorMeta meta = new SelectorMeta("Country", "name", null);
    Map<String, String> ctx = new HashMap<>();
    ctx.put("language", "es_ES");
    Map<String, Object>[] captured = new Map[1];

    try (MockedStatic<NeoSelectorPolicy> pMock = mockStatic(
        NeoSelectorPolicy.class); MockedStatic<SelectorQueryExecutor> eMock = mockStatic(SelectorQueryExecutor.class)) {
      pMock.when(() -> NeoSelectorPolicy.resolveContextParamFilter(anyString(), any(), anyString())).thenReturn(null);
      eMock.when(() -> SelectorQueryExecutor.execute(any(), anyString(), anyInt(), anyInt(), any(), anyString(),
          any())).thenAnswer(inv -> {
        captured[0] = inv.getArgument(6);
        return null;
      });

      invokeExecuteSelectorQuery(meta, ctx);
    }

    assertNotNull(captured[0]);
    assertEquals("es_ES", captured[0].get("language"));
  }

  /** A blank language value in contextParams is not added to extraFilterParams. */
  @Test
  @DisplayName("blank language in contextParams is not forwarded to SelectorQueryExecutor")
  @SuppressWarnings("unchecked")
  void testBlankLanguageNotPropagated() throws Exception {
    SelectorMeta meta = new SelectorMeta("Country", "name", null);
    Map<String, String> ctx = new HashMap<>();
    ctx.put("language", "  ");
    Map<String, Object>[] captured = new Map[1];

    try (MockedStatic<NeoSelectorPolicy> pMock = mockStatic(
        NeoSelectorPolicy.class); MockedStatic<SelectorQueryExecutor> eMock = mockStatic(SelectorQueryExecutor.class)) {
      pMock.when(() -> NeoSelectorPolicy.resolveContextParamFilter(anyString(), any(), anyString())).thenReturn(null);
      eMock.when(() -> SelectorQueryExecutor.execute(any(), anyString(), anyInt(), anyInt(), any(), anyString(),
          any())).thenAnswer(inv -> {
        captured[0] = inv.getArgument(6);
        return null;
      });

      invokeExecuteSelectorQuery(meta, ctx);
    }

    assertNotNull(captured[0]);
    assertFalse(captured[0].containsKey("language"));
  }

  /** Null contextParams does not produce a language key in extraFilterParams. */
  @Test
  @DisplayName("null contextParams does not add language to SelectorQueryExecutor params")
  @SuppressWarnings("unchecked")
  void testNullContextParamsNoLanguage() throws Exception {
    SelectorMeta meta = new SelectorMeta("Country", "name", null);
    Map<String, Object>[] captured = new Map[1];

    try (MockedStatic<NeoSelectorPolicy> pMock = mockStatic(
        NeoSelectorPolicy.class); MockedStatic<SelectorQueryExecutor> eMock = mockStatic(SelectorQueryExecutor.class)) {
      pMock.when(() -> NeoSelectorPolicy.resolveContextParamFilter(anyString(), any(), anyString())).thenReturn(null);
      eMock.when(() -> SelectorQueryExecutor.execute(any(), anyString(), anyInt(), anyInt(), any(), anyString(),
          any())).thenAnswer(inv -> {
        captured[0] = inv.getArgument(6);
        return null;
      });

      invokeExecuteSelectorQuery(meta, null);
    }

    assertNotNull(captured[0]);
    assertFalse(captured[0].containsKey("language"));
  }

  // --------------------------------------------------------------------
  // matchesPropertyName — resolve a selector identifier by DAL property
  // name (in addition to the DB column name). See ETP-4058.
  // --------------------------------------------------------------------

  private static final String PRICE_LIST_COLUMN = "M_PriceList_ID";
  private static final String PRICE_LIST_PROPERTY = "priceList";

  private static Column mockColumn(String dbColumnName) {
    Column column = mock(Column.class);
    when(column.getDBColumnName()).thenReturn(dbColumnName);
    return column;
  }

  private static Entity mockEntityWithProperty(String dbColumnName, String propertyName) {
    Entity entity = mock(Entity.class);
    Property property = mock(Property.class);
    when(property.getName()).thenReturn(propertyName);
    when(entity.getPropertyByColumnName(dbColumnName)).thenReturn(property);
    return entity;
  }

  /** The DAL property name resolves the FK column (priceList -> M_PriceList_ID). */
  @Test
  @DisplayName("matchesPropertyName resolves the FK column by DAL property name")
  void testMatchesPropertyNameByProperty() {
    Column column = mockColumn(PRICE_LIST_COLUMN);
    Entity entity = mockEntityWithProperty(PRICE_LIST_COLUMN, PRICE_LIST_PROPERTY);

    assertTrue(NeoSelectorService.matchesPropertyName(entity, column, PRICE_LIST_PROPERTY));
  }

  /** Property-name matching is case-insensitive. */
  @Test
  @DisplayName("matchesPropertyName is case-insensitive")
  void testMatchesPropertyNameCaseInsensitive() {
    Column column = mockColumn(PRICE_LIST_COLUMN);
    Entity entity = mockEntityWithProperty(PRICE_LIST_COLUMN, PRICE_LIST_PROPERTY);

    assertTrue(NeoSelectorService.matchesPropertyName(entity, column, "PRICELIST"));
  }

  /** An identifier that matches neither the property nor the column returns false. */
  @Test
  @DisplayName("matchesPropertyName returns false for an unknown identifier")
  void testMatchesPropertyNameUnknownIdentifier() {
    Column column = mockColumn(PRICE_LIST_COLUMN);
    Entity entity = mockEntityWithProperty(PRICE_LIST_COLUMN, PRICE_LIST_PROPERTY);

    assertFalse(NeoSelectorService.matchesPropertyName(entity, column, "banana"));
  }

  /** A null DAL entity or column is handled defensively (no NPE, returns false). */
  @Test
  @DisplayName("matchesPropertyName returns false for null entity or column")
  void testMatchesPropertyNameNullArguments() {
    Column column = mockColumn(PRICE_LIST_COLUMN);

    assertFalse(NeoSelectorService.matchesPropertyName(null, column, PRICE_LIST_PROPERTY));
    assertFalse(NeoSelectorService.matchesPropertyName(mock(Entity.class), null, PRICE_LIST_PROPERTY));
  }

  /** A property lookup that throws is swallowed and treated as no match. */
  @Test
  @DisplayName("matchesPropertyName returns false when property lookup throws")
  void testMatchesPropertyNameSwallowsException() {
    Column column = mockColumn(PRICE_LIST_COLUMN);
    Entity entity = mock(Entity.class);
    when(entity.getPropertyByColumnName(PRICE_LIST_COLUMN))
        .thenThrow(new RuntimeException("model not mapped"));

    assertFalse(NeoSelectorService.matchesPropertyName(entity, column, PRICE_LIST_PROPERTY));
  }

  // --------------------------------------------------------------------
  // findFieldByColumnName / findFieldByPropertyName — resolve an SFField by
  // DB column name (fast path) with a DAL property-name fallback. See
  // ETP-4058. Both methods are private static and invoked via reflection.
  // --------------------------------------------------------------------

  private static final String BPARTNER_COLUMN = "C_BPartner_ID";
  private static final String BPARTNER_PROPERTY = "businessPartner";
  private static final String PRICE_LIST_TABLE = "M_PriceList";
  private static final String ENTITY_ID = "entity-1";

  private static Column mockColumnWithTable(String dbColumnName, String dbTableName) {
    Column column = mock(Column.class);
    when(column.getDBColumnName()).thenReturn(dbColumnName);
    Table table = mock(Table.class);
    when(table.getDBTableName()).thenReturn(dbTableName);
    when(column.getTable()).thenReturn(table);
    return column;
  }

  private static SFField mockFieldWithColumn(Column column) {
    SFField field = mock(SFField.class);
    when(field.getADColumn()).thenReturn(column);
    return field;
  }

  /** Stub the fluent SFField criteria chain and return the shared criteria mock. */
  @SuppressWarnings("unchecked")
  private static OBCriteria<SFField> stubFieldCriteria(OBDal obDalMock) {
    OBCriteria<SFField> criteria = mock(OBCriteria.class);
    when(obDalMock.createCriteria(SFField.class)).thenReturn(criteria);
    when(criteria.add(any())).thenReturn(criteria);
    when(criteria.setMaxResults(anyInt())).thenReturn(criteria);
    return criteria;
  }

  /** Fast path: an exact DB column-name match returns the first result directly. */
  @Test
  @DisplayName("findFieldByColumnName returns the first result on a fast-path column match")
  void testFindFieldByColumnNameFastPathHit() throws Exception {
    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      OBDal obDalMock = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(obDalMock);
      OBCriteria<SFField> criteria = stubFieldCriteria(obDalMock);

      SFField expected = mock(SFField.class);
      when(criteria.list()).thenReturn(Collections.singletonList(expected));

      SFField result = invokeFindFieldByColumnName(ENTITY_ID, PRICE_LIST_COLUMN);

      assertSame(expected, result);
    }
  }

  /** Fast path miss falls back to property-name resolution and returns the match. */
  @Test
  @DisplayName("findFieldByColumnName falls back to property-name resolution when the column match is empty")
  void testFindFieldByColumnNameFallbackHit() throws Exception {
    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
        MockedStatic<ModelProvider> modelProvider = mockStatic(ModelProvider.class)) {
      OBDal obDalMock = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(obDalMock);
      OBCriteria<SFField> criteria = stubFieldCriteria(obDalMock);

      Column column = mockColumnWithTable(PRICE_LIST_COLUMN, PRICE_LIST_TABLE);
      SFField field = mockFieldWithColumn(column);
      // First list() is the empty fast-path result, second is the fallback scan.
      when(criteria.list())
          .thenReturn(Collections.emptyList())
          .thenReturn(Collections.singletonList(field));

      ModelProvider mpMock = mock(ModelProvider.class);
      modelProvider.when(ModelProvider::getInstance).thenReturn(mpMock);
      Entity dalEntity = mockEntityWithProperty(PRICE_LIST_COLUMN, PRICE_LIST_PROPERTY);
      when(mpMock.getEntityByTableName(PRICE_LIST_TABLE)).thenReturn(dalEntity);

      SFField result = invokeFindFieldByColumnName(ENTITY_ID, PRICE_LIST_PROPERTY);

      assertSame(field, result);
    }
  }

  /** Fast path miss and an empty fallback scan yield null. */
  @Test
  @DisplayName("findFieldByColumnName returns null when neither column nor property matches")
  void testFindFieldByColumnNameNoMatch() throws Exception {
    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      OBDal obDalMock = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(obDalMock);
      OBCriteria<SFField> criteria = stubFieldCriteria(obDalMock);

      when(criteria.list())
          .thenReturn(Collections.emptyList())
          .thenReturn(Collections.emptyList());

      SFField result = invokeFindFieldByColumnName(ENTITY_ID, "unknown");

      assertNull(result);
    }
  }

  /** A field with a null AD_Column is skipped; a later matching field is returned. */
  @Test
  @DisplayName("findFieldByPropertyName skips fields with a null AD_Column")
  void testFindFieldByPropertyNameSkipsNullColumn() throws Exception {
    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
        MockedStatic<ModelProvider> modelProvider = mockStatic(ModelProvider.class)) {
      OBDal obDalMock = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(obDalMock);
      OBCriteria<SFField> criteria = stubFieldCriteria(obDalMock);

      SFField nullColumnField = mockFieldWithColumn(null);
      Column column = mockColumnWithTable(PRICE_LIST_COLUMN, PRICE_LIST_TABLE);
      SFField matchField = mockFieldWithColumn(column);
      when(criteria.list()).thenReturn(Arrays.asList(nullColumnField, matchField));

      ModelProvider mpMock = mock(ModelProvider.class);
      modelProvider.when(ModelProvider::getInstance).thenReturn(mpMock);
      Entity dalEntity = mockEntityWithProperty(PRICE_LIST_COLUMN, PRICE_LIST_PROPERTY);
      when(mpMock.getEntityByTableName(PRICE_LIST_TABLE)).thenReturn(dalEntity);

      SFField result = invokeFindFieldByPropertyName(ENTITY_ID, PRICE_LIST_PROPERTY);

      assertSame(matchField, result);
    }
  }

  /** The DAL entity is resolved once (on the first field with a column) and reused. */
  @Test
  @DisplayName("findFieldByPropertyName resolves the DAL entity once and reuses it")
  void testFindFieldByPropertyNameResolvesEntityOnce() throws Exception {
    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
        MockedStatic<ModelProvider> modelProvider = mockStatic(ModelProvider.class)) {
      OBDal obDalMock = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(obDalMock);
      OBCriteria<SFField> criteria = stubFieldCriteria(obDalMock);

      // First field carries the table used to resolve the DAL entity; the second
      // field only needs its column name (entity already resolved and reused).
      Column firstColumn = mockColumnWithTable(BPARTNER_COLUMN, PRICE_LIST_TABLE);
      Column secondColumn = mockColumn(PRICE_LIST_COLUMN);
      SFField firstField = mockFieldWithColumn(firstColumn);
      SFField secondField = mockFieldWithColumn(secondColumn);
      when(criteria.list()).thenReturn(Arrays.asList(firstField, secondField));

      ModelProvider mpMock = mock(ModelProvider.class);
      modelProvider.when(ModelProvider::getInstance).thenReturn(mpMock);
      Entity dalEntity = mock(Entity.class);
      Property bpProperty = mock(Property.class);
      when(bpProperty.getName()).thenReturn(BPARTNER_PROPERTY);
      Property priceListProperty = mock(Property.class);
      when(priceListProperty.getName()).thenReturn(PRICE_LIST_PROPERTY);
      when(dalEntity.getPropertyByColumnName(BPARTNER_COLUMN)).thenReturn(bpProperty);
      when(dalEntity.getPropertyByColumnName(PRICE_LIST_COLUMN)).thenReturn(priceListProperty);
      when(mpMock.getEntityByTableName(PRICE_LIST_TABLE)).thenReturn(dalEntity);

      SFField result = invokeFindFieldByPropertyName(ENTITY_ID, PRICE_LIST_PROPERTY);

      assertSame(secondField, result);
      verify(mpMock, times(1)).getEntityByTableName(anyString());
    }
  }

  /** No field property matches the identifier, so null is returned. */
  @Test
  @DisplayName("findFieldByPropertyName returns null when no property matches")
  void testFindFieldByPropertyNameNoMatch() throws Exception {
    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
        MockedStatic<ModelProvider> modelProvider = mockStatic(ModelProvider.class)) {
      OBDal obDalMock = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(obDalMock);
      OBCriteria<SFField> criteria = stubFieldCriteria(obDalMock);

      Column column = mockColumnWithTable(PRICE_LIST_COLUMN, PRICE_LIST_TABLE);
      SFField field = mockFieldWithColumn(column);
      when(criteria.list()).thenReturn(Collections.singletonList(field));

      ModelProvider mpMock = mock(ModelProvider.class);
      modelProvider.when(ModelProvider::getInstance).thenReturn(mpMock);
      Entity dalEntity = mockEntityWithProperty(PRICE_LIST_COLUMN, PRICE_LIST_PROPERTY);
      when(mpMock.getEntityByTableName(PRICE_LIST_TABLE)).thenReturn(dalEntity);

      SFField result = invokeFindFieldByPropertyName(ENTITY_ID, "banana");

      assertNull(result);
    }
  }

  /** Property-name matching is case-insensitive (PRICELIST -> priceList). */
  @Test
  @DisplayName("findFieldByPropertyName matches the identifier case-insensitively")
  void testFindFieldByPropertyNameCaseInsensitive() throws Exception {
    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
        MockedStatic<ModelProvider> modelProvider = mockStatic(ModelProvider.class)) {
      OBDal obDalMock = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(obDalMock);
      OBCriteria<SFField> criteria = stubFieldCriteria(obDalMock);

      Column column = mockColumnWithTable(PRICE_LIST_COLUMN, PRICE_LIST_TABLE);
      SFField field = mockFieldWithColumn(column);
      when(criteria.list()).thenReturn(Collections.singletonList(field));

      ModelProvider mpMock = mock(ModelProvider.class);
      modelProvider.when(ModelProvider::getInstance).thenReturn(mpMock);
      Entity dalEntity = mockEntityWithProperty(PRICE_LIST_COLUMN, PRICE_LIST_PROPERTY);
      when(mpMock.getEntityByTableName(PRICE_LIST_TABLE)).thenReturn(dalEntity);

      SFField result = invokeFindFieldByPropertyName(ENTITY_ID, "PRICELIST");

      assertSame(field, result);
    }
  }

  private static SFField invokeFindFieldByColumnName(String entityId, String columnName)
      throws Exception {
    Method m = NeoSelectorService.class.getDeclaredMethod("findFieldByColumnName",
        String.class, String.class);
    m.setAccessible(true);
    return (SFField) m.invoke(null, entityId, columnName);
  }

  private static SFField invokeFindFieldByPropertyName(String entityId, String identifier)
      throws Exception {
    Method m = NeoSelectorService.class.getDeclaredMethod("findFieldByPropertyName",
        String.class, String.class);
    m.setAccessible(true);
    return (SFField) m.invoke(null, entityId, identifier);
  }

  @SuppressWarnings("unchecked")
  private static void invokeExecuteSelectorQuery(SelectorMeta meta, Map<String, String> contextParams)
      throws Exception {
    Method m = NeoSelectorService.class.getDeclaredMethod("executeSelectorQuery",
        SelectorMeta.class, String.class, int.class, int.class,
        String.class, String.class, Map.class);
    m.setAccessible(true);
    m.invoke(null, meta, "", 20, 0, "org-1", null, contextParams);
  }
}
