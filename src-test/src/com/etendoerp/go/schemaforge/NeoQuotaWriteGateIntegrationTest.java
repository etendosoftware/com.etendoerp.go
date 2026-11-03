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

package com.etendoerp.go.schemaforge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.query.NativeQuery;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestWatcher;
import org.junit.runner.Description;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.base.secureApp.VariablesSecureApp;
import org.openbravo.base.weld.test.WeldBaseTest;
import org.openbravo.client.kernel.RequestContext;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.access.User;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.businesspartner.BusinessPartner;
import org.openbravo.model.common.businesspartner.Location;
import org.openbravo.model.common.currency.Currency;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.common.enterprise.Warehouse;
import org.openbravo.model.common.plm.Product;
import org.openbravo.model.materialmgmt.transaction.ShipmentInOutLine;
import org.openbravo.model.pricing.pricelist.ProductPrice;
import org.openbravo.service.json.JsonConstants;
import org.openbravo.test.base.TestConstants;

import com.etendoerp.go.payment.SubscriptionService;
import com.etendoerp.go.schemaforge.data.BillingResource;
import com.etendoerp.go.schemaforge.data.Plan;
import com.etendoerp.go.schemaforge.data.PlanQuota;
import com.etendoerp.go.schemaforge.data.Subscription;
import com.etendoerp.go.schemaforge.data.UsageDaily;
import com.etendoerp.go.schemaforge.util.NeoTypeCoercionHelper;
import com.etendoerp.go.usage.UsageResourceValidator;
import com.etendoerp.go.usage.quota.ConsumptionSource;
import com.etendoerp.go.usage.quota.CreatedRowMatcher;
import com.etendoerp.go.usage.quota.DalQuotaLookup;
import com.etendoerp.go.usage.quota.QuotaClientCache;
import com.etendoerp.go.usage.quota.QuotaEvaluator;
import com.etendoerp.go.usage.quota.QuotaWriteGuard;

/**
 * <b>REQUIRES A DATABASE.</b> The plan-quota block of a NEO create, end to end: real catalog rows,
 * real DAL inserts, real SQL counts, a real advisory lock and a real commit or rollback.
 *
 * <p>The guarantee pinned here is the one no mock can show: <b>a refused create leaves nothing
 * committed</b>, and an allowed one is committed exactly once and then counts against the quota.
 * Every outcome is read back on <b>another thread</b>, through its own DAL session, so it is what
 * the database holds and never this thread's first-level cache.
 *
 * <ul>
 *   <li>CRUD create ({@code NeoCrudHandler} POST): {@link NeoQuotaWriteGate#run} owns the
 *       transaction and runs the create through the real {@code NeoBatchJsonDataService} deferred-
 *       commit instance (hence {@link WeldBaseTest}), against a {@code block} quota on {@code ADUser}
 *       rows. Live source (the {@code QuotaEvaluator} counts the rows under the
 *       {@code QuotaLock} advisory lock) and aggregate source (the {@code DalConsumptionReader} sums a
 *       stored {@code ETGO_USAGE_DAILY} day).</li>
 *   <li>Action create ({@link NeoQuotaCreateCheck}): a follow-up goods movement built by
 *       {@link InOutTargetBuilder} — the cheapest wired creator to drive for real (no CDI process,
 *       no completion) — whose second line goes over a {@code block} quota on movement lines. The
 *       follow-up handler answers the refusal with {@code toResponse()}; the header and both lines
 *       must be gone: no half-made document.</li>
 * </ul>
 *
 * <p><b>Fixture.</b> Each test builds and commits its own billing resource, plan, {@code block} plan
 * quota, open subscription (period around now) and, for the aggregate case, a stored usage day,
 * all for the F&amp;B demo client ({@link TestConstants.Clients#FB_GRP}) — never an Etendo GO tenant
 * such as SPRING7 — and every key or description carries {@value #MARKER}. The ids are generated by
 * the DAL on save. The test fails loudly, without touching anything, if F&amp;B already holds an
 * open subscription that is not this class's.
 *
 * <p><b>Cleanup</b> is explicit, not a rollback: the gate commits on purpose. Before each test,
 * and again in {@link #cleanUp()}, every row carrying the marker is deleted in FK order — so a run
 * killed between its fixture commit and {@code @After} heals on the next one. Each DELETE is best
 * effort; marked rows still present afterwards fail a passing test, and are only logged on a
 * failing one so they never hide its own failure.
 *
 * <p><b>The quota cache.</b> {@link QuotaClientCache} keeps a client's quotas for 60 s, and the
 * default guard is a JVM-wide singleton. The CRUD cases therefore build their own guard over a new
 * cache (the production pieces: {@link DalQuotaLookup}, {@link CreatedRowMatcher#dal()}, the default
 * {@link QuotaEvaluator}), so each sees its own fixture. The action case goes through the default
 * guard, as production does; it is the only case that does. It shares its JVM with the other DAL
 * tests ({@code goIsolatedDalTest}), any of which may have cached F&amp;B in that guard before this
 * fixture existed, so it first drops F&amp;B's entry ({@link #forgetTenantInTheDefaultGuard}).
 *
 * @covers com.etendoerp.go.schemaforge.NeoQuotaWriteGate
 * @covers com.etendoerp.go.schemaforge.NeoQuotaCreateCheck
 * @covers com.etendoerp.go.schemaforge.NeoBatchJsonDataService
 * @covers com.etendoerp.go.usage.quota.QuotaWriteGuard
 * @covers com.etendoerp.go.usage.quota.QuotaEvaluator
 * @covers com.etendoerp.go.usage.quota.DalConsumptionReader
 * @covers com.etendoerp.go.usage.quota.QuotaLock
 */
public class NeoQuotaWriteGateIntegrationTest extends WeldBaseTest {

  private static final Logger log = LogManager.getLogger(NeoQuotaWriteGateIntegrationTest.class);
  /** Marks every row this class writes; no underscore, it goes into an SQL/HQL {@code like}. */
  private static final String MARKER = "QGATEIT";
  private static final AtomicLong SEQUENCE = new AtomicLong();
  private static final String SYSTEM = "0";
  private static final String TENANT = TestConstants.Clients.FB_GRP;
  private static final long DAY_MS = 24L * 60 * 60 * 1000;

  // Real F&B demo data, the same rows as CreateDraftInvoiceHandlerNegativeQuantityIntegrationTest.
  private static final String BPARTNER_ID = "A6750F0D15334FB890C254369AC750A8";
  private static final String WAREHOUSE_ID = "B2D40D8A5D644DD89E329DC297309055";
  private static final String PRODUCT_PRICE_ID = "4028E6C72959682B01295B03CEE40245";
  private static final String EUR = "102";

  private final SubscriptionService subscriptionService = new SubscriptionService();
  /** This test's own rows: every key, user name and line description starts with it. */
  private String token;

  /**
   * The JSON service's singleton and the document-number allocation read the DAL servlet context,
   * which a test JVM does not have; OBBaseTest installs its mock one around each case.
   */
  @Override
  protected boolean shouldMockServletContext() {
    return true;
  }

  /** The marked rows the last cleanup could not delete, {@code ""} when there are none. */
  private String leftover = "";

  /**
   * Fails a passing test whose cleanup left marked rows behind, and only logs them when the test
   * already failed: a TestWatcher reports after {@code @After}, and its outcome never replaces the
   * test's own failure.
   */
  @Rule
  public TestWatcher leftoverCheck = new TestWatcher() {
    @Override
    protected void succeeded(Description description) {
      if (!leftover.isEmpty()) {
        throw new AssertionError("the cleanup left " + MARKER + " rows behind: " + leftover);
      }
    }

    @Override
    protected void failed(Throwable e, Description description) {
      if (!leftover.isEmpty()) {
        log.error("{} failed, and its cleanup also left {} rows behind: {}",
            description.getMethodName(), MARKER, leftover);
      }
    }
  };

  /**
   * Removes the marked rows a killed run may have committed (an open subscription on a marked plan
   * would otherwise fail every later run), then sets this test's token and context. Only rows
   * carrying {@value #MARKER} are touched.
   */
  @Before
  public void setUpFixtureToken() {
    assertEquals("an earlier run's " + MARKER + " rows could not be removed", "",
        deleteEverythingMarked());
    token = MARKER + Long.toString(SEQUENCE.incrementAndGet(), 36)
        + Long.toString(System.nanoTime(), 36).toUpperCase(Locale.ROOT);
    tenantContext();
  }

  /** Never throws: a leftover is reported by {@link #leftoverCheck}, after the test's outcome. */
  @After
  public void cleanUp() {
    try {
      RequestContext.get().setVariableSecureApp(null);
      OBDal.getInstance().rollbackAndClose();
    } catch (RuntimeException e) {
      log.warn("Could not reset the test session before the cleanup: {}", e.getMessage());
    }
    try {
      leftover = deleteEverythingMarked();
    } finally {
      OBContext.setOBContext((OBContext) null);
    }
  }

  // ===================== CRUD create, live source =====================

  /**
   * At the limit: one counted user already committed, {@code included_qty} 1. The create is
   * refused with the contract's 403 and the inserted row is rolled back — it never reaches the
   * database.
   */
  @Test
  public void aLiveCreateAtTheLimitIsRefusedAndNothingIsCommitted() throws Exception {
    givenBlockQuota(userResource(), ConsumptionSource.LIVE, 1L);
    givenCommittedUser(token + "A");

    NeoQuotaWriteGate.Gated gated = createUserThroughTheGate(freshGuard(), token + "B");

    assertQuotaExceeded(gated);
    assertEquals("the refused user must not be in the database", 0L, usersCommitted(token + "B"));
    assertEquals("the user already there is untouched", 1L, usersCommitted(token + "A"));
  }

  /**
   * Under the limit the create is committed exactly once, and it counts: the next create, now at
   * the limit, is refused and rolled back.
   */
  @Test
  public void aLiveCreateUnderTheLimitIsCommittedOnceAndCounts() throws Exception {
    givenBlockQuota(userResource(), ConsumptionSource.LIVE, 1L);

    NeoQuotaWriteGate.Gated first = createUserThroughTheGate(freshGuard(), token + "A");

    assertNull("under the limit the create stands", first.refusal());
    assertCreated(first);
    assertEquals("committed exactly once", 1L, usersCommitted(token + "A"));

    tenantContext();
    NeoQuotaWriteGate.Gated second = createUserThroughTheGate(freshGuard(), token + "B");

    assertQuotaExceeded(second);
    assertEquals(0L, usersCommitted(token + "B"));
    assertEquals(1L, usersCommitted(token + "A"));
  }

  // ===================== CRUD create, aggregate source =====================

  /** The stored usage of the period already reaches {@code included_qty}: refused, nothing kept. */
  @Test
  public void anAggregateCreateWhoseStoredUsageReachesTheLimitIsRefused() throws Exception {
    BillingResource resource = userResource();
    givenBlockQuota(resource, ConsumptionSource.AGGREGATE, 2L);
    givenStoredUsageToday(resource, 2L);

    NeoQuotaWriteGate.Gated gated = createUserThroughTheGate(freshGuard(), token + "A");

    assertQuotaExceeded(gated);
    assertEquals(0L, usersCommitted(token + "A"));
  }

  /** The stored usage is below {@code included_qty}: the create passes and is committed once. */
  @Test
  public void anAggregateCreateUnderTheStoredLimitIsCommitted() throws Exception {
    BillingResource resource = userResource();
    givenBlockQuota(resource, ConsumptionSource.AGGREGATE, 2L);
    givenStoredUsageToday(resource, 1L);

    NeoQuotaWriteGate.Gated gated = createUserThroughTheGate(freshGuard(), token + "A");

    assertNull(gated.refusal());
    assertCreated(gated);
    assertEquals(1L, usersCommitted(token + "A"));
  }

  // ===================== action create (NeoQuotaCreateCheck) =====================

  /**
   * A follow-up goods movement with two counted lines against {@code included_qty} 1: the first
   * line fits, the second goes over, so the whole document is refused. Answered as the follow-up
   * handler answers it ({@code Refused.toResponse()}), the header and both lines are rolled back.
   */
  @Test
  public void anActionWhoseLinesGoOverTheLimitLeavesNoHalfMadeDocument() throws Exception {
    BillingResource resource = resource(ShipmentInOutLine.ENTITY_NAME,
        "e.description like '" + token + "%'");
    givenBlockQuota(resource, ConsumptionSource.LIVE, 1L);
    forgetTenantInTheDefaultGuard();
    // The builder links every flushed line before the quota check runs, so the linker sees the
    // header this attempt really created — the one row the assertion must find gone.
    AtomicReference<String> headerId = new AtomicReference<>();
    // The core document-number observer reads the client from the request's VariablesSecureApp,
    // which a request always has and a test does not.
    RequestContext.get().setVariableSecureApp(new VariablesSecureApp(TestConstants.Users.ADMIN,
        TENANT, TestConstants.Orgs.ESP, TestConstants.Roles.FB_GRP_ADMIN, "en_US"));

    NeoResponse response = null;
    OBContext.setAdminMode(true);
    try {
      InOutTargetBuilder.build(InOutTargetBuilder.Direction.SALES, movementHeader(),
          List.of(movementLine(token + "-1"), movementLine(token + "-2")),
          (line, created) -> headerId.set(created.getShipmentReceipt().getId()));
      fail("the movement's second line is over the quota: the build must be refused");
    } catch (NeoQuotaCreateCheck.Refused refused) {
      response = refused.toResponse();
    } finally {
      OBContext.restorePreviousMode();
    }

    assertNotNull(response);
    assertEquals(403, response.getHttpStatus());
    assertEquals("QUOTA_EXCEEDED", response.getBody().getJSONObject("error").getString("code"));
    assertEquals(resource.getSearchKey(),
        response.getBody().getJSONObject("error").getJSONObject("quota").getString("resourceKey"));
    assertEquals("no movement line may survive the refusal", 0L, countCommitted(
        "select count(*) from m_inoutline where description like :value", token + "%"));
    assertNotNull("the builder flushed a header and linked its lines before the check",
        headerId.get());
    assertEquals("the movement header may not survive the refusal", 0L, countCommitted(
        "select count(*) from m_inout where m_inout_id = :value", headerId.get()));
  }

  // ===================== the create under test =====================

  /**
   * A NEO CRUD create of a user, as {@code NeoCrudHandler} POST runs it: through the gate, whose
   * create calls the JSON service's {@code add} with the Smartclient-wrapped body. No batch owns
   * the transaction, so the gate does.
   */
  private NeoQuotaWriteGate.Gated createUserThroughTheGate(QuotaWriteGuard guard, String username)
      throws Exception {
    JSONObject body = new JSONObject().put("name", username).put("username", username);
    Map<String, String> params = new HashMap<>();
    params.put(JsonConstants.ENTITYNAME, User.ENTITY_NAME);
    return NeoQuotaWriteGate.run(User.ENTITY_NAME, BatchService.currentJsonService(),
        service -> service.add(params,
            NeoTypeCoercionHelper.wrapForSmartclient(body, User.ENTITY_NAME, null)),
        guard, false);
  }

  /**
   * The production guard pieces over a NEW cache, so this test's fixture is read, not an entry
   * another test of this JVM left behind (the cache keeps a client for 60 s).
   */
  private static QuotaWriteGuard freshGuard() {
    return new QuotaWriteGuard(
        new QuotaClientCache(new DalQuotaLookup(), () -> System.nanoTime() / 1_000_000L),
        CreatedRowMatcher.dal(), new QuotaEvaluator(), () -> TENANT);
  }

  /**
   * Drops F&amp;B's entry from the default guard's cache, so the action case reads the quota its
   * fixture just committed. Another DAL test of this JVM may have cached F&amp;B within the last
   * {@link QuotaClientCache#TTL_MS}; production accepts that delay, this test cannot wait it out.
   * The cache has no eviction API, so its map is reached by reflection.
   */
  private static void forgetTenantInTheDefaultGuard() throws ReflectiveOperationException {
    Field cacheField = QuotaWriteGuard.class.getDeclaredField("cache");
    cacheField.setAccessible(true);
    Object cache = cacheField.get(QuotaWriteGuard.getDefault());
    Field entriesField = QuotaClientCache.class.getDeclaredField("entries");
    entriesField.setAccessible(true);
    ((Map<?, ?>) entriesField.get(cache)).remove(TENANT);
  }

  private void tenantContext() {
    OBContext.setOBContext(TestConstants.Users.ADMIN, TestConstants.Roles.FB_GRP_ADMIN, TENANT,
        TestConstants.Orgs.ESP);
  }

  // ===================== fixture (committed) =====================

  /** Counts the users of this test by creation date: live counts read them. */
  private BillingResource userResource() {
    return resource(User.ENTITY_NAME, "e.username like '" + token + "%'");
  }

  private BillingResource resource(String countedEntity, String restriction) {
    return committed(() -> {
      BillingResource resource = OBProvider.getInstance().get(BillingResource.class);
      resource.setNewOBObject(true);
      resource.setClient(OBDal.getInstance().get(Client.class, SYSTEM));
      resource.setOrganization(OBDal.getInstance().get(Organization.class, SYSTEM));
      resource.setActive(true);
      resource.setSearchKey(token);
      resource.setName(token);
      resource.setUnitLabel("rows");
      resource.setCountingMode(UsageResourceValidator.MODE_DECLARATIVE);
      resource.setCountedEntity(countedEntity);
      resource.setDateProperty("creationDate");
      resource.setHQLRestriction(restriction);
      OBDal.getInstance().save(resource);
      return resource;
    });
  }

  /**
   * A plan with one {@code block} quota on {@code resource}, and the tenant's open subscription to
   * it, its current period around now.
   */
  private void givenBlockQuota(BillingResource resource, ConsumptionSource source,
      long includedQty) {
    committed(() -> {
      // This class's own subscriptions were removed before the test, so an open one is foreign.
      assertFalse("F&B already holds an open ETGO_SUBSCRIPTION that is not this class's; this "
          + "test opens its own and never touches another one",
          subscriptionService.findOpen(TENANT).isPresent());
      Plan plan = OBProvider.getInstance().get(Plan.class);
      plan.setNewOBObject(true);
      plan.setClient(OBDal.getInstance().get(Client.class, SYSTEM));
      plan.setOrganization(OBDal.getInstance().get(Organization.class, SYSTEM));
      plan.setActive(true);
      plan.setSearchKey(token);
      plan.setName(token);
      plan.setBillingInterval("month");
      // No provider price: under Weld, PlanPriceDerivationHandler would look a price up at
      // Stripe. A plan without one is valid (the handler clears the derived price fields), and
      // the quota path reads no price.
      OBDal.getInstance().save(plan);

      PlanQuota quota = OBProvider.getInstance().get(PlanQuota.class);
      quota.setNewOBObject(true);
      quota.setClient(OBDal.getInstance().get(Client.class, SYSTEM));
      quota.setOrganization(OBDal.getInstance().get(Organization.class, SYSTEM));
      quota.setActive(true);
      quota.setPlan(plan);
      quota.setBillingResource(OBDal.getInstance().get(BillingResource.class, resource.getId()));
      quota.setIncludedQty(includedQty);
      quota.setEnforcementMode("block");
      quota.setConsumptionSource(source.value());
      OBDal.getInstance().save(quota);
      OBDal.getInstance().flush();

      Subscription subscription = subscriptionService.openSubscription(TENANT, plan, null,
          "cus_" + token.toLowerCase(Locale.ROOT), "sub_" + token.toLowerCase(Locale.ROOT));
      long now = System.currentTimeMillis();
      subscription.setCurrentPeriodStart(new Date(now - DAY_MS));
      subscription.setCurrentPeriodEnd(new Date(now + 29 * DAY_MS));
      OBDal.getInstance().save(subscription);
      return quota;
    });
  }

  /** A stored usage day of {@code quantity} for the tenant and resource, today. */
  private void givenStoredUsageToday(BillingResource resource, long quantity) {
    committed(() -> {
      UsageDaily day = OBProvider.getInstance().get(UsageDaily.class);
      day.setNewOBObject(true);
      day.setClient(OBDal.getInstance().get(Client.class, SYSTEM));
      day.setOrganization(OBDal.getInstance().get(Organization.class, SYSTEM));
      day.setActive(true);
      day.setTenantClient(OBDal.getInstance().get(Client.class, TENANT));
      day.setBillingResource(OBDal.getInstance().get(BillingResource.class, resource.getId()));
      day.setUsageDay(todayMidnight());
      day.setQuantity(quantity);
      day.setComputedAt(new Date());
      day.setSettled(false);
      OBDal.getInstance().save(day);
      return day;
    });
  }

  /** A counted user of the tenant, committed before the create under test. */
  private void givenCommittedUser(String username) {
    tenantContext();
    OBContext.setAdminMode(true);
    try {
      User user = OBProvider.getInstance().get(User.class);
      user.setNewOBObject(true);
      user.setClient(OBDal.getInstance().get(Client.class, TENANT));
      user.setOrganization(OBDal.getInstance().get(Organization.class, SYSTEM));
      user.setName(username);
      user.setUsername(username);
      OBDal.getInstance().save(user);
      OBDal.getInstance().commitAndClose();
    } finally {
      OBContext.restorePreviousMode();
    }
    assertEquals(1L, usersCommitted(username));
  }

  private InOutTargetBuilder.Header movementHeader() {
    Warehouse warehouse = OBDal.getInstance().get(Warehouse.class, WAREHOUSE_ID);
    BusinessPartner partner = OBDal.getInstance().get(BusinessPartner.class, BPARTNER_ID);
    Location address = partner.getBusinessPartnerLocationList().get(0);
    return new InOutTargetBuilder.Header(OBDal.getInstance().get(Client.class, TENANT),
        warehouse.getOrganization(), partner, address, warehouse,
        OBDal.getInstance().get(Currency.class, EUR), null);
  }

  private InOutTargetBuilder.Line movementLine(String description) {
    Product product = OBDal.getInstance().get(ProductPrice.class, PRODUCT_PRICE_ID).getProduct();
    return InOutTargetBuilder.Line.builder()
        .sourceLineId(description)
        .product(product)
        .uom(product.getUOM())
        .quantity(BigDecimal.ONE)
        .description(description)
        .stockable(product.isStocked())
        .build();
  }

  /** Runs {@code body} as System, commits it, and puts the tenant's context back. */
  private <T> T committed(java.util.function.Supplier<T> body) {
    OBContext.setOBContext(SYSTEM, SYSTEM, SYSTEM, SYSTEM);
    OBContext.setAdminMode(true);
    try {
      T result = body.get();
      OBDal.getInstance().flush();
      OBDal.getInstance().commitAndClose();
      return result;
    } finally {
      OBContext.restorePreviousMode();
      tenantContext();
    }
  }

  private static Date todayMidnight() {
    Calendar day = Calendar.getInstance();
    day.set(Calendar.HOUR_OF_DAY, 0);
    day.set(Calendar.MINUTE, 0);
    day.set(Calendar.SECOND, 0);
    day.set(Calendar.MILLISECOND, 0);
    return day.getTime();
  }

  // ===================== assertions =====================

  private static void assertQuotaExceeded(NeoQuotaWriteGate.Gated gated) throws Exception {
    NeoResponse refusal = gated.refusal();
    assertNotNull("the create must be refused", refusal);
    assertEquals(403, refusal.getHttpStatus());
    JSONObject error = refusal.getBody().getJSONObject("error");
    assertEquals("QUOTA_EXCEEDED", error.getString("code"));
    assertTrue(error.has("quota"));
  }

  private static void assertCreated(NeoQuotaWriteGate.Gated gated) throws Exception {
    JSONObject response = new JSONObject(gated.result()).getJSONObject(JsonConstants.RESPONSE_RESPONSE);
    assertEquals(gated.result(), JsonConstants.RPCREQUEST_STATUS_SUCCESS,
        response.getInt(JsonConstants.RESPONSE_STATUS));
  }

  // ===================== committed state, read on another thread =====================

  private static long usersCommitted(String username) {
    return countCommitted("select count(*) from ad_user where username = :value", username);
  }

  /**
   * Counts rows on ANOTHER thread, through that thread's own DAL session: what the database
   * committed, never what this thread's session still holds. Binds {@code value} to
   * {@code :value}.
   */
  private static long countCommitted(String sql, String value) {
    AtomicReference<Long> count = new AtomicReference<>();
    AtomicReference<Throwable> failure = new AtomicReference<>();
    Thread reader = new Thread(() -> {
      try {
        OBContext.setOBContext(SYSTEM, SYSTEM, SYSTEM, SYSTEM);
        @SuppressWarnings("rawtypes")
        NativeQuery query = OBDal.getInstance().getSession().createNativeQuery(sql);
        query.setParameter("value", value);
        count.set(((Number) query.uniqueResult()).longValue());
      } catch (Throwable t) { // NOSONAR - handed back to the test thread below
        failure.set(t);
      } finally {
        OBDal.getInstance().rollbackAndClose();
        OBContext.setOBContext((OBContext) null);
      }
    }, "quota-gate-it-reader");
    reader.start();
    try {
      reader.join(60_000L);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
    assertFalse("the reader thread did not finish", reader.isAlive());
    if (failure.get() != null) {
      throw new IllegalStateException("could not read the committed state", failure.get());
    }
    return count.get();
  }

  // ===================== cleanup =====================

  /**
   * Deletes, by the marker and in FK order, every row this class may have committed: the
   * movements and users a create left, then the usage days, subscriptions, plan quotas, plans and
   * billing resources of the fixture.
   *
   * <p>Best effort and total: each DELETE commits on its own, so one that fails (a new FK, say) is
   * logged and the others still run, and nothing here throws. What is still marked afterwards is
   * returned, for the caller to fail on.
   *
   * @return the marked rows still present, as {@code "table=count, ..."}; {@code ""} when none
   */
  private static String deleteEverythingMarked() {
    try {
      OBContext.setOBContext(SYSTEM, SYSTEM, SYSTEM, SYSTEM);
      OBContext.setAdminMode(true);
    } catch (RuntimeException e) {
      log.error("Could not enter the System context for the cleanup", e);
      return "cleanup not run: " + e.getMessage();
    }
    try {
      // A movement a refusal failed to roll back: its draft headers are found through the marked
      // lines, read before the lines go (M_InOutLine -> M_InOut has no ON DELETE CASCADE).
      List<?> headerIds = markedDraftHeaderIds();
      String resources = "(SELECT ETGO_Billing_Resource_ID FROM ETGO_Billing_Resource "
          + "WHERE Value LIKE :marker)";
      String plans = "(SELECT ETGO_Plan_ID FROM ETGO_Plan WHERE Value LIKE :marker)";
      List<String> filters = new ArrayList<>();
      filters.add("M_InOutLine WHERE Description LIKE :marker");
      filters.add("AD_User WHERE Username LIKE :marker");
      filters.add("ETGO_Usage_Daily WHERE ETGO_Billing_Resource_ID IN " + resources);
      filters.add("ETGO_Subscription WHERE ETGO_Plan_ID IN " + plans);
      filters.add("ETGO_Plan_Quota WHERE ETGO_Plan_ID IN " + plans);
      filters.add("ETGO_Plan WHERE Value LIKE :marker");
      filters.add("ETGO_Billing_Resource WHERE Value LIKE :marker");
      if (!headerIds.isEmpty()) {
        filters.add("M_InOut WHERE M_InOut_ID IN (:ids) AND DocStatus = 'DR'");
      }
      for (String filter : filters) {
        runMarked("DELETE FROM " + filter, headerIds, false);
      }
      List<String> remaining = new ArrayList<>();
      for (String filter : filters) {
        long count = runMarked("SELECT count(*) FROM " + filter, headerIds, true);
        if (count != 0) {
          remaining.add(filter.substring(0, filter.indexOf(' ')) + "="
              + (count < 0 ? "unreadable" : String.valueOf(count)));
        }
      }
      return String.join(", ", remaining);
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /** The draft movement headers of the marked lines; empty when they cannot be read. */
  @SuppressWarnings("rawtypes")
  private static List<?> markedDraftHeaderIds() {
    try {
      NativeQuery headers = OBDal.getInstance().getSession().createNativeQuery(
          "SELECT DISTINCT l.M_InOut_ID FROM M_InOutLine l JOIN M_InOut h"
              + " ON h.M_InOut_ID = l.M_InOut_ID WHERE l.Description LIKE :marker"
              + " AND h.DocStatus = 'DR'");
      headers.setParameter("marker", MARKER + "%");
      return headers.list();
    } catch (RuntimeException e) {
      log.warn("Could not read the marked movement headers: {}", e.getMessage());
      return List.of();
    } finally {
      OBDal.getInstance().rollbackAndClose();
    }
  }

  /**
   * Runs one marked DELETE or count in its own transaction, binding {@code :marker} and, when the
   * statement names it, {@code :ids}. Never throws.
   *
   * @return the count of a query; for a DELETE, 0. {@code -1} when the statement failed
   */
  @SuppressWarnings("rawtypes")
  private static long runMarked(String sql, List<?> headerIds, boolean query) {
    try {
      NativeQuery statement = OBDal.getInstance().getSession().createNativeQuery(sql);
      if (sql.contains(":marker")) {
        statement.setParameter("marker", MARKER + "%");
      }
      if (sql.contains(":ids")) {
        statement.setParameterList("ids", headerIds);
      }
      long result = query ? ((Number) statement.uniqueResult()).longValue() : 0L;
      if (!query) {
        statement.executeUpdate();
      }
      OBDal.getInstance().commitAndClose();
      return result;
    } catch (RuntimeException e) {
      log.warn("Cleanup statement failed, continuing with the next one: {} -> {}", sql,
          e.getMessage());
      try {
        OBDal.getInstance().rollbackAndClose();
      } catch (RuntimeException rollback) {
        log.warn("Could not roll back the failed cleanup statement: {}", rollback.getMessage());
      }
      return -1L;
    }
  }
}
