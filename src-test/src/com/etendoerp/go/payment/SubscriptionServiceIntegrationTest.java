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

import java.math.BigDecimal;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.hibernate.exception.ConstraintViolationException;
import org.hibernate.query.NativeQuery;
import org.junit.After;
import org.junit.Test;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.test.base.OBBaseTest;

import com.etendoerp.go.schemaforge.data.Plan;
import com.etendoerp.go.schemaforge.data.Subscription;

/**
 * ETP-5046 — real-database specs for {@link SubscriptionService} and, above all, for the partial
 * unique index that backs it:
 *
 * <pre>
 *   CREATE UNIQUE INDEX etgo_sub_open_envclient_uq
 *       ON etgo_subscription (environment_client_id)
 *    WHERE isactive = 'Y' AND end_date IS NULL;
 * </pre>
 *
 * <p><b>Why this class must exist.</b> "At most one OPEN subscription per tenant" is not enforced
 * anywhere in Java. {@link SubscriptionService#openSubscription} returns the existing row instead
 * of inserting a second one, but that is a convenience for a re-entered onboarding, not a
 * guarantee: it is a read-then-write with no lock, so two concurrent webhook deliveries, a
 * backfill racing a live payment, or any future writer that does not go through this service can
 * all produce a second open row. The only thing that actually cannot be bypassed is the index.
 *
 * <p>The index is declared in {@code src-db/database/model/tables/ETGO_SUBSCRIPTION.xml} as an
 * {@code <index unique="true">} carrying a {@code <whereClause>}. That {@code whereClause} is what
 * makes it PARTIAL, and it is one line of XML in a file that {@code export.database} rewrites
 * mechanically. <b>If a future {@code export.database} silently drops the {@code whereClause},
 * this test is the only thing that turns that into a red build instead of duplicate paying rows.</b>
 * Losing it in the other direction — the index disappearing altogether — is caught by
 * {@link #testASecondOpenSubscriptionForTheSameTenantIsRefusedByTheDatabase()}; losing only the
 * {@code whereClause} (so it degrades into a plain unique index on {@code ENVIRONMENT_CLIENT_ID})
 * is caught by {@link #testClosingTheFirstSubscriptionLetsTheNextOneOpen()}, which is the shape
 * every plan change (ETP-5053) will take. Neither test alone is sufficient; the pair is.
 *
 * <p><b>Cleanup is explicit, not a rollback</b> — the same discipline as
 * {@code BillingEventStoreIntegrationTest}. Every fixture is committed on purpose (a constraint
 * violation can only be observed against committed state, and a snapshot can only be proven stale
 * by re-reading it from the database after a separate commit), so {@link OBDal#rollbackAndClose()}
 * cleans up nothing. Every tenant, plan and subscription this class creates therefore carries the
 * {@value #MARKER} marker, and {@link #deleteCommittedFixtures()} removes them with native SQL in
 * FK order.
 *
 * <p><b>This class never touches a real tenant.</b> Its tenants are synthetic {@code AD_CLIENT}
 * rows it creates itself; the productive clients of this database are never read, written or
 * deleted. The {@code AD_CLIENT} insert trigger creates ~99 {@code AD_SEQUENCE} rows per client,
 * so cleanup removes those too.
 */
public class SubscriptionServiceIntegrationTest extends OBBaseTest {

  /** Prefix on every fixture {@code AD_CLIENT.VALUE} and {@code ETGO_PLAN.VALUE}. */
  private static final String MARKER = "ETP5046SUB-";

  /** The index whose name must appear in the failure chain for the spec to mean anything. */
  private static final String OPEN_INDEX = "etgo_sub_open_envclient_uq";

  private static final String ZERO = "0";

  private final SubscriptionService service = new SubscriptionService();

  /**
   * Deletes the committed fixtures, closes the session, and clears the thread context.
   *
   * <p>Deliberately not the admin-mode unwind loop: {@link SubscriptionService} installs a system
   * context when the thread has none and {@link OBContext#isInAdministratorMode()} then stays true
   * forever, so the loop cannot terminate. Same reasoning, and same shape, as
   * {@code CheckoutRequestStoreIntegrationTest#cleanUp}.
   */
  @After
  public void cleanUp() {
    try {
      deleteCommittedFixtures();
    } finally {
      OBDal.getInstance().rollbackAndClose();
      OBContext.setOBContext((OBContext) null);
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Group 1 — the index itself
  // ---------------------------------------------------------------------------------------------

  /**
   * The headline invariant: a tenant may not hold two open subscriptions.
   *
   * <p>The second row is built and saved directly rather than through
   * {@link SubscriptionService#openSubscription}, on purpose — the service would return the
   * existing row and the database would never be asked. What is under test here is the database,
   * not the service's courtesy guard.
   *
   * <p>The assertion is on the constraint NAME appearing somewhere in the failure chain rather
   * than on the exception type. Which type surfaces depends on where the flush was intercepted
   * (Hibernate's {@link ConstraintViolationException}, a {@code GenericJDBCException}, an
   * {@code OBException} wrapping either, or the driver's {@code PSQLException} underneath), and
   * pinning one of those would make this spec a test of Hibernate's exception translation rather
   * than of the schema. The name is what identifies the invariant.
   */
  @Test
  public void testASecondOpenSubscriptionForTheSameTenantIsRefusedByTheDatabase() {
    String tenant = createTenant("dup");
    String planId = createPricedPlan();
    String firstId = openCommitted(tenant, planId);
    assertNotNull("Sanity: the first subscription must have been committed", firstId);
    assertEquals(1L, rawOpenCount(tenant));

    Throwable failure = saveSecondOpenRowExpectingFailure(tenant, planId);

    assertNotNull("A second OPEN subscription for the same tenant must be refused by the database",
        failure);
    assertTrue("The failure must name " + OPEN_INDEX + ", otherwise this spec is pinning some "
        + "other constraint and the open-subscription invariant is unguarded. Chain was: "
        + describe(failure), chainNames(failure, OPEN_INDEX));
    assertEquals("The refused insert must have left exactly the one open row that was there",
        1L, rawOpenCount(tenant));
    assertEquals(1L, rawTotalCount(tenant));
  }

  /**
   * The other half of the pair, and the reason the index carries a {@code whereClause}: closing a
   * subscription must free the tenant to open the next one. This is exactly the shape a plan
   * change takes (ETP-5053 closes the current row and inserts the replacement), proven here before
   * that code exists.
   *
   * <p><b>Without this spec the suite would also pass against a plain unique index</b> on
   * {@code ENVIRONMENT_CLIENT_ID} — which is the wrong schema: it would make a tenant's price
   * history impossible and turn every future plan change into a constraint violation. A regression
   * that dropped the {@code whereClause} is invisible to every other test in this repository.
   */
  @Test
  public void testClosingTheFirstSubscriptionLetsTheNextOneOpen() {
    String tenant = createTenant("close");
    String planId = createPricedPlan();
    String firstId = openCommitted(tenant, planId);

    closeCommitted(firstId);
    assertNotNull("Sanity: the first row must now be closed", rawColumn(firstId, "END_DATE"));
    assertEquals("A closed row leaves the tenant with nothing open", 0L, rawOpenCount(tenant));

    String secondId = saveOpenRowCommitted(tenant, planId);

    assertNotNull("Closing the previous subscription must let the next one open — the index is "
        + "PARTIAL (isactive='Y' AND end_date IS NULL), not a plain unique index", secondId);
    assertEquals("Exactly one open row", 1L, rawOpenCount(tenant));
    assertEquals("Both rows survive: a tenant accumulates a price history", 2L,
        rawTotalCount(tenant));
    assertNull("The new row is the open one", rawColumn(secondId, "END_DATE"));
  }

  /**
   * The index is keyed on the tenant, so it must constrain each tenant independently. A regression
   * that indexed the wrong column (or indexed nothing at all beyond the predicate) would let the
   * first paying tenant block every other one — a failure that only shows up on the second
   * customer.
   */
  @Test
  public void testTwoDifferentTenantsMayEachHoldAnOpenSubscription() {
    String tenantA = createTenant("a");
    String tenantB = createTenant("b");
    String planId = createPricedPlan();

    String openA = openCommitted(tenantA, planId);
    String openB = openCommitted(tenantB, planId);

    assertNotNull(openA);
    assertNotNull(openB);
    assertFalse("Two distinct rows", openA.equals(openB));
    assertEquals(1L, rawOpenCount(tenantA));
    assertEquals(1L, rawOpenCount(tenantB));
  }

  // ---------------------------------------------------------------------------------------------
  // Group 2 — what the service reads back
  // ---------------------------------------------------------------------------------------------

  /**
   * {@code findOpen} means open, not "the most recent". A tenant whose only row is closed has no
   * subscription at all — that is what makes {@code TenantPlanService#resolvePlan} answer
   * {@code free} for a cancelled customer instead of keeping them entitled forever.
   *
   * <p>{@code findOpenForClients} answers the same question for a page full of tenants in one
   * query. Its contract is that a tenant without an open row is ABSENT from the map rather than
   * present with a null value, so the two spellings of "no subscription" cannot drift apart.
   */
  @Test
  public void testFindOpenIgnoresClosedRowsAndFindOpenForClientsResolvesSeveralTenants() {
    String subscribed = createTenant("open");
    String alsoSubscribed = createTenant("open2");
    String cancelled = createTenant("closed");
    String planId = createPricedPlan();
    String openRow = openCommitted(subscribed, planId);
    openCommitted(alsoSubscribed, planId);
    closeCommitted(openCommitted(cancelled, planId));

    OBContext.setOBContext(ZERO, ZERO, ZERO, ZERO);
    OBContext.setAdminMode(true);
    try {
      Optional<Subscription> found = service.findOpen(subscribed);
      assertTrue("The open row must be found", found.isPresent());
      assertEquals(openRow, found.get().getId());
      assertNull("The row found must be the open one", found.get().getEndDate());

      assertFalse("A tenant whose only subscription is closed has none open",
          service.findOpen(cancelled).isPresent());
      assertFalse("An unknown tenant has none open",
          service.findOpen(newId()).isPresent());

      Map<String, Subscription> byTenant = service.findOpenForClients(
          Arrays.asList(subscribed, alsoSubscribed, cancelled));

      assertEquals("Both open tenants resolve in one call; the cancelled one is absent", 2,
          byTenant.size());
      assertTrue(byTenant.containsKey(subscribed));
      assertTrue(byTenant.containsKey(alsoSubscribed));
      assertFalse("Absent, not present-with-null", byTenant.containsKey(cancelled));
      assertEquals(openRow, byTenant.get(subscribed).getId());
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Group 3 — the price snapshot
  // ---------------------------------------------------------------------------------------------

  /**
   * The ticket's headline guarantee, made executable: re-pricing a plan must change what NEW
   * buyers pay and leave every existing subscriber on the price they bought.
   *
   * <p>{@code PROVIDER_PRICE_ID}, {@code SNAPSHOT_AMOUNT} and {@code SNAPSHOT_CURRENCY} are copied
   * from the plan at {@code openSubscription} time and never rewritten. A regression that made the
   * subscription read its price THROUGH the plan FK instead of snapshotting it would look
   * identical in every unit test — the two are indistinguishable until the plan's price actually
   * changes.
   *
   * <p>So the plan is re-priced in its own committed transaction and the subscription is then
   * re-read <em>from the database</em> with native SQL. Reading it back through a DAL getter would
   * prove nothing: the entity is in Hibernate's first-level cache and would report what this test
   * wrote, not what is stored.
   */
  @Test
  public void testTheSnapshotIsTakenAtOpeningAndSurvivesALaterRepricingOfThePlan() {
    String tenant = createTenant("snapshot");
    String boughtPriceId = "price_" + newId().toLowerCase(Locale.ROOT);
    String planId = createPlan(boughtPriceId, new BigDecimal("49.0000"), "EUR");

    String subscriptionId = openCommitted(tenant, planId);

    assertEquals("The provider price id is snapshotted from the plan", boughtPriceId,
        rawColumn(subscriptionId, "PROVIDER_PRICE_ID"));
    assertEquals("The amount is snapshotted from the plan", 0,
        new BigDecimal("49.0000").compareTo((BigDecimal) rawColumn(subscriptionId,
            "SNAPSHOT_AMOUNT")));
    assertEquals("EUR", rawColumn(subscriptionId, "SNAPSHOT_CURRENCY"));

    String repricedPriceId = "price_" + newId().toLowerCase(Locale.ROOT);
    repriceCommitted(planId, repricedPriceId, new BigDecimal("99.0000"), "USD");

    assertEquals("Sanity: the plan really was re-priced", repricedPriceId,
        rawPlanColumn(planId, "PROVIDER_PRICE_ID"));
    assertEquals("An existing subscriber keeps the provider price id they bought", boughtPriceId,
        rawColumn(subscriptionId, "PROVIDER_PRICE_ID"));
    assertEquals("An existing subscriber keeps the amount they bought", 0,
        new BigDecimal("49.0000").compareTo((BigDecimal) rawColumn(subscriptionId,
            "SNAPSHOT_AMOUNT")));
    assertEquals("An existing subscriber keeps the currency they bought", "EUR",
        rawColumn(subscriptionId, "SNAPSHOT_CURRENCY"));
  }

  // ---------------------------------------------------------------------------------------------
  // Fixtures (committed)
  // ---------------------------------------------------------------------------------------------

  /** @return a 32-character uppercase hexadecimal id, the Etendo AD shape */
  private static String newId() {
    return UUID.randomUUID().toString().replace("-", "").toUpperCase(Locale.ROOT);
  }

  /**
   * Creates a synthetic tenant. Never one of this database's real productive clients: a fixture
   * that reused a real tenant could leave it looking subscribed.
   *
   * @param label a human hint carried in the row, for an operator reading a leftover
   * @return the new {@code AD_CLIENT_ID}
   */
  private String createTenant(String label) {
    String id = newId();
    nativeUpdateCommitted("INSERT INTO AD_CLIENT (AD_CLIENT_ID, AD_ORG_ID, ISACTIVE, CREATED, "
            + "CREATEDBY, UPDATED, UPDATEDBY, VALUE, NAME) "
            + "VALUES (:id, '0', 'Y', now(), '0', now(), '0', :value, :name)",
        "id", id, "value", MARKER + label, "name", MARKER + label + " " + id);
    return id;
  }

  /** @return the id of a plan with a provider price, the ordinary purchasable shape */
  private String createPricedPlan() {
    return createPlan("price_" + newId().toLowerCase(Locale.ROOT), new BigDecimal("19.0000"),
        "EUR");
  }

  /**
   * Creates a plan through the DAL and commits it.
   *
   * @param providerPriceId the provider price id, unique across the catalog
   * @param displayPrice the catalog price
   * @param currencyCode ISO currency code
   * @return the new {@code ETGO_PLAN_ID}
   */
  private String createPlan(String providerPriceId, BigDecimal displayPrice, String currencyCode) {
    OBContext.setOBContext(ZERO, ZERO, ZERO, ZERO);
    OBContext.setAdminMode(true);
    try {
      Plan plan = OBProvider.getInstance().get(Plan.class);
      plan.setNewOBObject(true);
      plan.setClient(OBDal.getInstance().get(Client.class, ZERO));
      plan.setOrganization(OBDal.getInstance().get(Organization.class, ZERO));
      plan.setActive(true);
      plan.setSearchKey(MARKER + newId());
      plan.setName(MARKER + "plan " + newId());
      plan.setBillingInterval("month");
      plan.setProviderPriceID(providerPriceId);
      plan.setDisplayPrice(displayPrice);
      plan.setCurrencyCode(currencyCode);
      OBDal.getInstance().save(plan);
      OBDal.getInstance().flush();
      String id = plan.getId();
      OBDal.getInstance().commitAndClose();
      return id;
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /**
   * Opens a subscription through the real service and commits it — the service deliberately does
   * not commit, because it runs inside the onboarding transaction.
   *
   * @param tenantId the tenant the subscription is for
   * @param planId the purchased plan
   * @return the new {@code ETGO_SUBSCRIPTION_ID}
   */
  private String openCommitted(String tenantId, String planId) {
    OBContext.setOBContext(ZERO, ZERO, ZERO, ZERO);
    OBContext.setAdminMode(true);
    try {
      Subscription opened = service.openSubscription(tenantId,
          OBDal.getInstance().get(Plan.class, planId), null,
          "cus_" + newId().toLowerCase(Locale.ROOT),
          "sub_" + newId().toLowerCase(Locale.ROOT));
      OBDal.getInstance().flush();
      String id = opened.getId();
      OBDal.getInstance().commitAndClose();
      return id;
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /**
   * Saves an open subscription row directly, bypassing {@link SubscriptionService}'s
   * already-open guard, and commits it.
   *
   * @param tenantId the tenant the subscription is for
   * @param planId the plan it points at
   * @return the new {@code ETGO_SUBSCRIPTION_ID}
   */
  private String saveOpenRowCommitted(String tenantId, String planId) {
    OBContext.setOBContext(ZERO, ZERO, ZERO, ZERO);
    OBContext.setAdminMode(true);
    try {
      Subscription row = buildOpenRow(tenantId, planId);
      OBDal.getInstance().save(row);
      OBDal.getInstance().flush();
      String id = row.getId();
      OBDal.getInstance().commitAndClose();
      return id;
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /**
   * Same write as {@link #saveOpenRowCommitted}, but expecting the database to refuse it.
   *
   * <p>A failed flush leaves the session unusable, so it is rolled back and closed before the
   * assertions run against committed state.
   *
   * @param tenantId the tenant that already holds an open subscription
   * @param planId the plan the refused row points at
   * @return the failure, or null when the write unexpectedly succeeded
   */
  private Throwable saveSecondOpenRowExpectingFailure(String tenantId, String planId) {
    OBContext.setOBContext(ZERO, ZERO, ZERO, ZERO);
    OBContext.setAdminMode(true);
    try {
      Subscription row = buildOpenRow(tenantId, planId);
      OBDal.getInstance().save(row);
      OBDal.getInstance().flush();
      OBDal.getInstance().commitAndClose();
      return null;
    } catch (RuntimeException expected) {
      OBDal.getInstance().rollbackAndClose();
      return expected;
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  private Subscription buildOpenRow(String tenantId, String planId) {
    Subscription row = OBProvider.getInstance().get(Subscription.class);
    row.setClient(OBDal.getInstance().get(Client.class, ZERO));
    row.setOrganization(OBDal.getInstance().get(Organization.class, ZERO));
    row.setEnvironmentClient(OBDal.getInstance().get(Client.class, tenantId));
    row.setPlan(OBDal.getInstance().get(Plan.class, planId));
    row.setSubscriptionStatus(SubscriptionService.STATUS_ACTIVE);
    row.setStartDate(new Date());
    row.setEndDate(null);
    return row;
  }

  /**
   * Closes a subscription by stamping {@code END_DATE}, the way a plan change will.
   *
   * <p>The end date is deliberately a minute ahead of "now" so it can never fall below
   * {@code START_DATE} and trip {@code ETGO_SUB_DATES_CHK}, which would fail this test for a
   * reason that has nothing to do with what it is about.
   *
   * @param subscriptionId the row to close
   */
  private void closeCommitted(String subscriptionId) {
    nativeUpdateCommitted("UPDATE ETGO_SUBSCRIPTION SET END_DATE = :endDate "
            + "WHERE ETGO_SUBSCRIPTION_ID = :id",
        "endDate", new Timestamp(System.currentTimeMillis() + 60_000L), "id", subscriptionId);
  }

  /**
   * Re-prices a plan in its own committed transaction — the event an existing subscriber must be
   * immune to.
   *
   * @param planId the plan to re-price
   * @param providerPriceId the new provider price id
   * @param displayPrice the new catalog price
   * @param currencyCode the new currency
   */
  private void repriceCommitted(String planId, String providerPriceId, BigDecimal displayPrice,
      String currencyCode) {
    OBContext.setOBContext(ZERO, ZERO, ZERO, ZERO);
    OBContext.setAdminMode(true);
    try {
      Plan plan = OBDal.getInstance().get(Plan.class, planId);
      plan.setProviderPriceID(providerPriceId);
      plan.setDisplayPrice(displayPrice);
      plan.setCurrencyCode(currencyCode);
      OBDal.getInstance().save(plan);
      OBDal.getInstance().flush();
      OBDal.getInstance().commitAndClose();
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Committed-state readers (native SQL — never the first-level cache)
  // ---------------------------------------------------------------------------------------------

  /**
   * @param tenantId the tenant
   * @return how many OPEN subscriptions the tenant holds — the index says at most one
   */
  private long rawOpenCount(String tenantId) {
    return ((Number) uniqueResult("SELECT COUNT(*) FROM ETGO_SUBSCRIPTION "
        + "WHERE ENVIRONMENT_CLIENT_ID = :tenant AND ISACTIVE = 'Y' AND END_DATE IS NULL",
        "tenant", tenantId)).longValue();
  }

  /**
   * @param tenantId the tenant
   * @return how many subscription rows the tenant has in total, open or closed
   */
  private long rawTotalCount(String tenantId) {
    return ((Number) uniqueResult("SELECT COUNT(*) FROM ETGO_SUBSCRIPTION "
        + "WHERE ENVIRONMENT_CLIENT_ID = :tenant", "tenant", tenantId)).longValue();
  }

  /**
   * @param subscriptionId the row
   * @param column a column name; always a literal from this class, never test input
   * @return the committed column value
   */
  private Object rawColumn(String subscriptionId, String column) {
    return uniqueResult("SELECT " + column + " FROM ETGO_SUBSCRIPTION "
        + "WHERE ETGO_SUBSCRIPTION_ID = :id", "id", subscriptionId);
  }

  /**
   * @param planId the plan
   * @param column a column name; always a literal from this class, never test input
   * @return the committed column value
   */
  private Object rawPlanColumn(String planId, String column) {
    return uniqueResult("SELECT " + column + " FROM ETGO_PLAN WHERE ETGO_PLAN_ID = :id",
        "id", planId);
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
  // Failure inspection
  // ---------------------------------------------------------------------------------------------

  /**
   * Walks the whole failure chain — causes AND {@link SQLException#getNextException()}, which is
   * where the driver hangs the detail Hibernate does not always copy — looking for the constraint
   * name, either as Hibernate extracted it or as PostgreSQL spelled it in the message.
   *
   * @param failure the thrown failure
   * @param constraintName the index name the invariant is carried by
   * @return true when the chain identifies that constraint
   */
  private static boolean chainNames(Throwable failure, String constraintName) {
    Set<Throwable> seen = new HashSet<>();
    Deque<Throwable> pending = new ArrayDeque<>();
    pushIfPresent(pending, failure);
    while (!pending.isEmpty()) {
      Throwable current = pending.pop();
      if (!seen.add(current)) {
        continue;
      }
      if (current instanceof ConstraintViolationException
          && containsIgnoreCase(((ConstraintViolationException) current).getConstraintName(),
              constraintName)) {
        return true;
      }
      if (containsIgnoreCase(current.getMessage(), constraintName)) {
        return true;
      }
      if (current instanceof SQLException) {
        pushIfPresent(pending, ((SQLException) current).getNextException());
      }
      pushIfPresent(pending, current.getCause());
    }
    return false;
  }

  /** @return every message in the chain, so a failing assertion says what actually happened */
  private static String describe(Throwable failure) {
    List<String> messages = new ArrayList<>();
    Set<Throwable> seen = new HashSet<>();
    Deque<Throwable> pending = new ArrayDeque<>();
    pushIfPresent(pending, failure);
    while (!pending.isEmpty()) {
      Throwable current = pending.pop();
      if (!seen.add(current)) {
        continue;
      }
      messages.add(current.getClass().getSimpleName() + ": " + current.getMessage());
      if (current instanceof SQLException) {
        pushIfPresent(pending, ((SQLException) current).getNextException());
      }
      pushIfPresent(pending, current.getCause());
    }
    return String.join(" | ", messages);
  }

  /**
   * {@link ArrayDeque} rejects null, and the end of a cause chain IS null, so every push is
   * guarded rather than every pop.
   *
   * @param pending the traversal stack
   * @param candidate the next failure to visit, possibly null
   */
  private static void pushIfPresent(Deque<Throwable> pending, Throwable candidate) {
    if (candidate != null) {
      pending.push(candidate);
    }
  }

  private static boolean containsIgnoreCase(String haystack, String needle) {
    return haystack != null
        && haystack.toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT));
  }

  // ---------------------------------------------------------------------------------------------
  // Cleanup
  // ---------------------------------------------------------------------------------------------

  /**
   * Removes everything this class committed, by marker rather than by collected id so rows left
   * behind by a test that died mid-way are cleaned up too. Order follows the foreign keys:
   * subscriptions, then the {@code AD_SEQUENCE} rows the {@code AD_CLIENT} insert trigger created,
   * then the tenants, then the plans.
   */
  @SuppressWarnings("rawtypes")
  private void deleteCommittedFixtures() {
    OBContext.setOBContext(ZERO, ZERO, ZERO, ZERO);
    OBContext.setAdminMode(true);
    try {
      String tenants = "(SELECT AD_CLIENT_ID FROM AD_CLIENT WHERE VALUE LIKE :marker)";
      for (String sql : new String[] {
          "DELETE FROM ETGO_SUBSCRIPTION WHERE ENVIRONMENT_CLIENT_ID IN " + tenants,
          "DELETE FROM AD_SEQUENCE WHERE AD_CLIENT_ID IN " + tenants,
          "DELETE FROM AD_CLIENT WHERE VALUE LIKE :marker",
          "DELETE FROM ETGO_PLAN WHERE VALUE LIKE :marker" }) {
        NativeQuery delete = OBDal.getInstance().getSession().createNativeQuery(sql);
        delete.setParameter("marker", MARKER + "%");
        delete.executeUpdate();
      }
      OBDal.getInstance().flush();
      OBDal.getInstance().commitAndClose();
    } finally {
      OBContext.restorePreviousMode();
    }
  }
}
