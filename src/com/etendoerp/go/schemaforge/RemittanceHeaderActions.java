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

import javax.servlet.http.HttpServletResponse;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.core.OBContext;
import org.openbravo.module.remittance.Remittance;

import com.etendoerp.go.schemaforge.util.NeoActionContract;

/**
 * The declared actions of the remittance header, run by {@link RemittanceHeaderHandler}: which
 * collaborator serves an action ({@link #serves}, {@link #dispatch}), the «Seleccionar desde
 * factura», «Procesar» and «Reactivar» actions ({@link #handleDueItems}) and where a call's
 * parameters travel ({@link #actionParams}). Split out of the handler; every DAL read and every
 * Classic run still goes through the handler's seams, so a test stubs them on the handler.
 */
final class RemittanceHeaderActions {

  private static final Logger log = LogManager.getLogger(RemittanceHeaderActions.class);

  private static final int SC_UNPROCESSABLE = 422;

  private RemittanceHeaderActions() {
  }

  /**
   * Whether one of the header's declared actions is named {@code action}.
   *
   * @param action the action name of the request
   * @return {@code true} when {@link #dispatch} answers it
   */
  static boolean serves(String action) {
    return RemittanceSepaFileData.serves(action) || RemittanceFileAction.serves(action)
        || RemittanceStatusAction.serves(action) || RemittanceReturnAction.serves(action)
        || RemittanceDueItems.serves(action) || RemittanceProcessAction.serves(action);
  }

  /**
   * Runs the declared action of {@code context}, which {@link #serves} accepts, through the
   * collaborator that owns it.
   *
   * @param handler the remittance header customization (its seams)
   * @param context the action request
   * @return the collaborator's answer
   */
  static NeoResponse dispatch(RemittanceHeaderHandler handler, NeoContext context) {
    String action = context.getFieldName();
    if (RemittanceSepaFileData.serves(action)) {
      return RemittanceSepaFileDataAction.handle(handler, context);
    }
    if (RemittanceFileAction.serves(action)) {
      return RemittanceFileAction.handle(handler, context);
    }
    if (RemittanceStatusAction.serves(action)) {
      return RemittanceStatusAction.handle(handler, context);
    }
    if (RemittanceReturnAction.serves(action)) {
      return RemittanceReturnAction.handle(handler, context);
    }
    return handleDueItems(handler, context);
  }

  /**
   * Runs a due-items action: 404 for an unknown remittance, 422 on a processed one, 405 for a
   * {@code selectDueItems} that is not a POST, the contract's 422 for a malformed call. Read
   * parameters come from the body (MCP, POST) or the query string (REST GET).
   *
   * @param handler the remittance header customization (its seams)
   * @param context the action request
   * @return the answer
   */
  static NeoResponse handleDueItems(RemittanceHeaderHandler handler, NeoContext context) {
    String action = context.getFieldName();
    boolean post = "POST".equals(context.getHttpMethod());
    boolean write = RemittanceDueItems.isWrite(action)
        || RemittanceProcessAction.serves(action);
    if (write && !post) {
      return NeoResponse.error(HttpServletResponse.SC_METHOD_NOT_ALLOWED,
          action + " only supports POST");
    }
    OBContext.setAdminMode(true);
    try {
      JSONObject params = actionParams(handler, action, context.getRequestBody() != null
          ? context.getRequestBody()
          : RemittanceDueItems.paramsFromQuery(RemittanceRequestParams.of(context,
              handler::currentRequest, RemittanceDueItems.P_SEARCH, RemittanceDueItems.P_PAGE,
              RemittanceDueItems.P_PAGE_SIZE)));
      NeoResponse invalid = NeoActionContract.validate(handler.actionContracts(), action, params);
      if (invalid != null) {
        return invalid;
      }
      Remittance remittance = handler.loadRemittance(context.getRecordId());
      if (remittance == null) {
        return NeoResponse.error(HttpServletResponse.SC_NOT_FOUND, "Remittance not found");
      }
      if (RemittanceProcessAction.ACTION.equals(action)) {
        return handler.processRemittance(remittance, params);
      }
      if (RemittanceProcessAction.ACTION_REACTIVATE.equals(action)) {
        return handler.reactivateRemittance(remittance);
      }
      if (Boolean.TRUE.equals(remittance.isProcessed())) {
        return NeoMessageText.error(SC_UNPROCESSABLE, RemittanceHeaderHandler.MSG_PROCESSED);
      }
      if (RemittanceDueItems.ACTION_SYNC.equals(action)) {
        return handler.syncDueItems(remittance, params);
      }
      return RemittanceDueItems.ACTION_AVAILABLE.equals(action)
          ? handler.availableDueItems(remittance, params)
          : handler.selectDueItems(remittance, params);
    } catch (Exception e) {
      log.error("remittance action {} failed on {}", action, context.getRecordId(), e);
      // syncDueItems deletes lines before the Classic run: an unexpected failure after that must
      // not leave the deletes pending in the session (unless a batch owns the transaction).
      NeoErrorResponses.rollbackUnlessBatchOwned();
      return NeoResponse.error(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
          "Could not run " + action + ". Please check logs for details.");
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /**
   * The parameters of a call, read where its contract says they travel: {@code process} and
   * {@code reactivate} are pressed from the SPA's process dialog, which posts
   * {@code {"fieldValues": {...}}}, and {@code etendo_action} wraps the agent's flat parameters
   * the same way for those contracts. A flat body is still accepted for them.
   *
   * @param handler the remittance header customization (its action contracts)
   * @param action  the action name, may be {@code null}
   * @param body    the request body
   * @return the parameters
   */
  static JSONObject actionParams(RemittanceHeaderHandler handler, String action,
      JSONObject body) {
    NeoActionContract contract = action != null ? handler.actionContracts().get(action) : null;
    if (contract == null || !contract.isFieldValuesBody()) {
      return body;
    }
    JSONObject fieldValues = body.optJSONObject(RemittanceHeaderHandler.FIELD_VALUES);
    return fieldValues != null ? fieldValues : body;
  }
}
