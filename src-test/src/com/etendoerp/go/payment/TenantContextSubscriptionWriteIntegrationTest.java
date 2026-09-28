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
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

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
 */
public class TenantContextSubscriptionWriteIntegrationTest extends OBBaseTest {

  private static final String MARKER = "ETP5046CTX-";
  private static final String ZERO = "0";

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
