/*
 * *************************************************************************
 * The contents of this file are subject to the Etendo License
 * (the "License"); you may not use this file except in compliance
 * with the License.
 * You may obtain a copy of the License at
 * https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * Software distributed under the License is distributed on an
 * "AS IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or implied.
 * See the License for the specific language governing rights and limitations under the License.
 * All portions are Copyright (C) 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */
package com.etendoerp.go.mcp;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.core.OBContext;

import com.etendoerp.go.schemaforge.BatchJobService;
import com.etendoerp.go.schemaforge.BatchService;
import com.etendoerp.go.schemaforge.NeoWriteChannel;

/**
 * The asynchronous batch tools of the MCP surface (ETP-5677): {@code etendo_batch} with
 * {@code async:true}, {@code etendo_job_status} and {@code etendo_job_cancel}.
 *
 * <p>They live here, apart from {@link McpToolRouter}, which only routes the tools and delegates
 * the job ones here — the router stays within the size its quality gate allows.</p>
 */
final class McpBatchJobTools {

  private static final Logger log = LogManager.getLogger(McpBatchJobTools.class);

  private McpBatchJobTools() {
    // Utility class: it is never instantiated.
  }

  /**
   * {@code etendo_batch} with {@code async:true} (ETP-5677): records the batch as a job and answers
   * with its id. The worker runs every row through the same {@code executeBatch} and the same MCP
   * body transforms the synchronous call uses, as admin-mode DAL work under the caller's identity —
   * exactly the context the synchronous tool gets.
   *
   * @param operations   the batch operations
   * @param args         the tool arguments ({@code _onlyReturnIds}, {@code _skipRedundantCallouts})
   * @param preprocessor the MCP body transform applied to every operation
   * @return the accepted job: its id, its state and the row/operation counters
   * @throws JSONException on an unexpected serialisation failure
   */
  static JSONObject submitBatchJob(JSONArray operations, JSONObject args,
      BatchService.OperationPreprocessor preprocessor) throws JSONException {
    boolean onlyReturnIds = args.optBoolean(BatchService.PARAM_ONLY_RETURN_IDS, false);
    boolean skipCallouts = args.optBoolean(BatchService.PARAM_SKIP_REDUNDANT_CALLOUTS, false);
    BatchService batchService = BatchService.forBatchOnly();
    return McpToolRouter.wrapAsTextContent(BatchJobService.getInstance()
        .submit(BatchJobService.Owner.current(), operations, "mcp",
            rowOps -> runMcpBatchRow(batchService, rowOps, onlyReturnIds, skipCallouts,
                preprocessor)));
  }

  /**
   * One row of an async MCP batch, shaped exactly like the synchronous call's outcome.
   *
   * @param batchService  the batch service that writes the row
   * @param rowOps        the operations of the row
   * @param onlyReturnIds whether the writes skip decorating the record they echo back
   * @param skipCallouts  whether redundant callouts may be skipped
   * @param preprocessor  the MCP body transform applied to every operation
   * @return the batch envelope of the row
   * @throws JSONException on an unexpected serialisation failure
   */
  private static JSONObject runMcpBatchRow(BatchService batchService, JSONArray rowOps,
      boolean onlyReturnIds, boolean skipCallouts,
      BatchService.OperationPreprocessor preprocessor) throws JSONException {
    OBContext.setAdminMode();
    // ETP-5791: the row runs on a job worker thread, outside McpServlet; label its writes as MCP.
    String previousChannel = NeoWriteChannel.enter(NeoWriteChannel.MCP);
    try {
      JSONObject result = batchService.executeBatch(rowOps, preprocessor, onlyReturnIds,
          skipCallouts);
      if (!result.optBoolean("committed", false)) {
        McpBatchEnvelope.toMcpBatchFailure(result);
      }
      return result;
    } finally {
      NeoWriteChannel.restore(previousChannel);
      OBContext.restorePreviousMode();
    }
  }

  /**
   * {@code etendo_job_status} / {@code etendo_job_cancel} (ETP-5677).
   *
   * @param toolName the tool that was called
   * @param args     the tool arguments; it must carry the job id
   * @return the job as the MCP content of the call
   */
  static JSONObject handleBatchJob(String toolName, JSONObject args) {
    String jobId = args == null ? null : args.optString("jobId", null);
    if (StringUtils.isBlank(jobId)) {
      return McpToolRouter.wrapAsErrorContent("jobId is required");
    }
    try {
      BatchJobService.Owner owner = BatchJobService.Owner.current();
      JSONObject job = McpConstants.TOOL_NEO_JOB_CANCEL.equals(toolName)
          ? BatchJobService.getInstance().cancel(owner, jobId)
          : BatchJobService.getInstance().status(owner, jobId);
      return McpToolRouter.wrapAsTextContent(job);
    } catch (BatchJobService.JobException e) {
      return McpToolRouter.wrapAsErrorContent(jobErrorMessage(e));
    } catch (Exception e) {
      log.error("Error executing {} session={}", toolName, McpUsageTelemetry.sessionForLog(), e);
      return McpToolRouter.wrapAsErrorContent("Error executing " + toolName + ": " + e.getMessage());
    }
  }

  /**
   * @param e the refusal the job service answered with
   * @return the message to show the caller, with the retry hint when there is one
   */
  static String jobErrorMessage(BatchJobService.JobException e) {
    return e.getRetryAfter() == null ? e.getMessage()
        : e.getMessage() + " (retry after " + e.getRetryAfter() + " s)";
  }
}
