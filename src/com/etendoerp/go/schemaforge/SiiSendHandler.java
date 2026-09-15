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
 * correction/modification envelope, communication type {@code A1}. Whenever that flag is
 * set, this handler routes to the classic "Modificar" action,
 * {@code org.openbravo.module.sii.process.MultiInvoiceSIIModification}, regardless of the
 * invoice's AEAT error code. Like {@code MultiEnvioFactura}, it extends
 * {@code BaseActionHandler}, so it is invoked through the same
 * {@link NeoProcessService#executeObuiappClass} bridge.
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

  @Override
  protected NeoResponse executeAction(String recordId) throws Exception {
    Invoice invoice = OBDal.getInstance().get(Invoice.class, recordId);

    if (invoice != null && Boolean.TRUE.equals(invoice.isAeatsiiErrorRegistral())) {
      return executeRegistralModification(recordId, invoice);
    }

    NeoResponse response = NeoProcessService.executeObuiappClass(PROCESS_CLASS, PROCESS_ID,
        buildInvoiceSendParams(recordId, invoice));
    return normalizeErrorShape(response);
  }

  /**
   * Routes a registry-error resend to {@code MultiInvoiceSIIModification}, the classic module's
   * "Modificar" action (communication type {@code A1}), whenever
   * {@link Invoice#isAeatsiiErrorRegistral()} is {@code true} — regardless of the invoice's
   * AEAT error code. Like {@code MultiEnvioFactura}, this is a real {@code BaseActionHandler},
   * so it is invoked through the same {@link NeoProcessService#executeObuiappClass} bridge.
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
