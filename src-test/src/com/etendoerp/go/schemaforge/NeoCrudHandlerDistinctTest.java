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
 * All portions are Copyright © 2021–2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.schemaforge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.query.Query;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.ModelProvider;
import org.openbravo.base.model.Property;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.dal.service.OBDal;
import org.openbravo.dal.service.OBQuery;
import org.openbravo.model.ad.datamodel.Table;
import org.openbravo.model.ad.ui.Tab;
import org.openbravo.service.json.JsonConstants;

/**
 * Unit tests for the distinct-value fetch helpers added to {@link NeoCrudHandler}.
 */
public class NeoCrudHandlerDistinctTest {

  @Test
  public void parseIntOrDefaultUsesFallbackForBlankOrMalformedValues() throws Exception {
    assertEquals(10, invokeParseIntOrDefault(null, 10));
    assertEquals(10, invokeParseIntOrDefault("  ", 10));
    assertEquals(10, invokeParseIntOrDefault("abc", 10));
    assertEquals(25, invokeParseIntOrDefault(" 25 ", 10));
  }

  @Test
  public void resolveDistinctPropertyFindsDirectCaseInsensitiveAndColumnMatches() throws Exception {
    Entity entity = mock(Entity.class);
    Property direct = property("documentStatus", "DocStatus");
    Property byName = property("businessPartner", "C_BPartner_ID");
    Property byColumn = property("orderDate", "DateOrdered");

    when(entity.getProperty("documentStatus", false)).thenReturn(direct);
    when(entity.getProperty("BUSINESSPARTNER", false)).thenReturn(null);
    when(entity.getProperty("dateordered", false)).thenReturn(null);
    when(entity.getProperties()).thenReturn(Arrays.asList(byName, byColumn));

    assertSame(direct, invokeResolveDistinctProperty(entity, "documentStatus"));
    assertSame(byName, invokeResolveDistinctProperty(entity, "BUSINESSPARTNER"));
    assertSame(byColumn, invokeResolveDistinctProperty(entity, "dateordered"));
    assertNull(invokeResolveDistinctProperty(entity, "missing"));
  }

  @Test
  public void toDistinctEntrySerializesNullScalarAndBaseOBObjectValues() throws Exception {
    JSONObject nullEntry = invokeToDistinctEntry(null);
    assertEquals("", nullEntry.getString("id"));
    assertEquals("", nullEntry.getString("_identifier"));

    JSONObject scalarEntry = invokeToDistinctEntry(42);
    assertEquals("42", scalarEntry.getString("id"));
    assertEquals("42", scalarEntry.getString("_identifier"));

    BaseOBObject bob = mock(BaseOBObject.class);
    when(bob.getId()).thenReturn("BP_ID");
    when(bob.getIdentifier()).thenReturn("Acme");

    JSONObject referenceEntry = invokeToDistinctEntry(bob);
    assertEquals("BP_ID", referenceEntry.getString("id"));
    assertEquals("Acme", referenceEntry.getString("_identifier"));
  }

  @SuppressWarnings({ "unchecked", "rawtypes" })
  @Test
  public void handleDistinctFetchBuildsQueryAndPaginatesResults() throws Exception {
    Tab tab = mock(Tab.class);
    Table table = mock(Table.class);
    Entity entity = mock(Entity.class);
    Property property = property("documentStatus", "DocStatus");
    ModelProvider modelProvider = mock(ModelProvider.class);
    OBDal obDal = mock(OBDal.class);
    OBQuery obQuery = mock(OBQuery.class);
    Query<Object> hibernateQuery = mock(Query.class);

    when(tab.getTable()).thenReturn(table);
    when(tab.getHqlwhereclause()).thenReturn("e.documentStatus <> 'VO'");
    when(tab.getTabLevel()).thenReturn(0L);
    when(table.getName()).thenReturn("Order");
    when(modelProvider.getEntity("Order")).thenReturn(entity);
    when(entity.getProperty("DocStatus", false)).thenReturn(null);
    when(entity.getProperties()).thenReturn(Collections.singletonList(property));
    when(obDal.createQuery(eq("Order"), anyString())).thenReturn(obQuery);
    when(obQuery.setSelectClause("DISTINCT e.documentStatus")).thenReturn(obQuery);
    when(obQuery.setNamedParameter("search", "%co%")).thenReturn(obQuery);
    when(obQuery.setFirstResult(2)).thenReturn(obQuery);
    when(obQuery.setMaxResult(3)).thenReturn(obQuery);
    when(obQuery.createQuery(Object.class)).thenReturn(hibernateQuery);
    when(hibernateQuery.list()).thenReturn(Arrays.asList("CO", "CL", "DR"));

    Map<String, String> params = new HashMap<>();
    params.put(JsonConstants.DISTINCT_PARAMETER, "DocStatus");
    params.put(JsonConstants.STARTROW_PARAMETER, "2");
    params.put(JsonConstants.ENDROW_PARAMETER, "3");
    params.put("_distinctSearch", "Co");

    try (MockedStatic<ModelProvider> modelProviderMock = mockStatic(ModelProvider.class);
        MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class)) {
      modelProviderMock.when(ModelProvider::getInstance).thenReturn(modelProvider);
      obDalMock.when(OBDal::getInstance).thenReturn(obDal);

      NeoResponse response = invokeHandleDistinctFetch(new NeoCrudHandler(mock(NeoServlet.class)),
          tab, params);

      assertEquals(200, response.getHttpStatus());
      JSONObject payload = response.getBody().getJSONObject("response");
      JSONArray data = payload.getJSONArray("data");
      assertEquals(2, payload.getInt("startRow"));
      assertEquals(3, payload.getInt("endRow"));
      assertTrue(payload.getBoolean("hasMore"));
      assertEquals(2, data.length());
      assertEquals("CO", data.getJSONObject(0).getString("id"));
      assertEquals("CL", data.getJSONObject(1).getString("_identifier"));
    }

    ArgumentCaptor<String> whereCaptor = ArgumentCaptor.forClass(String.class);
    verify(obDal).createQuery(eq("Order"), whereCaptor.capture());
    String where = whereCaptor.getValue();
    assertTrue(where.contains("as e where e.documentStatus IS NOT NULL"));
    assertTrue(where.contains("(e.documentStatus <> 'VO')"));
    assertTrue(where.contains("LOWER(CAST(e.documentStatus AS string)) LIKE :search"));
    assertTrue(where.endsWith("order by e.documentStatus asc"));
    verify(obQuery).setNamedParameter("search", "%co%");
    verify(obQuery).setFirstResult(2);
    verify(obQuery).setMaxResult(3);
  }

  @Test
  public void handleDistinctFetchRejectsMissingTabTableOrField() throws Exception {
    NeoCrudHandler handler = new NeoCrudHandler(mock(NeoServlet.class));
    Map<String, String> params = new HashMap<>();
    params.put(JsonConstants.DISTINCT_PARAMETER, "name");

    NeoResponse noTab = invokeHandleDistinctFetch(handler, null, params);
    assertEquals(500, noTab.getHttpStatus());

    Tab tab = mock(Tab.class);
    when(tab.getTable()).thenReturn(mock(Table.class));
    params.put(JsonConstants.DISTINCT_PARAMETER, "");

    NeoResponse noField = invokeHandleDistinctFetch(handler, tab, params);
    assertEquals(400, noField.getHttpStatus());
    assertEquals("_distinct requires a field name",
        noField.getBody().getJSONObject("error").getString("message"));
  }

  @Test
  public void handleDistinctFetchReturnsBadRequestForUnknownProperty() throws Exception {
    Tab tab = mock(Tab.class);
    Table table = mock(Table.class);
    Entity entity = mock(Entity.class);
    ModelProvider modelProvider = mock(ModelProvider.class);
    Map<String, String> params = new HashMap<>();
    params.put(JsonConstants.DISTINCT_PARAMETER, "missing");

    when(tab.getTable()).thenReturn(table);
    when(table.getName()).thenReturn("Order");
    when(modelProvider.getEntity("Order")).thenReturn(entity);
    when(entity.getProperty("missing", false)).thenReturn(null);
    when(entity.getProperties()).thenReturn(Collections.emptyList());

    try (MockedStatic<ModelProvider> modelProviderMock = mockStatic(ModelProvider.class)) {
      modelProviderMock.when(ModelProvider::getInstance).thenReturn(modelProvider);

      NeoResponse response = invokeHandleDistinctFetch(
          new NeoCrudHandler(mock(NeoServlet.class)), tab, params);

      assertEquals(400, response.getHttpStatus());
      assertTrue(response.getBody().getJSONObject("error").getString("message")
          .contains("Unknown field 'missing' on entity Order"));
    }
  }

  private static Property property(String name, String columnName) {
    Property property = mock(Property.class);
    when(property.getName()).thenReturn(name);
    when(property.getColumnName()).thenReturn(columnName);
    return property;
  }

  private static int invokeParseIntOrDefault(String raw, int fallback) throws Exception {
    Method method = NeoCrudHandler.class.getDeclaredMethod("parseIntOrDefault",
        String.class, int.class);
    method.setAccessible(true);
    return (Integer) method.invoke(null, raw, fallback);
  }

  private static Property invokeResolveDistinctProperty(Entity entity, String fieldName)
      throws Exception {
    Method method = NeoCrudHandler.class.getDeclaredMethod("resolveDistinctProperty",
        Entity.class, String.class);
    method.setAccessible(true);
    return (Property) method.invoke(null, entity, fieldName);
  }

  private static JSONObject invokeToDistinctEntry(Object value) throws Exception {
    Method method = NeoCrudHandler.class.getDeclaredMethod("toDistinctEntry", Object.class);
    method.setAccessible(true);
    return (JSONObject) method.invoke(null, value);
  }

  private static NeoResponse invokeHandleDistinctFetch(NeoCrudHandler handler, Tab tab,
      Map<String, String> params) throws Exception {
    Method method = NeoCrudHandler.class.getDeclaredMethod("handleDistinctFetch",
        Tab.class, Map.class);
    method.setAccessible(true);
    return (NeoResponse) method.invoke(handler, tab, params);
  }
}
