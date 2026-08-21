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
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
 * <p><b>Two very different rejections.</b> Before the bank commits, nothing exists yet: the
 * attempt is reported in the payment modal, the form stays ready to try again, and no payment is
 * ever recorded. After {@code authorized} a payment does exist, and a later rejection — seen by
 * the PSD2 module's periodic refresh, the Salt Edge webhook, or the SPA's own poll — flags it
 * {@link #PAYMENT_STATUS_ERROR} through {@code markPaymentAsFailed}. That is what makes the
 * "Error" badge and the retry action reachable, on the invoice's payment list and on the payment
 * window alike.
 *
 * <p>The flag is applied by {@code PisRejectedPaymentHandler}, an observer on the row PSD2 saves,
 * so it lands the moment any writer records the rejection — PSD2's scheduled refresh, its manual
 * button, the webhook or this module's own poll. {@link #reconcileAttemptsFor} repeats the check
 * when a screen is opened, as the net for anything that changed outside a DAL flush.
 *
 * <p><b>Known gap.</b> The flagged payment is deliberately not reactivated, so it stays applied
 * and the invoice keeps reading as paid until the retry succeeds. The errored row is the only
 * signal that the money never moved.
 */
public final class PisDeferredPaymentService {

  private static final Logger log = LogManager.getLogger(PisDeferredPaymentService.class);

  /** Keys of the intent snapshot persisted on {@code EM_ETGO_Payment_Intent}. */
  private static final String INTENT_INVOICE_ID = "invoiceId";
  private static final String INTENT_IS_RECEIPT = "isReceipt";
  private static final String INTENT_BODY = "body";

  /** Key the SPA reads the transfer's local id from, and posts it back under. */
  private static final String FIELD_PIS_PAYMENT_ID = "pisPaymentId";
  private static final String FIELD_PIS = "pis";
  private static final String FIELD_PROCESS = "process";
  private static final String PROCESS_CONFIRM = "confirm";

  /** Salt Edge statuses from which a payment must exist in Etendo Go. */
  private static final String PIS_STATUS_AUTHORIZED = "authorized";

  /**
   * {@code FIN_Payment.status} for a payment whose bank transfer was rejected <em>after</em> the
   * bank had already committed to it — the value {@code ETGOERR} ("Payment Error"), added by this
   * module to the Core status reference {@code 575BCB88A4694C27BC013DE9C73E6FE7}.
   * <p>
   * The payment stays <b>processed</b>: it is deliberately not reactivated, so it keeps its
   * installment and any credit it consumed, and {@link #handleRetryPisPayment} can reuse it for a
   * fresh transfer instead of building a second one. The trade-off is that the invoice still reads
   * as paid until the retry succeeds — the errored row is the only signal that it is not.
   */
  private static final String PAYMENT_STATUS_ERROR = "ETGOERR";

  /**
   * {@code FIN_Payment.status} "Payment Made": confirmed, with the withdrawal from the account not
   * recorded yet. What a transfer the bank has committed to but not executed reads as, and what a
   * retried payment returns to while the new attempt is in flight.
   */
  private static final String PAYMENT_STATUS_PAYMENT_MADE = "PPM";

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
    // PSD2 keeps the reference only inside its payment-attributes JSON and leaves the column empty,
    // so without this the attempt counter below would never see a previous try and every retry
    // would reuse the same reference — the very duplicate this suffix exists to avoid.
    pisPayment.setEndToEnd(endToEndId);
    OBDal.getInstance().save(pisPayment);
    OBDal.getInstance().flush();

    JSONObject data = new JSONObject();
    // No payment exists yet — the SPA only needs what it takes to open the SCA widget and poll.
    data.put("pisPaymentUrl", result.getPaymentUrl());
    data.put(FIELD_PIS_PAYMENT_ID, pisPayment.getId());
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
    return withAttemptSuffix(StringUtils.defaultString(invoice.getDocumentNo(), invoice.getId()));
  }

  private static String withAttemptSuffix(String reference) {
    OBCriteria<PisPayment> crit = OBDal.getInstance().createCriteria(PisPayment.class);
    crit.add(Restrictions.like(PisPayment.PROPERTY_ENDTOEND, reference + "-%"));
    String suffix = "-" + (crit.count() + 1);
    int room = 35 - suffix.length();
    String prefix = reference.length() > room ? reference.substring(0, room) : reference;
    return prefix + suffix;
  }

  /**
   * The same per-attempt reference, based on a payment instead of an invoice. Used by the retry
   * that reuses an existing payment, where the bridge would otherwise resend the payment's own
   * {@code documentNo} verbatim on every try.
   */
  private static String nextEndToEndId(FIN_Payment payment) {
    String prefix = StringUtils.defaultString(payment.getDocumentNo(), payment.getId());
    return withAttemptSuffix(prefix);
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
   *
   * @param context the NEO request; the transfer to retry comes from its body, so this is reachable
   *     both from the invoice's payment modal and from the payment record itself
   * @return the new transfer's widget URL and local id, or an error explaining why this one can no
   *     longer be retried
   */
  public static NeoResponse handleRetryPisPayment(NeoContext context) {
    JSONObject body = context.getRequestBody();
    String pisPaymentId = body != null ? body.optString(FIELD_PIS_PAYMENT_ID, null) : null;
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
        // still in flight. The latter is what a user closing the Salt Edge window needs, because
        // that window's session is single-use and reopening its URL always fails with a lost
        // session, so the only way back is a brand-new order. Refused from `authorized` on, where
        // the money is already moving and a second order would pay twice.
        if (!isRetryableStatus(failed.getStatus())) {
          return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST,
              "This bank transfer is already in progress and can no longer be restarted.");
        }
        // A transfer the bank had already committed to left a payment behind, flagged ETGOERR by
        // markPaymentAsFailed. That payment still holds the invoice's installment and any credit it
        // consumed, so the retry reuses it instead of registering a second one — the only shape
        // that cannot pay the invoice twice, and the reason this branch needs no intent snapshot.
        FIN_Payment existing = failed.getPayment();
        if (existing != null) {
          return retryReusingPayment(failed, existing);
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
        OBDal.getInstance().save(failed);
        OBDal.getInstance().flush();

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

  /**
   * Starts a fresh transfer for a payment that already exists, reusing it rather than building a
   * second one.
   *
   * <p>Everything the bank needs is already on the payment, so no intent snapshot is involved —
   * which matters, because the snapshot is cleared the moment the payment is created. Only the
   * template and creditor account are carried over from the rejected attempt; the bridge derives
   * amount, currency, creditor and description from the payment itself.
   *
   * <p>The rejected {@code PSD2_PIS_PAYMENT} row is left untouched as the audit trail of the failed
   * attempt: {@code failed} is a final state, so the DAO opens a new row for the new order and
   * links it to the same payment.
   */
  private static NeoResponse retryReusingPayment(PisPayment rejected, FIN_Payment payment)
      throws Exception {
    JSONObject pisInput = new JSONObject();
    if (StringUtils.isNotBlank(rejected.getSaltedgeTemplate())) {
      pisInput.put("template", rejected.getSaltedgeTemplate());
    }
    if (StringUtils.isNotBlank(rejected.getCreditorIban())) {
      pisInput.put(BankIntegrationConstants.CREDITOR_IBAN, rejected.getCreditorIban());
    }
    pisInput.put(BankIntegrationConstants.END_TO_END_ID, nextEndToEndId(payment));

    BankIntegrationPISUtils.PISCreatePaymentResult result =
        PisPaymentBridge.initiatePisPayment(payment, pisInput, currentRequest());

    PisPayment retry = PISPaymentDao.findBySaltedgePaymentId(result.getPaymentId());
    if (retry == null) {
      throw new OBException(
          "The bank accepted the transfer but it could not be registered locally. "
              + "Check the bank payment before retrying.");
    }
    retry.setEndToEnd(pisInput.getString(BankIntegrationConstants.END_TO_END_ID));
    OBDal.getInstance().save(retry);
    // Back to "in progress": a transfer is in flight again, so the payment must stop reading as
    // failed. It returns to ETGOERR only if this attempt is rejected in turn.
    payment.setStatus(PAYMENT_STATUS_PAYMENT_MADE);
    OBDal.getInstance().save(payment);
    OBDal.getInstance().flush();

    JSONObject data = new JSONObject();
    data.put("pisPaymentUrl", result.getPaymentUrl());
    data.put(FIELD_PIS_PAYMENT_ID, retry.getId());
    data.put("pisStatus", retry.getStatus());
    data.put("paymentDeferred", true);
    return PaymentRegistrationService.wrapCreatedData(data);
  }

  /**
   * Brings a payment's Etendo Go side in line with whatever Salt Edge status is already stored,
   * called when a screen that shows the payment is opened.
   *
   * <p><b>Why a read does this.</b> {@link #reconcile} has exactly one other caller — the SPA's
   * poll — and that only runs while a transfer is in flight with the modal open. Everything that
   * resolves later is seen instead by the PSD2 module's own periodic refresh, which records the new
   * Salt Edge status but knows nothing about Etendo Go's payment. Without this hook a transfer the
   * bank refused after committing to it would sit in {@code PPM} forever, reading as still in
   * progress. Reconciling when someone opens the invoice or the payment closes that gap without a
   * second scheduled process.
   *
   * <p>No Salt Edge call is made here: it acts on the stored status, which the PSD2 refresh keeps
   * current. So this stays a cheap local read — but it is a read that can write, which is the point.
   *
   * <p>Never throws: a screen must still open even if reconciliation fails.
   *
   * @param payment the payment being displayed; ignored when null or not PIS-backed
   */
  public static void reconcileAttemptsFor(FIN_Payment payment) {
    if (payment == null) {
      return;
    }
    try {
      OBCriteria<PisPayment> crit = OBDal.getInstance().createCriteria(PisPayment.class);
      crit.add(Restrictions.eq(PisPayment.PROPERTY_PAYMENT, payment));
      for (PisPayment attempt : crit.list()) {
        reconcile(attempt);
      }
    } catch (Exception e) {
      log.warn("Could not reconcile the bank transfers of payment {}: {}", payment.getId(),
          e.getMessage());
    }
  }

  /** Invoice-level readings of the payment states below, worst-first. */
  public static final String INVOICE_TRANSFER_ERROR = "error";
  public static final String INVOICE_TRANSFER_IN_PROGRESS = "inProgress";

  /**
   * The worst payment state each of {@code invoiceIds} carries, so an invoice stops claiming to be
   * paid while the money behind it has not actually moved.
   *
   * <p>An invoice whose only payment is in progress or rejected has an outstanding of zero — the
   * payment is applied either way — so it read as "Pagada" while the payment itself read as
   * "Pago en progreso" or "Pago con error". Same fact, two screens, opposite answers (ETP-4895).
   *
   * <p>Deliberately keyed on the payment's own status rather than on whether it went through PIS:
   * these are the very states the payment badges show, so the invoice cannot disagree with them by
   * construction. A payment that reached its account keeps the invoice silent, as before.
   *
   * <p>Worst-first when several payments disagree: a rejection asks the user to do something, an
   * in-flight transfer only asks them to wait. Showing the one that needs action is what gets the
   * error noticed instead of buried behind a payment that is merely pending.
   *
   * @param invoiceIds the invoices on the response; may be empty
   * @return invoice id → {@link #INVOICE_TRANSFER_ERROR} or {@link #INVOICE_TRANSFER_IN_PROGRESS};
   *     absent for invoices with nothing to report
   */
  public static Map<String, String> transferStateByInvoice(Collection<String> invoiceIds) {
    if (invoiceIds == null || invoiceIds.isEmpty()) {
      return Collections.emptyMap();
    }
    Map<String, String> byInvoice = new HashMap<>();
    try {
      OBContext.setAdminMode(true);
      try {
        String hql = "select distinct psd.invoicePaymentSchedule.invoice.id, pd.finPayment.status "
            + "from FIN_Payment_Detail pd "
            + "join pd.fINPaymentScheduleDetailList psd "
            + "where psd.invoicePaymentSchedule.invoice.id in :invoiceIds "
            + "and pd.finPayment.status in :states";
        List<Object[]> rows = OBDal.getInstance().getSession()
            .createQuery(hql, Object[].class)
            .setParameterList("invoiceIds", invoiceIds)
            .setParameterList("states", List.of(PAYMENT_STATUS_ERROR, PAYMENT_STATUS_PAYMENT_MADE))
            .list();
        for (Object[] row : rows) {
          String invoiceId = (String) row[0];
          boolean rejected = StringUtils.equals(PAYMENT_STATUS_ERROR, (String) row[1]);
          // A rejection sticks: once seen it is never downgraded by another payment.
          if (rejected || !byInvoice.containsKey(invoiceId)) {
            byInvoice.put(invoiceId,
                rejected ? INVOICE_TRANSFER_ERROR : INVOICE_TRANSFER_IN_PROGRESS);
          }
        }
      } finally {
        OBContext.restorePreviousMode();
      }
    } catch (Exception e) {
      log.warn("Could not resolve the payment state of the listed invoices: {}", e.getMessage());
    }
    return byInvoice;
  }

  /**
   * Which of {@code paymentIds} have a bank transfer behind them.
   *
   * <p>One query for the whole set on purpose: this feeds a list response, and asking per row turned
   * a grid page into fifty round trips.
   *
   * @param paymentIds {@code FIN_Payment} ids to check; may be empty
   * @return the subset that has at least one {@code PSD2_PIS_PAYMENT} row
   */
  public static Set<String> paymentsWithBankTransfer(Collection<String> paymentIds) {
    if (paymentIds == null || paymentIds.isEmpty()) {
      return Collections.emptySet();
    }
    Set<String> found = new HashSet<>();
    try {
      OBContext.setAdminMode(true);
      try {
        OBCriteria<PisPayment> crit = OBDal.getInstance().createCriteria(PisPayment.class);
        crit.add(Restrictions.in(PisPayment.PROPERTY_PAYMENT + ".id", paymentIds));
        for (PisPayment attempt : crit.list()) {
          if (attempt.getPayment() != null) {
            found.add(attempt.getPayment().getId());
          }
        }
      } finally {
        OBContext.restorePreviousMode();
      }
    } catch (Exception e) {
      log.warn("Could not resolve which payments have a bank transfer: {}", e.getMessage());
    }
    return found;
  }

  /**
   * Whether a payment's lifecycle belongs to its bank transfer rather than to the user.
   *
   * <p>A transfer Etendo Go initiated owns the payment it produced: reactivating or deleting it
   * behind the bank's back would leave Salt Edge holding an order for a payment that no longer
   * exists, and — once executed — money that moved with nothing recording it. So those actions are
   * withdrawn for as long as the transfer is live.
   *
   * <p>The one exception is {@link #PAYMENT_STATUS_ERROR}: there the bank refused the transfer, no
   * money moved and nothing is in flight, so the payment is the user's to retry or discard.
   *
   * <p>Payments that never went through PIS are never locked — this returns false for them, which is
   * what keeps the ordinary flow untouched.
   *
   * @param status the payment's {@code FIN_Payment.status}
   * @param hasBankTransfer whether it has a {@code PSD2_PIS_PAYMENT} row (see
   *     {@link #paymentsWithBankTransfer})
   * @return true when Reactivate and Delete must be withheld from this payment
   */
  public static boolean isLifecycleLockedByTransfer(String status, boolean hasBankTransfer) {
    return hasBankTransfer && !StringUtils.equals(PAYMENT_STATUS_ERROR, status);
  }

  /**
   * The rejected bank transfer {@code paymentId} can be retried from, or {@code null} when there is
   * none.
   *
   * <p>Only a payment flagged {@link #PAYMENT_STATUS_ERROR} qualifies: that is the state
   * {@link #markPaymentAsFailed} leaves behind when the bank refuses a transfer it had already
   * committed to, and the only one where retrying reuses the existing payment. A payment that is
   * merely in progress must not offer a retry — a second order there would pay twice.
   *
   * @param paymentId the {@code FIN_Payment} being displayed
   * @return the rejected attempt to replay, or {@code null}
   */
  public static PisPayment findRetryableAttempt(String paymentId) {
    if (StringUtils.isBlank(paymentId)) {
      return null;
    }
    FIN_Payment payment = OBDal.getInstance().get(FIN_Payment.class, paymentId);
    if (payment == null || !StringUtils.equals(PAYMENT_STATUS_ERROR, payment.getStatus())) {
      return null;
    }
    OBCriteria<PisPayment> crit = OBDal.getInstance().createCriteria(PisPayment.class);
    crit.add(Restrictions.eq(PisPayment.PROPERTY_PAYMENT, payment));
    crit.add(Restrictions.eq(PisPayment.PROPERTY_STATUS,
        BankIntegrationConstants.PIS_STATUS_FAILED));
    crit.addOrderBy(PisPayment.PROPERTY_CREATIONDATE, false);
    crit.setMaxResults(1);
    return (PisPayment) crit.uniqueResult();
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
      // Two very different rejections share this status.
      //
      // Before the bank committed, nothing was ever created: the money never moved, so recording
      // the attempt would only leave a row to clean up. The user is told in the modal and retries.
      // The snapshot is deliberately KEPT — it is what handleRetryPisPayment replays.
      //
      // After `authorized`, a payment already exists, and leaving it in PPM would show the transfer
      // as still in progress for something the bank has definitively refused. It is flagged instead
      // and offered for retry.
      markPaymentAsFailed(pisPayment);
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
   * Flags an already-created payment whose transfer the bank went on to refuse.
   * <p>
   * Deliberately does <b>not</b> reactivate it. Keeping it processed keeps it holding the invoice's
   * installment and any credit it consumed, which is what lets the retry reuse this very payment
   * rather than create a second one — the shape that cannot pay the invoice twice. See
   * {@link #PAYMENT_STATUS_ERROR} for what that costs.
   */
  private static void markPaymentAsFailed(PisPayment pisPayment) {
    FIN_Payment payment = pisPayment.getPayment();
    if (payment == null || StringUtils.equals(PAYMENT_STATUS_ERROR, payment.getStatus())) {
      return;
    }
    if (isStaleAttempt(pisPayment, payment)) {
      return;
    }
    payment.setStatus(PAYMENT_STATUS_ERROR);
    OBDal.getInstance().save(payment);
    OBDal.getInstance().flush();
    log.info("PIS {} was rejected after the bank had committed — payment {} flagged as {}",
        pisPayment.getId(), payment.getDocumentNo(), PAYMENT_STATUS_ERROR);
  }

  /**
   * True when this rejected transfer no longer describes the payment as it stands.
   *
   * <p>The flag means one specific thing: <em>this payment, as it is right now, was produced by a
   * transfer the bank refused</em>. Three situations break that link, and in all of them re-flagging
   * undoes something the user or a later transfer already did:
   *
   * <ol>
   *   <li><b>A newer attempt exists.</b> A retry starts a second transfer and puts the payment back
   *       in progress; the rejected row stays only as the audit trail of the attempt that failed.
   *   <li><b>The payment is no longer processed.</b> The user reactivated it: it is a draft being
   *       reworked, and a draft is not "a payment whose transfer failed".
   *   <li><b>The payment changed after this attempt last did.</b> It was reactivated and confirmed
   *       again — possibly by another method entirely — so whatever it is now, this rejection is
   *       not what produced it.
   * </ol>
   *
   * <p>Without these, {@link #reconcileAttemptsFor} — which walks every attempt each time a screen
   * opens — kept dragging the payment back to {@link #PAYMENT_STATUS_ERROR}: a retry read as failed
   * again on the next window load, and a reactivated payment came back from the server still
   * flagged, half draft ({@code processed = N}) and half errored.
   *
   * @param attempt the rejected transfer being evaluated
   * @param payment the payment it produced
   * @return true when the rejection must not be applied to the payment
   */
  static boolean isStaleAttempt(PisPayment attempt, FIN_Payment payment) {
    if (isSupersededByNewerAttempt(attempt)) {
      return true;
    }
    // Boxed on the entity, so compare rather than unbox: a null would blow up on a read path.
    if (!Boolean.TRUE.equals(payment.isProcessed())) {
      return true;
    }
    Date attemptAt = attempt.getLastStatusAt() != null ? attempt.getLastStatusAt()
        : attempt.getCreationDate();
    return attemptAt != null && payment.getUpdated() != null
        && payment.getUpdated().after(attemptAt);
  }

  /**
   * True when a later transfer exists on the same payment, which makes this rejected one history.
   *
   * <p>A retry starts a second {@code PSD2_PIS_PAYMENT} row against the same payment and puts it
   * back in progress; the rejected row stays as the audit trail of the attempt that failed. Without
   * this guard that row keeps re-flagging the payment as {@link #PAYMENT_STATUS_ERROR} every time
   * anything touches it — {@link #reconcileAttemptsFor} walks <em>all</em> attempts when a screen
   * opens, and PSD2's refresh fires an update event even when the status it writes is unchanged —
   * so a retry read as failed again the moment the window was reloaded.
   *
   * <p>Only the newest attempt speaks for the payment. If that one is refused in turn, it is the
   * one that flags it.
   *
   * @param attempt the rejected transfer being evaluated
   * @return true when a newer attempt exists for the same payment
   */
  static boolean isSupersededByNewerAttempt(PisPayment attempt) {
    FIN_Payment payment = attempt.getPayment();
    if (payment == null || attempt.getCreationDate() == null) {
      return false;
    }
    OBCriteria<PisPayment> crit = OBDal.getInstance().createCriteria(PisPayment.class);
    crit.add(Restrictions.eq(PisPayment.PROPERTY_PAYMENT, payment));
    crit.add(Restrictions.gt(PisPayment.PROPERTY_CREATIONDATE, attempt.getCreationDate()));
    crit.setMaxResults(1);
    return !crit.list().isEmpty();
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
