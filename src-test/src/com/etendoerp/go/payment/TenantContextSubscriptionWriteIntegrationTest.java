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
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.Locale;
import java.util.UUID;

import org.hibernate.query.NativeQuery;
import org.junit.After;
import org.junit.Test;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.test.base.OBBaseTest;

import com.etendoerp.go.rest.EtendoGoJwtServlet;
import com.etendoerp.go.schemaforge.data.CheckoutRequest;
import com.etendoerp.go.schemaforge.data.Plan;
import com.etendoerp.go.schemaforge.data.Subscription;

/**
 * ETP-5046 — the two System-owned (client {@code 0}) writes a paid onboarding makes, run from the
 * context the onboarding actually runs them in: the new tenant's admin.
 *
 * <p><b>Why this class exists.</b> {@link SubscriptionService#openSubscription} and
 * {@link TenantPlanService#retireProductivePreference} are called from
 * {@code EtendoGoJwtServlet#applyPaidUpgradeSideEffects}, after onboarding has switched the thread
 * to the freshly created tenant. Every other spec of these methods either mocks {@link OBContext}
 * or runs them as system ({@code setOBContext("0",...)}), so none could see what the manual happy
 * path test of 2026-09-27 hit: {@code SecurityChecker.checkWriteAccess} requires a
 * {@code ClientEnabled} row's client to equal the <em>current</em> client, and
 * {@code setAdminMode(true)} keeps that check on. A client-0 row written as a tenant is therefore
 * refused ({@code Client (0) of object ... is not present in ClientList <tenant>}), the refusal
 * marks the whole request for rollback, and the half-saved row later broke the organization
 * flush — every paid upgrade failed.
 *
 * <p>The tenant context here is {@link OBBaseTest#setTestUserContext()} (F&amp;B International
 * Group's admin): exactly the shape of the onboarding context — a non-system current client, with
 * client {@code 0} readable but not the current one.
 *
 * <p>Fixtures are committed and removed by marker with native SQL, the same discipline as
 * {@link SubscriptionServiceIntegrationTest}; the synthetic tenants are never real clients.
 *
 * <p><b>The subscription-model switch decides which of the two writes happens.</b> Group 2 drives
 * the servlet's {@code applyPaidUpgradeSideEffects} itself, from the same tenant context, with the
 * {@link SubscriptionModelSwitch} row absent (the pre-ETP-5046 marker path) and present (the row
 * path). The switch is environment-wide, so those specs commit NOTHING: the tenant, the switch row
 * and everything the upgrade writes stay in the test's transaction, are read back through the same
 * session, and are discarded by the rollback that opens {@link #cleanUp()}.
 *
 * @covers com.etendoerp.go.payment.SubscriptionService
 * @covers com.etendoerp.go.payment.TenantPlanService
 * @covers com.etendoerp.go.payment.SubscriptionModelSwitch
 * @covers com.etendoerp.go.rest.EtendoGoJwtServlet
 */
public class TenantContextSubscriptionWriteIntegrationTest extends OBBaseTest {

  private static final String MARKER = "ETP5046CTX-";
  private static final String ZERO = "0";
  /** The grandfathered plan, seeded by EnsureLegacyPlanScript, so it exists in every environment. */
  private static final String LEGACY_PLAN_ID = "219D5C8E15C64E97B2F553B228D30DD0";
  private static final String PAYMENT_TOKEN = "etp5046ctx-checkout-request";
  private static final String ACCOUNT_EMAIL = "etp5046ctx@example.test";

  private final SubscriptionService subscriptionService = new SubscriptionService();
  private final TenantPlanService tenantPlanService = new TenantPlanService();

  /**
   * Deletes the committed fixtures, closes the session, and clears the thread context.
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

  /**
   * A paid onboarding opens the tenant's subscription while the thread runs as that tenant. The
   * row must be written, commit with the caller's transaction, and leave the caller's context
   * exactly as it found it — onboarding continues in it straight afterwards.
   */
  @Test
  public void testASubscriptionOpensFromATenantAdminContext() {
    String tenant = createTenant("open");
    String planId = createPlan();

    setTestUserContext();
    assertTenantContext();
    Plan plan = readPlanAsTheCaller(planId);

    Subscription opened = subscriptionService.openSubscription(tenant, plan, null,
        "cus_" + newId().toLowerCase(Locale.ROOT), "sub_" + newId().toLowerCase(Locale.ROOT),
        plan.getProviderPriceID());

    assertNotNull(opened);
    assertTenantContext();
    OBDal.getInstance().flush();
    OBDal.getInstance().commitAndClose();

    assertEquals("The subscription must have been committed", 1L,
        count("SELECT COUNT(*) FROM ETGO_SUBSCRIPTION WHERE ENVIRONMENT_CLIENT_ID = :tenant"
            + " AND AD_CLIENT_ID = '0' AND END_DATE IS NULL", "tenant", tenant));
  }

  /**
   * Right after the subscription opens, the same tenant-context caller retires the legacy
   * {@code ETGO_TenantPlan} marker, which also lives at client {@code 0}. It must really be gone
   * after the commit, and the caller's context must be untouched.
   */
  @Test
  public void testTheLegacyPlanMarkerIsRetiredFromATenantAdminContext() {
    String tenant = createTenant("retire");
    createPlanPreference(tenant);
    assertEquals("Sanity: the marker was committed", 1L, planPreferenceCount(tenant));

    setTestUserContext();
    assertTenantContext();

    assertTrue("The marker must be reported as retired",
        tenantPlanService.retireProductivePreference(tenant));
    assertTenantContext();
    OBDal.getInstance().flush();
    OBDal.getInstance().commitAndClose();

    assertEquals("The marker must be gone after the commit", 0L, planPreferenceCount(tenant));
  }

  // ---------------------------------------------------------------------------------------------
  // Group 2 — the paid upgrade itself, on either side of the subscription-model switch
  // (nothing committed: see the class comment)
  // ---------------------------------------------------------------------------------------------

  /**
   * With no {@code ETGO_SubscriptionModel} row the environment has not switched, so a paid upgrade
   * is recorded exactly as before ETP-5046: the {@code ETGO_TenantPlan} marker is written, no
   * {@code ETGO_SUBSCRIPTION} row is opened, and the checkout request is not even looked up for
   * one. The tenant still reads back as productive — through the fallback.
   */
  @Test
  public void testWithTheSwitchOffAPaidUpgradeWritesTheMarkerAndOpensNoRow() throws Exception {
    String tenant = prepareUpgrade(false);
    RecordingCheckoutRequestStore store = new RecordingCheckoutRequestStore(null);

    setTestUserContext();
    assertTenantContext();
    applyPaidUpgradeSideEffects(store, tenant);
    assertTenantContext();
    OBDal.getInstance().flush();

    assertEquals("The switched-off path writes the marker", 1L, planPreferenceCount(tenant));
    assertEquals("and opens no subscription row", 0L, subscriptionCount(tenant));
    assertEquals("No row is attempted, so the checkout request is never read", 0, store.finds);
    assertEquals("The marker is what makes the tenant productive while the switch is off",
        TenantPlanService.PLAN_PRODUCTIVE, tenantPlanService.resolvePlan(tenant));
  }

  /**
   * With a System-level {@code ETGO_SubscriptionModel = 'Y'} row the same upgrade opens the tenant's
   * subscription from the checkout request it was bought under, and writes no marker.
   */
  @Test
  public void testWithTheSwitchOnAPaidUpgradeOpensTheRowAndWritesNoMarker() throws Exception {
    String tenant = prepareUpgrade(true);

    setTestUserContext();
    assertTenantContext();
    RecordingCheckoutRequestStore store =
        new RecordingCheckoutRequestStore(checkoutRequestOn(LEGACY_PLAN_ID));
    applyPaidUpgradeSideEffects(store, tenant);
    assertTenantContext();
    OBDal.getInstance().flush();

    assertEquals("The switched-on path opens the tenant's subscription", 1L,
        subscriptionCount(tenant));
    assertEquals("The row is opened from the checkout request", 1, store.finds);
    assertEquals("and no marker is written next to it", 0L, planPreferenceCount(tenant));
  }

  /**
   * Starts the rolled-back scenario as System: a synthetic tenant, and the environment's switch
   * forced to the requested state — any switch row the database carries is removed first, inside
   * the same transaction, so the spec does not depend on how this machine is configured.
   */
  private String prepareUpgrade(boolean switchOn) {
    String tenant = newId();
    nativeUpdate("INSERT INTO AD_CLIENT (AD_CLIENT_ID, AD_ORG_ID, ISACTIVE, CREATED, CREATEDBY, "
            + "UPDATED, UPDATEDBY, VALUE, NAME) "
            + "VALUES (:id, '0', 'Y', now(), '0', now(), '0', :value, :name)",
        "id", tenant, "value", MARKER + "switch", "name", MARKER + "switch " + tenant);
    nativeUpdate("DELETE FROM AD_PREFERENCE WHERE PROPERTY = :property",
        "property", SubscriptionModelSwitch.PREFERENCE_PROPERTY);
    if (switchOn) {
      nativeUpdate("INSERT INTO AD_PREFERENCE (AD_PREFERENCE_ID, AD_CLIENT_ID, AD_ORG_ID, "
              + "ISACTIVE, CREATED, CREATEDBY, UPDATED, UPDATEDBY, PROPERTY, VALUE, "
              + "ISPROPERTYLIST, SELECTED) "
              + "VALUES (:id, '0', '0', 'Y', now(), '0', now(), '0', :property, :value, 'Y', 'N')",
          "id", newId(), "property", SubscriptionModelSwitch.PREFERENCE_PROPERTY,
          "value", SubscriptionModelSwitch.ON);
    }
    assertEquals("Sanity: the switch is in the state this spec describes", switchOn,
        readSwitchAsSystem());
    assertFalse("Sanity: a fresh tenant carries no marker", planPreferenceCount(tenant) > 0);
    return tenant;
  }

  private static boolean readSwitchAsSystem() {
    OBContext previous = OBContext.getOBContext();
    OBContext.setOBContext(ZERO, ZERO, ZERO, ZERO);
    try {
      return new SubscriptionModelSwitch().isOn();
    } finally {
      OBContext.setOBContext(previous);
    }
  }

  /**
   * An unsaved checkout request carrying what the upgrade reads off a paid one, built under the
   * caller's context.
   */
  private static CheckoutRequest checkoutRequestOn(String planId) {
    CheckoutRequest request = OBProvider.getInstance().get(CheckoutRequest.class);
    request.setPlan(readPlanAsTheCaller(planId));
    request.setStripeCustomer("cus_" + newId().toLowerCase(Locale.ROOT));
    request.setStripeSubscription("sub_" + newId().toLowerCase(Locale.ROOT));
    request.setStripePrice("price_" + newId().toLowerCase(Locale.ROOT));
    return request;
  }

  /**
   * Runs the servlet's paid-upgrade step on a fresh servlet whose checkout request store is
   * {@code store}.
   *
   * <p>Reached by reflection: the method and the store field are package-private to
   * {@code com.etendoerp.go.rest}, and this class lives with the services it writes through. A
   * renamed member fails here loudly ({@link NoSuchMethodException} / {@link NoSuchFieldException}),
   * never silently.
   */
  private static void applyPaidUpgradeSideEffects(CheckoutRequestStore store, String tenant)
      throws Exception {
    EtendoGoJwtServlet servlet = new EtendoGoJwtServlet();
    Field storeField = EtendoGoJwtServlet.class.getDeclaredField("checkoutRequestStore");
    storeField.setAccessible(true);
    storeField.set(servlet, store);
    Method apply = EtendoGoJwtServlet.class.getDeclaredMethod("applyPaidUpgradeSideEffects",
        String.class, String.class, String.class, String.class, String.class);
    apply.setAccessible(true);
    try {
      apply.invoke(servlet, tenant, null, MARKER + "switch", ACCOUNT_EMAIL, PAYMENT_TOKEN);
    } catch (InvocationTargetException e) {
      if (e.getCause() instanceof Exception) {
        throw (Exception) e.getCause();
      }
      throw e;
    }
  }

  /** Answers every lookup with one in-memory request, and counts the lookups. */
  private static final class RecordingCheckoutRequestStore extends CheckoutRequestStore {
    private final CheckoutRequest request;
    private int finds;

    RecordingCheckoutRequestStore(CheckoutRequest request) {
      this.request = request;
    }

    @Override
    public CheckoutRequest find(String requestId, String accountEmail) {
      finds++;
      return request;
    }
  }

  private long subscriptionCount(String tenant) {
    return count("SELECT COUNT(*) FROM ETGO_SUBSCRIPTION WHERE ENVIRONMENT_CLIENT_ID = :tenant"
        + " AND AD_CLIENT_ID = '0' AND END_DATE IS NULL", "tenant", tenant);
  }

  /** Runs one statement as System in the current transaction; never commits. */
  @SuppressWarnings("rawtypes")
  private static void nativeUpdate(String sql, Object... nameValuePairs) {
    OBContext.setOBContext(ZERO, ZERO, ZERO, ZERO);
    OBContext.setAdminMode(true);
    try {
      NativeQuery query = OBDal.getInstance().getSession().createNativeQuery(sql);
      bind(query, nameValuePairs);
      query.executeUpdate();
    } finally {
      OBContext.restorePreviousMode();
      OBContext.setOBContext((OBContext) null);
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Context
  // ---------------------------------------------------------------------------------------------

  /** The precondition that makes this class meaningful, and the postcondition of every write. */
  private static void assertTenantContext() {
    OBContext context = OBContext.getOBContext();
    assertNotNull("A caller context must be set", context);
    assertEquals("The caller must still be the tenant admin", TEST_CLIENT_ID,
        context.getCurrentClient().getId());
    assertEquals(TEST_USER_ID, context.getUser().getId());
    assertNotEquals("Client 0 must not be the current client, or this spec proves nothing", ZERO,
        context.getCurrentClient().getId());
  }

  /**
   * Loads the plan the way the onboarding holds it: an object read before the write, under the
   * caller's context (admin mode only because the test role has no Plans window access).
   */
  private static Plan readPlanAsTheCaller(String planId) {
    OBContext.setAdminMode(true);
    try {
      Plan plan = OBDal.getInstance().get(Plan.class, planId);
      assertNotNull("Sanity: the plan fixture must be readable", plan);
      return plan;
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Fixtures (committed)
  // ---------------------------------------------------------------------------------------------

  private static String newId() {
    return UUID.randomUUID().toString().replace("-", "").toUpperCase(Locale.ROOT);
  }

  private String createTenant(String label) {
    String id = newId();
    nativeUpdateCommitted("INSERT INTO AD_CLIENT (AD_CLIENT_ID, AD_ORG_ID, ISACTIVE, CREATED, "
            + "CREATEDBY, UPDATED, UPDATEDBY, VALUE, NAME) "
            + "VALUES (:id, '0', 'Y', now(), '0', now(), '0', :value, :name)",
        "id", id, "value", MARKER + label, "name", MARKER + label + " " + id);
    return id;
  }

  private String createPlan() {
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
      plan.setProviderPriceID("price_" + newId().toLowerCase(Locale.ROOT));
      plan.setDisplayPrice(new BigDecimal("49.0000"));
      plan.setCurrencyCode("EUR");
      OBDal.getInstance().save(plan);
      OBDal.getInstance().flush();
      String id = plan.getId();
      OBDal.getInstance().commitAndClose();
      return id;
    } finally {
      OBContext.restorePreviousMode();
      OBContext.setOBContext((OBContext) null);
    }
  }

  /** The row {@code Preferences.setPreferenceValue} writes: client 0, the tenant in VISIBLEAT. */
  private void createPlanPreference(String tenant) {
    nativeUpdateCommitted("INSERT INTO AD_PREFERENCE (AD_PREFERENCE_ID, AD_CLIENT_ID, AD_ORG_ID, "
            + "ISACTIVE, CREATED, CREATEDBY, UPDATED, UPDATEDBY, ATTRIBUTE, VALUE, "
            + "VISIBLEAT_CLIENT_ID, ISPROPERTYLIST, SELECTED) "
            + "VALUES (:id, '0', '0', 'Y', now(), '0', now(), '0', :attribute, 'productive', "
            + ":tenant, 'N', 'N')",
        "id", newId(), "attribute", TenantPlanService.PREFERENCE_ATTRIBUTE, "tenant", tenant);
  }

  private long planPreferenceCount(String tenant) {
    return count("SELECT COUNT(*) FROM AD_PREFERENCE WHERE ATTRIBUTE = :attribute"
            + " AND VISIBLEAT_CLIENT_ID = :tenant",
        "attribute", TenantPlanService.PREFERENCE_ATTRIBUTE, "tenant", tenant);
  }

  @SuppressWarnings("rawtypes")
  private long count(String sql, Object... nameValuePairs) {
    OBContext previous = OBContext.getOBContext();
    OBContext.setOBContext(ZERO, ZERO, ZERO, ZERO);
    OBContext.setAdminMode(true);
    try {
      NativeQuery query = OBDal.getInstance().getSession().createNativeQuery(sql);
      bind(query, nameValuePairs);
      return ((Number) query.uniqueResult()).longValue();
    } finally {
      OBContext.restorePreviousMode();
      OBContext.setOBContext(previous);
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
      OBContext.setOBContext((OBContext) null);
    }
  }

  @SuppressWarnings({ "rawtypes", "unchecked" })
  private static void bind(NativeQuery query, Object... nameValuePairs) {
    for (int i = 0; i + 1 < nameValuePairs.length; i += 2) {
      query.setParameter((String) nameValuePairs[i], nameValuePairs[i + 1]);
    }
  }

  /**
   * Removes everything this class committed, by marker, in foreign-key order: subscriptions and
   * preferences of the synthetic tenants, the {@code AD_SEQUENCE} rows the {@code AD_CLIENT}
   * insert trigger created, the tenants, then the plans.
   */
  @SuppressWarnings("rawtypes")
  private void deleteCommittedFixtures() {
    OBDal.getInstance().rollbackAndClose();
    OBContext.setOBContext(ZERO, ZERO, ZERO, ZERO);
    OBContext.setAdminMode(true);
    try {
      String tenants = "(SELECT AD_CLIENT_ID FROM AD_CLIENT WHERE VALUE LIKE :marker)";
      for (String sql : new String[] {
          "DELETE FROM ETGO_SUBSCRIPTION WHERE ENVIRONMENT_CLIENT_ID IN " + tenants,
          "DELETE FROM AD_PREFERENCE WHERE VISIBLEAT_CLIENT_ID IN " + tenants,
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
