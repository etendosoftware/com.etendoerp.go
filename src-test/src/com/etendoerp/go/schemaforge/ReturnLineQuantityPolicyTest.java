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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.math.BigDecimal;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.Test;

/**
 * Unit tests for {@link ReturnLineQuantityPolicy} (ETP-5313 / ETP-5336).
 *
 * <p>Covers the two pure sign normalisations ({@code toStoredQuantity}/{@code
 * toDisplayQuantity}) and their JSON-body / JSON-array wrappers, used by {@link
 * ReturnMaterialReceiptLineHandler}, {@link ReturnToVendorShipmentLineHandler} and {@link
 * ReturnShipmentUtils#buildAndSaveReturnLine}.
 */
public class ReturnLineQuantityPolicyTest {

  private static final Logger LOG = LogManager.getLogger(ReturnLineQuantityPolicyTest.class);

  // ── toStoredQuantity ───────────────────────────────────────────────────────

  @Test
  public void testToStoredQuantityNegatesPositive() {
    assertEquals(new BigDecimal("-5"),
        ReturnLineQuantityPolicy.toStoredQuantity(new BigDecimal("5")));
  }

  @Test
  public void testToStoredQuantityLeavesNegativeUnchanged() {
    assertEquals(new BigDecimal("-5"),
        ReturnLineQuantityPolicy.toStoredQuantity(new BigDecimal("-5")));
  }

  @Test
  public void testToStoredQuantityZeroStaysZero() {
    assertEquals(0,
        BigDecimal.ZERO.compareTo(ReturnLineQuantityPolicy.toStoredQuantity(BigDecimal.ZERO)));
  }

  @Test
  public void testToStoredQuantityNullPassesThrough() {
    assertNull(ReturnLineQuantityPolicy.toStoredQuantity(null));
  }

  @Test
  public void testToStoredQuantityIsIdempotent() {
    BigDecimal once = ReturnLineQuantityPolicy.toStoredQuantity(new BigDecimal("7"));
    BigDecimal twice = ReturnLineQuantityPolicy.toStoredQuantity(once);
    assertEquals("applying the stored-sign normalisation twice must be harmless — several "
        + "layers (action handler, shared import helper, CRUD handler) may all call this on "
        + "the same value", once, twice);
  }

  // ── toDisplayQuantity ──────────────────────────────────────────────────────

  @Test
  public void testToDisplayQuantityAbsNegative() {
    assertEquals(new BigDecimal("5"),
        ReturnLineQuantityPolicy.toDisplayQuantity(new BigDecimal("-5")));
  }

  @Test
  public void testToDisplayQuantityLeavesPositiveUnchanged() {
    assertEquals(new BigDecimal("5"),
        ReturnLineQuantityPolicy.toDisplayQuantity(new BigDecimal("5")));
  }

  @Test
  public void testToDisplayQuantityZeroStaysZero() {
    assertEquals(0,
        BigDecimal.ZERO.compareTo(ReturnLineQuantityPolicy.toDisplayQuantity(BigDecimal.ZERO)));
  }

  @Test
  public void testToDisplayQuantityNullPassesThrough() {
    assertNull(ReturnLineQuantityPolicy.toDisplayQuantity(null));
  }

  @Test
  public void testToDisplayQuantityIsIdempotent() {
    BigDecimal once = ReturnLineQuantityPolicy.toDisplayQuantity(new BigDecimal("-9"));
    BigDecimal twice = ReturnLineQuantityPolicy.toDisplayQuantity(once);
    assertEquals(once, twice);
  }

  @Test
  public void testStoredThenDisplayRoundTripsToOriginalMagnitude() {
    BigDecimal original = new BigDecimal("12.5");
    BigDecimal stored = ReturnLineQuantityPolicy.toStoredQuantity(original);
    BigDecimal displayed = ReturnLineQuantityPolicy.toDisplayQuantity(stored);
    assertEquals(original, displayed);
  }

  // ── applyStoredSignToWriteBody ────────────────────────────────────────────

  @Test
  public void testApplyStoredSignToWriteBodyNegatesPositiveValue() throws Exception {
    JSONObject body = new JSONObject().put("movementQuantity", 8.0);
    ReturnLineQuantityPolicy.applyStoredSignToWriteBody(body, LOG);
    assertEquals(-8.0, body.getDouble("movementQuantity"), 0.0001);
  }

  @Test
  public void testApplyStoredSignToWriteBodyLeavesAlreadyNegativeValue() throws Exception {
    JSONObject body = new JSONObject().put("movementQuantity", -8.0);
    ReturnLineQuantityPolicy.applyStoredSignToWriteBody(body, LOG);
    assertEquals(-8.0, body.getDouble("movementQuantity"), 0.0001);
  }

  @Test
  public void testApplyStoredSignToWriteBodyNullBodyIsNoOp() {
    // Must not throw.
    ReturnLineQuantityPolicy.applyStoredSignToWriteBody(null, LOG);
  }

  @Test
  public void testApplyStoredSignToWriteBodyNoFieldIsNoOp() throws Exception {
    JSONObject body = new JSONObject().put("product", "prod-1");
    ReturnLineQuantityPolicy.applyStoredSignToWriteBody(body, LOG);
    assertFalse(body.has("movementQuantity"));
  }

  @Test
  public void testApplyStoredSignToWriteBodyNullFieldValueIsNoOp() throws Exception {
    JSONObject body = new JSONObject().put("movementQuantity", JSONObject.NULL);
    ReturnLineQuantityPolicy.applyStoredSignToWriteBody(body, LOG);
    assertTrue(body.isNull("movementQuantity"));
  }

  @Test
  public void testApplyStoredSignToWriteBodyNonNumericValueLeavesValueUnchanged() throws Exception {
    // BigDecimal parsing fails, caught and logged — the field must be left as-is, not
    // corrupted or removed.
    JSONObject body = new JSONObject().put("movementQuantity", "not-a-number");
    ReturnLineQuantityPolicy.applyStoredSignToWriteBody(body, LOG);
    assertEquals("not-a-number", body.getString("movementQuantity"));
  }

  // ── applyDisplaySignToRecords / applyDisplaySignToRecord ──────────────────

  @Test
  public void testApplyDisplaySignToRecordsFlipsEveryRecord() throws Exception {
    JSONArray arr = new JSONArray()
        .put(new JSONObject().put("id", "l1").put("movementQuantity", -3.0))
        .put(new JSONObject().put("id", "l2").put("movementQuantity", -4.0));
    ReturnLineQuantityPolicy.applyDisplaySignToRecords(arr, LOG);
    assertEquals(3.0, arr.getJSONObject(0).getDouble("movementQuantity"), 0.0001);
    assertEquals(4.0, arr.getJSONObject(1).getDouble("movementQuantity"), 0.0001);
  }

  @Test
  public void testApplyDisplaySignToRecordsNullArrayIsNoOp() {
    // Must not throw.
    ReturnLineQuantityPolicy.applyDisplaySignToRecords(null, LOG);
  }

  @Test
  public void testApplyDisplaySignToRecordsSkipsNonObjectEntries() throws Exception {
    JSONArray arr = new JSONArray().put("not-an-object");
    // optJSONObject(i) returns null for a non-object entry — must not throw.
    ReturnLineQuantityPolicy.applyDisplaySignToRecords(arr, LOG);
  }

  @Test
  public void testApplyDisplaySignToRecordNoFieldIsNoOp() throws Exception {
    JSONObject rec = new JSONObject().put("id", "l1");
    ReturnLineQuantityPolicy.applyDisplaySignToRecord(rec, LOG);
    assertFalse(rec.has("movementQuantity"));
  }

  @Test
  public void testApplyDisplaySignToRecordNullFieldValueIsNoOp() throws Exception {
    JSONObject rec = new JSONObject().put("movementQuantity", JSONObject.NULL);
    ReturnLineQuantityPolicy.applyDisplaySignToRecord(rec, LOG);
    assertTrue(rec.isNull("movementQuantity"));
  }
}
