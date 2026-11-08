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

import java.sql.SQLException;

import org.hibernate.JDBCException;

/**
 * ETP-5051 — tells a failure that reached the database from one raised before any SQL ran.
 *
 * <p>A failure from the database has aborted the PostgreSQL transaction it ran in: every later
 * statement of that transaction fails too. The quota write check fails closed on it, and an
 * isolated report stops reading on it. A bare {@code PersistenceException} is not enough: every
 * {@code HibernateException} is one, including a {@code QueryException} raised while parsing HQL.
 * Hibernate wraps a real database failure with its {@link JDBCException} as the cause, so the
 * chain walk finds it.
 */
public final class DatabaseFailures {

  private DatabaseFailures() {
  }

  /**
   * Whether an {@link SQLException} or a Hibernate {@link JDBCException} is anywhere in the cause
   * chain.
   *
   * @param error the failure; may be null
   * @return {@code true} when the failure came from the database
   */
  public static boolean isDatabaseFailure(Throwable error) {
    for (Throwable cause = error; cause != null; cause = nextCause(cause)) {
      if (cause instanceof SQLException || cause instanceof JDBCException) {
        return true;
      }
    }
    return false;
  }

  private static Throwable nextCause(Throwable cause) {
    return cause.getCause() == cause ? null : cause.getCause();
  }
}
