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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;

/**
 * Modelo 349 submission snapshot (ETP-5438): the {@code GET /fiscal349/operators} payload keeps
 * {@code operators} (one row per partner), {@code summary} and {@code rectificativeSummary}
 * (fixed E/S/A/I totals); the per-invoice {@code invoices} and {@code rectifications} rows become
 * {@code invoiceCount} / {@code rectificationCount}, after their per-operator origin counts are
 * folded into the operator rows. Extracted from {@link Fiscal349BoxesHandler} — see
 * {@link FiscalSnapshotSupport} for why.
 */
class Fiscal349SnapshotSupport implements FiscalSnapshotSupport {

  @Override
  public String declModel() {
    return "349";
  }

  @Override
  public JSONObject computeLivePayload(AbstractFiscalHandler handler, String orgId, int year,
      String period) throws Exception {
    return ((Fiscal349BoxesHandler) handler).computeOperators(orgId, year, period);
  }

  @Override
  public Map<String, String> excludedLists() {
    Map<String, String> excluded = new LinkedHashMap<>();
    excluded.put(Fiscal349BoxesHandler.INVOICES_KEY, "invoiceCount");
    excluded.put(Fiscal349BoxesHandler.RECTIFICATIONS_KEY, "rectificationCount");
    return excluded;
  }

  /** Snapshot key: how many origin purchase invoices back an operator row. */
  static final String ORIGIN_PURCHASES = "originPurchases";
  /** Snapshot key: how many origin sales invoices back an operator row. */
  static final String ORIGIN_SALES = "originSales";
  private static final String TYPE_PURCHASE = "Compra";
  private static final String TYPE_SALES = "Venta";

  /**
   * ETP-5438 review W1 — the operators' "Origen" column counts, per operator, the purchase/sales
   * invoices (or, for a corrective row, rectifications) behind it. The snapshot drops those rows,
   * so the counts are folded into each operator row first ({@link #ORIGIN_PURCHASES} /
   * {@link #ORIGIN_SALES}) — one pair per partner, so the size stays bounded. Grouping mirrors the
   * frontend's {@code originByNif} / {@code originByRectification} ({@code FmModel349Page.jsx}):
   * {@code nif|key}, where a rectification contributes one key per non-zero base
   * (Venta: products E / services S, Compra: products A / services I). An operator without any
   * matching row gets no counts (the column shows "—", as it would live).
   */
  @Override
  public void foldPerInvoiceAggregates(JSONObject payload) throws Exception {
    JSONArray operators = payload.optJSONArray(Fiscal349BoxesHandler.OPERATORS);
    if (operators == null) {
      return;
    }
    Map<String, int[]> byInvoice = new HashMap<>();
    JSONArray invoices = payload.optJSONArray(Fiscal349BoxesHandler.INVOICES_KEY);
    for (int i = 0; invoices != null && i < invoices.length(); i++) {
      JSONObject inv = invoices.getJSONObject(i);
      countOrigin(byInvoice, inv.optString(Fiscal349BoxesHandler.NIF_IVA_KEY) + "|" + inv.optString("key"), inv.optString("type"));
    }
    Map<String, int[]> byRectification = new HashMap<>();
    JSONArray rectifications = payload.optJSONArray(Fiscal349BoxesHandler.RECTIFICATIONS_KEY);
    for (int i = 0; rectifications != null && i < rectifications.length(); i++) {
      JSONObject r = rectifications.getJSONObject(i);
      for (String key : rectificationKeys(r)) {
        countOrigin(byRectification, r.optString(Fiscal349BoxesHandler.NIF_IVA_KEY) + "|" + key, r.optString("type"));
      }
    }
    for (int i = 0; i < operators.length(); i++) {
      JSONObject op = operators.getJSONObject(i);
      Map<String, int[]> source = op.optBoolean(Fiscal349BoxesHandler.RECTIFICATIVE, false) ? byRectification : byInvoice;
      int[] counts = source.get(op.optString("nif") + "|" + op.optString("key"));
      if (counts != null) {
        op.put(ORIGIN_PURCHASES, counts[0]);
        op.put(ORIGIN_SALES, counts[1]);
      }
    }
  }

  private static void countOrigin(Map<String, int[]> counts, String nifKey, String type) {
    int[] c = counts.computeIfAbsent(nifKey, k -> new int[2]);
    if (TYPE_PURCHASE.equals(type)) {
      c[0]++;
    } else if (TYPE_SALES.equals(type)) {
      c[1]++;
    }
  }

  /** The AEAT349 keys a rectification row contributes to — one per non-zero base. */
  private static List<String> rectificationKeys(JSONObject r) {
    String type = r.optString("type");
    String productsKey;
    String servicesKey;
    if (TYPE_SALES.equals(type)) {
      productsKey = "E";
      servicesKey = "S";
    } else if (TYPE_PURCHASE.equals(type)) {
      productsKey = "A";
      servicesKey = "I";
    } else {
      return new ArrayList<>();
    }
    List<String> keys = new ArrayList<>();
    if (r.optDouble("baseProducts", 0d) != 0d) {
      keys.add(productsKey);
    }
    if (r.optDouble("baseServices", 0d) != 0d) {
      keys.add(servicesKey);
    }
    return keys;
  }
}
