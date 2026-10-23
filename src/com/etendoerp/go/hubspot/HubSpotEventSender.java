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
import java.util.Optional;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;

import com.etendoerp.go.hubspot.HubSpotClient.HubSpotUnreachableException;
import com.etendoerp.go.hubspot.HubSpotClient.Response;
import com.etendoerp.go.hubspot.HubSpotOutboxStore.PendingEvent;

/**
 * Turns one outbox row into the HubSpot calls that report it.
 *
 * <ul>
 *   <li>{@code ACCOUNT_CREATED}: the contact, created or updated by address, as a self-serve
 *       lead.</li>
 *   <li>{@code DEMO_READY}: the contact's trial dates, the company, and the deal in the trial
 *       stage, associated with both. The deal's id is what every later event moves.</li>
 *   <li>{@code DEMO_ENTERED}: the contact's "trial activated" flag and the deal's active stage.</li>
 *   <li>{@code PAID}: the deal won, with the plan chosen.</li>
 *   <li>{@code DEMO_EXPIRED}: the deal lost.</li>
 * </ul>
 *
 * <p>Every call is safe to repeat: the contact is upserted by address and a deal is looked up by
 * its {@code etendo_account_id} before one is created, so a retry after a half-finished send does
 * not duplicate it.
 */
class HubSpotEventSender {

  private static final Logger log = LogManager.getLogger(HubSpotEventSender.class);

  static final String SELF_SERVE = "self_serve";
  private static final String PROPERTIES = "properties";
  // A path of the HubSpot API, appended to the configurable base URL (java:S1075 does not apply).
  private static final String COMPANIES_PATH = "/crm/v3/objects/companies"; // NOSONAR
  static final String DEAL_SUFFIX = " — Self-serve";

  /** The values of the deal's {@code etendo_plan} enumeration; any other key would be refused. */
  static final Set<String> HUBSPOT_PLANS = Set.of("start", "core", "pro", "scale", "enterprise");

  /** HubSpot-defined association types. */
  static final int DEAL_TO_CONTACT = 3;
  static final int DEAL_TO_COMPANY = 5;
  static final int COMPANY_TO_CONTACT = 2;

  private final HubSpotClient client;
  private final HubSpotConfiguration configuration;
  private final HubSpotOutboxStore store;
  private final DemoTrials trials;

  HubSpotEventSender(HubSpotClient client, HubSpotConfiguration configuration,
      HubSpotOutboxStore store, DemoTrials trials) {
    this.client = client;
    this.configuration = configuration;
    this.store = store;
    this.trials = trials;
  }

  /**
   * Sends one event.
   *
   * @param event the row to send
   * @param now   the instant of this pass
   * @return what became of it
   */
  Outcome send(PendingEvent event, Instant now) {
    try {
      switch (event.eventType()) {
        case HubSpotOutboxStore.ACCOUNT_CREATED:
          return Outcome.sent(upsertContact(event, contactBase(event)
              .put("lifecyclestage", "lead")));
        case HubSpotOutboxStore.DEMO_READY:
          return Outcome.sent(sendDemoReady(event, now));
        case HubSpotOutboxStore.DEMO_ENTERED:
          return sendDemoEntered(event);
        case HubSpotOutboxStore.PAID:
          return moveDeal(event, configuration.wonStage(), planOf(event));
        case HubSpotOutboxStore.DEMO_EXPIRED:
          return moveDeal(event, configuration.lostStage(), null);
        default:
          return Outcome.skipped("Unknown event type " + event.eventType());
      }
    } catch (CallFailed e) {
      return e.outcome;
    } catch (JSONException e) {
      return Outcome.failed("Could not build or read a HubSpot message: " + e.getMessage());
    }
  }

  private String sendDemoReady(PendingEvent event, Instant now)
      throws CallFailed, JSONException {
    JSONObject payload = payloadOf(event);
    JSONObject contact = contactBase(event);
    Optional<DemoTrials.Trial> trial = trials.find(event.environmentId(), now);
    if (trial.isPresent()) {
      contact.put("etendo_inicio_prueba", date(trial.get().startedAt()));
      contact.put("etendo_fin_prueba", date(trial.get().endsAt()));
    }
    String contactId = upsertContact(event, contact);

    String existingDeal = findDeal(event.accountId());
    if (existingDeal != null) {
      return existingDeal;
    }
    String companyName = payload.optString(HubSpotOutbox.FIELD_COMPANY_NAME, "");
    String companyId = createCompany(companyName,
        payload.optString(HubSpotOutbox.FIELD_COUNTRY, ""), contactId);

    JSONObject deal = new JSONObject()
        .put("dealname", StringUtils.defaultIfBlank(companyName, event.email()) + DEAL_SUFFIX)
        .put("pipeline", configuration.pipelineId())
        .put("dealstage", configuration.trialStage())
        .put("etendo_account_id", event.accountId())
        .put("etendo_camino_entrada_deal", SELF_SERVE);
    JSONArray associations = new JSONArray()
        .put(association(contactId, DEAL_TO_CONTACT));
    if (companyId != null) {
      associations.put(association(companyId, DEAL_TO_COMPANY));
    }
    return idOf(call("POST", "/crm/v3/objects/deals", new JSONObject()
        .put(PROPERTIES, deal)
        .put("associations", associations)));
  }

  private Outcome sendDemoEntered(PendingEvent event) throws CallFailed, JSONException {
    call("PATCH", "/crm/v3/objects/contacts/" + encode(event.email()) + "?idProperty=email",
        new JSONObject().put(PROPERTIES, new JSONObject().put("etendo_prueba_activada", "si")));
    // A deal already won or lost stays where it is: entering the demo after paying is no news.
    if (store.findStatus(event.accountId(), HubSpotOutboxStore.PAID) != null
        || store.findStatus(event.accountId(), HubSpotOutboxStore.DEMO_EXPIRED) != null) {
      return Outcome.sent(null);
    }
    return moveDeal(event, configuration.activeStage(), null);
  }

  private Outcome moveDeal(PendingEvent event, String stage, String plan)
      throws CallFailed, JSONException {
    String dealId = store.findDealId(event.accountId());
    if (dealId == null) {
      String demoStatus = store.findStatus(event.accountId(), HubSpotOutboxStore.DEMO_READY);
      if (HubSpotOutboxStore.STATUS_PENDING.equals(demoStatus)) {
        return Outcome.deferred();
      }
      return Outcome.skipped("The account has no HubSpot deal (demo event " + demoStatus + ")");
    }
    JSONObject properties = new JSONObject().put("dealstage", stage);
    if (plan != null) {
      properties.put("etendo_plan", plan);
    }
    call("PATCH", "/crm/v3/objects/deals/" + encode(dealId),
        new JSONObject().put(PROPERTIES, properties));
    return Outcome.sent(dealId);
  }

  private static String planOf(PendingEvent event) throws JSONException {
    String plan = StringUtils.trimToNull(payloadOf(event).optString("plan", null));
    if (plan == null) {
      return null;
    }
    if (!HUBSPOT_PLANS.contains(plan)) {
      log.warn("Plan '{}' paid by account {} is not a HubSpot plan; the deal is won without it",
          plan, event.accountId());
      return null;
    }
    return plan;
  }

  private JSONObject contactBase(PendingEvent event) throws JSONException {
    JSONObject properties = new JSONObject()
        .put("email", event.email())
        .put("etendo_camino_entrada", SELF_SERVE);
    String name = StringUtils.trimToEmpty(event.name());
    if (!name.isEmpty()) {
      properties.put("firstname", StringUtils.substringBefore(name, " "));
      String lastName = StringUtils.trimToNull(StringUtils.substringAfter(name, " "));
      if (lastName != null) {
        properties.put("lastname", lastName);
      }
    }
    return properties;
  }

  private String upsertContact(PendingEvent event, JSONObject properties)
      throws CallFailed, JSONException {
    JSONObject input = new JSONObject()
        .put("idProperty", "email")
        .put("id", event.email())
        .put(PROPERTIES, properties);
    JSONObject answer = call("POST", "/crm/v3/objects/contacts/batch/upsert",
        new JSONObject().put("inputs", new JSONArray().put(input)));
    JSONArray results = answer.optJSONArray("results");
    if (results == null || results.length() == 0) {
      throw new CallFailed(Outcome.failed("HubSpot upserted no contact: " + answer));
    }
    return results.getJSONObject(0).getString("id");
  }

  private String findDeal(String accountId) throws CallFailed, JSONException {
    JSONObject filter = new JSONObject()
        .put("propertyName", "etendo_account_id")
        .put("operator", "EQ")
        .put("value", accountId);
    JSONObject answer = call("POST", "/crm/v3/objects/deals/search", new JSONObject()
        .put("filterGroups", new JSONArray().put(new JSONObject()
            .put("filters", new JSONArray().put(filter))))
        .put("limit", 1));
    JSONArray results = answer.optJSONArray("results");
    return results == null || results.length() == 0 ? null
        : results.getJSONObject(0).getString("id");
  }

  /**
   * Creates the company. A company is a courtesy: the deal is what links HubSpot with Etendo, so a
   * refusal (a token without the companies scope, for one) leaves the deal without a company
   * instead of holding the deal back.
   */
  private String createCompany(String name, String country, String contactId)
      throws CallFailed, JSONException {
    if (StringUtils.isBlank(name)) {
      return null;
    }
    JSONObject properties = new JSONObject().put("name", name);
    if (StringUtils.isNotBlank(country)) {
      properties.put("country", country);
    }
    JSONObject body = new JSONObject()
        .put(PROPERTIES, properties)
        .put("associations", new JSONArray().put(association(contactId, COMPANY_TO_CONTACT)));
    Response response = request("POST", COMPANIES_PATH, body);
    if (!response.isSuccess()) {
      if (response.status() == 429 || response.status() >= 500) {
        throw new CallFailed(Outcome.retry(describe("POST", COMPANIES_PATH,
            response)));
      }
      log.warn("HubSpot refused the company '{}'; the deal is created without it: {}", name,
          describe("POST", COMPANIES_PATH, response));
      return null;
    }
    return idOf(new JSONObject(response.body()));
  }

  private static JSONObject association(String toId, int typeId) throws JSONException {
    return new JSONObject()
        .put("to", new JSONObject().put("id", toId))
        .put("types", new JSONArray().put(new JSONObject()
            .put("associationCategory", "HUBSPOT_DEFINED")
            .put("associationTypeId", typeId)));
  }

  private JSONObject call(String method, String path, JSONObject body)
      throws CallFailed, JSONException {
    Response response = request(method, path, body);
    if (!response.isSuccess()) {
      String reason = describe(method, path, response);
      throw new CallFailed(response.isRetryable() ? Outcome.retry(reason)
          : Outcome.failed(reason));
    }
    return StringUtils.isBlank(response.body()) ? new JSONObject()
        : new JSONObject(response.body());
  }

  private Response request(String method, String path, JSONObject body) throws CallFailed {
    try {
      return client.send(method, path, body);
    } catch (HubSpotUnreachableException e) {
      throw new CallFailed(Outcome.retry(e.getMessage()));
    }
  }

  private static String describe(String method, String path, Response response) {
    return method + " " + StringUtils.substringBefore(path, "?") + " answered HTTP "
        + response.status() + ": " + response.body();
  }

  private static String idOf(JSONObject object) throws CallFailed, JSONException {
    String id = object.optString("id", null);
    if (StringUtils.isBlank(id)) {
      throw new CallFailed(Outcome.failed("HubSpot answered without an id: " + object));
    }
    return id;
  }

  private static JSONObject payloadOf(PendingEvent event) throws JSONException {
    return StringUtils.isBlank(event.payload()) ? new JSONObject()
        : new JSONObject(event.payload());
  }

  /** HubSpot date properties take a {@code yyyy-MM-dd} calendar day. */
  static String date(Instant instant) {
    return instant.atZone(ZoneOffset.UTC).toLocalDate().toString();
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  /** What became of one event. */
  record Outcome(Kind kind, String hubSpotObjectId, String detail) {

    enum Kind {
      /** HubSpot has it. */
      SENT,
      /** Not sent, may succeed later. */
      RETRY,
      /** HubSpot refused it; sending it again would be refused again. */
      FAILED,
      /** There is nothing to send it to. */
      SKIPPED,
      /** Waits for an earlier event of the same account; no attempt was spent. */
      DEFERRED
    }

    static Outcome sent(String hubSpotObjectId) {
      return new Outcome(Kind.SENT, hubSpotObjectId, null);
    }

    static Outcome retry(String reason) {
      return new Outcome(Kind.RETRY, null, reason);
    }

    static Outcome failed(String reason) {
      return new Outcome(Kind.FAILED, null, reason);
    }

    static Outcome skipped(String reason) {
      return new Outcome(Kind.SKIPPED, null, reason);
    }

    static Outcome deferred() {
      return new Outcome(Kind.DEFERRED, null, null);
    }
  }

  /** Carries the outcome of a call that ended the send early. */
  private static final class CallFailed extends Exception {
    private static final long serialVersionUID = 1L;
    private final transient Outcome outcome;

    CallFailed(Outcome outcome) {
      super(outcome.detail(), null, false, false);
      this.outcome = outcome;
    }
  }
}
