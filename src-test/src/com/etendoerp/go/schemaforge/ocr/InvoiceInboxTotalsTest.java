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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.Test;

/**
 * The amounts of an inbox document (ETP-6050): the GO rule of two roundings, HALF_UP, tax per rate
 * on the sum of the nets. The cases of the SPA's shared fixture
 * ({@code invoice-inbox-totals.json}) are run here too, so both sides compute the same cents.
 *
 * @covers com.etendoerp.go.schemaforge.ocr.InvoiceInboxTotals
 */
class InvoiceInboxTotalsTest {

  private static final String FIXTURE =
      "etendo_schema_forge/tools/app-shell/__tests__/fixtures/invoice-inbox-totals.json";

  private static BigDecimal d(String value) {
    return new BigDecimal(value);
  }

  private static JSONArray lines(String json) throws Exception {
    return new JSONArray(json);
  }

  // ------------------------------------------------------------------ the shared fixture

  /** The fixture of the SPA repo, or {@code null} when it is not checked out beside the module. */
  private static File fixture() {
    String base = System.getProperty("user.dir");
    for (String relative : new String[] { "../../", "../", "", "../../../" }) {
      File candidate = new File(base, relative + FIXTURE);
      if (candidate.isFile()) {
        return candidate;
      }
    }
    return null;
  }

  @Test
  void everyCaseOfTheSharedFixtureGivesTheSameCents() throws Exception {
    File file = fixture();
    if (file == null && (Boolean.getBoolean("etgo.requireSpaFixture")
        || "true".equals(System.getenv("ETGO_REQUIRE_SPA_FIXTURE")))) {
      // A pipeline that has the SPA repo checked out sets the flag: a missing fixture is then a
      // failure, not a skip that nobody sees.
      fail("The shared fixture " + FIXTURE + " was not found and the SPA repo is required");
    }
    assumeTrue(file != null, "SKIPPED: the SPA repo is not beside the module, so the shared "
        + "fixture " + FIXTURE + " was not compared (the inline cases below still run; set "
        + "ETGO_REQUIRE_SPA_FIXTURE=true to make this a failure)");
    JSONArray cases = new JSONObject(new String(Files.readAllBytes(file.toPath()),
        StandardCharsets.UTF_8)).getJSONArray("cases");

    for (int c = 0; c < cases.length(); c++) {
      JSONObject current = cases.getJSONObject(c);
      String name = current.getString("name");
      JSONArray input = current.getJSONArray("lines");
      JSONObject expected = current.getJSONObject("expected");

      for (int i = 0; i < input.length(); i++) {
        JSONObject line = input.getJSONObject(i);
        BigDecimal discount = line.has("discount") ? d(line.getString("discount")) : null;
        BigDecimal quantity = d(line.getString("quantity"));
        BigDecimal price = d(line.getString("unitPrice"));
        JSONObject expectedLine = expected.getJSONArray("lines").getJSONObject(i);
        assertEquals(expectedLine.getString("unit6"),
            InvoiceInboxTotals.unit6(price, discount).toPlainString(), name + " unit6 " + i);
        assertEquals(expectedLine.getString("net"),
            InvoiceInboxTotals.lineNet(quantity, price, discount).toPlainString(),
            name + " net " + i);
      }

      InvoiceInboxTotals.Totals totals = InvoiceInboxTotals.totals(input);
      if (input.length() == 0) {
        assertNull(totals, name);
        // Java has no totals for no lines (the document total is then 0)
        assertEquals(0, d(expected.getString("linesTotal")).signum(), name);
        continue;
      }
      assertNotNull(totals, name);
      BigDecimal tax = BigDecimal.ZERO;
      JSONArray taxes = expected.getJSONArray("taxes");
      for (int t = 0; t < taxes.length(); t++) {
        tax = tax.add(d(taxes.getJSONObject(t).getString("amount")));
      }
      assertEquals(expected.getString("base"), totals.base().toPlainString(), name + " base");
      assertEquals(tax.setScale(2).toPlainString(), totals.tax().toPlainString(), name + " tax");
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
}
