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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.util.Date;

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
import org.openbravo.base.exception.OBException;
import org.openbravo.dal.core.OBContext;
import org.openbravo.model.ad.ui.Process;

import com.etendoerp.go.schemaforge.util.NeoAccessHelper;

/**
 * Unit tests for {@link NeoReportService}.
 *
 * <p>Tests cover: validateReportProcess (via reflection), describeReport
 * (success with formats, error propagation), convertValue (date, integer,
 * decimal, boolean, string, null refId), toKebabCase (normal, special chars,
 * null), and ReportMetadata inner class.
 *
 * <p>Note: methods that internally reference {@code ReportingUtils.ExportType}
 * (parseExportType, resolveReportMetadata, generateReport, mapContentType)
 * cannot be tested as unit tests because the ExportType enum has a static
 * initializer that requires OBDal/SessionHandler/WeldUtils at class-load time.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class NeoReportServiceTest {

  @Mock
  private Process process;

  private MockedStatic<OBContext> obContextMock;
  private MockedStatic<NeoAccessHelper> accessHelperMock;
  private MockedStatic<NeoProcessService> processServiceMock;

  @BeforeEach
  void setUp() {
    obContextMock = mockStatic(OBContext.class);
    accessHelperMock = mockStatic(NeoAccessHelper.class);
    processServiceMock = mockStatic(NeoProcessService.class);
  }

  @AfterEach
  void tearDown() {
    if (obContextMock != null) {
      obContextMock.close();
    }
    if (accessHelperMock != null) {
      accessHelperMock.close();
    }
    if (processServiceMock != null) {
      processServiceMock.close();
    }
  }

  // ---- Helper methods ----

  private void mockReportProcess(String name, String templateName, boolean isReport) {
    when(process.getName()).thenReturn(name);
    when(process.getJRTemplateName()).thenReturn(templateName);
    when(process.isReport()).thenReturn(isReport);
    when(process.getId()).thenReturn("test-process-id");
  }

  private void mockAccessDenied() {
    accessHelperMock.when(() -> NeoAccessHelper.hasProcessAccess(anyString())).thenReturn(false);
  }

  // ---- validateReportProcess (via reflection) ----

  private void invokeValidateReportProcess(Process proc) throws Exception {
    Method method = NeoReportService.class.getDeclaredMethod("validateReportProcess", Process.class);
    method.setAccessible(true);
    try {
      method.invoke(null, proc);
    } catch (InvocationTargetException e) {
      if (e.getCause() instanceof Exception) {
        throw (Exception) e.getCause();
      }
      throw e;
    }
  }

  /**
   * Verifies that a valid report process passes validation without error.
   */
  @Test
  void validateReportProcessValidProcess() throws Exception {
    mockReportProcess("Sales Report", "reports/sales.jrxml", true);
    invokeValidateReportProcess(process);
  }

  /**
   * Verifies that a non-report process throws OBException.
   */
  @Test
  void validateReportProcessNonReportThrows() {
    mockReportProcess("Not A Report", "reports/template.jrxml", false);

    OBException ex = assertThrows(OBException.class,
        () -> invokeValidateReportProcess(process));
    assertTrue(ex.getMessage().contains("is not a report"));
  }

  /**
   * Verifies that a process with no template throws OBException.
   */
  @Test
  void validateReportProcessMissingTemplateThrows() {
    when(process.getName()).thenReturn("No Template Report");
    when(process.isReport()).thenReturn(true);
    when(process.getJRTemplateName()).thenReturn(null);

    OBException ex = assertThrows(OBException.class,
        () -> invokeValidateReportProcess(process));
    assertTrue(ex.getMessage().contains("no Jasper template configured"));
  }

  /**
   * Verifies that a process with blank template throws OBException.
   */
  @Test
  void validateReportProcessBlankTemplateThrows() {
    when(process.getName()).thenReturn("Blank Template Report");
    when(process.isReport()).thenReturn(true);
    when(process.getJRTemplateName()).thenReturn("   ");

    OBException ex = assertThrows(OBException.class,
        () -> invokeValidateReportProcess(process));
    assertTrue(ex.getMessage().contains("no Jasper template configured"));
  }

  // ---- generateReport access denied ----

  /**
   * Verifies that access denied throws SecurityException.
   * This test only exercises the access check, which happens before any
   * ExportType reference is reached.
   */
  @Test
  void generateReportAccessDeniedThrowsSecurityException() {
    mockReportProcess("Secure Report", "reports/secure.jrxml", true);
    mockAccessDenied();

    java.io.OutputStream out = new java.io.ByteArrayOutputStream();
    assertThrows(SecurityException.class,
        () -> NeoReportService.generateReport(process, new JSONObject(), "PDF", out));
  }

  // ---- describeReport ----

  /**
   * Verifies that describeReport returns 200 with isReport and supportedFormats.
   */
  @Test
  void describeReportSuccessfulWithFormats() throws Exception {
    mockReportProcess("Report Desc", "reports/desc.jrxml", true);

    JSONObject body = new JSONObject();
    body.put("name", "Report Desc");
    NeoResponse okResponse = NeoResponse.ok(body);

    processServiceMock.when(() -> NeoProcessService.describeProcess(process))
        .thenReturn(okResponse);

    NeoResponse result = NeoReportService.describeReport(process);

    assertEquals(200, result.getHttpStatus());
    assertTrue(result.getBody().getBoolean("isReport"));

    JSONArray formats = result.getBody().getJSONArray("supportedFormats");
    assertEquals(5, formats.length());
    assertEquals("PDF", formats.getString(0));
    assertEquals("XLS", formats.getString(1));
    assertEquals("XLSX", formats.getString(2));
    assertEquals("HTML", formats.getString(3));
    assertEquals("CSV", formats.getString(4));
  }

  /**
   * Verifies that describeReport propagates non-200 from NeoProcessService.
   */
  @Test
  void describeReportErrorFromNeoProcessService() {
    mockReportProcess("Error Report", "reports/error.jrxml", true);

    NeoResponse errorResponse = NeoResponse.error(500, "Internal error");
    processServiceMock.when(() -> NeoProcessService.describeProcess(process))
        .thenReturn(errorResponse);

    NeoResponse result = NeoReportService.describeReport(process);

    assertEquals(500, result.getHttpStatus());
  }

  // ---- convertValue (via reflection) ----

  private Object invokeConvertValue(Object rawValue, String refId) throws Exception {
    Method method = NeoReportService.class.getDeclaredMethod("convertValue", Object.class,
        String.class);
    method.setAccessible(true);
    try {
      return method.invoke(null, rawValue, refId);
    } catch (InvocationTargetException e) {
      throw (Exception) e.getCause();
    }
  }

  /**
   * Verifies that ISO date string is parsed correctly for refId 15 (Date).
   */
  @Test
  void convertValueDateIsoFormat() throws Exception {
    Object result = invokeConvertValue("2025-06-15", "15");
    assertTrue(result instanceof Date);
    SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd");
    assertEquals("2025-06-15", fmt.format((Date) result));
  }

  /**
   * Verifies that datetime string is parsed correctly for refId 16 (DateTime).
   */
  @Test
  void convertValueDateTimeFormat() throws Exception {
    Object result = invokeConvertValue("2025-06-15T10:30:00", "16");
    assertTrue(result instanceof Date);
  }

  /**
   * Verifies that an ISO date string that matches the first format is parsed for refId 16.
   */
  @Test
  void convertValueDateTimeIsoDateFallback() throws Exception {
    Object result = invokeConvertValue("2025-06-15", "16");
    assertTrue(result instanceof Date);
  }

  /**
   * Verifies that integer string is parsed to Long for refId 11.
   */
  @Test
  void convertValueInteger() throws Exception {
    Object result = invokeConvertValue("42", "11");
    assertEquals(42L, result);
  }

  /**
   * Verifies that invalid integer falls back to string for refId 11.
   */
  @Test
  void convertValueIntegerInvalidFallsBackToString() throws Exception {
    Object result = invokeConvertValue("not-a-number", "11");
    assertEquals("not-a-number", result);
  }

  /**
   * Verifies that decimal string is parsed to BigDecimal for refId 22 (Number).
   */
  @Test
  void convertValueDecimalNumber() throws Exception {
    Object result = invokeConvertValue("123.45", "22");
    assertEquals(new BigDecimal("123.45"), result);
  }

  /**
   * Verifies that decimal string is parsed to BigDecimal for refId 12 (Amount).
   */
  @Test
  void convertValueDecimalAmount() throws Exception {
    Object result = invokeConvertValue("999.99", "12");
    assertEquals(new BigDecimal("999.99"), result);
  }

  /**
   * Verifies that "Y" is converted to true for refId 20 (Yes/No).
   */
  @Test
  void convertValueBooleanY() throws Exception {
    Object result = invokeConvertValue("Y", "20");
    assertEquals(true, result);
  }

  /**
   * Verifies that "true" (case-insensitive) is converted to true for refId 20.
   */
  @Test
  void convertValueBooleanTrueString() throws Exception {
    Object result = invokeConvertValue("true", "20");
    assertEquals(true, result);
  }

  /**
   * Verifies that "false" is converted to false for refId 20.
   */
  @Test
  void convertValueBooleanFalse() throws Exception {
    Object result = invokeConvertValue("false", "20");
    assertEquals(false, result);
  }

  /**
   * Verifies that "N" is converted to false for refId 20.
   */
  @Test
  void convertValueBooleanN() throws Exception {
    Object result = invokeConvertValue("N", "20");
    assertEquals(false, result);
  }

  /**
   * Verifies that string refId (10) returns string as-is.
   */
  @Test
  void convertValueString() throws Exception {
    Object result = invokeConvertValue("hello", "10");
    assertEquals("hello", result);
  }

  /**
   * Verifies that list refId (17) returns string as-is.
   */
  @Test
  void convertValueListRef() throws Exception {
    Object result = invokeConvertValue("listValue", "17");
    assertEquals("listValue", result);
  }

  /**
   * Verifies that table refId (18) returns string as-is.
   */
  @Test
  void convertValueTableRef() throws Exception {
    Object result = invokeConvertValue("tableValue", "18");
    assertEquals("tableValue", result);
  }

  /**
   * Verifies that tableDir refId (19) returns string as-is.
   */
  @Test
  void convertValueTableDirRef() throws Exception {
    Object result = invokeConvertValue("tableDirValue", "19");
    assertEquals("tableDirValue", result);
  }

  /**
   * Verifies that search refId (30) returns string as-is.
   */
  @Test
  void convertValueSearchRef() throws Exception {
    Object result = invokeConvertValue("searchValue", "30");
    assertEquals("searchValue", result);
  }

  /**
   * Verifies that null refId returns string representation.
   */
  @Test
  void convertValueNullRefIdReturnsString() throws Exception {
    Object result = invokeConvertValue("someValue", null);
    assertEquals("someValue", result);
  }

  /**
   * Verifies that null rawValue returns null.
   */
  @Test
  void convertValueNullRawValueReturnsNull() throws Exception {
    Object result = invokeConvertValue(null, "10");
    assertNull(result);
  }

  /**
   * Verifies that null rawValue with null refId returns null.
   */
  @Test
  void convertValueBothNullReturnsNull() throws Exception {
    Object result = invokeConvertValue(null, null);
    assertNull(result);
  }

  /**
   * Verifies that an invalid decimal falls back to string for refId 22 (Number).
   */
  @Test
  void convertValueDecimalInvalidFallsBackToString() throws Exception {
    Object result = invokeConvertValue("not-a-decimal", "22");
    assertEquals("not-a-decimal", result);
  }

  /**
   * Verifies that an unparseable date string falls back to the original string for refId 15.
   */
  @Test
  void convertValueDateInvalidFallsBackToString() throws Exception {
    Object result = invokeConvertValue("not-a-date", "15");
    assertEquals("not-a-date", result);
  }

  /**
   * Verifies that an unknown refId returns string as-is (default branch).
   */
  @Test
  void convertValueUnknownRefIdReturnsString() throws Exception {
    Object result = invokeConvertValue("unknownValue", "9999");
    assertEquals("unknownValue", result);
  }

  // ---- toKebabCase (via reflection) ----

  private String invokeToKebabCase(String name) throws Exception {
    Method method = NeoReportService.class.getDeclaredMethod("toKebabCase", String.class);
    method.setAccessible(true);
    try {
      return (String) method.invoke(null, name);
    } catch (InvocationTargetException e) {
      throw (Exception) e.getCause();
    }
  }

  /**
   * Verifies normal name converts to kebab-case.
   */
  @Test
  void toKebabCaseNormalName() throws Exception {
    assertEquals("sales-report", invokeToKebabCase("Sales Report"));
  }

  /**
   * Verifies name with underscores converts to kebab-case.
   */
  @Test
  void toKebabCaseUnderscores() throws Exception {
    assertEquals("sales-report-v2", invokeToKebabCase("Sales_Report_V2"));
  }

  /**
   * Verifies name with special characters strips them.
   */
  @Test
  void toKebabCaseSpecialChars() throws Exception {
    assertEquals("sales-report-2025", invokeToKebabCase("Sales Report (2025)"));
  }

  /**
   * Verifies name with multiple spaces collapses to single dash.
   */
  @Test
  void toKebabCaseMultipleSpaces() throws Exception {
    assertEquals("sales-report", invokeToKebabCase("Sales   Report"));
  }

  /**
   * Verifies that leading and trailing spaces/dashes are removed.
   */
  @Test
  void toKebabCaseLeadingTrailingSpaces() throws Exception {
    assertEquals("report", invokeToKebabCase("  Report  "));
  }

  /**
   * Verifies null returns "report".
   */
  @Test
  void toKebabCaseNullReturnsReport() throws Exception {
    assertEquals("report", invokeToKebabCase(null));
  }

  /**
   * Verifies name with mixed separators.
   */
  @Test
  void toKebabCaseMixedSeparators() throws Exception {
    assertEquals("my-report-name", invokeToKebabCase("My Report_Name"));
  }

  /**
   * Verifies that an empty string returns empty.
   */
  @Test
  void toKebabCaseEmptyString() throws Exception {
    assertEquals("", invokeToKebabCase(""));
  }

  /**
   * Verifies that a string with only special characters returns empty.
   */
  @Test
  void toKebabCaseOnlySpecialChars() throws Exception {
    assertEquals("", invokeToKebabCase("@#$%"));
  }

  // ---- ReportMetadata inner class ----

  /**
   * Verifies ReportMetadata getters return values from constructor.
   */
  @Test
  void reportMetadataGetters() {
    NeoReportService.ReportMetadata metadata = new NeoReportService.ReportMetadata(
        "test.pdf", "application/pdf");
    assertEquals("test.pdf", metadata.getFilename());
    assertEquals("application/pdf", metadata.getContentType());
  }

  /**
   * Verifies ReportMetadata with XLS content type.
   */
  @Test
  void reportMetadataXlsContentType() {
    NeoReportService.ReportMetadata metadata = new NeoReportService.ReportMetadata(
        "report.xls", "application/vnd.ms-excel");
    assertEquals("report.xls", metadata.getFilename());
    assertEquals("application/vnd.ms-excel", metadata.getContentType());
  }

  /**
   * Verifies ReportMetadata can hold null values.
   */
  @Test
  void reportMetadataNullValues() {
    NeoReportService.ReportMetadata metadata = new NeoReportService.ReportMetadata(null, null);
    assertNull(metadata.getFilename());
    assertNull(metadata.getContentType());
  }
}
