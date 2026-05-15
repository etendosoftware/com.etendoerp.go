/*
 *************************************************************************
 * The contents of this file are subject to the Etendo License
 * (the "License"), you may not use this file except in compliance
 * with the License.
 * You may obtain a copy of the License at
 * https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * Software distributed under the License is distributed on an
 * "AS IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
 * implied. See the License for the specific language governing rights
 * and limitations under the License.
 * All portions are Copyright (C) 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 *************************************************************************
 */
package com.etendoerp.go.schemaforge;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link WidgetQueryPolicyRegistry}.
 * Verifies that each policy's SQL templates contain the correct clauses, placeholders,
 * and regression guards so the dashboard widgets never silently return wrong data.
 */
class WidgetQueryPolicyRegistryTest {

  /**
   * Verifies that the best-products policy uses COALESCE so a missing current-period amount
   * defaults to 0 instead of NULL, which would break trend percentage calculations.
   */
  @Test
  void bestProductsTrendUsesZeroWhenCurrentPeriodHasNoSales() {
    WidgetQueryPolicyRegistry.WidgetQueryPolicy policy = WidgetQueryPolicyRegistry.bestProducts();

    assertTrue(policy.fallbackSql.contains("COALESCE(curr_period.amount, 0)"));
    assertTrue(policy.rangedSql.contains("COALESCE(curr_period.amount, 0)"));
  }

  /**
   * Verifies that the best-sellers policy uses COALESCE so a missing current-period quantity
   * defaults to 0 instead of NULL, which would break trend percentage calculations.
   */
  @Test
  void bestSellersTrendUsesZeroWhenCurrentPeriodHasNoSales() {
    WidgetQueryPolicyRegistry.WidgetQueryPolicy policy = WidgetQueryPolicyRegistry.bestSellers();

    assertTrue(policy.fallbackSql.contains("COALESCE(curr_period.qty, 0)"));
    assertTrue(policy.rangedSql.contains("COALESCE(curr_period.qty, 0)"));
  }

  // -----------------------------------------------------------------
  // recentInvoices() — regression tests for ETP-4004
  // The old handler had a hardcoded "CURRENT_DATE - '7 days'" that
  // ignored the ?range= param. These tests verify the replacement uses
  // dynamic placeholders and anchors to the most-recent invoice date.
  // -----------------------------------------------------------------

  /**
   * Regression guard: verifies that fallbackSql uses a 30-day window anchored to the latest
   * invoice date instead of the old hardcoded {@code CURRENT_DATE - '7 days'} offset.
   */
  @Test
  void recentInvoicesFallbackSqlAnchorsToRecentDateNot7Days() {
    WidgetQueryPolicyRegistry.WidgetQueryPolicy policy = WidgetQueryPolicyRegistry.recentInvoices();

    // Fallback must use the 30-day rolling window anchored to the latest invoice date,
    // not a hardcoded CURRENT_DATE offset.
    assertTrue(policy.fallbackSql.contains("CAST('30 days' AS interval)"),
        "fallbackSql should use a 30-day anchor window, not the old 7-day one");
    assertFalse(policy.fallbackSql.contains("CAST('7 days'"),
        "fallbackSql must not contain the old 7-day hardcoded offset (regression guard)");
  }

  /**
   * Verifies that rangedSql contains a {@code %s} format placeholder so the handler can
   * interpolate the resolved date boundary at runtime, and has no old hardcoded offsets.
   */
  @Test
  void recentInvoicesRangedSqlContainsPlaceholderNotHardcodedDate() {
    WidgetQueryPolicyRegistry.WidgetQueryPolicy policy = WidgetQueryPolicyRegistry.recentInvoices();

    // rangedSql must accept a dynamic date via %s so the handler can interpolate
    // the resolved range boundary at runtime.
    assertTrue(policy.rangedSql.contains("%s"),
        "rangedSql must contain the %s placeholder for the resolved range boundary");
    assertFalse(policy.rangedSql.contains("CAST('7 days'"),
        "rangedSql must not contain any hardcoded 7-day offset (regression guard)");
  }

  /**
   * Verifies that rangedSql orders rows by {@code dateinvoiced DESC} so the most
   * recent invoices always appear first in the widget list.
   */
  @Test
  void recentInvoicesRangedSqlOrdersByDateDescending() {
    WidgetQueryPolicyRegistry.WidgetQueryPolicy policy = WidgetQueryPolicyRegistry.recentInvoices();

    assertTrue(policy.rangedSql.contains("ORDER BY i.dateinvoiced DESC"),
        "rangedSql must order results by dateinvoiced DESC");
  }

  /**
   * Verifies that rangedSql includes {@code LIMIT 10} to cap the result set and
   * prevent unbounded queries from overloading the dashboard.
   */
  @Test
  void recentInvoicesRangedSqlLimitsResultsTo10() {
    WidgetQueryPolicyRegistry.WidgetQueryPolicy policy = WidgetQueryPolicyRegistry.recentInvoices();

    assertTrue(policy.rangedSql.contains("LIMIT 10"), "rangedSql must include LIMIT 10 to cap results");
  }

  /**
   * Verifies that fallbackSql also includes {@code LIMIT 10} so even the no-range path
   * caps its result set consistently with the ranged variant.
   */
  @Test
  void recentInvoicesFallbackSqlLimitsResultsTo10() {
    WidgetQueryPolicyRegistry.WidgetQueryPolicy policy = WidgetQueryPolicyRegistry.recentInvoices();

    assertTrue(policy.fallbackSql.contains("LIMIT 10"), "fallbackSql must include LIMIT 10 to cap results");
  }

  /**
   * Verifies that both SQL variants filter {@code issotrx = 'Y'} so only sales invoices
   * (not purchase invoices) appear in the recent-invoices widget.
   */
  @Test
  void recentInvoicesBothSqlsFilterSalesInvoicesOnly() {
    WidgetQueryPolicyRegistry.WidgetQueryPolicy policy = WidgetQueryPolicyRegistry.recentInvoices();

    assertTrue(policy.fallbackSql.contains("issotrx = 'Y'"),
        "fallbackSql must filter issotrx = 'Y' (sales invoices only)");
    assertTrue(policy.rangedSql.contains("issotrx = 'Y'"), "rangedSql must filter issotrx = 'Y' (sales invoices only)");
  }

  /**
   * Verifies that both SQL variants restrict {@code docstatus IN ('CO','CL')} so draft
   * or voided invoices are never surfaced in the widget.
   */
  @Test
  void recentInvoicesBothSqlsFilterCompletedOrClosedStatus() {
    WidgetQueryPolicyRegistry.WidgetQueryPolicy policy = WidgetQueryPolicyRegistry.recentInvoices();

    assertTrue(policy.fallbackSql.contains("docstatus IN ('CO','CL')"),
        "fallbackSql must filter docstatus IN ('CO','CL')");
    assertTrue(policy.rangedSql.contains("docstatus IN ('CO','CL')"), "rangedSql must filter docstatus IN ('CO','CL')");
  }
}
