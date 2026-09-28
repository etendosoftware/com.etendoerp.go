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
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.service.OBDal;

import com.etendoerp.go.schemaforge.data.SFSpec;
import com.etendoerp.go.schemaforge.util.NeoAccessHelper;
import com.etendoerp.go.schemaforge.util.NeoActionContract;

/**
 * The bank-statement actions an agent can run through {@code neo_action} (ETP-5447), on the
 * {@code bank-statements} report spec — the same mechanism as {@link ReconciliationAgentActions}
 * (ETP-5468).
 *
 * <p><b>Purely additive.</b> The SPA keeps calling {@code /sws/neo/bank-statements?action=…} — a
 * report-spec request whose context carries no endpoint type — and
 * {@link BankStatementsHandler#handle} only enters {@link #dispatch} for
 * {@code NeoEndpointType.ACTION}, which only {@code neo_action} produces for this spec. Every action here is translated into the EXACT request the SPA sends
 * (the {@code action} query param, the body keys the engine reads) and handed to the unchanged
 * engine, so the required header dates, the BSF document type, the line amount rules, the draft /
 * processed state machine and the PSD2 delete guard are the UI's own. This class only translates
 * the MCP call shape (record {@code id} = financial account or statement, {@code parameters} =
 * body) and validates it against the declared contract.</p>
 *
 * <p><b>Why the agent gets these instead of generic CRUD.</b> A generic write on
 * {@code financial-account/importedBankStatements} or {@code bankStatementLines} bypasses all of
 * those rules, so {@link BankStatementEntityHandler} refuses it (405) and names the action below
 * that does the job.</p>
 *
 * <p>The record {@code id} always wins over an id the agent also put in {@code parameters}: the
 * contract refuses such a key as undeclared, and the id is written into the engine body last.</p>
 */
final class BankStatementAgentActions {

  // Account-level actions (id = FIN_Financial_Account id).
  static final String CREATE_STATEMENT = "createStatement";
  static final String PREVIEW_STATEMENT = "previewStatement";
  static final String IMPORT_STATEMENT = "importStatement";
  // Statement-level actions (id = FIN_BankStatement id). The `Statement` suffix keeps them
  // unambiguous in the one flat catalog they share with the account-level actions.
  static final String UPDATE_STATEMENT = "updateStatement";
  static final String PROCESS_STATEMENT = "processStatement";
  static final String REACTIVATE_STATEMENT = "reactivateStatement";
  static final String DELETE_STATEMENT = "deleteStatement";

  private static final String S = NeoActionContract.TYPE_STRING;
  private static final String D = NeoActionContract.TYPE_DATE;
  private static final String B = NeoActionContract.TYPE_BOOLEAN;
  private static final String P_NAME = BankStatementsHandler.FIELD_NAME;
  private static final String P_TRANSACTION_DATE = BankStatementsHandler.FIELD_TRANSACTION_DATE;
  private static final String P_IMPORT_DATE = BankStatementsHandler.FIELD_IMPORT_DATE;
  private static final String P_LINES = BankStatementsHandler.FIELD_LINES;
  private static final String P_PROCESS = BankStatementsHandler.FIELD_PROCESS;
  private static final String P_NOTES = BankStatementsHandler.FIELD_NOTES;
  private static final String P_FILE_NAME = BankStatementsHandler.FIELD_FILE_NAME;
  private static final String P_CONTENT = BankStatementsHandler.FIELD_CONTENT_BASE64;

  /** What {@code id} identifies for the account-level actions. */
  private static final String ACCOUNT_ID_DESC =
      "The financial account id (FIN_Financial_Account) the statement belongs to.";
  /** What {@code id} identifies for the statement-level actions. */
  private static final String STATEMENT_ID_DESC =
      "The bank statement id (FIN_BankStatement): the id returned by createStatement / "
          + "importStatement, or a row of neo_list on financial-account/importedBankStatements.";
  private static final String ACCOUNT_NOUN = "the financial account id";
  private static final String STATEMENT_NOUN = "the bank statement id";

  private static final String NAME_DESC = "Statement name shown in the list (max 60 chars).";
  private static final String TRANSACTION_DATE_DESC =
      "Statement date, the day the bank issued it (yyyy-MM-dd).";
  private static final String IMPORT_DATE_DESC =
      "Date the statement is registered (yyyy-MM-dd).";
  private static final String NOTES_DESC = "Free-text notes (max 255).";
  private static final String LINES_DESC =
      "The statement lines. Each item is an object {date, description, bpartnerName, "
          + "bpartnerId?, glItemId?, reference?, in, out}. `date` is the movement date "
          + "(yyyy-MM-dd; defaults to the header transactionDate when omitted). `in` is money "
          + "received and `out` money paid: both are non-negative numbers and EXACTLY ONE of them "
          + "must be greater than zero — a negative amount, a zero line or a line with both sides "
          + "filled is refused with 400. `description` (max 2000) and `bpartnerName` (free-text "
          + "counterparty, max 60) are optional. `bpartnerId` (C_BPartner) and `glItemId` "
          + "(C_GLItem) are optional links; an id that does not exist is ignored. `reference` "
          + "(max 30) is optional and stored as `**` when omitted. Fully blank rows are skipped.";
  private static final String HEADER_DATES_NOTE =
      " Both header dates are REQUIRED and never defaulted to today.";
  private static final String UPLOAD_NOTE =
      " Formats, detected from the content: Cuaderno 43 (AEB norm 43, fixed 80-char records) or "
          + "a generic CSV whose header row is `Transaction Date, Reference No., Business Partner "
          + "Name, Description, Amount OUT, Amount IN` (dates dd/MM/yyyy). Any other content, "
          + "invalid Base64 or an empty file answers 400.";
  private static final String FILE_NAME_UPLOAD_DESC =
      "Name of the uploaded file, recorded on the statement (e.g. extracto-junio.csv).";
  private static final String CONTENT_DESC = "The whole file content, Base64-encoded.";
  private static final String ID_ONLY_NOTE = " No parameters: the statement is the id.";

  /** The declared actions, in presentation order: account-level first, then statement-level. */
  static final Map<String, NeoActionContract> CONTRACTS = buildContracts();

  /** How each action maps onto the engine request the SPA sends. */
  private static final Map<String, Route> ROUTES = Map.of(
      CREATE_STATEMENT, Route.account(BankStatementsHandler.ACTION_CREATE),
      PREVIEW_STATEMENT, Route.account(BankStatementsHandler.ACTION_PREVIEW),
      IMPORT_STATEMENT, Route.account(BankStatementsHandler.ACTION_IMPORT),
      UPDATE_STATEMENT, new Route(BankStatementsHandler.ACTION_UPDATE, true),
      PROCESS_STATEMENT, new Route(BankStatementsHandler.ACTION_PROCESS, false),
      REACTIVATE_STATEMENT, new Route(BankStatementsHandler.ACTION_REACTIVATE, false),
      DELETE_STATEMENT, new Route(BankStatementsHandler.ACTION_DELETE, false));

  /** Same give-up point as Core's {@code SessionHandler#flushRemainingChanges}. */
  private static final int MAX_FLUSHES = 100;

  private static final Logger log = LogManager.getLogger(BankStatementAgentActions.class);

  private BankStatementAgentActions() {
  }

  /**
   * One action resolved to the engine request: the engine {@code action}, the body key the record
   * id goes into, and whether the agent's parameters are copied into the body (the id-only actions
   * send {@code {id}} and nothing else, exactly as the SPA does).
   */
  private static final class Route {
    final String engineAction;
    final String idKey;
    final String idNoun;
    final boolean passParameters;

    /** A statement-level route: the id goes into the body's {@code id}. */
    Route(String engineAction, boolean passParameters) {
      this(engineAction, BankStatementsHandler.FIELD_ID, STATEMENT_NOUN, passParameters);
    }

    private Route(String engineAction, String idKey, String idNoun, boolean passParameters) {
      this.engineAction = engineAction;
      this.idKey = idKey;
      this.idNoun = idNoun;
      this.passParameters = passParameters;
    }

    /** An account-level route: parameters are the body, the id is FIN_Financial_Account_ID. */
    static Route account(String engineAction) {
      return new Route(engineAction, BankStatementsHandler.PARAM_ACCOUNT_ID, ACCOUNT_NOUN, true);
    }
  }

  /**
   * Runs one declared action for the record named by the context's record id.
   *
   * @param handler the bank-statements engine (re-entered exactly as on the SPA route)
   * @param context an ACTION context: {@code fieldName} = action, {@code recordId} = financial
   *                account or statement id (see each contract's {@code idDescription}),
   *                {@code requestBody} = the action parameters
   * @return the engine's own response, or a 422/403 when the call is refused before it runs
   */
  static NeoResponse dispatch(BankStatementsHandler handler, NeoContext context) {
    String action = context.getFieldName();
    JSONObject params = context.getRequestBody() != null ? context.getRequestBody()
        : new JSONObject();
    NeoResponse invalid = NeoActionContract.validate(CONTRACTS, action, params);
    if (invalid != null) {
      return invalid;
    }
    Route route = ROUTES.get(action);
    String recordId = StringUtils.trimToNull(context.getRecordId());
    if (recordId == null) {
      return NeoResponse.error(NeoActionContract.SC_UNPROCESSABLE,
          "id (" + route.idNoun + ") is required");
    }
    boolean mutating = CONTRACTS.get(action).isMutating();
    if (!hasAccess(context, mutating)) {
      return NeoResponse.error(HttpServletResponse.SC_FORBIDDEN,
          "Access denied to spec for current role");
    }
    try {
      JSONObject body = route.passParameters ? copy(params) : new JSONObject();
      body.put(route.idKey, recordId);
      NeoResponse response = handler.handle(derive(context, route.engineAction, body));
      // previewStatement is a read: the engine rolls its parse back and closes the session, so
      // there is nothing to flush.
      return mutating ? flushWhileContextIsSet(action, response) : response;
    } catch (JSONException e) {
      return NeoResponse.error(NeoActionContract.SC_UNPROCESSABLE,
          "Invalid action parameters: " + e.getMessage());
    }
  }

  /**
   * Flushes the session to a clean state while the caller's {@code OBContext} is still set, and
   * turns a flush failure into a rolled-back JSON error.
   *
   * <p><b>Needed here for the same reason as in {@link ReconciliationAgentActions}.</b> The engine
   * methods do flush before returning, and roll back and close on their own errors — but a flush
   * fires business event handlers that can change data again, which is why Core flushes until the
   * session is clean ({@code SessionHandler#flushRemainingChanges}). The SPA request gets those
   * extra flushes from {@code DalRequestFilter} with its {@code OBContext} still in place. An MCP
   * tool runs inside {@code McpSessionManager#executeInContext}, which flushes ONCE and restores
   * the previous (null) {@code OBContext}; anything left dirty is flushed by
   * {@code DalThreadCleaner} at request end with no context, fails with a NullPointerException in
   * {@code OBInterceptor}, and the agent gets a Tomcat HTML 500 after being told the write
   * succeeded. That defect lives in the generic MCP session scope, not in the reconciliation
   * engine, so it applies to these writes too — processing and reactivating a statement save the
   * header several times and fire the APRM statement observers. On a clean session the loop is a
   * no-op. An error response is returned untouched: the engine already rolled it back.</p>
   */
  private static NeoResponse flushWhileContextIsSet(String action, NeoResponse written) {
    if (written == null || written.getHttpStatus() >= HttpServletResponse.SC_BAD_REQUEST) {
      return written;
    }
    try {
      for (int flushes = 0; flushes < MAX_FLUSHES
          && OBDal.getInstance().getSession().isDirty(); flushes++) {
        OBDal.getInstance().flush();
      }
      return written;
    } catch (Exception e) {
      log.error("{}: could not persist the bank statement changes; rolled back", action, e);
      OBDal.getInstance().rollbackAndClose();
      return NeoResponse.error(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
          "The bank statement changes could not be saved and were rolled back: "
              + StringUtils.defaultIfBlank(e.getMessage(), e.getClass().getSimpleName()));
    }
  }

  /**
   * The same role gate the SPA route passes through {@code NeoRequestRouter}: report-spec access
   * with the HTTP method the SPA would use for that kind of action. {@code neo_action} is
   * authorized as a read by the MCP router, so a write needs the POST check here; previewStatement only reads,
   * so it is checked as GET even though the engine receives it as a POST (it needs the body).
   * Fails closed when the spec cannot be resolved.
   */
  private static boolean hasAccess(NeoContext context, boolean mutating) {
    SFSpec spec = context.getSfEntity() != null ? context.getSfEntity().getETGOSFSpec() : null;
    return spec != null && NeoAccessHelper.hasReportSpecAccess(spec, mutating ? "POST" : "GET");
  }

  /**
   * The request the SPA sends to {@code /sws/neo/bank-statements?action=<engineAction>}. The
   * endpoint type is deliberately left unset — that is what the SPA's report-spec request carries —
   * so {@link BankStatementsHandler#handle} routes it by method + {@code action} and cannot
   * re-enter {@link #dispatch}.
   */
  private static NeoContext derive(NeoContext source, String engineAction, JSONObject body) {
    return NeoContext.builder()
        .specName(source.getSpecName())
        .entityName(source.getEntityName())
        .httpMethod(BankStatementsHandler.METHOD_POST)
        .recordId(source.getRecordId())
        .requestBody(body)
        .queryParams(Map.of(BankStatementsHandler.PARAM_ACTION, engineAction))
        .adTab(source.getAdTab())
        .sfEntity(source.getSfEntity())
        .obContext(source.getObContext())
        .mcpOrigin(source.isMcpOrigin())
        .build();
  }

  /** A shallow copy, so the id overwrite never mutates the caller's parameters. */
  private static JSONObject copy(JSONObject source) throws JSONException {
    JSONObject out = new JSONObject();
    for (Iterator<?> it = source.keys(); it.hasNext();) {
      String key = String.valueOf(it.next());
      out.put(key, source.get(key));
    }
    return out;
  }

  private static Map<String, NeoActionContract> buildContracts() {
    Map<String, NeoActionContract> map = new LinkedHashMap<>();
    for (NeoActionContract c : new NeoActionContract[] { createContract(), previewContract(),
        importContract() }) {
      map.put(c.getName(), c.withIdDescription(ACCOUNT_ID_DESC));
    }
    for (NeoActionContract c : new NeoActionContract[] { updateContract(),
        idOnlyContract(PROCESS_STATEMENT, "Processes a DRAFT bank statement so its lines become "
            + "available for reconciliation. A statement that is already processed answers 400."),
        idOnlyContract(REACTIVATE_STATEMENT, "Returns a PROCESSED bank statement to draft so it "
            + "can be edited (updateStatement) or deleted (deleteStatement). Reconciliations are "
            + "not reversed: matched lines stay matched. A draft or a posted statement answers "
            + "400."),
        idOnlyContract(DELETE_STATEMENT, "Permanently deletes a DRAFT bank statement and its "
            + "lines (reactivateStatement a processed one first). Answers 409 when the account is "
            + "connected to the bank (PSD2) — its statements come from the bank feed — and 400 "
            + "when the statement still has lines matched to transactions (undo those "
            + "reconciliations first) or is processed.") }) {
      map.put(c.getName(), c.withIdDescription(STATEMENT_ID_DESC));
    }
    return Collections.unmodifiableMap(map);
  }

  private static NeoActionContract createContract() {
    return NeoActionContract.write(CREATE_STATEMENT,
        "Creates a bank statement by hand (header + lines) on the financial account. By default "
            + "it is also processed, so its lines become available for reconciliation; send "
            + "process=false to keep a draft (then processStatement it). The document type is "
            + "always the account's bank statement (BSF) type." + HEADER_DATES_NOTE
            + " Returns 201 with {id, name, lineCount, processed}; id is the new statement.",
        required(P_NAME, S, NAME_DESC),
        required(P_TRANSACTION_DATE, D, TRANSACTION_DATE_DESC),
        required(P_IMPORT_DATE, D, IMPORT_DATE_DESC),
        array(P_LINES, NeoActionContract.TYPE_OBJECT, true, LINES_DESC + " At least one."),
        optional(P_PROCESS, B, "Process the statement after saving. Default true; false saves "
            + "a draft."),
        optional(P_NOTES, S, NOTES_DESC),
        optional(P_FILE_NAME, S, "Source file name to record on the statement (max 255)."));
  }

  private static NeoActionContract previewContract() {
    return NeoActionContract.read(PREVIEW_STATEMENT,
        "Parses a bank statement file for the financial account and returns what "
            + "importStatement would create — format, fileName, lineCount, totalIn, totalOut, "
            + "periodFrom, periodTo, discardedLines and the lines — WITHOUT saving anything. Same "
            + "parameters as importStatement." + UPLOAD_NOTE,
        required(P_FILE_NAME, S, FILE_NAME_UPLOAD_DESC),
        required(P_CONTENT, S, CONTENT_DESC));
  }

  private static NeoActionContract importContract() {
    return NeoActionContract.write(IMPORT_STATEMENT,
        "Imports a bank statement file into the financial account. It arrives PROCESSED, so its "
            + "lines are ready to reconcile. Lines with no amount are dropped. transactionDate is "
            + "the last movement date of the file (today when no line has a date) and importDate "
            + "is now. Returns 201 with {id, fileName, lineCount, discardedLines}; a file with no "
            + "valid line answers 400 with code NO_VALID_LINES and saves nothing. Run "
            + "previewStatement first to check the content." + UPLOAD_NOTE,
        required(P_FILE_NAME, S, FILE_NAME_UPLOAD_DESC),
        required(P_CONTENT, S, CONTENT_DESC));
  }

  private static NeoActionContract updateContract() {
    return NeoActionContract.write(UPDATE_STATEMENT,
        "Edits a DRAFT bank statement (reactivateStatement a processed one first): sets the "
            + "header and REPLACES its unmatched lines with `lines`. Lines already matched to a "
            + "transaction are kept untouched and must not be resent. The header is sent whole: "
            + "an omitted fileName or notes is cleared." + HEADER_DATES_NOTE + " Stays a draft "
            + "unless process=true. A processed statement answers 400.",
        required(P_NAME, S, NAME_DESC),
        required(P_TRANSACTION_DATE, D, TRANSACTION_DATE_DESC),
        required(P_IMPORT_DATE, D, IMPORT_DATE_DESC),
        array(P_LINES, NeoActionContract.TYPE_OBJECT, false, LINES_DESC
            + " Required unless the statement already has matched lines."),
        optional(P_PROCESS, B, "Process the statement after saving. Default false (stays a "
            + "draft)."),
        optional(P_NOTES, S, NOTES_DESC + " Omitted clears them."),
        optional(P_FILE_NAME, S, "Source file name (max 255). Omitted clears it."));
  }

  private static NeoActionContract idOnlyContract(String name, String description) {
    return NeoActionContract.write(name, description + ID_ONLY_NOTE);
  }
}
