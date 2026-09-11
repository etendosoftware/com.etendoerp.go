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
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.criterion.Criterion;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.common.plm.Product;
import org.openbravo.model.materialmgmt.cost.Costing;
import org.openbravo.model.common.uom.UOM;
import org.openbravo.model.financialmgmt.tax.TaxCategory;
import org.openbravo.model.pricing.pricelist.PriceListVersion;
import org.openbravo.model.pricing.pricelist.ProductPrice;

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

  // ── ETP-4943: a Service product is never stocked/returnable ──────────────
  //
  // Enforced here (not just in the frontend `ProductAdditionalInfoPanel.jsx`) because that
  // panel's client-side auto-correction only runs while the "Additional Info" tab is mounted —
  // a user who sets Type=Service on the "General" tab and saves immediately never triggers it.
  // Mirrors the ETP-4606 defense-in-depth precedent (`ServiceProductGuard`), but as an
  // auto-correction rather than a rejection, since the product record itself (unlike a
  // stock-movement line) has no valid alternative to fall back to.

  private static NeoContext patchCtx(JSONObject body) {
    return NeoContext.builder()
        .specName("product").entityName("product")
        .httpMethod("PATCH").endpointType(NeoEndpointType.CRUD)
        .requestBody(body).build();
  }

  @Test
  public void testHandleForcesStockedAndReturnableFalseOnCreateWhenTypeIsService() throws Exception {
    JSONObject body = new JSONObject()
        .put("productType", "S")
        .put("stocked", true)
        .put("returnable", true);

    NeoResponse result = new ProductDefaultsHandler().handle(postCtx(body, mock(OBContext.class)));

    assertNull(result);
    assertEquals(false, body.getBoolean("stocked"));
    assertEquals(false, body.getBoolean("returnable"));
  }

  @Test
  public void testHandleForcesStockedAndReturnableFalseOnCreateWhenTypeIsServiceAndFlagsAbsent()
      throws Exception {
    // Flags omitted entirely (e.g. relying on whatever default the client applied) — must still
    // end up explicitly false, not just "left unset" (which could resolve to true downstream).
    JSONObject body = new JSONObject().put("productType", "S");

    new ProductDefaultsHandler().handle(postCtx(body, mock(OBContext.class)));

    assertEquals(false, body.getBoolean("stocked"));
    assertEquals(false, body.getBoolean("returnable"));
  }

  @Test
  public void testHandleForcesStockedAndReturnableFalseOnPatchWhenTypeIsService() throws Exception {
    // The case the frontend alone cannot guarantee: an edit that switches Type to Service.
    JSONObject body = new JSONObject()
        .put("productType", "S")
        .put("stocked", true)
        .put("returnable", true);

    NeoResponse result = new ProductDefaultsHandler().handle(patchCtx(body));

    assertNull(result);
    assertEquals(false, body.getBoolean("stocked"));
    assertEquals(false, body.getBoolean("returnable"));
  }

  @Test
  public void testHandleForcesStockedAndReturnableFalseOnCreateWhenTypeIsExpense() throws Exception {
    JSONObject body = new JSONObject()
        .put("productType", "E")
        .put("stocked", true)
        .put("returnable", true);

    NeoResponse result = new ProductDefaultsHandler().handle(postCtx(body, mock(OBContext.class)));

    assertNull(result);
    assertEquals(false, body.getBoolean("stocked"));
    assertEquals(false, body.getBoolean("returnable"));
  }

  @Test
  public void testHandleForcesStockedAndReturnableFalseOnPatchWhenTypeIsExpense() throws Exception {
    // The case the frontend alone cannot guarantee: an edit that switches Type to Expense.
    JSONObject body = new JSONObject()
        .put("productType", "E")
        .put("stocked", true)
        .put("returnable", true);

    NeoResponse result = new ProductDefaultsHandler().handle(patchCtx(body));

    assertNull(result);
    assertEquals(false, body.getBoolean("stocked"));
    assertEquals(false, body.getBoolean("returnable"));
  }

  @Test
  public void testHandleForcesStockedAndReturnableFalseOnCreateWhenTypeIsResource() throws Exception {
    JSONObject body = new JSONObject()
        .put("productType", "R")
        .put("stocked", true)
        .put("returnable", true);

    NeoResponse result = new ProductDefaultsHandler().handle(postCtx(body, mock(OBContext.class)));

    assertNull(result);
    assertEquals(false, body.getBoolean("stocked"));
    assertEquals(false, body.getBoolean("returnable"));
  }

  @Test
  public void testHandleForcesStockedAndReturnableFalseOnPatchWhenTypeIsResource() throws Exception {
    // The case the frontend alone cannot guarantee: an edit that switches Type to Resource.
    JSONObject body = new JSONObject()
        .put("productType", "R")
        .put("stocked", true)
        .put("returnable", true);

    NeoResponse result = new ProductDefaultsHandler().handle(patchCtx(body));

    assertNull(result);
    assertEquals(false, body.getBoolean("stocked"));
    assertEquals(false, body.getBoolean("returnable"));
  }

  @Test
  public void testHandleForcesStockedAndReturnableFalseWhenTypeIsExpenseAndFlagsAbsent()
      throws Exception {
    // Flags omitted entirely — must still end up explicitly false, not just "left unset".
    JSONObject body = new JSONObject().put("productType", "E");

    new ProductDefaultsHandler().handle(postCtx(body, mock(OBContext.class)));

    assertEquals(false, body.getBoolean("stocked"));
    assertEquals(false, body.getBoolean("returnable"));
  }

  @Test
  public void testHandleForcesStockedAndReturnableFalseWhenTypeIsResourceAndFlagsAbsent()
      throws Exception {
    // Flags omitted entirely — must still end up explicitly false, not just "left unset".
    JSONObject body = new JSONObject().put("productType", "R");

    new ProductDefaultsHandler().handle(postCtx(body, mock(OBContext.class)));

    assertEquals(false, body.getBoolean("stocked"));
    assertEquals(false, body.getBoolean("returnable"));
  }

  @Test
  public void testHandleLeavesStockedAndReturnableUntouchedForArticleType() throws Exception {
    ProductDefaultsHandler handler = handlerResolving(null, null, null, null);
    JSONObject body = new JSONObject()
        .put("productType", "I")
        .put("stocked", true)
        .put("returnable", true);

    handler.handle(postCtx(body, mock(OBContext.class)));

    assertEquals(true, body.getBoolean("stocked"));
    assertEquals(true, body.getBoolean("returnable"));
  }

  @Test
  public void testHandleLeavesStockedAndReturnableUntouchedWhenPatchDoesNotTouchType()
      throws Exception {
    // A PATCH that edits something unrelated (e.g. weight) and never mentions productType at
    // all must not force these flags off just because the record might already be a Service —
    // resolving the persisted type would need a DB lookup, out of scope for this ticket's
    // reported cases (all of which change productType in the same request).
    JSONObject body = new JSONObject().put("weight", 5);

    new ProductDefaultsHandler().handle(patchCtx(body));

    assertEquals(false, body.has("stocked"));
    assertEquals(false, body.has("returnable"));
  }

  // ── ETP-5245: a brand-new product is seeded with a zero price on each default tariff ─────
  //
  // Runs as a CRUD POST post-hook, so it can read the id the CRUD layer just assigned. Every
  // assertion below is about the same guarantee from a different angle: the seeding must never
  // be able to turn a successful product creation into a failure.

  private static final String PRODUCT_ID = "prod-1";
  private static final String SALES_VERSION_ID = "plv-sales";
  private static final String PURCHASE_VERSION_ID = "plv-purchase";

  /** A CRUD POST context whose previous result is the create response for {@code createdId}. */
  private static NeoContext createdProductCtx(String createdId, OBContext obContext)
      throws JSONException {
    JSONObject row = new JSONObject();
    if (createdId != null) {
      row.put("id", createdId);
    }
    JSONArray data = new JSONArray();
    data.put(row);
    return postAfterCtx(dataResponse(data), obContext);
  }

  private static NeoContext postAfterCtx(JSONObject previousBody, OBContext obContext) {
    return NeoContext.builder()
        .specName("product").entityName("product")
        .httpMethod("POST").endpointType(NeoEndpointType.CRUD)
        .obContext(obContext).previousResult(NeoResponse.ok(previousBody)).build();
  }

  private static JSONObject dataResponse(JSONArray data) throws JSONException {
    JSONObject response = new JSONObject();
    response.put("data", data);
    JSONObject body = new JSONObject();
    body.put("response", response);
    return body;
  }

  /** Everything the seeding path touches, opened together so each test only stubs what it needs. */
  private static final class SeedingMocks implements AutoCloseable {
    private final MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
    private final MockedStatic<OBContext> obContextMock = Mockito.mockStatic(OBContext.class);
    private final MockedStatic<OBProvider> obProviderMock = Mockito.mockStatic(OBProvider.class);
    private final MockedStatic<PriceListVersionResolver> resolverMock =
        Mockito.mockStatic(PriceListVersionResolver.class);
    private final MockedStatic<ProductHandlerUtils> utilsMock =
        Mockito.mockStatic(ProductHandlerUtils.class);

    private final OBDal dal = mock(OBDal.class);
    private final OBProvider provider = mock(OBProvider.class);
    private final Client client = mock(Client.class);
    private final Product product = mock(Product.class);
    private final PriceListVersion salesVersion = mock(PriceListVersion.class);
    private final PriceListVersion purchaseVersion = mock(PriceListVersion.class);

    private SeedingMocks() {
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      obProviderMock.when(OBProvider::getInstance).thenReturn(provider);
      when(product.getId()).thenReturn(PRODUCT_ID);
      when(product.getClient()).thenReturn(client);
      when(dal.get(Product.class, PRODUCT_ID)).thenReturn(product);
      when(salesVersion.getOrganization()).thenReturn(mock(Organization.class));
      when(purchaseVersion.getOrganization()).thenReturn(mock(Organization.class));
      when(dal.get(PriceListVersion.class, SALES_VERSION_ID)).thenReturn(salesVersion);
      when(dal.get(PriceListVersion.class, PURCHASE_VERSION_ID)).thenReturn(purchaseVersion);
    }

    /** Makes the tenant have a default tariff for both directions. */
    private void withBothDefaultTariffs(OBContext obContext) {
      resolverMock.when(() -> PriceListVersionResolver.resolveDefaultVersionId(obContext, true))
          .thenReturn(SALES_VERSION_ID);
      resolverMock.when(() -> PriceListVersionResolver.resolveDefaultVersionId(obContext, false))
          .thenReturn(PURCHASE_VERSION_ID);
    }

    @Override
    public void close() {
      utilsMock.close();
      resolverMock.close();
      obProviderMock.close();
      obContextMock.close();
      obDalMock.close();
    }
  }

  @Test
  public void testAfterHandleSeedsAZeroPriceOnBothDefaultTariffsAfterCreate() throws Exception {
    OBContext obContext = obContextWithClient(CLIENT1);
    try (SeedingMocks mocks = new SeedingMocks()) {
      mocks.withBothDefaultTariffs(obContext);
      mocks.utilsMock.when(() -> ProductHandlerUtils.findExistingPrice(anyString(), anyString()))
          .thenReturn(null);
      ProductPrice salesPrice = mock(ProductPrice.class);
      ProductPrice purchasePrice = mock(ProductPrice.class);
      when(mocks.provider.get(ProductPrice.class)).thenReturn(salesPrice, purchasePrice);

      NeoResponse result = new ProductDefaultsHandler()
          .afterHandle(createdProductCtx(PRODUCT_ID, obContext));

      // The create response is passed through untouched — seeding is a side effect, not a payload.
      assertNull(result);

      verify(salesPrice).setNewOBObject(true);
      verify(salesPrice).setProduct(mocks.product);
      verify(salesPrice).setClient(mocks.client);
      verify(salesPrice).setPriceListVersion(mocks.salesVersion);
      verify(salesPrice).setStandardPrice(BigDecimal.ZERO);
      verify(salesPrice).setListPrice(BigDecimal.ZERO);
      verify(salesPrice).setPriceLimit(BigDecimal.ZERO);
      verify(mocks.dal).save(salesPrice);

      verify(purchasePrice).setNewOBObject(true);
      verify(purchasePrice).setPriceListVersion(mocks.purchaseVersion);
      verify(purchasePrice).setStandardPrice(BigDecimal.ZERO);
      verify(purchasePrice).setListPrice(BigDecimal.ZERO);
      verify(purchasePrice).setPriceLimit(BigDecimal.ZERO);
      verify(mocks.dal).save(purchasePrice);

      verify(mocks.dal).flush();
    }
  }

  /**
   * Idempotency — the guarantee the products import depends on: it posts its own price for the
   * same tariff in the same /batch call, so seeding must skip any tariff the product is already
   * priced on rather than fight the unique (version, product) constraint.
   */
  @Test
  public void testAfterHandleSeedsNothingWhenTheProductIsAlreadyPricedOnBothTariffs()
      throws Exception {
    OBContext obContext = obContextWithClient(CLIENT1);
    try (SeedingMocks mocks = new SeedingMocks()) {
      mocks.withBothDefaultTariffs(obContext);
      mocks.utilsMock.when(() -> ProductHandlerUtils.findExistingPrice(anyString(), anyString()))
          .thenReturn(mock(ProductPrice.class));

      assertNull(new ProductDefaultsHandler().afterHandle(createdProductCtx(PRODUCT_ID, obContext)));

      verify(mocks.dal, never()).save(any());
      // Not even the version is loaded once the product turns out to be priced already.
      verify(mocks.dal, never()).get(PriceListVersion.class, SALES_VERSION_ID);
      verify(mocks.provider, never()).get(ProductPrice.class);
    }
  }

  /**
   * Half-idempotent case: an import that already priced the sales tariff still gets the purchase
   * one seeded, so it is never left invisible to purchasing.
   */
  @Test
  public void testAfterHandleSeedsOnlyTheDirectionThatIsNotPricedYet() throws Exception {
    OBContext obContext = obContextWithClient(CLIENT1);
    try (SeedingMocks mocks = new SeedingMocks()) {
      mocks.withBothDefaultTariffs(obContext);
      mocks.utilsMock.when(
              () -> ProductHandlerUtils.findExistingPrice(PRODUCT_ID, SALES_VERSION_ID))
          .thenReturn(mock(ProductPrice.class));
      mocks.utilsMock.when(
              () -> ProductHandlerUtils.findExistingPrice(PRODUCT_ID, PURCHASE_VERSION_ID))
          .thenReturn(null);
      ProductPrice purchasePrice = mock(ProductPrice.class);
      when(mocks.provider.get(ProductPrice.class)).thenReturn(purchasePrice);

      new ProductDefaultsHandler().afterHandle(createdProductCtx(PRODUCT_ID, obContext));

      verify(purchasePrice).setPriceListVersion(mocks.purchaseVersion);
      verify(mocks.dal, times(1)).save(any());
      verify(mocks.dal).save(purchasePrice);
    }
  }

  /**
   * A tenant with no default tariff configured must still be able to create products.
   */
  @Test
  public void testAfterHandleSeedsNothingWhenTheTenantHasNoDefaultTariff() throws Exception {
    OBContext obContext = obContextWithClient(CLIENT1);
    try (SeedingMocks mocks = new SeedingMocks()) {
      mocks.resolverMock
          .when(() -> PriceListVersionResolver.resolveDefaultVersionId(any(OBContext.class),
              org.mockito.ArgumentMatchers.anyBoolean()))
          .thenReturn(null);

      assertNull(new ProductDefaultsHandler().afterHandle(createdProductCtx(PRODUCT_ID, obContext)));

      verify(mocks.dal, never()).save(any());
      verify(mocks.provider, never()).get(ProductPrice.class);
    }
  }

  /**
   * Best-effort contract — the load-bearing guarantee of this whole post-hook: a database failure
   * while seeding is logged and swallowed, so the product the user just created still stands. The
   * CRUD create has already committed by the time {@code afterHandle} runs, so an exception
   * escaping here would report a 500 for a product that exists.
   *
   * <p>The create post-hook now touches the database twice (seed the prices, then read the cost
   * presence for {@code etgoHasCost}), so the assertions below pin the guarantee in its new shape:
   * the exception still never reaches the caller, and admin mode is still balanced — each section
   * that enters it restores it, so a failure cannot leak elevated privileges into the rest of the
   * request. The second section is unaffected by the first one's failure and still answers, which
   * is why the response now comes back annotated instead of null.
   */
  @Test
  public void testAfterHandleDoesNotFailTheCreateWhenTheDatabaseThrows() throws Exception {
    OBContext obContext = obContextWithClient(CLIENT1);
    try (SeedingMocks mocks = new SeedingMocks()) {
      when(mocks.dal.get(Product.class, PRODUCT_ID))
          .thenThrow(new IllegalStateException("session closed"));
      stubCostingCriteria(mocks.dal, Collections.emptyList());

      NeoResponse result = new ProductDefaultsHandler()
          .afterHandle(createdProductCtx(PRODUCT_ID, obContext));

      // No exception escaped, and the product is still there to be annotated.
      assertNotNull(result);
      assertFalse(result.getBody().getJSONObject("response").getJSONArray("data")
          .getJSONObject(0).getBoolean("etgoHasCost"));
      // Balanced: two admin-mode sections, two restores — including the one whose body threw.
      mocks.obContextMock.verify(OBContext::setAdminMode, times(2));
      mocks.obContextMock.verify(OBContext::restorePreviousMode, times(2));
    }
  }

  /**
   * The same best-effort contract for the other half of the create post-hook: the cost lookup is
   * what fails, and the create still stands.
   *
   * <p>Degrading to "no answer" is the correct failure mode here rather than an error: the
   * frontend reads an ABSENT {@code etgoHasCost} as "cannot tell" and deliberately never blocks on
   * it, so a cost lookup that cannot run costs the user a warning banner — not the product they
   * just created.
   */
  @Test
  public void testAfterHandleDoesNotFailTheCreateWhenTheCostLookupThrows() throws Exception {
    OBContext obContext = obContextWithClient(CLIENT1);
    try (SeedingMocks mocks = new SeedingMocks()) {
      mocks.withBothDefaultTariffs(obContext);
      mocks.utilsMock.when(() -> ProductHandlerUtils.findExistingPrice(anyString(), anyString()))
          .thenReturn(mock(ProductPrice.class));
      when(mocks.dal.createCriteria(Costing.class))
          .thenThrow(new IllegalStateException("session closed"));

      // No annotation could be produced, so the generic create response passes through unchanged.
      assertNull(new ProductDefaultsHandler().afterHandle(createdProductCtx(PRODUCT_ID, obContext)));
    }
  }

  /**
   * Same contract for a failure on the write side rather than the read side.
   */
  @Test
  public void testAfterHandleDoesNotFailTheCreateWhenSavingThePriceThrows() throws Exception {
    OBContext obContext = obContextWithClient(CLIENT1);
    try (SeedingMocks mocks = new SeedingMocks()) {
      mocks.withBothDefaultTariffs(obContext);
      mocks.utilsMock.when(() -> ProductHandlerUtils.findExistingPrice(anyString(), anyString()))
          .thenReturn(null);
      when(mocks.provider.get(ProductPrice.class)).thenReturn(mock(ProductPrice.class));
      Mockito.doThrow(new IllegalStateException("constraint violation"))
          .when(mocks.dal).save(any());

      assertNull(new ProductDefaultsHandler().afterHandle(createdProductCtx(PRODUCT_ID, obContext)));
    }
  }

  /**
   * A create response that carries no record id (nothing to attach a price to) is a no-op for the
   * seeding — the resolver is not even consulted. That is the guarantee this test exists for and
   * it is asserted unchanged below.
   *
   * <p>What did change is the return value: the cost annotation now runs on create responses too,
   * so a create whose response still has a data envelope comes back as that envelope rather than
   * as {@code null}. It is a harmless pass-through here — there is nothing to annotate (empty
   * array) or nothing to look the annotation up by (a record with no id, flagged {@code false}
   * without touching the Costing table).
   */
  @Test
  public void testAfterHandleSeedsNothingWhenTheCreateResponseCarriesNoRecordId() throws Exception {
    OBContext obContext = obContextWithClient(CLIENT1);
    try (SeedingMocks mocks = new SeedingMocks()) {
      ProductDefaultsHandler handler = new ProductDefaultsHandler();

      // Empty data array: nothing to seed, nothing to annotate.
      NeoResponse empty = handler.afterHandle(postAfterCtx(dataResponse(new JSONArray()), obContext));
      assertNotNull(empty);
      assertEquals(0, empty.getBody().getJSONObject("response").getJSONArray("data").length());

      // A record with no id: nothing to seed, and annotated false without a query.
      NeoResponse idless = handler.afterHandle(createdProductCtx(null, obContext));
      assertNotNull(idless);
      assertFalse(idless.getBody().getJSONObject("response").getJSONArray("data")
          .getJSONObject(0).getBoolean("etgoHasCost"));

      // No data envelope at all: the generic response passes through untouched.
      assertNull(handler.afterHandle(postAfterCtx(new JSONObject(), obContext)));

      // Unchanged and non-negotiable: no id -> no price seeded, resolver never consulted.
      mocks.resolverMock.verifyNoInteractions();
      verify(mocks.dal, never()).save(any());
      verify(mocks.dal, never()).createCriteria(Costing.class);
    }
  }

  /**
   * Defensive: the id came back but the row is not readable (deleted/rolled back) — no price is
   * attached to nothing.
   */
  @Test
  public void testAfterHandleSeedsNothingWhenTheProductCannotBeLoaded() throws Exception {
    OBContext obContext = obContextWithClient(CLIENT1);
    try (SeedingMocks mocks = new SeedingMocks()) {
      when(mocks.dal.get(Product.class, PRODUCT_ID)).thenReturn(null);

      assertNull(new ProductDefaultsHandler().afterHandle(createdProductCtx(PRODUCT_ID, obContext)));

      verify(mocks.dal, never()).save(any());
      mocks.resolverMock.verifyNoInteractions();
    }
  }

  /**
   * Defensive: the resolver named a version that no longer exists — skip rather than persist a
   * price with a null tariff.
   */
  @Test
  public void testAfterHandleSeedsNothingWhenTheResolvedVersionCannotBeLoaded() throws Exception {
    OBContext obContext = obContextWithClient(CLIENT1);
    try (SeedingMocks mocks = new SeedingMocks()) {
      mocks.withBothDefaultTariffs(obContext);
      mocks.utilsMock.when(() -> ProductHandlerUtils.findExistingPrice(anyString(), anyString()))
          .thenReturn(null);
      when(mocks.dal.get(PriceListVersion.class, SALES_VERSION_ID)).thenReturn(null);
      when(mocks.dal.get(PriceListVersion.class, PURCHASE_VERSION_ID)).thenReturn(null);

      assertNull(new ProductDefaultsHandler().afterHandle(createdProductCtx(PRODUCT_ID, obContext)));

      verify(mocks.dal, never()).save(any());
      verify(mocks.provider, never()).get(ProductPrice.class);
    }
  }

  // ── ETP-4967 non-regression: the GET branch of afterHandle still filters ─────────────────
  //
  // afterHandle's CRUD branch now forks on the HTTP method (POST → seed prices, anything else →
  // hide system-category products). These tests pin the "anything else" half so the fork cannot
  // silently swallow the ETP-4967 filtering.

  private static NeoContext crudAfterCtx(String method, JSONObject previousBody,
      OBContext obContext) {
    return NeoContext.builder()
        .specName("product").entityName("product")
        .httpMethod(method).endpointType(NeoEndpointType.CRUD)
        .obContext(obContext).previousResult(NeoResponse.ok(previousBody)).build();
  }

  private static JSONObject productListBody(String... categoryIds) throws JSONException {
    JSONArray data = new JSONArray();
    for (int i = 0; i < categoryIds.length; i++) {
      JSONObject row = new JSONObject();
      row.put("id", "product-" + i);
      row.put("productCategory", categoryIds[i]);
      data.put(row);
    }
    JSONObject body = dataResponse(data);
    JSONObject response = body.getJSONObject("response");
    response.put("totalRows", categoryIds.length);
    response.put("endRow", categoryIds.length);
    return body;
  }

  @Test
  public void testAfterHandleStillHidesSystemCategoryProductsOnGet() throws Exception {
    try (MockedStatic<SystemCategoryIds> categoryMock =
        Mockito.mockStatic(SystemCategoryIds.class)) {
      categoryMock.when(() -> SystemCategoryIds.resolve(CLIENT1)).thenReturn(Set.of("cat-system"));

      NeoResponse result = new ProductDefaultsHandler().afterHandle(crudAfterCtx("GET",
          productListBody("cat-normal", "cat-system", "cat-other"), obContextWithClient(CLIENT1)));

      assertNotNull(result);
      JSONObject response = result.getBody().getJSONObject("response");
      assertEquals(2, response.getJSONArray("data").length());
      assertEquals("product-0", response.getJSONArray("data").getJSONObject(0).getString("id"));
      assertEquals("product-2", response.getJSONArray("data").getJSONObject(1).getString("id"));
      // Row counts come from core's count query, computed before the filter ran.
      assertEquals(2, response.getInt("totalRows"));
      assertEquals(2, response.getInt("endRow"));
    }
  }

  @Test
  public void testAfterHandlePassesTheGetResponseThroughWhenNothingIsHidden() throws Exception {
    try (MockedStatic<SystemCategoryIds> categoryMock =
        Mockito.mockStatic(SystemCategoryIds.class)) {
      categoryMock.when(() -> SystemCategoryIds.resolve(CLIENT1)).thenReturn(Set.of());

      assertNull(new ProductDefaultsHandler().afterHandle(crudAfterCtx("GET",
          productListBody("cat-normal", "cat-other"), obContextWithClient(CLIENT1))));
    }
  }

  /**
   * The fork's other half: a GET must never run the POST-only seeding.
   */
  @Test
  public void testAfterHandleDoesNotSeedPricesOnGet() throws Exception {
    try (MockedStatic<SystemCategoryIds> categoryMock =
            Mockito.mockStatic(SystemCategoryIds.class);
        MockedStatic<PriceListVersionResolver> resolverMock =
            Mockito.mockStatic(PriceListVersionResolver.class)) {
      categoryMock.when(() -> SystemCategoryIds.resolve(CLIENT1)).thenReturn(Set.of("cat-system"));

      new ProductDefaultsHandler().afterHandle(crudAfterCtx("GET",
          productListBody("cat-normal", "cat-system"), obContextWithClient(CLIENT1)));

      resolverMock.verifyNoInteractions();
    }
  }

  /**
   * A PATCH response is neither filtered (the filter is GET-only) nor seeded (seeding is
   * POST-only) — unchanged from before the fork. Only the cost annotation applies to it, which is
   * pinned separately by {@code testAfterHandleAnnotatesAPatchResponse}; it is stubbed to succeed
   * here so this test measures the seeding and the filtering, not a failed lookup.
   */
  @Test
  public void testAfterHandleLeavesPatchResponsesAloneAndSeedsNothing() throws Exception {
    try (MockedStatic<PriceListVersionResolver> resolverMock =
            Mockito.mockStatic(PriceListVersionResolver.class);
        MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class);
        MockedStatic<OBContext> ctxMock = Mockito.mockStatic(OBContext.class);
        MockedStatic<SystemCategoryIds> categoryMock =
            Mockito.mockStatic(SystemCategoryIds.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      stubCostingCriteria(dal, Collections.emptyList());

      NeoResponse result = new ProductDefaultsHandler().afterHandle(crudAfterCtx("PATCH",
          productListBody("cat-normal"), obContextWithClient(CLIENT1)));

      // Not seeded...
      resolverMock.verifyNoInteractions();
      // ...and not filtered: the category filter never even resolves the hidden ids on a write.
      categoryMock.verifyNoInteractions();
      assertEquals(1, result.getBody().getJSONObject("response").getJSONArray("data").length());
    }
  }

  // ── ETP-5245: etgoHasCost annotation on every single-record response ─────────────────────
  //
  // A stockable product with no M_Costing row is a blocking condition in the Product window: the
  // banner and the save gate both read `etgoHasCost` off the record, so the backend has to state
  // it once rather than making the frontend fetch the Costing tab. It is emitted, never declared
  // in decisions.json.
  //
  // Scope: every response that carries a single record — read, create and update alike, since all
  // three hand the form the record it goes on displaying. The LIST GET is the one exclusion, where
  // this would be one COUNT per row and no list view needs it.

  private static NeoContext singleRecordGetCtx(String recordId, JSONObject previousBody,
      OBContext obContext) {
    return NeoContext.builder()
        .specName("product").entityName("product")
        .httpMethod("GET").endpointType(NeoEndpointType.CRUD)
        .recordId(recordId)
        .obContext(obContext).previousResult(NeoResponse.ok(previousBody)).build();
  }

  /** A single-record GET envelope for one product, with no productCategory to hide it by. */
  private static JSONObject singleProductBody(String productId) throws JSONException {
    JSONArray data = new JSONArray();
    data.put(new JSONObject().put("id", productId));
    return dataResponse(data);
  }

  /** Stubs the Costing lookup {@code hasCostDefined} performs; {@code rows} is what it finds. */
  @SuppressWarnings("unchecked")
  private static OBCriteria<Costing> stubCostingCriteria(OBDal dal, List<Costing> rows) {
    OBCriteria<Costing> crit = mock(OBCriteria.class);
    when(dal.createCriteria(Costing.class)).thenReturn(crit);
    when(crit.add(any(Criterion.class))).thenReturn(crit);
    when(crit.setMaxResults(anyInt())).thenReturn(crit);
    when(crit.list()).thenReturn(rows);
    return crit;
  }

  @Test
  public void testAfterHandleAnnotatesHasCostTrueWhenTheProductIsCosted() throws Exception {
    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class);
        MockedStatic<OBContext> ctxMock = Mockito.mockStatic(OBContext.class);
        MockedStatic<SystemCategoryIds> categoryMock =
            Mockito.mockStatic(SystemCategoryIds.class)) {
      categoryMock.when(() -> SystemCategoryIds.resolve(CLIENT1)).thenReturn(Set.of());
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      stubCostingCriteria(dal, Collections.singletonList(mock(Costing.class)));

      NeoResponse result = new ProductDefaultsHandler().afterHandle(
          singleRecordGetCtx(PRODUCT_ID, singleProductBody(PRODUCT_ID),
              obContextWithClient(CLIENT1)));

      assertNotNull(result);
      JSONObject row = result.getBody().getJSONObject("response")
          .getJSONArray("data").getJSONObject(0);
      assertTrue(row.getBoolean("etgoHasCost"));
    }
  }

  @Test
  public void testAfterHandleAnnotatesHasCostFalseWhenTheProductHasNoCostRow() throws Exception {
    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class);
        MockedStatic<OBContext> ctxMock = Mockito.mockStatic(OBContext.class);
        MockedStatic<SystemCategoryIds> categoryMock =
            Mockito.mockStatic(SystemCategoryIds.class)) {
      categoryMock.when(() -> SystemCategoryIds.resolve(CLIENT1)).thenReturn(Set.of());
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      stubCostingCriteria(dal, Collections.emptyList());

      NeoResponse result = new ProductDefaultsHandler().afterHandle(
          singleRecordGetCtx(PRODUCT_ID, singleProductBody(PRODUCT_ID),
              obContextWithClient(CLIENT1)));

      assertNotNull(result);
      JSONObject row = result.getBody().getJSONObject("response")
          .getJSONArray("data").getJSONObject(0);
      // Present and false — an ABSENT flag is what the frontend reads as "cannot tell", and it
      // deliberately never blocks. Emitting it is what makes the block possible at all.
      assertTrue(row.has("etgoHasCost"));
      assertFalse(row.getBoolean("etgoHasCost"));
    }
  }

  /**
   * The list GET is left exactly as it was: no annotation, and above all no COUNT per row.
   */
  @Test
  public void testAfterHandleDoesNotAnnotateTheListGet() throws Exception {
    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class);
        MockedStatic<OBContext> ctxMock = Mockito.mockStatic(OBContext.class);
        MockedStatic<SystemCategoryIds> categoryMock =
            Mockito.mockStatic(SystemCategoryIds.class)) {
      categoryMock.when(() -> SystemCategoryIds.resolve(CLIENT1)).thenReturn(Set.of("cat-system"));
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);

      NeoResponse result = new ProductDefaultsHandler().afterHandle(crudAfterCtx("GET",
          productListBody("cat-normal", "cat-system"), obContextWithClient(CLIENT1)));

      // The ETP-4967 filter still ran...
      assertNotNull(result);
      JSONArray data = result.getBody().getJSONObject("response").getJSONArray("data");
      assertEquals(1, data.length());
      // ...but nothing was annotated and the Costing table was never queried.
      assertFalse(data.getJSONObject(0).has("etgoHasCost"));
      verify(dal, never()).createCriteria(Costing.class);
    }
  }

  /**
   * The two afterHandle GET concerns compose: the filter decides which rows survive, the
   * annotation enriches whatever is left. A single-record GET of a hidden product must come back
   * empty — the annotation must not resurrect the row the filter just removed.
   */
  @Test
  public void testAfterHandleKeepsHidingSystemCategoryProductsOnTheSingleRecordGet()
      throws Exception {
    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class);
        MockedStatic<OBContext> ctxMock = Mockito.mockStatic(OBContext.class);
        MockedStatic<SystemCategoryIds> categoryMock =
            Mockito.mockStatic(SystemCategoryIds.class)) {
      categoryMock.when(() -> SystemCategoryIds.resolve(CLIENT1)).thenReturn(Set.of("cat-system"));
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      stubCostingCriteria(dal, Collections.emptyList());

      JSONArray data = new JSONArray();
      data.put(new JSONObject().put("id", PRODUCT_ID).put("productCategory", "cat-system"));
      NeoResponse result = new ProductDefaultsHandler().afterHandle(
          singleRecordGetCtx(PRODUCT_ID, dataResponse(data), obContextWithClient(CLIENT1)));

      assertNotNull(result);
      assertEquals(0, result.getBody().getJSONObject("response").getJSONArray("data").length());
    }
  }

  /**
   * A surviving row on a single-record GET that the filter DID touch (a two-row payload where one
   * is hidden) is still annotated — the annotation reads the filtered response, not the raw one.
   */
  @Test
  public void testAfterHandleAnnotatesTheRowsThatSurvivedTheCategoryFilter() throws Exception {
    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class);
        MockedStatic<OBContext> ctxMock = Mockito.mockStatic(OBContext.class);
        MockedStatic<SystemCategoryIds> categoryMock =
            Mockito.mockStatic(SystemCategoryIds.class)) {
      categoryMock.when(() -> SystemCategoryIds.resolve(CLIENT1)).thenReturn(Set.of("cat-system"));
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      stubCostingCriteria(dal, Collections.singletonList(mock(Costing.class)));

      JSONArray data = new JSONArray();
      data.put(new JSONObject().put("id", "hidden").put("productCategory", "cat-system"));
      data.put(new JSONObject().put("id", PRODUCT_ID).put("productCategory", "cat-normal"));
      NeoResponse result = new ProductDefaultsHandler().afterHandle(
          singleRecordGetCtx(PRODUCT_ID, dataResponse(data), obContextWithClient(CLIENT1)));

      assertNotNull(result);
      JSONArray survivors = result.getBody().getJSONObject("response").getJSONArray("data");
      assertEquals(1, survivors.length());
      assertEquals(PRODUCT_ID, survivors.getJSONObject(0).getString("id"));
      assertTrue(survivors.getJSONObject(0).getBoolean("etgoHasCost"));
    }
  }

  /**
   * WIDENED SCOPE — the assertion of this test is deliberately inverted: an update response IS
   * annotated now. It used to assert the opposite ("PATCH is not a GET, so nothing is annotated").
   *
   * <p>The original rule was "annotate only the single-record GET", to keep a Costing count off
   * the save path. That was the wrong trade: after an edit the form keeps showing the very record
   * the PATCH returned, so with no flag on that response the banner and the save gate went on
   * using the answer from whenever the record was last read — stale exactly when the edit itself
   * was what created or removed the cost. One count on one record is the price of the flag never
   * being out of date. The list GET, the only case where this would cost one count per row, is
   * still excluded.
   */
  @Test
  public void testAfterHandleAnnotatesAPatchResponse() throws Exception {
    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class);
        MockedStatic<OBContext> ctxMock = Mockito.mockStatic(OBContext.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      stubCostingCriteria(dal, Collections.singletonList(mock(Costing.class)));

      NeoContext ctx = NeoContext.builder()
          .specName("product").entityName("product")
          .httpMethod("PATCH").endpointType(NeoEndpointType.CRUD)
          .recordId(PRODUCT_ID)
          .obContext(obContextWithClient(CLIENT1))
          .previousResult(NeoResponse.ok(singleProductBody(PRODUCT_ID))).build();

      NeoResponse result = new ProductDefaultsHandler().afterHandle(ctx);

      assertNotNull(result);
      assertTrue(result.getBody().getJSONObject("response").getJSONArray("data")
          .getJSONObject(0).getBoolean("etgoHasCost"));
      // One count for the one record being saved — never more.
      verify(dal, times(1)).createCriteria(Costing.class);
    }
  }

  /**
   * The regression the fix is actually about: the CREATE response carries the flag too.
   *
   * <p>A product created without a cost used to come back with no {@code etgoHasCost} at all,
   * because the create branch returned before the annotation ran. An absent flag is deliberately
   * read by the frontend as "has a cost" — a backend that never emits it must never be able to
   * block a save — so the banner stayed hidden and the save gate stayed open until the user
   * reloaded the page. That is exactly the reported symptom: create a product with no cost, get no
   * warning.
   */
  @Test
  public void testAfterHandleAnnotatesTheCreateResponseOfAnUncostedProduct() throws Exception {
    OBContext obContext = obContextWithClient(CLIENT1);
    try (SeedingMocks mocks = new SeedingMocks()) {
      mocks.withBothDefaultTariffs(obContext);
      mocks.utilsMock.when(() -> ProductHandlerUtils.findExistingPrice(anyString(), anyString()))
          .thenReturn(mock(ProductPrice.class));
      stubCostingCriteria(mocks.dal, Collections.emptyList());

      NeoResponse result = new ProductDefaultsHandler()
          .afterHandle(createdProductCtx(PRODUCT_ID, obContext));

      assertNotNull(result);
      JSONObject row = result.getBody().getJSONObject("response")
          .getJSONArray("data").getJSONObject(0);
      // Present and false — the banner can appear on the record the user just created, with no
      // page reload.
      assertTrue(row.has("etgoHasCost"));
      assertFalse(row.getBoolean("etgoHasCost"));
    }
  }

  /**
   * The other side of the create case: a product that arrives already costed (e.g. the products
   * import, which posts its cost in the same batch) is annotated {@code true}, so the banner does
   * not flash on a product that is perfectly fine.
   */
  @Test
  public void testAfterHandleAnnotatesTheCreateResponseOfACostedProduct() throws Exception {
    OBContext obContext = obContextWithClient(CLIENT1);
    try (SeedingMocks mocks = new SeedingMocks()) {
      mocks.withBothDefaultTariffs(obContext);
      mocks.utilsMock.when(() -> ProductHandlerUtils.findExistingPrice(anyString(), anyString()))
          .thenReturn(mock(ProductPrice.class));
      stubCostingCriteria(mocks.dal, Collections.singletonList(mock(Costing.class)));

      NeoResponse result = new ProductDefaultsHandler()
          .afterHandle(createdProductCtx(PRODUCT_ID, obContext));

      assertNotNull(result);
      assertTrue(result.getBody().getJSONObject("response").getJSONArray("data")
          .getJSONObject(0).getBoolean("etgoHasCost"));
    }
  }

  /**
   * A record with no id cannot be looked up, so it is annotated {@code false} without a query
   * rather than blowing up.
   */
  @Test
  public void testAfterHandleAnnotatesFalseWithoutQueryingForAnIdlessRecord() throws Exception {
    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class);
        MockedStatic<OBContext> ctxMock = Mockito.mockStatic(OBContext.class);
        MockedStatic<SystemCategoryIds> categoryMock =
            Mockito.mockStatic(SystemCategoryIds.class)) {
      categoryMock.when(() -> SystemCategoryIds.resolve(CLIENT1)).thenReturn(Set.of());
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);

      JSONArray data = new JSONArray();
      data.put(new JSONObject().put("name", "no id here"));
      NeoResponse result = new ProductDefaultsHandler().afterHandle(
          singleRecordGetCtx(PRODUCT_ID, dataResponse(data), obContextWithClient(CLIENT1)));

      assertNotNull(result);
      assertFalse(result.getBody().getJSONObject("response").getJSONArray("data")
          .getJSONObject(0).getBoolean("etgoHasCost"));
      verify(dal, never()).createCriteria(Costing.class);
    }
  }

  @Test
  public void testEnforceNonStockableProductTypesDoesNotThrowWhenProductTypeAbsent()
      throws Exception {
    // Regression guard: Set.of(...).contains(null) throws NPE. A request that never mentions
    // productType at all (the common case: editing weight, price, etc. on an already-existing
    // product) must be a silent no-op, not a caught-and-logged exception on every such request.
    JSONObject body = new JSONObject().put("weight", 5);

    ProductDefaultsHandler.enforceNonStockableProductTypes(body);

    assertEquals(false, body.has("stocked"));
    assertEquals(false, body.has("returnable"));
  }
}
