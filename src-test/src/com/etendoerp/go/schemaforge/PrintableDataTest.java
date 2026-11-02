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
 * All portions are Copyright (C) 2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */
package com.etendoerp.go.schemaforge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ETP-6013 — the commercial printables' data, ported from the browser's {@code buildInvoiceData},
 * {@code buildOrderData} and {@code buildQuotationData}.
 *
 * <p>Each fixture below is mirrored, input and expected output, by the Vitest
 * {@code printableSources.vitest.js} (its "shared parity fixture" tests), which runs the real
 * browser builder on it. A change to either builder must
 * keep both tests on the same data, or the server PDF differs from the previewed one.</p>
 *
 * <p>The fixture has no partner address and no logo, so the port runs without any read.</p>
 *
 * @covers com.etendoerp.go.schemaforge.SalesInvoicePrintableData
 * @covers com.etendoerp.go.schemaforge.NeoPrintableCommons
 * @covers com.etendoerp.go.schemaforge.OrderPrintableData
 * @covers com.etendoerp.go.schemaforge.QuotationPrintableData
 * @covers com.etendoerp.go.schemaforge.MovementPrintableData
 */
@DisplayName("ETP-6013 — the printables' data ports and NeoPrintableCommons")
class PrintableDataTest {

  private static final String HEADER = "{\"documentNo\":\"INV-001\",\"grandTotalAmount\":121,"
      + "\"summedLineAmount\":100,\"businessPartner$_identifier\":\"ACME Corp\","
      + "\"currency$_identifier\":\"EUR\",\"invoiceDate\":\"2026-10-30\","
      + "\"paymentMethod$_identifier\":\"Transfer\",\"paymentTerms$_identifier\":\"30 days\","
      + "\"description\":\"Thanks\",\"etgoTotalDiscount\":0,\"bpAddress\":\"Main St 1\"}";
  private static final String LINES = "[{\"lineNo\":20,\"product$_identifier\":\"Widget\","
      + "\"productCode\":\"W-1\",\"invoicedQuantity\":2,\"unitPrice\":45,\"listPrice\":50,"
      + "\"etgoDiscount\":10,\"tax$_identifier\":\"IVA 21%\",\"lineNetAmount\":90},"
      + "{\"lineNo\":10,\"product$_identifier\":\"Gadget\",\"invoicedQuantity\":1,\"unitPrice\":10,"
      + "\"tax$_identifier\":\"IVA 21%\",\"lineNetAmount\":10}]";
  private static final String SESSION = "{\"organization\":{\"name\":\"Org SL\","
      + "\"address1\":\"Calle 1\",\"address2\":null,\"cityLine\":\"08001 - Barcelona\","
      + "\"taxId\":\"B123\"}}";

  /** What the browser builds for the fixture (printableSources.vitest.js). */
  private static final String EXPECTED = "{\"companyName\":\"Org SL\",\"companyAddress1\":\"Calle 1\","
      + "\"companyAddress2\":null,\"companyCityLine\":\"08001 - Barcelona\",\"companyTaxId\":\"B123\","
      + "\"companyLogoDataUrl\":null,\"hasCustomerAddress\":true,"
      + "\"customerAddressLines\":[\"Main St 1\"],\"documentNo\":\"INV-001\",\"currencyCode\":\"EUR\","
      + "\"invoiceDate\":\"2026-10-30\",\"customerName\":\"ACME Corp\",\"paymentMethod\":\"Transfer\","
      + "\"paymentTerms\":\"30 days\",\"notes\":\"Thanks\",\"lines\":["
      + "{\"lineNo\":10,\"productCode\":\"—\",\"productName\":\"Gadget\",\"quantity\":1,"
      + "\"unitPrice\":10,\"discount\":null,\"taxName\":\"IVA 21%\",\"lineTotal\":10},"
      + "{\"lineNo\":20,\"productCode\":\"W-1\",\"productName\":\"Widget\",\"quantity\":2,"
      + "\"unitPrice\":45,\"discount\":10,\"taxName\":\"IVA 21%\",\"lineTotal\":90}],"
      + "\"netAmount\":100,\"taxAmount\":21,\"grandTotal\":121,\"grossAmount\":110,"
      + "\"grossSubtotal\":110,\"discountPerProduct\":-10,\"etgoTotalDiscount\":null,"
      + "\"totalDiscountPct\":null,\"totalDiscountAmt\":null,\"hasAnyDiscount\":true,"
      + "\"hasTotalDiscount\":false,\"verifactuQrDataUrl\":null}";

  @Test
  @DisplayName("the same data as buildInvoiceData for the shared fixture, key for key and value for value")
  void sameDataAsTheBrowserBuilder() throws Exception {
    JSONObject data = SalesInvoicePrintableData.build(new JSONObject(HEADER),
        new JSONArray(LINES), new JSONObject(SESSION));

    assertJsonEquals(new JSONObject(EXPECTED), data, "$");
  }

  @Test
  @DisplayName("a total discount prints its percentage and the sign-flipped amount; no session falls back to the header org")
  void totalDiscountAndNoSession() throws Exception {
    JSONObject header = new JSONObject(HEADER).put("etgoTotalDiscount", 10)
        .put("organization$_identifier", "Header Org");
    JSONObject data = SalesInvoicePrintableData.build(header, new JSONArray(LINES), null);

    assertEquals(10L, ((Number) data.get("etgoTotalDiscount")).longValue());
    assertEquals(-10L, ((Number) data.get("totalDiscountAmt")).longValue(), "10% of a 100 net");
    assertTrue(data.getBoolean("hasTotalDiscount"));
    assertEquals("Header Org", data.getString("companyName"));
  }

  @Test
  @DisplayName("no line discount and no list price: no breakdown rows")
  void noDiscountNoBreakdown() throws Exception {
    JSONArray lines = new JSONArray("[{\"lineNo\":1,\"invoicedQuantity\":2,\"unitPrice\":5,"
        + "\"lineNetAmount\":10}]");
    JSONObject data = SalesInvoicePrintableData.build(new JSONObject(HEADER), lines,
        new JSONObject(SESSION));

    assertTrue(data.isNull("grossAmount"));
    assertTrue(data.isNull("discountPerProduct"));
    assertFalse(data.getBoolean("hasAnyDiscount"));
    assertEquals("—", data.getJSONArray("lines").getJSONObject(0).getString("productName"));
  }

  @Test
  @DisplayName("buildLocationAddressLines: lines in order, blanks dropped, the summary only when nothing else")
  void locationAddressLines() throws Exception {
    JSONObject location = new JSONObject("{\"addressLine1\":\" Calle 1 \",\"address2\":\"\","
        + "\"postalCode\":\"08001\",\"city\":\"Barcelona\",\"region$_identifier\":\"BCN\","
        + "\"country$_identifier\":\"Spain\"}");
    assertEquals(List.of("Calle 1", "08001 Barcelona", "BCN, Spain"),
        NeoPrintableCommons.buildLocationAddressLines(location, "ignored"));
    assertEquals(List.of("Fallback"), NeoPrintableCommons.buildLocationAddressLines(null, "Fallback"));
    assertEquals(List.of(), NeoPrintableCommons.buildLocationAddressLines(null, null));
  }

  @Test
  @DisplayName("JavaScript semantics: || skips '' and 0, ?? only null, Number() of null is 0")
  void javascriptSemantics() {
    assertEquals("b", NeoPrintableCommons.truthy("", 0, "b"));
    assertEquals(0, NeoPrintableCommons.coalesce(null, JSONObject.NULL, 0, 5));
    assertEquals(0.0, NeoPrintableCommons.number(null));
    assertTrue(Double.isNaN(NeoPrintableCommons.number("abc")));
    assertEquals(3L, NeoPrintableCommons.json(3.0));
    assertEquals(2.5, NeoPrintableCommons.json(2.5));
  }

  @Test
  @DisplayName("the Verifactu QR: blank is no QR at all (D2), a URL is a PNG data URL")
  void verifactuQr() throws Exception {
    assertEquals("", NeoPrintableCommons.verifactuQrDataUrl("  "));
    String qr = NeoPrintableCommons.verifactuQrDataUrl(
        "https://prewww2.aeat.es/wlpl/TIKE-CONT/ValidarQR?nif=B123&numserie=F1&fecha=30-10-2026&importe=121.00");
    assertTrue(qr.startsWith("data:image/png;base64,"), qr.substring(0, 30));
    // node qrcode's {width: 400, margin: 1}: the symbol and its quiet zone fill 400 px exactly
    java.awt.image.BufferedImage png = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(
        java.util.Base64.getDecoder().decode(qr.substring("data:image/png;base64,".length()))));
    assertEquals(400, png.getWidth());
    assertEquals(400, png.getHeight());
    assertEquals(0xFFFFFFFF, png.getRGB(0, 0), "the one-module margin is white");
    boolean darkNearTheEdge = false;
    for (int x = 0; x < 400 && !darkNearTheEdge; x++) {
      darkNearTheEdge = png.getRGB(x, 20) == 0xFF000000;
    }
    assertTrue(darkNearTheEdge, "the symbol starts one module in, not centred in a larger margin");
  }

  @Test
  @DisplayName("order: the same data as buildOrderData(spec, id, base, token, null) for the shared fixture")
  void orderSameDataAsTheBrowserBuilder() throws Exception {
    JSONObject header = new JSONObject("{\"documentNo\":\"SO-1\",\"grandTotalAmount\":121,"
        + "\"summedLineAmount\":100,\"businessPartner$_identifier\":\"ACME Corp\","
        + "\"currency$_identifier\":\"EUR\",\"orderDate\":\"2026-10-30\","
        + "\"paymentMethod$_identifier\":\"Transfer\",\"paymentTerms$_identifier\":\"30 days\","
        + "\"description\":null,\"etgoTotalDiscount\":5,\"bpAddress\":\"Main St 1\"}");
    JSONArray lines = new JSONArray("[{\"lineNo\":10,\"product$_identifier\":\"Widget\","
        + "\"product$_value\":\"W-1\",\"orderedQuantity\":2,\"listPrice\":50,\"unitPrice\":45,"
        + "\"discount\":10,\"tax$_identifier\":\"IVA 21%\",\"lineGrossAmount\":108.9,"
        + "\"lineNetAmount\":90}]");
    JSONObject session = new JSONObject("{\"organization\":{\"name\":\"Org SL\"},"
        + "\"currencyStandardPrecision\":2}");
    String expected = "{\"companyName\":\"Org SL\",\"companyAddress1\":null,"
        + "\"companyAddress2\":null,\"companyCityLine\":null,\"companyTaxId\":null,"
        + "\"companyLogoDataUrl\":null,\"hasCustomerAddress\":true,"
        + "\"customerAddressLines\":[\"Main St 1\"],\"documentNo\":\"SO-1\",\"currencyCode\":\"EUR\","
        + "\"invoiceDate\":\"2026-10-30\",\"customerName\":\"ACME Corp\","
        + "\"paymentMethod\":\"Transfer\",\"paymentTerms\":\"30 days\",\"notes\":null,"
        + "\"lines\":[{\"lineNo\":10,\"productCode\":\"W-1\",\"productName\":\"Widget\","
        + "\"quantity\":2,\"unitPrice\":50,\"discount\":10,\"taxName\":\"IVA 21%\","
        + "\"lineTotal\":108.9}],\"netAmount\":100,\"taxAmount\":21,\"grandTotal\":121,"
        + "\"grossAmount\":100,\"discountPerProduct\":-10,\"etgoTotalDiscount\":5,"
        + "\"totalDiscountAmt\":-4.5,\"exchangeRate\":null,\"orgCurrencyCode\":null,"
        + "\"orgGrandTotal\":null,\"rateDecimals\":2}";

    assertJsonEquals(new JSONObject(expected), OrderPrintableData.build(header, lines, session),
        "$");
  }

  @Test
  @DisplayName("quotation: the same data as buildQuotationData(id, base, token, null) for the shared fixture")
  void quotationSameDataAsTheBrowserBuilder() throws Exception {
    JSONObject header = new JSONObject("{\"documentNo\":\"QT-1\",\"grandTotalAmount\":110,"
        + "\"summedLineAmount\":100,\"businessPartner$_identifier\":\"ACME Corp\","
        + "\"currency$_identifier\":\"USD\",\"orderDate\":\"2026-10-30\","
        + "\"validUntil\":\"2026-11-30\",\"etgoTotalDiscount\":0,"
        + "\"partnerAddress$_identifier\":\"Elm St 2\"}");
    JSONArray lines = new JSONArray("[{\"lineNo\":10,\"product$_identifier\":\"Gadget\","
        + "\"orderedQuantity\":4,\"unitPrice\":25,\"tax$_identifier\":\"VAT 10%\","
        + "\"lineNetAmount\":100}]");
    JSONObject session = new JSONObject("{\"organization\":{\"name\":\"Org SL\"}}");
    String expected = "{\"companyName\":\"Org SL\",\"companyAddress1\":null,"
        + "\"companyAddress2\":null,\"companyCityLine\":null,\"companyTaxId\":null,"
        + "\"companyLogoDataUrl\":null,\"hasCustomerAddress\":true,"
        + "\"customerAddressLines\":[\"Elm St 2\"],\"documentNo\":\"QT-1\",\"currencyCode\":\"USD\","
        + "\"invoiceDate\":\"2026-10-30\",\"validUntil\":\"2026-11-30\","
        + "\"customerName\":\"ACME Corp\",\"paymentMethod\":null,\"paymentTerms\":null,"
        + "\"notes\":null,\"lines\":[{\"lineNo\":10,\"productCode\":\"—\","
        + "\"productName\":\"Gadget\",\"quantity\":4,\"unitPrice\":25,\"discount\":null,"
        + "\"taxName\":\"VAT 10%\",\"lineTotal\":100}],\"netAmount\":100,\"taxAmount\":10,"
        + "\"grandTotal\":110,\"grossAmount\":null,\"discountPerProduct\":null,"
        + "\"etgoTotalDiscount\":null,\"totalDiscountAmt\":null,\"exchangeRate\":null,"
        + "\"orgCurrencyCode\":null,\"orgGrandTotal\":null,\"rateDecimals\":4}";

    assertJsonEquals(new JSONObject(expected), QuotationPrintableData.build(header, lines, session),
        "$");
  }

  @Test
  @DisplayName("which statuses store a PDF: completed for invoices and orders, any non-draft for a quotation")
  void storedStatuses() {
    assertTrue(new SalesInvoicePrintableData().storesFor("CO"));
    assertFalse(new SalesInvoicePrintableData().storesFor("DR"));
    assertFalse(new SalesOrderPrintableData().storesFor("RE"));
    assertTrue(new QuotationPrintableData().storesFor("UE"));
    assertFalse(new QuotationPrintableData().storesFor("DR"));
    assertFalse(new QuotationPrintableData().storesFor(null));
  }

  // ── the movement family: printableSources.vitest.js "movement printables" fixtures ─────────────

  private static final String MOVEMENT_HEADER = "{\"documentNo\":\"MV-1\",\"movementDate\":\"2026-10-30\","
      + "\"businessPartner$_identifier\":\"ACME Corp\",\"warehouse$_identifier\":\"Main WH\","
      + "\"description\":\"Fragile\",\"partnerAddress$_identifier\":\"Elm St 2\","
      + "\"salesOrder$_identifier\":\"SO-7 - 30-10-2026\",\"documentStatus\":\"CO\","
      + "\"documentStatus$_identifier\":\"Completed\",\"sourceReceiptDocNo\":\"GR-3\","
      + "\"sourceShipmentDocNo\":\"GS-4\",\"issuerOrg\":{\"name\":\"Org SL\",\"address1\":\"Calle 1\","
      + "\"cityLine\":\"08001 - Barcelona\",\"taxId\":\"B123\"}}";
  private static final String MOVEMENT_LINES = "[{\"lineNo\":20,\"product$_identifier\":\"Widget\","
      + "\"product$_value\":\"W-1\",\"movementQuantity\":2,\"orderLineQty\":3,\"orderQuantity\":5},"
      + "{\"lineNo\":10,\"product$_identifier\":\"Gadget\",\"movementQuantity\":1.5}]";
  private static final String MOVEMENT_COMMON = "\"companyName\":\"Org SL\",\"companyAddress1\":\"Calle 1\","
      + "\"companyAddress2\":null,\"companyCityLine\":\"08001 - Barcelona\",\"companyTaxId\":\"B123\","
      + "\"companyLogoDataUrl\":null,\"documentNo\":\"MV-1\",\"movementDate\":\"2026-10-30\","
      + "\"customerName\":\"ACME Corp\",\"customerAddressLines\":[\"Elm St 2\"],"
      + "\"warehouse\":\"Main WH\",\"notes\":\"Fragile\",";

  private static JSONObject movement(MovementPrintableData builder) throws Exception {
    return builder.build(new JSONObject(MOVEMENT_HEADER), new JSONArray(MOVEMENT_LINES), null, null);
  }

  @Test
  @DisplayName("goods shipment: the same data as buildShipmentData for the shared fixture")
  void shipmentSameDataAsTheBrowserBuilder() throws Exception {
    String expected = "{" + MOVEMENT_COMMON + "\"documentStatusLabel\":\"Completed\","
        + "\"documentStatusClass\":\"status-co\",\"salesOrderRef\":\"SO-7\",\"lines\":["
        + "{\"lineNo\":1,\"productCode\":\"1\",\"productName\":\"Gadget\",\"orderedQty\":0,"
        + "\"deliveredQty\":1.5},{\"lineNo\":2,\"productCode\":\"W-1\",\"productName\":\"Widget\","
        + "\"orderedQty\":3,\"deliveredQty\":2}]}";
    assertJsonEquals(new JSONObject(expected), movement(new ShipmentPrintableData()), "$");
  }

  @Test
  @DisplayName("return to vendor: the same data as buildReturnToVendorData for the shared fixture")
  void returnToVendorSameDataAsTheBrowserBuilder() throws Exception {
    String expected = "{" + MOVEMENT_COMMON + "\"sourceReceiptRef\":\"GR-3\",\"lines\":["
        + "{\"lineNo\":1,\"productCode\":\"1\",\"productName\":\"Gadget\",\"returnedQty\":1.5,"
        + "\"originalQty\":0},{\"lineNo\":2,\"productCode\":\"W-1\",\"productName\":\"Widget\","
        + "\"returnedQty\":2,\"originalQty\":5}]}";
    assertJsonEquals(new JSONObject(expected), movement(new ReturnToVendorPrintableData()), "$");
  }

  @Test
  @DisplayName("return receipt: the same data as buildReceiptData for the shared fixture")
  void returnReceiptSameDataAsTheBrowserBuilder() throws Exception {
    String expected = "{" + MOVEMENT_COMMON + "\"sourceShipmentRef\":\"GS-4\",\"lines\":["
        + "{\"lineNo\":1,\"productCode\":\"1\",\"productName\":\"Gadget\",\"returnedQty\":1.5},"
        + "{\"lineNo\":2,\"productCode\":\"W-1\",\"productName\":\"Widget\",\"returnedQty\":2}]}";
    assertJsonEquals(new JSONObject(expected), movement(new ReturnReceiptPrintableData()), "$");
  }

  @Test
  @DisplayName("build(recordId): the builders read through NEO and fail with a dedicated exception")
  void buildersReadAndFailWithADedicatedException() throws Exception {
    try (org.mockito.MockedStatic<NeoPrintableReads> reads =
            org.mockito.Mockito.mockStatic(NeoPrintableReads.class);
        org.mockito.MockedStatic<NeoSessionService> session =
            org.mockito.Mockito.mockStatic(NeoSessionService.class)) {
      session.when(NeoSessionService::resolveSession)
          .thenReturn(NeoResponse.ok(new JSONObject(SESSION)));
      reads.when(() -> NeoPrintableReads.children(org.mockito.ArgumentMatchers.anyString(),
          org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString()))
          .thenReturn(new JSONArray(LINES));
      reads.when(() -> NeoPrintableReads.children(org.mockito.ArgumentMatchers.anyString(),
          org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
          org.mockito.ArgumentMatchers.anyMap())).thenReturn(new JSONArray(MOVEMENT_LINES));

      NeoPrintableDataBuilder[] builders = {new SalesInvoicePrintableData(),
          new SalesOrderPrintableData(), new QuotationPrintableData(), new ShipmentPrintableData()};
      for (NeoPrintableDataBuilder builder : builders) {
        // no record: a dedicated exception, never a generic one
        org.junit.jupiter.api.Assertions.assertThrows(
            NeoPrintableDataBuilder.PrintableDataException.class, () -> builder.build("R1"),
            builder.document());
      }
      reads.when(() -> NeoPrintableReads.readRecord("sales-invoice", "header", "R1"))
          .thenReturn(new JSONObject(HEADER));
      assertEquals("INV-001", new SalesInvoicePrintableData().build("R1").getString("documentNo"));
      reads.when(() -> NeoPrintableReads.readRecord("goods-shipment", "goodsShipment", "R1"))
          .thenReturn(new JSONObject(MOVEMENT_HEADER));
      assertEquals("MV-1", new ShipmentPrintableData().build("R1").getString("documentNo"));
    }
  }

  /** Recursive equality with numbers compared by value, JSON null as null. */
  private static void assertJsonEquals(Object expected, Object actual, String path)
      throws Exception {
    if (expected instanceof JSONObject) {
      assertTrue(actual instanceof JSONObject, path);
      JSONObject e = (JSONObject) expected;
      JSONObject a = (JSONObject) actual;
      assertEquals(keys(e), keys(a), path + " keys");
      for (String key : keys(e)) {
        assertJsonEquals(e.get(key), a.get(key), path + "." + key);
      }
    } else if (expected instanceof JSONArray) {
      assertTrue(actual instanceof JSONArray, path);
      JSONArray e = (JSONArray) expected;
      JSONArray a = (JSONArray) actual;
      assertEquals(e.length(), a.length(), path + " length");
      for (int i = 0; i < e.length(); i++) {
        assertJsonEquals(e.get(i), a.get(i), path + "[" + i + "]");
      }
    } else if (expected instanceof Number) {
      assertTrue(actual instanceof Number, path + " is a number: " + actual);
      assertEquals(((Number) expected).doubleValue(), ((Number) actual).doubleValue(), 1e-9, path);
    } else if (JSONObject.NULL.equals(expected)) {
      assertTrue(JSONObject.NULL.equals(actual), path + " is null: " + actual);
    } else {
      assertEquals(expected, actual, path);
    }
  }

  private static Set<String> keys(JSONObject object) {
    Set<String> keys = new TreeSet<>();
    Iterator<?> it = object.keys();
    while (it.hasNext()) {
      keys.add((String) it.next());
    }
    return keys;
  }
}
