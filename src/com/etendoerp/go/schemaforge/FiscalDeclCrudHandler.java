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
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBQuery;
import org.openbravo.dal.service.OBDal;

import com.etendoerp.go.schemaforge.data.FiscalDecl;

class FiscalDeclCrudHandler {

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
   * Entity name (= DB table name) for the AEAT validation-error rows persisted on every Modelo
   * 303 submission attempt (see {@link Fiscal303BoxesHandler#handleSubmit} /
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
   * (defensive: AEAT's error format is not contractually guaranteed). */
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

    if (hasStatus)   decl.set(PROPERTY_DECLARATION_STATUS, status);
    if (hasFileExt)  decl.set(PROPERTY_FILE_EXTERNAL, fileExternal);
    if (hasFileName) decl.set(PROPERTY_DECLARATION_FILE_NAME, fileName);
    decl.set(PROPERTY_UPDATED_BY, OBContext.getOBContext().getUser());
    OBDal.getInstance().commitAndClose();
    response.getWriter().write("{\"ok\":true}");
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
   * @param errors   raw AEAT error strings ({@code "CODE - message"}), persisted as
   *                 {@link #SEVERITY_BLOCK}. Never {@code null} (pass {@link java.util.Collections#emptyList()}).
   * @param warnings raw AEAT warning strings ({@code "CODE - message"}), persisted as
   *                 {@link #SEVERITY_WARN}. Never {@code null} (pass {@link java.util.Collections#emptyList()}).
   */
  void replaceIncidents(BaseOBObject decl, List<String> errors, List<String> warnings) {
    String declId = String.valueOf(decl.getId());
    for (BaseOBObject inc : queryIncidents(declId)) {
      OBDal.getInstance().remove(inc);
    }
    insertIncidents(decl, errors, SEVERITY_BLOCK);
    insertIncidents(decl, warnings, SEVERITY_WARN);
    OBDal.getInstance().commitAndClose();
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
    return o;
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
