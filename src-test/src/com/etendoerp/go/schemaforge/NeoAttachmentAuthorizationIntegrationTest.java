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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeNotNull;

import java.util.List;

import org.hibernate.criterion.Restrictions;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.order.Order;
import org.openbravo.test.base.OBBaseTest;

import com.etendoerp.go.schemaforge.NeoAttachmentAuthorizer.Access;

/**
 * ETP-4570 — SEC-11b authorization matrix against a real database (ADR-0003 S1, S4, S5, S6, S7,
 * S8 with the sample data's F&amp;B and QA Testing clients).
 *
 * <p><b>Written, not run locally:</b> it needs a provisioned DB, so CI or a reviewer with a
 * database runs it. Scenarios that need seeded fixtures the sample data lacks (same-org user
 * without table access, a second organization of the same client) are skipped through
 * {@code assumeNotNull} and are NOT covered by this class.</p>
 *
 * <p>Every call runs inside admin mode — the mode {@code NeoServlet} opens — and again without
 * it, because the decision must be the same either way.</p>
 *
 * @covers com.etendoerp.go.schemaforge.NeoAttachmentAuthorizer
 */
public class NeoAttachmentAuthorizationIntegrationTest extends OBBaseTest {

  private static final String ORDER_TABLE = "C_Order";
  private static final String MISSING_ID = "00000000000000000000000000000000";

  private String ownOrderId;
  private String foreignOrderId;

  @Before
  public void loadFixtures() {
    OBContext.setAdminMode(true);
    try {
      ownOrderId = firstIdOfClient(TEST_CLIENT_ID);
      foreignOrderId = firstIdOfClient(QA_TEST_CLIENT_ID);
    } finally {
      OBContext.restorePreviousMode();
    }
    assumeNotNull("the sample data has no order of the F&B client", ownOrderId);
    assumeNotNull("the sample data has no order of the QA Testing client", foreignOrderId);
  }

  @After
  public void rollBack() {
    OBDal.getInstance().rollbackAndClose();
  }

  /** S1 / S8 — a record of the caller's own client is reachable, with or without admin mode. */
  @Test
  public void ownClientRecordIsAuthorizedWithAndWithoutAdminMode() {
    assertTrue(NeoAttachmentAuthorizer.authorizeByRecord(ORDER_TABLE, ownOrderId, Access.READ));
    OBContext.setAdminMode(true);
    try {
      assertTrue(NeoAttachmentAuthorizer.authorizeByRecord(ORDER_TABLE, ownOrderId, Access.READ));
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /** S4 — another client's record is refused, with or without admin mode. */
  @Test
  public void crossClientRecordIsRefusedWithAndWithoutAdminMode() {
    assertFalse(
        NeoAttachmentAuthorizer.authorizeByRecord(ORDER_TABLE, foreignOrderId, Access.READ));
    assertFalse(
        NeoAttachmentAuthorizer.authorizeByRecord(ORDER_TABLE, foreignOrderId, Access.WRITE));
    OBContext.setAdminMode(true);
    try {
      assertFalse(
          NeoAttachmentAuthorizer.authorizeByRecord(ORDER_TABLE, foreignOrderId, Access.READ));
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /** S6 — a record that does not exist is refused exactly like a foreign one. */
  @Test
  public void missingRecordIsRefusedLikeAForeignOne() {
    assertFalse(NeoAttachmentAuthorizer.authorizeByRecord(ORDER_TABLE, MISSING_ID, Access.READ));
  }

  /** S6 / S7 — an unknown attachment id, and a bare id never resolves a foreign parent. */
  @Test
  public void unknownAttachmentIdIsRefused() {
    assertNull(NeoAttachmentAuthorizer.authorizeById(MISSING_ID, Access.READ));
    assertNull(NeoAttachmentAuthorizer.authorizeById(MISSING_ID, ORDER_TABLE, ownOrderId,
        Access.READ));
  }

  /** S5 — an inactive parent is refused (the order is deactivated inside the rolled-back session). */
  @Test
  public void inactiveParentRecordIsRefused() {
    OBContext.setAdminMode(true);
    try {
      Order order = OBDal.getInstance().get(Order.class, ownOrderId);
      assertNotNull(order);
      order.setActive(false);
      OBDal.getInstance().save(order);
      OBDal.getInstance().flush();
      assertFalse(NeoAttachmentAuthorizer.authorizeByRecord(ORDER_TABLE, ownOrderId, Access.READ));
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  private static String firstIdOfClient(String clientId) {
    OBCriteria<Order> criteria = OBDal.getInstance().createCriteria(Order.class);
    criteria.setFilterOnReadableClients(false);
    criteria.setFilterOnReadableOrganization(false);
    criteria.add(Restrictions.eq("client.id", clientId));
    criteria.setMaxResults(1);
    List<Order> rows = criteria.list();
    return rows.isEmpty() ? null : (String) ((BaseOBObject) rows.get(0)).getId();
  }
}
