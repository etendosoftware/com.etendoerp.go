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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.hibernate.Session;
import org.hibernate.query.Query;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;

/**
 * Covers {@code invoiceIdsByPayment}, which answers "which invoice can this payment be edited
 * against" so the payment window can open the invoice's editable modal for a draft instead of the
 * yes/no dialog it used to be stuck with (ETP-4895).
 *
 * <p>The other two members of {@link PaymentInvoiceApplications} are exercised through
 * {@code handleListPayments} in {@code PaymentRegistrationServiceTest} and
 * {@code PaymentRegistrationServiceAdvancedTest}, which is where their callers live.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PaymentInvoiceApplicationsTest {

  @Mock
  private OBDal obDal;

  @Mock
  private Session session;

  @Mock
  private OBContext obContext;

  private MockedStatic<OBDal> obDalMock;
  private MockedStatic<OBContext> obContextMock;

  @BeforeEach
  void setUp() {
    obDalMock = mockStatic(OBDal.class);
    obContextMock = mockStatic(OBContext.class);
    obDalMock.when(OBDal::getInstance).thenReturn(obDal);
    obContextMock.when(OBContext::getOBContext).thenReturn(obContext);
    when(obDal.getSession()).thenReturn(session);
  }

  @AfterEach
  void tearDown() {
    obDalMock.close();
    obContextMock.close();
  }

  @SuppressWarnings("unchecked")
  private void stubInvoiceQuery(List<Object[]> rows) {
    Query<Object[]> query = mock(Query.class);
    when(session.createQuery(anyString(), eq(Object[].class))).thenReturn(query);
    when(query.setParameterList(anyString(), any(java.util.Collection.class))).thenReturn(query);
    when(query.list()).thenReturn(rows);
  }

  @Test
  void invoiceIdsByPaymentSkipsTheQueryForAnEmptyBatch() {
    assertTrue(PaymentInvoiceApplications.invoiceIdsByPayment(null).isEmpty());
    assertTrue(PaymentInvoiceApplications.invoiceIdsByPayment(List.of()).isEmpty());
    verify(session, never()).createQuery(anyString(), eq(Object[].class));
  }

  @Test
  void invoiceIdsByPaymentResolvesASinglePayment() {
    // Explicit type argument: with a single element, List.of would read the row as the varargs
    // array itself and infer List<Object> instead of a one-row List<Object[]>.
    stubInvoiceQuery(List.<Object[]>of(new Object[]{ "pay-1", "inv-1" }));

    assertEquals("inv-1", PaymentInvoiceApplications.invoiceIdsByPayment(List.of("pay-1")).get("pay-1"));
  }

  @Test
  void invoiceIdsByPaymentDropsPaymentsSpanningSeveralInvoices() {
    // The editor is single-invoice: opening it on one of two would misapply the other on save, so
    // those fall back to the confirm dialog instead.
    stubInvoiceQuery(List.of(new Object[]{ "pay-1", "inv-1" }, new Object[]{ "pay-1", "inv-2" }));

    assertTrue(PaymentInvoiceApplications.invoiceIdsByPayment(List.of("pay-1")).isEmpty());
  }

  @Test
  void invoiceIdsByPaymentDropsPaymentsWithNoInvoiceAtAll() {
    // Abandoned shells with no application. Nothing to edit against.
    stubInvoiceQuery(List.of());

    assertTrue(PaymentInvoiceApplications.invoiceIdsByPayment(List.of("pay-1")).isEmpty());
  }

  @Test
  void invoiceIdsByPaymentResolvesEachPaymentOfTheBatchIndependently() {
    stubInvoiceQuery(List.of(
        new Object[]{ "pay-1", "inv-1" },
        new Object[]{ "pay-2", "inv-2" },
        new Object[]{ "pay-3", "inv-3" },
        new Object[]{ "pay-3", "inv-4" }));

    Map<String, String> resolved =
        PaymentInvoiceApplications.invoiceIdsByPayment(List.of("pay-1", "pay-2", "pay-3"));

    assertEquals(2, resolved.size());
    assertEquals("inv-1", resolved.get("pay-1"));
    assertEquals("inv-2", resolved.get("pay-2"));
  }

  @Test
  void invoiceIdsByPaymentSurvivesAFailedLookup() {
    // Losing the field costs the editable modal, never the response: the caller falls back.
    when(session.createQuery(anyString(), eq(Object[].class)))
        .thenThrow(new IllegalStateException("no session"));

    assertTrue(PaymentInvoiceApplications.invoiceIdsByPayment(List.of("pay-1")).isEmpty());
  }
}
