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

import static com.etendoerp.go.hubspot.HubSpotValues.describe;
import static com.etendoerp.go.hubspot.HubSpotValues.payloadOf;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;

import com.etendoerp.go.hubspot.HubSpotClient.HubSpotUnreachableException;
import com.etendoerp.go.hubspot.HubSpotClient.Response;
import com.etendoerp.go.hubspot.HubSpotEventSender.Outcome;
import com.etendoerp.go.hubspot.HubSpotOutboxStore.PendingEvent;

/**
 * ETP-6363 — sends the {@code SIGNUP_FORM} event: the sign-up, submitted to marketing's sign-up
 * form with its lead attribution. HubSpot creates or updates the contact, joins it to the visits of
 * its cookie and records the conversion, so the contact gets its original source (Google,
 * LinkedIn, an ad) instead of "Offline". Only what is known at sign-up: the trial dates and the
 * company come with {@code DEMO_READY}, whose update does not change the source.
 *
 * <p>Submitted once: a refusal is final (a field the form lacks), so the account's event then
 * creates the contact through the CRM API instead of waiting for it.
 */
class HubSpotSignupFormSender {

  private static final Logger log = LogManager.getLogger(HubSpotSignupFormSender.class);

  private final HubSpotClient client;
  private final HubSpotSignupForm signupForm;

  HubSpotSignupFormSender(HubSpotClient client, HubSpotSignupForm signupForm) {
    this.client = client;
    this.signupForm = signupForm;
  }

  /**
   * Submits the sign-up.
   *
   * @param event   the {@code SIGNUP_FORM} row
   * @param contact the contact's own properties, from which the form fields are taken
   * @return what became of it
   */
  Outcome send(PendingEvent event, JSONObject contact) throws JSONException {
    if (!signupForm.isEnabled()) {
      return Outcome.skipped("No HubSpot sign-up form is configured");
    }
    JSONArray fields = new JSONArray();
    for (String name : HubSpotEventSender.SIGNUP_FORM_FIELDS) {
      String value = contact.optString(name, null);
      if (StringUtils.isNotBlank(value)) {
        fields.put(new JSONObject().put("objectTypeId", HubSpotEventSender.CONTACT_OBJECT_TYPE)
            .put("name", name)
            .put("value", value));
      }
    }
    JSONObject body = new JSONObject().put("fields", fields);
    JSONObject context = formContext(payloadOf(event));
    if (context.length() > 0) {
      body.put("context", context);
    }
    Response response;
    try {
      response = client.send(signupForm.baseUrl(), "POST", signupForm.submitPath(), body);
    } catch (HubSpotUnreachableException e) {
      return Outcome.retry(e.getMessage());
    }
    if (response.isSuccess()) {
      return Outcome.sent(null);
    }
    String reason = describe("POST", signupForm.submitPath(), response);
    if (response.isRetryable()) {
      return Outcome.retry(reason);
    }
    // Usually a field the form does not have: worth the response in the log (marketing's spec).
    log.warn("HubSpot refused the sign-up form of account {}: {}", event.accountId(), reason);
    return Outcome.failed(reason);
  }

  /** The visitor cookie (only when there is one), the page and its title. */
  private static JSONObject formContext(JSONObject signupContext) throws JSONException {
    JSONObject context = new JSONObject();
    for (String field : new String[] { HubSpotOutbox.FIELD_HUTK, HubSpotOutbox.FIELD_PAGE_URI,
        HubSpotOutbox.FIELD_PAGE_NAME }) {
      String value = StringUtils.trimToNull(signupContext.optString(field, null));
      if (value != null) {
        context.put(field, value);
      }
    }
    return context;
  }
}
