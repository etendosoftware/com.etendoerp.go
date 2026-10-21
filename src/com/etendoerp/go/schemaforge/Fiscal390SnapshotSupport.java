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
package com.etendoerp.go.schemaforge;

import java.math.BigDecimal;
import java.util.Map;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;

/**
 * Modelo 390 submission snapshot and {@code GET /fiscal390/boxes} payload (ETP-5716).
 *
 * <p>The payload is fixed-size (no per-invoice arrays yet), so the snapshot keeps it whole —
 * including {@code taxReport}, which is how a submitted declaration records the report (and so
 * the layout/class) its figures were computed with. The per-invoice drilldown is its own
 * endpoint ({@link Fiscal390SourcesSupport}), so it never enters this payload or the snapshot.
 *
 * <p>The live payload uses the inputs stored on the declaration ({@code manualData.aeatInputs}):
 * the snapshot is taken when the declaration is presented, from what was saved by then.
 */
class Fiscal390SnapshotSupport implements FiscalSnapshotSupport {

  @Override
  public String declModel() {
    return Fiscal390BoxesHandler.MODEL;
  }

  @Override
  public JSONObject computeLivePayload(AbstractFiscalHandler handler, String orgId, int year,
      String period) throws Exception {
    Fiscal390BoxesHandler h390 = (Fiscal390BoxesHandler) handler;
    return h390.computePayload(orgId, year, h390.effectiveStoredInputs(orgId, year, period));
  }

  /**
   * The {@code GET /fiscal390/boxes} JSON:
   * <pre>
   * { "boxes":   { "&lt;box&gt;": "&lt;amount&gt;", ... },
   *   "summary": { "accrued": [47], "deductible": [64], "result": [65], "annualResult": [86]|[94] },
   *   "mode":    "liquidation" | "territory",
   *   "taxReport": { "searchKey": "AEAT390_2025_Y", "layoutYear": 2025, "fallback": true },
   *   "inputs":  { "&lt;AEAT390 input key&gt;": "&lt;amount&gt;", ... },
   *   "warnings": [ { "code": "UNSUPPORTED_RATE"|"NO_BOX", "parameter": "...", "rate": "..." } ],
   *   "invoiceBoxes": [ 1, 3, ... ] }   // boxes filled from invoices: the Facturas tab can list them
   * </pre>
   */
  static JSONObject buildResponse(Fiscal390BoxesCalculator.Result result,
      AbstractFiscalHandler.ResolvedTaxReport taxReport, Map<String, BigDecimal> inputs)
      throws Exception {
    JSONObject boxes = new JSONObject();
    for (Map.Entry<Integer, BigDecimal> e : result.boxes.entrySet()) {
      boxes.put(String.valueOf(e.getKey()), e.getValue().toPlainString());
    }
    boolean territory = Fiscal390DerivedBoxes.MODE_TERRITORY.equals(result.mode);
    JSONObject summary = new JSONObject();
    summary.put("accrued", amount(result, 47));
    summary.put("deductible", amount(result, 64));
    summary.put("result", amount(result, 65));
    summary.put("annualResult", amount(result, territory ? 94 : 86));

    JSONObject report = new JSONObject();
    report.put("searchKey", taxReport.searchKey);
    report.put("layoutYear", taxReport.layoutYear);
    report.put("fallback", taxReport.isFallback());

    JSONObject inputsJson = new JSONObject();
    for (Map.Entry<String, BigDecimal> e : inputs.entrySet()) {
      inputsJson.put(e.getKey(), e.getValue().toPlainString());
    }
    JSONArray warnings = new JSONArray();
    for (Map<String, String> w : result.warnings) {
      warnings.put(new JSONObject(w));
    }

    JSONObject root = new JSONObject();
    root.put(Fiscal390BoxesHandler.BOXES, boxes);
    root.put("summary", summary);
    root.put("mode", result.mode);
    root.put("taxReport", report);
    root.put("inputs", inputsJson);
    root.put("warnings", warnings);
    root.put("invoiceBoxes", new JSONArray(result.invoiceBoxes));
    return root;
  }

  private static String amount(Fiscal390BoxesCalculator.Result result, int box) {
    BigDecimal v = result.boxes.get(box);
    return (v != null ? v : BigDecimal.ZERO.setScale(2)).toPlainString();
  }
}
