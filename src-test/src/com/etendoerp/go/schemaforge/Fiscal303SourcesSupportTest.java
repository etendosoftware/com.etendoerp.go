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

  /**
   * ETP-5456: an intra-EU acquisition invoice posts TWO {@code C_INVOICETAX} lines for the SAME
   * base/tax — devengado (box 10/11, {@code VAT_SALES_EU}) and soportado deductible (box 36/37,
   * {@code Intracommunity_Goods}). Before the fix, {@code accumulateInvoiceTax} summed both lines
   * unconditionally into the same "Facturas" tab row, doubling base/vat/total. After the fix, the
   * row must show the real (non-duplicated) amount: base 20.00, vat 4.20 (21%), total 24.20 —
   * matching the numerically-verified regression this ticket reports.
   */
  @Test
  public void testCollectSources_intraEuAcquisitionPairedLines_notDoubled() {
    Invoice inv = buildInvoice("inv-eu-1", "REC-1000003", date(2026, 3, 5), date(2026, 3, 5));
    InvoiceTax devengado = buildInvoiceTax(inv, "20.00", "4.20");
    InvoiceTax soportado = buildInvoiceTax(inv, "20.00", "4.20");

    List<Map<String, Object>> rows = runCollectSources(
        java.util.Arrays.asList(devengado, soportado),
        java.util.Arrays.asList("rate-devengado", "rate-soportado"),
        java.util.Arrays.asList(java.util.Arrays.asList(10, 11), java.util.Arrays.asList(36, 37)));

    assertEquals(1, rows.size());
    Map<String, Object> row = rows.get(0);
    assertEquals(new BigDecimal("20.00"), row.get("base"));
    assertEquals(new BigDecimal("4.20"), row.get("vat"));
    assertEquals(new BigDecimal("24.20"), row.get("total"));
  }

  /**
   * ETP-5456 regression guard: a normal domestic invoice with a single tax line whose boxes are
   * NOT in the reverse-charge pair set (e.g. 7/9, regimen general 21%) must keep behaving exactly
   * as before — no halving, full base/tax reflected in the row.
   */
  @Test
  public void testCollectSources_domesticInvoiceSingleLine_unaffected() {
    Invoice inv = buildInvoice("inv-dom-1", "F-2026-0099", date(2026, 3, 6), date(2026, 3, 6));
    InvoiceTax it = buildInvoiceTax(inv, "100.00", "21.00");

    List<Map<String, Object>> rows = runCollectSources(
        Collections.singletonList(it), Collections.singletonList("rate-general"),
        Collections.singletonList(java.util.Arrays.asList(7, 9)));

    assertEquals(1, rows.size());
    Map<String, Object> row = rows.get(0);
    assertEquals(new BigDecimal("100.00"), row.get("base"));
    assertEquals(new BigDecimal("21.00"), row.get("vat"));
    assertEquals(new BigDecimal("121.00"), row.get("total"));
  }

  /**
   * ETP-5456 code review (Alex, B1) — regression guard for the intermediate-rounding bug: rounding
   * to scale-2 HALF_UP after EACH accumulated line (instead of once at the end) can push a halved
   * paired line across a ".xx5" boundary and land on the wrong cent. Two paired lines of base
   * 55.37 / tax 11.63 each (halved to an EXACT 27.685 / 5.815, no further rounding needed) used to
   * round the first line's 27.685 up to 27.69, then round 27.69 + 27.685 = 55.375 up to 55.38 —
   * one cent over the real 55.37. Accumulating unrounded and rounding once in
   * {@code finalizeInvoiceRow} must yield exactly 55.37 / 11.63 / 67.00.
   */
  @Test
  public void testCollectSources_pairedLinesOddCents_noIntermediateRoundingDrift() {
    Invoice inv = buildInvoice("inv-eu-oddcents", "REC-2000001", date(2026, 3, 7), date(2026, 3, 7));
    InvoiceTax devengado = buildInvoiceTax(inv, "55.37", "11.63");
    InvoiceTax soportado = buildInvoiceTax(inv, "55.37", "11.63");

    List<Map<String, Object>> rows = runCollectSources(
        java.util.Arrays.asList(devengado, soportado),
        java.util.Arrays.asList("rate-devengado-odd", "rate-soportado-odd"),
        java.util.Arrays.asList(java.util.Arrays.asList(10, 11), java.util.Arrays.asList(36, 37)));

    assertEquals(1, rows.size());
    Map<String, Object> row = rows.get(0);
    assertEquals(new BigDecimal("55.37"), row.get("base"));
    assertEquals(new BigDecimal("11.63"), row.get("vat"));
    assertEquals(new BigDecimal("67.00"), row.get("total"));
  }

  /**
   * ETP-5456 code review (Alex, W2a) — mixed invoice: a full intra-EU reverse-charge pair
   * (devengado 10/11 + soportado 36/37, base/tax 30.00/6.30 each, halving to 15.00/3.15 each so
   * the pair nets back to 30.00/6.30) coexists on the SAME invoice with an unrelated normal
   * domestic line (boxes 7/9, base 50.00, tax 10.50, no halving). The unrelated line must NOT be
   * halved and the paired lines must NOT be doubled: expected row is base 80.00 (30.00 + 50.00),
   * vat 16.80 (6.30 + 10.50), total 96.80.
   */
  @Test
  public void testCollectSources_mixedInvoiceReverseChargePlusNormalLine_eachHandledCorrectly() {
    Invoice inv = buildInvoice("inv-mixed-1", "REC-3000001", date(2026, 3, 8), date(2026, 3, 8));
    InvoiceTax devengado = buildInvoiceTax(inv, "30.00", "6.30");
    InvoiceTax soportado = buildInvoiceTax(inv, "30.00", "6.30");
    InvoiceTax normal    = buildInvoiceTax(inv, "50.00", "10.50");

    List<Map<String, Object>> rows = runCollectSources(
        java.util.Arrays.asList(devengado, soportado, normal),
        java.util.Arrays.asList("rate-devengado-mix", "rate-soportado-mix", "rate-normal-mix"),
        java.util.Arrays.asList(java.util.Arrays.asList(10, 11), java.util.Arrays.asList(36, 37),
            java.util.Arrays.asList(7, 9)));

    assertEquals(1, rows.size());
    Map<String, Object> row = rows.get(0);
    assertEquals(new BigDecimal("80.00"), row.get("base"));
    assertEquals(new BigDecimal("16.80"), row.get("vat"));
    assertEquals(new BigDecimal("96.80"), row.get("total"));
  }

  /**
   * ETP-5456 code review (Alex, W2b) — 3-way split: a single devengado line (boxes 10/11, base
   * 60.00 / tax 12.60) covers the FULL intra-EU acquisition, while the matching deduction is split
   * across TWO soportado lines on the same invoice — goods (boxes 36/37, base 40.00 / tax 8.40)
   * and investment goods (boxes 38/39, base 20.00 / tax 4.20) — whose bases/taxes sum back to the
   * devengado line's (40.00 + 20.00 = 60.00, 8.40 + 4.20 = 12.60). Alex verified analytically that
   * halving every matching line and summing generalizes to any number of paired lines; this pins
   * that down as an executable test. Expected: each line contributes exactly half
   * (30.00+20.00+10.00=60.00 base, 6.30+4.20+2.10=12.60 vat), total 72.60 — the real single amount,
   * not the 2x/3x a naive sum would produce.
   */
  @Test
  public void testCollectSources_threeWaySplitAcrossGoodsAndInvestmentBoxes_netsToRealAmount() {
    Invoice inv = buildInvoice("inv-3way-1", "REC-4000001", date(2026, 3, 9), date(2026, 3, 9));
    InvoiceTax devengado         = buildInvoiceTax(inv, "60.00", "12.60");
    InvoiceTax soportadoGoods    = buildInvoiceTax(inv, "40.00", "8.40");
    InvoiceTax soportadoInvest   = buildInvoiceTax(inv, "20.00", "4.20");

    List<Map<String, Object>> rows = runCollectSources(
        java.util.Arrays.asList(devengado, soportadoGoods, soportadoInvest),
        java.util.Arrays.asList("rate-devengado-3w", "rate-goods-3w", "rate-invest-3w"),
        java.util.Arrays.asList(java.util.Arrays.asList(10, 11), java.util.Arrays.asList(36, 37),
            java.util.Arrays.asList(38, 39)));

    assertEquals(1, rows.size());
    Map<String, Object> row = rows.get(0);
    assertEquals(new BigDecimal("60.00"), row.get("base"));
    assertEquals(new BigDecimal("12.60"), row.get("vat"));
    assertEquals(new BigDecimal("72.60"), row.get("total"));
  }

  // ── helpers ───────────────────────────────────────────────────────────────

  @SuppressWarnings("unchecked")
  private List<Map<String, Object>> runCollectSources(InvoiceTax it, String rateId, int baseBox, int taxBox) {
    return runCollectSources(Collections.singletonList(it), Collections.singletonList(rateId),
        Collections.singletonList(java.util.Arrays.asList(baseBox, taxBox)));
  }

  /**
   * Multi-line variant: each element of {@code items}/{@code rateIds}/{@code boxesPerLine} is one
   * {@code C_INVOICETAX} row returned in order by the mocked {@link ScrollableResults}, tagged with
   * its own tax rate id and mapped to its own box list in {@code rateToBoxes} — exactly how
   * {@code Fiscal303BoxesHandler} builds {@code rateToBoxes} from several distinct
   * {@code BoxGroupConfig}s before calling {@code collectSources}.
   */
  @SuppressWarnings("unchecked")
  private List<Map<String, Object>> runCollectSources(List<InvoiceTax> items, List<String> rateIds,
      List<List<Integer>> boxesPerLine) {
    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      OBDal obDal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(obDal);
      Session session = mock(Session.class);
      when(obDal.getSession()).thenReturn(session);

      Map<String, List<Integer>> rateToBoxes = new LinkedHashMap<>();
      for (int i = 0; i < items.size(); i++) {
        String rateId = rateIds.get(i);
        TaxRate rate = mock(TaxRate.class);
        when(rate.getId()).thenReturn(rateId);
        when(obDal.get(eq(TaxRate.class), eq(rateId))).thenReturn(rate);
        when(items.get(i).getTax()).thenReturn(rate);
        rateToBoxes.put(rateId, boxesPerLine.get(i));
      }

      Organization org = mock(Organization.class);
      List<Period> periods = Collections.emptyList();
      AEAT303Report2014Dao dao303 = mock(AEAT303Report2014Dao.class);

      ScrollableResults sr = mock(ScrollableResults.class);
      Boolean[] nextAnswers = new Boolean[items.size() + 1];
      for (int i = 0; i < items.size(); i++) nextAnswers[i] = true;
      nextAnswers[items.size()] = false;
      when(sr.next()).thenReturn(nextAnswers[0],
          java.util.Arrays.copyOfRange(nextAnswers, 1, nextAnswers.length));
      Object[] getAnswers = items.toArray();
      when(sr.get(0)).thenReturn(getAnswers[0],
          java.util.Arrays.copyOfRange(getAnswers, 1, getAnswers.length));
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
