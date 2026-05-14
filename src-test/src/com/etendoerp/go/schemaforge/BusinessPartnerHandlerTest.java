/*
 *************************************************************************
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
 *************************************************************************
 */
package com.etendoerp.go.schemaforge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.openbravo.dal.service.OBDal;

/**
 * Unit tests for {@link BusinessPartnerHandler}.
 *
 * <p>Covers the full decision tree of {@code handle()} and {@code afterHandle()}:
 * method guards (GET/non-write returns null), name derivation from firstname/lastname,
 * searchKey injection, pre-create billing field stripping, and the post-save
 * searchKey synchronisation from {@code em_etgo_identifier}.
 *
 * <p>Tests that touch the database use {@code mockStatic(OBDal.class)} to provide a
 * mock JDBC {@link Connection} and avoid requiring a live Etendo environment.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BusinessPartnerHandlerTest {

  private BusinessPartnerHandler handler;
  private NeoContext ctx;

  /**
   * Builds a minimal NEO POST response body with {@code response.data[0].id = recordId}.
   */
  private static JSONObject buildResponseBody() throws Exception {
    JSONObject recordEntry = new JSONObject();
    recordEntry.put("id", "REC_ID");
    JSONArray data = new JSONArray();
    data.put(recordEntry);
    JSONObject response = new JSONObject();
    response.put("data", data);
    JSONObject body = new JSONObject();
    body.put("response", response);
    return body;
  }

  // ── handle() — method guard ───────────────────────────────────────────────────

  @BeforeEach
  void setUp() {
    handler = new BusinessPartnerHandler();
    ctx = mock(NeoContext.class);
  }

  /**
   * Non-write methods (GET, DELETE) must return {@code null} immediately without
   * touching the request body.
   */
  @Test
  void testHandleGetMethodReturnsNull() {
    when(ctx.getHttpMethod()).thenReturn("GET");
    assertNull(handler.handle(ctx));
  }

  // ── handle() — POST: name derivation ─────────────────────────────────────────

  /**
   * When the request body is {@code null}, {@code handle()} must return {@code null}
   * regardless of the HTTP method.
   */
  @Test
  void testHandleNullBodyReturnsNull() {
    when(ctx.getHttpMethod()).thenReturn("POST");
    when(ctx.getRequestBody()).thenReturn(null);
    assertNull(handler.handle(ctx));
  }

  /**
   * When the POST body contains {@code etgoFirstname} and {@code etgoLastname} but no
   * {@code name}, the handler must derive {@code name} by concatenating the two parts.
   */
  @Test
  void testHandlePostDerivesNameFromFirstnameAndLastname() throws Exception {
    JSONObject body = new JSONObject();
    body.put("etgoFirstname", "Juan");
    body.put("etgoLastname", "García");
    when(ctx.getHttpMethod()).thenReturn("POST");
    when(ctx.getRequestBody()).thenReturn(body);

    handler.handle(ctx);

    assertEquals("Juan García", body.getString("name"));
  }

  /**
   * When only {@code etgoFirstname} is present and {@code etgoLastname} is absent,
   * {@code name} must be set to the first name only.
   */
  @Test
  void testHandlePostDerivesNameFromFirstnameOnly() throws Exception {
    JSONObject body = new JSONObject();
    body.put("etgoFirstname", "María");
    when(ctx.getHttpMethod()).thenReturn("POST");
    when(ctx.getRequestBody()).thenReturn(body);

    handler.handle(ctx);

    assertEquals("María", body.getString("name"));
  }

  // ── handle() — POST: searchKey injection ────────────────────────────────────

  /**
   * When {@code name} is already set in the POST body, the derivation must be skipped
   * even if firstname/lastname are also present.
   */
  @Test
  void testHandlePostDoesNotOverrideExistingName() throws Exception {
    JSONObject body = new JSONObject();
    body.put("name", "Nombre Existente");
    body.put("etgoFirstname", "Juan");
    body.put("etgoLastname", "García");
    when(ctx.getHttpMethod()).thenReturn("POST");
    when(ctx.getRequestBody()).thenReturn(body);

    handler.handle(ctx);

    assertEquals("Nombre Existente", body.getString("name"));
  }

  /**
   * When a {@code name} is present and {@code searchKey} is absent on a POST,
   * the handler must inject {@code searchKey = name} as a temporary placeholder.
   */
  @Test
  void testHandlePostInjectsSearchKeyFromName() throws Exception {
    JSONObject body = new JSONObject();
    body.put("name", "Empresa Test");
    when(ctx.getHttpMethod()).thenReturn("POST");
    when(ctx.getRequestBody()).thenReturn(body);

    handler.handle(ctx);

    assertEquals("Empresa Test", body.getString("searchKey"));
  }

  // ── handle() — POST: billing field stripping ─────────────────────────────────

  /**
   * When {@code searchKey} already exists in the POST body, the handler must not
   * overwrite it with the derived name.
   */
  @Test
  void testHandlePostDoesNotOverrideExistingSearchKey() throws Exception {
    JSONObject body = new JSONObject();
    body.put("name", "Empresa Test");
    body.put("searchKey", "CLAVE_EXISTENTE");
    when(ctx.getHttpMethod()).thenReturn("POST");
    when(ctx.getRequestBody()).thenReturn(body);

    handler.handle(ctx);

    assertEquals("CLAVE_EXISTENTE", body.getString("searchKey"));
  }

  // ── handle() — PATCH: name derivation from persisted parts ───────────────────

  /**
   * Billing-related defaults (priceList, paymentMethod, etc.) must be stripped from
   * the POST body before the record is saved to avoid premature association.
   */
  @Test
  void testHandlePostStripsBillingDefaults() throws Exception {
    JSONObject body = new JSONObject();
    body.put("name", "Empresa Test");
    body.put("priceList", "PRICE_LIST_ID");
    body.put("priceList$_identifier", "Lista de venta");
    body.put("paymentMethod", "PM_ID");
    when(ctx.getHttpMethod()).thenReturn("POST");
    when(ctx.getRequestBody()).thenReturn(body);

    handler.handle(ctx);

    assertNull(body.optString("priceList", null));
    assertNull(body.optString("priceList$_identifier", null));
    assertNull(body.optString("paymentMethod", null));
  }

  /**
   * On PATCH, when the persisted name is blank, the handler must derive it by merging
   * persisted parts with the incoming body values.
   */
  @Test
  void testHandlePatchDerivesNameWhenPersistedNameIsBlank() throws Exception {
    JSONObject body = new JSONObject();
    body.put("etgoFirstname", "Pedro");

    when(ctx.getHttpMethod()).thenReturn("PATCH");
    when(ctx.getRequestBody()).thenReturn(body);
    when(ctx.getRecordId()).thenReturn("REC_001");

    Connection connMock = mock(Connection.class);
    PreparedStatement psMock = mock(PreparedStatement.class);
    ResultSet rsMock = mock(ResultSet.class);

    when(rsMock.next()).thenReturn(true);
    when(rsMock.getString(1)).thenReturn("");
    when(rsMock.getString(2)).thenReturn("OldFirst");
    when(rsMock.getString(3)).thenReturn("López");
    when(psMock.executeQuery()).thenReturn(rsMock);
    when(connMock.prepareStatement(anyString())).thenReturn(psMock);

    try (MockedStatic<OBDal> mDal = mockStatic(OBDal.class)) {
      OBDal obDalMock = mock(OBDal.class);
      when(obDalMock.getConnection()).thenReturn(connMock);
      mDal.when(OBDal::getInstance).thenReturn(obDalMock);

      handler.handle(ctx);
    }

    assertEquals("Pedro López", body.getString("name"));
  }

  /**
   * On PATCH, when the persisted name is already set, the handler must not overwrite it.
   */
  @Test
  void testHandlePatchSkipsNameDerivationWhenPersistedNameSet() throws Exception {
    JSONObject body = new JSONObject();
    body.put("etgoFirstname", "Pedro");

    when(ctx.getHttpMethod()).thenReturn("PATCH");
    when(ctx.getRequestBody()).thenReturn(body);
    when(ctx.getRecordId()).thenReturn("REC_001");

    Connection connMock = mock(Connection.class);
    PreparedStatement psMock = mock(PreparedStatement.class);
    ResultSet rsMock = mock(ResultSet.class);

    when(rsMock.next()).thenReturn(true);
    when(rsMock.getString(1)).thenReturn("Nombre Guardado");
    when(psMock.executeQuery()).thenReturn(rsMock);
    when(connMock.prepareStatement(anyString())).thenReturn(psMock);

    try (MockedStatic<OBDal> mDal = mockStatic(OBDal.class)) {
      OBDal obDalMock = mock(OBDal.class);
      when(obDalMock.getConnection()).thenReturn(connMock);
      mDal.when(OBDal::getInstance).thenReturn(obDalMock);

      handler.handle(ctx);
    }

    assertNull(body.optString("name", null));
  }

  // ── afterHandle() — method guard ─────────────────────────────────────────────

  /**
   * On PATCH, when the recordId is blank, the handler must skip name derivation
   * entirely without querying the database.
   */
  @Test
  void testHandlePatchSkipsNameDerivationWhenRecordIdBlank() throws Exception {
    JSONObject body = new JSONObject();
    body.put("etgoFirstname", "Pedro");

    when(ctx.getHttpMethod()).thenReturn("PATCH");
    when(ctx.getRequestBody()).thenReturn(body);
    when(ctx.getRecordId()).thenReturn("");

    handler.handle(ctx);

    assertNull(body.optString("name", null));
  }

  /**
   * Non-POST methods (GET, PATCH) must return {@code null} from {@code afterHandle()}
   * without attempting to read the previous result.
   */
  @Test
  void testAfterHandleNonPostMethodReturnsNull() {
    when(ctx.getHttpMethod()).thenReturn("PATCH");
    assertNull(handler.afterHandle(ctx));
  }

  /**
   * When {@code getPreviousResult()} returns {@code null}, {@code afterHandle()} must
   * return {@code null} without throwing.
   */
  @Test
  void testAfterHandleNullPreviousResultReturnsNull() {
    when(ctx.getHttpMethod()).thenReturn("POST");
    when(ctx.getPreviousResult()).thenReturn(null);
    assertNull(handler.afterHandle(ctx));
  }

  /**
   * When the previous result's body is {@code null}, {@code afterHandle()} must
   * return {@code null}.
   */
  @Test
  void testAfterHandleNullBodyInPreviousResultReturnsNull() {
    NeoResponse prevResult = mock(NeoResponse.class);
    when(prevResult.getBody()).thenReturn(null);
    when(ctx.getHttpMethod()).thenReturn("POST");
    when(ctx.getPreviousResult()).thenReturn(prevResult);

    assertNull(handler.afterHandle(ctx));
  }

  /**
   * When the response body contains no {@code response.data} array, the record ID
   * cannot be extracted and {@code afterHandle()} must return {@code null}.
   */
  @Test
  void testAfterHandleNoRecordIdInBodyReturnsNull() {
    NeoResponse prevResult = mock(NeoResponse.class);
    when(prevResult.getBody()).thenReturn(new JSONObject());
    when(ctx.getHttpMethod()).thenReturn("POST");
    when(ctx.getPreviousResult()).thenReturn(prevResult);

    assertNull(handler.afterHandle(ctx));
  }

  // ── afterHandle() — happy path ────────────────────────────────────────────────

  /**
   * When the database returns a blank {@code em_etgo_identifier}, the handler must
   * return {@code null} without patching the response.
   */
  @Test
  void testAfterHandleBlankIdentifierReturnsNull() throws Exception {
    JSONObject body = buildResponseBody();
    NeoResponse prevResult = mock(NeoResponse.class);
    when(prevResult.getBody()).thenReturn(body);
    when(ctx.getHttpMethod()).thenReturn("POST");
    when(ctx.getPreviousResult()).thenReturn(prevResult);

    Connection connMock = mock(Connection.class);
    PreparedStatement psMock = mock(PreparedStatement.class);
    ResultSet rsMock = mock(ResultSet.class);

    when(rsMock.next()).thenReturn(false);
    when(psMock.executeQuery()).thenReturn(rsMock);
    when(connMock.prepareStatement(anyString())).thenReturn(psMock);

    try (MockedStatic<OBDal> mDal = mockStatic(OBDal.class)) {
      OBDal obDalMock = mock(OBDal.class);
      when(obDalMock.getConnection()).thenReturn(connMock);
      mDal.when(OBDal::getInstance).thenReturn(obDalMock);

      assertNull(handler.afterHandle(ctx));
    }
  }

  // ── Helpers ───────────────────────────────────────────────────────────────────

  /**
   * When the POST response contains a valid record ID and the database returns a
   * non-blank {@code em_etgo_identifier}, the handler must:
   * <ul>
   *   <li>update the {@code value} column via SQL</li>
   *   <li>patch {@code searchKey} in the response JSON</li>
   *   <li>return a non-null {@link NeoResponse}</li>
   * </ul>
   */
  @Test
  void testAfterHandleHappyPathPatchesSearchKeyAndReturnsResponse() throws Exception {
    JSONObject body = buildResponseBody();
    NeoResponse prevResult = mock(NeoResponse.class);
    when(prevResult.getBody()).thenReturn(body);
    when(ctx.getHttpMethod()).thenReturn("POST");
    when(ctx.getPreviousResult()).thenReturn(prevResult);

    Connection connMock = mock(Connection.class);
    PreparedStatement psSelect = mock(PreparedStatement.class);
    PreparedStatement psUpdate = mock(PreparedStatement.class);
    ResultSet rsMock = mock(ResultSet.class);

    when(rsMock.next()).thenReturn(true);
    when(rsMock.getString(1)).thenReturn("1000067");
    when(psSelect.executeQuery()).thenReturn(rsMock);
    when(connMock.prepareStatement(argThat(s -> s != null && s.contains("em_etgo_identifier")))).thenReturn(psSelect);
    when(connMock.prepareStatement(argThat(s -> s != null && s.contains("UPDATE")))).thenReturn(psUpdate);

    try (MockedStatic<OBDal> mDal = mockStatic(OBDal.class)) {
      OBDal obDalMock = mock(OBDal.class);
      when(obDalMock.getConnection()).thenReturn(connMock);
      mDal.when(OBDal::getInstance).thenReturn(obDalMock);

      NeoResponse result = handler.afterHandle(ctx);

      assertNotNull(result);
      String patchedKey = body.getJSONObject("response").getJSONArray("data").getJSONObject(0).getString("searchKey");
      assertEquals("1000067", patchedKey);
    }
  }
}
