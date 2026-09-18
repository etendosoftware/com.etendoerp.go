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

package com.etendoerp.go.payment;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.hibernate.query.NativeQuery;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.test.base.OBBaseTest;

/**
 * ETP-5046 — runs the REAL backfill SQL, twice, against the real database.
 *
 * <p>The fix under test is
 * {@code schema_forge/cli/src/data-fixes/sql/20260918T120000Z__R37-tenant-subscription-backfill.sql}:
 * it gives every tenant that carries the legacy {@code AD_Preference ETGO_TenantPlan='productive'}
 * marker, but no open subscription, one open {@code ETGO_SUBSCRIPTION} row on the grandfathered
 * {@code legacy-productive} plan.
 *
 * <p><b>Why the real file and not a paraphrase.</b> Everything that can go wrong with this fix is
 * a property of the statements as written. Re-running it must create zero additional rows —
 * two-layer idempotency, the {@code NOT EXISTS} guard in both {@code @check} and {@code @apply} —
 * and {@code @check} must CONVERGE to zero rows afterwards, because a {@code @check} that kept
 * returning rows would make the runner re-apply the fix on every run forever. A hand-written copy
 * of the SQL inside this test would drift from the shipped file and prove nothing about it. The
 * regression test in the schema_forge repository
 * ({@code cli/test/data-fixes-r37-tenant-subscription-backfill.test.js}) reads the same file as
 * text; this one is the only thing that executes it.
 *
 * <p><b>The schema_forge repository is a SIBLING repository and may be absent.</b> When the file
 * cannot be found the class skips with {@link Assume}, naming the path it looked for, rather than
 * failing a build that is simply checked out differently.
 *
 * <p><b>Fixtures never touch a real tenant.</b> This database holds six genuinely productive
 * tenants carrying the very preference this fix keys on. Every fixture here is a synthetic
 * {@code AD_CLIENT} created by this class, marked {@value #MARKER}, and every statement executed
 * is scoped to one fixture tenant id — the SQL is per-tenant by construction (the runner calls it
 * once per client), so no run can reach a real one. {@link #cleanUp()} removes every fixture and
 * then asserts that {@code ETGO_SUBSCRIPTION} is back to the exact row count it had before the
 * test: a backfill test that left a real tenant looking subscribed would be worse than no test.
 *
 * <p><b>Cleanup is explicit, not a rollback</b>, like every sibling integration test here: the
 * point of the spec is what a second run sees, which requires the first run to be committed.
 *
 * <p><b>The fix now REMOVES rows as well as inserting them</b> (ETP-5046): its third statement
 * retires the tenant's {@code ETGO_TenantPlan} preference in the same transaction as the backfilled
 * subscription, so the cutover is a per-tenant state transition with an observable end condition
 * rather than a fleet-wide flag day. That raises the stakes of the fixture discipline above — this
 * database holds six real {@code ETGO_TenantPlan} rows — so {@link #cleanUp()} now also asserts
 * that the fleet-wide count of that preference is exactly what it was before the test.
 */
public class SubscriptionBackfillIdempotencyIntegrationTest extends OBBaseTest {

  /** Prefix on every fixture {@code AD_CLIENT.VALUE}, {@code ETGO_ACCOUNT.EMAIL} and request id. */
  private static final String MARKER = "ETP5046BF-";

  private static final String ZERO = "0";

  /** Path of the fix, relative to whichever ancestor directory holds the sibling repository. */
  private static final String SQL_RELATIVE_PATH = "schema_forge/cli/src/data-fixes/sql/"
      + "20260918T120000Z__R37-tenant-subscription-backfill.sql";

  /** The grandfathered plan the fix insists on, shipped as sourcedata with this exact id. */
  private static final String LEGACY_PLAN_VALUE = "legacy-productive";
  private static final String LEGACY_PLAN_ID = "219D5C8E15C64E97B2F553B228D30DD0";
  private static final String LEGACY_PLAN_NAME = "Legacy Productive (grandfathered)";

  private String checkSection;
  private String applySection;
  private String reportSection;

  /**
   * True when this run created the {@code legacy-productive} plan row itself, which decides
   * whether cleanup may delete it. The row ships as sourcedata but is only present once
   * {@code update.database} has loaded it, so both cases are real.
   */
  private boolean createdLegacyPlan;

  /** Row count of {@code ETGO_SUBSCRIPTION} before the test, restored by cleanup. */
  private long subscriptionsBefore;

  /**
   * Fleet-wide row count of the {@code ETGO_TenantPlan} preference before the test.
   *
   * <p>Recorded in {@code @Before}, i.e. before this class creates any fixture, and re-asserted
   * after cleanup. Statement 3 of the fix DELETES this preference, and the database holds six real
   * productive tenants carrying it: a test that retired one of theirs would flip a paying customer
   * into the post-cutover state without a subscription behind it, which the read path would resolve
   * as free. Every statement is invoked per fixture tenant id, so it cannot reach them — this
   * assertion is what proves it rather than assuming it.
   */
  private long tenantPlanPreferencesBefore;

  private boolean sqlAvailable;

  @Before
  public void loadTheShippedFix() {
    Path fix = locateBackfillSql();
    Assume.assumeTrue("The backfill SQL was not found. It lives in the SIBLING schema_forge "
        + "repository, which may not be checked out here; looked for '" + SQL_RELATIVE_PATH
        + "' in every ancestor of " + Paths.get("").toAbsolutePath(), fix != null);
    sqlAvailable = true;

    String source = read(fix);
    checkSection = section(source, "check");
    applySection = section(source, "apply");
    reportSection = section(source, "report");
    assertFalse("The fix must declare a @check section", checkSection.trim().isEmpty());
    assertFalse("The fix must declare an @apply section", applySection.trim().isEmpty());
    assertFalse("The fix must declare a @report section", reportSection.trim().isEmpty());

    subscriptionsBefore = rawSubscriptionTotal();
    tenantPlanPreferencesBefore = rawTenantPlanPreferenceTotal();
    ensureLegacyPlan();
  }

  /**
   * Removes every fixture with native SQL and then verifies the table is back where it started.
   *
   * <p>The final assertion is the point: this class is the one place in the suite that inserts
   * subscription rows through raw SQL, and a leftover open row on any client would make that
   * client read back as productive to every consumer of the model.
   */
  @After
  public void cleanUp() {
    try {
      deleteCommittedFixtures();
      if (sqlAvailable) {
        assertEquals("The backfill test must leave ETGO_SUBSCRIPTION exactly as it found it",
            subscriptionsBefore, rawSubscriptionTotal());
        assertEquals("The backfill retires ETGO_TenantPlan preferences — every real tenant's must "
                + "still be there", tenantPlanPreferencesBefore, rawTenantPlanPreferenceTotal());
      }
    } finally {
      OBDal.getInstance().rollbackAndClose();
      OBContext.setOBContext((OBContext) null);
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Group 1 — the shape of @apply
  // ---------------------------------------------------------------------------------------------

  /**
   * {@code @apply} is THREE statements, and the order matters: an abort guard that refuses to
   * backfill when the grandfathered plan is missing, then the insert, then the per-tenant
   * retirement of the legacy {@code ETGO_TenantPlan} preference.
   *
   * <p>The guard exists because the alternative failure is silent and unrecoverable. If the plan
   * row were absent and the insert simply matched zero rows, the runner would record APPLIED,
   * advance the tenant's watermark past R37 forever, and leave a PAYING customer with no
   * subscription — flipped to free with nothing reporting it. An ERROR instead records FAILED,
   * which is not in the runner's PROCESSED set, so the tenant is retried on the next run once the
   * plan exists.
   *
   * <p>With the plan present — which {@link #ensureLegacyPlan()} guarantees — the guard must match
   * nothing. It is a {@code SELECT} whose projection is a deliberate cast-to-integer of an English
   * sentence, so "matches nothing" and "raises" are the only two outcomes it has.
   *
   * <p>The retirement comes LAST for a reason: its guard reads the row the insert writes, in the
   * same transaction. Retiring the preference first would open a window in which neither store
   * answers for the tenant if the insert then failed.
   */
  @Test
  public void testTheApplySectionIsAnAbortGuardThenTheInsertThenTheRetirement() {
    String tenant = createTenant("shape", true);
    List<String> statements = statementsOf(applySection, tenant);

    assertEquals("@apply must be exactly three statements", 3, statements.size());
    assertTrue("The first statement must be the abort guard: " + statements.get(0),
        statements.get(0).toLowerCase(Locale.ROOT).contains("abort_missing_legacy_plan"));
    assertTrue("The second statement must be the insert: " + statements.get(1),
        statements.get(1).trim().toLowerCase(Locale.ROOT)
            .startsWith("insert into etgo_subscription"));
    String retirement = statements.get(2).trim().toLowerCase(Locale.ROOT);
    assertTrue("The third statement must retire the plan preference: " + statements.get(2),
        retirement.startsWith("delete from ad_preference"));
    // The tenant of an AD_PREFERENCE row is VISIBLEAT_CLIENT_ID; its AD_CLIENT_ID is the System
    // pseudo-client, so a filter on ad_client_id would match zero rows for every tenant.
    assertTrue("The retirement must be scoped by visibleat_client_id: " + statements.get(2),
        retirement.contains("visibleat_client_id"));
    assertFalse("The retirement must NOT be scoped by ad_client_id: " + statements.get(2),
        retirement.contains("tp.ad_client_id"));

    assertEquals("With an active legacy-productive plan present, the abort guard must match "
        + "nothing", 0, selectRows(statements.get(0)).size());
  }

  // ---------------------------------------------------------------------------------------------
  // Group 2 — one row, then none: idempotency and convergence
  // ---------------------------------------------------------------------------------------------

  /**
   * The ticket's acceptance criterion, executed: the first apply inserts exactly one row and a
   * second apply inserts zero.
   *
   * <p>{@code @check} is asserted on both sides of the apply because idempotency alone is not
   * enough. A fix whose {@code @check} kept returning rows after a successful apply would be
   * re-applied by the runner on every single run — harmless in row terms thanks to the
   * {@code NOT EXISTS} guard, and completely invisible, but it would mean the fix never
   * converges and the tenant never reaches a settled state. Convergence to zero is what lets the
   * runner record SKIPPED_NOT_NEEDED afterwards.
   *
   * <p>This tenant owns a paid {@code ETGO_CHECKOUT_REQUEST}, so its Stripe ids must be carried
   * onto the subscription: they are the only link back to the provider for a tenant that was
   * provisioned before the subscription table existed.
   */
  @Test
  public void testTheBackfillInsertsOneRowAndASecondRunInsertsNone() {
    String tenant = createTenant("with-request", true);
    String requestId = createPaidCheckoutRequest(tenant, "cus_" + MARKER.toLowerCase(Locale.ROOT)
        + "customer", "sub_" + MARKER.toLowerCase(Locale.ROOT) + "subscription");
    assertNotNull("Sanity: the checkout request fixture must exist", requestId);

    assertEquals("@check must report the tenant as needing the fix before it runs", 1,
        selectRows(substitute(checkSection, tenant)).size());

    assertEquals("The first apply must create exactly one subscription", 1, apply(tenant));

    assertEquals("@check must converge to zero rows after a successful apply", 0,
        selectRows(substitute(checkSection, tenant)).size());
    assertEquals(1L, rawOpenCount(tenant));

    assertEquals("Re-running the backfill must create zero additional rows", 0, apply(tenant));

    assertEquals("Still exactly one open subscription for the tenant", 1L, rawOpenCount(tenant));
    assertEquals("And no closed one appeared either", 1L, rawTotalCount(tenant));
    assertEquals("@check stays converged", 0,
        selectRows(substitute(checkSection, tenant)).size());
  }

  /**
   * What the created row must actually say.
   *
   * <p>The plan is the grandfathered one, the row is open ({@code END_DATE IS NULL}) and its
   * status is {@code active} — anything else and the tenant would not read back as productive,
   * which is the entire purpose of the fix.
   *
   * <p>{@code PROVIDER_PRICE_ID}, {@code SNAPSHOT_AMOUNT} and {@code SNAPSHOT_CURRENCY} must be
   * NULL, and that is a deliberate statement rather than an omission: the grandfathered plan has
   * no price, so there is nothing truthful to snapshot, and inventing a figure here would put a
   * fabricated amount on a real customer's record.
   */
  @Test
  public void testTheCreatedRowIsAnOpenActiveGrandfatheredSubscriptionWithNoPriceSnapshot() {
    String tenant = createTenant("row-shape", true);
    createPaidCheckoutRequest(tenant, "cus_rowshape", "sub_rowshape");

    assertEquals(1, apply(tenant));

    String subscriptionId = (String) uniqueResult("SELECT ETGO_SUBSCRIPTION_ID "
        + "FROM ETGO_SUBSCRIPTION WHERE ENVIRONMENT_CLIENT_ID = :tenant", "tenant", tenant);
    assertNotNull(subscriptionId);
    assertEquals("The row must be on the grandfathered plan", LEGACY_PLAN_VALUE,
        uniqueResult("SELECT p.VALUE FROM ETGO_PLAN p "
            + "JOIN ETGO_SUBSCRIPTION s ON s.ETGO_PLAN_ID = p.ETGO_PLAN_ID "
            + "WHERE s.ETGO_SUBSCRIPTION_ID = :id", "id", subscriptionId));
    assertEquals(SubscriptionService.STATUS_ACTIVE, rawColumn(subscriptionId, "STATUS"));
    assertNull("The row must be OPEN", rawColumn(subscriptionId, "END_DATE"));
    assertEquals("The row is owned by the System pseudo-client and is ABOUT the tenant", ZERO,
        rawColumn(subscriptionId, "AD_CLIENT_ID"));
    assertEquals(tenant, rawColumn(subscriptionId, "ENVIRONMENT_CLIENT_ID"));
    assertNull("The grandfathered plan has no price, so there is nothing truthful to snapshot",
        rawColumn(subscriptionId, "PROVIDER_PRICE_ID"));
    assertNull(rawColumn(subscriptionId, "SNAPSHOT_AMOUNT"));
    assertNull(rawColumn(subscriptionId, "SNAPSHOT_CURRENCY"));
    assertNull("No account is invented for a backfilled row",
        rawColumn(subscriptionId, "ETGO_ACCOUNT_ID"));
  }

  // ---------------------------------------------------------------------------------------------
  // Group 3 — the Stripe ids, present and absent
  // ---------------------------------------------------------------------------------------------

  /**
   * Both halves of the Stripe-id rule, on two tenants that differ in exactly one thing.
   *
   * <p>A tenant whose paid checkout request this instance still holds gets its
   * {@code stripe_customer_id} and {@code stripe_subscription_id} carried onto the subscription.
   * A tenant marked productive through a path that left no usable request gets both NULL — which
   * is correct, not a defect: there is no provider subscription to point at, and writing anything
   * there would be a guess about a paying customer's billing.
   *
   * <p>{@code @report} is executed for both, because it is the only thing that tells an operator
   * which of the two happened without opening the table, and nothing else in the repository ever
   * runs it.
   */
  @Test
  public void testStripeIdsAreCopiedWhenACheckoutRequestExistsAndLeftNullWhenNoneDoes() {
    String withRequest = createTenant("stripe-yes", true);
    createPaidCheckoutRequest(withRequest, "cus_etp5046bf_yes", "sub_etp5046bf_yes");
    String withoutRequest = createTenant("stripe-no", true);

    assertEquals(1, apply(withRequest));
    assertEquals(1, apply(withoutRequest));

    String copied = (String) uniqueResult("SELECT ETGO_SUBSCRIPTION_ID FROM ETGO_SUBSCRIPTION "
        + "WHERE ENVIRONMENT_CLIENT_ID = :tenant", "tenant", withRequest);
    assertEquals("cus_etp5046bf_yes", rawColumn(copied, "STRIPE_CUSTOMER_ID"));
    assertEquals("sub_etp5046bf_yes", rawColumn(copied, "STRIPE_SUBSCRIPTION_ID"));

    String none = (String) uniqueResult("SELECT ETGO_SUBSCRIPTION_ID FROM ETGO_SUBSCRIPTION "
        + "WHERE ENVIRONMENT_CLIENT_ID = :tenant", "tenant", withoutRequest);
    assertNull("No usable checkout request means no provider subscription to point at",
        rawColumn(none, "STRIPE_CUSTOMER_ID"));
    assertNull(rawColumn(none, "STRIPE_SUBSCRIPTION_ID"));

    assertTrue("@report must say the ids were copied",
        reportText(withRequest).contains("stripe ids copied from etgo_checkout_request"));
    assertTrue("@report must say the ids were deliberately left NULL",
        reportText(withoutRequest).contains("stripe ids left NULL"));
  }

  /**
   * The tenant scoping, asserted rather than assumed.
   *
   * <p>The marker the fix keys on lives in {@code AD_PREFERENCE.VISIBLEAT_CLIENT_ID}, not in
   * {@code AD_CLIENT_ID} — the preference row is owned by the System pseudo-client and merely
   * <em>visible at</em> the tenant. A {@code @check} written against {@code ad_client_id} would
   * match nothing at all and every productive tenant would be silently skipped, which is why the
   * fixture is built the same way the real marker is.
   *
   * <p>So a tenant WITHOUT the marker must be left alone even though the fix is invoked for it:
   * that is what keeps the backfill from handing a subscription to a free tenant.
   */
  @Test
  public void testATenantWithoutTheProductiveMarkerIsNotBackfilled() {
    String unmarked = createTenant("unmarked", false);
    String marked = createTenant("marked", true);

    assertEquals("Sanity: the marker is stored in VISIBLEAT_CLIENT_ID; its AD_CLIENT_ID is the "
        + "System pseudo-client, so a fix filtering on AD_CLIENT_ID would match nothing", ZERO,
        uniqueResult("SELECT AD_CLIENT_ID FROM AD_PREFERENCE WHERE ATTRIBUTE = "
            + "'ETGO_TenantPlan' AND VISIBLEAT_CLIENT_ID = :tenant", "tenant", marked));
    assertEquals("Sanity: the marked tenant IS a candidate, so the two answers below differ for "
        + "the right reason", 1, selectRows(substitute(checkSection, marked)).size());

    assertEquals("A tenant with no productive marker is not a candidate", 0,
        selectRows(substitute(checkSection, unmarked)).size());
    assertEquals("And applying the fix to it must insert nothing", 0, apply(unmarked));
    assertEquals(0L, rawTotalCount(unmarked));
  }

  // ---------------------------------------------------------------------------------------------
  // Group 4 — the per-tenant retirement of the legacy ETGO_TenantPlan marker (ETP-5046)
  // ---------------------------------------------------------------------------------------------

  /**
   * The cutover, executed: the subscription appears and the preference disappears, together.
   *
   * <p>Retiring the marker per tenant — at the moment that tenant gains a live subscription — is
   * what turns the ETP-5046 cutover from a fleet-wide flag day into a state transition with an
   * OBSERVABLE end condition. When {@code select count(*) from ad_preference where
   * attribute='ETGO_TenantPlan'} reaches zero, and the fallback's WARN lines stop, the transitional
   * code can be deleted because a query says so, not because someone judged it safe.
   *
   * <p>The second apply is asserted too: it must retire nothing and insert nothing, which is what
   * makes a re-run harmless. And {@code @check} stays converged — now for two independent reasons,
   * since it requires BOTH a productive preference (gone) and no open subscription (present).
   */
  @Test
  public void testTheBackfillRetiresTheTenantPlanPreferenceInTheSameTransaction() {
    String tenant = createTenant("retire", true);
    assertEquals("Sanity: the fixture carries exactly one marker", 1L, rawPreferenceCount(tenant));

    assertEquals(1, apply(tenant));

    assertEquals("The marker must be retired by the same apply that created the subscription", 1,
        lastRetiredPreferences);
    assertEquals("No ETGO_TenantPlan row may remain for a tenant that now has a subscription", 0L,
        rawPreferenceCount(tenant));
    assertEquals("...and the subscription it was traded for must be there", 1L,
        rawOpenCount(tenant));

    assertEquals("A second run inserts nothing", 0, apply(tenant));
    assertEquals("...and retires nothing", 0, lastRetiredPreferences);
    assertEquals("@check stays converged", 0,
        selectRows(substitute(checkSection, tenant)).size());
  }

  /**
   * The retirement is guarded on an open subscription EXISTING, not on "the insert above ran".
   *
   * <p>That is what makes it self-healing: a tenant that obtained its subscription by any other
   * route — the runtime paid-upgrade path, a manual correction, an earlier partial run — is retired
   * the next time the fix is invoked for it. The negative half is asserted here because it is the
   * dangerous one: a tenant with a marker and NO subscription must keep its marker, since the
   * marker is then the only record that it paid.
   */
  @Test
  public void testTheRetirementOnlyFiresForATenantThatActuallyHasAnOpenSubscription() {
    String tenant = createTenant("retire-guard", true);
    List<String> statements = statementsOf(applySection, tenant);

    // Run ONLY the retirement, with no subscription in place.
    OBContext.setOBContext(ZERO, ZERO, ZERO, ZERO);
    OBContext.setAdminMode(true);
    int retired;
    try {
      retired = OBDal.getInstance().getSession().createNativeQuery(statements.get(2))
          .executeUpdate();
      OBDal.getInstance().flush();
      OBDal.getInstance().commitAndClose();
    } finally {
      OBContext.restorePreviousMode();
    }

    assertEquals("Without an open subscription the retirement must remove nothing", 0, retired);
    assertEquals("The marker is still the only record that this tenant paid", 1L,
        rawPreferenceCount(tenant));
    assertEquals("And the tenant is still a candidate for the backfill", 1,
        selectRows(substitute(checkSection, tenant)).size());
  }

  /**
   * {@code @report} is the ONLY audit trail of the retirement — the row it describes is gone.
   *
   * <p>So it has to say, per tenant, what happened: retired, or nothing to retire, or still present
   * because the tenant has no subscription. The ledger's {@code detail} column is where an operator
   * reads this back months later, and nothing else in the repository ever runs {@code @report}.
   */
  @Test
  public void testTheReportRecordsWhetherThePreferenceWasRetired() {
    String retired = createTenant("report-retired", true);
    assertEquals(1, apply(retired));
    assertTrue("@report must record the retirement: " + reportText(retired),
        reportText(retired).contains("preference retired for this tenant"));

    // A tenant with a marker and no subscription: @report must flag that it was NOT retired.
    String untouched = createTenant("report-untouched", true);
    assertTrue("@report must flag a tenant whose marker survived: " + reportText(untouched),
        reportText(untouched).contains("STILL PRESENT"));
  }

  // ---------------------------------------------------------------------------------------------
  // Running the shipped SQL
  // ---------------------------------------------------------------------------------------------

  /**
   * Runs all three statements of {@code @apply} for one tenant, in order, in ONE transaction, and
   * commits — which is the point: the runner wraps the whole section in a single transaction, so
   * the inserted subscription and the retired preference must land together or not at all.
   *
   * @param tenantId the tenant the fix is invoked for
   * @return how many subscription rows the insert created
   */
  private int apply(String tenantId) {
    lastRetiredPreferences = 0;
    List<String> statements = statementsOf(applySection, tenantId);
    assertEquals("@apply must be exactly three statements", 3, statements.size());
    OBContext.setOBContext(ZERO, ZERO, ZERO, ZERO);
    OBContext.setAdminMode(true);
    try {
      // Statement 1: the abort guard. It raises rather than returning rows, so running it is the
      // assertion — the fix must not proceed when the grandfathered plan is missing.
      OBDal.getInstance().getSession().createNativeQuery(statements.get(0)).list();
      int inserted = OBDal.getInstance().getSession().createNativeQuery(statements.get(1))
          .executeUpdate();
      // Statement 3: the per-tenant retirement, in the SAME transaction as the insert above.
      lastRetiredPreferences = OBDal.getInstance().getSession()
          .createNativeQuery(statements.get(2)).executeUpdate();
      OBDal.getInstance().flush();
      OBDal.getInstance().commitAndClose();
      return inserted;
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /** How many preference rows the last {@link #apply(String)} retired. */
  private int lastRetiredPreferences;

  /**
   * Runs {@code @report} for one tenant.
   *
   * @param tenantId the tenant the fix was invoked for
   * @return every reported cell flattened into one string, for containment assertions
   */
  @SuppressWarnings("rawtypes")
  private String reportText(String tenantId) {
    List<String> statements = statementsOf(reportSection, tenantId);
    assertEquals("@report must be a single statement", 1, statements.size());
    StringBuilder text = new StringBuilder();
    for (Object row : selectRows(statements.get(0))) {
      if (row instanceof Object[]) {
        for (Object cell : (Object[]) row) {
          text.append(cell).append(' ');
        }
      } else {
        text.append(row).append(' ');
      }
    }
    assertFalse("@report must always return at least one row on an applied tenant",
        text.toString().trim().isEmpty());
    return text.toString();
  }

  @SuppressWarnings({ "rawtypes", "unchecked" })
  private List<Object> selectRows(String sql) {
    OBContext.setOBContext(ZERO, ZERO, ZERO, ZERO);
    OBContext.setAdminMode(true);
    try {
      NativeQuery query = OBDal.getInstance().getSession().createNativeQuery(sql);
      return query.list();
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  private List<String> statementsOf(String sectionBody, String tenantId) {
    return splitStatements(substitute(sectionBody, tenantId));
  }

  /**
   * Substitutes the runner's two placeholders: the tenant, as a quoted literal (the runner binds
   * it; a native query here must not see a stray {@code :client_id}), and a fresh id for the row.
   *
   * @param sql a section body
   * @param tenantId the tenant the fix is invoked for
   * @return executable SQL
   */
  private static String substitute(String sql, String tenantId) {
    return sql.replace(":client_id", "'" + tenantId + "'")
        .replace("@uuid_ETGOSUB@", newId());
  }

  /**
   * Splits a section into statements on top-level semicolons, stripping {@code --} comments.
   *
   * <p>Single-quoted literals are tracked (including the doubled-quote escape) because the shipped
   * SQL contains both a semicolon inside a literal — {@code @report}'s pre-check sentence — and a
   * literal containing doubled quotes, {@code ''legacy-productive''}, in the abort guard. A naive
   * split on {@code ;} would cut the report in half and a naive comment strip would corrupt the
   * guard.
   *
   * @param sql a section body
   * @return the statements, comment-free and blank-free
   */
  private static List<String> splitStatements(String sql) {
    List<String> statements = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    boolean inLiteral = false;
    for (int i = 0; i < sql.length(); i++) {
      char character = sql.charAt(i);
      if (inLiteral) {
        current.append(character);
        if (character == '\'') {
          if (i + 1 < sql.length() && sql.charAt(i + 1) == '\'') {
            current.append('\'');
            i++;
          } else {
            inLiteral = false;
          }
        }
        continue;
      }
      if (character == '\'') {
        inLiteral = true;
        current.append(character);
        continue;
      }
      if (character == '-' && i + 1 < sql.length() && sql.charAt(i + 1) == '-') {
        while (i < sql.length() && sql.charAt(i) != '\n') {
          i++;
        }
        current.append('\n');
        continue;
      }
      if (character == ';') {
        addIfNotBlank(statements, current);
        current.setLength(0);
        continue;
      }
      current.append(character);
    }
    addIfNotBlank(statements, current);
    return statements;
  }

  private static void addIfNotBlank(List<String> statements, StringBuilder current) {
    if (!current.toString().trim().isEmpty()) {
      statements.add(current.toString());
    }
  }

  /**
   * Extracts one {@code -- @<name>} section.
   *
   * <p>The marker must match the line EXACTLY: the file's own header discusses the sections in
   * prose and contains a line beginning {@code -- @report placeholder convention}, which a prefix
   * match would mistake for the section itself and swallow the rest of the header into it.
   *
   * @param source the whole file
   * @param name {@code check}, {@code apply} or {@code report}
   * @return the section body, comments included
   */
  private static String section(String source, String name) {
    StringBuilder body = new StringBuilder();
    boolean inside = false;
    for (String line : source.split("\n", -1)) {
      String trimmed = line.trim();
      if (trimmed.matches("--\\s*@(check|apply|report)")) {
        inside = trimmed.matches("--\\s*@" + name);
        continue;
      }
      if (inside) {
        body.append(line).append('\n');
      }
    }
    return body.toString();
  }

  /**
   * @return the shipped fix, or null when the sibling schema_forge repository is not checked out
   */
  private static Path locateBackfillSql() {
    for (Path directory = Paths.get("").toAbsolutePath(); directory != null;
        directory = directory.getParent()) {
      Path candidate = directory.resolve(SQL_RELATIVE_PATH);
      if (Files.isRegularFile(candidate)) {
        return candidate;
      }
    }
    return null;
  }

  private static String read(Path path) {
    try {
      return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Fixtures (committed)
  // ---------------------------------------------------------------------------------------------

  private static String newId() {
    return UUID.randomUUID().toString().replace("-", "").toUpperCase(Locale.ROOT);
  }

  /**
   * Makes sure the fix's precondition holds, and remembers whether it had to.
   *
   * <p>The {@code legacy-productive} row ships in
   * {@code src-db/database/sourcedata/ETGO_PLAN.xml} but only reaches a given database once
   * {@code update.database} has loaded it, so both "already there" and "not yet" are ordinary
   * states. When this class creates it, it creates it with the sourcedata's own id, value and
   * name, so a row left behind by a crashed run is indistinguishable from the real one — and
   * deletes it again in cleanup. When the row is already there it is left strictly alone.
   */
  private void ensureLegacyPlan() {
    Object existing = uniqueResult("SELECT ETGO_PLAN_ID FROM ETGO_PLAN WHERE VALUE = :value",
        "value", LEGACY_PLAN_VALUE);
    createdLegacyPlan = existing == null;
    if (createdLegacyPlan) {
      nativeUpdateCommitted("INSERT INTO ETGO_PLAN (ETGO_PLAN_ID, AD_CLIENT_ID, AD_ORG_ID, "
              + "ISACTIVE, CREATED, CREATEDBY, UPDATED, UPDATEDBY, VALUE, NAME) "
              + "VALUES (:id, '0', '0', 'Y', now(), '0', now(), '0', :value, :name)",
          "id", LEGACY_PLAN_ID, "value", LEGACY_PLAN_VALUE, "name", LEGACY_PLAN_NAME);
    }
  }

  /**
   * Creates a synthetic tenant, optionally carrying the legacy productive marker.
   *
   * <p>The marker is an {@code AD_PREFERENCE} row owned by the System pseudo-client and scoped
   * through {@code VISIBLEAT_CLIENT_ID} — exactly how {@code Preferences.setPreferenceValue}
   * writes it and how {@code TenantPlanService#resolvePlan} used to read it back. Building the
   * fixture any other way would make the whole class pass against a fix that matches nothing.
   *
   * @param label a human hint carried in the row, for an operator reading a leftover
   * @param productive whether to write the {@code ETGO_TenantPlan='productive'} marker
   * @return the new {@code AD_CLIENT_ID}
   */
  private String createTenant(String label, boolean productive) {
    String id = newId();
    nativeUpdateCommitted("INSERT INTO AD_CLIENT (AD_CLIENT_ID, AD_ORG_ID, ISACTIVE, CREATED, "
            + "CREATEDBY, UPDATED, UPDATEDBY, VALUE, NAME) "
            + "VALUES (:id, '0', 'Y', now(), '0', now(), '0', :value, :name)",
        "id", id, "value", MARKER + label, "name", MARKER + label + " " + id);
    if (productive) {
      nativeUpdateCommitted("INSERT INTO AD_PREFERENCE (AD_PREFERENCE_ID, AD_CLIENT_ID, AD_ORG_ID, "
              + "ISACTIVE, CREATED, CREATEDBY, UPDATED, UPDATEDBY, ATTRIBUTE, VALUE, "
              + "ISPROPERTYLIST, VISIBLEAT_CLIENT_ID, SELECTED) "
              + "VALUES (:id, '0', '0', 'Y', now(), '0', now(), '0', "
              + "'ETGO_TenantPlan', 'productive', 'N', :tenant, 'N')",
          "id", newId(), "tenant", id);
    }
    return id;
  }

  /**
   * Creates the paid checkout request the fix copies Stripe ids from, plus the account it needs.
   *
   * @param tenantId the tenant the request provisioned, stored in {@code CREATED_CLIENT_ID}
   * @param stripeCustomerId {@code cus_...}
   * @param stripeSubscriptionId {@code sub_...}; a request without one is never picked by the fix
   * @return the correlation id of the request
   */
  private String createPaidCheckoutRequest(String tenantId, String stripeCustomerId,
      String stripeSubscriptionId) {
    String accountId = newId();
    String email = MARKER.toLowerCase(Locale.ROOT) + newId().toLowerCase(Locale.ROOT)
        + "@example.test";
    nativeUpdateCommitted("INSERT INTO ETGO_ACCOUNT (ETGO_ACCOUNT_ID, AD_CLIENT_ID, AD_ORG_ID, "
            + "ISACTIVE, CREATED, CREATEDBY, UPDATED, UPDATEDBY, EMAIL, NAME, STATUS) "
            + "VALUES (:id, '0', '0', 'Y', now(), '0', now(), '0', :email, :name, 'active')",
        "id", accountId, "email", email, "name", MARKER + "account");

    String requestId = MARKER + newId();
    nativeUpdateCommitted("INSERT INTO ETGO_CHECKOUT_REQUEST (ETGO_CHECKOUT_REQUEST_ID, "
            + "AD_CLIENT_ID, AD_ORG_ID, ISACTIVE, CREATED, CREATEDBY, UPDATED, UPDATEDBY, "
            + "REQUEST_ID, ETGO_ACCOUNT_ID, ACCOUNT_EMAIL, CLIENT_NAME, CHECKOUT_STATUS, "
            + "STRIPE_CUSTOMER_ID, STRIPE_SUBSCRIPTION_ID, CREATED_CLIENT_ID, "
            + "PROVISIONING_ATTEMPTS, CREATING_AT, PAID_AT) "
            + "VALUES (:id, '0', '0', 'Y', now(), '0', now(), '0', :requestId, :accountId, "
            + ":email, :clientName, 'PROVISIONED', :customer, :subscription, :tenant, 0, "
            + "now(), now())",
        "id", newId(), "requestId", requestId, "accountId", accountId, "email", email,
        "clientName", MARKER + "tenant", "customer", stripeCustomerId,
        "subscription", stripeSubscriptionId, "tenant", tenantId);
    return requestId;
  }

  // ---------------------------------------------------------------------------------------------
  // Committed-state readers (native SQL — never the first-level cache)
  // ---------------------------------------------------------------------------------------------

  private long rawOpenCount(String tenantId) {
    return ((Number) uniqueResult("SELECT COUNT(*) FROM ETGO_SUBSCRIPTION "
        + "WHERE ENVIRONMENT_CLIENT_ID = :tenant AND ISACTIVE = 'Y' AND END_DATE IS NULL",
        "tenant", tenantId)).longValue();
  }

  private long rawTotalCount(String tenantId) {
    return ((Number) uniqueResult("SELECT COUNT(*) FROM ETGO_SUBSCRIPTION "
        + "WHERE ENVIRONMENT_CLIENT_ID = :tenant", "tenant", tenantId)).longValue();
  }

  private long rawSubscriptionTotal() {
    return ((Number) uniqueResult("SELECT COUNT(*) FROM ETGO_SUBSCRIPTION")).longValue();
  }

  /** How many {@code ETGO_TenantPlan} rows are visible at one tenant, active or not. */
  private long rawPreferenceCount(String tenantId) {
    return ((Number) uniqueResult("SELECT COUNT(*) FROM AD_PREFERENCE "
        + "WHERE ATTRIBUTE = 'ETGO_TenantPlan' AND VISIBLEAT_CLIENT_ID = :tenant",
        "tenant", tenantId)).longValue();
  }

  /** Fleet-wide {@code ETGO_TenantPlan} count — the cutover's end-condition query. */
  private long rawTenantPlanPreferenceTotal() {
    return ((Number) uniqueResult(
        "SELECT COUNT(*) FROM AD_PREFERENCE WHERE ATTRIBUTE = 'ETGO_TenantPlan'")).longValue();
  }

  private Object rawColumn(String subscriptionId, String column) {
    return uniqueResult("SELECT " + column + " FROM ETGO_SUBSCRIPTION "
        + "WHERE ETGO_SUBSCRIPTION_ID = :id", "id", subscriptionId);
  }

  @SuppressWarnings("rawtypes")
  private Object uniqueResult(String sql, Object... nameValuePairs) {
    OBContext.setOBContext(ZERO, ZERO, ZERO, ZERO);
    OBContext.setAdminMode(true);
    try {
      NativeQuery query = OBDal.getInstance().getSession().createNativeQuery(sql);
      bind(query, nameValuePairs);
      return query.uniqueResult();
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  @SuppressWarnings("rawtypes")
  private void nativeUpdateCommitted(String sql, Object... nameValuePairs) {
    OBContext.setOBContext(ZERO, ZERO, ZERO, ZERO);
    OBContext.setAdminMode(true);
    try {
      NativeQuery query = OBDal.getInstance().getSession().createNativeQuery(sql);
      bind(query, nameValuePairs);
      query.executeUpdate();
      OBDal.getInstance().flush();
      OBDal.getInstance().commitAndClose();
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  @SuppressWarnings({ "rawtypes", "unchecked" })
  private static void bind(NativeQuery query, Object... nameValuePairs) {
    for (int i = 0; i + 1 < nameValuePairs.length; i += 2) {
      query.setParameter((String) nameValuePairs[i], nameValuePairs[i + 1]);
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Cleanup
  // ---------------------------------------------------------------------------------------------

  /**
   * Removes every fixture with native SQL, by marker rather than by collected id so rows left by a
   * test that died mid-way go too. Order follows the foreign keys: subscriptions, preferences,
   * checkout requests, accounts, the {@code AD_SEQUENCE} rows the {@code AD_CLIENT} insert trigger
   * created, then the tenants — and the grandfathered plan only when this run created it.
   */
  @SuppressWarnings("rawtypes")
  private void deleteCommittedFixtures() {
    OBContext.setOBContext(ZERO, ZERO, ZERO, ZERO);
    OBContext.setAdminMode(true);
    try {
      String tenants = "(SELECT AD_CLIENT_ID FROM AD_CLIENT WHERE VALUE LIKE :marker)";
      for (String sql : new String[] {
          "DELETE FROM ETGO_SUBSCRIPTION WHERE ENVIRONMENT_CLIENT_ID IN " + tenants,
          "DELETE FROM AD_PREFERENCE WHERE VISIBLEAT_CLIENT_ID IN " + tenants,
          "DELETE FROM ETGO_CHECKOUT_REQUEST WHERE REQUEST_ID LIKE :marker",
          "DELETE FROM ETGO_ACCOUNT WHERE EMAIL LIKE lower(:marker)",
          "DELETE FROM AD_SEQUENCE WHERE AD_CLIENT_ID IN " + tenants,
          "DELETE FROM AD_CLIENT WHERE VALUE LIKE :marker" }) {
        NativeQuery delete = OBDal.getInstance().getSession().createNativeQuery(sql);
        delete.setParameter("marker", MARKER + "%");
        delete.executeUpdate();
      }
      if (createdLegacyPlan) {
        NativeQuery deletePlan = OBDal.getInstance().getSession()
            .createNativeQuery("DELETE FROM ETGO_PLAN WHERE ETGO_PLAN_ID = :id");
        deletePlan.setParameter("id", LEGACY_PLAN_ID);
        deletePlan.executeUpdate();
      }
      OBDal.getInstance().flush();
      OBDal.getInstance().commitAndClose();
    } finally {
      OBContext.restorePreviousMode();
    }
  }
}
