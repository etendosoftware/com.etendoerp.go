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

package com.etendoerp.go.schemaforge.ocr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.Test;

/**
 * The amounts of an inbox document (ETP-6050): the GO rule of two roundings, HALF_UP, tax per rate
 * on the sum of the nets. The cases of this module's own file
 * ({@code invoice-inbox-totals-cases.json}, scales 0/2/3, both tax modes, signs, rounding order)
 * are run here too.
 *
 * @covers com.etendoerp.go.schemaforge.ocr.InvoiceInboxTotals
 */
class InvoiceInboxTotalsTest {

  private static final String CASES =
      "/com/etendoerp/go/schemaforge/ocr/invoice-inbox-totals-cases.json";

  private static BigDecimal d(String value) {
    return new BigDecimal(value);
  }

  private static JSONArray lines(String json) throws Exception {
    return new JSONArray(json);
  }

  // ------------------------------------------------------------------ the cases file

  private static JSONArray cases() throws Exception {
    try (InputStream in = InvoiceInboxTotalsTest.class.getResourceAsStream(CASES)) {
      assertNotNull(in, "the cases file " + CASES + " is on the test classpath");
      return new JSONObject(new String(in.readAllBytes(), StandardCharsets.UTF_8))
          .getJSONArray("cases");
    }
  }

  @Test
  void everyCaseOfTheCasesFileGivesTheExpectedCents() throws Exception {
    JSONArray cases = cases();
    assertTrue(cases.length() > 0, "the cases file has cases");

    for (int c = 0; c < cases.length(); c++) {
      JSONObject current = cases.getJSONObject(c);
      String name = current.getString("name");
      JSONArray input = current.getJSONArray("lines");
      JSONObject expected = current.getJSONObject("expected");
      int scale = current.optInt("scale", 2);

      for (int i = 0; i < input.length(); i++) {
        JSONObject line = input.getJSONObject(i);
        BigDecimal discount = line.has("discount") ? d(line.getString("discount")) : null;
        BigDecimal quantity = d(line.getString("quantity"));
        BigDecimal price = d(line.getString("unitPrice"));
        JSONObject expectedLine = expected.getJSONArray("lines").getJSONObject(i);
        assertEquals(expectedLine.getString("unit6"),
            InvoiceInboxTotals.unit6(price, discount).toPlainString(), name + " unit6 " + i);
        assertEquals(expectedLine.getString("net"),
            InvoiceInboxTotals.lineNet(quantity, price, discount, scale).toPlainString(),
            name + " net " + i);
      }

      InvoiceInboxTotals.Totals totals = InvoiceInboxTotals.totals(input, scale);
      if (input.length() == 0) {
        assertNull(totals, name);
        // Java has no totals for no lines (the document total is then 0)
        assertEquals(0, d(expected.getString("linesTotal")).signum(), name);
        continue;
      }
      assertNotNull(totals, name);
      BigDecimal tax = BigDecimal.ZERO;
      JSONArray taxes = expected.getJSONArray("taxes");
      assertEquals(taxes.length(), totals.rows().size(), name + " tax rows");
      for (int t = 0; t < taxes.length(); t++) {
        JSONObject row = taxes.getJSONObject(t);
        InvoiceInboxTotals.TaxRow actual = totals.rows().get(t);
        tax = tax.add(d(row.getString("amount")));
        assertEquals(0, d(row.getString("rate")).compareTo(actual.rate()), name + " rate " + t);
        assertEquals("L".equals(row.optString("docTaxAmount", "D")), actual.lineBased(),
            name + " mode " + t);
        assertEquals(row.getString("base"), actual.base().toPlainString(), name + " row base " + t);
        assertEquals(row.getString("amount"), actual.amount().toPlainString(),
            name + " row amount " + t);
      }
      assertEquals(expected.getString("base"), totals.base().toPlainString(), name + " base");
      assertEquals(tax.setScale(scale).toPlainString(), totals.tax().toPlainString(),
          name + " tax");
      assertEquals(expected.getString("linesTotal"), totals.total().toPlainString(),
          name + " total");
    }
  }

  // ------------------------------------------------------------------ the order cases, inline

  @Test
  void theOrderCasesFollowTheRealRuleAndNotTheSingleRoundingFormula() throws Exception {
    // quantity x price x (1 - d) rounded once would give 3056.79, 33000.70 and 10170.66
    assertEquals("0.063256", InvoiceInboxTotals.unit6(d("0.2771"), d("77.1721")).toPlainString());
    assertEquals("3056.78", InvoiceInboxTotals.lineNet(d("48324"), d("0.2771"), d("77.1721"))
        .toPlainString());
    assertEquals("33000.71", InvoiceInboxTotals.lineNet(d("88782"), d("0.5049"), d("26.3805"))
        .toPlainString());
    assertEquals("10170.65", InvoiceInboxTotals.lineNet(d("80422"), d("0.3477"), d("63.6278"))
        .toPlainString());
  }

  @Test
  void aHalfCentRoundsAwayFromZeroAndSoDoesItsNegativeTwin() {
    assertEquals("0.13", InvoiceInboxTotals.lineNet(d("1"), d("0.125"), null).toPlainString());
    assertEquals("-0.13", InvoiceInboxTotals.lineNet(d("-1"), d("0.125"), null).toPlainString());
  }

  @Test
  void anAbsentDiscountAndAZeroOneAreTheSame() {
    assertEquals(InvoiceInboxTotals.lineNet(d("7"), d("1.2345"), null),
        InvoiceInboxTotals.lineNet(d("7"), d("1.2345"), BigDecimal.ZERO));
    assertEquals("1.234500", InvoiceInboxTotals.unit6(d("1.2345"), null).toPlainString());
  }

  @Test
  void aDiscountOutsideZeroToHundredIsReadAsNoDiscount() {
    assertEquals("10.000000", InvoiceInboxTotals.unit6(d("10"), d("-1")).toPlainString());
    assertEquals("10.000000", InvoiceInboxTotals.unit6(d("10"), d("100.0001")).toPlainString());
    assertEquals("0.000000", InvoiceInboxTotals.unit6(d("10"), d("100")).toPlainString());
  }

  @Test
  void theTaxIsRoundedPerRateOnTheSumOfTheNets() throws Exception {
    // two lines of 0.05 net each at 10 %: per line 0.005 -> 0.01 twice would be 0.02; the sum
    // 0.10 x 10 % = 0.01
    InvoiceInboxTotals.Totals totals = InvoiceInboxTotals.totals(lines(
        "[{quantity:1,unitPrice:0.05,taxRate:10},{quantity:1,unitPrice:0.05,taxRate:10.0}]"));

    assertEquals("0.10", totals.base().toPlainString());
    assertEquals("0.01", totals.tax().toPlainString());
    assertEquals("0.11", totals.total().toPlainString());
  }

  @Test
  void aLineWithoutARateAddsToTheBaseOnly() throws Exception {
    InvoiceInboxTotals.Totals totals = InvoiceInboxTotals.totals(lines(
        "[{quantity:2,unitPrice:10,taxRate:21},{quantity:1,unitPrice:5}]"));

    assertEquals("25.00", totals.base().toPlainString());
    assertEquals("4.20", totals.tax().toPlainString());
    assertEquals("29.20", totals.total().toPlainString());
  }

  @Test
  void aLineWithoutQuantityOrPriceContributesNothing() throws Exception {
    InvoiceInboxTotals.Totals totals = InvoiceInboxTotals.totals(lines(
        "[{quantity:2,unitPrice:10,taxRate:0},{unitPrice:99,taxRate:21},{quantity:3,taxRate:21},"
            + "{quantity:null,unitPrice:null}]"));

    assertEquals("20.00", totals.total().toPlainString());
  }

  @Test
  void noLinesAtAllIsNullAndNullLinesIsNullToo() throws Exception {
    assertNull(InvoiceInboxTotals.totals(lines("[]")));
    assertNull(InvoiceInboxTotals.totals(null));
    assertNull(InvoiceInboxTotals.totals(lines("[{description:'x'}]")));
  }

  @Test
  void theDiscountOfALineIsReadFromItsDiscountKey() throws Exception {
    InvoiceInboxTotals.Totals totals = InvoiceInboxTotals.totals(lines(
        "[{quantity:3,unitPrice:100,discount:10,taxRate:21}]"));

    assertEquals("270.00", totals.base().toPlainString());
    assertEquals("326.70", totals.total().toPlainString());
  }

  // ------------------------------------------------------------------ signs, zero, scales, modes

  @Test
  void aNegativeQuantityIsACreditLineWithANegativeTax() throws Exception {
    InvoiceInboxTotals.Totals totals = InvoiceInboxTotals.totals(lines(
        "[{quantity:-2,unitPrice:50,taxRate:21}]"));

    assertEquals("-100.00", totals.base().toPlainString());
    assertEquals("-21.00", totals.tax().toPlainString());
    assertEquals("-121.00", totals.total().toPlainString());
  }

  @Test
  void aNegativePriceIsACreditLineToo() throws Exception {
    InvoiceInboxTotals.Totals totals = InvoiceInboxTotals.totals(lines(
        "[{quantity:3,unitPrice:-10,taxRate:21}]"));

    assertEquals("-30.00", totals.base().toPlainString());
    assertEquals("-6.30", totals.tax().toPlainString());
    assertEquals("-36.30", totals.total().toPlainString());
  }

  @Test
  void aNegativeAndAPositiveLineNetAgainstEachOtherInTheSameRate() throws Exception {
    InvoiceInboxTotals.Totals totals = InvoiceInboxTotals.totals(lines(
        "[{quantity:1,unitPrice:100,taxRate:21},{quantity:-1,unitPrice:40,taxRate:21}]"));

    assertEquals("60.00", totals.base().toPlainString());
    assertEquals("12.60", totals.tax().toPlainString());
    assertEquals(1, totals.rows().size());
    assertEquals("60.00", totals.rows().get(0).base().toPlainString());
  }

  @Test
  void aZeroQuantityLineIsAPresentLineOfZero() throws Exception {
    InvoiceInboxTotals.Totals totals = InvoiceInboxTotals.totals(lines(
        "[{quantity:0,unitPrice:50,taxRate:21}]"));

    assertNotNull(totals, "quantity and price are there: the total is 0, not unknown");
    assertEquals("0.00", totals.base().toPlainString());
    assertEquals("0.00", totals.tax().toPlainString());
    assertEquals("0.00", totals.total().toPlainString());
    assertEquals(1, totals.rows().size());
  }

  @Test
  void aZeroPriceLineIsAPresentLineOfZeroToo() throws Exception {
    assertEquals("0.00", InvoiceInboxTotals.totals(lines(
        "[{quantity:5,unitPrice:0,taxRate:10}]")).total().toPlainString());
  }

  @Test
  void aCreditDocumentRoundsItsLineBasedTaxPerLineAndItsDocumentBasedTaxOnTheSum()
      throws Exception {
    // each line: -1 x 0.125 -> net -0.13 (half away from zero)
    String line = "{quantity:-1,unitPrice:0.125,taxRate:21,tax:{docTaxAmount:'%s'}}";

    InvoiceInboxTotals.Totals documentBased = InvoiceInboxTotals.totals(lines(
        "[" + String.format(line, "D") + "," + String.format(line, "D") + "]"));
    InvoiceInboxTotals.Totals lineBased = InvoiceInboxTotals.totals(lines(
        "[" + String.format(line, "L") + "," + String.format(line, "L") + "]"));

    assertEquals("-0.26", documentBased.base().toPlainString());
    assertEquals("-0.05", documentBased.tax().toPlainString(), "-0.26 x 21 % = -0.0546");
    assertEquals("-0.26", lineBased.base().toPlainString());
    assertEquals("-0.06", lineBased.tax().toPlainString(), "-0.13 x 21 % = -0.0273 per line");
    assertEquals("-0.32", lineBased.total().toPlainString());
    assertTrue(lineBased.rows().get(0).lineBased());
    assertFalse(documentBased.rows().get(0).lineBased());
  }

  @Test
  void aDocumentWithBothModesKeepsOneRowPerRateAndModeSortedByRate() throws Exception {
    InvoiceInboxTotals.Totals totals = InvoiceInboxTotals.totals(lines(
        "[{quantity:1,unitPrice:10.05,taxRate:21,tax:{docTaxAmount:'L'}},"
            + "{quantity:1,unitPrice:10.05,taxRate:21,tax:{docTaxAmount:'D'}},"
            + "{quantity:1,unitPrice:100,taxRate:4}]"));

    assertEquals(3, totals.rows().size());
    assertEquals(0, new BigDecimal("4").compareTo(totals.rows().get(0).rate()));
    assertFalse(totals.rows().get(0).lineBased());
    assertEquals(0, new BigDecimal("21").compareTo(totals.rows().get(1).rate()));
    assertFalse(totals.rows().get(1).lineBased(), "the D row comes before the L row of a rate");
    assertTrue(totals.rows().get(2).lineBased());
    // 4.00 + 2.11 (10.05 x 21 % = 2.1105) + 2.11
    assertEquals("8.22", totals.tax().toPlainString());
  }

  @Test
  void anUnknownOrHostileRoundingModeReadsAsDocumentBased() throws Exception {
    for (String mode : new String[] { "l", "L ", "LINE", "<script>", "", "D", "1" }) {
      InvoiceInboxTotals.Totals totals = InvoiceInboxTotals.totals(lines(
          "[{quantity:1,unitPrice:0.05,taxRate:10,tax:{docTaxAmount:'" + mode + "'}},"
              + "{quantity:1,unitPrice:0.05,taxRate:10,tax:{docTaxAmount:'" + mode + "'}}]"));

      assertFalse(totals.rows().get(0).lineBased(), "mode '" + mode + "'");
      assertEquals("0.01", totals.tax().toPlainString(), "mode '" + mode + "'");
    }
    // a non-string mode
    InvoiceInboxTotals.Totals numeric = InvoiceInboxTotals.totals(lines(
        "[{quantity:1,unitPrice:10,taxRate:10,tax:{docTaxAmount:5}}]"));
    assertFalse(numeric.rows().get(0).lineBased());
  }

  @Test
  void aTaxBlockWithoutAnyModeIsDocumentBased() throws Exception {
    InvoiceInboxTotals.Totals totals = InvoiceInboxTotals.totals(lines(
        "[{quantity:1,unitPrice:10,taxRate:10,tax:{state:'resolved',id:'T10'}}]"));

    assertFalse(totals.rows().get(0).lineBased());
  }

  @Test
  void aCurrencyWithoutDecimalsRoundsEveryAmountToWholeUnits() throws Exception {
    // 3 x 0.5 = 1.5 -> 2 (half up), twice: base 4; tax 10 % of 4 = 0.4 -> 0
    JSONArray documentBased = lines(
        "[{quantity:3,unitPrice:0.5,taxRate:10},{quantity:3,unitPrice:0.5,taxRate:10}]");

    InvoiceInboxTotals.Totals totals = InvoiceInboxTotals.totals(documentBased, 0);

    assertEquals("4", totals.base().toPlainString());
    assertEquals("0", totals.tax().toPlainString());
    assertEquals("4", totals.total().toPlainString());
    assertEquals("2", InvoiceInboxTotals.lineNet(d("3"), d("0.5"), null, 0).toPlainString());
    assertEquals("-2", InvoiceInboxTotals.lineNet(d("-3"), d("0.5"), null, 0).toPlainString());
  }

  @Test
  void aCurrencyWithoutDecimalsRoundsALineBasedTaxPerLine() throws Exception {
    // each line net 5, tax 10 % = 0.5 -> 1 (half up) per line: 2; on the sum 10 x 10 % = 1
    String line = "{quantity:1,unitPrice:5,taxRate:10,tax:{docTaxAmount:'%s'}}";

    assertEquals("2", InvoiceInboxTotals.totals(lines("[" + String.format(line, "L") + ","
        + String.format(line, "L") + "]"), 0).tax().toPlainString());
    assertEquals("1", InvoiceInboxTotals.totals(lines("[" + String.format(line, "D") + ","
        + String.format(line, "D") + "]"), 0).tax().toPlainString());
  }

  @Test
  void aCurrencyOfThreeDecimalsKeepsThreeInTheNetAndTheTax() throws Exception {
    // 1 x 0.12345 -> 0.123 ; tax 21 % of 0.123 = 0.02583 -> 0.026
    InvoiceInboxTotals.Totals totals = InvoiceInboxTotals.totals(lines(
        "[{quantity:1,unitPrice:0.12345,taxRate:21}]"), 3);

    assertEquals("0.123", totals.base().toPlainString());
    assertEquals("0.026", totals.tax().toPlainString());
    assertEquals("0.149", totals.total().toPlainString());
    assertEquals("0.124", InvoiceInboxTotals.lineNet(d("1"), d("0.1235"), null, 3)
        .toPlainString());
  }

  @Test
  void theTwoDecimalOverloadsAreTheScaleTwoOnes() throws Exception {
    JSONArray input = lines("[{quantity:3,unitPrice:0.3333,taxRate:21}]");

    assertEquals(InvoiceInboxTotals.totals(input, 2).total().toPlainString(),
        InvoiceInboxTotals.totals(input).total().toPlainString());
    assertEquals(InvoiceInboxTotals.lineNet(d("3"), d("0.3333"), d("5"), 2),
        InvoiceInboxTotals.lineNet(d("3"), d("0.3333"), d("5")));
  }

  @Test
  void aDiscountAboveAHundredOrBelowZeroIsNoDiscountInTheTotals() throws Exception {
    for (String discount : new String[] { "150", "-5", "100.01" }) {
      InvoiceInboxTotals.Totals totals = InvoiceInboxTotals.totals(lines(
          "[{quantity:2,unitPrice:50,discount:" + discount + ",taxRate:0}]"));

      assertEquals("100.00", totals.total().toPlainString(), "discount " + discount);
    }
    assertEquals("0.00", InvoiceInboxTotals.totals(lines(
        "[{quantity:2,unitPrice:50,discount:100,taxRate:0}]")).total().toPlainString(),
        "a full discount is a free line, not an ignored one");
  }

  @Test
  void theRateIsTheLinesOwnThenTheTaxBlocksAndHostileTextIsNoRate() throws Exception {
    assertEquals("10.00", InvoiceInboxTotals.totals(lines(
        "[{quantity:1,unitPrice:100,taxRate:10,tax:{rate:21}}]")).tax().toPlainString(),
        "the line's own rate wins over the block's");
    assertEquals("21.00", InvoiceInboxTotals.totals(lines(
        "[{quantity:1,unitPrice:100,tax:{rate:21}}]")).tax().toPlainString());
    // hostile text where a number is expected reads as no rate: the line adds to the base only
    InvoiceInboxTotals.Totals hostile = InvoiceInboxTotals.totals(lines(
        "[{quantity:1,unitPrice:100,taxRate:'<script>alert(1)</script>',"
            + "tax:{rate:'1 OR 1=1',docTaxAmount:'L'}}]"));
    assertEquals("100.00", hostile.total().toPlainString());
    assertTrue(hostile.rows().isEmpty());
    assertNull(InvoiceInboxTotals.effectiveRate(null));
    assertNull(InvoiceInboxTotals.effectiveRate(new JSONObject()));
    assertEquals("5.21", InvoiceInboxTotals.effectiveRate(new JSONObject("{taxRate:5.2051}"))
        .toPlainString());
  }
}
