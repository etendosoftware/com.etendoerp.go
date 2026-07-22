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
  // bestProducts()/bestSellers() rangedSql — regression tests for ETP-4521
  // The old rangedSql ignored the ?range= param for the trend comparison:
  // curr_period/prev_period were hardcoded to "date_trunc('month', NOW())"
  // and a MAX(dateinvoiced) subselect, so the trend % never respected the
  // selected date range. These tests verify the replacement uses the
  // parameterized period placeholders (%1$s/%2$s/%3$s) instead.
  // -----------------------------------------------------------------

  /**
   * Verifies that the best-products rangedSql no longer hardcodes the current-month
   * boundary and no longer uses the prev-month MAX(dateinvoiced) subselect, using the
   * parameterized period placeholders instead.
   */
  @Test
  void bestProductsRangedSqlUsesParameterizedPeriodsNotHardcodedMonth() {
    WidgetQueryPolicyRegistry.WidgetQueryPolicy policy = WidgetQueryPolicyRegistry.bestProducts();

    assertFalse(policy.rangedSql.contains("date_trunc('month', NOW())"),
        "rangedSql must not hardcode the current-month boundary (regression guard)");
    assertFalse(policy.rangedSql.contains("date_trunc('month', dateinvoiced) <"),
        "rangedSql must not use the old prev-month MAX(dateinvoiced) subselect (regression guard)");
    assertTrue(policy.rangedSql.contains(">= %2$s"),
        "rangedSql must filter the previous period's lower bound via %2$s");
    assertTrue(policy.rangedSql.contains("< %3$s"),
        "rangedSql must filter the previous period's exclusive upper bound via %3$s");
  }

  /**
   * Verifies that the best-sellers rangedSql no longer hardcodes the current-month
   * boundary and no longer uses the prev-month MAX(dateinvoiced) subselect, using the
   * parameterized period placeholders instead.
   */
  @Test
  void bestSellersRangedSqlUsesParameterizedPeriodsNotHardcodedMonth() {
    WidgetQueryPolicyRegistry.WidgetQueryPolicy policy = WidgetQueryPolicyRegistry.bestSellers();

    assertFalse(policy.rangedSql.contains("date_trunc('month', NOW())"),
        "rangedSql must not hardcode the current-month boundary (regression guard)");
    assertFalse(policy.rangedSql.contains("date_trunc('month', dateinvoiced) <"),
        "rangedSql must not use the old prev-month MAX(dateinvoiced) subselect (regression guard)");
    assertTrue(policy.rangedSql.contains(">= %2$s"),
        "rangedSql must filter the previous period's lower bound via %2$s");
    assertTrue(policy.rangedSql.contains("< %3$s"),
        "rangedSql must filter the previous period's exclusive upper bound via %3$s");
  }

  /**
   * Regression guard for the future-dated-invoice leak: verifies that the best-products
   * rangedSql bounds the CURRENT period with an upper limit ({@code i.dateinvoiced <= NOW()})
   * so invoices dated in the future do not inflate the current-period figures.
   */
  @Test
  void bestProductsRangedSqlBoundsCurrentPeriodUpperLimitToNow() {
    WidgetQueryPolicyRegistry.WidgetQueryPolicy policy = WidgetQueryPolicyRegistry.bestProducts();

    assertTrue(policy.rangedSql.contains("i.dateinvoiced <= NOW()"),
        "rangedSql must bound the current period with i.dateinvoiced <= NOW() (future-date leak guard)");
    assertFalse(policy.rangedSql.contains("date_trunc('month', NOW())"),
        "rangedSql must not hardcode the current-month boundary (regression guard)");
    assertTrue(policy.rangedSql.contains(">= %2$s"),
        "rangedSql must filter the previous period's lower bound via %2$s");
    assertTrue(policy.rangedSql.contains("< %3$s"),
        "rangedSql must filter the previous period's exclusive upper bound via %3$s");
  }

  /**
   * Regression guard for the future-dated-invoice leak: verifies that the best-sellers
   * rangedSql bounds the CURRENT period with an upper limit ({@code i.dateinvoiced <= NOW()})
   * so invoices dated in the future do not inflate the current-period figures.
   */
  @Test
  void bestSellersRangedSqlBoundsCurrentPeriodUpperLimitToNow() {
    WidgetQueryPolicyRegistry.WidgetQueryPolicy policy = WidgetQueryPolicyRegistry.bestSellers();

    assertTrue(policy.rangedSql.contains("i.dateinvoiced <= NOW()"),
        "rangedSql must bound the current period with i.dateinvoiced <= NOW() (future-date leak guard)");
    assertFalse(policy.rangedSql.contains("date_trunc('month', NOW())"),
        "rangedSql must not hardcode the current-month boundary (regression guard)");
    assertTrue(policy.rangedSql.contains(">= %2$s"),
        "rangedSql must filter the previous period's lower bound via %2$s");
    assertTrue(policy.rangedSql.contains("< %3$s"),
        "rangedSql must filter the previous period's exclusive upper bound via %3$s");
  }

  /**
   * Verifies that the best-products fallbackSql (no range param) is unaffected by the fix
   * and still uses the current-month/MAX(dateinvoiced) comparison logic.
   */
  @Test
  void bestProductsFallbackSqlStillUsesMonthAndMaxDateLogic() {
    WidgetQueryPolicyRegistry.WidgetQueryPolicy policy = WidgetQueryPolicyRegistry.bestProducts();

    assertTrue(policy.fallbackSql.contains("date_trunc('month', i.dateinvoiced) = ("),
        "fallbackSql must keep comparing against the latest invoiced month via MAX(dateinvoiced)");
    assertTrue(policy.fallbackSql.contains("date_trunc('month', dateinvoiced) <"),
        "fallbackSql must keep the prev-month MAX(dateinvoiced) subselect");
  }

  /**
   * Verifies that the best-sellers fallbackSql (no range param) is unaffected by the fix
   * and still uses the current-month/MAX(dateinvoiced) comparison logic.
   */
  @Test
  void bestSellersFallbackSqlStillUsesMonthAndMaxDateLogic() {
    WidgetQueryPolicyRegistry.WidgetQueryPolicy policy = WidgetQueryPolicyRegistry.bestSellers();

    assertTrue(policy.fallbackSql.contains("date_trunc('month', i.dateinvoiced) = ("),
        "fallbackSql must keep comparing against the latest invoiced month via MAX(dateinvoiced)");
    assertTrue(policy.fallbackSql.contains("date_trunc('month', dateinvoiced) <"),
        "fallbackSql must keep the prev-month MAX(dateinvoiced) subselect");
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
   * Verifies that rangedSql includes {@code LIMIT 5} to match the five rows rendered
   * by {@code RecentSalesList} and avoid fetching unnecessary records.
   */
  @Test
  void recentInvoicesRangedSqlLimitsResultsTo5() {
    WidgetQueryPolicyRegistry.WidgetQueryPolicy policy = WidgetQueryPolicyRegistry.recentInvoices();

    assertTrue(policy.rangedSql.contains("LIMIT 5"), "rangedSql must include LIMIT 5 to match the UI row count");
  }

  /**
   * Verifies that fallbackSql also includes {@code LIMIT 5} so the no-range path
   * caps its result set consistently with the ranged variant.
   */
  @Test
  void recentInvoicesFallbackSqlLimitsResultsTo5() {
    WidgetQueryPolicyRegistry.WidgetQueryPolicy policy = WidgetQueryPolicyRegistry.recentInvoices();

    assertTrue(policy.fallbackSql.contains("LIMIT 5"), "fallbackSql must include LIMIT 5 to match the UI row count");
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
