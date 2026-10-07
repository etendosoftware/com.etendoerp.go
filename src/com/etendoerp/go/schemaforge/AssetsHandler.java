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

package com.etendoerp.go.schemaforge;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Date;

import javax.inject.Named;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.financialmgmt.assetmgmt.Asset;

/**
 * NeoHandler for the {@code assets} entity in the Assets window.
 *
 * <p>On POST (create) and PATCH (update), computes {@code depreciationEndDate} from
 * {@code depreciationStartDate} and {@code usableLifeMonths} using calendar-month
 * arithmetic:
 * <pre>
 *   depreciationEndDate = depreciationStartDate + usableLifeMonths (calendar months)
 * </pre>
 *
 * <p>On POST: both source fields must be present in the request body.
 * On PATCH: if either source field is present in the body, the missing field is loaded
 * from the persisted record via {@link OBDal}, so partial updates (e.g. only
 * {@code usableLifeMonths} changed) still trigger a recomputation. The computed value
 * is injected into the request body before the default CRUD service persists the record
 * ({@code handle()} returns {@code null} to continue with default DataSourceServlet handling).
 *
 * <p>On every CRUD response that includes the {@code etgoAmortizationStatus} field
 * (GET list, GET by id, and the record echoed back by POST/PUT/PATCH), {@link #afterHandle}
 * recomputes it to 2-decimal precision and overrides whatever value the classic DB trigger
 * ({@code etgo_a_asset_amort_status_trg()}, PostgreSQL-only, lives in Etendo Classic and is
 * intentionally left untouched — see ETP-5414) wrote. That trigger uses {@code ROUND(x)}
 * with no decimal argument, so it truncates to an integer (e.g. 16.67% is persisted as
 * {@code 17}); the UI progress bar in {@code AssetsSidebar.jsx} needs the precise value.
 * See {@link #recomputeAmortizationStatus(JSONObject)} for the formula, which mirrors the
 * trigger's semantics exactly except for the rounding scale.
 *
 * <p>All other endpoints pass through to the default service unchanged.
 *
 * <p>Registered via {@code JAVA_QUALIFIER = 'assetsHandler'} on the ETGO_SF_ENTITY
 * record for spec {@code assets}, entity {@code assets}. This is set automatically
 * by {@code push-to-neo.js} when {@code decisions.json} contains
 * {@code "javaQualifier": "assetsHandler"} in the {@code entities.assets} block.
 */
@Named("assetsHandler")
public class AssetsHandler implements NeoHandler {

  private static final Logger log = LogManager.getLogger(AssetsHandler.class);

  private static final String FIELD_DEPRECIATION_START_DATE = "depreciationStartDate";
  private static final String FIELD_DEPRECIATION_END_DATE = "depreciationEndDate";
  private static final String FIELD_USABLE_LIFE_MONTHS = "usableLifeMonths";

  // Fields backing the amortization-status recomputation. All three are real, included
  // ETGO_SF_FIELD entries (see artifacts/assets/contract.json) so they are already present
  // on every CRUD record response — no extra DB round-trip is needed to recompute the
  // percentage from the response body itself.
  private static final String FIELD_ETGO_AMORTIZATION_STATUS = "etgoAmortizationStatus";
  private static final String FIELD_DEPRECIATION_AMT = "depreciationAmt"; // AMORTIZATIONVALUEAMT
  private static final String FIELD_DEPRECIATED_VALUE = "depreciatedValue"; // DEPRECIATEDVALUE
  private static final String FIELD_PREVIOUSLY_DEPRECIATED_AMT = "previouslyDepreciatedAmt"; // DEPRECIATEDPREVIOUSAMT

  private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
  private static final int PERCENTAGE_SCALE = 2;

  private static final String HTTP_POST = "POST";
  private static final String HTTP_PATCH = "PATCH";

  /** ISO date pattern used by NEO Headless for date fields. */
  private static final String DATE_PATTERN = "yyyy-MM-dd";
  /** ISO date formatter for {@link LocalDate} parsing/formatting (equivalent to {@link #DATE_PATTERN}). */
  private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE;

  @Override
  public NeoResponse handle(NeoContext context) {
    if (context.getEndpointType() != NeoEndpointType.CRUD) {
      return null;
    }
    String method = context.getHttpMethod();
    if (!HTTP_POST.equalsIgnoreCase(method) && !HTTP_PATCH.equalsIgnoreCase(method)) {
      return null;
    }
    JSONObject body = context.getRequestBody();
    if (body == null) {
      return null;
    }
    boolean startDateInBody = hasValue(body, FIELD_DEPRECIATION_START_DATE);
    boolean usableLifeInBody = hasValue(body, FIELD_USABLE_LIFE_MONTHS);
    if (!hasRequiredSourceFields(method, startDateInBody, usableLifeInBody)) {
      return null;
    }
    try {
      String startDateStr = resolveStartDate(startDateInBody, body, context.getRecordId());
      if (startDateStr == null) {
        return null;
      }
      Long usableLifeMonths = resolveUsableLifeMonths(usableLifeInBody, body, context.getRecordId());
      if (usableLifeMonths == null) {
        return null;
      }
      LocalDate endDate = LocalDate.parse(startDateStr, DATE_FORMATTER).plusMonths(usableLifeMonths);
      String endDateStr = endDate.format(DATE_FORMATTER);
      // NOTE: this relies on depreciationEndDate being a writable (editable) field. The CRUD
      // write filter (NeoFieldFilter.filterWriteRequest) strips any included+readOnly field, so
      // if depreciationEndDate is ever reclassified to readOnly, this body.put is silently
      // dropped and the recompute regresses. If that classification changes, move this write to
      // afterHandle() and mutate the persisted record directly (see InventoryLineHandler).
      body.put(FIELD_DEPRECIATION_END_DATE, endDateStr);
      log.debug("AssetsHandler: computed depreciationEndDate={} from startDate={} + {}mo",
          endDateStr, startDateStr, usableLifeMonths);
    } catch (DateTimeParseException e) {
      log.warn("AssetsHandler: could not parse depreciationStartDate — skipping computation", e);
    } catch (Exception e) {
      log.warn("AssetsHandler: unexpected error computing depreciationEndDate — skipping", e);
    }
    return null;
  }

  /**
   * Post-hook: recomputes {@code etgoAmortizationStatus} to 2-decimal precision on every CRUD
   * response, replacing the integer-rounded value the classic DB trigger wrote. Never fails the
   * parent request over this side effect — any error is logged and swallowed, leaving the
   * trigger's value in place (degraded but safe, same pattern as the other handlers in this
   * package).
   */
  @Override
  public NeoResponse afterHandle(NeoContext context) {
    if (context.getEndpointType() != NeoEndpointType.CRUD) {
      return null;
    }
    NeoResponse previous = context.getPreviousResult();
    if (previous == null || previous.getBody() == null) {
      return null;
    }
    try {
      JSONObject body = previous.getBody();
      JSONArray records = extractRecordArray(body);
      if (records != null) {
        for (int i = 0; i < records.length(); i++) {
          recomputeAmortizationStatus(records.optJSONObject(i));
        }
      } else {
        // Flat single-record body (no "data"/"response.data" envelope).
        recomputeAmortizationStatus(body);
      }
    } catch (Exception e) {
      log.warn("AssetsHandler: could not recompute etgoAmortizationStatus — leaving "
          + "trigger-computed value as-is: {}", e.getMessage(), e);
    }
    return null; // mutated in place; keep the (possibly default) previous result
  }

  /**
   * Returns the array of record objects to recompute, handling both response shapes this
   * handler has been observed to receive: {@code {"response": {"data": [...]}}} (classic
   * DefaultJsonDataService envelope) and the plain {@code {"data": [...]}} shape. Returns
   * {@code null} when the body is a single flat record (no array envelope found).
   */
  private static JSONArray extractRecordArray(JSONObject body) {
    JSONObject response = body.optJSONObject("response");
    if (response != null) {
      JSONArray data = response.optJSONArray("data");
      if (data != null) {
        return data;
      }
    }
    return body.optJSONArray("data");
  }

  /**
   * Recomputes {@code etgoAmortizationStatus} on a single record, in place. No-op if the field
   * is not present on the record (not included for the current role, or the record shape is
   * unexpected) or if the source fields cannot be resolved.
   *
   * <p>Formula (mirrors {@code etgo_a_asset_amort_status_trg()} exactly, except this uses
   * 2-decimal rounding instead of the trigger's integer {@code ROUND(x)}):
   * <pre>
   *   status = LEAST(ROUND((depreciatedValue + previouslyDepreciatedAmt) / depreciationAmt * 100, 2), 100)
   *   status = 0  when depreciationAmt is null or zero (no plan defined)
   * </pre>
   */
  private static void recomputeAmortizationStatus(JSONObject entry) {
    if (entry == null || !entry.has(FIELD_ETGO_AMORTIZATION_STATUS)) {
      return;
    }
    try {
      BigDecimal denominator = optBigDecimal(entry, FIELD_DEPRECIATION_AMT);
      if (denominator == null || denominator.compareTo(BigDecimal.ZERO) == 0) {
        entry.put(FIELD_ETGO_AMORTIZATION_STATUS, BigDecimal.ZERO.setScale(PERCENTAGE_SCALE));
        return;
      }
      BigDecimal depreciatedValue = optBigDecimalOrZero(entry, FIELD_DEPRECIATED_VALUE);
      BigDecimal previouslyDepreciated = optBigDecimalOrZero(entry, FIELD_PREVIOUSLY_DEPRECIATED_AMT);
      BigDecimal numerator = depreciatedValue.add(previouslyDepreciated);
      BigDecimal percentage = numerator.multiply(HUNDRED)
          .divide(denominator, PERCENTAGE_SCALE, RoundingMode.HALF_UP);
      if (percentage.compareTo(HUNDRED) > 0) {
        percentage = HUNDRED.setScale(PERCENTAGE_SCALE);
      }
      entry.put(FIELD_ETGO_AMORTIZATION_STATUS, percentage);
    } catch (Exception e) {
      log.debug("AssetsHandler: skipping etgoAmortizationStatus recompute for record {}: {}",
          entry.opt("id"), e.getMessage());
    }
  }

  /**
   * Reads a numeric field from a JSON record tolerantly (jettison may surface it as a
   * {@link Number} or as a {@link String}). Returns {@code null} if absent, JSON-null, or
   * unparseable.
   */
  private static BigDecimal optBigDecimal(JSONObject entry, String key) {
    if (entry == null || !entry.has(key) || entry.isNull(key)) {
      return null;
    }
    Object raw = entry.opt(key);
    if (raw instanceof BigDecimal) {
      return (BigDecimal) raw;
    }
    if (raw instanceof Number) {
      return BigDecimal.valueOf(((Number) raw).doubleValue());
    }
    try {
      String strVal = entry.optString(key, null);
      return strVal != null && !strVal.isEmpty() ? new BigDecimal(strVal) : null;
    } catch (NumberFormatException e) {
      return null;
    }
  }

  /** Same as {@link #optBigDecimal(JSONObject, String)}, but returns {@link BigDecimal#ZERO}
   * instead of {@code null} when the field is absent/unparseable. */
  private static BigDecimal optBigDecimalOrZero(JSONObject entry, String key) {
    BigDecimal value = optBigDecimal(entry, key);
    return value != null ? value : BigDecimal.ZERO;
  }

  /**
   * Returns true when the request contains the source fields needed to recompute
   * {@code depreciationEndDate}. For POST, both fields must be present. For PATCH,
   * at least one must be present (the other is loaded from the persisted record).
   */
  private static boolean hasRequiredSourceFields(String method, boolean startDateInBody,
      boolean usableLifeInBody) {
    if (HTTP_POST.equalsIgnoreCase(method)) {
      return startDateInBody && usableLifeInBody;
    }
    return startDateInBody || usableLifeInBody;
  }

  /**
   * Resolves the depreciation start date. Prefers the value in the request body; on PATCH
   * falls back to the persisted record when the field is absent from the diff. Returns
   * {@code null} if the value cannot be resolved (computation should be skipped).
   */
  private static String resolveStartDate(boolean startDateInBody, JSONObject body,
      String recordId) throws org.codehaus.jettison.json.JSONException {
    if (startDateInBody) {
      return body.getString(FIELD_DEPRECIATION_START_DATE);
    }
    String loaded = loadStartDateFromRecord(recordId);
    if (loaded == null) {
      log.warn("AssetsHandler: PATCH has usableLifeMonths but depreciationStartDate not in body "
          + "and could not be loaded from record {} — skipping computation", recordId);
    }
    return loaded;
  }

  /**
   * Resolves the usable life in months. Prefers the value in the request body (parsed
   * tolerantly); on PATCH falls back to the persisted record. Returns {@code null} if the
   * value cannot be resolved (computation should be skipped).
   */
  private static Long resolveUsableLifeMonths(boolean usableLifeInBody, JSONObject body,
      String recordId) {
    if (usableLifeInBody) {
      long parsed = parseUsableLifeMonths(body, recordId);
      return parsed < 0 ? null : parsed;
    }
    Long loaded = loadUsableLifeMonthsFromRecord(recordId);
    if (loaded == null) {
      log.warn("AssetsHandler: PATCH has depreciationStartDate but usableLifeMonths not in body "
          + "and could not be loaded from record {} — skipping computation", recordId);
    }
    return loaded;
  }

  /**
   * Parses {@code usableLifeMonths} from the request body tolerantly.
   * Handles integer, long, or string representations (e.g. "24", "24.0").
   * Returns the parsed value, or {@code -1} if the value cannot be parsed
   * (a warning is logged in that case).
   */
  private static long parseUsableLifeMonths(JSONObject body, String recordId) {
    try {
      // Try direct integer read first (most common path)
      Object raw = body.get(FIELD_USABLE_LIFE_MONTHS);
      if (raw instanceof Number) {
        return ((Number) raw).longValue();
      }
      // Tolerate string representation (e.g. sent as "24" or "24.0")
      String strVal = body.optString(FIELD_USABLE_LIFE_MONTHS, null);
      if (strVal != null && !strVal.isEmpty()) {
        return new java.math.BigDecimal(strVal).longValue();
      }
    } catch (Exception e) {
      log.warn("AssetsHandler: could not parse usableLifeMonths for record {} — skipping computation: {}",
          recordId, e.getMessage());
    }
    return -1;
  }

  /**
   * Loads {@code depreciationStartDate} from the persisted Asset record identified by
   * {@code recordId}. Returns the date formatted as ISO {@code yyyy-MM-dd}, or {@code null}
   * if the record or the date cannot be resolved.
   */
  private static String loadStartDateFromRecord(String recordId) {
    if (recordId == null || recordId.isEmpty()) {
      return null;
    }
    try {
      Asset asset = OBDal.getInstance().get(Asset.class, recordId);
      if (asset == null) {
        return null;
      }
      Date date = asset.getDepreciationStartDate();
      if (date == null) {
        return null;
      }
      // Use SimpleDateFormat to format the persisted date — consistent with the rest of the
      // module and safe for any java.util.Date subclass (java.sql.Date.toInstant() would throw).
      return new SimpleDateFormat(DATE_PATTERN).format(date);
    } catch (Exception e) {
      log.warn("AssetsHandler: could not load depreciationStartDate from record {}: {}",
          recordId, e.getMessage());
      return null;
    }
  }

  /**
   * Loads {@code usableLifeMonths} from the persisted Asset record identified by
   * {@code recordId}. Returns the value, or {@code null} if the record or the field
   * cannot be resolved.
   */
  private static Long loadUsableLifeMonthsFromRecord(String recordId) {
    if (recordId == null || recordId.isEmpty()) {
      return null;
    }
    try {
      Asset asset = OBDal.getInstance().get(Asset.class, recordId);
      if (asset == null) {
        return null;
      }
      return asset.getUsableLifeMonths();
    } catch (Exception e) {
      log.warn("AssetsHandler: could not load usableLifeMonths from record {}: {}",
          recordId, e.getMessage());
      return null;
    }
  }

  /**
   * Returns true when {@code key} is present in {@code body} and its value is not JSON null.
   */
  private static boolean hasValue(JSONObject body, String key) {
    return body.has(key) && !body.isNull(key);
  }
}
