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
   * One MCP tool call, emitted by {@code McpUsageLogger} (Track B2).
   * <p>
   * A projection of the {@code ETGO_MCP_USAGE} row, never a replacement for it: D24 keeps the table
   * authoritative precisely because this road can drop, cap or sample. Shape only — tool, verb,
   * target entity, field NAMES, outcome, error code, latency, byte counts. Never argument values,
   * never record identifiers, and never a feedback row's payload.
   */
  public static final String BACKEND_MCP_TOOL_CALL_COMPLETED =
      "backend_mcp_tool_call_completed";

  /**
   * ETP-5791 — a contact ({@code C_BPartner}) create was committed, on any NEO channel (REST,
   * MCP, REST/MCP batch). Emitted by {@code ContactTypeTelemetry}; properties {@code account_id},
   * {@code is_customer}, {@code is_vendor}, {@code types_source}, {@code channel},
   * {@code flag_enabled}. Never a record identifier.
   */
  public static final String BACKEND_CONTACT_CREATED = "backend_contact_created";

  /**
   * ETP-5791 — an existing contact's {@code IsCustomer}/{@code IsVendor} actually changed and the
   * write was committed. Emitted by {@code ContactTypeTelemetry}; properties {@code account_id},
   * {@code previous_is_customer}, {@code previous_is_vendor}, {@code is_customer},
   * {@code is_vendor}, {@code days_since_created}, {@code change_trigger}, {@code change_origin},
   * {@code channel}, {@code flag_enabled}. Never a record identifier.
   */
  public static final String BACKEND_CONTACT_TYPE_CHANGED = "backend_contact_type_changed";

  /**
   * ETP-5581 — one decision on one suggested bounce pair: applied (discarded or failed), left
   * unchecked or sent to the panel in the {@code applySuggestions} modal, or discarded / dismissed
   * ("No es un rebote") in the reconciliation panel. Emitted by {@code BankBounceKpiTelemetry},
   * flag {@code bank-bounce-suggestions} on only. Never a record identifier.
   */
  public static final String BACKEND_BANK_BOUNCE_PAIR_DECIDED =
      "backend_bank_bounce_pair_decided";

  /**
   * ETP-5581 — a pair discarded as a bounce pair was restored by {@code restorePair}. Emitted by
   * {@code BankBounceKpiTelemetry}; carries the stored signal and confidence and the whole days
   * since the discard. Never a record identifier.
   */
  public static final String BACKEND_BANK_BOUNCE_PAIR_RESTORED =
      "backend_bank_bounce_pair_restored";

  /**
   * ETP-5581 — summary of one {@code applySuggestions} call that included bounce pairs: how many
   * were shown, discarded, unchecked and sent to the panel, and whether the user changed the
   * preselection. Emitted by {@code BankBounceKpiTelemetry}.
   */
  public static final String BACKEND_BANK_BOUNCE_SUGGESTIONS_APPLIED =
      "backend_bank_bounce_suggestions_applied";

  /**
   * ETP-6230 — a productive tenant finished onboarding (emitted after the onboarding commit).
   * Emitted by {@code AccountingKpiTelemetry}; properties {@code account_id}, {@code internal}.
   */
  public static final String BACKEND_PRODUCTIVE_ENVIRONMENT_CREATED =
      "backend_productive_environment_created";

  /**
   * ETP-6230 — the owner reviewed the accounting calendar (Primeros pasos or a manual period
   * open). Once per tenant and kind. Properties {@code type}, {@code source}, {@code account_id},
   * {@code internal}.
   */
  public static final String BACKEND_CALENDAR_REVIEW_COMPLETED =
      "backend_calendar_review_completed";

  /**
   * ETP-6230 — the first period-controlled document of a review-required tenant was processed
   * (outcome probe). Once per tenant. Properties {@code operation}, {@code source},
   * {@code entity}, {@code specName}, {@code account_id}, {@code internal}.
   */
  public static final String BACKEND_FIRST_DOCUMENT_PROCESSED =
      "backend_first_document_processed";

  /**
   * ETP-6230 — a write was refused because its period is closed or missing. Every refusal.
   * Properties {@code entity}, {@code specName}, {@code operation}, {@code source},
   * {@code errorCode}, {@code account_id}, {@code internal}.
   */
  public static final String BACKEND_PERIOD_REFUSED = "backend_period_refused";

  /**
   * Generic backend write-operation timing event emitted by NEO CRUD.
   */
  public static final String BACKEND_WRITE_OPERATION_COMPLETED =
      "backend_write_operation_completed";

  private NeoTelemetryEvents() {
  }
}
