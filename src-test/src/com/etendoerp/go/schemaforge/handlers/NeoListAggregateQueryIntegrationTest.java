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
 * All portions are Copyright © 2021–2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.schemaforge.handlers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.ModelProvider;
import org.openbravo.base.model.Property;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.financialmgmt.payment.FIN_Payment;
import org.openbravo.service.json.JsonConstants;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.base.secureApp.VariablesSecureApp;
import org.openbravo.base.weld.test.WeldBaseTest;
import org.openbravo.client.kernel.RequestContext;
import org.openbravo.model.ad.access.Role;
import org.openbravo.model.ad.ui.Tab;

import com.etendoerp.go.schemaforge.NeoContext;
import com.etendoerp.go.schemaforge.NeoEndpointType;
import com.etendoerp.go.schemaforge.NeoListAggregateQuery;
import com.etendoerp.go.schemaforge.NeoReadPredicates;
import com.etendoerp.go.schemaforge.NeoResponse;
import com.etendoerp.go.schemaforge.NeoServlet;
import com.etendoerp.go.schemaforge.util.NeoDistinctFetchSupport;

/**
 * Runs the grouped HQL of {@link NeoListAggregateQuery} against a real database, with the payment-in
 * tab parameters the list GET produces, and checks the groups against a computation over the same
 * rows done with plain DAL reads. The grouped query is the only part of the payment list summary
 * that no mock can vouch for: the select/group-by constants, the join the identifier criterion
 * forces, and the readable client/organization flags are all resolved by Hibernate.
 *
 * <p>Extends WeldBaseTest, not plain OBBaseTest: grouping goes through DefaultJsonDataService, whose static initialiser needs the Weld bean manager.
 *
 * <p>Read-only: it creates no data, so there is nothing to clean up; it runs against whatever
 * receipts the test client holds. Lives in the handlers package only to reuse the production
 * {@code SELECT}/{@code GROUP_BY} constants of {@link PaymentListSummary} instead of copies.
 *
 * <p>The {@code distinctValues} tests run the filter-dropdown query of a {@code ?_distinct=} fetch
 * (the values present in the filtered view) and compare it with the same computation done over
 * plain DAL reads.
 *
 * @covers com.etendoerp.go.schemaforge.NeoListAggregateQuery
 */
public class NeoListAggregateQueryIntegrationTest extends WeldBaseTest {

  /** AD_TAB_ID of the payment-in "Payment" tab (ETGO_SF_ENTITY finPayment of spec payment-in). */
  private static final String PAYMENT_IN_TAB = "C4B6506838E14A349D6717D6856F1B56";
  private static final String PAYMENT_IN_WINDOW = "E547CE89D4C04429B6340FFA44E70716";

  @Before
  public void setUpAdminContext() {
    setTestAdminContext();
  }

  @After
  public void tearDown() {
    OBDal.getInstance().rollbackAndClose();
  }

  private static Map<String, String> listParams(String criteria) {
    Map<String, String> p = new HashMap<>();
    p.put(JsonConstants.ENTITYNAME, FIN_Payment.ENTITY_NAME);
    p.put(JsonConstants.TAB_PARAMETER, PAYMENT_IN_TAB);
    p.put(JsonConstants.WINDOW_ID, PAYMENT_IN_WINDOW);
    p.put(JsonConstants.NO_ACTIVE_FILTER, "true");
    p.put(JsonConstants.WHERE_AND_FILTER_CLAUSE, "(e.receipt='Y')");
    p.put(JsonConstants.USE_ALIAS, "true");
    p.put(JsonConstants.STARTROW_PARAMETER, "0");
    p.put(JsonConstants.ENDROW_PARAMETER, "100");
    p.put(JsonConstants.SORTBY_PARAMETER, "-paymentDate");
    if (criteria != null) {
      p.put("criteria", criteria);
    }
    return p;
  }

  private static String criterion(String field, String operator, String value) throws Exception {
    return new JSONObject().put("fieldName", field).put("operator", operator)
        .put("value", value).toString();
  }

  private static String and(String... criteria) throws Exception {
    JSONArray arr = new JSONArray();
    for (String c : criteria) {
      arr.put(new JSONObject(c));
    }
    return new JSONObject().put("_constructor", "AdvancedCriteria").put("operator", "and")
        .put("criteria", arr).toString();
  }

  private static List<Object[]> grouped(String criteria) {
    NeoContext ctx = NeoContext.builder().specName("payment-in").entityName("finPayment")
        .httpMethod("GET").endpointType(NeoEndpointType.CRUD).build();
    NeoListAggregateQuery.remember(ctx, listParams(criteria));
    return NeoListAggregateQuery.of(ctx).groupBy(PaymentListSummary.SELECT,
        PaymentListSummary.GROUP_BY);
  }

  /** All receipts of the test client, oldest first. */
  private static List<FIN_Payment> receipts() {
    OBCriteria<FIN_Payment> crit = OBDal.getInstance().createCriteria(FIN_Payment.class);
    crit.add(org.hibernate.criterion.Restrictions.eq(FIN_Payment.PROPERTY_RECEIPT, true));
    crit.addOrderBy(FIN_Payment.PROPERTY_PAYMENTDATE, true);
    return crit.list();
  }

  /** currencyId|status -> {sum, count} from the SQL groups (methods folded together). */
  private static Map<String, Object[]> foldGroups(List<Object[]> rows) {
    Map<String, Object[]> out = new TreeMap<>();
    for (Object[] r : rows) {
      String key = r[0] + "|" + r[4];
      Object[] acc = out.computeIfAbsent(key, k -> new Object[] { BigDecimal.ZERO, 0L });
      acc[0] = ((BigDecimal) acc[0]).add((BigDecimal) r[5]);
      acc[1] = (Long) acc[1] + ((Number) r[6]).longValue();
    }
    return out;
  }

  private static Map<String, Object[]> foldPayments(List<FIN_Payment> payments) {
    Map<String, Object[]> out = new TreeMap<>();
    for (FIN_Payment p : payments) {
      String key = p.getCurrency().getId() + "|" + p.getStatus();
      Object[] acc = out.computeIfAbsent(key, k -> new Object[] { BigDecimal.ZERO, 0L });
      acc[0] = ((BigDecimal) acc[0]).add(p.getAmount());
      acc[1] = (Long) acc[1] + 1L;
    }
    return out;
  }

  private static void assertSameGroups(Map<String, Object[]> expected,
      Map<String, Object[]> actual) {
    assertEquals(expected.keySet(), actual.keySet());
    for (Map.Entry<String, Object[]> e : expected.entrySet()) {
      Object[] a = actual.get(e.getKey());
      assertEquals(e.getKey(), 0, ((BigDecimal) e.getValue()[0]).compareTo((BigDecimal) a[0]));
      assertEquals(e.getKey(), e.getValue()[1], a[1]);
    }
  }

  private static String day(Date d) {
    return new SimpleDateFormat("yyyy-MM-dd").format(d);
  }

  @Test
  public void unfilteredGroupsEqualADalComputationOverTheSameReceipts() {
    List<FIN_Payment> payments = receipts();
    assertFalse("the test client has no receipts to aggregate", payments.isEmpty());

    assertSameGroups(foldPayments(payments), foldGroups(grouped(null)));
  }

  @Test
  public void paymentDateRangeCriterionNarrowsTheGroups() throws Exception {
    List<FIN_Payment> all = receipts();
    assertFalse(all.isEmpty());
    Date from = all.get(0).getPaymentDate();
    Calendar cal = Calendar.getInstance();
    cal.setTime(from);
    cal.add(Calendar.YEAR, 1);
    Date to = cal.getTime();

    List<FIN_Payment> inRange = new java.util.ArrayList<>();
    for (FIN_Payment p : all) {
      if (!p.getPaymentDate().before(from) && !p.getPaymentDate().after(to)) {
        inRange.add(p);
      }
    }
    assertFalse(inRange.isEmpty());
    assertTrue("the range must actually exclude something", inRange.size() < all.size());

    String criteria = and(criterion("paymentDate", "greaterOrEqual", day(from)),
        criterion("paymentDate", "lessOrEqual", day(to)));
    assertSameGroups(foldPayments(inRange), foldGroups(grouped(criteria)));
  }

  @Test
  public void identifierCriterionForcesTheJoinAndStillAggregatesCorrectly() throws Exception {
    List<FIN_Payment> all = receipts();
    assertFalse(all.isEmpty());
    String partnerName = all.get(0).getBusinessPartner().getName();

    List<FIN_Payment> matching = new java.util.ArrayList<>();
    for (FIN_Payment p : all) {
      if (p.getBusinessPartner().getIdentifier().toLowerCase().contains(partnerName.toLowerCase())) {
        matching.add(p);
      }
    }
    assertFalse(matching.isEmpty());

    String criteria = and(criterion("businessPartner$_identifier", "iContains", partnerName));
    assertSameGroups(foldPayments(matching), foldGroups(grouped(criteria)));
  }

  @Test
  public void dateRangeAndIdentifierCriteriaCombine() throws Exception {
    List<FIN_Payment> all = receipts();
    assertFalse(all.isEmpty());
    FIN_Payment seed = all.get(0);
    String partnerName = seed.getBusinessPartner().getName();
    String from = day(seed.getPaymentDate());

    List<FIN_Payment> matching = new java.util.ArrayList<>();
    for (FIN_Payment p : all) {
      if (!day(p.getPaymentDate()).equals(from) && p.getPaymentDate().before(seed.getPaymentDate())) {
        continue;
      }
      if (p.getBusinessPartner().getIdentifier().toLowerCase().contains(partnerName.toLowerCase())) {
        matching.add(p);
      }
    }
    String criteria = and(criterion("paymentDate", "greaterOrEqual", from),
        criterion("businessPartner$_identifier", "iContains", partnerName));
    assertSameGroups(foldPayments(matching), foldGroups(grouped(criteria)));
  }

  @Test
  public void aSummaryBuiltFromTheRealGroupsCountsEveryNonVoidedReceipt() throws Exception {
    List<FIN_Payment> all = receipts();
    long nonVoid = all.stream().filter(p -> !"RPVOID".equals(p.getStatus())).count();

    List<PaymentListSummary.Group> groups = new java.util.ArrayList<>();
    for (Object[] r : grouped(null)) {
      groups.add(new PaymentListSummary.Group((String) r[0], (String) r[1], (String) r[2],
          (String) r[3], (String) r[4], (BigDecimal) r[5], ((Number) r[6]).longValue()));
    }
    JSONObject summary = PaymentListSummary.build(groups, null,
        new com.etendoerp.go.schemaforge.NeoCurrencyTotals((a, b) -> null));
    assertEquals(nonVoid, summary.getLong("rowCount"));
  }

  // -------------------------------------------------------------------------
  // distinctValues: the values a filter dropdown offers over the filtered view
  // -------------------------------------------------------------------------

  private static final String STATUS = "e.status";

  private static List<Object> distinct(Map<String, String> params, String projection,
      String extraPredicate, Map<String, Object> extraParams, int first, int max) {
    NeoContext ctx = NeoContext.builder().specName("payment-in").entityName("finPayment")
        .httpMethod("GET").endpointType(NeoEndpointType.CRUD).build();
    NeoListAggregateQuery.remember(ctx, params);
    return NeoListAggregateQuery.of(ctx).distinctValues(projection, extraPredicate, extraParams,
        first, max);
  }

  private static List<Object> distinctStatuses(String criteria) {
    return distinct(listParams(criteria), STATUS, null, null, 0, 100);
  }

  private static List<Object> sorted(Collection<String> values) {
    return new ArrayList<Object>(new TreeSet<>(values));
  }

  private static Set<String> statusesOf(Collection<FIN_Payment> payments) {
    Set<String> out = new TreeSet<>();
    for (FIN_Payment p : payments) {
      out.add(p.getStatus());
    }
    return out;
  }

  private static String or(String... criteria) throws Exception {
    JSONArray arr = new JSONArray();
    for (String c : criteria) {
      arr.put(new JSONObject(c));
    }
    return new JSONObject().put("operator", "or").put("criteria", arr).toString();
  }

  /**
   * The distinct-values cases read the receipts the local database already holds and cannot create
   * their own (a payment needs a business partner, financial account and document type). When the
   * seed data lacks what a case needs, the case is skipped, never failed.
   */
  private static List<FIN_Payment> readableReceiptsOrSkip() {
    List<FIN_Payment> all = receipts();
    assumeTrue("skipped: the test client holds no receipt to read distinct values from",
        !all.isEmpty());
    return all;
  }

  /** Two statuses carried by the readable receipts, in the order the data presents them. */
  private static List<String> twoReceiptStatuses(List<FIN_Payment> all) {
    List<String> statuses = new ArrayList<>(statusesOf(all));
    assumeTrue("skipped: the test client needs receipts in at least two statuses",
        statuses.size() >= 2);
    return statuses;
  }

  @Test
  public void distinctValuesWithoutCriteriaAreTheStatusesOfEveryReadableReceipt() {
    List<FIN_Payment> all = readableReceiptsOrSkip();

    assertEquals(sorted(statusesOf(all)), distinctStatuses(null));
  }

  @Test
  public void equalsCriterionNarrowsTheValuesToThatOne() throws Exception {
    String status = twoReceiptStatuses(readableReceiptsOrSkip()).get(0);

    assertEquals(java.util.Collections.singletonList(status),
        distinctStatuses(and(criterion("status", "equals", status))));
  }

  @Test
  public void aCriterionMatchingNothingGivesAnEmptyPage() throws Exception {
    assertTrue(distinctStatuses(and(criterion("status", "equals", "NOPE"))).isEmpty());
  }

  @Test
  public void notEqualCriterionDropsThatValueAndKeepsTheRest() throws Exception {
    List<FIN_Payment> all = readableReceiptsOrSkip();
    String excluded = twoReceiptStatuses(all).get(0);
    Set<String> expected = statusesOf(all);
    expected.remove(excluded);

    assertEquals(sorted(expected), distinctStatuses(and(criterion("status", "notEqual", excluded))));
  }

  @Test
  public void orCombinationOfEqualsCriteriaOffersBothValues() throws Exception {
    List<FIN_Payment> all = readableReceiptsOrSkip();
    List<String> statuses = twoReceiptStatuses(all);
    String a = statuses.get(0);
    String b = statuses.get(1);

    String criteria = or(criterion("status", "equals", a), criterion("status", "equals", b));
    Set<String> expected = new TreeSet<>(java.util.Arrays.asList(a, b));

    assertEquals(sorted(expected), distinctStatuses(criteria));
  }

  @Test
  public void identifierCriterionForcesTheJoinAndStillReturnsDistinctOrderedValues()
      throws Exception {
    List<FIN_Payment> all = readableReceiptsOrSkip();
    String partnerName = all.get(0).getBusinessPartner().getName();

    List<FIN_Payment> matching = new ArrayList<>();
    for (FIN_Payment p : all) {
      if (p.getBusinessPartner().getIdentifier().toLowerCase()
          .contains(partnerName.toLowerCase())) {
        matching.add(p);
      }
    }
    assertFalse(matching.isEmpty());

    String criteria = and(criterion("businessPartner$_identifier", "iContains", partnerName));
    assertEquals(sorted(statusesOf(matching)), distinctStatuses(criteria));
  }

  @Test
  public void dateRangeCriterionOffersOnlyTheValuesOfRowsInTheRange() throws Exception {
    List<FIN_Payment> all = readableReceiptsOrSkip();
    Date latest = all.get(all.size() - 1).getPaymentDate();
    String from = day(latest);

    List<FIN_Payment> inRange = new ArrayList<>();
    for (FIN_Payment p : all) {
      if (!day(p.getPaymentDate()).equals(from) && p.getPaymentDate().before(latest)) {
        continue;
      }
      inRange.add(p);
    }
    Set<String> expected = statusesOf(inRange);
    assumeTrue("skipped: the latest day must carry fewer statuses than the whole view, or the range"
        + " would prove nothing", expected.size() < statusesOf(all).size());

    String criteria = and(criterion("paymentDate", "greaterOrEqual", from),
        criterion("paymentDate", "lessOrEqual", from));
    assertEquals(sorted(expected), distinctStatuses(criteria));
  }

  @Test
  public void subsetTabStyleOrBlockInsideAnAndCombinesWithAnotherCriterion() throws Exception {
    List<FIN_Payment> all = readableReceiptsOrSkip();
    // a is the status of the latest receipt, so the combination always has a row to find.
    FIN_Payment latest = all.get(all.size() - 1);
    String a = latest.getStatus();
    List<String> others = new ArrayList<>(statusesOf(all));
    others.remove(a);
    assumeTrue("skipped: the test client needs receipts in at least two statuses",
        !others.isEmpty());
    String b = others.get(0);
    String from = day(latest.getPaymentDate());

    // (status = a OR status = b) AND paymentDate >= latest day — the shape of a subset tab.
    String criteria = and(or(criterion("status", "equals", a), criterion("status", "equals", b)),
        criterion("paymentDate", "greaterOrEqual", from));

    List<FIN_Payment> matching = new ArrayList<>();
    for (FIN_Payment p : all) {
      boolean statusOk = a.equals(p.getStatus()) || b.equals(p.getStatus());
      boolean dateOk = day(p.getPaymentDate()).compareTo(from) >= 0;
      if (statusOk && dateOk) {
        matching.add(p);
      }
    }
    assertFalse(matching.isEmpty());

    assertEquals(sorted(statusesOf(matching)), distinctStatuses(criteria));
  }

  @Test
  public void fkProjectionWithTheSearchPredicateOnTheIdentifierNarrowsTheOfferedPartners() {
    List<FIN_Payment> all = readableReceiptsOrSkip();
    Property partner = ModelProvider.getInstance().getEntity(FIN_Payment.ENTITY_NAME)
        .getProperty("businessPartner");
    String token = null;
    for (FIN_Payment p : all) {
      String name = p.getBusinessPartner().getName().toLowerCase();
      if (!name.contains("%") && !name.contains("_")) {
        token = name;
        break;
      }
    }
    assumeTrue("skipped: no receipt partner has a LIKE-safe name", token != null);

    Set<String> expected = new TreeSet<>();
    Set<String> everyPartner = new TreeSet<>();
    for (FIN_Payment p : all) {
      everyPartner.add(p.getBusinessPartner().getId());
      if (p.getBusinessPartner().getName().toLowerCase().contains(token)
          || p.getBusinessPartner().getSearchKey().toLowerCase().contains(token)) {
        expected.add(p.getBusinessPartner().getId());
      }
    }
    assertFalse(expected.isEmpty());
    assumeTrue("skipped: the search token matches every partner, so it would narrow nothing",
        expected.size() < everyPartner.size());

    String projection = NeoDistinctFetchSupport.buildDistinctProjection(partner,
        "businessPartner");
    String predicate = NeoDistinctFetchSupport.buildDistinctSearchPredicate(partner,
        "businessPartner", token);
    Map<String, Object> searchParams = Map.of("search", "%" + token + "%");

    List<Object> offered = distinct(listParams(null), projection, predicate, searchParams, 0, 500);

    assertEquals("e.businessPartner.id", projection);
    assertEquals(new TreeSet<Object>(expected), new TreeSet<Object>(offered));
    assertEquals("no duplicates: the projection is DISTINCT", offered.size(),
        new LinkedHashSet<Object>(offered).size());
  }

  @Test
  public void extraPredicateIsParenthesisedAndItsParamsMergeWithTheCriteriaParams()
      throws Exception {
    List<FIN_Payment> all = readableReceiptsOrSkip();
    Set<String> receiptStatuses = statusesOf(all);
    OBCriteria<FIN_Payment> everyPayment = OBDal.getInstance().createCriteria(FIN_Payment.class);
    everyPayment.add(org.hibernate.criterion.Restrictions.eq(FIN_Payment.PROPERTY_RECEIPT, false));
    String payOnlyStatus = null;
    for (FIN_Payment p : everyPayment.list()) {
      if (!receiptStatuses.contains(p.getStatus())) {
        payOnlyStatus = p.getStatus();
        break;
      }
    }
    assumeTrue("skipped: the test client needs a payment-out status no receipt carries",
        payOnlyStatus != null);
    String receiptStatus = all.get(0).getStatus();

    // The criterion binds its own named parameter; the extra predicate binds :a and :b. Unparenthesised,
    // "... and <not null> and e.status = :a or e.status = :b" would let the payment-out rows
    // through, escaping the receipt filter of the list.
    String criteria = and(criterion("status", "notEqual", "NOPE"));
    Map<String, Object> extra = new HashMap<>();
    extra.put("a", receiptStatus);
    extra.put("b", payOnlyStatus);

    List<Object> offered = distinct(listParams(criteria), STATUS,
        "e.status = :a or e.status = :b", extra, 0, 100);

    assertEquals(java.util.Collections.singletonList(receiptStatus), offered);
  }

  @Test
  public void aViewWithNoFilterAtAllUsesTheWhereBranchOfAnEmptyCoreClause() {
    Map<String, String> params = listParams(null);
    params.remove(JsonConstants.WHERE_AND_FILTER_CLAUSE);

    OBCriteria<FIN_Payment> every = OBDal.getInstance().createCriteria(FIN_Payment.class);
    List<Object> offered = distinct(params, STATUS, null, null, 0, 100);

    assertEquals(sorted(statusesOf(every.list())), offered);
  }

  @Test
  public void pagingWalksEveryDistinctValueExactlyOnce() {
    Map<String, String> params = listParams(null);
    String projection = "e.businessPartner.id";
    List<Object> all = distinct(params, projection, null, null, 0, 1000);
    assumeTrue("skipped: paging needs more than two partners among the receipts", all.size() > 2);

    List<Object> walked = new ArrayList<>();
    int pageSize = 2;
    for (int first = 0;; first += pageSize) {
      // the handler asks for pageSize + 1 rows to detect hasMore
      List<Object> page = distinct(params, projection, null, null, first, pageSize + 1);
      assertTrue(page.size() <= pageSize + 1);
      walked.addAll(page.subList(0, Math.min(pageSize, page.size())));
      if (page.size() <= pageSize) {
        break;
      }
    }

    assertEquals(all, walked);
    assertEquals(all.size(), new LinkedHashSet<Object>(walked).size());
  }

  @Test
  public void distinctValuesStayWithinTheReadableClientAndOrganizations() {
    List<FIN_Payment> readable = readableReceiptsOrSkip();
    Set<String> readableStatuses = statusesOf(readable);

    OBCriteria<FIN_Payment> unfiltered = OBDal.getInstance().createCriteria(FIN_Payment.class);
    unfiltered.setFilterOnReadableClients(false);
    unfiltered.setFilterOnReadableOrganization(false);
    unfiltered.add(org.hibernate.criterion.Restrictions.eq(FIN_Payment.PROPERTY_RECEIPT, true));
    Set<String> hidden = statusesOf(unfiltered.list());
    hidden.removeAll(readableStatuses);
    assumeTrue("skipped: the database must hold a receipt status of another client to prove the"
        + " filter", !hidden.isEmpty());

    List<Object> offered = distinctStatuses(null);

    assertEquals(sorted(readableStatuses), offered);
    for (String h : hidden) {
      assertFalse("a status of a non-readable client leaked: " + h, offered.contains(h));
    }
  }

  // -------------------------------------------------------------------------
  // The handler's own path: NeoCrudHandler#handleDistinctFetch builds its params through
  // NeoDistinctFetchSupport#toDistinctFilterParams and runs them as real HQL. NeoCrudHandler is package-private and in
  // another package, so it is driven by reflection, as NeoCrudHandlerTest does.
  // -------------------------------------------------------------------------

  /** AD_TAB_ID of the System Administrator "Role" tab: its where is {@code e.client.id = @#ad_client_id@}. */
  private static final String ROLE_TAB = "119";

  /** Runs the handler's {@code ?_distinct=<field>} fetch and returns its response, unchecked. */
  private static NeoResponse handlerDistinctResponse(String tabId, String entityName, String field,
      Map<String, String> extraQueryParams) throws Exception {
    Tab tab = OBDal.getInstance().get(Tab.class, tabId);
    assumeTrue("skipped: tab " + tabId + " is not in this database", tab != null);

    Map<String, String> query = new HashMap<>();
    query.put(JsonConstants.DISTINCT_PARAMETER, field);
    query.put(JsonConstants.STARTROW_PARAMETER, "0");
    query.put(JsonConstants.ENDROW_PARAMETER, "199");
    query.putAll(extraQueryParams);
    NeoContext ctx = NeoContext.builder().specName("distinct-it").entityName(entityName)
        .httpMethod("GET").endpointType(NeoEndpointType.CRUD).queryParams(query).adTab(tab).build();

    Class<?> handlerClass = Class.forName("com.etendoerp.go.schemaforge.NeoCrudHandler");
    Constructor<?> ctor = handlerClass.getDeclaredConstructor(NeoServlet.class);
    ctor.setAccessible(true);
    Object handler = ctor.newInstance(Mockito.mock(NeoServlet.class));
    Method fetch = handlerClass.getDeclaredMethod("handleDistinctFetch", Tab.class, Map.class,
        NeoContext.class);
    fetch.setAccessible(true);
    // The servlet runs the handler in admin mode (the tab's table name is not directly readable).
    OBContext.setAdminMode(true);
    try {
      return (NeoResponse) fetch.invoke(handler, tab, query, ctx);
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /** The {@code response.data[].id}s of a successful distinct fetch, in the order offered. */
  private static List<String> offeredIds(NeoResponse response) throws Exception {
    assertEquals("the fetch must succeed, body: " + response.getBody(), 200,
        response.getHttpStatus());
    JSONArray data = response.getBody().getJSONObject("response").getJSONArray("data");
    List<String> ids = new ArrayList<>();
    for (int i = 0; i < data.length(); i++) {
      ids.add(data.getJSONObject(i).getString("id"));
    }
    return ids;
  }

  /** Runs the handler's {@code ?_distinct=<field>} fetch and returns the offered {@code id}s. */
  private static Set<String> handlerDistinctIds(String tabId, String entityName, String field,
      Map<String, String> extraQueryParams) throws Exception {
    return new TreeSet<>(offeredIds(handlerDistinctResponse(tabId, entityName, field,
        extraQueryParams)));
  }

  @Test
  public void handlerDistinctFetchFiltersByATabWhereWithASessionTokenLikeTheList() throws Exception {
    String sessionClient = OBContext.getOBContext().getCurrentClient().getId();
    OBCriteria<Role> roles = OBDal.getInstance().createCriteria(Role.class);
    Set<String> inSessionClient = new TreeSet<>();
    Set<String> inOtherReadableClients = new TreeSet<>();
    for (Role r : roles.list()) {
      (sessionClient.equals(r.getClient().getId()) ? inSessionClient : inOtherReadableClients)
          .add(r.getName());
    }
    inOtherReadableClients.removeAll(inSessionClient);
    assumeTrue("skipped: the session client has no role", !inSessionClient.isEmpty());
    assumeTrue("skipped: needs a role name that only another readable client owns, or the token"
        + " filter would be indistinguishable from no filter", !inOtherReadableClients.isEmpty());

    // The servlet's request carries the session the @#token@ resolves from; there is none here, so
    // the one value the tab where needs is supplied through the request context.
    VariablesSecureApp vars = Mockito.mock(VariablesSecureApp.class);
    Mockito.when(vars.getSessionValue(Mockito.anyString())).thenAnswer(
        inv -> "#AD_CLIENT_ID".equalsIgnoreCase(inv.getArgument(0)) ? sessionClient : "");
    Mockito.when(vars.getSessionValue(Mockito.anyString(), Mockito.anyString())).thenAnswer(
        inv -> "#AD_CLIENT_ID".equalsIgnoreCase(inv.getArgument(0)) ? sessionClient : "");
    RequestContext.get().setVariableSecureApp(vars);
    Set<String> offered;
    try {
      offered = handlerDistinctIds(ROLE_TAB, "ADRole", "name", java.util.Collections.emptyMap());
    } finally {
      RequestContext.get().setVariableSecureApp(null);
    }

    // The tab where is "e.client.id = @#ad_client_id@": the old path dropped a where holding an
    // @token@ and offered the names of every readable client.
    assertEquals(inSessionClient, offered);
    for (String other : inOtherReadableClients) {
      assertFalse("a name outside the session client leaked: " + other, offered.contains(other));
    }
  }

  @Test
  public void handlerDistinctFetchWithAnIdStillAppliesTheCustomizationReadPredicate()
      throws Exception {
    List<FIN_Payment> all = readableReceiptsOrSkip();
    List<String> statuses = twoReceiptStatuses(all);
    String kept = statuses.get(0);
    String excluded = statuses.get(1);
    Map<String, String> withId = Map.of("id", all.get(0).getId());

    // Baseline: with an id and no predicate the fetch is the plain list of values.
    assertEquals(statusesOf(all), handlerDistinctIds(PAYMENT_IN_TAB, "finPayment", "status", withId));

    // The seam: NeoReadPredicates.resolve is what asks the entity's customization. Registering a
    // real customization needs an @NeoExtension class or an ETGO_SF_ENTITY qualifier bean, i.e.
    // production-side wiring, so the predicate is injected at that single method and everything
    // else (the id handling, the params, the HQL) runs for real.
    try (MockedStatic<NeoReadPredicates> predicates = Mockito.mockStatic(NeoReadPredicates.class,
        Mockito.CALLS_REAL_METHODS)) {
      predicates.when(() -> NeoReadPredicates.resolve(Mockito.any(), Mockito.any()))
          .thenReturn("e.status = '" + kept + "'");

      Set<String> offered = handlerDistinctIds(PAYMENT_IN_TAB, "finPayment", "status", withId);

      assertEquals(java.util.Collections.singleton(kept), offered);
      assertFalse("a value the predicate excludes was offered: " + excluded,
          offered.contains(excluded));
    }
  }

  // -------------------------------------------------------------------------
  // The grid criteria as the real list sends it (a JSON ARRAY of criteria) through the handler:
  // dot-path / $ fields over a foreign key, an OR block, and boolean Y/N values.
  // -------------------------------------------------------------------------

  private static String array(String... items) throws Exception {
    JSONArray arr = new JSONArray();
    for (String i : items) {
      arr.put(new JSONObject(i));
    }
    return arr.toString();
  }

  private static String boolCriterion(String field, String operator, Object value)
      throws Exception {
    return new JSONObject().put("fieldName", field).put("operator", operator).put("value", value)
        .toString();
  }

  /** Statuses the handler offers for {@code criteria} over the payment-in tab. */
  private static List<String> handlerStatuses(String criteria) throws Exception {
    return offeredIds(handlerDistinctResponse(PAYMENT_IN_TAB, "finPayment", "status",
        Map.of("criteria", criteria)));
  }

  @Test
  public void anOrBlockOverForeignKeyDotPathsComposesWithBooleanDotPathCriteria() throws Exception {
    List<FIN_Payment> all = readableReceiptsOrSkip();
    assumeTrue("skipped: the receipts need a status to exclude and one to keep",
        statusesOf(all).size() >= 2);
    String excluded = all.get(all.size() - 1).getStatus();

    // The "Factura rectificativa" shape: boolean FK dot-path equals/notEqual AND an OR block of two
    // dot-path conditions (one boolean, one string). The three paths force the joins.
    String orBlock = new JSONObject().put("_constructor", "AdvancedCriteria")
        .put("operator", "or").put("criteria", new JSONArray()
            .put(new JSONObject(boolCriterion("businessPartner$customer", "equals", false)))
            .put(new JSONObject(boolCriterion("documentType$documentCategory", "equals", "ARR"))))
        .toString();
    String criteria = array(boolCriterion("businessPartner$customer", "equals", true), orBlock,
        boolCriterion("status", "notEqual", excluded));

    Set<String> expected = new TreeSet<>();
    for (FIN_Payment p : all) {
      boolean customer = Boolean.TRUE.equals(p.getBusinessPartner().isCustomer());
      boolean category = p.getDocumentType() != null
          && "ARR".equals(p.getDocumentType().getDocumentCategory());
      if (customer && (!customer || category) && !excluded.equals(p.getStatus())) {
        expected.add(p.getStatus());
      }
    }

    assertEquals(new ArrayList<>(expected), handlerStatuses(criteria));
  }

  @Test
  public void anOrBlockWhoseBranchesMatchNothingOffersNoValues() throws Exception {
    readableReceiptsOrSkip();
    String orBlock = new JSONObject().put("_constructor", "AdvancedCriteria")
        .put("operator", "or").put("criteria", new JSONArray()
            .put(new JSONObject(boolCriterion("businessPartner$customer", "notEqual", true)))
            .put(new JSONObject(boolCriterion("documentType$documentCategory", "equals", "NOPE"))))
        .toString();

    // Every receipt's partner is a customer and no document category is NOPE.
    List<FIN_Payment> matching = new ArrayList<>();
    for (FIN_Payment p : receipts()) {
      if (!Boolean.TRUE.equals(p.getBusinessPartner().isCustomer())
          || "NOPE".equals(p.getDocumentType().getDocumentCategory())) {
        matching.add(p);
      }
    }
    assumeTrue("skipped: the receipts hold a non-customer partner, so the OR would match",
        matching.isEmpty());

    assertTrue(handlerStatuses(array(orBlock)).isEmpty());
  }

  /** The distinct {@code processed} labels of the receipts whose flag equals {@code flag}. */
  private static List<String> processedLabels(List<FIN_Payment> all, boolean flag) {
    Set<String> out = new TreeSet<>();
    for (FIN_Payment p : all) {
      if (Boolean.TRUE.equals(p.isProcessed()) == flag) {
        out.add(String.valueOf(flag));
      }
    }
    return new ArrayList<>(out);
  }

  @Test
  public void booleanYAndNCriteriaBehaveLikeTrueAndFalseThroughTheHandler() throws Exception {
    List<FIN_Payment> all = readableReceiptsOrSkip();

    // _distinct of the boolean itself, narrowed by a criterion on the same boolean. Core would read
    // "Y" as Boolean.valueOf("Y") == false, so a missing normalization inverts these results.
    for (Object[] c : new Object[][] { { "Y", true }, { "N", false } }) {
      List<String> viaYn = offeredIds(handlerDistinctResponse(PAYMENT_IN_TAB, "finPayment",
          "processed", Map.of("criteria", array(boolCriterion("processed", "equals", c[0])))));
      List<String> viaBoolean = offeredIds(handlerDistinctResponse(PAYMENT_IN_TAB, "finPayment",
          "processed", Map.of("criteria", array(boolCriterion("processed", "equals", c[1])))));

      assertEquals("Y/N must act as true/false for " + c[0], viaBoolean, viaYn);
      assertEquals(processedLabels(all, (Boolean) c[1]), viaYn);
    }

    // The same Y/N on another boolean narrows the statuses like the true/false does.
    for (Object[] c : new Object[][] { { "Y", true }, { "N", false } }) {
      Set<String> expected = new TreeSet<>();
      for (FIN_Payment p : all) {
        if (Boolean.TRUE.equals(p.isProcessed()) == (Boolean) c[1]) {
          expected.add(p.getStatus());
        }
      }
      assertEquals(new ArrayList<>(expected), offeredIds(handlerDistinctResponse(PAYMENT_IN_TAB,
          "finPayment", "status", Map.of("criteria", array(boolCriterion("processed", "equals",
              c[0]))))));
    }
  }

  @Test
  public void distinctSearchOverABooleanRunsTheCastAndNarrowsTheOfferedValues() throws Exception {
    List<FIN_Payment> all = readableReceiptsOrSkip();
    List<String> unfiltered = offeredIds(handlerDistinctResponse(PAYMENT_IN_TAB, "finPayment",
        "processed", Map.of()));
    assertEquals(processedLabels(all, true).size() + processedLabels(all, false).size(),
        unfiltered.size());

    // The search predicate is LOWER(CAST(e.processed AS string)) LIKE :search. The cast must run
    // on a boolean column (a 200, never a 500) and only ever narrow what is offered.
    for (String search : new String[] { "y", "n", "true", "zzz-no-such-label" }) {
      List<String> offered = offeredIds(handlerDistinctResponse(PAYMENT_IN_TAB, "finPayment",
          "processed", Map.of("_distinctSearch", search)));
      assertTrue("search '" + search + "' must offer a subset of " + unfiltered,
          unfiltered.containsAll(offered));
    }
    assertTrue(offeredIds(handlerDistinctResponse(PAYMENT_IN_TAB, "finPayment", "processed",
        Map.of("_distinctSearch", "zzz-no-such-label"))).isEmpty());
  }

  // -------------------------------------------------------------------------
  // Child tab: parentId + a tab where holding a parent token
  // -------------------------------------------------------------------------

  /** AD_TAB_ID of the Business Partner window's "Partner Orders" child tab. */
  private static final String PARTNER_ORDERS_TAB = "551";

  @Test
  public void aChildTabDistinctFetchIsRestrictedToTheParentThroughTheTabWhere() throws Exception {
    OBContext.setAdminMode(true);
    Map<String, Set<String>> statusesByPartner = new HashMap<>();
    Set<String> allStatuses = new TreeSet<>();
    try {
      List<?> rows = OBDal.getInstance().getSession().createQuery(
          "select e.businessPartner.id, e.documentStatus from Order e").list();
      for (Object row : rows) {
        Object[] r = (Object[]) row;
        statusesByPartner.computeIfAbsent((String) r[0], k -> new TreeSet<>()).add((String) r[1]);
        allStatuses.add((String) r[1]);
      }
    } finally {
      OBContext.restorePreviousMode();
    }
    String partner = null;
    for (Map.Entry<String, Set<String>> e : statusesByPartner.entrySet()) {
      if (e.getValue().size() < allStatuses.size()) {
        partner = e.getKey();
        break;
      }
    }
    assumeTrue("skipped: needs a partner whose orders carry fewer statuses than the whole table, "
        + "or the parent restriction would be indistinguishable from none", partner != null);

    Set<String> offered = handlerDistinctIds(PARTNER_ORDERS_TAB, "order", "documentStatus",
        Map.of("parentId", partner));

    assertEquals(statusesByPartner.get(partner), offered);
  }

  // -------------------------------------------------------------------------
  // Page size
  // -------------------------------------------------------------------------

  @Test
  public void aRowWindowAboveTheCapIsCappedAndReportsMoreValues() throws Exception {
    OBContext.setAdminMode(true);
    long distinctDocumentNos;
    try {
      distinctDocumentNos = ((Number) OBDal.getInstance().getSession().createQuery(
          "select count(distinct e.documentNo) from Order e where e.salesTransaction=true"
              + " and e.transactionDocument.return=false"
              + " and e.transactionDocument.sOSubType not like 'OB'").uniqueResult()).longValue();
    } finally {
      OBContext.restorePreviousMode();
    }
    assumeTrue("skipped: needs more than 200 distinct sales order numbers to prove the cap, found "
        + distinctDocumentNos, distinctDocumentNos > 200);

    Map<String, String> window = new HashMap<>();
    window.put(JsonConstants.STARTROW_PARAMETER, "0");
    window.put(JsonConstants.ENDROW_PARAMETER, "999");
    NeoResponse response = handlerDistinctResponse("186", "order", "documentNo", window);

    List<String> ids = offeredIds(response);
    JSONObject payload = response.getBody().getJSONObject("response");
    assertEquals("a 1000-row window is capped at 200", 200, ids.size());
    assertEquals(199, payload.getInt("endRow"));
    assertTrue("more values exist beyond the capped page", payload.getBoolean("hasMore"));
    assertEquals("no duplicates", 200, new LinkedHashSet<>(ids).size());
  }
}
