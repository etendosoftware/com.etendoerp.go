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
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import javax.inject.Named;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.criterion.Order;
import org.hibernate.criterion.Restrictions;
import org.openbravo.costing.CostingUtils;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.client.kernel.RequestContext;
import org.openbravo.erpCommon.utility.OBCurrencyUtils;
import org.openbravo.model.common.plm.Product;
import org.openbravo.model.materialmgmt.cost.Costing;

import com.etendoerp.go.schemaforge.util.NeoDateFormat;

/**
 * NeoHandler for the {@code costing} entity of the Product window (ETP-5245).
 *
 * <p>Turns the Costing tab, until now read-only, into a place where a user can record the
 * product's standard cost over time. The table behind it, {@code M_Costing}, is the costing
 * engine's own table, so writing into it by hand is only safe under a specific set of column
 * values. This handler is what makes them true regardless of what the client sends — the UI is a
 * convenience, this is the guarantee.
 *
 * <h2>Why each derived value is forced</h2>
 * <ul>
 *   <li><b>{@code costType = 'STA'}</b> — a manual {@code 'AVA'} row would be read by
 *       {@code AverageAlgorithm#getProductCost} as the current average cost while
 *       {@code getLastCumulatedCosting} skipped it (it filters on non-null cumulative columns),
 *       so stock valuation and running cost would silently diverge. The user never picks the
 *       type.</li>
 *   <li><b>{@code permanent = false}</b> — {@code M_COSTING_TRG} raises
 *       {@code @CannotModifyPermanentCost@} / {@code @CannotDeletePermanentCost@} on any row
 *       flagged permanent once the product has document lines. The engine writes its own rows
 *       permanent precisely so nobody touches them; ours must stay editable.</li>
 *   <li><b>{@code production = false}</b> — {@code MA_PRODUCTION_COST} does a
 *       {@code SELECT ... INTO} over production rows with no {@code TOO_MANY_ROWS} handler, so a
 *       duplicate would stop production from being processed.</li>
 *   <li><b>{@code manual = true}</b> — purely our own marker. Nothing in core writes or reads
 *       {@code ISMANUAL}; it is what lets the UI and {@link #guardEngineRow} tell a
 *       human-entered row from an engine-generated one.</li>
 *   <li><b>currency</b> — the AD default for {@code C_Currency_ID} is {@code 100} (USD), which
 *       would be wrong on every euro instance. Resolved from the organisation instead.</li>
 *   <li><b>quantity / price / cumulative columns</b> — left unset. They only mean something for
 *       engine rows, and {@code AverageAlgorithm#getLastCumulatedCosting} excludes rows whose
 *       cumulative columns are null, which is exactly what we want.</li>
 * </ul>
 *
 * <h2>Date ranges</h2>
 * The engine resolves a cost with {@code startingDate <= date AND endingDate > date} and, on more
 * than one match, logs a warning and takes {@code get(0)} <em>with no {@code ORDER BY}</em>
 * ({@code CostingUtils#getStandardCostDefinition}). Overlapping ranges therefore make the applied
 * cost depend on the database's execution plan. Rather than reject an overlap, this handler
 * closes the neighbouring ranges around the new row — the same thing the engine itself does in
 * {@code StandardAlgorithm#insertCost} — so the history stays contiguous and unambiguous.
 *
 * <h2>Known, accepted divergence</h2>
 * {@code AD_TAB} 800057 ships {@code EM_OBUIAPP_CAN_ADD='N'} and
 * {@code EM_OBUIAPP_CAN_DELETE='N'}: Openbravo deliberately disabled adding and deleting on this
 * tab in the classic backoffice. NEO Headless does not read those flags. Defining a standard cost
 * also changes the accounting of goods shipments and matched invoices ({@code DocInOut},
 * {@code DocMatchInv}), and {@code CostingUtils#getDefaultCost} consults it even when the
 * organisation's costing rule is Average. Both were reviewed and accepted for ETP-5245.
 *
 * <p>Registered via {@code ETGO_SF_ENTITY.Java_Qualifier = "productCostingHandler"}.
 * {@code @Named} only, never a normal scope — {@code lookupHandler} reads {@code @Named} off the
 * concrete class and a client proxy would not carry it.
 */
@Named("productCostingHandler")
public class ProductCostingHandler implements NeoHandler {

  private static final Logger log = LogManager.getLogger(ProductCostingHandler.class);

  private static final String ENTITY = "costing";
  private static final String METHOD_POST = "POST";
  private static final String METHOD_DELETE = "DELETE";
  private static final String METHOD_GET = "GET";

  /** SmartClient sort parameter the DAL datasource turns into the query's ORDER BY. */
  private static final String PARAM_SORT_BY = "_sortBy";

  /**
   * Default order for the cost list: most recent first.
   *
   * <p>Comma-separated DAL property paths, each optionally suffixed with a direction — the
   * syntax {@code AdvancedQueryBuilder.getOrderByClausePart} parses (it splits on {@code ' '}
   * to read the direction). {@code DefaultJsonDataService} appends {@code id} on its own, so
   * the ordering is total and stable across pages.</p>
   *
   * <p>{@code startingDate} first so the cost in force is on top; {@code creationDate} breaks
   * the tie, which is the common case — the costing engine writes several rows carrying the
   * same start date, and without the tiebreaker their relative order is arbitrary.</p>
   */
  static final String DEFAULT_SORT_BY = "startingDate desc,creationDate desc";

  private static final String FIELD_PRODUCT = "product";
  private static final String FIELD_PARENT_ID = "parentId";
  private static final String FIELD_COST = "cost";
  private static final String FIELD_STARTING_DATE = "startingDate";
  private static final String FIELD_ENDING_DATE = "endingDate";
  private static final String FIELD_COST_TYPE = "costType";
  private static final String FIELD_MANUAL = "manual";
  private static final String FIELD_PERMANENT = "permanent";
  private static final String FIELD_PRODUCTION = "production";
  private static final String FIELD_CURRENCY = "cCurrencyID";
  private static final String FIELD_ORGANIZATION = "organization";
  private static final String FIELD_DEFAULTS = "defaults";

  /** The only cost type a manual row may carry. */
  private static final String COST_TYPE_STANDARD = "STA";

  /** Fields that only ever belong to an engine-generated row. */
  private static final String[] ENGINE_ONLY_FIELDS = {
      "inventoryTransaction", "invoiceLine", "quantity", "price",
      "totalMovementQuantity", "totalStockValuation", "originalCost" };

  // Messages are English on purpose and mapped to a locale key in the frontend's
  // lib/backendErrors.js, the pattern ChartOfAccountsSaveValidationSupport documents as correct.
  static final String ERR_NO_PRODUCT = "A cost line must belong to a product.";
  static final String ERR_COST_REQUIRED = "The cost is required.";
  static final String ERR_COST_NEGATIVE = "The cost cannot be negative.";
  static final String ERR_INVALID_RANGE = "The expiry date must be later than the start date.";
  static final String ERR_PREPARE_FAILED = "The cost line could not be prepared. Try again.";
  static final String ERR_ENGINE_ROW =
      "This cost was calculated by the system and cannot be modified or deleted.";

  @Override
  public NeoResponse handle(NeoContext context) {
    if (context == null || context.getEndpointType() != NeoEndpointType.CRUD
        || !ENTITY.equals(context.getEntityName())) {
      return null;
    }
    if (METHOD_POST.equals(context.getHttpMethod())) {
      return prepareCreate(context);
    }
    applyDefaultSort(context);
    return guardEngineRow(context);
  }

  /**
   * Defaults the cost list to "most recent first" when the caller expressed no preference.
   *
   * <p>Written into {@link NeoContext#getQueryParams()} rather than applied to the result:
   * {@code NeoCrudHandler.buildDalParams} copies every query param straight into the DAL
   * fetch params, so this becomes the query's own ORDER BY. Sorting the returned array in
   * {@code afterHandle} would be wrong — {@code applyPaginationDefaults} caps an unpaginated
   * list at 100 rows, and the costing engine writes one {@code M_Costing} row per transaction,
   * so anything past the first page would be ordered by whatever the database happened to
   * return.</p>
   *
   * <p>Only ever ADDS the parameter: an explicit {@code _sortBy} from the UI's column headers,
   * the REST API or the MCP always wins. No-op on anything but a list GET (a by-id GET has
   * nothing to order).</p>
   *
   * @param context the CRUD context, whose query-param map is mutated in place
   */
  private void applyDefaultSort(NeoContext context) {
    if (!METHOD_GET.equals(context.getHttpMethod())
        || StringUtils.isNotBlank(context.getRecordId())) {
      return;
    }
    Map<String, String> queryParams = context.getQueryParams();
    if (queryParams == null || StringUtils.isNotBlank(queryParams.get(PARAM_SORT_BY))) {
      return;
    }
    queryParams.put(PARAM_SORT_BY, DEFAULT_SORT_BY);
  }

  @Override
  public NeoResponse afterHandle(NeoContext context) {
    if (context == null || !ENTITY.equals(context.getEntityName())) {
      return null;
    }
    if (NeoEndpointType.DEFAULTS.equals(context.getEndpointType())) {
      return injectCostingDefaults(context);
    }
    if (NeoEndpointType.CRUD.equals(context.getEndpointType())
        && METHOD_POST.equals(context.getHttpMethod())) {
      closeAdjacentRanges(context);
    }
    return null;
  }

  // ── Create ──────────────────────────────────────────────────────────────────

  /**
   * Fills in every column the user does not type and rejects the input the engine could not cope
   * with. Returning a non-null response aborts the create before the CRUD layer runs.
   *
   * @param context the POST context
   * @return an error response, or {@code null} to let the create proceed
   */
  private NeoResponse prepareCreate(NeoContext context) {
    JSONObject body = context.getRequestBody();
    if (body == null) {
      return null;
    }
    try {
      String productId = resolveProductId(context, body);
      if (StringUtils.isBlank(productId)) {
        return NeoResponse.error(400, ERR_NO_PRODUCT);
      }

      forceDerivedColumns(body);
      injectOrganisationAndCurrency(context, body);
      injectDefaultEndingDate(body);

      return validate(body);
    } catch (Exception e) {
      // Fail CLOSED. Returning null here would hand the request to the CRUD layer with
      // validate() never having run and, if forceDerivedColumns was the thrower, with the AD
      // defaults for costType/permanent/production — a row indistinguishable from one the
      // costing engine wrote, which is the single thing this class exists to prevent. The
      // read-only guard below can afford to fail open; this cannot.
      log.error("Could not prepare the manual cost line for creation", e);
      return NeoResponse.error(500, ERR_PREPARE_FAILED);
    }
  }

  /**
   * Reads the owning product from the request.
   *
   * <p>In a pre-hook the product still arrives as {@code parentId}: the CRUD layer only renames it
   * to the {@code product} property afterwards ({@code NeoCrudHandler#injectParentIdAsProperty}).
   * Both spellings are accepted so a direct API caller works too.
   *
   * @param context the request context
   * @param body the request body
   * @return the product id, or {@code null} when the request names none
   */
  private String resolveProductId(NeoContext context, JSONObject body) {
    String fromBody = StringUtils.trimToNull(body.optString(FIELD_PRODUCT, null));
    if (fromBody != null) {
      return fromBody;
    }
    String fromParentField = StringUtils.trimToNull(body.optString(FIELD_PARENT_ID, null));
    if (fromParentField != null) {
      return fromParentField;
    }
    return context.getQueryParams() != null
        ? StringUtils.trimToNull(context.getQueryParams().get(FIELD_PARENT_ID)) : null;
  }

  /**
   * Overwrites the columns whose value is ours to decide, and strips the ones that only belong to
   * an engine-generated row.
   *
   * <p>Unconditional {@code put}, not "only if absent": these must hold whatever the client sent.
   * They also have to be declared {@code system} (not {@code discarded}) in {@code decisions.json},
   * or {@code NeoFieldFilter#filterCreateRequest} drops them from the body and the row is created
   * with the AD defaults instead — indistinguishable from an engine row.
   *
   * @param body the request body to normalise
   */
  private void forceDerivedColumns(JSONObject body) throws Exception {
    body.put(FIELD_COST_TYPE, COST_TYPE_STANDARD);
    body.put(FIELD_MANUAL, true);
    body.put(FIELD_PERMANENT, false);
    body.put(FIELD_PRODUCTION, false);
    for (String field : ENGINE_ONLY_FIELDS) {
      body.remove(field);
    }
  }

  /**
   * Defaults the organisation to the context's and the currency to that organisation's, rather
   * than letting the AD default ({@code 100} = USD) through.
   *
   * @param context the request context
   * @param body the request body
   */
  private void injectOrganisationAndCurrency(NeoContext context, JSONObject body) throws Exception {
    OBContext obContext = context.getObContext();
    if (obContext == null || obContext.getCurrentOrganization() == null) {
      return;
    }
    String orgId = StringUtils.defaultIfBlank(body.optString(FIELD_ORGANIZATION, null),
        obContext.getCurrentOrganization().getId());
    body.put(FIELD_ORGANIZATION, orgId);
    if (body.has(FIELD_CURRENCY) && StringUtils.isNotBlank(body.optString(FIELD_CURRENCY, null))) {
      return;
    }
    OBContext.setAdminMode();
    try {
      String currencyId = OBCurrencyUtils.getOrgCurrency(orgId);
      if (StringUtils.isNotBlank(currencyId)) {
        body.put(FIELD_CURRENCY, currencyId);
      }
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /**
   * Applies the open-ended expiry date when the user left it blank.
   *
   * <p>{@code DateTo} is mandatory in the dictionary but optional for the user: an empty expiry
   * means "in force indefinitely", which the engine spells as {@code 31-12-9999}
   * ({@code CostingUtils#getLastDate}). Taken from core rather than hardcoded so the two cannot
   * drift apart.
   *
   * @param body the request body
   */
  private void injectDefaultEndingDate(JSONObject body) throws Exception {
    if (StringUtils.isNotBlank(body.optString(FIELD_ENDING_DATE, null))) {
      return;
    }
    body.put(FIELD_ENDING_DATE, NeoDateFormat.toWireDate(CostingUtils.getLastDate()));
  }

  /**
   * Rejects a cost line the costing engine could not make sense of.
   *
   * @param body the normalised request body
   * @return an error response, or {@code null} when the line is acceptable
   */
  private NeoResponse validate(JSONObject body) {
    String rawCost = StringUtils.trimToNull(body.optString(FIELD_COST, null));
    if (rawCost == null) {
      return NeoResponse.error(400, ERR_COST_REQUIRED);
    }
    try {
      if (new BigDecimal(rawCost).signum() < 0) {
        return NeoResponse.error(400, ERR_COST_NEGATIVE);
      }
    } catch (NumberFormatException e) {
      return NeoResponse.error(400, ERR_COST_REQUIRED);
    }

    LocalDate from = parseDate(body.optString(FIELD_STARTING_DATE, null));
    LocalDate to = parseDate(body.optString(FIELD_ENDING_DATE, null));
    if (from != null && to != null && !to.isAfter(from)) {
      return NeoResponse.error(400, ERR_INVALID_RANGE);
    }
    return null;
  }

  // ── Post-create: keep the history contiguous ────────────────────────────────

  /**
   * Closes the ranges either side of the row just created, so no two standard costs of the same
   * product and organisation are in force on the same day.
   *
   * <p>The engine does the same thing when it inserts a cost
   * ({@code StandardAlgorithm#insertCost} shortens the previous row before cloning it). Doing it
   * here means the user simply says "from this date the cost is X" and the history follows,
   * instead of being asked to fix the previous line's expiry date first.
   *
   * <p>Only {@code DateTo} is ever touched, which {@code M_COSTING_TRG} allows even on rows
   * flagged permanent — so an engine-generated neighbour can be closed safely.
   *
   * <p>Best-effort: the cost line is already created and valid on its own; failing to tidy the
   * neighbours must not turn a successful save into an error.
   *
   * @param context the POST context whose previous result carries the created row
   */
  private void closeAdjacentRanges(NeoContext context) {
    String costingId = extractCreatedRecordId(context);
    if (StringUtils.isBlank(costingId)) {
      return;
    }
    try {
      OBContext.setAdminMode();
      try {
        Costing created = OBDal.getInstance().get(Costing.class, costingId);
        if (created == null || created.getStartingDate() == null) {
          return;
        }
        closePreviousRange(created);
        clampOwnRange(created);
        OBDal.getInstance().flush();
      } finally {
        OBContext.restorePreviousMode();
      }
    } catch (Exception e) {
      log.error("Could not close the cost ranges around {}", costingId, e);
    }
  }

  /**
   * Shortens the row in force immediately before the new one so it ends where the new one starts.
   *
   * @param created the row just created
   */
  private void closePreviousRange(Costing created) {
    Costing previous = findNeighbour(created, true);
    if (previous != null && previous.getEndingDate() != null
        && previous.getEndingDate().after(created.getStartingDate())) {
      previous.setEndingDate(created.getStartingDate());
      OBDal.getInstance().save(previous);
    }
  }

  /**
   * Pulls the new row's own expiry date back to the start of the next one, when the user left it
   * open-ended but a later cost already exists.
   *
   * @param created the row just created
   */
  private void clampOwnRange(Costing created) {
    Costing next = findNeighbour(created, false);
    if (next != null && next.getStartingDate() != null
        && (created.getEndingDate() == null
            || created.getEndingDate().after(next.getStartingDate()))) {
      created.setEndingDate(next.getStartingDate());
      OBDal.getInstance().save(created);
    }
  }

  /**
   * Finds the standard cost row of the same product and organisation nearest to the given one.
   *
   * <p>Mirrors the predicate {@code CostingUtils#getStandardCostDefinition} uses to read a cost —
   * same cost type, non-null cost, and no organisation-readability filter — so the rows this
   * considers are exactly the rows the engine would consider. Engine-generated neighbours are
   * included deliberately: skipping them would leave the very overlap this exists to prevent.
   *
   * @param created the row just created
   * @param before {@code true} for the row starting before it, {@code false} for the one after
   * @return the neighbouring row, or {@code null} when there is none
   */
  private Costing findNeighbour(Costing created, boolean before) {
    OBCriteria<Costing> crit = OBDal.getInstance().createCriteria(Costing.class);
    crit.setFilterOnReadableOrganization(false);
    crit.add(Restrictions.eq(Costing.PROPERTY_PRODUCT, created.getProduct()));
    crit.add(Restrictions.eq(Costing.PROPERTY_ORGANIZATION, created.getOrganization()));
    crit.add(Restrictions.eq(Costing.PROPERTY_COSTTYPE, COST_TYPE_STANDARD));
    crit.add(Restrictions.isNotNull(Costing.PROPERTY_COST));
    crit.add(Restrictions.ne("id", created.getId()));
    if (before) {
      crit.add(Restrictions.lt(Costing.PROPERTY_STARTINGDATE, created.getStartingDate()));
      crit.addOrder(Order.desc(Costing.PROPERTY_STARTINGDATE));
    } else {
      crit.add(Restrictions.gt(Costing.PROPERTY_STARTINGDATE, created.getStartingDate()));
      crit.addOrder(Order.asc(Costing.PROPERTY_STARTINGDATE));
    }
    crit.setMaxResults(1);
    List<Costing> results = crit.list();
    return results.isEmpty() ? null : results.get(0);
  }

  // ── Update / delete ─────────────────────────────────────────────────────────

  /**
   * Refuses to modify or delete a row the costing engine produced.
   *
   * <p>The Costing tab shows the whole history, engine rows included, and the grid has no
   * per-row delete gate — the bin renders on every row. This is therefore the only real
   * protection, and it covers the API and MCP just as much as the UI. A response of 403 stops the
   * request before the CRUD layer ({@code NeoServletSupport#handleWithHooks}).
   *
   * @param context the PATCH/PUT/DELETE context
   * @return a 403 response for an engine row, or {@code null} for a manual one
   */
  private NeoResponse guardEngineRow(NeoContext context) {
    String recordId = context.getRecordId();
    if (StringUtils.isBlank(recordId)) {
      return null;
    }
    try {
      OBContext.setAdminMode();
      try {
        Costing costing = OBDal.getInstance().get(Costing.class, recordId);
        if (costing == null) {
          return null;
        }
        if (!Boolean.TRUE.equals(costing.isManual())) {
          return NeoResponse.error(403, ERR_ENGINE_ROW);
        }
      } finally {
        OBContext.restorePreviousMode();
      }
    } catch (Exception e) {
      log.error("Could not check whether cost line {} is manual", recordId, e);
      return null;
    }
    return stripImmutableFields(context);
  }

  /**
   * Drops the derived columns from an update, so a manual row cannot be turned into an average
   * cost or made permanent after the fact.
   *
   * @param context the update context
   * @return always {@code null} — the update itself is allowed to proceed
   */
  private NeoResponse stripImmutableFields(NeoContext context) {
    if (METHOD_DELETE.equals(context.getHttpMethod())) {
      return null;
    }
    JSONObject body = context.getRequestBody();
    if (body != null) {
      body.remove(FIELD_COST_TYPE);
      body.remove(FIELD_MANUAL);
      body.remove(FIELD_PERMANENT);
      body.remove(FIELD_PRODUCTION);
      body.remove(FIELD_CURRENCY);
    }
    return null;
  }

  // ── Defaults ────────────────────────────────────────────────────────────────

  /**
   * Pre-fills a new cost line with the product's creation date as its start date.
   *
   * <p>{@code M_Costing.DateFrom} carries no {@code AD_Column.DefaultValue}, so there is nothing
   * for the generic defaults service to resolve — it has to be injected here.
   *
   * @param context the {@code /defaults} context
   * @return the enriched response, or {@code null} to leave it untouched
   */
  private NeoResponse injectCostingDefaults(NeoContext context) {
    NeoResponse previous = context.getPreviousResult();
    if (previous == null || previous.getBody() == null) {
      return null;
    }
    try {
      String productId = resolveDefaultsParentId(context);
      if (StringUtils.isBlank(productId)) {
        return null;
      }
      JSONObject body = previous.getBody();
      JSONObject defaults = body.optJSONObject(FIELD_DEFAULTS);
      if (defaults == null) {
        defaults = new JSONObject();
        body.put(FIELD_DEFAULTS, defaults);
      }
      OBContext.setAdminMode();
      try {
        Product product = OBDal.getInstance().get(Product.class, productId);
        if (product != null && product.getCreationDate() != null) {
          defaults.put(FIELD_STARTING_DATE, NeoDateFormat.toWireDate(product.getCreationDate()));
        }
      } finally {
        OBContext.restorePreviousMode();
      }
      defaults.put(FIELD_COST_TYPE, COST_TYPE_STANDARD);
      return NeoResponse.ok(body);
    } catch (Exception e) {
      log.error("Could not inject costing defaults", e);
      return null;
    }
  }

  // ── Shared helpers ──────────────────────────────────────────────────────────

  /**
   * Reads the id of the record the CRUD layer just created.
   *
   * @param context the context whose previous result holds the create response
   * @return the new record id, or {@code null}
   */
  private String extractCreatedRecordId(NeoContext context) {
    NeoResponse previous = context.getPreviousResult();
    if (previous == null || previous.getBody() == null) {
      return null;
    }
    JSONObject response = previous.getBody().optJSONObject("response");
    if (response == null) {
      return null;
    }
    JSONArray data = response.optJSONArray("data");
    if (data == null || data.length() == 0) {
      return null;
    }
    JSONObject record = data.optJSONObject(0);
    return record != null ? StringUtils.trimToNull(record.optString("id", null)) : null;
  }

  /**
   * Parses a wire date, tolerating the shapes NEO accepts.
   *
   * @param raw the raw value
   * @return the parsed date, or {@code null} when it is absent or unparseable
   */
  private LocalDate parseDate(String raw) {
    String trimmed = StringUtils.trimToNull(raw);
    if (trimmed == null) {
      return null;
    }
    try {
      String canonical = NeoDateFormat.toCanonical(trimmed, false);
      String usable = StringUtils.isNotBlank(canonical) ? canonical : trimmed;
      return LocalDate.parse(usable.substring(0, 10));
    } catch (Exception e) {
      log.debug("Unparseable cost line date '{}'", raw);
      return null;
    }
  }

  /**
   * Resolves the owning product for a {@code /defaults} request.
   *
   * <p>Two transports, two places to look. {@code NeoHookDispatcher#buildHookContext} does not
   * copy query parameters onto the context it builds for a REST {@code /defaults} hook, so there
   * the product has to come from the servlet request (the approach
   * {@code InvoiceExchangeRateHandler#readParentIdFromRequest} takes). {@code McpHookExecutor},
   * on the other hand, does populate {@code queryParams} and has no servlet request behind it —
   * so checking the context first and the request second is the only form that works on both.
   *
   * @param context the {@code /defaults} context
   * @return the parent product id, or {@code null} when neither transport carries one
   */
  private static String resolveDefaultsParentId(NeoContext context) {
    String fromParams = context.getQueryParams() != null
        ? StringUtils.trimToNull(context.getQueryParams().get(FIELD_PARENT_ID)) : null;
    return fromParams != null ? fromParams : readParentIdFromRequest();
  }

  /**
   * Reads {@code parentId} straight off the HTTP request.
   *
   * <p>Fallback for the REST {@code /defaults} path only — see
   * {@link #resolveDefaultsParentId(NeoContext)}.
   *
   * @return the parent product id, or {@code null} when the request carries none
   */
  private static String readParentIdFromRequest() {
    try {
      if (RequestContext.get() == null || RequestContext.get().getRequest() == null) {
        return null;
      }
      return StringUtils.trimToNull(RequestContext.get().getRequest().getParameter(FIELD_PARENT_ID));
    } catch (Exception e) {
      log.debug("Could not read parentId from the request: {}", e.getMessage());
      return null;
    }
  }
}
