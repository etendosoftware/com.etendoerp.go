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

package com.etendoerp.go.payment.invoicing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.Test;

import com.etendoerp.go.payment.invoicing.PaymentLedgerStore.PaymentRow;

/**
 * How payments are recorded from the webhook and handed to the ERP (ETP-6322): one row per paid
 * invoice, the fiscal snapshot refreshed until the invoice number freezes it, and the state checks
 * of the events the ERP reports.
 *
 * @covers com.etendoerp.go.payment.invoicing.PaymentLedger
 */
class PaymentLedgerTest {

  private static final String ID = "0123456789ABCDEF0123456789ABCDEF";
  private static final String ENVIRONMENT = "ENV00000000000000000000000000001";

  private final PaymentLedgerStore store = mock(PaymentLedgerStore.class);
  private final PaymentMethodReader methods = mock(PaymentMethodReader.class);
  private final FakeDatabase database = new FakeDatabase();
  private final PaymentLedger ledger = new PaymentLedger(store, methods, database);

  /** Runs the SQL work against a mock connection and counts how the transaction ended. */
  static final class FakeDatabase implements PaymentLedger.Database {
    final Connection connection = mock(Connection.class);
    int commits;
    int rollbacks;

    @Override
    public <T> T call(PaymentLedger.SqlWork<T> work) {
      try {
        return work.run(connection);
      } catch (SQLException | JSONException e) {
        throw new IllegalStateException(e);
      }
    }

    @Override
    public void commit() {
      commits++;
    }

    @Override
    public void rollback() {
      rollbacks++;
    }
  }

  private static PaymentRow row(String status, String invoiceNumber, String environment,
      String fiscalData) throws JSONException {
    return new PaymentRow(ID, status, invoiceNumber, "sub_1", environment, fiscalData,
        new JSONObject().put("id", ID).put("status", PaymentStatus.toApi(status)));
  }

  private static JSONObject paidEvent(long amount) throws Exception {
    return StripeInvoicePaymentTest.event("invoice.paid",
        StripeInvoicePaymentTest.currentInvoice("subscription_cycle", amount));
  }

  // --- recording -------------------------------------------------------------------------------

  @Test
  void aPaidInvoiceIsRecordedWithTheAccountTheEnvironmentAndItsSnapshots() throws Exception {
    Connection c = database.connection;
    JSONObject method = new JSONObject().put("last4", "4242");
    JSONObject fiscal = new JSONObject().put("taxId", "B12345678");
    when(store.isRecorded(c, "in_1")).thenReturn(false);
    when(methods.read("in_1")).thenReturn(method);
    when(store.accountIdFor(c, "req-1", "sub_1")).thenReturn("ACC");
    when(store.accountEmail(c, "ACC")).thenReturn("account@example.com");
    when(store.environmentClientFor(c, "sub_1", "req-1")).thenReturn(ENVIRONMENT);
    when(store.fiscalData(c, ENVIRONMENT)).thenReturn(fiscal);
    when(store.insert(eq(c), any(), eq("ACC"), eq("account@example.com"), eq(ENVIRONMENT),
        eq(fiscal), eq(method))).thenReturn(ID);

    ledger.recordPayment("invoice.paid", paidEvent(2_500));

    verify(store).addEvent(c, ID, PaymentStatus.EVENT_RECORDED, null, null, null);
    assertEquals(0, database.commits, "the webhook owns the transaction");
  }

  @Test
  void theBillingEmailFallsBackToTheOneStripeBilled() throws Exception {
    Connection c = database.connection;
    when(store.accountIdFor(c, "req-1", "sub_1")).thenReturn(null);

    ledger.recordPayment("invoice.paid", paidEvent(2_500));

    verify(store).insert(eq(c), any(), isNull(), eq("owner@example.com"), isNull(), isNull(),
        isNull());
  }

  @Test
  void aRedeliveredInvoiceAsksStripeNothingAndAddsNothing() throws Exception {
    when(store.isRecorded(database.connection, "in_1")).thenReturn(true);

    ledger.recordPayment("invoice.paid", paidEvent(2_500));

    verifyNoInteractions(methods);
    verify(store, never()).insert(any(), any(), any(), any(), any(), any(), any());
  }

  @Test
  void anInvoiceThatLostTheRaceToARedeliveryAddsNoEvent() throws Exception {
    when(store.insert(any(), any(), any(), any(), any(), any(), any())).thenReturn(null);

    ledger.recordPayment("invoice.paid", paidEvent(2_500));

    verify(store, never()).addEvent(any(), anyString(), anyString(), any(), any(), any());
  }

  @Test
  void aFreeInvoiceRecordsNothing() throws Exception {
    ledger.recordPayment("invoice.paid", paidEvent(0));

    verifyNoInteractions(store, methods);
  }

  @Test
  void aFailedWriteReachesTheWebhook() throws Exception {
    when(store.insert(any(), any(), any(), any(), any(), any(), any()))
        .thenThrow(new SQLException("boom"));

    assertThrows(IllegalStateException.class,
        () -> ledger.recordPayment("invoice.paid", paidEvent(2_500)));
  }

  // --- listing and the fiscal snapshot ---------------------------------------------------------

  @Test
  void anUnknownStatusOrLimitIsRefused() {
    PaymentsApiException status = assertThrows(PaymentsApiException.class,
        () -> ledger.list("paid", null, null));
    assertEquals(400, status.status());
    assertEquals("status", status.field());

    assertEquals("limit", assertThrows(PaymentsApiException.class,
        () -> ledger.list(null, null, "0")).field());
    assertEquals("limit", assertThrows(PaymentsApiException.class,
        () -> ledger.list(null, null, "lots")).field());
    assertEquals("limit", assertThrows(PaymentsApiException.class,
        () -> ledger.list(null, null, String.valueOf(PaymentLedger.MAX_LIMIT + 1))).field());
  }

  @Test
  void aFullPageNamesTheCursorOfTheNext() throws Exception {
    Connection c = database.connection;
    PaymentRow first = new PaymentRow("A", PaymentStatus.INVOICED, "F-1", null, ENVIRONMENT,
        null, new JSONObject().put("id", "A"));
    PaymentRow second = new PaymentRow("B", PaymentStatus.INVOICED, "F-2", null, ENVIRONMENT,
        null, new JSONObject().put("id", "B"));
    when(store.list(c, PaymentStatus.INVOICED, "Z", 2)).thenReturn(List.of(first, second));

    PaymentLedger.Page page = ledger.list("invoiced", "Z", "1");

    assertEquals(1, page.payments().size());
    assertEquals("A", page.nextCursor());
    assertEquals(1, database.commits);
  }

  @Test
  void theLastPageHasNoCursor() throws Exception {
    when(store.list(database.connection, null, null, PaymentLedger.DEFAULT_LIMIT + 1))
        .thenReturn(List.of());

    assertNull(ledger.list(null, null, null).nextCursor());
  }

  @Test
  void aPendingPaymentGetsTheCompanysCurrentDataAndItsEnvironment() throws Exception {
    Connection c = database.connection;
    PaymentRow pending = row(PaymentStatus.PENDING_INVOICE, null, null, null);
    JSONObject fiscal = new JSONObject().put("taxId", "B12345678");
    PaymentRow refreshed = row(PaymentStatus.PENDING_INVOICE, null, ENVIRONMENT,
        fiscal.toString());
    when(store.list(eq(c), eq(PaymentStatus.PENDING_INVOICE), isNull(), anyInt()))
        .thenReturn(List.of(pending));
    when(store.environmentClientFor(c, "sub_1", null)).thenReturn(ENVIRONMENT);
    when(store.fiscalData(c, ENVIRONMENT)).thenReturn(fiscal);
    when(store.find(c, ID)).thenReturn(refreshed);

    PaymentLedger.Page page = ledger.list("pending_invoice", null, null);

    verify(store).updateFiscalData(c, ID, ENVIRONMENT, fiscal);
    assertEquals(refreshed.json(), page.payments().get(0));
  }

  @Test
  void anUnchangedSnapshotIsNotRewritten() throws Exception {
    Connection c = database.connection;
    JSONObject fiscal = new JSONObject().put("taxId", "B12345678");
    PaymentRow pending = row(PaymentStatus.PENDING_INVOICE, null, ENVIRONMENT,
        fiscal.toString());
    when(store.list(eq(c), any(), any(), anyInt())).thenReturn(List.of(pending));
    when(store.fiscalData(c, ENVIRONMENT)).thenReturn(new JSONObject(fiscal.toString()));

    ledger.list(null, null, null);

    verify(store, never()).updateFiscalData(any(), any(), any(), any());
  }

  @Test
  void aCompanyThatDisappearedKeepsTheLastSnapshot() throws Exception {
    Connection c = database.connection;
    PaymentRow pending = row(PaymentStatus.PENDING_INVOICE, null, ENVIRONMENT,
        "{\"taxId\":\"B1\"}");
    when(store.list(eq(c), any(), any(), anyInt())).thenReturn(List.of(pending));
    when(store.fiscalData(c, ENVIRONMENT)).thenReturn(null);

    ledger.list(null, null, null);

    verify(store, never()).updateFiscalData(any(), any(), any(), any());
  }

  @Test
  void anInvoicedPaymentKeepsTheSnapshotItWasInvoicedWith() throws Exception {
    Connection c = database.connection;
    PaymentRow invoiced = row(PaymentStatus.INVOICED, "F-1", ENVIRONMENT, "{\"taxId\":\"B1\"}");
    when(store.find(c, ID)).thenReturn(invoiced);
    when(store.events(c, ID)).thenReturn(new JSONArray());

    JSONObject payment = ledger.get(ID);

    verify(store, never()).fiscalData(any(), any());
    assertEquals(0, payment.getJSONArray("events").length());
  }

  @Test
  void anUnknownPaymentIsNotFound() throws Exception {
    PaymentsApiException e = assertThrows(PaymentsApiException.class, () -> ledger.get(ID));

    assertEquals(404, e.status());
    assertEquals(PaymentLedger.CODE_PAYMENT_NOT_FOUND, e.code());
  }

  // --- the invoice number ----------------------------------------------------------------------

  @Test
  void theFirstInvoiceNumberMovesThePaymentToInvoiced() throws Exception {
    Connection c = database.connection;
    when(store.find(c, ID, true)).thenReturn(row(PaymentStatus.PENDING_INVOICE, null,
        ENVIRONMENT, null));
    when(store.find(c, ID)).thenReturn(row(PaymentStatus.INVOICED, "F-1", ENVIRONMENT, null));
    when(store.events(c, ID)).thenReturn(new JSONArray());

    ledger.setInvoiceNumber(ID, " F-1 ", "erp");

    verify(store).setInvoiceNumber(c, ID, "F-1");
    verify(store).setStatus(c, ID, PaymentStatus.INVOICED);
    verify(store).addEvent(c, ID, PaymentStatus.INVOICED, null, null, "erp");
    assertEquals(1, database.commits);
  }

  @Test
  void theSameNumberAgainChangesNothing() throws Exception {
    Connection c = database.connection;
    when(store.find(c, ID, true)).thenReturn(row(PaymentStatus.MAIL_SENT, "F-1", ENVIRONMENT,
        null));
    when(store.events(c, ID)).thenReturn(new JSONArray());

    ledger.setInvoiceNumber(ID, "F-1", "erp");

    verify(store, never()).setInvoiceNumber(any(), any(), any());
    verify(store, never()).setStatus(any(), any(), any());
  }

  @Test
  void anotherNumberIsAConflictAndRollsBack() throws Exception {
    when(store.find(database.connection, ID, true)).thenReturn(row(PaymentStatus.INVOICED,
        "F-1", ENVIRONMENT, null));

    PaymentsApiException e = assertThrows(PaymentsApiException.class,
        () -> ledger.setInvoiceNumber(ID, "F-2", "erp"));

    assertEquals(409, e.status());
    assertEquals(PaymentLedger.CODE_INVOICE_NUMBER_CONFLICT, e.code());
    assertEquals(1, database.rollbacks);
    assertEquals(0, database.commits);
  }

  @Test
  void aMissingOrOverlongNumberIsRefusedBeforeAnyQuery() {
    assertEquals(400, assertThrows(PaymentsApiException.class,
        () -> ledger.setInvoiceNumber(ID, " ", "erp")).status());
    assertEquals(400, assertThrows(PaymentsApiException.class,
        () -> ledger.setInvoiceNumber(ID, "x".repeat(256), "erp")).status());
    verifyNoInteractions(store);
  }

  // --- reported events -------------------------------------------------------------------------

  @Test
  void anAcceptedEventMovesThePaymentAndIsRecorded() throws Exception {
    Connection c = database.connection;
    when(store.find(c, ID, true)).thenReturn(row(PaymentStatus.INVOICED, "F-1", ENVIRONMENT,
        null));
    when(store.find(c, ID)).thenReturn(row(PaymentStatus.MAIL_SENT, "F-1", ENVIRONMENT, null));
    when(store.events(c, ID)).thenReturn(new JSONArray());

    ledger.report(ID, new JSONObject().put("event", "mail_sent"), "erp");

    verify(store).setStatus(c, ID, PaymentStatus.MAIL_SENT);
    verify(store).addEvent(c, ID, PaymentStatus.MAIL_SENT, null, null, "erp");
  }

  @Test
  void anErrorKeepsItsStepAndReason() throws Exception {
    Connection c = database.connection;
    when(store.find(eq(c), eq(ID), anyBoolean())).thenReturn(row(PaymentStatus.PENDING_INVOICE,
        null, ENVIRONMENT, null));
    when(store.find(c, ID)).thenReturn(row(PaymentStatus.ERROR, null, ENVIRONMENT, null));
    when(store.events(c, ID)).thenReturn(new JSONArray());

    ledger.report(ID, new JSONObject().put("event", "error").put("step", "invoice")
        .put("reason", "Missing tax id"), "erp");

    verify(store).addEvent(c, ID, PaymentStatus.ERROR, "invoice", "Missing tax id", "erp");
  }

  @Test
  void anErrorWithoutStepOrReasonIsRefused() throws Exception {
    assertEquals(PaymentLedger.FIELD_STEP, assertThrows(PaymentsApiException.class,
        () -> ledger.report(ID, new JSONObject().put("event", "error").put("reason", "x"),
            "erp")).field());
    assertEquals(PaymentLedger.FIELD_REASON, assertThrows(PaymentsApiException.class,
        () -> ledger.report(ID, new JSONObject().put("event", "error").put("step", "mail"),
            "erp")).field());
  }

  @Test
  void anUnknownEventIsRefused() {
    assertEquals(PaymentLedger.FIELD_EVENT, assertThrows(PaymentsApiException.class,
        () -> ledger.report(ID, new JSONObject(), "erp")).field());
    assertEquals(PaymentLedger.FIELD_EVENT, assertThrows(PaymentsApiException.class,
        () -> ledger.report(ID, new JSONObject().put("event", "pending_invoice"), "erp"))
        .field());
  }

  @Test
  void anEventTheStateDoesNotAllowIsAConflict() throws Exception {
    when(store.find(database.connection, ID, true)).thenReturn(row(
        PaymentStatus.PENDING_INVOICE, null, ENVIRONMENT, null));

    PaymentsApiException e = assertThrows(PaymentsApiException.class,
        () -> ledger.report(ID, new JSONObject().put("event", "completed"), "erp"));

    assertEquals(409, e.status());
    assertEquals(PaymentLedger.CODE_INVOICE_NUMBER_MISSING, e.code());
    verify(store, never()).setStatus(any(), any(), any());
    assertEquals(1, database.rollbacks);
  }

  @Test
  void eachRefusalHasItsCode() throws Exception {
    when(store.find(database.connection, ID, true))
        .thenReturn(row(PaymentStatus.COMPLETED, "F-1", ENVIRONMENT, null))
        .thenReturn(row(PaymentStatus.MAIL_SENT, "F-1", ENVIRONMENT, null));

    assertEquals(PaymentLedger.CODE_PAYMENT_COMPLETED, assertThrows(PaymentsApiException.class,
        () -> ledger.report(ID, new JSONObject().put("event", "invoiced"), "erp")).code());
    assertEquals(PaymentLedger.CODE_INVALID_TRANSITION, assertThrows(PaymentsApiException.class,
        () -> ledger.report(ID, new JSONObject().put("event", "invoiced"), "erp")).code());
  }
}
