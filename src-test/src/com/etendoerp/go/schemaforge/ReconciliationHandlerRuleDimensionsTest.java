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
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import org.codehaus.jettison.json.JSONObject;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.plm.Product;
import org.openbravo.model.financialmgmt.accounting.Costcenter;
import org.openbravo.model.financialmgmt.payment.FIN_FinaccTransaction;
import org.openbravo.model.financialmgmt.payment.FIN_FinancialAccount;
import org.openbravo.model.project.Project;

/**
 * Unit tests for the accounting-dimension step of {@link ReconciliationHandler} (ETP-4950):
 * {@code applyRuleDimensions} — which copies a matching rule's project / cost center / product onto
 * the {@code FIN_FinaccTransaction} Automatch generates — and {@code headerDimensionsOf}, the
 * per-instance memo of the account's active header dimensions.
 *
 * <p>Both are package-private seams, so they are driven directly instead of through the whole
 * {@code createTransactionForRule} path (which also needs {@code OBProvider}, the line-number query
 * and Classic's transaction process). The interesting behaviours are:
 *
 * <ul>
 *   <li>an ACTIVE dimension declared by the rule is assigned;</li>
 *   <li>an INACTIVE dimension is silently skipped — the ticket's functional requirement — and its
 *       entity is not even loaded;</li>
 *   <li>the configuration is resolved once per account, not once per line;</li>
 *   <li>a configuration-lookup failure fails OPEN (no dimensions, no exception) so the whole
 *       reconciliation does not die with it.</li>
 *   <li>the configuration is resolved LAZILY — a spec that asks for no dimension (the shape every
 *       rule without dimensions produces, and the one the difference postings use) must not run a
 *       single configuration query.</li>
 * </ul>
 */
@RunWith(MockitoJUnitRunner.Silent.class)
public class ReconciliationHandlerRuleDimensionsTest {

  private static final String FAT = AccountingDimensionsSupport.DOCBASETYPE_FAT;

  private static final String ACCOUNT_ID = "ACC-1";
  private static final String OTHER_ACCOUNT_ID = "ACC-2";
  private static final String PROJECT_ID = "PJ-1";
  private static final String COSTCENTER_ID = "CC-1";
  private static final String PRODUCT_ID = "PR-1";

  /**
   * Releases the inline mock-maker references created during the test, so they do not accumulate
   * across the module's single test JVM.
   */
  @After
  public void clearMocks() {
    Mockito.framework().clearInlineMocks();
  }

  // ── fixtures ───────────────────────────────────────────────────────────────

  private static FIN_FinancialAccount account() {
    FIN_FinancialAccount account = mock(FIN_FinancialAccount.class);
    when(account.getId()).thenReturn(ACCOUNT_ID);
    return account;
  }

  /** The {@code createPayment} spec shape {@code AutoMatchSupport.putRuleDimensions} emits. */
  private static JSONObject spec(String projectId, String costcenterId, String productId)
      throws Exception {
    return new JSONObject()
        .put(AutoMatchSupport.KEY_PROJECT_ID, projectId)
        .put(AutoMatchSupport.KEY_COSTCENTER_ID, costcenterId)
        .put(AutoMatchSupport.KEY_PRODUCT_ID, productId);
  }

  private static Set<String> dims(String... keys) {
    return new HashSet<>(Arrays.asList(keys));
  }

  private static Set<String> allThreeDimensions() {
    return dims(AccountingDimensionsSupport.DIM_PROJECT,
        AccountingDimensionsSupport.DIM_COSTCENTER,
        AccountingDimensionsSupport.DIM_PRODUCT);
  }

  /** A handler whose dimension resolution is pinned to {@code allowed} for {@link #ACCOUNT_ID}. */
  private static ReconciliationHandler handlerAllowing(Set<String> allowed) {
    ReconciliationHandler handler = spy(new ReconciliationHandler());
    doReturn(allowed).when(handler).headerDimensionsOf(ACCOUNT_ID);
    return handler;
  }

  // ── applyRuleDimensions ───────────────────────────────────────────────────

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
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      Project project = mock(Project.class);
      Costcenter costcenter = mock(Costcenter.class);
      Product product = mock(Product.class);
      when(dal.get(eq(Project.class), eq(PROJECT_ID))).thenReturn(project);
      when(dal.get(eq(Costcenter.class), eq(COSTCENTER_ID))).thenReturn(costcenter);
      when(dal.get(eq(Product.class), eq(PRODUCT_ID))).thenReturn(product);

      FIN_FinaccTransaction trx = mock(FIN_FinaccTransaction.class);
      handlerAllowing(allThreeDimensions()).applyRuleDimensions(trx, account(),
          spec(PROJECT_ID, COSTCENTER_ID, PRODUCT_ID));

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
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      Costcenter costcenter = mock(Costcenter.class);
      Product product = mock(Product.class);
      when(dal.get(eq(Costcenter.class), eq(COSTCENTER_ID))).thenReturn(costcenter);
      when(dal.get(eq(Product.class), eq(PRODUCT_ID))).thenReturn(product);

      FIN_FinaccTransaction trx = mock(FIN_FinaccTransaction.class);
      // Project switched off in the Accounting Schema; cost center and product still active.
      handlerAllowing(dims(AccountingDimensionsSupport.DIM_COSTCENTER,
          AccountingDimensionsSupport.DIM_PRODUCT))
          .applyRuleDimensions(trx, account(), spec(PROJECT_ID, COSTCENTER_ID, PRODUCT_ID));

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
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);

      FIN_FinaccTransaction trx = mock(FIN_FinaccTransaction.class);
      handlerAllowing(Collections.emptySet()).applyRuleDimensions(trx, account(),
          spec(PROJECT_ID, COSTCENTER_ID, PRODUCT_ID));

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
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      Product product = mock(Product.class);
      when(dal.get(eq(Product.class), eq(PRODUCT_ID))).thenReturn(product);

      FIN_FinaccTransaction trx = mock(FIN_FinaccTransaction.class);
      handlerAllowing(allThreeDimensions()).applyRuleDimensions(trx, account(),
          spec("", "   ", PRODUCT_ID));

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
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);

      FIN_FinaccTransaction trx = mock(FIN_FinaccTransaction.class);
      handlerAllowing(allThreeDimensions()).applyRuleDimensions(trx, account(),
          new JSONObject().put("glItemId", "GL-1"));

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
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(eq(Project.class), eq(PROJECT_ID))).thenReturn(null);

      FIN_FinaccTransaction trx = mock(FIN_FinaccTransaction.class);
      handlerAllowing(Collections.singleton(AccountingDimensionsSupport.DIM_PROJECT))
          .applyRuleDimensions(trx, account(), spec(PROJECT_ID, COSTCENTER_ID, PRODUCT_ID));

      verify(dal).get(eq(Project.class), eq(PROJECT_ID));
      verify(trx, never()).setProject(any());
    }
  }

  // ── headerDimensionsOf ────────────────────────────────────────────────────

  /**
   * The account's configuration is resolved once and memoized for the lifetime of the handler: an
   * {@code applySuggestions} batch over many lines of the same account must not re-read it per
   * line.
   */
  @Test
  public void testHeaderDimensionsOfMemoizesPerAccount() {
    try (MockedStatic<AccountingDimensionsSupport> dims =
             mockStatic(AccountingDimensionsSupport.class)) {
      dims.when(() -> AccountingDimensionsSupport.activeHeaderDimensionsForAccount(ACCOUNT_ID, FAT))
          .thenReturn(Collections.singleton(AccountingDimensionsSupport.DIM_PROJECT));

      ReconciliationHandler handler = new ReconciliationHandler();
      Set<String> first = handler.headerDimensionsOf(ACCOUNT_ID);
      Set<String> second = handler.headerDimensionsOf(ACCOUNT_ID);

      assertEquals(Collections.singleton(AccountingDimensionsSupport.DIM_PROJECT), first);
      assertSame("the memo must hand back the very same set", first, second);
      dims.verify(
          () -> AccountingDimensionsSupport.activeHeaderDimensionsForAccount(ACCOUNT_ID, FAT),
          times(1));
    }
  }

  /** Distinct accounts are memoized independently — one entry must not answer for another. */
  @Test
  public void testHeaderDimensionsOfKeepsOneEntryPerAccount() {
    try (MockedStatic<AccountingDimensionsSupport> dims =
             mockStatic(AccountingDimensionsSupport.class)) {
      dims.when(() -> AccountingDimensionsSupport.activeHeaderDimensionsForAccount(ACCOUNT_ID, FAT))
          .thenReturn(Collections.singleton(AccountingDimensionsSupport.DIM_PROJECT));
      dims.when(() -> AccountingDimensionsSupport.activeHeaderDimensionsForAccount(
              OTHER_ACCOUNT_ID, FAT))
          .thenReturn(Collections.singleton(AccountingDimensionsSupport.DIM_PRODUCT));

      ReconciliationHandler handler = new ReconciliationHandler();

      assertEquals(Collections.singleton(AccountingDimensionsSupport.DIM_PROJECT),
          handler.headerDimensionsOf(ACCOUNT_ID));
      assertEquals(Collections.singleton(AccountingDimensionsSupport.DIM_PRODUCT),
          handler.headerDimensionsOf(OTHER_ACCOUNT_ID));
      dims.verify(
          () -> AccountingDimensionsSupport.activeHeaderDimensionsForAccount(ACCOUNT_ID, FAT),
          times(1));
      dims.verify(() -> AccountingDimensionsSupport.activeHeaderDimensionsForAccount(
          OTHER_ACCOUNT_ID, FAT), times(1));
    }
  }

  /**
   * A configuration-lookup failure fails OPEN: an empty set, no exception propagated, so a tenant
   * with an unreadable accounting setup still gets its reconciliation (just without dimensions).
   */
  @Test
  public void testHeaderDimensionsOfFailsOpenWhenResolutionThrows() {
    try (MockedStatic<AccountingDimensionsSupport> dims =
             mockStatic(AccountingDimensionsSupport.class)) {
      dims.when(() -> AccountingDimensionsSupport.activeHeaderDimensionsForAccount(ACCOUNT_ID, FAT))
          .thenThrow(new IllegalStateException("no accounting schema"));

      assertTrue(new ReconciliationHandler().headerDimensionsOf(ACCOUNT_ID).isEmpty());
    }
  }

  /**
   * A failed resolution is also memoized, so a broken configuration is not re-queried once per line
   * of the batch.
   */
  @Test
  public void testHeaderDimensionsOfMemoizesTheFailOpenResult() {
    try (MockedStatic<AccountingDimensionsSupport> dims =
             mockStatic(AccountingDimensionsSupport.class)) {
      dims.when(() -> AccountingDimensionsSupport.activeHeaderDimensionsForAccount(ACCOUNT_ID, FAT))
          .thenThrow(new IllegalStateException("no accounting schema"));

      ReconciliationHandler handler = new ReconciliationHandler();
      assertTrue(handler.headerDimensionsOf(ACCOUNT_ID).isEmpty());
      assertTrue(handler.headerDimensionsOf(ACCOUNT_ID).isEmpty());

      dims.verify(
          () -> AccountingDimensionsSupport.activeHeaderDimensionsForAccount(ACCOUNT_ID, FAT),
          times(1));
    }
  }

  /**
   * A fresh handler starts with an empty memo, so a configuration change is picked up by the next
   * request ({@code NeoHandler} beans are {@code @Dependent} — one instance per request).
   */
  @Test
  public void testHeaderDimensionsOfMemoIsScopedToTheHandlerInstance() {
    try (MockedStatic<AccountingDimensionsSupport> dims =
             mockStatic(AccountingDimensionsSupport.class)) {
      dims.when(() -> AccountingDimensionsSupport.activeHeaderDimensionsForAccount(ACCOUNT_ID, FAT))
          .thenReturn(Collections.singleton(AccountingDimensionsSupport.DIM_PROJECT));

      new ReconciliationHandler().headerDimensionsOf(ACCOUNT_ID);
      new ReconciliationHandler().headerDimensionsOf(ACCOUNT_ID);

      dims.verify(
          () -> AccountingDimensionsSupport.activeHeaderDimensionsForAccount(ACCOUNT_ID, FAT),
          times(2));
    }
  }

  // ── applyRuleDimensions: lazy configuration resolution ────────────────────

  /**
   * A spec that asks for no dimension must not resolve the account's configuration at all: the
   * guard returns before {@code headerDimensionsOf}, so the difference postings built by
   * {@code ReconciliationDifferenceSupport} — which never carry a dimension — pay nothing for a
   * feature they do not use.
   *
   * @throws Exception if the JSON plumbing fails
   */
  @Test
  public void testApplyRuleDimensionsDoesNotResolveConfigWhenSpecHasNoDimensionKeys()
      throws Exception {
    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);

      FIN_FinaccTransaction trx = mock(FIN_FinaccTransaction.class);
      ReconciliationHandler handler = handlerAllowing(allThreeDimensions());
      handler.applyRuleDimensions(trx, account(), new JSONObject().put("glItemId", "GL-1"));

      verify(handler, never()).headerDimensionsOf(anyString());
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
  public void testApplyRuleDimensionsDoesNotResolveConfigWhenEveryDimensionIsBlank()
      throws Exception {
    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);

      FIN_FinaccTransaction trx = mock(FIN_FinaccTransaction.class);
      ReconciliationHandler handler = handlerAllowing(allThreeDimensions());
      // Exactly what AutoMatchSupport.putRuleDimensions emits for a rule with no dimensions.
      handler.applyRuleDimensions(trx, account(), spec("", "", ""));

      verify(handler, never()).headerDimensionsOf(anyString());
      verify(trx, never()).setProject(any());
      verify(trx, never()).setCostCenter(any());
      verify(trx, never()).setProduct(any());
    }
  }

  /**
   * The guard is a short-circuit, not a switch-off: a single non-blank dimension is enough to
   * resolve the configuration (once) and to assign that dimension.
   *
   * @throws Exception if the JSON plumbing fails
   */
  @Test
  public void testApplyRuleDimensionsResolvesConfigWhenASingleDimensionIsRequested()
      throws Exception {
    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      Product product = mock(Product.class);
      when(dal.get(eq(Product.class), eq(PRODUCT_ID))).thenReturn(product);

      FIN_FinaccTransaction trx = mock(FIN_FinaccTransaction.class);
      ReconciliationHandler handler = handlerAllowing(allThreeDimensions());
      handler.applyRuleDimensions(trx, account(), spec("", "", PRODUCT_ID));

      verify(handler, times(1)).headerDimensionsOf(ACCOUNT_ID);
      verify(trx).setProduct(product);
      verify(trx, never()).setProject(any());
      verify(trx, never()).setCostCenter(any());
    }
  }

  /**
   * NON-REGRESSION (suite hang): the no-dimension path must not touch the JDBC connection.
   *
   * <p>The configuration queries in {@code AccountingDimensionsSupport} iterate with
   * {@code while (rs.next())}, and many tests across this module stub {@code rs.next()} with an
   * unbounded {@code thenReturn(true)} (see {@code ReconciliationHandlerTest}'s two direct
   * {@code createTransactionForRule} tests, which only need the line-number query). Without the
   * guard, {@code createTransactionForRule} resolved the dimension configuration unconditionally,
   * that unbounded stub turned the {@code while} into an INFINITE LOOP, and the whole
   * {@code ReconciliationHandlerTest} suite hung instead of failing.
   *
   * <p>So this test reproduces exactly that stub — {@code when(rs.next()).thenReturn(true)} with no
   * terminating {@code false} — and drives {@code applyRuleDimensions} with the real (unstubbed)
   * {@code headerDimensionsOf}. The {@code timeout} is the assertion that matters: if the guard is
   * ever removed, this test FAILS by timeout rather than hanging the build, and the failure message
   * points straight at the cause.
   *
   * @throws Exception if the JSON or JDBC plumbing fails
   */
  @Test(timeout = 5000)
  public void testApplyRuleDimensionsNeverTouchesJdbcWhenNoDimensionIsRequested() throws Exception {
    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);

      Connection conn = mock(Connection.class);
      PreparedStatement ps = mock(PreparedStatement.class);
      ResultSet rs = mock(ResultSet.class);
      when(dal.getConnection()).thenReturn(conn);
      when(conn.prepareStatement(anyString())).thenReturn(ps);
      when(ps.executeQuery()).thenReturn(rs);
      // The problematic stub: always another row, never an end.
      when(rs.next()).thenReturn(true);

      FIN_FinaccTransaction trx = mock(FIN_FinaccTransaction.class);
      // A spy WITHOUT headerDimensionsOf stubbed, so the real resolution (and its JDBC queries)
      // would run if the guard were gone.
      ReconciliationHandler handler = spy(new ReconciliationHandler());
      handler.applyRuleDimensions(trx, account(), spec("", "", ""));

      verify(handler, never()).headerDimensionsOf(anyString());
      verify(dal, never()).getConnection();
      verify(conn, never()).prepareStatement(anyString());
      verify(trx, never()).setProject(any());
      verify(trx, never()).setCostCenter(any());
      verify(trx, never()).setProduct(any());
    }
  }
}
