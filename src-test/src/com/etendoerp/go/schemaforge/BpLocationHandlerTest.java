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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
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

import com.etendoerp.go.featureflags.GoFeatureFlags;

/**
 * Unit tests for {@link BpLocationHandler} (ETP-5806).
 *
 * <p>This entity is a direct, un-wrapped CRUD mapping onto {@code C_Location} with no curated
 * {@code ETGO_SF_FIELD} rows for the 3 structured SEPA columns (see the class javadoc), so the
 * handler owns them end to end: a pre-hook ({@code handle}) validates and remembers the effective
 * values, and a post-hook ({@code afterHandle}) persists them (POST/PUT) once the base record
 * exists, or enriches the response with them (GET) — bypassing {@code NeoFieldFilter} either way.
 * Also the {@code structured-address-for-sepa} flag-OFF branch ({@link FlagOff}), stubbing
 * {@link GoFeatureFlags#isEnabled} directly so the suite does not depend on the local
 * {@code Openbravo.properties} toggle: {@code handle()}/{@code afterHandle()} return {@code null}
 * immediately, so the 3 fields fall through untouched to generic CRUD.
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
  }

  // ── handle() — update (pre-hook) ─────────────────────────────────────────

  @Nested
  class Update {

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
