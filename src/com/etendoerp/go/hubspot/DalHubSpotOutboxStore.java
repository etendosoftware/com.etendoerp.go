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

package com.etendoerp.go.hubspot;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.apache.commons.lang3.StringUtils;
import org.hibernate.query.NativeQuery;
import org.openbravo.dal.service.OBDal;

import com.etendoerp.go.payment.TenantEnvironmentLifecycleService;

/**
 * {@link HubSpotOutboxStore} in native SQL on the DAL session, so its writes join whatever
 * transaction the caller has open.
 *
 * <p>Native rather than DAL entities: rows are added with {@code ON CONFLICT DO NOTHING}
 * ({@link HubSpotOutbox#enqueue}), so an event detected twice is recorded once.
 */
class DalHubSpotOutboxStore implements HubSpotOutboxStore {

  /** Longest error text kept, so a huge HubSpot answer cannot bloat the table. */
  static final int MAX_ERROR_LENGTH = 2000;

  private static final String PARAM_ACCOUNT = "account";
  private static final String PARAM_ERROR = "error";
  private static final String PARAM_ENVIRONMENT = "environment";
  private static final String PARAM_TYPE = "type";
  private static final String PARAM_SINCE = "since";

  private static final String BILLING_TYPES = "('PAYMENT', 'PAYMENT_FAILED', 'CANCEL_REQUESTED',"
      + " 'CANCEL_REVERTED', 'CANCELED')";

  // The purchase that bought the subscription names the productive it created; a re-subscription
  // buys a new subscription for an existing environment, recorded the same way.
  private static final String SUBSCRIPTION_ENVIRONMENT_SQL = "select c.created_client_id,"
      + " c.client_name, p.value from etgo_checkout_request c"
      + " left join etgo_plan p on p.etgo_plan_id = c.etgo_plan_id"
      + " where c.stripe_subscription_id = :subscription and c.created_client_id is not null"
      + " order by c.created desc";

  private static final String NEWER_BILLING_SQL = "select count(*) from etgo_hubspot_outbox"
      + " where etgo_account_id = :account and environment_id = :environment"
      + " and status = 'SENT' and event_type in " + BILLING_TYPES
      + " and occurred_at > :occurred";

  private static final String CANCELED_BEFORE_SQL = "select count(*) from etgo_hubspot_outbox"
      + " where etgo_account_id = :account and environment_id = :environment"
      + " and status = 'SENT' and event_type = '" + CANCELED + "'"
      + " and occurred_at < :occurred";

  private static final String ENVIRONMENT_DEAL_SQL = "select hubspot_object_id, payload"
      + " from etgo_hubspot_outbox where etgo_account_id = :account and event_type = :type"
      + " and event_key = :environment and status = 'SENT'";

  /** How long after its expiry a demo is still watched for a late payment. */
  static final int EXPIRED_WATCH_DAYS = 90;

  // A demo that expired stays open: paying after the trial ended still moves the deal (ETP-6082).
  // It is dropped once it paid, or some months after it expired, so the pass does not grow forever.
  // Every column is aliased: Hibernate refuses two unnamed columns of the same kind ("exists").
  private static final String OPEN_DEMOS_SQL = "select d.etgo_account_id, d.environment_id,"
      + " d.created, exists (select 1 from etgo_hubspot_outbox e"
      + "   where e.etgo_account_id = d.etgo_account_id and e.event_type = 'DEMO_ENTERED')"
      + " as entered,"
      + " exists (select 1 from etgo_hubspot_outbox x"
      + "   where x.etgo_account_id = d.etgo_account_id and x.event_type = 'DEMO_EXPIRED')"
      + " as expired,"
      + " (select count(*) from etgo_hubspot_outbox m"
      + "   where m.etgo_account_id = d.etgo_account_id and m.event_type = 'MILESTONE')"
      + " as milestones"
      + " from etgo_hubspot_outbox d"
      + " where d.event_type = 'DEMO_READY' and d.status in ('PENDING', 'SENT')"
      + " and d.environment_id is not null"
      + " and not exists (select 1 from etgo_hubspot_outbox c"
      + "   where c.etgo_account_id = d.etgo_account_id and c.event_type = 'PAID')"
      + " and not exists (select 1 from etgo_hubspot_outbox c"
      + "   where c.etgo_account_id = d.etgo_account_id and c.event_type = 'DEMO_EXPIRED'"
      + "   and c.created < now() - interval '" + EXPIRED_WATCH_DAYS + " days')"
      + " order by d.created";

  /**
   * What the owner did in the demo. A row counts when it is newer than the demo and was not
   * loaded by the onboarding or the sample data: every row those load is mapped in
   * {@code AD_REF_DATA_LOADED}. An import is an asynchronous batch job, which only the import
   * dialog starts. The completed and posted dates are the invoice's last update.
   */
  private static final String MILESTONES_SQL = "select"
      + " (select min(j.finished) from etgo_batch_job j where j.ad_client_id = :environment"
      + "   and j.created >= :since and j.state in ('SUCCEEDED', 'PARTIAL')"
      + "   and j.processed_rows > 0) as m_import,"
      + ownRows("min(t.created)", "c_bpartner", "291", "") + " as m_partner,"
      + ownRows("min(t.created)", "m_product", "208", "") + " as m_product,"
      + ownRows("min(t.created)", "c_order", "259", "") + " as m_order,"
      + ownRows("min(t.created)", "c_invoice", "318", "") + " as m_invoice,"
      + ownRows("min(t.updated)", "c_invoice", "318", " and t.docstatus = 'CO'")
      + " as m_completed,"
      + ownRows("min(t.updated)", "c_invoice", "318", " and t.posted = 'Y'") + " as m_posted";

  private static final String PRODUCTIVE_SQL = "select case when exists (select 1"
      + "   from ad_preference p where p.ad_client_id = :environment and p.attribute = :attribute"
      + "   and p.isactive = 'Y' and coalesce(p.value, '') <> '')"
      + " or exists (select 1 from etgo_checkout_request c where c.etgo_account_id = :account"
      + "   and c.created_client_id is not null) then 1 else 0 end";

  // ETGO_GO_SESSION covers the SPA's cookie path; ETGO_USAGE_EVENT also covers the legacy bearer
  // login and survives the session rows being purged. Either one is an entry.
  private static final String ENTERED_SQL = "select case when exists (select 1 from etgo_go_session"
      + "   where ctx_client_id = :environment)"
      + " or exists (select 1 from etgo_usage_event where ad_client_id = :environment"
      + "   and event_type = 'session.login') then 1 else 0 end";

  // The interval is the bought price's; the plan's own is the fallback for an older purchase.
  private static final String PAID_SQL = "select p.value,"
      + " coalesce(pp.billing_interval, p.billing_interval) from etgo_checkout_request c"
      + " left join etgo_plan p on p.etgo_plan_id = c.etgo_plan_id"
      + " left join etgo_plan pp on pp.provider_price_id = c.stripe_price_id"
      + " where c.etgo_account_id = :account and c.paid_at is not null and c.paid_at >= :since"
      + " order by c.paid_at desc";

  private static final String PENDING_SQL = "select o.etgo_hubspot_outbox_id, o.etgo_account_id,"
      + " o.event_type, o.environment_id, o.payload, o.attempt_count, o.next_attempt_at,"
      + " a.email, a.name, o.occurred_at, a.phone, a.sector"
      + " from etgo_hubspot_outbox o join etgo_account a on a.etgo_account_id = o.etgo_account_id"
      + " where o.status = 'PENDING' and o.isactive = 'Y'"
      + " order by o.created, o.etgo_hubspot_outbox_id";

  @Override
  public boolean enqueue(String accountId, String eventType, String environmentId,
      String payload) {
    return OBDal.getInstance().getSession().doReturningWork(connection -> HubSpotOutbox
        .enqueue(connection, accountId, eventType, environmentId, payload));
  }

  @Override
  public boolean enqueueKeyed(String accountId, String eventType, String eventKey,
      String environmentId, String payload) {
    return OBDal.getInstance().getSession().doReturningWork(connection -> HubSpotOutbox
        .enqueue(connection, accountId, eventType, eventKey, environmentId, payload, null));
  }

  @Override
  public Optional<SubscriptionEnvironment> findSubscriptionEnvironment(String subscriptionId) {
    List<?> rows = query(SUBSCRIPTION_ENVIRONMENT_SQL)
        .setParameter("subscription", subscriptionId)
        .setMaxResults(1)
        .getResultList();
    if (rows.isEmpty()) {
      return Optional.empty();
    }
    Object[] c = (Object[]) rows.get(0);
    return Optional.of(new SubscriptionEnvironment((String) c[0], (String) c[1], (String) c[2]));
  }

  @Override
  public void assignEnvironment(String id, String environmentId) {
    update("environment_id = :environment", id)
        .setParameter(PARAM_ENVIRONMENT, environmentId)
        .executeUpdate();
  }

  @Override
  public boolean hasNewerBillingEvent(String accountId, String environmentId,
      Instant occurredAt) {
    Object count = query(NEWER_BILLING_SQL)
        .setParameter(PARAM_ACCOUNT, accountId)
        .setParameter(PARAM_ENVIRONMENT, environmentId)
        .setParameter("occurred", Timestamp.from(occurredAt))
        .getSingleResult();
    return count instanceof Number && ((Number) count).intValue() > 0;
  }

  @Override
  public boolean wasCanceledBefore(String accountId, String environmentId, Instant occurredAt) {
    Object count = query(CANCELED_BEFORE_SQL)
        .setParameter(PARAM_ACCOUNT, accountId)
        .setParameter(PARAM_ENVIRONMENT, environmentId)
        .setParameter("occurred", Timestamp.from(occurredAt))
        .getSingleResult();
    return count instanceof Number && ((Number) count).intValue() > 0;
  }

  @Override
  public Optional<EnvironmentDeal> findEnvironmentDeal(String accountId, String environmentId) {
    List<?> rows = query(ENVIRONMENT_DEAL_SQL)
        .setParameter(PARAM_ACCOUNT, accountId)
        .setParameter(PARAM_TYPE, PRODUCTIVE_DEAL)
        .setParameter(PARAM_ENVIRONMENT, environmentId)
        .getResultList();
    if (rows.isEmpty()) {
      return Optional.empty();
    }
    Object[] c = (Object[]) rows.get(0);
    return Optional.of(new EnvironmentDeal((String) c[0],
        HubSpotOutbox.OWNED_DEAL_PAYLOAD.equals(c[1])));
  }

  @Override
  public int countEnvironmentDeals(String accountId) {
    Object count = query("select count(*) from etgo_hubspot_outbox"
        + " where etgo_account_id = :account and event_type = :type")
        .setParameter(PARAM_ACCOUNT, accountId)
        .setParameter(PARAM_TYPE, PRODUCTIVE_DEAL)
        .getSingleResult();
    return count instanceof Number ? ((Number) count).intValue() : 0;
  }

  @Override
  public void recordEnvironmentDeal(String accountId, String environmentId, EnvironmentDeal deal) {
    OBDal.getInstance().getSession().doWork(connection -> HubSpotOutbox
        .recordEnvironmentDeal(connection, accountId, environmentId, deal));
  }

  @Override
  public List<OpenDemo> findOpenDemos() {
    List<OpenDemo> demos = new ArrayList<>();
    for (Object row : query(OPEN_DEMOS_SQL).getResultList()) {
      Object[] columns = (Object[]) row;
      demos.add(new OpenDemo((String) columns[0], (String) columns[1], instant(columns[2]),
          Boolean.TRUE.equals(columns[3]), Boolean.TRUE.equals(columns[4]),
          columns[5] instanceof Number ? ((Number) columns[5]).intValue() : 0));
    }
    return demos;
  }

  @Override
  public boolean hasEnteredEnvironment(String environmentId) {
    Object result = query(ENTERED_SQL).setParameter(PARAM_ENVIRONMENT, environmentId)
        .getSingleResult();
    return result instanceof Number && ((Number) result).intValue() == 1;
  }

  @Override
  public Map<String, Instant> findMilestones(String environmentId, Instant since) {
    Object[] dates = (Object[]) query(MILESTONES_SQL)
        .setParameter(PARAM_ENVIRONMENT, environmentId)
        .setParameter(PARAM_SINCE, Timestamp.from(since))
        .getSingleResult();
    Map<String, Instant> reached = new LinkedHashMap<>();
    for (int i = 0; i < MILESTONES.size(); i++) {
      Instant at = instant(dates[i]);
      if (at != null) {
        reached.put(MILESTONES.get(i), at);
      }
    }
    return reached;
  }

  @Override
  public boolean hasProductiveEnvironment(String accountId, String environmentId) {
    Object result = query(PRODUCTIVE_SQL)
        .setParameter(PARAM_ENVIRONMENT, environmentId)
        .setParameter("attribute", TenantEnvironmentLifecycleService.ASSOCIATED_PRODUCTIVE_ATTRIBUTE)
        .setParameter(PARAM_ACCOUNT, accountId)
        .getSingleResult();
    return result instanceof Number && ((Number) result).intValue() == 1;
  }

  @Override
  public Optional<PaidCheckout> findPaidCheckout(String accountId, Instant since) {
    List<?> rows = query(PAID_SQL)
        .setParameter(PARAM_ACCOUNT, accountId)
        .setParameter(PARAM_SINCE, Timestamp.from(since))
        .setMaxResults(1)
        .getResultList();
    if (rows.isEmpty()) {
      return Optional.empty();
    }
    Object[] c = (Object[]) rows.get(0);
    return Optional.of(new PaidCheckout((String) c[0], hubSpotInterval((String) c[1])));
  }

  /** {@code ETGO_PLAN.BILLING_INTERVAL} as the deal's {@code etendo_periodicidad}. */
  static String hubSpotInterval(String billingInterval) {
    if ("year".equals(billingInterval)) {
      return StripeSubscriptionEvents.ANNUAL;
    }
    return "month".equals(billingInterval) ? StripeSubscriptionEvents.MONTHLY : null;
  }

  /**
   * One milestone's date: {@code aggregate} over the demo's rows of {@code table} that its owner
   * created.
   */
  private static String ownRows(String aggregate, String table, String tableId,
      String condition) {
    String key = table + "_id";
    return " (select " + aggregate + " from " + table + " t where t.ad_client_id = :environment"
        + " and t.created >= :since" + condition
        + " and not exists (select 1 from ad_ref_data_loaded r"
        + "   where r.ad_client_id = :environment and r.ad_table_id = '" + tableId + "'"
        + "   and r.specific_id = t." + key + "))";
  }

  @Override
  public List<PendingEvent> findPending(int limit) {
    List<PendingEvent> pending = new ArrayList<>();
    for (Object row : query(PENDING_SQL).setMaxResults(limit).getResultList()) {
      Object[] c = (Object[]) row;
      pending.add(new PendingEvent((String) c[0], (String) c[1], (String) c[2], (String) c[3],
          (String) c[4], c[5] == null ? 0 : ((Number) c[5]).intValue(), instant(c[6]),
          (String) c[7], (String) c[8], instant(c[9]), (String) c[10], (String) c[11]));
    }
    return pending;
  }

  @Override
  public String findDealId(String accountId) {
    List<?> rows = query("select hubspot_object_id from etgo_hubspot_outbox"
        + " where etgo_account_id = :account and event_type = 'DEMO_READY' and status = 'SENT'")
        .setParameter(PARAM_ACCOUNT, accountId)
        .getResultList();
    return rows.isEmpty() ? null : StringUtils.trimToNull((String) rows.get(0));
  }

  @Override
  public String findStatus(String accountId, String eventType) {
    List<?> rows = query("select status from etgo_hubspot_outbox"
        + " where etgo_account_id = :account and event_type = :type")
        .setParameter(PARAM_ACCOUNT, accountId)
        .setParameter(PARAM_TYPE, eventType)
        .getResultList();
    return rows.isEmpty() ? null : (String) rows.get(0);
  }

  @Override
  public void markSent(String id, String hubSpotObjectId) {
    update("status = 'SENT', processed_at = now(), next_attempt_at = null, last_error = null,"
        + " hubspot_object_id = :object", id)
        .setParameter("object", hubSpotObjectId)
        .executeUpdate();
  }

  @Override
  public void markRetry(String id, int attemptCount, Instant nextAttemptAt, String error) {
    update("attempt_count = :attempts, next_attempt_at = :next, last_error = :error", id)
        .setParameter("attempts", BigDecimal.valueOf(attemptCount))
        .setParameter("next", Timestamp.from(nextAttemptAt))
        .setParameter(PARAM_ERROR, StringUtils.left(error, MAX_ERROR_LENGTH))
        .executeUpdate();
  }

  @Override
  public void markFailed(String id, int attemptCount, String error) {
    update("status = 'FAILED', processed_at = now(), attempt_count = :attempts,"
        + " next_attempt_at = null, last_error = :error", id)
        .setParameter("attempts", BigDecimal.valueOf(attemptCount))
        .setParameter(PARAM_ERROR, StringUtils.left(error, MAX_ERROR_LENGTH))
        .executeUpdate();
  }

  @Override
  public void markSkipped(String id, String reason) {
    update("status = 'SKIPPED', processed_at = now(), next_attempt_at = null,"
        + " last_error = :error", id)
        .setParameter(PARAM_ERROR, StringUtils.left(reason, MAX_ERROR_LENGTH))
        .executeUpdate();
  }

  @Override
  public void commit() {
    OBDal.getInstance().commitAndClose();
  }

  @Override
  public void rollback() {
    OBDal.getInstance().rollbackAndClose();
  }

  private static NativeQuery<?> update(String assignments, String id) {
    return query("update etgo_hubspot_outbox set " + assignments + ", updated = now()"
        + " where etgo_hubspot_outbox_id = :id").setParameter("id", id);
  }

  private static NativeQuery<?> query(String sql) {
    return OBDal.getInstance().getSession().createNativeQuery(sql);
  }

  private static Instant instant(Object value) {
    return value instanceof Timestamp ? ((Timestamp) value).toInstant() : null;
  }
}
