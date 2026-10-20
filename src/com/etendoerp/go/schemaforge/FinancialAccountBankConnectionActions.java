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
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.core.OBContext;
import org.openbravo.model.financialmgmt.payment.FIN_FinancialAccount;

import com.etendoerp.go.schemaforge.util.NeoActionContract;

/**
 * Disconnecting an account from its bank (PSD2 / Salt Edge) as declared actions of {@code
 * financial-account/account} (ETP-5804): what "Editar cuenta" and the row's kebab let a person do.
 *
 * <p>Two actions, one per UI option, both handed to the SPA's {@code
 * financial-account-bank-connection} endpoint as {@code POST ?action=disconnect} with the body the
 * SPA sends ({@code financialAccountId}, {@code permanentDeletion}) — through {@link
 * FinancialAccountBankConnectionEndpoint}, so its customization is resolved and run as REST runs
 * it. The disconnect itself stays {@link FinancialAccountBankConnectionHandler}'s:</p>
 * <ul>
 *   <li>{@code disconnectBank} — soft: sync stops, the account normally keeps its link and
 *       history and can be reconnected from the UI (a connection shared by several accounts is
 *       unlinked instead; the answer's {@code reconnectable} says which);</li>
 *   <li>{@code deleteBankConnection} — permanent and irreversible: the connection is deleted at
 *       the bank aggregator and the account is unlinked.</li>
 * </ul>
 * <p>Connecting and reconnecting stay out of the MCP: they need the account holder to
 * authenticate at the bank (SCA).</p>
 */
class FinancialAccountBankConnectionActions {

  private static final Logger log =
      LogManager.getLogger(FinancialAccountBankConnectionActions.class);

  static final String DISCONNECT_ACTION = "disconnectBank";
  static final String DELETE_ACTION = "deleteBankConnection";

  static final String ENDPOINT_ACTION = "disconnect";
  static final String KEY_ACCOUNT_ID = "financialAccountId";
  static final String KEY_PERMANENT = "permanentDeletion";

  static final String MSG_NOT_FOUND = "Financial account not found";

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
   * The bank connection actions, declared for agents. {@code id} is the account to disconnect.
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
      FIN_FinancialAccount account =
          TenantOwnership.loadOwned(FIN_FinancialAccount.class, context.getRecordId());
      if (account == null) {
        return NeoResponse.error(HttpServletResponse.SC_NOT_FOUND, MSG_NOT_FOUND);
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

  private NeoResponse disconnect(FIN_FinancialAccount account, boolean permanent, boolean mcp)
      throws JSONException {
    // The body the SPA sends to ?action=disconnect.
    JSONObject body = new JSONObject();
    body.put(KEY_ACCOUNT_ID, account.getId());
    body.put(KEY_PERMANENT, permanent);
    NeoResponse result = bankConnectionEndpoint.apply(NeoContext.builder()
        .specName(FinancialAccountBankConnectionEndpoint.SPEC)
        .entityName(FinancialAccountBankConnectionEndpoint.SPEC)
        .httpMethod("POST")
        .queryParams(Map.of("action", ENDPOINT_ACTION))
        .requestBody(body)
        .endpointType(NeoEndpointType.CRUD)
        .mcpOrigin(mcp)
        .obContext(OBContext.getOBContext())
        .build());
    if (result == null || result.getHttpStatus() < 200 || result.getHttpStatus() >= 300) {
      return result;
    }
    return NeoResponse.ok(envelope(dataOf(result)));
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
