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
 * Unit tests for {@link Fiscal303SourcesSupport}: the ETP-5338 {@code accountingDate} field, and
 * the ETP-5456 row-per-declaration-side grouping (replacing the discarded halving approach — see
 * the class javadoc on {@link Fiscal303SourcesSupport} for the full history), the sign-preserving
 * accumulation rule, and the {@code accrued}/{@code deductible} {@code type} label.
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
   * ETP-5456 (final design — see class javadoc): an intra-EU acquisition invoice posts TWO
   * {@code C_INVOICETAX} lines for the SAME base/tax — devengado (box 10/11, {@code
   * VAT_SALES_EU}) and soportado deductible (box 36/37, {@code Intracommunity_Goods}). These land
   * in two DIFFERENT box sets, so {@code collectSources} must produce TWO rows, each showing the
   * FULL (non-halved) amount, one tagged {@code "accrued"} and one {@code "deductible"} — not one
   * doubled row (the original bug) and not one halved row (the discarded intermediate fix).
   */
  @Test
  public void testCollectSources_intraEuAcquisitionPairedLines_producesTwoFullAmountRows() {
    Invoice inv = buildInvoice("inv-eu-1", "REC-1000003", date(2026, 3, 5), date(2026, 3, 5));
    InvoiceTax devengado = buildInvoiceTax(inv, "20.00", "4.20");
    InvoiceTax soportado = buildInvoiceTax(inv, "20.00", "4.20");

    List<Map<String, Object>> rows = runCollectSources(
        java.util.Arrays.asList(devengado, soportado),
        java.util.Arrays.asList("rate-devengado", "rate-soportado"),
        java.util.Arrays.asList(java.util.Arrays.asList(10, 11), java.util.Arrays.asList(36, 37)));

    assertEquals(2, rows.size());
    Map<String, Object> accrued = rows.get(0);
    assertEquals("accrued", accrued.get("type"));
    assertEquals("10,11", accrued.get(Fiscal303BoxesHandler.BOXES));
    assertEquals(new BigDecimal("20.00"), accrued.get("base"));
    assertEquals(new BigDecimal("4.20"), accrued.get("vat"));
    assertEquals(new BigDecimal("24.20"), accrued.get("total"));

    Map<String, Object> deductible = rows.get(1);
    assertEquals("deductible", deductible.get("type"));
    assertEquals("36,37", deductible.get(Fiscal303BoxesHandler.BOXES));
    assertEquals(new BigDecimal("20.00"), deductible.get("base"));
    assertEquals(new BigDecimal("4.20"), deductible.get("vat"));
    assertEquals(new BigDecimal("24.20"), deductible.get("total"));
  }

  /**
   * ETP-5456: the row-per-side grouping is generic over the box FAMILY, not hardcoded to
   * intra-EU/{@code Intracommunity_Goods} — a domestic ISP (reverse charge) operation uses a
   * different box pair on each side (12/13 devengado, {@code VAT_SALES_ISP}; 28/29 deductible,
   * {@code Normal_Operations}) and must be split into two rows exactly the same way, with no
   * reference anywhere to the specific box numbers 10/11/36/37.
   */
  @Test
  public void testCollectSources_domesticIspReverseCharge_alsoProducesTwoRowsGenerically() {
    Invoice inv = buildInvoice("inv-isp-1", "REC-6000001", date(2026, 3, 12), date(2026, 3, 12));
    InvoiceTax devengado = buildInvoiceTax(inv, "45.00", "9.45");
    InvoiceTax soportado = buildInvoiceTax(inv, "45.00", "9.45");

    List<Map<String, Object>> rows = runCollectSources(
        java.util.Arrays.asList(devengado, soportado),
        java.util.Arrays.asList("rate-isp-devengado", "rate-isp-soportado"),
        java.util.Arrays.asList(java.util.Arrays.asList(12, 13), java.util.Arrays.asList(28, 29)));

    assertEquals(2, rows.size());
    Map<String, Object> accrued = rows.get(0);
    assertEquals("accrued", accrued.get("type"));
    assertEquals("12,13", accrued.get(Fiscal303BoxesHandler.BOXES));
    assertEquals(new BigDecimal("45.00"), accrued.get("base"));
    assertEquals(new BigDecimal("9.45"), accrued.get("vat"));
    assertEquals(new BigDecimal("54.45"), accrued.get("total"));

    Map<String, Object> deductible = rows.get(1);
    assertEquals("deductible", deductible.get("type"));
    assertEquals("28,29", deductible.get(Fiscal303BoxesHandler.BOXES));
    assertEquals(new BigDecimal("45.00"), deductible.get("base"));
    assertEquals(new BigDecimal("9.45"), deductible.get("vat"));
    assertEquals(new BigDecimal("54.45"), deductible.get("total"));
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
   * ETP-5456 (final design) — regression guard for odd-cent amounts on a row-per-side pair: with
   * halving gone, there is no intermediate division to introduce rounding drift, but each side's
   * row must still preserve its line's EXACT amount (55.37 / 11.63) with no spurious rounding —
   * not the previously-expected halved 27.685-ish figures.
   */
  @Test
  public void testCollectSources_pairedLinesOddCents_eachSidePreservesExactAmount() {
    Invoice inv = buildInvoice("inv-eu-oddcents", "REC-2000001", date(2026, 3, 7), date(2026, 3, 7));
    InvoiceTax devengado = buildInvoiceTax(inv, "55.37", "11.63");
    InvoiceTax soportado = buildInvoiceTax(inv, "55.37", "11.63");

    List<Map<String, Object>> rows = runCollectSources(
        java.util.Arrays.asList(devengado, soportado),
        java.util.Arrays.asList("rate-devengado-odd", "rate-soportado-odd"),
        java.util.Arrays.asList(java.util.Arrays.asList(10, 11), java.util.Arrays.asList(36, 37)));

    assertEquals(2, rows.size());
    for (Map<String, Object> row : rows) {
      assertEquals(new BigDecimal("55.37"), row.get("base"));
      assertEquals(new BigDecimal("11.63"), row.get("vat"));
      assertEquals(new BigDecimal("67.00"), row.get("total"));
    }
    assertEquals("accrued", rows.get(0).get("type"));
    assertEquals("deductible", rows.get(1).get("type"));
  }

  /**
   * ETP-5456 (final design) — mixed invoice: a full intra-EU reverse-charge pair (devengado 10/11
   * + soportado 36/37, base/tax 30.00/6.30 each) coexists on the SAME invoice with an unrelated
   * normal domestic line (boxes 7/9, base 50.00, tax 10.50). Three DIFFERENT box sets must
   * produce THREE separate rows, each with its own full amount — nothing merges and nothing is
   * halved, even though two of the three rows happen to belong to the same invoice as a pair.
   */
  @Test
  public void testCollectSources_mixedInvoiceReverseChargePlusNormalLine_producesThreeRows() {
    Invoice inv = buildInvoice("inv-mixed-1", "REC-3000001", date(2026, 3, 8), date(2026, 3, 8));
    InvoiceTax devengado = buildInvoiceTax(inv, "30.00", "6.30");
    InvoiceTax soportado = buildInvoiceTax(inv, "30.00", "6.30");
    InvoiceTax normal    = buildInvoiceTax(inv, "50.00", "10.50");

    List<Map<String, Object>> rows = runCollectSources(
        java.util.Arrays.asList(devengado, soportado, normal),
        java.util.Arrays.asList("rate-devengado-mix", "rate-soportado-mix", "rate-normal-mix"),
        java.util.Arrays.asList(java.util.Arrays.asList(10, 11), java.util.Arrays.asList(36, 37),
            java.util.Arrays.asList(7, 9)));

    assertEquals(3, rows.size());

    Map<String, Object> accrued = rows.get(0);
    assertEquals("accrued", accrued.get("type"));
    assertEquals("10,11", accrued.get(Fiscal303BoxesHandler.BOXES));
    assertEquals(new BigDecimal("30.00"), accrued.get("base"));
    assertEquals(new BigDecimal("6.30"), accrued.get("vat"));

    Map<String, Object> deductible = rows.get(1);
    assertEquals("deductible", deductible.get("type"));
    assertEquals("36,37", deductible.get(Fiscal303BoxesHandler.BOXES));
    assertEquals(new BigDecimal("30.00"), deductible.get("base"));
    assertEquals(new BigDecimal("6.30"), deductible.get("vat"));

    Map<String, Object> normalRow = rows.get(2);
    assertEquals("accrued", normalRow.get("type"));
    assertEquals("7,9", normalRow.get(Fiscal303BoxesHandler.BOXES));
    assertEquals(new BigDecimal("50.00"), normalRow.get("base"));
    assertEquals(new BigDecimal("10.50"), normalRow.get("vat"));
    assertEquals(new BigDecimal("60.50"), normalRow.get("total"));
  }

  /**
   * ETP-5456 (final design) — 3-way split: a single devengado line (boxes 10/11, base 60.00 /
   * tax 12.60) covers the FULL intra-EU acquisition, while the matching deduction is split across
   * TWO soportado lines on the same invoice — goods (boxes 36/37, base 40.00 / tax 8.40) and
   * investment goods (boxes 38/39, base 20.00 / tax 4.20). Since each of the three lines resolves
   * to its OWN distinct box set, {@code collectSources} must produce THREE rows, each carrying its
   * own line's real (un-netted) amount — never the netted/summed total a halving approach would
   * have produced.
   */
  @Test
  public void testCollectSources_threeWaySplitAcrossGoodsAndInvestmentBoxes_eachRowKeepsOwnAmount() {
    Invoice inv = buildInvoice("inv-3way-1", "REC-4000001", date(2026, 3, 9), date(2026, 3, 9));
    InvoiceTax devengado         = buildInvoiceTax(inv, "60.00", "12.60");
    InvoiceTax soportadoGoods    = buildInvoiceTax(inv, "40.00", "8.40");
    InvoiceTax soportadoInvest   = buildInvoiceTax(inv, "20.00", "4.20");

    List<Map<String, Object>> rows = runCollectSources(
        java.util.Arrays.asList(devengado, soportadoGoods, soportadoInvest),
        java.util.Arrays.asList("rate-devengado-3w", "rate-goods-3w", "rate-invest-3w"),
        java.util.Arrays.asList(java.util.Arrays.asList(10, 11), java.util.Arrays.asList(36, 37),
            java.util.Arrays.asList(38, 39)));

    assertEquals(3, rows.size());

    Map<String, Object> accrued = rows.get(0);
    assertEquals("accrued", accrued.get("type"));
    assertEquals("10,11", accrued.get(Fiscal303BoxesHandler.BOXES));
    assertEquals(new BigDecimal("60.00"), accrued.get("base"));
    assertEquals(new BigDecimal("12.60"), accrued.get("vat"));
    assertEquals(new BigDecimal("72.60"), accrued.get("total"));

    Map<String, Object> goods = rows.get(1);
    assertEquals("deductible", goods.get("type"));
    assertEquals("36,37", goods.get(Fiscal303BoxesHandler.BOXES));
    assertEquals(new BigDecimal("40.00"), goods.get("base"));
    assertEquals(new BigDecimal("8.40"), goods.get("vat"));
    assertEquals(new BigDecimal("48.40"), goods.get("total"));

    Map<String, Object> invest = rows.get(2);
    assertEquals("deductible", invest.get("type"));
    assertEquals("38,39", invest.get(Fiscal303BoxesHandler.BOXES));
    assertEquals(new BigDecimal("20.00"), invest.get("base"));
    assertEquals(new BigDecimal("4.20"), invest.get("vat"));
    assertEquals(new BigDecimal("24.20"), invest.get("total"));
  }

  /**
   * ETP-5456 (final design) — a rectificativa (corrective/negative) invoice posting a NEGATIVE
   * intra-EU acquisition pair on both sides. {@code isCorrectiveInvoiceTax} redirects each line's
   * normal box pair through {@link Fiscal303SourcesSupport#correctiveBoxesFor} to a DIFFERENT
   * corrective pair (10/11 → 14/15 devengado; 36/37 → 40/41 deductible), so the two lines still
   * land in two distinct box sets and produce two rows. {@code accumulateInvoiceTax} must keep
   * each row's NEGATIVE sign — base as-is, tax {@code .abs()} then re-signed to match the base —
   * with no {@code .abs()} anywhere stripping the sign off the final row.
   */
  @Test
  public void testCollectSources_negativeRectificativaPairedLines_preservesSignOnBothRows() {
    Invoice inv = buildInvoice("inv-rect-1", "RECT-1000001", date(2026, 3, 10), date(2026, 3, 10));
    InvoiceTax devengado = buildInvoiceTax(inv, "-20.00", "-4.20");
    InvoiceTax soportado = buildInvoiceTax(inv, "-20.00", "-4.20");

    List<Map<String, Object>> rows = runCollectSources(
        java.util.Arrays.asList(devengado, soportado),
        java.util.Arrays.asList("rate-devengado-rect", "rate-soportado-rect"),
        java.util.Arrays.asList(java.util.Arrays.asList(10, 11), java.util.Arrays.asList(36, 37)));

    assertEquals(2, rows.size());

    Map<String, Object> accrued = rows.get(0);
    assertEquals("accrued", accrued.get("type"));
    // Corrective redirection: normal 10/11 -> corrective 14/15 (SALES_GENERAL_CORRECTIVE_BOXES).
    assertEquals("14,15", accrued.get(Fiscal303BoxesHandler.BOXES));
    assertEquals(new BigDecimal("-20.00"), accrued.get("base"));
    assertEquals(new BigDecimal("-4.20"), accrued.get("vat"));
    assertEquals(new BigDecimal("-24.20"), accrued.get("total"));

    Map<String, Object> deductible = rows.get(1);
    assertEquals("deductible", deductible.get("type"));
    // Corrective redirection: normal 36/37 -> corrective 40/41 (PURCHASE_CORRECTIVE_BOXES).
    assertEquals("40,41", deductible.get(Fiscal303BoxesHandler.BOXES));
    assertEquals(new BigDecimal("-20.00"), deductible.get("base"));
    assertEquals(new BigDecimal("-4.20"), deductible.get("vat"));
    assertEquals(new BigDecimal("-24.20"), deductible.get("total"));
  }

  /**
   * ETP-5456 (final design) — GAP CLOSED, was previously documented as a known limitation of the
   * halving approach: a single unpaired box-10/11 line (data-integrity edge case — e.g. a partial
   * import, or an invoice where only the devengado leg was posted) used to be silently halved to
   * HALF its real amount because the old code assumed every line in a paired-box family had a
   * counterpart. With halving removed entirely, an unpaired line is simply its own row with its
   * FULL amount — there is no pairing assumption left to violate.
   */
  @Test
  public void testCollectSources_unpairedReverseChargeLine_keepsFullAmount_gapClosed() {
    Invoice inv = buildInvoice("inv-unpaired-1", "REC-5000001", date(2026, 3, 11), date(2026, 3, 11));
    InvoiceTax devengadoOnly = buildInvoiceTax(inv, "20.00", "4.20");

    List<Map<String, Object>> rows = runCollectSources(devengadoOnly, "rate-devengado-only", 10, 11);

    assertEquals(1, rows.size());
    Map<String, Object> row = rows.get(0);
    assertEquals("accrued", row.get("type"));
    assertEquals(new BigDecimal("20.00"), row.get("base"));
    assertEquals(new BigDecimal("4.20"), row.get("vat"));
    assertEquals(new BigDecimal("24.20"), row.get("total"));
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
