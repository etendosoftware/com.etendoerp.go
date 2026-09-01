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
 * All portions are Copyright © 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.schemaforge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;
import org.mockito.stubbing.OngoingStubbing;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.erpCommon.utility.DimensionDisplayUtility;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.plm.Product;
import org.openbravo.model.financialmgmt.accounting.Costcenter;
import org.openbravo.model.financialmgmt.payment.FIN_FinaccTransaction;
import org.openbravo.model.financialmgmt.payment.FIN_FinancialAccount;
import org.openbravo.model.project.Project;

/**
 * Unit tests for {@link AccountingDimensionsSupport} — the single source of truth for "which
 * accounting dimensions are active right now" (ETP-4950).
 *
 * <p>The class answers that question from two mutually exclusive sources, selected by
 * {@code AD_Client.Acctdim_Centrally_Maintained}, so the tests are organised around that fork:
 *
 * <ul>
 *   <li>flag {@code 'N'} / {@code null} → the flat {@code C_AcctSchema_Element} switches minus the
 *       dimensions explicitly hidden in {@code ad_client_acctdimension.show_in_header};</li>
 *   <li>flag {@code 'Y'} → Core's {@code DimensionDisplayUtility} matrix, keyed
 *       {@code $Element_<COD>_<DOCBASETYPE>_H}, <b>falling back to the flat set</b> for the
 *       dimensions Core does not emit a key for (activity, campaign, sales region) instead of
 *       reading their absence as "inactive".</li>
 * </ul>
 *
 * <p>The class also owns the two consumers of that answer: {@code applyRuleDimensions}, which
 * copies a matching rule's project / cost center / product onto the {@code FIN_FinaccTransaction}
 * Automatch generates (skipping whatever is not active, and resolving the allowed set LAZILY —
 * through a {@link Supplier} that must stay unconsulted for a dimensionless spec), and
 * {@code toOrderedArray}, which serializes a dimension set in the canonical display order.
 *
 * <p>Both SQL statements are driven through a mocked JDBC {@link Connection}; the four queries are
 * told apart by the table they hit and by whether they scope through {@code fin_financial_account}.
 * JUnit 4 + the Silent runner mirror the sibling handler tests, and {@code clearInlineMocks()}
 * keeps the module's single test JVM heap flat.
 */
@RunWith(MockitoJUnitRunner.Silent.class)
public class AccountingDimensionsSupportTest {

  private static final String FAT = AccountingDimensionsSupport.DOCBASETYPE_FAT;

  /** Table fragments used to tell the four prepared statements apart. */
  private static final String TBL_ELEMENT = "c_acctschema_element";
  private static final String TBL_CLIENT_DIM = "ad_client_acctdimension";
  private static final String TBL_ACCOUNT = "fin_financial_account";

  private static final String COL_ELEMENT = "elementtype";
  private static final String COL_DIMENSION = "dimension";

  private static final String ACCOUNT_ID = "ACC-1";
  private static final String CLIENT_ID = "CLI-1";
  private static final String PROJECT_ID = "PJ-1";
  private static final String COSTCENTER_ID = "CC-1";
  private static final String PRODUCT_ID = "PR-1";

  /**
   * Releases the inline mock-maker references created during the test. Without this they accumulate
   * across the module's single test JVM and push the fork past its heap cap.
   */
  @After
  public void clearMocks() {
    Mockito.framework().clearInlineMocks();
  }

  // ── fixtures ───────────────────────────────────────────────────────────────

  /** A result set that yields one row per {@code codes} entry on {@code column}, then ends. */
  private static PreparedStatement psRows(String column, String... codes) throws Exception {
    PreparedStatement ps = mock(PreparedStatement.class);
    ResultSet rs = mock(ResultSet.class);
    when(ps.executeQuery()).thenReturn(rs);

    OngoingStubbing<Boolean> next = when(rs.next());
    for (int i = 0; i < codes.length; i++) {
      next = next.thenReturn(Boolean.TRUE);
    }
    next.thenReturn(Boolean.FALSE);

    if (codes.length > 0) {
      OngoingStubbing<String> value = when(rs.getString(column));
      for (String code : codes) {
        value = value.thenReturn(code);
      }
    }
    return ps;
  }

  /**
   * Stubs one of the four queries on {@code conn} and returns its statement (so a test can assert
   * the bound parameters).
   *
   * @param table         {@link #TBL_ELEMENT} (flat source) or {@link #TBL_CLIENT_DIM} (hidden set)
   * @param accountScoped whether the wanted variant resolves the tenant through the account
   */
  private static PreparedStatement stubQuery(Connection conn, String table, boolean accountScoped,
      String column, String... codes) throws Exception {
    PreparedStatement ps = psRows(column, codes);
    when(conn.prepareStatement(argThat((String sql) -> sql != null && sql.contains(table)
        && sql.contains(TBL_ACCOUNT) == accountScoped))).thenReturn(ps);
    return ps;
  }

  /** The {@code $Element_<code>_FAT_H} key {@code DimensionDisplayUtility} writes. */
  private static String headerKey(String elementCode) {
    return DimensionDisplayUtility.ELEMENT + "_" + elementCode + "_" + FAT + "_"
        + DimensionDisplayUtility.DIM_Header;
  }

  private static Set<String> setOf(String... keys) {
    return new HashSet<>(Arrays.asList(keys));
  }

  /** A client mock whose centrally-maintained flag is {@code flag} (possibly {@code null}). */
  private static Client client(Boolean flag) {
    Client c = mock(Client.class);
    when(c.getId()).thenReturn(CLIENT_ID);
    when(c.isAcctdimCentrallyMaintained()).thenReturn(flag);
    return c;
  }

  /** An {@link OBDal} static mock whose {@code getConnection()} answers with {@code conn}. */
  private static OBDal dalOn(MockedStatic<OBDal> obDal, Connection conn) {
    OBDal dal = mock(OBDal.class);
    obDal.when(OBDal::getInstance).thenReturn(dal);
    when(dal.getConnection()).thenReturn(conn);
    return dal;
  }

  /** An {@link OBDal} static mock with no JDBC connection — the entity-lookup-only tests. */
  private static OBDal dalOnly(MockedStatic<OBDal> obDal) {
    OBDal dal = mock(OBDal.class);
    obDal.when(OBDal::getInstance).thenReturn(dal);
    return dal;
  }

  /** The {@code createPayment} spec shape {@code AutoMatchSupport.putRuleDimensions} emits. */
  private static JSONObject spec(String projectId, String costcenterId, String productId)
      throws Exception {
    return new JSONObject()
        .put(AutoMatchSupport.KEY_PROJECT_ID, projectId)
        .put(AutoMatchSupport.KEY_COSTCENTER_ID, costcenterId)
        .put(AutoMatchSupport.KEY_PRODUCT_ID, productId);
  }

  private static Set<String> allThreeDimensions() {
    return setOf(AccountingDimensionsSupport.DIM_PROJECT,
        AccountingDimensionsSupport.DIM_COSTCENTER,
        AccountingDimensionsSupport.DIM_PRODUCT);
  }

  /**
   * A dimension supplier that records how many times it was consulted. The laziness of
   * {@code applyRuleDimensions} is a behaviour, not an optimization (the real supplier runs SQL),
   * so the tests assert on the invocation count rather than on the resolved set alone.
   */
  private static final class CountingSupplier implements Supplier<Set<String>> {
    private final Set<String> allowed;
    private int calls;

    CountingSupplier(Set<String> allowed) {
      this.allowed = allowed;
    }

    @Override
    public Set<String> get() {
      calls++;
      return allowed;
    }

    int calls() {
      return calls;
    }
  }

  private static CountingSupplier allowing(Set<String> allowed) {
    return new CountingSupplier(allowed);
  }

  /** The dimension keys of a serialized payload, in the order they were written. */
  private static List<String> asList(JSONArray arr) {
    List<String> keys = new ArrayList<>();
    for (int i = 0; i < arr.length(); i++) {
      keys.add(arr.optString(i));
    }
    return keys;
  }

  // ── flat source (C_AcctSchema_Element) ─────────────────────────────────────

  /**
   * The flat per-account query maps Core element codes to the lowercase UI dimension keys, trims
   * padded codes, and silently drops a code that is not a navigable dimension ({@code AC} =
   * account).
   *
   * @throws Exception if the mocked JDBC plumbing fails
   */
  @Test
  public void testFlatActiveDimensionsForAccountMapsKnownElementCodes() throws Exception {
    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      Connection conn = mock(Connection.class);
      dalOn(obDal, conn);
      stubQuery(conn, TBL_ELEMENT, true, COL_ELEMENT, "PJ", " CC ", "PR", "AC");

      assertEquals(setOf(AccountingDimensionsSupport.DIM_PROJECT,
              AccountingDimensionsSupport.DIM_COSTCENTER,
              AccountingDimensionsSupport.DIM_PRODUCT),
          AccountingDimensionsSupport.flatActiveDimensionsForAccount(ACCOUNT_ID));
    }
  }

  /**
   * The flat per-client query binds the client id as its single parameter (a numeric-looking legacy
   * id must go through {@code setString}, never {@code setInt}).
   *
   * @throws Exception if the mocked JDBC plumbing fails
   */
  @Test
  public void testFlatActiveDimensionsForClientBindsTheClientIdAsString() throws Exception {
    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      Connection conn = mock(Connection.class);
      dalOn(obDal, conn);
      PreparedStatement ps = stubQuery(conn, TBL_ELEMENT, false, COL_ELEMENT, "U1", "U2");

      assertEquals(setOf(AccountingDimensionsSupport.DIM_USER1,
              AccountingDimensionsSupport.DIM_USER2),
          AccountingDimensionsSupport.flatActiveDimensionsForClient(CLIENT_ID));
      verify(ps).setString(1, CLIENT_ID);
    }
  }

  /**
   * An empty chart of accounts yields an empty set rather than a null.
   *
   * @throws Exception if the mocked JDBC plumbing fails
   */
  @Test
  public void testFlatActiveDimensionsWithNoRowsReturnsEmptySet() throws Exception {
    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      Connection conn = mock(Connection.class);
      dalOn(obDal, conn);
      stubQuery(conn, TBL_ELEMENT, false, COL_ELEMENT);

      assertTrue(AccountingDimensionsSupport.flatActiveDimensionsForClient(CLIENT_ID).isEmpty());
    }
  }

  // ── header set, flag 'N' (flat source wins) ────────────────────────────────

  /**
   * A null client short-circuits to an empty set without touching the DAL at all.
   *
   * @throws Exception if the call under test declares it
   */
  @Test
  public void testActiveHeaderDimensionsWithNullClientReturnsEmptySet() throws Exception {
    assertTrue(AccountingDimensionsSupport.activeHeaderDimensions(null, FAT).isEmpty());
  }

  /**
   * With the flag off, the header set is the flat active set MINUS the dimensions explicitly
   * flagged {@code show_in_header = 'N'} for this document base type. Header dimensions default to
   * visible when there is no override row, so only the listed ones drop out.
   *
   * @throws Exception if the mocked JDBC plumbing fails
   */
  @Test
  public void testNotCentrallyMaintainedRemovesExplicitlyHiddenHeaderDimensions() throws Exception {
    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
         MockedStatic<OBContext> obContext = mockStatic(OBContext.class)) {
      Connection conn = mock(Connection.class);
      dalOn(obDal, conn);
      stubQuery(conn, TBL_ELEMENT, false, COL_ELEMENT, "PJ", "CC", "PR");
      PreparedStatement hidden = stubQuery(conn, TBL_CLIENT_DIM, false, COL_DIMENSION, "PJ");

      Set<String> dims =
          AccountingDimensionsSupport.activeHeaderDimensions(client(Boolean.FALSE), FAT);

      assertEquals(setOf(AccountingDimensionsSupport.DIM_COSTCENTER,
          AccountingDimensionsSupport.DIM_PRODUCT), dims);
      // The hidden-set query is scoped by document base type first, then by client.
      verify(hidden).setString(1, FAT);
      verify(hidden).setString(2, CLIENT_ID);
      obContext.verify(() -> OBContext.setAdminMode(true));
      obContext.verify(OBContext::restorePreviousMode);
    }
  }

  /**
   * A {@code null} centrally-maintained flag (never configured) must behave exactly like
   * {@code 'N'}: the flat source is authoritative and Core's matrix is never consulted.
   *
   * @throws Exception if the mocked JDBC plumbing fails
   */
  @Test
  public void testNullCentrallyMaintainedFlagFallsBackToTheFlatSource() throws Exception {
    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
         MockedStatic<OBContext> obContext = mockStatic(OBContext.class);
         MockedStatic<DimensionDisplayUtility> core = mockStatic(DimensionDisplayUtility.class)) {
      Connection conn = mock(Connection.class);
      dalOn(obDal, conn);
      stubQuery(conn, TBL_ELEMENT, false, COL_ELEMENT, "PJ");
      stubQuery(conn, TBL_CLIENT_DIM, false, COL_DIMENSION);

      Set<String> dims = AccountingDimensionsSupport.activeHeaderDimensions(client(null), FAT);

      assertEquals(Collections.singleton(AccountingDimensionsSupport.DIM_PROJECT), dims);
      core.verify(() -> DimensionDisplayUtility.getAccountingDimensionConfiguration(any()), never());
      obContext.verify(OBContext::restorePreviousMode);
    }
  }

  // ── header set, flag 'Y' (Core's matrix wins) ──────────────────────────────

  /**
   * With the flag on, Core's {@code $Element_<COD>_FAT_H} matrix decides: {@code 'Y'} shows the
   * dimension even when the flat chart-of-accounts switch is off, {@code 'N'} hides it even when
   * the flat switch is on ({@code C_AcctSchema_Element.IsActive} is a no-op under this flag).
   *
   * @throws Exception if the mocked JDBC plumbing fails
   */
  @Test
  public void testCentrallyMaintainedMatrixOverridesTheFlatSwitches() throws Exception {
    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
         MockedStatic<OBContext> obContext = mockStatic(OBContext.class);
         MockedStatic<DimensionDisplayUtility> core = mockStatic(DimensionDisplayUtility.class)) {
      Connection conn = mock(Connection.class);
      dalOn(obDal, conn);
      // Flat: project + cost center active, product inactive.
      stubQuery(conn, TBL_ELEMENT, false, COL_ELEMENT, "PJ", "CC");

      Client c = client(Boolean.TRUE);
      Map<String, String> config = new HashMap<>();
      config.put(headerKey("PJ"), "N");   // active in flat, hidden by the matrix
      config.put(headerKey("CC"), "Y");   // active in both
      config.put(headerKey("PR"), "Y");   // inactive in flat, shown by the matrix
      core.when(() -> DimensionDisplayUtility.getAccountingDimensionConfiguration(c))
          .thenReturn(config);

      Set<String> dims = AccountingDimensionsSupport.activeHeaderDimensions(c, FAT);

      assertEquals(setOf(AccountingDimensionsSupport.DIM_COSTCENTER,
          AccountingDimensionsSupport.DIM_PRODUCT), dims);
      // No hidden-header query is issued on this branch.
      verify(conn, never()).prepareStatement(argThat(
          (String sql) -> sql != null && sql.contains(TBL_CLIENT_DIM)));
      obContext.verify(OBContext::restorePreviousMode);
    }
  }

  /**
   * REGRESSION (ETP-4950): Core only emits matrix keys for the dimensions its Client window can
   * configure. Activity, campaign and sales region have NO key at all, so their absence must fall
   * back to the flat chart-of-accounts answer rather than being read as "inactive" — otherwise an
   * empty/partial matrix would silently strip every dimension.
   *
   * @throws Exception if the mocked JDBC plumbing fails
   */
  @Test
  public void testCentrallyMaintainedFallsBackToFlatForDimensionsCoreDoesNotConfigure()
      throws Exception {
    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
         MockedStatic<OBContext> obContext = mockStatic(OBContext.class);
         MockedStatic<DimensionDisplayUtility> core = mockStatic(DimensionDisplayUtility.class)) {
      Connection conn = mock(Connection.class);
      dalOn(obDal, conn);
      stubQuery(conn, TBL_ELEMENT, false, COL_ELEMENT, "AY", "MC", "SR", "PJ");

      Client c = client(Boolean.TRUE);
      // Core configured only the project key; the other three have no entry at all.
      core.when(() -> DimensionDisplayUtility.getAccountingDimensionConfiguration(c))
          .thenReturn(Collections.singletonMap(headerKey("PJ"), "Y"));

      Set<String> dims = AccountingDimensionsSupport.activeHeaderDimensions(c, FAT);

      assertEquals(setOf(AccountingDimensionsSupport.DIM_ACTIVITY,
              AccountingDimensionsSupport.DIM_CAMPAIGN,
              AccountingDimensionsSupport.DIM_SALESREGION,
              AccountingDimensionsSupport.DIM_PROJECT),
          dims);
      obContext.verify(OBContext::restorePreviousMode);
    }
  }

  /**
   * An empty matrix (nothing configured yet) degrades to exactly the flat active set, so switching
   * the flag on never silently empties the dimension list.
   *
   * @throws Exception if the mocked JDBC plumbing fails
   */
  @Test
  public void testCentrallyMaintainedWithEmptyMatrixEqualsTheFlatSet() throws Exception {
    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
         MockedStatic<OBContext> obContext = mockStatic(OBContext.class);
         MockedStatic<DimensionDisplayUtility> core = mockStatic(DimensionDisplayUtility.class)) {
      Connection conn = mock(Connection.class);
      dalOn(obDal, conn);
      stubQuery(conn, TBL_ELEMENT, false, COL_ELEMENT, "PJ", "CC", "PR");

      Client c = client(Boolean.TRUE);
      core.when(() -> DimensionDisplayUtility.getAccountingDimensionConfiguration(c))
          .thenReturn(new HashMap<>());

      assertEquals(setOf(AccountingDimensionsSupport.DIM_PROJECT,
              AccountingDimensionsSupport.DIM_COSTCENTER,
              AccountingDimensionsSupport.DIM_PRODUCT),
          AccountingDimensionsSupport.activeHeaderDimensions(c, FAT));
      obContext.verify(OBContext::restorePreviousMode);
    }
  }

  /**
   * The matrix is read per document base type: a key written for another base type must not leak
   * into the {@code FAT} answer.
   *
   * @throws Exception if the mocked JDBC plumbing fails
   */
  @Test
  public void testCentrallyMaintainedMatrixIsScopedByDocumentBaseType() throws Exception {
    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
         MockedStatic<OBContext> obContext = mockStatic(OBContext.class);
         MockedStatic<DimensionDisplayUtility> core = mockStatic(DimensionDisplayUtility.class)) {
      Connection conn = mock(Connection.class);
      dalOn(obDal, conn);
      // Project is NOT active in the flat source, so only a FAT-scoped 'Y' could switch it on.
      stubQuery(conn, TBL_ELEMENT, false, COL_ELEMENT);

      Client c = client(Boolean.TRUE);
      core.when(() -> DimensionDisplayUtility.getAccountingDimensionConfiguration(c))
          .thenReturn(Collections.singletonMap(
              DimensionDisplayUtility.ELEMENT + "_PJ_ARI_" + DimensionDisplayUtility.DIM_Header,
              "Y"));

      assertTrue(AccountingDimensionsSupport.activeHeaderDimensions(c, FAT).isEmpty());
      obContext.verify(OBContext::restorePreviousMode);
    }
  }

  // ── account / current-client entry points ──────────────────────────────────

  /**
   * When the financial account resolves, its client drives the answer through the client-scoped
   * queries; the account-scoped fallback queries are not used.
   *
   * @throws Exception if the mocked JDBC plumbing fails
   */
  @Test
  public void testActiveHeaderDimensionsForAccountResolvesTheTenantFromTheAccount()
      throws Exception {
    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
         MockedStatic<OBContext> obContext = mockStatic(OBContext.class)) {
      Connection conn = mock(Connection.class);
      OBDal dal = dalOn(obDal, conn);

      Client c = client(Boolean.FALSE);
      FIN_FinancialAccount account = mock(FIN_FinancialAccount.class);
      when(account.getClient()).thenReturn(c);
      when(dal.get(eq(FIN_FinancialAccount.class), eq(ACCOUNT_ID))).thenReturn(account);

      PreparedStatement flatByClient =
          stubQuery(conn, TBL_ELEMENT, false, COL_ELEMENT, "PJ", "CC");
      stubQuery(conn, TBL_CLIENT_DIM, false, COL_DIMENSION, "CC");

      Set<String> dims =
          AccountingDimensionsSupport.activeHeaderDimensionsForAccount(ACCOUNT_ID, FAT);

      assertEquals(Collections.singleton(AccountingDimensionsSupport.DIM_PROJECT), dims);
      verify(flatByClient).setString(1, CLIENT_ID);
      verify(conn, never()).prepareStatement(argThat(
          (String sql) -> sql != null && sql.contains(TBL_ACCOUNT)));
      obContext.verify(OBContext::restorePreviousMode);
    }
  }

  /**
   * When the account (or its client) cannot be resolved, the account-scoped queries take over so a
   * lookup failure never leaves the caller with an empty dimension set.
   *
   * @throws Exception if the mocked JDBC plumbing fails
   */
  @Test
  public void testActiveHeaderDimensionsForAccountFallsBackToTheAccountScopedQueries()
      throws Exception {
    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      Connection conn = mock(Connection.class);
      OBDal dal = dalOn(obDal, conn);
      when(dal.get(eq(FIN_FinancialAccount.class), anyString())).thenReturn(null);

      PreparedStatement flatByAccount =
          stubQuery(conn, TBL_ELEMENT, true, COL_ELEMENT, "PJ", "PR");
      PreparedStatement hiddenByAccount =
          stubQuery(conn, TBL_CLIENT_DIM, true, COL_DIMENSION, "PR");

      Set<String> dims =
          AccountingDimensionsSupport.activeHeaderDimensionsForAccount(ACCOUNT_ID, FAT);

      assertEquals(Collections.singleton(AccountingDimensionsSupport.DIM_PROJECT), dims);
      verify(flatByAccount).setString(1, ACCOUNT_ID);
      verify(hiddenByAccount).setString(1, FAT);
      verify(hiddenByAccount).setString(2, ACCOUNT_ID);
    }
  }

  /**
   * The current-client entry point resolves the tenant out of {@link OBContext} and then behaves
   * like {@code activeHeaderDimensions}.
   *
   * @throws Exception if the mocked JDBC plumbing fails
   */
  @Test
  public void testActiveHeaderDimensionsForCurrentClientResolvesTheSessionTenant()
      throws Exception {
    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
         MockedStatic<OBContext> obContext = mockStatic(OBContext.class)) {
      Connection conn = mock(Connection.class);
      OBDal dal = dalOn(obDal, conn);

      Client c = client(Boolean.FALSE);
      OBContext ctx = mock(OBContext.class);
      when(ctx.getCurrentClient()).thenReturn(c);
      obContext.when(OBContext::getOBContext).thenReturn(ctx);
      when(dal.get(eq(Client.class), eq(CLIENT_ID))).thenReturn(c);

      stubQuery(conn, TBL_ELEMENT, false, COL_ELEMENT, "PJ", "CC");
      stubQuery(conn, TBL_CLIENT_DIM, false, COL_DIMENSION, "CC");

      assertEquals(Collections.singleton(AccountingDimensionsSupport.DIM_PROJECT),
          AccountingDimensionsSupport.activeHeaderDimensionsForCurrentClient(FAT));
    }
  }

  /**
   * With no usable session context the current-client lookup fails soft: an empty set, no
   * exception escaping to the caller.
   *
   * @throws Exception if the call under test declares it
   */
  @Test
  public void testActiveHeaderDimensionsForCurrentClientWithNoContextReturnsEmptySet()
      throws Exception {
    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
         MockedStatic<OBContext> obContext = mockStatic(OBContext.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      // getOBContext() answers null (Mockito default) → the lookup throws and is swallowed.
      obContext.when(OBContext::getOBContext).thenReturn(null);

      assertTrue(AccountingDimensionsSupport.activeHeaderDimensionsForCurrentClient(FAT).isEmpty());
    }
  }

  // ── applyRuleDimensions ────────────────────────────────────────────────────

  /**
   * REGRESSION (ETP-4950): with all three dimensions active, the rule's project, cost center and
   * product are resolved and set on the generated transaction. Before the fix nothing read these
   * three keys, so the movement came out without them.
   *
   * @throws Exception if the JSON plumbing fails
   */
  @Test
  public void testApplyRuleDimensionsAssignsEveryActiveDimension() throws Exception {
    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      OBDal dal = dalOnly(obDal);
      Project project = mock(Project.class);
      Costcenter costcenter = mock(Costcenter.class);
      Product product = mock(Product.class);
      when(dal.get(eq(Project.class), eq(PROJECT_ID))).thenReturn(project);
      when(dal.get(eq(Costcenter.class), eq(COSTCENTER_ID))).thenReturn(costcenter);
      when(dal.get(eq(Product.class), eq(PRODUCT_ID))).thenReturn(product);

      FIN_FinaccTransaction trx = mock(FIN_FinaccTransaction.class);
      AccountingDimensionsSupport.applyRuleDimensions(trx,
          spec(PROJECT_ID, COSTCENTER_ID, PRODUCT_ID), allowing(allThreeDimensions()));

      verify(trx).setProject(project);
      verify(trx).setCostCenter(costcenter);
      verify(trx).setProduct(product);
    }
  }

  /**
   * THE TICKET'S FUNCTIONAL REQUIREMENT: a dimension that is not active for the tenant is skipped
   * while the remaining ones are still applied — and the inactive dimension's entity is never even
   * loaded, so an id left on a rule after the dimension was switched off costs nothing.
   *
   * @throws Exception if the JSON plumbing fails
   */
  @Test
  public void testApplyRuleDimensionsSkipsTheDimensionThatIsNotActive() throws Exception {
    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      OBDal dal = dalOnly(obDal);
      Costcenter costcenter = mock(Costcenter.class);
      Product product = mock(Product.class);
      when(dal.get(eq(Costcenter.class), eq(COSTCENTER_ID))).thenReturn(costcenter);
      when(dal.get(eq(Product.class), eq(PRODUCT_ID))).thenReturn(product);

      FIN_FinaccTransaction trx = mock(FIN_FinaccTransaction.class);
      // Project switched off in the Accounting Schema; cost center and product still active.
      AccountingDimensionsSupport.applyRuleDimensions(trx,
          spec(PROJECT_ID, COSTCENTER_ID, PRODUCT_ID),
          allowing(setOf(AccountingDimensionsSupport.DIM_COSTCENTER,
              AccountingDimensionsSupport.DIM_PRODUCT)));

      verify(trx, never()).setProject(any());
      verify(dal, never()).get(eq(Project.class), anyString());
      verify(trx).setCostCenter(costcenter);
      verify(trx).setProduct(product);
    }
  }

  /**
   * With no dimension active, nothing is assigned and no entity is loaded — the transaction keeps
   * exactly the pre-ETP-4950 shape.
   *
   * @throws Exception if the JSON plumbing fails
   */
  @Test
  public void testApplyRuleDimensionsAssignsNothingWhenNoDimensionIsActive() throws Exception {
    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      OBDal dal = dalOnly(obDal);

      FIN_FinaccTransaction trx = mock(FIN_FinaccTransaction.class);
      AccountingDimensionsSupport.applyRuleDimensions(trx,
          spec(PROJECT_ID, COSTCENTER_ID, PRODUCT_ID), allowing(Collections.emptySet()));

      verify(trx, never()).setProject(any());
      verify(trx, never()).setCostCenter(any());
      verify(trx, never()).setProduct(any());
      verify(dal, never()).get(eq(Project.class), anyString());
      verify(dal, never()).get(eq(Costcenter.class), anyString());
      verify(dal, never()).get(eq(Product.class), anyString());
    }
  }

  /**
   * An active dimension the rule leaves blank (the empty string
   * {@code AutoMatchSupport.putRuleDimensions} emits) is not assigned, and does not trip a lookup
   * for a blank id.
   *
   * @throws Exception if the JSON plumbing fails
   */
  @Test
  public void testApplyRuleDimensionsIgnoresBlankIdsOnActiveDimensions() throws Exception {
    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      OBDal dal = dalOnly(obDal);
      Product product = mock(Product.class);
      when(dal.get(eq(Product.class), eq(PRODUCT_ID))).thenReturn(product);

      FIN_FinaccTransaction trx = mock(FIN_FinaccTransaction.class);
      AccountingDimensionsSupport.applyRuleDimensions(trx, spec("", "   ", PRODUCT_ID),
          allowing(allThreeDimensions()));

      verify(trx, never()).setProject(any());
      verify(trx, never()).setCostCenter(any());
      verify(dal, never()).get(eq(Project.class), anyString());
      verify(dal, never()).get(eq(Costcenter.class), anyString());
      verify(trx).setProduct(product);
    }
  }

  /**
   * A spec that omits the dimension keys entirely (an older suggestion payload replayed) is handled
   * without a null being pushed onto the transaction.
   *
   * @throws Exception if the JSON plumbing fails
   */
  @Test
  public void testApplyRuleDimensionsToleratesASpecWithoutDimensionKeys() throws Exception {
    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      dalOnly(obDal);

      FIN_FinaccTransaction trx = mock(FIN_FinaccTransaction.class);
      AccountingDimensionsSupport.applyRuleDimensions(trx,
          new JSONObject().put("glItemId", "GL-1"), allowing(allThreeDimensions()));

      verify(trx, never()).setProject(any());
      verify(trx, never()).setCostCenter(any());
      verify(trx, never()).setProduct(any());
    }
  }

  /**
   * An active dimension whose referenced record no longer exists leaves the transaction untouched
   * rather than clearing it with a null.
   *
   * @throws Exception if the JSON plumbing fails
   */
  @Test
  public void testApplyRuleDimensionsSkipsADimensionWhoseRecordIsGone() throws Exception {
    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      OBDal dal = dalOnly(obDal);
      when(dal.get(eq(Project.class), eq(PROJECT_ID))).thenReturn(null);

      FIN_FinaccTransaction trx = mock(FIN_FinaccTransaction.class);
      AccountingDimensionsSupport.applyRuleDimensions(trx,
          spec(PROJECT_ID, COSTCENTER_ID, PRODUCT_ID),
          allowing(Collections.singleton(AccountingDimensionsSupport.DIM_PROJECT)));

      verify(dal).get(eq(Project.class), eq(PROJECT_ID));
      verify(trx, never()).setProject(any());
    }
  }

  // ── applyRuleDimensions: lazy configuration resolution ────────────────────

  /**
   * A spec that asks for no dimension must not resolve the account's configuration at all: the
   * guard returns before the supplier is consulted, so the difference postings built by
   * {@code ReconciliationDifferenceSupport} — which never carry a dimension — pay nothing for a
   * feature they do not use.
   *
   * @throws Exception if the JSON plumbing fails
   */
  @Test
  public void testApplyRuleDimensionsDoesNotConsultTheSupplierWhenSpecHasNoDimensionKeys()
      throws Exception {
    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      dalOnly(obDal);

      FIN_FinaccTransaction trx = mock(FIN_FinaccTransaction.class);
      CountingSupplier allowed = allowing(allThreeDimensions());
      AccountingDimensionsSupport.applyRuleDimensions(trx,
          new JSONObject().put("glItemId", "GL-1"), allowed);

      assertEquals("the dimension configuration must not be resolved", 0, allowed.calls());
      verify(trx, never()).setProject(any());
      verify(trx, never()).setCostCenter(any());
      verify(trx, never()).setProduct(any());
    }
  }

  /**
   * THE REAL-WORLD CASE: every matching rule emits all three dimension keys, blank ones as the
   * empty string ({@code AutoMatchSupport.putRuleDimensions} uses
   * {@code defaultIfBlank(id, "")}, never null). So a rule without dimensions arrives here with
   * three present-but-empty keys, and that shape must still skip the configuration lookup —
   * checking key presence instead of key emptiness would resolve the configuration for every
   * single rule match.
   *
   * @throws Exception if the JSON plumbing fails
   */
  @Test
  public void testApplyRuleDimensionsDoesNotConsultTheSupplierWhenEveryDimensionIsBlank()
      throws Exception {
    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      dalOnly(obDal);

      FIN_FinaccTransaction trx = mock(FIN_FinaccTransaction.class);
      CountingSupplier allowed = allowing(allThreeDimensions());
      // Exactly what AutoMatchSupport.putRuleDimensions emits for a rule with no dimensions.
      AccountingDimensionsSupport.applyRuleDimensions(trx, spec("", "", ""), allowed);

      assertEquals("the dimension configuration must not be resolved", 0, allowed.calls());
      verify(trx, never()).setProject(any());
      verify(trx, never()).setCostCenter(any());
      verify(trx, never()).setProduct(any());
    }
  }

  /**
   * A supplier that would blow up if consulted proves the guard short-circuits before it, not
   * merely that its result is unused: the caller's resolution (SQL, DAL, memo) is genuinely never
   * entered for a dimensionless spec.
   *
   * @throws Exception if the JSON plumbing fails
   */
  @Test
  public void testApplyRuleDimensionsNeverConsultsAnExplodingSupplierForADimensionlessSpec()
      throws Exception {
    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      dalOnly(obDal);

      FIN_FinaccTransaction trx = mock(FIN_FinaccTransaction.class);
      AccountingDimensionsSupport.applyRuleDimensions(trx, spec("", "", ""), () -> {
        throw new AssertionError("the dimension supplier must not be consulted");
      });

      verify(trx, never()).setProduct(any());
    }
  }

  /**
   * The guard is a short-circuit, not a switch-off: a single non-blank dimension is enough to
   * consult the supplier (exactly once) and to assign that dimension.
   *
   * @throws Exception if the JSON plumbing fails
   */
  @Test
  public void testApplyRuleDimensionsConsultsTheSupplierOnceWhenASingleDimensionIsRequested()
      throws Exception {
    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      OBDal dal = dalOnly(obDal);
      Product product = mock(Product.class);
      when(dal.get(eq(Product.class), eq(PRODUCT_ID))).thenReturn(product);

      FIN_FinaccTransaction trx = mock(FIN_FinaccTransaction.class);
      CountingSupplier allowed = allowing(allThreeDimensions());
      AccountingDimensionsSupport.applyRuleDimensions(trx, spec("", "", PRODUCT_ID), allowed);

      assertEquals("one resolution for the whole spec, not one per dimension", 1, allowed.calls());
      verify(trx).setProduct(product);
      verify(trx, never()).setProject(any());
      verify(trx, never()).setCostCenter(any());
    }
  }

  /**
   * {@code requestsAnyDimension} — the guard itself — is true only when at least one dimension id
   * is non-blank, whatever the mix of absent, empty and whitespace-only keys around it.
   *
   * @throws Exception if the JSON plumbing fails
   */
  @Test
  public void testRequestsAnyDimensionIsTrueOnlyForANonBlankDimensionId() throws Exception {
    assertFalse(AccountingDimensionsSupport.requestsAnyDimension(new JSONObject()));
    assertFalse(AccountingDimensionsSupport.requestsAnyDimension(spec("", "", "")));
    assertFalse(AccountingDimensionsSupport.requestsAnyDimension(spec(" ", "  ", "\t")));
    assertTrue(AccountingDimensionsSupport.requestsAnyDimension(spec(PROJECT_ID, "", "")));
    assertTrue(AccountingDimensionsSupport.requestsAnyDimension(spec("", COSTCENTER_ID, "")));
    assertTrue(AccountingDimensionsSupport.requestsAnyDimension(spec("", "", PRODUCT_ID)));
  }

  // ── toOrderedArray ─────────────────────────────────────────────────────────

  /**
   * The payload order is {@code DIM_ORDER}'s, never the set's own iteration order: the source set
   * is a hash/linked set built the other way round, and the array must still come out canonical so
   * the frontend renders the dimensions in a stable sequence.
   */
  @Test
  public void testToOrderedArrayFollowsTheCanonicalOrderNotTheSetIterationOrder() {
    Set<String> reversed = new LinkedHashSet<>();
    for (int i = AccountingDimensionsSupport.DIM_ORDER.size() - 1; i >= 0; i--) {
      reversed.add(AccountingDimensionsSupport.DIM_ORDER.get(i));
    }

    assertEquals(AccountingDimensionsSupport.DIM_ORDER,
        asList(AccountingDimensionsSupport.toOrderedArray(reversed)));
  }

  /**
   * Keys absent from the set are omitted, and a key that is not a known dimension is not echoed
   * back — the array only ever contains {@code DIM_ORDER} entries, in that order.
   */
  @Test
  public void testToOrderedArrayOmitsAbsentAndUnknownKeys() {
    Set<String> subset = new LinkedHashSet<>();
    subset.add(AccountingDimensionsSupport.DIM_PRODUCT);
    subset.add("not-a-dimension");
    subset.add(AccountingDimensionsSupport.DIM_ORGANIZATION);

    assertEquals(Arrays.asList(AccountingDimensionsSupport.DIM_ORGANIZATION,
            AccountingDimensionsSupport.DIM_PRODUCT),
        asList(AccountingDimensionsSupport.toOrderedArray(subset)));
  }

  /** An empty dimension set serializes to an empty array, never to a null payload. */
  @Test
  public void testToOrderedArrayOnAnEmptySetYieldsAnEmptyArray() {
    JSONArray arr = AccountingDimensionsSupport.toOrderedArray(Collections.emptySet());

    assertEquals(0, arr.length());
    assertEquals(Collections.emptyList(), asList(arr));
  }

  // ── constant tables ────────────────────────────────────────────────────────

  /**
   * {@code DIM_ORDER} must list every dimension {@code DIM_BY_ELEMENT} can produce (and nothing
   * else): the payload builders iterate {@code DIM_ORDER}, so a code added to only one of the two
   * tables would silently disappear from every dimension payload.
   */
  @Test
  public void testDimensionOrderCoversEveryMappedElementCode() {
    assertEquals(new HashSet<>(AccountingDimensionsSupport.DIM_BY_ELEMENT.values()),
        new HashSet<>(AccountingDimensionsSupport.DIM_ORDER));
    assertEquals(AccountingDimensionsSupport.DIM_ORDER.size(),
        new HashSet<>(AccountingDimensionsSupport.DIM_ORDER).size());
  }

  /** The three dimensions a matching rule can carry are all mapped and ordered. */
  @Test
  public void testRuleDimensionsAreMappedFromCoreElementCodes() {
    assertEquals(AccountingDimensionsSupport.DIM_PROJECT,
        AccountingDimensionsSupport.DIM_BY_ELEMENT.get("PJ"));
    assertEquals(AccountingDimensionsSupport.DIM_COSTCENTER,
        AccountingDimensionsSupport.DIM_BY_ELEMENT.get("CC"));
    assertEquals(AccountingDimensionsSupport.DIM_PRODUCT,
        AccountingDimensionsSupport.DIM_BY_ELEMENT.get("PR"));
  }
}
