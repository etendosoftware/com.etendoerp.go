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
 * All portions are Copyright © 2021–2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.schemaforge.util;

/**
 * Sanitizes exception messages before they are sent in HTTP responses.
 *
 * DB and JDBC exceptions can contain internal infrastructure details
 * (hostnames, driver versions, SQL state codes). Those are replaced with a
 * generic message so they never reach the client.
 */
public final class NeoErrorSanitizer {

  static final String GENERIC_DB_ERROR = "Service temporarily unavailable";

  private NeoErrorSanitizer() {
  }

  /**
   * Returns a safe message for the given throwable.
   * If the throwable or any exception in its cause chain is a DB/JDBC/Hibernate
   * exception, returns a generic message. Otherwise returns {@code t.getMessage()}.
   */
  public static String sanitize(Throwable t) {
    if (t == null) {
      return GENERIC_DB_ERROR;
    }
    Throwable current = t;
    while (current != null) {
      if (isDbException(current)) {
        return GENERIC_DB_ERROR;
      }
      current = current.getCause();
    }
    return t.getMessage();
  }

  private static boolean isDbException(Throwable t) {
    String name = t.getClass().getName();
    return name.contains("SQLException")
        || name.contains("PSQLException")
        || name.contains("HibernateException")
        || name.contains("JDBCException")
        || name.contains("PersistenceException")
        || name.contains("DataAccessException")
        || name.contains("TransactionException");
  }
}
