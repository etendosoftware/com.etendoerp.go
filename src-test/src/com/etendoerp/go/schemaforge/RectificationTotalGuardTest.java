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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.codehaus.jettison.json.JSONObject;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.currency.Currency;
import org.openbravo.model.common.invoice.Invoice;

/**
 * Unit tests for {@link RectificationTotalGuard} (ETP-5745 CP-9): completing a rectificativa
 * invoice is refused when it would push the total of every COMPLETED rectificativa against the
 * same origin past the origin's own total. Every other completion keeps running unchanged.
 *
 * <p>Same-currency assumption only (product decision): a rectificativa in a different currency
 * than its origin is out of scope for this guard — see the production class javadoc.
 *
 * @covers com.etendoerp.go.schemaforge.RectificationTotalGuard
 */
public class RectificationTotalGuardTest {

  private static final String INVOICE_ID = "RECT-5745";
  private static final String ORIGIN_ID = "ORIG-5745";
  private static final String EUR = "EUR-CURR-ID";

  private static NeoContext completeAction(String recordId) throws Exception {
    JSONObject body = new JSONObject().put("documentAction", "CO");
    return NeoContext.builder()
        .endpointType(NeoEndpointType.CRUD)
        .httpMethod("PATCH")
        .recordId(recordId)
        .requestBody(body)
        .build();
  }

  private static Currency currency(String id) {
    Currency c = mock(Currency.class);
    when(c.getId()).thenReturn(id);
    when(c.getISOCode()).thenReturn(id);
    return c;
  }

  /**
   * An {@link Invoice} mock carrying everything {@code RectificationTotalGuard} reads off it.
   * {@code currency} is kept as a fixture parameter (every test here uses the same {@link #EUR}
   * currency on both sides) purely to document that these are same-currency scenarios — the guard
   * itself no longer reads {@code getCurrency()} after the multi-currency revert.
   */
  private static Invoice invoice(BigDecimal grandTotal, Currency currency) {
    Invoice inv = mock(Invoice.class);
    when(inv.getGrandTotalAmount()).thenReturn(grandTotal);
    when(inv.getCurrency()).thenReturn(currency);
    return inv;
  }

  /**
   * Wires a {@link Connection} mock answering the guard's two id-only queries (origins, completed
   * rectification ids) by matching a distinctive substring of the SQL text.
   */
  private static Connection connectionAnswering(String originId, List<String> rectificationIds)
      throws SQLException {
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

    // completedRectificationIds: SELECT inv.c_invoice_id FROM c_invoice_reverse r JOIN c_invoice inv ...
    PreparedStatement idsPs = mock(PreparedStatement.class);
    ResultSet idsRs = mock(ResultSet.class);
    when(idsPs.executeQuery()).thenReturn(idsRs);
    if (rectificationIds.isEmpty()) {
      when(idsRs.next()).thenReturn(false);
    } else {
      Boolean[] nexts = new Boolean[rectificationIds.size() + 1];
      for (int i = 0; i < rectificationIds.size(); i++) {
        nexts[i] = true;
      }
      nexts[rectificationIds.size()] = false;
      String first = rectificationIds.get(0);
      String[] rest = rectificationIds.subList(1, rectificationIds.size()).toArray(new String[0]);
      when(idsRs.next()).thenReturn(nexts[0], java.util.Arrays.copyOfRange(nexts, 1, nexts.length));
      when(idsRs.getString(1)).thenReturn(first, rest);
    }
    when(conn.prepareStatement(argThat(sql -> sql != null && sql.contains("SELECT inv.c_invoice_id"))))
        .thenReturn(idsPs);

    return conn;
  }

  private static NeoResponse checkWith(NeoContext context, Connection conn, Invoice origin,
      List<Invoice> rectifications, List<String> rectificationIds) throws Exception {
    try (MockedStatic<OBDal> dal = Mockito.mockStatic(OBDal.class)) {
      OBDal obDal = mock(OBDal.class);
      dal.when(OBDal::getInstance).thenReturn(obDal);
      when(obDal.getConnection()).thenReturn(conn);
      when(obDal.get(Invoice.class, ORIGIN_ID)).thenReturn(origin);
      for (int i = 0; i < rectificationIds.size(); i++) {
        when(obDal.get(Invoice.class, rectificationIds.get(i))).thenReturn(rectifications.get(i));
      }
      return RectificationTotalGuard.checkCompletion(context);
    }
  }

  /**
   * Wires a {@link Connection} mock answering the guard's origins query with MULTIPLE origin
   * ids, plus one completed-rectification-ids query PER origin, returned (via
   * {@code conn.prepareStatement}) in the same order {@code originInvoiceIds} yields the origins
   * — mirroring the production loop in {@code checkCompletion}, which calls
   * {@code sumCompletedRectifications} once per origin, in order.
   */
  private static Connection connectionAnsweringMultiOrigin(List<String> originIds,
      Map<String, List<String>> rectificationIdsByOrigin) throws SQLException {
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

    // completedRectificationIds: one PreparedStatement per origin, in origin order.
    PreparedStatement[] idsStatements = new PreparedStatement[originIds.size()];
    for (int i = 0; i < originIds.size(); i++) {
      List<String> ids = rectificationIdsByOrigin.getOrDefault(originIds.get(i), List.of());
      PreparedStatement idsPs = mock(PreparedStatement.class);
      ResultSet idsRs = mock(ResultSet.class);
      when(idsPs.executeQuery()).thenReturn(idsRs);
      if (ids.isEmpty()) {
        when(idsRs.next()).thenReturn(false);
      } else {
        Boolean[] nexts = new Boolean[ids.size() + 1];
        for (int j = 0; j < ids.size(); j++) {
          nexts[j] = true;
        }
        nexts[ids.size()] = false;
        when(idsRs.next()).thenReturn(nexts[0], Arrays.copyOfRange(nexts, 1, nexts.length));
        when(idsRs.getString(1)).thenReturn(ids.get(0), ids.subList(1, ids.size()).toArray(new String[0]));
      }
      idsStatements[i] = idsPs;
    }
    when(conn.prepareStatement(argThat(sql -> sql != null && sql.contains("SELECT inv.c_invoice_id"))))
        .thenReturn(idsStatements[0], Arrays.copyOfRange(idsStatements, 1, idsStatements.length));

    return conn;
  }

  private static NeoResponse checkWithMultiOrigin(NeoContext context, Connection conn,
      Map<String, Invoice> originsById, Map<String, Invoice> rectificationsById) throws Exception {
    try (MockedStatic<OBDal> dal = Mockito.mockStatic(OBDal.class)) {
      OBDal obDal = mock(OBDal.class);
      dal.when(OBDal::getInstance).thenReturn(obDal);
      when(obDal.getConnection()).thenReturn(conn);
      for (Map.Entry<String, Invoice> e : originsById.entrySet()) {
        when(obDal.get(Invoice.class, e.getKey())).thenReturn(e.getValue());
      }
      for (Map.Entry<String, Invoice> e : rectificationsById.entrySet()) {
        when(obDal.get(Invoice.class, e.getKey())).thenReturn(e.getValue());
      }
      return RectificationTotalGuard.checkCompletion(context);
    }
  }

  // ---------------------------------------------------------------------------
  // multi-origin — a single rectificativa can rectify MORE THAN ONE origin invoice at once
  // (e.g. importing lines from two different source invoices into one rectificativa)
  // ---------------------------------------------------------------------------

  /**
   * One rectificativa (INVOICE_ID, grand total 300) rectifies two distinct origins with
   * different totals; its own total stays within BOTH origins' limits, so completion is allowed.
   */
  @Test
  public void multipleOriginsEachWithinTheirOwnTotalAreNotBlocked() throws Exception {
    String originA = "ORIG-A-5745";
    String originB = "ORIG-B-5745";
    Currency eur = currency(EUR);
    Invoice originAInvoice = invoice(new BigDecimal("363.00"), eur);
    Invoice originBInvoice = invoice(new BigDecimal("400.00"), eur);
    Invoice rectification = invoice(new BigDecimal("300.00"), eur);

    Connection conn = connectionAnsweringMultiOrigin(List.of(originA, originB),
        Map.of(originA, List.of(INVOICE_ID), originB, List.of(INVOICE_ID)));

    Map<String, Invoice> origins = new LinkedHashMap<>();
    origins.put(originA, originAInvoice);
    origins.put(originB, originBInvoice);

    assertNull(checkWithMultiOrigin(completeAction(INVOICE_ID), conn, origins,
        Map.of(INVOICE_ID, rectification)));
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
    Currency eur = currency(EUR);
    Invoice originAInvoice = invoice(new BigDecimal("363.00"), eur); // 300 <= 363: passes
    Invoice originBInvoice = invoice(new BigDecimal("250.00"), eur); // 300 > 250: exceeded
    Invoice rectification = invoice(new BigDecimal("300.00"), eur);

    Connection conn = connectionAnsweringMultiOrigin(List.of(originA, originB),
        Map.of(originA, List.of(INVOICE_ID), originB, List.of(INVOICE_ID)));

    Map<String, Invoice> origins = new LinkedHashMap<>();
    origins.put(originA, originAInvoice);
    origins.put(originB, originBInvoice);

    NeoResponse response = checkWithMultiOrigin(completeAction(INVOICE_ID), conn, origins,
        Map.of(INVOICE_ID, rectification));

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
  // the refusal condition — single currency (no conversion involved)
  // ---------------------------------------------------------------------------

  @Test
  public void invoiceWithNoOriginIsNeverBlocked() throws Exception {
    Connection conn = connectionAnswering(null, List.of());

    assertNull(checkWith(completeAction(INVOICE_ID), conn, null, List.of(), List.of()));
  }

  @Test
  public void rectifiedTotalWithinTheOriginalIsNotBlocked() throws Exception {
    // origin 363 EUR, this rectificativa (same currency) is exactly 363: allowed.
    Currency eur = currency(EUR);
    Invoice origin = invoice(new BigDecimal("363.00"), eur);
    Invoice rect = invoice(new BigDecimal("363.00"), eur);
    Connection conn = connectionAnswering(ORIGIN_ID, List.of(INVOICE_ID));

    assertNull(checkWith(completeAction(INVOICE_ID), conn, origin, List.of(rect), List.of(INVOICE_ID)));
  }

  /** CP-9 repro: origin 363, two rectificativas summing to 726 — over-rectification refused. */
  @Test
  public void rectifiedTotalExceedingTheOriginalIsBlocked() throws Exception {
    Currency eur = currency(EUR);
    Invoice origin = invoice(new BigDecimal("363.00"), eur);
    Invoice rect1 = invoice(new BigDecimal("363.00"), eur);
    Invoice rect2 = invoice(new BigDecimal("363.00"), eur);
    Connection conn = connectionAnswering(ORIGIN_ID, List.of("RECT-OTHER", INVOICE_ID));

    NeoResponse response = checkWith(completeAction(INVOICE_ID), conn, origin,
        List.of(rect1, rect2), List.of("RECT-OTHER", INVOICE_ID));

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
    Currency eur = currency(EUR);
    Invoice origin = invoice(new BigDecimal("-100.00"), eur);
    Invoice rect = invoice(new BigDecimal("-150.00"), eur);
    Connection conn = connectionAnswering(ORIGIN_ID, List.of(INVOICE_ID));

    assertNotNull(checkWith(completeAction(INVOICE_ID), conn, origin, List.of(rect), List.of(INVOICE_ID)));
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

    assertNull(checkWith(completeAction(INVOICE_ID), conn, null, List.of(), List.of()));
  }
}
