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

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import javax.inject.Named;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.criterion.Restrictions;
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
 * <p><b>Remove ({@code eTPRRemovePayment}):</b> the backing process
 * ({@code com.etendoerp.payment.removal.handler.RemovePayment}, invoked via
 * {@code PaymentRemovalUtil.reactivateAndRemove(payment)}) ends with a plain
 * {@code OBDal.getInstance().remove(payment)}. It never deletes the join rows between the
 * payment and the invoice/order it was applied to ({@code FIN_PaymentScheduleDetail} /
 * {@code FIN_PaymentDetail}); there is no delete-cascade for them either, since they are
 * an internal payment↔schedule join, not an AD parent/child tab. Removing an <em>applied</em>
 * payment (one with an active application) therefore fails with a raw FK violation,
 * surfaced to the user as the generic "This record cannot be deleted... Please see Linked
 * Items" error. This handler intercepts the action and, before delegating, deletes those
 * join rows itself and recalculates the affected invoices — mirroring exactly what
 * {@code PaymentRemovalUtil.remove()} does, just completed correctly. Unapplied payments
 * (no {@code FIN_PaymentDetail} rows) are unaffected — the cleanup is a no-op and the
 * standard action proceeds as before. Two further live-testing rounds (see the reject-cycle
 * notes on {@link #handleRemove} and {@link #removeApplicationDetails}) found: (1) deleted
 * children must also be detached from their parent's in-memory collection, or Hibernate's
 * final end-of-request flush throws; (2) a still-{@code Processed} payment must be reactivated
 * BEFORE its detail rows are touched, or a core AD trigger blocks the delete outright — and a
 * payment tied to a processed Payment Proposal cannot be removed via this action at all.
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
   * delegates to the standard {@code eTPRRemovePayment} button action so the payment header
   * itself gets removed exactly as before.
   *
   * <p>Order matters: {@code collectAffectedInvoiceIds} is called <em>before</em> deleting
   * anything — it walks the same detail list we are about to remove.
   *
   * <p><b>Reject-cycle 1 fix:</b> an earlier version of this method called
   * {@code OBDal.remove()} on each child without detaching it from its parent's in-memory
   * Hibernate collection first. That collection object stays loaded and unchanged in the
   * session even after the child row is deleted, so at the <em>final</em> flush of the request
   * (Hibernate's own end-of-thread cleanup, {@code DalThreadCleaner}, which runs after this
   * method — and even after the whole button action — has already returned) the session's
   * dirty-checking pass finds a still-managed collection that "contains" an entity it also
   * knows is deleted, and throws {@code EntityNotFoundException: deleted object would be
   * re-saved by cascade}. Because that flush happens outside this method's call stack, no
   * try/catch here can intercept it — it surfaced as an unhandled 500 to the client instead of
   * the NeoResponse.error(...) this method returns for in-method failures. {@link
   * #removeApplicationDetails} now removes each child from its owning collection in the same
   * step as the {@code OBDal.remove()} call, so no stale membership survives past this method
   * for Hibernate to trip over later.
   *
   * <p>The payment is additionally evicted from the Hibernate session before delegating. With
   * the collections now correctly mutated, eviction is no longer load-bearing for correctness
   * — the in-memory {@code payment} object is already consistent with the DB, so even the
   * cached instance would dirty-check cleanly. It is kept as defense in depth for whatever the
   * delegated button action's own re-fetch does (it may run through a different framework path
   * than a plain {@code OBDal.get}), since removing it could not be re-verified against a live
   * server in this pass and the risk of introducing a second reject cycle outweighs deleting a
   * redundant-but-harmless line. Not the whole session — {@code context.getSfEntity()} and its
   * lazily-loaded {@code AD_Tab} association, read later by
   * {@code NeoButtonActionHelper.addTabParamsCore}, must stay attached.
   *
   * <p>Everything — our cleanup and the delegated removal — stays inside the single request
   * transaction, so a downstream failure rolls back atomically instead of leaving a
   * partially-cleaned payment.
   *
   * <p><b>Reject-cycle 2 fix — reactivate before touching detail rows:</b> a core Etendo
   * trigger ({@code aprm_fin_pmt_detail_check_trg} on {@code FIN_Payment_Detail}) hard-blocks
   * inserting or deleting a detail row while its parent {@code FIN_Payment.Processed = 'Y'}
   * (AD_Message 20501, "Document posted/processed"). {@code RemovePayment.action()} (the
   * standard delegated action) DOES reactivate the payment before removing it — but only
   * inside {@code PaymentRemovalUtil.reactivateAndRemove()}, i.e. AFTER our pre-hook cleanup
   * has already run. So for any payment that is still {@code Processed = 'Y'} when this
   * handler runs (the normal case — the one already-reactivated payment tested in the first
   * live check happened to slip past this because it had been partially reactivated by an
   * earlier failed attempt), {@link #removeApplicationDetails} used to try deleting detail
   * rows while still processed, and the trigger rejected it. This method now calls {@code
   * PaymentRemovalUtil.reactivate(recordId, "R")} itself first whenever {@code
   * payment.isProcessed()}, then re-fetches the payment (reactivation reassigns/reloads
   * state — e.g. reversing a linked {@code FIN_Finacc_Transaction} and clearing the session —
   * through its own internal calls, so the local reference must not be reused), before any
   * cleanup runs.
   *
   * <p>This does <em>not</em> risk a double-reactivation: {@code reactivateAndRemove()}'s own
   * logic is {@code if (payment.isProcessed()) reactivate(...)} — since we already flipped
   * {@code Processed} to {@code 'N'} ourselves, the delegated action's fresh reload of the
   * payment sees {@code isProcessed() == false} and skips calling {@code reactivate()} a
   * second time, going straight to {@code remove()}. This is read directly from
   * {@code PaymentRemovalUtil}'s source, not assumed.
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
   * @param context the current NEO request context
   * @return the process result from the delegated button action, a 400 if the payment is tied
   *     to a processed Payment Proposal, or a 500 error on unexpected failure
   */
  private NeoResponse handleRemove(NeoContext context) {
    try {
      FIN_Payment payment = OBDal.getInstance().get(FIN_Payment.class, context.getRecordId());
      if (payment != null) {
        FIN_PaymentPropDetail blocking = findProcessedProposalPropDetail(payment);
        if (blocking != null) {
          return NeoResponse.error(400, "This payment was generated from a processed Payment "
              + "Proposal (" + blocking.getFinPaymentProposal().getIdentifier() + ") and cannot "
              + "be removed from here; reverse the Payment Proposal instead.");
        }

        if (Boolean.TRUE.equals(payment.isProcessed())) {
          PaymentRemovalUtil.reactivate(context.getRecordId(), REACTIVATE_BEFORE_REMOVE_VALUE);
          payment = OBDal.getInstance().get(FIN_Payment.class, context.getRecordId());
        }

        Set<String> affectedInvoiceIds = PaymentRemovalUtil.collectAffectedInvoiceIds(payment);
        removeApplicationDetails(payment);
        OBDal.getInstance().flush();
        PaymentRemovalUtil.updateInvoicesAfterPaymentRemoval(affectedInvoiceIds);
        OBDal.getInstance().flush();
        OBDal.getInstance().getSession().evict(payment);
      }
      return NeoButtonActionHelper.executeButtonActionCore(
          context.getSfEntity(), context.getRecordId(), context.getFieldName(), new JSONObject());
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
      JSONObject record = dataArr.getJSONObject(0);
      String transactionId = resolveFinancialTransactionId(context.getRecordId());
      record.put(FIELD_FINANCIAL_TRANSACTION_ID,
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
