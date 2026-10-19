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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeNotNull;

import java.util.List;

import javax.servlet.http.HttpServletResponse;

import org.codehaus.jettison.json.JSONObject;
import org.hibernate.criterion.Restrictions;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.base.weld.test.WeldBaseTest;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.erpCommon.utility.SequenceIdData;
import org.openbravo.model.ad.ui.Tab;
import org.openbravo.model.common.businesspartner.BusinessPartner;
import org.openbravo.model.common.order.Order;
import org.openbravo.model.common.order.OrderLine;

/**
 * Two-tenant coverage for {@link NeoReferenceOwnership} against a real database (ETP-5785).
 *
 * <p>The caller is the standard test user of the F&amp;B client; the "other tenant" is the QA
 * Testing client that ships with the same sample data. A create of a sales order line goes through
 * the real REST default path ({@link NeoCrudHandler#handleDefault(NeoContext)}), so the refusals are
 * proven where they happen and with the response the caller actually gets.
 *
 * <p>Nothing here is meant to persist: a refused write writes nothing by construction, and every
 * test rolls the session back in {@link #rollBack()} anyway.
 *
 * @covers com.etendoerp.go.schemaforge.NeoReferenceOwnership
 */
public class NeoReferenceOwnershipIntegrationTest extends WeldBaseTest {

  /** Sales Order window, "Lines" tab (C_OrderLine). */
  private static final String SALES_ORDER_LINES_TAB_ID = "187";
  /** "Unit", a System (client 0) unit of measure. */
  private static final String SYSTEM_UOM_ID = "100";
  private static final String MARKER = "NeoReferenceOwnershipIntegrationTest";
  private static final String UNKNOWN_REFERENCE = "refered to but not present in the import set";
  private static final String FIELD_PARENT_ID = "parentId";
  private static final String FIELD_PRODUCT = "product";
  private static final String FIELD_QUANTITY = "orderedQuantity";
  private static final String FIELD_DESCRIPTION = "description";
  private static final String FIELD_PARTNER = "businessPartner";

  private Tab linesTab;
  private String foreignOrderId;
  private String foreignPartnerId;

  @Before
  public void loadFixtures() {
    OBContext.setAdminMode(true);
    try {
      linesTab = OBDal.getInstance().get(Tab.class, SALES_ORDER_LINES_TAB_ID);
      foreignOrderId = firstIdOfClient(Order.class, QA_TEST_CLIENT_ID);
      foreignPartnerId = firstIdOfClient(BusinessPartner.class, QA_TEST_CLIENT_ID);
    } finally {
      OBContext.restorePreviousMode();
    }
    assumeNotNull("the sample data has no Sales Order Lines tab", linesTab);
    assumeNotNull("the sample data has no order of the QA Testing client", foreignOrderId);
    assumeNotNull("the sample data has no partner of the QA Testing client", foreignPartnerId);
  }

  @After
  public void rollBack() {
    OBDal.getInstance().rollbackAndClose();
  }

  // ── refused ────────────────────────────────────────────────────────────────

  /** A line attached to another tenant's order is refused, and nothing is written. */
  @Test
  public void testParentOfAnotherTenantIsRefusedAndNothingIsWritten() throws Exception {
    long foreignLinesBefore = countLinesOf(foreignOrderId);

    NeoResponse response = createLine(new JSONObject()
        .put(FIELD_PARENT_ID, foreignOrderId)
        .put(FIELD_PRODUCT, TEST_PRODUCT_ID)
        .put(FIELD_QUANTITY, 1)
        .put(FIELD_DESCRIPTION, MARKER));

    assertRefused(response);
    assertEquals(foreignLinesBefore, countLinesOf(foreignOrderId));
    assertEquals(0L, countMarkedLines());
  }

  /** A line of the caller's own order pointing at another tenant's partner is refused too. */
  @Test
  public void testForeignKeyToAnotherTenantIsRefusedAndNothingIsWritten() throws Exception {
    long ownLinesBefore = countLinesOf(TEST_ORDER_ID);

    NeoResponse response = createLine(new JSONObject()
        .put(FIELD_PARENT_ID, TEST_ORDER_ID)
        .put(FIELD_PRODUCT, TEST_PRODUCT_ID)
        .put(FIELD_PARTNER, foreignPartnerId)
        .put(FIELD_QUANTITY, 1)
        .put(FIELD_DESCRIPTION, MARKER));

    assertRefused(response);
    assertEquals(ownLinesBefore, countLinesOf(TEST_ORDER_ID));
    assertEquals(0L, countMarkedLines());
  }

  /**
   * Another tenant's order and an id that exists nowhere get the same answer — only the id the
   * caller sent differs — so the refusal cannot be used to probe ids.
   */
  @Test
  public void testForeignAndMissingParentsAreIndistinguishable() throws Exception {
    String missingId = SequenceIdData.getUUID();

    NeoResponse foreign = createLine(new JSONObject()
        .put(FIELD_PARENT_ID, foreignOrderId).put(FIELD_PRODUCT, TEST_PRODUCT_ID));
    NeoResponse missing = createLine(new JSONObject()
        .put(FIELD_PARENT_ID, missingId).put(FIELD_PRODUCT, TEST_PRODUCT_ID));

    assertEquals(missing.getHttpStatus(), foreign.getHttpStatus());
    assertEquals(missing.getBody().toString(),
        foreign.getBody().toString().replace(foreignOrderId, missingId));
  }

  // ── accepted ───────────────────────────────────────────────────────────────

  /** The caller's own order, product and partner, and System rows, pass the check. */
  @Test
  public void testOwnAndSystemReferencesAreAccepted() throws Exception {
    String ownPartnerId;
    OBContext.setAdminMode(true);
    try {
      ownPartnerId = OBDal.getInstance().get(Order.class, TEST_ORDER_ID).getBusinessPartner()
          .getId();
    } finally {
      OBContext.restorePreviousMode();
    }
    JSONObject fields = new JSONObject()
        .put(FIELD_PRODUCT, TEST_PRODUCT_ID)
        .put(FIELD_PARTNER, ownPartnerId)
        .put("uOM", SYSTEM_UOM_ID)
        .put("currency", EURO_ID);

    assertNull(NeoReferenceOwnership.refuseUnownedReferences(linesTab, fields, TEST_ORDER_ID));
  }

  // ── helpers ────────────────────────────────────────────────────────────────

  private NeoResponse createLine(JSONObject body) {
    NeoContext context = NeoContext.builder()
        .specName("sales-order")
        .entityName("lines")
        .httpMethod("POST")
        .requestBody(body)
        .adTab(linesTab)
        .obContext(OBContext.getOBContext())
        .endpointType(NeoEndpointType.CRUD)
        .build();
    return new NeoCrudHandler(null).handleDefault(context);
  }

  private static void assertRefused(NeoResponse response) throws Exception {
    assertNotNull(response);
    assertEquals(HttpServletResponse.SC_BAD_REQUEST, response.getHttpStatus());
    String message = response.getBody().getJSONObject("error").getString("message");
    assertTrue(message, message.contains(UNKNOWN_REFERENCE));
  }

  private static <T extends BaseOBObject> String firstIdOfClient(Class<T> type, String clientId) {
    OBCriteria<T> criteria = OBDal.getInstance().createCriteria(type);
    criteria.setFilterOnReadableClients(false);
    criteria.setFilterOnReadableOrganization(false);
    criteria.setFilterOnActive(false);
    criteria.add(Restrictions.eq("client.id", clientId));
    criteria.setMaxResults(1);
    List<T> rows = criteria.list();
    return rows.isEmpty() ? null : (String) rows.get(0).getId();
  }

  private static long countLinesOf(String orderId) {
    OBContext.setAdminMode(true);
    try {
      OBCriteria<OrderLine> criteria = OBDal.getInstance().createCriteria(OrderLine.class);
      criteria.setFilterOnReadableClients(false);
      criteria.setFilterOnReadableOrganization(false);
      criteria.setFilterOnActive(false);
      criteria.add(Restrictions.eq(OrderLine.PROPERTY_SALESORDER + ".id", orderId));
      return criteria.count();
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  private static long countMarkedLines() {
    OBContext.setAdminMode(true);
    try {
      OBDal.getInstance().flush();
      OBCriteria<OrderLine> criteria = OBDal.getInstance().createCriteria(OrderLine.class);
      criteria.setFilterOnReadableClients(false);
      criteria.setFilterOnReadableOrganization(false);
      criteria.setFilterOnActive(false);
      criteria.add(Restrictions.eq(OrderLine.PROPERTY_DESCRIPTION, MARKER));
      return criteria.count();
    } finally {
      OBContext.restorePreviousMode();
    }
  }
}
