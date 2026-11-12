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

import org.apache.commons.lang3.StringUtils;
import org.openbravo.base.session.OBPropertiesProvider;

import com.etendoerp.copilot.util.CopilotUtils;
import com.etendoerp.copilot.util.OpenAIUtils;

/**
 * Where the LLM gateway is and how to authenticate against it.
 *
 * <p>{@link #fromRuntime()} adds no setting of its own: it reuses the Copilot credentials every
 * Etendo Go environment already has — {@code openai.api.key} (a LiteLLM key) and
 * {@code copilot.proxy.url} — read exactly as {@link OpenAIUtils} reads them.</p>
 *
 * <p>Deliberately not a record: its {@code toString()} must never print the key.</p>
 */
public final class LlmConfig {

  /** Used when {@code copilot.proxy.url} is not set. */
  static final String DEFAULT_BASE_URL = "https://llm.etendo.software";
  /** LiteLLM model group for the Decisions API (OpenAI {@code gpt-6-luna}). */
  static final String DEFAULT_MODEL = "etendo-decisions";
  static final int DEFAULT_TIMEOUT_MS = 30000;

  private static final String PROXY_URL_PROPERTY = "copilot.proxy.url";
  /**
   * LiteLLM serves the Decisions API on {@code /decisions} and {@code /v1/decisions}, so the
   * Copilot URL, which carries no {@code /v1}, works as is (and so does one that does).
   */
  private static final String DECISIONS_PATH = "/decisions";

  private final String baseUrl;
  private final String apiKey;
  private final String model;
  private final int timeoutMs;

  /**
   * Creates a configuration from already resolved values.
   *
   * @param baseUrl base URL of an OpenAI-compatible API; blank means {@value #DEFAULT_BASE_URL}
   * @param apiKey the key, or {@code null} when none is configured
   * @param model gateway model id; blank means {@value #DEFAULT_MODEL}
   * @param timeoutMs connect and request timeout; not positive means {@value #DEFAULT_TIMEOUT_MS}
   */
  public LlmConfig(String baseUrl, String apiKey, String model, int timeoutMs) {
    this.baseUrl = StringUtils.defaultIfBlank(StringUtils.trimToNull(baseUrl), DEFAULT_BASE_URL);
    this.apiKey = StringUtils.trimToNull(apiKey);
    this.model = StringUtils.defaultIfBlank(StringUtils.trimToNull(model), DEFAULT_MODEL);
    this.timeoutMs = timeoutMs > 0 ? timeoutMs : DEFAULT_TIMEOUT_MS;
  }

  /**
   * Reads the Copilot gateway credentials from Openbravo.properties, with model
   * {@value #DEFAULT_MODEL}.
   *
   * @return the runtime configuration
   */
  public static LlmConfig fromRuntime() {
    String proxyUrl = CopilotUtils.readPropertyWithLegacyCompatibility(
        OBPropertiesProvider.getInstance().getOpenbravoProperties(), PROXY_URL_PROPERTY, null);
    return new LlmConfig(proxyUrl, OpenAIUtils.getOpenaiApiKey(), DEFAULT_MODEL,
        DEFAULT_TIMEOUT_MS);
  }

  /**
   * The same configuration answering with another gateway model.
   *
   * @param otherModel a gateway model group that serves the Decisions API
   * @return a copy that uses {@code otherModel}
   */
  public LlmConfig withModel(String otherModel) {
    return new LlmConfig(baseUrl, apiKey, otherModel, timeoutMs);
  }

  /** @return whether a key was found, i.e. whether a call can be attempted at all */
  public boolean isConfigured() {
    return apiKey != null;
  }

  /** @return the Decisions API URL; a trailing slash on the base is ignored */
  public String decisionsUrl() {
    return StringUtils.stripEnd(baseUrl, "/") + DECISIONS_PATH;
  }

  public String getBaseUrl() {
    return baseUrl;
  }

  String getApiKey() {
    return apiKey;
  }

  public String getModel() {
    return model;
  }

  public int getTimeoutMs() {
    return timeoutMs;
  }

  @Override
  public String toString() {
    return "LlmConfig{baseUrl=" + baseUrl + ", configured=" + isConfigured() + ", model=" + model
        + ", timeoutMs=" + timeoutMs + "}";
  }
}
