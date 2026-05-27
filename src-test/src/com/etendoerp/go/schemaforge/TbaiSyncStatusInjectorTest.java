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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.junit.Test;

/**
 * Unit tests for {@link TbaiSyncStatusInjector}.
 *
 * <p>Coverage targets the observable contract of {@code inject()} without a live database:
 * <ul>
 *   <li>empty or id-less arrays return early without touching {@code OBDal}</li>
 *   <li>when the TBAI module is absent ({@code OBDal} unavailable), the exception is
 *       swallowed and original data is returned unmodified</li>
 * </ul>
 *
 * <p>The happy-path (actual TBAI rows injected into the response) requires a live
 * {@code tbai_syncinvoice} table and is covered by integration tests.
 */
public class TbaiSyncStatusInjectorTest {

  // ── early-return paths (no OBDal contact) ───────────────────────────────

  /**
   * An empty data array must not throw and must remain empty.
   */
  @Test
  public void testInjectEmptyArrayIsNoop() throws JSONException {
    JSONArray data = new JSONArray();
    TbaiSyncStatusInjector.inject(data);
    assertEquals("empty array must stay empty", 0, data.length());
  }

  /**
   * Records without an {@code id} field produce an empty id list, so {@code inject}
   * returns before reaching {@code OBDal}. No {@code tbaiSyncEstado} must be added.
   */
  @Test
  public void testInjectArrayWithNoIdsIsNoop() throws JSONException {
    JSONArray data = new JSONArray()
        .put(new JSONObject().put("grandTotalAmount", 100.0))
        .put(new JSONObject().put("grandTotalAmount", 200.0));

    TbaiSyncStatusInjector.inject(data);

    assertFalse("first record must not gain tbaiSyncEstado",
        data.getJSONObject(0).has("tbaiSyncEstado"));
    assertFalse("second record must not gain tbaiSyncEstado",
        data.getJSONObject(1).has("tbaiSyncEstado"));
  }

  /**
   * An {@code id} field present but empty string is treated as absent — ids list
   * stays empty, early return before any DB access.
   */
  @Test
  public void testInjectArrayWithEmptyStringIdIsNoop() throws JSONException {
    JSONArray data = new JSONArray()
        .put(new JSONObject().put("id", "").put("documentNo", "SI-001"));

    TbaiSyncStatusInjector.inject(data);

    assertFalse("Record with empty ID should not have tbaiSyncEstado injected",
        data.getJSONObject(0).has("tbaiSyncEstado"));
    assertEquals("SI-001", data.getJSONObject(0).getString("documentNo"));
  }

  // ── TBAI module absent (OBDal throws) ───────────────────────────────────

  /**
   * When the TBAI module is not installed, {@code OBDal.getInstance()} throws because
   * Hibernate is not initialised in the unit-test context. {@code inject()} must catch
   * the exception silently and return the original data unmodified.
   */
  @Test
  public void testInjectWithOBDalUnavailableLeavesDataUnchanged() throws JSONException {
    JSONObject invoice = new JSONObject()
        .put("id", "INV-001")
        .put("grandTotalAmount", 500.0);
    JSONArray data = new JSONArray().put(invoice);

    TbaiSyncStatusInjector.inject(data);

    assertFalse("tbaiSyncEstado must not be set when OBDal is unavailable",
        data.getJSONObject(0).has("tbaiSyncEstado"));
    assertEquals(500.0, data.getJSONObject(0).getDouble("grandTotalAmount"), 0.001);
  }

  /**
   * Same as above but with a multi-record page — all records must survive unmodified.
   */
  @Test
  public void testInjectMultipleRecordsWithOBDalUnavailableLeavesAllUnchanged() throws JSONException {
    JSONArray data = new JSONArray()
        .put(new JSONObject().put("id", "INV-001").put("documentNo", "SI-001"))
        .put(new JSONObject().put("id", "INV-002").put("documentNo", "SI-002"));

    TbaiSyncStatusInjector.inject(data);

    assertFalse("First record must not gain tbaiSyncEstado when OBDal is unavailable",
        data.getJSONObject(0).has("tbaiSyncEstado"));
    assertFalse("Second record must not gain tbaiSyncEstado when OBDal is unavailable",
        data.getJSONObject(1).has("tbaiSyncEstado"));
    assertEquals("SI-001", data.getJSONObject(0).getString("documentNo"));
    assertEquals("SI-002", data.getJSONObject(1).getString("documentNo"));
  }

  /**
   * Mixed array: some records have ids, some do not. The exception path still fires
   * (non-empty ids list reaches OBDal) and all records emerge unmodified.
   */
  @Test
  public void testInjectMixedIdAndNoIdRecordsWithOBDalUnavailable() throws JSONException {
    JSONArray data = new JSONArray()
        .put(new JSONObject().put("id", "INV-001").put("documentNo", "SI-001"))
        .put(new JSONObject().put("documentNo", "SI-002")); // no id

    TbaiSyncStatusInjector.inject(data);

    assertFalse("Record with id must not gain tbaiSyncEstado when OBDal is unavailable",
        data.getJSONObject(0).has("tbaiSyncEstado"));
    assertFalse("Record without id must not gain tbaiSyncEstado",
        data.getJSONObject(1).has("tbaiSyncEstado"));
  }
}
