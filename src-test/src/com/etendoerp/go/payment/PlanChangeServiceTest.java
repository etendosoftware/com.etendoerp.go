/*
 * *************************************************************************
 * Etendo License. See https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * *************************************************************************
 */
package com.etendoerp.go.payment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;
import java.util.Optional;

import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import com.etendoerp.go.schemaforge.data.Plan;
import com.etendoerp.go.schemaforge.data.PlanChange;
import com.etendoerp.go.schemaforge.data.Subscription;

/**
 * ETP-5053 — the plan change service: every refusal has a code, an upgrade updates the item and
 * never moves the billing anchor, a downgrade schedules a phase at the period end, and repeating
 * a request never schedules or prorates twice.
 *
 * @covers com.etendoerp.go.payment.PlanChangeService
 * @covers com.etendoerp.go.payment.PlanChangeRefusedException
 * @covers com.etendoerp.go.payment.PlanChangeResult
 * @covers com.etendoerp.go.payment.PlanChangePreview
 */
class PlanChangeServiceTest {

  private static final String CLIENT_ID = "48F0981053084BC49CCEEFEC296E2A3D";
  private static final String SUB_ID = "sub_123";
  private static final Instant NOW = Instant.parse("2026-10-31T10:00:00Z");
  private static final long PERIOD_START = 1_761_955_200L; // 2026-11-01T00:00:00Z
  private static final long PERIOD_END = 1_764_547_200L; // 2026-12-01T00:00:00Z

  private final SubscriptionService subscriptionService = mock(SubscriptionService.class);
  private final PlanCatalogService planCatalogService = mock(PlanCatalogService.class);
  private final PlanChangeCatalog planChangeCatalog = mock(PlanChangeCatalog.class);
  private final TenantEnvironmentLifecycleService lifecycle =
      mock(TenantEnvironmentLifecycleService.class);
  private final StripeApiClient stripe = mock(StripeApiClient.class);
  private boolean configured = true;
  private final PlanChangeService service = new PlanChangeService(subscriptionService,
      planCatalogService, planChangeCatalog, lifecycle, stripe, () -> configured,
      Clock.fixed(NOW, ZoneOffset.UTC));

  private Plan core;
  private Plan pro;
  private Subscription open;

  private static Plan plan(String id, String key, String priceId) {
    Plan plan = mock(Plan.class);
    when(plan.getId()).thenReturn(id);
    when(plan.getSearchKey()).thenReturn(key);
    when(plan.getProviderPriceID()).thenReturn(priceId);
    when(plan.getBillingInterval()).thenReturn("month");
    return plan;
  }

  private static PlanChange rule(String timing) {
    PlanChange change = mock(PlanChange.class);
    when(change.getTiming()).thenReturn(timing);
    return change;
  }

  private static StripeResponse ok(String json) {
    return new StripeResponse(200, json);
  }

  private static String liveSubscription(String price, String scheduleJson) {
    return "{\"id\":\"" + SUB_ID + "\",\"status\":\"active\",\"current_period_start\":"
        + PERIOD_START + ",\"current_period_end\":" + PERIOD_END + ",\"items\":{\"data\":[{\"id\":"
        + "\"si_1\",\"price\":{\"id\":\"" + price + "\"}}]}"
        + (scheduleJson == null ? "" : ",\"schedule\":" + scheduleJson) + "}";
  }

  @BeforeEach
  void setUp() throws Exception {
    core = plan("CORE", "core", "price_core");
    pro = plan("PRO", "pro", "price_pro");
    open = mock(Subscription.class);
    when(open.getId()).thenReturn("ROW");
    when(open.getPlan()).thenReturn(core);
    when(open.getSubscriptionStatus()).thenReturn(SubscriptionService.STATUS_ACTIVE);
    when(open.getStripeSubscription()).thenReturn(SUB_ID);
    when(subscriptionService.findOpen(CLIENT_ID)).thenReturn(Optional.of(open));
    when(planCatalogService.findPurchasablePlan("pro")).thenReturn(Optional.of(pro));
    when(planCatalogService.findPurchasablePlan("core")).thenReturn(Optional.of(core));
    when(planCatalogService.hasProviderPrice(any())).thenReturn(true);
    when(stripe.get(startsWith("/v1/subscriptions/")))
        .thenReturn(ok(liveSubscription("price_core", null)));
  }

  private PlanChangeRefusedException refusal(String planKey) {
    return assertThrows(PlanChangeRefusedException.class,
        () -> service.requestChange(CLIENT_ID, planKey));
  }

  // ---------------------------------------------------------------- refusals

  @Test
  void aTenantWithoutAnOpenRowIsRefused() {
    when(subscriptionService.findOpen(CLIENT_ID)).thenReturn(Optional.empty());

    assertEquals(PlanChangeRefusedException.NO_SUBSCRIPTION, refusal("pro").code());
  }

  @Test
  void aPastDueOrCanceledSubscriptionIsRefusedBeforeAnyProviderCall() throws Exception {
    when(open.getSubscriptionStatus()).thenReturn(SubscriptionService.STATUS_PAST_DUE);
    assertEquals(PlanChangeRefusedException.SUBSCRIPTION_NOT_CURRENT, refusal("pro").code());

    when(open.getSubscriptionStatus()).thenReturn(SubscriptionService.STATUS_ACTIVE);
    when(open.getEndDate()).thenReturn(new Date()); // closed reads as canceled
    assertEquals(PlanChangeRefusedException.SUBSCRIPTION_NOT_CURRENT, refusal("pro").code());

    verify(stripe, never()).get(anyString());
    verify(stripe, never()).postForm(anyString(), anyString(), any());
  }

  @Test
  void anUnknownPlanKeyIsRefused() {
    when(planCatalogService.findPurchasablePlan("gold")).thenReturn(Optional.empty());

    assertEquals(PlanChangeRefusedException.PLAN_NOT_FOUND, refusal("gold").code());
  }

  @Test
  void aChangeWithNoActiveAllowedRowIsRefusedAndTheSamePlanTooBecauseItHasNone() {
    when(planChangeCatalog.findAllowed(core, pro)).thenReturn(Optional.empty());
    when(planChangeCatalog.findAllowed(core, core)).thenReturn(Optional.empty());

    assertEquals(PlanChangeRefusedException.PLAN_CHANGE_NOT_ALLOWED, refusal("pro").code());
    assertEquals(PlanChangeRefusedException.PLAN_CHANGE_NOT_ALLOWED, refusal("core").code());
  }

  @Test
  void aTargetWithoutAProviderPriceOrAnUnconfiguredProviderIsRefused() {
    PlanChange rule1 = rule("immediate");
    when(planChangeCatalog.findAllowed(core, pro)).thenReturn(Optional.of(rule1));
    when(planCatalogService.hasProviderPrice(pro)).thenReturn(false);
    assertEquals(PlanChangeRefusedException.NOT_CONFIGURED, refusal("pro").code());

    when(planCatalogService.hasProviderPrice(pro)).thenReturn(true);
    configured = false;
    assertEquals(PlanChangeRefusedException.NOT_CONFIGURED, refusal("pro").code());
  }

  @Test
  void aRowWithoutAProviderSubscriptionIsRefused() {
    PlanChange rule2 = rule("immediate");
    when(planChangeCatalog.findAllowed(core, pro)).thenReturn(Optional.of(rule2));
    when(open.getStripeSubscription()).thenReturn("  ");

    assertEquals(PlanChangeRefusedException.NO_SUBSCRIPTION, refusal("pro").code());
  }

  @Test
  void aProviderRefusalIsACodedRefusalNeverAnUnhandledError() throws Exception {
    PlanChange rule3 = rule("immediate");
    when(planChangeCatalog.findAllowed(core, pro)).thenReturn(Optional.of(rule3));
    when(stripe.postForm(startsWith("/v1/subscriptions/"), anyString(), anyString()))
        .thenReturn(new StripeResponse(402, "{\"error\":{\"code\":\"card_declined\"}}"));

    PlanChangeRefusedException refused = refusal("pro");

    assertEquals(PlanChangeRefusedException.PROVIDER_REJECTED, refused.code());
    assertTrue(refused.getMessage().contains("card_declined"), refused.getMessage());
  }

  // ---------------------------------------------------------------- upgrade

  @Test
  void anUpgradeUpdatesTheItemWithProrationAndNeverMovesTheBillingAnchor() throws Exception {
    PlanChange rule4 = rule("immediate");
    when(planChangeCatalog.findAllowed(core, pro)).thenReturn(Optional.of(rule4));
    when(stripe.postForm(startsWith("/v1/subscriptions/"), anyString(), anyString()))
        .thenReturn(ok(liveSubscription("price_pro", null)));
    TenantEnvironmentLifecycleService.LifecycleTarget target = LifecycleTargetFixtures.row(
        CLIENT_ID, open, SubscriptionLifecycleApplier.StoredState.NONE);
    when(lifecycle.targetForSubscription(SUB_ID)).thenReturn(target);
    when(lifecycle.applySubscriptionEvent(eq(target), any(), eq(NOW))).thenReturn(true);

    PlanChangeResult result;
    try (MockedStatic<SystemContext> system = Mockito.mockStatic(SystemContext.class)) {
      system.when(() -> SystemContext.run(anyString(), any())).thenAnswer(invocation -> {
        ((Runnable) invocation.getArgument(1)).run();
        return null;
      });
      result = service.requestChange(CLIENT_ID, "pro");
    }

    assertEquals(PlanChangeTiming.IMMEDIATE, result.timing());
    assertSame(pro, result.targetPlan());
    assertEquals(NOW, result.effectiveAt());
    assertFalse(result.alreadyPending());
    ArgumentCaptor<String> form = ArgumentCaptor.forClass(String.class);
    verify(stripe).postForm(eq("/v1/subscriptions/" + SUB_ID), form.capture(),
        eq("plan-change:ROW:PRO:" + NOW.getEpochSecond()));
    String body = form.getValue();
    assertTrue(body.contains("items%5B0%5D%5Bid%5D=si_1"), body);
    assertTrue(body.contains("items%5B0%5D%5Bprice%5D=price_pro"), body);
    assertTrue(body.contains("proration_behavior=always_invoice"), body);
    assertTrue(body.contains("proration_date=" + NOW.getEpochSecond()), body);
    // Applied only once paid: an unpaid upgrade never changes the plan on credit.
    assertTrue(body.contains("payment_behavior=pending_if_incomplete"), body);
    assertTrue(body.contains("expand%5B%5D=latest_invoice"), body);
    assertFalse(result.paymentRequired());
    assertFalse(body.contains("billing_cycle_anchor"), "PRD §8.2: " + body);
    // The provider's answer is applied through the lifecycle path, so the row rolls over now.
    ArgumentCaptor<SubscriptionEventOutcome> outcome =
        ArgumentCaptor.forClass(SubscriptionEventOutcome.class);
    verify(lifecycle).applySubscriptionEvent(eq(target), outcome.capture(), eq(NOW));
    assertEquals("price_pro", outcome.getValue().providerPriceId());
    assertEquals(Instant.ofEpochSecond(PERIOD_END), outcome.getValue().periodEnd());
    verify(stripe, never()).postForm(startsWith("/v1/subscription_schedules"), anyString());
  }

  @Test
  void anUpgradeOverAPendingDowngradeReleasesTheScheduleAndClearsThePendingColumns()
      throws Exception {
    PlanChange rule5 = rule("immediate");
    when(planChangeCatalog.findAllowed(core, pro)).thenReturn(Optional.of(rule5));
    when(stripe.get(startsWith("/v1/subscriptions/")))
        .thenReturn(ok(liveSubscription("price_core", "\"sub_sched_1\"")));
    when(stripe.postForm(eq("/v1/subscription_schedules/sub_sched_1/release"), anyString()))
        .thenReturn(ok("{}"));
    when(stripe.postForm(startsWith("/v1/subscriptions/"), anyString(), anyString()))
        .thenReturn(ok(liveSubscription("price_pro", null)));
    when(lifecycle.targetForSubscription(SUB_ID)).thenReturn(null);

    try (MockedStatic<SystemContext> system = Mockito.mockStatic(SystemContext.class)) {
      system.when(() -> SystemContext.run(anyString(), any())).thenAnswer(invocation -> {
        ((Runnable) invocation.getArgument(1)).run();
        return null;
      });
      service.requestChange(CLIENT_ID, "pro");
    }

    verify(stripe).postForm(eq("/v1/subscription_schedules/sub_sched_1/release"), anyString());
    verify(subscriptionService).clearPendingChange(open);
  }

  // ---------------------------------------------------------------- downgrade

  @Test
  void aDowngradeSchedulesTheTargetPriceFromThePeriodEndWithoutProrationAndRecordsItPending()
      throws Exception {
    PlanChange rule6 = rule("period_end");
    when(planChangeCatalog.findAllowed(pro, core)).thenReturn(Optional.of(rule6));
    when(open.getPlan()).thenReturn(pro);
    when(stripe.get(startsWith("/v1/subscriptions/")))
        .thenReturn(ok(liveSubscription("price_pro", null)));
    when(stripe.postForm(eq("/v1/subscription_schedules"), anyString(), anyString()))
        .thenReturn(ok("{\"id\":\"sub_sched_9\"}"));
    when(stripe.postForm(eq("/v1/subscription_schedules/sub_sched_9"), anyString()))
        .thenReturn(ok("{\"id\":\"sub_sched_9\"}"));

    PlanChangeResult result = service.requestChange(CLIENT_ID, "core");

    assertEquals(PlanChangeTiming.PERIOD_END, result.timing());
    assertSame(core, result.targetPlan());
    assertEquals(Instant.ofEpochSecond(PERIOD_END), result.effectiveAt());
    ArgumentCaptor<String> create = ArgumentCaptor.forClass(String.class);
    verify(stripe).postForm(eq("/v1/subscription_schedules"), create.capture(),
        eq("plan-change-schedule:ROW"));
    assertEquals("from_subscription=" + SUB_ID, create.getValue());
    ArgumentCaptor<String> phases = ArgumentCaptor.forClass(String.class);
    verify(stripe).postForm(eq("/v1/subscription_schedules/sub_sched_9"), phases.capture());
    String body = phases.getValue();
    assertTrue(body.contains("phases%5B0%5D%5Bitems%5D%5B0%5D%5Bprice%5D=price_pro"), body);
    assertTrue(body.contains("phases%5B0%5D%5Bend_date%5D=" + PERIOD_END), body);
    assertTrue(body.contains("phases%5B1%5D%5Bitems%5D%5B0%5D%5Bprice%5D=price_core"), body);
    // One cycle of the target plan as a duration: iterations is unknown to current Stripe APIs.
    assertTrue(body.contains("phases%5B1%5D%5Bduration%5D%5Binterval%5D=month"), body);
    assertTrue(body.contains("phases%5B1%5D%5Bduration%5D%5Binterval_count%5D=1"), body);
    assertFalse(body.contains("iterations"), body);
    assertTrue(body.contains("proration_behavior=none"), body);
    assertTrue(body.contains("end_behavior=release"), body);
    verify(subscriptionService).writePendingChange(open, core,
        Date.from(Instant.ofEpochSecond(PERIOD_END)));
    // Nothing changes on the subscription itself until the boundary.
    verify(stripe, never()).postForm(startsWith("/v1/subscriptions/"), anyString(), any());
    verify(lifecycle, never()).applySubscriptionEvent(any(), any(), any());
  }

  @Test
  void theSameDowngradeTwiceIsIdempotentAndAsksTheProviderNothing() throws Exception {
    PlanChange rule7 = rule("period_end");
    when(planChangeCatalog.findAllowed(pro, core)).thenReturn(Optional.of(rule7));
    when(open.getPlan()).thenReturn(pro);
    when(open.getPendingPlan()).thenReturn(core);
    Date pendingAt = Date.from(Instant.ofEpochSecond(PERIOD_END));
    when(open.getPendingEffectiveDate()).thenReturn(pendingAt);

    PlanChangeResult result = service.requestChange(CLIENT_ID, "core");

    assertTrue(result.alreadyPending());
    assertEquals(pendingAt.toInstant(), result.effectiveAt());
    verify(stripe, never()).get(anyString());
    verify(stripe, never()).postForm(anyString(), anyString());
    verify(stripe, never()).postForm(anyString(), anyString(), any());
    verify(subscriptionService, never()).writePendingChange(any(), any(), any());
  }

  @Test
  void aSecondDowngradeToAnotherPlanUpdatesTheExistingScheduleInsteadOfCreatingOne()
      throws Exception {
    Plan mini = plan("MINI", "mini", "price_mini");
    when(planCatalogService.findPurchasablePlan("mini")).thenReturn(Optional.of(mini));
    PlanChange rule8 = rule("period_end");
    // Pending: the rules start from the plan the downgrade moves to (core), not from pro.
    when(planChangeCatalog.findAllowed(core, mini)).thenReturn(Optional.of(rule8));
    when(open.getPlan()).thenReturn(pro);
    when(open.getPendingPlan()).thenReturn(core);
    when(open.getPendingEffectiveDate()).thenReturn(new Date());
    when(stripe.get(startsWith("/v1/subscriptions/")))
        .thenReturn(ok(liveSubscription("price_pro", "{\"id\":\"sub_sched_9\"}")));
    when(stripe.postForm(eq("/v1/subscription_schedules/sub_sched_9"), anyString()))
        .thenReturn(ok("{\"id\":\"sub_sched_9\"}"));

    service.requestChange(CLIENT_ID, "mini");

    verify(stripe, never()).postForm(eq("/v1/subscription_schedules"), anyString(), any());
    ArgumentCaptor<String> phases = ArgumentCaptor.forClass(String.class);
    verify(stripe).postForm(eq("/v1/subscription_schedules/sub_sched_9"), phases.capture());
    assertTrue(phases.getValue().contains("phases%5B1%5D%5Bitems%5D%5B0%5D%5Bprice%5D=price_mini"),
        phases.getValue());
    verify(subscriptionService).writePendingChange(eq(open), eq(mini), any(Date.class));
  }

  @Test
  void aSubscriptionWithoutAPeriodCannotBeScheduledAndSaysSo() throws Exception {
    PlanChange rule9 = rule("period_end");
    when(planChangeCatalog.findAllowed(pro, core)).thenReturn(Optional.of(rule9));
    when(open.getPlan()).thenReturn(pro);
    when(stripe.get(startsWith("/v1/subscriptions/"))).thenReturn(ok("{\"id\":\"" + SUB_ID
        + "\",\"status\":\"active\",\"items\":{\"data\":[{\"id\":\"si_1\","
        + "\"price\":\"price_pro\"}]}}"));

    assertEquals(PlanChangeRefusedException.PROVIDER_REJECTED, refusal("core").code());
    verify(subscriptionService, never()).writePendingChange(any(), any(), any());
  }

  // ---------------------------------------------------------------- offered changes

  @Test
  void theOfferedChangesAreTheAllowedRowsWhoseTargetIsSellable() {
    PlanChange toPro = mock(PlanChange.class);
    when(toPro.getTargetPlan()).thenReturn(pro);
    Plan unpriced = plan("FREE", "free", null);
    PlanChange toUnpriced = mock(PlanChange.class);
    when(toUnpriced.getTargetPlan()).thenReturn(unpriced);
    when(planChangeCatalog.listAllowed(core)).thenReturn(List.of(toPro, toUnpriced));
    when(planCatalogService.hasProviderPrice(unpriced)).thenReturn(false);

    assertEquals(List.of(toPro), service.allowedChanges(open));
    assertTrue(service.allowedChanges(null).isEmpty());
    when(open.getPlan()).thenReturn(null);
    assertTrue(service.allowedChanges(open).isEmpty());
  }

  @Test
  void whileADowngradeIsPendingTheOfferStartsFromThePlanItMovesTo() {
    when(open.getPlan()).thenReturn(pro);
    when(open.getPendingPlan()).thenReturn(core);
    PlanChange toPro = rule("immediate");
    when(toPro.getTargetPlan()).thenReturn(pro);
    when(planChangeCatalog.listAllowed(core)).thenReturn(List.of(toPro));

    assertEquals(List.of(toPro), service.allowedChanges(open));
    assertSame(core, PlanChangeService.fromPlanOf(open));
    verify(planChangeCatalog, never()).listAllowed(pro);
  }

  @Test
  void keepingTheCurrentPlanCancelsTheScheduledDowngradeAndChargesNothing() throws Exception {
    when(open.getPlan()).thenReturn(pro);
    when(open.getPendingPlan()).thenReturn(core);
    when(open.getPendingEffectiveDate()).thenReturn(new Date());
    when(stripe.get(startsWith("/v1/subscriptions/")))
        .thenReturn(ok(liveSubscription("price_pro", "\"sub_sched_1\"")));
    when(stripe.postForm(eq("/v1/subscription_schedules/sub_sched_1/release"), anyString()))
        .thenReturn(ok("{}"));

    PlanChangeResult result = service.requestChange(CLIENT_ID, "pro");

    assertTrue(result.pendingCancelled());
    assertSame(pro, result.targetPlan());
    assertFalse(result.paymentRequired());
    verify(stripe).postForm(eq("/v1/subscription_schedules/sub_sched_1/release"), anyString());
    verify(subscriptionService).clearPendingChange(open);
    // No rule lookup and no subscription update: nothing is charged.
    verify(planChangeCatalog, never()).findAllowed(any(), any());
    verify(stripe, never()).postForm(startsWith("/v1/subscriptions/"), anyString(), anyString());
  }

  @Test
  void aRefusedReleaseKeepsTheScheduledDowngradeOnBothSides() throws Exception {
    when(open.getPlan()).thenReturn(pro);
    when(open.getPendingPlan()).thenReturn(core);
    when(open.getPendingEffectiveDate()).thenReturn(new Date());
    when(stripe.get(startsWith("/v1/subscriptions/")))
        .thenReturn(ok(liveSubscription("price_pro", "\"sub_sched_1\"")));
    when(stripe.postForm(eq("/v1/subscription_schedules/sub_sched_1/release"), anyString()))
        .thenReturn(new StripeResponse(400, "{\"error\":{\"code\":\"resource_missing\"}}"));

    PlanChangeRefusedException refusal = assertThrows(PlanChangeRefusedException.class,
        () -> service.requestChange(CLIENT_ID, "pro"));

    assertEquals(PlanChangeRefusedException.PROVIDER_REJECTED, refusal.code());
    // Clearing here while the provider still downgrades would show "no change" falsely.
    verify(subscriptionService, never()).clearPendingChange(any());
  }

  @Test
  void anUpgradeWhoseInvoiceIsUnpaidWaitsForThePaymentAndChangesNothingLocally() throws Exception {
    PlanChange immediate = rule("immediate");
    when(planChangeCatalog.findAllowed(core, pro)).thenReturn(Optional.of(immediate));
    // The provider kept the update aside: the item is still on the current price.
    String pending = liveSubscription("price_core", null).replaceFirst("\\}$",
        ",\"pending_update\":{\"expires_at\":1793000000},\"latest_invoice\":{\"id\":\"in_1\","
            + "\"hosted_invoice_url\":\"https://invoice.stripe.com/i/abc\"}}");
    when(stripe.postForm(startsWith("/v1/subscriptions/"), anyString(), anyString()))
        .thenReturn(ok(pending));

    PlanChangeResult result = service.requestChange(CLIENT_ID, "pro");

    assertEquals(PlanChangeTiming.IMMEDIATE, result.timing());
    assertTrue(result.paymentRequired());
    assertEquals("https://invoice.stripe.com/i/abc", result.paymentUrl());
    // No rollover, no pending columns: the webhook rolls the row once the invoice is paid.
    verify(lifecycle, never()).applySubscriptionEvent(any(), any(), any());
    verify(subscriptionService, never()).writePendingChange(any(), any(), any());
  }

  @Test
  void aPreviewQuotesTheChargeAndTheCardAndChangesNothing() throws Exception {
    PlanChange immediate = rule("immediate");
    when(planChangeCatalog.findAllowed(core, pro)).thenReturn(Optional.of(immediate));
    String live = liveSubscription("price_core", null).replaceFirst("\\}$",
        ",\"default_payment_method\":{\"id\":\"pm_1\",\"card\":{\"brand\":\"visa\","
            + "\"last4\":\"4242\"}}}");
    when(stripe.get(startsWith("/v1/subscriptions/"))).thenReturn(ok(live));
    when(stripe.postForm(eq("/v1/invoices/create_preview"), anyString()))
        .thenReturn(ok("{\"amount_due\":45829,\"currency\":\"eur\"}"));

    PlanChangePreview preview = service.previewChange(CLIENT_ID, "pro");

    assertEquals(PlanChangeTiming.IMMEDIATE, preview.timing());
    assertSame(pro, preview.targetPlan());
    assertEquals(new java.math.BigDecimal("458.29"), preview.amountDue());
    assertEquals("EUR", preview.currency());
    assertEquals(NOW.getEpochSecond(), preview.prorationDate());
    assertEquals("visa", preview.cardBrand());
    assertEquals("4242", preview.cardLast4());
    verify(stripe).get("/v1/subscriptions/" + SUB_ID + "?expand%5B%5D=default_payment_method");
    ArgumentCaptor<String> form = ArgumentCaptor.forClass(String.class);
    verify(stripe).postForm(eq("/v1/invoices/create_preview"), form.capture());
    String body = form.getValue();
    assertTrue(body.contains("subscription=" + SUB_ID), body);
    assertTrue(body.contains("subscription_details%5Bitems%5D%5B0%5D%5Bid%5D=si_1"), body);
    assertTrue(body.contains("subscription_details%5Bitems%5D%5B0%5D%5Bprice%5D=price_pro"), body);
    assertTrue(body.contains("subscription_details%5Bproration_behavior%5D=always_invoice"), body);
    assertTrue(body.contains("subscription_details%5Bproration_date%5D=" + NOW.getEpochSecond()),
        body);
    // A preview changes nothing at the provider or here.
    verify(stripe, never()).postForm(startsWith("/v1/subscriptions/"), anyString(), anyString());
    verify(lifecycle, never()).applySubscriptionEvent(any(), any(), any());
  }

  @Test
  void aDowngradePreviewChargesNothingAndSaysWhenItApplies() throws Exception {
    PlanChange periodEnd = rule("period_end");
    when(planChangeCatalog.findAllowed(pro, core)).thenReturn(Optional.of(periodEnd));
    when(open.getPlan()).thenReturn(pro);
    when(stripe.get(startsWith("/v1/subscriptions/")))
        .thenReturn(ok(liveSubscription("price_pro", null)));

    PlanChangePreview preview = service.previewChange(CLIENT_ID, "core");

    assertEquals(PlanChangeTiming.PERIOD_END, preview.timing());
    assertEquals(Instant.ofEpochSecond(PERIOD_END), preview.effectiveAt());
    assertNull(preview.amountDue());
    assertNull(preview.prorationDate());
    verify(stripe, never()).postForm(anyString(), anyString());
  }

  @Test
  void aPreviewRefusesWhatTheChangeWouldRefuse() {
    when(planChangeCatalog.findAllowed(core, pro)).thenReturn(Optional.empty());

    PlanChangeRefusedException refusal = assertThrows(PlanChangeRefusedException.class,
        () -> service.previewChange(CLIENT_ID, "pro"));

    assertEquals(PlanChangeRefusedException.PLAN_CHANGE_NOT_ALLOWED, refusal.code());
  }

  @Test
  void anUpgradeIsChargedAtTheQuotedProrationDateOnlyWhileItIsRecent() {
    long quoted = NOW.minusSeconds(120).getEpochSecond();
    assertEquals(Instant.ofEpochSecond(quoted), service.prorationInstant(quoted));
    assertEquals(NOW, service.prorationInstant(null));
    assertEquals(NOW, service.prorationInstant(NOW.plusSeconds(60).getEpochSecond()));
    assertEquals(NOW, service.prorationInstant(NOW.minusSeconds(3601).getEpochSecond()));
  }

  @Test
  void theDowngradePhaseLastsOneCycleOfTheTargetPlanAndMonthlyWhenItsIntervalIsUnknown() {
    Plan yearly = mock(Plan.class);
    when(yearly.getBillingInterval()).thenReturn(" Year ");
    Plan unknown = mock(Plan.class);
    when(unknown.getBillingInterval()).thenReturn(null);

    assertEquals("year", PlanChangeService.durationIntervalOf(yearly));
    assertEquals("month", PlanChangeService.durationIntervalOf(core));
    assertEquals("month", PlanChangeService.durationIntervalOf(unknown));
  }

  @Test
  void theScheduleIdIsReadExpandedOrBare() throws Exception {
    assertEquals("sub_sched_1", PlanChangeService.scheduleIdOf(
        new JSONObject("{\"schedule\":\"sub_sched_1\"}")));
    assertEquals("sub_sched_2", PlanChangeService.scheduleIdOf(
        new JSONObject("{\"schedule\":{\"id\":\"sub_sched_2\"}}")));
    assertNull(PlanChangeService.scheduleIdOf(new JSONObject("{\"schedule\":null}")));
    assertNull(PlanChangeService.scheduleIdOf(new JSONObject("{}")));
    assertNull(PlanChangeService.scheduleIdOf(null));
  }
}
