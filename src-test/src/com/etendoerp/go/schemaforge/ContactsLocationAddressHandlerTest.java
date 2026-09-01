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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;

import org.openbravo.module.bptaxidkey.ViesService;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.HashMap;
import java.util.Map;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.core.SessionHandler;
import org.openbravo.dal.service.OBDal;
import org.openbravo.erpCommon.utility.OBMessageUtils;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.businesspartner.BusinessPartner;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.common.geography.Country;
import org.openbravo.model.common.geography.Location;
import org.openbravo.model.common.geography.Region;
import org.openbravo.model.ad.system.Client;
import org.openbravo.base.exception.OBException;
import org.openbravo.dal.service.OBQuery;

/**
 * Unit tests for {@link ContactsLocationAddressHandler}.
 *
 * <p>Covers: handle() routing (POST/PUT/GET/DELETE), handleCreate (missing parentId,
 * null BP, successful creation), handleUpdate (null bpLoc, null geoLoc, successful
 * update), afterHandle (non-GET, enrich single record, enrich list display names),
 * and static helper methods (nullIfEmpty, boolField, str, joinNonNull, buildDisplayName).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ContactsLocationAddressHandlerTest {

  private ContactsLocationAddressHandler handler;

  @Mock
  private OBDal obDal;
  @Mock
  private OBProvider obProvider;
  @Mock
  private SessionHandler sessionHandler;

  private MockedStatic<OBDal> obDalMock;
  private MockedStatic<OBContext> obContextMock;
  private MockedStatic<OBProvider> obProviderMock;
  private MockedStatic<SessionHandler> sessionHandlerMock;

  @BeforeEach
  void setUp() {
    handler = new ContactsLocationAddressHandler();
    obDalMock = mockStatic(OBDal.class);
    obContextMock = mockStatic(OBContext.class);
    obProviderMock = mockStatic(OBProvider.class);
    sessionHandlerMock = mockStatic(SessionHandler.class);

    obDalMock.when(OBDal::getInstance).thenReturn(obDal);
    obProviderMock.when(OBProvider::getInstance).thenReturn(obProvider);
    sessionHandlerMock.when(SessionHandler::getInstance).thenReturn(sessionHandler);
  }

  @AfterEach
  void tearDown() {
    obDalMock.close();
    obContextMock.close();
    obProviderMock.close();
    sessionHandlerMock.close();
  }

  // ── handle() routing ────────────────────────────────────────────────────

  /**
   * Verifies that a GET request returns null (falls through to default CRUD).
   */
  @Test
  void testHandleGetReturnsNull() {
    NeoContext ctx = buildContext("GET", null, null, null);
    assertNull(handler.handle(ctx));
  }

  /**
   * Verifies that a DELETE request returns null (falls through to default CRUD).
   */
  @Test
  void testHandleDeleteReturnsNull() {
    NeoContext ctx = buildContext("DELETE", null, null, null);
    assertNull(handler.handle(ctx));
  }

  /**
   * Verifies that a POST with a recordId returns null (only POST without recordId triggers create).
   */
  @Test
  void testHandlePostWithRecordIdReturnsNull() {
    NeoContext ctx = buildContext("POST", "some-id", null, null);
    assertNull(handler.handle(ctx));
  }

  /**
   * Verifies that a PUT without a recordId returns null (only PUT with recordId triggers update).
   */
  @Test
  void testHandlePutWithoutRecordIdReturnsNull() {
    NeoContext ctx = buildContext("PUT", null, null, null);
    assertNull(handler.handle(ctx));
  }

  // ── handleCreate ────────────────────────────────────────────────────────

  /**
   * Verifies that a POST without parentId returns HTTP 400.
   */
  @Test
  void testHandleCreateMissingParentIdReturns400() throws Exception {
    NeoContext ctx = buildContext("POST", null, new JSONObject(), Collections.emptyMap());
    NeoResponse response = handler.handle(ctx);
    assertNotNull(response);
    assertEquals(400, response.getHttpStatus());
  }

  /**
   * Verifies that a POST with empty parentId returns HTTP 400.
   */
  @Test
  void testHandleCreateEmptyParentIdReturns400() throws Exception {
    Map<String, String> params = new HashMap<>();
    params.put("parentId", "");
    NeoContext ctx = buildContext("POST", null, new JSONObject(), params);
    NeoResponse response = handler.handle(ctx);
    assertNotNull(response);
    assertEquals(400, response.getHttpStatus());
  }

  /**
   * Verifies that a POST with a parentId for a non-existent BP returns HTTP 404.
   */
  @Test
  void testHandleCreateNullBpReturns404() throws Exception {
    Map<String, String> params = new HashMap<>();
    params.put("parentId", "missing-bp-id");
    when(obDal.get(BusinessPartner.class, "missing-bp-id")).thenReturn(null);

    NeoContext ctx = buildContext("POST", null, new JSONObject(), params);
    NeoResponse response = handler.handle(ctx);
    assertNotNull(response);
    assertEquals(404, response.getHttpStatus());
  }

  /**
   * Verifies that a successful POST creates C_Location and C_BPartner_Location,
   * returns HTTP 201, and the response contains the expected structure.
   */
  @Test
  void testHandleCreateSuccessful() throws Exception {
    Map<String, String> params = new HashMap<>();
    params.put("parentId", "bp-123");

    BusinessPartner bp = mock(BusinessPartner.class);
    Client client = mock(Client.class);
    Organization org = mock(Organization.class);
    when(bp.getClient()).thenReturn(client);
    when(bp.getOrganization()).thenReturn(org);
    when(obDal.get(BusinessPartner.class, "bp-123")).thenReturn(bp);

    Location geoLoc = mock(Location.class);
    when(geoLoc.getId()).thenReturn("geo-loc-id");
    when(obProvider.get(Location.class)).thenReturn(geoLoc);

    org.openbravo.model.common.businesspartner.Location bpLoc =
        mock(org.openbravo.model.common.businesspartner.Location.class);
    when(bpLoc.getId()).thenReturn("bp-loc-id");
    when(bpLoc.getName()).thenReturn("Test Address");
    when(bpLoc.isShipToAddress()).thenReturn(Boolean.TRUE);
    when(bpLoc.isInvoiceToAddress()).thenReturn(Boolean.TRUE);
    when(obProvider.get(org.openbravo.model.common.businesspartner.Location.class)).thenReturn(bpLoc);

    JSONObject body = new JSONObject();
    body.put("name", "Test Address");
    body.put("addressLine1", "123 Main St");
    body.put("cityName", "Springfield");

    NeoContext ctx = buildContext("POST", null, body, params);
    NeoResponse response = handler.handle(ctx);

    assertNotNull(response);
    assertEquals(201, response.getHttpStatus());

    JSONObject responseBody = response.getBody();
    assertNotNull(responseBody.optJSONObject("response"));
    JSONArray data = responseBody.getJSONObject("response").getJSONArray("data");
    assertEquals(1, data.length());

    JSONObject resultRecord = data.getJSONObject(0);
    assertEquals("bp-loc-id", resultRecord.getString("id"));
    assertEquals("geo-loc-id", resultRecord.getString("locationAddress"));
  }

  // ── handleUpdate ────────────────────────────────────────────────────────

  /**
   * Verifies that a PUT for a non-existent BPartner Location returns HTTP 404.
   */
  @Test
  void testHandleUpdateNullBpLocReturns404() throws Exception {
    when(obDal.get(org.openbravo.model.common.businesspartner.Location.class, "missing-id"))
        .thenReturn(null);

    NeoContext ctx = buildContext("PUT", "missing-id", new JSONObject(), null);
    NeoResponse response = handler.handle(ctx);
    assertNotNull(response);
    assertEquals(404, response.getHttpStatus());
  }

  /**
   * Verifies that a PUT for a BPartner Location with no linked C_Location returns HTTP 500.
   */
  @Test
  void testHandleUpdateNullGeoLocReturns500() throws Exception {
    org.openbravo.model.common.businesspartner.Location bpLoc =
        mock(org.openbravo.model.common.businesspartner.Location.class);
    when(bpLoc.getLocationAddress()).thenReturn(null);
    when(obDal.get(org.openbravo.model.common.businesspartner.Location.class, "bpl-id"))
        .thenReturn(bpLoc);

    NeoContext ctx = buildContext("PUT", "bpl-id", new JSONObject(), null);
    NeoResponse response = handler.handle(ctx);
    assertNotNull(response);
    assertEquals(500, response.getHttpStatus());
  }

  /**
   * Verifies a successful PUT updates both records and returns HTTP 200.
   */
  @Test
  void testHandleUpdateSuccessful() throws Exception {
    Location geoLoc = mock(Location.class);
    when(geoLoc.getId()).thenReturn("geo-id");

    org.openbravo.model.common.businesspartner.Location bpLoc =
        mock(org.openbravo.model.common.businesspartner.Location.class);
    when(bpLoc.getId()).thenReturn("bpl-id");
    when(bpLoc.getName()).thenReturn("Updated Name");
    when(bpLoc.isShipToAddress()).thenReturn(Boolean.TRUE);
    when(bpLoc.isInvoiceToAddress()).thenReturn(Boolean.FALSE);
    when(bpLoc.getLocationAddress()).thenReturn(geoLoc);
    when(obDal.get(org.openbravo.model.common.businesspartner.Location.class, "bpl-id"))
        .thenReturn(bpLoc);

    JSONObject body = new JSONObject();
    body.put("name", "Updated Name");
    body.put("addressLine1", "456 Oak Ave");
    body.put("shipToAddress", "Y");
    body.put("invoiceToAddress", "N");

    NeoContext ctx = buildContext("PUT", "bpl-id", body, null);
    NeoResponse response = handler.handle(ctx);

    assertNotNull(response);
    assertEquals(200, response.getHttpStatus());

    JSONArray data = response.getBody().getJSONObject("response").getJSONArray("data");
    assertEquals(1, data.length());
    assertEquals("bpl-id", data.getJSONObject(0).getString("id"));
  }

  /**
   * Verifies that when the update body contains a country field, the handler
   * looks it up via OBDal and sets it on the geoLoc.
   */
  @Test
  void testHandleUpdateWithCountryAndRegion() throws Exception {
    Location geoLoc = mock(Location.class);
    when(geoLoc.getId()).thenReturn("geo-id");

    Country country = mock(Country.class);
    when(country.getId()).thenReturn("country-id");
    when(country.getName()).thenReturn("Spain");
    when(country.getIdentifier()).thenReturn("España");
    when(obDal.get(Country.class, "country-id")).thenReturn(country);

    Region region = mock(Region.class);
    when(region.getId()).thenReturn("region-id");
    when(region.getName()).thenReturn("Catalonia");
    when(obDal.get(Region.class, "region-id")).thenReturn(region);

    org.openbravo.model.common.businesspartner.Location bpLoc =
        mock(org.openbravo.model.common.businesspartner.Location.class);
    when(bpLoc.getId()).thenReturn("bpl-id");
    when(bpLoc.getName()).thenReturn("Name");
    when(bpLoc.isShipToAddress()).thenReturn(Boolean.TRUE);
    when(bpLoc.isInvoiceToAddress()).thenReturn(Boolean.TRUE);
    when(bpLoc.getLocationAddress()).thenReturn(geoLoc);
    when(obDal.get(org.openbravo.model.common.businesspartner.Location.class, "bpl-id"))
        .thenReturn(bpLoc);

    JSONObject body = new JSONObject();
    body.put("country", "country-id");
    body.put("region", "region-id");

    NeoContext ctx = buildContext("PUT", "bpl-id", body, null);
    NeoResponse response = handler.handle(ctx);

    assertNotNull(response);
    assertEquals(200, response.getHttpStatus());
    verify(geoLoc).setCountry(country);
    verify(geoLoc).setRegion(region);
  }

  /**
   * Verifies that when region is explicitly set to empty in the body, geoLoc.setRegion(null) is called.
   */
  @Test
  void testHandleUpdateClearsRegionWhenEmpty() throws Exception {
    Location geoLoc = mock(Location.class);
    when(geoLoc.getId()).thenReturn("geo-id");

    org.openbravo.model.common.businesspartner.Location bpLoc =
        mock(org.openbravo.model.common.businesspartner.Location.class);
    when(bpLoc.getId()).thenReturn("bpl-id");
    when(bpLoc.getName()).thenReturn("Name");
    when(bpLoc.isShipToAddress()).thenReturn(Boolean.TRUE);
    when(bpLoc.isInvoiceToAddress()).thenReturn(Boolean.TRUE);
    when(bpLoc.getLocationAddress()).thenReturn(geoLoc);
    when(obDal.get(org.openbravo.model.common.businesspartner.Location.class, "bpl-id"))
        .thenReturn(bpLoc);

    JSONObject body = new JSONObject();
    body.put("region", "");

    NeoContext ctx = buildContext("PUT", "bpl-id", body, null);
    NeoResponse response = handler.handle(ctx);

    assertNotNull(response);
    assertEquals(200, response.getHttpStatus());
    verify(geoLoc).setRegion(null);
  }

  // ── handle() exception path ─────────────────────────────────────────────

  /**
   * Verifies that when an exception occurs during handleCreate, the handler
   * rolls back the session and returns HTTP 500.
   */
  @Test
  void testHandleExceptionRollsBackAndReturns500() throws Exception {
    Map<String, String> params = new HashMap<>();
    params.put("parentId", "bp-123");

    when(obDal.get(BusinessPartner.class, "bp-123")).thenThrow(new RuntimeException("DB error"));

    NeoContext ctx = buildContext("POST", null, new JSONObject(), params);
    NeoResponse response = handler.handle(ctx);

    assertNotNull(response);
    assertEquals(500, response.getHttpStatus());
    verify(sessionHandler).rollback();
  }

  // ── afterHandle() ───────────────────────────────────────────────────────

  /**
   * Verifies that afterHandle returns null for non-GET methods.
   */
  @Test
  void testAfterHandleNonGetReturnsNull() {
    NeoContext ctx = buildContext("POST", null, null, null);
    assertNull(handler.afterHandle(ctx));
  }

  /**
   * Verifies that afterHandle returns null for PUT.
   */
  @Test
  void testAfterHandlePutReturnsNull() {
    NeoContext ctx = buildContext("PUT", "some-id", null, null);
    assertNull(handler.afterHandle(ctx));
  }

  /**
   * Verifies that afterHandle with GET and recordId enriches the response with C_Location data.
   */
  @Test
  void testAfterHandleGetByIdEnrichesLocationData() throws Exception {
    Location geoLoc = mock(Location.class);
    when(geoLoc.getAddressLine1()).thenReturn("123 Main St");
    when(geoLoc.getAddressLine2()).thenReturn("Apt 4");
    when(geoLoc.getCityName()).thenReturn("Springfield");
    when(geoLoc.getPostalCode()).thenReturn("62704");

    Country country = mock(Country.class);
    when(country.getId()).thenReturn("US");
    // ETP-5022 — both getters are stubbed on purpose: getName() is the base-language value the
    // handler must NOT use, getIdentifier() is the translated one it must. Asserting the
    // translated value below is what fails if a call site ever slips back to getName().
    when(country.getName()).thenReturn("United States");
    when(country.getIdentifier()).thenReturn("Estados Unidos");
    when(geoLoc.getCountry()).thenReturn(country);

    Region region = mock(Region.class);
    when(region.getId()).thenReturn("IL");
    when(region.getName()).thenReturn("Illinois");
    when(geoLoc.getRegion()).thenReturn(region);

    when(obDal.get(Location.class, "geo-loc-id")).thenReturn(geoLoc);

    JSONObject jsonRecord = new JSONObject();
    jsonRecord.put("id", "bpl-id");
    jsonRecord.put("locationAddress", "geo-loc-id");

    NeoResponse previousResult = buildPreviousResult(jsonRecord);
    NeoContext ctx = buildContextWithPrevious("GET", "bpl-id", previousResult);

    NeoResponse response = handler.afterHandle(ctx);

    assertNotNull(response);
    assertEquals(200, response.getHttpStatus());

    JSONObject enriched = response.getBody()
        .getJSONObject("response")
        .getJSONArray("data")
        .getJSONObject(0);
    assertEquals("123 Main St", enriched.getString("addressLine1"));
    assertEquals("Apt 4", enriched.getString("addressLine2"));
    assertEquals("Springfield", enriched.getString("cityName"));
    assertEquals("62704", enriched.getString("postalCode"));
    assertEquals("US", enriched.getString("country"));
    assertEquals("Estados Unidos", enriched.getString("country$_identifier"));
    assertEquals("IL", enriched.getString("region"));
    assertEquals("Illinois", enriched.getString("region$_identifier"));
  }

  /**
   * Verifies that afterHandle returns null when the geoLoc is not found in the DB.
   */
  @Test
  void testAfterHandleGetByIdNullGeoLocReturnsNull() throws Exception {
    when(obDal.get(Location.class, "missing-geo")).thenReturn(null);

    JSONObject jsonRecord = new JSONObject();
    jsonRecord.put("id", "bpl-id");
    jsonRecord.put("locationAddress", "missing-geo");

    NeoResponse previousResult = buildPreviousResult(jsonRecord);
    NeoContext ctx = buildContextWithPrevious("GET", "bpl-id", previousResult);

    assertNull(handler.afterHandle(ctx));
  }

  /**
   * Verifies that afterHandle returns null when locationAddress field is missing.
   */
  @Test
  void testAfterHandleGetByIdMissingLocationAddressReturnsNull() throws Exception {
    JSONObject jsonRecord = new JSONObject();
    jsonRecord.put("id", "bpl-id");

    NeoResponse previousResult = buildPreviousResult(jsonRecord);
    NeoContext ctx = buildContextWithPrevious("GET", "bpl-id", previousResult);

    assertNull(handler.afterHandle(ctx));
  }

  /**
   * Verifies that afterHandle returns null when previousResult is null.
   */
  @Test
  void testAfterHandleGetNullPreviousResultReturnsNull() {
    NeoContext ctx = NeoContext.builder()
        .specName("contacts")
        .entityName("locationAddress")
        .httpMethod("GET")
        .recordId("some-id")
        .endpointType(NeoEndpointType.CRUD)
        .build();
    assertNull(handler.afterHandle(ctx));
  }

  /**
   * Verifies that afterHandle GET list replaces stale "Location" names with computed display names.
   */
  @Test
  void testAfterHandleGetListReplacesStaleNames() throws Exception {
    Location geoLoc = mock(Location.class);
    when(geoLoc.getCityName()).thenReturn("Madrid");
    when(geoLoc.getAddressLine1()).thenReturn("Calle Mayor 1");
    when(geoLoc.getCountry()).thenReturn(null);
    when(geoLoc.getRegion()).thenReturn(null);

    org.openbravo.model.common.businesspartner.Location bpLoc =
        mock(org.openbravo.model.common.businesspartner.Location.class);
    when(bpLoc.getLocationAddress()).thenReturn(geoLoc);
    when(obDal.get(org.openbravo.model.common.businesspartner.Location.class, "bpl-1"))
        .thenReturn(bpLoc);

    JSONObject rec1 = new JSONObject();
    rec1.put("id", "bpl-1");
    rec1.put("name", "Location");

    JSONObject rec2 = new JSONObject();
    rec2.put("id", "bpl-2");
    rec2.put("name", "Already Named");

    JSONArray dataArr = new JSONArray();
    dataArr.put(rec1);
    dataArr.put(rec2);

    JSONObject responseData = new JSONObject();
    responseData.put("status", 0);
    responseData.put("data", dataArr);
    JSONObject wrapper = new JSONObject();
    wrapper.put("response", responseData);

    NeoResponse previousResult = NeoResponse.ok(wrapper);

    NeoContext ctx = NeoContext.builder()
        .specName("contacts")
        .entityName("locationAddress")
        .httpMethod("GET")
        .endpointType(NeoEndpointType.CRUD)
        .previousResult(previousResult)
        .build();

    NeoResponse response = handler.afterHandle(ctx);

    assertNotNull(response);
    assertEquals(200, response.getHttpStatus());
    assertEquals("Madrid, Calle Mayor 1",
        response.getBody().getJSONObject("response").getJSONArray("data").getJSONObject(0).getString("name"));
    assertEquals("Already Named",
        response.getBody().getJSONObject("response").getJSONArray("data").getJSONObject(1).getString("name"));
  }

  /**
   * Verifies that afterHandle GET list returns null when no names need replacing.
   */
  @Test
  void testAfterHandleGetListNoStaleNamesReturnsNull() throws Exception {
    JSONObject rec1 = new JSONObject();
    rec1.put("id", "bpl-1");
    rec1.put("name", "Good Name");

    JSONArray dataArr = new JSONArray();
    dataArr.put(rec1);

    JSONObject responseData = new JSONObject();
    responseData.put("status", 0);
    responseData.put("data", dataArr);
    JSONObject wrapper = new JSONObject();
    wrapper.put("response", responseData);

    NeoResponse previousResult = NeoResponse.ok(wrapper);

    NeoContext ctx = NeoContext.builder()
        .specName("contacts")
        .entityName("locationAddress")
        .httpMethod("GET")
        .endpointType(NeoEndpointType.CRUD)
        .previousResult(previousResult)
        .build();

    assertNull(handler.afterHandle(ctx));
  }

  /**
   * Verifies that afterHandle GET list returns null when the data array is empty.
   */
  @Test
  void testAfterHandleGetListEmptyDataReturnsNull() throws Exception {
    JSONObject responseData = new JSONObject();
    responseData.put("status", 0);
    responseData.put("data", new JSONArray());
    JSONObject wrapper = new JSONObject();
    wrapper.put("response", responseData);

    NeoResponse previousResult = NeoResponse.ok(wrapper);

    NeoContext ctx = NeoContext.builder()
        .specName("contacts")
        .entityName("locationAddress")
        .httpMethod("GET")
        .endpointType(NeoEndpointType.CRUD)
        .previousResult(previousResult)
        .build();

    assertNull(handler.afterHandle(ctx));
  }

  /**
   * Verifies that afterHandle GET list handles records with empty name the same as "Location".
   */
  @Test
  void testAfterHandleGetListReplacesEmptyNames() throws Exception {
    Location geoLoc = mock(Location.class);
    when(geoLoc.getCityName()).thenReturn("Barcelona");
    when(geoLoc.getAddressLine1()).thenReturn(null);
    when(geoLoc.getCountry()).thenReturn(null);
    Region region = mock(Region.class);
    when(region.getName()).thenReturn("Catalonia");
    when(geoLoc.getRegion()).thenReturn(region);

    org.openbravo.model.common.businesspartner.Location bpLoc =
        mock(org.openbravo.model.common.businesspartner.Location.class);
    when(bpLoc.getLocationAddress()).thenReturn(geoLoc);
    when(obDal.get(org.openbravo.model.common.businesspartner.Location.class, "bpl-1"))
        .thenReturn(bpLoc);

    JSONObject rec = new JSONObject();
    rec.put("id", "bpl-1");
    rec.put("name", "");

    JSONArray dataArr = new JSONArray();
    dataArr.put(rec);

    JSONObject responseData = new JSONObject();
    responseData.put("status", 0);
    responseData.put("data", dataArr);
    JSONObject wrapper = new JSONObject();
    wrapper.put("response", responseData);

    NeoResponse previousResult = NeoResponse.ok(wrapper);

    NeoContext ctx = NeoContext.builder()
        .specName("contacts")
        .entityName("locationAddress")
        .httpMethod("GET")
        .endpointType(NeoEndpointType.CRUD)
        .previousResult(previousResult)
        .build();

    NeoResponse response = handler.afterHandle(ctx);
    assertNotNull(response);
    assertEquals("Barcelona",
        response.getBody().getJSONObject("response").getJSONArray("data").getJSONObject(0).getString("name"));
  }

  // ── Static helper methods via reflection ────────────────────────────────

  /**
   * Verifies that nullIfEmpty returns null for null input.
   */
  @Test
  void testNullIfEmptyReturnsNullForNull() throws Exception {
    Method method = ContactsLocationAddressHandler.class.getDeclaredMethod("nullIfEmpty", String.class);
    method.setAccessible(true);
    assertNull(method.invoke(null, (Object) null));
  }

  /**
   * Verifies that nullIfEmpty returns null for empty string.
   */
  @Test
  void testNullIfEmptyReturnsNullForEmpty() throws Exception {
    Method method = ContactsLocationAddressHandler.class.getDeclaredMethod("nullIfEmpty", String.class);
    method.setAccessible(true);
    assertNull(method.invoke(null, ""));
  }

  /**
   * Verifies that nullIfEmpty returns null for the literal "null" string.
   */
  @Test
  void testNullIfEmptyReturnsNullForLiteralNull() throws Exception {
    Method method = ContactsLocationAddressHandler.class.getDeclaredMethod("nullIfEmpty", String.class);
    method.setAccessible(true);
    assertNull(method.invoke(null, "null"));
  }

  /**
   * Verifies that nullIfEmpty returns the value for a non-empty string.
   */
  @Test
  void testNullIfEmptyReturnsValueForNonEmpty() throws Exception {
    Method method = ContactsLocationAddressHandler.class.getDeclaredMethod("nullIfEmpty", String.class);
    method.setAccessible(true);
    assertEquals("hello", method.invoke(null, "hello"));
  }

  /**
   * Verifies that boolField returns the default when the key is absent.
   */
  @Test
  void testBoolFieldReturnsDefaultWhenKeyAbsent() throws Exception {
    Method method = ContactsLocationAddressHandler.class.getDeclaredMethod(
        "boolField", JSONObject.class, String.class, boolean.class);
    method.setAccessible(true);

    JSONObject body = new JSONObject();
    assertEquals(true, method.invoke(null, body, "missing", true));
    assertEquals(false, method.invoke(null, body, "missing", false));
  }

  /**
   * Verifies that boolField returns true for "Y" (case-insensitive).
   */
  @Test
  void testBoolFieldReturnsTrueForY() throws Exception {
    Method method = ContactsLocationAddressHandler.class.getDeclaredMethod(
        "boolField", JSONObject.class, String.class, boolean.class);
    method.setAccessible(true);

    JSONObject body = new JSONObject();
    body.put("flag", "Y");
    assertEquals(true, method.invoke(null, body, "flag", false));

    body.put("flag", "y");
    assertEquals(true, method.invoke(null, body, "flag", false));
  }

  /**
   * Verifies that boolField returns true for "true" (case-insensitive).
   */
  @Test
  void testBoolFieldReturnsTrueForTrue() throws Exception {
    Method method = ContactsLocationAddressHandler.class.getDeclaredMethod(
        "boolField", JSONObject.class, String.class, boolean.class);
    method.setAccessible(true);

    JSONObject body = new JSONObject();
    body.put("flag", "true");
    assertEquals(true, method.invoke(null, body, "flag", false));

    body.put("flag", "TRUE");
    assertEquals(true, method.invoke(null, body, "flag", false));
  }

  /**
   * Verifies that boolField returns false for "N".
   */
  @Test
  void testBoolFieldReturnsFalseForN() throws Exception {
    Method method = ContactsLocationAddressHandler.class.getDeclaredMethod(
        "boolField", JSONObject.class, String.class, boolean.class);
    method.setAccessible(true);

    JSONObject body = new JSONObject();
    body.put("flag", "N");
    assertEquals(false, method.invoke(null, body, "flag", true));
  }

  /**
   * Verifies that str returns fallback when key is absent.
   */
  @Test
  void testStrReturnsFallbackWhenKeyAbsent() throws Exception {
    Method method = ContactsLocationAddressHandler.class.getDeclaredMethod(
        "str", JSONObject.class, String.class, String.class);
    method.setAccessible(true);

    JSONObject body = new JSONObject();
    assertEquals("fallback", method.invoke(null, body, "missing", "fallback"));
  }

  /**
   * Verifies that str returns the value when key is present.
   */
  @Test
  void testStrReturnsValueWhenKeyPresent() throws Exception {
    Method method = ContactsLocationAddressHandler.class.getDeclaredMethod(
        "str", JSONObject.class, String.class, String.class);
    method.setAccessible(true);

    JSONObject body = new JSONObject();
    body.put("name", "Hello");
    assertEquals("Hello", method.invoke(null, body, "name", "fallback"));
  }

  /**
   * Verifies that str returns fallback when key has empty value.
   */
  @Test
  void testStrReturnsFallbackWhenValueEmpty() throws Exception {
    Method method = ContactsLocationAddressHandler.class.getDeclaredMethod(
        "str", JSONObject.class, String.class, String.class);
    method.setAccessible(true);

    JSONObject body = new JSONObject();
    body.put("name", "");
    assertEquals("fallback", method.invoke(null, body, "name", "fallback"));
  }

  /**
   * Verifies joinNonNull with multiple non-null parts.
   */
  @Test
  void testJoinNonNullMultipleParts() throws Exception {
    Method method = ContactsLocationAddressHandler.class.getDeclaredMethod("joinNonNull", String[].class);
    method.setAccessible(true);
    assertEquals("a, b", method.invoke(null, (Object) new String[]{ "a", "b" }));
  }

  /**
   * Verifies joinNonNull with a single non-null part.
   */
  @Test
  void testJoinNonNullSinglePart() throws Exception {
    Method method = ContactsLocationAddressHandler.class.getDeclaredMethod("joinNonNull", String[].class);
    method.setAccessible(true);
    assertEquals("a", method.invoke(null, (Object) new String[]{ "a", null }));
  }

  /**
   * Verifies joinNonNull returns null when all parts are null.
   */
  @Test
  void testJoinNonNullAllNullReturnsNull() throws Exception {
    Method method = ContactsLocationAddressHandler.class.getDeclaredMethod("joinNonNull", String[].class);
    method.setAccessible(true);
    assertNull(method.invoke(null, (Object) new String[]{ null, null }));
  }

  /**
   * Verifies buildDisplayName with city + addressLine1.
   */
  @Test
  void testBuildDisplayNameWithCityAndAddress() throws Exception {
    Method method = ContactsLocationAddressHandler.class.getDeclaredMethod(
        "buildDisplayName", Location.class);
    method.setAccessible(true);

    Location geoLoc = mock(Location.class);
    when(geoLoc.getCityName()).thenReturn("Madrid");
    when(geoLoc.getAddressLine1()).thenReturn("Gran Via 1");

    assertEquals("Madrid, Gran Via 1", method.invoke(null, geoLoc));
  }

  /**
   * Verifies buildDisplayName falls back to region + country when city and address are null.
   */
  @Test
  void testBuildDisplayNameFallsBackToRegionAndCountry() throws Exception {
    Method method = ContactsLocationAddressHandler.class.getDeclaredMethod(
        "buildDisplayName", Location.class);
    method.setAccessible(true);

    Location geoLoc = mock(Location.class);
    when(geoLoc.getCityName()).thenReturn(null);
    when(geoLoc.getAddressLine1()).thenReturn(null);

    Region region = mock(Region.class);
    when(region.getName()).thenReturn("Catalonia");
    when(geoLoc.getRegion()).thenReturn(region);

    Country country = mock(Country.class);
    // ETP-5022 — both getters are stubbed on purpose: getName() is the base-language value the
    // handler must NOT use, getIdentifier() is the translated one it must. Asserting the
    // translated value below is what fails if a call site ever slips back to getName().
    when(country.getName()).thenReturn("Spain");
    when(country.getIdentifier()).thenReturn("España");
    when(geoLoc.getCountry()).thenReturn(country);

    assertEquals("Catalonia, España", method.invoke(null, geoLoc));
  }

  /**
   * Verifies buildDisplayName returns null when all fields are null.
   */
  @Test
  void testBuildDisplayNameReturnsNullWhenAllEmpty() throws Exception {
    Method method = ContactsLocationAddressHandler.class.getDeclaredMethod(
        "buildDisplayName", Location.class);
    method.setAccessible(true);

    Location geoLoc = mock(Location.class);
    when(geoLoc.getCityName()).thenReturn(null);
    when(geoLoc.getAddressLine1()).thenReturn(null);
    when(geoLoc.getRegion()).thenReturn(null);
    when(geoLoc.getCountry()).thenReturn(null);

    assertNull(method.invoke(null, geoLoc));
  }

  /**
   * Verifies buildDisplayName with only city (no addressLine1).
   */
  @Test
  void testBuildDisplayNameWithOnlyCity() throws Exception {
    Method method = ContactsLocationAddressHandler.class.getDeclaredMethod(
        "buildDisplayName", Location.class);
    method.setAccessible(true);

    Location geoLoc = mock(Location.class);
    when(geoLoc.getCityName()).thenReturn("Madrid");
    when(geoLoc.getAddressLine1()).thenReturn(null);

    assertEquals("Madrid", method.invoke(null, geoLoc));
  }

  // ── queryBPKey ──────────────────────────────────────────────────────────

  /**
   * queryBPKey must return null immediately when bpId is null (no DB hit).
   */
  @Test
  void testQueryBPKeyReturnsNullForNullBpId() throws Exception {
    Method method = ContactsLocationAddressHandler.class.getDeclaredMethod("queryBPKey", String.class);
    method.setAccessible(true);
    assertNull(method.invoke(null, (Object) null));
  }

  /**
   * queryBPKey must return null when bpId is blank (no DB hit).
   */
  @Test
  void testQueryBPKeyReturnsNullForEmptyBpId() throws Exception {
    Method method = ContactsLocationAddressHandler.class.getDeclaredMethod("queryBPKey", String.class);
    method.setAccessible(true);
    assertNull(method.invoke(null, ""));
  }

  /**
   * queryBPKey returns the key value from the DB when the row exists.
   */
  @Test
  void testQueryBPKeyReturnsValueFromDB() throws Exception {
    Method method = ContactsLocationAddressHandler.class.getDeclaredMethod("queryBPKey", String.class);
    method.setAccessible(true);

    Connection connMock = mock(Connection.class);
    PreparedStatement psMock = mock(PreparedStatement.class);
    ResultSet rsMock = mock(ResultSet.class);
    when(rsMock.next()).thenReturn(true);
    when(rsMock.getString("em_obtik_tax_id_key")).thenReturn("2");
    when(psMock.executeQuery()).thenReturn(rsMock);
    when(connMock.prepareStatement(anyString())).thenReturn(psMock);
    when(obDal.getConnection()).thenReturn(connMock);

    assertEquals("2", method.invoke(null, "bp-123"));
  }

  /**
   * queryBPKey returns null when the row is not found.
   */
  @Test
  void testQueryBPKeyReturnsNullWhenRowNotFound() throws Exception {
    Method method = ContactsLocationAddressHandler.class.getDeclaredMethod("queryBPKey", String.class);
    method.setAccessible(true);

    Connection connMock = mock(Connection.class);
    PreparedStatement psMock = mock(PreparedStatement.class);
    ResultSet rsMock = mock(ResultSet.class);
    when(rsMock.next()).thenReturn(false);
    when(psMock.executeQuery()).thenReturn(rsMock);
    when(connMock.prepareStatement(anyString())).thenReturn(psMock);
    when(obDal.getConnection()).thenReturn(connMock);

    assertNull(method.invoke(null, "bp-123"));
  }

  // ── checkAndAutoSetTaxKey: early return paths ───────────────────────────

  /**
   * When handleCreate receives a country but the pre-save key is already "2" (NOI),
   * checkAndAutoSetTaxKey returns immediately and no messages are injected.
   */
  @Test
  void testHandleCreateSkipsAutoSetWhenPreSaveKeyIsNOI() throws Exception {
    Map<String, String> params = new HashMap<>();
    params.put("parentId", "bp-123");

    BusinessPartner bp = mock(BusinessPartner.class);
    Client client = mock(Client.class);
    Organization org = mock(Organization.class);
    when(bp.getClient()).thenReturn(client);
    when(bp.getOrganization()).thenReturn(org);
    when(obDal.get(BusinessPartner.class, "bp-123")).thenReturn(bp);

    Location geoLoc = mock(Location.class);
    when(geoLoc.getId()).thenReturn("geo-id");
    when(obProvider.get(Location.class)).thenReturn(geoLoc);

    org.openbravo.model.common.businesspartner.Location bpLoc =
        mock(org.openbravo.model.common.businesspartner.Location.class);
    when(bpLoc.getId()).thenReturn("bp-loc-id");
    when(bpLoc.getName()).thenReturn("Addr");
    when(bpLoc.isShipToAddress()).thenReturn(Boolean.TRUE);
    when(bpLoc.isInvoiceToAddress()).thenReturn(Boolean.TRUE);
    when(obProvider.get(org.openbravo.model.common.businesspartner.Location.class)).thenReturn(bpLoc);

    // queryBPKey returns "2" — checkAndAutoSetTaxKey must exit immediately
    Connection connMock = mock(Connection.class);
    PreparedStatement psMock = mock(PreparedStatement.class);
    ResultSet rsMock = mock(ResultSet.class);
    when(rsMock.next()).thenReturn(true);
    when(rsMock.getString("em_obtik_tax_id_key")).thenReturn("2");
    when(psMock.executeQuery()).thenReturn(rsMock);
    when(connMock.prepareStatement(anyString())).thenReturn(psMock);
    when(obDal.getConnection()).thenReturn(connMock);

    JSONObject body = new JSONObject();
    body.put("name", "Test");
    body.put("country", "ES");

    NeoContext ctx = buildContext("POST", null, body, params);
    NeoResponse response = handler.handle(ctx);

    assertEquals(201, response.getHttpStatus());
    JSONObject dataRec = response.getBody().getJSONObject("response").getJSONArray("data").getJSONObject(0);
    assertNull(dataRec.optJSONArray("messages"));
  }

  /**
   * When the country is not an EU country, checkAndAutoSetTaxKey returns early
   * and no messages are injected.
   */
  @Test
  void testHandleCreateSkipsAutoSetWhenCountryIsNotEU() throws Exception {
    Map<String, String> params = new HashMap<>();
    params.put("parentId", "bp-123");

    BusinessPartner bp = mock(BusinessPartner.class);
    Client client = mock(Client.class);
    Organization org = mock(Organization.class);
    when(bp.getClient()).thenReturn(client);
    when(bp.getOrganization()).thenReturn(org);
    when(obDal.get(BusinessPartner.class, "bp-123")).thenReturn(bp);

    Location geoLoc = mock(Location.class);
    when(geoLoc.getId()).thenReturn("geo-id");
    when(obProvider.get(Location.class)).thenReturn(geoLoc);

    org.openbravo.model.common.businesspartner.Location bpLoc =
        mock(org.openbravo.model.common.businesspartner.Location.class);
    when(bpLoc.getId()).thenReturn("bp-loc-id");
    when(bpLoc.getName()).thenReturn("Addr");
    when(bpLoc.isShipToAddress()).thenReturn(Boolean.TRUE);
    when(bpLoc.isInvoiceToAddress()).thenReturn(Boolean.TRUE);
    when(obProvider.get(org.openbravo.model.common.businesspartner.Location.class)).thenReturn(bpLoc);

    // queryBPKey returns null (row not found), so preSaveKey != "2"
    // checkAndAutoSetTaxKey check 1: country is NOT EU (em_eucntry_iseucountry = "N")
    Connection connMock = mock(Connection.class);
    when(obDal.getConnection()).thenReturn(connMock);

    PreparedStatement psKey = mock(PreparedStatement.class);
    ResultSet rsKey = mock(ResultSet.class);
    when(rsKey.next()).thenReturn(false);
    when(psKey.executeQuery()).thenReturn(rsKey);

    PreparedStatement psCountry = mock(PreparedStatement.class);
    ResultSet rsCountry = mock(ResultSet.class);
    when(rsCountry.next()).thenReturn(true);
    when(rsCountry.getString("em_eucntry_iseucountry")).thenReturn("N");
    when(rsCountry.getString("countrycode")).thenReturn("US");
    when(psCountry.executeQuery()).thenReturn(rsCountry);

    when(connMock.prepareStatement(argThat(s -> s != null && s.contains("em_obtik_tax_id_key"))))
        .thenReturn(psKey);
    when(connMock.prepareStatement(argThat(s -> s != null && s.contains("em_eucntry_iseucountry"))))
        .thenReturn(psCountry);

    JSONObject body = new JSONObject();
    body.put("name", "Test");
    body.put("country", "US-COUNTRY-ID");

    NeoContext ctx = buildContext("POST", null, body, params);
    NeoResponse response = handler.handle(ctx);

    assertEquals(201, response.getHttpStatus());
    JSONObject dataRec = response.getBody().getJSONObject("response").getJSONArray("data").getJSONObject(0);
    assertNull(dataRec.optJSONArray("messages"));
  }

  /**
   * When the BP taxId is shorter than 2 characters, checkAndAutoSetTaxKey returns early.
   */
  @Test
  void testHandleCreateSkipsAutoSetWhenTaxIdTooShort() throws Exception {
    Map<String, String> params = new HashMap<>();
    params.put("parentId", "bp-123");

    BusinessPartner bp = mock(BusinessPartner.class);
    Client client = mock(Client.class);
    Organization org = mock(Organization.class);
    when(bp.getClient()).thenReturn(client);
    when(bp.getOrganization()).thenReturn(org);
    when(obDal.get(BusinessPartner.class, "bp-123")).thenReturn(bp);

    Location geoLoc = mock(Location.class);
    when(geoLoc.getId()).thenReturn("geo-id");
    when(obProvider.get(Location.class)).thenReturn(geoLoc);

    org.openbravo.model.common.businesspartner.Location bpLoc =
        mock(org.openbravo.model.common.businesspartner.Location.class);
    when(bpLoc.getId()).thenReturn("bp-loc-id");
    when(bpLoc.getName()).thenReturn("Addr");
    when(bpLoc.isShipToAddress()).thenReturn(Boolean.TRUE);
    when(bpLoc.isInvoiceToAddress()).thenReturn(Boolean.TRUE);
    when(obProvider.get(org.openbravo.model.common.businesspartner.Location.class)).thenReturn(bpLoc);

    Connection connMock = mock(Connection.class);
    when(obDal.getConnection()).thenReturn(connMock);

    // queryBPKey → null (no row)
    PreparedStatement psKey = mock(PreparedStatement.class);
    ResultSet rsKey = mock(ResultSet.class);
    when(rsKey.next()).thenReturn(false);
    when(psKey.executeQuery()).thenReturn(rsKey);

    // country check → EU, not ES
    PreparedStatement psCountry = mock(PreparedStatement.class);
    ResultSet rsCountry = mock(ResultSet.class);
    when(rsCountry.next()).thenReturn(true);
    when(rsCountry.getString("em_eucntry_iseucountry")).thenReturn("Y");
    when(rsCountry.getString("countrycode")).thenReturn("FR");
    when(psCountry.executeQuery()).thenReturn(rsCountry);

    // taxId query → "X" (single char — too short)
    PreparedStatement psTax = mock(PreparedStatement.class);
    ResultSet rsTax = mock(ResultSet.class);
    when(rsTax.next()).thenReturn(true);
    when(rsTax.getString("taxid")).thenReturn("X");
    when(psTax.executeQuery()).thenReturn(rsTax);

    when(connMock.prepareStatement(argThat(s -> s != null && s.contains("em_obtik_tax_id_key"))))
        .thenReturn(psKey);
    when(connMock.prepareStatement(argThat(s -> s != null && s.contains("em_eucntry_iseucountry"))))
        .thenReturn(psCountry);
    when(connMock.prepareStatement(argThat(s -> s != null && s.contains("taxid"))))
        .thenReturn(psTax);

    JSONObject body = new JSONObject();
    body.put("name", "Test");
    body.put("country", "FR-COUNTRY-ID");

    NeoContext ctx = buildContext("POST", null, body, params);
    NeoResponse response = handler.handle(ctx);

    assertEquals(201, response.getHttpStatus());
    JSONObject dataRec = response.getBody().getJSONObject("response").getJSONArray("data").getJSONObject(0);
    assertNull(dataRec.optJSONArray("messages"));
  }

  /**
   * Happy path: EU non-ES country, taxId prefix matches EU country, key auto-set to "2",
   * VIES resolves to "V" — a "warning" (tax key auto-set notification) message is injected.
   */
  @Test
  void testHandleCreateInjectsMessageWhenTaxKeyAutoSet() throws Exception {
    Map<String, String> params = new HashMap<>();
    params.put("parentId", "bp-123");

    BusinessPartner bp = mock(BusinessPartner.class);
    Client client = mock(Client.class);
    Organization org = mock(Organization.class);
    when(bp.getClient()).thenReturn(client);
    when(bp.getOrganization()).thenReturn(org);
    when(obDal.get(BusinessPartner.class, "bp-123")).thenReturn(bp);

    Location geoLoc = mock(Location.class);
    when(geoLoc.getId()).thenReturn("geo-id");
    when(obProvider.get(Location.class)).thenReturn(geoLoc);

    org.openbravo.model.common.businesspartner.Location bpLoc =
        mock(org.openbravo.model.common.businesspartner.Location.class);
    when(bpLoc.getId()).thenReturn("bp-loc-id");
    when(bpLoc.getName()).thenReturn("Addr");
    when(bpLoc.isShipToAddress()).thenReturn(Boolean.TRUE);
    when(bpLoc.isInvoiceToAddress()).thenReturn(Boolean.TRUE);
    when(obProvider.get(org.openbravo.model.common.businesspartner.Location.class)).thenReturn(bpLoc);

    Connection connMock = mock(Connection.class);
    when(obDal.getConnection()).thenReturn(connMock);

    // queryBPKey → null (preSaveKey = null, not "2")
    PreparedStatement psKey = mock(PreparedStatement.class);
    ResultSet rsKey = mock(ResultSet.class);
    when(rsKey.next()).thenReturn(false);
    when(psKey.executeQuery()).thenReturn(rsKey);

    // country check → EU, not ES
    PreparedStatement psCountry = mock(PreparedStatement.class);
    ResultSet rsCountry = mock(ResultSet.class);
    when(rsCountry.next()).thenReturn(true);
    when(rsCountry.getString("em_eucntry_iseucountry")).thenReturn("Y");
    when(rsCountry.getString("countrycode")).thenReturn("FR");
    when(psCountry.executeQuery()).thenReturn(rsCountry);

    // taxId → "FR12345678"
    PreparedStatement psTax = mock(PreparedStatement.class);
    ResultSet rsTax = mock(ResultSet.class);
    when(rsTax.next()).thenReturn(true);
    when(rsTax.getString("taxid")).thenReturn("FR12345678");
    when(psTax.executeQuery()).thenReturn(rsTax);

    // prefix match count → 1 (FR is EU, not ES)
    PreparedStatement psCount = mock(PreparedStatement.class);
    ResultSet rsCount = mock(ResultSet.class);
    when(rsCount.next()).thenReturn(true);
    when(rsCount.getInt(1)).thenReturn(1);
    when(psCount.executeQuery()).thenReturn(rsCount);

    // UPDATE c_bpartner SET em_obtik_tax_id_key = '2'
    PreparedStatement psUpdateKey = mock(PreparedStatement.class);

    // UPDATE c_bpartner SET em_obtik_viesstatus
    PreparedStatement psUpdateVies = mock(PreparedStatement.class);

    when(connMock.prepareStatement(argThat(s -> s != null && s.contains("em_obtik_tax_id_key") && s.contains("SELECT"))))
        .thenReturn(psKey);
    when(connMock.prepareStatement(argThat(s -> s != null && s.contains("em_eucntry_iseucountry"))))
        .thenReturn(psCountry);
    when(connMock.prepareStatement(argThat(s -> s != null && s.contains("taxid"))))
        .thenReturn(psTax);
    when(connMock.prepareStatement(argThat(s -> s != null && s.contains("SUBSTRING"))))
        .thenReturn(psCount);
    when(connMock.prepareStatement(argThat(s -> s != null && s.contains("em_obtik_tax_id_key") && s.contains("UPDATE"))))
        .thenReturn(psUpdateKey);
    when(connMock.prepareStatement(argThat(s -> s != null && s.contains("em_obtik_viesstatus"))))
        .thenReturn(psUpdateVies);

    try (MockedStatic<ViesService> mVies = mockStatic(ViesService.class);
         MockedStatic<OBMessageUtils> mMsg = mockStatic(OBMessageUtils.class)) {

      java.lang.reflect.Constructor<ViesService.ViesResult> ctor =
          ViesService.ViesResult.class.getDeclaredConstructor(String.class, String.class, String.class);
      ctor.setAccessible(true);
      ViesService.ViesResult viesResult = ctor.newInstance("V", null, null);
      mVies.when(() -> ViesService.checkVat(anyString())).thenReturn(viesResult);
      mMsg.when(() -> OBMessageUtils.messageBD(anyString())).thenReturn("msg");

      JSONObject body = new JSONObject();
      body.put("name", "Test");
      body.put("country", "FR-COUNTRY-ID");

      NeoContext ctx = buildContext("POST", null, body, params);
      NeoResponse response = handler.handle(ctx);

      assertEquals(201, response.getHttpStatus());
      JSONObject dataRec = response.getBody().getJSONObject("response").getJSONArray("data").getJSONObject(0);
      assertNotNull(dataRec.optJSONArray("messages"));
      assertEquals("warning", dataRec.getJSONArray("messages").getJSONObject(0).getString("type"));
    }
  }

  /**
   * handleUpdate with country and preSaveKey already "2" skips auto-set and returns 200.
   */
  @Test
  void testHandleUpdateSkipsAutoSetWhenPreSaveKeyIsNOI() throws Exception {
    Location geoLoc = mock(Location.class);
    when(geoLoc.getId()).thenReturn("geo-id");

    org.openbravo.model.common.businesspartner.Location bpLoc =
        mock(org.openbravo.model.common.businesspartner.Location.class);
    when(bpLoc.getId()).thenReturn("bpl-id");
    when(bpLoc.getName()).thenReturn("Name");
    when(bpLoc.isShipToAddress()).thenReturn(Boolean.TRUE);
    when(bpLoc.isInvoiceToAddress()).thenReturn(Boolean.TRUE);
    when(bpLoc.getLocationAddress()).thenReturn(geoLoc);

    BusinessPartner bp = mock(BusinessPartner.class);
    when(bp.getId()).thenReturn("bp-123");
    when(bpLoc.getBusinessPartner()).thenReturn(bp);

    when(obDal.get(org.openbravo.model.common.businesspartner.Location.class, "bpl-id"))
        .thenReturn(bpLoc);

    // queryBPKey returns "2"
    Connection connMock = mock(Connection.class);
    PreparedStatement psMock = mock(PreparedStatement.class);
    ResultSet rsMock = mock(ResultSet.class);
    when(rsMock.next()).thenReturn(true);
    when(rsMock.getString("em_obtik_tax_id_key")).thenReturn("2");
    when(psMock.executeQuery()).thenReturn(rsMock);
    when(connMock.prepareStatement(anyString())).thenReturn(psMock);
    when(obDal.getConnection()).thenReturn(connMock);

    JSONObject body = new JSONObject();
    body.put("country", "FR-COUNTRY-ID");

    NeoContext ctx = buildContext("PUT", "bpl-id", body, null);
    NeoResponse response = handler.handle(ctx);

    assertEquals(200, response.getHttpStatus());
    JSONObject dataRec = response.getBody().getJSONObject("response").getJSONArray("data").getJSONObject(0);
    assertNull(dataRec.optJSONArray("messages"));
  }

  // ── Private helpers ─────────────────────────────────────────────────────

  private NeoContext buildContext(String method, String recordId, JSONObject body,
      Map<String, String> queryParams) {
    return NeoContext.builder()
        .specName("contacts")
        .entityName("locationAddress")
        .httpMethod(method)
        .recordId(recordId)
        .requestBody(body)
        .queryParams(queryParams != null ? queryParams : Collections.emptyMap())
        .endpointType(NeoEndpointType.CRUD)
        .build();
  }

  private NeoContext buildContextWithPrevious(String method, String recordId,
      NeoResponse previousResult) {
    return NeoContext.builder()
        .specName("contacts")
        .entityName("locationAddress")
        .httpMethod(method)
        .recordId(recordId)
        .endpointType(NeoEndpointType.CRUD)
        .previousResult(previousResult)
        .build();
  }

  private NeoResponse buildPreviousResult(JSONObject jsonRecord) throws Exception {
    JSONArray dataArr = new JSONArray();
    dataArr.put(jsonRecord);
    JSONObject responseData = new JSONObject();
    responseData.put("status", 0);
    responseData.put("data", dataArr);
    JSONObject wrapper = new JSONObject();
    wrapper.put("response", responseData);
    return NeoResponse.ok(wrapper);
  }

  // ── resolveRegionByName (ETP-4997) ──────────────────────────────────────

  /**
   * The province used to be resolved in the browser and then dropped in silence: scoping the
   * candidates by country required fetching each one's country from
   * {@code /sws/neo/contacts/region}, which no NEO spec exposes, so every candidate was
   * discarded and the descriptor skipped the field without an error. These tests pin the
   * server-side replacement, whose two hard cases are the seed data's System-plus-tenant
   * duplicate of every Spanish province and a hand-typed name missing its accent.
   */
  @Test
  void testResolveRegionPrefersTheTenantsOwnRowOverTheSystemCopy() throws Exception {
    // Exactly the shipped data: 'MADRID' at System level and 'MADRID ' (trailing space) for the
    // tenant, both active, both readable. Name matching alone finds two; the client decides.
    Region systemRow = mockRegion("MADRID", "0");
    Region tenantRow = mockRegion("MADRID ", "T1");
    stubRegionsOfCountry(Arrays.asList(systemRow, tenantRow));
    stubCurrentClient("T1");

    assertSame(tenantRow, invokeResolveRegionByName("Madrid", mockCountry("Spain")));
  }

  @Test
  void testResolveRegionFallsBackToTheSystemRowWhenTheTenantHasNoneOfItsOwn() throws Exception {
    Region systemRow = mockRegion("MADRID", "0");
    stubRegionsOfCountry(Collections.singletonList(systemRow));
    stubCurrentClient("T1");

    // preferOwnClient must not narrow to nothing — a tenant that never got its own copy still
    // has to be able to import an address.
    assertSame(systemRow, invokeResolveRegionByName("madrid", mockCountry("Spain")));
  }

  @Test
  void testResolveRegionIgnoresAccentsAndCase() throws Exception {
    // The fuzzy search this replaced absorbed a missing accent; an exact comparison would not,
    // and "Alava"/"A Coruna" is what a human actually types.
    Region alava = mockRegion("ÁLAVA", "0");
    stubRegionsOfCountry(Collections.singletonList(alava));
    stubCurrentClient("T1");

    assertSame(alava, invokeResolveRegionByName("alava", mockCountry("Spain")));
  }

  @Test
  void testResolveRegionReturnsNullForABlankValueWithoutQuerying() throws Exception {
    assertNull(invokeResolveRegionByName("   ", mockCountry("Spain")));
    verify(obDal, org.mockito.Mockito.never()).createQuery(eq(Region.class), anyString());
  }

  @Test
  void testResolveRegionRefusesAnUnknownNameAndNamesItAndTheCountry() throws Exception {
    stubRegionsOfCountry(Collections.singletonList(mockRegion("MADRID", "0")));
    stubCurrentClient("T1");

    OBException thrown = assertThrows(OBException.class,
        () -> invokeResolveRegionByName("Nowhereland", mockCountry("Spain")));
    // The message is what the import shows on the failing row, so both halves have to be in it
    // or the user cannot tell a typo from a missing master record.
    assertTrue(thrown.getMessage().contains("Nowhereland"));
    assertTrue(thrown.getMessage().contains("Spain"));
  }

  @Test
  void testResolveRegionRefusesWhenThereIsNoCountryToScopeBy() throws Exception {
    OBException thrown = assertThrows(OBException.class,
        () -> invokeResolveRegionByName("Córdoba", null));
    assertTrue(thrown.getMessage().contains("country"));
  }

  @Test
  void testResolveRegionRefusesAGenuineAmbiguityInsideOneClient() throws Exception {
    // Two rows of the SAME client cannot be told apart by any rule, so this must fail rather
    // than pick one and write the wrong province.
    stubRegionsOfCountry(Arrays.asList(mockRegion("MADRID", "T1"), mockRegion("Madrid ", "T1")));
    stubCurrentClient("T1");

    OBException thrown = assertThrows(OBException.class,
        () -> invokeResolveRegionByName("Madrid", mockCountry("Spain")));
    assertTrue(thrown.getMessage().contains("ambiguous"));
  }

  /**
   * A whitespace-only {@code regionName} must be indistinguishable from an absent one. It was
   * not: {@code nullIfEmpty} only rejects "", so "   " reached the resolver, which answers null
   * for a blank name, and the region was CLEARED — a whitespace cell in a re-imported file
   * erasing a province already on the record. Found in PR review.
   */
  @Test
  void testWhitespaceOnlyRegionNameLeavesTheRegionUntouched() throws Exception {
    Region existing = mockRegion("MADRID", "T1");
    // Built BEFORE the stubbing: mockCountry() stubs its own mock, and Mockito rejects a
    // stubbing started inside another one's argument (UnfinishedStubbingException).
    Country country = mockCountry("Spain");
    Location geoLoc = mock(Location.class);
    when(geoLoc.getRegion()).thenReturn(existing);
    when(geoLoc.getCountry()).thenReturn(country);

    JSONObject body = new JSONObject();
    body.put("regionName", "   ");
    invokeApplyGeoLocFields(body, geoLoc);

    // Neither set to something else NOR cleared: the field is simply not mentioned.
    verify(geoLoc, org.mockito.Mockito.never()).setRegion(any());
  }

  @Test
  void testAnExplicitRegionIdOfBlankStillClears() throws Exception {
    // Clearing stays the id field's job — that is what the Location modal's selector sends.
    Country country = mockCountry("Spain");
    Location geoLoc = mock(Location.class);
    when(geoLoc.getCountry()).thenReturn(country);

    JSONObject body = new JSONObject();
    body.put("region", "");
    invokeApplyGeoLocFields(body, geoLoc);

    verify(geoLoc).setRegion(null);
  }

  private void invokeApplyGeoLocFields(JSONObject body, Location geoLoc) throws Exception {
    Method method = ContactsLocationAddressHandler.class
        .getDeclaredMethod("applyGeoLocFields", JSONObject.class, Location.class);
    method.setAccessible(true);
    try {
      method.invoke(null, body, geoLoc);
    } catch (InvocationTargetException e) {
      Throwable cause = e.getCause();
      if (cause instanceof RuntimeException) {
        throw (RuntimeException) cause;
      }
      throw (Exception) cause;
    }
  }

  private Region mockRegion(String name, String clientId) {
    Region region = mock(Region.class);
    when(region.getName()).thenReturn(name);
    Client client = mock(Client.class);
    when(client.getId()).thenReturn(clientId);
    when(region.getClient()).thenReturn(client);
    return region;
  }

  private Country mockCountry(String name) {
    Country country = mock(Country.class);
    when(country.getId()).thenReturn("C-" + name);
    when(country.getName()).thenReturn(name);
    return country;
  }

  private void stubRegionsOfCountry(List<Region> regions) {
    @SuppressWarnings("unchecked")
    OBQuery<Region> query = mock(OBQuery.class);
    when(obDal.createQuery(eq(Region.class), anyString())).thenReturn(query);
    when(query.setNamedParameter(anyString(), any())).thenReturn(query);
    when(query.list()).thenReturn(regions);
  }

  private void stubCurrentClient(String clientId) {
    OBContext context = mock(OBContext.class);
    Client client = mock(Client.class);
    when(client.getId()).thenReturn(clientId);
    when(context.getCurrentClient()).thenReturn(client);
    obContextMock.when(OBContext::getOBContext).thenReturn(context);
  }

  /** Invokes the private static resolver, unwrapping reflection's exception wrapper. */
  private Region invokeResolveRegionByName(String rawName, Country country) throws Exception {
    Method method = ContactsLocationAddressHandler.class
        .getDeclaredMethod("resolveRegionByName", String.class, Country.class);
    method.setAccessible(true);
    try {
      return (Region) method.invoke(null, rawName, country);
    } catch (InvocationTargetException e) {
      Throwable cause = e.getCause();
      if (cause instanceof RuntimeException) {
        throw (RuntimeException) cause;
      }
      throw (Exception) cause;
    }
  }
}
