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

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.inject.Named;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.criterion.Restrictions;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.client.kernel.RequestContext;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.plm.Product;
import org.openbravo.model.common.uom.UOM;
import org.openbravo.model.financialmgmt.tax.TaxCategory;
import org.openbravo.model.materialmgmt.cost.Costing;
import org.openbravo.model.pricing.pricelist.PriceListVersion;
import org.openbravo.model.pricing.pricelist.ProductPrice;

/**
 * NeoHandler for the {@code product} header entity (ETP-4670).
 *
 * <p>Restores the default-value resolution for {@code uOM} ({@code C_UOM_ID}) and
 * {@code taxCategory} ({@code C_TaxCategory_ID}) that used to live as an {@code @SQL=} expression
 * directly on {@code AD_Column.DefaultValue} for those two Core dictionary columns. That DB patch
 * was applied by temporarily flipping the Core module's {@code IsInDevelopment} flag to write
 * directly to a system column — architecturally wrong, since it changes Core dictionary behavior
 * for every Etendo installation (Classic/Enterprise included), not just Etendo GO. This handler
 * reimplements the same COALESCE semantics (client's own default row, falling back to the System
 * client {@code '0'}) purely at the NEO Headless layer, scoped to the {@code product} spec only.
 *
 * <p>Both {@link UOM#PROPERTY_DEFAULT} ("default", column {@code IsDefault}) reference tables
 * ({@code C_UOM}, {@code C_TaxCategory}) are TableDir (reference id {@code 19}) columns. Without
 * this handler, the generic NEO fallback ({@code NeoDefaultsService#resolveFirstComboOption} /
 * {@code tryInjectFirstFromLookup}) would silently pick whichever row sorts first alphabetically —
 * not necessarily the one flagged {@code IsDefault='Y'} — both on the {@code /defaults} preview
 * endpoint and on record creation.
 *
 * <p>Registered via {@code ETGO_SF_ENTITY.Java_Qualifier = "productDefaultsHandler"} on the
 * {@code product} header entity. No other handler was registered for that entity/qualifier before
 * this change, so a brand-new handler class was used rather than extending an existing one.
 *
 * <p>ETP-4967: also hides any product classified under a category flagged
 * {@code em_etgo_issystemcategory = 'Y'}. ETP-5009 moved the list half into the query
 * ({@link #readPredicates}), so the list, its count, its paging and the {@code ?_distinct=} filter
 * values all agree; a read by id is still hidden by {@link #hideSystemCategoryProducts}.
 *
 * <p>ETP-4943 / ETP-5091: also forces {@code stocked}/{@code returnable} to {@code false}
 * whenever a request declares {@code productType} as Service ({@code "S"}), Expense
 * ({@code "E"}, "Gasto") or Resource ({@code "R"}, "Recurso") — see
 * {@link #enforceNonStockableProductTypes}. Runs on both POST and PATCH, unlike the
 * uOM/taxCategory defaulting above (POST-only): the frontend's own auto-correction
 * (`ProductAdditionalInfoPanel.jsx`) only fires while the "Additional Info" tab is mounted, so a
 * user who sets the type to one of these on the "General" tab and saves immediately never
 * triggers it. This is the authoritative enforcement; the frontend one is a same-session UX
 * nicety, not the guarantee.
 */
@Named("productDefaultsHandler")
public class ProductDefaultsHandler implements NeoHandler {

  private static final Logger log = LogManager.getLogger(ProductDefaultsHandler.class);

  private static final String SPEC = "product";
  private static final String METHOD_POST = "POST";
  private static final String METHOD_PATCH = "PATCH";
  private static final String METHOD_GET = "GET";
  private static final String FIELD_UOM = "uOM";
  private static final String FIELD_TAX_CATEGORY = "taxCategory";
  private static final String FIELD_PRODUCT_CATEGORY = "productCategory";
  private static final String FIELD_PRODUCT_TYPE = "productType";
  private static final String FIELD_STOCKED = "stocked";
  private static final String FIELD_RETURNABLE = "returnable";
  // ETP-4943 (Service) / ETP-5091 (Expense, Resource) — none of these product types have a
  // physical existence in inventory.
  private static final Set<String> NON_STOCKABLE_PRODUCT_TYPES = Set.of("S", "E", "R");
  /**
   * ETP-5680: tax category the Product window pairs with each product type, matched by
   * {@code C_TaxCategory.Name} inside the tenant (the names the Spanish accounting package seeds).
   * A name the tenant does not have resolves to nothing and the caller keeps its own value.
   */
  static final String TAX_CATEGORY_NAME_SERVICE = "IVA Normal Servicios";
  static final String TAX_CATEGORY_NAME_GOODS = "IVA Normal";
  /** Query parameter of {@code /product/defaults} carrying the product type to resolve for. */
  static final String PARAM_PRODUCT_TYPE = "productType";
  /**
   * Key added to the {@code /defaults} payload when {@link #PARAM_PRODUCT_TYPE} is sent: the id
   * (plus {@code $_identifier}) of the type's tax category, present ONLY when the tenant has it.
   * Distinct from {@code taxCategory} (the IsDefault fallback) so the UI can tell "no match".
   */
  static final String DEFAULT_KEY_TYPE_TAX_CATEGORY = "typeTaxCategory";
  private static final String FIELD_TOTAL_ROWS = "totalRows";
  private static final String FIELD_END_ROW = "endRow";
  private static final String SYSTEM_CLIENT_ID = "0";
  private static final String FIELD_RESPONSE = "response";
  /** Display-only flag consumed by the Product window's cost banner and save gate. */
  private static final String FIELD_HAS_COST = "etgoHasCost";
  /**
   * ETP-5676: create-body directive. {@code true} skips {@link #seedDefaultPrices} for this
   * product. Read and removed by {@link #handle} before the generic CRUD path sees the body, so it
   * never reaches the field filter or the DAL.
   */
  static final String DIRECTIVE_SKIP_PRICES_SEED = "_skipPricesSeed";
  /** In a parsed {@link #DIRECTIVE_SKIP_PRICES_SEED}: skip both directions, whatever the tariff. */
  static final String SKIP_ALL_TARIFFS = "*";
  /** Carries {@link #DIRECTIVE_SKIP_PRICES_SEED} from the pre-hook to {@link #afterHandle}. */
  private static final String ATTR_SKIP_PRICES_SEED =
      ProductDefaultsHandler.class.getName() + ".skipPricesSeed";
  /**
   * ETP-5009: excludes products whose category is flagged {@code EM_Etgo_IsSystemCategory = 'Y'}
   * (DAL property {@code etgoIssystemcategory}, a Yes/No column mapped as {@code Boolean} — the
   * same property {@code ProductCategorySystemFlagSelectorPolicy} already filters on). A
   * server-side constant: nothing in it comes from the request.
   *
   * <p>A correlated {@code not exists} on the FK id rather than the implicit path
   * {@code e.productCategory.etgoIssystemcategory}, so it adds no join to the list query nor to
   * the {@code SELECT DISTINCT} of the {@code ?_distinct=} fetch. It is not scoped to the current
   * client the way {@link SystemCategoryIds} is: a product only ever references a category it can
   * see, so a system category of any client is one this product must not be listed under.
   */
  static final String EXCLUDE_SYSTEM_CATEGORY_PREDICATE =
      "not exists (select 1 from ProductCategory pc where pc.id = e.productCategory.id"
          + " and pc.etgoIssystemcategory = true)";

  @Override
  public NeoResponse handle(NeoContext context) {
    if (context == null || !SPEC.equals(context.getSpecName())) {
      return null;
    }
    String method = context.getHttpMethod();
    boolean isCreate = METHOD_POST.equals(method);
    boolean isUpdate = METHOD_PATCH.equals(method);
    if (!isCreate && !isUpdate) {
      return null;
    }
    JSONObject body = context.getRequestBody();
    if (body == null) {
      return null;
    }
    // Stripped on PATCH too, so the directive never reaches the generic write path; honoured on
    // create only, the one place a price is seeded.
    Set<String> skipPricesSeed = consumeSkipPricesSeed(body);
    if (isCreate && !skipPricesSeed.isEmpty()) {
      context.setAttribute(ATTR_SKIP_PRICES_SEED, skipPricesSeed);
    }
    try {
      enforceNonStockableProductTypes(body);
    } catch (Exception e) {
      log.error("product pre-hook: failed to enforce non-stockable product type stock flags", e);
    }
    if (isCreate) {
      try {
        String clientId = resolveClientId(context);
        injectIfMissing(body, FIELD_UOM, UOM.class, clientId);
        injectTaxCategoryIfMissing(body, clientId);
      } catch (Exception e) {
        log.error("product pre-hook: failed to inject uOM/taxCategory default", e);
      }
    }
    // Never short-circuits: the generic CRUD create/update still runs, now with the request
    // body already corrected (so the generic "first combo option" fallback never fires for
    // uOM/taxCategory, and a Service product can never persist as stocked/returnable).
    return null;
  }

  /**
   * ETP-5676: reads and removes {@link #DIRECTIVE_SKIP_PRICES_SEED} from a write body.
   *
   * <p>For a caller that sends its own prices in the same request — the products import writes a
   * sales and a purchase price op right after the product op. Seeding zero rows first only for
   * {@code ProductPriceHandler} to find and overwrite them doubled every price write, and cost a
   * flush plus core's price observer per row.
   *
   * <p>Two forms. An array of price list version ids — what the import sends: the versions the
   * same request prices — skips a direction only when the version the seed would use for it is
   * listed. Once a user clears the default flag on a direction, the caller (selector order) and
   * {@link PriceListVersionResolver} (org before {@code *}) can fall back to different lists, so
   * this is the form that cannot leave the server's default tariff without a row. {@code true} skips both directions unconditionally.
   *
   * @param body the request body, mutated in place
   * @return the listed version ids, {@link #SKIP_ALL_TARIFFS} alone for {@code true}, or an empty
   *     set when the seed must run as usual
   */
  static Set<String> consumeSkipPricesSeed(JSONObject body) {
    if (body == null || !body.has(DIRECTIVE_SKIP_PRICES_SEED)) {
      return Set.of();
    }
    Object raw = body.opt(DIRECTIVE_SKIP_PRICES_SEED);
    body.remove(DIRECTIVE_SKIP_PRICES_SEED);
    if (raw instanceof JSONArray) {
      JSONArray ids = (JSONArray) raw;
      Set<String> listed = new java.util.HashSet<>();
      for (int i = 0; i < ids.length(); i++) {
        String id = StringUtils.trimToNull(ids.optString(i, null));
        if (id != null && !SKIP_ALL_TARIFFS.equals(id)) {
          listed.add(id);
        }
      }
      return listed;
    }
    return Boolean.TRUE.equals(raw) || "true".equals(raw) ? Set.of(SKIP_ALL_TARIFFS) : Set.of();
  }

  /**
   * ETP-5676: published to agents by {@code etendo_schema(view:"create")} and accepted by the MCP
   * write verbs without being reported as an unknown field.
   */
  @Override
  public Map<String, String> createDirectives() {
    return Map.of(DIRECTIVE_SKIP_PRICES_SEED,
        "Optional. An array of price list version ids this same request (e.g. the same "
            + "etendo_batch) prices the product on: the new product is not given the zero-priced row "
            + "on a default sales/purchase tariff whose version is in the list — your price op "
            + "writes it instead. A default tariff not in the list is still seeded. true skips "
            + "both unconditionally (only when you price both default tariffs yourself). Create "
            + "only: accepted and ignored on etendo_update.");
  }

  /**
   * ETP-4943 / ETP-5091: Service, Expense and Resource products have no physical existence, so
   * none of them can ever be stocked or returnable. Forces both flags to {@code false} whenever
   * the request itself declares {@code productType} as one of {@link #NON_STOCKABLE_PRODUCT_TYPES}
   * — whether or not the caller sent a value for them, so an omitted flag can't silently resolve
   * to {@code true} downstream. Deliberately scoped to requests that mention {@code productType}:
   * a PATCH that edits something else on an already-non-stockable product (e.g. weight) and never
   * touches the type is left alone — resolving the persisted type would need a DB lookup, out of
   * scope for this ticket's reported cases (all of which change {@code productType} in the same
   * request).
   */
  static void enforceNonStockableProductTypes(JSONObject body) throws JSONException {
    String productType = body.optString(FIELD_PRODUCT_TYPE, null);
    if (productType == null || !NON_STOCKABLE_PRODUCT_TYPES.contains(productType)) {
      return;
    }
    body.put(FIELD_STOCKED, false);
    body.put(FIELD_RETURNABLE, false);
  }

  /**
   * ETP-5009: keeps system-category products out of every list read — REST list and count, the
   * {@code ?_distinct=} filter values, MCP {@code etendo_list} — by putting the exclusion into the
   * query. Replaces the list half of {@link #hideSystemCategoryProducts}, which post-filtered a
   * page core had already cut and counted, and which the distinct fetch never reached at all: the
   * Product window's Categoría filter offered "Discounts" and its Tipo filter offered Servicio,
   * both carried only by the hidden {@code ETGO_DTO} product.
   */
  @Override
  public List<String> readPredicates(NeoContext context) {
    if (context == null || !SPEC.equals(context.getSpecName())) {
      return List.of();
    }
    return List.of(EXCLUDE_SYSTEM_CATEGORY_PREDICATE);
  }

  @Override
  public NeoResponse afterHandle(NeoContext context) {
    if (context == null || !SPEC.equals(context.getSpecName())) {
      return null;
    }
    if (NeoEndpointType.DEFAULTS.equals(context.getEndpointType())) {
      return injectDefaults(context);
    }
    if (NeoEndpointType.CRUD.equals(context.getEndpointType())) {
      if (METHOD_POST.equals(context.getHttpMethod()) && !skipsAllTariffs(context)) {
        seedDefaultPrices(context);
      }
      return annotateCostPresence(context, hideSystemCategoryProducts(context));
    }
    return null;
  }

  /**
   * ETP-5245: tells the UI whether this product already has a cost defined, as
   * {@code etgoHasCost} on the record.
   *
   * <p>A stockable product with no cost line is a blocking condition: the Product window shows a
   * warning banner and refuses to save until one exists. Both the banner and the save gate need
   * the same answer, and neither should have to fetch the Costing tab to get it — so the backend
   * states it once, on the record, the way {@code pisLocked} does for payments. Like that flag it
   * is emitted, never declared in {@code decisions.json}; it is display-only and is not part of
   * any save payload.
   *
   * <p>Annotates every single-record response — read, create and update alike. A list GET is
   * skipped: it would cost one count per row and no list view needs it.
   *
   * @param context the CRUD GET context
   * @param filtered the response {@link #hideSystemCategoryProducts} produced, or {@code null}
   *     when it left the previous result untouched
   * @return the response to return from {@code afterHandle}
   */
  private NeoResponse annotateCostPresence(NeoContext context, NeoResponse filtered) {
    // Every single-record response, not just a read: the create and update responses carry the
    // record the form keeps showing, so skipping them left a freshly created product with the
    // flag absent — and an absent flag means "has a cost", so the banner stayed hidden until the
    // user reloaded the page. Only a GET list is excluded, where this would be one count per row.
    if (METHOD_GET.equals(context.getHttpMethod()) && !context.isReadById()) {
      return filtered;
    }
    // ETP-5676: a batch sent with _onlyReturnIds reads only the id; the flag is for the form.
    if (context.isIdsOnlyResponse()) {
      return filtered;
    }
    NeoResponse target = filtered != null ? filtered : context.getPreviousResult();
    if (target == null || target.getBody() == null) {
      return filtered;
    }
    try {
      JSONObject response = target.getBody().optJSONObject(FIELD_RESPONSE);
      JSONArray data = response != null ? response.optJSONArray("data") : null;
      if (data == null) {
        return filtered;
      }
      OBContext.setAdminMode();
      try {
        for (int i = 0; i < data.length(); i++) {
          JSONObject row = data.optJSONObject(i);
          if (row != null) {
            row.put(FIELD_HAS_COST, hasCostDefined(row.optString("id", null)));
          }
        }
      } finally {
        OBContext.restorePreviousMode();
      }
      return target;
    } catch (Exception e) {
      log.error("product afterHandle: could not annotate cost presence", e);
      return filtered;
    }
  }

  /**
   * Whether the product has at least one active {@code M_Costing} row.
   *
   * <p>Counts every row, engine-generated ones included: the question the banner asks is "does
   * this product have a cost at all", not "did a human type one".
   *
   * @param productId the product to check
   * @return {@code true} when at least one active cost row exists
   */
  private boolean hasCostDefined(String productId) {
    if (StringUtils.isBlank(productId)) {
      return false;
    }
    OBCriteria<Costing> crit = OBDal.getInstance().createCriteria(Costing.class);
    crit.setFilterOnReadableOrganization(false);
    crit.add(Restrictions.eq(Costing.PROPERTY_PRODUCT + ".id", productId));
    crit.add(Restrictions.eq(Costing.PROPERTY_ACTIVE, true));
    crit.setMaxResults(1);
    return !crit.list().isEmpty();
  }

  /**
   * ETP-5245: gives a brand-new product a zero-priced row on each of the tenant's default
   * tariffs, so it is never invisible to sales and purchase for lack of a price.
   *
   * <p>Runs as a post-hook because it needs the id the CRUD layer just assigned, read from
   * {@code previousResult} the same way {@code PriceListHeaderHandler#afterHandle} reads it.
   * {@code NeoServletSupport#handleWithHooks} skips {@code afterHandle} entirely when the create
   * failed, so a rejected product never leaves orphan prices behind.
   *
   * <p>Deliberately best-effort: a tenant with no default tariff configured, or a price that
   * cannot be written, must not turn a successful product creation into an error. The failure is
   * logged and the product stands.
   *
   * <p>Idempotent by construction — it skips any tariff the product is already priced on. That
   * matters beyond retries: the products import posts its own price for the same tariff in the
   * same {@code /batch} call, and {@code ProductPriceHandler} turns that second write into an
   * update of the row seeded here rather than a duplicate. ETP-5676: such a caller should send
   * {@link #DIRECTIVE_SKIP_PRICES_SEED} instead, so the price is written once.
   *
   * @param context the CRUD POST context whose previous result carries the created product
   */
  private void seedDefaultPrices(NeoContext context) {
    String productId = extractCreatedRecordId(context);
    if (StringUtils.isBlank(productId)) {
      return;
    }
    try {
      OBContext.setAdminMode();
      try {
        Product product = OBDal.getInstance().get(Product.class, productId);
        if (product == null) {
          return;
        }
        for (boolean salesPriceList : new boolean[] { true, false }) {
          seedPriceOnDefaultTariff(context, product, salesPriceList);
        }
        OBDal.getInstance().flush();
      } finally {
        OBContext.restorePreviousMode();
      }
    } catch (Exception e) {
      log.error("product afterHandle: could not seed default prices for product {}",
          productId, e);
    }
  }

  /** The {@link #DIRECTIVE_SKIP_PRICES_SEED} the pre-hook kept for this operation; empty if none. */
  @SuppressWarnings("unchecked")
  private static Set<String> skippedTariffs(NeoContext context) {
    Object kept = context.getAttribute(ATTR_SKIP_PRICES_SEED);
    return kept instanceof Set ? (Set<String>) kept : Set.of();
  }

  private static boolean skipsAllTariffs(NeoContext context) {
    return skippedTariffs(context).contains(SKIP_ALL_TARIFFS);
  }

  /**
   * Creates the zero-priced row for one direction, unless the product already has one there.
   *
   * @param context the CRUD context, used for its client/organisation scope
   * @param product the product just created
   * @param salesPriceList {@code true} for the sales tariff, {@code false} for the purchase one
   */
  private void seedPriceOnDefaultTariff(NeoContext context, Product product,
      boolean salesPriceList) {
    String versionId = resolveDefaultVersionId(context.getObContext(), salesPriceList);
    if (StringUtils.isBlank(versionId)) {
      log.debug("No default {} price list configured; product {} gets no seeded price",
          salesPriceList ? "sales" : "purchase", product.getId());
      return;
    }
    if (skippedTariffs(context).contains(versionId)) {
      // ETP-5676: the same request prices this very version itself.
      return;
    }
    if (ProductHandlerUtils.findExistingPrice(product.getId(), versionId) != null) {
      return;
    }
    PriceListVersion version = OBDal.getInstance().get(PriceListVersion.class, versionId);
    if (version == null) {
      return;
    }
    ProductPrice price = OBProvider.getInstance().get(ProductPrice.class);
    price.setNewOBObject(true);
    price.setClient(product.getClient());
    // The product's organisation, not the tariff's. A default tariff often lives in the shared
    // organisation '0'; putting the price row there while it points at a product in a child
    // organisation is the direction Etendo's organisation tree forbids. Owned by the child, it
    // can reference an ancestor's price list version safely.
    price.setOrganization(product.getOrganization());
    price.setProduct(product);
    price.setPriceListVersion(version);
    price.setStandardPrice(BigDecimal.ZERO);
    price.setListPrice(BigDecimal.ZERO);
    price.setPriceLimit(BigDecimal.ZERO);
    OBDal.getInstance().save(price);
  }

  /**
   * The default tariff version for one direction, resolved once per batch request (ETP-5676).
   * Keyed by client and current organisation — the two inputs
   * {@link PriceListVersionResolver#resolveDefaultVersionId} reads from the context.
   */
  private static String resolveDefaultVersionId(OBContext obContext, boolean salesPriceList) {
    String clientId = obContext != null && obContext.getCurrentClient() != null
        ? obContext.getCurrentClient().getId() : null;
    String orgId = obContext != null && obContext.getCurrentOrganization() != null
        ? obContext.getCurrentOrganization().getId() : null;
    return NeoBatchScope.memoize(
        ProductDefaultsHandler.class.getName() + "|priceListVersion|" + salesPriceList + "|"
            + clientId + "|" + orgId,
        () -> PriceListVersionResolver.resolveDefaultVersionId(obContext, salesPriceList));
  }

  /**
   * Reads the id of the record the default CRUD layer just created.
   *
   * @param context the context whose previous result holds the create response
   * @return the new record id, or {@code null} when the response does not carry one
   */
  private String extractCreatedRecordId(NeoContext context) {
    NeoResponse previous = context.getPreviousResult();
    if (previous == null || previous.getBody() == null) {
      return null;
    }
    JSONObject response = previous.getBody().optJSONObject(FIELD_RESPONSE);
    if (response == null) {
      return null;
    }
    JSONArray data = response.optJSONArray("data");
    if (data == null || data.length() == 0) {
      return null;
    }
    JSONObject row = data.optJSONObject(0);
    return row != null ? StringUtils.trimToNull(row.optString("id", null)) : null;
  }

  private NeoResponse injectDefaults(NeoContext context) {
    NeoResponse previous = context.getPreviousResult();
    if (previous == null || previous.getBody() == null) {
      return null;
    }
    try {
      String clientId = resolveClientId(context);
      JSONObject body = previous.getBody();
      JSONObject defaults = body.optJSONObject("defaults");
      if (defaults == null) {
        defaults = new JSONObject();
        body.put("defaults", defaults);
      }
      overwriteDefault(defaults, FIELD_UOM, UOM.class, clientId);
      overwriteDefault(defaults, FIELD_TAX_CATEGORY, TaxCategory.class, clientId);
      injectTypeTaxCategory(defaults, context, clientId);
      return NeoResponse.ok(body);
    } catch (Exception e) {
      log.error("product afterHandle: failed to inject uOM/taxCategory default", e);
      return null;
    }
  }

  /**
   * ETP-4967: strips a product classified under a category flagged {@code
   * em_etgo_issystemcategory = 'Y'} (see {@link SystemCategoryIds}) from a single-record GET, so
   * e.g. {@code ETGO_DTO} (category "Discounts") cannot be opened in the Product window either.
   *
   * <p>ETP-5009: single-record reads only. A list read is already restricted in the query by
   * {@link #readPredicates}; a read by id is not, because core resolves it with its own
   * {@code id = :id} query and ignores the where clause, so this post-filter is still what hides
   * the record there. "Read by id" is {@link NeoContext#isReadById}: the path id <em>and</em> the
   * query-string form {@code GET …/product/product?id=X}, which core fetches by id just the same.
   */
  private NeoResponse hideSystemCategoryProducts(NeoContext context) {
    if (!context.isReadById()) {
      return null;
    }
    NeoResponse previous = context.getPreviousResult();
    if (previous == null || previous.getBody() == null) {
      return null;
    }
    try {
      JSONObject body = previous.getBody();
      JSONObject responseWrapper = body.optJSONObject(FIELD_RESPONSE);
      JSONArray dataArr = responseWrapper != null ? responseWrapper.optJSONArray("data") : null;
      if (dataArr == null || dataArr.length() == 0) {
        return null;
      }
      Set<String> hiddenCategoryIds = resolveHiddenCategoryIds(context);
      if (hiddenCategoryIds.isEmpty()) {
        return null;
      }
      FilterOutcome outcome = filterHiddenCategoryRows(dataArr, hiddenCategoryIds);
      if (outcome.removedCount() == 0) {
        return null;
      }
      responseWrapper.put("data", outcome.filtered());
      adjustRowCounts(responseWrapper, outcome.removedCount());
      return NeoResponse.ok(body);
    } catch (Exception e) {
      log.warn("product afterHandle: failed to hide system-category products: {}", e.getMessage());
      return null;
    }
  }

  /** The current request's client's system-flagged category ids, or empty when unresolvable. */
  private static Set<String> resolveHiddenCategoryIds(NeoContext context) {
    String clientId = resolveClientId(context);
    if (StringUtils.isBlank(clientId)) {
      return Set.of();
    }
    return SystemCategoryIds.resolve(clientId);
  }

  /** Result of {@link #filterHiddenCategoryRows}: the surviving rows and how many were cut. */
  private record FilterOutcome(JSONArray filtered, int removedCount) {
  }

  /**
   * Removes rows whose {@code productCategory} is in {@code hiddenCategoryIds} from
   * {@code dataArr}.
   */
  private static FilterOutcome filterHiddenCategoryRows(JSONArray dataArr,
      Set<String> hiddenCategoryIds) {
    JSONArray filtered = new JSONArray();
    int removedCount = 0;
    for (int i = 0; i < dataArr.length(); i++) {
      JSONObject row = dataArr.optJSONObject(i);
      if (row == null) {
        continue;
      }
      if (hiddenCategoryIds.contains(row.optString(FIELD_PRODUCT_CATEGORY, ""))) {
        removedCount++;
      } else {
        filtered.put(row);
      }
    }
    return new FilterOutcome(filtered, removedCount);
  }

  /**
   * {@code response.totalRows}/{@code endRow} were computed before {@link #filterHiddenCategoryRows}
   * ran — decrement them by {@code removedCount} so the emptied single-record response does not
   * still claim to hold a row.
   */
  private static void adjustRowCounts(JSONObject responseWrapper, int removedCount)
      throws JSONException {
    if (responseWrapper.has(FIELD_TOTAL_ROWS)) {
      responseWrapper.put(FIELD_TOTAL_ROWS,
          Math.max(0, responseWrapper.optInt(FIELD_TOTAL_ROWS, 0) - removedCount));
    }
    if (responseWrapper.has(FIELD_END_ROW)) {
      responseWrapper.put(FIELD_END_ROW,
          Math.max(0, responseWrapper.optInt(FIELD_END_ROW, 0) - removedCount));
    }
  }

  /**
   * Injects the resolved default id into {@code body[propertyName]} only when the caller did not
   * already provide a value for it — an explicit user selection always wins.
   */
  private void injectIfMissing(JSONObject body, String propertyName,
      Class<? extends BaseOBObject> entityClass, String clientId) throws JSONException {
    if (body.has(propertyName) && StringUtils.isNotBlank(body.optString(propertyName, null))) {
      return;
    }
    String defaultId = resolveDefaultId(entityClass, clientId);
    if (defaultId != null) {
      body.put(propertyName, defaultId);
    }
  }

  /**
   * ETP-5680: tax category name for a product type — Service pairs with the services rate, every
   * other type (Item, Resource, Expense) with the general one. {@code null} for a blank type.
   */
  static String taxCategoryNameForType(String productType) {
    if (StringUtils.isBlank(productType)) {
      return null;
    }
    return "S".equals(productType.trim()) ? TAX_CATEGORY_NAME_SERVICE : TAX_CATEGORY_NAME_GOODS;
  }

  /**
   * ETP-5680: create-time fill of {@code taxCategory}. An explicit value always wins; otherwise the
   * category matching the body's {@code productType} by name, falling back to the IsDefault row
   * when the body has no type or the tenant lacks the named category. Create only — a PATCH never
   * recalculates the category.
   */
  private void injectTaxCategoryIfMissing(JSONObject body, String clientId) throws JSONException {
    if (body.has(FIELD_TAX_CATEGORY) && StringUtils.isNotBlank(body.optString(FIELD_TAX_CATEGORY, null))) {
      return;
    }
    String typeId = resolveTaxCategoryIdForType(body.optString(FIELD_PRODUCT_TYPE, null), clientId);
    if (typeId != null) {
      body.put(FIELD_TAX_CATEGORY, typeId);
      return;
    }
    injectIfMissing(body, FIELD_TAX_CATEGORY, TaxCategory.class, clientId);
  }

  /**
   * ETP-5680: makes the {@code /defaults} payload carry the tax category that matches the type.
   *
   * <ul>
   *   <li>With a {@code productType} request parameter (the form re-asking after the user changed
   *       the type): adds {@link #DEFAULT_KEY_TYPE_TAX_CATEGORY} (+ {@code $_identifier}) when the
   *       tenant has the named category; the payload is left alone otherwise, which is the UI's
   *       signal to keep the field as it is.</li>
   *   <li>Without it (a new form opening): the type is the one the payload itself defaults to
   *       ({@code defaults.productType}, Item), and {@code taxCategory} is overwritten with that
   *       type's category, so a new Item starts as "IVA Normal" rather than as whatever row is
   *       flagged IsDefault. A tenant without the named category keeps the IsDefault value.</li>
   * </ul>
   */
  private void injectTypeTaxCategory(JSONObject defaults, NeoContext context, String clientId)
      throws JSONException {
    String requested = requestedProductType(context);
    String productType = requested != null ? requested
        : StringUtils.trimToNull(defaults.optString(FIELD_PRODUCT_TYPE, null));
    String id = resolveTaxCategoryIdForType(productType, clientId);
    if (id == null) {
      return;
    }
    String key = requested != null ? DEFAULT_KEY_TYPE_TAX_CATEGORY : FIELD_TAX_CATEGORY;
    defaults.put(key, id);
    BaseOBObject obj = OBDal.getInstance().get(TaxCategory.class, id);
    if (obj != null) {
      defaults.put(key + "$_identifier", obj.getIdentifier());
    }
  }

  /**
   * The {@code productType} query parameter of a {@code /defaults} call. Read from the hook
   * context's query map, which {@code NeoSubEndpointDispatcher} now fills from the request; the
   * servlet request is only a last-resort fallback, because on the live REST path
   * {@code RequestContext.get().getRequest()} did not carry the parameter.
   */
  static String requestedProductType(NeoContext context) {
    Map<String, String> params = context.getQueryParams();
    String fromContext = params != null ? StringUtils.trimToNull(params.get(PARAM_PRODUCT_TYPE)) : null;
    if (fromContext != null) {
      return fromContext;
    }
    try {
      if (RequestContext.get() == null || RequestContext.get().getRequest() == null) {
        return null;
      }
      return StringUtils.trimToNull(
          RequestContext.get().getRequest().getParameter(PARAM_PRODUCT_TYPE));
    } catch (Exception e) {
      return null;
    }
  }

  /**
   * ETP-5680: id of the tax category named for {@code productType} in the client (then the System
   * client), or {@code null} when the type is blank or no such active category exists.
   */
  String resolveTaxCategoryIdForType(String productType, String clientId) {
    String name = taxCategoryNameForType(productType);
    if (name == null) {
      return null;
    }
    if (StringUtils.isNotBlank(clientId)) {
      String own = queryTaxCategoryIdByName(name, clientId);
      if (own != null) {
        return own;
      }
    }
    return queryTaxCategoryIdByName(name, SYSTEM_CLIENT_ID);
  }

  String queryTaxCategoryIdByName(String name, String clientId) {
    try {
      OBContext.setAdminMode(true);
      OBCriteria<TaxCategory> crit = OBDal.getInstance().createCriteria(TaxCategory.class);
      crit.add(Restrictions.eq("client.id", clientId));
      crit.add(Restrictions.eq(TaxCategory.PROPERTY_NAME, name));
      crit.add(Restrictions.eq("active", true));
      crit.setMaxResults(1);
      TaxCategory found = (TaxCategory) crit.uniqueResult();
      return found != null ? found.getId() : null;
    } catch (Exception e) {
      log.debug("Could not resolve tax category '{}' / client {}: {}", name, clientId,
          e.getMessage());
      return null;
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /**
   * Overwrites {@code defaults[propertyName]} (and its {@code $_identifier} companion) with the
   * resolved default id, mirroring {@code FinancialAccountHandler#injectClientCurrencyDefault}.
   * Only overwrites when a real default is found — leaves whatever the generic resolver already
   * put there (including {@code null}) otherwise.
   */
  private void overwriteDefault(JSONObject defaults, String propertyName,
      Class<? extends BaseOBObject> entityClass, String clientId) throws JSONException {
    String defaultId = resolveDefaultId(entityClass, clientId);
    if (defaultId == null) {
      return;
    }
    defaults.put(propertyName, defaultId);
    BaseOBObject obj = OBDal.getInstance().get(entityClass, defaultId);
    if (obj != null) {
      defaults.put(propertyName + "$_identifier", obj.getIdentifier());
    }
  }

  /**
   * Resolves the id of the row of {@code entityClass} flagged {@code IsDefault='Y'} for the given
   * client, falling back to the System client ({@code '0'}) when the client has no default row of
   * its own — the same COALESCE semantics the reverted {@code @SQL=} column default used to apply.
   *
   * @return the resolved id, or {@code null} if neither the client nor the System client has a
   *         row marked as default
   */
  String resolveDefaultId(Class<? extends BaseOBObject> entityClass, String clientId) {
    // ETP-5676: once per batch request and client — every product of an import asks the same.
    return NeoBatchScope.memoize(
        ProductDefaultsHandler.class.getName() + "|default|" + entityClass.getName() + "|"
            + clientId,
        () -> resolveDefaultIdUncached(entityClass, clientId));
  }

  private String resolveDefaultIdUncached(Class<? extends BaseOBObject> entityClass,
      String clientId) {
    if (StringUtils.isNotBlank(clientId)) {
      String ownDefault = queryDefaultId(entityClass, clientId);
      if (ownDefault != null) {
        return ownDefault;
      }
    }
    return queryDefaultId(entityClass, SYSTEM_CLIENT_ID);
  }

  <T extends BaseOBObject> String queryDefaultId(Class<T> entityClass, String clientId) {
    try {
      OBContext.setAdminMode(true);
      OBCriteria<T> crit = OBDal.getInstance().createCriteria(entityClass);
      crit.add(Restrictions.eq("client.id", clientId));
      crit.add(Restrictions.eq("default", true));
      crit.add(Restrictions.eq("active", true));
      crit.setMaxResults(1);
      @SuppressWarnings("unchecked")
      T obj = (T) crit.uniqueResult();
      return obj != null ? String.valueOf(obj.getId()) : null;
    } catch (Exception e) {
      log.debug("Could not resolve default row for {} / client {}: {}",
          entityClass.getSimpleName(), clientId, e.getMessage());
      return null;
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /**
   * Resolves the client to scope the default lookup to: the current OBContext client. Falls back
   * to {@code null} (which resolveDefaultId treats as "go straight to System") when no OB context
   * is available, e.g. in tests that build a bare {@link NeoContext}.
   */
  private static String resolveClientId(NeoContext context) {
    if (context.getObContext() != null && context.getObContext().getCurrentClient() != null) {
      return context.getObContext().getCurrentClient().getId();
    }
    return null;
  }
}
