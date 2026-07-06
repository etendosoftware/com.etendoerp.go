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

import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.core.OBContext;

/**
 * Shared template for legacy invoice action handlers exposed through NEO action endpoints.
 */
abstract class AbstractLegacyInvoiceActionHandler implements NeoHandler {

  private static final String RECORD_ID_REQUIRED = "Record ID is required";

  @Override
  public final NeoResponse handle(NeoContext context) {
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
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, RECORD_ID_REQUIRED);
    }

    try {
      OBContext.setAdminMode(true);
      try {
        return executeAction(recordId);
      } finally {
        OBContext.restorePreviousMode();
      }
    } catch (Exception e) {
      return NeoResponse.error(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
          buildExecutionErrorMessage(e));
    }
  }

  protected abstract boolean matchesActionName(String fieldName);

  protected abstract NeoResponse executeAction(String recordId) throws Exception;

  protected abstract String buildExecutionErrorMessage(Exception e);

  /**
   * Invokes an OBUIAPP handler class for a single record, building the minimal
   * {@code recordId}/{@code inpRecordId}/{@code recordIds} payload the shared
   * {@link NeoProcessService#executeObuiappClass} bridge needs to resolve the input record.
   */
  protected static NeoResponse executeSingleRecordObuiappClass(String processClass,
      String processId, String recordId) throws Exception {
    JSONObject params = new JSONObject();
    params.put("recordId", recordId);
    params.put("inpRecordId", recordId);
    JSONArray recordIds = new JSONArray();
    recordIds.put(recordId);
    params.put("recordIds", recordIds);

    return NeoProcessService.executeObuiappClass(processClass, processId, params);
  }
}
