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
  public static final String BACKEND_OCR_FIELD_ACCURACY =
      "backend_ocr_field_accuracy";
  public static final String BACKEND_BANK_MATCH_ATTEMPTED =
      "backend_bank_match_attempted";
  public static final String BACKEND_ASSET_CREATED =
      "backend_asset_created";
  public static final String BACKEND_EMAIL_INVOICE_INGESTED =
      "backend_email_invoice_ingested";
  public static final String BACKEND_MONTHLY_CLOSE_STARTED =
      "backend_monthly_close_started";
  public static final String BACKEND_MONTHLY_CLOSE_COMPLETED =
      "backend_monthly_close_completed";

  /**
   * Generic backend write-operation timing event emitted by NEO CRUD.
   */
  public static final String BACKEND_WRITE_OPERATION_COMPLETED =
      "backend_write_operation_completed";

  private NeoTelemetryEvents() {
  }
}
