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

import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;
import org.openbravo.model.ad.datamodel.Column;

/**
 * Registry of AD_Reference IDs whose selectors must include system-client ('0') records
 * alongside the current-client records.
 *
 * <p>By default, selectors backed by a SQL validation rule are routed through
 * {@link ComboReferenceSelectorExecutor} (core ComboTableData), which resolves
 * {@code @AD_CLIENT_ID@} to the current session client and therefore excludes
 * system-client ('0') data.
 *
 * <p>References registered here bypass that route and fall through to the standard
 * OBQuery path. The client equality predicate produced by the validation rule is
 * expanded from {@code e.client.id = 'X'} to {@code e.client.id IN ('X', '0')} so
 * system records pass all applicable business rules.
 *
 * <p>To add a new reference: append its {@code AD_Reference.id} to {@link #SYSTEM_CLIENT_REFS}.
 * No other changes are required.
 */
final class SystemClientSelectorRegistry {

  /**
   * AD_Reference IDs whose selectors include system-client records.
   * <ul>
   *   <li>{@code "158"} — C_Tax_ID (FinancialMgmtTaxRate): taxes are provisioned at
   *       client='0' and must be visible to all tenants.</li>
   * </ul>
   */
  private static final Set<String> SYSTEM_CLIENT_REFS = Set.of("158");

  /** Matches the HQL client equality predicate produced by SqlToHqlTranslator. */
  private static final Pattern CLIENT_EQUALITY = Pattern.compile(
      "e\\.client\\.id\\s*=\\s*'([^']+)'");

  private SystemClientSelectorRegistry() {
  }

  /**
   * Returns {@code true} when the given AD_Reference ID is registered for system-client inclusion.
   * Use {@link #isRegisteredColumn(Column)} when you have the AD_Column — it reads the correct
   * reference search key (AD_Reference_Value) rather than the base reference type (18/19/30).
   */
  static boolean isRegistered(String refId) {
    return refId != null && SYSTEM_CLIENT_REFS.contains(refId);
  }

  /**
   * Returns {@code true} when the column's base reference (AD_Reference_ID) or its
   * reference search key (AD_Reference_Value_ID) is registered for system-client inclusion.
   *
   * <p>Standard columns like {@code C_Tax_ID} carry the registered ID ("158") on the base
   * reference ({@code column.getReference()}). Extension columns may carry it on the search key
   * ({@code column.getReferenceSearchKey()}). Both are checked.
   */
  static boolean isRegisteredColumn(Column column) {
    if (column == null) {
      return false;
    }
    org.openbravo.model.ad.domain.Reference baseRef = column.getReference();
    org.openbravo.model.ad.domain.Reference refSearchKey = column.getReferenceSearchKey();
    return (baseRef != null && isRegistered(baseRef.getId())) ||
           (refSearchKey != null && isRegistered(refSearchKey.getId()));
  }

  /**
   * Expand any {@code e.client.id = 'X'} predicate in the given HQL filter to
   * {@code e.client.id IN ('X', '0')} so that system-client ('0') records are included.
   *
   * <p>No-op when {@code hqlFilter} is blank or contains no client equality predicate.
   */
  static String expandClientFilter(String hqlFilter) {
    if (StringUtils.isBlank(hqlFilter)) {
      return hqlFilter;
    }
    Matcher m = CLIENT_EQUALITY.matcher(hqlFilter);
    StringBuilder sb = new StringBuilder();
    while (m.find()) {
      String clientId = m.group(1);
      m.appendReplacement(sb, Matcher.quoteReplacement(
          "e.client.id IN ('" + clientId + "', '0')"));
    }
    m.appendTail(sb);
    return sb.toString();
  }
}
