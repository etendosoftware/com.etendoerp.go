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

package com.etendoerp.go.usage.quota;

import java.time.ZoneId;
import java.util.Collection;
import java.util.Date;

import org.apache.commons.lang3.StringUtils;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;

/**
 * ETP-5051 — the one JSON shape of a quota, the contract's {@code QuotaItem}, used by
 * {@code GET /sws/go/billing/subscription} ({@code planQuotas}), {@code POST
 * /sws/go/session/environment} ({@code quotas}) and the NEO 403 refusal ({@code error.quota}).
 *
 * <p>The keys of ETP-5049's {@code planQuotas} item are unchanged; {@code consumptionSource},
 * {@code consumed}, {@code verdict}, {@code periodStart} and {@code periodEnd} are new. A missing
 * value is JSON null, never a default.
 *
 * <p>{@code periodStart} and {@code periodEnd} are the <b>counted window</b> as date-only strings
 * ({@code yyyy-MM-dd}, server-local days): the first counted day and the exclusive end day, the day
 * the period ends on. Consumption is counted over {@code [periodStart, periodEnd)}.
 *
 * <p>ETP-6060: {@code warningAlert} and {@code limitAlert} tell the SPA how to show the quota's
 * alert ({@code pinned}, {@code dismissible}, {@code hidden}); null keeps its default. They change
 * no verdict and no refusal.
 */
public final class QuotaItemJson {

  public static final String RESOURCE_KEY = "resourceKey";
  public static final String RESOURCE_NAME = "resourceName";
  public static final String UNIT_LABEL = "unitLabel";
  public static final String INCLUDED_QTY = "includedQty";
  public static final String ENFORCEMENT_MODE = "enforcementMode";
  public static final String WARNING_THRESHOLD = "warningThreshold";
  public static final String PERIOD = "period";
  public static final String CONSUMPTION_SOURCE = "consumptionSource";
  public static final String CONSUMED = "consumed";
  public static final String VERDICT = "verdict";
  public static final String PERIOD_START = "periodStart";
  public static final String PERIOD_END = "periodEnd";
  public static final String WARNING_ALERT = "warningAlert";
  public static final String LIMIT_ALERT = "limitAlert";

  private QuotaItemJson() {
  }

  /**
   * Renders one evaluation.
   *
   * @param evaluation the evaluation
   * @return the {@code QuotaItem}
   * @throws JSONException never in practice
   */
  public static JSONObject of(QuotaEvaluation evaluation) throws JSONException {
    QuotaDefinition quota = evaluation.getQuota();
    JSONObject item = new JSONObject();
    item.put(RESOURCE_KEY, orNull(quota.getResourceKey()));
    item.put(RESOURCE_NAME, quota.getResourceKey() == null && quota.getResourceName() == null
        ? JSONObject.NULL : StringUtils.defaultString(quota.getResourceName()));
    item.put(UNIT_LABEL, orNull(quota.getUnitLabel()));
    item.put(INCLUDED_QTY, orNull(quota.getIncludedQty()));
    item.put(ENFORCEMENT_MODE, quota.getEnforcementMode());
    item.put(WARNING_THRESHOLD, orNull(quota.getWarningThreshold()));
    item.put(PERIOD, orNull(evaluation.getPlanInterval()));
    item.put(CONSUMPTION_SOURCE, quota.getSource().value());
    item.put(CONSUMED, orNull(evaluation.getConsumed()));
    item.put(VERDICT, evaluation.getVerdict() == null ? JSONObject.NULL
        : evaluation.getVerdict().name());
    QuotaPeriod period = evaluation.getPeriod();
    // The counted window, as days: the first counted day and the exclusive end day. The server
    // counts in local days, so the UTC instants of the period would name the wrong day.
    item.put(PERIOD_START, period == null ? JSONObject.NULL : day(period.getWindowFrom()));
    item.put(PERIOD_END, period == null ? JSONObject.NULL : day(period.getWindowTo()));
    item.put(WARNING_ALERT, orNull(quota.getWarningAlert()));
    item.put(LIMIT_ALERT, orNull(quota.getLimitAlert()));
    return item;
  }

  /**
   * Renders a list of evaluations, in order.
   *
   * @param evaluations the evaluations
   * @return a JSON array of {@code QuotaItem}
   * @throws JSONException never in practice
   */
  public static JSONArray ofAll(Collection<QuotaEvaluation> evaluations) throws JSONException {
    JSONArray array = new JSONArray();
    if (evaluations != null) {
      for (QuotaEvaluation evaluation : evaluations) {
        array.put(of(evaluation));
      }
    }
    return array;
  }

  /**
   * The refusal sentence of the contract, built from catalog metadata only:
   * {@code Quota exceeded: <resourceName> (<consumed>/<includedQty> <unitLabel>)}. The unit is
   * left out when the resource has none.
   *
   * @param evaluation the DENY evaluation
   * @return the message
   */
  public static String refusalMessage(QuotaEvaluation evaluation) {
    QuotaDefinition quota = evaluation.getQuota();
    String name = StringUtils.defaultIfBlank(quota.getResourceName(), quota.getResourceKey());
    String unit = StringUtils.isBlank(quota.getUnitLabel()) ? "" : " " + quota.getUnitLabel();
    return "Quota exceeded: " + name + " (" + evaluation.getConsumed() + '/'
        + quota.getIncludedQty() + unit + ')';
  }

  private static Object orNull(Object value) {
    return value == null ? JSONObject.NULL : value;
  }

  /** The server-local calendar day of a day boundary, as {@code yyyy-MM-dd}. */
  private static String day(Date startOfDay) {
    return startOfDay.toInstant().atZone(ZoneId.systemDefault()).toLocalDate().toString();
  }
}
