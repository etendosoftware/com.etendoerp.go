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
 * All portions are Copyright © 2021–2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.schemaforge.ocr;

import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * Tax id normalization shared by the receiver check and the vendor match (ETP-5715, ETP-6048): the
 * rules of the application's {@code receiverTaxIdCheck.js}, rule for rule.
 *
 * <p>No check-digit validation; leading zeros are kept; a known VAT country prefix is ignored on
 * either side (so {@code ES-B81639719} matches {@code B81639719}), while the letters of an id that
 * only look like a prefix (the X/Y/Z of an NIE, the letter of a CIF) are never stripped.</p>
 */
public final class TaxIdNormalizer {

  /** Leading two-letter prefixes that are real VAT country prefixes. GR is an alias of EL. */
  private static final Set<String> VAT_PREFIXES = Set.of("AT", "BE", "BG", "CY", "CZ", "DE", "DK",
      "EE", "EL", "ES", "FI", "FR", "HR", "HU", "IE", "IT", "LT", "LU", "LV", "MT", "NL", "PL",
      "PT", "RO", "SE", "SI", "SK", "XI", "GB");

  /** The alias of {@code EL} (the VAT prefix of Greece) some documents and records use. */
  private static final String GREEK_ALIAS = "GR";

  private TaxIdNormalizer() {
  }

  /**
   * Uppercase, keeping only letters A-Z and digits; blank and placeholder values become empty.
   *
   * @param value the id as written
   * @return the cleaned id
   */
  public static String clean(String value) {
    return value == null ? "" : value.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
  }

  /**
   * The id without a known VAT country prefix, only when something remains after it.
   *
   * @param clean an id already cleaned
   * @return the id without its prefix
   */
  public static String stripVatPrefix(String clean) {
    String head = clean.length() >= 2 ? clean.substring(0, 2) : clean;
    String prefix = "GR".equals(head) ? "EL" : head;
    return clean.length() > 2 && VAT_PREFIXES.contains(prefix) ? clean.substring(2) : clean;
  }

  /**
   * Whether two ids are the same once cleaned, with or without a country prefix.
   *
   * @param a first id as written
   * @param b second id as written
   * @return {@code true} when they match
   */
  public static boolean idsMatch(String a, String b) {
    String ca = clean(a);
    String cb = clean(b);
    if (ca.isEmpty() || cb.isEmpty()) {
      return false;
    }
    return ca.equals(cb) || stripVatPrefix(ca).equals(stripVatPrefix(cb));
  }

  /**
   * Every cleaned spelling a stored id could have for the same company: the id as given, without its
   * VAT prefix, and the prefixless id behind each known prefix. For a search over cleaned stored ids.
   *
   * @param clean an id already cleaned
   * @return the spellings, empty when {@code clean} is empty
   */
  public static Set<String> variants(String clean) {
    Set<String> out = new TreeSet<>();
    if (clean == null || clean.isEmpty()) {
      return out;
    }
    String bare = stripVatPrefix(clean);
    out.add(clean);
    out.add(bare);
    for (String prefix : VAT_PREFIXES) {
      out.add(prefix + bare);
    }
    // EL and GR are the same Greek prefix: when one is spelled, the stored id may carry the other.
    out.add(GREEK_ALIAS + bare);
    return out;
  }
}
