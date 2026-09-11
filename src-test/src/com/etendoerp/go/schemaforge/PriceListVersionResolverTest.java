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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.hibernate.criterion.Criterion;
import org.hibernate.criterion.Order;
import org.hibernate.criterion.SimpleExpression;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.enterprise.Organization;
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

  // ── resolveDefaultVersionId (ETP-5245) ────────────────────────────────────
  //
  // These tests drive a small in-memory stand-in for the M_PriceList_Version ⋈ M_PriceList join
  // (see fakeCriteria/select below) rather than feeding pre-canned results to consecutive
  // createCriteria() calls. That way they assert the resolver's SEMANTICS — which tariff wins —
  // instead of how many queries it happens to run to get there, so the "flagged default beats a
  // newer unflagged list" guarantee survives a refactor of the query strategy.

  private static final String CLIENT_ID = "client-1";
  private static final String CONTEXT_ORG = "org-ctx";
  private static final String SHARED_ORG = "0";

  /** One M_PriceList_Version row joined to its parent M_PriceList. */
  private record VersionRow(String versionId, String orgId, boolean sales, boolean flaggedDefault,
      boolean active, long validFrom) {
  }

  private static VersionRow row(String versionId, String orgId, boolean sales,
      boolean flaggedDefault, long validFrom) {
    return new VersionRow(versionId, orgId, sales, flaggedDefault, true, validFrom);
  }

  private static OBContext contextWith(String clientId, String orgId) {
    OBContext obContext = mock(OBContext.class);
    if (clientId != null) {
      Client client = mock(Client.class);
      when(client.getId()).thenReturn(clientId);
      when(obContext.getCurrentClient()).thenReturn(client);
    }
    if (orgId != null) {
      Organization organization = mock(Organization.class);
      when(organization.getId()).thenReturn(orgId);
      when(obContext.getCurrentOrganization()).thenReturn(organization);
    }
    return obContext;
  }

  /** Makes every createCriteria(PriceListVersion.class) query the given in-memory catalog. */
  private static void stubTenantCatalog(OBDal dal, List<VersionRow> rows) {
    when(dal.createCriteria(PriceListVersion.class)).thenAnswer(invocation -> fakeCriteria(rows));
  }

  /**
   * A criteria mock that records its {@code Restrictions.eq} calls and answers {@code list()} by
   * filtering the in-memory catalog with them, honouring {@code addOrder}/{@code setMaxResults}.
   */
  @SuppressWarnings("unchecked")
  private static OBCriteria<PriceListVersion> fakeCriteria(List<VersionRow> rows) {
    OBCriteria<PriceListVersion> crit = mock(OBCriteria.class);
    Map<String, Object> restrictions = new HashMap<>();
    int[] maxResults = { Integer.MAX_VALUE };
    when(crit.add(any(Criterion.class))).thenAnswer(invocation -> {
      Criterion criterion = invocation.getArgument(0);
      if (criterion instanceof SimpleExpression) {
        SimpleExpression expression = (SimpleExpression) criterion;
        restrictions.put(expression.getPropertyName(), expression.getValue());
      }
      return crit;
    });
    when(crit.createAlias(anyString(), anyString())).thenReturn(crit);
    when(crit.addOrder(any(Order.class))).thenReturn(crit);
    when(crit.setMaxResults(anyInt())).thenAnswer(invocation -> {
      maxResults[0] = invocation.getArgument(0);
      return crit;
    });
    when(crit.list()).thenAnswer(invocation -> select(rows, restrictions, maxResults[0]));
    return crit;
  }

  private static List<PriceListVersion> select(List<VersionRow> rows,
      Map<String, Object> restrictions, int maxResults) {
    List<VersionRow> matches = new ArrayList<>();
    for (VersionRow candidate : rows) {
      if (!Objects.equals(restrictions.get("client.id"), CLIENT_ID)
          || !Objects.equals(restrictions.get("organization.id"), candidate.orgId())
          || !Objects.equals(restrictions.get("pl.salesPriceList"), candidate.sales())) {
        continue;
      }
      // Only skips inactive rows when the query actually asked for active ones, so dropping the
      // isactive restriction would surface as a failing test rather than as silently equal output.
      boolean asksActive = Boolean.TRUE.equals(restrictions.get("active"))
          || Boolean.TRUE.equals(restrictions.get("pl.active"));
      if (asksActive && !candidate.active()) {
        continue;
      }
      if (Boolean.TRUE.equals(restrictions.get("pl.default")) && !candidate.flaggedDefault()) {
        continue;
      }
      matches.add(candidate);
    }
    matches.sort(Comparator.comparingLong(VersionRow::validFrom).reversed());
    List<PriceListVersion> result = new ArrayList<>();
    for (VersionRow match : matches.subList(0, Math.min(matches.size(), Math.max(maxResults, 0)))) {
      PriceListVersion version = mock(PriceListVersion.class);
      when(version.getId()).thenReturn(match.versionId());
      result.add(version);
    }
    return result;
  }

  /**
   * Runs {@code resolveDefaultVersionId} against the given catalog with OBDal/OBContext statics
   * mocked, and hands the OBDal mock plus the static mock to the caller for extra verification.
   */
  private static void withCatalog(List<VersionRow> rows, CatalogScenario scenario) {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
        MockedStatic<OBContext> obContextMock = Mockito.mockStatic(OBContext.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      stubTenantCatalog(dal, rows);
      scenario.run(dal, obContextMock);
    }
  }

  @FunctionalInterface
  private interface CatalogScenario {
    void run(OBDal dal, MockedStatic<OBContext> obContextMock);
  }

  /**
   * Verifies that a null context resolves to null without touching the database.
   */
  @Test
  public void testResolveDefaultVersionIdReturnsNullForNullContext() {
    assertNull(PriceListVersionResolver.resolveDefaultVersionId(null, true));
  }

  /**
   * Verifies that a context without a current client resolves to null (nothing to scope to).
   */
  @Test
  public void testResolveDefaultVersionIdReturnsNullWhenContextHasNoClient() {
    assertNull(PriceListVersionResolver.resolveDefaultVersionId(mock(OBContext.class), true));
  }

  /**
   * THE regression this feature exists for: a price list the tenant explicitly flagged as default
   * must win over a NEWER unflagged one in the same organisation. Before ETP-5245 the resolver
   * ordered by validFromDate alone, so ticking "default" in the UI had no effect.
   */
  @Test
  public void testResolveDefaultVersionIdPrefersFlaggedDefaultOverNewerUnflaggedInSameOrg() {
    withCatalog(Arrays.asList(
        row("v-newer-unflagged", CONTEXT_ORG, true, false, 2_000L),
        row("v-flagged-default", CONTEXT_ORG, true, true, 1_000L)),
        (dal, statics) -> assertEquals("v-flagged-default",
            PriceListVersionResolver.resolveDefaultVersionId(
                contextWith(CLIENT_ID, CONTEXT_ORG), true)));
  }

  /**
   * Verifies that the explicit flag outranks organisation proximity too: a default list in the
   * shared organisation '0' beats a non-default one in the context's own organisation.
   */
  @Test
  public void testResolveDefaultVersionIdPrefersSharedOrgDefaultOverContextOrgNonDefault() {
    withCatalog(Arrays.asList(
        row("v-ctx-unflagged", CONTEXT_ORG, true, false, 9_000L),
        row("v-shared-default", SHARED_ORG, true, true, 1_000L)),
        (dal, statics) -> assertEquals("v-shared-default",
            PriceListVersionResolver.resolveDefaultVersionId(
                contextWith(CLIENT_ID, CONTEXT_ORG), true)));
  }

  /**
   * Verifies that organisation precedence still decides BETWEEN two flagged defaults: the
   * context's own organisation wins over the shared one, even when the shared one is newer.
   */
  @Test
  public void testResolveDefaultVersionIdPrefersContextOrgWhenBothOrgsFlagADefault() {
    withCatalog(Arrays.asList(
        row("v-ctx-default", CONTEXT_ORG, true, true, 1_000L),
        row("v-shared-default", SHARED_ORG, true, true, 9_000L)),
        (dal, statics) -> assertEquals("v-ctx-default",
            PriceListVersionResolver.resolveDefaultVersionId(
                contextWith(CLIENT_ID, CONTEXT_ORG), true)));
  }

  /**
   * Verifies the legacy path is intact: a tenant that never ticked "default" on any price list
   * keeps getting the most recent validFromDate, exactly as before ETP-5245.
   */
  @Test
  public void testResolveDefaultVersionIdFallsBackToNewestWhenNothingIsFlagged() {
    withCatalog(Arrays.asList(
        row("v-old", CONTEXT_ORG, true, false, 1_000L),
        row("v-newest", CONTEXT_ORG, true, false, 3_000L),
        row("v-middle", CONTEXT_ORG, true, false, 2_000L)),
        (dal, statics) -> assertEquals("v-newest",
            PriceListVersionResolver.resolveDefaultVersionId(
                contextWith(CLIENT_ID, CONTEXT_ORG), true)));
  }

  /**
   * Verifies that the unflagged fallback still honours organisation precedence: the context's own
   * organisation wins over a newer shared-organisation list.
   */
  @Test
  public void testResolveDefaultVersionIdFallbackStillPrefersContextOrgOverSharedOrg() {
    withCatalog(Arrays.asList(
        row("v-ctx", CONTEXT_ORG, true, false, 1_000L),
        row("v-shared-newer", SHARED_ORG, true, false, 9_000L)),
        (dal, statics) -> assertEquals("v-ctx",
            PriceListVersionResolver.resolveDefaultVersionId(
                contextWith(CLIENT_ID, CONTEXT_ORG), true)));
  }

  /**
   * Verifies that the purchase direction resolves the purchase tariff, never the sales one.
   */
  @Test
  public void testResolveDefaultVersionIdResolvesThePurchaseTariffWhenSalesIsFalse() {
    withCatalog(Arrays.asList(
        row("v-sales-default", CONTEXT_ORG, true, true, 5_000L),
        row("v-purchase-default", CONTEXT_ORG, false, true, 1_000L)),
        (dal, statics) -> assertEquals("v-purchase-default",
            PriceListVersionResolver.resolveDefaultVersionId(
                contextWith(CLIENT_ID, CONTEXT_ORG), false)));
  }

  /**
   * Verifies that a purchase lookup never falls back to a sales list when the tenant has no
   * purchase tariff — a sales price landing on a purchase document (or vice versa) would be far
   * worse than no price at all.
   */
  @Test
  public void testResolveDefaultVersionIdReturnsNullRatherThanCrossingDirections() {
    withCatalog(Collections.singletonList(row("v-sales-only", CONTEXT_ORG, true, true, 1_000L)),
        (dal, statics) -> assertNull(PriceListVersionResolver.resolveDefaultVersionId(
            contextWith(CLIENT_ID, CONTEXT_ORG), false)));
  }

  /**
   * Verifies that an inactive price list version is never resolved, even when it is the one
   * flagged as default.
   */
  @Test
  public void testResolveDefaultVersionIdIgnoresInactiveVersions() {
    withCatalog(Arrays.asList(
        new VersionRow("v-inactive-default", CONTEXT_ORG, true, true, false, 9_000L),
        row("v-active-unflagged", CONTEXT_ORG, true, false, 1_000L)),
        (dal, statics) -> assertEquals("v-active-unflagged",
            PriceListVersionResolver.resolveDefaultVersionId(
                contextWith(CLIENT_ID, CONTEXT_ORG), true)));
  }

  /**
   * Verifies that a tenant with no matching price list at all resolves to null instead of
   * throwing — callers treat null as "seed no price".
   */
  @Test
  public void testResolveDefaultVersionIdReturnsNullWhenTenantHasNoPriceList() {
    withCatalog(Collections.emptyList(),
        (dal, statics) -> assertNull(PriceListVersionResolver.resolveDefaultVersionId(
            contextWith(CLIENT_ID, CONTEXT_ORG), true)));
  }

  /**
   * Verifies that a context without a current organisation falls back to the shared organisation
   * '0' rather than resolving nothing.
   */
  @Test
  public void testResolveDefaultVersionIdFallsBackToSharedOrgWhenContextHasNoOrganization() {
    withCatalog(Collections.singletonList(row("v-shared", SHARED_ORG, true, true, 1_000L)),
        (dal, statics) -> assertEquals("v-shared",
            PriceListVersionResolver.resolveDefaultVersionId(contextWith(CLIENT_ID, null), true)));
  }

  /**
   * Verifies that a context already scoped to the shared organisation does not query it twice per
   * pass (two passes, one organisation each = two queries, not four).
   */
  @Test
  public void testResolveDefaultVersionIdDoesNotQuerySharedOrgTwiceWhenContextIsShared() {
    withCatalog(Collections.emptyList(), (dal, statics) -> {
      assertNull(PriceListVersionResolver.resolveDefaultVersionId(
          contextWith(CLIENT_ID, SHARED_ORG), true));
      verify(dal, times(2)).createCriteria(PriceListVersion.class);
    });
  }

  /**
   * Verifies both passes and both organisations are tried before giving up (2 × 2 = 4 queries).
   */
  @Test
  public void testResolveDefaultVersionIdTriesBothPassesAndBothOrganisations() {
    withCatalog(Collections.emptyList(), (dal, statics) -> {
      assertNull(PriceListVersionResolver.resolveDefaultVersionId(
          contextWith(CLIENT_ID, CONTEXT_ORG), true));
      verify(dal, times(4)).createCriteria(PriceListVersion.class);
    });
  }

  /**
   * Verifies that a failing query is swallowed (logged, null returned) and that admin mode is
   * still restored — the callers are best-effort and must not turn this into a request failure.
   */
  @Test
  public void testResolveDefaultVersionIdReturnsNullAndRestoresAdminModeWhenQueryFails() {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
        MockedStatic<OBContext> obContextMock = Mockito.mockStatic(OBContext.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.createCriteria(PriceListVersion.class))
          .thenThrow(new IllegalStateException("session closed"));

      assertNull(PriceListVersionResolver.resolveDefaultVersionId(
          contextWith(CLIENT_ID, CONTEXT_ORG), true));

      obContextMock.verify(OBContext::setAdminMode);
      obContextMock.verify(OBContext::restorePreviousMode);
    }
  }
}
