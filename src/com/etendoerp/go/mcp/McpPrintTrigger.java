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

package com.etendoerp.go.mcp;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONObject;

import com.etendoerp.go.schemaforge.NeoPrintAfterComplete;
import com.etendoerp.go.schemaforge.data.SFEntity;
import com.etendoerp.go.schemaforge.data.SFSpec;

/**
 * Queues the server-side PDF of a document after a successful MCP document action (ETP-6013).
 *
 * <p>Wraps the router's single-document action path — {@code etendo_action}, and every document of
 * {@code etendo_action_batch} — so the action itself ({@code McpToolRouter.handleAction}) stays
 * untouched. After a success it hands the document to {@link NeoPrintAfterComplete#afterAction},
 * which decides (the {@code DocAction} button on an entity declaring {@code MCP_CONFIG.pdf.print})
 * and renders later, after the call commits. Nothing here can change the action's answer.</p>
 */
final class McpPrintTrigger {

  private static final Logger log = LogManager.getLogger(McpPrintTrigger.class);

  static final String KEY_IS_ERROR = "isError";
  static final String PARAM_ACTION = "action";

  private McpPrintTrigger() {
  }

  /**
   * The action path, followed by the server-side PDF queueing on a success.
   *
   * @param action the single-document action path
   * @return the same path, queueing the PDF after it answered a success
   */
  static McpActionBatch.ActionRunner queueingServerPdf(McpActionBatch.ActionRunner action) {
    return (specName, args) -> {
      JSONObject result = action.run(specName, args);
      if (result != null && !result.optBoolean(KEY_IS_ERROR, false)) {
        queue(specName, args);
      }
      return result;
    };
  }

  /**
   * Hands the acted-on document to {@link NeoPrintAfterComplete}, in the MCP's PDF language (the
   * email tool's default, so a later email and this PDF match). Never throws.
   *
   * @param specName the spec of the call
   * @param args     the action's arguments: {@code entity}, {@code id}, {@code action}
   */
  static void queue(String specName, JSONObject args) {
    try {
      SFSpec spec = McpToolRouterSupport.findActiveSpecByName(specName);
      SFEntity entity = McpToolRouterSupport.findIncludedEntity(spec.getId(),
          args.getString(McpConstants.PARAM_ENTITY));
      NeoPrintAfterComplete.afterAction(entity, args.getString(McpConstants.PARAM_ID),
          args.getString(PARAM_ACTION), true, McpDocumentEmailRequest.DEFAULT_LANGUAGE);
    } catch (Exception e) {
      log.debug("[print] server-side PDF not queued: {}", e.getClass().getSimpleName());
    }
  }
}
