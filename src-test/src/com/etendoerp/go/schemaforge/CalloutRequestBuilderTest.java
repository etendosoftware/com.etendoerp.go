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

import java.math.BigDecimal;
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
import org.openbravo.model.ad.domain.Reference;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.ad.ui.Tab;
import org.openbravo.model.ad.ui.Window;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.common.enterprise.Warehouse;

/**
 * Unit tests for {@link CalloutRequestBuilder}.
 *
 * <p>All tests mock the Etendo static singletons (OBDal, OBContext, ModelProvider,
 * NeoDefaultsSqlHelper) so no live database is required. Mocks are opened in
 * {@link #setUp()} and closed in reverse order in {@link #tearDown()} to guarantee
 * correct isolation when the full suite runs.</p>
 */
public class CalloutRequestBuilderTest {

  // Static singletons that carry JVM-wide state and MUST be mocked for every test.
  private MockedStatic<OBDal> obDalMock;
  private MockedStatic<OBContext> obContextMock;
  private MockedStatic<ModelProvider> modelProviderMock;
  private MockedStatic<NeoDefaultsSqlHelper> neoSqlHelperMock;

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

    // ── NeoDefaultsSqlHelper ──────────────────────────────────────────
    neoSqlHelperMock = mockStatic(NeoDefaultsSqlHelper.class);
    neoSqlHelperMock.when(() -> NeoDefaultsSqlHelper.resolveFirstOrgForClient(anyString()))
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
    neoSqlHelperMock.close();
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

  @Test
  public void mapAuxValuesToParams_booleanTrue_becomesY() throws Exception {
    // ETP-4784: aux values were the last bridge point still serialized raw via optString,
    // which would render a JSON boolean as "true" and break the Classic "Y"/"N" comparison.
    JSONObject auxValues = new JSONObject();
    auxValues.put("salesTransaction_LOC", true);

    CalloutRequestBuilder.ColumnLookupMaps maps = new CalloutRequestBuilder.ColumnLookupMaps();
    maps.propertyNameToInp.put("salestransaction", "inpissotrx");
    Map<String, String[]> params = new HashMap<>();
    CalloutRequestBuilder.mapAuxValuesToParams(auxValues, maps, params);

    assertNotNull(params.get("inpissotrx_LOC"));
    assertEquals("Y", params.get("inpissotrx_LOC")[0]);
  }

  @Test
  public void mapAuxValuesToParams_booleanFalse_becomesN() throws Exception {
    JSONObject auxValues = new JSONObject();
    auxValues.put("salesTransaction_LOC", false);

    CalloutRequestBuilder.ColumnLookupMaps maps = new CalloutRequestBuilder.ColumnLookupMaps();
    maps.propertyNameToInp.put("salestransaction", "inpissotrx");
    Map<String, String[]> params = new HashMap<>();
    CalloutRequestBuilder.mapAuxValuesToParams(auxValues, maps, params);

    assertNotNull(params.get("inpissotrx_LOC"));
    assertEquals("N", params.get("inpissotrx_LOC")[0]);
  }

  @Test
  public void mapAuxValuesToParams_idStringValueUnchangedAfterConverterSwitch() throws Exception {
    // Regression guard: routing aux values through toClassicParamValue must not alter the
    // ordinary id/string case that selectors emit in practice.
    JSONObject auxValues = new JSONObject();
    auxValues.put("businessPartner_LOC", "5D2AFF1C4A0B4E3E9E2B0C1D2E3F4A5B");

    CalloutRequestBuilder.ColumnLookupMaps maps = new CalloutRequestBuilder.ColumnLookupMaps();
    maps.propertyNameToInp.put("businesspartner", "inpcBpartnerId");
    Map<String, String[]> params = new HashMap<>();
    CalloutRequestBuilder.mapAuxValuesToParams(auxValues, maps, params);

    assertNotNull(params.get("inpcBpartnerId_LOC"));
    assertEquals("5D2AFF1C4A0B4E3E9E2B0C1D2E3F4A5B", params.get("inpcBpartnerId_LOC")[0]);
  }

  @Test
  public void mapAuxValuesToParams_jsonNullValue_becomesEmptyString() throws Exception {
    // Same behaviour the previous optString(key, "") default provided.
    JSONObject auxValues = new JSONObject();
    auxValues.put("businessPartner_LOC", JSONObject.NULL);

    CalloutRequestBuilder.ColumnLookupMaps maps = new CalloutRequestBuilder.ColumnLookupMaps();
    maps.propertyNameToInp.put("businesspartner", "inpcBpartnerId");
    Map<String, String[]> params = new HashMap<>();
    CalloutRequestBuilder.mapAuxValuesToParams(auxValues, maps, params);

    assertNotNull(params.get("inpcBpartnerId_LOC"));
    assertEquals("", params.get("inpcBpartnerId_LOC")[0]);
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
    neoSqlHelperMock.when(() -> NeoDefaultsSqlHelper.resolveFirstOrgForClient("CLIENT-001"))
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
    neoSqlHelperMock.verify(
        () -> NeoDefaultsSqlHelper.resolveFirstOrgForClient(anyString()), never());
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

  // ─────────────────────────────────────────────────────────────────────
  // reformatDateParams + getCalloutDatePattern (via buildRequestParams)
  // ─────────────────────────────────────────────────────────────────────

  /**
   * Exercises {@link CalloutRequestBuilder#reformatDateParams} and
   * {@link CalloutRequestBuilder#getCalloutDatePattern} end-to-end.
   *
   * <p>The column list returned by OBDal contains a Date column (AD reference 15).
   * The form state provides the date in ISO format; after {@code buildRequestParams}
   * the param for that column must be in the Etendo UI date format (dd-MM-yyyy).</p>
   */
  @Test
  @SuppressWarnings("unchecked")
  public void buildRequestParams_reformatsDateColumnFromIsoToEtendo() throws Exception {
    // Date column: "DateAcct" → inpdateacct, AD reference 15
    Column dateCol = mock(Column.class);
    when(dateCol.getDBColumnName()).thenReturn("DateAcct");
    Reference dateRef = mock(Reference.class);
    when(dateRef.getId()).thenReturn("15");
    when(dateCol.getReference()).thenReturn(dateRef);
    when(dateCol.getDefaultValue()).thenReturn(null);

    Tab tab = mock(Tab.class);
    Table table = mock(Table.class);
    when(tab.getTable()).thenReturn(table);
    when(table.getId()).thenReturn("GL_JOURNAL");
    when(tab.getId()).thenReturn("GL-TAB-001");
    when(tab.getWindow()).thenReturn(null);
    when(tab.getTabLevel()).thenReturn(0L);

    OBCriteria<Column> criteria = mock(OBCriteria.class);
    when(dal.createCriteria(Column.class)).thenReturn(criteria);
    when(criteria.add(any())).thenReturn(criteria);
    when(criteria.list()).thenReturn(Collections.singletonList(dateCol));
    when(modelProvider.getEntityByTableId("GL_JOURNAL")).thenReturn(null);

    // formState carries the date in ISO format; "inpfield" is the trigger → not overwritten
    JSONObject formState = new JSONObject();
    formState.put("inpdateacct", "2026-06-16");

    Map<String, String[]> params = CalloutRequestBuilder.buildRequestParams(
        tab, "VALUE", formState, "inpfield", null);

    // reformatDateParams converts "2026-06-16" → "16-06-2026" (Etendo dd-MM-yyyy fallback)
    assertNotNull(params.get("inpdateacct"));
    assertEquals("16-06-2026", params.get("inpdateacct")[0]);
  }

  // ─────────────────────────────────────────────────────────────────────
  // toClassicParamValue (ETP-4784) — JSON form-state value → Classic param string
  // ─────────────────────────────────────────────────────────────────────
  //
  // NEO's formState is typed JSON, so a Yes/No column arrives as a real JSON boolean
  // (salesTransaction: true). Classic callouts read raw request params and compare them
  // against Etendo's "Y"/"N" convention — e.g. SiiAutoSetSIIKEYByDefault gates its whole
  // branch on StringUtils.equals("Y", getStringParameter("inpissotrx")). Rendering the
  // boolean as "true" made that comparison fail silently and the branch never ran.

  @Test
  public void toClassicParamValue_booleanTrue_becomesY() {
    assertEquals("Y", CalloutRequestBuilder.toClassicParamValue(Boolean.TRUE));
  }

  @Test
  public void toClassicParamValue_booleanFalse_becomesN() {
    assertEquals("N", CalloutRequestBuilder.toClassicParamValue(Boolean.FALSE));
  }

  @Test
  public void toClassicParamValue_null_becomesEmptyString() {
    assertEquals("", CalloutRequestBuilder.toClassicParamValue(null));
  }

  @Test
  public void toClassicParamValue_jsonNullSentinel_becomesEmptyString() {
    assertEquals("", CalloutRequestBuilder.toClassicParamValue(JSONObject.NULL));
  }

  @Test
  public void toClassicParamValue_stringPassesThroughUnchanged() {
    assertEquals("BP-001", CalloutRequestBuilder.toClassicParamValue("BP-001"));
  }

  /**
   * A literal "true"/"false" STRING is not a JSON boolean and must survive untouched —
   * only real {@link Boolean} values are translated to the Y/N convention.
   */
  @Test
  public void toClassicParamValue_literalTrueStringIsNotTranslated() {
    assertEquals("true", CalloutRequestBuilder.toClassicParamValue("true"));
  }

  @Test
  public void toClassicParamValue_numberRenderedViaToString() {
    assertEquals("42", CalloutRequestBuilder.toClassicParamValue(Integer.valueOf(42)));
    assertEquals("12.50", CalloutRequestBuilder.toClassicParamValue(new BigDecimal("12.50")));
  }

  /**
   * An empty string stays empty (it is neither null nor the JSON NULL sentinel), so a
   * cleared field is still sent to the callout as a blank param rather than being dropped.
   */
  @Test
  public void toClassicParamValue_emptyStringStaysEmpty() {
    assertEquals("", CalloutRequestBuilder.toClassicParamValue(""));
  }

  /**
   * The real regression path (ETP-4784): a boolean in the formState must reach the callout
   * as "Y", not "true". Previously mapFormStateEntry used {@code optString}, which renders
   * a JSON boolean as "true" and broke every Classic callout comparing against "Y".
   */
  @Test
  public void mapFormStateToParams_booleanFormStateValueMappedAsY() throws Exception {
    JSONObject formState = new JSONObject();
    formState.put("salesTransaction", true);

    CalloutRequestBuilder.ColumnLookupMaps maps = new CalloutRequestBuilder.ColumnLookupMaps();
    maps.propertyNameToInp.put("salestransaction", "inpissotrx");
    Map<String, String[]> params = new HashMap<>();
    CalloutRequestBuilder.mapFormStateToParams(formState, "inpother", maps, params);

    assertNotNull(params.get("inpissotrx"));
    assertEquals("Y", params.get("inpissotrx")[0]);
  }

  @Test
  public void mapFormStateToParams_falseBooleanFormStateValueMappedAsN() throws Exception {
    JSONObject formState = new JSONObject();
    formState.put("salesTransaction", false);

    CalloutRequestBuilder.ColumnLookupMaps maps = new CalloutRequestBuilder.ColumnLookupMaps();
    maps.propertyNameToInp.put("salestransaction", "inpissotrx");
    Map<String, String[]> params = new HashMap<>();
    CalloutRequestBuilder.mapFormStateToParams(formState, "inpother", maps, params);

    assertNotNull(params.get("inpissotrx"));
    assertEquals("N", params.get("inpissotrx")[0]);
  }

  /**
   * End-to-end through the public entry point: a boolean form-state value survives the whole
   * {@code buildRequestParams} pass as "Y" (this is exactly what the Classic callout reads).
   */
  @Test
  @SuppressWarnings("unchecked")
  public void buildRequestParams_booleanFormStateValueReachesCalloutAsY() throws Exception {
    Column soTrxCol = mock(Column.class);
    when(soTrxCol.getDBColumnName()).thenReturn("IsSOTrx");
    Reference boolRef = mock(Reference.class);
    when(boolRef.getId()).thenReturn("20");
    when(soTrxCol.getReference()).thenReturn(boolRef);
    when(soTrxCol.getDefaultValue()).thenReturn(null);

    Tab tab = mock(Tab.class);
    Table table = mock(Table.class);
    when(tab.getTable()).thenReturn(table);
    when(table.getId()).thenReturn("C_INVOICE");
    when(tab.getId()).thenReturn("INV-TAB-001");
    when(tab.getWindow()).thenReturn(null);
    when(tab.getTabLevel()).thenReturn(0L);

    OBCriteria<Column> criteria = mock(OBCriteria.class);
    when(dal.createCriteria(Column.class)).thenReturn(criteria);
    when(criteria.add(any())).thenReturn(criteria);
    when(criteria.list()).thenReturn(Collections.singletonList(soTrxCol));
    when(modelProvider.getEntityByTableId("C_INVOICE")).thenReturn(null);

    // Typed JSON boolean, as the NEO frontend actually sends it
    JSONObject formState = new JSONObject();
    formState.put("issotrx", true);

    Map<String, String[]> params = CalloutRequestBuilder.buildRequestParams(
        tab, "VALUE", formState, "inpfield", null);

    assertNotNull(params.get("inpissotrx"));
    assertEquals("Y", params.get("inpissotrx")[0]);
  }

  // ─────────────────────────────────────────────────────────────────────
  // buildRequestParams — the TRIGGER field's own value (ETP-4784)
  // ─────────────────────────────────────────────────────────────────────
  //
  // The trigger param is written straight from the `value` argument, bypassing
  // mapFormStateToParams entirely (mapFormStateEntry explicitly skips the trigger key).
  // It therefore needs its own Y/N normalization: before the fix it went through
  // value.toString(), so a Yes/No column that was itself the changed field reached the
  // callout as "true" — exactly the comparison every Classic callout fails on.

  /**
   * Builds a tab whose only column is the boolean {@code IsSOTrx}, used as the callout
   * trigger field so the trigger param itself carries the boolean value.
   */
  @SuppressWarnings("unchecked")
  private Tab booleanTriggerTab() {
    Column soTrxCol = mock(Column.class);
    when(soTrxCol.getDBColumnName()).thenReturn("IsSOTrx");
    Reference boolRef = mock(Reference.class);
    when(boolRef.getId()).thenReturn("20");
    when(soTrxCol.getReference()).thenReturn(boolRef);
    when(soTrxCol.getDefaultValue()).thenReturn(null);

    Tab tab = mock(Tab.class);
    Table table = mock(Table.class);
    when(tab.getTable()).thenReturn(table);
    when(table.getId()).thenReturn("C_INVOICE");
    when(tab.getId()).thenReturn("INV-TAB-TRIGGER");
    when(tab.getWindow()).thenReturn(null);
    when(tab.getTabLevel()).thenReturn(0L);

    OBCriteria<Column> criteria = mock(OBCriteria.class);
    when(dal.createCriteria(Column.class)).thenReturn(criteria);
    when(criteria.add(any())).thenReturn(criteria);
    when(criteria.list()).thenReturn(Collections.singletonList(soTrxCol));
    when(modelProvider.getEntityByTableId("C_INVOICE")).thenReturn(null);
    return tab;
  }

  /**
   * The regression the earlier suite missed: when the field that FIRES the callout is itself
   * a boolean, its param must be "Y" — not "true". The pre-existing boolean test used a
   * different key ("inpfield") as the trigger, so this exact path stayed unexercised.
   */
  @Test
  public void buildRequestParams_booleanTriggerFieldValueBecomesY() throws Exception {
    Map<String, String[]> params = CalloutRequestBuilder.buildRequestParams(
        booleanTriggerTab(), Boolean.TRUE, new JSONObject(), "inpissotrx", null);

    assertNotNull(params.get("inpissotrx"));
    assertEquals("Y", params.get("inpissotrx")[0]);
    // The trigger name itself is unaffected by the value normalization.
    assertEquals("inpissotrx", params.get("inpLastFieldChanged")[0]);
  }

  /** Same path for the negative value: the trigger param must be "N", never "false". */
  @Test
  public void buildRequestParams_booleanTriggerFieldValueBecomesN() throws Exception {
    Map<String, String[]> params = CalloutRequestBuilder.buildRequestParams(
        booleanTriggerTab(), Boolean.FALSE, new JSONObject(), "inpissotrx", null);

    assertNotNull(params.get("inpissotrx"));
    assertEquals("N", params.get("inpissotrx")[0]);
  }

  /**
   * A non-boolean trigger value keeps its plain string form — the normalization must not
   * leak into ordinary fields.
   */
  @Test
  public void buildRequestParams_nonBooleanTriggerFieldValueUnchanged() throws Exception {
    Map<String, String[]> params = CalloutRequestBuilder.buildRequestParams(
        booleanTriggerTab(), "BP-001", new JSONObject(), "inpissotrx", null);

    assertEquals("BP-001", params.get("inpissotrx")[0]);
  }

  // ─────────────────────────────────────────────────────────────────────
  // resolveFieldValueAsString (ETP-4784) — parent-tab record fields
  // ─────────────────────────────────────────────────────────────────────
  //
  // Parent-tab fields are read straight off the DAL bean, where a Yes/No column
  // materializes as a java.lang.Boolean. Rendering it with val.toString() injected "true"
  // into every child-tab callout request; it must obey the same Classic Y/N convention as
  // the form-state params. FK references still resolve to the referenced record's id.

  /**
   * Runs {@code injectParentTabParams} for a child tab whose parent record exposes the single
   * column {@code IsSOTrx} carrying {@code parentPropertyValue}, and returns the resulting
   * params so each test only asserts on the rendered value.
   */
  private Map<String, String[]> injectParentFieldValue(Object parentPropertyValue)
      throws Exception {
    Tab parentTab = mockTab("parent", 10L, 0L);
    Tab childTab = mockTab("child", 20L, 1L);
    Window window = mock(Window.class);
    when(childTab.getWindow()).thenReturn(window);
    when(window.getADTabList()).thenReturn(Arrays.asList(parentTab, childTab));

    Table parentTable = mock(Table.class);
    when(parentTab.getTable()).thenReturn(parentTable);
    when(parentTable.getId()).thenReturn("C_ORDER");
    when(parentTable.getDBTableName()).thenReturn("C_Order");

    Column col = mock(Column.class);
    when(col.isActive()).thenReturn(true);
    when(col.getDBColumnName()).thenReturn("IsSOTrx");
    when(parentTable.getADColumnList()).thenReturn(Collections.singletonList(col));
    when(dal.get(Tab.class, "parent")).thenReturn(parentTab);

    Entity parentEntity = mock(Entity.class);
    when(parentEntity.getName()).thenReturn("Order");
    Property prop = mock(Property.class);
    when(prop.getName()).thenReturn("salesTransaction");
    when(parentEntity.getPropertyByColumnName("IsSOTrx")).thenReturn(prop);
    when(modelProvider.getEntityByTableId("C_ORDER")).thenReturn(parentEntity);

    BaseOBObject parentRecord = mock(BaseOBObject.class);
    when(parentRecord.get("salesTransaction")).thenReturn(parentPropertyValue);
    when(dal.get("Order", "ORDER-HEADER-001")).thenReturn(parentRecord);

    JSONObject formState = new JSONObject();
    formState.put("id", "ORDER-HEADER-001");

    Map<String, String[]> params = new HashMap<>();
    CalloutRequestBuilder.injectParentTabParams(childTab, formState, params);
    return params;
  }

  /**
   * The header's Yes/No column reaches the child-tab callout as "Y" — DAL hands it over as a
   * Boolean, and {@code toString()} would have produced "true".
   */
  @Test
  public void injectParentTabParams_booleanParentFieldInjectedAsY() throws Exception {
    Map<String, String[]> params = injectParentFieldValue(Boolean.TRUE);

    assertNotNull(params.get("inpissotrx"));
    assertEquals("Y", params.get("inpissotrx")[0]);
  }

  /** Same for the negative value: "N", never "false". */
  @Test
  public void injectParentTabParams_falseBooleanParentFieldInjectedAsN() throws Exception {
    Map<String, String[]> params = injectParentFieldValue(Boolean.FALSE);

    assertNotNull(params.get("inpissotrx"));
    assertEquals("N", params.get("inpissotrx")[0]);
  }

  /** An FK-typed parent field still resolves to the referenced record's id, not its toString(). */
  @Test
  public void injectParentTabParams_baseObObjectParentFieldInjectedAsId() throws Exception {
    BaseOBObject referenced = mock(BaseOBObject.class);
    when(referenced.getId()).thenReturn("BP-001");

    Map<String, String[]> params = injectParentFieldValue(referenced);

    assertNotNull(params.get("inpissotrx"));
    assertEquals("BP-001", params.get("inpissotrx")[0]);
  }

  /** A null parent field is sent as a blank param, exactly like a cleared form-state field. */
  @Test
  public void injectParentTabParams_nullParentFieldInjectedAsEmptyString() throws Exception {
    Map<String, String[]> params = injectParentFieldValue(null);

    assertNotNull(params.get("inpissotrx"));
    assertEquals("", params.get("inpissotrx")[0]);
  }

  /** Plain strings pass through untouched. */
  @Test
  public void injectParentTabParams_stringParentFieldInjectedUnchanged() throws Exception {
    Map<String, String[]> params = injectParentFieldValue("ORD-001");

    assertEquals("ORD-001", params.get("inpissotrx")[0]);
  }

  /** Numeric parent fields keep their canonical string form (scale included). */
  @Test
  public void injectParentTabParams_numericParentFieldInjectedViaToString() throws Exception {
    Map<String, String[]> params = injectParentFieldValue(new BigDecimal("12.50"));

    assertEquals("12.50", params.get("inpissotrx")[0]);
  }
}
