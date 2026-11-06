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

package com.etendoerp.go.hubspot;

import org.apache.commons.lang3.StringUtils;

import com.etendoerp.go.common.GoRuntimeProperties;

/**
 * Where and as whom the HubSpot sync writes, and which deal pipeline it moves.
 *
 * <p>Each value is read as a JVM system property, then {@code Openbravo.properties}, then an
 * environment variable ({@link GoRuntimeProperties}). Only the token has no default: without it
 * the sync sends nothing and the outbox keeps every event pending. The pipeline and stage ids
 * default to the "Self-Serve" pipeline of the production portal, so production needs the token
 * alone; a test portal has its own ids and must configure them.
 *
 * @param token             the private app token, {@code null} when not configured
 * @param apiBaseUrl        the API root, without a trailing slash
 * @param pipelineId        the deal pipeline every self-serve deal is created in
 * @param accountStage      MQL: the account exists and its address is confirmed
 * @param activatedStage    SQL: the owner used the ERP for the first time (a qualification
 *                          milestone)
 * @param cardStage         the owner paid the checkout: the demo became a productive
 * @param firstPaymentStage the subscription renewed for the first time
 * @param expiredStage      the trial ended with no productive environment
 * @param lostStage         the subscription was cancelled
 */
public record HubSpotConfiguration(String token, String apiBaseUrl, String pipelineId,
    String accountStage, String activatedStage, String cardStage, String firstPaymentStage,
    String expiredStage, String lostStage) {

  static final String TOKEN_PROPERTY = "etendo.go.hubspot.token";
  static final String TOKEN_ENV = "ETGO_HUBSPOT_TOKEN";
  static final String DEFAULT_API_BASE_URL = "https://api.hubapi.com";

  /** "Self-Serve" pipeline and its stages in portal 52086088, read on 2026-11-05. */
  static final String DEFAULT_PIPELINE = "952774980";
  static final String DEFAULT_ACCOUNT_STAGE = "1471483196";
  static final String DEFAULT_ACTIVATED_STAGE = "1471483197";
  static final String DEFAULT_CARD_STAGE = "1471504904";
  static final String DEFAULT_FIRST_PAYMENT_STAGE = "1471504905";
  static final String DEFAULT_EXPIRED_STAGE = "1471504906";
  static final String DEFAULT_LOST_STAGE = "1471504907";

  /** Where the settings come from. */
  @FunctionalInterface
  interface Settings {
    /**
     * Reads one setting.
     *
     * @param propertyName its property name
     * @param envName      its environment variable
     * @return its value, or {@code null} when it is not set
     */
    String read(String propertyName, String envName);
  }

  /**
   * Reads the configuration as currently set on this server.
   *
   * @return the configuration
   */
  public static HubSpotConfiguration fromRuntime() {
    return from((property, env) -> GoRuntimeProperties.readValue(property, env, null));
  }

  static HubSpotConfiguration from(Settings settings) {
    return new HubSpotConfiguration(
        StringUtils.trimToNull(settings.read(TOKEN_PROPERTY, TOKEN_ENV)),
        StringUtils.removeEnd(read(settings, "api.base.url", "API_BASE_URL",
            DEFAULT_API_BASE_URL), "/"),
        read(settings, "pipeline", "PIPELINE", DEFAULT_PIPELINE),
        read(settings, "stage.account", "STAGE_ACCOUNT", DEFAULT_ACCOUNT_STAGE),
        read(settings, "stage.activated", "STAGE_ACTIVATED", DEFAULT_ACTIVATED_STAGE),
        read(settings, "stage.card", "STAGE_CARD", DEFAULT_CARD_STAGE),
        read(settings, "stage.first.payment", "STAGE_FIRST_PAYMENT",
            DEFAULT_FIRST_PAYMENT_STAGE),
        read(settings, "stage.expired", "STAGE_EXPIRED", DEFAULT_EXPIRED_STAGE),
        read(settings, "stage.lost", "STAGE_LOST", DEFAULT_LOST_STAGE));
  }

  private static String read(Settings settings, String propertySuffix, String envSuffix,
      String defaultValue) {
    String value = settings.read("etendo.go.hubspot." + propertySuffix,
        "ETGO_HUBSPOT_" + envSuffix);
    return StringUtils.defaultIfBlank(StringUtils.trim(value), defaultValue);
  }

  /**
   * Tells whether anything may be sent.
   *
   * @return whether a token is configured
   */
  public boolean isEnabled() {
    return token != null;
  }

  @Override
  public String toString() {
    // The token is a credential: never let it reach a log line.
    return "HubSpotConfiguration[enabled=" + isEnabled() + ", apiBaseUrl=" + apiBaseUrl
        + ", pipelineId=" + pipelineId + "]";
  }
}
