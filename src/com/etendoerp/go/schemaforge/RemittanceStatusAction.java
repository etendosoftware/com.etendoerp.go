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
 * All portions are Copyright © 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.schemaforge;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Map;

import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.module.remittance.Remittance;
import org.openbravo.module.remittance.utility.RemittanceStatusUtil;

import com.etendoerp.automated.remittance.hooks.RemittanceProcessHookImpl;
import com.etendoerp.go.schemaforge.util.NeoActionContract;
import com.etendoerp.go.schemaforge.util.NeoMessageTranslator;

/**
 * ETP-6247 — the two steps after «Confirmar» of the remittance header
 * ({@link RemittanceHeaderHandler}), a Holded-like flow in which the remittance is NOT settled when
 * it is confirmed:
 * <ul>
 *   <li>{@value #ACTION_MARK_SENT} (write): «Enviada al banco», {@code PR -> SB}. Purely
 *       informational: only the status changes ({@link RemittanceStatusUtil#markSentToBank}); no
 *       payment, settlement or accounting row is touched;</li>
 *   <li>{@value #ACTION_MARK_COLLECTED} (write): «Cobrada», from {@code PR} or {@code SB}: settles
 *       every payment of the remittance on the remittance DUE DATE with the module's own
 *       settlement ({@link RemittanceProcessHookImpl#settleOnDueDate}, which runs
 *       {@code REM_SettleProtest.cancelPayment}: a settlement row and a bank transaction per
 *       payment, the payment received/made, the invoices paid). The status ends {@code CT}
 *       (collection) or {@code PT} (payment) and the remittance is flagged
 *       {@code EM_Etar_Automaticprocess = 'Y'} — «settled by the automated-remittance module» —
 *       which is what Classic and the SPA read to offer the returns and hide «Reactivar». All or
 *       nothing: any failure rolls everything back, 422.</li>
 * </ul>
 * Both are POST only and sit behind {@code sepa-remittances} like the rest of the header.
 */
final class RemittanceStatusAction {

  private static final Logger log = LogManager.getLogger(RemittanceStatusAction.class);

  static final String ACTION_MARK_SENT = "markSent";
  static final String ACTION_MARK_COLLECTED = "markCollected";

  static final String MSG_SENT_NOT_ALLOWED = "ETGO_RemittanceMarkSentNotAllowed";
  static final String MSG_COLLECTED_NOT_ALLOWED = "ETGO_RemittanceMarkCollectedNotAllowed";
  static final String MSG_COLLECTED_FAILED = "ETGO_RemittanceMarkCollectedFailed";

  private static final int SC_UNPROCESSABLE = NeoActionContract.SC_UNPROCESSABLE;
  private static final String POST = "POST";
  private static final String KEY_STATUS = "status";

  private RemittanceStatusAction() {
  }

  /** Declares the two actions on the remittance header. */
  static void addContracts(Map<String, NeoActionContract> contracts) {
    contracts.put(ACTION_MARK_SENT, NeoActionContract.write(ACTION_MARK_SENT,
        "Marks a confirmed remittance as sent to the bank (status PR to SB). Informational only: "
            + "nothing is settled, no payment or accounting row changes. Refused unless the "
            + "remittance is confirmed and still PR. Takes no parameters. Returns status.")
        .withIdDescription("the id of the remittance"));
    contracts.put(ACTION_MARK_COLLECTED, NeoActionContract.write(ACTION_MARK_COLLECTED,
        "Marks a confirmed remittance as collected (a payment remittance: paid): settles every "
            + "payment of it on the remittance due date, with the remittance module's own "
            + "settlement (a bank transaction per payment, the invoices paid), all or nothing. "
            + "Allowed from PR or SB; the status becomes CT (PT for a payment remittance) and the "
            + "remittance can no longer be reactivated, but its lines can be returned. Takes no "
            + "parameters. Returns status and settledOn (the due date, yyyy-MM-dd).")
        .withIdDescription("the id of the remittance"));
  }

  /** @return whether {@code action} is one of this class's actions */
  static boolean serves(String action) {
    return ACTION_MARK_SENT.equals(action) || ACTION_MARK_COLLECTED.equals(action);
  }

  /**
   * Whether {@code status} is one a remittance cannot be reactivated, sent or collected from: it
   * has settlements or returns, or was voided.
   *
   * @param status a {@code REM_REMITTANCE.REMITTANCE_STATUS} value, may be {@code null}
   * @return {@code true} for CT, PT, CP, PP and AN
   */
  static boolean isSettledOrVoided(Object status) {
    return RemittanceStatusUtil.COLLECTED.equals(status) || RemittanceStatusUtil.PAID.equals(status)
        || RemittanceStatusUtil.PARTIALLY_COLLECTED.equals(status)
        || RemittanceStatusUtil.PARTIALLY_PAID.equals(status)
        || RemittanceStatusUtil.VOIDED.equals(status);
  }

  /**
   * Runs one of the actions: 405 when not a POST, the contract's 422 for a malformed call, 404 for
   * an unknown remittance. The remittance row is locked ({@code SELECT ... FOR UPDATE}, through
   * {@link RemittanceHeaderHandler#lockRemittance}) before its status is validated, so two
   * concurrent requests cannot both pass the check and settle twice: the second waits, then sees
   * the status the first one left.
   *
   * @param handler the remittance header customization (its seams)
   * @param context the action request
   * @return the answer
   */
  static NeoResponse handle(RemittanceHeaderHandler handler, NeoContext context) {
    String action = context.getFieldName();
    if (!POST.equals(context.getHttpMethod())) {
      return NeoResponse.error(HttpServletResponse.SC_METHOD_NOT_ALLOWED,
          action + " only supports POST");
    }
    OBContext.setAdminMode(true);
    try {
      JSONObject body = context.getRequestBody() != null ? context.getRequestBody()
          : new JSONObject();
      NeoResponse invalid = NeoActionContract.validate(handler.actionContracts(), action,
          RemittanceHeaderActions.actionParams(handler, action, body));
      if (invalid != null) {
        return invalid;
      }
      Remittance remittance = handler.loadRemittance(context.getRecordId());
      if (remittance == null) {
        return NeoResponse.error(HttpServletResponse.SC_NOT_FOUND, "Remittance not found");
      }
      // Row lock BEFORE the state check: two concurrent «Cobrada» (or «Enviada» racing
      // «Cobrada») would otherwise both see PR/SB and settle every payment twice.
      handler.lockRemittance(remittance);
      return ACTION_MARK_SENT.equals(action) ? markSent(remittance) : markCollected(remittance);
    } catch (Exception e) {
      log.error("remittance action {} failed on {}", action, context.getRecordId(), e);
      NeoErrorResponses.rollbackUnlessBatchOwned();
      return NeoResponse.error(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
          "Could not run " + action + ". Please check logs for details.");
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /**
   * «Enviada al banco».
   *
   * @param remittance a remittance of the tenant, already row-locked by the caller
   * @return {@code {status}}, or 422
   * @throws JSONException when the answer body cannot be built
   */
  static NeoResponse markSent(Remittance remittance) throws JSONException {
    if (!Boolean.TRUE.equals(remittance.isProcessed())) {
      return NeoMessageText.error(SC_UNPROCESSABLE, MSG_SENT_NOT_ALLOWED);
    }
    refresh(remittance);
    if (!markSentToBank(remittance)) {
      return NeoMessageText.error(SC_UNPROCESSABLE, MSG_SENT_NOT_ALLOWED);
    }
    return NeoResponse.ok(new JSONObject().put(KEY_STATUS, RemittanceStatusUtil.SENT_TO_BANK));
  }

  /**
   * «Cobrada».
   *
   * @param remittance a remittance of the tenant, already row-locked by the caller
   * @return {@code {status, settledOn}}, or 422
   * @throws JSONException when the answer body cannot be built
   */
  static NeoResponse markCollected(Remittance remittance) throws JSONException {
    if (!Boolean.TRUE.equals(remittance.isProcessed())) {
      return NeoMessageText.error(SC_UNPROCESSABLE, MSG_COLLECTED_NOT_ALLOWED);
    }
    refresh(remittance);
    Object status = remittance.get(RemittanceStatusUtil.PROPERTY_REMITTANCE_STATUS);
    if (!RemittanceStatusUtil.PROCESSED.equals(status)
        && !RemittanceStatusUtil.SENT_TO_BANK.equals(status)) {
      return NeoMessageText.error(SC_UNPROCESSABLE, MSG_COLLECTED_NOT_ALLOWED);
    }
    String remittanceId = remittance.getId();
    Date dueDate = remittance.getDueDate();
    // The Classic helpers of the settlement read RequestContext.get().getVariablesSecureApp(),
    // which a NEO request does not guarantee: pushed here, restored on close.
    try (NeoProcessService.RequestContextScope ignored =
        NeoProcessService.pushRequestContextVars()) {
      settle(remittance);
      OBDal.getInstance().flush();
      Remittance settled = OBDal.getInstance().get(Remittance.class, remittanceId);
      Object after = settled != null
          ? settled.get(RemittanceStatusUtil.PROPERTY_REMITTANCE_STATUS)
          : null;
      // The module can swallow some of its own errors: verify the outcome, never assume it.
      if (!RemittanceStatusUtil.COLLECTED.equals(after) && !RemittanceStatusUtil.PAID.equals(after)) {
        throw new IllegalStateException("The remittance was not fully settled, its status is "
            + after);
      }
      settled.setETARAutomaticprocess(true);
      OBDal.getInstance().save(settled);
      OBDal.getInstance().flush();
      JSONObject body = new JSONObject().put(KEY_STATUS, after);
      body.put("settledOn", dueDate != null
          ? new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(dueDate)
          : JSONObject.NULL);
      return NeoResponse.ok(body);
    } catch (Exception e) {
      log.error("markCollected failed on remittance {}", remittanceId, e);
      String text = StringUtils.defaultIfBlank(
          NeoMessageTranslator.safeParseTranslation(e.getMessage()), "-");
      // Translated first: the rollback closes the session.
      NeoErrorResponses.rollbackUnlessBatchOwned();
      return NeoMessageText.error(SC_UNPROCESSABLE, MSG_COLLECTED_FAILED, text);
    }
  }

  // ---------------------------------------------------------------------------
  // Seams
  // ---------------------------------------------------------------------------

  /**
   * Seam: refreshes a stale stored status. A remittance that existed before the status column keeps
   * its default {@code PE} until a REM transition refreshes it; {@code SB} is kept.
   */
  static void refresh(Remittance remittance) {
    RemittanceStatusUtil.refresh(remittance);
  }

  /** Seam over {@link RemittanceStatusUtil#markSentToBank}. */
  static boolean markSentToBank(Remittance remittance) {
    return RemittanceStatusUtil.markSentToBank(remittance);
  }

  /** Seam over {@link RemittanceProcessHookImpl#settleOnDueDate}. */
  static void settle(Remittance remittance) {
    new RemittanceProcessHookImpl().settleOnDueDate(remittance);
  }
}
