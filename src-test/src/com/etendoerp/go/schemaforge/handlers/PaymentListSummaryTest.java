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
 * All portions are Copyright © 2021–2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.schemaforge.handlers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.math.BigDecimal;
import java.util.List;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.Test;

import com.etendoerp.go.schemaforge.NeoCurrencyTotals;
import com.etendoerp.go.schemaforge.NeoCurrencyTotals.TargetCurrency;

/**
 * Guards the buckets of the payment list summary (ETP-5778).
 *
 * @covers com.etendoerp.go.schemaforge.handlers.PaymentListSummary
 */
public class PaymentListSummaryTest {

  private static final TargetCurrency EUR = new TargetCurrency("eur", "EUR", 2);
  private static final NeoCurrencyTotals CONVERTER = new NeoCurrencyTotals(
      (from, to) -> "usd".equals(from) ? new BigDecimal("0.5") : null);

  private static PaymentListSummary.Group group(String cur, String iso, String method,
      String status, String amount, long count) {
    return new PaymentListSummary.Group(cur, iso, method, "M-" + method, status,
        new BigDecimal(amount), count);
  }

  @Test
  public void bucketsExcludeVoidAndConvertForeignAmounts() throws Exception {
    JSONObject s = PaymentListSummary.build(List.of(
        group("eur", "EUR", "cash", "RPR", "100.00", 2),
        group("usd", "USD", "cash", "RPR", "50.00", 3),
        group("eur", "EUR", "wire", "RPAE", "10.00", 1),
        group("eur", "EUR", "wire", "RPAP", "7.00", 4),
        group("eur", "EUR", "wire", "RPVOID", "999.00", 9)), EUR, CONVERTER);

    JSONObject total = s.getJSONObject("total");
    assertEquals(0, new BigDecimal("135.00").compareTo((BigDecimal) total.get("amount")));
    assertTrue(total.getBoolean("approximate"));
    assertEquals(6, total.getInt("count"));
    assertEquals(4, s.getInt("draftCount"));
    assertEquals(10, s.getInt("rowCount"));
    assertFalse(s.getJSONObject("pending").getBoolean("approximate"));
    assertEquals("cash", s.getJSONArray("byMethod").getJSONObject(0).getString("id"));
    assertEquals("EUR", s.getJSONArray("byCurrency").getJSONObject(0).getString("currencyIso"));
  }

  @Test
  public void emptySetYieldsZeroedSummary() throws Exception {
    JSONObject s = PaymentListSummary.build(List.of(), EUR, CONVERTER);
    assertEquals(0, s.getInt("rowCount"));
    assertEquals(0, s.getJSONArray("byCurrency").length());
  }

  @Test
  public void missingRateIsListedAndExcluded() throws Exception {
    JSONObject s = PaymentListSummary.build(
        List.of(group("gbp", "GBP", "cash", "RPR", "20.00", 1)), EUR, CONVERTER);
    assertEquals("GBP", s.getJSONObject("total").getJSONArray("missingRateCurrencies").get(0));
  }

  private static BigDecimal amt(JSONObject o, String key) throws Exception {
    return (BigDecimal) o.get(key);
  }

  @Test
  public void depositedStatusesMatchTheGridList() {
    assertEquals(java.util.Set.of("RPR", "RPPC", "RDNC", "PPM", "PWNC", "RPAE"),
        PaymentListSummary.DEPOSITED_STATUSES);
  }

  @Test
  public void everyDepositedStatusCountsAsTotalNotPending() throws Exception {
    for (String status : PaymentListSummary.DEPOSITED_STATUSES) {
      JSONObject s = PaymentListSummary.build(
          List.of(group("eur", "EUR", "cash", status, "10.00", 1)), EUR, CONVERTER);
      assertEquals(status, 1, s.getJSONObject("total").getInt("count"));
      assertEquals(status, 0, s.getJSONObject("pending").getInt("count"));
    }
  }

  @Test
  public void voidIsExcludedFromEveryBucket() throws Exception {
    JSONObject s = PaymentListSummary.build(
        List.of(group("eur", "EUR", "cash", "RPVOID", "500.00", 5)), EUR, CONVERTER);
    assertEquals(0, s.getInt("rowCount"));
    assertEquals(0, s.getInt("draftCount"));
    assertEquals(0, s.getJSONObject("total").getInt("count"));
    assertEquals(0, s.getJSONObject("pending").getInt("count"));
    assertEquals(0, s.getJSONArray("byMethod").length());
    assertEquals(0, s.getJSONArray("byCurrency").length());
  }

  @Test
  public void errorAndUnknownStatusesArePending() throws Exception {
    JSONObject s = PaymentListSummary.build(List.of(
        group("eur", "EUR", "cash", "ETGOERR", "3.00", 2),
        group("eur", "EUR", "cash", "WHATEVER", "4.00", 1)), EUR, CONVERTER);
    assertEquals(3, s.getJSONObject("pending").getInt("count"));
    assertEquals(3, s.getInt("draftCount"));
    assertEquals(0, s.getJSONObject("total").getInt("count"));
    assertEquals(3, s.getInt("rowCount"));
    assertEquals(0, new BigDecimal("7.00").compareTo(amt(s.getJSONObject("pending"), "amount")));
    // pending never feeds the deposited breakdowns
    assertEquals(0, s.getJSONArray("byMethod").length());
    assertEquals(0, s.getJSONArray("byCurrency").length());
  }

  @Test
  public void byCurrencyKeepsOriginalAmountsOrgFirstThenAmountDesc() throws Exception {
    JSONObject s = PaymentListSummary.build(List.of(
        group("usd", "USD", "cash", "RPR", "900.00", 1),
        group("eur", "EUR", "cash", "RPR", "10.00", 2),
        group("eur", "EUR", "wire", "RPAE", "5.00", 1),
        group("gbp", "GBP", "cash", "RPR", "20.00", 1)), EUR, CONVERTER);
    JSONArray rows = s.getJSONArray("byCurrency");
    assertEquals(3, rows.length());
    assertEquals("EUR", rows.getJSONObject(0).getString("currencyIso"));
    assertEquals(0, new BigDecimal("15.00").compareTo(amt(rows.getJSONObject(0), "total")));
    assertEquals(3, rows.getJSONObject(0).getInt("count"));
    assertEquals("USD", rows.getJSONObject(1).getString("currencyIso"));
    // original amount, not converted at 0.5
    assertEquals(0, new BigDecimal("900.00").compareTo(amt(rows.getJSONObject(1), "total")));
    assertEquals("GBP", rows.getJSONObject(2).getString("currencyIso"));
  }

  @Test
  public void byCurrencyTiesOnAmountAreOrderedByIso() throws Exception {
    JSONObject s = PaymentListSummary.build(List.of(
        group("usd", "USD", "cash", "RPR", "10.00", 1),
        group("gbp", "GBP", "cash", "RPR", "10.00", 1)), EUR, CONVERTER);
    assertEquals("GBP", s.getJSONArray("byCurrency").getJSONObject(0).getString("currencyIso"));
  }

  @Test
  public void byMethodConvertsPerMethodWithItsOwnApproximateFlag() throws Exception {
    JSONObject s = PaymentListSummary.build(List.of(
        group("eur", "EUR", "cash", "RPR", "10.00", 1),
        group("usd", "USD", "card", "RPR", "100.00", 1),
        group("eur", "EUR", "card", "RPR", "1.00", 1)), EUR, CONVERTER);
    JSONArray methods = s.getJSONArray("byMethod");
    JSONObject card = methods.getJSONObject(0);
    JSONObject cash = methods.getJSONObject(1);
    assertEquals("card", card.getString("id"));
    assertEquals("M-card", card.getString("label"));
    assertEquals(0, new BigDecimal("51.00").compareTo(amt(card, "amount")));
    assertTrue(card.getBoolean("approximate"));
    assertEquals(2, card.getInt("count"));
    assertEquals("cash", cash.getString("id"));
    assertFalse(cash.getBoolean("approximate"));
  }

  @Test
  public void byMethodTiesOnAmountAreOrderedByLabel() throws Exception {
    JSONObject s = PaymentListSummary.build(List.of(
        group("eur", "EUR", "zeta", "RPR", "10.00", 1),
        group("eur", "EUR", "alpha", "RPR", "10.00", 1)), EUR, CONVERTER);
    assertEquals("alpha", s.getJSONArray("byMethod").getJSONObject(0).getString("id"));
  }

  @Test
  public void missingRateIsReportedPerBucket() throws Exception {
    JSONObject s = PaymentListSummary.build(List.of(
        group("gbp", "GBP", "cash", "RPR", "20.00", 1),
        group("usd", "USD", "cash", "RPAP", "20.00", 1)), EUR, CONVERTER);
    assertEquals(1, s.getJSONObject("total").getJSONArray("missingRateCurrencies").length());
    assertEquals(0, s.getJSONObject("pending").getJSONArray("missingRateCurrencies").length());
  }

  @Test
  public void nullOrgCurrencyFallsBackToRawSums() throws Exception {
    JSONObject s = PaymentListSummary.build(List.of(
        group("usd", "USD", "cash", "RPR", "10.00", 1),
        group("eur", "EUR", "cash", "RPR", "5.00", 1)), null, CONVERTER);
    assertEquals(0, new BigDecimal("15.00").compareTo(amt(s.getJSONObject("total"), "amount")));
    assertFalse(s.getJSONObject("total").getBoolean("approximate"));
    assertEquals("USD", s.getString("orgCurrencyIso"));
  }

  @Test
  public void emptySetHasZeroedBucketsAndNullIsoWithoutOrg() throws Exception {
    JSONObject s = PaymentListSummary.build(List.of(), null, CONVERTER);
    assertTrue(s.isNull("orgCurrencyIso"));
    assertEquals(0, s.getInt("draftCount"));
    assertEquals(0, s.getJSONObject("total").getInt("count"));
    assertEquals(0, BigDecimal.ZERO.compareTo(amt(s.getJSONObject("total"), "amount")));
    assertEquals(0, s.getJSONArray("byMethod").length());
  }
}
