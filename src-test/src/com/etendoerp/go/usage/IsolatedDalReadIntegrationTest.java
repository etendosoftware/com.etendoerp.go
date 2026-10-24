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

package com.etendoerp.go.usage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.Test;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.test.base.OBBaseTest;

/**
 * <b>REQUIRES A DATABASE.</b> What only the real DAL can show about an isolated read: the worker
 * binds a context of its own for the caller's user, role, client and organization; its
 * transaction carries the 25 s {@code statement_timeout} while the caller's does not; and a
 * statement that fails inside the read leaves the caller's transaction usable — the reason the
 * class exists.
 *
 * <p>Read-only: the worker's session is rolled back by the read itself and the caller's in
 * {@link #rollBack()}. The pool is stopped in {@link #stopWorkers()}.
 *
 * @covers com.etendoerp.go.usage.IsolatedDalRead
 */
public class IsolatedDalReadIntegrationTest extends OBBaseTest {

  @After
  public void rollBack() {
    OBDal.getInstance().rollbackAndClose();
  }

  @AfterClass
  public static void stopWorkers() {
    IsolatedDalRead.shutdown();
  }

  private static String statementTimeout() {
    return String.valueOf(OBDal.getInstance().getSession()
        .createNativeQuery("show statement_timeout").uniqueResult());
  }

  @Test
  public void theWorkerHasItsOwnContextForTheCallersUserRoleClientOrgLanguageAndWarehouse() {
    setTestUserContext();
    OBContext caller = OBContext.getOBContext();

    Object[] seen = IsolatedDalRead.call(() -> {
      OBContext worker = OBContext.getOBContext();
      return new Object[] { worker, worker.getUser().getId(), worker.getRole().getId(),
          worker.getCurrentClient().getId(), worker.getCurrentOrganization().getId(),
          worker.getLanguage().getLanguage(),
          worker.getWarehouse() == null ? null : worker.getWarehouse().getId() };
    });

    assertNotSame("the worker must never share the caller's OBContext instance", caller, seen[0]);
    assertEquals(TEST_USER_ID, seen[1]);
    assertEquals(TEST_ROLE_ID, seen[2]);
    assertEquals(TEST_CLIENT_ID, seen[3]);
    assertEquals(TEST_ORG_ID, seen[4]);
    assertEquals("the caller's language (re-review N5)", caller.getLanguage().getLanguage(),
        seen[5]);
    assertEquals("the caller's warehouse (re-review N5)",
        caller.getWarehouse() == null ? null : caller.getWarehouse().getId(), seen[6]);
    assertSame("the caller keeps its own context", caller, OBContext.getOBContext());
  }

  @Test
  public void theWorkerTransactionCarriesTheStatementTimeoutAndTheCallersDoesNot() {
    String callerBefore = statementTimeout();

    String onWorker = IsolatedDalRead.call(IsolatedDalReadIntegrationTest::statementTimeout);

    assertEquals("25s", onWorker);
    assertFalse("this test needs a server default other than 25s", "25s".equals(callerBefore));
    assertEquals(callerBefore, statementTimeout());
  }

  @Test
  public void theWritePathWorkerCarriesItsOwnShorterStatementTimeout() {
    assertEquals("4s",
        IsolatedDalRead.callOnWritePath(IsolatedDalReadIntegrationTest::statementTimeout));
  }

  @Test
  public void aStatementFailingInTheReadLeavesTheCallersTransactionUsable() {
    setTestUserContext();
    assertEquals(1, ((Number) OBDal.getInstance().getSession()
        .createNativeQuery("select 1").uniqueResult()).intValue());

    try {
      IsolatedDalRead.call(() -> OBDal.getInstance().getSession()
          .createNativeQuery("select 1 / 0").uniqueResult());
      fail("the division by zero must fail the read");
    } catch (RuntimeException expected) {
      // the read's own failure reaches the caller
    }

    assertFalse(OBDal.getInstance().getSession().getTransaction().getRollbackOnly());
    assertEquals(2, ((Number) OBDal.getInstance().getSession()
        .createNativeQuery("select 2").uniqueResult()).intValue());
    assertEquals("a later isolated read still works", Integer.valueOf(3),
        IsolatedDalRead.call(() -> ((Number) OBDal.getInstance().getSession()
            .createNativeQuery("select 3").uniqueResult()).intValue()));
  }
}
