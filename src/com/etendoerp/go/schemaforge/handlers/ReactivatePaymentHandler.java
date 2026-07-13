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
 * standard action proceeds as before.
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
   * anything — it walks the same detail list we are about to remove. The payment is evicted
   * from the Hibernate session (not the whole session — {@code context.getSfEntity()} and its
   * lazily-loaded {@code AD_Tab} association, read later by
   * {@code NeoButtonActionHelper.addTabParamsCore}, must stay attached) so that the delegated
   * action's own {@code OBDal.get(FIN_Payment.class, id)} re-queries a fresh instance whose
   * {@code getFINPaymentDetailList()} correctly reflects the just-flushed deletes, instead of
   * returning the same session-cached instance with a stale in-memory collection. Everything
   * — our cleanup and the delegated removal — stays inside the single request transaction, so
   * a downstream failure rolls back atomically instead of leaving a partially-cleaned payment.
   *
   * @param context the current NEO request context
   * @return the process result from the delegated button action, or a 500 error on failure
   */
  private NeoResponse handleRemove(NeoContext context) {
    try {
      FIN_Payment payment = OBDal.getInstance().get(FIN_Payment.class, context.getRecordId());
      if (payment != null) {
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
   * Removes every {@code FIN_PaymentScheduleDetail} and {@code FIN_PaymentDetail} row
   * belonging to {@code payment}. A no-op when the payment has no applied details (e.g. a
   * draft/unapplied payment) — the regression path is unaffected.
   *
   * @param payment the payment whose application join rows are being removed
   */
  private static void removeApplicationDetails(FIN_Payment payment) {
    List<FIN_PaymentDetail> details = new ArrayList<>(payment.getFINPaymentDetailList());
    for (FIN_PaymentDetail detail : details) {
      List<FIN_PaymentScheduleDetail> scheduleDetails =
          new ArrayList<>(detail.getFINPaymentScheduleDetailList());
      for (FIN_PaymentScheduleDetail scheduleDetail : scheduleDetails) {
        OBDal.getInstance().remove(scheduleDetail);
      }
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
