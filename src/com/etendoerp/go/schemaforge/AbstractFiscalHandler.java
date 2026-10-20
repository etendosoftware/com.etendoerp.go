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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.apache.log4j.Logger;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.criterion.Order;
import org.hibernate.criterion.Restrictions;
import org.openbravo.base.exception.OBException;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.common.invoice.Invoice;
import org.openbravo.model.financialmgmt.accounting.coa.AcctSchema;
import org.openbravo.model.financialmgmt.calendar.Period;
import org.openbravo.module.taxreportlauncher.TaxReport;

import com.etendoerp.go.common.MalformedJsonException;
import com.etendoerp.go.schemaforge.util.NeoAccessHelper;
import com.etendoerp.go.schemaforge.util.NeoMessageTranslator;
import com.etendoerp.go.schemaforge.util.RoleAccessMatrix;

abstract class AbstractFiscalHandler {

  protected static final Logger log = Logger.getLogger(AbstractFiscalHandler.class);

  protected static final String DECLARATIONS = "declarations";
  protected static final String INCIDENTS    = "incidents";
  protected static final String MODIFIED     = "modified";
  protected static final String PERIOD_KEY   = "period";
  protected static final String SINCE_KEY    = "since";
  protected static final String JSON_CT      = "application/json;charset=UTF-8";

  /**
   * Last-resort text for a 500 that has nothing better to say. {@link #userMessage} falls back to
   * it so the browser is never handed a message-less error object (ETP-5027, QA F6).
   */
  protected static final String GENERIC_ERROR_MESSAGE = "An internal error occurred.";

  protected final NeoServlet servlet;
  private   final FiscalDeclCrudHandler declHandler;

  AbstractFiscalHandler(NeoServlet servlet) {
    this.servlet     = servlet;
    this.declHandler = new FiscalDeclCrudHandler(servlet);
  }

  /**
   * Exposes the shared {@link FiscalDeclCrudHandler} delegate to subclasses that need one of its
   * declaration lookups (e.g. {@link #guardNotAlreadySubmitted}'s ETP-5438 use of {@link
   * FiscalDeclCrudHandler#findLatestDeclarationStatus}) without instantiating a second one.
   */
  protected FiscalDeclCrudHandler declHandler() {
    return declHandler;
  }

  /**
   * Thrown by {@link #guardNotAlreadySubmitted} — every {@code dispatch()} override that calls it
   * must catch this specifically (before its own generic {@code catch (Exception e)}) and turn it
   * into a clean {@code 409} instead of letting it bubble up wrapped as a generic {@code 500}. See
   * {@link Fiscal303BoxesHandler#dispatch}/{@link Fiscal349BoxesHandler#dispatch} for the exact
   * catch-and-409 pattern (identical in both). Package-private (not private) so both handlers'
   * test classes can assert on it directly — inherited nested types are reachable by simple name
   * from subclass code, and by {@code SubClass.AlreadySubmittedException} from outside it (JLS
   * 8.5), so this does not need to be duplicated per subclass.
   */
  static final class AlreadySubmittedException extends RuntimeException {
    AlreadySubmittedException(String message) {
      super(message);
    }
  }

  /**
   * ETP-5438 defense in depth, shared by every fiscal model's generate handler — rejects a
   * {@code generate} (file generation) call once the LATEST declaration for this {@code
   * (org, year, period, model)} natural key is already in {@link
   * FiscalDeclCrudHandler#SUBMITTED_STATUSES}. The frontend already hides "Generar fichero <N>"
   * once {@code isSubmitted} (every {@code FmModel<N>Page.jsx}), but the NEO generate entities
   * are otherwise unaware of any declaration's status at all — they compute purely from
   * {@code (orgId, year, period)} against LIVE invoice data — so a direct/raw call (or a future
   * frontend regression) would silently regenerate an already-presented declaration, with no
   * server-side guard, unlike the PUT path {@link FiscalDeclCrudHandler#rejectRepresentation}
   * already covers.
   *
   * <p>The pure-read entities ({@code /fiscal303/boxes}, {@code /fiscal349/operators}) are
   * deliberately NOT gated: they serve a submitted declaration from its persisted snapshot (see
   * {@link #snapshotOrCompute}), or compute it live for a legacy declaration presented before
   * snapshots existed.
   *
   * <p>Gates on the MOST RECENT declaration (highest {@code DECL_SEQ}) for the natural key, not
   * just any match — see {@link FiscalDeclCrudHandler#findLatestDeclarationStatus}'s own javadoc
   * for why: a period can legitimately have more than one declaration (the rectificativa flow),
   * and an older, already-submitted one must not block a fresh draft for the same period.
   *
   * <p>No-op (returns normally) when no declaration exists yet for the natural key — a first-time
   * compute before any declaration row was ever created is not "already submitted" by definition.
   *
   * @param model the {@code ETGO_Fiscal_Decl.model} value for the calling handler (e.g. {@code
   *              "303"}, {@code "349"}) — passed explicitly rather than derived from {@link
   *              #getModelKey()}, which returns the URL segment ({@code "fiscal303"}/{@code
   *              "fiscal349"}), not the bare model code the declaration table stores.
   */
  protected void guardNotAlreadySubmitted(String orgId, int year, String period, String model) {
    String clientId = OBContext.getOBContext().getCurrentClient().getId();
    String status = declHandler().findLatestDeclarationStatus(clientId, declarationOrgId(), model,
        year, period);
    if (status != null && FiscalDeclCrudHandler.SUBMITTED_STATUSES.contains(status)) {
      throw new AlreadySubmittedException(
          "This declaration was already submitted (status: " + status + ") for org=" + orgId
              + " year=" + year + " period=" + period + " model=" + model);
    }
  }

  /**
   * ETP-5725 (reopened QA fix) — shared defense-in-depth for {@code generate} (file generation,
   * both {@code /fiscal303/generate} and {@code /fiscal349/generate}): rejects a request whose
   * EFFECTIVE corrective/ordinaria identification would violate the same business rule enforced
   * on the {@code PUT} path by {@link FiscalDeclCorrectiveConsistencySupport#checkCorrectiveConsistency} ("a
   * corrective declaration needs a presented ordinaria to correct" / "only one presented
   * ordinaria per period") — see that method's javadoc for the rule itself. {@code generate} is
   * the actual AEAT submission artifact (the literal file the user uploads to the AEAT portal),
   * not a preview, so it must never be generatable while the declaration is in an invalid state,
   * exactly like "Guardar"/"Registrar/Presentar".
   *
   * <p>{@code generate} has no declaration id of its own — only {@code (orgId, year, period,
   * model)} — so the natural key's sibling search excludes the LATEST declaration for that key,
   * if one already exists (mirroring {@link #guardNotAlreadySubmitted}'s own "latest DECL_SEQ
   * wins" natural-key lookup), rather than a specific row like the PUT path excludes itself.
   *
   * @param effectivelyCorrective whether THIS generate call's own identification — as the
   *                               caller's request describes it, since {@code generate} has no
   *                               persisted row of its own to trust at call time (the frontend
   *                               never flushes a save before generating) — marks it as
   *                               rectificativa (303) / sustitutiva (349). Each handler's own
   *                               {@code dispatch} resolves this from its own unambiguous request
   *                               parameter — see {@link Fiscal303BoxesHandler#dispatch} /
   *                               {@link Fiscal349BoxesHandler#dispatch}.
   * @throws AlreadySubmittedException when the rule is violated — caught by {@link #runDispatch}
   *                                    exactly like {@link #guardNotAlreadySubmitted}'s own, and
   *                                    turned into a clean 409.
   */
  protected void guardCorrectiveConsistency(String orgId, int year, String period, String model,
      boolean effectivelyCorrective) {
    String clientId = OBContext.getOBContext().getCurrentClient().getId();
    BaseOBObject latest = declHandler().findLatestDeclaration(clientId, declarationOrgId(), model,
        year, period);
    String excludeId = latest != null && latest.getId() != null ? String.valueOf(latest.getId()) : null;
    String violation = declHandler().correctiveConsistencySupport.checkCorrectiveConsistency(
        clientId, declarationOrgId(), model, year, period, excludeId, effectivelyCorrective);
    if (violation != null) {
      throw new AlreadySubmittedException(
          violation + " for org=" + orgId + " year=" + year + " period=" + period
              + " model=" + model);
    }
  }

  /**
   * The organization declarations are STORED under — the raw session org, exactly what
   * {@link FiscalDeclCrudHandler} writes on POST and filters by on GET. Deliberately NOT the
   * {@code orgId} {@link #dispatch} receives: that one comes from {@link #resolveEffectiveOrg},
   * which maps a {@code *} (org {@code "0"}) session to the first leaf org so the COMPUTATION has
   * real invoice data, but a declaration created from that same session is stored under
   * {@code "0"}. Looking declarations up with the effective org would never find them (ETP-5438
   * review W1: the snapshot was missed and the generate guard let a submitted declaration
   * through). Declaration lookups use this; computations keep the effective org.
   */
  protected String declarationOrgId() {
    return OBContext.getOBContext().getCurrentOrganization().getId();
  }

  /**
   * What this model freezes as a submission snapshot, and how it computes its live payload
   * (ETP-5438). Assigned by each subclass constructor; see {@link FiscalSnapshotSupport}.
   * Package-private and non-final so tests can install a double.
   */
  FiscalSnapshotSupport snapshotSupport;

  /**
   * The bare {@code ETGO_Fiscal_Decl.model} code this handler serves ({@code "303"},
   * {@code "349"}) — not the URL segment {@link #getModelKey()} returns.
   */
  protected String getDeclModel() {
    return snapshotSupport.declModel();
  }

  /**
   * Whether this model allows a single ORDINARY declaration per exercise / period (ETP-5716):
   * a second ordinary one is answered {@code 409 DUPLICATE_ORDINARY} by
   * {@link FiscalDeclCrudHandler}. Default {@code false} (303/349: the rectificativa flow
   * re-files an ordinary); the Modelo 390 handler overrides it.
   */
  protected boolean allowsSingleOrdinary() {
    return false;
  }

  /**
   * Computes, from the CURRENT invoice data, the exact JSON payload this model's read endpoint
   * returns ({@code /fiscal303/boxes}, {@code /fiscal349/operators}) for a declaration that is
   * not served from a snapshot. {@link #computeSubmittedSnapshot} derives the snapshot from it.
   * Passes {@code this} to the support so a Mockito spy of the handler stays in the call chain.
   */
  @SuppressWarnings("java:S112")
  JSONObject computeLivePayload(String orgId, int year, String period) throws Exception {
    return snapshotSupport.computeLivePayload(this, orgId, year, period);
  }

  /**
   * ETP-5438 — the submission snapshot: {@link #computeLivePayload}'s payload reduced to its
   * fixed-size figures by {@link FiscalSnapshotSupport#toSnapshot} — see there for what is kept,
   * why, and the known 349 size limit.
   */
  @SuppressWarnings("java:S112")
  final JSONObject computeSubmittedSnapshot(String orgId, int year, String period)
      throws Exception {
    return snapshotSupport.toSnapshot(computeLivePayload(orgId, year, period));
  }

  /**
   * ETP-5438 — the payload the read endpoint serves: the persisted submission snapshot when the
   * latest declaration for the natural key is submitted and has one, otherwise a live
   * {@link #computeLivePayload}. A submitted declaration WITHOUT a snapshot (a legacy one,
   * presented before snapshots existed) deliberately keeps the live compute — no data-fix for
   * those, product decision. Drafts/ready declarations always compute live.
   */
  @SuppressWarnings("java:S112")
  protected JSONObject snapshotOrCompute(String orgId, int year, String period) throws Exception {
    JSONObject snapshot = findSubmittedSnapshot(year, period);
    return snapshot != null ? snapshot : computeLivePayload(orgId, year, period);
  }

  /**
   * The persisted submission snapshot of the latest declaration for this model's natural key, or
   * {@code null} when that declaration is not submitted or has none (see
   * {@link #snapshotOrCompute}). Split out so a model whose live payload takes request inputs
   * ({@link Fiscal390BoxesHandler}) can serve the frozen figures without going through
   * {@link #computeLivePayload}. Looked up on the org the declaration is stored with
   * ({@link #declarationOrgId}).
   */
  protected JSONObject findSubmittedSnapshot(int year, String period) {
    String clientId = OBContext.getOBContext().getCurrentClient().getId();
    return declHandler().snapshots.findLatestSubmittedSnapshot(clientId, declarationOrgId(),
        getDeclModel(), year, period);
  }

  /**
   * Wires every handler's {@link FiscalDeclCrudHandler} to compute a submission snapshot through
   * the handler that owns the declaration's model (ETP-5438). Needed because every declaration
   * PUT — Modelo 303 and Modelo 349 alike — arrives through {@code /fiscal303/declarations}, so
   * the CRUD handler must be able to reach the OTHER model's compute too. The org is resolved
   * exactly like the read endpoint resolves it ({@link #resolveEffectiveOrg}), so the snapshot
   * matches what that read returns.
   */
  static void linkSubmittedSnapshotProviders(AbstractFiscalHandler... handlers) {
    java.util.Map<String, AbstractFiscalHandler> byModel = new HashMap<>();
    for (AbstractFiscalHandler h : handlers) {
      byModel.put(h.getDeclModel(), h);
    }
    FiscalSubmittedSnapshotSupport.SnapshotProvider provider = (model, year, period) -> {
      AbstractFiscalHandler owner = byModel.get(model);
      return owner != null
          ? owner.computeSubmittedSnapshot(owner.resolveEffectiveOrg(), year, period)
          : null;
    };
    // Per-model "single ordinary" rule, resolved through the same per-model map (ETP-5716).
    java.util.function.Predicate<String> singleOrdinary = model -> {
      AbstractFiscalHandler owner = byModel.get(model);
      return owner != null && owner.allowsSingleOrdinary();
    };
    for (AbstractFiscalHandler h : handlers) {
      h.declHandler().snapshots.setProvider(provider);
      h.declHandler().setSingleOrdinaryModel(singleOrdinary);
    }
  }

  void handle(String entityName, String method, HttpServletRequest request,
      HttpServletResponse response) throws IOException {
    // ETP-5546: single entry point for every /fiscal303 and /fiscal349 sub-route
    // (declarations, incidents, boxes, submit, modified, ...) — gate here once instead of
    // per-entity. "Modelos Fiscales" access is the role's grant on the Tax Report window
    // (ETP-5116 proxy, shared constant per ETP-5540); the method is tiered against IsReadWrite
    // so a read-only grant still denies POST /fiscal303/submit.
    if (!NeoAccessHelper.hasWindowAccess(RoleAccessMatrix.TAX_MODELS_PROXY_WINDOW_ID, method)) {
      servlet.sendError(response, HttpServletResponse.SC_FORBIDDEN, "Access denied");
      return;
    }
    if (DECLARATIONS.equals(entityName) || INCIDENTS.equals(entityName)) {
      delegateToDeclHandler(entityName, method, request, response);
      return;
    }
    if (!isKnownEntity(entityName)) {
      servlet.sendError(response, HttpServletResponse.SC_NOT_FOUND,
          "Unknown " + getModelKey() + " entity: " + entityName);
      return;
    }
    boolean methodOk = ("GET".equals(method) && allowsGet(entityName))
        || ("POST".equals(method) && allowsPost(entityName));
    if (!methodOk) {
      servlet.sendError(response, HttpServletResponse.SC_METHOD_NOT_ALLOWED,
          "Method not allowed for /" + getModelKey() + "/" + entityName);
      return;
    }
    String yearStr = request.getParameter("year");
    String period  = request.getParameter(PERIOD_KEY);
    if (yearStr == null || period == null) {
      servlet.sendError(response, HttpServletResponse.SC_BAD_REQUEST,
          "Missing required params: year, period");
      return;
    }
    if (MODIFIED.equals(entityName) && request.getParameter(SINCE_KEY) == null) {
      servlet.sendError(response, HttpServletResponse.SC_BAD_REQUEST,
          "Missing required param: since");
      return;
    }
    int year;
    try {
      year = Integer.parseInt(yearStr);
    } catch (NumberFormatException e) {
      servlet.sendError(response, HttpServletResponse.SC_BAD_REQUEST,
          "Invalid year: " + yearStr);
      return;
    }
    try {
      String orgId = resolveEffectiveOrg();
      dispatch(entityName, orgId, year, period, request, response);
    } catch (Exception e) {
      if (sendIncompleteFiscalYear(response, e, entityName)) {
        return;
      }
      if (e instanceof FiscalHandlerException) {
        log.error("Error in /" + getModelKey() + "/" + entityName, e);
        servlet.sendError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, userMessage(e));
        return;
      }
      log.error("Unexpected error in /" + getModelKey() + "/" + entityName, e);
      servlet.sendError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
          GENERIC_ERROR_MESSAGE);
    }
  }

  /**
   * Answers an {@link IncompleteFiscalYearException} anywhere in {@code e}'s cause chain with the
   * structured {@code 422 INCOMPLETE_FISCAL_YEAR} (year + missing months, no organization id in
   * the text), so the client can explain it and point to the fiscal calendar. The full English
   * message stays in the log.
   *
   * @return {@code true} when the answer was written
   */
  boolean sendIncompleteFiscalYear(HttpServletResponse response, Throwable e, String entityName)
      throws IOException {
    IncompleteFiscalYearException incomplete = IncompleteFiscalYearException.find(e);
    if (incomplete == null) {
      return false;
    }
    log.warn("/" + getModelKey() + "/" + entityName + ": " + incomplete.getMessage());
    try {
      servlet.writeResponse(response,
          NeoResponse.error(IncompleteFiscalYearException.HTTP_STATUS, incomplete.toErrorBody()));
    } catch (org.codehaus.jettison.json.JSONException jsonEx) {
      servlet.sendError(response, IncompleteFiscalYearException.HTTP_STATUS, incomplete.userText());
    }
    return true;
  }

  /**
   * Runs the {@link FiscalDeclCrudHandler} delegate for "declarations" or "incidents" — both of
   * which bypass {@link #isKnownEntity}/the year-period gate entirely — and translates any
   * failure into a 500, sharing the error-message format so it's defined once rather than once
   * per entity.
   */
  private void delegateToDeclHandler(String entityName, String method, HttpServletRequest request,
      HttpServletResponse response) throws IOException {
    try {
      if (DECLARATIONS.equals(entityName)) {
        declHandler.handleDeclarations(method, request, response);
      } else {
        declHandler.handleIncidents(method, request, response);
      }
    } catch (MalformedJsonException e) {
      servlet.sendError(response, HttpServletResponse.SC_BAD_REQUEST, "Invalid JSON body");
    } catch (Exception e) {
      log.error("Error in /" + getModelKey() + "/" + entityName, e);
      servlet.sendError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, userMessage(e));
    }
  }

  /**
   * Builds the message sent to the browser for a failed fiscal request.
   *
   * <p>Two transformations, both required for the text to be readable:
   * <ol>
   *   <li><b>Unwrap.</b> {@link FiscalHandlerException} carries no message of its own — it is
   *       built as {@code super(cause)}, so its {@code getMessage()} is the cause's
   *       {@code toString()} and therefore prefixed with the cause's fully-qualified class name.
   *       Reading the cause's own message drops that prefix. Only our own wrapper is unwrapped;
   *       any other exception keeps its message as-is.</li>
   *   <li><b>Translate.</b> Etendo business logic raises errors carrying a raw AD_Message key
   *       (e.g. {@code @AEAT349_Phone_Contact_Mandatory@} thrown by {@code AEAT3492010Report}).
   *       Forwarding that verbatim shows the literal key to the user, so it goes through
   *       {@link NeoMessageTranslator#safeParseTranslation}. This covers every AEAT message
   *       raised under both /fiscal349 and /fiscal303, which share this dispatch path.</li>
   * </ol>
   *
   * <p>Falls back to the original message whenever unwrapping would yield nothing, so an
   * exception with a message-less cause never degrades into a blank error.
   *
   * <p>ETP-5027 (QA F6): and falls back once more to {@link #GENERIC_ERROR_MESSAGE} when there is
   * no message at all. {@code getMessage()} is legitimately null for e.g. a
   * {@code NullPointerException}, {@code safeParseTranslation} passes null straight through, and
   * {@code NeoResponse.error} then puts a null that jettison's jettison drops — leaving the
   * browser a message-less {@code {"error":{"status":500}}} with nothing to show the user. This
   * method is the single funnel for every fiscal error message, so the floor belongs here.
   */
  protected String userMessage(Throwable t) {
    String message = t.getMessage();
    if (t instanceof FiscalHandlerException && t.getCause() != null
        && t.getCause().getMessage() != null && !t.getCause().getMessage().isEmpty()) {
      message = t.getCause().getMessage();
    }
    String translated = NeoMessageTranslator.safeParseTranslation(message);
    return StringUtils.isBlank(translated) ? GENERIC_ERROR_MESSAGE : translated;
  }

  protected abstract boolean isKnownEntity(String entityName);

  @SuppressWarnings("java:S1172")
  protected boolean allowsPost(String entityName) { return false; }

  /**
   * Whether {@code entityName} may be reached with GET. Defaults to {@code true} because every
   * fiscal entity is a read. Mutating entities MUST override this to return {@code false} for
   * themselves — {@code /fiscal349/validate-vies}, which writes VIES statuses, and
   * {@code /fiscal303/submit}, which files a declaration with the AEAT.
   *
   * <p><b>This is not a drive-by or CSRF defence, and must not be described as one.</b> NEO
   * authenticates with a Bearer token in the {@code Authorization} header (every call in
   * {@code fiscalModelsUtils.js} sets it), so a link, an address bar, an {@code <img>} prefetch
   * or a cross-site form carries no credential and gets a 401 whatever the method. The real
   * reasons are narrower, and all three are enough on their own:
   * <ul>
   *   <li>A GET with side effects lands in browser history, proxy caches and access logs.</li>
   *   <li>Many HTTP clients and proxies <b>auto-retry GETs</b> but not POSTs, so a retry can
   *       re-fire the side effect. Guards like {@code ALREADY_SUBMITTED} limit the consequence;
   *       they do not remove it.</li>
   *   <li>Plain HTTP semantics: GET must be safe and idempotent. Filing a declaration, or
   *       writing back VIES statuses, is neither.</li>
   * </ul>
   */
  @SuppressWarnings("java:S1172")
  protected boolean allowsGet(String entityName) { return true; }

  protected abstract void dispatch(String entityName, String orgId, int year, String period,
      HttpServletRequest request, HttpServletResponse response) throws FiscalHandlerException;

  protected abstract String getModelKey();

  /**
   * Functional shape for the per-model work {@link #runDispatch} wraps — one {@code dispatch()}
   * override's whole if/else entity chain, as a lambda.
   */
  @FunctionalInterface
  protected interface DispatchBody {
    /**
     * Runs one {@code dispatch()} override's entity if/else chain. Declares the generic
     * {@code Exception} deliberately (SonarQube java:S112 reviewed, not a shortcut): the lambda
     * bodies it wraps call into siblings that each throw a different checked type (JSON parsing,
     * I/O on the response writer, DAL/report lookups), and {@link #runDispatch} exists precisely
     * to funnel every one of them into the single {@link FiscalHandlerException} translation —
     * narrowing this to a specific type would defeat that purpose.
     *
     * @throws Exception whatever checked exception the wrapped {@code dispatch()} entity chain
     *                    raises — {@link #runDispatch} is the single place that translates it.
     */
    @SuppressWarnings("java:S112")
    void run() throws Exception;
  }

  /**
   * Runs {@code body} (a {@code dispatch()} override's entity if/else chain), translating any
   * {@link AlreadySubmittedException} into a clean 409 and any other exception into a
   * {@link FiscalHandlerException} — the exact try/catch chain every {@code dispatch()} override
   * needs. Hoisted here (ETP-5438, SonarQube java:S1192) once {@link Fiscal303BoxesHandler#dispatch}
   * and {@link Fiscal349BoxesHandler#dispatch} both needed the identical chain: CPD flagged it as a
   * duplicated block spanning the catch clauses plus the two model-fixed wrapper methods
   * immediately below them (both subclasses' {@code guardNotAlreadySubmitted(orgId, year, period)}
   * and {@code getModelKey()} — CPD normalizes literals, so the two differ only by the AEAT model
   * code/URL segment and still matched as one duplicate).
   */
  protected void runDispatch(HttpServletResponse response, DispatchBody body)
      throws FiscalHandlerException {
    try {
      body.run();
    } catch (AlreadySubmittedException e) {
      try {
        servlet.sendError(response, HttpServletResponse.SC_CONFLICT, e.getMessage());
      } catch (Exception ioEx) {
        throw new FiscalHandlerException(ioEx);
      }
    } catch (MalformedJsonException e) {
      try {
        servlet.sendError(response, HttpServletResponse.SC_BAD_REQUEST, "Invalid JSON body");
      } catch (Exception ioEx) {
        throw new FiscalHandlerException(ioEx);
      }
    } catch (FiscalHandlerException e) {
      throw e;
    } catch (Exception e) {
      throw new FiscalHandlerException(e);
    }
  }

  /**
   * Replaces the persisted AEAT validation rows ({@code ETGO_Fiscal_Decl_Incident}) for a
   * declaration: deletes every existing row for it, then inserts one row per entry in
   * {@code errors} (severity {@code block}) followed by one row per entry in {@code warnings}
   * (severity {@code warn}), both parsed as {@code "CODE - message"}. Called by
   * {@link Fiscal303BoxesHandler} on EVERY submission attempt (test and production alike) — empty
   * {@code errors} and {@code warnings} lists simply leave the declaration with no incident rows.
   * See {@link FiscalDeclCrudHandler#replaceIncidents} for the persistence details (shared with
   * the read path, {@code GET /fiscal303/incidents}).
   */
  protected void replaceIncidents(BaseOBObject decl, List<String> errors, List<String> warnings) {
    declHandler.replaceIncidents(decl, errors, warnings);
  }

  /**
   * Same as {@link #replaceIncidents} but does not commit — used by
   * {@link Fiscal303SubmissionSupport#handleSubmit} so the incidents write shares a single
   * transaction with the declaration status/attachment write that follows it (ETP-4456
   * atomicity fix). See {@link FiscalDeclCrudHandler#replaceIncidentsNoCommit}.
   */
  protected void replaceIncidentsNoCommit(BaseOBObject decl, List<String> errors,
      List<String> warnings) {
    declHandler.replaceIncidentsNoCommit(decl, errors, warnings);
  }

  // ── shared helpers ────────────────────────────────────────────────

  protected void handleModified(String orgId, int year, String period, Date since,
      HttpServletResponse response) throws Exception {
    List<Period> periods = resolvePeriods(orgId, year, period);
    if (periods.isEmpty()) { writeModifiedJson(response, false, 0); return; }
    Date fromDate = periods.get(0).getStartingDate();
    Date toDate   = periods.get(periods.size() - 1).getEndingDate();
    if (fromDate == null || toDate == null) { writeModifiedJson(response, false, 0); return; }

    Long count = (Long) OBDal.getInstance().getSession()
        .createQuery(
            "select count(i.id) from Invoice i "
            + "where i.organization.id = :orgId "
            + "  and i.invoiceDate between :fromDate and :toDate "
            + "  and i.updated > :since")
        .setParameter("orgId",    orgId)
        .setParameter("fromDate", fromDate)
        .setParameter("toDate",   toDate)
        .setParameter(SINCE_KEY,  since)
        .uniqueResult();

    boolean modified = count != null && count > 0;
    writeModifiedJson(response, modified, count == null ? 0 : count.intValue());
  }

  private void writeModifiedJson(HttpServletResponse response, boolean modified, int count)
      throws Exception {
    JSONObject out = new JSONObject();
    out.put(MODIFIED, modified);
    out.put("count", count);
    response.setContentType(JSON_CT);
    response.getWriter().write(out.toString());
  }

  protected void writeGeneratedFile(HashMap<String, Object> result, String filenameWithExt,
      HttpServletResponse response) throws Exception {
    Object fileContent = result.get("file");
    if (fileContent == null) {
      throw new OBException("generateElectronicFile returned no file content");
    }
    byte[] bytes = fileContent.toString().getBytes(StandardCharsets.ISO_8859_1);
    response.setContentType("text/plain");
    response.setCharacterEncoding("ISO-8859-1");
    response.setHeader("Content-Disposition",
        "attachment; filename=\"" + filenameWithExt + "\"");
    response.setContentLength(bytes.length);
    response.getOutputStream().write(bytes);
    response.flushBuffer();
  }

  protected String resolveEffectiveOrg() {
    String orgId = OBContext.getOBContext().getCurrentOrganization().getId();
    if (!"0".equals(orgId)) {
      return orgId;
    }
    // Session org is * — find the non-summary leaf org for the current client.
    String clientId = OBContext.getOBContext().getCurrentClient().getId();
    OBCriteria<Organization> crit = OBDal.getInstance().createCriteria(Organization.class);
    crit.add(Restrictions.eq(Organization.PROPERTY_CLIENT + ".id", clientId));
    crit.add(Restrictions.eq(Organization.PROPERTY_SUMMARYLEVEL, false));
    crit.add(Restrictions.ne(Organization.PROPERTY_ID, "0"));
    crit.addOrder(Order.asc(Organization.PROPERTY_NAME));
    crit.setMaxResults(1);
    List<Organization> orgs = crit.list();
    if (orgs.isEmpty()) {
      throw new OBException("No leaf organization found for client=" + clientId);
    }
    return orgs.get(0).getId();
  }

  protected AcctSchema resolveAcctSchema() {
    String clientId = OBContext.getOBContext().getCurrentClient().getId();
    OBCriteria<AcctSchema> crit = OBDal.getInstance().createCriteria(AcctSchema.class);
    crit.add(Restrictions.eq(AcctSchema.PROPERTY_CLIENT + ".id", clientId));
    crit.add(Restrictions.eq(AcctSchema.PROPERTY_ACTIVE, true));
    crit.setMaxResults(1);
    List<AcctSchema> list = crit.list();
    if (list.isEmpty()) {
      throw new OBException("No AcctSchema found for client=" + clientId);
    }
    return list.get(0);
  }

  /**
   * Org-scoped (falls back to org {@code "0"}) {@code TaxReport} searchKey lookup, shared by
   * every fiscal model's {@code TaxReport} resolution — hoisted here (SonarQube java:S1192/
   * duplicated-block) from {@link Fiscal303BoxesHandler#resolveTaxReport} and {@code
   * Fiscal349BoxesHandler#resolveTaxReport349}, which previously each carried their own
   * byte-identical private copy. Returns {@code null} (never throws) so callers can fall through
   * to a different searchKey on an empty result instead of failing outright.
   */
  protected TaxReport findTaxReport(String orgId, String searchKey) {
    OBCriteria<TaxReport> crit = OBDal.getInstance().createCriteria(TaxReport.class);
    crit.add(Restrictions.in(TaxReport.PROPERTY_ORGANIZATION + ".id", Arrays.asList(orgId, "0")));
    crit.add(Restrictions.eq(TaxReport.PROPERTY_SEARCHKEY, searchKey));
    crit.addOrder(Order.desc(TaxReport.PROPERTY_ORGANIZATION + ".id"));
    crit.setMaxResults(1);
    List<TaxReport> list = crit.list();
    return list.isEmpty() ? null : list.get(0);
  }

  /**
   * A {@code TaxReport} picked by {@link #findLatestTaxReport}: the row itself, its search key and
   * the layout year parsed from that key. {@link #isFallback} tells whether the layout belongs to
   * an earlier exercise than the one being declared.
   */
  static final class ResolvedTaxReport {
    final TaxReport report;
    final String searchKey;
    final int layoutYear;
    final int requestedYear;

    ResolvedTaxReport(TaxReport report, String searchKey, int layoutYear, int requestedYear) {
      this.report = report;
      this.searchKey = searchKey;
      this.layoutYear = layoutYear;
      this.requestedYear = requestedYear;
    }

    boolean isFallback() {
      return layoutYear != requestedYear;
    }
  }

  /**
   * Year-fallback {@code TaxReport} lookup (ETP-5716): the report of {@code year} when one
   * exists, otherwise the one of the latest earlier year. Candidates are the ACTIVE rows of org
   * {@code orgId} or org {@code "0"} whose search key matches {@code searchKeyLike} (SQL LIKE,
   * a coarse pre-filter) and then {@code yearPattern}, whose group 1 must capture the 4-digit
   * layout year. See {@link #pickLatestTaxReport} for the selection rule. Only Modelo 390 uses
   * it: 303/349 keep their exact-key {@link #findTaxReport} lookups.
   *
   * @return the resolved report, or {@code null} when no candidate qualifies
   */
  protected ResolvedTaxReport findLatestTaxReport(String orgId, String searchKeyLike,
      Pattern yearPattern, int year) {
    OBCriteria<TaxReport> crit = OBDal.getInstance().createCriteria(TaxReport.class);
    crit.add(Restrictions.in(TaxReport.PROPERTY_ORGANIZATION + ".id", Arrays.asList(orgId, "0")));
    crit.add(Restrictions.like(TaxReport.PROPERTY_SEARCHKEY, searchKeyLike));
    crit.add(Restrictions.eq(TaxReport.PROPERTY_ACTIVE, true));
    return pickLatestTaxReport(crit.list(), yearPattern, year, orgId);
  }

  /**
   * Selection rule of {@link #findLatestTaxReport}, free of any database access:
   * <ol>
   *   <li>inactive rows and search keys not fully matching {@code yearPattern} are ignored — so
   *       an inactive or oddly-named row can never shadow a valid one;</li>
   *   <li>rows whose layout year is after {@code year} are ignored (the DECLARED exercise decides,
   *       never today's date);</li>
   *   <li>the highest remaining layout year wins;</li>
   *   <li>on a tie, the row of {@code orgId} wins over the org {@code "0"} one.</li>
   * </ol>
   *
   * @return the winner, or {@code null} when nothing qualifies
   */
  static ResolvedTaxReport pickLatestTaxReport(List<TaxReport> candidates, Pattern yearPattern,
      int year, String orgId) {
    TaxReport best = null;
    int bestYear = Integer.MIN_VALUE;
    boolean bestIsOrgRow = false;
    for (TaxReport candidate : candidates) {
      if (!Boolean.TRUE.equals(candidate.isActive()) || candidate.getSearchKey() == null) {
        continue;
      }
      Matcher m = yearPattern.matcher(candidate.getSearchKey());
      if (!m.matches()) {
        continue;
      }
      int layoutYear = Integer.parseInt(m.group(1));
      if (layoutYear > year) {
        continue;
      }
      boolean isOrgRow = candidate.getOrganization() != null
          && orgId.equals(candidate.getOrganization().getId());
      if (layoutYear > bestYear || (layoutYear == bestYear && isOrgRow && !bestIsOrgRow)) {
        best = candidate;
        bestYear = layoutYear;
        bestIsOrgRow = isOrgRow;
      }
    }
    return best == null ? null
        : new ResolvedTaxReport(best, best.getSearchKey(), bestYear, year);
  }

  /**
   * The period code of an ANNUAL declaration (Modelo 390, AEAT period {@code 0A}):
   * {@link #resolvePeriods} maps it to periods 1-12 of the fiscal year.
   */
  static final String ANNUAL_PERIOD = "0A";

  private static final int MONTHS_PER_YEAR = 12;

  /**
   * Resolves the calendar periods a declaration period code covers: {@code Tn} is quarter n
   * (months 3n-2..3n), {@value #ANNUAL_PERIOD} is the whole fiscal year (months 1-12, and fails
   * when any of them is missing — see {@link #requireFullYear}), anything else is a single month
   * number.
   */
  @SuppressWarnings("unchecked")
  protected List<Period> resolvePeriods(String orgId, int year, String periodCode) {
    int monthFrom;
    int monthTo;
    boolean annual = ANNUAL_PERIOD.equals(periodCode);
    if (annual) {
      monthFrom = 1;
      monthTo   = MONTHS_PER_YEAR;
    } else if (periodCode.startsWith("T")) {
      int q = Integer.parseInt(periodCode.substring(1));
      monthFrom = (q - 1) * 3 + 1;
      monthTo   = q * 3;
    } else {
      monthFrom = Integer.parseInt(periodCode);
      monthTo   = monthFrom;
    }
    List<Period> periods = OBDal.getInstance().getSession()
        .createQuery(
            "from FinancialMgmtPeriod p "
            + "where p.organization.id = :orgId "
            + "  and p.year.fiscalYear = :year "
            + "  and p.periodNo between :from and :to "
            + "order by p.periodNo",
            Period.class)
        .setParameter("orgId", orgId)
        .setParameter("year",  String.valueOf(year))
        .setParameter("from",  (long) monthFrom)
        .setParameter("to",    (long) monthTo)
        .list();
    if (annual) {
      requireFullYear(periods, orgId, year);
    }
    return periods;
  }

  /**
   * An annual declaration over an incomplete calendar would silently under-report, so a fiscal
   * year that does not have exactly one period per month 1-12 is rejected, naming the missing
   * months.
   *
   * @throws IncompleteFiscalYearException when any month 1-12 has no period — answered by
   *     {@link #handle} as the structured {@code 422 INCOMPLETE_FISCAL_YEAR}
   */
  static void requireFullYear(List<Period> periods, String orgId, int year) {
    Set<Long> present = new TreeSet<>();
    for (Period p : periods) {
      if (p.getPeriodNo() != null) {
        present.add(p.getPeriodNo());
      }
    }
    List<Long> missing = new ArrayList<>();
    for (long month = 1; month <= MONTHS_PER_YEAR; month++) {
      if (!present.contains(month)) {
        missing.add(month);
      }
    }
    if (!missing.isEmpty()) {
      throw new IncompleteFiscalYearException(orgId, year, missing);
    }
  }
}
