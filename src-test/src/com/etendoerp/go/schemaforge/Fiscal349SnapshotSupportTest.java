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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.Test;

/** Unit tests for {@link Fiscal349SnapshotSupport} (ETP-5438). */
public class Fiscal349SnapshotSupportTest {

  private final Fiscal349SnapshotSupport support = new Fiscal349SnapshotSupport();

  @Test
  public void declModelIs349() {
    assertEquals("349", support.declModel());
  }

  @Test
  public void computeLivePayloadDelegatesToComputeOperators() throws Exception {
    Fiscal349BoxesHandler handler = spy(new Fiscal349BoxesHandler(mock(NeoServlet.class)));
    JSONObject operators = new JSONObject().put("operators", new JSONArray());
    doReturn(operators).when(handler).computeOperators("org1", 2026, "T1");

    assertSame(operators, support.computeLivePayload(handler, "org1", 2026, "T1"));
  }

  /** A payload without operators is left alone (nothing to fold). */
  @Test
  public void foldWithoutOperatorsIsANoOp() throws Exception {
    JSONObject payload = new JSONObject().put("invoices", new JSONArray());
    support.foldPerInvoiceAggregates(payload);
    assertFalse(payload.has("operators"));
  }

  /**
   * Rectifications with both bases count under both keys; an unknown row type contributes to no
   * key; an invoice of unknown type is grouped but counts as neither purchase nor sale.
   */
  @Test
  public void foldHandlesBothBasesAndUnknownTypes() throws Exception {
    JSONObject payload = new JSONObject();
    payload.put("operators", new JSONArray()
        .put(new JSONObject().put("nif", "PT1").put("key", "A").put("rectificative", true))
        .put(new JSONObject().put("nif", "PT1").put("key", "I").put("rectificative", true))
        .put(new JSONObject().put("nif", "NL2").put("key", "E").put("rectificative", false)));
    payload.put("invoices", new JSONArray()
        .put(new JSONObject().put("nifIva", "NL2").put("key", "E").put("type", "Otro")));
    payload.put("rectifications", new JSONArray()
        .put(new JSONObject().put("nifIva", "PT1").put("type", "Compra")
            .put("baseProducts", "5.00").put("baseServices", "7.00"))
        .put(new JSONObject().put("nifIva", "PT1").put("type", "Otro")
            .put("baseProducts", "9.00")));

    support.foldPerInvoiceAggregates(payload);
    JSONArray ops = payload.getJSONArray("operators");

    assertEquals(1, ops.getJSONObject(0).getInt(Fiscal349SnapshotSupport.ORIGIN_PURCHASES));
    assertEquals(1, ops.getJSONObject(1).getInt(Fiscal349SnapshotSupport.ORIGIN_PURCHASES));
    assertEquals(0, ops.getJSONObject(2).getInt(Fiscal349SnapshotSupport.ORIGIN_PURCHASES));
    assertEquals(0, ops.getJSONObject(2).getInt(Fiscal349SnapshotSupport.ORIGIN_SALES));
  }

  /** The snapshot drops invoices and rectifications and keeps both counts. */
  @Test
  public void toSnapshotReplacesBothListsWithCounts() throws Exception {
    JSONObject snapshot = support.toSnapshot(new JSONObject()
        .put("operators", new JSONArray())
        .put("invoices", new JSONArray().put(new JSONObject()).put(new JSONObject()))
        .put("rectifications", new JSONArray().put(new JSONObject())));
    assertFalse(snapshot.has("invoices"));
    assertFalse(snapshot.has("rectifications"));
    assertEquals(2, snapshot.getInt("invoiceCount"));
    assertEquals(1, snapshot.getInt("rectificationCount"));
  }
}
