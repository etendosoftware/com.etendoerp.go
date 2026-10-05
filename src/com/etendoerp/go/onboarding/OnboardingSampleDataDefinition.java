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
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * ETP-5426 — which GOClient sourcedata rows make up the optional "sample data" a new tenant can be
 * born with, and how they differ from the GOClient originals.
 *
 * <p><b>Same source, second pass.</b> The sample data is read from the very files the base
 * onboarding dataset and {@code install.source} already read ({@code referencedata/sampledata/
 * GOClient}). Nothing is copied and nothing is deleted from those files — ETP-5079 showed what
 * deleting from them does to {@code install.source}. Instead, {@link OnboardingDatasetNormalizer}
 * runs a second pass with {@link OnboardingDatasetProfile#SAMPLE_DATA}, which takes:
 *
 * <ul>
 *   <li>{@link #TRANSACTIONAL_TABLES} whole — every row of those tables is sample data;</li>
 *   <li>from the {@link OnboardingDemoMasterData} tables, ONLY the demo rows. Those are the exact
 *       rows the base pass drops; the rest of each file (the internal {@code ETGO_DTO} product, the
 *       primary warehouse, …) already reached the tenant through the base pass.</li>
 * </ul>
 *
 * <p><b>It is imported after the base pass has committed</b>, by {@link OnboardingSampleDataService}.
 * Every reference from a sample row to a base row (document types, price list, calendar, chart of
 * accounts, …) carries the GOClient id and resolves through the {@code AD_REF_DATA_LOADED} mapping
 * the base import recorded for the tenant.
 *
 * <p><b>Deliberate differences from GOClient</b>, which is what a tenant sees instead:
 *
 * <ul>
 *   <li>Documents are imported <b>unposted</b>: {@code FACT_ACCT} is not imported and
 *       {@code POSTED} is rewritten to {@code 'N'} (see {@link #rewriteValue}); {@code 'D'} (posting
 *       disabled) is kept. The system-wide {@code AcctServerProcess} then posts them on its next
 *       cycle with the tenant's own accounts — accepted as the intended outcome.</li>
 *   <li>{@code AD_USER_ID} is stripped from the documents and assets: it points at GOAdmin/GOuser,
 *       which do not exist in a tenant ({@code AD_USER} is never imported).</li>
 *   <li>Partners GOClient ships without an identifier get the next numbers of the tenant's own
 *       identifier sequence, as if a user had created them (see {@link #PENDING_IDENTIFIER}).</li>
 *   <li>Every row goes to the tenant's business organization, including GOClient's client-level
 *       ({@code '0'}) partners, products, warehouse and locator — see
 *       {@link OnboardingDatasetProfile#keepsClientLevelOrganization()}.</li>
 *   <li>Per-entity posting accounts ({@code C_BP_CUSTOMER_ACCT}, {@code M_PRODUCT_ACCT},
 *       {@code FIN_FINANCIAL_ACCOUNT_ACCT}, …) are NOT taken from GOClient — they are provisioned
 *       by {@link OnboardingAccountingWiringService}, exactly as for the base master data, so they
 *       follow the tenant's own defaults (and ETP-5207's financial-account divergence).
 *       {@code A_ASSET_ACCT} is the exception: the wiring service has no statement for assets.</li>
 * </ul>
 */
final class OnboardingSampleDataDefinition {

  static final String BUSINESS_PARTNER_TABLE = "C_BPARTNER";
  static final String IDENTIFIER_COLUMN = "EM_ETGO_IDENTIFIER";

  /** Tables whose every row is sample data. */
  static final Set<String> TRANSACTIONAL_TABLES = Set.of(
      "A_AMORTIZATION",
      "A_AMORTIZATIONLINE",
      "A_ASSET",
      "A_ASSET_ACCT",
      BUSINESS_PARTNER_TABLE,
      "C_BPARTNER_LOCATION",
      "C_INVOICE",
      "C_INVOICELINE",
      "C_INVOICELINETAX",
      "C_INVOICETAX",
      "C_ORDER",
      "C_ORDERLINE",
      "C_ORDERLINETAX",
      "C_ORDERTAX",
      "FIN_FINACC_TRANSACTION",
      "FIN_PAYMENT",
      "FIN_PAYMENT_DETAIL",
      "FIN_PAYMENT_SCHEDULE",
      "FIN_PAYMENT_SCHEDULEDETAIL",
      "M_COSTING",
      "M_INOUT",
      "M_INOUTLINE",
      "M_INVENTORY",
      "M_INVENTORYLINE",
      "M_MATCHINV",
      "M_MATCHPO",
      "M_MOVEMENT",
      "M_MOVEMENTLINE",
      "M_STORAGE_DETAIL",
      "M_STORAGE_PENDING",
      "M_TRANSACTION",
      "M_TRANSACTION_COST"
  );

  /**
   * Tables shared with the base pass, from which only the {@link OnboardingDemoMasterData} rows are
   * taken. Must name exactly the tables the base pass's demo master-data filter matches.
   */
  static final Set<String> DEMO_MASTER_DATA_TABLES = Set.of(
      "AD_ORG_WAREHOUSE",
      "FIN_FINACC_PAYMENTMETHOD",
      "FIN_FINANCIAL_ACCOUNT",
      "M_LOCATOR",
      "M_PRODUCT",
      "M_PRODUCTPRICE",
      "M_PRODUCT_CATEGORY",
      "M_PRODUCT_CATEGORY_TRL",
      "M_WAREHOUSE"
  );

  /** Every table the sample-data pass reads. */
  static final Set<String> TABLES = Stream.of(TRANSACTIONAL_TABLES, DEMO_MASTER_DATA_TABLES)
      .flatMap(Set::stream)
      .collect(Collectors.toUnmodifiableSet());

  private static final String AD_USER_ID_COLUMN = "AD_USER_ID";

  /** Columns stripped per table, on top of the columns the base pass always strips. */
  private static final Map<String, Set<String>> STRIPPED_COLUMNS_BY_TABLE = Map.of(
      "A_ASSET", Set.of(AD_USER_ID_COLUMN),
      "C_INVOICE", Set.of(AD_USER_ID_COLUMN),
      "C_ORDER", Set.of(AD_USER_ID_COLUMN),
      "M_INOUT", Set.of(AD_USER_ID_COLUMN)
  );


  /**
   * Stand-in identifier given to every sample partner GOClient ships without one, replaced by a real
   * number from the tenant's own sequence right after the import (see
   * {@link OnboardingSampleDataService}). Needed because {@code EM_Etgo_Identifier} is filled by a
   * transactional sequence generator at insert time, and inside one import flush that generator
   * handed the same number to every partner. A non-blank value that does not start with
   * {@code "<"} makes the generator keep it as is. Fits the column (32 characters).
   */
  static final String PENDING_IDENTIFIER = "ETGO_SAMPLE_DATA_PENDING";

  static final String POSTED_COLUMN = "POSTED";
  static final String UNPOSTED = "N";
  static final String POSTING_DISABLED = "D";

  /**
   * The GOClient business partners. A tenant whose {@code AD_REF_DATA_LOADED} maps any of them has
   * already received the sample data: the mapping is written by the import itself and survives the
   * user deleting the partner afterwards, so it is a reliable "already imported" marker.
   */
  static final Set<String> PRESENCE_MARKER_BUSINESS_PARTNER_IDS = Set.of(
      "0ABDA2D3D6C249598F3564C566B8C511",
      "203884E383AB4B5AAF3FA05EF8E9BE46",
      "6BD084B9C1744044B9691AD373F96A93",
      "8EB9853B2C144E9BBF4F7AC1679AFEDD",
      "BC8DDDF69DDA49E9938729F19B0F330E"
  );

  /** Only Spanish tenants invoicing in euros can receive the sample data (its taxes and ids are Spanish). */
  static final String SUPPORTED_COUNTRY_CODE = "ES";
  static final String SUPPORTED_CURRENCY_ISO = "EUR";

  private OnboardingSampleDataDefinition() {
  }

  static boolean includesTable(String tableName) {
    return tableName != null && TABLES.contains(tableName.toUpperCase());
  }

  static boolean isDemoMasterDataTable(String tableName) {
    return tableName != null && DEMO_MASTER_DATA_TABLES.contains(tableName.toUpperCase());
  }

  static boolean isStrippedColumn(String tableName, String columnName) {
    if (tableName == null) {
      return false;
    }
    Set<String> perTable = STRIPPED_COLUMNS_BY_TABLE.get(tableName.toUpperCase());
    return perTable != null && perTable.contains(columnName);
  }

  /**
   * Returns the value a sample row carries into the tenant for a column: {@code POSTED} becomes
   * {@code 'N'} unless posting is disabled for the document ({@code 'D'}); every other value is
   * unchanged.
   *
   * @param columnName the sourcedata column name
   * @param rawValue   the trimmed, non-empty sourcedata value
   * @return the value to import
   */
  static String rewriteValue(String columnName, String rawValue) {
    if (POSTED_COLUMN.equals(columnName) && !POSTING_DISABLED.equals(rawValue)) {
      return UNPOSTED;
    }
    return rawValue;
  }

  /**
   * Returns the columns to add to a sample row that the source leaves empty: a partner without an
   * {@code EM_ETGO_IDENTIFIER} gets {@link #PENDING_IDENTIFIER}. Every other row gets nothing.
   *
   * @param tableName  the sourcedata table name
   * @param rawColumns the row's raw sourcedata columns, keys upper-cased
   * @return the column values to add, keyed by upper-cased column name
   */
  static Map<String, String> addedColumns(String tableName, Map<String, String> rawColumns) {
    if (!BUSINESS_PARTNER_TABLE.equalsIgnoreCase(tableName)) {
      return Map.of();
    }
    String identifier = rawColumns.get(IDENTIFIER_COLUMN);
    return identifier == null || identifier.isBlank()
        ? Map.of(IDENTIFIER_COLUMN, PENDING_IDENTIFIER)
        : Map.of();
  }

  /**
   * Whether an onboarding request may receive the sample data: the user asked for it, it is a demo
   * (not a paid) environment, and the tenant is Spanish and invoices in euros.
   *
   * @param requested    the signup form's opt-in
   * @param paidUpgrade  whether the request provisions a paid environment
   * @param countryCode  the requested country (ISO 3166-1 alpha-2)
   * @param currencyIso  the requested currency (ISO 4217)
   * @return {@code true} when the sample data must be imported
   */
  static boolean isEligible(boolean requested, boolean paidUpgrade, String countryCode,
      String currencyIso) {
    return requested
        && !paidUpgrade
        && SUPPORTED_COUNTRY_CODE.equalsIgnoreCase(countryCode)
        && SUPPORTED_CURRENCY_ISO.equalsIgnoreCase(currencyIso);
  }
}
