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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Collections;
import java.util.Map;

import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.pricing.pricelist.PriceList;
import org.openbravo.model.pricing.pricelist.PriceListVersion;

/**
 * Unit tests for {@link PriceListVersionResolver}.
 *
 * <p>Tests are split into three groups:
 * <ul>
 *   <li><strong>findSingleVersion</strong> – null guard, single result, multiple results (warning path)</li>
 *   <li><strong>findSingleVersionId</strong> – id extraction convenience overload</li>
 *   <li><strong>findSingleVersionIds</strong> – batch resolution for list GET responses</li>
 * </ul>
 */
public class PriceListVersionResolverTest {

  // ── helpers ───────────────────────────────────────────────────────────────

  /**
   * Wires OBDal mock and a criteria mock that returns the given versions list.
   * Caller must be inside a {@code try (MockedStatic<OBDal> ...)} block.
   */
  @SuppressWarnings("unchecked")
  private static OBCriteria<PriceListVersion> stubCriteria(OBDal dal,
      java.util.List<PriceListVersion> versions) {
    OBCriteria<PriceListVersion> crit = mock(OBCriteria.class);
    when(dal.createCriteria(PriceListVersion.class)).thenReturn(crit);
    when(crit.list()).thenReturn(versions);
    return crit;
  }

  // ── findSingleVersion ─────────────────────────────────────────────────────

  /**
   * Verifies that findSingleVersion returns null immediately for a null price list,
   * without touching OBDal.
   */
  @Test
  public void testFindSingleVersionReturnsNullForNullPriceList() {
    assertNull(PriceListVersionResolver.findSingleVersion(null));
  }

  /**
   * Verifies that findSingleVersion returns null when the price list has no versions.
   */
  @Test
  public void testFindSingleVersionReturnsNullWhenNoVersionsExist() {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      stubCriteria(dal, Collections.emptyList());

      assertNull(PriceListVersionResolver.findSingleVersion(mock(PriceList.class)));
    }
  }

  /**
   * Verifies that findSingleVersion returns the single existing version.
   */
  @Test
  public void testFindSingleVersionReturnsSingleVersion() {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      PriceListVersion version = mock(PriceListVersion.class);
      stubCriteria(dal, Collections.singletonList(version));

      assertSame(version, PriceListVersionResolver.findSingleVersion(mock(PriceList.class)));
    }
  }

  /**
   * Verifies that findSingleVersion returns the first version without throwing when multiple
   * versions are found (broken-invariant warning path).
   */
  @Test
  public void testFindSingleVersionReturnsFirstWhenMultipleVersionsExist() {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      PriceListVersion v1 = mock(PriceListVersion.class);
      PriceListVersion v2 = mock(PriceListVersion.class);
      stubCriteria(dal, Arrays.asList(v1, v2));

      PriceList pl = mock(PriceList.class);
      when(pl.getName()).thenReturn("Test PL");

      assertSame(v1, PriceListVersionResolver.findSingleVersion(pl));
    }
  }

  // ── findSingleVersionId ───────────────────────────────────────────────────

  /**
   * Verifies that findSingleVersionId returns null for a null price list.
   */
  @Test
  public void testFindSingleVersionIdReturnsNullForNullPriceList() {
    assertNull(PriceListVersionResolver.findSingleVersionId(null));
  }

  /**
   * Verifies that findSingleVersionId returns null when no version exists.
   */
  @Test
  public void testFindSingleVersionIdReturnsNullWhenNoVersionExists() {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      stubCriteria(dal, Collections.emptyList());

      assertNull(PriceListVersionResolver.findSingleVersionId(mock(PriceList.class)));
    }
  }

  /**
   * Verifies that findSingleVersionId returns the id of the single version.
   */
  @Test
  public void testFindSingleVersionIdReturnsIdWhenVersionExists() {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      PriceListVersion version = mock(PriceListVersion.class);
      when(version.getId()).thenReturn("v-abc");
      stubCriteria(dal, Collections.singletonList(version));

      assertEquals("v-abc", PriceListVersionResolver.findSingleVersionId(mock(PriceList.class)));
    }
  }

  // ── findSingleVersionIds (batch) ──────────────────────────────────────────

  /**
   * Verifies that findSingleVersionIds returns an empty map for a null input.
   */
  @Test
  public void testFindSingleVersionIdsReturnsEmptyMapForNullInput() {
    Map<String, String> result = PriceListVersionResolver.findSingleVersionIds(null);
    assertNotNull(result);
    assertTrue(result.isEmpty());
  }

  /**
   * Verifies that findSingleVersionIds returns an empty map for an empty list.
   */
  @Test
  public void testFindSingleVersionIdsReturnsEmptyMapForEmptyList() {
    Map<String, String> result = PriceListVersionResolver.findSingleVersionIds(Collections.emptyList());
    assertNotNull(result);
    assertTrue(result.isEmpty());
  }

  /**
   * Verifies that findSingleVersionIds correctly maps each price list id to its version id.
   */
  @Test
  public void testFindSingleVersionIdsBatchResolvesMultipleIds() {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);

      PriceList pl1 = mock(PriceList.class);
      when(pl1.getId()).thenReturn("pl-1");
      PriceListVersion v1 = mock(PriceListVersion.class);
      when(v1.getPriceList()).thenReturn(pl1);
      when(v1.getId()).thenReturn("v-1");

      PriceList pl2 = mock(PriceList.class);
      when(pl2.getId()).thenReturn("pl-2");
      PriceListVersion v2 = mock(PriceListVersion.class);
      when(v2.getPriceList()).thenReturn(pl2);
      when(v2.getId()).thenReturn("v-2");

      stubCriteria(dal, Arrays.asList(v1, v2));

      Map<String, String> result = PriceListVersionResolver.findSingleVersionIds(
          Arrays.asList("pl-1", "pl-2"));

      assertEquals(2, result.size());
      assertEquals("v-1", result.get("pl-1"));
      assertEquals("v-2", result.get("pl-2"));
    }
  }

  /**
   * Verifies that findSingleVersionIds keeps only the first version id when a price list
   * unexpectedly has multiple versions (putIfAbsent semantics).
   */
  @Test
  public void testFindSingleVersionIdsKeepsFirstVersionWhenDuplicatesFound() {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);

      PriceList pl = mock(PriceList.class);
      when(pl.getId()).thenReturn("pl-1");

      PriceListVersion first = mock(PriceListVersion.class);
      when(first.getPriceList()).thenReturn(pl);
      when(first.getId()).thenReturn("v-first");

      PriceListVersion second = mock(PriceListVersion.class);
      when(second.getPriceList()).thenReturn(pl);
      when(second.getId()).thenReturn("v-second");

      stubCriteria(dal, Arrays.asList(first, second));

      Map<String, String> result = PriceListVersionResolver.findSingleVersionIds(
          Collections.singletonList("pl-1"));

      assertEquals(1, result.size());
      assertEquals("v-first", result.get("pl-1"));
    }
  }
}
