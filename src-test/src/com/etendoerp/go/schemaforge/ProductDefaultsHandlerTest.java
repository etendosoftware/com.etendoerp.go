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
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.criterion.Criterion;
import org.junit.After;
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
 *
 * @covers com.etendoerp.go.schemaforge.ProductDefaultsHandler
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
   * ETP-5676: {@code _skipPricesSeed} is read and stripped by the pre-hook — so it never reaches
   * the generic write path — and the post-hook of the same operation then seeds nothing, because
   * the caller writes the product's prices itself in the same request.
   */
  @Test
  public void testSkipPricesSeedDirectiveIsStrippedAndSuppressesTheSeed() throws Exception {
    OBContext obContext = obContextWithClient(CLIENT1);
    try (SeedingMocks mocks = new SeedingMocks()) {
      mocks.withBothDefaultTariffs(obContext);
      mocks.utilsMock.when(() -> ProductHandlerUtils.findExistingPrice(anyString(), anyString()))
          .thenReturn(null);
      JSONObject body = new JSONObject();
      body.put("name", "PERF5676-1");
      body.put(ProductDefaultsHandler.DIRECTIVE_SKIP_PRICES_SEED, true);
      NeoContext ctx = postCtx(body, obContext);
      ProductDefaultsHandler handler = new ProductDefaultsHandler();

      assertNull(handler.handle(ctx));
      assertFalse(body.has(ProductDefaultsHandler.DIRECTIVE_SKIP_PRICES_SEED));

      JSONObject row = new JSONObject();
      row.put("id", PRODUCT_ID);
      ctx.setPreviousResult(NeoResponse.ok(dataResponse(new JSONArray().put(row))));
      handler.afterHandle(ctx);

      verify(mocks.provider, never()).get(ProductPrice.class);
      verify(mocks.dal, never()).save(any());
    }
  }

  /**
   * Review W2: the list form skips a direction only when the version the seed would use is one the
   * request prices itself. Here the caller priced the server's sales default and some other
   * purchase list: sales is not seeded, the server's purchase default still is — the case where
   * the client's tariff choice and PriceListVersionResolver's disagree.
   */
  @Test
  public void testSkipPricesSeedListSkipsOnlyTheListedDefaultVersions() throws Exception {
    OBContext obContext = obContextWithClient(CLIENT1);
    try (SeedingMocks mocks = new SeedingMocks()) {
      mocks.withBothDefaultTariffs(obContext);
      mocks.utilsMock.when(() -> ProductHandlerUtils.findExistingPrice(anyString(), anyString()))
          .thenReturn(null);
      ProductPrice purchasePrice = mock(ProductPrice.class);
      when(mocks.provider.get(ProductPrice.class)).thenReturn(purchasePrice);
      JSONObject body = new JSONObject().put("name", "PERF5676-2")
          .put(ProductDefaultsHandler.DIRECTIVE_SKIP_PRICES_SEED,
              new JSONArray().put(SALES_VERSION_ID).put("plv-some-other-purchase-list"));
      NeoContext ctx = postCtx(body, obContext);
      ProductDefaultsHandler handler = new ProductDefaultsHandler();

      handler.handle(ctx);
      assertFalse(body.has(ProductDefaultsHandler.DIRECTIVE_SKIP_PRICES_SEED));
      ctx.setPreviousResult(NeoResponse.ok(createdRow()));
      handler.afterHandle(ctx);

      verify(mocks.provider, times(1)).get(ProductPrice.class);
      verify(purchasePrice).setPriceListVersion(mocks.purchaseVersion);
      verify(mocks.dal, times(1)).save(any());
    }
  }

  /**
   * Runs one create carrying {@code directive} as {@code _skipPricesSeed} (none when
   * {@code null}) through both hooks, against a tenant with both default tariffs and no price yet.
   *
   * @return the versions the seed wrote a zero-priced row on
   */
  private static Set<PriceListVersion> seededVersionsFor(Object directive) throws Exception {
    OBContext obContext = obContextWithClient(CLIENT1);
    try (SeedingMocks mocks = new SeedingMocks()) {
      mocks.withBothDefaultTariffs(obContext);
      mocks.utilsMock.when(() -> ProductHandlerUtils.findExistingPrice(anyString(), anyString()))
          .thenReturn(null);
      Set<PriceListVersion> seeded = new java.util.HashSet<>();
      when(mocks.provider.get(ProductPrice.class)).thenAnswer(invocation -> {
        ProductPrice price = mock(ProductPrice.class);
        org.mockito.Mockito.doAnswer(set -> seeded.add(set.getArgument(0)))
            .when(price).setPriceListVersion(any());
        return price;
      });
      JSONObject body = new JSONObject().put("name", "P");
      if (directive != null) {
        body.put(ProductDefaultsHandler.DIRECTIVE_SKIP_PRICES_SEED, directive);
      }
      NeoContext ctx = postCtx(body, obContext);
      ProductDefaultsHandler handler = new ProductDefaultsHandler();

      handler.handle(ctx);
      assertFalse("the directive never reaches the write path, whatever its form",
          body.has(ProductDefaultsHandler.DIRECTIVE_SKIP_PRICES_SEED));
      ctx.setPreviousResult(NeoResponse.ok(createdRow()));
      handler.afterHandle(ctx);

      Set<PriceListVersion> named = new java.util.HashSet<>();
      for (PriceListVersion version : seeded) {
        named.add(version == mocks.salesVersion ? SALES : version == mocks.purchaseVersion
            ? PURCHASE : version);
      }
      return named;
    }
  }

  /** Stand-ins for "the sales / purchase default version" in {@link #seededVersionsFor}. */
  private static final PriceListVersion SALES = mock(PriceListVersion.class);
  private static final PriceListVersion PURCHASE = mock(PriceListVersion.class);

  /**
   * ETP-5676 list form, edge cases: only a listed default version is skipped. An empty list, a
   * list of versions that are not the server's defaults, and a {@code "*"} inside the list (the
   * internal skip-all marker, never a caller's id) all leave both defaults seeded; listing both
   * skips both.
   */
  @Test
  public void testSkipPricesSeedListEdgeCases() throws Exception {
    assertEquals("empty list", Set.of(SALES, PURCHASE), seededVersionsFor(new JSONArray()));
    assertEquals("unknown ids", Set.of(SALES, PURCHASE),
        seededVersionsFor(new JSONArray().put("plv-unknown").put("plv-other")));
    assertEquals("the skip-all marker inside a list is not honoured", Set.of(SALES, PURCHASE),
        seededVersionsFor(new JSONArray().put(ProductDefaultsHandler.SKIP_ALL_TARIFFS)));
    assertEquals("only purchase listed", Set.of(SALES),
        seededVersionsFor(new JSONArray().put(PURCHASE_VERSION_ID)));
    assertEquals("both listed", Set.of(),
        seededVersionsFor(new JSONArray().put(SALES_VERSION_ID).put(PURCHASE_VERSION_ID)));
  }

  /** {@code true} (boolean or string) still skips both; any other type is ignored and seeds both. */
  @Test
  public void testSkipPricesSeedScalarForms() throws Exception {
    assertEquals(Set.of(), seededVersionsFor(Boolean.TRUE));
    assertEquals(Set.of(), seededVersionsFor("true"));
    assertEquals("no directive", Set.of(SALES, PURCHASE), seededVersionsFor(null));
    assertEquals(Set.of(SALES, PURCHASE), seededVersionsFor(Boolean.FALSE));
    assertEquals(Set.of(SALES, PURCHASE), seededVersionsFor("yes"));
    assertEquals(Set.of(SALES, PURCHASE), seededVersionsFor(1));
    assertEquals(Set.of(SALES, PURCHASE), seededVersionsFor(SALES_VERSION_ID));
    assertEquals(Set.of(SALES, PURCHASE),
        seededVersionsFor(new JSONObject().put("ids", new JSONArray().put(SALES_VERSION_ID))));
  }

  /** The parsed list drops blank entries and trims ids. */
  @Test
  public void testConsumeSkipPricesSeedNormalisesTheList() throws Exception {
    JSONObject body = new JSONObject().put(ProductDefaultsHandler.DIRECTIVE_SKIP_PRICES_SEED,
        new JSONArray().put(" plv-1 ").put("").put("  ").put("plv-2"));

    assertEquals(Set.of("plv-1", "plv-2"), ProductDefaultsHandler.consumeSkipPricesSeed(body));
    assertFalse(body.has(ProductDefaultsHandler.DIRECTIVE_SKIP_PRICES_SEED));
  }

  /** The list form is stripped on PATCH too. */
  @Test
  public void testSkipPricesSeedListIsStrippedOnPatch() throws Exception {
    JSONObject body = new JSONObject().put(ProductDefaultsHandler.DIRECTIVE_SKIP_PRICES_SEED,
        new JSONArray().put(SALES_VERSION_ID));

    assertNull(new ProductDefaultsHandler().handle(patchCtx(body)));

    assertEquals(0, body.length());
  }

  /** A batch scope left open by a failed assertion must not leak into the next test. */
  @After
  public void clearLeakedBatchScope() {
    while (NeoBatchScope.current() != null) {
      NeoBatchScope.close(null);
    }
  }

  /** A product create as each channel builds it; the customization is the same on all of them. */
  private static NeoContext createOn(String channel, JSONObject body, OBContext obContext) {
    NeoContext.Builder builder = NeoContext.builder()
        .specName("product").entityName("product")
        .httpMethod("POST").endpointType(NeoEndpointType.CRUD)
        .requestBody(body).obContext(obContext);
    switch (channel) {
      case "batch":
        builder.idsOnlyResponse(true);
        break;
      case "mcp":
        builder.mcpOrigin(true);
        break;
      default:
        break;
    }
    return builder.build();
  }

  private static JSONObject createdRow() throws JSONException {
    return dataResponse(new JSONArray().put(new JSONObject().put("id", PRODUCT_ID)));
  }

  /**
   * ETP-5676 parity: REST single create, a batch operation (here with {@code _onlyReturnIds}) and
   * an MCP create all reach this same customization, and on each the directive is stripped before
   * the generic write path and the seed is skipped.
   */
  @Test
  public void testSkipPricesSeedIsHonouredOnEveryChannel() throws Exception {
    for (String channel : new String[] { "rest", "batch", "mcp" }) {
      OBContext obContext = obContextWithClient(CLIENT1);
      try (SeedingMocks mocks = new SeedingMocks()) {
        mocks.withBothDefaultTariffs(obContext);
        JSONObject body = new JSONObject().put("name", "P")
            .put(ProductDefaultsHandler.DIRECTIVE_SKIP_PRICES_SEED, true);
        NeoContext ctx = createOn(channel, body, obContext);
        ProductDefaultsHandler handler = new ProductDefaultsHandler();

        assertNull(channel, handler.handle(ctx));
        assertFalse(channel, body.has(ProductDefaultsHandler.DIRECTIVE_SKIP_PRICES_SEED));
        ctx.setPreviousResult(NeoResponse.ok(createdRow()));
        handler.afterHandle(ctx);

        mocks.resolverMock.verify(
            () -> PriceListVersionResolver.resolveDefaultVersionId(any(), anyBoolean()), never());
        verify(mocks.dal, never()).save(any());
      }
    }
  }

  /** An explicit {@code false} is still stripped, and the product gets its seeded prices. */
  @Test
  public void testSkipPricesSeedFalseIsStrippedAndTheSeedRuns() throws Exception {
    OBContext obContext = obContextWithClient(CLIENT1);
    try (SeedingMocks mocks = new SeedingMocks()) {
      mocks.withBothDefaultTariffs(obContext);
      mocks.utilsMock.when(() -> ProductHandlerUtils.findExistingPrice(anyString(), anyString()))
          .thenReturn(null);
      ProductPrice salesPrice = mock(ProductPrice.class);
      ProductPrice purchasePrice = mock(ProductPrice.class);
      when(mocks.provider.get(ProductPrice.class)).thenReturn(salesPrice, purchasePrice);
      JSONObject body = new JSONObject()
          .put(ProductDefaultsHandler.DIRECTIVE_SKIP_PRICES_SEED, false);
      NeoContext ctx = postCtx(body, obContext);
      ProductDefaultsHandler handler = new ProductDefaultsHandler();

      handler.handle(ctx);
      assertFalse(body.has(ProductDefaultsHandler.DIRECTIVE_SKIP_PRICES_SEED));
      ctx.setPreviousResult(NeoResponse.ok(createdRow()));
      handler.afterHandle(ctx);

      verify(mocks.dal).save(salesPrice);
      verify(mocks.dal).save(purchasePrice);
    }
  }

  /** On PATCH the directive is stripped too, so it never reaches the field filter or the DAL. */
  @Test
  public void testSkipPricesSeedIsStrippedOnPatch() throws Exception {
    JSONObject body = new JSONObject().put("name", "renamed")
        .put(ProductDefaultsHandler.DIRECTIVE_SKIP_PRICES_SEED, true);

    assertNull(new ProductDefaultsHandler().handle(patchCtx(body)));

    assertFalse(body.has(ProductDefaultsHandler.DIRECTIVE_SKIP_PRICES_SEED));
    assertEquals("renamed", body.getString("name"));
  }

  @Test
  public void testConsumeSkipPricesSeedAnswersEmptyWithoutTheDirective() throws Exception {
    assertTrue(ProductDefaultsHandler.consumeSkipPricesSeed(null).isEmpty());
    JSONObject body = new JSONObject().put("name", "P");
    assertTrue(ProductDefaultsHandler.consumeSkipPricesSeed(body).isEmpty());
    assertEquals(1, body.length());
  }

  /**
   * {@code _onlyReturnIds} only drops the decoration of the echoed record; the seed is a write,
   * not a decoration, and still runs on an ids-only create without the directive.
   */
  @Test
  public void testIdsOnlyCreateWithoutTheDirectiveStillSeedsPrices() throws Exception {
    OBContext obContext = obContextWithClient(CLIENT1);
    try (SeedingMocks mocks = new SeedingMocks()) {
      mocks.withBothDefaultTariffs(obContext);
      mocks.utilsMock.when(() -> ProductHandlerUtils.findExistingPrice(anyString(), anyString()))
          .thenReturn(null);
      ProductPrice salesPrice = mock(ProductPrice.class);
      ProductPrice purchasePrice = mock(ProductPrice.class);
      when(mocks.provider.get(ProductPrice.class)).thenReturn(salesPrice, purchasePrice);
      stubCostingCriteria(mocks.dal, Collections.emptyList());
      NeoContext ctx = createOn("batch", new JSONObject().put("name", "P"), obContext);
      ProductDefaultsHandler handler = new ProductDefaultsHandler();

      handler.handle(ctx);
      ctx.setPreviousResult(NeoResponse.ok(createdRow()));
      handler.afterHandle(ctx);

      verify(mocks.dal).save(salesPrice);
      verify(mocks.dal).save(purchasePrice);
    }
  }

  private static OBContext obContextWithOrg(String clientId, String orgId) {
    OBContext obContext = obContextWithClient(clientId);
    Organization org = mock(Organization.class);
    when(org.getId()).thenReturn(orgId);
    when(obContext.getCurrentOrganization()).thenReturn(org);
    return obContext;
  }

  /** Seeds (into an already priced product, so nothing is written) for one create. */
  private static void seedOnce(OBContext obContext) throws Exception {
    new ProductDefaultsHandler().afterHandle(createdProductCtx(PRODUCT_ID, obContext));
  }

  /**
   * ETP-5676: inside a batch the default tariff is resolved once per direction and organisation;
   * an operation of another organisation resolves its own. Outside a batch every create resolves.
   */
  @Test
  public void testDefaultTariffLookupIsMemoizedPerBatchAndOrganisation() throws Exception {
    OBContext orgA = obContextWithOrg(CLIENT1, "ORG-A");
    OBContext orgB = obContextWithOrg(CLIENT1, "ORG-B");
    try (SeedingMocks mocks = new SeedingMocks()) {
      mocks.withBothDefaultTariffs(orgA);
      mocks.withBothDefaultTariffs(orgB);
      mocks.utilsMock.when(() -> ProductHandlerUtils.findExistingPrice(anyString(), anyString()))
          .thenReturn(mock(ProductPrice.class));

      NeoBatchScope previous = NeoBatchScope.open(false);
      try {
        seedOnce(orgA);
        seedOnce(orgA);
        seedOnce(orgB);
      } finally {
        NeoBatchScope.close(previous);
      }
      for (boolean sales : new boolean[] { true, false }) {
        mocks.resolverMock.verify(
            () -> PriceListVersionResolver.resolveDefaultVersionId(orgA, sales), times(1));
        mocks.resolverMock.verify(
            () -> PriceListVersionResolver.resolveDefaultVersionId(orgB, sales), times(1));
      }

      seedOnce(orgA);
      seedOnce(orgA);
      mocks.resolverMock.verify(
          () -> PriceListVersionResolver.resolveDefaultVersionId(orgA, true), times(3));
    }
  }

  /**
   * ETP-5676: the uOM / taxCategory default rows are looked up once per batch and client — not
   * once per product — and another client looks up its own. Outside a batch every create asks.
   */
  @Test
  public void testDefaultRowLookupIsMemoizedPerBatchAndClient() throws Exception {
    ProductDefaultsHandler handler = handlerResolving(
        UOM_CLIENT_DEFAULT, UOM_SYSTEM_DEFAULT, TAX_CLIENT_DEFAULT, "tax-system-default");
    doReturn("uom-client2").when(handler).queryDefaultId(UOM.class, "CLIENT2");
    doReturn("tax-client2").when(handler).queryDefaultId(TaxCategory.class, "CLIENT2");
    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      dalMock.when(OBDal::getInstance).thenReturn(mock(OBDal.class));

      NeoBatchScope previous = NeoBatchScope.open(false);
      try {
        for (int i = 0; i < 3; i++) {
          JSONObject body = new JSONObject();
          handler.handle(postCtx(body, obContextWithClient(CLIENT1)));
          assertEquals(UOM_CLIENT_DEFAULT, body.getString("uOM"));
          assertEquals(TAX_CLIENT_DEFAULT, body.getString("taxCategory"));
        }
        JSONObject other = new JSONObject();
        handler.handle(postCtx(other, obContextWithClient("CLIENT2")));
        assertEquals("uom-client2", other.getString("uOM"));
      } finally {
        NeoBatchScope.close(previous);
      }
      verify(handler, times(1)).queryDefaultId(UOM.class, CLIENT1);
      verify(handler, times(1)).queryDefaultId(TaxCategory.class, CLIENT1);
      verify(handler, times(1)).queryDefaultId(UOM.class, "CLIENT2");

      handler.handle(postCtx(new JSONObject(), obContextWithClient(CLIENT1)));
      handler.handle(postCtx(new JSONObject(), obContextWithClient(CLIENT1)));
      verify(handler, times(3)).queryDefaultId(UOM.class, CLIENT1);
    }
  }

  /** ETP-5676: the directive is declared, so the MCP write verbs accept it and etendo_schema lists it. */
  @Test
  public void testSkipPricesSeedIsDeclaredAsACreateDirective() {
    assertTrue(new ProductDefaultsHandler().createDirectives()
        .containsKey(ProductDefaultsHandler.DIRECTIVE_SKIP_PRICES_SEED));
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
  // silently swallow the ETP-4967 filtering. Since ETP-5009 that filter covers the single-record
  // GET only; the list GET is restricted in the query by readPredicates.

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

  /**
   * ETP-5009: the list GET is no longer post-filtered. The exclusion moved into the query
   * ({@link ProductDefaultsHandler#readPredicates}), so a list response comes back exactly as core
   * produced it — post-filtering a page core had already cut and counted is what left pages short
   * and {@code totalRows} wrong. The hidden-category ids are not even resolved.
   */
  @Test
  public void testAfterHandleNoLongerPostFiltersTheListGet() throws Exception {
    try (MockedStatic<SystemCategoryIds> categoryMock =
        Mockito.mockStatic(SystemCategoryIds.class)) {
      categoryMock.when(() -> SystemCategoryIds.resolve(CLIENT1)).thenReturn(Set.of("cat-system"));

      JSONObject listBody = productListBody("cat-normal", "cat-system", "cat-other");
      NeoResponse result = new ProductDefaultsHandler().afterHandle(crudAfterCtx("GET",
          listBody, obContextWithClient(CLIENT1)));

      assertNull(result);
      JSONObject response = listBody.getJSONObject("response");
      assertEquals(3, response.getJSONArray("data").length());
      assertEquals(3, response.getInt("totalRows"));
      assertEquals(3, response.getInt("endRow"));
      categoryMock.verifyNoInteractions();
    }
  }

  /**
   * ETP-5009: the single-record GET is still post-filtered — core resolves a read by id with its
   * own {@code id = :id} query, so the read predicate never reaches it — and the row counts of
   * the emptied response are decremented so it does not still claim to hold a row.
   */
  @Test
  public void testAfterHandleHidesSystemCategoryProductOnSingleRecordGetAndAdjustsRowCounts()
      throws Exception {
    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class);
        MockedStatic<OBContext> ctxMock = Mockito.mockStatic(OBContext.class);
        MockedStatic<SystemCategoryIds> categoryMock =
            Mockito.mockStatic(SystemCategoryIds.class)) {
      categoryMock.when(() -> SystemCategoryIds.resolve(CLIENT1)).thenReturn(Set.of("cat-system"));
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);

      JSONArray data = new JSONArray();
      data.put(new JSONObject().put("id", PRODUCT_ID).put("productCategory", "cat-system"));
      JSONObject body = dataResponse(data);
      body.getJSONObject("response").put("totalRows", 1);
      body.getJSONObject("response").put("endRow", 1);

      NeoResponse result = new ProductDefaultsHandler().afterHandle(
          singleRecordGetCtx(PRODUCT_ID, body, obContextWithClient(CLIENT1)));

      assertNotNull(result);
      JSONObject response = result.getBody().getJSONObject("response");
      assertEquals(0, response.getJSONArray("data").length());
      assertEquals(0, response.getInt("totalRows"));
      assertEquals(0, response.getInt("endRow"));
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
   * The list GET is left exactly as core produced it: no annotation, above all no COUNT per row,
   * and — since ETP-5009 — no post-filter either (the exclusion is in the query).
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

      JSONObject listBody = productListBody("cat-normal", "cat-system");
      NeoResponse result = new ProductDefaultsHandler().afterHandle(crudAfterCtx("GET",
          listBody, obContextWithClient(CLIENT1)));

      // Nothing replaced the list response...
      assertNull(result);
      JSONArray data = listBody.getJSONObject("response").getJSONArray("data");
      assertEquals(2, data.length());
      // ...nothing was annotated and the Costing table was never queried.
      assertFalse(data.getJSONObject(0).has("etgoHasCost"));
      assertFalse(data.getJSONObject(1).has("etgoHasCost"));
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
   * ETP-5676: a batch sent with {@code _onlyReturnIds} reads only the new id, so the create
   * response is not annotated and the Costing lookup behind the flag is not run.
   */
  @Test
  public void testAfterHandleSkipsTheCostFlagOnAnIdsOnlyCreate() throws Exception {
    OBContext obContext = obContextWithClient(CLIENT1);
    try (SeedingMocks mocks = new SeedingMocks()) {
      mocks.withBothDefaultTariffs(obContext);
      mocks.utilsMock.when(() -> ProductHandlerUtils.findExistingPrice(anyString(), anyString()))
          .thenReturn(mock(ProductPrice.class));
      stubCostingCriteria(mocks.dal, Collections.emptyList());
      NeoContext ctx = NeoContext.builder()
          .specName("product").entityName("product")
          .httpMethod("POST").endpointType(NeoEndpointType.CRUD)
          .idsOnlyResponse(true)
          .obContext(obContext).previousResult(NeoResponse.ok(singleProductBody(PRODUCT_ID)))
          .build();

      new ProductDefaultsHandler().afterHandle(ctx);

      assertFalse(ctx.getPreviousResult().getBody().getJSONObject("response")
          .getJSONArray("data").getJSONObject(0).has("etgoHasCost"));
      verify(mocks.dal, never()).createCriteria(Costing.class);
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

  // ── ETP-5009: readPredicates ─────────────────────────────────────────────────────────────

  @Test
  public void testReadPredicatesExcludesSystemCategoryProductsForTheProductSpec() {
    NeoContext ctx = NeoContext.builder()
        .specName("product").entityName("product")
        .httpMethod("GET").endpointType(NeoEndpointType.CRUD).build();

    assertEquals(List.of(ProductDefaultsHandler.EXCLUDE_SYSTEM_CATEGORY_PREDICATE),
        new ProductDefaultsHandler().readPredicates(ctx));
  }

  @Test
  public void testReadPredicatesIsACorrelatedNotExistsOnTheSystemCategoryFlag() {
    String predicate = ProductDefaultsHandler.EXCLUDE_SYSTEM_CATEGORY_PREDICATE;
    assertTrue(predicate.startsWith("not exists ("));
    assertTrue(predicate.contains("from ProductCategory pc"));
    assertTrue(predicate.contains("pc.id = e.productCategory.id"));
    assertTrue(predicate.contains("pc.etgoIssystemcategory = true"));
  }

  @Test
  public void testReadPredicatesIsEmptyForAnotherSpec() {
    NeoContext ctx = NeoContext.builder()
        .specName("other-spec").entityName("product")
        .httpMethod("GET").endpointType(NeoEndpointType.CRUD).build();

    assertTrue(new ProductDefaultsHandler().readPredicates(ctx).isEmpty());
  }

  @Test
  public void testReadPredicatesIsEmptyForANullContext() {
    assertTrue(new ProductDefaultsHandler().readPredicates(null).isEmpty());
  }

  // ── ETP-5009 (W1): GET product/product?id=X is a read by id too ──────────────────────────
  //
  // Core fetches the query-string form by id exactly like the path form, so the list read
  // predicate never reaches it. Gating the post-filter on the path id alone let a hidden product
  // be read with GET …/product/product?id=<hidden id>; it is gated on NeoContext#isReadById.

  private static NeoContext queryIdGetCtx(String queryId, JSONObject previousBody,
      OBContext obContext) {
    return NeoContext.builder()
        .specName("product").entityName("product")
        .httpMethod("GET").endpointType(NeoEndpointType.CRUD)
        .queryParams(Map.of("id", queryId))
        .obContext(obContext).previousResult(NeoResponse.ok(previousBody)).build();
  }

  @Test
  public void testAfterHandleHidesSystemCategoryProductOnAQueryStringIdGetAndAdjustsRowCounts()
      throws Exception {
    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class);
        MockedStatic<OBContext> ctxMock = Mockito.mockStatic(OBContext.class);
        MockedStatic<SystemCategoryIds> categoryMock =
            Mockito.mockStatic(SystemCategoryIds.class)) {
      categoryMock.when(() -> SystemCategoryIds.resolve(CLIENT1)).thenReturn(Set.of("cat-system"));
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);

      JSONArray data = new JSONArray();
      data.put(new JSONObject().put("id", PRODUCT_ID).put("productCategory", "cat-system"));
      JSONObject body = dataResponse(data);
      body.getJSONObject("response").put("totalRows", 1);
      body.getJSONObject("response").put("endRow", 1);

      NeoResponse result = new ProductDefaultsHandler().afterHandle(
          queryIdGetCtx(PRODUCT_ID, body, obContextWithClient(CLIENT1)));

      assertNotNull(result);
      JSONObject response = result.getBody().getJSONObject("response");
      assertEquals(0, response.getJSONArray("data").length());
      assertEquals(0, response.getInt("totalRows"));
      assertEquals(0, response.getInt("endRow"));
      categoryMock.verify(() -> SystemCategoryIds.resolve(CLIENT1));
    }
  }

  /**
   * A visible product read by {@code ?id=} keeps its row and its counts — and, being a read by
   * id, it is annotated with {@code etgoHasCost} like the path form is.
   */
  @Test
  public void testAfterHandleKeepsAVisibleProductOnAQueryStringIdGet() throws Exception {
    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class);
        MockedStatic<OBContext> ctxMock = Mockito.mockStatic(OBContext.class);
        MockedStatic<SystemCategoryIds> categoryMock =
            Mockito.mockStatic(SystemCategoryIds.class)) {
      categoryMock.when(() -> SystemCategoryIds.resolve(CLIENT1)).thenReturn(Set.of("cat-system"));
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      stubCostingCriteria(dal, Collections.singletonList(mock(Costing.class)));

      JSONArray data = new JSONArray();
      data.put(new JSONObject().put("id", PRODUCT_ID).put("productCategory", "cat-normal"));
      JSONObject body = dataResponse(data);
      body.getJSONObject("response").put("totalRows", 1);
      body.getJSONObject("response").put("endRow", 1);

      NeoResponse result = new ProductDefaultsHandler().afterHandle(
          queryIdGetCtx(PRODUCT_ID, body, obContextWithClient(CLIENT1)));

      assertNotNull(result);
      JSONObject response = result.getBody().getJSONObject("response");
      assertEquals(1, response.getJSONArray("data").length());
      assertEquals(1, response.getInt("totalRows"));
      assertEquals(1, response.getInt("endRow"));
      JSONObject row = response.getJSONArray("data").getJSONObject(0);
      assertEquals(PRODUCT_ID, row.getString("id"));
      assertTrue(row.getBoolean("etgoHasCost"));
    }
  }

  /** A blank {@code ?id=} names no record: it stays a list read, neither filtered nor annotated. */
  @Test
  public void testAfterHandleTreatsABlankQueryStringIdAsAListGet() throws Exception {
    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class);
        MockedStatic<SystemCategoryIds> categoryMock =
            Mockito.mockStatic(SystemCategoryIds.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);

      JSONObject listBody = productListBody("cat-normal", "cat-system");
      NeoResponse result = new ProductDefaultsHandler().afterHandle(
          queryIdGetCtx(" ", listBody, obContextWithClient(CLIENT1)));

      assertNull(result);
      assertEquals(2, listBody.getJSONObject("response").getJSONArray("data").length());
      categoryMock.verifyNoInteractions();
      verify(dal, never()).createCriteria(Costing.class);
    }
  }
  // ── ETP-5680: tax category follows the product type ──────────────────────

  private static ProductDefaultsHandler handlerWithTypeTaxCategories(String servicesId,
      String goodsId) {
    ProductDefaultsHandler handler = handlerResolving(
        UOM_CLIENT_DEFAULT, UOM_SYSTEM_DEFAULT, TAX_CLIENT_DEFAULT, "tax-system-default");
    doReturn(servicesId).when(handler).queryTaxCategoryIdByName(
        ProductDefaultsHandler.TAX_CATEGORY_NAME_SERVICE, CLIENT1);
    doReturn(goodsId).when(handler).queryTaxCategoryIdByName(
        ProductDefaultsHandler.TAX_CATEGORY_NAME_GOODS, CLIENT1);
    doReturn(null).when(handler).queryTaxCategoryIdByName(anyString(), org.mockito.ArgumentMatchers.eq(SYSTEM_CLIENT_ID));
    return handler;
  }

  @Test
  public void testTaxCategoryNameForTypeMapsServiceAndEveryOtherType() {
    assertEquals("IVA Normal Servicios", ProductDefaultsHandler.taxCategoryNameForType("S"));
    assertEquals("IVA Normal", ProductDefaultsHandler.taxCategoryNameForType("I"));
    assertEquals("IVA Normal", ProductDefaultsHandler.taxCategoryNameForType("R"));
    assertEquals("IVA Normal", ProductDefaultsHandler.taxCategoryNameForType("E"));
    assertNull(ProductDefaultsHandler.taxCategoryNameForType(null));
    assertNull(ProductDefaultsHandler.taxCategoryNameForType(" "));
  }

  @Test
  public void testCreateFillsMissingTaxCategoryFromProductType() throws Exception {
    ProductDefaultsHandler handler = handlerWithTypeTaxCategories("tax-services", "tax-goods");

    JSONObject service = new JSONObject().put("productType", "S");
    handler.handle(postCtx(service, obContextWithClient(CLIENT1)));
    assertEquals("tax-services", service.getString("taxCategory"));

    JSONObject item = new JSONObject().put("productType", "I");
    handler.handle(postCtx(item, obContextWithClient(CLIENT1)));
    assertEquals("tax-goods", item.getString("taxCategory"));
  }

  @Test
  public void testCreateKeepsExplicitTaxCategoryAndFallsBackToIsDefault() throws Exception {
    ProductDefaultsHandler handler = handlerWithTypeTaxCategories("tax-services", "tax-goods");

    JSONObject explicit = new JSONObject().put("productType", "S").put("taxCategory", "mine");
    handler.handle(postCtx(explicit, obContextWithClient(CLIENT1)));
    assertEquals("mine", explicit.getString("taxCategory"));

    // No productType in the body: legacy IsDefault fallback.
    JSONObject noType = new JSONObject();
    handler.handle(postCtx(noType, obContextWithClient(CLIENT1)));
    assertEquals(TAX_CLIENT_DEFAULT, noType.getString("taxCategory"));
  }

  @Test
  public void testCreateFallsBackToIsDefaultWhenTenantLacksTheNamedCategory() throws Exception {
    ProductDefaultsHandler handler = handlerWithTypeTaxCategories(null, null);

    JSONObject body = new JSONObject().put("productType", "S");
    handler.handle(postCtx(body, obContextWithClient(CLIENT1)));
    assertEquals(TAX_CLIENT_DEFAULT, body.getString("taxCategory"));
  }

  @Test
  public void testPatchNeverRecalculatesTaxCategory() throws Exception {
    ProductDefaultsHandler handler = handlerWithTypeTaxCategories("tax-services", "tax-goods");

    JSONObject body = new JSONObject().put("productType", "S");
    NeoContext ctx = NeoContext.builder()
        .specName("product").entityName("product")
        .httpMethod("PATCH").endpointType(NeoEndpointType.CRUD)
        .requestBody(body).obContext(obContextWithClient(CLIENT1)).build();
    handler.handle(ctx);
    assertFalse(body.has("taxCategory"));
  }

  @Test
  public void testDefaultsExposesTypeTaxCategoryOnlyWhenTypeSentAndNameExists() throws Exception {
    ProductDefaultsHandler handler = handlerWithTypeTaxCategories("tax-services", null);

    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(any(Class.class), anyString())).thenReturn(null);

      NeoContext withType = NeoContext.builder()
          .specName("product").entityName("product")
          .httpMethod("GET").endpointType(NeoEndpointType.DEFAULTS)
          .queryParams(Map.of("productType", "S"))
          .obContext(obContextWithClient(CLIENT1))
          .previousResult(NeoResponse.ok(new JSONObject().put("defaults", new JSONObject())))
          .build();
      JSONObject d = handler.afterHandle(withType).getBody().getJSONObject("defaults");
      assertEquals("tax-services", d.getString("typeTaxCategory"));
      assertEquals("tax-services", d.getString("taxCategory"));
      assertEquals("S", d.getString("productType"));

      // Name missing for this type (goods) -> key absent, UI leaves the field untouched.
      NeoContext missing = NeoContext.builder()
          .specName("product").entityName("product")
          .httpMethod("GET").endpointType(NeoEndpointType.DEFAULTS)
          .queryParams(Map.of("productType", "I"))
          .obContext(obContextWithClient(CLIENT1))
          .previousResult(NeoResponse.ok(new JSONObject().put("defaults", new JSONObject())))
          .build();
      JSONObject d2 = handler.afterHandle(missing).getBody().getJSONObject("defaults");
      assertFalse(d2.has("typeTaxCategory"));
      assertEquals(TAX_CLIENT_DEFAULT, d2.getString("taxCategory"));

      // No productType parameter -> key absent.
      JSONObject d3 = handler.afterHandle(defaultsCtx(obContextWithClient(CLIENT1),
          NeoResponse.ok(new JSONObject().put("defaults", new JSONObject()))))
          .getBody().getJSONObject("defaults");
      assertFalse(d3.has("typeTaxCategory"));
    }
  }

  @Test
  public void testDefaultsReadsProductTypeFromServletRequestWhenContextHasNoQueryMap()
      throws Exception {
    // Fallback guard: a hook context built without a query map (not the REST /defaults path any
    // more) still gets the type from the servlet request.
    ProductDefaultsHandler handler = handlerWithTypeTaxCategories("tax-services", "tax-goods");
    javax.servlet.http.HttpServletRequest req = mock(javax.servlet.http.HttpServletRequest.class);
    when(req.getParameter("productType")).thenReturn("S");
    org.openbravo.client.kernel.RequestContext rc =
        mock(org.openbravo.client.kernel.RequestContext.class);
    when(rc.getRequest()).thenReturn(req);

    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
        MockedStatic<org.openbravo.client.kernel.RequestContext> rcMock =
            Mockito.mockStatic(org.openbravo.client.kernel.RequestContext.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(any(Class.class), anyString())).thenReturn(null);
      rcMock.when(org.openbravo.client.kernel.RequestContext::get).thenReturn(rc);

      NeoResponse result = handler.afterHandle(defaultsCtx(obContextWithClient(CLIENT1),
          NeoResponse.ok(new JSONObject().put("defaults", new JSONObject()))));
      assertEquals("tax-services",
          result.getBody().getJSONObject("defaults").getString("typeTaxCategory"));
    }
  }

  @Test
  public void testNewFormDefaultsStartAnItemOnItsTypeTaxCategory() throws Exception {
    // Sequence: new form opens (no productType param, payload defaults to Item) -> the user
    // changes the type (param sent as a query param, as NeoSubEndpointDispatcher now forwards it).
    ProductDefaultsHandler handler = handlerWithTypeTaxCategories("tax-services", "tax-goods");

    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(any(Class.class), anyString())).thenReturn(null);

      JSONObject initial = handler.afterHandle(defaultsCtx(obContextWithClient(CLIENT1),
          NeoResponse.ok(new JSONObject().put("defaults",
              new JSONObject().put("productType", "I").put("taxCategory", "generic-first")))))
          .getBody().getJSONObject("defaults");
      assertEquals("tax-goods", initial.getString("taxCategory"));
      assertFalse(initial.has("typeTaxCategory"));

      NeoContext changed = NeoContext.builder()
          .specName("product").entityName("product")
          .httpMethod("GET").endpointType(NeoEndpointType.DEFAULTS)
          .queryParams(Map.of("productType", "S"))
          .obContext(obContextWithClient(CLIENT1))
          .previousResult(NeoResponse.ok(new JSONObject().put("defaults",
              new JSONObject().put("productType", "I"))))
          .build();
      JSONObject afterChange = handler.afterHandle(changed).getBody().getJSONObject("defaults");
      assertEquals("tax-services", afterChange.getString("typeTaxCategory"));
    }
  }

  @Test
  public void testNewFormKeepsIsDefaultTaxCategoryWhenTenantLacksTheNamedCategory()
      throws Exception {
    ProductDefaultsHandler handler = handlerWithTypeTaxCategories(null, null);

    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(any(Class.class), anyString())).thenReturn(null);

      JSONObject initial = handler.afterHandle(defaultsCtx(obContextWithClient(CLIENT1),
          NeoResponse.ok(new JSONObject().put("defaults", new JSONObject().put("productType", "I")))))
          .getBody().getJSONObject("defaults");
      assertEquals(TAX_CLIENT_DEFAULT, initial.getString("taxCategory"));
    }
  }

  // ── ETP-5680 via MCP: fallback placeholders cede to the type's category ──

  private static NeoContext postCtxWithPlaceholders(JSONObject body, String... placeholders) {
    NeoContext ctx = postCtx(body, obContextWithClient(CLIENT1));
    for (String key : placeholders) {
      ctx.addFallbackDefault(key);
    }
    return ctx;
  }

  @Test
  public void testPlaceholderTaxCategoryIsReplacedByTheTypesCategory() throws Exception {
    ProductDefaultsHandler handler = handlerWithTypeTaxCategories("tax-services", "tax-goods");

    JSONObject service = new JSONObject().put("productType", "S").put("taxCategory", "first-combo");
    handler.handle(postCtxWithPlaceholders(service, "taxCategory"));
    assertEquals("tax-services", service.getString("taxCategory"));

    JSONObject item = new JSONObject().put("productType", "I").put("taxCategory", "first-combo");
    handler.handle(postCtxWithPlaceholders(item, "taxCategory"));
    assertEquals("tax-goods", item.getString("taxCategory"));
  }

  @Test
  public void testExplicitTaxCategoryIsNeverAPlaceholder() throws Exception {
    ProductDefaultsHandler handler = handlerWithTypeTaxCategories("tax-services", "tax-goods");

    // Only uOM was a placeholder; the caller chose taxCategory (Exento), so it is kept.
    JSONObject body = new JSONObject().put("productType", "S").put("taxCategory", "exento")
        .put("uOM", "first-uom");
    handler.handle(postCtxWithPlaceholders(body, "uOM"));
    assertEquals("exento", body.getString("taxCategory"));
  }

  @Test
  public void testPlaceholderTaxCategoryFallsBackToIsDefaultThenIsKept() throws Exception {
    ProductDefaultsHandler lacking = handlerWithTypeTaxCategories(null, null);
    JSONObject body = new JSONObject().put("productType", "S").put("taxCategory", "first-combo");
    lacking.handle(postCtxWithPlaceholders(body, "taxCategory"));
    assertEquals(TAX_CLIENT_DEFAULT, body.getString("taxCategory"));

    ProductDefaultsHandler noDefaultAnywhere = spy(new ProductDefaultsHandler());
    doReturn(null).when(noDefaultAnywhere).queryDefaultId(any(), anyString());
    doReturn(null).when(noDefaultAnywhere).queryTaxCategoryIdByName(anyString(), anyString());
    JSONObject kept = new JSONObject().put("productType", "S").put("taxCategory", "first-combo");
    noDefaultAnywhere.handle(postCtxWithPlaceholders(kept, "taxCategory"));
    assertEquals("first-combo", kept.getString("taxCategory"));
  }

  @Test
  public void testPlaceholderUomIsReplacedByTheIsDefaultUnit() throws Exception {
    ProductDefaultsHandler handler = handlerWithTypeTaxCategories("tax-services", "tax-goods");

    JSONObject body = new JSONObject().put("productType", "I").put("uOM", "first-uom");
    handler.handle(postCtxWithPlaceholders(body, "uOM"));
    assertEquals(UOM_CLIENT_DEFAULT, body.getString("uOM"));

    JSONObject chosen = new JSONObject().put("productType", "I").put("uOM", "my-uom");
    handler.handle(postCtxWithPlaceholders(chosen));
    assertEquals("my-uom", chosen.getString("uOM"));
  }

  @Test
  public void testEmptyPlaceholderSetBehavesLikeRest() throws Exception {
    ProductDefaultsHandler handler = handlerWithTypeTaxCategories("tax-services", "tax-goods");

    JSONObject body = new JSONObject().put("productType", "S").put("taxCategory", "present");
    handler.handle(postCtx(body, obContextWithClient(CLIENT1)));
    assertEquals("present", body.getString("taxCategory"));

    JSONObject missing = new JSONObject().put("productType", "S");
    handler.handle(postCtx(missing, obContextWithClient(CLIENT1)));
    assertEquals("tax-services", missing.getString("taxCategory"));
  }

  private static NeoContext defaultsCtxAsking(String productType, JSONObject defaults) throws Exception {
    NeoContext.Builder builder = NeoContext.builder()
        .specName("product").entityName("product")
        .httpMethod("GET").endpointType(NeoEndpointType.DEFAULTS)
        .obContext(obContextWithClient(CLIENT1))
        .previousResult(NeoResponse.ok(new JSONObject().put("defaults", defaults)));
    if (productType != null) {
      builder.queryParams(Map.of("productType", productType));
    }
    return builder.build();
  }

  private static JSONObject genericDefaults() throws Exception {
    return new JSONObject().put("productType", "I").put("productType$_identifier", "Item")
        .put("taxCategory", "generic-first");
  }

  /** A copy of taxCategory from the defaults of a type must be that type's category. */
  @Test
  public void testDefaultsOfARequestedTypeAreConsistentWithThatType() throws Exception {
    ProductDefaultsHandler handler = handlerWithTypeTaxCategories("tax-services", "tax-goods");

    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(any(Class.class), anyString())).thenReturn(null);

      JSONObject service = handler.afterHandle(defaultsCtxAsking("S", genericDefaults()))
          .getBody().getJSONObject("defaults");
      assertEquals("S", service.getString("productType"));
      assertFalse(service.has("productType$_identifier"));
      assertEquals("tax-services", service.getString("taxCategory"));
      assertEquals("tax-services", service.getString("typeTaxCategory"));

      JSONObject item = handler.afterHandle(defaultsCtxAsking("I", genericDefaults()))
          .getBody().getJSONObject("defaults");
      assertEquals("I", item.getString("productType"));
      assertEquals("tax-goods", item.getString("taxCategory"));
      assertEquals("tax-goods", item.getString("typeTaxCategory"));
    }
  }

  /** No named category for the requested type: taxCategory stays, no typeTaxCategory, type follows. */
  @Test
  public void testDefaultsOfARequestedTypeWithoutItsNamedCategory() throws Exception {
    ProductDefaultsHandler handler = handlerWithTypeTaxCategories(null, "tax-goods");

    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(any(Class.class), anyString())).thenReturn(null);

      JSONObject service = handler.afterHandle(defaultsCtxAsking("S", genericDefaults()))
          .getBody().getJSONObject("defaults");
      assertEquals("S", service.getString("productType"));
      assertEquals(TAX_CLIENT_DEFAULT, service.getString("taxCategory"));
      assertFalse(service.has("typeTaxCategory"));
    }
  }

  /** Without a requested type, and for an unknown one, the payload keeps asking for Item. */
  @Test
  public void testDefaultsWithoutAValidRequestedTypeStayOnItem() throws Exception {
    ProductDefaultsHandler handler = handlerWithTypeTaxCategories("tax-services", "tax-goods");

    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(any(Class.class), anyString())).thenReturn(null);

      for (String asked : new String[] { null, "ZZ" }) {
        JSONObject d = handler.afterHandle(defaultsCtxAsking(asked, genericDefaults()))
            .getBody().getJSONObject("defaults");
        assertEquals("I", d.getString("productType"));
        assertEquals("Item", d.getString("productType$_identifier"));
        assertEquals("tax-goods", d.getString("taxCategory"));
        assertFalse(d.has("typeTaxCategory"));
      }
    }
  }
}
