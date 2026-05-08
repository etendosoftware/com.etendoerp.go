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
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.client.application.Process;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;

/**
 * NeoHandler delegate for the legacy TicketBAI XML generation button on Sales Invoice.
 *
 * <p>Classic Etendo wires {@code Em_Tbai_Xmlgenerator} to
 * {@code com.smf.ticketbai.process.XMLConvertionFromInvoice}, an OBUIAPP handler
 * that expects a {@code recordIds} array in its request content. This adapter
 * builds that payload for a single invoice and delegates to the standard NEO
 * OBUIAPP execution path.
 */
@Named("tbai-xmlgenerator")
public class TbaiXmlgeneratorHandler implements NeoHandler {

  static final String ACTION_NAME = "EM_Tbai_Xmlgenerator";
  static final String ACTION_NAME_LEGACY = "Em_Tbai_Xmlgenerator";
  static final String ACTION_NAME_QUALIFIER = "tbaiXmlgenerator";
  private static final String PROCESS_ID = "BE2486102F2C41779B760609FD69A225";

  @Override
  public NeoResponse handle(NeoContext context) {
    if (!NeoEndpointType.ACTION.equals(context.getEndpointType())) {
      return null;
    }
    if (!"POST".equals(context.getHttpMethod())) {
      return null;
    }
    if (!matchesActionName(context.getFieldName())) {
      return null;
    }

    String recordId = context.getRecordId();
    if (StringUtils.isBlank(recordId)) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, "Record ID is required");
    }

    try {
      OBContext.setAdminMode(true);
      try {
        Process process = OBDal.getInstance().get(Process.class, PROCESS_ID);
        if (process == null) {
          return NeoResponse.error(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
              "TicketBAI process not found: " + PROCESS_ID);
        }

        JSONObject params = new JSONObject();
        params.put("recordId", recordId);
        params.put("inpRecordId", recordId);
        JSONArray recordIds = new JSONArray();
        recordIds.put(recordId);
        params.put("recordIds", recordIds);

        return NeoProcessService.executeObuiappProcess(process, params);
      } finally {
        OBContext.restorePreviousMode();
      }
    } catch (Exception e) {
      return NeoResponse.error(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
          "TicketBAI XML generation failed: " + e.getMessage());
    }
  }

  private boolean matchesActionName(String fieldName) {
    return ACTION_NAME.equals(fieldName)
        || ACTION_NAME_LEGACY.equals(fieldName)
        || ACTION_NAME_QUALIFIER.equals(fieldName);
  }
}
