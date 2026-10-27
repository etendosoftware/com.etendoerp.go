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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.codehaus.jettison.json.JSONObject;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.dal.service.OBDal;

/**
 * Unit tests for {@link RectificationTotalGuard} (ETP-5745 CP-9): completing a rectificativa
 * invoice is refused when it would push the total of every COMPLETED rectificativa against the
 * same origin past the origin's own total. Every other completion keeps running unchanged.
 *
 * <p>Same-currency assumption only (product decision): a rectificativa in a different currency
 * than its origin is out of scope for this guard — see the production class javadoc. The guard
 * is plain aggregate SQL end to end, so these tests mock only {@link Connection}/
 * {@link PreparedStatement}/{@link ResultSet} — no DAL entity reads.
 *
 * @covers com.etendoerp.go.schemaforge.RectificationTotalGuard
 */
public class RectificationTotalGuardTest {

  private static final String INVOICE_ID = "RECT-5745";
  private static final String ORIGIN_ID = "ORIG-5745";

  private static NeoContext completeAction(String recordId) throws Exception {
    JSONObject body = new JSONObject().put("documentAction", "CO");
    return NeoContext.builder()
        .endpointType(NeoEndpointType.CRUD)
        .httpMethod("PATCH")
        .recordId(recordId)
        .requestBody(body)
        .build();
  }

  /**
   * Wires a {@link Connection} mock that answers each of the guard's three queries by matching a
   * distinctive substring of the SQL text, so one fixture drives all three without caring about
   * call order.
   */
  private static Connection connectionAnswering(String originId, BigDecimal originTotal,
      BigDecimal rectifiedSum) throws SQLException {
    Connection conn = mock(Connection.class);

    // originInvoiceIds: SELECT DISTINCT reversed_c_invoice_id FROM c_invoice_reverse WHERE c_invoice_id = ?
    PreparedStatement originsPs = mock(PreparedStatement.class);
    ResultSet originsRs = mock(ResultSet.class);
    when(originsPs.executeQuery()).thenReturn(originsRs);
    if (originId != null) {
      when(originsRs.next()).thenReturn(true, false);
      when(originsRs.getString(1)).thenReturn(originId);
    } else {
      when(originsRs.next()).thenReturn(false);
    }
    when(conn.prepareStatement(argThat(sql -> sql != null && sql.contains("reversed_c_invoice_id FROM c_invoice_reverse"))))
        .thenReturn(originsPs);

    // absGrandTotal: SELECT grandtotal FROM c_invoice WHERE c_invoice_id = ?
    PreparedStatement totalPs = mock(PreparedStatement.class);
    ResultSet totalRs = mock(ResultSet.class);
    when(totalPs.executeQuery()).thenReturn(totalRs);
    if (originTotal != null) {
      when(totalRs.next()).thenReturn(true);
      when(totalRs.getBigDecimal(1)).thenReturn(originTotal);
    } else {
      when(totalRs.next()).thenReturn(false);
    }
    when(conn.prepareStatement(argThat(sql -> sql != null && sql.startsWith("SELECT grandtotal FROM c_invoice"))))
        .thenReturn(totalPs);

    // sumCompletedRectifications: SELECT COALESCE(SUM(ABS(inv.grandtotal)), 0) ...
    PreparedStatement sumPs = mock(PreparedStatement.class);
    ResultSet sumRs = mock(ResultSet.class);
    when(sumPs.executeQuery()).thenReturn(sumRs);
    when(sumRs.next()).thenReturn(true);
    when(sumRs.getBigDecimal(1)).thenReturn(rectifiedSum);
    when(conn.prepareStatement(argThat(sql -> sql != null && sql.contains("SUM(ABS(inv.grandtotal))"))))
        .thenReturn(sumPs);

    return conn;
  }

  private static NeoResponse checkWith(NeoContext context, Connection conn) throws Exception {
    try (MockedStatic<OBDal> dal = Mockito.mockStatic(OBDal.class)) {
      OBDal obDal = mock(OBDal.class);
      dal.when(OBDal::getInstance).thenReturn(obDal);
      when(obDal.getConnection()).thenReturn(conn);
      return RectificationTotalGuard.checkCompletion(context);
    }
  }

  /**
   * Wires a {@link Connection} mock answering the origins query with MULTIPLE origin ids, plus
   * one {@code absGrandTotal} statement and one {@code sumCompletedRectifications} statement PER
   * origin, returned (via {@code conn.prepareStatement}) in the same order {@code checkCompletion}
   * evaluates them — mirroring the production loop, which calls {@code absGrandTotal} then
   * {@code sumCompletedRectifications} once per origin, in order.
   */
  private static Connection connectionAnsweringMultiOrigin(List<String> originIds,
      Map<String, BigDecimal> originTotalsById, Map<String, BigDecimal> rectifiedSumsById)
      throws SQLException {
    Connection conn = mock(Connection.class);

    // originInvoiceIds: SELECT DISTINCT reversed_c_invoice_id FROM c_invoice_reverse WHERE c_invoice_id = ?
    PreparedStatement originsPs = mock(PreparedStatement.class);
    ResultSet originsRs = mock(ResultSet.class);
    when(originsPs.executeQuery()).thenReturn(originsRs);
    Boolean[] originNexts = new Boolean[originIds.size() + 1];
    for (int i = 0; i < originIds.size(); i++) {
      originNexts[i] = true;
    }
    originNexts[originIds.size()] = false;
    when(originsRs.next()).thenReturn(originNexts[0], Arrays.copyOfRange(originNexts, 1, originNexts.length));
    when(originsRs.getString(1)).thenReturn(originIds.get(0),
        originIds.subList(1, originIds.size()).toArray(new String[0]));
    when(conn.prepareStatement(argThat(sql -> sql != null && sql.contains("reversed_c_invoice_id FROM c_invoice_reverse"))))
        .thenReturn(originsPs);

    // absGrandTotal: one PreparedStatement per origin, in origin order.
    PreparedStatement[] totalStatements = new PreparedStatement[originIds.size()];
    for (int i = 0; i < originIds.size(); i++) {
      PreparedStatement totalPs = mock(PreparedStatement.class);
      ResultSet totalRs = mock(ResultSet.class);
      when(totalPs.executeQuery()).thenReturn(totalRs);
      when(totalRs.next()).thenReturn(true);
      when(totalRs.getBigDecimal(1)).thenReturn(originTotalsById.get(originIds.get(i)));
      totalStatements[i] = totalPs;
    }
    when(conn.prepareStatement(argThat(sql -> sql != null && sql.startsWith("SELECT grandtotal FROM c_invoice"))))
        .thenReturn(totalStatements[0], Arrays.copyOfRange(totalStatements, 1, totalStatements.length));

    // sumCompletedRectifications: one PreparedStatement per origin, in origin order.
    PreparedStatement[] sumStatements = new PreparedStatement[originIds.size()];
    for (int i = 0; i < originIds.size(); i++) {
      PreparedStatement sumPs = mock(PreparedStatement.class);
      ResultSet sumRs = mock(ResultSet.class);
      when(sumPs.executeQuery()).thenReturn(sumRs);
      when(sumRs.next()).thenReturn(true);
      when(sumRs.getBigDecimal(1)).thenReturn(rectifiedSumsById.get(originIds.get(i)));
      sumStatements[i] = sumPs;
    }
    when(conn.prepareStatement(argThat(sql -> sql != null && sql.contains("SUM(ABS(inv.grandtotal))"))))
        .thenReturn(sumStatements[0], Arrays.copyOfRange(sumStatements, 1, sumStatements.length));

    return conn;
  }

  // ---------------------------------------------------------------------------
  // multi-origin — a single rectificativa can rectify MORE THAN ONE origin invoice at once
  // (e.g. importing lines from two different source invoices into one rectificativa)
  // ---------------------------------------------------------------------------

  /**
   * One rectificativa rectifies two distinct origins with different totals; its own total (300)
   * stays within BOTH origins' limits, so completion is allowed.
   */
  @Test
  public void multipleOriginsEachWithinTheirOwnTotalAreNotBlocked() throws Exception {
    String originA = "ORIG-A-5745";
    String originB = "ORIG-B-5745";

    Connection conn = connectionAnsweringMultiOrigin(List.of(originA, originB),
        Map.of(originA, new BigDecimal("363.00"), originB, new BigDecimal("400.00")),
        Map.of(originA, new BigDecimal("300.00"), originB, new BigDecimal("300.00")));

    assertNull(checkWith(completeAction(INVOICE_ID), conn));
  }

  /**
   * Same rectificativa (total 300) against two origins, but the SECOND origin's own total (250)
   * is lower than 300: the loop must still evaluate origin B after origin A passed, and refuse
   * naming origin B specifically — proving every origin in the plural loop is actually checked,
   * not just the first.
   */
  @Test
  public void multipleOriginsOneExceededIsBlockedNamingThatOrigin() throws Exception {
    String originA = "ORIG-A-5745";
    String originB = "ORIG-B-5745";

    Connection conn = connectionAnsweringMultiOrigin(List.of(originA, originB),
        Map.of(originA, new BigDecimal("363.00"), originB, new BigDecimal("250.00")),
        Map.of(originA, new BigDecimal("300.00"), originB, new BigDecimal("300.00")));

    NeoResponse response = checkWith(completeAction(INVOICE_ID), conn);

    assertNotNull("origin B's own total (250) is exceeded by the 300 rectified — expected a refusal",
        response);
    assertEquals(422, response.getHttpStatus());
    JSONObject body = response.getBody();
    JSONObject messageParams = body.getJSONObject("messageParams");
    assertEquals(originB, messageParams.getString("originInvoiceId"));
    assertEquals(0, new BigDecimal(messageParams.getString("originTotal")).compareTo(new BigDecimal("250.00")));
    assertEquals(0, new BigDecimal(messageParams.getString("rectifiedTotal")).compareTo(new BigDecimal("300.00")));
  }

  // ---------------------------------------------------------------------------
  // gating — only a documentAction=CO completion request is evaluated
  // ---------------------------------------------------------------------------

  @Test
  public void aNonCompletionPatchIsNeverEvaluated() throws Exception {
    JSONObject body = new JSONObject().put("documentAction", "RE");
    NeoContext context = NeoContext.builder()
        .endpointType(NeoEndpointType.CRUD).httpMethod("PATCH").recordId(INVOICE_ID)
        .requestBody(body).build();

    assertNull(RectificationTotalGuard.checkCompletion(context));
  }

  @Test
  public void aBlankRecordIdIsNeverEvaluated() throws Exception {
    JSONObject body = new JSONObject().put("documentAction", "CO");
    NeoContext context = NeoContext.builder()
        .endpointType(NeoEndpointType.CRUD).httpMethod("PATCH").recordId(null)
        .requestBody(body).build();

    assertNull(RectificationTotalGuard.checkCompletion(context));
  }

  // ---------------------------------------------------------------------------
  // the refusal condition
  // ---------------------------------------------------------------------------

  @Test
  public void invoiceWithNoOriginIsNeverBlocked() throws Exception {
    Connection conn = connectionAnswering(null, null, BigDecimal.ZERO);

    assertNull(checkWith(completeAction(INVOICE_ID), conn));
  }

  @Test
  public void rectifiedTotalWithinTheOriginalIsNotBlocked() throws Exception {
    // origin 363, this rectificativa (plus any other completed one) sums to exactly 363: allowed.
    Connection conn = connectionAnswering(ORIGIN_ID, new BigDecimal("363.00"), new BigDecimal("363.00"));

    assertNull(checkWith(completeAction(INVOICE_ID), conn));
  }

  /** CP-9 repro: origin 363, two rectificativas summing to 726 — over-rectification refused. */
  @Test
  public void rectifiedTotalExceedingTheOriginalIsBlocked() throws Exception {
    Connection conn = connectionAnswering(ORIGIN_ID, new BigDecimal("363.00"), new BigDecimal("726.00"));

    NeoResponse response = checkWith(completeAction(INVOICE_ID), conn);

    assertNotNull("expected a refusal", response);
    assertEquals(422, response.getHttpStatus());
    JSONObject body = response.getBody();
    assertFalse(body.getBoolean("success"));
    assertEquals(RectificationTotalGuard.MSG_RECTIFICATION_EXCEEDS_ORIGINAL,
        body.getJSONArray("messageKeys").getString(0));
    JSONObject messageParams = body.getJSONObject("messageParams");
    assertEquals(ORIGIN_ID, messageParams.getString("originInvoiceId"));
    assertTrue(body.getString("message").contains("exceed"));
  }

  @Test
  public void aNegativeOriginalIsComparedByAbsoluteValue() throws Exception {
    // a credit-note-style original (-100) rectified by -150: still over-rectification.
    Connection conn = connectionAnswering(ORIGIN_ID, new BigDecimal("-100.00"), new BigDecimal("150.00"));

    assertNotNull(checkWith(completeAction(INVOICE_ID), conn));
  }

  // ---------------------------------------------------------------------------
  // fail-open — a read error must never block, never throw
  // ---------------------------------------------------------------------------

  @Test
  public void failsOpenWhenTheConnectionCannotBeObtained() throws Exception {
    try (MockedStatic<OBDal> dal = Mockito.mockStatic(OBDal.class)) {
      OBDal obDal = mock(OBDal.class);
      dal.when(OBDal::getInstance).thenReturn(obDal);
      when(obDal.getConnection()).thenThrow(new IllegalStateException("no DAL"));

      assertNull(RectificationTotalGuard.checkCompletion(completeAction(INVOICE_ID)));
    }
  }

  @Test
  public void failsOpenWhenAQueryThrows() throws Exception {
    Connection conn = mock(Connection.class);
    when(conn.prepareStatement(anyString())).thenThrow(new SQLException("connection closed"));

    assertNull(checkWith(completeAction(INVOICE_ID), conn));
  }
}
