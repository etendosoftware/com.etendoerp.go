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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.base.model.Entity;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.invoice.Invoice;
import org.openbravo.model.common.invoice.InvoiceLine;

/**
 * Unit tests for {@link CompletedInvoiceWriteFence} (ETP-5692): a processed invoice accepts on the
 * CRUD update path only the changes the invoice window can make.
 *
 * <p>Repro of the live QA findings: BUG-3 — on a Completed, unposted invoice a REST/MCP update of
 * {@code paymentMethod} answered 200 while the SPA locks it; BUG-2 — on a POSTED invoice the
 * header {@code costcenter} answered 200 (core's {@code C_INVOICE_TRG} does not lock
 * {@code C_Costcenter_ID}), leaving the header in disagreement with the ledger.</p>
 *
 * @covers com.etendoerp.go.schemaforge.CompletedInvoiceWriteFence
 */
public class CompletedInvoiceWriteFenceTest {

  private static final String INVOICE_ID = "INV-1";
  private static final String LINE_ID = "LINE-1";
  private static final String COSTCENTER = "costcenter";
  private static final String PROJECT = "project";
  private static final String DESCRIPTION = "description";
  private static final String PAYMENT_METHOD = "paymentMethod";
  private static final String CODE = "code";
  private static final String FIELDS = "fields";
  private static final String ERROR = "error";
  private static final String STATUS_CO = "CO";

  private static final Set<String> SALES = CompletedInvoiceWriteFence.HEADER_EDITABLE_WHEN_COMPLETED;
  private static final Set<String> PURCHASE =
      CompletedInvoiceWriteFence.PURCHASE_HEADER_EDITABLE_WHEN_COMPLETED;

  // ---------------------------------------------------------------------------
  // fixtures
  // ---------------------------------------------------------------------------

  private static BaseOBObject ref(String id) {
    BaseOBObject ref = mock(BaseOBObject.class);
    when(ref.getId()).thenReturn(id);
    return ref;
  }

  /** A mocked entity record whose properties are {@code stored} (every key a property). */
  private static <T extends BaseOBObject> T record(Class<T> type, Map<String, Object> stored) {
    T rec = mock(type);
    Entity entity = mock(Entity.class);
    when(entity.hasProperty(anyString())).thenAnswer(inv -> stored.containsKey(inv.getArgument(0)));
    when(rec.getEntity()).thenReturn(entity);
    when(rec.get(anyString())).thenAnswer(inv -> stored.get(inv.<String>getArgument(0)));
    return rec;
  }

  private static Map<String, Object> storedHeader() {
    Map<String, Object> stored = new HashMap<>();
    stored.put(DESCRIPTION, "old note");
    stored.put(PAYMENT_METHOD, ref("PM-1"));
    stored.put(COSTCENTER, ref("CC-1"));
    stored.put(PROJECT, ref("PRJ-1"));
    stored.put("orderReference", "SUP-1");
    stored.put("accountingDate", new GregorianCalendar(2026, Calendar.OCTOBER, 9).getTime());
    stored.put("grandTotalAmount", new BigDecimal("121.00"));
    stored.put("salesTransaction", Boolean.TRUE);
    stored.put("updated", new GregorianCalendar(2026, Calendar.OCTOBER, 9).getTime());
    stored.put("organization", ref("ORG-1"));
    return stored;
  }

  private static Invoice invoice(String docStatus, String posted, boolean processed) {
    Invoice invoice = record(Invoice.class, storedHeader());
    when(invoice.isProcessed()).thenReturn(processed);
    when(invoice.getDocumentStatus()).thenReturn(docStatus);
    when(invoice.getPosted()).thenReturn(posted);
    return invoice;
  }

  private static NeoContext update(String method, String recordId, JSONObject body) {
    return NeoContext.builder()
        .endpointType(NeoEndpointType.CRUD)
        .httpMethod(method)
        .recordId(recordId)
        .requestBody(body)
        .build();
  }

  private static NeoResponse header(Invoice invoice, Set<String> allowlist, JSONObject body) {
    try (MockedStatic<OBContext> obc = Mockito.mockStatic(OBContext.class);
        MockedStatic<OBDal> dal = Mockito.mockStatic(OBDal.class)) {
      OBDal obDal = mock(OBDal.class);
      dal.when(OBDal::getInstance).thenReturn(obDal);
      when(obDal.get(Invoice.class, INVOICE_ID)).thenReturn(invoice);
      return CompletedInvoiceWriteFence.checkHeader(update("PATCH", INVOICE_ID, body), allowlist);
    }
  }

  private static NeoResponse line(Invoice invoice, JSONObject body) {
    Map<String, Object> stored = new HashMap<>();
    stored.put(DESCRIPTION, "line note");
    stored.put(PROJECT, ref("PRJ-1"));
    stored.put(COSTCENTER, ref("CC-1"));
    stored.put("invoice", invoice);
    InvoiceLine line = record(InvoiceLine.class, stored);
    when(line.getInvoice()).thenReturn(invoice);
    try (MockedStatic<OBContext> obc = Mockito.mockStatic(OBContext.class);
        MockedStatic<OBDal> dal = Mockito.mockStatic(OBDal.class)) {
      OBDal obDal = mock(OBDal.class);
      dal.when(OBDal::getInstance).thenReturn(obDal);
      when(obDal.get(InvoiceLine.class, LINE_ID)).thenReturn(line);
      return CompletedInvoiceWriteFence.checkLine(update("PUT", LINE_ID, body));
    }
  }

  private static JSONObject body(Object... keyValues) throws Exception {
    JSONObject body = new JSONObject();
    for (int i = 0; i < keyValues.length; i += 2) {
      body.put((String) keyValues[i], keyValues[i + 1]);
    }
    return body;
  }

  private static JSONObject errorOf(NeoResponse response) throws Exception {
    assertNotNull("expected a refusal", response);
    assertEquals(422, response.getHttpStatus());
    return response.getBody().getJSONObject(ERROR);
  }

  private static String only(JSONArray array) throws Exception {
    assertEquals(1, array.length());
    return array.getString(0);
  }

  // ---------------------------------------------------------------------------
  // header — BUG-3: the completed fence
  // ---------------------------------------------------------------------------

  @Test
  public void draftInvoiceIsNeverFenced() throws Exception {
    assertNull(header(invoice("DR", "N", false), SALES, body(PAYMENT_METHOD, "PM-2")));
  }

  /** BUG-3 repro: paymentMethod on a Completed, unposted invoice used to answer 200. */
  @Test
  public void completedInvoiceRefusesAFieldOutsideTheAllowlist() throws Exception {
    JSONObject error = errorOf(header(invoice(STATUS_CO, "N", true), PURCHASE,
        body(PAYMENT_METHOD, "PM-2", DESCRIPTION, "new note")));

    assertEquals(CompletedInvoiceWriteFence.CODE_FIELDS_LOCKED, error.getString(CODE));
    assertEquals(PAYMENT_METHOD, only(error.getJSONArray(FIELDS)));
    assertEquals(PURCHASE.size(), error.getJSONArray("allowedFields").length());
    assertEquals(CompletedInvoiceWriteFence.MSG_FIELDS_LOCKED, only(error.getJSONArray("messageKeys")));
    assertEquals(PAYMENT_METHOD,
        only(error.getJSONObject("messageParams").getJSONArray(FIELDS)));
    assertTrue(error.getString("message").contains(PAYMENT_METHOD));
    assertTrue(error.getString("message").contains("orderReference"));
    assertFalse("never the SPA's drop-and-retry code", "read_only_field".equals(error.optString(ERROR)));
  }

  @Test
  public void completedInvoiceNamesEveryRefusedField() throws Exception {
    JSONObject error = errorOf(header(invoice(STATUS_CO, "N", true), SALES,
        body(PAYMENT_METHOD, "PM-2", "orderReference", "SUP-2")));

    assertEquals(2, error.getJSONArray(FIELDS).length());
  }

  /** A field sent with its stored value is not a change (the SPA and MCP re-send ids). */
  @Test
  public void unchangedValuesAreNotChanges() throws Exception {
    JSONObject idAsObject = new JSONObject().put("id", "PM-1");
    assertNull(header(invoice(STATUS_CO, "N", true), SALES, body(PAYMENT_METHOD, idAsObject,
        "accountingDate", "2026-10-09", "grandTotalAmount", "121", "salesTransaction", "Y")));
  }

  /** The correction flow: dimensions, accounting date and the notes stay editable on CO + unposted. */
  @Test
  public void completedUnpostedInvoiceAcceptsTheAllowlist() throws Exception {
    assertNull(header(invoice(STATUS_CO, "N", true), SALES, body(PROJECT, "PRJ-2",
        COSTCENTER, "CC-2", "accountingDate", "2026-10-10", DESCRIPTION, "new note")));
  }

  @Test
  public void orderReferenceIsAllowedOnPurchaseOnly() throws Exception {
    assertNull(header(invoice(STATUS_CO, "N", true), PURCHASE, body("orderReference", "SUP-2")));
    assertEquals("orderReference", only(errorOf(header(invoice(STATUS_CO, "N", true), SALES,
        body("orderReference", "SUP-2"))).getJSONArray(FIELDS)));
  }

  /** The SPA locks a purchase invoice's supplier number once it is sent to the SII; so does the API. */
  @Test
  public void orderReferenceOfAnSiiSentPurchaseInvoiceIsRefused() throws Exception {
    Invoice sent = invoice(STATUS_CO, "N", true);
    when(sent.isAeatsiiIssent()).thenReturn(true);
    when(sent.isSalesTransaction()).thenReturn(false);

    JSONObject error = errorOf(header(sent, PURCHASE, body("orderReference", "SUP-2")));

    assertEquals(CompletedInvoiceWriteFence.CODE_SII_SENT_LOCKED, error.getString(CODE));
    assertEquals("orderReference", only(error.getJSONArray(FIELDS)));
    assertNull("the other allowlisted fields stay editable",
        header(sent, PURCHASE, body(DESCRIPTION, "new note")));
  }

  /** A virtual field cannot be compared and is never allowed: fail closed. */
  @Test
  public void unknownKeyOnCompletedInvoiceIsRefused() throws Exception {
    JSONObject error = errorOf(header(invoice(STATUS_CO, "N", true), SALES,
        body("originInvoices", new JSONArray().put("INV-0"))));

    assertEquals("originInvoices", only(error.getJSONArray(FIELDS)));
  }

  @Test
  public void metaKeysAreNotBusinessChanges() throws Exception {
    assertNull(header(invoice(STATUS_CO, "N", true), SALES, body("id", INVOICE_ID,
        "updated", "2026-10-09T10:00:00", "project$_identifier", "Other", "_entityName", "Invoice",
        "organization", "ORG-9")));
  }

  // ---------------------------------------------------------------------------
  // header — BUG-2: posted / status dimension lock
  // ---------------------------------------------------------------------------

  /** BUG-2 repro: costcenter on a POSTED invoice used to answer 200. */
  @Test
  public void postedInvoiceRefusesItsCostCenter() throws Exception {
    JSONObject error = errorOf(header(invoice(STATUS_CO, "Y", true), SALES,
        body(COSTCENTER, "CC-2")));

    assertEquals(CompletedInvoiceWriteFence.CODE_POSTED_LOCKED, error.getString(CODE));
    assertEquals(COSTCENTER, only(error.getJSONArray(FIELDS)));
    assertEquals(CompletedInvoiceWriteFence.MSG_POSTED_LOCKED, only(error.getJSONArray("messageKeys")));
    assertTrue(error.getString("hint").contains("unpost"));
  }

  @Test
  public void postedInvoiceRefusesProjectAndAccountingDate() throws Exception {
    JSONObject error = errorOf(header(invoice(STATUS_CO, "Y", true), SALES,
        body(PROJECT, "PRJ-2", "accountingDate", "2026-10-10")));

    assertEquals(2, error.getJSONArray(FIELDS).length());
  }

  @Test
  public void postedInvoiceStillAcceptsItsNotes() throws Exception {
    assertNull(header(invoice(STATUS_CO, "Y", true), SALES, body(DESCRIPTION, "new note")));
  }

  /** Voided: the dimensions stay locked even though it is not posted. */
  @Test
  public void voidedInvoiceRefusesItsDimensions() throws Exception {
    JSONObject error = errorOf(header(invoice("VO", "D", true), SALES, body(PROJECT, "PRJ-2")));

    assertEquals(CompletedInvoiceWriteFence.CODE_STATUS_LOCKED, error.getString(CODE));
    assertEquals("VO", error.getJSONObject("messageParams").getString("docStatus"));
    assertEquals("project cannot be changed on a voided or closed invoice (status VO).",
        error.getString("message"));
  }

  // ---------------------------------------------------------------------------
  // lines
  // ---------------------------------------------------------------------------

  @Test
  public void lineOfCompletedInvoiceRefusesAFieldOutsideTheLineAllowlist() throws Exception {
    JSONObject error = errorOf(line(invoice(STATUS_CO, "N", true), body(DESCRIPTION, "changed")));

    assertEquals(DESCRIPTION, only(error.getJSONArray(FIELDS)));
    assertEquals(2, error.getJSONArray("allowedFields").length());
  }

  @Test
  public void lineOfCompletedUnpostedInvoiceAcceptsItsDimensions() throws Exception {
    assertNull(line(invoice(STATUS_CO, "N", true), body(PROJECT, "PRJ-2", COSTCENTER, "CC-2")));
  }

  @Test
  public void lineOfPostedInvoiceRefusesItsDimensions() throws Exception {
    JSONObject error = errorOf(line(invoice(STATUS_CO, "Y", true), body(COSTCENTER, "CC-2")));

    assertEquals(CompletedInvoiceWriteFence.CODE_POSTED_LOCKED, error.getString(CODE));
  }

  @Test
  public void lineOfDraftInvoiceIsNeverFenced() throws Exception {
    assertNull(line(invoice("DR", "N", false), body(DESCRIPTION, "changed")));
  }

  // ---------------------------------------------------------------------------
  // scope and fail-open
  // ---------------------------------------------------------------------------

  @Test
  public void onlyCrudUpdatesAreFenced() throws Exception {
    JSONObject changes = body(PAYMENT_METHOD, "PM-2");
    NeoContext create = update("POST", INVOICE_ID, changes);
    NeoContext action = NeoContext.builder().endpointType(NeoEndpointType.ACTION)
        .httpMethod("PATCH").recordId(INVOICE_ID).requestBody(changes).build();

    assertNull(CompletedInvoiceWriteFence.checkHeader(create, SALES));
    assertNull(CompletedInvoiceWriteFence.checkHeader(action, SALES));
    assertNull(CompletedInvoiceWriteFence.checkHeader(update("PATCH", null, changes), SALES));
    assertNull(CompletedInvoiceWriteFence.checkHeader(update("PATCH", INVOICE_ID, null), SALES));
  }

  @Test
  public void anUnreadableInvoiceFailsOpen() throws Exception {
    try (MockedStatic<OBContext> obc = Mockito.mockStatic(OBContext.class);
        MockedStatic<OBDal> dal = Mockito.mockStatic(OBDal.class)) {
      dal.when(OBDal::getInstance).thenThrow(new IllegalStateException("no DAL"));

      assertNull(CompletedInvoiceWriteFence.checkHeader(
          update("PATCH", INVOICE_ID, body(PAYMENT_METHOD, "PM-2")), SALES));
    }
  }

  @Test
  public void sameValueComparesEachStoredShape() {
    assertTrue(CompletedInvoiceWriteFence.sameValue(null, ""));
    assertTrue(CompletedInvoiceWriteFence.sameValue(null, JSONObject.NULL));
    assertFalse(CompletedInvoiceWriteFence.sameValue(null, "x"));
    assertFalse(CompletedInvoiceWriteFence.sameValue(ref("A"), null));
    assertTrue(CompletedInvoiceWriteFence.sameValue(ref("A"), "A"));
    assertFalse(CompletedInvoiceWriteFence.sameValue(ref("A"), "B"));
    assertTrue(CompletedInvoiceWriteFence.sameValue(new BigDecimal("1.50"), 1.5));
    assertFalse(CompletedInvoiceWriteFence.sameValue(new BigDecimal("1.50"), "abc"));
    assertTrue(CompletedInvoiceWriteFence.sameValue(Boolean.FALSE, "N"));
    assertTrue(CompletedInvoiceWriteFence.sameValue(Boolean.TRUE, true));
    assertFalse(CompletedInvoiceWriteFence.sameValue(
        new GregorianCalendar(2026, Calendar.OCTOBER, 9).getTime(), "2026"));
    assertTrue(CompletedInvoiceWriteFence.sameValue("a", "a"));
  }
}
