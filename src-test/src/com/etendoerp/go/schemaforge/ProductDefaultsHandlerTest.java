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
import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import org.codehaus.jettison.json.JSONObject;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.uom.UOM;
import org.openbravo.model.financialmgmt.tax.TaxCategory;

/**
 * Unit tests for {@link ProductDefaultsHandler} (ETP-4670).
 *
 * <p>{@link ProductDefaultsHandler#resolveDefaultId} is stubbed directly (via a spy) rather than
 * mocking the full {@code OBCriteria}/{@code OBDal} chain, since that chain is already covered by
 * {@code ProductCategoryDefaultHandlerTest} and the interesting behavior here is the
 * client-then-System COALESCE fallback and the pre/post-hook wiring, not the Hibernate criteria
 * construction itself.
 */
public class ProductDefaultsHandlerTest {

  private static final String SYSTEM_CLIENT_ID = "0";
  private static final String CLIENT1 = "CLIENT1";
  private static final String UOM_CLIENT_DEFAULT = "uom-client-default";
  private static final String UOM_SYSTEM_DEFAULT = "uom-system-default";
  private static final String TAX_CLIENT_DEFAULT = "tax-client-default";

  // ── helpers ───────────────────────────────────────────────────────────────

  private static OBContext obContextWithClient(String clientId) {
    OBContext obContext = mock(OBContext.class);
    Client client = mock(Client.class);
    when(client.getId()).thenReturn(clientId);
    when(obContext.getCurrentClient()).thenReturn(client);
    return obContext;
  }

  private static NeoContext postCtx(JSONObject body, OBContext obContext) {
    return NeoContext.builder()
        .specName("product").entityName("product")
        .httpMethod("POST").endpointType(NeoEndpointType.CRUD)
        .requestBody(body).obContext(obContext).build();
  }

  private static NeoContext defaultsCtx(OBContext obContext, NeoResponse previousResult) {
    return NeoContext.builder()
        .specName("product").entityName("product")
        .httpMethod("GET").endpointType(NeoEndpointType.DEFAULTS)
        .obContext(obContext).previousResult(previousResult).build();
  }

  /**
   * A spy whose {@link ProductDefaultsHandler#queryDefaultId} (the raw, single-client OBCriteria
   * lookup) is stubbed per-entity/client, so tests can express "client X has a default UOM row" /
   * "only System has one" without touching OBDal/Hibernate, while still exercising the REAL
   * client-then-System COALESCE fallback logic in {@link ProductDefaultsHandler#resolveDefaultId}.
   */
  private static ProductDefaultsHandler handlerResolving(String uomForClient, String uomForSystem,
      String taxForClient, String taxForSystem) {
    ProductDefaultsHandler handler = spy(new ProductDefaultsHandler());
    when(handler.queryDefaultId(UOM.class, CLIENT1)).thenReturn(uomForClient);
    when(handler.queryDefaultId(UOM.class, SYSTEM_CLIENT_ID)).thenReturn(uomForSystem);
    when(handler.queryDefaultId(TaxCategory.class, CLIENT1)).thenReturn(taxForClient);
    when(handler.queryDefaultId(TaxCategory.class, SYSTEM_CLIENT_ID)).thenReturn(taxForSystem);
    return handler;
  }

  // ── guard conditions ──────────────────────────────────────────────────────

  @Test
  public void testHandleReturnsNullForNullContext() {
    assertNull(new ProductDefaultsHandler().handle(null));
  }

  @Test
  public void testHandleIgnoresDifferentSpec() throws Exception {
    JSONObject body = new JSONObject();
    NeoContext ctx = NeoContext.builder()
        .specName("other-spec").httpMethod("POST").endpointType(NeoEndpointType.CRUD)
        .requestBody(body).build();
    assertNull(new ProductDefaultsHandler().handle(ctx));
    assertEquals(0, body.length());
  }

  @Test
  public void testHandleIgnoresNonPostMethods() throws Exception {
    JSONObject body = new JSONObject();
    NeoContext ctx = NeoContext.builder()
        .specName("product").httpMethod("PATCH").endpointType(NeoEndpointType.CRUD)
        .requestBody(body).build();
    assertNull(new ProductDefaultsHandler().handle(ctx));
    assertEquals(0, body.length());
  }

  // ── create (POST) — client default wins over System ──────────────────────

  @Test
  public void testHandleInjectsClientDefaultOnCreateWhenFieldsMissing() throws Exception {
    ProductDefaultsHandler handler = handlerResolving(
        UOM_CLIENT_DEFAULT, UOM_SYSTEM_DEFAULT, TAX_CLIENT_DEFAULT, "tax-system-default");

    JSONObject body = new JSONObject();
    NeoResponse result = handler.handle(postCtx(body, obContextWithClient(CLIENT1)));

    assertNull(result);
    assertEquals(UOM_CLIENT_DEFAULT, body.getString("uOM"));
    assertEquals(TAX_CLIENT_DEFAULT, body.getString("taxCategory"));
  }

  @Test
  public void testHandleDoesNotOverwriteExplicitlyProvidedFields() throws Exception {
    ProductDefaultsHandler handler = handlerResolving(
        UOM_CLIENT_DEFAULT, UOM_SYSTEM_DEFAULT, TAX_CLIENT_DEFAULT, "tax-system-default");

    JSONObject body = new JSONObject().put("uOM", "user-chosen-uom");
    handler.handle(postCtx(body, obContextWithClient(CLIENT1)));

    // uOM was explicitly provided by the caller — never overwritten.
    assertEquals("user-chosen-uom", body.getString("uOM"));
    // taxCategory was missing — still gets injected.
    assertEquals(TAX_CLIENT_DEFAULT, body.getString("taxCategory"));
  }

  // ── create (POST) — fallback to System (client '0') ───────────────────────

  @Test
  public void testHandleFallsBackToSystemDefaultWhenClientHasNone() throws Exception {
    // Client has no default UOM/TaxCategory row of its own — only System ('0') does.
    ProductDefaultsHandler handler = handlerResolving(
        null, UOM_SYSTEM_DEFAULT, null, "tax-system-default");

    JSONObject body = new JSONObject();
    handler.handle(postCtx(body, obContextWithClient(CLIENT1)));

    assertEquals(UOM_SYSTEM_DEFAULT, body.getString("uOM"));
    assertEquals("tax-system-default", body.getString("taxCategory"));
  }

  // ── create (POST) — nothing marked default anywhere ───────────────────────

  @Test
  public void testHandleLeavesFieldsUnsetWhenNoDefaultExistsAnywhere() throws Exception {
    // Neither the client nor System has a row marked IsDefault='Y'.
    ProductDefaultsHandler handler = handlerResolving(null, null, null, null);

    JSONObject body = new JSONObject();
    NeoResponse result = handler.handle(postCtx(body, obContextWithClient(CLIENT1)));

    // Must not throw, and must not inject a bogus value: the field is simply left absent so the
    // generic CRUD / combo-fallback (or a NOT NULL validation error) handles it downstream.
    assertNull(result);
    assertEquals(false, body.has("uOM"));
    assertEquals(false, body.has("taxCategory"));
  }

  @Test
  public void testHandleUsesSystemDirectlyWhenClientContextMissing() throws Exception {
    ProductDefaultsHandler handler = handlerResolving(
        UOM_CLIENT_DEFAULT, UOM_SYSTEM_DEFAULT, TAX_CLIENT_DEFAULT, "tax-system-default");

    JSONObject body = new JSONObject();
    handler.handle(postCtx(body, mock(OBContext.class)));

    // No client resolvable from context -> resolveDefaultId is called with clientId=null,
    // which short-circuits straight to the System lookup.
    assertEquals(UOM_SYSTEM_DEFAULT, body.getString("uOM"));
    assertEquals("tax-system-default", body.getString("taxCategory"));
  }

  // ── /defaults endpoint (afterHandle) ──────────────────────────────────────

  @Test
  public void testAfterHandleOverwritesDefaultsResponseWithClientDefault() throws Exception {
    ProductDefaultsHandler handler = handlerResolving(
        UOM_CLIENT_DEFAULT, UOM_SYSTEM_DEFAULT, TAX_CLIENT_DEFAULT, "tax-system-default");

    JSONObject defaults = new JSONObject().put("name", "Some Product");
    JSONObject previousBody = new JSONObject().put("defaults", defaults);
    NeoResponse previous = NeoResponse.ok(previousBody);

    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      // Identifier lookup ($_identifier companion) is best-effort — return null so the
      // handler just skips it, which is fine for this test's assertions.
      when(dal.get(org.mockito.ArgumentMatchers.any(Class.class),
          org.mockito.ArgumentMatchers.anyString())).thenReturn(null);

      NeoResponse result = handler.afterHandle(defaultsCtx(obContextWithClient(CLIENT1), previous));

      assertEquals(UOM_CLIENT_DEFAULT,
          result.getBody().getJSONObject("defaults").getString("uOM"));
      assertEquals(TAX_CLIENT_DEFAULT,
          result.getBody().getJSONObject("defaults").getString("taxCategory"));
    }
  }

  @Test
  public void testAfterHandleIgnoresNonDefaultsEndpoint() throws Exception {
    JSONObject previousBody = new JSONObject();
    NeoContext ctx = NeoContext.builder()
        .specName("product").httpMethod("GET").endpointType(NeoEndpointType.CRUD)
        .previousResult(NeoResponse.ok(previousBody)).build();

    assertNull(new ProductDefaultsHandler().afterHandle(ctx));
  }

  @Test
  public void testAfterHandleLeavesDefaultsUntouchedWhenNoDefaultExistsAnywhere() throws Exception {
    ProductDefaultsHandler handler = handlerResolving(null, null, null, null);
    JSONObject defaults = new JSONObject();
    JSONObject previousBody = new JSONObject().put("defaults", defaults);
    NeoResponse previous = NeoResponse.ok(previousBody);

    NeoResponse result = handler.afterHandle(defaultsCtx(obContextWithClient(CLIENT1), previous));

    // Nothing to overwrite with -> the generic response passes through untouched (no crash).
    assertEquals(false, result.getBody().getJSONObject("defaults").has("uOM"));
    assertEquals(false, result.getBody().getJSONObject("defaults").has("taxCategory"));
  }
}
