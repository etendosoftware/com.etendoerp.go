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

package com.etendoerp.go.schemaforge.selector.policy;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Unit tests for {@link ReferenceOverrideSelectorPolicy#resolveFilter}.
 *
 * <p>All branches exercised without any external dependencies — the class
 * contains only a static map and a simple lookup, so pure-JUnit is enough.</p>
 */
public class ReferenceOverrideSelectorPolicyTest {

  // ── null input ────────────────────────────────────────────────────────────

  @Test
  public void resolveFilter_null_returnsNull() {
    assertNull(ReferenceOverrideSelectorPolicy.resolveFilter(null));
  }

  // ── unknown reference ─────────────────────────────────────────────────────

  @Test
  public void resolveFilter_unknownId_returnsNull() {
    assertNull(ReferenceOverrideSelectorPolicy.resolveFilter("UNKNOWN_REFERENCE_ID"));
  }

  @Test
  public void resolveFilter_emptyString_returnsNull() {
    assertNull(ReferenceOverrideSelectorPolicy.resolveFilter(""));
  }

  // ── known overrides ───────────────────────────────────────────────────────

  /**
   * Reference 166 (price list) must restrict to sales price lists only.
   */
  @Test
  public void resolveFilter_ref166_returnsSalesPriceListFilter() {
    String filter = ReferenceOverrideSelectorPolicy.resolveFilter("166");
    assertNotNull(filter);
    assertTrue("Expected salesPriceList = true filter",
        filter.contains("salesPriceList") && filter.contains("true"));
  }

  /**
   * Reference 800031 (purchase price list) must restrict to non-sales price lists.
   */
  @Test
  public void resolveFilter_ref800031_returnsPurchasePriceListFilter() {
    String filter = ReferenceOverrideSelectorPolicy.resolveFilter("800031");
    assertNotNull(filter);
    assertTrue("Expected salesPriceList = false filter",
        filter.contains("salesPriceList") && filter.contains("false"));
  }

  /**
   * Reference EED0EF97D4A7421687F3B365D009E7A6 (payment method FK) must
   * restrict to methods linked to at least one financial account.
   */
  @Test
  public void resolveFilter_paymentMethodRef_returnsFinAccPaymentMethodFilter() {
    String filter = ReferenceOverrideSelectorPolicy.resolveFilter(
        "EED0EF97D4A7421687F3B365D009E7A6");
    assertNotNull(filter);
    assertTrue("Expected FinancialMgmtFinAccPaymentMethod subquery",
        filter.contains("FinancialMgmtFinAccPaymentMethod"));
    assertTrue("Expected paymentMethod property reference",
        filter.contains("paymentMethod"));
  }

  /**
   * Reference DF1CEA94B3564A33AFDB37C07E1CE353 (financial account FK) must
   * restrict to accounts linked to an active payment method mapping.
   */
  @Test
  public void resolveFilter_financialAccountRef_returnsFinAccFilter() {
    String filter = ReferenceOverrideSelectorPolicy.resolveFilter(
        "DF1CEA94B3564A33AFDB37C07E1CE353");
    assertNotNull(filter);
    assertTrue("Expected FinancialMgmtFinAccPaymentMethod subquery",
        filter.contains("FinancialMgmtFinAccPaymentMethod"));
    assertTrue("Expected account property reference",
        filter.contains("account"));
  }

  /**
   * All four configured overrides must return non-null, non-empty strings.
   */
  @Test
  public void resolveFilter_allKnownRefs_returnNonEmptyFilters() {
    String[] knownRefs = {
        "166",
        "800031",
        "EED0EF97D4A7421687F3B365D009E7A6",
        "DF1CEA94B3564A33AFDB37C07E1CE353"
    };
    for (String ref : knownRefs) {
      String filter = ReferenceOverrideSelectorPolicy.resolveFilter(ref);
      assertNotNull("Filter must not be null for known ref: " + ref, filter);
      assertTrue("Filter must not be blank for known ref: " + ref, !filter.isEmpty());
    }
  }
}
