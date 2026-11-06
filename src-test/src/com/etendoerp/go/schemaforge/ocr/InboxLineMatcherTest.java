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

package com.etendoerp.go.schemaforge.ocr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.etendoerp.go.schemaforge.ocr.OcrLookups.Candidate;
import com.etendoerp.go.schemaforge.ocr.OcrLookups.Tax;
import com.etendoerp.go.schemaforge.ocr.OcrLookups.TaxAnswer;

/**
 * The product and the tax of every line of an inbox document. The product follows the application's
 * review ({@code OcrProductMatch}): linked only on an exact name or a single top similarity of 90 %
 * or more, never on a lookalike. The tax waits for the vendor's address and is asked once per rate.
 *
 * @covers com.etendoerp.go.schemaforge.ocr.InboxLineMatcher
 */
class InboxLineMatcherTest {

  private static final String STATE = "state";
  private static final String PRODUCT = "product";
  private static final String TAX = "tax";
  private static final String DESCRIPTION = "description";
  private static final String LOCATION = "LOC1";
  private static final String DATE = "2026-10-30";
  private static final String HARNESS = "Arnés escalada QA5289 talla M";

  private InboxFakeLookups lookups;
  private InboxLineMatcher matcher;

  @BeforeEach
  void setUp() {
    lookups = new InboxFakeLookups();
    matcher = new InboxLineMatcher(lookups);
  }

  private static JSONArray items(String... descriptions) throws JSONException {
    JSONArray items = new JSONArray();
    for (String description : descriptions) {
      items.put(new JSONObject().put(DESCRIPTION, description).put("quantity", 2)
          .put("unit_price", 50).put("tax_rate", 21).put("tax_label", "IVA 21%"));
    }
    return items;
  }

  private JSONObject product(String description, Candidate... candidates) throws Exception {
    lookups.products = List.<List<Candidate>>of(List.of(candidates));
    return matcher.review(items(description), LOCATION, DATE).getJSONObject(0)
        .getJSONObject(PRODUCT);
  }

  private static Tax tax(String id, String name, String rate) {
    return new Tax(id, name, new BigDecimal(rate));
  }

  // ---------------------------------------------------------------- the line itself

  @Test
  @DisplayName("a line carries what was read: index, description, quantity, price, printed rate and label")
  void theLineKeepsWhatWasRead() throws Exception {
    JSONArray items = items("Widget");
    items.put(new JSONObject().put(DESCRIPTION, "Bare"));
    lookups.products = List.of(List.of(), List.of());

    JSONArray lines = matcher.review(items, LOCATION, DATE);

    JSONObject first = lines.getJSONObject(0);
    assertEquals(0, first.getInt("index"));
    assertEquals("Widget", first.getString(DESCRIPTION));
    assertEquals(2.0, first.getDouble("quantity"), 0.0);
    assertEquals(50.0, first.getDouble("unitPrice"), 0.0);
    assertEquals(21.0, first.getDouble("taxRate"), 0.0);
    assertEquals("IVA 21%", first.getString("taxLabel"));
    JSONObject second = lines.getJSONObject(1);
    assertEquals(1, second.getInt("index"));
    assertTrue(second.isNull("quantity"), "absent numbers are null, not zero");
    assertTrue(second.isNull("unitPrice"));
    assertTrue(second.isNull("taxRate"));
  }

  @Test
  @DisplayName("no items, or none at all, give no lines and ask nothing")
  void noItemsGiveNoLines() throws Exception {
    assertEquals(0, matcher.review(null, LOCATION, DATE).length());
    assertEquals(0, matcher.review(new JSONArray(), LOCATION, DATE).length());
    assertTrue(lookups.productAsks.isEmpty());
  }

  @Test
  @DisplayName("products are looked up once, for all the descriptions in line order")
  void productsAreAskedInOneCall() throws Exception {
    lookups.products = List.of(List.of(), List.of());

    matcher.review(items("First", "Second"), LOCATION, DATE);

    assertEquals(1, lookups.productAsks.size());
    assertEquals(List.of("First", "Second"), lookups.productAsks.get(0));
  }

  @Test
  @DisplayName("a lookup that answers fewer lists than lines leaves the rest as new products")
  void missingCandidateListsAreNone() throws Exception {
    lookups.products = List.of(List.of());

    JSONArray lines = matcher.review(items("First", "Second"), LOCATION, DATE);

    assertEquals("new", lines.getJSONObject(1).getJSONObject(PRODUCT).getString(STATE));
  }

  // ---------------------------------------------------------------- products

  @Test
  @DisplayName("ETP-5654: a lookalike that shares only a code is NOT linked (Mosquetón vs Arnés ... QA5289)")
  void aLookalikeIsNeverLinked() throws Exception {
    JSONObject product = product("Mosquetón HMS QA5289", new Candidate("P9", HARNESS, "72"));

    assertEquals("ambiguous", product.getString(STATE));
    assertFalse(product.has("id") && !product.isNull("id"), "no product is chosen");
    assertEquals("P9", product.getJSONArray("candidates").getJSONObject(0).getString("id"));
    assertEquals("72", product.getJSONArray("candidates").getJSONObject(0).getString("similarity"));
  }

  @Test
  @DisplayName("ETP-5654: 95 % and 92 %: the single top, above 90 %, is linked by similarity")
  void aSingleTopAboveTheThresholdIsLinked() throws Exception {
    JSONObject product = product("Servicio web", new Candidate("P1", "Servicios web", "95"),
        new Candidate("P2", "Servicio webs pro", "92"));

    assertEquals("linked", product.getString(STATE));
    assertEquals("P1", product.getString("id"));
    assertEquals("Servicios web", product.getString("name"));
    assertEquals("similarity", product.getString("matchedBy"));
    assertEquals(2, product.getJSONArray("candidates").length());
  }

  @Test
  @DisplayName("two candidates tied at 93 % are only suggested")
  void aTieIsOnlySuggested() throws Exception {
    JSONObject product = product("Servicio web", new Candidate("P1", "Servicios web", "93"),
        new Candidate("P2", "Servicio webs", "93"));

    assertEquals("ambiguous", product.getString(STATE));
    assertTrue(product.isNull("id"));
  }

  @Test
  @DisplayName("the threshold is 90 %: 90 links, 89.9 does not")
  void theThresholdIsNinety() throws Exception {
    assertEquals("linked", product("Servicio web", new Candidate("P1", "Servicios web", "90"))
        .getString(STATE));
    assertEquals("ambiguous", product("Servicio web", new Candidate("P1", "Servicios web", "89.9"))
        .getString(STATE));
  }

  @Test
  @DisplayName("an exact name, accents and case aside, is linked as exact whatever its similarity")
  void anExactNameIsLinkedAsExact() throws Exception {
    JSONObject product = product("ARNES escalada QA5289 talla M",
        new Candidate("P9", HARNESS, "60"));

    assertEquals("linked", product.getString(STATE));
    assertEquals("exact", product.getString("matchedBy"));
    assertEquals("P9", product.getString("id"));
  }

  @Test
  @DisplayName("two exact names are not decided for the user: ambiguous")
  void twoExactNamesAreAmbiguous() throws Exception {
    JSONObject product = product(HARNESS, new Candidate("P8", "Arnes escalada QA5289 talla M", "99"),
        new Candidate("P9", HARNESS, "99"));

    assertEquals("ambiguous", product.getString(STATE));
  }

  @Test
  @DisplayName("no candidates, or candidates without an id, is a new product with none chosen")
  void noCandidatesIsNew() throws Exception {
    JSONObject none = product("Brand new thing");
    assertEquals("new", none.getString(STATE));
    assertTrue(none.isNull("id"));
    assertEquals(0, none.getJSONArray("candidates").length());

    assertEquals("new", product("Brand new thing", new Candidate(null, "ghost", "99"))
        .getString(STATE));
  }

  // ---------------------------------------------------------------- taxes

  @Test
  @DisplayName("without an address (new vendor, none on file) every line is pending_vendor and no tax is asked")
  void withoutAnAddressTheTaxIsPending() throws Exception {
    lookups.products = List.of(List.of());

    JSONObject line = matcher.review(items("Widget"), null, DATE).getJSONObject(0);

    assertEquals("pending_vendor", line.getJSONObject(TAX).getString(STATE));
    assertTrue(lookups.taxAsks.isEmpty());
  }

  @Test
  @DisplayName("a tax of the printed rate for the address is resolved, with its candidates")
  void aTaxAtTheRateIsResolved() throws Exception {
    lookups.products = List.of(List.of());
    lookups.taxByRate.put("21", new TaxAnswer(true, tax("T21", "IVA 21%", "21"),
        List.of(tax("T21", "IVA 21%", "21"), tax("T21R", "IVA 21% rec", "21"))));

    JSONObject taxJson = matcher.review(items("Widget"), LOCATION, DATE).getJSONObject(0)
        .getJSONObject(TAX);

    assertEquals("resolved", taxJson.getString(STATE));
    assertEquals("T21", taxJson.getString("id"));
    assertEquals("IVA 21%", taxJson.getString("name"));
    assertEquals(21.0, taxJson.getDouble("rate"), 0.0);
    assertEquals(2, taxJson.getJSONArray("candidates").length());
    assertEquals(List.of(LOCATION + "|21"), lookups.taxAsks);
    assertEquals(List.of(DATE), lookups.taxDates);
  }

  @Test
  @DisplayName("a known address with no tax at that rate is no_tax_at_rate, keeping the options")
  void noTaxAtTheRate() throws Exception {
    lookups.products = List.of(List.of());
    lookups.taxByRate.put("21", new TaxAnswer(true, null, List.of(tax("T10", "IVA 10%", "10"))));

    JSONObject taxJson = matcher.review(items("Widget"), LOCATION, DATE).getJSONObject(0)
        .getJSONObject(TAX);

    assertEquals("no_tax_at_rate", taxJson.getString(STATE));
    assertEquals("T10", taxJson.getJSONArray("candidates").getJSONObject(0).getString("id"));
    assertFalse(taxJson.has("id"));
  }

  @Test
  @DisplayName("a tax lookup that could not be made is unknown")
  void anUnansweredTaxIsUnknown() throws Exception {
    lookups.products = List.of(List.of());
    lookups.taxByRate.put("21", new TaxAnswer(false, null, List.of()));

    assertEquals("unknown", matcher.review(items("Widget"), LOCATION, DATE).getJSONObject(0)
        .getJSONObject(TAX).getString(STATE));
  }

  @Test
  @DisplayName("the tax is asked once per rate: 21 and 21.0 are the same rate, no rate is its own")
  void taxesAreCachedPerRate() throws Exception {
    lookups.products = List.of(List.of(), List.of(), List.of(), List.of(), List.of());
    JSONArray items = new JSONArray();
    items.put(new JSONObject().put(DESCRIPTION, "a").put("tax_rate", 21));
    items.put(new JSONObject().put(DESCRIPTION, "b").put("tax_rate", 21.0));
    items.put(new JSONObject().put(DESCRIPTION, "c").put("tax_rate", 10));
    items.put(new JSONObject().put(DESCRIPTION, "d"));
    items.put(new JSONObject().put(DESCRIPTION, "e").put("tax_rate", JSONObject.NULL));

    matcher.review(items, LOCATION, DATE);

    assertEquals(List.of(LOCATION + "|21", LOCATION + "|10", LOCATION + "|"), lookups.taxAsks);
  }

  @Test
  @DisplayName("a line without a printed rate still asks, so the category's default tax can answer")
  void aMissingRateStillAsks() throws Exception {
    lookups.products = List.of(List.of());
    lookups.taxByRate.put("", new TaxAnswer(true, tax("TDEF", "IVA default", "21"), List.of()));
    JSONArray items = new JSONArray().put(new JSONObject().put(DESCRIPTION, "a"));

    JSONObject taxJson = matcher.review(items, LOCATION, null).getJSONObject(0).getJSONObject(TAX);

    assertEquals("resolved", taxJson.getString(STATE));
    assertEquals("TDEF", taxJson.getString("id"));
    assertEquals(1, lookups.taxDates.size());
    assertNull(lookups.taxDates.get(0), "no date read: the lookup falls back to today");
  }
}
