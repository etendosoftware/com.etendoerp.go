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

  /**
   * Returned instead of {@link #GENERIC_DB_ERROR} when the cause chain contains a
   * unique-constraint violation (Postgres SQLState 23505) — e.g. re-importing a row
   * whose business key already exists. This is a legitimate, actionable data conflict,
   * not an infra failure, so it gets its own distinct, business-friendly message rather
   * than being lumped in with the generic DB-error fallback.
   *
   * <p>Wording deliberately mirrors Etendo's own native uniqueness-violation message
   * ("... must be unique.", e.g. "There is already a Business Partner with the same
   * (Client, Organization, Search Key). (Client, Organization, Search Key) must be
   * unique.") — the import UI's {@code isDuplicateKeyError()}
   * (schema_forge_core's importEngine.js) classifies a row as a graceful "already
   * exists" skip, not a hard failure, purely by matching {@code /must be unique/i}
   * against the message text. This is the fallback path (raw JDBC exceptions that
   * bypass Etendo's own translation, e.g. BusinessPartnerHandler's updateSearchKey())
   * — it must satisfy that same regex or the import UI would misclassify it as a
   * genuine failure instead of a skippable duplicate.</p>
   */
  static final String DUPLICATE_KEY_ERROR = "A record with this value already exists. This value must be unique.";

  private static final String SQLSTATE_UNIQUE_VIOLATION = "23505";

  /**
   * Matches a default Java {@code Object.toString()} rendering
   * ({@code fully.qualified.ClassName@hexHash}, inner classes included via {@code $}), e.g.
   * {@code com.etendoerp.redis.interfaces.CachedSet@55b0cf12}. Requires at least a two-segment
   * package before the class name and a hex suffix after {@code @}, so ordinary text (emails,
   * dotted version strings) is not matched.
   *
   * <p>The package-segment group is capped at {@link #MAX_PACKAGE_SEGMENTS} repetitions
   * (SonarQube java:S5998): Java's regex engine matches group repetition recursively, so an
   * unbounded {@code {2,}} here would let an attacker-controlled message (e.g. one reflected
   * into an exception) with thousands of {@code .}-separated tokens exhaust the stack. No real
   * fully-qualified Java class name comes close to the cap.</p>
   */
  private static final int MAX_PACKAGE_SEGMENTS = 20;

  private static final java.util.regex.Pattern OBJECT_TOSTRING_PATTERN =
      java.util.regex.Pattern.compile(
          "(?:[A-Za-z_$][A-Za-z0-9_$]*\\.){2," + MAX_PACKAGE_SEGMENTS
              + "}[A-Za-z_$][A-Za-z0-9_$]*@[0-9a-fA-F]+");

  static final String REDACTED_OBJECT = "[value]";

  private NeoErrorSanitizer() {
  }

  /**
   * Returns a safe message for the given throwable.
   * If the throwable or any exception in its cause chain is a unique-constraint
   * violation, returns {@link #DUPLICATE_KEY_ERROR}. Otherwise, if it is any other
   * DB/JDBC/Hibernate exception, returns a generic message. Otherwise returns
   * {@code t.getMessage()}.
   *
   * @param t the throwable to inspect; may be {@code null}
   * @return a sanitized error message safe to expose in HTTP responses
   */
  public static String sanitize(Throwable t) {
    if (isDuplicateKeyViolation(t)) {
      return DUPLICATE_KEY_ERROR;
    }
    Throwable current = t;
    while (current != null) {
      if (isDbException(current)) {
        return GENERIC_DB_ERROR;
      }
      current = current.getCause();
    }
    return t == null ? GENERIC_DB_ERROR : redactObjectReferences(t.getMessage());
  }

  /**
   * Replaces every default Java {@code toString()} reference
   * ({@code fully.qualified.ClassName@hexHash}) in {@code message} with {@link #REDACTED_OBJECT},
   * so an internal object identity can never leak into an HTTP response body verbatim.
   *
   * <p>Openbravo/Hibernate validators occasionally interpolate a raw collection object into a
   * message — e.g. a List-reference failure renders as {@code "... should be one of the following
   * values: com.etendoerp.redis.interfaces.CachedSet@55b0cf12 but it is value 0"}. This strips the
   * leaked token while leaving the rest of the (already-translated) message intact and readable.
   * ETP-4668.</p>
   *
   * @param message the error message to redact; may be {@code null}
   * @return the message with any object-identity token replaced, or {@code null} if input was null
   */
  public static String redactObjectReferences(String message) {
    if (message == null) {
      return null;
    }
    return OBJECT_TOSTRING_PATTERN.matcher(message).replaceAll(REDACTED_OBJECT);
  }

  /**
   * Returns {@code true} if {@code t} or any exception in its cause chain is a
   * unique-constraint violation (Postgres SQLState 23505). Exposed so callers that
   * build the HTTP response (e.g. {@code NeoCrudHandler}) can also pick the status
   * code — a duplicate-key conflict is a 409, not a 500, even though it arrives as
   * an unchecked exception like any other DB failure.
   *
   * @param t the throwable to inspect; may be {@code null}
   * @return whether the chain contains a unique-constraint violation
   */
  public static boolean isDuplicateKeyViolation(Throwable t) {
    Throwable current = t;
    while (current != null) {
      if (current instanceof java.sql.SQLException
          && SQLSTATE_UNIQUE_VIOLATION.equals(((java.sql.SQLException) current).getSQLState())) {
        return true;
      }
      current = current.getCause();
    }
    return false;
  }

  /**
   * Returns {@code true} if {@code message} is a duplicate-key/uniqueness-violation
   * message. Unlike {@link #isDuplicateKeyViolation(Throwable)} (which inspects a raw
   * JDBC exception's SQLState), this checks an already-built, already-translated
   * message string — the case where Etendo's own {@code DefaultJsonDataService} caught
   * the underlying constraint violation internally and returned it as a normal JSON
   * RPC response (e.g. {@code {"response":{"status":-1,"error":{"message":"..."}}}})
   * rather than throwing, so there is no {@link Throwable} to inspect at all. Etendo's
   * native message for this case already contains "must be unique" (e.g. "There is
   * already a Business Partner with the same (Client, Organization, Search Key). ...
   * must be unique."), the same phrase {@link #DUPLICATE_KEY_ERROR} deliberately
   * mirrors — so one check covers both the thrown-exception and the
   * normal-JSON-response paths, and both end up classified the same way by the import
   * UI's own {@code isDuplicateKeyError()}.
   *
   * @param message the (possibly already-translated) error message; may be {@code null}
   * @return whether the message describes a duplicate-key/uniqueness conflict
   */
  public static boolean isDuplicateKeyMessage(String message) {
    return message != null && message.toLowerCase().contains("must be unique");
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
