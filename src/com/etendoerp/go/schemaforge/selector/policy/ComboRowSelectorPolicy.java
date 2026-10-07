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

import java.util.Arrays;
import java.util.Set;

import org.openbravo.dal.core.OBContext;
import org.openbravo.data.FieldProvider;

import com.etendoerp.go.schemaforge.SystemCategoryIds;
import com.etendoerp.go.supportaccess.SupportUserExclusion;

/**
 * ETP-4967: post-filters rows returned by {@code ComboReferenceSelectorExecutor}'s classic
 * {@code ComboTableData} path (used for columns whose FK reference carries a SQL
 * {@code AD_Val_Rule}, like {@code M_Product.M_Product_Category_ID}) to exclude rows flagged
 * hidden — {@code EM_Etgo_IsSystemCategory = 'Y'} on {@code M_Product_Category}, e.g. "Discounts".
 *
 * <p>{@link ProductCategorySystemFlagSelectorPolicy} covers the same exclusion for plain
 * TableDir/Table FK selectors that go through {@code NeoSelectorService}'s HQL path — this class
 * exists because the classic-combo path is a SEPARATE code path that never reaches that policy
 * (it defers entirely to core's {@code ComboTableData}, driven by the column's own
 * {@code AD_Val_Rule} SQL). {@code M_Product_Category_ID} is owned by the Core module and cannot
 * be repointed to a new validation rule without a template (AD_Message 20532) — this filters the
 * combo's own SQL output instead of touching that rule.
 *
 * <p>Keyed by physical DB column name rather than target entity: a SQL-validation-rule combo has
 * no fixed target table the way a TableDir/Table reference does (the target lives inside the
 * validation rule's own SQL), so column name is the only identifier {@code buildResponse} has
 * that is stable enough to hook on.
 *
 * <p>Applied to {@code rawRows} before pagination bookkeeping ({@code hasMore}/{@code totalCount})
 * is computed, so those numbers reflect the filtered set — accepted limitation: because the
 * underlying SQL page is already fixed by {@code ComboTableData.select}'s own
 * {@code offset}/{@code limit}, a page that happens to include a hidden category can come back
 * short of {@code limit} visible rows instead of being backfilled from the next page. Harmless in
 * practice for product categories (a handful of rows per tenant).
 */
public final class ComboRowSelectorPolicy {

  private static final String COLUMN_PRODUCT_CATEGORY_FK = "M_Product_Category_ID";
  private static final String FIELD_ID = "ID";

  private ComboRowSelectorPolicy() {
  }

  /**
   * Filters {@code rawRows} for the given column when a hiding policy applies to it; returns
   * {@code rawRows} unchanged otherwise (including when it is {@code null}/empty).
   *
   * @param columnName physical DB column name the combo selector was built for (e.g.
   *        {@code "M_Product_Category_ID"}); only {@link #COLUMN_PRODUCT_CATEGORY_FK} triggers
   *        filtering, matched case-insensitively
   * @param rawRows the combo's own raw result rows, or {@code null}/empty
   * @return {@code rawRows} with hidden rows removed, or {@code rawRows} itself unchanged when
   *         no hiding policy applies to {@code columnName} or nothing needs filtering
   */
  public static FieldProvider[] filter(String columnName, FieldProvider[] rawRows) {
    FieldProvider[] rows = withoutSupportUser(rawRows);
    if (rows == null || rows.length == 0
        || !COLUMN_PRODUCT_CATEGORY_FK.equalsIgnoreCase(columnName)) {
      return rows;
    }
    String clientId = OBContext.getOBContext().getCurrentClient().getId();
    Set<String> hiddenIds = SystemCategoryIds.resolve(clientId);
    if (hiddenIds.isEmpty()) {
      return rows;
    }
    return Arrays.stream(rows)
        .filter(row -> !hiddenIds.contains(row.getField(FIELD_ID)))
        .toArray(FieldProvider[]::new);
  }

  /**
   * ETP-5351 (T6) — drops the current client's "Soporte Etendo" user from a combo, whatever the
   * column. The combo's SQL comes from the column's validation rule, so this is the only place
   * the route can be filtered; the id is derived from the client and no other record of any table
   * carries it, so matching on it alone is safe for every column. Same short-page limitation as
   * the category filter above, at most one row per tenant.
   */
  private static FieldProvider[] withoutSupportUser(FieldProvider[] rawRows) {
    String supportUserId = rawRows == null || rawRows.length == 0 ? null
        : SupportUserExclusion.supportUserIdOrNull(SupportUserExclusion.currentClientId());
    if (supportUserId == null) {
      return rawRows;
    }
    return Arrays.stream(rawRows)
        .filter(row -> !supportUserId.equals(row.getField(FIELD_ID)))
        .toArray(FieldProvider[]::new);
  }
}
