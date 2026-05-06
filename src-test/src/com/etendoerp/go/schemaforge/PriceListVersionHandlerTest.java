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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Collections;

import org.codehaus.jettison.json.JSONObject;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.pricing.pricelist.PriceList;
import org.openbravo.model.pricing.pricelist.PriceListVersion;

/**
 * Unit tests for {@link PriceListVersionHandler}.
 *
 * <p>Tests are split into three groups:
 * <ul>
 *   <li><strong>Pass-through</strong> – early returns for non-POST, non-CRUD, missing body, etc.</li>
 *   <li><strong>First version allowed</strong> – POST proceeds when no version exists yet</li>
 *   <li><strong>Duplicate rejected</strong> – POST returns 409 when a version already exists</li>
 * </ul>
 */
public class PriceListVersionHandlerTest {

  // ── helpers ───────────────────────────────────────────────────────────────

  private static NeoContext crudPost(JSONObject body) {
    return NeoContext.builder()
        .specName("price-list")
        .entityName("priceListVersion")
        .httpMethod("POST")
        .endpointType(NeoEndpointType.CRUD)
        .requestBody(body)
        .build();
  }

  private static JSONObject bodyWithPriceList(String priceListId) throws Exception {
    return new JSONObject().put("priceList", priceListId);
  }

  @SuppressWarnings("unchecked")
  private static void stubVersionCriteria(OBDal dal,
      java.util.List<PriceListVersion> versions) {
    OBCriteria<PriceListVersion> crit = mock(OBCriteria.class);
    when(dal.createCriteria(PriceListVersion.class)).thenReturn(crit);
    when(crit.list()).thenReturn(versions);
  }

  // ── pass-through ──────────────────────────────────────────────────────────

  /**
   * Verifies that handle returns null for non-POST methods (read flows are not validated here).
   */
  @Test
  public void testHandleReturnsNullForGetMethod() {
    NeoContext ctx = NeoContext.builder()
        .httpMethod("GET").endpointType(NeoEndpointType.CRUD).build();
    assertNull(new PriceListVersionHandler().handle(ctx));
  }

  /**
   * Verifies that handle returns null for action endpoints (only CRUD POST is intercepted).
   */
  @Test
  public void testHandleReturnsNullForActionEndpoint() {
    NeoContext ctx = NeoContext.builder()
        .httpMethod("POST").endpointType(NeoEndpointType.ACTION).build();
    assertNull(new PriceListVersionHandler().handle(ctx));
  }

  /**
   * Verifies that handle returns null when the request body is missing.
   */
  @Test
  public void testHandleReturnsNullWhenBodyIsNull() {
    NeoContext ctx = crudPost(null);
    assertNull(new PriceListVersionHandler().handle(ctx));
  }

  /**
   * Verifies that handle returns null when the body has no priceList field
   * (let CRUD return its native validation error).
   */
  @Test
  public void testHandleReturnsNullWhenPriceListIsMissing() throws Exception {
    NeoContext ctx = crudPost(new JSONObject().put("name", "v1"));
    assertNull(new PriceListVersionHandler().handle(ctx));
  }

  /**
   * Verifies that handle returns null when the referenced price list cannot be loaded
   * (let CRUD return its native foreign-key error).
   */
  @Test
  public void testHandleReturnsNullWhenPriceListNotFound() throws Exception {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(PriceList.class, "pl-missing")).thenReturn(null);

      NeoContext ctx = crudPost(bodyWithPriceList("pl-missing"));
      assertNull(new PriceListVersionHandler().handle(ctx));
    }
  }

  /**
   * Verifies that afterHandle is a pass-through (always returns null).
   */
  @Test
  public void testAfterHandleAlwaysReturnsNull() {
    NeoContext ctx = NeoContext.builder()
        .httpMethod("POST").endpointType(NeoEndpointType.CRUD).build();
    assertNull(new PriceListVersionHandler().afterHandle(ctx));
  }

  // ── first version allowed ────────────────────────────────────────────────

  /**
   * Verifies that handle returns null (passing through to CRUD) when the price list has no
   * prior version, so the first version can be created normally.
   */
  @Test
  public void testHandleAllowsFirstVersion() throws Exception {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      PriceList pl = mock(PriceList.class);
      when(dal.get(PriceList.class, "pl-1")).thenReturn(pl);
      stubVersionCriteria(dal, Collections.emptyList());

      NeoContext ctx = crudPost(bodyWithPriceList("pl-1"));
      assertNull(new PriceListVersionHandler().handle(ctx));
    }
  }

  // ── duplicate rejected ───────────────────────────────────────────────────

  /**
   * Verifies that handle returns a 409 error response when the price list already has a version,
   * preventing the second insert from reaching the database via the NEO API.
   */
  @Test
  public void testHandleRejectsDuplicateVersionWith409() throws Exception {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      PriceList pl = mock(PriceList.class);
      when(pl.getName()).thenReturn("Test PL");
      when(dal.get(PriceList.class, "pl-1")).thenReturn(pl);
      PriceListVersion existing = mock(PriceListVersion.class);
      when(existing.getId()).thenReturn("v-existing");
      stubVersionCriteria(dal, Collections.singletonList(existing));

      NeoContext ctx = crudPost(bodyWithPriceList("pl-1"));
      NeoResponse result = new PriceListVersionHandler().handle(ctx);

      assertNotNull(result);
      assertEquals(409, result.getHttpStatus());
      assertNotNull(result.getBody());
      assertEquals(409, result.getBody().getJSONObject("error").getInt("status"));
    }
  }
}
