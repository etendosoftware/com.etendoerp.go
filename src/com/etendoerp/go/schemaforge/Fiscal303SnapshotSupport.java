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
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;

/**
 * Modelo 303 submission snapshot (ETP-5438): the {@code GET /fiscal303/boxes} payload keeps
 * {@code boxes} + {@code summary}; the per-invoice {@code sources} drilldown becomes its row count,
 * {@code sourceCount}. Also owns building that payload from a {@code computeBoxes} result
 * ({@link #buildResponse}, moved here from {@link Fiscal303BoxesHandler} — see
 * {@link FiscalSnapshotSupport} for why).
 */
class Fiscal303SnapshotSupport implements FiscalSnapshotSupport {

  @Override
  public String declModel() {
    return "303";
  }

  @Override
  public JSONObject computeLivePayload(AbstractFiscalHandler handler, String orgId, int year,
      String period) throws Exception {
    Fiscal303BoxesHandler.ComputeResult cr =
        ((Fiscal303BoxesHandler) handler).computeBoxes(orgId, year, period);
    return buildResponse(cr.boxes, cr.sources);
  }

  @Override
  public Map<String, String> excludedLists() {
    return Collections.singletonMap("sources", "sourceCount");
  }

  /** The {@code GET /fiscal303/boxes} JSON shape: {@code boxes}, {@code summary}, {@code sources}. */
  static JSONObject buildResponse(Map<Integer, BigDecimal> b, List<Map<String, Object>> sources)
      throws Exception {

    JSONObject boxes = new JSONObject();
    for (Map.Entry<Integer, BigDecimal> e : b.entrySet()) {
      boxes.put(String.valueOf(e.getKey()), e.getValue().toString());
    }
    BigDecimal accrued    = b.getOrDefault(27, BigDecimal.ZERO);
    BigDecimal deductible = b.getOrDefault(45, BigDecimal.ZERO);
    BigDecimal result     = b.getOrDefault(46, BigDecimal.ZERO);
    JSONObject summary = new JSONObject();
    summary.put("accrued",    accrued.toString());
    summary.put("deductible", deductible.toString());
    summary.put("result",     result.toString());
    JSONArray sourcesArr = new JSONArray();
    for (Map<String, Object> row : sources) {
      JSONObject s = new JSONObject();
      for (Map.Entry<String, Object> e : row.entrySet()) {
        Object v = e.getValue();
        if (v instanceof BigDecimal) {
          s.put(e.getKey(), v.toString());
        } else {
          s.put(e.getKey(), v != null ? v.toString() : "");
        }
      }
      sourcesArr.put(s);
    }
    JSONObject root = new JSONObject();
    root.put(Fiscal303BoxesHandler.BOXES,     boxes);
    root.put("summary", summary);
    root.put("sources", sourcesArr);
    return root;
  }}
