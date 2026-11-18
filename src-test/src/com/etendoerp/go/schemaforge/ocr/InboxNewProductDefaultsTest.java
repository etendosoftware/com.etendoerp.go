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

import java.util.List;
import java.util.Optional;

import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.etendoerp.go.schemaforge.ocr.OcrLookups.Party;

/**
 * What a product that does not exist yet is pre-filled with: the printed unit when it resolves
 * (source {@code ocr}), else the default unit, plus the default category (source {@code default});
 * each value absent when none was found, and every question asked at most once per instance.
 *
 * @covers com.etendoerp.go.schemaforge.ocr.InboxNewProductDefaults
 * @covers com.etendoerp.go.schemaforge.ocr.OcrLookups
 */
class InboxNewProductDefaultsTest {

  private InboxFakeLookups lookups;
  private InboxNewProductDefaults defaults;

  @BeforeEach
  void setUp() {
    lookups = new InboxFakeLookups();
    defaults = new InboxNewProductDefaults(lookups, "ORG-1");
  }

  @Test
  @DisplayName("the printed unit wins over the default unit and is tagged ocr")
  void thePrintedUnitWins() throws Exception {
    lookups.unitsByPrinted.put("kg", new Party("U-KG", "Kilogramo"));
    lookups.defaultUom = Optional.of(new Party("U-DEF", "Unit"));
    lookups.defaultCategory = Optional.of(new Party("C-DEF", "General"));

    JSONObject draft = defaults.draftFor("kg");

    assertEquals("U-KG", draft.getString("uomId"));
    assertEquals("ocr", draft.getString("uomSource"));
    assertEquals("C-DEF", draft.getString("categoryId"));
    assertEquals("default", draft.getString("categorySource"));
    assertEquals(4, draft.length());
    assertEquals(0, lookups.defaultUomAsks, "the default unit is not needed");
  }

  @Test
  @DisplayName("no printed unit, or one that matches none, gives the default unit tagged default")
  void theDefaultUnitIsTheFallback() throws Exception {
    lookups.defaultUom = Optional.of(new Party("U-DEF", "Unit"));

    for (String printed : new String[] { null, "", "  ", "zzz" }) {
      JSONObject draft = defaults.draftFor(printed);
      assertEquals("U-DEF", draft.getString("uomId"), String.valueOf(printed));
      assertEquals("default", draft.getString("uomSource"));
    }
  }

  @Test
  @DisplayName("without a default unit there is no uomId and without a category no categoryId")
  void valuesAreAbsentWhenNothingIsFound() throws Exception {
    assertEquals(0, defaults.draftFor("kg").length());

    lookups.defaultUom = Optional.of(new Party("U-DEF", "Unit"));
    InboxNewProductDefaults onlyUnit = new InboxNewProductDefaults(lookups, "ORG-1");
    JSONObject draft = onlyUnit.draftFor(null);
    assertFalse(draft.has("categoryId"));
    assertFalse(draft.has("categorySource"));
    assertTrue(draft.has("uomId"));
  }

  @Test
  @DisplayName("each question is asked once per instance, the printed text by its trimmed value")
  void everyQuestionIsAskedOnce() throws Exception {
    lookups.defaultUom = Optional.of(new Party("U-DEF", "Unit"));
    lookups.defaultCategory = Optional.of(new Party("C-DEF", "General"));

    defaults.draftFor("zzz");
    defaults.draftFor(" zzz ");
    defaults.draftFor("zzz");
    defaults.draftFor("yyy");

    assertEquals(List.of("zzz", "yyy"), lookups.printedUnitAsks);
    assertEquals(1, lookups.defaultUomAsks);
    assertEquals(List.of("ORG-1"), lookups.categoryOrganizations);
  }

  @Test
  @DisplayName("an implementation that supports none of the lookups resolves nothing, never a guess")
  void theInterfaceDefaultsResolveNothing() {
    OcrLookups bare = org.mockito.Mockito.mock(OcrLookups.class,
        org.mockito.Mockito.CALLS_REAL_METHODS);

    assertTrue(bare.uomByPrintedUnit("kg").isEmpty());
    assertTrue(bare.defaultUom().isEmpty());
    assertTrue(bare.defaultProductCategory("ORG-1").isEmpty());
    assertTrue(bare.uomById("U-1").isEmpty());
  }
}
