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
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.Test;

import com.etendoerp.go.schemaforge.DocumentLineImport.HeaderLink;
import com.etendoerp.go.schemaforge.DocumentLineImport.ImportRefusal;
import com.etendoerp.go.schemaforge.DocumentLineImport.ImportedLine;

/**
 * Unit tests for {@link DocumentLineImport}, the rules every "import lines into a draft document"
 * action shares (ETP-5713): the request, the preconditions, the cap, the header link and the shape
 * of the answers. The delegates' own tests cover each path end to end; these pin the rules.
 *
 * @covers com.etendoerp.go.schemaforge.DocumentLineImport
 */
public class DocumentLineImportTest {

  private static BigDecimal bd(String value) {
    return new BigDecimal(value);
  }

  private static JSONObject body(String json) throws Exception {
    return new JSONObject(json);
  }

  private static JSONObject errorOf(ImportRefusal refusal) throws Exception {
    return refusal.response().getBody().getJSONObject("error");
  }

  private static Map<String, String> lines(String... idAndName) {
    Map<String, String> lines = new LinkedHashMap<>();
    for (int i = 0; i < idAndName.length; i += 2) {
      lines.put(idAndName[i], idAndName[i + 1]);
    }
    return lines;
  }

  private static Map<String, BigDecimal> qty(String... idAndQty) {
    Map<String, BigDecimal> map = new LinkedHashMap<>();
    for (int i = 0; i < idAndQty.length; i += 2) {
      map.put(idAndQty[i], bd(idAndQty[i + 1]));
    }
    return map;
  }

  // ── the request ───────────────────────────────────────────────────────────

  @Test
  public void parseRequestedLines_aMissingOrEmptyListMeansEverythingPending() throws Exception {
    assertTrue(DocumentLineImport.parseRequestedLines(null).isEmpty());
    assertTrue(DocumentLineImport.parseRequestedLines(body("{}")).isEmpty());
    assertTrue(DocumentLineImport.parseRequestedLines(body("{\"lines\":[]}")).isEmpty());
  }

  @Test
  public void parseRequestedLines_sumsTheSameSourceLineListedTwice_inRequestOrder()
      throws Exception {
    Map<String, BigDecimal> requested = DocumentLineImport.parseRequestedLines(body(
        "{\"lines\":[{\"sourceLineId\":\"b\",\"quantity\":2},{\"sourceLineId\":\"a\","
            + "\"quantity\":\"1.5\"},{\"sourceLineId\":\"b\",\"quantity\":3}]}"));

    assertEquals(List.of("b", "a"), List.copyOf(requested.keySet()));
    assertEquals(0, bd("5").compareTo(requested.get("b")));
    assertEquals(0, bd("1.5").compareTo(requested.get("a")));
  }

  @Test
  public void parseRequestedLines_anEntryThatIsNotUnderstoodIsRefusedNeverSkipped()
      throws Exception {
    for (String entry : new String[] { "{\"quantity\":1}", "{\"sourceLineId\":\"a\"}",
        "{\"sourceLineId\":\"a\",\"quantity\":0}", "{\"sourceLineId\":\"a\",\"quantity\":\"x\"}",
        "{\"sourceLineId\":\" \",\"quantity\":1}" }) {
      try {
        DocumentLineImport.parseRequestedLines(body("{\"lines\":[" + entry + "]}"));
        fail("must refuse " + entry);
      } catch (ImportRefusal refusal) {
        assertEquals(422, refusal.response().getHttpStatus());
        assertEquals(DocumentLineImport.ERROR_INVALID_LINE, refusal.code());
        assertTrue(errorOf(refusal).getString("message").contains("lines[0]"));
      }
    }
  }

  // ── the cap ───────────────────────────────────────────────────────────────

  @Test
  public void plan_withoutRequestedLines_importsEveryPendingLineInFull() {
    Map<String, BigDecimal> planned = DocumentLineImport.plan(null,
        lines("l1", "Agua", "l2", "Pan", "l3", "Sal"), qty("l1", "4", "l3", "7"));

    assertEquals(List.of("l1", "l3"), List.copyOf(planned.keySet()));
    assertEquals(0, bd("4").compareTo(planned.get("l1")));
    assertEquals(0, bd("7").compareTo(planned.get("l3")));
  }

  @Test
  public void plan_withNothingPending_isRefusedAsNothingPending() throws Exception {
    try {
      DocumentLineImport.plan(null, lines("l1", "Agua"), new LinkedHashMap<>());
      fail("nothing pending must be refused");
    } catch (ImportRefusal refusal) {
      assertEquals(422, refusal.response().getHttpStatus());
      assertEquals(DocumentLineImport.ERROR_NOTHING_PENDING, refusal.code());
    }
  }

  @Test
  public void plan_explicitPartialQuantity_isHonouredExactly() {
    Map<String, BigDecimal> planned = DocumentLineImport.plan(qty("l1", "2"),
        lines("l1", "Agua", "l2", "Pan"), qty("l1", "4", "l2", "9"));

    assertEquals(1, planned.size());
    assertEquals(0, bd("2").compareTo(planned.get("l1")));
  }

  @Test
  public void plan_aQuantityEqualToThePendingIsAccepted() {
    Map<String, BigDecimal> planned = DocumentLineImport.plan(qty("l1", "4"),
        lines("l1", "Agua"), qty("l1", "4"));

    assertEquals(0, bd("4").compareTo(planned.get("l1")));
  }

  @Test
  public void plan_aQuantityOverThePending_is422NamingProductRequestedAndPending()
      throws Exception {
    try {
      DocumentLineImport.plan(qty("l1", "5"), lines("l1", "Agua"), qty("l1", "4"));
      fail("over the cap must be refused");
    } catch (ImportRefusal refusal) {
      assertEquals(422, refusal.response().getHttpStatus());
      assertEquals(DocumentLineImport.ERROR_EXCEEDS_PENDING, refusal.code());
      JSONObject error = errorOf(refusal);
      assertEquals("Agua", error.getString("product"));
      assertEquals(0, bd("5").compareTo(new BigDecimal(error.get("requested").toString())));
      assertEquals(0, bd("4").compareTo(new BigDecimal(error.get("pending").toString())));
      assertEquals("l1", error.getString("sourceLineId"));
      String message = error.getString("message");
      assertTrue(message, message.contains("Agua") && message.contains("5")
          && message.contains("4") && message.contains("draft"));
    }
  }

  /** A line with nothing pending (drafts took it all) refuses any quantity and says so. */
  @Test
  public void plan_aLineWithNothingPending_isRefusedWithPendingZero() throws Exception {
    try {
      DocumentLineImport.plan(qty("l1", "1"), lines("l1", "Agua", "l2", "Pan"),
          qty("l2", "3"));
      fail("a line with nothing pending must be refused");
    } catch (ImportRefusal refusal) {
      assertEquals(DocumentLineImport.ERROR_EXCEEDS_PENDING, refusal.code());
      assertEquals(0, errorOf(refusal).getInt("pending"));
      assertTrue(errorOf(refusal).getString("message").contains("nothing is pending"));
    }
  }

  @Test
  public void plan_aLineThatIsNotInTheSourceDocument_isRefused() throws Exception {
    try {
      DocumentLineImport.plan(qty("ghost", "1"), lines("l1", "Agua"), qty("l1", "4"));
      fail("a foreign line must be refused");
    } catch (ImportRefusal refusal) {
      assertEquals(DocumentLineImport.ERROR_LINE_NOT_IN_DOCUMENT, refusal.code());
      assertEquals(422, refusal.response().getHttpStatus());
    }
  }

  /** The first offending line wins, and the whole request is judged before anything is built. */
  @Test
  public void plan_oneLineOverTheCap_refusesTheWholeRequest() {
    try {
      DocumentLineImport.plan(qty("l1", "1", "l2", "10"), lines("l1", "Agua", "l2", "Pan"),
          qty("l1", "4", "l2", "9"));
      fail("the second line is over the cap");
    } catch (ImportRefusal refusal) {
      assertEquals(DocumentLineImport.ERROR_EXCEEDS_PENDING, refusal.code());
    }
  }

  @Test
  public void plan_negativeLines_keepTheirSignAndAreCappedByMagnitude() {
    Map<String, BigDecimal> planned = DocumentLineImport.plan(qty("l1", "-2"),
        lines("l1", "Devolución"), qty("l1", "-3"));
    assertEquals(0, bd("-2").compareTo(planned.get("l1")));

    for (String wrong : new String[] { "2", "-4" }) {
      try {
        DocumentLineImport.plan(qty("l1", wrong), lines("l1", "Devolución"), qty("l1", "-3"));
        fail(wrong + " does not fit a pending of -3");
      } catch (ImportRefusal refusal) {
        assertEquals(DocumentLineImport.ERROR_EXCEEDS_PENDING, refusal.code());
      }
    }
  }

  @Test
  public void fits_needsTheSignOfThePendingAndAMagnitudeNotAboveIt() {
    assertTrue(DocumentLineImport.fits(bd("1"), bd("1")));
    assertTrue(DocumentLineImport.fits(bd("0.5"), bd("1")));
    assertFalse(DocumentLineImport.fits(bd("1.01"), bd("1")));
    assertFalse(DocumentLineImport.fits(bd("1"), bd("0")));
    assertFalse(DocumentLineImport.fits(bd("-1"), bd("1")));
  }

  // ── the preconditions ─────────────────────────────────────────────────────

  @Test
  public void requireDraftTarget_acceptsADraft_andRefusesAnythingElse() throws Exception {
    DocumentLineImport.requireDraftTarget("100", "DR");
    for (String status : new String[] { "CO", "VO", "RE", "CL" }) {
      try {
        DocumentLineImport.requireDraftTarget("100", status);
        fail(status + " is not a draft");
      } catch (ImportRefusal refusal) {
        assertEquals(DocumentLineImport.ERROR_TARGET_NOT_DRAFT, refusal.code());
        assertEquals(422, refusal.response().getHttpStatus());
        assertTrue(errorOf(refusal).getString("message").contains("100"));
      }
    }
  }

  @Test
  public void requireCompletedSource_acceptsCompleted_andRefusesADraftOrVoided() {
    DocumentLineImport.requireCompletedSource("200", "CO");
    for (String status : new String[] { "DR", "VO", "UE" }) {
      try {
        DocumentLineImport.requireCompletedSource("200", status);
        fail(status + " is not completed");
      } catch (ImportRefusal refusal) {
        assertEquals(DocumentLineImport.ERROR_SOURCE_NOT_COMPLETED, refusal.code());
      }
    }
  }

  @Test
  public void requireSameBusinessPartner_refusesAnotherPartner() {
    DocumentLineImport.requireSameBusinessPartner("bp1", "bp1", "300");
    try {
      DocumentLineImport.requireSameBusinessPartner("bp1", "bp2", "300");
      fail("another partner must be refused");
    } catch (ImportRefusal refusal) {
      assertEquals(DocumentLineImport.ERROR_PARTNER_MISMATCH, refusal.code());
    }
  }

  @Test
  public void requireSameCurrency_refusesAnotherCurrency_butOnlyWhenBothHaveOne() {
    DocumentLineImport.requireSameCurrency("eur", "eur", "300");
    DocumentLineImport.requireSameCurrency(null, "usd", "300");
    DocumentLineImport.requireSameCurrency("eur", null, "300");
    try {
      DocumentLineImport.requireSameCurrency("eur", "usd", "300");
      fail("another currency must be refused");
    } catch (ImportRefusal refusal) {
      assertEquals(DocumentLineImport.ERROR_CURRENCY_MISMATCH, refusal.code());
    }
  }

  @Test
  public void requireSameDirection_refusesASalesSourceIntoAPurchaseTarget_andViceVersa() {
    DocumentLineImport.requireSameDirection(true, true, "300");
    DocumentLineImport.requireSameDirection(false, false, "300");
    for (boolean[] pair : new boolean[][] { { true, false }, { false, true } }) {
      try {
        DocumentLineImport.requireSameDirection(pair[0], pair[1], "300");
        fail("opposite directions must be refused");
      } catch (ImportRefusal refusal) {
        assertEquals(DocumentLineImport.ERROR_DIRECTION_MISMATCH, refusal.code());
      }
    }
  }

  @Test
  public void notFound_is404() throws Exception {
    ImportRefusal refusal = DocumentLineImport.notFound("Order", "o1");
    assertEquals(404, refusal.response().getHttpStatus());
    assertEquals(DocumentLineImport.ERROR_NOT_FOUND, refusal.code());
    assertEquals("Order not found: o1", errorOf(refusal).getString("message"));
  }

  // ── the header link ───────────────────────────────────────────────────────

  private static Set<String> orders(String... ids) {
    return new LinkedHashSet<>(List.of(ids));
  }

  @Test
  public void resolveHeaderLink_oneOrder_linksAHeaderWithNoneOrTheSameOne() {
    HeaderLink link = DocumentLineImport.resolveHeaderLink(null, orders("o1"));
    assertSame(HeaderLink.Action.LINK, link.action());
    assertEquals("o1", link.orderId());

    link = DocumentLineImport.resolveHeaderLink("o1", orders("o1"));
    assertSame(HeaderLink.Action.LINK, link.action());
    assertEquals("o1", link.orderId());
  }

  @Test
  public void resolveHeaderLink_oneOrder_keepsALinkToAnotherOrder() {
    assertSame(HeaderLink.Action.KEEP,
        DocumentLineImport.resolveHeaderLink("other", orders("o1")).action());
  }

  @Test
  public void resolveHeaderLink_twoOrders_leavesAHeaderWithNoLinkEmpty() {
    assertSame(HeaderLink.Action.KEEP,
        DocumentLineImport.resolveHeaderLink(null, orders("o1", "o2")).action());
  }

  /** A link set when the first order came in is cleared once a second order joins. */
  @Test
  public void resolveHeaderLink_twoOrders_clearsALinkToOneOfThem() {
    assertSame(HeaderLink.Action.CLEAR,
        DocumentLineImport.resolveHeaderLink("o1", orders("o1", "o2")).action());
  }

  @Test
  public void resolveHeaderLink_twoOrders_keepsALinkToAnOrderNoLineComesFrom() {
    assertSame(HeaderLink.Action.KEEP,
        DocumentLineImport.resolveHeaderLink("manual", orders("o1", "o2")).action());
  }

  @Test
  public void resolveHeaderLink_noOrderOnAnyLine_changesNothing() {
    assertSame(HeaderLink.Action.KEEP,
        DocumentLineImport.resolveHeaderLink(null, orders()).action());
    assertSame(HeaderLink.Action.KEEP,
        DocumentLineImport.resolveHeaderLink("o1", orders()).action());
  }

  // ── the answer ────────────────────────────────────────────────────────────

  @Test
  public void ok_reportsEveryLineAndTheHeaderLink() throws Exception {
    NeoResponse response = DocumentLineImport.ok(List.of(
        new ImportedLine("n1", "s1", "p1", "Agua", bd("2"), "o1"),
        new ImportedLine("n2", "s2", "p2", "Pan", bd("3"), "o1")), "o1");

    assertEquals(200, response.getHttpStatus());
    JSONObject data = response.getBody().getJSONObject("response").getJSONObject("data");
    assertEquals(2, data.getInt("importedCount"));
    assertTrue(data.getBoolean("headerLinked"));
    assertEquals("o1", data.getString("salesOrder"));
    JSONArray lines = data.getJSONArray("lines");
    assertEquals(2, lines.length());
    JSONObject first = lines.getJSONObject(0);
    assertEquals("n1", first.getString("id"));
    assertEquals("s1", first.getString("sourceLineId"));
    assertEquals("p1", first.getString("product"));
    assertEquals("Agua", first.getString("productName"));
    assertEquals(0, bd("2").compareTo(new BigDecimal(first.get("quantity").toString())));
  }

  @Test
  public void ok_withoutALinkedOrder_saysTheHeaderIsNotLinkedAndOmitsTheOrder() throws Exception {
    NeoResponse response = DocumentLineImport.ok(
        List.of(new ImportedLine("n1", "s1", "p1", "Agua", bd("2"), "o1")), null);

    JSONObject data = response.getBody().getJSONObject("response").getJSONObject("data");
    assertFalse(data.getBoolean("headerLinked"));
    assertFalse(data.has("salesOrder"));
  }

  @Test
  public void lineRow_carriesThePendingQuantityAndTheProductIdentifier() throws Exception {
    JSONObject row = DocumentLineImport.lineRow("l1", 10L, "p1", "Agua", "u1", bd("4"));

    assertEquals("l1", row.getString("id"));
    assertEquals(10, row.getInt("lineNo"));
    assertEquals("p1", row.getString("product"));
    assertEquals("Agua", row.getString("product$_identifier"));
    assertEquals("u1", row.getString("uOM"));
    assertEquals(0, bd("4").compareTo(new BigDecimal(row.get("pendingQuantity").toString())));
  }

  @Test
  public void text_isNullForABlankOrMissingKey() throws Exception {
    assertNull(DocumentLineImport.text(null, "k"));
    assertNull(DocumentLineImport.text(body("{\"k\":\"  \"}"), "k"));
    assertEquals("v", DocumentLineImport.text(body("{\"k\":\"v\"}"), "k"));
  }

  /** N2: the link is reported as it is; "linked" means an imported line belongs to that order. */
  @Test
  public void ok_headerLinkedOnlyWhenAnImportedLineBelongsToTheHeadersOrder() throws Exception {
    List<ImportedLine> lines = List.of(new ImportedLine("n1", "s1", "p1", "Agua", bd("2"), "o1"));

    JSONObject other = DocumentLineImport.ok(lines, "x").getBody().getJSONObject("response")
        .getJSONObject("data");
    assertFalse("no imported line belongs to the header's order", other.getBoolean("headerLinked"));
    assertEquals("the header's current order, as it is", "x", other.getString("salesOrder"));

    JSONObject own = DocumentLineImport.ok(lines, "o1").getBody().getJSONObject("response")
        .getJSONObject("data");
    assertTrue(own.getBoolean("headerLinked"));
    assertEquals("o1", own.getString("salesOrder"));
  }

  /** W6: a discovery page says whether it left something out and which limits it used. */
  @Test
  public void wrapPage_carriesDataHasMoreAndTheLimits() throws Exception {
    JSONObject response = DocumentLineImport.wrapPage(new JSONArray().put(new JSONObject()), true,
        50, 200).getBody().getJSONObject("response");

    assertEquals(1, response.getJSONArray("data").length());
    assertTrue(response.getBoolean("hasMore"));
    assertEquals(50, response.getInt("limit"));
    assertEquals(200, response.getInt("scanLimit"));
  }

  /**
   * W1: the source lines are locked with FOR NO KEY UPDATE, in ascending id order. It still makes
   * two imports of the same line wait for each other, but it no longer conflicts with the FOR KEY
   * SHARE a foreign-key insert (an invoice line pointing at the order line) takes on the same row,
   * which with FOR UPDATE made two import paths wait for each other in a cycle.
   */
  @Test
  public void locks_useForNoKeyUpdate_inAscendingIdOrder() throws Exception {
    try (org.mockito.MockedStatic<org.openbravo.dal.service.OBDal> dalMock =
        org.mockito.Mockito.mockStatic(org.openbravo.dal.service.OBDal.class)) {
      org.openbravo.dal.service.OBDal dal = org.mockito.Mockito.mock(
          org.openbravo.dal.service.OBDal.class);
      dalMock.when(org.openbravo.dal.service.OBDal::getInstance).thenReturn(dal);
      java.sql.Connection conn = org.mockito.Mockito.mock(java.sql.Connection.class);
      java.sql.PreparedStatement ps = org.mockito.Mockito.mock(java.sql.PreparedStatement.class);
      java.sql.ResultSet rs = org.mockito.Mockito.mock(java.sql.ResultSet.class);
      org.mockito.Mockito.when(dal.getConnection()).thenReturn(conn);
      org.mockito.ArgumentCaptor<String> sql = org.mockito.ArgumentCaptor.forClass(String.class);
      org.mockito.Mockito.when(conn.prepareStatement(sql.capture())).thenReturn(ps);
      org.mockito.Mockito.when(ps.executeQuery()).thenReturn(rs);
      org.mockito.ArgumentCaptor<String> ids = org.mockito.ArgumentCaptor.forClass(String.class);

      DocumentLineImport.lockOrderLines(List.of("b", "c", "a"));
      DocumentLineImport.lockInOutLines(List.of("z"));

      for (String statement : sql.getAllValues()) {
        assertTrue(statement, statement.endsWith("FOR NO KEY UPDATE"));
      }
      org.mockito.Mockito.verify(ps, org.mockito.Mockito.times(4)).setString(
          org.mockito.ArgumentMatchers.eq(1), ids.capture());
      assertEquals(List.of("a", "b", "c", "z"), ids.getAllValues());
    }
  }
}
