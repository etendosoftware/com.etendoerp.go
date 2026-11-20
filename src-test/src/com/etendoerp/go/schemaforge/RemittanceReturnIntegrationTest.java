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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.openbravo.advpaymentmngt.dao.TransactionsDao;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.base.weld.test.WeldBaseTest;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.core.SessionHandler;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.ad.utility.Attachment;
import org.openbravo.model.common.businesspartner.BankAccount;
import org.openbravo.model.common.businesspartner.BusinessPartner;
import org.openbravo.model.common.enterprise.DocumentType;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.common.enterprise.OrganizationInformation;
import org.openbravo.model.common.geography.Country;
import org.openbravo.model.common.geography.Location;
import org.openbravo.model.common.invoice.Invoice;
import org.openbravo.model.financialmgmt.payment.FIN_FinaccTransaction;
import org.openbravo.model.financialmgmt.payment.FIN_FinancialAccount;
import org.openbravo.model.financialmgmt.payment.FIN_Payment;
import org.openbravo.model.financialmgmt.payment.FIN_PaymentSchedule;
import org.openbravo.model.financialmgmt.payment.FIN_PaymentScheduleDetail;
import org.openbravo.model.financialmgmt.payment.FIN_Reconciliation;
import org.openbravo.module.remittance.Remittance;
import org.openbravo.module.remittance.RemittanceLine;
import org.openbravo.module.remittance.RemittanceLineCancel;
import org.openbravo.module.remittance.RemittanceType;
import org.openbravo.module.remittance.utility.RemittanceStatusUtil;

/**
 * Real-database acceptance of the step flow of a remittance and of the returns of a collected one
 * (ETP-6247): a collection remittance of GOClient is built, confirmed ({@code PR}: payments
 * remitted, invoices unpaid, file attached), marked sent ({@code SB}), marked collected (settled on
 * its due date, {@code CT}) and then returned through {@link RemittanceReturnAction}, all inside
 * the test transaction, rolled back after each test. The step tests also pin the reactivation
 * (allowed on {@code PR}/{@code SB}, refused once collected, paid, partial or voided).
 *
 * <p>What it pins, with the real module code ({@code AutomatedReturnProcess}, REM's settle undo,
 * the payment removal utility) and the real triggers:
 * <ul>
 *   <li>{@code returnState} of a settled line is {@code SETTLED} and returnable;</li>
 *   <li>{@code returnLines}: the payment is void ({@code RPVOID}), the settlement (Cancel) row is
 *       gone, a Return row exists, a negative payment exists, the invoice's due is pending again
 *       with its outstanding amount restored and nothing paid, and the remittance status is
 *       {@code CP}; {@code returnState} then says {@code RETURNED} with the return date;</li>
 *   <li>the SEPA file can no longer be generated ({@code ETGO_RemittanceFileHasReturns});</li>
 *   <li>a reconciled settlement transaction refuses the return and changes nothing;</li>
 *   <li>grouping {@code GGPD}: two lines share one payment, one of them alone is refused
 *       ({@code SHARED_PAYMENT}) and both together are returned;</li>
 *   <li>REM's own processes run the same on a remittance whose SEPA columns are NULL (one from
 *       before they existed) and on one that carries them: the trigger's freeze of the processed
 *       remittance does not stop them; values set before processing come out unchanged, and a
 *       NULL one gets the copy the file used.</li>
 * </ul>
 *
 * <p>The sample data: GOClient's sales dues FV1000045 and FV1000046 (EUR 40.91 each, the same
 * partner and due date, so {@code GGPD} merges them), remitted from «Cuenta Remesa ETP-6148»; the
 * test runs as GOClient's admin role. The same database the SEPA file integration test uses.
 *
 * <p>A class of its own: {@code RemittanceReturnActionTest} mocks the module's return and
 * {@code RemittanceFileGeneratorIntegrationTest} is about the SEPA file.
 *
 * @covers com.etendoerp.go.schemaforge.RemittanceReturnAction
 * @covers com.etendoerp.go.schemaforge.RemittanceReturnState
 * @covers com.etendoerp.go.schemaforge.RemittanceReturnProcess
 * @covers com.etendoerp.go.schemaforge.RemittanceFileAction
 * @covers com.etendoerp.go.schemaforge.RemittanceStatusAction
 * @covers com.etendoerp.go.schemaforge.RemittanceProcessAction
 * @covers com.etendoerp.go.schemaforge.RemittanceIban
 * @covers com.etendoerp.go.schemaforge.RemittanceCreditorId
 */
public class RemittanceReturnIntegrationTest extends WeldBaseTest {

  private static final String PARTNER_IBAN = "ES7921000813610123456789";
  private static final String SPAIN_ID = "106";

  private static final String GO_CLIENT_ID = "802509E12436405C86BA1FD5B1DF508C";
  private static final String GO_ORG_ID = "61849243BE89460EB70866880A545D50";
  private static final String GO_ADMIN_ROLE_ID = "9B8D736190724807AB256DC95F20EC5E";
  private static final String GO_ACCOUNT_ID = "1728F0AC26EF478287FE8F9210052AC8";
  /** FV1000045 and FV1000046: same partner, same due date, EUR 40.91 each. */
  private static final String DUE_A_DETAIL_ID = "A2324465AF674B82AD63A43AA0703A25";
  private static final String DUE_B_DETAIL_ID = "48B9DA0B195244EDA1D75BA5706052E6";
  private static final BigDecimal DUE_AMOUNT = new BigDecimal("40.91");
  /** A valid IBAN and creditor identifier written on the remittance (not the account's). */
  private static final String REMITTANCE_IBAN = "ES9121000418450200051332";
  private static final String CREDITOR_ID = "ES97000B12345678";

  private final RemittanceHeaderHandler handler = new RemittanceHeaderHandler();

  @Override
  protected boolean shouldMockServletContext() {
    return true;
  }

  @Before
  public void openTheRequest() {
    setTestAdminContext();
    SessionHandler.getInstance().setDoRollback(true);
    OBContext.setAdminMode(true);
  }

  @After
  public void rollBack() {
    try {
      OBDal.getInstance().rollbackAndClose();
    } finally {
      while (OBContext.getOBContext() != null
          && OBContext.getOBContext().isInAdministratorMode()) {
        OBContext.restorePreviousMode();
      }
    }
  }

  // ---------------------------------------------------------------- the happy path

  @Test
  public void aSettledLineIsReturnableAndItsReturnRestoresTheDue() throws Exception {
    Remittance remittance = confirmed(RemittanceProcessAction.GROUPING_NONE, DUE_A_DETAIL_ID);
    String remittanceId = remittance.getId();
    String lineId = lineIds(remittance).get(0);
    String scheduleId = scheduleIdOf(lineId);
    String invoiceId = invoiceOf(scheduleId).getId();

    JSONObject before = lineOf(RemittanceReturnAction.state(remittance), lineId);
    assertEquals("SETTLED", before.getString("state"));
    assertTrue(before.toString(), before.getBoolean("returnable"));
    assertTrue(before.isNull("reasonCode"));
    String paymentId = before.getString("paymentId");
    FIN_Payment payment = OBDal.getInstance().get(FIN_Payment.class, paymentId);
    assertEquals("the settlement was deposited", true,
        !payment.getFINFinaccTransactionList().isEmpty());
    assertEquals("the due is paid by the payment", 0,
        outstandingOf(scheduleId).compareTo(BigDecimal.ZERO));
    String returnDay = LocalDate.now().minusDays(2).toString();

    NeoResponse response = RemittanceReturnAction.returnLines(handler, remittance,
        params(returnDay, lineId));

    assertEquals(String.valueOf(response.getBody()), 200, response.getHttpStatus());
    JSONObject body = response.getBody();
    assertEquals("CP", body.getString("status"));
    assertEquals("[\"" + lineId + "\"]", body.getJSONArray("returned").toString());

    OBDal.getInstance().flush();
    Remittance reloaded = OBDal.getInstance().get(Remittance.class, remittanceId);
    payment = OBDal.getInstance().get(FIN_Payment.class, paymentId);
    OBDal.getInstance().refresh(payment);
    assertEquals("the original payment is void", "RPVOID", payment.getStatus());
    assertTrue("the settlement row is gone",
        RemittanceReturnState.settlementRows(reloaded).isEmpty());
    assertEquals("no Cancel row of the remittance remains", 0L, count(
        "select count(c.id) from " + RemittanceLineCancel.ENTITY_NAME
            + " c where c.remittance.id = :id", remittanceId));
    assertTrue("a Return row exists", RemittanceReturnState.hasReturns(reloaded));

    FIN_Payment negative = OBDal.getInstance().get(FIN_Payment.class,
        body.getString("returnPaymentId"));
    assertNotNull("the negative payment exists", negative);
    assertEquals("the negative payment returns the amount", 0,
        DUE_AMOUNT.negate().compareTo(negative.getAmount()));
    assertEquals(negative.getDocumentNo(), body.getString("returnPaymentDocumentNo"));

    assertEquals("the due is pending again, in full", 0,
        DUE_AMOUNT.compareTo(outstandingOf(scheduleId)));
    assertEquals("nothing is paid", 0, BigDecimal.ZERO.compareTo(paidOf(scheduleId)));
    assertEquals("the pending details add up to the due", 0,
        DUE_AMOUNT.compareTo(pendingDetailsOf(scheduleId)));
    Invoice invoice = OBDal.getInstance().get(Invoice.class, invoiceId);
    OBDal.getInstance().refresh(invoice);
    assertFalse("the invoice is not paid", Boolean.TRUE.equals(invoice.isPaymentComplete()));
    assertEquals("the invoice owes it again", 0,
        DUE_AMOUNT.compareTo(invoice.getOutstandingAmount()));

    OBDal.getInstance().refresh(reloaded);
    assertEquals("CP", String.valueOf(reloaded.get(RemittanceStatusUtil.PROPERTY_REMITTANCE_STATUS)));
    JSONObject after = RemittanceReturnAction.state(reloaded);
    assertTrue(after.getBoolean("hasReturns"));
    JSONObject afterLine = lineOf(after, lineId);
    assertEquals("RETURNED", afterLine.getString("state"));
    assertFalse(afterLine.getBoolean("returnable"));
    assertEquals("ALREADY_RETURNED", afterLine.getString("reasonCode"));
    assertEquals(returnDay, afterLine.getString("returnDate"));

    NeoResponse file = RemittanceFileAction.generateFile(reloaded);
    assertEquals(422, file.getHttpStatus());
    assertEquals("ETGO_RemittanceFileHasReturns", file.getBody().getJSONObject("error")
        .getJSONArray("messageKeys").getString(0));

    NeoResponse again = RemittanceReturnAction.returnLines(handler, reloaded,
        params(null, lineId));
    assertEquals("a returned line cannot be returned twice", 422, again.getHttpStatus());
    assertEquals("ALREADY_RETURNED", again.getBody().getJSONObject("error")
        .getJSONArray("problems").getJSONObject(0).getString("reasonCode"));
  }

  // ---------------------------------------------------------------- a refusal

  @Test
  public void aReconciledSettlementIsRefusedAndNothingChanges() throws Exception {
    Remittance remittance = confirmed(RemittanceProcessAction.GROUPING_NONE, DUE_A_DETAIL_ID);
    String lineId = lineIds(remittance).get(0);
    String paymentId = lineOf(RemittanceReturnAction.state(remittance), lineId)
        .getString("paymentId");
    FIN_Payment payment = OBDal.getInstance().get(FIN_Payment.class, paymentId);
    assertFalse("the settlement was deposited", payment.getFINFinaccTransactionList().isEmpty());
    FIN_FinaccTransaction transaction = payment.getFINFinaccTransactionList().get(0);
    transaction.setReconciliation(reconciliation(transaction.getAccount()));
    OBDal.getInstance().save(transaction);
    OBDal.getInstance().flush();

    JSONObject line = lineOf(RemittanceReturnAction.state(remittance), lineId);
    assertEquals("SETTLED", line.getString("state"));
    assertFalse(line.getBoolean("returnable"));
    assertEquals("RECONCILED", line.getString("reasonCode"));

    NeoResponse response = RemittanceReturnAction.returnLines(handler, remittance,
        params(null, lineId));

    assertEquals(422, response.getHttpStatus());
    JSONObject error = response.getBody().getJSONObject("error");
    assertEquals("ETGO_RemittanceReturnNotAllowed", error.getJSONArray("messageKeys").getString(0));
    assertEquals("RECONCILED", error.getJSONArray("problems").getJSONObject(0)
        .getString("reasonCode"));
    OBDal.getInstance().refresh(payment);
    assertEquals("the payment is untouched", false, "RPVOID".equals(payment.getStatus()));
    assertFalse("the settlement row is still there",
        RemittanceReturnState.settlementRows(remittance).isEmpty());
    assertFalse(RemittanceReturnState.hasReturns(remittance));
  }

  // ---------------------------------------------------------------- a shared payment (GGPD)

  @Test
  public void aGroupedPaymentIsReturnedWholeNeverByOneOfItsLines() throws Exception {
    Remittance remittance = confirmed(RemittanceProcessAction.GROUPING_BP_DUE_DATE,
        DUE_A_DETAIL_ID, DUE_B_DETAIL_ID);
    List<String> ids = lineIds(remittance);
    JSONObject first = lineOf(RemittanceReturnAction.state(remittance), ids.get(0));
    JSONObject second = lineOf(RemittanceReturnAction.state(remittance), ids.get(1));
    assertEquals("GGPD merges both lines into one payment", first.getString("paymentId"),
        second.getString("paymentId"));
    assertEquals(new JSONArray(ids).toString(), first.getJSONArray("paymentLineIds").toString());
    assertTrue(first.getBoolean("returnable"));
    String paymentId = first.getString("paymentId");
    List<String> scheduleIds = new ArrayList<>();
    for (String id : ids) {
      scheduleIds.add(scheduleIdOf(id));
    }

    NeoResponse partial = RemittanceReturnAction.returnLines(handler, remittance,
        params(null, ids.get(0)));

    assertEquals(422, partial.getHttpStatus());
    JSONObject problem = partial.getBody().getJSONObject("error").getJSONArray("problems")
        .getJSONObject(0);
    assertEquals("SHARED_PAYMENT", problem.getString("reasonCode"));
    assertEquals(ids.get(0), problem.getString("lineId"));
    FIN_Payment payment = OBDal.getInstance().get(FIN_Payment.class, paymentId);
    assertFalse("nothing was returned", "RPVOID".equals(payment.getStatus()));

    NeoResponse whole = RemittanceReturnAction.returnLines(handler, remittance,
        params(null, ids.toArray(new String[0])));

    assertEquals(String.valueOf(whole.getBody()), 200, whole.getHttpStatus());
    OBDal.getInstance().flush();
    Remittance reloaded = OBDal.getInstance().get(Remittance.class, remittance.getId());
    JSONObject state = RemittanceReturnAction.state(reloaded);
    for (String id : ids) {
      assertEquals("RETURNED", lineOf(state, id).getString("state"));
    }
    payment = OBDal.getInstance().get(FIN_Payment.class, paymentId);
    OBDal.getInstance().refresh(payment);
    assertEquals("RPVOID", payment.getStatus());
    for (String scheduleId : scheduleIds) {
      assertEquals("both dues are pending again", 0,
          DUE_AMOUNT.compareTo(outstandingOf(scheduleId)));
      assertEquals(0, BigDecimal.ZERO.compareTo(paidOf(scheduleId)));
    }
  }

  // ---------------------------------------------------------------- the step flow

  @Test
  public void confirmProcessesWithoutSettlingAttachesTheFileAndLeavesTheRemittanceInPr()
      throws Exception {
    Remittance remittance = built(RemittanceProcessAction.GROUPING_NONE, DUE_A_DETAIL_ID);
    String remittanceId = remittance.getId();

    NeoResponse response = RemittanceFileAction.confirm(remittance, new JSONObject());

    assertEquals(String.valueOf(response.getBody()), 200, response.getHttpStatus());
    assertEquals("PR", response.getBody().getString("status"));
    Attachment file = OBDal.getInstance().get(Attachment.class,
        response.getBody().getString("attachmentId"));
    assertNotNull("the SEPA file is attached", file);
    assertTrue(file.getName(), file.getName().endsWith(".xml"));

    Remittance reloaded = OBDal.getInstance().get(Remittance.class, remittanceId);
    OBDal.getInstance().refresh(reloaded);
    assertTrue(reloaded.isProcessed());
    assertEquals("PR", String.valueOf(reloaded.get(RemittanceStatusUtil.PROPERTY_REMITTANCE_STATUS)));
    assertFalse("the automatic settlement is off", Boolean.TRUE.equals(
        reloaded.isETARAutomaticprocess()));
    String lineId = lineIds(reloaded).get(0);
    JSONObject line = lineOf(RemittanceReturnAction.state(reloaded), lineId);
    FIN_Payment payment = OBDal.getInstance().get(FIN_Payment.class, line.getString("paymentId"));
    assertEquals("the payment is only remitted", "REM_SENT", payment.getStatus());
    assertTrue("nothing is deposited", payment.getFINFinaccTransactionList().isEmpty());
    String scheduleId = scheduleIdOf(lineId);
    assertEquals("the due is still owed", 0, DUE_AMOUNT.compareTo(outstandingOf(scheduleId)));
    Invoice invoice = invoiceOf(scheduleId);
    OBDal.getInstance().refresh(invoice);
    assertFalse("the invoice is unpaid", Boolean.TRUE.equals(invoice.isPaymentComplete()));
    assertEquals("PROCESSED", line.getString("state"));
    assertEquals("NOT_SETTLED", line.getString("reasonCode"));
    assertFalse(line.getBoolean("returnable"));

    NeoResponse early = RemittanceReturnAction.returnLines(handler, reloaded,
        params(null, lineId));
    assertEquals("nothing can be returned before it is collected", 422, early.getHttpStatus());
    assertEquals("NOT_SETTLED", early.getBody().getJSONObject("error").getJSONArray("problems")
        .getJSONObject(0).getString("reasonCode"));
  }

  @Test
  public void markSentIsInformationalAndOnlyFromPr() throws Exception {
    Remittance remittance = processed(RemittanceProcessAction.GROUPING_NONE, DUE_A_DETAIL_ID);
    String lineId = lineIds(remittance).get(0);
    String paymentId = lineOf(RemittanceReturnAction.state(remittance), lineId)
        .getString("paymentId");
    String scheduleId = scheduleIdOf(lineId);

    NeoResponse sent = RemittanceStatusAction.markSent(remittance);

    assertEquals(String.valueOf(sent.getBody()), 200, sent.getHttpStatus());
    assertEquals("SB", sent.getBody().getString("status"));
    Remittance reloaded = OBDal.getInstance().get(Remittance.class, remittance.getId());
    OBDal.getInstance().refresh(reloaded);
    assertEquals("SB", String.valueOf(reloaded.get(RemittanceStatusUtil.PROPERTY_REMITTANCE_STATUS)));
    FIN_Payment payment = OBDal.getInstance().get(FIN_Payment.class, paymentId);
    OBDal.getInstance().refresh(payment);
    assertEquals("nothing is settled", "REM_SENT", payment.getStatus());
    assertEquals(0, DUE_AMOUNT.compareTo(outstandingOf(scheduleId)));
    assertEquals("a line is still not returnable", "NOT_SETTLED",
        lineOf(RemittanceReturnAction.state(reloaded), lineId).getString("reasonCode"));

    NeoResponse again = RemittanceStatusAction.markSent(reloaded);

    assertEquals(422, again.getHttpStatus());
    assertEquals(RemittanceStatusAction.MSG_SENT_NOT_ALLOWED, again.getBody().getJSONObject(
        "error").getJSONArray("messageKeys").getString(0));
  }

  @Test
  public void markCollectedFromPrSettlesOnTheRemittanceDueDate() throws Exception {
    Remittance remittance = processed(RemittanceProcessAction.GROUPING_NONE, DUE_A_DETAIL_ID);
    assertCollectedOnTheDueDate(remittance);
  }

  @Test
  public void markCollectedFromSbSettlesTheSame() throws Exception {
    Remittance remittance = processed(RemittanceProcessAction.GROUPING_NONE, DUE_A_DETAIL_ID);
    assertEquals(200, RemittanceStatusAction.markSent(remittance).getHttpStatus());
    assertCollectedOnTheDueDate(remittance);
  }

  /**
   * The whole flow on a remittance whose SEPA columns are all NULL, as one created before they
   * existed (the trigger fills nothing): REM processes, settles and returns it. The file, made
   * after REM processed it, keeps the IBAN and creditor identifier it used as the remittance's
   * copy ({@link RemittanceIban#forFile}, {@link RemittanceCreditorId#forFile}): a NULL to value
   * write on a processed remittance, which the freeze lets through. The scheme stays NULL (CORE is
   * resolved when the file is made).
   */
  @Test
  public void theWholeStepFlowEndsInAReturn() throws Exception {
    Remittance remittance = built(RemittanceProcessAction.GROUPING_NONE, DUE_A_DETAIL_ID);
    assertSepaValues(remittance, null, null, null);
    String accountIban = RemittanceIban.normalize(remittance.getFinancialAccount().getIBAN());

    Remittance returned = wholeStepFlow(remittance);

    assertEquals("the IBAN the file used is kept", accountIban, returned.getIBAN());
    assertTrue("the creditor identifier the file used is kept: " + returned.getCreditorIdentifier(),
        SepaCreditorId.isValid(returned.getCreditorIdentifier()));
    assertNull("the scheme is not written", returned.getDirectDebitScheme());
    assertFalse(Boolean.TRUE.equals(returned.isFinanced()));
  }

  /**
   * The same flow with the SEPA values set: the trigger's freeze does not get in the way of REM's
   * own updates of the processed remittance (status, payments, settlement, return), and the values
   * reach the end unchanged.
   */
  @Test
  public void theWholeStepFlowWithSepaValuesKeepsThemToTheReturn() throws Exception {
    Remittance remittance = withSepaValues(
        built(RemittanceProcessAction.GROUPING_NONE, DUE_A_DETAIL_ID));

    Remittance returned = wholeStepFlow(remittance);

    assertSepaValues(returned, REMITTANCE_IBAN, CREDITOR_ID,
        RemittanceSepaProvisioning.SCHEME_CORE);
  }

  @Test
  public void reactivateWithSepaValuesKeepsThem() throws Exception {
    Remittance remittance = withSepaValues(
        built(RemittanceProcessAction.GROUPING_NONE, DUE_A_DETAIL_ID));
    NeoResponse processed = RemittanceProcessAction.process(remittance,
        new JSONObject().put(RemittanceProcessAction.P_GROUPING,
            RemittanceProcessAction.GROUPING_NONE));
    assertEquals(String.valueOf(processed.getBody()), 200, processed.getHttpStatus());
    remittance = OBDal.getInstance().get(Remittance.class, remittance.getId());

    assertReactivated(remittance);

    Remittance reloaded = OBDal.getInstance().get(Remittance.class, remittance.getId());
    OBDal.getInstance().refresh(reloaded);
    assertSepaValues(reloaded, REMITTANCE_IBAN, CREDITOR_ID,
        RemittanceSepaProvisioning.SCHEME_CORE);
  }

  /** Confirm, mark sent, collect and return the only line; the returned remittance, reread. */
  private Remittance wholeStepFlow(Remittance remittance) throws Exception {
    NeoResponse confirm = RemittanceFileAction.confirm(remittance, new JSONObject());
    assertEquals(String.valueOf(confirm.getBody()), 200, confirm.getHttpStatus());
    Remittance confirmed = OBDal.getInstance().get(Remittance.class, remittance.getId());
    assertEquals(200, RemittanceStatusAction.markSent(confirmed).getHttpStatus());
    collect(confirmed);
    Remittance collected = OBDal.getInstance().get(Remittance.class, remittance.getId());
    String lineId = lineIds(collected).get(0);
    assertEquals("SETTLED", lineOf(RemittanceReturnAction.state(collected), lineId)
        .getString("state"));

    NeoResponse returned = RemittanceReturnAction.returnLines(handler, collected,
        params(null, lineId));

    assertEquals(String.valueOf(returned.getBody()), 200, returned.getHttpStatus());
    assertEquals("CP", returned.getBody().getString("status"));
    OBDal.getInstance().flush();
    Remittance reloaded = OBDal.getInstance().get(Remittance.class, remittance.getId());
    OBDal.getInstance().refresh(reloaded);
    return reloaded;
  }

  @Test
  public void reactivateIsAllowedOnPrAndRemovesThePayments() throws Exception {
    Remittance remittance = processed(RemittanceProcessAction.GROUPING_NONE, DUE_A_DETAIL_ID);
    assertReactivated(remittance);
  }

  @Test
  public void reactivateIsAllowedOnSbToo() throws Exception {
    Remittance remittance = processed(RemittanceProcessAction.GROUPING_NONE, DUE_A_DETAIL_ID);
    assertEquals(200, RemittanceStatusAction.markSent(remittance).getHttpStatus());
    assertReactivated(remittance);
  }

  @Test
  public void reactivateIsRefusedOnceCollectedPaidPartialOrVoided() throws Exception {
    Remittance remittance = confirmed(RemittanceProcessAction.GROUPING_NONE, DUE_A_DETAIL_ID);
    String id = remittance.getId();
    assertReactivationRefused(remittance, "CT");
    for (String status : new String[] {"PT", "CP", "PP", "AN"}) {
      OBDal.getInstance().getSession().doWork(connection -> {
        try (java.sql.PreparedStatement statement = connection.prepareStatement(
            "update rem_remittance set remittance_status = ? where rem_remittance_id = ?")) {
          statement.setString(1, status);
          statement.setString(2, id);
          statement.executeUpdate();
        }
      });
      OBDal.getInstance().refresh(remittance);
      assertReactivationRefused(remittance, status);
    }
  }

  @Test
  public void aFailedSettlementOutsideABatchRollsEverythingBack() throws Exception {
    Remittance remittance = twoPaymentsOneWithABankTransaction();
    String remittanceId = remittance.getId();

    NeoResponse response = RemittanceStatusAction.markCollected(remittance);

    assertEquals(422, response.getHttpStatus());
    assertEquals(RemittanceStatusAction.MSG_COLLECTED_FAILED, response.getBody()
        .getJSONObject("error").getJSONArray("messageKeys").getString(0));
    assertNull("the whole transaction was rolled back, the settled payment included",
        OBDal.getInstance().get(Remittance.class, remittanceId));
  }

  @Test
  public void inABatchAFailedSettlementIsLeftToTheBatchAndNeverDeclaredCollected()
      throws Exception {
    Remittance remittance = twoPaymentsOneWithABankTransaction();
    String remittanceId = remittance.getId();

    NeoResponse response;
    BatchService.beginCallerOwnedTransaction();
    try {
      response = RemittanceStatusAction.markCollected(remittance);
      assertEquals(422, response.getHttpStatus());
      assertEquals(RemittanceStatusAction.MSG_COLLECTED_FAILED, response.getBody()
          .getJSONObject("error").getJSONArray("messageKeys").getString(0));
      Remittance reloaded = OBDal.getInstance().get(Remittance.class, remittanceId);
      assertNotNull("the action did not close the session under the batch", reloaded);
      assertFalse("the remittance is not flagged as settled",
          Boolean.TRUE.equals(reloaded.isETARAutomaticprocess()));
      Object status = reloaded.get(RemittanceStatusUtil.PROPERTY_REMITTANCE_STATUS);
      assertFalse("and never reports collected: " + status,
          RemittanceStatusUtil.COLLECTED.equals(status));
    } finally {
      BatchService.endCallerOwnedTransaction();
    }
  }

  /** Two payments (one per line); the second already has a bank transaction, REM refuses it. */
  private Remittance twoPaymentsOneWithABankTransaction() throws Exception {
    Remittance remittance = processed(RemittanceProcessAction.GROUPING_NONE, DUE_A_DETAIL_ID,
        DUE_B_DETAIL_ID);
    List<String> ids = lineIds(remittance);
    assertEquals(2, ids.size());
    FIN_Payment second = OBDal.getInstance().get(FIN_Payment.class,
        lineOf(RemittanceReturnAction.state(remittance), ids.get(1)).getString("paymentId"));
    FIN_Payment first = OBDal.getInstance().get(FIN_Payment.class,
        lineOf(RemittanceReturnAction.state(remittance), ids.get(0)).getString("paymentId"));
    assertFalse("one payment per line", first.getId().equals(second.getId()));
    FIN_FinaccTransaction existing = TransactionsDao.createFinAccTransaction(second);
    existing.setAccount(remittance.getFinancialAccount());
    OBDal.getInstance().save(existing);
    OBDal.getInstance().flush();
    return remittance;
  }

  private void assertCollectedOnTheDueDate(Remittance remittance) throws Exception {
    String remittanceId = remittance.getId();
    String lineId = lineIds(remittance).get(0);
    String paymentId = lineOf(RemittanceReturnAction.state(remittance), lineId)
        .getString("paymentId");
    String scheduleId = scheduleIdOf(lineId);
    Date dueDate = remittance.getDueDate();

    NeoResponse collected = RemittanceStatusAction.markCollected(remittance);

    assertEquals(String.valueOf(collected.getBody()), 200, collected.getHttpStatus());
    assertEquals("CT", collected.getBody().getString("status"));
    assertEquals(new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT)
        .format(dueDate), collected.getBody().getString("settledOn"));
    Remittance reloaded = OBDal.getInstance().get(Remittance.class, remittanceId);
    OBDal.getInstance().refresh(reloaded);
    assertEquals("CT", String.valueOf(reloaded.get(RemittanceStatusUtil.PROPERTY_REMITTANCE_STATUS)));
    assertTrue("the automatic flag is set", Boolean.TRUE.equals(reloaded.isETARAutomaticprocess()));
    FIN_Payment payment = OBDal.getInstance().get(FIN_Payment.class, paymentId);
    OBDal.getInstance().refresh(payment);
    assertFalse("a bank transaction settles the payment",
        payment.getFINFinaccTransactionList().isEmpty());
    assertEquals("on the remittance due date", new java.text.SimpleDateFormat("yyyy-MM-dd",
        java.util.Locale.ROOT).format(dueDate), new java.text.SimpleDateFormat("yyyy-MM-dd",
        java.util.Locale.ROOT).format(
            payment.getFINFinaccTransactionList().get(0).getTransactionDate()));
    assertEquals("the due is paid", 0, outstandingOf(scheduleId).compareTo(BigDecimal.ZERO));
    Invoice invoice = invoiceOf(scheduleId);
    OBDal.getInstance().refresh(invoice);
    assertTrue("the invoice is paid", Boolean.TRUE.equals(invoice.isPaymentComplete()));
    assertEquals("SETTLED", lineOf(RemittanceReturnAction.state(reloaded), lineId)
        .getString("state"));
  }

  private void assertReactivated(Remittance remittance) throws Exception {
    String remittanceId = remittance.getId();
    String lineId = lineIds(remittance).get(0);
    String paymentId = lineOf(RemittanceReturnAction.state(remittance), lineId)
        .getString("paymentId");
    String scheduleId = scheduleIdOf(lineId);

    NeoResponse reactivated = RemittanceProcessAction.reactivate(remittance);

    assertEquals(String.valueOf(reactivated.getBody()), 200, reactivated.getHttpStatus());
    assertEquals("PE", reactivated.getBody().getString("status"));
    Remittance reloaded = OBDal.getInstance().get(Remittance.class, remittanceId);
    OBDal.getInstance().refresh(reloaded);
    assertFalse(Boolean.TRUE.equals(reloaded.isProcessed()));
    assertEquals(0L, count("select count(p.id) from FIN_Payment p where p.id = :id", paymentId));
    assertEquals("the due is owed in full again", 0,
        DUE_AMOUNT.compareTo(outstandingOf(scheduleId)));
  }

  private static void assertReactivationRefused(Remittance remittance, String status)
      throws Exception {
    NeoResponse refused = RemittanceProcessAction.reactivate(remittance);
    assertEquals(status, 422, refused.getHttpStatus());
    assertEquals(status, RemittanceProcessAction.MSG_REACTIVATE_NOT_ALLOWED,
        refused.getBody().getJSONObject("error").getJSONArray("messageKeys").getString(0));
    assertTrue("nothing was reactivated (" + status + ")", Boolean.TRUE.equals(
        remittance.isProcessed()));
  }

  // ---------------------------------------------------------------- fixtures

  /** A GOClient collection remittance with one line per due, processed by REM and collected. */
  private Remittance confirmed(String grouping, String... dueDetailIds) throws Exception {
    Remittance remittance = processed(grouping, dueDetailIds);
    collect(remittance);
    return OBDal.getInstance().get(Remittance.class, remittance.getId());
  }

  /** «Cobrada» on {@code remittance}: the settlement on its due date, as the step does. */
  private static void collect(Remittance remittance) throws Exception {
    NeoResponse collected = RemittanceStatusAction.markCollected(remittance);
    assertEquals(String.valueOf(collected.getBody()), 200, collected.getHttpStatus());
    assertEquals("CT", collected.getBody().getString("status"));
  }

  /**
   * A GOClient collection remittance with one line per due, processed by REM without the
   * automatic settlement (status {@code PR}): payments remitted, invoices still unpaid.
   */
  private Remittance processed(String grouping, String... dueDetailIds) throws Exception {
    Remittance remittance = built(grouping, dueDetailIds);
    NeoResponse processed = RemittanceProcessAction.process(remittance,
        new JSONObject().put(RemittanceProcessAction.P_GROUPING, grouping));
    assertEquals(String.valueOf(processed.getBody()), 200, processed.getHttpStatus());
    // REM may clear the session (grouped payments): read the remittance again by id.
    remittance = OBDal.getInstance().get(Remittance.class, remittance.getId());
    assertTrue("REM left the remittance unprocessed: " + processed.getBody(),
        Boolean.TRUE.equals(remittance.isProcessed()));
    return remittance;
  }

  /** A GOClient collection remittance with one line per due, not processed yet. */
  private Remittance built(String grouping, String... dueDetailIds) throws Exception {
    OBContext.setOBContext("100", GO_ADMIN_ROLE_ID, GO_CLIENT_ID, GO_ORG_ID);
    OBContext.setAdminMode(true);
    // No request context is set up here on purpose: the bank transaction removal reads the
    // request's variables, and the action must provide them itself (it did not, and failed with
    // "No request object set").
    Client client = OBDal.getInstance().get(Client.class, GO_CLIENT_ID);
    RemittanceSepaProvisioning.ensureClient(client);
    FIN_FinancialAccount account = OBDal.getInstance().get(FIN_FinancialAccount.class,
        GO_ACCOUNT_ID);
    assertNotNull("GOClient's remittance account", account);
    RemittanceSepaProvisioning.ensureAccountLinks(account);
    completeOrganization(account.getOrganization());
    RemittanceType type = RemittanceSepaProvisioning.findType(client,
        RemittanceSepaProvisioning.SCHEME_CORE);
    assertNotNull("the CORE type", type);
    OBDal.getInstance().flush();
    OBDal.getInstance().refresh(type);

    Remittance remittance = OBProvider.getInstance().get(Remittance.class);
    remittance.setClient(client);
    remittance.setOrganization(account.getOrganization());
    remittance.setFinancialAccount(account);
    remittance.setRemittanceType(type);
    remittance.setDocumentType(remittanceDocumentType(client));
    remittance.setDocumentNo("SF-RET-IT-" + grouping);
    remittance.setName("SF RET IT " + grouping);
    remittance.setTransactionDate(day(LocalDate.now()));
    remittance.setDueDate(day(LocalDate.now().plusDays(10)));
    remittance.setProcessed(false);
    OBDal.getInstance().save(remittance);
    long lineNo = 10L;
    Set<String> bankAccountGiven = new HashSet<>();
    for (String dueId : dueDetailIds) {
      FIN_PaymentScheduleDetail due = OBDal.getInstance().get(FIN_PaymentScheduleDetail.class,
          dueId);
      assertNotNull("GOClient's sales due " + dueId, due);
      BusinessPartner partner = due.getInvoicePaymentSchedule().getInvoice().getBusinessPartner();
      if (partner.getObsepadMandateIdentifier() == null) {
        partner.setObsepadMandateIdentifier("MANDATE-IT-1");
      }
      if (partner.getObsepadMandateDate() == null) {
        partner.setObsepadMandateDate(day(LocalDate.now().minusDays(30)));
      }
      OBDal.getInstance().save(partner);
      if (bankAccountGiven.add(partner.getId())) {
        giveBankAccount(partner);
      }
      RemittanceLine line = OBProvider.getInstance().get(RemittanceLine.class);
      line.setClient(client);
      line.setOrganization(remittance.getOrganization());
      line.setRemittance(remittance);
      line.setPaymentScheduleDetail(due);
      line.setLineNo(lineNo);
      line.setAmount(due.getAmount());
      remittance.getREMRemittanceLineList().add(line);
      OBDal.getInstance().save(line);
      lineNo += 10L;
    }
    OBDal.getInstance().flush();
    // A remittance created in this session keeps its inverse instruction list as an empty,
    // initialized collection (a refresh initializes it too). REM adds each bank instruction by
    // setRemittance() only, and Classic's generator writes GrpHdr/NbOfTxs from
    // getREMInstructionList().size(), so the file would declare 0 transactions. The step actions
    // run in a request of their own that loads the remittance from the database: start from a
    // clean session, as that request does.
    String remittanceId = remittance.getId();
    OBDal.getInstance().getSession().clear();
    return OBDal.getInstance().get(Remittance.class, remittanceId);
  }

  /** Sets the remittance's own SEPA values, as the GO write path stores them, before it is processed. */
  private static Remittance withSepaValues(Remittance remittance) {
    remittance.setIBAN(REMITTANCE_IBAN);
    remittance.setCreditorIdentifier(CREDITOR_ID);
    remittance.setDirectDebitScheme(RemittanceSepaProvisioning.SCHEME_CORE);
    OBDal.getInstance().save(remittance);
    OBDal.getInstance().flush();
    return remittance;
  }

  private static void assertSepaValues(Remittance remittance, String iban, String creditorId,
      String scheme) {
    assertEquals("IBAN", iban, remittance.getIBAN());
    assertEquals("creditor identifier", creditorId, remittance.getCreditorIdentifier());
    assertEquals("direct debit scheme", scheme, remittance.getDirectDebitScheme());
    assertFalse("financed", Boolean.TRUE.equals(remittance.isFinanced()));
  }

  /** The organization's own information with a structured address, as the SEPA file needs. */
  private static void completeOrganization(Organization organization) {
    OrganizationInformation info = organization.getOrganizationInformationList().get(0);
    Location location = info.getLocationAddress();
    if (location == null) {
      location = OBProvider.getInstance().get(Location.class);
      location.setClient(organization.getClient());
      location.setOrganization(organization);
      info.setLocationAddress(location);
    }
    if (location.getCityName() == null) {
      location.setCityName("Madrid");
    }
    if (location.getCountry() == null) {
      location.setCountry(OBDal.getInstance().get(Country.class, SPAIN_ID));
    }
    location.setOBSEPAStreetName("Calle Mayor");
    location.setOBSEPABuildingNumber("1");
    OBDal.getInstance().save(location);
    OBDal.getInstance().save(info);
    OBDal.getInstance().flush();
  }

  private static List<String> lineIds(Remittance remittance) {
    List<String> ids = new ArrayList<>();
    for (RemittanceLine line : RemittanceFileReadiness.activeLines(remittance)) {
      ids.add(line.getId());
    }
    return ids;
  }

  private static String scheduleIdOf(String lineId) {
    RemittanceLine line = OBDal.getInstance().get(RemittanceLine.class, lineId);
    return line.getPaymentScheduleDetail().getInvoicePaymentSchedule().getId();
  }

  private static Invoice invoiceOf(String scheduleId) {
    return OBDal.getInstance().get(FIN_PaymentSchedule.class, scheduleId).getInvoice();
  }

  private static BigDecimal outstandingOf(String scheduleId) {
    FIN_PaymentSchedule schedule = OBDal.getInstance().get(FIN_PaymentSchedule.class, scheduleId);
    OBDal.getInstance().refresh(schedule);
    return schedule.getOutstandingAmount();
  }

  private static BigDecimal paidOf(String scheduleId) {
    FIN_PaymentSchedule schedule = OBDal.getInstance().get(FIN_PaymentSchedule.class, scheduleId);
    OBDal.getInstance().refresh(schedule);
    return schedule.getPaidAmount();
  }

  /** The amount of the due's details that no payment covers and nobody cancelled. */
  private static BigDecimal pendingDetailsOf(String scheduleId) {
    Object sum = OBDal.getInstance().getSession().createQuery(
        "select coalesce(sum(d.amount), 0) from FIN_Payment_ScheduleDetail d"
            + " where d.invoicePaymentSchedule.id = :schedule and d.canceled = false"
            + " and d.paymentDetails is null")
        .setParameter("schedule", scheduleId)
        .uniqueResult();
    return new BigDecimal(String.valueOf(sum));
  }

  private static long count(String hql, String id) {
    return ((Number) OBDal.getInstance().getSession().createQuery(hql)
        .setParameter("id", id).uniqueResult()).longValue();
  }

  private static JSONObject lineOf(JSONObject state, String lineId) throws Exception {
    return state.getJSONObject("lines").getJSONObject(lineId);
  }

  private static JSONObject params(String date, String... lineIds) throws Exception {
    JSONObject params = new JSONObject().put("lineIds", new JSONArray(List.of(lineIds)));
    if (date != null) {
      params.put("returnDate", date);
    }
    return params;
  }

  /** A reconciliation of {@code account}, the minimum its columns require. */
  private static FIN_Reconciliation reconciliation(FIN_FinancialAccount account) {
    DocumentType docType = (DocumentType) OBDal.getInstance().getSession().createQuery(
        "from DocumentType d where d.documentCategory = 'REC' and d.active = true")
        .setMaxResults(1).uniqueResult();
    assertNotNull("a reconciliation document type", docType);
    FIN_Reconciliation reconciliation = OBProvider.getInstance().get(FIN_Reconciliation.class);
    reconciliation.setClient(account.getClient());
    reconciliation.setOrganization(account.getOrganization());
    reconciliation.setAccount(account);
    reconciliation.setDocumentType(docType);
    reconciliation.setDocumentNo("SF-RET-REC-1");
    reconciliation.setEndingDate(day(LocalDate.now()));
    reconciliation.setTransactionDate(day(LocalDate.now()));
    reconciliation.setEndingBalance(BigDecimal.ZERO);
    reconciliation.setStartingbalance(BigDecimal.ZERO);
    reconciliation.setDocumentStatus("DR");
    reconciliation.setProcessed(false);
    OBDal.getInstance().save(reconciliation);
    OBDal.getInstance().flush();
    return reconciliation;
  }

  /** A default IBAN bank account for {@code partner}, the one REM writes on the instruction. */
  private static void giveBankAccount(BusinessPartner partner) {
    BankAccount bankAccount = OBProvider.getInstance().get(BankAccount.class);
    bankAccount.setClient(partner.getClient());
    bankAccount.setOrganization(partner.getOrganization());
    bankAccount.setBusinessPartner(partner);
    bankAccount.setBankFormat("IBAN");
    bankAccount.setIBAN(PARTNER_IBAN);
    bankAccount.setCountry(OBDal.getInstance().get(Country.class, SPAIN_ID));
    bankAccount.setETABAMIsdefault(true);
    OBDal.getInstance().save(bankAccount);
    OBDal.getInstance().flush();
  }

  private static DocumentType remittanceDocumentType(Client client) {
    DocumentType docType = (DocumentType) OBDal.getInstance().getSession().createQuery(
        "from DocumentType as d where d.client.id = :client and d.documentCategory = 'REM_REM'"
            + " and d.active = true")
        .setParameter("client", client.getId())
        .setMaxResults(1)
        .uniqueResult();
    assertNotNull("the remittance document type", docType);
    return docType;
  }

  private static Date day(LocalDate date) {
    return Date.from(date.atStartOfDay(ZoneId.systemDefault()).toInstant());
  }
}
