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
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.hibernate.criterion.Restrictions;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.datamodel.Column;
import org.openbravo.model.ad.ui.Tab;
import org.openbravo.model.common.invoice.Invoice;
import org.openbravo.model.common.order.Order;
import org.openbravo.model.materialmgmt.transaction.ShipmentInOut;
import org.openbravo.test.base.OBBaseTest;

/**
 * Real-database specs for {@link DocActionIdempotency} (ETP-5783). The unit test mocks the AD; what
 * only the real dictionary can prove is that the structural lookup (the single active List column
 * whose reference holds the draft status and the target) finds exactly one status column on the
 * three document headers, although orders use their own status list and invoices and shipments the
 * general one. Read-only: it reads existing documents and writes nothing, so there is nothing to
 * roll back beyond closing the session.
 *
 * @covers com.etendoerp.go.schemaforge.DocActionIdempotency
 */
public class DocActionIdempotencyIntegrationTest extends OBBaseTest {

  private static final String ORDER_TAB = "186";
  private static final String INVOICE_TAB = "263";
  private static final String SHIPMENT_TAB = "257";
  private static final String COMPLETED = "CO";
  private static final String DRAFT = "DR";

  @Before
  public void openAdminContext() {
    setQAAdminContext();
    OBContext.setAdminMode(true);
  }

  @After
  public void closeSession() {
    OBContext.restorePreviousMode();
    OBDal.getInstance().rollbackAndClose();
  }

  /** The DocStatus-style columns the lookup treats as candidates: draft and CO in one list. */
  private static long statusCandidates(String tabId) {
    Tab tab = OBDal.getInstance().get(Tab.class, tabId);
    assertNotNull("tab " + tabId + " must exist", tab);
    long found = 0;
    for (Column column : tab.getTable().getADColumnList()) {
      if (!Boolean.TRUE.equals(column.isActive()) || column.getReferenceSearchKey() == null
          || column.getReference() == null || !"17".equals(column.getReference().getId())) {
        continue;
      }
      java.util.Set<String> keys = new java.util.HashSet<>();
      for (org.openbravo.model.ad.domain.List value : column.getReferenceSearchKey()
          .getADListList()) {
        if (Boolean.TRUE.equals(value.isActive())
            && (DRAFT.equals(value.getSearchKey()) || COMPLETED.equals(value.getSearchKey()))) {
          keys.add(value.getSearchKey());
        }
      }
      if (keys.size() == 2) {
        found++;
      }
    }
    return found;
  }

  private static String idWithStatus(Class<? extends BaseOBObject> type, String status) {
    OBCriteria<? extends BaseOBObject> criteria = OBDal.getInstance().createCriteria(type);
    criteria.add(Restrictions.eq("documentStatus", status));
    criteria.setMaxResults(1);
    List<? extends BaseOBObject> rows = criteria.list();
    assertEquals("the test database needs a " + type.getSimpleName() + " in status " + status, 1,
        rows.size());
    return (String) rows.get(0).getId();
  }

  @Test
  public void everyDocumentHeaderHasExactlyOneStatusColumn() {
    assertEquals(1, statusCandidates(ORDER_TAB));
    assertEquals(1, statusCandidates(INVOICE_TAB));
    assertEquals(1, statusCandidates(SHIPMENT_TAB));
  }

  @Test
  public void aCompletedOrderAskedToCompleteIsAlreadyApplied() throws Exception {
    NeoResponse response = DocActionIdempotency.alreadyApplied(ORDER_TAB,
        idWithStatus(Order.class, COMPLETED), "CO");

    assertNotNull(response);
    assertEquals(200, response.getHttpStatus());
    assertTrue(response.getBody().getBoolean("alreadyApplied"));
  }

  @Test
  public void aCompletedInvoiceAskedToCompleteIsAlreadyApplied() {
    assertNotNull(DocActionIdempotency.alreadyApplied(INVOICE_TAB,
        idWithStatus(Invoice.class, COMPLETED), "CO"));
  }

  @Test
  public void aCompletedShipmentAskedToCompleteIsAlreadyApplied() {
    assertNotNull(DocActionIdempotency.alreadyApplied(SHIPMENT_TAB,
        idWithStatus(ShipmentInOut.class, COMPLETED), "CO"));
  }

  @Test
  public void aClosedOrderAskedToCloseIsAlreadyApplied() {
    assertNotNull(DocActionIdempotency.alreadyApplied(ORDER_TAB,
        idWithStatus(Order.class, "CL"), "CL"));
  }

  @Test
  public void aDraftRecordAskedToCompleteMustRunTheProcedure() {
    assertNull(DocActionIdempotency.alreadyApplied(ORDER_TAB, idWithStatus(Order.class, DRAFT),
        "CO"));
    assertNull(DocActionIdempotency.alreadyApplied(INVOICE_TAB,
        idWithStatus(Invoice.class, DRAFT), "CO"));
    assertNull(DocActionIdempotency.alreadyApplied(SHIPMENT_TAB,
        idWithStatus(ShipmentInOut.class, DRAFT), "CO"));
  }

  @Test
  public void reactivateNeverShortCircuits() {
    assertNull(DocActionIdempotency.alreadyApplied(ORDER_TAB,
        idWithStatus(Order.class, COMPLETED), "RE"));
    assertNull(DocActionIdempotency.alreadyApplied(ORDER_TAB, idWithStatus(Order.class, DRAFT),
        "RE"));
  }

  @Test
  public void aCompletedRecordAskedToVoidMustRunTheProcedure() {
    assertNull(DocActionIdempotency.alreadyApplied(INVOICE_TAB,
        idWithStatus(Invoice.class, COMPLETED), "VO"));
  }
}
