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

package com.etendoerp.go.schemaforge.handlers;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.inject.Named;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.query.NativeQuery;
import org.openbravo.client.kernel.RequestContext;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.dal.service.OBQuery;
import org.openbravo.model.financialmgmt.accounting.coa.ElementValue;

import com.etendoerp.go.schemaforge.NeoContext;
import com.etendoerp.go.schemaforge.NeoEndpointType;
import com.etendoerp.go.schemaforge.NeoHandler;
import com.etendoerp.go.schemaforge.NeoResponse;

/**
 * NeoHandler for the {@code chart-of-accounts} spec, bound to the
 * {@code elementValue} entity (table {@code C_ElementValue}).
 *
 * <p>Implements three behaviours:
 * <ul>
 *   <li><b>A — isLeaf enrichment</b> (afterHandle, CRUD GET): injects an {@code isLeaf}
 *       boolean into every record of the GET response based on {@code IsSummary}.
 *       {@code IsSummary = 'N'} → {@code isLeaf: true}; {@code 'Y'} → {@code false}.</li>
 *   <li><b>B — codePrefix default</b> (handle, DEFAULTS): when {@code parentAccountId}
 *       is present as a query parameter, returns the first 4 characters of the parent's
 *       {@code Value} (account code) as {@code codePrefix} in the defaults payload.</li>
 *   <li><b>C — PGC save validation</b> (handle, CRUD POST/PUT/PATCH):
 *       <ol>
 *         <li>Rejects codes that do not match {@code ^\d{8}$}.</li>
 *         <li>Rejects code changes on summary (non-leaf) accounts — accounts that have
 *             children in {@code AD_TreeNode}.</li>
 *         <li>Rejects prefix changes on leaf accounts (first 4 digits are immutable).</li>
 *       </ol>
 *   </li>
 * </ul>
 *
 * <p>{@code @Named} only — never a normal CDI scope. See CLAUDE.md §NeoHandler Pattern.
 */
@Named("chart-of-accounts")
public class ChartOfAccountsHandler implements NeoHandler {

  private static final Logger log = LogManager.getLogger(ChartOfAccountsHandler.class);

  /** API field name for the account code (mapped from DB column {@code Value}). */
  private static final String FIELD_SEARCH_KEY = "searchKey";

  /** Query param name for the parent account on new-record defaults calls. */
  private static final String PARAM_PARENT_ACCOUNT_ID = "parentAccountId";

  /** Number of leading digits that form the PGC prefix (immutable for leaf accounts). */
  private static final int PGC_PREFIX_LENGTH = 4;

  /** Required exact length of the account code. */
  private static final int ACCOUNT_CODE_LENGTH = 8;

  static final String ERR_INVALID_CODE =
      "El código de cuenta debe tener exactamente 8 dígitos";

  static final String ERR_SUMMARY_LOCKED =
      "Las cuentas resumen no pueden modificarse";

  static final String ERR_PREFIX_LOCKED =
      "El prefijo PGC (primeros 4 dígitos) no puede modificarse";

  /**
   * SQL that returns the {@code AD_Tree_ID} for a given {@code C_ElementValue_ID}.
   * Used to scope the children-count query to the correct chart of accounts tree.
   */
  private static final String SQL_TREE_ID =
      "SELECT AD_Tree_ID FROM AD_TreeNode WHERE Node_ID = :nodeId LIMIT 1";

  /**
   * SQL that counts immediate children of a node in a specific tree.
   * If count > 0 the account is a parent/summary account.
   */
  private static final String SQL_CHILDREN_COUNT =
      "SELECT COUNT(*) FROM AD_TreeNode "
      + "WHERE Parent_ID = :parentId AND AD_Tree_ID = :treeId";

  // ── NeoHandler entry points ────────────────────────────────────────────────

  /**
   * Pre-hook:
   * <ul>
   *   <li>CRUD POST/PUT/PATCH: validates the account code before saving.</li>
   *   <li>DEFAULTS and everything else: returns {@code null} so the default service runs
   *       first; {@code afterHandle} then augments the result.</li>
   * </ul>
   */
  @Override
  public NeoResponse handle(NeoContext context) {
    try {
      if (context.getEndpointType() == NeoEndpointType.CRUD) {
        String method = context.getHttpMethod();
        if ("POST".equals(method) || "PUT".equals(method) || "PATCH".equals(method)) {
          return validateSave(context);
        }
      }
      return null;
    } catch (Exception e) {
      log.error("ChartOfAccountsHandler.handle error: {}", e.getMessage(), e);
      return NeoResponse.error(500, "Chart of accounts handler error: " + e.getMessage());
    }
  }

  /**
   * Post-hook:
   * <ul>
   *   <li>CRUD GET: enriches every record with an {@code isLeaf} boolean.</li>
   *   <li>DEFAULTS: when {@code parentAccountId} is present, injects {@code codePrefix}
   *       (first {@value #PGC_PREFIX_LENGTH} digits of the parent's account code) into
   *       the defaults payload already resolved by the AD_Column defaults service — so
   *       both the standard field defaults <em>and</em> {@code codePrefix} are returned.</li>
   * </ul>
   * On any failure the original result is preserved (method returns {@code null}).
   */
  @Override
  public NeoResponse afterHandle(NeoContext context) {
    try {
      if (context.getEndpointType() == NeoEndpointType.CRUD
          && "GET".equals(context.getHttpMethod())) {
        return enrichWithIsLeaf(context);
      }
      if (context.getEndpointType() == NeoEndpointType.DEFAULTS) {
        return injectCodePrefix(context);
      }
      return null;
    } catch (Exception e) {
      log.warn("ChartOfAccountsHandler.afterHandle error: {}", e.getMessage(), e);
      return null;
    }
  }

  // ── A. isLeaf enrichment ───────────────────────────────────────────────────

  /**
   * Reads the {@code response.data} array from the previous GET result and adds
   * {@code isLeaf: true/false} to every record based on a batch DB lookup of
   * {@code C_ElementValue.IsSummary}.
   *
   * <p>Because {@code summaryLevel} is a system-visibility field in the contract it is
   * stripped from the GET response by the field filter before {@code afterHandle} runs.
   * A separate batch OBDal query is therefore used instead of reading the field from
   * the already-filtered response.</p>
   */
  private NeoResponse enrichWithIsLeaf(NeoContext context) throws Exception {
    JSONArray data = extractDataArray(context);
    if (data == null) {
      return null;
    }
    List<String> ids = collectIds(data);
    if (ids.isEmpty()) {
      return null;
    }
    Map<String, Boolean> isSummaryMap = querySummaryLevels(ids);
    applyIsLeaf(data, isSummaryMap);
    return NeoResponse.ok(context.getPreviousResult().getBody());
  }

  /**
   * Extracts the {@code response.data} JSONArray from the previous handler result,
   * or returns {@code null} when the structure is missing or empty.
   */
  private static JSONArray extractDataArray(NeoContext context) {
    NeoResponse previous = context.getPreviousResult();
    if (previous == null || previous.getBody() == null) {
      return null;
    }
    JSONObject response = previous.getBody().optJSONObject("response");
    if (response == null) {
      return null;
    }
    JSONArray data = response.optJSONArray("data");
    return (data != null && data.length() > 0) ? data : null;
  }

  /**
   * Injects {@code isLeaf} into each entry of {@code data} using the pre-built map.
   * Skips entries that have no {@code id} or whose id is not in the map.
   */
  private static void applyIsLeaf(JSONArray data, Map<String, Boolean> isSummaryMap)
      throws Exception {
    for (int i = 0; i < data.length(); i++) {
      JSONObject entry = data.optJSONObject(i);
      if (entry == null) {
        continue;
      }
      String id = entry.optString("id", null);
      Boolean isSummary = (id != null) ? isSummaryMap.get(id) : null;
      if (isSummary != null) {
        entry.put("isLeaf", !isSummary);
      }
    }
  }

  private static List<String> collectIds(JSONArray data) {
    List<String> ids = new ArrayList<>(data.length());
    for (int i = 0; i < data.length(); i++) {
      JSONObject entry = data.optJSONObject(i);
      if (entry != null) {
        String id = entry.optString("id", null);
        if (id != null && !id.isEmpty()) {
          ids.add(id);
        }
      }
    }
    return ids;
  }

  /**
   * Batch-queries {@code C_ElementValue.IsSummary} for the given list of record IDs.
   *
   * @param ids list of {@code C_ElementValue_ID} values
   * @return map of id → isSummaryLevel (true = summary/parent, false = posting/leaf)
   */
  private static Map<String, Boolean> querySummaryLevels(List<String> ids) {
    Map<String, Boolean> result = new HashMap<>(ids.size() * 2);
    OBContext.setAdminMode(true);
    try {
      OBQuery<ElementValue> qry = OBDal.getInstance()
          .createQuery(ElementValue.class, "id in :ids");
      qry.setNamedParameter("ids", ids);
      for (ElementValue ev : qry.list()) {
        result.put(ev.getId(), Boolean.TRUE.equals(ev.isSummaryLevel()));
      }
    } finally {
      OBContext.restorePreviousMode();
    }
    return result;
  }

  // ── B. Defaults — inject codePrefix from parent account ───────────────────

  /**
   * Post-hook for the DEFAULTS endpoint. Augments the AD_Column defaults already resolved
   * by the default service with a {@code codePrefix} key when {@code parentAccountId} is
   * present as a query parameter.
   *
   * <p>The response structure produced by the defaults service is:
   * <pre>{@code {"defaults": { "isActive": true, ... }}}</pre>
   * This method adds {@code "codePrefix": "<first 4 digits of parent Value>"} inside the
   * existing {@code defaults} object, so both the standard field defaults and the prefix
   * hint reach the frontend in a single response.
   *
   * <p>Query-param lookup follows the two-step pattern established by
   * {@code AmortizationHeaderHandler}:
   * <ol>
   *   <li>{@link NeoContext#getQueryParams()} — populated by the MCP path and the REST
   *       path through {@code NeoDefaultsEndpoint}.</li>
   *   <li>{@link RequestContext} HTTP parameter — fallback for callers that do not yet
   *       set queryParams in the hook context.</li>
   * </ol>
   *
   * @return the augmented {@link NeoResponse} when {@code parentAccountId} is resolved
   *         and the parent exists; {@code null} otherwise (keeps the original result).
   */
  private NeoResponse injectCodePrefix(NeoContext context) {
    String parentAccountId = resolveParentAccountId(context);
    if (parentAccountId == null) {
      return null;
    }
    NeoResponse previous = context.getPreviousResult();
    if (previous == null || previous.getBody() == null) {
      return null;
    }

    OBContext.setAdminMode(true);
    try {
      ElementValue parent = OBDal.getInstance().get(ElementValue.class, parentAccountId);
      if (parent == null) {
        log.debug("ChartOfAccountsHandler: parent account not found id={}", parentAccountId);
        return null;
      }
      String parentCode = parent.getSearchKey();
      if (parentCode == null || parentCode.length() < PGC_PREFIX_LENGTH) {
        log.debug("ChartOfAccountsHandler: parent code too short to derive prefix id={}",
            parentAccountId);
        return null;
      }

      JSONObject body = previous.getBody();
      JSONObject defaults = body.optJSONObject("defaults");
      if (defaults == null) {
        defaults = new JSONObject();
        body.put("defaults", defaults);
      }
      defaults.put("codePrefix", parentCode.substring(0, PGC_PREFIX_LENGTH));
      return NeoResponse.ok(body);
    } catch (Exception e) {
      log.error("ChartOfAccountsHandler.injectCodePrefix error for parentAccountId={}: {}",
          parentAccountId, e.getMessage(), e);
      return null;
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /**
   * Resolves {@code parentAccountId} using a two-step lookup:
   * NeoContext queryParams first, then the HTTP request via {@link RequestContext}.
   */
  private static String resolveParentAccountId(NeoContext context) {
    if (context.getQueryParams() != null) {
      String id = context.getQueryParams().get(PARAM_PARENT_ACCOUNT_ID);
      if (id != null && !id.isEmpty()) {
        return id;
      }
    }
    try {
      if (RequestContext.get() != null && RequestContext.get().getRequest() != null) {
        String id = RequestContext.get().getRequest().getParameter(PARAM_PARENT_ACCOUNT_ID);
        if (id != null && !id.isEmpty()) {
          return id;
        }
      }
    } catch (Exception e) {
      log.debug("ChartOfAccountsHandler: could not read parentAccountId from RequestContext: {}",
          e.getMessage());
    }
    return null;
  }

  // ── C. Save validation ─────────────────────────────────────────────────────

  /**
   * Validates the account code in a create or update request.
   *
   * <p>Validation rules:
   * <ol>
   *   <li>If {@code searchKey} is present in the request body it must match exactly
   *       {@value #ACCOUNT_CODE_LENGTH} decimal digits.</li>
   *   <li>For updates (PUT/PATCH): if the account has children in {@code AD_TreeNode}
   *       and the code is being changed, the update is rejected.</li>
   *   <li>For updates to leaf accounts (no children): if the first
   *       {@value #PGC_PREFIX_LENGTH} digits of the code would change, the update is
   *       rejected.</li>
   * </ol>
   *
   * <p>Returns {@code null} (fall through to default CRUD) when all validations pass
   * or when {@code searchKey} is absent from the body.
   */
  private NeoResponse validateSave(NeoContext context) {
    JSONObject body = context.getRequestBody();
    if (body == null) {
      return null;
    }

    String submittedCode = body.optString(FIELD_SEARCH_KEY, null);
    if (submittedCode == null) {
      return null; // field not being changed — skip format checks
    }

    // Validation 1: exactly 8 decimal digits
    if (!submittedCode.matches("\\d{" + ACCOUNT_CODE_LENGTH + "}")) {
      return NeoResponse.error(400, ERR_INVALID_CODE);
    }

    // New records: only format check applies (no existing code to compare against)
    boolean isNewRecord = "POST".equals(context.getHttpMethod())
        || context.getRecordId() == null;
    if (isNewRecord) {
      return null;
    }

    // Update: apply immutability rules
    OBContext.setAdminMode(true);
    try {
      return applyImmutabilityRules(context.getRecordId(), submittedCode);
    } catch (Exception e) {
      log.error("ChartOfAccountsHandler.validateSave error for recordId={}: {}",
          context.getRecordId(), e.getMessage(), e);
      return null; // let the default handler proceed
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /**
   * Applies the two immutability rules for an existing account:
   * summary-account code lock and leaf-account PGC prefix lock.
   *
   * @param recordId      the {@code C_ElementValue_ID} being updated
   * @param submittedCode the new {@code Value} submitted by the client
   * @return an error {@link NeoResponse} if a rule is violated, {@code null} otherwise
   */
  private NeoResponse applyImmutabilityRules(String recordId, String submittedCode) {
    ElementValue existing = OBDal.getInstance().get(ElementValue.class, recordId);
    if (existing == null) {
      return null; // record not found — let the default handler return 404
    }

    String currentCode = existing.getSearchKey();
    if (currentCode == null) {
      return null; // no current code to compare
    }

    boolean codeChanged = !submittedCode.equals(currentCode);
    int childrenCount = countChildren(recordId);
    boolean hasChildren = childrenCount > 0;

    // Rule 2: summary account (has children) — code must not change
    if (hasChildren && codeChanged) {
      return NeoResponse.error(400, ERR_SUMMARY_LOCKED);
    }

    // Rule 3: leaf account (no children) — PGC prefix (first 4 digits) is immutable
    if (!hasChildren && codeChanged
        && currentCode.length() >= PGC_PREFIX_LENGTH
        && submittedCode.length() >= PGC_PREFIX_LENGTH
        && !submittedCode.substring(0, PGC_PREFIX_LENGTH)
            .equals(currentCode.substring(0, PGC_PREFIX_LENGTH))) {
      return NeoResponse.error(400, ERR_PREFIX_LOCKED);
    }

    return null;
  }

  /**
   * Counts the number of immediate children of {@code parentId} in {@code AD_TreeNode}.
   * Scopes the query to the tree that contains the node (first match).
   *
   * @param parentId a {@code C_ElementValue_ID}
   * @return the number of child nodes; 0 if the node is not in any tree
   */
  @SuppressWarnings("unchecked")
  int countChildren(String parentId) {
    NativeQuery<Object> treeIdQry = (NativeQuery<Object>) OBDal.getInstance()
        .getSession()
        .createNativeQuery(SQL_TREE_ID);
    treeIdQry.setParameter("nodeId", parentId);
    List<Object> treeIdRows = treeIdQry.list();

    if (treeIdRows.isEmpty()) {
      return 0;
    }
    String treeId = String.valueOf(treeIdRows.get(0));

    NativeQuery<Object> countQry = (NativeQuery<Object>) OBDal.getInstance()
        .getSession()
        .createNativeQuery(SQL_CHILDREN_COUNT);
    countQry.setParameter("parentId", parentId);
    countQry.setParameter("treeId", treeId);
    List<Object> countRows = countQry.list();

    if (countRows.isEmpty()) {
      return 0;
    }
    Object countVal = countRows.get(0);
    if (countVal instanceof Number) {
      return ((Number) countVal).intValue();
    }
    return 0;
  }
}
