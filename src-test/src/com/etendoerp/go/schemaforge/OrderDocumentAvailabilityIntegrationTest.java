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

import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.test.base.OBBaseTest;

/**
 * Runs the HQL of {@link OrderDocumentAvailability} against the real Etendo model (ETP-5714).
 *
 * <p>A Mockito session accepts any query string, so a mistyped property ({@code salesOrderLine},
 * {@code salesTransaction}...) would only fail in production, as a {@code QueryException} that the
 * catalogue logs and answers as "nothing derived". Hibernate resolves every property of the query
 * when it compiles it, with no rows needed, so executing it empty is what proves the model names.
 * Read-only: it creates nothing and rolls back.</p>
 *
 * @covers com.etendoerp.go.schemaforge.OrderDocumentAvailability
 */
public class OrderDocumentAvailabilityIntegrationTest extends OBBaseTest {

  @Before
  public void setUp() {
    setTestAdminContext();
  }

  @After
  public void rollbackChanges() {
    while (OBContext.getOBContext() != null
        && OBContext.getOBContext().isInAdministratorMode()) {
      OBContext.restorePreviousMode();
    }
    OBDal.getInstance().rollbackAndClose();
  }

  @Test
  public void draftInvoicesQueryResolvesAgainstTheRealModelForBothSides() {
    for (boolean sales : new boolean[] { true, false }) {
      // production query, scoped to what the role reads: a nonexistent order has no drafts
      List<String> documentNos =
          OrderDocumentAvailability.draftInvoiceNumbers("NO-SUCH-ORDER", sales);

      assertTrue(documentNos.isEmpty());
    }
  }
}
