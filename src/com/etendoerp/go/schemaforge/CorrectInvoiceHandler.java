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
import org.codehaus.jettison.json.JSONObject;

/**
 * NeoHandler delegate for the legacy Correct_Invoice button on invalid VF invoices.
 *
 * <p>Classic Etendo wires {@code Correct_Invoice} to the OBUIAPP handler
 * {@code com.etendoerp.verifactu.process.CorrectedInvoice}, which marks the invoice
 * as corrected ({@code em_etvfac_corrected_inv = 'Y'}) so it can be resubmitted to
 * Verifactu. Without this handler, the button falls through to the generic
 * {@code NeoButtonActionHelper} bridge, which enforces an {@code OBUIAPP_Process_Access}
 * grant that was never configured for this process — silently returning 403 for every
 * role except System Administrator. This adapter bypasses that check the same way
 * {@link SiiSendHandler} and {@link TbaiXmlgeneratorHandler} do for their legacy buttons.
 */
@Named("correct-invoice-handler")
public class CorrectInvoiceHandler extends AbstractLegacyInvoiceActionHandler {

  static final String ACTION_NAME = "Correct_Invoice";
  static final String ACTION_NAME_QUALIFIER = "correctInvoice";
  private static final String PROCESS_ID = "F353F2A7307B464CA2C6515CBEFB0D93";
  private static final String PROCESS_CLASS = "com.etendoerp.verifactu.process.CorrectedInvoice";

  @Override
  protected NeoResponse executeAction(String recordId) throws Exception {
    JSONObject params = new JSONObject();
    params.put("recordId", recordId);
    params.put("inpRecordId", recordId);
    JSONArray recordIds = new JSONArray();
    recordIds.put(recordId);
    params.put("recordIds", recordIds);

    return NeoProcessService.executeObuiappClass(PROCESS_CLASS, PROCESS_ID, params);
  }

  @Override
  protected boolean matchesActionName(String fieldName) {
    return ACTION_NAME.equals(fieldName) || ACTION_NAME_QUALIFIER.equals(fieldName);
  }

  @Override
  protected String buildExecutionErrorMessage(Exception e) {
    return "Invoice correction failed: " + e.getMessage();
  }
}
