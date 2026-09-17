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
import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.hibernate.ScrollableResults;
import org.hibernate.Session;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.enterprise.DocumentType;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.common.invoice.Invoice;
import org.openbravo.model.common.invoice.InvoiceTax;
import org.openbravo.model.financialmgmt.calendar.Period;
import org.openbravo.model.financialmgmt.tax.TaxRate;
import org.openbravo.module.aeat303.es.api.CashVATOperationType;
import org.openbravo.module.aeat303.es.report.v2014.AEAT303Report2014Dao;

/**
 * Unit tests for {@link Fiscal303SourcesSupport}, in particular the ETP-5338 addition of the
 * {@code accountingDate} field on the per-invoice {@code sources} row built by the private
 * {@code buildNewInvoiceRow} method.
 *
 * <p>{@code buildNewInvoiceRow} is {@code private}, so it is exercised indirectly through
 * {@link Fiscal303SourcesSupport#collectSources}, its only caller — the package-private (not
 * private) entry point already reached directly by {@code owner}, mirroring the existing
 * {@code handler.sourcesSupport.finalizeInvoiceRow(row)} pattern in
 * {@link Fiscal303BoxesHandlerTest}. No visibility change was needed.</p>
 */
public class Fiscal303SourcesSupportTest {

  private Fiscal303BoxesHandler handler;

  @Before
  public void setUp() {
    handler = new Fiscal303BoxesHandler(null);
  }

  /**
   * When the invoice has a non-null {@code AccountingDate}, the resulting row's
   * {@code accountingDate} field must be the {@code yyyy-MM-dd} formatted string.
   */
  @Test
  public void testCollectSources_populatedAccountingDate_isFormattedString() {
    Invoice inv = buildInvoice("inv-1", "F-2026-0001", date(2026, 1, 15), date(2026, 1, 20));
    InvoiceTax it = buildInvoiceTax(inv, "100.00", "21.00");

    List<Map<String, Object>> rows = runCollectSources(it, "rate-1", 7, 9);

    assertEquals(1, rows.size());
    assertEquals("2026-01-20", rows.get(0).get("accountingDate"));
    // Sanity: the pre-existing (never-null-guarded) invoice date is unaffected.
    assertEquals("2026-01-15", rows.get(0).get("date"));
  }

  /**
   * When the invoice's {@code AccountingDate} is null, the row's {@code accountingDate} field
   * must be {@code null} — not throw a {@link NullPointerException}, and not fall back to the
   * invoice date.
   */
  @Test
  public void testCollectSources_nullAccountingDate_rowFieldIsNullNoNpe() {
    Invoice inv = buildInvoice("inv-2", "F-2026-0002", date(2026, 2, 10), null);
    InvoiceTax it = buildInvoiceTax(inv, "50.00", "10.50");

    List<Map<String, Object>> rows = runCollectSources(it, "rate-1", 7, 9);

    assertEquals(1, rows.size());
    assertNull(rows.get(0).get("accountingDate"));
    assertEquals("2026-02-10", rows.get(0).get("date"));
  }

  // ── helpers ───────────────────────────────────────────────────────────────

  @SuppressWarnings("unchecked")
  private List<Map<String, Object>> runCollectSources(InvoiceTax it, String rateId, int baseBox, int taxBox) {
    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      OBDal obDal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(obDal);
      Session session = mock(Session.class);
      when(obDal.getSession()).thenReturn(session);

      TaxRate rate = mock(TaxRate.class);
      when(rate.getId()).thenReturn(rateId);
      when(obDal.get(eq(TaxRate.class), eq(rateId))).thenReturn(rate);
      when(it.getTax()).thenReturn(rate);

      Map<String, List<Integer>> rateToBoxes = new LinkedHashMap<>();
      rateToBoxes.put(rateId, java.util.Arrays.asList(baseBox, taxBox));

      Organization org = mock(Organization.class);
      List<Period> periods = Collections.emptyList();
      AEAT303Report2014Dao dao303 = mock(AEAT303Report2014Dao.class);

      ScrollableResults sr = mock(ScrollableResults.class);
      when(sr.next()).thenReturn(true, false);
      when(sr.get(0)).thenReturn(it);
      when(dao303.getInvoiceTax(eq(org), anyList(), eq(periods), eq(CashVATOperationType.ONLY_NONCASHVAT)))
          .thenReturn(sr);

      return handler.sourcesSupport.collectSources(org, periods, dao303, rateToBoxes);
    }
  }

  private static Invoice buildInvoice(String id, String docNo, Date invoiceDate, Date accountingDate) {
    Invoice inv = mock(Invoice.class);
    when(inv.getId()).thenReturn(id);
    when(inv.getDocumentNo()).thenReturn(docNo);
    when(inv.getInvoiceDate()).thenReturn(invoiceDate);
    when(inv.getAccountingDate()).thenReturn(accountingDate);
    DocumentType docType = mock(DocumentType.class);
    when(docType.getDocumentCategory()).thenReturn("ARI");
    when(inv.getDocumentType()).thenReturn(docType);
    when(inv.getBusinessPartner()).thenReturn(null);
    return inv;
  }

  private static InvoiceTax buildInvoiceTax(Invoice inv, String base, String tax) {
    InvoiceTax it = mock(InvoiceTax.class);
    when(it.getInvoice()).thenReturn(inv);
    when(it.getTaxableAmount()).thenReturn(new BigDecimal(base));
    when(it.getTaxAmount()).thenReturn(new BigDecimal(tax));
    return it;
  }

  private static Date date(int year, int month, int day) {
    Calendar cal = Calendar.getInstance();
    cal.clear();
    cal.set(year, month - 1, day);
    return cal.getTime();
  }
}
