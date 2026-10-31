/*
 * *************************************************************************
 * Etendo License. See https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * *************************************************************************
 */
package com.etendoerp.go.payment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
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
import com.etendoerp.go.schemaforge.data.PlanChange;

/**
 * ETP-5053 — the Allowed plan changes configuration is the only source of which transitions a
 * customer may ask for: no row, no change.
 *
 * @covers com.etendoerp.go.payment.PlanChangeCatalog
 * @covers com.etendoerp.go.payment.PlanChangeTiming
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PlanChangeCatalogTest {

  @Mock private OBDal obDal;
  @Mock private OBQuery<PlanChange> changeQuery;
  private MockedStatic<OBDal> obDalMock;
  private MockedStatic<OBContext> obContextMock;
  private final PlanChangeCatalog catalog = new PlanChangeCatalog();

  @BeforeEach
  void setUp() {
    obDalMock = mockStatic(OBDal.class);
    obContextMock = mockStatic(OBContext.class);
    obDalMock.when(OBDal::getInstance).thenReturn(obDal);
    when(obDal.createQuery(eq(PlanChange.class), anyString())).thenReturn(changeQuery);
  }

  @AfterEach
  void tearDown() {
    obDalMock.close();
    obContextMock.close();
  }

  private static Plan plan(String id) {
    Plan plan = mock(Plan.class);
    when(plan.getId()).thenReturn(id);
    return plan;
  }

  @Test
  void listsNothingForANullPlanWithoutQuerying() {
    assertTrue(catalog.listAllowed(null).isEmpty());
    verify(obDal, never()).createQuery(eq(PlanChange.class), anyString());
  }

  @Test
  void listsOnlyActiveRowsWithAnActiveTargetFromThePlanCheapestTargetFirst() {
    PlanChange toPro = mock(PlanChange.class);
    PlanChange toScale = mock(PlanChange.class);
    when(changeQuery.list()).thenReturn(List.of(toPro, toScale));

    List<PlanChange> allowed = catalog.listAllowed(plan("CORE"));

    assertEquals(List.of(toPro, toScale), allowed);
    ArgumentCaptor<String> hql = ArgumentCaptor.forClass(String.class);
    verify(obDal).createQuery(eq(PlanChange.class), hql.capture());
    String query = hql.getValue();
    assertTrue(query.contains("change.plan.id = :planId"), query);
    assertTrue(query.contains("change.active = true"), query);
    assertTrue(query.contains("change.targetPlan.active = true"), query);
    assertTrue(query.contains("order by change.targetPlan.billingInterval,"
        + " change.targetPlan.displayPrice, change.targetPlan.searchKey"), query);
    verify(changeQuery).setNamedParameter("planId", "CORE");
    // System rows (client 0), read from a tenant's request context.
    verify(changeQuery).setFilterOnReadableClients(false);
    verify(changeQuery).setFilterOnReadableOrganization(false);
  }

  @Test
  void findsTheOneActiveRowForAFromAndTargetPair() {
    PlanChange row = mock(PlanChange.class);
    when(changeQuery.uniqueResult()).thenReturn(row);

    Optional<PlanChange> found = catalog.findAllowed(plan("CORE"), plan("PRO"));

    assertSame(row, found.orElseThrow());
    ArgumentCaptor<String> hql = ArgumentCaptor.forClass(String.class);
    verify(obDal).createQuery(eq(PlanChange.class), hql.capture());
    assertTrue(hql.getValue().contains("change.targetPlan.id = :targetId"), hql.getValue());
    verify(changeQuery).setNamedParameter("planId", "CORE");
    verify(changeQuery).setNamedParameter("targetId", "PRO");
    verify(changeQuery).setMaxResult(1);
  }

  @Test
  void aChangeWithNoActiveRowIsNotAllowed() {
    when(changeQuery.uniqueResult()).thenReturn(null);

    assertFalse(catalog.findAllowed(plan("PRO"), plan("CORE")).isPresent());
  }

  @Test
  void theSamePlanTwiceOrANullPlanIsNeverAllowedAndNeverQueried() {
    Plan core = plan("CORE");

    assertFalse(catalog.findAllowed(core, core).isPresent());
    assertFalse(catalog.findAllowed(null, core).isPresent());
    assertFalse(catalog.findAllowed(core, null).isPresent());
    verify(obDal, never()).createQuery(eq(PlanChange.class), anyString());
  }

  @Test
  void theTimingIsWhatTheRowDeclares() {
    PlanChange immediate = mock(PlanChange.class);
    when(immediate.getTiming()).thenReturn("immediate");
    PlanChange deferred = mock(PlanChange.class);
    when(deferred.getTiming()).thenReturn("period_end");

    assertEquals(PlanChangeTiming.IMMEDIATE, PlanChangeCatalog.timingOf(immediate));
    assertEquals(PlanChangeTiming.PERIOD_END, PlanChangeCatalog.timingOf(deferred));
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = { "now", "IMMEDIATE", " period end " })
  void anUnknownStoredTimingIsAnErrorNotADefault(String stored) {
    PlanChange row = mock(PlanChange.class);
    when(row.getId()).thenReturn("ROW-1");
    when(row.getTiming()).thenReturn(stored);

    assertThrows(IllegalStateException.class, () -> PlanChangeCatalog.timingOf(row));
    assertFalse(PlanChangeTiming.of(stored).isPresent());
  }

  @Test
  void theStoredSpellingsMatchTheCheckConstraint() {
    assertEquals("immediate", PlanChangeTiming.IMMEDIATE.value());
    assertEquals("period_end", PlanChangeTiming.PERIOD_END.value());
    assertEquals(Optional.of(PlanChangeTiming.PERIOD_END), PlanChangeTiming.of(" period_end "));
  }
}
