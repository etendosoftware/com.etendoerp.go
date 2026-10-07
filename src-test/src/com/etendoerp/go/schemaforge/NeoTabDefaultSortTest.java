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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.Map;

import org.junit.Test;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.Property;
import org.openbravo.model.ad.ui.Tab;

/**
 * Unit tests for {@link NeoTabDefaultSort} (ETP-5611): a child-tab list with no explicit sort is
 * ordered by the AD tab's {@code HQL_OrderBy_Clause} when — and only when — that clause is a plain
 * list of properties that exist on the entity. Anything else keeps today's behaviour (no sort).
 *
 * @covers com.etendoerp.go.schemaforge.NeoTabDefaultSort
 */
public class NeoTabDefaultSortTest {

  /** gLJournalLine-like entity: lineNo, debit and a many-to-one `account` with a `name`. */
  private static Entity lineEntity() {
    Entity account = mock(Entity.class);
    when(account.hasProperty("name")).thenReturn(true);
    when(account.getProperty("name")).thenReturn(mock(Property.class));

    Property accountProp = mock(Property.class);
    when(accountProp.getTargetEntity()).thenReturn(account);
    Property scalar = mock(Property.class);

    Entity line = mock(Entity.class);
    for (String p : new String[] { "lineNo", "debit", "transactionDate" }) {
      when(line.hasProperty(p)).thenReturn(true);
      when(line.getProperty(p)).thenReturn(scalar);
    }
    when(line.hasProperty("account")).thenReturn(true);
    when(line.getProperty("account")).thenReturn(accountProp);
    return line;
  }

  @Test
  public void plainPropertyBecomesSortBy() {
    assertEquals("lineNo", NeoTabDefaultSort.deriveSortBy("lineNo", lineEntity()));
  }

  @Test
  public void aliasPrefixAndDirectionsAreNormalized() {
    assertEquals("-transactionDate,lineNo",
        NeoTabDefaultSort.deriveSortBy("e.transactionDate DESC, e.lineNo asc", lineEntity()));
  }

  @Test
  public void leadingMinusIsAlreadySortBySyntax() {
    assertEquals("-transactionDate", NeoTabDefaultSort.deriveSortBy("-transactionDate", lineEntity()));
  }

  @Test
  public void propertyPathIsWalkedThroughTheTargetEntity() {
    assertEquals("account.name", NeoTabDefaultSort.deriveSortBy("account.name", lineEntity()));
  }

  @Test
  public void functionClauseIsSkipped() {
    assertNull(NeoTabDefaultSort.deriveSortBy("abs(debit) desc", lineEntity()));
  }

  @Test
  public void unknownPropertySkipsTheWholeClause() {
    // A stale or Classic-only name must not turn a working list into a 500.
    assertNull(NeoTabDefaultSort.deriveSortBy("lineNo, sEQNoAsset", lineEntity()));
  }

  @Test
  public void propertyNamesAreCaseSensitive() {
    // end-year-close declares `Debit DESC` — not a DAL property name.
    assertNull(NeoTabDefaultSort.deriveSortBy("Debit DESC", lineEntity()));
  }

  @Test
  public void foreignAliasIsSkipped() {
    assertNull(NeoTabDefaultSort.deriveSortBy("fa.type DESC, debit DESC", lineEntity()));
  }

  @Test
  public void pathThroughAScalarIsSkipped() {
    assertNull(NeoTabDefaultSort.deriveSortBy("lineNo.name", lineEntity()));
  }

  @Test
  public void minusAndDescTogetherAreSkipped() {
    assertNull(NeoTabDefaultSort.deriveSortBy("-lineNo desc", lineEntity()));
  }

  @Test
  public void blankClauseOrMissingEntityGivesNull() {
    assertNull(NeoTabDefaultSort.deriveSortBy("  ", lineEntity()));
    assertNull(NeoTabDefaultSort.deriveSortBy(null, lineEntity()));
    assertNull(NeoTabDefaultSort.deriveSortBy("lineNo", null));
  }

  // ─── applyIfAbsent ────────────────────────────────────────────────────────

  private static Tab tab(long level, String orderBy) {
    Tab tab = mock(Tab.class);
    when(tab.getTabLevel()).thenReturn(level);
    when(tab.getHqlorderbyclause()).thenReturn(orderBy);
    return tab;
  }

  @Test
  public void childTabWithoutSortGetsTheTabOrder() {
    Map<String, String> params = new HashMap<>();
    NeoTabDefaultSort.applyIfAbsent(params, tab(1L, "lineNo"), lineEntity());
    assertEquals("lineNo", params.get("_sortBy"));
  }

  @Test
  public void explicitSortByWins() {
    Map<String, String> params = new HashMap<>();
    params.put("_sortBy", "-debit");
    NeoTabDefaultSort.applyIfAbsent(params, tab(1L, "lineNo"), lineEntity());
    assertEquals("-debit", params.get("_sortBy"));
  }

  @Test
  public void explicitOrderByWins() {
    Map<String, String> params = new HashMap<>();
    params.put("_orderBy", "debit");
    NeoTabDefaultSort.applyIfAbsent(params, tab(1L, "lineNo"), lineEntity());
    assertFalse(params.containsKey("_sortBy"));
  }

  @Test
  public void summaryRequestIsLeftAlone() {
    // An aggregate (_summary) query with an `order by` on a non-aggregated column fails in SQL.
    Map<String, String> params = new HashMap<>();
    params.put("_summary", "{\"debit\":\"sum\"}");
    NeoTabDefaultSort.applyIfAbsent(params, tab(1L, "lineNo"), lineEntity());
    assertFalse(params.containsKey("_sortBy"));
  }

  @Test
  public void emptyPathSegmentIsSkipped() {
    assertNull(NeoTabDefaultSort.deriveSortBy("account..name", lineEntity()));
    assertNull(NeoTabDefaultSort.deriveSortBy("lineNo.", lineEntity()));
  }

  @Test
  public void headerTabIsLeftAlone() {
    Map<String, String> params = new HashMap<>();
    NeoTabDefaultSort.applyIfAbsent(params, tab(0L, "lineNo"), lineEntity());
    assertFalse(params.containsKey("_sortBy"));
  }

  @Test
  public void invalidClauseAddsNothing() {
    Map<String, String> params = new HashMap<>();
    NeoTabDefaultSort.applyIfAbsent(params, tab(1L, "abs(debit) desc"), lineEntity());
    assertFalse(params.containsKey("_sortBy"));
  }
}
