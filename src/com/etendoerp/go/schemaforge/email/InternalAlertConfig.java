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
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import com.etendoerp.go.common.ConfigPropertyReader;

/** Server-owned recipients and result filter, resolved at send time. */
public final class InternalAlertConfig {
  private final boolean enabled;
  private final List<String> recipients;
  private final Set<InternalAlertEvent.Status> statuses;
  private final String language;

  /**
   * Validates and freezes an alert configuration.
   *
   * @param enabled whether alerts are sent at all
   * @param recipients one to ten valid addresses
   * @param statuses results that are alerted; at least one
   * @param language language of the alert email
   * @throws IllegalArgumentException when a recipient is invalid or a list is out of bounds
   */
  public InternalAlertConfig(boolean enabled, List<String> recipients,
      Set<InternalAlertEvent.Status> statuses, String language) {
    List<String> addresses = new ArrayList<>();
    for (String recipient : recipients) {
      String address = recipient.trim();
      if (!EmailContractCommandSupport.isValidEmail(address)) {
        throw new IllegalArgumentException("Invalid internal alert recipient configuration");
      }
      addresses.add(address);
    }
    if (addresses.isEmpty() || addresses.size() > 10 || statuses.isEmpty()) {
      throw new IllegalArgumentException("Invalid internal alert configuration");
    }
    this.enabled = enabled;
    this.recipients = Collections.unmodifiableList(addresses);
    this.statuses = Collections.unmodifiableSet(EnumSet.copyOf(statuses));
    this.language = language;
  }

  /**
   * Reads the configuration from the {@code etendo.go.internalAlerts.*} properties or their
   * {@code ETGO_INTERNAL_ALERTS_*} environment variables, with safe defaults.
   *
   * @return the configuration in effect now
   */
  public static InternalAlertConfig fromRuntime() {
    String enabled = read("enabled", "ENABLED", "true");
    String statusValue = read("statuses", "STATUSES", "OK,ERROR");
    EnumSet<InternalAlertEvent.Status> statuses = EnumSet.noneOf(InternalAlertEvent.Status.class);
    for (String status : statusValue.split(",")) {
      statuses.add(InternalAlertEvent.Status.valueOf(status.trim().toUpperCase(Locale.ROOT)));
    }
    return new InternalAlertConfig("true".equalsIgnoreCase(enabled) || "Y".equalsIgnoreCase(enabled)
        || "1".equals(enabled), java.util.Arrays.asList(read("recipients", "RECIPIENTS",
            "builds@etendo.software").split(",")), statuses,
        read("language", "LANGUAGE", "es_ES"));
  }
  private static String read(String property, String env, String fallback) {
    return ConfigPropertyReader.readConfigValue("etendo.go.internalAlerts." + property,
        "ETGO_INTERNAL_ALERTS_" + env, fallback);
  }

  /**
   * Tells whether an event with {@code status} must be sent under this configuration.
   *
   * @param status result of the event
   * @return whether an event with that result must be sent
   */
  public boolean accepts(InternalAlertEvent.Status status) {
    return enabled && statuses.contains(status);
  }

  /**
   * Returns the addresses every alert is sent to.
   *
   * @return the configured recipients, unmodifiable
   */
  public List<String> getRecipients() {
    return recipients;
  }

  /**
   * Returns the language the alert email is rendered in.
   *
   * @return the language of the alert email
   */
  public String getLanguage() {
    return language;
  }
}
