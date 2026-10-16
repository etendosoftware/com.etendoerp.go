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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.codehaus.jettison.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.openbravo.base.exception.OBException;
import org.openbravo.model.financialmgmt.accounting.coa.AcctSchema;
import org.openbravo.model.financialmgmt.calendar.Period;
import org.openbravo.model.financialmgmt.calendar.Year;
import org.openbravo.module.taxreportlauncher.TaxReport;
import org.openbravo.module.taxreportlauncher.erpCommon.ad_reports.OBTL_TaxReport_I;

import com.etendoerp.go.schemaforge.util.NeoAccessHelper;

/**
 * Unit tests for the Modelo 390 endpoints (ETP-5716): routing of {@link Fiscal390BoxesHandler},
 * file generation ({@link Fiscal390GenerateSupport}) and the boxes payload / snapshot
 * ({@link Fiscal390SnapshotSupport}). The box computation itself is covered by
 * {@link Fiscal390BoxesCalculatorTest}.
 *
 * @covers com.etendoerp.go.schemaforge.Fiscal390BoxesHandler
 * @covers com.etendoerp.go.schemaforge.Fiscal390GenerateSupport
 * @covers com.etendoerp.go.schemaforge.Fiscal390SnapshotSupport
 * @covers com.etendoerp.go.schemaforge.Fiscal390PreloadSupport
 */
public class Fiscal390BoxesHandlerTest {

  private NeoServlet servlet;
  private Fiscal390BoxesHandler handler;
  private MockedStatic<NeoAccessHelper> accessMock;

  @Before
  public void setUp() {
    servlet = mock(NeoServlet.class);
    handler = spy(new Fiscal390BoxesHandler(servlet));
    doReturn("org1").when(handler).resolveEffectiveOrg();
    accessMock = mockStatic(NeoAccessHelper.class);
    accessMock.when(() -> NeoAccessHelper.hasWindowAccess(
        eq(NeoAttachmentAuthorizer.TAX_REPORT_WINDOW_ID), anyString())).thenReturn(true);
  }

  @After
  public void tearDown() {
    accessMock.close();
  }

  private static HttpServletRequest request(String period, String... extra) {
    HttpServletRequest req = mock(HttpServletRequest.class);
    when(req.getParameter("year")).thenReturn("2026");
    when(req.getParameter(AbstractFiscalHandler.PERIOD_KEY)).thenReturn(period);
    Map<String, String[]> params = new LinkedHashMap<>();
    params.put("year", new String[] { "2026" });
    params.put(AbstractFiscalHandler.PERIOD_KEY, new String[] { period });
    for (int i = 0; i < extra.length; i += 2) {
      params.put(extra[i], new String[] { extra[i + 1] });
      when(req.getParameter(extra[i])).thenReturn(extra[i + 1]);
    }
    when(req.getParameterMap()).thenReturn(params);
    return req;
  }

  private static HttpServletResponse response(StringWriter body) throws Exception {
    HttpServletResponse resp = mock(HttpServletResponse.class);
    when(resp.getWriter()).thenReturn(new PrintWriter(body, true));
    return resp;
  }

  // ── routing ─────────────────────────────────────────────────────────

  @Test
  public void testModelKey() {
    assertEquals("fiscal390", handler.getModelKey());
    assertEquals("390", handler.getDeclModel());
  }

  /** Modelo 390 is annual: any period other than 0A is a 400, nothing computed. */
  @Test
  public void testNonAnnualPeriodIsRejected() throws Exception {
    HttpServletResponse resp = response(new StringWriter());

    handler.handle(Fiscal390BoxesHandler.BOXES, "GET", request("T1"), resp);

    verify(servlet).sendError(eq(resp), eq(HttpServletResponse.SC_BAD_REQUEST),
        org.mockito.ArgumentMatchers.contains("0A"));
    verify(handler, never()).computePayload(anyString(), anyInt(), anyMap());
  }

  @Test
  public void testUnknownEntityIs404() throws Exception {
    HttpServletResponse resp = response(new StringWriter());
    handler.handle("operators", "GET", request("0A"), resp);
    verify(servlet).sendError(eq(resp), eq(HttpServletResponse.SC_NOT_FOUND), anyString());
  }

  /** {@code preload} (303 preload + cuadre, Fiscal390PreloadSupport) is a GET-only entity. */
  @Test
  public void testPreloadIsAKnownGetOnlyEntity() throws Exception {
    assertTrue(handler.isKnownEntity(Fiscal390PreloadSupport.PRELOAD));
    assertTrue(handler.allowsGet(Fiscal390PreloadSupport.PRELOAD));
    HttpServletResponse resp = response(new StringWriter());
    handler.handle(Fiscal390PreloadSupport.PRELOAD, "POST", request("0A"), resp);
    verify(servlet).sendError(eq(resp), eq(HttpServletResponse.SC_METHOD_NOT_ALLOWED),
        anyString());
  }

  @Test
  public void testGenerateIsGetOnly() throws Exception {
    HttpServletResponse resp = response(new StringWriter());
    handler.handle(Fiscal390BoxesHandler.GENERATE, "POST", request("0A"), resp);
    verify(servlet).sendError(eq(resp), eq(HttpServletResponse.SC_METHOD_NOT_ALLOWED),
        anyString());
  }

  /** A submitted declaration is served from its frozen snapshot, never recomputed. */
  @Test
  public void testBoxesServesSubmittedSnapshot() throws Exception {
    JSONObject snapshot = new JSONObject("{\"boxes\":{\"65\":\"1.00\"}}");
    doReturn(snapshot).when(handler).findSubmittedSnapshot(2026, "0A");
    StringWriter body = new StringWriter();

    handler.handle(Fiscal390BoxesHandler.BOXES, "GET", request("0A"), response(body));

    assertEquals(snapshot.toString(), body.toString());
    verify(handler, never()).computePayload(anyString(), anyInt(), anyMap());
  }

  /** A live preview uses the stored inputs overridden by the request's inputs. */
  @Test
  public void testBoxesComputesWithMergedInputs() throws Exception {
    doReturn(null).when(handler).findSubmittedSnapshot(2026, "0A");
    Map<String, String> stored = new LinkedHashMap<>();
    stored.put("LIQANUAL_VAT_IMPORT", "1");
    stored.put("INGRESAR_01", "5");
    doReturn(stored).when(handler).storedInputs(2026, "0A");
    doReturn(new JSONObject("{\"ok\":true}")).when(handler)
        .computePayload(anyString(), anyInt(), anyMap());
    StringWriter body = new StringWriter();

    handler.handle(Fiscal390BoxesHandler.BOXES, "GET",
        request("0A", "LIQANUAL_VAT_IMPORT", "2"), response(body));

    Map<String, String> expected = new LinkedHashMap<>();
    expected.put("LIQANUAL_VAT_IMPORT", "2");
    expected.put("INGRESAR_01", "5");
    verify(handler).computePayload("org1", 2026, expected);
    assertEquals("{\"ok\":true}", body.toString());
  }

  /** A non-numeric input is a 400 naming it, not a 500. */
  @Test
  public void testInvalidInputIs400() throws Exception {
    doReturn(null).when(handler).findSubmittedSnapshot(2026, "0A");
    doReturn(new LinkedHashMap<String, String>()).when(handler).storedInputs(2026, "0A");
    doThrow(new IllegalArgumentException("Invalid numeric value for INGRESAR_01")).when(handler)
        .computePayload(anyString(), anyInt(), anyMap());
    HttpServletResponse resp = response(new StringWriter());

    handler.handle(Fiscal390BoxesHandler.BOXES, "GET", request("0A"), resp);

    verify(servlet).sendError(resp, HttpServletResponse.SC_BAD_REQUEST,
        "Invalid numeric value for INGRESAR_01");
  }

  /** generate is refused with 409 once the latest declaration is submitted. */
  @Test
  public void testGenerateOnSubmittedDeclarationIs409() throws Exception {
    doThrow(new AbstractFiscalHandler.AlreadySubmittedException("already submitted"))
        .when(handler).guardNotAlreadySubmitted("org1", 2026, "0A", "390");
    HttpServletResponse resp = response(new StringWriter());

    handler.handle(Fiscal390BoxesHandler.GENERATE, "GET", request("0A"), resp);

    verify(servlet).sendError(resp, HttpServletResponse.SC_CONFLICT, "already submitted");
    verify(handler, never()).storedInputs(anyInt(), anyString());
  }

  /** No yearly 390 report up to the exercise: a clear error naming the year. */
  @Test
  public void testResolveTaxReport390FailsClearly() {
    doReturn(null).when(handler).findLatestTaxReport(eq("org1"), eq("AEAT390%_Y"), any(),
        eq(2026));
    try {
      handler.resolveTaxReport390("org1", 2026);
      fail("Expected OBException");
    } catch (OBException e) {
      assertTrue(e.getMessage().contains("2026"));
    }
  }

  // ── stored / request inputs ─────────────────────────────────────────

  @Test
  public void testReadStoredInputs() {
    assertEquals(Collections.singletonMap("LIQANUAL_VAT_IMPORT", "12.5"),
        Fiscal390BoxesHandler.readStoredInputs(
            "{\"identification\":{},\"aeatInputs\":{\"LIQANUAL_VAT_IMPORT\":\"12.5\","
                + "\"ALAVA\":null}}"));
    assertTrue(Fiscal390BoxesHandler.readStoredInputs("{\"other\":1}").isEmpty());
    assertTrue(Fiscal390BoxesHandler.readStoredInputs("not json").isEmpty());
    assertTrue(Fiscal390BoxesHandler.readStoredInputs("").isEmpty());
  }

  /** Structural params and blanks are never forwarded as AEAT inputs. */
  @Test
  public void testRequestInputsSkipStructuralParams() {
    HttpServletRequest req = request("0A", "id", "D1", "since", "1", "FileName", "evil",
        "ISSUPPLEMENTARY", "Y", "ALAVA", " ");

    assertEquals(Collections.singletonMap("ISSUPPLEMENTARY", "Y"),
        Fiscal390GenerateSupport.requestInputs(req));
  }

  // ── generate ────────────────────────────────────────────────────────

  private static File tempFile(String suffix, String content) throws Exception {
    File f = File.createTempFile("etgo390test", suffix);
    Files.write(f.toPath(), content.getBytes(StandardCharsets.UTF_8));
    return f;
  }

  /**
   * The classic class's TXT is picked from "files", every temp file is deleted, the download is
   * named after the EXERCISE (never the layout year) and the resolved report is in the headers.
   */
  @Test
  public void testHandleGenerateServesTxtAndCleansUp() throws Exception {
    Fiscal390BoxesHandler owner = mock(Fiscal390BoxesHandler.class);
    TaxReport report = mock(TaxReport.class);
    when(report.getId()).thenReturn("TR");
    when(report.getJavaClassName()).thenReturn("some.Report2025");
    when(owner.resolveTaxReport390("org1", 2026)).thenReturn(
        new AbstractFiscalHandler.ResolvedTaxReport(report, "AEAT390_2025_Y", 2025, 2026));
    AcctSchema schema = mock(AcctSchema.class);
    when(schema.getId()).thenReturn("AS");
    when(owner.resolveAcctSchema()).thenReturn(schema);
    Period jan = mock(Period.class);
    Period feb = mock(Period.class);
    Year year = mock(Year.class);
    when(year.getId()).thenReturn("Y2026");
    when(jan.getYear()).thenReturn(year);
    when(jan.getId()).thenReturn("P1");
    when(feb.getId()).thenReturn("P2");
    when(owner.resolvePeriods("org1", 2026, "0A")).thenReturn(Arrays.asList(jan, feb));

    File txt = tempFile(".txt", "<T3900>Ñ");
    File csv = tempFile("Info.csv", "a;b");
    OBTL_TaxReport_I classic = mock(OBTL_TaxReport_I.class);
    HashMap<String, Object> result = new HashMap<>();
    Set<File> files = new HashSet<>(Arrays.asList(txt, csv));
    result.put("files", files);
    result.put("fileName", "390_0A_2026");
    when(classic.generateElectronicFile(eq("org1"), eq("TR"), eq("AS"), eq("Y2026"),
        eq("P1,P2"), anyMap())).thenReturn(result);

    Fiscal390GenerateSupport support = spy(new Fiscal390GenerateSupport(owner));
    doReturn(classic).when(support).newReport("some.Report2025");
    HttpServletResponse resp = mock(HttpServletResponse.class);

    support.handleGenerate("org1", 2026, Collections.singletonMap("LIQANUAL_VAT_IMPORT", "3"),
        resp);

    @SuppressWarnings("unchecked")
    ArgumentCaptor<Map<String, String>> inputs = ArgumentCaptor.forClass(Map.class);
    verify(classic).generateElectronicFile(eq("org1"), eq("TR"), eq("AS"), eq("Y2026"),
        eq("P1,P2"), inputs.capture());
    assertEquals("390_0A_2026", inputs.getValue().get("FileName"));
    assertEquals("3", inputs.getValue().get("LIQANUAL_VAT_IMPORT"));

    @SuppressWarnings("unchecked")
    ArgumentCaptor<HashMap<String, Object>> served = ArgumentCaptor.forClass(HashMap.class);
    verify(owner).writeGeneratedFile(served.capture(), eq("390_0A_2026.txt"), eq(resp));
    assertEquals("<T3900>Ñ", served.getValue().get("file"));
    verify(resp).setHeader(Fiscal390GenerateSupport.HEADER_TAX_REPORT, "AEAT390_2025_Y");
    verify(resp).setHeader(Fiscal390GenerateSupport.HEADER_LAYOUT_YEAR, "2025");
    assertFalse("txt must be deleted", txt.exists());
    assertFalse("csv must be deleted", csv.exists());
  }

  /** No TXT in the result: an explicit error, and the other temp files are still deleted. */
  @Test
  public void testReadTxtContentWithoutTxtFails() throws Exception {
    File csv = tempFile("Info.csv", "a;b");
    Map<String, Object> result = new HashMap<>();
    result.put("files", Collections.singleton(csv));
    try {
      Fiscal390GenerateSupport.readTxtContent(result);
      fail("Expected OBException");
    } catch (OBException e) {
      assertTrue(e.getMessage().contains("TXT"));
    } finally {
      Fiscal390GenerateSupport.deleteReturnedFiles(result);
    }
    assertFalse(csv.exists());
  }

  /** A report class returning the 303 shape ("file") is served as-is. */
  @Test
  public void testReadTxtContentAcceptsInlineFile() throws Exception {
    assertEquals("content",
        Fiscal390GenerateSupport.readTxtContent(Collections.singletonMap("file", "content")));
  }

  @Test
  public void testFileNameUsesExerciseYear() {
    assertEquals("390_0A_2026", Fiscal390GenerateSupport.fileName(2026));
  }

  // ── payload / snapshot ──────────────────────────────────────────────

  @Test
  public void testBuildResponseShape() throws Exception {
    Fiscal390BoxesCalculator.Result result = new Fiscal390BoxesCalculator.Result();
    result.boxes.put(47, new BigDecimal("100.00"));
    result.boxes.put(64, new BigDecimal("40.00"));
    result.boxes.put(65, new BigDecimal("60.00"));
    result.boxes.put(86, new BigDecimal("55.00"));
    result.mode = Fiscal390DerivedBoxes.MODE_LIQUIDATION;
    Map<String, String> w = new LinkedHashMap<>();
    w.put("code", "NO_BOX");
    w.put("parameter", "P");
    result.warnings.add(w);
    TaxReport report = mock(TaxReport.class);

    JSONObject json = Fiscal390SnapshotSupport.buildResponse(result,
        new AbstractFiscalHandler.ResolvedTaxReport(report, "AEAT390_2025_Y", 2025, 2026),
        Collections.singletonMap("LIQANUAL_VAT_IMPORT", new BigDecimal("5")));

    assertEquals("60.00", json.getJSONObject("boxes").getString("65"));
    assertEquals("100.00", json.getJSONObject("summary").getString("accrued"));
    assertEquals("40.00", json.getJSONObject("summary").getString("deductible"));
    assertEquals("60.00", json.getJSONObject("summary").getString("result"));
    assertEquals("55.00", json.getJSONObject("summary").getString("annualResult"));
    assertEquals("liquidation", json.getString("mode"));
    JSONObject tr = json.getJSONObject("taxReport");
    assertEquals("AEAT390_2025_Y", tr.getString("searchKey"));
    assertEquals(2025, tr.getInt("layoutYear"));
    assertTrue(tr.getBoolean("fallback"));
    assertEquals("5", json.getJSONObject("inputs").getString("LIQANUAL_VAT_IMPORT"));
    assertEquals("NO_BOX", json.getJSONArray("warnings").getJSONObject(0).getString("code"));
  }

  /** The snapshot keeps the whole payload, taxReport included (nothing excluded). */
  @Test
  public void testSnapshotKeepsTaxReport() throws Exception {
    Fiscal390SnapshotSupport support = new Fiscal390SnapshotSupport();
    JSONObject payload = new JSONObject(
        "{\"boxes\":{},\"taxReport\":{\"searchKey\":\"AEAT390_2025_Y\"}}");
    assertEquals(payload.toString(), support.toSnapshot(new JSONObject(payload.toString()))
        .toString());
    assertEquals("390", support.declModel());
  }

  /** The submission snapshot is computed from the inputs stored on the declaration. */
  @Test
  public void testLivePayloadUsesStoredInputs() throws Exception {
    Map<String, String> stored = Collections.singletonMap("INGRESAR_01", "9");
    doReturn(stored).when(handler).storedInputs(2026, "0A");
    JSONObject live = new JSONObject("{\"boxes\":{}}");
    doReturn(live).when(handler).computePayload("org1", 2026, stored);

    assertEquals(live, handler.computeLivePayload("org1", 2026, "0A"));
  }
}
