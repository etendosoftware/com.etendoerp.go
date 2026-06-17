/*
 * *************************************************************************
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
 * *************************************************************************
 */
package com.etendoerp.go.schemaforge;

import java.util.HashMap;
import java.util.Map;

import javax.inject.Named;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.advpaymentmngt.process.FIN_AddPaymentFromJournal;
import org.openbravo.base.secureApp.VariablesSecureApp;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.ui.Process;
import org.openbravo.scheduling.ProcessBundle;
import org.openbravo.service.db.DalConnectionProvider;

/**
 * Hooks for the Simple G/L Journal header entity.
 *
 * <p><b>accountingSchema injection (POST CRUD):</b> the {@code gl_journal_multiacct_check} DB
 * constraint requires {@code C_AcctSchema_ID} to be non-null whenever {@code Multi_Gl = 'N'}. The
 * field is hidden from the UI (system visibility) so the frontend never sends it. This hook
 * resolves the accounting schema from the current session ({@code $C_AcctSchema_ID}) and injects
 * it before the INSERT (ETP-4244).
 *
 * <p><b>Completion ({@code documentAction=CO}):</b> the core {@code GL_Journal.DocAction} column is
 * linked to the {@code FIN_AddPaymentFromJournal} process, which despite its name IS the journal
 * completion process (it sets {@code DocAction} and calls the {@code gl_journal_post} DB procedure).
 * NEO's generic classic executor builds the {@link ProcessBundle} without a {@code ProcessContext},
 * so that process NPEs on {@code bundle.getContext().toVars()}. This hook intercepts the Complete
 * action and runs the process with a proper context, short-circuiting the broken default dispatch.
 */
@Named("glJournalHeaderHandler")
public class GlJournalHeaderHandler implements NeoHandler {

  private static final Logger log = LogManager.getLogger(GlJournalHeaderHandler.class);

  private static final String FIELD_ACCOUNTING_SCHEMA = "accountingSchema";
  private static final String FIELD_MULTI_GL = "multigeneralLedger";
  private static final String SESSION_KEY_ACCT_SCHEMA = "$C_AcctSchema_ID";

  private static final String FIELD_DOCUMENT_ACTION = "documentAction";
  private static final String PARAM_GL_JOURNAL_ID = "GL_Journal_ID";
  /** AD_Process_ID of "Add Payment From Journal" (FIN_AddPaymentFromJournal) — GL Journal completion. */
  private static final String COMPLETE_PROCESS_ID = "5BE14AA10165490A9ADEFB7532F7FA94";

  @Override
  public NeoResponse handle(NeoContext context) {
    // Completion runs the real core process with a proper context, replacing NEO's
    // contextless dispatch that would NPE inside FIN_AddPaymentFromJournal.
    if (isCompleteAction(context)) {
      return completeJournal(context);
    }
    if (!"POST".equalsIgnoreCase(context.getHttpMethod())) {
      return null;
    }
    JSONObject body = context.getRequestBody();
    if (body == null) {
      return null;
    }
    try {
      // When Multi_Gl = 'Y' the constraint allows a null AcctSchema — don't inject.
      String multiGl = body.optString(FIELD_MULTI_GL, "N");
      if ("Y".equalsIgnoreCase(multiGl)) {
        return null;
      }
      // Only inject when the field is absent (handler never overwrites an explicit caller value).
      if (body.has(FIELD_ACCOUNTING_SCHEMA) && !body.isNull(FIELD_ACCOUNTING_SCHEMA)) {
        return null;
      }
      VariablesSecureApp vars = NeoDefaultsService.buildVariablesSecureApp(context.getObContext());
      String acctSchemaId = vars.getSessionValue(SESSION_KEY_ACCT_SCHEMA);
      if (acctSchemaId != null && !acctSchemaId.isEmpty()) {
        body.put(FIELD_ACCOUNTING_SCHEMA, acctSchemaId);
        log.debug("[GL-JOURNAL] Injected accountingSchema={} from session", acctSchemaId);
      } else {
        log.warn("[GL-JOURNAL] No $C_AcctSchema_ID in session — gl_journal_multiacct_check may fire");
      }
    } catch (Exception e) {
      log.warn("[GL-JOURNAL] Could not inject accountingSchema: {}", e.getMessage(), e);
    }
    return null;
  }

  @Override
  public NeoResponse afterHandle(NeoContext context) {
    return null;
  }

  /**
   * True when this is the {@code POST /action/documentAction} request that completes the journal
   * (body carries {@code documentAction=CO}, either at the root or nested under {@code fieldValues}
   * as sent by the draft-mode confirm button).
   */
  private static boolean isCompleteAction(NeoContext context) {
    if (!NeoEndpointType.ACTION.equals(context.getEndpointType())) {
      return false;
    }
    if (!FIELD_DOCUMENT_ACTION.equals(context.getFieldName())) {
      return false;
    }
    JSONObject body = context.getRequestBody();
    if (body == null) {
      return false;
    }
    JSONObject fieldValues = body.optJSONObject("fieldValues");
    String docAction = fieldValues != null
        ? fieldValues.optString(FIELD_DOCUMENT_ACTION, "")
        : body.optString("docAction", body.optString(FIELD_DOCUMENT_ACTION, ""));
    return "CO".equals(docAction);
  }

  /**
   * Runs the core {@code FIN_AddPaymentFromJournal} process with a {@link ProcessBundle} that
   * carries a {@code ProcessContext} (built from the current OBContext), then translates the
   * {@code OBError} result into a {@link NeoResponse} via the shared classic-result translator.
   */
  private NeoResponse completeJournal(NeoContext context) {
    String journalId = context.getRecordId();
    if (journalId == null || journalId.isEmpty()) {
      return NeoResponse.error(400, "Missing GL Journal record id for completion");
    }
    try {
      VariablesSecureApp vars = NeoDefaultsService.buildVariablesSecureApp(context.getObContext());
      ProcessBundle bundle = new ProcessBundle(COMPLETE_PROCESS_ID, vars)
          .init(new DalConnectionProvider(false));
      Map<String, Object> params = new HashMap<>();
      params.put(PARAM_GL_JOURNAL_ID, journalId);
      bundle.setParams(params);
      new FIN_AddPaymentFromJournal().execute(bundle);
      Process process = OBDal.getInstance().get(Process.class, COMPLETE_PROCESS_ID);
      return NeoProcessService.translateClassicResult(bundle.getResult(), process);
    } catch (Exception e) {
      log.error("[GL-JOURNAL] Completion failed for id={}: {}", journalId, e.getMessage(), e);
      return NeoResponse.error(500, "GL Journal completion failed: " + e.getMessage());
    }
  }
}
