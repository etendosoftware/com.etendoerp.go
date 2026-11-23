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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mockStatic;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import com.etendoerp.go.common.GoAccountResolver;
import com.etendoerp.go.featureflags.GoFeatureFlags;
import com.etendoerp.go.schemaforge.InvoiceInboxConfirmSeeds.CountryRow;

/**
 * What a NEW vendor is created with when an inbox document is confirmed: the vendor body (flag off
 * and on), whether there is a usable address, the country (blank is Spain, aliases, ISO, translated
 * names, never a guess) and the address body. The mapping is pinned by the backend-only fixture
 * {@code invoice-inbox-vendor-seed.json}.
 *
 * @covers com.etendoerp.go.schemaforge.InvoiceInboxConfirmSeeds
 */
class InvoiceInboxConfirmSeedsTest {

  private static final String FIXTURE = "/com/etendoerp/go/schemaforge/invoice-inbox-vendor-seed.json";

  private static JSONObject fixture() throws Exception {
    try (InputStream in = InvoiceInboxConfirmSeedsTest.class.getResourceAsStream(FIXTURE)) {
      assertNotNull(in, "the fixture " + FIXTURE + " is on the test classpath");
      return new JSONObject(new String(in.readAllBytes(), StandardCharsets.UTF_8));
    }
  }

  /** Same keys and values, whatever the key order. */
  private static void assertSameJson(JSONObject expected, JSONObject actual, String message)
      throws Exception {
    assertEquals(expected.length(), actual.length(), message + " keys: " + actual);
    java.util.Iterator<?> keys = expected.keys();
    while (keys.hasNext()) {
      String key = (String) keys.next();
      assertTrue(actual.has(key), message + " has " + key);
      assertEquals(expected.get(key), actual.get(key), message + " " + key);
    }
  }

  private static List<CountryRow> countries(JSONArray array) throws Exception {
    List<CountryRow> rows = new ArrayList<>();
    for (int i = 0; i < array.length(); i++) {
      JSONObject c = array.getJSONObject(i);
      rows.add(new CountryRow(c.getString("id"), c.getString("iso"), c.getString("name")));
    }
    return rows;
  }

  private static List<CountryRow> spainAndFrance() {
    return List.of(new CountryRow("C_ES", "ES", "Spain"), new CountryRow("C_ES", "ES", "Espa\u00f1a"),
        new CountryRow("C_FR", "FR", "France"), new CountryRow("C_FR", "FR", "Francia"));
  }

  @Test
  @DisplayName("every case of the shared fixture gives the same vendor, address and country "
      + "(fixture predates ETP-5806's structured-address-for-sepa: pinned with the flag off)")
  void everyCaseOfTheFixture() throws Exception {
    JSONObject fixture = fixture();
    List<CountryRow> countries = countries(fixture.getJSONArray("countries"));
    JSONArray cases = fixture.getJSONArray("cases");
    assertTrue(cases.length() >= 5);

    try (MockedStatic<GoAccountResolver> accounts = mockStatic(GoAccountResolver.class);
        MockedStatic<GoFeatureFlags> flags = stubStructuredAddressFlag(false)) {
      accounts.when(GoAccountResolver::currentAccountEmail).thenReturn("acme@example.com");

      for (int i = 0; i < cases.length(); i++) {
        JSONObject c = cases.getJSONObject(i);
        String name = c.getString("name");
        JSONObject extracted = c.getJSONObject("extracted");
        String categoryId = c.isNull("categoryId") ? null : c.getString("categoryId");

        assertSameJson(c.getJSONObject("vendor"),
            InvoiceInboxConfirmSeeds.vendorBody(extracted, c.getBoolean("categoryType"),
                categoryId), name + " vendor");
        assertEquals(c.getBoolean("usableAddress"),
            InvoiceInboxConfirmSeeds.hasUsableAddress(extracted), name + " usable");
        if (!c.has("countryId")) {
          continue;
        }
        String countryId = InvoiceInboxConfirmSeeds.countryId(extracted.optString("country", ""),
            countries);
        assertEquals(c.isNull("countryId") ? null : c.getString("countryId"), countryId,
            name + " country");
        if (c.has("address")) {
          assertSameJson(c.getJSONObject("address"),
              InvoiceInboxConfirmSeeds.addressBody(extracted, countryId), name + " address");
        }
      }
    }
  }

  @Test
  @DisplayName("flag off sends vendor=true and the category, flag on only the category; never customer")
  void theVendorFlag() throws Exception {
    JSONObject extracted = new JSONObject().put("name", "  Acme  ");

    JSONObject off = InvoiceInboxConfirmSeeds.vendorBody(extracted, false, "CAT");
    JSONObject on = InvoiceInboxConfirmSeeds.vendorBody(extracted, true, "CAT");

    assertEquals("Acme", off.getString("name"));
    assertTrue(off.getBoolean("vendor"));
    assertEquals("CAT", off.getString("businessPartnerCategory"));
    assertFalse(on.has("vendor"));
    assertEquals("CAT", on.getString("businessPartnerCategory"));
    assertFalse(off.has("customer"));
    assertFalse(on.has("customer"));
  }

  @Test
  @DisplayName("only the data the document prints is sent; a non-string value is read as nothing")
  void onlyPresentValuesAreSent() throws Exception {
    JSONObject extracted = new JSONObject().put("name", "Acme").put("taxId", " ")
        .put("email", "a@b.c").put("phone", 12345);

    JSONObject body = InvoiceInboxConfirmSeeds.vendorBody(extracted, true, null);

    assertEquals("a@b.c", body.getString("etgoEmail"));
    assertFalse(body.has("taxID"));
    assertFalse(body.has("etgoPhone"));
    assertFalse(body.has("businessPartnerCategory"));
    assertEquals("", InvoiceInboxConfirmSeeds.vendorBody(null, true, null).getString("name"));
  }

  @Test
  @DisplayName("an address is usable when the document prints any of its parts")
  void usableAddress() throws Exception {
    assertTrue(InvoiceInboxConfirmSeeds.hasUsableAddress(new JSONObject().put("address", "Calle 1")));
    assertTrue(InvoiceInboxConfirmSeeds.hasUsableAddress(new JSONObject().put("postalCode", "28001")));
    assertTrue(InvoiceInboxConfirmSeeds.hasUsableAddress(new JSONObject().put("city", "Madrid")));
    assertTrue(InvoiceInboxConfirmSeeds.hasUsableAddress(new JSONObject().put("country", "ES")));
    assertFalse(InvoiceInboxConfirmSeeds.hasUsableAddress(new JSONObject().put("city", "  ")));
    assertFalse(InvoiceInboxConfirmSeeds.hasUsableAddress(new JSONObject()));
    assertFalse(InvoiceInboxConfirmSeeds.hasUsableAddress(null));
  }

  @Test
  @DisplayName("a blank printed country is Spain; Spain and Espana are Spain whatever the list translates")
  void blankAndTheSpainAliasesAreSpain() {
    assertEquals("C_ES", InvoiceInboxConfirmSeeds.countryId("", spainAndFrance()));
    assertEquals("C_ES", InvoiceInboxConfirmSeeds.countryId(null, spainAndFrance()));
    assertEquals("C_ES", InvoiceInboxConfirmSeeds.countryId("  ", spainAndFrance()));
    assertEquals("C_ES", InvoiceInboxConfirmSeeds.countryId("Espa\u00f1a", spainAndFrance()));
    assertEquals("C_ES", InvoiceInboxConfirmSeeds.countryId("SPAIN", spainAndFrance()));
    // a list that only knows Spain by its Spanish name, an es_ES tenant, and the other way round
    List<CountryRow> onlyEspana = List.of(new CountryRow("C_ES", "ES", "Espa\u00f1a"));
    List<CountryRow> onlySpain = List.of(new CountryRow("C_ES", "ES", "Spain"));
    assertEquals("C_ES", InvoiceInboxConfirmSeeds.countryId("Spain", onlyEspana));
    assertEquals("C_ES", InvoiceInboxConfirmSeeds.countryId("Espa\u00f1a", onlySpain));
  }

  @Test
  @DisplayName("a printed country is matched by ISO code, name or translated name, accents and case ignored")
  void aPrintedCountryIsMatched() {
    assertEquals("C_FR", InvoiceInboxConfirmSeeds.countryId("fr", spainAndFrance()));
    assertEquals("C_FR", InvoiceInboxConfirmSeeds.countryId("FRANCE", spainAndFrance()));
    assertEquals("C_FR", InvoiceInboxConfirmSeeds.countryId("  francia ", spainAndFrance()));
  }

  @Test
  @DisplayName("a printed country that matches nothing is never guessed, and no Spain means none")
  void anUnmatchedCountryIsNeverGuessed() {
    assertNull(InvoiceInboxConfirmSeeds.countryId("Norway", spainAndFrance()));
    assertNull(InvoiceInboxConfirmSeeds.countryId("", List.of(new CountryRow("C_FR", "FR", "France"))));
    assertNull(InvoiceInboxConfirmSeeds.countryId("Spain", List.of()));
  }

  /** Stubs {@code StructuredAddressComposer.isEnabled} (via its {@code GoFeatureFlags} backend) to
   * the given value, regardless of account — mirrors the pattern used by
   * {@code StructuredAddressComposerTest}. */
  private static MockedStatic<GoFeatureFlags> stubStructuredAddressFlag(boolean enabled) {
    MockedStatic<GoFeatureFlags> flags = mockStatic(GoFeatureFlags.class);
    flags.when(() -> GoFeatureFlags.isEnabled(
        eq(GoFeatureFlags.FLAG_STRUCTURED_ADDRESS_FOR_SEPA), any())).thenReturn(enabled);
    return flags;
  }

  @Test
  @DisplayName("the address body carries the country and only the parts that were printed "
      + "(structured-address-for-sepa off: pre-ETP-5806 behavior, no streetName/buildingNumber)")
  void theAddressBody() throws Exception {
    try (MockedStatic<GoAccountResolver> accounts = mockStatic(GoAccountResolver.class);
        MockedStatic<GoFeatureFlags> flags = stubStructuredAddressFlag(false)) {
      accounts.when(GoAccountResolver::currentAccountEmail).thenReturn("acme@example.com");

      JSONObject full = InvoiceInboxConfirmSeeds.addressBody(new JSONObject()
          .put("address", " Calle 1 ").put("postalCode", "28001").put("city", "Madrid"), "C_ES");
      JSONObject onlyCity = InvoiceInboxConfirmSeeds.addressBody(
          new JSONObject().put("city", "Oslo"), "C_NO");

      assertEquals("C_ES", full.getString("country"));
      assertEquals("Calle 1", full.getString("addressLine1"));
      assertEquals("28001", full.getString("postalCode"));
      assertEquals("Madrid", full.getString("cityName"));
      assertFalse(full.has("streetName"), "the flag is off: no structured fields are sent");
      assertFalse(full.has("buildingNumber"), "the flag is off: no structured fields are sent");
      assertEquals(2, onlyCity.length());
      assertFalse(onlyCity.has("addressLine1"));
      assertFalse(full.has("region"), "the region is never seeded");
    }
  }

  @Test
  @DisplayName("ETP-5806 repro (structured-address-for-sepa ON): a new-vendor address built from a "
      + "single OCR 'Street Number' line now carries streetName + buildingNumber, satisfying "
      + "ContactsLocationAddressHandler's mandatory check instead of failing "
      + "'Missing required field(s): streetName, buildingNumber'")
  void addressBodyParsesStreetAndNumberWhenStructuredAddressIsEnabled() throws Exception {
    try (MockedStatic<GoAccountResolver> accounts = mockStatic(GoAccountResolver.class);
        MockedStatic<GoFeatureFlags> flags = stubStructuredAddressFlag(true)) {
      accounts.when(GoAccountResolver::currentAccountEmail).thenReturn("acme@example.com");

      JSONObject body = InvoiceInboxConfirmSeeds.addressBody(new JSONObject()
          .put("address", "Calle Mayor 45").put("city", "Madrid"), "C_ES");

      assertEquals("Calle Mayor", body.getString("streetName"));
      assertEquals("45", body.getString("buildingNumber"));
      assertEquals("Madrid", body.getString("cityName"));
      // addressLine1 is still sent (harmless: ContactsLocationAddressHandler ignores it and
      // recomposes Address1/Address2 from the structured fields once streetName is present).
      assertEquals("Calle Mayor 45", body.getString("addressLine1"));
    }
  }

  @Test
  @DisplayName("ETP-5806 repro: a number-first OCR line is parsed in reverse order too")
  void addressBodyParsesNumberFirstLineWhenStructuredAddressIsEnabled() throws Exception {
    try (MockedStatic<GoAccountResolver> accounts = mockStatic(GoAccountResolver.class);
        MockedStatic<GoFeatureFlags> flags = stubStructuredAddressFlag(true)) {
      accounts.when(GoAccountResolver::currentAccountEmail).thenReturn("acme@example.com");

      JSONObject body = InvoiceInboxConfirmSeeds.addressBody(
          new JSONObject().put("address", "45 Main Street"), "C_ES");

      assertEquals("Main Street", body.getString("streetName"));
      assertEquals("45", body.getString("buildingNumber"));
    }
  }

  @Test
  @DisplayName("ETP-5806 repro: a line with no number falls back to the whole line as streetName, "
      + "no buildingNumber sent (the mandatory check's own 's/n' fallback applies downstream)")
  void addressBodyFallsBackToWholeLineWhenNoNumberIsFoundAndStructuredAddressIsEnabled()
      throws Exception {
    try (MockedStatic<GoAccountResolver> accounts = mockStatic(GoAccountResolver.class);
        MockedStatic<GoFeatureFlags> flags = stubStructuredAddressFlag(true)) {
      accounts.when(GoAccountResolver::currentAccountEmail).thenReturn("acme@example.com");

      JSONObject body = InvoiceInboxConfirmSeeds.addressBody(
          new JSONObject().put("address", "Calle Mayor"), "C_ES");

      assertEquals("Calle Mayor", body.getString("streetName"));
      assertFalse(body.has("buildingNumber"));
    }
  }

  @Test
  @DisplayName("ETP-5806 repro: no address line at all, flag on — nothing to parse, no structured "
      + "fields sent")
  void addressBodyWithNoAddressLineSendsNoStructuredFieldsEvenWhenEnabled() throws Exception {
    try (MockedStatic<GoAccountResolver> accounts = mockStatic(GoAccountResolver.class);
        MockedStatic<GoFeatureFlags> flags = stubStructuredAddressFlag(true)) {
      accounts.when(GoAccountResolver::currentAccountEmail).thenReturn("acme@example.com");

      JSONObject body = InvoiceInboxConfirmSeeds.addressBody(
          new JSONObject().put("city", "Oslo"), "C_NO");

      assertFalse(body.has("streetName"));
      assertFalse(body.has("buildingNumber"));
    }
  }

  @Test
  @DisplayName("splitStreetAndNumber: street-then-number, number-then-street, no number, and blank")
  void splitStreetAndNumberCases() {
    assertArrayEquals(new String[] {"Calle Mayor", "45"},
        InvoiceInboxConfirmSeeds.splitStreetAndNumber("Calle Mayor 45"));
    assertArrayEquals(new String[] {"Av. Libertador", "1234"},
        InvoiceInboxConfirmSeeds.splitStreetAndNumber("Av. Libertador 1234"));
    assertArrayEquals(new String[] {"Calle Mayor", "45"},
        InvoiceInboxConfirmSeeds.splitStreetAndNumber("Calle Mayor, 45"));
    assertArrayEquals(new String[] {"Main Street", "45"},
        InvoiceInboxConfirmSeeds.splitStreetAndNumber("45 Main Street"));
    assertArrayEquals(new String[] {"Just A Street Name", ""},
        InvoiceInboxConfirmSeeds.splitStreetAndNumber("Just A Street Name"));
    assertArrayEquals(new String[] {"", ""}, InvoiceInboxConfirmSeeds.splitStreetAndNumber("  "));
    assertArrayEquals(new String[] {"", ""}, InvoiceInboxConfirmSeeds.splitStreetAndNumber(null));
  }
}
