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

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;

import com.etendoerp.go.hubspot.HubSpotOutboxStore.PendingEvent;

/**
 * The values {@link HubSpotEventSender} writes: dates as HubSpot takes them, the country, the plan,
 * and the small JSON pieces of a request. Stateless; split out so the sender keeps to the calls.
 */
final class HubSpotValues {

  private static final Logger log = LogManager.getLogger(HubSpotValues.class);

  /** The values of the deal's {@code etendo_plan} enumeration; any other key would be refused. */
  static final Set<String> HUBSPOT_PLANS = Set.of("start", "core", "pro", "scale", "enterprise");

  /** The portal's existing contacts carry the country name in Spanish ("España"). */
  private static final Locale COUNTRY_LANGUAGE = new Locale("es");

  private HubSpotValues() {
  }

  static boolean isAnnual(String interval) {
    return StripeSubscriptionEvents.ANNUAL.equals(interval);
  }

  static Instant eventDate(PendingEvent event, JSONObject payload) {
    String at = payload.optString(StripeSubscriptionEvents.FIELD_AT, "");
    if (StringUtils.isNotBlank(at)) {
      try {
        return Instant.parse(at);
      } catch (DateTimeParseException e) {
        log.warn("Billing event {} carries an unreadable date '{}'", event.id(), at);
      }
    }
    return event.occurredAt() != null ? event.occurredAt() : Instant.now();
  }

  static void putIfPresent(JSONObject object, String key, String value)
      throws JSONException {
    if (StringUtils.isNotBlank(value)) {
      object.put(key, value);
    }
  }

  static String hubSpotPlan(String planKey, String accountId) {
    String plan = StringUtils.trimToNull(planKey);
    if (plan == null) {
      return null;
    }
    if (!HUBSPOT_PLANS.contains(plan)) {
      log.warn("Plan '{}' paid by account {} is not a HubSpot plan; the deal goes without it",
          plan, accountId);
      return null;
    }
    return plan;
  }

  /**
   * The Spanish name of the country the owner entered, written to the contact's and the company's
   * {@code country}, as the portal's existing records have it. A demo reported before the code was
   * recorded falls back to the stored name.
   */
  static String countryName(JSONObject demoPayload) {
    String code = countryCode(demoPayload);
    if (code != null) {
      String name = new Locale("", code).getDisplayCountry(COUNTRY_LANGUAGE);
      if (StringUtils.isNotBlank(name) && !name.equalsIgnoreCase(code)) {
        return name;
      }
    }
    return StringUtils.trimToNull(demoPayload.optString(HubSpotOutbox.FIELD_COUNTRY, ""));
  }

  /** The ISO 3166 two-letter code of the country the owner entered, upper case, or null. */
  static String countryCode(JSONObject demoPayload) {
    String code = StringUtils.trimToNull(demoPayload.optString(HubSpotOutbox.FIELD_COUNTRY_CODE,
        ""));
    return code != null && code.length() == 2 ? code.toUpperCase(Locale.ROOT) : null;
  }

  static JSONObject equalTo(String property, String value) throws JSONException {
    return new JSONObject()
        .put("propertyName", property)
        .put("operator", "EQ")
        .put("value", value);
  }

  static JSONObject association(String toId, int typeId) throws JSONException {
    return new JSONObject()
        .put("to", new JSONObject().put("id", toId))
        .put("types", new JSONArray().put(new JSONObject()
            .put("associationCategory", "HUBSPOT_DEFINED")
            .put("associationTypeId", typeId)));
  }

  static JSONObject payloadOf(PendingEvent event) throws JSONException {
    return StringUtils.isBlank(event.payload()) ? new JSONObject()
        : new JSONObject(event.payload());
  }

  /** HubSpot date properties take a {@code yyyy-MM-dd} calendar day. */
  static String date(Instant instant) {
    return instant.atZone(ZoneOffset.UTC).toLocalDate().toString();
  }

  /** An ISO 8601 instant, for HubSpot {@code datetime} properties. */
  static String dateTime(Instant instant) {
    return instant.truncatedTo(ChronoUnit.MILLIS).toString();
  }

  static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }
}
