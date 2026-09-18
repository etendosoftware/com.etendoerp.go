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
