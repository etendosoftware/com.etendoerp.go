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
}
