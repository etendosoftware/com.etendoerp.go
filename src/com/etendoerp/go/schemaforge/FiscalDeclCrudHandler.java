/*
 *************************************************************************
 * The contents of this file are subject to the Etendo License
 * (the "License"), you may not use this file except in compliance
 * with the License.
 * You may obtain a copy of the License at
 * https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * Software distributed under the License is distributed on an
 * "AS IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
 * implied. See the License for the specific language governing rights
 * and limitations under the License.
 * All portions are Copyright (C) 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 *************************************************************************
 */
package com.etendoerp.go.schemaforge;

import java.io.BufferedReader;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBQuery;
import org.openbravo.dal.service.OBDal;

import com.etendoerp.go.schemaforge.data.FiscalDecl;

class FiscalDeclCrudHandler {

  private static final Logger log = LogManager.getLogger(FiscalDeclCrudHandler.class);

  static final String DEFAULT_STATUS = "draft";

  static final String ENTITY_FISCAL_DECL = FiscalDecl.ENTITY_NAME;
  static final String PROPERTY_FISCAL_MODEL = "fiscalModel";
  static final String PROPERTY_FISCAL_YEAR = "fiscalYear";
  static final String PROPERTY_PERIOD = "period";
  static final String PROPERTY_DECLARATION_TYPE = "declarationType";
  static final String PROPERTY_DECLARATION_STATUS = "declarationStatus";
  static final String PROPERTY_DECLARATION_FILE_NAME = "declarationFileName";
  static final String PROPERTY_FILE_EXTERNAL = "fileExternal";
  /**
   * Java property for the {@code manual_data} TEXT column added to {@code ETGO_Fiscal_Decl} —
   * persists, as a compact JSON string, the frontend's manually-entered Modelo 303
   * identification checks and box-value overrides (previously kept only in ephemeral React
   * state, lost on every page refresh). Read back by {@link #declToJson} as a parsed nested
   * JSON object so the frontend never has to double-parse.
   */
  static final String PROPERTY_MANUAL_DATA = "manualData";
  /**
   * Java property for the {@code submission_method} VARCHAR(30) column added to
   * {@code ETGO_Fiscal_Decl} (ETP-4755) — distinguishes the 3 distinct code paths that can all
   * lead to a "Presentado" declaration, two of which collide on the exact same
   * {@code declarationStatus} value ({@code submitted_ack}): a manual acuse/justificante upload
   * ({@link #SUBMISSION_METHOD_MANUAL_ACK}), a manual submission with no receipt
   * ({@link #SUBMISSION_METHOD_MANUAL_NO_RECEIPT}, paired with {@code declarationStatus =
   * "submitted"}), and a real AEAT telematic submission ({@link #SUBMISSION_METHOD_AEAT_TELEMATIC},
   * set server-side only — see {@code Fiscal303SubmissionSupport#persistSuccessfulSubmission}).
   * Nullable: existing rows predate this feature and simply have no value, which is correct (not
   * an error state). Freeform string, no AD Reference/List — same precedent as the pre-existing
   * {@link #PROPERTY_DECLARATION_STATUS} column.
   */
  static final String PROPERTY_SUBMISSION_METHOD = "submissionMethod";

  /**
   * Entity name (= DB table name) for the AEAT validation-error rows persisted on every Modelo
   * 303 submission attempt (see {@link Fiscal303SubmissionSupport#handleSubmit} /
   * {@link #replaceIncidents}). Referenced by its raw entity-name string rather than a generated
   * entity class — {@code OBDal.createQuery(String, ...)} resolves it dynamically, so this code
   * has no compile-time dependency on a {@code src-gen} class for the table. Must match
   * {@code AD_Table.tablename} exactly (case-sensitive) — Postgres folds unquoted identifiers to
   * lowercase on {@code CREATE TABLE}, so this is {@code etgo_fiscal_decl_incident}, not the
   * mixed-case name passed to the table-creation webhook.
   */
  static final String ENTITY_FISCAL_DECL_INCIDENT = "etgo_fiscal_decl_incident";
  /**
   * Java property name for the FK column back to {@code ETGO_Fiscal_Decl}. Etendo derives this
   * from the AD_Element name assigned when the column is created via {@code /etendo:alter-db}
   * (element name "Fiscal Declaration" -&gt; property {@code fiscalDeclaration}) — verify this
   * against the generated entity once the table is actually created; a mismatch here fails at
   * runtime (HQL parse error on {@link #queryIncidents}), not at compile time.
   */
  static final String PROPERTY_INCIDENT_DECL = "fiscalDeclaration";
  static final String PROPERTY_INCIDENT_CODE = "code";
  static final String PROPERTY_INCIDENT_MESSAGE = "message";
  /**
   * Java property for the {@code severity} column added to {@code ETGO_Fiscal_Decl_Incident}
   * (ETP-4456, AEAT warnings persistence) — distinguishes AEAT blocking errors from non-blocking
   * warnings ({@code avisos}/{@code advertencias}). Values are exactly {@link #SEVERITY_BLOCK} /
   * {@link #SEVERITY_WARN} — chosen to match the frontend's existing severity vocabulary
   * ({@code IncidentsTab}, {@code fiscalModelsUtils.js}) verbatim, so no translation/mapping layer
   * is needed on either side of the wire.
   */
  static final String PROPERTY_INCIDENT_SEVERITY = "severity";
  /** Blocking AEAT error — matches {@code AEAT303SubmissionResult#getErrors()}. */
  static final String SEVERITY_BLOCK = "block";
  /** Non-blocking AEAT warning — matches {@code AEAT303SubmissionResult#getWarnings()}. */
  static final String SEVERITY_WARN = "warn";

  /** Matches a raw AEAT error string as {@code "<code> - <message>"}, e.g. {@code "35068 - El
   * resultado a ingresar..."} or {@code "E010124 - Para periodo mensual..."} — the code token is
   * always non-whitespace, alphanumeric. Falls back to an empty code when a string doesn't match
   * (defensive: AEAT's error format is not contractually guaranteed).
   *
   * <p><b>SonarQube hotspot (java:S5852, ReDoS/backtracking) reviewed for ETP-4456 — not
   * exploitable, kept as-is.</b> {@code \S+} and the {@code \s*} that follows it sit on strictly
   * complementary character classes (a char is either whitespace or not), so there is no
   * combinatorial ambiguity between them: {@code \S+} only ever backtracks by giving back
   * characters it itself consumed (all non-whitespace, by definition), and each backtrack step is
   * an O(1) check for the literal {@code -}, so the worst case (e.g. a long run of non-whitespace
   * with no matching dash at all) is linear in input length, not exponential. The one spot with
   * any real ambiguity is {@code \s*} directly followed by {@code .+} (both can match a whitespace
   * char), which resolves in at most one backtrack step since {@code .+} only needs to give back a
   * single character to reach {@code $}. An earlier attempt to "harden" this with possessive
   * quantifiers ({@code \S++\s*+-\s*+.++}) was REVERTED (code review, ETP-4456): possessive
   * quantifiers forbid backtracking entirely, which silently changes the match result for inputs
   * like {@code "35068-El resultado..."} (no space before the dash) — the greedy version finds the
   * split by backtracking {@code \S+} back through the dash itself (the dash is non-whitespace, so
   * {@code \S+} initially swallows it too); the possessive version can never give that dash back,
   * so it fails to match and falls back to an empty code. The 4 pre-existing {@code
   * splitAeatError} tests all use {@code "CODE - message"} (space on both sides of the dash) and
   * did not catch this — a regression test covering the no-space format (e.g. {@code
   * "35068-mensaje"}) is recommended before touching this pattern again. */
  private static final Pattern AEAT_ERROR_PATTERN = Pattern.compile("^(\\S+)\\s*-\\s*(.+)$");

  private static final String PROPERTY_CLIENT = "client";
  private static final String PROPERTY_ORGANIZATION = "organization";
  private static final String PROPERTY_CREATED_BY = "createdBy";
  private static final String PROPERTY_UPDATED = "updated";
  private static final String PROPERTY_UPDATED_BY = "updatedBy";
  private static final String JSON_CONTENT_TYPE = "application/json;charset=UTF-8";
  private static final String PERIOD_KEY        = "period";
  private static final String STATUS_KEY        = "status";
  private static final String FILE_NAME_KEY     = "fileName";
  private static final String FILE_EXTERNAL_KEY = "fileExternal";
  private static final String MANUAL_DATA_KEY   = "manualData";
  private static final String SUBMISSION_METHOD_KEY = "submissionMethod";
  private static final String CODE_KEY          = "code";
  private static final String MESSAGE_KEY       = "message";
  private static final String SEVERITY_KEY      = "severity";
  private static final String MISSING_ID_PARAM  = "Missing param: id";
  private static final String DECL_NOT_FOUND_PREFIX = "Declaration not found: ";

  private final NeoServlet servlet;

  FiscalDeclCrudHandler(NeoServlet servlet) {
    this.servlet = servlet;
  }

  void handleDeclarations(String method, HttpServletRequest request,
      HttpServletResponse response) throws Exception {
    String clientId = OBContext.getOBContext().getCurrentClient().getId();
    String orgId    = OBContext.getOBContext().getCurrentOrganization().getId();
    response.setContentType(JSON_CONTENT_TYPE);
    if ("GET".equals(method)) {
      handleDeclGet(clientId, orgId, response);
    } else if ("POST".equals(method)) {
      handleDeclPost(request, response);
    } else if ("PUT".equals(method)) {
      handleDeclPut(request, response);
    } else if ("DELETE".equals(method)) {
      handleDeclDelete(request, response);
    } else {
      servlet.sendError(response, HttpServletResponse.SC_METHOD_NOT_ALLOWED,
          "Unsupported method for /fiscal303/declarations: " + method);
    }
  }

  private void handleDeclGet(String clientId, String orgId, HttpServletResponse response)
      throws Exception {
    OBQuery<BaseOBObject> query = OBDal.getInstance().createQuery(ENTITY_FISCAL_DECL,
        "client.id = :clientId and organization.id = :orgId "
            + "order by fiscalYear desc, period desc, fiscalModel asc");
    query.setNamedParameter("clientId", clientId);
    query.setNamedParameter("orgId", orgId);
    JSONArray arr = new JSONArray();
    for (BaseOBObject decl : query.list()) arr.put(declToJson(decl));
    JSONObject out = new JSONObject();
    out.put("data", arr);
    response.getWriter().write(out.toString());
  }

  private void handleDeclPost(HttpServletRequest request,
      HttpServletResponse response) throws Exception {
    JSONObject body = readJsonBody(request);
    String model    = body.getString("model");
    long   year     = body.getLong("year");
    String period   = body.getString(PERIOD_KEY);
    String declType = "com".equals(body.optString("type")) ? "C" : "O";
    String status   = body.has(STATUS_KEY) ? body.getString(STATUS_KEY) : DEFAULT_STATUS;

    BaseOBObject decl = (BaseOBObject) OBProvider.getInstance().get(ENTITY_FISCAL_DECL);
    decl.set(PROPERTY_CLIENT, OBContext.getOBContext().getCurrentClient());
    decl.set(PROPERTY_ORGANIZATION, OBContext.getOBContext().getCurrentOrganization());
    decl.set(PROPERTY_CREATED_BY, OBContext.getOBContext().getUser());
    decl.set(PROPERTY_UPDATED_BY, OBContext.getOBContext().getUser());
    decl.set(PROPERTY_FISCAL_MODEL, model);
    decl.set(PROPERTY_FISCAL_YEAR, year);
    decl.set(PROPERTY_PERIOD, period);
    decl.set(PROPERTY_DECLARATION_TYPE, declType);
    decl.set(PROPERTY_DECLARATION_STATUS, status);
    OBDal.getInstance().save(decl);
    JSONObject created = declToJson(decl);
    OBDal.getInstance().commitAndClose();

    response.setStatus(HttpServletResponse.SC_CREATED);
    response.getWriter().write(created.toString());
  }

  private void handleDeclPut(HttpServletRequest request, HttpServletResponse response)
      throws Exception {
    String id = request.getParameter("id");
    BaseOBObject decl = resolveOwnedDeclaration(id, response);
    if (decl == null) {
      return;
    }
    JSONObject body      = readJsonBody(request);
    boolean hasStatus    = body.has(STATUS_KEY);
    String status        = hasStatus ? body.getString(STATUS_KEY) : null;
    boolean hasFileExt   = body.has(FILE_EXTERNAL_KEY);
    boolean fileExternal = body.optBoolean(FILE_EXTERNAL_KEY, false);
    boolean hasFileName  = body.has(FILE_NAME_KEY);
    String  fileName     = hasFileName && !body.isNull(FILE_NAME_KEY)
        ? body.getString(FILE_NAME_KEY) : null;
    // manualData is the only optional field here that is an arbitrary nested object rather than
    // a scalar the caller can't realistically malform, so it gets its own defensive setter
    // (see setManualDataIfPresent) instead of the getString/optBoolean one-liners above.
    //
    // Unlike fileName above (hasFileName = body.has(...), no null-check — an explicit null there
    // DOES clear the stored value via decl.set(..., null)), an explicit "manualData": null is
    // treated as "not sent" rather than "clear the value": manualData is autosaved from ephemeral
    // frontend state, so a caller wanting to reset it must send an empty object rather than null —
    // treating null as "clear" would risk silently wiping real user data from a stray/racy
    // autosave call. This asymmetry with fileName is intentional, not an oversight.
    boolean hasManualData = body.has(MANUAL_DATA_KEY) && !body.isNull(MANUAL_DATA_KEY);
    // submissionMethod (ETP-4755) follows the exact same "explicit null means not sent" precedent
    // as manualData above, not fileName's "explicit null clears it" one: this field is set once,
    // at the same time as the status change that makes the declaration "Presentado" (see
    // FmOverlays.jsx's PresentModal / handlePresent call sites), and is never meant to be wiped by
    // a stray/racy PUT that happens to include a null for it.
    boolean hasSubmissionMethod = body.has(SUBMISSION_METHOD_KEY) && !body.isNull(SUBMISSION_METHOD_KEY);
    String submissionMethod = hasSubmissionMethod ? body.getString(SUBMISSION_METHOD_KEY) : null;

    if (hasStatus)     decl.set(PROPERTY_DECLARATION_STATUS, status);
    if (hasFileExt)    decl.set(PROPERTY_FILE_EXTERNAL, fileExternal);
    if (hasFileName)   decl.set(PROPERTY_DECLARATION_FILE_NAME, fileName);
    if (hasSubmissionMethod) decl.set(PROPERTY_SUBMISSION_METHOD, submissionMethod);
    boolean manualDataApplied = !hasManualData || setManualDataIfPresent(decl, body);
    decl.set(PROPERTY_UPDATED_BY, OBContext.getOBContext().getUser());
    OBDal.getInstance().commitAndClose();
    if (manualDataApplied) {
      response.getWriter().write("{\"ok\":true}");
    } else {
      response.getWriter().write("{\"ok\":true,\"manualDataApplied\":false}");
    }
  }

  /**
   * Persists the {@code manualData} PUT field — a nested JSON object combining the frontend's
   * manually-entered Modelo 303 identification checks and box-value overrides — as a compact
   * JSON string in the {@code manual_data} column. Called only when the caller sent a non-null
   * {@code manualData}; {@link #handleDeclPut} is otherwise deliberately silent about fields the
   * caller didn't send.
   *
   * <p>Deliberately does NOT throw on a malformed {@code manualData} value: every other optional
   * field in {@link #handleDeclPut} is a scalar (string/boolean) that the caller can't
   * realistically send malformed, so that method has no precedent for failing the whole PUT over
   * one bad field, and this shape (an arbitrary tenant-authored JSON blob persisted into a TEXT
   * column) already degrades gracefully elsewhere in this package — see
   * {@code NeoFiscalModelsCatalogService#getActiveModels()} discarding an unparsable stored value
   * rather than surfacing an error. Skipping (with a warning log) keeps that same tolerance on
   * the write side: a malformed {@code manualData} simply leaves the previously stored value
   * untouched instead of 500ing/400ing an otherwise-valid status/fileName/fileExternal update.
   *
   * <p>Returns {@code false} only in that skip case, so {@link #handleDeclPut} can tell the
   * frontend the rest of the PUT applied but this one field silently didn't (see the
   * {@code manualDataApplied} response flag) instead of unconditionally claiming full success.
   *
   * @return {@code true} if {@code manualData} was applied (this method is only called when the
   *         field is present and non-null); {@code false} if it was present but malformed and the
   *         write was skipped.
   */
  private boolean setManualDataIfPresent(BaseOBObject decl, JSONObject body) {
    try {
      decl.set(PROPERTY_MANUAL_DATA, body.getJSONObject(MANUAL_DATA_KEY).toString());
      return true;
    } catch (JSONException e) {
      // Thrown by getJSONObject() when manualData is absent (can't happen here — the caller only
      // invokes this when body.has(MANUAL_DATA_KEY) is true) or, the real case, when its value is
      // not a JSON object (e.g. a string or a JSON array) — jettison's JSONObject#getJSONObject
      // only ever throws JSONException (verified against its bytecode), so this catch is
      // deliberately narrow: it swallows parse/type-mismatch failures only, nothing else.
      log.warn("Ignoring malformed manualData in PUT /fiscal303/declarations: {}", e.getMessage());
      return false;
    }
  }

  private void handleDeclDelete(HttpServletRequest request, HttpServletResponse response)
      throws Exception {
    String id = request.getParameter("id");
    BaseOBObject decl = resolveOwnedDeclaration(id, response);
    if (decl == null) {
      return;
    }
    OBDal.getInstance().remove(decl);
    OBDal.getInstance().commitAndClose();
    response.getWriter().write("{\"ok\":true}");
  }

  /**
   * Resolves a declaration by id and verifies it belongs to the current client/organization,
   * sending the appropriate error response itself (400 for a missing id, 404 for not-found or
   * wrong-owner) and returning {@code null} in either case. Shared by {@link #handleDeclPut},
   * {@link #handleDeclDelete} and {@link #handleIncidents} — the three entry points that resolve
   * a single declaration by id before doing anything else. Deliberately checks {@code id} for
   * blank BEFORE touching {@link OBContext}, so a missing-id request never depends on an AD
   * context being available (matches the pre-existing {@code handleIncidents} behavior; the two
   * legacy callers already resolved client/org unconditionally via their own
   * {@link #handleDeclarations} dispatcher, so recomputing them here is a harmless no-op for
   * them).
   */
  private BaseOBObject resolveOwnedDeclaration(String id, HttpServletResponse response)
      throws Exception {
    if (StringUtils.isBlank(id)) {
      servlet.sendError(response, HttpServletResponse.SC_BAD_REQUEST, MISSING_ID_PARAM);
      return null;
    }
    String clientId = OBContext.getOBContext().getCurrentClient().getId();
    String orgId    = OBContext.getOBContext().getCurrentOrganization().getId();
    BaseOBObject decl = OBDal.getInstance().get(ENTITY_FISCAL_DECL, id);
    if (decl == null || !clientId.equals(getRelatedId(decl, PROPERTY_CLIENT))
        || !orgId.equals(getRelatedId(decl, PROPERTY_ORGANIZATION))) {
      servlet.sendError(response, HttpServletResponse.SC_NOT_FOUND, DECL_NOT_FOUND_PREFIX + id);
      return null;
    }
    return decl;
  }

  /**
   * Handles {@code GET /fiscal303/incidents?id=<declId>} (also reachable, harmlessly, as
   * {@code /fiscal349/incidents} — the underlying table is generic across models, but only the
   * Modelo 303 telematic submission flow writes to it today): returns the AEAT validation rows
   * currently persisted for the declaration, as
   * {@code {"data":[{"code","message","severity"}, ...]}} — {@code severity} is either
   * {@link #SEVERITY_BLOCK} (AEAT error) or {@link #SEVERITY_WARN} (AEAT warning/aviso), added in
   * ETP-4456 so the "Incidencias" tab can render the two distinctly instead of assuming every row
   * is blocking. Read-only — the write path lives in {@link #replaceIncidents}, called from
   * {@code Fiscal303BoxesHandler#handleSubmit} on every submission attempt.
   */
  void handleIncidents(String method, HttpServletRequest request, HttpServletResponse response)
      throws Exception {
    response.setContentType(JSON_CONTENT_TYPE);
    if (!"GET".equals(method)) {
      servlet.sendError(response, HttpServletResponse.SC_METHOD_NOT_ALLOWED,
          "Unsupported method for /fiscal303/incidents: " + method);
      return;
    }
    String id = request.getParameter("id");
    BaseOBObject decl = resolveOwnedDeclaration(id, response);
    if (decl == null) {
      return;
    }
    JSONArray arr = new JSONArray();
    for (BaseOBObject inc : queryIncidents(id)) {
      JSONObject o = new JSONObject();
      o.put(CODE_KEY,     asString(inc.get(PROPERTY_INCIDENT_CODE)));
      o.put(MESSAGE_KEY,  asString(inc.get(PROPERTY_INCIDENT_MESSAGE)));
      o.put(SEVERITY_KEY, resolveSeverity(inc));
      arr.put(o);
    }
    JSONObject out = new JSONObject();
    out.put("data", arr);
    response.getWriter().write(out.toString());
  }

  /**
   * Reads the persisted {@code severity} value, defaulting to {@link #SEVERITY_BLOCK} for any row
   * that predates this column (blank/null) — preserves the pre-ETP-4456 behavior for old rows
   * rather than surfacing an empty string to the frontend.
   */
  private static String resolveSeverity(BaseOBObject inc) {
    String severity = asString(inc.get(PROPERTY_INCIDENT_SEVERITY));
    return StringUtils.isNotBlank(severity) ? severity : SEVERITY_BLOCK;
  }

  /** All {@code ETGO_Fiscal_Decl_Incident} rows for {@code declId}, oldest first. */
  private List<BaseOBObject> queryIncidents(String declId) {
    OBQuery<BaseOBObject> query = OBDal.getInstance().createQuery(ENTITY_FISCAL_DECL_INCIDENT,
        PROPERTY_INCIDENT_DECL + ".id = :declId order by created asc");
    query.setNamedParameter("declId", declId);
    return query.list();
  }

  /**
   * Deletes every existing incident row for {@code decl}, then inserts one row per DISTINCT
   * entry in {@code errors} (tagged {@link #SEVERITY_BLOCK}) followed by one row per DISTINCT
   * entry in {@code warnings} (tagged {@link #SEVERITY_WARN}) — each parsed via
   * {@link #splitAeatError}. AEAT's own ServValiDos test/validation response has been observed
   * repeating the exact same error string more than once (e.g. {@code E010063} twice for the same
   * declaration); deduplicating here (order preserved, {@link LinkedHashSet}) keeps the
   * Incidencias tab showing one row per distinct problem rather than faithfully mirroring AEAT's
   * duplication. Dedup is applied INDEPENDENTLY per list — an error and a warning that happen to
   * share the exact same {@code "CODE - message"} text are NOT collapsed into a single row, since
   * they represent two distinct severities of the same underlying finding.
   *
   * <p>Called on EVERY submission attempt (test mode and production alike) by
   * {@code Fiscal303BoxesHandler#handleSubmit}, regardless of whether the attempt succeeded — a
   * successful submission with no errors and no warnings is the success case and simply leaves
   * the declaration with no incident rows after the delete step. Self-contained (commits its own
   * transaction), matching the convention already used by {@link #handleDeclPost}/
   * {@link #handleDeclPut}/{@link #handleDeclDelete} in this class.
   *
   * <p><b>Not used by {@code Fiscal303BoxesHandler#handleSubmit} anymore</b> — that caller uses
   * {@link #replaceIncidentsNoCommit} instead, so the incidents write and the subsequent
   * declaration status/attachment write share ONE transaction (ETP-4456 atomicity fix: a process
   * death between two independent commits used to leave a reachable partial state). This method
   * stays available, self-contained, for any other/future caller that wants the incidents
   * replace as its own standalone transaction.
   *
   * @param errors   raw AEAT error strings ({@code "CODE - message"}), persisted as
   *                 {@link #SEVERITY_BLOCK}. Never {@code null} (pass {@link java.util.Collections#emptyList()}).
   * @param warnings raw AEAT warning strings ({@code "CODE - message"}), persisted as
   *                 {@link #SEVERITY_WARN}. Never {@code null} (pass {@link java.util.Collections#emptyList()}).
   */
  void replaceIncidents(BaseOBObject decl, List<String> errors, List<String> warnings) {
    replaceIncidentsNoCommit(decl, errors, warnings);
    OBDal.getInstance().commitAndClose();
  }

  /**
   * Same delete-then-reinsert logic as {@link #replaceIncidents}, but deliberately leaves the
   * commit to the caller — used by {@code Fiscal303BoxesHandler#handleSubmit} so the incidents
   * write and the declaration status/attachment write that follows it land in a single
   * {@code commitAndClose()} (ETP-4456 atomicity fix; see the "OBDal transactions: single DB
   * transaction, all-or-nothing rollback" principle in the project's CLAUDE.md).
   */
  void replaceIncidentsNoCommit(BaseOBObject decl, List<String> errors, List<String> warnings) {
    String declId = String.valueOf(decl.getId());
    for (BaseOBObject inc : queryIncidents(declId)) {
      OBDal.getInstance().remove(inc);
    }
    insertIncidents(decl, errors, SEVERITY_BLOCK);
    insertIncidents(decl, warnings, SEVERITY_WARN);
  }

  /**
   * Inserts one {@code ETGO_Fiscal_Decl_Incident} row per DISTINCT (order-preserving) entry in
   * {@code rawEntries}, tagged with {@code severity}. Shared insertion logic for both the error
   * and warning groups in {@link #replaceIncidents} — deliberately does NOT touch existing rows
   * (the caller is responsible for the delete step), so calling it twice with different severities
   * accumulates rather than replaces.
   */
  private void insertIncidents(BaseOBObject decl, List<String> rawEntries, String severity) {
    for (String raw : new LinkedHashSet<>(rawEntries)) {
      String[] parsed = splitAeatError(raw);
      BaseOBObject inc = (BaseOBObject) OBProvider.getInstance().get(ENTITY_FISCAL_DECL_INCIDENT);
      inc.set(PROPERTY_CLIENT, OBContext.getOBContext().getCurrentClient());
      inc.set(PROPERTY_ORGANIZATION, OBContext.getOBContext().getCurrentOrganization());
      inc.set(PROPERTY_CREATED_BY, OBContext.getOBContext().getUser());
      inc.set(PROPERTY_UPDATED_BY, OBContext.getOBContext().getUser());
      inc.set(PROPERTY_INCIDENT_DECL, decl);
      inc.set(PROPERTY_INCIDENT_CODE, parsed[0]);
      inc.set(PROPERTY_INCIDENT_MESSAGE, parsed[1]);
      inc.set(PROPERTY_INCIDENT_SEVERITY, severity);
      OBDal.getInstance().save(inc);
    }
  }

  /**
   * Splits a raw AEAT error string ({@code "35068 - El resultado a ingresar..."} or
   * {@code "E010124 - Para periodo mensual..."}) into {@code [code, message]}. Falls back to an
   * empty code with the whole string as the message when it doesn't match the expected shape —
   * AEAT's error format is not contractually guaranteed, and a malformed entry should still be
   * persisted (visible to the user) rather than dropped.
   */
  static String[] splitAeatError(String raw) {
    if (raw == null) {
      return new String[] { "", "" };
    }
    String trimmed = raw.trim();
    Matcher m = AEAT_ERROR_PATTERN.matcher(trimmed);
    return m.matches() ? new String[] { m.group(1), m.group(2) } : new String[] { "", trimmed };
  }

  JSONObject declToJson(BaseOBObject decl) throws Exception {
    JSONObject o = new JSONObject();
    o.put("id",           decl.getId() != null ? decl.getId() : "");
    o.put("model",        asString(decl.get(PROPERTY_FISCAL_MODEL)));
    o.put("year",         asInt(decl.get(PROPERTY_FISCAL_YEAR)));
    o.put(PERIOD_KEY,     asString(decl.get(PROPERTY_PERIOD)));
    String dt = asString(decl.get(PROPERTY_DECLARATION_TYPE));
    String dtNormalized = dt != null ? dt.trim() : "";
    o.put("type",         "C".equals(dtNormalized) ? "com" : "ord");
    String status = asString(decl.get(PROPERTY_DECLARATION_STATUS));
    Object fileName = decl.get(PROPERTY_DECLARATION_FILE_NAME);
    Object updated = decl.get(PROPERTY_UPDATED);
    o.put(STATUS_KEY,     !status.isEmpty() ? status : DEFAULT_STATUS);
    o.put(FILE_NAME_KEY,  fileName != null ? fileName : JSONObject.NULL);
    o.put(FILE_EXTERNAL_KEY, Boolean.TRUE.equals(decl.get(PROPERTY_FILE_EXTERNAL)));
    o.put("updatedAt",    updated instanceof java.util.Date
        ? ((java.util.Date) updated).getTime() : 0L);
    o.put(MANUAL_DATA_KEY, parseManualData(decl));
    String submissionMethod = asString(decl.get(PROPERTY_SUBMISSION_METHOD));
    o.put(SUBMISSION_METHOD_KEY, StringUtils.isNotBlank(submissionMethod)
        ? submissionMethod : JSONObject.NULL);
    return o;
  }

  /**
   * Parses the stored {@code manual_data} JSON string back into a nested {@link JSONObject} for
   * the API response, so the frontend never has to double-parse a JSON-string-inside-JSON. Falls
   * back to an empty object — never throws — for a null/blank stored value (declaration created
   * before this field existed, or never touched) AND for a corrupted/malformed stored string,
   * mirroring the same graceful-degradation-to-{@code {}} convention already used by
   * {@code NeoFiscalModelsCatalogService#getActiveModels()} for this exact "arbitrary JSON blob
   * in a persisted text field" shape.
   *
   * <p>Catches {@link JSONException} specifically rather than a bare {@code Exception}: jettison's
   * {@code new JSONObject(String)} constructor declares only {@code throws JSONException} (verified
   * against its bytecode — the same guarantee {@link #setManualDataIfPresent} relies on), so a
   * malformed stored string can only ever surface as that one checked type here. A raw
   * {@code decl.get(...)} that returned something other than a String would already have failed
   * earlier, in {@link #asString}, not in this parse step.
   */
  private static JSONObject parseManualData(BaseOBObject decl) {
    String raw = asString(decl.get(PROPERTY_MANUAL_DATA));
    try {
      return StringUtils.isNotBlank(raw) ? new JSONObject(raw) : new JSONObject();
    } catch (JSONException e) {
      return new JSONObject();
    }
  }

  private static String getRelatedId(BaseOBObject decl, String property) {
    Object related = decl.get(property);
    return related instanceof BaseOBObject && ((BaseOBObject) related).getId() != null
        ? String.valueOf(((BaseOBObject) related).getId()) : "";
  }

  private static String asString(Object value) {
    return value != null ? String.valueOf(value) : "";
  }

  private static int asInt(Object value) {
    if (value instanceof Number) {
      return ((Number) value).intValue();
    }
    return 0;
  }

  private JSONObject readJsonBody(HttpServletRequest request) throws Exception {
    StringBuilder sb = new StringBuilder();
    try (BufferedReader reader = request.getReader()) {
      String line;
      while ((line = reader.readLine()) != null) sb.append(line);
    }
    return new JSONObject(sb.toString());
  }
}
