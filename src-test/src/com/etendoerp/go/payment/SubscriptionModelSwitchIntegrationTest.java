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
import static org.junit.Assert.assertTrue;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.Layout;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configurator;
import org.apache.logging.log4j.core.config.Property;
import org.hibernate.query.NativeQuery;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.test.base.OBBaseTest;

/**
 * ETP-5046 — the subscription-model switch read from real {@code AD_Preference} rows, and the one
 * downstream decision that reads it outside the purchase path: the level of the transitional
 * fallback's log line.
 *
 * <p><b>Why a database spec.</b> {@code SubscriptionModelSwitchTest} pins the criteria the switch
 * builds, restriction by restriction, against a mocked {@code OBCriteria}. What it cannot see is
 * whether those restrictions, run by Hibernate against the real table, select exactly the
 * environment-wide row and nothing narrower: a {@code visibleAtClient} restriction that compiles to
 * the wrong column, an ordering on a property that is not the creation date, or the DAL's own
 * active filter disagreeing with the explicit one all pass a mock and fail here.
 *
 * <p><b>Everything is rolled back, nothing is committed.</b> The switch is environment-wide: a
 * committed {@code ETGO_SubscriptionModel} row would flip every paid purchase made against this
 * database while it existed, including a developer's manual onboarding running next to the build.
 * So every fixture — the switch rows, the synthetic tenant and its plan marker — is written with
 * native SQL in the test's own transaction, read back through the same session, and discarded by
 * {@link OBDal#rollbackAndClose()} in {@link #rollBack()}. For the same reason {@link #startClean()}
 * removes, inside that transaction only, any switch row the database already carries, so the specs
 * describe the rows they insert and not the state of the machine they run on.
 *
 * <p>The list value {@code ETGO_SubscriptionModel} is registered in {@code AD_REF_LIST} by this
 * module, but neither the switch nor the DAL validate {@code AD_Preference.Property} against it when
 * reading, so these specs do not depend on {@code update.database} having run.
 *
 * @covers com.etendoerp.go.payment.SubscriptionModelSwitch
 * @covers com.etendoerp.go.payment.TenantPlanPreferenceFallback
 */
public class SubscriptionModelSwitchIntegrationTest extends OBBaseTest {

  private static final String ZERO = "0";
  private static final String Y = "Y";
  private static final String N = "N";
  private static final String TENANT_MARKER = "ETP5046SW-";

  private final SubscriptionModelSwitch modelSwitch = new SubscriptionModelSwitch();

  /**
   * Runs as System and removes any switch row already in the database, inside the transaction that
   * {@link #rollBack()} discards.
   */
  @Before
  public void startClean() {
    OBContext.setOBContext(ZERO, ZERO, ZERO, ZERO);
    nativeUpdate("DELETE FROM AD_PREFERENCE WHERE PROPERTY = :property",
        "property", SubscriptionModelSwitch.PREFERENCE_PROPERTY);
  }

  /** Discards every row the spec wrote and clears the thread context. */
  @After
  public void rollBack() {
    OBDal.getInstance().rollbackAndClose();
    OBContext.setOBContext((OBContext) null);
  }

  // ---------------------------------------------------------------------------------------------
  // Group 1 — which row is the environment's decision
  // ---------------------------------------------------------------------------------------------

  /**
   * The one shape that turns the model on: client {@code 0}, active, no visibility restriction,
   * value {@code Y}. Asserted against its own absence first, so the spec cannot pass because
   * something else in the database already answered ON.
   */
  @Test
  public void testASystemLevelYRowTurnsTheModelOn() {
    assertFalse("Sanity: with no switch row the model is OFF", modelSwitch.isOn());

    insertSwitchRow(Y, Visibility.SYSTEM, true, 0);

    assertTrue("A System-level 'Y' row is the environment's decision to switch",
        modelSwitch.isOn());
  }

  /**
   * {@code VISIBLEAT_CLIENT_ID = '0'} and {@code VISIBLEAT_ORG_ID = '0'} spelled out are still
   * System level: the Preference window fills them in that way when the operator picks the System
   * client and the {@code *} organization, and a switch that only accepted NULL would ignore every
   * row created through the UI.
   */
  @Test
  public void testExplicitSystemVisibilityIsStillSystemLevel() {
    insertSwitchRow(Y, Visibility.EXPLICIT_SYSTEM, true, 0);

    assertTrue(modelSwitch.isOn());
  }

  /**
   * The shape {@code Preferences.setPreferenceValue} writes for a per-tenant value: the row lives at
   * client {@code 0} and names the tenant only in {@code VISIBLEAT_CLIENT_ID}. It is that tenant's
   * setting, not the environment's, and must not switch every other tenant's purchases.
   */
  @Test
  public void testAClientZeroRowVisibleAtOneTenantIsOff() {
    insertSwitchRow(Y, Visibility.TENANT, true, 0);

    assertFalse("A row visible at one tenant is not an environment-wide decision",
        modelSwitch.isOn());
  }

  /** A row owned by a tenant client is outside the System level altogether. */
  @Test
  public void testARowOwnedByATenantClientIsOff() {
    insertSwitchRow(Y, Visibility.TENANT_OWNED, true, 0);

    assertFalse(modelSwitch.isOn());
  }

  /** A row restricted to one role is a per-role setting, not the environment's. */
  @Test
  public void testARowRestrictedToARoleIsOff() {
    insertSwitchRow(Y, Visibility.ROLE, true, 0);

    assertFalse(modelSwitch.isOn());
  }

  /** A row restricted to one organization is a per-organization setting, not the environment's. */
  @Test
  public void testARowRestrictedToAnOrganizationIsOff() {
    insertSwitchRow(Y, Visibility.ORGANIZATION, true, 0);

    assertFalse(modelSwitch.isOn());
  }

  /**
   * An operator flips the switch by adding a newer row rather than editing the old one: the most
   * recently created System row decides. Here the newer row says {@code Y}.
   */
  @Test
  public void testTheNewestOfTwoSystemRowsWinsWhenItIsOn() {
    insertSwitchRow(N, Visibility.SYSTEM, true, 3600);
    insertSwitchRow(Y, Visibility.SYSTEM, true, 0);

    assertTrue("The newer 'Y' row must win over the older 'N' row", modelSwitch.isOn());
  }

  /**
   * The mirror of the spec above, so "newest wins" cannot be satisfied by "any Y wins": the older
   * row says {@code Y} and the newer one does not.
   */
  @Test
  public void testTheNewestOfTwoSystemRowsWinsWhenItIsOff() {
    insertSwitchRow(Y, Visibility.SYSTEM, true, 3600);
    insertSwitchRow(N, Visibility.SYSTEM, true, 0);

    assertFalse("The newer 'N' row must win over the older 'Y' row", modelSwitch.isOn());
  }

  /** Deactivating the row is how an operator turns it off without deleting it. */
  @Test
  public void testAnInactiveYRowIsOff() {
    insertSwitchRow(Y, Visibility.SYSTEM, false, 0);

    assertFalse("An inactive row is not a decision", modelSwitch.isOn());
  }

  // ---------------------------------------------------------------------------------------------
  // Group 2 — the switch sets the level of the transitional fallback's line
  // ---------------------------------------------------------------------------------------------

  /**
   * On an environment still OFF every paying tenant is recorded with the {@code ETGO_TenantPlan}
   * marker on purpose, so resolving one from it is the intended path: logged at DEBUG, never WARN.
   * The tenant must still read back as productive — the level is the only thing the switch changes
   * here.
   */
  @Test
  public void testAMarkedTenantResolvesAtDebugWhileNoSwitchRowExists() {
    String tenant = createTenantWithPlanMarker();
    LogCapture capture = LogCapture.attachTo(TenantPlanPreferenceFallback.class);
    try {
      assertEquals(TenantPlanService.PLAN_PRODUCTIVE, new TenantPlanService().resolvePlan(tenant));

      assertTrue("A switched-off environment must not warn: " + capture.messagesAt(Level.WARN),
          capture.messagesAt(Level.WARN).isEmpty());
      List<String> debug = capture.messagesAt(Level.DEBUG);
      assertEquals("One line per resolution: " + debug, 1, debug.size());
      assertTrue("The line names the tenant: " + debug, debug.get(0).contains(tenant));
    } finally {
      capture.detach();
    }
  }

  /**
   * On an environment that has switched, a tenant without a subscription row is a backfill that has
   * not run for it, and the line that says so is a WARN.
   */
  @Test
  public void testAMarkedTenantResolvesAtWarnOnceASystemYRowExists() {
    String tenant = createTenantWithPlanMarker();
    insertSwitchRow(Y, Visibility.SYSTEM, true, 0);
    LogCapture capture = LogCapture.attachTo(TenantPlanPreferenceFallback.class);
    try {
      assertEquals(TenantPlanService.PLAN_PRODUCTIVE, new TenantPlanService().resolvePlan(tenant));

      List<String> warn = capture.messagesAt(Level.WARN);
      assertEquals("One line per resolution: " + warn, 1, warn.size());
      assertTrue("The line names the tenant: " + warn, warn.get(0).contains(tenant));
      assertTrue("The WARN replaces the DEBUG line, it does not add to it: "
          + capture.messagesAt(Level.DEBUG), capture.messagesAt(Level.DEBUG).isEmpty());
    } finally {
      capture.detach();
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Fixtures (uncommitted, discarded by rollBack)
  // ---------------------------------------------------------------------------------------------

  /** The visibility shapes a switch row can take. */
  private enum Visibility {
    /** Client 0, every visibility column NULL. */
    SYSTEM,
    /** Client 0, visible at client 0 and organization 0 spelled out. */
    EXPLICIT_SYSTEM,
    /** Client 0, visible at one tenant client. */
    TENANT,
    /** Owned by a tenant client. */
    TENANT_OWNED,
    /** Client 0, visible at one role. */
    ROLE,
    /** Client 0, visible at one organization. */
    ORGANIZATION
  }

  /**
   * Inserts one {@code ETGO_SubscriptionModel} list preference.
   *
   * @param value the {@code VALUE} column
   * @param visibility which visibility shape the row takes
   * @param active the {@code ISACTIVE} flag
   * @param secondsOld how long before now the row was created; the switch orders by it
   */
  private void insertSwitchRow(String value, Visibility visibility, boolean active,
      int secondsOld) {
    String client = visibility == Visibility.TENANT_OWNED ? TEST_CLIENT_ID : ZERO;
    String visibleClient = null;
    String visibleOrg = null;
    String visibleRole = null;
    if (visibility == Visibility.EXPLICIT_SYSTEM) {
      visibleClient = ZERO;
      visibleOrg = ZERO;
    } else if (visibility == Visibility.TENANT) {
      visibleClient = TEST_CLIENT_ID;
    } else if (visibility == Visibility.ROLE) {
      visibleRole = TEST_ROLE_ID;
    } else if (visibility == Visibility.ORGANIZATION) {
      visibleOrg = TEST_ORG_ID;
    }
    nativeUpdate("INSERT INTO AD_PREFERENCE (AD_PREFERENCE_ID, AD_CLIENT_ID, AD_ORG_ID, ISACTIVE, "
            + "CREATED, CREATEDBY, UPDATED, UPDATEDBY, PROPERTY, VALUE, ISPROPERTYLIST, SELECTED, "
            + "VISIBLEAT_CLIENT_ID, VISIBLEAT_ORG_ID, VISIBLEAT_ROLE_ID) "
            + "VALUES (:id, :client, '0', :active, now() - make_interval(secs => :age), '0', "
            + "now(), '0', :property, :value, 'Y', 'N', "
            + "CAST(:visibleClient AS VARCHAR), CAST(:visibleOrg AS VARCHAR), "
            + "CAST(:visibleRole AS VARCHAR))",
        "id", newId(), "client", client, "active", active ? Y : N, "age", secondsOld,
        "property", SubscriptionModelSwitch.PREFERENCE_PROPERTY, "value", value,
        "visibleClient", visibleClient, "visibleOrg", visibleOrg, "visibleRole", visibleRole);
  }

  /**
   * A synthetic tenant with no subscription row and the {@code ETGO_TenantPlan='productive'} marker
   * in the shape {@code Preferences.setPreferenceValue} stores it: client 0, tenant in
   * {@code VISIBLEAT_CLIENT_ID}.
   */
  private String createTenantWithPlanMarker() {
    String tenant = newId();
    nativeUpdate("INSERT INTO AD_CLIENT (AD_CLIENT_ID, AD_ORG_ID, ISACTIVE, CREATED, CREATEDBY, "
            + "UPDATED, UPDATEDBY, VALUE, NAME) "
            + "VALUES (:id, '0', 'Y', now(), '0', now(), '0', :value, :name)",
        // AD_CLIENT.VALUE is 40 characters: the marker plus a short slice of the id.
        "id", tenant, "value", TENANT_MARKER + tenant.substring(0, 12),
        "name", TENANT_MARKER + tenant);
    nativeUpdate("INSERT INTO AD_PREFERENCE (AD_PREFERENCE_ID, AD_CLIENT_ID, AD_ORG_ID, ISACTIVE, "
            + "CREATED, CREATEDBY, UPDATED, UPDATEDBY, ATTRIBUTE, VALUE, VISIBLEAT_CLIENT_ID, "
            + "ISPROPERTYLIST, SELECTED) "
            + "VALUES (:id, '0', '0', 'Y', now(), '0', now(), '0', :attribute, :value, :tenant, "
            + "'N', 'N')",
        "id", newId(), "attribute", TenantPlanService.PREFERENCE_ATTRIBUTE,
        "value", TenantPlanService.PLAN_PRODUCTIVE, "tenant", tenant);
    return tenant;
  }

  private static String newId() {
    return UUID.randomUUID().toString().replace("-", "").toUpperCase(Locale.ROOT);
  }

  /** Runs one statement in the current transaction; never commits. */
  @SuppressWarnings({ "rawtypes", "unchecked" })
  private static void nativeUpdate(String sql, Object... nameValuePairs) {
    OBContext.setAdminMode(true);
    try {
      NativeQuery query = OBDal.getInstance().getSession().createNativeQuery(sql);
      for (int i = 0; i + 1 < nameValuePairs.length; i += 2) {
        query.setParameter((String) nameValuePairs[i], nameValuePairs[i + 1]);
      }
      query.executeUpdate();
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /**
   * Collects the events of one logger. Log4j2's default configuration in the test JVM is
   * ERROR-only, so the level is pinned to DEBUG while attached and restored afterwards; without
   * that neither line this group asserts would reach an appender and the specs would pass
   * vacuously.
   */
  private static final class LogCapture extends AbstractAppender {

    private final List<LogEvent> events = new ArrayList<>();
    private final String loggerName;
    private final Level previousLevel;

    private LogCapture(String loggerName, Level previousLevel) {
      super("Etp5046SwitchCapture", (Filter) null, (Layout<? extends Serializable>) null, true,
          new Property[0]);
      this.loggerName = loggerName;
      this.previousLevel = previousLevel;
    }

    static LogCapture attachTo(Class<?> type) {
      String name = type.getName();
      Level previous = LogManager.getLogger(name).getLevel();
      Configurator.setLevel(name, Level.DEBUG);
      LogCapture capture = new LogCapture(name, previous);
      capture.start();
      ((org.apache.logging.log4j.core.Logger) LogManager.getLogger(name)).addAppender(capture);
      return capture;
    }

    void detach() {
      ((org.apache.logging.log4j.core.Logger) LogManager.getLogger(loggerName))
          .removeAppender(this);
      stop();
      Configurator.setLevel(loggerName, previousLevel);
    }

    List<String> messagesAt(Level level) {
      List<String> messages = new ArrayList<>();
      for (LogEvent event : events) {
        if (level.equals(event.getLevel())) {
          messages.add(event.getMessage().getFormattedMessage());
        }
      }
      return messages;
    }

    @Override
    public void append(LogEvent event) {
      events.add(event.toImmutable());
    }
  }
}
