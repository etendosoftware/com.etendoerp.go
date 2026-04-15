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

import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.enterprise.Organization;

/**
 * Shared utility for multi-currency amount conversion in widget SQL queries.
 *
 * <p>Dashboard KPI aggregates must always reflect amounts in the org's functional
 * currency so they are mathematically summable. This helper provides the SQL
 * expression and the org currency resolver needed by all aggregating widget handlers.</p>
 *
 * <h3>Conversion logic (per invoice row)</h3>
 * <ol>
 *   <li>Invoice currency = org currency → multiply by 1.0 (same currency, no lookup)</li>
 *   <li>Invoice currency ≠ org currency, rate found in {@code C_Conversion_Rate} → apply rate</li>
 *   <li>Invoice currency ≠ org currency, no rate → COALESCE returns NULL → contributes 0 to SUM
 *       (conservative: avoids wrong totals from missing rates)</li>
 * </ol>
 *
 * <h3>Usage in handlers</h3>
 * <pre>
 * String orgCurrencyId = NeoConversionHelper.resolveOrgCurrencyId();
 * String amountExpr = (orgCurrencyId != null)
 *     ? NeoConversionHelper.CONVERTED_GRANDTOTAL
 *     : "i.grandtotal";
 * String sql = SQL_TEMPLATE.replace("{AMOUNT}", amountExpr);
 * // ... create query ...
 * if (orgCurrencyId != null) {
 *     query.setParameter("orgCurrencyId", orgCurrencyId);
 * }
 * </pre>
 *
 * The placeholder {@code {AMOUNT}} must appear in the SQL template wherever
 * {@code i.grandtotal} was previously used. Assumes invoice alias {@code i}
 * with columns {@code grandtotal}, {@code c_currency_id}, {@code dateinvoiced},
 * {@code ad_client_id}.
 */
public class NeoConversionHelper {

  /** Named parameter required when using {@link #CONVERTED_GRANDTOTAL}. */
  public static final String PARAM_ORG_CURRENCY_ID = "orgCurrencyId";

  /**
   * Placeholder used in SQL templates to mark where the amount expression goes.
   * Replace with {@link #CONVERTED_GRANDTOTAL} (with conversion) or {@code "i.grandtotal"}
   * (without) depending on whether {@link #resolveOrgCurrencyId()} returned a value.
   */
  public static final String AMOUNT_PLACEHOLDER = "{AMOUNT}";

  /**
   * SQL expression that converts {@code i.grandtotal} to the org's functional currency
   * using {@code C_Conversion_Rate}. Requires named parameter {@code :orgCurrencyId}.
   *
   * <p>Assumes invoice alias {@code i} with columns:
   * {@code grandtotal}, {@code c_currency_id}, {@code dateinvoiced}, {@code ad_client_id}.</p>
   */
  public static final String CONVERTED_GRANDTOTAL =
      "i.grandtotal * COALESCE("
    + "(SELECT cr.multiplyrate FROM c_conversion_rate cr"
    + " WHERE cr.c_currency_id = i.c_currency_id"
    + " AND cr.c_currency_id_to = :orgCurrencyId"
    + " AND cr.ad_client_id = i.ad_client_id"
    + " AND cr.isactive = 'Y'"
    + " AND cr.validfrom <= i.dateinvoiced"
    + " AND (cr.validto IS NULL OR cr.validto >= i.dateinvoiced)"
    + " ORDER BY cr.validfrom DESC LIMIT 1),"
    + "CASE WHEN i.c_currency_id = :orgCurrencyId THEN 1.0 ELSE NULL END)";

  private NeoConversionHelper() {
    // utility class — no instances
  }

  /**
   * Resolves the functional currency ID for the current organization.
   *
   * <p>Must be called inside an {@code OBContext.setAdminMode} block.
   * Returns {@code null} if the organization has no currency configured
   * (in which case callers should skip conversion and use {@code i.grandtotal} directly).</p>
   *
   * @return the {@code C_Currency_ID} of {@code AD_Org.C_Currency_ID}, or {@code null}
   */
  public static String resolveOrgCurrencyId() {
    Organization org = OBDal.getInstance().get(Organization.class,
        OBContext.getOBContext().getCurrentOrganization().getId());
    return (org != null && org.getCurrency() != null) ? org.getCurrency().getId() : null;
  }

  /**
   * Builds the final SQL by substituting the correct amount expression into the template.
   *
   * @param template    SQL string containing {@value #AMOUNT_PLACEHOLDER} placeholders
   * @param orgCurrencyId the org's currency ID (from {@link #resolveOrgCurrencyId()}),
   *                      or {@code null} to use raw {@code i.grandtotal}
   * @return SQL with all {@value #AMOUNT_PLACEHOLDER} occurrences replaced
   */
  public static String buildSql(String template, String orgCurrencyId) {
    String expr = (orgCurrencyId != null) ? CONVERTED_GRANDTOTAL : "i.grandtotal";
    return template.replace(AMOUNT_PLACEHOLDER, expr);
  }
}
