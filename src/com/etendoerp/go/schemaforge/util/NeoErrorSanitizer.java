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

  /** Postgres SQLState for a not-null constraint violation. */
  private static final String SQLSTATE_NOT_NULL_VIOLATION = "23502";

  /**
   * Names the column of a Postgres not-null violation, e.g.
   * {@code null value in column "c_bpartner_location_id" of relation "c_invoice" violates
   * not-null constraint}.
   *
   * <p>Matches the English server wording only, which is why {@link #isNotNullViolationMessage}
   * also accepts a bare {@code 23502} anywhere in the string: the SQLState is the
   * locale-independent signal, and the column name is a best-effort refinement on top of it. An
   * install running Postgres under a non-English {@code lc_messages} therefore still gets the
   * right status and a stripped message — it just loses the field name.</p>
   */
  private static final java.util.regex.Pattern NOT_NULL_COLUMN_PATTERN =
      java.util.regex.Pattern.compile(
          "null value in column\\s+[\"«']?([A-Za-z0-9_]+)[\"»']?", java.util.regex.Pattern.CASE_INSENSITIVE);

  /**
   * A parenthesised run long enough that it can only be a data dump, not prose.
   *
   * <p>The concrete leak this exists for: a not-null violation on {@code c_invoice} came back with
   * Postgres' {@code Failing row contains (…)} detail carrying **~90 columns** of the failing row —
   * an internals leak, and a sizeable context cost for an MCP agent that has to read it (ETP-4793 /
   * IMP-17, from IMP-23 §9.4). Cutting on the tuple rather than on the {@code Failing row contains}
   * lead-in is deliberate: that lead-in is localised by Postgres, the oversized tuple is not.</p>
   */
  private static final int MAX_PARENTHESISED_RUN = 200;

  private static final java.util.regex.Pattern LONG_TUPLE_PATTERN =
      java.util.regex.Pattern.compile("\\([^()]{" + MAX_PARENTHESISED_RUN + ",}\\)");

  /** Replaces a stripped row dump, so the response says a value was removed rather than hiding it. */
  static final String REDACTED_ROW = "(…)";

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
    return t == null ? GENERIC_DB_ERROR : stripRowDump(redactObjectReferences(t.getMessage()));
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

  /**
   * Returns {@code true} if {@code t} or any exception in its cause chain is a not-null constraint
   * violation (Postgres SQLState 23502). Mirrors {@link #isDuplicateKeyViolation(Throwable)} and
   * exists for the same reason: a caller who omitted a required value sent a bad request, so the
   * response must be a 4xx the agent can act on, never a 500 (ETP-4793 / IMP-17).
   *
   * @param t the throwable to inspect; may be {@code null}
   * @return whether the chain contains a not-null constraint violation
   */
  public static boolean isNotNullViolation(Throwable t) {
    Throwable current = t;
    while (current != null) {
      if (current instanceof java.sql.SQLException
          && SQLSTATE_NOT_NULL_VIOLATION.equals(((java.sql.SQLException) current).getSQLState())) {
        return true;
      }
      current = current.getCause();
    }
    return false;
  }

  /**
   * Returns {@code true} if {@code message} describes a not-null constraint violation.
   *
   * <p>The message-based counterpart to {@link #isNotNullViolation(Throwable)}, needed for the same
   * reason {@link #isDuplicateKeyMessage} is: {@code DefaultJsonDataService} catches the constraint
   * violation internally and returns it as an ordinary JSON RPC failure body, so there is no
   * {@link Throwable} left to inspect by the time {@code NeoCrudHandler} classifies the status.</p>
   *
   * @param message the (possibly already-translated) error message; may be {@code null}
   * @return whether the message describes a not-null constraint violation
   */
  public static boolean isNotNullViolationMessage(String message) {
    if (message == null) {
      return false;
    }
    return message.contains(SQLSTATE_NOT_NULL_VIOLATION)
        || NOT_NULL_COLUMN_PATTERN.matcher(message).find();
  }

  /**
   * Extracts the DB column named by a not-null violation message, e.g. {@code c_bpartner_location_id}.
   *
   * @param message the error message; may be {@code null}
   * @return the lower-cased column name, or {@code null} when the message names none
   */
  public static String notNullViolationColumn(String message) {
    if (message == null) {
      return null;
    }
    java.util.regex.Matcher matcher = NOT_NULL_COLUMN_PATTERN.matcher(message);
    return matcher.find() ? matcher.group(1).toLowerCase() : null;
  }

  /**
   * Extracts the not-null violation's column from anywhere in a throwable's cause chain.
   *
   * <p>Needed separately from {@link #notNullViolationColumn(String)} because {@link #sanitize} maps
   * any DB exception to a generic message: by the time the caller has a safe string to return, the
   * column name is gone. The raw chain is the only place it survives.</p>
   *
   * @param t the throwable to inspect; may be {@code null}
   * @return the lower-cased column name, or {@code null} when no message in the chain names one
   */
  public static String notNullViolationColumn(Throwable t) {
    Throwable current = t;
    while (current != null) {
      String column = notNullViolationColumn(current.getMessage());
      if (column != null) {
        return column;
      }
      current = current.getCause();
    }
    return null;
  }

  /**
   * Replaces every parenthesised run of at least {@value #MAX_PARENTHESISED_RUN} characters with
   * {@link #REDACTED_ROW}, so a Postgres {@code Failing row contains (…)} detail cannot carry the
   * whole failing row into an HTTP response (ETP-4793 / IMP-17).
   *
   * <p>Keyed on the shape of the leak rather than on the sentence that introduces it: the lead-in is
   * localised by the server's {@code lc_messages}, an oversized tuple is not. No human-readable
   * message has a 200-character parenthetical, so ordinary text passes through untouched.</p>
   *
   * @param message the error message to strip; may be {@code null}
   * @return the message with any row dump replaced, or {@code null} if input was null
   */
  public static String stripRowDump(String message) {
    if (message == null) {
      return null;
    }
    return LONG_TUPLE_PATTERN.matcher(message).replaceAll(REDACTED_ROW);
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
