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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.etendoerp.go.schemaforge.ocr.OcrLookups.Party;

/**
 * How the inbox finds the vendor of a document with nobody to ask: by tax id (with or without its
 * VAT prefix) first, then by an exact name that no tax id contradicts. A name that only contains the
 * text never links, even with one hit.
 *
 * @covers com.etendoerp.go.schemaforge.ocr.InboxVendorMatcher
 */
class InboxVendorMatcherTest {

  private static final String STATE = "state";
  private static final String MATCHED_BY = "matchedBy";
  private static final String CANDIDATES = "candidates";
  private static final String TAX_ID = "B81639719";
  private static final Party ACME = new Party("V1", "Acme S.L.");
  private static final Party ACME_TWIN = new Party("V2", "Acme S.L.");

  private InboxFakeLookups lookups;
  private InboxVendorMatcher matcher;

  @BeforeEach
  void setUp() {
    lookups = new InboxFakeLookups();
    matcher = new InboxVendorMatcher(lookups);
  }

  // ---------------------------------------------------------------- by tax id

  @Test
  @DisplayName("one vendor with the tax id is the match, linked by tax id, without asking by name")
  void oneVendorByTaxIdIsLinked() throws Exception {
    lookups.byTaxId.put(TAX_ID, List.of(ACME));

    JSONObject vendor = matcher.match("Another Name", TAX_ID);

    assertEquals("linked", vendor.getString(STATE));
    assertEquals("taxId", vendor.getString(MATCHED_BY));
    assertEquals("V1", vendor.getString("id"));
    assertEquals("Acme S.L.", vendor.getString("name"));
    assertEquals(0, vendor.getJSONArray(CANDIDATES).length());
    assertTrue(lookups.nameSearches.isEmpty(), "the tax id settled it");
  }

  @Test
  @DisplayName("the id is searched cleaned (upper case, no separators), prefix included as read")
  void theTaxIdIsSearchedClean() throws Exception {
    lookups.byTaxId.put("ESB81639719", List.of(ACME));

    JSONObject vendor = matcher.match("Acme", " es-b.816 39719 ");

    assertEquals(List.of("ESB81639719"), lookups.taxIdSearches);
    assertEquals("V1", vendor.getString("id"));
  }

  @Test
  @DisplayName("two vendors with the same tax id are ambiguous, with both as candidates and their tax ids")
  void twoVendorsWithTheSameTaxIdAreAmbiguous() throws Exception {
    lookups.byTaxId.put(TAX_ID, List.of(ACME, new Party("V2", "Acme Logistics")));
    lookups.storedTaxIds.put("V1", TAX_ID);
    lookups.storedTaxIds.put("V2", "ES" + TAX_ID);

    JSONObject vendor = matcher.match("Acme", TAX_ID);

    assertEquals("ambiguous", vendor.getString(STATE));
    assertEquals("taxId", vendor.getString(MATCHED_BY));
    assertFalse(vendor.has("id"), "no vendor is chosen");
    JSONArray candidates = vendor.getJSONArray(CANDIDATES);
    assertEquals(2, candidates.length());
    assertEquals("V1", candidates.getJSONObject(0).getString("id"));
    assertEquals(TAX_ID, candidates.getJSONObject(0).getString("taxId"));
    assertEquals("ES" + TAX_ID, candidates.getJSONObject(1).getString("taxId"));
  }

  @Test
  @DisplayName("no more than ten candidates are kept")
  void candidatesAreCapped() throws Exception {
    List<Party> many = new ArrayList<>();
    for (int i = 0; i < 14; i++) {
      many.add(new Party("V" + i, "Vendor " + i));
    }
    lookups.byTaxId.put(TAX_ID, many);

    assertEquals(10, matcher.match("x", TAX_ID).getJSONArray(CANDIDATES).length());
  }

  @Test
  @DisplayName("a tax id shorter than five characters is not searched; the name decides")
  void aShortTaxIdIsNotSearched() throws Exception {
    lookups.byName = List.of(ACME);

    JSONObject vendor = matcher.match("Acme S.L.", "AB-1");

    assertTrue(lookups.taxIdSearches.isEmpty());
    assertEquals("linked", vendor.getString(STATE));
    assertEquals("name", vendor.getString(MATCHED_BY));
  }

  // ---------------------------------------------------------------- by name

  @Test
  @DisplayName("a tax id nobody holds falls back to the name: one exact vendor, no stored tax id, is linked")
  void exactNameWithoutAStoredTaxIdIsLinked() throws Exception {
    lookups.byName = List.of(ACME);

    JSONObject vendor = matcher.match("  ACME s.l.  ", TAX_ID);

    assertEquals(List.of(TAX_ID), lookups.taxIdSearches);
    assertEquals("linked", vendor.getString(STATE));
    assertEquals("name", vendor.getString(MATCHED_BY));
    assertEquals("V1", vendor.getString("id"));
  }

  @Test
  @DisplayName("the document without a tax id links an exact name whatever the vendor stores")
  void exactNameWithNoDocumentTaxIdIsLinked() throws Exception {
    lookups.byName = List.of(ACME);
    lookups.storedTaxIds.put("V1", TAX_ID);

    JSONObject vendor = matcher.match("Acme S.L.", "");

    assertEquals("linked", vendor.getString(STATE));
    assertTrue(lookups.taxIdSearches.isEmpty());
  }

  @Test
  @DisplayName("an exact name whose stored tax id is the document's own, spelled differently, is linked")
  void exactNameWithTheSameTaxIdIsLinked() throws Exception {
    lookups.byName = List.of(ACME);
    lookups.storedTaxIds.put("V1", "ES-" + TAX_ID);

    assertEquals("linked", matcher.match("Acme S.L.", TAX_ID).getString(STATE));
  }

  @Test
  @DisplayName("an exact name with a different tax id on each side is another company: ambiguous")
  void exactNameWithAConflictingTaxIdIsAmbiguous() throws Exception {
    lookups.byName = List.of(ACME);
    lookups.storedTaxIds.put("V1", "A28000727");

    JSONObject vendor = matcher.match("Acme S.L.", TAX_ID);

    assertEquals("ambiguous", vendor.getString(STATE));
    assertEquals("name", vendor.getString(MATCHED_BY));
    assertFalse(vendor.has("id"));
    assertEquals(1, vendor.getJSONArray(CANDIDATES).length());
    assertEquals("A28000727", vendor.getJSONArray(CANDIDATES).getJSONObject(0).getString("taxId"));
  }

  @Test
  @DisplayName("two vendors with the exact name are ambiguous, and only they are the candidates")
  void twoExactNamesAreAmbiguous() throws Exception {
    lookups.byName = List.of(ACME, ACME_TWIN, new Party("V3", "Acme S.L. Logistics"));

    JSONObject vendor = matcher.match("Acme S.L.", "");

    assertEquals("ambiguous", vendor.getString(STATE));
    assertEquals(2, vendor.getJSONArray(CANDIDATES).length());
  }

  @Test
  @DisplayName("a name that only contains the text is ambiguous even with a single hit: never linked")
  void aContainsHitNeverLinks() throws Exception {
    lookups.byName = List.of(new Party("V3", "Acme S.L. Logistics"));

    JSONObject vendor = matcher.match("Acme", "");

    assertEquals("ambiguous", vendor.getString(STATE));
    assertEquals("name", vendor.getString(MATCHED_BY));
    assertEquals(1, vendor.getJSONArray(CANDIDATES).length());
    assertFalse(vendor.has("id"));
  }

  @Test
  @DisplayName("an exact name among other hits is still the only exact one: linked")
  void oneExactAmongSeveralHitsIsLinked() throws Exception {
    lookups.byName = List.of(new Party("V3", "Acme S.L. Logistics"), ACME);

    JSONObject vendor = matcher.match("Acme S.L.", "");

    assertEquals("linked", vendor.getString(STATE));
    assertEquals("V1", vendor.getString("id"));
  }

  @Test
  @DisplayName("a named vendor nobody knows is new, with no candidates")
  void aNamedUnknownVendorIsNew() throws Exception {
    JSONObject vendor = matcher.match("Brand New Ltd", TAX_ID);

    assertEquals("new", vendor.getString(STATE));
    assertEquals(0, vendor.getJSONArray(CANDIDATES).length());
    assertFalse(vendor.has("id"));
  }

  @Test
  @DisplayName("neither a name nor a tax id read: none, and nothing is searched by name")
  void nothingReadIsNone() throws Exception {
    for (String blank : new String[] { null, "", "   " }) {
      JSONObject vendor = matcher.match(blank, "");

      assertEquals("none", vendor.getString(STATE));
    }
    assertTrue(lookups.nameSearches.isEmpty());
  }

  @Test
  @DisplayName("a tax id with no name that nobody holds is none: there is nothing to create a vendor from")
  void aTaxIdAloneThatMatchesNothingIsNone() throws Exception {
    assertEquals("none", matcher.match("", TAX_ID).getString(STATE));
  }
}
