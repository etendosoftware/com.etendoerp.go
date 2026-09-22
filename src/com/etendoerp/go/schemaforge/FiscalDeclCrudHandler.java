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
import java.io.IOException;
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
  /**
   * Java property for the {@code decl_seq} DECIMAL(10,0) column added to
   * {@code ETGO_Fiscal_Decl} (ETP-5187 follow-up) — a zero-based, unbounded ordinal
   * disambiguating multiple declarations filed for the same natural key
   * ({@code client/org/model/fiscalYear/period}). Replaces the original ETP-5187 approach of
   * repurposing {@link #PROPERTY_DECLARATION_TYPE} (AEAT's genuine ordinaria/complementaria
   * business value, {@code VARCHAR(1)} CHECKed to exactly {@code 'O'}/{@code 'C'}) as an
   * artificial 2-slot disambiguator: there is no AEAT/legal limit on how many rectificativas can
   * be filed for a period, so capping the natural key at 2 rows was wrong, and conflating a real
   * business field with a uniqueness counter risked corrupting its actual meaning the moment a
   * future feature needs to let the user genuinely pick ordinaria vs. complementaria. See
   * {@link #resolveNextDeclSeq}.
   *
   * <p><b>Value is {@code "declarationSequence"}, not an abbreviated {@code "declSeq"}.</b>
   * Openbravo's dynamic {@code Entity}/{@code Property} model does NOT derive a property's Java
   * name from {@code AD_Column.ColumnName} (the physical DB column, {@code Decl_Seq}) — it derives
   * it from {@code AD_Column.Name} (see {@code NamingUtil#getPropertyMappingName}), camel-casing
   * on both {@code "_"} and {@code " "}. This column's {@code AD_Column.Name}/
   * {@code AD_Element.Name} is the human-readable {@code "Declaration Sequence"} (consistent with
   * the sibling columns {@link #PROPERTY_DECLARATION_TYPE}, {@link #PROPERTY_DECLARATION_STATUS}
   * and {@link #PROPERTY_DECLARATION_FILE_NAME}, all spelled out in full rather than abbreviated),
   * so the runtime property name is {@code "declarationSequence"}. Using {@code "declSeq"} here
   * — matching the abbreviated physical column name instead of the spelled-out element name —
   * caused every {@code decl.set(...)}/{@code decl.get(...)} call to throw
   * {@code CheckException: Property declSeq does not exist for entity ETGO_Fiscal_Decl}, even
   * with a correct, active {@code AD_Column} row and a freshly rebuilt runtime model. See
   * {@code docs/generated-custom-windows/fiscal-models.md} for the full writeup.
   */
  static final String PROPERTY_DECL_SEQ = "declarationSequence";
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
   * {@code submissionMethod} value set only by {@code Fiscal303SubmissionSupport
   * #persistSuccessfulSubmission} on a real, non-test-mode AEAT telematic success (ETP-4755).
   * Duplicated here (rather than referencing {@code Fiscal303SubmissionSupport}'s private
   * constant of the same value) because this class needs it purely as a guard value for the
   * "Reactivar declaración" reverse transition below (ETP-5338) — reactivating a declaration
   * that was actually filed with the AEAT would desync this table from what Hacienda has on
   * record, so it is blocked here regardless of what the frontend sends.
   */
  static final String SUBMISSION_METHOD_AEAT_TELEMATIC = "aeat_telematic";

  /**
   * The 3 {@link #PROPERTY_DECLARATION_STATUS} values that mean "already presented" — {@code
   * submitted}, {@code submitted_ext} (legacy/historical only, no longer selectable from
   * {@code PresentModal}, but still a real value on existing rows) and {@code submitted_ack}.
   * Mirrors the frontend's own local {@code isSubmitted}/{@code SUBMITTED_STATUSES} literals
   * (deliberately duplicated per language, not shared — see {@code fiscal-models.md}'s
   * "Duplicated, deliberately, in 4 places" for the frontend side of this same tradeoff). Used
   * by {@link #rejectRepresentation} (re-presentation guard, ETP-5438) and by
   * {@link Fiscal349BoxesHandler}/{@code Fiscal303BoxesHandler} to block a raw
   * compute/generate call against an already-presented declaration.
   */
  static final java.util.Set<String> SUBMITTED_STATUSES =
      java.util.Set.of("submitted", "submitted_ext", "submitted_ack");

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
  private static final String MODEL_KEY         = "model";
  private static final String PERIOD_KEY        = "period";
  private static final String STATUS_KEY        = "status";
  private static final String FILE_NAME_KEY     = "fileName";
  private static final String FILE_EXTERNAL_KEY = "fileExternal";
  private static final String PARAM_CLIENT_ID   = "clientId";
  private static final String PARAM_ORG_ID      = "orgId";
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
    query.setNamedParameter(PARAM_CLIENT_ID, clientId);
    query.setNamedParameter(PARAM_ORG_ID, orgId);
    JSONArray arr = new JSONArray();
    for (BaseOBObject decl : query.list()) arr.put(declToJson(decl));
    JSONObject out = new JSONObject();
    out.put("data", arr);
    response.getWriter().write(out.toString());
  }

  private void handleDeclPost(HttpServletRequest request,
      HttpServletResponse response) throws Exception {
    JSONObject body = readJsonBody(request);
    String model    = body.getString(MODEL_KEY);
    long   year     = body.getLong("year");
    String period   = body.getString(PERIOD_KEY);
    String requestedDeclType = "com".equals(body.optString("type")) ? "C" : "O";
    String status   = body.has(STATUS_KEY) ? body.getString(STATUS_KEY) : DEFAULT_STATUS;

    String clientId = OBContext.getOBContext().getCurrentClient().getId();
    String orgId    = OBContext.getOBContext().getCurrentOrganization().getId();

    // ETP-5272 — block creating a new declaration for a period that already has one sitting in
    // draft: a draft is an unfinished, in-progress declaration, and letting the user spawn a 2nd
    // one for the exact same period just fragments their work across two half-finished rows
    // instead of them completing (or deleting) the existing draft first. Mirrors the exact
    // draft-status guard already enforced by handleDeclDelete, but on the OPPOSITE direction:
    // delete allows ONLY a draft to be removed, creation blocks ONLY when a draft already
    // exists — a non-draft (ready/submitted/...) existing declaration is the intended
    // corrective/rectificativa case (ETP-5187) and must keep working exactly as before, via
    // resolveNextDeclSeq below, untouched by this check.
    if (hasDraftDeclaration(clientId, orgId, model, year, period)) {
      servlet.sendError(response, HttpServletResponse.SC_CONFLICT,
          "A draft declaration already exists for this period — complete or delete it before "
              + "creating a new one.");
      return;
    }

    // ETP-5187 — a 2nd (or later) declaration for the same model/year/period used to 500 on
    // ETGO_FISCAL_DECL_UQ (unique on client/org/model/year/period/DECL_TYPE) because the frontend
    // never sent a differentiator and every declaration defaulted to DECL_TYPE='O'. A follow-up
    // fix replaced DECL_TYPE (AEAT's genuine ordinaria/complementaria business value) with the
    // dedicated DECL_SEQ ordinal below as the uniqueness disambiguator: there is no AEAT/legal
    // cap on how many rectificativas can be filed for a period, so DECL_SEQ has no ceiling — see
    // resolveNextDeclSeq.
    long declSeq = resolveNextDeclSeq(clientId, orgId, model, year, period);

    BaseOBObject decl = (BaseOBObject) OBProvider.getInstance().get(ENTITY_FISCAL_DECL);
    decl.set(PROPERTY_CLIENT, OBContext.getOBContext().getCurrentClient());
    decl.set(PROPERTY_ORGANIZATION, OBContext.getOBContext().getCurrentOrganization());
    decl.set(PROPERTY_CREATED_BY, OBContext.getOBContext().getUser());
    decl.set(PROPERTY_UPDATED_BY, OBContext.getOBContext().getUser());
    decl.set(PROPERTY_FISCAL_MODEL, model);
    decl.set(PROPERTY_FISCAL_YEAR, year);
    decl.set(PROPERTY_PERIOD, period);
    decl.set(PROPERTY_DECLARATION_TYPE, requestedDeclType);
    decl.set(PROPERTY_DECL_SEQ, declSeq);
    decl.set(PROPERTY_DECLARATION_STATUS, status);
    OBDal.getInstance().save(decl);
    JSONObject created = declToJson(decl);
    OBDal.getInstance().commitAndClose();

    response.setStatus(HttpServletResponse.SC_CREATED);
    response.getWriter().write(created.toString());
  }

  /**
   * Returns {@code true} if ANY existing declaration for the given natural key
   * ({@code AD_CLIENT_ID, AD_ORG_ID, MODEL, FISCAL_YEAR, PERIOD}) currently has
   * {@link #DEFAULT_STATUS} ({@code "draft"}) — ETP-5272, the creation-time gate that mirrors
   * {@link #handleDeclDelete}'s existing draft-only guard.
   *
   * <p>Deliberately a separate, self-contained query rather than folded into
   * {@link #resolveNextDeclSeq}: that method has its own long-established, verified contract
   * ({@code MAX(DECL_SEQ) + 1}, no cap, no status awareness) and is explicitly NOT to be touched
   * by this feature — this is a distinct pre-condition, checked BEFORE it, not part of computing
   * the next ordinal. The extra query is one cheap indexed lookup on the same natural key
   * {@code ETGO_FISCAL_DECL_UQ} already covers; not worth entangling with resolveNextDeclSeq's
   * own iteration for that.
   */
  private boolean hasDraftDeclaration(String clientId, String orgId, String model, long year,
      String period) {
    OBQuery<BaseOBObject> query = OBDal.getInstance().createQuery(ENTITY_FISCAL_DECL,
        "client.id = :clientId and organization.id = :orgId and " + PROPERTY_FISCAL_MODEL
            + " = :model and " + PROPERTY_FISCAL_YEAR + " = :year and " + PROPERTY_PERIOD
            + " = :period");
    query.setNamedParameter(PARAM_CLIENT_ID, clientId);
    query.setNamedParameter(PARAM_ORG_ID, orgId);
    query.setNamedParameter(MODEL_KEY, model);
    query.setNamedParameter("year", Long.valueOf(year));
    query.setNamedParameter(PERIOD_KEY, period);
    for (BaseOBObject existing : query.list()) {
      if (DEFAULT_STATUS.equals(asString(existing.get(PROPERTY_DECLARATION_STATUS)))) {
        return true;
      }
    }
    return false;
  }

  /**
   * Resolves the next {@code DECL_SEQ} ordinal for the given natural key
   * ({@code AD_CLIENT_ID, AD_ORG_ID, MODEL, FISCAL_YEAR, PERIOD}) — {@code MAX(DECL_SEQ) + 1}
   * across every existing declaration sharing that key, or {@code 0} when none exist yet
   * (ETP-5187 — "allow a new declaration for an already-declared period, warn instead of
   * blocking"). {@code ETGO_FISCAL_DECL_UQ} is unique on this natural key plus {@code DECL_SEQ},
   * so returning a fresh, always-incrementing ordinal here guarantees the insert never collides
   * with the constraint — there is no cap: a 3rd, 4th, or Nth declaration for the same period
   * (the rectificativa flow — filed early, more invoices/corrections arrived later) succeeds just
   * like the 2nd, matching the real AEAT/legal rule that there is no limit on how many
   * rectificativas can be filed for a period.
   *
   * <p>Deliberately does NOT use {@link #PROPERTY_DECLARATION_TYPE} for this: that column is
   * AEAT's own ordinaria/complementaria business value (rendered verbatim by the frontend,
   * {@code FmListPage.jsx}), not an artificial disambiguator, and overloading it as one (the
   * original ETP-5187 approach) capped the whole system at 2 declarations per period since the
   * column is {@code VARCHAR(1)} CHECKed to exactly {@code 'O'}/{@code 'C'}.
   *
   * @return the next free {@code DECL_SEQ} value, starting at {@code 0}.
   */
  // Package-private (not private) so FiscalDeclCrudHandlerTest can exercise it directly, matching
  // the same test-visibility convention already used for splitAeatError/declToJson/replaceIncidents
  // in this class rather than introducing a new one.
  long resolveNextDeclSeq(String clientId, String orgId, String model, long year,
      String period) {
    OBQuery<BaseOBObject> query = OBDal.getInstance().createQuery(ENTITY_FISCAL_DECL,
        "client.id = :clientId and organization.id = :orgId and " + PROPERTY_FISCAL_MODEL
            + " = :model and " + PROPERTY_FISCAL_YEAR + " = :year and " + PROPERTY_PERIOD
            + " = :period");
    query.setNamedParameter(PARAM_CLIENT_ID, clientId);
    query.setNamedParameter(PARAM_ORG_ID, orgId);
    query.setNamedParameter(MODEL_KEY, model);
    query.setNamedParameter("year", Long.valueOf(year));
    query.setNamedParameter(PERIOD_KEY, period);
    long maxSeq = -1L;
    for (BaseOBObject existing : query.list()) {
      Object rawSeq = existing.get(PROPERTY_DECL_SEQ);
      long seq = rawSeq instanceof Number ? ((Number) rawSeq).longValue() : 0L;
      if (seq > maxSeq) {
        maxSeq = seq;
      }
    }
    return maxSeq + 1L;
  }

  /**
   * Resolves {@link #PROPERTY_DECLARATION_STATUS} of the MOST RECENT declaration (highest
   * {@link #PROPERTY_DECL_SEQ} — same "latest wins" ordinal {@link #resolveNextDeclSeq} computes
   * off) for the given natural key, or {@code null} when no declaration exists for it yet.
   *
   * <p>ETP-5438 — {@code /fiscal349/operators}, {@code /fiscal349/generate} and their 303
   * counterparts take no declaration id, only {@code (org, year, period)}: the natural key can
   * legitimately have MORE THAN ONE declaration (the rectificativa flow, {@link
   * #resolveNextDeclSeq}'s own javadoc), so "the declaration this call is about" is inherently
   * the latest one for that period — an older, already-submitted declaration for the SAME period
   * must not block a fresh rectificativa draft's own compute/generate. Used by {@link
   * Fiscal349BoxesHandler}/{@code Fiscal303BoxesHandler} to reject a compute/generate call once
   * that latest declaration is already in {@link #SUBMITTED_STATUSES} — the same "must not
   * silently recompute/regenerate an already-presented declaration" guarantee {@link
   * #rejectRepresentation} enforces for the PUT path, extended to the read/generate endpoints a
   * direct API call could otherwise reach without ever going through this handler's PUT at all.
   */
  String findLatestDeclarationStatus(String clientId, String orgId, String model, long year,
      String period) {
    OBQuery<BaseOBObject> query = OBDal.getInstance().createQuery(ENTITY_FISCAL_DECL,
        "client.id = :clientId and organization.id = :orgId and " + PROPERTY_FISCAL_MODEL
            + " = :model and " + PROPERTY_FISCAL_YEAR + " = :year and " + PROPERTY_PERIOD
            + " = :period");
    query.setNamedParameter(PARAM_CLIENT_ID, clientId);
    query.setNamedParameter(PARAM_ORG_ID, orgId);
    query.setNamedParameter(MODEL_KEY, model);
    query.setNamedParameter("year", Long.valueOf(year));
    query.setNamedParameter(PERIOD_KEY, period);
    long maxSeq = -1L;
    BaseOBObject latest = null;
    for (BaseOBObject existing : query.list()) {
      Object rawSeq = existing.get(PROPERTY_DECL_SEQ);
      long seq = rawSeq instanceof Number ? ((Number) rawSeq).longValue() : 0L;
      if (seq > maxSeq) {
        maxSeq = seq;
        latest = existing;
      }
    }
    return latest != null ? asString(latest.get(PROPERTY_DECLARATION_STATUS)) : null;
  }

  private void handleDeclPut(HttpServletRequest request, HttpServletResponse response)
      throws Exception {
    String id = request.getParameter("id");
    BaseOBObject decl = resolveOwnedDeclaration(id, response);
    if (decl == null) {
      return;
    }
    JSONObject body = readJsonBody(request);
    if (rejectTelematicReactivation(decl, body, id, response)) {
      return;
    }
    if (rejectRepresentation(decl, body, id, response)) {
      return;
    }
    if (rejectNegativeManualBoxes(body, id, response)) {
      return;
    }
    if (rejectOversizedIdentificationFields(body, id, response)) {
      return;
    }
    applyDeclPutScalarFields(decl, body);
    boolean manualDataApplied = applyManualDataIfRequested(decl, body);
    decl.set(PROPERTY_UPDATED_BY, OBContext.getOBContext().getUser());
    OBDal.getInstance().commitAndClose();
    writeDeclPutResponse(response, manualDataApplied);
  }

  /**
   * Guards "Reactivar declaración" (ETP-5338), which reverts a presented declaration back to
   * draft via this same PUT path ({@code status: "draft"}). Defense in depth, mirroring
   * {@link #handleDeclDelete}'s draft-only guard: the frontend already hides the Reactivar action
   * for {@code aeat_telematic} declarations ({@code FmRowActions.jsx} / {@code FmListPage.jsx}),
   * but this is what actually prevents one from being reopened regardless of what the client
   * sends — reactivating a declaration that was genuinely filed with the AEAT would desync this
   * table from what Hacienda has on record, which is unrecoverable from here.
   *
   * @return {@code true} if the PUT was rejected (a 409 was already sent to {@code response} and
   *         the caller must stop processing); {@code false} if the request may proceed.
   */
  private boolean rejectTelematicReactivation(BaseOBObject decl, JSONObject body, String id,
      HttpServletResponse response) throws Exception {
    boolean hasStatus = body.has(STATUS_KEY);
    String status = hasStatus ? body.getString(STATUS_KEY) : null;
    if (!hasStatus || !DEFAULT_STATUS.equals(status)) {
      return false;
    }
    String currentSubmissionMethod = asString(decl.get(PROPERTY_SUBMISSION_METHOD));
    if (!SUBMISSION_METHOD_AEAT_TELEMATIC.equals(currentSubmissionMethod)) {
      return false;
    }
    servlet.sendError(response, HttpServletResponse.SC_CONFLICT,
        "Cannot reactivate a declaration filed via AEAT telematic submission: " + id);
    return true;
  }

  /**
   * Blocks re-presenting a declaration that is already in a {@link #SUBMITTED_STATUSES} status
   * (ETP-5438) — "block re-presentation once already submitted". Defense in depth: the frontend
   * already hides "Registrar/Presentar" once {@code isSubmitted} ({@code FmModel303Page.jsx} /
   * {@code FmModel349Page.jsx}), but this is what actually prevents a raw PUT (or a future
   * frontend regression) from silently re-filing an already-presented declaration.
   *
   * <p>Only fires when BOTH the current status and the incoming one are in
   * {@link #SUBMITTED_STATUSES} — a transition INTO the submitted family from {@code draft}/
   * {@code ready} (the normal, first-time presentation) is unaffected, and so is the existing
   * "Reactivar declaración" transition BACK to {@code draft} ({@link #rejectTelematicReactivation}
   * already guards that one on its own, narrower, terms). Deliberately model-agnostic — the same
   * {@code ETGO_Fiscal_Decl} table and PUT path serve both Modelo 303 and Modelo 349, and nothing
   * about "you cannot re-present an already-presented declaration" is specific to either.
   *
   * @return {@code true} if the PUT was rejected (a 409 was already sent to {@code response} and
   *         the caller must stop processing); {@code false} if the request may proceed.
   */
  private boolean rejectRepresentation(BaseOBObject decl, JSONObject body, String id,
      HttpServletResponse response) throws Exception {
    boolean hasStatus = body.has(STATUS_KEY);
    String newStatus = hasStatus ? body.getString(STATUS_KEY) : null;
    if (!hasStatus || !SUBMITTED_STATUSES.contains(newStatus)) {
      return false;
    }
    String currentStatus = asString(decl.get(PROPERTY_DECLARATION_STATUS));
    if (!SUBMITTED_STATUSES.contains(currentStatus)) {
      return false;
    }
    servlet.sendError(response, HttpServletResponse.SC_CONFLICT,
        "This declaration was already submitted (status: " + currentStatus + "): " + id);
    return true;
  }

  // ETP-5393 Bug C — boxes the classic AEAT303Report engine hard-rejects when negative:
  // box 111 "Rectificación – Importe" (AEAT303Report2024.java:276-278,
  // @AEAT303_Negative_Not_Allowed_For_111@) and box 77 "IVA a la importación liquidado por la
  // Aduana pendiente de ingreso" (AEAT303Report2015.java:149-162,
  // @AEAT303_Negative_IVA_IMPORT_ADUANA@). The GO previsualización's manualOverrides had no
  // equivalent server-side check at all — unlike setManualDataIfPresent's tolerant handling of a
  // malformed manualData blob, a negative value here is a real business-rule violation the caller
  // must be told about, not silently swallowed.
  //
  // ETP-5438 (AEAT spec audit) — boxes 70, 78, 109 and 110 are ALSO declared "Num" (numérico sin
  // signo / unsigned) in the official Modelo 303 "Diseño de registro" (DR303e26v101 v1.01, the
  // spec bundled with this ticket), exactly like 111 and 77 — the same class of bug ETP-5393 Bug C
  // fixed for those two, just not caught at the time because the audit that found it (casilla-by-
  // casilla cross-check of every editable box's AEAT type against this guard's coverage) hadn't
  // been done yet. Added to the SAME set/mechanism rather than a parallel one, per the AEAT type
  // legend confirmed in the spec's own "Nota" footer on every page: "1. Los campos deben ser A
  // (Alfabético) An (Alfanumérico), Num (Numérico sin signo) o N (Numérico con signo)."
  private static final java.util.Set<String> NEGATIVE_NOT_ALLOWED_BOX_KEYS =
      java.util.Set.of("111", "77", "70", "78", "109", "110");

  /**
   * Rejects a PUT whose {@code manualData.manualOverrides} sets box 111 or box 77 to a negative
   * value — see {@link #NEGATIVE_NOT_ALLOWED_BOX_KEYS}. A missing/malformed {@code manualData} or
   * {@code manualOverrides} is not this method's concern (left to {@link #setManualDataIfPresent}'s
   * existing tolerant handling); this only fires when one of the two watched boxes is present and
   * parses to a negative number.
   *
   * @return {@code true} if the PUT was rejected (a 400 was already sent to {@code response} and
   *         the caller must stop processing); {@code false} if the request may proceed.
   */
  private boolean rejectNegativeManualBoxes(JSONObject body, String id, HttpServletResponse response)
      throws IOException {
    if (!body.has(MANUAL_DATA_KEY) || body.isNull(MANUAL_DATA_KEY)) {
      return false;
    }
    JSONObject manualData = body.optJSONObject(MANUAL_DATA_KEY);
    JSONObject overrides = manualData != null ? manualData.optJSONObject("manualOverrides") : null;
    if (overrides == null) {
      return false;
    }
    for (String boxKey : NEGATIVE_NOT_ALLOWED_BOX_KEYS) {
      if (!overrides.has(boxKey) || overrides.isNull(boxKey)) {
        continue;
      }
      double value = overrides.optDouble(boxKey, 0d);
      if (!Double.isNaN(value) && value < 0) {
        servlet.sendError(response, HttpServletResponse.SC_BAD_REQUEST,
            "Box " + boxKey + " does not accept negative values: " + id);
        return true;
      }
    }
    return false;
  }

  /**
   * Reads {@code manualData.identification} out of a PUT body, or {@code null} if
   * {@code manualData} (or {@code identification} within it) is absent/malformed — used by
   * {@link #rejectOversizedIdentificationFields} below, the ETP-5438 sibling of
   * {@link #rejectNegativeManualBoxes}'s own {@code manualOverrides} read.
   */
  private JSONObject extractIdentification(JSONObject body) {
    if (!body.has(MANUAL_DATA_KEY) || body.isNull(MANUAL_DATA_KEY)) {
      return null;
    }
    JSONObject manualData = body.optJSONObject(MANUAL_DATA_KEY);
    return manualData != null ? manualData.optJSONObject("identification") : null;
  }

  // ETP-5438 (AEAT spec audit) — max lengths for the alphanumeric ("An") identification fields,
  // read straight off the official Modelo 303 "Diseño de registro" (DR303e26v101 v1.01): the
  // DID page for the 6 bank_* fields (SWIFT-BIC 11, IBAN 34 — "Nota 6: Para el IBAN español
  // deberá empezar por ES y únicamente se usan las primeras 24 posiciones", Bank name 70, Bank
  // address 35, City 30, Country code 2) and page 3 for nro_justificante (13, "Número
  // justificante identificativo de la autoliquidación anterior"). Same rationale as
  // NEGATIVE_NOT_ALLOWED_BOX_KEYS above: the classic AEAT engine's fixed-width record slots make
  // an oversized value a real business-rule violation, not a cosmetic nit — better to reject it
  // here, where the user can still fix it, than have it silently truncated (or rejected outright)
  // at file-generation time.
  private static final java.util.Map<String, Integer> IDENTIFICATION_MAX_LENGTHS = buildIdentificationMaxLengths();

  private static java.util.Map<String, Integer> buildIdentificationMaxLengths() {
    java.util.Map<String, Integer> m = new java.util.LinkedHashMap<>();
    m.put("bank_iban", 34);
    m.put("bank_swift_bic", 11);
    m.put("bank_nombre", 70);
    m.put("bank_direccion", 35);
    m.put("bank_ciudad", 30);
    m.put("bank_pais", 2);
    m.put("nro_justificante", 13);
    return java.util.Collections.unmodifiableMap(m);
  }

  /**
   * Rejects a PUT whose {@code manualData.identification} carries a value longer than its AEAT
   * fixed-width slot (ETP-5438) — see {@link #IDENTIFICATION_MAX_LENGTHS}. A missing/malformed
   * {@code manualData}/{@code identification}, or a field simply absent from the payload, is not
   * this method's concern (same tolerant precedent as {@link #rejectNegativeManualBoxes}).
   *
   * @return {@code true} if the PUT was rejected (a 400 was already sent to {@code response} and
   *         the caller must stop processing); {@code false} if the request may proceed.
   */
  private boolean rejectOversizedIdentificationFields(JSONObject body, String id,
      HttpServletResponse response) throws IOException {
    JSONObject identification = extractIdentification(body);
    if (identification == null) {
      return false;
    }
    for (java.util.Map.Entry<String, Integer> entry : IDENTIFICATION_MAX_LENGTHS.entrySet()) {
      String key = entry.getKey();
      if (!identification.has(key) || identification.isNull(key)) {
        continue;
      }
      String value = identification.optString(key, "");
      if (value.length() > entry.getValue()) {
        servlet.sendError(response, HttpServletResponse.SC_BAD_REQUEST,
            "Field " + key + " exceeds its max length of " + entry.getValue() + " characters: " + id);
        return true;
      }
    }
    return false;
  }

  // ETP-5438 (AEAT spec audit) — bank_sepa ("Devolución - Marca SEPA") is also declared a
  // single-digit Num field on the DID page restricted to a 4-value enum (spec's own "Nota 2:
  // Devolución marca SEPA" table: 0 Vacía, 1 Cuenta España, 2 Unión Europea SEPA, 3 Resto
  // Países) in the same audit that found the gaps above. That fix (a `rejectInvalidBankSepa`
  // guard here, paired with a `type: 'select'` conversion in fm303Layouts.js) was reverted from
  // this ticket — it is being handled under a separate ticket instead. Do not re-add it here.

  /**
   * Applies every scalar (non-{@code manualData}) optional field {@link #handleDeclPut} accepts —
   * {@code status}, {@code fileExternal}, {@code fileName}, {@code submissionMethod} — each only
   * when the caller actually sent it.
   *
   * <p>{@code fileName}: unlike {@code submissionMethod} below, an explicit {@code null} here DOES
   * clear the stored value via {@code decl.set(..., null)} — {@code hasFileName} does not check
   * {@code isNull}, so a present-but-null {@code fileName} still reaches the setter as {@code null}.
   *
   * <p>{@code submissionMethod} (ETP-4755) follows the same "explicit null means not sent"
   * precedent as {@code manualData} (see {@link #applyManualDataIfRequested}), not
   * {@code fileName}'s "explicit null clears it" one: this field is set once, at the same time as
   * the status change that makes the declaration "Presentado" (see {@code FmOverlays.jsx}'s
   * {@code PresentModal} / {@code handlePresent} call sites), and is never meant to be wiped by a
   * stray/racy PUT that happens to include a null for it.
   */
  private void applyDeclPutScalarFields(BaseOBObject decl, JSONObject body) throws JSONException {
    if (body.has(STATUS_KEY)) {
      decl.set(PROPERTY_DECLARATION_STATUS, body.getString(STATUS_KEY));
    }
    if (body.has(FILE_EXTERNAL_KEY)) {
      decl.set(PROPERTY_FILE_EXTERNAL, body.optBoolean(FILE_EXTERNAL_KEY, false));
    }
    if (body.has(FILE_NAME_KEY)) {
      String fileName = !body.isNull(FILE_NAME_KEY) ? body.getString(FILE_NAME_KEY) : null;
      decl.set(PROPERTY_DECLARATION_FILE_NAME, fileName);
    }
    if (body.has(SUBMISSION_METHOD_KEY) && !body.isNull(SUBMISSION_METHOD_KEY)) {
      decl.set(PROPERTY_SUBMISSION_METHOD, body.getString(SUBMISSION_METHOD_KEY));
    }
  }

  /**
   * Applies {@code manualData} when the caller sent a non-null value — see
   * {@link #setManualDataIfPresent} for the persistence/error-tolerance contract. manualData is
   * the only optional {@link #handleDeclPut} field that is an arbitrary nested object rather than
   * a scalar the caller can't realistically malform, so it gets its own defensive setter instead
   * of a one-liner in {@link #applyDeclPutScalarFields}.
   *
   * <p>An explicit {@code "manualData": null} is treated as "not sent" rather than "clear the
   * value": manualData is autosaved from ephemeral frontend state, so a caller wanting to reset it
   * must send an empty object rather than null — treating null as "clear" would risk silently
   * wiping real user data from a stray/racy autosave call. This asymmetry with {@code fileName} is
   * intentional, not an oversight.
   *
   * @return {@code true} if manualData was applied or not requested; {@code false} if it was
   *         requested but malformed and the write was skipped.
   */
  private boolean applyManualDataIfRequested(BaseOBObject decl, JSONObject body) {
    boolean hasManualData = body.has(MANUAL_DATA_KEY) && !body.isNull(MANUAL_DATA_KEY);
    return !hasManualData || setManualDataIfPresent(decl, body);
  }

  private void writeDeclPutResponse(HttpServletResponse response, boolean manualDataApplied)
      throws IOException {
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

  /**
   * Deletes a declaration — restricted to {@code draft} status (ETP-5187, "edit/delete hover
   * actions on the declaration list row"). Defense in depth: the frontend already only shows the
   * delete action for draft rows ({@code FmListPage.jsx}), but this guard is what actually
   * prevents a non-draft declaration (ready/submitted/…) from being removed, regardless of what
   * the client sends.
   */
  private void handleDeclDelete(HttpServletRequest request, HttpServletResponse response)
      throws Exception {
    String id = request.getParameter("id");
    BaseOBObject decl = resolveOwnedDeclaration(id, response);
    if (decl == null) {
      return;
    }
    String status = asString(decl.get(PROPERTY_DECLARATION_STATUS));
    if (!DEFAULT_STATUS.equals(status)) {
      servlet.sendError(response, HttpServletResponse.SC_CONFLICT,
          "Only draft declarations can be deleted: " + id);
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
    o.put(MODEL_KEY,       asString(decl.get(PROPERTY_FISCAL_MODEL)));
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
