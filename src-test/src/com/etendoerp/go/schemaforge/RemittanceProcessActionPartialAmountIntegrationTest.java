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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.base.weld.test.WeldBaseTest;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.core.SessionHandler;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.enterprise.DocumentType;
import org.openbravo.model.financialmgmt.payment.FIN_FinancialAccount;
import org.openbravo.model.financialmgmt.payment.FIN_Payment;
import org.openbravo.model.financialmgmt.payment.FIN_PaymentSchedule;
import org.openbravo.model.financialmgmt.payment.FIN_PaymentScheduleDetail;
import org.openbravo.module.remittance.Remittance;
import org.openbravo.module.remittance.RemittanceLine;
import org.openbravo.module.remittance.RemittanceType;
import org.openbravo.module.remittance.utility.REM_SettleProtest;
import org.openbravo.module.remittance.utility.RemittanceStatusUtil;

/**
 * Real-database run of a remittance line that takes only <b>part</b> of an invoice due («A
 * remesar» below the outstanding): the line edit accepts it ({@link RemittanceLinesHandler}),
 * «Procesar» ({@link RemittanceProcessAction}, Classic's {@code REMRemittanceProcess} with grouping
 * {@code P}) creates a payment for exactly that part and splits the schedule detail, and settling
 * the payment ({@code REM_SettleProtest.cancelPayment}) leaves the rest of the due outstanding — a
 * due a new remittance can take again — with the remittance fully settled.
 *
 * <p>Nothing in a mock shows this: it is REM and the payment engine that split the detail and
 * update the schedule. It is also the repro of the process connection fix: without
 * {@code ProcessBundle.init} the payment creation fails inside {@code ProcessData}.
 *
 * <p><b>Fixtures.</b> The F&B sample client has no open sales dues, so the due is a purchase one:
 * invoice 10001723 of F&B España (EUR 133.10 outstanding), remitted from that organization's EUR
 * account «Cuenta de Banco» with the provisioned SEPA credit-transfer type (a pay-out remittance).
 * A pay-out remittance whose payments are all settled is {@code PT} (paid), the counterpart of a
 * collection's {@code CT}. Provisioning, the remittance, its line, the payment and the settlement
 * are all written in the test transaction and <b>rolled back</b> after each test; nothing reaches
 * the shared sample data. Runs on {@link WeldBaseTest}: REM is resolved through Weld.
 *
 * <p>A class of its own: {@code RemittanceProcessActionTest} is a Mockito (JUnit 5) class that
 * stubs the Classic run; this behaviour needs the database and Weld (JUnit 4).
 *
 * @covers com.etendoerp.go.schemaforge.RemittanceProcessAction
 * @covers com.etendoerp.go.schemaforge.RemittanceLinesHandler
 * @covers com.etendoerp.go.schemaforge.RemittanceLineAmount
 */
public class RemittanceProcessActionPartialAmountIntegrationTest extends WeldBaseTest {

  /** «Cuenta de Banco» of F&B España, in EUR. */
  private static final String EUR_ACCOUNT_ID = "DEDDE613C5314ACD8DCC60C474D1A107";
  /** The only schedule detail of purchase invoice 10001723's due: EUR 133.10, unpaid. */
  private static final String DUE_DETAIL_ID = "99EE208461314B4EAB1DDFC2D014816A";
  private static final String INVOICE_NO = "10001723";
  private static final BigDecimal DUE_AMOUNT = new BigDecimal("133.10");
  private static final BigDecimal PART = new BigDecimal("50.00");
  private static final BigDecimal REST = new BigDecimal("83.10");

  private Client client;
  private FIN_FinancialAccount account;
  private FIN_PaymentScheduleDetail detail;
  private FIN_PaymentSchedule schedule;

  @Before
  public void provisionTheAccount() {
    setTestAdminContext();
    SessionHandler.getInstance().setDoRollback(true);
    OBContext.setAdminMode(true);
    client = OBDal.getInstance().get(Client.class, TEST_CLIENT_ID);
    account = OBDal.getInstance().get(FIN_FinancialAccount.class, EUR_ACCOUNT_ID);
    detail = OBDal.getInstance().get(FIN_PaymentScheduleDetail.class, DUE_DETAIL_ID);
    assertNotNull("the sample EUR account must exist", account);
    assertNotNull("the sample due must exist", detail);
    assertNull("the sample due must be unpaid", detail.getPaymentDetails());
    assertEquals(0, DUE_AMOUNT.compareTo(detail.getAmount()));
    schedule = detail.getInvoicePaymentSchedule();

    RemittanceSepaProvisioning.ensureClient(client);
    RemittanceSepaProvisioning.ensureAccountLinks(account);
  }

  @After
  public void rollbackEverything() {
    while (OBContext.getOBContext() != null
        && OBContext.getOBContext().isInAdministratorMode()) {
      OBContext.restorePreviousMode();
    }
    OBDal.getInstance().rollbackAndClose();
  }

  @Test
  public void aPartOfADueIsPaidAndSettledAndTheRestStaysOutstanding() throws Exception {
    Remittance remittance = payOutRemittance();
    RemittanceLine line = lineFor(remittance, DUE_AMOUNT);

    // The line edit: more than the due is refused against the real detail, a part is accepted.
    RemittanceLinesHandler lines = new RemittanceLinesHandler();
    NeoResponse tooMuch = lines.checkAmountUpdate(line.getId(),
        new JSONObject().put("amount", "133.11"));
    assertEquals(422, tooMuch.getHttpStatus());
    assertEquals(0, DUE_AMOUNT.compareTo(new BigDecimal(tooMuch.getBody().getJSONObject("error")
        .get(RemittanceLineAmount.KEY_MAX).toString())));
    assertNull("a part of the due is accepted",
        lines.checkAmountUpdate(line.getId(), new JSONObject().put("amount", PART.toPlainString())));
    line.setAmount(PART);
    OBDal.getInstance().save(line);
    OBDal.getInstance().flush();

    // «Procesar», one payment per line.
    NeoResponse processed = RemittanceProcessAction.process(remittance,
        new JSONObject().put("grouping", "P"));

    assertEquals(processed.getBody().toString(), 200, processed.getHttpStatus());
    assertEquals(RemittanceStatusUtil.PROCESSED, processed.getBody().getString("status"));
    OBDal.getInstance().refresh(line);
    FIN_Payment payment = line.getPayment();
    assertNotNull("REM linked the line to its payment", payment);
    assertEquals("the payment is the part, not the due", 0, PART.compareTo(payment.getAmount()));

    OBDal.getInstance().refresh(schedule);
    List<FIN_PaymentScheduleDetail> details = schedule
        .getFINPaymentScheduleDetailInvoicePaymentScheduleList();
    BigDecimal paidPart = null;
    BigDecimal openPart = null;
    for (FIN_PaymentScheduleDetail each : details) {
      if (Boolean.TRUE.equals(each.isCanceled())) {
        continue;
      }
      if (each.getPaymentDetails() != null) {
        paidPart = each.getAmount();
      } else {
        openPart = each.getAmount();
      }
    }
    assertNotNull("the detail paid by the remittance", paidPart);
    assertNotNull("the payment engine split the detail: the rest stays open", openPart);
    assertEquals(0, PART.compareTo(paidPart));
    assertEquals(0, REST.compareTo(openPart));

    // Settled: the payment is made, the rest of the due is still outstanding.
    REM_SettleProtest.cancelPayment(payment, new Date());
    OBDal.getInstance().flush();
    OBDal.getInstance().refresh(schedule);
    OBDal.getInstance().refresh(remittance);

    assertEquals(0, REST.compareTo(schedule.getOutstandingAmount()));
    assertEquals(0, PART.compareTo(schedule.getPaidAmount()));
    assertEquals("a pay-out remittance with every payment settled is paid",
        RemittanceStatusUtil.PAID, remittance.get(RemittanceStatusUtil.PROPERTY_REMITTANCE_STATUS));

    // The rest is a due a new remittance can take, for at most what is left.
    JSONArray items = RemittanceDueItems.available(unsavedPayOutRemittance(),
        new JSONObject().put("search", INVOICE_NO)).getBody().getJSONArray("items");
    JSONObject rest = null;
    for (int i = 0; i < items.length(); i++) {
      if (schedule.getId().equals(items.getJSONObject(i).getString("id"))) {
        rest = items.getJSONObject(i);
      }
    }
    assertNotNull("the rest of the due is offered again", rest);
    assertEquals(0, REST.compareTo(new BigDecimal(rest.get("outstandingAmount").toString())));
    assertEquals("not in an unprocessed remittance", false, rest.getBoolean("alreadyInRemittance"));
  }

  @Test
  public void aLineAboveItsDueIsRefusedByRemBeforeAnyPaymentIsCreated() throws Exception {
    Remittance remittance = payOutRemittance();
    lineFor(remittance, DUE_AMOUNT.add(BigDecimal.ONE));

    NeoResponse refused = RemittanceProcessAction.process(remittance,
        new JSONObject().put("grouping", "P"));

    assertEquals(refused.getBody().toString(), 422, refused.getHttpStatus());
    assertEquals("REM's own out-of-range check: the line edit refuses the same amounts",
        RemittanceProcessAction.MSG_PROCESS_FAILED, refused.getBody().getJSONObject("error")
            .getJSONArray("messageKeys").getString(0));
  }

  // ---------------------------------------------------------------- helpers

  /** A saved, unprocessed pay-out (SEPA credit transfer) remittance on the EUR account. */
  private Remittance payOutRemittance() {
    Remittance remittance = newPayOutRemittance();
    remittance.setDocumentType(remittanceDocumentType());
    remittance.setDocumentNo("SF-PARTIAL-IT");
    OBDal.getInstance().save(remittance);
    OBDal.getInstance().flush();
    return remittance;
  }

  /** An unsaved one with a fixed id: the candidate query only binds its values. */
  private Remittance unsavedPayOutRemittance() {
    Remittance remittance = newPayOutRemittance();
    remittance.setId("SFPARTIALAMOUNTITUNSAVED00000000");
    return remittance;
  }

  private Remittance newPayOutRemittance() {
    RemittanceType type = RemittanceSepaProvisioning.findType(client,
        RemittanceSepaProvisioning.SCHEME_SCT);
    assertNotNull("ensureClient provisions the SCT type", type);
    Remittance remittance = OBProvider.getInstance().get(Remittance.class);
    remittance.setClient(client);
    remittance.setOrganization(account.getOrganization());
    remittance.setFinancialAccount(account);
    remittance.setRemittanceType(type);
    remittance.setName("SF partial amount IT");
    remittance.setTransactionDate(day(LocalDate.now()));
    remittance.setDueDate(day(LocalDate.now().plusDays(30)));
    remittance.setProcessed(false);
    return remittance;
  }

  private RemittanceLine lineFor(Remittance remittance, BigDecimal amount) {
    RemittanceLine line = OBProvider.getInstance().get(RemittanceLine.class);
    line.setClient(client);
    line.setOrganization(remittance.getOrganization());
    line.setRemittance(remittance);
    line.setPaymentScheduleDetail(detail);
    line.setLineNo(10L);
    line.setAmount(amount);
    remittance.getREMRemittanceLineList().add(line);
    OBDal.getInstance().save(line);
    OBDal.getInstance().flush();
    return line;
  }

  private DocumentType remittanceDocumentType() {
    DocumentType docType = (DocumentType) OBDal.getInstance().getSession().createQuery(
        "from DocumentType as d where d.client.id = :client and d.documentCategory = 'REM_REM'"
            + " and d.active = true")
        .setParameter("client", client.getId())
        .setMaxResults(1)
        .uniqueResult();
    assertNotNull("ensureClient provisions the remittance document type", docType);
    return docType;
  }

  private static Date day(LocalDate date) {
    return Date.from(date.atStartOfDay(ZoneId.systemDefault()).toInstant());
  }
}
