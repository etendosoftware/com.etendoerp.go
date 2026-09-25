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
import static com.etendoerp.go.schemaforge.util.NeoActionContract.Param.options;
import static com.etendoerp.go.schemaforge.util.NeoActionContract.Param.required;

import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;

import com.etendoerp.go.schemaforge.util.NeoActionContract;

/**
 * The bank-reconciliation actions an agent can run through {@code neo_action} (ETP-5468, front A).
 *
 * <p><b>Purely additive.</b> The SPA keeps calling {@code /sws/neo/bank-reconciliation?action=…}
 * — a report-spec request whose context carries no endpoint type — and {@link
 * ReconciliationHandler#handle} only enters {@link #dispatch} for {@code NeoEndpointType.ACTION},
 * which only {@code neo_action} produces for this spec. Every action here re-enters the SAME
 * wrapper the SPA route uses ({@code ReconciliationHandlerSupport.handle*}), so the business
 * validations, the {@code runPostAction} rollback and the error mapping are identical to the UI's —
 * this class only translates the MCP call shape (record {@code id} = financial account,
 * {@code parameters} = body or query params) and validates it against the declared contract.</p>
 *
 * <p><b>Why the agent gets these instead of Core's buttons.</b> The Core APRM buttons on
 * {@code financial-account/account} left statement lines matched into an unconfirmed draft
 * reconciliation (ETP-5468). These routes are the ones the Etendo GO UI uses, and they complete or
 * roll back as a unit.</p>
 */
final class ReconciliationAgentActions {

  static final String RECONCILE_GROUP = "reconcileGroup";
  static final String RECONCILE_DIFFERENCE = "reconcileDifference";
  static final String UNDO_RECONCILIATION = "undoReconciliation";
  static final String REMOVE_OPERATION = "removeOperation";
  static final String REACTIVATE_SELECTED = "reactivateSelected";
  static final String AUTO_MATCH = "autoMatch";
  static final String APPLY_SUGGESTIONS = "applySuggestions";
  static final String PENDING_LINES = "pendingLines";
  static final String CANDIDATES = "candidates";

  private static final String P_LINE = ReconciliationHandler.KEY_STATEMENT_LINE_ID;
  private static final String P_TRANSACTION_IDS = "transactionIds";
  private static final String P_DATE_FROM = ReconciliationHandler.PARAM_DATE_FROM;
  private static final String P_DATE_TO = ReconciliationHandler.PARAM_DATE_TO;
  private static final String S = NeoActionContract.TYPE_STRING;
  private static final String LINE_DESC =
      "Id of the bank statement line (from pendingLines). For a partially reconciled line use "
          + "its pending sub-line, not the group head (the refusal names it as remainderLineId).";

  /** What the {@code neo_action} {@code id} argument identifies for every action here. */
  private static final String ID_DESC =
      "The financial account id (FIN_Financial_Account) whose bank statement lines are "
          + "reconciled.";

  /** The declared actions, in presentation order: read helpers first, then the writes. */
  static final Map<String, NeoActionContract> CONTRACTS = buildContracts();

  /** Actions answered by a read-only (GET) SPA route. */
  private static final Map<String, BiFunction<ReconciliationHandler, NeoContext, NeoResponse>>
      READ_ROUTES = Map.of(
          PENDING_LINES, ReconciliationHandlerSupport::handlePendingLines,
          CANDIDATES, ReconciliationHandlerSupport::handleCandidates,
          AUTO_MATCH, ReconciliationHandlerSupport::handleAutoMatch);

  /** Actions answered by a mutating (POST) SPA route. */
  private static final Map<String, BiFunction<ReconciliationHandler, NeoContext, NeoResponse>>
      WRITE_ROUTES = Map.of(
          RECONCILE_GROUP, ReconciliationHandlerSupport::handleReconcileGroup,
          RECONCILE_DIFFERENCE, ReconciliationHandlerSupport::handleReconcileDifference,
          UNDO_RECONCILIATION, ReconciliationHandlerSupport::handleReactivate,
          REMOVE_OPERATION, ReconciliationHandlerSupport::handleRemoveOperation,
          REACTIVATE_SELECTED, ReconciliationHandlerSupport::handleReactivateSelected,
          APPLY_SUGGESTIONS, ReconciliationHandlerSupport::handleApplySuggestions);

  /** MCP parameter name → SPA query-param name, where they differ (read routes only). */
  private static final Map<String, String> QUERY_PARAM_ALIASES =
      Map.of(P_LINE, ReconciliationHandler.PARAM_LINE_ID);

  private ReconciliationAgentActions() {
  }

  /**
   * Runs one declared action for the financial account named by the context's record id.
   *
   * @param handler the reconciliation handler (its seams run exactly as on the SPA route)
   * @param context an ACTION context: {@code fieldName} = action, {@code recordId} = financial
   *                account id, {@code requestBody} = the action parameters
   * @return the SPA route's own response, or a 422/403 when the call is refused before it runs
   */
  static NeoResponse dispatch(ReconciliationHandler handler, NeoContext context) {
    String action = context.getFieldName();
    JSONObject params = context.getRequestBody() != null ? context.getRequestBody()
        : new JSONObject();
    NeoResponse invalid = NeoActionContract.validate(CONTRACTS, action, params);
    if (invalid != null) {
      return invalid;
    }
    String accountId = StringUtils.trimToNull(context.getRecordId());
    if (accountId == null) {
      return NeoResponse.error(NeoActionContract.SC_UNPROCESSABLE,
          "id (the financial account id) is required");
    }
    boolean mutating = CONTRACTS.get(action).isMutating();
    if (!AgentActionSupport.hasAccess(context, mutating)) {
      return NeoResponse.error(HttpServletResponse.SC_FORBIDDEN,
          "Access denied to spec for current role");
    }
    try {
      if (mutating) {
        JSONObject body = AgentActionSupport.copy(params);
        body.put(ReconciliationHandler.KEY_FINANCIAL_ACCOUNT_ID, accountId);
        NeoResponse written = WRITE_ROUTES.get(action).apply(handler,
            AgentActionSupport.derive(context, "POST", body, null));
        // ETP-5468 BUG-2: flush to clean while the OBContext is still set (see the helper).
        return AgentActionSupport.flushWhileContextIsSet(action, "reconciliation", written,
            handler::doRollbackAndClose);
      }
      Map<String, String> query = new HashMap<>();
      query.put(ReconciliationHandler.PARAM_ACCOUNT_ID, accountId);
      for (Iterator<?> it = params.keys(); it.hasNext();) {
        String key = String.valueOf(it.next());
        if (!params.isNull(key)) {
          query.put(QUERY_PARAM_ALIASES.getOrDefault(key, key), params.getString(key));
        }
      }
      return READ_ROUTES.get(action).apply(handler,
          AgentActionSupport.derive(context, "GET", null, query));
    } catch (JSONException e) {
      return NeoResponse.error(NeoActionContract.SC_UNPROCESSABLE,
          "Invalid action parameters: " + e.getMessage());
    }
  }

  private static Map<String, NeoActionContract> buildContracts() {
    Map<String, NeoActionContract> map = new LinkedHashMap<>();
    for (List<NeoActionContract> group : List.of(readContracts(), writeContracts())) {
      for (NeoActionContract c : group) {
        map.put(c.getName(), c.withIdDescription(ID_DESC));
      }
    }
    return Collections.unmodifiableMap(map);
  }

  /** The read-only helpers: they list and preview, never persist. */
  private static List<NeoActionContract> readContracts() {
    return List.of(
        NeoActionContract.read(PENDING_LINES,
            "Lists the account's bank statement lines with their reconciliation state and "
                + "per-state counts. Start here: every write "
                + "action needs a statementLineId from this list. Lines of a statement still in "
                + "draft are not reconcilable and are not listed as pending.",
            optional(P_DATE_FROM, NeoActionContract.TYPE_DATE, "Earliest line date (yyyy-MM-dd)."),
            optional(P_DATE_TO, NeoActionContract.TYPE_DATE, "Latest line date (yyyy-MM-dd)."),
            optional("q", S, "Free-text search on the line description.")),
        NeoActionContract.read(CANDIDATES,
            "Lists what a statement line can be reconciled against: existing movements "
                + "(kind=transactions, default) or unpaid invoices (kind=invoices). Amounts are "
                + "also given in the account currency (amountBase) for foreign-currency items.",
            required(P_LINE, S, LINE_DESC),
            options("kind", "transactions (default) or invoices.",
                List.of("transactions", "invoices")),
            options("docType", "Direction filter: receipts (money in) or payments (money out). "
                + "For invoices, defaults to the line's sign.", List.of("receipts", "payments")),
            optional(P_DATE_FROM, NeoActionContract.TYPE_DATE, "Earliest date (yyyy-MM-dd)."),
            optional(P_DATE_TO, NeoActionContract.TYPE_DATE, "Latest date (yyyy-MM-dd).")),
        NeoActionContract.read(AUTO_MATCH,
            "Automatch preview: proposes groups of movements for the pending lines. Never changes "
                + "data. Confirm the groups you accept with applySuggestions."));
  }

  /** The mutating actions: each one completes and processes, or rolls back. */
  private static List<NeoActionContract> writeContracts() {
    return List.of(
        NeoActionContract.write(RECONCILE_GROUP,
            "Reconciles one statement line against one or more existing movements (1:1 / 1:N) "
                + "and/or unpaid invoices, which are paid on the fly. Send operationIds, invoices, "
                + "or both. The selection must add up to the line amount; a gap within the "
                + "account's tolerance is posted to the difference GL item, a larger shortfall "
                + "leaves the line partially reconciled (pending remainder). Foreign-currency "
                + "items are converted with the same exchange rate the UI uses — no extra "
                + "parameter. Completes and processes the reconciliation, or rolls back.",
            required(P_LINE, S, LINE_DESC),
            array("operationIds", S, false, "Ids of existing movements (from candidates, "
                + "kind=transactions)."),
            array("invoices", NeoActionContract.TYPE_OBJECT, false, "Invoices to pay and "
                + "reconcile: [{invoiceId, scheduleId}] as returned by candidates kind=invoices."),
            optional("paymentMethodId", S, "Payment method for the invoice payments created here. "
                + "Defaults to the account's method."),
            optional("writeoffDifference", NeoActionContract.TYPE_BOOLEAN, "Single invoice only: "
                + "write off the shortfall so the invoice ends fully paid. Default false."),
            optional("glItemId", S, "GL item for a within-tolerance difference. Defaults to the "
                + "account's difference GL item; answered GL_ITEM_REQUIRED when neither exists."),
            optional("description", S, "Description of the difference movement, if one is "
                + "created.")),
        NeoActionContract.write(RECONCILE_DIFFERENCE,
            "Closes a partially reconciled line by posting its remainder to a GL item. The "
                + "remainder is computed server-side (no amount parameter) and must be within the "
                + "account's tolerance.",
            required(P_LINE, S, LINE_DESC),
            optional("glItemId", S, "GL item for the difference. Required when the account has "
                + "no difference GL item configured (the call then fails with GL_ITEM_REQUIRED)."),
            optional("description", S, "Description of the difference movement.")),
        NeoActionContract.write(UNDO_RECONCILIATION,
            "Undoes the reconciliation of a statement line: the line returns to pending, "
                + "movements and payments that the reconciliation created automatically are "
                + "removed, pre-existing movements are kept but unreconciled. Refused when the "
                + "accounting period is closed, or when another draft reconciliation of the "
                + "account holds unconfirmed matches.",
            required(P_LINE, S, LINE_DESC)),
        NeoActionContract.write(REMOVE_OPERATION,
            "Detaches specific movements from a reconciled line and deletes the ones the "
                + "reconciliation created. Reports failedTransactionIds for any it could not "
                + "free.",
            required(P_LINE, S, LINE_DESC),
            array(P_TRANSACTION_IDS, S, true, "Ids of the movements to detach.")),
        NeoActionContract.write(REACTIVATE_SELECTED,
            "Detaches specific movements from a reconciled line so it can be re-matched; the "
                + "line returns to the pending pool. Same mechanics and reporting as "
                + "removeOperation.",
            required(P_LINE, S, LINE_DESC),
            array(P_TRANSACTION_IDS, S, true, "Ids of the movements to detach.")),
        NeoActionContract.write(APPLY_SUGGESTIONS,
            "Confirms automatch groups in one reconciliation. Accept all = send every group "
                + "autoMatch returned; accept some = send only those; to reject a group simply do "
                + "not send it (nothing is persisted for it). Invalid groups are reported per "
                + "group in results[] without blocking the others.",
            array("groups", NeoActionContract.TYPE_OBJECT, true, "One entry per accepted autoMatch "
                + "group: {statementLineId: group.statementLine.id, operationIds: ids of the "
                + "group's operations whose isNew is false, createPayment: group.createPayment "
                + "(only when present)}.")));
  }
}
