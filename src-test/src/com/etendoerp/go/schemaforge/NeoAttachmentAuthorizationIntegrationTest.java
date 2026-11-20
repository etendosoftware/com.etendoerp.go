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
import org.openbravo.base.provider.OBProvider;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.datamodel.Table;
import org.openbravo.model.ad.utility.Attachment;
import org.openbravo.model.common.order.Order;
import org.openbravo.test.base.OBBaseTest;

import com.etendoerp.go.schemaforge.NeoAttachmentAuthorizer.Access;

/**
 * ETP-4570 — SEC-11b authorization against a real database, with the sample data's F&amp;B and QA
 * Testing clients. Attachments are created inside the session, which is rolled back.
 *
 * <p><b>Written, not run locally:</b> it needs a provisioned DB, so CI or a reviewer with a
 * database runs it.</p>
 *
 * <p><b>Covered:</b> S1 (own-client parent and attachment, by record and by bare id, with and
 * without admin mode), S4 (foreign-client record and attachment, with and without admin mode on
 * the record route), S5 (inactive parent), S6 (missing record, missing attachment), S7 (supplied
 * table or record that does not match the attachment's real parent).</p>
 *
 * <p>S3 (an order of the F&amp;B client in an organization the test role cannot read) is covered
 * when the sample data has one; otherwise that test is skipped through {@code assumeNotNull}.</p>
 *
 * <p><b>Not covered:</b> S2 (same-org role without table access) and S8 (a multi-org administrator
 * role): the sample data offers no role fixture, and none is created here. They stay covered only by the mock-based
 * {@code NeoAttachmentParentAuthorizationTest}. The OCR consumer's own call path is not exercised.</p>
 *
 * @covers com.etendoerp.go.schemaforge.NeoAttachmentAuthorizer
 */
public class NeoAttachmentAuthorizationIntegrationTest extends OBBaseTest {

  private static final String ORDER_TABLE = "C_Order";
  private static final String C_ORDER_TABLE_ID = "259";
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

  /** S3 — an own-client order in an organization the role cannot read is refused (skipped if none exists). */
  @Test
  public void ownClientRecordInNonReadableOrganizationIsRefused() {
    String hiddenOrderId = firstUnreadableOrgOrderId();
    assumeNotNull("the sample data has no F&B order in an organization the test role cannot read",
        hiddenOrderId);
    assertFalse(NeoAttachmentAuthorizer.authorizeByRecord(ORDER_TABLE, hiddenOrderId, Access.READ));
  }

  /** S6 — a record that does not exist is refused exactly like a foreign one. */
  @Test
  public void missingRecordIsRefusedLikeAForeignOne() {
    assertFalse(NeoAttachmentAuthorizer.authorizeByRecord(ORDER_TABLE, MISSING_ID, Access.READ));
  }

  /** S6 — an unknown attachment id is refused. */
  @Test
  public void unknownAttachmentIdIsRefused() {
    assertNull(NeoAttachmentAuthorizer.authorizeById(MISSING_ID, Access.READ));
  }

  /** S1 — an attachment of an own-client order is authorized by bare id, with and without admin mode. */
  @Test
  public void ownAttachmentIsAuthorizedByBareId() {
    String id = createAttachment(ownOrderId);
    assertNotNull(NeoAttachmentAuthorizer.authorizeById(id, Access.READ));
    OBContext.setAdminMode(true);
    try {
      assertNotNull(NeoAttachmentAuthorizer.authorizeById(id, Access.READ));
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /** S7 — a supplied table or record that does not match the real parent is refused. */
  @Test
  public void suppliedContextNotMatchingTheRealParentIsRefused() {
    String id = createAttachment(ownOrderId);
    assertNotNull(NeoAttachmentAuthorizer.authorizeById(id, ORDER_TABLE, ownOrderId, Access.READ));
    assertNull(NeoAttachmentAuthorizer.authorizeById(id, ORDER_TABLE, foreignOrderId, Access.READ));
    assertNull(NeoAttachmentAuthorizer.authorizeById(id, "AD_User", ownOrderId, Access.READ));
  }

  /** S4 — an attachment hanging off another client's order is refused by bare id. */
  @Test
  public void foreignClientAttachmentIsRefusedByBareId() {
    String id = createAttachment(foreignOrderId);
    assertNull(NeoAttachmentAuthorizer.authorizeById(id, Access.READ));
    assertNull(NeoAttachmentAuthorizer.authorizeById(id, Access.WRITE));
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

  /** Creates (inside the rolled-back session) an attachment bound to the given order. */
  private static String createAttachment(String orderId) {
    // false: the fixture for a foreign client's order must not trip the org/client access check
    OBContext.setAdminMode(false);
    try {
      Order order = OBDal.getInstance().get(Order.class, orderId);
      Attachment attachment = OBProvider.getInstance().get(Attachment.class);
      attachment.setClient(order.getClient());
      attachment.setOrganization(order.getOrganization());
      attachment.setTable(OBDal.getInstance().get(Table.class, C_ORDER_TABLE_ID));
      attachment.setRecord(orderId);
      attachment.setName("etp4570.txt");
      attachment.setPath("etp4570");
      attachment.setSequenceNumber(10L);
      OBDal.getInstance().save(attachment);
      OBDal.getInstance().flush();
      return attachment.getId();
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  private static String firstUnreadableOrgOrderId() {
    String[] readable = OBContext.getOBContext().getReadableOrganizations();
    OBContext.setAdminMode(true);
    try {
      OBCriteria<Order> criteria = OBDal.getInstance().createCriteria(Order.class);
      criteria.setFilterOnReadableClients(false);
      criteria.setFilterOnReadableOrganization(false);
      criteria.add(Restrictions.eq("client.id", TEST_CLIENT_ID));
      criteria.add(Restrictions.not(Restrictions.in("organization.id", readable)));
      criteria.setMaxResults(1);
      List<Order> rows = criteria.list();
      return rows.isEmpty() ? null : (String) ((BaseOBObject) rows.get(0)).getId();
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
