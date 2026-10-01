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
package com.etendoerp.go.startup;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.hibernate.Session;
import org.hibernate.query.NativeQuery;
import org.openbravo.dal.service.OBDal;

/**
 * ETP-5565 — the state {@link TemplateRoleAccessStartup} keeps on the live database:
 * {@code ETGO_TPL_ROLE_SYNC} (one row per system template: the fingerprint last propagated) and
 * {@code ETGO_TPL_ROLE_LEASE} (one row, so only one application task sweeps at a time).
 *
 * <p><b>Both tables' rows must never travel.</b> Their definitions ship with the module, but their
 * rows belong to each live database: they are in no dataset, not in the deploy delta's clone list
 * and not exported as source data. A delta that carried them could suppress a sweep or trigger a
 * needless one. The lease row is therefore seeded here, on the live database, not by a module
 * script (whatever a module script writes on the deploy clone would be diffed).</p>
 *
 * <p>Every lease method runs and commits its own short transaction with a short {@code
 * lock_timeout}, so a competing task never waits behind a sweep chunk. A lease is a row with an
 * expiry, not a PostgreSQL advisory lock: the sweep commits per chunk and DAL hands the
 * connection back to the pool in between, so a session-level advisory lock would be released on
 * the wrong connection or leak on a pooled one. An expired lease means its holder died, and any
 * task may take it over. {@code Lease_Until} is stored in UTC, see {@link #UTC_NOW}.</p>
 */
class TemplateRoleSyncStore {

  static final String LEASE_ID = "0";

  /** How long a lease lasts without renewal; renewed on every sweep chunk. */
  static final int LEASE_MINUTES = 10;

  /**
   * {@code Lease_Until} is a timestamp without time zone, and {@code now()} converts to the
   * session's time zone, which follows each JVM (and differs again in a psql session). Writing
   * and comparing it in UTC keeps the expiry correct between tasks or sessions in different time
   * zones.
   */
  static final String UTC_NOW = "(now() AT TIME ZONE 'UTC')";

  private static final String LEASE_LOCK_TIMEOUT = "2s";
  private static final int MAX_ERROR_LENGTH = 2000;

  /** A stored fingerprint and the rule version that computed it. */
  static final class Stored {
    private final String fingerprint;
    private final int algoVersion;

    Stored(String fingerprint, int algoVersion) {
      this.fingerprint = fingerprint;
      this.algoVersion = algoVersion;
    }

    String getFingerprint() {
      return fingerprint;
    }

    int getAlgoVersion() {
      return algoVersion;
    }
  }

  /** template id → stored fingerprint. Read-only, on the current transaction. */
  Map<String, Stored> readFingerprints() {
    @SuppressWarnings("unchecked")
    List<Object[]> rows = (List<Object[]>) session().createNativeQuery(
        "SELECT ad_role_id, fingerprint, algo_version FROM etgo_tpl_role_sync").list();
    Map<String, Stored> result = new LinkedHashMap<>();
    for (Object[] row : rows) {
      result.put((String) row[0], new Stored((String) row[1], ((Number) row[2]).intValue()));
    }
    return result;
  }

  /**
   * Stores {@code fingerprint} for {@code templateId} on the current transaction, unless a newer
   * rule version already stored one (blue and green tasks of two releases must not overwrite
   * each other back and forth).
   */
  void storeFingerprint(String templateId, String fingerprint, int algoVersion,
      int personalRoles) {
    NativeQuery<?> query = session().createNativeQuery(
        "INSERT INTO etgo_tpl_role_sync (etgo_tpl_role_sync_id, ad_client_id, ad_org_id, "
            + "isactive, created, createdby, updated, updatedby, ad_role_id, fingerprint, "
            + "algo_version, synced_at, personal_roles) "
            + "VALUES (get_uuid(), '0', '0', 'Y', now(), '0', now(), '0', :role, :fingerprint, "
            + ":version, now(), :roles) "
            + "ON CONFLICT (ad_role_id) DO UPDATE SET fingerprint = EXCLUDED.fingerprint, "
            + "algo_version = EXCLUDED.algo_version, synced_at = now(), updated = now(), "
            + "personal_roles = EXCLUDED.personal_roles "
            + "WHERE etgo_tpl_role_sync.algo_version <= EXCLUDED.algo_version");
    query.setParameter("role", templateId);
    query.setParameter("fingerprint", fingerprint);
    query.setParameter("version", algoVersion);
    query.setParameter("roles", personalRoles);
    query.executeUpdate();
  }

  /**
   * Takes the lease for {@code holder} if it is free, expired or already {@code holder}'s. Seeds
   * the lease row first if this database has none. Own committed transaction.
   *
   * @return whether {@code holder} now holds the lease
   */
  boolean acquireLease(String holder) {
    try {
      setLockTimeout();
      session().createNativeQuery(
          "INSERT INTO etgo_tpl_role_lease (etgo_tpl_role_lease_id, ad_client_id, ad_org_id, "
              + "isactive, created, createdby, updated, updatedby) "
              + "VALUES ('" + LEASE_ID + "', '0', '0', 'Y', now(), '0', now(), '0') "
              + "ON CONFLICT DO NOTHING").executeUpdate();
      NativeQuery<?> take = session().createNativeQuery(
          "UPDATE etgo_tpl_role_lease SET holder = :holder, "
              + "lease_until = " + UTC_NOW + " + make_interval(mins => :minutes), "
              + "updated = now() "
              + "WHERE etgo_tpl_role_lease_id = '" + LEASE_ID + "' "
              + "AND (lease_until IS NULL OR lease_until < " + UTC_NOW
              + " OR holder = :holder)");
      take.setParameter("holder", holder);
      take.setParameter("minutes", LEASE_MINUTES);
      boolean acquired = take.executeUpdate() == 1;
      OBDal.getInstance().commitAndClose();
      return acquired;
    } catch (RuntimeException e) {
      OBDal.getInstance().rollbackAndClose();
      throw e;
    }
  }

  /**
   * Extends {@code holder}'s lease. Own committed transaction.
   *
   * @return false when {@code holder} no longer holds it (it expired and another task took it)
   */
  boolean renewLease(String holder) {
    try {
      setLockTimeout();
      NativeQuery<?> renew = session().createNativeQuery(
          "UPDATE etgo_tpl_role_lease SET lease_until = " + UTC_NOW
              + " + make_interval(mins => :minutes), "
              + "updated = now() WHERE etgo_tpl_role_lease_id = '" + LEASE_ID + "' "
              + "AND holder = :holder");
      renew.setParameter("holder", holder);
      renew.setParameter("minutes", LEASE_MINUTES);
      boolean held = renew.executeUpdate() == 1;
      OBDal.getInstance().commitAndClose();
      return held;
    } catch (RuntimeException e) {
      OBDal.getInstance().rollbackAndClose();
      throw e;
    }
  }

  /**
   * Frees {@code holder}'s lease right away (not left to expire, so a retry can take it) and
   * records {@code error}, or clears the last one when {@code null}. Own committed transaction.
   */
  void releaseLease(String holder, String error) {
    try {
      setLockTimeout();
      NativeQuery<?> release = session().createNativeQuery(
          "UPDATE etgo_tpl_role_lease SET holder = NULL, lease_until = NULL, "
              + "last_error = CAST(:error AS varchar), updated = now() "
              + "WHERE etgo_tpl_role_lease_id = '" + LEASE_ID + "' AND holder = :holder");
      release.setParameter("holder", holder);
      release.setParameter("error", StringUtils.left(error, MAX_ERROR_LENGTH));
      release.executeUpdate();
      OBDal.getInstance().commitAndClose();
    } catch (RuntimeException e) {
      OBDal.getInstance().rollbackAndClose();
      throw e;
    }
  }

  private void setLockTimeout() {
    session().createNativeQuery("SELECT set_config('lock_timeout', :timeout, true)")
        .setParameter("timeout", LEASE_LOCK_TIMEOUT)
        .getSingleResult();
  }

  private static Session session() {
    return OBDal.getInstance().getSession();
  }
}
