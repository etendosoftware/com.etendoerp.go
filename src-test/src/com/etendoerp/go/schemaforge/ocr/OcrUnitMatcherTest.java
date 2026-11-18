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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.etendoerp.go.schemaforge.ocr.OcrUnitMatcher.Unit;

/**
 * The unit a line printed, resolved to one of the tenant's units: by symbol, EDI code or name
 * ignoring case and diacritics, through a small alias table, tenant over System, never a guess.
 * Pure: the units are handed in.
 *
 * @covers com.etendoerp.go.schemaforge.ocr.OcrUnitMatcher
 */
class OcrUnitMatcherTest {

  private static final String TENANT = "T1";
  private static final String SYSTEM = "0";

  private static Unit unit(String id, String client, String symbol, String edi, String name) {
    return new Unit(id, client, symbol, edi, name);
  }

  private static Optional<String> resolve(String printed, Unit... units) {
    return OcrUnitMatcher.resolve(printed, List.of(units), TENANT);
  }

  private static void assertResolves(String id, String printed, Unit... units) {
    assertEquals(Optional.of(id), resolve(printed, units), printed);
  }

  private static void assertNone(String printed, Unit... units) {
    assertTrue(resolve(printed, units).isEmpty(), printed + " resolves to " + resolve(printed, units));
  }

  private static final Unit KG = unit("KG", SYSTEM, "kg", "KGM", "Kilogram");

  @Test
  @DisplayName("an exact match by symbol, EDI code or name, ignoring case and diacritics")
  void exactMatchBySymbolEdiCodeOrName() {
    assertResolves("KG", "kg", KG);
    assertResolves("KG", "KG", KG);
    assertResolves("KG", "kgm", KG);
    assertResolves("KG", "KILOGRAM", KG);
    assertResolves("KG", "Kilogramo", unit("KG", SYSTEM, "kg", "KGM", "Kilogramo"));
    assertResolves("H", "  Hóra  ", unit("H", SYSTEM, "h", "HUR", "Hora"));
  }

  @Test
  @DisplayName("superscripts read as digits, trailing dots and repeated spaces are ignored")
  void foldingRules() {
    Unit m2 = unit("M2", SYSTEM, "m2", null, "Square meter");
    assertResolves("M2", "m\u00b2", m2);
    assertResolves("M2", "m2.", m2);
    assertResolves("M2", "M2...", m2);
    assertResolves("SM", "Square   meter", unit("SM", SYSTEM, "sm", null, "Square meter"));
    assertEquals("m2", OcrUnitMatcher.fold(" M\u00b2. "));
    assertEquals("", OcrUnitMatcher.fold(null));
  }

  @Test
  @DisplayName("null, blank text or no units resolve to none")
  void nothingToResolve() {
    assertNone(null, KG);
    assertNone("", KG);
    assertNone("   ", KG);
    assertNone("...", KG);
    assertTrue(OcrUnitMatcher.resolve("kg", null, TENANT).isEmpty());
    assertTrue(OcrUnitMatcher.resolve("kg", List.of(), TENANT).isEmpty());
  }

  @Test
  @DisplayName("the Spanish and English aliases lead to the unit that exists")
  void aliasFamilies() {
    assertResolves("KG", "kilos", KG);
    assertResolves("KG", "Kilogramos", KG);
    assertResolves("KG", "kgs", KG);
    assertResolves("L", "litros", unit("L", SYSTEM, "l", null, "Liter"));
    assertResolves("H", "horas", unit("H", SYSTEM, "h", null, "Hour"));
    assertResolves("H", "hrs", unit("H", SYSTEM, "h", null, "Hour"));
    assertResolves("D", "dias", unit("D", SYSTEM, "d", null, "Day"));
    assertResolves("BX", "cajas", unit("BX", SYSTEM, "bx", null, "Box"));
    assertResolves("SRV", "servicios", unit("SRV", SYSTEM, "srv", null, "Servicio"));
    assertResolves("MT", "metros", unit("MT", SYSTEM, "m", null, "Meter"));
  }

  @Test
  @DisplayName("ud, uds, und, unds, un, unidades and each all reach the generic unit")
  void theGenericUnitAliases() {
    Unit generic = unit("UD", SYSTEM, "ud", "C62", "Unit");
    for (String printed : new String[] { "ud", "uds", "ud.", "und", "unds", "u", "un",
        "unidades", "unit", "units", "ea", "each" }) {
      assertResolves("UD", printed, generic);
    }
  }

  @Test
  @DisplayName("the piece group prefers a piece unit and falls back to the generic unit")
  void pieceFallsBackToTheGenericUnit() {
    Unit generic = unit("UD", SYSTEM, "ud", null, "Unit");
    Unit piece = unit("PZA", TENANT, "pza", null, "Pieza");
    for (String printed : new String[] { "pza", "pieza", "pc", "pcs", "pz", "pieces" }) {
      assertResolves("UD", printed, generic);
      assertResolves("PZA", printed, generic, piece);
    }
  }

  @Test
  @DisplayName("mt and mts are not aliases: on English invoices mt is the metric ton")
  void mtIsNotAnAlias() {
    Unit meter = unit("MT", SYSTEM, "m", null, "Meter");
    assertNone("mt", meter);
    assertNone("mts", meter);
  }

  @Test
  @DisplayName("several units spelling the same text stay ambiguous: m is meter, month and labour month")
  void ambiguityGivesNone() {
    assertNone("m", unit("MT", SYSTEM, "m", null, "Meter"),
        unit("MO", SYSTEM, "m", null, "Month"), unit("LM", SYSTEM, "m", null, "Labour month"));
    assertNone("kg", unit("A", SYSTEM, "kg", null, "Kilo A"), unit("B", SYSTEM, "KG", null, "Kilo B"));
  }

  @Test
  @DisplayName("two tenant units that spell the same key, or two System units, are ambiguous")
  void twoOfTheSameOwnerAreAmbiguous() {
    assertNone("kg", unit("A", TENANT, "kg", null, "Kilo A"), unit("B", TENANT, "kg", null, "Kilo B"));
    assertNone("kg", unit("A", SYSTEM, "kg", null, "Kilo A"), unit("B", SYSTEM, "kg", null, "Kilo B"));
  }

  @Test
  @DisplayName("the tenant's own unit wins over the System's one with the same spelling")
  void tenantWinsOverSystem() {
    assertResolves("OWN", "kg", unit("SYS", SYSTEM, "kg", null, "Kilogram"),
        unit("OWN", TENANT, "kg", null, "Kilogram"));
    assertResolves("OWN", "kilos", unit("SYS", SYSTEM, "kg", null, "Kilogram"),
        unit("OWN", TENANT, "kg", null, "Kilogram"));
  }

  @Test
  @DisplayName("an alias that reaches no existing unit gives none: nothing is invented")
  void anAliasWithoutAUnitGivesNone() {
    assertNone("kilos", unit("L", SYSTEM, "l", null, "Liter"));
    assertNone("zzz", KG);
  }

  @Test
  @DisplayName("a direct match beats the aliases of the same text")
  void aDirectMatchBeatsTheAliases() {
    // "ud" is spelled by a tenant unit, so the generic-unit alias group is never consulted
    assertResolves("MINE", "ud", unit("MINE", TENANT, "ud", null, "My unit"),
        unit("GEN", SYSTEM, "unit", null, "Unit"));
  }
}
