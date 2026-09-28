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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.hibernate.criterion.Restrictions;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.base.weld.test.WeldBaseTest;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.core.TriggerHandler;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.access.User;
import org.openbravo.model.ad.domain.Preference;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.enterprise.Organization;

import com.etendoerp.go.schemaforge.data.BillingResource;
import com.etendoerp.go.schemaforge.data.UsageDaily;
import com.etendoerp.go.schemaforge.data.UsageRunLog;

/**
 * <b>REQUIRES A DATABASE AND A CDI CONTAINER.</b> This suite extends {@code WeldBaseTest}
 * (Arquillian + JUnit 4), which is {@code OBBaseTest} plus a running Weld container: it writes
 * real fixture rows, runs {@link UsageAggregationService} against a live Hibernate session, and
 * reads {@code ETGO_USAGE_DAILY} back. It cannot run in the plain unit-test JVM, and it needs the
 * ETP-5050 tables ({@code ETGO_BILLING_RESOURCE}, {@code ETGO_USAGE_DAILY}) to exist — i.e. an
 * {@code update.database} after the catalog commit.
 *
 * <p><b>Why {@code WeldBaseTest} rather than {@code OBBaseTest}.</b> The strategy extension point
 * resolves a counter through CDI, so proving it works requires a real container — with a plain
 * {@code OBBaseTest} there is no {@code BeanManager} and the proof degenerates into stubbing the
 * lookup, which proves nothing. {@code WeldBaseTest.createTestArchive()} synthesises an empty
 * {@code beans.xml} INTO a ShrinkWrap archive and imports {@code build/classes} and
 * {@code src-test/build/classes}; {@code src-test/build.xml} compiles {@code modules/*&#47;src-test}
 * into that second directory, so {@link UsageTestCounter} — a module test class — is a
 * discoverable bean with no {@code beans.xml} file and no build change.
 * {@code WeldBaseTest.setUp()} also calls {@code WeldUtils.setStaticInstanceBeanManager(...)},
 * the exact entry point {@link UsageCounterLookup} reads, so the production lookup runs
 * unmodified.
 *
 * <p>Everything is rolled back in {@link #cleanUp()}; nothing is committed.
 *
 * <h2>What this suite is for</h2>
 *
 * <p>The design claim of ETP-5050 is that <b>a new billable resource is added by INSERTING A
 * CATALOG ROW, with no change to production code</b>. That claim is not provable by a unit test,
 * because a unit test can only assert that the code does what the code does. It is provable only
 * by adding a resource the way a future implementer would and seeing it counted. The PRD states
 * the criterion bluntly: <i>if this test needs a code change to pass, the extensibility claim is
 * false.</i> So {@link #declarativeResourceIsCountedWithNoProductionCodeChange()} and
 * {@link #strategyResourceIsCountedWithNoProductionCodeChange()} are not ordinary coverage — they
 * are the executable form of the architectural promise, and a reviewer should treat a failure
 * there as a design finding, not a test bug.
 *
 * <p>The other three tests guard properties that only a real database can falsify:
 * <ul>
 *   <li><b>Idempotency</b> — the unique key (measured client, resource, day) is the whole of the
 *       idempotency story; there is no "already processed" marker. Only a real unique index can
 *       show whether a second run updates the row or duplicates it.</li>
 *   <li><b>Containment</b> — {@code UsageQueryComposerTest} proves the composed HQL is shaped
 *       correctly. It cannot prove that Hibernate executes it the way the shape implies. Here a
 *       genuinely widening fragment counts MORE rows and must still stay inside its tenant and
 *       its day.</li>
 *   <li><b>Per-tenant finality</b> — the settling window is a preference, so the same day can be
 *       settled for one tenant and still open for another. That interaction spans
 *       {@code AD_Preference}, the aggregation loop and the written row.</li>
 * </ul>
 *
 * <h2>The fixture</h2>
 *
 * <p>Counted entity is {@code ADUser}, bucketed by {@code lastPasswordUpdate} — a cheap existing
 * entity with a freely settable date column, so the fixture controls exactly which day each row
 * lands on without building a document. Every fixture row's username carries
 * {@link #FIXTURE_PREFIX}, and the resource's HQL restriction narrows to that prefix, so the
 * counts are exact and unaffected by whatever else the test database contains.
 */
public class UsageAggregationServiceIntegrationTest extends WeldBaseTest {

  /**
   * Marks the rows the counted resource is supposed to see. No underscore: it goes into an HQL
   * {@code like} pattern, where {@code _} is a single-character wildcard and would quietly widen
   * the very restriction whose narrowness the counts depend on.
   */
  private static final String FIXTURE_PREFIX = "ETP5050USAGE";

  /**
   * Marks rows the narrow resource must NOT see. Their only job is to give a widening fragment
   * something extra to find, so that "widened counts more" is a falsifiable claim rather than an
   * accident of what the test database happens to contain.
   */
  private static final String OTHER_PREFIX = "ETP5050OTHER";

  private static final String COUNTED_ENTITY = "ADUser";
  private static final String DATE_PROPERTY = "lastPasswordUpdate";

  /**
   * Makes every fixture username unique without spelling anything long into it. Static, because
   * JUnit builds a fresh test instance per method and the rows of two methods share a database.
   */
  private static final AtomicLong FIXTURE_SEQUENCE = new AtomicLong();

  private static final String SYSTEM_CLIENT = "0";
  private static final String ORG_ZERO = "0";

  /** Ids of the preferences created here, which must be deleted by id rather than by prefix. */
  private final List<String> createdPreferenceIds = new ArrayList<>();

  private Date theDay;
  private Date anotherDay;

  @Before
  public void prepareDays() {
    setSystemAdministratorContext();
    OBContext.setAdminMode(false);
    theDay = daysAgo(2);
    anotherDay = daysAgo(3);
  }

  /**
   * Deletes the fixture explicitly rather than relying on a rollback.
   *
   * <p><b>A rollback would not work here.</b> {@link UsageAggregationService} commits each
   * resource-day on its own (deliberately, so one bad resource cannot discard a whole run), and
   * that commit also commits everything this fixture saved earlier in the same transaction. By
   * the time the test ends there is nothing left to roll back, so a suite that trusted
   * {@code rollbackAndClose()} would quietly accumulate users, catalog rows and usage rows in the
   * database on every run — and the extra users would then be counted by the NEXT run, making the
   * exact counts drift. Deletion is in dependency order: usage rows, then the resources they
   * point at, then the counted users, then the preferences.
   *
   * <p>Run-log rows go first, with the usage rows: {@code ETGO_USAGE_RUN_LOG} carries a foreign
   * key to {@code ETGO_BILLING_RESOURCE}, so deleting a fixture resource while its log rows
   * survive aborts the whole delete batch with a constraint violation — which fails the test that
   * happened to run last AND leaves the fixture behind for every test after it.
   */
  @After
  public void cleanUp() {
    OBContext.setAdminMode(false);
    try {
      for (UsageDaily row : fixtureUsageRows()) {
        OBDal.getInstance().remove(row);
      }
      for (UsageRunLog row : fixtureRunLogRows()) {
        OBDal.getInstance().remove(row);
      }
      OBDal.getInstance().flush();
      for (BillingResource resource : fixtureResources()) {
        OBDal.getInstance().remove(resource);
      }
      for (User user : fixtureUsers()) {
        OBDal.getInstance().remove(user);
      }
      for (String preferenceId : createdPreferenceIds) {
        Preference preference = OBDal.getInstance().get(Preference.class, preferenceId);
        if (preference != null) {
          OBDal.getInstance().remove(preference);
        }
      }
      OBDal.getInstance().commitAndClose();
    } finally {
      createdPreferenceIds.clear();
      // Exactly two restores, one per setAdminMode: the one opened at the top of this method,
      // and the one {@link #prepareDays()} opens and deliberately holds for the whole test.
      //
      // NEVER unwind this with a loop on isInAdministratorMode(). That predicate is true when
      // the stack top is in admin mode, or the admin-mode flag is set, or the user is an
      // administrator; and setSystemAdministratorContext() puts the context on role "0", which sets
      // isAdministrator = true permanently. So the predicate stays true no matter how many
      // times the stack is popped, and each extra pop hits OBContext's unbalanced-call branch,
      // which logs a warning with a freshly constructed exception. The loop never terminates:
      // it spins at full CPU inside @After, so the test method itself passes but JUnit never
      // finishes it, Gradle produces zero result files and the build wedges. OBBaseTest's own
      // TestWatcher guards the identical check with `!role.getId().equals("0")` for exactly
      // this reason, and it already calls OBContext.clearAdminModeStack() after @After, so a
      // belt-and-braces loop here buys nothing anyway.
      OBContext.restorePreviousMode();
      OBContext.restorePreviousMode();
    }
  }

  private List<UsageDaily> fixtureUsageRows() {
    OBCriteria<UsageDaily> criteria = OBDal.getInstance().createCriteria(UsageDaily.class);
    // An explicit alias, NOT "billingResource.searchKey". Hibernate Criteria only dot-navigates
    // into an association for the special "<assoc>.id" form; any other property of the
    // associated entity needs a join, and without one the criteria throws QueryException
    // ("could not resolve property") at list() time.
    criteria.createAlias(UsageDaily.PROPERTY_BILLINGRESOURCE, "resource");
    criteria.add(
        Restrictions.like("resource." + BillingResource.PROPERTY_SEARCHKEY, FIXTURE_PREFIX + "%"));
    criteria.setFilterOnReadableClients(false);
    criteria.setFilterOnReadableOrganization(false);
    return criteria.list();
  }

  /** Run-log rows pointing at a fixture resource. Same alias rule as {@link #fixtureUsageRows}. */
  private List<UsageRunLog> fixtureRunLogRows() {
    OBCriteria<UsageRunLog> criteria = OBDal.getInstance().createCriteria(UsageRunLog.class);
    criteria.createAlias(UsageRunLog.PROPERTY_BILLINGRESOURCE, "resource");
    criteria.add(
        Restrictions.like("resource." + BillingResource.PROPERTY_SEARCHKEY, FIXTURE_PREFIX + "%"));
    criteria.setFilterOnReadableClients(false);
    criteria.setFilterOnReadableOrganization(false);
    return criteria.list();
  }

  private List<BillingResource> fixtureResources() {
    OBCriteria<BillingResource> criteria =
        OBDal.getInstance().createCriteria(BillingResource.class);
    criteria.add(Restrictions.like(BillingResource.PROPERTY_SEARCHKEY, FIXTURE_PREFIX + "%"));
    criteria.setFilterOnReadableClients(false);
    criteria.setFilterOnReadableOrganization(false);
    return criteria.list();
  }

  private List<User> fixtureUsers() {
    OBCriteria<User> criteria = OBDal.getInstance().createCriteria(User.class);
    criteria.add(Restrictions.or(
        Restrictions.like(User.PROPERTY_USERNAME, FIXTURE_PREFIX + "%"),
        Restrictions.like(User.PROPERTY_USERNAME, OTHER_PREFIX + "%")));
    criteria.setFilterOnReadableClients(false);
    criteria.setFilterOnReadableOrganization(false);
    return criteria.list();
  }

  // -------------------------------------------------------------------------
  // 1. Extensibility: declarative mode
  // -------------------------------------------------------------------------

  /**
   * THE EXTENSIBILITY PROOF for declarative mode. A brand-new resource is introduced purely as a
   * catalog row — a counted entity, a date property and an HQL restriction — and the scheduled
   * job counts it. No production class is touched, subclassed or registered. If this test ever
   * requires a code change to pass, the "add a row to bill something new" claim has become false
   * and the design should be revisited rather than the test adjusted.
   */
  @Test
  public void declarativeResourceIsCountedWithNoProductionCodeChange() {
    givenUsers(TEST_CLIENT_ID, theDay, 3);
    BillingResource resource = givenDeclarativeResource("DECL", restrictionForFixture());

    new UsageAggregationService().run(theDay, theDay);

    assertEquals("the new resource is counted purely because a catalog row exists", 3L,
        quantityFor(resource, TEST_CLIENT_ID, theDay));
  }

  /**
   * A day on which the resource has nothing to count writes no row: absence IS the zero. Writing
   * an explicit zero would be equally defensible, but the contract states absence, and a reader
   * of the table must be able to rely on one of the two.
   */
  @Test
  public void aDayWithNoActivityWritesNoRow() {
    givenUsers(TEST_CLIENT_ID, theDay, 2);
    BillingResource resource = givenDeclarativeResource("DECL_EMPTY", restrictionForFixture());

    new UsageAggregationService().run(anotherDay, anotherDay);

    assertNull("a day with nothing to count records nothing; absence is the zero",
        findRow(resource, TEST_CLIENT_ID, anotherDay));
  }

  // -------------------------------------------------------------------------
  // 2. Extensibility: strategy mode
  // -------------------------------------------------------------------------

  /**
   * THE EXTENSIBILITY PROOF for strategy mode: a resource whose rule is not a row count is added
   * by deploying a {@code @Named} counter and pointing a catalog row at its qualifier.
   * {@link UsageAggregationService} is not modified and does not know {@link UsageTestCounter}
   * exists; it finds it through CDI at run time, exactly as it would find a counter a future
   * implementer shipped in their own module.
   *
   * <p>Discovery is part of what is being proven, not a precondition for proving it. If the
   * counter is not found, this test FAILS — that is the correct outcome, because a counter that
   * CDI cannot see is exactly the production failure mode the extension point must not have
   * (ETP-4244). Do not soften this into a skip, and do not invoke the counter directly: calling
   * it by hand would prove that a method returns a list, not that the extension point works.
   *
   * <p>If this test ever requires a change to production code to pass, the "add a row, deploy a
   * counter, bill something new" claim has become false and the design should be revisited rather
   * than the test adjusted.
   */
  @Test
  public void strategyResourceIsCountedWithNoProductionCodeChange() {
    BillingResource resource = givenStrategyResource("STRAT", UsageTestCounter.QUALIFIER);

    new UsageAggregationService().run(theDay, theDay);

    assertEquals("the strategy's own value is recorded verbatim", UsageTestCounter.QUANTITY,
        quantityFor(resource, UsageTestCounter.MEASURED_CLIENT_ID, theDay));
  }

  /**
   * The counter attributes its count to a hard-coded tenant, because it cannot see
   * {@code OBBaseTest}'s {@code protected} constant from outside the test class. This pins the two
   * together: if the test client id ever changes, this fails here with an obvious message instead
   * of surfacing as a confusing foreign-key error inside the aggregation run.
   */
  @Test
  public void theTestCounterMeasuresTheTestClient() {
    assertEquals(TEST_CLIENT_ID, UsageTestCounter.MEASURED_CLIENT_ID);
  }

  /**
   * The lookup resolves the counter by its {@code @Named} value against the real container. Kept
   * as a separate, narrower test so that a discovery failure and a counting failure are
   * distinguishable at a glance: if both fail, the extension point is not wired; if only the
   * counting one fails, the counter is found but its result is not being recorded.
   */
  @Test
  public void theCounterIsDiscoverableByItsQualifier() {
    assertTrue("no UsageResourceCounter was discovered for @Named(\"" + UsageTestCounter.QUALIFIER
        + "\"); deployed: " + UsageCounterLookup.deployedQualifiers(),
        UsageCounterLookup.isDeployed(UsageTestCounter.QUALIFIER));
    assertNotNull(UsageCounterLookup.byQualifier(UsageTestCounter.QUALIFIER));
  }

  /**
   * A catalog row naming a qualifier nothing carries must fail loudly, not record nothing.
   * Recording nothing is indistinguishable from genuinely zero usage, and would under-bill
   * silently and indefinitely. The run as a whole still completes — one broken resource must not
   * stop the others — so the failure is visible in the result summary.
   *
   * <p><b>Why the fixture disables triggers, and why that is not cheating.</b> Since ETP-5050's
   * save-time observer landed, this row can no longer be CREATED through a normal save — the
   * observer rejects it, which is the feature working. But the STATE is still reachable in
   * production, by a sequence the observer cannot police: a row is saved while its counter is
   * deployed, and a later release removes or renames that counter. Nothing re-validates existing
   * catalog rows at deploy time, so the row survives pointing at a qualifier that no longer
   * exists. Disabling triggers reproduces exactly that — a row that entered the table without
   * this observer seeing it — rather than dodging validation for convenience. The boundary is
   * pinned by {@link #anUndeployedQualifierCannotBeSavedThroughTheNormalPath()} immediately
   * below, so the bypass cannot quietly become the only path this behaviour is tested through.
   */
  @Test
  public void aResourceNamingAnUndeployedCounterIsReportedAsAFailureAndDoesNotRecordZero() {
    BillingResource resource =
        givenStrategyResourceBypassingValidation("STRAT_MISSING", "no-such-counter-deployed");

    UsageAggregationResult result = new UsageAggregationService().run(theDay, theDay);

    assertTrue("a missing counter must be reported, never silently counted as zero",
        result.getResourcesFailed() > 0);
    assertNull(findRow(resource, TEST_CLIENT_ID, theDay));
  }

  /**
   * The other side of the same coin, and the reason the bypass above is honest: through the
   * NORMAL path the observer refuses the row outright. Without this test the bypass would be
   * indistinguishable from a test quietly routing around validation it found inconvenient.
   */
  @Test
  public void anUndeployedQualifierCannotBeSavedThroughTheNormalPath() {
    assertSaveRejected("no-such-counter-deployed",
        () -> givenStrategyResource("STRAT_REJECTED", "no-such-counter-deployed"));
  }

  /**
   * A well-formed declarative row saves, and the observer stamps what its counting query cost.
   * {@code LAST_VALIDATION_MS} is what makes a fragment that would table-scan every tenant
   * nightly visible at configuration time rather than at 02:00.
   */
  @Test
  public void savingADeclarativeResourceRecordsItsProbedCost() {
    BillingResource resource = givenDeclarativeResource("DECL_PROBED", restrictionForFixture());

    assertNotNull("the observer must stamp when the row was validated",
        resource.getLastValidated());
    assertNotNull("and how long its counting query took", resource.getLastValidationMs());
  }

  /** A fragment that could escape its subquery is refused at save, not at 02:00. */
  @Test
  public void aMalformedFragmentCannotBeSaved() {
    assertSaveRejected("parentheses",
        () -> givenDeclarativeResource("DECL_BAD_FRAGMENT", "1=1) or (1=1"));
  }

  /** An entity name that is not in the runtime model is refused at save. */
  @Test
  public void anUnknownCountedEntityCannotBeSaved() {
    assertSaveRejected("C_Invoice", () -> {
      BillingResource resource = newResource("DECL_BAD_ENTITY");
      resource.setCountingMode(UsageResourceValidator.MODE_DECLARATIVE);
      resource.setCountedEntity("C_Invoice");
      resource.setDateProperty(DATE_PROPERTY);
      OBDal.getInstance().save(resource);
      OBDal.getInstance().flush();
    });
  }

  // -------------------------------------------------------------------------
  // 3. Idempotency
  // -------------------------------------------------------------------------

  /**
   * Running twice over the same range must leave exactly the rows one run leaves, with the same
   * values. Nothing is diffed and nothing is marked as processed: the unique key (measured
   * client, resource, day) is the entire mechanism, so this is the test that proves it holds
   * against a real index rather than in principle.
   */
  @Test
  public void runningTwiceOverTheSameRangeIsIdempotent() {
    givenUsers(TEST_CLIENT_ID, theDay, 3);
    BillingResource resource = givenDeclarativeResource("DECL_IDEM", restrictionForFixture());

    new UsageAggregationService().run(theDay, theDay);
    long afterFirst = quantityFor(resource, TEST_CLIENT_ID, theDay);

    new UsageAggregationService().run(theDay, theDay);

    assertEquals("the second run must update in place, not insert a duplicate", 1,
        rowsFor(resource, TEST_CLIENT_ID, theDay).size());
    assertEquals("and must arrive at the same value", afterFirst,
        quantityFor(resource, TEST_CLIENT_ID, theDay));
  }

  /**
   * A recomputation after the underlying data changed REPLACES the value rather than adding to
   * it. Each day is recomputed from scratch, so an accumulating upsert would inflate a billed
   * figure on every nightly run — the most expensive failure mode this system has.
   */
  @Test
  public void aRecomputationReplacesTheValueRatherThanAccumulating() {
    givenUsers(TEST_CLIENT_ID, theDay, 2);
    BillingResource resource = givenDeclarativeResource("DECL_REPLACE", restrictionForFixture());

    new UsageAggregationService().run(theDay, theDay);
    assertEquals(2L, quantityFor(resource, TEST_CLIENT_ID, theDay));

    givenUsers(TEST_CLIENT_ID, theDay, 3);

    new UsageAggregationService().run(theDay, theDay);

    assertEquals("the day is recomputed from scratch; 2 + 3 would be an accumulating upsert", 5L,
        quantityFor(resource, TEST_CLIENT_ID, theDay));
  }

  // -------------------------------------------------------------------------
  // 4. Containment against a real database
  // -------------------------------------------------------------------------

  /**
   * CONTAINMENT, executed rather than inspected. The restriction below is deliberately BALANCED
   * and deliberately WIDENING: {@code ... or 1=1} matches every row of the entity, so it must
   * genuinely count more than the narrow resource does — a test whose widening fragment happened
   * to match nothing would prove nothing.
   *
   * <p>What must survive that widening is the outer {@code and}-chain and the {@code group by}:
   * no row may appear for another tenant, and none outside the day. Those are the two things a
   * fragment must never be able to reach, because reaching them would attribute one tenant's
   * usage to another — the failure that would bill the wrong customer.
   */
  @Test
  public void aWideningFragmentCountsMoreButStaysInsideItsTenantAndItsDay() {
    givenUsers(TEST_CLIENT_ID, theDay, 2);
    // Rows the narrow resource must not see, and the widened one must: without these, "or 1=1"
    // would widen the subquery to a set the outer day filter clamps back to the same 2 rows, and
    // the comparison below would pass even with containment removed.
    givenUsers(TEST_CLIENT_ID, theDay, 4, OTHER_PREFIX);
    givenUsers(TEST_CLIENT_ID, anotherDay, 5);
    givenUsers(QA_TEST_CLIENT_ID, theDay, 7);

    BillingResource narrow = givenDeclarativeResource("DECL_NARROW", restrictionForFixture());
    BillingResource widened =
        givenDeclarativeResource("DECL_WIDE", restrictionForFixture() + " or 1=1");

    new UsageAggregationService().run(theDay, theDay);

    long narrowCount = quantityFor(narrow, TEST_CLIENT_ID, theDay);
    long widenedCount = quantityFor(widened, TEST_CLIENT_ID, theDay);

    assertTrue("the fragment must really widen, or this test proves nothing: narrow="
        + narrowCount + " widened=" + widenedCount, widenedCount > narrowCount);

    assertNull("no row may be written for a day outside the requested bounds",
        findRow(widened, TEST_CLIENT_ID, anotherDay));

    UsageDaily otherTenant = findRow(widened, QA_TEST_CLIENT_ID, theDay);
    if (otherTenant != null) {
      assertEquals("the other tenant's row may only hold ITS OWN rows, never the first tenant's",
          countUsers(QA_TEST_CLIENT_ID, theDay), otherTenant.getQuantity().longValue());
    }
  }

  /**
   * Tenant attribution comes from the {@code group by}, so two tenants active on the same day get
   * one row each, each holding only its own rows. This is the property a paren breakout would
   * destroy, asserted here on the narrow (non-widened) resource where the expected numbers are
   * exact.
   */
  @Test
  public void eachTenantGetsItsOwnRowHoldingOnlyItsOwnCount() {
    givenUsers(TEST_CLIENT_ID, theDay, 2);
    givenUsers(QA_TEST_CLIENT_ID, theDay, 7);
    BillingResource resource = givenDeclarativeResource("DECL_TENANTS", restrictionForFixture());

    new UsageAggregationService().run(theDay, theDay);

    assertEquals(2L, quantityFor(resource, TEST_CLIENT_ID, theDay));
    assertEquals(7L, quantityFor(resource, QA_TEST_CLIENT_ID, theDay));
  }

  // -------------------------------------------------------------------------
  // 5. Per-tenant finality
  // -------------------------------------------------------------------------

  /**
   * The settling window is a preference, so it can differ per tenant — and the settled flag is
   * evaluated per tenant when the row is written. With a short window for one tenant and a long
   * one for another, the SAME day is final for the first and still open for the second.
   *
   * <p>Getting this wrong in either direction is costly: a day settled too early freezes a value
   * that was still supposed to change, and one never settled is never safe to bill from.
   */
  @Test
  public void theSameDayIsSettledForOneTenantAndOpenForAnother() {
    givenUsers(TEST_CLIENT_ID, theDay, 2);
    givenUsers(QA_TEST_CLIENT_ID, theDay, 3);

    // theDay is 2 days old: final under a 0-day window, still open under a 30-day one.
    givenSettlingWindowPreference(TEST_CLIENT_ID, "0");
    givenSettlingWindowPreference(QA_TEST_CLIENT_ID, "30");

    BillingResource resource = givenDeclarativeResource("DECL_SETTLE", restrictionForFixture());

    new UsageAggregationService().run(theDay, theDay);

    assertTrue("a 0-day window makes a 2-day-old day final for this tenant",
        findRow(resource, TEST_CLIENT_ID, theDay).isSettled());
    assertFalse("while a 30-day window leaves the very same day open for the other",
        findRow(resource, QA_TEST_CLIENT_ID, theDay).isSettled());
  }

  /** Today is inside every window, so it is never settled whatever the preference says. */
  @Test
  public void todayIsNeverSettled() {
    Date today = UsageDayRange.startOfDay(new Date());
    givenUsers(TEST_CLIENT_ID, today, 2);
    givenSettlingWindowPreference(TEST_CLIENT_ID, "0");
    BillingResource resource = givenDeclarativeResource("DECL_TODAY", restrictionForFixture());

    new UsageAggregationService().run(today, today);

    assertFalse("today is inside even a zero-day window",
        findRow(resource, TEST_CLIENT_ID, today).isSettled());
  }

  /**
   * Asserts that a save is refused by the observer, and refused FOR THE RIGHT REASON.
   *
   * <p>Two things this deliberately does not do. It does not pin the exception type: the observer
   * throws {@code IllegalArgumentException}, but it throws it from inside a Hibernate flush, and
   * the DAL is entitled to surface that wrapped. Naming the type would make the test fail when
   * the wrapper changes, which is not the behaviour anyone cares about. It therefore accepts any
   * {@link RuntimeException} and searches the whole cause chain for the text.
   *
   * <p>And it does not accept a bare "something was thrown". A save can fail for many boring
   * reasons — a missing mandatory column, a unique-key clash — and every one of them would let a
   * type-only assertion pass while the observer did nothing at all. That is exactly the failure
   * mode that hid here once: the observer silently stopped firing and the tests reported
   * "nothing happened". Requiring the message to name the offending value is what distinguishes
   * "rejected by validation" from "failed for some other reason".
   *
   * @param expectedText something only the right rejection would mention — the bad qualifier, the
   *     bad entity name, the word the fragment check uses
   */
  private void assertSaveRejected(String expectedText, Runnable save) {
    try {
      save.run();
      fail("the save-time observer must reject this row; expected a failure mentioning '"
          + expectedText + "' but the save succeeded");
    } catch (RuntimeException thrown) {
      String chain = messageChainOf(thrown);
      assertTrue("the save failed, but not for the expected reason — expected a message naming '"
          + expectedText + "', got: " + chain, chain.contains(expectedText));
    } finally {
      OBDal.getInstance().rollbackAndClose();
    }
  }

  /** Every message down the cause chain, so a wrapped rejection is still recognisable. */
  private static String messageChainOf(Throwable thrown) {
    StringBuilder chain = new StringBuilder();
    for (Throwable t = thrown; t != null && chain.length() < 8000; t = t.getCause()) {
      chain.append(t.getClass().getSimpleName()).append(": ").append(t.getMessage()).append(" | ");
      if (t.getCause() == t) {
        break;
      }
    }
    return chain.toString();
  }

  // -------------------------------------------------------------------------
  // fixture helpers
  // -------------------------------------------------------------------------

  private static Date daysAgo(int days) {
    return UsageDayRange.minusDays(new Date(), days);
  }

  /** Narrows counting to this suite's own rows, so the expected numbers are exact. */
  private static String restrictionForFixture() {
    return "e.username like '" + FIXTURE_PREFIX + "%'";
  }

  /** Creates {@code howMany} users the counted resource is meant to see. */
  private void givenUsers(String clientId, Date day, int howMany) {
    givenUsers(clientId, day, howMany, FIXTURE_PREFIX);
  }

  /** Creates {@code howMany} users in the tenant, all dated inside {@code day}. */
  private void givenUsers(String clientId, Date day, int howMany, String prefix) {
    Client client = OBDal.getInstance().get(Client.class, clientId);
    Organization org = OBDal.getInstance().get(Organization.class, ORG_ZERO);
    Date stamp = stampWithinDay(day);
    for (int i = 0; i < howMany; i++) {
      User user = OBProvider.getInstance().get(User.class);
      user.setNewOBObject(true);
      user.setClient(client);
      user.setOrganization(org);
      // AD_User.username is VARCHAR(60). Spelling the client id and the epoch millis into the
      // name overran that (73 chars) and every save failed validation. Nothing reads the name
      // back — only the prefix matters, because that is what the resource's HQL restriction and
      // the cleanup criteria match on — so a short unique suffix is enough.
      user.setUsername(prefix + Long.toString(FIXTURE_SEQUENCE.incrementAndGet(), 36)
          + Long.toString(System.nanoTime(), 36));
      user.setName(user.getUsername());
      user.setLastPasswordUpdate(stamp);
      OBDal.getInstance().save(user);
    }
    OBDal.getInstance().flush();
  }

  /**
   * A mid-morning instant inside the day. Deliberately NOT midnight: a row exactly on the
   * boundary belongs to the later day, and the fixture should not depend on which side of a
   * half-open bound the implementation puts it — {@code UsageDayRangeTest} owns that case.
   */
  private static Date stampWithinDay(Date day) {
    Calendar cal = Calendar.getInstance();
    cal.setTime(UsageDayRange.startOfDay(day));
    cal.add(Calendar.HOUR_OF_DAY, 10);
    return cal.getTime();
  }

  private BillingResource givenDeclarativeResource(String key, String restriction) {
    BillingResource resource = newResource(key);
    resource.setCountingMode(UsageResourceValidator.MODE_DECLARATIVE);
    resource.setCountedEntity(COUNTED_ENTITY);
    resource.setDateProperty(DATE_PROPERTY);
    resource.setHQLRestriction(restriction);
    OBDal.getInstance().save(resource);
    OBDal.getInstance().flush();
    return resource;
  }

  /**
   * Inserts a strategy row without the save-time observer running, modelling a row that entered
   * the catalog before its counter was removed. See the test that uses it for why this is a
   * faithful reproduction rather than a bypass of convenience.
   */
  private BillingResource givenStrategyResourceBypassingValidation(String key, String qualifier) {
    TriggerHandler.getInstance().disable();
    try {
      return givenStrategyResource(key, qualifier);
    } finally {
      TriggerHandler.getInstance().enable();
    }
  }

  private BillingResource givenStrategyResource(String key, String qualifier) {
    BillingResource resource = newResource(key);
    resource.setCountingMode(UsageResourceValidator.MODE_STRATEGY);
    resource.setStrategyQualifier(qualifier);
    OBDal.getInstance().save(resource);
    OBDal.getInstance().flush();
    return resource;
  }

  private BillingResource newResource(String key) {
    BillingResource resource = OBProvider.getInstance().get(BillingResource.class);
    resource.setNewOBObject(true);
    resource.setClient(OBDal.getInstance().get(Client.class, SYSTEM_CLIENT));
    resource.setOrganization(OBDal.getInstance().get(Organization.class, ORG_ZERO));
    resource.setSearchKey(FIXTURE_PREFIX + key + "_" + System.nanoTime());
    resource.setName(resource.getSearchKey());
    // ETGO_BILLING_RESOURCE.UNIT_LABEL is NOT NULL by design (it is the unit an invoice line is
    // denominated in), and the DAL has no default for it, so every fixture resource must set it
    // or the insert fails with a constraint violation on flush.
    resource.setUnitLabel("users");
    resource.setActive(true);
    return resource;
  }

  /**
   * Writes the settling-window preference for one tenant. It is a LIST preference: the key is an
   * {@code AD_Ref_List} value, so it lives in {@code AD_Preference.Property} with
   * {@code propertyList = true} and the value in {@code searchKey}. Writing it as an attribute
   * preference instead would leave {@link UsageSettings} reading the default with no error.
   */
  private void givenSettlingWindowPreference(String clientId, String value) {
    Preference preference = OBProvider.getInstance().get(Preference.class);
    preference.setNewOBObject(true);
    preference.setClient(OBDal.getInstance().get(Client.class, SYSTEM_CLIENT));
    preference.setOrganization(OBDal.getInstance().get(Organization.class, ORG_ZERO));
    preference.setPropertyList(true);
    preference.setProperty(UsageSettings.PREFERENCE_PROPERTY);
    preference.setSearchKey(value);
    preference.setVisibleAtClient(OBDal.getInstance().get(Client.class, clientId));
    preference.setVisibleAtOrganization(OBDal.getInstance().get(Organization.class, ORG_ZERO));
    OBDal.getInstance().save(preference);
    OBDal.getInstance().flush();
    createdPreferenceIds.add(preference.getId());
  }

  // -------------------------------------------------------------------------
  // read-back helpers
  // -------------------------------------------------------------------------

  private long quantityFor(BillingResource resource, String clientId, Date day) {
    UsageDaily row = findRow(resource, clientId, day);
    assertNotNull("expected a usage row for client " + clientId + " on " + day, row);
    return row.getQuantity().longValue();
  }

  private UsageDaily findRow(BillingResource resource, String clientId, Date day) {
    List<UsageDaily> rows = rowsFor(resource, clientId, day);
    return rows.isEmpty() ? null : rows.get(0);
  }

  private List<UsageDaily> rowsFor(BillingResource resource, String clientId, Date day) {
    OBCriteria<UsageDaily> criteria = OBDal.getInstance().createCriteria(UsageDaily.class);
    criteria.add(Restrictions.eq(UsageDaily.PROPERTY_BILLINGRESOURCE, resource));
    criteria.add(Restrictions.eq(UsageDaily.PROPERTY_USAGEDAY, UsageDayRange.startOfDay(day)));
    criteria.add(Restrictions.eq(UsageDaily.PROPERTY_TENANTCLIENT + ".id", clientId));
    // These rows are System-owned data ABOUT other tenants; with the readable filters on, the
    // assertions would silently see nothing and a broken run would read as an empty one.
    criteria.setFilterOnReadableClients(false);
    criteria.setFilterOnReadableOrganization(false);
    return criteria.list();
  }

  /** Counts every user a tenant has on a day — what a fully widened fragment should reach. */
  private long countUsers(String clientId, Date day) {
    OBCriteria<User> criteria = OBDal.getInstance().createCriteria(User.class);
    criteria.add(Restrictions.eq(User.PROPERTY_CLIENT + ".id", clientId));
    criteria.add(Restrictions.ge(User.PROPERTY_LASTPASSWORDUPDATE, UsageDayRange.startOfDay(day)));
    criteria.add(Restrictions.lt(User.PROPERTY_LASTPASSWORDUPDATE, UsageDayRange.nextDay(day)));
    criteria.setFilterOnReadableClients(false);
    criteria.setFilterOnReadableOrganization(false);
    return criteria.count();
  }

}
