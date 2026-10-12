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
package com.etendoerp.go.schemaforge;

import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

import org.codehaus.jettison.json.JSONObject;

/**
 * The optional caller choices of one follow-up request (ETP-5576): the top-level members of the
 * action POST body, as strings. Shared and identity-free — it does not know which keys exist; the
 * {@link TargetCreator} that needs a choice reads its own key and validates it (rejecting a bad
 * value with {@link FollowUpException.Reason#INVALID_INPUT}).
 *
 * <p>A creator that needs a choice it cannot make on its own asks for it by throwing a
 * {@link FollowUpException} carrying a {@link FollowUpException.RequiredInput}; the client
 * retries the same POST with {@code {"<key>": "<chosen id>"}}.
 *
 * <p>Normalization: a member whose value is {@code null} or blank is treated as absent, so
 * {@code {"warehouseId": ""}} means "no choice". Any other value is kept as its string form
 * (trimmed); a non-string value therefore reaches the creator as text and fails its validation.
 */
final class FollowUpInputs {

  private static final FollowUpInputs NONE = new FollowUpInputs(Collections.emptyMap());

  private final Map<String, String> values;

  private FollowUpInputs(Map<String, String> values) {
    this.values = values;
  }

  /** No caller choice (an empty or absent body). */
  static FollowUpInputs none() {
    return NONE;
  }

  /**
   * The caller choices carried by a request body.
   *
   * @param body the parsed POST body, may be {@code null}
   * @return the non-blank top-level members as strings; {@link #none()} when there is none
   */
  static FollowUpInputs fromRequestBody(JSONObject body) {
    if (body == null || body.length() == 0) {
      return NONE;
    }
    Map<String, String> collected = new LinkedHashMap<>();
    Iterator<?> keys = body.keys();
    while (keys.hasNext()) {
      String key = String.valueOf(keys.next());
      Object value = body.opt(key);
      if (value == null || JSONObject.NULL.equals(value)) {
        continue;
      }
      String text = String.valueOf(value).trim();
      if (!text.isEmpty()) {
        collected.put(key, text);
      }
    }
    return collected.isEmpty() ? NONE
        : new FollowUpInputs(Collections.unmodifiableMap(collected));
  }

  /**
   * @param key the input key, e.g. {@code warehouseId}
   * @return the caller's value for {@code key}, or {@code null} when not supplied
   */
  String get(String key) {
    return values.get(key);
  }

  /** {@code true} when the caller supplied no choice at all. */
  boolean isEmpty() {
    return values.isEmpty();
  }
}
