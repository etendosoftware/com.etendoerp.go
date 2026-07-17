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

package com.etendoerp.go.schemaforge.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.servlet.ReadListener;
import javax.servlet.ServletInputStream;
import javax.servlet.http.HttpServletRequest;

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
import org.openbravo.erpCommon.utility.DimensionDisplayUtility;
import org.openbravo.model.ad.datamodel.Column;
import org.openbravo.model.ad.datamodel.Table;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.ad.system.Language;
import org.openbravo.model.ad.ui.Field;
import org.openbravo.model.ad.ui.Tab;
import org.openbravo.model.common.enterprise.DocumentType;
import org.openbravo.model.common.enterprise.Organization;

import com.etendoerp.go.schemaforge.NeoResponse;
import com.etendoerp.go.schemaforge.NeoServlet.NeoPathInfo;
import com.etendoerp.go.schemaforge.data.SFEntity;
import com.etendoerp.go.schemaforge.data.SFSpec;

/**
 * Unit tests for {@link NeoDisplayLogicHelper}.
 *
 * <p>Covers: handleEvaluateDisplay (entity-not-found, no-linked-tab, successful evaluation),
 * buildJsObjectPreamble (null/empty/populated maps, skipSelf filtering),
 * buildEvalContext (currentValues population, OBContext session variables),
 * getPropertyName (DAL property, fallback to dbColumnName, fallback to field name),
 * evaluateExpression (true/false results, exception fail-open behaviour).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class NeoDisplayLogicHelperTest {

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
  @Mock
  private ModelProvider modelProvider;
  @Mock
  private OBScriptEngine scriptEngine;

  private MockedStatic<OBDal> obDalMock;
  private MockedStatic<OBContext> obContextMock;
  private MockedStatic<ModelProvider> modelProviderMock;
  private MockedStatic<OBScriptEngine> scriptEngineMock;

  @BeforeEach
  void setUp() {
    obDalMock = mockStatic(OBDal.class);
    obContextMock = mockStatic(OBContext.class);
    modelProviderMock = mockStatic(ModelProvider.class);
    scriptEngineMock = mockStatic(OBScriptEngine.class);

    obDalMock.when(OBDal::getInstance).thenReturn(obDal);
    obContextMock.when(OBContext::getOBContext).thenReturn(obContext);
    modelProviderMock.when(ModelProvider::getInstance).thenReturn(modelProvider);
    scriptEngineMock.when(OBScriptEngine::getInstance).thenReturn(scriptEngine);

    when(obContext.getCurrentOrganization()).thenReturn(organization);
    when(obContext.getCurrentClient()).thenReturn(client);
    when(obContext.getRole()).thenReturn(role);
    when(obContext.getUser()).thenReturn(user);
    when(obContext.getLanguage()).thenReturn(language);
    when(organization.getId()).thenReturn("org-001");
    when(client.getId()).thenReturn("client-001");
    when(role.getId()).thenReturn("role-001");
    when(user.getId()).thenReturn("user-001");
    when(language.getLanguage()).thenReturn("en_US");
  }

  @AfterEach
  void tearDown() {
    obDalMock.close();
    obContextMock.close();
    modelProviderMock.close();
    scriptEngineMock.close();
  }

  // ── Helper: create NeoPathInfo via reflection (constructors are package-private) ──

  private NeoPathInfo createPathInfo(String specName, String entityName) throws Exception {
    Constructor<NeoPathInfo> ctor = NeoPathInfo.class.getDeclaredConstructor(
        String.class, String.class, String.class,
        boolean.class, String.class,
        boolean.class, String.class, boolean.class,
        boolean.class, boolean.class);
    ctor.setAccessible(true);
    return ctor.newInstance(specName, entityName, null,
        false, null, false, null, true, false, false);
  }

  // ── Helper: wrap a string as a ServletInputStream ──

  private ServletInputStream toServletInputStream(String content) {
    ByteArrayInputStream bais = new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
    return new ServletInputStream() {
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
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // handleEvaluateDisplay
  // ═══════════════════════════════════════════════════════════════════════════

  @Nested
  @DisplayName("handleEvaluateDisplay")
  class HandleEvaluateDisplayTests {

    @SuppressWarnings({ "rawtypes", "unchecked" })
    @Test
    @DisplayName("returns 404 when entity is not found")
    void entityNotFoundReturns404() throws Exception {
      SFSpec spec = mock(SFSpec.class);
      when(spec.getId()).thenReturn("spec-001");

      OBCriteria criteria = mock(OBCriteria.class);
      when(obDal.createCriteria(SFEntity.class)).thenReturn(criteria);
      when(criteria.add(any(Criterion.class))).thenReturn(criteria);
      when(criteria.setMaxResults(1)).thenReturn(criteria);
      when(criteria.list()).thenReturn(Collections.emptyList());

      NeoPathInfo pathInfo = createPathInfo("mySpec", "NonExistent");
      HttpServletRequest request = mock(HttpServletRequest.class);

      NeoResponse response = NeoDisplayLogicHelper.handleEvaluateDisplay(spec, pathInfo, request);

      assertEquals(404, response.getHttpStatus());
      assertTrue(response.getBody().getJSONObject("error").getString("message")
          .contains("Entity not found"));
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    @Test
    @DisplayName("returns 500 when entity has no linked AD_Tab")
    void noLinkedTabReturns500() throws Exception {
      SFSpec spec = mock(SFSpec.class);
      when(spec.getId()).thenReturn("spec-001");

      SFEntity sfEntity = mock(SFEntity.class);
      when(sfEntity.getADTab()).thenReturn(null);

      OBCriteria criteria = mock(OBCriteria.class);
      when(obDal.createCriteria(SFEntity.class)).thenReturn(criteria);
      when(criteria.add(any(Criterion.class))).thenReturn(criteria);
      when(criteria.setMaxResults(1)).thenReturn(criteria);
      when(criteria.list()).thenReturn(Collections.singletonList(sfEntity));

      NeoPathInfo pathInfo = createPathInfo("mySpec", "Order");
      HttpServletRequest request = mock(HttpServletRequest.class);

      NeoResponse response = NeoDisplayLogicHelper.handleEvaluateDisplay(spec, pathInfo, request);

      assertEquals(500, response.getHttpStatus());
      assertTrue(response.getBody().getJSONObject("error").getString("message")
          .contains("no linked AD_Tab"));
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    @Test
    @DisplayName("returns 200 with visibility and readOnly maps on success")
    void successfulEvaluationReturns200() throws Exception {
      SFSpec spec = mock(SFSpec.class);
      when(spec.getId()).thenReturn("spec-001");

      // Set up entity + tab
      SFEntity sfEntity = mock(SFEntity.class);
      Tab tab = mock(Tab.class);
      when(sfEntity.getADTab()).thenReturn(tab);

      // Field with display logic
      Field activeField = mock(Field.class);
      when(activeField.isActive()).thenReturn(true);
      when(activeField.getName()).thenReturn("TestField");
      when(activeField.getDisplayLogic()).thenReturn("@IsActive@='Y'");
      Column col = mock(Column.class);
      when(col.getDBColumnName()).thenReturn("isactive");
      when(col.getReadOnlyLogic()).thenReturn(null);
      Table table = mock(Table.class);
      when(table.getId()).thenReturn("table-001");
      when(col.getTable()).thenReturn(table);
      when(activeField.getColumn()).thenReturn(col);

      // Inactive field should be skipped
      Field inactiveField = mock(Field.class);
      when(inactiveField.isActive()).thenReturn(false);

      List<Field> fields = new ArrayList<>();
      fields.add(activeField);
      fields.add(inactiveField);
      when(tab.getADFieldList()).thenReturn(fields);

      // Criteria returns entity
      OBCriteria entityCriteria = mock(OBCriteria.class);
      when(obDal.createCriteria(SFEntity.class)).thenReturn(entityCriteria);
      when(entityCriteria.add(any(Criterion.class))).thenReturn(entityCriteria);
      when(entityCriteria.setMaxResults(1)).thenReturn(entityCriteria);
      when(entityCriteria.list()).thenReturn(Collections.singletonList(sfEntity));

      // ModelProvider for getPropertyName
      Entity dalEntity = mock(Entity.class);
      Property property = mock(Property.class);
      when(property.getName()).thenReturn("active");
      when(dalEntity.getPropertyByColumnName("isactive")).thenReturn(property);
      when(modelProvider.getEntityByTableId("table-001")).thenReturn(dalEntity);

      // Client mock for buildEvalContext
      Client sysClient = mock(Client.class);
      when(sysClient.isAcctdimCentrallyMaintained()).thenReturn(false);
      when(obDal.get(eq(Client.class), eq("client-001"))).thenReturn(sysClient);

      // Script engine returns true
      when(scriptEngine.eval(any(String.class), any(Map.class))).thenReturn(Boolean.TRUE);

      // Request body
      HttpServletRequest request = mock(HttpServletRequest.class);
      String body = "{\"fieldValues\":{\"active\":\"Y\"}}";
      when(request.getInputStream()).thenReturn(toServletInputStream(body));

      NeoPathInfo pathInfo = createPathInfo("mySpec", "Order");

      NeoResponse response = NeoDisplayLogicHelper.handleEvaluateDisplay(spec, pathInfo, request);

      assertEquals(200, response.getHttpStatus());
      JSONObject resBody = response.getBody();
      assertTrue(resBody.has("visibility"));
      assertTrue(resBody.has("readOnly"));
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    @Test
    @DisplayName("returns 200 with readOnly logic evaluated when column has readOnlyLogic")
    void readOnlyLogicEvaluated() throws Exception {
      SFSpec spec = mock(SFSpec.class);
      when(spec.getId()).thenReturn("spec-001");

      SFEntity sfEntity = mock(SFEntity.class);
      Tab tab = mock(Tab.class);
      when(sfEntity.getADTab()).thenReturn(tab);

      Field field = mock(Field.class);
      when(field.isActive()).thenReturn(true);
      when(field.getName()).thenReturn("Amount");
      when(field.getDisplayLogic()).thenReturn(null);
      Column col = mock(Column.class);
      when(col.getDBColumnName()).thenReturn("amount");
      when(col.getReadOnlyLogic()).thenReturn("@Posted@='Y'");
      Table table = mock(Table.class);
      when(table.getId()).thenReturn("table-001");
      when(col.getTable()).thenReturn(table);
      when(field.getColumn()).thenReturn(col);

      when(tab.getADFieldList()).thenReturn(Collections.singletonList(field));

      OBCriteria entityCriteria = mock(OBCriteria.class);
      when(obDal.createCriteria(SFEntity.class)).thenReturn(entityCriteria);
      when(entityCriteria.add(any(Criterion.class))).thenReturn(entityCriteria);
      when(entityCriteria.setMaxResults(1)).thenReturn(entityCriteria);
      when(entityCriteria.list()).thenReturn(Collections.singletonList(sfEntity));

      Entity dalEntity = mock(Entity.class);
      Property property = mock(Property.class);
      when(property.getName()).thenReturn("amount");
      when(dalEntity.getPropertyByColumnName("amount")).thenReturn(property);
      when(modelProvider.getEntityByTableId("table-001")).thenReturn(dalEntity);

      Client sysClient = mock(Client.class);
      when(sysClient.isAcctdimCentrallyMaintained()).thenReturn(false);
      when(obDal.get(eq(Client.class), eq("client-001"))).thenReturn(sysClient);

      when(scriptEngine.eval(any(String.class), any(Map.class))).thenReturn(Boolean.TRUE);

      HttpServletRequest request = mock(HttpServletRequest.class);
      when(request.getInputStream()).thenReturn(toServletInputStream("{\"fieldValues\":{}}"));

      NeoPathInfo pathInfo = createPathInfo("mySpec", "Order");
      NeoResponse response = NeoDisplayLogicHelper.handleEvaluateDisplay(spec, pathInfo, request);

      assertEquals(200, response.getHttpStatus());
      JSONObject readOnly = response.getBody().getJSONObject("readOnly");
      assertTrue(readOnly.has("amount"));
    }
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // buildJsObjectPreamble
  // ═══════════════════════════════════════════════════════════════════════════

  @Nested
  @DisplayName("buildJsObjectPreamble")
  class BuildJsObjectPreambleTests {

    @Test
    @DisplayName("returns empty object declaration when map is null")
    void nullMapReturnsEmptyObject() {
      String result = NeoDisplayLogicHelper.buildJsObjectPreamble("ctx", null, false);
      assertEquals("var ctx = {};", result);
    }

    @Test
    @DisplayName("returns empty object declaration when map is empty")
    void emptyMapReturnsEmptyObject() {
      String result = NeoDisplayLogicHelper.buildJsObjectPreamble("ctx", new HashMap<>(), false);
      assertEquals("var ctx = {};", result);
    }

    @Test
    @DisplayName("serializes map entries into JS object")
    void mapWithValuesProducesJsObject() throws Exception {
      Map<String, Object> map = new HashMap<>();
      map.put("name", "John");
      map.put("age", "30");

      String result = NeoDisplayLogicHelper.buildJsObjectPreamble("data", map, false);

      assertTrue(result.startsWith("var data = "));
      assertTrue(result.endsWith(";"));
      // Parse the JSON portion to verify contents
      String jsonPart = result.substring("var data = ".length(), result.length() - 1);
      JSONObject parsed = new JSONObject(jsonPart);
      assertEquals("John", parsed.getString("name"));
      assertEquals("30", parsed.getString("age"));
    }

    @Test
    @DisplayName("skips null values in the map")
    void skipsNullValues() throws Exception {
      Map<String, Object> map = new HashMap<>();
      map.put("present", "yes");
      map.put("absent", null);

      String result = NeoDisplayLogicHelper.buildJsObjectPreamble("v", map, false);
      String jsonPart = result.substring("var v = ".length(), result.length() - 1);
      JSONObject parsed = new JSONObject(jsonPart);
      assertTrue(parsed.has("present"));
      assertFalse(parsed.has("absent"));
    }

    @Test
    @DisplayName("skips Map-typed values in the map")
    void skipsMapValues() throws Exception {
      Map<String, Object> map = new HashMap<>();
      map.put("simple", "text");
      map.put("nested", new HashMap<>());

      String result = NeoDisplayLogicHelper.buildJsObjectPreamble("v", map, false);
      String jsonPart = result.substring("var v = ".length(), result.length() - 1);
      JSONObject parsed = new JSONObject(jsonPart);
      assertTrue(parsed.has("simple"));
      assertFalse(parsed.has("nested"));
    }

    @Test
    @DisplayName("skipSelf=true filters out 'context' and 'currentValues' keys")
    void skipSelfFiltersContextAndCurrentValues() throws Exception {
      Map<String, Object> map = new HashMap<>();
      map.put("context", "should-be-skipped");
      map.put("currentValues", "should-be-skipped");
      map.put("AD_Org_ID", "org-001");

      String result = NeoDisplayLogicHelper.buildJsObjectPreamble("context", map, true);
      String jsonPart = result.substring("var context = ".length(), result.length() - 1);
      JSONObject parsed = new JSONObject(jsonPart);
      assertFalse(parsed.has("context"));
      assertFalse(parsed.has("currentValues"));
      assertEquals("org-001", parsed.getString("AD_Org_ID"));
    }

    @Test
    @DisplayName("skipSelf=false does not filter 'context' key (if value is not Map/null)")
    void skipSelfFalseKeepsContextKey() throws Exception {
      Map<String, Object> map = new HashMap<>();
      map.put("context", "keep-me");
      map.put("other", "value");

      String result = NeoDisplayLogicHelper.buildJsObjectPreamble("v", map, false);
      String jsonPart = result.substring("var v = ".length(), result.length() - 1);
      JSONObject parsed = new JSONObject(jsonPart);
      assertTrue(parsed.has("context"));
      assertTrue(parsed.has("other"));
    }
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // buildEvalContext
  // ═══════════════════════════════════════════════════════════════════════════

  @Nested
  @DisplayName("buildEvalContext")
  class BuildEvalContextTests {

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("populates currentValues from fieldValues JSON")
    void populatesCurrentValues() throws Exception {
      JSONObject fieldValues = new JSONObject();
      fieldValues.put("name", "Test Order");
      fieldValues.put("amount", 100);

      Client sysClient = mock(Client.class);
      when(sysClient.isAcctdimCentrallyMaintained()).thenReturn(false);
      when(obDal.get(eq(Client.class), eq("client-001"))).thenReturn(sysClient);

      Map<String, Object> ctx = NeoDisplayLogicHelper.buildEvalContext(fieldValues);

      assertNotNull(ctx.get("currentValues"));
      Map<String, Object> currentValues = (Map<String, Object>) ctx.get("currentValues");
      assertEquals("Test Order", currentValues.get("name"));
      assertEquals(100, currentValues.get("amount"));
    }

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("adds OBContext session variables (org, client, role, user)")
    void addsSessionVariables() throws Exception {
      JSONObject fieldValues = new JSONObject();

      Client sysClient = mock(Client.class);
      when(sysClient.isAcctdimCentrallyMaintained()).thenReturn(false);
      when(obDal.get(eq(Client.class), eq("client-001"))).thenReturn(sysClient);

      Map<String, Object> ctx = NeoDisplayLogicHelper.buildEvalContext(fieldValues);

      assertEquals("org-001", ctx.get("AD_Org_ID"));
      assertEquals("client-001", ctx.get("AD_Client_ID"));
      assertEquals("role-001", ctx.get("AD_Role_ID"));
      assertEquals("user-001", ctx.get("AD_User_ID"));
    }

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("converts JSON null values to Java null in currentValues")
    void convertsJsonNullToJavaNull() throws Exception {
      JSONObject fieldValues = new JSONObject();
      fieldValues.put("nullableField", JSONObject.NULL);

      Client sysClient = mock(Client.class);
      when(sysClient.isAcctdimCentrallyMaintained()).thenReturn(false);
      when(obDal.get(eq(Client.class), eq("client-001"))).thenReturn(sysClient);

      Map<String, Object> ctx = NeoDisplayLogicHelper.buildEvalContext(fieldValues);

      Map<String, Object> currentValues = (Map<String, Object>) ctx.get("currentValues");
      assertTrue(currentValues.containsKey("nullableField"));
      assertNull(currentValues.get("nullableField"));
    }

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("mirrors currentValues entries into the top-level context")
    void mirrorsCurrentValuesToTopLevel() throws Exception {
      JSONObject fieldValues = new JSONObject();
      fieldValues.put("documentNo", "DOC-001");

      Client sysClient = mock(Client.class);
      when(sysClient.isAcctdimCentrallyMaintained()).thenReturn(false);
      when(obDal.get(eq(Client.class), eq("client-001"))).thenReturn(sysClient);

      Map<String, Object> ctx = NeoDisplayLogicHelper.buildEvalContext(fieldValues);

      assertEquals("DOC-001", ctx.get("documentNo"));
    }
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // ETP-4529 regression: @ACCT_DIMENSION_DISPLAY@ macro for centrally-maintained clients
  //
  // Root cause: the evaluate-display endpoint used to be routed through
  // NeoDisplayLogicHandler, whose context builder never set the
  // "$IsAcctDimCentrally" session key. Etendo core's
  // DimensionDisplayUtility.computeAccountingDimensionDisplayLogic() generates JS that
  // branches on context.$IsAcctDimCentrally === 'N' vs 'Y'; with it undefined, BOTH
  // branches are false, so @ACCT_DIMENSION_DISPLAY@ always evaluated to false for any
  // client with acctdim_centrally_maintained = 'Y' (the mode almost every real client
  // uses), regardless of the actual GL Configuration toggles.
  //
  // NeoDisplayLogicHelper.buildEvalContext() is the correct implementation (it sets
  // $IsAcctDimCentrally, merges DimensionDisplayUtility.getAccountingDimensionConfiguration()
  // for centrally-maintained clients, and resolves DOCBASETYPE) and is now the one NeoServlet
  // routes evaluate-display through (see NeoSubEndpointDispatcher).
  // ═══════════════════════════════════════════════════════════════════════════

  @Nested
  @DisplayName("ETP-4529: ACCT_DIMENSION_DISPLAY macro for centrally-maintained clients")
  class AcctDimensionDisplayCentrallyMaintainedRegressionTests {

    /** Cost Center dimension flag key for an AR Invoice ("ARI") header field, as produced by
     *  DimensionDisplayUtility#getAccountingDimensionConfiguration for a centrally-maintained
     *  client with the Cost Center dimension enabled at header level. */
    private static final String DIM_FLAG_KEY = "$Element_CC_ARI_H";

    /** The exact JS shape DimensionDisplayUtility#computeAccountingDimensionDisplayLogic()
     *  generates for a header field mapped to the Cost Center dimension (see
     *  DimensionDisplayUtility.java lines 129-146). */
    private static final String ACCT_DIMENSION_DISPLAY_JS =
        "(context.$IsAcctDimCentrally === 'N' && context.$Element_CC === 'Y')"
        + " || (context.$IsAcctDimCentrally === 'Y'"
        + " && context['$Element_CC_' + OB.Utilities.getValue(currentValues, \"DOCBASETYPE\") + '_H'] === 'Y')";

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("buildEvalContext sets $IsAcctDimCentrally='Y' and merges the dimension "
        + "configuration map when the client is centrally maintained")
    void buildEvalContext_centrallyMaintainedClient_setsIsAcctDimCentrallyAndDimensionFlags()
        throws Exception {
      JSONObject fieldValues = new JSONObject();
      fieldValues.put("transactionDocument", "docType-ARI");

      Client sysClient = mock(Client.class);
      when(sysClient.isAcctdimCentrallyMaintained()).thenReturn(true);
      when(obDal.get(eq(Client.class), eq("client-001"))).thenReturn(sysClient);

      DocumentType docType = mock(DocumentType.class);
      when(docType.getDocumentCategory()).thenReturn("ARI");
      when(obDal.get(eq(DocumentType.class), eq("docType-ARI"))).thenReturn(docType);

      Map<String, String> acctDimMap = new HashMap<>();
      acctDimMap.put(DIM_FLAG_KEY, "Y");

      try (MockedStatic<DimensionDisplayUtility> dimUtilMock =
          mockStatic(DimensionDisplayUtility.class)) {
        dimUtilMock.when(() -> DimensionDisplayUtility.getAccountingDimensionConfiguration(sysClient))
            .thenReturn(acctDimMap);

        Map<String, Object> ctx = NeoDisplayLogicHelper.buildEvalContext(fieldValues);

        assertEquals("Y", ctx.get(DimensionDisplayUtility.IsAcctDimCentrally),
            "buildEvalContext must set $IsAcctDimCentrally so the core-generated macro "
                + "expression can branch correctly");
        assertEquals("Y", ctx.get(DIM_FLAG_KEY),
            "the centrally-maintained dimension configuration map must be merged into the "
                + "eval context");
        assertEquals("ARI", ctx.get("DOCBASETYPE"));
        Map<String, Object> currentValues = (Map<String, Object>) ctx.get("currentValues");
        assertEquals("ARI", currentValues.get("DOCBASETYPE"),
            "DOCBASETYPE must also be reachable via OB.Utilities.getValue(currentValues, ...)");
      }
    }

    /**
     * End-to-end reproduction of the exact bug: evaluates the real macro-shaped JS expression
     * (mirroring DynamicExpressionParser's generated output for @ACCT_DIMENSION_DISPLAY@) via the
     * real Rhino engine -- no mocked eval result.
     *
     * <p>With the FIXED context (NeoDisplayLogicHelper.buildEvalContext, now wired into
     * NeoServlet), the field-owning dimension (Cost Center) IS enabled in GL Configuration for
     * this client/doc-base-type/level combination, so the macro must evaluate {@code true}.
     *
     * <p>With the BUGGY context shape (mirroring the pre-fix NeoDisplayLogicHandler, which never
     * set $IsAcctDimCentrally), the exact same expression must evaluate {@code false} --
     * reproducing ETP-4529 for a centrally-maintained client regardless of GL Configuration.
     */
    @Test
    @DisplayName("the ACCT_DIMENSION_DISPLAY macro evaluates true with the fixed context and "
        + "false with the pre-fix (buggy) context, for the same GL Configuration")
    void acctDimensionDisplayMacro_trueWithFixedContext_falseWithBuggyContext() throws Exception {
      // Let OBScriptEngine.getInstance() return the REAL singleton (real Rhino), overriding the
      // class-level mock so this test exercises actual JS evaluation instead of a canned result.
      scriptEngineMock.when(OBScriptEngine::getInstance).thenCallRealMethod();

      Client sysClient = mock(Client.class);
      when(sysClient.isAcctdimCentrallyMaintained()).thenReturn(true);
      when(obDal.get(eq(Client.class), eq("client-001"))).thenReturn(sysClient);

      DocumentType docType = mock(DocumentType.class);
      when(docType.getDocumentCategory()).thenReturn("ARI");
      when(obDal.get(eq(DocumentType.class), eq("docType-ARI"))).thenReturn(docType);

      Map<String, String> acctDimMap = new HashMap<>();
      // The Cost Center dimension IS enabled in GL Configuration for AR Invoice headers.
      acctDimMap.put(DIM_FLAG_KEY, "Y");

      JSONObject fieldValues = new JSONObject();
      fieldValues.put("transactionDocument", "docType-ARI");

      Tab tab = mock(Tab.class);
      Field field = mock(Field.class);
      when(field.getName()).thenReturn("costCenter");

      // NeoDisplayLogicHelper#evaluateExpression always constructs a real DynamicExpressionParser
      // from the raw AD display-logic string to obtain getJSExpression(). Parsing a real
      // "@ACCT_DIMENSION_DISPLAY@" macro end-to-end needs a live AD_Tab/AD_Field/DB (dimension
      // mapping lookups), so DynamicExpressionParser's construction is intercepted here to force
      // getJSExpression() to return the exact JS core generates for this macro (see
      // DimensionDisplayUtility#computeAccountingDimensionDisplayLogic) -- everything downstream
      // of that (the OB.Utilities shim, context/currentValues preambles, and the real Rhino
      // OBScriptEngine eval) runs for real and unmocked.
      try (MockedConstruction<DynamicExpressionParser> parserMock = mockConstruction(
          DynamicExpressionParser.class,
          (mock, context) -> {
            when(mock.getJSExpression()).thenReturn(ACCT_DIMENSION_DISPLAY_JS);
            when(mock.getSessionAttributes()).thenReturn(Collections.emptyList());
          })) {

        // ── After the fix: NeoDisplayLogicHelper.buildEvalContext() ──
        try (MockedStatic<DimensionDisplayUtility> dimUtilMock =
            mockStatic(DimensionDisplayUtility.class)) {
          dimUtilMock.when(() -> DimensionDisplayUtility.getAccountingDimensionConfiguration(sysClient))
              .thenReturn(acctDimMap);

          Map<String, Object> fixedContext = NeoDisplayLogicHelper.buildEvalContext(fieldValues);

          boolean resultWithFix = NeoDisplayLogicHelper.evaluateExpression(
              "@ACCT_DIMENSION_DISPLAY@", tab, field, fixedContext);

          assertTrue(resultWithFix,
              "With the fixed context ($IsAcctDimCentrally set + dimension config merged), the "
                  + "macro must evaluate true when the dimension is enabled in GL Configuration");
        }

        // ── Before the fix: reproduces NeoDisplayLogicHandler's buggy context shape, which
        //    never set $IsAcctDimCentrally (only old-style $Element_<DIM> keys). Built by hand
        //    (rather than reusing buildEvalContext again) so DOCBASETYPE resolution and the
        //    old-style dimension flag are both deterministically present -- isolating
        //    $IsAcctDimCentrally as the only missing piece, exactly the ETP-4529 root cause.
        Map<String, Object> buggyCurrentValues = new HashMap<>();
        buggyCurrentValues.put("transactionDocument", "docType-ARI");
        buggyCurrentValues.put("DOCBASETYPE", "ARI");

        Map<String, Object> buggyContext = new HashMap<>();
        buggyContext.put("currentValues", buggyCurrentValues);
        buggyContext.putAll(buggyCurrentValues);
        // Old-style key NeoDisplayLogicHandler's resolveAccountingDimensions() did resolve --
        // present and 'Y', yet the macro still evaluates false because $IsAcctDimCentrally is
        // undefined, so neither branch of the macro can match.
        buggyContext.put("$Element_CC", "Y");
        buggyContext.put("context", buggyContext);

        boolean resultBeforeFix = NeoDisplayLogicHelper.evaluateExpression(
            "@ACCT_DIMENSION_DISPLAY@", tab, field, buggyContext);

        assertFalse(resultBeforeFix,
            "Reproduces ETP-4529: without $IsAcctDimCentrally, both branches of the macro are "
                + "false for a centrally-maintained client, regardless of GL Configuration");
      }
    }
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // getPropertyName
  // ═══════════════════════════════════════════════════════════════════════════

  @Nested
  @DisplayName("getPropertyName")
  class GetPropertyNameTests {

    @Test
    @DisplayName("returns DAL property name when column has a matching DAL property")
    void returnsDALPropertyName() {
      Field field = mock(Field.class);
      Column column = mock(Column.class);
      Table table = mock(Table.class);
      when(field.getColumn()).thenReturn(column);
      when(column.getDBColumnName()).thenReturn("ad_org_id");
      when(column.getTable()).thenReturn(table);
      when(table.getId()).thenReturn("table-001");

      Entity entity = mock(Entity.class);
      Property property = mock(Property.class);
      when(property.getName()).thenReturn("organization");
      when(entity.getPropertyByColumnName("ad_org_id")).thenReturn(property);
      when(modelProvider.getEntityByTableId("table-001")).thenReturn(entity);

      String result = NeoDisplayLogicHelper.getPropertyName(field);
      assertEquals("organization", result);
    }

    @Test
    @DisplayName("falls back to DB column name when no DAL property is found")
    void fallsBackToDbColumnName() {
      Field field = mock(Field.class);
      Column column = mock(Column.class);
      Table table = mock(Table.class);
      when(field.getColumn()).thenReturn(column);
      when(column.getDBColumnName()).thenReturn("custom_column");
      when(column.getTable()).thenReturn(table);
      when(table.getId()).thenReturn("table-001");

      Entity entity = mock(Entity.class);
      when(entity.getPropertyByColumnName("custom_column")).thenReturn(null);
      when(modelProvider.getEntityByTableId("table-001")).thenReturn(entity);

      String result = NeoDisplayLogicHelper.getPropertyName(field);
      assertEquals("custom_column", result);
    }

    @Test
    @DisplayName("falls back to DB column name when DAL entity is not found")
    void fallsBackToDbColumnNameWhenNoEntity() {
      Field field = mock(Field.class);
      Column column = mock(Column.class);
      Table table = mock(Table.class);
      when(field.getColumn()).thenReturn(column);
      when(column.getDBColumnName()).thenReturn("orphan_col");
      when(column.getTable()).thenReturn(table);
      when(table.getId()).thenReturn("table-unknown");

      when(modelProvider.getEntityByTableId("table-unknown")).thenReturn(null);

      String result = NeoDisplayLogicHelper.getPropertyName(field);
      assertEquals("orphan_col", result);
    }

    @Test
    @DisplayName("falls back to field name when column is null")
    void fallsBackToFieldNameWhenColumnIsNull() {
      Field field = mock(Field.class);
      when(field.getColumn()).thenReturn(null);
      when(field.getName()).thenReturn("My Display Field");

      String result = NeoDisplayLogicHelper.getPropertyName(field);
      assertEquals("My Display Field", result);
    }
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // evaluateExpression
  // ═══════════════════════════════════════════════════════════════════════════

  @Nested
  @DisplayName("evaluateExpression")
  class EvaluateExpressionTests {

    @Test
    @DisplayName("returns true when script engine evaluates to Boolean.TRUE")
    void returnsTrueWhenEngineReturnsTrue() throws Exception {
      Tab tab = mock(Tab.class);
      Field field = mock(Field.class);
      when(field.getName()).thenReturn("testField");

      Map<String, Object> evalContext = new HashMap<>();
      evalContext.put("currentValues", new HashMap<>());

      when(scriptEngine.eval(any(String.class), any(Map.class))).thenReturn(Boolean.TRUE);

      boolean result = NeoDisplayLogicHelper.evaluateExpression(
          "@IsActive@='Y'", tab, field, evalContext);

      assertTrue(result);
    }

    @Test
    @DisplayName("returns false when script engine evaluates to Boolean.FALSE")
    void returnsFalseWhenEngineReturnsFalse() throws Exception {
      Tab tab = mock(Tab.class);
      Field field = mock(Field.class);
      when(field.getName()).thenReturn("testField");

      Map<String, Object> evalContext = new HashMap<>();
      evalContext.put("currentValues", new HashMap<>());

      when(scriptEngine.eval(any(String.class), any(Map.class))).thenReturn(Boolean.FALSE);

      boolean result = NeoDisplayLogicHelper.evaluateExpression(
          "@IsActive@='Y'", tab, field, evalContext);

      assertFalse(result);
    }

    @Test
    @DisplayName("returns true (fail-open) when script engine throws exception")
    void returnsTrue_failOpen_whenEngineThrows() throws Exception {
      Tab tab = mock(Tab.class);
      Field field = mock(Field.class);
      when(field.getName()).thenReturn("testField");

      Map<String, Object> evalContext = new HashMap<>();
      evalContext.put("currentValues", new HashMap<>());

      when(scriptEngine.eval(any(String.class), any(Map.class)))
          .thenThrow(new RuntimeException("Script error"));

      boolean result = NeoDisplayLogicHelper.evaluateExpression(
          "@Invalid@='X'", tab, field, evalContext);

      assertTrue(result, "Should fail open (return true) when evaluation throws");
    }

    @Test
    @DisplayName("returns true when null field is passed (tab-level expression)")
    void returnsTrueForNullField() throws Exception {
      Tab tab = mock(Tab.class);

      Map<String, Object> evalContext = new HashMap<>();
      evalContext.put("currentValues", new HashMap<>());

      when(scriptEngine.eval(any(String.class), any(Map.class))).thenReturn(Boolean.TRUE);

      boolean result = NeoDisplayLogicHelper.evaluateExpression(
          "@IsActive@='Y'", tab, null, evalContext);

      assertTrue(result);
    }

    @Test
    @DisplayName("returns false when script engine returns non-Boolean truthy value")
    void returnsFalseForNonBooleanResult() throws Exception {
      Tab tab = mock(Tab.class);
      Field field = mock(Field.class);
      when(field.getName()).thenReturn("testField");

      Map<String, Object> evalContext = new HashMap<>();
      evalContext.put("currentValues", new HashMap<>());

      // Returns a string "true" instead of Boolean.TRUE
      when(scriptEngine.eval(any(String.class), any(Map.class))).thenReturn("true");

      boolean result = NeoDisplayLogicHelper.evaluateExpression(
          "@IsActive@='Y'", tab, field, evalContext);

      assertFalse(result, "Only Boolean.TRUE should return true, not String 'true'");
    }
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // parseRequestBody (private, tested indirectly via handleEvaluateDisplay)
  // ═══════════════════════════════════════════════════════════════════════════

  @Nested
  @DisplayName("parseRequestBody (indirect)")
  class ParseRequestBodyTests {

    @SuppressWarnings({ "rawtypes", "unchecked" })
    @Test
    @DisplayName("empty request body produces empty fieldValues (no NPE)")
    void emptyBodyDoesNotCauseNPE() throws Exception {
      SFSpec spec = mock(SFSpec.class);
      when(spec.getId()).thenReturn("spec-001");

      SFEntity sfEntity = mock(SFEntity.class);
      Tab tab = mock(Tab.class);
      when(sfEntity.getADTab()).thenReturn(tab);
      when(tab.getADFieldList()).thenReturn(Collections.emptyList());

      OBCriteria entityCriteria = mock(OBCriteria.class);
      when(obDal.createCriteria(SFEntity.class)).thenReturn(entityCriteria);
      when(entityCriteria.add(any(Criterion.class))).thenReturn(entityCriteria);
      when(entityCriteria.setMaxResults(1)).thenReturn(entityCriteria);
      when(entityCriteria.list()).thenReturn(Collections.singletonList(sfEntity));

      Client sysClient = mock(Client.class);
      when(sysClient.isAcctdimCentrallyMaintained()).thenReturn(false);
      when(obDal.get(eq(Client.class), eq("client-001"))).thenReturn(sysClient);

      HttpServletRequest request = mock(HttpServletRequest.class);
      when(request.getInputStream()).thenReturn(toServletInputStream(""));

      NeoPathInfo pathInfo = createPathInfo("mySpec", "Order");
      NeoResponse response = NeoDisplayLogicHelper.handleEvaluateDisplay(spec, pathInfo, request);

      assertEquals(200, response.getHttpStatus());
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    @Test
    @DisplayName("request body without fieldValues key produces empty context")
    void bodyWithoutFieldValuesKey() throws Exception {
      SFSpec spec = mock(SFSpec.class);
      when(spec.getId()).thenReturn("spec-001");

      SFEntity sfEntity = mock(SFEntity.class);
      Tab tab = mock(Tab.class);
      when(sfEntity.getADTab()).thenReturn(tab);
      when(tab.getADFieldList()).thenReturn(Collections.emptyList());

      OBCriteria entityCriteria = mock(OBCriteria.class);
      when(obDal.createCriteria(SFEntity.class)).thenReturn(entityCriteria);
      when(entityCriteria.add(any(Criterion.class))).thenReturn(entityCriteria);
      when(entityCriteria.setMaxResults(1)).thenReturn(entityCriteria);
      when(entityCriteria.list()).thenReturn(Collections.singletonList(sfEntity));

      Client sysClient = mock(Client.class);
      when(sysClient.isAcctdimCentrallyMaintained()).thenReturn(false);
      when(obDal.get(eq(Client.class), eq("client-001"))).thenReturn(sysClient);

      HttpServletRequest request = mock(HttpServletRequest.class);
      when(request.getInputStream()).thenReturn(toServletInputStream("{\"other\":\"data\"}"));

      NeoPathInfo pathInfo = createPathInfo("mySpec", "Order");
      NeoResponse response = NeoDisplayLogicHelper.handleEvaluateDisplay(spec, pathInfo, request);

      assertEquals(200, response.getHttpStatus());
    }
  }
}