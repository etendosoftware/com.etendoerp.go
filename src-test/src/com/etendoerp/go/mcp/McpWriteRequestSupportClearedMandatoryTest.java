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

package com.etendoerp.go.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.Property;
import org.openbravo.model.ad.datamodel.Column;
import org.openbravo.model.ad.datamodel.Table;
import org.openbravo.model.ad.ui.Tab;

import com.etendoerp.go.schemaforge.data.SFEntity;
import com.etendoerp.go.schemaforge.selector.policy.NeoSelectorPolicy;

/**
 * Unit tests for {@code McpWriteRequestSupport#rejectClearedMandatoryFields}: an
 * {@code etendo_update} must not empty a field AD declares mandatory.
 *
 * <p>The rule is structural — AD mandatory flag plus the key the caller sent — so every table,
 * column and property below is a mock name; nothing here names an entity. The second half pins the
 * wiring: the check runs on the MCP update path only, after the type coercion and before the
 * entity pre-hook, and the REST CRUD and batch services never call it.
 *
 * @covers com.etendoerp.go.mcp.McpWriteRequestSupport
 * @covers com.etendoerp.go.mcp.McpToolRouter
 */
@DisplayName("etendo_update rejects clearing a required field")
class McpWriteRequestSupportClearedMandatoryTest {

  private static final Logger LOG = LogManager.getLogger();
  private static final String TABLE = "Mock_Doc";
  private static final String LANG = "es_ES";

  private MockedStatic<NeoSelectorPolicy> policyStatic;
  private MockedStatic<McpSchemaFieldBuilder> labelsStatic;
  private Map<String, String[]> labels;
  private Set<String> serverResolved;
  private Tab adTab;
  private Entity entity;
  private final List<Column> columns = new java.util.ArrayList<>();
  private final SFEntity sfEntity = mock(SFEntity.class);

  @BeforeEach
  void setUp() {
    labels = new HashMap<>();
    serverResolved = new HashSet<>();
    policyStatic = mockStatic(NeoSelectorPolicy.class);
    policyStatic.when(() -> NeoSelectorPolicy.serverResolvedFieldNames(sfEntity))
        .thenAnswer(inv -> serverResolved);
    labelsStatic = mockStatic(McpSchemaFieldBuilder.class);
    labelsStatic.when(() -> McpSchemaFieldBuilder.loadFieldLabels(any(), any()))
        .thenAnswer(inv -> labels);
    adTab = mock(Tab.class);
    Table table = mock(Table.class);
    when(adTab.getTable()).thenReturn(table);
    when(table.getDBTableName()).thenReturn(TABLE);
    when(table.getADColumnList()).thenReturn(columns);
    entity = mock(Entity.class);
  }

  @AfterEach
  void tearDown() {
    labelsStatic.close();
    policyStatic.close();
  }

  /** Adds an AD column and the DAL property that backs it. */
  private void column(String dbName, String prop, boolean mandatory, boolean active,
      String adName) {
    Column col = mock(Column.class);
    when(col.getDBColumnName()).thenReturn(dbName);
    when(col.isMandatory()).thenReturn(mandatory);
    when(col.isActive()).thenReturn(active);
    when(col.getName()).thenReturn(adName);
    columns.add(col);
    Property property = mock(Property.class);
    when(property.getName()).thenReturn(prop);
    when(entity.getPropertyByColumnName(dbName)).thenReturn(property);
  }

  private JSONObject reject(JSONObject body, Set<String> systemColumns) throws Exception {
    return McpWriteRequestSupport.rejectClearedMandatoryFields(body, adTab, entity, systemColumns,
        new HashSet<>(), sfEntity, LANG, LOG);
  }

  private JSONObject reject(JSONObject body) throws Exception {
    return reject(body, new HashSet<>());
  }

  @Test
  @DisplayName("a mandatory field sent as null is refused with the validation envelope")
  void nullOnMandatoryIsRefused() throws Exception {
    column("DateX", "dateX", true, true, "Date X");
    JSONObject error = reject(new JSONObject().put("dateX", JSONObject.NULL));

    assertNotNull(error);
    assertEquals(422, error.getInt("status"));
    assertEquals("validation_error", error.getString("error"));
    assertTrue(error.getString("detail").contains("dateX"), error.getString("detail"));
    JSONObject item = error.getJSONArray("missingFields").getJSONObject(0);
    assertEquals("dateX", item.getString("name"));
    assertEquals("DateX", item.getString("column"));
    assertTrue(error.has("hint"));
  }

  @Test
  @DisplayName("a mandatory field sent as an empty string is refused")
  void emptyStringOnMandatoryIsRefused() throws Exception {
    column("DateX", "dateX", true, true, "Date X");
    assertNotNull(reject(new JSONObject().put("dateX", "")));
  }

  @Test
  @DisplayName("a mandatory field the caller did not send is left alone")
  void absentMandatoryIsNotRefused() throws Exception {
    column("DateX", "dateX", true, true, "Date X");
    assertNull(reject(new JSONObject().put("other", "v")));
  }

  @Test
  @DisplayName("a mandatory field sent with a value passes")
  void valuedMandatoryPasses() throws Exception {
    column("DateX", "dateX", true, true, "Date X");
    assertNull(reject(new JSONObject().put("dateX", "2026-11-01")));
  }

  @Test
  @DisplayName("an optional field may be cleared")
  void optionalMayBeCleared() throws Exception {
    column("NoteX", "noteX", false, true, "Note X");
    assertNull(reject(new JSONObject().put("noteX", JSONObject.NULL)));
  }

  @Test
  @DisplayName("an inactive mandatory column is ignored")
  void inactiveColumnIsIgnored() throws Exception {
    column("DateX", "dateX", true, false, "Date X");
    assertNull(reject(new JSONObject().put("dateX", JSONObject.NULL)));
  }

  @Test
  @DisplayName("server-resolved fields are not the caller's to keep")
  void serverResolvedFieldIsExcluded() throws Exception {
    column("LocX", "locX", true, true, "Loc X");
    serverResolved.add("locX");
    assertNull(reject(new JSONObject().put("locX", JSONObject.NULL)));
  }

  @Test
  @DisplayName("the primary key and the system columns are excluded")
  void primaryKeyAndSystemColumnsAreExcluded() throws Exception {
    column(TABLE + "_ID", "id", true, true, "Id");
    column("Created", "creationDate", true, true, "Created");
    JSONObject body = new JSONObject().put("id", JSONObject.NULL)
        .put("creationDate", JSONObject.NULL);
    assertNull(reject(body, Set.of("CREATED")));
  }

  @Test
  @DisplayName("the label is the user's language field label")
  void labelIsLocalized() throws Exception {
    column("DateX", "dateX", true, true, "Date X");
    labels.put("DATEX", new String[] { "Fecha X", "desc" });
    JSONObject error = reject(new JSONObject().put("dateX", JSONObject.NULL));

    assertEquals("Fecha X",
        error.getJSONArray("missingFields").getJSONObject(0).getString("label"));
    assertTrue(error.getString("detail").contains("Fecha X"), error.getString("detail"));
    labelsStatic.verify(() -> McpSchemaFieldBuilder.loadFieldLabels(adTab, LANG));
  }

  @Test
  @DisplayName("without a field label the AD column name is the label")
  void labelFallsBackToAdName() throws Exception {
    column("DateX", "dateX", true, true, "Date X");
    JSONObject error = reject(new JSONObject().put("dateX", JSONObject.NULL));
    assertEquals("Date X",
        error.getJSONArray("missingFields").getJSONObject(0).getString("label"));
  }

  @Test
  @DisplayName("every cleared field is named, the untouched ones are not")
  void everyClearedFieldIsNamed() throws Exception {
    column("DateX", "dateX", true, true, "Date X");
    column("DateY", "dateY", true, true, "Date Y");
    column("DateZ", "dateZ", true, true, "Date Z");
    JSONObject error = reject(new JSONObject().put("dateX", JSONObject.NULL)
        .put("dateY", "").put("dateZ", "2026-11-01"));
    JSONArray missing = error.getJSONArray("missingFields");
    assertEquals(2, missing.length());
    assertTrue(!error.getString("detail").contains("dateZ"));
  }

  @Test
  @DisplayName("a null body or a null entity is a no-op")
  void nullInputsAreNoOps() throws Exception {
    assertNull(McpWriteRequestSupport.rejectClearedMandatoryFields(null, adTab, entity,
        new HashSet<>(), new HashSet<>(), sfEntity, LANG, LOG));
    assertNull(McpWriteRequestSupport.rejectClearedMandatoryFields(new JSONObject(), adTab, null,
        new HashSet<>(), new HashSet<>(), sfEntity, LANG, LOG));
  }

  // ── wiring: MCP update only ──────────────────────────────────────────────

  private static final String ROUTER = "com/etendoerp/go/mcp/McpToolRouter.java";

  @Test
  @DisplayName("handleUpdate runs the check after the type coercion and before the pre-hook")
  void updateRunsTheCheckInPlace() {
    String body = McpSourceScanner.stripComments(
        McpSourceScanner.methodBody(McpSourceScanner.read(ROUTER), "handleUpdate"));
    int coerce = body.indexOf("coerceFieldTypes(");
    int check = body.indexOf("rejectClearedMandatoryFields(");
    int preHook = body.indexOf("NeoExtensionDispatcher.dispatch(");
    assertTrue(coerce >= 0 && check > coerce && preHook > check,
        "expected coerceFieldTypes < rejectClearedMandatoryFields < pre-hook dispatch");
  }

  @Test
  @DisplayName("create, REST CRUD and batch never run it (MCP update only)")
  void onlyTheMcpUpdateRunsIt() {
    String router = McpSourceScanner.stripComments(McpSourceScanner.read(ROUTER));
    assertEquals(1, router.split("rejectClearedMandatoryFields\\(", -1).length - 1,
        "the router must call it exactly once, from handleUpdate");
    for (String rest : List.of("com/etendoerp/go/schemaforge/NeoCrudHandler.java",
        "com/etendoerp/go/schemaforge/BatchService.java")) {
      assertTrue(!McpSourceScanner.read(rest).contains("rejectClearedMandatoryFields"), rest);
    }
  }
}
