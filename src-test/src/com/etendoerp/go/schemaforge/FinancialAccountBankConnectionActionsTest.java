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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
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
 * (disconnectBank, deleteBankConnection). The endpoint is stubbed: the tests read the exact
 * context and body it would receive.
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
    actions = new FinancialAccountBankConnectionActions(context -> {
      calls.add(context);
      if (endpointFailure != null) {
        throw endpointFailure;
      }
      return endpointAnswer;
    });
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
    @DisplayName("disconnectBank then deleteBankConnection, both mutating, no parameters")
    void declared() {
      Map<String, NeoActionContract> c = FinancialAccountBankConnectionActions.actionContracts();
      assertEquals(List.of("disconnectBank", "deleteBankConnection"), new ArrayList<>(c.keySet()));
      for (NeoActionContract contract : c.values()) {
        assertTrue(contract.isMutating());
        assertTrue(contract.getParams().isEmpty());
      }
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
  @DisplayName("wiring")
  class Wiring {

    @Test
    @DisplayName("FinancialAccountHandler declares both actions and routes them to its stub")
    void wired() throws Exception {
      FinancialAccountHandler handler = new FinancialAccountHandler();
      assertTrue(handler.actionContracts().keySet()
          .containsAll(List.of("disconnectBank", "deleteBankConnection")));
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
