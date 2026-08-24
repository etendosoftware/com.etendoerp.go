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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.math.MathContext;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.Session;
import org.hibernate.criterion.Criterion;
import org.hibernate.query.Query;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.openbravo.advpaymentmngt.dao.AdvPaymentMngtDao;
import org.openbravo.advpaymentmngt.process.FIN_AddPayment;
import org.openbravo.advpaymentmngt.process.FIN_PaymentProcess;
import org.openbravo.advpaymentmngt.utility.FIN_Utility;
import org.openbravo.base.exception.OBException;
import org.openbravo.base.secureApp.VariablesSecureApp;
import org.openbravo.client.kernel.RequestContext;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.erpCommon.utility.OBError;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.businesspartner.BusinessPartner;
import org.openbravo.model.common.currency.Currency;
import org.openbravo.model.common.enterprise.DocumentType;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.common.invoice.Invoice;
import org.openbravo.model.financialmgmt.payment.FIN_FinancialAccount;
import org.openbravo.model.financialmgmt.payment.FIN_Payment;
import org.openbravo.model.financialmgmt.payment.FIN_PaymentDetail;
import org.openbravo.model.financialmgmt.payment.FIN_PaymentMethod;
import org.openbravo.model.financialmgmt.payment.FIN_PaymentSchedule;
import org.openbravo.model.financialmgmt.payment.FIN_PaymentScheduleDetail;
import org.openbravo.model.financialmgmt.payment.FIN_Payment_Credit;
import org.openbravo.model.financialmgmt.payment.FinAccPaymentMethod;
import org.openbravo.service.db.DalConnectionProvider;

import com.etendoerp.payment.removal.util.PaymentRemovalUtil;
import com.etendoerp.psd2.bank.integration.data.PisPayment;
import com.etendoerp.psd2.bank.integration.utils.BankIntegrationConstants;
import com.etendoerp.psd2.bank.integration.utils.BankIntegrationPISUtils;
import com.etendoerp.psd2.bank.integration.utils.PISPaymentDao;

/**
 * Mockito unit tests for the ETP-4331 two-step Cobros/Pagos flow in
 * {@link PaymentRegistrationService}:
 * <ul>
 *   <li>{@code handleListCreditSources} - nets generatedCredit - usedCredit for accumulated
 *       credit, and lists unpaid negative PSDs (abonos) of any negative-total invoice, whatever
 *       its document type (ETP-4841), excluding the current invoice.</li>
 *   <li>{@code handleListPaymentMethods} - distinct payin/payout methods for accounts in
 *       the invoice's natural org tree.</li>
 *   <li>{@code doRegisterPaymentAdvanced} - draft (not processed), confirm (processed),
 *       credit consumption (used-credit + FIN_Payment_Credit), abono consumption (negative
 *       detail), and over-payment resolution (leave-credit / refund).</li>
 *   <li>{@code confirmDraftPayment} - processes a previously saved draft.</li>
 * </ul>
 *
 * <p>Strategy mirrors the sibling {@code PaymentRegistrationServiceTest} and
 * {@code AddPaymentServiceTest}: every static collaborator and every constructed
 * collaborator is mocked, so the orchestration logic runs over deterministic in-memory
 * fixtures without a database. Because each scenario wires a different criteria/HQL graph,
 * fixtures are built as local mocks inside each test (matching the established convention of
 * the sibling payment tests) under {@code Strictness.LENIENT}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PaymentRegistrationServiceAdvancedTest {

  private static final String INVOICE_ID = "inv-1";
  private static final String SCHEDULE_ID = "sched-1";
  private static final String ACCOUNT_ID = "acc-1";
  private static final String CLIENT_ID = "client-1";
  private static final String ORG_ID = "org-1";
  private static final String CURRENCY_ID = "CUR-ID";
  private static final String PAY_DATE = "2026-06-03";
  private static final String DRAFT = "draft";
  private static final String CONFIRM = "confirm";
  private static final String KIND_CREDIT = "credit";
  private static final String KIND_ABONO = "abono";
  private static final String CREDIT_PAY_ID = "credit-pay-1";
  private static final String ABONO_PSD_ID = "abono-psd-1";
  private static final String NEW_PAY_ID = "new-pay-1";
  private static final String DRAFT_PAY_ID = "draft-pay-1";
  private static final String ERROR_TYPE = "Error";
  private static final String ITEMS = "items";

  private MockedStatic<OBDal> obDalMock;
  private MockedStatic<OBContext> obContextMock;
  private MockedStatic<FIN_Utility> finUtilityMock;
  private MockedStatic<FIN_AddPayment> finAddPaymentMock;
  private MockedStatic<FIN_PaymentProcess> finPaymentProcessMock;
  private MockedStatic<NeoDefaultsService> neoDefaultsMock;
  private MockedStatic<RequestContext> requestContextMock;
  private MockedStatic<PaymentRemovalUtil> paymentRemovalUtilMock;
  private MockedConstruction<AdvPaymentMngtDao> daoConstruction;
  private MockedConstruction<DalConnectionProvider> connConstruction;

  private OBDal dal;
  private OBContext obContext;
  private Session session;

  private Invoice invoice;
  private BusinessPartner bp;
  private Organization org;
  private Currency currency;
  private DocumentType docType;
  private FIN_FinancialAccount account;
  private FIN_PaymentMethod method;
  private FIN_PaymentSchedule schedule;
  private FIN_Payment newPayment;
  private FIN_Payment refundPayment;
  private OBError okResult;

  @BeforeEach
  void setUp() {
    dal = mock(OBDal.class);
    obContext = mock(OBContext.class);
    session = mock(Session.class);

    invoice = mock(Invoice.class);
    bp = mock(BusinessPartner.class);
    org = mock(Organization.class);
    currency = mock(Currency.class);
    docType = mock(DocumentType.class);
    account = mock(FIN_FinancialAccount.class);
    method = mock(FIN_PaymentMethod.class);
    schedule = mock(FIN_PaymentSchedule.class);
    newPayment = mock(FIN_Payment.class);
    refundPayment = mock(FIN_Payment.class);
    okResult = mock(OBError.class);

    // ── static mocks ────────────────────────────────────────────────────────
    obDalMock = mockStatic(OBDal.class);
    obDalMock.when(OBDal::getInstance).thenReturn(dal);
    when(dal.getSession()).thenReturn(session);

    obContextMock = mockStatic(OBContext.class);
    obContextMock.when(OBContext::getOBContext).thenReturn(obContext);

    finUtilityMock = mockStatic(FIN_Utility.class);
    finUtilityMock.when(() -> FIN_Utility.getDocumentType(any(), anyString())).thenReturn(docType);
    finUtilityMock.when(() -> FIN_Utility.getDocumentNo(any(DocumentType.class), anyString()))
        .thenReturn("PAY-1");

    finAddPaymentMock = mockStatic(FIN_AddPayment.class);
    // ETP-4841: deliberately NO stub for FIN_AddPayment.setFinancialTransactionAmountAndRate — the
    // payment flow no longer calls that core helper (it recomputed rate = txnAmount / amount and so
    // lost the rate typed in the modal). Both fields are now written verbatim on the entity by
    // PaymentCurrencyConverter.applyTransactionAmountAndRate, which the tests below assert through
    // the entity setters plus a never() guard on the core helper.
    finAddPaymentMock.when(() -> FIN_AddPayment.updatePaymentDetail(any(), any(), any(), anyBoolean()))
        .thenReturn(BigDecimal.ZERO);
    finAddPaymentMock.when(() -> FIN_AddPayment.processPayment(any(), any(), anyString(), any(), anyString()))
        .thenReturn(okResult);
    finAddPaymentMock.when(() -> FIN_AddPayment.processPayment(any(), any(), anyString(), any(), anyString(), anyString()))
        .thenReturn(okResult);
    finAddPaymentMock.when(() -> FIN_AddPayment.createRefundPayment(any(), any(), any(), any(), any()))
        .thenReturn(refundPayment);

    finPaymentProcessMock = mockStatic(FIN_PaymentProcess.class);

    neoDefaultsMock = mockStatic(NeoDefaultsService.class);
    neoDefaultsMock.when(() -> NeoDefaultsService.buildVariablesSecureApp(any()))
        .thenReturn(mock(VariablesSecureApp.class));

    requestContextMock = mockStatic(RequestContext.class);
    requestContextMock.when(RequestContext::get).thenReturn(mock(RequestContext.class));

    paymentRemovalUtilMock = mockStatic(PaymentRemovalUtil.class);

    // ── common entity stubs ──────────────────────────────────────────────────
    when(okResult.getType()).thenReturn("Success");
    when(docType.getDocumentCategory()).thenReturn("ARR");

    when(invoice.getCurrency()).thenReturn(currency);
    when(invoice.getBusinessPartner()).thenReturn(bp);
    when(invoice.getOrganization()).thenReturn(org);
    when(invoice.getPaymentMethod()).thenReturn(method);
    when(account.getCurrency()).thenReturn(currency);
    when(account.getOrganization()).thenReturn(org);
    when(currency.getId()).thenReturn(CURRENCY_ID);
    when(org.getId()).thenReturn(ORG_ID);

    when(newPayment.getId()).thenReturn(NEW_PAY_ID);
    when(newPayment.getDocumentNo()).thenReturn("PAY-1");
    when(newPayment.getStatus()).thenReturn("RPR");
    when(newPayment.getCurrency()).thenReturn(currency);
    when(refundPayment.getId()).thenReturn("refund-1");
    when(refundPayment.getDocumentNo()).thenReturn("REF-1");

    final FIN_Payment paymentRef = newPayment;
    daoConstruction = mockConstruction(AdvPaymentMngtDao.class, (m, ctx) -> {
      when(m.getNewPayment(anyBoolean(), any(), any(), any(), any(), any(), any(), any(),
          any(), any(), any(), any(), any())).thenReturn(paymentRef);
      when(m.getNewPaymentScheduleDetail(any(), any()))
          .thenReturn(mock(FIN_PaymentScheduleDetail.class));
      when(m.getNewPaymentDetail(any(), any(), any(), any(), anyBoolean(), any()))
          .thenReturn(mock(FIN_PaymentDetail.class));
    });
    connConstruction = mockConstruction(DalConnectionProvider.class);
  }

  @AfterEach
  void tearDown() {
    closeQuietly(daoConstruction);
    closeQuietly(connConstruction);
    closeQuietly(paymentRemovalUtilMock);
    closeQuietly(requestContextMock);
    closeQuietly(neoDefaultsMock);
    closeQuietly(finPaymentProcessMock);
    closeQuietly(finAddPaymentMock);
    closeQuietly(finUtilityMock);
    closeQuietly(obContextMock);
    closeQuietly(obDalMock);
  }

  private static void closeQuietly(AutoCloseable closeable) {
    if (closeable != null) {
      try {
        closeable.close();
      } catch (Exception ignored) {
        // best-effort cleanup
      }
    }
  }

  // ════════════════════════════════════════════════════════════════════════
  // handleListCreditSources
  // ════════════════════════════════════════════════════════════════════════

  @Test
  @DisplayName("Credit listing nets generatedCredit - usedCredit and skips fully consumed credit")
  void testListCreditSourcesNetsAvailableCredit() throws Exception {
    NeoContext context = creditSourcesContext();
    stubInvoiceWithBp();

    // No abonos.
    stubAbonoQuery(Collections.emptyList());

    // Two credit payments: one partially consumed (avail 40), one fully consumed (avail 0).
    FIN_Payment partial = mock(FIN_Payment.class);
    when(partial.getId()).thenReturn("p-partial");
    when(partial.getDocumentNo()).thenReturn("CR/PARTIAL");
    when(partial.getGeneratedCredit()).thenReturn(new BigDecimal("100"));
    when(partial.getUsedCredit()).thenReturn(new BigDecimal("60"));
    when(partial.getPaymentDate()).thenReturn(null);
    when(partial.getDescription()).thenReturn("partial");

    // (a) The credit HQL already excludes consumed rows, but the in-loop guard also drops
    // any zero-availability row defensively — verify it is not listed.
    FIN_Payment consumed = mock(FIN_Payment.class);
    when(consumed.getId()).thenReturn("p-consumed");
    when(consumed.getDocumentNo()).thenReturn("CR/CONSUMED");
    when(consumed.getGeneratedCredit()).thenReturn(new BigDecimal("50"));
    when(consumed.getUsedCredit()).thenReturn(new BigDecimal("50"));
    when(consumed.getPaymentDate()).thenReturn(null);

    stubCreditQuery(Arrays.asList(partial, consumed));

    NeoResponse response = PaymentCreditSourcesService.handleListCreditSources(context, true);

    assertEquals(200, response.getHttpStatus());
    JSONArray items = response.getBody().getJSONArray(ITEMS);
    assertEquals(1, items.length(), "only the partially consumed credit must appear");
    JSONObject item = items.getJSONObject(0);
    assertEquals(KIND_CREDIT, item.getString("kind"));
    assertEquals("p-partial", item.getString("paymentId"));
    assertEquals(new BigDecimal("40"), new BigDecimal(item.getString("avail")));
  }

  @Test
  @DisplayName("Pagos (AP): accumulated credit is never queried — no supplier credit accrual in it1")
  void testListCreditSourcesPaymentSideNeverQueriesAccumulatedCredit() throws Exception {
    NeoContext context = creditSourcesContext();
    stubInvoiceWithBp();
    stubAbonoQuery(Collections.emptyList());

    NeoResponse response = PaymentCreditSourcesService.handleListCreditSources(context, false);

    assertEquals(200, response.getHttpStatus());
    assertEquals(0, response.getBody().getInt("totalCount"));
    verify(session, never()).createQuery(anyString(), eq(FIN_Payment.class));
  }

  @Test
  @DisplayName("Abono listing returns unpaid negative credit-memo PSDs with absolute avail")
  void testListCreditSourcesReturnsAbonos() throws Exception {
    NeoContext context = creditSourcesContext();
    stubInvoiceWithBp();

    FIN_PaymentScheduleDetail abono = mock(FIN_PaymentScheduleDetail.class);
    when(abono.getId()).thenReturn(ABONO_PSD_ID);
    when(abono.getAmount()).thenReturn(new BigDecimal("-25.00"));
    FIN_PaymentSchedule ps = mock(FIN_PaymentSchedule.class);
    Invoice ncInvoice = mock(Invoice.class);
    DocumentType ncType = mock(DocumentType.class);
    when(ncType.getName()).thenReturn("Credit Memo");
    when(ncInvoice.getDocumentNo()).thenReturn("NC/001");
    when(ncInvoice.getInvoiceDate()).thenReturn(null);
    when(ncInvoice.getDocumentType()).thenReturn(ncType);
    when(ps.getInvoice()).thenReturn(ncInvoice);
    when(abono.getInvoicePaymentSchedule()).thenReturn(ps);

    stubAbonoQuery(Collections.singletonList(abono));
    stubCreditQuery(Collections.emptyList());

    NeoResponse response = PaymentCreditSourcesService.handleListCreditSources(context, true);

    assertEquals(200, response.getHttpStatus());
    JSONArray items = response.getBody().getJSONArray(ITEMS);
    assertEquals(1, items.length());
    JSONObject item = items.getJSONObject(0);
    assertEquals(KIND_ABONO, item.getString("kind"));
    assertEquals(ABONO_PSD_ID, item.getString("psdId"));
    assertEquals("NC/001", item.getString("doc"));
    // amount -25.00 → avail 25.00 (absolute value)
    assertEquals(0, new BigDecimal("25.00").compareTo(new BigDecimal(item.getString("avail"))));
  }

  @Test
  @DisplayName("Credit sources are filtered by the invoice currency: both the abono and the "
      + "accumulated-credit queries bind :cur to the invoice's currency id")
  @SuppressWarnings("unchecked")
  void testListCreditSourcesFilteredByInvoiceCurrency() throws Exception {
    NeoContext context = creditSourcesContext();
    stubInvoiceWithBp();
    // invoice.getCurrency() → currency (CURRENCY_ID) is wired by the global setUp stubs.

    Query<FIN_PaymentScheduleDetail> abonoQuery = mock(Query.class);
    when(session.createQuery(anyString(), eq(FIN_PaymentScheduleDetail.class)))
        .thenReturn(abonoQuery);
    when(abonoQuery.setParameter(anyString(), any())).thenReturn(abonoQuery);
    when(abonoQuery.setMaxResults(anyInt())).thenReturn(abonoQuery);
    when(abonoQuery.list()).thenReturn(Collections.emptyList());

    Query<FIN_Payment> creditQuery = mock(Query.class);
    when(session.createQuery(anyString(), eq(FIN_Payment.class))).thenReturn(creditQuery);
    when(creditQuery.setParameter(anyString(), any())).thenReturn(creditQuery);
    when(creditQuery.setMaxResults(anyInt())).thenReturn(creditQuery);
    when(creditQuery.list()).thenReturn(Collections.emptyList());

    NeoResponse response = PaymentCreditSourcesService.handleListCreditSources(context, true);

    assertEquals(200, response.getHttpStatus());
    // Both source kinds must be restricted to the invoice currency — cross-currency credit
    // cannot be applied. The row filtering is enforced by the HQL predicate on :cur; here we
    // assert the predicate is bound to the invoice's own currency id.
    verify(abonoQuery).setParameter("cur", CURRENCY_ID);
    verify(creditQuery).setParameter("cur", CURRENCY_ID);
  }

  @Test
  @DisplayName("Credit sources for an invoice without a business partner returns empty")
  void testListCreditSourcesNoBusinessPartnerReturnsEmpty() throws Exception {
    NeoContext context = creditSourcesContext();
    when(dal.get(Invoice.class, INVOICE_ID)).thenReturn(invoice);
    when(invoice.getBusinessPartner()).thenReturn(null);

    NeoResponse response = PaymentCreditSourcesService.handleListCreditSources(context, true);

    assertEquals(200, response.getHttpStatus());
    assertEquals(0, response.getBody().getInt("totalCount"));
  }

  @Test
  @DisplayName("Abono and credit sources are merged into a single date-descending list, "
      + "interleaved by kind")
  void testListCreditSourcesInterleavesByDateDescending() throws Exception {
    NeoContext context = creditSourcesContext();
    stubInvoiceWithBp();

    // Abono rows: sorted by the originating credit-note/return invoice date.
    FIN_PaymentScheduleDetail abono1 = abonoPsd("abono-1", new BigDecimal("-10.00"), "NC/001",
        date("2026-07-02"), "Credit Memo");
    FIN_PaymentScheduleDetail abono2 = abonoPsd("abono-2", new BigDecimal("-20.00"), "NC/002",
        date("2026-06-30"), "Credit Memo");
    stubAbonoQuery(Arrays.asList(abono1, abono2));

    // Credit rows: sorted by the originating payment's payment date.
    FIN_Payment credit1 = creditPayment("credit-1", "CR/001", new BigDecimal("100"),
        new BigDecimal("0"), date("2026-07-01"));
    FIN_Payment credit2 = creditPayment("credit-2", "CR/002", new BigDecimal("50"),
        new BigDecimal("0"), date("2026-06-29"));
    stubCreditQuery(Arrays.asList(credit1, credit2));

    NeoResponse response = PaymentCreditSourcesService.handleListCreditSources(context, true);

    assertEquals(200, response.getHttpStatus());
    JSONArray items = response.getBody().getJSONArray(ITEMS);
    assertEquals(4, items.length());

    // Expected merged order (most recent first), interleaving the two kinds:
    // abono(07-02), credit(07-01), abono(06-30), credit(06-29) — neither a pure
    // "all abono then all credit" nor a pure "all credit then all abono" block.
    JSONObject first = items.getJSONObject(0);
    assertEquals(KIND_ABONO, first.getString("kind"));
    assertEquals("abono-1", first.getString("id"));

    JSONObject second = items.getJSONObject(1);
    assertEquals(KIND_CREDIT, second.getString("kind"));
    assertEquals("credit-1", second.getString("id"));

    JSONObject third = items.getJSONObject(2);
    assertEquals(KIND_ABONO, third.getString("kind"));
    assertEquals("abono-2", third.getString("id"));

    JSONObject fourth = items.getJSONObject(3);
    assertEquals(KIND_CREDIT, fourth.getString("kind"));
    assertEquals("credit-2", fourth.getString("id"));
  }

  @Test
  @DisplayName("A source with a null date is not lost and sorts after every dated source")
  void testListCreditSourcesNullDateSortsLast() throws Exception {
    NeoContext context = creditSourcesContext();
    stubInvoiceWithBp();

    FIN_PaymentScheduleDetail abonoNoDate = abonoPsd("abono-null", new BigDecimal("-15.00"),
        "NC/003", null, "Credit Memo");
    stubAbonoQuery(Collections.singletonList(abonoNoDate));

    FIN_Payment creditDated = creditPayment("credit-dated", "CR/003", new BigDecimal("30"),
        new BigDecimal("0"), date("2026-06-15"));
    stubCreditQuery(Collections.singletonList(creditDated));

    NeoResponse response = PaymentCreditSourcesService.handleListCreditSources(context, true);

    assertEquals(200, response.getHttpStatus());
    JSONArray items = response.getBody().getJSONArray(ITEMS);
    assertEquals(2, items.length());
    assertEquals("credit-dated", items.getJSONObject(0).getString("id"));
    assertEquals("abono-null", items.getJSONObject(1).getString("id"));
  }

  @Test
  @DisplayName("With no credit sources, abono-only results stay ordered by invoice date desc")
  void testListCreditSourcesOnlyAbonoOrderedByInvoiceDateDesc() throws Exception {
    NeoContext context = creditSourcesContext();
    stubInvoiceWithBp();

    FIN_PaymentScheduleDetail older = abonoPsd("abono-old", new BigDecimal("-5.00"), "NC/010",
        date("2026-06-01"), "Credit Memo");
    FIN_PaymentScheduleDetail newer = abonoPsd("abono-new", new BigDecimal("-5.00"), "NC/011",
        date("2026-06-20"), "Credit Memo");
    stubAbonoQuery(Arrays.asList(older, newer));
    stubCreditQuery(Collections.emptyList());

    NeoResponse response = PaymentCreditSourcesService.handleListCreditSources(context, true);

    assertEquals(200, response.getHttpStatus());
    JSONArray items = response.getBody().getJSONArray(ITEMS);
    assertEquals(2, items.length());
    assertEquals("abono-new", items.getJSONObject(0).getString("id"));
    assertEquals("abono-old", items.getJSONObject(1).getString("id"));
  }

  @Test
  @DisplayName("With no abono sources, credit-only results stay ordered by payment date desc")
  void testListCreditSourcesOnlyCreditOrderedByPaymentDateDesc() throws Exception {
    NeoContext context = creditSourcesContext();
    stubInvoiceWithBp();

    stubAbonoQuery(Collections.emptyList());

    FIN_Payment older = creditPayment("credit-old", "CR/010", new BigDecimal("40"),
        new BigDecimal("0"), date("2026-05-01"));
    FIN_Payment newer = creditPayment("credit-new", "CR/011", new BigDecimal("60"),
        new BigDecimal("0"), date("2026-05-20"));
    stubCreditQuery(Arrays.asList(older, newer));

    NeoResponse response = PaymentCreditSourcesService.handleListCreditSources(context, true);

    assertEquals(200, response.getHttpStatus());
    JSONArray items = response.getBody().getJSONArray(ITEMS);
    assertEquals(2, items.length());
    assertEquals("credit-new", items.getJSONObject(0).getString("id"));
    assertEquals("credit-old", items.getJSONObject(1).getString("id"));
  }

  // ════════════════════════════════════════════════════════════════════════
  // handleListCreditSources — ETP-4841 negative-total-only filter (no doc-type whitelist)
  // ════════════════════════════════════════════════════════════════════════

  /**
   * ETP-4841: eligibility is decided purely by the SIGN of the invoice total. The abono HQL keeps
   * the {@code grandTotalAmount < 0} predicate (it must stay INSIDE the query — with
   * {@code setMaxResults(50)}, filtering in Java afterwards would truncate before the restriction
   * applied and silently drop eligible sources) and no longer carries any rectificative doc-type
   * whitelist.
   */
  @Test
  @DisplayName("ETP-4841: the abono HQL restricts to a negative invoice total ONLY — the "
      + "rectificative doc-type whitelist is gone")
  @SuppressWarnings("unchecked")
  void testListCreditSourcesAbonoRestrictedToNegativeTotalOnly() throws Exception {
    NeoContext context = creditSourcesContext();
    stubInvoiceWithBp();

    Query<FIN_PaymentScheduleDetail> abonoQuery = mock(Query.class);
    ArgumentCaptor<String> hqlCaptor = ArgumentCaptor.forClass(String.class);
    when(session.createQuery(hqlCaptor.capture(), eq(FIN_PaymentScheduleDetail.class)))
        .thenReturn(abonoQuery);
    when(abonoQuery.setParameter(anyString(), any())).thenReturn(abonoQuery);
    when(abonoQuery.setMaxResults(anyInt())).thenReturn(abonoQuery);
    when(abonoQuery.list()).thenReturn(Collections.emptyList());
    stubCreditQuery(Collections.emptyList());

    NeoResponse response = PaymentCreditSourcesService.handleListCreditSources(context, true);

    assertEquals(200, response.getHttpStatus());
    String hql = hqlCaptor.getValue();
    // A POSITIVE-total invoice is excluded by this predicate even when it IS a rectificativa.
    assertTrue(hql.contains("grandTotalAmount < 0"),
        "the negative-total restriction must stay inside the HQL");
    assertFalse(hql.contains("rectDocTypes"),
        "the rectificative doc-type whitelist must be gone from the HQL");
    assertFalse(hql.contains("transactionDocument"),
        "the abono query must not filter by document type at all");
  }

  /**
   * The new capability: a negative-total invoice of an ORDINARY document type ("Factura", not a
   * Factura Rectificativa) is listed as a funding source. The doc-type whitelist used to drop it.
   * Nothing may re-introduce a Java-side doc-type filter after the query either.
   */
  @Test
  @DisplayName("ETP-4841: a negative-total invoice of an ORDINARY document type is listed as an "
      + "abono source")
  void testListCreditSourcesListsNegativeTotalInvoiceOfOrdinaryDocType() throws Exception {
    NeoContext context = creditSourcesContext();
    stubInvoiceWithBp();

    FIN_PaymentScheduleDetail ordinaryAbono = abonoPsd("abono-ordinary", new BigDecimal("-45.00"),
        "FAC/077", date("2026-06-11"), "Factura");
    stubAbonoQuery(Collections.singletonList(ordinaryAbono));
    stubCreditQuery(Collections.emptyList());

    NeoResponse response = PaymentCreditSourcesService.handleListCreditSources(context, true);

    assertEquals(200, response.getHttpStatus());
    JSONArray items = response.getBody().getJSONArray(ITEMS);
    assertEquals(1, items.length(), "an ordinary-doc-type credit must not be filtered out");
    JSONObject item = items.getJSONObject(0);
    assertEquals(KIND_ABONO, item.getString("kind"));
    assertEquals("abono-ordinary", item.getString("psdId"));
    assertEquals("Factura", item.getString("note"));
    assertEquals(0, new BigDecimal("45.00").compareTo(new BigDecimal(item.getString("avail"))));
  }

  @Test
  @DisplayName("The payment side (isReceipt=false) binds receipt=false on the abono query, so AP "
      + "only ever sees purchase-side sources")
  @SuppressWarnings("unchecked")
  void testListCreditSourcesForPaymentSideBindsReceiptFalse() throws Exception {
    NeoContext context = creditSourcesContext();
    stubInvoiceWithBp();

    Query<FIN_PaymentScheduleDetail> abonoQuery = mock(Query.class);
    when(session.createQuery(anyString(), eq(FIN_PaymentScheduleDetail.class)))
        .thenReturn(abonoQuery);
    when(abonoQuery.setParameter(anyString(), any())).thenReturn(abonoQuery);
    when(abonoQuery.setMaxResults(anyInt())).thenReturn(abonoQuery);
    when(abonoQuery.list()).thenReturn(Collections.emptyList());

    NeoResponse response = PaymentCreditSourcesService.handleListCreditSources(context, false);

    assertEquals(200, response.getHttpStatus());
    verify(abonoQuery).setParameter("receipt", Boolean.FALSE);
  }

  /**
   * {@code abonosUsedByDraft} is deliberately unrestricted: it answers "what is already physically
   * linked to this draft", not "what may be linked". A source the current rule would reject — here
   * an invoice whose total is POSITIVE — must still re-list, or the frontend
   * ({@code usePaymentBalance.seedLines}) would silently drop funding the draft already relies on,
   * leaving the user unable to see or un-check it.
   */
  @Test
  @DisplayName("ETP-4841: a draft's already-linked abono re-lists even when its invoice total is "
      + "no longer eligible — abonosUsedByDraft is deliberately unfiltered")
  void testListCreditSourcesEditModeRelistsLinkedAbonoRegardlessOfEligibility() throws Exception {
    String editPaymentId = "draft-being-edited";
    NeoContext context = creditSourcesContextWithEditPaymentId(editPaymentId);
    stubInvoiceWithBp();
    stubCreditQuery(Collections.emptyList());

    FIN_PaymentScheduleDetail linkedAbono = abonoPsdWithInvoiceTotal("abono-linked",
        new BigDecimal("-20.00"), "NC/020", date("2026-05-10"), new BigDecimal("120.00"));
    stubEditAbonoQueries(Collections.emptyList(), Collections.singletonList(linkedAbono));

    NeoResponse response = PaymentCreditSourcesService.handleListCreditSources(context, true);

    assertEquals(200, response.getHttpStatus());
    JSONArray items = response.getBody().getJSONArray(ITEMS);
    assertEquals(1, items.length());
    assertEquals("abono-linked", items.getJSONObject(0).getString("psdId"));
  }

  // ════════════════════════════════════════════════════════════════════════
  // handleListPaymentMethods
  // ════════════════════════════════════════════════════════════════════════

  @Test
  @SuppressWarnings("unchecked")
  @DisplayName("Payment methods are de-duplicated and filtered to the natural org tree")
  void testListPaymentMethodsDistinctWithinNaturalTree() throws Exception {
    NeoContext context = creditSourcesContext();
    stubInvoiceOrgTree();

    FIN_PaymentMethod pmA = mock(FIN_PaymentMethod.class);
    when(pmA.getId()).thenReturn("pm-A");
    when(pmA.getName()).thenReturn("Wire");
    FIN_PaymentMethod pmB = mock(FIN_PaymentMethod.class);
    when(pmB.getId()).thenReturn("pm-B");
    when(pmB.getName()).thenReturn("Cash");

    FIN_FinancialAccount inTree = mock(FIN_FinancialAccount.class);
    when(inTree.getOrganization()).thenReturn(org);
    FIN_FinancialAccount outOfTree = mock(FIN_FinancialAccount.class);
    Organization otherOrg = mock(Organization.class);
    when(otherOrg.getId()).thenReturn("org-other");
    when(outOfTree.getOrganization()).thenReturn(otherOrg);

    // two FAPM rows for pm-A on the same in-tree account (duplicate), one pm-B in tree,
    // and one pm-A on an out-of-tree account (must be excluded by org filter).
    FinAccPaymentMethod fapm1 = fapm(inTree, pmA);
    FinAccPaymentMethod fapm2 = fapm(inTree, pmA);
    FinAccPaymentMethod fapm3 = fapm(inTree, pmB);
    FinAccPaymentMethod fapm4 = fapm(outOfTree, pmA);

    OBCriteria<FinAccPaymentMethod> crit = mock(OBCriteria.class);
    when(dal.createCriteria(FinAccPaymentMethod.class)).thenReturn(crit);
    when(crit.setFilterOnReadableOrganization(anyBoolean())).thenReturn(crit);
    when(crit.add(any(Criterion.class))).thenReturn(crit);
    when(crit.list()).thenReturn(Arrays.asList(fapm1, fapm2, fapm3, fapm4));

    NeoResponse response = PaymentRegistrationService.handleListPaymentMethods(context, true);

    assertEquals(200, response.getHttpStatus());
    JSONArray items = response.getBody().getJSONArray(ITEMS);
    assertEquals(2, items.length(), "pm-A de-duplicated, out-of-tree pm-A excluded");
    assertEquals("pm-A", items.getJSONObject(0).getString("id"));
    assertEquals("pm-B", items.getJSONObject(1).getString("id"));
  }

  // ════════════════════════════════════════════════════════════════════════
  // doRegisterPaymentAdvanced - draft vs confirm
  // ════════════════════════════════════════════════════════════════════════

  @Test
  @DisplayName("Draft creates the payment but does NOT process it")
  void testAdvancedDraftDoesNotProcess() throws Exception {
    stubAdvancedBasics();
    stubPendingPSDs(new BigDecimal("100.00"));
    // A draft is created but not processed → the entity still reports processed=false.
    when(newPayment.isProcessed()).thenReturn(false);

    JSONObject body = advancedBody("100.00", DRAFT);

    NeoResponse response = PaymentRegistrationService.doRegisterPaymentAdvanced(
        INVOICE_ID, body, true);

    assertEquals(201, response.getHttpStatus());
    JSONObject data = response.getBody().getJSONObject("response").getJSONObject("data");
    assertFalse(data.getBoolean("processed"), "draft payment must report processed=false");
    finAddPaymentMock.verify(
        () -> FIN_AddPayment.processPayment(any(), any(), anyString(), any(), anyString()),
        never());
  }

  @Test
  @DisplayName("Confirm processes the payment and settles the installment with cash")
  void testAdvancedConfirmProcessesAndSettles() throws Exception {
    stubAdvancedBasics();
    FIN_PaymentScheduleDetail psd = stubPendingPSDs(new BigDecimal("100.00"));

    JSONObject body = advancedBody("100.00", CONFIRM);

    NeoResponse response = PaymentRegistrationService.doRegisterPaymentAdvanced(
        INVOICE_ID, body, true);

    assertEquals(201, response.getHttpStatus());
    // full cash applied to the installment
    finAddPaymentMock.verify(() -> FIN_AddPayment.updatePaymentDetail(
        eq(psd), eq(newPayment), eq(new BigDecimal("100.00")), eq(false)));
    finAddPaymentMock.verify(() -> FIN_AddPayment.processPayment(
        any(), any(), eq("P"), eq(newPayment), eq("")), times(1));
    finAddPaymentMock.verify(
        () -> FIN_AddPayment.createRefundPayment(any(), any(), any(), any(), any()), never());
  }

  // ════════════════════════════════════════════════════════════════════════
  // doRegisterPaymentAdvanced - multi-currency (conversion rate)
  // ════════════════════════════════════════════════════════════════════════

  @Test
  @DisplayName("A foreign-currency account applies the supplied rate to the financial transaction "
      + "amount (100 USD x 0.92 = 92.00 EUR) and no longer throws")
  void testAdvancedForeignCurrencyAppliesConversionRate() throws Exception {
    stubAdvancedBasics();
    stubPendingPSDs(new BigDecimal("100.00"));

    // Account is EUR (precision 2), invoice is USD → foreign: the modal sends conversionRate=0.92.
    Currency accountCurrency = mock(Currency.class);
    when(accountCurrency.getId()).thenReturn("EUR-ID");
    when(accountCurrency.getStandardPrecision()).thenReturn(2L);
    when(account.getCurrency()).thenReturn(accountCurrency);

    JSONObject body = advancedBody("100.00", CONFIRM).put("conversionRate", "0.92");

    NeoResponse response = PaymentRegistrationService.doRegisterPaymentAdvanced(
        INVOICE_ID, body, true);

    assertEquals(201, response.getHttpStatus());
    // Payment amount stays in invoice currency (100.00); the financial-transaction amount is the
    // account-currency conversion 100.00 x 0.92 = 92.00, rounded to the account precision (2).
    assertVerbatimTransactionFields(newPayment, "0.92", "92.00");
    // The payment is created in the invoice currency with the supplied rate + converted amount.
    AdvPaymentMngtDao dao = daoConstruction.constructed().get(0);
    verify(dao).getNewPayment(anyBoolean(), any(), any(), any(), any(), any(), any(), any(),
        any(), any(), eq(currency), eq(new BigDecimal("0.92")), eq(new BigDecimal("92.00")));
  }

  /**
   * ETP-4841 regression: the ticket's own numbers. A 58.70 (invoice currency) payment at a typed
   * rate of 0.89 converts to 52.24 in a 2-decimal account currency, and that rounding is exactly
   * what {@code FIN_AddPayment.setFinancialTransactionAmountAndRate} used to "correct" by
   * recomputing 52.24 / 58.70 = 0.889948892674617 — so reopening the draft no longer showed the
   * 0.89 the user typed. The rate must now be stored byte-for-byte as typed.
   */
  @Test
  @DisplayName("ETP-4841: a typed rate whose converted amount needs rounding is stored VERBATIM "
      + "(58.70 x 0.89 -> 52.24, rate stays 0.89 and is never recomputed to 0.8899...)")
  void testAdvancedForeignCurrencyStoresTypedRateVerbatimDespiteRounding() throws Exception {
    stubAdvancedBasics();
    stubPendingPSDs(new BigDecimal("58.70"));
    stubForeignEurAccount();

    JSONObject body = advancedBody("58.70", DRAFT).put("conversionRate", "0.89");

    NeoResponse response = PaymentRegistrationService.doRegisterPaymentAdvanced(
        INVOICE_ID, body, true);

    assertEquals(201, response.getHttpStatus());
    assertVerbatimTransactionFields(newPayment, "0.89", "52.24");

    // Explicit anti-regression assertion: the stored rate is the typed one, NOT the value the core
    // helper would have derived from the rounded transaction amount.
    ArgumentCaptor<BigDecimal> rateCaptor = ArgumentCaptor.forClass(BigDecimal.class);
    verify(newPayment).setFinancialTransactionConvertRate(rateCaptor.capture());
    assertEquals(0, new BigDecimal("0.89").compareTo(rateCaptor.getValue()));
    assertTrue(new BigDecimal("52.24").divide(new BigDecimal("58.70"), MathContext.DECIMAL64)
            .compareTo(rateCaptor.getValue()) != 0,
        "the stored rate must not be the core helper's txnAmount/amount recomputation");
  }

  @Test
  @DisplayName("A same-currency account defaults the conversion rate to ONE and keeps the "
      + "transaction amount equal to the payment amount (verbatim write, ETP-4841)")
  void testAdvancedSameCurrencyDefaultsRateToOne() throws Exception {
    stubAdvancedBasics();
    stubPendingPSDs(new BigDecimal("100.00"));
    // Account currency == invoice currency, no conversionRate in the body → rate ONE. The shared
    // currency declares 2 decimals, like a real EUR/USD account, so the conversion is a no-op.
    when(currency.getStandardPrecision()).thenReturn(2L);

    JSONObject body = advancedBody("100.00", CONFIRM);

    NeoResponse response = PaymentRegistrationService.doRegisterPaymentAdvanced(
        INVOICE_ID, body, true);

    assertEquals(201, response.getHttpStatus());
    // Single-currency behavior is unchanged by the verbatim write: rate ONE and a transaction
    // amount equal to the payment amount (100.00 x 1, rescaled to the account's own 2 decimals).
    assertVerbatimTransactionFields(newPayment, "1", "100.00");
  }

  // ════════════════════════════════════════════════════════════════════════
  // doRegisterPaymentAdvanced - edit-in-place (paymentId) rate handling
  // ════════════════════════════════════════════════════════════════════════

  /**
   * ETP-4841, edit path: reopening a draft and re-sending the same rate must rewrite it verbatim on
   * the SAME {@link FIN_Payment} row, so a second save does not drift the value the user typed.
   */
  @Test
  @DisplayName("Editing a draft with the same rate rewrites rate + transaction amount VERBATIM on "
      + "the same payment row (no new payment is created)")
  void testAdvancedEditDraftReappliesSameRateVerbatim() throws Exception {
    stubAdvancedBasics();
    stubForeignEurAccount();
    FIN_Payment draft = stubExistingDraft(new BigDecimal("58.70"));

    JSONObject body = advancedBody("58.70", DRAFT)
        .put("conversionRate", "0.89")
        .put("paymentId", DRAFT_PAY_ID);

    NeoResponse response = PaymentRegistrationService.doRegisterPaymentAdvanced(
        INVOICE_ID, body, true);

    assertEquals(201, response.getHttpStatus());
    assertEquals(DRAFT_PAY_ID, response.getBody().getJSONObject("response")
        .getJSONObject("data").getString("id"), "the edit must reuse the same payment row");
    assertVerbatimTransactionFields(draft, "0.89", "52.24");
    // The draft is reused, never re-created.
    AdvPaymentMngtDao dao = daoConstruction.constructed().get(0);
    verify(dao, never()).getNewPayment(anyBoolean(), any(), any(), any(), any(), any(), any(),
        any(), any(), any(), any(), any(), any());
  }

  /**
   * ETP-4841, edit path: changing the rate on a draft must persist the NEW typed rate (and its own
   * converted amount) verbatim — 58.70 x 0.95 = 55.7650 → 55.77 at the account's 2 decimals, whose
   * recomputation (55.77 / 58.70) would again have drifted the rate.
   */
  @Test
  @DisplayName("Editing a draft with a CHANGED rate stores the new rate verbatim (0.95 -> 55.77)")
  void testAdvancedEditDraftAppliesChangedRateVerbatim() throws Exception {
    stubAdvancedBasics();
    stubForeignEurAccount();
    FIN_Payment draft = stubExistingDraft(new BigDecimal("58.70"));

    JSONObject body = advancedBody("58.70", DRAFT)
        .put("conversionRate", "0.95")
        .put("paymentId", DRAFT_PAY_ID);

    NeoResponse response = PaymentRegistrationService.doRegisterPaymentAdvanced(
        INVOICE_ID, body, true);

    assertEquals(201, response.getHttpStatus());
    assertVerbatimTransactionFields(draft, "0.95", "55.77");
  }

  @Test
  @DisplayName("Editing a draft returns 404 when its paymentId does not resolve to a payment")
  void testAdvancedEditDraftUnknownPaymentIdReturns404() throws Exception {
    stubAdvancedBasics();
    when(dal.get(FIN_Payment.class, DRAFT_PAY_ID)).thenReturn(null);

    JSONObject body = advancedBody("58.70", DRAFT).put("paymentId", DRAFT_PAY_ID);

    NeoResponse response = PaymentRegistrationService.doRegisterPaymentAdvanced(
        INVOICE_ID, body, true);

    assertEquals(404, response.getHttpStatus());
    finAddPaymentMock.verify(() -> FIN_AddPayment.processPayment(
        any(), any(), anyString(), any(), anyString()), never());
  }

  @Test
  @DisplayName("Editing an already-processed payment is rejected before any field is rewritten")
  void testAdvancedEditProcessedPaymentThrows() throws Exception {
    stubAdvancedBasics();
    FIN_Payment processed = mock(FIN_Payment.class);
    when(processed.isProcessed()).thenReturn(true);
    when(dal.get(FIN_Payment.class, DRAFT_PAY_ID)).thenReturn(processed);

    JSONObject body = advancedBody("58.70", DRAFT).put("paymentId", DRAFT_PAY_ID);

    OBException ex = assertThrows(OBException.class,
        () -> PaymentRegistrationService.doRegisterPaymentAdvanced(INVOICE_ID, body, true));
    assertEquals("Cannot edit a processed payment", ex.getMessage());
    verify(processed, never()).setFinancialTransactionConvertRate(any());
  }

  @Test
  @DisplayName("A non-positive conversion rate is rejected with 400 before any payment is created")
  void testAdvancedRejectsNonPositiveConversionRate() throws Exception {
    stubAdvancedBasics();

    JSONObject body = advancedBody("100.00", CONFIRM).put("conversionRate", "0");

    NeoResponse response = PaymentRegistrationService.doRegisterPaymentAdvanced(
        INVOICE_ID, body, true);

    assertEquals(400, response.getHttpStatus());
    finAddPaymentMock.verify(() -> FIN_AddPayment.processPayment(
        any(), any(), anyString(), any(), anyString()), never());
  }

  @Test
  @DisplayName("A malformed conversion rate is rejected with 400")
  void testAdvancedRejectsMalformedConversionRate() throws Exception {
    stubAdvancedBasics();

    JSONObject body = advancedBody("100.00", CONFIRM).put("conversionRate", "abc");

    NeoResponse response = PaymentRegistrationService.doRegisterPaymentAdvanced(
        INVOICE_ID, body, true);

    assertEquals(400, response.getHttpStatus());
    finAddPaymentMock.verify(() -> FIN_AddPayment.processPayment(
        any(), any(), anyString(), any(), anyString()), never());
  }

  @Test
  @DisplayName("Defense-in-depth (B1): a foreign-currency payment with NO conversion rate is "
      + "rejected with 400 instead of silently booking amount x 1")
  void testAdvancedForeignCurrencyMissingRateRejected() throws Exception {
    stubAdvancedBasics();
    // Account is EUR, invoice is the global USD currency → foreign, but no conversionRate sent.
    Currency accountCurrency = mock(Currency.class);
    when(accountCurrency.getId()).thenReturn("EUR-ID");
    when(account.getCurrency()).thenReturn(accountCurrency);

    JSONObject body = advancedBody("100.00", CONFIRM); // conversionRate absent

    NeoResponse response = PaymentRegistrationService.doRegisterPaymentAdvanced(
        INVOICE_ID, body, true);

    assertEquals(400, response.getHttpStatus());
    finAddPaymentMock.verify(() -> FIN_AddPayment.processPayment(
        any(), any(), anyString(), any(), anyString()), never());
  }

  @Test
  @DisplayName("Defense-in-depth (B1): a foreign-currency payment with an explicit rate of ONE is "
      + "rejected with 400 (placeholder value, not a real cross-currency rate)")
  void testAdvancedForeignCurrencyRateOfOneRejected() throws Exception {
    stubAdvancedBasics();
    Currency accountCurrency = mock(Currency.class);
    when(accountCurrency.getId()).thenReturn("EUR-ID");
    when(account.getCurrency()).thenReturn(accountCurrency);

    JSONObject body = advancedBody("100.00", CONFIRM).put("conversionRate", "1");

    NeoResponse response = PaymentRegistrationService.doRegisterPaymentAdvanced(
        INVOICE_ID, body, true);

    assertEquals(400, response.getHttpStatus());
    finAddPaymentMock.verify(() -> FIN_AddPayment.processPayment(
        any(), any(), anyString(), any(), anyString()), never());
  }

  // ════════════════════════════════════════════════════════════════════════
  // doRegisterPaymentAdvanced - credit consumption
  // ════════════════════════════════════════════════════════════════════════

  @Test
  @DisplayName("Consuming accumulated credit bumps source usedCredit, links FIN_Payment_Credit, "
      + "and pays the invoice with zero cash")
  void testAdvancedConsumesAccumulatedCredit() throws Exception {
    stubAdvancedBasics();
    FIN_PaymentScheduleDetail psd = stubPendingPSDs(new BigDecimal("100.00"));

    FIN_Payment creditSource = mock(FIN_Payment.class);
    when(creditSource.getUsedCredit()).thenReturn(new BigDecimal("10"));
    when(dal.get(FIN_Payment.class, CREDIT_PAY_ID)).thenReturn(creditSource);

    // cash 0, credit 100 → fully funded by credit
    JSONArray sources = new JSONArray(Collections.singletonList(
        new JSONObject().put("kind", KIND_CREDIT).put("paymentId", CREDIT_PAY_ID).put("use", "100")));
    JSONObject body = advancedBody("0", CONFIRM).put("creditSources", sources);

    NeoResponse response = PaymentRegistrationService.doRegisterPaymentAdvanced(
        INVOICE_ID, body, true);

    assertEquals(201, response.getHttpStatus());
    // source usedCredit increased: prev 10 + 100 = 110
    verify(creditSource).setUsedCredit(new BigDecimal("110"));
    // a FIN_Payment_Credit link is created on the new payment for the consumed amount
    finPaymentProcessMock.verify(() -> FIN_PaymentProcess.linkCreditPayment(
        eq(newPayment), eq(new BigDecimal("100")), eq(creditSource)));
    // invoice settled with funds = cash(0) + credit(100) = 100
    finAddPaymentMock.verify(() -> FIN_AddPayment.updatePaymentDetail(
        eq(psd), eq(newPayment), eq(new BigDecimal("100.00")), eq(false)));
  }

  @Test
  @DisplayName("Consuming an abono (any negative-total invoice, ETP-4841) links it as a negative "
      + "payment detail")
  void testAdvancedConsumesAbonoAsNegativeDetail() throws Exception {
    stubAdvancedBasics();
    stubPendingPSDs(new BigDecimal("100.00"));

    FIN_PaymentScheduleDetail abonoPsd = mock(FIN_PaymentScheduleDetail.class);
    when(dal.get(FIN_PaymentScheduleDetail.class, ABONO_PSD_ID)).thenReturn(abonoPsd);
    // ETP-4841 eligibility guard: the abono's invoice only has to carry a NEGATIVE total — its
    // document type is deliberately never consulted, so none is stubbed here.
    FIN_PaymentSchedule abonoSchedule = mock(FIN_PaymentSchedule.class);
    Invoice abonoInvoice = mock(Invoice.class);
    when(abonoInvoice.getGrandTotalAmount()).thenReturn(new BigDecimal("-30.00"));
    when(abonoSchedule.getInvoice()).thenReturn(abonoInvoice);
    when(abonoPsd.getInvoicePaymentSchedule()).thenReturn(abonoSchedule);

    JSONArray sources = new JSONArray(Collections.singletonList(
        new JSONObject().put("kind", KIND_ABONO).put("psdId", ABONO_PSD_ID).put("use", "30")));
    JSONObject body = advancedBody("70.00", CONFIRM).put("creditSources", sources);

    NeoResponse response = PaymentRegistrationService.doRegisterPaymentAdvanced(
        INVOICE_ID, body, true);

    assertEquals(201, response.getHttpStatus());
    // abono linked as a NEGATIVE detail: use.negate() == -30
    finAddPaymentMock.verify(() -> FIN_AddPayment.updatePaymentDetail(
        eq(abonoPsd), eq(newPayment), eq(new BigDecimal("-30")), eq(false)));
    // abono must NOT be consumed via the credit (used-credit) path
    finPaymentProcessMock.verify(
        () -> FIN_PaymentProcess.linkCreditPayment(any(), any(), any()), never());
  }

  // ════════════════════════════════════════════════════════════════════════
  // doRegisterPaymentAdvanced - over-payment
  // ════════════════════════════════════════════════════════════════════════

  @Test
  @DisplayName("Over-payment with leave-credit registers credit and does not refund")
  void testAdvancedOverpaymentLeaveCredit() throws Exception {
    stubAdvancedBasics();
    stubPendingPSDs(new BigDecimal("100.00"));

    // cash 150 against a 100 installment → 50 leftover as generated credit
    JSONObject body = advancedBody("150.00", CONFIRM).put("overpaymentAction", "leave-credit");

    NeoResponse response = PaymentRegistrationService.doRegisterPaymentAdvanced(
        INVOICE_ID, body, true);

    assertEquals(201, response.getHttpStatus());
    // leftover registered as a generated-credit detail via the dao
    AdvPaymentMngtDao dao = daoConstruction.constructed().get(0);
    verify(dao).getNewPaymentScheduleDetail(eq(org), eq(new BigDecimal("50.00")));
    finAddPaymentMock.verify(
        () -> FIN_AddPayment.createRefundPayment(any(), any(), any(), any(), any()), never());
  }

  @Test
  @DisplayName("Over-payment with refund creates and processes a refund payment for the leftover")
  void testAdvancedOverpaymentRefund() throws Exception {
    stubAdvancedBasics();
    stubPendingPSDs(new BigDecimal("100.00"));

    JSONObject body = advancedBody("150.00", CONFIRM).put("overpaymentAction", "refund");

    NeoResponse response = PaymentRegistrationService.doRegisterPaymentAdvanced(
        INVOICE_ID, body, true);

    assertEquals(201, response.getHttpStatus());
    // refund created for the negated leftover (-50.00) then processed
    finAddPaymentMock.verify(() -> FIN_AddPayment.createRefundPayment(
        any(), any(), eq(newPayment), eq(new BigDecimal("-50.00")), any()));
    finAddPaymentMock.verify(() -> FIN_AddPayment.processPayment(
        any(), any(), eq("P"), eq(refundPayment), eq(""), anyString()));
  }

  // ════════════════════════════════════════════════════════════════════════
  // doRegisterPaymentAdvanced - PIS (bank transfer) branch
  // ════════════════════════════════════════════════════════════════════════

  /**
   * When {@code pis=true} and the account/method/currency are PIS-eligible, the confirm flow
   * dispatches to {@code PisDeferredPaymentService.initiateDeferredPis}: it creates no payment at
   * all, initiates the Salt Edge transfer and returns the PIS payment URL, the local PIS payment
   * id and status "requested".
   */
  @Test
  @DisplayName("PIS confirm starts the bank transfer and creates no payment")
  @SuppressWarnings("unchecked")
  void testAdvancedPisConfirmInitiatesBankTransfer() throws Exception {
    stubAdvancedBasics();
    stubPendingPSDs(new BigDecimal("100.00"));

    // PIS eligibility: bank-connected account, transfer method, EUR invoice.
    when(account.getPSD2ConnectionStatus())
        .thenReturn(BankIntegrationConstants.FA_CONNECTION_STATUS_CONNECTED);
    when(method.getName()).thenReturn("Bank Transfer");
    when(currency.getISOCode()).thenReturn("EUR");

    // nextEndToEndId counts this invoice's previous attempts to build a unique bank reference.
    when(invoice.getDocumentNo()).thenReturn("INV-77");
    OBCriteria<PisPayment> attemptCrit = mock(OBCriteria.class);
    when(dal.createCriteria(PisPayment.class)).thenReturn(attemptCrit);
    when(attemptCrit.add(any(Criterion.class))).thenReturn(attemptCrit);
    when(attemptCrit.count()).thenReturn(0);

    BankIntegrationPISUtils.PISCreatePaymentResult bridgeResult =
        mock(BankIntegrationPISUtils.PISCreatePaymentResult.class);
    when(bridgeResult.getPaymentId()).thenReturn("se-adv-1");
    when(bridgeResult.getPaymentUrl()).thenReturn("https://sca.saltedge/adv");
    PisPayment localPis = mock(PisPayment.class);
    when(localPis.getId()).thenReturn("local-adv-1");
    when(localPis.getStatus()).thenReturn("requested");

    JSONObject body = advancedBody("100.00", CONFIRM)
        .put("pis", true)
        .put("pisTemplate", "SEPA")
        .put("pisCreditorIban", "ES9121000418450200051332");

    try (MockedStatic<PisPaymentBridge> bridgeMock = mockStatic(PisPaymentBridge.class);
         MockedStatic<PISPaymentDao> pisDaoMock = mockStatic(PISPaymentDao.class)) {
      bridgeMock.when(() -> PisPaymentBridge.initiateDeferredPisPayment(
          eq(invoice), eq(account), any(), anyString(), any(), any())).thenReturn(bridgeResult);
      pisDaoMock.when(() -> PISPaymentDao.findBySaltedgePaymentId("se-adv-1")).thenReturn(localPis);

      NeoResponse response = PaymentRegistrationService.doRegisterPaymentAdvanced(
          INVOICE_ID, body, true);

      assertEquals(201, response.getHttpStatus());
      JSONObject data = response.getBody().getJSONObject("response").getJSONObject("data");
      assertEquals("https://sca.saltedge/adv", data.getString("pisPaymentUrl"));
      assertEquals("local-adv-1", data.getString("pisPaymentId"));
      assertTrue(data.getBoolean("paymentDeferred"));

      // The point of the whole change: confirming a bank transfer registers NOTHING. The payment
      // is built later, only once Salt Edge reports the bank committed to the transfer.
      finAddPaymentMock.verify(() -> FIN_AddPayment.processPayment(
          any(), any(), anyString(), any(), anyString()), never());
      // The request is snapshotted instead, so it can be replayed when that happens.
      verify(localPis).setETGOPaymentIntent(anyString());
    }
  }

  /**
   * A {@code pis=true} confirm against an account with no bank connection fails eligibility before any
   * payment is processed: {@code validatePisEligibility} throws {@link OBException}.
   */
  @Test
  @DisplayName("PIS confirm rejects an account with no bank connection before processing")
  void testAdvancedPisRejectsUnconnectedAccount() throws Exception {
    stubAdvancedBasics();
    when(account.getPSD2ConnectionStatus()).thenReturn("NC");
    when(method.getName()).thenReturn("Bank Transfer");
    when(currency.getISOCode()).thenReturn("EUR");

    JSONObject body = advancedBody("100.00", CONFIRM).put("pis", true);

    assertThrows(OBException.class,
        () -> PaymentRegistrationService.doRegisterPaymentAdvanced(INVOICE_ID, body, true));
    finAddPaymentMock.verify(() -> FIN_AddPayment.processPayment(
        any(), any(), anyString(), any(), anyString()), never());
  }

  @Test
  @DisplayName("Advanced register rejects an empty installment with no pending PSDs")
  void testAdvancedEmptyPendingPsdsThrows() {
    stubAdvancedBasics();
    stubPendingPSDs(); // empty

    JSONObject body = advancedBody("100.00", CONFIRM);

    OBException ex = assertThrows(OBException.class,
        () -> PaymentRegistrationService.doRegisterPaymentAdvanced(INVOICE_ID, body, true));
    assertTrue(ex.getMessage().contains("No pending payment schedule details"));
  }

  // ════════════════════════════════════════════════════════════════════════
  // confirmDraftPayment
  // ════════════════════════════════════════════════════════════════════════

  @Test
  @DisplayName("confirmDraftPayment processes the saved draft and returns its status")
  void testConfirmDraftPaymentProcesses() throws Exception {
    when(dal.get(FIN_Payment.class, NEW_PAY_ID)).thenReturn(newPayment);
    when(newPayment.isProcessed()).thenReturn(true);

    NeoResponse response = PaymentDraftEditService.confirmDraftPayment(NEW_PAY_ID);

    assertEquals(201, response.getHttpStatus());
    finAddPaymentMock.verify(() -> FIN_AddPayment.processPayment(
        any(), any(), eq("P"), eq(newPayment), eq("")), times(1));
    assertTrue(response.getBody().getJSONObject("response").getJSONObject("data")
        .getBoolean("processed"));
  }

  @Test
  @DisplayName("confirmDraftPayment returns 404 when the payment does not exist")
  void testConfirmDraftPaymentNotFound() throws Exception {
    when(dal.get(FIN_Payment.class, "missing")).thenReturn(null);

    NeoResponse response = PaymentDraftEditService.confirmDraftPayment("missing");

    assertEquals(404, response.getHttpStatus());
    finAddPaymentMock.verify(
        () -> FIN_AddPayment.processPayment(any(), any(), anyString(), any(), anyString()),
        never());
  }

  @Test
  @DisplayName("confirmDraftPayment surfaces a processing error as an exception")
  void testConfirmDraftPaymentProcessingError() {
    when(dal.get(FIN_Payment.class, NEW_PAY_ID)).thenReturn(newPayment);
    OBError error = mock(OBError.class);
    when(error.getType()).thenReturn(ERROR_TYPE);
    when(error.getMessage()).thenReturn("boom");
    finAddPaymentMock.when(() -> FIN_AddPayment.processPayment(any(), any(), anyString(), any(), anyString()))
        .thenReturn(error);

    OBException ex = assertThrows(OBException.class,
        () -> PaymentDraftEditService.confirmDraftPayment(NEW_PAY_ID));
    assertEquals("boom", ex.getMessage());
  }

  // ════════════════════════════════════════════════════════════════════════
  // deleteDraftPayment / releaseInstallmentDetails
  // ════════════════════════════════════════════════════════════════════════

  @Test
  @DisplayName("deleteDraftPayment returns 404 when the payment does not exist")
  void testDeleteDraftPaymentNotFoundReturns404() {
    when(dal.get(FIN_Payment.class, "missing")).thenReturn(null);

    NeoResponse response = PaymentDraftEditService.deleteDraftPayment("missing");

    assertEquals(404, response.getHttpStatus());
    paymentRemovalUtilMock.verify(() -> PaymentRemovalUtil.remove(any()), never());
  }

  @Test
  @DisplayName("deleteDraftPayment rejects an already-processed payment")
  void testDeleteDraftPaymentProcessedThrows() {
    when(dal.get(FIN_Payment.class, NEW_PAY_ID)).thenReturn(newPayment);
    when(newPayment.isProcessed()).thenReturn(true);

    OBException ex = assertThrows(OBException.class,
        () -> PaymentDraftEditService.deleteDraftPayment(NEW_PAY_ID));

    assertEquals("Cannot delete a processed payment", ex.getMessage());
    paymentRemovalUtilMock.verify(() -> PaymentRemovalUtil.remove(any()), never());
  }

  @Test
  @DisplayName("Deleting a draft with only the document installment PSD zeroes and detaches it, "
      + "then removes the payment")
  void testDeleteDraftPaymentReleasesDocumentInstallmentAndRemovesPayment() {
    when(dal.get(FIN_Payment.class, NEW_PAY_ID)).thenReturn(newPayment);
    when(newPayment.isProcessed()).thenReturn(false);

    FIN_PaymentScheduleDetail documentPsd = mock(FIN_PaymentScheduleDetail.class);
    when(documentPsd.getInvoicePaymentSchedule()).thenReturn(schedule);

    FIN_PaymentDetail detail = mock(FIN_PaymentDetail.class);
    List<FIN_PaymentScheduleDetail> psdList = new ArrayList<>(Collections.singletonList(documentPsd));
    when(detail.getFINPaymentScheduleDetailList()).thenReturn(psdList);

    List<FIN_PaymentDetail> detailList = new ArrayList<>(Collections.singletonList(detail));
    when(newPayment.getFINPaymentDetailList()).thenReturn(detailList);

    stubNoConsumedCredit();

    NeoResponse response = PaymentDraftEditService.deleteDraftPayment(NEW_PAY_ID);

    assertEquals(204, response.getHttpStatus());
    finAddPaymentMock.verify(() -> FIN_AddPayment.updatePaymentDetail(
        eq(documentPsd), eq(newPayment), eq(BigDecimal.ZERO), eq(false)));
    verify(dal).remove(documentPsd);
    verify(dal).remove(detail);
    assertTrue(detailList.isEmpty(), "the document detail must be detached from the payment");
    paymentRemovalUtilMock.verify(() -> PaymentRemovalUtil.remove(newPayment));
  }

  @Test
  @DisplayName("Deleting a draft that consumed accumulated credit restores usedCredit and removes "
      + "the FIN_Payment_Credit link")
  void testDeleteDraftPaymentReversesConsumedCredit() {
    when(dal.get(FIN_Payment.class, NEW_PAY_ID)).thenReturn(newPayment);
    when(newPayment.isProcessed()).thenReturn(false);
    when(newPayment.getFINPaymentDetailList()).thenReturn(new ArrayList<>());

    FIN_Payment creditSource = mock(FIN_Payment.class);
    when(creditSource.getUsedCredit()).thenReturn(new BigDecimal("110"));

    FIN_Payment_Credit link = mock(FIN_Payment_Credit.class);
    when(link.getCreditPaymentUsed()).thenReturn(creditSource);
    when(link.getAmount()).thenReturn(new BigDecimal("100"));

    OBCriteria<FIN_Payment_Credit> crit = mock(OBCriteria.class);
    when(dal.createCriteria(FIN_Payment_Credit.class)).thenReturn(crit);
    when(crit.add(any(Criterion.class))).thenReturn(crit);
    when(crit.list()).thenReturn(Collections.singletonList(link));

    NeoResponse response = PaymentDraftEditService.deleteDraftPayment(NEW_PAY_ID);

    assertEquals(204, response.getHttpStatus());
    verify(creditSource).setUsedCredit(new BigDecimal("10"));
    verify(dal).remove(link);
    paymentRemovalUtilMock.verify(() -> PaymentRemovalUtil.remove(newPayment));
  }

  @Test
  @DisplayName("Deleting a draft removes its payment-owned (credit/refund) schedule details")
  void testDeleteDraftPaymentRemovesCreditOwnedDetails() {
    when(dal.get(FIN_Payment.class, NEW_PAY_ID)).thenReturn(newPayment);
    when(newPayment.isProcessed()).thenReturn(false);

    // Payment-owned: neither an invoice nor an order schedule — e.g. a generated-credit detail.
    FIN_PaymentScheduleDetail creditOwnedPsd = mock(FIN_PaymentScheduleDetail.class);

    FIN_PaymentDetail detail = mock(FIN_PaymentDetail.class);
    List<FIN_PaymentScheduleDetail> psdList = new ArrayList<>(Collections.singletonList(creditOwnedPsd));
    when(detail.getFINPaymentScheduleDetailList()).thenReturn(psdList);

    List<FIN_PaymentDetail> detailList = new ArrayList<>(Collections.singletonList(detail));
    when(newPayment.getFINPaymentDetailList()).thenReturn(detailList);

    stubNoConsumedCredit();

    NeoResponse response = PaymentDraftEditService.deleteDraftPayment(NEW_PAY_ID);

    assertEquals(204, response.getHttpStatus());
    finAddPaymentMock.verify(() -> FIN_AddPayment.updatePaymentDetail(
        any(), any(), any(), anyBoolean()), never());
    verify(dal).remove(creditOwnedPsd);
    verify(dal).remove(detail);
    assertTrue(detailList.isEmpty(), "the credit-owned detail must be detached from the payment");
  }

  @Test
  @DisplayName("Deleting a draft with both a document PSD and a credit-owned PSD processes both "
      + "without double-processing or crashing")
  void testDeleteDraftPaymentHandlesMixedDocumentAndCreditOwnedDetails() {
    when(dal.get(FIN_Payment.class, NEW_PAY_ID)).thenReturn(newPayment);
    when(newPayment.isProcessed()).thenReturn(false);

    FIN_PaymentScheduleDetail documentPsd = mock(FIN_PaymentScheduleDetail.class);
    when(documentPsd.getInvoicePaymentSchedule()).thenReturn(schedule);
    FIN_PaymentDetail documentDetail = mock(FIN_PaymentDetail.class);
    List<FIN_PaymentScheduleDetail> documentPsdList =
        new ArrayList<>(Collections.singletonList(documentPsd));
    when(documentDetail.getFINPaymentScheduleDetailList()).thenReturn(documentPsdList);

    // Payment-owned: neither an invoice nor an order schedule.
    FIN_PaymentScheduleDetail creditOwnedPsd = mock(FIN_PaymentScheduleDetail.class);
    FIN_PaymentDetail creditOwnedDetail = mock(FIN_PaymentDetail.class);
    List<FIN_PaymentScheduleDetail> creditOwnedPsdList =
        new ArrayList<>(Collections.singletonList(creditOwnedPsd));
    when(creditOwnedDetail.getFINPaymentScheduleDetailList()).thenReturn(creditOwnedPsdList);

    List<FIN_PaymentDetail> detailList =
        new ArrayList<>(Arrays.asList(documentDetail, creditOwnedDetail));
    when(newPayment.getFINPaymentDetailList()).thenReturn(detailList);

    stubNoConsumedCredit();

    NeoResponse response = PaymentDraftEditService.deleteDraftPayment(NEW_PAY_ID);

    assertEquals(204, response.getHttpStatus());
    finAddPaymentMock.verify(() -> FIN_AddPayment.updatePaymentDetail(
        eq(documentPsd), eq(newPayment), eq(BigDecimal.ZERO), eq(false)), times(1));
    verify(dal).remove(documentPsd);
    verify(dal).remove(documentDetail);
    verify(dal).remove(creditOwnedPsd);
    verify(dal).remove(creditOwnedDetail);
    assertTrue(detailList.isEmpty(), "both details must be detached after the mixed cleanup");
    paymentRemovalUtilMock.verify(() -> PaymentRemovalUtil.remove(newPayment));
  }

  // ════════════════════════════════════════════════════════════════════════
  // creditSourcesUsedByPayment (via handleListPayments / paymentListItem)
  // ════════════════════════════════════════════════════════════════════════

  @Test
  @DisplayName("A draft consuming both an accumulated-credit source and an abono lists both as "
      + "creditSourcesUsed")
  void testHandleListPaymentsIncludesCreditSourcesUsedForDraft() throws Exception {
    stubNoPisPaymentLinked();

    FIN_Payment draft = mock(FIN_Payment.class);
    when(draft.getId()).thenReturn(NEW_PAY_ID);
    when(draft.getDocumentNo()).thenReturn("PAY-DRAFT");
    when(draft.getAmount()).thenReturn(new BigDecimal("70.00"));
    when(draft.getStatus()).thenReturn("RPR");
    when(draft.isProcessed()).thenReturn(false);
    when(draft.isReceipt()).thenReturn(true);
    // ETP-4841: the draft carries the rate the user typed in the modal; the row must expose it so
    // the edit modal reseeds its rate field from the draft instead of the system spot rate.
    when(draft.getFinancialTransactionConvertRate()).thenReturn(new BigDecimal("0.89"));

    FIN_Payment creditSourcePayment = mock(FIN_Payment.class);
    when(creditSourcePayment.getId()).thenReturn(CREDIT_PAY_ID);
    FIN_Payment_Credit link = mock(FIN_Payment_Credit.class);
    when(link.getCreditPaymentUsed()).thenReturn(creditSourcePayment);
    when(link.getAmount()).thenReturn(new BigDecimal("40"));
    OBCriteria<FIN_Payment_Credit> creditCrit = mock(OBCriteria.class);
    when(dal.createCriteria(FIN_Payment_Credit.class)).thenReturn(creditCrit);
    when(creditCrit.add(any(Criterion.class))).thenReturn(creditCrit);
    when(creditCrit.list()).thenReturn(Collections.singletonList(link));

    FIN_PaymentScheduleDetail abonoDetail = mock(FIN_PaymentScheduleDetail.class);
    when(abonoDetail.getId()).thenReturn(ABONO_PSD_ID);
    when(abonoDetail.getAmount()).thenReturn(new BigDecimal("-30"));
    FIN_PaymentDetail detail = mock(FIN_PaymentDetail.class);
    when(detail.getFINPaymentScheduleDetailList())
        .thenReturn(Collections.singletonList(abonoDetail));
    when(draft.getFINPaymentDetailList()).thenReturn(Collections.singletonList(detail));

    // Reused generic FIN_Payment session-query stub (same shape as stubCreditQuery).
    stubCreditQuery(Collections.singletonList(draft));

    NeoResponse response = PaymentRegistrationService.handleListPayments(listPaymentsContext());

    assertEquals(200, response.getHttpStatus());
    JSONArray data = response.getBody().getJSONObject("response").getJSONArray("data");
    assertEquals(1, data.length());
    JSONObject draftItem = data.getJSONObject(0);
    assertFalse(draftItem.getBoolean("processed"));
    assertEquals(0, new BigDecimal("0.89")
            .compareTo(new BigDecimal(draftItem.getString("conversionRate"))),
        "the row must expose the draft's own stored conversion rate (ETP-4841)");
    JSONArray used = draftItem.getJSONArray("creditSourcesUsed");
    assertEquals(2, used.length());

    JSONObject creditUsed = used.getJSONObject(0);
    assertEquals(KIND_CREDIT, creditUsed.getString("kind"));
    assertEquals(CREDIT_PAY_ID, creditUsed.getString("paymentId"));
    assertEquals(0, new BigDecimal("40").compareTo(new BigDecimal(creditUsed.getString("use"))));

    JSONObject abonoUsed = used.getJSONObject(1);
    assertEquals(KIND_ABONO, abonoUsed.getString("kind"));
    assertEquals(ABONO_PSD_ID, abonoUsed.getString("psdId"));
    assertEquals(0, new BigDecimal("30").compareTo(new BigDecimal(abonoUsed.getString("use"))));
  }

  @Test
  @DisplayName("A draft with no consumed credit or abono sources lists an empty creditSourcesUsed array")
  void testHandleListPaymentsCreditSourcesUsedEmptyWhenNoneConsumed() throws Exception {
    stubNoPisPaymentLinked();

    FIN_Payment draft = mock(FIN_Payment.class);
    when(draft.getId()).thenReturn(NEW_PAY_ID);
    when(draft.getDocumentNo()).thenReturn("PAY-DRAFT");
    when(draft.getAmount()).thenReturn(new BigDecimal("50.00"));
    when(draft.getStatus()).thenReturn("RPR");
    when(draft.isProcessed()).thenReturn(false);
    when(draft.isReceipt()).thenReturn(true);
    when(draft.getFINPaymentDetailList()).thenReturn(Collections.emptyList());

    OBCriteria<FIN_Payment_Credit> creditCrit = mock(OBCriteria.class);
    when(dal.createCriteria(FIN_Payment_Credit.class)).thenReturn(creditCrit);
    when(creditCrit.add(any(Criterion.class))).thenReturn(creditCrit);
    when(creditCrit.list()).thenReturn(Collections.emptyList());

    stubCreditQuery(Collections.singletonList(draft));

    NeoResponse response = PaymentRegistrationService.handleListPayments(listPaymentsContext());

    assertEquals(200, response.getHttpStatus());
    JSONObject draftItem = response.getBody().getJSONObject("response")
        .getJSONArray("data").getJSONObject(0);
    assertTrue(draftItem.has("creditSourcesUsed"));
    assertEquals(0, draftItem.getJSONArray("creditSourcesUsed").length());
  }

  @Test
  @DisplayName("A processed payment does not expose creditSourcesUsed")
  void testHandleListPaymentsOmitsCreditSourcesUsedForProcessedPayment() throws Exception {
    stubNoPisPaymentLinked();

    FIN_Payment processed = mock(FIN_Payment.class);
    when(processed.getId()).thenReturn(NEW_PAY_ID);
    when(processed.getDocumentNo()).thenReturn("PAY-1");
    when(processed.getAmount()).thenReturn(new BigDecimal("100.00"));
    when(processed.getStatus()).thenReturn("PPD");
    when(processed.isProcessed()).thenReturn(true);
    when(processed.isReceipt()).thenReturn(true);

    stubCreditQuery(Collections.singletonList(processed));

    NeoResponse response = PaymentRegistrationService.handleListPayments(listPaymentsContext());

    assertEquals(200, response.getHttpStatus());
    JSONObject item = response.getBody().getJSONObject("response")
        .getJSONArray("data").getJSONObject(0);
    assertFalse(item.has("creditSourcesUsed"));
  }

  // ════════════════════════════════════════════════════════════════════════
  // handleListCreditSources with editPaymentId (creditUsedByDraft / abonosUsedByDraft)
  // ════════════════════════════════════════════════════════════════════════

  @Test
  @DisplayName("Editing a draft adds its own consumption back in, so a fully-consumed credit "
      + "source still appears")
  void testListCreditSourcesWithEditPaymentIdIncludesFullyConsumedSource() throws Exception {
    String editPaymentId = "draft-being-edited";
    NeoContext context = creditSourcesContextWithEditPaymentId(editPaymentId);
    stubInvoiceWithBp();
    stubAbonoQuery(Collections.emptyList());

    FIN_Payment fullyConsumed = mock(FIN_Payment.class);
    when(fullyConsumed.getId()).thenReturn(CREDIT_PAY_ID);
    when(fullyConsumed.getDocumentNo()).thenReturn("CR/FULL");
    when(fullyConsumed.getGeneratedCredit()).thenReturn(new BigDecimal("100"));
    when(fullyConsumed.getUsedCredit()).thenReturn(new BigDecimal("100"));
    when(fullyConsumed.getPaymentDate()).thenReturn(null);
    when(fullyConsumed.getDescription()).thenReturn("full");
    stubCreditQuery(Collections.singletonList(fullyConsumed));

    FIN_Payment_Credit link = mock(FIN_Payment_Credit.class);
    when(link.getAmount()).thenReturn(new BigDecimal("40"));
    OBCriteria<FIN_Payment_Credit> linkCrit = mock(OBCriteria.class);
    when(dal.createCriteria(FIN_Payment_Credit.class)).thenReturn(linkCrit);
    when(linkCrit.add(any(Criterion.class))).thenReturn(linkCrit);
    when(linkCrit.setMaxResults(anyInt())).thenReturn(linkCrit);
    when(linkCrit.uniqueResult()).thenReturn(link);

    NeoResponse response = PaymentCreditSourcesService.handleListCreditSources(context, true);

    assertEquals(200, response.getHttpStatus());
    JSONArray items = response.getBody().getJSONArray(ITEMS);
    assertEquals(1, items.length(),
        "the fully-consumed source must still appear once its own draft's use is added back");
    JSONObject item = items.getJSONObject(0);
    assertEquals(KIND_CREDIT, item.getString("kind"));
    assertEquals(CREDIT_PAY_ID, item.getString("paymentId"));
    assertEquals(0, new BigDecimal("40").compareTo(new BigDecimal(item.getString("avail"))));
  }

  @Test
  @DisplayName("Editing a draft relists an abono PSD already linked to it")
  void testListCreditSourcesWithEditPaymentIdRelistsAlreadyLinkedAbono() throws Exception {
    String editPaymentId = "draft-being-edited";
    NeoContext context = creditSourcesContextWithEditPaymentId(editPaymentId);
    stubInvoiceWithBp();
    stubCreditQuery(Collections.emptyList());

    FIN_PaymentScheduleDetail linkedAbono = abonoPsd("abono-linked", new BigDecimal("-20.00"),
        "NC/020", date("2026-05-10"), "Credit Memo");
    stubEditAbonoQueries(Collections.emptyList(), Collections.singletonList(linkedAbono));

    NeoResponse response = PaymentCreditSourcesService.handleListCreditSources(context, true);

    assertEquals(200, response.getHttpStatus());
    JSONArray items = response.getBody().getJSONArray(ITEMS);
    assertEquals(1, items.length());
    JSONObject item = items.getJSONObject(0);
    assertEquals(KIND_ABONO, item.getString("kind"));
    assertEquals("abono-linked", item.getString("psdId"));
  }

  // ════════════════════════════════════════════════════════════════════════
  // Fixtures / helpers
  // ════════════════════════════════════════════════════════════════════════

  private NeoContext creditSourcesContext() {
    return NeoContext.builder()
        .recordId(INVOICE_ID)
        .httpMethod("GET")
        .endpointType(NeoEndpointType.CRUD)
        .build();
  }

  private void stubInvoiceWithBp() {
    when(dal.get(Invoice.class, INVOICE_ID)).thenReturn(invoice);
    when(invoice.getBusinessPartner()).thenReturn(bp);
    when(bp.getId()).thenReturn("bp-1");
  }

  private void stubInvoiceOrgTree() {
    when(dal.get(Invoice.class, INVOICE_ID)).thenReturn(invoice);
    Client client = mock(Client.class);
    when(client.getId()).thenReturn(CLIENT_ID);
    when(invoice.getClient()).thenReturn(client);
    when(invoice.getOrganization()).thenReturn(org);
    org.openbravo.dal.security.OrganizationStructureProvider osp =
        mock(org.openbravo.dal.security.OrganizationStructureProvider.class);
    when(obContext.getOrganizationStructureProvider(CLIENT_ID)).thenReturn(osp);
    when(osp.getNaturalTree(ORG_ID)).thenReturn(
        new java.util.HashSet<>(Collections.singletonList(ORG_ID)));
  }

  @SuppressWarnings("unchecked")
  private void stubAbonoQuery(List<FIN_PaymentScheduleDetail> result) {
    Query<FIN_PaymentScheduleDetail> q = mock(Query.class);
    when(session.createQuery(anyString(), eq(FIN_PaymentScheduleDetail.class))).thenReturn(q);
    when(q.setParameter(anyString(), any())).thenReturn(q);
    when(q.setMaxResults(anyInt())).thenReturn(q);
    when(q.list()).thenReturn(result);
  }

  @SuppressWarnings("unchecked")
  private void stubCreditQuery(List<FIN_Payment> result) {
    Query<FIN_Payment> q = mock(Query.class);
    when(session.createQuery(anyString(), eq(FIN_Payment.class))).thenReturn(q);
    when(q.setParameter(anyString(), any())).thenReturn(q);
    when(q.setMaxResults(anyInt())).thenReturn(q);
    when(q.list()).thenReturn(result);
  }

  /**
   * Stubs the two distinct abono HQL queries used by {@code collectAbonoSources} when editing a
   * draft: the "pending" query (excludes already-linked rows) and {@code abonosUsedByDraft}'s own
   * query (rows already linked to the draft). Matched by a unique substring of each HQL string so
   * they don't collide with the shared, single-mock {@link #stubAbonoQuery}.
   */
  @SuppressWarnings("unchecked")
  private void stubEditAbonoQueries(List<FIN_PaymentScheduleDetail> pending,
      List<FIN_PaymentScheduleDetail> usedByDraft) {
    Query<FIN_PaymentScheduleDetail> pendingQuery = mock(Query.class);
    when(session.createQuery(contains("psd.paymentDetails is null"), eq(FIN_PaymentScheduleDetail.class)))
        .thenReturn(pendingQuery);
    when(pendingQuery.setParameter(anyString(), any())).thenReturn(pendingQuery);
    when(pendingQuery.setMaxResults(anyInt())).thenReturn(pendingQuery);
    when(pendingQuery.list()).thenReturn(pending);

    Query<FIN_PaymentScheduleDetail> usedQuery = mock(Query.class);
    when(session.createQuery(contains("psd.paymentDetails.finPayment.id"), eq(FIN_PaymentScheduleDetail.class)))
        .thenReturn(usedQuery);
    when(usedQuery.setParameter(anyString(), any())).thenReturn(usedQuery);
    when(usedQuery.list()).thenReturn(usedByDraft);
  }

  /** A {@code handleListCreditSources} context carrying {@code editPaymentId} in its request body. */
  private NeoContext creditSourcesContextWithEditPaymentId(String editPaymentId) throws Exception {
    return NeoContext.builder()
        .recordId(INVOICE_ID)
        .httpMethod("GET")
        .endpointType(NeoEndpointType.CRUD)
        .requestBody(new JSONObject().put("editPaymentId", editPaymentId))
        .build();
  }

  /** A {@code handleListPayments} context — only {@code recordId} is read by that entry point. */
  private NeoContext listPaymentsContext() {
    return NeoContext.builder()
        .recordId(INVOICE_ID)
        .httpMethod("GET")
        .endpointType(NeoEndpointType.ACTION)
        .build();
  }

  /**
   * Stubs {@code PisPaymentService.linkedPisPayment} (called by every {@code paymentListItem})
   * to report no linked PIS payment, so {@code handleListPayments} tests don't NPE on the
   * unrelated {@code PisPayment} criteria.
   */
  @SuppressWarnings("unchecked")
  private void stubNoPisPaymentLinked() {
    OBCriteria<PisPayment> crit = mock(OBCriteria.class);
    when(dal.createCriteria(PisPayment.class)).thenReturn(crit);
    when(crit.add(any(Criterion.class))).thenReturn(crit);
    when(crit.setMaxResults(anyInt())).thenReturn(crit);
    when(crit.uniqueResult()).thenReturn(null);
  }

  /** Stubs {@code reverseConsumedCredit}'s {@code FIN_Payment_Credit} lookup to find nothing. */
  @SuppressWarnings("unchecked")
  private void stubNoConsumedCredit() {
    OBCriteria<FIN_Payment_Credit> crit = mock(OBCriteria.class);
    when(dal.createCriteria(FIN_Payment_Credit.class)).thenReturn(crit);
    when(crit.add(any(Criterion.class))).thenReturn(crit);
    when(crit.list()).thenReturn(Collections.emptyList());
  }

  /** Builds a mock 'abono' (pending negative credit-memo/return) PSD, sortable by invoice date. */
  private FIN_PaymentScheduleDetail abonoPsd(String id, BigDecimal amount, String docNo,
      Date invoiceDate, String docTypeName) {
    FIN_PaymentScheduleDetail psd = mock(FIN_PaymentScheduleDetail.class);
    when(psd.getId()).thenReturn(id);
    when(psd.getAmount()).thenReturn(amount);
    FIN_PaymentSchedule ps = mock(FIN_PaymentSchedule.class);
    Invoice ncInvoice = mock(Invoice.class);
    DocumentType ncType = mock(DocumentType.class);
    when(ncType.getName()).thenReturn(docTypeName);
    when(ncInvoice.getDocumentNo()).thenReturn(docNo);
    when(ncInvoice.getInvoiceDate()).thenReturn(invoiceDate);
    when(ncInvoice.getDocumentType()).thenReturn(ncType);
    when(ps.getInvoice()).thenReturn(ncInvoice);
    when(psd.getInvoicePaymentSchedule()).thenReturn(ps);
    return psd;
  }

  /**
   * Same as {@link #abonoPsd}, but the originating invoice also reports a {@code grandTotalAmount}
   * — used to prove that the listing code never consults the total sign in Java (ETP-4841 keeps
   * that restriction inside the HQL, and {@code abonosUsedByDraft} has no restriction at all).
   */
  private FIN_PaymentScheduleDetail abonoPsdWithInvoiceTotal(String id, BigDecimal amount,
      String docNo, Date invoiceDate, BigDecimal invoiceTotal) {
    FIN_PaymentScheduleDetail psd = mock(FIN_PaymentScheduleDetail.class);
    when(psd.getId()).thenReturn(id);
    when(psd.getAmount()).thenReturn(amount);
    FIN_PaymentSchedule ps = mock(FIN_PaymentSchedule.class);
    Invoice ncInvoice = mock(Invoice.class);
    DocumentType ncType = mock(DocumentType.class);
    when(ncType.getName()).thenReturn("Credit Memo");
    when(ncInvoice.getDocumentNo()).thenReturn(docNo);
    when(ncInvoice.getInvoiceDate()).thenReturn(invoiceDate);
    when(ncInvoice.getDocumentType()).thenReturn(ncType);
    when(ncInvoice.getGrandTotalAmount()).thenReturn(invoiceTotal);
    when(ps.getInvoice()).thenReturn(ncInvoice);
    when(psd.getInvoicePaymentSchedule()).thenReturn(ps);
    return psd;
  }

  /** Builds a mock accumulated-credit payment, sortable by payment date. */
  private FIN_Payment creditPayment(String id, String docNo, BigDecimal generatedCredit,
      BigDecimal usedCredit, Date paymentDate) {
    FIN_Payment payment = mock(FIN_Payment.class);
    when(payment.getId()).thenReturn(id);
    when(payment.getDocumentNo()).thenReturn(docNo);
    when(payment.getGeneratedCredit()).thenReturn(generatedCredit);
    when(payment.getUsedCredit()).thenReturn(usedCredit);
    when(payment.getPaymentDate()).thenReturn(paymentDate);
    when(payment.getDescription()).thenReturn("desc");
    return payment;
  }

  private Date date(String yyyyMMdd) throws Exception {
    return new SimpleDateFormat("yyyy-MM-dd").parse(yyyyMMdd);
  }

  private FinAccPaymentMethod fapm(FIN_FinancialAccount acc, FIN_PaymentMethod pm) {
    FinAccPaymentMethod f = mock(FinAccPaymentMethod.class);
    when(f.getAccount()).thenReturn(acc);
    when(f.getPaymentMethod()).thenReturn(pm);
    return f;
  }

  /**
   * Stubs the common entity loads and payment-method resolution path needed by every
   * {@code doRegisterPaymentAdvanced} test (invoice, schedule, account, doc type, a valid
   * resolved payment method).
   */
  @SuppressWarnings("unchecked")
  private void stubAdvancedBasics() {
    when(dal.get(Invoice.class, INVOICE_ID)).thenReturn(invoice);
    when(dal.get(FIN_PaymentSchedule.class, SCHEDULE_ID)).thenReturn(schedule);
    when(dal.get(FIN_FinancialAccount.class, ACCOUNT_ID)).thenReturn(account);
    when(invoice.getBusinessPartner()).thenReturn(bp);

    // resolveRequestedMethod: no explicit method requested → falls back to resolvePaymentMethod,
    // which finds the invoice's payment method valid for the account.
    OBCriteria<FinAccPaymentMethod> methodCrit = mock(OBCriteria.class);
    when(dal.createCriteria(FinAccPaymentMethod.class)).thenReturn(methodCrit);
    when(methodCrit.add(any(Criterion.class))).thenReturn(methodCrit);
    when(methodCrit.setMaxResults(anyInt())).thenReturn(methodCrit);
    when(methodCrit.list()).thenReturn(Collections.singletonList(mock(FinAccPaymentMethod.class)));
  }

  /**
   * Asserts the financial-transaction fields were written on {@code payment} VERBATIM (ETP-4841):
   * the conversion rate exactly as the modal typed it and the transaction amount exactly as
   * {@code PaymentCurrencyConverter.convertedAmount} rounded it — and that the core helper
   * {@code FIN_AddPayment.setFinancialTransactionAmountAndRate}, which would recompute the rate
   * from that rounded amount, is never called.
   */
  private void assertVerbatimTransactionFields(FIN_Payment payment, String rate, String txnAmount) {
    verify(payment).setFinancialTransactionConvertRate(new BigDecimal(rate));
    verify(payment).setFinancialTransactionAmount(new BigDecimal(txnAmount));
    finAddPaymentMock.verify(() -> FIN_AddPayment.setFinancialTransactionAmountAndRate(
        any(), any(), any(), any()), never());
  }

  /**
   * Makes the selected financial account foreign to the invoice: EUR with a standard precision of
   * 2 decimals, while the invoice keeps the global (USD-like) test currency.
   */
  private void stubForeignEurAccount() {
    Currency accountCurrency = mock(Currency.class);
    when(accountCurrency.getId()).thenReturn("EUR-ID");
    when(accountCurrency.getStandardPrecision()).thenReturn(2L);
    when(account.getCurrency()).thenReturn(accountCurrency);
  }

  /**
   * Stubs the existing DRAFT the edit-in-place path reuses: {@code DRAFT_PAY_ID} resolves to it, it
   * is not processed, it consumed no accumulated credit, and the document's own installment PSD for
   * {@link #SCHEDULE_ID} is still linked to it — the state
   * {@code PaymentDraftEditService.prepareEditableDraft} / {@code reapplyLinkedInstallmentPSD}
   * expect.
   */
  private FIN_Payment stubExistingDraft(BigDecimal scheduleTotal) {
    FIN_Payment draft = mock(FIN_Payment.class);
    when(draft.getId()).thenReturn(DRAFT_PAY_ID);
    when(draft.getDocumentNo()).thenReturn("PAY-DRAFT");
    when(draft.getStatus()).thenReturn("RPR");
    when(draft.isProcessed()).thenReturn(false);
    when(dal.get(FIN_Payment.class, DRAFT_PAY_ID)).thenReturn(draft);

    when(schedule.getId()).thenReturn(SCHEDULE_ID);
    when(schedule.getAmount()).thenReturn(scheduleTotal);
    FIN_PaymentScheduleDetail linkedPsd = mock(FIN_PaymentScheduleDetail.class);
    when(linkedPsd.getInvoicePaymentSchedule()).thenReturn(schedule);
    FIN_PaymentDetail detail = mock(FIN_PaymentDetail.class);
    when(detail.getFINPaymentScheduleDetailList())
        .thenReturn(new ArrayList<>(Collections.singletonList(linkedPsd)));
    when(draft.getFINPaymentDetailList())
        .thenReturn(new ArrayList<>(Collections.singletonList(detail)));
    stubNoConsumedCredit();
    return draft;
  }

  /**
   * Stubs {@code findPendingPSDs} to return a single PSD of the given amount. The criteria is
   * separate from the payment-method criteria, so it is wired on its own entity class.
   */
  @SuppressWarnings("unchecked")
  private FIN_PaymentScheduleDetail stubPendingPSDs(BigDecimal amount) {
    FIN_PaymentScheduleDetail psd = mock(FIN_PaymentScheduleDetail.class);
    when(psd.getAmount()).thenReturn(amount);
    OBCriteria<FIN_PaymentScheduleDetail> crit = mock(OBCriteria.class);
    when(dal.createCriteria(FIN_PaymentScheduleDetail.class)).thenReturn(crit);
    when(crit.add(any(Criterion.class))).thenReturn(crit);
    when(crit.addOrderBy(anyString(), anyBoolean())).thenReturn(crit);
    when(crit.list()).thenReturn(Collections.singletonList(psd));
    return psd;
  }

  @SuppressWarnings("unchecked")
  private void stubPendingPSDs() {
    OBCriteria<FIN_PaymentScheduleDetail> crit = mock(OBCriteria.class);
    when(dal.createCriteria(FIN_PaymentScheduleDetail.class)).thenReturn(crit);
    when(crit.add(any(Criterion.class))).thenReturn(crit);
    when(crit.addOrderBy(anyString(), anyBoolean())).thenReturn(crit);
    when(crit.list()).thenReturn(Collections.emptyList());
  }

  private JSONObject advancedBody(String cash, String process) {
    try {
      return new JSONObject()
          .put("scheduleId", SCHEDULE_ID)
          .put("actual_payment", cash)
          .put("payment_date", PAY_DATE)
          .put("fin_financial_account_id", ACCOUNT_ID)
          .put("process", process);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
