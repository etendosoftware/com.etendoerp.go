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

import java.util.Collections;
import java.util.Map;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;

/**
 * Per-model definition of a fiscal declaration's submission snapshot (ETP-5438): which model code
 * it serves, how its live read payload is computed, and how that payload is reduced to the
 * fixed-size figures frozen at submission. One stateless implementation per {@link AbstractFiscalHandler}
 * subclass ({@link Fiscal303SnapshotSupport}, {@link Fiscal349SnapshotSupport}); extracted from the
 * handlers themselves to keep them under the SonarQube {@code java:S1448} method-count threshold,
 * following the module's existing {@code *Support} pattern.
 *
 * <p>Methods take the handler as an argument instead of holding it, so a Mockito spy of the
 * handler (the pattern the handler tests use) stays in the call chain.
 */
interface FiscalSnapshotSupport {

  /**
   * The declaration model this definition serves.
   *
   * @return the bare {@code ETGO_Fiscal_Decl.model} code ({@code "303"}, {@code "349"})
   */
  String declModel();

  /**
   * The model's live read payload ({@code /fiscal303/boxes}, {@code /fiscal349/operators}),
   * computed from the CURRENT invoice data by {@code handler}.
   *
   * @param handler the model's handler (possibly a Mockito spy) that runs the compute
   * @param orgId   the effective organization the figures are computed for
   * @param year    the fiscal year
   * @param period  the period code ({@code "T1"}, {@code "01"}, ...)
   * @return the live read payload
   * @throws Exception whatever the handler's compute raises
   */
  @SuppressWarnings("java:S112")
  JSONObject computeLivePayload(AbstractFiscalHandler handler, String orgId, int year,
      String period) throws Exception;

  /**
   * The per-invoice arrays of the live payload that the snapshot does NOT keep, each mapped to the
   * key under which the snapshot stores its row count instead (ETP-5438 scope decision). A period
   * can hold tens of thousands of invoices, so keeping them would make the snapshot — and every
   * {@code GET /declarations}, which returns it — grow without bound. Empty by default.
   *
   * @return excluded array key mapped to the key of its row count in the snapshot
   */
  default Map<String, String> excludedLists() {
    return Collections.emptyMap();
  }

  /**
   * Hook run on the live payload right before its per-invoice arrays are dropped: lets a model
   * keep fixed-size aggregates the UI derives from those rows (e.g. 349's per-operator origin
   * counts). No-op by default.
   *
   * @param payload the live payload, modified in place
   * @throws Exception on a malformed payload
   */
  @SuppressWarnings("java:S112")
  default void foldPerInvoiceAggregates(JSONObject payload) throws Exception {
    // nothing to fold for models without per-invoice derived figures
  }

  /**
   * Reduces a live payload to the submission snapshot: {@link #foldPerInvoiceAggregates}, then
   * every array of {@link #excludedLists} replaced by its row count. The single place a snapshot
   * is built, so its size is bounded whatever the number of invoices in the period (the payload is
   * re-serialized through {@link JSONObject} when stored).
   *
   * <p><b>Known, accepted limit (349).</b> The 349 snapshot still grows with the number of
   * OPERATOR rows (~264 chars each, one per partner and key), so beyond roughly 3,800 operator
   * rows it exceeds the column's AD {@code FIELDLENGTH} of 1,000,000. The entity validator then
   * rejects it and the presentation fails safely (500, nothing written) — documented rather than
   * handled, being far beyond any realistic 349.
   *
   * @param payload the live payload; reduced in place
   * @return the same object, now the submission snapshot
   * @throws Exception on a malformed payload
   */
  @SuppressWarnings("java:S112")
  default JSONObject toSnapshot(JSONObject payload) throws Exception {
    foldPerInvoiceAggregates(payload);
    for (Map.Entry<String, String> excluded : excludedLists().entrySet()) {
      JSONArray rows = payload.optJSONArray(excluded.getKey());
      payload.remove(excluded.getKey());
      payload.put(excluded.getValue(), rows != null ? rows.length() : 0);
    }
    return payload;
  }
}
