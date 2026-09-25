/*
 *************************************************************************
 * The contents of this file are subject to the Etendo License
 * (the "License"), you may not use this file except in compliance
 * with the License.
 * You may obtain a copy of the License at
 * https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * Software distributed under the License is distributed on an
 * "AS IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
 * implied. See the License for the specific language governing rights
 * and limitations under the License.
 * All portions are Copyright (C) 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 *************************************************************************
 */
package com.etendoerp.go.schemaforge.util;

import java.util.Collections;
import java.util.List;

/**
 * One declared input parameter of a NEO-native report (ETP-4793 / IMP-19).
 *
 * <p><b>Why the handler declares this instead of the configuration (IMP-19).</b> Report input
 * parameters are read straight out of the request body by the handler
 * ({@code body.optString("dateFrom", "")}), and they are <b>not</b> AD columns of anything —
 * {@code dateFrom}, {@code recOrPay} and {@code column1} exist only in the handler's own SQL. All
 * eight active report specs carry <b>zero</b> {@code ETGO_SF_FIELD} rows, so
 * {@code ToolRegistry#buildProcessParamSchema}, which emits a property only for a field with an
 * {@code AD_Column}, produced an empty map for every one of them — and an untyped
 * {@code parameters:{type:"object"}} in every {@code generate_*} tool schema. Backfilling the
 * configuration cannot fix that, because there is no column to point the rows at. The handler is
 * therefore the only authority, exactly as it is for
 * {@code NeoHandler#servesActions} (ETP-4254), which exists for the same reason.</p>
 *
 * <p>An empty declaration is meaningful and is <b>not</b> the same as no declaration: it says the
 * report runs with no inputs, which is what {@code generate_inventory_stock_report} needed —
 * evidence B5 recorded that it "needs no parameters" and that this was "undiscoverable from the
 * schema". A handler that declares nothing at all is not a report generator; see
 * {@code NeoReportCallability#isReportCallable}.</p>
 *
 * <p>Type names are JSON Schema types, plus {@code date} for a {@code yyyy-MM-dd} string. They match
 * the vocabulary {@code AgingReportHandler} was already using in its GET descriptor.</p>
 */
public final class NeoReportParam {

  /** The only output format any NEO-native report handler serves today. */
  public static final String FORMAT_JSON = "json";

  /** JSON Schema type for a plain string parameter. */
  public static final String TYPE_STRING = "string";
  /** JSON Schema type for a whole-number parameter. */
  public static final String TYPE_INTEGER = "integer";
  /** JSON Schema type for a true/false parameter. */
  public static final String TYPE_BOOLEAN = "boolean";
  /**
   * A {@code yyyy-MM-dd} date, carried as a string. Deliberately distinct from
   * {@link #TYPE_STRING}: IMP-16 traced silent data corruption to date values whose expected shape
   * was never stated anywhere an agent could read it.
   */
  public static final String TYPE_DATE = "date";

  private final String name;
  private final String type;
  private final boolean required;
  private final String description;
  private final List<String> allowedValues;

  private NeoReportParam(String name, String type, boolean required, String description,
      List<String> allowedValues) {
    this.name = name;
    this.type = type;
    this.required = required;
    this.description = description;
    this.allowedValues = allowedValues == null ? Collections.emptyList()
        : List.copyOf(allowedValues);
  }

  /**
   * A required parameter: the report cannot run without it.
   *
   * @param name        the body key the handler reads
   * @param type        one of the {@code TYPE_*} constants
   * @param description what the parameter means, and its expected shape when that is not obvious
   * @return the descriptor
   */
  public static NeoReportParam required(String name, String type, String description) {
    return new NeoReportParam(name, type, true, description, null);
  }

  /**
   * An optional parameter. State the handler's own fallback in the description — an agent that
   * cannot see the default has no way to tell an omitted filter from a neutral one.
   *
   * @param name        the body key the handler reads
   * @param type        one of the {@code TYPE_*} constants
   * @param description what the parameter means, including the default the handler applies
   * @return the descriptor
   */
  public static NeoReportParam optional(String name, String type, String description) {
    return new NeoReportParam(name, type, false, description, null);
  }

  /**
   * An optional parameter restricted to a closed set of values.
   *
   * <p>Worth preferring over {@link #optional} wherever the handler branches on the value: a
   * string parameter compared with {@code "acct".equals(…)} silently treats every unrecognised
   * value as the other branch, so an agent's typo changes the report's meaning without any
   * signal. The enum moves that failure to schema-validation time.</p>
   *
   * @param name          the body key the handler reads
   * @param description   what the parameter means, including the default the handler applies
   * @param allowedValues every value the handler actually distinguishes
   * @return the descriptor
   */
  public static NeoReportParam options(String name, String description,
      List<String> allowedValues) {
    return new NeoReportParam(name, TYPE_STRING, false, description, allowedValues);
  }

  /**
   * @return the body key the handler reads this parameter from
   */
  public String getName() {
    return name;
  }

  /**
   * @return the declared type, one of the {@code TYPE_*} constants
   */
  public String getType() {
    return type;
  }

  /**
   * @return {@code true} when the report cannot run without this parameter
   */
  public boolean isRequired() {
    return required;
  }

  /**
   * @return the human-readable description shown to agents in the tool schema
   */
  public String getDescription() {
    return description;
  }

  /**
   * @return the closed set of accepted values, or an empty list when the value is unrestricted
   */
  public List<String> getAllowedValues() {
    return allowedValues;
  }
}
