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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Collections;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.ModelProvider;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.datamodel.Column;
import org.openbravo.model.common.enterprise.Locator;
import org.openbravo.model.common.enterprise.Warehouse;

import com.etendoerp.go.schemaforge.NeoResponse;
import com.etendoerp.go.schemaforge.NeoSelectorService;
import com.etendoerp.go.schemaforge.selector.meta.SelectorMeta;

/**
 * Unit tests for {@link NeoLocatorSelectorHelper#rewriteLocatorLabels}.
 *
 * <p>Uses {@link MockedStatic} for {@link OBDal}, {@link ModelProvider}, {@link OBContext}
 * and {@link NeoSelectorService} so no live database is required.</p>
 */
public class NeoLocatorSelectorHelperTest {

  private MockedStatic<OBDal> obDalMock;
  private MockedStatic<ModelProvider> modelProviderMock;
  private MockedStatic<OBContext> obContextMock;
  private MockedStatic<NeoSelectorService> selectorServiceMock;
  private OBDal dal;
  private ModelProvider modelProvider;
  private Column column;

  @Before
  public void setUp() {
    dal = mock(OBDal.class);
    modelProvider = mock(ModelProvider.class);
    column = mock(Column.class);
    when(column.getDBColumnName()).thenReturn("M_Locator_ID");

    obDalMock = mockStatic(OBDal.class);
    modelProviderMock = mockStatic(ModelProvider.class);
    obContextMock = mockStatic(OBContext.class);
    selectorServiceMock = mockStatic(NeoSelectorService.class);

    obDalMock.when(OBDal::getInstance).thenReturn(dal);
    modelProviderMock.when(ModelProvider::getInstance).thenReturn(modelProvider);
    selectorServiceMock.when(() -> NeoSelectorService.getBaseReferenceId(column)).thenReturn("19");
  }

  @After
  public void tearDown() {
    selectorServiceMock.close();
    obContextMock.close();
    modelProviderMock.close();
    obDalMock.close();
  }

  // ── locator selector labels are rewritten ─────────────────────────────────

  @Test
  public void rewriteLocatorLabels_locatorColumn_rewritesLabels() throws Exception {
    stubReference("Locator", "M_Locator");

    Warehouse wh1 = mock(Warehouse.class);
    when(wh1.getName()).thenReturn("Almacen GO");
    Warehouse wh2 = mock(Warehouse.class);
    when(wh2.getName()).thenReturn("Almacen BA");
    stubLocatorQuery(
        buildLocator("loc-1", wh1),
        buildLocator("loc-2", wh2));

    JSONArray items = new JSONArray();
    items.put(new JSONObject().put("id", "loc-1").put("label", "AG-0-0-0"));
    items.put(new JSONObject().put("id", "loc-2").put("label", "BA-0-0-0"));
    NeoResponse response = NeoResponse.ok(new JSONObject().put("items", items));

    NeoResponse result = NeoLocatorSelectorHelper.rewriteLocatorLabels(response, column);

    JSONArray resultItems = result.getBody().getJSONArray("items");
    assertEquals("Almacen GO", resultItems.getJSONObject(0).getString("label"));
    assertEquals("Almacen BA", resultItems.getJSONObject(1).getString("label"));
  }

  // ── non-locator selector is left untouched ────────────────────────────────

  @Test
  public void rewriteLocatorLabels_nonLocatorColumn_leavesUntouched() throws Exception {
    stubReference("BusinessPartner", "C_BPartner");

    JSONArray items = new JSONArray();
    items.put(new JSONObject().put("id", "bp-1").put("label", "Customer A"));
    NeoResponse response = NeoResponse.ok(new JSONObject().put("items", items));

    NeoResponse result = NeoLocatorSelectorHelper.rewriteLocatorLabels(response, column);

    JSONArray resultItems = result.getBody().getJSONArray("items");
    assertEquals("Customer A", resultItems.getJSONObject(0).getString("label"));
  }

  // ── locator without warehouse keeps its original label ────────────────────

  @Test
  public void rewriteLocatorLabels_locatorWithoutWarehouse_keepsLabel() throws Exception {
    stubReference("Locator", "M_Locator");
    stubLocatorQuery(buildLocator("loc-1", null));

    JSONArray items = new JSONArray();
    items.put(new JSONObject().put("id", "loc-1").put("label", "AG-0-0-0"));
    NeoResponse response = NeoResponse.ok(new JSONObject().put("items", items));

    NeoResponse result = NeoLocatorSelectorHelper.rewriteLocatorLabels(response, column);

    JSONArray resultItems = result.getBody().getJSONArray("items");
    assertEquals("AG-0-0-0", resultItems.getJSONObject(0).getString("label"));
  }

  // ── null response / null column are safe no-ops ───────────────────────────

  @Test
  public void rewriteLocatorLabels_nullResponse_returnsNull() {
    assertEquals(null, NeoLocatorSelectorHelper.rewriteLocatorLabels(null, column));
  }

  @Test
  public void rewriteLocatorLabels_nullColumn_returnsResponseUnchanged() throws Exception {
    JSONArray items = new JSONArray();
    items.put(new JSONObject().put("id", "loc-1").put("label", "AG-0-0-0"));
    NeoResponse response = NeoResponse.ok(new JSONObject().put("items", items));

    NeoResponse result = NeoLocatorSelectorHelper.rewriteLocatorLabels(response, null);

    assertEquals("AG-0-0-0", result.getBody().getJSONArray("items").getJSONObject(0)
        .getString("label"));
  }

  // ── helpers ───────────────────────────────────────────────────────────────

  private void stubReference(String entityName, String tableName) {
    SelectorMeta meta = new SelectorMeta(entityName, "name", null);
    selectorServiceMock.when(() -> NeoSelectorService.resolveTarget(any(Column.class), eq("19")))
        .thenReturn(meta);
    Entity entity = mock(Entity.class);
    when(entity.getTableName()).thenReturn(tableName);
    when(modelProvider.getEntity(entityName, false)).thenReturn(entity);
  }

  private Locator buildLocator(String id, Warehouse warehouse) {
    Locator locator = mock(Locator.class);
    when(locator.getId()).thenReturn(id);
    when(locator.getWarehouse()).thenReturn(warehouse);
    return locator;
  }

  @SuppressWarnings("unchecked")
  private void stubLocatorQuery(Locator... locators) {
    OBCriteria<Locator> locCrit = mock(OBCriteria.class);
    when(dal.createCriteria(Locator.class)).thenReturn(locCrit);
    when(locCrit.add(any())).thenReturn(locCrit);
    when(locCrit.list()).thenReturn(locators.length == 1
        ? Collections.singletonList(locators[0])
        : Arrays.asList(locators));
  }
}
