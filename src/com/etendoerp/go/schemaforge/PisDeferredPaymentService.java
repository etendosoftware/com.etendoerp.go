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

import java.math.BigDecimal;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.criterion.Restrictions;
import org.openbravo.base.exception.OBException;
import org.openbravo.client.kernel.RequestContext;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.invoice.Invoice;
import org.openbravo.model.financialmgmt.payment.FIN_FinancialAccount;
import org.openbravo.model.financialmgmt.payment.FIN_Payment;

import com.etendoerp.psd2.bank.integration.data.PisPayment;
import com.etendoerp.psd2.bank.integration.utils.BankIntegrationConstants;
import com.etendoerp.psd2.bank.integration.utils.BankIntegrationPISUtils;
import com.etendoerp.psd2.bank.integration.utils.PISPaymentDao;
import com.etendoerp.psd2.bank.integration.utils.PISTransactionUtils;

/**
 * Deferred creation of the {@link FIN_Payment} behind a PIS (Salt Edge) bank transfer.
 *
 * <p><b>Why this exists.</b> Etendo Go used to create <em>and process</em> the payment to
 * {@code PPM} before Salt Edge was even contacted, so an invoice read as paid the instant the user
 * clicked "Continue to bank" — regardless of whether the transfer was ever authorized, and with no
 * automatic way back if it was not (ETP-4895). The payment is now built only once Salt Edge
 * reports a <em>resolutive</em> status:
 *
 * <table>
 *   <caption>Salt Edge status → Etendo Go</caption>
 *   <tr><td>{@code requested} / {@code initiated} / {@code initiated_info_required} /
 *           {@code authorizing}</td><td>nothing exists yet</td></tr>
 *   <tr><td>{@code authorized}</td><td>payment created and processed</td></tr>
 *   <tr><td>{@code executed} / {@code settled}</td><td>created if needed, plus the bank
 *           transaction</td></tr>
 *   <tr><td>{@code failed}</td><td>nothing is created — the user is told and simply tries
 *           again</td></tr>
 * </table>
 *
 * <p><b>How the request survives.</b> Because no {@code FIN_Payment} is created up front, the
 * request itself is snapshotted as JSON on {@code PSD2_PIS_PAYMENT.EM_ETGO_Payment_Intent}. When a
 * resolutive status finally arrives — minutes later, from a background refresh — {@link #reconcile}
 * replays that snapshot through the ordinary
 * {@link PaymentRegistrationService#doRegisterPaymentAdvanced} path with {@code pis} switched off,
 * so credit consumption, installment linking, overpayment handling and processing all behave
 * exactly as they do for a non-PIS payment. Nothing about payment creation is reimplemented here.
 *
 * <p><b>What drives it.</b> {@link #reconcile} runs from Etendo Go's own status poll
 * ({@code PisPaymentService#refreshPisStatusFromSaltEdge}), which the payment modal calls every few
 * seconds while the transfer is in flight. A payment appears as soon as the bank commits to the
 * transfer ({@code authorized} onwards); a rejection creates nothing at all.
 *
 * <p>The one case this leaves open is a transfer that is still at an intermediate status when the
 * modal gives up waiting: nothing registers it afterwards, and it has to be entered by hand. This
 * is a deliberate trade — covering it would mean reacting to the PSD2 module's own background
 * refresh, which cannot call into Etendo Go without inverting the dependency between the two
 * modules. {@link #reconcile} is idempotent, so wiring such a trigger later needs no change here.
 *
 * <p>A transfer the user abandons — or one the bank rejects — never produces a payment, so the
 * invoice is left untouched either way and there is nothing to undo.
 *
 * <p><b>Why some pieces here look unused.</b> {@link #handleRetryPisPayment}, the {@code ETGOERR}
 * payment status and the "Error"/retry affordances in the SPA are currently unreachable: a
 * rejection is only ever observed while the payment modal is open, and there it is reported in
 * place, with the form left ready to try again. They are kept on purpose for the case this design
 * does not cover yet — a rejection arriving when nobody is watching (the PSD2 module's own
 * background refresh, or the Salt Edge webhook). There is no modal to report that in, so the
 * attempt would have to be recorded on the invoice instead, which is exactly what those pieces do.
 * Do not delete them as dead code without also deciding that case is out of scope.
 */
final class PisDeferredPaymentService {

  private static final Logger log = LogManager.getLogger(PisDeferredPaymentService.class);

  /** Keys of the intent snapshot persisted on {@code EM_ETGO_Payment_Intent}. */
  private static final String INTENT_INVOICE_ID = "invoiceId";
  private static final String INTENT_IS_RECEIPT = "isReceipt";
  private static final String INTENT_BODY = "body";

  private static final String FIELD_PIS = "pis";
  private static final String FIELD_PROCESS = "process";
  private static final String PROCESS_CONFIRM = "confirm";

  /** Salt Edge statuses from which a payment must exist in Etendo Go. */
  private static final String PIS_STATUS_AUTHORIZED = "authorized";

  private PisDeferredPaymentService() {
  }

  // ─── initiation ────────────────────────────────────────────────────────────

  /**
   * Starts a PIS transfer <em>without</em> creating any {@link FIN_Payment}, snapshotting the
   * request so it can be replayed once the bank resolves.
   *
   * @param invoice
   *     the invoice being paid — supplies the creditor and the end-to-end reference
   * @param account
   *     the financial account debited; mandatory on {@code PSD2_PIS_PAYMENT} and the source of the
   *     debtor IBAN and PSD2 provider
   * @param body
   *     the original SPA request, stored verbatim as the intent
   */
  static NeoResponse initiateDeferredPis(Invoice invoice, FIN_FinancialAccount account,
      BigDecimal amount, JSONObject body, JSONObject pisInput, boolean isReceipt) throws Exception {
    String endToEndId = nextEndToEndId(invoice);

    BankIntegrationPISUtils.PISCreatePaymentResult result =
        PisPaymentBridge.initiateDeferredPisPayment(invoice, account, amount, endToEndId, pisInput,
            currentRequest());

    PisPayment pisPayment = PISPaymentDao.findBySaltedgePaymentId(result.getPaymentId());
    if (pisPayment == null) {
      // The transfer was accepted by Salt Edge but we have no local row to track it. Surfacing
      // this loudly is better than returning a success the SPA would then poll forever.
      throw new OBException(
          "The bank accepted the transfer but it could not be registered locally. "
              + "Check the bank payment before retrying.");
    }
    pisPayment.setETGOPaymentIntent(buildIntent(invoice, body, isReceipt).toString());
    OBDal.getInstance().save(pisPayment);
    OBDal.getInstance().flush();

    JSONObject data = new JSONObject();
    // No payment exists yet — the SPA only needs what it takes to open the SCA widget and poll.
    data.put("pisPaymentUrl", result.getPaymentUrl());
    data.put("pisPaymentId", pisPayment.getId());
    data.put("pisStatus", pisPayment.getStatus());
    data.put("paymentDeferred", true);
    return PaymentRegistrationService.wrapCreatedData(data);
  }

  /**
   * Builds a bank reference that is unique per attempt.
   * <p>
   * It used to be the payment's own {@code documentNo}, which a retry would resubmit verbatim —
   * end-to-end ids must be unique per debtor account, so a duplicate risks a silent bank-side
   * reject or a false "already processed" match. With no payment to borrow a number from, the
   * invoice's document number is suffixed with the attempt count for that invoice.
   * <p>
   * Capped at 35 characters, the limit {@code GenerateBankPayment} enforces.
   */
  private static String nextEndToEndId(Invoice invoice) {
    String prefix = StringUtils.defaultString(invoice.getDocumentNo(), invoice.getId());
    OBCriteria<PisPayment> crit = OBDal.getInstance().createCriteria(PisPayment.class);
    crit.add(Restrictions.like(PisPayment.PROPERTY_ENDTOEND, prefix + "-%"));
    int attempt = crit.count() + 1;
    String suffix = "-" + attempt;
    int room = 35 - suffix.length();
    if (prefix.length() > room) {
      prefix = prefix.substring(0, room);
    }
    return prefix + suffix;
  }

  /** Snapshot replayed by {@link #reconcile}: enough to rebuild the payment from scratch. */
  private static JSONObject buildIntent(Invoice invoice, JSONObject body, boolean isReceipt)
      throws Exception {
    JSONObject intent = new JSONObject();
    intent.put(INTENT_INVOICE_ID, invoice.getId());
    intent.put(INTENT_IS_RECEIPT, isReceipt);
    intent.put(INTENT_BODY, body);
    return intent;
  }

  private static HttpServletRequest currentRequest() {
    return RequestContext.get() != null ? RequestContext.get().getRequest() : null;
  }

  /**
   * Retries a bank transfer the bank rejected, straight from the failed attempt.
   *
   * <p>Because a failed transfer never produced a {@link FIN_Payment} (nothing is registered before
   * a resolutive status), retrying is simply replaying the original request — a brand-new Salt Edge
   * payment with a fresh, unique end-to-end reference. The failed {@code PSD2_PIS_PAYMENT} row is
   * kept untouched as the audit trail of the attempt, with its snapshot released so it cannot be
   * replayed twice.
   *
   * <p>Body: {@code {pisPaymentId}}.
   */
  static NeoResponse handleRetryPisPayment(NeoContext context) {
    JSONObject body = context.getRequestBody();
    String pisPaymentId = body != null ? body.optString("pisPaymentId", null) : null;
    if (StringUtils.isBlank(pisPaymentId)) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, "pisPaymentId is required");
    }
    try {
      OBContext.setAdminMode(true);
      try {
        PisPayment failed = OBDal.getInstance().get(PisPayment.class, pisPaymentId);
        if (failed == null) {
          return NeoResponse.error(HttpServletResponse.SC_NOT_FOUND, "PIS payment not found");
        }
        // Anything the bank has not committed to can be retried: a rejection, and also an attempt
        // still in flight. The latter is what a user closing the Salt Edge window needs — that
        // window's session is single-use, so reopening its URL always fails with "session lost";
        // the only way back is a brand-new order. Refused from `authorized` on, where the money is
        // already moving and a second order would pay twice.
        if (!isRetryableStatus(failed.getStatus())) {
          return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST,
              "This bank transfer is already in progress and can no longer be restarted.");
        }
        String raw = failed.getETGOPaymentIntent();
        if (StringUtils.isBlank(raw)) {
          return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST,
              "This bank transfer can no longer be retried automatically. Register the payment "
                  + "again from the invoice.");
        }
        JSONObject intent = new JSONObject(raw);
        // Release the old attempt before starting a new one, so a failure mid-retry cannot leave
        // two rows both claiming the same intent.
        failed.setETGOPaymentIntent(null);
        FIN_Payment errored = failed.getPayment();
        failed.setPayment(null);
        OBDal.getInstance().save(failed);
        OBDal.getInstance().flush();

        // Drop the errored payment too. It is an unprocessed draft, but it still holds the
        // invoice's installment and any credit it consumed — leaving it behind would make the
        // retry find nothing left to pay. Deleting it releases both.
        if (errored != null) {
          PaymentDraftEditService.deleteDraftPayment(errored.getId());
        }

        // Replay the original request verbatim: it still carries pis=true, so it comes straight
        // back through the deferred-initiation branch and starts a fresh transfer.
        return PaymentRegistrationService.doRegisterPaymentAdvanced(
            intent.getString(INTENT_INVOICE_ID), intent.getJSONObject(INTENT_BODY),
            intent.optBoolean(INTENT_IS_RECEIPT, false));
      } finally {
        OBContext.restorePreviousMode();
      }
    } catch (OBException e) {
      OBDal.getInstance().rollbackAndClose();
      log.warn("Retry of PIS payment {} failed: {}", pisPaymentId, e.getMessage());
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, e.getMessage());
    } catch (Exception e) {
      OBDal.getInstance().rollbackAndClose();
      log.error("Error retrying PIS payment {}: {}", pisPaymentId, e.getMessage(), e);
      return NeoResponse.error(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
          "Failed to retry the bank transfer");
    }
  }

  // ─── reconciliation ────────────────────────────────────────────────────────

  /**
   * Brings {@code pisPayment}'s Etendo Go side in line with its current Salt Edge status.
   *
   * <p>Idempotent by construction: it does nothing unless the status is resolutive and the payment
   * is still missing, and the bank transaction is created through the PSD2 module's own
   * already-idempotent utility. Safe to call concurrently from the several refresh paths.
   *
   * @return true when this call created the payment
   */
  static boolean reconcile(PisPayment pisPayment) {
    String status = pisPayment.getStatus();
    if (isFailedStatus(status)) {
      // Rejected: nothing is created. The money never moved, so recording the attempt would only
      // leave a row to clean up; the user is told and retries. The snapshot is deliberately KEPT —
      // it is what {@link #handleRetryPisPayment} replays, which is the path that matters if this
      // rejection is ever observed with no modal open to report it in (see the class javadoc).
      return false;
    }
    if (!requiresPayment(status)) {
      return false;
    }
    boolean created = false;
    if (pisPayment.getPayment() == null) {
      created = createPaymentFromIntent(pisPayment);
    }
    // executed/settled additionally book the money movement. The PSD2 utility no-ops when a
    // transaction already exists, so repeated calls are harmless.
    if (isSettledStatus(status) && pisPayment.getPayment() != null) {
      PISTransactionUtils.createFinancialTransactionIfEligible(pisPayment);
    }
    return created;
  }

  /**
   * True once the bank has committed to the transfer: {@code authorized} onwards. Everything else
   * produces no payment at all.
   * <p>
   * That deliberately includes {@code failed}. A rejected transfer moved no money, so recording it
   * as a payment would leave a row the user has to clean up for an attempt that never happened —
   * noise on the invoice, and one more thing that can be mistaken for a real payment. The user is
   * told the transfer was rejected and simply tries again.
   */
  private static boolean requiresPayment(String status) {
    return StringUtils.equalsAnyIgnoreCase(status, PIS_STATUS_AUTHORIZED,
        BankIntegrationConstants.PIS_STATUS_EXECUTED, BankIntegrationConstants.PIS_STATUS_SETTLED);
  }

  /** True when Salt Edge rejected the transfer. Nothing is created; the attempt is just closed. */
  private static boolean isFailedStatus(String status) {
    return BankIntegrationConstants.PIS_STATUS_FAILED.equalsIgnoreCase(status);
  }

  /**
   * True while the attempt can be abandoned and replaced by a fresh one: the bank has not committed
   * to it, so no money is at risk of moving twice. Everything from {@code authorized} onwards is
   * excluded — restarting there could pay the invoice a second time.
   */
  private static boolean isRetryableStatus(String status) {
    return StringUtils.isBlank(status) || !requiresPayment(status);
  }

  private static boolean isSettledStatus(String status) {
    return StringUtils.equalsAnyIgnoreCase(status, BankIntegrationConstants.PIS_STATUS_EXECUTED,
        BankIntegrationConstants.PIS_STATUS_SETTLED);
  }

  /**
   * Replays the stored intent through the ordinary payment-registration path and links the result.
   * <p>
   * {@code pis} is switched off so the replay registers a plain payment instead of starting a
   * second bank transfer, and {@code process} is forced to {@code confirm} because reaching this
   * point means the bank committed to the transfer. A rejected transfer never gets here.
   */
  private static boolean createPaymentFromIntent(PisPayment pisPayment) {
    String raw = pisPayment.getETGOPaymentIntent();
    if (StringUtils.isBlank(raw)) {
      // Not an Etendo Go deferred transfer (e.g. initiated from Classic, which creates its own
      // payment up front). Nothing to replay.
      return false;
    }
    try {
      JSONObject intent = new JSONObject(raw);
      JSONObject body = intent.getJSONObject(INTENT_BODY);
      body.put(FIELD_PIS, false);
      body.put(FIELD_PROCESS, PROCESS_CONFIRM);

      NeoResponse response = PaymentRegistrationService.doRegisterPaymentAdvanced(
          intent.getString(INTENT_INVOICE_ID), body, intent.optBoolean(INTENT_IS_RECEIPT, false));

      String paymentId = extractPaymentId(response);
      if (StringUtils.isBlank(paymentId)) {
        log.error("PIS {}: replaying the payment intent returned no payment id; response={}",
            pisPayment.getId(), response != null ? response.getBody() : null);
        return false;
      }
      FIN_Payment payment = OBDal.getInstance().get(FIN_Payment.class, paymentId);
      pisPayment.setPayment(payment);
      // The snapshot has served its purpose; clearing it keeps a replay from ever running twice.
      pisPayment.setETGOPaymentIntent(null);
      OBDal.getInstance().save(pisPayment);
      OBDal.getInstance().flush();
      log.info("PIS {} reached status {} — created payment {}", pisPayment.getId(),
          pisPayment.getStatus(), payment != null ? payment.getDocumentNo() : paymentId);
      return true;
    } catch (Exception e) {
      log.error("PIS {}: could not create the payment from its stored intent: {}",
          pisPayment.getId(), e.getMessage(), e);
      return false;
    }
  }

  /** Digs the created payment's id out of the {@code {response:{data:{id}}}} envelope. */
  private static String extractPaymentId(NeoResponse response) {
    if (response == null || response.getBody() == null) {
      return null;
    }
    JSONObject data = response.getBody().optJSONObject("response");
    if (data == null) {
      return null;
    }
    JSONObject payload = data.optJSONObject("data");
    return payload != null ? payload.optString("id", null) : null;
  }

}
