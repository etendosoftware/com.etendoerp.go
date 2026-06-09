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
 * All portions are Copyright © 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.schemaforge;

import java.math.BigDecimal;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.base.exception.OBException;

import com.etendoerp.go.schemaforge.BankStatementFormatDetector.StatementFormat;

/**
 * Builds the read-only "preview" payload of a bank-statement import (the
 * {@code ?action=preview} response): totals (abonos / cargos) and the period
 * (min/max transaction date) aggregated over the parsed lines. Extracted from
 * {@link BankStatementsHandler} so the handler stays under the per-class
 * method-count limit; every method here is pure (no DB, no OBContext).
 */
public final class BankStatementPreview {

  private static final String KEY_CRAMOUNT = "cramount";
  private static final String KEY_DRAMOUNT = "dramount";
  private static final String KEY_DATE = "date";

  private BankStatementPreview() {
    // utility class — no instances
  }

  /**
   * Builds the preview envelope JSON: format, file name, line count, in/out
   * totals, period and the lines array.
   *
   * @param format    the detected statement format
   * @param fileName  the uploaded file name
   * @param lineCount the canonical line count (SQL row count)
   * @param lines     the parsed lines as returned by the preview reader
   * @return the preview payload object
   * @throws OBException if building the JSON payload fails (wraps the underlying
   *     {@link JSONException})
   */
  public static JSONObject buildPayload(StatementFormat format, String fileName,
      int lineCount, JSONArray lines) {
    try {
      Totals totals = aggregate(lines);

      JSONObject result = new JSONObject();
      result.put("format", format.name());
      result.put("fileName", fileName);
      result.put("lineCount", lineCount);
      result.put("totalIn", totals.totalIn);
      result.put("totalOut", totals.totalOut);
      result.put("periodFrom", totals.periodFrom);
      result.put("periodTo", totals.periodTo);
      result.put("lines", lines);
      return result;
    } catch (JSONException e) {
      throw new OBException("Error building bank statement preview payload", e);
    }
  }

  /** Aggregation result computed from the parsed lines: totals + period. */
  private static final class Totals {
    private BigDecimal totalIn = BigDecimal.ZERO;
    private BigDecimal totalOut = BigDecimal.ZERO;
    private String periodFrom = "";
    private String periodTo = "";
  }

  /**
   * Walks the parsed lines once, accumulating totalIn / totalOut and the min/max
   * transaction date.
   */
  private static Totals aggregate(JSONArray lines) throws JSONException {
    Totals t = new Totals();
    if (lines == null) {
      return t;
    }
    for (int i = 0; i < lines.length(); i++) {
      JSONObject row = lines.getJSONObject(i);
      t.totalIn = t.totalIn.add(amountFrom(row, KEY_CRAMOUNT));
      t.totalOut = t.totalOut.add(amountFrom(row, KEY_DRAMOUNT));
      updatePeriod(t, row.optString(KEY_DATE, ""));
    }
    return t;
  }

  private static BigDecimal amountFrom(JSONObject row, String key) throws JSONException {
    if (!row.has(key) || row.isNull(key)) return BigDecimal.ZERO;
    return new BigDecimal(row.getString(key));
  }

  private static void updatePeriod(Totals t, String date) {
    if (date.isEmpty()) return;
    if (t.periodFrom.isEmpty() || date.compareTo(t.periodFrom) < 0) t.periodFrom = date;
    if (t.periodTo.isEmpty()   || date.compareTo(t.periodTo)   > 0) t.periodTo = date;
  }
}
