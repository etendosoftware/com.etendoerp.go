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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.dal.core.OBContext;
import org.openbravo.model.financialmgmt.payment.FIN_FinancialAccount;

import com.etendoerp.go.schemaforge.util.NeoActionContract;

/**
 * Unit tests for the bank connection actions of {@code financial-account/account}
 * (disconnectBank, deleteBankConnection, and since ETP-6154 syncBank and bankSyncStatus). The
 * endpoint is stubbed: the tests read the exact context and body it would receive. Every action
 * is also gated on the role's access to the endpoint's report spec ({@code hasEndpointAccess}, a
 * spied seam granted by default here): POST access for the writes ({@code disconnectBank},
 * {@code deleteBankConnection}, {@code syncBank}), GET access for {@code bankSyncStatus}.
 *
 * @covers com.etendoerp.go.schemaforge.FinancialAccountBankConnectionActions
 * @covers com.etendoerp.go.schemaforge.FinancialAccountBankConnectionEndpoint
 */
// Test methods live in the @Nested inner classes below; S2187 only inspects the outer class.
@SuppressWarnings("java:S2187")
@DisplayName("financial account bank connection actions")
class FinancialAccountBankConnectionActionsTest {

  private static final String ACCOUNT = "ACC1";

  private MockedStatic<OBContext> contextMock;
  private MockedStatic<TenantOwnership> ownershipMock;
  private final List<NeoContext> calls = new ArrayList<>();
  private NeoResponse endpointAnswer;
  private RuntimeException endpointFailure;
  private FinancialAccountBankConnectionActions actions;

  @BeforeEach
  void setUp() throws Exception {
    contextMock = mockStatic(OBContext.class);
    ownershipMock = mockStatic(TenantOwnership.class);
    FIN_FinancialAccount account = mock(FIN_FinancialAccount.class);
    when(account.getId()).thenReturn(ACCOUNT);
    ownershipMock.when(() -> TenantOwnership.loadOwned(FIN_FinancialAccount.class, ACCOUNT))
        .thenReturn(account);
    endpointAnswer = NeoResponse.ok(new JSONObject().put("response",
        new JSONObject().put("data", new JSONObject().put("disconnected", true)
            .put("permanent", false).put("reconnectable", true))));
    actions = spy(new FinancialAccountBankConnectionActions(context -> {
      calls.add(context);
      if (endpointFailure != null) {
        throw endpointFailure;
      }
      return endpointAnswer;
    }));
    // The real seam reads the role's report-spec access from the database.
    doReturn(true).when(actions).hasEndpointAccess(anyBoolean());
  }

  @AfterEach
  void tearDown() {
    ownershipMock.close();
    contextMock.close();
    Mockito.framework().clearInlineMocks();
  }

  private static NeoContext ctx(String action, String recordId, String method, boolean mcp) {
    return NeoContext.builder().specName("financial-account").entityName("account")
        .httpMethod(method).recordId(recordId).requestBody(new JSONObject())
        .endpointType(NeoEndpointType.ACTION).fieldName(action).mcpOrigin(mcp).build();
  }

  private NeoContext onlyCall() {
    assertEquals(1, calls.size(), "the endpoint must be called exactly once");
    return calls.get(0);
  }

  @Nested
  @DisplayName("contracts")
  class Contracts {

    @Test
    @DisplayName("four actions in order, no parameters; only bankSyncStatus is a read")
    void declared() {
      Map<String, NeoActionContract> c = FinancialAccountBankConnectionActions.actionContracts();
      assertEquals(List.of("disconnectBank", "deleteBankConnection", "syncBank", "bankSyncStatus"),
          new ArrayList<>(c.keySet()));
      for (NeoActionContract contract : c.values()) {
        assertTrue(contract.getParams().isEmpty());
      }
      assertTrue(c.get("disconnectBank").isMutating());
      assertTrue(c.get("deleteBankConnection").isMutating());
      assertTrue(c.get("syncBank").isMutating());
      assertFalse(c.get("bankSyncStatus").isMutating());
    }

    @Test
    @DisplayName("syncBank tells the agent to poll bankSyncStatus on IN_PROGRESS and to retry the lock code")
    void syncDescribesInProgressAndRetry() {
      Map<String, NeoActionContract> c = FinancialAccountBankConnectionActions.actionContracts();
      String d = c.get("syncBank").getDescription();
      assertTrue(d.contains("IN_PROGRESS"), d);
      assertTrue(d.contains("bankSyncStatus"), d);
      assertTrue(d.contains("BANK_SYNC_LOCK_UNAVAILABLE"), d);
      assertTrue(d.contains("BANK_SYNC_DUPLICATE_CHECK_FAILED"), d);
      assertTrue(d.contains("calling syncBank again is safe"), d);
      String s = c.get("bankSyncStatus").getDescription();
      assertTrue(s.contains("inProgress"), s);
      assertTrue(s.contains("lastResult"), s);
      assertNotNull(c.get("syncBank").getIdDescription());
      assertNotNull(c.get("bankSyncStatus").getIdDescription());
    }

    @Test
    @DisplayName("the permanent deletion warns the agent to ask the user: irreversible")
    void deleteWarns() {
      String d = FinancialAccountBankConnectionActions.actionContracts()
          .get("deleteBankConnection").getDescription();
      assertTrue(d.contains("ask the user"), d);
      assertTrue(d.contains("irreversible") || d.contains("cannot be undone"), d);
    }

    @Test
    @DisplayName("disconnectBank states that repeating is safe only while soft-disconnected, then 404")
    void disconnectDescribesRepeatSemantics() {
      String d = FinancialAccountBankConnectionActions.actionContracts()
          .get("disconnectBank").getDescription();
      assertTrue(d.contains("still soft-disconnected"), d);
      assertTrue(d.contains("answers 404"), d);
    }

    @Test
    @DisplayName("deleteBankConnection names the UI option with its accented label")
    void deleteUsesTheAccentedUiLabel() {
      String d = FinancialAccountBankConnectionActions.actionContracts()
          .get("deleteBankConnection").getDescription();
      assertTrue(d.contains("Borrar conexi\u00f3n bancaria"), d);
    }

    @Test
    @DisplayName("an undeclared parameter is answered 422 by the shared validator")
    void undeclaredParameter() throws Exception {
      NeoResponse r = NeoActionContract.validate(
          FinancialAccountBankConnectionActions.actionContracts(), "disconnectBank",
          new JSONObject().put("permanentDeletion", true));
      assertNotNull(r);
      assertEquals(422, r.getHttpStatus());
      assertTrue(r.getBody().getJSONObject("error").getJSONArray("unknownParameters").toString()
          .contains("permanentDeletion"));
    }

    @Test
    @DisplayName("no parameters at all passes validation")
    void noParameters() {
      assertNull(NeoActionContract.validate(
          FinancialAccountBankConnectionActions.actionContracts(), "deleteBankConnection",
          new JSONObject()));
    }
  }

  @Nested
  @DisplayName("routing")
  class Routing {

    @Test
    @DisplayName("anything but POST is 405 and never reaches the endpoint")
    void notPost() {
      assertEquals(405, actions.handle(ctx("disconnectBank", ACCOUNT, "GET", true)).getHttpStatus());
      assertTrue(calls.isEmpty());
    }

    @Test
    @DisplayName("a missing or foreign account is 404 'Financial account not found'")
    void notFound() {
      NeoResponse r = actions.handle(ctx("disconnectBank", "OTHER", "POST", true));
      assertEquals(404, r.getHttpStatus());
      assertTrue(r.getBody().toString().contains("Financial account not found"));
      assertTrue(calls.isEmpty());
    }

    @Test
    @DisplayName("a non-ACTION context or a foreign action returns null")
    void notOurs() {
      NeoContext crud = NeoContext.builder().specName("financial-account").entityName("account")
          .httpMethod("POST").recordId(ACCOUNT).requestBody(new JSONObject())
          .endpointType(NeoEndpointType.CRUD).fieldName("disconnectBank").build();
      assertNull(actions.handle(crud));
      assertNull(actions.handle(ctx("transferFunds", ACCOUNT, "POST", true)));
      assertTrue(calls.isEmpty());
    }

    @Test
    @DisplayName("mcpOrigin is propagated to the endpoint")
    void mcpOrigin() {
      actions.handle(ctx("disconnectBank", ACCOUNT, "POST", true));
      assertTrue(onlyCall().isMcpOrigin());
      calls.clear();
      actions.handle(ctx("disconnectBank", ACCOUNT, "POST", false));
      assertFalse(onlyCall().isMcpOrigin());
    }
  }

  @Nested
  @DisplayName("dispatch")
  class Dispatch {

    @Test
    @DisplayName("disconnectBank sends the SPA's soft body to ?action=disconnect")
    void soft() throws Exception {
      actions.handle(ctx("disconnectBank", ACCOUNT, "POST", true));
      NeoContext c = onlyCall();
      assertEquals("financial-account-bank-connection", c.getSpecName());
      assertEquals("financial-account-bank-connection", c.getEntityName());
      assertEquals("POST", c.getHttpMethod());
      assertEquals("disconnect", c.getQueryParams().get("action"));
      assertEquals(ACCOUNT, c.getRequestBody().getString("financialAccountId"));
      assertFalse(c.getRequestBody().getBoolean("permanentDeletion"));
    }

    @Test
    @DisplayName("deleteBankConnection sends permanentDeletion=true")
    void permanent() throws Exception {
      actions.handle(ctx("deleteBankConnection", ACCOUNT, "POST", true));
      assertTrue(onlyCall().getRequestBody().getBoolean("permanentDeletion"));
    }

    @Test
    @DisplayName("endpoint errors 404, 400 and 500 propagate unchanged")
    void errorsPropagate() {
      for (int status : new int[] {404, 400, 500}) {
        endpointAnswer = NeoResponse.error(status, "boom " + status);
        NeoResponse r = actions.handle(ctx("disconnectBank", ACCOUNT, "POST", true));
        assertEquals(status, r.getHttpStatus());
        assertTrue(r.getBody().toString().contains("boom " + status));
      }
    }

    @Test
    @DisplayName("a 2xx is re-wrapped as response.data with the connection state")
    void successEnvelope() throws Exception {
      NeoResponse r = actions.handle(ctx("deleteBankConnection", ACCOUNT, "POST", true));
      assertEquals(200, r.getHttpStatus());
      JSONObject data = r.getBody().getJSONObject("response").getJSONObject("data");
      assertTrue(data.getBoolean("disconnected"));
      assertFalse(data.getBoolean("permanent"));
      assertTrue(data.getBoolean("reconnectable"));
    }

    @Test
    @DisplayName("a 2xx body without response.data is wrapped whole")
    void bareBody() throws Exception {
      endpointAnswer = NeoResponse.ok(new JSONObject().put("disconnected", true));
      NeoResponse r = actions.handle(ctx("disconnectBank", ACCOUNT, "POST", true));
      assertTrue(r.getBody().getJSONObject("response").getJSONObject("data")
          .getBoolean("disconnected"));
    }

    @Test
    @DisplayName("a null endpoint answer is passed through")
    void nullPassThrough() {
      endpointAnswer = null;
      assertNull(actions.handle(ctx("disconnectBank", ACCOUNT, "POST", true)));
    }

    @Test
    @DisplayName("an exception from the endpoint is a 500")
    void thrown() {
      endpointFailure = new IllegalStateException("down");
      assertEquals(500, actions.handle(ctx("disconnectBank", ACCOUNT, "POST", true))
          .getHttpStatus());
    }
  }

  @Nested
  @DisplayName("sync (ETP-6154)")
  class Sync {

    @Test
    @DisplayName("syncBank sends the SPA's body to ?action=sync and passes {status, message} through")
    void syncBank() throws Exception {
      endpointAnswer = NeoResponse.ok(new JSONObject().put("response", new JSONObject()
          .put("data", new JSONObject().put("status", "IN_PROGRESS")
              .put("message", "Fetching statements, please wait\u2026")
              .put("lastSyncDate", JSONObject.NULL))));

      NeoResponse r = actions.handle(ctx("syncBank", ACCOUNT, "POST", true));

      NeoContext c = onlyCall();
      assertEquals("financial-account-bank-connection", c.getSpecName());
      assertEquals("POST", c.getHttpMethod());
      assertEquals("sync", c.getQueryParams().get("action"));
      assertEquals(ACCOUNT, c.getRequestBody().getString("financialAccountId"));
      assertFalse(c.getRequestBody().has("permanentDeletion"));
      assertTrue(c.isMcpOrigin());
      assertEquals(200, r.getHttpStatus());
      JSONObject data = r.getBody().getJSONObject("response").getJSONObject("data");
      assertEquals("IN_PROGRESS", data.getString("status"));
      assertEquals("Fetching statements, please wait\u2026", data.getString("message"));
    }

    @Test
    @DisplayName("syncBank passes the endpoint's code through with its status and message")
    void syncBankPassesTheCodeThrough() throws Exception {
      endpointAnswer = NeoResponse.ok(new JSONObject().put("response", new JSONObject()
          .put("data", new JSONObject().put("status", "WARNING")
              .put("code", "BANK_SYNC_DUPLICATE_CHECK_FAILED")
              .put("message", "Some bank movements could not be checked")
              .put("lastSyncDate", JSONObject.NULL))));

      NeoResponse r = actions.handle(ctx("syncBank", ACCOUNT, "POST", true));

      JSONObject data = r.getBody().getJSONObject("response").getJSONObject("data");
      assertEquals("WARNING", data.getString("status"));
      assertEquals("BANK_SYNC_DUPLICATE_CHECK_FAILED", data.getString("code"));
      assertEquals("Some bank movements could not be checked", data.getString("message"));
    }

    @Test
    @DisplayName("syncBank passes an endpoint error through unchanged")
    void syncBankError() {
      endpointAnswer = NeoResponse.error(400, "boom sync");
      NeoResponse r = actions.handle(ctx("syncBank", ACCOUNT, "POST", false));
      assertEquals(400, r.getHttpStatus());
      assertTrue(r.getBody().toString().contains("boom sync"));
      assertFalse(onlyCall().isMcpOrigin());
    }

    @Test
    @DisplayName("bankSyncStatus asks ?action=syncStatus for this one account and unwraps its entry")
    void syncStatus() throws Exception {
      JSONObject entry = new JSONObject().put("inProgress", true)
          .put("lastSyncDate", "2026-11-01T08:00:00Z")
          .put("lastResult", new JSONObject().put("status", "OK"));
      endpointAnswer = NeoResponse.ok(new JSONObject().put("response", new JSONObject()
          .put("data", new JSONObject().put("accounts", new JSONObject().put(ACCOUNT, entry)))));

      NeoResponse r = actions.handle(ctx("bankSyncStatus", ACCOUNT, "POST", true));

      NeoContext c = onlyCall();
      assertEquals("syncStatus", c.getQueryParams().get("action"));
      assertEquals(1, c.getRequestBody().getJSONArray("financialAccountIds").length());
      assertEquals(ACCOUNT, c.getRequestBody().getJSONArray("financialAccountIds").getString(0));
      assertEquals(200, r.getHttpStatus());
      JSONObject data = r.getBody().getJSONObject("response").getJSONObject("data");
      assertTrue(data.getBoolean("inProgress"));
      assertEquals("2026-11-01T08:00:00Z", data.getString("lastSyncDate"));
      assertEquals("OK", data.getJSONObject("lastResult").getString("status"));
      assertFalse(data.has("accounts"));
    }

    @Test
    @DisplayName("bankSyncStatus is 404 when the endpoint omitted the account or sent no accounts")
    void syncStatusOmitted() throws Exception {
      endpointAnswer = NeoResponse.ok(new JSONObject().put("response", new JSONObject()
          .put("data", new JSONObject().put("accounts", new JSONObject()))));
      NeoResponse omitted = actions.handle(ctx("bankSyncStatus", ACCOUNT, "POST", true));
      endpointAnswer = NeoResponse.ok(new JSONObject().put("response", new JSONObject()
          .put("data", new JSONObject())));
      NeoResponse noAccounts = actions.handle(ctx("bankSyncStatus", ACCOUNT, "POST", true));

      assertEquals(404, omitted.getHttpStatus());
      assertTrue(omitted.getBody().toString().contains("Financial account not found"));
      assertEquals(404, noAccounts.getHttpStatus());
    }

    @Test
    @DisplayName("bankSyncStatus passes an endpoint error through unchanged")
    void syncStatusError() {
      endpointAnswer = NeoResponse.error(500, "boom status");
      NeoResponse r = actions.handle(ctx("bankSyncStatus", ACCOUNT, "POST", true));
      assertEquals(500, r.getHttpStatus());
      assertTrue(r.getBody().toString().contains("boom status"));
    }

    @Test
    @DisplayName("syncBank checks POST access to the endpoint spec; without it, 403 and no call")
    void syncBankNeedsPostAccess() {
      doReturn(false).when(actions).hasEndpointAccess(true);

      NeoResponse r = actions.handle(ctx("syncBank", ACCOUNT, "POST", true));

      assertEquals(403, r.getHttpStatus());
      assertTrue(r.getBody().toString()
          .contains(FinancialAccountBankConnectionActions.MSG_ACCESS_DENIED));
      verify(actions).hasEndpointAccess(true);
      assertTrue(calls.isEmpty());
    }

    @Test
    @DisplayName("bankSyncStatus checks GET access to the endpoint spec; without it, 403 and no call")
    void bankSyncStatusNeedsGetAccess() {
      doReturn(false).when(actions).hasEndpointAccess(false);

      NeoResponse r = actions.handle(ctx("bankSyncStatus", ACCOUNT, "POST", true));

      assertEquals(403, r.getHttpStatus());
      verify(actions).hasEndpointAccess(false);
      verify(actions, never()).hasEndpointAccess(true);
      assertTrue(calls.isEmpty());
    }

    @Test
    @DisplayName("granted access lets both sync actions through, each checked with its own mode")
    void grantedAccessPasses() {
      actions.handle(ctx("syncBank", ACCOUNT, "POST", true));
      actions.handle(ctx("bankSyncStatus", ACCOUNT, "POST", true));

      verify(actions).hasEndpointAccess(true);
      verify(actions).hasEndpointAccess(false);
      assertEquals(2, calls.size());
    }

    @Test
    @DisplayName("the disconnect actions are writes: each checks POST access to the endpoint spec")
    void disconnectIsGatedAsAWrite() {
      actions.handle(ctx("disconnectBank", ACCOUNT, "POST", true));
      actions.handle(ctx("deleteBankConnection", ACCOUNT, "POST", true));

      verify(actions, org.mockito.Mockito.times(2)).hasEndpointAccess(true);
      verify(actions, never()).hasEndpointAccess(false);
      assertEquals(2, calls.size());
    }

    @Test
    @DisplayName("without POST access both disconnect actions are 403 and never reach the endpoint")
    void disconnectWithoutAccessIsForbidden() {
      doReturn(false).when(actions).hasEndpointAccess(true);

      NeoResponse soft = actions.handle(ctx("disconnectBank", ACCOUNT, "POST", true));
      NeoResponse permanent = actions.handle(ctx("deleteBankConnection", ACCOUNT, "POST", true));

      for (NeoResponse r : new NeoResponse[] {soft, permanent}) {
        assertEquals(403, r.getHttpStatus());
        assertTrue(r.getBody().toString()
            .contains(FinancialAccountBankConnectionActions.MSG_ACCESS_DENIED));
      }
      assertTrue(calls.isEmpty());
    }

    @Test
    @DisplayName("sync actions on a missing or foreign account are 404 and never reach the endpoint")
    void notFound() {
      assertEquals(404, actions.handle(ctx("syncBank", "OTHER", "POST", true)).getHttpStatus());
      assertEquals(404,
          actions.handle(ctx("bankSyncStatus", "OTHER", "POST", true)).getHttpStatus());
      assertTrue(calls.isEmpty());
    }
  }

  @Nested
  @DisplayName("wiring")
  class Wiring {

    @Test
    @DisplayName("FinancialAccountHandler declares the four actions and routes them to its stub")
    void wired() throws Exception {
      FinancialAccountHandler handler = new FinancialAccountHandler();
      assertTrue(handler.actionContracts().keySet()
          .containsAll(List.of("disconnectBank", "deleteBankConnection", "syncBank",
              "bankSyncStatus")));
      FinancialAccountBankConnectionActions stub = mock(FinancialAccountBankConnectionActions.class);
      NeoResponse answer = NeoResponse.ok(new JSONObject());
      NeoContext context = ctx("disconnectBank", ACCOUNT, "POST", true);
      when(stub.handle(context)).thenReturn(answer);
      handler.bankConnectionActions = stub;
      assertEquals(answer, handler.handle(context));
    }

    @Test
    @DisplayName("the endpoint SPEC is the spec the handler serves and the one in ETGO_SF_SPEC")
    void specAndHandler() throws Exception {
      assertEquals("financial-account-bank-connection",
          FinancialAccountBankConnectionEndpoint.SPEC);
      assertTrue(source("FinancialAccountBankConnectionHandler.java").contains("\"disconnect\""));
      java.nio.file.Path rel = java.nio.file.Path.of(
          "src-db/database/sourcedata/ETGO_SF_SPEC.xml");
      java.nio.file.Path fromRoot = java.nio.file.Path.of("modules/com.etendoerp.go").resolve(rel);
      String xml = java.nio.file.Files.readString(
          java.nio.file.Files.exists(fromRoot) ? fromRoot : rel);
      assertTrue(xml.contains("<![CDATA[" + FinancialAccountBankConnectionEndpoint.SPEC + "]]>"));
    }
  }

  /** A source of this package, from the Etendo root or from the module directory. */
  private static String source(String file) throws java.io.IOException {
    String rel = "src/com/etendoerp/go/schemaforge/" + file;
    java.nio.file.Path fromRoot = java.nio.file.Path.of("modules/com.etendoerp.go", rel);
    return java.nio.file.Files.readString(java.nio.file.Files.exists(fromRoot) ? fromRoot
        : java.nio.file.Path.of(rel));
  }
}
