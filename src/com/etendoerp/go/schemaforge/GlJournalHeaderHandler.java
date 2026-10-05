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

import javax.inject.Inject;
import javax.inject.Named;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.advpaymentmngt.process.FIN_AddPaymentFromJournal;
import org.openbravo.base.secureApp.VariablesSecureApp;
import org.openbravo.client.kernel.RequestContext;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.ui.Process;
import org.openbravo.model.common.currency.Currency;
import org.openbravo.model.financialmgmt.accounting.coa.AcctSchema;
import org.openbravo.scheduling.ProcessBundle;
import org.openbravo.service.db.DalConnectionProvider;

import com.etendoerp.go.schemaforge.handlers.DocumentPostingService;

/**
 * Hooks for the Simple G/L Journal header entity.
 *
 * <p><b>accountingSchema injection (POST CRUD):</b> the {@code gl_journal_multiacct_check} DB
 * constraint requires {@code C_AcctSchema_ID} to be non-null whenever {@code Multi_Gl = 'N'}. The
 * field is hidden from the UI (system visibility) so the frontend never sends it. This hook
 * resolves the accounting schema from the current session ({@code $C_AcctSchema_ID}) and injects
 * it before the INSERT (ETP-4244).
 *
 * <p><b>Completion and reactivation ({@code documentAction=CO|RE}):</b> the core
 * {@code GL_Journal.DocAction} column is linked to the {@code FIN_AddPaymentFromJournal} process,
 * which despite its name IS the journal completion process (it sets {@code DocAction} and calls the
 * {@code gl_journal_post} DB procedure). NEO's generic classic executor builds the
 * {@link ProcessBundle} without a {@code ProcessContext}, so that process NPEs on
 * {@code bundle.getContext().toVars()}. This hook intercepts both actions and runs the process with
 * a proper context, short-circuiting the broken default dispatch. The process reads the action from
 * the HTTP parameter {@code inpdocaction} (falling back to CO), never from the body, so the hook sets
 * that parameter on the request wrapper for BOTH actions (ETP-5611): it outlives the operation, and
 * inside one {@code /batch} request an earlier RE would otherwise turn a later CO into a reactivate.
 *
 * <p><b>Single date (ETP-5611):</b> the window shows one "Fecha" ({@code accountingDate}); every
 * CRUD write mirrors it into {@code documentDate}, unconditionally — the UI itself sends
 * {@code documentDate=@#Date@} on create, and that value must not survive.
 *
 * <p><b>Currency (ETP-5611):</b> manual journals are single-currency in Etendo GO — always the
 * accounting schema currency. {@code @C_Currency_ID@} resolves to the organization currency first,
 * so for {@code Multi_Gl = 'N'} the hook overwrites {@code currency} on DEFAULTS (what the form
 * shows) and on POST (what is persisted, also for MCP/REST callers).
 */
@Named("glJournalHeaderHandler")
public class GlJournalHeaderHandler implements NeoHandler {

  private static final Logger log = LogManager.getLogger(GlJournalHeaderHandler.class);

  private static final String FIELD_ACCOUNTING_SCHEMA = "accountingSchema";
  private static final String FIELD_MULTI_GL = "multigeneralLedger";
  private static final String SESSION_KEY_ACCT_SCHEMA = "$C_AcctSchema_ID";

  private static final String FIELD_DOCUMENT_ACTION = "documentAction";
  private static final String FIELD_ACCOUNTING_DATE = "accountingDate";
  private static final String FIELD_DOCUMENT_DATE = "documentDate";
  private static final String FIELD_CURRENCY = "currency";
  private static final String SUFFIX_IDENTIFIER = "$_identifier";
  private static final String KEY_DEFAULTS = "defaults";
  private static final String DOC_ACTION_COMPLETE = "CO";
  private static final String DOC_ACTION_REACTIVATE = "RE";
  /** Request parameter {@code FIN_AddPaymentFromJournal} reads the action from. */
  private static final String PARAM_DOC_ACTION = "inpdocaction";
  private static final String PARAM_GL_JOURNAL_ID = "GL_Journal_ID";
  /** AD_Process_ID of "Add Payment From Journal" (FIN_AddPaymentFromJournal) — GL Journal completion. */
  private static final String COMPLETE_PROCESS_ID = "5BE14AA10165490A9ADEFB7532F7FA94";

  @Inject
  private DocumentPostingService postingService;

  /** Package-private seam so unit tests can inject a mocked {@link DocumentPostingService}. */
  void setPostingService(DocumentPostingService postingService) {
    this.postingService = postingService;
  }

  @Override
  public NeoResponse handle(NeoContext context) {
    NeoResponse posting = postingService != null ? postingService.handleAction(context) : null;
    if (posting != null) {
      return posting;
    }
    // Complete/Reactivate run the real core process with a proper context, replacing NEO's
    // contextless dispatch that would NPE inside FIN_AddPaymentFromJournal.
    String docAction = resolveDocAction(context);
    if (docAction != null) {
      return runDocumentAction(context, docAction);
    }
    if (NeoEndpointType.CRUD.equals(context.getEndpointType())
        && NeoHandlerUtils.isWriteMethod(context.getHttpMethod())) {
      NeoHandlerUtils.mirrorFieldValue(context.getRequestBody(), FIELD_ACCOUNTING_DATE, FIELD_DOCUMENT_DATE);
    }
    if (!"POST".equalsIgnoreCase(context.getHttpMethod())) {
      return null;
    }
    JSONObject body = context.getRequestBody();
    if (body == null) {
      return null;
    }
    try {
      // When Multi_Gl = 'Y' the constraint allows a null AcctSchema and the journal may be in any
      // currency — inject nothing.
      String multiGl = body.optString(FIELD_MULTI_GL, "N");
      if ("Y".equalsIgnoreCase(multiGl)) {
        return null;
      }
      // Only inject the schema when it is absent (never overwrite an explicit caller value).
      if (!body.has(FIELD_ACCOUNTING_SCHEMA) || body.isNull(FIELD_ACCOUNTING_SCHEMA)) {
        String acctSchemaId = sessionAcctSchemaId(context);
        if (acctSchemaId != null) {
          body.put(FIELD_ACCOUNTING_SCHEMA, acctSchemaId);
          log.debug("[GL-JOURNAL] Injected accountingSchema={} from session", acctSchemaId);
        } else {
          log.warn("[GL-JOURNAL] No $C_AcctSchema_ID in session — gl_journal_multiacct_check may fire");
        }
      }
      // The currency is always the schema's — the field is read-only in the UI, so a different
      // value can only come from MCP/REST, and the schema currency must win there too.
      String[] currency = resolveSchemaCurrency(body.optString(FIELD_ACCOUNTING_SCHEMA, null));
      if (currency.length == 2) {
        body.put(FIELD_CURRENCY, currency[0]);
      }
    } catch (Exception e) {
      log.warn("[GL-JOURNAL] Could not inject accountingSchema/currency: {}", e.getMessage(), e);
    }
    return null;
  }

  @Override
  public NeoResponse afterHandle(NeoContext context) {
    if (!NeoEndpointType.DEFAULTS.equals(context.getEndpointType())) {
      return null;
    }
    return injectSchemaCurrencyDefault(context);
  }

  /**
   * Overrides {@code defaults.currency} with the session accounting schema currency, so the new
   * journal form shows the currency the POST will persist (not the org-first
   * {@code @C_Currency_ID@}). Returns null — default response untouched — when the session has no
   * schema or the schema has no currency.
   */
  private NeoResponse injectSchemaCurrencyDefault(NeoContext context) {
    NeoResponse previous = context.getPreviousResult();
    if (previous == null || previous.getBody() == null) {
      return null;
    }
    try {
      String[] currency = resolveSchemaCurrency(sessionAcctSchemaId(context));
      if (currency.length != 2) {
        return null;
      }
      JSONObject body = previous.getBody();
      JSONObject defaults = body.optJSONObject(KEY_DEFAULTS);
      if (defaults == null) {
        defaults = new JSONObject();
        body.put(KEY_DEFAULTS, defaults);
      }
      defaults.put(FIELD_CURRENCY, currency[0]);
      defaults.put(FIELD_CURRENCY + SUFFIX_IDENTIFIER, currency[1]);
      return NeoResponse.ok(body);
    } catch (JSONException | RuntimeException e) {
      log.warn("[GL-JOURNAL] Could not inject the schema currency default: {}", e.getMessage(), e);
      return null;
    }
  }

  /** The session accounting schema ({@code $C_AcctSchema_ID}), or null when absent. */
  private static String sessionAcctSchemaId(NeoContext context) {
    VariablesSecureApp vars = NeoDefaultsService.buildVariablesSecureApp(context.getObContext());
    String acctSchemaId = vars.getSessionValue(SESSION_KEY_ACCT_SCHEMA);
    return acctSchemaId == null || acctSchemaId.isEmpty() ? null : acctSchemaId;
  }

  /**
   * {@code {currencyId, isoCode}} of the given accounting schema, or an empty array when the id is
   * blank, the schema does not exist or has no currency. Package-private seam for unit tests.
   */
  String[] resolveSchemaCurrency(String acctSchemaId) {
    if (acctSchemaId == null || acctSchemaId.isEmpty()) {
      return new String[0];
    }
    OBContext.setAdminMode(true);
    try {
      AcctSchema schema = OBDal.getInstance().get(AcctSchema.class, acctSchemaId);
      Currency currency = schema != null ? schema.getCurrency() : null;
      return currency != null ? new String[] { currency.getId(), currency.getISOCode() } : new String[0];
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /**
   * The document action ({@code CO} or {@code RE}) when this is a {@code POST
   * /action/documentAction} request this hook handles — the body carries it either at the root
   * ({@code docAction}/{@code documentAction}) or nested under {@code fieldValues} as sent by the
   * draft-mode confirm button. Null for anything else.
   */
  private static String resolveDocAction(NeoContext context) {
    if (!NeoEndpointType.ACTION.equals(context.getEndpointType())) {
      return null;
    }
    if (!FIELD_DOCUMENT_ACTION.equals(context.getFieldName())) {
      return null;
    }
    JSONObject body = context.getRequestBody();
    if (body == null) {
      return null;
    }
    JSONObject fieldValues = body.optJSONObject("fieldValues");
    String docAction = fieldValues != null
        ? fieldValues.optString(FIELD_DOCUMENT_ACTION, "")
        : body.optString("docAction", body.optString(FIELD_DOCUMENT_ACTION, ""));
    return DOC_ACTION_COMPLETE.equals(docAction) || DOC_ACTION_REACTIVATE.equals(docAction)
        ? docAction
        : null;
  }

  /**
   * Runs the core {@code FIN_AddPaymentFromJournal} process with a {@link ProcessBundle} that
   * carries a {@code ProcessContext} (built from the current OBContext), then translates the
   * {@code OBError} result into a {@link NeoResponse} via the shared classic-result translator.
   */
  private NeoResponse runDocumentAction(NeoContext context, String docAction) {
    String journalId = context.getRecordId();
    if (journalId == null || journalId.isEmpty()) {
      return NeoResponse.error(400, "Missing GL Journal record id for document action " + docAction);
    }
    RequestContext requestContext = RequestContext.get();
    if (requestContext == null || requestContext.getRequest() == null) {
      log.error("[GL-JOURNAL] No HTTP request bound — cannot pass {} to the journal process", docAction);
      return NeoResponse.error(500, "GL Journal document action requires an HTTP request");
    }
    try {
      requestContext.setRequestParameter(PARAM_DOC_ACTION, docAction);
      VariablesSecureApp vars = NeoDefaultsService.buildVariablesSecureApp(context.getObContext());
      ProcessBundle bundle = new ProcessBundle(COMPLETE_PROCESS_ID, vars)
          .init(new DalConnectionProvider(false));
      Map<String, Object> params = new HashMap<>();
      params.put(PARAM_GL_JOURNAL_ID, journalId);
      bundle.setParams(params);
      new FIN_AddPaymentFromJournal().execute(bundle);
      Process process = OBDal.getInstance().get(Process.class, COMPLETE_PROCESS_ID);
      if (process == null) {
        log.error("[GL-JOURNAL] Process record {} not found", COMPLETE_PROCESS_ID);
        return NeoResponse.error(500, "Completion process configuration missing");
      }
      return NeoProcessService.translateClassicResult(bundle.getResult(), process);
    } catch (Exception e) {
      log.error("[GL-JOURNAL] Document action {} failed for id={}: {}", docAction, journalId,
          e.getMessage(), e);
      return NeoResponse.error(500, "GL Journal document action " + docAction + " failed: " + e.getMessage());
    }
  }
}
