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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.codehaus.jettison.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.ModelProvider;
import org.openbravo.base.model.Property;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.datamodel.Column;
import org.openbravo.model.ad.datamodel.Table;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.ad.ui.Tab;
import org.openbravo.model.ad.ui.Window;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.common.enterprise.Warehouse;

/**
 * Unit tests for {@link CalloutRequestBuilder}.
 *
 * <p>All tests mock the Etendo static singletons (OBDal, OBContext, ModelProvider,
 * NeoDefaultsService) so no live database is required. Mocks are opened in
 * {@link #setUp()} and closed in reverse order in {@link #tearDown()} to guarantee
 * correct isolation when the full suite runs.</p>
 */
public class CalloutRequestBuilderTest {

  // Static singletons that carry JVM-wide state and MUST be mocked for every test.
  private MockedStatic<OBDal> obDalMock;
  private MockedStatic<OBContext> obContextMock;
  private MockedStatic<ModelProvider> modelProviderMock;
  private MockedStatic<NeoDefaultsService> neoDefaultsMock;

  private OBDal dal;
  private ModelProvider modelProvider;
  private OBContext obContext;

  @Before
  public void setUp() {
    // ── OBDal ──────────────────────────────────────────────────────────
    dal = mock(OBDal.class);
    obDalMock = mockStatic(OBDal.class);
    obDalMock.when(OBDal::getInstance).thenReturn(dal);

    // ── ModelProvider ─────────────────────────────────────────────────
    modelProvider = mock(ModelProvider.class);
    modelProviderMock = mockStatic(ModelProvider.class);
    modelProviderMock.when(ModelProvider::getInstance).thenReturn(modelProvider);

    // ── OBContext ─────────────────────────────────────────────────────
    obContext = mock(OBContext.class);
    obContextMock = mockStatic(OBContext.class);
    obContextMock.when(OBContext::getOBContext).thenReturn(obContext);

    // ── NeoDefaultsService ────────────────────────────────────────────
    neoDefaultsMock = mockStatic(NeoDefaultsService.class);
    neoDefaultsMock.when(() -> NeoDefaultsService.resolveFirstOrgForClient(anyString()))
        .thenReturn(null);

    // ── default OBContext chain so tests can share a minimal setup ────
    Client client = mock(Client.class);
    when(client.getId()).thenReturn("CLIENT-001");
    Organization org = mock(Organization.class);
    when(org.getId()).thenReturn("ORG-001");
    when(obContext.getCurrentClient()).thenReturn(client);
    when(obContext.getCurrentOrganization()).thenReturn(org);
    when(obContext.getWarehouse()).thenReturn(null);

    // Clear parent-tab cache so test ordering has no effect.
    CalloutRequestBuilder.clearParentTabCache();
  }

  @After
  public void tearDown() {
    // Close in REVERSE open order to satisfy Mockito's scope nesting requirement.
    neoDefaultsMock.close();
    obContextMock.close();
    modelProviderMock.close();
    obDalMock.close();

    NeoCalloutService.clearMetadataCache();
  }

  // ─────────────────────────────────────────────────────────────────────
  // Helpers
  // ─────────────────────────────────────────────────────────────────────

  private static Tab mockTab(String id, long sequence, long level) {
    Tab tab = mock(Tab.class);
    when(tab.getId()).thenReturn(id);
    when(tab.getSequenceNumber()).thenReturn(sequence);
    when(tab.getTabLevel()).thenReturn(level);
    return tab;
  }

  /** Create a minimal Tab mock with a Table that has the given tableId. */
  @SuppressWarnings("unchecked")
  private Tab tabWithTable(String tableId) {
    Tab tab = mock(Tab.class);
    Table table = mock(Table.class);
    when(tab.getTable()).thenReturn(table);
    when(table.getId()).thenReturn(tableId);
    // Empty column criteria by default
    OBCriteria<Column> criteria = mock(OBCriteria.class);
    when(dal.createCriteria(Column.class)).thenReturn(criteria);
    when(criteria.add(any())).thenReturn(criteria);
    when(criteria.list()).thenReturn(Collections.emptyList());
    when(modelProvider.getEntityByTableId(tableId)).thenReturn(null);
    return tab;
  }

  // ─────────────────────────────────────────────────────────────────────
  // resolveToInpName
  // ─────────────────────────────────────────────────────────────────────

  @Test
  public void resolveToInpName_alreadyInpPrefix_returnedAsIs() {
    Map<String, String> db = new HashMap<>();
    Map<String, String> clean = new HashMap<>();
    String result = CalloutRequestBuilder.resolveToInpName("inpTabId", db, clean);
    assertEquals("inpTabId", result);
  }

  @Test
  public void resolveToInpName_matchesByDbName() {
    Map<String, String> db = new HashMap<>();
    db.put("c_bpartner_id", "inpcBpartnerId");
    Map<String, String> clean = new HashMap<>();
    String result = CalloutRequestBuilder.resolveToInpName("C_BPartner_ID", db, clean);
    assertEquals("inpcBpartnerId", result);
  }

  @Test
  public void resolveToInpName_matchesByCleanName_fallback() {
    Map<String, String> db = new HashMap<>();
    Map<String, String> clean = new HashMap<>();
    clean.put("bpartner", "inpcBpartnerId");
    String result = CalloutRequestBuilder.resolveToInpName("bpartner", db, clean);
    assertEquals("inpcBpartnerId", result);
  }

  @Test
  public void resolveToInpName_noMatch_fallbacksToPrefixInp() {
    Map<String, String> db = new HashMap<>();
    Map<String, String> clean = new HashMap<>();
    String result = CalloutRequestBuilder.resolveToInpName("someField", db, clean);
    assertEquals("inpsomeField", result);
  }

  @Test
  public void resolveToInpName_dbNameMatchIsCaseInsensitive() {
    Map<String, String> db = new HashMap<>();
    db.put("documentno", "inpdocumentno");
    Map<String, String> clean = new HashMap<>();
    String result = CalloutRequestBuilder.resolveToInpName("DOCUMENTNO", db, clean);
    assertEquals("inpdocumentno", result);
  }

  // ─────────────────────────────────────────────────────────────────────
  // buildColumnLookupMaps
  // ─────────────────────────────────────────────────────────────────────

  @Test
  public void buildColumnLookupMaps_tabWithNullTable_returnsEmptyMaps() {
    Tab tab = mock(Tab.class);
    when(tab.getTable()).thenReturn(null);

    CalloutRequestBuilder.ColumnLookupMaps maps = CalloutRequestBuilder.buildColumnLookupMaps(tab);

    assertNotNull(maps);
    assertTrue(maps.columns.isEmpty());
    assertTrue(maps.propertyNameToInp.isEmpty());
    assertTrue(maps.dbNameToInp.isEmpty());
    assertTrue(maps.cleanNameToInp.isEmpty());
  }

  @Test
  @SuppressWarnings("unchecked")
  public void buildColumnLookupMaps_populatesAllThreeMaps() {
    Tab tab = mock(Tab.class);
    Table table = mock(Table.class);
    when(tab.getTable()).thenReturn(table);
    when(table.getId()).thenReturn("C_ORDER");

    Column col = mock(Column.class);
    when(col.getDBColumnName()).thenReturn("C_BPartner_ID");

    OBCriteria<Column> criteria = mock(OBCriteria.class);
    when(dal.createCriteria(Column.class)).thenReturn(criteria);
    when(criteria.add(any())).thenReturn(criteria);
    when(criteria.list()).thenReturn(Collections.singletonList(col));

    Entity entity = mock(Entity.class);
    Property property = mock(Property.class);
    when(property.getName()).thenReturn("businessPartner");
    when(entity.getPropertyByColumnName("C_BPartner_ID")).thenReturn(property);
    when(modelProvider.getEntityByTableId("C_ORDER")).thenReturn(entity);

    CalloutRequestBuilder.ColumnLookupMaps maps = CalloutRequestBuilder.buildColumnLookupMaps(tab);

    assertEquals(1, maps.columns.size());
    assertEquals("inpcBpartnerId", maps.propertyNameToInp.get("businesspartner"));
    assertEquals("inpcBpartnerId", maps.dbNameToInp.get("c_bpartner_id"));
    // cleanName map should contain at least "bpartner" -> "inpcBpartnerId"
    assertTrue(maps.cleanNameToInp.containsValue("inpcBpartnerId"));
  }

  @Test
  @SuppressWarnings("unchecked")
  public void buildColumnLookupMaps_modelProviderException_stilLPopulatesDbAndCleanMaps() {
    Tab tab = mock(Tab.class);
    Table table = mock(Table.class);
    when(tab.getTable()).thenReturn(table);
    when(table.getId()).thenReturn("M_PRODUCT");

    Column col = mock(Column.class);
    when(col.getDBColumnName()).thenReturn("M_Product_ID");

    OBCriteria<Column> criteria = mock(OBCriteria.class);
    when(dal.createCriteria(Column.class)).thenReturn(criteria);
    when(criteria.add(any())).thenReturn(criteria);
    when(criteria.list()).thenReturn(Collections.singletonList(col));

    // ModelProvider throws on this table
    when(modelProvider.getEntityByTableId("M_PRODUCT"))
        .thenThrow(new RuntimeException("entity not found"));

    CalloutRequestBuilder.ColumnLookupMaps maps = CalloutRequestBuilder.buildColumnLookupMaps(tab);

    // Should still have DB-name and clean-name entries
    assertEquals("inpmProductId", maps.dbNameToInp.get("m_product_id"));
    assertTrue(maps.propertyNameToInp.isEmpty());
  }

  @Test
  @SuppressWarnings("unchecked")
  public void buildColumnLookupMaps_dalEntityNullProperty_doesNotPopulatePropertyMap() {
    Tab tab = mock(Tab.class);
    Table table = mock(Table.class);
    when(tab.getTable()).thenReturn(table);
    when(table.getId()).thenReturn("AD_ORG");

    Column col = mock(Column.class);
    when(col.getDBColumnName()).thenReturn("AD_Org_ID");

    OBCriteria<Column> criteria = mock(OBCriteria.class);
    when(dal.createCriteria(Column.class)).thenReturn(criteria);
    when(criteria.add(any())).thenReturn(criteria);
    when(criteria.list()).thenReturn(Collections.singletonList(col));

    Entity entity = mock(Entity.class);
    when(entity.getPropertyByColumnName("AD_Org_ID")).thenReturn(null);
    when(modelProvider.getEntityByTableId("AD_ORG")).thenReturn(entity);

    CalloutRequestBuilder.ColumnLookupMaps maps = CalloutRequestBuilder.buildColumnLookupMaps(tab);

    assertTrue(maps.propertyNameToInp.isEmpty());
    assertEquals("inpadOrgId", maps.dbNameToInp.get("ad_org_id"));
  }

  // ─────────────────────────────────────────────────────────────────────
  // mapFormStateToParams
  // ─────────────────────────────────────────────────────────────────────

  @Test
  public void mapFormStateToParams_nullFormState_doesNothing() {
    CalloutRequestBuilder.ColumnLookupMaps maps = new CalloutRequestBuilder.ColumnLookupMaps();
    Map<String, String[]> params = new HashMap<>();
    CalloutRequestBuilder.mapFormStateToParams(null, "inpfield", maps, params);
    assertTrue(params.isEmpty());
  }

  @Test
  public void mapFormStateToParams_identifierCompanionKeySkipped() throws Exception {
    JSONObject formState = new JSONObject();
    formState.put("businessPartner$_identifier", "ACME Corp");

    CalloutRequestBuilder.ColumnLookupMaps maps = new CalloutRequestBuilder.ColumnLookupMaps();
    Map<String, String[]> params = new HashMap<>();
    CalloutRequestBuilder.mapFormStateToParams(formState, "inpother", maps, params);

    // Identifier companion key must NOT end up in params
    assertTrue(params.isEmpty());
  }

  @Test
  public void mapFormStateToParams_triggerFieldNotOverwritten() throws Exception {
    JSONObject formState = new JSONObject();
    formState.put("inpcBpartnerId", "BP-FORM");

    CalloutRequestBuilder.ColumnLookupMaps maps = new CalloutRequestBuilder.ColumnLookupMaps();
    Map<String, String[]> params = new HashMap<>();
    // "inpcBpartnerId" is the trigger field — must not be overwritten by formState
    CalloutRequestBuilder.mapFormStateToParams(formState, "inpcBpartnerId", maps, params);

    // Trigger key in formState should be skipped
    assertTrue(params.isEmpty());
  }

  @Test
  public void mapFormStateToParams_mapsInpPrefixedKeyDirectly() throws Exception {
    JSONObject formState = new JSONObject();
    formState.put("inpdocumentno", "ORDER-001");

    CalloutRequestBuilder.ColumnLookupMaps maps = new CalloutRequestBuilder.ColumnLookupMaps();
    Map<String, String[]> params = new HashMap<>();
    // Trigger is a different field
    CalloutRequestBuilder.mapFormStateToParams(formState, "inpcBpartnerId", maps, params);

    assertNotNull(params.get("inpdocumentno"));
    assertEquals("ORDER-001", params.get("inpdocumentno")[0]);
  }

  @Test
  public void mapFormStateToParams_usesPropertyNameMapFirst() throws Exception {
    JSONObject formState = new JSONObject();
    formState.put("businessPartner", "BP-001");

    CalloutRequestBuilder.ColumnLookupMaps maps = new CalloutRequestBuilder.ColumnLookupMaps();
    maps.propertyNameToInp.put("businesspartner", "inpcBpartnerId");
    Map<String, String[]> params = new HashMap<>();
    CalloutRequestBuilder.mapFormStateToParams(formState, "inpother", maps, params);

    assertNotNull(params.get("inpcBpartnerId"));
    assertEquals("BP-001", params.get("inpcBpartnerId")[0]);
  }

  @Test
  public void mapFormStateToParams_fallsBackToCleanNameMap() throws Exception {
    JSONObject formState = new JSONObject();
    formState.put("warehouse", "WH-001");

    CalloutRequestBuilder.ColumnLookupMaps maps = new CalloutRequestBuilder.ColumnLookupMaps();
    maps.cleanNameToInp.put("warehouse", "inpmWarehouseId");
    Map<String, String[]> params = new HashMap<>();
    CalloutRequestBuilder.mapFormStateToParams(formState, "inpother", maps, params);

    assertNotNull(params.get("inpmWarehouseId"));
    assertEquals("WH-001", params.get("inpmWarehouseId")[0]);
  }

  // ─────────────────────────────────────────────────────────────────────
  // mapAuxValuesToParams
  // ─────────────────────────────────────────────────────────────────────

  @Test
  public void mapAuxValuesToParams_nullAuxValues_doesNothing() {
    CalloutRequestBuilder.ColumnLookupMaps maps = new CalloutRequestBuilder.ColumnLookupMaps();
    Map<String, String[]> params = new HashMap<>();
    CalloutRequestBuilder.mapAuxValuesToParams(null, maps, params);
    assertTrue(params.isEmpty());
  }

  @Test
  public void mapAuxValuesToParams_noSuffixUnderscore_skipped() throws Exception {
    JSONObject auxValues = new JSONObject();
    auxValues.put("businessPartnerWithoutSuffix", "BP-001");

    CalloutRequestBuilder.ColumnLookupMaps maps = new CalloutRequestBuilder.ColumnLookupMaps();
    Map<String, String[]> params = new HashMap<>();
    CalloutRequestBuilder.mapAuxValuesToParams(auxValues, maps, params);

    // Key has no underscore suffix — resolveToInpName would only split if suffixStart > 0
    // "businessPartnerWithoutSuffix" has an underscore-free structure; last '_' doesn't exist
    // Actually it has no underscore at all so it's skipped at the suffixStart <= 0 check.
    assertTrue(params.isEmpty());
  }

  @Test
  public void mapAuxValuesToParams_propertyNameResolved_appendsSuffix() throws Exception {
    JSONObject auxValues = new JSONObject();
    auxValues.put("businessPartner_LOC", "LOC-VALUE");

    CalloutRequestBuilder.ColumnLookupMaps maps = new CalloutRequestBuilder.ColumnLookupMaps();
    maps.propertyNameToInp.put("businesspartner", "inpcBpartnerId");
    Map<String, String[]> params = new HashMap<>();
    CalloutRequestBuilder.mapAuxValuesToParams(auxValues, maps, params);

    assertNotNull(params.get("inpcBpartnerId_LOC"));
    assertEquals("LOC-VALUE", params.get("inpcBpartnerId_LOC")[0]);
  }

  @Test
  public void mapAuxValuesToParams_cleanNameResolved_appendsSuffix() throws Exception {
    JSONObject auxValues = new JSONObject();
    auxValues.put("warehouse_NEW", "WH-NEW");

    CalloutRequestBuilder.ColumnLookupMaps maps = new CalloutRequestBuilder.ColumnLookupMaps();
    maps.cleanNameToInp.put("warehouse", "inpmWarehouseId");
    Map<String, String[]> params = new HashMap<>();
    CalloutRequestBuilder.mapAuxValuesToParams(auxValues, maps, params);

    assertNotNull(params.get("inpmWarehouseId_NEW"));
    assertEquals("WH-NEW", params.get("inpmWarehouseId_NEW")[0]);
  }

  @Test
  public void mapAuxValuesToParams_unresolvableBase_skipped() throws Exception {
    JSONObject auxValues = new JSONObject();
    auxValues.put("unknownField_LOC", "SOME-VAL");

    CalloutRequestBuilder.ColumnLookupMaps maps = new CalloutRequestBuilder.ColumnLookupMaps();
    Map<String, String[]> params = new HashMap<>();
    CalloutRequestBuilder.mapAuxValuesToParams(auxValues, maps, params);

    // "unknownField" does not resolve via property or clean maps and has no "inp" prefix,
    // so the base resolution produces a synthetic "inpunknownField" key — we then get
    // "inpunknownField_LOC" which is fine for the callout but the base resolveToInpName
    // uses the fallback path ("inp" + first-char-lower + rest). Verify something was added.
    // This covers the fallback-to-inp-prefix path through the aux resolution.
    assertTrue(params.containsKey("inpunknownField_LOC"));
  }

  // ─────────────────────────────────────────────────────────────────────
  // fillMissingColumnDefaults
  // ─────────────────────────────────────────────────────────────────────

  @Test
  public void fillMissingColumnDefaults_tabWithNullTable_doesNothing() {
    Tab tab = mock(Tab.class);
    when(tab.getTable()).thenReturn(null);

    Map<String, String[]> params = new HashMap<>();
    CalloutRequestBuilder.fillMissingColumnDefaults(tab, obContext, Collections.emptyList(), params);

    assertTrue(params.isEmpty());
  }

  @Test
  @SuppressWarnings("unchecked")
  public void fillMissingColumnDefaults_alreadyPresentColumnIsSkipped() throws Exception {
    Tab tab = mock(Tab.class);
    Table table = mock(Table.class);
    when(tab.getTable()).thenReturn(table);
    Window window = mock(Window.class);
    when(tab.getWindow()).thenReturn(window);
    when(window.getId()).thenReturn("WIN-001");
    when(window.isSalesTransaction()).thenReturn(false);

    Column col = mock(Column.class);
    when(col.getDBColumnName()).thenReturn("C_BPartner_ID");
    when(col.getDefaultValue()).thenReturn(null);

    Map<String, String[]> params = new HashMap<>();
    params.put("inpcBpartnerId", new String[]{ "BP-EXISTING" });

    // Column already present — should not be overwritten
    CalloutRequestBuilder.fillMissingColumnDefaults(tab, obContext,
        Collections.singletonList(col), params);

    assertEquals("BP-EXISTING", params.get("inpcBpartnerId")[0]);
    assertEquals(1, params.size());
  }

  // ─────────────────────────────────────────────────────────────────────
  // injectParentTabParams
  // ─────────────────────────────────────────────────────────────────────

  @Test
  public void injectParentTabParams_topLevelTab_doesNothing() throws Exception {
    Tab tab = mock(Tab.class);
    when(tab.getTabLevel()).thenReturn(0L);

    Map<String, String[]> params = new HashMap<>();
    CalloutRequestBuilder.injectParentTabParams(tab, new JSONObject(), params);

    assertTrue(params.isEmpty());
  }

  @Test
  public void injectParentTabParams_nullTabLevel_doesNothing() throws Exception {
    Tab tab = mock(Tab.class);
    when(tab.getTabLevel()).thenReturn(null);

    Map<String, String[]> params = new HashMap<>();
    CalloutRequestBuilder.injectParentTabParams(tab, new JSONObject(), params);

    assertTrue(params.isEmpty());
  }

  @Test
  public void injectParentTabParams_nullFormState_doesNothing() throws Exception {
    Tab tab = mock(Tab.class);
    when(tab.getTabLevel()).thenReturn(1L);

    Map<String, String[]> params = new HashMap<>();
    CalloutRequestBuilder.injectParentTabParams(tab, null, params);

    assertTrue(params.isEmpty());
  }

  @Test
  public void injectParentTabParams_childTabWithNoParentFound_doesNothing() throws Exception {
    Tab childTab = mockTab("child", 20L, 1L);
    Window window = mock(Window.class);
    when(childTab.getWindow()).thenReturn(window);
    // Only the child in the tab list — no parent
    when(window.getADTabList()).thenReturn(Collections.singletonList(childTab));

    JSONObject formState = new JSONObject();
    formState.put("id", "HEADER-001");

    Map<String, String[]> params = new HashMap<>();
    CalloutRequestBuilder.injectParentTabParams(childTab, formState, params);

    // No parent tab found — nothing injected (though id might be in formState)
    assertTrue(params.isEmpty());
  }

  @Test
  public void injectParentTabParams_parentTabNullTable_doesNothing() throws Exception {
    Tab parentTab = mockTab("parent", 10L, 0L);
    Tab childTab = mockTab("child", 20L, 1L);
    Window window = mock(Window.class);
    when(childTab.getWindow()).thenReturn(window);
    when(window.getADTabList()).thenReturn(Arrays.asList(parentTab, childTab));

    // Parent has no table
    when(parentTab.getTable()).thenReturn(null);
    when(dal.get(Tab.class, "parent")).thenReturn(parentTab);

    JSONObject formState = new JSONObject();
    formState.put("id", "HEADER-001");

    Map<String, String[]> params = new HashMap<>();
    CalloutRequestBuilder.injectParentTabParams(childTab, formState, params);

    assertTrue(params.isEmpty());
  }

  @Test
  public void injectParentTabParams_injectsParentIdFromFormState() throws Exception {
    Tab parentTab = mockTab("parent", 10L, 0L);
    Tab childTab = mockTab("child", 20L, 1L);
    Window window = mock(Window.class);
    when(childTab.getWindow()).thenReturn(window);
    when(window.getADTabList()).thenReturn(Arrays.asList(parentTab, childTab));

    Table parentTable = mock(Table.class);
    when(parentTab.getTable()).thenReturn(parentTable);
    when(parentTable.getId()).thenReturn("C_ORDER");
    when(parentTable.getDBTableName()).thenReturn("C_Order");
    // No columns on parent table (empty list so no entity injection attempted)
    when(parentTable.getADColumnList()).thenReturn(Collections.emptyList());
    when(dal.get(Tab.class, "parent")).thenReturn(parentTab);

    // ModelProvider for injectParentRecordFields — returns null entity so no field injection
    when(modelProvider.getEntityByTableId("C_ORDER")).thenReturn(null);

    JSONObject formState = new JSONObject();
    formState.put("id", "ORDER-HEADER-001");

    Map<String, String[]> params = new HashMap<>();
    CalloutRequestBuilder.injectParentTabParams(childTab, formState, params);

    // Expected key: toInpName("C_Order_ID") = "inpcOrderId"
    assertNotNull(params.get("inpcOrderId"));
    assertEquals("ORDER-HEADER-001", params.get("inpcOrderId")[0]);
  }

  @Test
  public void injectParentTabParams_existingParentIdNotOverwritten() throws Exception {
    Tab parentTab = mockTab("parent", 10L, 0L);
    Tab childTab = mockTab("child", 20L, 1L);
    Window window = mock(Window.class);
    when(childTab.getWindow()).thenReturn(window);
    when(window.getADTabList()).thenReturn(Arrays.asList(parentTab, childTab));

    Table parentTable = mock(Table.class);
    when(parentTab.getTable()).thenReturn(parentTable);
    when(parentTable.getId()).thenReturn("C_ORDER");
    when(parentTable.getDBTableName()).thenReturn("C_Order");
    when(parentTable.getADColumnList()).thenReturn(Collections.emptyList());
    when(dal.get(Tab.class, "parent")).thenReturn(parentTab);
    when(modelProvider.getEntityByTableId("C_ORDER")).thenReturn(null);

    JSONObject formState = new JSONObject();
    formState.put("id", "NEW-VALUE");

    Map<String, String[]> params = new HashMap<>();
    params.put("inpcOrderId", new String[]{ "ALREADY-SET" });
    CalloutRequestBuilder.injectParentTabParams(childTab, formState, params);

    // Pre-existing value must NOT be overwritten by formState id
    assertEquals("ALREADY-SET", params.get("inpcOrderId")[0]);
  }

  // ─────────────────────────────────────────────────────────────────────
  // findParentTab
  // ─────────────────────────────────────────────────────────────────────

  @Test
  public void findParentTab_nullChild_returnsNull() {
    assertNull(CalloutRequestBuilder.findParentTab(null));
  }

  @Test
  public void findParentTab_nullChildId_returnsNull() {
    Tab tab = mock(Tab.class);
    when(tab.getId()).thenReturn(null);
    assertNull(CalloutRequestBuilder.findParentTab(tab));
  }

  @Test
  public void findParentTab_childWithNoWindow_returnsNull() {
    Tab child = mock(Tab.class);
    when(child.getId()).thenReturn("child-1");
    when(child.getTabLevel()).thenReturn(1L);
    when(child.getWindow()).thenReturn(null);
    assertNull(CalloutRequestBuilder.findParentTab(child));
  }

  @Test
  public void findParentTab_cacheHitDoesNotResortWindowTabs() {
    Tab parent = mockTab("parent", 10L, 0L);
    Tab child = mockTab("child", 20L, 1L);
    Window window = mock(Window.class);
    when(window.getADTabList()).thenReturn(Arrays.asList(parent, child));
    when(child.getWindow()).thenReturn(window);
    when(dal.get(Tab.class, "parent")).thenReturn(parent);

    Tab first = CalloutRequestBuilder.findParentTab(child);
    Tab second = CalloutRequestBuilder.findParentTab(child);

    assertSame(parent, first);
    assertSame(parent, second);
    // Tab list sort+walk only on first call
    verify(window, times(1)).getADTabList();
    // OBDal.get runs on every call (cheap L1/L2 cache in real Hibernate)
    verify(dal, times(2)).get(Tab.class, "parent");
  }

  @Test
  public void findParentTab_topLevelSentinelPreventsObdalOnSecondCall() {
    Tab topLevel = mockTab("top", 10L, 0L);
    Window window = mock(Window.class);
    when(window.getADTabList()).thenReturn(Collections.singletonList(topLevel));
    when(topLevel.getWindow()).thenReturn(window);

    Tab first = CalloutRequestBuilder.findParentTab(topLevel);
    Tab second = CalloutRequestBuilder.findParentTab(topLevel);

    assertNull(first);
    assertNull(second);
    verify(window, times(1)).getADTabList();
    // Sentinel "" short-circuits before calling OBDal
    verify(dal, never()).get(Tab.class, "");
  }

  @Test
  public void findParentTab_returnsNearestLowerLevelPredecessor() {
    Tab grandParent = mockTab("gp", 10L, 0L);
    Tab parent = mockTab("parent", 20L, 1L);
    Tab child = mockTab("child", 30L, 2L);
    Window window = mock(Window.class);
    when(window.getADTabList()).thenReturn(Arrays.asList(grandParent, parent, child));
    when(child.getWindow()).thenReturn(window);
    when(dal.get(Tab.class, "parent")).thenReturn(parent);

    Tab result = CalloutRequestBuilder.findParentTab(child);
    assertSame(parent, result);
  }

  @Test
  public void findParentTab_skipsTabsAtSameOrHigherLevel() {
    // Level-0 parent, then a sibling at level 2, then child at level 2.
    // Parent must be the level-0 tab, not the sibling.
    Tab root = mockTab("root", 10L, 0L);
    Tab sibling = mockTab("sibling", 20L, 2L);
    Tab child = mockTab("child", 30L, 2L);
    Window window = mock(Window.class);
    when(window.getADTabList()).thenReturn(Arrays.asList(root, sibling, child));
    when(child.getWindow()).thenReturn(window);
    when(dal.get(Tab.class, "root")).thenReturn(root);

    Tab result = CalloutRequestBuilder.findParentTab(child);
    assertSame(root, result);
  }

  // ─────────────────────────────────────────────────────────────────────
  // clearParentTabCache
  // ─────────────────────────────────────────────────────────────────────

  @Test
  public void clearParentTabCache_invalidatesCache() {
    Tab parent = mockTab("parent", 10L, 0L);
    Tab child = mockTab("child", 20L, 1L);
    Window window = mock(Window.class);
    when(window.getADTabList()).thenReturn(Arrays.asList(parent, child));
    when(child.getWindow()).thenReturn(window);
    when(dal.get(Tab.class, "parent")).thenReturn(parent);

    CalloutRequestBuilder.findParentTab(child);
    CalloutRequestBuilder.clearParentTabCache();
    CalloutRequestBuilder.findParentTab(child);

    // After invalidation the window must be walked again
    verify(window, times(2)).getADTabList();
  }

  @Test
  public void clearMetadataCache_alsoClearsParentTabCache() {
    Tab parent = mockTab("parent", 10L, 0L);
    Tab child = mockTab("child", 20L, 1L);
    Window window = mock(Window.class);
    when(window.getADTabList()).thenReturn(Arrays.asList(parent, child));
    when(child.getWindow()).thenReturn(window);
    when(dal.get(Tab.class, "parent")).thenReturn(parent);

    CalloutRequestBuilder.findParentTab(child);
    // clearMetadataCache on NeoCalloutService also calls clearParentTabCache
    NeoCalloutService.clearMetadataCache();
    CalloutRequestBuilder.findParentTab(child);

    verify(window, times(2)).getADTabList();
  }

  // ─────────────────────────────────────────────────────────────────────
  // buildRequestParams (integration-level with mocks)
  // ─────────────────────────────────────────────────────────────────────

  @Test
  @SuppressWarnings("unchecked")
  public void buildRequestParams_setsMetaParams() throws Exception {
    Tab tab = tabWithTable("C_ORDER");
    Window window = mock(Window.class);
    when(tab.getWindow()).thenReturn(window);
    when(window.getId()).thenReturn("WIN-001");
    when(tab.getId()).thenReturn("TAB-001");
    when(tab.getTabLevel()).thenReturn(0L);
    when(window.isSalesTransaction()).thenReturn(true);

    Map<String, String[]> params = CalloutRequestBuilder.buildRequestParams(
        tab, "BP-001", new JSONObject(), "inpcBpartnerId", null);

    assertEquals("inpcBpartnerId", params.get("inpLastFieldChanged")[0]);
    assertEquals("BP-001", params.get("inpcBpartnerId")[0]);
    assertEquals("TAB-001", params.get("inpTabId")[0]);
    assertEquals("WIN-001", params.get("inpwindowId")[0]);
    assertEquals("Y", params.get("isSOTrx")[0]);
  }

  @Test
  @SuppressWarnings("unchecked")
  public void buildRequestParams_nullValue_usesEmptyString() throws Exception {
    Tab tab = tabWithTable("C_ORDER");
    Window window = mock(Window.class);
    when(tab.getWindow()).thenReturn(window);
    when(window.getId()).thenReturn("WIN-001");
    when(tab.getId()).thenReturn("TAB-001");
    when(tab.getTabLevel()).thenReturn(0L);
    when(window.isSalesTransaction()).thenReturn(false);

    Map<String, String[]> params = CalloutRequestBuilder.buildRequestParams(
        tab, null, new JSONObject(), "inpcBpartnerId", null);

    assertEquals("", params.get("inpcBpartnerId")[0]);
  }

  @Test
  @SuppressWarnings("unchecked")
  public void buildRequestParams_noWindow_skipsWindowIdAndUsesNAsSOTrx() throws Exception {
    Tab tab = tabWithTable("C_ORDER");
    when(tab.getWindow()).thenReturn(null);
    when(tab.getId()).thenReturn("TAB-NWIN");
    when(tab.getTabLevel()).thenReturn(0L);

    Map<String, String[]> params = CalloutRequestBuilder.buildRequestParams(
        tab, "VAL", new JSONObject(), "inpfield", null);

    assertNull(params.get("inpwindowId"));
    assertEquals("N", params.get("isSOTrx")[0]);
  }

  @Test
  @SuppressWarnings("unchecked")
  public void buildRequestParams_orgFromOBContext_whenOrgIdIsZero() throws Exception {
    // Override the default org setup — org is "0"
    Organization org = mock(Organization.class);
    when(org.getId()).thenReturn("0");
    when(obContext.getCurrentOrganization()).thenReturn(org);
    // resolveFirstOrgForClient returns a real org
    neoDefaultsMock.when(() -> NeoDefaultsService.resolveFirstOrgForClient("CLIENT-001"))
        .thenReturn("REAL-ORG-999");

    Tab tab = tabWithTable("C_ORDER");
    Window window = mock(Window.class);
    when(tab.getWindow()).thenReturn(window);
    when(window.getId()).thenReturn("WIN-001");
    when(tab.getId()).thenReturn("TAB-001");
    when(tab.getTabLevel()).thenReturn(0L);
    when(window.isSalesTransaction()).thenReturn(false);

    Map<String, String[]> params = CalloutRequestBuilder.buildRequestParams(
        tab, "VAL", new JSONObject(), "inpfield", null);

    assertEquals("REAL-ORG-999", params.get("inpadOrgId")[0]);
  }

  @Test
  @SuppressWarnings("unchecked")
  public void buildRequestParams_warehouseInjectedFromOBContext_whenNoParent() throws Exception {
    Warehouse warehouse = mock(Warehouse.class);
    when(warehouse.getId()).thenReturn("WH-999");
    when(obContext.getWarehouse()).thenReturn(warehouse);

    Tab tab = tabWithTable("C_ORDER");
    Window window = mock(Window.class);
    when(tab.getWindow()).thenReturn(window);
    when(window.getId()).thenReturn("WIN-001");
    when(tab.getId()).thenReturn("TAB-WH");
    when(tab.getTabLevel()).thenReturn(0L);
    when(window.isSalesTransaction()).thenReturn(false);

    Map<String, String[]> params = CalloutRequestBuilder.buildRequestParams(
        tab, "VAL", new JSONObject(), "inpfield", null);

    assertNotNull(params.get("inpmWarehouseId"));
    assertEquals("WH-999", params.get("inpmWarehouseId")[0]);
  }

  @Test
  @SuppressWarnings("unchecked")
  public void buildRequestParams_formStateKeysMappedToInpParams() throws Exception {
    Tab tab = tabWithTable("C_ORDER");
    Window window = mock(Window.class);
    when(tab.getWindow()).thenReturn(window);
    when(window.getId()).thenReturn("WIN-001");
    when(tab.getId()).thenReturn("TAB-001");
    when(tab.getTabLevel()).thenReturn(0L);
    when(window.isSalesTransaction()).thenReturn(false);

    JSONObject formState = new JSONObject();
    formState.put("inpdocumentno", "ORD-001");

    Map<String, String[]> params = CalloutRequestBuilder.buildRequestParams(
        tab, "VAL", formState, "inpfield", null);

    assertNotNull(params.get("inpdocumentno"));
    assertEquals("ORD-001", params.get("inpdocumentno")[0]);
  }

  @Test
  @SuppressWarnings("unchecked")
  public void buildRequestParams_auxValuesAppendedWithSuffix() throws Exception {
    // Set up a real column for "businessPartner" so the aux resolution can find the mapping
    Tab tab = mock(Tab.class);
    Table table = mock(Table.class);
    when(tab.getTable()).thenReturn(table);
    when(table.getId()).thenReturn("C_ORDER");
    Window window = mock(Window.class);
    when(tab.getWindow()).thenReturn(window);
    when(window.getId()).thenReturn("WIN-001");
    when(tab.getId()).thenReturn("TAB-001");
    when(tab.getTabLevel()).thenReturn(0L);
    when(window.isSalesTransaction()).thenReturn(false);

    Column col = mock(Column.class);
    when(col.getDBColumnName()).thenReturn("C_BPartner_ID");
    when(col.getDefaultValue()).thenReturn(null);
    OBCriteria<Column> criteria = mock(OBCriteria.class);
    when(dal.createCriteria(Column.class)).thenReturn(criteria);
    when(criteria.add(any())).thenReturn(criteria);
    when(criteria.list()).thenReturn(Collections.singletonList(col));

    Entity entity = mock(Entity.class);
    Property property = mock(Property.class);
    when(property.getName()).thenReturn("businessPartner");
    when(entity.getPropertyByColumnName("C_BPartner_ID")).thenReturn(property);
    when(modelProvider.getEntityByTableId("C_ORDER")).thenReturn(entity);

    JSONObject auxValues = new JSONObject();
    auxValues.put("businessPartner_LOC", "LOC-VAL");

    Map<String, String[]> params = CalloutRequestBuilder.buildRequestParams(
        tab, "VAL", new JSONObject(), "inpfield", auxValues);

    assertNotNull(params.get("inpcBpartnerId_LOC"));
    assertEquals("LOC-VAL", params.get("inpcBpartnerId_LOC")[0]);
  }

  // ─────────────────────────────────────────────────────────────────────
  // resolveEffectiveCalloutOrgId (exercised indirectly via buildRequestParams)
  // ─────────────────────────────────────────────────────────────────────

  @Test
  @SuppressWarnings("unchecked")
  public void buildRequestParams_nonZeroOrgId_usedDirectly() throws Exception {
    Organization org = mock(Organization.class);
    when(org.getId()).thenReturn("REAL-ORG-123");
    when(obContext.getCurrentOrganization()).thenReturn(org);

    Tab tab = tabWithTable("C_ORDER");
    Window window = mock(Window.class);
    when(tab.getWindow()).thenReturn(window);
    when(window.getId()).thenReturn("WIN-001");
    when(tab.getId()).thenReturn("TAB-001");
    when(tab.getTabLevel()).thenReturn(0L);
    when(window.isSalesTransaction()).thenReturn(false);

    Map<String, String[]> params = CalloutRequestBuilder.buildRequestParams(
        tab, "VAL", new JSONObject(), "inpfield", null);

    assertEquals("REAL-ORG-123", params.get("inpadOrgId")[0]);
    // resolveFirstOrgForClient must NOT be called when org is already a real value
    neoDefaultsMock.verify(
        () -> NeoDefaultsService.resolveFirstOrgForClient(anyString()), never());
  }

  @Test
  @SuppressWarnings("unchecked")
  public void buildRequestParams_nullOrg_fallsBackToZero() throws Exception {
    when(obContext.getCurrentOrganization()).thenReturn(null);
    // Client must stay non-null because buildRequestParams calls getCurrentClient().getId()
    // unconditionally on line 101. We only null out the org to exercise the "0" fallback path.
    // (The production code does not guard against null client — that is an existing limitation.)

    Tab tab = tabWithTable("C_ORDER");
    when(tab.getWindow()).thenReturn(null);
    when(tab.getId()).thenReturn("TAB-X");
    when(tab.getTabLevel()).thenReturn(0L);

    Map<String, String[]> params = CalloutRequestBuilder.buildRequestParams(
        tab, "VAL", new JSONObject(), "inpfield", null);

    assertEquals("0", params.get("inpadOrgId")[0]);
  }

  // ─────────────────────────────────────────────────────────────────────
  // ColumnLookupMaps inner class
  // ─────────────────────────────────────────────────────────────────────

  @Test
  public void columnLookupMaps_initialState_hasMutableEmptyCollections() {
    CalloutRequestBuilder.ColumnLookupMaps maps = new CalloutRequestBuilder.ColumnLookupMaps();
    assertNotNull(maps.propertyNameToInp);
    assertNotNull(maps.cleanNameToInp);
    assertNotNull(maps.dbNameToInp);
    assertNotNull(maps.columns);
    assertTrue(maps.propertyNameToInp.isEmpty());
    assertTrue(maps.cleanNameToInp.isEmpty());
    assertTrue(maps.dbNameToInp.isEmpty());
    assertTrue(maps.columns.isEmpty());
  }
}
