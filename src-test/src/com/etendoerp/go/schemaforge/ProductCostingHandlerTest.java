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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.criterion.Criterion;
import org.hibernate.criterion.Order;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.client.kernel.RequestContext;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.erpCommon.utility.OBCurrencyUtils;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.common.plm.Product;
import org.openbravo.model.materialmgmt.cost.Costing;

/**
 * Unit tests for {@link ProductCostingHandler} (ETP-5245).
 *
 * <p>The Costing tab writes into {@code M_Costing}, the costing engine's own table. The handler
 * is what makes a hand-entered row safe there, so these tests are written against the guarantees
 * the class javadoc states, not against its implementation:
 *
 * <ul>
 *   <li>every derived column ends up at its forced value <em>whatever the client sent</em> —
 *       {@code costType='STA'} (an 'AVA' row diverges stock valuation from the running cost),
 *       {@code permanent=false} ({@code M_COSTING_TRG} would then refuse every later edit and
 *       delete), {@code production=false} ({@code MA_PRODUCTION_COST} has no
 *       {@code TOO_MANY_ROWS} handler), {@code manual=true} (our own marker);</li>
 *   <li>the currency comes from the organisation, never from the AD default {@code 100} = USD;
 *   </li>
 *   <li>the product is resolved from {@code parentId} — in a pre-hook that is where the UI's
 *       product still is;</li>
 *   <li>each refusal carries its exact {@code ERR_*} sentence, which
 *       {@code lib/backendErrors.js} maps literally to a locale key;</li>
 *   <li>an engine-generated row cannot be modified or deleted, by anyone, over any channel.</li>
 * </ul>
 *
 * <p>Pure Mockito — no DAL, no database.
 */
public class ProductCostingHandlerTest {

  private static final String PRODUCT_ID = "prod-1";
  private static final String ORG_ID = "org-1";
  private static final String ORG_CURRENCY_ID = "cur-eur";
  private static final String COSTING_ID = "costing-1";
  private static final String OTHER_PRODUCT_ID = "prod-2";
  /** The AD default for {@code C_Currency_ID}: USD. Wrong on every euro instance. */
  private static final String AD_DEFAULT_CURRENCY_ID = "100";

  private final ProductCostingHandler handler = new ProductCostingHandler();

  // ── context helpers ────────────────────────────────────────────────────────

  private static NeoContext crudCtx(String method, JSONObject body, String recordId,
      Map<String, String> queryParams, OBContext obContext) {
    return NeoContext.builder()
        .specName("product").entityName("costing")
        .httpMethod(method).endpointType(NeoEndpointType.CRUD)
        .requestBody(body).recordId(recordId).queryParams(queryParams)
        .obContext(obContext).build();
  }

  private static NeoContext postCtx(JSONObject body) {
    return crudCtx("POST", body, null, null, null);
  }

  private static NeoContext postCtx(JSONObject body, Map<String, String> queryParams) {
    return crudCtx("POST", body, null, queryParams, null);
  }

  /** An OBContext whose current organisation is {@link #ORG_ID}. */
  private static OBContext obContextWithOrg() {
    OBContext obContext = mock(OBContext.class);
    Organization org = mock(Organization.class);
    when(org.getId()).thenReturn(ORG_ID);
    when(obContext.getCurrentOrganization()).thenReturn(org);
    return obContext;
  }

  /** The minimal acceptable create body: a product and a cost. */
  private static JSONObject validBody() throws Exception {
    JSONObject body = new JSONObject();
    body.put("product", PRODUCT_ID);
    body.put("cost", "12.50");
    return body;
  }

  private static Map<String, String> params(String key, String value) {
    Map<String, String> map = new HashMap<>();
    map.put(key, value);
    return map;
  }

  /** The human-readable message a NeoResponse.error carries. */
  private static String messageOf(NeoResponse response) throws Exception {
    return response.getBody().getJSONObject("error").getString("message");
  }

  private static void assertRefusal(NeoResponse response, int status, String message)
      throws Exception {
    assertNotNull("expected the request to be refused", response);
    assertEquals(status, response.getHttpStatus());
    assertEquals(message, messageOf(response));
  }

  // ── routing ────────────────────────────────────────────────────────────────

  @Test
  public void testHandleIgnoresANullContext() {
    assertNull(handler.handle(null));
  }

  @Test
  public void testHandleIgnoresANonCrudEndpoint() throws Exception {
    NeoContext ctx = NeoContext.builder()
        .specName("product").entityName("costing")
        .httpMethod("POST").endpointType(NeoEndpointType.DEFAULTS)
        .requestBody(new JSONObject()).build();
    assertNull(handler.handle(ctx));
  }

  @Test
  public void testHandleIgnoresAnotherEntity() throws Exception {
    NeoContext ctx = NeoContext.builder()
        .specName("product").entityName("header")
        .httpMethod("POST").endpointType(NeoEndpointType.CRUD)
        .requestBody(new JSONObject()).build();
    assertNull(handler.handle(ctx));
  }

  @Test
  public void testHandleTolerantOfAMissingBodyOnCreate() {
    assertNull(handler.handle(postCtx(null)));
  }

  // ── the exact refusal sentences ────────────────────────────────────────────

  /**
   * The frontend maps these five sentences literally ({@code lib/backendErrors.js}); a silent
   * reword here would leave the user reading English with no error anywhere.
   */
  @Test
  public void testErrorMessagesAreTheOnesTheFrontendMaps() {
    assertEquals("A cost line must belong to a product.", ProductCostingHandler.ERR_NO_PRODUCT);
    assertEquals("The cost is required.", ProductCostingHandler.ERR_COST_REQUIRED);
    assertEquals("The cost cannot be negative.", ProductCostingHandler.ERR_COST_NEGATIVE);
    assertEquals("The expiry date must be later than the start date.",
        ProductCostingHandler.ERR_INVALID_RANGE);
    assertEquals("This cost was calculated by the system and cannot be modified or deleted.",
        ProductCostingHandler.ERR_ENGINE_ROW);
    assertEquals("The cost line could not be prepared. Try again.",
        ProductCostingHandler.ERR_PREPARE_FAILED);
  }

  // ── product resolution ─────────────────────────────────────────────────────

  @Test
  public void testCreateAcceptsTheProductFromTheBody() throws Exception {
    assertNull(handler.handle(postCtx(validBody())));
  }

  /**
   * The normal UI case: in a pre-hook the product still arrives as {@code parentId}, because the
   * CRUD layer only renames it to {@code product} afterwards.
   */
  @Test
  public void testCreateResolvesTheProductFromParentIdInTheBody() throws Exception {
    JSONObject body = new JSONObject();
    body.put("parentId", PRODUCT_ID);
    body.put("cost", "1");

    assertNull(handler.handle(postCtx(body)));
  }

  @Test
  public void testCreateResolvesTheProductFromParentIdInTheQueryString() throws Exception {
    JSONObject body = new JSONObject();
    body.put("cost", "1");

    assertNull(handler.handle(postCtx(body, params("parentId", PRODUCT_ID))));
  }

  @Test
  public void testCreateFallsBackToParentIdWhenTheProductFieldIsBlank() throws Exception {
    JSONObject body = new JSONObject();
    body.put("product", "   ");
    body.put("parentId", PRODUCT_ID);
    body.put("cost", "1");

    assertNull(handler.handle(postCtx(body)));
  }

  @Test
  public void testCreateRefusesALineThatNamesNoProduct() throws Exception {
    JSONObject body = new JSONObject();
    body.put("cost", "1");

    assertRefusal(handler.handle(postCtx(body)), 400, ProductCostingHandler.ERR_NO_PRODUCT);
  }

  @Test
  public void testCreateRefusesWhenTheQueryStringParentIdIsBlank() throws Exception {
    JSONObject body = new JSONObject();
    body.put("cost", "1");

    assertRefusal(handler.handle(postCtx(body, params("parentId", "  "))), 400,
        ProductCostingHandler.ERR_NO_PRODUCT);
  }

  // ── forced derived columns ─────────────────────────────────────────────────

  /**
   * A manual 'AVA' row would be read by {@code AverageAlgorithm#getProductCost} as the current
   * average cost while {@code getLastCumulatedCosting} skipped it, so stock valuation and running
   * cost would silently diverge. The user never picks the type — whatever they send.
   */
  @Test
  public void testCreateForcesTheStandardCostTypeOverAClientSuppliedAverage() throws Exception {
    JSONObject body = validBody();
    body.put("costType", "AVA");

    assertNull(handler.handle(postCtx(body)));
    assertEquals("STA", body.getString("costType"));
  }

  @Test
  public void testCreateSetsTheStandardCostTypeWhenTheClientSendsNone() throws Exception {
    JSONObject body = validBody();

    assertNull(handler.handle(postCtx(body)));
    assertEquals("STA", body.getString("costType"));
  }

  /**
   * {@code M_COSTING_TRG} raises {@code @CannotModifyPermanentCost@} on a permanent row once the
   * product has document lines — the user could then neither edit nor delete what they typed.
   */
  @Test
  public void testCreateForcesPermanentFalseOverAClientSuppliedTrue() throws Exception {
    JSONObject body = validBody();
    body.put("permanent", true);

    assertNull(handler.handle(postCtx(body)));
    assertFalse(body.getBoolean("permanent"));
  }

  /**
   * {@code MA_PRODUCTION_COST} does a {@code SELECT ... INTO} over production rows with no
   * {@code TOO_MANY_ROWS} handler, so a duplicate stops production from being processed.
   */
  @Test
  public void testCreateForcesProductionFalseOverAClientSuppliedTrue() throws Exception {
    JSONObject body = validBody();
    body.put("production", true);

    assertNull(handler.handle(postCtx(body)));
    assertFalse(body.getBoolean("production"));
  }

  /** {@code manual} is the marker that tells a human-entered row from an engine one. */
  @Test
  public void testCreateForcesManualTrueOverAClientSuppliedFalse() throws Exception {
    JSONObject body = validBody();
    body.put("manual", false);

    assertNull(handler.handle(postCtx(body)));
    assertTrue(body.getBoolean("manual"));
  }

  /**
   * The cumulative/transaction columns only mean something for engine rows, and
   * {@code AverageAlgorithm#getLastCumulatedCosting} excludes rows whose cumulative columns are
   * null — which is exactly what a manual row wants.
   */
  @Test
  public void testCreateStripsEveryEngineOnlyField() throws Exception {
    JSONObject body = validBody();
    for (String field : new String[]{ "inventoryTransaction", "invoiceLine", "quantity", "price",
        "totalMovementQuantity", "totalStockValuation", "originalCost" }) {
      body.put(field, "smuggled");
    }

    assertNull(handler.handle(postCtx(body)));

    for (String field : new String[]{ "inventoryTransaction", "invoiceLine", "quantity", "price",
        "totalMovementQuantity", "totalStockValuation", "originalCost" }) {
      assertFalse("engine-only field survived: " + field, body.has(field));
    }
  }

  @Test
  public void testCreateLeavesTheFieldsItDoesNotOwnAlone() throws Exception {
    JSONObject body = validBody();
    body.put("startingDate", "2026-01-01");
    body.put("endingDate", "2026-06-30");

    assertNull(handler.handle(postCtx(body)));

    assertEquals(PRODUCT_ID, body.getString("product"));
    assertEquals("12.50", body.getString("cost"));
    assertEquals("2026-01-01", body.getString("startingDate"));
    assertEquals("2026-06-30", body.getString("endingDate"));
  }

  // ── organisation and currency ──────────────────────────────────────────────

  @Test
  public void testCreateTakesTheCurrencyFromTheOrganisationNotTheAdDefault() throws Exception {
    JSONObject body = validBody();

    try (MockedStatic<OBContext> obContextMock = Mockito.mockStatic(OBContext.class);
        MockedStatic<OBCurrencyUtils> currencyMock = Mockito.mockStatic(OBCurrencyUtils.class)) {
      currencyMock.when(() -> OBCurrencyUtils.getOrgCurrency(ORG_ID)).thenReturn(ORG_CURRENCY_ID);

      assertNull(handler.handle(crudCtx("POST", body, null, null, obContextWithOrg())));
    }

    assertEquals(ORG_ID, body.getString("organization"));
    assertEquals(ORG_CURRENCY_ID, body.getString("cCurrencyID"));
    // The AD default for C_Currency_ID is 100 = USD, which would be wrong on a euro instance.
    assertFalse(AD_DEFAULT_CURRENCY_ID.equals(body.optString("cCurrencyID", null)));
  }

  @Test
  public void testCreateKeepsAnExplicitOrganisationAndResolvesItsCurrency() throws Exception {
    JSONObject body = validBody();
    body.put("organization", "org-other");

    try (MockedStatic<OBContext> obContextMock = Mockito.mockStatic(OBContext.class);
        MockedStatic<OBCurrencyUtils> currencyMock = Mockito.mockStatic(OBCurrencyUtils.class)) {
      currencyMock.when(() -> OBCurrencyUtils.getOrgCurrency("org-other")).thenReturn("cur-usd");

      assertNull(handler.handle(crudCtx("POST", body, null, null, obContextWithOrg())));
    }

    assertEquals("org-other", body.getString("organization"));
    assertEquals("cur-usd", body.getString("cCurrencyID"));
  }

  @Test
  public void testCreateDoesNotOverwriteACurrencyTheClientSupplied() throws Exception {
    JSONObject body = validBody();
    body.put("cCurrencyID", "cur-gbp");

    try (MockedStatic<OBContext> obContextMock = Mockito.mockStatic(OBContext.class);
        MockedStatic<OBCurrencyUtils> currencyMock = Mockito.mockStatic(OBCurrencyUtils.class)) {
      assertNull(handler.handle(crudCtx("POST", body, null, null, obContextWithOrg())));

      currencyMock.verifyNoInteractions();
    }

    assertEquals("cur-gbp", body.getString("cCurrencyID"));
  }

  @Test
  public void testCreateLeavesTheCurrencyUnsetWhenTheOrganisationResolvesToNone() throws Exception {
    JSONObject body = validBody();

    try (MockedStatic<OBContext> obContextMock = Mockito.mockStatic(OBContext.class);
        MockedStatic<OBCurrencyUtils> currencyMock = Mockito.mockStatic(OBCurrencyUtils.class)) {
      currencyMock.when(() -> OBCurrencyUtils.getOrgCurrency(ORG_ID)).thenReturn("  ");

      assertNull(handler.handle(crudCtx("POST", body, null, null, obContextWithOrg())));
    }

    assertFalse(body.has("cCurrencyID"));
  }

  @Test
  public void testCreateSkipsTheInjectionWhenThereIsNoOrganisationInContext() throws Exception {
    JSONObject body = validBody();
    OBContext obContext = mock(OBContext.class);
    when(obContext.getCurrentOrganization()).thenReturn(null);

    assertNull(handler.handle(crudCtx("POST", body, null, null, obContext)));

    assertFalse(body.has("organization"));
    assertFalse(body.has("cCurrencyID"));
    // The forced columns still applied — the currency is a separate concern.
    assertEquals("STA", body.getString("costType"));
  }

  // ── the open-ended expiry date ─────────────────────────────────────────────

  /**
   * {@code DateTo} is mandatory in the dictionary but optional for the user: an empty expiry means
   * "in force indefinitely", which the engine spells {@code 31-12-9999}
   * ({@code CostingUtils#getLastDate}).
   */
  @Test
  public void testCreateFillsAnEmptyExpiryWithTheOpenEndedDate() throws Exception {
    JSONObject body = validBody();
    body.put("startingDate", "2026-01-01");

    assertNull(handler.handle(postCtx(body)));

    assertEquals("9999-12-31", body.getString("endingDate"));
  }

  @Test
  public void testCreateFillsABlankExpiryWithTheOpenEndedDate() throws Exception {
    JSONObject body = validBody();
    body.put("endingDate", "   ");

    assertNull(handler.handle(postCtx(body)));

    assertEquals("9999-12-31", body.getString("endingDate"));
  }

  @Test
  public void testCreateKeepsAnExpiryTheUserTyped() throws Exception {
    JSONObject body = validBody();
    body.put("startingDate", "2026-01-01");
    body.put("endingDate", "2026-12-31");

    assertNull(handler.handle(postCtx(body)));

    assertEquals("2026-12-31", body.getString("endingDate"));
  }

  // ── validation ─────────────────────────────────────────────────────────────

  @Test
  public void testCreateRefusesALineWithNoCost() throws Exception {
    JSONObject body = new JSONObject();
    body.put("product", PRODUCT_ID);

    assertRefusal(handler.handle(postCtx(body)), 400, ProductCostingHandler.ERR_COST_REQUIRED);
  }

  @Test
  public void testCreateRefusesABlankCost() throws Exception {
    JSONObject body = new JSONObject();
    body.put("product", PRODUCT_ID);
    body.put("cost", "   ");

    assertRefusal(handler.handle(postCtx(body)), 400, ProductCostingHandler.ERR_COST_REQUIRED);
  }

  @Test
  public void testCreateRefusesAnUnparseableCost() throws Exception {
    JSONObject body = new JSONObject();
    body.put("product", PRODUCT_ID);
    body.put("cost", "twelve euros");

    assertRefusal(handler.handle(postCtx(body)), 400, ProductCostingHandler.ERR_COST_REQUIRED);
  }

  @Test
  public void testCreateRefusesANegativeCost() throws Exception {
    JSONObject body = new JSONObject();
    body.put("product", PRODUCT_ID);
    body.put("cost", "-0.01");

    assertRefusal(handler.handle(postCtx(body)), 400, ProductCostingHandler.ERR_COST_NEGATIVE);
  }

  /** Zero is a legitimate standard cost (a free sample, a not-yet-valued item). */
  @Test
  public void testCreateAcceptsAZeroCost() throws Exception {
    JSONObject body = new JSONObject();
    body.put("product", PRODUCT_ID);
    body.put("cost", "0");

    assertNull(handler.handle(postCtx(body)));
  }

  @Test
  public void testCreateRefusesAnExpiryEqualToTheStartDate() throws Exception {
    JSONObject body = validBody();
    body.put("startingDate", "2026-03-01");
    body.put("endingDate", "2026-03-01");

    assertRefusal(handler.handle(postCtx(body)), 400, ProductCostingHandler.ERR_INVALID_RANGE);
  }

  @Test
  public void testCreateRefusesAnExpiryBeforeTheStartDate() throws Exception {
    JSONObject body = validBody();
    body.put("startingDate", "2026-03-01");
    body.put("endingDate", "2026-02-28");

    assertRefusal(handler.handle(postCtx(body)), 400, ProductCostingHandler.ERR_INVALID_RANGE);
  }

  @Test
  public void testCreateAcceptsAnExpiryAfterTheStartDate() throws Exception {
    JSONObject body = validBody();
    body.put("startingDate", "2026-03-01");
    body.put("endingDate", "2026-03-02");

    assertNull(handler.handle(postCtx(body)));
  }

  /** An omitted start date leaves the range half-open; the defaulted 31-12-9999 expiry is fine. */
  @Test
  public void testCreateAcceptsALineWithNoStartDate() throws Exception {
    JSONObject body = validBody();

    assertNull(handler.handle(postCtx(body)));
    assertEquals("9999-12-31", body.getString("endingDate"));
  }

  // ── the engine-row guard ───────────────────────────────────────────────────

  private NeoResponse guard(String method, String recordId, JSONObject body, Costing found) {
    try (MockedStatic<OBContext> obContextMock = Mockito.mockStatic(OBContext.class);
        MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(Costing.class, recordId)).thenReturn(found);

      return handler.handle(crudCtx(method, body, recordId, null, null));
    }
  }

  private static Costing costingRow(Boolean manual) {
    Costing costing = mock(Costing.class);
    when(costing.isManual()).thenReturn(manual);
    return costing;
  }

  @Test
  public void testGuardIgnoresAnUpdateWithNoRecordId() throws Exception {
    assertNull(handler.handle(crudCtx("PATCH", new JSONObject(), null, null, null)));
  }

  @Test
  public void testGuardLetsThroughAnUpdateOfARecordThatNoLongerExists() throws Exception {
    assertNull(guard("PATCH", COSTING_ID, new JSONObject(), null));
  }

  /**
   * The single most important assertion in this file. The Costing grid renders the bin on every
   * row, engine-generated ones included, and there is no per-row delete gate — so this 403 is the
   * only thing standing between a stray click (or an MCP call) and a deleted costing-engine row.
   */
  @Test
  public void testGuardRefusesToDeleteAnEngineRow() throws Exception {
    assertRefusal(guard("DELETE", COSTING_ID, null, costingRow(Boolean.FALSE)), 403,
        ProductCostingHandler.ERR_ENGINE_ROW);
  }

  @Test
  public void testGuardRefusesToPatchAnEngineRow() throws Exception {
    assertRefusal(guard("PATCH", COSTING_ID, new JSONObject(), costingRow(Boolean.FALSE)), 403,
        ProductCostingHandler.ERR_ENGINE_ROW);
  }

  @Test
  public void testGuardRefusesToPutAnEngineRow() throws Exception {
    assertRefusal(guard("PUT", COSTING_ID, new JSONObject(), costingRow(Boolean.FALSE)), 403,
        ProductCostingHandler.ERR_ENGINE_ROW);
  }

  /** A null flag is not a manual row: only an explicit {@code true} unlocks the record. */
  @Test
  public void testGuardRefusesARowWhoseManualFlagIsNull() throws Exception {
    assertRefusal(guard("DELETE", COSTING_ID, null, costingRow(null)), 403,
        ProductCostingHandler.ERR_ENGINE_ROW);
  }

  @Test
  public void testGuardAllowsDeletingAManualRow() throws Exception {
    assertNull(guard("DELETE", COSTING_ID, null, costingRow(Boolean.TRUE)));
  }

  @Test
  public void testGuardAllowsUpdatingAManualRow() throws Exception {
    JSONObject body = new JSONObject();
    body.put("cost", "99");

    assertNull(guard("PATCH", COSTING_ID, body, costingRow(Boolean.TRUE)));
    assertEquals("99", body.getString("cost"));
  }

  /**
   * A manual row must not be turned into an average cost, or made permanent, after the fact — so
   * the derived columns are dropped from the update rather than rejected.
   */
  @Test
  public void testGuardStripsTheDerivedColumnsFromAnUpdateOfAManualRow() throws Exception {
    JSONObject body = new JSONObject();
    body.put("costType", "AVA");
    body.put("manual", false);
    body.put("permanent", true);
    body.put("production", true);
    body.put("cCurrencyID", "cur-usd");
    body.put("cost", "99");
    body.put("endingDate", "2027-01-01");

    assertNull(guard("PATCH", COSTING_ID, body, costingRow(Boolean.TRUE)));

    assertFalse(body.has("costType"));
    assertFalse(body.has("manual"));
    assertFalse(body.has("permanent"));
    assertFalse(body.has("production"));
    assertFalse(body.has("cCurrencyID"));
    // What the user is actually allowed to change survives.
    assertEquals("99", body.getString("cost"));
    assertEquals("2027-01-01", body.getString("endingDate"));
  }

  @Test
  public void testGuardToleratesAnUpdateWithNoBody() {
    assertNull(guard("PATCH", COSTING_ID, null, costingRow(Boolean.TRUE)));
  }

  /**
   * Fails open: a lookup that blows up must not turn every edit into a 500. The DAL's own
   * security still applies underneath.
   */
  @Test
  public void testGuardFailsOpenWhenTheLookupThrows() {
    try (MockedStatic<OBContext> obContextMock = Mockito.mockStatic(OBContext.class);
        MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(Costing.class, COSTING_ID)).thenThrow(new RuntimeException("boom"));

      assertNull(handler.handle(crudCtx("DELETE", null, COSTING_ID, null, null)));
    }
  }

  // ── defaults ───────────────────────────────────────────────────────────────

  private static NeoContext defaultsCtx(JSONObject previousBody,
      Map<String, String> queryParams) {
    return NeoContext.builder()
        .specName("product").entityName("costing")
        .httpMethod("GET").endpointType(NeoEndpointType.DEFAULTS)
        .queryParams(queryParams)
        .previousResult(previousBody == null ? null : NeoResponse.ok(previousBody))
        .build();
  }

  /**
   * Runs the {@code /defaults} post-hook with the owning product reachable through EITHER
   * transport, or neither.
   *
   * <p>The two are not interchangeable, which is the whole point of
   * {@code resolveDefaultsParentId}. On REST the hook context is rebuilt by
   * {@code NeoHookDispatcher.buildHookContext}, which never copies {@code queryParams} — only the
   * live servlet request has the parameter. Over MCP {@code McpHookExecutor} passes
   * {@code queryParams} and there is no servlet request carrying {@code parentId} at all. Reading
   * only one source silently no-ops on the other transport.
   *
   * @param fromQueryParams the id NeoContext carries (the MCP transport), or {@code null}
   * @param fromRequest the id the servlet request carries (the REST transport), or {@code null}
   */
  private NeoResponse defaults(JSONObject previousBody, String fromQueryParams,
      String fromRequest, Product product) {
    try (MockedStatic<OBContext> obContextMock = Mockito.mockStatic(OBContext.class);
        MockedStatic<RequestContext> requestMock = Mockito.mockStatic(RequestContext.class);
        MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      if (fromRequest != null) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getParameter("parentId")).thenReturn(fromRequest);
        RequestContext requestContext = mock(RequestContext.class);
        when(requestContext.getRequest()).thenReturn(request);
        requestMock.when(RequestContext::get).thenReturn(requestContext);
      }
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(Product.class, PRODUCT_ID)).thenReturn(product);
      when(dal.get(Product.class, OTHER_PRODUCT_ID)).thenReturn(null);

      Map<String, String> queryParams =
          fromQueryParams == null ? null : params("parentId", fromQueryParams);
      return handler.afterHandle(defaultsCtx(previousBody, queryParams));
    }
  }

  /** The REST transport: only the servlet request names the product. */
  private NeoResponse defaultsOverRest(JSONObject previousBody, Product product) {
    return defaults(previousBody, null, PRODUCT_ID, product);
  }

  @Test
  public void testAfterHandleIgnoresAnotherEntity() {
    NeoContext ctx = NeoContext.builder()
        .specName("product").entityName("header")
        .httpMethod("GET").endpointType(NeoEndpointType.DEFAULTS).build();
    assertNull(handler.afterHandle(ctx));
  }

  @Test
  public void testAfterHandleIgnoresANullContext() {
    assertNull(handler.afterHandle(null));
  }

  @Test
  public void testDefaultsAreLeftAloneWhenThereIsNoPreviousResult() {
    assertNull(defaultsOverRest(null, null));
  }

  @Test
  public void testDefaultsAreLeftAloneWhenTheRequestNamesNoProduct() throws Exception {
    assertNull(defaults(new JSONObject(), null, null, null));
  }

  /**
   * {@code M_Costing.DateFrom} carries no {@code AD_Column.DefaultValue}, so the generic defaults
   * service has nothing to resolve — the product's creation date has to be injected here.
   */
  @Test
  public void testDefaultsPreFillTheStartDateWithTheProductsCreationDate() throws Exception {
    Product product = mock(Product.class);
    when(product.getCreationDate()).thenReturn(dateOf(2026, 3, 15));

    NeoResponse result = defaultsOverRest(new JSONObject(), product);

    assertNotNull(result);
    JSONObject injected = result.getBody().getJSONObject("defaults");
    assertEquals("2026-03-15", injected.getString("startingDate"));
    assertEquals("STA", injected.getString("costType"));
  }

  @Test
  public void testDefaultsEnrichTheDefaultsBlockTheGenericServiceAlreadyBuilt() throws Exception {
    JSONObject previous = new JSONObject();
    previous.put("defaults", new JSONObject().put("someOtherField", "kept"));
    Product product = mock(Product.class);
    when(product.getCreationDate()).thenReturn(dateOf(2026, 1, 2));

    NeoResponse result = defaultsOverRest(previous, product);

    assertNotNull(result);
    JSONObject injected = result.getBody().getJSONObject("defaults");
    assertEquals("kept", injected.getString("someOtherField"));
    assertEquals("2026-01-02", injected.getString("startingDate"));
  }

  @Test
  public void testDefaultsStillForceTheCostTypeWhenTheProductCannotBeRead() throws Exception {
    NeoResponse result = defaultsOverRest(new JSONObject(), null);

    assertNotNull(result);
    JSONObject injected = result.getBody().getJSONObject("defaults");
    assertEquals("STA", injected.getString("costType"));
    assertFalse(injected.has("startingDate"));
  }

  @Test
  public void testDefaultsOmitTheStartDateWhenTheProductHasNoCreationDate() throws Exception {
    Product product = mock(Product.class);
    when(product.getCreationDate()).thenReturn(null);

    NeoResponse result = defaultsOverRest(new JSONObject(), product);

    assertNotNull(result);
    assertFalse(result.getBody().getJSONObject("defaults").has("startingDate"));
  }

  // ── resolveDefaultsParentId: both transports ───────────────────────────────
  //
  // The /defaults hook has to find the owning product on REST and on MCP, and the two carry it
  // in different places. Reading only one source is a silent no-op on the other transport: the
  // start date is simply not pre-filled and nothing fails anywhere.

  /**
   * The MCP transport. {@code McpHookExecutor.buildDefaultsHookContext} passes {@code queryParams}
   * and there is no servlet request behind the call, so the context is the only source.
   */
  @Test
  public void testDefaultsResolveTheProductFromTheContextQueryParams() throws Exception {
    Product product = mock(Product.class);
    when(product.getCreationDate()).thenReturn(dateOf(2026, 5, 4));

    NeoResponse result = defaults(new JSONObject(), PRODUCT_ID, null, product);

    assertNotNull(result);
    assertEquals("2026-05-04",
        result.getBody().getJSONObject("defaults").getString("startingDate"));
  }

  /**
   * The REST transport. {@code NeoHookDispatcher.buildHookContext} rebuilds the hook context
   * without {@code queryParams}, so only the live servlet request has the parameter.
   */
  @Test
  public void testDefaultsResolveTheProductFromTheServletRequestWhenTheContextHasNoParams()
      throws Exception {
    Product product = mock(Product.class);
    when(product.getCreationDate()).thenReturn(dateOf(2026, 5, 4));

    NeoResponse result = defaults(new JSONObject(), null, PRODUCT_ID, product);

    assertNotNull(result);
    assertEquals("2026-05-04",
        result.getBody().getJSONObject("defaults").getString("startingDate"));
  }

  /** With both sources populated the context wins — the request is only the fallback. */
  @Test
  public void testDefaultsPreferTheContextOverTheServletRequest() throws Exception {
    Product product = mock(Product.class);
    when(product.getCreationDate()).thenReturn(dateOf(2026, 5, 4));

    // OTHER_PRODUCT_ID resolves to no product, so a start date can only come from PRODUCT_ID.
    NeoResponse result = defaults(new JSONObject(), PRODUCT_ID, OTHER_PRODUCT_ID, product);

    assertNotNull(result);
    assertEquals("2026-05-04",
        result.getBody().getJSONObject("defaults").getString("startingDate"));
  }

  /** A blank context value is not an answer: it falls through to the request. */
  @Test
  public void testDefaultsFallBackToTheRequestWhenTheContextParamIsBlank() throws Exception {
    Product product = mock(Product.class);
    when(product.getCreationDate()).thenReturn(dateOf(2026, 5, 4));

    NeoResponse result = defaults(new JSONObject(), "   ", PRODUCT_ID, product);

    assertNotNull(result);
    assertEquals("2026-05-04",
        result.getBody().getJSONObject("defaults").getString("startingDate"));
  }

  @Test
  public void testDefaultsAreLeftAloneWhenNeitherTransportNamesAProduct() throws Exception {
    assertNull(defaults(new JSONObject(), null, null, null));
  }

  // ── the create path fails CLOSED ───────────────────────────────────────────
  //
  // Returning null from the pre-hook means "let the CRUD layer create the row". If preparation
  // blows up half way, that row is created with validate() never having run and possibly with the
  // AD defaults for costType/permanent/production — indistinguishable from one the costing engine
  // wrote, which is the single thing this class exists to prevent. So a failure here must be a
  // 500, not a shrug. (The read-only guard below is the opposite case and deliberately fails
  // open — see testGuardFailsOpenWhenTheLookupThrows.)

  /** A body that refuses to accept a write for one particular key. */
  private static final class BodyFailingOnKey extends JSONObject {
    private final String failingKey;

    BodyFailingOnKey(String failingKey) {
      this.failingKey = failingKey;
    }

    @Override
    public JSONObject put(String key, Object value) throws JSONException {
      failIfTargeted(key);
      return super.put(key, value);
    }

    @Override
    public JSONObject put(String key, boolean value) throws JSONException {
      failIfTargeted(key);
      return super.put(key, value);
    }

    private void failIfTargeted(String key) throws JSONException {
      if (failingKey.equals(key)) {
        throw new JSONException("cannot write " + key);
      }
    }
  }

  @Test
  public void testCreateFailsClosedWhenForcingTheDerivedColumnsThrows() throws Exception {
    JSONObject body = new BodyFailingOnKey("costType");
    body.put("product", PRODUCT_ID);
    body.put("cost", "12.50");

    assertRefusal(handler.handle(postCtx(body)), 500,
        ProductCostingHandler.ERR_PREPARE_FAILED);
    // Nothing was forced, which is exactly why the create must not proceed.
    assertFalse(body.has("costType"));
  }

  @Test
  public void testCreateFailsClosedWhenTheDefaultExpiryCannotBeWritten() throws Exception {
    JSONObject body = new BodyFailingOnKey("endingDate");
    body.put("product", PRODUCT_ID);
    body.put("cost", "12.50");

    assertRefusal(handler.handle(postCtx(body)), 500,
        ProductCostingHandler.ERR_PREPARE_FAILED);
  }

  @Test
  public void testCreateFailsClosedWhenTheOrganisationCurrencyLookupThrows() throws Exception {
    JSONObject body = validBody();

    try (MockedStatic<OBContext> obContextMock = Mockito.mockStatic(OBContext.class);
        MockedStatic<OBCurrencyUtils> currencyMock = Mockito.mockStatic(OBCurrencyUtils.class)) {
      currencyMock.when(() -> OBCurrencyUtils.getOrgCurrency(ORG_ID))
          .thenThrow(new RuntimeException("currency lookup exploded"));

      assertRefusal(handler.handle(crudCtx("POST", body, null, null, obContextWithOrg())), 500,
          ProductCostingHandler.ERR_PREPARE_FAILED);
    }

    // The row would have been created with no currency at all had the create been allowed on.
    assertFalse(body.has("cCurrencyID"));
  }

  /**
   * The case that made fail-open dangerous rather than merely untidy: validate() runs LAST, so a
   * failure anywhere before it means a negative cost is never checked. The refusal is what keeps
   * it out of M_Costing.
   */
  @Test
  public void testCreateDoesNotLetANegativeCostThroughWhenPreparationFails() throws Exception {
    JSONObject body = new JSONObject();
    body.put("product", PRODUCT_ID);
    body.put("cost", "-500");

    try (MockedStatic<OBContext> obContextMock = Mockito.mockStatic(OBContext.class);
        MockedStatic<OBCurrencyUtils> currencyMock = Mockito.mockStatic(OBCurrencyUtils.class)) {
      currencyMock.when(() -> OBCurrencyUtils.getOrgCurrency(ORG_ID))
          .thenThrow(new RuntimeException("currency lookup exploded"));

      NeoResponse response = handler.handle(crudCtx("POST", body, null, null, obContextWithOrg()));

      // Non-null == the CRUD layer never runs == the negative cost is never persisted.
      assertNotNull("a failed preparation must not let the create proceed", response);
      assertEquals(500, response.getHttpStatus());
    }
  }

  /** Same for an inverted date range, the other thing validate() is the only guard against. */
  @Test
  public void testCreateDoesNotLetAnInvertedDateRangeThroughWhenPreparationFails()
      throws Exception {
    JSONObject body = new JSONObject();
    body.put("product", PRODUCT_ID);
    body.put("cost", "10");
    body.put("startingDate", "2026-03-01");
    body.put("endingDate", "2026-02-01");

    try (MockedStatic<OBContext> obContextMock = Mockito.mockStatic(OBContext.class);
        MockedStatic<OBCurrencyUtils> currencyMock = Mockito.mockStatic(OBCurrencyUtils.class)) {
      currencyMock.when(() -> OBCurrencyUtils.getOrgCurrency(ORG_ID))
          .thenThrow(new RuntimeException("currency lookup exploded"));

      NeoResponse response = handler.handle(crudCtx("POST", body, null, null, obContextWithOrg()));

      assertNotNull("a failed preparation must not let the create proceed", response);
      assertEquals(500, response.getHttpStatus());
    }
  }

  /**
   * A request that names no product is still a 400, not the new 500 — the guard clause runs before
   * anything can throw, and the two carry different frontend messages.
   */
  @Test
  public void testCreateStillAnswers400WhenNoProductIsNamedRegardlessOfTheNewFailClosedPath()
      throws Exception {
    JSONObject body = new BodyFailingOnKey("costType");
    body.put("cost", "12.50");

    assertRefusal(handler.handle(postCtx(body)), 400, ProductCostingHandler.ERR_NO_PRODUCT);
  }

  // ── closing the adjacent ranges ────────────────────────────────────────────

  /**
   * Everything {@code closeAdjacentRanges} touches, stubbed together. {@code findNeighbour} runs
   * twice (previous, then next), so the two criteria are handed out in that order.
   */
  @SuppressWarnings("unchecked")
  private static final class NeighbourMocks implements AutoCloseable {
    private final MockedStatic<OBContext> obContextMock = Mockito.mockStatic(OBContext.class);
    private final MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
    private final OBDal dal = mock(OBDal.class);

    NeighbourMocks(Costing created, Costing previous, Costing next) {
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(Costing.class, COSTING_ID)).thenReturn(created);
      // Both criteria are built BEFORE the when(...) below: stubbing a mock inside an unfinished
      // when() is the classic UnfinishedStubbingException.
      OBCriteria<Costing> previousCrit = criteriaReturning(previous);
      OBCriteria<Costing> nextCrit = criteriaReturning(next);
      when(dal.createCriteria(Costing.class)).thenReturn(previousCrit, nextCrit);
    }

    private static OBCriteria<Costing> criteriaReturning(Costing row) {
      OBCriteria<Costing> crit = mock(OBCriteria.class);
      when(crit.add(any(Criterion.class))).thenReturn(crit);
      when(crit.addOrder(any(Order.class))).thenReturn(crit);
      when(crit.setMaxResults(anyInt())).thenReturn(crit);
      when(crit.setFilterOnReadableOrganization(anyBoolean())).thenReturn(crit);
      List<Costing> rows = row == null ? Collections.emptyList() : Collections.singletonList(row);
      when(crit.list()).thenReturn(rows);
      return crit;
    }

    @Override
    public void close() {
      obDalMock.close();
      obContextMock.close();
    }
  }

  private static Date dateOf(int year, int month, int day) {
    java.util.Calendar cal = java.util.Calendar.getInstance();
    cal.clear();
    cal.set(year, month - 1, day);
    return cal.getTime();
  }

  private static Costing createdRow(Date startingDate, Date endingDate) {
    Costing created = mock(Costing.class);
    when(created.getId()).thenReturn(COSTING_ID);
    when(created.getStartingDate()).thenReturn(startingDate);
    when(created.getEndingDate()).thenReturn(endingDate);
    return created;
  }

  private static Costing neighbourRow(Date startingDate, Date endingDate) {
    Costing row = mock(Costing.class);
    when(row.getStartingDate()).thenReturn(startingDate);
    when(row.getEndingDate()).thenReturn(endingDate);
    return row;
  }

  /** A POST afterHandle context whose previous result carries the created record's id. */
  private static NeoContext createdCtx(String createdId) throws Exception {
    JSONArray data = new JSONArray();
    if (createdId != null) {
      data.put(new JSONObject().put("id", createdId));
    }
    JSONObject body = new JSONObject().put("response", new JSONObject().put("data", data));
    return NeoContext.builder()
        .specName("product").entityName("costing")
        .httpMethod("POST").endpointType(NeoEndpointType.CRUD)
        .previousResult(NeoResponse.ok(body)).build();
  }

  @Test
  public void testAfterCreateDoesNothingWhenTheCreatedIdCannotBeRead() throws Exception {
    try (NeighbourMocks mocks = new NeighbourMocks(null, null, null)) {
      assertNull(handler.afterHandle(createdCtx(null)));

      verify(mocks.dal, never()).get(any(Class.class), any());
      verify(mocks.dal, never()).save(any());
    }
  }

  @Test
  public void testAfterCreateDoesNothingWhenTheCreatedRowHasNoStartDate() throws Exception {
    try (NeighbourMocks mocks = new NeighbourMocks(createdRow(null, null), null, null)) {
      assertNull(handler.afterHandle(createdCtx(COSTING_ID)));

      verify(mocks.dal, never()).save(any());
    }
  }

  /**
   * The previous cost was open-ended; it now ends where the new one starts, so the engine's
   * {@code startingDate <= date AND endingDate > date} predicate matches exactly one row per day.
   */
  @Test
  public void testAfterCreateClosesThePreviousRange() throws Exception {
    Date newStart = dateOf(2026, 6, 1);
    Costing created = createdRow(newStart, dateOf(9999, 12, 31));
    Costing previous = neighbourRow(dateOf(2026, 1, 1), dateOf(9999, 12, 31));

    try (NeighbourMocks mocks = new NeighbourMocks(created, previous, null)) {
      assertNull(handler.afterHandle(createdCtx(COSTING_ID)));

      verify(previous).setEndingDate(newStart);
      verify(mocks.dal).save(previous);
      verify(mocks.dal).flush();
    }
  }

  @Test
  public void testAfterCreateLeavesAPreviousRangeThatAlreadyEndsInTimeAlone() throws Exception {
    Costing created = createdRow(dateOf(2026, 6, 1), dateOf(9999, 12, 31));
    // Already closed before the new row starts — nothing overlaps.
    Costing previous = neighbourRow(dateOf(2026, 1, 1), dateOf(2026, 5, 1));

    try (NeighbourMocks mocks = new NeighbourMocks(created, previous, null)) {
      assertNull(handler.afterHandle(createdCtx(COSTING_ID)));

      verify(previous, never()).setEndingDate(any());
      verify(mocks.dal, never()).save(previous);
    }
  }

  /**
   * The user said "from June the cost is X" and left the expiry open, but a later cost already
   * exists — the new row is pulled back so the two do not both apply.
   */
  @Test
  public void testAfterCreateClampsItsOwnRangeToTheNextOne() throws Exception {
    Date nextStart = dateOf(2026, 9, 1);
    Costing created = createdRow(dateOf(2026, 6, 1), dateOf(9999, 12, 31));
    Costing next = neighbourRow(nextStart, dateOf(9999, 12, 31));

    try (NeighbourMocks mocks = new NeighbourMocks(created, null, next)) {
      assertNull(handler.afterHandle(createdCtx(COSTING_ID)));

      verify(created).setEndingDate(nextStart);
      verify(mocks.dal).save(created);
    }
  }

  @Test
  public void testAfterCreateClampsAnOpenEndedRowToTheNextOne() throws Exception {
    Date nextStart = dateOf(2026, 9, 1);
    Costing created = createdRow(dateOf(2026, 6, 1), null);
    Costing next = neighbourRow(nextStart, dateOf(9999, 12, 31));

    try (NeighbourMocks mocks = new NeighbourMocks(created, null, next)) {
      handler.afterHandle(createdCtx(COSTING_ID));

      verify(created).setEndingDate(nextStart);
      verify(mocks.dal).save(created);
    }
  }

  @Test
  public void testAfterCreateLeavesItsOwnRangeAloneWhenItAlreadyEndsBeforeTheNextOne()
      throws Exception {
    Costing created = createdRow(dateOf(2026, 6, 1), dateOf(2026, 7, 1));
    Costing next = neighbourRow(dateOf(2026, 9, 1), dateOf(9999, 12, 31));

    try (NeighbourMocks mocks = new NeighbourMocks(created, null, next)) {
      handler.afterHandle(createdCtx(COSTING_ID));

      verify(created, never()).setEndingDate(any());
      verify(mocks.dal, never()).save(created);
    }
  }

  @Test
  public void testAfterCreateClosesBothNeighboursAtOnce() throws Exception {
    Date newStart = dateOf(2026, 6, 1);
    Date nextStart = dateOf(2026, 9, 1);
    Costing created = createdRow(newStart, dateOf(9999, 12, 31));
    Costing previous = neighbourRow(dateOf(2026, 1, 1), dateOf(9999, 12, 31));
    Costing next = neighbourRow(nextStart, dateOf(9999, 12, 31));

    try (NeighbourMocks mocks = new NeighbourMocks(created, previous, next)) {
      handler.afterHandle(createdCtx(COSTING_ID));

      verify(previous).setEndingDate(newStart);
      verify(created).setEndingDate(nextStart);
      verify(mocks.dal).save(previous);
      verify(mocks.dal).save(created);
      verify(mocks.dal).flush();
    }
  }

  @Test
  public void testAfterCreateSavesNothingWhenTheProductHasNoOtherCostLine() throws Exception {
    Costing created = createdRow(dateOf(2026, 6, 1), dateOf(9999, 12, 31));

    try (NeighbourMocks mocks = new NeighbourMocks(created, null, null)) {
      assertNull(handler.afterHandle(createdCtx(COSTING_ID)));

      verify(mocks.dal, never()).save(any());
      verify(mocks.dal).flush();
    }
  }

  /**
   * Best-effort by design: the cost line is already created and valid on its own, so failing to
   * tidy its neighbours must not turn a successful save into an error the user sees.
   */
  @Test
  public void testAfterCreateSwallowsAFailureWhileTidyingTheNeighbours() throws Exception {
    try (MockedStatic<OBContext> obContextMock = Mockito.mockStatic(OBContext.class);
        MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(Costing.class, COSTING_ID)).thenThrow(new RuntimeException("boom"));

      assertNull(handler.afterHandle(createdCtx(COSTING_ID)));
    }
  }

  /** A read is not a create: the range tidying must not run on a GET. */
  @Test
  public void testAfterHandleDoesNotTidyRangesOnAGet() throws Exception {
    JSONObject body = new JSONObject().put("response",
        new JSONObject().put("data", new JSONArray().put(new JSONObject().put("id", COSTING_ID))));
    NeoContext ctx = NeoContext.builder()
        .specName("product").entityName("costing")
        .httpMethod("GET").endpointType(NeoEndpointType.CRUD)
        .previousResult(NeoResponse.ok(body)).build();

    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);

      assertNull(handler.afterHandle(ctx));

      verify(dal, never()).get(any(Class.class), any());
    }
  }

  // ── Default list order (ETP-5245) ──────────────────────────────────────────

  /**
   * The Costing tab must open most-recent-first. The order is injected as the DAL
   * `_sortBy` query param — NOT applied to the returned rows — so it becomes the
   * query's own ORDER BY and therefore survives pagination
   * (`NeoCrudHandler.applyPaginationDefaults` caps an unpaginated list at 100 rows,
   * and the costing engine writes one M_Costing row per transaction).
   */
  @Test
  public void testListGetDefaultsToMostRecentFirst() {
    Map<String, String> queryParams = new HashMap<>();
    queryParams.put("parentId", PRODUCT_ID);

    assertNull(handler.handle(crudCtx("GET", null, null, queryParams, null)));

    assertEquals(ProductCostingHandler.DEFAULT_SORT_BY, queryParams.get("_sortBy"));
  }

  /** startingDate leads (the cost in force on top), creationDate breaks the tie. */
  @Test
  public void testDefaultSortOrdersByStartingDateThenCreationDate() {
    assertEquals("startingDate desc,creationDate desc", ProductCostingHandler.DEFAULT_SORT_BY);
  }

  /** An explicit sort from the UI, the REST API or the MCP always wins. */
  @Test
  public void testExplicitSortByIsNeverOverwritten() {
    Map<String, String> queryParams = new HashMap<>();
    queryParams.put("_sortBy", "cost");

    assertNull(handler.handle(crudCtx("GET", null, null, queryParams, null)));

    assertEquals("cost", queryParams.get("_sortBy"));
  }

  /** A blank value is not a preference — it must still be defaulted. */
  @Test
  public void testBlankSortByIsTreatedAsAbsent() {
    Map<String, String> queryParams = new HashMap<>();
    queryParams.put("_sortBy", "   ");

    assertNull(handler.handle(crudCtx("GET", null, null, queryParams, null)));

    assertEquals(ProductCostingHandler.DEFAULT_SORT_BY, queryParams.get("_sortBy"));
  }

  /** A by-id GET has a single row: there is nothing to order. */
  @Test
  public void testByIdGetIsNotSorted() {
    Map<String, String> queryParams = new HashMap<>();

    handler.handle(crudCtx("GET", null, COSTING_ID, queryParams, null));

    assertFalse(queryParams.containsKey("_sortBy"));
  }

  /** Only a read is ordered — a write must not acquire a sort param. */
  @Test
  public void testWriteMethodsAreNotSorted() throws Exception {
    for (String method : new String[] { "PUT", "PATCH", "DELETE" }) {
      Map<String, String> queryParams = new HashMap<>();
      handler.handle(crudCtx(method, null, null, queryParams, null));
      assertFalse("method " + method, queryParams.containsKey("_sortBy"));
    }
  }

  /** A context carrying no query-param map at all must not blow up. */
  @Test
  public void testNullQueryParamsIsANoOp() {
    assertNull(handler.handle(crudCtx("GET", null, null, null, null)));
  }

  /** Sorting belongs to CRUD; the defaults endpoint has no list to order. */
  @Test
  public void testDefaultsEndpointIsNotSorted() {
    Map<String, String> queryParams = new HashMap<>();
    NeoContext ctx = NeoContext.builder()
        .specName("product").entityName("costing")
        .httpMethod("GET").endpointType(NeoEndpointType.DEFAULTS)
        .queryParams(queryParams).build();

    assertNull(handler.handle(ctx));

    assertFalse(queryParams.containsKey("_sortBy"));
  }
}
