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
import static org.junit.jupiter.api.Assertions.assertFalse;
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

import com.etendoerp.go.featureflags.GoFeatureFlags;

/**
 * Unit tests for {@link WarehouseLocationHandler}.
 *
 * <p>Covers: handle() dispatch gating (non-CRUD endpoint types fall through so
 * {@code NeoSelectorService} can serve selectors — the ETP-4526 regression),
 * unmatched CRUD combos (405), handleCreate, handleUpdate, handleGetById, and the
 * private field-mapping/display-name helpers exercised indirectly through those
 * three public-facing flows. Also the ETP-5806 structured-address rules: mandatory
 * streetName/buildingNumber/cityName/country, the oversized-field 400 (the former raw-SQL 500),
 * and address-line recomposition over any raw addressLine1/addressLine2 the body also sends.
 * Also the ETP-5806 {@code structured-address-for-sepa} flag-OFF branch, with
 * {@link GoFeatureFlags#isEnabled} stubbed to {@code false} so the suite is independent of the
 * local {@code Openbravo.properties} toggle: only {@code country} is mandatory, the 3 structured
 * fields are never read nor written, and a GET response omits their keys entirely.
 *
 * @covers com.etendoerp.go.schemaforge.WarehouseLocationHandler
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
    // ETP-5022 — both getters are stubbed on purpose: getName() is the base-language value the
    // handler must NOT use, getIdentifier() is the translated one it must. Asserting the
    // translated value below is what fails if a call site ever slips back to getName().
    when(country.getName()).thenReturn("Spain");
    when(country.getIdentifier()).thenReturn("España");
    when(obDal.get(Country.class, "country-id")).thenReturn(country);
    when(geoLoc.getCountry()).thenReturn(country);

    Region region = mock(Region.class);
    when(region.getId()).thenReturn("region-id");
    when(region.getName()).thenReturn("Catalonia");
    when(obDal.get(Region.class, "region-id")).thenReturn(region);
    when(geoLoc.getRegion()).thenReturn(region);

    JSONObject body = new JSONObject();
    // ETP-5806 — streetName/buildingNumber are now mandatory; once present, applyGeoLocFields
    // computes addressLine1/addressLine2 from them and ignores any raw value also sent.
    body.put("streetName", "Gran Via");
    body.put("buildingNumber", "45");
    body.put("buildingName", "Torre A");
    body.put("addressLine1", "123 Main St");
    body.put("addressLine2", "Apt 4");
    body.put("cityName", "Springfield");
    body.put("postalCode", "62704");
    body.put("country", "country-id");
    body.put("region", "region-id");

    try (MockedStatic<GoFeatureFlags> flags = mockStructuredAddressFlag(true)) {
      NeoResponse r = handler.handle(buildContext("POST", null, body));

      assertNotNull(r);
      assertEquals(201, r.getHttpStatus());

      verify(geoLoc).setClient(client);
      verify(geoLoc).setOrganization(organization);
      verify(geoLoc).setActive(Boolean.TRUE);
      verify(geoLoc).setOBSEPAStreetName("Gran Via");
      verify(geoLoc).setOBSEPABuildingNumber("45");
      verify(geoLoc).setOBSEPABuildingName("Torre A");
      // ETP-5806 — composed from street+number+name, NOT the raw addressLine1/addressLine2 sent.
      verify(geoLoc).setAddressLine1("Gran Via, 45 - Torre A");
      verify(geoLoc).setAddressLine2(isNull());
      verify(geoLoc, never()).setAddressLine1("123 Main St");
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
      assertEquals("España", rec.getString("country$_identifier"));
      assertEquals("region-id", rec.getString("region"));
      assertEquals("Catalonia", rec.getString("region$_identifier"));
    }
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
    body.put("streetName", "Gran Via");
    body.put("buildingNumber", "45");
    body.put("cityName", "Madrid");
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

    JSONObject body = new JSONObject();
    body.put("streetName", "Gran Via");
    body.put("buildingNumber", "45");
    body.put("cityName", "Madrid");
    body.put("country", "country-x");

    NeoResponse r = handler.handle(buildContext("POST", null, body));

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
    body.put("streetName", "Gran Via");
    body.put("buildingNumber", "45");
    body.put("cityName", "Madrid");
    body.put("country", "country-x");
    body.put("region", "");

    NeoResponse r = handler.handle(buildContext("POST", null, body));

    assertEquals(201, r.getHttpStatus());
    verify(geoLoc).setRegion(isNull());
  }

  /**
   * ETP-5806 — missing a mandatory field (streetName/buildingNumber/cityName/country) is a
   * clean 400, not a downstream NOT NULL violation at flush.
   */
  @Test
  void testCreateMissingMandatoryFieldsReturns400() throws Exception {
    JSONObject body = new JSONObject();
    body.put("streetName", "Gran Via");
    // buildingNumber, cityName and country intentionally omitted

    NeoResponse r = handler.handle(buildContext("POST", null, body));

    assertNotNull(r);
    assertEquals(400, r.getHttpStatus());
    verify(obDal, never()).save(any());
  }

  /**
   * ETP-5806 follow-up (the production 500 bug): a structured-address field longer than its DB
   * column must be rejected with a clean 400 before the write is attempted, never reach
   * {@code OBDal.flush()} as a raw data-truncation SQL exception.
   */
  @Test
  void testCreateFieldExceedingMaxLengthReturns400NotRawSqlException() throws Exception {
    JSONObject body = new JSONObject();
    body.put("streetName", "Gran Via");
    body.put("buildingNumber", "45");
    body.put("cityName", "Madrid");
    body.put("country", "country-x");
    // EM_OBSEPA_BldgNm is VARCHAR(35) — 36 chars overflows it.
    body.put("buildingName", "A".repeat(36));

    try (MockedStatic<GoFeatureFlags> flags = mockStructuredAddressFlag(true)) {
      NeoResponse r = handler.handle(buildContext("POST", null, body));

      assertNotNull(r);
      assertEquals(400, r.getHttpStatus());
      verify(obDal, never()).save(any());
      verify(obDal, never()).flush();
    }
  }

  /**
   * Once streetName is mandatory, applyGeoLocFields always takes the composed-address branch;
   * any raw addressLine1/addressLine2 the body also sends is ignored in favor of the composed
   * value. Also exercises the remaining nullIfEmpty variant (literal {@code "null"}) via
   * postalCode, the one field left unaffected by the new mandatory rule.
   */
  @Test
  void testCreateIgnoresRawAddressLinesWhenStreetNamePresent() throws Exception {
    Location geoLoc = mock(Location.class);
    when(geoLoc.getId()).thenReturn("geo-5");
    when(obProvider.get(Location.class)).thenReturn(geoLoc);

    JSONObject body = new JSONObject();
    body.put("streetName", "Gran Via");
    body.put("buildingNumber", "45");
    body.put("cityName", "Madrid");
    body.put("country", "country-x");
    body.put("addressLine1", "RAW LINE — SHOULD BE IGNORED");
    body.put("addressLine2", "RAW LINE 2 — SHOULD BE IGNORED");
    body.put("postalCode", "null");

    try (MockedStatic<GoFeatureFlags> flags = mockStructuredAddressFlag(true)) {
      NeoResponse r = handler.handle(buildContext("POST", null, body));

      assertEquals(201, r.getHttpStatus());
      verify(geoLoc).setAddressLine1("Gran Via, 45");
      verify(geoLoc).setAddressLine2(isNull());
      verify(geoLoc, never()).setAddressLine1("RAW LINE — SHOULD BE IGNORED");
      verify(geoLoc).setPostalCode(isNull());
    }
  }

  /**
   * An unexpected runtime exception during create must roll back the session
   * and surface as HTTP 500, never leak a stack trace to the caller.
   */
  @Test
  void testCreateExceptionRollsBackAndReturns500() throws Exception {
    when(obProvider.get(Location.class)).thenThrow(new RuntimeException("DB exploded"));

    JSONObject body = new JSONObject();
    body.put("streetName", "Gran Via");
    body.put("buildingNumber", "45");
    body.put("cityName", "Madrid");
    body.put("country", "country-x");

    NeoResponse r = handler.handle(buildContext("POST", null, body));

    assertNotNull(r);
    assertEquals(500, r.getHttpStatus());
    verify(sessionHandler).rollback();
  }

  // ── ETP-5806: structured-address-for-sepa flag OFF ─────────────────────

  /** Stubs {@link GoFeatureFlags#isEnabled} for the {@code structured-address-for-sepa} flag only. */
  private MockedStatic<GoFeatureFlags> mockStructuredAddressFlag(boolean enabled) {
    MockedStatic<GoFeatureFlags> flags = mockStatic(GoFeatureFlags.class);
    flags.when(() -> GoFeatureFlags.isEnabled(
        eq(GoFeatureFlags.FLAG_STRUCTURED_ADDRESS_FOR_SEPA), any())).thenReturn(enabled);
    return flags;
  }

  /**
   * Flag off: only {@code country} is mandatory on create — streetName/buildingNumber/cityName
   * are not required, and the legacy raw addressLine1 is used as given.
   */
  @Test
  void testCreateFlagOffOnlyRequiresCountryNotStructuredFields() throws Exception {
    Location geoLoc = mock(Location.class);
    when(geoLoc.getId()).thenReturn("geo-flag-off-1");
    when(obProvider.get(Location.class)).thenReturn(geoLoc);

    JSONObject body = new JSONObject();
    body.put("country", "country-x");
    body.put("addressLine1", "Legacy free-text line");
    // streetName, buildingNumber, buildingName and cityName intentionally omitted.

    try (MockedStatic<GoFeatureFlags> flags = mockStructuredAddressFlag(false)) {
      NeoResponse r = handler.handle(buildContext("POST", null, body));

      assertNotNull(r);
      assertEquals(201, r.getHttpStatus());
      verify(geoLoc).setAddressLine1("Legacy free-text line");
      verify(obDal).save(geoLoc);
    }
  }

  /**
   * Flag off: a create payload that still carries the 3 structured fields does not persist them
   * (never read off the body, never written to the record) and does not error on the missing
   * {@code cityName} that would be mandatory with the flag on.
   */
  @Test
  void testCreateFlagOffNeverPersistsStructuredFieldsEvenWhenPayloadCarriesThem() throws Exception {
    Location geoLoc = mock(Location.class);
    when(geoLoc.getId()).thenReturn("geo-flag-off-2");
    when(obProvider.get(Location.class)).thenReturn(geoLoc);

    JSONObject body = new JSONObject();
    body.put("country", "country-x");
    body.put("streetName", "Gran Via");
    body.put("buildingNumber", "45");
    body.put("buildingName", "Torre A");
    // cityName intentionally omitted — mandatory only when the flag is on.

    try (MockedStatic<GoFeatureFlags> flags = mockStructuredAddressFlag(false)) {
      NeoResponse r = handler.handle(buildContext("POST", null, body));

      assertNotNull(r);
      assertEquals(201, r.getHttpStatus());
      verify(geoLoc, never()).setOBSEPAStreetName(any());
      verify(geoLoc, never()).setOBSEPABuildingNumber(any());
      verify(geoLoc, never()).setOBSEPABuildingName(any());
    }
  }

  /**
   * Flag off: a GET-by-id response omits the 3 structured-address keys entirely (absent, not
   * {@code JSONObject.NULL}) — the SEPA getters are never even called.
   */
  @Test
  void testGetByIdFlagOffOmitsStructuredAddressKeysEntirely() throws Exception {
    Location geoLoc = mock(Location.class);
    when(geoLoc.getId()).thenReturn("geo-flag-off-3");
    when(obDal.get(Location.class, "geo-flag-off-3")).thenReturn(geoLoc);

    try (MockedStatic<GoFeatureFlags> flags = mockStructuredAddressFlag(false)) {
      NeoResponse r = handler.handle(buildContext("GET", "geo-flag-off-3", null));

      assertNotNull(r);
      assertEquals(200, r.getHttpStatus());
      JSONObject rec = r.getBody().getJSONObject("response").getJSONArray("data").getJSONObject(0);
      assertEquals("Location", rec.getString("name"));
      assertFalse(rec.has("streetName"), rec.toString());
      assertFalse(rec.has("buildingNumber"), rec.toString());
      assertFalse(rec.has("buildingName"), rec.toString());
      verify(geoLoc, never()).getOBSEPAStreetName();
      verify(geoLoc, never()).getOBSEPABuildingNumber();
      verify(geoLoc, never()).getOBSEPABuildingName();
    }
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
    body.put("streetName", "Calle Mayor");
    body.put("buildingNumber", "7");
    body.put("cityName", "Toledo");
    body.put("country", "country-upd");
    // ETP-5806 — a legacy record may already carry a free-text Address1; a non-blank streetName
    // on the update always recomputes it, so this raw value must be ignored regardless of
    // whatever addressLine1 geoLoc already had before this update.
    body.put("addressLine1", "456 Oak Ave");

    try (MockedStatic<GoFeatureFlags> flags = mockStructuredAddressFlag(true)) {
      NeoResponse r = handler.handle(buildContext("PUT", "geo-upd", body));

      assertNotNull(r);
      assertEquals(200, r.getHttpStatus());
      verify(geoLoc).setAddressLine1("Calle Mayor, 7");
      verify(geoLoc, never()).setAddressLine1("456 Oak Ave");
      verify(obDal, never()).save(any());
      verify(obDal).flush();

      JSONObject rec = r.getBody().getJSONObject("response").getJSONArray("data").getJSONObject(0);
      assertEquals("geo-upd", rec.getString("id"));
    }
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
    when(country.getIdentifier()).thenReturn("España");
    when(obDal.get(Country.class, "country-id")).thenReturn(country);

    Region region = mock(Region.class);
    when(region.getId()).thenReturn("region-id");
    when(region.getName()).thenReturn("Catalonia");
    when(obDal.get(Region.class, "region-id")).thenReturn(region);

    JSONObject body = new JSONObject();
    body.put("streetName", "Paseo del Prado");
    body.put("buildingNumber", "12");
    body.put("cityName", "Madrid");
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
    body.put("streetName", "Calle Alcala");
    body.put("buildingNumber", "20");
    body.put("cityName", "Madrid");
    body.put("country", "country-any");
    body.put("region", "");

    NeoResponse r = handler.handle(buildContext("PUT", "geo-upd-3", body));

    assertEquals(200, r.getHttpStatus());
    verify(geoLoc).setRegion(isNull());
  }

  /**
   * ETP-5806 — an update that omits the body's mandatory fields falls back to the existing
   * record's own country, but streetName/buildingNumber/cityName have no such fallback (the
   * update always resends the full address): a body missing them is rejected with 400.
   */
  @Test
  void testUpdateMissingMandatoryFieldsReturns400() throws Exception {
    Location geoLoc = mock(Location.class);
    when(obDal.get(Location.class, "geo-upd-4")).thenReturn(geoLoc);

    JSONObject body = new JSONObject();
    body.put("streetName", "Calle Alcala");
    // buildingNumber, cityName and country intentionally omitted, and geoLoc has none either

    NeoResponse r = handler.handle(buildContext("PUT", "geo-upd-4", body));

    assertNotNull(r);
    assertEquals(400, r.getHttpStatus());
    verify(obDal, never()).flush();
  }

  /**
   * ETP-5806 follow-up (the production 500 bug) on the update path: an oversized structured
   * field is rejected with 400 before {@code OBDal.flush()}, never surfaces as a raw SQL
   * data-truncation exception.
   */
  @Test
  void testUpdateFieldExceedingMaxLengthReturns400() throws Exception {
    Location geoLoc = mock(Location.class);
    when(obDal.get(Location.class, "geo-upd-5")).thenReturn(geoLoc);

    JSONObject body = new JSONObject();
    body.put("streetName", "Calle Alcala");
    body.put("buildingNumber", "A".repeat(17)); // EM_OBSEPA_BldgNb is VARCHAR(16)
    body.put("cityName", "Madrid");
    body.put("country", "country-any");

    try (MockedStatic<GoFeatureFlags> flags = mockStructuredAddressFlag(true)) {
      NeoResponse r = handler.handle(buildContext("PUT", "geo-upd-5", body));

      assertNotNull(r);
      assertEquals(400, r.getHttpStatus());
      verify(obDal, never()).flush();
    }
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
    assertEquals("Estados Unidos", rec.getString("country$_identifier"));
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
   *
   * ETP-5022: that fallback is the country as the USER sees it, so it must come from
   * getIdentifier() (translated), not getName() (base language).
   */
  @Test
  void testGetByIdDisplayNameFallsBackToTranslatedCountryOnly() throws Exception {
    Location geoLoc = mock(Location.class);
    when(geoLoc.getId()).thenReturn("geo-country-only");
    Country country = mock(Country.class);
    when(country.getName()).thenReturn("Spain");
    when(country.getIdentifier()).thenReturn("España");
    when(geoLoc.getCountry()).thenReturn(country);
    when(obDal.get(Location.class, "geo-country-only")).thenReturn(geoLoc);

    NeoResponse r = handler.handle(buildContext("GET", "geo-country-only", null));

    JSONObject rec = r.getBody().getJSONObject("response").getJSONArray("data").getJSONObject(0);
    assertEquals("España", rec.getString("name"));
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
