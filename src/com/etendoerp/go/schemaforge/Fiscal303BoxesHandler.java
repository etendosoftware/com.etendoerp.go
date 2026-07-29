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
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.ScrollableResults;
import org.hibernate.criterion.Order;
import org.hibernate.criterion.Restrictions;
import org.openbravo.base.exception.OBException;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.common.invoice.Invoice;
import org.openbravo.model.common.invoice.InvoiceTax;
import org.openbravo.model.financialmgmt.accounting.coa.AcctSchema;
import org.openbravo.model.financialmgmt.calendar.Period;
import org.openbravo.model.financialmgmt.tax.TaxRate;
import org.openbravo.module.aeat303.es.api.CashVATOperationType;
import org.openbravo.module.aeat303.es.api.InvoiceType;
import org.openbravo.module.aeat303.es.presentation.AEAT303DeclarationData;
import org.openbravo.module.aeat303.es.presentation.AEAT303DeclarationDataExtractor;
import org.openbravo.module.aeat303.es.presentation.AEAT303SubmissionResult;
import org.openbravo.module.aeat303.es.presentation.AEAT303SubmissionService;
import org.openbravo.module.aeat303.es.report.v2014.AEAT303Report2014Dao;
import org.openbravo.module.aeat303.es.util.AEAT303CalculationsHelper;
import org.openbravo.module.taxreportlauncher.TaxReport;
import org.openbravo.module.taxreportlauncher.TaxReportParameter;
import org.openbravo.module.taxreportlauncher.erpCommon.ad_reports.OBTL_TaxReport_I;

import com.etendoerp.go.schemaforge.data.FiscalDecl;

class Fiscal303BoxesHandler extends AbstractFiscalHandler {

  private static final String BOXES        = "boxes";
  private static final String GENERATE     = "generate";
  private static final String SUBMIT       = "submit";
  private static final String VAT_SALES    = "VAT_SALES";
  private static final String VAT_PURCHASE    = "VAT_PURCHASE";
  private static final String PURCHASE        = "Purchase";
  private static final String TAX_BASE_AMOUNT = "TaxBaseAmount";
  private static final String TAX_AMOUNT      = "TaxAmount";

  // ── AEAT 303 telematic submission (POST /neo/fiscal303/submit) ──────────
  private static final String ID_KEY = "id";
  private static final String STATUS_SUBMITTED_ACK = "submitted_ack";
  private static final String ERR_NO_CERTIFICATE = "NO_CERTIFICATE";
  private static final String ERR_MISSING_PRESENTER = "MISSING_PRESENTER";
  private static final String ERR_SUBMISSION_FAILED = "SUBMISSION_FAILED";
  private static final String ERR_ALREADY_SUBMITTED = "ALREADY_SUBMITTED";
  /** AEAT declaration type character for "a ingresar" (type I): the only type that uses NRC. */
  private static final String DECLARATION_TYPE_INGRESO = "I";

  /**
   * Query params consumed structurally by this handler's own routing (year/period/tipo/id) —
   * never forwarded to {@code OBTL_TaxReport_I#generateElectronicFile} as AEAT protocol params.
   * Everything else in the request is a candidate AEAT param name owned by the frontend's own
   * {@code IDENT_PARAM_MAP}/{@code BOX_PARAM_MAP} (see {@code fiscalModelsUtils.js}), which must
   * NOT be duplicated here — those maps evolve independently of this handler.
   */
  private static final Set<String> STRUCTURAL_PARAMS = Set.of("year", PERIOD_KEY, "tipo", ID_KEY);

  private static final BigDecimal PCT_21   = new BigDecimal("21");
  private static final BigDecimal PCT_10   = new BigDecimal("10");
  private static final BigDecimal PCT_7    = new BigDecimal("7");
  private static final BigDecimal PCT_8    = new BigDecimal("8");
  private static final BigDecimal PCT_4    = new BigDecimal("4");
  private static final BigDecimal PCT_5    = new BigDecimal("5");
  private static final BigDecimal PCT_2    = new BigDecimal("2");
  private static final BigDecimal PCT_1_40 = new BigDecimal("1.40");
  private static final BigDecimal PCT_5_20 = new BigDecimal("5.20");
  private static final BigDecimal PCT_0_50 = new BigDecimal("0.50");
  private static final BigDecimal PCT_0_26 = new BigDecimal("0.26");
  private static final BigDecimal PCT_0_62 = new BigDecimal("0.62");
  private static final BigDecimal PCT_1_00 = new BigDecimal("1.00");
  private static final BigDecimal PCT_1_75 = new BigDecimal("1.75");

  Fiscal303BoxesHandler(NeoServlet servlet) {
    super(servlet);
  }

  @Override
  protected boolean isKnownEntity(String entityName) {
    return BOXES.equals(entityName) || GENERATE.equals(entityName) || SUBMIT.equals(entityName)
        || MODIFIED.equals(entityName);
  }

  @Override
  protected boolean allowsPost(String entityName) {
    return SUBMIT.equals(entityName);
  }

  @Override
  protected void dispatch(String entityName, String orgId, int year, String period,
      HttpServletRequest request, HttpServletResponse response) throws FiscalHandlerException {
    try {
      if (BOXES.equals(entityName)) {
        ComputeResult cr = computeBoxes(orgId, year, period);
        JSONObject result = buildResponse(cr.boxes, cr.sources);
        response.setContentType(JSON_CT);
        response.getWriter().write(result.toString());
      } else if (GENERATE.equals(entityName)) {
        String tipo = request.getParameter("tipo");
        handleGenerate(orgId, year, period, tipo, request, response);
      } else if (SUBMIT.equals(entityName)) {
        String tipo = request.getParameter("tipo");
        String declId = request.getParameter(ID_KEY);
        handleSubmit(orgId, year, period, tipo, declId, request, response);
      } else {
        long sinceMs = Long.parseLong(request.getParameter(SINCE_KEY));
        handleModified(orgId, year, period, new java.util.Date(sinceMs), response);
      }
    } catch (FiscalHandlerException e) {
      throw e;
    } catch (Exception e) {
      throw new FiscalHandlerException(e);
    }
  }

  @Override
  protected String getModelKey() {
    return "fiscal303";
  }

  private void handleGenerate(String orgId, int year, String period, String tipo,
      HttpServletRequest request, HttpServletResponse response) throws Exception {
    String filename = "303_" + period + "_" + year;
    HashMap<String, Object> result =
        generateElectronicFile(orgId, year, period, tipo, filename, request);
    writeGeneratedFile(result, filename + ".txt", response);
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
  // Package-private for unit testing — a spy can stub this out to bypass the reflective
  // OBTL_TaxReport_I call and its TaxReport/AcctSchema/Period DB resolution, mirroring the
  // pattern already used by the package-private computeBoxes(...) overload above.
  HashMap<String, Object> generateElectronicFile(String orgId, int year, String period,
      String tipo, String filename, HttpServletRequest request) throws Exception {
    boolean quarterly = period.startsWith("T");
    String valueKey = quarterly ? "AEAT303_Q_" + year : "AEAT303_M_" + year;

    TaxReport taxReport   = resolveTaxReport(orgId, valueKey);
    AcctSchema acctSchema = resolveAcctSchema();
    List<Period> periods  = resolvePeriods(orgId, year, period);

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
    inputParams.put("Declaration_" + resolveDeclType(tipo), "Y");
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
   * (either mode) never mutate the declaration and never attach anything.</p>
   *
   * <p><b>Resubmission guard:</b> a production submission is rejected outright ({@code
   * ALREADY_SUBMITTED}, no call to the AEAT) when the declaration's status is already {@code
   * submitted_ack} — this blocks an accidental double-submission (double-click, network retry,
   * second tab) of a declaration the AEAT already accepted. Filing a genuine complementaria for
   * an already-submitted declaration is a distinct, manual process, not implemented by this
   * endpoint. Test-mode validations are never blocked by this guard: they never change
   * declaration status, so re-validating an already-submitted declaration is harmless.</p>
   */
  private void handleSubmit(String orgId, int year, String period, String tipo, String declId,
      HttpServletRequest request, HttpServletResponse response) throws Exception {
    if (StringUtils.isBlank(declId)) {
      servlet.sendError(response, HttpServletResponse.SC_BAD_REQUEST, "Missing required param: id");
      return;
    }

    String clientId = OBContext.getOBContext().getCurrentClient().getId();
    FiscalDecl decl = OBDal.getInstance().get(FiscalDecl.class, declId);
    if (!belongsTo(decl, clientId, orgId)) {
      servlet.sendError(response, HttpServletResponse.SC_NOT_FOUND,
          "Declaration not found: " + declId);
      return;
    }

    JSONObject body = readJsonBody(request);
    boolean testMode = body.optBoolean("testMode", false);
    String idi = StringUtils.defaultIfBlank(body.optString("idi", null), "ES");
    String nrc = body.optString("nrc", "");
    String presenterNif = body.optString("presenterNif", "");
    String presenterName = body.optString("presenterName", "");

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
          buildFailureJson(false, ERR_ALREADY_SUBMITTED,
              "This declaration was already submitted to the AEAT (status: " + STATUS_SUBMITTED_ACK
                  + "). Resubmitting the same declaration in production is not supported here — "
                  + "filing a complementaria is a separate, manual process."));
      return;
    }

    if (!testMode && (StringUtils.isBlank(presenterNif) || StringUtils.isBlank(presenterName))) {
      writeJson(response, HttpServletResponse.SC_BAD_REQUEST,
          buildFailureJson(false, ERR_MISSING_PRESENTER,
              "presenterNif and presenterName are required for a production submission"));
      return;
    }

    Organization org = OBDal.getInstance().get(Organization.class, orgId);
    AEAT303SubmissionService service = new AEAT303SubmissionService();

    if (!testMode && !service.hasOrgCertificate(org)) {
      writeJson(response, HttpServletResponse.SC_CONFLICT,
          buildFailureJson(false, ERR_NO_CERTIFICATE,
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
      log.error("Could not generate the 303 electronic file for submission (decl=" + declId + ")", e);
      writeJson(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
          buildFailureJson(testMode, ERR_SUBMISSION_FAILED,
              "Could not generate the declaration file: " + e.getMessage()));
      return;
    }

    AEAT303DeclarationData data = AEAT303DeclarationDataExtractor.extract(fileContent);
    String nrcToSubmit = resolveNrcForSubmission(data.getDeclarationType(), nrc);

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
      log.error("AEAT 303 submission failed (decl=" + declId + ")", e);
      writeJson(response, HttpServletResponse.SC_BAD_GATEWAY,
          buildFailureJson(testMode, ERR_SUBMISSION_FAILED, e.getMessage()));
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

    writeJson(response, HttpServletResponse.SC_OK, buildSubmissionResultJson(result, data));
  }

  /** True when the declaration exists and belongs to the current client/organization. */
  boolean belongsTo(FiscalDecl decl, String clientId, String orgId) {
    return decl != null
        && decl.getClient() != null && clientId.equals(decl.getClient().getId())
        && decl.getOrganization() != null && orgId.equals(decl.getOrganization().getId());
  }

  /**
   * NRC only applies to type I (ingreso) declarations — mirrors
   * {@code AEAT303PresentationServlet#resolveNrcForSubmission} exactly, so a value entered for a
   * non-I declaration is never forwarded to the AEAT.
   */
  static String resolveNrcForSubmission(String declarationType, String nrc) {
    return DECLARATION_TYPE_INGRESO.equals(declarationType) ? StringUtils.defaultString(nrc) : "";
  }

  /**
   * Persists (delete-then-reinsert) {@code result}'s AEAT error AND warning lists into
   * {@code ETGO_Fiscal_Decl_Incident} for {@code decl} — called on EVERY submission attempt
   * (test mode and production alike) by {@link #handleSubmit}, per explicit product decision
   * (ETP-4456), so the "Incidencias" tab always reflects only the LATEST attempt, never a stale
   * accumulation from a prior try. Errors are tagged {@code block}, warnings {@code warn} (see
   * {@link FiscalDeclCrudHandler#replaceIncidents}). Empty error AND warning lists (the clean
   * success case) simply leave the declaration with no incident rows. Best-effort: a persistence
   * failure here is logged and must never mask the actual submission outcome {@code handleSubmit}
   * already computed.
   */
  private void persistIncidentsBestEffort(FiscalDecl decl, String declId,
      AEAT303SubmissionResult result) {
    try {
      replaceIncidents(decl, result.getErrors(), result.getWarnings());
    } catch (Exception e) {
      log.error("Could not persist AEAT incidents for declaration " + declId, e);
    }
  }

  /**
   * Persists the outcome of a successful PRODUCTION submission: declaration status →
   * {@code submitted_ack}, declaration file name set to the justificante file, and the PDF
   * attached (best-effort — see {@link #attachJustificante}). Never called for test-mode results
   * (see {@link #attachTestJustificante} for that path, which attaches the PDF too but never
   * touches the declaration record) or failed submissions (see {@link #handleSubmit}).
   */
  private void persistSuccessfulSubmission(FiscalDecl decl, Organization org,
      AEAT303DeclarationData data, AEAT303SubmissionResult result) {
    String fileName = "justificante-303-" + safeFileToken(data.getFiscalYear()) + "-"
        + safeFileToken(data.getPeriod()) + ".pdf";
    try {
      decl.setDeclarationStatus(STATUS_SUBMITTED_ACK);
      decl.setDeclarationFileName(fileName);
      decl.setFileExternal(false);
      OBDal.getInstance().save(decl);
      OBDal.getInstance().commitAndClose();
    } catch (Exception e) {
      log.error("Could not update declaration " + decl.getId()
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
    String fileName = "TEST-justificante-303-" + safeFileToken(data.getFiscalYear()) + "-"
        + safeFileToken(data.getPeriod()) + ".pdf";
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
   */
  private void attachJustificante(FiscalDecl decl, Organization org, String fileName,
      byte[] pdfContent) {
    String tabId;
    try {
      String tableId = NeoAttachmentsHelper.resolveTableId(FiscalDecl.TABLE_NAME);
      tabId = NeoAttachmentsHelper.resolveTabId(tableId, null);
    } catch (Exception e) {
      log.warn("Could not resolve an AD_Tab for " + FiscalDecl.TABLE_NAME + " attachments", e);
      tabId = null;
    }
    if (tabId == null) {
      log.warn("No AD_Tab configured for " + FiscalDecl.TABLE_NAME + " attachments yet — the "
          + "AEAT 303 justificante PDF for declaration " + decl.getId() + " was not persisted as "
          + "an attachment (it is still returned inline in the API response).");
      return;
    }

    Path tempDir = null;
    try {
      tempDir = Files.createTempDirectory("aeat303go");
      Path pdfPath = tempDir.resolve(fileName);
      Files.write(pdfPath, pdfContent);
      NeoAttachmentsHelper.getAttachManager()
          .upload(new HashMap<>(), tabId, decl.getId(), org.getId(), pdfPath.toFile());
      OBDal.getInstance().commitAndClose();
    } catch (Exception e) {
      log.error("Could not attach the AEAT 303 justificante PDF to declaration " + decl.getId(), e);
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
      log.warn("Could not clean up temporary directory " + tempDir, e);
    }
  }

  static String safeFileToken(String value) {
    String token = StringUtils.defaultIfBlank(value, "NA");
    return token.replaceAll("[^A-Za-z0-9]", "_");
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
    response.setContentType(JSON_CT);
    response.setStatus(status);
    response.getWriter().write(body.toString());
  }

  JSONObject declarationDataJson(AEAT303DeclarationData data) throws Exception {
    JSONObject o = new JSONObject();
    o.put("nif", StringUtils.defaultString(data.getNif()));
    o.put("businessName", StringUtils.defaultString(data.getBusinessName()));
    o.put("fiscalYear", StringUtils.defaultString(data.getFiscalYear()));
    o.put(PERIOD_KEY, StringUtils.defaultString(data.getPeriod()));
    o.put("declarationType", StringUtils.defaultString(data.getDeclarationType()));
    o.put("resultAmount",
        data.getResultAmount() != null ? data.getResultAmount().toString() : JSONObject.NULL);
    o.put("iban", data.getIban() != null ? data.getIban() : JSONObject.NULL);
    return o;
  }

  /** Builds the response for a submission that reached the AEAT (successfully or not). */
  JSONObject buildSubmissionResultJson(AEAT303SubmissionResult result,
      AEAT303DeclarationData data) throws Exception {
    boolean testMode = result.isTestMode();
    boolean successful = result.isSuccessful();
    String status = successful ? (testMode ? "TEST_SUCCESS" : "SUCCESS") : "ERROR";

    JSONObject o = new JSONObject();
    o.put("status", status);
    o.put("testMode", testMode);
    o.put("csv", StringUtils.defaultString(result.getCsv()));
    o.put("presentationDate", StringUtils.defaultString(result.getPresentationDate()));
    o.put("registryNumber", StringUtils.defaultString(result.getRegistryNumber()));
    o.put("justificanteNumber", StringUtils.defaultString(result.getJustificanteNumber()));
    byte[] pdf = result.getPdfContent();
    o.put("pdfBase64", pdf != null ? Base64.getEncoder().encodeToString(pdf) : JSONObject.NULL);
    o.put("pdfDownloadFailed", result.isPdfDownloadFailed());
    o.put("errors", new JSONArray(result.getErrors()));
    o.put("warnings", new JSONArray(result.getWarnings()));
    o.put("declarationData", declarationDataJson(data));
    return o;
  }

  /** Builds the response for a submission that failed before (or without) reaching the AEAT. */
  JSONObject buildFailureJson(boolean testMode, String errorCode, String message)
      throws Exception {
    JSONObject o = new JSONObject();
    o.put("status", "ERROR");
    o.put("testMode", testMode);
    o.put("errorCode", errorCode);
    JSONArray errors = new JSONArray();
    errors.put(StringUtils.defaultString(message));
    o.put("errors", errors);
    o.put("warnings", new JSONArray());
    return o;
  }

  // ── Package-private helpers (tested directly) ─────────────────────────────

  /**
   * Maps a frontend AEAT letter code to the declaration type used by
   * {@code AEAT303_Utility.getCheckedInputParameter}. Accepted codes: C, I, V, U, G.
   * Anything else (null, empty, unknown alias) falls back to "N" (zero result).
   */
  static String resolveDeclType(String tipo) {
    if ("C".equals(tipo) || "I".equals(tipo) || "V".equals(tipo)
        || "U".equals(tipo) || "G".equals(tipo)) {
      return tipo;
    }
    return "N";
  }

  // ── Internal ─────────────────────────────────────────────────────

  static class ComputeResult {
    final Map<Integer, BigDecimal> boxes;
    final List<Map<String, Object>> sources;
    ComputeResult(Map<Integer, BigDecimal> boxes, List<Map<String, Object>> sources) {
      this.boxes = boxes;
      this.sources = sources;
    }
  }

  static class BoxGroupConfig {
    final String groupKey;
    final String paramKey;
    final String taxType;
    final String equivCharge;
    final String intracom;
    final int baseBox;
    final int taxBox;

    BoxGroupConfig(String groupKey, String paramKey, String taxType,
        String equivCharge, String intracom, int baseBox, int taxBox) {
      this.groupKey    = groupKey;
      this.paramKey    = paramKey;
      this.taxType     = taxType;
      this.equivCharge = equivCharge;
      this.intracom    = intracom;
      this.baseBox     = baseBox;
      this.taxBox      = taxBox;
    }
  }

  ComputeResult computeBoxes(String orgId, int year, String period) throws Exception {
    Organization org = OBDal.getInstance().get(Organization.class, orgId);

    boolean quarterly = period.startsWith("T");
    String valueKey = quarterly
        ? "AEAT303_Q_" + year
        : "AEAT303_M_" + year;
    TaxReport taxReport = resolveTaxReport(orgId, valueKey);

    AcctSchema acctSchema = resolveAcctSchema();

    List<Period> periods = resolvePeriods(orgId, year, period);
    if (periods.isEmpty()) {
      throw new OBException(
          "No periods found for org=" + orgId + " year=" + year + " period=" + period);
    }

    AEAT303CalculationsHelper helper =
        new AEAT303CalculationsHelper(org, periods, acctSchema, log);
    AEAT303Report2014Dao dao303 = new AEAT303Report2014Dao();

    Map<Integer, BigDecimal> b = new HashMap<>();
    // taxRateId → list of box numbers it contributes to
    Map<String, List<Integer>> rateToBoxes = new HashMap<>();

    boolean isNewForm = isOct2024OrLater(year, period);
    fillSalesBoxes(b, helper, dao303, taxReport, rateToBoxes, isNewForm);
    fillPurchaseBoxes(b, helper, dao303, taxReport, rateToBoxes);
    fillAdditionalInfoBoxes(b, helper, dao303, taxReport, rateToBoxes);

    computeSummaryBoxes(b);

    List<Map<String, Object>> sources = collectSources(org, periods, dao303, rateToBoxes);

    return new ComputeResult(b, sources);
  }

  /**
   * Returns true for form versions that introduced boxes 168-170 for 0.5%/0.26% RE
   * and reassigned boxes 16-18 to 1% RE (Oct 2024 onwards).
   */
  static boolean isOct2024OrLater(int year, String period) {
    if (year > 2024) return true;
    if (year < 2024) return false;
    if (period.startsWith("T")) {
      return Integer.parseInt(period.substring(1)) >= 4;
    }
    return Integer.parseInt(period) >= 10;
  }

  /**
   * Iterates C_INVOICETAX for all tracked tax rates and builds a per-invoice source row.
   * Groups multiple tax lines of the same invoice into one row.
   */
  private List<Map<String, Object>> collectSources(
      Organization org, List<Period> periods,
      AEAT303Report2014Dao dao303,
      Map<String, List<Integer>> rateToBoxes) {

    if (rateToBoxes.isEmpty()) return Collections.emptyList();

    List<TaxRate> allRates = buildRatesList(rateToBoxes);
    Map<String, Map<String, Object>> byInvoice = new LinkedHashMap<>();
    SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd");

    ScrollableResults sr = dao303.getInvoiceTax(
        org, allRates, periods, CashVATOperationType.ONLY_NONCASHVAT);
    try {
      while (sr.next()) {
        InvoiceTax it = (InvoiceTax) sr.get(0);
        Invoice inv   = it.getInvoice();
        Map<String, Object> row = byInvoice.computeIfAbsent(inv.getId(), k -> buildNewInvoiceRow(inv, sdf));
        accumulateInvoiceTax(row, it, rateToBoxes);
        OBDal.getInstance().getSession().evict(it);
        OBDal.getInstance().getSession().evict(inv);
      }
    } finally {
      sr.close();
    }

    List<Map<String, Object>> result = new ArrayList<>(byInvoice.values());
    result.forEach(this::finalizeInvoiceRow);
    return result;
  }

  private List<TaxRate> buildRatesList(Map<String, List<Integer>> rateToBoxes) {
    List<TaxRate> allRates = new ArrayList<>();
    for (String id : rateToBoxes.keySet()) {
      TaxRate tr = OBDal.getInstance().get(TaxRate.class, id);
      if (tr != null) allRates.add(tr);
    }
    return allRates;
  }

  private Map<String, Object> buildNewInvoiceRow(Invoice inv, SimpleDateFormat sdf) {
    Map<String, Object> r = new LinkedHashMap<>();
    r.put("ref",   inv.getDocumentNo());
    r.put("date",  sdf.format(inv.getInvoiceDate()));
    String cat = inv.getDocumentType().getDocumentCategory();
    r.put("type",  "ARI".equals(cat) || "ARI_RM".equals(cat) ? "Venta" : "Compra");
    r.put("party", inv.getBusinessPartner() != null ? inv.getBusinessPartner().getName() : "");
    r.put("base",  BigDecimal.ZERO);
    r.put("vat",   BigDecimal.ZERO);
    r.put(BOXES,   new java.util.LinkedHashSet<Integer>());
    return r;
  }

  private void accumulateInvoiceTax(Map<String, Object> row, InvoiceTax it,
      Map<String, List<Integer>> rateToBoxes) {
    BigDecimal base = it.getTaxableAmount() != null ? it.getTaxableAmount().abs() : BigDecimal.ZERO;
    BigDecimal tax  = it.getTaxAmount()     != null ? it.getTaxAmount().abs()     : BigDecimal.ZERO;
    row.put("base", round(((BigDecimal) row.get("base")).add(base)));
    row.put("vat",  round(((BigDecimal) row.get("vat")).add(tax)));
    List<Integer> boxes = rateToBoxes.get(it.getTax().getId());
    if (boxes != null) {
      @SuppressWarnings("unchecked")
      java.util.LinkedHashSet<Integer> bSet = (java.util.LinkedHashSet<Integer>) row.get(BOXES);
      bSet.addAll(boxes);
    }
  }

  void finalizeInvoiceRow(Map<String, Object> row) {
    @SuppressWarnings("unchecked")
    java.util.LinkedHashSet<Integer> bSet = (java.util.LinkedHashSet<Integer>) row.get(BOXES);
    List<Integer> sorted = new ArrayList<>(bSet);
    Collections.sort(sorted);
    StringBuilder sb = new StringBuilder();
    for (Integer bx : sorted) {
      if (sb.length() > 0) {
        sb.append(",");
      }
      sb.append(bx);
    }
    row.put(BOXES, sb.toString());
    BigDecimal base = (BigDecimal) row.get("base");
    BigDecimal vat  = (BigDecimal) row.get("vat");
    row.put("total", round(base.add(vat)));
  }

  // Package-private for unit testing — injects pre-built helper and dao,
  // skips DB lookups and source collection.
  @SuppressWarnings("java:S1172")
  ComputeResult computeBoxes(Organization org, TaxReport taxReport,
      List<Period> periods, AEAT303CalculationsHelper helper,
      AEAT303Report2014Dao dao303) {
    Map<Integer, BigDecimal> b = new HashMap<>();
    Map<String, List<Integer>> rateToBoxes = new HashMap<>();

    fillSalesBoxes(b, helper, dao303, taxReport, rateToBoxes, false);
    fillPurchaseBoxes(b, helper, dao303, taxReport, rateToBoxes);
    fillAdditionalInfoBoxes(b, helper, dao303, taxReport, rateToBoxes);
    computeSummaryBoxes(b);

    return new ComputeResult(b, Collections.emptyList());
  }

  private void fillSalesBoxes(Map<Integer, BigDecimal> b, AEAT303CalculationsHelper helper,
      AEAT303Report2014Dao dao303, TaxReport taxReport, Map<String, List<Integer>> rateToBoxes,
      boolean isNewForm) {

    // VAT_SALES_GENERAL — split by rate % → boxes 7/9 (21%), 4/6 (10%/7%/8%), 1/3 (4%/5%),
    // 150/152 (0%), 165/167 (2%)
    TaxReportParameter paramGeneral =
        dao303.getTaxReportParameter(taxReport, VAT_SALES, "VAT_SALES_GENERAL");
    if (paramGeneral != null) {
      List<TaxRate> salesGeneral =
          dao303.get303Taxes(taxReport.getId(), "All", "All", "All", paramGeneral);
      applyPercentageSplit(b, helper, salesGeneral, this::vatGeneralBoxes, rateToBoxes);
    }

    // VAT_SALES_EU → boxes 10, 11 (adq. intracomunitarias — buyer self-assesses)
    fillGroupBoxes(b, helper, dao303, taxReport,
        new BoxGroupConfig(VAT_SALES, "VAT_SALES_EU", PURCHASE, "No", "Yes", 10, 11), rateToBoxes);

    // VAT_SALES_ISP → boxes 12, 13 (inversión sujeto pasivo)
    fillGroupBoxes(b, helper, dao303, taxReport,
        new BoxGroupConfig(VAT_SALES, "VAT_SALES_ISP", PURCHASE, "No", "No", 12, 13), rateToBoxes);

    // VAT_SALES_EC (recargo equivalencia) — split by %.
    // Box assignment depends on form version: Oct 2024+ reassigned 0.5%/0.26% to 168/170
    // and introduced 1% in boxes 16/18.
    // Pre-2024: 0%, 0.50%, 0.62% all go to 16/18; box 17 = dominant rate by largest base.
    TaxReportParameter paramEC =
        dao303.getTaxReportParameter(taxReport, VAT_SALES, "VAT_SALES_EC");
    if (paramEC != null) {
      List<TaxRate> ecTaxes =
          dao303.get303Taxes(taxReport.getId(), "All", "All", "All", paramEC);
      applyPercentageSplit(b, helper, ecTaxes, pct -> vatEcBoxes(pct, isNewForm), rateToBoxes);
      if (!isNewForm && b.containsKey(16)) {
        BigDecimal dominantRate = computePreOct2024EcDominantRate(helper, ecTaxes);
        if (dominantRate != null) b.put(17, dominantRate);
      }
    }
  }

  List<Integer> vatGeneralBoxes(BigDecimal pct) {
    if (pct == null) return Collections.emptyList();
    if (pct.compareTo(PCT_21) == 0) return java.util.Arrays.asList(7, 9);
    if (pct.compareTo(PCT_10) == 0
        || pct.compareTo(PCT_7)  == 0
        || pct.compareTo(PCT_8)  == 0) return java.util.Arrays.asList(4, 6);
    if (pct.compareTo(PCT_4) == 0
        || pct.compareTo(PCT_5) == 0) return java.util.Arrays.asList(1, 3);
    if (pct.compareTo(BigDecimal.ZERO) == 0) return java.util.Arrays.asList(150, 152);
    if (pct.compareTo(PCT_2) == 0) return java.util.Arrays.asList(165, 167);
    return Collections.emptyList();
  }

  /**
   * Maps a VAT_SALES_EC percentage to the correct box pair.
   * From Oct 2024 (isNewForm=true), the AEAT 303 form redesigned the RE section:
   *   0.26% / 0.50% → boxes 168/170 (new row introduced Oct 2024)
   *   1.00%         → boxes 16/18  (previously held 0.50% in older forms)
   * Pre-Oct 2024 (isNewForm=false):
   *   0% / 0.50% / 0.62% → boxes 16/18 (box 17 = dominant rate, computed separately)
   */
  List<Integer> vatEcBoxes(BigDecimal pct, boolean isNewForm) {
    if (pct == null) return Collections.emptyList();
    if (pct.compareTo(PCT_1_40) == 0) return java.util.Arrays.asList(19, 21);
    if (pct.compareTo(PCT_5_20) == 0) return java.util.Arrays.asList(22, 24);
    if (pct.compareTo(PCT_1_75) == 0) return java.util.Arrays.asList(156, 158);
    if (isNewForm) {
      if (pct.compareTo(PCT_0_50) == 0 || pct.compareTo(PCT_0_26) == 0)
        return java.util.Arrays.asList(168, 170);
      if (pct.compareTo(PCT_1_00) == 0) return java.util.Arrays.asList(16, 18);
    } else {
      if (pct.compareTo(PCT_0_50) == 0 || pct.compareTo(PCT_0_62) == 0
          || pct.compareTo(BigDecimal.ZERO) == 0) return java.util.Arrays.asList(16, 18);
    }
    return Collections.emptyList();
  }

  /**
   * For pre-Oct 2024 forms: picks the dominant RE rate (0%, 0.50%, 0.62%) for box 17 display,
   * defined as the rate with the largest base imponible — matching Classic AEAT303Report2023 logic.
   * Returns null if no EC activity found.
   */
  BigDecimal computePreOct2024EcDominantRate(AEAT303CalculationsHelper helper,
      List<TaxRate> ecTaxes) {
    BigDecimal[] candidates = { BigDecimal.ZERO, PCT_0_50, PCT_0_62 };
    Map<BigDecimal, List<TaxRate>> split = splitByPercentage(ecTaxes);
    BigDecimal dominantRate = null;
    BigDecimal maxBase = BigDecimal.ZERO;
    for (BigDecimal rate : candidates) {
      BigDecimal normRate = rate.setScale(2, java.math.RoundingMode.HALF_UP);
      List<TaxRate> group = split.get(normRate);
      if (group == null || group.isEmpty()) continue;
      Map<String, BigDecimal> amounts = helper.calculateAmountsMap(group, InvoiceType.ALL);
      BigDecimal base = amounts.getOrDefault(TAX_BASE_AMOUNT, BigDecimal.ZERO).abs();
      if (base.compareTo(maxBase) > 0) {
        maxBase = base;
        dominantRate = normRate;
      }
    }
    return dominantRate;
  }

  private void applyPercentageSplit(Map<Integer, BigDecimal> b, AEAT303CalculationsHelper helper,
      List<TaxRate> rates, Function<BigDecimal, List<Integer>> boxMapper,
      Map<String, List<Integer>> rateToBoxes) {
    for (Map.Entry<BigDecimal, List<TaxRate>> e : splitByPercentage(rates).entrySet()) {
      BigDecimal pct = e.getKey();
      Map<String, BigDecimal> r = helper.calculateAmountsMap(e.getValue(), InvoiceType.ALL);
      List<Integer> boxes = boxMapper.apply(pct);
      if (!boxes.isEmpty()) {
        addToBox(b, boxes.get(0), r.get(TAX_BASE_AMOUNT));
        addToBox(b, boxes.get(1), r.get(TAX_AMOUNT));
        for (TaxRate tr : e.getValue()) rateToBoxes.put(tr.getId(), boxes);
      }
    }
  }

  private void fillPurchaseBoxes(Map<Integer, BigDecimal> b, AEAT303CalculationsHelper helper,
      AEAT303Report2014Dao dao303, TaxReport taxReport, Map<String, List<Integer>> rateToBoxes) {
    fillGroupBoxes(b, helper, dao303, taxReport,
        new BoxGroupConfig(VAT_PURCHASE, "Normal_Operations",         PURCHASE, "No", "No",  28, 29), rateToBoxes);
    fillGroupBoxes(b, helper, dao303, taxReport,
        new BoxGroupConfig(VAT_PURCHASE, "Investment_Goods",           PURCHASE, "No", "No",  30, 31), rateToBoxes);
    fillGroupBoxes(b, helper, dao303, taxReport,
        new BoxGroupConfig(VAT_PURCHASE, "Import_Goods",               PURCHASE, "No", "No",  32, 33), rateToBoxes);
    fillGroupBoxes(b, helper, dao303, taxReport,
        new BoxGroupConfig(VAT_PURCHASE, "Import_Investment_Goods",    PURCHASE, "No", "No",  34, 35), rateToBoxes);
    fillGroupBoxes(b, helper, dao303, taxReport,
        new BoxGroupConfig(VAT_PURCHASE, "Intracommunity_Goods",       PURCHASE, "No", "Yes", 36, 37), rateToBoxes);
    fillGroupBoxes(b, helper, dao303, taxReport,
        new BoxGroupConfig(VAT_PURCHASE, "Intracommunity_Investments", PURCHASE, "No", "Yes", 38, 39), rateToBoxes);
  }

  // taxBox == 0 means base-only (no corresponding tax amount box, e.g. 0% exempt rows)
  private void fillGroupBoxes(Map<Integer, BigDecimal> b, AEAT303CalculationsHelper helper,
      AEAT303Report2014Dao dao303, TaxReport taxReport,
      BoxGroupConfig cfg, Map<String, List<Integer>> rateToBoxes) {
    TaxReportParameter param = dao303.getTaxReportParameter(taxReport, cfg.groupKey, cfg.paramKey);
    if (param == null) return;
    List<TaxRate> rates =
        dao303.get303Taxes(taxReport.getId(), cfg.taxType, cfg.equivCharge, cfg.intracom, param);
    if (rates.isEmpty()) return;
    Map<String, BigDecimal> result = helper.calculateAmountsMap(rates, InvoiceType.ALL);
    addToBox(b, cfg.baseBox, result.get(TAX_BASE_AMOUNT));
    if (cfg.taxBox > 0) addToBox(b, cfg.taxBox, result.get(TAX_AMOUNT));
    List<Integer> boxes = cfg.taxBox > 0
        ? java.util.Arrays.asList(cfg.baseBox, cfg.taxBox)
        : java.util.Arrays.asList(cfg.baseBox);
    for (TaxRate tr : rates) rateToBoxes.put(tr.getId(), boxes);
  }

  private void fillAdditionalInfoBoxes(Map<Integer, BigDecimal> b, AEAT303CalculationsHelper helper,
      AEAT303Report2014Dao dao303, TaxReport taxReport, Map<String, List<Integer>> rateToBoxes) {
    // "Additional_Information" group only exists in monthly reports; quarterly reports use "Difference".
    // "Difference" is present in all reports and carries the same tax rates, so use it universally.
    // Box 59: intra-community deliveries (entregas intracomunitarias exentas)
    fillGroupBoxes(b, helper, dao303, taxReport,
        new BoxGroupConfig("Difference", "IntracommunitySales", "All", "All", "All", 59, 0), rateToBoxes);
    // Box 60: exports and other exempt operations with deduction right
    fillGroupBoxes(b, helper, dao303, taxReport,
        new BoxGroupConfig("Difference", "ExportsAndOperations", "All", "All", "All", 60, 0), rateToBoxes);
  }

  // resultado_final — standard company (100 % Estado, no pending credits, no complementary)
  private void computeSummaryBoxes(Map<Integer, BigDecimal> b) {
    int[] accruedBoxes    = { 3, 6, 9, 11, 13, 15, 18, 21, 24, 152, 158, 167, 170 };
    int[] deductibleBoxes = { 29, 31, 33, 35, 37, 39, 41, 42, 43, 44 };
    BigDecimal accrued    = sumBoxes(b, accruedBoxes);
    BigDecimal deductible = sumBoxes(b, deductibleBoxes);
    b.put(27, round(accrued));
    b.put(45, round(deductible));
    b.put(46, round(accrued.subtract(deductible)));

    BigDecimal r46 = b.getOrDefault(46, BigDecimal.ZERO);
    b.put(66, r46);   // amount attributable to Estado (box 65 % × box 46)
    b.put(69, r46);   // result before final adjustments (assumes boxes 64/76/77/78/68/108 = 0)
    b.put(71, r46);   // final declaration result (assumes boxes 70/109 = 0)

    BigDecimal r59 = b.getOrDefault(59, BigDecimal.ZERO);
    BigDecimal r60 = b.getOrDefault(60, BigDecimal.ZERO);
    if (r59.compareTo(BigDecimal.ZERO) > 0) b.put(93, r59);
    if (r60.compareTo(BigDecimal.ZERO) > 0) b.put(94, r60);
  }

  // ── Resolution helpers ───────────────────────────────────────────

  TaxReport resolveTaxReport(String orgId, String valueKey) {
    OBCriteria<TaxReport> crit = OBDal.getInstance().createCriteria(TaxReport.class);
    crit.add(Restrictions.in(TaxReport.PROPERTY_ORGANIZATION + ".id", Arrays.asList(orgId, "0")));
    crit.add(Restrictions.eq(TaxReport.PROPERTY_SEARCHKEY, valueKey));
    crit.addOrder(Order.desc(TaxReport.PROPERTY_ORGANIZATION + ".id"));
    crit.setMaxResults(1);
    List<TaxReport> list = crit.list();
    if (list.isEmpty()) {
      throw new OBException(
          "No TaxReport found for org=" + orgId + " searchKey=" + valueKey);
    }
    return list.get(0);
  }

  // ── Utility ──────────────────────────────────────────────────────

  private void addToBox(Map<Integer, BigDecimal> b, int box, BigDecimal val) {
    if (val == null || val.compareTo(BigDecimal.ZERO) == 0) return;
    b.merge(box, val, BigDecimal::add);
  }

  private BigDecimal sumBoxes(Map<Integer, BigDecimal> b, int[] boxes) {
    BigDecimal sum = BigDecimal.ZERO;
    for (int box : boxes) sum = sum.add(b.getOrDefault(box, BigDecimal.ZERO));
    return sum;
  }

  private BigDecimal round(BigDecimal v) {
    return v.setScale(2, java.math.RoundingMode.HALF_UP);
  }

  private Map<BigDecimal, List<TaxRate>> splitByPercentage(List<TaxRate> rates) {
    Map<BigDecimal, List<TaxRate>> map = new java.util.LinkedHashMap<>();
    for (TaxRate r : rates) {
      BigDecimal pct = r.getRate().abs().setScale(2, java.math.RoundingMode.HALF_UP);
      map.computeIfAbsent(pct, k -> new ArrayList<>()).add(r);
    }
    return map;
  }

  private JSONObject buildResponse(Map<Integer, BigDecimal> b,
      List<Map<String, Object>> sources) throws Exception {
    JSONObject boxes = new JSONObject();
    for (Map.Entry<Integer, BigDecimal> e : b.entrySet()) {
      boxes.put(String.valueOf(e.getKey()), e.getValue().toString());
    }
    BigDecimal accrued    = b.getOrDefault(27, BigDecimal.ZERO);
    BigDecimal deductible = b.getOrDefault(45, BigDecimal.ZERO);
    BigDecimal result     = b.getOrDefault(46, BigDecimal.ZERO);
    JSONObject summary = new JSONObject();
    summary.put("accrued",    accrued.toString());
    summary.put("deductible", deductible.toString());
    summary.put("result",     result.toString());
    JSONArray sourcesArr = new JSONArray();
    for (Map<String, Object> row : sources) {
      JSONObject s = new JSONObject();
      for (Map.Entry<String, Object> e : row.entrySet()) {
        Object v = e.getValue();
        if (v instanceof BigDecimal) {
          s.put(e.getKey(), v.toString());
        } else {
          s.put(e.getKey(), v != null ? v.toString() : "");
        }
      }
      sourcesArr.put(s);
    }
    JSONObject root = new JSONObject();
    root.put(BOXES,     boxes);
    root.put("summary", summary);
    root.put("sources", sourcesArr);
    return root;
  }
}
