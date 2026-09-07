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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.openbravo.base.exception.OBException;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.core.SessionHandler;
import org.openbravo.dal.service.OBDal;
import org.openbravo.dal.service.OBQuery;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.businesspartner.BusinessPartner;
import org.openbravo.model.common.geography.Country;
import org.openbravo.model.common.geography.Location;
import org.openbravo.model.common.geography.Region;

/**
 * ETP-5184 for {@link ContactsLocationAddressHandler}: how the parent Business Partner is resolved
 * on create, and how a free-text region name is written.
 *
 * <h2>Parent resolution</h2>
 * <p>The parent reaches this handler differently depending on the caller, and
 * {@code NeoContext.getQueryParams()} is {@code null} on the MCP CRUD hook path — {@code
 * McpHookExecutor.buildHookContext} never populates it. Reading it unguarded was the defect: the
 * MCP shape is exactly a body FK plus a {@code null} map, so a request with neither used to reach
 * the caller as a 500 instead of the 400 that names the missing argument. The three routes and the
 * blank-FK fall-through are pinned below.</p>
 *
 * <h2>Region resolution</h2>
 * <p>Before ETP-5184 the only outcome was the FK: an unresolvable name threw. That is right for a
 * country whose regions are loaded, and wrong for one with no {@code C_Region} rows at all — a
 * live Argentine address failed with {@code The region "Cordoba" does not exist in Argentina.}
 * because {@code C_Country.HasRegion = 'N'} there and no province could ever resolve. The fallback
 * writes {@code C_Location.RegionName}, Etendo's own home for that case, and the strict path is
 * kept where it means something.</p>
 *
 * <p>Complements {@code ContactsLocationAddressHandlerTest}, which covers {@code
 * resolveRegionByName} itself (the tenant-versus-System duplicate, accent folding, ambiguity) and
 * the whitespace guard.</p>
 */
// Test methods live in the @Nested inner classes below; S2187 only inspects
// the outer class for @Test methods, hence the suppression.
@SuppressWarnings("java:S2187")
@DisplayName("ContactsLocationAddressHandler — parent resolution and region name (ETP-5184)")
class ContactsLocationAddressParentAndRegionTest {

  private static final String BODY_BP = "bp-from-body";
  private static final String QUERY_BP = "bp-from-query-param";
  /** The DAL property the MCP write path puts the resolved parent FK under. */
  private static final String FIELD_BUSINESS_PARTNER =
      org.openbravo.model.common.businesspartner.Location.PROPERTY_BUSINESSPARTNER;

  private ContactsLocationAddressHandler handler;

  private OBDal obDal;
  private MockedStatic<OBDal> obDalMock;
  private MockedStatic<OBContext> obContextMock;
  private MockedStatic<OBProvider> obProviderMock;
  private MockedStatic<SessionHandler> sessionHandlerMock;

  @BeforeEach
  void setUp() {
    handler = new ContactsLocationAddressHandler();
    obDal = mock(OBDal.class);
    obDalMock = mockStatic(OBDal.class);
    obContextMock = mockStatic(OBContext.class);
    obProviderMock = mockStatic(OBProvider.class);
    sessionHandlerMock = mockStatic(SessionHandler.class);

    obDalMock.when(OBDal::getInstance).thenReturn(obDal);
    obProviderMock.when(OBProvider::getInstance).thenReturn(mock(OBProvider.class));
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
  @DisplayName("handleCreate — resolving the parent Business Partner")
  class ParentResolution {

    @Test
    @DisplayName("the body FK is used — the MCP path, where McpToolRouter writes the resolved FK")
    void bodyFkIsUsed() throws Exception {
      // neo_create removes parentId from the body and puts the resolved id under
      // businessPartner. A 404 rather than a 400 is the proof the id was read and looked up.
      JSONObject body = new JSONObject();
      body.put(FIELD_BUSINESS_PARTNER, BODY_BP);
      when(obDal.get(BusinessPartner.class, BODY_BP)).thenReturn(null);

      NeoResponse response = handler.handle(post(body, null));

      assertNotNull(response);
      assertEquals(404, response.getHttpStatus());
      verify(obDal).get(BusinessPartner.class, BODY_BP);
    }

    @Test
    @DisplayName("the body FK wins over a query parameter, and the two never disagree anyway")
    void bodyFkWinsOverQueryParam() throws Exception {
      JSONObject body = new JSONObject();
      body.put(FIELD_BUSINESS_PARTNER, BODY_BP);
      when(obDal.get(BusinessPartner.class, BODY_BP)).thenReturn(null);

      handler.handle(post(body, params(QUERY_BP)));

      verify(obDal).get(BusinessPartner.class, BODY_BP);
      verify(obDal, never()).get(BusinessPartner.class, QUERY_BP);
    }

    @Test
    @DisplayName("the parentId query parameter is used when the body carries no FK — the REST/UI "
        + "path, unchanged")
    void queryParamIsUsedWhenTheBodyHasNoFk() throws Exception {
      // POST /locationAddress?parentId=<bpId>: this must stay behaviourally identical to before
      // the change, because it is what the Contacts address modal sends.
      when(obDal.get(BusinessPartner.class, QUERY_BP)).thenReturn(null);

      NeoResponse response = handler.handle(post(new JSONObject(), params(QUERY_BP)));

      assertEquals(404, response.getHttpStatus());
      verify(obDal).get(BusinessPartner.class, QUERY_BP);
    }

    @Test
    @DisplayName("null queryParams and no body FK is a 400, not an NPE-driven 500")
    void nullQueryParamsIsA400() throws Exception {
      // The defect. McpHookExecutor.buildHookContext never populates queryParams, so this is the
      // real MCP shape; reading the map unguarded made the agent see "Location handler error:
      // null" instead of the argument it forgot.
      NeoResponse response = handler.handle(post(new JSONObject(), null));

      assertNotNull(response);
      assertEquals(400, response.getHttpStatus(),
          "a missing parent is the caller's mistake and must be reported as one");
      assertTrue(response.getBody().toString().contains("parentId"),
          "the refusal has to name the missing argument: " + response.getBody());
    }

    @Test
    @DisplayName("a null request body with null queryParams is a 400 as well")
    void nullBodyAndNullQueryParamsIsA400() throws Exception {
      NeoResponse response = handler.handle(post(null, null));

      assertNotNull(response);
      assertEquals(400, response.getHttpStatus());
    }

    @Test
    @DisplayName("an empty businessPartner falls through to the query parameter")
    void emptyBodyFkFallsThroughToTheQueryParam() throws Exception {
      // optString(key, null) can answer "" rather than null for a present-but-empty value, so
      // StringUtils.isBlank is what catches it. A plain null check would have taken "" as the id
      // and looked up a Business Partner that cannot exist.
      JSONObject body = new JSONObject();
      body.put(FIELD_BUSINESS_PARTNER, "");
      when(obDal.get(BusinessPartner.class, QUERY_BP)).thenReturn(null);

      NeoResponse response = handler.handle(post(body, params(QUERY_BP)));

      assertEquals(404, response.getHttpStatus());
      verify(obDal).get(BusinessPartner.class, QUERY_BP);
      verify(obDal, never()).get(eq(BusinessPartner.class), eq(""));
    }

    @Test
    @DisplayName("a whitespace-only businessPartner falls through too")
    void blankBodyFkFallsThroughToTheQueryParam() throws Exception {
      JSONObject body = new JSONObject();
      body.put(FIELD_BUSINESS_PARTNER, "   ");
      when(obDal.get(BusinessPartner.class, QUERY_BP)).thenReturn(null);

      handler.handle(post(body, params(QUERY_BP)));

      verify(obDal).get(BusinessPartner.class, QUERY_BP);
    }

    @Test
    @DisplayName("an empty businessPartner with no query parameter to fall back to is a 400")
    void emptyBodyFkAndNoQueryParamIsA400() throws Exception {
      JSONObject body = new JSONObject();
      body.put(FIELD_BUSINESS_PARTNER, "");

      assertEquals(400, handler.handle(post(body, null)).getHttpStatus());
      assertEquals(400, handler.handle(post(body, Collections.emptyMap())).getHttpStatus());
    }

    @Test
    @DisplayName("a blank parentId query parameter is a 400, not a lookup")
    void blankQueryParamIsA400() throws Exception {
      NeoResponse response = handler.handle(post(new JSONObject(), params("")));

      assertEquals(400, response.getHttpStatus());
      verify(obDal, never()).get(eq(BusinessPartner.class), anyString());
    }
  }

  @Nested
  @DisplayName("applyRegionName — free-text region")
  class RegionName {

    @Test
    @DisplayName("an explicit region id wins, clears the free text, and never resolves the name")
    void explicitRegionIdWins() throws Exception {
      // Every existing caller (the address modal's selector) sends an id, and it must keep
      // winning. It must also clear the free-text column: this branch used to leave RegionName
      // alone, which was the one path that could leave both columns populated.
      Region madrid = mock(Region.class);
      when(obDal.get(Region.class, "region-id")).thenReturn(madrid);
      Location geoLoc = mock(Location.class);

      JSONObject body = new JSONObject();
      body.put("region", "region-id");
      body.put("regionName", "Cordoba");
      applyGeoLocFields(body, geoLoc);

      verify(geoLoc).setRegion(madrid);
      verify(geoLoc).setRegionName(null);
      verify(geoLoc, never()).setRegionName("Cordoba");
      verify(obDal, never()).createQuery(eq(Region.class), anyString());
    }

    /**
     * The concrete state the fix prevents: a record whose {@code RegionName} was filled by the
     * free-text fallback (an Argentine address, no {@code C_Region} rows) is later edited from the
     * Location modal, whose selector sends a {@code region} id. Both columns populated is a record
     * that answers the same question two ways, and the contacts export's
     * {@code COALESCE(C_Region.name, C_Location.regionname)} would then pick whichever it likes —
     * so the assertion is that the stale free text is cleared, not merely that the FK is set.
     */
    @Test
    @DisplayName("a record carrying free text from the fallback does not keep both columns when a "
        + "selector id arrives")
    void aSelectorIdSupersedesTheStaleFreeText() throws Exception {
      Region madrid = mock(Region.class);
      when(obDal.get(Region.class, "madrid-id")).thenReturn(madrid);
      Location geoLoc = mock(Location.class);
      // The record as the fallback left it: free text set, no FK.
      when(geoLoc.getRegionName()).thenReturn("Cordoba");
      when(geoLoc.getRegion()).thenReturn(null);

      JSONObject body = new JSONObject();
      body.put("region", "madrid-id");
      applyGeoLocFields(body, geoLoc);

      verify(geoLoc).setRegion(madrid);
      verify(geoLoc).setRegionName(null);
      // Nothing must re-assert the superseded value, whatever order the writes happen in.
      verify(geoLoc, never()).setRegionName("Cordoba");
    }

    /**
     * Clearing the province must clear <b>both</b> representations of it. This is the branch the
     * Location modal's selector actually sends when a user empties the province field, and it was
     * the most user-visible of the three: with the free text left behind, the contacts export's
     * {@code COALESCE(C_Region.name, C_Location.regionname)} kept rendering the old province, so
     * the clear looked like a no-op to whoever performed it.
     *
     * <p>Both wire shapes reach this branch, and that is verified rather than assumed:
     * Jettison's {@code JSONObject.NULL.toString()} is the literal {@code "null"}, which
     * {@code nullIfEmpty} maps to {@code null}, while {@code has(key)} stays {@code true} because
     * the key is still in the map. So {@code region: null} and {@code region: ""} both land here,
     * and neither is confused with an absent key — an absent {@code region} must leave the
     * province alone.
     */
    @Test
    @DisplayName("clearing the region by id clears the FK and the stale free text, for both an "
        + "empty string and a JSON null")
    void clearingTheRegionClearsBothColumns() throws Exception {
      for (Object cleared : new Object[] { "", JSONObject.NULL }) {
        Location geoLoc = mock(Location.class);
        // The record as the free-text fallback left it: no FK, province in RegionName.
        when(geoLoc.getRegion()).thenReturn(null);
        when(geoLoc.getRegionName()).thenReturn("Cordoba");

        JSONObject body = new JSONObject();
        body.put("region", cleared);
        applyGeoLocFields(body, geoLoc);

        verify(geoLoc).setRegion(null);
        verify(geoLoc).setRegionName(null);
        verify(geoLoc, never()).setRegionName("Cordoba");
      }
    }

    @Test
    @DisplayName("an absent region key leaves both columns alone — clearing must be explicit")
    void anAbsentRegionKeyClearsNothing() throws Exception {
      // The other half of the same contract: only a present-and-empty `region` clears. A payload
      // that says nothing about the province (a partial update, an import column that is not in
      // the file) must not erase one already on the record.
      Location geoLoc = mock(Location.class);
      when(geoLoc.getRegionName()).thenReturn("Cordoba");

      JSONObject body = new JSONObject();
      body.put("cityName", "Rosario");
      applyGeoLocFields(body, geoLoc);

      verify(geoLoc, never()).setRegion(any());
      verify(geoLoc, never()).setRegionName(any());
    }

    @Test
    @DisplayName("a name that resolves sets the FK and clears the free-text column")
    void resolvedNameSetsTheFkAndClearsTheText() throws Exception {
      Region madrid = mockRegion("MADRID", "T1");
      Country spain = mockCountry("Spain", Boolean.TRUE);
      Location geoLoc = mock(Location.class);
      when(geoLoc.getCountry()).thenReturn(spain);
      stubRegionsOfCountry(Collections.singletonList(madrid));
      stubCurrentClient("T1");

      JSONObject body = new JSONObject();
      body.put("regionName", "Madrid");
      applyGeoLocFields(body, geoLoc);

      verify(geoLoc).setRegion(madrid);
      // Mutually exclusive: an FK to Madrid beside a RegionName of "Cordoba" is a record that
      // answers the same question twice, and the contacts export does
      // COALESCE(C_Region.name, C_Location.regionname) — either reader would be free to win.
      verify(geoLoc).setRegionName(null);
      verify(geoLoc, never()).setRegionName("Madrid");
    }

    @Test
    @DisplayName("a name that does not resolve in a country with no regions becomes free text")
    void unresolvedNameInACountryWithoutRegionsBecomesFreeText() throws Exception {
      // The live failure: Argentina has HasRegion = 'N' and no C_Region rows, so "Cordoba" could
      // never resolve and the province was rejected outright rather than stored.
      Country argentina = mockCountry("Argentina", Boolean.FALSE);
      Location geoLoc = mock(Location.class);
      when(geoLoc.getCountry()).thenReturn(argentina);
      stubRegionsOfCountry(Collections.emptyList());
      stubCurrentClient("T1");

      JSONObject body = new JSONObject();
      body.put("regionName", "Cordoba");
      applyGeoLocFields(body, geoLoc);

      verify(geoLoc).setRegionName("Cordoba");
      verify(geoLoc).setRegion(null);
    }

    @Test
    @DisplayName("hasRegions = null is treated as 'no regions' — it is a Boolean and can be null")
    void nullHasRegionsIsTreatedAsNoRegions() throws Exception {
      Country unknown = mockCountry("Nowhereland", null);
      Location geoLoc = mock(Location.class);
      when(geoLoc.getCountry()).thenReturn(unknown);
      stubRegionsOfCountry(Collections.emptyList());
      stubCurrentClient("T1");

      JSONObject body = new JSONObject();
      body.put("regionName", "Some Province");
      applyGeoLocFields(body, geoLoc);

      verify(geoLoc).setRegionName("Some Province");
      verify(geoLoc).setRegion(null);
    }

    @Test
    @DisplayName("a name that does not resolve in a country that HAS regions still throws")
    void unresolvedNameInACountryWithRegionsStillThrows() throws Exception {
      // Strictness preserved where it means something: with the Spanish provinces loaded, a name
      // that is none of them is a data error the importing user can see and fix.
      Country spain = mockCountry("Spain", Boolean.TRUE);
      Location geoLoc = mock(Location.class);
      when(geoLoc.getCountry()).thenReturn(spain);
      stubRegionsOfCountry(Collections.singletonList(mockRegion("MADRID", "0")));
      stubCurrentClient("T1");

      JSONObject body = new JSONObject();
      body.put("regionName", "ProvinciaInexistente");

      OBException thrown = assertThrows(OBException.class, () -> applyGeoLocFields(body, geoLoc));
      assertTrue(thrown.getMessage().contains("ProvinciaInexistente"));
      assertTrue(thrown.getMessage().contains("Spain"));
      verify(geoLoc, never()).setRegionName(any());
    }

    @Test
    @DisplayName("a name with no country throws — 'has regions' is unanswerable without one")
    void noCountryThrows() throws Exception {
      Location geoLoc = mock(Location.class);
      when(geoLoc.getCountry()).thenReturn(null);

      JSONObject body = new JSONObject();
      body.put("regionName", "Cordoba");

      OBException thrown = assertThrows(OBException.class, () -> applyGeoLocFields(body, geoLoc));
      assertTrue(thrown.getMessage().contains("country"),
          "region names are not unique across countries, and the message must say so: "
              + thrown.getMessage());
    }

    @Test
    @DisplayName("a whitespace-only name touches neither column")
    void whitespaceOnlyNameTouchesNothing() throws Exception {
      // trimToNull, not nullIfEmpty: "   " is visually empty to whoever typed it in a
      // spreadsheet, and a blank cell in a re-imported file must not erase a province already on
      // the record.
      Country spain = mockCountry("Spain", Boolean.TRUE);
      Location geoLoc = mock(Location.class);
      when(geoLoc.getCountry()).thenReturn(spain);

      JSONObject body = new JSONObject();
      body.put("regionName", "   ");
      applyGeoLocFields(body, geoLoc);

      verify(geoLoc, never()).setRegion(any());
      verify(geoLoc, never()).setRegionName(any());
      verify(obDal, never()).createQuery(eq(Region.class), anyString());
    }

    @Test
    @DisplayName("a resolved name is trimmed before being matched, and stored trimmed as free text")
    void freeTextIsTheTrimmedName() throws Exception {
      Country argentina = mockCountry("Argentina", Boolean.FALSE);
      Location geoLoc = mock(Location.class);
      when(geoLoc.getCountry()).thenReturn(argentina);
      stubRegionsOfCountry(Collections.emptyList());
      stubCurrentClient("T1");

      JSONObject body = new JSONObject();
      body.put("regionName", "  Cordoba  ");
      applyGeoLocFields(body, geoLoc);

      verify(geoLoc).setRegionName("Cordoba");
    }
  }

  // ── helpers ─────────────────────────────────────────────────────────────

  /** A create context; {@code queryParams} is left {@code null} when none is given. */
  private NeoContext post(JSONObject body, Map<String, String> queryParams) {
    NeoContext.Builder builder = NeoContext.builder()
        .specName("contacts")
        .entityName("locationAddress")
        .httpMethod("POST")
        .recordId(null)
        .requestBody(body)
        .endpointType(NeoEndpointType.CRUD);
    if (queryParams != null) {
      builder.queryParams(queryParams);
    }
    return builder.build();
  }

  private Map<String, String> params(String parentId) {
    Map<String, String> map = new HashMap<>();
    map.put("parentId", parentId);
    return map;
  }

  private Region mockRegion(String name, String clientId) {
    Region region = mock(Region.class);
    when(region.getName()).thenReturn(name);
    Client client = mock(Client.class);
    when(client.getId()).thenReturn(clientId);
    when(region.getClient()).thenReturn(client);
    return region;
  }

  private Country mockCountry(String name, Boolean hasRegions) {
    Country country = mock(Country.class);
    when(country.getId()).thenReturn("C-" + name);
    when(country.getName()).thenReturn(name);
    when(country.isHasRegions()).thenReturn(hasRegions);
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

  /** Invokes the private static writer, unwrapping reflection's exception wrapper. */
  private void applyGeoLocFields(JSONObject body, Location geoLoc) throws Exception {
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
}
