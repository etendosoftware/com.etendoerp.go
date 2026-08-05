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

package com.etendoerp.go.support;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import com.etendoerp.go.common.ConfigPropertyReader;

/**
 * Jira integration configuration shared by {@link SupportIntegrationClient} (outbound calls)
 * and {@link SupportJiraWebhookHandler} (inbound webhook parsing) — a single source of truth
 * instead of each file reading its own copy of the same properties.
 *
 * Resolution order per value: JVM system property ({@code -D}) &gt; {@code Openbravo.properties}
 * &gt; environment variable &gt; default. No real credential ever ships as a default — {@code url},
 * {@code username}, and {@code apiToken} all default to {@code null}, and {@link #isConfigured()}
 * lets callers detect a missing setup and skip/log instead of silently talking to the wrong Jira
 * account. Matches the pattern already used by {@code EmailProviderConfig} and
 * {@code EtendoGoGoogleIdentityVerifier} elsewhere in this module.
 */
final class JiraConfig {

  static final String PROP_URL = "support.jira.url";
  static final String PROP_USERNAME = "support.jira.username";
  static final String PROP_TOKEN = "support.jira.token";
  static final String PROP_BOT_EMAIL = "support.jira.bot.email";
  static final String PROP_BOT_NAME = "support.jira.bot.name";

  static final String ENV_URL = "ETGO_SUPPORT_JIRA_URL";
  static final String ENV_USERNAME = "ETGO_SUPPORT_JIRA_USERNAME";
  static final String ENV_TOKEN = "ETGO_SUPPORT_JIRA_TOKEN";
  static final String ENV_BOT_EMAIL = "ETGO_SUPPORT_JIRA_BOT_EMAIL";
  static final String ENV_BOT_NAME = "ETGO_SUPPORT_JIRA_BOT_NAME";

  // Not a credential — just the display name used for bot-echo detection — so, unlike the
  // 3 fields above, a real default here is safe (mirrors DEFAULT_TIMEOUT_MS-style non-secret
  // defaults elsewhere in this module).
  private static final String DEFAULT_BOT_NAME = "Information Etendo";

  private final String url;
  private final String username;
  private final String apiToken;
  private final String botEmail;
  private final String botName;

  private JiraConfig(String url, String username, String apiToken, String botEmail, String botName) {
    this.url = url;
    this.username = username;
    this.apiToken = apiToken;
    this.botEmail = botEmail;
    this.botName = botName;
  }

  /** Re-reads all values from the current environment — deliberately not cached in a {@code
   * static final} field, so a test (or a config change picked up via {@code Openbravo.properties}
   * without a restart) sees the current value rather than whatever was in effect at class-load
   * time. */
  static JiraConfig fromRuntime() {
    return new JiraConfig(
        ConfigPropertyReader.readConfigValue(PROP_URL, ENV_URL, null),
        ConfigPropertyReader.readConfigValue(PROP_USERNAME, ENV_USERNAME, null),
        ConfigPropertyReader.readConfigValue(PROP_TOKEN, ENV_TOKEN, null),
        ConfigPropertyReader.readConfigValue(PROP_BOT_EMAIL, ENV_BOT_EMAIL, null),
        ConfigPropertyReader.readConfigValue(PROP_BOT_NAME, ENV_BOT_NAME, DEFAULT_BOT_NAME));
  }

  /** True once URL, username, and token are all present. Callers must check this before making
   * an outbound call — a partial configuration (e.g. URL set but no token) is treated the same
   * as none at all, rather than attempting a call that can only fail. */
  boolean isConfigured() {
    return url != null && username != null && apiToken != null;
  }

  String getUrl() {
    return url;
  }

  String getUsername() {
    return username;
  }

  /** Empty string (never {@code null}) when unset, so callers can keep using {@code
   * .isEmpty()}-style checks against the bot email/name without an extra null guard. */
  String getBotEmail() {
    return botEmail == null ? "" : botEmail;
  }

  String getBotName() {
    return botName;
  }

  /** {@code Base64(username:apiToken)} for the Jira REST API's Basic Auth header. Only
   * meaningful when {@link #isConfigured()} is true — callers are expected to check that first. */
  String basicAuthCredentials() {
    return Base64.getEncoder().encodeToString((username + ":" + apiToken).getBytes(StandardCharsets.UTF_8));
  }

}
