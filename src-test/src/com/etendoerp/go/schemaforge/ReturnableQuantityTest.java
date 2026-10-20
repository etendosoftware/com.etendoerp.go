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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONObject;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.ModelProvider;
import org.openbravo.base.model.Property;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.materialmgmt.transaction.ShipmentInOut;
import org.openbravo.model.materialmgmt.transaction.ShipmentInOutLine;

/**
 * Unit tests for {@link ReturnableQuantity}, the one rule of how much of a receipt / shipment line
 * a return may still take (ETP-5711). The handlers' tests cover each path end to end; these pin the
 * rule itself: what counts as already returned, the exclusion of the line being edited, the
 * accumulation of duplicates and the shape of the 422.
 *
 * @covers com.etendoerp.go.schemaforge.ReturnableQuantity
 * @covers com.etendoerp.go.schemaforge.ReturnFromDocumentSupport
 */
public class ReturnableQuantityTest {

  private static final Logger LOG = LogManager.getLogger(ReturnableQuantityTest.class);

  private static BigDecimal bd(String value) {
    return new BigDecimal(value);
  }

  // ── what counts as already returned ───────────────────────────────────────

  /**
   * "Already returned" is every non-voided return line linked to the source line, DRAFTS INCLUDED
   * (the filter is on the voided status only), and the exclusion adds a second parameter.
   */
  @Test
  public void alreadyReturned_countsNonVoidedLinesDraftsIncluded_andBindsTheExclusion()
      throws Exception {
    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      Connection conn = ReturnedQuantityTestSupport.stubReturnedQuantities(dal,
          (src, excl) -> bd("3"));

      assertEquals(0, bd("3").compareTo(ReturnableQuantity.alreadyReturned("src-1", null)));
      assertEquals(0, bd("3").compareTo(ReturnableQuantity.alreadyReturned("src-1", "own-1")));

      ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
      verify(conn, Mockito.times(2)).prepareStatement(sql.capture());
      String withoutExclusion = sql.getAllValues().get(0);
      String withExclusion = sql.getAllValues().get(1);
      assertTrue(withoutExclusion, withoutExclusion.contains("DocStatus NOT IN ('VO')"));
      assertTrue("a draft return counts as returned", !withoutExclusion.contains("'DR'"));
      assertTrue(withoutExclusion, withoutExclusion.contains("rl.Canceled_Inoutline_ID = ?"));
      assertTrue(withoutExclusion, !withoutExclusion.contains("M_InOutLine_ID <>"));
      assertTrue(withExclusion, withExclusion.contains("rl.M_InOutLine_ID <> ?"));
    }
  }

  @Test
  public void alreadyReturned_aNullSumIsZero() throws Exception {
    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      ReturnedQuantityTestSupport.stubReturnedQuantities(dal, (src, excl) -> null);

      assertEquals(0, ReturnableQuantity.alreadyReturned("src-1", null).signum());
    }
  }

  @Test
  public void available_isWhatTheSourceMovedMinusWhatIsAlreadyReturned() throws Exception {
    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      ReturnedQuantityTestSupport.stubReturnedQuantities(dal, (src, excl) -> bd("6"));
      ShipmentInOutLine source = ReturnedQuantityTestSupport.sourceLine("src-1", "Agua", "10");

      assertEquals(0, bd("4").compareTo(ReturnableQuantity.available(source, null)));
    }
  }

  /** A source line with no quantity has nothing to return. */
  @Test
  public void available_aSourceLineWithoutQuantityHasNothing() throws Exception {
    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      ReturnedQuantityTestSupport.stubReturnedQuantities(dal, (src, excl) -> BigDecimal.ZERO);
      ShipmentInOutLine source = mock(ShipmentInOutLine.class);
      when(source.getId()).thenReturn("src-1");
      when(source.getMovementQuantity()).thenReturn(null);

      assertEquals(0, ReturnableQuantity.available(source, null).signum());
    }
  }

  // ── a request's quantities ────────────────────────────────────────────────

  @Test
  public void requests_sumTheSameSourceLineAndKeepAbsoluteValues() {
    ReturnableQuantity.Requests requests = new ReturnableQuantity.Requests();
    ShipmentInOutLine source = ReturnedQuantityTestSupport.sourceLine("src-1", "Agua", "10");

    requests.add(source, bd("3"));
    requests.add(source, bd("-2"));

    assertEquals(0, bd("5").compareTo(requests.quantity("src-1")));
    assertEquals(1, requests.sourceLineIds().size());
  }

  @Test
  public void refuseIfExceeds_acceptsExactlyTheRemainderAndRefusesOneUnitMore() throws Exception {
    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      ReturnedQuantityTestSupport.stubReturnedQuantities(dal, (src, excl) -> bd("6"));
      ShipmentInOutLine source = ReturnedQuantityTestSupport.sourceLine("src-1", "Agua", "10");

      ReturnableQuantity.Requests exact = new ReturnableQuantity.Requests();
      exact.add(source, bd("4"));
      assertNull(ReturnableQuantity.refuseIfExceeds(exact));

      ReturnableQuantity.Requests over = new ReturnableQuantity.Requests();
      over.add(source, bd("4.001"));
      NeoResponse refusal = ReturnableQuantity.refuseIfExceeds(over);
      assertNotNull(refusal);
      assertEquals(422, refusal.getHttpStatus());
    }
  }

  // ── the 422 ───────────────────────────────────────────────────────────────

  @Test
  public void exceeds_namesProductRequestedAndAvailable_inTheModulesErrorShape() throws Exception {
    ShipmentInOutLine source = ReturnedQuantityTestSupport.sourceLine("src-1", "Agua", "10");

    NeoResponse response = ReturnableQuantity.exceeds(source, bd("5.000"), bd("4"));

    assertEquals(422, response.getHttpStatus());
    JSONObject error = response.getBody().getJSONObject("error");
    assertEquals(422, error.getInt("status"));
    assertEquals("returnQuantityExceedsAvailable", error.getString("code"));
    assertEquals("Cannot return 5 of Agua: only 4 can still be returned from this line "
        + "(10 on the source document).", error.getString("message"));
    assertEquals("Agua", error.getString("product"));
    assertEquals(5, error.getDouble("requested"), 0.0001);
    assertEquals(4, error.getDouble("available"), 0.0001);
    assertEquals("src-1", error.getString("sourceLineId"));
  }

  @Test
  public void exceeds_withNothingLeftSaysSo_andNeverShowsANegativeAvailable() throws Exception {
    ShipmentInOutLine source = ReturnedQuantityTestSupport.sourceLine("src-1", "Agua", "10");

    NeoResponse response = ReturnableQuantity.exceeds(source, bd("1"), bd("-2"));

    JSONObject error = response.getBody().getJSONObject("error");
    assertTrue(error.getString("message"), error.getString("message").contains("nothing is left"));
    assertEquals(0, error.getDouble("available"), 0.0001);
  }

  /** Large round quantities print without an exponent. */
  @Test
  public void exceeds_printsRoundQuantitiesWithoutExponent() throws Exception {
    ShipmentInOutLine source = ReturnedQuantityTestSupport.sourceLine("src-1", "Agua", "1000");

    NeoResponse response = ReturnableQuantity.exceeds(source, bd("2E+3"), bd("1000"));

    String message = response.getBody().getJSONObject("error").getString("message");
    assertTrue(message, message.startsWith("Cannot return 2000 of Agua: only 1000 can"));
  }

  @Test
  public void productName_fallsBackToTheLineIdWhenThereIsNoProduct() {
    ShipmentInOutLine source = mock(ShipmentInOutLine.class);
    when(source.getId()).thenReturn("src-9");
    when(source.getProduct()).thenReturn(null);

    assertEquals("src-9", ReturnableQuantity.productName(source));
  }

  // ── quantity edit of an existing return line ──────────────────────────────

  private static NeoContext edit(String method, String recordId, JSONObject body) {
    return NeoContext.builder().httpMethod(method).endpointType(NeoEndpointType.CRUD)
        .recordId(recordId).requestBody(body).build();
  }

  @Test
  public void refuseQuantityEdit_ignoresEverythingThatIsNotAQuantityEditOfARecord() throws Exception {
    JSONObject withQuantity = new JSONObject().put("movementQuantity", 9);
    NeoContext create = edit("POST", null, withQuantity);
    NeoContext get = edit("GET", "l1", null);
    NeoContext noRecord = edit("PATCH", null, withQuantity);
    NeoContext noQuantity = edit("PATCH", "l1", new JSONObject().put("description", "x"));
    NeoContext action = NeoContext.builder().httpMethod("PATCH")
        .endpointType(NeoEndpointType.ACTION).recordId("l1").requestBody(withQuantity).build();
    NeoContext notANumber = edit("PUT", "l1", new JSONObject().put("movementQuantity", "abc"));

    for (NeoContext ctx : new NeoContext[] {null, create, get, noRecord, noQuantity, action,
        notANumber}) {
      assertNull(ReturnableQuantity.refuseQuantityEdit(ctx, LOG));
    }
  }

  /** A return line with no link to a source line has nothing to be capped against. */
  @Test
  public void refuseQuantityEdit_aLineWithoutSourceIsNotCapped() throws Exception {
    try (MockedStatic<OBContext> ignored = Mockito.mockStatic(OBContext.class);
         MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      ShipmentInOutLine loose = mock(ShipmentInOutLine.class);
      when(loose.getCanceledInoutLine()).thenReturn(null);
      when(dal.get(ShipmentInOutLine.class, "l1")).thenReturn(loose);

      assertNull(ReturnableQuantity.refuseQuantityEdit(
          edit("PATCH", "l1", new JSONObject().put("movementQuantity", 99)), LOG));
      verify(dal, never()).getConnection();
    }
  }

  /** A cap that cannot be verified is not skipped: the edit is refused with a 500. */
  @Test
  public void refuseQuantityEdit_failsClosedWhenTheCheckCannotRun() throws Exception {
    try (MockedStatic<OBContext> ignored = Mockito.mockStatic(OBContext.class);
         MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      ShipmentInOutLine own = mock(ShipmentInOutLine.class);
      ShipmentInOutLine source = ReturnedQuantityTestSupport.sourceLine("src-1", "Agua", "10");
      when(own.getCanceledInoutLine()).thenReturn(source);
      when(dal.get(ShipmentInOutLine.class, "l1")).thenReturn(own);
      Connection conn = mock(Connection.class);
      when(dal.getConnection()).thenReturn(conn);
      when(conn.prepareStatement(anyString())).thenThrow(new SQLException("down"));

      NeoResponse response = ReturnableQuantity.refuseQuantityEdit(
          edit("PATCH", "l1", new JSONObject().put("movementQuantity", 1)), LOG);

      assertNotNull(response);
      assertEquals(500, response.getHttpStatus());
    }
  }

  /** The line being edited is excluded, so editing to its own current quantity always passes. */
  @Test
  public void refuseQuantityEdit_excludesTheEditedLineFromWhatIsAlreadyReturned() throws Exception {
    List<String> excluded = new ArrayList<>();

    NeoResponse sameAsNow = ReturnedQuantityTestSupport.editLineQuantity(
        new ReturnToVendorShipmentLineHandler(), "PUT", "4", "10", "6", "4", excluded);

    assertNull("10 moved, 6 on other returns, this line holds 4: keeping 4 is fine", sameAsNow);
    assertEquals(List.of("ret-line-1"), excluded);
  }

  // ── the lock that closes the race on the cap (W1) ─────────────────────────

  /**
   * Wires {@code dal.getConnection()} so every statement appends {@code lock:<id>} or
   * {@code sum:<id>} to {@code events} when its first parameter is bound: the order the database
   * sees the statements in is the whole point of the lock.
   */
  private static void recordStatements(OBDal dal, List<String> events) throws Exception {
    Connection conn = mock(Connection.class);
    when(dal.getConnection()).thenReturn(conn);
    when(conn.prepareStatement(anyString())).thenAnswer(inv -> {
      String sql = inv.getArgument(0);
      String kind = sql.contains("FOR NO KEY UPDATE") ? "lock"
          : ReturnedQuantityTestSupport.isReturnedQuantityQuery(sql) ? "sum" : "other";
      PreparedStatement ps = mock(PreparedStatement.class);
      ResultSet rs = mock(ResultSet.class);
      when(ps.executeQuery()).thenReturn(rs);
      when(rs.next()).thenReturn(true);
      Mockito.doAnswer(call -> {
        if ((int) call.getArgument(0) == 1) {
          events.add(kind + ":" + call.getArgument(1));
        }
        return null;
      }).when(ps).setString(Mockito.anyInt(), anyString());
      return ps;
    });
  }

  /**
   * Two creates (or imports) on the same source line must not both read "nothing returned yet": the
   * source lines are locked, in sorted id order so two requests never wait on each other, BEFORE the
   * returned total is read.
   */
  @Test
  public void refuseIfExceeds_locksTheSourceLinesInSortedOrderBeforeReadingTheTotal()
      throws Exception {
    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      List<String> events = new ArrayList<>();
      recordStatements(dal, events);
      ReturnableQuantity.Requests requests = new ReturnableQuantity.Requests();
      requests.add(ReturnedQuantityTestSupport.sourceLine("src-b", "Agua", "10"), bd("1"));
      requests.add(ReturnedQuantityTestSupport.sourceLine("src-a", "Sal", "10"), bd("1"));

      assertNull(ReturnableQuantity.refuseIfExceeds(requests));

      assertEquals(List.of("lock:src-a", "lock:src-b", "sum:src-b", "sum:src-a"), events);
    }
  }

  /** The lock is a row lock on the source M_InOutLine, taken in the DAL's own connection. */
  @Test
  public void lockSourceLines_isAForUpdateOnTheSourceLineRow() throws Exception {
    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      List<String> events = new ArrayList<>();
      recordStatements(dal, events);

      ReturnableQuantity.lockSourceLines(List.of("src-2", "src-1", "src-2"));

      assertEquals("a duplicated id is locked once", List.of("lock:src-1", "lock:src-2"), events);
      ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
      verify(dal.getConnection()).prepareStatement(sql.capture());
      assertTrue(sql.getValue(), sql.getValue().contains("FROM M_InOutLine"));
      assertTrue(sql.getValue(), sql.getValue().contains("WHERE M_InOutLine_ID = ?"));
      assertTrue(sql.getValue(), sql.getValue().endsWith("FOR NO KEY UPDATE"));
    }
  }

  /** A quantity edit locks the line's source before it reads what the other returns hold. */
  @Test
  public void refuseQuantityEdit_locksTheSourceLineBeforeReadingTheTotal() throws Exception {
    try (MockedStatic<OBContext> ignored = Mockito.mockStatic(OBContext.class);
         MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      List<String> events = new ArrayList<>();
      recordStatements(dal, events);
      ShipmentInOutLine own = mock(ShipmentInOutLine.class);
      ShipmentInOutLine source = ReturnedQuantityTestSupport.sourceLine("src-1", "Agua", "10");
      when(own.getCanceledInoutLine()).thenReturn(source);
      when(dal.get(ShipmentInOutLine.class, "l1")).thenReturn(own);

      assertNull(ReturnableQuantity.refuseQuantityEdit(
          edit("PATCH", "l1", new JSONObject().put("movementQuantity", 1)), LOG));

      assertEquals(List.of("lock:src-1", "sum:src-1"), events);
    }
  }

  /** "Return everything" reads the available quantities too, so it locks first as well. */
  @Test
  public void plan_returnEverythingLocksEveryEligibleLineBeforeReadingTheTotals() throws Exception {
    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      List<String> events = new ArrayList<>();
      recordStatements(dal, events);
      ShipmentInOut source = ReturnedQuantityTestSupport.completedSource("rcp-1",
          ReturnedQuantityTestSupport.sourceLine("src-b", "Agua", "10"),
          ReturnedQuantityTestSupport.sourceLine("src-a", "Sal", "10"));

      ReturnFromDocumentSupport.Plan plan = ReturnFromDocumentSupport.plan(source, new JSONObject());

      assertNull(plan.refusal);
      assertEquals(List.of("lock:src-a", "lock:src-b", "sum:src-b", "sum:src-a"), events);
    }
  }

  // ── direct creation of a return line is refused (W2) ──────────────────────

  @Test
  public void refuseDirectCreate_answers422WithTheCodeAndTheTwoWaysIn() throws Exception {
    NeoContext create = edit("POST", null, new JSONObject().put("product", "p"));

    NeoResponse refusal = ReturnableQuantity.refuseDirectCreate(create, "importReceiptLines",
        "createPurchaseReturn");

    assertNotNull(refusal);
    assertEquals(422, refusal.getHttpStatus());
    JSONObject error = refusal.getBody().getJSONObject("error");
    assertEquals("returnLineRequiresImport", error.getString("code"));
    assertTrue(error.getString("message"), error.getString("message").contains("importReceiptLines"));
    assertTrue(error.getString("message"), error.getString("message").contains("createPurchaseReturn"));
  }

  @Test
  public void refuseDirectCreate_onlyRefusesTheCrudPost() throws Exception {
    JSONObject body = new JSONObject().put("movementQuantity", 1);
    NeoContext patch = edit("PATCH", "l1", body);
    NeoContext put = edit("PUT", "l1", body);
    NeoContext get = edit("GET", null, null);
    NeoContext callout = NeoContext.builder().httpMethod("POST")
        .endpointType(NeoEndpointType.CALLOUT).requestBody(body).build();
    NeoContext action = NeoContext.builder().httpMethod("POST")
        .endpointType(NeoEndpointType.ACTION).requestBody(body).build();

    for (NeoContext ctx : new NeoContext[] {null, patch, put, get, callout, action}) {
      assertNull(ReturnableQuantity.refuseDirectCreate(ctx, "a", "b"));
    }
  }

  // ── the reason is kept as the description: bounded by the AD column length ─

  private static void withDescriptionLength(int length, ThrowingRunnable body) throws Exception {
    try (MockedStatic<ModelProvider> mp = Mockito.mockStatic(ModelProvider.class)) {
      ModelProvider provider = mock(ModelProvider.class);
      Entity entity = mock(Entity.class);
      Property property = mock(Property.class);
      mp.when(ModelProvider::getInstance).thenReturn(provider);
      when(provider.getEntity(ShipmentInOut.ENTITY_NAME)).thenReturn(entity);
      when(entity.getProperty("description")).thenReturn(property);
      when(property.getFieldLength()).thenReturn(length);
      body.run();
    }
  }

  /** Test-local checked runnable. */
  private interface ThrowingRunnable {
    void run() throws Exception;
  }

  @Test
  public void plan_refusesAReasonLongerThanTheAdLengthOfTheDescription() throws Exception {
    ShipmentInOut source = ReturnedQuantityTestSupport.completedSource("rcp-1",
        ReturnedQuantityTestSupport.sourceLine("src-1", "Agua", "10"));
    withDescriptionLength(10, () -> {
      ReturnFromDocumentSupport.Plan tooLong = ReturnFromDocumentSupport.plan(source,
          new JSONObject().put("reason", "12345678901"));

      assertNotNull(tooLong.refusal);
      assertEquals(422, tooLong.refusal.getHttpStatus());
      JSONObject error = tooLong.refusal.getBody().getJSONObject("error");
      assertEquals("returnReasonTooLong", error.getString("code"));
      assertTrue(error.getString("message"), error.getString("message").contains("10"));
    });
  }

  @Test
  public void plan_aReasonOfExactlyTheAdLengthPasses() throws Exception {
    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      ReturnedQuantityTestSupport.stubReturnedQuantities(dal, (src, excl) -> BigDecimal.ZERO);
      ShipmentInOut source = ReturnedQuantityTestSupport.completedSource("rcp-1",
          ReturnedQuantityTestSupport.sourceLine("src-1", "Agua", "10"));
      withDescriptionLength(10, () -> assertNull(ReturnFromDocumentSupport.plan(source,
          new JSONObject().put("reason", "1234567890")).refusal));
    }
  }
}
