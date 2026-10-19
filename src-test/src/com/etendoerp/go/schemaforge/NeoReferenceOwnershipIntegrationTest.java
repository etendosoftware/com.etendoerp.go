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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import javax.servlet.ReadListener;
import javax.servlet.ServletInputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.criterion.MatchMode;
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
import org.openbravo.model.common.businesspartner.Location;
import org.openbravo.model.common.order.Order;
import org.openbravo.model.common.order.OrderLine;

import com.etendoerp.go.schemaforge.data.SFEntity;
import com.etendoerp.go.schemaforge.data.SFSpec;

/**
 * Two-tenant coverage for the reference-ownership rule of NEO writes against a real database
 * (ETP-5785).
 *
 * <p>The caller is the standard test user of the F&amp;B client; the "other tenant" is the QA
 * Testing client of the same sample data. Writes go through the real REST dispatch
 * ({@link NeoCrudHandler#handleWindowEntityCrud}, with the servlet's HTTP seams stubbed and its
 * customization dispatch delegated to the real {@link NeoServletSupport#handleWithHooks}) and
 * through {@link BatchService#executeBatch}, so the refusal is proven where it happens: before any
 * customization runs. Both shipped entities used here have one — {@code sales-order/lines}
 * ({@code orderLineHandler}) and {@code contacts/locationAddress}
 * ({@code contactsLocationAddressHandler}, which persists on its own).
 *
 * <p>Nothing here is meant to persist. A refused write writes nothing by construction, every test
 * rolls back in {@link #rollBack()}, and the batch case adds an operation that always fails, so even
 * a regression that let the first one through is rolled back rather than committed.
 *
 * @covers com.etendoerp.go.schemaforge.NeoReferenceOwnership
 * @covers com.etendoerp.go.schemaforge.NeoWritePreflight
 */
public class NeoReferenceOwnershipIntegrationTest extends WeldBaseTest {

  private static final String SALES_ORDER_LINES_TAB_ID = "187";
  /** "Unit", a System (client 0) unit of measure. */
  private static final String SYSTEM_UOM_ID = "100";
  /** Spain, shared reference data. */
  private static final String COUNTRY_SPAIN_ID = "106";
  private static final String MARKER = "NeoReferenceOwnershipIntegrationTest";
  private static final String UNKNOWN_REFERENCE = "refered to but not present in the import set";
  private static final String SALES_ORDER = "sales-order";
  private static final String LINES = "lines";
  private static final String CONTACTS = "contacts";
  private static final String LOCATION_ADDRESS = "locationAddress";
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

  // ── REST single, through the customization of sales-order/lines ────────────

  /** A line attached to another tenant's order is refused, and nothing is written. */
  @Test
  public void testParentOfAnotherTenantIsRefusedAndNothingIsWritten() throws Exception {
    long foreignLinesBefore = countLinesOf(foreignOrderId);

    NeoResponse response = restWrite(SALES_ORDER, LINES, new JSONObject()
        .put(FIELD_PARENT_ID, foreignOrderId)
        .put(FIELD_PRODUCT, TEST_PRODUCT_ID)
        .put(FIELD_QUANTITY, 1)
        .put(FIELD_DESCRIPTION, MARKER), null);

    assertRefused(response);
    assertEquals(foreignLinesBefore, countLinesOf(foreignOrderId));
    assertEquals(0L, countMarkedLines());
  }

  /** A line of the caller's own order pointing at another tenant's partner is refused too. */
  @Test
  public void testForeignKeyToAnotherTenantIsRefusedAndNothingIsWritten() throws Exception {
    long ownLinesBefore = countLinesOf(TEST_ORDER_ID);

    NeoResponse response = restWrite(SALES_ORDER, LINES, new JSONObject()
        .put(FIELD_PARENT_ID, TEST_ORDER_ID)
        .put(FIELD_PRODUCT, TEST_PRODUCT_ID)
        .put(FIELD_PARTNER, foreignPartnerId)
        .put(FIELD_QUANTITY, 1)
        .put(FIELD_DESCRIPTION, MARKER), null);

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

    NeoResponse foreign = restWrite(SALES_ORDER, LINES, new JSONObject()
        .put(FIELD_PARENT_ID, foreignOrderId).put(FIELD_PRODUCT, TEST_PRODUCT_ID), null);
    NeoResponse missing = restWrite(SALES_ORDER, LINES, new JSONObject()
        .put(FIELD_PARENT_ID, missingId).put(FIELD_PRODUCT, TEST_PRODUCT_ID), null);

    assertEquals(missing.getHttpStatus(), foreign.getHttpStatus());
    assertEquals(missing.getBody().toString(),
        foreign.getBody().toString().replace(foreignOrderId, missingId));
  }

  // ── a customization that persists on its own ───────────────────────────────

  /**
   * {@code contacts/locationAddress} is created by its customization, which writes the address
   * itself and never reaches the default step. With another tenant's partner as the parent (the
   * REST UI shape: {@code ?parentId=}), the write is refused before it runs.
   */
  @Test
  public void testCustomizationThatPersistsItselfNeverRunsForAForeignParent() throws Exception {
    long foreignAddressesBefore = countAddressesOf(foreignPartnerId);
    Map<String, String> query = new HashMap<>();
    query.put(FIELD_PARENT_ID, foreignPartnerId);

    NeoResponse response = restWrite(CONTACTS, LOCATION_ADDRESS, addressBody(), query);

    assertRefused(response);
    assertEquals(foreignAddressesBefore, countAddressesOf(foreignPartnerId));
  }

  /**
   * The same create inside a batch. The second operation always fails, so a regression that let
   * the first through would still be rolled back — but then the batch would fail at index 1, not 0.
   */
  @Test
  public void testBatchCreateThroughACustomizationIsRefusedFirst() throws Exception {
    long foreignAddressesBefore = countAddressesOf(foreignPartnerId);
    JSONArray operations = new JSONArray()
        .put(new JSONObject().put("id", "loc").put("spec", CONTACTS)
            .put("entity", LOCATION_ADDRESS)
            .put("body", addressBody().put(FIELD_PARENT_ID, foreignPartnerId)))
        .put(new JSONObject().put("id", "guard").put("spec", CONTACTS)
            .put("entity", "noSuchEntity").put("body", new JSONObject()));

    JSONObject result = BatchService.forBatchOnly().executeBatch(operations);

    assertFalse(result.getBoolean(BatchService.FIELD_COMMITTED));
    assertEquals(result.toString(), 0, result.getJSONObject("failedAt").getInt("index"));
    assertEquals(result.toString(), 400, result.getJSONObject("error").getInt("status"));
    assertEquals(foreignAddressesBefore, countAddressesOf(foreignPartnerId));
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

  private static JSONObject addressBody() throws Exception {
    return new JSONObject().put("addressLine1", MARKER).put("country", COUNTRY_SPAIN_ID)
        .put("name", MARKER);
  }

  /**
   * One REST create through {@link NeoCrudHandler#handleWindowEntityCrud}. Only the servlet's HTTP
   * seams are stubbed; the customization dispatch is the real one.
   */
  private NeoResponse restWrite(String specName, String entityName, JSONObject body,
      Map<String, String> query) throws Exception {
    SFSpec spec = NeoServletSupport.findSpec(specName);
    assumeNotNull("spec not configured: " + specName, spec);
    SFEntity entity = findEntity(spec.getId(), entityName);
    assumeNotNull("entity not configured: " + specName + "/" + entityName, entity);

    NeoServlet servlet = mock(NeoServlet.class);
    NeoCrudHandler handler = new NeoCrudHandler(servlet);
    AtomicReference<NeoResponse> written = new AtomicReference<>();
    when(servlet.findEntity(spec.getId(), entityName)).thenReturn(entity);
    when(servlet.extractQueryParams(any())).thenReturn(query == null ? new HashMap<>() : query);
    when(servlet.handleWithHooks(anyString(), any(NeoContext.class), any(), any()))
        .thenAnswer(inv -> NeoServletSupport.handleWithHooks(inv.getArgument(0),
            inv.getArgument(1), handler));
    doAnswer(inv -> {
      written.set(inv.getArgument(1));
      return null;
    }).when(servlet).writeResponse(any(), any());
    doAnswer(inv -> {
      written.set(NeoResponse.error((int) inv.getArgument(1), (String) inv.getArgument(2)));
      return null;
    }).when(servlet).sendError(any(), anyInt(), anyString());

    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getInputStream()).thenReturn(inputStreamOf(body.toString()));
    handler.handleWindowEntityCrud(spec, new NeoServlet.NeoPathInfo(specName, entityName, null),
        "POST", request, mock(HttpServletResponse.class));
    return written.get();
  }

  private static ServletInputStream inputStreamOf(String content) {
    ByteArrayInputStream bytes = new ByteArrayInputStream(
        content.getBytes(StandardCharsets.UTF_8));
    return new ServletInputStream() {
      @Override
      public int read() {
        return bytes.read();
      }

      @Override
      public boolean isFinished() {
        return bytes.available() == 0;
      }

      @Override
      public boolean isReady() {
        return true;
      }

      @Override
      public void setReadListener(ReadListener listener) {
        // not used: the body is read synchronously
      }
    };
  }

  private static SFEntity findEntity(String specId, String entityName) {
    OBCriteria<SFEntity> criteria = OBDal.getInstance().createCriteria(SFEntity.class);
    criteria.add(Restrictions.eq(SFEntity.PROPERTY_ETGOSFSPEC + ".id", specId));
    criteria.add(Restrictions.ilike(SFEntity.PROPERTY_NAME, entityName, MatchMode.EXACT));
    criteria.add(Restrictions.eq(SFEntity.PROPERTY_ISACTIVE, true));
    criteria.setMaxResults(1);
    List<SFEntity> rows = criteria.list();
    return rows.isEmpty() ? null : rows.get(0);
  }

  private static void assertRefused(NeoResponse response) throws Exception {
    assertNotNull(response);
    assertEquals(response.getBody().toString(), HttpServletResponse.SC_BAD_REQUEST,
        response.getHttpStatus());
    String message = response.getBody().getJSONObject("error").getString("message");
    assertTrue(message, message.contains(UNKNOWN_REFERENCE));
  }

  private static <T extends BaseOBObject> String firstIdOfClient(Class<T> type, String clientId) {
    OBCriteria<T> criteria = unfiltered(type);
    criteria.add(Restrictions.eq("client.id", clientId));
    criteria.setMaxResults(1);
    List<T> rows = criteria.list();
    return rows.isEmpty() ? null : (String) rows.get(0).getId();
  }

  private static <T extends BaseOBObject> OBCriteria<T> unfiltered(Class<T> type) {
    OBCriteria<T> criteria = OBDal.getInstance().createCriteria(type);
    criteria.setFilterOnReadableClients(false);
    criteria.setFilterOnReadableOrganization(false);
    criteria.setFilterOnActive(false);
    return criteria;
  }

  private static long countLinesOf(String orderId) {
    OBContext.setAdminMode(true);
    try {
      OBCriteria<OrderLine> criteria = unfiltered(OrderLine.class);
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
      OBCriteria<OrderLine> criteria = unfiltered(OrderLine.class);
      criteria.add(Restrictions.eq(OrderLine.PROPERTY_DESCRIPTION, MARKER));
      return criteria.count();
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  private static long countAddressesOf(String partnerId) {
    OBContext.setAdminMode(true);
    try {
      OBDal.getInstance().flush();
      OBCriteria<Location> criteria = unfiltered(Location.class);
      criteria.add(Restrictions.eq(Location.PROPERTY_BUSINESSPARTNER + ".id", partnerId));
      return criteria.count();
    } finally {
      OBContext.restorePreviousMode();
    }
  }
}
