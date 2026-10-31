/*
 * *************************************************************************
 * Etendo License. See https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * *************************************************************************
 */
package com.etendoerp.go.payment.classic;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.Map;
import java.util.function.Supplier;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.client.application.process.BaseProcessActionHandler;
import org.openbravo.client.application.process.ResponseActionsBuilder.MessageType;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.erpCommon.utility.OBMessageUtils;

import com.etendoerp.go.payment.PlanChangeRefusedException;
import com.etendoerp.go.payment.PlanChangeResult;
import com.etendoerp.go.payment.PlanChangeService;
import com.etendoerp.go.payment.PlanChangeTiming;
import com.etendoerp.go.payment.StripeTransportException;
import com.etendoerp.go.schemaforge.data.Plan;
import com.etendoerp.go.schemaforge.data.Subscription;

/**
 * ETP-5053 — the <b>Change Plan</b> button of the Subscription window (Classic): an operator
 * applies, for an assisted case, exactly the change the customer could ask for on the account
 * page — through the same {@link PlanChangeService}, so the same configuration (Allowed plan
 * changes), the same timing and the same refusals apply. Nothing here can do what the customer
 * cannot.
 */
public class SubscriptionPlanChangeActionHandler extends BaseProcessActionHandler {

  private static final Logger log = LogManager.getLogger(SubscriptionPlanChangeActionHandler.class);

  static final String KEY_NAME = "inpKeyName";
  static final String PARAMS = "_params";
  static final String PARAM_TARGET_PLAN = "target_plan_id";
  static final String MSG_APPLIED = "ETGO_PlanChangeApplied";
  static final String MSG_SCHEDULED = "ETGO_PlanChangeScheduled";
  static final String MSG_ALREADY_PENDING = "ETGO_PlanChangeAlreadyPending";
  static final String MSG_REFUSED = "ETGO_PlanChangeRefused";
  static final String MSG_PROVIDER_DOWN = "ETGO_PlanChangeProviderDown";
  static final String MSG_NO_PLAN = "ETGO_PlanChangeNoPlan";
  static final String MSG_ROW_CLOSED = "ETGO_PlanChangeRowClosed";
  private static final String TITLE_ERROR = "Error";
  private static final String TITLE_SUCCESS = "Success";

  private final Supplier<PlanChangeService> serviceFactory;

  /** Production wiring. */
  public SubscriptionPlanChangeActionHandler() {
    this(PlanChangeService::new);
  }

  /**
   * Explicit wiring, for tests.
   *
   * @param serviceFactory creates the service used by one execution
   */
  SubscriptionPlanChangeActionHandler(Supplier<PlanChangeService> serviceFactory) {
    this.serviceFactory = serviceFactory;
  }

  @Override
  protected JSONObject doExecute(Map<String, Object> parameters, String content) {
    try {
      JSONObject request = new JSONObject(StringUtils.defaultIfBlank(content, "{}"));
      return handle(request);
    } catch (PlanChangeRefusedException e) {
      log.info("Operator plan change refused: {} ({})", e.getMessage(), e.code());
      rollback();
      return error(translate(MSG_REFUSED, e.code(), e.getMessage()));
    } catch (StripeTransportException e) {
      log.warn("Operator plan change: the payment provider did not answer", e);
      rollback();
      return error(translate(MSG_PROVIDER_DOWN));
    } catch (JSONException | RuntimeException e) {
      log.error("Operator plan change failed", e);
      rollback();
      return error(translate(MSG_REFUSED, "ERROR", String.valueOf(e.getMessage())));
    }
  }

  private JSONObject handle(JSONObject request) throws JSONException, StripeTransportException {
    JSONObject params = request.optJSONObject(PARAMS);
    String targetPlanId = params == null || params.isNull(PARAM_TARGET_PLAN) ? null
        : StringUtils.trimToNull(params.optString(PARAM_TARGET_PLAN, null));
    if (targetPlanId == null) {
      return error(translate(MSG_NO_PLAN));
    }
    String rowId = selectedRowId(request);
    OBContext.setAdminMode(true);
    Subscription row;
    Plan target;
    try {
      row = rowId == null ? null : OBDal.getInstance().get(Subscription.class, rowId);
      target = OBDal.getInstance().get(Plan.class, targetPlanId);
    } finally {
      OBContext.restorePreviousMode();
    }
    if (row == null) {
      return error(translate(MSG_REFUSED, PlanChangeRefusedException.NO_SUBSCRIPTION,
          "No subscription row is selected"));
    }
    if (row.getEndDate() != null) {
      return error(translate(MSG_ROW_CLOSED));
    }
    if (target == null) {
      return error(translate(MSG_NO_PLAN));
    }
    PlanChangeResult result = serviceFactory.get().requestChange(row, target.getSearchKey());
    commit();
    String planName = StringUtils.defaultIfBlank(result.targetPlan().getName(),
        result.targetPlan().getSearchKey());
    String text;
    if (result.timing() == PlanChangeTiming.IMMEDIATE) {
      text = translate(MSG_APPLIED, planName);
    } else {
      String when = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
          .format(result.effectiveAt().atZone(zone()).toLocalDate());
      text = translate(result.alreadyPending() ? MSG_ALREADY_PENDING : MSG_SCHEDULED, planName,
          when);
    }
    return getResponseBuilder()
        .showMsgInProcessView(MessageType.SUCCESS, translate(TITLE_SUCCESS), text)
        .refreshGrid()
        .build();
  }

  /**
   * The selected {@code ETGO_SUBSCRIPTION} row: the request names the key property
   * ({@code inpKeyName}) and carries the id under that name.
   */
  static String selectedRowId(JSONObject request) {
    String keyName = StringUtils.trimToNull(request.optString(KEY_NAME, null));
    if (keyName == null || request.isNull(keyName)) {
      return null;
    }
    return StringUtils.trimToNull(request.optString(keyName, null));
  }

  protected JSONObject error(String text) {
    return getResponseBuilder().showMsgInProcessView(MessageType.ERROR, translate(TITLE_ERROR),
        text).build();
  }

  protected String translate(String key, String... params) {
    return OBMessageUtils.getI18NMessage(key, params);
  }

  /** Commits the work of this execution (the service never commits). */
  protected void commit() {
    OBDal.getInstance().commitAndClose();
  }

  /** Discards the work of this execution. */
  protected void rollback() {
    OBDal.getInstance().rollbackAndClose();
  }

  /** @return the zone the effective date is shown in: the server's, like the window's dates */
  protected ZoneId zone() {
    return ZoneId.systemDefault();
  }
}
