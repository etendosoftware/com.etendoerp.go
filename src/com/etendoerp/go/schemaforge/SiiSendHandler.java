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

import javax.inject.Named;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.invoice.Invoice;
import org.openbravo.module.sii.process.CorrectDuplicateInvoiceError;

/**
 * NeoHandler delegate for the legacy SII button on Sales Invoice.
 *
 * <p>Classic Etendo wires {@code Em_Aeatsii_Send} to the client-side process
 * {@code OB.AEATSII.send}, which in turn calls the server-side OBUIAPP action
 * handler {@code org.openbravo.module.sii.process.MultiEnvioFactura}. NEO cannot
 * execute that client-side hook directly, so this handler invokes the underlying
 * server-side action handler with the same payload shape used by the classic UI.
 *
 * <p>ETP-5272: {@code MultiEnvioFactura} always sends communication type {@code A0}
 * ("alta" / new registration). That is wrong for an invoice pending a registry-error
 * correction ({@link Invoice#isAeatsiiErrorRegistral()} {@code = true}) — AEAT expects the
 * correction/modification envelope, communication type {@code A1}. Classic Etendo itself
 * splits that case in two, by the invoice's actual AEAT error code
 * ({@link Invoice#getAeatsiiErrorCode()}):
 * <ul>
 *   <li>error code exactly {@code "3000"} (duplicate registration) — the classic
 *       "Corregir" button, {@code org.openbravo.module.sii.process.CorrectDuplicateInvoiceError}.
 *       It does a {@code ConsultaLR*} read-back against AEAT and syncs local fields; it does
 *       NOT resend. This handler calls it directly (it is a plain class, not a
 *       {@code BaseActionHandler}).</li>
 *   <li>any other registry error (or no error code at all) — the classic "Modificar" button,
 *       {@code org.openbravo.module.sii.process.MultiInvoiceSIIModification}. This is the real
 *       resend: like {@code MultiEnvioFactura}, it extends {@code BaseActionHandler}, so it is
 *       invoked through the same {@link NeoProcessService#executeObuiappClass} bridge.</li>
 * </ul>
 */
@Named("sii-send")
public class SiiSendHandler extends AbstractLegacyInvoiceActionHandler {

  static final String ACTION_NAME = "EM_Aeatsii_Send";
  static final String ACTION_NAME_LEGACY = "Em_aeatsii_send";
  static final String ACTION_NAME_QUALIFIER = "aeatsiiSend";
  private static final String PROCESS_ID = "2ECF46DAAEEB486EAF79D3594D50DE5F";
  private static final String PROCESS_CLASS = "org.openbravo.module.sii.process.MultiEnvioFactura";
  private static final String MODIFICATION_PROCESS_ID = "F5CCFE8DCAC04FBD9B4A217C6383032B";
  private static final String MODIFICATION_PROCESS_CLASS =
      "org.openbravo.module.sii.process.MultiInvoiceSIIModification";
  private static final String DUPLICATE_REGISTRATION_ERROR_CODE = "3000";
  private static final String STATUS = "status";
  private static final String MESSAGE = "message";
  private static final String ERROR = "error";
  private static final String SUCCESS = "success";
  private static final String RESPONSE_ACTIONS = "responseActions";
  private static final String SHOW_MSG_IN_VIEW = "showMsgInView";

  @Override
  protected NeoResponse executeAction(String recordId) throws Exception {
    Invoice invoice = OBDal.getInstance().get(Invoice.class, recordId);

    if (invoice != null && Boolean.TRUE.equals(invoice.isAeatsiiErrorRegistral())) {
      if (DUPLICATE_REGISTRATION_ERROR_CODE.equals(invoice.getAeatsiiErrorCode())) {
        return executeRegistralCorrection(recordId);
      }
      return executeRegistralModification(recordId, invoice);
    }

    NeoResponse response = NeoProcessService.executeObuiappClass(PROCESS_CLASS, PROCESS_ID,
        buildInvoiceSendParams(recordId, invoice));
    return normalizeErrorShape(response);
  }

  /**
   * Routes a registry-error resend (any AEAT error code other than the duplicate-registration
   * {@code "3000"}) to {@code MultiInvoiceSIIModification}, the classic module's "Modificar"
   * action (communication type {@code A1}). Unlike {@code CorrectDuplicateInvoiceError}, this
   * is a real {@code BaseActionHandler}, so it is invoked through the same
   * {@link NeoProcessService#executeObuiappClass} bridge already used for {@code MultiEnvioFactura}.
   */
  private static NeoResponse executeRegistralModification(String recordId, Invoice invoice)
      throws Exception {
    NeoResponse response = NeoProcessService.executeObuiappClass(MODIFICATION_PROCESS_CLASS,
        MODIFICATION_PROCESS_ID, buildInvoiceSendParams(recordId, invoice));
    return normalizeErrorShape(response);
  }

  /**
   * Builds the payload shape both {@code MultiEnvioFactura} and
   * {@code MultiInvoiceSIIModification} expect: a single-invoice {@code ids} array plus the
   * invoice's organization id, alongside the generic {@code recordId}/{@code inpRecordId} keys
   * {@link NeoProcessService#executeObuiappClass} forwards for record-context resolution.
   */
  private static JSONObject buildInvoiceSendParams(String recordId, Invoice invoice)
      throws JSONException {
    JSONObject params = new JSONObject();
    params.put("recordId", recordId);
    params.put("inpRecordId", recordId);
    params.put("orgid", resolveOrganizationId(invoice));
    JSONArray ids = new JSONArray();
    ids.put(recordId);
    params.put("ids", ids);
    return params;
  }

  /**
   * Routes a registry-error correction resend to {@code CorrectDuplicateInvoiceError},
   * the classic module's own resend path for this case (see class-level javadoc).
   *
   * <p>{@code CorrectDuplicateInvoiceError} is a plain class (not a {@code BaseActionHandler}),
   * so it is called directly rather than through {@link NeoProcessService}'s OBUIAPP bridge —
   * that bridge requires a {@code BaseActionHandler} instance and would reject this class.
   */
  private static NeoResponse executeRegistralCorrection(String recordId) {
    JSONObject handlerResult;
    try {
      handlerResult = new CorrectDuplicateInvoiceError().doExecute(recordId);
    } catch (Exception e) {
      return NeoResponse.ensureTopLevelMessage(
          NeoResponse.error(500, "SII registry-error correction failed: " + e.getMessage()));
    }
    return normalizeErrorShape(translateCorrectDuplicateResult(handlerResult));
  }

  /**
   * Translates {@code CorrectDuplicateInvoiceError#doExecute}'s result shape
   * ({@code responseActions[0].showMsgInView.{msgType,msgTitle,msgText}}) into a
   * {@link NeoResponse}. This shape is specific to this classic process (note: it uses
   * {@code showMsgInView}, not the {@code showMsgInProcessView} key
   * {@link NeoProcessService}'s generic OBUIAPP translator already recognizes), hence a
   * dedicated translator here rather than reusing that generic one.
   *
   * <p>Package-private and static so it can be unit tested directly against a synthetic
   * result, without needing a live AEAT connection or DB access.
   */
  static NeoResponse translateCorrectDuplicateResult(JSONObject handlerResult) {
    try {
      JSONObject msg = extractShowMsgInView(handlerResult);
      JSONObject body = new JSONObject();

      if (msg == null) {
        body.put(STATUS, SUCCESS);
        return NeoResponse.ok(body);
      }

      String msgType = msg.optString("msgType", SUCCESS);
      body.put(STATUS, msgType);
      body.put(MESSAGE, msg.optString("msgText", ""));

      if (ERROR.equalsIgnoreCase(msgType)) {
        return new NeoResponse(400, body);
      }
      return NeoResponse.ok(body);
    } catch (JSONException e) {
      return NeoResponse.error(500, "Error parsing SII correction response: " + e.getMessage());
    }
  }

  private static JSONObject extractShowMsgInView(JSONObject handlerResult) throws JSONException {
    if (handlerResult == null) {
      return null;
    }
    JSONArray actions = handlerResult.optJSONArray(RESPONSE_ACTIONS);
    if (actions == null || actions.length() == 0) {
      return null;
    }
    JSONObject first = actions.optJSONObject(0);
    return first == null ? null : first.optJSONObject(SHOW_MSG_IN_VIEW);
  }

  /**
   * Ensures every error response carries a top-level {@code message} field.
   *
   * <p>{@code MultiEnvioFactura} (the classic SII sending process) reports a failed
   * single-invoice send (e.g. a missing required field caught by
   * {@code SIIUtils.invoicePreviousValidations}) through
   * {@link NeoProcessService#translateObuiappResult}, which already produces a body with a
   * top-level {@code message}. But any exception the classic process throws instead (a bug in
   * its own validation/classification code, a SOAP/network failure, etc.) is caught generically
   * by {@link NeoProcessService#executeObuiappClass} and returned via
   * {@link NeoResponse#error(int, String)}, whose body nests the text under {@code error.message}.
   *
   * <p>The frontend ({@code SifSendingModal.jsx}) only reads {@code response.message} /
   * {@code message} off the on-demand send response — it does not know about the
   * {@code error.message} shape used by generic CRUD/process error handling elsewhere. Without
   * this normalization, any such exception would silently degrade to a generic "HTTP 500" in the
   * UI instead of showing the real cause, which is exactly the "sent but nothing reached AEAT"
   * symptom this handler exists to prevent.
   *
   * <p>As of the fix generalizing this normalization into
   * {@link NeoResponse#ensureTopLevelMessage(NeoResponse)}, {@code NeoProcessService}'s
   * {@code executeObuiappClass} already applies it before returning, so this call is now
   * idempotent (the body already has a top-level {@code message} by the time it gets here).
   * Kept as a thin delegate — rather than removed — so this handler's intent stays
   * self-documenting and unaffected by future changes to where the shared service applies
   * its own normalization.
   *
   * <p>Package-private and static so it can be unit tested directly against a synthetic
   * {@link NeoResponse}, without needing a live AEAT connection or DB access.
   */
  static NeoResponse normalizeErrorShape(NeoResponse response) {
    return NeoResponse.ensureTopLevelMessage(response);
  }

  @Override
  protected boolean matchesActionName(String fieldName) {
    return ACTION_NAME.equals(fieldName)
        || ACTION_NAME_LEGACY.equals(fieldName)
        || ACTION_NAME_QUALIFIER.equals(fieldName);
  }

  @Override
  protected String buildExecutionErrorMessage(Exception e) {
    return "SII send failed: " + e.getMessage();
  }

  private static String resolveOrganizationId(Invoice invoice) {
    if (invoice == null || invoice.getOrganization() == null) {
      return null;
    }
    return invoice.getOrganization().getId();
  }
}
