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
 * All portions are Copyright (C) 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */
package com.etendoerp.go.schemaforge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.Test;

import com.etendoerp.go.schemaforge.DefaultTaxResolver.Determination;
import com.etendoerp.go.schemaforge.DefaultTaxResolver.Result;

/**
 * Unit tests for the pure selection logic of {@link DefaultTaxResolver#rank} (the SQL fetchers are
 * not covered here).
 *
 * @covers com.etendoerp.go.schemaforge.DefaultTaxResolver
 */
public class DefaultTaxResolverTest {

  private static final String CLIENT = "C1";
  private static final String SYSTEM = "0";

  private static Determination det(String taxId, String rate, boolean summary, String catName,
      boolean catDefault, String catClient, long products) {
    return new Determination(taxId, "tax-" + taxId, new BigDecimal(rate), summary, "cat-" + taxId,
        catName, catDefault, catClient, products);
  }

  private static Determination det(String taxId, String rate) {
    return det(taxId, rate, false, "cat " + taxId, false, CLIENT, 0);
  }

  private static List<String> ids(List<Determination> list) {
    return list.stream().map(Determination::taxId).collect(Collectors.toList());
  }

  // ---- no rate ----

  @Test
  public void withoutRateTheProductDeterminationWins() {
    Determination product = det("P", "10");
    Determination def = det("D", "21", false, "def", true, CLIENT, 5);

    Result r = DefaultTaxResolver.rank(Arrays.asList(def), product, null);

    assertSame(product, r.chosen());
    assertEquals(DefaultTaxResolver.SOURCE_PRODUCT_CATEGORY, r.source());
    assertTrue(r.candidates().isEmpty());
  }

  @Test
  public void withoutRateTheDefaultCategoryIsUsedWhenThereIsNoProduct() {
    Determination def = det("D", "21", false, "def", true, CLIENT, 5);

    Result r = DefaultTaxResolver.rank(Arrays.asList(det("X", "4"), def), null, null);

    assertSame(def, r.chosen());
    assertEquals(DefaultTaxResolver.SOURCE_DEFAULT_CATEGORY, r.source());
  }

  @Test
  public void withoutRateTheClientDefaultCategoryBeatsTheSystemOne() {
    Determination system = det("S", "21", false, "aaa", true, SYSTEM, 0);
    Determination own = det("O", "10", false, "zzz", true, CLIENT, 0);

    Result r = DefaultTaxResolver.rank(Arrays.asList(system, own), null, null);

    assertSame(own, r.chosen());
    assertEquals(DefaultTaxResolver.SOURCE_DEFAULT_CATEGORY, r.source());
  }

  @Test
  public void withoutRateAndWithoutAnythingTheSourceIsNoContext() {
    Result r = DefaultTaxResolver.rank(Arrays.asList(det("X", "21")), null, null);

    assertNull(r.chosen());
    assertEquals(DefaultTaxResolver.SOURCE_NO_CONTEXT, r.source());
    assertTrue(r.candidates().isEmpty());
  }

  // ---- with rate ----

  @Test
  public void rateMatchesWithinTheToleranceOnly() {
    Result inside = DefaultTaxResolver.rank(Arrays.asList(det("A", "21.004")), null,
        new BigDecimal("21"));
    Result outside = DefaultTaxResolver.rank(Arrays.asList(det("A", "21.01")), null,
        new BigDecimal("21"));

    assertEquals("A", inside.chosen().taxId());
    assertNull(outside.chosen());
    assertEquals(DefaultTaxResolver.SOURCE_NO_MATCH, outside.source());
  }

  @Test
  public void withRateAndNoListTheSourceIsNoMatch() {
    for (List<Determination> empty : Arrays.<List<Determination>>asList(null,
        Collections.emptyList())) {
      Result r = DefaultTaxResolver.rank(empty, null, new BigDecimal("21"));

      assertNull(r.chosen());
      assertEquals(DefaultTaxResolver.SOURCE_NO_MATCH, r.source());
      assertTrue(r.candidates().isEmpty());
    }
  }

  @Test
  public void productCategoryBeatsDefaultCategoryBeatsOthers() {
    Determination other = det("O", "21", false, "a", false, CLIENT, 99);
    Determination def = det("D", "21", false, "b", true, CLIENT, 0);
    Determination product = det("P", "21", false, "c", false, CLIENT, 0);

    Result withProduct = DefaultTaxResolver.rank(Arrays.asList(other, def), product,
        new BigDecimal("21"));
    Result withDefault = DefaultTaxResolver.rank(Arrays.asList(other, def), null,
        new BigDecimal("21"));

    assertEquals("P", withProduct.chosen().taxId());
    assertEquals(DefaultTaxResolver.SOURCE_PRODUCT_CATEGORY, withProduct.source());
    assertEquals(Arrays.asList("D", "O"), ids(withProduct.candidates()));
    assertEquals("D", withDefault.chosen().taxId());
    assertEquals(DefaultTaxResolver.SOURCE_DEFAULT_CATEGORY, withDefault.source());
  }

  @Test
  public void nonSummaryThenMoreProductsThenCategoryNameBreakTies() {
    Determination summary = det("S", "21", true, "a", false, CLIENT, 100);
    Determination few = det("F", "21", false, "b", false, CLIENT, 1);
    Determination many = det("M", "21", false, "c", false, CLIENT, 10);
    Determination tieB = det("TB", "21", false, "z", false, CLIENT, 10);
    Determination tieA = det("TA", "21", false, "y", false, CLIENT, 10);

    Result r = DefaultTaxResolver.rank(Arrays.asList(summary, few, tieB, many, tieA), null,
        new BigDecimal("21"));

    // many (c), tieA (y), tieB (z) all have 10 products: ordered by category name.
    assertEquals(Arrays.asList("M", "TA", "TB", "F", "S"), concat(r.chosen(), r.candidates()));
    assertEquals(DefaultTaxResolver.SOURCE_RATE_MATCH, r.source());
  }

  private static List<String> concat(Determination first, List<Determination> rest) {
    List<String> out = new ArrayList<>();
    out.add(first.taxId());
    out.addAll(ids(rest));
    return out;
  }

  @Test
  public void withRateTheClientDefaultCategoryBeatsTheSystemOne() {
    Determination system = det("S", "21", false, "aaa", true, SYSTEM, 0);
    Determination own = det("O", "21", false, "zzz", true, CLIENT, 0);

    Result r = DefaultTaxResolver.rank(Arrays.asList(system, own), null, new BigDecimal("21"));

    assertSame(own, r.chosen());
    assertEquals(DefaultTaxResolver.SOURCE_DEFAULT_CATEGORY, r.source());
    assertEquals(Collections.singletonList("S"), ids(r.candidates()));
  }

  @Test
  public void candidatesAreCappedAtFive() {
    List<Determination> many = new ArrayList<>();
    for (int i = 0; i < 9; i++) {
      many.add(det("T" + i, "21", false, "cat" + i, false, CLIENT, 0));
    }

    Result r = DefaultTaxResolver.rank(many, null, new BigDecimal("21"));

    assertEquals("T0", r.chosen().taxId());
    assertEquals(DefaultTaxResolver.MAX_CANDIDATES, r.candidates().size());
    assertEquals(Arrays.asList("T1", "T2", "T3", "T4", "T5"), ids(r.candidates()));
  }

  @Test
  public void aTaxIsCountedOnceEvenWhenProductAndCategoryAgree() {
    Determination product = det("P", "21", false, "p", false, CLIENT, 0);
    Determination sameTaxOtherCategory = new Determination("P", "tax-P", new BigDecimal("21"),
        false, "cat-other", "other", true, CLIENT, 50);
    Determination another = det("A", "21", false, "a", false, CLIENT, 0);

    Result r = DefaultTaxResolver.rank(Arrays.asList(sameTaxOtherCategory, another), product,
        new BigDecimal("21"));

    assertEquals("P", r.chosen().taxId());
    assertEquals(DefaultTaxResolver.SOURCE_PRODUCT_CATEGORY, r.source());
    assertEquals(Collections.singletonList("A"), ids(r.candidates()));
  }

  /**
   * ETP-5585: c_gettax answers one tax per category — the 21+5.2% surcharge tax of "IVA Normal" —
   * and its sibling, the plain 21%, only enters the pool through fetchTieSiblings. Once there, the
   * default category must beat another category's 21%.
   */
  @Test
  public void aTieSiblingInTheDefaultCategoryBeatsAnotherCategorysSameRate() {
    Determination surcharge = new Determination("RE", "IVA+RE 21+5.2%", new BigDecimal("26.2"),
        true, "cat-normal", "IVA Normal", true, SYSTEM, 10);
    Determination sibling = new Determination("IVA21", "IVA 21%", new BigDecimal("21"), false,
        "cat-normal", "IVA Normal", true, SYSTEM, 10);
    Determination realEstate = new Determination("INM21", "B.Inmuebles 21%", new BigDecimal("21"),
        false, "cat-inm", "IVA Normal B.Inmuebles", false, SYSTEM, 0);

    Result r = DefaultTaxResolver.rank(Arrays.asList(surcharge, realEstate, sibling), null,
        new BigDecimal("21"));

    assertEquals("IVA21", r.chosen().taxId());
    assertEquals(DefaultTaxResolver.SOURCE_DEFAULT_CATEGORY, r.source());
    assertEquals(Collections.singletonList("INM21"), ids(r.candidates()));
  }

  @Test
  public void aSiblingOfTheProductCategoryIsStillTheProductCategory() {
    Determination product = new Determination("P26", "p", new BigDecimal("26.2"), true, "cat-p",
        "prod cat", false, CLIENT, 1);
    Determination sibling = new Determination("P21", "p21", new BigDecimal("21"), false, "cat-p",
        "prod cat", false, CLIENT, 1);
    Determination def = det("D", "21", false, "def", true, CLIENT, 9);

    Result r = DefaultTaxResolver.rank(Arrays.asList(def, sibling), product, new BigDecimal("21"));

    assertEquals("P21", r.chosen().taxId());
    assertEquals(DefaultTaxResolver.SOURCE_PRODUCT_CATEGORY, r.source());
  }

  /**
   * ETP-5585: in "IVA Super Reducido" c_gettax answers a rate-0 summary tax (ValidFrom 2016) and
   * the plain 4% (ValidFrom 2000) only enters as a sibling; it must win for rate 4.
   */
  @Test
  public void aNonSummarySiblingWithAnOlderValidityWinsAtItsRate() {
    Determination answer = new Determination("REAGYP", "Compensacion REAGYP 12%",
        new BigDecimal("0"), true, "cat-sr", "IVA Super Reducido", false, SYSTEM, 0,
        Timestamp.valueOf("2016-01-01 00:00:00"));
    Determination sibling = new Determination("IVA4", "Adquisiciones IVA 4%", new BigDecimal("4"),
        false, "cat-sr", "IVA Super Reducido", false, SYSTEM, 0,
        Timestamp.valueOf("2000-01-01 00:00:00"));
    Determination services = new Determination("SERV4", "Prestacion servicios nacional 4%",
        new BigDecimal("4"), false, "cat-srs", "IVA Super Reducido Servicios", false, SYSTEM, 0,
        Timestamp.valueOf("2001-01-01 00:00:00"));

    Result r = DefaultTaxResolver.rank(Arrays.asList(answer, services, sibling), null,
        new BigDecimal("4"));

    assertEquals("IVA4", r.chosen().taxId());
    assertEquals(DefaultTaxResolver.SOURCE_RATE_MATCH, r.source());
    assertEquals(Collections.singletonList("SERV4"), ids(r.candidates()));
  }

  /** Same category, same rank keys: the most recent ValidFrom wins, as in c_gettax; then the name. */
  @Test
  public void withinOneCategoryTheMostRecentValidityThenTheNameBreaksTies() {
    Determination art20 = new Determination("A20", "Exentas (art 20)", new BigDecimal("0"), false,
        "cat-ex", "Exento", false, SYSTEM, 0, Timestamp.valueOf("2000-01-01 00:00:00"));
    Determination art22 = new Determination("A22", "Exentas (art 22)", new BigDecimal("0"), false,
        "cat-ex", "Exento", false, SYSTEM, 0, Timestamp.valueOf("2001-01-01 00:00:00"));
    Determination bName = new Determination("B", "b tax", new BigDecimal("0"), false, "cat-ex",
        "Exento", false, SYSTEM, 0, Timestamp.valueOf("2001-01-01 00:00:00"));

    Result r = DefaultTaxResolver.rank(Arrays.asList(art20, bName, art22), null,
        BigDecimal.ZERO);

    assertEquals("A22", r.chosen().taxId());
    assertEquals(Arrays.asList("B", "A20"), ids(r.candidates()));
  }
}
