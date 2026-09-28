/*
 * *************************************************************************
 * Etendo License. See https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * *************************************************************************
 */
package com.etendoerp.go.payment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.hibernate.Session;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.openbravo.base.exception.OBSecurityException;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.dal.service.OBQuery;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.enterprise.Organization;

import com.etendoerp.go.schemaforge.data.Account;
import com.etendoerp.go.schemaforge.data.Plan;
import com.etendoerp.go.schemaforge.data.Subscription;

/**
 * Unit specs for {@link SubscriptionService} (ETP-5046).
 *
 * <p>Three properties are asserted here beyond ordinary coverage, because each one fails silently
 * rather than loudly:
 *
 * <ul>
 *   <li><b>The readable-client/organization filters must stay off.</b> Subscription rows live at
 *       client {@code 0} and are read while serving a request whose context belongs to some other
 *       tenant. With the filters on, every query returns nothing and every paying tenant reads
 *       back as free — no error anywhere.</li>
 *   <li><b>The bulk lookup must issue one query.</b> It exists only to replace a per-tenant query
 *       inside a comparator; a regression to one call per tenant is invisible except as latency,
 *       so the call count is asserted rather than the result alone.</li>
 *   <li><b>The price is snapshotted onto the row.</b> That is what grandfathers an existing
 *       subscriber against a later re-pricing of the plan, and nothing at runtime would report its
 *       absence.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SubscriptionServiceTest {

  private static final String CLIENT_ID = "48F0981053084BC49CCEEFEC296E2A3D";
  private static final String OTHER_CLIENT_ID = "9F5511B92BD0465FA678F75278FA9C3A";
  private static final String PLAN_KEY = "productive-monthly";
  private static final String PRICE_ID = "price_123";
  private static final String CUSTOMER_ID = "cus_123";
  private static final String STRIPE_SUBSCRIPTION_ID = "sub_123";

  @Mock private OBDal obDal;
  @Mock private OBProvider obProvider;
  @Mock private OBQuery<Subscription> subscriptionQuery;

  private MockedStatic<OBDal> obDalMock;
  private MockedStatic<OBProvider> obProviderMock;
  private MockedStatic<OBContext> obContextMock;

  private final SubscriptionService service = new SubscriptionService();

  @BeforeEach
  void setUp() {
    obDalMock = mockStatic(OBDal.class);
    obProviderMock = mockStatic(OBProvider.class);
    obContextMock = mockStatic(OBContext.class);
    obDalMock.when(OBDal::getInstance).thenReturn(obDal);
    obProviderMock.when(OBProvider::getInstance).thenReturn(obProvider);
    when(obDal.createQuery(eq(Subscription.class), anyString())).thenReturn(subscriptionQuery);
  }

  @AfterEach
  void tearDown() {
    obDalMock.close();
    obProviderMock.close();
    obContextMock.close();
  }

  private static Subscription subscriptionOf(String clientId) {
    Client tenant = mock(Client.class);
    when(tenant.getId()).thenReturn(clientId);
    Subscription subscription = mock(Subscription.class);
    when(subscription.getEnvironmentClient()).thenReturn(tenant);
    return subscription;
  }

  @Nested
  class FindOpen {

    @Test
    void returnsTheOpenRowOfTheRequestedTenant() {
      Subscription open = subscriptionOf(CLIENT_ID);
      when(subscriptionQuery.uniqueResult()).thenReturn(open);

      assertSame(open, service.findOpen(CLIENT_ID).orElseThrow());
    }

    @Test
    void returnsEmptyWhenTheTenantHasNoOpenRow() {
      when(subscriptionQuery.uniqueResult()).thenReturn(null);

      assertTrue(service.findOpen(CLIENT_ID).isEmpty());
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = { "", "   " })
    void returnsEmptyForABlankTenantWithoutQuerying(String clientId) {
      assertTrue(service.findOpen(clientId).isEmpty());

      verify(obDal, never()).createQuery(eq(Subscription.class), anyString());
    }

    @Test
    void asksOnlyForOpenRowsAndIgnoresContextVisibility() {
      Subscription open = subscriptionOf(CLIENT_ID);
      when(subscriptionQuery.uniqueResult()).thenReturn(open);

      service.findOpen(CLIENT_ID);

      ArgumentCaptor<String> hql = ArgumentCaptor.forClass(String.class);
      verify(obDal).createQuery(eq(Subscription.class), hql.capture());
      // "Open" is end date null AND active. Dropping either half makes a closed subscription — a
      // tenant that already churned, or the previous row of a plan change — read as current.
      assertTrue(hql.getValue().contains("endDate is null"), hql.getValue());
      assertTrue(hql.getValue().contains("active = true"), hql.getValue());
      verify(subscriptionQuery).setNamedParameter("environmentClientId", CLIENT_ID);
      // Client-0 rows about another tenant: with these filters on, the query matches nothing and
      // every paying tenant silently reads back as free.
      verify(subscriptionQuery).setFilterOnReadableClients(false);
      verify(subscriptionQuery).setFilterOnReadableOrganization(false);
    }
  }

  @Nested
  class FindOpenForClients {

    @Test
    void resolvesEveryTenantInASingleQuery() {
      List<Subscription> rows = List.of(subscriptionOf(CLIENT_ID), subscriptionOf(OTHER_CLIENT_ID));
      when(subscriptionQuery.list()).thenReturn(rows);

      Map<String, Subscription> byClient =
          service.findOpenForClients(List.of(CLIENT_ID, OTHER_CLIENT_ID));

      assertEquals(2, byClient.size());
      assertTrue(byClient.containsKey(CLIENT_ID));
      assertTrue(byClient.containsKey(OTHER_CLIENT_ID));
      // The whole point of this method: one round trip, not one per tenant. A regression here is
      // invisible except as latency on the environment list.
      verify(obDal, times(1)).createQuery(eq(Subscription.class), anyString());
    }

    @Test
    void leavesTenantsWithoutASubscriptionOutOfTheResult() {
      List<Subscription> rows = List.of(subscriptionOf(CLIENT_ID));
      when(subscriptionQuery.list()).thenReturn(rows);

      Map<String, Subscription> byClient =
          service.findOpenForClients(List.of(CLIENT_ID, OTHER_CLIENT_ID));

      assertEquals(1, byClient.size());
      assertTrue(byClient.containsKey(CLIENT_ID));
    }

    @Test
    void queriesNothingForAnEmptyOrBlankOnlyRequest() {
      assertTrue(service.findOpenForClients(null).isEmpty());
      assertTrue(service.findOpenForClients(Collections.emptyList()).isEmpty());
      assertTrue(service.findOpenForClients(java.util.Arrays.asList(null, "", "  ")).isEmpty());

      verify(obDal, never()).createQuery(eq(Subscription.class), anyString());
    }

    @Test
    void chunksTheIdListSoNoDriverLimitIsExceeded() {
      when(subscriptionQuery.list()).thenReturn(Collections.emptyList());
      List<String> manyIds = new ArrayList<>();
      for (int i = 0; i < 2500; i++) {
        manyIds.add("CLIENT-" + i);
      }

      service.findOpenForClients(manyIds);

      // 2500 ids at 1000 per predicate: three queries, never one expression list of 2500.
      verify(obDal, times(3)).createQuery(eq(Subscription.class), anyString());
    }

    @Test
    void ignoresContextVisibilityOnTheBulkLookupToo() {
      when(subscriptionQuery.list()).thenReturn(Collections.emptyList());

      service.findOpenForClients(List.of(CLIENT_ID));

      verify(subscriptionQuery).setFilterOnReadableClients(false);
      verify(subscriptionQuery).setFilterOnReadableOrganization(false);
    }
  }

  @Nested
  class OpenSubscription {

    private Plan plan;
    private Subscription created;

    @BeforeEach
    void givenACatalogPlanAndANewRow() {
      plan = mock(Plan.class);
      when(plan.getSearchKey()).thenReturn(PLAN_KEY);
      when(plan.getProviderPriceID()).thenReturn(PRICE_ID);
      when(plan.getDisplayPrice()).thenReturn(new BigDecimal("49.0000"));
      when(plan.getCurrencyCode()).thenReturn("EUR");
      created = mock(Subscription.class);
      when(obProvider.get(Subscription.class)).thenReturn(created);
      Client clientRow = mock(Client.class);
      Organization organizationRow = mock(Organization.class);
      when(obDal.get(eq(Client.class), any())).thenReturn(clientRow);
      when(obDal.get(eq(Organization.class), any())).thenReturn(organizationRow);
      when(subscriptionQuery.uniqueResult()).thenReturn(null);
    }

    @Test
    void opensAnActiveRowWithNoEndDate() {
      service.openSubscription(CLIENT_ID, plan, mock(Account.class), CUSTOMER_ID,
          STRIPE_SUBSCRIPTION_ID);

      verify(created).setSubscriptionStatus(SubscriptionService.STATUS_ACTIVE);
      verify(created).setEndDate(null);
      verify(created).setStartDate(any());
      verify(created).setPlan(plan);
      verify(created).setStripeCustomer(CUSTOMER_ID);
      verify(created).setStripeSubscription(STRIPE_SUBSCRIPTION_ID);
      verify(obDal).save(created);
    }

    @Test
    void writesTheSystemOwnedRowAsSystem() {
      // The paid onboarding calls this from the new tenant's context, where the DAL write check
      // refuses a client-0 row. TenantContextSubscriptionWriteIntegrationTest pins the real check,
      // and this spec pins that the write is routed through SystemContext at all.
      service.openSubscription(CLIENT_ID, plan, null, CUSTOMER_ID, STRIPE_SUBSCRIPTION_ID);

      obContextMock.verify(() -> OBContext.setOBContext("0", "0", "0", "0"));
      verify(obDal).save(created);
    }

    @Test
    void evictsARefusedRowSoTheCallersNextFlushCannotReplayIt() {
      // A save refused inside Hibernate's save event leaves the row in the session with an id and
      // no database state; the onboarding's next flush then UPDATEs a row that was never inserted
      // and the whole paid onboarding fails on a StaleStateException.
      Session session = mock(Session.class);
      when(obDal.getSession()).thenReturn(session);
      doThrow(new OBSecurityException("refused")).when(obDal).save(created);

      assertThrows(OBSecurityException.class, () -> service.openSubscription(CLIENT_ID, plan, null,
          CUSTOMER_ID, STRIPE_SUBSCRIPTION_ID));

      verify(session).evict(created);
    }

    @Test
    void snapshotsThePriceSoALaterRePricingCannotReachThisSubscriber() {
      service.openSubscription(CLIENT_ID, plan, null, null, null);

      // Editing the plan's price later must not change this subscriber's next invoice. The
      // snapshot is what makes that guarantee locally visible instead of something the reader has
      // to trust the provider for.
      verify(created).setProviderPriceID(PRICE_ID);
      verify(created).setSnapshotAmount(new BigDecimal("49.0000"));
      verify(created).setSnapshotCurrency("EUR");
    }

    @Test
    void neverRewritesTheSnapshotOfAnAlreadyOpenSubscription() {
      Subscription existing = subscriptionOf(CLIENT_ID);
      when(subscriptionQuery.uniqueResult()).thenReturn(existing);

      Subscription result = service.openSubscription(CLIENT_ID, plan, null, null, null);

      // A re-entered onboarding (the ?checkout=success URL stays live for the whole run) must be
      // idempotent, not a second row and not a re-snapshot at today's price.
      assertSame(existing, result);
      verify(obProvider, never()).get(Subscription.class);
      verify(existing, never()).setProviderPriceID(anyString());
      verify(obDal, never()).save(any());
    }

    @Test
    void refusesToOpenASubscriptionWithNoTenantOrNoPlan() {
      assertThrows(IllegalArgumentException.class,
          () -> service.openSubscription(null, plan, null, null, null));
      assertThrows(IllegalArgumentException.class,
          () -> service.openSubscription(CLIENT_ID, null, null, null, null));
    }

    @Test
    void doesNotCommit() {
      service.openSubscription(CLIENT_ID, plan, null, null, null);

      // It joins the onboarding transaction: a subscription that survived a rolled-back
      // environment would describe a tenant that does not exist.
      verify(obDal, never()).commitAndClose();
    }
  }

  /**
   * The overload the post-payment path calls: the subscription snapshots the price the checkout
   * actually CHARGED, which under the legacy price fallback is not the (priceless) plan's.
   */
  @Nested
  class OpenSubscriptionWithTheChargedPrice {

    private Subscription created;

    @BeforeEach
    void givenANewRow() {
      created = mock(Subscription.class);
      when(obProvider.get(Subscription.class)).thenReturn(created);
      when(obDal.get(eq(Client.class), any())).thenReturn(mock(Client.class));
      when(obDal.get(eq(Organization.class), any())).thenReturn(mock(Organization.class));
      when(subscriptionQuery.uniqueResult()).thenReturn(null);
    }

    private Plan legacyPlan() {
      Plan legacy = mock(Plan.class);
      when(legacy.getSearchKey()).thenReturn(PlanCatalogService.LEGACY_PLAN_KEY);
      when(legacy.getProviderPriceID()).thenReturn(null);
      when(legacy.getDisplayPrice()).thenReturn(null);
      when(legacy.getCurrencyCode()).thenReturn(null);
      return legacy;
    }

    private Plan pricedPlan() {
      Plan plan = mock(Plan.class);
      when(plan.getSearchKey()).thenReturn(PLAN_KEY);
      when(plan.getProviderPriceID()).thenReturn(PRICE_ID);
      when(plan.getDisplayPrice()).thenReturn(new BigDecimal("49.0000"));
      when(plan.getCurrencyCode()).thenReturn("EUR");
      return plan;
    }

    @Test
    void aFallbackPurchaseOpensOnTheGrandfatheredPlanWithTheChargedPrice() {
      Plan legacy = legacyPlan();

      service.openSubscription(CLIENT_ID, legacy, null, CUSTOMER_ID, STRIPE_SUBSCRIPTION_ID,
          "price_LEGACY_configured");

      verify(created).setPlan(legacy);
      verify(created).setSubscriptionStatus(SubscriptionService.STATUS_ACTIVE);
      // The grandfathered plan has no price of its own, so the charged one is the only record of
      // what this subscriber pays.
      verify(created).setProviderPriceID("price_LEGACY_configured");
      // The plan's display amount describes the plan's price, which this is not: no snapshot.
      verify(created, never()).setSnapshotAmount(any());
      verify(created, never()).setSnapshotCurrency(any());
      verify(obDal).save(created);
    }

    @Test
    void aChargedPriceThatIsThePlansOwnSnapshotsTheAmountToo() {
      service.openSubscription(CLIENT_ID, pricedPlan(), null, null, null, "  " + PRICE_ID + " ");

      verify(created).setProviderPriceID(PRICE_ID);
      verify(created).setSnapshotAmount(new BigDecimal("49.0000"));
      verify(created).setSnapshotCurrency("EUR");
    }

    @Test
    void aChargedPriceThatDiffersFromThePlansKeepsTheChargedIdAndNoAmount() {
      // The plan was re-priced between checkout and provisioning: record what was charged, never
      // the plan's current amount next to a price id it does not describe.
      service.openSubscription(CLIENT_ID, pricedPlan(), null, null, null, "price_OLD");

      verify(created).setProviderPriceID("price_OLD");
      verify(created, never()).setSnapshotAmount(any());
      verify(created, never()).setSnapshotCurrency(any());
    }

    @Test
    void noChargedPriceFallsBackToThePlansPrice() {
      service.openSubscription(CLIENT_ID, pricedPlan(), null, null, null, "   ");

      verify(created).setProviderPriceID(PRICE_ID);
      verify(created).setSnapshotAmount(new BigDecimal("49.0000"));
    }
  }

  /** Where the Stripe lifecycle webhooks land for a tenant with an open subscription row. */
  @Nested
  class ApplyLifecycleStatus {

    private static final java.time.Instant ANCHOR = java.time.Instant.parse("2026-10-31T00:00:00Z");

    @Test
    void currentIsStoredAsActive() {
      Subscription open = subscriptionOf(CLIENT_ID);
      when(subscriptionQuery.uniqueResult()).thenReturn(open);

      assertTrue(service.applyLifecycleStatus(CLIENT_ID,
          EnvironmentAccessPolicy.SubscriptionStatus.CURRENT, null));

      verify(open).setSubscriptionStatus(SubscriptionService.STATUS_ACTIVE);
      // null clears the grace anchor (ETP-5047: its own column, GRACE_ANCHOR).
      verify(open).setGraceAnchor(null);
      verify(obDal).save(open);
    }

    @Test
    void pastDueIsStoredWithTheGraceAnchorInItsOwnColumn() {
      Subscription open = subscriptionOf(CLIENT_ID);
      when(subscriptionQuery.uniqueResult()).thenReturn(open);

      assertTrue(service.applyLifecycleStatus(CLIENT_ID,
          EnvironmentAccessPolicy.SubscriptionStatus.PAST_DUE, ANCHOR));

      verify(open).setSubscriptionStatus(SubscriptionService.STATUS_PAST_DUE);
      // The access policy counts the grace days from this column. Before ETP-5047 it was
      // CURRENT_PERIOD_END, which now holds only the provider billing period.
      verify(open).setGraceAnchor(java.util.Date.from(ANCHOR));
      verify(open, never()).setCurrentPeriodEnd(any());
    }

    @Test
    void expiredIsStoredAsCanceledWithoutClosingTheRow() {
      Subscription open = subscriptionOf(CLIENT_ID);
      when(subscriptionQuery.uniqueResult()).thenReturn(open);

      assertTrue(service.applyLifecycleStatus(CLIENT_ID,
          EnvironmentAccessPolicy.SubscriptionStatus.EXPIRED, null));

      verify(open).setSubscriptionStatus(SubscriptionService.STATUS_CANCELED);
      // This is the development tool's write, which must be able to flip a tenant back; only the
      // webhook's applyLifecycleOutcome closes a row on a terminating event.
      verify(open, never()).setEndDate(any());
      verify(open).setGraceAnchor(null);
    }

    @Test
    void neverTouchesTheBillingPeriod() {
      // ETP-5047 — the anchor left CURRENT_PERIOD_END, so the pre-split rule that dropped a start
      // after the new anchor (to keep ETGO_SUB_PERIOD_CHK) is gone: the period is not written here
      // at all, whatever the anchor.
      Subscription open = subscriptionOf(CLIENT_ID);
      when(open.getCurrentPeriodStart())
          .thenReturn(java.util.Date.from(ANCHOR.plusSeconds(86_400L)));
      when(subscriptionQuery.uniqueResult()).thenReturn(open);

      service.applyLifecycleStatus(CLIENT_ID, EnvironmentAccessPolicy.SubscriptionStatus.PAST_DUE,
          ANCHOR);

      verify(open, never()).setCurrentPeriodStart(any());
      verify(open, never()).setCurrentPeriodEnd(any());
      verify(open).setGraceAnchor(java.util.Date.from(ANCHOR));
    }

    @Test
    void aTenantWithoutAnOpenRowIsReportedSoTheCallerCanUseThePreferences() {
      when(subscriptionQuery.uniqueResult()).thenReturn(null);

      assertEquals(false, service.applyLifecycleStatus(CLIENT_ID,
          EnvironmentAccessPolicy.SubscriptionStatus.CURRENT, null));

      verify(obDal, never()).save(any());
    }

    @Test
    void doesNotCommit() {
      Subscription open = subscriptionOf(CLIENT_ID);
      when(subscriptionQuery.uniqueResult()).thenReturn(open);

      service.applyLifecycleStatus(CLIENT_ID, EnvironmentAccessPolicy.SubscriptionStatus.CURRENT,
          null);

      // The webhook handler commits it together with the event's ledger row.
      verify(obDal, never()).commitAndClose();
    }

    @Test
    void refusesAStatusNoLifecycleEventProduces() {
      assertThrows(IllegalArgumentException.class, () -> service.applyLifecycleStatus(CLIENT_ID,
          EnvironmentAccessPolicy.SubscriptionStatus.LEGACY_ENTITLEMENT, null));
    }
  }

  /** ETP-5047 — the lookup a lifecycle event resolves its row with. */
  @Nested
  class FindOpenByStripeSubscription {

    @Test
    void returnsTheOpenRowCarryingTheId() {
      Subscription open = subscriptionOf(CLIENT_ID);
      when(subscriptionQuery.uniqueResult()).thenReturn(open);

      assertSame(open, service.findOpenByStripeSubscription(STRIPE_SUBSCRIPTION_ID).orElseThrow());
    }

    @Test
    void asksOnlyForOpenRowsSoAClosedPredecessorSharingTheIdNeverAnswers() {
      // STRIPE_SUBSCRIPTION_ID is not unique: a plan change keeps the Stripe subscription and
      // opens a successor row. Only the open one may take an event.
      service.findOpenByStripeSubscription("  " + STRIPE_SUBSCRIPTION_ID + " ");

      ArgumentCaptor<String> hql = ArgumentCaptor.forClass(String.class);
      verify(obDal).createQuery(eq(Subscription.class), hql.capture());
      assertTrue(hql.getValue().contains("stripeSubscription = :stripeSubscriptionId"),
          hql.getValue());
      assertTrue(hql.getValue().contains("endDate is null"), hql.getValue());
      assertTrue(hql.getValue().contains("active = true"), hql.getValue());
      verify(subscriptionQuery).setNamedParameter("stripeSubscriptionId", STRIPE_SUBSCRIPTION_ID);
      verify(subscriptionQuery).setFilterOnReadableClients(false);
      verify(subscriptionQuery).setFilterOnReadableOrganization(false);
      verify(subscriptionQuery).setMaxResult(1);
    }

    @Test
    void returnsEmptyWhenNoOpenRowCarriesTheId() {
      when(subscriptionQuery.uniqueResult()).thenReturn(null);

      assertTrue(service.findOpenByStripeSubscription(STRIPE_SUBSCRIPTION_ID).isEmpty());
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = { "", "   " })
    void findsNothingForABlankIdWithoutQuerying(String id) {
      assertTrue(service.findOpenByStripeSubscription(id).isEmpty());

      verify(obDal, never()).createQuery(eq(Subscription.class), anyString());
    }
  }

  /** ETP-5047 — "what is this tenant's subscription state": open row, else latest closed one. */
  @Nested
  class FindLatest {

    @Test
    void theOpenRowAnswersWithASingleQuery() {
      Subscription open = subscriptionOf(CLIENT_ID);
      when(subscriptionQuery.uniqueResult()).thenReturn(open);

      assertSame(open, service.findLatest(CLIENT_ID).orElseThrow());

      verify(obDal, times(1)).createQuery(eq(Subscription.class), anyString());
    }

    @Test
    void withNoOpenRowTheNewestClosedRowAnswers() {
      Subscription closed = subscriptionOf(CLIENT_ID);
      when(subscriptionQuery.uniqueResult()).thenReturn(null, closed);

      assertSame(closed, service.findLatest(CLIENT_ID).orElseThrow());

      ArgumentCaptor<String> hql = ArgumentCaptor.forClass(String.class);
      verify(obDal, times(2)).createQuery(eq(Subscription.class), hql.capture());
      String closedQuery = hql.getAllValues().get(1);
      assertTrue(closedQuery.contains("endDate is not null"), closedQuery);
      assertTrue(closedQuery.contains("active = true"), closedQuery);
      // Newest first: the latest END_DATE, then the latest created, and only one row.
      assertTrue(closedQuery.contains("order by sub.endDate desc, sub.creationDate desc"),
          closedQuery);
      verify(subscriptionQuery, times(2)).setMaxResult(1);
      verify(subscriptionQuery, times(2)).setFilterOnReadableClients(false);
    }

    @Test
    void aTenantThatNeverHadARowFindsNothing() {
      when(subscriptionQuery.uniqueResult()).thenReturn(null);

      assertTrue(service.findLatest(CLIENT_ID).isEmpty());
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = { "", "   " })
    void findsNothingForABlankTenantWithoutQuerying(String clientId) {
      assertTrue(service.findLatest(clientId).isEmpty());

      verify(obDal, never()).createQuery(eq(Subscription.class), anyString());
    }
  }

  @Nested
  class FindLatestForClients {

    @Test
    void anOpenRowAnswersAndAClosedOneFillsTheTenantsWithout() {
      Subscription open = subscriptionOf(CLIENT_ID);
      Subscription newestClosed = subscriptionOf(OTHER_CLIENT_ID);
      Subscription olderClosed = subscriptionOf(OTHER_CLIENT_ID);
      when(subscriptionQuery.list()).thenReturn(List.of(open), List.of(newestClosed, olderClosed));

      Map<String, Subscription> byClient =
          service.findLatestForClients(List.of(CLIENT_ID, OTHER_CLIENT_ID));

      assertEquals(2, byClient.size());
      assertSame(open, byClient.get(CLIENT_ID));
      // Ordered latest first, so the first closed row seen for a tenant is the one kept.
      assertSame(newestClosed, byClient.get(OTHER_CLIENT_ID));
      ArgumentCaptor<String> hql = ArgumentCaptor.forClass(String.class);
      verify(obDal, times(2)).createQuery(eq(Subscription.class), hql.capture());
      assertTrue(hql.getAllValues().get(1).contains("endDate is not null"));
      assertTrue(hql.getAllValues().get(1).contains("order by sub.endDate desc"));
      // The second query asks only for the tenants the first did not answer.
      verify(subscriptionQuery).setNamedParameter("environmentClientIds", List.of(OTHER_CLIENT_ID));
    }

    @Test
    void issuesNoSecondQueryWhenEveryTenantHasAnOpenRow() {
      List<Subscription> rows = List.of(subscriptionOf(CLIENT_ID), subscriptionOf(OTHER_CLIENT_ID));
      when(subscriptionQuery.list()).thenReturn(rows);

      service.findLatestForClients(List.of(CLIENT_ID, OTHER_CLIENT_ID));

      verify(obDal, times(1)).createQuery(eq(Subscription.class), anyString());
    }

    @Test
    void queriesNothingForANullOrBlankOnlyRequest() {
      assertTrue(service.findLatestForClients(null).isEmpty());
      assertTrue(service.findLatestForClients(java.util.Arrays.asList(null, "", "  ")).isEmpty());

      verify(obDal, never()).createQuery(eq(Subscription.class), anyString());
    }

    @Test
    void leavesATenantWithNoRowAtAllOutOfTheResult() {
      when(subscriptionQuery.list()).thenReturn(Collections.emptyList());

      assertTrue(service.findLatestForClients(List.of(CLIENT_ID)).isEmpty());
    }
  }

  /** ETP-5047 — the webhook's write onto the row it resolved. */
  @Nested
  class ApplyLifecycleOutcome {

    private final java.time.Instant anchor = java.time.Instant.parse("2026-10-31T00:00:00Z");
    private final java.time.Instant periodStart = java.time.Instant.parse("2026-11-01T00:00:00Z");
    private final java.time.Instant periodEnd = java.time.Instant.parse("2026-12-01T00:00:00Z");
    private final java.time.Instant eventAt = java.time.Instant.parse("2026-11-01T12:00:00Z");

    private SubscriptionEventOutcome pastDue() {
      return SubscriptionEventOutcome.apply(EnvironmentAccessPolicy.SubscriptionStatus.PAST_DUE,
          anchor);
    }

    @Test
    void writesStatusAndTheGraceAnchorButNotThePeriodWhenTheEventReportsNone() {
      Subscription row = subscriptionOf(CLIENT_ID);

      service.applyLifecycleOutcome(row, pastDue(), eventAt);

      verify(row).setSubscriptionStatus(SubscriptionService.STATUS_PAST_DUE);
      verify(row).setGraceAnchor(java.util.Date.from(anchor));
      // An event that reports no period (invoice.payment_failed) leaves the stored one alone —
      // and the anchor is never written into the period.
      verify(row, never()).setCurrentPeriodStart(any());
      verify(row, never()).setCurrentPeriodEnd(any());
      verify(row, never()).setEndDate(any());
      verify(obDal).save(row);
      verify(obDal, never()).commitAndClose();
    }

    @Test
    void writesBothPeriodColumnsWhenTheOutcomeCarriesAPeriod() {
      Subscription row = subscriptionOf(CLIENT_ID);

      service.applyLifecycleOutcome(row, SubscriptionEventOutcome
          .apply(EnvironmentAccessPolicy.SubscriptionStatus.CURRENT, null)
          .withPeriod(periodStart, periodEnd), eventAt);

      verify(row).setSubscriptionStatus(SubscriptionService.STATUS_ACTIVE);
      verify(row).setGraceAnchor(null);
      verify(row).setCurrentPeriodStart(java.util.Date.from(periodStart));
      verify(row).setCurrentPeriodEnd(java.util.Date.from(periodEnd));
    }

    @Test
    void setsTheWatermarkOnARowThatHasNone() {
      Subscription row = subscriptionOf(CLIENT_ID);

      service.applyLifecycleOutcome(row, pastDue(), eventAt);

      verify(row).setLastEventAt(java.util.Date.from(eventAt));
    }

    @Test
    void movesTheWatermarkForwardOnly() {
      Subscription older = subscriptionOf(CLIENT_ID);
      when(older.getLastEventAt()).thenReturn(java.util.Date.from(eventAt.minusSeconds(60L)));
      Subscription newer = subscriptionOf(CLIENT_ID);
      when(newer.getLastEventAt()).thenReturn(java.util.Date.from(eventAt.plusSeconds(60L)));
      Subscription same = subscriptionOf(CLIENT_ID);
      when(same.getLastEventAt()).thenReturn(java.util.Date.from(eventAt));

      service.applyLifecycleOutcome(older, pastDue(), eventAt);
      service.applyLifecycleOutcome(newer, pastDue(), eventAt);
      service.applyLifecycleOutcome(same, pastDue(), eventAt);

      verify(older).setLastEventAt(java.util.Date.from(eventAt));
      verify(newer, never()).setLastEventAt(any());
      verify(same, never()).setLastEventAt(any());
    }

    @Test
    void aNullEventInstantLeavesTheWatermarkAlone() {
      Subscription row = subscriptionOf(CLIENT_ID);

      service.applyLifecycleOutcome(row, pastDue(), null);

      verify(row, never()).setLastEventAt(any());
    }

    @Test
    void aTerminatingOutcomeClosesTheRowAtTheProviderEndInstant() {
      Subscription row = subscriptionOf(CLIENT_ID);
      when(row.getStartDate()).thenReturn(java.util.Date.from(anchor.minusSeconds(86_400L * 30)));
      java.time.Instant ended = java.time.Instant.parse("2026-11-15T09:00:00Z");

      service.applyLifecycleOutcome(row, SubscriptionEventOutcome
          .apply(EnvironmentAccessPolicy.SubscriptionStatus.EXPIRED, null).closing(ended), eventAt);

      verify(row).setSubscriptionStatus(SubscriptionService.STATUS_CANCELED);
      verify(row).setEndDate(java.util.Date.from(ended));
    }

    @Test
    void aClosingInstantBeforeTheStartDateClosesAtTheStartDate() {
      // ETGO_SUB_DATES_CHK: END_DATE >= START_DATE. A provider instant earlier than the row's own
      // start (the row was opened after Stripe ended it) must not reject the whole event.
      Subscription row = subscriptionOf(CLIENT_ID);
      java.util.Date start = java.util.Date.from(eventAt);
      when(row.getStartDate()).thenReturn(start);

      service.applyLifecycleOutcome(row, SubscriptionEventOutcome
          .apply(EnvironmentAccessPolicy.SubscriptionStatus.EXPIRED, null)
          .closing(eventAt.minusSeconds(3600L)), eventAt);

      verify(row).setEndDate(start);
    }

    @Test
    void aClosingOutcomeWithNoInstantClosesTheRowNow() {
      Subscription row = subscriptionOf(CLIENT_ID);
      java.util.Date before = new java.util.Date();

      service.applyLifecycleOutcome(row, SubscriptionEventOutcome
          .apply(EnvironmentAccessPolicy.SubscriptionStatus.EXPIRED, null).closing(null), eventAt);

      ArgumentCaptor<java.util.Date> end = ArgumentCaptor.forClass(java.util.Date.class);
      verify(row).setEndDate(end.capture());
      assertTrue(!end.getValue().before(before) && !end.getValue().after(new java.util.Date()),
          "closed 'now': " + end.getValue());
    }

    @Test
    void anAlreadyClosedRowIsNotClosedAgain() {
      Subscription row = subscriptionOf(CLIENT_ID);
      when(row.getEndDate()).thenReturn(java.util.Date.from(eventAt));

      service.applyLifecycleOutcome(row, SubscriptionEventOutcome
          .apply(EnvironmentAccessPolicy.SubscriptionStatus.EXPIRED, null)
          .closing(eventAt.plusSeconds(60L)), eventAt);

      verify(row, never()).setEndDate(any());
    }

    @Test
    void refusesAStatusNoLifecycleEventProduces() {
      Subscription row = subscriptionOf(CLIENT_ID);

      assertThrows(IllegalArgumentException.class, () -> service.applyLifecycleOutcome(row,
          SubscriptionEventOutcome.apply(
              EnvironmentAccessPolicy.SubscriptionStatus.LEGACY_ENTITLEMENT, null), eventAt));
      verify(obDal, never()).save(any());
    }
  }

  /** ETP-5047 — a re-subscription after a cancellation whose close never arrived. */
  @Nested
  class OpenSubscriptionOverACanceledRow {

    private static final String CANCELED_ROW_ID = "0D3C1D0C0A6B4E6C9E5E5B7C2A1F9D11";

    private Plan plan;
    private Subscription created;
    private Session session;
    @SuppressWarnings("rawtypes")
    private org.hibernate.query.Query closeStatement;
    private org.openbravo.model.ad.access.User systemUser;

    @BeforeEach
    void givenACatalogPlanAndANewRow() {
      plan = mock(Plan.class);
      when(plan.getSearchKey()).thenReturn(PLAN_KEY);
      when(plan.getProviderPriceID()).thenReturn(PRICE_ID);
      created = mock(Subscription.class);
      when(obProvider.get(Subscription.class)).thenReturn(created);
      when(obDal.get(eq(Client.class), any())).thenReturn(mock(Client.class));
      when(obDal.get(eq(Organization.class), any())).thenReturn(mock(Organization.class));
      session = mock(Session.class);
      closeStatement = mock(org.hibernate.query.Query.class, org.mockito.Mockito.RETURNS_SELF);
      when(closeStatement.executeUpdate()).thenReturn(1);
      when(session.createQuery(anyString())).thenReturn(closeStatement);
      when(obDal.getSession()).thenReturn(session);
      systemUser = mock(org.openbravo.model.ad.access.User.class);
      OBContext context = mock(OBContext.class);
      when(context.getUser()).thenReturn(systemUser);
      obContextMock.when(OBContext::getOBContext).thenReturn(context);
    }

    private Subscription canceledRow(String status) {
      Subscription canceled = subscriptionOf(CLIENT_ID);
      when(canceled.getId()).thenReturn(CANCELED_ROW_ID);
      when(canceled.getSubscriptionStatus()).thenReturn(status);
      when(subscriptionQuery.uniqueResult()).thenReturn(canceled);
      return canceled;
    }

    @Test
    void closesTheOpenCanceledRowInTheDatabaseBeforeInsertingTheNewOne() {
      Subscription canceled = canceledRow(SubscriptionService.STATUS_CANCELED);

      Subscription result = service.openSubscription(CLIENT_ID, plan, null, CUSTOMER_ID,
          STRIPE_SUBSCRIPTION_ID);

      assertSame(created, result);
      // The close must reach the database before the new row's insert: Hibernate runs inserts
      // before updates, and the partial unique index etgo_sub_open_envclient_uq would reject the
      // new open row while the old one is open. A targeted statement, then a refresh so the
      // in-session entity reads as closed too.
      org.mockito.InOrder order =
          org.mockito.Mockito.inOrder(closeStatement, session, obProvider, obDal);
      order.verify(closeStatement).executeUpdate();
      order.verify(session).refresh(canceled);
      order.verify(obProvider).get(Subscription.class);
      order.verify(obDal).save(created);
    }

    @Test
    void theCloseTouchesOnlyThatRowAndNeverFlushesTheCallersSession() {
      Subscription canceled = canceledRow(SubscriptionService.STATUS_CANCELED);

      service.openSubscription(CLIENT_ID, plan, null, null, null);

      // W1: a flush here would write the caller's whole pending onboarding as system.
      verify(obDal, never()).flush();
      // The entity is refreshed, not edited: a later flush has nothing of it left to write.
      verify(canceled, never()).setEndDate(any());
      ArgumentCaptor<String> hql = ArgumentCaptor.forClass(String.class);
      verify(session).createQuery(hql.capture());
      assertTrue(hql.getValue().startsWith("update ETGO_SUBSCRIPTION set endDate = :endDate, "
          + "updated = :updated, updatedBy = :updatedBy where id = :id"), hql.getValue());
      assertTrue(hql.getValue().endsWith(" and endDate is null"), hql.getValue());
      verify(closeStatement).setParameter("id", CANCELED_ROW_ID);
      verify(closeStatement).setParameter("updatedBy", systemUser);
      verify(closeStatement).setParameter(eq("endDate"), any(java.util.Date.class));
      verify(closeStatement).setParameter(eq("updated"), any(java.util.Date.class));
    }

    @Test
    void theEndDateIsNeverBeforeTheRowsStart() {
      Subscription canceled = canceledRow(SubscriptionService.STATUS_CANCELED);
      java.util.Date futureStart = new java.util.Date(System.currentTimeMillis() + 86_400_000L);
      when(canceled.getStartDate()).thenReturn(futureStart);

      service.openSubscription(CLIENT_ID, plan, null, null, null);

      // ETGO_SUB_DATES_CHK: END_DATE >= START_DATE, exactly as close() guarantees.
      verify(closeStatement).setParameter("endDate", futureStart);
    }

    @Test
    void aRowClosedMeanwhileStillLetsTheNewOneOpen() {
      Subscription canceled = canceledRow(SubscriptionService.STATUS_CANCELED);
      when(closeStatement.executeUpdate()).thenReturn(0);

      assertSame(created, service.openSubscription(CLIENT_ID, plan, null, null, null));
      verify(session).refresh(canceled);
      verify(obDal).save(created);
    }

    @Test
    void aCanceledStatusIsRecognisedInAnyCase() {
      Subscription canceled = canceledRow(" CANCELED ");

      assertSame(created, service.openSubscription(CLIENT_ID, plan, null, null, null));
      verify(closeStatement).executeUpdate();
      verify(session).refresh(canceled);
    }

    @ParameterizedTest
    @ValueSource(strings = { SubscriptionService.STATUS_ACTIVE, SubscriptionService.STATUS_PAST_DUE })
    void returnsALiveOpenRowUntouched(String status) {
      Subscription live = subscriptionOf(CLIENT_ID);
      when(live.getSubscriptionStatus()).thenReturn(status);
      when(subscriptionQuery.uniqueResult()).thenReturn(live);

      assertSame(live, service.openSubscription(CLIENT_ID, plan, null, null, null));

      verify(live, never()).setEndDate(any());
      verify(obDal, never()).flush();
      verify(session, never()).createQuery(anyString());
      verify(obProvider, never()).get(Subscription.class);
      verify(obDal, never()).save(any());
    }
  }

  /** ETP-5047 — the status a row stands for, and the anchor the access policy counts from. */
  @Nested
  class RowReaders {

    private final java.util.Date anchor =
        java.util.Date.from(java.time.Instant.parse("2026-10-31T00:00:00Z"));
    private final java.util.Date periodEnd =
        java.util.Date.from(java.time.Instant.parse("2026-12-01T00:00:00Z"));

    private Subscription row(String status) {
      Subscription row = mock(Subscription.class);
      when(row.getSubscriptionStatus()).thenReturn(status);
      return row;
    }

    @Test
    void effectiveStatusIsTheStoredOneWhileTheRowIsOpen() {
      assertEquals(SubscriptionService.STATUS_PAST_DUE,
          SubscriptionService.effectiveStatusOf(row(SubscriptionService.STATUS_PAST_DUE)));
    }

    @Test
    void effectiveStatusOfAClosedRowIsCanceledWhateverItsStatusSays() {
      Subscription superseded = row(SubscriptionService.STATUS_ACTIVE);
      when(superseded.getEndDate()).thenReturn(periodEnd);

      assertEquals(SubscriptionService.STATUS_CANCELED,
          SubscriptionService.effectiveStatusOf(superseded));
      assertEquals(null, SubscriptionService.effectiveStatusOf(null));
    }

    @Test
    void graceAnchorColumnWins() {
      Subscription pastDue = row(SubscriptionService.STATUS_PAST_DUE);
      when(pastDue.getGraceAnchor()).thenReturn(anchor);
      when(pastDue.getCurrentPeriodEnd()).thenReturn(periodEnd);

      assertEquals(anchor.toInstant(), SubscriptionService.graceAnchorOf(pastDue));
    }

    @Test
    void aPastDueRowInThePreSplitShapeFallsBackToThePeriodEnd() {
      Subscription legacy = row(SubscriptionService.STATUS_PAST_DUE);
      when(legacy.getCurrentPeriodEnd()).thenReturn(periodEnd);

      assertEquals(periodEnd.toInstant(), SubscriptionService.graceAnchorOf(legacy));
    }

    @Test
    void noFallbackWhenThePeriodHasAStartBecauseThatIsARealBillingPeriod() {
      Subscription pastDue = row(SubscriptionService.STATUS_PAST_DUE);
      when(pastDue.getCurrentPeriodStart()).thenReturn(anchor);
      when(pastDue.getCurrentPeriodEnd()).thenReturn(periodEnd);

      assertEquals(null, SubscriptionService.graceAnchorOf(pastDue));
    }

    @ParameterizedTest
    @ValueSource(strings = { SubscriptionService.STATUS_ACTIVE, SubscriptionService.STATUS_CANCELED })
    void noFallbackForARowThatIsNotPastDue(String status) {
      Subscription notPastDue = row(status);
      when(notPastDue.getCurrentPeriodEnd()).thenReturn(periodEnd);

      assertEquals(null, SubscriptionService.graceAnchorOf(notPastDue));
    }

    @Test
    void noFallbackForAClosedRowWhoseStatusStillSaysPastDue() {
      Subscription closed = row(SubscriptionService.STATUS_PAST_DUE);
      when(closed.getCurrentPeriodEnd()).thenReturn(periodEnd);
      when(closed.getEndDate()).thenReturn(periodEnd);

      assertEquals(null, SubscriptionService.graceAnchorOf(closed));
    }

    @Test
    void noFallbackWithNoPeriodEndAndNullForANullRow() {
      assertEquals(null, SubscriptionService.graceAnchorOf(row(SubscriptionService.STATUS_PAST_DUE)));
      assertEquals(null, SubscriptionService.graceAnchorOf(null));
    }

    @Test
    void lastEventAtReadsTheWatermarkColumn() {
      Subscription withWatermark = row(SubscriptionService.STATUS_ACTIVE);
      when(withWatermark.getLastEventAt()).thenReturn(anchor);

      assertEquals(anchor.toInstant(), SubscriptionService.lastEventAtOf(withWatermark));
      assertEquals(null, SubscriptionService.lastEventAtOf(row(SubscriptionService.STATUS_ACTIVE)));
      assertEquals(null, SubscriptionService.lastEventAtOf(null));
    }
  }

  @Test
  void opensASystemContextOnlyWhenTheCallerHasNone() {
    obContextMock.when(OBContext::getOBContext).thenReturn(mock(OBContext.class));
    when(subscriptionQuery.uniqueResult()).thenReturn(null);

    Optional<Subscription> ignored = service.findOpen(CLIENT_ID);

    assertTrue(ignored.isEmpty());
    // The environment-list and onboarding callers arrive holding a context the rest of their work
    // depends on, and setOBContext has no stack of its own — overwriting it would silently hand
    // them a system context for the remainder of the request.
    obContextMock.verify(() -> OBContext.setOBContext("0", "0", "0", "0"), never());
  }
}
