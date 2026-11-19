/*
 *************************************************************************
 * The contents of this file are subject to the Etendo License
 * (the "License"), you may not use this file except in compliance with
 * the License.
 * You may obtain a copy of the License at
 * https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * Software distributed under the License is distributed on an
 * "AS IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
 * implied. See the License for the specific language governing rights
 * and limitations under the License.
 * All portions are Copyright © 2021–2025 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 *************************************************************************
 */
package com.etendoerp.go.schemaforge.webhooks;

import static com.etendoerp.go.schemaforge.TestConstants.COUNT;
import static com.etendoerp.go.schemaforge.TestConstants.ERROR;
import static com.etendoerp.go.schemaforge.TestConstants.RESULT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Stream;

import org.codehaus.jettison.json.JSONArray;
import org.hibernate.criterion.Criterion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.model.ad.ui.Process;
import org.openbravo.model.ad.ui.Window;

import com.etendoerp.webhookevents.services.BaseWebhookService;

/**
 * Unit tests for {@link SFListWindows} and {@link SFListProcesses}, two copies of the same
 * webhook over a different AD entity: listing with/without search filter, max results, ordering,
 * exception handling, and edge cases. Every test runs on both webhooks.
 *
 * @covers com.etendoerp.go.schemaforge.webhooks.SFListWindows
 * @covers com.etendoerp.go.schemaforge.webhooks.SFListProcesses
 */
@MockitoSettings(strictness = Strictness.LENIENT)
class SFListWindowsAndProcessesTest extends BaseWebhookTest {

  /**
   * One listing webhook under test.
   *
   * @param webhook  builds the webhook
   * @param entity   the AD entity it lists
   * @param nameProperty the entity's name property, which the list is ordered by
   */
  record Listing(Supplier<BaseWebhookService> webhook, Class<? extends BaseOBObject> entity,
      String nameProperty) {
    @Override
    public String toString() {
      return entity.getSimpleName();
    }
  }

  static Stream<Listing> listings() {
    return Stream.of(
        new Listing(SFListWindows::new, Window.class, Window.PROPERTY_NAME),
        new Listing(SFListProcesses::new, Process.class, Process.PROPERTY_NAME));
  }

  // ── helpers ──────────────────────────────────────────────────────────

  @SuppressWarnings({ "unchecked", "rawtypes" })
  private OBCriteria<BaseOBObject> criteria(Listing listing) {
    return (OBCriteria) mockCriteria((Class) listing.entity());
  }

  /** A row of the listed entity with the given id and name. */
  private static BaseOBObject row(Listing listing, String id, String name) {
    if (listing.entity() == Window.class) {
      Window w = mock(Window.class);
      when(w.getId()).thenReturn(id);
      when(w.getName()).thenReturn(name);
      return w;
    }
    Process p = mock(Process.class);
    when(p.getId()).thenReturn(id);
    when(p.getName()).thenReturn(name);
    return p;
  }

  // ── happy paths ─────────────────────────────────────────────────────

  /** Verifies listing all records returns correct JSON with count. */
  @ParameterizedTest(name = "{0}")
  @MethodSource("listings")
  @DisplayName("List all returns correct JSON")
  void testListAll(Listing listing) throws Exception {
    OBCriteria<BaseOBObject> crit = criteria(listing);
    List<BaseOBObject> rows = Arrays.asList(row(listing, "id1", "Sales Order"),
        row(listing, "id2", "Purchase Invoice"));
    when(crit.list()).thenReturn(rows);

    listing.webhook().get().get(parameters, responseVars);

    assertNull(responseVars.get(ERROR));
    assertEquals("2", responseVars.get(COUNT));

    JSONArray arr = new JSONArray(responseVars.get(RESULT));
    assertEquals(2, arr.length());
    assertEquals("id1", arr.getJSONObject(0).getString("id"));
    assertEquals("Sales Order", arr.getJSONObject(0).getString("name"));
    assertEquals("id2", arr.getJSONObject(1).getString("id"));
    assertEquals("Purchase Invoice", arr.getJSONObject(1).getString("name"));
  }

  /** Verifies search filter adds ilike restriction. */
  @ParameterizedTest(name = "{0}")
  @MethodSource("listings")
  @DisplayName("Search query adds ilike restriction")
  void testListWithSearchQuery(Listing listing) throws Exception {
    OBCriteria<BaseOBObject> crit = criteria(listing);
    parameters.put("q", "sales");
    List<BaseOBObject> rows = List.of(row(listing, "id3", "Sales Order"));
    when(crit.list()).thenReturn(rows);

    listing.webhook().get().get(parameters, responseVars);

    assertNull(responseVars.get(ERROR));
    assertEquals("1", responseVars.get(COUNT));
    // Two add() calls: active + ilike
    verify(crit, times(2)).add(any(Criterion.class));
  }

  // ── empty result ────────────────────────────────────────────────────

  /** Verifies empty result returns count=0 and empty array. */
  @ParameterizedTest(name = "{0}")
  @MethodSource("listings")
  @DisplayName("Empty result returns count=0")
  void testListEmptyResult(Listing listing) throws Exception {
    when(criteria(listing).list()).thenReturn(new ArrayList<>());

    listing.webhook().get().get(parameters, responseVars);

    assertNull(responseVars.get(ERROR));
    assertEquals("0", responseVars.get(COUNT));
    assertEquals(0, new JSONArray(responseVars.get(RESULT)).length());
  }

  // ── blank query ignored ─────────────────────────────────────────────

  /** Verifies whitespace-only query is ignored (only active filter applied). */
  @ParameterizedTest(name = "{0}")
  @MethodSource("listings")
  @DisplayName("Whitespace query is ignored")
  void testListEmptyQueryIgnored(Listing listing) throws Exception {
    OBCriteria<BaseOBObject> crit = criteria(listing);
    parameters.put("q", "  ");
    when(crit.list()).thenReturn(new ArrayList<>());

    listing.webhook().get().get(parameters, responseVars);

    assertNull(responseVars.get(ERROR));
    verify(crit, times(1)).add(any(Criterion.class));
  }

  /** Verifies null q param uses list-all mode: only the active filter is applied. */
  @ParameterizedTest(name = "{0}")
  @MethodSource("listings")
  @DisplayName("Null q param lists all")
  void testNullQueryListsAll(Listing listing) throws Exception {
    OBCriteria<BaseOBObject> crit = criteria(listing);
    when(crit.list()).thenReturn(new ArrayList<>());

    listing.webhook().get().get(parameters, responseVars);

    assertNull(responseVars.get(ERROR));
    verify(crit, times(1)).add(any(Criterion.class));
  }

  // ── criteria configuration ──────────────────────────────────────────

  /** Verifies max results is set to 100. */
  @ParameterizedTest(name = "{0}")
  @MethodSource("listings")
  @DisplayName("Max results set to 100")
  void testMaxResults(Listing listing) throws Exception {
    OBCriteria<BaseOBObject> crit = criteria(listing);
    when(crit.list()).thenReturn(new ArrayList<>());

    listing.webhook().get().get(parameters, responseVars);

    verify(crit).setMaxResults(100);
  }

  /** Verifies order by name ascending. */
  @ParameterizedTest(name = "{0}")
  @MethodSource("listings")
  @DisplayName("Ordered by name ascending")
  void testOrderByName(Listing listing) throws Exception {
    OBCriteria<BaseOBObject> crit = criteria(listing);
    when(crit.list()).thenReturn(new ArrayList<>());

    listing.webhook().get().get(parameters, responseVars);

    verify(crit).addOrderBy(listing.nameProperty(), true);
  }

  // ── exception handling ──────────────────────────────────────────────

  /** Verifies exception is caught and error set. */
  @ParameterizedTest(name = "{0}")
  @MethodSource("listings")
  @DisplayName("Exception sets error in response")
  void testExceptionSetsError(Listing listing) {
    when(criteria(listing).list()).thenThrow(new RuntimeException("Connection lost"));

    listing.webhook().get().get(parameters, responseVars);

    assertEquals("Connection lost", responseVars.get(ERROR));
    assertNull(responseVars.get(COUNT));
    assertNull(responseVars.get(RESULT));
  }

  // ── edge cases ──────────────────────────────────────────────────────

  /** Verifies single result is returned correctly. */
  @ParameterizedTest(name = "{0}")
  @MethodSource("listings")
  @DisplayName("Single result is correct")
  void testSingleResult(Listing listing) throws Exception {
    List<BaseOBObject> rows = List.of(row(listing, "id99", "Only One"));
    when(criteria(listing).list()).thenReturn(rows);

    listing.webhook().get().get(parameters, responseVars);

    assertNull(responseVars.get(ERROR));
    assertEquals("1", responseVars.get(COUNT));
    JSONArray arr = new JSONArray(responseVars.get(RESULT));
    assertEquals(1, arr.length());
    assertEquals("id99", arr.getJSONObject(0).getString("id"));
  }

  /** Verifies input parameters map is not mutated. */
  @ParameterizedTest(name = "{0}")
  @MethodSource("listings")
  @DisplayName("Input params not mutated")
  void testInputParamsNotMutated(Listing listing) {
    when(criteria(listing).list()).thenReturn(new ArrayList<>());

    Map<String, String> originalParams = new HashMap<>(parameters);

    listing.webhook().get().get(parameters, responseVars);

    assertEquals(originalParams, parameters);
  }

  /** Verifies special characters in search query do not cause errors. */
  @ParameterizedTest(name = "{0}")
  @MethodSource("listings")
  @DisplayName("Special characters in query accepted")
  void testSpecialCharactersInQuery(Listing listing) throws Exception {
    parameters.put("q", "test<>&\"'");
    when(criteria(listing).list()).thenReturn(new ArrayList<>());

    listing.webhook().get().get(parameters, responseVars);

    assertNull(responseVars.get(ERROR));
  }

  /** Verifies a record with special characters in its name. */
  @ParameterizedTest(name = "{0}")
  @MethodSource("listings")
  @DisplayName("Record with special chars in name")
  void testRecordWithSpecialCharsInName(Listing listing) throws Exception {
    List<BaseOBObject> rows = List.of(row(listing, "w1", "Order <Daily> & \"Monthly\""));
    when(criteria(listing).list()).thenReturn(rows);

    listing.webhook().get().get(parameters, responseVars);

    assertNull(responseVars.get(ERROR));
    JSONArray arr = new JSONArray(responseVars.get(RESULT));
    assertEquals("Order <Daily> & \"Monthly\"", arr.getJSONObject(0).getString("name"));
  }
}
