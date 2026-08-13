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

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;

/**
 * Typed, validated representation of the allowlisted {@code recipientEdits} command field for the
 * document-send contract family. Carries per-channel additions and removals applied on top of the
 * trusted base recipient set.
 */
public final class EmailRecipientEdits {

  private static final String KEY_ADD = "add";
  private static final String KEY_REMOVE = "remove";
  private static final Set<String> ALLOWED_CHANNELS = Collections.unmodifiableSet(
      new LinkedHashSet<>(java.util.Arrays.asList(EmailRecipientSet.CHANNEL_TO,
          EmailRecipientSet.CHANNEL_CC)));

  private final List<String> toAdd;
  private final List<String> toRemove;
  private final List<String> ccAdd;

  private EmailRecipientEdits(List<String> toAdd, List<String> toRemove, List<String> ccAdd) {
    this.toAdd = Collections.unmodifiableList(toAdd);
    this.toRemove = Collections.unmodifiableList(toRemove);
    this.ccAdd = Collections.unmodifiableList(ccAdd);
  }

  /**
   * Parses and validates {@code recipientEdits} from a command body.
   *
   * @param body command body, may be {@code null}
   * @return parsed edits, or {@link Optional#empty()} when the field is absent
   * @throws InvalidRecipientEditsException when the field is malformed, has an unknown channel, or
   *         carries a blank or syntactically invalid email
   */
  public static Optional<EmailRecipientEdits> fromBody(JSONObject body)
      throws InvalidRecipientEditsException {
    if (body == null || !body.has(EmailContractCommandSupport.FIELD_RECIPIENT_EDITS)) {
      return Optional.empty();
    }
    Object raw = body.opt(EmailContractCommandSupport.FIELD_RECIPIENT_EDITS);
    if (!(raw instanceof JSONObject)) {
      throw new InvalidRecipientEditsException("recipientEdits must be an object");
    }
    JSONObject edits = (JSONObject) raw;
    for (java.util.Iterator<?> keys = edits.keys(); keys.hasNext();) {
      String channel = String.valueOf(keys.next());
      if (!ALLOWED_CHANNELS.contains(channel)) {
        throw new InvalidRecipientEditsException("Unknown recipient channel: " + channel);
      }
    }
    JSONObject toChannel = channelObject(edits, EmailRecipientSet.CHANNEL_TO);
    JSONObject ccChannel = channelObject(edits, EmailRecipientSet.CHANNEL_CC);
    List<String> toAdd = parseList(toChannel, KEY_ADD);
    List<String> toRemove = parseList(toChannel, KEY_REMOVE);
    List<String> ccAdd = parseList(ccChannel, KEY_ADD);
    if (ccChannel != null && ccChannel.has(KEY_REMOVE)) {
      throw new InvalidRecipientEditsException("cc channel does not support removals");
    }
    return Optional.of(new EmailRecipientEdits(toAdd, toRemove, ccAdd));
  }

  /**
   * Returns the validated, normalized to-channel additions.
   *
   * @return immutable to-add list
   */
  public List<String> getToAdd() {
    return toAdd;
  }

  /**
   * Returns the validated, normalized to-channel removals.
   *
   * @return immutable to-remove list
   */
  public List<String> getToRemove() {
    return toRemove;
  }

  /**
   * Returns the validated, normalized cc-channel additions.
   *
   * @return immutable cc-add list
   */
  public List<String> getCcAdd() {
    return ccAdd;
  }

  /**
   * Applies these edits to a trusted base to-recipient list following proposal section 4 steps
   * 4-6: removes against the base by comparable key, then adds to and cc, then defers
   * cross-channel dedup to {@link EmailRecipientSet#of(List, List)}.
   *
   * @param baseTo trusted base to-recipients
   * @return resulting normalized, cross-channel deduplicated recipient set
   */
  public EmailRecipientSet applyTo(List<String> baseTo) {
    Set<String> removeKeys = comparableKeys(toRemove);
    List<String> resultingTo = new ArrayList<>();
    if (baseTo != null) {
      for (String address : baseTo) {
        String normalized = EmailRecipientSet.normalizeAddress(address);
        if (normalized != null && !removeKeys.contains(normalized.toLowerCase(Locale.ROOT))) {
          resultingTo.add(normalized);
        }
      }
    }
    resultingTo.addAll(toAdd);
    return EmailRecipientSet.of(resultingTo, ccAdd);
  }

  private static JSONObject channelObject(JSONObject edits, String channel)
      throws InvalidRecipientEditsException {
    if (!edits.has(channel)) {
      return null;
    }
    Object raw = edits.opt(channel);
    if (!(raw instanceof JSONObject)) {
      throw new InvalidRecipientEditsException("recipientEdits." + channel + " must be an object");
    }
    return (JSONObject) raw;
  }

  private static List<String> parseList(JSONObject channel, String key)
      throws InvalidRecipientEditsException {
    if (channel == null || !channel.has(key)) {
      return Collections.emptyList();
    }
    Object raw = channel.opt(key);
    if (!(raw instanceof JSONArray)) {
      throw new InvalidRecipientEditsException("recipientEdits list must be an array");
    }
    JSONArray array = (JSONArray) raw;
    Set<String> seen = new LinkedHashSet<>();
    List<String> result = new ArrayList<>();
    for (int i = 0; i < array.length(); i++) {
      Object value = array.opt(i);
      String normalized = EmailRecipientSet.normalizeAddress(value == null ? null
          : String.valueOf(value));
      if (normalized == null) {
        throw new InvalidRecipientEditsException("recipientEdits address cannot be empty");
      }
      if (!EmailContractCommandSupport.isValidEmail(normalized)) {
        throw new InvalidRecipientEditsException("recipientEdits contains an invalid email");
      }
      if (seen.add(normalized.toLowerCase(Locale.ROOT))) {
        result.add(normalized);
      }
    }
    return result;
  }

  private static Set<String> comparableKeys(List<String> addresses) {
    Set<String> keys = new LinkedHashSet<>();
    for (String address : addresses) {
      keys.add(address.toLowerCase(Locale.ROOT));
    }
    return keys;
  }

  /**
   * Checked exception raised when {@code recipientEdits} is malformed or carries invalid data.
   * Carries a client-safe message used to build a {@code VALIDATION_FAILED} rejection.
   */
  public static final class InvalidRecipientEditsException extends Exception {
    private static final long serialVersionUID = 1L;

    /**
     * Creates an exception with a client-safe message.
     *
     * @param message client-safe rejection message
     */
    public InvalidRecipientEditsException(String message) {
      super(message);
    }
  }
}
