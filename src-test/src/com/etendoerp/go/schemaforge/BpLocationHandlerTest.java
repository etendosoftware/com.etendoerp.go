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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.geography.Location;

import com.etendoerp.go.common.GoAccountResolver;
import com.etendoerp.go.featureflags.GoFeatureFlags;

/**
 * Unit tests for {@link BpLocationHandler} (ETP-5806).
 *
 * <p>This entity is a direct, un-wrapped CRUD mapping onto {@code C_Location}, and the handler
 * owns the 3 structured SEPA fields end to end: a pre-hook ({@code handle}) validates and
 * remembers the effective values, and a post-hook ({@code afterHandle}) persists them (POST/PUT)
 * once the base record exists, or enriches the response with them (GET) — bypassing
 * {@code NeoFieldFilter} either way.
 *
 * <p>Also the {@code structured-address-for-sepa} flag-OFF branch ({@link FlagOff}), stubbing
 * {@link GoFeatureFlags#isEnabled} directly so the suite does not depend on the local
 * {@code Openbravo.properties} toggle: {@code handle()}/{@code afterHandle()} decline immediately,
 * and {@code handle()} first STRIPS the 3 fields from the write body. The 3 columns do carry
 * curated {@code ETGO_SF_FIELD} rows (editable, not read-only — see the handler's class javadoc),
 * so a body that still carried them would have them persisted ungated by generic CRUD.
 *
 * @covers com.etendoerp.go.schemaforge.BpLocationHandler
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BpLocationHandlerTest {

  private static final String SPEC = "bp-location";
  private static final String ENTITY = "bpLocation";

  private BpLocationHandler handler;
  private OBDal obDal;
  private MockedStatic<OBDal> obDalMock;
  private MockedStatic<OBContext> obContextMock;

  @BeforeEach
  void setUp() {
    handler = new BpLocationHandler();
    obDal = mock(OBDal.class);
    obDalMock = mockStatic(OBDal.class);
    obContextMock = mockStatic(OBContext.class);
    obDalMock.when(OBDal::getInstance).thenReturn(obDal);
  }

  @AfterEach
  void tearDown() {
    obDalMock.close();
    obContextMock.close();
  }

  // ── handle() dispatch gating ─────────────────────────────────────────────

  @Test
  void nullContextReturnsNull() {
    assertNull(handler.handle(null));
  }

  @Test
  void nonCrudEndpointReturnsNull() {
    NeoContext ctx = NeoContext.builder()
        .specName(SPEC).entityName(ENTITY)
        .httpMethod("GET").endpointType(NeoEndpointType.SELECTOR)
        .fieldName("C_Country_ID").build();
    assertNull(handler.handle(ctx));
  }

  @Test
  void getWithoutRecordIdIsNeitherCreateNorUpdateAndReturnsNull() {
    NeoResponse r = handler.handle(buildCrudContext("GET", null, new JSONObject()));
    assertNull(r);
  }

  @Test
  void nullBodyReturnsNull() {
    NeoResponse r = handler.handle(buildCrudContext("POST", null, null));
    assertNull(r);
  }

  // ── handle() — create (pre-hook) ─────────────────────────────────────────

  @Nested
  class Create {

    private MockedStatic<GoFeatureFlags> flags;

    @BeforeEach
    void stubFlagOn() {
      flags = mockStatic(GoFeatureFlags.class);
      flags.when(() -> GoFeatureFlags.isEnabled(
          eq(GoFeatureFlags.FLAG_STRUCTURED_ADDRESS_FOR_SEPA), any())).thenReturn(true);
    }

    @AfterEach
    void closeFlag() {
      flags.close();
    }

    @Test
    void missingMandatoryFieldsReturns400() throws Exception {
      JSONObject body = new JSONObject();
      body.put("streetName", "Gran Via");
      // buildingNumber, cityName and country intentionally omitted

      NeoResponse r = handler.handle(buildCrudContext("POST", null, body));

      assertNotNull(r);
      assertEquals(400, r.getHttpStatus());
    }

    @Test
    void fieldExceedingMaxLengthReturns400NotRawSqlException() throws Exception {
      JSONObject body = new JSONObject();
      body.put("streetName", "Gran Via");
      body.put("buildingNumber", "45");
      body.put("cityName", "Madrid");
      body.put("country", "ES");
      // EM_OBSEPA_BldgNm is VARCHAR(35) — 36 chars overflows it.
      body.put("buildingName", "A".repeat(36));

      NeoResponse r = handler.handle(buildCrudContext("POST", null, body));

      assertNotNull(r);
      assertEquals(400, r.getHttpStatus());
    }

    @Test
    void validCreateLetsGenericCrudContinue() throws Exception {
      JSONObject body = new JSONObject();
      body.put("streetName", "Gran Via");
      body.put("buildingNumber", "45");
      body.put("buildingName", "Torre A");
      body.put("cityName", "Madrid");
      body.put("country", "ES");

      NeoResponse r = handler.handle(buildCrudContext("POST", null, body));

      // The pre-hook returns null so generic CRUD owns id generation, client/org, etc.
      assertNull(r);
    }

    /**
     * The production 500 bug for the generic-CRUD-bound {@code bp-location} entity: before this
     * handler, a value exceeding its DB column reached {@code OBDal.flush()} unvalidated, since
     * no curated {@code ETGO_SF_FIELD} row exists for it to reject through the usual path.
     */
    @Test
    void oversizedBuildingNumberReturns400() throws Exception {
      JSONObject body = new JSONObject();
      body.put("streetName", "Gran Via");
      body.put("buildingNumber", "A".repeat(17)); // EM_OBSEPA_BldgNb is VARCHAR(16)
      body.put("cityName", "Madrid");
      body.put("country", "ES");

      NeoResponse r = handler.handle(buildCrudContext("POST", null, body));

      assertNotNull(r);
      assertEquals(400, r.getHttpStatus());
    }

    /**
     * ETP-5806 follow-up — the ON path reads the same sections the OFF path strips. A
     * {@code {"data": {...}}} envelope is what {@code NeoFieldFilter#filterBody} unwraps and
     * persists, so a create sent that way must be validated and composed from the INNER values,
     * not fall back to "nothing sent" and be rejected as missing-mandatory.
     */
    @Test
    void validCreateInsideADataEnvelopeIsValidatedFromTheInnerValues() throws Exception {
      JSONObject inner = new JSONObject();
      inner.put("streetName", "Gran Via");
      inner.put("buildingNumber", "45");
      inner.put("buildingName", "Torre A");
      inner.put("cityName", "Madrid");
      inner.put("country", "ES");
      JSONObject body = new JSONObject();
      body.put("data", inner);

      assertNull(handler.handle(buildCrudContext("POST", null, body)));
    }

    /** And an invalid inner value is caught rather than hidden by the envelope. */
    @Test
    void oversizedValueInsideADataEnvelopeStillReturns400() throws Exception {
      JSONObject inner = new JSONObject();
      inner.put("streetName", "Gran Via");
      inner.put("buildingNumber", "A".repeat(17)); // EM_OBSEPA_BldgNb is VARCHAR(16)
      inner.put("cityName", "Madrid");
      inner.put("country", "ES");
      JSONObject body = new JSONObject();
      body.put("data", inner);

      NeoResponse r = handler.handle(buildCrudContext("POST", null, body));

      assertNotNull(r);
      assertEquals(400, r.getHttpStatus());
    }
  }

  // ── handle() — update (pre-hook) ─────────────────────────────────────────

  @Nested
  class Update {

    private MockedStatic<GoFeatureFlags> flags;

    @BeforeEach
    void stubFlagOn() {
      flags = mockStatic(GoFeatureFlags.class);
      flags.when(() -> GoFeatureFlags.isEnabled(
          eq(GoFeatureFlags.FLAG_STRUCTURED_ADDRESS_FOR_SEPA), any())).thenReturn(true);
    }

    @AfterEach
    void closeFlag() {
      flags.close();
    }

    @Test
    void notFoundReturns404() {
      when(obDal.get(Location.class, "missing")).thenReturn(null);

      NeoResponse r = handler.handle(buildCrudContext("PUT", "missing", new JSONObject()));

      assertNotNull(r);
      assertEquals(404, r.getHttpStatus());
    }

    @Test
    void omittedFieldsFallBackToExistingValues() throws Exception {
      Location existing = mock(Location.class);
      when(existing.getOBSEPAStreetName()).thenReturn("Calle Existente");
      when(existing.getOBSEPABuildingNumber()).thenReturn("9");
      when(existing.getCityName()).thenReturn("Sevilla");
      org.openbravo.model.common.geography.Country existingCountry =
          mock(org.openbravo.model.common.geography.Country.class);
      when(existingCountry.getId()).thenReturn("ES");
      when(existing.getCountry()).thenReturn(existingCountry);
      when(obDal.get(Location.class, "loc-1")).thenReturn(existing);

      // Body only changes the building name; every mandatory field is omitted and must fall
      // back to the existing record's own values instead of being treated as missing.
      JSONObject body = new JSONObject();
      body.put("buildingName", "Nuevo Edificio");

      NeoResponse r = handler.handle(buildCrudContext("PUT", "loc-1", body));

      // Validation passed using the fallback values, so the pre-hook lets CRUD continue.
      assertNull(r);
    }

    /**
     * An update that explicitly blanks a mandatory field (as opposed to omitting it) must be
     * rejected: the effective state after the write would be incomplete.
     */
    @Test
    void explicitlyBlankingAMandatoryFieldIsRejected() throws Exception {
      Location existing = mock(Location.class);
      when(existing.getOBSEPAStreetName()).thenReturn("Calle Existente");
      when(existing.getOBSEPABuildingNumber()).thenReturn("9");
      when(existing.getCityName()).thenReturn("Sevilla");
      org.openbravo.model.common.geography.Country existingCountry =
          mock(org.openbravo.model.common.geography.Country.class);
      when(existingCountry.getId()).thenReturn("ES");
      when(existing.getCountry()).thenReturn(existingCountry);
      when(obDal.get(Location.class, "loc-1")).thenReturn(existing);

      JSONObject body = new JSONObject();
      body.put("streetName", ""); // explicit clear, not an omission

      NeoResponse r = handler.handle(buildCrudContext("PUT", "loc-1", body));

      assertNotNull(r);
      assertEquals(400, r.getHttpStatus());
    }

    /**
     * ETP-5806 follow-up: PATCH is an update too. {@code ETGO_SF_ENTITY} enables it for this
     * entity ({@code ISPATCH=Y}), {@code NeoServlet} routes it through the same hook dispatch as
     * PUT and {@code NeoCrudHandler} runs it through the same {@code executeUpdate} — so the
     * pre-hook must intercept and validate it instead of silently falling through.
     */
    @Test
    void patchIsValidatedLikeAPut() throws Exception {
      Location existing = mock(Location.class);
      when(existing.getOBSEPAStreetName()).thenReturn("Calle Existente");
      when(existing.getOBSEPABuildingNumber()).thenReturn("9");
      when(existing.getCityName()).thenReturn("Sevilla");
      when(obDal.get(Location.class, "loc-1")).thenReturn(existing);

      JSONObject body = new JSONObject();
      body.put("streetName", ""); // explicit clear of a mandatory field

      NeoResponse r = handler.handle(buildCrudContext("PATCH", "loc-1", body));

      assertNotNull(r);
      assertEquals(400, r.getHttpStatus());
    }

    /**
     * A legitimate partial PATCH — the body mentions one optional field and omits every mandatory
     * one — must NOT be rejected: {@code effective()} falls back to the existing record's values,
     * which is exactly PATCH's partial-update semantics.
     */
    @Test
    void partialPatchFallsBackToExistingValuesAndIsAccepted() throws Exception {
      Location existing = mock(Location.class);
      when(existing.getOBSEPAStreetName()).thenReturn("Calle Existente");
      when(existing.getOBSEPABuildingNumber()).thenReturn("9");
      when(existing.getCityName()).thenReturn("Sevilla");
      org.openbravo.model.common.geography.Country existingCountry =
          mock(org.openbravo.model.common.geography.Country.class);
      when(existingCountry.getId()).thenReturn("ES");
      when(existing.getCountry()).thenReturn(existingCountry);
      when(obDal.get(Location.class, "loc-1")).thenReturn(existing);

      JSONObject body = new JSONObject();
      body.put("buildingName", "Nuevo Edificio");

      assertNull(handler.handle(buildCrudContext("PATCH", "loc-1", body)));
    }

    @Test
    void patchOnAMissingRecordReturns404() {
      when(obDal.get(Location.class, "missing")).thenReturn(null);

      NeoResponse r = handler.handle(buildCrudContext("PATCH", "missing", new JSONObject()));

      assertNotNull(r);
      assertEquals(404, r.getHttpStatus());
    }

    @Test
    void fieldExceedingMaxLengthReturns400() throws Exception {
      Location existing = mock(Location.class);
      when(existing.getOBSEPAStreetName()).thenReturn("Calle Existente");
      when(existing.getOBSEPABuildingNumber()).thenReturn("9");
      when(existing.getCityName()).thenReturn("Sevilla");
      org.openbravo.model.common.geography.Country existingCountry =
          mock(org.openbravo.model.common.geography.Country.class);
      when(existingCountry.getId()).thenReturn("ES");
      when(existing.getCountry()).thenReturn(existingCountry);
      when(obDal.get(Location.class, "loc-1")).thenReturn(existing);

      JSONObject body = new JSONObject();
      body.put("buildingName", "C".repeat(36)); // EM_OBSEPA_BldgNm is VARCHAR(35)

      NeoResponse r = handler.handle(buildCrudContext("PUT", "loc-1", body));

      assertNotNull(r);
      assertEquals(400, r.getHttpStatus());
    }
  }

  // ── afterHandle() — persist (POST/PUT post-hook) ─────────────────────────

  @Nested
  class PersistAfterHandle {

    private MockedStatic<GoFeatureFlags> flags;

    @BeforeEach
    void stubFlagOn() {
      flags = mockStatic(GoFeatureFlags.class);
      flags.when(() -> GoFeatureFlags.isEnabled(
          eq(GoFeatureFlags.FLAG_STRUCTURED_ADDRESS_FOR_SEPA), any())).thenReturn(true);
    }

    @AfterEach
    void closeFlag() {
      flags.close();
    }

    @Test
    void nonCrudEndpointReturnsNull() {
      NeoContext ctx = NeoContext.builder()
          .specName(SPEC).entityName(ENTITY)
          .httpMethod("POST").endpointType(NeoEndpointType.ACTION)
          .build();
      assertNull(handler.afterHandle(ctx));
    }

    @Test
    void noPreviousResultReturnsNull() {
      NeoContext ctx = buildCrudContext("POST", null, new JSONObject());
      assertNull(handler.afterHandle(ctx));
    }

    @Test
    void createPersistsPendingFieldsAndEnrichesResponse() throws Exception {
      JSONObject body = new JSONObject();
      body.put("streetName", "Gran Via");
      body.put("buildingNumber", "45");
      body.put("buildingName", "Torre A");
      body.put("cityName", "Madrid");
      body.put("country", "ES");

      NeoContext preCtx = buildCrudContext("POST", null, body);
      assertNull(handler.handle(preCtx)); // pre-hook: remembers pending values

      Location saved = mock(Location.class);
      when(obDal.get(Location.class, "new-loc-id")).thenReturn(saved);

      NeoResponse generatedByCrud = wrapRecord("new-loc-id");
      NeoContext postCtx = buildCrudContext("POST", null, body);
      postCtx.setPreviousResult(generatedByCrud);

      NeoResponse result = handler.afterHandle(postCtx);

      assertNotNull(result);
      verify(saved).setOBSEPAStreetName("Gran Via");
      verify(saved).setOBSEPABuildingNumber("45");
      verify(saved).setOBSEPABuildingName("Torre A");
      verify(saved).setAddressLine1("Gran Via, 45 - Torre A");
      verify(obDal).flush();

      JSONObject recordJson = result.getBody().getJSONObject("response").getJSONArray("data").getJSONObject(0);
      assertEquals("Gran Via", recordJson.getString("streetName"));
      assertEquals("45", recordJson.getString("buildingNumber"));
      assertEquals("Torre A", recordJson.getString("buildingName"));
      assertEquals("Gran Via, 45 - Torre A", recordJson.getString("addressLine1"));
    }

    /**
     * PATCH reaches the persistence branch too, so a validated partial update recomposes
     * {@code Address1}/{@code Address2} instead of leaving them stale (ETP-5806 follow-up).
     */
    @Test
    void patchPersistsPendingFieldsAndRecomposesTheAddressLines() throws Exception {
      Location existing = mock(Location.class);
      when(existing.getOBSEPAStreetName()).thenReturn("Gran Via");
      when(existing.getOBSEPABuildingNumber()).thenReturn("45");
      when(existing.getCityName()).thenReturn("Madrid");
      org.openbravo.model.common.geography.Country existingCountry =
          mock(org.openbravo.model.common.geography.Country.class);
      when(existingCountry.getId()).thenReturn("ES");
      when(existing.getCountry()).thenReturn(existingCountry);
      when(obDal.get(Location.class, "loc-1")).thenReturn(existing);

      JSONObject body = new JSONObject();
      body.put("buildingName", "Torre A"); // partial body: only the optional field changes

      assertNull(handler.handle(buildCrudContext("PATCH", "loc-1", body)));

      NeoContext postCtx = buildCrudContext("PATCH", "loc-1", body);
      postCtx.setPreviousResult(wrapRecord("loc-1"));

      NeoResponse result = handler.afterHandle(postCtx);

      assertNotNull(result);
      verify(existing).setOBSEPAStreetName("Gran Via");
      verify(existing).setOBSEPABuildingNumber("45");
      verify(existing).setOBSEPABuildingName("Torre A");
      verify(existing).setAddressLine1("Gran Via, 45 - Torre A");
      verify(obDal).flush();
    }

    @Test
    void exceptionWhilePersistingReturns500() throws Exception {
      JSONObject body = new JSONObject();
      body.put("streetName", "Gran Via");
      body.put("buildingNumber", "45");
      body.put("cityName", "Madrid");
      body.put("country", "ES");

      NeoContext preCtx = buildCrudContext("POST", null, body);
      assertNull(handler.handle(preCtx));

      when(obDal.get(Location.class, "boom-id")).thenThrow(new RuntimeException("DB exploded"));

      NeoContext postCtx = buildCrudContext("POST", null, body);
      postCtx.setPreviousResult(wrapRecord("boom-id"));

      NeoResponse result = handler.afterHandle(postCtx);

      assertNotNull(result);
      assertEquals(500, result.getHttpStatus());
    }
  }

  // ── afterHandle() — GET enrichment ───────────────────────────────────────

  @Nested
  class GetEnrichment {

    private MockedStatic<GoFeatureFlags> flags;

    @BeforeEach
    void stubFlagOn() {
      flags = mockStatic(GoFeatureFlags.class);
      flags.when(() -> GoFeatureFlags.isEnabled(
          eq(GoFeatureFlags.FLAG_STRUCTURED_ADDRESS_FOR_SEPA), any())).thenReturn(true);
    }

    @AfterEach
    void closeFlag() {
      flags.close();
    }

    @Test
    void enrichesEveryRowWithStructuredFields() throws Exception {
      Location geoLoc = mock(Location.class);
      when(geoLoc.getOBSEPAStreetName()).thenReturn("Gran Via");
      when(geoLoc.getOBSEPABuildingNumber()).thenReturn("45");
      when(geoLoc.getOBSEPABuildingName()).thenReturn(null);
      when(obDal.get(Location.class, "loc-get")).thenReturn(geoLoc);

      NeoContext ctx = buildCrudContext("GET", "loc-get", null);
      ctx.setPreviousResult(wrapRecord("loc-get"));

      NeoResponse result = handler.afterHandle(ctx);

      assertNotNull(result);
      JSONObject recordJson = result.getBody().getJSONObject("response").getJSONArray("data").getJSONObject(0);
      assertEquals("Gran Via", recordJson.getString("streetName"));
      assertEquals("45", recordJson.getString("buildingNumber"));
      assertEquals(JSONObject.NULL, recordJson.get("buildingName"));
    }
  }

  // ── structured-address-for-sepa flag OFF ─────────────────────────────────

  @Nested
  class FlagOff {

    private MockedStatic<GoFeatureFlags> flags;

    @BeforeEach
    void stubFlagOff() {
      flags = mockStatic(GoFeatureFlags.class);
      flags.when(() -> GoFeatureFlags.isEnabled(
          eq(GoFeatureFlags.FLAG_STRUCTURED_ADDRESS_FOR_SEPA), any())).thenReturn(false);
    }

    @AfterEach
    void closeFlag() {
      flags.close();
    }

    /**
     * Flag off: {@code handle()} never intercepts a create, even one missing every mandatory
     * structured field — the validation in {@link BpLocationHandler#prepareStructuredAddress}
     * never runs, so the request falls straight through to generic CRUD.
     */
    @Test
    void handleReturnsNullForCreateRegardlessOfBody() throws Exception {
      JSONObject body = new JSONObject();
      // No streetName/buildingNumber/cityName/country at all — would be a 400 with the flag on.
      NeoResponse r = handler.handle(buildCrudContext("POST", null, body));
      assertNull(r);
      verify(obDal, never()).get(eq(Location.class), any());
    }

    /**
     * Flag off: {@code handle()} never intercepts an update either, so a stale client sending the
     * 3 structured fields on a PUT never reaches {@link BpLocationHandler#loadExisting}.
     */
    @Test
    void handleReturnsNullForUpdateRegardlessOfBody() throws Exception {
      JSONObject body = new JSONObject();
      body.put("streetName", "Gran Via");
      body.put("buildingNumber", "45");
      body.put("buildingName", "Local 2");
      NeoResponse r = handler.handle(buildCrudContext("PUT", "loc-1", body));
      assertNull(r);
      verify(obDal, never()).get(eq(Location.class), any());
    }

    /**
     * Flag off: a caller that sends the DAL property names is NOT writing them (ETP-5806
     * follow-up). The 3 columns DO carry {@code ETGO_SF_FIELD} rows for this entity
     * ({@code ISINCLUDED=Y}, {@code VISIBILITY=editable}, {@code ISREADONLY=N}, committed in
     * {@code ETGO_SF_FIELD.xml}), so falling through with the keys still on the body let generic
     * CRUD persist them ungated on REST, and the MCP write gate let them through for the same
     * reason — no mandatory check, no length check, and no {@code Address1}/{@code Address2}
     * recomposition, so the structured fields and the legacy lines drifted apart on a shared
     * {@code C_Location}. The pre-hook strips them from the body it declines to intercept.
     */
    @Test
    void handleStripsTheStructuredPropertiesFromAnUpdateBody() throws Exception {
      JSONObject body = new JSONObject();
      body.put("oBSEPAStreetName", "Gran Via");
      body.put("oBSEPABuildingNumber", "45");
      body.put("oBSEPABuildingName", "Torre A");
      body.put("addressLine1", "Gran Via 45");
      body.put("cityName", "Madrid");

      assertNull(handler.handle(buildCrudContext("PUT", "loc-1", body)));

      assertFalse(body.has("oBSEPAStreetName"));
      assertFalse(body.has("oBSEPABuildingNumber"));
      assertFalse(body.has("oBSEPABuildingName"));
      // Everything else the caller sent is untouched: flag off means the legacy plain-text
      // address path behaves exactly as it did before ETP-5806.
      assertEquals("Gran Via 45", body.getString("addressLine1"));
      assertEquals("Madrid", body.getString("cityName"));
    }

    /**
     * The same strip on a PATCH (ETP-5806 follow-up). PATCH was absent from the create/update
     * predicate, so {@code PATCH /sws/neo/bp-location/bpLocation/{id}} with a structured key still
     * reached generic CRUD and persisted it ungated while the flag was off — the exact hole the
     * strip exists to close, surviving on the easiest channel to reach.
     */
    @Test
    void handleStripsTheStructuredPropertiesFromAPatchBody() throws Exception {
      JSONObject body = new JSONObject();
      body.put("oBSEPAStreetName", "Gran Via");
      body.put("oBSEPABuildingNumber", "45");
      body.put("streetName", "Gran Via");
      body.put("EM_OBSEPA_BldgNm", "Torre A");
      body.put("cityName", "Madrid");

      assertNull(handler.handle(buildCrudContext("PATCH", "loc-1", body)));

      assertFalse(body.has("oBSEPAStreetName"));
      assertFalse(body.has("oBSEPABuildingNumber"));
      assertFalse(body.has("streetName"));
      assertFalse(body.has("EM_OBSEPA_BldgNm"));
      assertEquals("Madrid", body.getString("cityName"));
      verify(obDal, never()).get(eq(Location.class), any());
    }

    /** Same strip on a create, and for the published spellings and the raw AD column names. */
    @Test
    void handleStripsEverySpellingOfTheStructuredFieldsFromACreateBody() throws Exception {
      JSONObject body = new JSONObject();
      body.put("streetName", "Gran Via");
      body.put("oBSEPABuildingNumber", "45");
      body.put("EM_OBSEPA_BldgNm", "Torre A");
      body.put("addressLine2", "Torre A");

      assertNull(handler.handle(buildCrudContext("POST", null, body)));

      assertFalse(body.has("streetName"));
      assertFalse(body.has("oBSEPABuildingNumber"));
      assertFalse(body.has("EM_OBSEPA_BldgNm"));
      assertEquals("Torre A", body.getString("addressLine2"));
    }

    /**
     * ETP-5806 follow-up, the repro that was open: a {@code {"data": {...}}} envelope.
     * {@code NeoFieldFilter#filterBody} unwraps it and returns the INNER object, which
     * {@code NeoCrudHandler#executeUpdate} goes on to filter and persist — and the inner keys pass
     * the filter because the 3 columns are curated writable. A strip that only looked at the top
     * level removed nothing ({@code data} is the only top-level key), so
     * {@code PUT /sws/neo/bp-location/bpLocation/{id} {"data":{"oBSEPAStreetName":…}}} wrote the
     * field ungated: no mandatory check, no length check, no {@code Address1}/{@code Address2}
     * recomposition. Classic "mutates one object while persistence reads another".
     */
    @Test
    void handleStripsTheStructuredPropertiesFromADataWrappedUpdateBody() throws Exception {
      JSONObject inner = new JSONObject();
      inner.put("oBSEPAStreetName", "Gran Via");
      inner.put("oBSEPABuildingNumber", "45");
      inner.put("oBSEPABuildingName", "Torre A");
      inner.put("addressLine1", "Gran Via 45");
      inner.put("cityName", "Madrid");
      JSONObject body = new JSONObject();
      body.put("data", inner);

      assertNull(handler.handle(buildCrudContext("PUT", "loc-1", body)));

      assertFalse(inner.has("oBSEPAStreetName"));
      assertFalse(inner.has("oBSEPABuildingNumber"));
      assertFalse(inner.has("oBSEPABuildingName"));
      // Everything else inside the envelope is untouched.
      assertEquals("Gran Via 45", inner.getString("addressLine1"));
      assertEquals("Madrid", inner.getString("cityName"));
      // And the envelope itself survives — NeoFieldFilter still has something to unwrap.
      assertTrue(body.has("data"));
    }

    /** Same envelope on a create: {@code filterCreateRequest} calls the very same unwrap. */
    @Test
    void handleStripsEverySpellingFromADataWrappedCreateBody() throws Exception {
      JSONObject inner = new JSONObject();
      inner.put("streetName", "Gran Via");
      inner.put("oBSEPABuildingNumber", "45");
      inner.put("EM_OBSEPA_BldgNm", "Torre A");
      inner.put("addressLine2", "Torre A");
      JSONObject body = new JSONObject();
      body.put("data", inner);

      assertNull(handler.handle(buildCrudContext("POST", null, body)));

      assertFalse(inner.has("streetName"));
      assertFalse(inner.has("oBSEPABuildingNumber"));
      assertFalse(inner.has("EM_OBSEPA_BldgNm"));
      assertEquals("Torre A", inner.getString("addressLine2"));
    }

    /**
     * Both shapes in one body are handled in one pass — nothing guarantees only one is present,
     * and leaving either behind reopens the hole on whichever layer happens to read that one.
     */
    @Test
    void handleStripsBothTheTopLevelAndTheEnvelopeInOnePass() throws Exception {
      JSONObject inner = new JSONObject();
      inner.put("oBSEPAStreetName", "inner");
      JSONObject body = new JSONObject();
      body.put("oBSEPAStreetName", "outer");
      body.put("data", inner);

      assertNull(handler.handle(buildCrudContext("PATCH", "loc-1", body)));

      assertFalse(body.has("oBSEPAStreetName"));
      assertFalse(inner.has("oBSEPAStreetName"));
    }

    /**
     * The {@code /batch} channel (and MCP {@code etendo_batch}, which shares the method) reaches
     * this pre-hook: {@code BatchService#createRecord} builds a CRUD {@code NeoContext} with
     * {@code httpMethod "POST"}, a null {@code recordId} and the op's body, then dispatches
     * through {@code NeoServletSupport.handleWithHooks(..., REST_BATCH)} UNCONDITIONALLY — the
     * blank-qualifier early return was removed in ETP-5415 — and
     * {@code NeoExtensionDispatcher#resolve} tries the {@code @NeoExtension} index before the
     * qualifier on every channel. That matters here because {@code ETGO_SF_ENTITY.Java_Qualifier}
     * is empty for {@code bpLocation}: the annotation is the only binding. This asserts the body
     * such an op carries is stripped, in both shapes.
     */
    @Test
    void handleStripsABatchCreateOperationBody() throws Exception {
      JSONObject inner = new JSONObject();
      inner.put("oBSEPAStreetName", "Gran Via");
      JSONObject body = new JSONObject();
      body.put("oBSEPABuildingNumber", "45");
      body.put("cityName", "Madrid");
      body.put("data", inner);

      NeoContext ctx = NeoContext.builder()
          .specName(SPEC)
          .entityName(ENTITY)
          .httpMethod("POST")
          .requestBody(body)
          .queryParams(java.util.Collections.emptyMap())
          .endpointType(NeoEndpointType.CRUD)
          .build();

      assertNull(handler.handle(ctx));

      assertFalse(body.has("oBSEPABuildingNumber"));
      assertFalse(inner.has("oBSEPAStreetName"));
      assertEquals("Madrid", body.getString("cityName"));
    }

    /** A GET carries no write body, and the strip must not disturb the read path. */
    @Test
    void handleLeavesAReadAlone() throws Exception {
      JSONObject body = new JSONObject();
      body.put("oBSEPAStreetName", "Gran Via");

      assertNull(handler.handle(buildCrudContext("GET", "loc-1", body)));

      assertEquals("Gran Via", body.getString("oBSEPAStreetName"));
    }

    /**
     * Flag off: {@code afterHandle()} never persists the pending write (there is none — {@code
     * handle()} never set it while off) and never enriches a GET with the 3 structured keys; the
     * previous result passes through to the caller exactly as the generic CRUD layer produced it.
     */
    @Test
    void afterHandleReturnsNullAndNeverEnrichesAGetResponse() throws Exception {
      NeoContext ctx = buildCrudContext("GET", "loc-get", null);
      ctx.setPreviousResult(wrapRecord("loc-get"));

      NeoResponse result = handler.afterHandle(ctx);

      assertNull(result);
      verify(obDal, never()).get(eq(Location.class), any());
    }

    /**
     * Flag off: {@code afterHandle()} never persists pending structured fields after a create
     * either — the base record's fields fall through untouched to whatever generic CRUD wrote.
     */
    @Test
    void afterHandleReturnsNullAfterCreateRegardlessOfPreviousResult() throws Exception {
      NeoContext ctx = buildCrudContext("POST", null, new JSONObject());
      ctx.setPreviousResult(wrapRecord("new-loc-id"));

      NeoResponse result = handler.afterHandle(ctx);

      assertNull(result);
      verify(obDal, never()).get(eq(Location.class), any());
    }
  }

  // ── helpers ────────────────────────────────────────────────────────────

  // ── ETP-5806 follow-up: per-account targeting ─────────────────────────────

  /**
   * The write/read gating resolves the flag for the REQUESTING ACCOUNT, not account-less. Before
   * this follow-up the capability signal was already per-account while this handler was not, so a
   * targeted user saw the 3 fields in the UI, filled them in, and {@code handle()} returned
   * {@code null} — generic CRUD then dropped them without an error, per the flag's deliberate
   * "ignore, don't reject" contract.
   */
  @Nested
  class PerAccountTargeting {

    private static final String TARGETED_ACCOUNT = "localization@etendo.software";
    private static final String OTHER_ACCOUNT = "someone.else@example.com";

    /** True for {@link #TARGETED_ACCOUNT} only; false for every other key, including none. */
    private MockedStatic<GoFeatureFlags> targetedFlag() {
      MockedStatic<GoFeatureFlags> flags = mockStatic(GoFeatureFlags.class);
      flags.when(() -> GoFeatureFlags.isEnabled(
          eq(GoFeatureFlags.FLAG_STRUCTURED_ADDRESS_FOR_SEPA), any())).thenReturn(false);
      flags.when(() -> GoFeatureFlags.isEnabled(
          eq(GoFeatureFlags.FLAG_STRUCTURED_ADDRESS_FOR_SEPA),
          argThat(ctx -> ctx != null && TARGETED_ACCOUNT.equals(ctx.getTargetingKey()))))
          .thenReturn(true);
      return flags;
    }

    /** The targeted account's create IS intercepted and validated, so nothing is dropped. */
    @Test
    void handleInterceptsAndValidatesForATargetedAccount() throws Exception {
      try (MockedStatic<GoAccountResolver> accounts = mockStatic(GoAccountResolver.class);
          MockedStatic<GoFeatureFlags> flags = targetedFlag()) {
        accounts.when(GoAccountResolver::resolveCurrentAccountEmail).thenReturn(TARGETED_ACCOUNT);

        NeoResponse r = handler.handle(buildCrudContext("POST", null, new JSONObject()));

        assertNotNull(r);
        assertEquals(400, r.getHttpStatus());
      }
    }

    /** Same environment, untargeted account: the off behaviour is unchanged. */
    @Test
    void handleFallsThroughForAnUntargetedAccount() throws Exception {
      try (MockedStatic<GoAccountResolver> accounts = mockStatic(GoAccountResolver.class);
          MockedStatic<GoFeatureFlags> flags = targetedFlag()) {
        accounts.when(GoAccountResolver::resolveCurrentAccountEmail).thenReturn(OTHER_ACCOUNT);

        assertNull(handler.handle(buildCrudContext("POST", null, new JSONObject())));
      }
    }

    /**
     * An untargeted account cannot write the 3 fields by naming the DAL properties: the fall-
     * through strips them from the body generic CRUD goes on to persist (ETP-5806 follow-up).
     */
    @Test
    void handleStripsTheStructuredPropertiesForAnUntargetedAccount() throws Exception {
      try (MockedStatic<GoAccountResolver> accounts = mockStatic(GoAccountResolver.class);
          MockedStatic<GoFeatureFlags> flags = targetedFlag()) {
        accounts.when(GoAccountResolver::resolveCurrentAccountEmail).thenReturn(OTHER_ACCOUNT);
        JSONObject body = new JSONObject();
        body.put("oBSEPAStreetName", "Gran Via");
        body.put("oBSEPABuildingNumber", "45");

        assertNull(handler.handle(buildCrudContext("PUT", "loc-1", body)));

        assertFalse(body.has("oBSEPAStreetName"));
        assertFalse(body.has("oBSEPABuildingNumber"));
      }
    }

    /**
     * A targeted account's body is NOT stripped: the handler owns those keys while the flag is on,
     * and {@code afterHandle} writes the validated values itself.
     */
    @Test
    void handleKeepsTheStructuredFieldsForATargetedAccount() throws Exception {
      try (MockedStatic<GoAccountResolver> accounts = mockStatic(GoAccountResolver.class);
          MockedStatic<GoFeatureFlags> flags = targetedFlag()) {
        accounts.when(GoAccountResolver::resolveCurrentAccountEmail).thenReturn(TARGETED_ACCOUNT);
        JSONObject body = new JSONObject();
        body.put("streetName", "Gran Via");

        handler.handle(buildCrudContext("POST", null, body));

        assertTrue(body.has("streetName"));
      }
    }

    /** The read path is gated for the same account, so a targeted GET gets the 3 keys back. */
    @Test
    void afterHandleEnrichesForATargetedAccount() throws Exception {
      Location geoLoc = mock(Location.class);
      when(geoLoc.getOBSEPAStreetName()).thenReturn("Gran Via");
      when(geoLoc.getOBSEPABuildingNumber()).thenReturn("45");
      when(geoLoc.getOBSEPABuildingName()).thenReturn("Torre A");
      when(obDal.get(eq(Location.class), eq("loc-get"))).thenReturn(geoLoc);

      try (MockedStatic<GoAccountResolver> accounts = mockStatic(GoAccountResolver.class);
          MockedStatic<GoFeatureFlags> flags = targetedFlag()) {
        accounts.when(GoAccountResolver::resolveCurrentAccountEmail).thenReturn(TARGETED_ACCOUNT);

        NeoContext ctx = buildCrudContext("GET", "loc-get", null);
        ctx.setPreviousResult(wrapRecord("loc-get"));

        NeoResponse result = handler.afterHandle(ctx);

        assertNotNull(result);
        JSONObject recordJson =
            result.getBody().getJSONObject("response").getJSONArray("data").getJSONObject(0);
        assertEquals("Gran Via", recordJson.getString("streetName"));
        assertEquals("45", recordJson.getString("buildingNumber"));
        assertEquals("Torre A", recordJson.getString("buildingName"));
      }
    }

    /** Same environment and same record, untargeted account: the response is left untouched. */
    @Test
    void afterHandleDoesNotEnrichForAnUntargetedAccount() throws Exception {
      Location geoLoc = mock(Location.class);
      when(geoLoc.getOBSEPAStreetName()).thenReturn("Gran Via");
      when(obDal.get(eq(Location.class), eq("loc-get"))).thenReturn(geoLoc);

      try (MockedStatic<GoAccountResolver> accounts = mockStatic(GoAccountResolver.class);
          MockedStatic<GoFeatureFlags> flags = targetedFlag()) {
        accounts.when(GoAccountResolver::resolveCurrentAccountEmail).thenReturn(OTHER_ACCOUNT);

        NeoContext ctx = buildCrudContext("GET", "loc-get", null);
        ctx.setPreviousResult(wrapRecord("loc-get"));
        assertNull(handler.afterHandle(ctx));
      }
    }
  }

  private NeoContext buildCrudContext(String method, String recordId, JSONObject body) {
    return NeoContext.builder()
        .specName(SPEC)
        .entityName(ENTITY)
        .httpMethod(method)
        .recordId(recordId)
        .requestBody(body)
        .endpointType(NeoEndpointType.CRUD)
        .build();
  }

  private NeoResponse wrapRecord(String id) throws Exception {
    JSONObject recordJson = new JSONObject();
    recordJson.put("id", id);
    JSONArray dataArr = new JSONArray();
    dataArr.put(recordJson);
    JSONObject responseData = new JSONObject();
    responseData.put("status", 0);
    responseData.put("data", dataArr);
    JSONObject wrapper = new JSONObject();
    wrapper.put("response", responseData);
    return new NeoResponse(200, wrapper);
  }
}
