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

import java.util.Set;

import javax.inject.Named;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.criterion.Restrictions;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.uom.UOM;
import org.openbravo.model.financialmgmt.tax.TaxCategory;

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
 * {@code em_etgo_issystemcategory = 'Y'} from GET responses — see
 * {@link #hideSystemCategoryProducts}.
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
  private static final String FIELD_TOTAL_ROWS = "totalRows";
  private static final String FIELD_END_ROW = "endRow";
  private static final String SYSTEM_CLIENT_ID = "0";

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
    try {
      enforceNonStockableProductTypes(body);
    } catch (Exception e) {
      log.error("product pre-hook: failed to enforce non-stockable product type stock flags", e);
    }
    if (isCreate) {
      try {
        String clientId = resolveClientId(context);
        injectIfMissing(body, FIELD_UOM, UOM.class, clientId);
        injectIfMissing(body, FIELD_TAX_CATEGORY, TaxCategory.class, clientId);
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
  private static void enforceNonStockableProductTypes(JSONObject body) throws JSONException {
    if (!NON_STOCKABLE_PRODUCT_TYPES.contains(body.optString(FIELD_PRODUCT_TYPE, null))) {
      return;
    }
    body.put(FIELD_STOCKED, false);
    body.put(FIELD_RETURNABLE, false);
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
      return hideSystemCategoryProducts(context);
    }
    return null;
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
      return NeoResponse.ok(body);
    } catch (Exception e) {
      log.error("product afterHandle: failed to inject uOM/taxCategory default", e);
      return null;
    }
  }

  /**
   * ETP-4967: strips products classified under a category flagged {@code em_etgo_issystemcategory
   * = 'Y'} (see {@link SystemCategoryIds}) from GET responses (list and single-record alike —
   * {@code response.data} has the same shape either way), so e.g. {@code ETGO_DTO} (category
   * "Discounts") never shows up in the Product window.
   */
  private NeoResponse hideSystemCategoryProducts(NeoContext context) {
    if (!METHOD_GET.equals(context.getHttpMethod())) {
      return null;
    }
    NeoResponse previous = context.getPreviousResult();
    if (previous == null || previous.getBody() == null) {
      return null;
    }
    try {
      JSONObject body = previous.getBody();
      JSONObject responseWrapper = body.optJSONObject("response");
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
   * {@code response.totalRows}/{@code endRow} come from core's own datasource count query,
   * computed before {@link #filterHiddenCategoryRows} ran — decrement them by
   * {@code removedCount} so a caller that pages off those fields (rather than
   * {@code data.length}, which is already correct) does not request a page past the end.
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
