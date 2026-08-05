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
package com.etendoerp.go.onboarding;

import java.util.Map;
import java.util.Set;

/**
 * Defines which sampledata tables are safe to reuse for onboarding and which bootstrap fields must
 * be stripped before importing the curated XML into a new tenant.
 */
public final class OnboardingDatasetDefinition {

  private static final Set<String> EXCLUDED_TABLES = Set.of(
      "AD_CLIENT",
      "AD_CLIENTINFO",
      "AD_CLIENTMODULE",
      "AD_IMAGE",
      "AD_ORG",
      "AD_ORGINFO",
      "AD_ORGMODULE",
      "AD_PREFERENCE",
      "AD_PROCESS_REQUEST",
      "AD_REF_DATA_LOADED",
      "AD_ROLE",
      "AD_ROLE_ORGACCESS",
      "AD_TREE",
      "AD_TREENODE",
      "AD_USER",
      "AD_USER_ROLES",
      "ETGO_ACCOUNT",
      "FACT_ACCT"
  );

  private static final Set<String> INCLUDED_TABLES = Set.of(
      "AD_ORG_ACCTSCHEMA",
      "AD_ORG_WAREHOUSE",
      "AD_SEQUENCE",
      "A_ASSET_GROUP",
      "A_ASSET_GROUP_ACCT",
      "C_ACCTSCHEMA",
      "C_ACCTSCHEMA_DEFAULT",
      "C_ACCTSCHEMA_ELEMENT",
      "C_ACCTSCHEMA_GL",
      "C_ACCTSCHEMA_TABLE",
      "C_BP_GROUP",
      "C_BP_TAXCATEGORY",
      "C_CALENDAR",
      "C_DOCTYPE",
      "C_ELEMENT",
      "C_ELEMENTVALUE",
      "C_ELEMENTVALUE_TRL",
      "C_LOCATION",
      "C_PAYMENTTERM",
      "C_PERIOD",
      "C_PERIODCONTROL",
      // NOTE: C_TAX and C_TAXCATEGORY are intentionally NOT imported per-client. They are now
      // provisioned at system level (ad_client_id = '0') and shared by every tenant; imported
      // products and the client-level C_BP_TAXCATEGORY reference those system rows directly. The
      // per-client posting accounts (C_TAX_ACCT) ARE still client-level and are created by
      // OnboardingAccountingWiringService against the system taxes.
      "C_VALIDCOMBINATION",
      "C_YEAR",
      "ETGO_TRANSACTION_TYPE",
      "FIN_FINACC_PAYMENTMETHOD",
      "FIN_FINANCIAL_ACCOUNT",
      "FIN_MATCHING_ALGORITHM",
      "FIN_PAYMENTMETHOD",
      "GL_CATEGORY",
      "M_COSTING_RULE",
      "M_DISCOUNTSCHEMA",
      "M_LOCATOR",
      "M_PRICELIST",
      "M_PRICELIST_VERSION",
      "M_PRODUCT",
      "M_PRODUCTPRICE",
      "M_PRODUCT_CATEGORY",
      "M_WAREHOUSE"
  );

  private static final Set<String> STRIPPED_FIELDS = Set.of(
      "CREATED",
      "CREATEDBY",
      "UPDATED",
      "UPDATEDBY",
      "SALESREP_ID"
  );

  /**
   * Columns stripped only for specific tables. {@code C_ELEMENT.AD_TREE_ID} points to GOClient's
   * own account-element tree; importing it would carry a cross-tenant reference. It is stripped here
   * and re-pointed to the new tenant's own EV tree by {@link OnboardingAccountingWiringService}.
   */
  private static final Map<String, Set<String>> STRIPPED_FIELDS_BY_TABLE = Map.of(
      "C_ELEMENT", Set.of("AD_TREE_ID")
  );

  private OnboardingDatasetDefinition() {
  }

  public static Set<String> getExcludedTables() {
    return EXCLUDED_TABLES;
  }

  public static Set<String> getIncludedTables() {
    return INCLUDED_TABLES;
  }

  public static Set<String> getStrippedFields() {
    return STRIPPED_FIELDS;
  }

  /**
   * Returns whether a column must be stripped from a given table during normalization.
   * Combines the global {@link #STRIPPED_FIELDS} with per-table overrides.
   *
   * @param tableName  the sourcedata table name (case-insensitive)
   * @param columnName the column name
   * @return {@code true} when the column should be omitted from the normalized dataset
   */
  public static boolean isStrippedColumn(String tableName, String columnName) {
    if (STRIPPED_FIELDS.contains(columnName)) {
      return true;
    }
    if (tableName == null) {
      return false;
    }
    Set<String> perTable = STRIPPED_FIELDS_BY_TABLE.get(tableName.toUpperCase());
    return perTable != null && perTable.contains(columnName);
  }

  /**
   * Returns whether a sourcedata table is part of the curated onboarding dataset.
   *
   * @param tableName the database table name represented by the sourcedata file
   * @return {@code true} when the table should be normalized into the onboarding dataset
   */
  public static boolean shouldIncludeTable(String tableName) {
    return tableName != null
        && INCLUDED_TABLES.contains(tableName)
        && !EXCLUDED_TABLES.contains(tableName);
  }
}
