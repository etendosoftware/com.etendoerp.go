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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

import com.etendoerp.go.schemaforge.NeoSelectorService;
import com.etendoerp.go.schemaforge.data.SFEntity;
import com.etendoerp.go.schemaforge.data.SFField;
import com.etendoerp.go.schemaforge.util.NeoAccessHelper;

/**
 * Unit tests for {@link McpSchemaFieldBuilder}.
 *
 * <p>Extracted from {@code McpToolRouterSupportTest} together with the production class
 * (ETP-4510, Sonar S1448) — covers AD_Column → JSON field mapping (type/selector inference,
 * visibility, defaults, business-critical flags, button/process metadata) and the
 * per-entity field metadata load used by neo_schema.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class McpSchemaFieldBuilderTest {

  @Test
  @DisplayName("Utility class hides its constructor")
  void utilityClassHidesConstructor() throws ReflectiveOperationException {
    Constructor<McpSchemaFieldBuilder> constructor = McpSchemaFieldBuilder.class.getDeclaredConstructor();
    assertEquals(Modifier.PRIVATE, constructor.getModifiers() & Modifier.PRIVATE);
    constructor.setAccessible(true);
    constructor.newInstance();
  }

  private static Object invokeStatic(String methodName, Class<?>[] paramTypes, Object... args)
      throws Exception {
    Method method = McpSchemaFieldBuilder.class.getDeclaredMethod(methodName, paramTypes);
    method.setAccessible(true);
    return method.invoke(null, args);
  }

  // ─── mapColumnType ──────────────────────────────────────────────────

  @Nested
  @DisplayName("mapColumnType")
  class MapColumnType {

    @Test
    void nullRefIdReturnsString() {
      assertEquals("string", McpSchemaFieldBuilder.mapColumnType(null));
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
      assertEquals(expectedType, McpSchemaFieldBuilder.mapColumnType(refId));
    }

    @Test
    void obuiselRefIdMapToForeignKey() {
      assertEquals("foreignKey",
          McpSchemaFieldBuilder.mapColumnType(NeoSelectorService.REF_OBUISEL));
    }

    @Test
    void unknownRefIdDefaultsToString() {
      assertEquals("string", McpSchemaFieldBuilder.mapColumnType("999999"));
    }
  }

  // ─── mapSelectorType ────────────────────────────────────────────────

  @Nested
  @DisplayName("mapSelectorType")
  class MapSelectorType {

    @Test
    void nullRefIdReturnsNull() {
      assertNull(McpSchemaFieldBuilder.mapSelectorType(null));
    }

    @ParameterizedTest
    @CsvSource({
        "'19', TableDir",
        "'18', Table",
        "'30', Search"
    })
    void knownRefIdsMappedCorrectly(String refId, String expectedType) {
      assertEquals(expectedType, McpSchemaFieldBuilder.mapSelectorType(refId));
    }

    @Test
    void obuiselRefIdMapsToOBUISEL() {
      assertEquals("OBUISEL",
          McpSchemaFieldBuilder.mapSelectorType(NeoSelectorService.REF_OBUISEL));
    }

    @Test
    void unknownRefIdReturnsNull() {
      assertNull(McpSchemaFieldBuilder.mapSelectorType("99"));
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

    JSONArray fields = McpSchemaFieldBuilder.buildSchemaFieldsArray(
        tab,
        null,
        java.util.Map.of(),
        java.util.Map.of(),
        java.util.Map.of("COL1", "  Pick the correct customer.  "),
        java.util.Set.of(),
        java.util.Set.of());

    JSONObject field = fields.getJSONObject(0);
    assertEquals("Pick the correct customer.", field.getString("agentPrompt"));
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

    /**
     * ETP-4288: "0" is a legacy AD placeholder on FK (`_ID`) columns meaning "resolve via
     * callout/session logic" — it is not a usable FK value (see DocTypeResolver on the write
     * path). neo_schema must never surface it as a literal defaultExpression, since an agent
     * reading only the schema would treat "0" as a valid id and fail on neo_create/neo_update.
     */
    @Test
    void legacyZeroFkSentinelReplacedWithDynamicHint() throws Exception {
      org.openbravo.model.ad.datamodel.Column col = mock(org.openbravo.model.ad.datamodel.Column.class);
      when(col.getDefaultValue()).thenReturn("0");
      when(col.getDBColumnName()).thenReturn("C_DocType_ID");

      JSONObject fieldObj = new JSONObject();
      invokeStatic("addDefaultExpression",
          new Class<?>[]{ JSONObject.class, org.openbravo.model.ad.datamodel.Column.class },
          fieldObj, col);

      assertFalse(fieldObj.has("defaultExpression"));
      assertEquals("server", fieldObj.getString("defaultSource"));
      assertEquals("32-char hex ID (FK)", fieldObj.getString("defaultFormat"));
      assertEquals("Resolved per-tenant at request time — call neo_defaults to get the value",
          fieldObj.getString("defaultHint"));
    }

    /**
     * ETP-4288: non-FK columns with a legitimate literal "0" default (e.g. ChargeAmt,
     * EM_Etgo_Total_Discount) must keep reporting it as-is — the sentinel handling only
     * targets `_ID`-suffixed FK columns.
     */
    @Test
    void nonFkZeroDefaultIsUnaffected() throws Exception {
      org.openbravo.model.ad.datamodel.Column col = mock(org.openbravo.model.ad.datamodel.Column.class);
      when(col.getDefaultValue()).thenReturn("0");
      when(col.getDBColumnName()).thenReturn("ChargeAmt");

      JSONObject fieldObj = new JSONObject();
      invokeStatic("addDefaultExpression",
          new Class<?>[]{ JSONObject.class, org.openbravo.model.ad.datamodel.Column.class },
          fieldObj, col);

      assertEquals("0", fieldObj.getString("defaultExpression"));
      assertFalse(fieldObj.has("defaultSource"));
      assertFalse(fieldObj.has("defaultFormat"));
      assertFalse(fieldObj.has("defaultHint"));
    }

    /**
     * ETP-4288: a real (non-"0") FK default expression, e.g. a session-variable reference
     * like the currency default, must still be emitted verbatim — the sentinel handling
     * only intercepts the literal "0" placeholder, not legitimate expressions.
     */
    @Test
    void realFkDefaultExpressionIsPreserved() throws Exception {
      org.openbravo.model.ad.datamodel.Column col = mock(org.openbravo.model.ad.datamodel.Column.class);
      when(col.getDefaultValue()).thenReturn("@C_Currency_ID@");
      when(col.getDBColumnName()).thenReturn("C_Currency_ID");

      JSONObject fieldObj = new JSONObject();
      invokeStatic("addDefaultExpression",
          new Class<?>[]{ JSONObject.class, org.openbravo.model.ad.datamodel.Column.class },
          fieldObj, col);

      assertEquals("@C_Currency_ID@", fieldObj.getString("defaultExpression"));
      assertFalse(fieldObj.has("defaultSource"));
      assertFalse(fieldObj.has("defaultFormat"));
      assertFalse(fieldObj.has("defaultHint"));
    }

    /**
     * ETP-4288: the sentinel handling is generic across any `_ID` FK column, not
     * special-cased to C_DocType_ID/C_DocTypeTarget_ID — confirmed here with an unrelated
     * business partner FK column carrying the same "0" placeholder.
     */
    @Test
    void legacyZeroFkSentinelIsGenericAcrossTables() throws Exception {
      org.openbravo.model.ad.datamodel.Column col = mock(org.openbravo.model.ad.datamodel.Column.class);
      when(col.getDefaultValue()).thenReturn("0");
      when(col.getDBColumnName()).thenReturn("C_BPartner_ID");

      JSONObject fieldObj = new JSONObject();
      invokeStatic("addDefaultExpression",
          new Class<?>[]{ JSONObject.class, org.openbravo.model.ad.datamodel.Column.class },
          fieldObj, col);

      assertFalse(fieldObj.has("defaultExpression"));
      assertEquals("server", fieldObj.getString("defaultSource"));
      assertEquals("32-char hex ID (FK)", fieldObj.getString("defaultFormat"));

      // Regression guard (rejected design): never bake a resolved instance id into the schema.
      // No field in this object may carry a 32-char hex value anywhere.
      java.util.Iterator<String> keys = fieldObj.keys();
      while (keys.hasNext()) {
        String key = keys.next();
        Object value = fieldObj.get(key);
        if (value instanceof String) {
          assertFalse(((String) value).matches(".*[0-9A-Fa-f]{32}.*"),
              "field '" + key + "' must never carry a resolved 32-char hex instance id");
        }
      }
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
          McpSchemaFieldBuilder.findColumn(tab, "c_bpartner_id", null);
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
          McpSchemaFieldBuilder.findColumn(tab, "businessPartner", entity);
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

      assertNull(McpSchemaFieldBuilder.findColumn(tab, "notExists", null));
    }

    @Test
    void nullEntitySkipsPropertyFallback() {
      org.openbravo.model.ad.ui.Tab tab = mock(org.openbravo.model.ad.ui.Tab.class);
      org.openbravo.model.ad.datamodel.Table table = mock(org.openbravo.model.ad.datamodel.Table.class);
      when(tab.getTable()).thenReturn(table);

      org.openbravo.model.ad.datamodel.Column col = mock(org.openbravo.model.ad.datamodel.Column.class);
      when(col.getDBColumnName()).thenReturn("SomeCol");
      when(table.getADColumnList()).thenReturn(java.util.List.of(col));

      assertNull(McpSchemaFieldBuilder.findColumn(tab, "notMatching", null));
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
              java.util.Map.class,
              java.util.Set.class },
          col, tab, null,
          new java.util.HashMap<>(),
          new java.util.HashMap<>(),
          new java.util.HashMap<>(),
          new java.util.HashSet<>());

      assertEquals("button", result.getString("type"));
      assertEquals("Y", result.getString("triggerValue"));
      assertEquals("Processed", result.getString("action"));
      assertEquals("neo_action", result.getString("invokeVia"));
    }

    @Test
    @DisplayName("listBackedButtonEmitsSortedActionValuesAndParameter")
    void listBackedButtonEmitsSortedActionValuesAndParameter() throws Exception {
      org.openbravo.model.ad.datamodel.Column col = mock(
          org.openbravo.model.ad.datamodel.Column.class);
      org.openbravo.model.ad.domain.Reference listRef = mock(
          org.openbravo.model.ad.domain.Reference.class);
      Process classicProcess = mock(Process.class);

      when(col.getDBColumnName()).thenReturn("DocAction");
      when(col.getProcess()).thenReturn(classicProcess);
      when(col.getOBUIAPPProcess()).thenReturn(null);
      when(classicProcess.getName()).thenReturn("Process Order");
      when(classicProcess.getId()).thenReturn("CLASSIC-PROC-001");
      when(col.getReferenceSearchKey()).thenReturn(listRef);
      when(listRef.getId()).thenReturn("ORDER-DOCACTION-REF");

      // Unordered on purpose: getListLabels returns a HashMap.
      Map<String, String> labels = new HashMap<>();
      labels.put("VO", "Void");
      labels.put("CO", "Book");
      labels.put("CL", "Close");

      try (MockedStatic<NeoSelectorService> selectorMock = mockStatic(NeoSelectorService.class)) {
        selectorMock.when(() -> NeoSelectorService.getListLabels("ORDER-DOCACTION-REF"))
            .thenReturn(labels);

        JSONObject fieldObj = new JSONObject();
        invokeStatic("addButtonInfo",
            new Class<?>[]{ JSONObject.class, org.openbravo.model.ad.datamodel.Column.class },
            fieldObj, col);

        assertEquals("docAction", fieldObj.getString("actionParameter"));
        JSONArray values = fieldObj.getJSONArray("actionValues");
        assertEquals(3, values.length());
        assertEquals("CL", values.getJSONObject(0).getString("value"));
        assertEquals("Close", values.getJSONObject(0).getString("label"));
        assertEquals("CO", values.getJSONObject(1).getString("value"));
        assertEquals("Book", values.getJSONObject(1).getString("label"));
        assertEquals("VO", values.getJSONObject(2).getString("value"));
      }
    }

    @Test
    @DisplayName("buttonWithoutListReferenceOmitsActionValues")
    void buttonWithoutListReferenceOmitsActionValues() throws Exception {
      org.openbravo.model.ad.datamodel.Column col = mock(
          org.openbravo.model.ad.datamodel.Column.class);

      when(col.getDBColumnName()).thenReturn("Processing");
      when(col.getProcess()).thenReturn(null);
      when(col.getOBUIAPPProcess()).thenReturn(null);
      when(col.getReferenceSearchKey()).thenReturn(null);
      accessHelperMock.when(
          () -> NeoAccessHelper.resolveFallbackObuiappProcess(col)).thenReturn(null);

      JSONObject fieldObj = new JSONObject();
      invokeStatic("addButtonInfo",
          new Class<?>[]{ JSONObject.class, org.openbravo.model.ad.datamodel.Column.class },
          fieldObj, col);

      assertFalse(fieldObj.has("actionValues"));
      assertFalse(fieldObj.has("actionParameter"));
    }

    @Test
    @DisplayName("buttonWhoseListLookupReturnsNullOmitsActionValues")
    void buttonWhoseListLookupReturnsNullOmitsActionValues() throws Exception {
      org.openbravo.model.ad.datamodel.Column col = mock(
          org.openbravo.model.ad.datamodel.Column.class);
      org.openbravo.model.ad.domain.Reference listRef = mock(
          org.openbravo.model.ad.domain.Reference.class);

      when(col.getDBColumnName()).thenReturn("DocAction");
      when(col.getProcess()).thenReturn(null);
      when(col.getOBUIAPPProcess()).thenReturn(null);
      when(col.getReferenceSearchKey()).thenReturn(listRef);
      when(listRef.getId()).thenReturn("NULL-REF");
      accessHelperMock.when(
          () -> NeoAccessHelper.resolveFallbackObuiappProcess(col)).thenReturn(null);

      try (MockedStatic<NeoSelectorService> selectorMock = mockStatic(NeoSelectorService.class)) {
        // getListLabels swallows its own exceptions and is declared to return a map, but a
        // null return must not NPE the schema build — the field simply omits actionValues.
        selectorMock.when(() -> NeoSelectorService.getListLabels("NULL-REF")).thenReturn(null);

        JSONObject fieldObj = new JSONObject();
        invokeStatic("addButtonInfo",
            new Class<?>[]{ JSONObject.class, org.openbravo.model.ad.datamodel.Column.class },
            fieldObj, col);

        assertFalse(fieldObj.has("actionValues"));
        assertFalse(fieldObj.has("actionParameter"));
        // The rest of the button metadata is still emitted.
        assertEquals("neo_action", fieldObj.getString("invokeVia"));
      }
    }

    @Test
    @DisplayName("buttonWithEmptyListOmitsActionValues")
    void buttonWithEmptyListOmitsActionValues() throws Exception {
      org.openbravo.model.ad.datamodel.Column col = mock(
          org.openbravo.model.ad.datamodel.Column.class);
      org.openbravo.model.ad.domain.Reference listRef = mock(
          org.openbravo.model.ad.domain.Reference.class);

      when(col.getDBColumnName()).thenReturn("DocAction");
      when(col.getProcess()).thenReturn(null);
      when(col.getOBUIAPPProcess()).thenReturn(null);
      when(col.getReferenceSearchKey()).thenReturn(listRef);
      when(listRef.getId()).thenReturn("EMPTY-REF");
      accessHelperMock.when(
          () -> NeoAccessHelper.resolveFallbackObuiappProcess(col)).thenReturn(null);

      try (MockedStatic<NeoSelectorService> selectorMock = mockStatic(NeoSelectorService.class)) {
        selectorMock.when(() -> NeoSelectorService.getListLabels("EMPTY-REF"))
            .thenReturn(new HashMap<>());

        JSONObject fieldObj = new JSONObject();
        invokeStatic("addButtonInfo",
            new Class<?>[]{ JSONObject.class, org.openbravo.model.ad.datamodel.Column.class },
            fieldObj, col);

        assertFalse(fieldObj.has("actionValues"));
        assertFalse(fieldObj.has("actionParameter"));
      }
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
              java.util.Map.class,
              java.util.Set.class },
          col, tab, null,
          new java.util.HashMap<>(),
          businessCriticalMap,
          new java.util.HashMap<>(),
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
              java.util.Map.class,
              java.util.Set.class },
          col, tab, null,
          new java.util.HashMap<>(),
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
              java.util.Map.class,
              java.util.Set.class },
          col, tab, null,
          new java.util.HashMap<>(),
          businessCriticalMap,
          new java.util.HashMap<>(),
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

      McpSchemaFieldBuilder.FieldMetadata meta = McpSchemaFieldBuilder.loadFieldMetadata(sfEntity);

      assertTrue(meta.businessCriticalByColumnId.get("col-1"));
    }

    @Test
    @DisplayName("field with isBusinessCritical=false maps to false in result map")
    void fieldWithFalseMapsToFalse() {
      SFEntity sfEntity = mock(SFEntity.class);
      when(sfEntity.getId()).thenReturn("entity-2");
      mockFieldCriteria(List.of(buildSFField("col-2", false)));

      McpSchemaFieldBuilder.FieldMetadata meta = McpSchemaFieldBuilder.loadFieldMetadata(sfEntity);

      assertFalse(meta.businessCriticalByColumnId.get("col-2"));
    }

    @Test
    @DisplayName("field with isBusinessCritical=null maps to false — no NPE")
    void fieldWithNullMapsToFalse() {
      SFEntity sfEntity = mock(SFEntity.class);
      when(sfEntity.getId()).thenReturn("entity-3");
      mockFieldCriteria(List.of(buildSFField("col-3", null)));

      McpSchemaFieldBuilder.FieldMetadata meta = McpSchemaFieldBuilder.loadFieldMetadata(sfEntity);

      assertFalse(meta.businessCriticalByColumnId.get("col-3"));
    }
  }

  // ─── loadPreconditionRequirements / applyPreconditionRequirement (ETP-4276) ──

  @Nested
  @DisplayName("precondition-derived userRequired")
  class PreconditionRequirement {

    private final String assetsPreconditions =
        "{ \"800125\": ["
        + "{ \"field\": \"usableLifeMonths\", \"requiredWhen\": \"@calculateType@ != 'PE' && @amortize@ != 'YE'\" },"
        + "{ \"field\": \"usableLifeYears\",  \"requiredWhen\": \"@amortize@ == 'YE'\" },"
        + "{ \"field\": \"currency\" }"
        + "] }";

    private SFEntity entityWith(String preconditionsJson) {
      SFEntity entity = mock(SFEntity.class);
      when(entity.get("preconditions")).thenReturn(preconditionsJson);
      return entity;
    }

    @Test
    @DisplayName("loads requirements keyed by field, preserving conditions")
    void loadsRequirementsKeyedByField() {
      Map<String, String> req =
          McpSchemaFieldBuilder.loadPreconditionRequirements(entityWith(assetsPreconditions));
      assertEquals(3, req.size());
      assertEquals("@calculateType@ != 'PE' && @amortize@ != 'YE'", req.get("usableLifeMonths"));
      assertEquals("@amortize@ == 'YE'", req.get("usableLifeYears"));
      assertTrue(req.containsKey("currency"));
      assertTrue(req.get("currency").isEmpty(), "currency is unconditional => empty condition");
    }

    @Test
    @DisplayName("null entity yields an empty map")
    void nullEntityYieldsEmptyMap() {
      assertTrue(McpSchemaFieldBuilder.loadPreconditionRequirements(null).isEmpty());
    }

    @Test
    @DisplayName("null or blank preconditions yield an empty map")
    void nullOrBlankYieldsEmpty() {
      assertTrue(McpSchemaFieldBuilder.loadPreconditionRequirements(entityWith(null)).isEmpty());
      assertTrue(McpSchemaFieldBuilder.loadPreconditionRequirements(entityWith("   ")).isEmpty());
    }

    @Test
    @DisplayName("malformed preconditions fail open to an empty map")
    void malformedFailsOpen() {
      assertTrue(McpSchemaFieldBuilder.loadPreconditionRequirements(
          entityWith("{ not valid json")).isEmpty());
    }

    @Test
    @DisplayName("apply flags userRequired and surfaces the conditional requiredWhen")
    void applyFlagsUserRequired() throws Exception {
      Map<String, String> req =
          McpSchemaFieldBuilder.loadPreconditionRequirements(entityWith(assetsPreconditions));

      JSONObject conditional = new JSONObject();
      McpSchemaFieldBuilder.applyPreconditionRequirement(conditional, "usableLifeMonths", req);
      assertTrue(conditional.getBoolean("userRequired"));
      assertEquals("@calculateType@ != 'PE' && @amortize@ != 'YE'",
          conditional.getString("requiredWhen"));

      JSONObject unconditional = new JSONObject();
      McpSchemaFieldBuilder.applyPreconditionRequirement(unconditional, "currency", req);
      assertTrue(unconditional.getBoolean("userRequired"));
      assertFalse(unconditional.has("requiredWhen"),
          "unconditional field must not carry requiredWhen");

      JSONObject untouched = new JSONObject();
      McpSchemaFieldBuilder.applyPreconditionRequirement(untouched, "someOtherField", req);
      assertFalse(untouched.has("userRequired"),
          "field not named in preconditions must be left untouched");
    }

    @Test
    @DisplayName("applyPreconditionRequirements overlays every field of a built array")
    void applyOverArray() throws Exception {
      Map<String, String> req =
          McpSchemaFieldBuilder.loadPreconditionRequirements(entityWith(assetsPreconditions));
      JSONArray fields = new JSONArray();
      fields.put(new JSONObject().put("name", "usableLifeMonths"));
      fields.put(new JSONObject().put("name", "currency"));
      fields.put(new JSONObject().put("name", "assetValue"));

      McpSchemaFieldBuilder.applyPreconditionRequirements(fields, req);

      assertTrue(fields.getJSONObject(0).getBoolean("userRequired"));
      assertEquals("@calculateType@ != 'PE' && @amortize@ != 'YE'",
          fields.getJSONObject(0).getString("requiredWhen"));
      assertTrue(fields.getJSONObject(1).getBoolean("userRequired"));
      assertFalse(fields.getJSONObject(1).has("requiredWhen"));
      assertFalse(fields.getJSONObject(2).has("userRequired"),
          "field not in preconditions must be left untouched");
    }
  }

  // ─── applyCuratedLabels (IMP-1) ─────────────────────────────────────

  @Nested
  @DisplayName("applyCuratedLabels")
  class ApplyCuratedLabels {

    private JSONArray sampleFields() throws Exception {
      JSONArray fields = new JSONArray();
      fields.put(new JSONObject()
          .put("name", "siiDescription").put("column", "EM_Aeatsii_Descripcion_Sii")
          .put("label", "EM_Aeatsii_Descripcion_Sii"));
      fields.put(new JSONObject()
          .put("name", "costCenter").put("column", "User1_ID").put("label", "1st Dimension"));
      return fields;
    }

    @Test
    @DisplayName("overwrites the raw label and adds a description, keyed by column")
    void overlaysLabelAndDescription() throws Exception {
      JSONArray fields = sampleFields();
      Map<String, String[]> labels = new HashMap<>();
      labels.put("EM_AEATSII_DESCRIPCION_SII",
          new String[] { "SII Description", "Operation description to send to SII." });

      McpSchemaFieldBuilder.applyCuratedLabels(fields, labels);

      assertEquals("SII Description", fields.getJSONObject(0).getString("label"));
      assertEquals("Operation description to send to SII.",
          fields.getJSONObject(0).getString("description"));
    }

    @Test
    @DisplayName("leaves fields with no matching column untouched")
    void leavesUnmatchedFieldsUntouched() throws Exception {
      JSONArray fields = sampleFields();
      Map<String, String[]> labels = new HashMap<>();
      labels.put("EM_AEATSII_DESCRIPCION_SII", new String[] { "SII Description", "desc" });

      McpSchemaFieldBuilder.applyCuratedLabels(fields, labels);

      assertEquals("1st Dimension", fields.getJSONObject(1).getString("label"));
      assertFalse(fields.getJSONObject(1).has("description"));
    }

    @Test
    @DisplayName("a blank label or description leaves the existing value in place")
    void blankValuesAreIgnored() throws Exception {
      JSONArray fields = sampleFields();
      Map<String, String[]> labels = new HashMap<>();
      labels.put("USER1_ID", new String[] { "  ", "" });

      McpSchemaFieldBuilder.applyCuratedLabels(fields, labels);

      assertEquals("1st Dimension", fields.getJSONObject(1).getString("label"));
      assertFalse(fields.getJSONObject(1).has("description"));
    }

    @Test
    @DisplayName("null/empty overlay map is a no-op")
    void emptyMapIsNoop() throws Exception {
      JSONArray fields = sampleFields();
      McpSchemaFieldBuilder.applyCuratedLabels(fields, new HashMap<>());
      assertEquals("EM_Aeatsii_Descripcion_Sii", fields.getJSONObject(0).getString("label"));
      McpSchemaFieldBuilder.applyCuratedLabels(fields, null);
      assertEquals("EM_Aeatsii_Descripcion_Sii", fields.getJSONObject(0).getString("label"));
    }
  }
}
