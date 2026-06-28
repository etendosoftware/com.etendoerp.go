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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
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
import org.openbravo.model.financialmgmt.payment.FinAccPaymentMethod;
import org.openbravo.service.db.DalConnectionProvider;

/**
 * Mockito unit tests for the ETP-4331 two-step Cobros/Pagos flow in
 * {@link PaymentRegistrationService}:
 * <ul>
 *   <li>{@code handleListCreditSources} - nets generatedCredit - usedCredit for accumulated
 *       credit, and lists unpaid negative credit-memo PSDs (abonos) excluding the current
 *       invoice.</li>
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
  private static final String ERROR_TYPE = "Error";
  private static final String ITEMS = "items";

  private MockedStatic<OBDal> obDalMock;
  private MockedStatic<OBContext> obContextMock;
  private MockedStatic<FIN_Utility> finUtilityMock;
  private MockedStatic<FIN_AddPayment> finAddPaymentMock;
  private MockedStatic<FIN_PaymentProcess> finPaymentProcessMock;
  private MockedStatic<NeoDefaultsService> neoDefaultsMock;
  private MockedStatic<RequestContext> requestContextMock;
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
    finAddPaymentMock.when(() -> FIN_AddPayment.setFinancialTransactionAmountAndRate(
        any(), any(), any(), any())).thenReturn(newPayment);
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
  void listCreditSourcesNetsAvailableCredit() throws Exception {
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

    NeoResponse response = PaymentRegistrationService.handleListCreditSources(context, true);

    assertEquals(200, response.getHttpStatus());
    JSONArray items = response.getBody().getJSONArray(ITEMS);
    assertEquals(1, items.length(), "only the partially consumed credit must appear");
    JSONObject item = items.getJSONObject(0);
    assertEquals(KIND_CREDIT, item.getString("kind"));
    assertEquals("p-partial", item.getString("paymentId"));
    assertEquals(new BigDecimal("40"), new BigDecimal(item.getString("avail")));
  }

  @Test
  @DisplayName("Abono listing returns unpaid negative credit-memo PSDs with absolute avail")
  void listCreditSourcesReturnsAbonos() throws Exception {
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

    NeoResponse response = PaymentRegistrationService.handleListCreditSources(context, true);

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
  @DisplayName("Credit sources for an invoice without a business partner returns empty")
  void listCreditSourcesNoBusinessPartnerReturnsEmpty() throws Exception {
    NeoContext context = creditSourcesContext();
    when(dal.get(Invoice.class, INVOICE_ID)).thenReturn(invoice);
    when(invoice.getBusinessPartner()).thenReturn(null);

    NeoResponse response = PaymentRegistrationService.handleListCreditSources(context, true);

    assertEquals(200, response.getHttpStatus());
    assertEquals(0, response.getBody().getInt("totalCount"));
  }

  // ════════════════════════════════════════════════════════════════════════
  // handleListPaymentMethods
  // ════════════════════════════════════════════════════════════════════════

  @Test
  @SuppressWarnings("unchecked")
  @DisplayName("Payment methods are de-duplicated and filtered to the natural org tree")
  void listPaymentMethodsDistinctWithinNaturalTree() throws Exception {
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
  void advancedDraftDoesNotProcess() throws Exception {
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
  void advancedConfirmProcessesAndSettles() throws Exception {
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
  // doRegisterPaymentAdvanced - credit consumption
  // ════════════════════════════════════════════════════════════════════════

  @Test
  @DisplayName("Consuming accumulated credit bumps source usedCredit, links FIN_Payment_Credit, "
      + "and pays the invoice with zero cash")
  void advancedConsumesAccumulatedCredit() throws Exception {
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
  @DisplayName("Consuming an abono (credit memo) links it as a negative payment detail")
  void advancedConsumesAbonoAsNegativeDetail() throws Exception {
    stubAdvancedBasics();
    stubPendingPSDs(new BigDecimal("100.00"));

    FIN_PaymentScheduleDetail abonoPsd = mock(FIN_PaymentScheduleDetail.class);
    when(dal.get(FIN_PaymentScheduleDetail.class, ABONO_PSD_ID)).thenReturn(abonoPsd);

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
  void advancedOverpaymentLeaveCredit() throws Exception {
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
  void advancedOverpaymentRefund() throws Exception {
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

  @Test
  @DisplayName("Advanced register rejects an empty installment with no pending PSDs")
  void advancedEmptyPendingPsdsThrows() throws Exception {
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
  void confirmDraftPaymentProcesses() throws Exception {
    when(dal.get(FIN_Payment.class, NEW_PAY_ID)).thenReturn(newPayment);
    when(newPayment.isProcessed()).thenReturn(true);

    NeoResponse response = PaymentRegistrationService.confirmDraftPayment(NEW_PAY_ID);

    assertEquals(201, response.getHttpStatus());
    finAddPaymentMock.verify(() -> FIN_AddPayment.processPayment(
        any(), any(), eq("P"), eq(newPayment), eq("")), times(1));
    assertTrue(response.getBody().getJSONObject("response").getJSONObject("data")
        .getBoolean("processed"));
  }

  @Test
  @DisplayName("confirmDraftPayment returns 404 when the payment does not exist")
  void confirmDraftPaymentNotFound() throws Exception {
    when(dal.get(FIN_Payment.class, "missing")).thenReturn(null);

    NeoResponse response = PaymentRegistrationService.confirmDraftPayment("missing");

    assertEquals(404, response.getHttpStatus());
    finAddPaymentMock.verify(
        () -> FIN_AddPayment.processPayment(any(), any(), anyString(), any(), anyString()),
        never());
  }

  @Test
  @DisplayName("confirmDraftPayment surfaces a processing error as an exception")
  void confirmDraftPaymentProcessingError() throws Exception {
    when(dal.get(FIN_Payment.class, NEW_PAY_ID)).thenReturn(newPayment);
    OBError error = mock(OBError.class);
    when(error.getType()).thenReturn(ERROR_TYPE);
    when(error.getMessage()).thenReturn("boom");
    finAddPaymentMock.when(() -> FIN_AddPayment.processPayment(any(), any(), anyString(), any(), anyString()))
        .thenReturn(error);

    OBException ex = assertThrows(OBException.class,
        () -> PaymentRegistrationService.confirmDraftPayment(NEW_PAY_ID));
    assertEquals("boom", ex.getMessage());
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
