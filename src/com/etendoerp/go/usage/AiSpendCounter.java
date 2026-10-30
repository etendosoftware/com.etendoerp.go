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

package com.etendoerp.go.usage;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import javax.inject.Named;

import org.hibernate.query.NativeQuery;
import org.openbravo.dal.service.OBDal;

/**
 * The money a tenant spent on AI, per day, in US cents: the sum of {@code ETGO_USAGE_EVENT.COST_USD}
 * (agent chat turns, document OCR — every event a provider priced).
 *
 * <p>A strategy and not a declarative resource because a declarative one can only count rows. The
 * value is in cents because {@code ETGO_USAGE_DAILY.QTY} and quota quantities are integers; each
 * tenant-day is rounded half-up once, so a year's figure is within half a cent per active day of
 * the exact sum. Events of the Etendo support user ({@code "actor":"support"}) are not the tenant's
 * spend and are left out.
 *
 * <p>Annotated {@code @Named} and nothing else; see {@link UsageResourceCounter} for why.
 */
@Named(AiSpendCounter.QUALIFIER)
public class AiSpendCounter implements UsageResourceCounter {

  /** The {@code ETGO_BILLING_RESOURCE.Strategy_Qualifier} of the AI spend resource. */
  public static final String QUALIFIER = "aiSpend";

  private static final String PARAM_FROM = "dayFrom";
  private static final String PARAM_TO = "dayTo";
  private static final String PARAM_CLIENT = "clientId";
  private static final BigDecimal CENTS_PER_USD = BigDecimal.valueOf(100);

  static final String SPEND_SQL = "select e.ad_client_id, date_trunc('day', e.occurred_at),"
      + " sum(e.cost_usd) from etgo_usage_event e"
      + " where e.cost_usd is not null"
      + " and e.occurred_at >= :" + PARAM_FROM + " and e.occurred_at < :" + PARAM_TO
      + " and coalesce(cast(e.properties as jsonb) ->> 'actor', '') <> 'support'";
  static final String ONE_CLIENT = " and e.ad_client_id = :" + PARAM_CLIENT;
  static final String GROUP_BY = " group by e.ad_client_id, date_trunc('day', e.occurred_at)";

  @Override
  public List<DailyCount> count(UsageCountRequest request) {
    String sql = SPEND_SQL + (request.isAllTenants() ? "" : ONE_CLIENT) + GROUP_BY;
    @SuppressWarnings("unchecked")
    NativeQuery<Object[]> query = OBDal.getInstance().getSession().createNativeQuery(sql);
    query.setParameter(PARAM_FROM, UsageDayRange.startOfDay(request.getFrom()));
    query.setParameter(PARAM_TO, UsageDayRange.startOfDay(request.getTo()));
    if (!request.isAllTenants()) {
      query.setParameter(PARAM_CLIENT, request.getClientId());
    }
    List<DailyCount> counts = new ArrayList<>();
    for (Object[] row : query.list()) {
      long cents = toCents((Number) row[2]);
      if (cents > 0) {
        counts.add(new DailyCount((String) row[0], (Date) row[1], cents));
      }
    }
    return counts;
  }

  /** A day's spend in USD to whole cents, rounded half-up; null (no priced event) is 0. */
  static long toCents(Number usd) {
    if (usd == null) {
      return 0L;
    }
    BigDecimal value = usd instanceof BigDecimal ? (BigDecimal) usd : new BigDecimal(usd.toString());
    return value.multiply(CENTS_PER_USD).setScale(0, RoundingMode.HALF_UP).longValueExact();
  }
}
