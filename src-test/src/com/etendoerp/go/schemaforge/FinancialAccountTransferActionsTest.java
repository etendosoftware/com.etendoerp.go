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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.dal.core.OBContext;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.currency.Currency;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.financialmgmt.gl.GLItem;
import org.openbravo.model.financialmgmt.payment.FIN_FinancialAccount;

import com.etendoerp.go.schemaforge.util.NeoActionContract;

/**
 * ETP-5558 — funds transfers between financial accounts as declared actions of
 * {@code financial-account/account}, mirroring the Movements tab's Transferir
 * ({@code FundsTransferModal}). The movements endpoint and the rate lookup are stubbed: the tests
 * read the exact body the endpoint would receive.
 */
// Test methods live in the @Nested inner classes below; S2187 only inspects the outer class.
@SuppressWarnings("java:S2187")
@DisplayName("ETP-5558 — financial account transfer actions")
class FinancialAccountTransferActionsTest {

  private static final String SOURCE = "SRC";
  private static final String DEST = "DST";
  private static final String USD_DEST = "USD1";
  private static final String GL = "GL1";
  private static final LocalDate TODAY = LocalDate.of(2026, 10, 1);

  private MockedStatic<OBContext> contextMock;
  private MockedStatic<TenantOwnership> ownershipMock;
  private FIN_FinancialAccount source;
  private FIN_FinancialAccount dest;
  private FIN_FinancialAccount usdDest;
  private final List<NeoContext> calls = new ArrayList<>();
  private NeoResponse endpointAnswer;
  private Double systemRate;
  private final List<FIN_FinancialAccount> candidates = new ArrayList<>();
  private final Set<String> outsideTree = new java.util.HashSet<>();
  private FinancialAccountTransferActions actions;

  @BeforeEach
  void setUp() throws Exception {
    contextMock = mockStatic(OBContext.class);
    ownershipMock = mockStatic(TenantOwnership.class);
    source = account(SOURCE, "Banco Paridad", "102", "EUR", true);
    dest = account(DEST, "Caja", "102", "EUR", true);
    usdDest = account(USD_DEST, "Dollars", "100", "USD", true);
    GLItem gl = mock(GLItem.class);
    ownershipMock.when(() -> TenantOwnership.loadOwned(GLItem.class, GL)).thenReturn(gl);
    ownershipMock.when(() -> TenantOwnership.isVisibleToCurrentTenant(any(BaseOBObject.class)))
        .thenReturn(true);
    endpointAnswer = NeoResponse.createdWithData(new JSONObject().put("transferred", true));
    systemRate = 1.1;
    actions = new FinancialAccountTransferActions(context -> {
      calls.add(context);
      return endpointAnswer;
    }, (from, to, date) -> {
      assertEquals(TODAY, date, "the rate is today's, as the modal prefills it");
      return systemRate;
    }, () -> TODAY) {
      @Override
      List<FIN_FinancialAccount> candidates(FIN_FinancialAccount src) {
        return candidates;
      }

      @Override
      boolean inSourceTree(FIN_FinancialAccount src, FIN_FinancialAccount candidate) {
        return !outsideTree.contains(candidate.getId());
      }
    };
  }

  @AfterEach
  void tearDown() {
    ownershipMock.close();
    contextMock.close();
    Mockito.framework().clearInlineMocks();
  }

  private FIN_FinancialAccount account(String id, String name, String currencyId, String iso,
      boolean active) {
    FIN_FinancialAccount a = mock(FIN_FinancialAccount.class);
    when(a.getId()).thenReturn(id);
    when(a.getName()).thenReturn(name);
    when(a.isActive()).thenReturn(active);
    Currency c = mock(Currency.class);
    when(c.getId()).thenReturn(currencyId);
    when(c.getISOCode()).thenReturn(iso);
    when(a.getCurrency()).thenReturn(c);
    when(a.getClient()).thenReturn(mock(Client.class));
    when(a.getOrganization()).thenReturn(mock(Organization.class));
    ownershipMock.when(() -> TenantOwnership.loadOwned(FIN_FinancialAccount.class, id))
        .thenReturn(a);
    return a;
  }

  private static NeoContext ctx(String action, JSONObject params, String method) {
    return NeoContext.builder().specName("financial-account").entityName("account")
        .httpMethod(method).recordId(SOURCE).requestBody(params)
        .endpointType(NeoEndpointType.ACTION).fieldName(action).mcpOrigin(true).build();
  }

  private static NeoContext transferCtx(JSONObject params) {
    return ctx("transferFunds", params, "POST");
  }

  private static JSONObject ten() throws Exception {
    return new JSONObject().put("destinationAccountId", DEST).put("amount", 10)
        .put("glItemId", GL).put("description", "MCP-PARITY transfer");
  }

  private NeoContext onlyCall() {
    assertEquals(1, calls.size(), "the endpoint must be called exactly once");
    return calls.get(0);
  }

  private static String field(NeoResponse r) {
    return r.getBody().optJSONObject("error").optString("field", null);
  }

  private static JSONObject data(NeoResponse r) {
    return r.getBody().optJSONObject("response").optJSONObject("data");
  }

  @Nested
  @DisplayName("contracts")
  class Contracts {

    @Test
    @DisplayName("transferDestinations and transferFunds; the transfer needs destination,"
        + " amount and G/L item and takes no date")
    void contracts() throws Exception {
      Map<String, NeoActionContract> c = FinancialAccountTransferActions.actionContracts();
      assertEquals(List.of("transferDestinations", "transferFunds"), new ArrayList<>(c.keySet()));
      assertFalse(c.get("transferDestinations").isMutating());
      NeoActionContract transfer = c.get("transferFunds");
      assertTrue(transfer.isMutating());
      JSONArray required = transfer.toJson().getJSONObject("parameters").getJSONArray("required");
      assertEquals("[\"destinationAccountId\",\"amount\",\"glItemId\"]", required.toString());
      assertTrue(transfer.getParams().stream().noneMatch(p -> p.getName().contains("ate")
          && !"conversionRate".equals(p.getName())), "the modal books today: no date parameter");
      assertTrue(transfer.getDescription().contains("transfer back"));
    }

    @Test
    @DisplayName("FinancialAccountHandler declares and routes them next to the movement actions")
    void wired() throws Exception {
      FinancialAccountHandler handler = new FinancialAccountHandler();
      assertTrue(handler.actionContracts().keySet().containsAll(
          List.of("createMovement", "transferDestinations", "transferFunds")));
      FinancialAccountTransferActions stub = mock(FinancialAccountTransferActions.class);
      NeoResponse answer = NeoResponse.ok(new JSONObject());
      when(stub.handle(any())).thenReturn(answer);
      handler.transferActions = stub;
      assertEquals(answer, handler.handle(transferCtx(ten())));
    }
  }

  @Nested
  @DisplayName("transferDestinations")
  class Destinations {

    @Test
    @DisplayName("the modal's list: active, not the source, readable, in the tree; rate for"
        + " another currency")
    void list() throws Exception {
      FIN_FinancialAccount archived = account("ARC", "Old", "102", "EUR", false);
      FIN_FinancialAccount foreign = account("FOR", "Foreign", "102", "EUR", true);
      FIN_FinancialAccount otherOrg = account("ORG", "Other org", "102", "EUR", true);
      ownershipMock.when(() -> TenantOwnership.isVisibleToCurrentTenant(foreign))
          .thenReturn(false);
      outsideTree.add("ORG");
      candidates.addAll(List.of(source, dest, usdDest, archived, foreign, otherOrg));

      NeoResponse r = actions.handle(ctx("transferDestinations", null, "GET"));
      JSONArray items = data(r).getJSONArray("items");
      assertEquals(2, items.length(), items.toString());
      assertEquals(DEST, items.getJSONObject(0).getString("id"));
      assertTrue(items.getJSONObject(0).getBoolean("sameCurrency"));
      assertFalse(items.getJSONObject(0).has("conversionRate"));
      assertEquals(USD_DEST, items.getJSONObject(1).getString("id"));
      assertEquals(1.1, items.getJSONObject(1).getDouble("conversionRate"), 1e-9);
      assertTrue(calls.isEmpty(), "a read never reaches the movements endpoint");
    }

    @Test
    @DisplayName("no system rate shows as null")
    void noRate() throws Exception {
      systemRate = null;
      candidates.add(usdDest);
      JSONObject item = data(actions.handle(ctx("transferDestinations", null, "GET")))
          .getJSONArray("items").getJSONObject(0);
      assertTrue(item.isNull("conversionRate"));
    }
  }

  @Nested
  @DisplayName("transferFunds")
  class Transfer {

    @Test
    @DisplayName("same currency: the modal's body, dated today, no rate, no fee")
    void body() throws Exception {
      NeoResponse r = actions.handle(transferCtx(ten()));
      NeoContext call = onlyCall();
      assertEquals("POST", call.getHttpMethod());
      assertEquals("transfer", call.getQueryParams().get("action"));
      assertTrue(call.isMcpOrigin());
      JSONObject body = call.getRequestBody();
      assertEquals(SOURCE, body.getString("sourceAccountId"));
      assertEquals(DEST, body.getString("destinationAccountId"));
      assertEquals("10", body.getString("amount"));
      assertEquals("2026-10-01", body.getString("transferDate"));
      assertEquals(GL, body.getString("glItemId"));
      assertEquals("MCP-PARITY transfer", body.getString("description"));
      assertFalse(body.getBoolean("bankFee"));
      assertFalse(body.has("conversionRate"));
      assertFalse(body.has("bankFeeFrom"));
      assertEquals(201, r.getHttpStatus());
      JSONObject out = data(r);
      assertEquals(0, BigDecimal.TEN.compareTo(new BigDecimal(out.getString("amountReceived"))));
      assertEquals("2026-10-01", out.getString("date"));
    }

    @Test
    @DisplayName("another currency: today's system rate is sent, as the modal prefills it")
    void systemRate() throws Exception {
      NeoResponse r = actions.handle(transferCtx(ten().put("destinationAccountId", USD_DEST)));
      assertEquals("1.1", onlyCall().getRequestBody().getString("conversionRate"));
      assertEquals(0, new BigDecimal("11.0").compareTo(
          new BigDecimal(data(r).getString("amountReceived"))));
    }

    @Test
    @DisplayName("another currency: the caller's rate wins; none at all is 422")
    void rateRules() throws Exception {
      actions.handle(transferCtx(ten().put("destinationAccountId", USD_DEST)
          .put("conversionRate", 1.25)));
      assertEquals("1.25", onlyCall().getRequestBody().getString("conversionRate"));
      calls.clear();
      systemRate = null;
      NeoResponse r = actions.handle(transferCtx(ten().put("destinationAccountId", USD_DEST)));
      assertEquals(422, r.getHttpStatus());
      assertEquals("conversionRate", field(r));
      assertEquals("conversionRate", field(actions.handle(transferCtx(
          ten().put("destinationAccountId", USD_DEST).put("conversionRate", 0)))));
      assertTrue(calls.isEmpty());
    }

    @Test
    @DisplayName("same currency ignores a rate sent anyway")
    void sameCurrencyIgnoresRate() throws Exception {
      actions.handle(transferCtx(ten().put("conversionRate", 2)));
      assertFalse(onlyCall().getRequestBody().has("conversionRate"));
    }

    @Test
    @DisplayName("bank fees travel with bankFee true; a zero pair is no fee")
    void fees() throws Exception {
      actions.handle(transferCtx(ten().put("bankFeeFrom", 1.5)));
      JSONObject body = onlyCall().getRequestBody();
      assertTrue(body.getBoolean("bankFee"));
      assertEquals("1.5", body.getString("bankFeeFrom"));
      assertEquals("0", body.getString("bankFeeTo"));
      calls.clear();
      actions.handle(transferCtx(ten().put("bankFeeTo", 2)));
      assertTrue(onlyCall().getRequestBody().getBoolean("bankFee"), "a destination fee alone");
      calls.clear();
      actions.handle(transferCtx(ten().put("bankFeeFrom", 0).put("bankFeeTo", 0)));
      assertFalse(onlyCall().getRequestBody().getBoolean("bankFee"));
    }

    @Test
    @DisplayName("what the modal refuses is refused, nothing runs")
    void gates() throws Exception {
      Object[][] cases = {
          { ten().put("destinationAccountId", SOURCE), 422, "destinationAccountId" },
          { ten().put("amount", 0), 422, "amount" },
          { ten().put("amount", "x"), 422, "amount" },
          { ten().put("glItemId", ""), 422, "glItemId" },
          { ten().put("glItemId", "FOREIGN"), 422, "glItemId" },
          { ten().put("description", "x".repeat(256)), 422, "description" },
          { ten().put("bankFeeTo", -1), 422, "bankFeeTo" },
          { ten().put("destinationAccountId", "NOPE"), 404, null },
      };
      for (Object[] c : cases) {
        NeoResponse r = actions.handle(transferCtx((JSONObject) c[0]));
        assertEquals(c[1], r.getHttpStatus(), c[0].toString());
        if (c[2] != null) {
          assertEquals(c[2], field(r), c[0].toString());
        }
      }
      NeoResponse blank = actions.handle(transferCtx(ten().put("glItemId", " ")));
      assertTrue(blank.getBody().getJSONObject("error").getString("message")
          .startsWith("glItemId is required"), "a missing G/L item says so, not 'not found'");
      account("ARC", "Old", "102", "EUR", false);
      assertEquals(409, actions.handle(transferCtx(ten().put("destinationAccountId", "ARC")))
          .getHttpStatus());
      assertTrue(calls.isEmpty());
    }

    @Test
    @DisplayName("255 characters pass; GET is 405; an unreadable source is 404")
    void edges() throws Exception {
      actions.handle(transferCtx(ten().put("description", "x".repeat(255))));
      assertEquals(1, calls.size());
      assertEquals(405, actions.handle(ctx("transferFunds", ten(), "GET")).getHttpStatus());
      ownershipMock.when(() -> TenantOwnership.loadOwned(FIN_FinancialAccount.class, SOURCE))
          .thenReturn(null);
      assertEquals(404, actions.handle(transferCtx(ten())).getHttpStatus());
      assertEquals(1, calls.size());
    }

    @Test
    @DisplayName("an endpoint refusal is returned as it is")
    void passThrough() throws Exception {
      endpointAnswer = NeoResponse.error(400,
          "Source and destination accounts must belong to the same organization tree");
      NeoResponse r = actions.handle(transferCtx(ten()));
      assertEquals(400, r.getHttpStatus());
      assertFalse(r.getBody().has("response"));
    }

    @Test
    @DisplayName("not ours: null")
    void notOurs() throws Exception {
      assertNull(actions.handle(ctx("createMovement", ten(), "POST")));
      assertNull(actions.handle(NeoContext.builder().specName("financial-account")
          .entityName("account").httpMethod("POST").recordId(SOURCE)
          .endpointType(NeoEndpointType.CRUD).fieldName("transferFunds").build()));
      assertTrue(calls.isEmpty());
    }
  }
}
