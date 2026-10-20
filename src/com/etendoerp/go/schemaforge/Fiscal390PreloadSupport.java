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
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.dal.service.OBQuery;

/**
 * {@code GET /fiscal390/preload?year=&period=0A[&monthlyReturn=Y|N]} — the Modelo 303
 * self-assessments of the exercise and the Modelo 390 values they preload (ETP-5716). The
 * aggregation and the 303 → 390 mapping live in {@link Fiscal390PreloadAggregator}; this class
 * loads the declarations and the boxes of each.
 *
 * <p><b>Boxes of a 303.</b> A submitted declaration with a submission snapshot uses the frozen
 * {@code boxes} ({@code source: snapshot}); a draft — or a legacy submitted declaration presented
 * before snapshots — is computed live from the current invoices through the 303 handler, exactly
 * like {@code GET /fiscal303/boxes} ({@code source: live}). Either way the declaration's
 * {@code manualData.manualOverrides} go on top and the 303 derivation runs
 * ({@link Fiscal390PreloadAggregator#effectiveBoxes}), so each period has the figures its own 303
 * screen shows. A failed live compute is reported ({@code source: error}, {@code COMPUTE_FAILED})
 * and the period is left out — never the whole answer.
 *
 * <p>{@code monthlyReturn} decides where refunds go ([96] vs [98]); when absent the stored
 * {@code ISMONTHLYRETURN} of the 390 is used.
 */
class Fiscal390PreloadSupport {

  static final String PRELOAD = "preload";
  static final String MONTHLY_RETURN_PARAM = "monthlyReturn";
  static final String MODEL_303 = "303";
  static final String MANUAL_OVERRIDES_KEY = "manualOverrides";
  static final String IDENTIFICATION_KEY = "identification";
  static final String TIPO_KEY = "tipo_declaracion";
  static final String SOURCE_SNAPSHOT = "snapshot";
  static final String SOURCE_LIVE = "live";
  static final String SOURCE_ERROR = Fiscal390PreloadAggregator.SOURCE_ERROR;

  /** Computes a 303 period's raw boxes from the current invoices (the 303 handler). */
  interface Live303Boxes {
    /**
     * Computes the raw boxes of one 303 period.
     *
     * <p>Declares {@code throws Exception} on purpose: it wraps {@code
     * Fiscal303BoxesHandler#computeBoxes}, whose checked exceptions (an incomplete fiscal year
     * among them) reach {@link AbstractFiscalHandler#userMessage} unwrapped, so the period's error
     * text stays the 303's own. A dedicated wrapper would change that message.</p>
     *
     * @param orgId  the declaring organization
     * @param year   the exercise
     * @param period the 303 period code ({@code T1}-{@code T4}, {@code 01}-{@code 12})
     * @return AEAT box number → amount
     * @throws Exception whatever the 303 compute raises; the caller marks the period as failed
     */
    @SuppressWarnings("java:S112")
    Map<Integer, BigDecimal> compute(String orgId, int year, String period) throws Exception;
  }

  private final Fiscal390BoxesHandler handler;
  /** Package-private and non-final so tests can install a double. */
  Live303Boxes live303;

  Fiscal390PreloadSupport(Fiscal390BoxesHandler handler) {
    this.handler = handler;
  }

  private Live303Boxes live303() {
    if (live303 == null) {
      Fiscal303BoxesHandler h303 = new Fiscal303BoxesHandler(handler.servlet);
      live303 = (orgId, year, period) -> h303.computeBoxes(orgId, year, period).boxes;
    }
    return live303;
  }

  void handle(String orgId, int year, String period, HttpServletRequest request,
      HttpServletResponse response) throws Exception {
    String flag = request.getParameter(MONTHLY_RETURN_PARAM);
    boolean monthlyReturn = StringUtils.isNotBlank(flag) ? "Y".equals(flag)
        : "Y".equals(handler.storedInputs(year, period).get("ISMONTHLYRETURN"));
    JSONObject out = buildPayload(orgId, year, monthlyReturn);
    response.setContentType(AbstractFiscalHandler.JSON_CT);
    response.getWriter().write(out.toString());
  }

  /** The preload payload for the exercise (see {@code aeat-390-endpoints.md}). */
  JSONObject buildPayload(String orgId, int year, boolean monthlyReturn) throws Exception {
    return toJson(aggregateYear(orgId, year, monthlyReturn), year, monthlyReturn);
  }

  /** The aggregation of the exercise's 303s — the preload, and the 390 incident rules' input. */
  Fiscal390PreloadAggregator.Result aggregateYear(String orgId, int year, boolean monthlyReturn) {
    Map<Fiscal390PreloadAggregator.Decl303, BaseOBObject> loaded = loadDeclarations(year);
    List<Fiscal390PreloadAggregator.Decl303> winners =
        Fiscal390PreloadAggregator.selectWinners(loaded.keySet());
    for (Fiscal390PreloadAggregator.Decl303 d : winners) {
      resolveBoxes(d, loaded.get(d), orgId, year);
    }
    return Fiscal390PreloadAggregator.aggregate(winners, monthlyReturn);
  }

  // ── Loading ─────────────────────────────────────────────────────────────

  /**
   * Every Modelo 303 declaration of the exercise, on the org declarations are stored under, with
   * its row (identity keys: {@code Decl303} has no equals).
   */
  Map<Fiscal390PreloadAggregator.Decl303, BaseOBObject> loadDeclarations(int year) {
    String clientId = OBContext.getOBContext().getCurrentClient().getId();
    OBQuery<BaseOBObject> query = OBDal.getInstance().createQuery(
        FiscalDeclCrudHandler.ENTITY_FISCAL_DECL,
        "client.id = :clientId and organization.id = :orgId and "
            + FiscalDeclCrudHandler.PROPERTY_FISCAL_MODEL + " = :model and "
            + FiscalDeclCrudHandler.PROPERTY_FISCAL_YEAR + " = :year");
    query.setNamedParameter("clientId", clientId);
    query.setNamedParameter("orgId", handler.declarationOrgId());
    query.setNamedParameter("model", MODEL_303);
    query.setNamedParameter("year", Long.valueOf(year));
    Map<Fiscal390PreloadAggregator.Decl303, BaseOBObject> out = new IdentityHashMap<>();
    for (BaseOBObject row : query.list()) {
      out.put(toDecl(row), row);
    }
    return out;
  }

  static Fiscal390PreloadAggregator.Decl303 toDecl(BaseOBObject row) {
    String status = FiscalDeclCrudHandler.asString(
        row.get(FiscalDeclCrudHandler.PROPERTY_DECLARATION_STATUS));
    Object rawSeq = row.get(FiscalDeclCrudHandler.PROPERTY_DECL_SEQ);
    long seq = rawSeq instanceof Number ? ((Number) rawSeq).longValue() : 0L;
    return new Fiscal390PreloadAggregator.Decl303(String.valueOf(row.getId()),
        FiscalDeclCrudHandler.asString(row.get(FiscalDeclCrudHandler.PROPERTY_PERIOD)), status,
        FiscalDeclCrudHandler.SUBMITTED_STATUSES.contains(status), seq);
  }

  /** Fills {@code source}, {@code boxes} and {@code tipo} of a period winner. */
  void resolveBoxes(Fiscal390PreloadAggregator.Decl303 d, BaseOBObject row, String orgId,
      int year) {
    JSONObject manualData = parseObject(row != null
        ? FiscalDeclCrudHandler.asString(row.get(FiscalDeclCrudHandler.PROPERTY_MANUAL_DATA))
        : null);
    JSONObject identification = manualData.optJSONObject(IDENTIFICATION_KEY);
    String tipo = identification != null ? identification.optString(TIPO_KEY, null) : null;
    d.tipo = StringUtils.isNotBlank(tipo) ? tipo : null;
    Map<Integer, BigDecimal> overrides = boxMap(manualData.optJSONObject(MANUAL_OVERRIDES_KEY));

    JSONObject snapshot = d.submitted && row != null
        ? FiscalSubmittedSnapshotSupport.parseSubmittedSnapshot(row) : null;
    Map<Integer, BigDecimal> raw;
    if (snapshot != null && snapshot.optJSONObject(Fiscal303BoxesHandler.BOXES) != null) {
      d.source = SOURCE_SNAPSHOT;
      raw = boxMap(snapshot.optJSONObject(Fiscal303BoxesHandler.BOXES));
    } else {
      try {
        raw = live303().compute(orgId, year, d.period);
        d.source = SOURCE_LIVE;
      } catch (Exception e) {
        AbstractFiscalHandler.log.warn("Modelo 390 preload: could not compute the 303 of period "
            + d.period + ": " + e.getMessage());
        d.source = SOURCE_ERROR;
        d.error = handler.userMessage(e);
        return;
      }
    }
    d.boxes = Fiscal390PreloadAggregator.effectiveBoxes(raw, overrides);
  }

  // ── JSON ────────────────────────────────────────────────────────────────

  static JSONObject parseObject(String json) {
    if (StringUtils.isBlank(json)) {
      return new JSONObject();
    }
    try {
      return new JSONObject(json);
    } catch (Exception e) {
      return new JSONObject();
    }
  }

  /**
   * {@code { "<box>": number|string }} → box → amount. Keys that are not box numbers and values
   * that are not numbers (decimal comma accepted) are skipped.
   */
  static Map<Integer, BigDecimal> boxMap(JSONObject obj) {
    Map<Integer, BigDecimal> out = new HashMap<>();
    if (obj == null) {
      return out;
    }
    Iterator<?> keys = obj.keys();
    while (keys.hasNext()) {
      String key = String.valueOf(keys.next());
      if (obj.isNull(key) || !key.matches("\\d{1,4}")) {
        continue;
      }
      String value = obj.optString(key, "").trim().replace(',', '.');
      try {
        if (!value.isEmpty()) {
          out.put(Integer.valueOf(key), new BigDecimal(value));
        }
      } catch (NumberFormatException e) {
        // not a number: ignored, like the frontend's Number() of a blank override
      }
    }
    return out;
  }

  /**
   * <pre>
   * { "year": 2026, "periodicity": "quarterly"|"monthly", "monthlyReturn": false,
   *   "declarations": [ { "id", "period", "month", "status", "submitted", "source",
   *                       "tipo", "result" ([71]), "periodResult" ([69]), "kind",
   *                       "compensationApplied" ([78]), "superseded", "error"? } ],
   *   "missingPeriods": [ "T4" ], "lastPeriod": "T4"|null,
   *   "inputs": { "INGRESAR_03": "120.00", ..., "TOTAL_LASTPERIOD": "0.00", ... },
   *   "totals": { "85": "…", "95": "…", "96": "…", "97": "…", "98": "…", "662": "…" },
   *   "territory": false, "carryOverKnown": true,
   *   "expectedResult": "…"|null,
   *   "warnings": [ { "code": "MISSING_303", "periods": "T4" } ] }
   * </pre>
   */
  static JSONObject toJson(Fiscal390PreloadAggregator.Result r, int year, boolean monthlyReturn)
      throws Exception {
    JSONArray decls = new JSONArray();
    for (Fiscal390PreloadAggregator.Decl303 d : r.declarations) {
      JSONObject o = new JSONObject();
      o.put("id", d.id);
      o.put("period", d.period);
      o.put("month", Fiscal390PreloadAggregator.monthCode(
          Fiscal390PreloadAggregator.endMonth(d.period)));
      o.put("status", d.status);
      o.put("submitted", d.submitted);
      o.put("source", d.source);
      o.put("superseded", d.superseded);
      if (d.tipo != null) {
        o.put("tipo", d.tipo);
      }
      if (SOURCE_ERROR.equals(d.source)) {
        o.put(SOURCE_ERROR, d.error);
      } else {
        o.put("result", amount(d.boxes.get(71)));
        o.put("periodResult", amount(d.boxes.get(69)));
        o.put("kind", Fiscal390PreloadAggregator.kindOf(d));
        o.put("compensationApplied", amount(d.boxes.get(78)));
      }
      decls.put(o);
    }
    JSONObject inputs = new JSONObject();
    for (Map.Entry<String, BigDecimal> e : r.inputs.entrySet()) {
      inputs.put(e.getKey(), e.getValue().toPlainString());
    }
    JSONObject totals = new JSONObject();
    for (Map.Entry<Integer, BigDecimal> e : r.totals.entrySet()) {
      totals.put(String.valueOf(e.getKey()), e.getValue().toPlainString());
    }
    JSONArray warnings = new JSONArray();
    for (Map<String, String> w : r.warnings) {
      warnings.put(new JSONObject(w));
    }
    JSONObject out = new JSONObject();
    out.put("year", year);
    out.put("periodicity", r.periodicity);
    out.put(MONTHLY_RETURN_PARAM, monthlyReturn);
    out.put("declarations", decls);
    out.put("missingPeriods", new JSONArray(r.missingPeriods));
    out.put("lastPeriod", r.lastPeriod != null ? r.lastPeriod : JSONObject.NULL);
    out.put("inputs", inputs);
    out.put("totals", totals);
    out.put("territory", r.territory);
    out.put("carryOverKnown", r.carryOverKnown);
    out.put("expectedResult", r.expectedResult != null ? r.expectedResult.toPlainString()
        : JSONObject.NULL);
    out.put("warnings", warnings);
    return out;
  }

  private static String amount(BigDecimal v) {
    return Fiscal390PreloadAggregator.r2(v != null ? v : BigDecimal.ZERO).toPlainString();
  }
}
