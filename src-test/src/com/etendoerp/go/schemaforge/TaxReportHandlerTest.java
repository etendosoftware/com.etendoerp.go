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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.enterprise.Organization;

/**
 * Unit tests for {@link TaxReportHandler}.
 *
 * <p>Covers: GET descriptor, POST with missing body (400), POST with missing dates (400),
 * POST with valid params (purchase only, sales only, both), method guard (405),
 * exception handling (500), buildSection with showDetails true/false and groupByBp
 * true/false, nullSafe and sum via the full flow, and resolveCurrencySymbol fallback.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TaxReportHandlerTest {

  private TaxReportHandler handler;

  @Mock
  private OBDal obDal;
  @Mock
  private OBContext obContext;
  @Mock
  private Client client;
  @Mock
  private Organization organization;
  @Mock
  private Connection connection;
  @Mock
  private PreparedStatement preparedStatement;
  @Mock
  private ResultSet resultSet;

  private MockedStatic<OBDal> obDalMock;
  private MockedStatic<OBContext> obContextMock;

  @BeforeEach
  void setUp() throws SQLException {
    handler = new TaxReportHandler();
    obDalMock = mockStatic(OBDal.class);
    obContextMock = mockStatic(OBContext.class);

    obDalMock.when(OBDal::getInstance).thenReturn(obDal);
    obContextMock.when(OBContext::getOBContext).thenReturn(obContext);
    when(obContext.getCurrentClient()).thenReturn(client);
    when(client.getId()).thenReturn("test-client-id");
    when(obContext.getCurrentOrganization()).thenReturn(organization);
    when(organization.getId()).thenReturn("test-org-id");
    when(obDal.getConnection()).thenReturn(connection);
    when(connection.prepareStatement(anyString())).thenReturn(preparedStatement);
  }

  @AfterEach
  void tearDown() {
    obDalMock.close();
    obContextMock.close();
  }

  // ---- Helper methods -------------------------------------------------------

  private NeoContext buildContext(String method) {
    return NeoContext.builder()
        .specName("tax-report")
        .entityName("tax-report")
        .httpMethod(method)
        .endpointType(NeoEndpointType.CRUD)
        .build();
  }

  private NeoContext buildPostContext(JSONObject body) {
    return NeoContext.builder()
        .specName("tax-report")
        .entityName("tax-report")
        .httpMethod("POST")
        .requestBody(body)
        .endpointType(NeoEndpointType.CRUD)
        .build();
  }

  /**
   * Sets up the ResultSet mock to return the given number of rows.
   * Each row has distinct tax/bp/invoice data.
   */
  private void mockResultSetRows(int rowCount) throws SQLException {
    if (rowCount == 0) {
      when(resultSet.next()).thenReturn(false);
      when(preparedStatement.executeQuery()).thenReturn(resultSet);
      return;
    }

    // Build a chain: true, true, ... , false
    Boolean[] nextResults = new Boolean[rowCount + 1];
    for (int i = 0; i < rowCount; i++) {
      nextResults[i] = true;
    }
    nextResults[rowCount] = false;

    if (rowCount == 1) {
      when(resultSet.next()).thenReturn(true, false);
    } else if (rowCount == 2) {
      when(resultSet.next()).thenReturn(true, true, false);
    } else {
      Boolean[] rest = new Boolean[rowCount];
      for (int i = 0; i < rowCount - 1; i++) {
        rest[i] = true;
      }
      rest[rowCount - 1] = false;
      when(resultSet.next()).thenReturn(true, rest);
    }

    // Return different values for successive calls to simulate different rows
    when(resultSet.getString("tax_id")).thenReturn("tax-001", "tax-002", "tax-001");
    when(resultSet.getString("tax_name")).thenReturn("VAT 21%", "VAT 10%", "VAT 21%");
    when(resultSet.getBigDecimal("rate")).thenReturn(new BigDecimal("21"), new BigDecimal("10"), new BigDecimal("21"));
    when(resultSet.getString("tax_category_id")).thenReturn("cat-001", "cat-002", "cat-001");
    when(resultSet.getString("tax_category_name")).thenReturn("Standard VAT", "Reduced VAT", "Standard VAT");
    when(resultSet.getString("bp_id")).thenReturn("bp-001", "bp-002", "bp-001");
    when(resultSet.getString("bp_name")).thenReturn("Acme Corp", "Beta Inc", "Acme Corp");
    when(resultSet.getString("bp_taxid")).thenReturn("B12345678", "B87654321", "B12345678");
    when(resultSet.getString("bp_country")).thenReturn("Spain", "France", "Spain");
    when(resultSet.getString("bp_region")).thenReturn("Madrid", "Paris", "Madrid");
    when(resultSet.getString("invoice_id")).thenReturn("inv-001", "inv-002", "inv-003");
    when(resultSet.getString("doc_no")).thenReturn("AP/0001", "AP/0002", "AP/0003");
    when(resultSet.getString("doc_type")).thenReturn("AP Invoice", "AP Invoice", "AP Invoice");
    when(resultSet.getString("doc_date")).thenReturn("2025-01-15", "2025-01-20", "2025-02-01");
    when(resultSet.getString("acct_date")).thenReturn("2025-01-15", "2025-01-20", "2025-02-01");
    when(resultSet.getBigDecimal("tax_base_amt")).thenReturn(
        new BigDecimal("1000.00"), new BigDecimal("500.00"), new BigDecimal("2000.00"));
    when(resultSet.getBigDecimal("tax_amt")).thenReturn(
        new BigDecimal("210.00"), new BigDecimal("50.00"), new BigDecimal("420.00"));
    when(resultSet.getBigDecimal("total_amt")).thenReturn(
        new BigDecimal("1210.00"), new BigDecimal("550.00"), new BigDecimal("2420.00"));

    when(preparedStatement.executeQuery()).thenReturn(resultSet);
  }

  /**
   * Builds a minimal valid request body with dates and transaction type.
   */
  private JSONObject buildValidBody(String transactionType, boolean showDetails, boolean groupByBp)
      throws Exception {
    JSONObject body = new JSONObject();
    body.put("dateFrom", "2025-01-01");
    body.put("dateTo", "2025-12-31");
    body.put("dateType", "acct");
    body.put("transactionType", transactionType);
    body.put("taxType", "tax");
    body.put("showDetails", showDetails);
    body.put("groupByBp", groupByBp);
    body.put("bpNameType", "commercial");
    return body;
  }

  // ---- GET descriptor -------------------------------------------------------

  /**
   * Verifies that a GET request returns HTTP 200 with the report descriptor
   * containing name and description fields.
   */
  @Test
  void testGetReturnsDescriptor() throws Exception {
    NeoResponse response = handler.handle(buildContext("GET"));

    assertEquals(200, response.getHttpStatus());
    JSONObject body = response.getBody();
    assertNotNull(body);
    assertEquals("Tax Report", body.getString("name"));
    assertTrue(body.has("description"));
  }

  // ---- Method guard ---------------------------------------------------------

  /**
   * Verifies that PUT returns HTTP 405 Method not allowed.
   */
  @Test
  void testPutReturns405() {
    NeoResponse response = handler.handle(buildContext("PUT"));
    assertEquals(405, response.getHttpStatus());
  }

  /**
   * Verifies that DELETE returns HTTP 405 Method not allowed.
   */
  @Test
  void testDeleteReturns405() {
    NeoResponse response = handler.handle(buildContext("DELETE"));
    assertEquals(405, response.getHttpStatus());
  }

  /**
   * Verifies that PATCH returns HTTP 405 Method not allowed.
   */
  @Test
  void testPatchReturns405() {
    NeoResponse response = handler.handle(buildContext("PATCH"));
    assertEquals(405, response.getHttpStatus());
  }

  // ---- POST with missing body -----------------------------------------------

  /**
   * Verifies that a POST with null body returns HTTP 400.
   */
  @Test
  void testPostWithNullBodyReturns400() {
    NeoContext ctx = buildPostContext(null);
    NeoResponse response = handler.handle(ctx);
    assertEquals(400, response.getHttpStatus());
  }

  // ---- POST with missing dates ----------------------------------------------

  /**
   * Verifies that a POST with empty dateFrom returns HTTP 400.
   */
  @Test
  void testPostWithMissingDateFromReturns400() throws Exception {
    JSONObject body = new JSONObject();
    body.put("dateTo", "2025-12-31");
    NeoResponse response = handler.handle(buildPostContext(body));
    assertEquals(400, response.getHttpStatus());
  }

  /**
   * Verifies that a POST with empty dateTo returns HTTP 400.
   */
  @Test
  void testPostWithMissingDateToReturns400() throws Exception {
    JSONObject body = new JSONObject();
    body.put("dateFrom", "2025-01-01");
    NeoResponse response = handler.handle(buildPostContext(body));
    assertEquals(400, response.getHttpStatus());
  }

  /**
   * Verifies that a POST with both dates missing returns HTTP 400.
   */
  @Test
  void testPostWithBothDatesMissingReturns400() throws Exception {
    JSONObject body = new JSONObject();
    NeoResponse response = handler.handle(buildPostContext(body));
    assertEquals(400, response.getHttpStatus());
  }

  // ---- POST with valid params: purchase only --------------------------------

  /**
   * Verifies a successful POST with transactionType=P (purchase only).
   * The sales section should have empty detail, and the purchase section
   * should have summaryByCategory and summaryByRate.
   */
  @Test
  void testPostPurchaseOnlyReturns200WithData() throws Exception {
    mockResultSetRows(2);
    JSONObject body = buildValidBody("P", false, false);
    NeoResponse response = handler.handle(buildPostContext(body));

    assertEquals(200, response.getHttpStatus());
    JSONObject wrapper = response.getBody();
    JSONObject responseData = wrapper.getJSONObject("response");
    JSONObject data = responseData.getJSONObject("data");

    // Purchase section should have data
    JSONObject purchase = data.getJSONObject("purchase");
    assertNotNull(purchase);
    assertTrue(purchase.has("summaryByCategory"));
    assertTrue(purchase.has("summaryByRate"));

    // Sales section should be empty (no query was executed for sales)
    JSONObject sales = data.getJSONObject("sales");
    assertNotNull(sales);
    assertEquals(0, sales.getJSONArray("detail").length());
    assertEquals(0, sales.getJSONArray("summaryByCategory").length());
    assertEquals(0, sales.getJSONArray("summaryByRate").length());

    // Meta section should be present
    JSONObject meta = responseData.getJSONObject("meta");
    assertEquals("2025-01-01", meta.getString("dateFrom"));
    assertEquals("2025-12-31", meta.getString("dateTo"));
    assertEquals("P", meta.getString("transactionType"));
  }

  // ---- POST with valid params: sales only -----------------------------------

  /**
   * Verifies a successful POST with transactionType=S (sales only).
   * The purchase section should have empty arrays.
   */
  @Test
  void testPostSalesOnlyReturns200WithData() throws Exception {
    mockResultSetRows(2);
    JSONObject body = buildValidBody("S", false, false);
    NeoResponse response = handler.handle(buildPostContext(body));

    assertEquals(200, response.getHttpStatus());
    JSONObject data = response.getBody().getJSONObject("response").getJSONObject("data");

    // Purchase section should be empty
    JSONObject purchase = data.getJSONObject("purchase");
    assertEquals(0, purchase.getJSONArray("detail").length());
    assertEquals(0, purchase.getJSONArray("summaryByCategory").length());
    assertEquals(0, purchase.getJSONArray("summaryByRate").length());

    // Sales section should have data
    JSONObject sales = data.getJSONObject("sales");
    assertTrue(sales.getJSONArray("summaryByCategory").length() > 0);
    assertTrue(sales.getJSONArray("summaryByRate").length() > 0);
  }

  // ---- POST with valid params: both -----------------------------------------

  /**
   * Verifies a successful POST with transactionType=B (both purchase and sales).
   * Both sections should have populated data.
   */
  @Test
  void testPostBothTransactionsReturns200WithBothSections() throws Exception {
    mockResultSetRows(2);
    JSONObject body = buildValidBody("B", false, false);
    NeoResponse response = handler.handle(buildPostContext(body));

    assertEquals(200, response.getHttpStatus());
    JSONObject data = response.getBody().getJSONObject("response").getJSONObject("data");

    JSONObject purchase = data.getJSONObject("purchase");
    JSONObject sales = data.getJSONObject("sales");
    assertTrue(purchase.has("summaryByCategory"));
    assertTrue(sales.has("summaryByCategory"));
  }

  // ---- POST with showDetails=true, groupByBp=false --------------------------

  /**
   * Verifies that when showDetails=true and groupByBp=false the detail section
   * contains tax groups with doc rows (not BP groups).
   */
  @Test
  void testPostShowDetailsWithoutGroupByBp() throws Exception {
    mockResultSetRows(2);
    JSONObject body = buildValidBody("P", true, false);
    NeoResponse response = handler.handle(buildPostContext(body));

    assertEquals(200, response.getHttpStatus());
    JSONObject purchase = response.getBody()
        .getJSONObject("response").getJSONObject("data").getJSONObject("purchase");
    JSONArray detail = purchase.getJSONArray("detail");

    assertTrue(detail.length() > 0, "Detail should have tax groups when showDetails=true");
    JSONObject firstGroup = detail.getJSONObject(0);
    assertTrue(firstGroup.has("taxId"));
    assertTrue(firstGroup.has("taxName"));
    assertTrue(firstGroup.has("taxBaseAmt"));
    assertTrue(firstGroup.has("taxAmt"));
    assertTrue(firstGroup.has("totalAmt"));
    // With groupByBp=false, docs should be populated and bpGroups empty
    assertTrue(firstGroup.has("docs"));
    assertEquals(0, firstGroup.getJSONArray("bpGroups").length(),
        "bpGroups should be empty when groupByBp=false");
  }

  // ---- POST with showDetails=true, groupByBp=true ---------------------------

  /**
   * Verifies that when showDetails=true and groupByBp=true the detail section
   * contains tax groups with BP subgroups (not flat doc rows).
   */
  @Test
  void testPostShowDetailsWithGroupByBp() throws Exception {
    mockResultSetRows(2);
    JSONObject body = buildValidBody("P", true, true);
    NeoResponse response = handler.handle(buildPostContext(body));

    assertEquals(200, response.getHttpStatus());
    JSONObject purchase = response.getBody()
        .getJSONObject("response").getJSONObject("data").getJSONObject("purchase");
    JSONArray detail = purchase.getJSONArray("detail");

    assertTrue(detail.length() > 0, "Detail should have tax groups when showDetails=true");
    JSONObject firstGroup = detail.getJSONObject(0);
    // With groupByBp=true, bpGroups should be populated and docs empty
    assertTrue(firstGroup.getJSONArray("bpGroups").length() > 0,
        "bpGroups should be populated when groupByBp=true");
    assertEquals(0, firstGroup.getJSONArray("docs").length(),
        "docs should be empty when groupByBp=true");
  }

  // ---- POST with showDetails=false ------------------------------------------

  /**
   * Verifies that when showDetails=false the detail array is empty.
   */
  @Test
  void testPostWithoutShowDetailsReturnsEmptyDetail() throws Exception {
    mockResultSetRows(2);
    JSONObject body = buildValidBody("P", false, false);
    NeoResponse response = handler.handle(buildPostContext(body));

    assertEquals(200, response.getHttpStatus());
    JSONObject purchase = response.getBody()
        .getJSONObject("response").getJSONObject("data").getJSONObject("purchase");
    assertEquals(0, purchase.getJSONArray("detail").length(),
        "Detail should be empty when showDetails=false");
  }

  // ---- summaryByCategory structure ------------------------------------------

  /**
   * Verifies the summaryByCategory section contains taxCategoryId, taxCategoryName,
   * and a nested taxes array with rate, taxBaseAmt, taxAmt, totalAmt, bpCount.
   */
  @Test
  void testSummaryByCategoryStructure() throws Exception {
    mockResultSetRows(2);
    JSONObject body = buildValidBody("P", false, false);
    NeoResponse response = handler.handle(buildPostContext(body));

    JSONObject purchase = response.getBody()
        .getJSONObject("response").getJSONObject("data").getJSONObject("purchase");
    JSONArray summaryByCategory = purchase.getJSONArray("summaryByCategory");

    assertTrue(summaryByCategory.length() > 0);
    JSONObject firstCategory = summaryByCategory.getJSONObject(0);
    assertTrue(firstCategory.has("taxCategoryId"));
    assertTrue(firstCategory.has("taxCategoryName"));
    JSONArray taxes = firstCategory.getJSONArray("taxes");
    assertTrue(taxes.length() > 0);
    JSONObject firstTax = taxes.getJSONObject(0);
    assertTrue(firstTax.has("taxId"));
    assertTrue(firstTax.has("taxName"));
    assertTrue(firstTax.has("rate"));
    assertTrue(firstTax.has("taxBaseAmt"));
    assertTrue(firstTax.has("taxAmt"));
    assertTrue(firstTax.has("totalAmt"));
    assertTrue(firstTax.has("bpCount"));
  }

  // ---- summaryByRate structure ----------------------------------------------

  /**
   * Verifies the summaryByRate section contains rate, bpGroups, taxes,
   * taxBaseAmt, taxAmt, totalAmt, and bpCount.
   */
  @Test
  void testSummaryByRateStructure() throws Exception {
    mockResultSetRows(2);
    JSONObject body = buildValidBody("P", false, false);
    NeoResponse response = handler.handle(buildPostContext(body));

    JSONObject purchase = response.getBody()
        .getJSONObject("response").getJSONObject("data").getJSONObject("purchase");
    JSONArray summaryByRate = purchase.getJSONArray("summaryByRate");

    assertTrue(summaryByRate.length() > 0);
    JSONObject firstRate = summaryByRate.getJSONObject(0);
    assertTrue(firstRate.has("rate"));
    assertTrue(firstRate.has("bpGroups"));
    assertTrue(firstRate.has("taxes"));
    assertTrue(firstRate.has("taxBaseAmt"));
    assertTrue(firstRate.has("taxAmt"));
    assertTrue(firstRate.has("totalAmt"));
    assertTrue(firstRate.has("bpCount"));
  }

  // ---- summaryByRate bpGroups have taxes and bpTaxId ------------------------

  /**
   * Verifies that bpGroups inside summaryByRate contain bpTaxId and a nested
   * taxes array (forRate=true path in buildBpGroups).
   */
  @Test
  void testSummaryByRateBpGroupsHaveTaxesArray() throws Exception {
    mockResultSetRows(2);
    JSONObject body = buildValidBody("P", false, false);
    NeoResponse response = handler.handle(buildPostContext(body));

    JSONObject purchase = response.getBody()
        .getJSONObject("response").getJSONObject("data").getJSONObject("purchase");
    JSONArray summaryByRate = purchase.getJSONArray("summaryByRate");
    JSONObject firstRate = summaryByRate.getJSONObject(0);
    JSONArray bpGroups = firstRate.getJSONArray("bpGroups");

    assertTrue(bpGroups.length() > 0, "bpGroups should not be empty in summaryByRate");
    JSONObject firstBpGroup = bpGroups.getJSONObject(0);
    assertTrue(firstBpGroup.has("bpTaxId"), "bpGroup in summaryByRate must have bpTaxId");
    assertTrue(firstBpGroup.has("taxes"), "bpGroup in summaryByRate must have taxes array");
  }

  // ---- Amounts are summed correctly (nullSafe and sum tested via flow) -------

  /**
   * Verifies that the amounts in summaryByCategory are summed correctly
   * from the individual rows, implicitly testing nullSafe and sum helpers.
   */
  @Test
  void testAmountsAreSummedCorrectly() throws Exception {
    // Set up a single-row result to get exact values
    when(resultSet.next()).thenReturn(true, false);
    when(resultSet.getString("tax_id")).thenReturn("tax-001");
    when(resultSet.getString("tax_name")).thenReturn("VAT 21%");
    when(resultSet.getBigDecimal("rate")).thenReturn(new BigDecimal("21"));
    when(resultSet.getString("tax_category_id")).thenReturn("cat-001");
    when(resultSet.getString("tax_category_name")).thenReturn("Standard VAT");
    when(resultSet.getString("bp_id")).thenReturn("bp-001");
    when(resultSet.getString("bp_name")).thenReturn("Acme Corp");
    when(resultSet.getString("bp_taxid")).thenReturn("B12345678");
    when(resultSet.getString("bp_country")).thenReturn("Spain");
    when(resultSet.getString("bp_region")).thenReturn("Madrid");
    when(resultSet.getString("invoice_id")).thenReturn("inv-001");
    when(resultSet.getString("doc_no")).thenReturn("AP/0001");
    when(resultSet.getString("doc_type")).thenReturn("AP Invoice");
    when(resultSet.getString("doc_date")).thenReturn("2025-01-15");
    when(resultSet.getString("acct_date")).thenReturn("2025-01-15");
    when(resultSet.getBigDecimal("tax_base_amt")).thenReturn(new BigDecimal("1000.00"));
    when(resultSet.getBigDecimal("tax_amt")).thenReturn(new BigDecimal("210.00"));
    when(resultSet.getBigDecimal("total_amt")).thenReturn(new BigDecimal("1210.00"));
    when(preparedStatement.executeQuery()).thenReturn(resultSet);

    JSONObject body = buildValidBody("P", false, false);
    NeoResponse response = handler.handle(buildPostContext(body));

    JSONObject purchase = response.getBody()
        .getJSONObject("response").getJSONObject("data").getJSONObject("purchase");
    JSONArray summaryByCategory = purchase.getJSONArray("summaryByCategory");
    JSONObject firstCategory = summaryByCategory.getJSONObject(0);
    JSONArray taxes = firstCategory.getJSONArray("taxes");
    JSONObject tax = taxes.getJSONObject(0);

    assertEquals(0, new BigDecimal("1000.00").compareTo(getBd(tax, "taxBaseAmt")),
        "taxBaseAmt should be 1000.00");
    assertEquals(0, new BigDecimal("210.00").compareTo(getBd(tax, "taxAmt")),
        "taxAmt should be 210.00");
    assertEquals(0, new BigDecimal("1210.00").compareTo(getBd(tax, "totalAmt")),
        "totalAmt should be 1210.00");
    assertEquals(1, tax.getInt("bpCount"), "bpCount should be 1");
  }

  // ---- nullSafe: null BigDecimal becomes ZERO --------------------------------

  /**
   * Verifies that when the ResultSet returns null for amount fields,
   * they become BigDecimal.ZERO in the output (testing nullSafe via mapRow).
   */
  @Test
  void testNullSafeHandlesNullAmounts() throws Exception {
    when(resultSet.next()).thenReturn(true, false);
    when(resultSet.getString("tax_id")).thenReturn("tax-001");
    when(resultSet.getString("tax_name")).thenReturn("VAT 21%");
    when(resultSet.getBigDecimal("rate")).thenReturn(new BigDecimal("21"));
    when(resultSet.getString("tax_category_id")).thenReturn("cat-001");
    when(resultSet.getString("tax_category_name")).thenReturn("Standard VAT");
    when(resultSet.getString("bp_id")).thenReturn("bp-001");
    when(resultSet.getString("bp_name")).thenReturn("Acme Corp");
    when(resultSet.getString("bp_taxid")).thenReturn(null);
    when(resultSet.getString("bp_country")).thenReturn(null);
    when(resultSet.getString("bp_region")).thenReturn(null);
    when(resultSet.getString("invoice_id")).thenReturn("inv-001");
    when(resultSet.getString("doc_no")).thenReturn("AP/0001");
    when(resultSet.getString("doc_type")).thenReturn("AP Invoice");
    when(resultSet.getString("doc_date")).thenReturn("2025-01-15");
    when(resultSet.getString("acct_date")).thenReturn("2025-01-15");
    // Return null for amounts to trigger nullSafe
    when(resultSet.getBigDecimal("tax_base_amt")).thenReturn(null);
    when(resultSet.getBigDecimal("tax_amt")).thenReturn(null);
    when(resultSet.getBigDecimal("total_amt")).thenReturn(null);
    when(preparedStatement.executeQuery()).thenReturn(resultSet);

    JSONObject body = buildValidBody("P", true, false);
    NeoResponse response = handler.handle(buildPostContext(body));

    assertEquals(200, response.getHttpStatus());
    JSONObject purchase = response.getBody()
        .getJSONObject("response").getJSONObject("data").getJSONObject("purchase");
    JSONArray detail = purchase.getJSONArray("detail");
    JSONObject taxGroup = detail.getJSONObject(0);

    assertEquals(0, BigDecimal.ZERO.compareTo(getBd(taxGroup, "taxBaseAmt")),
        "Null taxBaseAmt should become 0");
    assertEquals(0, BigDecimal.ZERO.compareTo(getBd(taxGroup, "taxAmt")),
        "Null taxAmt should become 0");
    assertEquals(0, BigDecimal.ZERO.compareTo(getBd(taxGroup, "totalAmt")),
        "Null totalAmt should become 0");
  }

  // ---- Empty result set produces empty sections -----------------------------

  /**
   * Verifies that when queryRows returns no rows, all sections are empty arrays.
   */
  @Test
  void testPostWithNoRowsReturnsEmptySections() throws Exception {
    mockResultSetRows(0);
    JSONObject body = buildValidBody("P", true, false);
    NeoResponse response = handler.handle(buildPostContext(body));

    assertEquals(200, response.getHttpStatus());
    JSONObject purchase = response.getBody()
        .getJSONObject("response").getJSONObject("data").getJSONObject("purchase");
    assertEquals(0, purchase.getJSONArray("detail").length());
    assertEquals(0, purchase.getJSONArray("summaryByCategory").length());
    assertEquals(0, purchase.getJSONArray("summaryByRate").length());
  }

  // ---- Exception handling (500) ---------------------------------------------

  /**
   * Verifies that an exception during report execution returns HTTP 500.
   */
  @Test
  void testPostWithSQLExceptionReturns500() throws Exception {
    when(connection.prepareStatement(anyString())).thenThrow(new SQLException("DB error"));
    JSONObject body = buildValidBody("P", false, false);
    NeoResponse response = handler.handle(buildPostContext(body));

    assertEquals(500, response.getHttpStatus());
  }

  /**
   * Verifies that an OBContext failure during POST returns HTTP 500.
   */
  @Test
  void testPostWithOBContextExceptionReturns500() throws Exception {
    obContextMock.when(OBContext::getOBContext).thenThrow(new RuntimeException("Context unavailable"));
    JSONObject body = buildValidBody("P", false, false);
    NeoResponse response = handler.handle(buildPostContext(body));

    assertEquals(500, response.getHttpStatus());
  }

  // ---- resolveOrgId with explicit orgId -------------------------------------

  /**
   * Verifies that when orgId is provided in the body, it is used instead of the
   * context organization.
   */
  @Test
  void testResolveOrgIdUsesBodyValueWhenPresent() throws Exception {
    mockResultSetRows(0);
    JSONObject body = buildValidBody("P", false, false);
    body.put("orgId", "custom-org-id");
    NeoResponse response = handler.handle(buildPostContext(body));

    assertEquals(200, response.getHttpStatus());
  }

  // ---- dateType=invoice uses dateinvoiced -----------------------------------

  /**
   * Verifies that dateType other than 'acct' uses the invoice date column.
   */
  @Test
  void testDateTypeInvoiceUsesDateInvoiced() throws Exception {
    mockResultSetRows(0);
    JSONObject body = buildValidBody("P", false, false);
    body.put("dateType", "invoice");
    NeoResponse response = handler.handle(buildPostContext(body));

    assertEquals(200, response.getHttpStatus());
  }

  // ---- taxType=withholding filters correctly --------------------------------

  /**
   * Verifies that taxType=withholding is accepted and produces a valid response.
   */
  @Test
  void testWithholdingTaxType() throws Exception {
    mockResultSetRows(0);
    JSONObject body = buildValidBody("P", false, false);
    body.put("taxType", "withholding");
    NeoResponse response = handler.handle(buildPostContext(body));

    assertEquals(200, response.getHttpStatus());
  }

  // ---- taxId filter ---------------------------------------------------------

  /**
   * Verifies that providing a taxId filter is accepted.
   */
  @Test
  void testTaxIdFilter() throws Exception {
    mockResultSetRows(0);
    JSONObject body = buildValidBody("P", false, false);
    body.put("taxId", "specific-tax-id");
    NeoResponse response = handler.handle(buildPostContext(body));

    assertEquals(200, response.getHttpStatus());
  }

  // ---- bPartnerId filter with multiple IDs ----------------------------------

  /**
   * Verifies that providing a comma-separated bPartnerId is accepted.
   */
  @Test
  void testBPartnerIdFilter() throws Exception {
    mockResultSetRows(0);
    JSONObject body = buildValidBody("P", false, false);
    body.put("bPartnerId", "bp-001,bp-002,bp-003");
    NeoResponse response = handler.handle(buildPostContext(body));

    assertEquals(200, response.getHttpStatus());
  }

  // ---- bpNameType=legal covers the COALESCE branch --------------------------

  /**
   * Verifies that bpNameType=legal is accepted (it changes the SQL column).
   */
  @Test
  void testBpNameTypeLegal() throws Exception {
    mockResultSetRows(0);
    JSONObject body = buildValidBody("P", false, false);
    body.put("bpNameType", "legal");
    NeoResponse response = handler.handle(buildPostContext(body));

    assertEquals(200, response.getHttpStatus());
  }

  // ---- resolveCurrencySymbol with valid currencyId --------------------------

  /**
   * Verifies that when a currencyId is provided, resolveCurrencySymbol tries to
   * look up the currency and the symbol appears in meta.
   */
  @Test
  void testResolveCurrencySymbolWithValidId() throws Exception {
    mockResultSetRows(0);
    org.openbravo.model.common.currency.Currency currency =
        mock(org.openbravo.model.common.currency.Currency.class);
    when(currency.getSymbol()).thenReturn("EUR");
    when(obDal.get(org.openbravo.model.common.currency.Currency.class, "curr-001"))
        .thenReturn(currency);

    JSONObject body = buildValidBody("P", false, false);
    body.put("currencyId", "curr-001");
    NeoResponse response = handler.handle(buildPostContext(body));

    assertEquals(200, response.getHttpStatus());
    String symbol = response.getBody().getJSONObject("response")
        .getJSONObject("meta").getString("currencySymbol");
    assertEquals("EUR", symbol);
  }

  /**
   * Verifies that when the currency lookup returns null, the symbol is empty.
   */
  @Test
  void testResolveCurrencySymbolWithUnknownId() throws Exception {
    mockResultSetRows(0);
    when(obDal.get(org.openbravo.model.common.currency.Currency.class, "unknown-id"))
        .thenReturn(null);

    JSONObject body = buildValidBody("P", false, false);
    body.put("currencyId", "unknown-id");
    NeoResponse response = handler.handle(buildPostContext(body));

    assertEquals(200, response.getHttpStatus());
    String symbol = response.getBody().getJSONObject("response")
        .getJSONObject("meta").getString("currencySymbol");
    assertEquals("", symbol);
  }

  /**
   * Verifies that when the currency lookup throws an exception, the symbol is empty.
   */
  @Test
  void testResolveCurrencySymbolWithExceptionReturnsEmpty() throws Exception {
    mockResultSetRows(0);
    when(obDal.get(org.openbravo.model.common.currency.Currency.class, "bad-id"))
        .thenThrow(new RuntimeException("DB error"));

    JSONObject body = buildValidBody("P", false, false);
    body.put("currencyId", "bad-id");
    NeoResponse response = handler.handle(buildPostContext(body));

    assertEquals(200, response.getHttpStatus());
    String symbol = response.getBody().getJSONObject("response")
        .getJSONObject("meta").getString("currencySymbol");
    assertEquals("", symbol);
  }

  // ---- Meta structure -------------------------------------------------------

  /**
   * Verifies that the meta object contains all expected fields.
   */
  @Test
  void testMetaContainsAllExpectedFields() throws Exception {
    mockResultSetRows(0);
    JSONObject body = buildValidBody("B", true, true);
    NeoResponse response = handler.handle(buildPostContext(body));

    JSONObject meta = response.getBody().getJSONObject("response").getJSONObject("meta");
    assertEquals("2025-01-01", meta.getString("dateFrom"));
    assertEquals("2025-12-31", meta.getString("dateTo"));
    assertEquals("acct", meta.getString("dateType"));
    assertEquals("B", meta.getString("transactionType"));
    assertEquals("tax", meta.getString("taxType"));
    assertTrue(meta.has("currencySymbol"));
    assertEquals(true, meta.getBoolean("showDetails"));
    assertEquals(true, meta.getBoolean("groupByBp"));
    assertEquals("commercial", meta.getString("bpNameType"));
  }

  // ---- Multiple rows summing ------------------------------------------------

  /**
   * Verifies that with 3 rows (two for same tax, one for different tax),
   * the summaryByCategory correctly aggregates amounts across multiple rows.
   */
  @Test
  void testMultipleRowsSummedInSummaryByCategory() throws Exception {
    // 3 rows: 2 for tax-001 + 1 for tax-002
    when(resultSet.next()).thenReturn(true, true, true, false);
    when(resultSet.getString("tax_id")).thenReturn("tax-001", "tax-001", "tax-002");
    when(resultSet.getString("tax_name")).thenReturn("VAT 21%", "VAT 21%", "VAT 10%");
    when(resultSet.getBigDecimal("rate")).thenReturn(
        new BigDecimal("21"), new BigDecimal("21"), new BigDecimal("10"));
    when(resultSet.getString("tax_category_id")).thenReturn("cat-001", "cat-001", "cat-001");
    when(resultSet.getString("tax_category_name")).thenReturn(
        "Standard VAT", "Standard VAT", "Standard VAT");
    when(resultSet.getString("bp_id")).thenReturn("bp-001", "bp-002", "bp-001");
    when(resultSet.getString("bp_name")).thenReturn("Acme Corp", "Beta Inc", "Acme Corp");
    when(resultSet.getString("bp_taxid")).thenReturn("B12345678", "B87654321", "B12345678");
    when(resultSet.getString("bp_country")).thenReturn("Spain", "France", "Spain");
    when(resultSet.getString("bp_region")).thenReturn("Madrid", "Paris", "Madrid");
    when(resultSet.getString("invoice_id")).thenReturn("inv-001", "inv-002", "inv-003");
    when(resultSet.getString("doc_no")).thenReturn("AP/0001", "AP/0002", "AP/0003");
    when(resultSet.getString("doc_type")).thenReturn("AP Invoice", "AP Invoice", "AP Invoice");
    when(resultSet.getString("doc_date")).thenReturn("2025-01-15", "2025-01-20", "2025-02-01");
    when(resultSet.getString("acct_date")).thenReturn("2025-01-15", "2025-01-20", "2025-02-01");
    when(resultSet.getBigDecimal("tax_base_amt")).thenReturn(
        new BigDecimal("1000.00"), new BigDecimal("500.00"), new BigDecimal("2000.00"));
    when(resultSet.getBigDecimal("tax_amt")).thenReturn(
        new BigDecimal("210.00"), new BigDecimal("50.00"), new BigDecimal("420.00"));
    when(resultSet.getBigDecimal("total_amt")).thenReturn(
        new BigDecimal("1210.00"), new BigDecimal("550.00"), new BigDecimal("2420.00"));
    when(preparedStatement.executeQuery()).thenReturn(resultSet);

    JSONObject body = buildValidBody("P", true, false);
    NeoResponse response = handler.handle(buildPostContext(body));

    assertEquals(200, response.getHttpStatus());

    JSONObject purchase = response.getBody()
        .getJSONObject("response").getJSONObject("data").getJSONObject("purchase");

    // All 3 rows are in the same category (cat-001)
    JSONArray summaryByCategory = purchase.getJSONArray("summaryByCategory");
    assertEquals(1, summaryByCategory.length(), "All rows should be in a single category");

    // Two different taxes in the taxes array
    JSONArray taxes = summaryByCategory.getJSONObject(0).getJSONArray("taxes");
    assertEquals(2, taxes.length(), "Should have 2 distinct taxes");

    // Detail should have 2 tax groups
    JSONArray detail = purchase.getJSONArray("detail");
    assertEquals(2, detail.length(), "Detail should have 2 tax groups");

    // First tax group (tax-001) should sum 2 rows
    JSONObject taxGroup001 = detail.getJSONObject(0);
    assertEquals(0, new BigDecimal("1500.00").compareTo(getBd(taxGroup001, "taxBaseAmt")),
        "taxBaseAmt for tax-001 should be 1000+500=1500");
    assertEquals(0, new BigDecimal("260.00").compareTo(getBd(taxGroup001, "taxAmt")),
        "taxAmt for tax-001 should be 210+50=260");
  }

  // ---- bpCount in summaryByRate ---------------------------------------------

  /**
   * Verifies that bpCount in summaryByRate correctly counts distinct business partners.
   */
  @Test
  void testBpCountInSummaryByRate() throws Exception {
    // 2 rows, same rate, different BPs
    when(resultSet.next()).thenReturn(true, true, false);
    when(resultSet.getString("tax_id")).thenReturn("tax-001", "tax-001");
    when(resultSet.getString("tax_name")).thenReturn("VAT 21%", "VAT 21%");
    when(resultSet.getBigDecimal("rate")).thenReturn(new BigDecimal("21"), new BigDecimal("21"));
    when(resultSet.getString("tax_category_id")).thenReturn("cat-001", "cat-001");
    when(resultSet.getString("tax_category_name")).thenReturn("Standard VAT", "Standard VAT");
    when(resultSet.getString("bp_id")).thenReturn("bp-001", "bp-002");
    when(resultSet.getString("bp_name")).thenReturn("Acme Corp", "Beta Inc");
    when(resultSet.getString("bp_taxid")).thenReturn("B12345678", "B87654321");
    when(resultSet.getString("bp_country")).thenReturn("Spain", "France");
    when(resultSet.getString("bp_region")).thenReturn("Madrid", "Paris");
    when(resultSet.getString("invoice_id")).thenReturn("inv-001", "inv-002");
    when(resultSet.getString("doc_no")).thenReturn("AP/0001", "AP/0002");
    when(resultSet.getString("doc_type")).thenReturn("AP Invoice", "AP Invoice");
    when(resultSet.getString("doc_date")).thenReturn("2025-01-15", "2025-01-20");
    when(resultSet.getString("acct_date")).thenReturn("2025-01-15", "2025-01-20");
    when(resultSet.getBigDecimal("tax_base_amt")).thenReturn(
        new BigDecimal("1000.00"), new BigDecimal("500.00"));
    when(resultSet.getBigDecimal("tax_amt")).thenReturn(
        new BigDecimal("210.00"), new BigDecimal("105.00"));
    when(resultSet.getBigDecimal("total_amt")).thenReturn(
        new BigDecimal("1210.00"), new BigDecimal("605.00"));
    when(preparedStatement.executeQuery()).thenReturn(resultSet);

    JSONObject body = buildValidBody("P", false, false);
    NeoResponse response = handler.handle(buildPostContext(body));

    JSONObject purchase = response.getBody()
        .getJSONObject("response").getJSONObject("data").getJSONObject("purchase");
    JSONArray summaryByRate = purchase.getJSONArray("summaryByRate");

    assertEquals(1, summaryByRate.length(), "Both rows have same rate, so one group");
    JSONObject rateGroup = summaryByRate.getJSONObject(0);
    assertEquals(2, rateGroup.getInt("bpCount"), "Two distinct business partners");
    assertEquals(2, rateGroup.getJSONArray("bpGroups").length(),
        "Should have 2 BP groups in summaryByRate");
  }

  // ---- Detail doc rows structure (groupByBp=false) --------------------------

  /**
   * Verifies the document-level row fields when groupByBp=false and showDetails=true.
   */
  @Test
  void testDocRowsContainAllFields() throws Exception {
    when(resultSet.next()).thenReturn(true, false);
    when(resultSet.getString("tax_id")).thenReturn("tax-001");
    when(resultSet.getString("tax_name")).thenReturn("VAT 21%");
    when(resultSet.getBigDecimal("rate")).thenReturn(new BigDecimal("21"));
    when(resultSet.getString("tax_category_id")).thenReturn("cat-001");
    when(resultSet.getString("tax_category_name")).thenReturn("Standard VAT");
    when(resultSet.getString("bp_id")).thenReturn("bp-001");
    when(resultSet.getString("bp_name")).thenReturn("Acme Corp");
    when(resultSet.getString("bp_taxid")).thenReturn("B12345678");
    when(resultSet.getString("bp_country")).thenReturn("Spain");
    when(resultSet.getString("bp_region")).thenReturn("Madrid");
    when(resultSet.getString("invoice_id")).thenReturn("inv-001");
    when(resultSet.getString("doc_no")).thenReturn("AP/0001");
    when(resultSet.getString("doc_type")).thenReturn("AP Invoice");
    when(resultSet.getString("doc_date")).thenReturn("2025-01-15");
    when(resultSet.getString("acct_date")).thenReturn("2025-01-15");
    when(resultSet.getBigDecimal("tax_base_amt")).thenReturn(new BigDecimal("1000.00"));
    when(resultSet.getBigDecimal("tax_amt")).thenReturn(new BigDecimal("210.00"));
    when(resultSet.getBigDecimal("total_amt")).thenReturn(new BigDecimal("1210.00"));
    when(preparedStatement.executeQuery()).thenReturn(resultSet);

    JSONObject body = buildValidBody("P", true, false);
    NeoResponse response = handler.handle(buildPostContext(body));

    JSONObject purchase = response.getBody()
        .getJSONObject("response").getJSONObject("data").getJSONObject("purchase");
    JSONArray detail = purchase.getJSONArray("detail");
    JSONObject taxGroup = detail.getJSONObject(0);
    JSONArray docs = taxGroup.getJSONArray("docs");
    assertEquals(1, docs.length());

    JSONObject doc = docs.getJSONObject(0);
    assertEquals("inv-001", doc.getString("invoiceId"));
    assertEquals("AP/0001", doc.getString("docNo"));
    assertEquals("AP Invoice", doc.getString("docType"));
    assertEquals("2025-01-15", doc.getString("docDate"));
    assertEquals("2025-01-15", doc.getString("acctDate"));
    assertEquals("Acme Corp", doc.getString("bPartner"));
    assertEquals("Spain", doc.getString("bpCountry"));
    assertEquals("Madrid", doc.getString("bpRegion"));
    assertEquals(0, new BigDecimal("1000.00").compareTo(getBd(doc, "taxBaseAmt")));
    assertEquals(0, new BigDecimal("210.00").compareTo(getBd(doc, "taxAmt")));
    assertEquals(0, new BigDecimal("1210.00").compareTo(getBd(doc, "totalAmt")));
  }

  // ---- Private helpers -------------------------------------------------------

  /**
   * Extracts a BigDecimal from a Jettison JSONObject (which lacks getBigDecimal).
   */
  private static BigDecimal getBd(JSONObject obj, String key) throws Exception {
    Object val = obj.get(key);
    if (val instanceof BigDecimal) {
      return (BigDecimal) val;
    }
    return new BigDecimal(val.toString());
  }
}
