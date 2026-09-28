/*
 * *************************************************************************
 * Etendo License. See https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * *************************************************************************
 */
package com.etendoerp.go.payment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.dal.service.OBQuery;

import com.etendoerp.go.schemaforge.data.Plan;

/**
 * Unit specs for {@link PlanCatalogService} (ETP-5046).
 *
 * <p>The catalog replaced a single deployment property as the answer to "what can be bought", so
 * the two things that matter are that the lookup is scoped to active rows and that "exists" and
 * "is sellable" stay separate questions — they map onto different HTTP answers at the call site.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PlanCatalogServiceTest {

  private static final String PLAN_KEY = "productive-monthly";

  @Mock private OBDal obDal;
  @Mock private OBQuery<Plan> planQuery;

  private MockedStatic<OBDal> obDalMock;
  private MockedStatic<OBContext> obContextMock;

  private final PlanCatalogService service = new PlanCatalogService();

  @BeforeEach
  void setUp() {
    obDalMock = mockStatic(OBDal.class);
    obContextMock = mockStatic(OBContext.class);
    obDalMock.when(OBDal::getInstance).thenReturn(obDal);
    when(obDal.createQuery(eq(Plan.class), anyString())).thenReturn(planQuery);
  }

  @AfterEach
  void tearDown() {
    obDalMock.close();
    obContextMock.close();
  }

  @Test
  void findsTheActiveCatalogRowUnderTheKey() {
    Plan plan = mock(Plan.class);
    when(planQuery.uniqueResult()).thenReturn(plan);

    assertSame(plan, service.findPurchasablePlan(PLAN_KEY).orElseThrow());
  }

  @Test
  void returnsEmptyWhenTheKeyNamesNothingUsable() {
    when(planQuery.uniqueResult()).thenReturn(null);

    // Unknown and inactive are one answer on purpose: the checkout endpoint must not confirm
    // which keys exist, so it cannot be handed two distinguishable outcomes here.
    assertTrue(service.findPurchasablePlan(PLAN_KEY).isEmpty());
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(strings = { "", "   " })
  void returnsEmptyForABlankKeyWithoutQuerying(String planKey) {
    assertTrue(service.findPurchasablePlan(planKey).isEmpty());

    verify(obDal, never()).createQuery(eq(Plan.class), anyString());
  }

  @Test
  void restrictsTheLookupToActiveRowsAndIgnoresContextVisibility() {
    when(planQuery.uniqueResult()).thenReturn(mock(Plan.class));

    service.findPurchasablePlan(PLAN_KEY);

    ArgumentCaptor<String> hql = ArgumentCaptor.forClass(String.class);
    verify(obDal).createQuery(eq(Plan.class), hql.capture());
    assertTrue(hql.getValue().contains("active = true"), hql.getValue());
    verify(planQuery).setNamedParameter("searchKey", PLAN_KEY);
    // Catalog rows live at client 0 and are read while serving a request whose context belongs to
    // a tenant. With these filters on, every key would look unknown.
    verify(planQuery).setFilterOnReadableClients(false);
    verify(planQuery).setFilterOnReadableOrganization(false);
  }

  @Test
  void listsOnlyTheRowsThatCanActuallyBeBought() {
    Plan sellable = planWithPrice("price_123");
    Plan legacy = planWithPrice("   ");
    when(planQuery.list()).thenReturn(List.of(sellable, legacy));

    // The grandfathered row is a real, active plan — it simply cannot start a checkout, so a
    // client offering it would be offering a 503. Same predicate as the checkout endpoint uses,
    // never a second blank check hand-rolled into the query string.
    assertEquals(List.of(sellable), service.listPurchasablePlans());
  }

  @Test
  void listsNothingRatherThanFailingWhenTheCatalogIsEmpty() {
    when(planQuery.list()).thenReturn(Collections.emptyList());

    // "Nothing is on sale" is an answer, not an error: the endpoint above turns this into an
    // empty array and a 200.
    assertTrue(service.listPurchasablePlans().isEmpty());
  }

  @Test
  void restrictsTheListingToActiveRowsAndIgnoresContextVisibility() {
    when(planQuery.list()).thenReturn(Collections.emptyList());

    service.listPurchasablePlans();

    ArgumentCaptor<String> hql = ArgumentCaptor.forClass(String.class);
    verify(obDal).createQuery(eq(Plan.class), hql.capture());
    assertTrue(hql.getValue().contains("active = true"), hql.getValue());
    verify(planQuery).setFilterOnReadableClients(false);
    verify(planQuery).setFilterOnReadableOrganization(false);
  }

  private static Plan planWithPrice(String providerPriceId) {
    Plan plan = mock(Plan.class);
    when(plan.getProviderPriceID()).thenReturn(providerPriceId);
    return plan;
  }

  @Test
  void treatsAPlanWithNoProviderPriceAsNotSellable() {
    Plan legacy = mock(Plan.class);
    when(legacy.getProviderPriceID()).thenReturn("  ");

    // The grandfathered legacy plan has no price id BY DESIGN — it predates provider billing and
    // exists so backfilled tenants have a catalog row to point at. It is a real, valid plan that
    // simply cannot start a checkout, which is why this is a separate question from "does it
    // exist".
    assertFalse(service.hasProviderPrice(legacy));
    assertFalse(service.hasProviderPrice(null));
  }

  @Test
  void treatsAPlanCarryingAProviderPriceAsSellable() {
    Plan plan = mock(Plan.class);
    when(plan.getProviderPriceID()).thenReturn("price_123");

    assertTrue(service.hasProviderPrice(plan));
  }

  // ===================== legacy price fallback (ETP-5046 §6.1) =====================

  private static final String LEGACY_PRICE_ID = "price_LEGACY_configured";

  @Test
  void theFallbackIsActiveWhenALegacyPriceIsConfiguredAndNothingIsPriced() {
    service.configuredFallbackPriceId = () -> LEGACY_PRICE_ID;
    // The grandfathered row itself is active but priceless: it must not count as "priced", or the
    // fallback would retire itself on the very row it exists to sell.
    Plan legacy = planWithPrice(null);
    when(planQuery.list()).thenReturn(List.of(legacy));

    assertTrue(service.isLegacyFallbackActive());
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(strings = { "", "   " })
  void theFallbackIsInactiveWithoutAConfiguredLegacyPrice(String configured) {
    service.configuredFallbackPriceId = () -> configured;
    when(planQuery.list()).thenReturn(Collections.emptyList());

    // No price configured means nothing to sell — never a price nobody reviewed.
    assertFalse(service.isLegacyFallbackActive());
    // Decided on the property alone: the catalog is not even consulted.
    verify(obDal, never()).createQuery(eq(Plan.class), anyString());
  }

  @Test
  void theFirstPricedPlanRetiresTheFallback() {
    service.configuredFallbackPriceId = () -> LEGACY_PRICE_ID;
    Plan legacy = planWithPrice(null);
    Plan priced = planWithPrice("price_123");
    when(planQuery.list()).thenReturn(List.of(legacy, priced));

    // No redeploy, no property change: the next request sees the priced plan and the fallback is
    // gone, even though the legacy property is still set.
    assertFalse(service.isLegacyFallbackActive());
  }

  @Test
  void onlyActivePricedPlansRetireTheFallback() {
    service.configuredFallbackPriceId = () -> LEGACY_PRICE_ID;
    when(planQuery.list()).thenReturn(Collections.emptyList());

    service.isLegacyFallbackActive();

    // An inactive priced row is not for sale, so it must not switch off the one thing that is.
    // The predicate reuses listPurchasablePlans, whose query is restricted to active rows.
    ArgumentCaptor<String> hql = ArgumentCaptor.forClass(String.class);
    verify(obDal).createQuery(eq(Plan.class), hql.capture());
    assertTrue(hql.getValue().contains("active = true"), hql.getValue());
  }

  @Test
  void findsTheGrandfatheredPlanWhileTheFallbackIsActive() {
    service.configuredFallbackPriceId = () -> LEGACY_PRICE_ID;
    when(planQuery.list()).thenReturn(Collections.emptyList());
    Plan legacy = planWithPrice(null);
    when(planQuery.uniqueResult()).thenReturn(legacy);

    assertSame(legacy, service.findLegacyFallbackPlan().orElseThrow());
    verify(planQuery).setNamedParameter("searchKey", PlanCatalogService.LEGACY_PLAN_KEY);
  }

  @Test
  void findsNoGrandfatheredPlanOnceTheFallbackIsInactive() {
    service.configuredFallbackPriceId = () -> LEGACY_PRICE_ID;
    Plan priced = planWithPrice("price_123");
    Plan legacy = planWithPrice(null);
    when(planQuery.list()).thenReturn(List.of(priced));
    when(planQuery.uniqueResult()).thenReturn(legacy);

    // The row still exists and is still active — it is the fallback that is gone, and with it the
    // only way the priceless plan could be sold.
    assertTrue(service.findLegacyFallbackPlan().isEmpty());
    verify(planQuery, never()).setNamedParameter("searchKey", PlanCatalogService.LEGACY_PLAN_KEY);
  }

  @Test
  void findsNoGrandfatheredPlanWhenItsRowIsMissingOrInactive() {
    service.configuredFallbackPriceId = () -> LEGACY_PRICE_ID;
    when(planQuery.list()).thenReturn(Collections.emptyList());
    when(planQuery.uniqueResult()).thenReturn(null);

    assertTrue(service.findLegacyFallbackPlan().isEmpty());
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(strings = { "", "   ", "legacy-productive", "  legacy-productive  " })
  void aBlankKeyOrTheLegacyKeyAsksForTheGrandfatheredPlan(String planKey) {
    // A blank key is how an app-shell older than the plan catalog asks for "the" product.
    assertTrue(PlanCatalogService.namesLegacyPlan(planKey));
  }

  @ParameterizedTest
  @ValueSource(strings = { "productive-monthly", "legacy-productive-2", "tampered-key" })
  void anyOtherKeyDoesNotAskForTheGrandfatheredPlan(String planKey) {
    assertFalse(PlanCatalogService.namesLegacyPlan(planKey));
  }
}
