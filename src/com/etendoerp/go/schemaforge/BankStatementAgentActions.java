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
 * All portions are Copyright (C) 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */


package com.etendoerp.go.schemaforge;

import static com.etendoerp.go.schemaforge.util.NeoActionContract.Param.array;
import static com.etendoerp.go.schemaforge.util.NeoActionContract.Param.optional;
import static com.etendoerp.go.schemaforge.util.NeoActionContract.Param.required;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.service.OBDal;

import com.etendoerp.go.schemaforge.util.NeoActionContract;

/**
 * The bank-statement actions an agent can run through {@code etendo_action} (ETP-5447, ETP-5469).
 *
 * <p><b>Purely additive.</b> The SPA keeps calling {@code /sws/neo/bank-statements?action=…} — a
 * report-spec request whose context carries no endpoint type — and
 * {@link BankStatementsHandler#handle} only enters {@link #dispatch} for
 * {@code NeoEndpointType.ACTION}, which only {@code etendo_action} produces for this spec. Every action
 * here re-enters the SAME handler method the SPA route uses ({@code handleCreate},
 * {@code handleUpdate}, {@code handleProcess}, …), so the required header dates, the BSF document
 * type, the line amount rules, the draft / processed state machine, the PSD2 delete guard, the
 * rollback and the error mapping are the UI's own. This class only translates the MCP call shape
 * (record {@code id} + {@code parameters} = body), validates it against the declared contract and —
 * for the write paths whose input the SPA shapes on the client — applies the checks the UI performs
 * before it ever sends the request ({@link BankStatementAgentValidation}).</p>
 *
 * <p><b>Why the agent needs these.</b> A generic write on the {@code financial-account} entities
 * {@code importedBankStatements} / {@code bankStatementLines} bypasses all of those rules: a
 * statement created there was never processed (its lines never became reconcilable), could not be
 * processed or reactivated at all, could not be imported from a file, and skipped every validation
 * the UI applies to a manual statement. Those generic writes are refused (405, see
 * {@link BankStatementEntityHandler}) and name the action below that does the job.</p>
 *
 * <p><b>What {@code id} means</b> depends on the action, and each contract states it in its
 * {@code idDescription}: the financial account for the account-level actions (list, preview,
 * create, import), the bank statement for the statement-level ones (lines, update, process,
 * reactivate, delete). The record {@code id} always wins over an id the agent also put in
 * {@code parameters}: the contract refuses such a key as undeclared, and the id is written into the
 * body last.</p>
 */
final class BankStatementAgentActions {

  static final String LIST_STATEMENTS = "listStatements";
  static final String STATEMENT_LINES = "statementLines";
  static final String PREVIEW_STATEMENT = "previewStatement";
  static final String CREATE_STATEMENT = "createStatement";
  static final String IMPORT_STATEMENT = "importStatement";
  static final String UPDATE_STATEMENT = "updateStatement";
  static final String PROCESS_STATEMENT = "processStatement";
  static final String REACTIVATE_STATEMENT = "reactivateStatement";
  static final String DELETE_STATEMENT = "deleteStatement";

  /** Body / query key the SPA uses for the financial account. */
  static final String KEY_ACCOUNT_ID = BankStatementsHandler.PARAM_ACCOUNT_ID;
  /** Query key the SPA uses for the statement whose lines are listed. */
  static final String KEY_STATEMENT_ID = "statementId";
  /** Body key the SPA uses for the statement being edited / processed / reactivated / deleted. */
  static final String KEY_ID = BankStatementsHandler.FIELD_ID;

  static final String P_NAME = BankStatementsHandler.FIELD_NAME;
  static final String P_TRANSACTION_DATE = BankStatementsHandler.FIELD_TRANSACTION_DATE;
  static final String P_IMPORT_DATE = BankStatementsHandler.FIELD_IMPORT_DATE;
  static final String P_FILE_NAME = BankStatementsHandler.FIELD_FILE_NAME;
  static final String P_NOTES = BankStatementsHandler.FIELD_NOTES;
  static final String P_PROCESS = BankStatementsHandler.FIELD_PROCESS;
  static final String P_LINES = BankStatementsHandler.FIELD_LINES;
  static final String P_CONTENT_BASE64 = BankStatementsHandler.FIELD_CONTENT_BASE64;

  private static final String S = NeoActionContract.TYPE_STRING;
  private static final String D = NeoActionContract.TYPE_DATE;
  private static final String B = NeoActionContract.TYPE_BOOLEAN;
  /** Tail of the "at most N characters." parameter descriptions (Sonar S1192). */
  private static final String CHARACTERS_SUFFIX = " characters.";

  private static final String ACCOUNT_ID_DESC =
      "The financial account id (FIN_Financial_Account) the statements belong to.";
  private static final String STATEMENT_ID_DESC =
      "The bank statement id (FIN_BankStatement), as returned by listStatements or by "
          + "createStatement / importStatement.";

  private static final String NAME_DESC = "Statement name, at most "
      + BankStatementAgentValidation.MAX_NAME + CHARACTERS_SUFFIX;
  private static final String TRANSACTION_DATE_DESC =
      "Statement date, the day the bank issued it (yyyy-MM-dd).";
  private static final String IMPORT_DATE_DESC = "Date the statement is registered (yyyy-MM-dd).";
  private static final String NOTES_DESC = "Optional notes, at most "
      + BankStatementAgentValidation.MAX_TEXT + CHARACTERS_SUFFIX;
  private static final String FILE_NAME_DESC = "Optional source file name, at most "
      + BankStatementAgentValidation.MAX_TEXT + CHARACTERS_SUFFIX;
  private static final String LINES_DESC =
      "Lines: {date: yyyy-MM-dd (required), in: amount received, out: amount paid — exactly one "
          + "of them > 0, as a number or a numeric string with a dot decimal separator, "
          + "description (<= " + BankStatementAgentValidation.MAX_DESCRIPTION + " chars), "
          + "reference (<= " + BankStatementAgentValidation.MAX_REFERENCE + " chars; blank is "
          + "stored as '**'), bpartnerName (<= " + BankStatementAgentValidation.MAX_NAME
          + " chars), bpartnerId (a contact id), glItemId (a G/L item id)}. No other keys are "
          + "accepted.";
  private static final String HEADER_DATES_NOTE =
      " Both header dates are REQUIRED and never defaulted to today.";
  private static final String UPLOAD_NOTE =
      " Formats, detected from the content: Cuaderno 43 (AEB norm 43, fixed 80-char records) or "
          + "a CSV whose header row is `Transaction Date, Reference No., Business Partner Name, "
          + "Description, Amount OUT, Amount IN` (dates dd/MM/yyyy). Any other content, invalid "
          + "base64 or an empty file answers 400.";
  /** ETP-5471 — create, import and preview are refused on a bank-connected account. */
  private static final String BANK_CONNECTED_NOTE =
      " Answers 409 when the account is connected to the bank (PSD2): its statements come from "
          + "the bank feed and cannot be created or imported by hand.";
  private static final String UPLOAD_FILE_NAME_DESC =
      "The file name (used as the statement name), e.g. extracto-junio.csv.";
  private static final String CONTENT_DESC = "The file content as standard base64 (RFC 4648 "
      + "alphabet with padding, no line breaks or whitespace). At most "
      + BankStatementAgentValidation.MAX_IMPORT_BYTES / 1024 + " KB of file content through this "
      + "action; larger files must be imported from the Etendo UI.";

  /**
   * The declared actions, in presentation order: the reads first (list, lines, preview), then the
   * writes.
   */
  static final Map<String, NeoActionContract> CONTRACTS = buildContracts();

  /**
   * Where each action lands: the SPA handler method, the key that carries the record id, and
   * whether the call is a GET with the id as its only query param (the list reads) or a POST with
   * the parameters as its body (every other action, including previewStatement, which only reads
   * but needs the upload body).
   */
  private static final Map<String, Route> ROUTES = Map.of(
      LIST_STATEMENTS, Route.query(BankStatementsHandler::handleList, KEY_ACCOUNT_ID),
      STATEMENT_LINES, Route.query(BankStatementsHandler::handleGetLines, KEY_STATEMENT_ID),
      PREVIEW_STATEMENT, Route.body(BankStatementsHandler::handlePreview, KEY_ACCOUNT_ID),
      CREATE_STATEMENT, Route.body(BankStatementsHandler::handleCreate, KEY_ACCOUNT_ID),
      IMPORT_STATEMENT, Route.body(BankStatementsHandler::handleImport, KEY_ACCOUNT_ID),
      UPDATE_STATEMENT, Route.body(BankStatementsHandler::handleUpdate, KEY_ID),
      PROCESS_STATEMENT, Route.body(BankStatementsHandler::handleProcess, KEY_ID),
      REACTIVATE_STATEMENT, Route.body(BankStatementsHandler::handleReactivate, KEY_ID),
      DELETE_STATEMENT, Route.body(BankStatementsHandler::handleDelete, KEY_ID));

  private BankStatementAgentActions() {
  }

  /**
   * Runs one declared action for the record named by the context's record id.
   *
   * @param handler the bank-statements handler (its methods run exactly as on the SPA route)
   * @param context an ACTION context: {@code fieldName} = action, {@code recordId} = financial
   *                account or statement id (see each contract), {@code requestBody} = parameters
   * @return the SPA route's own response, or a 422/403 when the call is refused before it runs
   */
  static NeoResponse dispatch(BankStatementsHandler handler, NeoContext context) {
    String action = context.getFieldName();
    JSONObject params = context.getRequestBody() != null ? context.getRequestBody()
        : new JSONObject();
    NeoResponse invalid = NeoActionContract.validate(CONTRACTS, action, params);
    if (invalid != null) {
      return invalid;
    }
    NeoActionContract contract = CONTRACTS.get(action);
    String recordId = StringUtils.trimToNull(context.getRecordId());
    if (recordId == null) {
      return NeoResponse.error(NeoActionContract.SC_UNPROCESSABLE,
          "id is required: " + contract.getIdDescription());
    }
    // previewStatement only reads, so it is gated as a GET even though it is sent as a POST.
    boolean mutating = contract.isMutating();
    if (!AgentActionSupport.hasAccess(context, mutating)) {
      return NeoResponse.error(HttpServletResponse.SC_FORBIDDEN,
          "Access denied to spec for current role");
    }
    NeoResponse refused = BankStatementAgentValidation.check(handler, action, params);
    if (refused != null) {
      return refused;
    }
    Route route = ROUTES.get(action);
    if (!route.withBody) {
      return route.target.apply(handler, AgentActionSupport.derive(context, "GET", null,
          Map.of(route.idKey, recordId)));
    }
    try {
      JSONObject body = AgentActionSupport.copy(params);
      body.put(route.idKey, recordId);
      NeoResponse answered = route.target.apply(handler,
          AgentActionSupport.derive(context, "POST", body, null));
      // previewStatement rolls its parse back and closes the session itself: nothing to flush.
      return mutating
          ? AgentActionSupport.flushWhileContextIsSet(action, "bank statement", answered,
              () -> OBDal.getInstance().rollbackAndClose())
          : answered;
    } catch (JSONException e) {
      return NeoResponse.error(NeoActionContract.SC_UNPROCESSABLE,
          "Invalid action parameters: " + e.getMessage());
    }
  }

  private static Map<String, NeoActionContract> buildContracts() {
    Map<String, NeoActionContract> map = new LinkedHashMap<>();
    for (NeoActionContract c : readContracts()) {
      map.put(c.getName(), c);
    }
    for (NeoActionContract c : writeContracts()) {
      map.put(c.getName(), c);
    }
    return Collections.unmodifiableMap(map);
  }

  /** The non-mutating actions: nothing they do is persisted. */
  private static List<NeoActionContract> readContracts() {
    return List.of(
        NeoActionContract.read(LIST_STATEMENTS,
            "Lists the account's bank statements, newest import first: id, documentNo, name, "
                + "fileName, importDate, transactionDate, processed (Y = processed, N = draft), "
                + "posted, lineCount, matchedCount, totalIn, totalOut, period and status.")
            .withIdDescription(ACCOUNT_ID_DESC),
        NeoActionContract.read(STATEMENT_LINES,
            "Lists the lines of one statement with their amounts (cramount = money in, dramount "
                + "= money out), counterparty, G/L item and the movements each line is reconciled "
                + "with, if any.")
            .withIdDescription(STATEMENT_ID_DESC),
        NeoActionContract.read(PREVIEW_STATEMENT,
            "Parses a bank statement file for the financial account and returns what "
                + "importStatement would create — format, fileName, lineCount, totalIn, totalOut, "
                + "periodFrom, periodTo, discardedLines and the lines — WITHOUT saving anything. "
                + "Same parameters as importStatement." + UPLOAD_NOTE + BANK_CONNECTED_NOTE,
            required(P_FILE_NAME, S, UPLOAD_FILE_NAME_DESC),
            required(P_CONTENT_BASE64, S, CONTENT_DESC))
            .withIdDescription(ACCOUNT_ID_DESC));
  }

  /** The mutating actions: each one completes or rolls back as a unit. */
  private static List<NeoActionContract> writeContracts() {
    return List.of(
        NeoActionContract.write(CREATE_STATEMENT,
            "Creates a bank statement by hand (header + lines), the same as the UI's manual "
                + "statement. By default it is also processed, so its lines become reconcilable "
                + "through the bank-reconciliation spec; send process=false to keep it as a draft "
                + "and run processStatement later. The document type is always the account's "
                + "bank statement (BSF) type." + HEADER_DATES_NOTE + " Every line needs its date "
                + "and an amount on exactly one side (in OR out, never both, never negative). "
                + "Texts longer than their column are refused, not truncated. Returns 201 with "
                + "{id, name, lineCount, processed}; id is the new statement." + BANK_CONNECTED_NOTE,
            required(P_NAME, S, NAME_DESC),
            required(P_TRANSACTION_DATE, D, TRANSACTION_DATE_DESC),
            required(P_IMPORT_DATE, D, IMPORT_DATE_DESC),
            array(P_LINES, NeoActionContract.TYPE_OBJECT, true, LINES_DESC + " At least one."),
            optional(P_PROCESS, B,
                "true (default) processes the statement right away; false saves it as a draft."),
            optional(P_NOTES, S, NOTES_DESC),
            optional(P_FILE_NAME, S, FILE_NAME_DESC))
            .withIdDescription(ACCOUNT_ID_DESC),
        NeoActionContract.write(IMPORT_STATEMENT,
            "Imports a bank statement file into the financial account, the same as the UI's "
                + "import. It arrives PROCESSED, so its lines are ready to reconcile. Lines with "
                + "no amount are discarded, as in Classic. transactionDate is the last movement "
                + "date of the file (today when no line has a date) and importDate is now. "
                + "Returns 201 with {id, fileName, lineCount, discardedLines}; a file with no "
                + "valid line answers 400 with code NO_VALID_LINES and saves nothing. Run "
                + "previewStatement first to check the content." + UPLOAD_NOTE
                + BANK_CONNECTED_NOTE,
            required(P_FILE_NAME, S, UPLOAD_FILE_NAME_DESC),
            required(P_CONTENT_BASE64, S, CONTENT_DESC))
            .withIdDescription(ACCOUNT_ID_DESC),
        NeoActionContract.write(UPDATE_STATEMENT,
            "Edits a DRAFT bank statement (reactivateStatement a processed one first): sets the "
                + "header and REPLACES its unmatched lines with `lines`. Lines already matched to "
                + "a transaction are kept untouched and must not be resent. The header is sent "
                + "whole: an omitted fileName or notes is cleared." + HEADER_DATES_NOTE
                + " Stays a draft unless process=true. A processed statement answers 400.",
            required(P_NAME, S, NAME_DESC),
            required(P_TRANSACTION_DATE, D, TRANSACTION_DATE_DESC),
            required(P_IMPORT_DATE, D, IMPORT_DATE_DESC),
            array(P_LINES, NeoActionContract.TYPE_OBJECT, false, LINES_DESC
                + " Required unless the statement already has matched lines."),
            optional(P_PROCESS, B,
                "Process the statement after saving. Default false (stays a draft)."),
            optional(P_NOTES, S, NOTES_DESC + " Omitted clears them."),
            optional(P_FILE_NAME, S, FILE_NAME_DESC + " Omitted clears it."))
            .withIdDescription(STATEMENT_ID_DESC),
        NeoActionContract.write(PROCESS_STATEMENT,
            "Processes a draft statement so its lines become reconcilable. Refused when the "
                + "statement is already processed.")
            .withIdDescription(STATEMENT_ID_DESC),
        NeoActionContract.write(REACTIVATE_STATEMENT,
            "Returns a processed statement to draft so it can be edited (updateStatement) or "
                + "deleted (deleteStatement). Reconciled lines stay reconciled. Refused when the "
                + "statement is a draft or is posted.")
            .withIdDescription(STATEMENT_ID_DESC),
        NeoActionContract.write(DELETE_STATEMENT,
            "Permanently deletes a DRAFT bank statement and its lines (reactivateStatement a "
                + "processed one first). Answers 409 when the account is connected to the bank "
                + "(PSD2) — its statements come from the bank feed — and 400 when the statement "
                + "still has lines matched to transactions (undo those reconciliations first) or "
                + "is processed.")
            .withIdDescription(STATEMENT_ID_DESC));
  }

  /** One action's landing point on the SPA handler. */
  private static final class Route {
    private final BiFunction<BankStatementsHandler, NeoContext, NeoResponse> target;
    private final String idKey;
    private final boolean withBody;

    private Route(BiFunction<BankStatementsHandler, NeoContext, NeoResponse> target, String idKey,
        boolean withBody) {
      this.target = target;
      this.idKey = idKey;
      this.withBody = withBody;
    }

    /** A GET whose only query param is the record id. */
    static Route query(BiFunction<BankStatementsHandler, NeoContext, NeoResponse> target,
        String idKey) {
      return new Route(target, idKey, false);
    }

    /** A POST whose body is the parameters plus the record id. */
    static Route body(BiFunction<BankStatementsHandler, NeoContext, NeoResponse> target,
        String idKey) {
      return new Route(target, idKey, true);
    }
  }
}
