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
import static org.junit.jupiter.api.Assertions.assertNull;
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
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
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
import org.openbravo.model.financialmgmt.gl.GLItem;
import org.openbravo.model.financialmgmt.payment.FIN_FinancialAccount;
import org.openbravo.model.financialmgmt.payment.FIN_Payment;
import org.openbravo.model.financialmgmt.payment.FIN_PaymentDetail;
import org.openbravo.model.financialmgmt.payment.FIN_PaymentMethod;
import org.openbravo.model.financialmgmt.payment.FIN_PaymentScheduleDetail;
import org.openbravo.model.financialmgmt.payment.FinAccPaymentMethod;
import org.openbravo.service.db.DalConnectionProvider;

/**
 * Mockito unit tests for {@link AddPaymentService}, which replicates Etendo
 * Classic's "Add Payment" by creating and processing a FIN_Payment.
 *
 * <p>Strategy: every static collaborator ({@link OBDal}, {@link OBContext},
 * {@link FIN_Utility}, {@link FIN_AddPayment}, {@link NeoDefaultsService},
 * {@link RequestContext}) and every constructed collaborator
 * ({@link AdvPaymentMngtDao}, {@link DalConnectionProvider}) is mocked, so the
 * orchestration logic runs over deterministic in-memory fixtures without a DB.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AddPaymentServiceTest {

  private static final String ACCOUNT_ID = "ACC-1";
  /** The session tenant every mocked entity below belongs to (ETP-4950 ownership guard). */
  private static final String TENANT_CLIENT = "client-1";
  private static final String TENANT_ORG = "org-1";
  /** A client the session may NOT read — used by the tenant-isolation regressions. */
  private static final String FOREIGN_CLIENT = "client-other";
  private static final String BP_ID = "BP-1";
  private static final String METHOD_ID = "PM-1";
  private static final String PSD_ID = "PSD-1";
  private static final String GL_ID = "GL-1";

  private MockedStatic<OBDal> obDalMock;
  private MockedStatic<OBContext> obContextMock;
  private MockedStatic<FIN_Utility> finUtilityMock;
  private MockedStatic<FIN_AddPayment> finAddPaymentMock;
  private MockedStatic<NeoDefaultsService> neoDefaultsMock;
  private MockedStatic<RequestContext> requestContextMock;
  private MockedConstruction<AdvPaymentMngtDao> daoConstruction;
  private MockedConstruction<DalConnectionProvider> connConstruction;

  private OBDal dal;
  private FIN_FinancialAccount account;
  /** A client outside the session's readable set, and a row owned by it (ETP-4950 regressions). */
  private Client foreignClient;
  private FIN_PaymentScheduleDetail foreignPsd;
  private BusinessPartner bp;
  private Organization org;
  private Currency currency;
  private FIN_PaymentMethod method;
  private DocumentType docType;
  private FIN_Payment payment;
  private FIN_Payment refundPayment;
  private OBError okResult;

  @BeforeEach
  void setUp() {
    account = mock(FIN_FinancialAccount.class);
    foreignClient = mock(Client.class);
    foreignPsd = mock(FIN_PaymentScheduleDetail.class);
    bp = mock(BusinessPartner.class);
    org = mock(Organization.class);
    currency = mock(Currency.class);
    method = mock(FIN_PaymentMethod.class);
    docType = mock(DocumentType.class);
    payment = mock(FIN_Payment.class);
    refundPayment = mock(FIN_Payment.class);
    okResult = mock(OBError.class);

    when(account.getCurrency()).thenReturn(currency);
    when(account.getOrganization()).thenReturn(org);
    when(docType.getDocumentCategory()).thenReturn("ARR");
    when(payment.getId()).thenReturn("pay-1");
    when(payment.getDocumentNo()).thenReturn("PAY-1");
    when(payment.getStatus()).thenReturn("RPR");
    when(refundPayment.getId()).thenReturn("refund-1");
    when(refundPayment.getDocumentNo()).thenReturn("REF-1");
    when(okResult.getType()).thenReturn("Success");
    // Build collaborators that stub internally BEFORE the surrounding when(),
    // so their nested stubbing does not interleave with an open stubbing.
    List<FIN_PaymentDetail> defaultDetails = details("18.03");
    OBCriteria<FinAccPaymentMethod> validCrit = validMethodCriteria();
    // Default: the linked details sum to 18.03 (exact-payment scenario).
    when(payment.getFINPaymentDetailList()).thenReturn(defaultDetails);

    // Every id in the request body is now resolved through TenantOwnership, which hides a row
    // belonging to another tenant (ETP-4950). So each mocked entity has to look like it belongs to
    // the session's client, and the session has to advertise that client as readable — Mockito
    // would otherwise default getReadableClients() to an EMPTY array and every lookup would come
    // back null, surfacing as "Financial account not found". Built before the surrounding when(),
    // same reason the comment above gives.
    Client tenantClient = mock(Client.class);
    when(tenantClient.getId()).thenReturn(TENANT_CLIENT);
    when(org.getId()).thenReturn(TENANT_ORG);
    when(account.getClient()).thenReturn(tenantClient);
    when(bp.getClient()).thenReturn(tenantClient);
    when(method.getClient()).thenReturn(tenantClient);
    when(org.getClient()).thenReturn(tenantClient);
    when(currency.getClient()).thenReturn(tenantClient);
    FIN_PaymentScheduleDetail ownedPsd = mock(FIN_PaymentScheduleDetail.class);
    when(ownedPsd.getClient()).thenReturn(tenantClient);
    GLItem ownedGlItem = mock(GLItem.class);
    when(ownedGlItem.getClient()).thenReturn(tenantClient);

    obDalMock = mockStatic(OBDal.class);
    dal = mock(OBDal.class);
    obDalMock.when(OBDal::getInstance).thenReturn(dal);
    when(dal.get(eq(FIN_FinancialAccount.class), anyString())).thenReturn(account);
    when(dal.get(eq(BusinessPartner.class), anyString())).thenReturn(bp);
    when(dal.get(eq(FIN_PaymentMethod.class), anyString())).thenReturn(method);
    when(dal.get(eq(FIN_PaymentScheduleDetail.class), anyString())).thenReturn(ownedPsd);
    when(dal.get(eq(GLItem.class), anyString())).thenReturn(ownedGlItem);
    when(dal.createCriteria(eq(FinAccPaymentMethod.class))).thenReturn(validCrit);

    OBContext tenantContext = mock(OBContext.class);
    when(tenantContext.getReadableClients()).thenReturn(new String[] {TENANT_CLIENT});
    when(tenantContext.getReadableOrganizations()).thenReturn(new String[] {TENANT_ORG});
    obContextMock = mockStatic(OBContext.class);
    obContextMock.when(OBContext::getOBContext).thenReturn(tenantContext);

    finUtilityMock = mockStatic(FIN_Utility.class);
    finUtilityMock.when(() -> FIN_Utility.getDocumentType(any(), anyString())).thenReturn(docType);
    finUtilityMock.when(() -> FIN_Utility.getDocumentNo(any(DocumentType.class), anyString()))
        .thenReturn("PAY-1");

    finAddPaymentMock = mockStatic(FIN_AddPayment.class);
    finAddPaymentMock.when(() -> FIN_AddPayment.setFinancialTransactionAmountAndRate(any(), any(), any(), any()))
        .thenReturn(payment);
    finAddPaymentMock.when(() -> FIN_AddPayment.updatePaymentDetail(any(), any(), any(), anyBoolean()))
        .thenReturn(BigDecimal.ZERO);
    finAddPaymentMock.when(() -> FIN_AddPayment.processPayment(any(), any(), anyString(), any(), anyString()))
        .thenReturn(okResult);
    finAddPaymentMock.when(() -> FIN_AddPayment.processPayment(any(), any(), anyString(), any(), anyString(), anyString()))
        .thenReturn(okResult);
    finAddPaymentMock.when(() -> FIN_AddPayment.createRefundPayment(any(), any(), any(), any(), any()))
        .thenReturn(refundPayment);

    neoDefaultsMock = mockStatic(NeoDefaultsService.class);
    neoDefaultsMock.when(() -> NeoDefaultsService.buildVariablesSecureApp(any()))
        .thenReturn(mock(VariablesSecureApp.class));

    requestContextMock = mockStatic(RequestContext.class);
    requestContextMock.when(RequestContext::get).thenReturn(mock(RequestContext.class));

    final FIN_Payment paymentRef = payment;
    daoConstruction = mockConstruction(AdvPaymentMngtDao.class, (m, ctx) -> {
      when(m.getNewPayment(anyBoolean(), any(), any(), any(), any(), any(), any(), any(),
          any(), any(), any(), any(), any())).thenReturn(paymentRef);
      when(m.getNewPaymentScheduleDetail(any(), any())).thenReturn(mock(FIN_PaymentScheduleDetail.class));
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

  // ── Happy paths ───────────────────────────────────────────────────────────

  @Test
  @DisplayName("Exact receipt against one invoice creates and processes the payment")
  void testExactReceiptHappyPath() throws Exception {
    JSONObject body = baseBody("18.03").put("selectedInvoices", new JSONObject().put(PSD_ID, 18.03));

    NeoResponse response = AddPaymentService.doAddPayment(body);

    assertEquals(201, response.getHttpStatus());
    JSONObject data = response.getBody().getJSONObject("response").getJSONObject("data");
    assertEquals("pay-1", data.getString("id"));
    assertEquals("PAY-1", data.getString("documentNo"));

    finAddPaymentMock.verify(() -> FIN_AddPayment.updatePaymentDetail(any(), eq(payment),
        eq(new BigDecimal("18.03")), eq(false)));
    finAddPaymentMock.verify(() -> FIN_AddPayment.processPayment(any(), any(), eq("P"), eq(payment), eq("")));
    finAddPaymentMock.verify(() -> FIN_AddPayment.createRefundPayment(any(), any(), any(), any(), any()), never());
  }

  @Test
  @DisplayName("Write-off flag is forwarded to updatePaymentDetail")
  void testWriteoffForwarded() throws Exception {
    JSONObject body = baseBody("18.03")
        .put("selectedInvoices", new JSONObject().put(PSD_ID, 18.03))
        .put("writeoffs", new JSONObject().put(PSD_ID, true));

    AddPaymentService.doAddPayment(body);

    finAddPaymentMock.verify(() -> FIN_AddPayment.updatePaymentDetail(any(), eq(payment), any(), eq(true)));
  }

  @Test
  @DisplayName("Over-payment with leave-credit registers credit and does not refund")
  void testOverpaymentLeaveCredit() throws Exception {
    // Linked details sum to 18.03 but the payment is 25.00 → 6.97 left as credit.
    JSONObject body = baseBody("25.00")
        .put("selectedInvoices", new JSONObject().put(PSD_ID, 18.03))
        .put("overpaymentAction", "leave-credit");

    NeoResponse response = AddPaymentService.doAddPayment(body);

    assertEquals(201, response.getHttpStatus());
    finAddPaymentMock.verify(() -> FIN_AddPayment.createRefundPayment(any(), any(), any(), any(), any()), never());
  }

  @Test
  @DisplayName("Over-payment with refund creates and processes the refund payment")
  void testOverpaymentRefund() throws Exception {
    JSONObject body = baseBody("25.00")
        .put("selectedInvoices", new JSONObject().put(PSD_ID, 18.03))
        .put("overpaymentAction", "refund");

    NeoResponse response = AddPaymentService.doAddPayment(body);

    JSONObject data = response.getBody().getJSONObject("response").getJSONObject("data");
    assertEquals("refund-1", data.getString("refundPaymentId"));
    finAddPaymentMock.verify(() -> FIN_AddPayment.createRefundPayment(any(), any(), eq(payment),
        eq(new BigDecimal("-6.97")), any()));
    finAddPaymentMock.verify(() -> FIN_AddPayment.processPayment(any(), any(), eq("P"),
        eq(refundPayment), eq(""), anyString()));
  }

  @Test
  @DisplayName("Receipt G/L line is saved with received minus paid")
  void testGlLineReceiptSign() throws Exception {
    JSONObject gl = new JSONObject().put("glItemId", GL_ID).put("receivedIn", 5).put("paidOut", 2);
    JSONObject body = baseBody("18.03")
        .put("selectedInvoices", new JSONObject().put(PSD_ID, 18.03))
        .put("glItems", new JSONArray(Collections.singletonList(gl)));

    AddPaymentService.doAddPayment(body);

    finAddPaymentMock.verify(() -> FIN_AddPayment.saveGLItem(eq(payment),
        eq(new BigDecimal("3")), any(GLItem.class)));
  }

  @Test
  @DisplayName("Payment G/L line is saved with paid minus received (inverse sign)")
  void testGlLinePaymentSign() throws Exception {
    JSONObject gl = new JSONObject().put("glItemId", GL_ID).put("receivedIn", 0).put("paidOut", 4);
    JSONObject body = baseBody("18.03").put("isReceipt", false)
        .put("selectedInvoices", new JSONObject().put(PSD_ID, 18.03))
        .put("glItems", new JSONArray(Collections.singletonList(gl)));

    AddPaymentService.doAddPayment(body);

    finAddPaymentMock.verify(() -> FIN_AddPayment.saveGLItem(eq(payment),
        eq(new BigDecimal("4")), any(GLItem.class)));
  }

  @Test
  @DisplayName("Blank G/L id and zero-amount lines are skipped")
  void testGlLinesSkipped() throws Exception {
    JSONArray gls = new JSONArray(Arrays.asList(
        new JSONObject().put("glItemId", "").put("receivedIn", 5).put("paidOut", 0),
        new JSONObject().put("glItemId", GL_ID).put("receivedIn", 0).put("paidOut", 0)));
    JSONObject body = baseBody("18.03")
        .put("selectedInvoices", new JSONObject().put(PSD_ID, 18.03))
        .put("glItems", gls);

    AddPaymentService.doAddPayment(body);

    finAddPaymentMock.verify(() -> FIN_AddPayment.saveGLItem(any(), any(), any()), never());
  }

  @Test
  @DisplayName("Invoice lines with zero / invalid amounts are skipped")
  void testInvoiceLinesSkipped() throws Exception {
    JSONObject sel = new JSONObject().put("PSD-A", 0).put("PSD-B", "not-a-number");
    JSONObject body = baseBody("0.01").put("selectedInvoices", sel);

    AddPaymentService.doAddPayment(body);

    finAddPaymentMock.verify(() -> FIN_AddPayment.updatePaymentDetail(any(), any(), any(), anyBoolean()), never());
  }

  @Test
  @DisplayName("organizationId from the movement overrides the account organization")
  void testResolveMovementOrg() throws Exception {
    // Owned by the session tenant, or TenantOwnership hides it and resolveOrg silently falls back
    // to the account's own organization (ETP-4950).
    Client movementOrgClient = mock(Client.class);
    when(movementOrgClient.getId()).thenReturn(TENANT_CLIENT);
    Organization movementOrg = mock(Organization.class);
    when(movementOrg.getClient()).thenReturn(movementOrgClient);
    when(dal.get(eq(Organization.class), eq("ORG-9"))).thenReturn(movementOrg);
    JSONObject body = baseBody("18.03")
        .put("organizationId", "ORG-9")
        .put("selectedInvoices", new JSONObject().put(PSD_ID, 18.03));

    NeoResponse response = AddPaymentService.doAddPayment(body);

    assertEquals(201, response.getHttpStatus());
    finUtilityMock.verify(() -> FIN_Utility.getDocumentType(eq(movementOrg), anyString()));
  }

  @Test
  @DisplayName("Payment method falls back to the first method configured for the account")
  void testPaymentMethodFallback() throws Exception {
    FinAccPaymentMethod fapm = mock(FinAccPaymentMethod.class);
    when(fapm.getPaymentMethod()).thenReturn(method);
    OBCriteria<FinAccPaymentMethod> crit = criteriaReturning(Collections.singletonList(fapm));
    when(dal.createCriteria(eq(FinAccPaymentMethod.class))).thenReturn(crit);
    JSONObject body = baseBody("18.03").put("paymentMethodId", "")
        .put("selectedInvoices", new JSONObject().put(PSD_ID, 18.03));

    NeoResponse response = AddPaymentService.doAddPayment(body);

    assertEquals(201, response.getHttpStatus());
  }

  // ── Validation / error paths ──────────────────────────────────────────────

  @Test
  @DisplayName("Invalid amount is rejected")
  void testInvalidAmount() {
    OBException ex = assertThrows(OBException.class,
        () -> AddPaymentService.doAddPayment(baseBody("abc")));
    assertTrue(ex.getMessage().contains("Invalid amount"));
  }

  @Test
  @DisplayName("Non-positive amount is rejected")
  void testNonPositiveAmount() {
    assertThrows(OBException.class, () -> AddPaymentService.doAddPayment(baseBody("0")));
  }

  @Test
  @DisplayName("Invalid date is rejected")
  void testInvalidDate() throws Exception {
    JSONObject body = baseBody("18.03");
    body.put("paymentDate", "31/31/2026");
    assertThrows(OBException.class, () -> AddPaymentService.doAddPayment(body));
  }

  @Test
  @DisplayName("Missing financial account is rejected")
  void testAccountNotFound() {
    when(dal.get(eq(FIN_FinancialAccount.class), anyString())).thenReturn(null);
    OBException ex = assertThrows(OBException.class,
        () -> AddPaymentService.doAddPayment(baseBody("18.03")));
    assertTrue(ex.getMessage().contains("Financial account not found"));
  }

  @Test
  @DisplayName("Missing contact is rejected")
  void testContactNotFound() {
    when(dal.get(eq(BusinessPartner.class), anyString())).thenReturn(null);
    assertThrows(OBException.class, () -> AddPaymentService.doAddPayment(baseBody("18.03")));
  }

  @Test
  @DisplayName("No valid payment method is rejected")
  void testPaymentMethodMissing() {
    when(dal.get(eq(FIN_PaymentMethod.class), anyString())).thenReturn(null);
    OBCriteria<FinAccPaymentMethod> emptyCrit = criteriaReturning(Collections.emptyList());
    when(dal.createCriteria(eq(FinAccPaymentMethod.class))).thenReturn(emptyCrit);
    OBException ex = assertThrows(OBException.class,
        () -> AddPaymentService.doAddPayment(baseBody("18.03")));
    assertTrue(ex.getMessage().contains("payment method"));
  }

  @Test
  @DisplayName("Missing document type is rejected")
  void testDocTypeMissing() {
    finUtilityMock.when(() -> FIN_Utility.getDocumentType(any(), anyString())).thenReturn(null);
    assertThrows(OBException.class, () -> AddPaymentService.doAddPayment(baseBody("18.03")));
  }

  @Test
  @DisplayName("A processing error surfaces as an exception")
  void testProcessingError() throws Exception {
    OBError error = mock(OBError.class);
    when(error.getType()).thenReturn("Error");
    when(error.getMessage()).thenReturn("boom");
    finAddPaymentMock.when(() -> FIN_AddPayment.processPayment(any(), any(), anyString(), any(), anyString()))
        .thenReturn(error);
    JSONObject body = baseBody("18.03").put("selectedInvoices", new JSONObject().put(PSD_ID, 18.03));

    OBException ex = assertThrows(OBException.class, () -> AddPaymentService.doAddPayment(body));
    assertEquals("boom", ex.getMessage());
  }

  @Test
  @DisplayName("Unknown invoice installment is rejected")
  void testPsdNotFound() throws Exception {
    when(dal.get(eq(FIN_PaymentScheduleDetail.class), anyString())).thenReturn(null);
    JSONObject body = baseBody("18.03").put("selectedInvoices", new JSONObject().put(PSD_ID, 18.03));
    OBException ex = assertThrows(OBException.class, () -> AddPaymentService.doAddPayment(body));
    assertTrue(ex.getMessage().contains("Invoice installment not found"));
  }

  /**
   * A financial account of ANOTHER tenant is reported as missing, so no payment is registered
   * against it.
   *
   * <p>Regression for ETP-4950 / H1: the id arrives in the request body and used to be resolved
   * with a bare {@code OBDal.get}, which applies no client predicate. Nothing here was a deliberate
   * guard — isolation happened to fall out of an {@code OBCriteria} three calls later, when the
   * payment method was resolved. Note the assertion is "not found", not "forbidden": the caller
   * must not be able to tell a foreign account from a nonexistent one.
   */
  @Test
  @DisplayName("A financial account of another tenant is not found")
  void testForeignFinancialAccountIsRejected() throws Exception {
    when(foreignClient.getId()).thenReturn(FOREIGN_CLIENT);
    when(account.getClient()).thenReturn(foreignClient);
    JSONObject body = baseBody("18.03").put("selectedInvoices", new JSONObject().put(PSD_ID, 18.03));

    OBException ex = assertThrows(OBException.class, () -> AddPaymentService.doAddPayment(body));

    assertTrue(ex.getMessage().contains("Financial account not found"));
  }

  /**
   * An invoice instalment of ANOTHER tenant is reported as missing, so nothing is written against
   * it.
   *
   * <p>Regression for ETP-4950 / H2, the most damaging of the set: the keys of
   * {@code selectedInvoices} come from the request body, the payment being built is the caller's
   * own, and {@code FIN_AddPayment.updatePaymentDetail} was reached with the foreign instalment —
   * so with {@code writeoffs[psdId]=true} the caller forgave the balance of another tenant's
   * invoice.
   */
  @Test
  @DisplayName("An invoice instalment of another tenant is not found")
  void testForeignInvoiceInstalmentIsRejected() throws Exception {
    when(foreignClient.getId()).thenReturn(FOREIGN_CLIENT);
    when(foreignPsd.getClient()).thenReturn(foreignClient);
    when(dal.get(eq(FIN_PaymentScheduleDetail.class), anyString())).thenReturn(foreignPsd);
    JSONObject body = baseBody("18.03")
        .put("selectedInvoices", new JSONObject().put(PSD_ID, 18.03))
        .put("writeoffs", new JSONObject().put(PSD_ID, true));

    OBException ex = assertThrows(OBException.class, () -> AddPaymentService.doAddPayment(body));

    assertTrue(ex.getMessage().contains("Invoice installment not found"));
  }

  @Test
  @DisplayName("Unknown G/L item is rejected")
  void testGlItemNotFound() throws Exception {
    when(dal.get(eq(GLItem.class), anyString())).thenReturn(null);
    JSONObject gl = new JSONObject().put("glItemId", GL_ID).put("receivedIn", 5).put("paidOut", 0);
    JSONObject body = baseBody("18.03")
        .put("selectedInvoices", new JSONObject().put(PSD_ID, 18.03))
        .put("glItems", new JSONArray(Collections.singletonList(gl)));
    assertThrows(OBException.class, () -> AddPaymentService.doAddPayment(body));
  }

  @Test
  @DisplayName("No invoices and no over-payment processes a bare payment")
  void testNoInvoicesNoOverpayment() throws Exception {
    when(payment.getFINPaymentDetailList()).thenReturn(Collections.emptyList());
    JSONObject body = baseBody("10.00"); // assigned 0, leftover 10 → credit, no action → stays credit
    NeoResponse response = AddPaymentService.doAddPayment(body);
    assertEquals(201, response.getHttpStatus());
    assertNull(response.getBody().getJSONObject("response")
        .getJSONObject("data").opt("refundPaymentId"), "no refund payment");
    finAddPaymentMock.verify(() -> FIN_AddPayment.processPayment(any(), any(), eq("P"), eq(payment), eq("")),
        times(1));
  }

  // ── Fixtures / helpers ────────────────────────────────────────────────────

  private JSONObject baseBody(String amount) {
    try {
      return new JSONObject()
          .put("FIN_Financial_Account_ID", ACCOUNT_ID)
          .put("bpartnerId", BP_ID)
          .put("paymentMethodId", METHOD_ID)
          .put("isReceipt", true)
          .put("amount", amount)
          .put("paymentDate", "2026-06-03")
          .put("referenceNo", "REF")
          .put("description", "Test payment");
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static List<FIN_PaymentDetail> details(String... amounts) {
    return Arrays.stream(amounts).map(a -> {
      FIN_PaymentDetail d = mock(FIN_PaymentDetail.class);
      when(d.getAmount()).thenReturn(new BigDecimal(a));
      return d;
    }).collect(java.util.stream.Collectors.toList());
  }

  /** Criteria whose chainable add/setMaxResults return itself and list() is non-empty. */
  private OBCriteria<FinAccPaymentMethod> validMethodCriteria() {
    return criteriaReturning(Collections.singletonList(mock(FinAccPaymentMethod.class)));
  }

  @SuppressWarnings("unchecked")
  private OBCriteria<FinAccPaymentMethod> criteriaReturning(List<FinAccPaymentMethod> result) {
    OBCriteria<FinAccPaymentMethod> crit = mock(OBCriteria.class);
    when(crit.add(any())).thenReturn(crit);
    when(crit.setMaxResults(anyInt())).thenReturn(crit);
    when(crit.list()).thenReturn(result);
    return crit;
  }
}
