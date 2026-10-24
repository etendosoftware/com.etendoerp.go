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

import java.nio.charset.StandardCharsets;

import org.openbravo.dal.service.OBDal;

/**
 * ETP-5051 — serialises the NEO write check of one tenant and one resource, so two concurrent
 * creates cannot both count the same consumption and both slip under a {@code block} quota.
 *
 * <p>Taken only for a {@link ConsumptionSource#LIVE live} {@code block} quota, right before the
 * live count, and only once a created row has matched the resource and the tenant has such a quota
 * — every other create takes no lock. The stored aggregate is lagged by the usage job, so its cap
 * is approximate anyway and a lock would buy nothing.
 */
@FunctionalInterface
public interface QuotaLock {

  /** Takes no lock: for evaluators that never run the write check, and for tests. */
  QuotaLock NONE = (clientId, resourceId) -> {
  };

  /**
   * Serialises the caller's transaction with every other transaction that takes the same lock,
   * until it commits or rolls back.
   *
   * @param clientId the tenant
   * @param resourceId the billing resource
   */
  void acquire(String clientId, String resourceId);

  /**
   * The production lock: a PostgreSQL transaction-level advisory lock
   * ({@code pg_advisory_xact_lock}) on the request's own session, released by its commit or
   * rollback. The module targets PostgreSQL only.
   *
   * @return the advisory lock
   */
  static QuotaLock advisory() {
    return (clientId, resourceId) -> OBDal.getInstance().getSession()
        // count(*) over the call: a bare pg_advisory_xact_lock() returns void, which Hibernate
        // cannot map.
        .createNativeQuery("select count(*) from (select pg_advisory_xact_lock(:lockKey)) l")
        .setParameter("lockKey", key(clientId, resourceId))
        .getSingleResult();
  }

  /**
   * The advisory-lock key of a tenant and a resource: a stable 64-bit FNV-1a hash, the same in
   * every JVM, so every node serialises on the same key.
   *
   * @param clientId the tenant
   * @param resourceId the billing resource
   * @return the lock key
   */
  static long key(String clientId, String resourceId) {
    long hash = 0xcbf29ce484222325L;
    byte[] bytes = ("etgo-quota|" + clientId + '|' + resourceId).getBytes(StandardCharsets.UTF_8);
    for (byte b : bytes) {
      hash ^= b & 0xff;
      hash *= 0x100000001b3L;
    }
    return hash;
  }
}
