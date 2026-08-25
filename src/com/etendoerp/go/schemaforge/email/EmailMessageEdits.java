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

package com.etendoerp.go.schemaforge.email;

import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;

import com.etendoerp.go.schemaforge.email.render.EmailEscape;
import org.codehaus.jettison.json.JSONObject;

/**
 * Typed, validated representation of the allowlisted {@code messageEdits} command field for the
 * document-send contract family (ETP-4717). Carries the operator-authored subject and message that
 * override the contract-composed defaults.
 * <p>
 * This is the only channel through which a browser may influence email copy. Everything else —
 * template, recipient, provider metadata — stays server-resolved. Because the provider's content
 * template renders {@code body} as HTML, the operator's message is escaped here and never passed
 * through verbatim.
 */
public final class EmailMessageEdits {

  private static final String KEY_SUBJECT = "subject";
  private static final String KEY_MESSAGE = "message";
  private static final Set<String> ALLOWED_KEYS = Collections.unmodifiableSet(
      new LinkedHashSet<>(Arrays.asList(KEY_SUBJECT, KEY_MESSAGE)));

  static final int MAX_SUBJECT_LENGTH = 200;
  static final int MAX_MESSAGE_LENGTH = 5000;

  private final String subject;
  private final String message;

  private EmailMessageEdits(String subject, String message) {
    this.subject = subject;
    this.message = message;
  }

  /**
   * Parses and validates {@code messageEdits} from a command body.
   *
   * @param body command body, may be {@code null}
   * @return parsed edits, or {@link Optional#empty()} when the field is absent
   * @throws InvalidMessageEditsException when the field is malformed, carries an unknown key,
   *         exceeds a length cap, or has neither a subject nor a message
   */
  public static Optional<EmailMessageEdits> fromBody(JSONObject body)
      throws InvalidMessageEditsException {
    if (body == null || !body.has(EmailContractCommandSupport.FIELD_MESSAGE_EDITS)) {
      return Optional.empty();
    }
    Object raw = body.opt(EmailContractCommandSupport.FIELD_MESSAGE_EDITS);
    if (!(raw instanceof JSONObject)) {
      throw new InvalidMessageEditsException("messageEdits must be an object");
    }
    JSONObject edits = (JSONObject) raw;
    for (Iterator<?> keys = edits.keys(); keys.hasNext();) {
      String key = String.valueOf(keys.next());
      if (!ALLOWED_KEYS.contains(key)) {
        throw new InvalidMessageEditsException("Unknown messageEdits field: " + key);
      }
    }
    String subject = sanitizeSubject(StringUtils.trimToNull(edits.optString(KEY_SUBJECT)));
    String message = StringUtils.trimToNull(edits.optString(KEY_MESSAGE));
    if (subject == null && message == null) {
      throw new InvalidMessageEditsException("messageEdits must carry a subject or a message");
    }
    if (subject != null && subject.length() > MAX_SUBJECT_LENGTH) {
      throw new InvalidMessageEditsException(
          "Subject exceeds the maximum of " + MAX_SUBJECT_LENGTH + " characters");
    }
    if (message != null && message.length() > MAX_MESSAGE_LENGTH) {
      throw new InvalidMessageEditsException(
          "Message exceeds the maximum of " + MAX_MESSAGE_LENGTH + " characters");
    }
    return Optional.of(new EmailMessageEdits(subject, message));
  }

  /**
   * Returns the operator-authored subject, header-injection characters already removed.
   *
   * @return subject override, or {@code null} when the operator did not set one
   */
  public String getSubject() {
    return subject;
  }

  /**
   * Returns the operator-authored message as raw text, before HTML escaping.
   *
   * @return message override, or {@code null} when the operator did not type one
   */
  public String getMessage() {
    return message;
  }

  /**
   * Renders the operator message as an HTML fragment safe to hand to the provider's content
   * template: every markup character is escaped and line breaks become {@code <br>}.
   *
   * @return escaped HTML body, or {@code null} when there is no message override
   */
  public String toHtmlBody() {
    if (message == null) {
      return null;
    }
    return escapeHtml(message).replace("\r\n", "\n").replace("\r", "\n").replace("\n", "<br>");
  }

  /**
   * Stable hash of the operator-authored content, used to keep the send idempotency key distinct
   * per message. Without it, correcting the text and re-sending to the same recipients collides
   * with the previous send and is answered as a duplicate.
   *
   * @return hex SHA-256 of the subject and message pair
   */
  public String contentHash() {
    return EmailRecipientSet.sha256(StringUtils.defaultString(subject) + "\0"
        + StringUtils.defaultString(message));
  }

  /**
   * Strips CR and LF from a subject. A raw newline in a subject is an email header-injection
   * vector, so it is removed before the value ever reaches the provider.
   */
  private static String sanitizeSubject(String value) {
    if (value == null) {
      return null;
    }
    return StringUtils.trimToNull(value.replace("\r", " ").replace("\n", " "));
  }

  private static String escapeHtml(String value) {
    return EmailEscape.escapeHtml(value);
  }

  /**
   * Signals a malformed or disallowed {@code messageEdits} payload with a client-safe message.
   */
  public static final class InvalidMessageEditsException extends Exception {
    private static final long serialVersionUID = 1L;

    /**
     * Creates an exception with a client-safe message.
     *
     * @param message client-safe rejection message
     */
    public InvalidMessageEditsException(String message) {
      super(message);
    }
  }
}
