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
 * All portions are Copyright (C) 2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.usage;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import javax.inject.Named;

import org.hibernate.query.NativeQuery;
import org.openbravo.dal.service.OBDal;

import com.etendoerp.go.usageevents.PublicApiCall;
import com.etendoerp.go.usageevents.UsageEvent;
import com.etendoerp.go.usageevents.UsageEventTypes;

/**
 * ETP-6057 — the public API requests a tenant made, per day: the sum of {@code properties.count}
 * over its {@code publicapi.request} rows of {@code ETGO_USAGE_EVENT} (one row per key, spec,
 * entity, method and status per minute, written by {@code PublicApiUsageCounter}).
 *
 * <p>Every request NEO answered counts, refused ones included (401/402/403/4xx/5xx), and each
 * operation of a {@code /sws/neo/batch} call counts as one request; the status is in the row for
 * anyone who wants to split them. The wrong-secret 401 of the token exchange
 * ({@code properties.kind = 'tokenExchange'}) is recorded but <b>not billed</b>: a key's
 * {@code client_identifier} is not secret, so anyone could otherwise inflate a tenant's bill by
 * sending wrong secrets for it. The exclusion is on the kind, which only the backend sets, never on
 * a target a request path could imitate.</p>
 *
 * <p>Only rows with {@code SOURCE = 'backend'} count: {@code publicapi.request} is a server-only
 * type the UI usage endpoint drops, and the source filter is a second line. The
 * {@code "actor":"support"} exclusion mirrors {@link AiSpendCounter} and only takes effect if a
 * row is ever written for the tenant's support user; a public API key never acts as it today.</p>
 *
 * <p>A strategy and not a declarative resource because a declarative one counts rows, and a row here
 * stands for {@code count} requests. Annotated {@code @Named} and nothing else; see
 * {@link UsageResourceCounter} for why.</p>
 */
@Named(PublicApiRequestCounter.QUALIFIER)
public class PublicApiRequestCounter implements UsageResourceCounter {

  /** The {@code ETGO_BILLING_RESOURCE.Strategy_Qualifier} of the public API requests resource. */
  public static final String QUALIFIER = "publicApiRequests";

  private static final String PARAM_FROM = "dayFrom";
  private static final String PARAM_TO = "dayTo";
  private static final String PARAM_CLIENT = "clientId";
  private static final String PARAM_TYPE = "eventType";
  private static final String PARAM_SOURCE = "source";
  private static final String PARAM_UNBILLED_KIND = "unbilledKind";

  static final String REQUESTS_SQL = "select e.ad_client_id, date_trunc('day', e.occurred_at),"
      + " sum(cast(cast(e.properties as jsonb) ->> 'count' as bigint)) from etgo_usage_event e"
      + " where e.event_type = :" + PARAM_TYPE
      + " and e.source = :" + PARAM_SOURCE
      + " and e.occurred_at >= :" + PARAM_FROM + " and e.occurred_at < :" + PARAM_TO
      + " and coalesce(cast(e.properties as jsonb) ->> 'kind', '') <> :" + PARAM_UNBILLED_KIND
      + " and coalesce(cast(e.properties as jsonb) ->> 'actor', '') <> 'support'";
  static final String ONE_CLIENT = " and e.ad_client_id = :" + PARAM_CLIENT;
  static final String GROUP_BY = " group by e.ad_client_id, date_trunc('day', e.occurred_at)";
  // Both variants are complete constants so no SQL is assembled at run time.
  static final String ALL_TENANTS_SQL = REQUESTS_SQL + GROUP_BY;
  static final String ONE_TENANT_SQL = REQUESTS_SQL + ONE_CLIENT + GROUP_BY;

  @Override
  public List<DailyCount> count(UsageCountRequest request) {
    @SuppressWarnings("unchecked")
    NativeQuery<Object[]> query = OBDal.getInstance().getSession()
        .createNativeQuery(request.isAllTenants() ? ALL_TENANTS_SQL : ONE_TENANT_SQL);
    query.setParameter(PARAM_TYPE, UsageEventTypes.PUBLIC_API_REQUEST);
    query.setParameter(PARAM_SOURCE, UsageEvent.SOURCE_BACKEND);
    query.setParameter(PARAM_UNBILLED_KIND, PublicApiCall.KIND_TOKEN_EXCHANGE);
    query.setParameter(PARAM_FROM, UsageDayRange.startOfDay(request.getFrom()));
    query.setParameter(PARAM_TO, UsageDayRange.startOfDay(request.getTo()));
    if (!request.isAllTenants()) {
      query.setParameter(PARAM_CLIENT, request.getClientId());
    }
    List<DailyCount> counts = new ArrayList<>();
    for (Object[] row : query.list()) {
      long requests = row[2] == null ? 0L : ((Number) row[2]).longValue();
      if (requests > 0L) {
        counts.add(new DailyCount((String) row[0], (Date) row[1], requests));
      }
    }
    return counts;
  }
}
