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

import java.text.Normalizer;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Resolves the unit of measure a line printed ("kg", "ud.", "Horas") to one of the tenant's units
 * (ETP-6051). Pure: it works on the rows it is handed and never reads the database.
 *
 * <p>The comparison ignores case and diacritics and looks at the symbol, the EDI code and the name
 * of each unit. A printed text that matches nothing goes through a small Spanish and English alias
 * table; an alias only ever leads to a unit that exists in the rows, so it never invents one. There
 * is no guessing: when more than one unit still matches, the answer is none. When the matches
 * span the tenant's own units and the System's, the tenant's win (the precedence of every default
 * of the product window); two of the tenant's, or two of the System's, stay ambiguous. A text that
 * several units spell (in the stock data {@code m} is meter, month and labour month) stays
 * unresolved; and {@code mt} is not an alias at all, because on English invoices it is the metric ton.</p>
 */
public final class OcrUnitMatcher {

  /** A unit of measure as the matcher sees it. */
  public static final class Unit {
    private final String id;
    private final String clientId;
    private final String symbol;
    private final String ediCode;
    private final String name;

    /**
     * Describes a unit.
     *
     * @param id       the {@code C_UOM_ID}
     * @param clientId the owner client ({@code "0"} for System)
     * @param symbol   {@code UOMSymbol}, may be {@code null}
     * @param ediCode  the EDI code, may be {@code null}
     * @param name     the name, may be {@code null}
     */
    public Unit(String id, String clientId, String symbol, String ediCode, String name) {
      this.id = id;
      this.clientId = clientId;
      this.symbol = symbol;
      this.ediCode = ediCode;
      this.name = name;
    }

    private boolean spells(String key) {
      return key.equals(fold(symbol)) || key.equals(fold(ediCode)) || key.equals(fold(name));
    }
  }

  /** The generic unit terms: the last resort of every "piece" alias. */
  private static final List<String> UNIT = List.of("unit", "ud", "ea", "unidad");
  /** Alias groups per folded printed text, tried in order; the first group that matches any unit decides. */
  private static final Map<String, List<List<String>>> ALIASES = aliases();

  private OcrUnitMatcher() {
  }

  /**
   * Resolves a printed unit.
   *
   * @param printed  the unit as printed, may be {@code null}
   * @param units    the active units the tenant can see
   * @param clientId the current client (its own units win over System's)
   * @return the id of the one unit, empty when none or several match
   */
  public static Optional<String> resolve(String printed, List<Unit> units, String clientId) {
    String key = fold(printed);
    if (key.isEmpty() || units == null || units.isEmpty()) {
      return Optional.empty();
    }
    Set<Unit> direct = matches(List.of(key), units);
    if (!direct.isEmpty()) {
      return only(direct, clientId);
    }
    for (List<String> group : ALIASES.getOrDefault(key, List.of())) {
      Set<Unit> found = matches(group, units);
      if (!found.isEmpty()) {
        return only(found, clientId);
      }
    }
    return Optional.empty();
  }

  private static Set<Unit> matches(List<String> keys, List<Unit> units) {
    Set<Unit> out = new LinkedHashSet<>();
    for (Unit unit : units) {
      for (String key : keys) {
        if (unit.spells(key)) {
          out.add(unit);
        }
      }
    }
    return out;
  }

  /** The single unit of the set, the tenant's own ones first; empty when still more than one. */
  private static Optional<String> only(Set<Unit> found, String clientId) {
    Set<String> ids = new LinkedHashSet<>();
    for (Unit unit : found) {
      if (unit.clientId != null && unit.clientId.equals(clientId)) {
        ids.add(unit.id);
      }
    }
    if (ids.isEmpty()) {
      for (Unit unit : found) {
        ids.add(unit.id);
      }
    }
    return ids.size() == 1 ? Optional.of(ids.iterator().next()) : Optional.empty();
  }

  /**
   * Lower case, no diacritics, superscripts as digits ({@code m²} is {@code m2}), no trailing dot,
   * single spaces.
   *
   * @param value the text, may be {@code null}
   * @return the comparison key, empty for nothing
   */
  static String fold(String value) {
    if (value == null) {
      return "";
    }
    String folded = Normalizer.normalize(value, Normalizer.Form.NFKD).replaceAll("\\p{M}", "")
        .toLowerCase(Locale.ROOT).trim().replaceAll("\\s+", " ");
    while (folded.endsWith(".")) {
      folded = folded.substring(0, folded.length() - 1).trim();
    }
    return folded;
  }

  private static Map<String, List<List<String>>> aliases() {
    Map<String, List<List<String>>> map = new HashMap<>();
    alias(map, List.of("ud", "uds", "und", "unds", "u", "un", "unid", "unidad", "unidades",
        "unit", "units", "ea", "each"), UNIT);
    // Pieces: a tenant "Piece"/"Pieza" unit first, else the generic unit (the stock data has no piece).
    alias(map, List.of("pza", "pz", "pc", "pcs", "pieza", "piezas", "piece", "pieces"),
        List.of("piece", "pieza", "pza"), UNIT);
    alias(map, List.of("kg", "kgs", "kilo", "kilos", "kilogramo", "kilogramos", "kilogram",
        "kilograms"), List.of("kg", "kilogram", "kilogramo"));
    alias(map, List.of("g", "gr", "grs", "gramo", "gramos", "gram", "grams"),
        List.of("g", "gram", "gramo"));
    alias(map, List.of("l", "lt", "lts", "litro", "litros", "liter", "liters", "litre",
        "litres"), List.of("l", "liter", "litre", "litro"));
    alias(map, List.of("metro", "metros", "meter", "meters", "metre", "metres"),
        List.of("meter", "metre", "metro"));
    alias(map, List.of("cm", "centimetro", "centimetros", "centimeter", "centimetre"),
        List.of("cm", "centimeter", "centimetre", "centimetro"));
    alias(map, List.of("mm", "milimetro", "milimetros", "millimeter", "millimetre"),
        List.of("mm", "millimeter", "millimetre", "milimetro"));
    alias(map, List.of("h", "hr", "hrs", "hora", "horas", "hour", "hours"),
        List.of("h", "hour", "hora", "hr"));
    alias(map, List.of("dia", "dias", "day", "days"), List.of("day", "dia"));
    alias(map, List.of("caja", "cajas", "box", "boxes"), List.of("box", "caja", "case"));
    alias(map, List.of("pack", "packs", "paquete", "paquetes"),
        List.of("pack", "paquete", "package"));
    alias(map, List.of("servicio", "servicios", "service", "services"),
        List.of("service", "servicio"));
    return map;
  }

  @SafeVarargs
  private static void alias(Map<String, List<List<String>>> map, List<String> printed,
      List<String>... groups) {
    for (String key : printed) {
      map.put(key, List.of(groups));
    }
  }
}
