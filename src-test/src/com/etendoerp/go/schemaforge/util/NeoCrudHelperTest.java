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
package com.etendoerp.go.schemaforge.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.servlet.http.HttpServletResponse;

import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.openbravo.base.exception.OBException;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.ModelProvider;
import org.openbravo.base.model.Property;
import org.openbravo.model.ad.datamodel.Column;
import org.openbravo.model.ad.datamodel.Table;
import org.openbravo.model.ad.ui.Tab;
import org.openbravo.model.ad.ui.Window;
import org.openbravo.service.json.JsonConstants;
import org.openbravo.erpCommon.utility.OBMessageUtils;

import com.etendoerp.go.schemaforge.DocTypeResolver;
import com.etendoerp.go.schemaforge.NeoContext;
import com.etendoerp.go.schemaforge.NeoDefaultsCascadeHelper;
import com.etendoerp.go.schemaforge.NeoDefaultsService;
import com.etendoerp.go.schemaforge.NeoFieldFilter;
import com.etendoerp.go.schemaforge.NeoResponse;

/**
 * Unit tests for {@link NeoCrudHelper}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class NeoCrudHelperTest {

  private static final String TAB_ID = "tab-123";
  private static final String WINDOW_ID = "win-456";
  private static final String ENTITY_NAME = "Order";
  private static final String RECORD_ID = "rec-789";

  private NeoContext context;
  private Tab adTab;
  private Window window;

  @BeforeEach
  void setUp() {
    context = mock(NeoContext.class);
    adTab = mock(Tab.class);
    window = mock(Window.class);
    when(adTab.getId()).thenReturn(TAB_ID);
    when(adTab.getWindow()).thenReturn(window);
    when(window.getId()).thenReturn(WINDOW_ID);
  }

  @Nested
  @DisplayName("buildBaseParams")
  class BuildBaseParams {

    @Test
    @DisplayName("should include entity name, tab ID, window ID and no-active-filter")
    void shouldIncludeBaseParameters() {
      when(context.getRecordId()).thenReturn(null);
      when(context.getQueryParams()).thenReturn(null);

      Map<String, String> params = NeoCrudHelper.buildBaseParams(context, adTab, ENTITY_NAME);

      assertEquals(ENTITY_NAME, params.get(JsonConstants.ENTITYNAME));
      assertEquals(TAB_ID, params.get(JsonConstants.TAB_PARAMETER));
      assertEquals(WINDOW_ID, params.get(JsonConstants.WINDOW_ID));
      assertEquals("true", params.get(JsonConstants.NO_ACTIVE_FILTER));
    }

    @Test
    @DisplayName("should include record ID when present in context")
    void shouldIncludeRecordIdWhenPresent() {
      when(context.getRecordId()).thenReturn(RECORD_ID);
      when(context.getQueryParams()).thenReturn(null);

      Map<String, String> params = NeoCrudHelper.buildBaseParams(context, adTab, ENTITY_NAME);

      assertEquals(RECORD_ID, params.get(JsonConstants.ID));
    }

    @Test
    @DisplayName("should not include record ID when null")
    void shouldNotIncludeRecordIdWhenNull() {
      when(context.getRecordId()).thenReturn(null);
      when(context.getQueryParams()).thenReturn(null);

      Map<String, String> params = NeoCrudHelper.buildBaseParams(context, adTab, ENTITY_NAME);

      assertFalse(params.containsKey(JsonConstants.ID));
    }

    @Test
    @DisplayName("should copy query params from context")
    void shouldCopyQueryParams() {
      when(context.getRecordId()).thenReturn(null);
      Map<String, String> queryParams = new HashMap<>();
      queryParams.put("customFilter", "value1");
      queryParams.put("sortBy", "name");
      when(context.getQueryParams()).thenReturn(queryParams);

      Map<String, String> params = NeoCrudHelper.buildBaseParams(context, adTab, ENTITY_NAME);

      assertEquals("value1", params.get("customFilter"));
      assertEquals("name", params.get("sortBy"));
    }

    @Test
    @DisplayName("should strip _neoWhere from query params to prevent HQL injection")
    void shouldStripNeoWhereParam() {
      when(context.getRecordId()).thenReturn(null);
      Map<String, String> queryParams = new HashMap<>();
      queryParams.put(NeoCrudHelper.NEO_WHERE_PARAM, "malicious HQL");
      queryParams.put("validParam", "ok");
      when(context.getQueryParams()).thenReturn(queryParams);

      Map<String, String> params = NeoCrudHelper.buildBaseParams(context, adTab, ENTITY_NAME);

      assertFalse(params.containsKey(NeoCrudHelper.NEO_WHERE_PARAM));
      assertEquals("ok", params.get("validParam"));
    }

    @Test
    @DisplayName("should handle null query params gracefully")
    void shouldHandleNullQueryParams() {
      when(context.getRecordId()).thenReturn(null);
      when(context.getQueryParams()).thenReturn(null);

      Map<String, String> params = NeoCrudHelper.buildBaseParams(context, adTab, ENTITY_NAME);

      assertNotNull(params);
      assertEquals(4, params.size());
    }
  }

  @Nested
  @DisplayName("buildWhereClause")
  class BuildWhereClause {

    @Test
    @DisplayName("should add tab HQL where clause when present")
    void shouldAddTabHqlWhereClause() {
      Map<String, String> params = new HashMap<>();
      when(adTab.getHqlwhereclause()).thenReturn("e.active = true");
      when(context.getQueryParams()).thenReturn(null);

      NeoCrudHelper.buildWhereClause(params, adTab, context);

      assertTrue(params.containsKey(JsonConstants.WHERE_AND_FILTER_CLAUSE));
      assertEquals("(e.active = true)", params.get(JsonConstants.WHERE_AND_FILTER_CLAUSE));
      assertEquals("true", params.get(JsonConstants.USE_ALIAS));
    }

    @Test
    @DisplayName("should not set where clause when tab has no HQL and no parent")
    void shouldNotSetWhereClauseWhenEmpty() {
      Map<String, String> params = new HashMap<>();
      when(adTab.getHqlwhereclause()).thenReturn(null);
      when(context.getQueryParams()).thenReturn(null);

      NeoCrudHelper.buildWhereClause(params, adTab, context);

      assertFalse(params.containsKey(JsonConstants.WHERE_AND_FILTER_CLAUSE));
    }

    @Test
    @DisplayName("should replace session variables in tab HQL with parentId")
    void shouldReplaceSessionVariablesWithParentId() {
      Map<String, String> params = new HashMap<>();
      Map<String, String> queryParams = new HashMap<>();
      queryParams.put("parentId", "parent-abc");
      when(context.getQueryParams()).thenReturn(queryParams);
      when(adTab.getHqlwhereclause()).thenReturn("e.order.id = @Order.id@");
      when(adTab.getTabLevel()).thenReturn(null);

      NeoCrudHelper.buildWhereClause(params, adTab, context);

      String where = params.get(JsonConstants.WHERE_AND_FILTER_CLAUSE);
      assertNotNull(where);
      assertTrue(where.contains("'parent-abc'"));
      assertFalse(where.contains("@"));
    }

    @Test
    @DisplayName("should escape single quotes in parentId to prevent SQL injection")
    void shouldEscapeSingleQuotesInParentId() {
      Map<String, String> params = new HashMap<>();
      Map<String, String> queryParams = new HashMap<>();
      queryParams.put("parentId", "val'ue");
      when(context.getQueryParams()).thenReturn(queryParams);
      when(adTab.getHqlwhereclause()).thenReturn("e.field = @Some.var@");
      when(adTab.getTabLevel()).thenReturn(null);

      NeoCrudHelper.buildWhereClause(params, adTab, context);

      String where = params.get(JsonConstants.WHERE_AND_FILTER_CLAUSE);
      assertTrue(where.contains("val''ue"));
    }

    @Test
    @DisplayName("should consume _neoWhere from params and append to where clause")
    void shouldConsumeNeoWhereParam() {
      Map<String, String> params = new HashMap<>();
      params.put(NeoCrudHelper.NEO_WHERE_PARAM, "e.custom = 'test'");
      when(adTab.getHqlwhereclause()).thenReturn(null);
      when(context.getQueryParams()).thenReturn(null);

      NeoCrudHelper.buildWhereClause(params, adTab, context);

      assertFalse(params.containsKey(NeoCrudHelper.NEO_WHERE_PARAM));
      assertEquals("(e.custom = 'test')", params.get(JsonConstants.WHERE_AND_FILTER_CLAUSE));
    }

    @Test
    @DisplayName("should combine tab HQL and _neoWhere with AND")
    void shouldCombineTabHqlAndNeoWhere() {
      Map<String, String> params = new HashMap<>();
      params.put(NeoCrudHelper.NEO_WHERE_PARAM, "e.status = 'active'");
      when(adTab.getHqlwhereclause()).thenReturn("e.org.id = '1'");
      when(context.getQueryParams()).thenReturn(null);

      NeoCrudHelper.buildWhereClause(params, adTab, context);

      String where = params.get(JsonConstants.WHERE_AND_FILTER_CLAUSE);
      assertTrue(where.contains("(e.org.id = '1')"));
      assertTrue(where.contains(" and "));
      assertTrue(where.contains("(e.status = 'active')"));
    }

    @Test
    @DisplayName("should add parent filter for child tabs with parentId")
    void shouldAddParentFilterForChildTabs() {
      Map<String, String> params = new HashMap<>();
      Map<String, String> queryParams = new HashMap<>();
      queryParams.put("parentId", "parent-123");
      when(context.getQueryParams()).thenReturn(queryParams);
      when(adTab.getHqlwhereclause()).thenReturn(null);
      when(adTab.getTabLevel()).thenReturn(1L);

      try (MockedStatic<NeoTypeCoercionHelper> coercionMock = mockStatic(
          NeoTypeCoercionHelper.class)) {
        NeoTypeCoercionHelper.ParentFilter parentFilter =
            mock(NeoTypeCoercionHelper.ParentFilter.class);
        when(parentFilter.resolveForStringApi()).thenReturn("e.order.id = 'parent-123'");
        coercionMock.when(() -> NeoTypeCoercionHelper.buildParentWhereClause(adTab, "parent-123"))
            .thenReturn(parentFilter);

        NeoCrudHelper.buildWhereClause(params, adTab, context);

        String where = params.get(JsonConstants.WHERE_AND_FILTER_CLAUSE);
        assertNotNull(where);
        assertTrue(where.contains("e.order.id = 'parent-123'"));
      }
    }
  }

  @Nested
  @DisplayName("applyPaginationDefaults")
  class ApplyPaginationDefaults {

    @Test
    @DisplayName("should set default startrow and endrow when not present")
    void shouldSetDefaultsWhenNotPresent() {
      Map<String, String> params = new HashMap<>();

      NeoCrudHelper.applyPaginationDefaults(params);

      assertEquals("0", params.get(JsonConstants.STARTROW_PARAMETER));
      assertEquals("100", params.get(JsonConstants.ENDROW_PARAMETER));
    }

    @Test
    @DisplayName("should not override existing startrow")
    void shouldNotOverrideExistingStartRow() {
      Map<String, String> params = new HashMap<>();
      params.put(JsonConstants.STARTROW_PARAMETER, "50");

      NeoCrudHelper.applyPaginationDefaults(params);

      assertEquals("50", params.get(JsonConstants.STARTROW_PARAMETER));
      assertEquals("100", params.get(JsonConstants.ENDROW_PARAMETER));
    }

    @Test
    @DisplayName("should not override existing endrow")
    void shouldNotOverrideExistingEndRow() {
      Map<String, String> params = new HashMap<>();
      params.put(JsonConstants.ENDROW_PARAMETER, "200");

      NeoCrudHelper.applyPaginationDefaults(params);

      assertEquals("0", params.get(JsonConstants.STARTROW_PARAMETER));
      assertEquals("200", params.get(JsonConstants.ENDROW_PARAMETER));
    }

    @Test
    @DisplayName("should preserve both when already set")
    void shouldPreserveBothWhenAlreadySet() {
      Map<String, String> params = new HashMap<>();
      params.put(JsonConstants.STARTROW_PARAMETER, "10");
      params.put(JsonConstants.ENDROW_PARAMETER, "20");

      NeoCrudHelper.applyPaginationDefaults(params);

      assertEquals("10", params.get(JsonConstants.STARTROW_PARAMETER));
      assertEquals("20", params.get(JsonConstants.ENDROW_PARAMETER));
    }
  }

  // Note: dispatchCrudMethod, handlePost, and handlePutOrPatch tests are omitted because
  // DefaultJsonDataService cannot be mocked in unit tests (its static initializer requires
  // a Weld/CDI servlet context). These methods are covered by integration tests instead.

  @Nested
  @DisplayName("resolveAndMapParentId")
  class ResolveAndMapParentId {

    @Test
    @DisplayName("should throw OBException when adTab is null")
    void shouldThrowWhenAdTabNull() {
      assertThrows(OBException.class,
          () -> NeoCrudHelper.resolveAndMapParentId(new JSONObject(), null));
    }

    @Test
    @DisplayName("should return null when requestBody is null")
    void shouldReturnNullWhenRequestBodyNull() throws Exception {
      assertNull(NeoCrudHelper.resolveAndMapParentId(null, adTab));
    }

    @Test
    @DisplayName("should return null when requestBody has no parentId")
    void shouldReturnNullWhenNoParentId() throws Exception {
      JSONObject body = new JSONObject();
      body.put("name", "Test");

      assertNull(NeoCrudHelper.resolveAndMapParentId(body, adTab));
    }

    @Test
    @DisplayName("should extract and remove parentId from body")
    void shouldExtractAndRemoveParentId() throws Exception {
      JSONObject body = new JSONObject();
      body.put("parentId", "parent-abc");
      body.put("name", "Test");
      when(adTab.getTabLevel()).thenReturn(0L);

      String result = NeoCrudHelper.resolveAndMapParentId(body, adTab);

      assertEquals("parent-abc", result);
      assertFalse(body.has("parentId"));
      assertTrue(body.has("name"));
    }

    @Test
    @DisplayName("should map parentId to FK column for child tabs")
    void shouldMapParentIdToFkColumnForChildTabs() throws Exception {
      JSONObject body = new JSONObject();
      body.put("parentId", "parent-abc");
      when(adTab.getTabLevel()).thenReturn(1L);

      Table table = mock(Table.class);
      when(adTab.getTable()).thenReturn(table);
      when(table.getDBTableName()).thenReturn("C_OrderLine");

      Entity entity = mock(Entity.class);
      Column col = mock(Column.class);
      when(col.isLinkToParentColumn()).thenReturn(true);
      when(col.isActive()).thenReturn(true);
      when(col.getDBColumnName()).thenReturn("C_Order_ID");

      Property prop = mock(Property.class);
      when(prop.getColumnName()).thenReturn("C_Order_ID");
      when(prop.getName()).thenReturn("salesOrder");

      List<Column> columns = new ArrayList<>();
      columns.add(col);
      when(table.getADColumnList()).thenReturn(columns);

      List<Property> properties = new ArrayList<>();
      properties.add(prop);
      when(entity.getProperties()).thenReturn(properties);

      try (MockedStatic<ModelProvider> modelProviderMock = mockStatic(ModelProvider.class)) {
        ModelProvider mp = mock(ModelProvider.class);
        modelProviderMock.when(ModelProvider::getInstance).thenReturn(mp);
        when(mp.getEntityByTableName("C_OrderLine")).thenReturn(entity);

        String result = NeoCrudHelper.resolveAndMapParentId(body, adTab);

        assertEquals("parent-abc", result);
        assertFalse(body.has("parentId"));
        assertEquals("parent-abc", body.getString("salesOrder"));
      }
    }

    @Test
    @DisplayName("should not map FK when tab level is 0")
    void shouldNotMapFkWhenTabLevelZero() throws Exception {
      JSONObject body = new JSONObject();
      body.put("parentId", "parent-abc");
      when(adTab.getTabLevel()).thenReturn(0L);

      String result = NeoCrudHelper.resolveAndMapParentId(body, adTab);

      assertEquals("parent-abc", result);
      assertFalse(body.has("parentId"));
      assertFalse(body.has("salesOrder"));
    }
  }

  @Nested
  @DisplayName("mapParentIdToFkColumn")
  class MapParentIdToFkColumn {

    @Test
    @DisplayName("should set parentId on matching FK property")
    void shouldSetParentIdOnMatchingProperty() throws Exception {
      JSONObject body = new JSONObject();
      Entity entity = mock(Entity.class);

      Column col = mock(Column.class);
      when(col.isLinkToParentColumn()).thenReturn(true);
      when(col.isActive()).thenReturn(true);
      when(col.getDBColumnName()).thenReturn("C_Order_ID");

      Property prop = mock(Property.class);
      when(prop.getColumnName()).thenReturn("C_Order_ID");
      when(prop.getName()).thenReturn("salesOrder");

      Table table = mock(Table.class);
      when(adTab.getTable()).thenReturn(table);
      when(table.getADColumnList()).thenReturn(Collections.singletonList(col));
      when(entity.getProperties()).thenReturn(Collections.singletonList(prop));

      NeoCrudHelper.mapParentIdToFkColumn(body, adTab, entity, "parent-123");

      assertEquals("parent-123", body.getString("salesOrder"));
    }

    @Test
    @DisplayName("should skip inactive columns")
    void shouldSkipInactiveColumns() throws Exception {
      JSONObject body = new JSONObject();
      Entity entity = mock(Entity.class);

      Column col = mock(Column.class);
      when(col.isLinkToParentColumn()).thenReturn(true);
      when(col.isActive()).thenReturn(false);

      Table table = mock(Table.class);
      when(adTab.getTable()).thenReturn(table);
      when(table.getADColumnList()).thenReturn(Collections.singletonList(col));

      NeoCrudHelper.mapParentIdToFkColumn(body, adTab, entity, "parent-123");

      assertEquals(0, body.length());
    }

    @Test
    @DisplayName("should skip non-link-to-parent columns")
    void shouldSkipNonLinkToParentColumns() throws Exception {
      JSONObject body = new JSONObject();
      Entity entity = mock(Entity.class);

      Column col = mock(Column.class);
      when(col.isLinkToParentColumn()).thenReturn(false);

      Table table = mock(Table.class);
      when(adTab.getTable()).thenReturn(table);
      when(table.getADColumnList()).thenReturn(Collections.singletonList(col));

      NeoCrudHelper.mapParentIdToFkColumn(body, adTab, entity, "parent-123");

      assertEquals(0, body.length());
    }
  }

  @Nested
  @DisplayName("executePostCalloutCascade")
  class ExecutePostCalloutCascade {

    @Test
    @DisplayName("should skip cascade when adTab is null")
    void shouldSkipWhenAdTabNull() {
      JSONObject body = new JSONObject();
      NeoCrudHelper.executePostCalloutCascade(body, null, context, null, null);
      // No exception means success - method returned early
    }

    @Test
    @DisplayName("should skip cascade when tab level is null")
    void shouldSkipWhenTabLevelNull() {
      JSONObject body = new JSONObject();
      when(adTab.getTabLevel()).thenReturn(null);
      NeoCrudHelper.executePostCalloutCascade(body, adTab, context, null, null);
    }

    @Test
    @DisplayName("should skip cascade for line tabs (tabLevel > 0)")
    void shouldSkipForLineTabs() {
      JSONObject body = new JSONObject();
      when(adTab.getTabLevel()).thenReturn(1L);
      NeoCrudHelper.executePostCalloutCascade(body, adTab, context, null, null);
    }

    @Test
    @DisplayName("should execute cascade for header tabs (tabLevel == 0)")
    void shouldExecuteForHeaderTabs() throws Exception {
      JSONObject body = new JSONObject();
      body.put("name", "Test");
      when(adTab.getTabLevel()).thenReturn(0L);

      try (MockedStatic<NeoDefaultsCascadeHelper> cascadeMock = mockStatic(
              NeoDefaultsCascadeHelper.class);
           MockedStatic<DocTypeResolver> docTypeMock = mockStatic(DocTypeResolver.class);
           MockedStatic<NeoDefaultsService> defaultsMock = mockStatic(NeoDefaultsService.class)) {

        Set<String> protectedFields = Collections.singleton("name");
        NeoCrudHelper.executePostCalloutCascade(
            body, adTab, context, "parent-1", protectedFields);

        cascadeMock.verify(() -> NeoDefaultsCascadeHelper.executeCalloutCascade(
            eq(context), eq(adTab), eq(body), any(Set.class), eq(protectedFields)));
        docTypeMock.verify(() -> DocTypeResolver.reapplyDocTypeFromTabFilter(
            body, adTab, context));
        cascadeMock.verify(() -> NeoDefaultsCascadeHelper.removeEmptyFkValues(body, adTab));
        defaultsMock.verify(() -> NeoDefaultsService.injectMandatoryDefaults(
            body, adTab, context, "parent-1"));
      }
    }

    @Test
    @DisplayName("should detect sequence preview fields and pass them as protected")
    void shouldDetectSequencePreviewFields() throws Exception {
      JSONObject body = new JSONObject();
      body.put("documentNo", "<1000371>");
      body.put("name", "Regular value");
      when(adTab.getTabLevel()).thenReturn(0L);

      try (MockedStatic<NeoDefaultsCascadeHelper> cascadeMock = mockStatic(
              NeoDefaultsCascadeHelper.class);
           MockedStatic<DocTypeResolver> docTypeMock = mockStatic(DocTypeResolver.class);
           MockedStatic<NeoDefaultsService> defaultsMock = mockStatic(NeoDefaultsService.class)) {

        NeoCrudHelper.executePostCalloutCascade(
            body, adTab, context, null, Collections.emptySet());

        cascadeMock.verify(() -> NeoDefaultsCascadeHelper.executeCalloutCascade(
            eq(context), eq(adTab), eq(body),
            org.mockito.ArgumentMatchers.argThat(
                (Set<String> seqFields) -> seqFields.contains("documentNo")
                    && !seqFields.contains("name")),
            any(Set.class)));
      }
    }

    @Test
    @DisplayName("should use empty set when protectedFields is null")
    void shouldUseEmptySetWhenProtectedFieldsNull() throws Exception {
      JSONObject body = new JSONObject();
      when(adTab.getTabLevel()).thenReturn(0L);

      try (MockedStatic<NeoDefaultsCascadeHelper> cascadeMock = mockStatic(
              NeoDefaultsCascadeHelper.class);
           MockedStatic<DocTypeResolver> docTypeMock = mockStatic(DocTypeResolver.class);
           MockedStatic<NeoDefaultsService> defaultsMock = mockStatic(NeoDefaultsService.class)) {

        NeoCrudHelper.executePostCalloutCascade(
            body, adTab, context, null, null);

        cascadeMock.verify(() -> NeoDefaultsCascadeHelper.executeCalloutCascade(
            eq(context), eq(adTab), eq(body), any(Set.class),
            eq(Collections.emptySet())));
      }
    }
  }

  @Nested
  @DisplayName("buildCrudResponse")
  class BuildCrudResponse {

    private NeoFieldFilter fieldFilter;

    @BeforeEach
    void setUp() {
      fieldFilter = mock(NeoFieldFilter.class);
    }

    @Test
    @DisplayName("should return ok response for successful result")
    void shouldReturnOkForSuccessfulResult() throws Exception {
      JSONObject inner = new JSONObject();
      inner.put("status", 0);
      JSONObject responseJson = new JSONObject();
      responseJson.put("response", inner);
      when(context.getHttpMethod()).thenReturn("POST");
      when(context.getSfEntity()).thenReturn(null);

      try (MockedStatic<OBMessageUtils> msgMock = mockStatic(OBMessageUtils.class)) {
        NeoResponse response = NeoCrudHelper.buildCrudResponse(
            responseJson.toString(), context, fieldFilter);

        assertNotNull(response);
        assertEquals(200, response.getHttpStatus());
      }
    }

    @Test
    @DisplayName("should return error for failure status")
    void shouldReturnErrorForFailureStatus() throws Exception {
      JSONObject error = new JSONObject();
      error.put("message", "Something went wrong");
      JSONObject inner = new JSONObject();
      inner.put("status", -1);
      inner.put("error", error);
      JSONObject responseJson = new JSONObject();
      responseJson.put("response", inner);
      when(context.getHttpMethod()).thenReturn("POST");

      try (MockedStatic<OBMessageUtils> msgMock = mockStatic(OBMessageUtils.class)) {
        msgMock.when(() -> OBMessageUtils.messageBD("Something went wrong"))
            .thenReturn("Something went wrong");

        NeoResponse response = NeoCrudHelper.buildCrudResponse(
            responseJson.toString(), context, fieldFilter);

        assertNotNull(response);
        assertEquals(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, response.getHttpStatus());
      }
    }

    @Test
    @DisplayName("should return bad request for validation error status")
    void shouldReturnBadRequestForValidationError() throws Exception {
      JSONObject inner = new JSONObject();
      inner.put("status", -4);
      JSONObject responseJson = new JSONObject();
      responseJson.put("response", inner);
      when(context.getHttpMethod()).thenReturn("POST");

      NeoResponse response = NeoCrudHelper.buildCrudResponse(
          responseJson.toString(), context, fieldFilter);

      assertNotNull(response);
      assertEquals(HttpServletResponse.SC_BAD_REQUEST, response.getHttpStatus());
    }

    @Test
    @DisplayName("should enrich list identifiers for GET with sfEntity")
    void shouldEnrichListIdentifiersForGet() throws Exception {
      JSONObject inner = new JSONObject();
      inner.put("status", 0);
      JSONObject responseJson = new JSONObject();
      responseJson.put("response", inner);
      when(context.getHttpMethod()).thenReturn("GET");
      when(context.getSfEntity()).thenReturn(null);

      try (MockedStatic<NeoListIdentifierHelper> listIdMock = mockStatic(
          NeoListIdentifierHelper.class)) {
        NeoResponse response = NeoCrudHelper.buildCrudResponse(
            responseJson.toString(), context, fieldFilter);

        assertNotNull(response);
        assertEquals(200, response.getHttpStatus());
      }
    }
  }

  @Nested
  @DisplayName("checkForServiceErrors")
  class CheckForServiceErrors {

    @Test
    @DisplayName("should return null when no response object present")
    void shouldReturnNullWhenNoResponseObject() throws Exception {
      JSONObject json = new JSONObject();
      json.put("data", "something");

      assertNull(NeoCrudHelper.checkForServiceErrors(json));
    }

    @Test
    @DisplayName("should return null for successful status")
    void shouldReturnNullForSuccessStatus() throws Exception {
      JSONObject inner = new JSONObject();
      inner.put("status", 0);
      JSONObject json = new JSONObject();
      json.put("response", inner);

      assertNull(NeoCrudHelper.checkForServiceErrors(json));
    }

    @Test
    @DisplayName("should return error response for failure status with error message")
    void shouldReturnErrorForFailureWithMessage() throws Exception {
      JSONObject error = new JSONObject();
      error.put("message", "Custom error");
      JSONObject inner = new JSONObject();
      inner.put("status", -1);
      inner.put("error", error);
      JSONObject json = new JSONObject();
      json.put("response", inner);

      try (MockedStatic<OBMessageUtils> msgMock = mockStatic(OBMessageUtils.class)) {
        msgMock.when(() -> OBMessageUtils.messageBD("Custom error"))
            .thenReturn("Custom error translated");

        NeoResponse response = NeoCrudHelper.checkForServiceErrors(json);

        assertNotNull(response);
        assertEquals(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, response.getHttpStatus());
      }
    }

    @Test
    @DisplayName("should use default message when no error object present")
    void shouldUseDefaultMessageWhenNoErrorObject() throws Exception {
      JSONObject inner = new JSONObject();
      inner.put("status", -1);
      JSONObject json = new JSONObject();
      json.put("response", inner);

      try (MockedStatic<OBMessageUtils> msgMock = mockStatic(OBMessageUtils.class)) {
        msgMock.when(() -> OBMessageUtils.messageBD("Write operation failed"))
            .thenReturn("Write operation failed");

        NeoResponse response = NeoCrudHelper.checkForServiceErrors(json);

        assertNotNull(response);
        assertEquals(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, response.getHttpStatus());
      }
    }

    @Test
    @DisplayName("should return bad request for validation error status")
    void shouldReturnBadRequestForValidationError() throws Exception {
      JSONObject inner = new JSONObject();
      inner.put("status", -4);
      JSONObject json = new JSONObject();
      json.put("response", inner);

      NeoResponse response = NeoCrudHelper.checkForServiceErrors(json);

      assertNotNull(response);
      assertEquals(HttpServletResponse.SC_BAD_REQUEST, response.getHttpStatus());
    }
  }
}
