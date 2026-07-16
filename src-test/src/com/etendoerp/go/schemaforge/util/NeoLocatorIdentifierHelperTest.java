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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.util.Collections;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.ModelProvider;
import org.openbravo.base.model.Property;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.datamodel.Column;
import org.openbravo.model.ad.datamodel.Table;
import org.openbravo.model.ad.ui.Tab;
import org.openbravo.model.common.enterprise.Locator;
import org.openbravo.model.common.enterprise.Warehouse;

import com.etendoerp.go.schemaforge.NeoSelectorService;
import com.etendoerp.go.schemaforge.data.SFEntity;
import com.etendoerp.go.schemaforge.data.SFField;
import com.etendoerp.go.schemaforge.selector.meta.SelectorMeta;

/**
 * Unit tests for {@link NeoLocatorIdentifierHelper#enrichLocatorIdentifiers}.
 *
 * <p>Uses {@link MockedStatic} for {@link OBDal}, {@link ModelProvider}, {@link OBContext}
 * and {@link NeoSelectorService} so no live database is required.</p>
 */
public class NeoLocatorIdentifierHelperTest {

  private static final String TABLE_NAME = "m_internal_consumptionline";
  private static final String LOCATOR_COLUMN = "M_Locator_ID";
  private static final String LOCATOR_PROP = "storageBin";

  private MockedStatic<OBDal> obDalMock;
  private MockedStatic<ModelProvider> modelProviderMock;
  private MockedStatic<OBContext> obContextMock;
  private MockedStatic<NeoSelectorService> selectorServiceMock;
  private OBDal dal;
  private ModelProvider modelProvider;

  @Before
  public void setUp() {
    dal = mock(OBDal.class);
    modelProvider = mock(ModelProvider.class);

    obDalMock = mockStatic(OBDal.class);
    modelProviderMock = mockStatic(ModelProvider.class);
    obContextMock = mockStatic(OBContext.class);
    selectorServiceMock = mockStatic(NeoSelectorService.class);

    obDalMock.when(OBDal::getInstance).thenReturn(dal);
    modelProviderMock.when(ModelProvider::getInstance).thenReturn(modelProvider);
  }

  @After
  public void tearDown() {
    selectorServiceMock.close();
    obContextMock.close();
    modelProviderMock.close();
    obDalMock.close();
  }

  // ── locator field is rewritten with the warehouse name ────────────────────

  @Test
  public void enrichLocatorIdentifiers_locatorField_setsWarehouseName() throws Exception {
    SFEntity sfEntity = buildEntityWithLocatorField();
    stubLocatorReference("Locator", "M_Locator");
    Warehouse warehouse = mock(Warehouse.class);
    when(warehouse.getName()).thenReturn("Almacen GO");
    stubLocatorQuery("loc-1", warehouse);

    JSONObject record = new JSONObject();
    record.put(LOCATOR_PROP, "loc-1");
    record.put(LOCATOR_PROP + "$_identifier", "AG-0-0-0");
    JSONObject response = buildResponseJson(record);

    NeoLocatorIdentifierHelper.enrichLocatorIdentifiers(response, sfEntity);

    JSONObject enriched = response.getJSONObject("response").getJSONArray("data").getJSONObject(0);
    assertEquals("Almacen GO", enriched.optString(LOCATOR_PROP + "$_identifier"));
  }

  // ── warehouse Name blank → falls back to identifier ───────────────────────

  @Test
  public void enrichLocatorIdentifiers_blankWarehouseName_fallsBackToIdentifier() throws Exception {
    SFEntity sfEntity = buildEntityWithLocatorField();
    stubLocatorReference("Locator", "M_Locator");
    Warehouse warehouse = mock(Warehouse.class);
    when(warehouse.getName()).thenReturn("");
    when(warehouse.getIdentifier()).thenReturn("Almacen GO");
    stubLocatorQuery("loc-1", warehouse);

    JSONObject record = new JSONObject();
    record.put(LOCATOR_PROP, "loc-1");
    JSONObject response = buildResponseJson(record);

    NeoLocatorIdentifierHelper.enrichLocatorIdentifiers(response, sfEntity);

    JSONObject enriched = response.getJSONObject("response").getJSONArray("data").getJSONObject(0);
    assertEquals("Almacen GO", enriched.optString(LOCATOR_PROP + "$_identifier"));
  }

  // ── non-locator field is left untouched ───────────────────────────────────

  @Test
  public void enrichLocatorIdentifiers_nonLocatorField_leavesUntouched() throws Exception {
    SFEntity sfEntity = buildEntityWithLocatorField();
    // Resolves to a non-locator entity → isLocatorRef returns false
    stubLocatorReference("BusinessPartner", "C_BPartner");

    JSONObject record = new JSONObject();
    record.put(LOCATOR_PROP, "bp-1");
    record.put(LOCATOR_PROP + "$_identifier", "Original BP");
    JSONObject response = buildResponseJson(record);

    NeoLocatorIdentifierHelper.enrichLocatorIdentifiers(response, sfEntity);

    JSONObject result = response.getJSONObject("response").getJSONArray("data").getJSONObject(0);
    assertEquals("Original BP", result.optString(LOCATOR_PROP + "$_identifier"));
  }

  // ── blank raw id → nothing resolved, identifier untouched ─────────────────

  @Test
  public void enrichLocatorIdentifiers_blankRawId_leavesUntouched() throws Exception {
    SFEntity sfEntity = buildEntityWithLocatorField();
    stubLocatorReference("Locator", "M_Locator");

    JSONObject record = new JSONObject();
    record.put(LOCATOR_PROP, "");
    record.put(LOCATOR_PROP + "$_identifier", "AG-0-0-0");
    JSONObject response = buildResponseJson(record);

    NeoLocatorIdentifierHelper.enrichLocatorIdentifiers(response, sfEntity);

    JSONObject result = response.getJSONObject("response").getJSONArray("data").getJSONObject(0);
    assertEquals("AG-0-0-0", result.optString(LOCATOR_PROP + "$_identifier"));
  }

  // ── locator without warehouse → identifier untouched ──────────────────────

  @Test
  public void enrichLocatorIdentifiers_locatorWithoutWarehouse_leavesUntouched() throws Exception {
    SFEntity sfEntity = buildEntityWithLocatorField();
    stubLocatorReference("Locator", "M_Locator");
    stubLocatorQuery("loc-1", null); // no warehouse

    JSONObject record = new JSONObject();
    record.put(LOCATOR_PROP, "loc-1");
    record.put(LOCATOR_PROP + "$_identifier", "AG-0-0-0");
    JSONObject response = buildResponseJson(record);

    NeoLocatorIdentifierHelper.enrichLocatorIdentifiers(response, sfEntity);

    JSONObject result = response.getJSONObject("response").getJSONArray("data").getJSONObject(0);
    assertEquals("AG-0-0-0", result.optString(LOCATOR_PROP + "$_identifier"));
  }

  // ── null sfEntity guard ───────────────────────────────────────────────────

  @Test
  public void enrichLocatorIdentifiers_nullSfEntity_doesNothing() throws Exception {
    JSONObject response = buildResponseJson(new JSONObject().put(LOCATOR_PROP, "loc-1"));
    NeoLocatorIdentifierHelper.enrichLocatorIdentifiers(response, null);
    assertNull(response.getJSONObject("response").getJSONArray("data").getJSONObject(0)
        .opt(LOCATOR_PROP + "$_identifier"));
  }

  // ── helpers ───────────────────────────────────────────────────────────────

  @SuppressWarnings("unchecked")
  private SFEntity buildEntityWithLocatorField() {
    SFEntity sfEntity = mock(SFEntity.class);
    when(sfEntity.getId()).thenReturn("entity-1");

    Tab tab = mock(Tab.class);
    Table table = mock(Table.class);
    when(table.getDBTableName()).thenReturn(TABLE_NAME);
    when(tab.getTable()).thenReturn(table);
    when(sfEntity.getADTab()).thenReturn(tab);

    Entity dalEntity = mock(Entity.class);
    when(modelProvider.getEntityByTableName(TABLE_NAME)).thenReturn(dalEntity);
    Property prop = mock(Property.class);
    when(prop.getName()).thenReturn(LOCATOR_PROP);
    when(dalEntity.getPropertyByColumnName(LOCATOR_COLUMN)).thenReturn(prop);

    Column col = mock(Column.class);
    when(col.getDBColumnName()).thenReturn(LOCATOR_COLUMN);

    SFField sfField = mock(SFField.class);
    when(sfField.isIncluded()).thenReturn(Boolean.TRUE);
    when(sfField.getADColumn()).thenReturn(col);

    OBCriteria<SFField> criteria = mock(OBCriteria.class);
    when(dal.createCriteria(SFField.class)).thenReturn(criteria);
    when(criteria.add(any())).thenReturn(criteria);
    when(criteria.setFilterOnReadableClients(anyBoolean())).thenReturn(criteria);
    when(criteria.setFilterOnReadableOrganization(anyBoolean())).thenReturn(criteria);
    when(criteria.list()).thenReturn(Collections.singletonList(sfField));

    // Stub reference resolution for that same column
    selectorServiceMock.when(() -> NeoSelectorService.getBaseReferenceId(col)).thenReturn("19");
    return sfEntity;
  }

  private void stubLocatorReference(String entityName, String tableName) {
    SelectorMeta meta = new SelectorMeta(entityName, "name", null);
    selectorServiceMock.when(() -> NeoSelectorService.resolveTarget(any(Column.class), eq("19")))
        .thenReturn(meta);
    Entity targetEntity = mock(Entity.class);
    when(targetEntity.getTableName()).thenReturn(tableName);
    when(modelProvider.getEntity(entityName, false)).thenReturn(targetEntity);
  }

  @SuppressWarnings("unchecked")
  private void stubLocatorQuery(String locatorId, Warehouse warehouse) {
    Locator locator = mock(Locator.class);
    when(locator.getId()).thenReturn(locatorId);
    when(locator.getWarehouse()).thenReturn(warehouse);

    OBCriteria<Locator> locCrit = mock(OBCriteria.class);
    when(dal.createCriteria(Locator.class)).thenReturn(locCrit);
    when(locCrit.add(any())).thenReturn(locCrit);
    when(locCrit.list()).thenReturn(Collections.singletonList(locator));
  }

  private JSONObject buildResponseJson(JSONObject record) throws Exception {
    JSONArray dataArray = new JSONArray();
    dataArray.put(record);
    JSONObject inner = new JSONObject();
    inner.put("data", dataArray);
    JSONObject response = new JSONObject();
    response.put("response", inner);
    return response;
  }
}
