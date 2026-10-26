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
import com.etendoerp.go.hubspot.HubSpotOutboxStore.EnvironmentDeal;
import com.etendoerp.go.hubspot.HubSpotOutboxStore.PendingEvent;
import com.etendoerp.go.hubspot.HubSpotOutboxStore.SubscriptionEnvironment;

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
 *   <li>The billing events ({@code PAYMENT}, {@code PAYMENT_FAILED}, {@code CANCEL_REQUESTED},
 *       {@code CANCEL_REVERTED}, {@code CANCELED}): the subscription state and dates on the deal
 *       of the productive environment the subscription pays for.</li>
 * </ul>
 *
 * <p>Each productive environment has its own deal. The first one takes the account's deal: the
 * one its demo created, or else the one marketing created with the account's id. Any further
 * productive gets a new deal, won from the start. Stages move only on the deals the sync created;
 * a marketing deal only gets its properties written.
 *
 * <p>Every call is safe to repeat: the contact is upserted by address and a deal is looked up
 * before one is created, so a retry after a half-finished send does not duplicate it.
 */
class HubSpotEventSender {

  private static final Logger log = LogManager.getLogger(HubSpotEventSender.class);

  static final String SELF_SERVE = "self_serve";
  private static final String PROPERTIES = "properties";
  // A path of the HubSpot API, appended to the configurable base URL (java:S1075 does not apply).
  private static final String COMPANIES_PATH = "/crm/v3/objects/companies"; // NOSONAR
  static final String DEAL_SUFFIX = " — Self-serve";

  /** Deal properties of the subscription, created by marketing on 2026-10-23. */
  static final String SUBSCRIPTION_STATE = "etendo_estado_suscripcion";
  static final String BILLING_INTERVAL = "etendo_periodicidad";
  static final String LAST_PAYMENT = "etendo_fecha_ultimo_pago";
  static final String FAILED_CHARGE = "etendo_fecha_cobro_fallido";
  static final String CANCELLATION = "etendo_fecha_baja";
  static final String STATE_TRIAL = "trial";
  static final String STATE_ACTIVE = "active";
  static final String STATE_SUSPENDED = "suspended";
  static final String STATE_CANCELLED = "cancelled";

  private static final String DEALS_PATH = "/crm/v3/objects/deals"; // NOSONAR - see COMPANIES_PATH
  private static final String DEAL_STAGE = "dealstage";
  private static final String PLAN = "etendo_plan";
  private static final String ACCOUNT_ID = "etendo_account_id";
  private static final String DEAL_NAME = "dealname";
  private static final String PIPELINE = "pipeline";
  private static final String ASSOCIATIONS = "associations";
  private static final String EMAIL = "email";
  private static final String RESULTS = "results";

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
        case HubSpotOutboxStore.PAYMENT:
        case HubSpotOutboxStore.PAYMENT_FAILED:
        case HubSpotOutboxStore.CANCEL_REQUESTED:
        case HubSpotOutboxStore.CANCEL_REVERTED:
        case HubSpotOutboxStore.CANCELED:
          return sendBillingEvent(event);
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
        .put(DEAL_NAME, StringUtils.defaultIfBlank(companyName, event.email()) + DEAL_SUFFIX)
        .put(PIPELINE, configuration.pipelineId())
        .put(DEAL_STAGE, configuration.trialStage())
        .put(ACCOUNT_ID, event.accountId())
        .put("etendo_camino_entrada_deal", SELF_SERVE)
        .put(SUBSCRIPTION_STATE, STATE_TRIAL);
    JSONArray associations = new JSONArray()
        .put(association(contactId, DEAL_TO_CONTACT));
    if (companyId != null) {
      associations.put(association(companyId, DEAL_TO_COMPANY));
    }
    return idOf(call("POST", DEALS_PATH, new JSONObject()
        .put(PROPERTIES, deal)
        .put(ASSOCIATIONS, associations)));
  }

  private Outcome sendDemoEntered(PendingEvent event) throws CallFailed, JSONException {
    call("PATCH", "/crm/v3/objects/contacts/" + encode(event.email()) + "?idProperty=email",
        new JSONObject().put(PROPERTIES, new JSONObject().put("etendo_prueba_activada", "si")));
    // A deal already won or lost stays where it is: entering the demo after paying is no news.
    if (store.findStatus(event.accountId(), HubSpotOutboxStore.PAID) != null
        || store.findStatus(event.accountId(), HubSpotOutboxStore.PAYMENT) != null
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
    JSONObject properties = new JSONObject().put(DEAL_STAGE, stage);
    if (plan != null) {
      properties.put(PLAN, plan);
    }
    updateDeal(dealId, properties);
    return Outcome.sent(dealId);
  }

  private Outcome sendBillingEvent(PendingEvent event) throws CallFailed, JSONException {
    JSONObject payload = payloadOf(event);
    Optional<SubscriptionEnvironment> environment = store.findSubscriptionEnvironment(
        payload.optString(StripeSubscriptionEvents.FIELD_SUBSCRIPTION, ""));
    if (environment.isEmpty()) {
      // The first payment usually arrives before its environment exists; it waits for it.
      return Outcome.retry("The subscription's productive environment is not provisioned yet");
    }
    String environmentId = environment.get().environmentId();
    store.assignEnvironment(event.id(), environmentId);
    if (event.occurredAt() != null
        && store.hasNewerBillingEvent(event.accountId(), environmentId, event.occurredAt())) {
      return Outcome.skipped("A later billing event of environment " + environmentId
          + " was already sent");
    }
    Optional<EnvironmentDeal> deal = environmentDeal(event, environment.get());
    if (deal.isEmpty()) {
      return Outcome.skipped("The account has no HubSpot deal");
    }
    String dealId = deal.get().dealId();
    updateDeal(dealId, billingProperties(event, payload, deal.get(), environment.get()));
    return Outcome.sent(dealId);
  }

  private JSONObject billingProperties(PendingEvent event, JSONObject payload,
      EnvironmentDeal deal, SubscriptionEnvironment environment)
      throws CallFailed, JSONException {
    String day = date(eventDate(event, payload));
    JSONObject properties = new JSONObject();
    switch (event.eventType()) {
      case HubSpotOutboxStore.PAYMENT:
        properties.put(SUBSCRIPTION_STATE, STATE_ACTIVE).put(LAST_PAYMENT, day);
        putIfPresent(properties, "amount", payload.optString(StripeSubscriptionEvents.FIELD_AMOUNT,
            null));
        putIfPresent(properties, BILLING_INTERVAL,
            payload.optString(StripeSubscriptionEvents.FIELD_INTERVAL, null));
        if (deal.owned()) {
          // A payment wins the deal, whatever it went through: a trial that expired before the
          // owner paid left it lost.
          properties.put(DEAL_STAGE, configuration.wonStage());
          putIfPresent(properties, PLAN, hubSpotPlan(environment.planKey(), event.accountId()));
        }
        break;
      case HubSpotOutboxStore.PAYMENT_FAILED:
        properties.put(SUBSCRIPTION_STATE, STATE_SUSPENDED).put(FAILED_CHARGE, day);
        break;
      case HubSpotOutboxStore.CANCEL_REQUESTED:
        // The owner keeps access until the period ends, so the subscription is still active.
        properties.put(CANCELLATION, day);
        break;
      case HubSpotOutboxStore.CANCEL_REVERTED:
        properties.put(SUBSCRIPTION_STATE, STATE_ACTIVE).put(CANCELLATION, "");
        break;
      default:
        properties.put(SUBSCRIPTION_STATE, STATE_CANCELLED);
        // The date of a scheduled cancellation is when it was asked for: kept, not overwritten
        // with the end of the period.
        if (StringUtils.isBlank(dealProperty(deal.dealId(), CANCELLATION))) {
          properties.put(CANCELLATION, day);
        }
        break;
    }
    return properties;
  }

  /**
   * The deal of a productive environment, decided the first time one of its events is sent and
   * then recorded.
   *
   * @return the deal, or empty when the account has none in HubSpot: then nothing is recorded,
   *     and a later event looks again
   */
  private Optional<EnvironmentDeal> environmentDeal(PendingEvent event,
      SubscriptionEnvironment environment) throws CallFailed, JSONException {
    Optional<EnvironmentDeal> known = store.findEnvironmentDeal(event.accountId(),
        environment.environmentId());
    if (known.isPresent()) {
      return known;
    }
    Optional<EnvironmentDeal> accountDeal = accountDeal(event.accountId());
    if (accountDeal.isEmpty()) {
      return Optional.empty();
    }
    EnvironmentDeal decided = store.countEnvironmentDeals(event.accountId()) == 0
        ? accountDeal.get()
        : new EnvironmentDeal(createEnvironmentDeal(event, environment, accountDeal.get()), true);
    store.recordEnvironmentDeal(event.accountId(), environment.environmentId(), decided);
    return Optional.of(decided);
  }

  /** The deal the account started with: its demo's, else the one marketing created. */
  private Optional<EnvironmentDeal> accountDeal(String accountId)
      throws CallFailed, JSONException {
    String demoDeal = store.findDealId(accountId);
    if (demoDeal != null) {
      return Optional.of(new EnvironmentDeal(demoDeal, true));
    }
    JSONObject found = searchDeal(accountId);
    if (found == null) {
      return Optional.empty();
    }
    JSONObject properties = found.optJSONObject(PROPERTIES);
    boolean owned = properties != null
        && configuration.pipelineId().equals(properties.optString(PIPELINE, ""));
    return Optional.of(new EnvironmentDeal(found.getString("id"), owned));
  }

  /**
   * A further productive's own deal, won, next to the contact and company of the first one.
   *
   * <p>It carries no {@code etendo_account_id}: HubSpot holds that property unique, and the
   * account's first deal already has it. The link to the environment is the
   * {@code PRODUCTIVE_DEAL} row the caller records.
   */
  private String createEnvironmentDeal(PendingEvent event, SubscriptionEnvironment environment,
      EnvironmentDeal accountDeal) throws CallFailed, JSONException {
    String dealName = StringUtils.defaultIfBlank(environment.name(), event.email()) + DEAL_SUFFIX;
    String contactId = upsertContact(event, new JSONObject().put(EMAIL, event.email()));
    // Looked up by name and contact first: a retry after the deal was created but not recorded
    // finds it.
    JSONObject existing = searchDeal(new JSONArray()
        .put(equalTo(DEAL_NAME, dealName))
        .put(equalTo("associations.contact", contactId)));
    if (existing != null) {
      return existing.getString("id");
    }
    JSONObject deal = new JSONObject()
        .put(DEAL_NAME, dealName)
        .put(PIPELINE, configuration.pipelineId())
        .put(DEAL_STAGE, configuration.wonStage())
        .put("etendo_camino_entrada_deal", SELF_SERVE);
    putIfPresent(deal, PLAN, hubSpotPlan(environment.planKey(), event.accountId()));
    JSONArray associations = new JSONArray().put(association(contactId, DEAL_TO_CONTACT));
    String companyId = dealCompany(accountDeal.dealId());
    if (companyId != null) {
      associations.put(association(companyId, DEAL_TO_COMPANY));
    }
    return idOf(call("POST", DEALS_PATH, new JSONObject()
        .put(PROPERTIES, deal)
        .put(ASSOCIATIONS, associations)));
  }

  /** The company of a deal, best effort like {@link #createCompany}. */
  private String dealCompany(String dealId) throws CallFailed, JSONException {
    String path = DEALS_PATH + "/" + encode(dealId) + "/associations/companies"; // NOSONAR
    Response response = request("GET", path, null);
    if (!response.isSuccess()) {
      if (response.status() == 429 || response.status() >= 500) {
        throw new CallFailed(Outcome.retry(describe("GET", path, response)));
      }
      log.warn("Could not read the company of deal {}; the new deal has none: {}", dealId,
          describe("GET", path, response));
      return null;
    }
    JSONArray results = new JSONObject(response.body()).optJSONArray(RESULTS);
    return results == null || results.length() == 0 ? null
        : results.getJSONObject(0).optString("id", null);
  }

  private String dealProperty(String dealId, String property) throws CallFailed, JSONException {
    JSONObject deal = call("GET", DEALS_PATH + "/" + encode(dealId) + "?properties="
        + encode(property), null);
    JSONObject properties = deal.optJSONObject(PROPERTIES);
    // A property never set comes back as JSON null, which optString would read as "null".
    return properties == null || properties.isNull(property) ? null
        : StringUtils.trimToNull(properties.optString(property, ""));
  }

  private void updateDeal(String dealId, JSONObject properties)
      throws CallFailed, JSONException {
    call("PATCH", DEALS_PATH + "/" + encode(dealId),
        new JSONObject().put(PROPERTIES, properties));
  }

  private static Instant eventDate(PendingEvent event, JSONObject payload) {
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

  private static void putIfPresent(JSONObject object, String key, String value)
      throws JSONException {
    if (StringUtils.isNotBlank(value)) {
      object.put(key, value);
    }
  }

  private static String planOf(PendingEvent event) throws JSONException {
    return hubSpotPlan(payloadOf(event).optString("plan", null), event.accountId());
  }

  private static String hubSpotPlan(String planKey, String accountId) {
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

  private JSONObject contactBase(PendingEvent event) throws JSONException {
    JSONObject properties = new JSONObject()
        .put(EMAIL, event.email())
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
        .put("idProperty", EMAIL)
        .put("id", event.email())
        .put(PROPERTIES, properties);
    JSONObject answer = call("POST", "/crm/v3/objects/contacts/batch/upsert",
        new JSONObject().put("inputs", new JSONArray().put(input)));
    JSONArray results = answer.optJSONArray(RESULTS);
    if (results == null || results.length() == 0) {
      throw new CallFailed(Outcome.failed("HubSpot upserted no contact: " + answer));
    }
    return results.getJSONObject(0).getString("id");
  }

  private String findDeal(String accountId) throws CallFailed, JSONException {
    JSONObject found = searchDeal(accountId);
    return found == null ? null : found.getString("id");
  }

  /** The deal of the account: the only one holding its id, which HubSpot keeps unique. */
  private JSONObject searchDeal(String accountId) throws CallFailed, JSONException {
    return searchDeal(new JSONArray().put(equalTo(ACCOUNT_ID, accountId)));
  }

  /**
   * Searches a deal.
   *
   * @return the first match, with its pipeline, or {@code null}
   */
  private JSONObject searchDeal(JSONArray filters) throws CallFailed, JSONException {
    JSONObject answer = call("POST", DEALS_PATH + "/search", new JSONObject()
        .put("filterGroups", new JSONArray().put(new JSONObject().put("filters", filters)))
        .put(PROPERTIES, new JSONArray().put(PIPELINE))
        .put("limit", 1));
    JSONArray results = answer.optJSONArray(RESULTS);
    return results == null || results.length() == 0 ? null : results.getJSONObject(0);
  }

  private static JSONObject equalTo(String property, String value) throws JSONException {
    return new JSONObject()
        .put("propertyName", property)
        .put("operator", "EQ")
        .put("value", value);
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
        .put(ASSOCIATIONS, new JSONArray().put(association(contactId, COMPANY_TO_CONTACT)));
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
