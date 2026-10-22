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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.openbravo.dal.core.OBContext;
import org.openbravo.model.financialmgmt.accounting.coa.AccountingCombination;
import org.openbravo.model.financialmgmt.accounting.coa.AcctSchemaGL;

import com.etendoerp.go.schemaforge.handlers.GeneralLedgerAccountsHandler;

/**
 * The generic CRUD path of the balancing rule: a PUT/PATCH on {@code Cuentas generales} is judged on
 * the body merged with the stored row, with values read the way core's JsonToDataConverter stores
 * them, and the {@code @NeoExtension} handler of that entity applies it.
 *
 * @covers com.etendoerp.go.schemaforge.GeneralLedgerBalancingRule
 * @covers com.etendoerp.go.schemaforge.handlers.GeneralLedgerAccountsHandler
 */
@DisplayName("General ledger balancing rule — generic CRUD path")
class GeneralLedgerBalancingRuleTest {

  private static final String ROW_ID = "gl-row-1";

  private MockedStatic<OBContext> obContext;
  private MockedStatic<TenantOwnership> ownership;
  private AcctSchemaGL stored;

  @BeforeEach
  void setUp() {
    obContext = mockStatic(OBContext.class);
    ownership = mockStatic(TenantOwnership.class);
    stored = mock(AcctSchemaGL.class);
    ownership.when(() -> TenantOwnership.loadOwned(AcctSchemaGL.class, ROW_ID)).thenReturn(stored);
  }

  @AfterEach
  void tearDown() {
    obContext.close();
    ownership.close();
  }

  private static NeoContext ctx(String method, JSONObject body) {
    return NeoContext.builder()
        .httpMethod(method)
        .specName("general-ledger-configuration")
        .entityName("Cuentas generales")
        .endpointType(NeoEndpointType.CRUD)
        .recordId(ROW_ID)
        .requestBody(body)
        .build();
  }

  private void storedSuspense(boolean on, boolean withAccount) {
    when(stored.get(AcctSchemaGL.PROPERTY_SUSPENSEBALANCINGUSE)).thenReturn(on);
    when(stored.get(AcctSchemaGL.PROPERTY_SUSPENSEBALANCING))
        .thenReturn(withAccount ? mock(AccountingCombination.class) : null);
  }

  private static void assertMissing(NeoResponse response, String field) throws Exception {
    assertEquals(400, response.getHttpStatus());
    JSONObject error = response.getBody().getJSONObject("error");
    assertEquals("MISSING_REQUIRED_FIELDS", error.getString("code"));
    assertEquals(1, error.getJSONArray("fields").length());
    assertEquals(field, error.getJSONArray("fields").getString(0));
  }

  @ParameterizedTest
  @ValueSource(strings = { "PATCH", "PUT" })
  @DisplayName("A partial body turning the switch on, on a row without the account, is refused")
  void partialSwitchOnWithoutAccount(String method) throws Exception {
    storedSuspense(false, false);
    assertMissing(GeneralLedgerBalancingRule.checkUpdate(
        ctx(method, new JSONObject().put("suspenseBalancingUse", true))), "suspenseBalancing");
  }

  @Test
  @DisplayName("Turning the switch on over a stored account continues")
  void switchOnWithStoredAccount() throws Exception {
    storedSuspense(false, true);
    assertNull(GeneralLedgerBalancingRule.checkUpdate(
        ctx("PATCH", new JSONObject().put("suspenseBalancingUse", true))));
  }

  @Test
  @DisplayName("Clearing the account while the stored switch is on is refused, also inside a data envelope")
  void clearingAccountRefusedFlatAndWrapped() throws Exception {
    storedSuspense(true, true);
    JSONObject fields = new JSONObject().put("suspenseBalancing", JSONObject.NULL);
    assertMissing(GeneralLedgerBalancingRule.checkUpdate(ctx("PATCH", fields)), "suspenseBalancing");
    assertMissing(GeneralLedgerBalancingRule.checkUpdate(ctx("PATCH",
        new JSONObject().put("updated", "2026-10-22T10:00:00").put("data", fields))), "suspenseBalancing");
  }

  @Test
  @DisplayName("Currency pair: switch on with a blank account is refused on currencyBalancingAcct")
  void currencyPair() throws Exception {
    assertMissing(GeneralLedgerBalancingRule.checkUpdate(ctx("PATCH", new JSONObject()
        .put("currencyBalancingUse", true).put("currencyBalancingAcct", ""))), "currencyBalancingAcct");
  }

  @Test
  @DisplayName("An unrelated field on an already broken row is refused")
  void brokenRowBlocksUnrelatedField() throws Exception {
    storedSuspense(true, false);
    assertMissing(GeneralLedgerBalancingRule.checkUpdate(
        ctx("PATCH", new JSONObject().put("retainedEarning", "combo-1"))), "suspenseBalancing");
  }

  @Test
  @DisplayName("A null body is judged on the stored row, without failing")
  void nullBody() throws Exception {
    assertNull(GeneralLedgerBalancingRule.checkUpdate(ctx("PATCH", null)));
    storedSuspense(true, false);
    assertMissing(GeneralLedgerBalancingRule.checkUpdate(ctx("PATCH", null)), "suspenseBalancing");
  }

  @Test
  @DisplayName("A row the caller cannot read is left to the default path (404)")
  void foreignOrMissingRow() throws Exception {
    ownership.when(() -> TenantOwnership.loadOwned(AcctSchemaGL.class, ROW_ID)).thenReturn(null);
    assertNull(GeneralLedgerBalancingRule.checkUpdate(
        ctx("PATCH", new JSONObject().put("suspenseBalancingUse", true))));
  }

  @Test
  @DisplayName("GET, POST, DELETE and non-CRUD endpoints pass through")
  void otherMethodsPassThrough() throws Exception {
    storedSuspense(true, false);
    for (String method : new String[] { "GET", "POST", "DELETE" }) {
      assertNull(GeneralLedgerBalancingRule.checkUpdate(ctx(method, new JSONObject())), method);
    }
    NeoContext action = NeoContext.builder().httpMethod("PATCH").endpointType(NeoEndpointType.ACTION)
        .recordId(ROW_ID).build();
    assertNull(GeneralLedgerBalancingRule.checkUpdate(action));
  }

  @Test
  @DisplayName("Switch values read like JsonToDataConverter: Y/true on, N/false off")
  void switchNormalisation() {
    assertEquals(true, GeneralLedgerBalancingRule.isTrue("Y"));
    assertEquals(true, GeneralLedgerBalancingRule.isTrue("true"));
    assertEquals(true, GeneralLedgerBalancingRule.isTrue(Boolean.TRUE));
    assertEquals(false, GeneralLedgerBalancingRule.isTrue("N"));
    assertEquals(false, GeneralLedgerBalancingRule.isTrue("false"));
    assertEquals(false, GeneralLedgerBalancingRule.isTrue(JSONObject.NULL));
  }

  @Test
  @DisplayName("Account values: blank, null and {id: \"\"} are absent; an id, bare or wrapped, is present")
  void accountNormalisation() throws Exception {
    assertEquals(false, GeneralLedgerBalancingRule.isPresent(""));
    assertEquals(false, GeneralLedgerBalancingRule.isPresent(JSONObject.NULL));
    assertEquals(false, GeneralLedgerBalancingRule.isPresent(new JSONObject().put("id", "")));
    assertEquals(true, GeneralLedgerBalancingRule.isPresent("combo-1"));
    assertEquals(true, GeneralLedgerBalancingRule.isPresent(new JSONObject().put("id", "combo-1")));
  }

  @Test
  @DisplayName("The Cuentas generales customization applies the rule after the posting-account guard")
  void handlerAppliesRule() throws Exception {
    storedSuspense(true, false);
    // No AD tab on the context: the posting-account guard passes, so the rule decides.
    assertMissing(new GeneralLedgerAccountsHandler().handle(
        ctx("PATCH", new JSONObject().put("retainedEarning", "combo-1"))), "suspenseBalancing");
  }
}
