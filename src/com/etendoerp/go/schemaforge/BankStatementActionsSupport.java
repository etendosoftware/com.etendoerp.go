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
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import javax.inject.Inject;
import javax.inject.Named;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;

import com.etendoerp.go.schemaforge.util.NeoActionContract;
import com.etendoerp.go.schemaforge.util.NeoReportParam;

/**
 * Exposes the bank-statement operations of {@link BankStatementsHandler} as NAMED actions of the
 * {@code financial-account} W spec (ETP-5447), so an agent reaches them with
 * {@code neo_action {spec:"financial-account", entity, id, action, parameters}} — or REST with
 * {@code /sws/neo/financial-account/<entity>/<id>/action/<name>} — instead of through the generic
 * CRUD of {@code importedBankStatements} / {@code bankStatementLines}.
 *
 * <p><b>Writes only.</b> Every action here changes data (or, for {@code previewStatement}, parses an
 * upload) and is called with POST. Reads stay on the generic path: {@code neo_list} /
 * {@code neo_get} on {@code financial-account/importedBankStatements} and
 * {@code bankStatementLines} already return the statements (with their persisted
 * {@code EM_ETGO_*} aggregates) and their lines, and {@link BankStatementEntityHandler} lets reads
 * pass through — so there is no named read action to keep in sync with them.</p>
 *
 * <p><b>Why an adapter and not a second implementation.</b> {@link BankStatementsHandler} is the
 * one engine behind the SPA's {@code /sws/neo/bank-statements?action=...} endpoint: it enforces the
 * required header dates, the account's BSF document type, the line amount rules, the draft /
 * processed state machine and the PSD2 delete guard. This class does not re-implement any of it —
 * it translates an action request into the exact request the SPA sends (the {@code action} query
 * param, the ids the engine reads, the body) and hands that to the engine unchanged. The SPA keeps
 * calling the engine directly; both paths therefore answer identically.</p>
 *
 * <p>Two entities carry actions. On {@code account} the record id is the
 * {@code FIN_Financial_Account} id and the actions create, preview or import statements for it; on
 * {@code importedBankStatements} the record id is the {@code FIN_BankStatement} id and the actions
 * act on that one statement. The record id always wins over any id the agent also sent in the
 * parameters, so an action can never be pointed at a record other than the one it was called on.</p>
 *
 * <p>Not a {@link NeoHandler}: {@link FinancialAccountHandler} and
 * {@link BankStatementEntityHandler} call it from their own pre-hooks.</p>
 */
@Named("bankStatementActionsSupport")
public class BankStatementActionsSupport {

  private static final Logger log = LogManager.getLogger(BankStatementActionsSupport.class);

  static final String ENTITY_ACCOUNT = "account";
  static final String ENTITY_STATEMENTS = "importedBankStatements";

  // Account actions (record id = FIN_Financial_Account id).
  static final String ACTION_CREATE_STATEMENT = "createStatement";
  static final String ACTION_IMPORT_STATEMENT = "importStatement";
  static final String ACTION_PREVIEW_STATEMENT = "previewStatement";

  // Statement actions (record id = FIN_BankStatement id).
  static final String ACTION_PROCESS = "process";
  static final String ACTION_REACTIVATE = "reactivate";
  static final String ACTION_DELETE = "delete";
  static final String ACTION_UPDATE = "update";

  // The `action` query-param values BankStatementsHandler routes on.
  private static final String ENGINE_CREATE = "create";
  private static final String ENGINE_IMPORT = "import";
  private static final String ENGINE_PREVIEW = "preview";

  private static final String METHOD_POST = NeoActionContract.METHOD_POST;

  // The request keys BankStatementsHandler reads — same literals as its own constants.
  private static final String PARAM_ACTION = "action";
  private static final String PARAM_ACCOUNT_ID = "FIN_Financial_Account_ID";
  private static final String FIELD_ID = "id";

  private static final String DATE_SHAPE =
      "Date as yyyy-MM-dd (an ISO date-time such as 2026-06-04T00:00:00Z is also accepted;"
          + " only the calendar day is kept).";
  private static final String LINES_DESCRIPTION =
      "The statement lines, at least one. Each item is an object: {date, description,"
          + " bpartnerName, bpartnerId?, glItemId?, reference?, in, out}. `date` is the"
          + " movement date (yyyy-MM-dd; defaults to the header transactionDate when omitted)."
          + " `in` is money received (deposit) and `out` money paid (withdrawal): both are"
          + " non-negative numbers and EXACTLY ONE of them must be greater than zero — a negative"
          + " amount, a zero line or a line with both sides filled is rejected with 400."
          + " `description` (max 2000 chars) and `bpartnerName` (free-text counterparty, max 60)"
          + " are optional text. `bpartnerId` (C_BPartner id) and `glItemId` (C_GLItem id) are"
          + " optional links; an id that does not exist is silently ignored. `reference`"
          + " (max 30) is optional and stored as `**` when omitted. Fully blank rows are skipped.";
  private static final String NAME_DESCRIPTION = "Statement name shown in the list (max 60 chars).";
  private static final String HEADER_DATES_NOTE =
      " Both header dates (transactionDate, importDate) are REQUIRED: a missing or unparseable"
          + " one answers 400 `Missing required field: <field>` — they are never defaulted to"
          + " today.";
  private static final String UPLOAD_NOTE =
      " Supported formats, detected from the content: Cuaderno 43 (AEB norm 43, fixed 80-char"
          + " records starting with 11/22/33/99) and a generic CSV whose header row carries at"
          + " least two of: Transaction Date, Amount IN, Amount OUT, Reference No., Business"
          + " Partner Name, Description. Any other content answers 400 (unsupported format).";

  @Inject
  private BankStatementsHandler bankStatementsHandler;

  /** Package-private seam so unit tests can supply a mocked engine. */
  void setBankStatementsHandler(BankStatementsHandler bankStatementsHandler) {
    this.bankStatementsHandler = bankStatementsHandler;
  }

  /**
   * One named action, resolved to the request the engine expects.
   *
   * <p>Every route is a POST to the engine. {@code idKey} names the body key the record id goes
   * into. {@code passParameters} copies the agent's parameters into the body; the pure id actions
   * (process, reactivate, delete) ignore them so a stray key cannot reach the engine.</p>
   */
  private static final class Route {
    final String engineAction;
    final String idKey;
    final boolean passParameters;

    Route(String engineAction, String idKey, boolean passParameters) {
      this.engineAction = engineAction;
      this.idKey = idKey;
      this.passParameters = passParameters;
    }
  }

  /**
   * Answers a named bank-statement action, or {@code null} when the request is not one.
   *
   * @param context the entity handler's pre-hook context
   * @return the engine's response (whole, as the SPA would get it); a 400 when the record id is
   *         blank; a 405 when the action is called with any method but POST; {@code null} when the
   *         request is not an ACTION or names no action this class knows for the context's entity
   */
  public NeoResponse handle(NeoContext context) {
    if (context == null || !NeoEndpointType.ACTION.equals(context.getEndpointType())) {
      return null;
    }
    Route route = resolve(context.getEntityName(), context.getFieldName());
    if (route == null) {
      return null;
    }
    String action = context.getFieldName();
    if (StringUtils.isBlank(context.getRecordId())) {
      return NeoResponse.error(400, "Action '" + action + "' needs the record id: call it on"
          + " the " + recordNoun(context.getEntityName()) + " it applies to.");
    }
    // Every action is POST-only: a write must never fire from a GET (a crawler or a prefetch
    // would create or delete statements), and previewStatement carries an upload body.
    if (!METHOD_POST.equalsIgnoreCase(context.getHttpMethod())) {
      return NeoResponse.error(405, "Action '" + action + "' must be called with POST.");
    }
    try {
      return bankStatementsHandler.handle(buildEngineContext(context, route));
    } catch (JSONException e) {
      log.warn("Could not build the bank-statement request for action {}: {}", action,
          e.getMessage());
      return NeoResponse.error(400, "Invalid parameters for action '" + action + "'.");
    }
  }

  /**
   * Resolves an entity + action name to its engine route.
   *
   * @return the route, or {@code null} when the entity has no such action
   */
  private static Route resolve(String entityName, String action) {
    if (StringUtils.isBlank(action)) {
      return null;
    }
    if (ENTITY_ACCOUNT.equals(entityName)) {
      switch (action) {
        case ACTION_CREATE_STATEMENT:
          return new Route(ENGINE_CREATE, PARAM_ACCOUNT_ID, true);
        case ACTION_IMPORT_STATEMENT:
          return new Route(ENGINE_IMPORT, PARAM_ACCOUNT_ID, true);
        case ACTION_PREVIEW_STATEMENT:
          return new Route(ENGINE_PREVIEW, PARAM_ACCOUNT_ID, true);
        default:
          return null;
      }
    }
    if (ENTITY_STATEMENTS.equals(entityName)) {
      switch (action) {
        case ACTION_PROCESS:
        case ACTION_REACTIVATE:
        case ACTION_DELETE:
          // The engine action names are the same words.
          return new Route(action, FIELD_ID, false);
        case ACTION_UPDATE:
          return new Route(ACTION_UPDATE, FIELD_ID, true);
        default:
          return null;
      }
    }
    return null;
  }

  /**
   * Builds the request the SPA would send to {@code /sws/neo/bank-statements} for this action.
   * Spec, entity, audit context and the MCP-origin flag are carried over; the endpoint type is
   * CRUD because that is what the engine sees from the SPA.
   */
  private static NeoContext buildEngineContext(NeoContext context, Route route)
      throws JSONException {
    Map<String, String> queryParams = new HashMap<>();
    queryParams.put(PARAM_ACTION, route.engineAction);
    JSONObject body = route.passParameters ? copyOf(context.getRequestBody()) : new JSONObject();
    // The record the action was called on always wins over an id sent in the parameters.
    body.put(route.idKey, context.getRecordId());
    return NeoContext.builder()
        .specName(context.getSpecName())
        .entityName(context.getEntityName())
        .httpMethod(METHOD_POST)
        .recordId(context.getRecordId())
        .requestBody(body)
        .queryParams(queryParams)
        .adTab(context.getAdTab())
        .sfEntity(context.getSfEntity())
        .obContext(context.getObContext())
        .mcpOrigin(context.isMcpOrigin())
        .endpointType(NeoEndpointType.CRUD)
        .build();
  }

  /** A shallow copy, so the id overwrite never mutates the caller's body. */
  private static JSONObject copyOf(JSONObject source) throws JSONException {
    JSONObject copy = new JSONObject();
    if (source == null) {
      return copy;
    }
    Iterator<?> keys = source.keys();
    while (keys.hasNext()) {
      String key = String.valueOf(keys.next());
      copy.put(key, source.get(key));
    }
    return copy;
  }

  private static String recordNoun(String entityName) {
    return ENTITY_ACCOUNT.equals(entityName) ? "financial account" : "bank statement";
  }

  /**
   * The named actions an entity serves, for the MCP catalog.
   *
   * @param entityName {@code account} or {@code importedBankStatements}
   * @return the declared actions; empty for any other entity
   */
  public List<NeoActionContract> declaredActions(String entityName) {
    if (ENTITY_ACCOUNT.equals(entityName)) {
      return List.of(createStatementContract(), previewStatementContract(),
          importStatementContract());
    }
    if (ENTITY_STATEMENTS.equals(entityName)) {
      return List.of(updateContract(), processContract(), reactivateContract(),
          deleteContract());
    }
    return List.of();
  }

  private static NeoActionContract createStatementContract() {
    return NeoActionContract.builder(ACTION_CREATE_STATEMENT)
        .description("Creates a bank statement by hand (header + lines) on this financial"
            + " account — the id is the FIN_Financial_Account id. By default it is also"
            + " processed, so its lines become available for reconciliation; send"
            + " process=false to keep it as a draft (then `process` it later on"
            + " importedBankStatements). The document type is always the account's bank"
            + " statement (BSF) type." + HEADER_DATES_NOTE
            + " Returns 201 with {id, name, lineCount, processed}; `id` is the new"
            + " importedBankStatements record.")
        .param(NeoReportParam.required("name", NeoReportParam.TYPE_STRING, NAME_DESCRIPTION))
        .param(NeoReportParam.required("transactionDate", NeoReportParam.TYPE_DATE,
            "Statement date (the day the bank issued it). " + DATE_SHAPE))
        .param(NeoReportParam.required("importDate", NeoReportParam.TYPE_DATE,
            "Date the statement is registered. " + DATE_SHAPE))
        .param(NeoReportParam.required("lines", NeoReportParam.TYPE_ARRAY, LINES_DESCRIPTION))
        .param(NeoReportParam.optional("process", NeoReportParam.TYPE_BOOLEAN,
            "Process the statement after saving. Default true; false saves a draft."))
        .param(NeoReportParam.optional("fileName", NeoReportParam.TYPE_STRING,
            "Optional source file name to record on the statement (max 255)."))
        .param(NeoReportParam.optional("notes", NeoReportParam.TYPE_STRING,
            "Optional free-text notes (max 255)."))
        .build();
  }

  private static NeoActionContract previewStatementContract() {
    return NeoActionContract.builder(ACTION_PREVIEW_STATEMENT)
        .description("Parses a bank statement file for this financial account and returns"
            + " what importStatement would create — format, lineCount, totalIn, totalOut,"
            + " periodFrom, periodTo and the lines — WITHOUT saving anything. Same parameters"
            + " as importStatement." + UPLOAD_NOTE)
        .readOnly(true)
        .param(fileNameParam())
        .param(contentParam())
        .build();
  }

  private static NeoActionContract importStatementContract() {
    return NeoActionContract.builder(ACTION_IMPORT_STATEMENT)
        .description("Imports a bank statement file into this financial account — the id is"
            + " the FIN_Financial_Account id — and processes it, so its lines are ready to"
            + " reconcile. Lines with no amount are dropped. Returns 201 with {id, fileName,"
            + " lineCount, discardedLines}; a file with no valid line answers 400 with code"
            + " NO_VALID_LINES and saves nothing. Run previewStatement first to check the"
            + " content." + UPLOAD_NOTE)
        .param(fileNameParam())
        .param(contentParam())
        .build();
  }

  private static NeoReportParam fileNameParam() {
    return NeoReportParam.required("fileName", NeoReportParam.TYPE_STRING,
        "Name of the uploaded file, recorded on the statement (e.g. extracto-junio.csv).");
  }

  private static NeoReportParam contentParam() {
    return NeoReportParam.required("contentBase64", NeoReportParam.TYPE_STRING,
        "The whole file content, Base64-encoded. Invalid Base64 or an empty file answers 400.");
  }

  private static NeoActionContract updateContract() {
    return NeoActionContract.builder(ACTION_UPDATE)
        .description("Edits a DRAFT bank statement (reactivate a processed one first): sets"
            + " the header and REPLACES its unmatched lines with `lines`. Lines already matched"
            + " to a transaction are kept untouched and must not be resent; `lines` may be"
            + " empty only when such matched lines remain. The header is sent whole: name and"
            + " both dates are required, and an omitted fileName or notes is cleared."
            + HEADER_DATES_NOTE + " By default the statement stays a draft; send process=true"
            + " to process it after saving. A processed statement answers 400.")
        .param(NeoReportParam.required("name", NeoReportParam.TYPE_STRING, NAME_DESCRIPTION))
        .param(NeoReportParam.required("transactionDate", NeoReportParam.TYPE_DATE,
            "Statement date. " + DATE_SHAPE))
        .param(NeoReportParam.required("importDate", NeoReportParam.TYPE_DATE,
            "Registration date. " + DATE_SHAPE))
        .param(NeoReportParam.optional("lines", NeoReportParam.TYPE_ARRAY, LINES_DESCRIPTION
            + " Required unless the statement already has matched lines."))
        .param(NeoReportParam.optional("process", NeoReportParam.TYPE_BOOLEAN,
            "Process the statement after saving. Default false (stays a draft)."))
        .param(NeoReportParam.optional("fileName", NeoReportParam.TYPE_STRING,
            "Source file name (max 255); omitted clears it."))
        .param(NeoReportParam.optional("notes", NeoReportParam.TYPE_STRING,
            "Free-text notes (max 255); omitted clears them."))
        .build();
  }

  private static NeoActionContract processContract() {
    return NeoActionContract.builder(ACTION_PROCESS)
        .description("Processes a DRAFT bank statement so its lines become available for"
            + " reconciliation. No parameters. A statement that is already processed answers"
            + " 400.")
        .build();
  }

  private static NeoActionContract reactivateContract() {
    return NeoActionContract.builder(ACTION_REACTIVATE)
        .description("Returns a PROCESSED bank statement to draft so it can be edited"
            + " (update) or deleted. Reconciliations are not reversed: matched lines stay"
            + " matched. No parameters. A draft or a posted statement answers 400.")
        .build();
  }

  private static NeoActionContract deleteContract() {
    return NeoActionContract.builder(ACTION_DELETE)
        .description("Permanently deletes a DRAFT bank statement and its lines (reactivate a"
            + " processed one first). No parameters. Answers 409 when the account is connected"
            + " to the bank (PSD2) — its statements come from the bank feed and cannot be"
            + " deleted — and 400 when the statement still has lines matched to transactions"
            + " (unreconcile them first) or is processed.")
        .build();
  }
}
