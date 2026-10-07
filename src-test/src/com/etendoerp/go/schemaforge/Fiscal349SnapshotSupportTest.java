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
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.Test;
import org.openbravo.model.common.businesspartner.BusinessPartner;
import org.openbravo.model.common.invoice.Invoice;

/**
 * Unit tests for {@link Fiscal349SnapshotSupport} (ETP-5438).
 *
 * @covers com.etendoerp.go.schemaforge.Fiscal349SnapshotSupport
 */
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

  /**
   * ETP-5597: a sale mixing goods (E) and services (S) produces one "Facturas origen" row per key
   * (built by the real collectInvoices), so the same invoice counts as origin of BOTH operator
   * rows of its partner — nif|E and nif|S — instead of leaving one of them at "—".
   */
  @Test
  public void foldCountsAMixedInvoiceUnderBothOfItsKeys() throws Exception {
    JSONObject payload = mixedSalePayload();

    support.foldPerInvoiceAggregates(payload);
    JSONArray ops = payload.getJSONArray("operators");

    for (int i = 0; i < 2; i++) {
      assertEquals(1, ops.getJSONObject(i).getInt(Fiscal349SnapshotSupport.ORIGIN_SALES));
      assertEquals(0, ops.getJSONObject(i).getInt(Fiscal349SnapshotSupport.ORIGIN_PURCHASES));
    }
  }

  /**
   * ETP-5597: the snapshot's invoiceCount counts origin rows, not distinct invoices — a mixed E+S
   * sale is two rows (one per key), matching the live "Facturas origen" badge, and both operator
   * rows keep their folded origin count.
   */
  @Test
  public void toSnapshotCountsAMixedInvoiceOncePerKey() throws Exception {
    JSONObject snapshot = support.toSnapshot(mixedSalePayload());

    assertFalse(snapshot.has("invoices"));
    assertEquals(2, snapshot.getInt("invoiceCount"));
    assertEquals(0, snapshot.getInt("rectificationCount"));
    JSONArray ops = snapshot.getJSONArray("operators");
    for (int i = 0; i < 2; i++) {
      assertEquals(1, ops.getJSONObject(i).getInt(Fiscal349SnapshotSupport.ORIGIN_SALES));
    }
  }

  /**
   * One sale of partner FR1 mixing goods (E 600) and services (S 400), with its origin rows built
   * by the real {@link Fiscal349BoxesHandler#collectInvoices}, and the two operator rows nif|E and
   * nif|S it backs.
   */
  private static JSONObject mixedSalePayload() throws Exception {
    BusinessPartner bp = mock(BusinessPartner.class);
    when(bp.getTaxID()).thenReturn("FR1");
    Invoice inv = mock(Invoice.class);
    when(inv.getId()).thenReturn("s1");
    when(inv.getBusinessPartner()).thenReturn(bp);
    Map<String, BigDecimal> bases = new LinkedHashMap<>();
    bases.put("E", new BigDecimal("600"));
    bases.put("S", new BigDecimal("400"));
    JSONArray invoices = new Fiscal349BoxesHandler(mock(NeoServlet.class)).collectInvoices(
        Collections.<Invoice>emptySet(), Collections.singleton(inv),
        Collections.singletonMap("s1", bases));

    return new JSONObject()
        .put("operators", new JSONArray()
            .put(new JSONObject().put("nif", "FR1").put("key", "E"))
            .put(new JSONObject().put("nif", "FR1").put("key", "S")))
        .put("invoices", invoices);
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
