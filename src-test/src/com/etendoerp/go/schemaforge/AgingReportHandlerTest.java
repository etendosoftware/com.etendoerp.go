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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.openbravo.advpaymentmngt.utility.FIN_Utility;
import org.openbravo.dal.security.OrganizationStructureProvider;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBQuery;
import org.openbravo.dal.service.OBDal;
import org.openbravo.data.FieldProvider;
import org.openbravo.erpCommon.ad_reports.AgingDao;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.financialmgmt.accounting.coa.AcctSchema;

import com.etendoerp.go.schemaforge.util.NeoAccessHelper;
import com.etendoerp.go.schemaforge.util.NeoReportParam;

/**
 * Unit tests for {@link AgingReportHandler}.
 * Covers pure-logic helpers: bucket resolution, BP IN clause, date parsing,
 * summary/detail row building, meta building, and the describe/handle entry point.
 */
class AgingReportHandlerTest {

  private AgingReportHandler handler;

  @BeforeEach
  void setUp() {
    handler = new AgingReportHandler();
  }

  // Reflection helpers
  private static Object invokePrivate(Object target, String methodName,
      Class<?>[] paramTypes, Object... args) throws Exception {
    Method m = target.getClass().getDeclaredMethod(methodName, paramTypes);
    m.setAccessible(true);
    return m.invoke(target, args);
  }

  private static Object invokeStatic(String methodName, Class<?>[] paramTypes,
      Object... args) throws Exception {
    Method m = AgingReportHandler.class.getDeclaredMethod(methodName, paramTypes);
    m.setAccessible(true);
    return m.invoke(null, args);
  }

  // Access private inner class BucketConfig
  private static Object newBucketConfig(String c1, String c2, String c3, String c4, int active)
      throws Exception {
    Class<?> bucketClass = Class.forName(
        "com.etendoerp.go.schemaforge.AgingReportHandler$BucketConfig");
    Constructor<?> ctor = bucketClass.getDeclaredConstructors()[0];
    ctor.setAccessible(true);
    return ctor.newInstance(c1, c2, c3, c4, active);
  }

  private static String getBucketField(Object bucket, String methodName) throws Exception {
    Method m = bucket.getClass().getDeclaredMethod(methodName);
    m.setAccessible(true);
    return (String) m.invoke(bucket);
  }

  private static int getBucketActiveBuckets(Object bucket) throws Exception {
    java.lang.reflect.Field f = bucket.getClass().getDeclaredField("activeBuckets");
    f.setAccessible(true);
    return f.getInt(bucket);
  }

  private static String getBucketRawField(Object bucket, String fieldName) throws Exception {
    java.lang.reflect.Field f = bucket.getClass().getDeclaredField(fieldName);
    f.setAccessible(true);
    return (String) f.get(bucket);
  }

  // Simple FieldProvider stub
  private static FieldProvider stubFieldProvider(java.util.Map<String, String> fields) {
    return fieldName -> fields.getOrDefault(fieldName, "");
  }

  // -------------------------------------------------------------------------
  // handle() entry point
  // -------------------------------------------------------------------------

  /** Stubs {@code NeoAccessHelper.hasObuiappProcessAccess} to grant access for the aging-receivable process. */
  private MockedStatic<NeoAccessHelper> mockAccessGranted() {
    MockedStatic<NeoAccessHelper> accessMock = mockStatic(NeoAccessHelper.class);
    accessMock.when(() -> NeoAccessHelper.hasObuiappProcessAccess(anyString())).thenReturn(true);
    return accessMock;
  }

  @Nested
  @DisplayName("handle")
  class Handle {

    @Test
    @DisplayName("GET returns describe response with parameters")
    void getReturnsDescribe() {
      try (MockedStatic<NeoAccessHelper> accessMock = mockAccessGranted()) {
        NeoContext ctx = NeoContext.builder().httpMethod("GET").build();
        NeoResponse result = handler.handle(ctx);

        assertEquals(200, result.getHttpStatus());
        JSONObject body = result.getBody();
        assertNotNull(body);
        assertTrue(body.has("name"));
        assertTrue(body.has("parameters"));
      }
    }

    @Test
    @DisplayName("POST without body returns 400")
    void postWithoutBodyReturns400() {
      try (MockedStatic<NeoAccessHelper> accessMock = mockAccessGranted()) {
        NeoContext ctx = NeoContext.builder().httpMethod("POST").build();
        NeoResponse result = handler.handle(ctx);

        assertEquals(400, result.getHttpStatus());
      }
    }

    @Test
    @DisplayName("DELETE returns 405")
    void deleteReturns405() {
      try (MockedStatic<NeoAccessHelper> accessMock = mockAccessGranted()) {
        NeoContext ctx = NeoContext.builder().httpMethod("DELETE").build();
        NeoResponse result = handler.handle(ctx);

        assertEquals(405, result.getHttpStatus());
      }
    }

    @Test
    @DisplayName("PUT returns 405")
    void putReturns405() {
      try (MockedStatic<NeoAccessHelper> accessMock = mockAccessGranted()) {
        NeoContext ctx = NeoContext.builder().httpMethod("PUT").build();
        NeoResponse result = handler.handle(ctx);

        assertEquals(405, result.getHttpStatus());
      }
    }

    /**
     * When the current role does not have {@code hasObuiappProcessAccess} for the "Aging
     * Balance Process Definition for Receivables" OBUIAPP process, {@code handle} must deny
     * with a 403 before doing anything else — even for a well-formed POST body that would
     * otherwise reach {@link AgingDao}. Verified via {@link MockedConstruction}: if the guard
     * did not short-circuit, {@code executeReport} would construct an {@code AgingDao}
     * instance, so an empty construction list proves the business logic was never reached.
     */
    @Test
    @DisplayName("Access denied short-circuits before any AgingDao work (POST)")
    void handleReturns403WhenAccessDenied() {
      try (MockedStatic<NeoAccessHelper> accessMock = mockStatic(NeoAccessHelper.class);
           MockedConstruction<AgingDao> daoConstruction = mockConstruction(AgingDao.class)) {
        accessMock.when(() -> NeoAccessHelper.hasObuiappProcessAccess(anyString()))
            .thenReturn(false);

        JSONObject body = new JSONObject();
        try {
          body.put("recOrPay", "RECEIVABLES");
        } catch (Exception e) {
          throw new IllegalStateException(e);
        }
        NeoContext ctx = NeoContext.builder().httpMethod("POST").requestBody(body).build();

        NeoResponse result = handler.handle(ctx);

        assertEquals(403, result.getHttpStatus());
        assertTrue(daoConstruction.constructed().isEmpty());
      }
    }

    /**
     * Same denial for a GET (describe) request — the guard must gate every HTTP method, not
     * just POST.
     */
    @Test
    @DisplayName("Access denied short-circuits before describeReport (GET)")
    void handleGetReturns403WhenAccessDenied() {
      try (MockedStatic<NeoAccessHelper> accessMock = mockStatic(NeoAccessHelper.class)) {
        accessMock.when(() -> NeoAccessHelper.hasObuiappProcessAccess(anyString()))
            .thenReturn(false);

        NeoContext ctx = NeoContext.builder().httpMethod("GET").build();
        NeoResponse result = handler.handle(ctx);

        assertEquals(403, result.getHttpStatus());
        JSONObject body = result.getBody();
        assertNotNull(body);
        assertFalse(body.has("parameters"));
      }
    }

    /**
     * AgingDao creates a HashSet from the payment statuses returned by FIN_Utility. A null
     * status collection is a missing server prerequisite, not an invalid MCP parameter, and
     * must therefore be reported before the DAO is constructed.
     */
    @Test
    @DisplayName("Missing confirmed payment statuses returns an actionable 422")
    void missingConfirmedPaymentStatusesReturns422() throws Exception {
      try (MockedStatic<NeoAccessHelper> accessMock = mockAccessGranted();
           MockedStatic<FIN_Utility> paymentStatusMock = mockStatic(FIN_Utility.class);
           MockedConstruction<AgingDao> daoConstruction = mockConstruction(AgingDao.class)) {
        paymentStatusMock.when(FIN_Utility::getListPaymentConfirmed).thenReturn(null);

        JSONObject body = new JSONObject();
        body.put("recOrPay", "RECEIVABLES");
        NeoResponse result = handler.handle(
            NeoContext.builder().httpMethod("POST").requestBody(body).build());

        assertEquals(422, result.getHttpStatus());
        assertTrue(result.getBody().getJSONObject("error").getString("message")
            .contains("confirmed payment statuses"));
        assertTrue(daoConstruction.constructed().isEmpty());
      }
    }

    @Test
    @DisplayName("Unresolvable organization returns an actionable 400")
    void unresolvableOrganizationReturns400() throws Exception {
      OBDal dal = mock(OBDal.class);
      try (MockedStatic<NeoAccessHelper> accessMock = mockAccessGranted();
           MockedStatic<FIN_Utility> paymentStatusMock = mockStatic(FIN_Utility.class);
           MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class);
           MockedConstruction<AgingDao> daoConstruction = mockConstruction(AgingDao.class)) {
        paymentStatusMock.when(FIN_Utility::getListPaymentConfirmed)
            .thenReturn(Collections.singletonList("RPR"));
        obDalMock.when(OBDal::getInstance).thenReturn(dal);
        when(dal.get(Organization.class, "org-id")).thenReturn(null);

        JSONObject body = new JSONObject();
        body.put("recOrPay", "RECEIVABLES");
        body.put("orgId", "org-id");
        NeoResponse result = handler.handle(
            NeoContext.builder().httpMethod("POST").requestBody(body).build());

        assertEquals(400, result.getHttpStatus());
        assertTrue(result.getBody().getJSONObject("error").getString("message")
            .contains("organization context"));
        assertTrue(daoConstruction.constructed().isEmpty());
      }
    }

    @Test
    @DisplayName("Missing organization tree returns an actionable 422")
    void missingOrganizationTreeReturns422() throws Exception {
      OBDal dal = mock(OBDal.class);
      try (MockedStatic<NeoAccessHelper> accessMock = mockAccessGranted();
           MockedStatic<FIN_Utility> paymentStatusMock = mockStatic(FIN_Utility.class);
           MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class);
           MockedConstruction<OrganizationStructureProvider> orgProviderConstruction =
               mockConstruction(OrganizationStructureProvider.class, (provider, ignored) ->
                   when(provider.getChildTree("org-id", true)).thenReturn(null));
           MockedConstruction<AgingDao> daoConstruction = mockConstruction(AgingDao.class)) {
        paymentStatusMock.when(FIN_Utility::getListPaymentConfirmed)
            .thenReturn(Collections.singletonList("RPR"));
        obDalMock.when(OBDal::getInstance).thenReturn(dal);
        when(dal.get(Organization.class, "org-id")).thenReturn(mock(Organization.class));

        JSONObject body = new JSONObject();
        body.put("recOrPay", "RECEIVABLES");
        body.put("orgId", "org-id");
        NeoResponse result = handler.handle(
            NeoContext.builder().httpMethod("POST").requestBody(body).build());

        assertEquals(422, result.getHttpStatus());
        assertTrue(result.getBody().getJSONObject("error").getString("message")
            .contains("organization tree"));
        assertTrue(daoConstruction.constructed().isEmpty());
      }
    }

    @Test
    @DisplayName("Missing accounting schema returns an actionable 422")
    void missingAccountingSchemaReturns422() throws Exception {
      OBDal dal = mock(OBDal.class);
      @SuppressWarnings("unchecked")
      OBQuery<AcctSchema> schemaQuery = mock(OBQuery.class);
      try (MockedStatic<NeoAccessHelper> accessMock = mockAccessGranted();
           MockedStatic<FIN_Utility> paymentStatusMock = mockStatic(FIN_Utility.class);
           MockedStatic<OBContext> contextMock = mockStatic(OBContext.class);
           MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class);
           MockedConstruction<OrganizationStructureProvider> orgProviderConstruction =
               mockConstruction(OrganizationStructureProvider.class, (provider, ignored) ->
                   when(provider.getChildTree("org-id", true))
                       .thenReturn(Collections.singleton("org-id")));
           MockedConstruction<AgingDao> daoConstruction = mockConstruction(AgingDao.class)) {
        paymentStatusMock.when(FIN_Utility::getListPaymentConfirmed)
            .thenReturn(Collections.singletonList("RPR"));
        obDalMock.when(OBDal::getInstance).thenReturn(dal);
        when(dal.get(Organization.class, "org-id")).thenReturn(mock(Organization.class));
        when(dal.createQuery(eq(AcctSchema.class), anyString())).thenReturn(schemaQuery);
        when(schemaQuery.setNamedParameter(anyString(), anyString())).thenReturn(schemaQuery);
        when(schemaQuery.setMaxResult(1)).thenReturn(schemaQuery);
        when(schemaQuery.uniqueResult()).thenReturn(null);

        JSONObject body = new JSONObject();
        body.put("recOrPay", "RECEIVABLES");
        body.put("orgId", "org-id");
        NeoResponse result = handler.handle(
            NeoContext.builder().httpMethod("POST").requestBody(body).build());

        assertEquals(422, result.getHttpStatus());
        assertTrue(result.getBody().getJSONObject("error").getString("message")
            .contains("No accounting schema"));
        assertTrue(daoConstruction.constructed().isEmpty());
      }
    }
  }

  // -------------------------------------------------------------------------
  // describeReport
  // -------------------------------------------------------------------------

  @Nested
  @DisplayName("describeReport")
  class DescribeReport {

    /**
     * The descriptor is now rendered from {@link AgingReportHandler#reportParameters()} instead of
     * a hand-written second copy (ETP-4793 / IMP-19). That copy had drifted: it listed 8 of the 10
     * parameters the handler actually reads — {@code glId} and {@code showDetails} were absent —
     * and it claimed {@code recOrPay} was required when the code defaults it to RECEIVABLES.
     * Rendering from the declaration is what makes that class of drift impossible, so the test
     * asserts against the declaration rather than a second hardcoded count.
     */
    @Test
    @DisplayName("Renders every declared parameter, and only those")
    void returnsAllParams() throws Exception {
      NeoResponse result = (NeoResponse) invokePrivate(handler, "describeReport",
          new Class<?>[] {});

      assertEquals(200, result.getHttpStatus());
      JSONObject body = result.getBody();
      assertEquals("Aging Report", body.getString("name"));

      List<NeoReportParam> declared = handler.reportParameters().orElseThrow();
      JSONArray params = body.getJSONArray("parameters");
      assertEquals(declared.size(), params.length());
      for (int i = 0; i < declared.size(); i++) {
        assertEquals(declared.get(i).getName(), params.getJSONObject(i).getString("name"));
      }

      // The two the old hand-written descriptor omitted entirely.
      List<String> names = new ArrayList<>();
      for (int i = 0; i < params.length(); i++) {
        names.add(params.getJSONObject(i).getString("name"));
      }
      assertTrue(names.contains("glId"));
      assertTrue(names.contains("showDetails"));

      // recOrPay is a closed set with a non-neutral default, not a requirement: declaring it
      // required would reject calls that work today.
      JSONObject recOrPay = params.getJSONObject(0);
      assertEquals("recOrPay", recOrPay.getString("name"));
      assertFalse(recOrPay.getBoolean("required"));
      assertEquals("RECEIVABLES", recOrPay.getJSONArray("allowedValues").getString(0));
    }
  }

  // -------------------------------------------------------------------------
  // toBigDecimal
  // -------------------------------------------------------------------------

  @Nested
  @DisplayName("toBigDecimal")
  class ToBigDecimal {

    @ParameterizedTest
    @NullAndEmptySource
    @DisplayName("Returns ZERO for null and empty")
    void nullAndEmpty(String input) throws Exception {
      BigDecimal result = (BigDecimal) invokeStatic("toBigDecimal",
          new Class<?>[] { String.class }, input);
      assertEquals(BigDecimal.ZERO, result);
    }

    @Test
    @DisplayName("Parses valid number")
    void parsesValidNumber() throws Exception {
      BigDecimal result = (BigDecimal) invokeStatic("toBigDecimal",
          new Class<?>[] { String.class }, "123.45");
      assertEquals(new BigDecimal("123.45"), result);
    }

    @Test
    @DisplayName("Returns ZERO for non-numeric string")
    void nonNumericReturnsZero() throws Exception {
      BigDecimal result = (BigDecimal) invokeStatic("toBigDecimal",
          new Class<?>[] { String.class }, "abc");
      assertEquals(BigDecimal.ZERO, result);
    }

    @Test
    @DisplayName("Parses negative number")
    void parsesNegative() throws Exception {
      BigDecimal result = (BigDecimal) invokeStatic("toBigDecimal",
          new Class<?>[] { String.class }, "-42.50");
      assertEquals(new BigDecimal("-42.50"), result);
    }
  }

  // -------------------------------------------------------------------------
  // buildBpInClause
  // -------------------------------------------------------------------------

  @Nested
  @DisplayName("buildBpInClause")
  class BuildBpInClause {

    @Test
    @DisplayName("Empty input returns empty string")
    void emptyReturnsEmpty() throws Exception {
      String result = (String) invokeStatic("buildBpInClause",
          new Class<?>[] { String.class }, "");
      assertEquals("", result);
    }

    @Test
    @DisplayName("Single ID returns ('id')")
    void singleId() throws Exception {
      String result = (String) invokeStatic("buildBpInClause",
          new Class<?>[] { String.class }, "ABC123");
      assertEquals("('ABC123')", result);
    }

    @Test
    @DisplayName("Multiple comma-separated IDs")
    void multipleIds() throws Exception {
      String result = (String) invokeStatic("buildBpInClause",
          new Class<?>[] { String.class }, "ID1,ID2,ID3");
      assertEquals("('ID1','ID2','ID3')", result);
    }

    @Test
    @DisplayName("Trims whitespace around IDs")
    void trimsWhitespace() throws Exception {
      String result = (String) invokeStatic("buildBpInClause",
          new Class<?>[] { String.class }, " ID1 , ID2 ");
      assertEquals("('ID1','ID2')", result);
    }

    @Test
    @DisplayName("Escapes single quotes in IDs")
    void escapesSingleQuotes() throws Exception {
      String result = (String) invokeStatic("buildBpInClause",
          new Class<?>[] { String.class }, "O'Brien");
      assertEquals("('O''Brien')", result);
    }
  }

  // -------------------------------------------------------------------------
  // resolveDate
  // -------------------------------------------------------------------------

  @Nested
  @DisplayName("resolveDate")
  class ResolveDate {

    @Test
    @DisplayName("Empty string returns current date (today)")
    void emptyReturnsToday() throws Exception {
      Date result = (Date) invokeStatic("resolveDate",
          new Class<?>[] { String.class }, "");
      assertNotNull(result);
      // Should be within 1 second of now
      assertTrue(Math.abs(result.getTime() - System.currentTimeMillis()) < 1000);
    }

    @Test
    @DisplayName("Parses yyyy-MM-dd format correctly")
    void parsesValidDate() throws Exception {
      Date result = (Date) invokeStatic("resolveDate",
          new Class<?>[] { String.class }, "2025-06-15");
      assertNotNull(result);
      SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd");
      assertEquals("2025-06-15", sdf.format(result));
    }

    @Test
    @DisplayName("Throws ParseException for invalid date format")
    void invalidDateThrows() {
      try {
        invokeStatic("resolveDate", new Class<?>[] { String.class }, "not-a-date");
        // Should not reach here
        assertTrue(false, "Expected ParseException");
      } catch (Exception e) {
        assertTrue(e.getCause() instanceof java.text.ParseException);
      }
    }
  }

  // -------------------------------------------------------------------------
  // resolveBuckets
  // -------------------------------------------------------------------------

  @Nested
  @DisplayName("resolveBuckets")
  class ResolveBuckets {

    @Test
    @DisplayName("No columns specified uses defaults (30,60,90,120) with 4 active")
    void defaultBuckets() throws Exception {
      JSONObject body = new JSONObject();
      Object bucket = invokePrivate(handler, "resolveBuckets",
          new Class<?>[] { JSONObject.class }, body);

      assertEquals("30", getBucketRawField(bucket, "col1Raw"));
      assertEquals("60", getBucketRawField(bucket, "col2Raw"));
      assertEquals("90", getBucketRawField(bucket, "col3Raw"));
      assertEquals("120", getBucketRawField(bucket, "col4Raw"));
      assertEquals(4, getBucketActiveBuckets(bucket));
    }

    @Test
    @DisplayName("Only col1 specified → 1 active bucket")
    void onlyCol1() throws Exception {
      JSONObject body = new JSONObject();
      body.put("column1", "45");
      Object bucket = invokePrivate(handler, "resolveBuckets",
          new Class<?>[] { JSONObject.class }, body);

      assertEquals("45", getBucketRawField(bucket, "col1Raw"));
      assertEquals(1, getBucketActiveBuckets(bucket));
    }

    @Test
    @DisplayName("Col1 + col2 → 2 active buckets")
    void col1AndCol2() throws Exception {
      JSONObject body = new JSONObject();
      body.put("column1", "15");
      body.put("column2", "45");
      Object bucket = invokePrivate(handler, "resolveBuckets",
          new Class<?>[] { JSONObject.class }, body);

      assertEquals(2, getBucketActiveBuckets(bucket));
    }

    @Test
    @DisplayName("Col1 + col2 + col3 → 3 active buckets")
    void threeColumns() throws Exception {
      JSONObject body = new JSONObject();
      body.put("column1", "15");
      body.put("column2", "30");
      body.put("column3", "60");
      Object bucket = invokePrivate(handler, "resolveBuckets",
          new Class<?>[] { JSONObject.class }, body);

      assertEquals(3, getBucketActiveBuckets(bucket));
    }

    @Test
    @DisplayName("All four columns → 4 active buckets")
    void allFourColumns() throws Exception {
      JSONObject body = new JSONObject();
      body.put("column1", "15");
      body.put("column2", "30");
      body.put("column3", "60");
      body.put("column4", "90");
      Object bucket = invokePrivate(handler, "resolveBuckets",
          new Class<?>[] { JSONObject.class }, body);

      assertEquals(4, getBucketActiveBuckets(bucket));
    }
  }

  // -------------------------------------------------------------------------
  // BucketConfig.col1-4 sentinel logic
  // -------------------------------------------------------------------------

  @Nested
  @DisplayName("BucketConfig sentinel logic")
  class BucketConfigSentinel {

    @Test
    @DisplayName("col1 always returns raw value")
    void col1AlwaysReturnsRaw() throws Exception {
      Object bucket = newBucketConfig("30", "60", "90", "120", 4);
      assertEquals("30", getBucketField(bucket, "col1"));
    }

    @Test
    @DisplayName("col2 returns sentinel when < 2 active buckets")
    void col2SentinelWhenInactive() throws Exception {
      Object bucket = newBucketConfig("30", "60", "90", "120", 1);
      assertEquals("99999", getBucketField(bucket, "col2"));
    }

    @Test
    @DisplayName("col2 returns raw when >= 2 active buckets")
    void col2RawWhenActive() throws Exception {
      Object bucket = newBucketConfig("30", "60", "90", "120", 2);
      assertEquals("60", getBucketField(bucket, "col2"));
    }

    @Test
    @DisplayName("col3 returns sentinel when < 3 active buckets")
    void col3SentinelWhenInactive() throws Exception {
      Object bucket = newBucketConfig("30", "60", "90", "120", 2);
      assertEquals("99999", getBucketField(bucket, "col3"));
    }

    @Test
    @DisplayName("col3 returns raw when >= 3 active buckets")
    void col3RawWhenActive() throws Exception {
      Object bucket = newBucketConfig("30", "60", "90", "120", 3);
      assertEquals("90", getBucketField(bucket, "col3"));
    }

    @Test
    @DisplayName("col4 returns sentinel when < 4 active buckets")
    void col4SentinelWhenInactive() throws Exception {
      Object bucket = newBucketConfig("30", "60", "90", "120", 3);
      assertEquals("99999", getBucketField(bucket, "col4"));
    }

    @Test
    @DisplayName("col4 returns raw when >= 4 active buckets")
    void col4RawWhenActive() throws Exception {
      Object bucket = newBucketConfig("30", "60", "90", "120", 4);
      assertEquals("120", getBucketField(bucket, "col4"));
    }
  }

  // -------------------------------------------------------------------------
  // buildSummaryRows
  // -------------------------------------------------------------------------

  @Nested
  @DisplayName("buildSummaryRows")
  class BuildSummaryRows {

    @Test
    @DisplayName("Returns empty array for null data")
    void nullDataReturnsEmpty() throws Exception {
      JSONArray result = (JSONArray) invokeStatic("buildSummaryRows",
          new Class<?>[] { FieldProvider[].class, int.class },
          (FieldProvider[]) null, 4);
      assertEquals(0, result.length());
    }

    @Test
    @DisplayName("Returns empty array for empty data")
    void emptyDataReturnsEmpty() throws Exception {
      JSONArray result = (JSONArray) invokeStatic("buildSummaryRows",
          new Class<?>[] { FieldProvider[].class, int.class },
          new FieldProvider[0], 4);
      assertEquals(0, result.length());
    }

    @Test
    @DisplayName("Builds row with all 4 buckets active")
    void buildsRowWith4Buckets() throws Exception {
      java.util.Map<String, String> fields = new java.util.HashMap<>();
      fields.put("BPartnerID", "BP-001");
      fields.put("BPartner", "Acme Corp");
      fields.put("amount0", "100.00");
      fields.put("amount1", "200.00");
      fields.put("amount2", "300.00");
      fields.put("amount3", "400.00");
      fields.put("amount4", "500.00");
      fields.put("amount5", "600.00");
      fields.put("Total", "2100.00");
      fields.put("credit", "50.00");
      fields.put("net", "2050.00");

      FieldProvider fp = stubFieldProvider(fields);
      JSONArray result = (JSONArray) invokeStatic("buildSummaryRows",
          new Class<?>[] { FieldProvider[].class, int.class },
          new FieldProvider[] { fp }, 4);

      assertEquals(1, result.length());
      JSONObject row = result.getJSONObject(0);
      assertEquals("BP-001", row.getString("bPartnerId"));
      assertEquals("Acme Corp", row.getString("bPartner"));
      assertEquals(new BigDecimal("100.00"), row.get("current"));
      assertEquals(new BigDecimal("200.00"), row.get("days30"));
      assertEquals(new BigDecimal("300.00"), row.get("days60"));
      assertEquals(new BigDecimal("400.00"), row.get("days90"));
      assertEquals(new BigDecimal("500.00"), row.get("days120"));
      assertEquals(new BigDecimal("600.00"), row.get("days150plus"));
    }

    @Test
    @DisplayName("With 2 active buckets, days60/days90/days120 are zero and daysPlus accumulates")
    void accumulates2Buckets() throws Exception {
      java.util.Map<String, String> fields = new java.util.HashMap<>();
      fields.put("BPartnerID", "BP-002");
      fields.put("BPartner", "TestCo");
      fields.put("amount0", "10");
      fields.put("amount1", "20");
      fields.put("amount2", "30");
      fields.put("amount3", "40");
      fields.put("amount4", "50");
      fields.put("amount5", "60");
      fields.put("Total", "210");
      fields.put("credit", "0");
      fields.put("net", "210");

      FieldProvider fp = stubFieldProvider(fields);
      JSONArray result = (JSONArray) invokeStatic("buildSummaryRows",
          new Class<?>[] { FieldProvider[].class, int.class },
          new FieldProvider[] { fp }, 2);

      JSONObject row = result.getJSONObject(0);
      assertEquals(new BigDecimal("30"), row.get("days60"));
      assertEquals(BigDecimal.ZERO, row.get("days90"));
      assertEquals(BigDecimal.ZERO, row.get("days120"));
      // days150plus = amount5 + amount4 + amount3 = 60+50+40 = 150
      assertEquals(new BigDecimal("150"), row.get("days150plus"));
    }

    @Test
    @DisplayName("With 1 active bucket, only days30 shown, rest accumulated")
    void accumulates1Bucket() throws Exception {
      java.util.Map<String, String> fields = new java.util.HashMap<>();
      fields.put("BPartnerID", "BP-003");
      fields.put("BPartner", "Solo");
      fields.put("amount0", "10");
      fields.put("amount1", "20");
      fields.put("amount2", "30");
      fields.put("amount3", "40");
      fields.put("amount4", "50");
      fields.put("amount5", "60");
      fields.put("Total", "210");
      fields.put("credit", "0");
      fields.put("net", "210");

      FieldProvider fp = stubFieldProvider(fields);
      JSONArray result = (JSONArray) invokeStatic("buildSummaryRows",
          new Class<?>[] { FieldProvider[].class, int.class },
          new FieldProvider[] { fp }, 1);

      JSONObject row = result.getJSONObject(0);
      assertEquals(BigDecimal.ZERO, row.get("days60"));
      assertEquals(BigDecimal.ZERO, row.get("days90"));
      assertEquals(BigDecimal.ZERO, row.get("days120"));
      // days150plus = amount5 + amount4 + amount3 + amount2 = 60+50+40+30 = 180
      assertEquals(new BigDecimal("180"), row.get("days150plus"));
    }
  }

  // -------------------------------------------------------------------------
  // buildDocRow
  // -------------------------------------------------------------------------

  @Nested
  @DisplayName("buildDocRow")
  class BuildDocRow {

    @Test
    @DisplayName("Builds document row with all fields")
    void buildsDocRow() throws Exception {
      java.util.Map<String, String> fields = new java.util.HashMap<>();
      fields.put("INVOICE_ID", "INV-001");
      fields.put("INVOICE_NUMBER", "2025/001");
      fields.put("INVOICE_DATE", "2025-01-15");
      fields.put("AMOUNT0", "100");
      fields.put("AMOUNT1", "200");
      fields.put("AMOUNT2", "300");
      fields.put("AMOUNT3", "400");
      fields.put("AMOUNT4", "500");
      fields.put("AMOUNT5", "600");

      FieldProvider fp = stubFieldProvider(fields);
      JSONObject doc = (JSONObject) invokeStatic("buildDocRow",
          new Class<?>[] { FieldProvider.class, int.class }, fp, 4);

      assertEquals("INV-001", doc.getString("invoiceId"));
      assertEquals("2025/001", doc.getString("docNo"));
      assertEquals("2025-01-15", doc.getString("dateInvoiced"));
      assertEquals(new BigDecimal("100"), doc.get("current"));
      assertEquals(new BigDecimal("600"), doc.get("days150plus"));
    }

    @Test
    @DisplayName("With 1 active bucket, accumulates remaining into daysPlus")
    void docRowWith1Bucket() throws Exception {
      java.util.Map<String, String> fields = new java.util.HashMap<>();
      fields.put("INVOICE_ID", "INV-002");
      fields.put("INVOICE_NUMBER", "2025/002");
      fields.put("INVOICE_DATE", "2025-02-01");
      fields.put("AMOUNT0", "10");
      fields.put("AMOUNT1", "20");
      fields.put("AMOUNT2", "30");
      fields.put("AMOUNT3", "40");
      fields.put("AMOUNT4", "50");
      fields.put("AMOUNT5", "60");

      FieldProvider fp = stubFieldProvider(fields);
      JSONObject doc = (JSONObject) invokeStatic("buildDocRow",
          new Class<?>[] { FieldProvider.class, int.class }, fp, 1);

      assertEquals(BigDecimal.ZERO, doc.get("days60"));
      assertEquals(BigDecimal.ZERO, doc.get("days90"));
      assertEquals(BigDecimal.ZERO, doc.get("days120"));
      // 60 + 50 + 40 + 30 = 180
      assertEquals(new BigDecimal("180"), doc.get("days150plus"));
    }
  }

  // -------------------------------------------------------------------------
  // buildMeta
  // -------------------------------------------------------------------------

  @Nested
  @DisplayName("buildMeta")
  class BuildMeta {

    @Test
    @DisplayName("Builds meta with all 4 buckets active")
    void metaWith4Buckets() throws Exception {
      Object bucket = newBucketConfig("30", "60", "90", "120", 4);
      Date date = new SimpleDateFormat("yyyy-MM-dd").parse("2025-06-15");

      JSONObject meta = (JSONObject) invokeStatic("buildMeta",
          new Class<?>[] { String.class, Date.class,
              Class.forName("com.etendoerp.go.schemaforge.AgingReportHandler$BucketConfig"),
              boolean.class },
          "RECEIVABLES", date, bucket, true);

      assertEquals("RECEIVABLES", meta.getString("recOrPay"));
      assertEquals("2025-06-15", meta.getString("currentDate"));
      assertEquals("30", meta.getString("column1"));
      assertEquals("60", meta.getString("column2"));
      assertEquals("90", meta.getString("column3"));
      assertEquals("120", meta.getString("column4"));
      assertEquals(4, meta.getInt("activeBuckets"));
      assertTrue(meta.getBoolean("showBucket2"));
      assertTrue(meta.getBoolean("showBucket3"));
      assertTrue(meta.getBoolean("showBucket4"));
      assertEquals(">120", meta.getString("lastBucketLabel"));
      assertTrue(meta.getBoolean("showDetails"));
    }

    @Test
    @DisplayName("Builds meta with 2 buckets — col3/col4 are empty, showBucket3/4 false")
    void metaWith2Buckets() throws Exception {
      Object bucket = newBucketConfig("15", "45", "", "", 2);
      Date date = new SimpleDateFormat("yyyy-MM-dd").parse("2025-01-01");

      JSONObject meta = (JSONObject) invokeStatic("buildMeta",
          new Class<?>[] { String.class, Date.class,
              Class.forName("com.etendoerp.go.schemaforge.AgingReportHandler$BucketConfig"),
              boolean.class },
          "PAYABLES", date, bucket, false);

      assertEquals("PAYABLES", meta.getString("recOrPay"));
      assertEquals("15", meta.getString("column1"));
      assertEquals("45", meta.getString("column2"));
      assertEquals("", meta.getString("column3"));
      assertEquals("", meta.getString("column4"));
      assertFalse(meta.getBoolean("showBucket3"));
      assertFalse(meta.getBoolean("showBucket4"));
      assertEquals(">45", meta.getString("lastBucketLabel"));
      assertFalse(meta.getBoolean("showDetails"));
    }

    @Test
    @DisplayName("Builds meta with 1 bucket — lastBucketLabel uses col1")
    void metaWith1Bucket() throws Exception {
      Object bucket = newBucketConfig("7", "", "", "", 1);
      Date date = new Date();

      JSONObject meta = (JSONObject) invokeStatic("buildMeta",
          new Class<?>[] { String.class, Date.class,
              Class.forName("com.etendoerp.go.schemaforge.AgingReportHandler$BucketConfig"),
              boolean.class },
          "RECEIVABLES", date, bucket, false);

      assertEquals(">7", meta.getString("lastBucketLabel"));
      assertFalse(meta.getBoolean("showBucket2"));
    }
  }

  // -------------------------------------------------------------------------
  // lastBucketValue
  // -------------------------------------------------------------------------

  @Nested
  @DisplayName("lastBucketValue")
  class LastBucketValue {

    @Test
    @DisplayName("Returns col4Raw for 4 active buckets")
    void fourBuckets() throws Exception {
      Object bucket = newBucketConfig("30", "60", "90", "120", 4);
      String result = (String) invokeStatic("lastBucketValue",
          new Class<?>[] { Class.forName(
              "com.etendoerp.go.schemaforge.AgingReportHandler$BucketConfig") },
          bucket);
      assertEquals("120", result);
    }

    @Test
    @DisplayName("Returns col3Raw for 3 active buckets")
    void threeBuckets() throws Exception {
      Object bucket = newBucketConfig("30", "60", "90", "", 3);
      String result = (String) invokeStatic("lastBucketValue",
          new Class<?>[] { Class.forName(
              "com.etendoerp.go.schemaforge.AgingReportHandler$BucketConfig") },
          bucket);
      assertEquals("90", result);
    }

    @Test
    @DisplayName("Returns col2Raw for 2 active buckets")
    void twoBuckets() throws Exception {
      Object bucket = newBucketConfig("30", "60", "", "", 2);
      String result = (String) invokeStatic("lastBucketValue",
          new Class<?>[] { Class.forName(
              "com.etendoerp.go.schemaforge.AgingReportHandler$BucketConfig") },
          bucket);
      assertEquals("60", result);
    }

    @Test
    @DisplayName("Returns col1Raw for 1 active bucket")
    void oneBucket() throws Exception {
      Object bucket = newBucketConfig("30", "", "", "", 1);
      String result = (String) invokeStatic("lastBucketValue",
          new Class<?>[] { Class.forName(
              "com.etendoerp.go.schemaforge.AgingReportHandler$BucketConfig") },
          bucket);
      assertEquals("30", result);
    }
  }

  // -------------------------------------------------------------------------
  // groupDetailByBp
  // -------------------------------------------------------------------------

  @Nested
  @DisplayName("groupDetailByBp")
  class GroupDetailByBp {

    @Test
    @DisplayName("Returns empty map for null data")
    void nullDataReturnsEmpty() throws Exception {
      @SuppressWarnings("unchecked")
      java.util.Map<String, JSONArray> result = (java.util.Map<String, JSONArray>) invokeStatic(
          "groupDetailByBp",
          new Class<?>[] { FieldProvider[].class, int.class },
          (FieldProvider[]) null, 4);
      assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("Skips rows with non-empty AMOUNT6 (subtotals)")
    void skipsSubtotalRows() throws Exception {
      java.util.Map<String, String> fields = new java.util.HashMap<>();
      fields.put("AMOUNT6", "100");
      fields.put("BPARTNER", "BP-001");
      fields.put("INVOICE_ID", "INV-001");
      fields.put("INVOICE_NUMBER", "001");
      fields.put("INVOICE_DATE", "2025-01-01");
      fields.put("AMOUNT0", "0");
      fields.put("AMOUNT1", "0");
      fields.put("AMOUNT2", "0");
      fields.put("AMOUNT3", "0");
      fields.put("AMOUNT4", "0");
      fields.put("AMOUNT5", "0");

      FieldProvider fp = stubFieldProvider(fields);
      @SuppressWarnings("unchecked")
      java.util.Map<String, JSONArray> result = (java.util.Map<String, JSONArray>) invokeStatic(
          "groupDetailByBp",
          new Class<?>[] { FieldProvider[].class, int.class },
          new FieldProvider[] { fp }, 4);

      assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("Groups detail rows by BPARTNER ID")
    void groupsByBpId() throws Exception {
      java.util.Map<String, String> f1 = new java.util.HashMap<>();
      f1.put("AMOUNT6", "");
      f1.put("BPARTNER", "BP-001");
      f1.put("INVOICE_ID", "INV-1");
      f1.put("INVOICE_NUMBER", "001");
      f1.put("INVOICE_DATE", "2025-01-01");
      f1.put("AMOUNT0", "10");
      f1.put("AMOUNT1", "20");
      f1.put("AMOUNT2", "0");
      f1.put("AMOUNT3", "0");
      f1.put("AMOUNT4", "0");
      f1.put("AMOUNT5", "0");

      java.util.Map<String, String> f2 = new java.util.HashMap<>();
      f2.put("AMOUNT6", "");
      f2.put("BPARTNER", "BP-001");
      f2.put("INVOICE_ID", "INV-2");
      f2.put("INVOICE_NUMBER", "002");
      f2.put("INVOICE_DATE", "2025-02-01");
      f2.put("AMOUNT0", "5");
      f2.put("AMOUNT1", "15");
      f2.put("AMOUNT2", "0");
      f2.put("AMOUNT3", "0");
      f2.put("AMOUNT4", "0");
      f2.put("AMOUNT5", "0");

      java.util.Map<String, String> f3 = new java.util.HashMap<>();
      f3.put("AMOUNT6", "");
      f3.put("BPARTNER", "BP-002");
      f3.put("INVOICE_ID", "INV-3");
      f3.put("INVOICE_NUMBER", "003");
      f3.put("INVOICE_DATE", "2025-03-01");
      f3.put("AMOUNT0", "100");
      f3.put("AMOUNT1", "0");
      f3.put("AMOUNT2", "0");
      f3.put("AMOUNT3", "0");
      f3.put("AMOUNT4", "0");
      f3.put("AMOUNT5", "0");

      FieldProvider[] data = { stubFieldProvider(f1), stubFieldProvider(f2), stubFieldProvider(f3) };
      @SuppressWarnings("unchecked")
      java.util.Map<String, JSONArray> result = (java.util.Map<String, JSONArray>) invokeStatic(
          "groupDetailByBp",
          new Class<?>[] { FieldProvider[].class, int.class },
          data, 4);

      assertEquals(2, result.size());
      assertEquals(2, result.get("BP-001").length());
      assertEquals(1, result.get("BP-002").length());
    }
  }

  // -------------------------------------------------------------------------
  // param helper
  // -------------------------------------------------------------------------

  @Nested
  @DisplayName("param helper")
  class ParamHelper {

    /**
     * The helper takes the declared parameter itself now, so name/type/required/description can
     * no longer be retyped differently from what the handler reads.
     */
    @Test
    @DisplayName("Builds JSON parameter definition from the declaration")
    void buildsParam() throws Exception {
      JSONObject p = (JSONObject) invokeStatic("param",
          new Class<?>[] { NeoReportParam.class },
          NeoReportParam.required("dateFrom", NeoReportParam.TYPE_DATE, "Start date."));

      assertEquals("dateFrom", p.getString("name"));
      assertEquals(NeoReportParam.TYPE_DATE, p.getString("type"));
      assertTrue(p.getBoolean("required"));
      assertEquals("Start date.", p.getString("description"));
      assertFalse(p.has("allowedValues"), "an open-ended parameter has no closed set");
    }

    @Test
    @DisplayName("A closed set is rendered as allowedValues")
    void buildsParamWithAllowedValues() throws Exception {
      JSONObject p = (JSONObject) invokeStatic("param",
          new Class<?>[] { NeoReportParam.class },
          NeoReportParam.options("recOrPay", "Which side.",
              List.of("RECEIVABLES", "PAYABLES")));

      assertFalse(p.getBoolean("required"));
      assertEquals(2, p.getJSONArray("allowedValues").length());
      assertEquals("PAYABLES", p.getJSONArray("allowedValues").getString(1));
    }
  }
}
