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
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Base64;
import java.util.HashMap;
import java.util.Set;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.module.aeat303.es.presentation.AEAT303SubmissionResult;

import com.etendoerp.go.schemaforge.data.FiscalDecl;

/**
 * Model-agnostic pieces of an AEAT telematic submission (PresBasicaDos / ServValiDos), shared by
 * {@link Fiscal303SubmissionSupport} ({@code POST /fiscal303/submit}) and
 * {@link Fiscal390SubmissionSupport} ({@code POST /fiscal390/submit}) — ETP-5716.
 *
 * <p>Everything here was moved verbatim out of the Modelo 303 submission code, where it was the
 * only caller: the JSON request/response plumbing, the response shapes of a submission that
 * reached the AEAT ({@link #resultJson}) or failed before it ({@link #failureJson}), and the
 * justificante PDF attachment ({@link #attachPdf}). None of it branches on the model; the
 * model-specific parts (file generation, presenter/NRC rules, declaration data) stay in each
 * model's support class.
 */
final class FiscalAeatSubmissionSupport {

  static final String PARAM_TEST_MODE = "testMode";

  private FiscalAeatSubmissionSupport() {
  }

  /** The request body as JSON; an empty body yields an empty object. */
  static JSONObject readJsonBody(HttpServletRequest request) throws Exception {
    StringBuilder sb = new StringBuilder();
    try (BufferedReader reader = request.getReader()) {
      String line;
      while ((line = reader.readLine()) != null) sb.append(line);
    }
    String raw = sb.toString();
    return raw.isEmpty() ? new JSONObject() : new JSONObject(raw);
  }

  static void writeJson(HttpServletResponse response, int status, JSONObject body)
      throws Exception {
    response.setContentType(AbstractFiscalHandler.JSON_CT);
    response.setStatus(status);
    response.getWriter().write(body.toString());
  }

  /**
   * The response of a submission that reached the AEAT (successfully or not):
   * {@code status} ({@code SUCCESS} / {@code TEST_SUCCESS} / {@code ERROR}), {@code testMode},
   * CSV, presentation date, registry and justificante numbers, the PDF as base64, the
   * {@code pdfDownloadFailed} flag, the AEAT {@code errors}/{@code warnings} and the model's
   * {@code declarationData}.
   */
  static JSONObject resultJson(AEAT303SubmissionResult result, JSONObject declarationData)
      throws Exception {
    boolean testMode = result.isTestMode();
    boolean successful = result.isSuccessful();
    String status;
    if (!successful) {
      status = "ERROR";
    } else if (testMode) {
      status = "TEST_SUCCESS";
    } else {
      status = "SUCCESS";
    }

    JSONObject o = new JSONObject();
    o.put("status", status);
    o.put(PARAM_TEST_MODE, testMode);
    o.put("csv", StringUtils.defaultString(result.getCsv()));
    o.put("presentationDate", StringUtils.defaultString(result.getPresentationDate()));
    o.put("registryNumber", StringUtils.defaultString(result.getRegistryNumber()));
    o.put("justificanteNumber", StringUtils.defaultString(result.getJustificanteNumber()));
    byte[] pdf = result.getPdfContent();
    o.put("pdfBase64", pdf != null ? Base64.getEncoder().encodeToString(pdf) : JSONObject.NULL);
    o.put("pdfDownloadFailed", result.isPdfDownloadFailed());
    o.put("errors", new JSONArray(result.getErrors()));
    o.put("warnings", new JSONArray(result.getWarnings()));
    o.put("declarationData", declarationData);
    return o;
  }

  /** The response of a submission that failed before (or without) reaching the AEAT. */
  static JSONObject failureJson(boolean testMode, String errorCode, String message)
      throws Exception {
    JSONObject o = new JSONObject();
    o.put("status", "ERROR");
    o.put(PARAM_TEST_MODE, testMode);
    o.put("errorCode", errorCode);
    JSONArray errors = new JSONArray();
    errors.put(StringUtils.defaultString(message));
    o.put("errors", errors);
    o.put("warnings", new JSONArray());
    return o;
  }

  /**
   * Attaches a justificante/receipt PDF to the declaration record with the same generic
   * attachment infrastructure the {@code /neo/attachments} endpoints use
   * ({@link NeoAttachmentsHelper}). Best-effort: a failure is logged and never thrown, because the
   * PDF is also returned inline (base64) in the API response. Does NOT commit — the caller commits
   * once, together with the rest of the submission write (ETP-4456 atomicity fix).
   *
   * @param tempPrefix prefix of the owner-only temporary directory the PDF is staged in
   * @param modelLabel model number used in the log lines only (e.g. {@code "303"})
   */
  static void attachPdf(FiscalDecl decl, Organization org, String fileName, byte[] pdfContent,
      String tempPrefix, String modelLabel) {
    String tabId;
    try {
      String tableId = NeoAttachmentsHelper.resolveTableId(FiscalDecl.TABLE_NAME);
      tabId = NeoAttachmentsHelper.resolveTabId(tableId, null);
    } catch (Exception e) {
      AbstractFiscalHandler.log.warn("Could not resolve an AD_Tab for " + FiscalDecl.TABLE_NAME + " attachments", e);
      tabId = null;
    }
    if (tabId == null) {
      AbstractFiscalHandler.log.warn("No AD_Tab configured for " + FiscalDecl.TABLE_NAME + " attachments yet — the "
          + "AEAT " + modelLabel + " justificante PDF for declaration " + decl.getId() + " was not persisted as "
          + "an attachment (it is still returned inline in the API response).");
      return;
    }

    Path tempDir = null;
    try {
      // SonarQube java:S5443 (ETP-4456): explicit owner-only permissions. Etendo GO's runtime is
      // Linux-only, so PosixFilePermissions is safe to use unconditionally here.
      FileAttribute<Set<PosixFilePermission>> ownerOnly =
          PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"));
      tempDir = Files.createTempDirectory(tempPrefix, ownerOnly);
      Path pdfPath = tempDir.resolve(fileName);
      Files.write(pdfPath, pdfContent);
      NeoAttachmentsHelper.getAttachManager()
          .upload(new HashMap<>(), tabId, decl.getId(), org.getId(), pdfPath.toFile());
    } catch (Exception e) {
      AbstractFiscalHandler.log.error("Could not attach the AEAT " + modelLabel + " justificante PDF to declaration "
          + decl.getId(), e);
    } finally {
      cleanupTempDir(tempDir);
    }
  }

  private static void cleanupTempDir(Path tempDir) {
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
      AbstractFiscalHandler.log.warn("Could not clean up temporary directory " + tempDir, e);
    }
  }
}
