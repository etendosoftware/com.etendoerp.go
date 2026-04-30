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
 * Unit tests for {@link NeoDefaultsService#injectLineNetAmountIfMissing}.
 *
 * <p>These tests do NOT require a database connection. All tests exercise the
 * server-side computation of {@code lineNetAmount = invoicedQuantity × unitPrice},
 * which runs after {@code filterWriteRequest} strips the read-only {@code lineNetAmount}
 * field from the PATCH body.</p>
 *
 * <p>Since ETP-3662, {@code lineGrossAmount} / {@code grossAmount} are computed
 * client-side and sent with the request; the server no longer recomputes them.
 * {@code injectLineNetAmountIfMissing} covers only the net amount fallback for
 * invoice lines.</p>
 */
public class NeoDefaultsServiceTest {

  // ── Helpers ──────────────────────────────────────────────────────────────────

  private static void assertAmountEquals(double expected, double actual) {
    assertEquals("expected " + expected + " but got " + actual,
        expected, actual, 0.005);
  }

  // ── injectLineNetAmountIfMissing — null body → no NPE ─────────────────────

  @Test
  public void testNullBodyIsIgnored() {
    // Should not throw
    NeoDefaultsService.injectLineNetAmountIfMissing(null);
  }

  // ── injectLineNetAmountIfMissing — zero qty → no injection ─────────────────

  @Test
  public void testZeroQtyNoInjection() throws Exception {
    JSONObject body = new JSONObject();
    body.put("invoicedQuantity", "0");
    body.put("unitPrice", 29.70);

    NeoDefaultsService.injectLineNetAmountIfMissing(body);

    assertFalse("lineNetAmount should not be injected when invoicedQuantity is zero",
        body.has("lineNetAmount"));
  }

  // ── injectLineNetAmountIfMissing — missing qty → no injection ──────────────

  @Test
  public void testMissingQtyNoInjection() throws Exception {
    JSONObject body = new JSONObject();
    body.put("unitPrice", 29.70);

    NeoDefaultsService.injectLineNetAmountIfMissing(body);

    assertFalse("lineNetAmount should not be injected when invoicedQuantity is absent",
        body.has("lineNetAmount"));
  }

  // ── injectLineNetAmountIfMissing — zero unitPrice → no injection ───────────

  @Test
  public void testZeroUnitPriceNoInjection() throws Exception {
    JSONObject body = new JSONObject();
    body.put("invoicedQuantity", "3");
    body.put("unitPrice", 0);

    NeoDefaultsService.injectLineNetAmountIfMissing(body);

    assertFalse("lineNetAmount should not be injected when unitPrice is zero",
        body.has("lineNetAmount"));
  }

  // ── injectLineNetAmountIfMissing — normal path ────────────────────────────

  @Test
  public void testNormalPathComputation() throws Exception {
    JSONObject body = new JSONObject();
    body.put("invoicedQuantity", "3");
    body.put("unitPrice", 29.70);

    NeoDefaultsService.injectLineNetAmountIfMissing(body);

    assertTrue("lineNetAmount should be injected", body.has("lineNetAmount"));
    assertAmountEquals(89.10, body.getDouble("lineNetAmount")); // 29.70 × 3
  }

  // ── injectLineNetAmountIfMissing — invoicedQuantity as string ─────────────

  @Test
  public void testInvoicedQtyAsStringIsParsed() throws Exception {
    JSONObject body = new JSONObject();
    body.put("invoicedQuantity", "5");
    body.put("unitPrice", 10.00);

    NeoDefaultsService.injectLineNetAmountIfMissing(body);

    assertTrue("lineNetAmount should be injected", body.has("lineNetAmount"));
    assertAmountEquals(50.00, body.getDouble("lineNetAmount")); // 10 × 5
  }

  // ── injectLineNetAmountIfMissing — always recomputes (no early-return) ─────

  /**
   * Since ETP-3662 the method always recomputes lineNetAmount to override any stale
   * value left by the product callout (which computes for qty=1). Verify that an
   * existing lineNetAmount is overwritten with the correct value.
   */
  @Test
  public void testAlwaysRecomputes() throws Exception {
    JSONObject body = new JSONObject();
    body.put("invoicedQuantity", "3");
    body.put("unitPrice", 29.70);
    body.put("lineNetAmount", 999.99); // stale value from callout

    NeoDefaultsService.injectLineNetAmountIfMissing(body);

    assertAmountEquals(89.10, body.getDouble("lineNetAmount")); // overwritten: 29.70 × 3
  }

}
