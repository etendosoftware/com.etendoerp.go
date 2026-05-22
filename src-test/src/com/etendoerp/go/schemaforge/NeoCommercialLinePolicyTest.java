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

import org.codehaus.jettison.json.JSONObject;
import org.junit.Test;

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
}
