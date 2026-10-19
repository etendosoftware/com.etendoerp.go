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
import static org.junit.Assert.assertTrue;

import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.financialmgmt.payment.FIN_Payment;
import org.openbravo.service.json.JsonConstants;
import org.openbravo.base.weld.test.WeldBaseTest;

import com.etendoerp.go.schemaforge.NeoContext;
import com.etendoerp.go.schemaforge.NeoEndpointType;
import com.etendoerp.go.schemaforge.NeoListAggregateQuery;

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
}
