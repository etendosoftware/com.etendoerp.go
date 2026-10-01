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

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.etendoerp.go.schemaforge.util.NeoActionContract;

/**
 * ETP-5558 Step 3 — the invoice payment actions declare their contracts (FR-1).
 *
 * <p>The handlers already serve these actions to the SPA; what was missing is a declaration an
 * agent can discover. Every assertion here is about that declaration being <b>true</b>: a contract
 * that omits a key the service reads would make the MCP refuse a valid call (it validates against
 * the contract before dispatch), and one that adds a key the service ignores would invite the agent
 * to send something that does nothing.</p>
 */
@DisplayName("ETP-5558 — invoice payment action contracts")
class PaymentActionContractsTest {

  private static final List<String> EXPECTED = List.of("registerPayment", "confirmPayment",
      "deletePayment", "invoicePayments", "invoiceAccounts", "invoicePaymentMethods",
      "invoiceCreditSources");

  private static final Set<String> PIS = Set.of("pisSupplierAccounts", "pisTemplates",
      "pisPaymentStatus", "cancelPisPayment", "retryPisPayment");

  @ParameterizedTest
  @ValueSource(booleans = { true, false })
  @DisplayName("both directions declare the same seven payment actions, and no PIS action")
  void declaresThePaymentActions(boolean isReceipt) {
    Map<String, NeoActionContract> contracts = PaymentActionHandlerSupport.actionContracts(isReceipt);

    assertEquals(EXPECTED, List.copyOf(contracts.keySet()));
    for (String pis : PIS) {
      assertFalse(contracts.containsKey(pis), "PIS is excluded from MCP: " + pis);
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = { true, false })
  @DisplayName("every contract says what the neo_action id is: the invoice")
  void everyContractNamesItsId(boolean isReceipt) {
    for (NeoActionContract c : PaymentActionHandlerSupport.actionContracts(isReceipt).values()) {
      assertNotNull(c.getIdDescription(), c.getName());
      assertTrue(c.getIdDescription().toLowerCase().contains("invoice"), c.getName());
    }
  }

  /**
   * The parameter set is the contract's whole promise. These are exactly the body keys
   * {@code PaymentRegistrationService.doRegisterPaymentAdvanced} and {@code PaymentCreditConsumer}
   * read, minus {@code pis} — PIS is excluded, and leaving the key undeclared is what makes the MCP
   * refuse it.
   */
  @Test
  @DisplayName("registerPayment declares exactly the keys the service reads, minus pis")
  void registerPaymentParameters() throws Exception {
    NeoActionContract c = PaymentActionHandlerSupport.actionContracts(true).get("registerPayment");
    Set<String> names = new TreeSet<>();
    c.getParams().forEach(p -> names.add(p.getName()));

    assertEquals(new TreeSet<>(List.of("scheduleId", "actual_payment", "payment_date",
        "fin_financial_account_id", "fin_paymentmethod_id", "process", "paymentId",
        "creditSources", "overpaymentAction", "conversionRate", "writeoffDifference")), names);
    assertTrue(c.isMutating());

    JSONObject schema = c.toJson().getJSONObject("parameters");
    JSONArray required = schema.getJSONArray("required");
    Set<String> req = new TreeSet<>();
    for (int i = 0; i < required.length(); i++) {
      req.add(required.getString(i));
    }
    assertEquals(new TreeSet<>(List.of("actual_payment", "payment_date",
        "fin_financial_account_id", "process")), req,
        "the three the handler refuses a call without, plus process (MCP-only, see below); "
            + "scheduleId is resolved for an agent (PaymentAgentSupport)");

    JSONObject props = schema.getJSONObject("properties");
    assertEquals(List.of("draft", "confirm"),
        jsonList(props.getJSONObject("process").getJSONArray("enum")));
    assertEquals(List.of("leave-credit", "refund"),
        jsonList(props.getJSONObject("overpaymentAction").getJSONArray("enum")));
    assertEquals("number", props.getJSONObject("actual_payment").getString("type"));
    assertTrue(props.getJSONObject("writeoffDifference").getString("description")
        .contains("writeoffLimit"), "the cap must be stated where the agent decides");
    assertTrue(props.getJSONObject("conversionRate").getString("description")
        .contains("currencies differ"));
    // The service multiplies actual_payment by conversionRate INTO the account currency
    // (PaymentCurrencyConverter.convertedAmount), so the amount is in the invoice currency.
    String amount = props.getJSONObject("actual_payment").getString("description");
    assertTrue(amount.contains("invoice currency"), amount);
    assertFalse(amount.contains("in the financial account's currency"), amount);
    assertTrue(amount.contains("conversionRate"), amount);
    String credit = props.getJSONObject("creditSources").getString("description");
    assertFalse(credit.contains("{kind, use, id}"), "the keys are paymentId / psdId: " + credit);
  }

  @Test
  @DisplayName("the parameter validation accepts a body the SPA sends and refuses pis")
  void validationMatchesTheService() throws Exception {
    Map<String, NeoActionContract> contracts = PaymentActionHandlerSupport.actionContracts(true);
    JSONObject spaBody = new JSONObject("{\"scheduleId\":\"S\",\"actual_payment\":\"121\","
        + "\"payment_date\":\"2026-09-30\",\"fin_financial_account_id\":\"A\","
        + "\"fin_paymentmethod_id\":\"M\",\"process\":\"confirm\",\"creditSources\":[]}");
    assertNull(NeoActionContract.validate(contracts, "registerPayment", spaBody));

    JSONObject numeric = new JSONObject(spaBody.toString());
    numeric.put("actual_payment", 121);
    assertNull(NeoActionContract.validate(contracts, "registerPayment", numeric),
        "an agent sending a JSON number for an amount must not be refused");

    JSONObject words = new JSONObject(spaBody.toString());
    words.put("actual_payment", "ten");
    NeoResponse notNumeric = NeoActionContract.validate(contracts, "registerPayment", words);
    assertTrue(notNumeric.getBody().getJSONObject("error").getString("message")
        .contains("a number or numeric string"), "the refusal says both shapes are accepted");

    JSONObject pis = new JSONObject(spaBody.toString());
    pis.put("pis", true);
    assertEquals(422,
        NeoActionContract.validate(contracts, "registerPayment", pis).getHttpStatus());
  }

  /**
   * The contract promises that paymentId, conversionRate and writeoffDifference take effect on
   * their own. The handler reads them only on its advanced path, which a body without
   * {@code process} (or another advanced key) does not take — over REST such a body creates a NEW
   * payment, refuses the rate or skips the write-off, silently (§4.12.9). REST stays as it is;
   * the MCP closes the gap by requiring {@code process}, so every call it lets through takes the
   * advanced path. The SPA always sends it.
   */
  @ParameterizedTest
  @ValueSource(strings = { "paymentId", "conversionRate", "writeoffDifference" })
  @DisplayName("the MCP refuses an advanced-only key without process, naming process")
  void advancedOnlyKeysNeedProcess(String key) throws Exception {
    Map<String, NeoActionContract> contracts = PaymentActionHandlerSupport.actionContracts(true);
    JSONObject body = new JSONObject("{\"scheduleId\":\"S\",\"actual_payment\":\"10\","
        + "\"payment_date\":\"2026-09-30\",\"fin_financial_account_id\":\"A\"}");
    body.put(key, "writeoffDifference".equals(key) ? (Object) Boolean.TRUE : "1.1");

    NeoResponse refused = NeoActionContract.validate(contracts, "registerPayment", body);
    assertNotNull(refused, key + " without process must not reach the simple path");
    assertEquals(422, refused.getHttpStatus());
    assertEquals("process", refused.getBody().getJSONObject("error")
        .getJSONArray("missingParameters").getString(0));

    body.put("process", "confirm");
    assertNull(NeoActionContract.validate(contracts, "registerPayment", body), key);
  }

  @Test
  @DisplayName("process is a required closed choice whose description tells the agent why")
  void processIsRequired() throws Exception {
    JSONObject process = PaymentActionHandlerSupport.actionContracts(false).get("registerPayment")
        .toJson().getJSONObject("parameters").getJSONObject("properties")
        .getJSONObject("process");
    assertEquals(List.of("draft", "confirm"), jsonList(process.getJSONArray("enum")));
    assertTrue(process.getString("description").contains("'confirm'"));
  }

  @Test
  @DisplayName("confirmPayment and deletePayment require paymentId; the lists read nothing")
  void otherActions() {
    Map<String, NeoActionContract> contracts = PaymentActionHandlerSupport.actionContracts(false);
    assertEquals(List.of("paymentId"), names(contracts.get("confirmPayment")));
    assertEquals(List.of("paymentId"), names(contracts.get("deletePayment")));
    assertTrue(names(contracts.get("invoicePayments")).isEmpty());
    assertEquals(List.of("editPaymentId"), names(contracts.get("invoiceCreditSources")));
    assertFalse(contracts.get("invoiceAccounts").isMutating());
  }

  @Test
  @DisplayName("both invoice headers publish the payment actions plus currencyOptions over GET")
  void headersPublishTheUnion() {
    Map<String, NeoActionContract> sales = new SalesInvoiceHeaderHandler().actionContracts();
    Map<String, NeoActionContract> purchase = new PurchaseInvoiceHeaderHandler().actionContracts();

    assertTrue(sales.keySet().containsAll(EXPECTED) && sales.containsKey("currencyOptions"));
    assertTrue(purchase.keySet().containsAll(EXPECTED) && purchase.containsKey("currencyOptions"));
    assertEquals("GET", sales.get("currencyOptions").getHttpMethod(),
        "currencyOptions only answers GET; the MCP must call it that way");
    assertEquals("POST", sales.get("registerPayment").getHttpMethod());
  }

  @Test
  @DisplayName("both invoice headers exclude the five PIS actions and the PSD2 button, in code")
  void headersExcludePis() {
    Set<String> expected = new TreeSet<>(PIS);
    expected.add("psd2GenerateBankPayment");
    assertEquals(expected, new TreeSet<>(new SalesInvoiceHeaderHandler().agentExcludedActions()));
    assertEquals(expected,
        new TreeSet<>(new PurchaseInvoiceHeaderHandler().agentExcludedActions()));
  }

  @Test
  @DisplayName("a handler that excludes nothing says so")
  void defaultExcludesNothing() {
    assertTrue(new CurrencyOptionsHandler().agentExcludedActions().isEmpty());
  }

  private static List<String> names(NeoActionContract c) {
    return c.getParams().stream().map(NeoActionContract.Param::getName).toList();
  }

  private static List<String> jsonList(JSONArray arr) throws Exception {
    List<String> out = new java.util.ArrayList<>();
    for (int i = 0; i < arr.length(); i++) {
      out.add(arr.getString(i));
    }
    return out;
  }
}
