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
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import javax.inject.Named;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.criterion.Restrictions;
import org.openbravo.advpaymentmngt.process.FIN_AddPayment;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.financialmgmt.payment.FIN_FinaccTransaction;
import org.openbravo.model.financialmgmt.payment.FIN_Payment;
import org.openbravo.model.financialmgmt.payment.FIN_PaymentDetail;
import org.openbravo.model.financialmgmt.payment.FIN_PaymentProposal;
import org.openbravo.model.financialmgmt.payment.FIN_PaymentPropDetail;
import org.openbravo.model.financialmgmt.payment.FIN_PaymentScheduleDetail;

import com.etendoerp.go.schemaforge.NeoContext;
import com.etendoerp.go.schemaforge.NeoEndpointType;
import com.etendoerp.go.schemaforge.NeoHandler;
import com.etendoerp.go.schemaforge.NeoResponse;
import com.etendoerp.go.schemaforge.util.NeoButtonActionHelper;
import com.etendoerp.payment.removal.util.PaymentRemovalUtil;

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
 * the payment has not been reconciled yet (e.g. status is not {@code RPPC}).
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
    return null;
  }

  private NeoResponse handleReactivate(NeoContext context) {
    try {
      JSONObject params = new JSONObject();
      params.put(ACTION_PARAM, REACTIVATE_VALUE);
      return NeoButtonActionHelper.executeButtonActionCore(
          context.getSfEntity(), context.getRecordId(), context.getFieldName(), params);
    } catch (Exception e) {
      log.error("Error reactivating payment for record {}", context.getRecordId(), e);
      return NeoResponse.error(500, "Payment reactivation failed: " + e.getMessage());
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
      return NeoResponse.ok(body);
    } catch (Exception e) {
      log.error("Error resolving financial transaction for payment {}", context.getRecordId(), e);
      return null;
    }
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
