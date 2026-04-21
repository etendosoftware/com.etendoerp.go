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
      "AD_ORG_WAREHOUSE",
      "AD_SEQUENCE",
      "C_BP_GROUP",
      "C_CALENDAR",
      "C_DOCTYPE",
      "C_LOCATION",
      "C_PAYMENTTERM",
      "C_PERIOD",
      "C_TAX",
      "C_TAXCATEGORY",
      "C_YEAR",
      "FIN_FINACC_PAYMENTMETHOD",
      "FIN_FINANCIAL_ACCOUNT",
      "FIN_MATCHING_ALGORITHM",
      "FIN_PAYMENTMETHOD",
      "GL_CATEGORY",
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
