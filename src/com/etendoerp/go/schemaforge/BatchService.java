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

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.criterion.MatchMode;
import org.hibernate.criterion.Restrictions;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.ui.Tab;

import com.etendoerp.go.schemaforge.data.SFEntity;
import com.etendoerp.go.schemaforge.data.SFSpec;
import com.etendoerp.go.schemaforge.util.NeoMethodPolicy;

/**
 * Generic sequential batch endpoint.
 *
 * <p>Accepts an ordered list of CRUD operations, runs them in order, and supports
 * back-references between operations. Used by the React UI to ingest a multi-record
 * document and exposed as a generic primitive that an MCP agent can compose alongside
 * {@code neo_selectors} / {@code neo_create}.</p>
 *
 * <p><b>Not atomic. This is a defect of the write path below, not a design choice
 * (IMP-23).</b> The code here does own a single transaction — one {@code commitAndClose()}
 * after the loop, {@code rollbackAndClose()} on failure — but each operation reaches core's
 * {@code DefaultJsonDataService#update}, which ends its success branch with an unconditional
 * {@code OBDal.getInstance().commitAndClose()}. So every op commits itself, and the rollback
 * here finds an empty session. There is no supported way to suppress that commit: it takes no
 * parameter, {@code SessionHandler#setDoRollback} is only read by {@code DalThreadHandler} at
 * thread end, and disabling triggers makes {@code commitAndClose} throw. Making this genuinely
 * atomic means not routing through that core method at all.</p>
 *
 * <p>Until then the failure response tells the truth instead of implying a rollback: see
 * {@code persisted} / {@code atomic} / {@code hint} in
 * {@link #failureBody(int, String, int, String, JSONObject, JSONArray)}. The failure mode is
 * asymmetric and that is why three benchmark runs read it as intermittent — a validation or
 * FK failure is caught before any op runs and really does look atomic, while a persist-time
 * failure (a value the DAL accepts and Postgres rejects) leaves the earlier ops committed.</p>
 *
 * <p>Request shape (a single window's "ingest invoice" looks like this — but
 * the same endpoint serves any spec):</p>
 *
 * <pre>
 * POST /sws/neo/batch
 * {
 *   "operations": [
 *     { "id":"bp",  "spec":"contacts",         "entity":"businessPartner", "body": {...} },
 *     { "id":"loc", "spec":"contacts",         "entity":"locationAddress",
 *       "parentRef":"bp", "body": {...} },
 *     { "id":"inv", "spec":"purchase-invoice", "entity":"Header",
 *       "body": { "businessPartner":"$ref:bp" } },
 *     { "id":"l0",  "spec":"purchase-invoice", "entity":"Lines",
 *       "parentRef":"inv", "body": {...} }
 *   ]
 * }
 * </pre>
 *
 * <p>Substitution rules applied to each {@code body} before dispatch:</p>
 * <ul>
 *   <li>Any string value of the form {@code "$ref:<opId>"} is replaced with
 *       the resolved record id of the previous op carrying that {@code id}.</li>
 *   <li>If the op declares a top-level {@code "parentRef":"<opId>"}, its
 *       resolved id is written into the body as {@code "parentId"} — same
 *       channel {@code NeoCrudHandler.executePostCreate} already reads to
 *       inject the parent FK property.</li>
 * </ul>
 *
 * <p>Response:</p>
 * <ul>
 *   <li>Success: {@code { committed:true, operations: [{ id, ok:true, recordId },…] }}.</li>
 *   <li>Failure: {@code { committed:false, atomic:false, failedAt: { id, index },
 *       persisted: [{ id, ok:true, recordId },…], hint, error: {…} }} — a rollback is attempted,
 *       but {@code persisted} lists the operations that had already committed and therefore
 *       survived it. An empty {@code persisted} means nothing landed.</li>
 * </ul>
 *
 * <p>Find-or-create logic is intentionally NOT in the server. Callers (the UI
 * descriptor or an LLM agent) decide whether to look up an entity first and
 * either embed an existing id or include a create op. This keeps the endpoint
 * generic — adding a new window requires no server code beyond the existing
 * NEO CRUD pipeline and (rarely) a {@code NeoHandler} for window-specific
 * pre/post hooks.</p>
 */
public class BatchService {

  private static final Logger log = LogManager.getLogger(BatchService.class);

  /**
   * Prefix marking a forward reference to an earlier op's recordId, substituted by
   * {@link #substituteRefs}. Public because the MCP layer must recognise these placeholders to
   * leave them alone during its FK-by-name pre-pass (IMP-15) — at that point the referenced op has
   * not run yet, so the value is not resolvable as either an id or a name.
   */
  public static final String REF_PREFIX = "$ref:";
  private static final String FIELD_COMMITTED = "committed";
  private static final String FIELD_ATOMIC = "atomic";
  private static final String FIELD_PERSISTED = "persisted";
  private static final String FIELD_HINT = "hint";
  private static final String FIELD_PARENT_ID = "parentId";
  private static final String FIELD_ID = "id";
  private static final String FIELD_ENTITY = "entity";
  private static final String FIELD_SPEC = "spec";
  private static final String FIELD_BODY = "body";
  private static final String FIELD_PARENT_REF = "parentRef";
  private static final String OPS_PREFIX = "operations[";

  private final NeoServlet servlet;
  private final NeoCrudHandler crudHandler;

  /**
   * HTTP-bound constructor used by {@link NeoServlet}. The servlet is needed
   * only by the HTTP wrapper {@link #handle(HttpServletRequest, HttpServletResponse)}
   * and is never touched by {@link #executeBatch(JSONArray)}.
   */
  BatchService(NeoServlet servlet) {
    this.servlet = servlet;
    this.crudHandler = servlet != null ? servlet.crudHandler : new NeoCrudHandler(null);
  }

  /**
   * Factory for non-HTTP callers (the MCP layer). The batch endpoint dispatches
   * exclusively through {@link NeoCrudHandler#handleDefault(NeoContext)}, which
   * does not touch the owning servlet — only {@code handleWithHooks} does. This
   * factory makes that contract explicit so callers cannot accidentally invoke
   * {@link #handle(HttpServletRequest, HttpServletResponse)} (which would NPE)
   * and so future changes that need the servlet in the default path fail at
   * construction rather than at runtime.
   *
   * @return a {@link BatchService} wired without a servlet reference, usable
   *         only via {@link #executeBatch(JSONArray)}
   */
  public static BatchService forBatchOnly() {
    return new BatchService((NeoServlet) null);
  }

  /**
   * Entry point invoked by {@link NeoServlet#processRequest} when the path
   * matches {@code /sws/neo/batch}. Reads, validates, and dispatches the
   * batch; writes the success/failure response directly.
   */
  void handle(HttpServletRequest request, HttpServletResponse response) throws IOException {
    if (servlet == null) {
      throw new IllegalStateException(
          "BatchService.handle requires the servlet-bound constructor");
    }
    JSONObject body;
    try {
      String raw = NeoRequestBodyParser.readRequestBody(request);
      if (StringUtils.isBlank(raw)) {
        servlet.sendError(response, HttpServletResponse.SC_BAD_REQUEST, "Empty request body");
        return;
      }
      body = new JSONObject(raw);
    } catch (JSONException e) {
      servlet.sendError(response, HttpServletResponse.SC_BAD_REQUEST,
          "Invalid JSON body: " + e.getMessage());
      return;
    }

    JSONArray operations = body.optJSONArray("operations");
    if (operations == null || operations.length() == 0) {
      servlet.sendError(response, HttpServletResponse.SC_BAD_REQUEST,
          "Missing or empty 'operations' array");
      return;
    }

    JSONObject result;
    try {
      result = executeBatch(operations);
    } catch (JSONException e) {
      log.error("[BATCH] JSON error while executing batch", e);
      servlet.sendError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
          "Batch failed: " + e.getMessage());
      return;
    }

    int status = HttpServletResponse.SC_OK;
    if (!result.optBoolean(FIELD_COMMITTED, false)) {
      JSONObject error = result.optJSONObject("error");
      if (error != null) {
        status = error.optInt("status", HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
      } else {
        status = HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
      }
    }
    servlet.writeResponse(response, new NeoResponse(status, result));
  }

  /**
   * Run a batch of CRUD operations in order. Owns the OBDal transaction lifecycle it can own:
   * commits after the loop, rolls back on any failure. Performs no HTTP I/O — both success and
   * failure are returned as a JSONObject for the caller to translate (HTTP wrapper, MCP content,
   * etc.).
   *
   * <p><b>The rollback does not undo earlier operations</b> — each one has already committed
   * itself inside core's write path. See the class javadoc (IMP-23); the failure body reports the
   * survivors rather than pretending they do not exist.</p>
   *
   * <p>Response shapes:</p>
   * <ul>
   *   <li>Success: {@code {committed:true, operations:[{id, ok:true, recordId},…]}}.</li>
   *   <li>Failure: {@code {committed:false, atomic:false, failedAt:{id,index},
   *       persisted:[…], hint, error:{status,message,detail?}}}.</li>
   * </ul>
   *
   * @param operations the ordered list of operation objects (must be non-null)
   * @return a JSONObject in one of the two shapes documented above
   * @throws JSONException only on truly unexpected JSON serialization failures
   */
  public JSONObject executeBatch(JSONArray operations) throws JSONException {
    if (operations == null) {
      return failureBody(-1, null, HttpServletResponse.SC_BAD_REQUEST,
          "Missing 'operations' array", null, null);
    }
    log.info("[BATCH] received {} operation(s)", operations.length());

    Map<String, String> resolvedIds = new HashMap<>();
    JSONArray opResults = new JSONArray();
    boolean commitAttempted = false;
    try {
      for (int i = 0; i < operations.length(); i++) {
        JSONObject failure = processOperation(i, operations.optJSONObject(i), resolvedIds, opResults);
        if (failure != null) {
          rollbackQuietly();
          return failure;
        }
      }
      // Mark before the call: commitAndClose closes the session even on failure,
      // so a subsequent rollbackQuietly would log a misleading "no session" error.
      commitAttempted = true;
      OBDal.getInstance().commitAndClose();
      log.info("[BATCH] committed {} operation(s)", opResults.length());
      JSONObject ok = new JSONObject();
      ok.put(FIELD_COMMITTED, true);
      ok.put("operations", opResults);
      return ok;
    } catch (Exception e) {
      log.error("[BATCH] unexpected failure", e);
      if (!commitAttempted) {
        rollbackQuietly();
      }
      return failureBody(-1, null, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
          "Batch failed: " + e.getMessage(), null, opResults);
    }
  }

  /**
   * Process one batch operation. Returns {@code null} on success (the op was
   * dispatched and its recordId stored in {@code resolvedIds} / {@code opResults}),
   * or a failure body that the caller should return after rolling back.
   */
  private JSONObject processOperation(int i, JSONObject op, Map<String, String> resolvedIds,
      JSONArray opResults) throws JSONException {
    if (op == null) {
      return failureBody(i, null, HttpServletResponse.SC_BAD_REQUEST,
          OPS_PREFIX + i + "] must be an object", null, opResults);
    }
    String opId = op.optString(FIELD_ID, null);
    String specName = op.optString(FIELD_SPEC, null);
    String entityName = op.optString(FIELD_ENTITY, null);
    if (StringUtils.isBlank(opId) || StringUtils.isBlank(specName) || StringUtils.isBlank(entityName)) {
      return failureBody(i, opId, HttpServletResponse.SC_BAD_REQUEST,
          OPS_PREFIX + i + "] requires 'id', 'spec', 'entity'", null, opResults);
    }
    if (resolvedIds.containsKey(opId)) {
      return failureBody(i, opId, HttpServletResponse.SC_BAD_REQUEST,
          OPS_PREFIX + i + "].id duplicates an earlier op", null, opResults);
    }
    SFSpec spec = NeoServletSupport.findSpec(specName);
    if (spec == null) {
      return failureBody(i, opId, HttpServletResponse.SC_NOT_FOUND,
          "Spec not found: " + specName, null, opResults);
    }

    JSONObject opBody = op.optJSONObject(FIELD_BODY);
    if (opBody == null) {
      opBody = new JSONObject();
    }
    JSONObject substitutionFailure = trySubstituteRefs(i, opId, opBody, resolvedIds, opResults);
    if (substitutionFailure != null) {
      return substitutionFailure;
    }

    String parentRef = op.optString(FIELD_PARENT_REF, null);
    if (StringUtils.isNotBlank(parentRef) && !resolvedIds.containsKey(parentRef)) {
      return failureBody(i, opId, HttpServletResponse.SC_BAD_REQUEST,
          OPS_PREFIX + i + "].parentRef '" + parentRef + "' does not match any earlier op id",
          null, opResults);
    }
    String parentId = resolveParentId(op, resolvedIds, opBody);

    NeoResponse rowResp = createRecord(spec, entityName, opBody, parentId);
    if (!isSuccess(rowResp)) {
      log.warn("[BATCH] op '{}' (index {}) failed with status {}", opId, i, rowResp.getHttpStatus());
      return failureBody(i, opId, rowResp.getHttpStatus(),
          "Operation '" + opId + "' rejected by server", rowResp.getBody(), opResults);
    }
    String recordId = extractRecordId(rowResp.getBody());
    if (StringUtils.isBlank(recordId)) {
      return failureBody(i, opId, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
          "Operation '" + opId + "' created but id missing in response", null, opResults);
    }
    resolvedIds.put(opId, recordId);
    JSONObject perOp = new JSONObject();
    perOp.put(FIELD_ID, opId);
    perOp.put("ok", true);
    perOp.put("recordId", recordId);
    opResults.put(perOp);
    return null;
  }

  /**
   * Run {@link #substituteRefs} and convert any {@link JSONException} into a
   * batch failure body. Keeps the per-op flow free of nested try/catch.
   */
  private JSONObject trySubstituteRefs(int i, String opId, JSONObject opBody,
      Map<String, String> resolvedIds, JSONArray opResults) throws JSONException {
    try {
      substituteRefs(opBody, resolvedIds);
      return null;
    } catch (JSONException e) {
      return failureBody(i, opId, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
          "Failed to substitute refs: " + e.getMessage(), null, opResults);
    }
  }

  private void rollbackQuietly() {
    try {
      OBDal.getInstance().rollbackAndClose();
    } catch (Exception rollbackErr) {
      log.error("[BATCH] rollback failed", rollbackErr);
    }
  }

  /**
   * Build the failure JSON payload. {@code detail} is optional — pass the
   * sub-response body when one is available so callers can correlate the
   * underlying NEO error.
   *
   * <p><b>{@code persisted} is not decoration (IMP-23).</b> Because every op commits itself
   * (see {@link #executeBatch}), the ops that ran before the failing one are already durable, and
   * this array is the caller's only record of them. It used to be discarded here while sitting in
   * memory one frame up, which is how a benchmark run left an orphan {@code sales-order} header
   * undeleted for five days: the response said {@code committed:false} and gave the caller no reason
   * to look for a record that existed. An empty array is reported explicitly rather than omitted —
   * "nothing survived" and "we are not telling you" must not look alike.</p>
   *
   * @param persisted the successful-op results accumulated so far, or {@code null} when the batch
   *                  failed before any op ran
   */
  private JSONObject failureBody(int index, String opId, int status, String message,
      JSONObject detail, JSONArray persisted) throws JSONException {
    JSONObject body = new JSONObject();
    body.put(FIELD_COMMITTED, false);
    JSONArray survivors = persisted != null ? persisted : new JSONArray();
    body.put(FIELD_ATOMIC, false);
    body.put(FIELD_PERSISTED, survivors);
    body.put(FIELD_HINT, survivors.length() > 0
        ? survivors.length() + " operation(s) that ran before the failure were already committed and "
            + "were NOT rolled back — see 'persisted' for their recordIds. Those records exist: "
            + "delete them, or reuse them and retry only the remaining operations. Retrying the "
            + "whole batch as-is will create duplicates."
        : "No operation persisted this time, but this endpoint is not atomic: a failure at persist "
            + "time (rather than validation time) leaves earlier operations committed. Always read "
            + "'persisted' rather than assuming a failed batch wrote nothing.");
    JSONObject failedAt = new JSONObject();
    failedAt.put("index", index);
    if (opId != null) {
      failedAt.put(FIELD_ID, opId);
    }
    body.put("failedAt", failedAt);
    JSONObject error = new JSONObject();
    error.put("status", status);
    error.put("message", message);
    if (detail != null) {
      error.put("detail", detail);
    }
    body.put("error", error);
    return body;
  }

  /**
   * Resolve the {@code parentRef} field of an op into a concrete parent id.
   * Returns {@code null} when the op declares no parentRef. Throws nothing —
   * unknown refs surface as failures one level up so the caller controls the
   * error response.
   *
   * <p>If the body already carries an explicit {@code "parentId"}, that value
   * wins (the caller may already have resolved it themselves).</p>
   */
  private String resolveParentId(JSONObject op, Map<String, String> resolvedIds, JSONObject opBody) {
    if (opBody.has(FIELD_PARENT_ID)) {
      String explicit = opBody.optString(FIELD_PARENT_ID, null);
      if (StringUtils.isNotBlank(explicit)) {
        return explicit;
      }
    }
    String parentRef = op.optString(FIELD_PARENT_REF, null);
    if (StringUtils.isBlank(parentRef)) {
      return null;
    }
    return resolvedIds.get(parentRef);
  }

  /**
   * Replace every {@code "$ref:<opId>"} string value inside {@code obj} with
   * the resolved id. Recurses through nested objects and arrays. Refs whose
   * op has not yet been resolved are left as-is so the surrounding logic can
   * surface the broken link as a validation error if the field reaches the
   * server unresolved.
   */
  private void substituteRefs(JSONObject obj, Map<String, String> resolvedIds) throws JSONException {
    if (obj == null || resolvedIds.isEmpty()) {
      return;
    }
    List<String> keys = new ArrayList<>();
    Iterator<String> it = obj.keys();
    while (it.hasNext()) {
      keys.add(it.next());
    }
    for (String key : keys) {
      Object value = obj.opt(key);
      if (value instanceof String) {
        String resolved = maybeResolveRef((String) value, resolvedIds);
        if (resolved != null) {
          obj.put(key, resolved);
        }
      } else if (value instanceof JSONObject) {
        substituteRefs((JSONObject) value, resolvedIds);
      } else if (value instanceof JSONArray) {
        substituteRefsInArray((JSONArray) value, resolvedIds);
      }
    }
  }

  private void substituteRefsInArray(JSONArray arr, Map<String, String> resolvedIds) throws JSONException {
    for (int i = 0; i < arr.length(); i++) {
      Object value = arr.opt(i);
      if (value instanceof String) {
        String resolved = maybeResolveRef((String) value, resolvedIds);
        if (resolved != null) {
          arr.put(i, resolved);
        }
      } else if (value instanceof JSONObject) {
        substituteRefs((JSONObject) value, resolvedIds);
      } else if (value instanceof JSONArray) {
        substituteRefsInArray((JSONArray) value, resolvedIds);
      }
    }
  }

  private String maybeResolveRef(String value, Map<String, String> resolvedIds) {
    if (value == null || !value.startsWith(REF_PREFIX)) {
      return null;
    }
    String opId = value.substring(REF_PREFIX.length());
    return resolvedIds.get(opId);
  }

  /**
   * Run the standard NEO POST pipeline (defaults injection, callout cascade,
   * field filtering, DefaultJsonDataService.add) for a single record without
   * going through HTTP. The OBDal session is shared across all calls within a
   * single batch request, so all writes participate in one transaction.
   */
  private NeoResponse createRecord(SFSpec spec, String entityName, JSONObject body, String parentId) {
    SFEntity sfEntity = findEntity(spec.getId(), entityName);
    if (sfEntity == null) {
      return NeoResponse.error(HttpServletResponse.SC_NOT_FOUND,
          "Entity not found in spec '" + spec.getId() + "': " + entityName);
    }
    // ETP-4254: /batch (and MCP neo_batch, which shares this method) enters the CRUD
    // pipeline at NeoCrudHandler#handleDefault — i.e. AFTER the method-flag gate in
    // handleWindowEntityCrud. Without this check a read-only entity (all mutation flags
    // 'N', e.g. the SII/VeriFactu monitor logs) rejected a direct POST with 405 while
    // still accepting the very same create when smuggled inside a batch operation.
    if (!NeoMethodPolicy.isMethodEnabled(sfEntity, NeoMethodPolicy.METHOD_POST)) {
      return NeoResponse.error(HttpServletResponse.SC_METHOD_NOT_ALLOWED,
          NeoMethodPolicy.buildNotEnabledMessage(NeoMethodPolicy.METHOD_POST, entityName));
    }

    Tab adTab = sfEntity.getADTab();
    if (adTab == null) {
      return NeoResponse.error(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
          "No AD_Tab linked to entity: " + entityName);
    }

    // Only inject when the body does not already carry an explicit parentId.
    // resolveParentId already honours that explicit value, so this guard keeps
    // body precedence even if the resolver semantics change later.
    if (parentId != null && !body.has(FIELD_PARENT_ID)) {
      try {
        body.put(FIELD_PARENT_ID, parentId);
      } catch (JSONException e) {
        return NeoResponse.error(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
            "Failed to set parentId: " + e.getMessage());
      }
    }

    // A custom NeoHandler (e.g. Contacts' locationAddress -> ContactsLocationAddressHandler)
    // reads its parent id from queryParams, exactly like the real HTTP endpoint it also
    // serves (POST .../locationAddress?parentId={bpId}) - body-only wouldn't reach it.
    Map<String, String> queryParams = parentId != null
        ? Collections.singletonMap(FIELD_PARENT_ID, parentId)
        : Collections.emptyMap();

    NeoContext ctx = NeoContext.builder()
        .specName(spec.getId())
        .entityName(entityName)
        .httpMethod("POST")
        .requestBody(body)
        .queryParams(queryParams)
        .adTab(adTab)
        .sfEntity(sfEntity)
        .obContext(OBContext.getOBContext())
        .endpointType(NeoEndpointType.CRUD)
        .build();

    // Entities with a configured Java_Qualifier own logic the generic CRUD path knows
    // nothing about (e.g. locationAddress creates a nested C_Location from fields the
    // C_BPartner_Location join entity itself never exposes) - dispatching straight to
    // handleDefault silently skipped that logic for every /batch op, unlike the direct
    // HTTP endpoint for the same entity, which always routes through handleWithHooks.
    // Confirmed via a real import run: a location op created a bare C_BPartner_Location
    // row with none of its required fields populated, hitting a raw Postgres NOT NULL
    // violation instead of ever running ContactsLocationAddressHandler.
    String javaQualifier = sfEntity.getJavaQualifier();
    if (StringUtils.isNotBlank(javaQualifier)) {
      return NeoServletSupport.handleWithHooks(javaQualifier, ctx, crudHandler);
    }
    return crudHandler.handleDefault(ctx);
  }

  /**
   * Find an active, included {@link SFEntity} by parent spec ID and entity
   * name. Mirrors {@link NeoServlet#findEntity(String, String)} so the batch
   * service does not need a servlet reference for non-HTTP callers.
   */
  private SFEntity findEntity(String specId, String entityName) {
    OBCriteria<SFEntity> criteria = OBDal.getInstance().createCriteria(SFEntity.class);
    criteria.add(Restrictions.eq(SFEntity.PROPERTY_ETGOSFSPEC + ".id", specId));
    criteria.add(Restrictions.ilike(SFEntity.PROPERTY_NAME, entityName, MatchMode.EXACT));
    criteria.add(Restrictions.eq(SFEntity.PROPERTY_ISACTIVE, true));
    criteria.add(Restrictions.eq(SFEntity.PROPERTY_ISINCLUDED, true));
    criteria.setMaxResults(1);
    List<SFEntity> results = criteria.list();
    return results.isEmpty() ? null : results.get(0);
  }

  private static boolean isSuccess(NeoResponse response) {
    if (response == null) {
      return false;
    }
    int status = response.getHttpStatus();
    return status >= 200 && status < 300;
  }

  private static String extractRecordId(JSONObject responseBody) {
    if (responseBody == null) {
      return null;
    }
    JSONObject inner = responseBody.optJSONObject("response");
    if (inner == null) {
      return null;
    }
    JSONArray data = inner.optJSONArray("data");
    if (data == null || data.length() == 0) {
      return null;
    }
    JSONObject row = data.optJSONObject(0);
    if (row == null) {
      return null;
    }
    String id = row.optString(FIELD_ID, null);
    return StringUtils.isBlank(id) ? null : id;
  }
}
