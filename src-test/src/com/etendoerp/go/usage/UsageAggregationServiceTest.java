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

package com.etendoerp.go.usage;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.MockedStatic;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.enterprise.Organization;

import com.etendoerp.go.schemaforge.data.BillingResource;
import com.etendoerp.go.schemaforge.data.UsageDaily;

/**
 * Unit specs for the transaction boundary of {@link UsageAggregationService} (ETP-5050).
 *
 * <p>{@code OBDal}, {@code OBProvider}, {@link UsageSettings} and {@link UsageCounterLookup} are
 * mocked statically; nothing here touches a database. The sibling
 * {@code UsageAggregationServiceIntegrationTest} covers what only a real database can falsify
 * (the unique key, containment, per-tenant finality). What this class covers is the thing a
 * database test would find hardest to pin down precisely: <b>which unit of work commits, and what
 * a failure takes down with it</b>.
 *
 * <h2>The regression this class exists for</h2>
 *
 * <p>The run originally iterated resource ENTITIES fetched once up front, wrote each resource-day
 * with only a {@code flush()}, and on any per-resource failure called
 * {@code OBDal.rollbackAndClose()}. Nothing committed until {@code DalBaseProcess} finished, so
 * that rollback discarded <em>the whole run so far</em> — every earlier day and every earlier
 * resource — while {@code result.getRowsWritten()} still counted the discarded rows as written.
 * A single misconfigured resource silently threw away a whole night's aggregation and reported
 * success. Closing the session also detached the resource entities still held by the loop, so one
 * failure could cascade into failing everything after it.
 *
 * <p>The fix makes each resource-day its own transaction: ids are iterated instead of entities,
 * each resource is re-read fresh inside its unit, and a successful unit commits. The tests below
 * assert both halves — that a failure is CONTAINED to its own unit, and that the surviving work
 * was actually COMMITTED rather than merely flushed. Asserting only "the second resource was
 * still saved" would pass under the old code too, because the old code also kept looping; it was
 * the rollback of already-flushed work that made the bug invisible. So the commit itself is
 * verified, in order, against the failure.
 */
class UsageAggregationServiceTest {

  private static final String FAILING_ID = "RESOURCE_THAT_FAILS";
  private static final String HEALTHY_ID = "RESOURCE_THAT_WORKS";
  private static final String QUALIFIER = "healthy-counter";
  private static final String MEASURED_CLIENT_ID = "A1B2C3D4E5F60718293A4B5C6D7E8F90";
  private static final long COUNTED = 17L;
  /** A second tenant, used for the row that stops counting. */
  private static final String OTHER_CLIENT_ID = "0FEDCBA98765432100FEDCBA98765432";

  private MockedStatic<OBContext> obContext;
  private MockedStatic<OBDal> obDalStatic;
  private MockedStatic<OBProvider> obProviderStatic;
  private MockedStatic<UsageSettings> usageSettings;
  private MockedStatic<UsageCounterLookup> counterLookup;

  private OBDal obDal;
  private UsageDaily writtenRow;
  private OBCriteria<UsageDaily> usageCriteria;

  private static Date day() {
    Calendar cal = Calendar.getInstance();
    cal.clear();
    cal.set(2026, Calendar.MARCH, 10, 0, 0, 0);
    return cal.getTime();
  }

  @BeforeEach
  void setUp() {
    // run() now enters admin mode itself (it reads across every tenant), so OBContext has to be
    // stubbed or the static call finds no thread-bound context.
    obContext = mockStatic(OBContext.class);
    obDalStatic = mockStatic(OBDal.class);
    obProviderStatic = mockStatic(OBProvider.class);
    usageSettings = mockStatic(UsageSettings.class);
    counterLookup = mockStatic(UsageCounterLookup.class);

    obDal = mock(OBDal.class);
    obDalStatic.when(OBDal::getInstance).thenReturn(obDal);

    usageSettings.when(() -> UsageSettings.getSettlingWindowDays(anyString())).thenReturn(5);

    // The upsert path: no existing row, so a fresh one is provisioned and saved.
    writtenRow = mock(UsageDaily.class);
    OBProvider provider = mock(OBProvider.class);
    obProviderStatic.when(OBProvider::getInstance).thenReturn(provider);
    when(provider.get(UsageDaily.class)).thenReturn(writtenRow);
    when(obDal.get(eq(Client.class), any())).thenReturn(mock(Client.class));
    when(obDal.get(eq(Organization.class), any())).thenReturn(mock(Organization.class));
    givenNoExistingUsageRow();
  }

  @AfterEach
  void tearDown() {
    counterLookup.close();
    obContext.close();
    usageSettings.close();
    obProviderStatic.close();
    obDalStatic.close();
  }

  /** The catalog scan returns these ids, in this order. */
  @SuppressWarnings("unchecked")
  private void givenCatalog(String... resourceIds) {
    OBCriteria<BillingResource> criteria = mock(OBCriteria.class);
    when(obDal.createCriteria(BillingResource.class)).thenReturn(criteria);

    List<BillingResource> rows = new ArrayList<>();
    for (String id : resourceIds) {
      BillingResource row = mock(BillingResource.class);
      when(row.getId()).thenReturn(id);
      rows.add(row);
    }
    when(criteria.list()).thenReturn(rows);
  }

  /** A resource that blows up when counted: an unknown counting mode is the cheapest way. */
  private void givenAFailingResource(String id) {
    BillingResource resource = mock(BillingResource.class);
    when(resource.getId()).thenReturn(id);
    when(resource.getSearchKey()).thenReturn(id);
    when(resource.getCountingMode()).thenReturn("UNKNOWN_MODE");
    when(obDal.get(BillingResource.class, id)).thenReturn(resource);
  }

  /** A strategy resource whose counter reports one tenant with a non-zero value. */
  private void givenAHealthyResource(String id) {
    BillingResource resource = mock(BillingResource.class);
    when(resource.getId()).thenReturn(id);
    when(resource.getSearchKey()).thenReturn(id);
    when(resource.getCountingMode()).thenReturn(UsageResourceValidator.MODE_STRATEGY);
    when(resource.getStrategyQualifier()).thenReturn(QUALIFIER);
    when(obDal.get(BillingResource.class, id)).thenReturn(resource);

    // Typed explicitly: MockedStatic infers the stubbed type as Object, so a bare lambda has no
    // target type to become a UsageResourceCounter.
    UsageResourceCounter counter = request -> Arrays
        .asList(new DailyCount(MEASURED_CLIENT_ID, request.getFrom(), COUNTED));
    counterLookup.when(() -> UsageCounterLookup.byQualifier(QUALIFIER)).thenReturn(counter);
  }

  @SuppressWarnings("unchecked")
  private void givenNoExistingUsageRow() {
    usageCriteria = mock(OBCriteria.class);
    when(obDal.createCriteria(UsageDaily.class)).thenReturn(usageCriteria);
    when(usageCriteria.uniqueResult()).thenReturn(null);
  }

  /**
   * A row already stored for the measured tenant on the counted day. {@code find(...)} reads it
   * through {@code uniqueResult()}; the zero-out sweep reads the same criteria through
   * {@code list()}, so both are stubbed to agree.
   */
  private UsageDaily givenStoredRowForCountedTenant(boolean settled, long quantity) {
    UsageDaily stored = storedRow(MEASURED_CLIENT_ID, settled, quantity);
    when(usageCriteria.uniqueResult()).thenReturn(stored);
    when(usageCriteria.list()).thenReturn(Arrays.asList(stored));
    return stored;
  }

  /**
   * A row stored for a tenant the recount does NOT report — the tenant that dropped to zero.
   * {@code find(...)} still answers null, because the counted tenant has no row of its own.
   */
  private UsageDaily givenStoredRowForATenantThatNoLongerCounts(boolean settled, long quantity) {
    UsageDaily stored = storedRow(OTHER_CLIENT_ID, settled, quantity);
    when(usageCriteria.list()).thenReturn(Arrays.asList(stored));
    return stored;
  }

  private UsageDaily storedRow(String clientId, boolean settled, long quantity) {
    UsageDaily stored = mock(UsageDaily.class);
    Client measured = mock(Client.class);
    when(measured.getId()).thenReturn(clientId);
    when(stored.getMeasuredClient()).thenReturn(measured);
    when(stored.isSettled()).thenReturn(settled);
    when(stored.getQuantity()).thenReturn(quantity);
    return stored;
  }

  @Nested
  @DisplayName("the transaction boundary")
  class TransactionBoundary {

    /**
     * THE REGRESSION TEST. The FIRST resource of the run fails; the second must still be written
     * AND committed. Under the old code the second resource was also reached and saved — the loop
     * always continued — so "was it saved" alone does not distinguish the fix. What does is that
     * its work reaches a commit of its own, and that the failing resource's rollback is a
     * separate event from it.
     */
    @Test
    void aFailingResourceDoesNotDiscardTheWorkOfTheOnesAroundIt() {
      givenCatalog(FAILING_ID, HEALTHY_ID);
      givenAFailingResource(FAILING_ID);
      givenAHealthyResource(HEALTHY_ID);

      UsageAggregationResult result = new UsageAggregationService().run(day(), day());

      assertAll(
          () -> verify(obDal).save(writtenRow),
          () -> verify(obDal, times(1)).commitAndClose(),
          () -> verify(obDal, times(1)).rollbackAndClose(),
          () -> assertEquals(1, result.getResourcesFailed(), "the failure is reported"),
          () -> assertEquals(1, result.getRowsWritten(),
              "and only work that actually committed is counted as written"));
    }

    /**
     * The order is the point: the rollback belongs to the failing unit and happens BEFORE the
     * healthy resource's work, so it cannot reach it. A rollback issued after the commit would
     * be harmless too, but a single {@code rollbackAndClose} straddling both — the old bug —
     * would show up here as a rollback with no commit before it.
     */
    @Test
    void theFailingUnitRollsBackBeforeTheHealthyUnitCommits() {
      givenCatalog(FAILING_ID, HEALTHY_ID);
      givenAFailingResource(FAILING_ID);
      givenAHealthyResource(HEALTHY_ID);

      new UsageAggregationService().run(day(), day());

      InOrder order = inOrder(obDal);
      order.verify(obDal).rollbackAndClose();
      order.verify(obDal).save(writtenRow);
      order.verify(obDal).commitAndClose();
    }

    /** A clean run commits once per resource-day and rolls nothing back. */
    @Test
    void aSuccessfulResourceDayCommitsAndDoesNotRollBack() {
      givenCatalog(HEALTHY_ID);
      givenAHealthyResource(HEALTHY_ID);

      UsageAggregationResult result = new UsageAggregationService().run(day(), day());

      assertAll(() -> verify(obDal, times(1)).commitAndClose(),
          () -> verify(obDal, never()).rollbackAndClose(),
          () -> assertEquals(0, result.getResourcesFailed()),
          () -> assertEquals(1, result.getRowsWritten()));
    }

    /**
     * One transaction per resource PER DAY, not one per run: a three-day backfill over one
     * resource commits three times. Committing once per run would restore the old blast radius
     * for anything that failed late in the range.
     */
    @Test
    void eachResourceDayIsItsOwnTransaction() {
      givenCatalog(HEALTHY_ID);
      givenAHealthyResource(HEALTHY_ID);

      Date from = day();
      Date to = UsageDayRange.nextDay(UsageDayRange.nextDay(from));

      UsageAggregationResult result = new UsageAggregationService().run(from, to);

      assertAll(() -> assertEquals(3, result.getDaysProcessed()),
          () -> verify(obDal, times(3)).commitAndClose());
    }

    /**
     * A failed unit contributes nothing to the written count. The old code incremented rows for
     * work it then rolled back, so the summary an operator read reported a night that had not
     * happened.
     */
    @Test
    void rowsFromAFailedUnitAreNotCountedAsWritten() {
      givenCatalog(FAILING_ID);
      givenAFailingResource(FAILING_ID);

      UsageAggregationResult result = new UsageAggregationService().run(day(), day());

      assertAll(() -> assertEquals(0, result.getRowsWritten()),
          () -> assertEquals(1, result.getResourcesFailed()),
          () -> verify(obDal, never()).commitAndClose());
    }
  }

  @Nested
  @DisplayName("iterating ids rather than entities")
  class IdIteration {

    /**
     * Each resource is re-read inside its own unit. This is not a style choice: a commit closes
     * the session, so an entity held across the loop would be detached from the second unit
     * onwards and any lazy read on it would throw — one failure cascading into failing
     * everything after it, which is the second half of the bug this fix addresses.
     */
    @Test
    void eachResourceIsReReadFreshInsideItsOwnUnit() {
      givenCatalog(HEALTHY_ID);
      givenAHealthyResource(HEALTHY_ID);

      Date from = day();
      Date to = UsageDayRange.nextDay(from);

      new UsageAggregationService().run(from, to);

      verify(obDal, times(2)).get(BillingResource.class, HEALTHY_ID);
    }

    /**
     * A resource deactivated or deleted between the catalog scan and its turn resolves to null.
     * That is an ordinary race, not a failure: it is skipped silently rather than counted as a
     * broken resource, which would otherwise turn every catalog edit during a run into a warning.
     *
     * <p>It is skipped from the PROCESSED count too. The summary an operator reads is the only
     * account of what the run did, so a resource that was never counted must not appear in it as
     * though it had been — that would read as "counted, and found nothing".
     */
    @Test
    void aResourceThatVanishedBetweenTheScanAndItsTurnIsSkippedNotFailed() {
      givenCatalog(HEALTHY_ID, "GONE");
      givenAHealthyResource(HEALTHY_ID);
      when(obDal.get(BillingResource.class, "GONE")).thenReturn(null);

      UsageAggregationResult result = new UsageAggregationService().run(day(), day());

      assertAll(() -> assertEquals(0, result.getResourcesFailed()),
          () -> assertEquals(1, result.getResourcesProcessed(),
              "the vanished resource was never counted, so it is not reported as processed"),
          () -> assertEquals(1, result.getRowsWritten()),
          () -> verify(obDal, times(1)).commitAndClose());
    }
  }

  @Nested
  @DisplayName("an empty catalog")
  class EmptyCatalog {

    /** Nothing to count is not an error, and must not open or commit a transaction. */
    @Test
    void anEmptyCatalogDoesNothingAtAll() {
      givenCatalog();

      UsageAggregationResult result = new UsageAggregationService().run(day(), day());

      assertAll(() -> assertEquals(0, result.getDaysProcessed()),
          () -> assertEquals(0, result.getResourcesProcessed()),
          () -> assertEquals(0, result.getRowsWritten()),
          () -> assertEquals(0, result.getResourcesFailed()),
          () -> verify(obDal, never()).commitAndClose(),
          () -> verify(obDal, never()).rollbackAndClose());
    }
  }

  @Nested
  @DisplayName("the settling-window run")
  class SettlingWindowRun {

    /**
     * The scheduled run iterates the MAXIMUM configured window, inclusive of today, because it
     * counts every tenant in one grouped query per day and so cannot use a per-tenant range.
     * Using the system window instead would leave a longer-windowed tenant's older days flagged
     * unsettled yet never recomputed.
     */
    @Test
    void coversTheMaximumWindowPlusToday() {
      givenCatalog(HEALTHY_ID);
      givenAHealthyResource(HEALTHY_ID);
      usageSettings.when(UsageSettings::getMaxSettlingWindowDays).thenReturn(3);

      UsageAggregationResult result = new UsageAggregationService().runForSettlingWindow();

      assertEquals(4, result.getDaysProcessed(), "today plus the three days behind it");
    }

    @Test
    void aZeroWindowStillProcessesToday() {
      givenCatalog(HEALTHY_ID);
      givenAHealthyResource(HEALTHY_ID);
      usageSettings.when(UsageSettings::getMaxSettlingWindowDays).thenReturn(0);

      assertEquals(1, new UsageAggregationService().runForSettlingWindow().getDaysProcessed());
    }
  }

  @Nested
  @DisplayName("the value written")
  class ValueWritten {

    /** The counter's value reaches the row verbatim; nothing rescales or accumulates it. */
    @Test
    void theCountersValueIsStoredAsIs() {
      givenCatalog(HEALTHY_ID);
      givenAHealthyResource(HEALTHY_ID);

      new UsageAggregationService().run(day(), day());

      verify(writtenRow).setQuantity(COUNTED);
    }

    /**
     * Finality is decided per tenant from that tenant's own window, not from a run-wide value.
     * Here the day is well outside a 5-day window, so the row is written settled.
     */
    @Test
    void theSettledFlagComesFromTheMeasuredTenantsOwnWindow() {
      givenCatalog(HEALTHY_ID);
      givenAHealthyResource(HEALTHY_ID);

      new UsageAggregationService().run(day(), day());

      assertAll(
          () -> verify(writtenRow).setSettled(true),
          () -> usageSettings
              .verify(() -> UsageSettings.getSettlingWindowDays(MEASURED_CLIENT_ID)));
    }

    /** Today is inside every window, so a row for today is never written settled. */
    @Test
    void todayIsWrittenUnsettled() {
      givenCatalog(HEALTHY_ID);
      givenAHealthyResource(HEALTHY_ID);
      Date today = UsageDayRange.startOfDay(new Date());

      new UsageAggregationService().run(today, today);

      verify(writtenRow).setSettled(false);
    }

    /**
     * A strategy that returns null is treated as "nothing to record", not as a crash: an
     * implementer returning null instead of an empty list is a mistake the run absorbs rather
     * than a reason to fail a resource that may simply have had no activity.
     */
    @Test
    void aStrategyReturningNullRecordsNothingAndDoesNotFail() {
      givenCatalog(HEALTHY_ID);
      BillingResource resource = mock(BillingResource.class);
      when(resource.getId()).thenReturn(HEALTHY_ID);
      when(resource.getSearchKey()).thenReturn(HEALTHY_ID);
      when(resource.getCountingMode()).thenReturn(UsageResourceValidator.MODE_STRATEGY);
      when(resource.getStrategyQualifier()).thenReturn(QUALIFIER);
      when(obDal.get(BillingResource.class, HEALTHY_ID)).thenReturn(resource);
      UsageResourceCounter nullReturningCounter = request -> null;
      counterLookup.when(() -> UsageCounterLookup.byQualifier(QUALIFIER))
          .thenReturn(nullReturningCounter);

      UsageAggregationResult result = new UsageAggregationService().run(day(), day());

      assertAll(() -> assertEquals(0, result.getResourcesFailed()),
          () -> assertEquals(0, result.getRowsWritten()),
          () -> verify(obDal, never()).save(any()));
    }

    /**
     * A qualifier nothing carries fails the resource loudly. Recording nothing would be
     * indistinguishable from genuinely zero usage and would under-bill silently for as long as
     * the typo survived.
     */
    @Test
    void anUndeployedQualifierFailsTheResourceRatherThanRecordingZero() {
      givenCatalog(HEALTHY_ID);
      BillingResource resource = mock(BillingResource.class);
      when(resource.getId()).thenReturn(HEALTHY_ID);
      when(resource.getSearchKey()).thenReturn(HEALTHY_ID);
      when(resource.getCountingMode()).thenReturn(UsageResourceValidator.MODE_STRATEGY);
      when(resource.getStrategyQualifier()).thenReturn("no-such-counter");
      when(obDal.get(BillingResource.class, HEALTHY_ID)).thenReturn(resource);
      counterLookup.when(() -> UsageCounterLookup.byQualifier("no-such-counter"))
          .thenReturn(null);
      counterLookup.when(UsageCounterLookup::deployedQualifiers).thenReturn("(none deployed)");

      UsageAggregationResult result = new UsageAggregationService().run(day(), day());

      assertAll(() -> assertEquals(1, result.getResourcesFailed()),
          () -> assertEquals(0, result.getRowsWritten()),
          () -> verify(obDal, never()).save(any()),
          () -> verify(obDal).rollbackAndClose());
    }

    @Test
    void theRunSummaryDescribesWhatHappened() {
      givenCatalog(HEALTHY_ID);
      givenAHealthyResource(HEALTHY_ID);

      String summary = new UsageAggregationService().run(day(), day()).toString();

      assertTrue(summary.contains("1 day(s)") && summary.contains("1 usage row(s)"), summary);
    }
  }

  @Nested
  @DisplayName("a final day is never rewritten by the scheduled run (B2)")
  class FinalDaysAreFrozen {

    /**
     * THE REGRESSION TEST for acceptance criterion #4, which had no test at all.
     *
     * <p>"Once a day is final it never changes" is the one normative invariant of the design: a
     * figure that has been reported to a customer must not silently move afterwards. The scheduled
     * run cannot simply avoid final days, because it iterates the LARGEST settling window
     * configured anywhere and therefore necessarily revisits days that are already final for a
     * tenant with a shorter window. So the guard has to be at the row: a stored row flagged
     * settled is left exactly as it is.
     *
     * <p>Before the fix both entry points shared one code path, so the invariant could not even be
     * expressed — which is why no test caught it.
     */
    @Test
    void theScheduledRunLeavesAnAlreadySettledRowAlone() {
      givenCatalog(HEALTHY_ID);
      givenAHealthyResource(HEALTHY_ID);
      usageSettings.when(UsageSettings::getMaxSettlingWindowDays).thenReturn(0);
      UsageDaily stored = givenStoredRowForCountedTenant(true, 99L);

      UsageAggregationResult result = new UsageAggregationService().runForSettlingWindow();

      assertAll(() -> verify(obDal, never()).save(stored),
          () -> verify(stored, never()).setQuantity(any()),
          () -> assertEquals(0, result.getRowsWritten(),
              "a row that was skipped must not be reported as written"));
    }

    /**
     * The other half, and the half that makes the first one meaningful: a backfill is a DELIBERATE
     * recomputation, so it may rewrite a final day. Without this, "never rewrite" could be
     * satisfied by never writing at all, and shadow mode — reviewing a past month before anyone is
     * billed — would be impossible.
     */
    @Test
    void anExplicitBackfillDoesRewriteAnAlreadySettledRow() {
      givenCatalog(HEALTHY_ID);
      givenAHealthyResource(HEALTHY_ID);
      UsageDaily stored = givenStoredRowForCountedTenant(true, 99L);

      UsageAggregationResult result = new UsageAggregationService().run(day(), day());

      assertAll(() -> verify(obDal).save(stored),
          () -> verify(stored).setQuantity(COUNTED),
          () -> assertEquals(1, result.getRowsWritten()));
    }

    /** An unsettled row is still open, so the scheduled run updates it as usual. */
    @Test
    void theScheduledRunStillRewritesARowThatIsNotYetSettled() {
      givenCatalog(HEALTHY_ID);
      givenAHealthyResource(HEALTHY_ID);
      usageSettings.when(UsageSettings::getMaxSettlingWindowDays).thenReturn(0);
      UsageDaily stored = givenStoredRowForCountedTenant(false, 99L);

      new UsageAggregationService().runForSettlingWindow();

      verify(stored).setQuantity(COUNTED);
    }

    /** The flag is explicit on the three-argument form, so both entry points are pinned. */
    @Test
    void theRewriteFlagIsWhatDecides() {
      givenCatalog(HEALTHY_ID);
      givenAHealthyResource(HEALTHY_ID);
      UsageDaily stored = givenStoredRowForCountedTenant(true, 99L);

      new UsageAggregationService().run(day(), day(), false);
      verify(obDal, never()).save(stored);

      new UsageAggregationService().run(day(), day(), true);
      verify(obDal).save(stored);
    }
  }

  @Nested
  @DisplayName("a tenant that stops counting is zeroed, not left stale (B3)")
  class TenantsThatDropToZero {

    /**
     * THE REGRESSION TEST for B3. The counting query groups by tenant and only emits groups with
     * rows, so a tenant whose documents were all voided produces NO group — and the upsert loop,
     * which only walks the returned counts, never touched its row. Its last non-zero value stood
     * forever, so the claim that each day is "recomputed from scratch" was false for exactly the
     * case that costs a customer money: usage that went away but kept being billed.
     *
     * <p>Absence of a row means zero only until a row exists. Once one does, it has to be set.
     */
    @Test
    void aStoredRowForATenantTheRecountNoLongerReportsIsSetToZero() {
      givenCatalog(HEALTHY_ID);
      givenAHealthyResource(HEALTHY_ID);
      UsageDaily stale = givenStoredRowForATenantThatNoLongerCounts(false, 5L);

      UsageAggregationResult result = new UsageAggregationService().run(day(), day());

      assertAll(() -> verify(stale).setQuantity(0L),
          () -> verify(obDal).save(stale),
          () -> assertEquals(2, result.getRowsWritten(),
              "the counted tenant's row plus the zeroed one"));
    }

    /**
     * Zeroing obeys the same freeze as counting. A tenant that dropped to zero on a day that is
     * already final must NOT have that day rewritten — otherwise B3 would quietly reintroduce the
     * very revision B2 forbids, which is the kind of interaction that only shows up when both
     * rules are exercised together.
     */
    @Test
    void aFrozenStaleRowIsLeftAloneByTheScheduledRun() {
      givenCatalog(HEALTHY_ID);
      givenAHealthyResource(HEALTHY_ID);
      usageSettings.when(UsageSettings::getMaxSettlingWindowDays).thenReturn(0);
      UsageDaily stale = givenStoredRowForATenantThatNoLongerCounts(true, 5L);

      new UsageAggregationService().runForSettlingWindow();

      assertAll(() -> verify(stale, never()).setQuantity(any()),
          () -> verify(obDal, never()).save(stale));
    }

    /** But a backfill may zero a final day, symmetrically with the counted case. */
    @Test
    void aBackfillDoesZeroAFrozenStaleRow() {
      givenCatalog(HEALTHY_ID);
      givenAHealthyResource(HEALTHY_ID);
      UsageDaily stale = givenStoredRowForATenantThatNoLongerCounts(true, 5L);

      new UsageAggregationService().run(day(), day());

      verify(stale).setQuantity(0L);
    }

    /**
     * A row already at zero is left untouched. Rewriting it would be harmless to the value but
     * would bump COMPUTED_AT on every nightly run forever, turning a quiet table into one whose
     * rows all look freshly recomputed — and hiding which days actually changed.
     */
    @Test
    void aRowAlreadyAtZeroIsNotRewritten() {
      givenCatalog(HEALTHY_ID);
      givenAHealthyResource(HEALTHY_ID);
      UsageDaily alreadyZero = givenStoredRowForATenantThatNoLongerCounts(false, 0L);

      UsageAggregationResult result = new UsageAggregationService().run(day(), day());

      assertAll(() -> verify(obDal, never()).save(alreadyZero),
          () -> assertEquals(1, result.getRowsWritten(), "only the counted tenant's row"));
    }

    /** A tenant the recount DID report is updated normally, not zeroed by the sweep. */
    @Test
    void aTenantThatStillCountsIsNotZeroed() {
      givenCatalog(HEALTHY_ID);
      givenAHealthyResource(HEALTHY_ID);
      UsageDaily stored = givenStoredRowForCountedTenant(false, 5L);

      new UsageAggregationService().run(day(), day());

      assertAll(() -> verify(stored).setQuantity(COUNTED),
          () -> verify(stored, never()).setQuantity(0L));
    }
  }
}
