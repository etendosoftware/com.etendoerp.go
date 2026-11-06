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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The mandatory-data rules of an inbox document: what makes it complete, what is reported missing
 * (as codes), and which error code wins when several apply. Pure: no database, no mocks.
 *
 * @covers com.etendoerp.go.schemaforge.InvoiceInboxValidator
 */
class InvoiceInboxValidatorTest {

  private static final String LINKED = "linked";
  private static final String NEW = "new";
  private static final String AMBIGUOUS = "ambiguous";
  private static final String STATE = "state";
  private static final String HEADER = "header";
  private static final String VENDOR = "vendor";
  private static final String PRODUCT = "product";
  private static final String TAX = "tax";
  private static final String QUANTITY = "quantity";
  private static final String UNIT_PRICE = "unitPrice";
  private static final String TAX_RATE = "taxRate";
  private static final String DOCUMENT_NO = "documentNo";

  // ---------------------------------------------------------------- fixtures

  /** A complete document: linked vendor, date, amount, one line with its product and tax resolved. */
  private static JSONObject complete() throws JSONException {
    return new JSONObject()
        .put(HEADER, new JSONObject()
            .put("receiver", new JSONObject().put("check", "match"))
            .put(VENDOR, new JSONObject().put(STATE, LINKED).put("id", "V1"))
            .put("invoiceDate", "2026-10-30").put("totalAmount", 121.0).put(DOCUMENT_NO, "F-1"))
        .put("lines", new JSONArray().put(line()));
  }

  private static JSONObject line() throws JSONException {
    return new JSONObject().put("description", "Widget").put(QUANTITY, 2).put(UNIT_PRICE, 50)
        .put(TAX_RATE, 21).put(PRODUCT, new JSONObject().put(STATE, LINKED).put("id", "P1"))
        .put(TAX, new JSONObject().put(STATE, "resolved").put("id", "T21"));
  }

  private static JSONObject header(JSONObject document) throws JSONException {
    return document.getJSONObject(HEADER);
  }

  private static JSONObject firstLine(JSONObject document) throws JSONException {
    return document.getJSONArray("lines").getJSONObject(0);
  }

  private static InvoiceInboxValidator.Result validate(JSONObject document) {
    return InvoiceInboxValidator.validate(document);
  }

  private static void assertMissing(JSONObject document, String... expected) {
    InvoiceInboxValidator.Result result = validate(document);
    assertEquals(List.of(expected), result.getMissing());
    assertFalse(result.isValid());
    assertEquals(InvoiceInboxValidator.MISSING_FIELDS, result.getErrorCode());
  }

  // ---------------------------------------------------------------- the complete document

  @Test
  @DisplayName("a complete document is valid: nothing missing, no error code")
  void aCompleteDocumentIsValid() throws Exception {
    InvoiceInboxValidator.Result result = validate(complete());

    assertTrue(result.isValid());
    assertTrue(result.getMissing().isEmpty());
    assertNull(result.getErrorCode());
  }

  @Test
  @DisplayName("the verdict serializes as the validation block: valid and the missing codes")
  void theVerdictIsTheValidationBlock() throws Exception {
    JSONObject document = complete();
    header(document).put("invoiceDate", JSONObject.NULL);

    JSONObject block = validate(document).toJson();

    assertFalse(block.getBoolean("valid"));
    assertEquals("invoiceDate", block.getJSONArray("missing").getString(0));
    assertTrue(validate(complete()).toJson().getBoolean("valid"));
  }

  // ---------------------------------------------------------------- each mandatory, separately

  @Test
  @DisplayName("an ambiguous or unmatched vendor, or none at all, is a missing vendor")
  void vendorMustBeLinkedOrComplete() throws Exception {
    for (String state : new String[] { AMBIGUOUS, "none", "garbage" }) {
      JSONObject document = complete();
      header(document).getJSONObject(VENDOR).put(STATE, state);
      assertMissing(document, VENDOR);
    }
    JSONObject noVendor = complete();
    header(noVendor).remove(VENDOR);
    assertMissing(noVendor, VENDOR);
  }

  @Test
  @DisplayName("a new vendor is complete only with a name and a tax id: what its creation needs")
  void aNewVendorNeedsNameAndTaxId() throws Exception {
    JSONObject full = complete();
    header(full).put(VENDOR, new JSONObject().put(STATE, NEW).put("extracted",
        new JSONObject().put("name", "Acme").put("taxId", "B81639719")));
    assertTrue(validate(full).isValid());

    for (JSONObject extracted : new JSONObject[] {
        new JSONObject().put("name", "Acme").put("taxId", "  "),
        new JSONObject().put("name", "").put("taxId", "B81639719"),
        new JSONObject().put("taxId", "B81639719"),
        new JSONObject().put("name", "Acme").put("taxId", JSONObject.NULL),
        new JSONObject() }) {
      JSONObject document = complete();
      header(document).put(VENDOR, new JSONObject().put(STATE, NEW).put("extracted", extracted));
      assertMissing(document, VENDOR);
    }
    JSONObject noExtracted = complete();
    header(noExtracted).put(VENDOR, new JSONObject().put(STATE, NEW));
    assertMissing(noExtracted, VENDOR);
  }

  @Test
  @DisplayName("a null, blank or absent invoice date is missing")
  void theInvoiceDateIsMandatory() throws Exception {
    JSONObject asNull = complete();
    header(asNull).put("invoiceDate", JSONObject.NULL);
    assertMissing(asNull, "invoiceDate");

    JSONObject blank = complete();
    header(blank).put("invoiceDate", "  ");
    assertMissing(blank, "invoiceDate");

    JSONObject absent = complete();
    header(absent).remove("invoiceDate");
    assertMissing(absent, "invoiceDate");
  }

  @Test
  @DisplayName("an amount of zero, a negative one, null or absent is a missing amount")
  void theAmountMustBeAboveZero() throws Exception {
    for (Object amount : new Object[] { 0, 0.0, -5.0, JSONObject.NULL }) {
      JSONObject document = complete();
      header(document).put("totalAmount", amount);
      assertMissing(document, "amount");
    }
    JSONObject absent = complete();
    header(absent).remove("totalAmount");
    assertMissing(absent, "amount");

    JSONObject cents = complete();
    header(cents).put("totalAmount", 0.01);
    assertTrue(validate(cents).isValid());
  }

  @Test
  @DisplayName("a document without lines, or with an empty list, is missing its lines")
  void atLeastOneLineIsRequired() throws Exception {
    JSONObject empty = complete();
    empty.put("lines", new JSONArray());
    assertMissing(empty, "lines");

    JSONObject absent = complete();
    absent.remove("lines");
    assertMissing(absent, "lines");
  }

  @Test
  @DisplayName("a line whose tax is not resolved is missing its tax, and only that")
  void aLineNeedsItsTax() throws Exception {
    for (String state : new String[] { "no_tax_at_rate", "unknown", "" }) {
      JSONObject document = complete();
      firstLine(document).put(TAX, new JSONObject().put(STATE, state));
      assertMissing(document, "line[0].tax");
    }
    JSONObject noTax = complete();
    firstLine(noTax).remove(TAX);
    assertMissing(noTax, "line[0].tax");
  }

  @Test
  @DisplayName("D2: a tax pending the vendor's address is valid when the line prints a rate, missing when not")
  void aPendingTaxNeedsAPrintedRate() throws Exception {
    JSONObject printed = complete();
    firstLine(printed).put(TAX, new JSONObject().put(STATE, "pending_vendor"));
    assertTrue(validate(printed).isValid(), "a printed 21 % is enough");

    JSONObject zeroRate = complete();
    firstLine(zeroRate).put(TAX, new JSONObject().put(STATE, "pending_vendor")).put(TAX_RATE, 0);
    assertTrue(validate(zeroRate).isValid(), "a printed 0 % is a rate");

    JSONObject nullRate = complete();
    firstLine(nullRate).put(TAX, new JSONObject().put(STATE, "pending_vendor"))
        .put(TAX_RATE, JSONObject.NULL);
    assertMissing(nullRate, "line[0].tax");

    JSONObject noRate = complete();
    firstLine(noRate).put(TAX, new JSONObject().put(STATE, "pending_vendor")).remove(TAX_RATE);
    assertMissing(noRate, "line[0].tax");
  }

  @Test
  @DisplayName("an ambiguous product, or none, is a missing product")
  void aLineNeedsItsProduct() throws Exception {
    JSONObject ambiguous = complete();
    firstLine(ambiguous).put(PRODUCT, new JSONObject().put(STATE, AMBIGUOUS));
    assertMissing(ambiguous, "line[0].product");

    JSONObject none = complete();
    firstLine(none).remove(PRODUCT);
    assertMissing(none, "line[0].product");
  }

  @Test
  @DisplayName("D8: a new product is complete with a description, a quantity above zero and a price of zero or more")
  void aNewProductNeedsDescriptionQuantityAndPrice() throws Exception {
    JSONObject full = complete();
    firstLine(full).put(PRODUCT, new JSONObject().put(STATE, NEW));
    assertTrue(validate(full).isValid());

    JSONObject free = complete();
    firstLine(free).put(PRODUCT, new JSONObject().put(STATE, NEW)).put(UNIT_PRICE, 0);
    assertTrue(validate(free).isValid(), "a price of zero is allowed");

    String[][] broken = { { "description", "  " }, { QUANTITY, "0" }, { QUANTITY, "-1" },
        { UNIT_PRICE, "-0.01" } };
    for (String[] change : broken) {
      JSONObject document = complete();
      firstLine(document).put(PRODUCT, new JSONObject().put(STATE, NEW)).put(change[0],
          "description".equals(change[0]) ? change[1] : Double.valueOf(change[1]));
      assertMissing(document, "line[0].product");
    }
    for (String key : new String[] { "description", QUANTITY, UNIT_PRICE }) {
      JSONObject asNull = complete();
      firstLine(asNull).put(PRODUCT, new JSONObject().put(STATE, NEW)).put(key, JSONObject.NULL);
      assertMissing(asNull, "line[0].product");
      JSONObject absent = complete();
      firstLine(absent).put(PRODUCT, new JSONObject().put(STATE, NEW)).remove(key);
      assertMissing(absent, "line[0].product");
    }
  }

  @Test
  @DisplayName("a linked product needs no quantity or price: the match is what counts")
  void aLinkedProductIsComplete() throws Exception {
    JSONObject document = complete();
    firstLine(document).put(QUANTITY, JSONObject.NULL).put(UNIT_PRICE, JSONObject.NULL);

    assertTrue(validate(document).isValid());
  }

  // ---------------------------------------------------------------- combined

  @Test
  @DisplayName("every missing item is reported at once, in document order, line by line")
  void missingItemsAreReportedTogether() throws Exception {
    JSONObject document = complete();
    header(document).getJSONObject(VENDOR).put(STATE, AMBIGUOUS);
    header(document).put("invoiceDate", JSONObject.NULL).put("totalAmount", 0);
    JSONObject second = line();
    second.put(TAX, new JSONObject().put(STATE, "unknown"))
        .put(PRODUCT, new JSONObject().put(STATE, AMBIGUOUS));
    document.getJSONArray("lines").put(second);

    assertMissing(document, VENDOR, "invoiceDate", "amount", "line[1].tax", "line[1].product");
  }

  @Test
  @DisplayName("a line that is not an object counts as missing both its tax and its product")
  void aBrokenLineIsMissingBoth() throws Exception {
    JSONObject document = complete();
    document.put("lines", new JSONArray().put("not an object"));

    assertMissing(document, "line[0].tax", "line[0].product");
  }

  // ---------------------------------------------------------------- error code priority

  @Test
  @DisplayName("a document number of 20 characters fits; 21 does not, and trailing spaces do not count")
  void theReferenceLimitIsTwentyCharacters() throws Exception {
    JSONObject fits = complete();
    header(fits).put(DOCUMENT_NO, "A".repeat(20) + "   ");
    assertTrue(validate(fits).isValid());

    JSONObject tooLong = complete();
    header(tooLong).put(DOCUMENT_NO, "A".repeat(21));
    InvoiceInboxValidator.Result result = validate(tooLong);
    assertFalse(result.isValid());
    assertEquals(InvoiceInboxValidator.REFERENCE_TOO_LONG, result.getErrorCode());
    assertTrue(result.getMissing().isEmpty(), "nothing is missing: the number just does not fit");
  }

  @Test
  @DisplayName("REFERENCE_TOO_LONG wins over MISSING_FIELDS and keeps the missing list")
  void tooLongBeatsMissing() throws Exception {
    JSONObject document = complete();
    header(document).put(DOCUMENT_NO, "A".repeat(21)).put("invoiceDate", JSONObject.NULL);

    InvoiceInboxValidator.Result result = validate(document);

    assertEquals(InvoiceInboxValidator.REFERENCE_TOO_LONG, result.getErrorCode());
    assertEquals(List.of("invoiceDate"), result.getMissing());
  }

  @Test
  @DisplayName("FOREIGN_RECEIVER wins over everything and reports only the receiver")
  void foreignReceiverBeatsEverything() throws Exception {
    JSONObject document = complete();
    header(document).put("receiver", new JSONObject().put("check", "mismatch"))
        .put(DOCUMENT_NO, "A".repeat(30)).put("invoiceDate", JSONObject.NULL);
    document.put("lines", new JSONArray());

    InvoiceInboxValidator.Result result = validate(document);

    assertEquals(InvoiceInboxValidator.FOREIGN_RECEIVER, result.getErrorCode());
    assertEquals(List.of("receiver"), result.getMissing());
    assertFalse(result.isValid());
  }

  @Test
  @DisplayName("the other receiver checks (absent, same as issuer, no organization id) are not a foreign receiver")
  void onlyAMismatchIsForeign() throws Exception {
    for (String check : new String[] { "match", "absent", "same-as-issuer", "no-org-tax-id" }) {
      JSONObject document = complete();
      header(document).put("receiver", new JSONObject().put("check", check));
      assertTrue(validate(document).isValid(), check);
    }
    JSONObject noReceiver = complete();
    header(noReceiver).remove("receiver");
    assertTrue(validate(noReceiver).isValid());
  }

  @Test
  @DisplayName("a null document, or one without a header, is MISSING_FIELDS with the header missing")
  void aDocumentWithoutHeader() throws Exception {
    for (JSONObject document : new JSONObject[] { null, new JSONObject() }) {
      InvoiceInboxValidator.Result result = validate(document);
      assertEquals(List.of(HEADER), result.getMissing());
      assertEquals(InvoiceInboxValidator.MISSING_FIELDS, result.getErrorCode());
    }
  }
}
