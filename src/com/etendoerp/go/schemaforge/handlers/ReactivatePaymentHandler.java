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

import javax.inject.Named;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONObject;

import com.etendoerp.go.schemaforge.NeoContext;
import com.etendoerp.go.schemaforge.NeoEndpointType;
import com.etendoerp.go.schemaforge.NeoHandler;
import com.etendoerp.go.schemaforge.NeoResponse;
import com.etendoerp.go.schemaforge.util.NeoButtonActionHelper;

/**
 * Shared {@code NeoHandler} for the payment Reactivate and Confirm actions, used by both
 * the payment-in (cobros) and payment-out (pagos) windows. Any payment header entity that
 * registers {@code JAVA_QUALIFIER = 'payment-reactivate'} on its {@code ETGO_SF_ENTITY}
 * record routes through this handler.
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
  private static final String ACTION_PARAM = "action";
  private static final String REACTIVATE_VALUE = "RE";
  private static final String CONFIRM_VALUE = "P";
  /** Exact AD column name for FIN_Payment PK — differs in case from the DB table name. */
  private static final String FIN_PAYMENT_ID_KEY = "Fin_Payment_ID";

  /**
   * Pre-hook: intercepts {@code etprReactivatePayment} (injects {@code action = "RE"}) and
   * {@code aPRMProcessPayment} (injects {@code Fin_Payment_ID} + {@code action = "P"}).
   * Returns {@code null} for all other requests so default handling proceeds.
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

  @Override
  public NeoResponse afterHandle(NeoContext context) {
    return null;
  }
}
