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

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.module.aeat303.es.presentation.AEAT303SubmissionRequestBuilder;
import org.openbravo.module.aeat303.es.presentation.AEAT303SubmissionService;

import com.etendoerp.go.schemaforge.data.FiscalDecl;

/**
 * {@code POST /fiscal390/generate-validated?year=&period=0A&id=<declId>} (ETP-5716): the Modelo
 * 390 file of "Generar fichero 390", checked by the AEAT validation service (ServValiDos — the
 * one the test mode of the direct presentation uses; no organization certificate) before the
 * user downloads it.
 *
 * <p><b>The validation never blocks the download.</b> The file is generated first and is always
 * in the answer; the validation only adds its {@link FiscalAeatValidationOutcome}:
 * <ul>
 *   <li>{@code accepted} — the AEAT found no error;</li>
 *   <li>{@code rejected} — the AEAT errors, each with its code;</li>
 *   <li>{@code connection_failure} / {@code not_validated} — no usable answer (timeout, refused
 *       connection, an answer that is not the validator's): the file was not validated.</li>
 * </ul>
 * The wait is bounded ({@link #CONNECT_TIMEOUT_MILLIS} + {@link #READ_TIMEOUT_MILLIS}), far below
 * the classic service's interactive defaults.
 *
 * <p>An answer of the AEAT replaces the declaration's AEAT incidents, exactly like an attempt of
 * the direct presentation ({@link FiscalDeclCrudHandler#replaceAeatIncidentsNoCommit}); no answer
 * leaves them as they were. Everything an attempt produces goes through
 * {@link #recordOutcome}, which also leaves the attempt's row in the attempt log.
 *
 * <p>Answer (JSON, 200): {@code { fileName, fileBase64, taxReport, layoutYear, validation }} —
 * {@code fileBase64} is the file's ISO-8859-1 bytes, the same bytes {@code GET generate} serves.
 * The guards of {@code generate} (already submitted, blocking incidents, incomplete fiscal year)
 * run before this class and answer the same errors.
 */
class Fiscal390FileValidationSupport {

  static final String GENERATE_VALIDATED = "generate-validated";
  /** Bounded wait for the validator: the download must never hang on it. */
  static final int CONNECT_TIMEOUT_MILLIS = 10_000;
  static final int READ_TIMEOUT_MILLIS = 30_000;
  private static final String ID_PARAM = "id";
  private static final String LANGUAGE = "ES";

  private final Fiscal390BoxesHandler owner;

  Fiscal390FileValidationSupport(Fiscal390BoxesHandler owner) {
    this.owner = owner;
  }

  void handle(String orgId, int year, String period, Map<String, String> inputs,
      HttpServletRequest request, HttpServletResponse response) throws Exception {
    String declId = request.getParameter(ID_PARAM);
    if (StringUtils.isBlank(declId)) {
      owner.servlet.sendError(response, HttpServletResponse.SC_BAD_REQUEST,
          "Missing required param: id");
      return;
    }
    // tenant-ok: request declId, but isOwned390() below 404s another client/org's declaration
    FiscalDecl decl = OBDal.getInstance().get(FiscalDecl.class, declId);
    if (!owner.submissionSupport().isOwned390(decl, year)) {
      owner.servlet.sendError(response, HttpServletResponse.SC_NOT_FOUND,
          "Declaration not found: " + declId);
      return;
    }

    long startedAt = System.currentTimeMillis();
    Fiscal390GenerateSupport.Generated generated =
        owner.generateSupport().generateContent(orgId, year, inputs);
    long generatedAt = System.currentTimeMillis();
    AEAT303SubmissionService service = newValidationService();
    String endpoint = FiscalAeatAttemptLog.endpointOf(service, true);
    FiscalAeatValidationOutcome outcome = validate(service, generated.content, year, period);
    long validatedAt = System.currentTimeMillis();
    recordOutcome(decl,
        new FiscalAeatAttemptLog.Declaration(Fiscal390BoxesHandler.MODEL, year, period, declId,
            orgId),
        outcome, endpoint);
    // Where the time of a "Generar fichero 390" goes .
    AbstractFiscalHandler.log.info("Modelo 390 generate-validated (decl=" + declId + "): file "
        + (generatedAt - startedAt) + " ms, AEAT validation (" + outcome.outcome().key() + ") "
        + (validatedAt - generatedAt) + " ms, recording "
        + (System.currentTimeMillis() - validatedAt) + " ms");

    JSONObject out = new JSONObject();
    out.put("fileName", Fiscal390GenerateSupport.fileName(year) + ".txt");
    out.put("fileBase64", Base64.getEncoder()
        .encodeToString(generated.content.getBytes(StandardCharsets.ISO_8859_1)));
    if (generated.taxReport != null) {
      out.put("taxReport", generated.taxReport.searchKey);
      out.put("layoutYear", generated.taxReport.layoutYear);
    }
    out.put("validation", outcome.toJson());
    FiscalAeatSubmissionSupport.writeJson(response, HttpServletResponse.SC_OK, out);
  }

  /**
   * Sends the file to the AEAT validation service. Never throws: whatever goes wrong becomes an
   * unanswered outcome, so the caller can always hand the file over.
   */
  FiscalAeatValidationOutcome validate(AEAT303SubmissionService service, String fileContent,
      int year, String period) {
    try {
      service.withTimeouts(CONNECT_TIMEOUT_MILLIS, READ_TIMEOUT_MILLIS);
      return FiscalAeatValidationOutcome.answered(Fiscal390BoxesHandler.MODEL,
          FiscalAeatValidationOutcome.Path.DOWNLOAD,
          service.submitValidation(AEAT303SubmissionRequestBuilder.MODELO_390, fileContent,
              String.valueOf(year), period, LANGUAGE));
    } catch (Exception e) {
      AbstractFiscalHandler.log.warn("Modelo 390 (" + year + "): the generated file could not be"
          + " validated by the AEAT; it is downloaded unvalidated", e);
      return FiscalAeatValidationOutcome.unanswered(Fiscal390BoxesHandler.MODEL,
          FiscalAeatValidationOutcome.Path.DOWNLOAD, true, e);
    } finally {
      restoreTimeouts(service);
    }
  }

  private static void restoreTimeouts(AEAT303SubmissionService service) {
    try {
      service.withDefaultTimeouts();
    } catch (Exception e) {
      AbstractFiscalHandler.log.warn("Could not restore the AEAT service timeouts", e);
    }
  }

  /** Overridable in tests: the classic AEAT service. */
  AEAT303SubmissionService newValidationService() {
    return new AEAT303SubmissionService();
  }

  /**
   * The single point every validation attempt of the download path goes through, with its full
   * {@link FiscalAeatValidationOutcome} (path, test mode, outcome, AEAT error codes). Every
   * attempt — answered or not — leaves its row in the attempt log
   * ({@link FiscalAeatAttemptLog}, own connection). An AEAT answer is also stored in the
   * declaration's incidents: it replaces the previous AEAT incidents (a clean one leaves none);
   * no answer keeps them. An accepted answer's PDF is attached ({@link #attachValidationPdf}).
   *
   * <p>Best-effort: a persistence failure is logged and never reaches the caller, which still
   * has a file to hand over.
   */
  void recordOutcome(FiscalDecl decl, FiscalAeatAttemptLog.Declaration declaration,
      FiscalAeatValidationOutcome outcome, String endpoint) {
    owner.attemptLog().record(declaration, outcome, endpoint);
    if (!outcome.answered()) {
      return;
    }
    try {
      owner.declHandler().replaceAeatIncidentsNoCommit(decl, outcome.errors(),
          outcome.warnings());
      attachValidationPdf(decl, declaration, outcome);
      OBDal.getInstance().commitAndClose();
    } catch (Exception e) {
      AbstractFiscalHandler.log.error("Could not persist the AEAT validation incidents of"
          + " declaration " + decl.getId(), e);
      rollbackQuietly();
    }
  }

  /**
   * Keeps the validator's draft PDF of an ACCEPTED file as proof that it was validated:
   * {@code VALIDACION-390-<year>-0A.pdf}, replacing the one of the previous
   * accepted validation. A rejected answer, or an accepted one without PDF, attaches nothing and
   * leaves the existing one. Never throws (the attach helpers are best-effort).
   */
  void attachValidationPdf(FiscalDecl decl, FiscalAeatAttemptLog.Declaration declaration,
      FiscalAeatValidationOutcome outcome) {
    byte[] pdf = outcome.outcome() == FiscalAeatValidationOutcome.Outcome.ACCEPTED
        ? outcome.pdf() : null;
    if (pdf == null) {
      return;
    }
    try {
      // tenant-ok: orgId is dispatch's resolveEffectiveOrg() (session org), never the request
      Organization org = OBDal.getInstance().get(Organization.class, declaration.orgId());
      FiscalAeatSubmissionSupport.replaceAttachedPdf(decl, org,
          Fiscal390SubmissionSupport.validationFileName(declaration.year(), declaration.period()),
          pdf, "aeat390goval", Fiscal390BoxesHandler.MODEL,
          Fiscal390SubmissionSupport.legacyValidationFileName(declaration.year(),
              declaration.period()));
    } catch (Exception e) {
      AbstractFiscalHandler.log.error("Could not attach the AEAT validation PDF to declaration "
          + declaration.declId(), e);
    }
  }

  private static void rollbackQuietly() {
    try {
      OBDal.getInstance().rollbackAndClose();
    } catch (Exception e) {
      AbstractFiscalHandler.log.warn("Could not roll back after a failed incidents write", e);
    }
  }
}
