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
 * All portions are Copyright © 2021–2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.schemaforge;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Map;

import javax.inject.Inject;
import javax.inject.Named;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.Property;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.client.kernel.RequestContext;
import org.openbravo.dal.service.OBDal;

import com.etendoerp.go.schemaforge.handlers.DocumentPostingService;

/**
 * NeoHandler for the {@code header} entity of the {@code amortization} spec.
 *
 * <p>Computes the {@code name} default on new-record forms using the asset name and its
 * depreciation start date when an {@code assetId} query parameter is present:
 * {@code "Amortización - {assetName} - {amortizationstartdate}"}.
 *
 * <p>Falls back to {@code "Amortización"} when the parameter is absent, the asset cannot
 * be found, or any lookup error occurs — never crashes, never blocks the defaults call.
 *
 * <p>Only fires on the {@link NeoEndpointType#DEFAULTS} endpoint. All other endpoints
 * pass through unchanged.
 *
 * <p><b>List "Posted" filter (pre-hook).</b> The grid renders {@code Posted} as a boolean
 * badge, so its column filter sends a criteria entry with a JS boolean value
 * ({@code {fieldName:"posted", operator:"equals", value:true|false}}). But {@code A_Amortization.Posted}
 * is Etendo's multi-value Posted-Status list column (a {@code String} DAL property, ~17 codes
 * where only {@code 'Y'} means posted). Core {@code AdvancedQueryBuilder.getTypeSafeValue} only
 * converts {@code boolean → 'Y'/'N'} for genuine {@code Boolean} properties, so the raw boolean is
 * stringified to {@code "true"/"false"} and compared against the varchar column, matching zero rows
 * for both "Posted" and "Not posted". {@link #handle(NeoContext)} rewrites that single criteria
 * entry in place before the default CRUD query builds, giving correct binary semantics:
 * <ul>
 *   <li>{@code true} (or {@code "true"/"Y"}) → {@code equals 'Y'}  (Posted = 'Y')</li>
 *   <li>{@code false} (or {@code "false"/"N"}) → {@code notEqual 'Y'}  (Posted &lt;&gt; 'Y', every non-Y code)</li>
 * </ul>
 * Only the {@code posted} entry is touched; all other filters, sorting and paging are preserved.
 * Requests without a posted filter pass through unchanged.
 *
 * <p>Registered via {@code JAVA_QUALIFIER = 'amortizationHeaderHandler'} on the
 * {@code header} entity of the {@code amortization} ETGO_SF_SPEC record.
 */
@Named("amortizationHeaderHandler")
public class AmortizationHeaderHandler implements NeoHandler {

  private static final Logger log = LogManager.getLogger(AmortizationHeaderHandler.class);

  private static final String PARAM_ASSET_ID = "assetId";
  private static final String FIELD_DEFAULTS = "defaults";
  private static final String PROPERTY_NAME = "name";
  private static final String ASSET_ENTITY_NAME = "FinancialMgmtAsset";
  private static final String COLUMN_NAME = "Name";
  private static final String COLUMN_START_DATE = "Amortizationstartdate";
  private static final String DATE_FORMAT = "yyyy-MM-dd";
  private static final String NAME_PREFIX = "Amortización - ";
  private static final String NAME_FALLBACK = "Amortización";

  // --- List "Posted" filter rewrite ------------------------------------------------
  private static final String CRITERIA_PARAM = "criteria";
  private static final String KEY_FIELD_NAME = "fieldName";
  private static final String KEY_OPERATOR = "operator";
  private static final String KEY_VALUE = "value";
  private static final String KEY_CRITERIA = "criteria";
  private static final String POSTED_FIELD = "posted";
  private static final String POSTED_COLUMN = "Posted";
  private static final String POSTED_YES = "Y";
  private static final String OPERATOR_EQUALS = "equals";
  private static final String OPERATOR_NOT_EQUAL = "notEqual";

  @Inject
  private DocumentPostingService postingService;

  /** Package-private seam so unit tests can inject a mocked {@link DocumentPostingService}. */
  void setPostingService(DocumentPostingService postingService) {
    this.postingService = postingService;
  }

  @Override
  public NeoResponse handle(NeoContext context) {
    NeoResponse posting = postingService != null ? postingService.handleAction(context) : null;
    if (posting != null) {
      return posting;
    }
    // Rewrite the list "Posted" boolean filter into correct binary String semantics
    // (Posted = 'Y' / Posted <> 'Y') before the default CRUD query builds. See class Javadoc.
    rewritePostedFilter(context);
    // Pre-hook: nothing else to intercept — let the defaults/CRUD service run
    return null;
  }

  /**
   * Rewrites the {@code posted} list-filter criteria (if present) in the request query params
   * so the boolean the grid sends maps to the multi-value {@code Posted} status column. Mutates
   * the {@link NeoContext#getQueryParams()} map in place — the default CRUD path reads the same
   * map when it builds the DAL query. No-op when there is no {@code criteria} param or no posted
   * entry. Never throws: any parse issue leaves the original criteria untouched.
   */
  static void rewritePostedFilter(NeoContext context) {
    if (context == null || context.getQueryParams() == null) {
      return;
    }
    Map<String, String> queryParams = context.getQueryParams();
    String rawCriteria = queryParams.get(CRITERIA_PARAM);
    if (rawCriteria == null || rawCriteria.trim().isEmpty()) {
      return;
    }
    try {
      String rewritten = rewritePostedInCriteria(rawCriteria);
      if (rewritten != null && !rewritten.equals(rawCriteria)) {
        queryParams.put(CRITERIA_PARAM, rewritten);
      }
    } catch (Exception e) {
      log.debug("Could not rewrite posted filter criteria, leaving it unchanged: {}", e.getMessage());
    }
  }

  /**
   * Parses the raw {@code criteria} JSON (either a bare array of criteria entries or a single
   * {@code AdvancedCriteria} object), rewrites every {@code posted} leaf, and returns the
   * re-serialized JSON preserving the original top-level shape.
   */
  static String rewritePostedInCriteria(String rawCriteria) throws JSONException {
    String trimmed = rawCriteria.trim();
    if (trimmed.startsWith("[")) {
      JSONArray arr = new JSONArray(trimmed);
      rewriteCriteriaArray(arr);
      return arr.toString();
    }
    JSONObject obj = new JSONObject(trimmed);
    rewriteCriteriaObject(obj);
    return obj.toString();
  }

  private static void rewriteCriteriaArray(JSONArray arr) throws JSONException {
    for (int i = 0; i < arr.length(); i++) {
      JSONObject entry = arr.optJSONObject(i);
      if (entry != null) {
        rewriteCriteriaObject(entry);
      }
    }
  }

  /**
   * Rewrites a single criteria node. Nested {@code AdvancedCriteria} (has a {@code criteria}
   * array) is recursed into; a leaf whose {@code fieldName} targets the posted field/column has
   * its {@code operator} and {@code value} rewritten to correct binary status semantics. All
   * other leaves are left untouched.
   */
  private static void rewriteCriteriaObject(JSONObject entry) throws JSONException {
    JSONArray nested = entry.optJSONArray(KEY_CRITERIA);
    if (nested != null) {
      rewriteCriteriaArray(nested);
      return;
    }
    String field = entry.optString(KEY_FIELD_NAME, null);
    if (!isPostedField(field)) {
      return;
    }
    boolean posted = isPostedTrue(entry.opt(KEY_VALUE));
    entry.put(KEY_OPERATOR, posted ? OPERATOR_EQUALS : OPERATOR_NOT_EQUAL);
    entry.put(KEY_VALUE, POSTED_YES);
  }

  private static boolean isPostedField(String field) {
    return POSTED_FIELD.equalsIgnoreCase(field) || POSTED_COLUMN.equalsIgnoreCase(field);
  }

  /**
   * Interprets a filter value as "posted" (true) vs "not posted" (false). Robust to the value
   * arriving as a JSON boolean, the strings {@code "true"/"false"}, the status codes
   * {@code "Y"/"N"}, or {@code "1"}. Anything not recognized as posted is treated as not posted.
   */
  private static boolean isPostedTrue(Object value) {
    if (value == null) {
      return false;
    }
    if (value instanceof Boolean) {
      return (Boolean) value;
    }
    String s = value.toString().trim();
    return "true".equalsIgnoreCase(s) || POSTED_YES.equalsIgnoreCase(s)
        || "posted".equalsIgnoreCase(s) || "1".equals(s);
  }

  /**
   * Post-hook: after the defaults service resolves AD defaults, inject a computed {@code name}
   * when {@code assetId} is present. Does not overwrite a name already set by the defaults service.
   */
  @Override
  public NeoResponse afterHandle(NeoContext context) {
    if (!NeoEndpointType.DEFAULTS.equals(context.getEndpointType())) {
      return null;
    }
    NeoResponse previous = context.getPreviousResult();
    if (previous == null || previous.getBody() == null) {
      return null;
    }
    try {
      JSONObject body = previous.getBody();
      JSONObject defaults = body.optJSONObject(FIELD_DEFAULTS);
      if (defaults == null) {
        defaults = new JSONObject();
        body.put(FIELD_DEFAULTS, defaults);
      }
      // Do not overwrite a name already resolved by the defaults service
      if (defaults.has(PROPERTY_NAME) && !defaults.isNull(PROPERTY_NAME)) {
        return null;
      }
      String computedName = computeNameDefault(context);
      defaults.put(PROPERTY_NAME, computedName);
      return NeoResponse.ok(body);
    } catch (Exception e) {
      log.error("Failed to inject name default for amortization header: {}", e.getMessage(), e);
      return null;
    }
  }

  /**
   * Computes the name default by resolving {@code assetId} from the {@link NeoContext}
   * query-param map (populated by both the MCP path and the REST path) with a fallback to
   * the HTTP {@link RequestContext} for callers that do not yet populate queryParams.
   */
  private static String computeNameDefault(NeoContext context) {
    String assetId = readAssetIdFromContext(context);
    if (assetId == null) {
      return NAME_FALLBACK;
    }
    try {
      BaseOBObject asset = OBDal.getInstance().get(ASSET_ENTITY_NAME, assetId);
      if (asset == null) {
        log.debug("Asset not found for id '{}', using fallback name", assetId);
        return NAME_FALLBACK;
      }
      // Use the entity of the already-loaded asset — never null, no extra ModelProvider lookup.
      Entity assetEntity = asset.getEntity();
      Property nameProp = assetEntity.getPropertyByColumnName(COLUMN_NAME, false);
      Property dateProp = assetEntity.getPropertyByColumnName(COLUMN_START_DATE, false);

      String assetName = nameProp != null ? (String) asset.get(nameProp.getName()) : null;
      Object rawDate = dateProp != null ? asset.get(dateProp.getName()) : null;

      if (assetName == null || assetName.isEmpty()) {
        return NAME_FALLBACK;
      }
      StringBuilder sb = new StringBuilder(NAME_PREFIX).append(assetName);
      if (rawDate != null) {
        sb.append(" - ").append(formatDate(rawDate));
      }
      return sb.toString();
    } catch (Exception e) {
      log.debug("Could not compute amortization name for assetId '{}': {}", assetId, e.getMessage());
      return NAME_FALLBACK;
    }
  }

  /**
   * Resolves {@code assetId} using a two-step lookup:
   * <ol>
   *   <li>{@link NeoContext#getQueryParams()} — populated by both the MCP path
   *       ({@code McpToolRouter.handleDefaults}) and the REST path
   *       ({@code NeoDefaultsEndpoint}).</li>
   *   <li>{@link RequestContext} HTTP parameter — kept as a fallback for any direct
   *       servlet invocation that does not populate queryParams.</li>
   * </ol>
   */
  private static String readAssetIdFromContext(NeoContext context) {
    // Primary: queryParams populated by McpToolRouter and NeoDefaultsEndpoint
    if (context != null && context.getQueryParams() != null) {
      String assetId = context.getQueryParams().get(PARAM_ASSET_ID);
      if (assetId != null && !assetId.isEmpty()) {
        return assetId;
      }
    }
    // Fallback: direct HTTP request (legacy callers that do not set queryParams)
    try {
      if (RequestContext.get() == null || RequestContext.get().getRequest() == null) {
        return null;
      }
      String assetId = RequestContext.get().getRequest().getParameter(PARAM_ASSET_ID);
      return (assetId != null && !assetId.isEmpty()) ? assetId : null;
    } catch (Exception e) {
      log.debug("Could not read assetId from request context: {}", e.getMessage());
      return null;
    }
  }

  private static String formatDate(Object rawDate) {
    try {
      if (rawDate instanceof Date) {
        return new SimpleDateFormat(DATE_FORMAT).format((Date) rawDate);
      }
      return rawDate.toString();
    } catch (Exception e) {
      return rawDate.toString();
    }
  }
}
