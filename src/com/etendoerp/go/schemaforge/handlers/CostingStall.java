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

package com.etendoerp.go.schemaforge.handlers;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.service.OBDal;
import org.openbravo.erpCommon.ad_forms.AcctServer;

import com.etendoerp.go.schemaforge.NeoResponse;

/**
 * The refusal of a post whose cost is not calculated because costing itself is stuck (ETP-5714).
 *
 * <p>"Cost not calculated yet" is normally a matter of seconds: the costing background process
 * runs about every 30 seconds, works the queue oldest first and the post can simply be repeated.
 * In a live tenant it never succeeded: the client's costing rule used the standard algorithm, the
 * products had no standard cost, and the process aborted on the oldest transaction on every run
 * (the run still ended {@code SUC}), so everything behind it waited for ever. Telling that caller
 * "retry in 30 seconds" is wrong, so when an uncalculated transaction of the document's
 * organisation is older than {@link #STALLED_AFTER_MINUTES} the answer says costing looks stalled and
 * that an administrator must act.</p>
 */
final class CostingStall {

  private static final Logger log = LogManager.getLogger(CostingStall.class);

  /**
   * How old the oldest uncalculated transaction may be before costing is judged stalled. The queue
   * is worked oldest first about every 30 seconds, so one that waited this long is not waiting for
   * its turn.
   */
  static final int STALLED_AFTER_MINUTES = 10;
  /** The AD message and the identity ({@code messageKeys}) of the refusal. */
  static final String MSG_COSTING_STALLED = "ETGO_CostCalculationStalled";
  /** The error code of the refusal; not retryable. */
  static final String ERROR_COSTING_STALLED = "costCalculationStalled";
  /** {@code messageParams} entry carrying {@link #STALLED_AFTER_MINUTES}. */
  static final String MESSAGE_PARAMS_STALLED_AFTER_MINUTES = "stalledAfterMinutes";
  private static final int SC_UNPROCESSABLE = 422;

  private static final String FALLBACK_TEXT = "Cost calculation looks stalled: transactions have "
      + "been waiting for their cost for a long time, so repeating the post will not help. An "
      + "administrator must check the Costing Background process and the pending transactions.";
  private static final String HINT = "Retrying will not help. An administrator must check the "
      + "Costing Background process (its last runs) and the transactions still waiting for cost "
      + "(M_Transaction with iscostcalculated N): a transaction the process cannot cost, for "
      + "example a product with no cost for the organisation's costing rule, blocks every one "
      + "behind it. Nothing was posted.";
  /** Costing queue probe: is any uncalculated stocked transaction older than N minutes? */
  private static final String QUEUE_SQL = "SELECT EXISTS (SELECT 1 FROM m_transaction t "
      + "JOIN m_product p ON p.m_product_id = t.m_product_id "
      + "WHERE t.ad_client_id = ? AND t.ad_org_id = ? AND t.iscostcalculated = 'N' "
      + "AND t.costing_status <> 'S' AND p.producttype = 'I' AND p.isstocked = 'Y' "
      + "AND t.trxprocessdate < now() - (? * interval '1 minute'))";

  private CostingStall() {
  }

  /**
   * Whether the costing queue of the failing document's organisation is stuck. Fails OPEN to "not
   * stalled": the probe only chooses between two refusals of a post that failed anyway, so an
   * unreadable queue keeps the retryable answer.
   *
   * @param acct the failed accounting server, which knows the document's client and organisation
   * @return {@code true} when an uncalculated stocked transaction is older than the threshold
   */
  static boolean isQueueStalled(AcctServer acct) {
    if (StringUtils.isAnyBlank(acct.AD_Client_ID, acct.AD_Org_ID)) {
      return false;
    }
    try (PreparedStatement ps = OBDal.getInstance().getConnection().prepareStatement(QUEUE_SQL)) {
      ps.setString(1, acct.AD_Client_ID);
      ps.setString(2, acct.AD_Org_ID);
      ps.setInt(3, STALLED_AFTER_MINUTES);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() && rs.getBoolean(1);
      }
    } catch (Exception e) {
      log.debug("Could not probe the costing queue of org {}", acct.AD_Org_ID, e);
      return false;
    }
  }

  /**
   * The failure result of a stalled costing queue.
   *
   * @param localizedText the AD message in the GO locale; blank or the key itself when the message
   *                      is missing, which keeps the English fallback
   * @return the result, naming {@link #MSG_COSTING_STALLED} and the threshold
   */
  static DocumentPostingService.PostResult failure(String localizedText) {
    boolean usable = StringUtils.isNotBlank(localizedText)
        && !MSG_COSTING_STALLED.equals(localizedText);
    return new DocumentPostingService.PostResult(false, usable ? localizedText : FALLBACK_TEXT,
        List.of(MSG_COSTING_STALLED), Map.of(MESSAGE_PARAMS_STALLED_AFTER_MINUTES, STALLED_AFTER_MINUTES));
  }

  /**
   * Whether a post result is the stalled-costing refusal.
   *
   * @param result the post outcome
   * @return {@code true} for a failure naming {@link #MSG_COSTING_STALLED}
   */
  static boolean isStalled(DocumentPostingService.PostResult result) {
    return !result.ok() && result.messageKeys().contains(MSG_COSTING_STALLED);
  }

  /**
   * The HTTP answer: 422, the code as {@code error}, {@code retryable:false}, no
   * {@code retryAfterSeconds} and no {@code Retry-After}, and a hint for an administrator.
   *
   * @param body   the flat body already carrying {@code success}, {@code message} and the identity
   * @param result the stalled post result
   * @return the response
   * @throws JSONException if the body cannot be completed
   */
  static NeoResponse answer(JSONObject body, DocumentPostingService.PostResult result)
      throws JSONException {
    body.put("error", ERROR_COSTING_STALLED);
    body.put("status", SC_UNPROCESSABLE);
    body.put("detail", result.message());
    body.put("retryable", false);
    body.put("hint", HINT);
    return NeoResponse.error(SC_UNPROCESSABLE, body);
  }

  private static final String COST_RETRY_HINT = "Cost is calculated by a background process that "
      + "runs about every 30 seconds. Nothing was posted and the document keeps the posting "
      + "status Etendo set (posted NC) until a later post succeeds: wait about 30 seconds and "
      + "repeat the same post action.";

  /** @return whether the failure is the retryable "cost not calculated yet" one (ETP-5714) */
  static boolean isRetryable(DocumentPostingService.PostResult result) {
    return !result.ok() && result.messageKeys().contains(DocumentPostingService.MSG_NOT_CALCULATED_COST)
        && result.messageParams().containsKey(DocumentPostingService.MESSAGE_PARAMS_RETRY_AFTER_SECONDS);
  }

  /**
   * The 409 answer for a post that failed only because cost is not calculated yet (ETP-5714):
   * the flat body the SPA already reads ({@code message}, {@code messageKeys}) plus the canonical
   * MCP error shape — {@code error} is the code, so the MCP envelope keeps it instead of
   * replacing it with a generic one — {@code retryable} and {@code retryAfterSeconds}; REST
   * additionally gets the standard {@code Retry-After} header.
   */
  static NeoResponse retryLater(JSONObject body, DocumentPostingService.PostResult result) throws JSONException {
    body.put("error", DocumentPostingService.ERROR_COST_NOT_CALCULATED);
    body.put("status", DocumentPostingService.STATUS_RETRY_LATER);
    body.put("detail", result.message());
    body.put("retryable", true);
    body.put(DocumentPostingService.MESSAGE_PARAMS_RETRY_AFTER_SECONDS, DocumentPostingService.COST_RETRY_AFTER_SECONDS);
    body.put("hint", COST_RETRY_HINT);
    return NeoResponse.error(DocumentPostingService.STATUS_RETRY_LATER, body)
        .withHeader("Retry-After", String.valueOf(DocumentPostingService.COST_RETRY_AFTER_SECONDS));
  }
}
