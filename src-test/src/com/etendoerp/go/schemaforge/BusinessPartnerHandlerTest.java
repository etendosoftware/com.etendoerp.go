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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.openbravo.erpCommon.utility.OBMessageUtils;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;

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

  /**
   * Regression (ETP-4156): {@code C_BPartner.Value} is {@code VARCHAR(40)} while
   * {@code Name} has 60, so a long commercial name must be truncated before it is used as
   * the placeholder searchKey — otherwise the save fails with
   * "Value too long. Length 48, maximum allowed 40". The app-shell used to pre-truncate
   * this client-side; the guard now lives here.
   */
  @Test
  void testHandlePostTruncatesInjectedSearchKeyToColumnLength() throws Exception {
    String longName = "Comercializadora Internacional de Suministros Generales";
    JSONObject body = new JSONObject();
    body.put("name", longName);
    when(ctx.getHttpMethod()).thenReturn("POST");
    when(ctx.getRequestBody()).thenReturn(body);

    handler.handle(ctx);

    assertEquals(40, body.getString("searchKey").length());
    assertEquals(longName.substring(0, 40), body.getString("searchKey"));
    assertEquals(longName, body.getString("name"), "name itself must not be truncated");
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

  /**
   * Reproduces ETP-4469: two concurrent POSTs race on {@code updateSearchKey()} and the
   * second one hits the {@code c_bpartner_value} unique-constraint violation (both rows
   * fetched the same not-yet-committed sequence value). The handler must protect the
   * update with a savepoint and roll back to it on the duplicate-key error, so the
   * shared {@code /batch} connection is recovered instead of left poisoned for whatever
   * statement runs next in the same transaction.
   */
  @Test
  void testAfterHandleRecoversFromDuplicateSearchKeyRaceWithSavepoint() throws Exception {
    JSONObject body = buildResponseBody();
    NeoResponse prevResult = mock(NeoResponse.class);
    when(prevResult.getBody()).thenReturn(body);
    when(ctx.getHttpMethod()).thenReturn("POST");
    when(ctx.getPreviousResult()).thenReturn(prevResult);

    Connection connMock = mock(Connection.class);
    PreparedStatement psSelect = mock(PreparedStatement.class);
    PreparedStatement psUpdate = mock(PreparedStatement.class);
    ResultSet rsMock = mock(ResultSet.class);
    Savepoint savepointMock = mock(Savepoint.class);

    when(rsMock.next()).thenReturn(true);
    when(rsMock.getString(1)).thenReturn("1000013");
    when(psSelect.executeQuery()).thenReturn(rsMock);
    when(connMock.prepareStatement(argThat(s -> s != null && s.contains("em_etgo_identifier")))).thenReturn(psSelect);
    when(connMock.prepareStatement(argThat(s -> s != null && s.contains("UPDATE")))).thenReturn(psUpdate);
    when(connMock.setSavepoint()).thenReturn(savepointMock);

    SQLException duplicateKeyViolation = new SQLException(
        "ERROR: duplicate key value violates unique constraint \"c_bpartner_value\"", "23505");
    doThrow(duplicateKeyViolation).when(psUpdate).executeUpdate();

    try (MockedStatic<OBDal> mDal = mockStatic(OBDal.class)) {
      OBDal obDalMock = mock(OBDal.class);
      when(obDalMock.getConnection()).thenReturn(connMock);
      mDal.when(OBDal::getInstance).thenReturn(obDalMock);

      // Must not throw / must not blow up the batch — this is what the outer swallowing
      // try/catch already gives us today, so it alone is not enough to prove the fix.
      handler.afterHandle(ctx);

      // The real assertion: the handler must recover the connection via a savepoint
      // rollback rather than leaving the failed UPDATE unrolled-back on a shared connection.
      verify(connMock).setSavepoint();
      verify(connMock).rollback(savepointMock);
      verify(connMock, never()).rollback();
    }
  }

  // ── afterHandle() — GET: contact email fallback ──────────────────────────────

  /**
   * Builds a NEO GET response body with a single record carrying {@code id = REC_ID}
   * and the given {@code etgoEmail} (omitted when {@code email} is {@code null}).
   */
  private static JSONObject buildGetBody(String email) throws Exception {
    JSONObject recordEntry = new JSONObject();
    recordEntry.put("id", "REC_ID");
    if (email != null) {
      recordEntry.put("etgoEmail", email);
    }
    JSONArray data = new JSONArray();
    data.put(recordEntry);
    JSONObject response = new JSONObject();
    response.put("data", data);
    JSONObject body = new JSONObject();
    body.put("response", response);
    return body;
  }

  /**
   * A GET with no recordId is a list fetch; the handler must skip the email fallback
   * and return {@code null} without touching the database.
   */
  @Test
  void testAfterHandleGetBlankRecordIdReturnsNull() {
    when(ctx.getHttpMethod()).thenReturn("GET");
    when(ctx.getRecordId()).thenReturn("");
    assertNull(handler.afterHandle(ctx));
  }

  /**
   * When the partner record already carries its own {@code etgoEmail}, the handler must
   * leave the response untouched (return {@code null}) and never query contacts.
   */
  @Test
  void testAfterHandleGetPartnerAlreadyHasEmailReturnsNull() throws Exception {
    NeoResponse prevResult = mock(NeoResponse.class);
    when(prevResult.getBody()).thenReturn(buildGetBody("partner@own.com"));
    when(ctx.getHttpMethod()).thenReturn("GET");
    when(ctx.getRecordId()).thenReturn("REC_ID");
    when(ctx.getPreviousResult()).thenReturn(prevResult);

    assertNull(handler.afterHandle(ctx));
  }

  /**
   * When the partner email is blank and no contact has a valid email, the handler must
   * return {@code null} and leave the record unchanged.
   */
  @Test
  void testAfterHandleGetNoContactEmailReturnsNull() throws Exception {
    JSONObject body = buildGetBody(null);
    NeoResponse prevResult = mock(NeoResponse.class);
    when(prevResult.getBody()).thenReturn(body);
    when(ctx.getHttpMethod()).thenReturn("GET");
    when(ctx.getRecordId()).thenReturn("REC_ID");
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

  /**
   * When the partner email is blank and a contact has a valid email, the handler must
   * inject that email into {@code etgoEmail} and return a non-null response.
   */
  @Test
  void testAfterHandleGetInjectsContactEmail() throws Exception {
    JSONObject body = buildGetBody(null);
    NeoResponse prevResult = mock(NeoResponse.class);
    when(prevResult.getBody()).thenReturn(body);
    when(ctx.getHttpMethod()).thenReturn("GET");
    when(ctx.getRecordId()).thenReturn("REC_ID");
    when(ctx.getPreviousResult()).thenReturn(prevResult);

    Connection connMock = mock(Connection.class);
    PreparedStatement psMock = mock(PreparedStatement.class);
    ResultSet rsMock = mock(ResultSet.class);
    when(rsMock.next()).thenReturn(true);
    when(rsMock.getString(1)).thenReturn("contact@partner.com");
    when(psMock.executeQuery()).thenReturn(rsMock);
    when(connMock.prepareStatement(anyString())).thenReturn(psMock);

    try (MockedStatic<OBDal> mDal = mockStatic(OBDal.class)) {
      OBDal obDalMock = mock(OBDal.class);
      when(obDalMock.getConnection()).thenReturn(connMock);
      mDal.when(OBDal::getInstance).thenReturn(obDalMock);

      NeoResponse result = handler.afterHandle(ctx);

      assertNotNull(result);
      String patchedEmail = body.getJSONObject("response").getJSONArray("data").getJSONObject(0).getString("etgoEmail");
      assertEquals("contact@partner.com", patchedEmail);
    }
  }

  /**
   * A GET with a recordId but a {@code null} previous result must skip the fallback
   * and return {@code null} without touching the database.
   */
  @Test
  void testAfterHandleGetNullPreviousResultReturnsNull() {
    when(ctx.getHttpMethod()).thenReturn("GET");
    when(ctx.getRecordId()).thenReturn("REC_ID");
    when(ctx.getPreviousResult()).thenReturn(null);

    assertNull(handler.afterHandle(ctx));
  }

  /**
   * A GET whose response body carries no record array must skip the fallback and
   * return {@code null}.
   */
  @Test
  void testAfterHandleGetNoRecordInBodyReturnsNull() {
    NeoResponse prevResult = mock(NeoResponse.class);
    when(prevResult.getBody()).thenReturn(new JSONObject());
    when(ctx.getHttpMethod()).thenReturn("GET");
    when(ctx.getRecordId()).thenReturn("REC_ID");
    when(ctx.getPreviousResult()).thenReturn(prevResult);

    assertNull(handler.afterHandle(ctx));
  }

  // ── afterHandle() — DELETE guard ─────────────────────────────────────────

  /**
   * DELETE is not a write method — afterHandle must return {@code null} immediately.
   */
  @Test
  void testAfterHandleDeleteReturnsNull() {
    when(ctx.getHttpMethod()).thenReturn("DELETE");
    assertNull(handler.afterHandle(ctx));
  }

  // ── afterHandle() — injectViesMessage: guard paths ──────────────────────

  /**
   * When the response body has no "response" key, injectViesMessage returns false
   * and afterHandle returns null (PUT path — no searchKey update).
   */
  @Test
  void testAfterHandlePutNoViesMessageWhenBodyHasNoResponseKey() throws Exception {
    JSONObject body = new JSONObject();
    body.put("someOtherKey", "value");

    NeoResponse prevResult = mock(NeoResponse.class);
    when(prevResult.getBody()).thenReturn(body);
    when(ctx.getHttpMethod()).thenReturn("PUT");
    when(ctx.getPreviousResult()).thenReturn(prevResult);

    assertNull(handler.afterHandle(ctx));
  }

  /**
   * When the response data array is empty, injectViesMessage returns false
   * and afterHandle returns null.
   */
  @Test
  void testAfterHandlePutNoViesMessageWhenDataArrayIsEmpty() throws Exception {
    JSONObject response = new JSONObject();
    response.put("data", new JSONArray());
    JSONObject body = new JSONObject();
    body.put("response", response);

    NeoResponse prevResult = mock(NeoResponse.class);
    when(prevResult.getBody()).thenReturn(body);
    when(ctx.getHttpMethod()).thenReturn("PUT");
    when(ctx.getPreviousResult()).thenReturn(prevResult);

    assertNull(handler.afterHandle(ctx));
  }

  /**
   * When oBTIKTaxIDKey != "2", injectViesMessage must skip message injection
   * and afterHandle returns null (no searchKey patch on PUT).
   */
  @Test
  void testAfterHandlePutNoViesMessageWhenTaxKeyNotNOI() throws Exception {
    JSONObject savedRecord = new JSONObject();
    savedRecord.put("id", "REC_ID");
    savedRecord.put("oBTIKTaxIDKey", "1");
    savedRecord.put("oBTIKVIESStatus", "V");
    JSONArray data = new JSONArray();
    data.put(savedRecord);
    JSONObject response = new JSONObject();
    response.put("data", data);
    JSONObject body = new JSONObject();
    body.put("response", response);

    NeoResponse prevResult = mock(NeoResponse.class);
    when(prevResult.getBody()).thenReturn(body);
    when(ctx.getHttpMethod()).thenReturn("PUT");
    when(ctx.getPreviousResult()).thenReturn(prevResult);

    assertNull(handler.afterHandle(ctx));
    assertNull(body.optJSONArray("messages"));
  }

  /**
   * When oBTIKVIESStatus = "P" (pending), injectViesMessage must not inject a message.
   */
  @Test
  void testAfterHandlePutNoViesMessageWhenStatusIsPending() throws Exception {
    JSONObject savedRecord = new JSONObject();
    savedRecord.put("id", "REC_ID");
    savedRecord.put("oBTIKTaxIDKey", "2");
    savedRecord.put("oBTIKVIESStatus", "P");
    JSONArray data = new JSONArray();
    data.put(savedRecord);
    JSONObject response = new JSONObject();
    response.put("data", data);
    JSONObject body = new JSONObject();
    body.put("response", response);

    NeoResponse prevResult = mock(NeoResponse.class);
    when(prevResult.getBody()).thenReturn(body);
    when(ctx.getHttpMethod()).thenReturn("PUT");
    when(ctx.getPreviousResult()).thenReturn(prevResult);

    assertNull(handler.afterHandle(ctx));
    assertNull(body.optJSONArray("messages"));
  }

  /**
   * When oBTIKVIESStatus is absent (null), injectViesMessage must not inject a message.
   */
  @Test
  void testAfterHandlePutNoViesMessageWhenStatusIsNull() throws Exception {
    JSONObject savedRecord = new JSONObject();
    savedRecord.put("id", "REC_ID");
    savedRecord.put("oBTIKTaxIDKey", "2");
    JSONArray data = new JSONArray();
    data.put(savedRecord);
    JSONObject response = new JSONObject();
    response.put("data", data);
    JSONObject body = new JSONObject();
    body.put("response", response);

    NeoResponse prevResult = mock(NeoResponse.class);
    when(prevResult.getBody()).thenReturn(body);
    when(ctx.getHttpMethod()).thenReturn("PUT");
    when(ctx.getPreviousResult()).thenReturn(prevResult);

    assertNull(handler.afterHandle(ctx));
    assertNull(body.optJSONArray("messages"));
  }

  // ── afterHandle() — injectViesMessage: message injection paths ───────────

  /**
   * Builds a POST response body containing a record with the given tax key and VIES status.
   */
  private static JSONObject buildViesBody(String taxIdKey, String viesStatus) throws Exception {
    JSONObject savedRecord = new JSONObject();
    savedRecord.put("id", "REC_ID");
    if (taxIdKey != null) {
      savedRecord.put("oBTIKTaxIDKey", taxIdKey);
    }
    if (viesStatus != null) {
      savedRecord.put("oBTIKVIESStatus", viesStatus);
    }
    JSONArray data = new JSONArray();
    data.put(savedRecord);
    JSONObject response = new JSONObject();
    response.put("data", data);
    JSONObject body = new JSONObject();
    body.put("response", response);
    return body;
  }

  /**
   * When PUT body has oBTIKTaxIDKey = "2" and oBTIKVIESStatus = "V",
   * injectViesMessage must inject a "success" message.
   */
  @Test
  void testAfterHandlePutInjectsViesSuccessMessageForValidStatus() throws Exception {
    JSONObject body = buildViesBody("2", "V");

    NeoResponse prevResult = mock(NeoResponse.class);
    when(prevResult.getBody()).thenReturn(body);
    when(ctx.getHttpMethod()).thenReturn("PUT");
    when(ctx.getPreviousResult()).thenReturn(prevResult);

    try (MockedStatic<OBMessageUtils> mMsg = mockStatic(OBMessageUtils.class)) {
      mMsg.when(() -> OBMessageUtils.messageBD(anyString())).thenReturn("msg");

      NeoResponse result = handler.afterHandle(ctx);

      assertNotNull(result);
      JSONArray messages = body.optJSONArray("messages");
      assertNotNull(messages);
      assertEquals(1, messages.length());
      assertEquals("success", messages.getJSONObject(0).getString("type"));
    }
  }

  /**
   * When PUT body has oBTIKTaxIDKey = "2" and oBTIKVIESStatus = "I",
   * injectViesMessage must inject a "warning" message.
   */
  @Test
  void testAfterHandlePutInjectsViesWarningMessageForInvalidStatus() throws Exception {
    JSONObject body = buildViesBody("2", "I");

    NeoResponse prevResult = mock(NeoResponse.class);
    when(prevResult.getBody()).thenReturn(body);
    when(ctx.getHttpMethod()).thenReturn("PUT");
    when(ctx.getPreviousResult()).thenReturn(prevResult);

    try (MockedStatic<OBMessageUtils> mMsg = mockStatic(OBMessageUtils.class)) {
      mMsg.when(() -> OBMessageUtils.messageBD(anyString())).thenReturn("msg");

      NeoResponse result = handler.afterHandle(ctx);

      assertNotNull(result);
      JSONArray messages = body.optJSONArray("messages");
      assertNotNull(messages);
      assertEquals(1, messages.length());
      assertEquals("warning", messages.getJSONObject(0).getString("type"));
    }
  }

  /**
   * On POST with oBTIKTaxIDKey = "2" and oBTIKVIESStatus = "V", afterHandle must
   * run both the searchKey lookup (returning blank) and injectViesMessage,
   * returning a non-null response with a "success" message.
   */
  @Test
  void testAfterHandlePostInjectsViesSuccessMessageWhenIdentifierIsBlank() throws Exception {
    JSONObject body = buildViesBody("2", "V");

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

    try (MockedStatic<OBDal> mDal = mockStatic(OBDal.class);
         MockedStatic<OBMessageUtils> mMsg = mockStatic(OBMessageUtils.class)) {
      OBDal obDalMock = mock(OBDal.class);
      when(obDalMock.getConnection()).thenReturn(connMock);
      mDal.when(OBDal::getInstance).thenReturn(obDalMock);
      mMsg.when(() -> OBMessageUtils.messageBD(anyString())).thenReturn("msg");

      NeoResponse result = handler.afterHandle(ctx);

      assertNotNull(result);
      JSONArray messages = body.optJSONArray("messages");
      assertNotNull(messages);
      assertEquals("success", messages.getJSONObject(0).getString("type"));
    }
  }

  /**
   * On POST with oBTIKTaxIDKey != "2", no message is injected and afterHandle returns
   * null when the identifier lookup is also blank.
   */
  @Test
  void testAfterHandlePostNoViesMessageWhenTaxKeyNotNOI() throws Exception {
    JSONObject body = buildViesBody("1", "V");

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
      assertNull(body.optJSONArray("messages"));
    }
  }

  /**
   * On PATCH with oBTIKTaxIDKey = "2" and oBTIKVIESStatus = "I", afterHandle
   * must inject a "warning" message (PATCH is now treated as a write method).
   */
  @Test
  void testAfterHandlePatchInjectsViesWarningMessage() throws Exception {
    JSONObject body = buildViesBody("2", "I");

    NeoResponse prevResult = mock(NeoResponse.class);
    when(prevResult.getBody()).thenReturn(body);
    when(ctx.getHttpMethod()).thenReturn("PATCH");
    when(ctx.getPreviousResult()).thenReturn(prevResult);

    try (MockedStatic<OBMessageUtils> mMsg = mockStatic(OBMessageUtils.class)) {
      mMsg.when(() -> OBMessageUtils.messageBD(anyString())).thenReturn("msg");

      NeoResponse result = handler.afterHandle(ctx);

      assertNotNull(result);
      assertEquals("warning", body.getJSONArray("messages").getJSONObject(0).getString("type"));
    }
  }
}
