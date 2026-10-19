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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.codehaus.jettison.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;
import org.openbravo.dal.core.OBContext;
import org.openbravo.erpCommon.utility.OBMessageUtils;
import org.openbravo.model.ad.datamodel.Table;
import org.openbravo.model.ad.ui.Tab;

import com.etendoerp.go.schemaforge.data.SFEntity;

/**
 * Unit tests for {@link NeoWritePreflight} — the reference-ownership check a REST or batch write
 * passes BEFORE any customization is dispatched (ETP-5785).
 *
 * <p>{@link NeoReferenceOwnership} is mocked: what is pinned here is what the preflight hands it
 * (DAL-keyed copy of the body, parent from body or query string), that the body itself is never
 * modified, and that a refusal becomes the same 400 the default write step answers.
 *
 * @covers com.etendoerp.go.schemaforge.NeoWritePreflight
 */
@RunWith(MockitoJUnitRunner.Silent.class)
public class NeoWritePreflightTest {

  private static final String REFUSAL = "{\"response\":{\"status\":-4,\"errors\":{\"id\":"
      + "\"New object Partner(null)  (key: P-1_Partner) refered to but not present in the import"
      + " set\"}}}";
  private static final String PARENT_ID = "parentId";

  private MockedStatic<NeoReferenceOwnership> ownership;
  private MockedStatic<NeoFieldFilter> fieldFilters;
  private MockedStatic<OBContext> obContext;
  private Tab adTab;
  private SFEntity sfEntity;

  @Before
  public void setUp() {
    ownership = mockStatic(NeoReferenceOwnership.class);
    fieldFilters = mockStatic(NeoFieldFilter.class);
    obContext = mockStatic(OBContext.class);
    obContext.when(OBContext::getOBContext).thenReturn(mock(OBContext.class));

    Table table = mock(Table.class);
    when(table.getName()).thenReturn("OrderLine");
    adTab = mock(Tab.class);
    when(adTab.getTable()).thenReturn(table);
    sfEntity = mock(SFEntity.class);

    NeoFieldFilter filter = mock(NeoFieldFilter.class);
    when(filter.resolveWritablePropName(anyString())).thenAnswer(inv -> inv.getArgument(0));
    when(filter.resolveWritablePropName("unitPrice")).thenReturn("priceActual");
    fieldFilters.when(() -> NeoFieldFilter.forEntity(sfEntity, "OrderLine")).thenReturn(filter);
  }

  /** Releases the static mocks and the inline mock-maker references of this test. */
  @After
  public void tearDown() {
    ownership.close();
    fieldFilters.close();
    obContext.close();
    Mockito.framework().clearInlineMocks();
  }

  private NeoContext context(String method, JSONObject body, Map<String, String> query) {
    return NeoContext.builder()
        .specName("sales-order")
        .entityName("lines")
        .httpMethod(method)
        .requestBody(body)
        .queryParams(query)
        .adTab(adTab)
        .sfEntity(sfEntity)
        .endpointType(NeoEndpointType.CRUD)
        .build();
  }

  private ArgumentCaptor<JSONObject> captureFields(String parentId) {
    ArgumentCaptor<JSONObject> fields = ArgumentCaptor.forClass(JSONObject.class);
    ownership.verify(() -> NeoReferenceOwnership.refuseUnownedReferences(eq(adTab),
        fields.capture(), parentId == null ? isNull() : eq(parentId)));
    return fields;
  }

  /** Reads and deletes run no reference check. */
  @Test
  public void testIgnoresNonWriteMethods() {
    assertNull(NeoWritePreflight.refusalFor(context("GET", null, null)));
    assertNull(NeoWritePreflight.refusalFor(context("DELETE", null, null)));
    ownership.verifyNoInteractions();
  }

  /** With no session there is no tenant to compare against. */
  @Test
  public void testDoesNothingWithoutASession() throws Exception {
    obContext.when(OBContext::getOBContext).thenReturn(null);

    assertNull(NeoWritePreflight.refusalFor(context("POST", new JSONObject(), null)));
    ownership.verifyNoInteractions();
  }

  /**
   * The check sees the body keyed by DAL property name, without the parent and the server-owned
   * tenant fields, and the request body itself is left exactly as it was sent.
   */
  @Test
  public void testChecksADalKeyedCopyAndLeavesTheBodyUntouched() throws Exception {
    JSONObject body = new JSONObject().put("unitPrice", "10").put("businessPartner", "BP-1")
        .put("organization", "ORG-X").put(PARENT_ID, "H-1");
    String before = body.toString();

    assertNull(NeoWritePreflight.refusalFor(context("POST", body, null)));

    JSONObject fields = captureFields("H-1").getValue();
    assertEquals("10", fields.getString("priceActual"));
    assertEquals("BP-1", fields.getString("businessPartner"));
    assertFalse(fields.has("unitPrice"));
    assertFalse(fields.has("organization"));
    assertFalse(fields.has(PARENT_ID));
    assertEquals(before, body.toString());
  }

  /** A body wrapped in {@code data} is read inside the wrapper, as the field filter does. */
  @Test
  public void testUnwrapsADataEnvelope() throws Exception {
    JSONObject body = new JSONObject().put("data",
        new JSONObject().put("businessPartner", "BP-1"));

    NeoWritePreflight.refusalFor(context("PUT", body, null));

    assertEquals("BP-1", captureFields(null).getValue().getString("businessPartner"));
  }

  /** The REST UI sends the parent on the query string; that is the parent checked then. */
  @Test
  public void testTakesTheParentFromTheQueryString() throws Exception {
    Map<String, String> query = new HashMap<>();
    query.put(PARENT_ID, "BP-PARENT");

    NeoWritePreflight.refusalFor(context("POST", new JSONObject(), query));

    captureFields("BP-PARENT");
  }

  /** When body and query string name different parents, both are checked. */
  @Test
  public void testChecksBothParentsWhenTheyDiffer() throws Exception {
    Map<String, String> query = new HashMap<>();
    query.put(PARENT_ID, "Q-PARENT");

    NeoWritePreflight.refusalFor(context("POST", new JSONObject().put(PARENT_ID, "B-PARENT"),
        query));

    captureFields("B-PARENT");
    ownership.verify(() -> NeoReferenceOwnership.refuseUnownedReferences(adTab, null,
        "Q-PARENT"));
  }

  /** A value sent under both its API key and its DAL name is checked in both spellings. */
  @Test
  public void testChecksBothSpellingsOfTheSameField() throws Exception {
    JSONObject body = new JSONObject().put("unitPrice", "API").put("priceActual", "DAL");

    NeoWritePreflight.refusalFor(context("PATCH", body, null));

    ArgumentCaptor<JSONObject> fields = ArgumentCaptor.forClass(JSONObject.class);
    ownership.verify(() -> NeoReferenceOwnership.refuseUnownedReferences(eq(adTab),
        fields.capture(), any()), Mockito.times(2));
    List<JSONObject> checked = fields.getAllValues();
    assertTrue(checked.get(0).has("priceActual") && checked.get(1).has("priceActual"));
    assertFalse(checked.get(0).getString("priceActual")
        .equals(checked.get(1).getString("priceActual")));
  }

  /** A refusal is answered as the default write step answers core's own: a 400 with its message. */
  @Test
  public void testARefusalBecomesTheDefaultStepsValidationError() throws Exception {
    ownership.when(() -> NeoReferenceOwnership.refuseUnownedReferences(any(), any(), any()))
        .thenReturn(REFUSAL);

    NeoResponse response;
    try (MockedStatic<OBMessageUtils> messages = mockStatic(OBMessageUtils.class)) {
      messages.when(() -> OBMessageUtils.messageBD(anyString())).thenAnswer(inv -> inv.getArgument(0));
      response = NeoWritePreflight.refusalFor(context("POST",
          new JSONObject().put("businessPartner", "P-1"), null));
    }

    assertNotNull(response);
    assertEquals(400, response.getHttpStatus());
    String message = response.getBody().getJSONObject("error").getString("message");
    assertTrue(message, message.contains("refered to but not present in the import set"));
  }
}
