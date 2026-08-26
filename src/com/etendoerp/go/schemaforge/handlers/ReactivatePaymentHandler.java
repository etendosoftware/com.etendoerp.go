/*
 *************************************************************************
 * The contents of this file are subject to the Etendo License
 * (the "License"), you may not use this file except in compliance
 * with the License.
 * You may obtain a copy of the License at
 * https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * Software distributed under the License is distributed on an
 * "AS IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
 * implied. See the License for the specific language governing rights
 * and limitations under the License.
 * All portions are Copyright (C) 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 *************************************************************************
 */

package com.etendoerp.go.schemaforge.handlers;

import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.inject.Named;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.criterion.Restrictions;
import org.openbravo.advpaymentmngt.process.FIN_AddPayment;
import org.openbravo.advpaymentmngt.utility.FIN_Utility;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.currency.Currency;
import org.openbravo.model.financialmgmt.payment.FIN_FinaccTransaction;
import org.openbravo.model.financialmgmt.payment.FIN_FinancialAccount;
import org.openbravo.model.financialmgmt.payment.FIN_Payment;
import org.openbravo.model.financialmgmt.payment.FIN_PaymentDetail;
import org.openbravo.model.financialmgmt.payment.FIN_PaymentProposal;
import org.openbravo.model.financialmgmt.payment.FIN_PaymentPropDetail;
import org.openbravo.model.financialmgmt.payment.FIN_PaymentScheduleDetail;

import com.etendoerp.go.schemaforge.NeoContext;
import com.etendoerp.go.schemaforge.NeoEndpointType;
import com.etendoerp.go.schemaforge.NeoHandler;
import com.etendoerp.go.schemaforge.NeoResponse;
import com.etendoerp.go.schemaforge.PaymentInvoiceApplications;
import com.etendoerp.go.schemaforge.PaymentRegistrationService;
import com.etendoerp.go.schemaforge.PisDeferredPaymentService;
import com.etendoerp.go.schemaforge.PisPaymentService;
import com.etendoerp.go.schemaforge.util.NeoButtonActionHelper;
import com.etendoerp.go.schemaforge.util.NeoDateFormat;
import com.etendoerp.payment.removal.util.PaymentRemovalUtil;
import com.etendoerp.psd2.bank.integration.data.PisPayment;

/**
 * Shared {@code NeoHandler} for the payment Reactivate, Confirm, and Remove actions, used
 * by both the payment-in (cobros) and payment-out (pagos) windows. Any payment header
 * entity that registers {@code JAVA_QUALIFIER = 'payment-reactivate'} on its
 * {@code ETGO_SF_ENTITY} record routes through this handler.
 *
 * <p><b>Reactivate ({@code etprReactivatePayment}):</b> The backing process
 * {@code com.etendoerp.payment.removal.handler.ReactivatePayment} requires a mandatory
 * {@code action} parameter ({@code "RE"}). The NEO UI never sends it, so this handler
 * intercepts the action and injects {@code action = "RE"} before delegating to the
 * standard button-action machinery.
 *
 * <p><b>Confirm ({@code aPRMProcessPayment}):</b> The backing process
 * {@code org.openbravo.advpaymentmngt.process.FIN_PaymentProcess} reads the record ID
 * from the bundle under the key {@code "Fin_Payment_ID"} (mixed-case column name from AD),
 * but {@code NeoButtonActionHelper.addTabParamsCore} populates {@code "FIN_Payment_ID"}
 * (using the DB table name {@code "FIN_Payment"}). The case mismatch causes a null ID and
 * a "id to load is required for loading" error. This handler intercepts the confirm action,
 * injects both {@code Fin_Payment_ID} (correct key) and {@code action = "P"} (confirm
 * action expected by {@code FIN_PaymentProcess}), and delegates to the standard executor.
 *
 * <p><b>Remove ({@code eTPRRemovePayment}):</b> the standard process
 * ({@code com.etendoerp.payment.removal.handler.RemovePayment}, via {@code
 * PaymentRemovalUtil.reactivateAndRemove(payment)}) ends with a plain {@code
 * OBDal.getInstance().remove(payment)}. It never deletes the join rows between the payment and
 * the invoice/order it was applied to ({@code FIN_PaymentScheduleDetail} /
 * {@code FIN_PaymentDetail}); there is no delete-cascade for them either, since they are an
 * internal payment↔schedule join, not an AD parent/child tab. Removing an <em>applied</em>
 * payment therefore fails with a raw FK violation, surfaced to the user as the generic "This
 * record cannot be deleted... Please see Linked Items" error. This handler, before removing
 * the payment, deletes those join rows itself and recalculates the affected invoices —
 * mirroring exactly what {@code PaymentRemovalUtil.remove()} does, just completed correctly.
 * Unapplied payments (no {@code FIN_PaymentDetail} rows) are unaffected — the cleanup is a
 * no-op. For a payment that was already a DRAFT, {@link #releaseInstallmentsToPending} first
 * hands each installment back to Core so the invoice keeps a payable (pending) schedule
 * fragment; without it the invoice became permanently unpayable (ETP-4841).
 *
 * <p>Three rounds of live testing (see the reject-cycle notes on {@link #handleRemove} and
 * {@link #removeApplicationDetails}) shaped the current design: (1) deleted children must also
 * be detached from their parent's in-memory collection, or Hibernate's final end-of-request
 * flush throws {@code EntityNotFoundException: deleted object would be re-saved by cascade};
 * (2) a still-{@code Processed} payment must be reactivated BEFORE its detail rows are
 * touched, or a core AD trigger blocks the delete outright — and a payment tied to a processed
 * Payment Proposal cannot be removed via this action at all; (3) reactivating a reconciled
 * payment clears the ENTIRE Hibernate session as a side effect of {@code
 * PaymentRemovalUtil.reactivate()}'s own internals, which broke a FOURTH design (delegating
 * the final removal step to the standard button-action framework via {@code
 * NeoButtonActionHelper.executeButtonActionCore}) because that framework needs {@code
 * context.getSfEntity()}'s lazy {@code AD_Tab} proxy, loaded before this handler ever ran and
 * before the session got cleared. Rather than defensively initializing that proxy around the
 * one call proven to clear the session, this handler now removes the payment itself by
 * calling {@code PaymentRemovalUtil.remove(payment)} directly — the same utility already used
 * for every other step here — eliminating the button-action delegation (and the {@code
 * context.getSfEntity()} dependency, and the whole class of stale-proxy-after-session-clear
 * risk that comes with it) for this action entirely, instead of working around it.
 *
 * <p><b>GET (single record, post-hook):</b> injects a nullable {@code financialTransactionId}
 * field so the UI can navigate from the payment detail to the reconciled bank transaction
 * (there is no forward FK from {@code FIN_Payment} to {@code FIN_Finacc_Transaction}, only
 * the reverse {@code FIN_Finacc_Transaction.Fin_Payment_ID}). The field is {@code null} when
 * the payment has not been reconciled yet (e.g. status is not {@code RPPC}). It also injects the
 * three multi-currency extras {@code accountCurrency} / {@code conversionRate} /
 * {@code financialTransactionAmount} — see {@link #injectMultiCurrencyExtras}.
 *
 * <p>{@code @Named} only — never a normal CDI scope. {@code lookupHandler()} reads the
 * {@code @Named} annotation off the concrete handler class; a normal-scoped bean would be a
 * Weld client proxy whose subclass does not carry the (non-{@code @Inherited}) {@code @Named},
 * so the handler would be silently skipped. {@code @Named}-only defaults to {@code @Dependent}
 * (no proxy).
 */
@Named("payment-reactivate")
public class ReactivatePaymentHandler implements NeoHandler {

  private static final Logger log = LogManager.getLogger(ReactivatePaymentHandler.class);
  private static final String REACTIVATE_ACTION_FIELD = "etprReactivatePayment";
  private static final String CONFIRM_ACTION_FIELD = "aPRMProcessPayment";
  private static final String REMOVE_ACTION_FIELD = "eTPRRemovePayment";
  private static final String ACTION_PARAM = "action";
  private static final String REACTIVATE_VALUE = "RE";
  private static final String CONFIRM_VALUE = "P";
  /**
   * Action code for {@code PaymentRemovalUtil.reactivate(paymentId, action)} when called
   * directly by {@link #handleRemove}, ahead of the join-row cleanup. Distinct from {@code
   * REACTIVATE_VALUE} ("RE"), which is the separate action code the user-facing Reactivate
   * button sends to {@code FIN_AddPayment.processPayment}. "R" is the exact value
   * {@code PaymentRemovalUtil.reactivateAndRemove()} itself uses internally (its
   * {@code REACTIVATE_AND_REMOVE_LINES} constant) and the same value the base module's own
   * passing test ({@code PaymentRemovalTest.reactivatePayment}) exercises — proven-safe, not
   * a guess.
   */
  private static final String REACTIVATE_BEFORE_REMOVE_VALUE = "R";
  /** Exact AD column name for FIN_Payment PK — differs in case from the DB table name. */
  private static final String FIN_PAYMENT_ID_KEY = "Fin_Payment_ID";
  /** Nullable field injected into the single-record GET response (see class javadoc). */
  private static final String FIELD_FINANCIAL_TRANSACTION_ID = "financialTransactionId";
  /**
   * Read-only multi-currency extras injected into the single-record GET response so the payment
   * detail panel can show the amount in the financial account's currency alongside the payment's
   * own, plus the rate between them. None is reachable through the frontend contract:
   * {@code Finacc_Txn_Convert_Rate} / {@code Finacc_Txn_Amount} are {@code ISINCLUDED = N} on
   * payment-in, and the ACCOUNT's currency ISO is one hop past {@code Fin_Financial_Account_ID} in
   * both windows. Injecting them here keeps this a pure read enrichment — no AD change, hence no
   * {@code push-to-neo} / {@code export.database}. Field names deliberately match what
   * {@code PaymentRegistrationService.paymentListItem} already emits for the invoice payment modal,
   * so both surfaces speak one shape.
   */
  /**
   * Action + field for retrying a bank transfer the bank rejected after committing to it. The
   * payment is flagged {@code ETGOERR} and kept processed, so the retry reuses it rather than
   * registering a second one — see {@code PisDeferredPaymentService}. The id is injected on the
   * single-record GET so the payment window can offer the retry without first having to look up
   * the invoice the payment came from.
   */
  private static final String PIS_RETRY_ACTION_FIELD = "retryPisPayment";
  /**
   * Same poll the invoice's payment modal runs, reachable from the payment record too.
   *
   * <p>A retry started here opens the bank popup and then had nothing watching it: the modal's poll
   * belongs to the modal, the async webhook cannot reach a non-public server, and PSD2's periodic
   * refresh is not scheduled by default — so the new attempt sat at {@code requested} and the
   * payment read as "in progress" long after the bank had executed it. The action itself takes the
   * transfer from the body and ignores the record it is posted to, so routing it here needs no
   * invoice.
   */
  private static final String PIS_STATUS_ACTION_FIELD = "pisPaymentStatus";
  /** Mirrors {@code PisDeferredPaymentService.PAYMENT_STATUS_ERROR}, which is not visible here. */
  private static final String PAYMENT_STATUS_ERROR = "ETGOERR";
  private static final String FIELD_PIS_PAYMENT_ID = "pisPaymentId";
  /**
   * Read-only flag telling the UI that this payment's lifecycle belongs to its bank transfer, so
   * Reactivate and Delete must not be offered. Emitted on the single record AND on every list row,
   * because both surfaces offer those actions and a rule enforced in only one of them is a rule the
   * user can walk around. Always present (never absent) so the UI can tell "this backend does not
   * send it" apart from "this payment is not locked". See
   * {@code PisDeferredPaymentService#isLifecycleLockedByTransfer}.
   */
  private static final String FIELD_PIS_LOCKED = "pisLocked";
  /**
   * The invoice this payment was applied to, or {@code null} when it is not exactly one. Lets the
   * window open the invoice's own payment editor for a draft instead of the yes/no confirm dialog —
   * see {@code PaymentInvoiceApplications#invoiceIdsByPayment}. Emitted alongside
   * {@link #FIELD_PIS_LOCKED} so the grid's kebab can do the same, in the same batch.
   */
  private static final String FIELD_INVOICE_ID = "invoiceId";
  private static final String FIELD_ID = "id";
  private static final String FIELD_STATUS = "status";
  private static final String FIELD_ACCOUNT_CURRENCY = "accountCurrency";
  private static final String FIELD_CONVERSION_RATE = "conversionRate";
  private static final String FIELD_FINANCIAL_TRANSACTION_AMOUNT = "financialTransactionAmount";
  /**
   * The payment's audit {@code updated} timestamp, ISO with a time of day.
   *
   * <p>Injected here rather than registered as a NEO field because the push to
   * {@code ETGO_SF_FIELD} excludes audit columns, so declaring it in {@code decisions.json} — where
   * it already is — never reaches the runtime. The activity panel needs a real time of day: it used
   * to fall back to {@code paymentDate}, a date-only column, and rendered a payment confirmed at
   * 12:10 as "· 00:00" (ETP-4895).
   */
  private static final String FIELD_UPDATED_AT = "updatedAt";
  private static final String HTTP_GET = "GET";
  private static final String KEY_RESPONSE = "response";
  private static final String KEY_DATA = "data";

  /**
   * Pre-hook: intercepts {@code etprReactivatePayment} (injects {@code action = "RE"}),
   * {@code aPRMProcessPayment} (injects {@code Fin_Payment_ID} + {@code action = "P"}), and
   * {@code eTPRRemovePayment} (deletes the payment↔schedule join rows before delegating, see
   * class javadoc). Returns {@code null} for all other requests so default handling proceeds.
   *
   * @param context the current NEO request context
   * @return the process result for the handled actions, otherwise {@code null}
   */
  @Override
  public NeoResponse handle(NeoContext context) {
    if (context.getEndpointType() != NeoEndpointType.ACTION) {
      return null;
    }
    String fieldName = context.getFieldName();
    if (REACTIVATE_ACTION_FIELD.equals(fieldName)) {
      return handleReactivate(context);
    }
    if (CONFIRM_ACTION_FIELD.equals(fieldName)) {
      return handleConfirm(context);
    }
    if (REMOVE_ACTION_FIELD.equals(fieldName)) {
      return handleRemove(context);
    }
    if (PIS_RETRY_ACTION_FIELD.equals(fieldName)) {
      // Same action the invoice's payment modal posts; routed here too so it is reachable straight
      // from the payment record, which is where a rejection observed after the fact shows up.
      return PisDeferredPaymentService.handleRetryPisPayment(context);
    }
    if (PIS_STATUS_ACTION_FIELD.equals(fieldName)) {
      return PisPaymentService.handlePisPaymentStatus(context);
    }
    return null;
  }

  private NeoResponse handleReactivate(NeoContext context) {
    try {
      clearTransferErrorFlag(context.getRecordId());
      JSONObject params = new JSONObject();
      params.put(ACTION_PARAM, REACTIVATE_VALUE);
      return NeoButtonActionHelper.executeButtonActionCore(
          context.getSfEntity(), context.getRecordId(), context.getFieldName(), params);
    } catch (Exception e) {
      log.error("Error reactivating payment for record {}", context.getRecordId(), e);
      return NeoResponse.error(500, "Payment reactivation failed: " + e.getMessage());
    }
  }

  /**
   * Puts Core's own status back on a payment flagged {@code ETGOERR} before Core reactivates it.
   *
   * <p>{@code ETGOERR} is Etendo Go's overlay on a payment Core knows as processed. Core's
   * reactivation decides whether to give the invoice its outstanding back by comparing the
   * payment's status against the one its payment method implies:
   *
   * <pre>
   * restorePaidAmounts = seqnumberpaymentstatus(payment.getStatus())
   *                   == seqnumberpaymentstatus(invoicePaymentStatus(payment))
   * </pre>
   *
   * <p>Our status is not in that sequence — {@code aprm_seqnumberpaymentstatus} answers 70 for
   * anything it does not know, against 40 for {@code PPM} — so the comparison never held and the
   * payment came back to draft while its invoice still read as fully paid (ETP-4895).
   *
   * <p>Restoring {@code invoicePaymentStatus} rather than a literal is what makes this correct for
   * an account with automatic withdrawal on, where the flagged payment had been {@code PWNC} and
   * not {@code PPM}: it is by definition the value Core is about to compare against.
   *
   * <p>Nothing is lost by clearing the flag here — the user is explicitly abandoning this payment's
   * transfer, and the rejected {@code PSD2_PIS_PAYMENT} row remains as the audit trail.
   */
  private void clearTransferErrorFlag(String paymentId) {
    if (StringUtils.isBlank(paymentId)) {
      return;
    }
    OBContext.setAdminMode(true);
    try {
      FIN_Payment payment = OBDal.getInstance().get(FIN_Payment.class, paymentId);
      if (payment == null || !StringUtils.equals(PAYMENT_STATUS_ERROR, payment.getStatus())) {
        return;
      }
      payment.setStatus(FIN_Utility.invoicePaymentStatus(payment));
      OBDal.getInstance().save(payment);
      OBDal.getInstance().flush();
    } catch (Exception e) {
      // Never block the reactivation: at worst Core skips restoring the amounts, which is the
      // behaviour we had before this ran at all.
      log.warn("Could not clear the transfer error flag on payment {}: {}", paymentId,
          e.getMessage());
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  private NeoResponse handleConfirm(NeoContext context) {
    try {
      JSONObject params = new JSONObject();
      params.put(FIN_PAYMENT_ID_KEY, context.getRecordId());
      params.put(ACTION_PARAM, CONFIRM_VALUE);
      return NeoButtonActionHelper.executeButtonActionCore(
          context.getSfEntity(), context.getRecordId(), context.getFieldName(), params);
    } catch (Exception e) {
      log.error("Error confirming payment for record {}", context.getRecordId(), e);
      return NeoResponse.error(500, "Payment confirmation failed: " + e.getMessage());
    }
  }

  /**
   * Deletes the payment↔schedule join rows ({@code FIN_PaymentScheduleDetail} /
   * {@code FIN_PaymentDetail}) that block {@code OBDal.remove(FIN_Payment)} with a raw FK
   * violation (see class javadoc), recalculates the invoices they were applied to, then
   * removes the payment itself via {@code PaymentRemovalUtil.remove(payment)}.
   *
   * <p>Order matters: {@code collectAffectedInvoiceIds} is called <em>before</em> deleting
   * anything — it walks the same detail list we are about to remove.
   *
   * <p><b>Reject-cycle 1 fix:</b> an earlier version of this method called
   * {@code OBDal.remove()} on each child without detaching it from its parent's in-memory
   * Hibernate collection first. That collection object stays loaded and unchanged in the
   * session even after the child row is deleted, so at the <em>final</em> flush of the request
   * (Hibernate's own end-of-thread cleanup, {@code DalThreadCleaner}) the session's
   * dirty-checking pass finds a still-managed collection that "contains" an entity it also
   * knows is deleted, and throws {@code EntityNotFoundException: deleted object would be
   * re-saved by cascade}. {@link #removeApplicationDetails} now removes each child from its
   * owning collection in the same step as the {@code OBDal.remove()} call, so no stale
   * membership survives for Hibernate to trip over later.
   *
   * <p>Everything here — cleanup and the final removal — stays inside the single request
   * transaction, so a downstream failure rolls back atomically instead of leaving a
   * partially-cleaned payment.
   *
   * <p><b>Reject-cycle 2 fix — reactivate before touching detail rows:</b> a core Etendo
   * trigger ({@code aprm_fin_pmt_detail_check_trg} on {@code FIN_Payment_Detail}) hard-blocks
   * inserting or deleting a detail row while its parent {@code FIN_Payment.Processed = 'Y'}
   * (AD_Message 20501, "Document posted/processed"). The standard {@code RemovePayment.action()}
   * DOES reactivate the payment before removing it — but only inside {@code
   * PaymentRemovalUtil.reactivateAndRemove()}, i.e. only if that whole helper is used, and only
   * AFTER whatever cleanup runs ahead of it. So for any payment that is still {@code
   * Processed = 'Y'} when this handler runs, {@link #removeApplicationDetails} would try
   * deleting detail rows while still processed, and the trigger would reject it. This method
   * calls {@code PaymentRemovalUtil.reactivate(recordId, "R")} itself first whenever {@code
   * payment.isProcessed()}, then re-fetches the payment (reactivation reassigns/reloads state
   * — e.g. reversing a linked {@code FIN_Finacc_Transaction} and clearing the session — through
   * its own internal calls, so the local reference must not be reused), before any cleanup
   * runs.
   *
   * <p><b>Reject-cycle 2 fix — Payment Proposal guard:</b> a second, independent trigger
   * ({@code aprm_fin_prop_detail_check_trg} on {@code FIN_Payment_Prop_Detail}) hard-blocks
   * mutating a proportional-detail row while its <em>owning {@code FIN_Payment_Proposal}</em>
   * is processed — a completely different "processed" flag than the payment's own, on a
   * completely different entity, so reactivating the payment does not clear it. A payment
   * generated from a finalized mass-payment proposal cannot be removed through this action at
   * all (reversing it is a separate, proposal-level business flow); {@link
   * #findProcessedProposalPropDetail} detects this upfront and this method returns a clear
   * 400 instead of attempting (and failing) any mutation.
   *
   * <p><b>Reject-cycle 3 fix — remove the button-action delegation entirely:</b> a fourth
   * design, delegating the final removal to the standard button-action framework ({@code
   * NeoButtonActionHelper.executeButtonActionCore(context.getSfEntity(), ...)}), broke because
   * reactivating a reconciled payment clears the ENTIRE Hibernate session as a side effect of
   * {@code PaymentRemovalUtil.reactivate()}'s own internals ({@code
   * TransactionRemovalUtil.reactivateAndRemove(...)} followed by {@code
   * OBDal.getInstance().getSession().clear()}), silently detaching {@code
   * context.getSfEntity()}'s lazy {@code AD_Tab} proxy — loaded by the NEO dispatch layer
   * before this handler ever ran — which the delegated action's {@code addTabParamsCore} then
   * failed to lazily resolve ({@code could not initialize proxy [ADTab#...] - no Session}).
   * Rather than defensively initializing that proxy around the one call proven to clear the
   * session, this method instead calls {@code PaymentRemovalUtil.remove(payment)} directly —
   * the same utility already used for every other step here — which eliminates {@code
   * context.getSfEntity()} and the button-action framework from this action's path entirely.
   * {@code remove()} is confirmed, via {@code javap -c} on the exact dependency jar
   * ({@code payment.removal-3.1.0.jar}), to have exactly one return path (always {@code true}
   * on success) and to always throw on failure (an {@code OBException}, re-wrapped) — never a
   * silent {@code false} — so the existing {@code catch (Exception e)} below already handles
   * every failure mode; no boolean-return branching is needed. {@code remove()} also clears the
   * session itself, but nothing after this method touches any entity loaded before it, so that
   * is harmless now.
   *
   * @param context the current NEO request context
   * @return a 200 on success, a 404 if the payment does not exist, a 400 if the payment is
   *     tied to a processed Payment Proposal, or a 500 error on unexpected failure
   */
  private NeoResponse handleRemove(NeoContext context) {
    try {
      FIN_Payment payment = OBDal.getInstance().get(FIN_Payment.class, context.getRecordId());
      if (payment == null) {
        return NeoResponse.error(404, "Payment not found: " + context.getRecordId());
      }

      FIN_PaymentPropDetail blocking = findProcessedProposalPropDetail(payment);
      if (blocking != null) {
        return NeoResponse.error(400, "This payment was generated from a processed Payment "
            + "Proposal (" + blocking.getFinPaymentProposal().getIdentifier() + ") and cannot "
            + "be removed from here; reverse the Payment Proposal instead.");
      }

      boolean wasProcessed = Boolean.TRUE.equals(payment.isProcessed());
      if (wasProcessed) {
        PaymentRemovalUtil.reactivate(payment.getId(), REACTIVATE_BEFORE_REMOVE_VALUE);
        payment = OBDal.getInstance().get(FIN_Payment.class, context.getRecordId());
      }

      Set<String> affectedInvoiceIds = PaymentRemovalUtil.collectAffectedInvoiceIds(payment);
      if (!wasProcessed) {
        releaseInstallmentsToPending(payment);
      }
      removeApplicationDetails(payment);
      OBDal.getInstance().flush();
      // Only a payment that WAS processed moved the invoice's paid/outstanding amounts, so only
      // that case needs them recomputed. Running the recompute for a draft is not merely
      // redundant, it corrupts the invoice: PaymentRemovalUtil.sumDetails() sums EVERY schedule
      // detail of the installment without checking whether it is linked to a payment detail, so
      // the pending fragment that releaseInstallmentsToPending() just restored gets counted as
      // "paid" — leaving paidAmount = full, outstandingAmount = 0 and the invoice flagged
      // paymentComplete, i.e. unpayable and wrongly shown as paid in both Etendo Go and Classic
      // (ETP-4841, observed live on a 39.93 invoice). Verified against real data: an installment
      // whose only linked detail belongs to an unprocessed payment reports paidAmount 0, which is
      // why the draft path has nothing to recompute in the first place.
      if (wasProcessed) {
        PaymentRemovalUtil.updateInvoicesAfterPaymentRemoval(affectedInvoiceIds);
        OBDal.getInstance().flush();
      }

      PaymentRemovalUtil.remove(payment);

      return NeoResponse.ok(new JSONObject().put("success", true));
    } catch (Exception e) {
      log.error("Error removing payment for record {}", context.getRecordId(), e);
      return NeoResponse.error(500, "Payment removal failed: " + e.getMessage());
    }
  }

  /**
   * Finds a {@code FIN_PaymentPropDetail} belonging to {@code payment} whose owning
   * {@code FIN_Payment_Proposal} is processed, if any. Such rows cannot be touched (insert,
   * update, or delete) by {@code aprm_fin_prop_detail_check_trg} while the proposal is
   * processed — see the reject-cycle 2 note on {@link #handleRemove}.
   *
   * @param payment the payment to inspect
   * @return a blocking {@code FIN_PaymentPropDetail}, or {@code null} if none exists
   */
  private static FIN_PaymentPropDetail findProcessedProposalPropDetail(FIN_Payment payment) {
    for (FIN_PaymentDetail detail : payment.getFINPaymentDetailList()) {
      for (FIN_PaymentScheduleDetail scheduleDetail : detail.getFINPaymentScheduleDetailList()) {
        for (FIN_PaymentPropDetail propDetail : scheduleDetail.getFINPaymentPropDetailList()) {
          FIN_PaymentProposal proposal = propDetail.getFinPaymentProposal();
          if (proposal != null && Boolean.TRUE.equals(proposal.isProcessed())) {
            return propDetail;
          }
        }
      }
    }
    return null;
  }

  /**
   * Removes every {@code FIN_PaymentPropDetail}, {@code FIN_PaymentScheduleDetail}, and
   * {@code FIN_PaymentDetail} row belonging to {@code payment}. A no-op when the payment has
   * no applied details (e.g. a draft/unapplied payment) — the regression path is unaffected.
   *
   * <p>Each child is removed from its <em>owning collection</em> in the same step as the
   * {@code OBDal.remove()} call. Deleting a child via {@code OBDal.remove()} without also
   * detaching it from its parent's already-loaded in-memory collection leaves that collection
   * stale — Hibernate still considers the parent "associated" with an entity it also knows is
   * deleted, and throws {@code EntityNotFoundException: deleted object would be re-saved by
   * cascade} the next time that collection is dirty-checked (see the reject-cycle 1 note on
   * {@link #handleRemove}). Iterating over defensive {@code ArrayList} copies (not the live
   * collections) avoids a {@code ConcurrentModificationException} from removing while
   * iterating.
   *
   * <p><b>Reject-cycle 2 addition — {@code FIN_PaymentPropDetail}:</b> the real-Hibernate-session
   * integration test ({@code ReactivatePaymentHandlerRemoveIntegrationTest}) caught a THIRD
   * child table that neither the original manual SQL diagnosis nor reject-cycle 1 accounted
   * for: {@code FIN_Payment_Prop_Detail} (reached via
   * {@code scheduleDetail.getFINPaymentPropDetailList()}) has its own FK to
   * {@code FIN_Payment_ScheduleDetail}. Deleting it is only reachable here when its owning
   * {@code FIN_Payment_Proposal} is NOT processed — {@link #handleRemove} calls {@link
   * #findProcessedProposalPropDetail} first and refuses with a 400 whenever it is, since
   * {@code aprm_fin_prop_detail_check_trg} hard-blocks mutating a processed proposal's
   * prop-detail rows regardless of what we do to the payment itself. This loop still deletes
   * (and detaches) any prop-detail rows tied to an unprocessed proposal — a rarer case, but one
   * the trigger permits.
   *
   * <p>Package-private (not {@code private}) so the {@code OBBaseTest}-based integration
   * regression test can invoke it directly against a real Hibernate session, the same way
   * {@link #resolveFinancialTransactionId} is package-private for its own unit tests.
   *
   * @param payment the payment whose application join rows are being removed
   */
  /**
   * Releases each of {@code payment}'s document-linked {@link FIN_PaymentScheduleDetail} rows back
   * to PENDING before {@link #removeApplicationDetails} deletes them, by handing each one to Core's
   * own reconciliation with a zero amount. Core's "editing an existing link" branch then leaves an
   * unlinked ({@code FIN_Payment_Detail_ID IS NULL}) fragment carrying the released amount — which
   * is exactly what {@code PaymentRegistrationService.findPendingPSDs} requires in order to register
   * any later payment against the same installment.
   *
   * <p><b>Only when the payment was ALREADY a draft (ETP-4841).</b> {@link
   * #removeApplicationDetails} deletes every schedule detail outright and never restores a pending
   * fragment. For a PROCESSED payment that is harmless, because {@code
   * PaymentRemovalUtil.reactivate()} has by then already reversed the invoice's payment plan through
   * Core. A DRAFT skips reactivation entirely (there is nothing to reactivate), so nothing restored
   * the fragment and the installment was left with NO schedule-detail rows at all — every later
   * attempt to pay that invoice then failed with "No pending payment schedule details found for this
   * installment" (HTTP 400).
   *
   * <p>Diagnosed with a controlled experiment rather than by reading alone (an earlier fix premised
   * on Core's internal branching was wrong and got reverted): the same draft-then-delete cycle was
   * run twice on two equivalent invoices, once per delete route, and the DB inspected directly.
   * Deleting through this handler left 0 schedule-detail rows; deleting through the invoice modal
   * ({@code PaymentDraftEditService.deleteDraftPayment}, which already performs this release) left
   * exactly 1 correct pending row. This method makes both routes converge on that verified end
   * state. Note it deliberately does NOT depend on which internal branch Core takes — whether it
   * unlinks the existing row in place or copies it — only on the end state the experiment proved.
   *
   * <p>Package-visible for the same reason {@link #removeApplicationDetails} is: {@code
   * ReactivatePaymentHandlerDraftRemoveIntegrationTest} drives it directly against a real Hibernate
   * session, and a compile-time call keeps a future rename from silently turning that test into a
   * no-op the way a reflective lookup would.
   */
  static void releaseInstallmentsToPending(FIN_Payment payment) {
    for (FIN_PaymentDetail detail : payment.getFINPaymentDetailList()) {
      for (FIN_PaymentScheduleDetail psd : detail.getFINPaymentScheduleDetailList()) {
        if (psd.getInvoicePaymentSchedule() != null || psd.getOrderPaymentSchedule() != null) {
          FIN_AddPayment.updatePaymentDetail(psd, payment, BigDecimal.ZERO, false);
        }
      }
    }
    OBDal.getInstance().flush();
  }

  static void removeApplicationDetails(FIN_Payment payment) {
    List<FIN_PaymentDetail> details = new ArrayList<>(payment.getFINPaymentDetailList());
    for (FIN_PaymentDetail detail : details) {
      List<FIN_PaymentScheduleDetail> scheduleDetails =
          new ArrayList<>(detail.getFINPaymentScheduleDetailList());
      for (FIN_PaymentScheduleDetail scheduleDetail : scheduleDetails) {
        List<FIN_PaymentPropDetail> propDetails =
            new ArrayList<>(scheduleDetail.getFINPaymentPropDetailList());
        for (FIN_PaymentPropDetail propDetail : propDetails) {
          scheduleDetail.getFINPaymentPropDetailList().remove(propDetail);
          OBDal.getInstance().remove(propDetail);
        }
        detail.getFINPaymentScheduleDetailList().remove(scheduleDetail);
        OBDal.getInstance().remove(scheduleDetail);
      }
      payment.getFINPaymentDetailList().remove(detail);
      OBDal.getInstance().remove(detail);
    }
  }

  /**
   * Post-hook: on a single-record GET, injects {@code financialTransactionId} into the
   * response so the UI can offer a "go to transaction" link once the payment is
   * reconciled. Returns {@code null} (no change) for list responses, other HTTP
   * methods, or when the previous result has no body.
   *
   * @param context the current NEO request context
   * @return the updated response, or {@code null} to leave the previous result untouched
   */
  @Override
  public NeoResponse afterHandle(NeoContext context) {
    if (isListGet(context)) {
      return injectLockFlagOnList(context);
    }
    if (!isSingleRecordGet(context)) {
      return null;
    }
    try {
      JSONObject body = context.getPreviousResult().getBody();
      JSONArray dataArr = extractDataArray(body);
      if (dataArr == null || dataArr.length() != 1) {
        return null;
      }
      JSONObject paymentRecord = dataArr.getJSONObject(0);
      String transactionId = resolveFinancialTransactionId(context.getRecordId());
      paymentRecord.put(FIELD_FINANCIAL_TRANSACTION_ID,
          transactionId != null ? transactionId : JSONObject.NULL);
      injectMultiCurrencyExtrasQuietly(paymentRecord, context.getRecordId());
      injectRetryablePisAttemptQuietly(paymentRecord, context.getRecordId());
      injectUpdatedAtQuietly(paymentRecord, context.getRecordId());
      injectLockFlags(new JSONArray().put(paymentRecord));
      return NeoResponse.ok(body);
    } catch (Exception e) {
      log.error("Error resolving financial transaction for payment {}", context.getRecordId(), e);
      return null;
    }
  }

  /**
   * List counterpart of {@link #afterHandle}: adds {@link #FIELD_PIS_LOCKED} and
   * {@link #FIELD_INVOICE_ID} to every row.
   *
   * <p>The other enrichments stay single-record — they each cost a query and the grid does not show
   * them. This one is worth it because the grid's kebab offers Reactivate and its row actions offer
   * Delete, and it is answered for the whole page in one query.
   *
   * @return the enriched response, or {@code null} to leave the previous result untouched
   */
  private NeoResponse injectLockFlagOnList(NeoContext context) {
    try {
      JSONObject body = context.getPreviousResult().getBody();
      JSONArray dataArr = extractDataArray(body);
      if (dataArr == null || dataArr.length() == 0) {
        return null;
      }
      injectLockFlags(dataArr);
      return NeoResponse.ok(body);
    } catch (Exception e) {
      log.error("Could not flag bank-transfer-locked payments on the list response", e);
      return null;
    }
  }

  /**
   * Sets {@link #FIELD_PIS_LOCKED} and {@link #FIELD_INVOICE_ID} on every row of {@code records},
   * resolving each with a single query for the whole batch. Swallows failures: losing the flag
   * hides two buttons that were there before, which is far better than losing the response.
   */
  private void injectLockFlags(JSONArray records) {
    try {
      Set<String> ids = new HashSet<>();
      for (int i = 0; i < records.length(); i++) {
        String id = records.getJSONObject(i).optString(FIELD_ID, null);
        if (StringUtils.isNotBlank(id)) {
          ids.add(id);
        }
      }
      Set<String> withTransfer = PisDeferredPaymentService.paymentsWithBankTransfer(ids);
      Map<String, String> invoiceIds = PaymentInvoiceApplications.invoiceIdsByPayment(ids);
      for (int i = 0; i < records.length(); i++) {
        JSONObject row = records.getJSONObject(i);
        String id = row.optString(FIELD_ID, null);
        row.put(FIELD_PIS_LOCKED, PisDeferredPaymentService.isLifecycleLockedByTransfer(
            row.optString(FIELD_STATUS, null), withTransfer.contains(id)));
        String invoiceId = invoiceIds.get(id);
        row.put(FIELD_INVOICE_ID, invoiceId != null ? invoiceId : JSONObject.NULL);
      }
    } catch (Exception e) {
      log.warn("Could not flag bank-transfer-locked payments: {}", e.getMessage());
    }
  }

  private static boolean isListGet(NeoContext context) {
    return context != null
        && HTTP_GET.equals(context.getHttpMethod())
        && context.getRecordId() == null
        && context.getPreviousResult() != null
        && context.getPreviousResult().getBody() != null;
  }

  /**
   * Adds {@link #FIELD_PIS_PAYMENT_ID}: the rejected bank transfer this payment can be retried
   * from, or {@code null} when there is none. Always present, so the UI can tell "this backend
   * does not send it" apart from "this payment has nothing to retry".
   *
   * <p>Swallows failures for the same reason as the multi-currency extras: a retry affordance is
   * not worth discarding the whole enriched response over.
   */
  private void injectRetryablePisAttemptQuietly(JSONObject paymentRecord, String paymentId) {
    try {
      // The other moment a resolution that arrived after the payment modal closed can be noticed.
      PisDeferredPaymentService.reconcileAttemptsFor(
          OBDal.getInstance().get(FIN_Payment.class, paymentId));
      PisPayment rejected = PisDeferredPaymentService.findRetryableAttempt(paymentId);
      paymentRecord.put(FIELD_PIS_PAYMENT_ID, rejected != null ? rejected.getId() : JSONObject.NULL);
    } catch (Exception e) {
      log.warn("Could not resolve a retryable PIS attempt for payment {}", paymentId, e);
    }
  }

  /**
   * Adds {@link #FIELD_UPDATED_AT}: when this payment last changed, with its time of day.
   *
   * <p>This is the only timestamp the client can get that carries an hour. {@code paymentDate} is a
   * date-only AD column, and the audit columns are absent from the spec (see
   * {@link #FIELD_UPDATED_AT}), so without this the activity panel has nothing but midnight to
   * show. Always present — {@code null} rather than missing — so the UI can tell "this backend does
   * not send it" apart from "this payment has no timestamp".
   *
   * <p>Swallows failures for the same reason as its siblings: a timestamp is a display nicety and
   * is not worth discarding the whole enriched response over.
   */
  private void injectUpdatedAtQuietly(JSONObject paymentRecord, String paymentId) {
    try {
      FIN_Payment payment = OBDal.getInstance().get(FIN_Payment.class, paymentId);
      Date updated = payment != null ? payment.getUpdated() : null;
      paymentRecord.put(FIELD_UPDATED_AT, updated != null
          ? new SimpleDateFormat(NeoDateFormat.ISO_DATETIME).format(updated)
          : JSONObject.NULL);
    } catch (Exception e) {
      log.warn("Could not resolve the updated timestamp for payment {}", paymentId, e);
    }
  }

  /**
   * Calls {@link #injectMultiCurrencyExtras} and swallows any failure.
   *
   * <p>Isolated on purpose: those three fields are a display nicety, so a failure resolving them
   * must never discard the whole enriched response. {@link #afterHandle}'s catch returns
   * {@code null} (i.e. "leave the previous result untouched"), which would also drop the
   * {@code financialTransactionId} injection and silently break the "go to transaction" link.
   *
   * @param paymentRecord the payment JSON object to enrich, mutated in place
   * @param paymentId the {@code FIN_Payment} id being returned
   */
  private void injectMultiCurrencyExtrasQuietly(JSONObject paymentRecord, String paymentId) {
    try {
      injectMultiCurrencyExtras(paymentRecord, paymentId);
    } catch (Exception e) {
      log.warn("Could not resolve multi-currency fields for payment {}", paymentId, e);
    }
  }

  /**
   * Adds {@link #FIELD_ACCOUNT_CURRENCY}, {@link #FIELD_CONVERSION_RATE} and
   * {@link #FIELD_FINANCIAL_TRANSACTION_AMOUNT} to a single-record GET payload.
   *
   * <p>Every key is always present (explicitly {@code JSONObject.NULL} when unavailable) so the UI
   * can tell "this backend does not send it" apart from "this payment has no value", rather than
   * inferring intent from a missing key. The rate is emitted VERBATIM as stored — the payment's own
   * booked rate, in its stored payment-currency → account-currency direction — which for a payment
   * plays the same role the invoice's own document rate plays in the preview panel: the record's own
   * rate wins over any system spot rate. Keeping it unconverted is what makes the detail panel show
   * back exactly the rate the user typed in the Cobros/Pagos modal (ETP-4841).
   *
   * <p>Package-private so unit tests can drive it without the full {@code afterHandle} flow.
   *
   * @param paymentRecord the payment JSON object to enrich, mutated in place
   * @param paymentId the {@code FIN_Payment} id being returned
   * @throws JSONException if the payload rejects a put
   */
  void injectMultiCurrencyExtras(JSONObject paymentRecord, String paymentId) throws JSONException {
    FIN_Payment payment = OBDal.getInstance().get(FIN_Payment.class, paymentId);
    FIN_FinancialAccount account = payment != null ? payment.getAccount() : null;
    Currency accountCurrency = account != null ? account.getCurrency() : null;
    paymentRecord.put(FIELD_ACCOUNT_CURRENCY,
        accountCurrency != null && accountCurrency.getISOCode() != null
            ? accountCurrency.getISOCode() : JSONObject.NULL);
    paymentRecord.put(FIELD_CONVERSION_RATE, nullSafe(
        payment != null ? payment.getFinancialTransactionConvertRate() : null));
    paymentRecord.put(FIELD_FINANCIAL_TRANSACTION_AMOUNT, nullSafe(
        payment != null ? payment.getFinancialTransactionAmount() : null));
  }

  /** {@code JSONObject.NULL} for a missing amount, so the key is emitted rather than dropped. */
  private static Object nullSafe(BigDecimal value) {
    return value != null ? value : JSONObject.NULL;
  }

  private static boolean isSingleRecordGet(NeoContext context) {
    return context != null
        && HTTP_GET.equals(context.getHttpMethod())
        && context.getRecordId() != null
        && context.getPreviousResult() != null
        && context.getPreviousResult().getBody() != null;
  }

  private static JSONArray extractDataArray(JSONObject body) {
    JSONObject responseWrapper = body.optJSONObject(KEY_RESPONSE);
    return responseWrapper != null ? responseWrapper.optJSONArray(KEY_DATA) : null;
  }

  /**
   * Looks up the {@link FIN_FinaccTransaction} reconciled against this payment. There is no
   * forward FK on {@code FIN_Payment}, only the reverse {@code Fin_Payment_ID} column on the
   * transaction, so this queries by that association. Package-private so unit tests can stub
   * the OBDal criteria without exercising the full afterHandle flow.
   *
   * @param paymentId the {@code FIN_Payment} id
   * @return the linked transaction id, or {@code null} when the payment is not reconciled yet
   */
  String resolveFinancialTransactionId(String paymentId) {
    FIN_Payment payment = OBDal.getInstance().get(FIN_Payment.class, paymentId);
    if (payment == null) {
      return null;
    }
    OBCriteria<FIN_FinaccTransaction> crit = OBDal.getInstance()
        .createCriteria(FIN_FinaccTransaction.class);
    crit.add(Restrictions.eq(FIN_FinaccTransaction.PROPERTY_FINPAYMENT, payment));
    crit.setMaxResults(1);
    List<FIN_FinaccTransaction> results = crit.list();
    return results.isEmpty() ? null : results.get(0).getId();
  }
}
