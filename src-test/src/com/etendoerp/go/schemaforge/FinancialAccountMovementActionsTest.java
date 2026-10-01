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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.advpaymentmngt.process.FIN_TransactionProcess;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.businesspartner.BusinessPartner;
import org.openbravo.model.common.currency.Currency;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.financialmgmt.gl.GLItem;
import org.openbravo.model.financialmgmt.payment.FIN_FinaccTransaction;
import org.openbravo.model.financialmgmt.payment.FIN_FinancialAccount;
import org.openbravo.model.financialmgmt.payment.FIN_Payment;

import com.etendoerp.go.schemaforge.util.NeoActionContract;

/**
 * ETP-5558 — the manual movements of a financial account as declared actions of
 * {@code financial-account/account}. Through MCP an agent could not record a deposit: the SPA's
 * endpoint ({@code financial-account-transactions}) is a report spec the MCP refuses, and the
 * {@code transaction} entity refuses every write. In blind run {@code 20261001T1949-local-a00c} the
 * agent recorded a bank-statement line instead.
 *
 * <p>The SPA's endpoint is stubbed: every test reads the exact context and body it would
 * receive, which is what proves the business rules stay single-sourced.</p>
 */
// Test methods live in the @Nested inner classes below; S2187 only inspects the outer class.
@SuppressWarnings("java:S2187")
@DisplayName("ETP-5558 — financial account movement actions")
class FinancialAccountMovementActionsTest {

  private static final String ACCOUNT = "ACC1";
  private static final String OTHER_ACCOUNT = "ACC2";
  private static final String MOVEMENT = "MOV1";
  private static final String GL = "GL1";
  private static final String EUR = "102";

  private MockedStatic<OBContext> contextMock;
  private MockedStatic<TenantOwnership> ownershipMock;
  private MockedStatic<OBDal> dalMock;
  private OBDal dal;
  private FIN_FinancialAccount account;
  private final List<NeoContext> calls = new ArrayList<>();
  private NeoResponse endpointAnswer;
  private FinancialAccountMovementActions actions;

  @BeforeEach
  void setUp() throws Exception {
    contextMock = mockStatic(OBContext.class);
    ownershipMock = mockStatic(TenantOwnership.class);
    dalMock = mockStatic(OBDal.class);
    dal = mock(OBDal.class);
    dalMock.when(OBDal::getInstance).thenReturn(dal);
    account = accountWithId(ACCOUNT);
    owned(FIN_FinancialAccount.class, ACCOUNT, account);
    owned(GLItem.class, GL, glItem(GL));
    endpointAnswer = NeoResponse.createdWithData(new JSONObject().put("id", MOVEMENT));
    actions = new FinancialAccountMovementActions(context -> {
      calls.add(context);
      return endpointAnswer;
    });
  }

  @AfterEach
  void tearDown() {
    dalMock.close();
    ownershipMock.close();
    contextMock.close();
    Mockito.framework().clearInlineMocks();
  }

  // ── fixtures ────────────────────────────────────────────────────────────

  private static FIN_FinancialAccount accountWithId(String id) {
    FIN_FinancialAccount a = mock(FIN_FinancialAccount.class);
    when(a.getId()).thenReturn(id);
    Currency currency = mock(Currency.class);
    when(currency.getId()).thenReturn(EUR);
    when(a.getCurrency()).thenReturn(currency);
    return a;
  }

  private static GLItem glItem(String id) {
    GLItem gl = mock(GLItem.class);
    when(gl.getId()).thenReturn(id);
    return gl;
  }

  private <T extends org.openbravo.base.structure.BaseOBObject> void owned(Class<T> type,
      String id, T value) {
    ownershipMock.when(() -> TenantOwnership.loadOwned(type, id)).thenReturn(value);
  }

  private static Date day(String iso) {
    return Date.from(LocalDate.parse(iso).atStartOfDay(ZoneId.systemDefault()).toInstant());
  }

  /** A movement of {@code accountId}; registered as owned and as what OBDal re-reads. */
  private FIN_FinaccTransaction movement(String accountId, String trxType, boolean processed,
      String posted) {
    FIN_FinaccTransaction m = mock(FIN_FinaccTransaction.class);
    when(m.getId()).thenReturn(MOVEMENT);
    FIN_FinancialAccount owner = ACCOUNT.equals(accountId) ? account : accountWithId(accountId);
    when(m.getAccount()).thenReturn(owner);
    when(m.getTransactionType()).thenReturn(trxType);
    when(m.isProcessed()).thenReturn(processed);
    when(m.getPosted()).thenReturn(posted);
    when(m.getDepositAmount()).thenReturn("BPD".equals(trxType) ? new BigDecimal("40")
        : BigDecimal.ZERO);
    when(m.getPaymentAmount()).thenReturn("BPW".equals(trxType) ? new BigDecimal("40")
        : BigDecimal.ZERO);
    when(m.getTransactionDate()).thenReturn(day("2026-09-15"));
    when(m.getDateAcct()).thenReturn(day("2026-09-16"));
    when(m.getDescription()).thenReturn("old text");
    GLItem gl = glItem(GL);
    when(m.getGLItem()).thenReturn(gl);
    BusinessPartner bp = mock(BusinessPartner.class);
    when(bp.getId()).thenReturn("BP1");
    when(m.getBusinessPartner()).thenReturn(bp);
    Currency currency = mock(Currency.class);
    when(currency.getId()).thenReturn(EUR);
    when(m.getCurrency()).thenReturn(currency);
    when(m.getStatus()).thenReturn(processed ? "RPR" : "RPAE");
    owned(FIN_FinaccTransaction.class, MOVEMENT, m);
    when(dal.get(FIN_FinaccTransaction.class, MOVEMENT)).thenReturn(m);
    return m;
  }

  private static FIN_Payment payment(boolean receipt) {
    FIN_Payment p = mock(FIN_Payment.class);
    when(p.isReceipt()).thenReturn(receipt);
    return p;
  }

  private static NeoContext ctx(String action, JSONObject params) {
    return ctx(action, params, "POST");
  }

  private static NeoContext ctx(String action, JSONObject params, String method) {
    return NeoContext.builder().specName("financial-account").entityName("account")
        .httpMethod(method).recordId(ACCOUNT).requestBody(params)
        .endpointType(NeoEndpointType.ACTION).fieldName(action).mcpOrigin(true).build();
  }

  private static JSONObject deposit() throws Exception {
    return new JSONObject().put("trxType", "BPD").put("amount", 100).put("date", "2026-09-30")
        .put("glItemId", GL).put("description", "MCP-PARITY deposit");
  }

  private NeoContext onlyCall() {
    assertEquals(1, calls.size(), "the endpoint must be called exactly once");
    return calls.get(0);
  }

  private static String error(NeoResponse r) {
    return r.getBody().optJSONObject("error").optString("message");
  }

  private static String field(NeoResponse r) {
    return r.getBody().optJSONObject("error").optString("field", null);
  }

  private static JSONObject data(NeoResponse r) {
    return r.getBody().optJSONObject("response").optJSONObject("data");
  }

  // ── the contracts ─────────────────────────────────────────────────────

  @Nested
  @DisplayName("contracts")
  class Contracts {

    @Test
    @DisplayName("seven actions, in the order the Movements tab offers them")
    void names() {
      assertEquals(List.of("listMovements", "movementGlItems", "createMovement",
          "updateMovement", "processMovement", "reactivateMovement", "deleteMovement"),
          new ArrayList<>(FinancialAccountMovementActions.actionContracts().keySet()));
    }

    @Test
    @DisplayName("createMovement requires trxType (BPD|BPW only), amount, date and glItemId")
    void createContract() throws Exception {
      NeoActionContract create =
          FinancialAccountMovementActions.actionContracts().get("createMovement");
      assertTrue(create.isMutating());
      JSONObject schema = create.toJson().getJSONObject("parameters");
      List<String> required = new ArrayList<>();
      for (int i = 0; i < schema.getJSONArray("required").length(); i++) {
        required.add(schema.getJSONArray("required").getString(i));
      }
      assertEquals(List.of("trxType", "amount", "date", "glItemId"), required);
      JSONObject trxType = schema.getJSONObject("properties").getJSONObject("trxType");
      assertEquals("[\"BPD\",\"BPW\"]", trxType.getJSONArray("enum").toString(),
          "the form offers deposit and withdrawal only, never a bank fee");
      assertTrue(create.getDescription().contains("not a bank-statement line"),
          "the description must tell a movement from a statement line");
    }

    @Test
    @DisplayName("every write requires movementId except createMovement; reads are not mutating")
    void movementId() {
      Map<String, NeoActionContract> c = FinancialAccountMovementActions.actionContracts();
      for (String name : List.of("updateMovement", "processMovement", "reactivateMovement",
          "deleteMovement")) {
        assertTrue(c.get(name).isMutating(), name);
        assertTrue(c.get(name).getParams().stream()
            .anyMatch(p -> "movementId".equals(p.getName()) && p.isRequired()), name);
      }
      assertFalse(c.get("listMovements").isMutating());
      assertFalse(c.get("movementGlItems").isMutating());
    }

    @Test
    @DisplayName("an agent's call that skips the contract is refused before anything runs")
    void validatedByContract() throws Exception {
      NeoResponse bad = NeoActionContract.validate(
          FinancialAccountMovementActions.actionContracts(), "createMovement",
          deposit().put("trxType", "BF"));
      assertNotNull(bad, "BF is not offered by the form");
      assertEquals(422, bad.getHttpStatus());
      assertNull(NeoActionContract.validate(FinancialAccountMovementActions.actionContracts(),
          "createMovement", deposit()));
    }
  }

  // ── routing ───────────────────────────────────────────────────────────

  @Nested
  @DisplayName("routing")
  class Routing {

    @Test
    @DisplayName("not an ACTION, or not a movement action: null, nothing called")
    void notOurs() throws Exception {
      assertNull(actions.handle(NeoContext.builder().specName("financial-account")
          .entityName("account").httpMethod("POST").recordId(ACCOUNT)
          .endpointType(NeoEndpointType.CRUD).fieldName("createMovement").build()));
      assertNull(actions.handle(ctx("aPRMReconcile", new JSONObject())));
      assertTrue(calls.isEmpty());
    }

    @Test
    @DisplayName("a write over GET is 405")
    void writeNeedsPost() throws Exception {
      assertEquals(405, actions.handle(ctx("createMovement", deposit(), "GET")).getHttpStatus());
      assertTrue(calls.isEmpty());
    }

    @Test
    @DisplayName("an account the tenant cannot read is 404 and nothing runs")
    void foreignAccount() throws Exception {
      owned(FIN_FinancialAccount.class, ACCOUNT, null);
      NeoResponse r = actions.handle(ctx("createMovement", deposit()));
      assertEquals(404, r.getHttpStatus());
      assertTrue(calls.isEmpty());
    }

    @Test
    @DisplayName("listMovements is the endpoint's list of this account")
    void list() throws Exception {
      endpointAnswer = NeoResponse.ok(new JSONObject().put("ok", true));
      NeoResponse r = actions.handle(ctx("listMovements", null, "GET"));
      assertEquals(200, r.getHttpStatus());
      NeoContext call = onlyCall();
      assertEquals("GET", call.getHttpMethod());
      assertEquals(ACCOUNT, call.getQueryParams().get("FIN_Financial_Account_ID"));
      assertNull(call.getQueryParams().get("action"));
      assertEquals("financial-account-transactions", call.getSpecName());
    }

    @Test
    @DisplayName("movementGlItems is the endpoint's G/L item lookup, search as q")
    void glItems() throws Exception {
      endpointAnswer = NeoResponse.ok(new JSONObject());
      actions.handle(ctx("movementGlItems", new JSONObject().put("search", "banc"), "GET"));
      NeoContext call = onlyCall();
      assertEquals("glitem-lookup", call.getQueryParams().get("action"));
      assertEquals("banc", call.getQueryParams().get("q"));
    }

    @Test
    @DisplayName("the endpoint is told the call came through MCP")
    void mcpOriginTravels() throws Exception {
      actions.handle(ctx("createMovement", deposit()));
      assertTrue(onlyCall().isMcpOrigin());
    }
  }

  // ── createMovement ──────────────────────────────────────────────────────

  @Nested
  @DisplayName("createMovement")
  class Create {

    @Test
    @DisplayName("a deposit is sent as the New movement form sends it")
    void depositBody() throws Exception {
      movement(ACCOUNT, "BPD", false, "N");
      NeoResponse r = actions.handle(ctx("createMovement", deposit()));

      NeoContext call = onlyCall();
      assertEquals("POST", call.getHttpMethod());
      assertEquals("create", call.getQueryParams().get("action"));
      JSONObject body = call.getRequestBody();
      assertEquals(ACCOUNT, body.getString("FIN_Financial_Account_ID"));
      assertEquals("BPD", body.getString("trxType"));
      assertEquals("2026-09-30", body.getString("transactionDate"));
      assertEquals("2026-09-30", body.getString("accountingDate"));
      assertEquals(0, new BigDecimal("100").compareTo(new BigDecimal(body.getString("depositAmount"))));
      assertEquals(0, BigDecimal.ZERO.compareTo(new BigDecimal(body.getString("paymentAmount"))));
      assertEquals(EUR, body.getString("currencyId"), "the account currency, as the SPA sends");
      assertEquals(GL, body.getString("glItemId"));
      assertEquals("MCP-PARITY deposit", body.getString("description"));
      assertFalse(body.getBoolean("process"), "Guardar by default: a draft");

      assertEquals(201, r.getHttpStatus());
      JSONObject movement = data(r);
      assertEquals(MOVEMENT, movement.getString("id"));
      assertEquals(ACCOUNT, movement.getString("accountId"));
      assertEquals("2026-09-15", movement.getString("date"), "re-read, not echoed");
      assertFalse(movement.getBoolean("processed"));
    }

    @Test
    @DisplayName("a withdrawal goes to paymentAmount; process true is Confirmar")
    void withdrawalProcessed() throws Exception {
      actions.handle(ctx("createMovement", deposit().put("trxType", "BPW").put("process", true)));
      JSONObject body = onlyCall().getRequestBody();
      assertEquals(0, BigDecimal.ZERO.compareTo(new BigDecimal(body.getString("depositAmount"))));
      assertEquals(0, new BigDecimal("100").compareTo(new BigDecimal(body.getString("paymentAmount"))));
      assertTrue(body.getBoolean("process"));
    }

    @Test
    @DisplayName("what the form refuses is refused (422 naming the field), nothing runs")
    void formGates() throws Exception {
      Object[][] cases = {
          { deposit().put("trxType", "BF"), "trxType" },
          { deposit().put("amount", 0), "amount" },
          { deposit().put("amount", -5), "amount" },
          { deposit().put("amount", "abc"), "amount" },
          { deposit().put("glItemId", ""), "glItemId" },
          { deposit().put("description", "x".repeat(256)), "description" },
      };
      for (Object[] c : cases) {
        NeoResponse r = actions.handle(ctx("createMovement", (JSONObject) c[0]));
        assertEquals(422, r.getHttpStatus(), c[0].toString());
        assertEquals(c[1], field(r), c[0].toString());
      }
      JSONObject noGl = deposit();
      noGl.remove("glItemId");
      assertEquals("glItemId", field(actions.handle(ctx("createMovement", noGl))));
      assertTrue(calls.isEmpty());
    }

    @Test
    @DisplayName("255 characters of description pass")
    void descriptionAtTheLimit() throws Exception {
      actions.handle(ctx("createMovement", deposit().put("description", "x".repeat(255))));
      assertEquals(1, calls.size());
    }

    @Test
    @DisplayName("an id the tenant cannot read is refused, not silently dropped")
    void unreadableReferences() throws Exception {
      owned(GLItem.class, GL, null);
      NeoResponse r = actions.handle(ctx("createMovement", deposit()));
      assertEquals(422, r.getHttpStatus());
      assertEquals("glItemId", field(r));
      owned(GLItem.class, GL, glItem(GL));
      r = actions.handle(ctx("createMovement", deposit().put("bpartnerId", "FOREIGN")));
      assertEquals("bpartnerId", field(r));
      assertTrue(error(r).contains("FOREIGN"));
      assertTrue(calls.isEmpty());
    }

    @Test
    @DisplayName("a readable business partner travels to the endpoint")
    void readableReference() throws Exception {
      owned(BusinessPartner.class, "BP9", mock(BusinessPartner.class));
      actions.handle(ctx("createMovement", deposit().put("bpartnerId", "BP9")));
      assertEquals("BP9", onlyCall().getRequestBody().getString("bpartnerId"));
    }

    @Test
    @DisplayName("an endpoint refusal is returned as it is")
    void endpointRefusalPassesThrough() throws Exception {
      endpointAnswer = NeoResponse.error(400, "Currency not found");
      NeoResponse r = actions.handle(ctx("createMovement", deposit()));
      assertEquals(400, r.getHttpStatus());
      assertEquals("Currency not found", error(r));
    }
  }

  // ── updateMovement ──────────────────────────────────────────────────────

  @Nested
  @DisplayName("updateMovement")
  class Update {

    private JSONObject change() throws Exception {
      return new JSONObject().put("movementId", MOVEMENT);
    }

    @Test
    @DisplayName("a draft is merged: only what changes is sent different")
    void draftMerge() throws Exception {
      endpointAnswer = NeoResponse.ok(new JSONObject());
      movement(ACCOUNT, "BPD", false, "N");
      NeoResponse r = actions.handle(ctx("updateMovement", change().put("amount", 75)));
      NeoContext call = onlyCall();
      assertEquals("update", call.getQueryParams().get("action"));
      JSONObject body = call.getRequestBody();
      assertEquals(MOVEMENT, body.getString("id"));
      assertEquals("BPD", body.getString("trxType"));
      assertEquals(0, new BigDecimal("75").compareTo(new BigDecimal(body.getString("depositAmount"))));
      assertEquals("2026-09-15", body.getString("transactionDate"));
      assertEquals("2026-09-16", body.getString("accountingDate"), "kept, not collapsed");
      assertEquals("old text", body.getString("description"));
      assertEquals(GL, body.getString("glItemId"));
      assertEquals("BP1", body.getString("bpartnerId"));
      assertEquals(EUR, body.getString("currencyId"));
      assertFalse(body.getBoolean("process"));
      assertEquals(200, r.getHttpStatus());
      assertEquals(MOVEMENT, data(r).getString("id"));
    }

    @Test
    @DisplayName("switching the type moves the amount; a new date sets both dates; '' clears")
    void typeDateAndClear() throws Exception {
      endpointAnswer = NeoResponse.ok(new JSONObject());
      movement(ACCOUNT, "BPD", false, "N");
      actions.handle(ctx("updateMovement", change().put("trxType", "BPW")
          .put("date", "2026-09-30").put("bpartnerId", "")));
      JSONObject body = onlyCall().getRequestBody();
      assertEquals(0, BigDecimal.ZERO.compareTo(new BigDecimal(body.getString("depositAmount"))));
      assertEquals(0, new BigDecimal("40").compareTo(new BigDecimal(body.getString("paymentAmount"))));
      assertEquals("2026-09-30", body.getString("transactionDate"));
      assertEquals("2026-09-30", body.getString("accountingDate"));
      assertEquals("", body.getString("bpartnerId"));
    }

    @Test
    @DisplayName("a processed movement takes its description and dimensions only")
    void processedPartialEdit() throws Exception {
      endpointAnswer = NeoResponse.ok(new JSONObject());
      movement(ACCOUNT, "BPD", true, "N");
      for (String locked : List.of("amount", "trxType", "date")) {
        Object value = "amount".equals(locked) ? 5 : ("trxType".equals(locked) ? "BPW"
            : "2026-09-01");
        NeoResponse r = actions.handle(ctx("updateMovement", change().put(locked, value)));
        assertEquals(422, r.getHttpStatus(), locked);
        assertEquals(locked, field(r));
        assertTrue(error(r).contains("reactivateMovement"), error(r));
      }
      assertEquals(409, actions.handle(ctx("updateMovement", change().put("process", true)))
          .getHttpStatus());
      assertTrue(calls.isEmpty());
      actions.handle(ctx("updateMovement", change().put("description", "new text")));
      assertEquals("new text", onlyCall().getRequestBody().getString("description"));
    }

    @Test
    @DisplayName("a posted movement, or one of a payment or receipt, is not edited here")
    void notEditable() throws Exception {
      movement(ACCOUNT, "BPD", true, "Y");
      assertEquals(409, actions.handle(ctx("updateMovement", change())).getHttpStatus());
      FIN_FinaccTransaction linked = movement(ACCOUNT, "BPD", false, "N");
      FIN_Payment receipt = payment(true);
      when(linked.getFinPayment()).thenReturn(receipt);
      NeoResponse r = actions.handle(ctx("updateMovement", change()));
      assertEquals(409, r.getHttpStatus());
      assertTrue(error(r).contains("receipt"), error(r));
      FIN_Payment pay = payment(false);
      when(linked.getFinPayment()).thenReturn(pay);
      assertTrue(error(actions.handle(ctx("updateMovement", change()))).contains("payment"));
      assertTrue(calls.isEmpty());
    }

    @Test
    @DisplayName("the merged draft still has to be what the form accepts")
    void mergedShape() throws Exception {
      FIN_FinaccTransaction m = movement(ACCOUNT, "BPD", false, "N");
      when(m.getGLItem()).thenReturn(null);
      assertEquals("glItemId", field(actions.handle(ctx("updateMovement", change()))));
      assertEquals("amount", field(actions.handle(ctx("updateMovement",
          change().put("glItemId", GL).put("amount", 0)))));
      assertTrue(calls.isEmpty());
    }
  }

  // ── process / reactivate / delete ───────────────────────────────────────

  @Nested
  @DisplayName("processMovement, reactivateMovement, deleteMovement")
  class Lifecycle {

    private JSONObject target() throws Exception {
      return new JSONObject().put("movementId", MOVEMENT);
    }

    @Test
    @DisplayName("a movement of another account, or unreadable, is 404 for every write")
    void notThisAccount() throws Exception {
      movement(OTHER_ACCOUNT, "BPD", false, "N");
      for (String a : List.of("updateMovement", "processMovement", "reactivateMovement",
          "deleteMovement")) {
        assertEquals(404, actions.handle(ctx(a, target())).getHttpStatus(), a);
      }
      owned(FIN_FinaccTransaction.class, MOVEMENT, null);
      assertEquals(404, actions.handle(ctx("deleteMovement", target())).getHttpStatus());
      assertTrue(calls.isEmpty());
    }

    @Test
    @DisplayName("process: a draft goes to the endpoint with its id")
    void process() throws Exception {
      endpointAnswer = NeoResponse.ok(new JSONObject());
      movement(ACCOUNT, "BPD", false, "N");
      assertEquals(200, actions.handle(ctx("processMovement", target())).getHttpStatus());
      NeoContext call = onlyCall();
      assertEquals("process", call.getQueryParams().get("action"));
      assertEquals("{\"id\":\"MOV1\"}", call.getRequestBody().toString());
    }

    @Test
    @DisplayName("process: refused on a processed movement and on a payment's")
    void processGates() throws Exception {
      movement(ACCOUNT, "BPD", true, "N");
      assertEquals(409, actions.handle(ctx("processMovement", target())).getHttpStatus());
      FIN_FinaccTransaction linked = movement(ACCOUNT, "BPD", false, "N");
      FIN_Payment pay = payment(false);
      when(linked.getFinPayment()).thenReturn(pay);
      assertEquals(409, actions.handle(ctx("processMovement", target())).getHttpStatus());
      assertTrue(calls.isEmpty());
    }

    @Test
    @DisplayName("reactivate: only a processed movement")
    void reactivate() throws Exception {
      movement(ACCOUNT, "BPD", false, "N");
      assertEquals(409, actions.handle(ctx("reactivateMovement", target())).getHttpStatus());
      assertTrue(calls.isEmpty());
      endpointAnswer = NeoResponse.ok(new JSONObject());
      movement(ACCOUNT, "BPD", true, "Y");
      assertEquals(200, actions.handle(ctx("reactivateMovement", target())).getHttpStatus());
      assertEquals("reactivate", onlyCall().getQueryParams().get("action"));
    }

    @Test
    @DisplayName("delete: any status, as the row's Eliminar; answers what was deleted")
    void delete() throws Exception {
      endpointAnswer = NeoResponse.ok(new JSONObject());
      movement(ACCOUNT, "BPD", true, "Y");
      NeoResponse r = actions.handle(ctx("deleteMovement", target()));
      NeoContext call = onlyCall();
      assertEquals("delete", call.getQueryParams().get("action"));
      assertEquals("{\"id\":\"MOV1\"}", call.getRequestBody().toString(),
          "no paymentRemoval: the endpoint's default, the row kebab's");
      assertEquals(200, r.getHttpStatus());
      JSONObject deleted = data(r).getJSONObject("deleted");
      assertEquals(MOVEMENT, deleted.getString("id"));
      assertTrue(deleted.getBoolean("processed"));
    }

    @Test
    @DisplayName("delete: the endpoint's 409 (payment, transfer leg) is returned as it is")
    void deleteRefusal() throws Exception {
      movement(ACCOUNT, "BPD", false, "N");
      endpointAnswer = NeoResponse.error(409,
          "Movements generated by a funds transfer cannot be deleted.");
      NeoResponse r = actions.handle(ctx("deleteMovement", target()));
      assertEquals(409, r.getHttpStatus());
      assertFalse(r.getBody().has("response"));
    }
  }

  // ── the real endpoint ─────────────────────────────────────────────────

  @Nested
  @DisplayName("against the real FinancialAccountTransactionsHandler")
  class RealEndpoint {

    @Test
    @DisplayName("createMovement books the deposit the SPA would: BPD, 100, 30/09, the G/L item")
    void createsTheSameRow() throws Exception {
      Client client = mock(Client.class);
      Organization org = mock(Organization.class);
      when(account.getClient()).thenReturn(client);
      when(account.getOrganization()).thenReturn(org);
      Currency currency = account.getCurrency();
      owned(Currency.class, EUR, currency);
      GLItem gl = glItem(GL);
      owned(GLItem.class, GL, gl);
      FIN_FinaccTransaction trx = mock(FIN_FinaccTransaction.class);
      when(trx.getId()).thenReturn("NEW");
      Connection conn = mock(Connection.class);
      PreparedStatement ps = mock(PreparedStatement.class);
      ResultSet rs = mock(ResultSet.class);
      when(dal.getConnection()).thenReturn(conn);
      when(conn.prepareStatement(anyString())).thenReturn(ps);
      when(ps.executeQuery()).thenReturn(rs);
      when(rs.next()).thenReturn(true);
      when(rs.getLong("next_line")).thenReturn(10L);

      Function<NeoContext, NeoResponse> real =
          context -> new FinancialAccountTransactionsHandler().handle(context);
      try (MockedStatic<OBProvider> providerMock = mockStatic(OBProvider.class);
           MockedStatic<FIN_TransactionProcess> process =
               mockStatic(FIN_TransactionProcess.class)) {
        OBProvider provider = mock(OBProvider.class);
        providerMock.when(OBProvider::getInstance).thenReturn(provider);
        when(provider.get(FIN_FinaccTransaction.class)).thenReturn(trx);

        NeoResponse r = new FinancialAccountMovementActions(real)
            .handle(ctx("createMovement", deposit().put("process", true)));

        assertEquals(201, r.getHttpStatus(), String.valueOf(r.getBody()));
        verify(trx).setAccount(account);
        verify(trx).setTransactionType("BPD");
        verify(trx).setDepositAmount(new BigDecimal("100"));
        verify(trx).setPaymentAmount(BigDecimal.ZERO);
        verify(trx).setTransactionDate(day("2026-09-30"));
        verify(trx).setDateAcct(day("2026-09-30"));
        verify(trx).setGLItem(gl);
        verify(trx).setCurrency(currency);
        verify(trx).setDescription("MCP-PARITY deposit");
        process.verify(() -> FIN_TransactionProcess.doTransactionProcess("P", trx));
      }
    }
  }

  // ── wiring ────────────────────────────────────────────────────────────

  @Nested
  @DisplayName("wiring")
  class Wiring {

    @Test
    @DisplayName("FinancialAccountHandler declares the movement actions")
    void declared() {
      assertTrue(new FinancialAccountHandler().actionContracts().keySet()
          .containsAll(FinancialAccountMovementActions.actionContracts().keySet()));
    }

    @Test
    @DisplayName("FinancialAccountHandler routes the account's actions, and only the account's")
    void routed() throws Exception {
      FinancialAccountHandler handler = new FinancialAccountHandler();
      FinancialAccountMovementActions stub = mock(FinancialAccountMovementActions.class);
      NeoResponse answer = NeoResponse.ok(new JSONObject());
      when(stub.handle(any())).thenReturn(answer);
      handler.movementActions = stub;
      assertEquals(answer, handler.handle(ctx("createMovement", deposit())));
      NeoContext transaction = NeoContext.builder().specName("financial-account")
          .entityName("transaction").httpMethod("POST").recordId("T")
          .endpointType(NeoEndpointType.ACTION).fieldName("createMovement").build();
      assertNull(handler.handle(transaction));
      verify(stub, Mockito.times(1)).handle(any());
    }

    @Test
    @DisplayName("every endpoint action this class calls is one the endpoint routes")
    void endpointActionsExist() throws Exception {
      String endpoint = source("FinancialAccountTransactionsHandler.java");
      String actionsSrc = source("FinancialAccountMovementActions.java");
      Matcher called = Pattern.compile("callEndpoint\\(\"(?:GET|POST)\", \"([a-z-]+)\"")
          .matcher(actionsSrc);
      List<String> names = new ArrayList<>();
      while (called.find()) {
        names.add(called.group(1));
      }
      assertEquals(List.of("glitem-lookup", "create", "update", "process", "reactivate", "delete"),
          names);
      for (String name : names) {
        assertTrue(endpoint.contains("= \"" + name + "\";"), name + " is not an endpoint action");
      }
    }

    /** A source of this package, from the Etendo root or from the module directory. */
    private String source(String file) throws java.io.IOException {
      String rel = "src/com/etendoerp/go/schemaforge/" + file;
      java.nio.file.Path fromRoot = java.nio.file.Path.of("modules/com.etendoerp.go", rel);
      return java.nio.file.Files.readString(java.nio.file.Files.exists(fromRoot) ? fromRoot
          : java.nio.file.Path.of(rel));
    }
  }
}
