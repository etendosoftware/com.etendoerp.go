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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;

import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.service.OBCriteria;
import org.hibernate.Session;
import org.hibernate.query.NativeQuery;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.enterprise.Locator;
import org.openbravo.model.common.enterprise.Warehouse;
import org.openbravo.model.materialmgmt.transaction.InventoryCount;
import org.openbravo.model.materialmgmt.transaction.InventoryCountLine;
import org.openbravo.model.common.plm.Product;

/**
 * Unit tests for {@link InventoryLineHandler}.
 *
 * <p>Covers guard clauses for {@code handle()}, {@code afterCallout()}, and
 * the pure-logic branches of {@code queryProductStock()}.
 * DB-dependent flows (handlePostPreHook full path, handlePatchPreHook) require
 * integration coverage with a real OBDal context.
 */
public class InventoryLineHandlerTest {

  private static final InventoryLineHandler HANDLER = new InventoryLineHandler();

  // ── handle() guard clauses ────────────────────────────────────────────────

  /**
   * handle() must short-circuit on non-CRUD endpoints without touching the body.
   */
  @Test
  public void testHandleReturnsNullForNonCrudEndpoint() {
    NeoContext ctx = NeoContext.builder().httpMethod("POST").endpointType(NeoEndpointType.ACTION).build();
    assertNull(HANDLER.handle(ctx));
  }

  /**
   * handle() must be a no-op for read-only GET requests.
   */
  @Test
  public void testHandleReturnsNullForGetMethod() {
    NeoContext ctx = NeoContext.builder().httpMethod("GET").endpointType(NeoEndpointType.CRUD).build();
    assertNull(HANDLER.handle(ctx));
  }

  /**
   * handle() must be a no-op for DELETE requests.
   */
  @Test
  public void testHandleReturnsNullForDeleteMethod() {
    NeoContext ctx = NeoContext.builder().httpMethod("DELETE").endpointType(NeoEndpointType.CRUD).build();
    assertNull(HANDLER.handle(ctx));
  }

  /**
   * handle() must return null when no request body is present, avoiding NPE.
   */
  @Test
  public void testHandleReturnsNullWhenBodyIsNull() {
    NeoContext ctx = NeoContext.builder().httpMethod("POST").endpointType(NeoEndpointType.CRUD).build();
    assertNull(HANDLER.handle(ctx));
  }

  // ── afterHandle() ─────────────────────────────────────────────────────────

  /**
   * afterHandle() is not overridden and must always return null.
   */
  @Test
  public void testAfterHandleAlwaysReturnsNull() {
    NeoContext ctx = NeoContext.builder().httpMethod("GET").endpointType(NeoEndpointType.CRUD).build();
    assertNull(HANDLER.afterHandle(ctx));
  }

  // ── afterCallout() guard clauses ──────────────────────────────────────────

  /**
   * afterCallout() must be a no-op when the endpoint is not CALLOUT.
   */
  @Test
  public void testAfterCalloutReturnsNullForNonCalloutEndpoint() {
    NeoContext ctx = NeoContext.builder().httpMethod("POST").endpointType(NeoEndpointType.CRUD).build();
    assertNull(HANDLER.afterCallout(ctx));
  }

  /**
   * afterCallout() must return null without throwing when the request body is absent.
   */
  @Test
  public void testAfterCalloutReturnsNullWhenBodyIsNull() {
    NeoContext ctx = NeoContext.builder().endpointType(NeoEndpointType.CALLOUT).build();
    assertNull(HANDLER.afterCallout(ctx));
  }

  /**
   * afterCallout() must return null when no previous callout result exists.
   */
  @Test
  public void testAfterCalloutReturnsNullWhenPreviousResultIsNull() throws Exception {
    NeoContext ctx = NeoContext.builder().endpointType(NeoEndpointType.CALLOUT).requestBody(
        new JSONObject().put("value", "prod-1")).build();
    assertNull(HANDLER.afterCallout(ctx));
  }

  /**
   * afterCallout() must skip override when the updates object has no quantity fields.
   */
  @Test
  public void testAfterCalloutReturnsNullWhenUpdatesLacksQuantityFields() throws Exception {
    JSONObject prevBody = new JSONObject().put("updates", new JSONObject().put("someOtherField", "x"));
    NeoContext ctx = NeoContext.builder().endpointType(NeoEndpointType.CALLOUT).requestBody(
        new JSONObject().put("value", "prod-1")).previousResult(NeoResponse.ok(prevBody)).build();
    assertNull(HANDLER.afterCallout(ctx));
  }

  /**
   * afterCallout() must return null when the product ID value is blank.
   */
  @Test
  public void testAfterCalloutReturnsNullWhenProductIdBlank() throws Exception {
    JSONObject updates = new JSONObject().put("bookQuantity", new JSONObject().put("value", 100.0)).put("quantityCount",
        new JSONObject().put("value", 100.0));
    JSONObject prevBody = new JSONObject().put("updates", updates);
    NeoContext ctx = NeoContext.builder().endpointType(NeoEndpointType.CALLOUT).requestBody(
        new JSONObject().put("value", "")).previousResult(NeoResponse.ok(prevBody)).build();
    assertNull(HANDLER.afterCallout(ctx));
  }

  /**
   * afterCallout() must return null when the body carries no formState object.
   */
  @Test
  public void testAfterCalloutReturnsNullWhenFormStateAbsent() throws Exception {
    JSONObject updates = new JSONObject().put("bookQuantity", new JSONObject().put("value", 100.0));
    JSONObject prevBody = new JSONObject().put("updates", updates);
    NeoContext ctx = NeoContext.builder().endpointType(NeoEndpointType.CALLOUT).requestBody(
        new JSONObject().put("value", "prod-1")).previousResult(NeoResponse.ok(prevBody)).build();
    assertNull(HANDLER.afterCallout(ctx));
  }

  /**
   * afterCallout() must silently return null when the inventory record does not exist.
   */
  @Test
  public void testAfterCalloutReturnsNullWhenInventoryNotFound() throws Exception {
    JSONObject updates = new JSONObject().put("bookQuantity", new JSONObject().put("value", 100.0)).put("quantityCount",
        new JSONObject().put("value", 100.0));
    JSONObject prevBody = new JSONObject().put("updates", updates);
    JSONObject body = new JSONObject().put("value", "prod-1").put("formState",
        new JSONObject().put("physInventory", "inv-missing"));

    NeoContext ctx = NeoContext.builder().endpointType(NeoEndpointType.CALLOUT).requestBody(body).previousResult(
        NeoResponse.ok(prevBody)).build();

    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(eq(InventoryCount.class), eq("inv-missing"))).thenReturn(null);

      assertNull(HANDLER.afterCallout(ctx));
    }
  }

  // ── queryProductStock() ───────────────────────────────────────────────────

  /**
   * queryProductStock() must return 0.0 when the SQL query yields no rows.
   */
  @SuppressWarnings("unchecked")
  @Test
  public void testQueryProductStockReturnsZeroWhenResultIsNull() {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      Session session = mock(Session.class);
      when(dal.getSession()).thenReturn(session);
      NativeQuery<Object> nq = mock(NativeQuery.class);
      when(session.createNativeQuery(anyString())).thenReturn(nq);
      when(nq.setParameter(anyString(), any())).thenReturn(nq);
      when(nq.uniqueResult()).thenReturn(null);

      assertEquals(0.0, InventoryLineHandler.queryProductStock("wh-1", "prod-1"), 0.0);
    }
  }

  /**
   * queryProductStock() must convert a BigDecimal SQL result to double correctly.
   */
  @SuppressWarnings("unchecked")
  @Test
  public void testQueryProductStockReturnsActualValue() {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      Session session = mock(Session.class);
      when(dal.getSession()).thenReturn(session);
      NativeQuery<Object> nq = mock(NativeQuery.class);
      when(session.createNativeQuery(anyString())).thenReturn(nq);
      when(nq.setParameter(anyString(), any())).thenReturn(nq);
      when(nq.uniqueResult()).thenReturn(new BigDecimal("296.00"));

      assertEquals(296.0, InventoryLineHandler.queryProductStock("wh-1", "prod-1"), 0.001);
    }
  }

  /**
   * queryProductStock() must handle an Integer SQL result via the Number contract.
   */
  @SuppressWarnings("unchecked")
  @Test
  public void testQueryProductStockHandlesIntegerResult() {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      Session session = mock(Session.class);
      when(dal.getSession()).thenReturn(session);
      NativeQuery<Object> nq = mock(NativeQuery.class);
      when(session.createNativeQuery(anyString())).thenReturn(nq);
      when(nq.setParameter(anyString(), any())).thenReturn(nq);
      when(nq.uniqueResult()).thenReturn(100);

      assertEquals(100.0, InventoryLineHandler.queryProductStock("wh-1", "prod-1"), 0.0);
    }
  }

  // ── resolveDefaultLocatorInfo() ───────────────────────────────────────────────

  /**
   * resolveDefaultLocatorInfo() must return null when the inventory's warehouse is absent.
   */
  @Test
  public void testResolveDefaultLocatorInfoReturnsNullWhenWarehouseIsNull() {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      InventoryCount inventory = mock(InventoryCount.class);
      when(dal.get(eq(InventoryCount.class), eq("inv-1"))).thenReturn(inventory);
      when(inventory.getWarehouse()).thenReturn(null);

      assertNull(InventoryLineHandler.resolveDefaultLocatorInfo("inv-1"));
    }
  }

  /**
   * resolveDefaultLocatorInfo() must return null when no default active locator exists.
   */
  @SuppressWarnings("unchecked")
  @Test
  public void testResolveDefaultLocatorInfoReturnsNullWhenLocatorNotFound() {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      InventoryCount inventory = mock(InventoryCount.class);
      Warehouse warehouse = mock(Warehouse.class);
      when(dal.get(eq(InventoryCount.class), eq("inv-1"))).thenReturn(inventory);
      when(inventory.getWarehouse()).thenReturn(warehouse);
      OBCriteria criteria = mock(OBCriteria.class);
      when(dal.createCriteria(Locator.class)).thenReturn(criteria);
      when(criteria.add(any())).thenReturn(criteria);
      when(criteria.addOrder(any())).thenReturn(criteria);
      when(criteria.setMaxResults(1)).thenReturn(criteria);
      when(criteria.uniqueResult()).thenReturn(null);

      assertNull(InventoryLineHandler.resolveDefaultLocatorInfo("inv-1"));
    }
  }

  /**
   * resolveDefaultLocatorInfo() must return a fully-populated LocatorInfo when all data is found.
   */
  @SuppressWarnings("unchecked")
  @Test
  public void testResolveDefaultLocatorInfoReturnsLocatorInfoWhenFound() {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      InventoryCount inventory = mock(InventoryCount.class);
      Warehouse warehouse = mock(Warehouse.class);
      Locator locator = mock(Locator.class);
      when(dal.get(eq(InventoryCount.class), eq("inv-1"))).thenReturn(inventory);
      when(inventory.getWarehouse()).thenReturn(warehouse);
      when(warehouse.getName()).thenReturn("Main Warehouse");
      when(warehouse.getId()).thenReturn("wh-1");
      when(locator.getId()).thenReturn("loc-1");
      when(locator.getSearchKey()).thenReturn("L001");
      OBCriteria criteria = mock(OBCriteria.class);
      when(dal.createCriteria(Locator.class)).thenReturn(criteria);
      when(criteria.add(any())).thenReturn(criteria);
      when(criteria.addOrder(any())).thenReturn(criteria);
      when(criteria.setMaxResults(1)).thenReturn(criteria);
      when(criteria.uniqueResult()).thenReturn(locator);

      InventoryLineHandler.LocatorInfo info = InventoryLineHandler.resolveDefaultLocatorInfo("inv-1");
      assertEquals("loc-1", info.locatorId);
      assertEquals("L001", info.locatorValue);
      assertEquals("Main Warehouse", info.warehouseName);
      assertEquals("wh-1", info.warehouseId);
    }
  }

  // ── handle() — POST path ──────────────────────────────────────────────────────

  /**
   * handle() POST with an empty parentId must return without modifying the body.
   */
  @Test
  public void testHandlePostWithEmptyParentIdIsNoOp() throws Exception {
    JSONObject body = new JSONObject().put("product", "prod-1");
    NeoContext ctx = NeoContext.builder().httpMethod("POST").endpointType(NeoEndpointType.CRUD).requestBody(body).build();
    assertNull(HANDLER.handle(ctx));
    assertFalse(body.has("storageBin"));
  }

  /**
   * handle() POST must set storageBin and bookQuantity when parentId, locator and product all resolve.
   */
  @SuppressWarnings("unchecked")
  @Test
  public void testHandlePostSetsStorageBinAndBookQuantity() throws Exception {
    JSONObject body = new JSONObject().put("parentId", "inv-1").put("product", "prod-1");
    NeoContext ctx = NeoContext.builder().httpMethod("POST").endpointType(NeoEndpointType.CRUD).requestBody(body).build();

    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      InventoryCount inventory = mock(InventoryCount.class);
      Warehouse warehouse = mock(Warehouse.class);
      Locator locator = mock(Locator.class);
      when(dal.get(eq(InventoryCount.class), eq("inv-1"))).thenReturn(inventory);
      when(inventory.getWarehouse()).thenReturn(warehouse);
      when(warehouse.getName()).thenReturn("WH1");
      when(warehouse.getId()).thenReturn("wh-1");
      when(locator.getId()).thenReturn("loc-1");
      when(locator.getSearchKey()).thenReturn("L001");
      OBCriteria criteria = mock(OBCriteria.class);
      when(dal.createCriteria(Locator.class)).thenReturn(criteria);
      when(criteria.add(any())).thenReturn(criteria);
      when(criteria.addOrder(any())).thenReturn(criteria);
      when(criteria.setMaxResults(1)).thenReturn(criteria);
      when(criteria.uniqueResult()).thenReturn(locator);
      Session session = mock(Session.class);
      when(dal.getSession()).thenReturn(session);
      NativeQuery<Object> nq = mock(NativeQuery.class);
      when(session.createNativeQuery(anyString())).thenReturn(nq);
      when(nq.setParameter(anyString(), any())).thenReturn(nq);
      when(nq.uniqueResult()).thenReturn(new BigDecimal("100.00"));

      assertNull(HANDLER.handle(ctx));
      assertEquals("loc-1", body.getString("storageBin"));
      assertEquals(100.0, body.getDouble("bookQuantity"), 0.001);
    }
  }

  // ── handle() — PATCH path ─────────────────────────────────────────────────────

  /**
   * handle() PATCH must be a no-op when the context carries no record ID.
   */
  @Test
  public void testHandlePatchWithNullLineIdIsNoOp() {
    NeoContext ctx = mock(NeoContext.class);
    when(ctx.getEndpointType()).thenReturn(NeoEndpointType.CRUD);
    when(ctx.getHttpMethod()).thenReturn("PATCH");
    when(ctx.getRequestBody()).thenReturn(new JSONObject());
    when(ctx.getRecordId()).thenReturn(null);
    assertNull(HANDLER.handle(ctx));
  }

  /**
   * handle() PATCH must be a no-op when the inventory line record does not exist.
   */
  @Test
  public void testHandlePatchWithLineNotFoundIsNoOp() {
    NeoContext ctx = mock(NeoContext.class);
    when(ctx.getEndpointType()).thenReturn(NeoEndpointType.CRUD);
    when(ctx.getHttpMethod()).thenReturn("PATCH");
    when(ctx.getRequestBody()).thenReturn(new JSONObject());
    when(ctx.getRecordId()).thenReturn("line-missing");

    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(eq(InventoryCountLine.class), eq("line-missing"))).thenReturn(null);
      assertNull(HANDLER.handle(ctx));
    }
  }

  /**
   * handle() PATCH must set bookQuantity on the entity when the product resolves via the request body.
   */
  @SuppressWarnings("unchecked")
  @Test
  public void testHandlePatchSetsBookQuantityOnEntity() throws Exception {
    JSONObject body = new JSONObject().put("product", "prod-1");
    NeoContext ctx = mock(NeoContext.class);
    when(ctx.getEndpointType()).thenReturn(NeoEndpointType.CRUD);
    when(ctx.getHttpMethod()).thenReturn("PATCH");
    when(ctx.getRequestBody()).thenReturn(body);
    when(ctx.getRecordId()).thenReturn("line-1");

    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      InventoryCountLine line = mock(InventoryCountLine.class);
      InventoryCount inventory = mock(InventoryCount.class);
      Warehouse warehouse = mock(Warehouse.class);
      when(dal.get(eq(InventoryCountLine.class), eq("line-1"))).thenReturn(line);
      when(line.getPhysInventory()).thenReturn(inventory);
      when(inventory.getId()).thenReturn("inv-1");
      when(dal.get(eq(InventoryCount.class), eq("inv-1"))).thenReturn(inventory);
      when(inventory.getWarehouse()).thenReturn(warehouse);
      when(warehouse.getName()).thenReturn("WH1");
      when(warehouse.getId()).thenReturn("wh-1");
      Locator locator = mock(Locator.class);
      when(locator.getId()).thenReturn("loc-1");
      when(locator.getSearchKey()).thenReturn("L001");
      OBCriteria criteria = mock(OBCriteria.class);
      when(dal.createCriteria(Locator.class)).thenReturn(criteria);
      when(criteria.add(any())).thenReturn(criteria);
      when(criteria.addOrder(any())).thenReturn(criteria);
      when(criteria.setMaxResults(1)).thenReturn(criteria);
      when(criteria.uniqueResult()).thenReturn(locator);
      Session session = mock(Session.class);
      when(dal.getSession()).thenReturn(session);
      NativeQuery<Object> nq = mock(NativeQuery.class);
      when(session.createNativeQuery(anyString())).thenReturn(nq);
      when(nq.setParameter(anyString(), any())).thenReturn(nq);
      when(nq.uniqueResult()).thenReturn(new BigDecimal("200.00"));

      assertNull(HANDLER.handle(ctx));
      verify(line).setBookQuantity(BigDecimal.valueOf(200.0));
      verify(dal).save(line);
    }
  }

  // ── afterCallout() — success path ─────────────────────────────────────────────

  /**
   * afterCallout() must override both bookQuantity and quantityCount with warehouse-scoped stock.
   */
  @SuppressWarnings("unchecked")
  @Test
  public void testAfterCalloutOverridesBothQuantityFieldsOnSuccess() throws Exception {
    JSONObject updates = new JSONObject()
        .put("bookQuantity", new JSONObject().put("value", 50.0))
        .put("quantityCount", new JSONObject().put("value", 50.0));
    JSONObject prevBody = new JSONObject().put("updates", updates);
    JSONObject body = new JSONObject().put("value", "prod-1").put("formState",
        new JSONObject().put("physInventory", "inv-1"));
    NeoContext ctx = NeoContext.builder().endpointType(NeoEndpointType.CALLOUT).requestBody(body).previousResult(
        NeoResponse.ok(prevBody)).build();

    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      InventoryCount inventory = mock(InventoryCount.class);
      Warehouse warehouse = mock(Warehouse.class);
      when(dal.get(eq(InventoryCount.class), eq("inv-1"))).thenReturn(inventory);
      when(inventory.getWarehouse()).thenReturn(warehouse);
      when(warehouse.getName()).thenReturn("WH1");
      when(warehouse.getId()).thenReturn("wh-1");
      Locator locator = mock(Locator.class);
      when(locator.getId()).thenReturn("loc-1");
      when(locator.getSearchKey()).thenReturn("L001");
      OBCriteria criteria = mock(OBCriteria.class);
      when(dal.createCriteria(Locator.class)).thenReturn(criteria);
      when(criteria.add(any())).thenReturn(criteria);
      when(criteria.addOrder(any())).thenReturn(criteria);
      when(criteria.setMaxResults(1)).thenReturn(criteria);
      when(criteria.uniqueResult()).thenReturn(locator);
      Session session = mock(Session.class);
      when(dal.getSession()).thenReturn(session);
      NativeQuery<Object> nq = mock(NativeQuery.class);
      when(session.createNativeQuery(anyString())).thenReturn(nq);
      when(nq.setParameter(anyString(), any())).thenReturn(nq);
      when(nq.uniqueResult()).thenReturn(new BigDecimal("75.00"));

      assertNull(HANDLER.afterCallout(ctx));
      assertEquals(75.0,
          prevBody.getJSONObject("updates").getJSONObject("bookQuantity").getDouble("value"), 0.001);
      assertEquals(75.0,
          prevBody.getJSONObject("updates").getJSONObject("quantityCount").getDouble("value"), 0.001);
    }
  }

  /**
   * afterCallout() must use the formState "id" field as fallback when "physInventory" is absent.
   */
  @SuppressWarnings("unchecked")
  @Test
  public void testAfterCalloutFallsBackToFormStateId() throws Exception {
    JSONObject updates = new JSONObject()
        .put("bookQuantity", new JSONObject().put("value", 50.0))
        .put("quantityCount", new JSONObject().put("value", 50.0));
    JSONObject prevBody = new JSONObject().put("updates", updates);
    JSONObject body = new JSONObject().put("value", "prod-1").put("formState",
        new JSONObject().put("id", "inv-fallback"));
    NeoContext ctx = NeoContext.builder().endpointType(NeoEndpointType.CALLOUT).requestBody(body).previousResult(
        NeoResponse.ok(prevBody)).build();

    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      InventoryCount inventory = mock(InventoryCount.class);
      Warehouse warehouse = mock(Warehouse.class);
      when(dal.get(eq(InventoryCount.class), eq("inv-fallback"))).thenReturn(inventory);
      when(inventory.getWarehouse()).thenReturn(warehouse);
      when(warehouse.getName()).thenReturn("WH2");
      when(warehouse.getId()).thenReturn("wh-2");
      Locator locator = mock(Locator.class);
      when(locator.getId()).thenReturn("loc-2");
      when(locator.getSearchKey()).thenReturn("L002");
      OBCriteria criteria = mock(OBCriteria.class);
      when(dal.createCriteria(Locator.class)).thenReturn(criteria);
      when(criteria.add(any())).thenReturn(criteria);
      when(criteria.addOrder(any())).thenReturn(criteria);
      when(criteria.setMaxResults(1)).thenReturn(criteria);
      when(criteria.uniqueResult()).thenReturn(locator);
      Session session = mock(Session.class);
      when(dal.getSession()).thenReturn(session);
      NativeQuery<Object> nq = mock(NativeQuery.class);
      when(session.createNativeQuery(anyString())).thenReturn(nq);
      when(nq.setParameter(anyString(), any())).thenReturn(nq);
      when(nq.uniqueResult()).thenReturn(new BigDecimal("55.00"));

      assertNull(HANDLER.afterCallout(ctx));
      assertEquals(55.0,
          prevBody.getJSONObject("updates").getJSONObject("bookQuantity").getDouble("value"), 0.001);
    }
  }
}
