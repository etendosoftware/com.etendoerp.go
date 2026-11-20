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
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;

import org.hibernate.Session;
import org.hibernate.query.Query;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.financialmgmt.payment.FIN_FinaccTransaction;
import org.openbravo.model.financialmgmt.payment.FIN_PaymentMethod;
import org.openbravo.model.financialmgmt.payment.FIN_Payment;
import org.openbravo.model.financialmgmt.payment.FIN_Reconciliation;
import org.openbravo.module.remittance.Remittance;
import org.openbravo.module.remittance.RemittanceLine;
import org.openbravo.module.remittance.RemittanceLineCancel;
import org.openbravo.module.remittance.RemittanceLineReturn;
import org.openbravo.module.remittance.RemittanceType;

import com.etendoerp.go.schemaforge.RemittanceReturnState.LineState;

/**
 * Unit tests for {@link RemittanceReturnState} (ETP-6247): the state of each remittance line as far
 * as returns go, and why it can or cannot be returned.
 *
 * <p>The DAL is a mocked Hibernate session answering the four queries of the class by their HQL
 * (lines, settlement rows, return rows, return count); the entities are mocks.
 *
 * <p>A class of its own: {@code RemittanceReturnState} is a new production class and no existing
 * test class is about returns ({@code RemittanceFileReadinessTest} specifies the SEPA file
 * readiness).
 *
 * @covers com.etendoerp.go.schemaforge.RemittanceReturnState
 */
class RemittanceReturnStateTest {

  private static final String REM_ID = "REM-1";

  private MockedStatic<OBDal> dalStatic;
  private Remittance remittance;
  private final List<RemittanceLine> lines = new ArrayList<>();
  private final List<RemittanceLineCancel> cancels = new ArrayList<>();
  private final List<RemittanceLineReturn> returns = new ArrayList<>();
  private Long returnCount;

  @SuppressWarnings({ "unchecked", "rawtypes" })
  @BeforeEach
  void setUp() {
    OBDal dal = mock(OBDal.class);
    Session session = mock(Session.class);
    when(dal.getSession()).thenReturn(session);
    when(session.createQuery(anyString(), any(Class.class))).thenAnswer(inv -> {
      String hql = inv.getArgument(0);
      Query query = mock(Query.class, RETURNS_SELF);
      if (hql.startsWith("select rl from")) {
        when(query.list()).thenReturn(lines);
      } else if (hql.startsWith("select c from")) {
        when(query.list()).thenReturn(cancels);
      } else if (hql.startsWith("select count")) {
        when(query.uniqueResult()).thenReturn(returnCount);
      } else if (hql.startsWith("select r from")) {
        when(query.list()).thenReturn(returns);
      } else {
        throw new AssertionError("unexpected query: " + hql);
      }
      return query;
    });
    dalStatic = mockStatic(OBDal.class);
    dalStatic.when(OBDal::getInstance).thenReturn(dal);
    remittance = remittance(true, true);
  }

  @AfterEach
  void tearDown() {
    dalStatic.close();
    Mockito.framework().clearInlineMocks();
  }

  // ---------------------------------------------------------------- the four states

  @Test
  void aLineOfAnUnprocessedRemittanceIsPending() {
    remittance = remittance(false, true);
    FIN_Payment payment = payment("P1", "RPAE");
    lines.add(line("L1", payment));

    LineState state = only();

    assertEquals(RemittanceReturnState.PENDING, state.state);
    assertEquals(RemittanceReturnState.NOT_PROCESSED, state.reasonCode);
    assertFalse(state.returnable());
  }

  @Test
  void aProcessedLineWithoutASettlementRowIsProcessedAndNotSettled() {
    lines.add(line("L1", payment("P1", "RPAE")));

    LineState state = only();

    assertEquals(RemittanceReturnState.PROCESSED, state.state);
    assertEquals(RemittanceReturnState.NOT_SETTLED, state.reasonCode);
    assertFalse(state.returnable());
  }

  @Test
  void aSettledLineOfACollectionThatNothingBlocksIsReturnable() {
    FIN_Payment payment = payment("P1", "RPR");
    lines.add(line("L1", payment));
    cancels.add(cancel(payment, null));

    LineState state = only();

    assertEquals(RemittanceReturnState.SETTLED, state.state);
    assertNull(state.reasonCode);
    assertTrue(state.returnable());
    assertEquals("P1", state.paymentId);
  }

  @Test
  void aLineWhosePaymentIsVoidIsReturnedEvenIfARowOfThePaymentRemains() {
    FIN_Payment payment = payment("P1", RemittanceReturnState.PAYMENT_VOID);
    lines.add(line("L1", payment));
    cancels.add(cancel(payment, null));

    LineState state = only();

    assertEquals(RemittanceReturnState.RETURNED, state.state);
    assertEquals(RemittanceReturnState.ALREADY_RETURNED, state.reasonCode);
    assertFalse(state.returnable());
    assertNull(state.returnDate, "no return row exists to date it");
  }

  // ---------------------------------------------------------------- reasons

  @Test
  void aSettledLineOfAPaymentRemittanceIsNotACollection() {
    remittance = remittance(true, false);
    FIN_Payment payment = payment("P1", "RPR");
    lines.add(line("L1", payment));
    cancels.add(cancel(payment, null));

    LineState state = only();

    assertEquals(RemittanceReturnState.SETTLED, state.state);
    assertEquals(RemittanceReturnState.NOT_A_COLLECTION, state.reasonCode);
    assertFalse(state.returnable());
  }

  @Test
  void aPostedSettlementRowBlocksTheReturn() {
    FIN_Payment payment = payment("P1", "RPR");
    lines.add(line("L1", payment));
    cancels.add(cancel(payment, "Y"));

    assertEquals(RemittanceReturnState.POSTED, only().reasonCode);
  }

  @Test
  void aPostedPaymentBlocksTheReturn() {
    FIN_Payment payment = payment("P1", "RPR");
    when(payment.getPosted()).thenReturn("Y");
    lines.add(line("L1", payment));
    cancels.add(cancel(payment, "N"));

    assertEquals(RemittanceReturnState.POSTED, only().reasonCode);
  }

  @Test
  void aPostedBankTransactionBlocksTheReturn() {
    FIN_Payment payment = payment("P1", "RPR");
    FIN_FinaccTransaction transaction = mock(FIN_FinaccTransaction.class);
    when(transaction.getPosted()).thenReturn("Y");
    when(payment.getFINFinaccTransactionList()).thenReturn(List.of(transaction));
    lines.add(line("L1", payment));
    cancels.add(cancel(payment, "N"));

    assertEquals(RemittanceReturnState.POSTED, only().reasonCode);
  }

  @Test
  void aReconciledBankTransactionBlocksTheReturn() {
    FIN_Payment payment = payment("P1", "RPR");
    FIN_FinaccTransaction transaction = mock(FIN_FinaccTransaction.class);
    when(transaction.getReconciliation()).thenReturn(mock(FIN_Reconciliation.class));
    when(payment.getFINFinaccTransactionList()).thenReturn(List.of(transaction));
    lines.add(line("L1", payment));
    cancels.add(cancel(payment, "N"));

    LineState state = only();

    assertEquals(RemittanceReturnState.RECONCILED, state.reasonCode);
    assertFalse(state.returnable());
  }

  @Test
  void aTransactionBothReconciledAndPostedIsReportedAsReconciled() {
    FIN_Payment payment = payment("P1", "RPR");
    FIN_FinaccTransaction transaction = mock(FIN_FinaccTransaction.class);
    when(transaction.getReconciliation()).thenReturn(mock(FIN_Reconciliation.class));
    when(transaction.getPosted()).thenReturn("Y");
    when(payment.getFINFinaccTransactionList()).thenReturn(List.of(transaction));
    lines.add(line("L1", payment));
    cancels.add(cancel(payment, "N"));

    assertEquals(RemittanceReturnState.RECONCILED, only().reasonCode);
  }

  @Test
  void anUnpostedUnreconciledTransactionDoesNotBlock() {
    FIN_Payment payment = payment("P1", "RPR");
    FIN_FinaccTransaction transaction = mock(FIN_FinaccTransaction.class);
    when(transaction.getPosted()).thenReturn("N");
    when(payment.getFINFinaccTransactionList()).thenReturn(List.of(transaction));
    lines.add(line("L1", payment));
    cancels.add(cancel(payment, "N"));

    assertTrue(only().returnable());
  }

  // ---------------------------------------------------------------- shared payments

  @Test
  void linesOfOneGroupedPaymentListEachOther() {
    FIN_Payment shared = payment("P1", "RPR");
    FIN_Payment own = payment("P2", "RPR");
    lines.add(line("L1", shared));
    lines.add(line("L2", shared));
    lines.add(line("L3", own));
    cancels.add(cancel(shared, null));
    cancels.add(cancel(own, null));

    Map<String, LineState> states = RemittanceReturnState.of(remittance);

    assertEquals(List.of("L1", "L2"), states.get("L1").paymentLineIds);
    assertEquals(List.of("L1", "L2"), states.get("L2").paymentLineIds);
    assertEquals(List.of("L3"), states.get("L3").paymentLineIds);
    assertEquals(List.of("L1", "L2", "L3"), new ArrayList<>(states.keySet()),
        "line order is kept");
  }

  @Test
  void aLineWithoutAPaymentHasNoPaymentAndNeverSettles() {
    lines.add(line("L1", null));

    LineState state = only();

    assertNull(state.paymentId);
    assertEquals(List.of("L1"), state.paymentLineIds);
    assertEquals(RemittanceReturnState.PROCESSED, state.state);
    assertEquals(RemittanceReturnState.NOT_SETTLED, state.reasonCode);
  }

  @Test
  void twoLinesWithoutAPaymentAreNotGroupedTogether() {
    lines.add(line("L1", null));
    lines.add(line("L2", null));

    Map<String, LineState> states = RemittanceReturnState.of(remittance);

    assertEquals(List.of("L1"), states.get("L1").paymentLineIds);
    assertEquals(List.of("L2"), states.get("L2").paymentLineIds);
  }

  // ---------------------------------------------------------------- settlement rows

  @Test
  void settlementRowsAreKeyedByPaymentTheFirstWinsAndOnesWithoutPaymentAreDropped() {
    FIN_Payment payment = payment("P1", "RPR");
    RemittanceLineCancel first = cancel(payment, null);
    RemittanceLineCancel second = cancel(payment, null);
    RemittanceLineCancel orphan = cancel(null, null);
    cancels.add(first);
    cancels.add(second);
    cancels.add(orphan);

    Map<String, RemittanceLineCancel> rows = RemittanceReturnState.settlementRows(remittance);

    assertEquals(1, rows.size());
    assertEquals(first, rows.get("P1"));
  }

  // ---------------------------------------------------------------- hasReturns

  @Test
  void aRemittanceHasReturnsWhenItHasAProcessedReturnRow() {
    returnCount = 1L;
    assertTrue(RemittanceReturnState.hasReturns(remittance));
  }

  @Test
  void aRemittanceWithNoReturnRowsHasNoReturns() {
    returnCount = 0L;
    assertFalse(RemittanceReturnState.hasReturns(remittance));
  }

  @Test
  void aNullCountIsNoReturns() {
    returnCount = null;
    assertFalse(RemittanceReturnState.hasReturns(remittance));
  }

  // ---------------------------------------------------------------- returnDate

  @Test
  void theReturnDateIsTheAccountingDateOfTheReturnRowNearestToTheVoid() {
    FIN_Payment payment = payment("P1", RemittanceReturnState.PAYMENT_VOID);
    when(payment.getUpdated()).thenReturn(at(2026, 11, 10, 12));
    lines.add(line("L1", payment));
    returns.add(returnRow(day(2026, 10, 1), at(2026, 10, 1, 12)));
    returns.add(returnRow(day(2026, 11, 9), at(2026, 11, 10, 12)));
    returns.add(returnRow(day(2026, 11, 20), at(2026, 11, 20, 12)));

    LineState state = only();

    assertEquals("2026-11-09", state.returnDate);
  }

  @Test
  void returnRowsWithoutADateAreNotCandidates() {
    FIN_Payment payment = payment("P1", RemittanceReturnState.PAYMENT_VOID);
    when(payment.getUpdated()).thenReturn(at(2026, 11, 10, 12));
    lines.add(line("L1", payment));
    returns.add(returnRow(null, at(2026, 11, 10, 12)));
    returns.add(returnRow(day(2026, 11, 3), at(2026, 11, 5, 12)));

    assertEquals("2026-11-03", only().returnDate);
  }

  @Test
  void aVoidPaymentWithoutAnUpdateDateHasNoReturnDate() {
    FIN_Payment payment = payment("P1", RemittanceReturnState.PAYMENT_VOID);
    when(payment.getUpdated()).thenReturn(null);
    lines.add(line("L1", payment));
    returns.add(returnRow(day(2026, 11, 3), at(2026, 11, 5, 12)));

    assertNull(only().returnDate);
  }

  // ---------------------------------------------------------------- isCollection

  @Test
  void aRemittanceIsACollectionOnlyWhenItsMethodAllowsPayIn() {
    assertTrue(RemittanceReturnState.isCollection(remittance(true, true)));
    assertFalse(RemittanceReturnState.isCollection(remittance(true, false)));

    Remittance noType = mock(Remittance.class);
    assertFalse(RemittanceReturnState.isCollection(noType));

    Remittance noMethod = mock(Remittance.class);
    when(noMethod.getRemittanceType()).thenReturn(mock(RemittanceType.class));
    assertFalse(RemittanceReturnState.isCollection(noMethod));
  }

  @Test
  void aRemittanceWithoutLinesHasNoStates() {
    assertEquals(Collections.emptyMap(), RemittanceReturnState.of(remittance));
  }

  // ---------------------------------------------------------------- fixtures

  private LineState only() {
    Map<String, LineState> states = RemittanceReturnState.of(remittance);
    assertEquals(1, states.size());
    return states.values().iterator().next();
  }

  private static Remittance remittance(boolean processed, boolean collection) {
    Remittance remittance = mock(Remittance.class);
    when(remittance.getId()).thenReturn(REM_ID);
    when(remittance.isProcessed()).thenReturn(processed);
    RemittanceType type = mock(RemittanceType.class);
    FIN_PaymentMethod method = mock(FIN_PaymentMethod.class);
    when(method.isPayinAllow()).thenReturn(collection);
    when(type.getPaymentMethod()).thenReturn(method);
    when(remittance.getRemittanceType()).thenReturn(type);
    return remittance;
  }

  private static RemittanceLine line(String id, FIN_Payment payment) {
    RemittanceLine line = mock(RemittanceLine.class);
    when(line.getId()).thenReturn(id);
    when(line.getPayment()).thenReturn(payment);
    return line;
  }

  private static FIN_Payment payment(String id, String status) {
    FIN_Payment payment = mock(FIN_Payment.class);
    when(payment.getId()).thenReturn(id);
    when(payment.getStatus()).thenReturn(status);
    when(payment.getFINFinaccTransactionList()).thenReturn(new ArrayList<>());
    return payment;
  }

  private static RemittanceLineCancel cancel(FIN_Payment payment, String posted) {
    RemittanceLineCancel cancel = mock(RemittanceLineCancel.class);
    when(cancel.getPayment()).thenReturn(payment);
    when(cancel.getPosted()).thenReturn(posted);
    return cancel;
  }

  private static RemittanceLineReturn returnRow(Date accountingDate, Date created) {
    RemittanceLineReturn row = mock(RemittanceLineReturn.class);
    when(row.getAccountingDate()).thenReturn(accountingDate);
    when(row.getCreationDate()).thenReturn(created);
    return row;
  }

  private static Date day(int year, int month, int day) {
    return Date.from(LocalDate.of(year, month, day).atStartOfDay(ZoneId.systemDefault())
        .toInstant());
  }

  private static Date at(int year, int month, int day, int hour) {
    return Date.from(LocalDate.of(year, month, day).atTime(hour, 0).atZone(ZoneId.systemDefault())
        .toInstant());
  }
}
