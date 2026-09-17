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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.invocation.Invocation;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.enterprise.Organization;

import com.etendoerp.go.schemaforge.data.BillingResource;
import com.etendoerp.go.schemaforge.data.UsageRunLog;

/**
 * Unit specs for {@link UsageRunLedger} (ETP-5050).
 *
 * <p><b>What the log is for.</b> The aggregation run reports a summary to whoever launched it and
 * then forgets; the nightly one has nobody watching at all. {@code ETGO_USAGE_RUN_LOG} is the only
 * durable account of what a run touched — which resources, which tenants, how many days, and what
 * went wrong — so the properties asserted here are the ones an operator reads a past run back by.
 *
 * <p><b>Why the ledger accumulates in memory and writes once at the end, which shapes every test
 * below.</b> {@link UsageAggregationService} commits or rolls back each resource-day on its own,
 * deliberately, so one bad resource cannot discard a whole night's work. A log row written inside
 * a unit that then failed would be rolled back with that unit — losing precisely the failure worth
 * recording. So the ledger collects, and writes afterwards in a transaction of its own. The
 * failure cases below are therefore not edge cases: they are the main case.
 *
 * <p>Two shapes of row come out, which is what the nullable tenant column is for: one per (run,
 * resource, tenant) counted, and — only when something failed — one per (run, resource) with NO
 * tenant, because the common failures (a fragment that will not compile, a qualifier no counter
 * carries) happen before any tenant is known. Tests here address rows by that distinction rather
 * than by save order, so they state the domain property instead of an implementation detail.
 */
class UsageRunLedgerTest {

  private static final String RESOURCE_A = "RESOURCE_A_ID";
  private static final String RESOURCE_B = "RESOURCE_B_ID";
  private static final String TENANT_A = "A1B2C3D4E5F60718293A4B5C6D7E8F90";
  private static final String TENANT_B = "0FEDCBA98765432100FEDCBA98765432";

  private MockedStatic<OBDal> obDalStatic;
  private MockedStatic<OBProvider> obProviderStatic;

  private OBDal obDal;
  private final Map<String, Client> clients = new HashMap<>();
  private final Map<String, BillingResource> resources = new HashMap<>();

  private static Date day(int year, int month, int dayOfMonth) {
    Calendar cal = Calendar.getInstance();
    cal.clear();
    cal.set(year, month - 1, dayOfMonth, 0, 0, 0);
    return cal.getTime();
  }

  @BeforeEach
  void setUp() {
    obDalStatic = mockStatic(OBDal.class);
    obProviderStatic = mockStatic(OBProvider.class);

    obDal = mock(OBDal.class);
    obDalStatic.when(OBDal::getInstance).thenReturn(obDal);

    OBProvider provider = mock(OBProvider.class);
    obProviderStatic.when(OBProvider::getInstance).thenReturn(provider);
    // A FRESH row per call: the ledger writes several, and a shared mock would merge what each
    // of them was told into one indistinguishable pile of invocations.
    when(provider.get(UsageRunLog.class)).thenAnswer(invocation -> mock(UsageRunLog.class));

    when(obDal.get(eq(Organization.class), any())).thenReturn(mock(Organization.class));
    // One stable Client mock per id, so a row can be matched back to the tenant it is about.
    //
    // The id is pulled out into an Object local FIRST, deliberately. Invocation.getArgument is
    // generic (<T> T), so handing it straight to String.valueOf lets the compiler infer T =
    // char[] -- String.valueOf(char[]) being the more specific overload -- and the call then
    // dies at run time with "class java.lang.String cannot be cast to class [C". Inside a
    // Mockito answer that throw surfaces as the LEDGER failing to write its log, which is both
    // plausible and completely wrong.
    when(obDal.get(eq(Client.class), any())).thenAnswer(invocation -> {
      Object clientId = invocation.getArgument(1);
      return clientFor(String.valueOf(clientId));
    });
    when(obDal.get(eq(BillingResource.class), anyString())).thenAnswer(
        invocation -> resources.get(invocation.getArgument(1)));
  }

  @AfterEach
  void tearDown() {
    obProviderStatic.close();
    obDalStatic.close();
  }

  /**
   * One stable mock per tenant id.
   *
   * <p>Plain {@code mock(...)} with no stubbing of its own, deliberately: this is reached from
   * inside the {@code OBDal.get} answer, and calling {@code when(...)} while an answer is running
   * starts a stubbing Mockito cannot finish — it throws, the ledger swallows it as a logging
   * failure and rolls back, and every assertion below then fails against a fixture problem
   * wearing the costume of a production one.
   */
  private Client clientFor(String clientId) {
    return clients.computeIfAbsent(clientId, id -> mock(Client.class));
  }

  /** A catalog resource the ledger can read back by id when it writes. */
  private BillingResource givenResource(String id) {
    return resources.computeIfAbsent(id, key -> {
      BillingResource resource = mock(BillingResource.class);
      when(resource.getId()).thenReturn(key);
      when(resource.getSearchKey()).thenReturn(key);
      return resource;
    });
  }

  /** Every row handed to {@code OBDal.save}, in the order it was written. */
  private List<UsageRunLog> savedRows() {
    ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
    verify(obDal, org.mockito.Mockito.atLeast(0)).save(captor.capture());
    List<UsageRunLog> rows = new ArrayList<>();
    for (Object saved : captor.getAllValues()) {
      rows.add((UsageRunLog) saved);
    }
    return rows;
  }

  /**
   * The value a setter was called with, or null if it never was.
   *
   * <p>Reading the invocations rather than asserting them one at a time is what lets a row be
   * IDENTIFIED (the one about tenant A, the one about no tenant at all) before anything is
   * claimed about it. Addressing rows by save order instead would silently start testing a
   * different row the day the accumulation order changed.
   */
  private static Object valuePassedTo(UsageRunLog row, String setter) {
    for (Invocation invocation : mockingDetails(row).getInvocations()) {
      if (invocation.getMethod().getName().equals(setter)) {
        return invocation.getArgument(0);
      }
    }
    return null;
  }

  /**
   * The value a setter was called with, failing if it never was.
   *
   * <p>Used by every assertion, while the nullable {@link #valuePassedTo} is used only to
   * IDENTIFY a row. The distinction is the safety net for the one weakness of reading invocations
   * by name: the compiler cannot check a setter spelled as a string, so a renamed column would
   * otherwise turn every assertion into "expected S but was null" — true, baffling, and pointing
   * at the wrong thing. This says which setter was never called instead.
   */
  private static Object requireValuePassedTo(UsageRunLog row, String setter) {
    assertNotNull(row, "no row was written to assert on");
    boolean called = mockingDetails(row).getInvocations().stream()
        .anyMatch(invocation -> invocation.getMethod().getName().equals(setter));
    assertTrue(called, "the row was never told " + setter + "(...) -- if the column was renamed,"
        + " this test is reading a setter that no longer exists");
    return valuePassedTo(row, setter);
  }

  /** The row about one tenant of one resource. */
  private UsageRunLog rowFor(String clientId) {
    for (UsageRunLog row : savedRows()) {
      if (clientFor(clientId).equals(valuePassedTo(row, "setTenantClient"))) {
        return row;
      }
    }
    return null;
  }

  /** The row about a resource itself — the one with no tenant, written only on failure. */
  private UsageRunLog resourceLevelRow() {
    for (UsageRunLog row : savedRows()) {
      if (valuePassedTo(row, "setTenantClient") == null) {
        return row;
      }
    }
    return null;
  }

  @Nested
  @DisplayName("a clean run")
  class CleanRun {

    /**
     * One row per (run, resource, tenant), each carrying only its own numbers. Tenant attribution
     * is the whole reason this table is not just a counter: "the run processed 40 days" answers
     * nothing an operator asks, while "this tenant, this resource, two days, five rows" does.
     */
    @Test
    void oneRowPerTenantCarriesItsOwnDaysAndRows() {
      BillingResource resource = givenResource(RESOURCE_A);
      UsageRunLedger ledger = new UsageRunLedger();
      ledger.covering(day(2026, 3, 1), day(2026, 3, 2));
      ledger.tenantDay(resource, TENANT_A, 3);
      ledger.tenantDay(resource, TENANT_A, 2);
      ledger.tenantDay(resource, TENANT_B, 0);

      int written = ledger.write();

      assertAll(() -> assertEquals(2, written, "one row per tenant, and no more"),
          () -> assertEquals(2L, requireValuePassedTo(rowFor(TENANT_A), "setDaysSucceeded")),
          () -> assertEquals(5L, requireValuePassedTo(rowFor(TENANT_A), "setRowsWritten")),
          () -> assertEquals(1L, requireValuePassedTo(rowFor(TENANT_B), "setDaysSucceeded")),
          () -> assertEquals(0L, requireValuePassedTo(rowFor(TENANT_B), "setRowsWritten"),
              "a day that was already final wrote nothing, and still counts as processed"),
          () -> assertEquals(UsageRunLedger.STATUS_SUCCESS,
              requireValuePassedTo(rowFor(TENANT_A), "setStatus")));
    }

    /**
     * No resource-level row when nothing failed. That row exists to carry a failure that belongs
     * to no tenant; writing one anyway would make every clean run look like it had something to
     * report, and the column that distinguishes them would stop meaning anything.
     */
    @Test
    void noResourceLevelRowIsWrittenWhenNothingFailed() {
      BillingResource resource = givenResource(RESOURCE_A);
      UsageRunLedger ledger = new UsageRunLedger();
      ledger.covering(day(2026, 3, 1), day(2026, 3, 1));
      ledger.tenantDay(resource, TENANT_A, 1);

      ledger.write();

      assertNull(resourceLevelRow(), "a clean run has no tenant-less failure to record");
    }

    /**
     * THE COLUMN THE TABLE IS ORGANISED BY. Nothing else groups one execution: the rows are
     * spread across resources and tenants, and two runs of the same range on the same day are
     * distinguishable only by this id. If it varied within a run, a reader could never assemble
     * "what did last night do" from the table at all.
     */
    @Test
    void everyRowOfOneRunSharesOneRunId() {
      BillingResource first = givenResource(RESOURCE_A);
      BillingResource second = givenResource(RESOURCE_B);
      UsageRunLedger ledger = new UsageRunLedger();
      ledger.covering(day(2026, 3, 1), day(2026, 3, 1));
      ledger.tenantDay(first, TENANT_A, 1);
      ledger.tenantDay(first, TENANT_B, 1);
      ledger.tenantDay(second, TENANT_A, 1);
      ledger.resourceDayFailed(second, RESOURCE_B, "boom");

      ledger.write();

      List<UsageRunLog> rows = savedRows();
      assertAll(() -> assertEquals(4, rows.size(), "three tenant rows plus one failure row"),
          () -> assertTrue(rows.stream()
              .allMatch(row -> ledger.getRunId().equals(requireValuePassedTo(row, "setRun"))),
              "every row must carry the run's own id"),
          () -> assertNotNull(ledger.getRunId()));
    }

    /** And two runs are two ids, or the grouping above would group everything together. */
    @Test
    void twoRunsDoNotShareARunId() {
      assertNotEquals(new UsageRunLedger().getRunId(), new UsageRunLedger().getRunId());
    }

    /**
     * The range is the one the run was ASKED for, not the day whichever tally happened to be
     * recorded last. A row saying it covers a single day, when the run covered a month, would
     * misreport coverage in the one direction that matters — it would look like days were never
     * processed.
     */
    @Test
    void everyRowCarriesTheRequestedRangeNotTheDayItWasRecordedOn() {
      BillingResource resource = givenResource(RESOURCE_A);
      Date from = day(2026, 3, 1);
      Date to = day(2026, 3, 31);
      UsageRunLedger ledger = new UsageRunLedger();
      ledger.covering(from, to);
      // Recorded per day, as the run does; the row must still describe the whole range.
      ledger.tenantDay(resource, TENANT_A, 1);
      ledger.tenantDay(resource, TENANT_A, 1);

      ledger.write();

      assertAll(() -> assertEquals(from, requireValuePassedTo(rowFor(TENANT_A), "setDateFrom")),
          () -> assertEquals(to, requireValuePassedTo(rowFor(TENANT_A), "setDateTo")));
    }

    /** The ledger commits its own transaction; nothing else would commit it. */
    @Test
    void theLogIsCommittedOnItsOwn() {
      BillingResource resource = givenResource(RESOURCE_A);
      UsageRunLedger ledger = new UsageRunLedger();
      ledger.covering(day(2026, 3, 1), day(2026, 3, 1));
      ledger.tenantDay(resource, TENANT_A, 1);

      ledger.write();

      assertAll(() -> verify(obDal, times(1)).commitAndClose(),
          () -> verify(obDal, never()).rollbackAndClose());
    }

    /**
     * A run that touched nothing writes nothing — and, importantly, does not open a transaction
     * to write nothing in. An empty catalog is not an event.
     */
    @Test
    void aRunThatTouchedNothingWritesNothing() {
      UsageRunLedger ledger = new UsageRunLedger();
      ledger.covering(day(2026, 3, 1), day(2026, 3, 1));

      assertAll(() -> assertEquals(0, ledger.write()),
          () -> verify(obDal, never()).save(any()),
          () -> verify(obDal, never()).commitAndClose());
    }
  }

  @Nested
  @DisplayName("a run with failures")
  class FailedRun {

    /**
     * THE CASE THE LOG EXISTS FOR. A resource that failed before any tenant was known — a
     * fragment that will not compile, a qualifier no counter carries — has nowhere else to be
     * recorded, and the run itself has already rolled that unit back. Status {@code E} because
     * nothing at all completed for it.
     */
    @Test
    void aResourceThatFailedOutrightGetsATenantLessRowWithTheReason() {
      BillingResource resource = givenResource(RESOURCE_A);
      UsageRunLedger ledger = new UsageRunLedger();
      ledger.covering(day(2026, 3, 1), day(2026, 3, 1));
      ledger.resourceDayFailed(resource, RESOURCE_A, "No counter deployed for that qualifier");

      int written = ledger.write();

      UsageRunLog row = resourceLevelRow();
      assertAll(() -> assertEquals(1, written),
          () -> assertNotNull(row, "the failure must be recorded somewhere"),
          () -> assertEquals(1L, requireValuePassedTo(row, "setDaysFailed")),
          () -> assertEquals(0L, requireValuePassedTo(row, "setDaysSucceeded")),
          () -> assertEquals("No counter deployed for that qualifier",
              requireValuePassedTo(row, "setErrorMessage")),
          () -> assertEquals(UsageRunLedger.STATUS_ERROR, requireValuePassedTo(row, "setStatus"),
              "nothing completed for this resource, so it is not a partial success"));
    }

    /**
     * Partial, not error, when some of the run did work: the distinction is what tells an
     * operator whether a re-run has everything left to do or only the remainder. Both shapes of
     * row say so — the tenant rows as well as the failure row — because either might be the one
     * a report groups by.
     */
    @Test
    void statusIsPartialWhenSomeTenantsSucceededAndSomeDaysFailed() {
      BillingResource resource = givenResource(RESOURCE_A);
      UsageRunLedger ledger = new UsageRunLedger();
      ledger.covering(day(2026, 3, 1), day(2026, 3, 2));
      ledger.tenantDay(resource, TENANT_A, 4);
      ledger.resourceDayFailed(resource, RESOURCE_A, "connection closed");

      ledger.write();

      assertAll(
          () -> assertEquals(UsageRunLedger.STATUS_PARTIAL,
              requireValuePassedTo(rowFor(TENANT_A), "setStatus"),
              "the tenant's own row must not claim a clean run"),
          () -> assertEquals(UsageRunLedger.STATUS_PARTIAL,
              requireValuePassedTo(resourceLevelRow(), "setStatus")),
          () -> assertEquals(4L, requireValuePassedTo(rowFor(TENANT_A), "setRowsWritten"),
              "the work that did complete is still reported"));
    }

    /**
     * One resource failing on four days of a six-day window is ONE thing wrong, so it is ONE row
     * — with the count of days it cost and the FIRST reason, which is the one that explains the
     * rest. A row per failed day would make the log as large as the failure was long and bury
     * every other resource under it.
     */
    @Test
    void aResourceFailingOnSeveralDaysGetsOneRowWithTheFirstReason() {
      BillingResource resource = givenResource(RESOURCE_A);
      UsageRunLedger ledger = new UsageRunLedger();
      ledger.covering(day(2026, 3, 1), day(2026, 3, 4));
      ledger.resourceDayFailed(resource, RESOURCE_A, "reason from day 1");
      ledger.resourceDayFailed(resource, RESOURCE_A, "reason from day 2");
      ledger.resourceDayFailed(resource, RESOURCE_A, "reason from day 3");
      ledger.resourceDayFailed(resource, RESOURCE_A, "reason from day 4");

      int written = ledger.write();

      UsageRunLog row = resourceLevelRow();
      assertAll(() -> assertEquals(1, written, "one row, not one per failed day"),
          () -> assertEquals(4L, requireValuePassedTo(row, "setDaysFailed"),
              "but all four days are still counted as lost"),
          () -> assertEquals("reason from day 1", requireValuePassedTo(row, "setErrorMessage")));
    }

    /**
     * A failure that happened before the resource could even be read still lands on the right
     * tally: the run passes the id it was iterating, which is all it has.
     */
    @Test
    void aFailureRecordedByIdAloneIsStillWritten() {
      givenResource(RESOURCE_A);
      UsageRunLedger ledger = new UsageRunLedger();
      ledger.covering(day(2026, 3, 1), day(2026, 3, 1));
      ledger.resourceDayFailed(null, RESOURCE_A, "could not read the resource");

      assertAll(() -> assertEquals(1, ledger.write()),
          () -> assertEquals("could not read the resource",
              requireValuePassedTo(resourceLevelRow(), "setErrorMessage")));
    }

    /**
     * A resource deleted between the run and the write is skipped rather than crashing the log:
     * the row could not be written anyway (the table has a foreign key to the catalog), and
     * losing one line must not cost the rest of them.
     */
    @Test
    void aResourceThatVanishedBeforeTheWriteIsSkipped() {
      BillingResource present = givenResource(RESOURCE_A);
      UsageRunLedger ledger = new UsageRunLedger();
      ledger.covering(day(2026, 3, 1), day(2026, 3, 1));
      ledger.tenantDay(present, TENANT_A, 1);
      // Recorded under an id the catalog no longer resolves.
      ledger.resourceDayFailed(null, "DELETED_RESOURCE_ID", "gone");

      assertAll(() -> assertEquals(1, ledger.write(), "only the surviving resource's row"),
          () -> verify(obDal, times(1)).commitAndClose());
    }

    /**
     * The reason reaches a user-facing column, so it goes through the at-sign guard: Openbravo
     * parses '@' as a message parameter, and a reason quoting {@code @Named(...)} — the single
     * most likely thing a counter failure says — would render blank wherever this column is
     * displayed.
     */
    @Test
    void theRecordedReasonIsAtSafe() {
      BillingResource resource = givenResource(RESOURCE_A);
      UsageRunLedger ledger = new UsageRunLedger();
      ledger.covering(day(2026, 3, 1), day(2026, 3, 1));
      ledger.resourceDayFailed(resource, RESOURCE_A, "No counter with @Named(\"active@users\")");

      ledger.write();

      String recorded = String.valueOf(requireValuePassedTo(resourceLevelRow(), "setErrorMessage"));
      assertAll(() -> assertTrue(recorded.contains("(at)Named"), recorded),
          () -> assertTrue(recorded.contains("active(at)users"), recorded),
          () -> org.junit.jupiter.api.Assertions.assertFalse(recorded.contains("@"), recorded));
    }

    /**
     * A failure with no reason at all still records something. A null there would read as "it
     * failed and we have no idea", which is true but useless; saying so explicitly is the point.
     */
    @Test
    void aFailureWithNoReasonStillSaysSomething() {
      BillingResource resource = givenResource(RESOURCE_A);
      UsageRunLedger ledger = new UsageRunLedger();
      ledger.covering(day(2026, 3, 1), day(2026, 3, 1));
      ledger.resourceDayFailed(resource, RESOURCE_A, null);

      ledger.write();

      assertNotNull(requireValuePassedTo(resourceLevelRow(), "setErrorMessage"));
    }
  }

  @Nested
  @DisplayName("when the log itself cannot be written")
  class LoggingFailure {

    /**
     * Losing the log must NOT fail the run. By the time the ledger writes, the usage rows are
     * already committed and correct; throwing here would turn a bookkeeping problem into a
     * reported failure of work that actually succeeded, and — because the process now throws on
     * failure — would record a successful night as an error.
     */
    @Test
    void aLoggingFailureIsSwallowedAndRolledBackRatherThanFailingTheRun() {
      BillingResource resource = givenResource(RESOURCE_A);
      org.mockito.Mockito.doThrow(new IllegalStateException("log table is gone"))
          .when(obDal).save(any());
      UsageRunLedger ledger = new UsageRunLedger();
      ledger.covering(day(2026, 3, 1), day(2026, 3, 1));
      ledger.tenantDay(resource, TENANT_A, 1);

      int written = ledger.write();

      assertAll(() -> assertEquals(0, written, "nothing was written, and it says so"),
          () -> verify(obDal).rollbackAndClose(),
          () -> verify(obDal, never()).commitAndClose());
    }
  }
}
