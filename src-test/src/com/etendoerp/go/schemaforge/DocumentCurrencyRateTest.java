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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.codehaus.jettison.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.base.model.Entity;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.erpCommon.utility.OBCurrencyUtils;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.currency.Currency;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.common.order.Order;

/**
 * Unit tests for {@link DocumentCurrencyRate}: the save rules ({@link DocumentCurrencyRate#beforeWrite}),
 * the callout recalculation ({@link DocumentCurrencyRate#applyToCallout}), the recomputation of a
 * generated document ({@link DocumentCurrencyRate#recompute}) and the completion refusal
 * ({@link DocumentCurrencyRate#refuseCompletionWithoutRate}).
 *
 * <p>The DAL, the context, the organization currency and the rate lookup
 * ({@link NeoExchangeRateService#rateAsDecimal}) are statically mocked. The organization currency is
 * {@code eur}; configured rates are listed in {@link #setUp()}. A currency's ISO code resolves to its
 * id (no {@link Currency} row is found), so the refusal params name the ids.
 *
 * @covers com.etendoerp.go.schemaforge.DocumentCurrencyRate
 */
public class DocumentCurrencyRateTest {

  private static final String RATE = "eTGOCurrencyRate";
  private static final DocumentCurrencyRate.RateType ORDER_TYPE = new DocumentCurrencyRate.RateType(
      "Order", RATE, "orderDate", "ETGO_RateRefDateOrder");
  private static final DocumentCurrencyRate.RateType INVOICE_TYPE =
      new DocumentCurrencyRate.RateType("Invoice", RATE, "accountingDate",
          "ETGO_RateRefDateAccounting");
  private static final String DOC_ID = "doc-1";
  private static final String ABSENT = "<absent>";
  private static final String NULL = "<null>";

  private MockedStatic<OBContext> obContextStatic;
  private MockedStatic<OBDal> obDalStatic;
  private MockedStatic<OBCurrencyUtils> currencyUtils;
  private MockedStatic<NeoExchangeRateService> rateService;
  private OBDal dal;

  @Before
  public void setUp() {
    Organization sessionOrg = mock(Organization.class);
    when(sessionOrg.getId()).thenReturn("org-1");
    Client sessionClient = mock(Client.class);
    when(sessionClient.getId()).thenReturn("c-1");
    OBContext context = mock(OBContext.class);
    when(context.getCurrentOrganization()).thenReturn(sessionOrg);
    when(context.getCurrentClient()).thenReturn(sessionClient);
    dal = mock(OBDal.class);
    when(dal.getSession()).thenReturn(mock(org.hibernate.Session.class));

    obContextStatic = Mockito.mockStatic(OBContext.class);
    obContextStatic.when(OBContext::getOBContext).thenReturn(context);
    obDalStatic = Mockito.mockStatic(OBDal.class);
    obDalStatic.when(OBDal::getInstance).thenReturn(dal);
    currencyUtils = Mockito.mockStatic(OBCurrencyUtils.class);
    currencyUtils.when(() -> OBCurrencyUtils.getOrgCurrency(anyString())).thenReturn("eur");
    rateService = Mockito.mockStatic(NeoExchangeRateService.class);
    configureRate("usd", "2026-03-15", "1.2");
    configureRate("gbp", "2026-03-01", "0.85");
    configureRate("usd", "2026-03-20", "1.3");
    configureRate("usd", "2026-03-31", "1.4");
    configureRate("usd", "2026-04-01", "1.5");
    configureRate("usd", "2026-04-02", "1.6");
  }

  @After
  public void tearDown() {
    rateService.close();
    currencyUtils.close();
    obDalStatic.close();
    obContextStatic.close();
  }

  private void configureRate(String docCurrency, String date, String rate) {
    rateService.when(() -> NeoExchangeRateService.rateAsDecimal("eur", docCurrency,
        LocalDate.parse(date), "org-1", "c-1")).thenReturn(new BigDecimal(rate));
  }

  /** A stored document {@code DOC_ID} of {@code type}, found by the DAL under its entity name. */
  private Order storedDoc(DocumentCurrencyRate.RateType type, String currencyId, String date,
      BigDecimal rate, boolean processed) {
    Currency currency = mock(Currency.class);
    when(currency.getId()).thenReturn(currencyId);
    Organization org = mock(Organization.class);
    when(org.getId()).thenReturn("org-1");
    Client client = mock(Client.class);
    when(client.getId()).thenReturn("c-1");
    Entity entity = mock(Entity.class);
    when(entity.hasProperty("processed")).thenReturn(true);
    Order doc = mock(Order.class);
    when(doc.getId()).thenReturn(DOC_ID);
    when(doc.getEntity()).thenReturn(entity);
    when(doc.get("currency")).thenReturn(currency);
    when(doc.get("organization")).thenReturn(org);
    when(doc.get("client")).thenReturn(client);
    when(doc.get(type.referenceDateProperty()))
        .thenReturn(date != null ? java.sql.Date.valueOf(date) : null);
    when(doc.get(RATE)).thenReturn(rate);
    when(doc.get("processed")).thenReturn(processed);
    when(dal.get(type.entityName(), DOC_ID)).thenReturn(doc);
    return doc;
  }

  private static NeoContext write(String recordId, JSONObject body) {
    return NeoContext.builder().httpMethod(recordId == null ? "POST" : "PATCH")
        .endpointType(NeoEndpointType.CRUD).recordId(recordId).requestBody(body).build();
  }

  private static String bodyRate(JSONObject body) {
    if (!body.has(RATE)) {
      return ABSENT;
    }
    Object value = body.opt(RATE);
    return value == null || value == JSONObject.NULL ? NULL
        : new BigDecimal(String.valueOf(value)).stripTrailingZeros().toPlainString();
  }

  // ---------------------------------------------------------------------------------------------
  // beforeWrite
  // ---------------------------------------------------------------------------------------------

  /**
   * Table-driven save rules on an order dated 2026-03-01: an org-currency document is never touched;
   * a write that changes the date or the currency without a rate gets the recalculated one (or
   * {@code null} and the 422 when none is configured); an explicit positive rate is honored; a rate
   * sent empty or zero is refused (the completion refusal when the write completes); a manual rate
   * survives a write that changes neither; a processed document is left alone; a create computes the
   * rate from its reference date, or defers when the body does not say its currency yet.
   */
  @Test
  public void beforeWriteMatrix() throws Exception {
    BigDecimal manual = new BigDecimal("1.10");
    Object[][] cases = {
        // name, recordId, body, completing, stored currency (null: none), stored rate, processed,
        // expected refusal code (null: none), expected body rate
        { "org-currency document untouched", DOC_ID, "{\"orderDate\":\"2026-03-15\"}", false,
            "eur", null, false, null, ABSENT },
        { "back to the org currency resets the rate to 1", DOC_ID, "{\"currency\":\"eur\"}",
            false, "usd", new BigDecimal("1.47"), false, null, "1" },
        { "back to the org currency overwrites a sent rate", DOC_ID,
            "{\"currency\":\"eur\",\"eTGOCurrencyRate\":\"1.47\"}", false,
            "usd", new BigDecimal("1.47"), false, null, "1" },
        { "org to foreign currency takes the rate of the date", DOC_ID, "{\"currency\":\"gbp\"}",
            false, "eur", BigDecimal.ONE, false, null, "0.85" },
        { "org currency re-sent leaves the rate alone", DOC_ID, "{\"currency\":\"eur\"}",
            false, "eur", BigDecimal.ONE, false, null, ABSENT },
        { "date change recalculates", DOC_ID, "{\"orderDate\":\"2026-03-15\"}", false,
            "usd", manual, false, null, "1.2" },
        { "currency change recalculates", DOC_ID, "{\"currency\":\"gbp\"}", false,
            "usd", manual, false, null, "0.85" },
        { "explicit positive rate honored", DOC_ID,
            "{\"orderDate\":\"2026-03-15\",\"eTGOCurrencyRate\":\"1.5\"}", false,
            "usd", manual, false, null, "1.5" },
        { "manual rate survives an unrelated write", DOC_ID, "{\"description\":\"x\"}", false,
            "usd", manual, false, null, ABSENT },
        { "rate cleared is refused", DOC_ID, "{\"eTGOCurrencyRate\":null}", false,
            "usd", manual, false, DocumentRateMessages.CODE_MISSING_ON_SAVE, NULL },
        { "rate zero is refused", DOC_ID, "{\"eTGOCurrencyRate\":\"0\"}", false,
            "usd", manual, false, DocumentRateMessages.CODE_MISSING_ON_SAVE, "0" },
        { "date change without a configured rate is refused", DOC_ID,
            "{\"orderDate\":\"2026-03-25\"}", false,
            "usd", manual, false, DocumentRateMessages.CODE_MISSING_ON_SAVE, NULL },
        { "completing without a rate gets the completion refusal", DOC_ID,
            "{\"eTGOCurrencyRate\":null,\"documentAction\":\"CO\"}", true,
            "usd", manual, false, DocumentRateMessages.CODE_MISSING_ON_COMPLETE, NULL },
        { "processed document untouched", DOC_ID, "{\"eTGOCurrencyRate\":null}", false,
            "usd", null, true, null, NULL },
        { "create computes from its reference date", null,
            "{\"currency\":\"usd\",\"orderDate\":\"2026-03-20\",\"organization\":\"org-1\"}", false,
            null, null, false, null, "1.3" },
        { "create without currency is deferred", null, "{\"orderDate\":\"2026-03-20\"}", false,
            null, null, false, null, ABSENT },
    };
    for (Object[] c : cases) {
      String name = (String) c[0];
      if (c[4] != null) {
        storedDoc(ORDER_TYPE, (String) c[4], "2026-03-01", (BigDecimal) c[5], (Boolean) c[6]);
      }
      JSONObject body = new JSONObject((String) c[2]);

      NeoResponse refusal = new DocumentCurrencyRate(ORDER_TYPE)
          .beforeWrite(write((String) c[1], body), (Boolean) c[3]);

      if (c[7] == null) {
        assertNull(name, refusal);
      } else {
        assertNotNull(name, refusal);
        assertEquals(name, 422, refusal.getHttpStatus());
        assertEquals(name, c[7], refusal.getBody().getJSONObject("error").getString("code"));
      }
      assertEquals(name, c[8], bodyRate(body));
    }
  }

  /**
   * The save refusal identifies itself for a client that renders its own wording: both message keys
   * (the text and the reference-date name) and the params — ISO codes, the reference date it was
   * looked up on, the property holding it and the label key naming it.
   */
  @Test
  public void beforeWriteRefusalCarriesMessageKeysAndParams() throws Exception {
    storedDoc(INVOICE_TYPE, "usd", "2026-03-01", null, false);
    JSONObject body = new JSONObject("{\"accountingDate\":\"2026-03-25\"}");

    NeoResponse refusal = new DocumentCurrencyRate(INVOICE_TYPE).beforeWrite(write(DOC_ID, body),
        false);

    JSONObject error = refusal.getBody().getJSONObject("error");
    assertEquals(DocumentRateMessages.MSG_MISSING_ON_SAVE,
        error.getJSONArray(NeoProcessService.MESSAGE_KEYS).getString(0));
    assertEquals("ETGO_RateRefDateAccounting",
        error.getJSONArray(NeoProcessService.MESSAGE_KEYS).getString(1));
    JSONObject params = error.getJSONObject(NeoProcessService.MESSAGE_PARAMS);
    assertEquals("eur", params.getString("orgCurrency"));
    assertEquals("usd", params.getString("documentCurrency"));
    assertEquals("2026-03-25", params.getString("date"));
    assertEquals("accountingDate", params.getString("dateField"));
    assertEquals("ETGO_RateRefDateAccounting", params.getString("referenceDate"));
  }

  // ---------------------------------------------------------------------------------------------
  // applyToCallout
  // ---------------------------------------------------------------------------------------------

  /**
   * Table-driven, invoice (reference date = accounting date), new record: the rate is returned in
   * {@code updates.eTGOCurrencyRate} when the callout was triggered by the currency or the
   * accounting date, or when the cascade copied a new date into the accounting date (an invoice-date
   * change) — {@code {"value": null}} when no rate is configured. An unrelated trigger and an
   * org-currency document leave {@code updates} alone.
   */
  @Test
  public void applyToCalloutTriggers() throws Exception {
    Object[][] cases = {
        // name, trigger, triggered value, formState, updates (null: none), expected rate
        { "currency trigger", "currency", "usd",
            "{\"accountingDate\":\"2026-03-31\",\"organization\":\"org-1\"}", null, "1.4" },
        { "accounting date trigger", "accountingDate", "2026-04-01",
            "{\"currency\":\"usd\",\"accountingDate\":\"2026-03-01\"}", null, "1.5" },
        { "invoice date cascaded into the accounting date", "invoiceDate", "2026-04-02",
            "{\"currency\":\"usd\",\"accountingDate\":\"2026-03-01\"}",
            "{\"accountingDate\":{\"value\":\"2026-04-02\"}}", "1.6" },
        { "no configured rate gives null", "accountingDate", "2026-05-01",
            "{\"currency\":\"usd\"}", null, NULL },
        { "unrelated field", "description", "x",
            "{\"currency\":\"usd\",\"accountingDate\":\"2026-03-31\"}", null, ABSENT },
        { "org currency", "currency", "eur",
            "{\"accountingDate\":\"2026-03-31\"}", null, ABSENT },
    };
    for (Object[] c : cases) {
      String name = (String) c[0];
      JSONObject body = new JSONObject();
      JSONObject updates = c[4] != null ? new JSONObject((String) c[4]) : null;
      if (updates != null) {
        body.put("updates", updates);
      }
      JSONObject requestBody = new JSONObject().put("field", c[1]).put("value", c[2]);
      NeoHandlerUtils.CalloutFields fields = new NeoHandlerUtils.CalloutFields(body, updates,
          requestBody, (String) c[1], new JSONObject((String) c[3]));

      new DocumentCurrencyRate(INVOICE_TYPE).applyToCallout(fields, null);

      JSONObject out = body.optJSONObject("updates");
      String actual;
      if (out == null || !out.has(RATE)) {
        actual = ABSENT;
      } else {
        actual = bodyRate(new JSONObject().put(RATE, out.getJSONObject(RATE).opt("value")));
      }
      assertEquals(name, c[5], actual);
    }
  }

  // ---------------------------------------------------------------------------------------------
  // recompute
  // ---------------------------------------------------------------------------------------------

  /**
   * A generated document gets the rate valid on ITS reference date, saved and flushed; with no
   * configured rate it is saved with {@code null}. A processed, an incomplete (no reference date) and
   * an org-currency document are left alone.
   */
  @Test
  public void recomputeSetsTheRateOfTheReferenceDateOrNull() {
    Object[][] cases = {
        // name, currency, date, processed, saved, expected rate
        { "rate configured", "usd", "2026-03-20", false, true, "1.3" },
        { "no rate configured", "usd", "2026-06-30", false, true, null },
        { "processed", "usd", "2026-03-20", true, false, null },
        { "no reference date", "usd", null, false, false, null },
        { "org currency", "eur", "2026-03-20", false, false, null },
    };
    for (Object[] c : cases) {
      String name = (String) c[0];
      Mockito.clearInvocations(dal);
      Order doc = storedDoc(ORDER_TYPE, (String) c[1], (String) c[2], null, (Boolean) c[3]);

      BigDecimal rate = new DocumentCurrencyRate(ORDER_TYPE).recompute(doc);

      if (c[5] == null) {
        assertNull(name, rate);
      } else {
        assertEquals(name, 0, new BigDecimal((String) c[5]).compareTo(rate));
        assertEquals(name, DocumentCurrencyRate.RATE_SCALE, rate.scale());
      }
      if ((Boolean) c[4]) {
        verify(doc).set(RATE, rate);
        verify(dal).save(doc);
        verify(dal).flush();
      } else {
        verify(doc, never()).set(any(), any());
        verify(dal, never()).save(any());
      }
    }
  }

  // ---------------------------------------------------------------------------------------------
  // refuseCompletionWithoutRate
  // ---------------------------------------------------------------------------------------------

  /**
   * Completing a stored foreign-currency document without a rate is refused with the completion
   * 422; one with a positive rate, an org-currency one and a missing one are let through.
   */
  @Test
  public void refuseCompletionWithoutRateOnlyForAForeignDocumentWithoutRate() throws Exception {
    DocumentCurrencyRate rate = new DocumentCurrencyRate(ORDER_TYPE);

    assertNull("missing document", rate.refuseCompletionWithoutRate(DOC_ID));

    storedDoc(ORDER_TYPE, "usd", "2026-03-01", BigDecimal.ZERO, false);
    NeoResponse refusal = rate.refuseCompletionWithoutRate(DOC_ID);
    assertEquals(422, refusal.getHttpStatus());
    JSONObject error = refusal.getBody().getJSONObject("error");
    assertEquals(DocumentRateMessages.CODE_MISSING_ON_COMPLETE, error.getString("code"));
    assertEquals("usd", error.getJSONObject(NeoProcessService.MESSAGE_PARAMS)
        .getString("documentCurrency"));

    storedDoc(ORDER_TYPE, "usd", "2026-03-01", new BigDecimal("1.10"), false);
    assertNull("own positive rate", rate.refuseCompletionWithoutRate(DOC_ID));

    storedDoc(ORDER_TYPE, "eur", "2026-03-01", null, false);
    assertNull("org currency", rate.refuseCompletionWithoutRate(DOC_ID));
  }

  /**
   * The create-and-complete generation flows: {@code requireRateForCompletion} throws exactly when
   * {@code missingRateOnCompletion} yields a refusal (foreign currency, empty rate), and the exception
   * answers the same 422 completion refusal; a positive rate or the org currency pass silently.
   */
  @Test
  public void requireRateForCompletionThrowsOnlyForAForeignDocumentWithoutRate() throws Exception {
    Object[][] passing = {
        { "own positive rate", "usd", new BigDecimal("1.10") },
        { "org currency", "eur", null },
    };
    for (Object[] c : passing) {
      Order doc = storedDoc(ORDER_TYPE, (String) c[1], "2026-03-01", (BigDecimal) c[2], false);
      DocumentCurrencyRate.requireRateForCompletion(doc, RATE);
      assertNull((String) c[0], DocumentCurrencyRate.missingRateOnCompletion(doc, RATE));
    }

    Order foreign = storedDoc(ORDER_TYPE, "usd", "2026-03-01", null, false);
    DocumentCurrencyRate.MissingRateException thrown = null;
    try {
      DocumentCurrencyRate.requireRateForCompletion(foreign, RATE);
    } catch (DocumentCurrencyRate.MissingRateException e) {
      thrown = e;
    }
    assertNotNull("a foreign document without rate must be refused", thrown);
    assertEquals(DocumentCurrencyRate.missingRateOnCompletion(foreign, RATE), thrown.getMessage());
    NeoResponse response = thrown.toResponse();
    assertEquals(422, response.getHttpStatus());
    JSONObject error = response.getBody().getJSONObject("error");
    assertEquals(DocumentRateMessages.CODE_MISSING_ON_COMPLETE, error.getString("code"));
    JSONObject params = error.getJSONObject(NeoProcessService.MESSAGE_PARAMS);
    assertEquals("eur", params.getString("orgCurrency"));
    assertEquals("usd", params.getString("documentCurrency"));
  }

  /**
   * A batch create whose currency is a {@code $ref:} to an earlier operation does not know its
   * currency yet: it is deferred to the post-hook, not refused nor recalculated with a bogus id.
   */
  @Test
  public void beforeWriteDefersACreateWhoseCurrencyIsABatchReference() throws Exception {
    JSONObject body = new JSONObject().put("currency", BatchService.REF_PREFIX + "op1.id")
        .put("orderDate", "2026-03-20").put("organization", "org-1");

    assertNull(new DocumentCurrencyRate(ORDER_TYPE).beforeWrite(write(null, body), false));
    assertFalse(body.has(RATE));
    rateService.verify(() -> NeoExchangeRateService.rateAsDecimal(any(), any(), any(), any(),
        any()), never());
  }
}
