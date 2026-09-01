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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.codehaus.jettison.json.JSONObject;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.financialmgmt.payment.FIN_FinaccTransaction;

/**
 * Unit tests for {@link ReconciliationHandler}'s share of the accounting-dimension step introduced
 * by ETP-4950: {@code headerDimensionsOf}, the per-instance memo of a financial account's active
 * header dimensions, which is what the handler feeds to
 * {@code AccountingDimensionsSupport.applyRuleDimensions} as its lazy dimension supplier.
 *
 * <p>The assignment behaviour itself (which dimension is copied onto the generated
 * {@code FIN_FinaccTransaction}, and the laziness of the supplier) lives with the code, in
 * {@code AccountingDimensionsSupportTest}. What remains handler-specific, and is covered here:
 *
 * <ul>
 *   <li>the configuration is resolved once per account, not once per reconciled line;</li>
 *   <li>distinct accounts are memoized independently;</li>
 *   <li>a configuration-lookup failure fails OPEN (no dimensions, no exception) so the whole
 *       reconciliation does not die with it — and that failure is memoized too;</li>
 *   <li>the memo is scoped to the handler instance, so a configuration change is picked up by the
 *       next request;</li>
 *   <li>the end-to-end non-regression for the suite hang: wiring the REAL memo behind the lazy
 *       supplier must still not touch JDBC for a dimensionless spec.</li>
 * </ul>
 *
 * <p>{@code headerDimensionsOf} is a package-private seam, so it is driven directly instead of
 * through the whole {@code createTransactionForRule} path (which also needs {@code OBProvider}, the
 * line-number query and Classic's transaction process).
 */
@RunWith(MockitoJUnitRunner.Silent.class)
public class ReconciliationHandlerRuleDimensionsTest {

  private static final String FAT = AccountingDimensionsSupport.DOCBASETYPE_FAT;

  private static final String ACCOUNT_ID = "ACC-1";
  private static final String OTHER_ACCOUNT_ID = "ACC-2";

  /**
   * Releases the inline mock-maker references created during the test, so they do not accumulate
   * across the module's single test JVM.
   */
  @After
  public void clearMocks() {
    Mockito.framework().clearInlineMocks();
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

  // ── the handler's real supplier behind the lazy guard ─────────────────────

  /**
   * NON-REGRESSION (suite hang): the no-dimension path must not touch the JDBC connection, wired
   * exactly as {@code createTransactionForRule} wires it —
   * {@code applyRuleDimensions(trx, spec, () -> headerDimensionsOf(account.getId()))} — with the
   * REAL memo behind the supplier instead of a stub.
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
   * terminating {@code false} — and drives {@code applyRuleDimensions} through a supplier that
   * really calls {@code headerDimensionsOf}, so removing the guard would genuinely enter the SQL.
   * The {@code timeout} IS THE ASSERTION that matters: if the guard is ever removed, this test
   * FAILS by timeout rather than hanging the build, and the failure message points straight at the
   * cause.
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
      // The real (unstubbed) memo behind the supplier, so the real resolution — and its JDBC
      // queries — would run if the guard were gone.
      ReconciliationHandler handler = new ReconciliationHandler();
      AtomicInteger resolutions = new AtomicInteger();
      // Exactly what AutoMatchSupport.putRuleDimensions emits for a rule with no dimensions.
      JSONObject spec = new JSONObject()
          .put(AutoMatchSupport.KEY_PROJECT_ID, "")
          .put(AutoMatchSupport.KEY_COSTCENTER_ID, "")
          .put(AutoMatchSupport.KEY_PRODUCT_ID, "");

      AccountingDimensionsSupport.applyRuleDimensions(trx, spec, () -> {
        resolutions.incrementAndGet();
        return handler.headerDimensionsOf(ACCOUNT_ID);
      });

      assertEquals("the dimension configuration must not be resolved", 0, resolutions.get());
      verify(dal, never()).getConnection();
      verify(conn, never()).prepareStatement(anyString());
      verify(trx, never()).setProject(any());
      verify(trx, never()).setCostCenter(any());
      verify(trx, never()).setProduct(any());
    }
  }
}
