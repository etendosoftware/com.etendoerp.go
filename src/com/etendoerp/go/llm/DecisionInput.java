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

package com.etendoerp.go.llm;

import java.util.ArrayList;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;

/**
 * The evidence every question of a request is evaluated against: text, JSON and images, in the
 * order added.
 *
 * <p>Sent as <b>one user message of {@code input_text} and {@code input_image} parts</b>, the
 * only shape the gateway (LiteLLM 1.106) validates: it rejects {@code developer}/{@code system}
 * messages and {@code input_json} parts, which OpenAI's own API accepts. Hence:</p>
 * <ul>
 * <li>{@link #guidance(String)} — the closest thing to a system prompt — is the first text part;
 * what to evaluate still belongs in each question's instructions.</li>
 * <li>{@link #json(Object)} is sent as a text part holding the serialized JSON.</li>
 * </ul>
 *
 * <pre>{@code
 * new DecisionInput()
 *     .guidance("You review incoming supplier invoices for a Spanish company.")
 *     .text("Extracted invoice header:")
 *     .json(invoiceHeader)
 *     .image("data:image/png;base64,...");
 * }</pre>
 */
public final class DecisionInput {

  private static final String INPUT_TEXT = "input_text";
  private static final String TEXT = "text";

  private String guidance;
  private final List<JSONObject> parts = new ArrayList<>();

  /**
   * Input made of a single text.
   *
   * @param text the evidence
   * @return the input
   */
  public static DecisionInput ofText(String text) {
    return new DecisionInput().text(text);
  }

  /**
   * Sets guidance that applies to every question; sent before the other parts.
   *
   * @param text the guidance; blank clears it
   * @return this input
   */
  public DecisionInput guidance(String text) {
    this.guidance = StringUtils.trimToNull(text);
    return this;
  }

  /**
   * Adds a text part.
   *
   * @param text non-blank text
   * @return this input
   */
  public DecisionInput text(String text) {
    if (StringUtils.isBlank(text)) {
      throw new IllegalArgumentException("text part cannot be blank");
    }
    return add(INPUT_TEXT, TEXT, text);
  }

  /**
   * Adds structured data, sent serialized as a text part.
   *
   * @param data a {@link JSONObject} or {@link JSONArray}
   * @return this input
   */
  public DecisionInput json(Object data) {
    if (!(data instanceof JSONObject) && !(data instanceof JSONArray)) {
      throw new IllegalArgumentException("json part must be a JSONObject or a JSONArray");
    }
    return add(INPUT_TEXT, TEXT, data.toString());
  }

  /**
   * Adds an image.
   *
   * @param url a public {@code http(s)} URL or a {@code data:image/...;base64,} URL
   * @return this input
   */
  public DecisionInput image(String url) {
    if (StringUtils.isBlank(url)) {
      throw new IllegalArgumentException("image URL cannot be blank");
    }
    return add("input_image", "image_url", url);
  }

  boolean isEmpty() {
    return parts.isEmpty();
  }

  JSONArray toJson() throws JSONException {
    JSONArray content = new JSONArray();
    if (guidance != null) {
      content.put(new JSONObject().put("type", INPUT_TEXT).put(TEXT, guidance));
    }
    for (JSONObject part : parts) {
      content.put(part);
    }
    return new JSONArray().put(new JSONObject()
        .put("type", "message")
        .put("role", "user")
        .put("content", content));
  }

  private DecisionInput add(String type, String key, Object value) {
    try {
      parts.add(new JSONObject().put("type", type).put(key, value));
    } catch (JSONException e) {
      throw new IllegalArgumentException("cannot add " + type + " part", e);
    }
    return this;
  }
}
