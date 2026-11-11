/*
 *************************************************************************
 * The contents of this file are subject to the Etendo License
 * (the "License"), you may not use this file except in compliance
 * with the License.
 * You may obtain a copy of the License at
 * https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * Software distributed under the License is distributed on an
 * "AS IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
 * implied. See the License for the specific language governing rights
 * and limitations under the License.
 * All portions are Copyright (C) 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 *************************************************************************
 */
package com.etendoerp.go.hubspot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.Test;

import com.etendoerp.go.hubspot.HubSpotClient.HubSpotUnreachableException;
import com.etendoerp.go.hubspot.HubSpotClient.Response;
import com.etendoerp.go.hubspot.HubSpotEventSender.Outcome;
import com.etendoerp.go.hubspot.HubSpotOutboxStore.EnvironmentDeal;
import com.etendoerp.go.hubspot.HubSpotOutboxStore.PendingEvent;
import com.etendoerp.go.hubspot.HubSpotOutboxStore.SubscriptionEnvironment;

/**
 * The HubSpot calls each outbox event becomes, and what a refusal or an outage makes of it.
 *
 * @covers com.etendoerp.go.hubspot.HubSpotEventSender
 */
class HubSpotEventSenderTest {

  private static final String ACCOUNT = "A1";
  private static final String CLIENT = "C1";
  private static final Instant NOW = Instant.parse("2026-10-22T12:00:00Z");
  private static final HubSpotConfiguration CONFIG = new HubSpotConfiguration("t",
      "https://hubspot.test", "pipe", "mql", "sql", "card", "first", "expired", "lost");

  private final List<Call> calls = new ArrayList<>();
  private final HubSpotOutboxStore store = mock(HubSpotOutboxStore.class);
  private final DemoTrials trials = mock(DemoTrials.class);
  private Function<Call, Response> answers = call -> ok(call);

  private final HubSpotEventSender sender = new HubSpotEventSender((method, path, body) -> {
    Call call = new Call(method, path, body);
    calls.add(call);
    return answers.apply(call);
  }, CONFIG, store, trials);

  private record Call(String method, String path, JSONObject body) {
    JSONObject properties() {
      return body.optJSONObject("properties");
    }
  }

  private static Response ok(Call call) {
    if (call.path().endsWith("/batch/upsert")) {
      return new Response(200, "{\"results\":[{\"id\":\"contact-1\"}]}");
    }
    if (call.path().endsWith("/deals/search")) {
      return new Response(200, "{\"results\":[]}");
    }
    if (call.path().endsWith("/companies")) {
      return new Response(201, "{\"id\":\"company-1\"}");
    }
    if (call.path().endsWith("/deals")) {
      return new Response(201, "{\"id\":\"deal-1\"}");
    }
    return new Response(200, "{}");
  }

  /** HubSpot's answer to reading a deal that sits in {@code stage} of {@code pipeline}. */
  private static Function<Call, Response> dealIn(String pipeline, String stage) {
    return call -> call.method().equals("GET") && call.path().contains("/crm/v3/objects/deals/")
        ? new Response(200, "{\"id\":\"deal-1\",\"properties\":{\"pipeline\":\"" + pipeline
            + "\",\"dealstage\":\"" + stage + "\"}}")
        : ok(call);
  }

  private static PendingEvent event(String type, String payload) {
    return new PendingEvent("row-1", ACCOUNT, type, CLIENT, payload, 0, null, "ana@empresa.es",
        "Ana García López", null);
  }

  private Call call(String method, String pathEnd) {
    return calls.stream()
        .filter(c -> c.method().equals(method) && c.path().endsWith(pathEnd))
        .findFirst().orElseThrow(() -> new AssertionError(method + " " + pathEnd + " not called"));
  }

  @Test
  void aNewAccountBecomesASelfServeContactWithoutALifecycleStage() throws Exception {
    Outcome outcome = sender.send(event(HubSpotOutboxStore.ACCOUNT_CREATED, null), NOW);

    assertEquals(Outcome.Kind.SENT, outcome.kind());
    JSONObject input = call("POST", "/crm/v3/objects/contacts/batch/upsert").body()
        .getJSONArray("inputs").getJSONObject(0);
    assertEquals("email", input.getString("idProperty"));
    assertEquals("ana@empresa.es", input.getString("id"));
    JSONObject properties = input.getJSONObject("properties");
    // ETP-6082: HubSpot derives the contact's lifecycle stage from its deal's.
    assertFalse(properties.has("lifecyclestage"));
    assertEquals("self_serve", properties.getString("etendo_camino_entrada"));
    assertEquals("Ana", properties.getString("firstname"));
    assertEquals("García López", properties.getString("lastname"));
  }

  @Test
  void aConfirmedAccountGetsItsDealInTheAccountStage() throws Exception {
    // ETP-6082: MQL "Alta la cuenta" is the account created and its address confirmed.
    Outcome outcome = sender.send(event(HubSpotOutboxStore.ACCOUNT_CREATED, null), NOW);

    assertEquals("deal-1", outcome.hubSpotObjectId());
    Call deal = call("POST", "/crm/v3/objects/deals");
    assertEquals("Ana García López — Self-serve", deal.properties().getString("dealname"));
    assertEquals("pipe", deal.properties().getString("pipeline"));
    assertEquals("mql", deal.properties().getString("dealstage"));
    assertEquals(ACCOUNT, deal.properties().getString("etendo_account_id"));
    assertEquals("trial", deal.properties().getString("etendo_estado_suscripcion"));
    assertTrue(deal.body().getJSONArray("associations").toString().contains("contact-1"));
  }

  @Test
  void aConfirmedAccountWhoseDealExistsIsNotGivenASecondOne() {
    answers = call -> call.path().endsWith("/deals/search")
        ? new Response(200, "{\"results\":[{\"id\":\"deal-9\"}]}") : ok(call);

    Outcome outcome = sender.send(event(HubSpotOutboxStore.ACCOUNT_CREATED, null), NOW);

    assertEquals("deal-9", outcome.hubSpotObjectId());
    assertTrue(calls.stream().noneMatch(c -> c.method().equals("POST")
        && c.path().equals("/crm/v3/objects/deals")));
  }

  @Test
  void theSectorChosenAtSignUpReachesTheContact() throws Exception {
    PendingEvent event = new PendingEvent("row-4", ACCOUNT, HubSpotOutboxStore.ACCOUNT_CREATED,
        CLIENT, null, 0, null, "ana@empresa.es", "Ana", null, null, "distribución_logística");

    sender.send(event, NOW);

    assertEquals("distribución_logística", call("POST", "/batch/upsert").body()
        .getJSONArray("inputs").getJSONObject(0).getJSONObject("properties")
        .getString("sector"));
  }

  @Test
  void aSectorHubSpotDoesNotKnowIsLeftOutRatherThanRefused() throws Exception {
    PendingEvent event = new PendingEvent("row-4", ACCOUNT, HubSpotOutboxStore.ACCOUNT_CREATED,
        CLIENT, null, 0, null, "ana@empresa.es", "Ana", null, null, "Industria");

    sender.send(event, NOW);

    assertFalse(call("POST", "/batch/upsert").body().getJSONArray("inputs").getJSONObject(0)
        .getJSONObject("properties").has("sector"));
  }

  @Test
  void aContactWithoutPhoneSendsNone() throws Exception {
    sender.send(event(HubSpotOutboxStore.ACCOUNT_CREATED, null), NOW);

    JSONObject properties = call("POST", "/crm/v3/objects/contacts/batch/upsert").body()
        .getJSONArray("inputs").getJSONObject(0).getJSONObject("properties");
    assertFalse(properties.has("phone"));
  }

  @Test
  void theStoredPhoneReachesTheContactWithItsPrefix() throws Exception {
    PendingEvent event = new PendingEvent("row-3", ACCOUNT, HubSpotOutboxStore.ACCOUNT_CREATED,
        CLIENT, null, 0, null, "ana@empresa.es", "Ana García López", null, "+34 600123456");

    sender.send(event, NOW);

    JSONObject contact = call("POST", "/crm/v3/objects/contacts/batch/upsert").body()
        .getJSONArray("inputs")
        .getJSONObject(0).getJSONObject("properties");
    assertEquals("+34 600123456", contact.getString("phone"));
  }

  @Test
  void aReadyDemoDatesTheTrialAndCreatesTheDealWithItsCompany() throws Exception {
    when(trials.find(CLIENT, NOW)).thenReturn(Optional.of(new DemoTrials.Trial(
        Instant.parse("2026-10-15T09:00:00Z"), Instant.parse("2026-10-29T09:00:00Z"), false)));

    Outcome outcome = sender.send(event(HubSpotOutboxStore.DEMO_READY,
        HubSpotOutbox.demoPayload("Empresa SL", "Spain")), NOW);

    assertEquals(Outcome.Kind.SENT, outcome.kind());
    assertEquals("deal-1", outcome.hubSpotObjectId());
    JSONObject contact = call("POST", "/batch/upsert").body().getJSONArray("inputs")
        .getJSONObject(0).getJSONObject("properties");
    assertEquals("2026-10-15", contact.getString("etendo_inicio_prueba"));
    assertEquals("2026-10-29", contact.getString("etendo_fin_prueba"));
    assertEquals("Spain", contact.getString("country"));
    assertFalse(contact.has("lifecyclestage"), "HubSpot derives it from the deal");

    JSONObject company = call("POST", "/crm/v3/objects/companies").properties();
    assertEquals("Empresa SL", company.getString("name"));
    assertEquals("Spain", company.getString("country"));

    Call deal = call("POST", "/crm/v3/objects/deals");
    assertEquals("Empresa SL — Self-serve", deal.properties().getString("dealname"));
    assertEquals("pipe", deal.properties().getString("pipeline"));
    assertEquals("mql", deal.properties().getString("dealstage"));
    assertEquals(ACCOUNT, deal.properties().getString("etendo_account_id"));
    assertEquals("self_serve", deal.properties().getString("etendo_camino_entrada_deal"));
    assertEquals("trial", deal.properties().getString("etendo_estado_suscripcion"));
    String associations = deal.body().getJSONArray("associations").toString();
    assertTrue(associations.contains("contact-1") && associations.contains("company-1"));
  }

  @Test
  void aDealAlreadyCreatedByAnInterruptedSendIsNotCreatedTwice() {
    answers = call -> call.path().endsWith("/deals/search")
        ? new Response(200, "{\"results\":[{\"id\":\"deal-9\"}]}") : ok(call);

    Outcome outcome = sender.send(event(HubSpotOutboxStore.DEMO_READY,
        HubSpotOutbox.demoPayload("Empresa SL", "Spain")), NOW);

    assertEquals("deal-9", outcome.hubSpotObjectId());
    assertTrue(calls.stream().noneMatch(c -> c.path().endsWith("/companies")
        || c.path().equals("/crm/v3/objects/deals")));
  }

  @Test
  void theCountryReachesContactAndCompanyInSpanishWithItsCode() throws Exception {
    sender.send(event(HubSpotOutboxStore.DEMO_READY,
        HubSpotOutbox.demoPayload("Empresa SL", "Spain", "es")), NOW);

    JSONObject contact = call("POST", "/batch/upsert").body().getJSONArray("inputs")
        .getJSONObject(0).getJSONObject("properties");
    assertEquals("España", contact.getString("country"));
    assertEquals("ES", contact.getString("hs_country_region_code"));
    assertEquals("España", call("POST", "/crm/v3/objects/companies").properties()
        .getString("country"));
  }

  @Test
  void aReadyDemoNamesTheAccountsDealAfterTheCompanyAndGivesItTheCompany() throws Exception {
    answers = call -> call.path().endsWith("/deals/search")
        ? new Response(200, "{\"results\":[{\"id\":\"deal-1\",\"properties\":"
            + "{\"pipeline\":\"pipe\"}}]}")
        : call.path().endsWith("/deals/deal-1/associations/companies")
            ? new Response(200, "{\"results\":[]}") : ok(call);

    Outcome outcome = sender.send(event(HubSpotOutboxStore.DEMO_READY,
        HubSpotOutbox.demoPayload("Empresa SL", "Spain", "ES")), NOW);

    assertEquals("deal-1", outcome.hubSpotObjectId());
    assertEquals("Empresa SL — Self-serve",
        call("PATCH", "/deals/deal-1").properties().getString("dealname"));
    call("POST", "/crm/v3/objects/companies");
    call("PUT", "/deals/deal-1/associations/default/companies/company-1");
    assertTrue(calls.stream().noneMatch(c -> c.method().equals("POST")
        && c.path().equals("/crm/v3/objects/deals")));
  }

  @Test
  void aRetriedReadyDemoDoesNotCreateASecondCompany() {
    answers = call -> call.path().endsWith("/deals/search")
        ? new Response(200, "{\"results\":[{\"id\":\"deal-1\",\"properties\":"
            + "{\"pipeline\":\"pipe\"}}]}")
        : call.path().endsWith("/deals/deal-1/associations/companies")
            ? new Response(200, "{\"results\":[{\"id\":\"company-1\"}]}") : ok(call);

    sender.send(event(HubSpotOutboxStore.DEMO_READY,
        HubSpotOutbox.demoPayload("Empresa SL", "Spain", "ES")), NOW);

    assertTrue(calls.stream().noneMatch(c -> c.path().endsWith("/companies")
        && c.method().equals("POST")));
  }

  @Test
  void aRefusedCompanyLeavesTheDealWithoutOne() throws Exception {
    answers = call -> call.path().endsWith("/companies")
        ? new Response(403, "{\"category\":\"MISSING_SCOPES\"}") : ok(call);

    Outcome outcome = sender.send(event(HubSpotOutboxStore.DEMO_READY,
        HubSpotOutbox.demoPayload("Empresa SL", "Spain")), NOW);

    assertEquals(Outcome.Kind.SENT, outcome.kind());
    assertFalse(call("POST", "/crm/v3/objects/deals").body().getJSONArray("associations")
        .toString().contains("company"));
  }

  @Test
  void enteringTheDemoOnlyFlagsTheContact() throws Exception {
    // ETP-6082: the deal is activated by what the owner does in the demo, not by entering it.
    when(store.findDealId(ACCOUNT)).thenReturn("deal-1");

    Outcome outcome = sender.send(event(HubSpotOutboxStore.DEMO_ENTERED, null), NOW);

    assertEquals(Outcome.Kind.SENT, outcome.kind());
    Call contact = call("PATCH", "/crm/v3/objects/contacts/ana%40empresa.es?idProperty=email");
    assertEquals("si", contact.properties().getString("etendo_prueba_activada"));
    assertTrue(calls.stream().noneMatch(c -> c.path().contains("/deals")));
  }

  @Test
  void theFirstMilestoneActivatesTheDeal() throws Exception {
    when(store.findDealId(ACCOUNT)).thenReturn("deal-1");
    answers = dealIn("pipe", "mql");

    sender.send(event(HubSpotOutboxStore.MILESTONE,
        "{\"milestone\":\"product\",\"at\":\"2026-10-03T09:15:00Z\"}"), NOW);

    JSONObject deal = call("PATCH", "/deals/deal-1").properties();
    assertEquals("sql", deal.getString("dealstage"));
    assertEquals("2026-10-03T09:15:00Z", deal.getString("etendo_fecha_primer_producto"));
  }

  @Test
  void aMilestoneNeverTakesADealBack() throws Exception {
    when(store.findDealId(ACCOUNT)).thenReturn("deal-1");
    answers = dealIn("pipe", "card");

    Outcome outcome = sender.send(event(HubSpotOutboxStore.MILESTONE,
        "{\"milestone\":\"order\",\"at\":\"2026-10-03T09:15:00Z\"}"), NOW);

    assertEquals(Outcome.Kind.SENT, outcome.kind());
    JSONObject deal = call("PATCH", "/deals/deal-1").properties();
    assertFalse(deal.has("dealstage"), "a deal past SQL keeps its stage");
    assertEquals("2026-10-03T09:15:00Z", deal.getString("etendo_fecha_primer_pedido"));
  }

  @Test
  void aLaterMilestoneOnlyDatesTheDeal() throws Exception {
    when(store.findDealId(ACCOUNT)).thenReturn("deal-1");
    answers = dealIn("pipe", "sql");

    sender.send(event(HubSpotOutboxStore.MILESTONE,
        "{\"milestone\":\"invoice_posted\",\"at\":\"2026-10-03T09:15:00Z\"}"), NOW);

    JSONObject deal = call("PATCH", "/deals/deal-1").properties();
    assertEquals("2026-10-03T09:15:00Z",
        deal.getString("etendo_fecha_primera_factura_contabilizada"));
    assertFalse(deal.has("dealstage"), "already activated");
  }

  @Test
  void aMonthlyPaymentPutsTheDealInTheCardStageWithPlanAndInterval() throws Exception {
    when(store.findDealId(ACCOUNT)).thenReturn("deal-1");
    answers = dealIn("pipe", "sql");

    sender.send(event(HubSpotOutboxStore.PAID,
        "{\"plan\":\"core\",\"interval\":\"monthly\"}"), NOW);

    JSONObject deal = call("PATCH", "/deals/deal-1").properties();
    assertEquals("card", deal.getString("dealstage"));
    assertEquals("core", deal.getString("etendo_plan"));
    assertEquals("monthly", deal.getString("etendo_periodicidad"));
  }

  @Test
  void anAnnualPaymentGoesStraightToTheFirstPaymentStage() throws Exception {
    // ETP-6082: the year is paid up front, so there is no renewal to wait for.
    when(store.findDealId(ACCOUNT)).thenReturn("deal-1");
    answers = dealIn("pipe", "sql");

    sender.send(event(HubSpotOutboxStore.PAID,
        "{\"plan\":\"core\",\"interval\":\"annual\"}"), NOW);

    JSONObject deal = call("PATCH", "/deals/deal-1").properties();
    assertEquals("first", deal.getString("dealstage"));
    assertEquals("annual", deal.getString("etendo_periodicidad"));
  }

  @Test
  void aPaymentAfterTheTrialExpiredStillReachesTheCardStage() throws Exception {
    when(store.findDealId(ACCOUNT)).thenReturn("deal-1");
    answers = dealIn("pipe", "expired");

    sender.send(event(HubSpotOutboxStore.PAID, "{\"plan\":\"pro\"}"), NOW);

    assertEquals("card", call("PATCH", "/deals/deal-1").properties().getString("dealstage"));
  }

  @Test
  void aPaymentOnAMarketingDealWritesPlanAndIntervalButNotTheStage() throws Exception {
    when(store.findDealId(ACCOUNT)).thenReturn("deal-1");
    answers = dealIn("sales-pipeline", "appointmentscheduled");

    sender.send(event(HubSpotOutboxStore.PAID,
        "{\"plan\":\"pro\",\"interval\":\"monthly\"}"), NOW);

    JSONObject deal = call("PATCH", "/deals/deal-1").properties();
    assertEquals("pro", deal.getString("etendo_plan"));
    assertEquals("monthly", deal.getString("etendo_periodicidad"));
    assertFalse(deal.has("dealstage"), "a marketing deal's stage is marketing's");
  }

  @Test
  void aPlanHubSpotDoesNotKnowIsLeftOutRatherThanRefused() {
    when(store.findDealId(ACCOUNT)).thenReturn("deal-1");
    answers = dealIn("pipe", "mql");

    sender.send(event(HubSpotOutboxStore.PAID, "{\"plan\":\"legacy-productive\"}"), NOW);

    assertFalse(call("PATCH", "/deals/deal-1").properties().has("etendo_plan"));
  }

  @Test
  void anExpiredTrialMovesTheDealToTheExpiredStage() throws Exception {
    when(store.findDealId(ACCOUNT)).thenReturn("deal-1");
    answers = dealIn("pipe", "sql");

    sender.send(event(HubSpotOutboxStore.DEMO_EXPIRED, null), NOW);

    assertEquals("expired", call("PATCH", "/deals/deal-1").properties()
        .getString("dealstage"));
  }

  @Test
  void anExpiryLeavesADealThatAlreadyHasACardWhereItIs() {
    when(store.findDealId(ACCOUNT)).thenReturn("deal-1");
    answers = dealIn("pipe", "card");

    sender.send(event(HubSpotOutboxStore.DEMO_EXPIRED, null), NOW);

    assertTrue(calls.stream().noneMatch(c -> c.method().equals("PATCH")));
  }

  @Test
  void aDealEventWaitsForTheDealStillBeingCreated() {
    when(store.findStatus(ACCOUNT, HubSpotOutboxStore.DEMO_READY)).thenReturn("PENDING");

    Outcome outcome = sender.send(event(HubSpotOutboxStore.DEMO_EXPIRED, null), NOW);

    assertEquals(Outcome.Kind.DEFERRED, outcome.kind());
    assertTrue(calls.isEmpty());
  }

  @Test
  void aDealEventWithNoDealToMoveIsSkipped() {
    when(store.findStatus(ACCOUNT, HubSpotOutboxStore.DEMO_READY)).thenReturn("FAILED");

    assertEquals(Outcome.Kind.SKIPPED,
        sender.send(event(HubSpotOutboxStore.PAID, null), NOW).kind());
  }

  @Test
  void aRefusalIsFinalButThrottlingAndOutagesAreRetried() {
    answers = call -> new Response(400, "{\"message\":\"bad property\"}");
    Outcome refused = sender.send(event(HubSpotOutboxStore.ACCOUNT_CREATED, null), NOW);
    assertEquals(Outcome.Kind.FAILED, refused.kind());
    assertTrue(refused.detail().contains("HTTP 400") && refused.detail().contains("bad property"));

    answers = call -> new Response(429, "");
    assertEquals(Outcome.Kind.RETRY,
        sender.send(event(HubSpotOutboxStore.ACCOUNT_CREATED, null), NOW).kind());

    answers = call -> new Response(401, "");
    assertEquals(Outcome.Kind.RETRY,
        sender.send(event(HubSpotOutboxStore.ACCOUNT_CREATED, null), NOW).kind());
  }

  @Test
  void noAnswerAtAllIsRetried() throws Exception {
    HubSpotClient unreachable = mock(HubSpotClient.class);
    when(unreachable.send(anyString(), anyString(), any()))
        .thenThrow(new HubSpotUnreachableException("timed out", null));
    HubSpotEventSender offline = new HubSpotEventSender(unreachable, CONFIG, store, trials);

    Outcome outcome = offline.send(event(HubSpotOutboxStore.ACCOUNT_CREATED, null), NOW);

    assertEquals(Outcome.Kind.RETRY, outcome.kind());
    assertEquals("timed out", outcome.detail());
  }

  @Test
  void anUnknownEventIsSkipped() {
    assertEquals(Outcome.Kind.SKIPPED, sender.send(event("SOMETHING_ELSE", null), NOW).kind());
    assertTrue(calls.isEmpty());
  }

  @Test
  void aDemoWithoutTrialOrCompanyStillGetsADealNamedAfterTheAddress() throws Exception {
    when(trials.find(CLIENT, NOW)).thenReturn(Optional.empty());

    sender.send(event(HubSpotOutboxStore.DEMO_READY, null), NOW);

    assertNull(call("POST", "/batch/upsert").body().getJSONArray("inputs").getJSONObject(0)
        .getJSONObject("properties").optString("etendo_inicio_prueba", null));
    assertTrue(calls.stream().noneMatch(c -> c.path().endsWith("/companies")));
    assertEquals("ana@empresa.es — Self-serve",
        call("POST", "/crm/v3/objects/deals").properties().getString("dealname"));
  }

  // --- Billing events: each lands on the deal of the productive its subscription pays for ---

  private static final String SUBSCRIPTION = "sub_1";
  private static final String PRODUCTIVE = "P1";
  private static final Instant OCCURRED = Instant.parse("2026-10-20T10:00:00Z");

  private static PendingEvent billing(String type, String extraPayload) {
    String payload = "{\"subscriptionId\":\"" + SUBSCRIPTION + "\",\"at\":\"" + OCCURRED + "\""
        + (extraPayload == null ? "" : "," + extraPayload) + "}";
    return new PendingEvent("row-2", ACCOUNT, type, null, payload, 0, null, "ana@empresa.es",
        "Ana García López", OCCURRED);
  }

  private void productiveWithKnownDeal(String dealId, boolean owned) {
    when(store.findSubscriptionEnvironment(SUBSCRIPTION)).thenReturn(Optional.of(
        new SubscriptionEnvironment(PRODUCTIVE, "Empresa SL", "core")));
    when(store.findEnvironmentDeal(ACCOUNT, PRODUCTIVE))
        .thenReturn(Optional.of(new EnvironmentDeal(dealId, owned)));
  }

  @Test
  void aPaymentOnTheFirstProductiveTakesTheDemosDealAndWinsIt() throws Exception {
    when(store.findSubscriptionEnvironment(SUBSCRIPTION)).thenReturn(Optional.of(
        new SubscriptionEnvironment(PRODUCTIVE, "Empresa SL", "core")));
    when(store.findEnvironmentDeal(ACCOUNT, PRODUCTIVE)).thenReturn(Optional.empty());
    when(store.findDealId(ACCOUNT)).thenReturn("deal-1");

    Outcome outcome = sender.send(billing(HubSpotOutboxStore.PAYMENT,
        "\"amount\":\"22.00\",\"currency\":\"EUR\",\"interval\":\"monthly\""), NOW);

    assertEquals(Outcome.Kind.SENT, outcome.kind());
    assertEquals("deal-1", outcome.hubSpotObjectId());
    verify(store).assignEnvironment("row-2", PRODUCTIVE);
    verify(store).recordEnvironmentDeal(ACCOUNT, PRODUCTIVE, new EnvironmentDeal("deal-1", true));
    JSONObject deal = call("PATCH", "/deals/deal-1").properties();
    assertEquals("active", deal.getString("etendo_estado_suscripcion"));
    assertEquals("2026-10-20", deal.getString("etendo_fecha_ultimo_pago"));
    assertEquals("22.00", deal.getString("amount"));
    assertEquals("monthly", deal.getString("etendo_periodicidad"));
    assertEquals("core", deal.getString("etendo_plan"));
  }

  @Test
  void theFirstRenewalMovesTheDealToTheFirstPaymentStage() throws Exception {
    productiveWithKnownDeal("deal-1", true);
    answers = dealIn("pipe", "card");

    sender.send(billing(HubSpotOutboxStore.PAYMENT,
        "\"amount\":\"22.00\",\"billingReason\":\"subscription_cycle\""), NOW);

    assertEquals("first", call("PATCH", "/deals/deal-1").properties().getString("dealstage"));
  }

  @Test
  void theInvoiceOfAnAnnualPlanIsAlreadyTheFirstPayment() throws Exception {
    // Covers the plan whose interval the checkout did not know: Stripe's invoice says it.
    productiveWithKnownDeal("deal-1", true);
    answers = dealIn("pipe", "card");

    sender.send(billing(HubSpotOutboxStore.PAYMENT,
        "\"amount\":\"250.00\",\"interval\":\"annual\","
            + "\"billingReason\":\"subscription_create\""), NOW);

    assertEquals("first", call("PATCH", "/deals/deal-1").properties().getString("dealstage"));
  }

  @Test
  void theFirstChargeAboveZeroIsTheFirstPaymentEvenAtTheCheckout() throws Exception {
    // ETP-6082: marketing's first payment is the first charge above zero, whichever invoice it is.
    productiveWithKnownDeal("deal-1", true);
    answers = dealIn("pipe", "card");

    sender.send(billing(HubSpotOutboxStore.PAYMENT,
        "\"amount\":\"25.00\",\"interval\":\"monthly\","
            + "\"billingReason\":\"subscription_create\""), NOW);

    assertEquals("first", call("PATCH", "/deals/deal-1").properties().getString("dealstage"));
  }

  @Test
  void aPaymentNeverTakesADealBackFromTheFirstPaymentStage() throws Exception {
    productiveWithKnownDeal("deal-1", true);
    answers = dealIn("pipe", "first");

    sender.send(billing(HubSpotOutboxStore.PAYMENT, "\"amount\":\"25.00\""), NOW);

    assertFalse(call("PATCH", "/deals/deal-1").properties().has("dealstage"));
  }

  @Test
  void aFurtherAnnualProductiveGetsItsDealInTheFirstPaymentStage() throws Exception {
    when(store.findSubscriptionEnvironment(SUBSCRIPTION)).thenReturn(Optional.of(
        new SubscriptionEnvironment(PRODUCTIVE, "Segunda SL", "pro")));
    when(store.findEnvironmentDeal(ACCOUNT, PRODUCTIVE)).thenReturn(Optional.empty());
    when(store.findDealId(ACCOUNT)).thenReturn("deal-1");
    when(store.countEnvironmentDeals(ACCOUNT)).thenReturn(1);
    answers = call -> call.path().equals("/crm/v3/objects/deals")
        ? new Response(201, "{\"id\":\"deal-2\"}") : ok(call);

    sender.send(billing(HubSpotOutboxStore.PAYMENT, "\"interval\":\"annual\""), NOW);

    assertEquals("first", call("POST", "/crm/v3/objects/deals").properties()
        .getString("dealstage"));
  }

  @Test
  void aPaymentAfterTheCancellationReactivatesTheLostDeal() throws Exception {
    productiveWithKnownDeal("deal-1", true);
    when(store.wasCanceledBefore(ACCOUNT, PRODUCTIVE, OCCURRED)).thenReturn(true);
    answers = dealIn("pipe", "lost");

    sender.send(billing(HubSpotOutboxStore.PAYMENT,
        "\"amount\":\"25.00\",\"interval\":\"monthly\","
            + "\"billingReason\":\"subscription_create\""), NOW);

    JSONObject deal = call("PATCH", "/deals/deal-1").properties();
    assertEquals("first", deal.getString("dealstage"), "a lost customer who pays is one again");
    assertEquals("true", deal.getString("etendo_es_reactivacion"));
    assertEquals("active", deal.getString("etendo_estado_suscripcion"));
  }

  @Test
  void anAnnualReactivationGoesStraightToTheFirstPaymentStage() throws Exception {
    productiveWithKnownDeal("deal-1", true);
    when(store.wasCanceledBefore(ACCOUNT, PRODUCTIVE, OCCURRED)).thenReturn(true);
    answers = dealIn("pipe", "lost");

    sender.send(billing(HubSpotOutboxStore.PAYMENT,
        "\"amount\":\"250.00\",\"interval\":\"annual\""), NOW);

    assertEquals("first", call("PATCH", "/deals/deal-1").properties().getString("dealstage"));
  }

  @Test
  void aPaymentWithNoCancellationBeforeLeavesALostDealAlone() throws Exception {
    productiveWithKnownDeal("deal-1", true);
    answers = dealIn("pipe", "lost");

    sender.send(billing(HubSpotOutboxStore.PAYMENT,
        "\"amount\":\"22.00\",\"billingReason\":\"subscription_cycle\""), NOW);

    JSONObject deal = call("PATCH", "/deals/deal-1").properties();
    assertFalse(deal.has("dealstage"), "lost is left only by a re-subscription");
    assertFalse(deal.has("etendo_es_reactivacion"));
  }

  @Test
  void aReactivatedMarketingDealIsFlaggedButKeepsItsStage() throws Exception {
    productiveWithKnownDeal("deal-1", false);
    when(store.wasCanceledBefore(ACCOUNT, PRODUCTIVE, OCCURRED)).thenReturn(true);

    sender.send(billing(HubSpotOutboxStore.PAYMENT, "\"amount\":\"22.00\""), NOW);

    JSONObject deal = call("PATCH", "/deals/deal-1").properties();
    assertEquals("true", deal.getString("etendo_es_reactivacion"));
    assertFalse(deal.has("dealstage"));
  }

  @Test
  void aRenewalOfAMarketingDealLeavesItsStage() throws Exception {
    productiveWithKnownDeal("deal-1", false);

    sender.send(billing(HubSpotOutboxStore.PAYMENT,
        "\"amount\":\"22.00\",\"billingReason\":\"subscription_cycle\""), NOW);

    assertFalse(call("PATCH", "/deals/deal-1").properties().has("dealstage"));
    assertTrue(calls.stream().noneMatch(c -> c.method().equals("GET")));
  }

  @Test
  void aMarketingDealOnlyGetsItsPropertiesWritten() throws Exception {
    when(store.findSubscriptionEnvironment(SUBSCRIPTION)).thenReturn(Optional.of(
        new SubscriptionEnvironment(PRODUCTIVE, "Empresa SL", "core")));
    when(store.findEnvironmentDeal(ACCOUNT, PRODUCTIVE)).thenReturn(Optional.empty());
    answers = call -> call.path().endsWith("/deals/search")
        ? new Response(200, "{\"results\":[{\"id\":\"deal-7\",\"properties\":"
            + "{\"pipeline\":\"demo-pipeline\"}}]}")
        : ok(call);

    sender.send(billing(HubSpotOutboxStore.PAYMENT, null), NOW);

    verify(store).recordEnvironmentDeal(ACCOUNT, PRODUCTIVE, new EnvironmentDeal("deal-7", false));
    JSONObject deal = call("PATCH", "/deals/deal-7").properties();
    assertEquals("active", deal.getString("etendo_estado_suscripcion"));
    assertFalse(deal.has("dealstage"), "a marketing deal's stage is marketing's");
    assertFalse(deal.has("etendo_plan"));
  }

  @Test
  void aFurtherProductiveGetsItsOwnWonDealNextToTheFirstOnesCompany() throws Exception {
    when(store.findSubscriptionEnvironment(SUBSCRIPTION)).thenReturn(Optional.of(
        new SubscriptionEnvironment(PRODUCTIVE, "Segunda SL", "pro")));
    when(store.findEnvironmentDeal(ACCOUNT, PRODUCTIVE)).thenReturn(Optional.empty());
    when(store.findDealId(ACCOUNT)).thenReturn("deal-1");
    when(store.countEnvironmentDeals(ACCOUNT)).thenReturn(1);
    answers = call -> call.path().endsWith("/deals/deal-1/associations/companies")
        ? new Response(200, "{\"results\":[{\"id\":\"company-1\"}]}")
        : call.path().equals("/crm/v3/objects/deals")
            ? new Response(201, "{\"id\":\"deal-2\"}") : ok(call);

    Outcome outcome = sender.send(billing(HubSpotOutboxStore.PAYMENT, null), NOW);

    assertEquals("deal-2", outcome.hubSpotObjectId());
    Call created = call("POST", "/crm/v3/objects/deals");
    assertEquals("Segunda SL — Self-serve", created.properties().getString("dealname"));
    assertEquals("first", created.properties().getString("dealstage"),
        "only a charge above zero is reported, so a payment is the first one");
    assertEquals("pro", created.properties().getString("etendo_plan"));
    assertFalse(created.properties().has("etendo_account_id"),
        "HubSpot keeps the account id unique, and the first deal holds it");
    String associations = created.body().getJSONArray("associations").toString();
    assertTrue(associations.contains("contact-1") && associations.contains("company-1"));
    JSONObject contact = call("POST", "/batch/upsert").body().getJSONArray("inputs")
        .getJSONObject(0).getJSONObject("properties");
    assertEquals(1, contact.length(), "only the address: a marketing contact keeps its own data");
    verify(store).recordEnvironmentDeal(ACCOUNT, PRODUCTIVE, new EnvironmentDeal("deal-2", true));
    assertEquals("active", call("PATCH", "/deals/deal-2").properties()
        .getString("etendo_estado_suscripcion"));
  }

  @Test
  void aFurtherProductiveDealAlreadyCreatedByAnInterruptedSendIsReused() {
    when(store.findSubscriptionEnvironment(SUBSCRIPTION)).thenReturn(Optional.of(
        new SubscriptionEnvironment(PRODUCTIVE, "Segunda SL", null)));
    when(store.findEnvironmentDeal(ACCOUNT, PRODUCTIVE)).thenReturn(Optional.empty());
    when(store.findDealId(ACCOUNT)).thenReturn("deal-1");
    when(store.countEnvironmentDeals(ACCOUNT)).thenReturn(1);
    answers = call -> call.path().endsWith("/deals/search")
        && call.body().toString().contains("Segunda SL")
        && call.body().toString().contains("associations.contact")
        ? new Response(200, "{\"results\":[{\"id\":\"deal-2\"}]}") : ok(call);

    sender.send(billing(HubSpotOutboxStore.PAYMENT, null), NOW);

    assertTrue(calls.stream().noneMatch(c -> c.method().equals("POST")
        && c.path().equals("/crm/v3/objects/deals")));
    verify(store).recordEnvironmentDeal(ACCOUNT, PRODUCTIVE, new EnvironmentDeal("deal-2", true));
  }

  @Test
  void aBillingEventWaitsForItsEnvironmentToBeProvisioned() {
    when(store.findSubscriptionEnvironment(SUBSCRIPTION)).thenReturn(Optional.empty());

    Outcome outcome = sender.send(billing(HubSpotOutboxStore.PAYMENT, null), NOW);

    assertEquals(Outcome.Kind.RETRY, outcome.kind());
    assertTrue(calls.isEmpty());
  }

  @Test
  void aFailedFirstChargeSuspendsTheAccountsDeal() throws Exception {
    // ETP-6082: the charge that fails is the one that would have created the productive.
    when(store.findSubscriptionEnvironment(SUBSCRIPTION)).thenReturn(Optional.empty());
    when(store.findDealId(ACCOUNT)).thenReturn("deal-1");

    Outcome outcome = sender.send(billing(HubSpotOutboxStore.PAYMENT_FAILED, null), NOW);

    assertEquals(Outcome.Kind.SENT, outcome.kind());
    JSONObject deal = call("PATCH", "/deals/deal-1").properties();
    assertEquals("suspended", deal.getString("etendo_estado_suscripcion"));
    assertEquals("2026-10-20", deal.getString("etendo_fecha_cobro_fallido"));
    verify(store, never()).recordEnvironmentDeal(any(), any(), any());
  }

  @Test
  void aFailedChargeOfAFurtherPurchaseWaitsForItsEnvironment() {
    when(store.findSubscriptionEnvironment(SUBSCRIPTION)).thenReturn(Optional.empty());
    when(store.countEnvironmentDeals(ACCOUNT)).thenReturn(1);

    assertEquals(Outcome.Kind.RETRY,
        sender.send(billing(HubSpotOutboxStore.PAYMENT_FAILED, null), NOW).kind());
    assertTrue(calls.isEmpty());
  }

  @Test
  void aBillingEventOlderThanOneAlreadySentIsSkipped() {
    productiveWithKnownDeal("deal-1", true);
    when(store.hasNewerBillingEvent(ACCOUNT, PRODUCTIVE, OCCURRED)).thenReturn(true);

    Outcome outcome = sender.send(billing(HubSpotOutboxStore.PAYMENT_FAILED, null), NOW);

    assertEquals(Outcome.Kind.SKIPPED, outcome.kind());
    assertTrue(calls.isEmpty());
  }

  @Test
  void anAccountWithNoDealAnywhereIsSkippedAndNothingIsRecorded() {
    when(store.findSubscriptionEnvironment(SUBSCRIPTION)).thenReturn(Optional.of(
        new SubscriptionEnvironment(PRODUCTIVE, "Empresa SL", null)));
    when(store.findEnvironmentDeal(ACCOUNT, PRODUCTIVE)).thenReturn(Optional.empty());

    Outcome outcome = sender.send(billing(HubSpotOutboxStore.PAYMENT, null), NOW);

    assertEquals(Outcome.Kind.SKIPPED, outcome.kind());
    verify(store, never()).recordEnvironmentDeal(any(), any(), any());
  }

  @Test
  void aFailedChargeSuspendsTheSubscription() throws Exception {
    productiveWithKnownDeal("deal-1", true);

    sender.send(billing(HubSpotOutboxStore.PAYMENT_FAILED, null), NOW);

    JSONObject deal = call("PATCH", "/deals/deal-1").properties();
    assertEquals("suspended", deal.getString("etendo_estado_suscripcion"));
    assertEquals("2026-10-20", deal.getString("etendo_fecha_cobro_fallido"));
    assertFalse(deal.has("dealstage"));
  }

  @Test
  void aScheduledCancellationIsCancelledAtOnceAndDatedWhenTheAccessEnds() throws Exception {
    // ETP-6082: marketing's spec.
    productiveWithKnownDeal("deal-1", true);

    sender.send(billing(HubSpotOutboxStore.CANCEL_REQUESTED,
        "\"accessEndsAt\":\"2026-11-19T23:59:59Z\""), NOW);

    JSONObject deal = call("PATCH", "/deals/deal-1").properties();
    assertEquals("cancelled", deal.getString("etendo_estado_suscripcion"));
    assertEquals("2026-11-19", deal.getString("etendo_fecha_baja"));
    assertFalse(deal.has("dealstage"), "lost only when the subscription ends");
  }

  @Test
  void aCancellationWithoutItsAccessEndIsDatedTheDayItWasAsked() throws Exception {
    productiveWithKnownDeal("deal-1", true);

    sender.send(billing(HubSpotOutboxStore.CANCEL_REQUESTED, "\"accessEndsAt\":\"later\""),
        NOW);

    assertEquals("2026-10-20", call("PATCH", "/deals/deal-1").properties()
        .getString("etendo_fecha_baja"));
  }

  @Test
  void aWithdrawnCancellationReactivatesAndClearsTheDate() throws Exception {
    productiveWithKnownDeal("deal-1", true);
    answers = dealIn("pipe", "first");

    sender.send(billing(HubSpotOutboxStore.CANCEL_REVERTED, null), NOW);

    JSONObject deal = call("PATCH", "/deals/deal-1").properties();
    assertEquals("active", deal.getString("etendo_estado_suscripcion"));
    assertEquals("", deal.getString("etendo_fecha_baja"));
    assertFalse(deal.has("dealstage"));
  }

  @Test
  void aWithdrawnCancellationOfALostDealMakesItACustomerAgain() throws Exception {
    productiveWithKnownDeal("deal-1", true);
    answers = dealIn("pipe", "lost");

    sender.send(billing(HubSpotOutboxStore.CANCEL_REVERTED, null), NOW);

    assertEquals("first", call("PATCH", "/deals/deal-1").properties().getString("dealstage"));
  }

  @Test
  void anEndedSubscriptionKeepsTheDateTheCancellationWasAskedFor() throws Exception {
    productiveWithKnownDeal("deal-1", true);
    answers = call -> call.method().equals("GET")
        ? new Response(200, "{\"properties\":{\"pipeline\":\"pipe\",\"dealstage\":\"first\","
            + "\"etendo_fecha_baja\":\"2026-10-01\"}}")
        : ok(call);

    sender.send(billing(HubSpotOutboxStore.CANCELED, null), NOW);

    JSONObject deal = call("PATCH", "/deals/deal-1").properties();
    assertEquals("cancelled", deal.getString("etendo_estado_suscripcion"));
    assertEquals("lost", deal.getString("dealstage"));
    assertFalse(deal.has("etendo_fecha_baja"));
  }

  @Test
  void anImmediateCancellationIsDatedWhenItEnds() throws Exception {
    productiveWithKnownDeal("deal-1", true);
    // How HubSpot answers for a property that was never set.
    answers = call -> call.method().equals("GET")
        ? new Response(200, "{\"properties\":{\"etendo_fecha_baja\":null}}")
        : ok(call);

    sender.send(billing(HubSpotOutboxStore.CANCELED, null), NOW);

    assertEquals("2026-10-20", call("PATCH", "/deals/deal-1").properties()
        .getString("etendo_fecha_baja"));
  }

  // --- A booked demo: marketing's deal in the Demo pipeline (ETP-6082) ---

  private static final String DEMO_PIPELINE = HubSpotConfiguration.DEFAULT_DEMO_PIPELINE;

  @Test
  void aBookedDemoClaimsMarketingsDealInsteadOfCreatingOne() throws Exception {
    Outcome outcome = sender.send(event(HubSpotOutboxStore.DEMO_READY,
        "{\"companyName\":\"Empresa SL\",\"country\":\"España\",\"countryCode\":\"ES\","
            + "\"hubspotDealId\":\"555\"}"), NOW);

    assertEquals(Outcome.Kind.SENT, outcome.kind());
    assertEquals("555", outcome.hubSpotObjectId());
    call("GET", "/deals/555?properties=pipeline");
    JSONObject deal = call("PATCH", "/deals/555").properties();
    assertEquals(ACCOUNT, deal.getString("etendo_account_id"));
    assertEquals(1, deal.length(), "name, company and stage are marketing's");
    assertTrue(calls.stream().noneMatch(c -> c.method().equals("POST")
        && (c.path().endsWith("/deals") || c.path().endsWith("/companies"))));
    JSONObject contact = call("POST", "/batch/upsert").body().getJSONArray("inputs")
        .getJSONObject(0).getJSONObject("properties");
    assertFalse(contact.has("etendo_camino_entrada"), "a booked demo's way in is marketing's");
    assertEquals("España", contact.getString("country"));
  }

  @Test
  void aBookedDealThatDoesNotExistIsRefused() {
    answers = call -> call.method().equals("GET")
        ? new Response(404, "{\"message\":\"not found\"}") : ok(call);

    Outcome outcome = sender.send(event(HubSpotOutboxStore.DEMO_READY,
        "{\"companyName\":\"Empresa SL\",\"hubspotDealId\":\"999\"}"), NOW);

    assertEquals(Outcome.Kind.FAILED, outcome.kind());
    assertTrue(calls.stream().noneMatch(c -> c.method().equals("PATCH")));
  }

  @Test
  void theFirstMilestoneOfADemoAfterItTookPlaceMovesItToTheDemoSql() throws Exception {
    when(store.findDealId(ACCOUNT)).thenReturn("555");
    answers = dealIn(DEMO_PIPELINE, HubSpotConfiguration.DEFAULT_DEMO_ENTRY_STAGE);

    sender.send(event(HubSpotOutboxStore.MILESTONE, "{\"milestone\":\"invoice\"}"), NOW);

    assertEquals(HubSpotConfiguration.DEFAULT_DEMO_ACTIVATED_STAGE,
        call("PATCH", "/deals/555").properties().getString("dealstage"));
  }

  @Test
  void aMilestoneBeforeTheDemoTookPlaceLeavesItsStageToMarketing() throws Exception {
    // Vico uses the environment during the demo; only marketing moves it to "Demo realizada".
    when(store.findDealId(ACCOUNT)).thenReturn("555");
    answers = dealIn(DEMO_PIPELINE, "1471455756");

    sender.send(event(HubSpotOutboxStore.MILESTONE, "{\"milestone\":\"invoice\"}"), NOW);

    assertFalse(call("PATCH", "/deals/555").properties().has("dealstage"));
  }

  @Test
  void aDemosPaymentMovesItWithTheDemoPipelinesStages() throws Exception {
    productiveWithKnownDeal("555", true);
    answers = dealIn(DEMO_PIPELINE, HubSpotConfiguration.DEFAULT_DEMO_CARD_STAGE);

    sender.send(billing(HubSpotOutboxStore.PAYMENT, "\"amount\":\"25.00\""), NOW);

    assertEquals(HubSpotConfiguration.DEFAULT_DEMO_FIRST_PAYMENT_STAGE,
        call("PATCH", "/deals/555").properties().getString("dealstage"));
  }

  @Test
  void aDemosFurtherProductiveGetsItsDealInTheDemoPipeline() throws Exception {
    when(store.findSubscriptionEnvironment(SUBSCRIPTION)).thenReturn(Optional.of(
        new SubscriptionEnvironment(PRODUCTIVE, "Segunda SL", "pro")));
    when(store.findEnvironmentDeal(ACCOUNT, PRODUCTIVE)).thenReturn(Optional.empty());
    when(store.findDealId(ACCOUNT)).thenReturn("555");
    when(store.countEnvironmentDeals(ACCOUNT)).thenReturn(1);
    Function<Call, Response> demoDeal = dealIn(DEMO_PIPELINE,
        HubSpotConfiguration.DEFAULT_DEMO_FIRST_PAYMENT_STAGE);
    answers = call -> call.path().equals("/crm/v3/objects/deals")
        ? new Response(201, "{\"id\":\"deal-2\"}") : demoDeal.apply(call);

    sender.send(billing(HubSpotOutboxStore.PAYMENT_FAILED, null), NOW);

    JSONObject created = call("POST", "/crm/v3/objects/deals").properties();
    assertEquals(DEMO_PIPELINE, created.getString("pipeline"));
    assertEquals(HubSpotConfiguration.DEFAULT_DEMO_CARD_STAGE, created.getString("dealstage"));
    assertFalse(created.has("etendo_camino_entrada_deal"));
  }
}
