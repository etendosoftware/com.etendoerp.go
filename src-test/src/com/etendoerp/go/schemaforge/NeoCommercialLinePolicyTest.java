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
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import org.codehaus.jettison.json.JSONObject;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.dal.service.OBDal;

/**
 * Unit tests for {@link NeoCommercialLinePolicy}.
 *
 * Covers {@code injectLineGrossAmountIfMissing} (order lines) and
 * {@code normalizeOrderLineSelectorPriceMapping} without any DB access:
 * all paths that require {@code fetchTaxRate} are avoided by providing
 * a non-zero {@code grossUnitPrice} or an empty tax ID (rate = 0).
 *
 * The {@code injectCommercialAmounts} ordering tests are the one exception — deriving a gross
 * from a net inherently needs a tax rate, so they stub the JDBC chain via {@link #withTaxRate}
 * instead of touching a real DB.
 *
 * Key regressions guarded: the client-value guard, the no-double-discount formula
 * ({@code baseNetAmt = unitPrice × qty}, never applying discount twice), and the
 * net-before-gross injector order (ETP-4855).
 */
public class NeoCommercialLinePolicyTest {

  private static final double DELTA = 0.001;

  // ── injectLineGrossAmountIfMissing ────────────────────────────────────────

  @Test
  public void testInjectLineGross_nullBody_doesNotThrow() {
    NeoCommercialLinePolicy.injectLineGrossAmountIfMissing(null);
  }

  /**
   * Client already sent a non-zero lineGrossAmount → must not be overwritten.
   * Regression guard: server-side fallback must yield to client computation.
   */
  @Test
  public void testInjectLineGross_clientValuePresent_notOverwritten() throws Exception {
    JSONObject body = new JSONObject()
        .put("lineGrossAmount", 5.0)
        .put("orderedQuantity", "2")
        .put("unitPrice", 10.0);

    NeoCommercialLinePolicy.injectLineGrossAmountIfMissing(body);

    assertEquals(5.0, body.getDouble("lineGrossAmount"), DELTA);
  }

  @Test
  public void testInjectLineGross_zeroQuantity_nothingInjected() throws Exception {
    JSONObject body = new JSONObject()
        .put("orderedQuantity", "0")
        .put("unitPrice", 10.0);

    NeoCommercialLinePolicy.injectLineGrossAmountIfMissing(body);

    assertFalse(body.has("lineGrossAmount"));
  }

  @Test
  public void testInjectLineGross_zeroUnitPriceNoGross_nothingInjected() throws Exception {
    JSONObject body = new JSONObject()
        .put("orderedQuantity", "2")
        .put("unitPrice", 0.0)
        .put("grossUnitPrice", 0.0)
        .put("tax", "");

    NeoCommercialLinePolicy.injectLineGrossAmountIfMissing(body);

    assertFalse(body.has("lineGrossAmount"));
  }

  /**
   * When grossUnitPrice is provided, lineGrossAmount = grossUnitPrice × qty (no DB needed).
   */
  @Test
  public void testInjectLineGross_grossUnitPriceProvided_usedDirectly() throws Exception {
    JSONObject body = new JSONObject()
        .put("orderedQuantity", "3")
        .put("unitPrice", 10.0)
        .put("grossUnitPrice", 12.10);

    NeoCommercialLinePolicy.injectLineGrossAmountIfMissing(body);

    assertEquals(36.30, body.getDouble("lineGrossAmount"), 0.01);
  }

  /**
   * No tax → rate = 0 → lineGrossAmount = unitPrice × qty × 1.0.
   * Verifies the formula without DB access.
   */
  @Test
  public void testInjectLineGross_noTax_lineGrossEqualsUnitPriceTimesQty() throws Exception {
    JSONObject body = new JSONObject()
        .put("orderedQuantity", "2")
        .put("unitPrice", 50.0)
        .put("tax", "");

    NeoCommercialLinePolicy.injectLineGrossAmountIfMissing(body);

    assertEquals(100.0, body.getDouble("lineGrossAmount"), 0.01);
  }

  /**
   * Regression guard for ETP-3662 double-discount bug.
   *
   * unitPrice=80 is ALREADY post-10%-discount (PriceList=100, disc=10%).
   * The formula must be {@code unitPrice × qty} with NO further discount factor.
   * Before the fix, the formula was {@code unitPrice × qty × (1 − discount/100)},
   * which would produce 72.0 here instead of the correct 80.0.
   */
  @Test
  public void testInjectLineGross_noDoubleDiscount_unitPriceAlreadyPostDiscount() throws Exception {
    JSONObject body = new JSONObject()
        .put("orderedQuantity", "1")
        .put("unitPrice", 80.0)
        .put("tax", "");

    NeoCommercialLinePolicy.injectLineGrossAmountIfMissing(body);

    assertEquals(80.0, body.getDouble("lineGrossAmount"), DELTA);
  }

  // ── normalizeOrderLineSelectorPriceMapping ────────────────────────────────

  @Test
  public void testNormalize_nullBody_doesNotThrow() {
    NeoCommercialLinePolicy.normalizeOrderLineSelectorPriceMapping(null, false, "Net PL");
  }

  @Test
  public void testNormalize_taxIncludedPriceList_grossUnitPricePreserved() throws Exception {
    JSONObject body = new JSONObject().put("grossUnitPrice", 12.10);

    NeoCommercialLinePolicy.normalizeOrderLineSelectorPriceMapping(body, true, "Gross PL");

    assertEquals(12.10, body.getDouble("grossUnitPrice"), DELTA);
  }

  @Test
  public void testNormalize_netPriceList_grossUnitPriceResetToZero() throws Exception {
    JSONObject body = new JSONObject().put("grossUnitPrice", 12.10);

    NeoCommercialLinePolicy.normalizeOrderLineSelectorPriceMapping(body, false, "Net PL");

    assertEquals(0.0, body.getDouble("grossUnitPrice"), DELTA);
  }

  @Test
  public void testNormalize_grossUnitPriceAlreadyZero_notChanged() throws Exception {
    JSONObject body = new JSONObject().put("grossUnitPrice", 0.0);

    NeoCommercialLinePolicy.normalizeOrderLineSelectorPriceMapping(body, false, "Net PL");

    assertEquals(0.0, body.getDouble("grossUnitPrice"), DELTA);
  }

  // ── injectGrossAmountIfMissing (invoice lines) ────────────────────────────

  @Test
  public void testInjectGross_nullBody_doesNotThrow() {
    NeoCommercialLinePolicy.injectGrossAmountIfMissing(null);
  }

  /** Non-numeric quantity → NumberFormatException is swallowed, nothing injected. */
  @Test
  public void testInjectGross_nonNumericQuantity_nothingInjected() throws Exception {
    JSONObject body = new JSONObject()
        .put("invoicedQuantity", "not-a-number")
        .put("grossUnitPrice", 10.0);

    NeoCommercialLinePolicy.injectGrossAmountIfMissing(body);

    assertFalse(body.has("grossAmount"));
  }

  @Test
  public void testInjectGross_zeroQuantity_nothingInjected() throws Exception {
    JSONObject body = new JSONObject()
        .put("invoicedQuantity", "0")
        .put("grossUnitPrice", 10.0);

    NeoCommercialLinePolicy.injectGrossAmountIfMissing(body);

    assertFalse(body.has("grossAmount"));
  }

  /**
   * A positive net amount with no tax needs no gross recomputation → skip entirely.
   * Guards the "already has a usable net, no tax" short-circuit (no DB fetch).
   */
  @Test
  public void testInjectGross_netAmountPresentNoTax_nothingInjected() throws Exception {
    JSONObject body = new JSONObject()
        .put("invoicedQuantity", "2")
        .put("lineNetAmount", 100.0)
        .put("tax", "");

    NeoCommercialLinePolicy.injectGrossAmountIfMissing(body);

    assertFalse(body.has("grossAmount"));
  }

  /** grossUnitPrice provided → grossAmount = grossUnitPrice × qty (no DB access). */
  @Test
  public void testInjectGross_grossUnitPriceProvided_usedDirectly() throws Exception {
    JSONObject body = new JSONObject()
        .put("invoicedQuantity", "3")
        .put("grossUnitPrice", 5.0)
        .put("tax", "");

    NeoCommercialLinePolicy.injectGrossAmountIfMissing(body);

    assertEquals(15.0, body.getDouble("grossAmount"), DELTA);
  }

  /**
   * No gross price and no net amount → the computed value is NaN → nothing injected.
   * This exercises the NaN guard without ever reaching the tax-rate DB fallback.
   */
  @Test
  public void testInjectGross_noGrossNoNet_nanGuardSkipsInjection() throws Exception {
    JSONObject body = new JSONObject()
        .put("invoicedQuantity", "2")
        .put("tax", "");

    NeoCommercialLinePolicy.injectGrossAmountIfMissing(body);

    assertFalse(body.has("grossAmount"));
  }

  // ── injectLineNetAmountIfMissing ──────────────────────────────────────────

  @Test
  public void testInjectLineNet_nullBody_doesNotThrow() {
    NeoCommercialLinePolicy.injectLineNetAmountIfMissing(null);
  }

  /** Non-numeric quantity → NumberFormatException swallowed, nothing injected. */
  @Test
  public void testInjectLineNet_nonNumericQuantity_nothingInjected() throws Exception {
    JSONObject body = new JSONObject()
        .put("invoicedQuantity", "x")
        .put("unitPrice", 10.0);

    NeoCommercialLinePolicy.injectLineNetAmountIfMissing(body);

    assertFalse(body.has("lineNetAmount"));
  }

  @Test
  public void testInjectLineNet_zeroUnitPrice_nothingInjected() throws Exception {
    JSONObject body = new JSONObject()
        .put("invoicedQuantity", "2")
        .put("unitPrice", 0.0);

    NeoCommercialLinePolicy.injectLineNetAmountIfMissing(body);

    assertFalse(body.has("lineNetAmount"));
  }

  @Test
  public void testInjectLineNet_computesQtyTimesUnitPrice() throws Exception {
    JSONObject body = new JSONObject()
        .put("invoicedQuantity", "4")
        .put("unitPrice", 25.0);

    NeoCommercialLinePolicy.injectLineNetAmountIfMissing(body);

    assertEquals(100.0, body.getDouble("lineNetAmount"), DELTA);
    assertTrue(body.has("lineNetAmount"));
  }

  /** Non-numeric orderedQuantity → NFE branch of injectLineGrossAmountIfMissing. */
  @Test
  public void testInjectLineGross_nonNumericQuantity_nothingInjected() throws Exception {
    JSONObject body = new JSONObject()
        .put("orderedQuantity", "abc")
        .put("unitPrice", 10.0);

    NeoCommercialLinePolicy.injectLineGrossAmountIfMissing(body);

    assertFalse(body.has("lineGrossAmount"));
  }

  // ── ETP-4567: negative-amount lines (resolveGrossAmount NaN guard) ────────

  /**
   * ETP-4567 regression guard: a negative quantity is a legitimate line (the frontend
   * now allows negative qty/price). baseNetAmt = unitPrice × qty is negative here, and
   * must still resolve a gross amount instead of the NaN guard silently discarding it
   * (which used to leave the stale pre-edit lineGrossAmount untouched in the DB).
   */
  @Test
  public void testInjectLineGross_negativeQuantity_computesNegativeGross() throws Exception {
    JSONObject body = new JSONObject()
        .put("orderedQuantity", "-2")
        .put("unitPrice", 50.0)
        .put("tax", "");

    NeoCommercialLinePolicy.injectLineGrossAmountIfMissing(body);

    assertEquals(-100.0, body.getDouble("lineGrossAmount"), DELTA);
  }

  /**
   * ETP-4567 regression guard: qty AND unitPrice both negative (e.g. qty already negative,
   * user then flips the price's sign too) — baseNetAmt = unitPrice × qty is POSITIVE here.
   * Before the fix, {@code unitPrice > 0 ? ... : 0} forced baseNetAmt to 0 whenever unitPrice
   * was negative, regardless of qty, which then hit the NaN guard and silently dropped
   * lineGrossAmount from the update (leaving the previous, wrong-signed value in the DB).
   */
  @Test
  public void testInjectLineGross_negativeQtyAndNegativePrice_computesPositiveGross() throws Exception {
    JSONObject body = new JSONObject()
        .put("orderedQuantity", "-1")
        .put("unitPrice", -50.0)
        .put("tax", "");

    NeoCommercialLinePolicy.injectLineGrossAmountIfMissing(body);

    assertEquals(50.0, body.getDouble("lineGrossAmount"), DELTA);
  }

  /** Same guard, invoice side (injectGrossAmountIfMissing / invoicedQuantity). */
  @Test
  public void testInjectGross_negativeLineNetAmount_computesNegativeGross() throws Exception {
    JSONObject body = new JSONObject()
        .put("invoicedQuantity", "-2")
        .put("lineNetAmount", -100.0)
        .put("tax", "");

    NeoCommercialLinePolicy.injectGrossAmountIfMissing(body);

    assertEquals(-100.0, body.getDouble("grossAmount"), DELTA);
  }

  /** baseNetAmt exactly zero must remain indeterminate (NaN guard still applies). */
  @Test
  public void testInjectLineGross_zeroBaseNetAmt_stillNothingInjected() throws Exception {
    JSONObject body = new JSONObject()
        .put("orderedQuantity", "2")
        .put("unitPrice", 0.0)
        .put("grossUnitPrice", 0.0)
        .put("tax", "");

    NeoCommercialLinePolicy.injectLineGrossAmountIfMissing(body);

    assertFalse(body.has("lineGrossAmount"));
  }

  // ── ETP-4855: injectCommercialAmounts ordering ────────────────────────────

  @Test
  public void testInjectCommercialAmounts_nullBody_doesNotThrow() {
    NeoCommercialLinePolicy.injectCommercialAmounts(null);
  }

  /**
   * ETP-4855 regression guard — THE ordering test.
   *
   * <p>An invoice line carrying only quantity, unit price and tax (no amounts at all) is
   * exactly what the OCR {@code /batch} ingest, the MCP write path and the line import modal
   * send. {@code injectGrossAmountIfMissing} derives the gross from {@code lineNetAmount}, so
   * if it runs before {@code injectLineNetAmountIfMissing} the base is 0, the NaN guard fires
   * and {@code grossAmount} is never written — persisting {@code LINE_GROSS_AMOUNT = 0} and a
   * line "Total" column rendered as 0.
   *
   * <p>Asserting on the sequence (not on each injector in isolation, which is what the tests
   * above do and why the bug shipped) is the whole point of this test.
   */
  @Test
  public void testInjectCommercialAmounts_amountsAbsent_derivesNetThenGross() throws Exception {
    JSONObject body = new JSONObject()
        .put("invoicedQuantity", "20")
        .put("unitPrice", 15.5)
        .put("tax", "TAX21");

    withTaxRate(21.0, () -> NeoCommercialLinePolicy.injectCommercialAmounts(body));

    // Net first: 20 × 15.5. Then gross off that net: 310 × 1.21.
    assertEquals(310.0, body.getDouble("lineNetAmount"), DELTA);
    assertEquals(375.1, body.getDouble("grossAmount"), DELTA);
  }

  /**
   * Centralising the sequence must not leak invoice-side fields onto order lines: an order line
   * uses {@code orderedQuantity}, so only {@code lineGrossAmount} may be produced.
   */
  @Test
  public void testInjectCommercialAmounts_orderLine_onlyLineGrossInjected() throws Exception {
    JSONObject body = new JSONObject()
        .put("orderedQuantity", "3")
        .put("unitPrice", 20.0)
        .put("tax", "");

    NeoCommercialLinePolicy.injectCommercialAmounts(body);

    assertEquals(60.0, body.getDouble("lineGrossAmount"), DELTA);
    assertFalse(body.has("lineNetAmount"));
    assertFalse(body.has("grossAmount"));
  }

  /**
   * Run {@code action} with {@code NeoCommercialLinePolicy#fetchTaxRate} resolving to
   * {@code rate}. The rate lookup goes straight to JDBC through
   * {@code OBDal.getInstance().getConnection(false)}, so the whole chain down to the
   * ResultSet is stubbed — no DB, no DAL initialisation.
   */
  private void withTaxRate(double rate, Runnable action) throws Exception {
    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      Connection conn = mock(Connection.class);
      PreparedStatement ps = mock(PreparedStatement.class);
      ResultSet rs = mock(ResultSet.class);

      dalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.getConnection(false)).thenReturn(conn);
      when(conn.prepareStatement(anyString())).thenReturn(ps);
      when(ps.executeQuery()).thenReturn(rs);
      when(rs.next()).thenReturn(true);
      when(rs.getDouble(1)).thenReturn(rate);

      action.run();
    }
  }
}
