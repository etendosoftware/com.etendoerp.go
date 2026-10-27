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
      "https://hubspot.test", "pipe", "trial", "active", "won", "lost");

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
  void aNewAccountBecomesASelfServeLead() throws Exception {
    Outcome outcome = sender.send(event(HubSpotOutboxStore.ACCOUNT_CREATED, null), NOW);

    assertEquals(Outcome.Kind.SENT, outcome.kind());
    JSONObject input = call("POST", "/crm/v3/objects/contacts/batch/upsert").body()
        .getJSONArray("inputs").getJSONObject(0);
    assertEquals("email", input.getString("idProperty"));
    assertEquals("ana@empresa.es", input.getString("id"));
    JSONObject properties = input.getJSONObject("properties");
    assertEquals("lead", properties.getString("lifecyclestage"));
    assertEquals("self_serve", properties.getString("etendo_camino_entrada"));
    assertEquals("Ana", properties.getString("firstname"));
    assertEquals("García López", properties.getString("lastname"));
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
    assertFalse(contact.has("lifecyclestage"), "lifecycle stage is set once, at sign-up");

    JSONObject company = call("POST", "/crm/v3/objects/companies").properties();
    assertEquals("Empresa SL", company.getString("name"));
    assertEquals("Spain", company.getString("country"));

    Call deal = call("POST", "/crm/v3/objects/deals");
    assertEquals("Empresa SL — Self-serve", deal.properties().getString("dealname"));
    assertEquals("pipe", deal.properties().getString("pipeline"));
    assertEquals("trial", deal.properties().getString("dealstage"));
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
  void enteringTheDemoActivatesTheTrialAndMovesTheDeal() throws Exception {
    when(store.findDealId(ACCOUNT)).thenReturn("deal-1");

    Outcome outcome = sender.send(event(HubSpotOutboxStore.DEMO_ENTERED, null), NOW);

    assertEquals(Outcome.Kind.SENT, outcome.kind());
    Call contact = call("PATCH", "/crm/v3/objects/contacts/ana%40empresa.es?idProperty=email");
    assertEquals("si", contact.properties().getString("etendo_prueba_activada"));
    assertEquals("active", call("PATCH", "/deals/deal-1").properties().getString("dealstage"));
  }

  @Test
  void enteringTheDemoAfterPayingLeavesTheDealWon() {
    when(store.findStatus(ACCOUNT, HubSpotOutboxStore.PAID)).thenReturn("SENT");

    sender.send(event(HubSpotOutboxStore.DEMO_ENTERED, null), NOW);

    assertTrue(calls.stream().noneMatch(c -> c.path().contains("/deals/")));
  }

  @Test
  void aPaymentWinsTheDealWithThePlanChosen() throws Exception {
    when(store.findDealId(ACCOUNT)).thenReturn("deal-1");

    sender.send(event(HubSpotOutboxStore.PAID, "{\"plan\":\"core\"}"), NOW);

    JSONObject deal = call("PATCH", "/deals/deal-1").properties();
    assertEquals("won", deal.getString("dealstage"));
    assertEquals("core", deal.getString("etendo_plan"));
  }

  @Test
  void aPlanHubSpotDoesNotKnowIsLeftOutRatherThanRefused() {
    when(store.findDealId(ACCOUNT)).thenReturn("deal-1");

    sender.send(event(HubSpotOutboxStore.PAID, "{\"plan\":\"legacy-productive\"}"), NOW);

    assertFalse(call("PATCH", "/deals/deal-1").properties().has("etendo_plan"));
  }

  @Test
  void anExpiredTrialLosesTheDeal() throws Exception {
    when(store.findDealId(ACCOUNT)).thenReturn("deal-1");

    sender.send(event(HubSpotOutboxStore.DEMO_EXPIRED, null), NOW);

    assertEquals("lost", call("PATCH", "/deals/deal-1").properties().getString("dealstage"));
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
    assertEquals("won", deal.getString("dealstage"));
    assertEquals("core", deal.getString("etendo_plan"));
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
    assertEquals("won", created.properties().getString("dealstage"));
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
  void aScheduledCancellationIsDatedTheDayItWasAskedAndStaysActive() throws Exception {
    productiveWithKnownDeal("deal-1", true);

    sender.send(billing(HubSpotOutboxStore.CANCEL_REQUESTED, null), NOW);

    JSONObject deal = call("PATCH", "/deals/deal-1").properties();
    assertEquals("2026-10-20", deal.getString("etendo_fecha_baja"));
    assertFalse(deal.has("etendo_estado_suscripcion"), "the owner keeps access until the end");
  }

  @Test
  void aWithdrawnCancellationReactivatesAndClearsTheDate() throws Exception {
    productiveWithKnownDeal("deal-1", true);

    sender.send(billing(HubSpotOutboxStore.CANCEL_REVERTED, null), NOW);

    JSONObject deal = call("PATCH", "/deals/deal-1").properties();
    assertEquals("active", deal.getString("etendo_estado_suscripcion"));
    assertEquals("", deal.getString("etendo_fecha_baja"));
  }

  @Test
  void anEndedSubscriptionKeepsTheDateTheCancellationWasAskedFor() throws Exception {
    productiveWithKnownDeal("deal-1", true);
    answers = call -> call.method().equals("GET")
        ? new Response(200, "{\"properties\":{\"etendo_fecha_baja\":\"2026-10-01\"}}")
        : ok(call);

    sender.send(billing(HubSpotOutboxStore.CANCELED, null), NOW);

    JSONObject deal = call("PATCH", "/deals/deal-1").properties();
    assertEquals("cancelled", deal.getString("etendo_estado_suscripcion"));
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

  @Test
  void enteringTheDemoAfterABillingPaymentLeavesTheDealWon() {
    when(store.findStatus(ACCOUNT, HubSpotOutboxStore.PAYMENT)).thenReturn("SENT");

    sender.send(event(HubSpotOutboxStore.DEMO_ENTERED, null), NOW);

    assertTrue(calls.stream().noneMatch(c -> c.path().contains("/deals/")));
  }
}
