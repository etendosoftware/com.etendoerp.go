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

import java.sql.SQLException;

import javax.persistence.EntityNotFoundException;
import javax.persistence.OptimisticLockException;
import javax.persistence.PessimisticLockException;

import org.hibernate.StaleStateException;
import org.hibernate.exception.ConstraintViolationException;
import org.hibernate.exception.LockAcquisitionException;

/**
 * ETP-5278 — classifies a failure of a role-composition write as "another request changed this
 * user's roles at the same time" so the webhooks ({@code SFAssignUserRoles}, {@code
 * SFPromoteUserRole}) answer {@code success:false} with {@link #CODE} instead of the bridge's
 * generic HTTP 500.
 *
 * <p>With {@link UserRoleWriteLock} in place these should only happen when the lock wait times
 * out, but the classification is kept broad on purpose (defence in depth): every exception type
 * listed in {@link #isConcurrencyFailure(Throwable)} is one actually observed when two writes
 * for the same user overlapped (local repro + production logs, ETP-5278).</p>
 */
public final class RoleWriteConflicts {

  /** Machine-readable failure code the frontend maps to an i18n message. */
  public static final String CODE = "CONCURRENT_MODIFICATION";

  /** PostgreSQL SQLSTATEs that mean "lost a race", not "bad data". */
  private static final String LOCK_NOT_AVAILABLE = "55P03";
  private static final String DEADLOCK_DETECTED = "40P01";
  private static final String SERIALIZATION_FAILURE = "40001";
  private static final String UNIQUE_VIOLATION = "23505";

  private RoleWriteConflicts() {
    // static utility
  }

  /**
   * Tells whether {@code error} means "another request changed this user's roles at the same
   * time" rather than bad data or a crash, by walking its whole cause chain.
   *
   * @param error the failure raised by a role-composition write; {@code null} is not a race
   * @return {@code true} when {@code error} (or any cause in its chain) is one of the failures an
   *     overlapping write on the same user produces
   */
  public static boolean isConcurrencyFailure(Throwable error) {
    for (Throwable t = error; t != null; t = t.getCause() == t ? null : t.getCause()) {
      if (t instanceof StaleStateException || t instanceof OptimisticLockException
          || t instanceof PessimisticLockException || t instanceof EntityNotFoundException
          || t instanceof LockAcquisitionException || t instanceof ConstraintViolationException) {
        return true;
      }
      if (t instanceof SQLException && isRaceSqlState(((SQLException) t).getSQLState())) {
        return true;
      }
    }
    return false;
  }

  private static boolean isRaceSqlState(String sqlState) {
    return LOCK_NOT_AVAILABLE.equals(sqlState) || DEADLOCK_DETECTED.equals(sqlState)
        || SERIALIZATION_FAILURE.equals(sqlState) || UNIQUE_VIOLATION.equals(sqlState);
  }
}
