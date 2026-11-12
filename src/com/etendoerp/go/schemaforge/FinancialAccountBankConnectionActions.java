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

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

import javax.servlet.http.HttpServletResponse;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.core.OBContext;
import org.openbravo.model.financialmgmt.payment.FIN_FinancialAccount;

import com.etendoerp.go.schemaforge.data.SFSpec;
import com.etendoerp.go.schemaforge.util.NeoAccessHelper;
import com.etendoerp.go.schemaforge.util.NeoActionContract;

/**
 * The account's bank connection (PSD2 / Salt Edge) as declared actions of {@code
 * financial-account/account} (ETP-5804, ETP-6154): what "Editar cuenta", the row's kebab and the
 * row's sync button let a person do.
 *
 * <p>Every action is handed to the SPA's {@code financial-account-bank-connection} endpoint with
 * the body the SPA sends — through {@link FinancialAccountBankConnectionEndpoint}, so its
 * customization is resolved and run as REST runs it. The work itself stays
 * {@link FinancialAccountBankConnectionHandler}'s:</p>
 * <ul>
 *   <li>{@code disconnectBank} — {@code ?action=disconnect}, soft: sync stops, the account
 *       normally keeps its link and history and can be reconnected from the UI (a connection
 *       shared by several accounts is unlinked instead; the answer's {@code reconnectable} says
 *       which);</li>
 *   <li>{@code deleteBankConnection} — {@code ?action=disconnect} with {@code permanentDeletion}:
 *       permanent and irreversible, the connection is deleted at the bank aggregator and the
 *       account is unlinked;</li>
 *   <li>{@code syncBank} — {@code ?action=sync}: imports the account's new bank movements,
 *       synchronously. One sync per account at a time: while another one runs (from the UI, a
 *       schedule or another agent) it answers {@code IN_PROGRESS} at once instead of waiting;</li>
 *   <li>{@code bankSyncStatus} — {@code ?action=syncStatus}: whether a sync of the account is
 *       running and how the last one ended.</li>
 * </ul>
 * <p><b>Role gate.</b> Every action first passes the same check the SPA's own request passes in
 * {@code NeoRequestRouter}: report-spec access to {@code financial-account-bank-connection},
 * {@code POST} for the writes ({@code disconnectBank}, {@code deleteBankConnection},
 * {@code syncBank}) and {@code GET} for {@code bankSyncStatus}; see {@link #hasEndpointAccess}.
 * The MCP router only authorizes {@code etendo_action} as a read of {@code financial-account},
 * so until ETP-6154 a role with read-only access to the Financial Account window could
 * disconnect or delete a bank connection through the MCP (a gap open since ETP-5804).</p>
 * <p>Connecting and reconnecting stay out of the MCP: they need the account holder to
 * authenticate at the bank (SCA).</p>
 */
class FinancialAccountBankConnectionActions {

  private static final Logger log =
      LogManager.getLogger(FinancialAccountBankConnectionActions.class);

  static final String DISCONNECT_ACTION = "disconnectBank";
  static final String DELETE_ACTION = "deleteBankConnection";

  static final String SYNC_ACTION = "syncBank";
  static final String SYNC_STATUS_ACTION = "bankSyncStatus";

  static final String ENDPOINT_ACTION = "disconnect";
  static final String ENDPOINT_SYNC_ACTION = "sync";
  static final String ENDPOINT_SYNC_STATUS_ACTION = "syncStatus";
  static final String KEY_ACCOUNT_ID = "financialAccountId";
  static final String KEY_PERMANENT = "permanentDeletion";
  static final String KEY_ACCOUNT_IDS = "financialAccountIds";
  static final String KEY_ACCOUNTS = "accounts";

  static final String MSG_NOT_FOUND = "Financial account not found";
  static final String MSG_ACCESS_DENIED = "Access denied to spec for current role";

  /** The bank connection endpoint. A seam so unit tests see the exact body it receives. */
  private final Function<NeoContext, NeoResponse> bankConnectionEndpoint;

  FinancialAccountBankConnectionActions() {
    this(FinancialAccountBankConnectionEndpoint::call);
  }

  FinancialAccountBankConnectionActions(Function<NeoContext, NeoResponse> bankConnectionEndpoint) {
    this.bankConnectionEndpoint = bankConnectionEndpoint;
  }

  /** @return whether {@code action} is one of the bank connection actions */
  static boolean serves(String action) {
    return action != null && actionContracts().containsKey(action);
  }

  /**
   * The bank connection actions, declared for agents. {@code id} is the account acted on.
   *
   * @return the contracts, in presentation order
   */
  static Map<String, NeoActionContract> actionContracts() {
    String idDescription = "the id of the financial account whose bank connection is "
        + "disconnected";
    Map<String, NeoActionContract> contracts = new LinkedHashMap<>();
    contracts.put(DISCONNECT_ACTION, NeoActionContract.write(DISCONNECT_ACTION,
        "Disconnects this account from its bank (PSD2), exactly as the account's Desconectar "
            + "option does in its soft mode: bank synchronization stops, and normally the "
            + "account keeps its bank link and its history, so it can be reconnected later from "
            + "Etendo (the reconnection needs the account holder to authenticate at the bank, so "
            + "an agent cannot do it). If the bank connection is shared by several accounts, it "
            + "is unlinked from this one instead and cannot be reconnected: read the returned "
            + "reconnectable flag. Use it to release the account for manual bank statements, which a "
            + "connected account refuses. It does not delete the account. Repeating it on an "
            + "account that is still soft-disconnected is safe (200); once the account is "
            + "unlinked there is nothing left to disconnect and it answers 404. "
            + "Returns {disconnected, permanent, reconnectable}.")
        .withIdDescription(idDescription));
    contracts.put(DELETE_ACTION, NeoActionContract.write(DELETE_ACTION,
        "PERMANENTLY deletes this account's bank connection, exactly as the account's Borrar "
            + "conexi\u00f3n bancaria option does: the connection is deleted at the bank aggregator "
            + "(Salt Edge) and the account is unlinked. This is irreversible and cannot be "
            + "undone: if the account is connected again, movements of periods already "
            + "reconciled may be imported again and create duplicates. Do not call it on your "
            + "own: ask the user first and call it only after they confirm. For a reversible "
            + "stop use disconnectBank. Afterwards the bank connection no longer blocks deleting the "
            + "account (other delete blockers may still apply). Returns "
            + "{disconnected, permanent, reconnectable}.")
        .withIdDescription(idDescription));
    String syncIdDescription = "the id of the financial account whose bank movements are "
        + "synchronized";
    contracts.put(SYNC_ACTION, NeoActionContract.write(SYNC_ACTION,
        "Imports the new movements of this bank-connected account from its bank (PSD2), exactly "
            + "as the account's Sincronizar button does: the movements are added to the "
            + "account's imported bank statements, ready to reconcile. It runs synchronously and "
            + "can take a while (it waits for the bank). Only one synchronization of an account "
            + "runs at a time: if another one is already running (started from the Etendo UI, by "
            + "the scheduled import or by another agent), it does not start a second one and "
            + "answers status IN_PROGRESS at once with a 'Trayendo extractos' / "
            + "'Fetching statements' message; then call bankSyncStatus until inProgress is "
            + "false instead of calling syncBank again. Also call bankSyncStatus if this call "
            + "times out: the synchronization keeps running on the server. Movements already "
            + "imported are never imported twice. The account must be connected to its bank "
            + "(see the account's bank connection status); otherwise it answers ERROR. Returns "
            + "{status: OK|WARNING|ERROR|IN_PROGRESS, message, lastSyncDate}; WARNING means it "
            + "finished but the message needs attention (no new movements, an import date beyond "
            + "what the bank provides, a connection that needs reconnecting from the Etendo UI); "
            + "an ERROR with code BANK_SYNC_LOCK_UNAVAILABLE is temporary, retry in a few "
            + "moments. code BANK_SYNC_DUPLICATE_CHECK_FAILED (on an ERROR, or a WARNING when "
            + "other movements were still imported) means some movements could not be checked "
            + "against the ones already imported and were skipped to avoid duplicates: calling "
            + "syncBank again is safe and imports them.")
        .withIdDescription(syncIdDescription));
    contracts.put(SYNC_STATUS_ACTION, NeoActionContract.read(SYNC_STATUS_ACTION,
        "Tells whether a bank synchronization of this account is running right now and how the "
            + "last one ended, without starting one. Use it after syncBank answers IN_PROGRESS "
            + "or times out, polling every few seconds until inProgress is false. Returns "
            + "{inProgress, lastSyncDate, lastResult: {status: OK|ERROR|null, message, at}}: "
            + "lastSyncDate is the last successful import into Etendo; lastResult is the last "
            + "attempt of the bank connection to reach the bank (ERROR carries the bank's "
            + "message), null fields when it never ran.")
        .withIdDescription(syncIdDescription));
    return contracts;
  }

  /**
   * Serve one bank connection action.
   *
   * @param context the ACTION context; {@code recordId} is the account
   * @return the answer, or {@code null} when {@code context} is not a bank connection action
   */
  NeoResponse handle(NeoContext context) {
    String action = context.getFieldName();
    if (!NeoEndpointType.ACTION.equals(context.getEndpointType()) || !serves(action)) {
      return null;
    }
    if (!"POST".equals(context.getHttpMethod())) {
      return NeoResponse.error(HttpServletResponse.SC_METHOD_NOT_ALLOWED, "Method not allowed.");
    }
    try {
      OBContext.setAdminMode(true);
      if (!hasEndpointAccess(actionContracts().get(action).isMutating())) {
        return NeoResponse.error(HttpServletResponse.SC_FORBIDDEN, MSG_ACCESS_DENIED);
      }
      FIN_FinancialAccount account =
          TenantOwnership.loadOwned(FIN_FinancialAccount.class, context.getRecordId());
      if (account == null) {
        return NeoResponse.error(HttpServletResponse.SC_NOT_FOUND, MSG_NOT_FOUND);
      }
      if (SYNC_ACTION.equals(action)) {
        return sync(account, context.isMcpOrigin());
      }
      if (SYNC_STATUS_ACTION.equals(action)) {
        return syncStatus(account, context.isMcpOrigin());
      }
      return disconnect(account, DELETE_ACTION.equals(action), context.isMcpOrigin());
    } catch (Exception e) {
      log.error("Bank connection action {} failed on account {}", action, context.getRecordId(),
          e);
      return NeoResponse.error(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
          "Could not run " + action + ". Please check logs for details.");
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /**
   * The role gate the SPA's own request passes through in {@code NeoRequestRouter}: report-spec
   * access to {@code financial-account-bank-connection}, as a {@code POST} for a write and a
   * {@code GET} for a read (ETP-6154).
   *
   * <p>Applied to every action of this class. Needed because {@code etendo_action} is authorized by
   * the MCP router as a read of {@code financial-account}, while the endpoint itself is called here
   * directly, past the router: without this check a role with read-only access to the Financial
   * Account window could disconnect or delete the bank connection, or start a sync, none of which
   * it can do from the UI. The disconnect actions had that gap since ETP-5804; ETP-6154 closed it
   * together with the new sync actions. Fails closed when the spec cannot be resolved. Called in
   * admin mode (the spec lookup reads a dictionary table); the role checked is still the
   * caller's. A seam for unit tests.</p>
   *
   * @param mutating whether the action writes
   * @return {@code true} when the current role may run it
   */
  boolean hasEndpointAccess(boolean mutating) {
    SFSpec spec = NeoServletSupport.findSpec(FinancialAccountBankConnectionEndpoint.SPEC);
    return spec != null && NeoAccessHelper.hasReportSpecAccess(spec, mutating ? "POST" : "GET");
  }

  /**
   * Disconnects the account, softly or permanently, as the SPA's Desconectar / Borrar conexión
   * bancaria options do.
   *
   * @param account   the account, already checked to belong to the caller's tenant
   * @param permanent whether the connection is deleted at the aggregator
   * @param mcp       whether the request came through the MCP
   * @return the endpoint's {@code data}, or its error response unchanged
   * @throws JSONException when the body cannot be built
   */
  private NeoResponse disconnect(FIN_FinancialAccount account, boolean permanent, boolean mcp)
      throws JSONException {
    // The body the SPA sends to ?action=disconnect.
    JSONObject body = new JSONObject();
    body.put(KEY_ACCOUNT_ID, account.getId());
    body.put(KEY_PERMANENT, permanent);
    NeoResponse result = callEndpoint(ENDPOINT_ACTION, body, mcp);
    if (!isSuccess(result)) {
      return result;
    }
    return NeoResponse.ok(envelope(dataOf(result)));
  }

  /**
   * Synchronizes the account's bank movements, as the SPA's Sincronizar button does (ETP-6154).
   * The endpoint answers 200 for every outcome, {@code IN_PROGRESS} included, with a translated
   * message; it is passed through as is.
   *
   * @param account the account, already checked to belong to the caller's tenant
   * @param mcp     whether the request came through the MCP
   * @return the endpoint's {@code {status, message, lastSyncDate}}, or its error response
   * @throws JSONException when the body cannot be built
   */
  private NeoResponse sync(FIN_FinancialAccount account, boolean mcp) throws JSONException {
    // The body the SPA sends to ?action=sync.
    JSONObject body = new JSONObject();
    body.put(KEY_ACCOUNT_ID, account.getId());
    NeoResponse result = callEndpoint(ENDPOINT_SYNC_ACTION, body, mcp);
    if (!isSuccess(result)) {
      return result;
    }
    return NeoResponse.ok(envelope(dataOf(result)));
  }

  /**
   * Reports whether a bank sync of the account is running and how the last one ended
   * (ETP-6154), unwrapping the account's own entry from the endpoint's batch answer.
   *
   * @param account the account, already checked to belong to the caller's tenant
   * @param mcp     whether the request came through the MCP
   * @return {@code {inProgress, lastSyncDate, lastResult}}, a 404 when the endpoint omitted the
   *         account (it could not read it), or the endpoint's error response
   * @throws JSONException when the body cannot be built
   */
  private NeoResponse syncStatus(FIN_FinancialAccount account, boolean mcp) throws JSONException {
    // The body the SPA sends to ?action=syncStatus, for this one account.
    JSONObject body = new JSONObject();
    body.put(KEY_ACCOUNT_IDS, new JSONArray().put(account.getId()));
    NeoResponse result = callEndpoint(ENDPOINT_SYNC_STATUS_ACTION, body, mcp);
    if (!isSuccess(result)) {
      return result;
    }
    JSONObject accounts = dataOf(result).optJSONObject(KEY_ACCOUNTS);
    JSONObject entry = accounts == null ? null : accounts.optJSONObject(account.getId());
    if (entry == null) {
      return NeoResponse.error(HttpServletResponse.SC_NOT_FOUND, MSG_NOT_FOUND);
    }
    return NeoResponse.ok(envelope(entry));
  }

  /**
   * Calls the bank connection endpoint the way the SPA does: {@code POST ?action=<action>}.
   *
   * @param endpointAction the endpoint's {@code action} query parameter
   * @param body           the request body
   * @param mcp            whether the request came through the MCP
   * @return the endpoint's answer
   */
  private NeoResponse callEndpoint(String endpointAction, JSONObject body, boolean mcp) {
    return bankConnectionEndpoint.apply(NeoContext.builder()
        .specName(FinancialAccountBankConnectionEndpoint.SPEC)
        .entityName(FinancialAccountBankConnectionEndpoint.SPEC)
        .httpMethod("POST")
        .queryParams(Map.of("action", endpointAction))
        .requestBody(body)
        .endpointType(NeoEndpointType.CRUD)
        .mcpOrigin(mcp)
        .obContext(OBContext.getOBContext())
        .build());
  }

  /**
   * Whether the endpoint answered with a 2xx.
   *
   * @param result the endpoint's answer, possibly {@code null}
   * @return {@code true} for a non-null 2xx answer
   */
  private static boolean isSuccess(NeoResponse result) {
    return result != null && result.getHttpStatus() >= 200 && result.getHttpStatus() < 300;
  }

  /** The {@code response.data} the endpoint answered, or its whole body when it has none. */
  private static JSONObject dataOf(NeoResponse result) {
    JSONObject body = result.getBody();
    if (body == null) {
      return new JSONObject();
    }
    JSONObject response = body.optJSONObject("response");
    JSONObject data = response == null ? null : response.optJSONObject("data");
    return data != null ? data : body;
  }

  private static JSONObject envelope(JSONObject data) throws JSONException {
    return new JSONObject().put("response", new JSONObject().put("data", data));
  }
}
