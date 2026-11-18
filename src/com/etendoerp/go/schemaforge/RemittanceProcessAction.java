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

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.base.secureApp.VariablesSecureApp;
import org.openbravo.base.weld.WeldUtils;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.erpCommon.utility.OBError;
import org.openbravo.module.remittance.Remittance;
import org.openbravo.module.remittance.RemittanceLine;
import org.openbravo.module.remittance.process.REMRemittanceProcess;
import org.openbravo.module.remittance.utility.RemittanceStatusUtil;
import org.openbravo.scheduling.ProcessBundle;

import com.etendoerp.go.schemaforge.util.NeoActionContract;
import com.etendoerp.go.schemaforge.util.NeoActionContract.Param;

/**
 * ETP-6148 — «Procesar» of a remittance: Classic's {@code REMRemittanceProcess} (the «Process
 * Remittance» button, {@code AD_Process} {@value #PROCESS_ID}) run on the record, as the button
 * does. A declared action of the remittance header ({@link RemittanceHeaderHandler}).
 *
 * <p>The grouping is the button's action code: {@value #GROUPING_NONE} (one payment per line, the
 * default) or {@value #GROUPING_BP_DUE_DATE} (one per business partner and due date). REM checks
 * the rest itself — no repeated payments, at least one line, amounts in range and the
 * account ↔ method link setup ({@code checkPMSetup}, which the links {@link
 * RemittanceSepaProvisioning} creates satisfy) — creates the payments, marks the remittance
 * processed and refreshes its status. A refusal is answered 422 with REM's own text.
 *
 * <p>ETP-6247: the process does NOT settle. It creates the payments in status Remitted
 * ({@code REM_SENT}); the invoices stay unpaid until «Cobrada» ({@link RemittanceStatusAction}).
 *
 * <p>The process runs directly, like {@code NeoProcessService}'s scheduling-process strategy, so a
 * role that may write the remittance window may process it without a separate
 * {@code AD_Process_Access} grant — the same rule Classic's button follows.
 */
final class RemittanceProcessAction {

  private static final Logger log = LogManager.getLogger(RemittanceProcessAction.class);

  static final String ACTION = "process";
  static final String ACTION_REACTIVATE = "reactivate";
  /** REM's action code for «Reactivate». */
  static final String REACTIVATE_CODE = "R";
  static final String P_GROUPING = "grouping";
  static final String GROUPING_NONE = "P";
  static final String GROUPING_BP_DUE_DATE = "GGPD";
  static final String PROCESS_ID = "FF8080812E77C0AF012E77C585600014";

  static final String MSG_ALREADY_PROCESSED = "ETGO_RemittanceAlreadyProcessed";
  static final String MSG_NO_LINES = "ETGO_RemittanceNoLines";
  static final String MSG_PROCESS_FAILED = "ETGO_RemittanceProcessFailed";
  static final String MSG_NOT_PROCESSED = "ETGO_RemittanceNotProcessed";
  static final String MSG_REACTIVATE_FAILED = "ETGO_RemittanceReactivateFailed";
  /** ETP-6247: a collected, paid, partially settled or voided remittance cannot be reactivated. */
  static final String MSG_REACTIVATE_NOT_ALLOWED = "ETGO_RemittanceReactivateNotAllowed";

  private RemittanceProcessAction() {
  }

  static void addContract(Map<String, NeoActionContract> contracts) {
    contracts.put(ACTION, NeoActionContract.write(ACTION,
        "Processes the remittance with the remittance module's own process (the «Process "
            + "Remittance» button): creates the payments of its lines (status Remitted, nothing "
            + "settled) and marks it processed; its status becomes PR. Refused when it is already processed or has no lines. Returns "
            + "status (the remittance status code) and message.",
        Param.options(P_GROUPING, "'" + GROUPING_NONE + "' one payment per line (default), '"
            + GROUPING_BP_DUE_DATE + "' one payment per business partner and due date.",
            List.of(GROUPING_NONE, GROUPING_BP_DUE_DATE)))
        .withIdDescription("the id of the remittance")
        .withFieldValuesBody());
    contracts.put(ACTION_REACTIVATE, NeoActionContract.write(ACTION_REACTIVATE,
        "Reactivates a processed remittance with the remittance module's own process: removes the "
            + "payments the processing created and marks it not processed again; its status "
            + "becomes PE. Only while the remittance is PR (processed) or SB (sent to bank). "
            + "Refused when it is not processed, is collected/paid/partially settled/voided, is "
            + "posted, has settled or returned lines, or its payments cannot be reactivated. Takes no parameters. Returns status "
            + "(the remittance status code) and message.")
        .withIdDescription("the id of the remittance")
        .withFieldValuesBody());
  }

  /** @return whether {@code action} is one of this class's actions */
  static boolean serves(String action) {
    return ACTION.equals(action) || ACTION_REACTIVATE.equals(action);
  }

  /**
   * Runs the process on {@code remittance}.
   *
   * @param remittance the remittance, not processed (the caller refuses a processed one)
   * @param params     {@value #P_GROUPING}, optional
   * @return {@code {status, message}}, or 422
   */
  static NeoResponse process(Remittance remittance, JSONObject params) throws JSONException {
    if (Boolean.TRUE.equals(remittance.isProcessed())) {
      return NeoMessageText.error(NeoActionContract.SC_UNPROCESSABLE, MSG_ALREADY_PROCESSED);
    }
    if (countLines(remittance) == 0) {
      return NeoMessageText.error(NeoActionContract.SC_UNPROCESSABLE, MSG_NO_LINES);
    }
    String grouping = StringUtils.defaultIfBlank(params.optString(P_GROUPING, null),
        GROUPING_NONE);
    disableAutomaticSettlement(remittance);
    return runAndAnswer(remittance, grouping, MSG_PROCESS_FAILED);
  }

  /**
   * Reactivates {@code remittance} (REM action {@value #REACTIVATE_CODE}). REM refuses a posted
   * remittance, one with settled or returned lines, and one whose payments cannot be reactivated;
   * on success its status is refreshed to {@code PE} by REM's {@code RemittanceStatusUtil}.
   *
   * @param remittance the remittance
   * @return {@code {status, message}}, or 422
   */
  static NeoResponse reactivate(Remittance remittance) throws JSONException {
    if (!Boolean.TRUE.equals(remittance.isProcessed())) {
      return NeoMessageText.error(NeoActionContract.SC_UNPROCESSABLE, MSG_NOT_PROCESSED);
    }
    if (RemittanceStatusAction.isSettledOrVoided(
        remittance.get(RemittanceStatusUtil.PROPERTY_REMITTANCE_STATUS))) {
      return NeoMessageText.error(NeoActionContract.SC_UNPROCESSABLE, MSG_REACTIVATE_NOT_ALLOWED);
    }
    return runAndAnswer(remittance, REACTIVATE_CODE, MSG_REACTIVATE_FAILED);
  }

  /**
   * ETP-6247 — the remittance is processed WITHOUT the automatic settlement: the
   * automated-remittance hook settles the payments on process only when
   * {@code EM_Etar_Automaticprocess = 'Y'}, and an earlier version of Etendo GO created remittances
   * with it on. Reset to {@code N} before REM runs, so the payments stay Remitted and the invoices
   * unpaid until «Cobrada» ({@link RemittanceStatusAction}). The caller runs in a transaction and
   * flushes through REM's own process.
   *
   * @param remittance the remittance about to be processed
   */
  static void disableAutomaticSettlement(Remittance remittance) {
    if (Boolean.TRUE.equals(remittance.isETARAutomaticprocess())) {
      remittance.setETARAutomaticprocess(false);
      OBDal.getInstance().save(remittance);
      OBDal.getInstance().flush();
    }
  }

  private static NeoResponse runAndAnswer(Remittance remittance, String actionCode,
      String failureKey) throws JSONException {
    OBError result = run(remittance.getId(), actionCode);
    NeoResponse refusal = refusalOf(result, remittance.getId(), actionCode, failureKey);
    if (refusal != null) {
      return refusal;
    }
    Remittance reloaded = OBDal.getInstance().get(Remittance.class, remittance.getId());
    JSONObject body = new JSONObject();
    body.put("status", reloaded != null
        ? reloaded.get(RemittanceStatusUtil.PROPERTY_REMITTANCE_STATUS)
        : JSONObject.NULL);
    body.put("message", StringUtils.defaultString(
        com.etendoerp.go.schemaforge.util.NeoMessageTranslator.safeParseTranslation(result.getMessage())));
    return NeoResponse.ok(body);
  }

  /**
   * The 422 for a REM run that did not succeed, after rolling back what it wrote; {@code null} when
   * it succeeded. Shared with «Confirmar» ({@link RemittanceFileAction}).
   *
   * @param result       REM's result, {@code null} when the run gave none
   * @param remittanceId the remittance, for the log
   * @param actionCode   REM's action code, for the log
   * @param failureKey   the message key of the refusal; its {@code {0}} is REM's text
   * @return the refusal, or {@code null}
   */
  static NeoResponse refusalOf(OBError result, String remittanceId, String actionCode,
      String failureKey) {
    if (result != null && !"error".equalsIgnoreCase(result.getType())) {
      return null;
    }
    String text = result != null ? StringUtils.defaultIfBlank(result.getMessage(),
        result.getTitle()) : null;
    log.warn("{} refused for remittance {}: {}", actionCode, remittanceId, text);
    String translated = StringUtils.defaultIfBlank(
        com.etendoerp.go.schemaforge.util.NeoMessageTranslator.safeParseTranslation(text), "-");
    // REM can catch an OBException after creating payments and only set the error, so what it
    // wrote must not commit with our 422. Translated first: the rollback closes the session.
    rollback();
    return NeoMessageText.error(NeoActionContract.SC_UNPROCESSABLE, failureKey, translated);
  }

  /**
   * Seam: rolls back what a refused REM run wrote, unless a batch ({@code etendo_batch},
   * {@code /sws/neo/batch}) owns the transaction and rolls the whole set back itself.
   */
  static void rollback() {
    NeoErrorResponses.rollbackUnlessBatchOwned();
  }

  /**
   * Seam: runs {@code REMRemittanceProcess} with the button's parameters.
   *
   * @param remittanceId the remittance
   * @param grouping     REM's action code: a grouping ({@code P}, {@code GGPD}) to process, or
   *                     {@value #REACTIVATE_CODE} to reactivate
   * @return REM's result, or an error result when the run throws
   */
  static OBError run(String remittanceId, String grouping) {
    try {
      org.openbravo.scheduling.Process process =
          WeldUtils.getInstanceFromStaticBeanManager(REMRemittanceProcess.class);
      VariablesSecureApp vars = NeoDefaultsService.buildVariablesSecureApp(OBContext.getOBContext());
      // The DAL connection, as NeoProcessService runs a process: REM's payment creation runs the
      // payment process on bundle.getConnection(), which is null without init (NPE in
      // ProcessData, ETP-6148 round 3).
      ProcessBundle bundle = new ProcessBundle(PROCESS_ID, vars)
          .init(new org.openbravo.service.db.DalConnectionProvider(false));
      Map<String, Object> bundleParams = new HashMap<>();
      bundleParams.put("REM_Remittance_ID", remittanceId);
      bundleParams.put("action", grouping);
      bundle.setParams(bundleParams);
      process.execute(bundle);
      Object result = bundle.getResult();
      return result instanceof OBError ? (OBError) result : null;
    } catch (Exception e) {
      log.error("REMRemittanceProcess failed on remittance {}", remittanceId, e);
      OBError error = new OBError();
      error.setType("Error");
      error.setMessage(e.getMessage());
      return error;
    }
  }

  private static long countLines(Remittance remittance) {
    Long count = OBDal.getInstance().getSession().createQuery(
        "select count(rl.id) from " + RemittanceLine.ENTITY_NAME
            + " as rl where rl.remittance.id = :remittance and rl.active = true", Long.class)
        .setParameter("remittance", remittance.getId())
        .uniqueResult();
    return count != null ? count : 0L;
  }
}
