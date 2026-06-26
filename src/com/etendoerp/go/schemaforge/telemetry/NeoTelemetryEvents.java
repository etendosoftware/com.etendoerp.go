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

package com.etendoerp.go.schemaforge.telemetry;

/**
 * Backend observability event names for NEO authoritative facts and timings.
 */
public final class NeoTelemetryEvents {

  public static final String BACKEND_ACCOUNTING_ENTRY_GENERATED =
      "backend_accounting_entry_generated";
  public static final String BACKEND_ACCOUNTING_ENTRY_CREATED =
      "backend_accounting_entry_created";
  public static final String BACKEND_ACCOUNTING_ENTRY_VALIDATED =
      "backend_accounting_entry_validated";
  public static final String BACKEND_ACCOUNTING_LINK_VALIDATED =
      "backend_accounting_link_validated";
  public static final String BACKEND_ACCEPTANCE_INTEGRITY_CHECK_COMPLETED =
      "backend_acceptance_integrity_check_completed";
  public static final String BACKEND_OCR_FIELD_ACCURACY =
      "backend_ocr_field_accuracy";
  public static final String BACKEND_OCR_EXTRACTION_EVALUATED =
      "backend_ocr_extraction_evaluated";
  public static final String BACKEND_BANK_MATCH_ATTEMPTED =
      "backend_bank_match_attempted";
  public static final String BACKEND_BANK_RECONCILIATION_MATCH_EVALUATED =
      "backend_bank_reconciliation_match_evaluated";
  public static final String BACKEND_ASSET_CREATED =
      "backend_asset_created";
  public static final String BACKEND_DEPRECIATION_CALCULATION_VALIDATED =
      "backend_depreciation_calculation_validated";
  public static final String BACKEND_EMAIL_INVOICE_INGESTED =
      "backend_email_invoice_ingested";
  public static final String BACKEND_INVOICE_INGESTION_COMPLETED =
      "backend_invoice_ingestion_completed";
  public static final String BACKEND_MASTER_DATA_QUALITY_EVALUATED =
      "backend_master_data_quality_evaluated";
  public static final String BACKEND_MONTHLY_CLOSE_STARTED =
      "backend_monthly_close_started";
  public static final String BACKEND_MONTHLY_CLOSE_COMPLETED =
      "backend_monthly_close_completed";
  public static final String BACKEND_ROLE_ASSIGNMENT_VALIDATED =
      "backend_role_assignment_validated";
  public static final String BACKEND_STOCK_COUNT_RECONCILED =
      "backend_stock_count_reconciled";
  public static final String BACKEND_STOCK_MOVEMENT_VALIDATED =
      "backend_stock_movement_validated";

  /**
   * Generic backend write-operation timing event emitted by NEO CRUD.
   */
  public static final String BACKEND_WRITE_OPERATION_COMPLETED =
      "backend_write_operation_completed";

  private NeoTelemetryEvents() {
  }
}
