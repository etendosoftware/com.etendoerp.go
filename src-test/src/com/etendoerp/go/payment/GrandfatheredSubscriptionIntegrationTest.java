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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.sql.Timestamp;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.hibernate.query.NativeQuery;
import org.junit.After;
import org.junit.Test;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.dal.service.OBQuery;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.test.base.OBBaseTest;

import com.etendoerp.go.schemaforge.data.Plan;
import com.etendoerp.go.schemaforge.data.PlanQuota;
import com.etendoerp.go.schemaforge.data.Subscription;

/**
 * ETP-5046 — real-database specs for the grandfathered plan shape, guarding the single most
 * dangerous possible mis-implementation of this feature: <b>deciding that a tenant is productive
 * by looking at its plan's provider price id.</b>
 *
 * <p>The {@code legacy-productive} plan exists precisely so that tenants provisioned before
 * provider billing have a catalog row to point at. It predates Stripe, so it has NO
 * {@code PROVIDER_PRICE_ID}, no {@code DISPLAY_PRICE} and no {@code CURRENCY_CODE} — the table's
 * own {@code ETGO_PLAN_PRICED_CHK} allows exactly that combination. A {@code resolvePlan} keyed on
 * "does this plan have a price id" would therefore flip EVERY backfilled tenant to {@code free}
 * the instant it shipped, silently, with no error anywhere — every paying legacy customer locked
 * out of the features they paid for. {@code TenantPlanService#resolvePlan} says so in prose; this
 * class says so in a way that fails the build.
 *
 * <p>A mock could not catch that regression, because the mis-implementation and the correct
 * implementation differ only in which committed columns they read. So the plan here is a real row
 * with real NULLs, and the answer is taken from the real service.
 *
 * <p><b>The second invariant: absence is the answer.</b> A plan is UNLIMITED for a resource when
 * it has NO {@code ETGO_PLAN_QUOTA} row for it. {@code PlanQuotaSchemaInvariantTest} guards that
 * from the XML side (no default on {@code INCLUDED_QTY}); this class guards it from the data side,
 * by asserting the grandfathered plan's quota query comes back EMPTY. The assertion is on
 * emptiness and never on a quantity — a row carrying zero would mean "capped at zero", the exact
 * opposite of unlimited, and a spec that accepted either would not notice the difference.
 *
 * <p><b>Cleanup is explicit, not a rollback</b>, for the same reason as the sibling integration
 * tests: {@code resolvePlan} must read committed state, so every fixture is committed and removed
 * afterwards by the {@value #MARKER} marker with native SQL. The tenants are synthetic
 * {@code AD_CLIENT} rows this class creates; no real tenant is read or written.
 */
public class GrandfatheredSubscriptionIntegrationTest extends OBBaseTest {

  /** Prefix on every fixture {@code AD_CLIENT.VALUE} and {@code ETGO_PLAN.VALUE}. */
  private static final String MARKER = "ETP5046GF-";

  private static final String ZERO = "0";

  private final SubscriptionService subscriptions = new SubscriptionService();
  private final TenantPlanService plans = new TenantPlanService();

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
  // Group 1 — productive means an open subscription, not a priced plan
  // ---------------------------------------------------------------------------------------------

  /**
   * The spec this whole class exists for. The plan carries no provider price id at all, which is
   * the grandfathered shape, and the tenant is still productive because it holds an open
   * subscription in a paying status.
   *
   * <p>Note the asymmetry that makes this assertion meaningful: {@code resolvePlan} swallows any
   * {@link RuntimeException} and degrades to {@code free}, so a broken query, a missing table or a
   * context failure all read as {@code free} too. {@code productive} is the only answer this
   * method cannot produce by accident — which is why the positive case is asserted first and the
   * NULL price id is asserted alongside it, so the spec cannot pass while quietly testing nothing.
   */
  @Test
  public void testATenantOnTheGrandfatheredPricelessPlanIsProductive() {
    String planId = createGrandfatheredPlan();
    String tenant = createTenant("legacy");
    openCommitted(tenant, planId, SubscriptionService.STATUS_ACTIVE);

    assertNull("Sanity: the grandfathered plan must genuinely have no provider price id, "
        + "otherwise this spec is not testing the grandfathered shape",
        rawPlanColumn(planId, "PROVIDER_PRICE_ID"));
    assertNull(rawPlanColumn(planId, "DISPLAY_PRICE"));
    assertNull(rawPlanColumn(planId, "CURRENCY_CODE"));

    assertEquals("A tenant with an open subscription is productive even though its plan has no "
        + "price id — productive means 'has an open subscription', never 'has a priced plan'",
        TenantPlanService.PLAN_PRODUCTIVE, plans.resolvePlan(tenant));
  }

  /**
   * {@code past_due} is deliberately productive: an invoice has failed but the subscription is
   * still live and the provider is still retrying, so cutting access off mid-dunning would punish
   * a customer whose card merely expired. {@code canceled} is deliberately not.
   *
   * <p>Both are asserted on the SAME tenant and the same row, mutated in place, so the two answers
   * cannot come from two differently-built fixtures.
   */
  @Test
  public void testPastDueIsStillProductiveAndCanceledIsNot() {
    String planId = createGrandfatheredPlan();
    String tenant = createTenant("dunning");
    String subscriptionId = openCommitted(tenant, planId, SubscriptionService.STATUS_ACTIVE);

    setStatusCommitted(subscriptionId, SubscriptionService.STATUS_PAST_DUE);
    assertEquals("A failed invoice must not cut the tenant off mid-dunning",
        TenantPlanService.PLAN_PRODUCTIVE, plans.resolvePlan(tenant));

    setStatusCommitted(subscriptionId, SubscriptionService.STATUS_CANCELED);
    assertEquals("A terminated subscription is no longer entitled to the paid plan",
        TenantPlanService.PLAN_FREE, plans.resolvePlan(tenant));
  }

  /**
   * A closed row is not an open row, whatever its status says. This is what will keep a tenant
   * from staying productive on the strength of last year's subscription once plan changes
   * (ETP-5053) start leaving closed rows behind: the status column of a superseded row is
   * history, not entitlement.
   */
  @Test
  public void testAClosedSubscriptionIsNoLongerProductiveEvenWhileItsStatusStillSaysActive() {
    String planId = createGrandfatheredPlan();
    String tenant = createTenant("closed");
    String subscriptionId = openCommitted(tenant, planId, SubscriptionService.STATUS_ACTIVE);

    closeCommitted(subscriptionId);

    assertEquals("Sanity: the row's status is untouched — only END_DATE was stamped",
        SubscriptionService.STATUS_ACTIVE, rawColumn(subscriptionId, "STATUS"));
    assertNotNull(rawColumn(subscriptionId, "END_DATE"));
    assertEquals("A closed subscription is not an entitlement", TenantPlanService.PLAN_FREE,
        plans.resolvePlan(tenant));
  }

  /**
   * The default for the entire existing fleet: no subscription row at all reads back as
   * {@code free}, with no migration and no null anywhere. {@code resolvePlan} is contractually
   * never null and never throwing, so the blank and unknown spellings of "no tenant" must answer
   * the same thing rather than blowing up inside a caller that has no null guard.
   */
  @Test
  public void testATenantWithNoSubscriptionAtAllIsFree() {
    String tenant = createTenant("unsubscribed");

    assertEquals(TenantPlanService.PLAN_FREE, plans.resolvePlan(tenant));
    assertEquals("An unknown tenant id", TenantPlanService.PLAN_FREE, plans.resolvePlan(newId()));
    assertEquals("No tenant at all", TenantPlanService.PLAN_FREE, plans.resolvePlan(null));
    assertEquals("A blank tenant id", TenantPlanService.PLAN_FREE, plans.resolvePlan("   "));
  }

  // ---------------------------------------------------------------------------------------------
  // Group 2 — no quota rows means unlimited
  // ---------------------------------------------------------------------------------------------

  /**
   * The grandfathered plan has zero quota children, and that absence IS the "unlimited" answer for
   * every resource.
   *
   * <p><b>The assertion is emptiness, never a quantity.</b> There is no such thing as a quota row
   * that means unlimited: a row that exists is a real, deliberate cap, and a row carrying
   * {@code INCLUDED_QTY = 0} would cap the resource at zero — total denial, the exact opposite of
   * what a grandfathered tenant is entitled to. So a spec that asserted "quantity is 0 or the list
   * is empty" would pass against the catastrophic case. This one cannot.
   */
  @Test
  public void testTheGrandfatheredPlanHasNoQuotaRowsAtAllWhichIsHowUnlimitedIsSpelled() {
    String planId = createGrandfatheredPlan();

    OBContext.setOBContext(ZERO, ZERO, ZERO, ZERO);
    OBContext.setAdminMode(true);
    try {
      OBQuery<PlanQuota> query = OBDal.getInstance().createQuery(PlanQuota.class,
          "as q where q." + PlanQuota.PROPERTY_PLAN + ".id = :planId");
      query.setNamedParameter("planId", planId);
      query.setFilterOnReadableClients(false);
      query.setFilterOnReadableOrganization(false);
      List<PlanQuota> quotas = query.list();

      assertTrue("The grandfathered plan must have NO quota rows: the absence of a row is the "
          + "unlimited case, so any row here — including one carrying zero — would cap a "
          + "grandfathered tenant. Found: " + quotas.size(), quotas.isEmpty());
    } finally {
      OBContext.restorePreviousMode();
    }

    assertEquals("Same answer straight from the database, past every cache", 0L,
        ((Number) uniqueResult("SELECT COUNT(*) FROM ETGO_PLAN_QUOTA WHERE ETGO_PLAN_ID = :planId",
            "planId", planId)).longValue());
  }

  // ---------------------------------------------------------------------------------------------
  // Fixtures (committed)
  // ---------------------------------------------------------------------------------------------

  private static String newId() {
    return UUID.randomUUID().toString().replace("-", "").toUpperCase(Locale.ROOT);
  }

  /**
   * Creates a plan in the grandfathered shape: no provider price id, no price, no currency — the
   * combination {@code ETGO_PLAN_PRICED_CHK} exists to allow.
   *
   * @return the new {@code ETGO_PLAN_ID}
   */
  private String createGrandfatheredPlan() {
    OBContext.setOBContext(ZERO, ZERO, ZERO, ZERO);
    OBContext.setAdminMode(true);
    try {
      Plan plan = OBProvider.getInstance().get(Plan.class);
      plan.setNewOBObject(true);
      plan.setClient(OBDal.getInstance().get(Client.class, ZERO));
      plan.setOrganization(OBDal.getInstance().get(Organization.class, ZERO));
      plan.setActive(true);
      plan.setSearchKey(MARKER + newId());
      plan.setName(MARKER + "legacy productive " + newId());
      plan.setProviderPriceID(null);
      plan.setDisplayPrice(null);
      plan.setCurrencyCode(null);
      plan.setBillingInterval(null);
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
   * Creates a synthetic tenant. Never one of this database's real productive clients.
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

  /**
   * Opens a subscription through the real service, sets the status under test, and commits.
   *
   * @param tenantId the tenant the subscription is for
   * @param planId the plan it points at
   * @param status one of the {@code SubscriptionService.STATUS_*} values
   * @return the new {@code ETGO_SUBSCRIPTION_ID}
   */
  private String openCommitted(String tenantId, String planId, String status) {
    OBContext.setOBContext(ZERO, ZERO, ZERO, ZERO);
    OBContext.setAdminMode(true);
    try {
      Subscription opened = subscriptions.openSubscription(tenantId,
          OBDal.getInstance().get(Plan.class, planId), null, null, null);
      opened.setSubscriptionStatus(status);
      OBDal.getInstance().save(opened);
      OBDal.getInstance().flush();
      String id = opened.getId();
      OBDal.getInstance().commitAndClose();
      return id;
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /**
   * Moves a committed subscription to another status, through native SQL and its own commit.
   *
   * <p>Native rather than a DAL setter, and committed rather than flushed, so the next
   * {@code resolvePlan} reads the database instead of a first-level cache that still holds the
   * previous status.
   *
   * @param subscriptionId the row to move
   * @param status the new status
   */
  private void setStatusCommitted(String subscriptionId, String status) {
    nativeUpdateCommitted("UPDATE ETGO_SUBSCRIPTION SET STATUS = :status "
        + "WHERE ETGO_SUBSCRIPTION_ID = :id", "status", status, "id", subscriptionId);
  }

  /**
   * Closes a subscription by stamping {@code END_DATE} and nothing else — the status column is
   * deliberately left saying {@code active}, because that is the state the spec is about.
   *
   * <p>A minute ahead of "now" so it can never fall below {@code START_DATE} and trip
   * {@code ETGO_SUB_DATES_CHK} for a reason unrelated to what is being tested.
   *
   * @param subscriptionId the row to close
   */
  private void closeCommitted(String subscriptionId) {
    nativeUpdateCommitted("UPDATE ETGO_SUBSCRIPTION SET END_DATE = :endDate "
            + "WHERE ETGO_SUBSCRIPTION_ID = :id",
        "endDate", new Timestamp(new Date().getTime() + 60_000L), "id", subscriptionId);
  }

  // ---------------------------------------------------------------------------------------------
  // Committed-state readers (native SQL — never the first-level cache)
  // ---------------------------------------------------------------------------------------------

  private Object rawColumn(String subscriptionId, String column) {
    return uniqueResult("SELECT " + column + " FROM ETGO_SUBSCRIPTION "
        + "WHERE ETGO_SUBSCRIPTION_ID = :id", "id", subscriptionId);
  }

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
