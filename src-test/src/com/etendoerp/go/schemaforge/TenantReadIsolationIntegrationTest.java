/*
 * *************************************************************************
 * The contents of this file are subject to the Etendo License
 * (the "License"), you may not use this file except in compliance with
 * the License.
 * You may obtain a copy of the License at
 * https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * Software distributed under the License  is  distributed  on  an  "AS IS"
 * basis, WITHOUT WARRANTY OF ANY KIND, either express or implied. See the
 * License for the specific language governing rights and limitations under
 * the License.
 * All portions are Copyright © 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.schemaforge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeNotNull;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.codehaus.jettison.json.JSONObject;
import org.hibernate.criterion.Restrictions;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.ModelProvider;
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
import org.openbravo.model.common.plm.Product;
import org.openbravo.model.financialmgmt.calendar.Year;

import com.etendoerp.go.schemaforge.handlers.YearAccountingHandler;

/**
 * Two-tenant coverage of the read paths that resolve a request-supplied id (ETP-5819), against a
 * real database.
 *
 * <p>The caller is the standard test user of the F&amp;B client; the "other tenant" is the QA
 * Testing client of the same sample data. Every call runs under {@code OBContext.setAdminMode()},
 * the mode NeoServlet opens. Nothing is written.
 *
 * <p>Where the caller's answer is compared with the one for an id that exists nowhere, equality is
 * the point: another tenant's id must not be told apart from a missing one.
 */
public class TenantReadIsolationIntegrationTest extends WeldBaseTest {

  private static final String SALES_ORDER_LINES_TAB_ID = "187";
  private static final String INP_PARTNER = "inpcBpartnerId";
  private static final String IDENTIFIER_SUFFIX = "$_identifier";

  private Tab linesTab;
  private String foreignOrderId;
  private String foreignPartnerId;
  private String ownPartnerId;
  private String foreignProductId;
  private String foreignYearId;
  private boolean requestAdminMode;

  @Before
  public void loadFixtures() {
    OBContext.setAdminMode(true);
    try {
      linesTab = OBDal.getInstance().get(Tab.class, SALES_ORDER_LINES_TAB_ID);
      foreignOrderId = firstIdOfClient(Order.class, QA_TEST_CLIENT_ID);
      foreignPartnerId = firstIdOfClient(BusinessPartner.class, QA_TEST_CLIENT_ID);
      ownPartnerId = firstIdOfClient(BusinessPartner.class, TEST_CLIENT_ID);
      foreignProductId = firstIdOfClient(Product.class, QA_TEST_CLIENT_ID);
      foreignYearId = firstIdOfClient(Year.class, QA_TEST_CLIENT_ID);
    } finally {
      OBContext.restorePreviousMode();
    }
    assumeNotNull("the sample data has no Sales Order Lines tab", linesTab);
    assumeNotNull("the sample data has no order of the QA Testing client", foreignOrderId);
    assumeNotNull("the sample data has no partner of the QA Testing client", foreignPartnerId);
    assumeNotNull("the sample data has no partner of the F&B client", ownPartnerId);
    assumeNotNull("the sample data has no product of the QA Testing client", foreignProductId);

    OBContext.setAdminMode();
    requestAdminMode = true;
  }

  @After
  public void rollBack() {
    try {
      OBDal.getInstance().rollbackAndClose();
    } finally {
      if (requestAdminMode) {
        OBContext.restorePreviousMode();
        requestAdminMode = false;
      }
    }
  }

  // ── callouts ───────────────────────────────────────────────────────────────

  @Test
  public void testCalloutParamOfAnotherTenantIsBlanked() {
    Map<String, String[]> params = paramsWithPartner(foreignPartnerId);

    CalloutRequestBuilder.blankForeignReferences(linesTab, params);

    assertEquals("", params.get(INP_PARTNER)[0]);
  }

  @Test
  public void testCalloutParamOfTheCallerIsKept() {
    Map<String, String[]> params = paramsWithPartner(ownPartnerId);

    CalloutRequestBuilder.blankForeignReferences(linesTab, params);

    assertEquals(ownPartnerId, params.get(INP_PARTNER)[0]);
  }

  /** An id that names no row is left to the callout, as before: an unsaved record's id is one. */
  @Test
  public void testCalloutParamOfAMissingRowIsKept() {
    String missingId = SequenceIdData.getUUID();
    Map<String, String[]> params = paramsWithPartner(missingId);

    CalloutRequestBuilder.blankForeignReferences(linesTab, params);

    assertEquals(missingId, params.get(INP_PARTNER)[0]);
  }

  // ── defaults: link-to-parent label ───────────────────────────────────────────

  @Test
  public void testDefaultsNeverLabelAnotherTenantsParent() throws Exception {
    JSONObject defaults = new JSONObject();

    NeoDefaultsService.tryInjectIdentifier(defaults, orderLineEntity(),
        OrderLine.PROPERTY_SALESORDER, foreignOrderId);

    assertFalse(defaults.has(OrderLine.PROPERTY_SALESORDER + IDENTIFIER_SUFFIX));
  }

  @Test
  public void testDefaultsLabelTheCallersParent() throws Exception {
    JSONObject defaults = new JSONObject();

    NeoDefaultsService.tryInjectIdentifier(defaults, orderLineEntity(),
        OrderLine.PROPERTY_SALESORDER, TEST_ORDER_ID);

    assertTrue(defaults.has(OrderLine.PROPERTY_SALESORDER + IDENTIFIER_SUFFIX));
  }

  // ── product prices ──────────────────────────────────────────────────────────

  /** Another tenant's product answers exactly like an id that exists nowhere: an empty list. */
  @Test
  public void testPricesOfAnotherTenantsProductListLikeAMissingOne() throws Exception {
    NeoResponse foreign = listPrices(foreignProductId);
    NeoResponse missing = listPrices(SequenceIdData.getUUID());

    assertEquals(200, foreign.getHttpStatus());
    assertEquals(missing.getBody().toString(), foreign.getBody().toString());
  }

  // ── product stock and closing entries: unfiltered SQL/HQL behind an id ────────

  @Test
  public void testStockOfAnotherTenantsProductListsLikeAMissingOne() {
    NeoResponse foreign = list(new ProductStockWarehouseHandler(), "parentId", foreignProductId);
    NeoResponse missing = list(new ProductStockWarehouseHandler(), "parentId",
        SequenceIdData.getUUID());

    assertEquals(200, foreign.getHttpStatus());
    assertEquals(missing.getBody().toString(), foreign.getBody().toString());
  }

  @Test
  public void testClosingEntriesOfAnotherTenantsYearListLikeAMissingOne() {
    assumeNotNull("the sample data has no year of the QA Testing client", foreignYearId);
    NeoResponse foreign = list(new YearAccountingHandler(), "year", foreignYearId);
    NeoResponse missing = list(new YearAccountingHandler(), "year", SequenceIdData.getUUID());

    assertEquals(200, foreign.getHttpStatus());
    assertEquals(missing.getBody().toString(), foreign.getBody().toString());
  }

  // ── helpers ────────────────────────────────────────────────────────────────

  private static Map<String, String[]> paramsWithPartner(String partnerId) {
    Map<String, String[]> params = new HashMap<>();
    params.put(INP_PARTNER, new String[] { partnerId });
    return params;
  }

  private static Entity orderLineEntity() {
    return ModelProvider.getInstance().getEntity(OrderLine.ENTITY_NAME);
  }

  private static NeoResponse listPrices(String productId) {
    return list(new ProductPriceHandler(), "parentId", productId);
  }

  private static NeoResponse list(NeoHandler handler, String param, String id) {
    Map<String, String> query = new HashMap<>();
    query.put(param, id);
    return handler.handle(NeoContext.builder()
        .httpMethod("GET")
        .endpointType(NeoEndpointType.CRUD)
        .queryParams(query)
        .build());
  }

  private static <T extends BaseOBObject> String firstIdOfClient(Class<T> type, String clientId) {
    OBCriteria<T> criteria = OBDal.getInstance().createCriteria(type);
    criteria.setFilterOnReadableClients(false);
    criteria.setFilterOnReadableOrganization(false);
    criteria.add(Restrictions.eq("client.id", clientId));
    criteria.setMaxResults(1);
    List<T> rows = criteria.list();
    return rows.isEmpty() ? null : (String) rows.get(0).getId();
  }
}
