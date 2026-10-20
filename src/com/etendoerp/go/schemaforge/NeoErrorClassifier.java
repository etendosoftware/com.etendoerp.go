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

package com.etendoerp.go.schemaforge;

import java.sql.SQLException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import javax.servlet.http.HttpServletResponse;

import org.hibernate.PropertyValueException;
import org.hibernate.TransientObjectException;
import org.openbravo.base.exception.OBException;
import org.openbravo.base.exception.OBSecurityException;

import com.etendoerp.go.schemaforge.util.NeoErrorSanitizer;
import com.etendoerp.go.schemaforge.util.NeoMessageTranslator;

/**
 * Decides which HTTP answer an exception deserves, from the exception alone (ETP-5782).
 *
 * <p>The decision is <b>structural</b>: the exception type, the PostgreSQL SQLState of the first
 * {@link SQLException} in the cause chain, whether the exception carries an AD message key, and the
 * HTTP verb. It never looks at an entity, table or spec name, and it never classifies by the text
 * of a message — text is only ever displayed, after the class is decided. That is what lets every
 * error funnel of every channel (REST single, REST batch, MCP) share it.
 *
 * <p>The classes, in the order they are tested:
 * <ol>
 *   <li>{@code forbidden} 403 — an {@link OBSecurityException} or {@link SecurityException};</li>
 *   <li>by SQLState: {@code P0001} (a PL/pgSQL {@code RAISE}, i.e. every trigger or DB function
 *       refusal, including {@code RAISE_APPLICATION_ERROR}) → {@code business_rule} 422;
 *       {@code 23505} → {@code duplicate_key} 409; {@code 23502} → {@code missing_required_value}
 *       400; {@code 23503} → {@code record_in_use} 409 on DELETE, {@code unknown_reference} 400
 *       otherwise; {@code 23514} and class {@code 22} → {@code invalid_value} 422;
 *       {@code 40P01}/{@code 40001} → {@code retryable_conflict} 409; {@code 57014}, class
 *       {@code 08}, class {@code 53} and {@code 57P0x} → {@code service_unavailable} 503;</li>
 *   <li>Hibernate: {@link TransientObjectException} (a reference to a row that does not exist) →
 *       {@code unknown_reference} 400; {@link PropertyValueException} → {@code
 *       missing_required_value} 400;</li>
 *   <li>an exception that carries an AD message key — a {@link MessageKeyedException}, or an
 *       {@link OBException} whose message holds {@code @Key@} tokens and that wraps no foreign
 *       cause → {@code business_rule} 422;</li>
 *   <li>anything else → {@code server_error} 500. A plain {@link OBException} without a key is
 *       here on purpose: nothing in it says it is a business refusal rather than a fault.</li>
 * </ol>
 */
public final class NeoErrorClassifier {

  public static final String CODE_BUSINESS_RULE = "business_rule";
  public static final String CODE_DUPLICATE_KEY = "duplicate_key";
  public static final String CODE_MISSING_REQUIRED_VALUE = "missing_required_value";
  public static final String CODE_UNKNOWN_REFERENCE = "unknown_reference";
  public static final String CODE_RECORD_IN_USE = "record_in_use";
  public static final String CODE_INVALID_VALUE = "invalid_value";
  public static final String CODE_FORBIDDEN = "forbidden";
  public static final String CODE_RETRYABLE_CONFLICT = "retryable_conflict";
  public static final String CODE_SERVICE_UNAVAILABLE = "service_unavailable";
  public static final String CODE_SERVER_ERROR = "server_error";

  /**
   * The one answer for a reference that resolves to no row the caller may use. Deliberately names
   * nothing: not the id, not the entity, and not whether the row exists for another tenant.
   */
  public static final String MSG_UNKNOWN_REFERENCE =
      "A referenced record does not exist or is not accessible.";
  static final String MSG_RECORD_IN_USE =
      "This record cannot be deleted because other records use it.";
  static final String MSG_INVALID_VALUE = "A value sent is not valid.";
  static final String MSG_MISSING_REQUIRED_VALUE = "A required value is missing.";
  static final String MSG_FORBIDDEN = "This action is not allowed for the current role.";
  static final String MSG_RETRYABLE_CONFLICT =
      "The record is being modified by another operation. Retry the request.";

  /** The AD message core itself answers with for a refused DAL access. */
  static final String ACTION_NOT_ALLOWED_KEY = "OBUIAPP_ActionNotAllowed";

  static final int STATUS_UNPROCESSABLE = 422;

  private static final String SQLSTATE_RAISE = "P0001";
  private static final String SQLSTATE_UNIQUE = "23505";
  private static final String SQLSTATE_NOT_NULL = "23502";
  private static final String SQLSTATE_FOREIGN_KEY = "23503";
  private static final String SQLSTATE_CHECK = "23514";
  private static final String SQLSTATE_DEADLOCK = "40P01";
  private static final String SQLSTATE_SERIALIZATION = "40001";
  private static final String SQLSTATE_QUERY_CANCELED = "57014";

  /** PostgreSQL's severity prefix, localised but always upper case: {@code ERROR: }, {@code FEHLER: }. */
  private static final java.util.regex.Pattern SEVERITY_PREFIX =
      java.util.regex.Pattern.compile("^\\p{Lu}+:\\s+");

  /** Bounds the cause-chain walk: a cyclic or absurdly deep chain must not hang an error path. */
  private static final int MAX_CHAIN = 50;

  private NeoErrorClassifier() {
  }

  /**
   * The outcome of a classification. {@code rawMessage} is the text the class is displayed from —
   * never shown as is: {@link #displayMessage(Classification)} translates and sanitizes it.
   *
   * @param status      the HTTP status
   * @param code        the machine-readable {@code error.code}
   * @param rawMessage  the untranslated source text for the display message; may be {@code null}
   * @param messageKeys the AD message keys the exception carried, in order; never {@code null}
   */
  public record Classification(int status, String code, String rawMessage,
      List<String> messageKeys) {

    /** @return whether this is a 5xx answer (logged as ERROR, never a caller's mistake) */
    public boolean isServerSide() {
      return status >= HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
    }
  }

  /**
   * Classifies a throwable.
   *
   * @param t          the throwable, possibly wrapped; may be {@code null}
   * @param httpMethod the verb of the request that failed; only DELETE changes an answer (a
   *                   foreign-key violation on DELETE means "still in use")
   * @return the classification, never {@code null}
   */
  public static Classification classify(Throwable t, String httpMethod) {
    if (t == null) {
      return serverError(null);
    }
    List<Throwable> chain = chainOf(t);
    if (chain.stream().anyMatch(NeoErrorClassifier::isSecurityRefusal)) {
      return of(HttpServletResponse.SC_FORBIDDEN, CODE_FORBIDDEN, null);
    }
    SQLException sql = firstSqlExceptionWithState(chain);
    if (sql != null) {
      Classification bySqlState = classifySqlState(sql, "DELETE".equalsIgnoreCase(httpMethod));
      if (bySqlState != null) {
        return bySqlState;
      }
      // A SQL failure with an unrecognised state is infrastructure, whatever wraps it.
      return serverError(t);
    }
    for (Throwable current : chain) {
      if (current instanceof TransientObjectException) {
        return of(HttpServletResponse.SC_BAD_REQUEST, CODE_UNKNOWN_REFERENCE, null);
      }
      if (current instanceof PropertyValueException) {
        return of(HttpServletResponse.SC_BAD_REQUEST, CODE_MISSING_REQUIRED_VALUE, null);
      }
    }
    Classification keyed = classifyMessageKeyed(chain);
    return keyed != null ? keyed : serverError(t);
  }

  /**
   * The text shown to the caller for a classification: translated in the request language when the
   * class carries a source text, a fixed generic sentence when it must not reveal anything, and in
   * every case stripped of row dumps and object identities.
   *
   * <p>Must run on a usable session: translation reads {@code AD_Message}. Callers that roll back
   * do so first (see {@link NeoErrorResponses}).
   *
   * @param c the classification
   * @return the message for the response body
   */
  public static String displayMessage(Classification c) {
    String message;
    switch (c.code()) {
      case CODE_BUSINESS_RULE:
        message = translatedOr(c.rawMessage(), c.rawMessage());
        break;
      case CODE_INVALID_VALUE:
        message = translatedOrGeneric(c.rawMessage(), MSG_INVALID_VALUE);
        break;
      case CODE_FORBIDDEN:
        message = translatedOrGeneric("@" + ACTION_NOT_ALLOWED_KEY + "@", MSG_FORBIDDEN);
        break;
      case CODE_DUPLICATE_KEY:
        message = NeoErrorSanitizer.DUPLICATE_KEY_ERROR;
        break;
      case CODE_UNKNOWN_REFERENCE:
        message = MSG_UNKNOWN_REFERENCE;
        break;
      case CODE_RECORD_IN_USE:
        message = MSG_RECORD_IN_USE;
        break;
      case CODE_MISSING_REQUIRED_VALUE:
        message = MSG_MISSING_REQUIRED_VALUE;
        break;
      case CODE_RETRYABLE_CONFLICT:
        message = MSG_RETRYABLE_CONFLICT;
        break;
      default:
        message = c.rawMessage();
        break;
    }
    return NeoErrorSanitizer.stripRowDump(NeoErrorSanitizer.redactObjectReferences(message));
  }

  private static Classification classifySqlState(SQLException sql, boolean delete) {
    String state = sql.getSQLState();
    switch (state) {
      case SQLSTATE_RAISE:
        return new Classification(STATUS_UNPROCESSABLE, CODE_BUSINESS_RULE,
            primaryLine(sql.getMessage()),
            NeoMessageTranslator.extractMessageKeys(sql.getMessage()));
      case SQLSTATE_UNIQUE:
        return of(HttpServletResponse.SC_CONFLICT, CODE_DUPLICATE_KEY, null);
      case SQLSTATE_NOT_NULL:
        return of(HttpServletResponse.SC_BAD_REQUEST, CODE_MISSING_REQUIRED_VALUE, null);
      case SQLSTATE_FOREIGN_KEY:
        return delete ? of(HttpServletResponse.SC_CONFLICT, CODE_RECORD_IN_USE, null)
            : of(HttpServletResponse.SC_BAD_REQUEST, CODE_UNKNOWN_REFERENCE, null);
      case SQLSTATE_CHECK:
        return of(STATUS_UNPROCESSABLE, CODE_INVALID_VALUE, sql.getMessage());
      case SQLSTATE_DEADLOCK:
      case SQLSTATE_SERIALIZATION:
        return of(HttpServletResponse.SC_CONFLICT, CODE_RETRYABLE_CONFLICT, null);
      case SQLSTATE_QUERY_CANCELED:
        return serviceUnavailable();
      default:
        return classifySqlStateClass(state);
    }
  }

  /** The SQLState classes (first two characters) that decide on their own. */
  private static Classification classifySqlStateClass(String state) {
    String stateClass = state.length() >= 2 ? state.substring(0, 2) : state;
    if ("22".equals(stateClass)) {
      // data exception: value too long, out of range, unparseable text, bad datetime…
      return of(STATUS_UNPROCESSABLE, CODE_INVALID_VALUE, null);
    }
    if ("08".equals(stateClass) || "53".equals(stateClass) || state.startsWith("57P0")) {
      // connection lost, resources exhausted, server shutting down
      return serviceUnavailable();
    }
    return null;
  }

  /**
   * A business refusal identified by the AD message key the exception carries. Only an
   * {@link OBException}: the carrier Etendo business code uses for a refusal. One that wraps a
   * foreign cause (an NPE, a JDBC failure) is a wrapped fault, whatever its message says.
   */
  private static Classification classifyMessageKeyed(List<Throwable> chain) {
    for (Throwable current : chain) {
      if (current instanceof MessageKeyedException) {
        MessageKeyedException keyed = (MessageKeyedException) current;
        return new Classification(STATUS_UNPROCESSABLE, CODE_BUSINESS_RULE, keyed.getMessage(),
            List.of(keyed.getMessageKey()));
      }
    }
    Throwable head = chain.get(0);
    if (head instanceof OBException && chain.stream().allMatch(OBException.class::isInstance)) {
      List<String> keys = NeoMessageTranslator.extractMessageKeys(head.getMessage());
      if (!keys.isEmpty()) {
        return new Classification(STATUS_UNPROCESSABLE, CODE_BUSINESS_RULE, head.getMessage(),
            keys);
      }
    }
    return null;
  }

  private static boolean isSecurityRefusal(Throwable t) {
    return t instanceof OBSecurityException || t instanceof SecurityException;
  }

  /**
   * The cause chain, plus — for every {@link SQLException} — its {@code getNextException()} chain,
   * where a JDBC batch hides the real error (a {@code BatchUpdateException} carries only "Batch
   * entry 0 … was aborted"; the PostgreSQL error with its SQLState is the next exception).
   */
  static List<Throwable> chainOf(Throwable t) {
    Map<Throwable, Boolean> seen = new IdentityHashMap<>();
    List<Throwable> chain = new java.util.ArrayList<>();
    Throwable current = t;
    while (current != null && !seen.containsKey(current) && chain.size() < MAX_CHAIN) {
      seen.put(current, Boolean.TRUE);
      chain.add(current);
      if (current instanceof SQLException) {
        SQLException next = ((SQLException) current).getNextException();
        while (next != null && !seen.containsKey(next) && chain.size() < MAX_CHAIN) {
          seen.put(next, Boolean.TRUE);
          chain.add(next);
          next = next.getNextException();
        }
      }
      current = current.getCause();
    }
    return chain;
  }

  /**
   * The most specific SQLState in the chain. A wrapper ({@code BatchUpdateException}, a generic
   * JDBC exception) may carry a vague state of its own while the underlying PostgreSQL error carries
   * the real one, so the last state found wins over the first.
   */
  private static SQLException firstSqlExceptionWithState(List<Throwable> chain) {
    SQLException found = null;
    for (Throwable current : chain) {
      if (current instanceof SQLException && ((SQLException) current).getSQLState() != null) {
        found = (SQLException) current;
      }
    }
    return found;
  }

  private static String translatedOr(String raw, String fallback) {
    if (raw == null || raw.isBlank()) {
      return fallback;
    }
    String translated = primaryLine(NeoMessageTranslator.translateDbMessage(raw));
    return translated == null || translated.isBlank() ? fallback : translated;
  }

  /**
   * The translated text when translation actually resolved something (an {@code @Key@} token, or a
   * constraint with its own AD message); the generic text otherwise, because an untranslated
   * database message names tables, columns and constraints.
   */
  private static String translatedOrGeneric(String raw, String generic) {
    if (raw == null || raw.isBlank()) {
      return generic;
    }
    String translated = primaryLine(NeoMessageTranslator.translateDbMessage(raw));
    if (translated == null || translated.isBlank()
        || translated.equals(primaryLine(raw)) || translated.contains("@")) {
      return generic;
    }
    return translated;
  }

  /**
   * The first line of a database message, without its severity prefix: PostgreSQL appends the
   * failing PL/pgSQL function and line ({@code Where: …}) and a {@code Detail:} with row data on
   * the following lines, none of which may reach a caller.
   *
   * @param message the message; may be {@code null}
   * @return the cleaned first line, or {@code null}
   */
  static String primaryLine(String message) {
    if (message == null) {
      return null;
    }
    String line = message.strip();
    int newline = line.indexOf('\n');
    if (newline >= 0) {
      line = line.substring(0, newline).strip();
    }
    return SEVERITY_PREFIX.matcher(line).replaceFirst("");
  }

  private static Classification of(int status, String code, String rawMessage) {
    return new Classification(status, code, rawMessage, Collections.emptyList());
  }

  private static Classification serviceUnavailable() {
    return new Classification(HttpServletResponse.SC_SERVICE_UNAVAILABLE,
        CODE_SERVICE_UNAVAILABLE, NeoErrorSanitizer.sanitize(null), Collections.emptyList());
  }

  private static Classification serverError(Throwable t) {
    String message = NeoErrorSanitizer.sanitize(t);
    if ((message == null || message.isBlank()) && t != null) {
      // A bare NullPointerException has no message: its type is the only thing worth showing.
      message = t.getClass().getSimpleName();
    }
    return new Classification(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, CODE_SERVER_ERROR,
        message, Collections.emptyList());
  }
}
