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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.criterion.Criterion;
import org.hibernate.criterion.Order;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.openbravo.base.exception.OBSecurityException;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.datamodel.Column;
import org.openbravo.model.ad.domain.Reference;
import org.openbravo.model.ad.ui.Process;
import org.openbravo.model.ad.ui.ProcessParameter;
import org.openbravo.model.ad.ui.Window;

import com.etendoerp.go.schemaforge.data.SFEntity;
import com.etendoerp.go.schemaforge.data.SFField;
import com.etendoerp.go.schemaforge.data.SFSpec;

/**
 * Unit tests for {@link McpResourceProvider}.
 *
 * <p>Covers: listResources (empty, W/P/R types, access-denied filtering),
 * readResource URI dispatch, readSpecsList (accessible/inaccessible specs),
 * readSpec (access denied, valid spec with entities), readEntity (found,
 * access denied), readProcess (non-process type, no linked process, valid
 * process with parameters), and unknown URI exception.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class McpResourceProviderTest {

  private McpResourceProvider provider;

  @Mock
  private OBDal obDal;

  private MockedStatic<OBDal> obDalMock;
  private MockedStatic<McpToolRouterSupport> routerSupportMock;

  @BeforeEach
  void setUp() {
    provider = new McpResourceProvider();
    obDalMock = mockStatic(OBDal.class);
    routerSupportMock = mockStatic(McpToolRouterSupport.class);

    obDalMock.when(OBDal::getInstance).thenReturn(obDal);
  }

  @AfterEach
  void tearDown() {
    routerSupportMock.close();
    obDalMock.close();
  }

  // ── Helper methods ──────────────────────────────────────────────────────

  @SuppressWarnings("unchecked")
  private OBCriteria<SFSpec> mockSpecCriteria(List<SFSpec> specs) {
    OBCriteria<SFSpec> criteria = mock(OBCriteria.class);
    when(obDal.createCriteria(SFSpec.class)).thenReturn(criteria);
    when(criteria.add(any(Criterion.class))).thenReturn(criteria);
    when(criteria.addOrder(any(Order.class))).thenReturn(criteria);
    when(criteria.list()).thenReturn(specs);
    return criteria;
  }

  @SuppressWarnings("unchecked")
  private OBCriteria<SFEntity> mockEntityCriteria(List<SFEntity> entities) {
    OBCriteria<SFEntity> criteria = mock(OBCriteria.class);
    when(obDal.createCriteria(SFEntity.class)).thenReturn(criteria);
    when(criteria.add(any(Criterion.class))).thenReturn(criteria);
    when(criteria.addOrder(any(Order.class))).thenReturn(criteria);
    when(criteria.list()).thenReturn(entities);
    return criteria;
  }

  @SuppressWarnings("unchecked")
  private OBCriteria<SFField> mockFieldCriteria(List<SFField> fields) {
    OBCriteria<SFField> criteria = mock(OBCriteria.class);
    when(obDal.createCriteria(SFField.class)).thenReturn(criteria);
    when(criteria.add(any(Criterion.class))).thenReturn(criteria);
    when(criteria.addOrder(any(Order.class))).thenReturn(criteria);
    when(criteria.list()).thenReturn(fields);
    return criteria;
  }

  private SFSpec buildSpec(String id, String name, String type, String description) {
    SFSpec spec = mock(SFSpec.class);
    when(spec.getId()).thenReturn(id);
    when(spec.getName()).thenReturn(name);
    when(spec.getSpecType()).thenReturn(type);
    when(spec.getDescription()).thenReturn(description);
    when(spec.isActive()).thenReturn(true);
    return spec;
  }

  private SFEntity buildEntity(String id, String name, boolean included) {
    SFEntity entity = mock(SFEntity.class);
    when(entity.getId()).thenReturn(id);
    when(entity.getName()).thenReturn(name);
    when(entity.isIncluded()).thenReturn(included);
    return entity;
  }

  private SFField buildField(String columnName, String label, String refId,
      boolean readOnly, boolean mandatory, String defaultValue) {
    SFField field = mock(SFField.class);
    Column column = mock(Column.class);
    Reference reference = mock(Reference.class);

    when(field.getADColumn()).thenReturn(column);
    when(field.isReadOnly()).thenReturn(readOnly);
    when(field.getDefaultValue()).thenReturn(defaultValue);

    when(column.getDBColumnName()).thenReturn(columnName);
    when(column.getName()).thenReturn(label);
    when(column.getReference()).thenReturn(reference);
    when(column.isMandatory()).thenReturn(mandatory);
    when(reference.getId()).thenReturn(refId);

    return field;
  }

  // ── listResources ───────────────────────────────────────────────────────

  @Nested
  @DisplayName("listResources")
  class ListResourcesTests {

    /**
     * When no active specs exist, listResources should return only the static
     * neo://specs resource descriptor.
     */
    @Test
    @DisplayName("returns only the specs-list resource when no active specs exist")
    void emptySpecsReturnsOnlySpecsListResource() throws Exception {
      mockSpecCriteria(Collections.emptyList());

      JSONArray resources = provider.listResources();

      assertEquals(1, resources.length(), "Should contain only the static specs-list resource");
      assertEquals("neo://specs", resources.getJSONObject(0).getString("uri"));
      assertEquals("application/json", resources.getJSONObject(0).getString("mimeType"));
    }

    /**
     * A Window-type spec with access should produce exactly one additional
     * resource (the spec itself, no process resource).
     */
    @Test
    @DisplayName("Window spec produces one spec resource (no process resource)")
    void windowSpecProducesOneResource() throws Exception {
      SFSpec windowSpec = buildSpec("w1", "purchase-order", "W", "Purchase Order");
      mockSpecCriteria(Collections.singletonList(windowSpec));

      routerSupportMock.when(() -> McpToolRouterSupport.hasSpecAccess(windowSpec, "W"))
          .thenReturn(true);

      JSONArray resources = provider.listResources();

      // 1 static + 1 spec resource
      assertEquals(2, resources.length());
      assertEquals("neo://specs/purchase-order", resources.getJSONObject(1).getString("uri"));
      assertEquals("Spec: purchase-order", resources.getJSONObject(1).getString("name"));
    }

    /**
     * A Process-type spec with access should produce two additional resources:
     * the spec resource and a process resource.
     */
    @Test
    @DisplayName("Process spec produces spec + process resources")
    void processSpecProducesTwoResources() throws Exception {
      SFSpec processSpec = buildSpec("p1", "run-report", "P", "Run Report");
      mockSpecCriteria(Collections.singletonList(processSpec));

      routerSupportMock.when(() -> McpToolRouterSupport.hasSpecAccess(processSpec, "P"))
          .thenReturn(true);

      JSONArray resources = provider.listResources();

      // 1 static + 1 spec + 1 process
      assertEquals(3, resources.length());
      assertEquals("neo://specs/run-report", resources.getJSONObject(1).getString("uri"));
      assertEquals("neo://processes/run-report", resources.getJSONObject(2).getString("uri"));
      assertEquals("Process parameters for run-report",
          resources.getJSONObject(2).getString("description"));
    }

    /**
     * A Report-type spec with access should produce a process resource with
     * "Report parameters" in the description.
     */
    @Test
    @DisplayName("Report spec produces process resource with 'Report' prefix")
    void reportSpecProducesReportProcessResource() throws Exception {
      SFSpec reportSpec = buildSpec("r1", "sales-report", "R", "Sales Report");
      mockSpecCriteria(Collections.singletonList(reportSpec));

      routerSupportMock.when(() -> McpToolRouterSupport.hasSpecAccess(reportSpec, "R"))
          .thenReturn(true);

      JSONArray resources = provider.listResources();

      assertEquals(3, resources.length());
      assertEquals("Report parameters for sales-report",
          resources.getJSONObject(2).getString("description"));
    }

    /**
     * Specs without access should be filtered out entirely (no spec or process resource).
     */
    @Test
    @DisplayName("access-denied specs are filtered out")
    void accessDeniedSpecsFiltered() throws Exception {
      SFSpec deniedSpec = buildSpec("d1", "secret-spec", "W", "Secret");
      SFSpec allowedSpec = buildSpec("a1", "open-spec", "W", "Open");
      mockSpecCriteria(Arrays.asList(deniedSpec, allowedSpec));

      routerSupportMock.when(() -> McpToolRouterSupport.hasSpecAccess(deniedSpec, "W"))
          .thenReturn(false);
      routerSupportMock.when(() -> McpToolRouterSupport.hasSpecAccess(allowedSpec, "W"))
          .thenReturn(true);

      JSONArray resources = provider.listResources();

      // 1 static + 1 allowed spec
      assertEquals(2, resources.length());
      assertEquals("neo://specs/open-spec", resources.getJSONObject(1).getString("uri"));
    }

    /**
     * Multiple specs of mixed types with mixed access produce the correct
     * total number of resource descriptors.
     */
    @Test
    @DisplayName("mixed specs produce correct resource count")
    void mixedSpecsCorrectCount() throws Exception {
      SFSpec windowSpec = buildSpec("w1", "win-spec", "W", "Window Spec");
      SFSpec processSpec = buildSpec("p1", "proc-spec", "P", "Process Spec");
      SFSpec deniedSpec = buildSpec("d1", "denied-spec", "R", "Denied");
      mockSpecCriteria(Arrays.asList(windowSpec, processSpec, deniedSpec));

      routerSupportMock.when(() -> McpToolRouterSupport.hasSpecAccess(windowSpec, "W"))
          .thenReturn(true);
      routerSupportMock.when(() -> McpToolRouterSupport.hasSpecAccess(processSpec, "P"))
          .thenReturn(true);
      routerSupportMock.when(() -> McpToolRouterSupport.hasSpecAccess(deniedSpec, "R"))
          .thenReturn(false);

      JSONArray resources = provider.listResources();

      // 1 static + 1 window spec + (1 process spec + 1 process resource) = 4
      assertEquals(4, resources.length());
    }
  }

  // ── readResource URI dispatch ───────────────────────────────────────────

  @Nested
  @DisplayName("readResource URI dispatch")
  class ReadResourceDispatchTests {

    /**
     * Exact "neo://specs" URI should invoke readSpecsList and return a
     * result containing "specs" and "count" keys.
     */
    @Test
    @DisplayName("neo://specs dispatches to readSpecsList")
    void specsUriDispatchesToSpecsList() throws Exception {
      mockSpecCriteria(Collections.emptyList());

      JSONObject result = provider.readResource("neo://specs");

      assertTrue(result.has("specs"), "Result must contain 'specs' key");
      assertTrue(result.has("count"), "Result must contain 'count' key");
      assertEquals(0, result.getInt("count"));
    }

    /**
     * A single-segment path after neo://specs/ should invoke readSpec.
     */
    @Test
    @DisplayName("neo://specs/{name} dispatches to readSpec")
    void specUriDispatchesToReadSpec() throws Exception {
      SFSpec spec = buildSpec("s1", "my-spec", "W", "My Spec");
      Window window = mock(Window.class);
      when(window.getName()).thenReturn("My Window");
      when(window.getId()).thenReturn("win-1");
      when(spec.getADWindow()).thenReturn(window);

      routerSupportMock.when(() -> McpToolRouterSupport.findActiveSpecByName("my-spec"))
          .thenReturn(spec);
      routerSupportMock.when(() -> McpToolRouterSupport.hasSpecAccess(spec, "W"))
          .thenReturn(true);
      routerSupportMock.when(() -> McpToolRouterSupport.listIncludedEntities("s1"))
          .thenReturn(Collections.emptyList());

      JSONObject result = provider.readResource("neo://specs/my-spec");

      assertEquals("my-spec", result.getString("name"));
      assertEquals("W", result.getString("type"));
    }

    /**
     * A two-segment path after neo://specs/ should invoke readEntity.
     */
    @Test
    @DisplayName("neo://specs/{name}/{entity} dispatches to readEntity")
    void entityUriDispatchesToReadEntity() throws Exception {
      SFSpec spec = buildSpec("s1", "my-spec", "W", "My Spec");
      SFEntity entity = buildEntity("e1", "Order", true);

      routerSupportMock.when(() -> McpToolRouterSupport.findActiveSpecByName("my-spec"))
          .thenReturn(spec);
      routerSupportMock.when(() -> McpToolRouterSupport.hasSpecAccess(spec, "W"))
          .thenReturn(true);
      routerSupportMock.when(() -> McpToolRouterSupport.findIncludedEntity("s1", "Order"))
          .thenReturn(entity);
      routerSupportMock.when(() -> McpToolRouterSupport.buildMethodsArray(entity))
          .thenReturn(new JSONArray().put("GET").put("POST"));

      mockFieldCriteria(Collections.emptyList());

      JSONObject result = provider.readResource("neo://specs/my-spec/Order");

      assertEquals("Order", result.getString("name"));
      assertEquals("my-spec", result.getString("specName"));
      assertEquals("W", result.getString("specType"));
    }

    /**
     * URI with neo://processes/ prefix should dispatch to readProcess.
     */
    @Test
    @DisplayName("neo://processes/{name} dispatches to readProcess")
    void processUriDispatchesToReadProcess() throws Exception {
      SFSpec spec = buildSpec("p1", "my-process", "P", "My Process");
      Process adProcess = mock(Process.class);
      when(adProcess.getName()).thenReturn("RunImport");
      when(adProcess.getId()).thenReturn("proc-1");
      when(adProcess.getDescription()).thenReturn("Imports data");
      when(adProcess.getHelpComment()).thenReturn("Help text");
      when(adProcess.getUIPattern()).thenReturn("S");
      when(adProcess.getADProcessParameterList()).thenReturn(Collections.emptyList());
      when(spec.getProcess()).thenReturn(adProcess);

      routerSupportMock.when(() -> McpToolRouterSupport.findActiveSpecByName("my-process"))
          .thenReturn(spec);
      routerSupportMock.when(() -> McpToolRouterSupport.hasSpecAccess(spec, "P"))
          .thenReturn(true);

      JSONObject result = provider.readResource("neo://processes/my-process");

      assertEquals("my-process", result.getString("specName"));
      assertEquals("P", result.getString("specType"));
      assertEquals("RunImport", result.getString("processName"));
    }

    /**
     * An unknown URI prefix should throw IllegalArgumentException.
     */
    @Test
    @DisplayName("unknown URI throws IllegalArgumentException")
    void unknownUriThrowsException() {
      assertThrows(IllegalArgumentException.class,
          () -> provider.readResource("neo://unknown/something"));
    }
  }

  // ── readSpecsList ───────────────────────────────────────────────────────

  @Nested
  @DisplayName("readSpecsList (neo://specs)")
  class ReadSpecsListTests {

    /**
     * Accessible specs should be included in the specs array; inaccessible ones excluded.
     */
    @Test
    @DisplayName("includes accessible specs and excludes inaccessible ones")
    void filtersSpecsByAccess() throws Exception {
      SFSpec accessible = buildSpec("a1", "public-spec", "W", "Public");
      Window window = mock(Window.class);
      when(window.getName()).thenReturn("Public Window");
      when(accessible.getADWindow()).thenReturn(window);

      SFSpec denied = buildSpec("d1", "private-spec", "W", "Private");

      mockSpecCriteria(Arrays.asList(accessible, denied));
      mockEntityCriteria(Collections.emptyList());

      routerSupportMock.when(() -> McpToolRouterSupport.hasSpecAccess(accessible, "W"))
          .thenReturn(true);
      routerSupportMock.when(() -> McpToolRouterSupport.hasSpecAccess(denied, "W"))
          .thenReturn(false);

      JSONObject result = provider.readResource("neo://specs");

      assertEquals(1, result.getInt("count"));
      JSONArray specs = result.getJSONArray("specs");
      assertEquals(1, specs.length());
      assertEquals("public-spec", specs.getJSONObject(0).getString("name"));
    }

    /**
     * A Window-type spec should include windowName in the summary.
     */
    @Test
    @DisplayName("Window spec summary includes windowName")
    void windowSpecIncludesWindowName() throws Exception {
      SFSpec spec = buildSpec("w1", "order-spec", "W", "Order Spec");
      Window window = mock(Window.class);
      when(window.getName()).thenReturn("Sales Order");
      when(spec.getADWindow()).thenReturn(window);

      mockSpecCriteria(Collections.singletonList(spec));
      mockEntityCriteria(Collections.emptyList());

      routerSupportMock.when(() -> McpToolRouterSupport.hasSpecAccess(spec, "W"))
          .thenReturn(true);

      JSONObject result = provider.readResource("neo://specs");

      JSONObject specObj = result.getJSONArray("specs").getJSONObject(0);
      assertEquals("Sales Order", specObj.getString("windowName"));
    }

    /**
     * A Process-type spec should include processName in the summary.
     */
    @Test
    @DisplayName("Process spec summary includes processName")
    void processSpecIncludesProcessName() throws Exception {
      SFSpec spec = buildSpec("p1", "import-spec", "P", "Import Spec");
      Process process = mock(Process.class);
      when(process.getName()).thenReturn("DataImport");
      when(spec.getProcess()).thenReturn(process);

      mockSpecCriteria(Collections.singletonList(spec));
      mockEntityCriteria(Collections.emptyList());

      routerSupportMock.when(() -> McpToolRouterSupport.hasSpecAccess(spec, "P"))
          .thenReturn(true);

      JSONObject result = provider.readResource("neo://specs");

      JSONObject specObj = result.getJSONArray("specs").getJSONObject(0);
      assertEquals("DataImport", specObj.getString("processName"));
    }

    /**
     * A Report-type spec should include isReport=true in the summary.
     */
    @Test
    @DisplayName("Report spec summary includes isReport=true")
    void reportSpecIncludesIsReport() throws Exception {
      SFSpec spec = buildSpec("r1", "report-spec", "R", "Report Spec");
      Process process = mock(Process.class);
      when(process.getName()).thenReturn("SalesReport");
      when(spec.getProcess()).thenReturn(process);

      mockSpecCriteria(Collections.singletonList(spec));
      mockEntityCriteria(Collections.emptyList());

      routerSupportMock.when(() -> McpToolRouterSupport.hasSpecAccess(spec, "R"))
          .thenReturn(true);

      JSONObject result = provider.readResource("neo://specs");

      JSONObject specObj = result.getJSONArray("specs").getJSONObject(0);
      assertTrue(specObj.getBoolean("isReport"));
    }

    /**
     * The entityCount in the spec summary should reflect the number of
     * included entities returned by the criteria.
     */
    @Test
    @DisplayName("entityCount reflects included entity count")
    void entityCountReflectsIncludedEntities() throws Exception {
      SFSpec spec = buildSpec("w1", "multi-entity", "W", "Multi Entity Spec");
      Window window = mock(Window.class);
      when(window.getName()).thenReturn("Multi Window");
      when(spec.getADWindow()).thenReturn(window);

      SFEntity e1 = buildEntity("e1", "Header", true);
      SFEntity e2 = buildEntity("e2", "Lines", true);

      mockSpecCriteria(Collections.singletonList(spec));
      mockEntityCriteria(Arrays.asList(e1, e2));

      routerSupportMock.when(() -> McpToolRouterSupport.hasSpecAccess(spec, "W"))
          .thenReturn(true);

      JSONObject result = provider.readResource("neo://specs");

      JSONObject specObj = result.getJSONArray("specs").getJSONObject(0);
      assertEquals(2, specObj.getInt("entityCount"));
    }
  }

  // ── readSpec ────────────────────────────────────────────────────────────

  @Nested
  @DisplayName("readSpec (neo://specs/{name})")
  class ReadSpecTests {

    /**
     * Access denied to a spec should throw OBSecurityException.
     */
    @Test
    @DisplayName("access denied throws OBSecurityException")
    void accessDeniedThrowsSecurity() {
      SFSpec spec = buildSpec("s1", "secret", "W", "Secret");

      routerSupportMock.when(() -> McpToolRouterSupport.findActiveSpecByName("secret"))
          .thenReturn(spec);
      routerSupportMock.when(() -> McpToolRouterSupport.hasSpecAccess(spec, "W"))
          .thenReturn(false);

      OBSecurityException ex = assertThrows(OBSecurityException.class,
          () -> provider.readResource("neo://specs/secret"));
      assertTrue(ex.getMessage().contains("Access denied to spec 'secret'"));
    }

    /**
     * A null spec (not found) should throw OBSecurityException.
     */
    @Test
    @DisplayName("spec not found throws OBSecurityException")
    void specNotFoundThrowsSecurity() {
      routerSupportMock.when(() -> McpToolRouterSupport.findActiveSpecByName("nonexistent"))
          .thenReturn(null);

      assertThrows(OBSecurityException.class,
          () -> provider.readResource("neo://specs/nonexistent"));
    }

    /**
     * A valid Window spec should return the spec schema with windowName,
     * windowId, and entities array.
     */
    @Test
    @DisplayName("valid Window spec returns schema with window info and entities")
    void validWindowSpecReturnsSchema() throws Exception {
      SFSpec spec = buildSpec("s1", "purchase-order", "W", "Purchase Order");
      Window window = mock(Window.class);
      when(window.getName()).thenReturn("Purchase Order Window");
      when(window.getId()).thenReturn("win-po");
      when(spec.getADWindow()).thenReturn(window);

      SFEntity entity = buildEntity("e1", "OrderHeader", true);

      routerSupportMock.when(() -> McpToolRouterSupport.findActiveSpecByName("purchase-order"))
          .thenReturn(spec);
      routerSupportMock.when(() -> McpToolRouterSupport.hasSpecAccess(spec, "W"))
          .thenReturn(true);
      routerSupportMock.when(() -> McpToolRouterSupport.listIncludedEntities("s1"))
          .thenReturn(Collections.singletonList(entity));
      routerSupportMock.when(() -> McpToolRouterSupport.buildMethodsArray(entity))
          .thenReturn(new JSONArray().put("GET"));

      mockFieldCriteria(Collections.emptyList());

      JSONObject result = provider.readResource("neo://specs/purchase-order");

      assertEquals("purchase-order", result.getString("name"));
      assertEquals("W", result.getString("type"));
      assertEquals("Purchase Order", result.getString("description"));
      assertEquals("Purchase Order Window", result.getString("windowName"));
      assertEquals("win-po", result.getString("windowId"));
      assertTrue(result.has("entities"));
      assertEquals(1, result.getJSONArray("entities").length());
      assertEquals("OrderHeader",
          result.getJSONArray("entities").getJSONObject(0).getString("name"));
    }

    /**
     * A valid Process spec should return processName and processId.
     */
    @Test
    @DisplayName("valid Process spec returns process info")
    void validProcessSpecReturnsProcessInfo() throws Exception {
      SFSpec spec = buildSpec("p1", "import-data", "P", "Import Data");
      Process process = mock(Process.class);
      when(process.getName()).thenReturn("DataImport");
      when(process.getId()).thenReturn("proc-di");
      when(spec.getProcess()).thenReturn(process);

      routerSupportMock.when(() -> McpToolRouterSupport.findActiveSpecByName("import-data"))
          .thenReturn(spec);
      routerSupportMock.when(() -> McpToolRouterSupport.hasSpecAccess(spec, "P"))
          .thenReturn(true);
      routerSupportMock.when(() -> McpToolRouterSupport.listIncludedEntities("p1"))
          .thenReturn(Collections.emptyList());

      JSONObject result = provider.readResource("neo://specs/import-data");

      assertEquals("DataImport", result.getString("processName"));
      assertEquals("proc-di", result.getString("processId"));
    }

    /**
     * A spec with entities should include fields for each entity.
     */
    @Test
    @DisplayName("entities include fields with type and selector info")
    void entitiesIncludeFieldsWithMetadata() throws Exception {
      SFSpec spec = buildSpec("s1", "order-spec", "W", "Order Spec");
      when(spec.getADWindow()).thenReturn(null);

      SFEntity entity = buildEntity("e1", "OrderLine", true);
      SFField field = buildField("C_OrderLine_ID", "Order Line", "13", false, true, null);

      routerSupportMock.when(() -> McpToolRouterSupport.findActiveSpecByName("order-spec"))
          .thenReturn(spec);
      routerSupportMock.when(() -> McpToolRouterSupport.hasSpecAccess(spec, "W"))
          .thenReturn(true);
      routerSupportMock.when(() -> McpToolRouterSupport.listIncludedEntities("s1"))
          .thenReturn(Collections.singletonList(entity));
      routerSupportMock.when(() -> McpToolRouterSupport.buildMethodsArray(entity))
          .thenReturn(new JSONArray().put("GET").put("POST"));
      routerSupportMock.when(() -> McpToolRouterSupport.mapColumnType("13"))
          .thenReturn("id");
      routerSupportMock.when(() -> McpToolRouterSupport.mapSelectorType("13"))
          .thenReturn(null);

      mockFieldCriteria(Collections.singletonList(field));

      JSONObject result = provider.readResource("neo://specs/order-spec");

      JSONObject entityObj = result.getJSONArray("entities").getJSONObject(0);
      JSONArray fields = entityObj.getJSONArray("fields");
      assertEquals(1, fields.length());

      JSONObject fieldObj = fields.getJSONObject(0);
      assertEquals("C_OrderLine_ID", fieldObj.getString("name"));
      assertEquals("Order Line", fieldObj.getString("label"));
      assertEquals("id", fieldObj.getString("type"));
      assertTrue(fieldObj.getBoolean("required"));
    }

    /**
     * Fields with a selector type should include hasSelector=true and selectorType.
     */
    @Test
    @DisplayName("fields with selector include hasSelector and selectorType")
    void fieldsWithSelectorIncludeMetadata() throws Exception {
      SFSpec spec = buildSpec("s1", "sel-spec", "W", "Selector Spec");
      when(spec.getADWindow()).thenReturn(null);

      SFEntity entity = buildEntity("e1", "Entity1", true);
      SFField field = buildField("C_BPartner_ID", "Business Partner", "30", false, false, null);

      routerSupportMock.when(() -> McpToolRouterSupport.findActiveSpecByName("sel-spec"))
          .thenReturn(spec);
      routerSupportMock.when(() -> McpToolRouterSupport.hasSpecAccess(spec, "W"))
          .thenReturn(true);
      routerSupportMock.when(() -> McpToolRouterSupport.listIncludedEntities("s1"))
          .thenReturn(Collections.singletonList(entity));
      routerSupportMock.when(() -> McpToolRouterSupport.buildMethodsArray(entity))
          .thenReturn(new JSONArray().put("GET"));
      routerSupportMock.when(() -> McpToolRouterSupport.mapColumnType("30"))
          .thenReturn("selector");
      routerSupportMock.when(() -> McpToolRouterSupport.mapSelectorType("30"))
          .thenReturn("TableDir");

      mockFieldCriteria(Collections.singletonList(field));

      JSONObject result = provider.readResource("neo://specs/sel-spec");

      JSONObject fieldObj = result.getJSONArray("entities").getJSONObject(0)
          .getJSONArray("fields").getJSONObject(0);
      assertTrue(fieldObj.getBoolean("hasSelector"));
      assertEquals("TableDir", fieldObj.getString("selectorType"));
    }

    /**
     * Fields with a non-empty default value should include defaultValue.
     */
    @Test
    @DisplayName("fields with default value include defaultValue key")
    void fieldsWithDefaultValueIncludeIt() throws Exception {
      SFSpec spec = buildSpec("s1", "def-spec", "W", "Default Spec");
      when(spec.getADWindow()).thenReturn(null);

      SFEntity entity = buildEntity("e1", "Entity1", true);
      SFField field = buildField("IsActive", "Active", "20", false, false, "Y");

      routerSupportMock.when(() -> McpToolRouterSupport.findActiveSpecByName("def-spec"))
          .thenReturn(spec);
      routerSupportMock.when(() -> McpToolRouterSupport.hasSpecAccess(spec, "W"))
          .thenReturn(true);
      routerSupportMock.when(() -> McpToolRouterSupport.listIncludedEntities("s1"))
          .thenReturn(Collections.singletonList(entity));
      routerSupportMock.when(() -> McpToolRouterSupport.buildMethodsArray(entity))
          .thenReturn(new JSONArray().put("GET"));
      routerSupportMock.when(() -> McpToolRouterSupport.mapColumnType("20"))
          .thenReturn("boolean");
      routerSupportMock.when(() -> McpToolRouterSupport.mapSelectorType("20"))
          .thenReturn(null);

      mockFieldCriteria(Collections.singletonList(field));

      JSONObject result = provider.readResource("neo://specs/def-spec");

      JSONObject fieldObj = result.getJSONArray("entities").getJSONObject(0)
          .getJSONArray("fields").getJSONObject(0);
      assertEquals("Y", fieldObj.getString("defaultValue"));
    }

    /**
     * Fields with a null AD_Column should be skipped.
     */
    @Test
    @DisplayName("fields with null AD_Column are skipped")
    void fieldsWithNullColumnSkipped() throws Exception {
      SFSpec spec = buildSpec("s1", "null-col-spec", "W", "Null Col Spec");
      when(spec.getADWindow()).thenReturn(null);

      SFEntity entity = buildEntity("e1", "Entity1", true);
      SFField nullField = mock(SFField.class);
      when(nullField.getADColumn()).thenReturn(null);

      routerSupportMock.when(() -> McpToolRouterSupport.findActiveSpecByName("null-col-spec"))
          .thenReturn(spec);
      routerSupportMock.when(() -> McpToolRouterSupport.hasSpecAccess(spec, "W"))
          .thenReturn(true);
      routerSupportMock.when(() -> McpToolRouterSupport.listIncludedEntities("s1"))
          .thenReturn(Collections.singletonList(entity));
      routerSupportMock.when(() -> McpToolRouterSupport.buildMethodsArray(entity))
          .thenReturn(new JSONArray().put("GET"));

      mockFieldCriteria(Collections.singletonList(nullField));

      JSONObject result = provider.readResource("neo://specs/null-col-spec");

      JSONArray fields = result.getJSONArray("entities").getJSONObject(0)
          .getJSONArray("fields");
      assertEquals(0, fields.length(), "Field with null column should be skipped");
    }
  }

  // ── readEntity ──────────────────────────────────────────────────────────

  @Nested
  @DisplayName("readEntity (neo://specs/{name}/{entity})")
  class ReadEntityTests {

    /**
     * Access denied to the parent spec should throw OBSecurityException.
     */
    @Test
    @DisplayName("access denied throws OBSecurityException")
    void accessDeniedThrowsSecurity() {
      SFSpec spec = buildSpec("s1", "denied-spec", "W", "Denied");

      routerSupportMock.when(() -> McpToolRouterSupport.findActiveSpecByName("denied-spec"))
          .thenReturn(spec);
      routerSupportMock.when(() -> McpToolRouterSupport.hasSpecAccess(spec, "W"))
          .thenReturn(false);

      OBSecurityException ex = assertThrows(OBSecurityException.class,
          () -> provider.readResource("neo://specs/denied-spec/SomeEntity"));
      assertTrue(ex.getMessage().contains("Access denied to spec 'denied-spec'"));
    }

    /**
     * A valid entity should be returned with specName and specType.
     */
    @Test
    @DisplayName("valid entity returns with specName and specType")
    void validEntityReturnsWithSpecContext() throws Exception {
      SFSpec spec = buildSpec("s1", "order-spec", "W", "Order Spec");
      SFEntity entity = buildEntity("e1", "OrderHeader", true);

      routerSupportMock.when(() -> McpToolRouterSupport.findActiveSpecByName("order-spec"))
          .thenReturn(spec);
      routerSupportMock.when(() -> McpToolRouterSupport.hasSpecAccess(spec, "W"))
          .thenReturn(true);
      routerSupportMock.when(() -> McpToolRouterSupport.findIncludedEntity("s1", "OrderHeader"))
          .thenReturn(entity);
      routerSupportMock.when(() -> McpToolRouterSupport.buildMethodsArray(entity))
          .thenReturn(new JSONArray().put("GET").put("POST").put("DELETE"));

      mockFieldCriteria(Collections.emptyList());

      JSONObject result = provider.readResource("neo://specs/order-spec/OrderHeader");

      assertEquals("OrderHeader", result.getString("name"));
      assertEquals("order-spec", result.getString("specName"));
      assertEquals("W", result.getString("specType"));
      assertTrue(result.getBoolean("isIncluded"));
      assertNotNull(result.getJSONArray("methods"));
      assertNotNull(result.getJSONArray("fields"));
    }
  }

  // ── readProcess ─────────────────────────────────────────────────────────

  @Nested
  @DisplayName("readProcess (neo://processes/{name})")
  class ReadProcessTests {

    /**
     * Access denied to the spec should throw OBSecurityException.
     */
    @Test
    @DisplayName("access denied throws OBSecurityException")
    void accessDeniedThrowsSecurity() {
      SFSpec spec = buildSpec("p1", "secret-proc", "P", "Secret");

      routerSupportMock.when(() -> McpToolRouterSupport.findActiveSpecByName("secret-proc"))
          .thenReturn(spec);
      routerSupportMock.when(() -> McpToolRouterSupport.hasSpecAccess(spec, "P"))
          .thenReturn(false);

      assertThrows(OBSecurityException.class,
          () -> provider.readResource("neo://processes/secret-proc"));
    }

    /**
     * A non-process/non-report spec type should throw IllegalArgumentException.
     */
    @Test
    @DisplayName("non-process spec type throws IllegalArgumentException")
    void nonProcessSpecThrowsIllegalArgument() {
      SFSpec spec = buildSpec("w1", "window-spec", "W", "Window");

      routerSupportMock.when(() -> McpToolRouterSupport.findActiveSpecByName("window-spec"))
          .thenReturn(spec);
      routerSupportMock.when(() -> McpToolRouterSupport.hasSpecAccess(spec, "W"))
          .thenReturn(true);

      IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
          () -> provider.readResource("neo://processes/window-spec"));
      assertTrue(ex.getMessage().contains("not a process or report"));
    }

    /**
     * A process spec with no linked AD_Process should throw IllegalArgumentException.
     */
    @Test
    @DisplayName("no linked AD_Process throws IllegalArgumentException")
    void noLinkedProcessThrowsIllegalArgument() {
      SFSpec spec = buildSpec("p1", "orphan-proc", "P", "Orphan Process");
      when(spec.getProcess()).thenReturn(null);

      routerSupportMock.when(() -> McpToolRouterSupport.findActiveSpecByName("orphan-proc"))
          .thenReturn(spec);
      routerSupportMock.when(() -> McpToolRouterSupport.hasSpecAccess(spec, "P"))
          .thenReturn(true);

      IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
          () -> provider.readResource("neo://processes/orphan-proc"));
      assertTrue(ex.getMessage().contains("no linked AD_Process"));
    }

    /**
     * A valid process spec should return all process metadata fields.
     */
    @Test
    @DisplayName("valid process returns full metadata")
    void validProcessReturnsMetadata() throws Exception {
      SFSpec spec = buildSpec("p1", "import-proc", "P", "Import Process");
      Process adProcess = mock(Process.class);
      when(adProcess.getName()).thenReturn("ImportData");
      when(adProcess.getId()).thenReturn("proc-imp");
      when(adProcess.getDescription()).thenReturn("Imports CSV data");
      when(adProcess.getHelpComment()).thenReturn("Use CSV format");
      when(adProcess.getUIPattern()).thenReturn("S");
      when(adProcess.getADProcessParameterList()).thenReturn(Collections.emptyList());
      when(spec.getProcess()).thenReturn(adProcess);

      routerSupportMock.when(() -> McpToolRouterSupport.findActiveSpecByName("import-proc"))
          .thenReturn(spec);
      routerSupportMock.when(() -> McpToolRouterSupport.hasSpecAccess(spec, "P"))
          .thenReturn(true);

      JSONObject result = provider.readResource("neo://processes/import-proc");

      assertEquals("import-proc", result.getString("specName"));
      assertEquals("P", result.getString("specType"));
      assertEquals(false, result.getBoolean("isReport"));
      assertEquals("ImportData", result.getString("processName"));
      assertEquals("proc-imp", result.getString("processId"));
      assertEquals("Imports CSV data", result.getString("description"));
      assertEquals("Use CSV format", result.getString("helpComment"));
      assertEquals("S", result.getString("uiPattern"));
      assertEquals(0, result.getInt("parameterCount"));
    }

    /**
     * A Report-type spec should have isReport=true.
     */
    @Test
    @DisplayName("report process returns isReport=true")
    void reportProcessReturnsIsReport() throws Exception {
      SFSpec spec = buildSpec("r1", "sales-report", "R", "Sales Report");
      Process adProcess = mock(Process.class);
      when(adProcess.getName()).thenReturn("SalesReport");
      when(adProcess.getId()).thenReturn("proc-sr");
      when(adProcess.getDescription()).thenReturn(null);
      when(adProcess.getHelpComment()).thenReturn(null);
      when(adProcess.getUIPattern()).thenReturn("M");
      when(adProcess.getADProcessParameterList()).thenReturn(Collections.emptyList());
      when(spec.getProcess()).thenReturn(adProcess);

      routerSupportMock.when(() -> McpToolRouterSupport.findActiveSpecByName("sales-report"))
          .thenReturn(spec);
      routerSupportMock.when(() -> McpToolRouterSupport.hasSpecAccess(spec, "R"))
          .thenReturn(true);

      JSONObject result = provider.readResource("neo://processes/sales-report");

      assertTrue(result.getBoolean("isReport"));
    }

    /**
     * A process with active and inactive parameters should only include active ones.
     */
    @Test
    @DisplayName("process includes only active parameters")
    void processIncludesOnlyActiveParameters() throws Exception {
      SFSpec spec = buildSpec("p1", "param-proc", "P", "Param Process");
      Process adProcess = mock(Process.class);
      when(adProcess.getName()).thenReturn("ParamProc");
      when(adProcess.getId()).thenReturn("proc-pp");
      when(adProcess.getDescription()).thenReturn("Process with params");
      when(adProcess.getHelpComment()).thenReturn(null);
      when(adProcess.getUIPattern()).thenReturn("S");
      when(spec.getProcess()).thenReturn(adProcess);

      // Active parameter
      ProcessParameter activeParam = mock(ProcessParameter.class);
      when(activeParam.isActive()).thenReturn(true);
      when(activeParam.getName()).thenReturn("DateFrom");
      when(activeParam.getDBColumnName()).thenReturn("datefrom");
      when(activeParam.getSequenceNumber()).thenReturn(10L);
      when(activeParam.isMandatory()).thenReturn(true);
      when(activeParam.getDefaultValue()).thenReturn("@today@");
      when(activeParam.getDescription()).thenReturn("Start date");
      Reference paramRef = mock(Reference.class);
      when(paramRef.getId()).thenReturn("15");
      when(paramRef.getName()).thenReturn("Date");
      when(activeParam.getReference()).thenReturn(paramRef);
      when(activeParam.getReferenceSearchKey()).thenReturn(null);
      when(activeParam.isRange()).thenReturn(false);
      when(activeParam.getLength()).thenReturn(10L);

      // Inactive parameter
      ProcessParameter inactiveParam = mock(ProcessParameter.class);
      when(inactiveParam.isActive()).thenReturn(false);

      when(adProcess.getADProcessParameterList())
          .thenReturn(Arrays.asList(activeParam, inactiveParam));

      routerSupportMock.when(() -> McpToolRouterSupport.findActiveSpecByName("param-proc"))
          .thenReturn(spec);
      routerSupportMock.when(() -> McpToolRouterSupport.hasSpecAccess(spec, "P"))
          .thenReturn(true);

      JSONObject result = provider.readResource("neo://processes/param-proc");

      assertEquals(1, result.getInt("parameterCount"));
      JSONArray params = result.getJSONArray("parameters");
      assertEquals(1, params.length());

      JSONObject paramObj = params.getJSONObject(0);
      assertEquals("DateFrom", paramObj.getString("name"));
      assertEquals("datefrom", paramObj.getString("dbColumnName"));
      assertEquals(10, paramObj.getInt("sequenceNumber"));
      assertTrue(paramObj.getBoolean("mandatory"));
      assertEquals("@today@", paramObj.getString("defaultValue"));
      assertEquals("Start date", paramObj.getString("description"));
      assertEquals("15", paramObj.getString("referenceId"));
      assertEquals("Date", paramObj.getString("referenceType"));
      assertEquals(false, paramObj.getBoolean("isRange"));
      assertEquals(10, paramObj.getInt("length"));
    }

    /**
     * A parameter with a referenceSearchKey should include referenceSearchKeyId.
     */
    @Test
    @DisplayName("parameter with referenceSearchKey includes referenceSearchKeyId")
    void parameterWithReferenceSearchKey() throws Exception {
      SFSpec spec = buildSpec("p1", "ref-proc", "P", "Ref Process");
      Process adProcess = mock(Process.class);
      when(adProcess.getName()).thenReturn("RefProc");
      when(adProcess.getId()).thenReturn("proc-rp");
      when(adProcess.getDescription()).thenReturn(null);
      when(adProcess.getHelpComment()).thenReturn(null);
      when(adProcess.getUIPattern()).thenReturn("S");
      when(spec.getProcess()).thenReturn(adProcess);

      ProcessParameter param = mock(ProcessParameter.class);
      when(param.isActive()).thenReturn(true);
      when(param.getName()).thenReturn("BPartner");
      when(param.getDBColumnName()).thenReturn("c_bpartner_id");
      when(param.getSequenceNumber()).thenReturn(20L);
      when(param.isMandatory()).thenReturn(false);
      when(param.getDefaultValue()).thenReturn(null);
      when(param.getDescription()).thenReturn(null);

      Reference ref = mock(Reference.class);
      when(ref.getId()).thenReturn("30");
      when(ref.getName()).thenReturn("Search");
      when(param.getReference()).thenReturn(ref);

      Reference searchKeyRef = mock(Reference.class);
      when(searchKeyRef.getId()).thenReturn("800001");
      when(param.getReferenceSearchKey()).thenReturn(searchKeyRef);

      when(param.isRange()).thenReturn(false);
      when(param.getLength()).thenReturn(32L);

      when(adProcess.getADProcessParameterList()).thenReturn(Collections.singletonList(param));

      routerSupportMock.when(() -> McpToolRouterSupport.findActiveSpecByName("ref-proc"))
          .thenReturn(spec);
      routerSupportMock.when(() -> McpToolRouterSupport.hasSpecAccess(spec, "P"))
          .thenReturn(true);

      JSONObject result = provider.readResource("neo://processes/ref-proc");

      JSONObject paramObj = result.getJSONArray("parameters").getJSONObject(0);
      assertEquals("800001", paramObj.getString("referenceSearchKeyId"));
    }

    /**
     * A parameter with isRange=true should be reflected in the output.
     */
    @Test
    @DisplayName("range parameter includes isRange=true")
    void rangeParameterIncludesFlag() throws Exception {
      SFSpec spec = buildSpec("p1", "range-proc", "P", "Range Process");
      Process adProcess = mock(Process.class);
      when(adProcess.getName()).thenReturn("RangeProc");
      when(adProcess.getId()).thenReturn("proc-range");
      when(adProcess.getDescription()).thenReturn(null);
      when(adProcess.getHelpComment()).thenReturn(null);
      when(adProcess.getUIPattern()).thenReturn("S");
      when(spec.getProcess()).thenReturn(adProcess);

      ProcessParameter param = mock(ProcessParameter.class);
      when(param.isActive()).thenReturn(true);
      when(param.getName()).thenReturn("DateRange");
      when(param.getDBColumnName()).thenReturn("daterange");
      when(param.getSequenceNumber()).thenReturn(10L);
      when(param.isMandatory()).thenReturn(false);
      when(param.getDefaultValue()).thenReturn(null);
      when(param.getDescription()).thenReturn(null);
      when(param.getReference()).thenReturn(null);
      when(param.getReferenceSearchKey()).thenReturn(null);
      when(param.isRange()).thenReturn(true);
      when(param.getLength()).thenReturn(10L);

      when(adProcess.getADProcessParameterList()).thenReturn(Collections.singletonList(param));

      routerSupportMock.when(() -> McpToolRouterSupport.findActiveSpecByName("range-proc"))
          .thenReturn(spec);
      routerSupportMock.when(() -> McpToolRouterSupport.hasSpecAccess(spec, "P"))
          .thenReturn(true);

      JSONObject result = provider.readResource("neo://processes/range-proc");

      JSONObject paramObj = result.getJSONArray("parameters").getJSONObject(0);
      assertTrue(paramObj.getBoolean("isRange"));
    }
  }
}
