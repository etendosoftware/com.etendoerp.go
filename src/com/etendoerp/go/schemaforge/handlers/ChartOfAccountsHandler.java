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

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
import org.openbravo.model.financialmgmt.accounting.coa.AcctSchema;
import org.openbravo.model.financialmgmt.accounting.coa.ElementValue;
import org.openbravo.service.json.JsonConstants;

import com.etendoerp.go.schemaforge.NeoContext;
import com.etendoerp.go.schemaforge.NeoEndpointType;
import com.etendoerp.go.schemaforge.NeoHandler;
import com.etendoerp.go.schemaforge.NeoResponse;

/**
 * NeoHandler for the {@code chart-of-accounts} spec, bound to the
 * {@code elementValue} entity (table {@code C_ElementValue}).
 *
 * <p>Implements five behaviours:
 * <ul>
 *   <li><b>A — isLeaf enrichment</b> (afterHandle, CRUD GET): injects an {@code isLeaf}
 *       boolean into every record of the GET response based on {@code IsSummary}.
 *       {@code IsSummary = 'N'} → {@code isLeaf: true}; {@code 'Y'} → {@code false}.</li>
 *   <li><b>B — hierarchy metadata</b> (afterHandle, CRUD GET list): injects
 *       {@code parentId} (direct parent's {@code C_ElementValue_ID}, null if root),
 *       {@code depth} (hops to root; 0 = root), {@code hasChildren} (true if the node has
 *       children in {@code AD_TreeNode}), {@code parentCode4} (the {@code Value} of the
 *       nearest ancestor whose {@code Value} has exactly 4 characters, null if none — kept
 *       for backward compatibility with {@code NewAccountModal}'s parent selector),
 *       {@code elementLevel} (the node's own {@code C_ElementValue.ElementLevel}: {@code E}
 *       Heading, {@code C} Account, {@code D} Breakdown, {@code S} Subaccount), and
 *       {@code ancestors} — the FULL ancestor chain (root-to-leaf order, node itself
 *       excluded), each entry {@code {value, name, elementLevel}}, mirroring Etendo
 *       Classic's "Combinación de cuentas" grouped view so the frontend can build a genuine
 *       N-level nested tree instead of the flat 4-digit grouping. Uses a single bulk load of
 *       the full tree — no N+1 queries.</li>
 *   <li><b>C — YTD balances</b> (afterHandle, CRUD GET list): injects {@code ytdDebit},
 *       {@code ytdCredit}, and {@code ytdBalance} for the current fiscal year.
 *       Leaf balances are read from {@code fact_acct} in one query; summary-account totals
 *       are computed in-memory via a bottom-up tree rollup — no recursive SQL.</li>
 *   <li><b>D — codePrefix default</b> (handle, DEFAULTS): when {@code parentAccountId}
 *       is present as a query parameter, returns the first 4 characters of the parent's
 *       {@code Value} (account code) as {@code codePrefix} in the defaults payload.</li>
 *   <li><b>E — PGC save validation</b> (handle, CRUD POST/PUT/PATCH): code format, protected
 *       parent-like codes, cross-client duplicates on create, and code-immutability rules on
 *       update. Delegated to {@link ChartOfAccountsSaveValidationSupport#validateSave} — split
 *       out purely to keep this class's own method count under the Sonar {@code java:S1448}
 *       limit; see that class's javadoc for the full rule list.</li>
 *   <li><b>F — GL Item auto-provisioning</b> (afterHandle, CRUD POST — ETP-5020): after a
 *       successful subaccount create, ensures an invisible {@code GLItem}/{@code GLItemAccounts}
 *       pair exists behind it for every active {@code AcctSchema}, via
 *       {@link GlItemProvisioningSupport#ensureGlItemForSubaccount}. Best-effort — never blocks or
 *       rolls back the subaccount save.</li>
 *   <li><b>G — GL Item active-state sync</b> (afterHandle, CRUD PATCH/PUT — ETP-5020): when a
 *       request flips {@code active} on a subaccount (the ETP-4884 deactivate/reactivate toggle),
 *       mirrors the new state onto its {@code GLItemAccounts} row(s) via
 *       {@link GlItemProvisioningSupport#setGlItemAccountsActiveForSubaccount}, so the invisible
 *       GL Item can never silently diverge from its subaccount's active state.</li>
 *   <li><b>H — GL Item name resync</b> (afterHandle, CRUD PATCH/PUT — ETP-5101): when a
 *       request touches {@code name} or {@code searchKey} on a subaccount (a rename or a code
 *       edit), recomposes and rewrites its GL Item's name via
 *       {@link GlItemProvisioningSupport#ensureGlItemForSubaccount} — until this was added,
 *       only the POST path (F) ever refreshed the composed name, so a rename via PUT/PATCH left
 *       the GL Item's name silently stale.</li>
 * </ul>
 *
 * <p>{@code @Named} only — never a normal CDI scope. See CLAUDE.md §NeoHandler Pattern.
 */
@Named("chart-of-accounts")
public class ChartOfAccountsHandler implements NeoHandler {

  private static final Logger log = LogManager.getLogger(ChartOfAccountsHandler.class);

  /** ETP-5020 — GL Item auto-provisioning behind subaccounts. See class javadoc F/G. */
  private final GlItemProvisioningSupport glItemProvisioning = new GlItemProvisioningSupport();

  /** ETP-5101 — save validation. See class javadoc E and {@link ChartOfAccountsSaveValidationSupport}. */
  private final ChartOfAccountsSaveValidationSupport saveValidation = new ChartOfAccountsSaveValidationSupport();

  /** API field name for the account code (mapped from DB column {@code Value}). */
  static final String FIELD_SEARCH_KEY = "searchKey";

  private static final String PARAM_CLIENT_ID = "clientId";
  private static final String RESP_TOTAL_ROWS = "totalRows";
  private static final String RESP_RESPONSE = "response";

  /** Query param name for the parent account on new-record defaults calls. */
  private static final String PARAM_PARENT_ACCOUNT_ID = "parentAccountId";

  /** API/body field name for the record's active flag. */
  private static final String FIELD_ACTIVE = "active";

  /** API/body field name for the record's display name. */
  private static final String FIELD_NAME = "name";

  /**
   * Number of leading digits that form the PGC prefix (immutable for leaf accounts).
   *
   * <p>Package-private: also used by {@link ChartOfAccountsSaveValidationSupport}, which was
   * split out of this class to keep its method count under the Sonar {@code java:S1448} limit.
   */
  static final int PGC_PREFIX_LENGTH = 4;

  /**
   * Maximum number of hops traversed upward in the tree before bailing out,
   * guarding against circular references in corrupted {@code AD_TreeNode} data.
   *
   * <p>Package-private: also used by {@link ChartOfAccountsTreeMath}, which was split out of
   * this class to keep its method count under the Sonar {@code java:S1448} limit.
   */
  static final int MAX_TREE_DEPTH = 30;

  /**
   * SQL that finds the {@code AD_Tree_ID} for the {@code EV} (Element Value) tree
   * belonging to a given client. One tree per chart of accounts per client.
   */
  private static final String SQL_FIND_EV_TREE =
      "SELECT ad_tree_id FROM ad_tree "
      + "WHERE treetype = 'EV' AND ad_client_id = :clientId "
      + "LIMIT 1";

  /**
   * SQL that loads all {@code (node_id, parent_id)} pairs for a given tree.
   * Root nodes have {@code parent_id = '0'}.
   */
  private static final String SQL_LOAD_TREE_NODES =
      "SELECT node_id, parent_id FROM ad_treenode WHERE ad_tree_id = :treeId";

  /**
   * SQL that loads {@code (c_elementvalue_id, value, name, elementlevel)} rows for a given
   * client. Used to look up account codes/names/levels when walking the tree for
   * {@code parentCode4} and for the full {@code ancestors} chain.
   */
  private static final String SQL_LOAD_EV_VALUES =
      "SELECT c_elementvalue_id, value, name, elementlevel "
      + "FROM c_elementvalue WHERE ad_client_id = :clientId";

  /**
   * SQL that finds the {@code c_year_id} for the current fiscal year of a given client.
   * Joins through {@code c_period} to get year date boundaries, because {@code c_year}
   * itself has no {@code startdate}/{@code enddate} columns.
   */
  private static final String SQL_CURRENT_YEAR =
      "SELECT DISTINCT y.c_year_id "
      + "FROM c_year y "
      + "JOIN c_period p ON p.c_year_id = y.c_year_id "
      + "WHERE y.ad_client_id = :clientId "
      + "  AND CURRENT_DATE BETWEEN p.startdate AND p.enddate "
      + "LIMIT 1";

  /**
   * SQL that aggregates YTD debit, credit, and net balance per account
   * from {@code fact_acct} for all periods belonging to a given fiscal year.
   * Returns only accounts that have actual postings — summary accounts (with no direct
   * postings) are enriched later via in-memory rollup.
   */
  private static final String SQL_YTD_BALANCES =
      "SELECT fa.account_id, "
      + "  COALESCE(SUM(fa.amtacctdr), 0) AS ytd_debit, "
      + "  COALESCE(SUM(fa.amtacctcr), 0) AS ytd_credit, "
      + "  COALESCE(SUM(fa.amtacctdr - fa.amtacctcr), 0) AS ytd_balance "
      + "FROM fact_acct fa "
      + "JOIN c_period p ON fa.c_period_id = p.c_period_id "
      + "WHERE p.c_year_id = :yearId "
      + "  AND fa.ad_client_id = :clientId "
      + "GROUP BY fa.account_id";

  /**
   * SQL used for the CoA list endpoint. It intentionally bypasses
   * {@code DefaultJsonDataService}, whose readable-client filtering can return an empty list
   * for JWT-authenticated GO users even when the current client owns account records.
   */
  private static final String SQL_LIST_LEAF_ACCOUNTS =
      "SELECT c_elementvalue_id, value, name, description, accounttype, issummary, isactive "
      + "FROM c_elementvalue "
      + "WHERE ad_client_id = :clientId "
      + "  AND issummary = 'N'";

  private static final String SQL_COUNT_LEAF_ACCOUNTS =
      "SELECT COUNT(*) FROM c_elementvalue "
      + "WHERE ad_client_id = :clientId "
      + "  AND issummary = 'N'";

  private static final String SQL_GET_ACCOUNT_BY_ID =
      "SELECT c_elementvalue_id, value, name, description, accounttype, issummary, isactive "
      + "FROM c_elementvalue "
      + "WHERE ad_client_id = :clientId "
      + "  AND c_elementvalue_id = :recordId";

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
    if (context.getEndpointType() != NeoEndpointType.CRUD) {
      return null;
    }
    try {
      return handleCrudRequest(context);
    } catch (Exception e) {
      log.error("ChartOfAccountsHandler.handle error: {}", e.getMessage(), e);
      return NeoResponse.error(500, "Chart of accounts handler error: " + e.getMessage());
    }
  }

  private NeoResponse handleCrudRequest(NeoContext context) throws Exception {
    String method = context.getHttpMethod();
    if ("GET".equals(method)) {
      return context.getRecordId() == null
          ? fetchElementValuesDirectly(context)
          : fetchElementValueByIdDirectly(context);
    }
    if ("POST".equals(method) || "PUT".equals(method) || "PATCH".equals(method)) {
      return saveValidation.validateSave(context);
    }
    return null;
  }

  /**
   * Post-hook:
   * <ul>
   *   <li>CRUD GET: enriches every record with {@code isLeaf}. For list responses
   *       (no {@code recordId}), also injects hierarchy metadata ({@code parentId},
   *       {@code depth}, {@code hasChildren}, {@code parentCode4}) and YTD balance
   *       fields ({@code ytdDebit}, {@code ytdCredit}, {@code ytdBalance}).</li>
   *   <li>DEFAULTS: when {@code parentAccountId} is present, injects {@code codePrefix}
   *       (first {@value #PGC_PREFIX_LENGTH} digits of the parent's account code) into
   *       the defaults payload already resolved by the AD_Column defaults service.</li>
   * </ul>
   * On any failure the original result is preserved (method returns {@code null}).
   */
  @Override
  public NeoResponse afterHandle(NeoContext context) {
    try {
      if (context.getEndpointType() == NeoEndpointType.CRUD) {
        String method = context.getHttpMethod();
        if ("GET".equals(method)) {
          return enrichGetResponse(context);
        }
        if ("POST".equals(method)) {
          provisionGlItemAfterCreate(context);
          return null;
        }
        if ("PATCH".equals(method) || "PUT".equals(method)) {
          syncGlItemActiveState(context);
          syncGlItemNameAfterUpdate(context);
          return null;
        }
        return null;
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

  // ── F. GL Item auto-provisioning + G. active-state sync (ETP-5020) ─────────

  /**
   * F — after a successful subaccount POST, ensures its invisible GL Item exists (see class
   * javadoc). {@link NeoContext#getRecordId()} is never populated for {@code POST} (same gap
   * {@code UserRoleAssignmentHandler} documents for {@code user} creation), so the created
   * record's id is read from {@code previousResult.body.response.data[0].id} instead. Best-effort:
   * {@link GlItemProvisioningSupport#ensureGlItemForSubaccount} already swallows its own failures,
   * and any failure resolving the id/entity here is caught by {@link #afterHandle}'s own try/catch
   * — either way, nothing here can block or roll back the primary subaccount save.
   */
  private void provisionGlItemAfterCreate(NeoContext context) {
    String subaccountId = extractCreatedRecordId(context);
    if (subaccountId == null) {
      return;
    }
    OBContext.setAdminMode(true);
    try {
      ElementValue subaccount = OBDal.getInstance().get(ElementValue.class, subaccountId);
      if (subaccount == null) {
        return;
      }
      List<AcctSchema> schemas = glItemProvisioning.resolveActiveSchemas(subaccount.getClient());
      glItemProvisioning.ensureGlItemForSubaccount(subaccount, schemas);
      OBDal.getInstance().flush();
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /**
   * Reads the just-created record's {@code id} out of {@code previousResult.body.response.data[0]}
   * — confirmed (see {@code UserRoleAssignmentHandler.inviteNewlyCreatedUser}'s javadoc) to always
   * be a {@code JSONArray} of exactly one element for a single-record create response.
   */
  private static String extractCreatedRecordId(NeoContext context) {
    NeoResponse previous = context.getPreviousResult();
    JSONObject body = previous != null ? previous.getBody() : null;
    JSONObject response = body != null ? body.optJSONObject(RESP_RESPONSE) : null;
    JSONArray data = response != null ? response.optJSONArray("data") : null;
    JSONObject first = data != null && data.length() > 0 ? data.optJSONObject(0) : null;
    String id = first != null ? first.optString("id", null) : null;
    return (id == null || id.isEmpty()) ? null : id;
  }

  /**
   * G — when a PATCH/PUT touches {@code active} on a subaccount (the ETP-4884 deactivate/reactivate
   * toggle), mirrors the subaccount's new active state onto its {@code GLItemAccounts} row(s). The
   * actual new state is read back from the freshly-saved {@link ElementValue} entity rather than
   * trusting the request body's encoding of the boolean (mirrors
   * {@code UserRoleAssignmentHandler.syncRoleAfterUpdate}'s same "re-read the saved entity, don't
   * trust the wire payload" pattern) — the body is consulted only as a cheap early-exit so this
   * never runs for a PATCH that does not touch {@code active} at all.
   */
  private void syncGlItemActiveState(NeoContext context) {
    JSONObject body = context.getRequestBody();
    if (body == null || !body.has(FIELD_ACTIVE) || body.isNull(FIELD_ACTIVE)) {
      return;
    }
    String recordId = context.getRecordId();
    if (recordId == null) {
      return;
    }
    OBContext.setAdminMode(true);
    try {
      ElementValue subaccount = OBDal.getInstance().get(ElementValue.class, recordId);
      if (subaccount == null) {
        return;
      }
      List<AcctSchema> schemas = glItemProvisioning.resolveActiveSchemas(subaccount.getClient());
      boolean active = Boolean.TRUE.equals(subaccount.isActive());
      glItemProvisioning.setGlItemAccountsActiveForSubaccount(subaccount, schemas, active);
      OBDal.getInstance().flush();
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /**
   * H — when a PATCH/PUT touches {@code name} or {@code searchKey} on a subaccount, resyncs its
   * already-provisioned GL Item's composed name (see class javadoc). Reuses
   * {@link GlItemProvisioningSupport#ensureGlItemForSubaccount} — its idempotent-rerun branch
   * already recomposes and rewrites the name for a schema with an existing link (see
   * {@code GlItemProvisioningSupport#ensureGlItemForSchema}); this hook is what actually invokes
   * it after an update, since {@link #provisionGlItemAfterCreate} only runs on POST. Same cheap
   * early-exit pattern as {@link #syncGlItemActiveState} — only runs when the request body
   * actually touches one of the two fields the composed name depends on.
   */
  private void syncGlItemNameAfterUpdate(NeoContext context) {
    JSONObject body = context.getRequestBody();
    if (body == null || (!body.has(FIELD_NAME) && !body.has(FIELD_SEARCH_KEY))) {
      return;
    }
    String recordId = context.getRecordId();
    if (recordId == null) {
      return;
    }
    OBContext.setAdminMode(true);
    try {
      ElementValue subaccount = OBDal.getInstance().get(ElementValue.class, recordId);
      if (subaccount == null) {
        return;
      }
      List<AcctSchema> schemas = glItemProvisioning.resolveActiveSchemas(subaccount.getClient());
      glItemProvisioning.ensureGlItemForSubaccount(subaccount, schemas);
      OBDal.getInstance().flush();
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  // ── A. isLeaf enrichment + B. Hierarchy + C. YTD ─────────────────────────

  /**
   * Serves the list GET through native SQL so CoA remains visible when the generic
   * SmartClient datasource loses records through readable-client filtering.
   */
  @SuppressWarnings("unchecked")
  private NeoResponse fetchElementValuesDirectly(NeoContext context) throws Exception {
    OBContext obCtx = context.getObContext();
    if (obCtx == null || obCtx.getCurrentClient() == null) {
      return null;
    }

    Map<String, String> queryParams = context.getQueryParams();
    int startRow = Math.max(0, parseIntOrDefault(queryParams,
        JsonConstants.STARTROW_PARAMETER, 0));
    int requestedEndRow = parseIntOrDefault(queryParams,
        JsonConstants.ENDROW_PARAMETER, startRow + 74);
    int pageSize = Math.max(1, requestedEndRow >= startRow
        ? requestedEndRow - startRow + 1
        : 75);
    String clientId = obCtx.getCurrentClient().getId();
    Map<String, Object> sqlParams = new HashMap<>();
    String rawCriteria = queryParams != null ? queryParams.get("criteria") : null;
    String rawSortBy = queryParams != null ? queryParams.get("_sortBy") : null;
    String whereClause = buildLeafAccountWhereClause(rawCriteria, sqlParams);
    String orderBy = resolveLeafAccountOrderBy(rawSortBy);

    OBContext.setAdminMode(true);
    try {
      NativeQuery<Object> countQry = (NativeQuery<Object>) OBDal.getInstance()
          .getSession()
          .createNativeQuery(SQL_COUNT_LEAF_ACCOUNTS + whereClause);
      countQry.setParameter(PARAM_CLIENT_ID, clientId);
      ChartOfAccountsCriteria.applySqlParameters(countQry, sqlParams);
      int totalRows = toInt(countQry.uniqueResult());

      NativeQuery<Object> listQry = (NativeQuery<Object>) OBDal.getInstance()
          .getSession()
          .createNativeQuery(SQL_LIST_LEAF_ACCOUNTS + whereClause
              + " ORDER BY " + orderBy
              + " LIMIT :limit OFFSET :offset");
      listQry.setParameter(PARAM_CLIENT_ID, clientId);
      ChartOfAccountsCriteria.applySqlParameters(listQry, sqlParams);
      listQry.setParameter("limit", pageSize);
      listQry.setParameter("offset", startRow);

      JSONArray data = new JSONArray();
      for (Object rawRow : listQry.list()) {
        data.put(toAccountJson((Object[]) rawRow));
      }

      JSONObject response = new JSONObject();
      response.put("startRow", startRow);
      response.put("endRow", data.length() > 0 ? startRow + data.length() - 1 : requestedEndRow);
      response.put(RESP_TOTAL_ROWS, totalRows);
      response.put("data", data);
      response.put("status", 0);

      JSONObject body = new JSONObject();
      body.put(RESP_RESPONSE, response);
      return NeoResponse.ok(body);
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  @SuppressWarnings("unchecked")
  private NeoResponse fetchElementValueByIdDirectly(NeoContext context) throws Exception {
    OBContext obCtx = context.getObContext();
    if (obCtx == null || obCtx.getCurrentClient() == null) {
      return null;
    }

    OBContext.setAdminMode(true);
    try {
      NativeQuery<Object> detailQry = (NativeQuery<Object>) OBDal.getInstance()
          .getSession()
          .createNativeQuery(SQL_GET_ACCOUNT_BY_ID);
      detailQry.setParameter(PARAM_CLIENT_ID, obCtx.getCurrentClient().getId());
      detailQry.setParameter("recordId", context.getRecordId());

      JSONArray data = new JSONArray();
      List<Object> rows = detailQry.list();
      if (!rows.isEmpty()) {
        data.put(toAccountJson((Object[]) rows.get(0)));
      }

      JSONObject response = new JSONObject();
      response.put("startRow", 0);
      response.put("endRow", data.length() > 0 ? 0 : -1);
      response.put(RESP_TOTAL_ROWS, data.length());
      response.put("data", data);
      response.put("status", 0);

      JSONObject body = new JSONObject();
      body.put(RESP_RESPONSE, response);
      return NeoResponse.ok(body);
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  private static JSONObject toAccountJson(Object[] row) throws Exception {
    JSONObject entry = new JSONObject();
    entry.put("id", row[0]);
    entry.put(FIELD_SEARCH_KEY, row[1]);
    entry.put("name", row[2]);
    entry.put("description", row[3] != null ? row[3] : JSONObject.NULL);
    entry.put("accountType", row[4] != null ? row[4] : JSONObject.NULL);
    entry.put("summaryLevel", "Y".equals(String.valueOf(row[5])));
    entry.put(FIELD_ACTIVE, "Y".equals(String.valueOf(row[6])));
    entry.put("protectedParentLikeSubaccount",
        ChartOfAccountsSaveValidationSupport.isProtectedParentLikeSubaccount(String.valueOf(row[1])) ? "Y" : "N");
    return entry;
  }

  private static int parseIntOrDefault(Map<String, String> queryParams, String key, int fallback) {
    if (queryParams == null) {
      return fallback;
    }
    String raw = queryParams.get(key);
    if (raw == null || raw.trim().isEmpty()) {
      return fallback;
    }
    try {
      return Integer.parseInt(raw.trim());
    } catch (NumberFormatException e) {
      return fallback;
    }
  }

  private static int toInt(Object value) {
    if (value instanceof Number) {
      return ((Number) value).intValue();
    }
    return 0;
  }

  /** @see ChartOfAccountsCriteria#buildLeafAccountWhereClause */
  static String buildLeafAccountWhereClause(String rawCriteria,
      Map<String, Object> sqlParams) throws Exception {
    return ChartOfAccountsCriteria.buildLeafAccountWhereClause(rawCriteria, sqlParams);
  }

  /** @see ChartOfAccountsCriteria#resolveLeafAccountOrderBy */
  static String resolveLeafAccountOrderBy(String rawSortBy) {
    return ChartOfAccountsCriteria.resolveLeafAccountOrderBy(rawSortBy);
  }

  /**
   * Unified GET response enrichment. Always applies {@code isLeaf}; applies hierarchy
   * metadata and YTD balances only on list responses (no {@code recordId}).
   */
  private NeoResponse enrichGetResponse(NeoContext context) throws Exception {
    JSONArray data = extractDataArray(context);
    if (data == null) {
      return null;
    }
    List<String> ids = collectIds(data);
    if (ids.isEmpty()) {
      return null;
    }

    // A: isLeaf — applies to both single-record GET and list GET
    Map<String, Boolean> isSummaryMap = querySummaryLevels(ids);
    applyIsLeaf(data, isSummaryMap);

    // B + C: hierarchy metadata and YTD balances — list GET only
    boolean isList = context.getRecordId() == null;
    if (isList) {
      OBContext obCtx = context.getObContext();
      if (obCtx != null) {
        String clientId = obCtx.getCurrentClient().getId();
        TreeData treeData = loadTreeData(clientId);
        applyHierarchyMetadata(data, treeData);
        Map<String, BigDecimal[]> ytdBalances = computeYtdBalances(clientId, treeData.nodeParentMap);
        applyYtdBalances(data, ytdBalances);
      }
      // Show only subaccounts (issummary = 'N') — summary/group accounts are navigation
      // artefacts only; the UI groups rows by parentCode4 instead.
      if (containsSummaryRows(isSummaryMap)) {
        filterToSubaccounts(data, isSummaryMap, context.getPreviousResult().getBody());
      }
    }

    return NeoResponse.ok(context.getPreviousResult().getBody());
  }

  /**
   * Extracts the {@code response.data} JSONArray from the previous handler result,
   * or returns {@code null} when the structure is missing or empty.
   */
  static JSONArray extractDataArray(NeoContext context) {
    NeoResponse previous = context.getPreviousResult();
    if (previous == null || previous.getBody() == null) {
      return null;
    }
    JSONObject response = previous.getBody().optJSONObject(RESP_RESPONSE);
    if (response == null) {
      return null;
    }
    JSONArray data = response.optJSONArray("data");
    return (data != null && data.length() > 0) ? data : null;
  }

  static List<String> collectIds(JSONArray data) {
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
   * Injects {@code isLeaf} into each entry of {@code data} using the pre-built map.
   * Skips entries that have no {@code id} or whose id is not in the map.
   */
  static void applyIsLeaf(JSONArray data, Map<String, Boolean> isSummaryMap)
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

  /**
   * Removes summary accounts ({@code IsSummary = 'Y'}) from the list response in-place.
   * Rebuilds the {@code response.data} JSONArray keeping only posting/subaccounts,
   * and updates {@code response.totalRows} to match.
   */
  private static void filterToSubaccounts(JSONArray data, Map<String, Boolean> isSummaryMap,
      JSONObject responseBody) throws Exception {
    JSONArray filtered = new JSONArray();
    for (int i = 0; i < data.length(); i++) {
      JSONObject entry = data.optJSONObject(i);
      if (entry == null) {
        continue;
      }
      String id = entry.optString("id", null);
      Boolean isSummary = id != null ? isSummaryMap.get(id) : null;
      if (!Boolean.TRUE.equals(isSummary)) {
        filtered.put(entry);
      }
    }
    JSONObject response = responseBody.optJSONObject(RESP_RESPONSE);
    if (response != null) {
      response.put("data", filtered);
      response.put(RESP_TOTAL_ROWS, filtered.length());
    }
  }

  private static boolean containsSummaryRows(Map<String, Boolean> isSummaryMap) {
    return isSummaryMap.values().stream().anyMatch(Boolean.TRUE::equals);
  }

  // ── B. Hierarchy metadata ──────────────────────────────────────────────────

  /**
   * Container for the in-memory chart of accounts tree, loaded once per GET_LIST request.
   *
   * <ul>
   *   <li>{@code nodeParentMap} — {@code nodeId → parentId}; {@code null} value means root.</li>
   *   <li>{@code nodeValueMap} — {@code nodeId → Value} (account code string from
   *       {@code C_ElementValue.Value}).</li>
   *   <li>{@code nodeElementLevelMap} — {@code nodeId → ElementLevel} ({@code E} Heading,
   *       {@code C} Account, {@code D} Breakdown, {@code S} Subaccount).</li>
   *   <li>{@code parentNodeIds} — set of nodeIds that have at least one child in the tree.</li>
   * </ul>
   */
  private static class TreeData {
    final Map<String, String> nodeParentMap;
    final Map<String, String> nodeValueMap;
    final Map<String, String> nodeNameMap;
    final Map<String, String> nodeElementLevelMap;
    final Set<String> parentNodeIds;

    TreeData(Map<String, String> nodeParent, Map<String, String> nodeValue,
        Map<String, String> nodeName, Map<String, String> nodeElementLevel,
        Set<String> parents) {
      this.nodeParentMap = nodeParent;
      this.nodeValueMap = nodeValue;
      this.nodeNameMap = nodeName;
      this.nodeElementLevelMap = nodeElementLevel;
      this.parentNodeIds = parents;
    }
  }

  /**
   * Loads the full chart of accounts tree for {@code clientId} into memory.
   *
   * <p>Uses two queries: one for {@code AD_TreeNode} (parent/child relationships)
   * and one for {@code C_ElementValue} (account codes). Both run in admin mode
   * to ensure cross-org visibility within the client.
   *
   * @param clientId the {@code AD_Client_ID} of the current tenant
   * @return a populated {@link TreeData}; empty maps when no tree is found
   */
  @SuppressWarnings("unchecked")
  private TreeData loadTreeData(String clientId) {
    OBContext.setAdminMode(true);
    try {
      // 1. Find the EV tree for this client
      NativeQuery<Object> treeQry = (NativeQuery<Object>) OBDal.getInstance()
          .getSession()
          .createNativeQuery(SQL_FIND_EV_TREE);
      treeQry.setParameter(PARAM_CLIENT_ID, clientId);
      List<Object> treeRows = treeQry.list();
      if (treeRows.isEmpty()) {
        log.debug("ChartOfAccountsHandler: no EV tree found for clientId={}", clientId);
        return new TreeData(Collections.emptyMap(), Collections.emptyMap(),
            Collections.emptyMap(), Collections.emptyMap(), Collections.emptySet());
      }
      String treeId = (String) treeRows.get(0);

      // 2. Load all treenode rows for this tree
      NativeQuery<Object> nodeQry = (NativeQuery<Object>) OBDal.getInstance()
          .getSession()
          .createNativeQuery(SQL_LOAD_TREE_NODES);
      nodeQry.setParameter("treeId", treeId);
      List<Object> nodeRows = nodeQry.list();

      Map<String, String> nodeParentMap = new HashMap<>(nodeRows.size() * 2);
      Set<String> parentNodeIds = new HashSet<>();

      for (Object rawRow : nodeRows) {
        Object[] row = (Object[]) rawRow;
        String nodeId = (String) row[0];
        String parentId = (String) row[1];
        // Root nodes are marked with parent_id = '0' or NULL
        if (parentId != null && !"0".equals(parentId)) {
          nodeParentMap.put(nodeId, parentId);
          parentNodeIds.add(parentId);
        } else {
          nodeParentMap.put(nodeId, null); // root — no parent
        }
      }

      // 3. Load all element value codes for the client (for parentCode4 resolution)
      NativeQuery<Object> evQry = (NativeQuery<Object>) OBDal.getInstance()
          .getSession()
          .createNativeQuery(SQL_LOAD_EV_VALUES);
      evQry.setParameter(PARAM_CLIENT_ID, clientId);
      List<Object> evRows = evQry.list();

      Map<String, String> nodeValueMap = new HashMap<>(evRows.size() * 2);
      Map<String, String> nodeNameMap = new HashMap<>(evRows.size() * 2);
      Map<String, String> nodeElementLevelMap = new HashMap<>(evRows.size() * 2);
      for (Object rawRow : evRows) {
        Object[] row = (Object[]) rawRow;
        nodeValueMap.put((String) row[0], (String) row[1]);
        nodeNameMap.put((String) row[0], (String) row[2]);
        nodeElementLevelMap.put((String) row[0], row.length > 3 ? (String) row[3] : null);
      }

      return new TreeData(nodeParentMap, nodeValueMap, nodeNameMap, nodeElementLevelMap,
          parentNodeIds);

    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /**
   * Injects hierarchy fields into each record of the GET_LIST response.
   *
   * <p>Fields injected per record:
   * <ul>
   *   <li>{@code parentId} — {@code C_ElementValue_ID} of the direct parent; JSON null
   *       for root nodes.</li>
   *   <li>{@code depth} — number of hops from this node to the root (0 = root).</li>
   *   <li>{@code hasChildren} — {@code true} if this node appears as a parent in
   *       {@code AD_TreeNode}.</li>
   *   <li>{@code parentCode4} — {@code Value} of the nearest ancestor whose {@code Value}
   *       has exactly {@value ChartOfAccountsTreeMath#PARENT_CODE_LENGTH} characters; JSON
   *       null if none found.</li>
   *   <li>{@code elementLevel} — the node's own {@code C_ElementValue.ElementLevel}
   *       ({@code E}/{@code C}/{@code D}/{@code S}); JSON null if not found.</li>
   *   <li>{@code ancestors} — full ancestor chain, root-to-leaf, node itself excluded; each
   *       entry is {@code {value, name, elementLevel}}. Empty array for root nodes.</li>
   * </ul>
   */
  private void applyHierarchyMetadata(JSONArray data, TreeData tree) throws Exception {
    for (int i = 0; i < data.length(); i++) {
      JSONObject entry = data.optJSONObject(i);
      String id = entry != null ? entry.optString("id", null) : null;
      if (entry == null || id == null) {
        continue;
      }
      injectHierarchyFields(entry, id, tree);
    }
  }

  /**
   * Computes and writes the hierarchy fields (see {@link #applyHierarchyMetadata}) for a
   * single {@code entry}/{@code id} pair. Split out of {@link #applyHierarchyMetadata} to
   * keep cognitive complexity low — this method has no loop or nested branching of its own.
   */
  private void injectHierarchyFields(JSONObject entry, String id, TreeData tree) throws Exception {
    String parentId = tree.nodeParentMap.get(id); // null when root or not in tree
    int depth = ChartOfAccountsTreeMath.computeDepth(id, tree.nodeParentMap);
    boolean hasChildren = tree.parentNodeIds.contains(id);
    String parentCode4 = ChartOfAccountsTreeMath.findParentCode4(id, tree.nodeParentMap,
        tree.nodeValueMap);
    String parentCode4Name = ChartOfAccountsTreeMath.findParentCode4Name(id, tree.nodeParentMap,
        tree.nodeValueMap, tree.nodeNameMap);
    String elementLevel = tree.nodeElementLevelMap.get(id);
    JSONArray ancestors = ChartOfAccountsTreeMath.buildAncestorChain(id, tree.nodeParentMap,
        tree.nodeValueMap, tree.nodeNameMap, tree.nodeElementLevelMap);

    entry.put("parentId", ChartOfAccountsTreeMath.orNull(parentId));
    entry.put("depth", depth);
    entry.put("hasChildren", hasChildren);
    entry.put("parentCode4", ChartOfAccountsTreeMath.orNull(parentCode4));
    entry.put("parentCode4Name", ChartOfAccountsTreeMath.orNull(parentCode4Name));
    entry.put("elementLevel", ChartOfAccountsTreeMath.orNull(elementLevel));
    entry.put("ancestors", ancestors);
  }

  // ── C. YTD balances ───────────────────────────────────────────────────────

  /**
   * Loads YTD balances from {@code fact_acct} for the current fiscal year and rolls
   * them up to summary accounts using the in-memory tree.
   *
   * <p>Two queries are issued:
   * <ol>
   *   <li>Fiscal year lookup: finds the {@code c_year_id} whose period dates bracket
   *       {@code CURRENT_DATE} for this client.</li>
   *   <li>Balance aggregation: one {@code GROUP BY account_id} over {@code fact_acct}
   *       joined to {@code c_period} — no N+1 queries.</li>
   * </ol>
   *
   * <p>After loading leaf balances, summary accounts are enriched via
   * {@link #rollupBalances}, which propagates each node's balance to its parent
   * bottom-up. This runs entirely in memory — no recursive SQL.
   *
   * @param clientId      the {@code AD_Client_ID} of the current tenant
   * @param nodeParentMap tree parent relationship (from {@link #loadTreeData})
   * @return map of {@code C_ElementValue_ID} → {@code [ytdDebit, ytdCredit, ytdBalance]};
   *         empty map when no fiscal year is active
   */
  @SuppressWarnings("unchecked")
  private Map<String, BigDecimal[]> computeYtdBalances(String clientId,
      Map<String, String> nodeParentMap) {
    OBContext.setAdminMode(true);
    try {
      // 1. Find the current fiscal year
      NativeQuery<Object> yearQry = (NativeQuery<Object>) OBDal.getInstance()
          .getSession()
          .createNativeQuery(SQL_CURRENT_YEAR);
      yearQry.setParameter(PARAM_CLIENT_ID, clientId);
      List<Object> yearRows = yearQry.list();
      if (yearRows.isEmpty()) {
        log.debug("ChartOfAccountsHandler: no active fiscal year for clientId={}", clientId);
        return Collections.emptyMap();
      }
      String yearId = (String) yearRows.get(0);

      // 2. Aggregate YTD balances for all posting accounts
      NativeQuery<Object> balQry = (NativeQuery<Object>) OBDal.getInstance()
          .getSession()
          .createNativeQuery(SQL_YTD_BALANCES);
      balQry.setParameter("yearId", yearId);
      balQry.setParameter(PARAM_CLIENT_ID, clientId);
      List<Object> balRows = balQry.list();

      Map<String, BigDecimal[]> balances = new HashMap<>(balRows.size() * 2);
      for (Object rawRow : balRows) {
        Object[] row = (Object[]) rawRow;
        String accountId = (String) row[0];
        BigDecimal debit = toBigDecimal(row[1]);
        BigDecimal credit = toBigDecimal(row[2]);
        BigDecimal balance = toBigDecimal(row[3]);
        balances.put(accountId, new BigDecimal[]{debit, credit, balance});
      }

      // 3. Roll up leaf totals to summary/parent accounts in-memory
      rollupBalances(balances, nodeParentMap);

      return balances;

    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /**
   * Safely converts a value returned by a {@code NativeQuery} numeric column to
   * {@link BigDecimal}. Handles {@code BigDecimal}, {@code Number}, and {@code null}.
   */
  static BigDecimal toBigDecimal(Object value) {
    if (value instanceof BigDecimal) {
      return (BigDecimal) value;
    }
    if (value instanceof Number) {
      return new BigDecimal(value.toString());
    }
    return BigDecimal.ZERO;
  }

  /**
   * Propagates leaf account balances upward to their ancestors in-memory.
   *
   * <p>Algorithm: iterate over a snapshot of the initial (leaf) balance entries.
   * For each entry, walk up the {@code nodeParentMap} and add the entry's balance
   * to every ancestor. Because only the ORIGINAL leaf entries are iterated (snapshot),
   * contributions from different leaves accumulate correctly at each ancestor without
   * double-counting.
   *
   * <p>After this method returns, {@code balances} contains correct accumulated totals
   * for both leaf accounts and all their summary ancestors.
   *
   * @param balances      mutable map of account_id → [debit, credit, balance]; modified
   *                      in place to add summary account entries
   * @param nodeParentMap tree relationship (nodeId → parentId; null value = root)
   */
  static void rollupBalances(Map<String, BigDecimal[]> balances,
      Map<String, String> nodeParentMap) {
    // Snapshot the original leaf entries so we don't iterate newly created parent entries
    List<Map.Entry<String, BigDecimal[]>> leaves = new ArrayList<>(balances.entrySet());

    for (Map.Entry<String, BigDecimal[]> entry : leaves) {
      String nodeId = entry.getKey();
      BigDecimal[] leafBalance = entry.getValue();

      String parentId = nodeParentMap.get(nodeId);
      int guard = 0;
      while (parentId != null && guard < MAX_TREE_DEPTH) {
        BigDecimal[] parentBalance = balances.computeIfAbsent(parentId,
            k -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO});
        parentBalance[0] = parentBalance[0].add(leafBalance[0]);
        parentBalance[1] = parentBalance[1].add(leafBalance[1]);
        parentBalance[2] = parentBalance[2].add(leafBalance[2]);

        parentId = nodeParentMap.get(parentId);
        guard++;
      }
    }
  }

  /**
   * Injects {@code ytdDebit}, {@code ytdCredit}, and {@code ytdBalance} into each
   * record of the GET_LIST response. Records with no balance data receive zeros.
   */
  static void applyYtdBalances(JSONArray data, Map<String, BigDecimal[]> ytdBalances)
      throws Exception {
    for (int i = 0; i < data.length(); i++) {
      JSONObject entry = data.optJSONObject(i);
      String id = entry != null ? entry.optString("id", null) : null;
      if (entry != null && id != null) {
        BigDecimal[] balance = ytdBalances.get(id);
        if (balance != null) {
          entry.put("ytdDebit", balance[0]);
          entry.put("ytdCredit", balance[1]);
          entry.put("ytdBalance", balance[2]);
        } else {
          entry.put("ytdDebit", BigDecimal.ZERO);
          entry.put("ytdCredit", BigDecimal.ZERO);
          entry.put("ytdBalance", BigDecimal.ZERO);
        }
      }
    }
  }

  // ── D. Defaults — inject codePrefix from parent account ───────────────────

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
}
