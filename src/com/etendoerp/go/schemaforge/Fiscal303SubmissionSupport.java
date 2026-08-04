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
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.base.exception.OBException;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.financialmgmt.accounting.coa.AcctSchema;
import org.openbravo.model.financialmgmt.calendar.Period;
import org.openbravo.module.aeat303.es.presentation.AEAT303DeclarationData;
import org.openbravo.module.aeat303.es.presentation.AEAT303DeclarationDataExtractor;
import org.openbravo.module.aeat303.es.presentation.AEAT303SubmissionResult;
import org.openbravo.module.aeat303.es.presentation.AEAT303SubmissionService;
import org.openbravo.module.taxreportlauncher.TaxReport;
import org.openbravo.module.taxreportlauncher.erpCommon.ad_reports.OBTL_TaxReport_I;

import com.etendoerp.go.schemaforge.data.FiscalDecl;

/**
 * Handles the AEAT 303 telematic submission entities — {@code GET/POST /neo/fiscal303/generate}
 * and {@code POST /neo/fiscal303/submit} — on behalf of {@link Fiscal303BoxesHandler}, which
 * constructs one instance of this class and delegates to it from {@code dispatch(...)}.
 *
 * <p>Extracted verbatim from {@link Fiscal303BoxesHandler} (ETP-4456) purely to keep that class's
 * method count under the SonarQube {@code java:S1448} threshold (it had grown to 47 methods,
 * mixing this "submission" concern with the pre-existing box-computation concern). This class is
 * exactly that concern: everything the {@code /generate} and {@code /submit} entities need, and
 * nothing else. No behavior change — every method here is a direct move, calling back into
 * {@code owner} for the routing constants, JSON-shape helpers ({@code belongsTo}/
 * {@code declarationDataJson}/{@code buildSubmissionResultJson}/{@code buildFailureJson}/
 * {@code resolveNrcForSubmission}/{@code safeFileToken}/{@code resolveDeclType}/
 * {@code resolveTaxReport}) and inherited {@link AbstractFiscalHandler} infrastructure
 * ({@code servlet}, {@code log}, {@code resolveAcctSchema}, {@code resolvePeriods},
 * {@code writeGeneratedFile}, {@code replaceIncidentsNoCommit}) that all stay declared exactly
 * where they already were. {@code owner}'s package-private/protected members are reachable here
 * because this class lives in the same package, not because of any inheritance relationship.</p>
 */
class Fiscal303SubmissionSupport {

  private static final String STATUS_SUBMITTED_ACK = "submitted_ack";
  private static final String ERR_NO_CERTIFICATE = "NO_CERTIFICATE";
  private static final String ERR_MISSING_PRESENTER = "MISSING_PRESENTER";
  private static final String ERR_SUBMISSION_FAILED = "SUBMISSION_FAILED";
  private static final String ERR_ALREADY_SUBMITTED = "ALREADY_SUBMITTED";

  /**
   * Query params consumed structurally by this handler's own routing (year/period/tipo/id) —
   * never forwarded to {@code OBTL_TaxReport_I#generateElectronicFile} as AEAT protocol params.
   * Everything else in the request is a candidate AEAT param name owned by the frontend's own
   * {@code IDENT_PARAM_MAP}/{@code BOX_PARAM_MAP} (see {@code fiscalModelsUtils.js}), which must
   * NOT be duplicated here — those maps evolve independently of this handler.
   */
  private static final Set<String> STRUCTURAL_PARAMS =
      Set.of("year", AbstractFiscalHandler.PERIOD_KEY, "tipo", Fiscal303BoxesHandler.ID_KEY);

  private final Fiscal303BoxesHandler owner;

  Fiscal303SubmissionSupport(Fiscal303BoxesHandler owner) {
    this.owner = owner;
  }

  void handleGenerate(String orgId, int year, String period, String tipo,
      HttpServletRequest request, HttpServletResponse response) throws Exception {
    String filename = "303_" + period + "_" + year;
    HashMap<String, Object> result =
        generateElectronicFile(orgId, year, period, tipo, filename, request);
    owner.writeGeneratedFile(result, filename + ".txt", response);
  }

  /**
   * Regenerates the {@code .303} electronic file content server-side — the same reflective call
   * to the Classic {@code OBTL_TaxReport_I} implementation used by {@link #handleGenerate} for
   * downloads. Reused by the AEAT telematic submission entity ({@link #handleSubmit}) so that a
   * client-supplied file is never trusted: every submission is generated fresh from the current
   * DB state, exactly like a manual "Generar fichero" download.
   *
   * <p>Every non-structural request query parameter (i.e. anything other than {@code
   * STRUCTURAL_PARAMS}) is forwarded verbatim into {@code inputParams} — these are the
   * AEAT-protocol-named identification/box-override params the frontend builds via its own
   * {@code IDENT_PARAM_MAP}/{@code BOX_PARAM_MAP} (e.g. {@code IBAN}, {@code BIC},
   * {@code Special_Compensations}). Only declaration types {@code U}/{@code D}/{@code X}
   * (Domiciliación / Devolución / Devolución transferencia extranjero) accept or require
   * {@code IBAN} downstream in {@code AEAT303Report2014} — sending it for any other tipo (e.g.
   * {@code I}) is rejected by AEAT with error {@code EDID065}.</p>
   *
   * @return the raw result map from {@code OBTL_TaxReport_I#generateElectronicFile}, with the
   *         flat file content under key {@code "file"}
   */
  HashMap<String, Object> generateElectronicFile(String orgId, int year, String period,
      String tipo, String filename, HttpServletRequest request) throws Exception {
    boolean quarterly = period.startsWith("T");
    String valueKey = quarterly ? "AEAT303_Q_" + year : "AEAT303_M_" + year;

    TaxReport taxReport   = owner.resolveTaxReport(orgId, valueKey);
    AcctSchema acctSchema = owner.resolveAcctSchema();
    List<Period> periods  = owner.resolvePeriods(orgId, year, period);

    if (periods.isEmpty()) {
      throw new OBException(
          "No fiscal periods found for org=" + orgId + " year=" + year + " period=" + period);
    }

    String yearId    = periods.get(0).getYear().getId();
    String periodIds = periods.stream().map(Period::getId).collect(Collectors.joining(","));
    String reportId  = taxReport.getId();
    String acctId    = acctSchema.getId();
    String className = taxReport.getJavaClassName();

    Map<String, String> inputParams = new HashMap<>();
    inputParams.put("FileName", filename);
    // Declaration type required by AEAT303_Utility.getCheckedInputParameter.
    // Frontend sends AEAT letter codes directly: C, I, V, U, G. Default N (zero result).
    inputParams.put("Declaration_" + Fiscal303BoxesHandler.resolveDeclType(tipo), "Y");
    // Box 65: percentage attributable to the State (always 100 for Modelo 303).
    inputParams.put("ToPublicTreasury", "100");
    mergeAeatRequestParams(inputParams, request);

    OBTL_TaxReport_I report = (OBTL_TaxReport_I)
        Class.forName(className).getDeclaredConstructor().newInstance();

    return report.generateElectronicFile(orgId, reportId, acctId, yearId, periodIds, inputParams);
  }

  /**
   * Forwards every request query parameter that is not {@link #STRUCTURAL_PARAMS} into {@code
   * inputParams} — verbatim, blank values skipped. These are AEAT-protocol-named identification
   * and box-override params the frontend already builds (see class-level javadoc on {@link
   * #generateElectronicFile}); this handler does not hardcode their names so the frontend's own
   * param maps can evolve independently without this merge drifting out of sync.
   */
  private static void mergeAeatRequestParams(Map<String, String> inputParams,
      HttpServletRequest request) {
    for (Map.Entry<String, String[]> entry : request.getParameterMap().entrySet()) {
      String key = entry.getKey();
      if (STRUCTURAL_PARAMS.contains(key)) {
        continue;
      }
      String[] values = entry.getValue();
      String value = values != null && values.length > 0 ? values[0] : null;
      if (StringUtils.isNotBlank(value)) {
        inputParams.put(key, value);
      }
    }
  }

  /**
   * Handles {@code POST /neo/fiscal303/submit?year=&period=&tipo=&id=<declId>}: regenerates the
   * {@code .303} file server-side and presents it to the AEAT (production, {@code PresBasicaDos})
   * or validates it only (test mode, {@code ServValiDos}), reusing the exact Classic protocol
   * implementation in {@code org.openbravo.module.aeat303.es.presentation.AEAT303SubmissionService}.
   *
   * <p>Request body (JSON): {@code { testMode, idi, nrc, presenterNif, presenterName }}.
   * {@code presenterNif}/{@code presenterName} are mandatory for a production submission (AEAT
   * Firma No Criptográfica requires them; the frontend should default them from the same org
   * identification data already used for file generation) and ignored in test mode.</p>
   *
   * <p><b>Certificate resolution:</b> only the organization's stored certificate
   * ({@code ETSG_Certificate}, uploaded beforehand via {@code POST /neo/certificate}) is used —
   * unlike Classic's popup flow, this endpoint does NOT support a session-only certificate
   * upload fallback in the same call. A stateless single-POST API has no clean equivalent of
   * Classic's multi-step "upload cert then submit" screen flow; requiring the certificate to
   * already be stored is a simpler, better fit for Go's REST style. Test-mode (ServValiDos)
   * submissions never need a certificate at all, so this restriction only affects production.</p>
   *
   * <p><b>Persistence:</b> only a successful PRODUCTION submission updates the declaration
   * record itself (status → {@code submitted_ack}, file name set) and attaches the justificante
   * PDF, both via {@link #persistSuccessfulSubmission} — matching Classic's "test submissions
   * leave no trace" rule for the declaration's own fields. A successful TEST-mode submission
   * (ServValiDos) does NOT touch the declaration record at all — no status/filename/
   * {@code fileExternal} change — but DOES attach the returned PDF as a clearly test-labeled
   * artifact via {@link #attachTestJustificante}, so the user can review it from the same
   * Justificante tab without it being confusable with a real justificante. Failed submissions
   * (either mode) never mutate the declaration and never attach anything. The AEAT-incidents
   * write ({@link #persistIncidentsBestEffort}) and this status/attachment write share ONE
   * transaction — a single {@link #commitSubmissionBestEffort} call at the end of this method,
   * not two-to-three independent commits — so a mid-process failure can't leave a reachable
   * partial state (ETP-4456 atomicity fix).</p>
   *
   * <p><b>Resubmission guard:</b> a production submission is rejected outright ({@code
   * ALREADY_SUBMITTED}, no call to the AEAT) when the declaration's status is already {@code
   * submitted_ack} — this blocks an accidental double-submission (double-click, network retry,
   * second tab) of a declaration the AEAT already accepted. Filing a genuine complementaria for
   * an already-submitted declaration is a distinct, manual process, not implemented by this
   * endpoint. Test-mode validations are never blocked by this guard: they never change
   * declaration status, so re-validating an already-submitted declaration is harmless.</p>
   *
   * <p>The resubmission and missing-presenter guards are checked via {@link
   * #rejectResubmissionOrMissingPresenter} — extracted purely to keep this method's cognitive
   * complexity under the SonarQube {@code java:S3776} threshold (ETP-4456). That extraction
   * deliberately stops short of also folding in the certificate guard below: a QA test asserts
   * {@link AEAT303SubmissionService} is never constructed at all for an already-submitted
   * declaration, so the org/service construction between the two guard groups — and therefore the
   * split point itself — must stay exactly where it already was.</p>
   */
  void handleSubmit(String orgId, int year, String period, String tipo, String declId,
      HttpServletRequest request, HttpServletResponse response) throws Exception {
    if (StringUtils.isBlank(declId)) {
      owner.servlet.sendError(response, HttpServletResponse.SC_BAD_REQUEST,
          "Missing required param: id");
      return;
    }

    String clientId = OBContext.getOBContext().getCurrentClient().getId();
    FiscalDecl decl = OBDal.getInstance().get(FiscalDecl.class, declId);
    if (!owner.belongsTo(decl, clientId, orgId)) {
      owner.servlet.sendError(response, HttpServletResponse.SC_NOT_FOUND,
          "Declaration not found: " + declId);
      return;
    }

    JSONObject body = readJsonBody(request);
    boolean testMode = body.optBoolean(Fiscal303BoxesHandler.PARAM_TEST_MODE, false);
    String idi = StringUtils.defaultIfBlank(body.optString("idi", null), "ES");
    String nrc = body.optString("nrc", "");
    String presenterNif = body.optString("presenterNif", "");
    String presenterName = body.optString("presenterName", "");

    if (rejectResubmissionOrMissingPresenter(decl, testMode, presenterNif, presenterName,
        response)) {
      return;
    }

    Organization org = OBDal.getInstance().get(Organization.class, orgId);
    AEAT303SubmissionService service = new AEAT303SubmissionService();

    if (!testMode && !service.hasOrgCertificate(org)) {
      writeJson(response, HttpServletResponse.SC_CONFLICT,
          owner.buildFailureJson(false, ERR_NO_CERTIFICATE,
              "No certificate configured for this organization. Upload one via Fiscal "
                  + "Configuration before submitting in production mode."));
      return;
    }

    String filename = "303_" + period + "_" + year;
    String fileContent;
    try {
      HashMap<String, Object> generated =
          generateElectronicFile(orgId, year, period, tipo, filename, request);
      Object fileObj = generated.get("file");
      if (fileObj == null) {
        throw new OBException("generateElectronicFile returned no file content");
      }
      fileContent = fileObj.toString();
    } catch (Exception e) {
      owner.log.error("Could not generate the 303 electronic file for submission (decl=" + declId
          + ")", e);
      writeJson(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
          owner.buildFailureJson(testMode, ERR_SUBMISSION_FAILED,
              "Could not generate the declaration file: " + e.getMessage()));
      return;
    }

    AEAT303DeclarationData data = AEAT303DeclarationDataExtractor.extract(fileContent);
    String nrcToSubmit =
        Fiscal303BoxesHandler.resolveNrcForSubmission(data.getDeclarationType(), nrc);

    AEAT303SubmissionResult result;
    try {
      if (testMode) {
        result = service.submitValidation(fileContent, data.getFiscalYear(), data.getPeriod(), idi);
      } else {
        result = service.submitProduction(AEAT303SubmissionService.ProductionSubmissionRequest.builder()
            .org(org)
            .fileContent(fileContent)
            .fiscalYear(data.getFiscalYear())
            .period(data.getPeriod())
            .presenterNif(presenterNif)
            .presenterName(presenterName)
            .nrc(nrcToSubmit)
            .language(idi)
            .build());
      }
    } catch (OBException e) {
      owner.log.error("AEAT 303 submission failed (decl=" + declId + ")", e);
      writeJson(response, HttpServletResponse.SC_BAD_GATEWAY,
          owner.buildFailureJson(testMode, ERR_SUBMISSION_FAILED, e.getMessage()));
      return;
    }

    persistIncidentsBestEffort(decl, declId, result);

    if (result.isSuccessful()) {
      if (testMode) {
        attachTestJustificante(decl, org, data, result);
      } else {
        persistSuccessfulSubmission(decl, org, data, result);
      }
    }

    commitSubmissionBestEffort(declId);

    writeJson(response, HttpServletResponse.SC_OK, owner.buildSubmissionResultJson(result, data));
  }

  /**
   * Validates the two production-submission guards that must be checked BEFORE the organization
   * is loaded and {@link AEAT303SubmissionService} is even constructed — resubmission of an
   * already-accepted declaration, and missing presenter identification — writing the matching
   * error response itself and returning {@code true} when the request was rejected (the caller
   * must return immediately in that case). Test-mode validations never trip either guard: they
   * never change declaration status, so re-validating an already-submitted declaration is
   * harmless and stays allowed.
   *
   * <p>Deliberately does NOT also fold in the certificate guard: that one needs {@code org}/
   * {@code service}, which {@link #handleSubmit} only creates once these two checks pass, and a
   * QA regression test asserts {@link AEAT303SubmissionService} is never constructed at all for
   * an already-submitted declaration — moving its construction ahead of that check (by combining
   * all three guards here) would break that guarantee. Extracted from {@link #handleSubmit}
   * purely to keep its cognitive complexity under the SonarQube {@code java:S3776} threshold
   * (ETP-4456); same checks, same order, no behavior change.</p>
   */
  private boolean rejectResubmissionOrMissingPresenter(FiscalDecl decl, boolean testMode,
      String presenterNif, String presenterName, HttpServletResponse response) throws Exception {
    // Guard against a naive resubmission of a declaration already accepted by the AEAT: a
    // repeat production presentation of the SAME declaration must be filed as a "complementaria"
    // (per the AEAT protocol notes), which is a distinct, manual process with its own
    // declaration-type flag — deliberately NOT implemented here. This endpoint only blocks the
    // silent, unguarded resubmission (double-click, network retry, second tab); it never
    // attempts a complementaria filing itself. Test-mode (ServValiDos) validations never change
    // declaration status, so re-validating an already-submitted declaration is harmless and
    // stays allowed — only production resubmission is blocked.
    if (!testMode && STATUS_SUBMITTED_ACK.equals(decl.getDeclarationStatus())) {
      writeJson(response, HttpServletResponse.SC_CONFLICT,
          owner.buildFailureJson(false, ERR_ALREADY_SUBMITTED,
              "This declaration was already submitted to the AEAT (status: " + STATUS_SUBMITTED_ACK
                  + "). Resubmitting the same declaration in production is not supported here — "
                  + "filing a complementaria is a separate, manual process."));
      return true;
    }

    if (!testMode && (StringUtils.isBlank(presenterNif) || StringUtils.isBlank(presenterName))) {
      writeJson(response, HttpServletResponse.SC_BAD_REQUEST,
          owner.buildFailureJson(false, ERR_MISSING_PRESENTER,
              "presenterNif and presenterName are required for a production submission"));
      return true;
    }

    return false;
  }

  /**
   * Single commit boundary for the whole submission write path (ETP-4456 atomicity fix): the
   * incidents replace ({@link #persistIncidentsBestEffort}) and, on success, the declaration
   * status/attachment write ({@link #persistSuccessfulSubmission} / {@link
   * #attachTestJustificante}) now all land in this ONE {@code commitAndClose()} instead of the
   * previous 2-3 independent commits — a process death between those used to leave a reachable
   * partial state (e.g. incidents persisted but the declaration status/attachment not updated,
   * or vice versa). Best-effort by design: a failure here is logged but must never fail or mask
   * the submission result already computed and about to be written to the caller.
   */
  private void commitSubmissionBestEffort(String declId) {
    try {
      OBDal.getInstance().commitAndClose();
    } catch (Exception e) {
      owner.log.error("Could not commit AEAT 303 submission persistence for declaration "
          + declId, e);
    }
  }

  /**
   * Persists (delete-then-reinsert) {@code result}'s AEAT error AND warning lists into
   * {@code ETGO_Fiscal_Decl_Incident} for {@code decl} — called on EVERY submission attempt
   * (test mode and production alike) by {@link #handleSubmit}, per explicit product decision
   * (ETP-4456), so the "Incidencias" tab always reflects only the LATEST attempt, never a stale
   * accumulation from a prior try. Errors are tagged {@code block}, warnings {@code warn} (see
   * {@link FiscalDeclCrudHandler#replaceIncidentsNoCommit}). Empty error AND warning lists (the
   * clean success case) simply leave the declaration with no incident rows. Best-effort: a
   * persistence failure here is logged and must never mask the actual submission outcome
   * {@code handleSubmit} already computed. Uses the no-commit variant deliberately — the actual
   * commit happens once, later, in {@link #commitSubmissionBestEffort}, together with the
   * declaration status/attachment write (ETP-4456 atomicity fix).
   */
  private void persistIncidentsBestEffort(FiscalDecl decl, String declId,
      AEAT303SubmissionResult result) {
    try {
      owner.replaceIncidentsNoCommit(decl, result.getErrors(), result.getWarnings());
    } catch (Exception e) {
      owner.log.error("Could not persist AEAT incidents for declaration " + declId, e);
    }
  }

  /**
   * Persists the outcome of a successful PRODUCTION submission: declaration status →
   * {@code submitted_ack}, declaration file name set to the justificante file, and the PDF
   * attached (best-effort — see {@link #attachJustificante}). Never called for test-mode results
   * (see {@link #attachTestJustificante} for that path, which attaches the PDF too but never
   * touches the declaration record) or failed submissions (see {@link #handleSubmit}).
   *
   * <p>Deliberately does NOT commit — {@code decl.save()} here and the attachment write in
   * {@link #attachJustificante} land in the same transaction as the incidents write, committed
   * once by {@link #commitSubmissionBestEffort} at the end of {@link #handleSubmit} (ETP-4456
   * atomicity fix).</p>
   */
  private void persistSuccessfulSubmission(FiscalDecl decl, Organization org,
      AEAT303DeclarationData data, AEAT303SubmissionResult result) {
    String fileName = "justificante-303-" + Fiscal303BoxesHandler.safeFileToken(data.getFiscalYear())
        + "-" + Fiscal303BoxesHandler.safeFileToken(data.getPeriod()) + ".pdf";
    try {
      decl.setDeclarationStatus(STATUS_SUBMITTED_ACK);
      decl.setDeclarationFileName(fileName);
      decl.setFileExternal(false);
      OBDal.getInstance().save(decl);
    } catch (Exception e) {
      owner.log.error("Could not update declaration " + decl.getId()
          + " after a successful AEAT 303 submission", e);
    }

    if (result.getPdfContent() != null) {
      attachJustificante(decl, org, fileName, result.getPdfContent());
    }
  }

  /**
   * Attaches the outcome PDF of a successful TEST-mode submission (ServValiDos) to the
   * declaration's Justificante tab, deliberately WITHOUT touching {@code decl} itself — test
   * mode must stay non-authoritative: no status change, no {@code declarationFileName}/
   * {@code fileExternal} update, no {@code save()}/{@code commitAndClose()} on the declaration.
   * {@code decl} already exists and is already committed as-is; this only adds an independent
   * attachment row via the same infra {@link #persistSuccessfulSubmission} uses for production.
   *
   * <p>The filename is prefixed with {@code "TEST-"} so it can never be confused with a real
   * justificante in the attachment list. Repeated test submissions for the same declaration are
   * allowed (test-mode resubmission is not blocked by the {@code ALREADY_SUBMITTED} guard in
   * {@link #handleSubmit}) and each one produces its own attachment sharing the same filename —
   * this is intentionally not disambiguated further: {@code Attachment} rows are keyed by their
   * own id, not by filename uniqueness (see {@link NeoAttachmentsHelper#getAttachManager()} /
   * {@code AttachImplementationManager#upload}, which always creates a new row), and the
   * attachment list UI already surfaces each row's own creation timestamp, so multiple
   * same-named test attachments are distinguishable there without any extra naming scheme here.
   */
  private void attachTestJustificante(FiscalDecl decl, Organization org,
      AEAT303DeclarationData data, AEAT303SubmissionResult result) {
    if (result.getPdfContent() == null) {
      return;
    }
    String fileName = "TEST-justificante-303-" + Fiscal303BoxesHandler.safeFileToken(data.getFiscalYear())
        + "-" + Fiscal303BoxesHandler.safeFileToken(data.getPeriod()) + ".pdf";
    attachJustificante(decl, org, fileName, result.getPdfContent());
  }

  /**
   * Attaches a justificante/receipt PDF to the declaration record using the same generic
   * attachment infrastructure the {@code /neo/attachments} endpoints use
   * ({@link NeoAttachmentsHelper}), mirroring the pattern Classic uses for
   * {@code AEAT303_Presentation} ({@code AEAT303PresentationStore}). Called both for a successful
   * PRODUCTION submission ({@link #persistSuccessfulSubmission}) and a successful TEST-mode
   * submission ({@link #attachTestJustificante}); the caller decides the filename and whether any
   * other declaration field is mutated, this method only ever adds an attachment.
   *
   * <p>{@code ETGO_Fiscal_Decl} now has a registered {@code AD_Window}/{@code AD_Tab} (added in
   * the "Justificante attachment fix" follow-up — see the ETP-4456 plan doc), so
   * {@link NeoAttachmentsHelper#resolveTabId} resolves a real tab and the attach call actually
   * persists. It remains best-effort regardless of that: an attach failure here never blocks or
   * rolls back the submission response, which is why the PDF is always also returned inline
   * (base64) in the API response.</p>
   *
   * <p>Deliberately does NOT commit — {@code AttachImplementationManager#upload} itself never
   * commits (same as every other {@code /neo/attachments} caller in this module), and this
   * method now relies on {@link #commitSubmissionBestEffort} to commit once, together with the
   * incidents write and (for production) the declaration status write, instead of its own
   * independent transaction (ETP-4456 atomicity fix).</p>
   */
  private void attachJustificante(FiscalDecl decl, Organization org, String fileName,
      byte[] pdfContent) {
    String tabId;
    try {
      String tableId = NeoAttachmentsHelper.resolveTableId(FiscalDecl.TABLE_NAME);
      tabId = NeoAttachmentsHelper.resolveTabId(tableId, null);
    } catch (Exception e) {
      owner.log.warn("Could not resolve an AD_Tab for " + FiscalDecl.TABLE_NAME + " attachments", e);
      tabId = null;
    }
    if (tabId == null) {
      owner.log.warn("No AD_Tab configured for " + FiscalDecl.TABLE_NAME + " attachments yet — the "
          + "AEAT 303 justificante PDF for declaration " + decl.getId() + " was not persisted as "
          + "an attachment (it is still returned inline in the API response).");
      return;
    }

    Path tempDir = null;
    try {
      // Files.createTempDirectory (unlike a hand-rolled File.mkdir()/createTempFile() pair) creates
      // the directory with owner-only access out of the box: per the java.nio.file.Files javadoc,
      // when no FileAttribute is passed, the default provider creates it "with permissions that
      // most platforms use for created directories", and Files.createTempDirectory in particular is
      // documented as designed to be at least as restrictive as (and on POSIX, more restrictive
      // than) File.createTempFile — the exact hand-rolled pattern SonarQube's java:S5443 rule is
      // written to catch, where the OS umask decides the final mode. On POSIX systems this resolves
      // to 0700 (owner rwx only), so no other OS user — let alone another application — can list,
      // read, or write into it before the PDF is written and the directory is removed in the
      // `finally` block below. No fixed, predictable, or shared/world-writable path (e.g. a static
      // name under /tmp) is ever used. Reviewed for ETP-4456: this hotspot is a false positive given
      // the API already in use; a human still needs to mark it "Safe" in the SonarQube dashboard — a
      // code comment cannot flip hotspot review status via the API.
      tempDir = Files.createTempDirectory("aeat303go");
      Path pdfPath = tempDir.resolve(fileName);
      Files.write(pdfPath, pdfContent);
      NeoAttachmentsHelper.getAttachManager()
          .upload(new HashMap<>(), tabId, decl.getId(), org.getId(), pdfPath.toFile());
    } catch (Exception e) {
      owner.log.error("Could not attach the AEAT 303 justificante PDF to declaration "
          + decl.getId(), e);
    } finally {
      cleanupTempDir(tempDir);
    }
  }

  private void cleanupTempDir(Path tempDir) {
    if (tempDir == null) {
      return;
    }
    try {
      File[] children = tempDir.toFile().listFiles();
      if (children != null) {
        for (File child : children) {
          Files.deleteIfExists(child.toPath());
        }
      }
      Files.deleteIfExists(tempDir);
    } catch (Exception e) {
      owner.log.warn("Could not clean up temporary directory " + tempDir, e);
    }
  }

  private JSONObject readJsonBody(HttpServletRequest request) throws Exception {
    StringBuilder sb = new StringBuilder();
    try (BufferedReader reader = request.getReader()) {
      String line;
      while ((line = reader.readLine()) != null) sb.append(line);
    }
    String raw = sb.toString();
    return raw.isEmpty() ? new JSONObject() : new JSONObject(raw);
  }

  private void writeJson(HttpServletResponse response, int status, JSONObject body)
      throws Exception {
    response.setContentType(AbstractFiscalHandler.JSON_CT);
    response.setStatus(status);
    response.getWriter().write(body.toString());
  }
}
