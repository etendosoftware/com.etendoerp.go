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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.Map;

import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.core.SessionHandler;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.businesspartner.BusinessPartner;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.common.geography.Country;
import org.openbravo.model.common.geography.Location;

/**
 * ETP-5234 for {@link ContactsLocationAddressHandler}: the two create modes.
 *
 * <p>{@code neo_schema} advertises {@code locationAddress} — an FK to an existing C_Location — as
 * this entity's own field, so an MCP client naturally creates the address through
 * {@code bp-location/bpLocation} first and then hands the resulting id here. The handler ignored
 * that id and always built a brand new C_Location from the body's raw fields, which for such a
 * caller are all absent: the insert reached {@code flush()} with a null {@code c_country_id} and
 * the agent got a bare {@code 500 ConstraintViolationException} for a request that was, from the
 * schema's point of view, correct.
 *
 * <p>What is pinned here is that both modes now exist and neither can silently corrupt the other:
 *
 * <ul>
 *   <li><b>Mode A — reuse.</b> The received C_Location is linked as-is and <b>never mutated</b>.
 *       It is a shared master record that may already hang off other Business Partners, so
 *       running it through {@code applyGeoLocFields} with this one caller's partial body would
 *       overwrite an address belonging to someone else.</li>
 *   <li><b>Mode B — create.</b> Unchanged, and the only mode the Contacts UI ever uses. The
 *       country is now validated up front instead of failing as a raw NOT NULL violation.</li>
 *   <li><b>Both at once</b> is refused rather than guessed at — which one would win is not a rule
 *       any caller could rely on.</li>
 * </ul>
 *
 * <p>Complements {@code ContactsLocationAddressHandlerTest} (routing, update, the GET enrichers)
 * and {@code ContactsLocationAddressParentAndRegionTest} (parent resolution, region columns).
 */
// Test methods live in the @Nested inner classes below; S2187 only inspects
// the outer class for @Test methods, hence the suppression.
@SuppressWarnings("java:S2187")
@DisplayName("ContactsLocationAddressHandler — the two create modes (ETP-5234)")
class ContactsLocationAddressCreateModesTest {

  private static final String BP_ID = "bp-123";
  private static final String COUNTRY_ID = "country-es";
  private static final String EXISTING_LOCATION_ID = "existing-geo-loc";

  private ContactsLocationAddressHandler handler;

  private OBDal obDal;
  private OBProvider obProvider;
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

    obDal = mock(OBDal.class);
    obProvider = mock(OBProvider.class);
    obDalMock.when(OBDal::getInstance).thenReturn(obDal);
    obProviderMock.when(OBProvider::getInstance).thenReturn(obProvider);
    sessionHandlerMock.when(SessionHandler::getInstance).thenReturn(mock(SessionHandler.class));
  }

  @AfterEach
  void tearDown() {
    obDalMock.close();
    obContextMock.close();
    obProviderMock.close();
    sessionHandlerMock.close();
  }

  @Nested
  @DisplayName("mode A — reusing a C_Location the caller already created")
  class Reuse {

    @Test
    @DisplayName("the received locationAddress is what gets linked, and no C_Location is built")
    void theReceivedLocationIsLinked() throws Exception {
      stubBusinessPartner();
      Location existing = stubExistingLocation(COUNTRY_ID);
      org.openbravo.model.common.businesspartner.Location bpLoc = stubNewBpLocation();

      JSONObject body = new JSONObject();
      body.put("locationAddress", EXISTING_LOCATION_ID);
      body.put("name", "Juan B. Justo 595");

      NeoResponse response = handler.handle(post(body));

      assertEquals(201, response.getHttpStatus());
      // The id the caller sent comes back — the whole point of the mode. Reading it off the
      // response rather than off the mock is what a client actually observes.
      assertEquals(EXISTING_LOCATION_ID, recordOf(response).getString("locationAddress"));
      verify(bpLoc).setLocationAddress(existing);
      // Not merely "a different Location was not linked": none was ever asked for.
      verify(obProvider, never()).get(Location.class);
    }

    @Test
    @DisplayName("the reused C_Location is never written to — it is a shared master record")
    void theReusedLocationIsNeverMutated() throws Exception {
      stubBusinessPartner();
      Location existing = stubExistingLocation(COUNTRY_ID);
      stubNewBpLocation();

      JSONObject body = new JSONObject();
      body.put("locationAddress", EXISTING_LOCATION_ID);

      handler.handle(post(body));

      // The record may already hang off other Business Partners. Every setter, not just the
      // address lines: a caller's partial body must not reach it at all.
      verify(existing, never()).setAddressLine1(any());
      verify(existing, never()).setAddressLine2(any());
      verify(existing, never()).setCityName(any());
      verify(existing, never()).setPostalCode(any());
      verify(existing, never()).setCountry(any());
      verify(existing, never()).setRegion(any());
      verify(existing, never()).setRegionName(any());
    }

    @Test
    @DisplayName("an id that resolves to nothing is a 400 naming it, not a constraint violation")
    void anUnresolvableIdIsA400() throws Exception {
      stubBusinessPartner();
      when(obDal.get(Location.class, "ghost-location")).thenReturn(null);

      JSONObject body = new JSONObject();
      body.put("locationAddress", "ghost-location");

      NeoResponse response = handler.handle(post(body));

      assertEquals(400, response.getHttpStatus());
      assertTrue(response.getBody().toString().contains("ghost-location"),
          "the refusal has to name the rejected id: " + response.getBody());
      verify(obDal, never()).save(any());
    }

    @Test
    @DisplayName("no country is required — the reused record already has one")
    void noCountryIsRequired() throws Exception {
      stubBusinessPartner();
      stubExistingLocation(COUNTRY_ID);
      stubNewBpLocation();

      JSONObject body = new JSONObject();
      body.put("locationAddress", EXISTING_LOCATION_ID);

      assertEquals(201, handler.handle(post(body)).getHttpStatus());
    }

    /**
     * The MCP shape. A client that serializes every key its schema declares sends the raw address
     * fields as explicit {@code null}s next to a real {@code locationAddress} — which is plain
     * reuse, not an ambiguous request. Checking {@code body.has()} instead of the resolved value
     * would turn every such call into a spurious 400.
     */
    @Test
    @DisplayName("explicit nulls for the raw fields are not an ambiguous request")
    void explicitNullsAreNotAmbiguous() throws Exception {
      stubBusinessPartner();
      stubExistingLocation(COUNTRY_ID);
      stubNewBpLocation();

      JSONObject body = new JSONObject();
      body.put("locationAddress", EXISTING_LOCATION_ID);
      body.put("addressLine1", JSONObject.NULL);
      body.put("addressLine2", JSONObject.NULL);
      body.put("cityName", JSONObject.NULL);
      body.put("postalCode", JSONObject.NULL);
      body.put("country", JSONObject.NULL);
      body.put("region", JSONObject.NULL);
      body.put("regionName", JSONObject.NULL);

      assertEquals(201, handler.handle(post(body)).getHttpStatus());
    }
  }

  @Nested
  @DisplayName("mode B — building a new C_Location from raw address fields")
  class CreateFromRawFields {

    @Test
    @DisplayName("a country that resolves to nothing is a 400, not a NOT NULL violation at flush")
    void anUnresolvableCountryIsA400() throws Exception {
      stubBusinessPartner();
      when(obDal.get(Country.class, "ghost-country")).thenReturn(null);

      JSONObject body = new JSONObject();
      body.put("country", "ghost-country");
      body.put("addressLine1", "Juan B. Justo 595");

      NeoResponse response = handler.handle(post(body));

      assertEquals(400, response.getHttpStatus());
      assertTrue(response.getBody().toString().contains("ghost-country"),
          "the refusal has to name the rejected id: " + response.getBody());
      // applyGeoLocFields silently ignores an unresolvable country — correct on update, where it
      // must not blank out one the record already has, and fatal here, where there is none to
      // fall back on. The guard has to precede the save, not follow it.
      verify(obDal, never()).save(any());
    }

    @Test
    @DisplayName("no country at all is a 400 naming the field")
    void aMissingCountryIsA400() throws Exception {
      JSONObject body = new JSONObject();
      body.put("addressLine1", "Juan B. Justo 595");
      body.put("cityName", "Río Cuarto");

      NeoResponse response = handler.handle(post(body));

      assertEquals(400, response.getHttpStatus());
      assertTrue(response.getBody().toString().contains("country"),
          "the refusal has to name the missing field: " + response.getBody());
      // Refused before the parent is even fetched: nothing about the request is servable.
      verify(obDal, never()).get(BusinessPartner.class, BP_ID);
    }
  }

  @Nested
  @DisplayName("both modes in one body")
  class Ambiguous {

    @Test
    @DisplayName("it is refused rather than one mode silently winning")
    void bothModesAtOnceIsA400() throws Exception {
      JSONObject body = new JSONObject();
      body.put("locationAddress", EXISTING_LOCATION_ID);
      body.put("addressLine1", "Juan B. Justo 595");
      body.put("country", COUNTRY_ID);

      NeoResponse response = handler.handle(post(body));

      assertEquals(400, response.getHttpStatus());
      assertTrue(response.getBody().toString().contains("Ambiguous"),
          "the caller has to be told the two modes conflict: " + response.getBody());
      // Neither mode ran: no Location was fetched for reuse and none was built either.
      verify(obDal, never()).get(Location.class, EXISTING_LOCATION_ID);
      verify(obProvider, never()).get(Location.class);
    }

    @Test
    @DisplayName("a lone region name counts as a raw address field")
    void aRegionNameAloneIsEnoughToConflict() throws Exception {
      JSONObject body = new JSONObject();
      body.put("locationAddress", EXISTING_LOCATION_ID);
      body.put("regionName", "Córdoba");

      NeoResponse response = handler.handle(post(body));

      // regionName is the address import's own entry point, so a body carrying it is asking for
      // a C_Location to be built — the same conflict as a street or a city would be.
      assertEquals(400, response.getHttpStatus());
      assertTrue(response.getBody().toString().contains("Ambiguous"), response.getBody().toString());
    }
  }

  // ── helpers ─────────────────────────────────────────────────────────────

  private void stubBusinessPartner() {
    BusinessPartner bp = mock(BusinessPartner.class);
    when(bp.getClient()).thenReturn(mock(Client.class));
    when(bp.getOrganization()).thenReturn(mock(Organization.class));
    when(obDal.get(BusinessPartner.class, BP_ID)).thenReturn(bp);
  }

  /** An existing C_Location, as mode A finds it: already carrying a country of its own. */
  private Location stubExistingLocation(String countryId) {
    Country country = mock(Country.class);
    when(country.getId()).thenReturn(countryId);
    Location existing = mock(Location.class);
    when(existing.getId()).thenReturn(EXISTING_LOCATION_ID);
    when(existing.getCountry()).thenReturn(country);
    when(obDal.get(Location.class, EXISTING_LOCATION_ID)).thenReturn(existing);
    return existing;
  }

  private org.openbravo.model.common.businesspartner.Location stubNewBpLocation() {
    org.openbravo.model.common.businesspartner.Location bpLoc =
        mock(org.openbravo.model.common.businesspartner.Location.class);
    when(bpLoc.getId()).thenReturn("bp-loc-id");
    when(obProvider.get(org.openbravo.model.common.businesspartner.Location.class))
        .thenReturn(bpLoc);
    return bpLoc;
  }

  /** A create context carrying {@code parentId} — the REST/UI shape. */
  private NeoContext post(JSONObject body) {
    Map<String, String> queryParams = new HashMap<>();
    queryParams.put("parentId", BP_ID);
    return NeoContext.builder()
        .specName("contacts")
        .entityName("locationAddress")
        .httpMethod("POST")
        .recordId(null)
        .requestBody(body)
        .queryParams(queryParams)
        .endpointType(NeoEndpointType.CRUD)
        .build();
  }

  private JSONObject recordOf(NeoResponse response) throws Exception {
    assertNotNull(response.getBody());
    return response.getBody().getJSONObject("response").getJSONArray("data").getJSONObject(0);
  }
}
