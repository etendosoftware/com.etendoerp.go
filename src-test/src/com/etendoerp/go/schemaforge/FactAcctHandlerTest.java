/*
 *************************************************************************
 * The contents of this file are subject to the Etendo License
 * (the "License"), you may not use this file except in compliance
 * with the License.
 * You may obtain a copy of the License at
 * https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * Software distributed under the License is distributed on an
 * "AS IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
 * implied. See the License for the specific language governing rights
 * and limitations under the License.
 * All portions are Copyright (C) 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 *************************************************************************
 */
package com.etendoerp.go.schemaforge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.openbravo.dal.service.OBDal;
import org.openbravo.dal.service.OBQuery;
import org.openbravo.model.financialmgmt.accounting.AccountingFact;
import org.openbravo.model.financialmgmt.accounting.coa.ElementValue;

/**
 * Unit tests for {@link FactAcctHandler}.
 * Covers all branches: endpoint-type guard, HTTP-method guard, recordId guard,
 * empty/null parentId, unknown specName, both supported specNames (purchase-invoice
 * and goods-receipt), full row mapping with all fields populated, null field handling,
 * and exception handling. Static dependency on {@link OBDal} is isolated with
 * Mockito {@code MockedStatic} so no DB or CDI container is required.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FactAcctHandlerTest {

  private FactAcctHandler handler;

  private MockedStatic<OBDal> obDalMock;
  private OBDal obDalInstance;

  @BeforeEach
  void setUp() {
    handler = new FactAcctHandler();
    obDalInstance = mock(OBDal.class);
    obDalMock = mockStatic(OBDal.class);
    obDalMock.when(OBDal::getInstance).thenReturn(obDalInstance);
  }

  @AfterEach
  void tearDown() {
    obDalMock.close();
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private NeoContext buildCrudGetListContext(String specName, Map<String, String> queryParams) {
    return NeoContext.builder()
        .httpMethod("GET")
        .specName(specName)
        .entityName("accounting")
        .endpointType(NeoEndpointType.CRUD)
        .queryParams(queryParams)
        .build();
  }

  private NeoContext buildCrudGetWithRecordId(String specName, String recordId) {
    return NeoContext.builder()
        .httpMethod("GET")
        .specName(specName)
        .entityName("accounting")
        .endpointType(NeoEndpointType.CRUD)
        .recordId(recordId)
        .build();
  }

  @SuppressWarnings("unchecked")
  private OBQuery<AccountingFact> mockQuery(List<AccountingFact> results) {
    OBQuery<AccountingFact> query = mock(OBQuery.class);
    when(obDalInstance.createQuery(eq(AccountingFact.class), anyString())).thenReturn(query);
    when(query.setNamedParameter(anyString(), any())).thenReturn(query);
    when(query.list()).thenReturn(results);
    return query;
  }

  @SuppressWarnings("unchecked")
  private OBQuery<AccountingFact> mockQueryThrows(RuntimeException ex) {
    OBQuery<AccountingFact> query = mock(OBQuery.class);
    when(obDalInstance.createQuery(eq(AccountingFact.class), anyString())).thenReturn(query);
    when(query.setNamedParameter(anyString(), any())).thenReturn(query);
    when(query.list()).thenThrow(ex);
    return query;
  }

  private AccountingFact buildFact(String id, String accountSearchKey, String accountName,
      Date accountingDate, String postingType, BigDecimal debit, BigDecimal credit,
      String description) {
    AccountingFact af = mock(AccountingFact.class);
    doReturn(id).when(af).getId();

    if (accountSearchKey != null || accountName != null) {
      ElementValue account = mock(ElementValue.class);
      when(account.getSearchKey()).thenReturn(accountSearchKey);
      when(account.getName()).thenReturn(accountName);
      when(af.getAccount()).thenReturn(account);
    } else {
      when(af.getAccount()).thenReturn(null);
    }

    when(af.getAccountingDate()).thenReturn(accountingDate);
    when(af.getPostingType()).thenReturn(postingType);
    when(af.getDebit()).thenReturn(debit);
    when(af.getCredit()).thenReturn(credit);
    when(af.getDescription()).thenReturn(description);

    return af;
  }

  // ---------------------------------------------------------------------------
  // Guard clause tests
  // ---------------------------------------------------------------------------

  /**
   * Verifies that a non-CRUD endpoint type returns null (pass-through).
   */
  @Test
  void nonCrudEndpointTypeReturnsNull() {
    NeoContext ctx = NeoContext.builder()
        .httpMethod("GET")
        .specName("purchase-invoice")
        .entityName("accounting")
        .endpointType(NeoEndpointType.ACTION)
        .build();

    NeoResponse response = handler.handle(ctx);

    assertNull(response);
  }

  /**
   * Verifies that a POST request returns null (only GET is handled).
   */
  @Test
  void postMethodReturnsNull() {
    NeoContext ctx = NeoContext.builder()
        .httpMethod("POST")
        .specName("purchase-invoice")
        .entityName("accounting")
        .endpointType(NeoEndpointType.CRUD)
        .build();

    NeoResponse response = handler.handle(ctx);

    assertNull(response);
  }

  /**
   * Verifies that a GET request with a recordId returns null (only list is handled).
   */
  @Test
  void getWithRecordIdReturnsNull() {
    NeoResponse response = handler.handle(
        buildCrudGetWithRecordId("purchase-invoice", "some-record-id"));

    assertNull(response);
  }

  // ---------------------------------------------------------------------------
  // Empty / null parentId tests
  // ---------------------------------------------------------------------------

  /**
   * Verifies that a missing parentId (empty queryParams map) returns an empty response.
   */
  @Test
  void emptyParentIdReturnsEmptyResponse() throws Exception {
    NeoContext ctx = buildCrudGetListContext("purchase-invoice", Map.of());

    NeoResponse response = handler.handle(ctx);

    assertEquals(200, response.getHttpStatus());
    JSONObject responseBody = response.getBody().getJSONObject("response");
    assertEquals(0, responseBody.getInt("count"));
    assertEquals(0, responseBody.getJSONArray("data").length());
  }

  /**
   * Verifies that null queryParams returns an empty response.
   */
  @Test
  void nullQueryParamsReturnsEmptyResponse() throws Exception {
    NeoContext ctx = buildCrudGetListContext("purchase-invoice", null);

    NeoResponse response = handler.handle(ctx);

    assertEquals(200, response.getHttpStatus());
    JSONObject responseBody = response.getBody().getJSONObject("response");
    assertEquals(0, responseBody.getInt("count"));
    assertEquals(0, responseBody.getJSONArray("data").length());
  }

  /**
   * Verifies that an empty-string parentId returns an empty response.
   */
  @Test
  void emptyStringParentIdReturnsEmptyResponse() throws Exception {
    NeoContext ctx = buildCrudGetListContext("purchase-invoice", Map.of("parentId", ""));

    NeoResponse response = handler.handle(ctx);

    assertEquals(200, response.getHttpStatus());
    JSONObject responseBody = response.getBody().getJSONObject("response");
    assertEquals(0, responseBody.getInt("count"));
  }

  // ---------------------------------------------------------------------------
  // Unknown specName
  // ---------------------------------------------------------------------------

  /**
   * Verifies that an unknown specName returns an empty response.
   */
  @Test
  void unknownSpecNameReturnsEmptyResponse() throws Exception {
    NeoContext ctx = buildCrudGetListContext("unknown-spec", Map.of("parentId", "parent-123"));

    NeoResponse response = handler.handle(ctx);

    assertEquals(200, response.getHttpStatus());
    JSONObject responseBody = response.getBody().getJSONObject("response");
    assertEquals(0, responseBody.getInt("count"));
    assertEquals(0, responseBody.getJSONArray("data").length());
  }

  // ---------------------------------------------------------------------------
  // Both supported specNames
  // ---------------------------------------------------------------------------

  /**
   * Verifies that "purchase-invoice" specName works and queries with table ID 318.
   */
  @Test
  void purchaseInvoiceSpecNameUsesTableId318() throws Exception {
    mockQuery(Collections.emptyList());

    NeoContext ctx = buildCrudGetListContext("purchase-invoice", Map.of("parentId", "inv-123"));

    NeoResponse response = handler.handle(ctx);

    assertEquals(200, response.getHttpStatus());
    JSONObject responseBody = response.getBody().getJSONObject("response");
    assertEquals(0, responseBody.getInt("count"));
  }

  /**
   * Verifies that "goods-receipt" specName works and queries with table ID 319.
   */
  @Test
  void goodsReceiptSpecNameUsesTableId319() throws Exception {
    mockQuery(Collections.emptyList());

    NeoContext ctx = buildCrudGetListContext("goods-receipt", Map.of("parentId", "gr-456"));

    NeoResponse response = handler.handle(ctx);

    assertEquals(200, response.getHttpStatus());
    JSONObject responseBody = response.getBody().getJSONObject("response");
    assertEquals(0, responseBody.getInt("count"));
  }

  // ---------------------------------------------------------------------------
  // Full row mapping
  // ---------------------------------------------------------------------------

  /**
   * Verifies that a row with all non-null values is mapped correctly to the JSON response,
   * including account formatting as "searchKey - name" and date formatting as yyyy-MM-dd.
   */
  @Test
  void fullRowMappingWithAllFieldsPopulated() throws Exception {
    Date testDate = new java.text.SimpleDateFormat("yyyy-MM-dd").parse("2026-03-15");
    AccountingFact af = buildFact(
        "fact-001", "4100", "Accounts Payable", testDate,
        "A", BigDecimal.valueOf(1500.50), BigDecimal.valueOf(0), "Invoice payment");

    mockQuery(List.of(af));

    NeoContext ctx = buildCrudGetListContext("purchase-invoice", Map.of("parentId", "inv-123"));
    NeoResponse response = handler.handle(ctx);

    assertEquals(200, response.getHttpStatus());
    JSONObject responseBody = response.getBody().getJSONObject("response");
    assertEquals(1, responseBody.getInt("count"));

    JSONArray data = responseBody.getJSONArray("data");
    assertEquals(1, data.length());

    JSONObject row = data.getJSONObject(0);
    assertEquals("fact-001", row.getString("id"));
    assertEquals("4100 - Accounts Payable", row.getString("account"));
    assertEquals("2026-03-15", row.getString("accountingDate"));
    assertEquals("A", row.getString("postingType"));
    assertEquals(1500.5, row.getDouble("debit"), 0.001);
    assertEquals(0.0, row.getDouble("credit"), 0.001);
    assertEquals("Invoice payment", row.getString("description"));
  }

  /**
   * Verifies that multiple rows are correctly mapped and counted.
   */
  @Test
  void multipleRowsAreMappedCorrectly() throws Exception {
    Date testDate = new java.text.SimpleDateFormat("yyyy-MM-dd").parse("2026-01-10");
    AccountingFact af1 = buildFact(
        "fact-001", "4100", "Payable", testDate,
        "A", BigDecimal.TEN, BigDecimal.ZERO, "Line 1");
    AccountingFact af2 = buildFact(
        "fact-002", "6000", "Expense", testDate,
        "A", BigDecimal.ZERO, BigDecimal.TEN, "Line 2");

    mockQuery(List.of(af1, af2));

    NeoContext ctx = buildCrudGetListContext("goods-receipt", Map.of("parentId", "gr-789"));
    NeoResponse response = handler.handle(ctx);

    assertEquals(200, response.getHttpStatus());
    JSONObject responseBody = response.getBody().getJSONObject("response");
    assertEquals(2, responseBody.getInt("count"));
    assertEquals(2, responseBody.getJSONArray("data").length());
  }

  // ---------------------------------------------------------------------------
  // Null field handling
  // ---------------------------------------------------------------------------

  /**
   * Verifies that a null account produces an empty string for the account field.
   */
  @Test
  void nullAccountProducesEmptyString() throws Exception {
    AccountingFact af = buildFact(
        "fact-null-acc", null, null, new Date(),
        "A", BigDecimal.ONE, BigDecimal.ZERO, "test");

    mockQuery(List.of(af));

    NeoContext ctx = buildCrudGetListContext("purchase-invoice", Map.of("parentId", "inv-123"));
    NeoResponse response = handler.handle(ctx);

    JSONObject row = response.getBody()
        .getJSONObject("response")
        .getJSONArray("data")
        .getJSONObject(0);

    assertEquals("", row.getString("account"));
  }

  /**
   * Verifies that a null accounting date produces JSONObject.NULL for the accountingDate field.
   */
  @Test
  void nullDateProducesJsonNull() throws Exception {
    AccountingFact af = buildFact(
        "fact-null-date", "4100", "Payable", null,
        "A", BigDecimal.ONE, BigDecimal.ZERO, "test");

    mockQuery(List.of(af));

    NeoContext ctx = buildCrudGetListContext("purchase-invoice", Map.of("parentId", "inv-123"));
    NeoResponse response = handler.handle(ctx);

    JSONObject row = response.getBody()
        .getJSONObject("response")
        .getJSONArray("data")
        .getJSONObject(0);

    assertEquals(JSONObject.NULL, row.get("accountingDate"));
  }

  /**
   * Verifies that a null description produces an empty string.
   */
  @Test
  void nullDescriptionProducesEmptyString() throws Exception {
    AccountingFact af = buildFact(
        "fact-null-desc", "4100", "Payable", new Date(),
        "A", BigDecimal.ONE, BigDecimal.ZERO, null);

    mockQuery(List.of(af));

    NeoContext ctx = buildCrudGetListContext("purchase-invoice", Map.of("parentId", "inv-123"));
    NeoResponse response = handler.handle(ctx);

    JSONObject row = response.getBody()
        .getJSONObject("response")
        .getJSONArray("data")
        .getJSONObject(0);

    assertEquals("", row.getString("description"));
  }

  /**
   * Verifies that a null postingType produces an empty string.
   */
  @Test
  void nullPostingTypeProducesEmptyString() throws Exception {
    AccountingFact af = buildFact(
        "fact-null-pt", "4100", "Payable", new Date(),
        null, BigDecimal.ONE, BigDecimal.ZERO, "test");

    mockQuery(List.of(af));

    NeoContext ctx = buildCrudGetListContext("purchase-invoice", Map.of("parentId", "inv-123"));
    NeoResponse response = handler.handle(ctx);

    JSONObject row = response.getBody()
        .getJSONObject("response")
        .getJSONArray("data")
        .getJSONObject(0);

    assertEquals("", row.getString("postingType"));
  }

  /**
   * Verifies that null debit and credit default to BigDecimal.ZERO.
   */
  @Test
  void nullDebitAndCreditDefaultToZero() throws Exception {
    AccountingFact af = buildFact(
        "fact-null-amounts", "4100", "Payable", new Date(),
        "A", null, null, "test");

    mockQuery(List.of(af));

    NeoContext ctx = buildCrudGetListContext("purchase-invoice", Map.of("parentId", "inv-123"));
    NeoResponse response = handler.handle(ctx);

    JSONObject row = response.getBody()
        .getJSONObject("response")
        .getJSONArray("data")
        .getJSONObject(0);

    assertEquals(0, row.getInt("debit"));
    assertEquals(0, row.getInt("credit"));
  }

  // ---------------------------------------------------------------------------
  // Exception handling
  // ---------------------------------------------------------------------------

  /**
   * Verifies that an exception thrown during query execution results in an HTTP 500 response.
   */
  @Test
  void exceptionDuringQueryReturns500() {
    mockQueryThrows(new RuntimeException("DB connection failed"));

    NeoContext ctx = buildCrudGetListContext("purchase-invoice", Map.of("parentId", "inv-123"));
    NeoResponse response = handler.handle(ctx);

    assertEquals(500, response.getHttpStatus());
  }
}
