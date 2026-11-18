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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.math.BigDecimal;

import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The bodies of the records a confirmed inbox line is created with: the invoice line (printed price
 * as the list price, net unit price after the discount, tax always an id) and the new product
 * (name, category, unit, search key, tax category).
 *
 * @covers com.etendoerp.go.schemaforge.InvoiceInboxConfirmLines
 */
class InvoiceInboxConfirmLinesTest {

  private static JSONObject line(String json) throws Exception {
    return new JSONObject(json);
  }

  private static void assertDecimal(String expected, Object actual) {
    assertEquals(0, new BigDecimal(expected).compareTo(new BigDecimal(actual.toString())),
        expected + " vs " + actual);
  }

  @Test
  @DisplayName("a line without discount: product, description, quantity, list price = unit price, tax id")
  void aPlainLine() throws Exception {
    JSONObject body = InvoiceInboxConfirmLines.lineBody(
        line("{description:'  Tornillo ',quantity:10,unitPrice:2.5}"), "P1", "T21");

    assertEquals("P1", body.getString("product"));
    assertEquals("Tornillo", body.getString("description"));
    assertDecimal("10", body.get("invoicedQuantity"));
    assertDecimal("2.5", body.get("listPrice"));
    assertDecimal("2.5", body.get("unitPrice"));
    assertEquals("T21", body.getString("tax"));
    assertFalse(body.has("etgoDiscount"));
  }

  @Test
  @DisplayName("a discount sends the printed price as list price, the net unit price and etgoDiscount")
  void aDiscount() throws Exception {
    JSONObject body = InvoiceInboxConfirmLines.lineBody(
        line("{description:'x',quantity:3,unitPrice:100,discount:10.5}"), "P1", "T21");

    assertDecimal("100", body.get("listPrice"));
    assertDecimal("89.5", body.get("unitPrice"));
    assertDecimal("10.5", body.get("etgoDiscount"));
  }

  @Test
  @DisplayName("the net unit price is rounded to six decimals")
  void sixDecimalDiscountRounding() throws Exception {
    JSONObject body = InvoiceInboxConfirmLines.lineBody(
        line("{quantity:1,unitPrice:1,discount:33.333333}"), "P1", null);

    assertDecimal("0.666667", body.get("unitPrice"));
    assertDecimal("33.333333", body.get("etgoDiscount"));
  }

  @Test
  @DisplayName("a discount outside 0-100 is read as none")
  void anInvalidDiscountIsIgnored() throws Exception {
    for (String discount : new String[] { "-1", "100.01", "250" }) {
      JSONObject body = InvoiceInboxConfirmLines.lineBody(
          line("{quantity:1,unitPrice:80,discount:" + discount + "}"), "P1", null);

      assertDecimal("80", body.get("unitPrice"));
      assertFalse(body.has("etgoDiscount"), discount);
    }
    assertDecimal("0", InvoiceInboxConfirmLines.discountOf(line("{discount:'abc'}")));
    assertDecimal("0", InvoiceInboxConfirmLines.discountOf(line("{}")));
    assertDecimal("100", InvoiceInboxConfirmLines.discountOf(line("{discount:100}")));
    assertDecimal("0", InvoiceInboxConfirmLines.discountOf(line("{discount:0}")));
  }

  @Test
  @DisplayName("a missing price sends no price at all, a missing quantity no quantity")
  void missingPriceOrQuantity() throws Exception {
    JSONObject noPrice = InvoiceInboxConfirmLines.lineBody(line("{quantity:2,discount:10}"), "P1",
        "T1");
    JSONObject noQuantity = InvoiceInboxConfirmLines.lineBody(line("{unitPrice:3}"), "P1", "T1");

    assertFalse(noPrice.has("listPrice"));
    assertFalse(noPrice.has("unitPrice"));
    assertFalse(noPrice.has("etgoDiscount"));
    assertFalse(noQuantity.has("invoicedQuantity"));
    assertDecimal("3", noQuantity.get("unitPrice"));
  }

  @Test
  @DisplayName("a blank tax id sends no tax (the line's own determination decides); a negative quantity is kept")
  void taxAndNegativeQuantity() throws Exception {
    JSONObject credit = InvoiceInboxConfirmLines.lineBody(line("{quantity:-2,unitPrice:5}"), "P1",
        "  ");

    assertFalse(credit.has("tax"));
    assertFalse(credit.has("description"));
    assertDecimal("-2", credit.get("invoicedQuantity"));
  }

  @Test
  @DisplayName("a new product takes the name, category and unit of the draft, the search key and the tax category")
  void aProductBody() throws Exception {
    JSONObject body = InvoiceInboxConfirmLines.productBody(line("{description:'Line text',"
        + "product:{state:'new',new:{name:' Tornillo M8 ',categoryId:'C1',uomId:'U1'}}}"),
        "TC1", "P-0007");

    assertEquals("Tornillo M8", body.getString("name"));
    assertEquals("C1", body.getString("productCategory"));
    assertEquals("U1", body.getString("uOM"));
    assertEquals("P-0007", body.getString("searchKey"));
    assertEquals("TC1", body.getString("taxCategory"));
  }

  @Test
  @DisplayName("a blank draft name falls back to the line description; no tax category leaves it out")
  void aProductBodyFallbacks() throws Exception {
    JSONObject body = InvoiceInboxConfirmLines.productBody(line("{description:' Line text ',"
        + "product:{state:'new',new:{name:'  ',categoryId:'C1',uomId:'U1'}}}"), null, "P-1");

    assertEquals("Line text", body.getString("name"));
    assertFalse(body.has("taxCategory"));
    assertEquals(4, body.length());
  }
}
