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

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Map;
import java.util.TimeZone;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.base.exception.OBException;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.dal.service.OBQuery;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.module.aeat303.es.presentation.AEAT303SubmissionRequestBuilder;
import org.openbravo.module.aeat303.es.presentation.AEAT303SubmissionResult;
import org.openbravo.module.aeat303.es.presentation.AEAT303SubmissionService;

import com.etendoerp.go.schemaforge.data.FiscalDecl;
import com.etendoerp.go.schemaforge.util.NeoMessageTranslator;

/**
 * {@code POST /fiscal390/submit?year=&period=0A&id=<declId>} (ETP-5716): the AEAT telematic
 * filing of a Modelo 390, on behalf of {@link Fiscal390BoxesHandler}.
 *
 * <p>The AEAT protocol is the classic one, reused as-is: {@link AEAT303SubmissionService} speaks
 * the generic direct-presentation services (PresBasicaDos / ServValiDos) and only the
 * {@code MODELO}/{@code PERIODO} change per model — this class asks for
 * {@link AEAT303SubmissionRequestBuilder#MODELO_390}, period {@code 0A} and never an NRC (a 390
 * is informative: the payment was settled by the last 303). Production calls are routed to the
 * AEAT test environment when the client has {@code ETSG_ForceTestMode = Y}, exactly like 303.
 *
 * <p>Flow (production):
 * <ol>
 *   <li>guards: declaration exists, belongs to the session org, is a 390 of that year; not
 *       already submitted ({@code ALREADY_SUBMITTED}); no blocking incident
 *       ({@code BLOCKING_INCIDENTS}); presenter NIF/name present ({@code MISSING_PRESENTER});
 *       the organization has a certificate ({@code NO_CERTIFICATE});</li>
 *   <li>the file is regenerated server-side from the declaration's STORED inputs
 *       ({@code manualData.aeatInputs}) — never from the request, so the file Hacienda receives
 *       is the one the user saved, and the same inputs freeze the snapshot;</li>
 *   <li>the submission snapshot is computed and validated BEFORE the AEAT call
 *       ({@code SNAPSHOT_FAILED}: nothing is filed when the figures cannot be frozen);</li>
 *   <li>on an accepted filing: status {@code submitted_ack}, {@code submissionMethod
 *       aeat_telematic}, the snapshot, {@code manualData.submission} (date, justificante, CSV,
 *       registry number, user) and the justificante PDF attached — one commit;</li>
 *   <li>on an AEAT error: nothing changes — the declaration stays a draft, and the AEAT errors
 *       come back in the response.</li>
 * </ol>
 *
 * <p>Unlike 303, the AEAT errors are NOT copied into the declaration incidents: the 390
 * incidents are the model's own blocking/warning rules, and a blocking AEAT error stored there
 * would block the very retry that fixes it. Test mode (ServValiDos) never touches the
 * declaration; a returned draft PDF is attached with a {@code TEST-} prefix.
 */
class Fiscal390SubmissionSupport {

  static final String SUBMIT = "submit";
  /** Key, inside the declaration's {@code manualData}, of the filing data. */
  static final String SUBMISSION_KEY = "submission";
  static final String SUBMISSION_METHOD_AEAT_TELEMATIC =
      FiscalDeclCrudHandler.SUBMISSION_METHOD_AEAT_TELEMATIC;
  private static final String STATUS_SUBMITTED_ACK = "submitted_ack";
  private static final String ID_PARAM = "id";

  static final String ERR_NO_CERTIFICATE = "NO_CERTIFICATE";
  static final String ERR_MISSING_PRESENTER = "MISSING_PRESENTER";
  static final String ERR_SUBMISSION_FAILED = "SUBMISSION_FAILED";
  static final String ERR_ALREADY_SUBMITTED = "ALREADY_SUBMITTED";
  static final String ERR_SNAPSHOT_FAILED = "SNAPSHOT_FAILED";
  static final String ERR_BLOCKING_INCIDENTS = "BLOCKING_INCIDENTS";

  private final Fiscal390BoxesHandler owner;

  Fiscal390SubmissionSupport(Fiscal390BoxesHandler owner) {
    this.owner = owner;
  }

  void handleSubmit(String orgId, int year, String period, HttpServletRequest request,
      HttpServletResponse response) throws Exception {
    String declId = request.getParameter(ID_PARAM);
    if (StringUtils.isBlank(declId)) {
      owner.servlet.sendError(response, HttpServletResponse.SC_BAD_REQUEST,
          "Missing required param: id");
      return;
    }
    FiscalDecl decl = OBDal.getInstance().get(FiscalDecl.class, declId);
    if (!isOwned390(decl, year)) {
      owner.servlet.sendError(response, HttpServletResponse.SC_NOT_FOUND,
          "Declaration not found: " + declId);
      return;
    }

    JSONObject body = FiscalAeatSubmissionSupport.readJsonBody(request);
    boolean testMode = body.optBoolean(FiscalAeatSubmissionSupport.PARAM_TEST_MODE, false);
    String idi = StringUtils.defaultIfBlank(body.optString("idi", null), "ES");
    String presenterNif = StringUtils.trimToEmpty(body.optString("presenterNif", ""));
    String presenterName = StringUtils.trimToEmpty(body.optString("presenterName", ""));

    if (rejectProductionPreconditions(decl, testMode, presenterNif, presenterName, response)) {
      return;
    }

    Organization org = OBDal.getInstance().get(Organization.class, orgId);
    AEAT303SubmissionService service = newSubmissionService();
    if (!testMode && !service.hasOrgCertificate(org)) {
      FiscalAeatSubmissionSupport.writeJson(response, HttpServletResponse.SC_CONFLICT,
          FiscalAeatSubmissionSupport.failureJson(false, ERR_NO_CERTIFICATE,
              "No certificate configured for this organization. Upload one via Fiscal "
                  + "Configuration before submitting in production mode."));
      return;
    }

    Map<String, String> inputs = Fiscal390BoxesHandler.readStoredInputs(decl.getManualData());
    String fileContent;
    try {
      fileContent = owner.generateSupport().generateContent(orgId, year, inputs).content;
    } catch (Exception e) {
      AbstractFiscalHandler.log.error(
          "Could not generate the 390 electronic file for submission (decl=" + declId + ")", e);
      FiscalAeatSubmissionSupport.writeJson(response,
          HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
          FiscalAeatSubmissionSupport.failureJson(testMode, ERR_SUBMISSION_FAILED,
              translatedMessage(e)));
      return;
    }

    JSONObject snapshot = null;
    if (!testMode) {
      try {
        snapshot = owner.snapshotSupport.toSnapshot(owner.computePayload(orgId, year, inputs));
        FiscalSubmittedSnapshotSupport.validateSubmittedSnapshot(decl, snapshot.toString());
      } catch (Exception e) {
        AbstractFiscalHandler.log.error("Could not compute the submission snapshot for decl="
            + declId + "; the declaration was not sent to the AEAT", e);
        FiscalAeatSubmissionSupport.writeJson(response,
            HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
            FiscalAeatSubmissionSupport.failureJson(false, ERR_SNAPSHOT_FAILED,
                "The declaration was not submitted: its figures could not be computed ("
                    + translatedMessage(e) + ")."));
        return;
      }
    }

    String fiscalYear = String.valueOf(year);
    AEAT303SubmissionResult result;
    try {
      if (testMode) {
        result = service.submitValidation(AEAT303SubmissionRequestBuilder.MODELO_390,
            fileContent, fiscalYear, period, idi);
      } else {
        result = service.submitProduction(
            AEAT303SubmissionService.ProductionSubmissionRequest.builder()
                .modelo(AEAT303SubmissionRequestBuilder.MODELO_390)
                .org(org)
                .fileContent(fileContent)
                .fiscalYear(fiscalYear)
                .period(period)
                .presenterNif(presenterNif)
                .presenterName(presenterName)
                .nrc("")
                .language(idi)
                .build());
      }
    } catch (OBException e) {
      AbstractFiscalHandler.log.error("AEAT 390 submission failed (decl=" + declId + ")", e);
      FiscalAeatSubmissionSupport.writeJson(response, HttpServletResponse.SC_BAD_GATEWAY,
          FiscalAeatSubmissionSupport.failureJson(testMode, ERR_SUBMISSION_FAILED,
              translatedMessage(e)));
      return;
    }

    if (result.isSuccessful()) {
      if (testMode) {
        attachIfPresent(decl, org, "TEST-" + justificanteFileName(year, period), result);
      } else {
        persistSuccessfulSubmission(decl, org, year, period, result, snapshot);
      }
      commitBestEffort(declId);
    }

    FiscalAeatSubmissionSupport.writeJson(response, HttpServletResponse.SC_OK,
        FiscalAeatSubmissionSupport.resultJson(result,
            declarationDataJson(decl, year, period, snapshot)));
  }

  /** Overridable in tests: the classic AEAT service. */
  AEAT303SubmissionService newSubmissionService() {
    return new AEAT303SubmissionService();
  }

  /**
   * The declaration exists, belongs to the current client and to the org declarations are
   * stored under ({@link AbstractFiscalHandler#declarationOrgId}), is a Modelo 390 and is of the
   * requested exercise.
   */
  boolean isOwned390(FiscalDecl decl, int year) {
    if (decl == null || decl.getClient() == null || decl.getOrganization() == null) {
      return false;
    }
    String clientId = OBContext.getOBContext().getCurrentClient().getId();
    return clientId.equals(decl.getClient().getId())
        && owner.declarationOrgId().equals(decl.getOrganization().getId())
        && Fiscal390BoxesHandler.MODEL.equals(decl.getFiscalModel())
        && decl.getFiscalYear() != null && decl.getFiscalYear().intValue() == year;
  }

  /**
   * The production-only guards that need no organization or AEAT service: already submitted,
   * blocking incidents, presenter identification. Writes the error and returns {@code true} when
   * the request was rejected. Test mode never changes the declaration, so it trips none.
   */
  private boolean rejectProductionPreconditions(FiscalDecl decl, boolean testMode,
      String presenterNif, String presenterName, HttpServletResponse response) throws Exception {
    if (testMode) {
      return false;
    }
    String status = decl.getDeclarationStatus();
    if (status != null && FiscalDeclCrudHandler.SUBMITTED_STATUSES.contains(status)) {
      FiscalAeatSubmissionSupport.writeJson(response, HttpServletResponse.SC_CONFLICT,
          FiscalAeatSubmissionSupport.failureJson(false, ERR_ALREADY_SUBMITTED,
              "This declaration was already submitted (status: " + status + "). To correct it,"
                  + " create a substitute declaration."));
      return true;
    }
    if (countBlockingIncidents(decl.getId()) > 0) {
      FiscalAeatSubmissionSupport.writeJson(response, HttpServletResponse.SC_CONFLICT,
          FiscalAeatSubmissionSupport.failureJson(false, ERR_BLOCKING_INCIDENTS,
              "The declaration has blocking incidents: resolve them before submitting."));
      return true;
    }
    if (StringUtils.isBlank(presenterNif) || StringUtils.isBlank(presenterName)) {
      FiscalAeatSubmissionSupport.writeJson(response, HttpServletResponse.SC_BAD_REQUEST,
          FiscalAeatSubmissionSupport.failureJson(false, ERR_MISSING_PRESENTER,
              "presenterNif and presenterName are required for a production submission"));
      return true;
    }
    return false;
  }

  /**
   * Blocking incidents of the declaration — the same rows (and the same "a blank severity is
   * blocking" default) the Incidencias tab counts.
   */
  long countBlockingIncidents(String declId) {
    OBQuery<BaseOBObject> query = OBDal.getInstance().createQuery(
        FiscalDeclCrudHandler.ENTITY_FISCAL_DECL_INCIDENT,
        FiscalDeclCrudHandler.PROPERTY_INCIDENT_DECL + ".id = :declId and (coalesce("
            + FiscalDeclCrudHandler.PROPERTY_INCIDENT_SEVERITY + ", '') = '' or "
            + FiscalDeclCrudHandler.PROPERTY_INCIDENT_SEVERITY + " = :block)");
    query.setNamedParameter("declId", declId);
    query.setNamedParameter("block", FiscalDeclCrudHandler.SEVERITY_BLOCK);
    return query.count();
  }

  /**
   * Stages (does not commit) the outcome of an accepted production filing — status, method,
   * file name, snapshot, {@code manualData.submission} — and attaches the justificante PDF.
   */
  void persistSuccessfulSubmission(FiscalDecl decl, Organization org, int year, String period,
      AEAT303SubmissionResult result, JSONObject snapshot) {
    String fileName = justificanteFileName(year, period);
    try {
      decl.setDeclarationStatus(STATUS_SUBMITTED_ACK);
      decl.setDeclarationFileName(fileName);
      decl.setFileExternal(false);
      decl.setSubmissionMethod(SUBMISSION_METHOD_AEAT_TELEMATIC);
      decl.setManualData(withSubmission(decl.getManualData(), result, currentUserName(), new Date())
          .toString());
      applySnapshot(decl, snapshot);
      OBDal.getInstance().save(decl);
    } catch (Exception e) {
      AbstractFiscalHandler.log.error("Could not update declaration " + decl.getId()
          + " after a successful AEAT 390 submission", e);
    }
    attachIfPresent(decl, org, fileName, result);
  }

  /** Mirrors 303: a filed declaration whose snapshot cannot be stored keeps its status. */
  private void applySnapshot(FiscalDecl decl, JSONObject snapshot) {
    if (snapshot == null) {
      return;
    }
    try {
      decl.setSubmittedSnapshot(snapshot.toString());
    } catch (Exception e) {
      AbstractFiscalHandler.log.error(Fiscal303SubmissionSupport.SNAPSHOT_MISSING_MARKER + " decl="
          + decl.getId() + " model=390 — filed with the AEAT but the submission snapshot could"
          + " not be stored; it will be served live", e);
    }
  }

  /**
   * {@code manualData} with {@code submission} set to the filing data. A malformed stored blob
   * is replaced (the filing data must never be lost because of it).
   */
  static JSONObject withSubmission(String manualData, AEAT303SubmissionResult result,
      String userName, Date submittedAt) throws Exception {
    JSONObject root;
    try {
      root = StringUtils.isNotBlank(manualData) ? new JSONObject(manualData) : new JSONObject();
    } catch (Exception e) {
      AbstractFiscalHandler.log.warn("Replacing unparseable Modelo 390 manualData on submission: "
          + e.getMessage());
      root = new JSONObject();
    }
    JSONObject submission = new JSONObject();
    submission.put("method", SUBMISSION_METHOD_AEAT_TELEMATIC);
    submission.put("presentationDate", StringUtils.defaultString(result.getPresentationDate()));
    submission.put("justificante", StringUtils.defaultString(result.getJustificanteNumber()));
    submission.put("csv", StringUtils.defaultString(result.getCsv()));
    submission.put("registryNumber", StringUtils.defaultString(result.getRegistryNumber()));
    submission.put("user", StringUtils.defaultString(userName));
    submission.put("submittedAt", isoUtc(submittedAt));
    root.put(SUBMISSION_KEY, submission);
    return root;
  }

  static String isoUtc(Date date) {
    SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");
    f.setTimeZone(TimeZone.getTimeZone("UTC"));
    return f.format(date);
  }

  static String justificanteFileName(int year, String period) {
    return "justificante-390-" + year + "-" + Fiscal303BoxesHandler.safeFileToken(period) + ".pdf";
  }

  /** The {@code declarationData} of the response — what the result screen shows. */
  static JSONObject declarationDataJson(FiscalDecl decl, int year, String period,
      JSONObject snapshot) throws Exception {
    JSONObject o = new JSONObject();
    o.put("fiscalYear", String.valueOf(year));
    o.put(AbstractFiscalHandler.PERIOD_KEY, StringUtils.defaultString(period));
    o.put("declarationType", StringUtils.defaultString(decl.getDeclarationType()).trim());
    JSONObject summary = snapshot != null ? snapshot.optJSONObject("summary") : null;
    o.put("resultAmount", summary != null && summary.has("annualResult")
        ? summary.getString("annualResult") : JSONObject.NULL);
    return o;
  }

  private void attachIfPresent(FiscalDecl decl, Organization org, String fileName,
      AEAT303SubmissionResult result) {
    if (result.getPdfContent() != null) {
      FiscalAeatSubmissionSupport.attachPdf(decl, org, fileName, result.getPdfContent(),
          "aeat390go", Fiscal390BoxesHandler.MODEL);
    }
  }

  private void commitBestEffort(String declId) {
    try {
      OBDal.getInstance().commitAndClose();
    } catch (Exception e) {
      AbstractFiscalHandler.log.error(
          "Could not commit AEAT 390 submission persistence for declaration " + declId, e);
    }
  }

  private static String currentUserName() {
    try {
      return OBContext.getOBContext().getUser().getName();
    } catch (Exception e) {
      return "";
    }
  }

  private static String translatedMessage(Exception e) {
    return StringUtils.defaultIfBlank(NeoMessageTranslator.safeParseTranslation(e.getMessage()),
        e.getClass().getSimpleName());
  }
}
