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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Collections;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.erpCommon.utility.OBCurrencyUtils;
import org.openbravo.erpCommon.utility.OBMessageUtils;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.pricing.pricelist.PriceList;
import org.openbravo.model.pricing.pricelist.PriceListSchema;
import org.openbravo.model.pricing.pricelist.PriceListVersion;
import org.openbravo.model.pricing.pricelist.ProductPrice;

/**
 * Unit tests for {@link PriceListHeaderHandler}.
 *
 * <p>Tests are split into the following groups:
 * <ul>
 *   <li><strong>handle()</strong> – always returns null (pass-through pre-hook)</li>
 *   <li><strong>Guard conditions</strong> – early returns without any DB access</li>
 *   <li><strong>GET</strong> – injects {@code priceListVersion} on single-record and batch responses</li>
 *   <li><strong>POST</strong> – auto-creates the hidden version (idempotent) and injects it</li>
 *   <li><strong>PATCH / PUT</strong> – syncs the version name with the price list name</li>
 *   <li><strong>Error resilience</strong> – returns null when downstream resolution throws</li>
 * </ul>
 */
public class PriceListHeaderHandlerTest {

  // ── helpers ───────────────────────────────────────────────────────────────

  private static NeoContext getCtxWithId(String recordId, String method) {
    return NeoContext.builder()
        .specName("price-list")
        .entityName("priceList")
        .httpMethod(method)
        .endpointType(NeoEndpointType.CRUD)
        .recordId(recordId)
        .build();
  }

  private static NeoContext getCtxWithId(String recordId) {
    return getCtxWithId(recordId, "GET");
  }

  private static NeoContext listCtx() {
    return getCtxWithId(null);
  }

  private static JSONObject singleRecordBody(String id) throws JSONException {
    JSONArray data = new JSONArray().put(new JSONObject().put("id", id));
    return new JSONObject().put("response", new JSONObject().put("data", data));
  }

  private static JSONObject listBody(String... ids) throws JSONException {
    JSONArray data = new JSONArray();
    for (String id : ids) {
      data.put(new JSONObject().put("id", id));
    }
    return new JSONObject().put("response", new JSONObject().put("data", data));
  }

  private static NeoContext ctxWithPreviousResult(String recordId, JSONObject body) {
    NeoContext ctx = getCtxWithId(recordId);
    ctx.setPreviousResult(NeoResponse.ok(body));
    return ctx;
  }

  private static NeoContext listCtxWithPreviousResult(JSONObject body) {
    NeoContext ctx = listCtx();
    ctx.setPreviousResult(NeoResponse.ok(body));
    return ctx;
  }

  private static NeoContext postCtxWithBody(JSONObject body) {
    NeoContext ctx = getCtxWithId(null, "POST");
    ctx.setPreviousResult(NeoResponse.ok(body));
    return ctx;
  }

  private static NeoContext patchCtxWithBody(String recordId, JSONObject body) {
    NeoContext ctx = getCtxWithId(recordId, "PATCH");
    ctx.setPreviousResult(NeoResponse.ok(body));
    return ctx;
  }

  @SuppressWarnings("unchecked")
  private static OBCriteria<PriceListVersion> stubVersionCriteria(OBDal dal,
      java.util.List<PriceListVersion> versions) {
    OBCriteria<PriceListVersion> crit = mock(OBCriteria.class);
    when(dal.createCriteria(PriceListVersion.class)).thenReturn(crit);
    when(crit.list()).thenReturn(versions);
    return crit;
  }

  @SuppressWarnings("unchecked")
  private static OBCriteria<PriceListSchema> stubSchemaCriteria(OBDal dal,
      java.util.List<PriceListSchema> schemas) {
    OBCriteria<PriceListSchema> crit = mock(OBCriteria.class);
    when(dal.createCriteria(PriceListSchema.class)).thenReturn(crit);
    when(crit.list()).thenReturn(schemas);
    return crit;
  }

  /**
   * Stubs {@code dal.createCriteria(ProductPrice.class)} used by the product-count
   * computation (ETP-4592). Defaults both call shapes to "no products" (single-record
   * {@code uniqueResult()} and batch {@code list()}) so tests that don't care about the
   * count still pass; callers that do care override the relevant stub afterwards.
   */
  @SuppressWarnings("unchecked")
  private static OBCriteria<ProductPrice> stubProductPriceCriteria(OBDal dal) {
    OBCriteria<ProductPrice> crit = mock(OBCriteria.class);
    when(dal.createCriteria(ProductPrice.class)).thenReturn(crit);
    when(crit.uniqueResult()).thenReturn(0L);
    when(crit.list()).thenReturn(Collections.emptyList());
    return crit;
  }

  // ── handle() ──────────────────────────────────────────────────────────────

  /**
   * Verifies that handle always returns null (pure pass-through pre-hook).
   */
  @Test
  public void testHandleAlwaysReturnsNull() {
    NeoContext ctx = NeoContext.builder()
        .httpMethod("GET").endpointType(NeoEndpointType.CRUD).build();
    assertNull(new PriceListHeaderHandler().handle(ctx));
  }

  private static NeoContext postCreateCtx(JSONObject requestBody, OBContext obContext) {
    return NeoContext.builder()
        .specName("price-list").entityName("priceList")
        .httpMethod("POST").endpointType(NeoEndpointType.CRUD)
        .requestBody(requestBody).obContext(obContext).build();
  }

  /**
   * Tariffs inherit the organization currency: a POST create without a {@code currency}
   * field gets the org currency injected into the request body (so the mandatory
   * C_Currency_ID column is satisfied), and handle() still returns null to continue
   * to the default create.
   */
  @Test
  public void testHandlePostInjectsOrgCurrencyWhenMissing() throws JSONException {
    JSONObject body = new JSONObject().put("name", "My Tariff").put("salesPriceList", true);
    OBContext obContext = mock(OBContext.class);
    Organization org = mock(Organization.class);
    when(org.getId()).thenReturn("ORG1");
    when(obContext.getCurrentOrganization()).thenReturn(org);

    try (MockedStatic<OBContext> obCtxMock = Mockito.mockStatic(OBContext.class);
         MockedStatic<OBCurrencyUtils> curMock = Mockito.mockStatic(OBCurrencyUtils.class)) {
      curMock.when(() -> OBCurrencyUtils.getOrgCurrency("ORG1")).thenReturn("CUR1");

      assertNull(new PriceListHeaderHandler().handle(postCreateCtx(body, obContext)));
      assertEquals("CUR1", body.getString("currency"));
    }
  }

  /**
   * A POST that already carries a currency is left untouched — the org-currency resolver
   * is never invoked.
   */
  @Test
  public void testHandlePostKeepsExplicitCurrency() throws JSONException {
    JSONObject body = new JSONObject().put("name", "My Tariff").put("currency", "EXISTING");

    try (MockedStatic<OBCurrencyUtils> curMock = Mockito.mockStatic(OBCurrencyUtils.class)) {
      assertNull(new PriceListHeaderHandler().handle(postCreateCtx(body, mock(OBContext.class))));
      assertEquals("EXISTING", body.getString("currency"));
      curMock.verifyNoInteractions();
    }
  }

  /**
   * When the org currency cannot be resolved, the body is left without a currency (the
   * downstream defaults/validation layer handles it) and no exception propagates.
   */
  @Test
  public void testHandlePostLeavesCurrencyUnsetWhenUnresolved() throws JSONException {
    JSONObject body = new JSONObject().put("name", "My Tariff");
    OBContext obContext = mock(OBContext.class);
    Organization org = mock(Organization.class);
    when(org.getId()).thenReturn("ORG1");
    when(obContext.getCurrentOrganization()).thenReturn(org);

    try (MockedStatic<OBContext> obCtxMock = Mockito.mockStatic(OBContext.class);
         MockedStatic<OBCurrencyUtils> curMock = Mockito.mockStatic(OBCurrencyUtils.class)) {
      curMock.when(() -> OBCurrencyUtils.getOrgCurrency("ORG1")).thenReturn(null);

      assertNull(new PriceListHeaderHandler().handle(postCreateCtx(body, obContext)));
      assertFalse(body.has("currency"));
    }
  }

  /**
   * A GET never triggers currency injection (only POST creates need it).
   */
  @Test
  public void testHandleGetDoesNotInjectCurrency() throws JSONException {
    JSONObject body = new JSONObject().put("name", "My Tariff");
    NeoContext ctx = NeoContext.builder()
        .httpMethod("GET").endpointType(NeoEndpointType.CRUD)
        .requestBody(body).build();

    try (MockedStatic<OBCurrencyUtils> curMock = Mockito.mockStatic(OBCurrencyUtils.class)) {
      assertNull(new PriceListHeaderHandler().handle(ctx));
      assertFalse(body.has("currency"));
      curMock.verifyNoInteractions();
    }
  }

  // ── handle() — block deactivating a default price list (ETP-4592) ─────────

  private static final String MSG_KEY_CANNOT_DEACTIVATE = "ETGO_PriceListCannotDeactivateDefault";

  private static NeoContext patchCtx(String recordId, JSONObject requestBody) {
    return methodCtx("PATCH", recordId, requestBody);
  }

  private static NeoContext methodCtx(String method, String recordId, JSONObject requestBody) {
    return NeoContext.builder()
        .specName("price-list").entityName("priceList")
        .httpMethod(method).endpointType(NeoEndpointType.CRUD)
        .recordId(recordId).requestBody(requestBody).build();
  }

  /**
   * A PATCH that explicitly sets {@code active: false} on a price list currently marked as
   * default is blocked with a 400, and the error text comes from the AD_Message catalog via
   * {@code OBMessageUtils.messageBD} (which resolves the requesting user's language at runtime).
   */
  @Test
  public void testHandleBlocksDeactivatingDefaultPriceList() throws JSONException {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
         MockedStatic<OBMessageUtils> msgMock = Mockito.mockStatic(OBMessageUtils.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      PriceList pl = mock(PriceList.class);
      when(pl.isDefault()).thenReturn(true);
      when(dal.get(PriceList.class, "pl-1")).thenReturn(pl);
      msgMock.when(() -> OBMessageUtils.messageBD(MSG_KEY_CANNOT_DEACTIVATE))
          .thenReturn("Localized message");

      JSONObject body = new JSONObject().put("active", false);
      NeoResponse result = new PriceListHeaderHandler().handle(patchCtx("pl-1", body));

      assertNotNull(result);
      assertEquals(400, result.getHttpStatus());
      assertEquals("Localized message",
          result.getBody().getJSONObject("error").getString("message"));
      msgMock.verify(() -> OBMessageUtils.messageBD(MSG_KEY_CANNOT_DEACTIVATE));
    }
  }

  /**
   * Deactivating a price list that is NOT marked as default is allowed (handle() falls through)
   * and the message catalog is never consulted.
   */
  @Test
  public void testHandleAllowsDeactivatingNonDefaultPriceList() throws JSONException {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
         MockedStatic<OBMessageUtils> msgMock = Mockito.mockStatic(OBMessageUtils.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      PriceList pl = mock(PriceList.class);
      when(pl.isDefault()).thenReturn(false);
      when(dal.get(PriceList.class, "pl-2")).thenReturn(pl);

      JSONObject body = new JSONObject().put("active", false);
      assertNull(new PriceListHeaderHandler().handle(patchCtx("pl-2", body)));
      msgMock.verifyNoInteractions();
    }
  }

  /**
   * A PATCH that doesn't touch {@code active} at all never triggers the guard — and never
   * even reaches OBDal, since {@code isExplicitlyDeactivating} short-circuits first.
   */
  @Test
  public void testHandleIgnoresPatchWithoutActiveField() throws JSONException {
    JSONObject body = new JSONObject().put("name", "Renamed Tariff");

    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      assertNull(new PriceListHeaderHandler().handle(patchCtx("pl-3", body)));
      obDalMock.verifyNoInteractions();
    }
  }

  /**
   * A PATCH that sets {@code active: true} (reactivating) is never blocked, regardless of the
   * default flag.
   */
  @Test
  public void testHandleIgnoresPatchReactivating() throws JSONException {
    JSONObject body = new JSONObject().put("active", true);

    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      assertNull(new PriceListHeaderHandler().handle(patchCtx("pl-4", body)));
      obDalMock.verifyNoInteractions();
    }
  }

  /**
   * The guard applies to PUT the same way it applies to PATCH — both are treated as updates.
   */
  @Test
  public void testHandleBlocksDeactivatingDefaultPriceListViaPut() throws JSONException {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
         MockedStatic<OBMessageUtils> msgMock = Mockito.mockStatic(OBMessageUtils.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      PriceList pl = mock(PriceList.class);
      when(pl.isDefault()).thenReturn(true);
      when(dal.get(PriceList.class, "pl-5")).thenReturn(pl);
      msgMock.when(() -> OBMessageUtils.messageBD(MSG_KEY_CANNOT_DEACTIVATE))
          .thenReturn("Localized message");

      JSONObject body = new JSONObject().put("active", false);
      NeoResponse result = new PriceListHeaderHandler().handle(methodCtx("PUT", "pl-5", body));

      assertNotNull(result);
      assertEquals(400, result.getHttpStatus());
    }
  }

  /**
   * {@code isExplicitlyDeactivating} also recognizes the string-encoded booleans
   * ({@code "false"}/{@code "N"}, case-insensitive) that some client payloads send instead of
   * a JSON boolean.
   */
  @Test
  public void testHandleBlocksDeactivatingWhenActiveIsStringEncoded() throws JSONException {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
         MockedStatic<OBMessageUtils> msgMock = Mockito.mockStatic(OBMessageUtils.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      PriceList pl = mock(PriceList.class);
      when(pl.isDefault()).thenReturn(true);
      when(dal.get(PriceList.class, "pl-6")).thenReturn(pl);
      msgMock.when(() -> OBMessageUtils.messageBD(MSG_KEY_CANNOT_DEACTIVATE))
          .thenReturn("Localized message");

      JSONObject body = new JSONObject().put("active", "N");
      assertNotNull(new PriceListHeaderHandler().handle(patchCtx("pl-6", body)));
    }
  }

  /**
   * A truthy string value for {@code active} (e.g. {@code "Y"}) is not a deactivation and never
   * triggers the guard.
   */
  @Test
  public void testHandleIgnoresStringEncodedActiveTrue() throws JSONException {
    JSONObject body = new JSONObject().put("active", "Y");

    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      assertNull(new PriceListHeaderHandler().handle(patchCtx("pl-7", body)));
      obDalMock.verifyNoInteractions();
    }
  }

  /**
   * Defensive guard: a PATCH with no record id (should not normally occur for an update) never
   * reaches the price list lookup — {@code blockDeactivatingDefault} returns null immediately.
   */
  @Test
  public void testHandleAllowsDeactivatingWhenRecordIdMissing() throws JSONException {
    JSONObject body = new JSONObject().put("active", false);

    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      assertNull(new PriceListHeaderHandler().handle(patchCtx(null, body)));
      obDalMock.verifyNoInteractions();
    }
  }

  /**
   * Defensive guard: if the price list id no longer resolves to a record (e.g., deleted between
   * request and validation), the guard does not block — there's nothing to protect.
   */
  @Test
  public void testHandleAllowsDeactivatingWhenPriceListNotFound() throws JSONException {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
         MockedStatic<OBMessageUtils> msgMock = Mockito.mockStatic(OBMessageUtils.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(PriceList.class, "pl-missing")).thenReturn(null);

      JSONObject body = new JSONObject().put("active", false);
      assertNull(new PriceListHeaderHandler().handle(patchCtx("pl-missing", body)));
      msgMock.verifyNoInteractions();
    }
  }

  // ── guard conditions ──────────────────────────────────────────────────────

  /**
   * Verifies that afterHandle returns null for unsupported HTTP methods (e.g., DELETE).
   */
  @Test
  public void testAfterHandleReturnsNullForUnsupportedMethod() {
    NeoContext ctx = NeoContext.builder()
        .httpMethod("DELETE").endpointType(NeoEndpointType.CRUD).build();
    assertNull(new PriceListHeaderHandler().afterHandle(ctx));
  }

  /**
   * Verifies that afterHandle returns null when no previous result is set.
   */
  @Test
  public void testAfterHandleReturnsNullWhenNoPreviousResult() {
    NeoContext ctx = getCtxWithId("pl-1");
    assertNull(new PriceListHeaderHandler().afterHandle(ctx));
  }

  /**
   * Verifies that afterHandle returns null when the previous result has a null body.
   */
  @Test
  public void testAfterHandleReturnsNullWhenBodyIsNull() {
    NeoContext ctx = getCtxWithId("pl-1");
    ctx.setPreviousResult(new NeoResponse(200, null));
    assertNull(new PriceListHeaderHandler().afterHandle(ctx));
  }

  /**
   * Verifies that afterHandle returns null when the response JSON lacks the "response" wrapper.
   */
  @Test
  public void testAfterHandleReturnsNullWhenNoResponseWrapper() throws JSONException {
    NeoContext ctx = getCtxWithId("pl-1");
    ctx.setPreviousResult(NeoResponse.ok(new JSONObject().put("other", "value")));
    assertNull(new PriceListHeaderHandler().afterHandle(ctx));
  }

  /**
   * Verifies that afterHandle returns null when the data array in the response is empty.
   */
  @Test
  public void testAfterHandleReturnsNullWhenDataArrayIsEmpty() throws JSONException {
    JSONObject body = new JSONObject().put("response",
        new JSONObject().put("data", new JSONArray()));
    NeoContext ctx = getCtxWithId("pl-1");
    ctx.setPreviousResult(NeoResponse.ok(body));
    assertNull(new PriceListHeaderHandler().afterHandle(ctx));
  }

  // ── GET — single-record ───────────────────────────────────────────────────

  /**
   * Verifies that afterHandle injects the version id into a single-record GET response.
   */
  @Test
  public void testAfterHandleSingleRecordInjectsVersionId() throws Exception {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);

      PriceList mockPl = mock(PriceList.class);
      when(dal.get(PriceList.class, "pl-1")).thenReturn(mockPl);
      PriceListVersion mockVersion = mock(PriceListVersion.class);
      when(mockVersion.getId()).thenReturn("v-1");
      stubVersionCriteria(dal, Collections.singletonList(mockVersion));
      OBCriteria<ProductPrice> productPriceCrit = stubProductPriceCriteria(dal);
      when(productPriceCrit.uniqueResult()).thenReturn(7L);

      JSONObject body = singleRecordBody("pl-1");
      NeoContext ctx = ctxWithPreviousResult("pl-1", body);

      NeoResponse result = new PriceListHeaderHandler().afterHandle(ctx);

      assertNotNull(result);
      assertEquals(200, result.getHttpStatus());
      JSONObject savedRecord = result.getBody()
          .getJSONObject("response").getJSONArray("data")
          .getJSONObject(0);
      assertEquals("v-1", savedRecord.getString("priceListVersion"));
      assertEquals(7L, savedRecord.getLong("etgoProductcount"));
    }
  }

  /**
   * Verifies that afterHandle injects an empty string for priceListVersion when the
   * price list record is not found in OBDal (e.g., stale id).
   */
  @Test
  public void testAfterHandleSingleRecordUsesEmptyStringWhenPriceListNotFound() throws Exception {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(PriceList.class, "pl-99")).thenReturn(null);

      JSONObject body = singleRecordBody("pl-99");
      NeoContext ctx = ctxWithPreviousResult("pl-99", body);

      NeoResponse result = new PriceListHeaderHandler().afterHandle(ctx);

      assertNotNull(result);
      assertEquals("", result.getBody()
          .getJSONObject("response").getJSONArray("data")
          .getJSONObject(0).getString("priceListVersion"));
    }
  }

  /**
   * Verifies that afterHandle injects an empty string when the price list has no version yet.
   */
  @Test
  public void testAfterHandleSingleRecordUsesEmptyStringWhenNoVersionExists() throws Exception {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);

      PriceList mockPl = mock(PriceList.class);
      when(dal.get(PriceList.class, "pl-2")).thenReturn(mockPl);
      stubVersionCriteria(dal, Collections.emptyList());

      JSONObject body = singleRecordBody("pl-2");
      NeoContext ctx = ctxWithPreviousResult("pl-2", body);

      NeoResponse result = new PriceListHeaderHandler().afterHandle(ctx);

      assertNotNull(result);
      assertEquals("", result.getBody()
          .getJSONObject("response").getJSONArray("data")
          .getJSONObject(0).getString("priceListVersion"));
    }
  }

  // ── GET — list (batch) ────────────────────────────────────────────────────

  /**
   * Verifies that afterHandle injects the correct version id for each record in a list response.
   */
  @Test
  public void testAfterHandleBatchInjectsVersionIds() throws Exception {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);

      PriceList pl1 = mock(PriceList.class);
      when(pl1.getId()).thenReturn("pl-1");
      PriceListVersion v1 = mock(PriceListVersion.class);
      when(v1.getPriceList()).thenReturn(pl1);
      when(v1.getId()).thenReturn("v-1");

      PriceList pl2 = mock(PriceList.class);
      when(pl2.getId()).thenReturn("pl-2");
      PriceListVersion v2 = mock(PriceListVersion.class);
      when(v2.getPriceList()).thenReturn(pl2);
      when(v2.getId()).thenReturn("v-2");

      stubVersionCriteria(dal, Arrays.asList(v1, v2));
      OBCriteria<ProductPrice> productPriceCrit = stubProductPriceCriteria(dal);
      // Raw type: the criteria projection turns list() into a List<Object[]> at runtime,
      // but the mock's generic signature is fixed to List<ProductPrice> — erase it to stub freely.
      @SuppressWarnings({ "unchecked", "rawtypes" })
      OBCriteria rawProductPriceCrit = productPriceCrit;
      when(rawProductPriceCrit.list()).thenReturn(Arrays.asList(
          new Object[] { "v-1", 3L },
          new Object[] { "v-2", 5L }));

      JSONObject body = listBody("pl-1", "pl-2");
      NeoContext ctx = listCtxWithPreviousResult(body);

      NeoResponse result = new PriceListHeaderHandler().afterHandle(ctx);

      assertNotNull(result);
      assertEquals(200, result.getHttpStatus());
      JSONArray data = result.getBody().getJSONObject("response").getJSONArray("data");
      assertEquals("v-1", data.getJSONObject(0).getString("priceListVersion"));
      assertEquals("v-2", data.getJSONObject(1).getString("priceListVersion"));
      assertEquals(3L, data.getJSONObject(0).getLong("etgoProductcount"));
      assertEquals(5L, data.getJSONObject(1).getLong("etgoProductcount"));
    }
  }

  /**
   * Verifies that afterHandle injects empty strings for records whose version is not found.
   */
  @Test
  public void testAfterHandleBatchUsesEmptyStringForMissingVersions() throws Exception {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      stubVersionCriteria(dal, Collections.emptyList());

      JSONObject body = listBody("pl-3", "pl-4");
      NeoContext ctx = listCtxWithPreviousResult(body);

      NeoResponse result = new PriceListHeaderHandler().afterHandle(ctx);

      assertNotNull(result);
      JSONArray data = result.getBody().getJSONObject("response").getJSONArray("data");
      assertEquals("", data.getJSONObject(0).getString("priceListVersion"));
      assertEquals("", data.getJSONObject(1).getString("priceListVersion"));
    }
  }

  // ── POST — auto-create version ────────────────────────────────────────────

  /**
   * Verifies that on POST, when the price list already has a version, no new version is created
   * (idempotent guard) and the existing version id is injected into the response.
   */
  @Test
  public void testAfterHandlePostDoesNotRecreateVersionWhenAlreadyExists() throws Exception {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
         MockedStatic<OBProvider> obProviderMock = Mockito.mockStatic(OBProvider.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);

      PriceList pl = mock(PriceList.class);
      when(dal.get(PriceList.class, "pl-new")).thenReturn(pl);
      PriceListVersion existing = mock(PriceListVersion.class);
      when(existing.getId()).thenReturn("v-existing");
      stubVersionCriteria(dal, Collections.singletonList(existing));
      stubProductPriceCriteria(dal);

      JSONObject body = singleRecordBody("pl-new");
      NeoContext ctx = postCtxWithBody(body);

      NeoResponse result = new PriceListHeaderHandler().afterHandle(ctx);

      assertNotNull(result);
      String injected = result.getBody().getJSONObject("response").getJSONArray("data")
          .getJSONObject(0).getString("priceListVersion");
      assertEquals("v-existing", injected);
      // OBProvider must not be touched because the guard short-circuits before creation
      obProviderMock.verifyNoInteractions();
    }
  }

  /**
   * Verifies that on POST, when the price list has no version and an existing
   * {@link PriceListSchema} is available for the client, the handler reuses that schema
   * (does not create a new one) and saves the new {@link PriceListVersion}.
   * Also verifies that {@code flush()} is called so the subsequent annotation step sees
   * the just-saved version.
   */
  @Test
  public void testAfterHandlePostCreatesVersionReusingExistingSchema() throws Exception {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
         MockedStatic<OBProvider> obProviderMock = Mockito.mockStatic(OBProvider.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);

      PriceList pl = mock(PriceList.class);
      when(pl.getName()).thenReturn("My PL");
      when(pl.getClient()).thenReturn(mock(Client.class));
      when(pl.getOrganization()).thenReturn(mock(Organization.class));
      when(dal.get(PriceList.class, "pl-new")).thenReturn(pl);
      stubVersionCriteria(dal, Collections.emptyList());

      PriceListSchema existingSchema = mock(PriceListSchema.class);
      stubSchemaCriteria(dal, Collections.singletonList(existingSchema));

      OBProvider provider = mock(OBProvider.class);
      obProviderMock.when(OBProvider::getInstance).thenReturn(provider);
      PriceListVersion newVersion = mock(PriceListVersion.class);
      when(provider.get(PriceListVersion.class)).thenReturn(newVersion);

      JSONObject body = singleRecordBody("pl-new");
      NeoContext ctx = postCtxWithBody(body);

      NeoResponse result = new PriceListHeaderHandler().afterHandle(ctx);

      assertNotNull(result);
      // Existing schema reused: OBProvider was never asked for a new PriceListSchema
      verify(provider, never()).get(PriceListSchema.class);
      verify(dal, never()).save(any(PriceListSchema.class));
      // Version was created and saved with the reused schema
      verify(dal, times(1)).save(newVersion);
      verify(newVersion).setName("My PL");
      verify(newVersion).setPriceList(pl);
      verify(newVersion).setPriceListSchema(existingSchema);
      // Flush ensures the just-saved version is visible to the subsequent criteria query
      verify(dal, times(1)).flush();
    }
  }

  /**
   * Verifies that on POST, when no {@link PriceListSchema} exists yet for the client
   * (first-run bootstrap), the handler creates a default one with the standard name and
   * uses it for the new version.
   */
  @Test
  public void testAfterHandlePostCreatesSchemaWhenNoneExists() throws Exception {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
         MockedStatic<OBProvider> obProviderMock = Mockito.mockStatic(OBProvider.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);

      PriceList pl = mock(PriceList.class);
      when(pl.getName()).thenReturn("My PL");
      when(pl.getClient()).thenReturn(mock(Client.class));
      when(pl.getOrganization()).thenReturn(mock(Organization.class));
      when(dal.get(PriceList.class, "pl-new")).thenReturn(pl);
      stubVersionCriteria(dal, Collections.emptyList());
      stubSchemaCriteria(dal, Collections.emptyList());

      OBProvider provider = mock(OBProvider.class);
      obProviderMock.when(OBProvider::getInstance).thenReturn(provider);
      PriceListSchema newSchema = mock(PriceListSchema.class);
      when(provider.get(PriceListSchema.class)).thenReturn(newSchema);
      PriceListVersion newVersion = mock(PriceListVersion.class);
      when(provider.get(PriceListVersion.class)).thenReturn(newVersion);

      JSONObject body = singleRecordBody("pl-new");
      NeoContext ctx = postCtxWithBody(body);

      NeoResponse result = new PriceListHeaderHandler().afterHandle(ctx);

      assertNotNull(result);
      verify(newSchema).setName("Esquema de Lista de Precios");
      verify(dal, times(1)).save(newSchema);
      verify(dal, times(1)).save(newVersion);
      verify(newVersion).setPriceListSchema(newSchema);
    }
  }

  // ── PATCH / PUT — sync version name ───────────────────────────────────────

  /**
   * Verifies that on PATCH, the handler updates the version name when the price list name
   * has changed and saves the version.
   */
  @Test
  public void testAfterHandlePatchSyncsVersionNameWhenChanged() throws Exception {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);

      PriceList pl = mock(PriceList.class);
      when(pl.getName()).thenReturn("New Name");
      when(dal.get(PriceList.class, "pl-1")).thenReturn(pl);
      PriceListVersion version = mock(PriceListVersion.class);
      when(version.getName()).thenReturn("Old Name");
      when(version.getId()).thenReturn("v-1");
      stubVersionCriteria(dal, Collections.singletonList(version));
      stubProductPriceCriteria(dal);

      JSONObject body = singleRecordBody("pl-1");
      NeoContext ctx = patchCtxWithBody("pl-1", body);

      NeoResponse result = new PriceListHeaderHandler().afterHandle(ctx);

      assertNotNull(result);
      verify(version).setName("New Name");
      verify(dal, times(1)).save(version);
    }
  }

  /**
   * Verifies that on PATCH, when the price list name is unchanged, the version is not saved
   * (avoids unnecessary updates).
   */
  @Test
  public void testAfterHandlePatchDoesNotSaveWhenNameUnchanged() throws Exception {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);

      PriceList pl = mock(PriceList.class);
      when(pl.getName()).thenReturn("Same Name");
      when(dal.get(PriceList.class, "pl-1")).thenReturn(pl);
      PriceListVersion version = mock(PriceListVersion.class);
      when(version.getName()).thenReturn("Same Name");
      when(version.getId()).thenReturn("v-1");
      stubVersionCriteria(dal, Collections.singletonList(version));
      stubProductPriceCriteria(dal);

      JSONObject body = singleRecordBody("pl-1");
      NeoContext ctx = patchCtxWithBody("pl-1", body);

      NeoResponse result = new PriceListHeaderHandler().afterHandle(ctx);

      assertNotNull(result);
      verify(version, never()).setName(any());
      verify(dal, never()).save(version);
    }
  }

  /**
   * Verifies that on PATCH, when the price list has no version, the handler does not throw
   * (a Classic-created price list may have no version).
   */
  @Test
  public void testAfterHandlePatchDoesNothingWhenNoVersionExists() throws Exception {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);

      PriceList pl = mock(PriceList.class);
      when(dal.get(PriceList.class, "pl-1")).thenReturn(pl);
      stubVersionCriteria(dal, Collections.emptyList());

      JSONObject body = singleRecordBody("pl-1");
      NeoContext ctx = patchCtxWithBody("pl-1", body);

      NeoResponse result = new PriceListHeaderHandler().afterHandle(ctx);

      assertNotNull(result);
      verify(dal, never()).save(any(PriceListVersion.class));
    }
  }

  // ── error resilience ──────────────────────────────────────────────────────

  /**
   * Verifies that afterHandle returns null and does not propagate exceptions when
   * the underlying resolver throws a RuntimeException.
   */
  @Test
  public void testAfterHandleReturnsNullOnUnexpectedException() throws JSONException {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(PriceList.class, "pl-err")).thenThrow(new RuntimeException("DB unavailable"));

      JSONObject body = singleRecordBody("pl-err");
      NeoContext ctx = ctxWithPreviousResult("pl-err", body);

      assertNull(new PriceListHeaderHandler().afterHandle(ctx));
    }
  }
}
