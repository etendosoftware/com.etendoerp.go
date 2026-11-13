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

import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;

import com.etendoerp.go.schemaforge.telemetry.AccountingKpiTelemetry;

/**
 * Feeds the accounting KPI telemetry (ETP-6230) from one finished MCP tool call: period refusals
 * ({@code backend_period_refused}) and the first-document probe
 * ({@code backend_first_document_processed}). The MCP half of what {@code NeoServlet} does for
 * REST. Enqueue only — nothing here touches the business transaction — and it never throws.
 *
 * <p>Runs independently of {@link McpUsageLogger#isEnabled()}: the KPI must not depend on the usage
 * log switch.</p>
 */
final class McpAccountingKpiObserver {

  private static final Logger log = LogManager.getLogger(McpAccountingKpiObserver.class);

  /** Tools that change data: the only ones that can make a document processed. */
  private static final Set<String> WRITE_TOOLS = Set.of(McpConstants.TOOL_NEO_CREATE,
      McpConstants.TOOL_NEO_UPDATE, McpConstants.TOOL_NEO_DELETE, McpConstants.TOOL_NEO_ACTION,
      McpConstants.TOOL_NEO_ACTION_BATCH, McpConstants.TOOL_NEO_BATCH);
  /** Tools whose answer re-renders old job rows on every poll: never observed for refusals. */
  private static final Set<String> JOB_TOOLS = Set.of(McpConstants.TOOL_NEO_JOB_STATUS,
      McpConstants.TOOL_NEO_JOB_CANCEL);
  private static final Set<String> BATCH_TOOLS = Set.of(McpConstants.TOOL_NEO_ACTION_BATCH,
      McpConstants.TOOL_NEO_BATCH);

  private McpAccountingKpiObserver() {
  }

  static void observe(String clientId, String toolName, JSONObject params, JSONObject result) {
    try {
      if (StringUtils.isBlank(toolName) || StringUtils.isBlank(clientId) || result == null
          || JOB_TOOLS.contains(toolName)) {
        return;
      }
      JSONObject arguments = params != null ? params.optJSONObject("arguments") : null;
      String spec = argument(arguments, McpConstants.PARAM_SPEC);
      String entity = argument(arguments, McpConstants.PARAM_ENTITY);
      boolean batch = BATCH_TOOLS.contains(toolName);
      String source = batch ? AccountingKpiTelemetry.SOURCE_BATCH : AccountingKpiTelemetry.SOURCE_MCP;
      String action = argument(arguments, McpActionBatch.PARAM_ACTION);
      boolean actionTool = McpConstants.TOOL_NEO_ACTION.equals(toolName)
          || McpConstants.TOOL_NEO_ACTION_BATCH.equals(toolName);
      String operation = actionTool && action != null ? action : McpUsageTelemetry.verbFor(toolName);
      boolean failed = McpUsageTelemetry.isError(result);

      if (failed || batch) {
        observeRefusals(clientId, source, spec, entity, operation, result, failed);
      }
      if (!failed && WRITE_TOOLS.contains(toolName)) {
        AccountingKpiTelemetry.enqueueFirstDocumentProbe(clientId, source, spec, entity,
            actionTool ? action : null);
      }
    } catch (RuntimeException e) {
      log.debug("Accounting KPI telemetry: could not observe MCP tool '{}'.", toolName, e);
    }
  }

  private static void observeRefusals(String clientId, String source, String spec, String entity,
      String operation, JSONObject result, boolean failed) {
    String text = McpActionBatch.textOf(result);
    JSONObject body = StringUtils.startsWith(StringUtils.trim(text), "{") ? parse(text) : null;
    if (body != null) {
      AccountingKpiTelemetry.observeRefusal(clientId, source, spec, entity, operation, body, null);
    } else if (failed) {
      // prose envelope (pre-IMP-5): same recognition REST uses on a plain message
      AccountingKpiTelemetry.observeRefusal(clientId, source, spec, entity, operation, null, text);
    }
  }

  private static JSONObject parse(String text) {
    try {
      return new JSONObject(text);
    } catch (org.codehaus.jettison.json.JSONException e) {
      return null;
    }
  }

  private static String argument(JSONObject arguments, String name) {
    return arguments == null ? null : StringUtils.trimToNull(arguments.optString(name, null));
  }
}
