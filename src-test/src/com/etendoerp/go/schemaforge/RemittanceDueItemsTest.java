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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.Session;
import org.hibernate.query.Query;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.security.OrganizationStructureProvider;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.currency.Currency;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.financialmgmt.payment.FIN_FinancialAccount;
import org.openbravo.model.financialmgmt.payment.FIN_PaymentMethod;
import org.openbravo.model.financialmgmt.payment.FinAccPaymentMethod;
import org.openbravo.module.remittance.Remittance;
import org.openbravo.module.remittance.RemittanceLine;
import org.openbravo.module.remittance.RemittanceType;

import com.etendoerp.go.schemaforge.util.NeoActionContract;

/**
 * Unit tests for {@link RemittanceDueItems}, «Seleccionar desde factura»: the candidate rules of
 * the invoice due amounts a remittance can take, the paged list (with the due status and what the
 * remittance already takes of each due), the selection through Classic's
 * {@code REMSelectInvoicesAndOrders}, and the sync that adds, changes and removes dues in one
 * transaction.
 *
 * <p>The DAL is mocked: every HQL query is recorded with the parameters it is bound with and
 * answered from the fixtures of the test, and {@link NeoProcessService#executeObuiappClass} stands
 * for the Classic handler. What is asserted for the list filters is therefore the rule as the
 * query states it (clauses and bound values: the organization tree, the day after the due date, the
 * account currency, the direction); that the database applies it to real invoices is a property of
 * the query on a real session.
 *
 * <p>A class of its own: {@code RemittanceDueItems} is a separate production class with its own
 * rules; {@code RemittanceHeaderHandlerTest} pins only how the header routes the actions to it.
 *
 * @covers com.etendoerp.go.schemaforge.RemittanceDueItems
 * @covers com.etendoerp.go.schemaforge.RemittanceDueSelection
 */
class RemittanceDueItemsTest {

  private static final String CLIENT_ID = "CLIENT-1";
  private static final String ORG_ID = "ORG-1";
  private static final String REM_ID = "REM-1";
  private static final String ACC_ID = "ACC-1";
  private static final String METHOD_ID = "PM-1";
  private static final String EUR_ID = "EUR-ID";
  private static final LocalDate DUE_DATE = LocalDate.of(2026, 11, 30);
  private static final Set<String> ORG_TREE = Set.of(ORG_ID, "ORG-CHILD");

  /** One {@code createQuery} call: its HQL and the parameters it was bound with. */
  private static final class Recorded {
    final String hql;
    final Map<String, Object> params = new HashMap<>();
    Integer firstResult;
    Integer maxResults;

    Recorded(String hql) {
      this.hql = hql;
    }
  }

  private MockedStatic<OBDal> dalStatic;
  private MockedStatic<OBContext> contextStatic;
  private MockedStatic<NeoProcessService> processStatic;
  private OBDal dal;
  private final List<Recorded> queries = new ArrayList<>();

  // Fixtures the queries answer.
  private FinAccPaymentMethod link;
  private List<Object[]> candidateRows = new ArrayList<>();
  private Long candidateTotal = 0L;
  /** The lines of this remittance: {schedule id, line id, amount}, in line order. */
  private final List<Object[]> includedLines = new ArrayList<>();
  private final List<Long> lineCounts = new ArrayList<>();
  private NeoResponse classicAnswer;
  private JSONObject classicParams;

  @BeforeEach
  void setUp() {
    dal = mock(OBDal.class);
    Session session = mock(Session.class);
    when(dal.getSession()).thenReturn(session);
    when(session.createQuery(anyString(), any(Class.class))).thenAnswer(
        inv -> recordingQuery(inv.getArgument(0)));
    OrganizationStructureProvider orgs = mock(OrganizationStructureProvider.class);
    when(orgs.getChildTree(ORG_ID, true)).thenReturn(ORG_TREE);
    OBContext obContext = mock(OBContext.class);
    when(obContext.getOrganizationStructureProvider()).thenReturn(orgs);
    link = link(true, false);

    dalStatic = mockStatic(OBDal.class);
    dalStatic.when(OBDal::getInstance).thenReturn(dal);
    contextStatic = mockStatic(OBContext.class);
    contextStatic.when(OBContext::getOBContext).thenReturn(obContext);
    processStatic = mockStatic(NeoProcessService.class);
    processStatic.when(() -> NeoProcessService.executeObuiappClass(anyString(), anyString(),
        any(JSONObject.class))).thenAnswer(inv -> {
          classicParams = inv.getArgument(2);
          return classicAnswer;
        });
  }

  @AfterEach
  void tearDown() {
    processStatic.close();
    contextStatic.close();
    dalStatic.close();
    Mockito.framework().clearInlineMocks();
  }

  // ---------------------------------------------------------------- contracts

  @Test
  void theThreeActionsAreDeclaredWithTheirParameters() {
    Map<String, NeoActionContract> contracts = new java.util.LinkedHashMap<>();
    RemittanceDueItems.addContracts(contracts);

    assertEquals(List.of("availableDueItems", "selectDueItems", "syncDueItems"),
        new ArrayList<>(contracts.keySet()));
    assertFalse(contracts.get("availableDueItems").isMutating());
    assertTrue(contracts.get("selectDueItems").isMutating());
    assertTrue(contracts.get("syncDueItems").isMutating());
    List<String> syncParams = new ArrayList<>();
    contracts.get("syncDueItems").getParams().forEach(p -> syncParams.add(p.getName()));
    assertEquals(List.of("items", "remove"), syncParams);
    assertTrue(RemittanceDueItems.serves("syncDueItems"));
    assertTrue(RemittanceDueItems.isWrite("selectDueItems"));
    assertTrue(RemittanceDueItems.isWrite("syncDueItems"));
    assertFalse(RemittanceDueItems.isWrite("availableDueItems"), "the list is a read");
    assertFalse(RemittanceDueItems.isWrite(null));
    List<String> readParams = new ArrayList<>();
    contracts.get("availableDueItems").getParams().forEach(p -> readParams.add(p.getName()));
    assertEquals(List.of("search", "page", "pageSize"), readParams);
    assertEquals("ids", contracts.get("selectDueItems").getParams().get(0).getName());
    assertTrue(RemittanceDueItems.serves("availableDueItems"));
    assertTrue(RemittanceDueItems.serves("selectDueItems"));
    assertFalse(RemittanceDueItems.serves("process"));
    assertFalse(RemittanceDueItems.serves(null));
  }

  // ---------------------------------------------------------------- candidate rules

  @Test
  void theCandidateQueryStatesEveryRule() throws Exception {
    RemittanceDueItems.available(remittance(), new JSONObject());

    Recorded rows = rowsQuery();
    String hql = rows.hql;
    assertTrue(hql.contains("join psd.invoicePaymentSchedule as ips"),
        "inner join on the invoice schedule: order schedules never qualify");
    assertTrue(hql.contains("join ips.invoice as inv"), hql);
    assertTrue(hql.contains("join psd.businessPartner as bp"), "a business partner is required");
    assertTrue(hql.contains("psd.paymentDetails is null"), "paid details are excluded");
    assertTrue(hql.contains("psd.canceled = false and psd.invoicePaid = false"),
        "cancelled and invoice-paid details are excluded");
    assertTrue(hql.contains("psd.organization.id in (:orgs)"), hql);
    assertTrue(hql.contains("(ips.expectedDate < :dueDateLimit or exists (select 1 from "
        + "REM_RemittanceLine as mine where mine.paymentScheduleDetail.id = psd.id"
        + " and mine.remittance.id = :remittance))"),
        "a due already in this remittance stays listed past the remittance due date: " + hql);
    assertTrue(hql.contains("cur.id = :currency"), "the invoice currency is the account's");
    assertTrue(hql.contains("other.remittance.id <> :remittance"
        + " and other.remittance.processed = false"),
        "a schedule in another unprocessed remittance is excluded");
    assertTrue(hql.contains("order by ips.expectedDate, inv.documentNo"), hql);

    assertEquals(CLIENT_ID, rows.params.get("client"));
    assertEquals(REM_ID, rows.params.get("remittance"));
    assertEquals(ORG_TREE, rows.params.get("orgs"), "the child tree of the remittance org");
    assertEquals(EUR_ID, rows.params.get("currency"));
    assertEquals(date(DUE_DATE.plusDays(1)), rows.params.get("dueDateLimit"),
        "strictly before the day after: the due date itself is included, later is excluded");
  }

  @Test
  void aPayInLinkListsSalesInvoicesOnly() throws Exception {
    link = link(true, false);

    RemittanceDueItems.available(remittance(), new JSONObject());

    assertTrue(rowsQuery().hql.contains("inv.salesTransaction = :sales"));
    assertEquals(Boolean.TRUE, rowsQuery().params.get("sales"));
    assertEquals(Boolean.TRUE, countQuery().params.get("sales"));
  }

  @Test
  void aPayOutLinkListsPurchaseInvoicesOnly() throws Exception {
    link = link(false, true);

    RemittanceDueItems.available(remittance(), new JSONObject());

    assertEquals(Boolean.FALSE, rowsQuery().params.get("sales"));
  }

  @Test
  void aLinkAllowingBothDirectionsOrNoLinkDoesNotFilterTheDirection() throws Exception {
    for (FinAccPaymentMethod candidate : new FinAccPaymentMethod[] { link(true, true),
        link(false, false), null }) {
      link = candidate;
      queries.clear();

      RemittanceDueItems.available(remittance(), new JSONObject());

      assertFalse(rowsQuery().hql.contains("inv.salesTransaction = :sales"),
          String.valueOf(candidate));
      assertFalse(rowsQuery().params.containsKey("sales"));
    }
  }

  @Test
  void theDirectionComesFromTheLinkOfTheAccountToTheTypeMethod() throws Exception {
    RemittanceDueItems.available(remittance(), new JSONObject());

    Recorded linkQuery = queries.stream().filter(q -> q.hql.contains(FinAccPaymentMethod.ENTITY_NAME))
        .findFirst().orElseThrow(() -> new AssertionError("no link query"));
    assertEquals(ACC_ID, linkQuery.params.get("account"));
    assertEquals(METHOD_ID, linkQuery.params.get("method"));
    assertEquals(Integer.valueOf(1), linkQuery.maxResults);
  }

  @Test
  void aRemittanceWithoutTypeAccountCurrencyOrDateIsRefused422() throws Exception {
    List<Remittance> incomplete = new ArrayList<>();
    Remittance noType = remittance();
    when(noType.getRemittanceType()).thenReturn(null);
    incomplete.add(noType);
    Remittance noAccount = remittance();
    when(noAccount.getFinancialAccount()).thenReturn(null);
    incomplete.add(noAccount);
    Remittance noDate = remittance();
    when(noDate.getDueDate()).thenReturn(null);
    incomplete.add(noDate);
    Remittance noCurrency = remittance();
    FIN_FinancialAccount accountWithoutCurrency = mock(FIN_FinancialAccount.class);
    when(noCurrency.getFinancialAccount()).thenReturn(accountWithoutCurrency);
    incomplete.add(noCurrency);

    for (Remittance remittance : incomplete) {
      NeoResponse listed = RemittanceDueItems.available(remittance, new JSONObject());
      NeoResponse selected = RemittanceDueItems.select(remittance,
          new JSONObject().put("ids", new JSONArray().put("IPS-1")));

      assertEquals(422, listed.getHttpStatus());
      assertEquals(RemittanceDueItems.MSG_TYPE_OR_ACCOUNT_MISSING, messageKey(listed));
      assertEquals(RemittanceDueItems.MSG_TYPE_OR_ACCOUNT_MISSING, messageKey(selected));
    }
    assertTrue(queries.isEmpty(), "no candidate query runs");
  }

  // ---------------------------------------------------------------- list

  @Test
  void theListMapsEachScheduleAndFlagsOnlyTheOnesAlreadyInThisRemittance() throws Exception {
    candidateRows.add(new Object[] { "IPS-1", "FV-001", "Cliente A", date(LocalDate.of(2026, 11,
        10)), new BigDecimal("120.50"), "EUR", "INV-1", Boolean.TRUE,
        date(LocalDate.of(2026, 10, 1)), new BigDecimal("242.00"), "BP-A" });
    candidateRows.add(row("IPS-2", "FV-002", "Cliente B", LocalDate.of(2026, 11, 30), "80"));
    candidateTotal = 7L;
    includedLines.add(new Object[] { "IPS-2", "LINE-1", new BigDecimal("30") });

    NeoResponse response = RemittanceDueItems.available(remittance(), new JSONObject());

    assertEquals(200, response.getHttpStatus());
    JSONArray items = response.getBody().getJSONArray("items");
    assertEquals(2, items.length());
    JSONObject first = items.getJSONObject(0);
    assertEquals("IPS-1", first.getString("id"));
    assertEquals("FV-001", first.getString("documentNo"));
    assertEquals("INV-1", first.getString("invoiceId"));
    assertTrue(first.getBoolean("salesTransaction"));
    assertFalse(first.has("docTypeName"), "the document type is not offered");
    assertEquals("2026-10-01", first.getString("invoiceDate"));
    assertEquals("Cliente A", first.getString("businessPartner"));
    assertEquals("BP-A", first.getString("businessPartnerId"));
    assertEquals("2026-11-10", first.getString("dueDate"));
    assertEquals(0, new BigDecimal("242.00").compareTo(new BigDecimal(first.get("grandTotalAmount")
        .toString())));
    assertEquals(0, new BigDecimal("120.50").compareTo(new BigDecimal(first.get("outstandingAmount")
        .toString())));
    assertEquals("EUR", first.getString("currency"));
    assertFalse(first.getBoolean("alreadyInRemittance"));
    assertFalse(first.has("lineAmount"), "only a due already in the remittance has a line amount");
    assertFalse(first.has("lineIds"));
    JSONObject second = items.getJSONObject(1);
    assertTrue(second.getBoolean("alreadyInRemittance"));
    assertEquals(0, new BigDecimal("30").compareTo(new BigDecimal(second.get("lineAmount")
        .toString())), "what this remittance takes, not the outstanding");
    assertEquals("LINE-1", second.getJSONArray("lineIds").getString(0));
    assertEquals(7L, response.getBody().getLong("total"), "the total counts every candidate");
    Recorded inRemittance = includedQuery();
    assertEquals(REM_ID, inRemittance.params.get("remittance"));
    assertTrue(inRemittance.hql.contains("order by rl.lineNo"), inRemittance.hql);
  }

  /**
   * Classic spreads a due over one line per open schedule detail: the item shows the sum of all
   * the lines of the due, and every line id in line order.
   */
  @Test
  void theLineAmountOfADueIsTheSumOfAllItsLines() throws Exception {
    candidateRows.add(row("IPS-1", "FV-001", "Cliente A", LocalDate.of(2026, 11, 10), "200"));
    includedLines.add(new Object[] { "IPS-1", "LINE-A", new BigDecimal("50.25") });
    includedLines.add(new Object[] { "IPS-9", "LINE-X", new BigDecimal("999") });
    includedLines.add(new Object[] { "IPS-1", "LINE-B", new BigDecimal("24.75") });

    JSONObject item = RemittanceDueItems.available(remittance(), new JSONObject()).getBody()
        .getJSONArray("items").getJSONObject(0);

    assertEquals(0, new BigDecimal("75.00").compareTo(new BigDecimal(item.get("lineAmount")
        .toString())));
    JSONArray lineIds = item.getJSONArray("lineIds");
    assertEquals(2, lineIds.length());
    assertEquals("LINE-A", lineIds.getString(0));
    assertEquals("LINE-B", lineIds.getString(1));
  }

  @Test
  void eachItemCarriesTheStatusOfItsDueDateAgainstToday() throws Exception {
    LocalDate today = LocalDate.now();
    candidateRows.add(row("IPS-1", "FV-001", "A", today.minusDays(1), "1"));
    candidateRows.add(row("IPS-2", "FV-002", "B", today, "1"));
    candidateRows.add(row("IPS-3", "FV-003", "C", today.plusDays(1), "1"));
    Object[] noDate = row("IPS-4", "FV-004", "D", today, "1");
    noDate[3] = null;
    noDate[8] = null;
    candidateRows.add(noDate);

    JSONArray items = RemittanceDueItems.available(remittance(), new JSONObject()).getBody()
        .getJSONArray("items");

    assertEquals("OVERDUE", items.getJSONObject(0).getString("dueStatus"));
    assertEquals("DUE_TODAY", items.getJSONObject(1).getString("dueStatus"));
    assertEquals("PENDING", items.getJSONObject(2).getString("dueStatus"));
    JSONObject undated = items.getJSONObject(3);
    assertEquals("PENDING", undated.getString("dueStatus"));
    assertTrue(undated.isNull("dueDate"));
    assertTrue(undated.isNull("invoiceDate"));
  }

  @Test
  void dueStatusComparesTheDayWithTodayAtItsBoundaries() {
    LocalDate today = LocalDate.of(2026, 11, 11);

    assertEquals(RemittanceDueItems.DUE_OVERDUE,
        RemittanceDueItems.dueStatus(LocalDate.of(2026, 11, 10), today));
    assertEquals(RemittanceDueItems.DUE_TODAY, RemittanceDueItems.dueStatus(today, today));
    assertEquals(RemittanceDueItems.DUE_PENDING,
        RemittanceDueItems.dueStatus(LocalDate.of(2026, 11, 12), today));
    assertEquals(RemittanceDueItems.DUE_OVERDUE,
        RemittanceDueItems.dueStatus(LocalDate.of(2025, 12, 31), today));
    assertEquals(RemittanceDueItems.DUE_PENDING, RemittanceDueItems.dueStatus(null, today),
        "no date: not overdue");
    assertEquals("OVERDUE", RemittanceDueItems.DUE_OVERDUE);
    assertEquals("DUE_TODAY", RemittanceDueItems.DUE_TODAY);
    assertEquals("PENDING", RemittanceDueItems.DUE_PENDING);
  }

  @Test
  void aNullTotalIsZero() throws Exception {
    candidateTotal = null;

    assertEquals(0L, RemittanceDueItems.available(remittance(), new JSONObject()).getBody()
        .getLong("total"));
  }

  @Test
  void theSearchMatchesTheInvoiceNumberOrTheBusinessPartnerCaseInsensitively() throws Exception {
    RemittanceDueItems.available(remittance(), new JSONObject().put("search", "  BBva "));

    for (Recorded query : List.of(rowsQuery(), countQuery())) {
      assertTrue(query.hql.contains(
          "(lower(inv.documentNo) like :search or lower(bp.name) like :search)"), query.hql);
      assertEquals("%bbva%", query.params.get("search"));
    }
  }

  @Test
  void aBlankSearchIsNoSearch() throws Exception {
    RemittanceDueItems.available(remittance(), new JSONObject().put("search", "   "));

    assertFalse(rowsQuery().hql.contains(":search"));
  }

  @Test
  void thePageSizeDefaultsTo50IsCappedAt200AndPagesStartAt1() throws Exception {
    Object[][] cases = {
        // page, pageSize, expected firstResult, expected maxResults
        { null, null, 0, 50 },
        { 3, 20, 40, 20 },
        { 1, 500, 0, 200 },
        { 0, 0, 0, 1 },
        { -2, 10, 0, 10 }, };
    for (Object[] c : cases) {
      queries.clear();
      JSONObject params = new JSONObject();
      if (c[0] != null) {
        params.put("page", c[0]);
      }
      if (c[1] != null) {
        params.put("pageSize", c[1]);
      }

      RemittanceDueItems.available(remittance(), params);

      assertEquals(c[2], rowsQuery().firstResult, java.util.Arrays.toString(c));
      assertEquals(c[3], rowsQuery().maxResults, java.util.Arrays.toString(c));
    }
  }

  @Test
  void queryStringParametersAreTypedAsTheContractDeclaresThem() throws Exception {
    Map<String, String> query = new HashMap<>();
    query.put("search", "BBVA");
    query.put("page", "3");
    query.put("pageSize", "abc");

    JSONObject params = RemittanceDueItems.paramsFromQuery(query);

    assertEquals("BBVA", params.getString("search"));
    assertEquals(3, params.get("page"));
    assertFalse(params.has("pageSize"), "not a number: left out");
    Map<String, String> blank = new HashMap<>();
    blank.put("search", " ");
    blank.put("page", "-1");
    assertEquals(0, RemittanceDueItems.paramsFromQuery(blank).length());
    assertEquals(0, RemittanceDueItems.paramsFromQuery(null).length());
  }

  // ---------------------------------------------------------------- select

  @Test
  void selectingNothingIsRefused422() throws Exception {
    for (JSONObject params : new JSONObject[] { new JSONObject(),
        new JSONObject().put("ids", new JSONArray()),
        new JSONObject().put("ids", new JSONArray().put(" ").put(JSONObject.NULL)) }) {
      NeoResponse response = RemittanceDueItems.select(remittance(), params);

      assertEquals(422, response.getHttpStatus());
      assertEquals(RemittanceDueItems.MSG_IDS_REQUIRED, messageKey(response));
    }
    processStatic.verifyNoInteractions();
  }

  @Test
  void anyIneligibleIdRefusesTheWholeSelectionAndNamesIt() throws Exception {
    candidateRows.add(row("IPS-1", "FV-001", "Cliente A", LocalDate.of(2026, 11, 10), "10"));

    NeoResponse response = RemittanceDueItems.select(remittance(),
        new JSONObject().put("ids", new JSONArray().put("IPS-1").put("IPS-GONE")));

    assertEquals(422, response.getHttpStatus());
    assertEquals(RemittanceDueItems.MSG_NOT_AVAILABLE, messageKey(response));
    JSONArray invalid = response.getBody().getJSONObject("error").getJSONArray("invalidIds");
    assertEquals(1, invalid.length());
    assertEquals("IPS-GONE", invalid.getString(0));
    processStatic.verifyNoInteractions();
    Recorded rows = rowsQuery();
    assertTrue(rows.hql.contains("ips.id in (:ids)"), "re-checked against the same rules");
    assertEquals(new LinkedHashSet<>(List.of("IPS-1", "IPS-GONE")), rows.params.get("ids"));
  }

  @Test
  void validIdsAreSelectedForTheirFullOutstandingAmountWithoutDeletingExistingLines()
      throws Exception {
    candidateRows.add(row("IPS-1", "FV-001", "Cliente A", LocalDate.of(2026, 11, 10), "120.50"));
    candidateRows.add(row("IPS-2", "FV-002", "Cliente B", LocalDate.of(2026, 11, 20), "80"));
    lineCounts.add(2L);
    lineCounts.add(4L);
    classicAnswer = viewMessage("success", "2 lines created");

    NeoResponse response = RemittanceDueItems.select(remittance(),
        new JSONObject().put("ids", new JSONArray().put("IPS-1").put("IPS-2")));

    assertEquals(200, response.getHttpStatus());
    assertEquals(2L, response.getBody().getLong("created"), "the difference of the line count");
    assertEquals("2 lines created", response.getBody().getString("message"));
    processStatic.verify(() -> NeoProcessService.executeObuiappClass(
        RemittanceDueItems.SELECT_HANDLER_CLASS, RemittanceDueItems.SELECT_PROCESS_ID,
        classicParams));
    assertEquals(REM_ID, classicParams.getString("inpRecordId"));
    assertEquals(RemittanceDueItems.HEADER_TAB_ID, classicParams.getString("inpTabId"));
    JSONObject grid = classicParams.getJSONObject(RemittanceDueItems.GRID_PARAMETER);
    assertFalse(grid.has("_allRows"), "without _allRows REM never deletes an existing line");
    JSONArray selection = grid.getJSONArray("_selection");
    assertEquals(2, selection.length());
    JSONObject first = selection.getJSONObject(0);
    assertEquals("IPS-1", first.getString("paymentScheduleID"));
    assertEquals("120.50", first.getString("realpayment"));
    assertEquals("120.50", first.getString("outstandingamount"));
    assertEquals("0", first.getString("rest"));
    assertEquals("80", selection.getJSONObject(1).getString("realpayment"));
  }

  @Test
  void aSuccessWithoutAMessageReportsTheCreatedCountAndNeverANegativeOne() throws Exception {
    candidateRows.add(row("IPS-1", "FV-001", "Cliente A", LocalDate.of(2026, 11, 10), "10"));
    lineCounts.add(3L);
    lineCounts.add(1L);
    classicAnswer = NeoResponse.ok(new JSONObject());

    NeoResponse response = RemittanceDueItems.select(remittance(),
        new JSONObject().put("ids", new JSONArray().put("IPS-1")));

    assertEquals(0L, response.getBody().getLong("created"));
    assertEquals("0", response.getBody().getString("message"));
  }

  @Test
  void aRefusalOfTheClassicProcessIsAnswered422WithItsText() throws Exception {
    candidateRows.add(row("IPS-1", "FV-001", "Cliente A", LocalDate.of(2026, 11, 10), "10"));
    lineCounts.add(1L);
    lineCounts.add(1L);
    classicAnswer = viewMessage("ERROR", "La factura ya está en otra remesa");

    NeoResponse response = RemittanceDueItems.select(remittance(),
        new JSONObject().put("ids", new JSONArray().put("IPS-1")));

    assertEquals(422, response.getHttpStatus());
    assertEquals(RemittanceDueItems.MSG_SELECTION_FAILED, messageKey(response));
    assertTrue(response.getBody().getJSONObject("error").getString("message")
        .contains("La factura ya está en otra remesa"));
  }

  /**
   * REM may have written lines before it refused: the transaction is rolled back
   * ({@code NeoErrorResponses.rollbackUnlessBatchOwned}, outside a batch) before the 422. A
   * successful selection, and a refusal made before calling REM, are never rolled back.
   */
  @Test
  void aRemRefusalRollsTheTransactionBackAndASuccessDoesNot() throws Exception {
    candidateRows.add(row("IPS-1", "FV-001", "Cliente A", LocalDate.of(2026, 11, 10), "10"));
    JSONObject select = new JSONObject().put("ids", new JSONArray().put("IPS-1"));
    lineCounts.add(1L);
    classicAnswer = viewMessage("ERROR", "refused");
    NeoResponse refused = RemittanceDueItems.select(remittance(), select);
    assertEquals(422, refused.getHttpStatus());
    assertEquals("ETGO_RemittanceDueItemsSelectionFailed", messageKey(refused),
        "the key the SPA maps to backendError.remittanceDueItemsSelectionFailed");
    verify(dal).rollbackAndClose();

    Mockito.clearInvocations(dal);
    lineCounts.add(1L);
    lineCounts.add(2L);
    classicAnswer = viewMessage("success", "ok");
    assertEquals(200, RemittanceDueItems.select(remittance(), select).getHttpStatus());
    assertEquals(422, RemittanceDueItems.select(remittance(), new JSONObject()
        .put("ids", new JSONArray().put("IPS-GONE"))).getHttpStatus());
    verify(dal, never()).rollbackAndClose();
  }

  @Test
  void failureTextReadsTheShapesTheClassicAnswerCanTake() throws Exception {
    assertEquals("No response", RemittanceDueSelection.failureText(null));
    assertEquals("No response", RemittanceDueSelection.failureText(new NeoResponse(200, null)));
    assertEquals("boom", RemittanceDueSelection.failureText(
        new NeoResponse(500, new JSONObject().put("message", "boom"))));
    assertEquals("Process failed", RemittanceDueSelection.failureText(
        new NeoResponse(500, new JSONObject())));
    assertEquals("bad", RemittanceDueSelection.failureText(viewMessage("error", "bad")));
    assertEquals("Process failed", RemittanceDueSelection.failureText(viewMessage("ERROR", "")));
    JSONObject processView = new JSONObject().put("responseActions", new JSONArray()
        .put(new JSONObject().put("showMsgInProcessView",
            new JSONObject().put("msgType", "error").put("msgText", "process view"))));
    assertEquals("process view",
        RemittanceDueSelection.failureText(new NeoResponse(200, processView)));
    assertNull(RemittanceDueSelection.failureText(viewMessage("success", "ok")));
    assertNull(RemittanceDueSelection.failureText(NeoResponse.ok(new JSONObject())));
  }

  @Test
  void idsOfTrimsDeduplicatesAndDropsBlanks() throws Exception {
    Set<String> ids = RemittanceDueItems.idsOf(
        new JSONArray().put(" A ").put("B").put("A").put("").put(JSONObject.NULL));

    assertEquals(new LinkedHashSet<>(List.of("A", "B")), ids);
    assertTrue(RemittanceDueItems.idsOf(null).isEmpty());
  }

  // ---------------------------------------------------------------- sync

  @Test
  void syncingNothingIsRefused422() throws Exception {
    for (JSONObject params : new JSONObject[] { new JSONObject(),
        new JSONObject().put("items", new JSONArray()).put("remove", new JSONArray()),
        new JSONObject().put("items", new JSONArray().put(new JSONObject().put("amount", 5))),
        new JSONObject().put("remove", new JSONArray().put(" ")) }) {
      NeoResponse response = RemittanceDueItems.sync(remittance(), params);

      assertEquals(422, response.getHttpStatus(), params.toString());
      assertEquals(RemittanceDueItems.MSG_IDS_REQUIRED, messageKey(response));
    }
    processStatic.verifyNoInteractions();
    verify(dal, never()).remove(any());
  }

  @Test
  void syncOnARemittanceWithoutTypeOrAccountIsRefused422() throws Exception {
    Remittance noType = remittance();
    when(noType.getRemittanceType()).thenReturn(null);

    NeoResponse response = RemittanceDueItems.sync(noType,
        new JSONObject().put("remove", new JSONArray().put("IPS-1")));

    assertEquals(422, response.getHttpStatus());
    assertEquals(RemittanceDueItems.MSG_TYPE_OR_ACCOUNT_MISSING, messageKey(response));
  }

  @Test
  void removingADueThatIsNotInTheRemittanceRefusesTheWholeSyncAndNamesIt() throws Exception {
    includedLines.add(new Object[] { "IPS-1", "LINE-1", new BigDecimal("10") });
    Remittance remittance = remittance();
    withLines(remittance, "LINE-1");

    NeoResponse response = RemittanceDueItems.sync(remittance,
        new JSONObject().put("remove", new JSONArray().put("IPS-1").put("IPS-9")));

    assertEquals(422, response.getHttpStatus());
    assertEquals("ETGO_RemittanceDueItemNotIncluded", messageKey(response));
    assertEquals(List.of("IPS-9"), invalidIds(response));
    verify(dal, never()).remove(any());
    assertEquals(1, remittance.getREMRemittanceLineList().size(), "nothing removed");
    processStatic.verifyNoInteractions();
  }

  @Test
  void aDueBothKeptAndRemovedIsRefusedNotIncluded() throws Exception {
    includedLines.add(new Object[] { "IPS-1", "LINE-1", new BigDecimal("10") });
    candidateRows.add(row("IPS-1", "FV-001", "A", LocalDate.of(2026, 11, 10), "100"));

    NeoResponse response = RemittanceDueItems.sync(remittance(), new JSONObject()
        .put("items", new JSONArray().put(item("IPS-1", 20)))
        .put("remove", new JSONArray().put("IPS-1")));

    assertEquals(422, response.getHttpStatus());
    assertEquals(RemittanceDueItems.MSG_NOT_INCLUDED, messageKey(response));
    assertEquals(List.of("IPS-1"), invalidIds(response));
    processStatic.verifyNoInteractions();
  }

  @Test
  void anItemThatIsNotACandidateRefusesTheWholeSyncAndNamesIt() throws Exception {
    candidateRows.add(row("IPS-1", "FV-001", "A", LocalDate.of(2026, 11, 10), "100"));

    NeoResponse response = RemittanceDueItems.sync(remittance(), new JSONObject()
        .put("items", new JSONArray().put(item("IPS-1", 20)).put(item("IPS-GONE", 5))));

    assertEquals(422, response.getHttpStatus());
    assertEquals(RemittanceDueItems.MSG_NOT_AVAILABLE, messageKey(response));
    assertEquals(List.of("IPS-GONE"), invalidIds(response));
    Recorded rows = rowsQuery();
    assertTrue(rows.hql.contains("ips.id in (:ids)"), "re-checked against the candidate rules");
    assertTrue(rows.hql.contains("psd.paymentDetails is null"), rows.hql);
    assertEquals(Set.of("IPS-1", "IPS-GONE"), rows.params.get("ids"));
    processStatic.verifyNoInteractions();
  }

  @Test
  void anAmountOutOfRangeIsRefused422WithTheMaxOfThatDue() throws Exception {
    candidateRows.add(row("IPS-1", "FV-001", "A", LocalDate.of(2026, 11, 10), "133.10"));
    candidateRows.add(row("IPS-2", "FV-002", "B", LocalDate.of(2026, 11, 12), "50"));

    NeoResponse response = RemittanceDueItems.sync(remittance(), new JSONObject()
        .put("items", new JSONArray().put(item("IPS-1", "133.11")).put(item("IPS-2", 10))));

    assertEquals(422, response.getHttpStatus());
    assertEquals(RemittanceLineAmount.MSG_INVALID, messageKey(response));
    JSONObject error = response.getBody().getJSONObject("error");
    assertEquals(0, new BigDecimal("133.10").compareTo(new BigDecimal(error.get("max")
        .toString())));
    assertEquals(List.of("IPS-1"), invalidIds(response));
    processStatic.verifyNoInteractions();
    verify(dal, never()).flush();
  }

  @Test
  void severalBadAmountsAreAllNamedWithoutASingleMax() throws Exception {
    candidateRows.add(row("IPS-1", "FV-001", "A", LocalDate.of(2026, 11, 10), "100"));
    candidateRows.add(row("IPS-2", "FV-002", "B", LocalDate.of(2026, 11, 12), "50"));
    candidateRows.add(row("IPS-3", "FV-003", "C", LocalDate.of(2026, 11, 13), "50"));

    NeoResponse response = RemittanceDueItems.sync(remittance(), new JSONObject()
        .put("items", new JSONArray().put(item("IPS-1", 0)).put(item("IPS-2", "abc"))
            .put(item("IPS-3", -5))));

    assertEquals(422, response.getHttpStatus());
    assertEquals(RemittanceLineAmount.MSG_INVALID, messageKey(response));
    assertFalse(response.getBody().getJSONObject("error").has("max"),
        "no single max for several dues");
    assertEquals(List.of("IPS-1", "IPS-2", "IPS-3"), invalidIds(response));
    processStatic.verifyNoInteractions();
  }

  @Test
  void aNewDueIsAddedThroughClassicWithAPartOfItsOutstanding() throws Exception {
    candidateRows.add(row("IPS-1", "FV-001", "A", LocalDate.of(2026, 11, 10), "133.10"));
    classicAnswer = viewMessage("success", "1 line created");

    NeoResponse response = RemittanceDueItems.sync(remittance(), new JSONObject()
        .put("items", new JSONArray().put(item("IPS-1", "50"))));

    assertEquals(200, response.getHttpStatus());
    JSONObject body = response.getBody();
    assertEquals(1, body.getInt("created"));
    assertEquals(0, body.getInt("updated"));
    assertEquals(0, body.getInt("removed"));
    assertEquals("1 line created", body.getString("message"));
    processStatic.verify(() -> NeoProcessService.executeObuiappClass(
        RemittanceDueItems.SELECT_HANDLER_CLASS, RemittanceDueItems.SELECT_PROCESS_ID,
        classicParams));
    assertEquals(REM_ID, classicParams.getString("inpRecordId"));
    assertEquals(RemittanceDueItems.HEADER_TAB_ID, classicParams.getString("inpTabId"));
    JSONObject grid = classicParams.getJSONObject(RemittanceDueItems.GRID_PARAMETER);
    assertFalse(grid.has("_allRows"), "without _allRows REM never deletes an existing line");
    JSONObject selected = grid.getJSONArray("_selection").getJSONObject(0);
    assertEquals("IPS-1", selected.getString("paymentScheduleID"));
    assertEquals("50", selected.getString("realpayment"), "the part asked for");
    assertEquals("133.10", selected.getString("outstandingamount"));
    assertEquals("0", selected.getString("rest"));
    verify(dal).flush();
    verify(dal, never()).remove(any());
  }

  @Test
  void aChangedAmountReplacesTheLinesOfTheDue() throws Exception {
    candidateRows.add(row("IPS-1", "FV-001", "A", LocalDate.of(2026, 11, 10), "200"));
    includedLines.add(new Object[] { "IPS-1", "LINE-A", new BigDecimal("50") });
    includedLines.add(new Object[] { "IPS-1", "LINE-B", new BigDecimal("25") });
    Remittance remittance = remittance();
    Map<String, RemittanceLine> lines = withLines(remittance, "LINE-A", "LINE-B", "LINE-OTHER");
    classicAnswer = viewMessage("success", "ok");

    NeoResponse response = RemittanceDueItems.sync(remittance, new JSONObject()
        .put("items", new JSONArray().put(item("IPS-1", 120))));

    assertEquals(200, response.getHttpStatus());
    assertEquals(0, response.getBody().getInt("created"));
    assertEquals(1, response.getBody().getInt("updated"));
    verify(dal).remove(lines.get("LINE-A"));
    verify(dal).remove(lines.get("LINE-B"));
    verify(dal, never()).remove(lines.get("LINE-OTHER"));
    assertEquals(List.of(lines.get("LINE-OTHER")), remittance.getREMRemittanceLineList(),
        "only the lines of that due leave the remittance's collection");
    JSONObject selected = classicParams.getJSONObject(RemittanceDueItems.GRID_PARAMETER)
        .getJSONArray("_selection").getJSONObject(0);
    assertEquals("120", selected.getString("realpayment"));
    assertEquals("200", selected.getString("outstandingamount"));
  }

  @Test
  void aDueAlreadyInTheRemittanceWithTheSameAmountIsLeftAsItIs() throws Exception {
    candidateRows.add(row("IPS-1", "FV-001", "A", LocalDate.of(2026, 11, 10), "200"));
    includedLines.add(new Object[] { "IPS-1", "LINE-A", new BigDecimal("20") });
    includedLines.add(new Object[] { "IPS-1", "LINE-B", new BigDecimal("10") });

    NeoResponse response = RemittanceDueItems.sync(remittance(), new JSONObject()
        .put("items", new JSONArray().put(item("IPS-1", "30.00"))));

    assertEquals(200, response.getHttpStatus());
    assertEquals(0, response.getBody().getInt("created"));
    assertEquals(0, response.getBody().getInt("updated"));
    assertEquals(0, response.getBody().getInt("removed"));
    assertEquals("", response.getBody().getString("message"));
    processStatic.verifyNoInteractions();
    verify(dal, never()).remove(any());
  }

  @Test
  void removingADueDeletesAllItsLinesWithoutCallingClassic() throws Exception {
    includedLines.add(new Object[] { "IPS-1", "LINE-A", new BigDecimal("20") });
    includedLines.add(new Object[] { "IPS-1", "LINE-B", new BigDecimal("10") });
    includedLines.add(new Object[] { "IPS-2", "LINE-C", new BigDecimal("5") });
    Remittance remittance = remittance();
    Map<String, RemittanceLine> lines = withLines(remittance, "LINE-A", "LINE-B", "LINE-C");

    NeoResponse response = RemittanceDueItems.sync(remittance,
        new JSONObject().put("remove", new JSONArray().put("IPS-1")));

    assertEquals(200, response.getHttpStatus());
    assertEquals(1, response.getBody().getInt("removed"), "counted in dues, not lines");
    assertEquals(0, response.getBody().getInt("created"));
    verify(dal).remove(lines.get("LINE-A"));
    verify(dal).remove(lines.get("LINE-B"));
    verify(dal, never()).remove(lines.get("LINE-C"));
    verify(dal).flush();
    processStatic.verifyNoInteractions();
  }

  @Test
  void aSyncAddsChangesAndRemovesInOneCall() throws Exception {
    candidateRows.add(row("IPS-NEW", "FV-001", "A", LocalDate.of(2026, 11, 10), "100"));
    candidateRows.add(row("IPS-KEPT", "FV-002", "B", LocalDate.of(2026, 11, 11), "100"));
    includedLines.add(new Object[] { "IPS-KEPT", "LINE-K", new BigDecimal("100") });
    includedLines.add(new Object[] { "IPS-OUT", "LINE-O", new BigDecimal("40") });
    Remittance remittance = remittance();
    withLines(remittance, "LINE-K", "LINE-O");
    classicAnswer = NeoResponse.ok(new JSONObject());

    NeoResponse response = RemittanceDueItems.sync(remittance, new JSONObject()
        .put("items", new JSONArray().put(item("IPS-NEW", 100)).put(item("IPS-KEPT", 60)))
        .put("remove", new JSONArray().put("IPS-OUT")));

    assertEquals(200, response.getHttpStatus());
    assertEquals(1, response.getBody().getInt("created"));
    assertEquals(1, response.getBody().getInt("updated"));
    assertEquals(1, response.getBody().getInt("removed"));
    assertEquals("2", response.getBody().getString("message"),
        "without a REM message, the number of dues written");
    JSONArray selection = classicParams.getJSONObject(RemittanceDueItems.GRID_PARAMETER)
        .getJSONArray("_selection");
    assertEquals(2, selection.length(), "one Classic run for the new and the changed due");
  }

  @Test
  void aRemRefusalOfTheSyncRollsEverythingBack() throws Exception {
    candidateRows.add(row("IPS-1", "FV-001", "A", LocalDate.of(2026, 11, 10), "100"));
    includedLines.add(new Object[] { "IPS-2", "LINE-B", new BigDecimal("10") });
    Remittance remittance = remittance();
    withLines(remittance, "LINE-B");
    classicAnswer = viewMessage("ERROR", "La factura ya está en otra remesa");

    NeoResponse response = RemittanceDueItems.sync(remittance, new JSONObject()
        .put("items", new JSONArray().put(item("IPS-1", 40)))
        .put("remove", new JSONArray().put("IPS-2")));

    assertEquals(422, response.getHttpStatus());
    assertEquals(RemittanceDueItems.MSG_SELECTION_FAILED, messageKey(response));
    assertTrue(response.getBody().getJSONObject("error").getString("message")
        .contains("La factura ya está en otra remesa"));
    verify(dal).rollbackAndClose();
  }

  @Test
  void aSuccessfulSyncIsNeverRolledBack() throws Exception {
    candidateRows.add(row("IPS-1", "FV-001", "A", LocalDate.of(2026, 11, 10), "100"));
    classicAnswer = viewMessage("success", "ok");

    assertEquals(200, RemittanceDueItems.sync(remittance(), new JSONObject()
        .put("items", new JSONArray().put(item("IPS-1", 40)))).getHttpStatus());
    verify(dal, never()).rollbackAndClose();
  }

  // ---------------------------------------------------------------- helpers

  @SuppressWarnings("unchecked")
  private Query<Object> recordingQuery(String hql) {
    Recorded recorded = new Recorded(hql);
    queries.add(recorded);
    Query<Object> query = mock(Query.class, RETURNS_SELF);
    when(query.setParameter(anyString(), any())).thenAnswer(inv -> {
      recorded.params.put(inv.getArgument(0), inv.getArgument(1));
      return query;
    });
    when(query.setParameterList(anyString(), any(Collection.class))).thenAnswer(inv -> {
      recorded.params.put(inv.getArgument(0), inv.getArgument(1));
      return query;
    });
    when(query.setFirstResult(org.mockito.ArgumentMatchers.anyInt())).thenAnswer(inv -> {
      recorded.firstResult = inv.getArgument(0);
      return query;
    });
    when(query.setMaxResults(org.mockito.ArgumentMatchers.anyInt())).thenAnswer(inv -> {
      recorded.maxResults = inv.getArgument(0);
      return query;
    });
    when(query.list()).thenAnswer(inv -> {
      if (hql.contains(FinAccPaymentMethod.ENTITY_NAME)) {
        return link != null ? new ArrayList<Object>(List.of(link)) : new ArrayList<Object>();
      }
      if (hql.startsWith("select rl.paymentScheduleDetail.invoicePaymentSchedule.id")) {
        return new ArrayList<Object>(includedLines);
      }
      if (hql.startsWith("select ips.id")) {
        return new ArrayList<Object>(filterByIds(recorded));
      }
      return new ArrayList<Object>();
    });
    when(query.uniqueResult()).thenAnswer(inv -> {
      if (hql.startsWith("select count(distinct ips.id)")) {
        return candidateTotal;
      }
      if (hql.startsWith("select count(rl.id)")) {
        return lineCounts.isEmpty() ? 0L : lineCounts.remove(0);
      }
      return null;
    });
    return query;
  }

  /** The select re-check binds {@code ids}: only the candidate rows with one of them qualify. */
  private List<Object[]> filterByIds(Recorded recorded) {
    Object ids = recorded.params.get("ids");
    if (!(ids instanceof Collection)) {
      return candidateRows;
    }
    List<Object[]> filtered = new ArrayList<>();
    for (Object[] row : candidateRows) {
      if (((Collection<?>) ids).contains(row[0])) {
        filtered.add(row);
      }
    }
    return filtered;
  }

  private Recorded rowsQuery() {
    return queries.stream().filter(q -> q.hql.startsWith("select ips.id")).findFirst()
        .orElseThrow(() -> new AssertionError("no candidate rows query"));
  }

  private Recorded includedQuery() {
    return queries.stream()
        .filter(q -> q.hql.startsWith("select rl.paymentScheduleDetail.invoicePaymentSchedule.id"))
        .findFirst().orElseThrow(() -> new AssertionError("no in-remittance query"));
  }

  private Recorded countQuery() {
    return queries.stream().filter(q -> q.hql.startsWith("select count(distinct ips.id)"))
        .findFirst().orElseThrow(() -> new AssertionError("no candidate count query"));
  }

  private static JSONObject item(String id, Object amount) throws Exception {
    return new JSONObject().put("id", id).put("amount", amount);
  }

  private static List<String> invalidIds(NeoResponse response) throws Exception {
    JSONArray ids = response.getBody().getJSONObject("error").getJSONArray("invalidIds");
    List<String> list = new ArrayList<>();
    for (int i = 0; i < ids.length(); i++) {
      list.add(ids.getString(i));
    }
    return list;
  }

  /**
   * Gives {@code remittance} a mutable line collection with one line per id, each also answered by
   * {@code OBDal.get}.
   */
  private Map<String, RemittanceLine> withLines(Remittance remittance, String... ids) {
    Map<String, RemittanceLine> lines = new java.util.LinkedHashMap<>();
    List<RemittanceLine> collection = new ArrayList<>();
    for (String id : ids) {
      RemittanceLine line = mock(RemittanceLine.class);
      when(line.getId()).thenReturn(id);
      when(dal.get(RemittanceLine.class, id)).thenReturn(line);
      lines.put(id, line);
      collection.add(line);
    }
    when(remittance.getREMRemittanceLineList()).thenReturn(collection);
    return lines;
  }

  private static Remittance remittance() {
    Currency eur = mock(Currency.class);
    when(eur.getId()).thenReturn(EUR_ID);
    FIN_FinancialAccount account = mock(FIN_FinancialAccount.class);
    when(account.getId()).thenReturn(ACC_ID);
    when(account.getCurrency()).thenReturn(eur);
    FIN_PaymentMethod method = mock(FIN_PaymentMethod.class);
    when(method.getId()).thenReturn(METHOD_ID);
    RemittanceType type = mock(RemittanceType.class);
    when(type.getPaymentMethod()).thenReturn(method);
    Client client = mock(Client.class);
    when(client.getId()).thenReturn(CLIENT_ID);
    Organization org = mock(Organization.class);
    when(org.getId()).thenReturn(ORG_ID);
    Remittance remittance = mock(Remittance.class);
    when(remittance.getId()).thenReturn(REM_ID);
    when(remittance.getClient()).thenReturn(client);
    when(remittance.getOrganization()).thenReturn(org);
    when(remittance.getFinancialAccount()).thenReturn(account);
    when(remittance.getRemittanceType()).thenReturn(type);
    when(remittance.getDueDate()).thenReturn(date(DUE_DATE));
    return remittance;
  }

  private static FinAccPaymentMethod link(boolean payIn, boolean payOut) {
    FinAccPaymentMethod link = mock(FinAccPaymentMethod.class);
    when(link.isPayinAllow()).thenReturn(payIn);
    when(link.isPayoutAllow()).thenReturn(payOut);
    return link;
  }

  private static Object[] row(String id, String documentNo, String partner, LocalDate expected,
      String amount) {
    return new Object[] { id, documentNo, partner, date(expected), new BigDecimal(amount), "EUR",
        "INV-" + id, Boolean.FALSE, date(expected.minusDays(30)),
        new BigDecimal(amount), "BP-" + id };
  }

  private static Date date(LocalDate day) {
    return Date.from(day.atStartOfDay(ZoneId.systemDefault()).toInstant());
  }

  private static NeoResponse viewMessage(String type, String text) throws Exception {
    return NeoResponse.ok(new JSONObject().put("responseActions", new JSONArray()
        .put(new JSONObject().put("showMsgInView",
            new JSONObject().put("msgType", type).put("msgText", text)))));
  }

  private static String messageKey(NeoResponse response) throws Exception {
    return response.getBody().getJSONObject("error").getJSONArray("messageKeys").getString(0);
  }
}
