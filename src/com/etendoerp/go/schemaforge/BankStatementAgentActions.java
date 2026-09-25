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
 * The bank-statement actions an agent can run through {@code neo_action} (ETP-5469).
 *
 * <p><b>Purely additive.</b> The SPA keeps calling {@code /sws/neo/bank-statements?action=…} — a
 * report-spec request whose context carries no endpoint type — and
 * {@link BankStatementsHandler#handle} only enters {@link #dispatch} for
 * {@code NeoEndpointType.ACTION}, which only {@code neo_action} produces for this spec. Every action
 * here re-enters the SAME handler method the SPA route uses ({@code handleCreate},
 * {@code handleProcess}, …), so the business validations, the rollback and the error mapping are
 * the UI's own. This class only translates the MCP call shape (record {@code id} + {@code parameters}
 * = body), validates it against the declared contract and — for the two write paths whose input
 * the SPA shapes on the client — applies the checks the UI performs before it ever sends the
 * request ({@link BankStatementAgentValidation}).</p>
 *
 * <p><b>Why the agent needs these.</b> Before ETP-5469 an agent could only reach statements through
 * the generic {@code financial-account} entities {@code importedBankStatements} /
 * {@code bankStatementLines}: a statement created there was never processed (its lines never became
 * reconcilable), could not be processed or reactivated at all, could not be imported from a file,
 * and skipped every validation the UI applies to a manual statement.</p>
 *
 * <p><b>What {@code id} means</b> depends on the action, and each contract states it in its
 * {@code idDescription}: the financial account for the account-level actions (list, create,
 * import), the bank statement for the statement-level ones (lines, process, reactivate).</p>
 */
final class BankStatementAgentActions {

  static final String LIST_STATEMENTS = "listStatements";
  static final String STATEMENT_LINES = "statementLines";
  static final String CREATE_STATEMENT = "createStatement";
  static final String IMPORT_STATEMENT = "importStatement";
  static final String PROCESS_STATEMENT = "processStatement";
  static final String REACTIVATE_STATEMENT = "reactivateStatement";

  /** Body / query key the SPA uses for the financial account. */
  static final String KEY_ACCOUNT_ID = "FIN_Financial_Account_ID";
  /** Query key the SPA uses for the statement whose lines are listed. */
  static final String KEY_STATEMENT_ID = "statementId";
  /** Body key the SPA uses for the statement being processed / reactivated. */
  static final String KEY_ID = "id";

  static final String P_NAME = "name";
  static final String P_TRANSACTION_DATE = "transactionDate";
  static final String P_IMPORT_DATE = "importDate";
  static final String P_FILE_NAME = "fileName";
  static final String P_NOTES = "notes";
  static final String P_PROCESS = "process";
  static final String P_LINES = "lines";
  static final String P_CONTENT_BASE64 = "contentBase64";

  private static final String S = NeoActionContract.TYPE_STRING;
  /** Tail of the "at most N characters." parameter descriptions (Sonar S1192). */
  private static final String CHARACTERS_SUFFIX = " characters.";

  private static final String ACCOUNT_ID_DESC =
      "The financial account id (FIN_Financial_Account) the statements belong to.";
  private static final String STATEMENT_ID_DESC =
      "The bank statement id (FIN_BankStatement), as returned by listStatements or by "
          + "createStatement / importStatement.";

  /** The declared actions, in presentation order: read helpers first, then the writes. */
  static final Map<String, NeoActionContract> CONTRACTS = buildContracts();

  /** Where each action lands: the SPA handler method and the key that carries the record id. */
  private static final Map<String, Route> ROUTES = Map.of(
      LIST_STATEMENTS, new Route(BankStatementsHandler::handleList, KEY_ACCOUNT_ID),
      STATEMENT_LINES, new Route(BankStatementsHandler::handleGetLines, KEY_STATEMENT_ID),
      CREATE_STATEMENT, new Route(BankStatementsHandler::handleCreate, KEY_ACCOUNT_ID),
      IMPORT_STATEMENT, new Route(BankStatementsHandler::handleImport, KEY_ACCOUNT_ID),
      PROCESS_STATEMENT, new Route(BankStatementsHandler::handleProcess, KEY_ID),
      REACTIVATE_STATEMENT, new Route(BankStatementsHandler::handleReactivate, KEY_ID));

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
    if (!mutating) {
      return route.target.apply(handler, AgentActionSupport.derive(context, "GET", null,
          Map.of(route.idKey, recordId)));
    }
    try {
      JSONObject body = AgentActionSupport.copy(params);
      body.put(route.idKey, recordId);
      NeoResponse written = route.target.apply(handler,
          AgentActionSupport.derive(context, "POST", body, null));
      return AgentActionSupport.flushWhileContextIsSet(action, "bank statement", written,
          () -> OBDal.getInstance().rollbackAndClose());
    } catch (JSONException e) {
      return NeoResponse.error(NeoActionContract.SC_UNPROCESSABLE,
          "Invalid action parameters: " + e.getMessage());
    }
  }

  private static Map<String, NeoActionContract> buildContracts() {
    Map<String, NeoActionContract> map = new LinkedHashMap<>();
    for (NeoActionContract c : List.of(
        NeoActionContract.read(LIST_STATEMENTS,
            "Lists the account's bank statements, newest import first: id, documentNo, name, "
                + "fileName, importDate, transactionDate, processed (Y = processed, N = draft), "
                + "posted, lineCount, matchedCount, totalIn, totalOut, period and status.")
            .withIdDescription(ACCOUNT_ID_DESC),
        NeoActionContract.read(STATEMENT_LINES,
            "Lists the lines of one statement with their amounts (cramount = money in, dramount "
                + "= money out), counterparty, G/L item and the movements each line is reconciled "
                + "with, if any.")
            .withIdDescription(STATEMENT_ID_DESC))) {
      map.put(c.getName(), c);
    }
    for (NeoActionContract c : writeContracts()) {
      map.put(c.getName(), c);
    }
    return Collections.unmodifiableMap(map);
  }

  /** The mutating actions: each one completes or rolls back as a unit. */
  private static List<NeoActionContract> writeContracts() {
    return List.of(
        NeoActionContract.write(CREATE_STATEMENT,
            "Creates a bank statement by hand (header + lines), the same as the UI's manual "
                + "statement. By default it is also processed, so its lines become reconcilable "
                + "through the bank-reconciliation spec; send process=false to keep it as a draft "
                + "and run processStatement later. Every line needs its date and an amount on "
                + "exactly one side (in OR out, never both, never negative). Texts longer than "
                + "their column are refused, not truncated. Returns {id, name, lineCount, "
                + "processed}.",
            required(P_NAME, S, "Statement name, at most "
                + BankStatementAgentValidation.MAX_NAME + CHARACTERS_SUFFIX),
            optional(P_TRANSACTION_DATE, NeoActionContract.TYPE_DATE,
                "Statement date (yyyy-MM-dd). Defaults to today."),
            optional(P_IMPORT_DATE, NeoActionContract.TYPE_DATE,
                "Import date (yyyy-MM-dd). Defaults to today."),
            optional(P_FILE_NAME, S, "Optional source file name, at most "
                + BankStatementAgentValidation.MAX_TEXT + CHARACTERS_SUFFIX),
            optional(P_NOTES, S, "Optional notes, at most "
                + BankStatementAgentValidation.MAX_TEXT + CHARACTERS_SUFFIX),
            optional(P_PROCESS, NeoActionContract.TYPE_BOOLEAN,
                "true (default) processes the statement right away; false saves it as a draft."),
            array(P_LINES, NeoActionContract.TYPE_OBJECT, true,
                "At least one line: {date: yyyy-MM-dd (required), in: amount received, out: "
                    + "amount paid — exactly one of them > 0, as a number or a numeric string "
                    + "with a dot decimal separator, description (<= "
                    + BankStatementAgentValidation.MAX_DESCRIPTION + " chars), reference (<= "
                    + BankStatementAgentValidation.MAX_REFERENCE + " chars; blank is stored as "
                    + "'**'), bpartnerName (<= " + BankStatementAgentValidation.MAX_NAME
                    + " chars), bpartnerId (a contact id), glItemId (a G/L item id)}. No other "
                    + "keys are accepted.")).withIdDescription(ACCOUNT_ID_DESC),
        NeoActionContract.write(IMPORT_STATEMENT,
            "Imports a bank statement file and processes it, the same as the UI's import: a "
                + "Cuaderno 43 file, or a CSV whose header has the columns Transaction Date "
                + "(dd/MM/yyyy), Reference No., Business Partner Name, Amount OUT, Amount IN, "
                + "Description. Lines with no amount are discarded, as in Classic. Returns {id, "
                + "fileName, lineCount, discardedLines}; an all-empty file fails with "
                + "NO_VALID_LINES.",
            required(P_FILE_NAME, S, "The file name (used as the statement name)."),
            required(P_CONTENT_BASE64, S, "The file content as standard base64 (RFC 4648 "
                + "alphabet with padding, no line breaks or whitespace). At most "
                + BankStatementAgentValidation.MAX_IMPORT_BYTES / 1024 + " KB of file content "
                + "through this action; larger files must be imported from the Etendo GO UI."))
            .withIdDescription(ACCOUNT_ID_DESC),
        NeoActionContract.write(PROCESS_STATEMENT,
            "Processes a draft statement so its lines become reconcilable. Refused when the "
                + "statement is already processed.").withIdDescription(STATEMENT_ID_DESC),
        NeoActionContract.write(REACTIVATE_STATEMENT,
            "Returns a processed statement to draft. Reconciled lines stay reconciled. Refused "
                + "when the statement is a draft or is posted.")
            .withIdDescription(STATEMENT_ID_DESC));
  }

  /** One action's landing point on the SPA handler. */
  private static final class Route {
    private final BiFunction<BankStatementsHandler, NeoContext, NeoResponse> target;
    private final String idKey;

    Route(BiFunction<BankStatementsHandler, NeoContext, NeoResponse> target, String idKey) {
      this.target = target;
      this.idKey = idKey;
    }
  }
}
