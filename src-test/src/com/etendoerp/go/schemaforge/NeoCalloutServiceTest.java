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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.List;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.After;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.ModelProvider;
import org.openbravo.base.model.Property;
import org.openbravo.base.secureApp.LoginUtils;
import org.openbravo.base.secureApp.VariablesSecureApp;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.database.ConnectionProvider;
import org.openbravo.model.ad.access.Role;
import org.openbravo.model.ad.access.User;
import org.openbravo.model.ad.datamodel.Column;
import org.openbravo.model.ad.datamodel.Table;
import org.openbravo.model.ad.domain.Callout;
import org.openbravo.model.ad.domain.ModelImplementation;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.ad.system.Language;
import org.openbravo.model.ad.ui.Tab;
import org.openbravo.model.ad.ui.Window;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.common.enterprise.Warehouse;

import com.etendoerp.go.schemaforge.util.NeoSessionVarsCache;

/**
 * Unit tests for {@link NeoCalloutService}.
 * Tests field name mapping, response transformation, and synthetic request.
 * These tests do not require a database connection.
 */
public class NeoCalloutServiceTest {

  @After
  public void clearCalloutMetadataCache() {
    NeoCalloutService.clearMetadataCache();
  }

  // ── toInpName tests ────────────────────────────────────────────────

  @Test
  public void testToInpNameBasicColumn() {
    assertEquals("inpdocumentno", NeoCalloutService.toInpName("DocumentNo"));
  }

  @Test
  public void testToInpNameWithIdSuffix() {
    assertEquals("inpcBpartnerId", NeoCalloutService.toInpName("C_BPartner_ID"));
  }

  @Test
  public void testToInpNameWithWarehouse() {
    assertEquals("inpmWarehouseId", NeoCalloutService.toInpName("M_Warehouse_ID"));
  }

  @Test
  public void testToInpNameIsActive() {
    assertEquals("inpisactive", NeoCalloutService.toInpName("IsActive"));
  }

  @Test
  public void testToInpNameAdOrg() {
    assertEquals("inpadOrgId", NeoCalloutService.toInpName("AD_Org_ID"));
  }

  @Test
  public void testToInpNameSimple() {
    assertEquals("inpname", NeoCalloutService.toInpName("Name"));
  }

  @Test
  public void testToInpNameMultipleUnderscores() {
    assertEquals("inpcOrderlineId", NeoCalloutService.toInpName("C_OrderLine_ID"));
  }

  @Test
  public void testToInpNameEmpty() {
    assertEquals("inp", NeoCalloutService.toInpName(""));
  }

  // ── transformColumnName tests ──────────────────────────────────────

  @Test
  public void testTransformColumnNameBasic() {
    assertEquals("cBpartnerId", NeoCalloutService.transformColumnName("C_BPartner_ID"));
  }

  @Test
  public void testTransformColumnNameNoUnderscore() {
    assertEquals("documentno", NeoCalloutService.transformColumnName("DocumentNo"));
  }

  @Test
  public void testTransformColumnNameSingleChar() {
    assertEquals("x", NeoCalloutService.transformColumnName("X"));
  }

  @Test
  public void testTransformColumnNameEmpty() {
    assertEquals("", NeoCalloutService.transformColumnName(""));
  }

  @Test
  public void testTransformColumnNameNull() {
    assertEquals("", NeoCalloutService.transformColumnName(null));
  }

  // ── toCleanFieldName tests ─────────────────────────────────────────

  @Test
  public void testToCleanFieldNameForeignKey() {
    // C_BPartner_ID -> strip _ID -> C_BPartner -> strip prefix C_ -> BPartner -> camelCase
    assertEquals("bpartner", NeoCalloutService.toCleanFieldName("C_BPartner_ID"));
  }

  @Test
  public void testToCleanFieldNameWarehouse() {
    // M_Warehouse_ID -> strip _ID -> M_Warehouse -> strip prefix M_ -> Warehouse -> camelCase
    assertEquals("warehouse", NeoCalloutService.toCleanFieldName("M_Warehouse_ID"));
  }

  @Test
  public void testToCleanFieldNameNoSuffix() {
    assertEquals("documentno", NeoCalloutService.toCleanFieldName("DocumentNo"));
  }

  @Test
  public void testToCleanFieldNameAdOrg() {
    // AD_Org_ID -> strip _ID -> AD_Org -> strip prefix AD_ -> Org -> camelCase
    assertEquals("org", NeoCalloutService.toCleanFieldName("AD_Org_ID"));
  }

  @Test
  public void testToCleanFieldNameNull() {
    assertEquals("", NeoCalloutService.toCleanFieldName(null));
  }

  // ── Callout metadata cache tests ─────────────────────────────────────

  @Test
  public void testLoadColumnCalloutMetadataStoresOnlyScalarValues() {
    NeoCalloutService.clearMetadataCache();

    OBDal obDal = mock(OBDal.class);
    @SuppressWarnings("unchecked")
    OBCriteria<Column> criteria = mock(OBCriteria.class);
    Column column = mock(Column.class);
    Callout callout = mock(Callout.class);
    ModelImplementation implementation = mock(ModelImplementation.class);

    when(obDal.createCriteria(Column.class)).thenReturn(criteria);
    when(criteria.add(any())).thenReturn(criteria);
    when(criteria.list()).thenReturn(Collections.singletonList(column));
    when(column.getDBColumnName()).thenReturn("C_BPartner_ID");
    when(column.getCallout()).thenReturn(callout);
    when(callout.getName()).thenReturn("Business Partner Callout");
    when(callout.getADModelImplementationList())
        .thenReturn(Collections.singletonList(implementation));
    when(implementation.getJavaClassName()).thenReturn("com.example.BPartnerCallout");

    try (MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class)) {
      obDalMock.when(OBDal::getInstance).thenReturn(obDal);

      List<NeoCalloutService.ColumnCalloutMetadata> metadata =
          NeoCalloutService.loadColumnCalloutMetadata("C_ORDER");

      assertEquals(1, metadata.size());
      NeoCalloutService.ColumnCalloutMetadata item = metadata.get(0);
      assertEquals("C_BPartner_ID", item.dbColumnName);
      assertEquals("Business Partner Callout", item.calloutName);
      assertEquals("com.example.BPartnerCallout", item.className);
    }
  }

  @Test
  public void testResolveCalloutUsesCachedScalarMetadataWithoutTouchingDalAgain() {
    NeoCalloutService.clearMetadataCache();

    OBDal obDal = mock(OBDal.class);
    @SuppressWarnings("unchecked")
    OBCriteria<Column> criteria = mock(OBCriteria.class);
    Column column = mock(Column.class);
    Callout callout = mock(Callout.class);
    ModelImplementation implementation = mock(ModelImplementation.class);
    Tab tab = mock(Tab.class);
    Table table = mock(Table.class);
    ModelProvider modelProvider = mock(ModelProvider.class);
    Entity entity = mock(Entity.class);
    Property property = mock(Property.class);

    when(tab.getTable()).thenReturn(table);
    when(table.getId()).thenReturn("C_ORDER");
    when(obDal.createCriteria(Column.class)).thenReturn(criteria);
    when(criteria.add(any())).thenReturn(criteria);
    when(criteria.list()).thenReturn(Collections.singletonList(column));
    when(column.getDBColumnName()).thenReturn("C_BPartner_ID");
    when(column.getCallout()).thenReturn(callout);
    when(callout.getName()).thenReturn("Business Partner Callout");
    when(callout.getADModelImplementationList())
        .thenReturn(Collections.singletonList(implementation));
    when(implementation.getJavaClassName()).thenReturn("com.example.BPartnerCallout");
    when(modelProvider.getEntityByTableId("C_ORDER")).thenReturn(entity);
    when(entity.getPropertyByColumnName("C_BPartner_ID")).thenReturn(property);
    when(property.getName()).thenReturn("businessPartner");

    try (MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class);
        MockedStatic<ModelProvider> modelProviderMock = mockStatic(ModelProvider.class)) {
      obDalMock.when(OBDal::getInstance).thenReturn(obDal);
      modelProviderMock.when(ModelProvider::getInstance).thenReturn(modelProvider);

      NeoCalloutService.CalloutInfo first =
          NeoCalloutService.resolveCallout(tab, "businessPartner");

      assertNotNull(first);
      assertEquals("com.example.BPartnerCallout", first.className);
      assertEquals("inpcBpartnerId", first.inpFieldName);
      assertEquals("C_BPartner_ID", first.columnName);

      NeoCalloutService.CalloutInfo second =
          NeoCalloutService.resolveCallout(tab, "businessPartner");

      assertNotNull(second);
      assertEquals("com.example.BPartnerCallout", second.className);
      verify(obDal, times(1)).createCriteria(Column.class);
      verify(callout, times(1)).getADModelImplementationList();
    }
  }

  // ── Name mapping cache tests ────────────────────────────────────────

  /**
   * Build the mocks needed to drive {@code getNameMappings} for a single column.
   * Every test in this section uses the same shape: one column on a table, with the
   * Entity returning a property named {@code businessPartner}.
   */
  private MappingMocks setupSingleColumnMocks(String tableId, String dbColumnName,
      String propertyName) {
    OBDal obDal = mock(OBDal.class);
    @SuppressWarnings("unchecked")
    OBCriteria<Column> criteria = mock(OBCriteria.class);
    Column column = mock(Column.class);
    ModelProvider modelProvider = mock(ModelProvider.class);
    Entity entity = mock(Entity.class);
    Property property = mock(Property.class);

    when(obDal.createCriteria(Column.class)).thenReturn(criteria);
    when(criteria.add(any())).thenReturn(criteria);
    when(criteria.list()).thenReturn(Collections.singletonList(column));
    when(column.getDBColumnName()).thenReturn(dbColumnName);
    when(column.getCallout()).thenReturn(null);
    when(modelProvider.getEntityByTableId(tableId)).thenReturn(entity);
    when(entity.getPropertyByColumnName(dbColumnName)).thenReturn(property);
    when(property.getName()).thenReturn(propertyName);

    return new MappingMocks(obDal, modelProvider, entity);
  }

  private static class MappingMocks {
    final OBDal obDal;
    final ModelProvider modelProvider;
    final Entity entity;
    MappingMocks(OBDal obDal, ModelProvider modelProvider, Entity entity) {
      this.obDal = obDal;
      this.modelProvider = modelProvider;
      this.entity = entity;
    }
  }

  @Test
  public void testGetNameMappingsCachedAcrossInpAndProperty() {
    NeoCalloutService.clearMetadataCache();
    Tab tab = mock(Tab.class);
    Table table = mock(Table.class);
    when(tab.getTable()).thenReturn(table);
    when(table.getId()).thenReturn("C_ORDER");

    MappingMocks m = setupSingleColumnMocks("C_ORDER", "C_BPartner_ID", "businessPartner");

    try (MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class);
        MockedStatic<ModelProvider> modelProviderMock = mockStatic(ModelProvider.class)) {
      obDalMock.when(OBDal::getInstance).thenReturn(m.obDal);
      modelProviderMock.when(ModelProvider::getInstance).thenReturn(m.modelProvider);

      NeoCalloutService.TableNameMappings first =
          NeoCalloutService.getNameMappings("C_ORDER");
      NeoCalloutService.TableNameMappings second =
          NeoCalloutService.getNameMappings("C_ORDER");

      assertEquals("businessPartner", first.inpToProperty.get("inpcBpartnerId"));
      assertEquals("C_BPartner_ID", first.propertyToColumn.get("businessPartner"));
      // Same instance returned (computeIfAbsent is hit only once).
      assertTrue(first == second);
      // The DB and model lookups happen exactly once across both calls.
      verify(m.obDal, times(1)).createCriteria(Column.class);
      verify(m.entity, times(1)).getPropertyByColumnName("C_BPartner_ID");
    }
  }

  @Test
  public void testFromCleanFieldNameUsesCacheAndPreservesContract() {
    NeoCalloutService.clearMetadataCache();
    Tab tab = mock(Tab.class);
    Table table = mock(Table.class);
    when(tab.getTable()).thenReturn(table);
    when(table.getId()).thenReturn("C_ORDER");

    MappingMocks m = setupSingleColumnMocks("C_ORDER", "C_BPartner_ID", "businessPartner");

    try (MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class);
        MockedStatic<ModelProvider> modelProviderMock = mockStatic(ModelProvider.class)) {
      obDalMock.when(OBDal::getInstance).thenReturn(m.obDal);
      modelProviderMock.when(ModelProvider::getInstance).thenReturn(m.modelProvider);

      // Same contract as the old method: DAL property name → DB column name.
      assertEquals("C_BPartner_ID",
          NeoCalloutService.fromCleanFieldName("businessPartner", tab));
      assertEquals("C_BPartner_ID",
          NeoCalloutService.fromCleanFieldName("businessPartner", tab));

      // Unknown name still falls back to the input.
      assertEquals("totallyUnknown",
          NeoCalloutService.fromCleanFieldName("totallyUnknown", tab));

      // Three calls, but only one DB hit and one Entity lookup.
      verify(m.obDal, times(1)).createCriteria(Column.class);
      verify(m.entity, times(1)).getPropertyByColumnName("C_BPartner_ID");
    }
  }

  @Test
  public void testNameMappingsFallsBackWhenEntityLacksProperty() {
    NeoCalloutService.clearMetadataCache();

    OBDal obDal = mock(OBDal.class);
    @SuppressWarnings("unchecked")
    OBCriteria<Column> criteria = mock(OBCriteria.class);
    Column column = mock(Column.class);
    ModelProvider modelProvider = mock(ModelProvider.class);
    Entity entity = mock(Entity.class);

    when(obDal.createCriteria(Column.class)).thenReturn(criteria);
    when(criteria.add(any())).thenReturn(criteria);
    when(criteria.list()).thenReturn(Collections.singletonList(column));
    when(column.getDBColumnName()).thenReturn("M_Warehouse_ID");
    when(column.getCallout()).thenReturn(null);
    when(modelProvider.getEntityByTableId("M_TEST")).thenReturn(entity);
    // Entity returns null property — exercises the fallback to toCleanFieldName.
    when(entity.getPropertyByColumnName("M_Warehouse_ID")).thenReturn(null);

    try (MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class);
        MockedStatic<ModelProvider> modelProviderMock = mockStatic(ModelProvider.class)) {
      obDalMock.when(OBDal::getInstance).thenReturn(obDal);
      modelProviderMock.when(ModelProvider::getInstance).thenReturn(modelProvider);

      NeoCalloutService.TableNameMappings mappings =
          NeoCalloutService.getNameMappings("M_TEST");

      // Falls back to toCleanFieldName — same heuristic as the old code.
      assertEquals("warehouse", mappings.inpToProperty.get("inpmWarehouseId"));
      assertEquals("M_Warehouse_ID", mappings.propertyToColumn.get("warehouse"));
    }
  }

  @Test
  public void testClearMetadataCacheInvalidatesNameMappings() {
    NeoCalloutService.clearMetadataCache();
    Tab tab = mock(Tab.class);
    Table table = mock(Table.class);
    when(tab.getTable()).thenReturn(table);
    when(table.getId()).thenReturn("C_ORDER");

    MappingMocks m = setupSingleColumnMocks("C_ORDER", "C_BPartner_ID", "businessPartner");

    try (MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class);
        MockedStatic<ModelProvider> modelProviderMock = mockStatic(ModelProvider.class)) {
      obDalMock.when(OBDal::getInstance).thenReturn(m.obDal);
      modelProviderMock.when(ModelProvider::getInstance).thenReturn(m.modelProvider);

      NeoCalloutService.getNameMappings("C_ORDER");
      NeoCalloutService.clearMetadataCache();
      NeoCalloutService.getNameMappings("C_ORDER");

      // Both the column metadata cache and the name mappings cache have been refilled.
      verify(m.obDal, times(2)).createCriteria(Column.class);
      verify(m.entity, times(2)).getPropertyByColumnName("C_BPartner_ID");
    }
  }

  // ── Response transformation tests ──────────────────────────────────

  @Test
  public void testTransformResponseEmpty() throws Exception {
    JSONObject result = NeoCalloutService.transformResponse(null, null);

    assertNotNull(result);
    assertTrue(result.has("updates"));
    assertTrue(result.has("combos"));
    assertTrue(result.has("messages"));
    assertEquals(0, result.getJSONObject("updates").length());
    assertEquals(0, result.getJSONObject("combos").length());
    assertEquals(0, result.getJSONArray("messages").length());
  }

  @Test
  public void testTransformResponseSimpleFieldUpdate() throws Exception {
    JSONObject calloutResult = new JSONObject();
    JSONObject fieldValue = new JSONObject();
    fieldValue.put("value", "PL-001");
    fieldValue.put("classicValue", "PL-001");
    calloutResult.put("inppricelist", fieldValue);

    JSONObject result = NeoCalloutService.transformResponse(calloutResult, null);

    JSONObject updates = result.getJSONObject("updates");
    assertTrue(updates.has("pricelist"));
    assertEquals("PL-001", updates.getJSONObject("pricelist").getString("value"));
  }

  @Test
  public void testTransformResponseComboUpdate() throws Exception {
    JSONObject calloutResult = new JSONObject();
    JSONObject comboValue = new JSONObject();
    comboValue.put("value", "WH-001");
    comboValue.put("classicValue", "WH-001");

    JSONArray entries = new JSONArray();
    JSONObject entry1 = new JSONObject();
    entry1.put("id", "WH-001");
    entry1.put("_identifier", "Main Warehouse");
    entries.put(entry1);
    JSONObject entry2 = new JSONObject();
    entry2.put("id", "WH-002");
    entry2.put("_identifier", "Secondary Warehouse");
    entries.put(entry2);
    comboValue.put("entries", entries);

    calloutResult.put("inpmWarehouseId", comboValue);

    JSONObject result = NeoCalloutService.transformResponse(calloutResult, null);

    JSONObject combos = result.getJSONObject("combos");
    // inpmWarehouseId -> stripped "inp" = mWarehouseId (fallback since no tab)
    assertTrue(combos.length() > 0);

    // Check the combo has entries
    String comboKey = combos.keys().next().toString();
    JSONObject combo = combos.getJSONObject(comboKey);
    assertEquals("WH-001", combo.getString("selected"));
    JSONArray comboEntries = combo.getJSONArray("entries");
    assertEquals(2, comboEntries.length());
    assertEquals("WH-001", comboEntries.getJSONObject(0).getString("id"));
    assertEquals("Main Warehouse", comboEntries.getJSONObject(0).getString("identifier"));
  }

  @Test
  public void testTransformResponseMessage() throws Exception {
    JSONObject calloutResult = new JSONObject();
    JSONObject msgValue = new JSONObject();
    msgValue.put("value", "Credit limit exceeded");
    msgValue.put("classicValue", "Credit limit exceeded");
    calloutResult.put("WARNING", msgValue);

    JSONObject result = NeoCalloutService.transformResponse(calloutResult, null);

    JSONArray messages = result.getJSONArray("messages");
    assertEquals(1, messages.length());
    assertEquals("WARNING", messages.getJSONObject(0).getString("type"));
    assertEquals("Credit limit exceeded", messages.getJSONObject(0).getString("text"));
  }

  @Test
  public void testTransformResponseSkipsJsExecute() throws Exception {
    JSONObject calloutResult = new JSONObject();
    JSONObject jsValue = new JSONObject();
    jsValue.put("value", "alert('test')");
    jsValue.put("classicValue", "alert('test')");
    calloutResult.put("JSEXECUTE", jsValue);

    JSONObject result = NeoCalloutService.transformResponse(calloutResult, null);

    // JSEXECUTE should not appear in updates or combos
    assertEquals(0, result.getJSONObject("updates").length());
    assertEquals(0, result.getJSONObject("combos").length());
    assertEquals(0, result.getJSONArray("messages").length());
  }

  @Test
  public void testTransformResponseMixedContent() throws Exception {
    JSONObject calloutResult = new JSONObject();

    // Simple field update
    JSONObject priceValue = new JSONObject();
    priceValue.put("value", "100.00");
    priceValue.put("classicValue", "100.00");
    calloutResult.put("inpgrandtotal", priceValue);

    // Message
    JSONObject msgValue = new JSONObject();
    msgValue.put("value", "Total recalculated");
    msgValue.put("classicValue", "Total recalculated");
    calloutResult.put("INFO", msgValue);

    // JS Execute (should be filtered)
    JSONObject jsValue = new JSONObject();
    jsValue.put("value", "reloadGrid()");
    jsValue.put("classicValue", "reloadGrid()");
    calloutResult.put("JSEXECUTE", jsValue);

    JSONObject result = NeoCalloutService.transformResponse(calloutResult, null);

    assertEquals(1, result.getJSONObject("updates").length());
    assertEquals(0, result.getJSONObject("combos").length());
    assertEquals(1, result.getJSONArray("messages").length());
  }

  @Test
  public void testTransformResponseErrorMessage() throws Exception {
    JSONObject calloutResult = new JSONObject();
    JSONObject errorValue = new JSONObject();
    errorValue.put("value", "Invalid business partner");
    errorValue.put("classicValue", "Invalid business partner");
    calloutResult.put("ERROR", errorValue);

    JSONObject result = NeoCalloutService.transformResponse(calloutResult, null);

    JSONArray messages = result.getJSONArray("messages");
    assertEquals(1, messages.length());
    assertEquals("ERROR", messages.getJSONObject(0).getString("type"));
    assertEquals("Invalid business partner", messages.getJSONObject(0).getString("text"));
  }

  // ── SyntheticHttpServletRequest tests ──────────────────────────────

  @Test
  public void testSyntheticRequestGetParameter() {
    java.util.Map<String, String[]> params = new java.util.HashMap<>();
    params.put("inpTabId", new String[]{ "TAB123" });
    params.put("inpcBpartnerId", new String[]{ "BP-001" });

    SyntheticHttpServletRequest request = new SyntheticHttpServletRequest(params, null);

    assertEquals("TAB123", request.getParameter("inpTabId"));
    assertEquals("BP-001", request.getParameter("inpcBpartnerId"));
    assertNull(request.getParameter("nonexistent"));
  }

  @Test
  public void testSyntheticRequestParameterMap() {
    java.util.Map<String, String[]> params = new java.util.HashMap<>();
    params.put("key1", new String[]{ "val1" });
    params.put("key2", new String[]{ "val2" });

    SyntheticHttpServletRequest request = new SyntheticHttpServletRequest(params, null);

    assertEquals(2, request.getParameterMap().size());
  }

  @Test
  public void testSyntheticRequestSession() {
    java.util.Map<String, Object> sessionAttrs = new java.util.HashMap<>();
    sessionAttrs.put("#AD_User_ID", "USER-001");
    sessionAttrs.put("#AD_Role_ID", "ROLE-001");

    SyntheticHttpServletRequest request = new SyntheticHttpServletRequest(null, sessionAttrs);

    assertNotNull(request.getSession());
    // Session attributes are stored with uppercase keys
    assertEquals("USER-001", request.getSession().getAttribute("#AD_USER_ID"));
    assertEquals("ROLE-001", request.getSession().getAttribute("#AD_ROLE_ID"));
  }

  @Test
  public void testSyntheticRequestSessionAttributeUppercase() {
    java.util.Map<String, Object> sessionAttrs = new java.util.HashMap<>();
    sessionAttrs.put("#AD_Client_ID", "CLIENT-001");

    SyntheticHttpServletRequest request = new SyntheticHttpServletRequest(null, sessionAttrs);

    // VariablesBase looks up with toUpperCase, so the session must handle it
    assertEquals("CLIENT-001", request.getSession().getAttribute("#AD_CLIENT_ID"));
  }

  @Test
  public void testSyntheticRequestNullParams() {
    SyntheticHttpServletRequest request = new SyntheticHttpServletRequest(null, null);

    assertNull(request.getParameter("anything"));
    assertNotNull(request.getSession());
    assertEquals(0, request.getParameterMap().size());
  }

  @Test
  public void testSyntheticRequestMethod() {
    SyntheticHttpServletRequest request = new SyntheticHttpServletRequest(null, null);
    assertEquals("POST", request.getMethod());
  }

  @Test
  public void testSyntheticRequestAttributes() {
    SyntheticHttpServletRequest request = new SyntheticHttpServletRequest(null, null);

    request.setAttribute("test", "value");
    assertEquals("value", request.getAttribute("test"));

    request.removeAttribute("test");
    assertNull(request.getAttribute("test"));
  }

  // ── IsSOTrx per-tab regression ─────────────────────────────────────

  /**
   * Build a mocked OBContext exposing the minimum chain required by
   * {@link NeoCalloutService#buildVars(OBContext, Tab)}.
   */
  private static OBContext mockOBContext(String userId, String roleId, String clientId,
      String orgId, String warehouseId, String lang) {
    OBContext ctx = mock(OBContext.class);
    User user = mock(User.class);
    Role role = mock(Role.class);
    Client client = mock(Client.class);
    Organization org = mock(Organization.class);
    Warehouse warehouse = mock(Warehouse.class);
    Language language = mock(Language.class);
    when(user.getId()).thenReturn(userId);
    when(role.getId()).thenReturn(roleId);
    when(client.getId()).thenReturn(clientId);
    when(org.getId()).thenReturn(orgId);
    when(warehouse.getId()).thenReturn(warehouseId);
    when(language.getLanguage()).thenReturn(lang);
    when(ctx.getUser()).thenReturn(user);
    when(ctx.getRole()).thenReturn(role);
    when(ctx.getCurrentClient()).thenReturn(client);
    when(ctx.getCurrentOrganization()).thenReturn(org);
    when(ctx.getWarehouse()).thenReturn(warehouse);
    when(ctx.getLanguage()).thenReturn(language);
    return ctx;
  }

  private static Tab mockTabWithSalesFlag(boolean isSales) {
    Tab tab = mock(Tab.class);
    Window window = mock(Window.class);
    when(tab.getWindow()).thenReturn(window);
    when(window.isSalesTransaction()).thenReturn(isSales);
    return tab;
  }

  /**
   * The cached snapshot is identity-only. The two windows below share user, role,
   * client, org, warehouse and language — so they hit the same cache entry — but
   * one is a sales window and the other a purchase window. The test guards that:
   *
   * <ol>
   *   <li>Each VSA receives its own {@code IsSOTrx}/{@code isSOTrx} value.</li>
   *   <li>The two VSAs are distinct instances (no aliasing of mutable state).</li>
   *   <li>The slow path ({@code fillSessionArguments}) runs only once because the
   *       snapshot is reused across tabs.</li>
   * </ol>
   */
  @Test
  public void testBuildVarsAppliesIsSOTrxPerTabOnSharedCacheSnapshot() {
    NeoSessionVarsCache.clear();
    try (MockedStatic<LoginUtils> loginMock = mockStatic(LoginUtils.class)) {
      loginMock.when(() -> LoginUtils.fillSessionArguments(
              any(ConnectionProvider.class), any(VariablesSecureApp.class),
              anyString(), anyString(), anyString(),
              anyString(), anyString(), anyString(), anyString()))
          .thenAnswer(inv -> {
            VariablesSecureApp vars = inv.getArgument(1);
            vars.setSessionValue("#AD_User_ID", inv.getArgument(2));
            vars.setSessionValue("#AD_Role_ID", inv.getArgument(5));
            vars.setSessionValue("#AD_Client_ID", inv.getArgument(6));
            vars.setSessionValue("#AD_Org_ID", inv.getArgument(7));
            return Boolean.TRUE;
          });
      loginMock.when(() -> LoginUtils.readNumberFormat(
              any(VariablesSecureApp.class), anyString()))
          .thenAnswer(inv -> null);

      OBContext ctx = mockOBContext("U1", "R1", "C1", "O1", "W1", "es_ES");
      Tab salesTab = mockTabWithSalesFlag(true);
      Tab purchaseTab = mockTabWithSalesFlag(false);

      VariablesSecureApp salesVars = NeoCalloutService.buildVars(ctx, salesTab);
      VariablesSecureApp purchaseVars = NeoCalloutService.buildVars(ctx, purchaseTab);

      assertNotSame("VSAs must not alias — IsSOTrx would leak otherwise",
          salesVars, purchaseVars);
      assertEquals("Y", salesVars.getSessionValue("IsSOTrx"));
      assertEquals("Y", salesVars.getSessionValue("isSOTrx"));
      assertEquals("N", purchaseVars.getSessionValue("IsSOTrx"));
      assertEquals("N", purchaseVars.getSessionValue("isSOTrx"));
      // Identity payload still came from the same cached snapshot.
      assertEquals("U1", salesVars.getSessionValue("#AD_User_ID"));
      assertEquals("U1", purchaseVars.getSessionValue("#AD_User_ID"));
      // Slow path runs only once even though two tabs were materialized.
      loginMock.verify(() -> LoginUtils.fillSessionArguments(
              any(), any(), anyString(), anyString(), anyString(),
              anyString(), anyString(), anyString(), anyString()),
          times(1));
    } finally {
      NeoSessionVarsCache.clear();
    }
  }
}
