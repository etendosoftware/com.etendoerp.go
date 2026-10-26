/*
 * *************************************************************************
 * Etendo License. See https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * *************************************************************************
 */
package com.etendoerp.go.payment.reconcile;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;

import java.util.List;

import org.codehaus.jettison.json.JSONObject;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.openbravo.dal.service.OBDal;

/**
 * Specs for the billing reconciliation's shared helpers, {@link ReconciliationSupport}: the
 * allow-listed payload summary and the per-item failure handling that keeps one bad row from
 * stopping a run.
 *
 * @covers com.etendoerp.go.payment.reconcile.ReconciliationSupport
 */
public class ReconciliationSupportTest {

  @Test
  public void theSummaryKeepsNonNullPairsAndLeavesNullValuesOut() throws Exception {
    JSONObject summary = new JSONObject(ReconciliationSupport.summary("session", "cs_test_1",
        "customer", null, "subscription", "sub_1", "dangling"));

    assertEquals(2, summary.length());
    assertEquals("cs_test_1", summary.getString("session"));
    assertEquals("sub_1", summary.getString("subscription"));
  }

  @Test
  public void aFailedItemIsRolledBackAndRecordedAsAnErrorOfTheRun() {
    OBDal dal = mock(OBDal.class);
    ReconciliationResult result = new ReconciliationResult(false);
    try (MockedStatic<OBDal> dalStatic = mockStatic(OBDal.class)) {
      dalStatic.when(OBDal::getInstance).thenReturn(dal);

      ReconciliationSupport.itemFailed(result, "checkout req-1",
          new IllegalStateException("boom"));

      verify(dal).rollbackAndClose();
    }
    assertEquals(List.of("checkout req-1: IllegalStateException"), result.getErrors());
  }

  @Test
  public void aFailingRollbackDoesNotEscape() {
    OBDal dal = mock(OBDal.class);
    doThrow(new IllegalStateException("closed")).when(dal).rollbackAndClose();
    try (MockedStatic<OBDal> dalStatic = mockStatic(OBDal.class)) {
      dalStatic.when(OBDal::getInstance).thenReturn(dal);

      // The contract is that nothing escapes: a throw here fails the test.
      ReconciliationSupport.rollbackQuietly();

      verify(dal).rollbackAndClose();
    }
  }
}
