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
package com.etendoerp.go.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.lang.reflect.Modifier;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.openbravo.base.model.Property;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.datamodel.Column;
import org.openbravo.model.ad.ui.Process;
import org.openbravo.model.ad.ui.Window;

import com.etendoerp.go.schemaforge.NeoSelectorService;
import com.etendoerp.go.schemaforge.data.SFEntity;
import com.etendoerp.go.schemaforge.data.SFField;
import com.etendoerp.go.schemaforge.data.SFSpec;
import com.etendoerp.go.schemaforge.util.NeoAccessHelper;

/**
 * Unit tests for {@link McpToolRouterSupport}.
 * Tests the pure utility methods that don't require DB access.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class McpToolRouterSupportTest {

  @Test
  @DisplayName("Utility class hides its constructor")
  void utilityClassHidesConstructor() throws ReflectiveOperationException {
    Constructor<McpToolRouterSupport> constructor = McpToolRouterSupport.class.getDeclaredConstructor();
    assertEquals(Modifier.PRIVATE, constructor.getModifiers() & Modifier.PRIVATE);
    constructor.setAccessible(true);
    constructor.newInstance();
  }


  private static Object invokeStatic(String methodName, Class<?>[] paramTypes, Object... args)
      throws Exception {
    Method method = McpToolRouterSupport.class.getDeclaredMethod(methodName, paramTypes);
    method.setAccessible(true);
    return method.invoke(null, args);
  }

  // ─── buildMethodsArray ──────────────────────────────────────────────

  @Nested
  @DisplayName("buildMethodsArray")
  class BuildMethodsArray {

    @Test
    void allMethodsEnabled() {
      SFEntity entity = mock(SFEntity.class);
      when(entity.isGet()).thenReturn(true);
      when(entity.isGetByID()).thenReturn(true);
      when(entity.isPost()).thenReturn(true);
      when(entity.isPut()).thenReturn(true);
      when(entity.isPatch()).thenReturn(true);
      when(entity.isDelete()).thenReturn(true);

      JSONArray methods = McpToolRouterSupport.buildMethodsArray(entity);
      assertEquals(5, methods.length()); // GET, POST, PUT, PATCH, DELETE
      assertTrue(arrayContains(methods, "GET"));
      assertTrue(arrayContains(methods, "POST"));
      assertTrue(arrayContains(methods, "PUT"));
      assertTrue(arrayContains(methods, "PATCH"));
      assertTrue(arrayContains(methods, "DELETE"));
    }

    @Test
    void noMethodsEnabled() {
      SFEntity entity = mock(SFEntity.class);
      when(entity.isGet()).thenReturn(false);
      when(entity.isGetByID()).thenReturn(false);
      when(entity.isPost()).thenReturn(false);
      when(entity.isPut()).thenReturn(false);
      when(entity.isPatch()).thenReturn(false);
      when(entity.isDelete()).thenReturn(false);

      JSONArray methods = McpToolRouterSupport.buildMethodsArray(entity);
      assertEquals(0, methods.length());
    }

    @Test
    void onlyGetByIdAddsGet() {
      SFEntity entity = mock(SFEntity.class);
      when(entity.isGet()).thenReturn(false);
      when(entity.isGetByID()).thenReturn(true);
      when(entity.isPost()).thenReturn(false);
      when(entity.isPut()).thenReturn(false);
      when(entity.isPatch()).thenReturn(false);
      when(entity.isDelete()).thenReturn(false);

      JSONArray methods = McpToolRouterSupport.buildMethodsArray(entity);
      assertEquals(1, methods.length());
      assertTrue(arrayContains(methods, "GET"));
    }

    @Test
    void nullBooleansTreatedAsFalse() {
      SFEntity entity = mock(SFEntity.class);
      when(entity.isGet()).thenReturn(null);
      when(entity.isGetByID()).thenReturn(null);
      when(entity.isPost()).thenReturn(null);
      when(entity.isPut()).thenReturn(null);
      when(entity.isPatch()).thenReturn(null);
      when(entity.isDelete()).thenReturn(null);

      JSONArray methods = McpToolRouterSupport.buildMethodsArray(entity);
      assertEquals(0, methods.length());
    }
  }

  // ─── mapColumnType ──────────────────────────────────────────────────

  @Nested
  @DisplayName("mapColumnType")
  class MapColumnType {

    @Test
    void nullRefIdReturnsString() {
      assertEquals("string", McpToolRouterSupport.mapColumnType(null));
    }

    @ParameterizedTest
    @CsvSource({
        "'10', string",
        "'14', string",
        "'34', string",
        "'11', number",
        "'22', number",
        "'29', number",
        "'12', number",
        "'800008', number",
        "'800019', number",
        "'20', boolean",
        "'15', date",
        "'16', datetime",
        "'24', time",
        "'28', button",
        "'17', list",
        "'13', id",
        "'19', foreignKey",
        "'18', foreignKey",
        "'30', foreignKey"
    })
    void knownRefIdsMappedCorrectly(String refId, String expectedType) {
      assertEquals(expectedType, McpToolRouterSupport.mapColumnType(refId));
    }

    @Test
    void obuiselRefIdMapToForeignKey() {
      assertEquals("foreignKey",
          McpToolRouterSupport.mapColumnType(NeoSelectorService.REF_OBUISEL));
    }

    @Test
    void unknownRefIdDefaultsToString() {
      assertEquals("string", McpToolRouterSupport.mapColumnType("999999"));
    }
  }

  // ─── mapSelectorType ────────────────────────────────────────────────

  @Nested
  @DisplayName("mapSelectorType")
  class MapSelectorType {

    @Test
    void nullRefIdReturnsNull() {
      assertNull(McpToolRouterSupport.mapSelectorType(null));
    }

    @ParameterizedTest
    @CsvSource({
        "'19', TableDir",
        "'18', Table",
        "'30', Search"
    })
    void knownRefIdsMappedCorrectly(String refId, String expectedType) {
      assertEquals(expectedType, McpToolRouterSupport.mapSelectorType(refId));
    }

    @Test
    void obuiselRefIdMapsToOBUISEL() {
      assertEquals("OBUISEL",
          McpToolRouterSupport.mapSelectorType(NeoSelectorService.REF_OBUISEL));
    }

    @Test
    void unknownRefIdReturnsNull() {
      assertNull(McpToolRouterSupport.mapSelectorType("99"));
    }
  }

  // ─── hasSpecAccess ──────────────────────────────────────────────────

  @Nested
  @DisplayName("hasSpecAccess")
  class HasSpecAccess {

    private MockedStatic<NeoAccessUtils> accessMock;

    @BeforeEach
    void setUp() {
      accessMock = mockStatic(NeoAccessUtils.class);
    }

    @AfterEach
    void tearDown() {
      accessMock.close();
    }

    @Test
    void windowSpecWithAccessReturnsTrue() {
      SFSpec spec = mock(SFSpec.class);
      Window window = mock(Window.class);
      when(spec.getADWindow()).thenReturn(window);
      when(window.getId()).thenReturn("win-1");
      accessMock.when(() -> NeoAccessUtils.hasWindowAccess("win-1")).thenReturn(true);

      assertTrue(McpToolRouterSupport.hasSpecAccess(spec, "W"));
    }

    @Test
    void windowSpecWithoutAccessReturnsFalse() {
      SFSpec spec = mock(SFSpec.class);
      Window window = mock(Window.class);
      when(spec.getADWindow()).thenReturn(window);
      when(window.getId()).thenReturn("win-2");
      accessMock.when(() -> NeoAccessUtils.hasWindowAccess("win-2")).thenReturn(false);

      assertFalse(McpToolRouterSupport.hasSpecAccess(spec, "W"));
    }

    @Test
    void windowSpecWithNullWindowReturnsTrue() {
      SFSpec spec = mock(SFSpec.class);
      when(spec.getADWindow()).thenReturn(null);

      assertTrue(McpToolRouterSupport.hasSpecAccess(spec, "W"));
    }

    @Test
    void processSpecWithAccessReturnsTrue() {
      SFSpec spec = mock(SFSpec.class);
      Process process = mock(Process.class);
      when(spec.getProcess()).thenReturn(process);
      when(process.getId()).thenReturn("proc-1");
      accessMock.when(() -> NeoAccessUtils.hasProcessAccess("proc-1")).thenReturn(true);

      assertTrue(McpToolRouterSupport.hasSpecAccess(spec, "P"));
    }

    @Test
    void reportSpecDelegatesToProcessAccess() {
      SFSpec spec = mock(SFSpec.class);
      Process process = mock(Process.class);
      when(spec.getProcess()).thenReturn(process);
      when(process.getId()).thenReturn("proc-2");
      accessMock.when(() -> NeoAccessUtils.hasProcessAccess("proc-2")).thenReturn(false);

      assertFalse(McpToolRouterSupport.hasSpecAccess(spec, "R"));
    }

    @Test
    void unknownSpecTypeReturnsTrue() {
      SFSpec spec = mock(SFSpec.class);
      assertTrue(McpToolRouterSupport.hasSpecAccess(spec, "X"));
    }
  }

  // ─── buildDiscoverSpec ──────────────────────────────────────────────

  @Nested
  @DisplayName("buildDiscoverSpec")
  class BuildDiscoverSpec {

    @Test
    void basicFieldsAreSet() throws Exception {
      SFSpec spec = mock(SFSpec.class);
      when(spec.getName()).thenReturn("sales-order");
      when(spec.getDescription()).thenReturn("Sales Order");

      JSONObject result = McpToolRouterSupport.buildDiscoverSpec(spec, "W", null);
      assertEquals("sales-order", result.getString("name"));
      assertEquals("W", result.getString("type"));
      assertEquals("Sales Order", result.getString("description"));
    }

    @Test
    void nullDescriptionIsOmitted() throws Exception {
      SFSpec spec = mock(SFSpec.class);
      when(spec.getName()).thenReturn("test");
      when(spec.getDescription()).thenReturn(null);

      JSONObject result = McpToolRouterSupport.buildDiscoverSpec(spec, "W", null);
      assertFalse(result.has("description"));
    }

    @Test
    void entitiesArrayIsIncludedWhenProvided() throws Exception {
      SFSpec spec = mock(SFSpec.class);
      when(spec.getName()).thenReturn("test");
      when(spec.getDescription()).thenReturn(null);
      JSONArray entities = new JSONArray();
      entities.put(new JSONObject().put("name", "header"));

      JSONObject result = McpToolRouterSupport.buildDiscoverSpec(spec, "W", entities);
      assertTrue(result.has("entities"));
      assertEquals(1, result.getJSONArray("entities").length());
    }

    @Test
    void nullEntitiesOmitsKey() throws Exception {
      SFSpec spec = mock(SFSpec.class);
      when(spec.getName()).thenReturn("test");
      when(spec.getDescription()).thenReturn(null);

      JSONObject result = McpToolRouterSupport.buildDiscoverSpec(spec, "W", null);
      assertFalse(result.has("entities"));
    }

    @Test
    void reportTypeAddsIsReportTrue() throws Exception {
      SFSpec spec = mock(SFSpec.class);
      when(spec.getName()).thenReturn("aging");
      when(spec.getDescription()).thenReturn(null);

      JSONObject result = McpToolRouterSupport.buildDiscoverSpec(spec, "R", null);
      assertTrue(result.getBoolean("isReport"));
    }

    @Test
    void windowTypeDoesNotAddIsReport() throws Exception {
      SFSpec spec = mock(SFSpec.class);
      when(spec.getName()).thenReturn("order");
      when(spec.getDescription()).thenReturn(null);

      JSONObject result = McpToolRouterSupport.buildDiscoverSpec(spec, "W", null);
      assertFalse(result.has("isReport"));
    }

    @Test
    void agentPromptIsIncludedWhenPresent() throws Exception {
      SFSpec spec = mock(SFSpec.class);
      when(spec.getName()).thenReturn("purchase-order");
      when(spec.getDescription()).thenReturn(null);
      when(spec.getAgentPrompt()).thenReturn("Always confirm before completing the order.");

      JSONObject result = McpToolRouterSupport.buildDiscoverSpec(spec, "W", null);
      assertEquals("Always confirm before completing the order.", result.getString("agentPrompt"));
    }

    @Test
    void blankAgentPromptIsOmitted() throws Exception {
      SFSpec spec = mock(SFSpec.class);
      when(spec.getName()).thenReturn("purchase-order");
      when(spec.getDescription()).thenReturn(null);
      when(spec.getAgentPrompt()).thenReturn("   ");

      JSONObject result = McpToolRouterSupport.buildDiscoverSpec(spec, "W", null);
      assertFalse(result.has("agentPrompt"));
    }

    @Test
    void nullAgentPromptIsOmitted() throws Exception {
      SFSpec spec = mock(SFSpec.class);
      when(spec.getName()).thenReturn("purchase-order");
      when(spec.getDescription()).thenReturn(null);
      when(spec.getAgentPrompt()).thenReturn(null);

      JSONObject result = McpToolRouterSupport.buildDiscoverSpec(spec, "W", null);
      assertFalse(result.has("agentPrompt"));
    }
  }

  @Test
  void schemaFieldsIncludeAgentPromptWhenProvided() throws Exception {
    org.openbravo.model.ad.ui.Tab tab = mock(org.openbravo.model.ad.ui.Tab.class);
    org.openbravo.model.ad.datamodel.Table table = mock(org.openbravo.model.ad.datamodel.Table.class);
    org.openbravo.model.ad.datamodel.Column col =
        mock(org.openbravo.model.ad.datamodel.Column.class);

    when(tab.getTable()).thenReturn(table);
    when(table.getDBTableName()).thenReturn("C_Order");
    when(table.getADColumnList()).thenReturn(java.util.List.of(col));
    when(col.getId()).thenReturn("COL1");
    when(col.isActive()).thenReturn(true);
    when(col.getDBColumnName()).thenReturn("C_BPartner_ID");
    when(col.getName()).thenReturn("Business Partner");
    when(col.isMandatory()).thenReturn(false);
    when(col.isUseAutomaticSequence()).thenReturn(false);
    when(col.getDefaultValue()).thenReturn(null);

    JSONArray fields = McpToolRouterSupport.buildSchemaFieldsArray(
        tab,
        null,
        java.util.Map.of(),
        java.util.Map.of("COL1", "  Pick the correct customer.  "),
        java.util.Set.of(),
        java.util.Set.of());

    JSONObject field = fields.getJSONObject(0);
    assertEquals("Pick the correct customer.", field.getString("agentPrompt"));
  }

  // ─── isMandatoryValueMissing ────────────────────────────────────────

  @Nested
  @DisplayName("isMandatoryValueMissing")
  class IsMandatoryValueMissing {

    @Test
    void missingKeyReturnsTrue() throws Exception {
      JSONObject body = new JSONObject();
      assertTrue(McpToolRouterSupport.isMandatoryValueMissing(body, "name"));
    }

    @Test
    void nullValueReturnsTrue() throws Exception {
      JSONObject body = new JSONObject();
      body.put("name", JSONObject.NULL);
      assertTrue(McpToolRouterSupport.isMandatoryValueMissing(body, "name"));
    }

    @Test
    void emptyStringReturnsTrue() throws Exception {
      JSONObject body = new JSONObject();
      body.put("name", "");
      assertTrue(McpToolRouterSupport.isMandatoryValueMissing(body, "name"));
    }

    @Test
    void nonEmptyStringReturnsFalse() throws Exception {
      JSONObject body = new JSONObject();
      body.put("name", "Test");
      assertFalse(McpToolRouterSupport.isMandatoryValueMissing(body, "name"));
    }

    @Test
    void numericValueReturnsFalse() throws Exception {
      JSONObject body = new JSONObject();
      body.put("amount", 42);
      assertFalse(McpToolRouterSupport.isMandatoryValueMissing(body, "amount"));
    }

    @Test
    void booleanValueReturnsFalse() throws Exception {
      JSONObject body = new JSONObject();
      body.put("active", true);
      assertFalse(McpToolRouterSupport.isMandatoryValueMissing(body, "active"));
    }
  }

  // ─── coercePrimitiveFieldValue ──────────────────────────────────────

  @Nested
  @DisplayName("coercePrimitiveFieldValue")
  class CoercePrimitiveFieldValue {

    @Mock private Logger log;

    @Test
    void coercesStringToLong() throws Exception {
      Property prop = mock(Property.class);
      when(prop.getPrimitiveObjectType()).thenReturn((Class) Long.class);
      JSONObject body = new JSONObject();
      body.put("lineNo", "42");

      McpToolRouterSupport.coercePrimitiveFieldValue(body, "lineNo", prop, log);
      assertEquals(42L, body.getLong("lineNo"));
    }

    @Test
    void coercesDecimalStringToLongByTruncating() throws Exception {
      Property prop = mock(Property.class);
      when(prop.getPrimitiveObjectType()).thenReturn((Class) Long.class);
      JSONObject body = new JSONObject();
      body.put("seqNo", "10.0");

      McpToolRouterSupport.coercePrimitiveFieldValue(body, "seqNo", prop, log);
      assertEquals(10L, body.getLong("seqNo"));
    }

    @Test
    void coercesStringToBigDecimal() throws Exception {
      Property prop = mock(Property.class);
      when(prop.getPrimitiveObjectType()).thenReturn((Class) BigDecimal.class);
      JSONObject body = new JSONObject();
      body.put("amount", "123.45");

      McpToolRouterSupport.coercePrimitiveFieldValue(body, "amount", prop, log);
      assertEquals(new BigDecimal("123.45"), body.get("amount"));
    }

    @Test
    void coercesYStringToTrue() throws Exception {
      Property prop = mock(Property.class);
      when(prop.getPrimitiveObjectType()).thenReturn((Class) Boolean.class);
      JSONObject body = new JSONObject();
      body.put("active", "Y");

      McpToolRouterSupport.coercePrimitiveFieldValue(body, "active", prop, log);
      assertTrue(body.getBoolean("active"));
    }

    @Test
    void coercesTrueStringToTrue() throws Exception {
      Property prop = mock(Property.class);
      when(prop.getPrimitiveObjectType()).thenReturn((Class) Boolean.class);
      JSONObject body = new JSONObject();
      body.put("active", "true");

      McpToolRouterSupport.coercePrimitiveFieldValue(body, "active", prop, log);
      assertTrue(body.getBoolean("active"));
    }

    @Test
    void coercesNStringToFalse() throws Exception {
      Property prop = mock(Property.class);
      when(prop.getPrimitiveObjectType()).thenReturn((Class) Boolean.class);
      JSONObject body = new JSONObject();
      body.put("active", "N");

      McpToolRouterSupport.coercePrimitiveFieldValue(body, "active", prop, log);
      assertFalse(body.getBoolean("active"));
    }

    @Test
    void nonStringValueIsNotCoerced() throws Exception {
      Property prop = mock(Property.class);
      JSONObject body = new JSONObject();
      body.put("count", 5);

      McpToolRouterSupport.coercePrimitiveFieldValue(body, "count", prop, log);
      assertEquals(5, body.getInt("count"));
    }

    @Test
    void emptyStringIsNotCoerced() throws Exception {
      Property prop = mock(Property.class);
      JSONObject body = new JSONObject();
      body.put("name", "");

      McpToolRouterSupport.coercePrimitiveFieldValue(body, "name", prop, log);
      assertEquals("", body.getString("name"));
    }
  }

  // ─── buildMissingFieldInfo ──────────────────────────────────────────

  @Nested
  @DisplayName("buildMissingFieldInfo")
  class BuildMissingFieldInfo {

    @Test
    void foreignKeyFieldHasSelectorInfo() throws Exception {
      org.openbravo.model.ad.datamodel.Column col = mock(org.openbravo.model.ad.datamodel.Column.class);
      org.openbravo.model.ad.domain.Reference ref = mock(org.openbravo.model.ad.domain.Reference.class);
      when(col.getReference()).thenReturn(ref);
      when(ref.getId()).thenReturn("19"); // TableDir
      when(col.getDBColumnName()).thenReturn("C_BPartner_ID");
      when(col.getName()).thenReturn("Business Partner");

      java.util.Set<String> selectorRefs = java.util.Set.of("19", "18", "30",
          NeoSelectorService.REF_OBUISEL);

      JSONObject result = McpToolRouterSupport.buildMissingFieldInfo(col, "businessPartner",
          selectorRefs);

      assertEquals("businessPartner", result.getString("name"));
      assertEquals("C_BPartner_ID", result.getString("column"));
      assertEquals("foreignKey", result.getString("type"));
      assertTrue(result.getBoolean("hasSelector"));
      assertEquals("Business Partner", result.getString("label"));
    }

    @Test
    void nonForeignKeyFieldHasOtherType() throws Exception {
      org.openbravo.model.ad.datamodel.Column col = mock(org.openbravo.model.ad.datamodel.Column.class);
      org.openbravo.model.ad.domain.Reference ref = mock(org.openbravo.model.ad.domain.Reference.class);
      when(col.getReference()).thenReturn(ref);
      when(ref.getId()).thenReturn("10"); // String
      when(col.getDBColumnName()).thenReturn("Name");
      when(col.getName()).thenReturn("Name");

      java.util.Set<String> selectorRefs = java.util.Set.of("19", "18");

      JSONObject result = McpToolRouterSupport.buildMissingFieldInfo(col, "name", selectorRefs);

      assertEquals("other", result.getString("type"));
      assertFalse(result.has("hasSelector"));
    }
  }

  // ─── isReadOnlyColumn (private, tested via buildSchemaField indirectly) ──

  @Nested
  @DisplayName("isReadOnlyColumn")
  class IsReadOnlyColumn {

    @Test
    void pkColumnIsReadOnly() throws Exception {
      org.openbravo.model.ad.ui.Tab tab = mock(org.openbravo.model.ad.ui.Tab.class);
      org.openbravo.model.ad.datamodel.Table table = mock(org.openbravo.model.ad.datamodel.Table.class);
      when(tab.getTable()).thenReturn(table);
      when(table.getDBTableName()).thenReturn("C_Order");

      org.openbravo.model.ad.datamodel.Column col = mock(org.openbravo.model.ad.datamodel.Column.class);
      when(col.getDBColumnName()).thenReturn("C_Order_ID");
      when(col.isUseAutomaticSequence()).thenReturn(false);

      boolean result = (boolean) invokeStatic("isReadOnlyColumn",
          new Class<?>[]{ org.openbravo.model.ad.ui.Tab.class,
              org.openbravo.model.ad.datamodel.Column.class },
          tab, col);
      assertTrue(result);
    }

    @Test
    void documentNoIsReadOnly() throws Exception {
      org.openbravo.model.ad.ui.Tab tab = mock(org.openbravo.model.ad.ui.Tab.class);
      org.openbravo.model.ad.datamodel.Table table = mock(org.openbravo.model.ad.datamodel.Table.class);
      when(tab.getTable()).thenReturn(table);
      when(table.getDBTableName()).thenReturn("C_Invoice");

      org.openbravo.model.ad.datamodel.Column col = mock(org.openbravo.model.ad.datamodel.Column.class);
      when(col.getDBColumnName()).thenReturn("DocumentNo");
      when(col.isUseAutomaticSequence()).thenReturn(false);

      boolean result = (boolean) invokeStatic("isReadOnlyColumn",
          new Class<?>[]{ org.openbravo.model.ad.ui.Tab.class,
              org.openbravo.model.ad.datamodel.Column.class },
          tab, col);
      assertTrue(result);
    }

    @Test
    void autoSequenceColumnIsReadOnly() throws Exception {
      org.openbravo.model.ad.ui.Tab tab = mock(org.openbravo.model.ad.ui.Tab.class);
      org.openbravo.model.ad.datamodel.Table table = mock(org.openbravo.model.ad.datamodel.Table.class);
      when(tab.getTable()).thenReturn(table);
      when(table.getDBTableName()).thenReturn("C_Order");

      org.openbravo.model.ad.datamodel.Column col = mock(org.openbravo.model.ad.datamodel.Column.class);
      when(col.getDBColumnName()).thenReturn("RegularCol");
      when(col.isUseAutomaticSequence()).thenReturn(true);

      boolean result = (boolean) invokeStatic("isReadOnlyColumn",
          new Class<?>[]{ org.openbravo.model.ad.ui.Tab.class,
              org.openbravo.model.ad.datamodel.Column.class },
          tab, col);
      assertTrue(result);
    }

    @Test
    void regularColumnIsNotReadOnly() throws Exception {
      org.openbravo.model.ad.ui.Tab tab = mock(org.openbravo.model.ad.ui.Tab.class);
      org.openbravo.model.ad.datamodel.Table table = mock(org.openbravo.model.ad.datamodel.Table.class);
      when(tab.getTable()).thenReturn(table);
      when(table.getDBTableName()).thenReturn("C_Order");

      org.openbravo.model.ad.datamodel.Column col = mock(org.openbravo.model.ad.datamodel.Column.class);
      when(col.getDBColumnName()).thenReturn("Description");
      when(col.isUseAutomaticSequence()).thenReturn(false);

      boolean result = (boolean) invokeStatic("isReadOnlyColumn",
          new Class<?>[]{ org.openbravo.model.ad.ui.Tab.class,
              org.openbravo.model.ad.datamodel.Column.class },
          tab, col);
      assertFalse(result);
    }
  }

  // ─── resolvePropertyName ─────────────────────────────────────────────

  @Nested
  @DisplayName("resolvePropertyName")
  class ResolvePropertyName {

    @Test
    void nullEntityReturnsDatabaseColumnName() throws Exception {
      String result = (String) invokeStatic("resolvePropertyName",
          new Class<?>[]{ org.openbravo.base.model.Entity.class, String.class },
          null, "C_BPartner_ID");
      assertEquals("C_BPartner_ID", result);
    }

    @Test
    void resolvedPropertyReturnsPropertyName() throws Exception {
      org.openbravo.base.model.Entity entity = mock(org.openbravo.base.model.Entity.class);
      Property prop = mock(Property.class);
      when(entity.getPropertyByColumnName("C_BPartner_ID")).thenReturn(prop);
      when(prop.getName()).thenReturn("businessPartner");

      String result = (String) invokeStatic("resolvePropertyName",
          new Class<?>[]{ org.openbravo.base.model.Entity.class, String.class },
          entity, "C_BPartner_ID");
      assertEquals("businessPartner", result);
    }

    @Test
    void nullPropertyReturnsDatabaseColumnName() throws Exception {
      org.openbravo.base.model.Entity entity = mock(org.openbravo.base.model.Entity.class);
      when(entity.getPropertyByColumnName("Unknown_Col")).thenReturn(null);

      String result = (String) invokeStatic("resolvePropertyName",
          new Class<?>[]{ org.openbravo.base.model.Entity.class, String.class },
          entity, "Unknown_Col");
      assertEquals("Unknown_Col", result);
    }

    @Test
    void exceptionReturnsDatabaseColumnName() throws Exception {
      org.openbravo.base.model.Entity entity = mock(org.openbravo.base.model.Entity.class);
      when(entity.getPropertyByColumnName("Bad_Col")).thenThrow(new RuntimeException("fail"));

      String result = (String) invokeStatic("resolvePropertyName",
          new Class<?>[]{ org.openbravo.base.model.Entity.class, String.class },
          entity, "Bad_Col");
      assertEquals("Bad_Col", result);
    }
  }

  // ─── addDefaultExpression ───────────────────────────────────────────

  @Nested
  @DisplayName("addDefaultExpression")
  class AddDefaultExpression {

    @Test
    void addsNonBlankDefault() throws Exception {
      org.openbravo.model.ad.datamodel.Column col = mock(org.openbravo.model.ad.datamodel.Column.class);
      when(col.getDefaultValue()).thenReturn("@SQL=SELECT 1");

      JSONObject fieldObj = new JSONObject();
      invokeStatic("addDefaultExpression",
          new Class<?>[]{ JSONObject.class, org.openbravo.model.ad.datamodel.Column.class },
          fieldObj, col);
      assertEquals("@SQL=SELECT 1", fieldObj.getString("defaultExpression"));
    }

    @Test
    void nullDefaultOmitsKey() throws Exception {
      org.openbravo.model.ad.datamodel.Column col = mock(org.openbravo.model.ad.datamodel.Column.class);
      when(col.getDefaultValue()).thenReturn(null);

      JSONObject fieldObj = new JSONObject();
      invokeStatic("addDefaultExpression",
          new Class<?>[]{ JSONObject.class, org.openbravo.model.ad.datamodel.Column.class },
          fieldObj, col);
      assertFalse(fieldObj.has("defaultExpression"));
    }

    @Test
    void blankDefaultOmitsKey() throws Exception {
      org.openbravo.model.ad.datamodel.Column col = mock(org.openbravo.model.ad.datamodel.Column.class);
      when(col.getDefaultValue()).thenReturn("   ");

      JSONObject fieldObj = new JSONObject();
      invokeStatic("addDefaultExpression",
          new Class<?>[]{ JSONObject.class, org.openbravo.model.ad.datamodel.Column.class },
          fieldObj, col);
      assertFalse(fieldObj.has("defaultExpression"));
    }
  }

  // ─── addVisibility ──────────────────────────────────────────────────

  @Nested
  @DisplayName("addVisibility")
  class AddVisibility {

    @Test
    void nullVisibilityOmitsKeys() throws Exception {
      JSONObject fieldObj = new JSONObject();
      invokeStatic("addVisibility",
          new Class<?>[]{ JSONObject.class, String.class, boolean.class },
          fieldObj, null, true);
      assertFalse(fieldObj.has("visibility"));
      assertFalse(fieldObj.has("userRequired"));
    }

    @Test
    void editableVisibilityWithMandatorySetsUserRequired() throws Exception {
      JSONObject fieldObj = new JSONObject();
      invokeStatic("addVisibility",
          new Class<?>[]{ JSONObject.class, String.class, boolean.class },
          fieldObj, "editable", true);
      assertEquals("editable", fieldObj.getString("visibility"));
      assertTrue(fieldObj.getBoolean("userRequired"));
    }

    @Test
    void editableVisibilityWithNonMandatorySetsFalse() throws Exception {
      JSONObject fieldObj = new JSONObject();
      invokeStatic("addVisibility",
          new Class<?>[]{ JSONObject.class, String.class, boolean.class },
          fieldObj, "editable", false);
      assertFalse(fieldObj.getBoolean("userRequired"));
    }

    @Test
    void hiddenVisibilitySetsUserRequiredFalse() throws Exception {
      JSONObject fieldObj = new JSONObject();
      invokeStatic("addVisibility",
          new Class<?>[]{ JSONObject.class, String.class, boolean.class },
          fieldObj, "hidden", true);
      assertEquals("hidden", fieldObj.getString("visibility"));
      assertFalse(fieldObj.getBoolean("userRequired"));
    }
  }

  // ─── addSelectorInfo ────────────────────────────────────────────────

  @Nested
  @DisplayName("addSelectorInfo")
  class AddSelectorInfo {

    @Test
    void selectorRefAddsHasSelectorAndType() throws Exception {
      JSONObject fieldObj = new JSONObject();
      java.util.Set<String> selectorRefs = java.util.Set.of("19", "18", "30");

      invokeStatic("addSelectorInfo",
          new Class<?>[]{ JSONObject.class, String.class, java.util.Set.class },
          fieldObj, "19", selectorRefs);

      assertTrue(fieldObj.getBoolean("hasSelector"));
      assertEquals("TableDir", fieldObj.getString("selectorType"));
    }

    @Test
    void nonSelectorRefOmitsKeys() throws Exception {
      JSONObject fieldObj = new JSONObject();
      java.util.Set<String> selectorRefs = java.util.Set.of("19", "18");

      invokeStatic("addSelectorInfo",
          new Class<?>[]{ JSONObject.class, String.class, java.util.Set.class },
          fieldObj, "10", selectorRefs);

      assertFalse(fieldObj.has("hasSelector"));
    }

    @Test
    void nullRefIdOmitsKeys() throws Exception {
      JSONObject fieldObj = new JSONObject();
      java.util.Set<String> selectorRefs = java.util.Set.of("19");

      invokeStatic("addSelectorInfo",
          new Class<?>[]{ JSONObject.class, String.class, java.util.Set.class },
          fieldObj, null, selectorRefs);

      assertFalse(fieldObj.has("hasSelector"));
    }
  }

  // ─── shouldIncludeSchemaColumn ──────────────────────────────────────

  @Nested
  @DisplayName("shouldIncludeSchemaColumn")
  class ShouldIncludeSchemaColumn {

    @Test
    void activeNonSystemColumnIsIncluded() throws Exception {
      org.openbravo.model.ad.datamodel.Column col = mock(org.openbravo.model.ad.datamodel.Column.class);
      when(col.isActive()).thenReturn(true);
      when(col.getDBColumnName()).thenReturn("Name");

      java.util.Set<String> systemCols = java.util.Set.of("AD_CLIENT_ID", "AD_ORG_ID");
      boolean result = (boolean) invokeStatic("shouldIncludeSchemaColumn",
          new Class<?>[]{ org.openbravo.model.ad.datamodel.Column.class, java.util.Set.class },
          col, systemCols);
      assertTrue(result);
    }

    @Test
    void inactiveColumnIsExcluded() throws Exception {
      org.openbravo.model.ad.datamodel.Column col = mock(org.openbravo.model.ad.datamodel.Column.class);
      when(col.isActive()).thenReturn(false);
      when(col.getDBColumnName()).thenReturn("Name");

      boolean result = (boolean) invokeStatic("shouldIncludeSchemaColumn",
          new Class<?>[]{ org.openbravo.model.ad.datamodel.Column.class, java.util.Set.class },
          col, java.util.Set.of());
      assertFalse(result);
    }

    @Test
    void systemColumnIsExcluded() throws Exception {
      org.openbravo.model.ad.datamodel.Column col = mock(org.openbravo.model.ad.datamodel.Column.class);
      when(col.isActive()).thenReturn(true);
      when(col.getDBColumnName()).thenReturn("ad_client_id");

      java.util.Set<String> systemCols = java.util.Set.of("AD_CLIENT_ID", "AD_ORG_ID");
      boolean result = (boolean) invokeStatic("shouldIncludeSchemaColumn",
          new Class<?>[]{ org.openbravo.model.ad.datamodel.Column.class, java.util.Set.class },
          col, systemCols);
      assertFalse(result);
    }
  }

  // ─── resolveMandatoryProperty ───────────────────────────────────────

  @Nested
  @DisplayName("resolveMandatoryProperty")
  class ResolveMandatoryProperty {

    @Test
    void inactiveColumnReturnsNull() {
      org.openbravo.model.ad.ui.Tab tab = mock(org.openbravo.model.ad.ui.Tab.class);
      org.openbravo.base.model.Entity entity = mock(org.openbravo.base.model.Entity.class);
      org.openbravo.model.ad.datamodel.Column col = mock(org.openbravo.model.ad.datamodel.Column.class);
      when(col.isActive()).thenReturn(false);

      assertNull(McpToolRouterSupport.resolveMandatoryProperty(tab, entity, col, java.util.Set.of()));
    }

    @Test
    void nonMandatoryColumnReturnsNull() {
      org.openbravo.model.ad.ui.Tab tab = mock(org.openbravo.model.ad.ui.Tab.class);
      org.openbravo.base.model.Entity entity = mock(org.openbravo.base.model.Entity.class);
      org.openbravo.model.ad.datamodel.Column col = mock(org.openbravo.model.ad.datamodel.Column.class);
      when(col.isActive()).thenReturn(true);
      when(col.isMandatory()).thenReturn(false);

      assertNull(McpToolRouterSupport.resolveMandatoryProperty(tab, entity, col, java.util.Set.of()));
    }

    @Test
    void pkColumnReturnsNull() {
      org.openbravo.model.ad.ui.Tab tab = mock(org.openbravo.model.ad.ui.Tab.class);
      org.openbravo.model.ad.datamodel.Table table = mock(org.openbravo.model.ad.datamodel.Table.class);
      when(tab.getTable()).thenReturn(table);
      when(table.getDBTableName()).thenReturn("C_Order");

      org.openbravo.base.model.Entity entity = mock(org.openbravo.base.model.Entity.class);
      org.openbravo.model.ad.datamodel.Column col = mock(org.openbravo.model.ad.datamodel.Column.class);
      when(col.isActive()).thenReturn(true);
      when(col.isMandatory()).thenReturn(true);
      when(col.getDBColumnName()).thenReturn("C_Order_ID");

      assertNull(McpToolRouterSupport.resolveMandatoryProperty(tab, entity, col, java.util.Set.of()));
    }

    @Test
    void systemColumnReturnsNull() {
      org.openbravo.model.ad.ui.Tab tab = mock(org.openbravo.model.ad.ui.Tab.class);
      org.openbravo.model.ad.datamodel.Table table = mock(org.openbravo.model.ad.datamodel.Table.class);
      when(tab.getTable()).thenReturn(table);
      when(table.getDBTableName()).thenReturn("C_Order");

      org.openbravo.base.model.Entity entity = mock(org.openbravo.base.model.Entity.class);
      org.openbravo.model.ad.datamodel.Column col = mock(org.openbravo.model.ad.datamodel.Column.class);
      when(col.isActive()).thenReturn(true);
      when(col.isMandatory()).thenReturn(true);
      when(col.getDBColumnName()).thenReturn("AD_Client_ID");

      java.util.Set<String> systemCols = java.util.Set.of("AD_CLIENT_ID");
      assertNull(McpToolRouterSupport.resolveMandatoryProperty(tab, entity, col, systemCols));
    }

    @Test
    void mandatoryNonSystemColumnReturnsProperty() {
      org.openbravo.model.ad.ui.Tab tab = mock(org.openbravo.model.ad.ui.Tab.class);
      org.openbravo.model.ad.datamodel.Table table = mock(org.openbravo.model.ad.datamodel.Table.class);
      when(tab.getTable()).thenReturn(table);
      when(table.getDBTableName()).thenReturn("C_Order");

      org.openbravo.base.model.Entity entity = mock(org.openbravo.base.model.Entity.class);
      Property prop = mock(Property.class);
      when(entity.getPropertyByColumnName("C_BPartner_ID")).thenReturn(prop);

      org.openbravo.model.ad.datamodel.Column col = mock(org.openbravo.model.ad.datamodel.Column.class);
      when(col.isActive()).thenReturn(true);
      when(col.isMandatory()).thenReturn(true);
      when(col.getDBColumnName()).thenReturn("C_BPartner_ID");

      Property result = McpToolRouterSupport.resolveMandatoryProperty(tab, entity, col, java.util.Set.of());
      assertEquals(prop, result);
    }

    @Test
    void exceptionInPropertyLookupReturnsNull() {
      org.openbravo.model.ad.ui.Tab tab = mock(org.openbravo.model.ad.ui.Tab.class);
      org.openbravo.model.ad.datamodel.Table table = mock(org.openbravo.model.ad.datamodel.Table.class);
      when(tab.getTable()).thenReturn(table);
      when(table.getDBTableName()).thenReturn("C_Order");

      org.openbravo.base.model.Entity entity = mock(org.openbravo.base.model.Entity.class);
      when(entity.getPropertyByColumnName("BadCol")).thenThrow(new RuntimeException("boom"));

      org.openbravo.model.ad.datamodel.Column col = mock(org.openbravo.model.ad.datamodel.Column.class);
      when(col.isActive()).thenReturn(true);
      when(col.isMandatory()).thenReturn(true);
      when(col.getDBColumnName()).thenReturn("BadCol");

      assertNull(McpToolRouterSupport.resolveMandatoryProperty(tab, entity, col, java.util.Set.of()));
    }
  }

  // ─── findColumn ─────────────────────────────────────────────────────

  @Nested
  @DisplayName("findColumn")
  class FindColumn {

    @Test
    void findsByDbColumnNameCaseInsensitive() {
      org.openbravo.model.ad.ui.Tab tab = mock(org.openbravo.model.ad.ui.Tab.class);
      org.openbravo.model.ad.datamodel.Table table = mock(org.openbravo.model.ad.datamodel.Table.class);
      when(tab.getTable()).thenReturn(table);

      org.openbravo.model.ad.datamodel.Column col = mock(org.openbravo.model.ad.datamodel.Column.class);
      when(col.getDBColumnName()).thenReturn("C_BPartner_ID");
      when(table.getADColumnList()).thenReturn(java.util.List.of(col));

      org.openbravo.model.ad.datamodel.Column result =
          McpToolRouterSupport.findColumn(tab, "c_bpartner_id", null);
      assertEquals(col, result);
    }

    @Test
    void findsByPropertyNameFallback() {
      org.openbravo.model.ad.ui.Tab tab = mock(org.openbravo.model.ad.ui.Tab.class);
      org.openbravo.model.ad.datamodel.Table table = mock(org.openbravo.model.ad.datamodel.Table.class);
      when(tab.getTable()).thenReturn(table);

      org.openbravo.model.ad.datamodel.Column col = mock(org.openbravo.model.ad.datamodel.Column.class);
      when(col.getDBColumnName()).thenReturn("C_BPartner_ID");
      when(table.getADColumnList()).thenReturn(java.util.List.of(col));

      org.openbravo.base.model.Entity entity = mock(org.openbravo.base.model.Entity.class);
      Property prop = mock(Property.class);
      when(entity.getPropertyByColumnName("C_BPartner_ID")).thenReturn(prop);
      when(prop.getName()).thenReturn("businessPartner");

      org.openbravo.model.ad.datamodel.Column result =
          McpToolRouterSupport.findColumn(tab, "businessPartner", entity);
      assertEquals(col, result);
    }

    @Test
    void returnsNullWhenNotFound() {
      org.openbravo.model.ad.ui.Tab tab = mock(org.openbravo.model.ad.ui.Tab.class);
      org.openbravo.model.ad.datamodel.Table table = mock(org.openbravo.model.ad.datamodel.Table.class);
      when(tab.getTable()).thenReturn(table);

      org.openbravo.model.ad.datamodel.Column col = mock(org.openbravo.model.ad.datamodel.Column.class);
      when(col.getDBColumnName()).thenReturn("Other_Col");
      when(table.getADColumnList()).thenReturn(java.util.List.of(col));

      assertNull(McpToolRouterSupport.findColumn(tab, "notExists", null));
    }

    @Test
    void nullEntitySkipsPropertyFallback() {
      org.openbravo.model.ad.ui.Tab tab = mock(org.openbravo.model.ad.ui.Tab.class);
      org.openbravo.model.ad.datamodel.Table table = mock(org.openbravo.model.ad.datamodel.Table.class);
      when(tab.getTable()).thenReturn(table);

      org.openbravo.model.ad.datamodel.Column col = mock(org.openbravo.model.ad.datamodel.Column.class);
      when(col.getDBColumnName()).thenReturn("SomeCol");
      when(table.getADColumnList()).thenReturn(java.util.List.of(col));

      assertNull(McpToolRouterSupport.findColumn(tab, "notMatching", null));
    }
  }

  // ─── addButtonInfo ──────────────────────────────────────────────────

  @Nested
  @DisplayName("addButtonInfo")
  class AddButtonInfo {

    private MockedStatic<NeoAccessHelper> accessHelperMock;

    @BeforeEach
    void setUp() {
      accessHelperMock = mockStatic(NeoAccessHelper.class);
    }

    @AfterEach
    void tearDown() {
      accessHelperMock.close();
    }

    @Test
    @DisplayName("buttonColumnWithObuiappProcessEmitsAllFields")
    void buttonColumnWithObuiappProcessEmitsAllFields() throws Exception {
      org.openbravo.model.ad.datamodel.Column col = mock(
          org.openbravo.model.ad.datamodel.Column.class);
      org.openbravo.client.application.Process obuiappProcess = mock(
          org.openbravo.client.application.Process.class);

      when(col.getDBColumnName()).thenReturn("Processed");
      when(col.getProcess()).thenReturn(null);
      when(col.getOBUIAPPProcess()).thenReturn(obuiappProcess);
      when(obuiappProcess.getName()).thenReturn("Complete Order");
      when(obuiappProcess.getId()).thenReturn("OBUIAPP-PROC-001");

      JSONObject fieldObj = new JSONObject();
      invokeStatic("addButtonInfo",
          new Class<?>[]{ JSONObject.class, org.openbravo.model.ad.datamodel.Column.class },
          fieldObj, col);

      assertEquals("Y", fieldObj.getString("triggerValue"));
      assertEquals("Processed", fieldObj.getString("action"));
      assertEquals("neo_action", fieldObj.getString("invokeVia"));
      assertEquals("OBUIAPP", fieldObj.getString("processType"));
      assertEquals("Complete Order", fieldObj.getString("processName"));
      assertEquals("OBUIAPP-PROC-001", fieldObj.getString("processId"));
    }

    @Test
    @DisplayName("buttonColumnWithClassicProcessEmitsAllFields")
    void buttonColumnWithClassicProcessEmitsAllFields() throws Exception {
      org.openbravo.model.ad.datamodel.Column col = mock(
          org.openbravo.model.ad.datamodel.Column.class);
      Process classicProcess = mock(Process.class);

      when(col.getDBColumnName()).thenReturn("DocAction");
      when(col.getProcess()).thenReturn(classicProcess);
      when(col.getOBUIAPPProcess()).thenReturn(null);
      when(classicProcess.getName()).thenReturn("Post Document");
      when(classicProcess.getId()).thenReturn("CLASSIC-PROC-001");

      JSONObject fieldObj = new JSONObject();
      invokeStatic("addButtonInfo",
          new Class<?>[]{ JSONObject.class, org.openbravo.model.ad.datamodel.Column.class },
          fieldObj, col);

      assertEquals("Y", fieldObj.getString("triggerValue"));
      assertEquals("DocAction", fieldObj.getString("action"));
      assertEquals("neo_action", fieldObj.getString("invokeVia"));
      assertEquals("Classic", fieldObj.getString("processType"));
      assertEquals("Post Document", fieldObj.getString("processName"));
      assertEquals("CLASSIC-PROC-001", fieldObj.getString("processId"));
    }

    @Test
    @DisplayName("buttonColumnWithNoProcessEmitsOnlyTrigger")
    void buttonColumnWithNoProcessEmitsOnlyTrigger() throws Exception {
      org.openbravo.model.ad.datamodel.Column col = mock(
          org.openbravo.model.ad.datamodel.Column.class);

      when(col.getDBColumnName()).thenReturn("Posted");
      when(col.getProcess()).thenReturn(null);
      when(col.getOBUIAPPProcess()).thenReturn(null);
      accessHelperMock.when(
          () -> NeoAccessHelper.resolveFallbackObuiappProcess(col)).thenReturn(null);

      JSONObject fieldObj = new JSONObject();
      invokeStatic("addButtonInfo",
          new Class<?>[]{ JSONObject.class, org.openbravo.model.ad.datamodel.Column.class },
          fieldObj, col);

      assertEquals("Y", fieldObj.getString("triggerValue"));
      assertEquals("Posted", fieldObj.getString("action"));
      assertEquals("neo_action", fieldObj.getString("invokeVia"));
      assertFalse(fieldObj.has("processType"));
      assertFalse(fieldObj.has("processName"));
      assertFalse(fieldObj.has("processId"));
    }

    @Test
    @DisplayName("buildSchemaFieldButtonIncludesTriggerValue")
    void buildSchemaFieldButtonIncludesTriggerValue() throws Exception {
      // Set up column with ref "28" (button)
      org.openbravo.model.ad.datamodel.Column col = mock(
          org.openbravo.model.ad.datamodel.Column.class);
      org.openbravo.model.ad.domain.Reference ref = mock(
          org.openbravo.model.ad.domain.Reference.class);
      org.openbravo.model.ad.ui.Tab tab = mock(org.openbravo.model.ad.ui.Tab.class);
      org.openbravo.model.ad.datamodel.Table table = mock(
          org.openbravo.model.ad.datamodel.Table.class);

      when(ref.getId()).thenReturn("28");
      when(col.getReference()).thenReturn(ref);
      when(col.getDBColumnName()).thenReturn("Processed");
      when(col.getName()).thenReturn("Processed");
      when(col.isMandatory()).thenReturn(false);
      when(col.isUseAutomaticSequence()).thenReturn(false);
      when(col.getDefaultValue()).thenReturn(null);
      when(col.getProcess()).thenReturn(null);
      when(col.getOBUIAPPProcess()).thenReturn(null);
      when(col.getId()).thenReturn("col-processed-id");
      when(tab.getTable()).thenReturn(table);
      when(table.getDBTableName()).thenReturn("C_Order");

      accessHelperMock.when(
          () -> NeoAccessHelper.resolveFallbackObuiappProcess(col)).thenReturn(null);

      JSONObject result = (JSONObject) invokeStatic("buildSchemaField",
          new Class<?>[]{ org.openbravo.model.ad.datamodel.Column.class,
              org.openbravo.model.ad.ui.Tab.class,
              org.openbravo.base.model.Entity.class,
              java.util.Map.class,
              java.util.Map.class,
              java.util.Set.class },
          col, tab, null,
          new java.util.HashMap<>(),
          new java.util.HashMap<>(),
          new java.util.HashSet<>());

      assertEquals("button", result.getString("type"));
      assertEquals("Y", result.getString("triggerValue"));
      assertEquals("Processed", result.getString("action"));
      assertEquals("neo_action", result.getString("invokeVia"));
    }
  }

  // ─── buildSchemaField — businessCritical flag ───────────────────────

  @Nested
  @DisplayName("buildSchemaField — businessCritical flag")
  class BuildSchemaFieldBusinessCritical {

    private MockedStatic<NeoAccessHelper> accessHelperMock;

    @BeforeEach
    void setUp() {
      accessHelperMock = mockStatic(NeoAccessHelper.class);
    }

    @AfterEach
    void tearDown() {
      accessHelperMock.close();
    }

    private org.openbravo.model.ad.datamodel.Column buildStringColumn(String colId) {
      org.openbravo.model.ad.datamodel.Column col = mock(
          org.openbravo.model.ad.datamodel.Column.class);
      org.openbravo.model.ad.domain.Reference ref = mock(
          org.openbravo.model.ad.domain.Reference.class);
      when(ref.getId()).thenReturn("10"); // string
      when(col.getReference()).thenReturn(ref);
      when(col.getDBColumnName()).thenReturn("Description");
      when(col.getName()).thenReturn("Description");
      when(col.isMandatory()).thenReturn(false);
      when(col.isUseAutomaticSequence()).thenReturn(false);
      when(col.getDefaultValue()).thenReturn(null);
      when(col.getId()).thenReturn(colId);
      return col;
    }

    private org.openbravo.model.ad.ui.Tab buildTab() {
      org.openbravo.model.ad.ui.Tab tab = mock(org.openbravo.model.ad.ui.Tab.class);
      org.openbravo.model.ad.datamodel.Table table = mock(
          org.openbravo.model.ad.datamodel.Table.class);
      when(tab.getTable()).thenReturn(table);
      when(table.getDBTableName()).thenReturn("C_Order");
      return tab;
    }

    @Test
    @DisplayName("businessCritical true when flag set in map")
    void businessCriticalTrueWhenFlagSet() throws Exception {
      org.openbravo.model.ad.datamodel.Column col = buildStringColumn("col-desc-1");
      org.openbravo.model.ad.ui.Tab tab = buildTab();

      Map<String, Boolean> businessCriticalMap = new HashMap<>();
      businessCriticalMap.put("col-desc-1", true);

      JSONObject result = (JSONObject) invokeStatic("buildSchemaField",
          new Class<?>[]{ org.openbravo.model.ad.datamodel.Column.class,
              org.openbravo.model.ad.ui.Tab.class,
              org.openbravo.base.model.Entity.class,
              java.util.Map.class,
              java.util.Map.class,
              java.util.Set.class },
          col, tab, null,
          new java.util.HashMap<>(),
          businessCriticalMap,
          new java.util.HashSet<>());

      assertTrue(result.getBoolean("businessCritical"));
    }

    @Test
    @DisplayName("businessCritical false when flag absent from map — no NPE")
    void businessCriticalFalseWhenFlagAbsent() throws Exception {
      org.openbravo.model.ad.datamodel.Column col = buildStringColumn("col-desc-2");
      org.openbravo.model.ad.ui.Tab tab = buildTab();

      JSONObject result = (JSONObject) invokeStatic("buildSchemaField",
          new Class<?>[]{ org.openbravo.model.ad.datamodel.Column.class,
              org.openbravo.model.ad.ui.Tab.class,
              org.openbravo.base.model.Entity.class,
              java.util.Map.class,
              java.util.Map.class,
              java.util.Set.class },
          col, tab, null,
          new java.util.HashMap<>(),
          new java.util.HashMap<>(),
          new java.util.HashSet<>());

      assertFalse(result.getBoolean("businessCritical"));
    }

    @Test
    @DisplayName("businessCritical false when map contains explicit false")
    void businessCriticalFalseWhenExplicitFalse() throws Exception {
      org.openbravo.model.ad.datamodel.Column col = buildStringColumn("col-desc-3");
      org.openbravo.model.ad.ui.Tab tab = buildTab();

      Map<String, Boolean> businessCriticalMap = new HashMap<>();
      businessCriticalMap.put("col-desc-3", false);

      JSONObject result = (JSONObject) invokeStatic("buildSchemaField",
          new Class<?>[]{ org.openbravo.model.ad.datamodel.Column.class,
              org.openbravo.model.ad.ui.Tab.class,
              org.openbravo.base.model.Entity.class,
              java.util.Map.class,
              java.util.Map.class,
              java.util.Set.class },
          col, tab, null,
          new java.util.HashMap<>(),
          businessCriticalMap,
          new java.util.HashSet<>());

      assertFalse(result.getBoolean("businessCritical"));
    }
  }

  // ─── loadFieldMetadata — businessCritical mapping ───────────────────

  @Nested
  @DisplayName("loadFieldMetadata — businessCritical mapping")
  class LoadFieldMetadata {

    @Mock private OBDal mockOBDal;
    private MockedStatic<OBDal> obDalMock;

    @BeforeEach
    void setUp() {
      obDalMock = mockStatic(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(mockOBDal);
    }

    @AfterEach
    void tearDown() {
      obDalMock.close();
    }

    @SuppressWarnings("unchecked")
    private OBCriteria<SFField> mockFieldCriteria(List<SFField> fields) {
      OBCriteria<SFField> crit = mock(OBCriteria.class);
      when(mockOBDal.createCriteria(SFField.class)).thenReturn(crit);
      when(crit.list()).thenReturn(fields);
      return crit;
    }

    private SFField buildSFField(String columnId, Boolean businessCritical) {
      SFField field = mock(SFField.class);
      Column col = mock(Column.class);
      when(col.getId()).thenReturn(columnId);
      when(field.getADColumn()).thenReturn(col);
      when(field.isBusinessCritical()).thenReturn(businessCritical);
      return field;
    }

    @Test
    @DisplayName("field with isBusinessCritical=true maps to true in result map")
    void fieldWithTrueMapsToTrue() {
      SFEntity sfEntity = mock(SFEntity.class);
      when(sfEntity.getId()).thenReturn("entity-1");
      mockFieldCriteria(List.of(buildSFField("col-1", true)));

      McpToolRouterSupport.FieldMetadata meta = McpToolRouterSupport.loadFieldMetadata(sfEntity);

      assertTrue(meta.businessCriticalByColumnId.get("col-1"));
    }

    @Test
    @DisplayName("field with isBusinessCritical=false maps to false in result map")
    void fieldWithFalseMapsToFalse() {
      SFEntity sfEntity = mock(SFEntity.class);
      when(sfEntity.getId()).thenReturn("entity-2");
      mockFieldCriteria(List.of(buildSFField("col-2", false)));

      McpToolRouterSupport.FieldMetadata meta = McpToolRouterSupport.loadFieldMetadata(sfEntity);

      assertFalse(meta.businessCriticalByColumnId.get("col-2"));
    }

    @Test
    @DisplayName("field with isBusinessCritical=null maps to false — no NPE")
    void fieldWithNullMapsToFalse() {
      SFEntity sfEntity = mock(SFEntity.class);
      when(sfEntity.getId()).thenReturn("entity-3");
      mockFieldCriteria(List.of(buildSFField("col-3", null)));

      McpToolRouterSupport.FieldMetadata meta = McpToolRouterSupport.loadFieldMetadata(sfEntity);

      assertFalse(meta.businessCriticalByColumnId.get("col-3"));
    }
  }

  // ─── Helper ─────────────────────────────────────────────────────────

  private static boolean arrayContains(JSONArray array, String value) {
    for (int i = 0; i < array.length(); i++) {
      try {
        if (value.equals(array.getString(i))) {
          return true;
        }
      } catch (Exception ignored) {
        // skip
      }
    }
    return false;
  }
}
