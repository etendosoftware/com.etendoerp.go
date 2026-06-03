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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import javax.servlet.ReadListener;
import javax.servlet.ServletInputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.codehaus.jettison.json.JSONObject;
import org.hibernate.criterion.Criterion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.openbravo.base.expression.OBScriptEngine;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.ModelProvider;
import org.openbravo.base.model.Property;
import org.openbravo.client.application.DynamicExpressionParser;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.datamodel.Column;
import org.openbravo.model.ad.datamodel.Table;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.ad.system.Language;
import org.openbravo.model.ad.ui.Field;
import org.openbravo.model.ad.ui.Tab;
import org.openbravo.model.common.enterprise.Organization;

import com.etendoerp.go.schemaforge.data.SFEntity;
import com.etendoerp.go.schemaforge.data.SFSpec;

/**
 * Unit tests for {@link NeoDisplayLogicHandler}.
 *
 * <p>Covers: handleEvaluateDisplay (entity not found, no linked tab, success,
 * exception), parseFieldValuesFromRequest (empty body, valid body, invalid JSON),
 * buildEvalContext (field values, session vars), getPropertyName (DAL resolution,
 * fallback to dbColumnName, null column), and evaluateExpression (true, false,
 * exception fail-open).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class NeoDisplayLogicHandlerTest {

  private NeoDisplayLogicHandler handler;

  @Mock
  private OBDal obDal;
  @Mock
  private OBContext obContext;
  @Mock
  private Client client;
  @Mock
  private Organization organization;
  @Mock
  private org.openbravo.model.ad.access.Role role;
  @Mock
  private org.openbravo.model.ad.access.User user;
  @Mock
  private Language language;

  private MockedStatic<OBDal> obDalMock;
  private MockedStatic<OBContext> obContextMock;

  @BeforeEach
  void setUp() {
    handler = new NeoDisplayLogicHandler();
    obDalMock = mockStatic(OBDal.class);
    obContextMock = mockStatic(OBContext.class);

    obDalMock.when(OBDal::getInstance).thenReturn(obDal);
    obContextMock.when(OBContext::getOBContext).thenReturn(obContext);

    when(obContext.getCurrentClient()).thenReturn(client);
    when(obContext.getCurrentOrganization()).thenReturn(organization);
    when(obContext.getRole()).thenReturn(role);
    when(obContext.getUser()).thenReturn(user);
    when(obContext.getLanguage()).thenReturn(language);

    when(client.getId()).thenReturn("test-client-id");
    when(organization.getId()).thenReturn("test-org-id");
    when(role.getId()).thenReturn("test-role-id");
    when(user.getId()).thenReturn("test-user-id");
    when(language.getLanguage()).thenReturn("en_US");
  }

  @AfterEach
  void tearDown() {
    obDalMock.close();
    obContextMock.close();
  }

  // ── Helpers ────────────────────────────────────────────────────────────────

  private SFSpec createMockSpec() {
    SFSpec spec = mock(SFSpec.class);
    when(spec.getId()).thenReturn("test-spec-id");
    return spec;
  }

  private NeoServlet.NeoPathInfo createPathInfo(String entityName) {
    return new NeoServlet.NeoPathInfo("testSpec", entityName, null);
  }

  private HttpServletRequest createRequestWithBody(String body) throws Exception {
    HttpServletRequest request = mock(HttpServletRequest.class);
    byte[] bytes = body != null ? body.getBytes(StandardCharsets.UTF_8) : new byte[0];
    ByteArrayInputStream bais = new ByteArrayInputStream(bytes);
    ServletInputStream sis = new ServletInputStream() {
      @Override
      public int read() {
        return bais.read();
      }

      @Override
      public boolean isFinished() {
        return bais.available() == 0;
      }

      @Override
      public boolean isReady() {
        return true;
      }

      @Override
      public void setReadListener(ReadListener readListener) {
        // no-op
      }
    };
    when(request.getInputStream()).thenReturn(sis);
    return request;
  }

  @SuppressWarnings({ "rawtypes", "unchecked" })
  private OBCriteria<SFEntity> mockCriteriaReturning(List<SFEntity> results) {
    OBCriteria criteria = mock(OBCriteria.class);
    when(obDal.createCriteria(SFEntity.class)).thenReturn(criteria);
    when(criteria.add(any(Criterion.class))).thenReturn(criteria);
    when(criteria.setMaxResults(1)).thenReturn(criteria);
    when(criteria.list()).thenReturn(results);
    return criteria;
  }

  // ── handleEvaluateDisplay ──────────────────────────────────────────────────

  @Nested
  @DisplayName("handleEvaluateDisplay")
  class HandleEvaluateDisplay {

    /**
     * Verifies that a 404 response is returned when the entity is not found
     * in the SchemaForge spec.
     */
    @Test
    void entityNotFoundReturns404() throws Exception {
      mockCriteriaReturning(Collections.emptyList());
      HttpServletRequest request = createRequestWithBody("{}");

      NeoResponse response = handler.handleEvaluateDisplay(
          createMockSpec(), createPathInfo("nonExistent"), request);

      assertEquals(HttpServletResponse.SC_NOT_FOUND, response.getHttpStatus());
      assertTrue(response.getBody().getJSONObject("error").getString("message")
          .contains("nonExistent"));
    }

    /**
     * Verifies that a 500 response is returned when the entity exists but
     * has no linked AD_Tab.
     */
    @Test
    void noLinkedTabReturns500() throws Exception {
      SFEntity sfEntity = mock(SFEntity.class);
      when(sfEntity.getADTab()).thenReturn(null);
      mockCriteriaReturning(Collections.singletonList(sfEntity));
      HttpServletRequest request = createRequestWithBody("{}");

      NeoResponse response = handler.handleEvaluateDisplay(
          createMockSpec(), createPathInfo("testEntity"), request);

      assertEquals(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, response.getHttpStatus());
      assertTrue(response.getBody().getJSONObject("error").getString("message")
          .contains("no linked AD_Tab"));
    }

    /**
     * Verifies that a successful evaluation returns HTTP 200 with visibility and
     * readOnly maps containing the evaluated results for active fields.
     */
    @Test
    void successfulEvaluationReturns200WithVisibilityAndReadOnly() throws Exception {
      // Set up entity with tab and fields
      SFEntity sfEntity = mock(SFEntity.class);
      Tab tab = mock(Tab.class);
      when(sfEntity.getADTab()).thenReturn(tab);
      mockCriteriaReturning(Collections.singletonList(sfEntity));

      // Set up a field with display logic and readOnly logic
      Field field = mock(Field.class);
      when(field.isActive()).thenReturn(true);
      when(field.getName()).thenReturn("testField");
      when(field.getDisplayLogic()).thenReturn("@DocStatus@='CO'");

      Column column = mock(Column.class);
      when(column.getReadOnlyLogic()).thenReturn("@Processed@='Y'");
      when(column.getDBColumnName()).thenReturn("DOCSTATUS");
      Table table = mock(Table.class);
      when(table.getId()).thenReturn("test-table-id");
      when(column.getTable()).thenReturn(table);
      when(field.getColumn()).thenReturn(column);

      List<Field> fields = new ArrayList<>();
      fields.add(field);
      when(tab.getADFieldList()).thenReturn(fields);

      // Mock ModelProvider for getPropertyName
      ModelProvider modelProvider = mock(ModelProvider.class);
      Entity dalEntity = mock(Entity.class);
      Property property = mock(Property.class);
      when(property.getName()).thenReturn("documentStatus");
      when(dalEntity.getPropertyByColumnName("DOCSTATUS")).thenReturn(property);
      when(modelProvider.getEntityByTableId("test-table-id")).thenReturn(dalEntity);

      // Mock OBScriptEngine
      OBScriptEngine scriptEngine = mock(OBScriptEngine.class);
      when(scriptEngine.eval(anyString(), any(Map.class))).thenReturn(Boolean.TRUE);

      HttpServletRequest request = createRequestWithBody(
          "{\"fieldValues\":{\"documentStatus\":\"CO\"}}");

      try (MockedStatic<ModelProvider> modelProviderMock = mockStatic(ModelProvider.class);
           MockedStatic<OBScriptEngine> scriptEngineMock = mockStatic(OBScriptEngine.class);
           MockedStatic<DynamicExpressionParser> depMock = mockStatic(
               DynamicExpressionParser.class);
           MockedConstruction<DynamicExpressionParser> depConstruction = mockConstruction(
               DynamicExpressionParser.class,
               (mock, context) -> when(mock.getJSExpression()).thenReturn("true"))) {

        modelProviderMock.when(ModelProvider::getInstance).thenReturn(modelProvider);
        scriptEngineMock.when(OBScriptEngine::getInstance).thenReturn(scriptEngine);
        depMock.when(() -> DynamicExpressionParser
            .replaceSystemPreferencesInDisplayLogic(anyString())).thenAnswer(
            invocation -> invocation.getArgument(0));

        NeoResponse response = handler.handleEvaluateDisplay(
            createMockSpec(), createPathInfo("testEntity"), request);

        assertEquals(HttpServletResponse.SC_OK, response.getHttpStatus());
        JSONObject body = response.getBody();
        assertNotNull(body);
        assertTrue(body.has("visibility"));
        assertTrue(body.has("readOnly"));
        assertTrue(body.getJSONObject("visibility").getBoolean("documentStatus"));
        assertTrue(body.getJSONObject("readOnly").getBoolean("documentStatus"));
      }
    }

    /**
     * Verifies that when an unexpected exception is thrown during evaluation,
     * the handler returns HTTP 500 with a descriptive error message.
     */
    @Test
    void unexpectedExceptionReturns500() throws Exception {
      SFEntity sfEntity = mock(SFEntity.class);
      Tab tab = mock(Tab.class);
      when(sfEntity.getADTab()).thenReturn(tab);
      when(tab.getADFieldList()).thenThrow(new RuntimeException("DB unavailable"));
      mockCriteriaReturning(Collections.singletonList(sfEntity));

      HttpServletRequest request = createRequestWithBody("{\"fieldValues\":{}}");

      NeoResponse response = handler.handleEvaluateDisplay(
          createMockSpec(), createPathInfo("testEntity"), request);

      assertEquals(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, response.getHttpStatus());
      assertTrue(response.getBody().getJSONObject("error").getString("message")
          .contains("Error evaluating display logic"));
    }

    /**
     * Verifies that an invalid (unparseable) request body returns HTTP 400.
     */
    @Test
    void invalidRequestBodyReturns400() throws Exception {
      SFEntity sfEntity = mock(SFEntity.class);
      Tab tab = mock(Tab.class);
      when(sfEntity.getADTab()).thenReturn(tab);
      mockCriteriaReturning(Collections.singletonList(sfEntity));

      HttpServletRequest request = createRequestWithBody("not valid json {{{");

      NeoResponse response = handler.handleEvaluateDisplay(
          createMockSpec(), createPathInfo("testEntity"), request);

      assertEquals(HttpServletResponse.SC_BAD_REQUEST, response.getHttpStatus());
    }

    /**
     * Verifies that inactive fields are skipped during evaluation.
     */
    @Test
    void inactiveFieldsAreSkipped() throws Exception {
      SFEntity sfEntity = mock(SFEntity.class);
      Tab tab = mock(Tab.class);
      when(sfEntity.getADTab()).thenReturn(tab);
      mockCriteriaReturning(Collections.singletonList(sfEntity));

      Field activeField = mock(Field.class);
      when(activeField.isActive()).thenReturn(true);
      when(activeField.getName()).thenReturn("activeField");
      when(activeField.getDisplayLogic()).thenReturn(null);
      when(activeField.getColumn()).thenReturn(null);

      Field inactiveField = mock(Field.class);
      when(inactiveField.isActive()).thenReturn(false);

      List<Field> fields = new ArrayList<>();
      fields.add(activeField);
      fields.add(inactiveField);
      when(tab.getADFieldList()).thenReturn(fields);

      HttpServletRequest request = createRequestWithBody("{\"fieldValues\":{}}");

      NeoResponse response = handler.handleEvaluateDisplay(
          createMockSpec(), createPathInfo("testEntity"), request);

      assertEquals(HttpServletResponse.SC_OK, response.getHttpStatus());
      JSONObject body = response.getBody();
      // Both visibility and readOnly should be empty since activeField has no
      // display/readOnly logic and inactiveField was skipped.
      assertEquals(0, body.getJSONObject("visibility").length());
      assertEquals(0, body.getJSONObject("readOnly").length());
    }
  }

  // ── parseFieldValuesFromRequest ────────────────────────────────────────────

  @Nested
  @DisplayName("parseFieldValuesFromRequest")
  class ParseFieldValues {

    /**
     * Verifies that an empty request body returns an empty JSONObject.
     */
    @Test
    void emptyBodyReturnsEmptyJson() throws Exception {
      HttpServletRequest request = createRequestWithBody("");

      // Use reflection to invoke the private method
      java.lang.reflect.Method method = NeoDisplayLogicHandler.class.getDeclaredMethod(
          "parseFieldValuesFromRequest", HttpServletRequest.class);
      method.setAccessible(true);

      JSONObject result = (JSONObject) method.invoke(handler, request);

      assertNotNull(result);
      assertEquals(0, result.length());
    }

    /**
     * Verifies that a valid body with a "fieldValues" key extracts the nested object.
     */
    @Test
    void validBodyWithFieldValuesExtractsValues() throws Exception {
      HttpServletRequest request = createRequestWithBody(
          "{\"fieldValues\":{\"name\":\"Test\",\"amount\":100}}");

      java.lang.reflect.Method method = NeoDisplayLogicHandler.class.getDeclaredMethod(
          "parseFieldValuesFromRequest", HttpServletRequest.class);
      method.setAccessible(true);

      JSONObject result = (JSONObject) method.invoke(handler, request);

      assertNotNull(result);
      assertEquals("Test", result.getString("name"));
      assertEquals(100, result.getInt("amount"));
    }

    /**
     * Verifies that a valid body without a "fieldValues" key returns an empty JSONObject.
     */
    @Test
    void validBodyWithoutFieldValuesReturnsEmpty() throws Exception {
      HttpServletRequest request = createRequestWithBody("{\"otherKey\":\"value\"}");

      java.lang.reflect.Method method = NeoDisplayLogicHandler.class.getDeclaredMethod(
          "parseFieldValuesFromRequest", HttpServletRequest.class);
      method.setAccessible(true);

      JSONObject result = (JSONObject) method.invoke(handler, request);

      assertNotNull(result);
      assertEquals(0, result.length());
    }

    /**
     * Verifies that invalid (unparseable) JSON returns null to signal a 400 error.
     */
    @Test
    void invalidJsonReturnsNull() throws Exception {
      HttpServletRequest request = createRequestWithBody("not json {{{");

      java.lang.reflect.Method method = NeoDisplayLogicHandler.class.getDeclaredMethod(
          "parseFieldValuesFromRequest", HttpServletRequest.class);
      method.setAccessible(true);

      JSONObject result = (JSONObject) method.invoke(handler, request);

      assertNull(result);
    }
  }

  // ── buildEvalContext ───────────────────────────────────────────────────────

  @Nested
  @DisplayName("buildEvalContext")
  class BuildEvalContext {

    /**
     * Verifies that field values are populated both at the top level and under
     * the "currentValues" key.
     */
    @Test
    @SuppressWarnings("unchecked")
    void fieldValuesPopulatedAtTopLevelAndUnderCurrentValues() throws Exception {
      JSONObject fieldValues = new JSONObject();
      fieldValues.put("documentStatus", "CO");
      fieldValues.put("processed", true);

      java.lang.reflect.Method method = NeoDisplayLogicHandler.class.getDeclaredMethod(
          "buildEvalContext", JSONObject.class);
      method.setAccessible(true);

      Map<String, Object> ctx = (Map<String, Object>) method.invoke(handler, fieldValues);

      // Top-level field values
      assertEquals("CO", ctx.get("documentStatus"));
      assertEquals(true, ctx.get("processed"));

      // currentValues nested map
      Map<String, Object> currentValues = (Map<String, Object>) ctx.get("currentValues");
      assertNotNull(currentValues);
      assertEquals("CO", currentValues.get("documentStatus"));
      assertEquals(true, currentValues.get("processed"));
    }

    /**
     * Verifies that OBContext session variables (AD_Org_ID, AD_Client_ID,
     * AD_Role_ID, AD_User_ID) are added to the evaluation context.
     */
    @Test
    @SuppressWarnings("unchecked")
    void sessionVarsAddedToContext() throws Exception {
      JSONObject fieldValues = new JSONObject();

      java.lang.reflect.Method method = NeoDisplayLogicHandler.class.getDeclaredMethod(
          "buildEvalContext", JSONObject.class);
      method.setAccessible(true);

      Map<String, Object> ctx = (Map<String, Object>) method.invoke(handler, fieldValues);

      assertEquals("test-org-id", ctx.get("AD_Org_ID"));
      assertEquals("test-client-id", ctx.get("AD_Client_ID"));
      assertEquals("test-role-id", ctx.get("AD_Role_ID"));
      assertEquals("test-user-id", ctx.get("AD_User_ID"));
    }

    /**
     * Verifies that JSONObject.NULL values are converted to Java null in the
     * evaluation context.
     */
    @Test
    @SuppressWarnings("unchecked")
    void jsonNullConvertedToJavaNull() throws Exception {
      JSONObject fieldValues = new JSONObject();
      fieldValues.put("nullableField", JSONObject.NULL);

      java.lang.reflect.Method method = NeoDisplayLogicHandler.class.getDeclaredMethod(
          "buildEvalContext", JSONObject.class);
      method.setAccessible(true);

      Map<String, Object> ctx = (Map<String, Object>) method.invoke(handler, fieldValues);

      assertTrue(ctx.containsKey("nullableField"));
      assertNull(ctx.get("nullableField"));

      Map<String, Object> currentValues = (Map<String, Object>) ctx.get("currentValues");
      assertTrue(currentValues.containsKey("nullableField"));
      assertNull(currentValues.get("nullableField"));
    }

    /**
     * Verifies that a "context" alias is added to the evaluation context
     * pointing to the context map itself.
     */
    @Test
    @SuppressWarnings("unchecked")
    void contextAliasPresentInEvalContext() throws Exception {
      JSONObject fieldValues = new JSONObject();

      java.lang.reflect.Method method = NeoDisplayLogicHandler.class.getDeclaredMethod(
          "buildEvalContext", JSONObject.class);
      method.setAccessible(true);

      Map<String, Object> ctx = (Map<String, Object>) method.invoke(handler, fieldValues);

      assertNotNull(ctx.get("context"));
      // The "context" key should reference the same map instance
      assertTrue(ctx.get("context") == ctx);
    }
  }

  // ── getPropertyName ────────────────────────────────────────────────────────

  @Nested
  @DisplayName("getPropertyName")
  class GetPropertyName {

    /**
     * Verifies that when DAL property resolution succeeds, the DAL property
     * name (e.g. "documentStatus") is returned.
     */
    @Test
    void withDalPropertyResolution() throws Exception {
      Field field = mock(Field.class);
      Column column = mock(Column.class);
      Table table = mock(Table.class);
      when(table.getId()).thenReturn("table-id");
      when(column.getTable()).thenReturn(table);
      when(column.getDBColumnName()).thenReturn("DOCSTATUS");
      when(field.getColumn()).thenReturn(column);

      ModelProvider modelProvider = mock(ModelProvider.class);
      Entity dalEntity = mock(Entity.class);
      Property property = mock(Property.class);
      when(property.getName()).thenReturn("documentStatus");
      when(dalEntity.getPropertyByColumnName("DOCSTATUS")).thenReturn(property);
      when(modelProvider.getEntityByTableId("table-id")).thenReturn(dalEntity);

      try (MockedStatic<ModelProvider> mpMock = mockStatic(ModelProvider.class)) {
        mpMock.when(ModelProvider::getInstance).thenReturn(modelProvider);

        java.lang.reflect.Method method = NeoDisplayLogicHandler.class.getDeclaredMethod(
            "getPropertyName", Field.class);
        method.setAccessible(true);

        String result = (String) method.invoke(handler, field);
        assertEquals("documentStatus", result);
      }
    }

    /**
     * Verifies that when the DAL entity is found but the property lookup returns
     * null, the raw dbColumnName is used as fallback.
     */
    @Test
    void fallbackToDbColumnNameWhenPropertyNotFound() throws Exception {
      Field field = mock(Field.class);
      Column column = mock(Column.class);
      Table table = mock(Table.class);
      when(table.getId()).thenReturn("table-id");
      when(column.getTable()).thenReturn(table);
      when(column.getDBColumnName()).thenReturn("CUSTOM_COL");
      when(field.getColumn()).thenReturn(column);

      ModelProvider modelProvider = mock(ModelProvider.class);
      Entity dalEntity = mock(Entity.class);
      when(dalEntity.getPropertyByColumnName("CUSTOM_COL")).thenReturn(null);
      when(modelProvider.getEntityByTableId("table-id")).thenReturn(dalEntity);

      try (MockedStatic<ModelProvider> mpMock = mockStatic(ModelProvider.class)) {
        mpMock.when(ModelProvider::getInstance).thenReturn(modelProvider);

        java.lang.reflect.Method method = NeoDisplayLogicHandler.class.getDeclaredMethod(
            "getPropertyName", Field.class);
        method.setAccessible(true);

        String result = (String) method.invoke(handler, field);
        assertEquals("CUSTOM_COL", result);
      }
    }

    /**
     * Verifies that when the DAL entity is not found for the table, the
     * raw dbColumnName is used as fallback.
     */
    @Test
    void fallbackToDbColumnNameWhenEntityNotFound() throws Exception {
      Field field = mock(Field.class);
      Column column = mock(Column.class);
      Table table = mock(Table.class);
      when(table.getId()).thenReturn("unknown-table-id");
      when(column.getTable()).thenReturn(table);
      when(column.getDBColumnName()).thenReturn("SOME_COL");
      when(field.getColumn()).thenReturn(column);

      ModelProvider modelProvider = mock(ModelProvider.class);
      when(modelProvider.getEntityByTableId("unknown-table-id")).thenReturn(null);

      try (MockedStatic<ModelProvider> mpMock = mockStatic(ModelProvider.class)) {
        mpMock.when(ModelProvider::getInstance).thenReturn(modelProvider);

        java.lang.reflect.Method method = NeoDisplayLogicHandler.class.getDeclaredMethod(
            "getPropertyName", Field.class);
        method.setAccessible(true);

        String result = (String) method.invoke(handler, field);
        assertEquals("SOME_COL", result);
      }
    }

    /**
     * Verifies that when the field has no column, the field name is used
     * as fallback.
     */
    @Test
    void nullColumnFallsBackToFieldName() throws Exception {
      Field field = mock(Field.class);
      when(field.getColumn()).thenReturn(null);
      when(field.getName()).thenReturn("My Custom Field");

      java.lang.reflect.Method method = NeoDisplayLogicHandler.class.getDeclaredMethod(
          "getPropertyName", Field.class);
      method.setAccessible(true);

      String result = (String) method.invoke(handler, field);
      assertEquals("My Custom Field", result);
    }
  }

  // ── evaluateExpression ─────────────────────────────────────────────────────

  @Nested
  @DisplayName("evaluateExpression")
  class EvaluateExpression {

    /**
     * Verifies that evaluateExpression returns true when the script engine
     * evaluates the expression to Boolean.TRUE.
     */
    @Test
    void successReturningTrue() throws Exception {
      Tab tab = mock(Tab.class);
      Field field = mock(Field.class);
      when(field.getName()).thenReturn("testField");

      OBScriptEngine scriptEngine = mock(OBScriptEngine.class);
      when(scriptEngine.eval(anyString(), any(Map.class))).thenReturn(Boolean.TRUE);

      try (MockedStatic<OBScriptEngine> seMock = mockStatic(OBScriptEngine.class);
           MockedStatic<DynamicExpressionParser> depMock = mockStatic(
               DynamicExpressionParser.class);
           MockedConstruction<DynamicExpressionParser> depConstruction = mockConstruction(
               DynamicExpressionParser.class,
               (mock, context) -> when(mock.getJSExpression()).thenReturn("true"))) {

        seMock.when(OBScriptEngine::getInstance).thenReturn(scriptEngine);
        depMock.when(() -> DynamicExpressionParser
            .replaceSystemPreferencesInDisplayLogic(anyString())).thenAnswer(
            invocation -> invocation.getArgument(0));

        java.lang.reflect.Method method = NeoDisplayLogicHandler.class.getDeclaredMethod(
            "evaluateExpression", String.class, Tab.class, Field.class, Map.class);
        method.setAccessible(true);

        boolean result = (boolean) method.invoke(
            handler, "@DocStatus@='CO'", tab, field, Collections.emptyMap());
        assertTrue(result);
      }
    }

    /**
     * Verifies that evaluateExpression returns false when the script engine
     * evaluates the expression to Boolean.FALSE.
     */
    @Test
    void successReturningFalse() throws Exception {
      Tab tab = mock(Tab.class);
      Field field = mock(Field.class);
      when(field.getName()).thenReturn("testField");

      OBScriptEngine scriptEngine = mock(OBScriptEngine.class);
      when(scriptEngine.eval(anyString(), any(Map.class))).thenReturn(Boolean.FALSE);

      try (MockedStatic<OBScriptEngine> seMock = mockStatic(OBScriptEngine.class);
           MockedStatic<DynamicExpressionParser> depMock = mockStatic(
               DynamicExpressionParser.class);
           MockedConstruction<DynamicExpressionParser> depConstruction = mockConstruction(
               DynamicExpressionParser.class,
               (mock, context) -> when(mock.getJSExpression()).thenReturn("false"))) {

        seMock.when(OBScriptEngine::getInstance).thenReturn(scriptEngine);
        depMock.when(() -> DynamicExpressionParser
            .replaceSystemPreferencesInDisplayLogic(anyString())).thenAnswer(
            invocation -> invocation.getArgument(0));

        java.lang.reflect.Method method = NeoDisplayLogicHandler.class.getDeclaredMethod(
            "evaluateExpression", String.class, Tab.class, Field.class, Map.class);
        method.setAccessible(true);

        boolean result = (boolean) method.invoke(
            handler, "@DocStatus@='CO'", tab, field, Collections.emptyMap());
        assertFalse(result);
      }
    }

    /**
     * Verifies that evaluateExpression returns true (fail-open) when an
     * exception occurs during evaluation.
     */
    @Test
    void exceptionReturnsTrue() throws Exception {
      Tab tab = mock(Tab.class);
      Field field = mock(Field.class);
      when(field.getName()).thenReturn("failingField");

      try (MockedStatic<DynamicExpressionParser> depMock = mockStatic(
               DynamicExpressionParser.class)) {

        depMock.when(() -> DynamicExpressionParser
            .replaceSystemPreferencesInDisplayLogic(anyString()))
            .thenThrow(new RuntimeException("Parse error"));

        java.lang.reflect.Method method = NeoDisplayLogicHandler.class.getDeclaredMethod(
            "evaluateExpression", String.class, Tab.class, Field.class, Map.class);
        method.setAccessible(true);

        boolean result = (boolean) method.invoke(
            handler, "invalid expression", tab, field, Collections.emptyMap());
        assertTrue(result, "On exception, evaluateExpression must return true (fail-open)");
      }
    }

    /**
     * Verifies that evaluateExpression returns true (fail-open) when the
     * script engine throws an exception during eval.
     */
    @Test
    void scriptEngineExceptionReturnsTrue() throws Exception {
      Tab tab = mock(Tab.class);
      Field field = mock(Field.class);
      when(field.getName()).thenReturn("badScriptField");

      OBScriptEngine scriptEngine = mock(OBScriptEngine.class);
      when(scriptEngine.eval(anyString(), any(Map.class)))
          .thenThrow(new javax.script.ScriptException("Syntax error"));

      try (MockedStatic<OBScriptEngine> seMock = mockStatic(OBScriptEngine.class);
           MockedStatic<DynamicExpressionParser> depMock = mockStatic(
               DynamicExpressionParser.class);
           MockedConstruction<DynamicExpressionParser> depConstruction = mockConstruction(
               DynamicExpressionParser.class,
               (mock, context) -> when(mock.getJSExpression()).thenReturn("bad script"))) {

        seMock.when(OBScriptEngine::getInstance).thenReturn(scriptEngine);
        depMock.when(() -> DynamicExpressionParser
            .replaceSystemPreferencesInDisplayLogic(anyString())).thenAnswer(
            invocation -> invocation.getArgument(0));

        java.lang.reflect.Method method = NeoDisplayLogicHandler.class.getDeclaredMethod(
            "evaluateExpression", String.class, Tab.class, Field.class, Map.class);
        method.setAccessible(true);

        boolean result = (boolean) method.invoke(
            handler, "bad expression", tab, field, Collections.emptyMap());
        assertTrue(result, "On ScriptException, evaluateExpression must return true (fail-open)");
      }
    }

    /**
     * Verifies that evaluateExpression handles a null field gracefully
     * (the field name logged would be "tab-level" in the catch block).
     */
    @Test
    void nullFieldDoesNotThrow() throws Exception {
      Tab tab = mock(Tab.class);

      try (MockedStatic<DynamicExpressionParser> depMock = mockStatic(
               DynamicExpressionParser.class)) {

        depMock.when(() -> DynamicExpressionParser
            .replaceSystemPreferencesInDisplayLogic(anyString()))
            .thenThrow(new RuntimeException("Parse error"));

        java.lang.reflect.Method method = NeoDisplayLogicHandler.class.getDeclaredMethod(
            "evaluateExpression", String.class, Tab.class, Field.class, Map.class);
        method.setAccessible(true);

        // Should not throw; returns true (fail-open)
        boolean result = (boolean) method.invoke(
            handler, "@Invalid@", tab, null, Collections.emptyMap());
        assertTrue(result);
      }
    }
  }
}
