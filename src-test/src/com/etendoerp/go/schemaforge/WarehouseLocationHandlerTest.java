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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;

import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.core.SessionHandler;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.common.geography.Country;
import org.openbravo.model.common.geography.Location;
import org.openbravo.model.common.geography.Region;

/**
 * Unit tests for {@link WarehouseLocationHandler}.
 *
 * <p>Covers: handle() dispatch gating (non-CRUD endpoint types fall through so
 * {@code NeoSelectorService} can serve selectors — the ETP-4526 regression),
 * unmatched CRUD combos (405), handleCreate, handleUpdate, handleGetById, and the
 * private field-mapping/display-name helpers exercised indirectly through those
 * three public-facing flows.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WarehouseLocationHandlerTest {

  private static final String SPEC_WAREHOUSE = "warehouse";
  private static final String ENTITY_LOCATION = "location";

  private WarehouseLocationHandler handler;

  private OBDal obDal;
  private OBProvider obProvider;
  private SessionHandler sessionHandler;
  private OBContext obContext;
  private Client client;
  private Organization organization;

  private MockedStatic<OBDal> obDalMock;
  private MockedStatic<OBContext> obContextStaticMock;
  private MockedStatic<OBProvider> obProviderMock;
  private MockedStatic<SessionHandler> sessionHandlerMock;

  @BeforeEach
  void setUp() {
    handler = new WarehouseLocationHandler();

    obDal = mock(OBDal.class);
    obProvider = mock(OBProvider.class);
    sessionHandler = mock(SessionHandler.class);
    obContext = mock(OBContext.class);
    client = mock(Client.class);
    organization = mock(Organization.class);

    obDalMock = mockStatic(OBDal.class);
    obContextStaticMock = mockStatic(OBContext.class);
    obProviderMock = mockStatic(OBProvider.class);
    sessionHandlerMock = mockStatic(SessionHandler.class);

    obDalMock.when(OBDal::getInstance).thenReturn(obDal);
    obProviderMock.when(OBProvider::getInstance).thenReturn(obProvider);
    sessionHandlerMock.when(SessionHandler::getInstance).thenReturn(sessionHandler);

    when(obContext.getCurrentClient()).thenReturn(client);
    when(obContext.getCurrentOrganization()).thenReturn(organization);
    when(client.getId()).thenReturn("client-1");
    when(organization.getId()).thenReturn("org-1");
  }

  @AfterEach
  void tearDown() {
    obDalMock.close();
    obContextStaticMock.close();
    obProviderMock.close();
    sessionHandlerMock.close();
  }

  // ── handle() dispatch gating ─────────────────────────────────────────────

  /**
   * A SELECTOR sub-endpoint request (country/region pickers) must fall through
   * to {@code NeoSelectorService} untouched — this is the ETP-4526 regression:
   * the handler used to swallow it with a 405 because it never checked
   * {@code endpointType}, breaking the Location modal's country/region pickers.
   */
  @Test
  void testSelectorEndpointReturnsNull() {
    NeoContext ctx = NeoContext.builder()
        .specName(SPEC_WAREHOUSE).entityName(ENTITY_LOCATION)
        .httpMethod("GET").endpointType(NeoEndpointType.SELECTOR)
        .fieldName("C_Country_ID").build();
    assertNull(handler.handle(ctx));
  }

  /**
   * Any other non-CRUD endpoint type (action, callout, defaults, ...) must also
   * fall through, not just SELECTOR.
   */
  @Test
  void testActionEndpointReturnsNull() {
    NeoContext ctx = NeoContext.builder()
        .specName(SPEC_WAREHOUSE).entityName(ENTITY_LOCATION)
        .httpMethod("POST").endpointType(NeoEndpointType.ACTION)
        .fieldName("someAction").build();
    assertNull(handler.handle(ctx));
  }

  /**
   * The entity is tab-less: there is no generic CRUD fallback. A POST that
   * carries a recordId doesn't match the create branch, so it must be refused
   * with 405 rather than silently doing nothing.
   */
  @Test
  void testPostWithRecordIdReturns405() {
    NeoResponse r = handler.handle(buildContext("POST", "loc-1", null));
    assertNotNull(r);
    assertEquals(405, r.getHttpStatus());
  }

  /**
   * A PUT without a recordId doesn't match the update branch — 405.
   */
  @Test
  void testPutWithoutRecordIdReturns405() {
    NeoResponse r = handler.handle(buildContext("PUT", null, null));
    assertNotNull(r);
    assertEquals(405, r.getHttpStatus());
  }

  /**
   * A GET without a recordId under the CRUD endpoint type (list semantics)
   * has no generic fallback and must be refused with 405.
   */
  @Test
  void testGetWithoutRecordIdReturns405() {
    NeoResponse r = handler.handle(buildContext("GET", null, null));
    assertNotNull(r);
    assertEquals(405, r.getHttpStatus());
  }

  /**
   * DELETE is never supported by this handler.
   */
  @Test
  void testDeleteReturns405() {
    NeoResponse r = handler.handle(buildContext("DELETE", "loc-1", null));
    assertNotNull(r);
    assertEquals(405, r.getHttpStatus());
  }

  // ── handleCreate ──────────────────────────────────────────────────────────

  /**
   * Full happy path: client/org resolved from the context, all address fields
   * mapped, country and region resolved and set, entity saved and flushed, and
   * the 201 response echoes back the new record with resolved identifiers.
   */
  @Test
  void testCreateHappyPathSetsAllFieldsAndReturns201() throws Exception {
    Location geoLoc = mock(Location.class);
    when(geoLoc.getId()).thenReturn("geo-1");
    when(geoLoc.getCityName()).thenReturn("Springfield");
    when(geoLoc.getAddressLine1()).thenReturn("123 Main St");
    when(geoLoc.getAddressLine2()).thenReturn("Apt 4");
    when(geoLoc.getPostalCode()).thenReturn("62704");
    when(obProvider.get(Location.class)).thenReturn(geoLoc);

    when(obDal.get(Client.class, "client-1")).thenReturn(client);
    when(obDal.get(Organization.class, "org-1")).thenReturn(organization);

    Country country = mock(Country.class);
    when(country.getId()).thenReturn("country-id");
    when(country.getName()).thenReturn("Spain");
    when(obDal.get(Country.class, "country-id")).thenReturn(country);
    when(geoLoc.getCountry()).thenReturn(country);

    Region region = mock(Region.class);
    when(region.getId()).thenReturn("region-id");
    when(region.getName()).thenReturn("Catalonia");
    when(obDal.get(Region.class, "region-id")).thenReturn(region);
    when(geoLoc.getRegion()).thenReturn(region);

    JSONObject body = new JSONObject();
    body.put("addressLine1", "123 Main St");
    body.put("addressLine2", "Apt 4");
    body.put("cityName", "Springfield");
    body.put("postalCode", "62704");
    body.put("country", "country-id");
    body.put("region", "region-id");

    NeoResponse r = handler.handle(buildContext("POST", null, body));

    assertNotNull(r);
    assertEquals(201, r.getHttpStatus());

    verify(geoLoc).setClient(client);
    verify(geoLoc).setOrganization(organization);
    verify(geoLoc).setActive(Boolean.TRUE);
    verify(geoLoc).setAddressLine1("123 Main St");
    verify(geoLoc).setAddressLine2("Apt 4");
    verify(geoLoc).setCityName("Springfield");
    verify(geoLoc).setPostalCode("62704");
    verify(geoLoc).setCountry(country);
    verify(geoLoc).setRegion(region);
    verify(obDal).save(geoLoc);
    verify(obDal).flush();
    obContextStaticMock.verify(() -> OBContext.setAdminMode(true));
    obContextStaticMock.verify(OBContext::restorePreviousMode);

    JSONObject rec = r.getBody().getJSONObject("response").getJSONArray("data").getJSONObject(0);
    assertEquals("geo-1", rec.getString("id"));
    assertEquals("Springfield, 123 Main St", rec.getString("name"));
    assertEquals("country-id", rec.getString("country"));
    assertEquals("Spain", rec.getString("country$_identifier"));
    assertEquals("region-id", rec.getString("region"));
    assertEquals("Catalonia", rec.getString("region$_identifier"));
  }

  /**
   * When the country id in the body does not resolve to a real {@link Country},
   * the handler must NOT call {@code setCountry} — leaving the field untouched
   * rather than setting it to null.
   */
  @Test
  void testCreateCountryNotFoundDoesNotSetCountry() throws Exception {
    Location geoLoc = mock(Location.class);
    when(geoLoc.getId()).thenReturn("geo-2");
    when(obProvider.get(Location.class)).thenReturn(geoLoc);
    when(obDal.get(Country.class, "missing-country")).thenReturn(null);

    JSONObject body = new JSONObject();
    body.put("country", "missing-country");

    NeoResponse r = handler.handle(buildContext("POST", null, body));

    assertEquals(201, r.getHttpStatus());
    verify(geoLoc, never()).setCountry(any());
  }

  /**
   * When the body has no {@code region} key at all, the region must be left
   * completely untouched (as opposed to being explicitly cleared).
   */
  @Test
  void testCreateNoRegionKeyLeavesRegionUntouched() throws Exception {
    Location geoLoc = mock(Location.class);
    when(geoLoc.getId()).thenReturn("geo-3");
    when(obProvider.get(Location.class)).thenReturn(geoLoc);

    NeoResponse r = handler.handle(buildContext("POST", null, new JSONObject()));

    assertEquals(201, r.getHttpStatus());
    verify(geoLoc, never()).setRegion(any());
  }

  /**
   * An explicit empty-string {@code region} in the body means "clear the
   * region" — {@code setRegion(null)} must be called.
   */
  @Test
  void testCreateEmptyRegionClearsRegion() throws Exception {
    Location geoLoc = mock(Location.class);
    when(geoLoc.getId()).thenReturn("geo-4");
    when(obProvider.get(Location.class)).thenReturn(geoLoc);

    JSONObject body = new JSONObject();
    body.put("region", "");

    NeoResponse r = handler.handle(buildContext("POST", null, body));

    assertEquals(201, r.getHttpStatus());
    verify(geoLoc).setRegion(isNull());
  }

  /**
   * Exercises every branch of {@code nullIfEmpty} in one pass: an absent key,
   * an empty string, the literal {@code "null"}, and a real value.
   */
  @Test
  void testCreateNullIfEmptyVariants() throws Exception {
    Location geoLoc = mock(Location.class);
    when(geoLoc.getId()).thenReturn("geo-5");
    when(obProvider.get(Location.class)).thenReturn(geoLoc);

    JSONObject body = new JSONObject();
    // addressLine1 intentionally absent
    body.put("addressLine2", "");
    body.put("cityName", "null");
    body.put("postalCode", "123 Main");

    NeoResponse r = handler.handle(buildContext("POST", null, body));

    assertEquals(201, r.getHttpStatus());
    verify(geoLoc).setAddressLine1(isNull());
    verify(geoLoc).setAddressLine2(isNull());
    verify(geoLoc).setCityName(isNull());
    verify(geoLoc).setPostalCode("123 Main");
  }

  /**
   * An unexpected runtime exception during create must roll back the session
   * and surface as HTTP 500, never leak a stack trace to the caller.
   */
  @Test
  void testCreateExceptionRollsBackAndReturns500() {
    when(obProvider.get(Location.class)).thenThrow(new RuntimeException("DB exploded"));

    NeoResponse r = handler.handle(buildContext("POST", null, new JSONObject()));

    assertNotNull(r);
    assertEquals(500, r.getHttpStatus());
    verify(sessionHandler).rollback();
  }

  // ── handleUpdate ──────────────────────────────────────────────────────────

  /**
   * Happy path update: existing C_Location is found, fields are re-applied,
   * flushed (no explicit save — Hibernate dirty-checks the managed entity),
   * and the 200 response echoes the updated record.
   */
  @Test
  void testUpdateHappyPathReturns200() throws Exception {
    Location geoLoc = mock(Location.class);
    when(geoLoc.getId()).thenReturn("geo-upd");
    when(obDal.get(Location.class, "geo-upd")).thenReturn(geoLoc);

    JSONObject body = new JSONObject();
    body.put("addressLine1", "456 Oak Ave");

    NeoResponse r = handler.handle(buildContext("PUT", "geo-upd", body));

    assertNotNull(r);
    assertEquals(200, r.getHttpStatus());
    verify(geoLoc).setAddressLine1("456 Oak Ave");
    verify(obDal, never()).save(any());
    verify(obDal).flush();

    JSONObject rec = r.getBody().getJSONObject("response").getJSONArray("data").getJSONObject(0);
    assertEquals("geo-upd", rec.getString("id"));
  }

  /**
   * Updating a non-existent id must return 404 and never touch the DAL beyond
   * the initial lookup.
   */
  @Test
  void testUpdateNotFoundReturns404() {
    when(obDal.get(Location.class, "missing")).thenReturn(null);

    NeoResponse r = handler.handle(buildContext("PUT", "missing", new JSONObject()));

    assertNotNull(r);
    assertEquals(404, r.getHttpStatus());
    verify(obDal, never()).flush();
  }

  /**
   * Update with both country and region ids resolves and sets both.
   */
  @Test
  void testUpdateWithCountryAndRegionSetsBoth() throws Exception {
    Location geoLoc = mock(Location.class);
    when(geoLoc.getId()).thenReturn("geo-upd-2");
    when(obDal.get(Location.class, "geo-upd-2")).thenReturn(geoLoc);

    Country country = mock(Country.class);
    when(country.getId()).thenReturn("country-id");
    when(country.getName()).thenReturn("Spain");
    when(obDal.get(Country.class, "country-id")).thenReturn(country);

    Region region = mock(Region.class);
    when(region.getId()).thenReturn("region-id");
    when(region.getName()).thenReturn("Catalonia");
    when(obDal.get(Region.class, "region-id")).thenReturn(region);

    JSONObject body = new JSONObject();
    body.put("country", "country-id");
    body.put("region", "region-id");

    NeoResponse r = handler.handle(buildContext("PUT", "geo-upd-2", body));

    assertEquals(200, r.getHttpStatus());
    verify(geoLoc).setCountry(country);
    verify(geoLoc).setRegion(region);
  }

  /**
   * Update with an explicit empty region clears it.
   */
  @Test
  void testUpdateEmptyRegionClearsRegion() throws Exception {
    Location geoLoc = mock(Location.class);
    when(geoLoc.getId()).thenReturn("geo-upd-3");
    when(obDal.get(Location.class, "geo-upd-3")).thenReturn(geoLoc);

    JSONObject body = new JSONObject();
    body.put("region", "");

    NeoResponse r = handler.handle(buildContext("PUT", "geo-upd-3", body));

    assertEquals(200, r.getHttpStatus());
    verify(geoLoc).setRegion(isNull());
  }

  // ── handleGetById ─────────────────────────────────────────────────────────

  /**
   * GET-by-id happy path: all address fields, country and region present —
   * verifies the full JSON shape including resolved identifiers, and the
   * "city, addressLine1" branch of {@code buildDisplayName}/{@code joinNonNull}.
   */
  @Test
  void testGetByIdHappyPathReturns200WithFullFields() throws Exception {
    Location geoLoc = mock(Location.class);
    when(geoLoc.getId()).thenReturn("geo-get");
    when(geoLoc.getAddressLine1()).thenReturn("123 Main St");
    when(geoLoc.getAddressLine2()).thenReturn("Apt 4");
    when(geoLoc.getCityName()).thenReturn("Springfield");
    when(geoLoc.getPostalCode()).thenReturn("62704");

    Country country = mock(Country.class);
    when(country.getId()).thenReturn("US");
    when(country.getName()).thenReturn("United States");
    when(geoLoc.getCountry()).thenReturn(country);

    Region region = mock(Region.class);
    when(region.getId()).thenReturn("IL");
    when(region.getName()).thenReturn("Illinois");
    when(geoLoc.getRegion()).thenReturn(region);

    when(obDal.get(Location.class, "geo-get")).thenReturn(geoLoc);

    NeoResponse r = handler.handle(buildContext("GET", "geo-get", null));

    assertNotNull(r);
    assertEquals(200, r.getHttpStatus());

    JSONObject rec = r.getBody().getJSONObject("response").getJSONArray("data").getJSONObject(0);
    assertEquals("geo-get", rec.getString("id"));
    assertEquals("Springfield, 123 Main St", rec.getString("name"));
    assertEquals("123 Main St", rec.getString("addressLine1"));
    assertEquals("Apt 4", rec.getString("addressLine2"));
    assertEquals("Springfield", rec.getString("cityName"));
    assertEquals("62704", rec.getString("postalCode"));
    assertEquals("US", rec.getString("country"));
    assertEquals("United States", rec.getString("country$_identifier"));
    assertEquals("IL", rec.getString("region"));
    assertEquals("Illinois", rec.getString("region$_identifier"));
  }

  /**
   * A missing id must return 404.
   */
  @Test
  void testGetByIdNotFoundReturns404() {
    when(obDal.get(Location.class, "missing")).thenReturn(null);

    NeoResponse r = handler.handle(buildContext("GET", "missing", null));

    assertNotNull(r);
    assertEquals(404, r.getHttpStatus());
  }

  /**
   * A Location with every field empty must fall back to the literal
   * {@code "Location"} display name, and every JSON field must be emitted as
   * {@code JSONObject.NULL} rather than the Java {@code null} / omitted.
   */
  @Test
  void testGetByIdAllFieldsEmptyFallsBackToLiteralName() throws Exception {
    Location geoLoc = mock(Location.class);
    when(geoLoc.getId()).thenReturn("geo-empty");
    when(obDal.get(Location.class, "geo-empty")).thenReturn(geoLoc);

    NeoResponse r = handler.handle(buildContext("GET", "geo-empty", null));

    assertEquals(200, r.getHttpStatus());
    JSONObject rec = r.getBody().getJSONObject("response").getJSONArray("data").getJSONObject(0);
    assertEquals("Location", rec.getString("name"));
    assertEquals(JSONObject.NULL, rec.get("addressLine1"));
    assertEquals(JSONObject.NULL, rec.get("addressLine2"));
    assertEquals(JSONObject.NULL, rec.get("cityName"));
    assertEquals(JSONObject.NULL, rec.get("postalCode"));
    assertEquals(JSONObject.NULL, rec.get("country"));
    assertEquals(JSONObject.NULL, rec.get("country$_identifier"));
    assertEquals(JSONObject.NULL, rec.get("region"));
    assertEquals(JSONObject.NULL, rec.get("region$_identifier"));
  }

  /**
   * When city/address are empty but country alone is present (no region), the
   * display name falls back to the country name alone — the single-value,
   * no-comma branch of {@code joinNonNull} via the region/country fallback.
   */
  @Test
  void testGetByIdDisplayNameFallsBackToCountryOnly() throws Exception {
    Location geoLoc = mock(Location.class);
    when(geoLoc.getId()).thenReturn("geo-country-only");
    Country country = mock(Country.class);
    when(country.getName()).thenReturn("Portugal");
    when(geoLoc.getCountry()).thenReturn(country);
    when(obDal.get(Location.class, "geo-country-only")).thenReturn(geoLoc);

    NeoResponse r = handler.handle(buildContext("GET", "geo-country-only", null));

    JSONObject rec = r.getBody().getJSONObject("response").getJSONArray("data").getJSONObject(0);
    assertEquals("Portugal", rec.getString("name"));
  }

  /**
   * When only {@code cityName} is present (no addressLine1), the display name
   * is the city alone — the single-value, no-comma branch of the primary
   * {@code joinNonNull} call (as opposed to the region/country fallback).
   */
  @Test
  void testGetByIdDisplayNameOnlyCity() throws Exception {
    Location geoLoc = mock(Location.class);
    when(geoLoc.getId()).thenReturn("geo-city-only");
    when(geoLoc.getCityName()).thenReturn("Lisbon");
    when(obDal.get(Location.class, "geo-city-only")).thenReturn(geoLoc);

    NeoResponse r = handler.handle(buildContext("GET", "geo-city-only", null));

    JSONObject rec = r.getBody().getJSONObject("response").getJSONArray("data").getJSONObject(0);
    assertEquals("Lisbon", rec.getString("name"));
  }

  // ── static helpers via reflection (branches not otherwise reachable) ────

  @Test
  void testNullIfEmptyReturnsNullForNull() throws Exception {
    Method m = WarehouseLocationHandler.class.getDeclaredMethod("nullIfEmpty", String.class);
    m.setAccessible(true);
    assertNull(m.invoke(null, (Object) null));
  }

  @Test
  void testNullIfEmptyReturnsNullForEmpty() throws Exception {
    Method m = WarehouseLocationHandler.class.getDeclaredMethod("nullIfEmpty", String.class);
    m.setAccessible(true);
    assertNull(m.invoke(null, ""));
  }

  @Test
  void testNullIfEmptyReturnsNullForLiteralNull() throws Exception {
    Method m = WarehouseLocationHandler.class.getDeclaredMethod("nullIfEmpty", String.class);
    m.setAccessible(true);
    assertNull(m.invoke(null, "null"));
  }

  @Test
  void testNullIfEmptyReturnsValueForNonEmpty() throws Exception {
    Method m = WarehouseLocationHandler.class.getDeclaredMethod("nullIfEmpty", String.class);
    m.setAccessible(true);
    assertEquals("hello", m.invoke(null, "hello"));
  }

  @Test
  void testJoinNonNullMultipleParts() throws Exception {
    Method m = WarehouseLocationHandler.class.getDeclaredMethod("joinNonNull", String[].class);
    m.setAccessible(true);
    assertEquals("a, b", m.invoke(null, (Object) new String[]{ "a", "b" }));
  }

  @Test
  void testJoinNonNullSinglePart() throws Exception {
    Method m = WarehouseLocationHandler.class.getDeclaredMethod("joinNonNull", String[].class);
    m.setAccessible(true);
    assertEquals("a", m.invoke(null, (Object) new String[]{ "a", null }));
  }

  @Test
  void testJoinNonNullAllNullReturnsNull() throws Exception {
    Method m = WarehouseLocationHandler.class.getDeclaredMethod("joinNonNull", String[].class);
    m.setAccessible(true);
    assertNull(m.invoke(null, (Object) new String[]{ null, null }));
  }

  // ── private helpers ───────────────────────────────────────────────────────

  private NeoContext buildContext(String method, String recordId, JSONObject body) {
    return NeoContext.builder()
        .specName(SPEC_WAREHOUSE)
        .entityName(ENTITY_LOCATION)
        .httpMethod(method)
        .recordId(recordId)
        .requestBody(body)
        .endpointType(NeoEndpointType.CRUD)
        .obContext(obContext)
        .build();
  }
}
