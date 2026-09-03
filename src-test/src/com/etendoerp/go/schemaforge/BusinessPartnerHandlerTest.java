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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.openbravo.erpCommon.utility.OBMessageUtils;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;

import java.util.HashMap;
import java.util.Map;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.erpCommon.utility.OBCurrencyUtils;
import org.openbravo.model.common.enterprise.Organization;

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

  // ── handle() — POST: org currency injection (ETP-4649) ───────────────────────

  /**
   * A new Business Partner with no currency breaks purchase invoice confirmation later on. When
   * the POST body omits {@code bPCurrencyID}, the handler must resolve and inject the
   * organization's currency.
   */
  @Test
  void testHandlePostInjectsOrgCurrencyWhenMissing() throws Exception {
    JSONObject body = new JSONObject();
    body.put("name", "Empresa Test");
    when(ctx.getHttpMethod()).thenReturn("POST");
    when(ctx.getRequestBody()).thenReturn(body);

    OBContext obContext = mock(OBContext.class);
    Organization org = mock(Organization.class);
    when(org.getId()).thenReturn("ORG1");
    when(obContext.getCurrentOrganization()).thenReturn(org);
    when(ctx.getObContext()).thenReturn(obContext);

    try (MockedStatic<OBContext> obCtxMock = mockStatic(OBContext.class);
        MockedStatic<OBCurrencyUtils> curMock = mockStatic(OBCurrencyUtils.class)) {
      curMock.when(() -> OBCurrencyUtils.getOrgCurrency("ORG1")).thenReturn("CUR1");

      handler.handle(ctx);

      assertEquals("CUR1", body.getString("bPCurrencyID"));
    }
  }

  /**
   * A POST that already carries {@code bPCurrencyID} must be left untouched — the org-currency
   * resolver is never invoked, so an explicit caller value is never overwritten.
   */
  @Test
  void testHandlePostKeepsExplicitCurrency() throws Exception {
    JSONObject body = new JSONObject();
    body.put("name", "Empresa Test");
    body.put("bPCurrencyID", "EXISTING");
    when(ctx.getHttpMethod()).thenReturn("POST");
    when(ctx.getRequestBody()).thenReturn(body);

    try (MockedStatic<OBCurrencyUtils> curMock = mockStatic(OBCurrencyUtils.class)) {
      handler.handle(ctx);

      assertEquals("EXISTING", body.getString("bPCurrencyID"));
      curMock.verifyNoInteractions();
    }
  }

  /**
   * When the org currency cannot be resolved (e.g. the organization has none configured), the
   * body is left without {@code bPCurrencyID} and no exception propagates.
   */
  @Test
  void testHandlePostLeavesCurrencyUnsetWhenUnresolved() throws Exception {
    JSONObject body = new JSONObject();
    body.put("name", "Empresa Test");
    when(ctx.getHttpMethod()).thenReturn("POST");
    when(ctx.getRequestBody()).thenReturn(body);

    OBContext obContext = mock(OBContext.class);
    Organization org = mock(Organization.class);
    when(org.getId()).thenReturn("ORG1");
    when(obContext.getCurrentOrganization()).thenReturn(org);
    when(ctx.getObContext()).thenReturn(obContext);

    try (MockedStatic<OBContext> obCtxMock = mockStatic(OBContext.class);
        MockedStatic<OBCurrencyUtils> curMock = mockStatic(OBCurrencyUtils.class)) {
      curMock.when(() -> OBCurrencyUtils.getOrgCurrency("ORG1")).thenReturn(null);

      handler.handle(ctx);

      assertFalse(body.has("bPCurrencyID"));
    }
  }

  /**
   * When the NEO context has no {@code OBContext} at all (defensive guard), currency injection
   * is skipped without throwing.
   */
  @Test
  void testHandlePostSkipsCurrencyInjectionWhenObContextIsNull() throws Exception {
    JSONObject body = new JSONObject();
    body.put("name", "Empresa Test");
    when(ctx.getHttpMethod()).thenReturn("POST");
    when(ctx.getRequestBody()).thenReturn(body);
    when(ctx.getObContext()).thenReturn(null);

    try (MockedStatic<OBCurrencyUtils> curMock = mockStatic(OBCurrencyUtils.class)) {
      handler.handle(ctx);

      assertFalse(body.has("bPCurrencyID"));
      curMock.verifyNoInteractions();
    }
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

  // ── afterHandle() — ETP-4565: vendor/customer posting-account backfill on PATCH/PUT ──

  /**
   * Reproduces ETP-4565: Classic's {@code c_bpartner_trg} only auto-creates
   * {@code C_BP_Vendor_Acct} on {@code TG_OP='INSERT'}, so flipping {@code isVendor} to Y on an
   * already-persisted BP via PATCH never gets its posting-account row (confirmed live on a real
   * tenant). When the PATCH body touches the {@code vendor} field, {@code afterHandle()} must run
   * the backfill INSERT bound to the record id, and must NOT touch the customer-side statement.
   */
  @Test
  void testAfterHandlePatchWithVendorFieldBackfillsVendorAcctRow() throws Exception {
    JSONObject body = buildResponseBody();
    NeoResponse prevResult = mock(NeoResponse.class);
    when(prevResult.getBody()).thenReturn(body);
    when(ctx.getHttpMethod()).thenReturn("PATCH");
    when(ctx.getPreviousResult()).thenReturn(prevResult);
    when(ctx.getRecordId()).thenReturn("BP_ID_1");
    JSONObject requestBody = new JSONObject();
    requestBody.put("vendor", true);
    when(ctx.getRequestBody()).thenReturn(requestBody);

    Connection connMock = mock(Connection.class);
    PreparedStatement psVendor = mock(PreparedStatement.class);
    when(connMock.prepareStatement(argThat(s -> s != null && s.contains("c_bp_vendor_acct"))))
        .thenReturn(psVendor);

    try (MockedStatic<OBDal> mDal = mockStatic(OBDal.class)) {
      OBDal obDalMock = mock(OBDal.class);
      when(obDalMock.getConnection()).thenReturn(connMock);
      mDal.when(OBDal::getInstance).thenReturn(obDalMock);

      handler.afterHandle(ctx);

      verify(psVendor).setString(1, "BP_ID_1");
      verify(psVendor).executeUpdate();
      verify(connMock, never())
          .prepareStatement(argThat(s -> s != null && s.contains("c_bp_customer_acct")));
    }
  }

  /**
   * Symmetric case: PATCH body touches {@code customer} — {@code afterHandle()} must run the
   * customer-side backfill INSERT and must NOT touch the vendor-side statement.
   */
  @Test
  void testAfterHandlePatchWithCustomerFieldBackfillsCustomerAcctRow() throws Exception {
    JSONObject body = buildResponseBody();
    NeoResponse prevResult = mock(NeoResponse.class);
    when(prevResult.getBody()).thenReturn(body);
    when(ctx.getHttpMethod()).thenReturn("PUT");
    when(ctx.getPreviousResult()).thenReturn(prevResult);
    when(ctx.getRecordId()).thenReturn("BP_ID_2");
    JSONObject requestBody = new JSONObject();
    requestBody.put("customer", true);
    when(ctx.getRequestBody()).thenReturn(requestBody);

    Connection connMock = mock(Connection.class);
    PreparedStatement psCustomer = mock(PreparedStatement.class);
    when(connMock.prepareStatement(argThat(s -> s != null && s.contains("c_bp_customer_acct"))))
        .thenReturn(psCustomer);

    try (MockedStatic<OBDal> mDal = mockStatic(OBDal.class)) {
      OBDal obDalMock = mock(OBDal.class);
      when(obDalMock.getConnection()).thenReturn(connMock);
      mDal.when(OBDal::getInstance).thenReturn(obDalMock);

      handler.afterHandle(ctx);

      verify(psCustomer).setString(1, "BP_ID_2");
      verify(psCustomer).executeUpdate();
      verify(connMock, never())
          .prepareStatement(argThat(s -> s != null && s.contains("c_bp_vendor_acct")));
    }
  }

  /**
   * When the PATCH body touches neither {@code vendor} nor {@code customer}, the handler must
   * skip the backfill entirely — no DB access at all — so ordinary field updates (e.g. address,
   * email) never pay for the extra lookup. Deliberately does NOT mock {@link OBDal}: if the guard
   * were broken, the real static call would blow up this unit test instead of silently no-op'ing.
   */
  @Test
  void testAfterHandlePatchWithoutVendorOrCustomerFieldsSkipsBackfill() throws Exception {
    JSONObject body = buildResponseBody();
    NeoResponse prevResult = mock(NeoResponse.class);
    when(prevResult.getBody()).thenReturn(body);
    when(ctx.getHttpMethod()).thenReturn("PATCH");
    when(ctx.getPreviousResult()).thenReturn(prevResult);
    when(ctx.getRecordId()).thenReturn("BP_ID_3");
    JSONObject requestBody = new JSONObject();
    requestBody.put("etgoEmail", "a@b.com");
    when(ctx.getRequestBody()).thenReturn(requestBody);

    handler.afterHandle(ctx);
    // No assertion beyond "did not throw" — reaching this line without an exception proves
    // OBDal.getInstance() was never invoked in this unmocked-static context.
  }

  /**
   * Reproduces the PR #804 review BLOCKER: {@code c_bpartner_trg} (the native Classic trigger,
   * {@code src-db/database/model/triggers/C_BPARTNER_TRG.xml} lines 59-75) resolves
   * {@code C_BP_Vendor_Acct}/{@code C_BP_Customer_Acct} posting accounts EXCLUSIVELY from the BP's
   * own {@code C_BP_Group_Acct} row (joined on the BP's {@code C_BP_Group_ID}) — never from the
   * generic, client-wide {@code C_AcctSchema_Default} table (that table only feeds the trigger's
   * separate, group-independent Employee branch). A BP group can carry account overrides that
   * diverge from the schema default (see {@code OnboardingAccountingWiringService
   * .overrideAcreedorGroupAccounts()} — e.g. the "Acreedor" group overrides {@code v_liability_acct}
   * to {@code 41000000}); sourcing from the schema default instead of the group silently wires the
   * WRONG account for any BP in such a group. The backfill SQL must therefore join
   * {@code c_bp_group_acct} keyed by {@code c_bp_group_id}, and must NOT reference
   * {@code c_acctschema_default} at all.
   */
  @Test
  void testAfterHandlePatchVendorBackfillSourcesAccountFromBpGroupAcctNotSchemaDefault() throws Exception {
    JSONObject body = buildResponseBody();
    NeoResponse prevResult = mock(NeoResponse.class);
    when(prevResult.getBody()).thenReturn(body);
    when(ctx.getHttpMethod()).thenReturn("PATCH");
    when(ctx.getPreviousResult()).thenReturn(prevResult);
    when(ctx.getRecordId()).thenReturn("BP_ID_5");
    JSONObject requestBody = new JSONObject();
    requestBody.put("vendor", true);
    when(ctx.getRequestBody()).thenReturn(requestBody);

    Connection connMock = mock(Connection.class);
    PreparedStatement psVendor = mock(PreparedStatement.class);
    ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
    when(connMock.prepareStatement(sqlCaptor.capture())).thenReturn(psVendor);

    try (MockedStatic<OBDal> mDal = mockStatic(OBDal.class)) {
      OBDal obDalMock = mock(OBDal.class);
      when(obDalMock.getConnection()).thenReturn(connMock);
      mDal.when(OBDal::getInstance).thenReturn(obDalMock);

      handler.afterHandle(ctx);
    }

    String sql = sqlCaptor.getValue().toLowerCase();
    assertTrue(sql.contains("c_bp_group_acct"),
        "vendor backfill SQL must source posting accounts from c_bp_group_acct (the BP's own"
            + " group), matching c_bpartner_trg — was:\n" + sql);
    assertTrue(sql.contains("c_bp_group_id"),
        "vendor backfill SQL must key the c_bp_group_acct lookup by the BP's own c_bp_group_id"
            + " — was:\n" + sql);
    assertFalse(sql.contains("c_acctschema_default"),
        "vendor backfill SQL must NOT source posting accounts from the generic client-wide"
            + " c_acctschema_default — that silently ignores per-group account overrides"
            + " (e.g. the Acreedor group's v_liability_acct override) — was:\n" + sql);
  }

  /**
   * Symmetric case for the customer-side backfill statement.
   */
  @Test
  void testAfterHandlePatchCustomerBackfillSourcesAccountFromBpGroupAcctNotSchemaDefault() throws Exception {
    JSONObject body = buildResponseBody();
    NeoResponse prevResult = mock(NeoResponse.class);
    when(prevResult.getBody()).thenReturn(body);
    when(ctx.getHttpMethod()).thenReturn("PUT");
    when(ctx.getPreviousResult()).thenReturn(prevResult);
    when(ctx.getRecordId()).thenReturn("BP_ID_6");
    JSONObject requestBody = new JSONObject();
    requestBody.put("customer", true);
    when(ctx.getRequestBody()).thenReturn(requestBody);

    Connection connMock = mock(Connection.class);
    PreparedStatement psCustomer = mock(PreparedStatement.class);
    ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
    when(connMock.prepareStatement(sqlCaptor.capture())).thenReturn(psCustomer);

    try (MockedStatic<OBDal> mDal = mockStatic(OBDal.class)) {
      OBDal obDalMock = mock(OBDal.class);
      when(obDalMock.getConnection()).thenReturn(connMock);
      mDal.when(OBDal::getInstance).thenReturn(obDalMock);

      handler.afterHandle(ctx);
    }

    String sql = sqlCaptor.getValue().toLowerCase();
    assertTrue(sql.contains("c_bp_group_acct"),
        "customer backfill SQL must source posting accounts from c_bp_group_acct (the BP's own"
            + " group), matching c_bpartner_trg — was:\n" + sql);
    assertTrue(sql.contains("c_bp_group_id"),
        "customer backfill SQL must key the c_bp_group_acct lookup by the BP's own c_bp_group_id"
            + " — was:\n" + sql);
    assertFalse(sql.contains("c_acctschema_default"),
        "customer backfill SQL must NOT source posting accounts from the generic client-wide"
            + " c_acctschema_default — that silently ignores per-group account overrides"
            + " — was:\n" + sql);
  }

  /**
   * S1 follow-up: a PATCH that explicitly sets {@code vendor: false} (e.g. un-flagging a BP as a
   * vendor) must NOT run the backfill lookup — only a flip TO {@code true} ever needs a new
   * posting-account row. {@link OBDal} IS mocked here (unlike the sibling "no vendor/customer
   * key at all" test) so the assertion can positively prove no statement was ever prepared,
   * rather than relying on the surrounding try/catch to swallow an unmocked static call either
   * way — that would pass identically whether or not the guard actually exists.
   */
  @Test
  void testAfterHandlePatchWithVendorFieldExplicitlyFalseSkipsBackfill() throws Exception {
    JSONObject body = buildResponseBody();
    NeoResponse prevResult = mock(NeoResponse.class);
    when(prevResult.getBody()).thenReturn(body);
    when(ctx.getHttpMethod()).thenReturn("PATCH");
    when(ctx.getPreviousResult()).thenReturn(prevResult);
    when(ctx.getRecordId()).thenReturn("BP_ID_7");
    JSONObject requestBody = new JSONObject();
    requestBody.put("vendor", false);
    when(ctx.getRequestBody()).thenReturn(requestBody);

    Connection connMock = mock(Connection.class);
    try (MockedStatic<OBDal> mDal = mockStatic(OBDal.class)) {
      OBDal obDalMock = mock(OBDal.class);
      when(obDalMock.getConnection()).thenReturn(connMock);
      mDal.when(OBDal::getInstance).thenReturn(obDalMock);

      handler.afterHandle(ctx);

      verify(connMock, never()).prepareStatement(anyString());
    }
  }

  /**
   * A failure inside the backfill (e.g. a transient DB error) must be logged and swallowed, never
   * propagated out of {@code afterHandle()} — the surrounding searchKey/VIES logic on the same
   * request must not be sacrificed because of this best-effort backfill.
   */
  @Test
  void testAfterHandlePatchVendorBackfillFailureIsSwallowed() throws Exception {
    JSONObject body = buildResponseBody();
    NeoResponse prevResult = mock(NeoResponse.class);
    when(prevResult.getBody()).thenReturn(body);
    when(ctx.getHttpMethod()).thenReturn("PATCH");
    when(ctx.getPreviousResult()).thenReturn(prevResult);
    when(ctx.getRecordId()).thenReturn("BP_ID_4");
    JSONObject requestBody = new JSONObject();
    requestBody.put("vendor", true);
    when(ctx.getRequestBody()).thenReturn(requestBody);

    try (MockedStatic<OBDal> mDal = mockStatic(OBDal.class)) {
      OBDal obDalMock = mock(OBDal.class);
      when(obDalMock.getConnection()).thenThrow(new RuntimeException("connection pool exhausted"));
      mDal.when(OBDal::getInstance).thenReturn(obDalMock);

      // Must not throw.
      handler.afterHandle(ctx);
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

  // ── afterHandle() GET — child data for the CSV export (ETP-4997) ─────────────

  /** A list response whose rows carry the given ids. */
  private static JSONObject buildListBody(String... ids) throws Exception {
    JSONArray data = new JSONArray();
    for (String id : ids) {
      data.put(new JSONObject().put("id", id).put("name", "BP " + id));
    }
    return new JSONObject().put("response", new JSONObject().put("data", data));
  }

  /**
   * Wires a GET list context asking for child data, plus a mock connection whose two child
   * queries answer from {@code locationRows} / {@code contactRows} (keyed by business partner).
   */
  private Connection stubChildQueries(JSONObject body,
      Map<String, String[]> locationRows, Map<String, String[]> contactRows) throws Exception {
    when(ctx.getHttpMethod()).thenReturn("GET");
    when(ctx.getRecordId()).thenReturn(null);
    when(ctx.getQueryParams()).thenReturn(Map.of("includeChildData", "1"));
    when(ctx.getPreviousResult()).thenReturn(NeoResponse.ok(body));

    Connection connMock = mock(Connection.class);
    when(connMock.createArrayOf(anyString(), org.mockito.ArgumentMatchers.any())).thenReturn(mock(Array.class));
    when(connMock.prepareStatement(anyString())).thenAnswer(invocation -> {
      String sql = invocation.getArgument(0);
      boolean isLocation = sql.contains("c_bpartner_location");
      Map<String, String[]> source = isLocation ? locationRows : contactRows;
      // Read off the handler's own constants, never re-spelled here: a mock that declares its
      // own column names asserts the test's idea of the schema instead of the code's. That is
      // how `postcode` (the column C_Location does not have) passed this test while the real
      // export silently produced blank address columns — see BusinessPartnerHandlerDbTest, which
      // covers the half a mock structurally cannot: whether the names match the database.
      String[] cols = isLocation
          ? BusinessPartnerHandler.LOCATION_COLUMNS
          : BusinessPartnerHandler.CONTACT_COLUMNS;
      PreparedStatement ps = mock(PreparedStatement.class);
      ResultSet rs = mock(ResultSet.class);
      java.util.Iterator<Map.Entry<String, String[]>> it = source.entrySet().iterator();
      java.util.concurrent.atomic.AtomicReference<Map.Entry<String, String[]>> current =
          new java.util.concurrent.atomic.AtomicReference<>();
      when(rs.next()).thenAnswer(i -> {
        if (!it.hasNext()) {
          return false;
        }
        current.set(it.next());
        return true;
      });
      when(rs.getString(anyString())).thenAnswer(i -> {
        String column = i.getArgument(0);
        if ("c_bpartner_id".equals(column)) {
          return current.get().getKey();
        }
        for (int c = 0; c < cols.length; c++) {
          if (cols[c].equals(column)) {
            return current.get().getValue()[c];
          }
        }
        return null;
      });
      when(ps.executeQuery()).thenReturn(rs);
      return ps;
    });
    return connMock;
  }

  private NeoResponse runAfterHandleGet(Connection connMock) {
    try (MockedStatic<OBDal> mDal = mockStatic(OBDal.class)) {
      OBDal obDalMock = mock(OBDal.class);
      when(obDalMock.getConnection()).thenReturn(connMock);
      mDal.when(OBDal::getInstance).thenReturn(obDalMock);
      return handler.afterHandle(ctx);
    }
  }

  /**
   * The address and contact person a Contacts row does NOT carry are attached under
   * {@code etgoChildData}, keyed by the import targets the CSV export addresses by dotted path.
   */
  @Test
  void attachesPrimaryAddressAndContactToEachListRow() throws Exception {
    JSONObject body = buildListBody("BP1", "BP2");
    Map<String, String[]> locations = new HashMap<>();
    locations.put("BP1", new String[] { "Calle Mayor 12", "Madrid", "28013", "Spain", "Madrid" });
    locations.put("BP2", new String[] { "Av. Industria 45", "Barcelona", "08018", "Spain", "Barcelona" });
    Map<String, String[]> contacts = new HashMap<>();
    contacts.put("BP1", new String[] { "Lucía", "Fernández", "lucia@example.com", "610000101", "Compras" });
    contacts.put("BP2", new String[] { "Andrés", "Molina", "andres@example.com", "610000102", "Taller" });

    NeoResponse result = runAfterHandleGet(stubChildQueries(body, locations, contacts));

    assertNotNull(result);
    JSONObject row = result.getBody().getJSONObject("response").getJSONArray("data").getJSONObject(0);
    JSONObject child = row.getJSONObject("etgoChildData");
    assertEquals("Calle Mayor 12", child.getString("address"));
    assertEquals("Madrid", child.getString("city"));
    assertEquals("28013", child.getString("postal"));
    assertEquals("Spain", child.getString("country"));
    assertEquals("Madrid", child.getString("region"));
    assertEquals("lucia@example.com", child.getString("email"));
    assertEquals("Lucía", child.getString("firstName"));
    assertEquals("Fernández", child.getString("lastName"));
    assertEquals("610000101", child.getString("phone"));
    assertEquals("Compras", child.getString("position"));

    JSONObject second = result.getBody().getJSONObject("response").getJSONArray("data").getJSONObject(1);
    assertEquals("Barcelona", second.getJSONObject("etgoChildData").getString("city"));
  }

  /**
   * A partner with no address and no contact still gets the key, empty — the export must emit
   * its columns (the header set has to match the import template) with blank cells.
   */
  @Test
  void attachesAnEmptyChildObjectWhenAPartnerHasNoChildren() throws Exception {
    JSONObject body = buildListBody("BP1");
    NeoResponse result = runAfterHandleGet(stubChildQueries(body, new HashMap<>(), new HashMap<>()));

    assertNotNull(result);
    JSONObject row = result.getBody().getJSONObject("response").getJSONArray("data").getJSONObject(0);
    assertTrue(row.has("etgoChildData"));
    assertEquals(0, row.getJSONObject("etgoChildData").length());
  }

  /** Without the opt-in flag the normal grid must not pay for the two extra queries. */
  @Test
  void doesNotAttachChildDataWithoutTheFlag() throws Exception {
    JSONObject body = buildListBody("BP1");
    when(ctx.getHttpMethod()).thenReturn("GET");
    when(ctx.getRecordId()).thenReturn(null);
    when(ctx.getQueryParams()).thenReturn(Map.of());
    when(ctx.getPreviousResult()).thenReturn(NeoResponse.ok(body));

    Connection connMock = mock(Connection.class);
    NeoResponse result = runAfterHandleGet(connMock);

    assertNull(result);
    verify(connMock, never()).prepareStatement(anyString());
    assertFalse(body.getJSONObject("response").getJSONArray("data").getJSONObject(0).has("etgoChildData"));
  }

  /**
   * A failed enrichment must cost the user empty columns, never their export: the handler
   * declines and the default CRUD result stands.
   */
  @Test
  void declinesWhenAChildQueryFails() throws Exception {
    JSONObject body = buildListBody("BP1");
    when(ctx.getHttpMethod()).thenReturn("GET");
    when(ctx.getRecordId()).thenReturn(null);
    when(ctx.getQueryParams()).thenReturn(Map.of("includeChildData", "1"));
    when(ctx.getPreviousResult()).thenReturn(NeoResponse.ok(body));

    Connection connMock = mock(Connection.class);
    when(connMock.createArrayOf(anyString(), org.mockito.ArgumentMatchers.any())).thenReturn(mock(Array.class));
    when(connMock.prepareStatement(anyString())).thenThrow(new SQLException("boom"));

    assertNull(runAfterHandleGet(connMock));
  }

  /** The flag is a list-only concern; a single-record GET keeps the contact-email fallback. */
  @Test
  void ignoresTheFlagOnASingleRecordGet() throws Exception {
    when(ctx.getHttpMethod()).thenReturn("GET");
    when(ctx.getRecordId()).thenReturn("BP1");
    when(ctx.getQueryParams()).thenReturn(Map.of("includeChildData", "1"));
    when(ctx.getPreviousResult()).thenReturn(NeoResponse.ok(buildListBody("BP1")));

    Connection connMock = mock(Connection.class);
    ResultSet rs = mock(ResultSet.class);
    PreparedStatement ps = mock(PreparedStatement.class);
    when(rs.next()).thenReturn(false);
    when(ps.executeQuery()).thenReturn(rs);
    when(connMock.prepareStatement(anyString())).thenReturn(ps);

    // No child data attached; the email fallback found nothing, so the default result stands.
    NeoResponse result = runAfterHandleGet(connMock);
    assertNull(result);
  }
}
