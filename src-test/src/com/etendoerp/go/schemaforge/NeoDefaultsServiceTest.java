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
 * All portions are Copyright © 2021–2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.schemaforge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.codehaus.jettison.json.JSONObject;
import org.junit.Test;

/**
 * Unit tests for {@link NeoDefaultsService#injectLineGrossAmountIfMissing}.
 *
 * <p>These tests do NOT require a database connection. All tests exercise the
 * net-price-list path with {@code tax=""} so {@code fetchTaxRate()} short-circuits
 * to 0%, making the tax factor 1.0 and removing the need for a live DB.</p>
 *
 * <p>The gross-price-list path ({@code grossUnitPrice > 0}) requires no tax lookup
 * either, so it is also covered without DB access.</p>
 */
public class NeoDefaultsServiceTest {

  // ── Helpers ──────────────────────────────────────────────────────────────────

  /**
   * Asserts that two doubles are equal within a small epsilon (0.005 — half a cent).
   */
  private static void assertAmountEquals(double expected, double actual) {
    assertEquals("expected " + expected + " but got " + actual,
        expected, actual, 0.005);
  }

  // ── injectLineGrossAmountIfMissing — client value respected ───────────────────

  /**
   * When the body already contains a non-zero {@code lineGrossAmount} the method
   * must return early without overwriting it (client is the source of truth).
   */
  @Test
  public void testClientValueRespected() throws Exception {
    JSONObject body = new JSONObject();
    body.put("orderedQuantity", "3");
    body.put("unitPrice", 29.70);
    body.put("discount", 10);
    body.put("tax", "");
    body.put("lineGrossAmount", 107.81);

    NeoDefaultsService.injectLineGrossAmountIfMissing(body);

    assertAmountEquals(107.81, body.getDouble("lineGrossAmount"));
  }

  // ── injectLineGrossAmountIfMissing — no unitPrice → no injection ──────────────

  /**
   * When {@code unitPrice} is absent the server cannot compute a meaningful value;
   * {@code lineGrossAmount} must NOT be injected.
   */
  @Test
  public void testNoUnitPriceNoInjection() throws Exception {
    JSONObject body = new JSONObject();
    body.put("orderedQuantity", "3");
    body.put("tax", "");

    NeoDefaultsService.injectLineGrossAmountIfMissing(body);

    assertFalse("lineGrossAmount should not be injected when unitPrice is missing",
        body.has("lineGrossAmount"));
  }

  // ── injectLineGrossAmountIfMissing — no orderedQuantity → no injection ─────────

  /**
   * When {@code orderedQuantity} is absent (zero) the formula is undefined;
   * no injection must occur.
   */
  @Test
  public void testNoQtyNoInjection() throws Exception {
    JSONObject body = new JSONObject();
    body.put("unitPrice", 29.70);
    body.put("tax", "");

    NeoDefaultsService.injectLineGrossAmountIfMissing(body);

    assertFalse("lineGrossAmount should not be injected when orderedQuantity is missing",
        body.has("lineGrossAmount"));
  }

  // ── injectLineGrossAmountIfMissing — null body → no NPE ───────────────────────

  /**
   * Null body must be silently ignored (defensive null-check in the implementation).
   */
  @Test
  public void testNullBodyIsIgnored() {
    // Should not throw
    NeoDefaultsService.injectLineGrossAmountIfMissing(null);
  }

  // ── injectLineGrossAmountIfMissing — net-path regression: no double discount ───

  /**
   * Regression test for the double-discount bug fixed in ETP-3662.
   *
   * <p>Scenario: listPrice=33, discount=10%, unitPrice=29.70 (pre-computed by client),
   * qty=3, tax="" (0% → factor=1.0).</p>
   *
   * <p>Correct formula: {@code unitPrice × qty × taxFactor = 29.70 × 3 × 1.0 = 89.10}.
   * The old (buggy) formula applied discountFactor again:
   * {@code unitPrice × qty × discountFactor × taxFactor = 29.70 × 3 × 0.9 × 1.0 = 80.19}.
   * This test verifies the fix produces 89.10, not 80.19.</p>
   */
  @Test
  public void testNetPathNoDoubleDiscount() throws Exception {
    JSONObject body = new JSONObject();
    body.put("orderedQuantity", "3");
    body.put("unitPrice", 29.70);   // already post-discount (29.70 = 33 × 0.9)
    body.put("discount", 10);       // must NOT be applied again server-side
    body.put("tax", "");            // empty taxId → fetchTaxRate returns 0 → factor = 1.0

    NeoDefaultsService.injectLineGrossAmountIfMissing(body);

    assertTrue("lineGrossAmount should be injected", body.has("lineGrossAmount"));
    double injected = body.getDouble("lineGrossAmount");
    assertAmountEquals(89.10, injected);  // correct: 29.70 × 3 × 1.0
    // Prove old bug value is wrong
    assertFalse("double-discount value 80.19 must not be produced",
        Math.abs(injected - 80.19) < 0.005);
  }

  // ── injectLineGrossAmountIfMissing — gross-price-list path ────────────────────

  /**
   * When {@code grossUnitPrice > 0} the formula is {@code grossUnitPrice × qty},
   * no tax lookup is required.
   */
  @Test
  public void testGrossPathUsesGrossUnitPrice() throws Exception {
    JSONObject body = new JSONObject();
    body.put("orderedQuantity", "2");
    body.put("unitPrice", 0);         // ignored on gross path
    body.put("grossUnitPrice", 53.24);
    body.put("tax", "SOME-TAX-ID");   // irrelevant — gross path skips tax lookup

    NeoDefaultsService.injectLineGrossAmountIfMissing(body);

    assertTrue("lineGrossAmount should be injected on gross path", body.has("lineGrossAmount"));
    assertAmountEquals(106.48, body.getDouble("lineGrossAmount")); // 53.24 × 2
  }

  // ── injectLineGrossAmountIfMissing — orderedQuantity as string ────────────────

  /**
   * {@code orderedQuantity} arrives as a JSON string in some request paths.
   * The method parses it via {@code Double.parseDouble} — verify this works.
   */
  @Test
  public void testOrdQtyAsStringIsParsed() throws Exception {
    JSONObject body = new JSONObject();
    body.put("orderedQuantity", "5");
    body.put("unitPrice", 10.00);
    body.put("tax", "");

    NeoDefaultsService.injectLineGrossAmountIfMissing(body);

    assertTrue("lineGrossAmount should be injected", body.has("lineGrossAmount"));
    assertAmountEquals(50.00, body.getDouble("lineGrossAmount")); // 10 × 5 × 1.0
  }

}
