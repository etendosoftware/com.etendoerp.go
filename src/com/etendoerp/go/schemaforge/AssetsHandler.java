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

import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Date;

import javax.inject.Named;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
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
