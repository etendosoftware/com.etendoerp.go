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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import org.codehaus.jettison.json.JSONObject;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.plm.Product;
import org.openbravo.model.common.uom.UOM;

/**
 * Unit tests for {@link NeoCommercialLinePolicy}.
 *
 * Covers {@code injectLineGrossAmountIfMissing} (order lines) and
 * {@code normalizeOrderLineSelectorPriceMapping} without any DB access:
 * all paths that require {@code fetchTaxRate} are avoided by providing
 * a non-zero {@code grossUnitPrice} or an empty tax ID (rate = 0).
 *
 * Key regression guarded: the client-value guard and the no-double-discount
 * formula ({@code baseNetAmt = unitPrice × qty}, never applying discount twice).
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

  // ── injectProductDerivedUomIfMissing (IMP-15, four-attempt bug) ───────────
  //
  // Incident recap: C_UOM_ID is mandatory on C_OrderLine, so
  // NeoDefaultsService#tryInjectFirstFromLookup preselects the first combo option
  // for it — alphabetically "Centimeter" (ADF850C3E6E9413B9F9EEA5C87456073) — before the
  // product callout ever runs. On the REST path that mandatory, already-populated field
  // then lands in protectedCalloutFields, which is exactly what blocked the callout's
  // correct answer ("100" = Unit) from overwriting the wrong guess. The bad id reached the
  // DAL and the c_orderline_trg trigger raised AD message 20111 ("Unit of Measure mismatch
  // (product/transaction)").
  //
  // Three fix attempts before this one all widened the guard's notion of "absent" — first
  // "", then "0", then "null" — against a value ("ADF850C3...") that was a legitimate,
  // real UOM id and would never match any of those sentinels. The actual fix replaced
  // "is body.uOM non-empty?" (never a safe proxy, since the defaults pass guarantees it is
  // always non-empty) with an explicit userProvidedUom flag supplied by the caller.

  private static final String PRODUCT_ID = "4028E6C227BB4E9C0127BB6A46810004";
  private static final String CENTIMETER_GUESS_UOM_ID = "ADF850C3E6E9413B9F9EEA5C87456073";
  private static final String PRODUCT_UOM_ID = "100";

  private static Product mockProductWithUom(String uomId) {
    Product product = mock(Product.class);
    UOM uom = mock(UOM.class);
    when(uom.getId()).thenReturn(uomId);
    when(product.getUOM()).thenReturn(uom);
    return product;
  }

  /**
   * THE regression case. A body already carries the defaults-pass "Centimeter" guess — a
   * real, valid, non-sentinel UOM id, not an empty/"0"/"null" placeholder — in the {@code uOM}
   * field. With {@code userProvidedUom=false} the caller never actually chose it, so it MUST
   * still be overridden by the product's own UOM. A test that only exercised empty/"0"/"null"
   * would have passed against all three broken attempts and proves nothing about this bug:
   * the whole point of the fix was that "non-empty" is not a usable stand-in for "user chose
   * it". Do not simplify this assertion away.
   */
  @Test
  public void testInjectProductDerivedUom_defaultsGuessPresent_userDidNotProvideIt_isOverridden()
      throws Exception {
    JSONObject body = new JSONObject()
        .put("product", PRODUCT_ID)
        .put("uOM", CENTIMETER_GUESS_UOM_ID);

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(Product.class, PRODUCT_ID)).thenReturn(mockProductWithUom(PRODUCT_UOM_ID));

      NeoCommercialLinePolicy.injectProductDerivedUomIfMissing(body, false);

      assertEquals(PRODUCT_UOM_ID, body.getString("uOM"));
    }
  }

  /**
   * When the caller explicitly provided uOM, their choice is preserved exactly as sent and
   * OBDal is never touched — the whole reason the flag exists is to short-circuit before any
   * lookup runs.
   */
  @Test
  public void testInjectProductDerivedUom_userProvidedUom_leftUntouchedNoDalInteraction()
      throws Exception {
    JSONObject body = new JSONObject()
        .put("product", PRODUCT_ID)
        .put("uOM", CENTIMETER_GUESS_UOM_ID);

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      NeoCommercialLinePolicy.injectProductDerivedUomIfMissing(body, true);

      assertEquals(CENTIMETER_GUESS_UOM_ID, body.getString("uOM"));
      obDal.verifyNoInteractions();
    }
  }

  /** No uOM key at all in the body → injected from the product. */
  @Test
  public void testInjectProductDerivedUom_uomKeyAbsent_injectedFromProduct() throws Exception {
    JSONObject body = new JSONObject().put("product", PRODUCT_ID);

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(Product.class, PRODUCT_ID)).thenReturn(mockProductWithUom(PRODUCT_UOM_ID));

      NeoCommercialLinePolicy.injectProductDerivedUomIfMissing(body, false);

      assertEquals(PRODUCT_UOM_ID, body.getString("uOM"));
    }
  }

  /** uOM key present but empty string → injected from the product. */
  @Test
  public void testInjectProductDerivedUom_uomKeyEmptyString_injectedFromProduct() throws Exception {
    JSONObject body = new JSONObject().put("product", PRODUCT_ID).put("uOM", "");

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(Product.class, PRODUCT_ID)).thenReturn(mockProductWithUom(PRODUCT_UOM_ID));

      NeoCommercialLinePolicy.injectProductDerivedUomIfMissing(body, false);

      assertEquals(PRODUCT_UOM_ID, body.getString("uOM"));
    }
  }

  /** No {@code product} in the body → no-op, no uOM written, no DAL interaction. */
  @Test
  public void testInjectProductDerivedUom_noProduct_noopNoDalInteraction() throws Exception {
    JSONObject body = new JSONObject().put("uOM", CENTIMETER_GUESS_UOM_ID);

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      NeoCommercialLinePolicy.injectProductDerivedUomIfMissing(body, false);

      assertEquals(CENTIMETER_GUESS_UOM_ID, body.getString("uOM"));
      obDal.verifyNoInteractions();
    }
  }

  /** {@code body == null} must not throw. */
  @Test
  public void testInjectProductDerivedUom_nullBody_doesNotThrow() {
    NeoCommercialLinePolicy.injectProductDerivedUomIfMissing(null, false);
  }

  /**
   * Product resolves but has no UOM configured → the 20111-warning branch: no uOM written,
   * no exception propagated.
   */
  @Test
  public void testInjectProductDerivedUom_productHasNoUom_nothingInjected() throws Exception {
    JSONObject body = new JSONObject().put("product", PRODUCT_ID);

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      Product productWithoutUom = mock(Product.class);
      when(productWithoutUom.getUOM()).thenReturn(null);
      when(dal.get(Product.class, PRODUCT_ID)).thenReturn(productWithoutUom);

      NeoCommercialLinePolicy.injectProductDerivedUomIfMissing(body, false);

      assertFalse(body.has("uOM"));
    }
  }

  /** Product id does not resolve to any record (get returns null) → nothing injected, no exception. */
  @Test
  public void testInjectProductDerivedUom_productNotFound_nothingInjected() throws Exception {
    JSONObject body = new JSONObject().put("product", PRODUCT_ID);

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(Product.class, PRODUCT_ID)).thenReturn(null);

      NeoCommercialLinePolicy.injectProductDerivedUomIfMissing(body, false);

      assertFalse(body.has("uOM"));
    }
  }

  /** OBDal.get throws → the exception is swallowed, body is left unchanged, nothing propagates. */
  @Test
  public void testInjectProductDerivedUom_dalThrows_exceptionSwallowedBodyUnchanged() throws Exception {
    JSONObject body = new JSONObject().put("product", PRODUCT_ID);

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(Product.class, PRODUCT_ID)).thenThrow(new RuntimeException("DAL boom"));

      NeoCommercialLinePolicy.injectProductDerivedUomIfMissing(body, false);

      assertFalse(body.has("uOM"));
    }
  }
}
