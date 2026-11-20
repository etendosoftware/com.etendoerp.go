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
 * All portions are Copyright © 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.schemaforge;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.erpCommon.utility.OBError;
import org.openbravo.module.remittance.Remittance;

import com.etendoerp.go.mcp.McpAttachmentLinks;
import com.etendoerp.go.schemaforge.util.NeoActionContract;
import com.etendoerp.go.schemaforge.util.NeoActionContract.Param;

/**
 * ETP-6247 — the SEPA file actions of the remittance header ({@link RemittanceHeaderHandler}):
 * <ul>
 *   <li>{@value #ACTION_READINESS} (read): whether the file can be generated and why not
 *       ({@link RemittanceFileReadiness});</li>
 *   <li>{@value #ACTION_CONFIRM} (write): «Confirmar» — readiness, then in ONE transaction the
 *       remittance is processed by Classic's {@code REMRemittanceProcess} WITHOUT the automatic
 *       settlement (the payments are created Remitted, the invoices stay unpaid, the status is
 *       {@code PR}), its file is generated with the remittance type's Classic generator and stored
 *       as the record's main attachment. Any failure rolls the whole of it back. The later steps
 *       «Enviada al banco» / «Cobrada» are {@link RemittanceStatusAction};</li>
 *   <li>{@value #ACTION_GENERATE} (write): «Regenerar fichero» on a processed remittance — a new
 *       main attachment, the earlier files kept. Refused ({@value #MSG_HAS_RETURNS}) once a line
 *       was returned.</li>
 * </ul>
 * A refusal is a 422 with the message key in {@code error.messageKeys}; the readiness refusal
 * ({@value #MSG_NOT_READY}) also carries {@code error.problems}. Both writes emit
 * {@code backend_remittance_file_generated} once they succeed ({@link RemittanceFileTelemetry}).
 */
final class RemittanceFileAction {

  private static final Logger log = LogManager.getLogger(RemittanceFileAction.class);

  static final String ACTION_READINESS = RemittanceFileReadiness.ACTION;
  static final String ACTION_CONFIRM = "confirm";
  static final String ACTION_GENERATE = "generateFile";

  static final String MSG_NOT_READY = "ETGO_RemittanceFileNotReady";
  static final String MSG_NOT_PROCESSED = "ETGO_RemittanceFileNotProcessed";
  static final String MSG_HAS_RETURNS = "ETGO_RemittanceFileHasReturns";

  static final String KEY_STATUS = "status";
  static final String KEY_DOWNLOAD_URL = "downloadUrl";
  /** The NEO spec of the remittance window, which the signed download link names. */
  static final String SPEC = "remittance";

  private static final int SC_UNPROCESSABLE = NeoActionContract.SC_UNPROCESSABLE;
  private static final String POST = "POST";

  private RemittanceFileAction() {
  }

  /** The description of the record id the three actions take. */
  private static final String ID_DESCRIPTION = "the id of the remittance";

  /** Declares the three actions on the remittance header. */
  static void addContracts(Map<String, NeoActionContract> contracts) {
    contracts.put(ACTION_READINESS, NeoActionContract.read(ACTION_READINESS,
        "Checks whether the SEPA file of the remittance can be generated. Returns ready, scheme "
            + "(CORE collection or SCT payment), problems (each with scope org/account/line, code, "
            + "message, params and, for a line, lineId, lineIds, businessPartnerId, "
            + "businessPartnerName, invoiceId, documentNo), summary (invoiceCount, lineCount, "
            + "totalAmount, currency, dueDate) and latestFile ({attachmentId, fileName} of the "
            + "last generated file, or null). Fix the problems before calling confirm.")
        .withIdDescription(ID_DESCRIPTION));
    contracts.put(ACTION_CONFIRM, NeoActionContract.write(ACTION_CONFIRM,
        "Confirms the remittance: processes it (creates the payments of its lines, status "
            + "Remitted; nothing is settled and the invoices stay unpaid), generates its SEPA "
            + "file and attaches it as the remittance's main document, all or nothing; the "
            + "remittance status becomes PR. Refused when it is already processed, has no lines or is not "
            + "ready (the refusal lists the problems, as fileReadiness). Returns status (the "
            + "remittance status code), attachmentId, fileName and downloadUrl (a short-lived "
            + "link to the file, or null).",
        Param.options(RemittanceProcessAction.P_GROUPING, "'"
            + RemittanceProcessAction.GROUPING_NONE + "' one payment per line (default), '"
            + RemittanceProcessAction.GROUPING_BP_DUE_DATE
            + "' one payment per business partner and due date.",
            List.of(RemittanceProcessAction.GROUPING_NONE,
                RemittanceProcessAction.GROUPING_BP_DUE_DATE)))
        .withIdDescription(ID_DESCRIPTION)
        .withFieldValuesBody());
    contracts.put(ACTION_GENERATE, NeoActionContract.write(ACTION_GENERATE,
        "Generates the SEPA file of a confirmed (processed) remittance again (while it is PR or "
            + "SB, or later) and attaches it as its main document; the earlier files are kept. Takes no parameters. Returns "
            + "status, attachmentId, fileName and downloadUrl, as confirm. Refused once a line "
            + "of the remittance was returned (the file would still list it).")
        .withIdDescription(ID_DESCRIPTION));
  }

  /** @return whether {@code action} is one of this class's actions */
  static boolean serves(String action) {
    return ACTION_READINESS.equals(action) || ACTION_CONFIRM.equals(action)
        || ACTION_GENERATE.equals(action);
  }

  /**
   * Runs one of the actions: 405 for a write that is not a POST, the contract's 422 for a
   * malformed call, 404 for an unknown remittance.
   *
   * @param handler the remittance header customization (its seams)
   * @param context the action request
   * @return the answer
   */
  static NeoResponse handle(RemittanceHeaderHandler handler, NeoContext context) {
    String action = context.getFieldName();
    if (!ACTION_READINESS.equals(action) && !POST.equals(context.getHttpMethod())) {
      return NeoResponse.error(HttpServletResponse.SC_METHOD_NOT_ALLOWED,
          action + " only supports POST");
    }
    OBContext.setAdminMode(true);
    try {
      JSONObject body = context.getRequestBody() != null ? context.getRequestBody()
          : new JSONObject();
      JSONObject params = handler.actionParams(action, body);
      NeoResponse invalid = NeoActionContract.validate(handler.actionContracts(), action, params);
      if (invalid != null) {
        return invalid;
      }
      Remittance remittance = handler.loadRemittance(context.getRecordId());
      if (remittance == null) {
        return NeoResponse.error(HttpServletResponse.SC_NOT_FOUND, "Remittance not found");
      }
      if (ACTION_READINESS.equals(action)) {
        return NeoResponse.ok(RemittanceFileReadiness.body(remittance));
      }
      handler.lockRemittance(remittance);
      return ACTION_CONFIRM.equals(action) ? confirm(remittance, params)
          : generateFile(remittance);
    } catch (Exception e) {
      log.error("remittance action {} failed on {}", action, context.getRecordId(), e);
      NeoErrorResponses.rollbackUnlessBatchOwned();
      return NeoResponse.error(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
          "Could not run " + action + ". Please check logs for details.");
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /**
   * «Confirmar».
   *
   * @param remittance a remittance of the tenant
   * @param params     {@value RemittanceProcessAction#P_GROUPING}, optional
   * @return {@code {status, attachmentId, fileName, downloadUrl}}, or 422
   */
  static NeoResponse confirm(Remittance remittance, JSONObject params)
      throws JSONException, IOException {
    if (Boolean.TRUE.equals(remittance.isProcessed())) {
      return NeoMessageText.error(SC_UNPROCESSABLE, RemittanceProcessAction.MSG_ALREADY_PROCESSED);
    }
    RemittanceFileReadiness.Result readiness = RemittanceFileReadiness.evaluate(remittance);
    if (readiness.lineCount == 0) {
      return NeoMessageText.error(SC_UNPROCESSABLE, RemittanceProcessAction.MSG_NO_LINES);
    }
    if (!readiness.isReady()) {
      return notReady(readiness);
    }
    String remittanceId = remittance.getId();
    RemittanceSepaFileSetup.ensure(remittance.getRemittanceType(), readiness.scheme);
    // ETP-6247: processed WITHOUT the automatic settlement (payments stay Remitted, invoices
    // unpaid); «Cobrada» settles. A remittance an earlier version created automatic is reset.
    RemittanceProcessAction.disableAutomaticSettlement(remittance);
    String grouping = StringUtils.defaultIfBlank(
        params.optString(RemittanceProcessAction.P_GROUPING, null),
        RemittanceProcessAction.GROUPING_NONE);
    OBError result = runProcess(remittanceId, grouping);
    NeoResponse refused = RemittanceProcessAction.refusalOf(result, remittanceId, grouping,
        RemittanceProcessAction.MSG_PROCESS_FAILED);
    if (refused != null) {
      return refused;
    }
    Remittance processed = OBDal.getInstance().get(Remittance.class, remittanceId);
    if (processed == null || !Boolean.TRUE.equals(processed.isProcessed())) {
      RemittanceProcessAction.rollback();
      return NeoMessageText.error(SC_UNPROCESSABLE, RemittanceProcessAction.MSG_PROCESS_FAILED,
          "-");
    }
    return generateAndStore(processed, readiness, false);
  }

  /**
   * «Regenerar fichero».
   *
   * @param remittance a remittance of the tenant
   * @return {@code {status, attachmentId, fileName, downloadUrl}}, or 422
   */
  static NeoResponse generateFile(Remittance remittance) throws JSONException, IOException {
    if (!Boolean.TRUE.equals(remittance.isProcessed())) {
      return NeoMessageText.error(SC_UNPROCESSABLE, MSG_NOT_PROCESSED);
    }
    // ETP-6247: the Classic generator writes every line of the remittance, returned ones included,
    // so the file of a remittance with returns would list receipts the bank did not collect.
    if (RemittanceReturnState.hasReturns(remittance)) {
      return NeoMessageText.error(SC_UNPROCESSABLE, MSG_HAS_RETURNS);
    }
    RemittanceFileReadiness.Result readiness = RemittanceFileReadiness.evaluate(remittance);
    if (!readiness.isReady()) {
      return notReady(readiness);
    }
    RemittanceSepaFileSetup.ensure(remittance.getRemittanceType(), readiness.scheme);
    return generateAndStore(remittance, readiness, true);
  }

  // ---------------------------------------------------------------------------
  // Steps
  // ---------------------------------------------------------------------------

  private static NeoResponse generateAndStore(Remittance remittance,
      RemittanceFileReadiness.Result readiness, boolean regeneration)
      throws JSONException, IOException {
    byte[] content;
    try {
      content = generate(remittance);
    } catch (RemittanceFileGenerator.Refusal refusal) {
      RemittanceProcessAction.rollback();
      return NeoMessageText.error(SC_UNPROCESSABLE, refusal.getKey(), refusal.getArgs());
    }
    RemittanceFileGenerator.Stored stored = store(remittance, content);
    JSONObject body = new JSONObject();
    Object status = remittance.get(
        org.openbravo.module.remittance.utility.RemittanceStatusUtil.PROPERTY_REMITTANCE_STATUS);
    body.put(KEY_STATUS, status != null ? status : JSONObject.NULL);
    body.put(RemittanceFileReadiness.KEY_ATTACHMENT_ID, stored.attachmentId);
    body.put(RemittanceFileReadiness.KEY_FILE_NAME, stored.fileName);
    String url = downloadUrl(remittance.getId(), stored.attachmentId);
    body.put(KEY_DOWNLOAD_URL, url != null ? url : JSONObject.NULL);
    RemittanceFileTelemetry.fileGenerated(remittance, readiness.isCollection(),
        readiness.invoiceCount, regeneration);
    return NeoResponse.ok(body);
  }

  private static NeoResponse notReady(RemittanceFileReadiness.Result readiness)
      throws JSONException {
    NeoResponse refusal = NeoMessageText.error(SC_UNPROCESSABLE, MSG_NOT_READY,
        readiness.problems.size());
    JSONObject error = refusal.getBody() != null ? refusal.getBody().optJSONObject("error") : null;
    if (error != null) {
      error.put(RemittanceFileReadiness.KEY_PROBLEMS, readiness.problems.toJson());
    }
    return refusal;
  }

  // ---------------------------------------------------------------------------
  // Seams
  // ---------------------------------------------------------------------------

  /** Seam over {@link RemittanceProcessAction#run}. */
  static OBError runProcess(String remittanceId, String grouping) {
    return RemittanceProcessAction.run(remittanceId, grouping);
  }

  /** Seam over {@link RemittanceFileGenerator#generate}. */
  static byte[] generate(Remittance remittance) throws RemittanceFileGenerator.Refusal {
    return RemittanceFileGenerator.generate(remittance);
  }

  /** Seam over {@link RemittanceFileGenerator#store}. */
  static RemittanceFileGenerator.Stored store(Remittance remittance, byte[] content)
      throws IOException {
    return RemittanceFileGenerator.store(remittance, content);
  }

  /** The signed link to the stored file, or {@code null} (never fails the action). */
  static String downloadUrl(String remittanceId, String attachmentId) {
    try {
      return McpAttachmentLinks.downloadUrl(SPEC, remittanceId, attachmentId);
    } catch (RuntimeException e) {
      log.debug("No download link for the SEPA file {}: {}", attachmentId, e.getMessage());
      return null;
    }
  }
}
