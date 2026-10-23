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
import java.util.List;
import java.util.Optional;

import org.apache.commons.lang3.StringUtils;
import org.hibernate.query.NativeQuery;
import org.openbravo.dal.service.OBDal;

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

  private static final String OPEN_DEMOS_SQL = "select d.etgo_account_id, d.environment_id,"
      + " d.created, exists (select 1 from etgo_hubspot_outbox e"
      + "   where e.etgo_account_id = d.etgo_account_id and e.event_type = 'DEMO_ENTERED')"
      + " from etgo_hubspot_outbox d"
      + " where d.event_type = 'DEMO_READY' and d.status in ('PENDING', 'SENT')"
      + " and d.environment_id is not null"
      + " and not exists (select 1 from etgo_hubspot_outbox c"
      + "   where c.etgo_account_id = d.etgo_account_id and c.event_type in ('PAID', 'DEMO_EXPIRED'))"
      + " order by d.created";

  // ETGO_GO_SESSION covers the SPA's cookie path; ETGO_USAGE_EVENT also covers the legacy bearer
  // login and survives the session rows being purged. Either one is an entry.
  private static final String ENTERED_SQL = "select case when exists (select 1 from etgo_go_session"
      + "   where ctx_client_id = :environment)"
      + " or exists (select 1 from etgo_usage_event where ad_client_id = :environment"
      + "   and event_type = 'session.login') then 1 else 0 end";

  private static final String PAID_SQL = "select p.value from etgo_checkout_request c"
      + " left join etgo_plan p on p.etgo_plan_id = c.etgo_plan_id"
      + " where c.etgo_account_id = :account and c.paid_at is not null and c.paid_at >= :since"
      + " order by c.paid_at desc";

  private static final String PENDING_SQL = "select o.etgo_hubspot_outbox_id, o.etgo_account_id,"
      + " o.event_type, o.environment_id, o.payload, o.attempt_count, o.next_attempt_at,"
      + " a.email, a.name"
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
  public List<OpenDemo> findOpenDemos() {
    List<OpenDemo> demos = new ArrayList<>();
    for (Object row : query(OPEN_DEMOS_SQL).getResultList()) {
      Object[] columns = (Object[]) row;
      demos.add(new OpenDemo((String) columns[0], (String) columns[1], instant(columns[2]),
          Boolean.TRUE.equals(columns[3])));
    }
    return demos;
  }

  @Override
  public boolean hasEnteredEnvironment(String environmentId) {
    Object result = query(ENTERED_SQL).setParameter("environment", environmentId)
        .getSingleResult();
    return result instanceof Number && ((Number) result).intValue() == 1;
  }

  @Override
  public Optional<PaidCheckout> findPaidCheckout(String accountId, Instant since) {
    List<?> rows = query(PAID_SQL)
        .setParameter(PARAM_ACCOUNT, accountId)
        .setParameter("since", Timestamp.from(since))
        .setMaxResults(1)
        .getResultList();
    return rows.isEmpty() ? Optional.empty()
        : Optional.of(new PaidCheckout((String) rows.get(0)));
  }

  @Override
  public List<PendingEvent> findPending(int limit) {
    List<PendingEvent> pending = new ArrayList<>();
    for (Object row : query(PENDING_SQL).setMaxResults(limit).getResultList()) {
      Object[] c = (Object[]) row;
      pending.add(new PendingEvent((String) c[0], (String) c[1], (String) c[2], (String) c[3],
          (String) c[4], c[5] == null ? 0 : ((Number) c[5]).intValue(), instant(c[6]),
          (String) c[7], (String) c[8]));
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
        .setParameter("type", eventType)
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
