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

package com.etendoerp.go.mcp;

final class McpConstants {

  static final String PARAM_ENTITY = "entity";
  static final String PARAM_FIELDS = "fields";
  static final String PARAM_COLUMN = "column";
  static final String PARAM_FIELD = "field";
  static final String PARAM_QUERY = "query";
  static final String PARAM_PARAMETERS = "parameters";
  static final String PARAM_PARENT_ID = "parentId";
  static final String PARAM_ASSET_ID = "assetId";
  /** Widget enum key for the {@code neo_widget} tool. */
  static final String PARAM_WIDGET = "widget";
  /** Free-form parameters object passed through to the widget handler (e.g. {@code range}). */
  static final String PARAM_PARAMS = "params";
  /** Current record context used to resolve dependent MCP selectors. */
  static final String PARAM_RECORD_CONTEXT = "recordContext";
  /** Parent/header record context used to resolve child MCP selectors. */
  static final String PARAM_PARENT_CONTEXT = "parentContext";
  static final String TYPE_STRING = "string";
  static final String TYPE_OBJECT = "object";
  static final String KEY_PROPERTIES = "properties";
  static final String KEY_DESCRIPTION = "description";
  static final String GENERATE_PREFIX = "generate_";
  // Action result JSON keys
  static final String KEY_ERROR = "error";
  static final String KEY_STATUS = "status";
  static final String KEY_MESSAGE = "message";
  static final String KEY_PROCESS_RESULT = "processResult";
  static final String KEY_PROCESS_MESSAGE = "processMessage";

  // Button-action metadata surfaced by neo_schema (ETP-4285)
  /**
   * Key under which a list-backed button's chosen value travels in {@code neo_action}'s
   * {@code parameters}. Consumed by {@code NeoProcessService.setDocAction}, which writes it
   * onto the record before the process runs.
   */
  static final String PARAM_DOC_ACTION = "docAction";
  /** {@code neo_schema} key listing the discrete values a button accepts. */
  static final String KEY_ACTION_VALUES = "actionValues";
  /** {@code neo_schema} key naming the parameter the chosen value must go under. */
  static final String KEY_ACTION_PARAMETER = "actionParameter";

  static final String LABEL_SPEC_NAME = "Spec name";
  static final String LABEL_ENTITY_NAME = "Entity name within the spec";
  static final String LABEL_ENTITY_NAME_WITH_EXAMPLE =
      "Entity name within the spec (e.g. 'header', 'lines')";

  /** Tool name for the amortization plan generation tool. */
  static final String TOOL_GENERATE_AMORTIZATION_PLAN = "neo_generate_amortization_plan";

  /** Tool name for the business-widget enum tool (gap G4, ETP-4284). */
  static final String TOOL_NEO_WIDGET = "neo_widget";
  /** Spec name that backs the widget handler entities (type W, no AD_Tab). */
  static final String SPEC_DASHBOARD = "dashboard";

  // Widget enum keys / backing dashboard entity names (gap G4, ETP-4284).
  static final String WIDGET_KPIS = "kpis";
  static final String WIDGET_REVENUE_TREND = "revenue-trend";
  static final String WIDGET_PENDING_TASKS = "pending-tasks";
  static final String WIDGET_ACTIVITY = "activity";
  static final String WIDGET_RECENT_INVOICES = "recent-invoices";
  static final String WIDGET_BEST_PRODUCTS = "best-products";
  static final String WIDGET_BEST_SELLERS = "best-sellers";
  static final String WIDGET_PENDING_AMOUNTS = "pending-amounts";
  static final String WIDGET_TOP_CLIENTS = "top-clients";

  private McpConstants() {
  }
}
