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
package com.etendoerp.go.roles;

import org.hibernate.Session;
import org.openbravo.dal.service.OBDal;

/**
 * ETP-5278 — serializes every role-composition WRITE that targets the same {@code AD_User}:
 * {@link UserRoleCompositionService#assignTemplateRoles(String, java.util.List,
 * org.openbravo.model.ad.access.Role, String)}, {@link
 * UserRoleCompositionService#promoteToAdmin} and {@link UserRoleCompositionService#demoteFromAdmin}.
 *
 * <p><b>Why.</b> Before this lock, two overlapping requests for the same user (e.g. the Users
 * form's Save re-enabled mid-flight, or "Make administrator" clicked while a role save was still
 * running) each diffed against the SAME pre-change {@code AD_Role_Inheritance} snapshot. The
 * first to commit won; the second then failed deleting/updating rows the first had already
 * removed ({@code StaleStateException}/{@code OptimisticLockException}/{@code
 * EntityNotFoundException}/duplicate key), which surfaced as the bridge's generic HTTP 500
 * (reproduced locally and confirmed in production logs, ETP-5278 CP-6).</p>
 *
 * <p><b>How.</b> A PostgreSQL transaction-scoped advisory lock keyed by the target user id,
 * taken through the current Hibernate session (so it lives in the same transaction as the
 * write that follows) and released automatically at commit/rollback — every NEO webhook
 * request is one transaction. The loser therefore WAITS, and once it gets the lock it reads the
 * winner's committed state (READ COMMITTED) and reconciles against that instead of a stale
 * snapshot. An advisory lock is used rather than {@code SELECT … FOR UPDATE} on {@code AD_User}
 * so it never blocks unrelated writers of that row (e.g. the form's own NEO PATCH).</p>
 *
 * <p><b>Must run before any read of the target user.</b> Anything loaded into the session
 * BEFORE the lock stays in Hibernate's first-level cache with its pre-lock state — callers take
 * the lock first thing, right after argument validation.</p>
 *
 * <p>The wait is bounded by {@link #LOCK_TIMEOUT} (restored to its previous value right after the
 * lock is granted, so the rest of the transaction keeps its normal lock semantics). A timeout
 * raises PostgreSQL {@code 55P03}, which the webhooks report as {@code CONCURRENT_MODIFICATION}
 * — see {@link RoleWriteConflicts#isConcurrencyFailure(Throwable)}.</p>
 *
 * <p>Instance-based (not static) so plain unit tests, which mock {@link OBDal} without a real
 * session, can inject {@link #NO_OP}.</p>
 */
public class UserRoleWriteLock {

  /** Namespaces this lock's keys so they never collide with other advisory-lock users. */
  static final int LOCK_NAMESPACE = 5278;

  /** Upper bound on how long a request waits for a concurrent write on the same user. */
  static final String LOCK_TIMEOUT = "30s";

  /** Lock used by plain unit tests that have no real Hibernate session. */
  static final UserRoleWriteLock NO_OP = new UserRoleWriteLock() {
    @Override
    public void acquire(String userId) {
      // intentionally empty — see class javadoc
    }
  };

  /**
   * Blocks until no other transaction holds the write lock for {@code userId}, then holds it
   * until this transaction ends.
   *
   * @param userId the target {@code AD_User_ID}; never blank (callers validate first)
   */
  public void acquire(String userId) {
    Session session = OBDal.getInstance().getSession();
    Object previousTimeout = session
        .createNativeQuery("SELECT current_setting('lock_timeout')")
        .getSingleResult();
    session.createNativeQuery("SELECT set_config('lock_timeout', :timeout, true)")
        .setParameter("timeout", LOCK_TIMEOUT)
        .getSingleResult();
    session.createNativeQuery("SELECT CAST(pg_advisory_xact_lock(:ns, hashtext(:userId)) AS text)")
        .setParameter("ns", LOCK_NAMESPACE)
        .setParameter("userId", userId)
        .getSingleResult();
    session.createNativeQuery("SELECT set_config('lock_timeout', :timeout, true)")
        .setParameter("timeout", String.valueOf(previousTimeout))
        .getSingleResult();
  }
}
