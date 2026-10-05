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

import org.apache.commons.lang3.StringUtils;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;

import com.etendoerp.go.schemaforge.BatchService;

/**
 * The outcome envelope {@code neo_batch} answers a failed batch with, whether the batch was
 * rejected by the MCP pre-pass before the transaction opened or failed inside
 * {@link BatchService#executeBatch}. Split from {@link McpToolRouterSupport} (ETP-5558).
 */
final class McpBatchEnvelope {

  private McpBatchEnvelope() {
  }

  /**
   * Error codes of a batch operation that no change to its body can fix: the entity refuses the
   * verb ({@code method_not_allowed}) or cannot be attached to a parent at all
   * ({@code parent_unresolvable}). The criterion is the code, not the status — a 422 can just as
   * well be a field the agent can correct.
   */
  private static final java.util.Set<String> NON_RETRYABLE_OP_CODES = java.util.Set.of(
      McpConstants.ERROR_METHOD_NOT_ALLOWED, McpConstants.ERROR_PARENT_UNRESOLVABLE);

  /**
   * Report a batch rejected by the MCP FK pre-pass in the same outcome envelope a batch failure
   * always uses (ETP-4793 / IMP-5 clause (i)).
   *
   * <p><b>The envelope used to differ by failure class.</b> A batch that failed inside
   * {@code executeBatch} came back as {@code {committed:false, atomic, persisted, hint, failedAt,
   * error:{…}}}. A batch rejected by the FK-by-name pre-pass — which runs <em>before</em>
   * {@code executeBatch} — came back as the resolver's flat error with a {@code failedAt} bolted on
   * and <b>no {@code committed} key at all</b> (evidence C9), so an agent branching on
   * {@code committed}, exactly as the tool description tells it to, read {@code false} from a missing
   * key by luck or crashed on it. One condition, two shapes, and the difference was invisible from
   * the call site.</p>
   *
   * <p><b>{@code atomic:true} / {@code persisted:[]} are true here by construction</b>, not by
   * observation — a stronger guarantee than {@code executeBatch} can give. IMP-23 §1 found that the
   * discriminator three benchmark runs had missed was exactly this: a pre-pass failure happens before
   * the transaction opens, so nothing can have persisted, which is why these failures always
   * <em>looked</em> atomic while persist-time failures were not. What used to be an accident of
   * timing is now a claim the response makes. The hint says so specifically rather than reusing
   * {@code BatchService}'s "rolled back as a unit" wording: no rollback happened, because no
   * transaction was opened.</p>
   *
   * @param fkError the resolver's structured error for the first op that failed to resolve
   * @param index   the index of that operation in the {@code operations} array
   * @param opId    that operation's caller-supplied {@code id}, or {@code null} when it declared none
   * @return the batch outcome envelope
   * @throws JSONException never in practice (all values are plain strings/ints)
   */
  static JSONObject toMcpBatchPreflightFailure(JSONObject fkError, int index, String opId)
      throws JSONException {
    JSONObject body = new JSONObject();
    body.put(BatchService.FIELD_COMMITTED, false);
    body.put(BatchService.FIELD_ATOMIC, true);
    body.put(BatchService.FIELD_PERSISTED, new JSONArray());
    String persisted = "Nothing was persisted: the batch was rejected before the transaction "
        + "opened, so no records were created and none need cleaning up. ";
    // ETP-5558: a refusal no body change can fix (the verb is hidden, the parent cannot be
    // identified) must not be met with "fix it and retry" while its own error says "do not retry".
    String errorCode = fkError == null ? null : fkError.optString(McpConstants.KEY_ERROR, null);
    body.put(BatchService.FIELD_HINT, NON_RETRYABLE_OP_CODES.contains(errorCode)
            ? persisted + "The operation reported in 'failedAt' can never succeed as written — "
                + "its 'error' says why and what to use instead. Remove or replace that "
                + "operation, then retry the rest of the batch."
            : persisted + "Fix the operation reported in 'failedAt' and retry the whole batch.");
    JSONObject failedAt = new JSONObject();
    failedAt.put("index", index);
    if (StringUtils.isNotBlank(opId)) {
      failedAt.put("id", opId);
    }
    body.put("failedAt", failedAt);
    body.put(McpConstants.KEY_ERROR, fkError);
    return body;
  }

  /**
   * Rewrite a {@code BatchService} failure body into the IMP-5 error envelope (IMP-15).
   * <p>
   * {@code BatchService} serves both the REST {@code /batch} endpoint and {@code neo_batch}, and it
   * forwards the failing operation's sub-response verbatim as {@code error.detail}. For an MCP agent
   * that meant a raw DAL payload — {@code {"response":{"status":-4,"errors":{"id":"New object
   * Currency(null) (key: EUR_Currency) refered to but not present in the import set"}}}} — with no
   * error code, no field and no next step, while the single-record verbs had carried a structured
   * envelope since IMP-5. The translation happens here rather than in {@code BatchService} so the
   * REST contract, and any non-MCP caller reading {@code detail}, stay untouched.
   * <p>
   * Success bodies ({@code committed:true}) and bodies with no {@code error} object pass through
   * unchanged. The {@code failedAt} pointer is always preserved — it is what tells the agent which
   * operation to fix.
   *
   * @param result the body returned by {@code BatchService#executeBatch}, mutated in place
   * @return the same object, for call chaining
   * @throws JSONException never in practice (all values are plain strings/ints)
   */
  static JSONObject toMcpBatchFailure(JSONObject result) throws JSONException {
    if (result == null || result.optBoolean("committed", false)) {
      return result;
    }
    JSONObject rawError = result.optJSONObject(McpConstants.KEY_ERROR);
    if (rawError == null || isImp5Envelope(rawError)) {
      // ETP-5558: an MCP preprocessor rejection (an McpRoutingException, the FK resolver, the image
      // check) already carries its IMP-5 envelope. Rewriting it by status flattened it to
      // validation_error / "Batch operation failed" and lost the code, detail and hint the
      // single-record verb returns for the same condition.
      return result;
    }
    int status = rawError.optInt(McpConstants.KEY_STATUS, 500);
    String message = rawError.optString(McpConstants.KEY_MESSAGE, "Batch operation failed");
    JSONObject detail = rawError.optJSONObject(McpConstants.KEY_DETAIL);

    JSONArray missingFields = McpSupportInternals.extractMissingFields(detail);
    if (missingFields != null) {
      result.put(McpConstants.KEY_ERROR, McpSupportInternals.buildBatchMissingFieldsError(missingFields));
      return result;
    }

    String dalMessage = McpSupportInternals.extractDalMessage(detail);

    JSONObject clean = new JSONObject();
    clean.put(McpConstants.KEY_STATUS, status);
    clean.put(McpConstants.KEY_ERROR, McpSupportInternals.errorCodeForStatus(status));
    clean.put(McpConstants.KEY_DETAIL, dalMessage == null ? message : message + ": " + dalMessage);
    clean.put(McpConstants.KEY_SEE_ALSO, McpConstants.SEE_ALSO_WRITING);
    result.put(McpConstants.KEY_ERROR, clean);
    return result;
  }

  /**
   * Whether a batch {@code error} object is already in the IMP-5 shape — it carries a string
   * {@code error} code. {@code BatchService}'s own failures carry {@code status}/{@code message}/
   * {@code detail} and never an {@code error} key, so the two cannot be confused.
   */
  private static boolean isImp5Envelope(JSONObject error) {
    Object code = error.opt(McpConstants.KEY_ERROR);
    return code instanceof String && StringUtils.isNotBlank((String) code);
  }
}
