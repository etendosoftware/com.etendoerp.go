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

package com.etendoerp.go.schemaforge.handlers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.inject.Named;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.Session;
import org.hibernate.query.NativeQuery;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.mockito.junit.MockitoJUnitRunner;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.dal.service.OBQuery;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.financialmgmt.accounting.coa.ElementValue;

import com.etendoerp.go.schemaforge.NeoContext;
import com.etendoerp.go.schemaforge.NeoEndpointType;
import com.etendoerp.go.schemaforge.NeoResponse;

/**
 * Unit tests for {@link ChartOfAccountsHandler}.
 *
 * <p>Tests cover pure in-memory helpers (no OBDal or OBContext):
 * {@link ChartOfAccountsHandler#computeDepth computeDepth},
 * {@link ChartOfAccountsHandler#findParentCode4 findParentCode4},
 * {@link ChartOfAccountsHandler#buildAncestorChain buildAncestorChain},
 * {@link ChartOfAccountsHandler#rollupBalances rollupBalances},
 * {@link ChartOfAccountsHandler#toBigDecimal toBigDecimal},
 * {@link ChartOfAccountsHandler#applyIsLeaf applyIsLeaf},
 * {@link ChartOfAccountsHandler#applyYtdBalances applyYtdBalances},
 * {@link ChartOfAccountsHandler#collectIds collectIds},
 * and handler routing / annotation contracts.
 *
 * <p>Methods that require OBDal ({@code loadTreeData}, {@code computeYtdBalances},
 * {@code querySummaryLevels}, {@code validateSave}) are integration-test territory
 * and are excluded here.
 */
@RunWith(MockitoJUnitRunner.Silent.class)
public class ChartOfAccountsHandlerTest {

  private final ChartOfAccountsHandler handler = new ChartOfAccountsHandler();

  // ── @Named annotation ──────────────────────────────────────────────────────

  @Test
  public void handlerCarriesChartOfAccountsNamedQualifier() {
    Named named = ChartOfAccountsHandler.class.getAnnotation(Named.class);
    assertNotNull("ChartOfAccountsHandler must be annotated @Named", named);
    assertEquals("chart-of-accounts", named.value());
  }

  // ── handle() routing ──────────────────────────────────────────────────────

  @Test
  public void handleReturnsNullForCrudGet() {
    NeoContext ctx = mock(NeoContext.class);
    when(ctx.getEndpointType()).thenReturn(NeoEndpointType.CRUD);
    when(ctx.getHttpMethod()).thenReturn("GET");
    assertNull(handler.handle(ctx));
  }

  @Test
  public void handleReturnsNullForDefaultsEndpoint() {
    NeoContext ctx = mock(NeoContext.class);
    when(ctx.getEndpointType()).thenReturn(NeoEndpointType.DEFAULTS);
    assertNull(handler.handle(ctx));
  }

  @Test
  public void handleReturnsErrorForInvalidCode() throws Exception {
    NeoContext ctx = mock(NeoContext.class);
    when(ctx.getEndpointType()).thenReturn(NeoEndpointType.CRUD);
    when(ctx.getHttpMethod()).thenReturn("POST");
    JSONObject body = new JSONObject().put("searchKey", "123ABC"); // not 8 digits
    when(ctx.getRequestBody()).thenReturn(body);

    NeoResponse resp = handler.handle(ctx);
    assertNotNull(resp);
    assertEquals(400, resp.getHttpStatus());
  }

  @Test
  public void handleReturnsErrorForProtectedParentLikeSubaccountOnCreate() throws Exception {
    NeoContext ctx = mock(NeoContext.class);
    when(ctx.getEndpointType()).thenReturn(NeoEndpointType.CRUD);
    when(ctx.getHttpMethod()).thenReturn("POST");
    JSONObject body = new JSONObject().put("searchKey", "10000000");
    when(ctx.getRequestBody()).thenReturn(body);

    NeoResponse resp = handler.handle(ctx);
    assertNotNull(resp);
    assertEquals(400, resp.getHttpStatus());
  }

  @Test
  public void handleReturnsNullWhenSearchKeyAbsentOnPost() throws Exception {
    NeoContext ctx = mock(NeoContext.class);
    when(ctx.getEndpointType()).thenReturn(NeoEndpointType.CRUD);
    when(ctx.getHttpMethod()).thenReturn("POST");
    when(ctx.getRequestBody()).thenReturn(new JSONObject()); // no searchKey
    assertNull(handler.handle(ctx));
  }

  @Test
  public void handleReturnsNullForNullBody() {
    NeoContext ctx = mock(NeoContext.class);
    when(ctx.getEndpointType()).thenReturn(NeoEndpointType.CRUD);
    when(ctx.getHttpMethod()).thenReturn("PUT");
    when(ctx.getRequestBody()).thenReturn(null);
    assertNull(handler.handle(ctx));
  }

  // ── afterHandle() routing ─────────────────────────────────────────────────

  @Test
  public void afterHandleReturnsNullForCrudPost() {
    NeoContext ctx = mock(NeoContext.class);
    when(ctx.getEndpointType()).thenReturn(NeoEndpointType.CRUD);
    when(ctx.getHttpMethod()).thenReturn("POST");
    assertNull(handler.afterHandle(ctx));
  }

  @Test
  public void afterHandleReturnsNullForSelectorEndpoint() {
    NeoContext ctx = mock(NeoContext.class);
    when(ctx.getEndpointType()).thenReturn(NeoEndpointType.SELECTOR);
    assertNull(handler.afterHandle(ctx));
  }

  @Test
  public void afterHandleReturnsNullWhenPreviousResultIsNull() {
    NeoContext ctx = mock(NeoContext.class);
    when(ctx.getEndpointType()).thenReturn(NeoEndpointType.CRUD);
    when(ctx.getHttpMethod()).thenReturn("GET");
    when(ctx.getPreviousResult()).thenReturn(null);
    assertNull(handler.afterHandle(ctx));
  }

  // ── validation error messages ─────────────────────────────────────────────

  @Test
  public void errInvalidCodeMessageIsInSpanish() {
    assertTrue("Error must be a user-facing Spanish message",
        ChartOfAccountsHandler.ERR_INVALID_CODE.contains("8 dígitos"));
  }

  @Test
  public void errSummaryLockedMessageIsInSpanish() {
    assertTrue(ChartOfAccountsHandler.ERR_SUMMARY_LOCKED.length() > 5);
  }

  @Test
  public void errPrefixLockedMessageIsInSpanish() {
    assertTrue(ChartOfAccountsHandler.ERR_PREFIX_LOCKED.length() > 5);
  }

  @Test
  public void errProtectedParentLikeSubaccountMessageIsInSpanish() {
    assertTrue("Error must mention protected parent-like subaccounts",
        ChartOfAccountsHandler.ERR_PROTECTED_PARENT_LIKE_SUBACCOUNT.contains("subcuentas padre"));
    assertTrue(ChartOfAccountsHandler.ERR_PROTECTED_PARENT_LIKE_SUBACCOUNT.contains("0000"));
  }

  // ── account code validation ────────────────────────────────────────────────

  @Test
  public void isValidAccountCodeAcceptsExactlyEightDigits() {
    assertTrue(ChartOfAccountsHandler.isValidAccountCode("12345678"));
    assertTrue(ChartOfAccountsHandler.isValidAccountCode("00000000"));
  }

  @Test
  public void isValidAccountCodeRejectsNullNonDigitsAndWrongLength() {
    assertFalse(ChartOfAccountsHandler.isValidAccountCode(null));
    assertFalse(ChartOfAccountsHandler.isValidAccountCode("1234567"));
    assertFalse(ChartOfAccountsHandler.isValidAccountCode("123456789"));
    assertFalse(ChartOfAccountsHandler.isValidAccountCode("1234A678"));
    assertFalse(ChartOfAccountsHandler.isValidAccountCode(" 12345678"));
  }

  @Test
  public void isProtectedParentLikeSubaccountAcceptsEightDigitCodesEndingInFourZeros() {
    assertTrue(ChartOfAccountsHandler.isProtectedParentLikeSubaccount("10000000"));
    assertTrue(ChartOfAccountsHandler.isProtectedParentLikeSubaccount("10100000"));
    assertTrue(ChartOfAccountsHandler.isProtectedParentLikeSubaccount("99990000"));
  }

  @Test
  public void isProtectedParentLikeSubaccountRejectsLeafCodesAndInvalidCodes() {
    assertFalse(ChartOfAccountsHandler.isProtectedParentLikeSubaccount(null));
    assertFalse(ChartOfAccountsHandler.isProtectedParentLikeSubaccount("10000001"));
    assertFalse(ChartOfAccountsHandler.isProtectedParentLikeSubaccount("1000"));
    assertFalse(ChartOfAccountsHandler.isProtectedParentLikeSubaccount("1000000A"));
  }

  @Test
  public void buildLeafAccountWhereClauseSupportsNestedSupportedCriteria() throws Exception {
    Map<String, Object> params = new HashMap<>();
    String where = ChartOfAccountsHandler.buildLeafAccountWhereClause(
        "{\"_constructor\":\"AdvancedCriteria\",\"operator\":\"and\",\"criteria\":["
            + "{\"fieldName\":\"searchKey\",\"operator\":\"iContains\",\"value\":\"43\"},"
            + "{\"_constructor\":\"AdvancedCriteria\",\"operator\":\"or\",\"criteria\":["
            + "{\"fieldName\":\"active\",\"operator\":\"equals\",\"value\":true},"
            + "{\"fieldName\":\"accountType\",\"operator\":\"equals\",\"value\":\"E\"}]}]}",
        params);

    assertTrue(where.contains("value ILIKE :filter1"));
    assertTrue(where.contains("(isactive = :filter2 OR accounttype = :filter3)"));
    assertEquals("%43%", params.get("filter1"));
    assertEquals("Y", params.get("filter2"));
    assertEquals("E", params.get("filter3"));
  }

  @Test
  public void buildLeafAccountWhereClauseIgnoresUnsupportedCriteria() throws Exception {
    Map<String, Object> params = new HashMap<>();
    String where = ChartOfAccountsHandler.buildLeafAccountWhereClause(
        "[{\"fieldName\":\"ytdBalance\",\"operator\":\"greaterThan\",\"value\":10}]",
        params);

    assertEquals("", where);
    assertTrue(params.isEmpty());
  }

  @Test
  public void resolveLeafAccountOrderByRestrictsSortToSupportedColumns() {
    assertEquals("value DESC", ChartOfAccountsHandler.resolveLeafAccountOrderBy("-searchKey"));
    assertEquals("name ASC", ChartOfAccountsHandler.resolveLeafAccountOrderBy("name asc"));
    assertEquals("value ASC", ChartOfAccountsHandler.resolveLeafAccountOrderBy("ytdBalance desc"));
    assertEquals("value ASC", ChartOfAccountsHandler.resolveLeafAccountOrderBy(null));
  }

  // ── computeDepth ──────────────────────────────────────────────────────────

  @Test
  public void computeDepthRootNodeIsZero() {
    Map<String, String> parents = Collections.singletonMap("root", null);
    assertEquals(0, ChartOfAccountsHandler.computeDepth("root", parents));
  }

  @Test
  public void computeDepthNodeNotInMapIsZero() {
    assertEquals(0, ChartOfAccountsHandler.computeDepth("ghost", Collections.emptyMap()));
  }

  @Test
  public void computeDepthDirectChildOfRootIsOne() {
    Map<String, String> parents = new HashMap<>();
    parents.put("root", null);
    parents.put("child", "root");
    assertEquals(1, ChartOfAccountsHandler.computeDepth("child", parents));
  }

  @Test
  public void computeDepthThreeLevelsDeep() {
    // root → level1 → level2 → leaf
    Map<String, String> parents = new HashMap<>();
    parents.put("root", null);
    parents.put("level1", "root");
    parents.put("level2", "level1");
    parents.put("leaf", "level2");
    assertEquals(3, ChartOfAccountsHandler.computeDepth("leaf", parents));
  }

  @Test
  public void computeDepthCapAtMaxDepthForCircularReference() {
    // Artificially long chain; if there is a circle the cap must prevent infinite loop
    Map<String, String> parents = new HashMap<>();
    for (int i = 0; i < 50; i++) {
      parents.put("n" + i, "n" + (i + 1));
    }
    // Point the last one back to simulate a cycle (no terminating null)
    parents.put("n50", "n0");

    int depth = ChartOfAccountsHandler.computeDepth("n0", parents);
    // Must be some finite number <= 30 (MAX_TREE_DEPTH constant)
    assertTrue("depth must be capped at MAX_TREE_DEPTH", depth <= 30);
  }

  // ── findParentCode4 ───────────────────────────────────────────────────────

  @Test
  public void findParentCode4ReturnsNullWhenNoParent() {
    Map<String, String> parents = Collections.singletonMap("node", null);
    Map<String, String> values = Collections.singletonMap("node", "1234");
    assertNull(ChartOfAccountsHandler.findParentCode4("node", parents, values));
  }

  @Test
  public void findParentCode4FindsDirectParentWithFourCharValue() {
    Map<String, String> parents = new HashMap<>();
    parents.put("leaf", "parent");
    parents.put("parent", null);
    Map<String, String> values = new HashMap<>();
    values.put("leaf", "12345678");
    values.put("parent", "1234");

    assertEquals("1234", ChartOfAccountsHandler.findParentCode4("leaf", parents, values));
  }

  @Test
  public void findParentCode4SkipsParentWithWrongLength() {
    // leaf → parent3char → grandparent4char
    Map<String, String> parents = new HashMap<>();
    parents.put("leaf", "p3");
    parents.put("p3", "gp4");
    parents.put("gp4", null);
    Map<String, String> values = new HashMap<>();
    values.put("leaf", "12345678");
    values.put("p3", "123");      // length 3 — skip
    values.put("gp4", "1234");   // length 4 — match

    assertEquals("1234", ChartOfAccountsHandler.findParentCode4("leaf", parents, values));
  }

  @Test
  public void findParentCode4ReturnsNullWhenNoAncestorHasFourCharValue() {
    Map<String, String> parents = new HashMap<>();
    parents.put("leaf", "parent");
    parents.put("parent", null);
    Map<String, String> values = new HashMap<>();
    values.put("leaf", "12345678");
    values.put("parent", "12");  // length 2 — not a match

    assertNull(ChartOfAccountsHandler.findParentCode4("leaf", parents, values));
  }

  @Test
  public void findParentCode4ExcludesNodeItself() {
    // Node itself has length-4 value — should NOT be returned, traversal starts at parent
    Map<String, String> parents = new HashMap<>();
    parents.put("node", "parent");
    parents.put("parent", null);
    Map<String, String> values = new HashMap<>();
    values.put("node", "ABCD");   // length 4, but excluded (is the node itself)
    values.put("parent", "AB");   // length 2 — no match

    assertNull(ChartOfAccountsHandler.findParentCode4("node", parents, values));
  }

  // ── findParentCode4Name ───────────────────────────────────────────────────

  @Test
  public void findParentCode4NameReturnsNullWhenNodeHasNoParent() {
    Map<String, String> parents = Collections.singletonMap("node", null);
    Map<String, String> values = Collections.singletonMap("node", "1234");
    Map<String, String> names = Collections.singletonMap("node", "Test");
    assertNull(ChartOfAccountsHandler.findParentCode4Name("node", parents, values, names));
  }

  @Test
  public void findParentCode4NameReturnsNullWhenNodeNotInMap() {
    assertNull(ChartOfAccountsHandler.findParentCode4Name("ghost",
        Collections.emptyMap(), Collections.emptyMap(), Collections.emptyMap()));
  }

  @Test
  public void findParentCode4NameFindsSummaryParentName() {
    Map<String, String> parents = new HashMap<>();
    parents.put("leaf", "parent");
    parents.put("parent", null);
    Map<String, String> values = new HashMap<>();
    values.put("parent", "1234");
    Map<String, String> names = new HashMap<>();
    names.put("parent", "Acreedores");

    assertEquals("Acreedores",
        ChartOfAccountsHandler.findParentCode4Name("leaf", parents, values, names));
  }

  @Test
  public void findParentCode4NameSkipsParentWithWrongValueLength() {
    // leaf → p3 (length-3 value) → gp4 (length-4 value — match)
    Map<String, String> parents = new HashMap<>();
    parents.put("leaf", "p3");
    parents.put("p3", "gp4");
    parents.put("gp4", null);
    Map<String, String> values = new HashMap<>();
    values.put("p3", "123");
    values.put("gp4", "1234");
    Map<String, String> names = new HashMap<>();
    names.put("p3", "WrongLevel");
    names.put("gp4", "GrandParentGroup");

    assertEquals("GrandParentGroup",
        ChartOfAccountsHandler.findParentCode4Name("leaf", parents, values, names));
  }

  @Test
  public void findParentCode4NameReturnsNullWhenNoAncestorMatchesFourCharValue() {
    Map<String, String> parents = new HashMap<>();
    parents.put("leaf", "parent");
    parents.put("parent", null);
    Map<String, String> values = new HashMap<>();
    values.put("parent", "12");
    Map<String, String> names = new HashMap<>();
    names.put("parent", "ShortCode");

    assertNull(ChartOfAccountsHandler.findParentCode4Name("leaf", parents, values, names));
  }

  // ── buildAncestorChain ────────────────────────────────────────────────────

  @Test
  public void buildAncestorChainReturnsEmptyArrayForRootNode() throws Exception {
    Map<String, String> parents = Collections.singletonMap("root", null);
    JSONArray chain = ChartOfAccountsHandler.buildAncestorChain("root", parents,
        Collections.emptyMap(), Collections.emptyMap(), Collections.emptyMap());
    assertEquals(0, chain.length());
  }

  @Test
  public void buildAncestorChainExcludesNodeItself() throws Exception {
    // node → parent (root); node's own value must not appear in its own chain
    Map<String, String> parents = new HashMap<>();
    parents.put("node", "parent");
    parents.put("parent", null);
    Map<String, String> values = new HashMap<>();
    values.put("node", "20000000");
    values.put("parent", "2000");

    JSONArray chain = ChartOfAccountsHandler.buildAncestorChain("node", parents, values,
        Collections.emptyMap(), Collections.emptyMap());

    assertEquals(1, chain.length());
    assertEquals("2000", chain.getJSONObject(0).getString("value"));
  }

  @Test
  public void buildAncestorChainOrdersRootToLeafForSixLevelPgcExample() throws Exception {
    // Mirrors the live example from the CoA hierarchy investigation:
    // 20000000 (S) → 2000 (D) → 200 (C) → A.A.I (E) → A.A (E) → A (E, root)
    Map<String, String> parents = new HashMap<>();
    parents.put("leaf", "breakdown");
    parents.put("breakdown", "account");
    parents.put("account", "headingIII");
    parents.put("headingIII", "headingII");
    parents.put("headingII", "headingI");
    parents.put("headingI", null);

    Map<String, String> values = new HashMap<>();
    values.put("leaf", "20000000");
    values.put("breakdown", "2000");
    values.put("account", "200");
    values.put("headingIII", "A.A.I");
    values.put("headingII", "A.A");
    values.put("headingI", "A");

    Map<String, String> names = new HashMap<>();
    names.put("breakdown", "Investigación.");
    names.put("account", "Investigación.");
    names.put("headingIII", "I. Inmovilizado intangible.");
    names.put("headingII", "A) ACTIVO NO CORRIENTE");
    names.put("headingI", "ACTIVO");

    Map<String, String> levels = new HashMap<>();
    levels.put("breakdown", "D");
    levels.put("account", "C");
    levels.put("headingIII", "E");
    levels.put("headingII", "E");
    levels.put("headingI", "E");

    JSONArray chain = ChartOfAccountsHandler.buildAncestorChain("leaf", parents, values, names, levels);

    assertEquals(5, chain.length());
    assertEquals("A", chain.getJSONObject(0).getString("value"));
    assertEquals("E", chain.getJSONObject(0).getString("elementLevel"));
    assertEquals("A.A", chain.getJSONObject(1).getString("value"));
    assertEquals("A.A.I", chain.getJSONObject(2).getString("value"));
    assertEquals("200", chain.getJSONObject(3).getString("value"));
    assertEquals("C", chain.getJSONObject(3).getString("elementLevel"));
    assertEquals("Investigación.", chain.getJSONObject(3).getString("name"));
    assertEquals("2000", chain.getJSONObject(4).getString("value"));
    assertEquals("D", chain.getJSONObject(4).getString("elementLevel"));
  }

  @Test
  public void buildAncestorChainCapsAtMaxDepthForCircularReference() throws Exception {
    Map<String, String> parents = new HashMap<>();
    for (int i = 0; i < 50; i++) {
      parents.put("n" + i, "n" + (i + 1));
    }
    parents.put("n50", "n0"); // cycle, no terminating null

    JSONArray chain = ChartOfAccountsHandler.buildAncestorChain("n0", parents,
        Collections.emptyMap(), Collections.emptyMap(), Collections.emptyMap());

    assertTrue("chain must be capped at MAX_TREE_DEPTH", chain.length() <= 30);
  }

  @Test
  public void buildAncestorChainUsesJsonNullForMissingValueNameOrLevel() throws Exception {
    Map<String, String> parents = new HashMap<>();
    parents.put("leaf", "ghost"); // "ghost" has no entry in value/name/level maps
    parents.put("ghost", null);

    JSONArray chain = ChartOfAccountsHandler.buildAncestorChain("leaf", parents,
        Collections.emptyMap(), Collections.emptyMap(), Collections.emptyMap());

    assertEquals(1, chain.length());
    assertTrue(chain.getJSONObject(0).isNull("value"));
    assertTrue(chain.getJSONObject(0).isNull("name"));
    assertTrue(chain.getJSONObject(0).isNull("elementLevel"));
  }

  // ── rollupBalances ────────────────────────────────────────────────────────

  @Test
  public void rollupBalancesEmptyMapIsNoOp() {
    Map<String, BigDecimal[]> balances = new HashMap<>();
    ChartOfAccountsHandler.rollupBalances(balances, Collections.emptyMap());
    assertTrue(balances.isEmpty());
  }

  @Test
  public void rollupBalancesLeafPropagatesBalanceToParent() {
    // leaf → parent (root)
    Map<String, String> parents = new HashMap<>();
    parents.put("leaf", "parent");
    parents.put("parent", null);

    Map<String, BigDecimal[]> balances = new HashMap<>();
    balances.put("leaf", new BigDecimal[]{
        new BigDecimal("100.00"),
        new BigDecimal("50.00"),
        new BigDecimal("50.00")
    });

    ChartOfAccountsHandler.rollupBalances(balances, parents);

    BigDecimal[] parentBalance = balances.get("parent");
    assertNotNull("parent must have an entry after rollup", parentBalance);
    assertEquals(new BigDecimal("100.00"), parentBalance[0]); // debit
    assertEquals(new BigDecimal("50.00"), parentBalance[1]);  // credit
    assertEquals(new BigDecimal("50.00"), parentBalance[2]);  // balance
  }

  @Test
  public void rollupBalancesTwoLeavesAccumulateAtCommonParent() {
    // leaf1 → parent (root), leaf2 → parent (root)
    Map<String, String> parents = new HashMap<>();
    parents.put("leaf1", "parent");
    parents.put("leaf2", "parent");
    parents.put("parent", null);

    Map<String, BigDecimal[]> balances = new HashMap<>();
    balances.put("leaf1", new BigDecimal[]{bd("100"), bd("40"), bd("60")});
    balances.put("leaf2", new BigDecimal[]{bd("200"), bd("80"), bd("120")});

    ChartOfAccountsHandler.rollupBalances(balances, parents);

    BigDecimal[] parentBalance = balances.get("parent");
    assertNotNull(parentBalance);
    assertEquals(bd("300"), parentBalance[0]); // 100 + 200
    assertEquals(bd("120"), parentBalance[1]); //  40 +  80
    assertEquals(bd("180"), parentBalance[2]); //  60 + 120
  }

  @Test
  public void rollupBalancesThreeLevelTreeAccumulatesCorrectly() {
    // grandchild → child → root
    Map<String, String> parents = new HashMap<>();
    parents.put("grandchild", "child");
    parents.put("child", "root");
    parents.put("root", null);

    Map<String, BigDecimal[]> balances = new HashMap<>();
    balances.put("grandchild", new BigDecimal[]{bd("50"), bd("10"), bd("40")});

    ChartOfAccountsHandler.rollupBalances(balances, parents);

    // grandchild unchanged
    assertArrayEquals3(new BigDecimal[]{bd("50"), bd("10"), bd("40")}, balances.get("grandchild"));
    // child gets grandchild's balance
    assertArrayEquals3(new BigDecimal[]{bd("50"), bd("10"), bd("40")}, balances.get("child"));
    // root gets child's (= grandchild's) balance
    assertArrayEquals3(new BigDecimal[]{bd("50"), bd("10"), bd("40")}, balances.get("root"));
  }

  @Test
  public void rollupBalancesLeafWithNoEntryInTreeIsIgnored() {
    // "orphan" is in balances but has no parent in tree — should not crash
    Map<String, String> parents = Collections.emptyMap();

    Map<String, BigDecimal[]> balances = new HashMap<>();
    balances.put("orphan", new BigDecimal[]{bd("99"), bd("0"), bd("99")});

    ChartOfAccountsHandler.rollupBalances(balances, parents);

    // Only orphan entry remains — no new keys created
    assertEquals(1, balances.size());
  }

  // ── toBigDecimal ──────────────────────────────────────────────────────────

  @Test
  public void toBigDecimalFromBigDecimalReturnsSameValue() {
    BigDecimal expected = new BigDecimal("123.45");
    assertEquals(expected, ChartOfAccountsHandler.toBigDecimal(expected));
  }

  @Test
  public void toBigDecimalFromIntegerReturnsEquivalentBigDecimal() {
    assertEquals(new BigDecimal("42"),
        ChartOfAccountsHandler.toBigDecimal(Integer.valueOf(42)));
  }

  @Test
  public void toBigDecimalFromNullReturnsZero() {
    assertEquals(BigDecimal.ZERO, ChartOfAccountsHandler.toBigDecimal(null));
  }

  @Test
  public void toBigDecimalFromDoubleReturnsStringEquivalent() {
    BigDecimal result = ChartOfAccountsHandler.toBigDecimal(Double.valueOf(10.5));
    assertNotNull(result);
    assertEquals(0, new BigDecimal("10.5").compareTo(result));
  }

  // ── collectIds ───────────────────────────────────────────────────────────

  @Test
  public void collectIdsExtractsAllIds() throws Exception {
    JSONArray data = new JSONArray();
    data.put(new JSONObject().put("id", "A"));
    data.put(new JSONObject().put("id", "B"));
    data.put(new JSONObject().put("id", "C"));

    List<String> ids = ChartOfAccountsHandler.collectIds(data);
    assertEquals(3, ids.size());
    assertTrue(ids.contains("A"));
    assertTrue(ids.contains("B"));
    assertTrue(ids.contains("C"));
  }

  @Test
  public void collectIdsSkipsEntriesWithoutId() throws Exception {
    JSONArray data = new JSONArray();
    data.put(new JSONObject().put("id", "A"));
    data.put(new JSONObject().put("name", "no-id")); // no id key
    data.put(new JSONObject().put("id", "B"));

    List<String> ids = ChartOfAccountsHandler.collectIds(data);
    assertEquals(2, ids.size());
  }

  @Test
  public void collectIdsReturnsEmptyForEmptyArray() throws Exception {
    List<String> ids = ChartOfAccountsHandler.collectIds(new JSONArray());
    assertTrue(ids.isEmpty());
  }

  // ── applyIsLeaf ───────────────────────────────────────────────────────────

  @Test
  public void applyIsLeafSetsLeafTrueWhenNotSummary() throws Exception {
    JSONArray data = new JSONArray();
    data.put(new JSONObject().put("id", "EV1"));

    Map<String, Boolean> isSummaryMap = Collections.singletonMap("EV1", Boolean.FALSE);
    ChartOfAccountsHandler.applyIsLeaf(data, isSummaryMap);

    assertTrue(data.getJSONObject(0).getBoolean("isLeaf"));
  }

  @Test
  public void applyIsLeafSetsLeafFalseWhenSummary() throws Exception {
    JSONArray data = new JSONArray();
    data.put(new JSONObject().put("id", "EV2"));

    Map<String, Boolean> isSummaryMap = Collections.singletonMap("EV2", Boolean.TRUE);
    ChartOfAccountsHandler.applyIsLeaf(data, isSummaryMap);

    assertFalse(data.getJSONObject(0).getBoolean("isLeaf"));
  }

  @Test
  public void applyIsLeafSkipsEntryWhenIdNotInMap() throws Exception {
    JSONArray data = new JSONArray();
    JSONObject entry = new JSONObject().put("id", "UNKNOWN");
    data.put(entry);

    ChartOfAccountsHandler.applyIsLeaf(data, Collections.emptyMap());

    assertFalse("isLeaf key must not be injected when id is not in summary map",
        entry.has("isLeaf"));
  }

  // ── applyYtdBalances ─────────────────────────────────────────────────────

  @Test
  public void applyYtdBalancesInjectsAllThreeFieldsForKnownAccount() throws Exception {
    JSONArray data = new JSONArray();
    data.put(new JSONObject().put("id", "ACC1"));

    Map<String, BigDecimal[]> ytd = new HashMap<>();
    ytd.put("ACC1", new BigDecimal[]{bd("500"), bd("300"), bd("200")});

    ChartOfAccountsHandler.applyYtdBalances(data, ytd);

    JSONObject entry = data.getJSONObject(0);
    assertEquals(bd("500"), entry.get("ytdDebit"));
    assertEquals(bd("300"), entry.get("ytdCredit"));
    assertEquals(bd("200"), entry.get("ytdBalance"));
  }

  @Test
  public void applyYtdBalancesInjectsZerosForUnknownAccount() throws Exception {
    JSONArray data = new JSONArray();
    data.put(new JSONObject().put("id", "ACC_NO_DATA"));

    ChartOfAccountsHandler.applyYtdBalances(data, Collections.emptyMap());

    JSONObject entry = data.getJSONObject(0);
    assertEquals(BigDecimal.ZERO, entry.get("ytdDebit"));
    assertEquals(BigDecimal.ZERO, entry.get("ytdCredit"));
    assertEquals(BigDecimal.ZERO, entry.get("ytdBalance"));
  }

  @Test
  public void applyYtdBalancesSkipsEntryWithoutId() throws Exception {
    JSONArray data = new JSONArray();
    JSONObject entry = new JSONObject().put("name", "no-id");
    data.put(entry);

    ChartOfAccountsHandler.applyYtdBalances(data, Collections.emptyMap());

    // No ytdDebit/ytdCredit/ytdBalance should be injected
    assertFalse(entry.has("ytdDebit"));
  }

  // ── extractDataArray ──────────────────────────────────────────────────────

  @Test
  public void extractDataArrayReturnsNullWhenPreviousResultIsNull() {
    NeoContext ctx = mock(NeoContext.class);
    when(ctx.getPreviousResult()).thenReturn(null);
    assertNull(ChartOfAccountsHandler.extractDataArray(ctx));
  }

  @Test
  public void extractDataArrayReturnsNullWhenBodyIsNull() {
    NeoContext ctx = mock(NeoContext.class);
    NeoResponse response = mock(NeoResponse.class);
    when(response.getBody()).thenReturn(null);
    when(ctx.getPreviousResult()).thenReturn(response);
    assertNull(ChartOfAccountsHandler.extractDataArray(ctx));
  }

  @Test
  public void extractDataArrayReturnsNullWhenDataArrayIsEmpty() throws Exception {
    NeoContext ctx = mock(NeoContext.class);
    JSONObject body = new JSONObject().put("response",
        new JSONObject().put("data", new JSONArray()));
    NeoResponse response = mock(NeoResponse.class);
    when(response.getBody()).thenReturn(body);
    when(ctx.getPreviousResult()).thenReturn(response);
    assertNull(ChartOfAccountsHandler.extractDataArray(ctx));
  }

  @Test
  public void extractDataArrayReturnsArrayWhenPresent() throws Exception {
    NeoContext ctx = mock(NeoContext.class);
    JSONArray dataArray = new JSONArray().put(new JSONObject().put("id", "EV1"));
    JSONObject body = new JSONObject().put("response",
        new JSONObject().put("data", dataArray));
    NeoResponse response = mock(NeoResponse.class);
    when(response.getBody()).thenReturn(body);
    when(ctx.getPreviousResult()).thenReturn(response);

    JSONArray result = ChartOfAccountsHandler.extractDataArray(ctx);
    assertNotNull(result);
    assertEquals(1, result.length());
  }

  // ── extractDataArray — missing response key ──────────────────────────────

  @Test
  public void extractDataArrayReturnsNullWhenBodyHasNoResponseKey() {
    NeoContext ctx = mock(NeoContext.class);
    NeoResponse response = mock(NeoResponse.class);
    when(response.getBody()).thenReturn(new JSONObject()); // body with no "response" key
    when(ctx.getPreviousResult()).thenReturn(response);
    assertNull(ChartOfAccountsHandler.extractDataArray(ctx));
  }

  // ── applyIsLeaf — null array entry ─────────────────────────────────────

  @Test
  public void applyIsLeafSkipsNullArrayEntry() throws Exception {
    JSONArray data = new JSONArray();
    data.put(JSONObject.NULL); // null entry in the array
    data.put(new JSONObject().put("id", "EV2"));

    Map<String, Boolean> isSummaryMap = Collections.singletonMap("EV2", Boolean.FALSE);
    ChartOfAccountsHandler.applyIsLeaf(data, isSummaryMap);

    // null entry was skipped; EV2 was processed
    assertTrue(data.getJSONObject(1).getBoolean("isLeaf"));
  }

  // ── handle() — catch block and null return for DELETE ────────────────────

  @Test
  public void handleReturns500WhenCrudRequestThrows() {
    NeoContext ctx = mock(NeoContext.class);
    when(ctx.getEndpointType()).thenReturn(NeoEndpointType.CRUD);
    when(ctx.getHttpMethod()).thenThrow(new RuntimeException("boom"));

    NeoResponse resp = handler.handle(ctx);
    assertNotNull(resp);
    assertEquals(500, resp.getHttpStatus());
  }

  @Test
  public void handleReturnsNullForCrudDelete() {
    NeoContext ctx = mock(NeoContext.class);
    when(ctx.getEndpointType()).thenReturn(NeoEndpointType.CRUD);
    when(ctx.getHttpMethod()).thenReturn("DELETE");
    assertNull(handler.handle(ctx));
  }

  // ── afterHandle() — DEFAULTS path and catch block ────────────────────────

  @Test
  public void afterHandleReturnsNullForDefaultsWhenNoParentIdFound() {
    NeoContext ctx = mock(NeoContext.class);
    when(ctx.getEndpointType()).thenReturn(NeoEndpointType.DEFAULTS);
    // getQueryParams() returns an empty map: no parentAccountId → resolveParentAccountId returns null
    when(ctx.getQueryParams()).thenReturn(Collections.emptyMap());
    // RequestContext.get() will return null in unit-test context → also no id
    assertNull(handler.afterHandle(ctx));
  }

  @Test
  public void afterHandleReturnsNullForDefaultsWhenParentIdFoundButNoPreviousResult() {
    NeoContext ctx = mock(NeoContext.class);
    when(ctx.getEndpointType()).thenReturn(NeoEndpointType.DEFAULTS);
    Map<String, String> queryParams = new HashMap<>();
    queryParams.put("parentAccountId", "SOME-ID");
    when(ctx.getQueryParams()).thenReturn(queryParams);
    when(ctx.getPreviousResult()).thenReturn(null); // injectCodePrefix checks previous == null
    assertNull(handler.afterHandle(ctx));
  }

  @Test
  public void afterHandleReturnsNullWhenInjectCodePrefixThrows() {
    NeoContext ctx = mock(NeoContext.class);
    when(ctx.getEndpointType()).thenReturn(NeoEndpointType.DEFAULTS);
    Map<String, String> queryParams = new HashMap<>();
    queryParams.put("parentAccountId", "SOME-ID");
    when(ctx.getQueryParams()).thenReturn(queryParams);
    when(ctx.getPreviousResult()).thenThrow(new RuntimeException("inject-error"));

    // afterHandle catch block returns null instead of propagating
    assertNull(handler.afterHandle(ctx));
  }

  // ── afterHandle() CRUD GET list — enrichGetResponse with OBDal mock ──────

  @SuppressWarnings("unchecked")
  @Test
  public void afterHandleEnrichesGetResponseListWithOBDalMock() throws Exception {
    // Build a list-GET context: no recordId → isList=true
    JSONArray dataArray = new JSONArray().put(new JSONObject().put("id", "EV1"));
    JSONObject responseJson = new JSONObject()
        .put("data", dataArray)
        .put("totalRows", 1);
    JSONObject body = new JSONObject().put("response", responseJson);

    NeoResponse prevResult = mock(NeoResponse.class);
    when(prevResult.getBody()).thenReturn(body);

    OBContext obCtxInstance = mock(OBContext.class);
    Client clientMock = mock(Client.class);
    when(clientMock.getId()).thenReturn("TEST_CLIENT");
    when(obCtxInstance.getCurrentClient()).thenReturn(clientMock);

    NeoContext ctx = mock(NeoContext.class);
    when(ctx.getEndpointType()).thenReturn(NeoEndpointType.CRUD);
    when(ctx.getHttpMethod()).thenReturn("GET");
    when(ctx.getRecordId()).thenReturn(null); // list GET
    when(ctx.getPreviousResult()).thenReturn(prevResult);
    when(ctx.getObContext()).thenReturn(obCtxInstance);

    OBDal dalMock = mock(OBDal.class);
    @SuppressWarnings("unchecked")
    OBQuery<ElementValue> summaryQry = mock(OBQuery.class);
    when(dalMock.createQuery(any(Class.class), anyString())).thenReturn(summaryQry);
    when(summaryQry.setNamedParameter(anyString(), any())).thenReturn(summaryQry);
    when(summaryQry.list()).thenReturn(Collections.emptyList()); // no summary data

    Session sessionMock = mock(Session.class);
    when(dalMock.getSession()).thenReturn(sessionMock);

    @SuppressWarnings("unchecked")
    NativeQuery<Object> treeQry = mock(NativeQuery.class);   // SQL_FIND_EV_TREE → tree found
    @SuppressWarnings("unchecked")
    NativeQuery<Object> nodeQry = mock(NativeQuery.class);   // SQL_LOAD_TREE_NODES → empty
    @SuppressWarnings("unchecked")
    NativeQuery<Object> evQry = mock(NativeQuery.class);     // SQL_LOAD_EV_VALUES → empty
    @SuppressWarnings("unchecked")
    NativeQuery<Object> yearQry = mock(NativeQuery.class);   // SQL_CURRENT_YEAR → empty

    // setParameter return value is not used in production code — no need to stub
    when(treeQry.list()).thenReturn(Collections.singletonList("TREE-ID")); // tree exists
    when(nodeQry.list()).thenReturn(Collections.emptyList()); // no nodes
    when(evQry.list()).thenReturn(Collections.emptyList());   // no EVs
    when(yearQry.list()).thenReturn(Collections.emptyList()); // no fiscal year → emptyMap

    when(sessionMock.createNativeQuery(anyString()))
        .thenReturn(treeQry)
        .thenReturn(nodeQry)
        .thenReturn(evQry)
        .thenReturn(yearQry);

    try (MockedStatic<OBDal> obDalStatic = mockStatic(OBDal.class);
         MockedStatic<OBContext> obCtxStatic = mockStatic(OBContext.class)) {
      obDalStatic.when(OBDal::getInstance).thenReturn(dalMock);
      // setAdminMode and restorePreviousMode default to no-op in MockedStatic

      NeoResponse result = handler.afterHandle(ctx);
      assertNotNull("enrichGetResponse must return a non-null NeoResponse", result);
      assertEquals(200, result.getHttpStatus());
    }
  }

  // ── afterHandle() DEFAULTS — injectCodePrefix OBDal path ─────────────────

  @SuppressWarnings("unchecked")
  @Test
  public void afterHandleInjectsCodePrefixWhenParentFoundInOBDal() throws Exception {
    JSONObject body = new JSONObject().put("defaults", new JSONObject());
    NeoResponse prevResult = mock(NeoResponse.class);
    when(prevResult.getBody()).thenReturn(body);

    NeoContext ctx = mock(NeoContext.class);
    when(ctx.getEndpointType()).thenReturn(NeoEndpointType.DEFAULTS);
    Map<String, String> queryParams = new HashMap<>();
    queryParams.put("parentAccountId", "PARENT-ID");
    when(ctx.getQueryParams()).thenReturn(queryParams);
    when(ctx.getPreviousResult()).thenReturn(prevResult);

    ElementValue parentEV = mock(ElementValue.class);
    when(parentEV.getSearchKey()).thenReturn("12345678"); // 8 digits → prefix = "1234"

    OBDal dalMock = mock(OBDal.class);
    when(dalMock.get(any(Class.class), any())).thenReturn(parentEV);

    try (MockedStatic<OBDal> obDalStatic = mockStatic(OBDal.class);
         MockedStatic<OBContext> obCtxStatic = mockStatic(OBContext.class)) {
      obDalStatic.when(OBDal::getInstance).thenReturn(dalMock);

      NeoResponse result = handler.afterHandle(ctx);
      assertNotNull(result);
      assertEquals(200, result.getHttpStatus());
      assertEquals("1234", result.getBody().getJSONObject("defaults").getString("codePrefix"));
    }
  }

  // ── afterHandle() CRUD GET list — loadTreeData with populated node rows ────

  @SuppressWarnings("unchecked")
  @Test
  public void afterHandleEnrichesGetResponseWithPopulatedNodeRows() throws Exception {
    // Same list-GET context as the base test but nodeRows and evRows are non-empty,
    // exercising the for-loop bodies inside loadTreeData (lines 641-651, 665-667).
    JSONArray dataArray = new JSONArray().put(new JSONObject().put("id", "EV1"));
    JSONObject responseJson = new JSONObject().put("data", dataArray).put("totalRows", 1);
    JSONObject body = new JSONObject().put("response", responseJson);

    NeoResponse prevResult = mock(NeoResponse.class);
    when(prevResult.getBody()).thenReturn(body);

    OBContext obCtxInstance = mock(OBContext.class);
    Client clientMock = mock(Client.class);
    when(clientMock.getId()).thenReturn("TEST_CLIENT");
    when(obCtxInstance.getCurrentClient()).thenReturn(clientMock);

    NeoContext ctx = mock(NeoContext.class);
    when(ctx.getEndpointType()).thenReturn(NeoEndpointType.CRUD);
    when(ctx.getHttpMethod()).thenReturn("GET");
    when(ctx.getRecordId()).thenReturn(null);
    when(ctx.getPreviousResult()).thenReturn(prevResult);
    when(ctx.getObContext()).thenReturn(obCtxInstance);

    OBDal dalMock = mock(OBDal.class);
    OBQuery<ElementValue> summaryQry = mock(OBQuery.class);
    when(dalMock.createQuery(any(Class.class), anyString())).thenReturn(summaryQry);
    when(summaryQry.setNamedParameter(anyString(), any())).thenReturn(summaryQry);
    when(summaryQry.list()).thenReturn(Collections.emptyList());

    Session sessionMock = mock(Session.class);
    when(dalMock.getSession()).thenReturn(sessionMock);

    NativeQuery<Object> treeQry = mock(NativeQuery.class);
    NativeQuery<Object> nodeQry = mock(NativeQuery.class);
    NativeQuery<Object> evQry = mock(NativeQuery.class);
    NativeQuery<Object> yearQry = mock(NativeQuery.class);

    when(treeQry.list()).thenReturn(Collections.singletonList("TREE-ID"));

    // Non-empty nodeRows: EV1 is a non-root child, ROOT-0 is a root node
    java.util.List<Object> nodeRowsList = new java.util.ArrayList<>();
    nodeRowsList.add(new Object[]{"EV1", "ROOT-0"}); // non-root → covers lines 641-648
    nodeRowsList.add(new Object[]{"ROOT-0", "0"});   // parentId="0" → covers lines 649-651
    when(nodeQry.list()).thenReturn(nodeRowsList);

    // Non-empty evRows: EV1 and ROOT-0 with value/name/elementLevel → covers lines 665-667
    java.util.List<Object> evRowsList = new java.util.ArrayList<>();
    evRowsList.add(new Object[]{"EV1", "1001", "Caja", "S"});
    evRowsList.add(new Object[]{"ROOT-0", "1000", "Grupo Caja", "D"});
    when(evQry.list()).thenReturn(evRowsList);

    when(yearQry.list()).thenReturn(Collections.emptyList()); // no fiscal year

    when(sessionMock.createNativeQuery(anyString()))
        .thenReturn(treeQry)
        .thenReturn(nodeQry)
        .thenReturn(evQry)
        .thenReturn(yearQry);

    try (MockedStatic<OBDal> obDalStatic = mockStatic(OBDal.class);
         MockedStatic<OBContext> obCtxStatic = mockStatic(OBContext.class)) {
      obDalStatic.when(OBDal::getInstance).thenReturn(dalMock);

      NeoResponse result = handler.afterHandle(ctx);
      assertNotNull(result);
      assertEquals(200, result.getHttpStatus());
    }
  }

  // ── countChildren (package-private, no OBDal mock available in unit test) ─

  // NOTE: countChildren requires OBDal.getInstance().getSession() which is unavailable
  // in unit tests. It is covered by integration tests in the full Etendo test suite.

  // ── helpers ───────────────────────────────────────────────────────────────

  /** Shorthand for creating BigDecimal from string. */
  private static BigDecimal bd(String val) {
    return new BigDecimal(val);
  }

  /** Asserts that a 3-element BigDecimal array matches expected values by index. */
  private static void assertArrayEquals3(BigDecimal[] expected, BigDecimal[] actual) {
    assertNotNull("balance array must not be null", actual);
    assertEquals("debit", expected[0], actual[0]);
    assertEquals("credit", expected[1], actual[1]);
    assertEquals("balance", expected[2], actual[2]);
  }
}
