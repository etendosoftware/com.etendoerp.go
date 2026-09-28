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
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.openbravo.dal.core.OBContext;
import org.openbravo.model.financialmgmt.payment.FIN_BankStatement;
import org.openbravo.model.financialmgmt.payment.FIN_FinancialAccount;

/**
 * Unit tests for {@link BankStatementWriteGuardHandler} (ETP-5471): the generic
 * {@code financial-account} bank-statement entities must not create a statement, add a line to
 * one, or delete one on a bank-connected (PSD2) account, and must leave every other request to the
 * default CRUD.
 *
 * <p>{@link TenantOwnership} and {@link OBContext} are mocked statically; the connection predicate
 * ({@link BankStatementsSupport#isBankConnected}) and the endpoint-type check
 * ({@link NeoEndpointTypes#isCrud}: a null or CRUD endpoint type counts as CRUD) run for real, so the
 * status codes below are compared by VALUE.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("BankStatementWriteGuardHandler (ETP-5471)")
class BankStatementWriteGuardHandlerTest {

  private static final String SPEC = "financial-account";
  private static final String ENTITY_STATEMENTS = "importedBankStatements";
  private static final String ENTITY_LINES = "bankStatementLines";
  private static final String ACCOUNT_ID = "ACC-1";
  private static final String STATEMENT_ID = "STMT-1";
  private static final String PROP_ACCOUNT = "account";
  private static final String PROP_BANK_STATEMENT = "bankStatement";
  private static final String PARENT_ID = "parentId";

  /** PSD2 "connected" code, by value (see BankStatementsHandlerTest for the rationale). */
  private static final String PSD2_CONNECTED = "CO";
  /** The column's default: not bank-connected. */
  private static final String PSD2_DISCONNECTED = "DC";

  /** Literal copies — the frontend's BACKEND_ERROR_MAP matches these by EXACT text. */
  private static final String MSG_NOT_CREATABLE =
      "This account is synchronized with the bank; statements cannot be created or imported manually.";
  private static final String MSG_NOT_DELETABLE =
      "Statements from a bank-connected account cannot be deleted.";

  @Mock
  private FIN_FinancialAccount account;
  @Mock
  private FIN_BankStatement statement;

  private BankStatementWriteGuardHandler handler;
  private MockedStatic<TenantOwnership> tenantOwnershipMock;
  private MockedStatic<OBContext> obContextMock;

  @BeforeEach
  void setUp() {
    handler = new BankStatementWriteGuardHandler();
    tenantOwnershipMock = mockStatic(TenantOwnership.class);
    obContextMock = mockStatic(OBContext.class);
    tenantOwnershipMock.when(() -> TenantOwnership.loadOwned(FIN_FinancialAccount.class, ACCOUNT_ID))
        .thenReturn(account);
    tenantOwnershipMock.when(() -> TenantOwnership.loadOwned(FIN_BankStatement.class, STATEMENT_ID))
        .thenReturn(statement);
    when(statement.getAccount()).thenReturn(account);
  }

  @AfterEach
  void tearDown() {
    tenantOwnershipMock.close();
    obContextMock.close();
    Mockito.framework().clearInlineMocks();
  }

  // ── fixtures ───────────────────────────────────────────────────────────

  private static NeoContext ctx(String entity, String method, NeoEndpointType type, String recordId,
      JSONObject body) {
    return NeoContext.builder()
        .specName(SPEC)
        .entityName(entity)
        .httpMethod(method)
        .recordId(recordId)
        .requestBody(body)
        .endpointType(type)
        .build();
  }

  private static NeoContext crud(String entity, String method, String recordId, JSONObject body) {
    return ctx(entity, method, NeoEndpointType.CRUD, recordId, body);
  }

  private static JSONObject bodyWith(String property, Object value) throws Exception {
    return new JSONObject().put(property, value);
  }

  private void connectionStatus(String status) {
    when(account.getPSD2ConnectionStatus()).thenReturn(status);
  }

  private static void assertRefused(NeoResponse response, String message) throws Exception {
    assertNotNull(response, "the request must be refused, not handed to the default CRUD");
    assertEquals(409, response.getHttpStatus());
    assertEquals(message, response.getBody().getJSONObject("error").getString("message"));
  }

  // ── importedBankStatements POST ────────────────────────────────────────

  @Test
  @DisplayName("statement POST on a connected account → 409 not-creatable")
  void testStatementPostOnConnectedAccountIsRefused() throws Exception {
    connectionStatus(PSD2_CONNECTED);
    NeoResponse r = handler.handle(
        crud(ENTITY_STATEMENTS, "POST", null, bodyWith(PROP_ACCOUNT, ACCOUNT_ID)));
    assertRefused(r, MSG_NOT_CREATABLE);
  }

  @Test
  @DisplayName("statement POST with no endpoint type (batch / MCP callers) is treated as CRUD")
  void testStatementPostWithNullEndpointTypeIsRefused() throws Exception {
    connectionStatus(PSD2_CONNECTED);
    NeoResponse r = handler.handle(
        ctx(ENTITY_STATEMENTS, "POST", null, null, bodyWith(PROP_ACCOUNT, ACCOUNT_ID)));
    assertRefused(r, MSG_NOT_CREATABLE);
  }

  @Test
  @DisplayName("statement POST carrying the account through parentId (REST) → 409")
  void testStatementPostWithParentIdOnConnectedAccountIsRefused() throws Exception {
    connectionStatus(PSD2_CONNECTED);
    NeoResponse r = handler.handle(
        crud(ENTITY_STATEMENTS, "POST", null, bodyWith(PARENT_ID, ACCOUNT_ID)));
    assertRefused(r, MSG_NOT_CREATABLE);
  }

  @Test
  @DisplayName("statement POST on a disconnected account → default CRUD")
  void testStatementPostOnDisconnectedAccountPasses() throws Exception {
    connectionStatus(PSD2_DISCONNECTED);
    assertNull(handler.handle(
        crud(ENTITY_STATEMENTS, "POST", null, bodyWith(PROP_ACCOUNT, ACCOUNT_ID))));
  }

  @Test
  @DisplayName("statement POST on an account with no connection status → default CRUD")
  void testStatementPostOnNullStatusPasses() throws Exception {
    connectionStatus(null);
    assertNull(handler.handle(
        crud(ENTITY_STATEMENTS, "POST", null, bodyWith(PROP_ACCOUNT, ACCOUNT_ID))));
  }

  @Test
  @DisplayName("statement POST whose body names no account → default CRUD")
  void testStatementPostWithoutAccountPasses() throws Exception {
    connectionStatus(PSD2_CONNECTED);
    assertNull(handler.handle(crud(ENTITY_STATEMENTS, "POST", null, new JSONObject())));
  }

  @Test
  @DisplayName("statement POST with a null body → default CRUD")
  void testStatementPostWithNullBodyPasses() throws Exception {
    connectionStatus(PSD2_CONNECTED);
    assertNull(handler.handle(crud(ENTITY_STATEMENTS, "POST", null, null)));
  }

  @Test
  @DisplayName("statement POST on an unknown / foreign account (loadOwned → null) → default CRUD")
  void testStatementPostOnUnknownAccountPasses() throws Exception {
    connectionStatus(PSD2_CONNECTED);
    assertNull(handler.handle(
        crud(ENTITY_STATEMENTS, "POST", null, bodyWith(PROP_ACCOUNT, "ACC-UNKNOWN"))));
  }

  // ── bankStatementLines POST ────────────────────────────────────────────

  @Test
  @DisplayName("line POST on a statement of a connected account → 409 not-creatable")
  void testLinePostOnConnectedAccountIsRefused() throws Exception {
    connectionStatus(PSD2_CONNECTED);
    NeoResponse r = handler.handle(
        crud(ENTITY_LINES, "POST", null, bodyWith(PROP_BANK_STATEMENT, STATEMENT_ID)));
    assertRefused(r, MSG_NOT_CREATABLE);
  }

  @Test
  @DisplayName("line POST with the statement as an {id} object → 409")
  void testLinePostWithStatementObjectOnConnectedAccountIsRefused() throws Exception {
    connectionStatus(PSD2_CONNECTED);
    JSONObject body = bodyWith(PROP_BANK_STATEMENT, new JSONObject().put("id", STATEMENT_ID));
    assertRefused(handler.handle(crud(ENTITY_LINES, "POST", null, body)), MSG_NOT_CREATABLE);
  }

  @Test
  @DisplayName("line POST on a statement of a disconnected account → default CRUD")
  void testLinePostOnDisconnectedAccountPasses() throws Exception {
    connectionStatus(PSD2_DISCONNECTED);
    assertNull(handler.handle(
        crud(ENTITY_LINES, "POST", null, bodyWith(PROP_BANK_STATEMENT, STATEMENT_ID))));
  }

  @Test
  @DisplayName("line POST on an unknown statement → default CRUD")
  void testLinePostOnUnknownStatementPasses() throws Exception {
    connectionStatus(PSD2_CONNECTED);
    assertNull(handler.handle(
        crud(ENTITY_LINES, "POST", null, bodyWith(PROP_BANK_STATEMENT, "STMT-UNKNOWN"))));
  }

  @Test
  @DisplayName("line POST on a statement with no account → default CRUD")
  void testLinePostOnStatementWithoutAccountPasses() throws Exception {
    when(statement.getAccount()).thenReturn(null);
    assertNull(handler.handle(
        crud(ENTITY_LINES, "POST", null, bodyWith(PROP_BANK_STATEMENT, STATEMENT_ID))));
  }

  @Test
  @DisplayName("line POST whose body names no statement → default CRUD")
  void testLinePostWithoutStatementPasses() throws Exception {
    connectionStatus(PSD2_CONNECTED);
    assertNull(handler.handle(crud(ENTITY_LINES, "POST", null, new JSONObject())));
  }

  // ── importedBankStatements DELETE ──────────────────────────────────────

  @Test
  @DisplayName("statement DELETE on a connected account → 409 with the ?action=delete text")
  void testStatementDeleteOnConnectedAccountIsRefused() throws Exception {
    connectionStatus(PSD2_CONNECTED);
    NeoResponse r = handler.handle(crud(ENTITY_STATEMENTS, "DELETE", STATEMENT_ID, null));
    assertRefused(r, MSG_NOT_DELETABLE);
  }

  @Test
  @DisplayName("statement DELETE on a disconnected account → default CRUD")
  void testStatementDeleteOnDisconnectedAccountPasses() throws Exception {
    connectionStatus(PSD2_DISCONNECTED);
    assertNull(handler.handle(crud(ENTITY_STATEMENTS, "DELETE", STATEMENT_ID, null)));
  }

  @Test
  @DisplayName("statement DELETE of an unknown record → default CRUD")
  void testStatementDeleteOfUnknownRecordPasses() throws Exception {
    connectionStatus(PSD2_CONNECTED);
    assertNull(handler.handle(crud(ENTITY_STATEMENTS, "DELETE", "STMT-UNKNOWN", null)));
  }

  @Test
  @DisplayName("line DELETE is not guarded (only statement deletes are)")
  void testLineDeleteOnConnectedAccountPasses() throws Exception {
    connectionStatus(PSD2_CONNECTED);
    assertNull(handler.handle(crud(ENTITY_LINES, "DELETE", STATEMENT_ID, null)));
  }

  // ── everything else is left to the default CRUD ────────────────────────

  @Test
  @DisplayName("PUT and PATCH on a connected account → default CRUD")
  void testEditsOnConnectedAccountPass() throws Exception {
    connectionStatus(PSD2_CONNECTED);
    JSONObject body = bodyWith(PROP_ACCOUNT, ACCOUNT_ID);
    assertNull(handler.handle(crud(ENTITY_STATEMENTS, "PUT", STATEMENT_ID, body)));
    assertNull(handler.handle(crud(ENTITY_STATEMENTS, "PATCH", STATEMENT_ID, body)));
    JSONObject lineBody = bodyWith(PROP_BANK_STATEMENT, STATEMENT_ID);
    assertNull(handler.handle(crud(ENTITY_LINES, "PUT", "LINE-1", lineBody)));
    assertNull(handler.handle(crud(ENTITY_LINES, "PATCH", "LINE-1", lineBody)));
  }

  @Test
  @DisplayName("GET on a connected account → default CRUD")
  void testGetOnConnectedAccountPasses() throws Exception {
    connectionStatus(PSD2_CONNECTED);
    assertNull(handler.handle(crud(ENTITY_STATEMENTS, "GET", STATEMENT_ID, null)));
    assertNull(handler.handle(crud(ENTITY_LINES, "GET", null, null)));
  }

  @Test
  @DisplayName("an ACTION sub-endpoint is skipped before entering admin mode")
  void testActionEndpointIsSkipped() throws Exception {
    connectionStatus(PSD2_CONNECTED);
    assertNull(handler.handle(ctx(ENTITY_STATEMENTS, "POST", NeoEndpointType.ACTION, null,
        bodyWith(PROP_ACCOUNT, ACCOUNT_ID))));
    assertNull(handler.handle(ctx(ENTITY_STATEMENTS, "DELETE", NeoEndpointType.ACTION,
        STATEMENT_ID, null)));
    obContextMock.verify(() -> OBContext.setAdminMode(true), never());
  }

  @Test
  @DisplayName("an unrelated entity → default CRUD")
  void testUnrelatedEntityPasses() throws Exception {
    connectionStatus(PSD2_CONNECTED);
    assertNull(handler.handle(crud("account", "POST", null, bodyWith(PROP_ACCOUNT, ACCOUNT_ID))));
    assertNull(handler.handle(crud("account", "DELETE", STATEMENT_ID, null)));
  }

  @Test
  @DisplayName("a CRUD request runs in admin mode and always restores it")
  void testCrudRequestRestoresAdminMode() throws Exception {
    connectionStatus(PSD2_CONNECTED);
    handler.handle(crud(ENTITY_STATEMENTS, "POST", null, bodyWith(PROP_ACCOUNT, ACCOUNT_ID)));
    obContextMock.verify(() -> OBContext.setAdminMode(true));
    obContextMock.verify(OBContext::restorePreviousMode);
  }

  // ── referencedId ───────────────────────────────────────────────────────

  @Test
  @DisplayName("referencedId: the property as a plain string id")
  void testReferencedIdReadsStringProperty() throws Exception {
    assertEquals(ACCOUNT_ID,
        BankStatementWriteGuardHandler.referencedId(bodyWith(PROP_ACCOUNT, ACCOUNT_ID), PROP_ACCOUNT));
  }

  @Test
  @DisplayName("referencedId: the property as an {id} object")
  void testReferencedIdReadsObjectProperty() throws Exception {
    JSONObject body = bodyWith(PROP_ACCOUNT, new JSONObject().put("id", ACCOUNT_ID));
    assertEquals(ACCOUNT_ID, BankStatementWriteGuardHandler.referencedId(body, PROP_ACCOUNT));
  }

  @Test
  @DisplayName("referencedId: falls back to parentId when the property is absent")
  void testReferencedIdFallsBackToParentId() throws Exception {
    assertEquals(ACCOUNT_ID,
        BankStatementWriteGuardHandler.referencedId(bodyWith(PARENT_ID, ACCOUNT_ID), PROP_ACCOUNT));
  }

  @Test
  @DisplayName("referencedId: falls back to parentId when the {id} object carries no id")
  void testReferencedIdFallsBackToParentIdOnEmptyObject() throws Exception {
    JSONObject body = bodyWith(PROP_ACCOUNT, new JSONObject()).put(PARENT_ID, ACCOUNT_ID);
    assertEquals(ACCOUNT_ID, BankStatementWriteGuardHandler.referencedId(body, PROP_ACCOUNT));
  }

  @Test
  @DisplayName("referencedId: the property wins over parentId")
  void testReferencedIdPropertyWinsOverParentId() throws Exception {
    JSONObject body = bodyWith(PROP_ACCOUNT, ACCOUNT_ID).put(PARENT_ID, "OTHER-ID");
    assertEquals(ACCOUNT_ID, BankStatementWriteGuardHandler.referencedId(body, PROP_ACCOUNT));
  }

  @Test
  @DisplayName("referencedId: blank property and blank parentId → null")
  void testReferencedIdBlankIsNull() throws Exception {
    JSONObject body = bodyWith(PROP_ACCOUNT, "  ").put(PARENT_ID, "");
    assertNull(BankStatementWriteGuardHandler.referencedId(body, PROP_ACCOUNT));
  }

  @Test
  @DisplayName("referencedId: an empty body → null")
  void testReferencedIdEmptyBodyIsNull() {
    assertNull(BankStatementWriteGuardHandler.referencedId(new JSONObject(), PROP_ACCOUNT));
  }

  @Test
  @DisplayName("referencedId: a null body → null")
  void testReferencedIdNullBodyIsNull() {
    assertNull(BankStatementWriteGuardHandler.referencedId(null, PROP_ACCOUNT));
  }
}
