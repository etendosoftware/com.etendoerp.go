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
package com.etendoerp.go.schemaforge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Collections;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.enterprise.Locator;
import org.openbravo.model.common.enterprise.Warehouse;

/**
 * Unit tests for {@link InternalConsumptionLineHandler}.
 *
 * <p>Covers the {@code handle()} no-op contract, all guard-clause branches
 * in {@code afterHandle()}, the successful warehouse-name enrichment path,
 * the locator-without-warehouse edge case, and the exception fallback.
 */
public class InternalConsumptionLineHandlerTest {

  private static final InternalConsumptionLineHandler HANDLER = new InternalConsumptionLineHandler();

  // ── handle() ─────────────────────────────────────────────────────────────

  /**
   * handle() must always return null regardless of context, as the handler
   * only operates in the afterHandle phase.
   */
  @Test
  public void testHandleAlwaysReturnsNull() {
    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.SELECTOR)
        .fieldName("M_Locator_ID")
        .build();
    assertNull(HANDLER.handle(ctx));
  }

  /**
   * handle() must return null even for non-SELECTOR endpoints.
   */
  @Test
  public void testHandleReturnsNullForCrudEndpoint() {
    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.CRUD)
        .httpMethod("GET")
        .build();
    assertNull(HANDLER.handle(ctx));
  }

  // ── afterHandle() guard clauses ──────────────────────────────────────────

  /**
   * afterHandle() must return null when the endpoint type is not SELECTOR.
   */
  @Test
  public void testAfterHandleReturnsNullForNonSelectorEndpoint() {
    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.CRUD)
        .fieldName("M_Locator_ID")
        .build();
    assertNull(HANDLER.afterHandle(ctx));
  }

  /**
   * afterHandle() must return null when the endpoint type is ACTION.
   */
  @Test
  public void testAfterHandleReturnsNullForActionEndpoint() {
    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.ACTION)
        .fieldName("M_Locator_ID")
        .build();
    assertNull(HANDLER.afterHandle(ctx));
  }

  /**
   * afterHandle() must return null when the field name does not match M_Locator_ID.
   */
  @Test
  public void testAfterHandleReturnsNullForWrongFieldName() {
    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.SELECTOR)
        .fieldName("C_BPartner_ID")
        .build();
    assertNull(HANDLER.afterHandle(ctx));
  }

  /**
   * afterHandle() must return null when the field name is null.
   */
  @Test
  public void testAfterHandleReturnsNullForNullFieldName() {
    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.SELECTOR)
        .build();
    assertNull(HANDLER.afterHandle(ctx));
  }

  /**
   * afterHandle() must return null when previousResult is null.
   */
  @Test
  public void testAfterHandleReturnsNullWhenPreviousResultIsNull() {
    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.SELECTOR)
        .fieldName("M_Locator_ID")
        .build();
    assertNull(HANDLER.afterHandle(ctx));
  }

  /**
   * afterHandle() must return null when previousResult has a null body.
   */
  @Test
  public void testAfterHandleReturnsNullWhenPreviousResultBodyIsNull() {
    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.SELECTOR)
        .fieldName("M_Locator_ID")
        .previousResult(new NeoResponse(200, null))
        .build();
    assertNull(HANDLER.afterHandle(ctx));
  }

  /**
   * afterHandle() must return null when the body has no items array.
   */
  @Test
  public void testAfterHandleReturnsNullWhenItemsArrayIsMissing() throws Exception {
    JSONObject body = new JSONObject().put("total", 0);
    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.SELECTOR)
        .fieldName("M_Locator_ID")
        .previousResult(NeoResponse.ok(body))
        .build();
    assertNull(HANDLER.afterHandle(ctx));
  }

  /**
   * afterHandle() must return null when the items array is empty.
   */
  @Test
  public void testAfterHandleReturnsNullWhenItemsArrayIsEmpty() throws Exception {
    JSONObject body = new JSONObject().put("items", new JSONArray());
    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.SELECTOR)
        .fieldName("M_Locator_ID")
        .previousResult(NeoResponse.ok(body))
        .build();
    assertNull(HANDLER.afterHandle(ctx));
  }

  // ── afterHandle() success path ───────────────────────────────────────────

  /**
   * afterHandle() must replace item labels with the corresponding warehouse
   * names when locators are found with warehouses.
   */
  @SuppressWarnings("unchecked")
  @Test
  public void testAfterHandleEnrichesItemsWithWarehouseNames() throws Exception {
    JSONArray items = new JSONArray();
    items.put(new JSONObject().put("id", "loc-1").put("label", "Bin-A"));
    items.put(new JSONObject().put("id", "loc-2").put("label", "Bin-B"));
    JSONObject body = new JSONObject().put("items", items);

    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.SELECTOR)
        .fieldName("M_Locator_ID")
        .previousResult(NeoResponse.ok(body))
        .build();

    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
         MockedStatic<OBContext> obCtxMock = Mockito.mockStatic(OBContext.class)) {

      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);

      Locator locator1 = mock(Locator.class);
      Warehouse warehouse1 = mock(Warehouse.class);
      doReturn("loc-1").when(locator1).getId();
      when(locator1.getWarehouse()).thenReturn(warehouse1);
      when(warehouse1.getIdentifier()).thenReturn("Main Warehouse");

      Locator locator2 = mock(Locator.class);
      Warehouse warehouse2 = mock(Warehouse.class);
      doReturn("loc-2").when(locator2).getId();
      when(locator2.getWarehouse()).thenReturn(warehouse2);
      when(warehouse2.getIdentifier()).thenReturn("Secondary Warehouse");

      OBCriteria<Locator> criteria = mock(OBCriteria.class);
      when(dal.createCriteria(Locator.class)).thenReturn(criteria);
      when(criteria.add(any())).thenReturn(criteria);
      when(criteria.list()).thenReturn(Arrays.asList(locator1, locator2));

      NeoResponse result = HANDLER.afterHandle(ctx);

      assertNotNull(result);
      JSONArray resultItems = result.getBody().getJSONArray("items");
      assertEquals("Main Warehouse", resultItems.getJSONObject(0).getString("label"));
      assertEquals("Secondary Warehouse", resultItems.getJSONObject(1).getString("label"));
    }
  }

  /**
   * afterHandle() must leave the label unchanged for locators that have no
   * warehouse associated.
   */
  @SuppressWarnings("unchecked")
  @Test
  public void testAfterHandleLeavesLabelWhenLocatorHasNoWarehouse() throws Exception {
    JSONArray items = new JSONArray();
    items.put(new JSONObject().put("id", "loc-orphan").put("label", "Orphan-Bin"));
    JSONObject body = new JSONObject().put("items", items);

    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.SELECTOR)
        .fieldName("M_Locator_ID")
        .previousResult(NeoResponse.ok(body))
        .build();

    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
         MockedStatic<OBContext> obCtxMock = Mockito.mockStatic(OBContext.class)) {

      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);

      Locator locator = mock(Locator.class);
      doReturn("loc-orphan").when(locator).getId();
      when(locator.getWarehouse()).thenReturn(null);

      OBCriteria<Locator> criteria = mock(OBCriteria.class);
      when(dal.createCriteria(Locator.class)).thenReturn(criteria);
      when(criteria.add(any())).thenReturn(criteria);
      when(criteria.list()).thenReturn(Collections.singletonList(locator));

      NeoResponse result = HANDLER.afterHandle(ctx);

      assertNotNull(result);
      JSONArray resultItems = result.getBody().getJSONArray("items");
      assertEquals("Orphan-Bin", resultItems.getJSONObject(0).getString("label"));
    }
  }

  /**
   * afterHandle() must enrich only the locators that have warehouses, leaving
   * others with their original labels.
   */
  @SuppressWarnings("unchecked")
  @Test
  public void testAfterHandleMixedLocatorsWithAndWithoutWarehouse() throws Exception {
    JSONArray items = new JSONArray();
    items.put(new JSONObject().put("id", "loc-1").put("label", "Bin-A"));
    items.put(new JSONObject().put("id", "loc-2").put("label", "Bin-B"));
    JSONObject body = new JSONObject().put("items", items);

    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.SELECTOR)
        .fieldName("M_Locator_ID")
        .previousResult(NeoResponse.ok(body))
        .build();

    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
         MockedStatic<OBContext> obCtxMock = Mockito.mockStatic(OBContext.class)) {

      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);

      Locator locator1 = mock(Locator.class);
      Warehouse warehouse1 = mock(Warehouse.class);
      doReturn("loc-1").when(locator1).getId();
      when(locator1.getWarehouse()).thenReturn(warehouse1);
      when(warehouse1.getIdentifier()).thenReturn("Warehouse Alpha");

      Locator locator2 = mock(Locator.class);
      doReturn("loc-2").when(locator2).getId();
      when(locator2.getWarehouse()).thenReturn(null);

      OBCriteria<Locator> criteria = mock(OBCriteria.class);
      when(dal.createCriteria(Locator.class)).thenReturn(criteria);
      when(criteria.add(any())).thenReturn(criteria);
      when(criteria.list()).thenReturn(Arrays.asList(locator1, locator2));

      NeoResponse result = HANDLER.afterHandle(ctx);

      assertNotNull(result);
      JSONArray resultItems = result.getBody().getJSONArray("items");
      assertEquals("Warehouse Alpha", resultItems.getJSONObject(0).getString("label"));
      assertEquals("Bin-B", resultItems.getJSONObject(1).getString("label"));
    }
  }

  // ── afterHandle() exception path ─────────────────────────────────────────

  /**
   * afterHandle() must return null when an exception occurs during enrichment,
   * ensuring the error is non-fatal and the original response can still be used.
   */
  @Test
  public void testAfterHandleReturnsNullOnException() throws Exception {
    JSONArray items = new JSONArray();
    items.put(new JSONObject().put("id", "loc-1").put("label", "Bin-A"));
    JSONObject body = new JSONObject().put("items", items);

    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.SELECTOR)
        .fieldName("M_Locator_ID")
        .previousResult(NeoResponse.ok(body))
        .build();

    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
         MockedStatic<OBContext> obCtxMock = Mockito.mockStatic(OBContext.class)) {

      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);

      when(dal.createCriteria(Locator.class)).thenThrow(new RuntimeException("DB connection lost"));

      assertNull(HANDLER.afterHandle(ctx));
    }
  }

  /**
   * afterHandle() must return null when OBContext.setAdminMode throws,
   * confirming the catch block handles exceptions from any point in the flow.
   */
  @Test
  public void testAfterHandleReturnsNullWhenAdminModeThrows() throws Exception {
    JSONArray items = new JSONArray();
    items.put(new JSONObject().put("id", "loc-1").put("label", "Bin-A"));
    JSONObject body = new JSONObject().put("items", items);

    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.SELECTOR)
        .fieldName("M_Locator_ID")
        .previousResult(NeoResponse.ok(body))
        .build();

    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
         MockedStatic<OBContext> obCtxMock = Mockito.mockStatic(OBContext.class)) {

      obCtxMock.when(OBContext::setAdminMode).thenThrow(new RuntimeException("Context failure"));

      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);

      assertNull(HANDLER.afterHandle(ctx));
    }
  }
}
