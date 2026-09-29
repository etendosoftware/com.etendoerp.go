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
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import org.codehaus.jettison.json.JSONObject;
import org.junit.Test;

/** Unit tests for {@link Fiscal303SnapshotSupport} (ETP-5438). */
public class Fiscal303SnapshotSupportTest {

  private final Fiscal303SnapshotSupport support = new Fiscal303SnapshotSupport();

  @Test
  public void declModelIs303() {
    assertEquals("303", support.declModel());
  }

  /** The live payload is built from the handler's computeBoxes — through a spy of the handler. */
  @Test
  public void computeLivePayloadBuildsTheBoxesResponseFromTheHandler() throws Exception {
    Fiscal303BoxesHandler handler = spy(new Fiscal303BoxesHandler(mock(NeoServlet.class)));
    Map<Integer, BigDecimal> boxes = new HashMap<>();
    boxes.put(27, new BigDecimal("300.00"));
    boxes.put(45, new BigDecimal("100.00"));
    boxes.put(46, new BigDecimal("200.00"));
    Map<String, Object> source = new LinkedHashMap<>();
    source.put("ref", "FV1");
    source.put("base", new BigDecimal("10.00"));
    source.put("party", null);
    doReturn(new Fiscal303BoxesHandler.ComputeResult(boxes, Collections.singletonList(source)))
        .when(handler).computeBoxes("org1", 2026, "T1");

    JSONObject payload = support.computeLivePayload(handler, "org1", 2026, "T1");

    assertEquals("300.00", payload.getJSONObject("boxes").getString("27"));
    assertEquals("300.00", payload.getJSONObject("summary").getString("accrued"));
    assertEquals("100.00", payload.getJSONObject("summary").getString("deductible"));
    assertEquals("200.00", payload.getJSONObject("summary").getString("result"));
    assertEquals("10.00", payload.getJSONArray("sources").getJSONObject(0).getString("base"));
    assertEquals("", payload.getJSONArray("sources").getJSONObject(0).getString("party"));
  }

  /** Missing summary boxes default to zero. */
  @Test
  public void buildResponseDefaultsMissingSummaryBoxesToZero() throws Exception {
    JSONObject payload = Fiscal303SnapshotSupport.buildResponse(new HashMap<>(), Collections.emptyList());
    assertEquals("0", payload.getJSONObject("summary").getString("result"));
    assertEquals(0, payload.getJSONArray("sources").length());
  }

  /** The snapshot drops the per-invoice sources and keeps their count. */
  @Test
  public void toSnapshotReplacesSourcesWithTheirCount() throws Exception {
    JSONObject snapshot = support.toSnapshot(
        new JSONObject("{\"boxes\":{\"7\":\"1\"},\"sources\":[{},{},{}]}"));
    assertFalse(snapshot.has("sources"));
    assertEquals(3, snapshot.getInt("sourceCount"));
  }

  /** A payload without sources still gets a count of zero. */
  @Test
  public void toSnapshotCountsAbsentSourcesAsZero() throws Exception {
    assertEquals(0, support.toSnapshot(new JSONObject("{\"boxes\":{}}")).getInt("sourceCount"));
  }
}
