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

import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The tax id normalization the receiver check and the vendor match share: what a spelling of an id
 * reduces to, and which spellings are the same company.
 *
 * @covers com.etendoerp.go.schemaforge.ocr.TaxIdNormalizer
 */
class TaxIdNormalizerTest {

  private static final String CIF = "B81639719";
  private static final String CIF_WITH_PREFIX = "ESB81639719";
  private static final String NIE = "X1234567L";
  private static final String GREEK_BARE = "123456789";

  @Test
  @DisplayName("clean keeps A-Z and digits in upper case: spaces, dots, dashes and slashes go")
  void cleanKeepsLettersAndDigits() {
    assertEquals(CIF_WITH_PREFIX, TaxIdNormalizer.clean(" es-b.816 39719 "));
    assertEquals("12345678Z", TaxIdNormalizer.clean("12.345.678-z"));
    assertEquals("A12345678", TaxIdNormalizer.clean("A/12345678"));
  }

  @Test
  @DisplayName("clean turns null, blank and placeholder values into an empty string")
  void cleanOfNothingIsEmpty() {
    assertEquals("", TaxIdNormalizer.clean(null));
    assertEquals("", TaxIdNormalizer.clean(""));
    assertEquals("", TaxIdNormalizer.clean("   "));
    assertEquals("", TaxIdNormalizer.clean("- . -"));
  }

  @Test
  @DisplayName("stripVatPrefix removes a known country prefix and keeps what follows")
  void stripsAKnownPrefix() {
    assertEquals(CIF, TaxIdNormalizer.stripVatPrefix(CIF_WITH_PREFIX));
    assertEquals("501964843", TaxIdNormalizer.stripVatPrefix("PT501964843"));
    assertEquals(GREEK_BARE, TaxIdNormalizer.stripVatPrefix("EL" + GREEK_BARE));
  }

  @Test
  @DisplayName("GR is an alias of EL: both prefixes are stripped")
  void greekAliasIsStripped() {
    assertEquals(GREEK_BARE, TaxIdNormalizer.stripVatPrefix("GR" + GREEK_BARE));
    assertEquals(GREEK_BARE, TaxIdNormalizer.stripVatPrefix("EL" + GREEK_BARE));
  }

  @Test
  @DisplayName("the letters of an NIE or a CIF are not a country prefix and are never stripped")
  void idLettersAreNotPrefixes() {
    assertEquals(NIE, TaxIdNormalizer.stripVatPrefix(NIE));
    assertEquals(CIF, TaxIdNormalizer.stripVatPrefix(CIF));
    assertEquals("12345678Z", TaxIdNormalizer.stripVatPrefix("12345678Z"));
  }

  @Test
  @DisplayName("a value that is only a prefix, or shorter, is left as it is")
  void nothingRemainingKeepsTheValue() {
    assertEquals("ES", TaxIdNormalizer.stripVatPrefix("ES"));
    assertEquals("E", TaxIdNormalizer.stripVatPrefix("E"));
    assertEquals("", TaxIdNormalizer.stripVatPrefix(""));
  }

  @Test
  @DisplayName("idsMatch ignores formatting and a country prefix on either side")
  void idsMatchIgnoresFormattingAndPrefix() {
    assertTrue(TaxIdNormalizer.idsMatch("B-81.639.719", CIF));
    assertTrue(TaxIdNormalizer.idsMatch("ES-B81639719", CIF), "prefix on the first only");
    assertTrue(TaxIdNormalizer.idsMatch(CIF, "es b81639719"), "prefix on the second only");
    assertTrue(TaxIdNormalizer.idsMatch(CIF_WITH_PREFIX, "ES B 81639719"), "prefix on both");
  }

  @Test
  @DisplayName("EL and GR spell the same Greek id")
  void greekSpellingsMatch() {
    assertTrue(TaxIdNormalizer.idsMatch("EL" + GREEK_BARE, "GR" + GREEK_BARE));
    assertTrue(TaxIdNormalizer.idsMatch("GR" + GREEK_BARE, GREEK_BARE));
    assertTrue(TaxIdNormalizer.idsMatch(GREEK_BARE, "EL" + GREEK_BARE));
  }

  @Test
  @DisplayName("different ids do not match, and an NIE is not its own tail")
  void differentIdsDoNotMatch() {
    assertFalse(TaxIdNormalizer.idsMatch(CIF, "B81639710"));
    assertFalse(TaxIdNormalizer.idsMatch(NIE, "1234567L"));
  }

  @Test
  @DisplayName("a blank or null side never matches, not even another blank")
  void blankNeverMatches() {
    assertFalse(TaxIdNormalizer.idsMatch(null, null));
    assertFalse(TaxIdNormalizer.idsMatch("", ""));
    assertFalse(TaxIdNormalizer.idsMatch(CIF, null));
    assertFalse(TaxIdNormalizer.idsMatch("  ", CIF));
    assertFalse(TaxIdNormalizer.idsMatch("- -", "."));
  }

  @Test
  @DisplayName("variants lists the id, its bare form and the bare form behind each country prefix")
  void variantsCoverTheStoredSpellings() {
    Set<String> fromBare = TaxIdNormalizer.variants(CIF);
    assertTrue(fromBare.containsAll(Set.of(CIF, CIF_WITH_PREFIX, "PT" + CIF, "EL" + CIF)));

    Set<String> fromPrefixed = TaxIdNormalizer.variants(CIF_WITH_PREFIX);
    assertTrue(fromPrefixed.contains(CIF), "the document had a prefix, the stored id has none");
    assertTrue(fromPrefixed.contains(CIF_WITH_PREFIX));
  }

  @Test
  @DisplayName("variants of a Greek id given as GR reach the EL and the prefixless spellings")
  void variantsOfAGreekId() {
    Set<String> spellings = TaxIdNormalizer.variants("GR" + GREEK_BARE);

    assertTrue(spellings.containsAll(Set.of("GR" + GREEK_BARE, "EL" + GREEK_BARE, GREEK_BARE)));
  }

  @Test
  @DisplayName("variants of nothing is empty: a blank id is never searched")
  void variantsOfNothingIsEmpty() {
    assertTrue(TaxIdNormalizer.variants(null).isEmpty());
    assertTrue(TaxIdNormalizer.variants("").isEmpty());
  }

  @Test
  @DisplayName("an NIE is only searched as itself and behind prefixes, never as its tail")
  void variantsOfAnNieKeepTheLetter() {
    Set<String> spellings = TaxIdNormalizer.variants(NIE);

    assertTrue(spellings.contains(NIE));
    assertTrue(spellings.contains("ES" + NIE));
    assertFalse(spellings.contains("1234567L"));
  }
}
