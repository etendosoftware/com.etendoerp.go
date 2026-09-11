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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import javax.servlet.ServletOutputStream;
import javax.servlet.WriteListener;
import javax.servlet.http.HttpServletResponse;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Unit tests for {@link NeoCsvExportService} — pure (no DB / OBContext). Builds
 * the standard list envelope, drives {@code tryExport}, and asserts the streamed
 * CSV plus the download headers.
 */
class NeoCsvExportServiceTest {

  /** Query params and envelope keys the neutralization cases build over and over. */
  private static final String EXPORT_PARAM = "export";
  private static final String CSV_FORMAT = "csv";
  private static final String COLUMNS_PARAM = "columns";
  private static final String STATEMENTS_KEY = "statements";
  private static final String CELL_KEY = "cell";
  private static final String CELL_LABEL = "Cell";
  /** The CSV writer's line separator, as a {@code String.split} regex. */
  private static final String CRLF_SEPARATOR = "\\r\\n";

  private static NeoResponse envelope(String key, JSONArray rows) throws Exception {
    JSONObject data = new JSONObject().put(key, rows);
    JSONObject responseData = new JSONObject().put("data", data);
    JSONObject body = new JSONObject().put("response", responseData);
    return NeoResponse.ok(body);
  }

  /**
   * The OTHER shape a NEO list GET returns: the standard Openbravo envelope from
   * {@code DefaultJsonDataService.fetch}, where {@code response.data} is the row array itself
   * rather than an object of named collections. This is what every generic CRUD list produces —
   * the contacts and products list exports (ETP-4997) among them.
   */
  private static NeoResponse flatEnvelope(JSONArray rows) throws Exception {
    JSONObject responseData = new JSONObject()
        .put("startRow", 0)
        .put("endRow", Math.max(0, rows.length() - 1))
        .put("totalRows", rows.length())
        .put("data", rows)
        .put("status", 0);
    JSONObject body = new JSONObject().put("response", responseData);
    return NeoResponse.ok(body);
  }

  private static Map<String, String> params(String... kv) {
    Map<String, String> m = new HashMap<>();
    for (int i = 0; i + 1 < kv.length; i += 2) {
      m.put(kv[i], kv[i + 1]);
    }
    return m;
  }

  /** Mocked response whose writer captures everything into a StringWriter. */
  private static class CapturingResponse {
    final StringWriter sw = new StringWriter();
    final HttpServletResponse response;

    CapturingResponse() throws IOException {
      response = mock(HttpServletResponse.class);
      when(response.getWriter()).thenReturn(new PrintWriter(sw));
    }

    String csv() {
      return sw.toString();
    }
  }

  /**
   * Mocked response whose OutputStream captures the workbook bytes, so an xlsx export can be
   * read back with POI and asserted cell by cell rather than merely "did not throw".
   */
  private static class CapturingBinaryResponse {
    final ByteArrayOutputStream out = new ByteArrayOutputStream();
    final HttpServletResponse response;

    CapturingBinaryResponse() throws IOException {
      response = mock(HttpServletResponse.class);
      when(response.getOutputStream()).thenReturn(new ServletOutputStream() {
        @Override public void write(int b) {
          out.write(b);
        }

        @Override public boolean isReady() {
          return true;
        }

        @Override public void setWriteListener(WriteListener listener) {
          // Nothing to notify: this stream is always ready.
        }
      });
    }

    /** The streamed workbook's single sheet, as rows of raw cell strings. */
    List<List<String>> sheetRows() throws Exception {
      try (Workbook workbook = new XSSFWorkbook(new ByteArrayInputStream(out.toByteArray()))) {
        assertEquals(1, workbook.getNumberOfSheets(),
            "parseXlsx rejects a workbook with two data sheets, so the export must write one");
        Sheet sheet = workbook.getSheetAt(0);
        List<List<String>> rows = new ArrayList<>();
        for (Row row : sheet) {
          List<String> cells = new ArrayList<>();
          for (int c = 0; c < row.getLastCellNum(); c++) {
            Cell cell = row.getCell(c);
            // Deliberately getStringCellValue, not a formatter: it throws on a non-string cell,
            // which is exactly the assertion — every exported cell must be a STRING cell.
            cells.add(cell == null ? "" : cell.getStringCellValue());
          }
          rows.add(cells);
        }
        return rows;
      }
    }

    /** Cell types of one row, to assert nothing was written as a number, date or formula. */
    List<CellType> cellTypes(int rowIndex) throws Exception {
      try (Workbook workbook = new XSSFWorkbook(new ByteArrayInputStream(out.toByteArray()))) {
        Row row = workbook.getSheetAt(0).getRow(rowIndex);
        List<CellType> types = new ArrayList<>();
        for (int c = 0; c < row.getLastCellNum(); c++) {
          types.add(row.getCell(c).getCellType());
        }
        return types;
      }
    }
  }

  // ── export=xlsx (ETP-4997) ──────────────────────────────────────────────────

  @Test
  void streamsAnXlsxWorkbookWithTheRequestedColumnsAndHeaders() throws Exception {
    CapturingBinaryResponse cap = new CapturingBinaryResponse();
    JSONArray rows = new JSONArray()
        .put(new JSONObject().put("id", "b1").put("name", "Distribuciones García S.L.").put("postal", "08018"))
        .put(new JSONObject().put("id", "b2").put("name", "Talleres Molina").put("postal", "41002"));

    boolean handled = NeoCsvExportService.tryExport(flatEnvelope(rows),
        params("export", "xlsx", "columns", "name:Razón Social|postal:Código Postal"), cap.response);

    assertTrue(handled);
    assertEquals(
        List.of(List.of("Razón Social", "Código Postal"),
                List.of("Distribuciones García S.L.", "08018"),
                List.of("Talleres Molina", "41002")),
        cap.sheetRows());
  }

  /**
   * The decision the whole round trip rests on. A numeric cell would come back from the import
   * reader as the NUMBER 8018 — leading zero gone, unrecoverably — so every cell must be a
   * STRING cell, including ones whose content looks like a number or a date.
   */
  @Test
  void writesEveryCellAsAStringCellEvenWhenItLooksNumericOrLikeADate() throws Exception {
    CapturingBinaryResponse cap = new CapturingBinaryResponse();
    JSONArray rows = new JSONArray().put(new JSONObject()
        .put("id", "b1").put("postal", "08018").put("importe", "1234.56").put("fecha", "2026-08-31"));

    NeoCsvExportService.tryExport(flatEnvelope(rows),
        params("export", "xlsx", "columns", "postal:CP|importe:Importe|fecha:Fecha:date"), cap.response);

    assertEquals(List.of(CellType.STRING, CellType.STRING, CellType.STRING), cap.cellTypes(1));
    assertEquals(List.of("08018", "1234.56", "31-08-2026"), cap.sheetRows().get(1));
  }

  /**
   * The mirror of the CSV behaviour, and it must NOT be shared. CSV prefixes an apostrophe to
   * neutralize formula injection; an xlsx string cell is inert (a formula is a different cell
   * type), so the same prefix would be a literal character in the user's spreadsheet.
   */
  @Test
  void doesNotApplyTheCsvFormulaApostropheToAnXlsxCell() throws Exception {
    CapturingBinaryResponse cap = new CapturingBinaryResponse();
    JSONArray rows = new JSONArray().put(new JSONObject().put("id", "b1").put("name", "=SUM(A1)"));

    NeoCsvExportService.tryExport(flatEnvelope(rows),
        params("export", "xlsx", "columns", "name:Nombre"), cap.response);

    assertEquals("=SUM(A1)", cap.sheetRows().get(1).get(0));
    assertEquals(CellType.STRING, cap.cellTypes(1).get(0));
  }

  @Test
  void appliesValueMapsAndTheIdFilterToAnXlsxExportToo() throws Exception {
    CapturingBinaryResponse cap = new CapturingBinaryResponse();
    JSONArray rows = new JSONArray()
        .put(new JSONObject().put("id", "b1").put("etgoIsperson", "false"))
        .put(new JSONObject().put("id", "b2").put("etgoIsperson", "true"));

    NeoCsvExportService.tryExport(flatEnvelope(rows),
        params("export", "xlsx", "columns", "etgoIsperson:Tipo", "ids", "b2",
            "valueMaps", "{\"etgoIsperson\":{\"true\":\"Persona\",\"false\":\"Empresa\"}}"),
        cap.response);

    assertEquals(List.of(List.of("Tipo"), List.of("Persona")), cap.sheetRows());
  }

  @Test
  void namesTheXlsxDownloadWithAnXlsxExtensionEvenWhenTheCallerAskedForCsv() throws Exception {
    CapturingBinaryResponse cap = new CapturingBinaryResponse();
    JSONArray rows = new JSONArray().put(new JSONObject().put("id", "b1").put("name", "Ana"));

    NeoCsvExportService.tryExport(flatEnvelope(rows),
        params("export", "xlsx", "filename", "contacts-export.csv", "columns", "name:Nombre"),
        cap.response);

    verify(cap.response).setHeader(eq("Content-Disposition"), contains("contacts-export.xlsx"));
    verify(cap.response).setContentType(NeoXlsxExportWriter.CONTENT_TYPE);
  }

  @Test
  void neverTouchesTheWriterOnAnXlsxExport() throws Exception {
    // getWriter() and getOutputStream() are mutually exclusive on one response; touching both
    // throws IllegalStateException at runtime, not at compile time.
    CapturingBinaryResponse cap = new CapturingBinaryResponse();
    JSONArray rows = new JSONArray().put(new JSONObject().put("id", "b1").put("name", "Ana"));

    NeoCsvExportService.tryExport(flatEnvelope(rows),
        params("export", "xlsx", "columns", "name:Nombre"), cap.response);

    verify(cap.response, never()).getWriter();
  }

  @Test
  void declinesAnUnrecognizedExportFormatInsteadOfGuessing() throws Exception {
    CapturingResponse cap = new CapturingResponse();
    JSONArray rows = new JSONArray().put(new JSONObject().put("id", "b1").put("name", "Ana"));

    boolean handled = NeoCsvExportService.tryExport(flatEnvelope(rows),
        params("export", "ods"), cap.response);

    assertFalse(handled, "an unknown format must fall through to the JSON response");
    assertEquals("", cap.csv());
  }

  @Test
  void acceptsTheFormatCaseInsensitively() throws Exception {
    CapturingBinaryResponse cap = new CapturingBinaryResponse();
    JSONArray rows = new JSONArray().put(new JSONObject().put("id", "b1").put("name", "Ana"));

    boolean handled = NeoCsvExportService.tryExport(flatEnvelope(rows),
        params("export", "XLSX", "columns", "name:Nombre"), cap.response);

    assertTrue(handled);
    assertEquals(List.of("Ana"), cap.sheetRows().get(1));
  }

  @Test
  void doesNothingWithoutTheExportFlag() throws Exception {
    CapturingResponse cap = new CapturingResponse();
    NeoResponse res = envelope("statements", new JSONArray().put(new JSONObject().put("id", "s1")));

    boolean handled = NeoCsvExportService.tryExport(res, params("foo", "bar"), cap.response);

    assertFalse(handled);
    verify(cap.response, never()).getWriter();
  }

  @Test
  void serializesSelectedColumnsWithLabelsDateFormattingAndEscaping() throws Exception {
    CapturingResponse cap = new CapturingResponse();
    JSONArray rows = new JSONArray().put(new JSONObject()
        .put("id", "s1")
        .put("documentNo", "BS-001")
        .put("importDate", "2026-06-10")
        .put("note", "a,b\"c"));
    NeoResponse res = envelope("statements", rows);

    boolean handled = NeoCsvExportService.tryExport(
        res,
        params("export", "csv",
            "columns", "documentNo:Document No.|importDate:Import Date:date|note:Note",
            "filename", "Extractos"),
        cap.response);

    assertTrue(handled);
    String csv = cap.csv();
    // Header uses the labels; rows use the values, with ISO date → dd-MM-yyyy.
    assertTrue(csv.contains("\"Document No.\",\"Import Date\",\"Note\""), csv);
    assertTrue(csv.contains("\"BS-001\",\"10-06-2026\""), csv);
    // RFC 4180: inner quotes doubled, comma kept inside the quoted field.
    assertTrue(csv.contains("\"a,b\"\"c\""), csv);

    verify(cap.response).setContentType(contains("text/csv"));
    verify(cap.response).setHeader(eq("Content-Disposition"), contains("Extractos.csv"));
  }

  @Test
  void keepsOnlyRowsWhoseIdIsInTheIdsFilter() throws Exception {
    CapturingResponse cap = new CapturingResponse();
    JSONArray rows = new JSONArray()
        .put(new JSONObject().put("id", "s1").put("documentNo", "KEEP"))
        .put(new JSONObject().put("id", "s2").put("documentNo", "DROP"));
    NeoResponse res = envelope("statements", rows);

    NeoCsvExportService.tryExport(res,
        params("export", "csv", "ids", "s1", "columns", "documentNo:Doc"), cap.response);

    String csv = cap.csv();
    assertTrue(csv.contains("KEEP"), csv);
    assertFalse(csv.contains("DROP"), csv);
  }

  @Test
  void resolvesDottedPathsIntoNestedArrays() throws Exception {
    CapturingResponse cap = new CapturingResponse();
    JSONArray rows = new JSONArray().put(new JSONObject()
        .put("id", "l1")
        .put("txns", new JSONArray().put(new JSONObject().put("documentNo", "PAY-1"))));
    NeoResponse res = envelope("lines", rows);

    NeoCsvExportService.tryExport(res,
        params("export", "csv", "columns", "txns.0.documentNo:Transaction"), cap.response);

    String csv = cap.csv();
    assertTrue(csv.contains("\"Transaction\""), csv);
    assertTrue(csv.contains("\"PAY-1\""), csv);
  }

  @Test
  void derivesColumnsFromTheFirstRowWhenNoColumnsGiven() throws Exception {
    CapturingResponse cap = new CapturingResponse();
    JSONArray rows = new JSONArray().put(new JSONObject().put("id", "s1").put("documentNo", "BS-9"));
    NeoResponse res = envelope("statements", rows);

    boolean handled = NeoCsvExportService.tryExport(res, params("export", "csv"), cap.response);

    assertTrue(handled);
    String csv = cap.csv();
    assertTrue(csv.contains("\"id\""), csv);
    assertTrue(csv.contains("\"documentNo\""), csv);
    assertTrue(csv.contains("\"BS-9\""), csv);
  }

  @Test
  void emptyRowArrayStillStreamsTheHeaderOnly() throws Exception {
    CapturingResponse cap = new CapturingResponse();
    NeoResponse res = envelope("statements", new JSONArray());

    boolean handled = NeoCsvExportService.tryExport(res,
        params("export", "csv", "columns", "documentNo:Doc|name:Name"), cap.response);

    assertTrue(handled);
    String csv = cap.csv();
    assertTrue(csv.contains("\"Doc\",\"Name\""), csv);
    // Only the header line (plus the leading BOM); no data rows.
    assertFalse(csv.replace("﻿", "").trim().contains("\n"), csv);
  }

  // ── Spreadsheet formula neutralization (ETP-5032, closes SEC-04) ──────────
  //
  // The cases below are the canonical cross-runtime contract, not this class's own
  // choice of inputs: docs/security/csv-neutralization-fixtures.md (referenced by
  // ADR-0004 D2) is the specification, and its executable twin for the two JavaScript
  // implementations is
  // @etendosoftware/app-shell-core/lib/csv/csvNeutralizationFixtures.js. A trigger is
  // added to that table FIRST, then to every implementation — so this stream must stay
  // in step with it.

  /**
   * The canonical fixture table: {@code description, input, expected cell value}. "Expected"
   * is the value AFTER neutralization and BEFORE CSV quoting, which is why every assertion
   * below wraps it in {@link #quotedField}.
   *
   * <p>The {@code null} row of the markdown table is covered by two dedicated tests instead
   * ({@link #writesAnEmptyCellForAJsonNullValue} and the pre-existing missing-key test),
   * because a JSON row expresses "no value" as an absent key or {@code JSONObject.NULL},
   * never as a Java {@code null} cell.
   *
   * @return one {@link Arguments} triple per fixture row
   */
  static Stream<Arguments> spreadsheetNeutralizationFixtures() {
    return Stream.of(
        Arguments.of("Equals", "=1+1", "'=1+1"),
        Arguments.of("Plus", "+SUM(A1:A2)", "'+SUM(A1:A2)"),
        Arguments.of("Minus / DDE-like", "-CMD", "'-CMD"),
        Arguments.of("At sign", "@SUM(A1:A2)", "'@SUM(A1:A2)"),
        Arguments.of("DDE command payload", "+cmd|' /C calc'!A0", "'+cmd|' /C calc'!A0"),
        Arguments.of("HYPERLINK payload", "=HYPERLINK(\"http://example.com\",\"Click\")",
            "'=HYPERLINK(\"http://example.com\",\"Click\")"),
        Arguments.of("Marker behind spaces", "   =1+1", "'   =1+1"),
        Arguments.of("Marker behind TAB", "\t=1+1", "'\t=1+1"),
        Arguments.of("Marker behind CR", "\r=1+1", "'\r=1+1"),
        Arguments.of("Marker behind LF", "\n=1+1", "'\n=1+1"),
        Arguments.of("TAB as first standalone control", "\tText", "'\tText"),
        Arguments.of("CR as first standalone control", "\rText", "'\rText"),
        Arguments.of("LF as first standalone control", "\nText", "'\nText"),
        Arguments.of("BOM before marker", "\uFEFF=1+1", "'\uFEFF=1+1"),
        Arguments.of("NBSP before marker", "\u00A0=1+1", "'\u00A0=1+1"),
        Arguments.of("Full-width equals", "\uFF1D1+1", "'\uFF1D1+1"),
        Arguments.of("Full-width plus", "\uFF0BSUM(A1:A2)", "'\uFF0BSUM(A1:A2)"),
        Arguments.of("Full-width minus", "\uFF0DCMD", "'\uFF0DCMD"),
        Arguments.of("Full-width at", "\uFF20SUM(A1:A2)", "'\uFF20SUM(A1:A2)"),
        Arguments.of("Already neutralized", "'=1+1", "'=1+1"),
        Arguments.of("Negative number", "-500.00", "'-500.00"),
        Arguments.of("Plain text", "Normal Value", "Normal Value"),
        Arguments.of("Plain text behind spaces", "  Normal Value", "  Normal Value"),
        Arguments.of("Trigger not in first position", "Total = 1+1", "Total = 1+1"),
        Arguments.of("Empty", "", ""));
  }

  /**
   * The same fixtures, minus the rows a column LABEL cannot express, because
   * {@code NeoExportTable.parseColumns} normalizes the {@code columns} spec before the writer
   * ever sees it: it splits on {@code |} and {@code :}, falls back to the column key for a
   * blank label, and TRIMS what is left. So a label whose payload is leading whitespace (or
   * TAB/CR/LF) cannot survive the parser and is not a neutralization concern — those triggers
   * are covered on the data-cell path, where nothing trims.
   *
   * <p>Filtering here rather than branching inside the test keeps the assertion
   * unconditional: every case this stream yields must be neutralized, with no escape hatch.
   *
   * @return the fixture rows that survive the columns-spec parser unchanged
   */
  static Stream<Arguments> headerLabelFixtures() {
    return spreadsheetNeutralizationFixtures().filter(args -> {
      String label = (String) args.get()[1];
      return !label.isEmpty()
          && label.equals(label.trim())
          && label.indexOf('|') < 0
          && label.indexOf(':') < 0;
    });
  }

  /**
   * Every fixture row, asserted on a DATA cell.
   *
   * @param description fixture label, used as the test name
   * @param input       the stored value
   * @param expected    the cell value after neutralization, before quoting
   * @throws Exception if the envelope cannot be built or the response cannot be written
   */
  @ParameterizedTest(name = "data cell — {0}")
  @MethodSource("spreadsheetNeutralizationFixtures")
  void neutralizesEveryCanonicalFixtureInADataCell(String description, String input,
      String expected) throws Exception {
    String csv = exportOneCell(input);

    assertTrue(csv.contains(quotedField(expected)), description + " → " + csv);
  }

  /**
   * Every fixture row, asserted on a HEADER LABEL. Labels are attacker-influenced too:
   * they derive from AD field names and from the caller-supplied {@code columns} spec
   * (ADR-0004 D3 requires both header and data cells to be neutralized).
   *
   * @param description fixture label, used as the test name
   * @param input       the column label
   * @param expected    the label after neutralization, before quoting
   * @throws Exception if the envelope cannot be built or the response cannot be written
   */
  @ParameterizedTest(name = "header label — {0}")
  @MethodSource("headerLabelFixtures")
  void neutralizesEveryCanonicalFixtureInAHeaderLabel(String description, String input,
      String expected) throws Exception {
    CapturingResponse cap = new CapturingResponse();
    JSONArray rows = new JSONArray().put(new JSONObject().put(CELL_KEY, "value"));

    NeoCsvExportService.tryExport(envelope(STATEMENTS_KEY, rows),
        params(EXPORT_PARAM, CSV_FORMAT, COLUMNS_PARAM, CELL_KEY + ":" + input), cap.response);

    assertTrue(cap.csv().contains(quotedField(expected)), description + " → " + cap.csv());
  }

  /**
   * Pins the normative ordering: neutralize, THEN quote. The apostrophe must land INSIDE the
   * quoted field, the embedded quote must be doubled, and the comma and newline must not split
   * the cell — one RFC 4180 field, however the value is built.
   *
   * @throws Exception if the envelope cannot be built or the response cannot be written
   */
  @Test
  void neutralizesBeforeQuotingWhenTheTriggerArrivesWithDelimitersAndQuotes() throws Exception {
    String csv = exportOneCell("=1+1,\"x\"\nnext");

    assertTrue(csv.contains("\"'=1+1,\"\"x\"\"\nnext\""), csv);
    // The apostrophe is never emitted outside the quotes.
    assertFalse(csv.contains("'\"=1+1"), csv);
  }

  /**
   * A JSON null cell exports as an empty field, with no apostrophe: there is nothing for a
   * spreadsheet to interpret.
   *
   * @throws Exception if the envelope cannot be built or the response cannot be written
   */
  @Test
  void writesAnEmptyCellForAJsonNullValue() throws Exception {
    CapturingResponse cap = new CapturingResponse();
    JSONArray rows = new JSONArray().put(new JSONObject().put(CELL_KEY, JSONObject.NULL));

    NeoCsvExportService.tryExport(envelope(STATEMENTS_KEY, rows),
        params(EXPORT_PARAM, CSV_FORMAT, COLUMNS_PARAM, CELL_KEY + ":" + CELL_LABEL),
        cap.response);

    // The data line is exactly one empty quoted field — not an apostrophe, not the literal
    // string "null".
    String[] lines = cap.csv().split(CRLF_SEPARATOR);
    assertEquals(2, lines.length, cap.csv());
    assertEquals("\"\"", lines[1], cap.csv());
  }

  /**
   * Exports {@code value} as the single cell of a single row and returns the streamed CSV.
   * Collapses the envelope-plus-params boilerplate every neutralization case would otherwise
   * repeat.
   *
   * @param value the cell value to export
   * @return the streamed CSV, BOM included
   * @throws Exception if the envelope cannot be built or the response cannot be written
   */
  private static String exportOneCell(String value) throws Exception {
    CapturingResponse cap = new CapturingResponse();
    JSONArray rows = new JSONArray().put(new JSONObject().put(CELL_KEY, value));

    boolean handled = NeoCsvExportService.tryExport(envelope(STATEMENTS_KEY, rows),
        params(EXPORT_PARAM, CSV_FORMAT, COLUMNS_PARAM, CELL_KEY + ":" + CELL_LABEL),
        cap.response);

    assertTrue(handled, "tryExport declined an export=csv request");
    return cap.csv();
  }

  /**
   * Wraps an expected cell value the way the CSV writer does: always quoted, inner quotes
   * doubled. Keeps the fixture table free of quoting noise so it stays comparable, row for
   * row, with the markdown specification.
   *
   * @param cell expected cell value after neutralization
   * @return the expected RFC 4180 field
   */
  private static String quotedField(String cell) {
    return "\"" + cell.replace("\"", "\"\"") + "\"";
  }

  // ETP-4997 — a generic CRUD list (contacts, products) returns `response.data` as the array
  // itself. Only the nested shape used to be recognized, so `tryExport` declined and the browser
  // saved a .csv file containing the raw JSON envelope.
  @Test
  void exportsAGenericCrudListWhoseDataIsTheRowArrayItself() throws Exception {
    CapturingResponse cap = new CapturingResponse();
    JSONArray rows = new JSONArray()
        .put(new JSONObject().put("id", "bp1").put("name", "Blanquiceleste S.A.").put("taxID", "232323"))
        .put(new JSONObject().put("id", "bp2").put("name", "Juan Perez").put("taxID", "K01927367"));

    boolean handled = NeoCsvExportService.tryExport(flatEnvelope(rows),
        params("export", "csv", "columns", "name:nombre comercial *|taxID:cif/nif *"), cap.response);

    assertTrue(handled);
    verify(cap.response).setContentType(contains("text/csv"));
    String csv = cap.csv();
    assertTrue(csv.contains("\"nombre comercial *\",\"cif/nif *\""), csv);
    assertTrue(csv.contains("\"Blanquiceleste S.A.\",\"232323\""), csv);
    assertTrue(csv.contains("\"Juan Perez\",\"K01927367\""), csv);
  }

  // A column whose key is absent from the row is what carries Contacts' ten contact-scoped
  // template columns (address, city, …): the header must still be written, with an empty cell.
  @Test
  void writesAnEmptyCellForAColumnTheRowDoesNotCarry() throws Exception {
    CapturingResponse cap = new CapturingResponse();
    JSONArray rows = new JSONArray().put(new JSONObject().put("name", "Blanquiceleste S.A."));

    boolean handled = NeoCsvExportService.tryExport(flatEnvelope(rows),
        params("export", "csv", "columns", "name:nombre comercial|:ciudad|:pais"), cap.response);

    assertTrue(handled);
    String csv = cap.csv();
    assertTrue(csv.contains("\"nombre comercial\",\"ciudad\",\"pais\""), csv);
    assertTrue(csv.contains("\"Blanquiceleste S.A.\",\"\",\"\""), csv);
  }

  // FK columns are exported through their `$_identifier` companion, which is a flat key on the
  // row — not a dotted path — so the readable label is what lands in the file.
  @Test
  void resolvesAnIdentifierCompanionKeyOnAFlatRow() throws Exception {
    CapturingResponse cap = new CapturingResponse();
    JSONArray rows = new JSONArray().put(new JSONObject()
        .put("businessPartnerCategory", "01099ABCE98A40BBA20022ABDD10FD2F")
        .put("businessPartnerCategory$_identifier", "Proveedor"));

    boolean handled = NeoCsvExportService.tryExport(flatEnvelope(rows),
        params("export", "csv", "columns", "businessPartnerCategory$_identifier:categoria"),
        cap.response);

    assertTrue(handled);
    String csv = cap.csv();
    assertTrue(csv.contains("\"categoria\""), csv);
    assertTrue(csv.contains("\"Proveedor\""), csv);
    assertFalse(csv.contains("01099ABCE98A40BBA20022ABDD10FD2F"), csv);
  }

  // A GET that is not a list at all (a single record, an action result) must still decline
  // rather than stream a bogus file.
  @Test
  void declinesWhenTheEnvelopeCarriesNoRowArray() throws Exception {
    CapturingResponse cap = new CapturingResponse();
    JSONObject responseData = new JSONObject().put("data", new JSONObject().put("id", "bp1"));
    NeoResponse res = NeoResponse.ok(new JSONObject().put("response", responseData));

    boolean handled = NeoCsvExportService.tryExport(res, params("export", "csv"), cap.response);

    assertFalse(handled);
    verify(cap.response, never()).getWriter();
  }

  // ETP-4997 — a raw list row carries codes ("false", "6", "I"), which are unreadable in a
  // spreadsheet and defeat the edit half of export -> edit -> import. The caller maps them to
  // the words its importer accepts back.
  @Test
  void translatesCodedValuesIntoTheLabelsTheCallerSupplied() throws Exception {
    CapturingResponse cap = new CapturingResponse();
    JSONArray rows = new JSONArray()
        .put(new JSONObject().put("etgoIsperson", false).put("oBTIKTaxIDKey", "1"))
        .put(new JSONObject().put("etgoIsperson", true).put("oBTIKTaxIDKey", "6"));

    boolean handled = NeoCsvExportService.tryExport(flatEnvelope(rows),
        params("export", "csv",
            "columns", "etgoIsperson:tipo|oBTIKTaxIDKey:clave nif",
            "valueMaps", "{\"etgoIsperson\":{\"true\":\"Persona\",\"false\":\"Empresa\"},"
                + "\"oBTIKTaxIDKey\":{\"1\":\"NIF\",\"6\":\"Otro documento probatorio\"}}"),
        cap.response);

    assertTrue(handled);
    String csv = cap.csv();
    assertTrue(csv.contains("\"Empresa\",\"NIF\""), csv);
    assertTrue(csv.contains("\"Persona\",\"Otro documento probatorio\""), csv);
    assertFalse(csv.contains("\"false\""), csv);
    assertFalse(csv.contains("\"6\""), csv);
  }

  // An unmapped value must survive untouched, and a blank cell must STAY blank: empty means
  // "this row says nothing about the field", which is how the importer reads it back too.
  @Test
  void leavesUnmappedAndBlankValuesAlone() throws Exception {
    CapturingResponse cap = new CapturingResponse();
    JSONArray rows = new JSONArray().put(new JSONObject()
        .put("productType", "R")
        .put("name", "Tornillo")
        .put("blank", ""));

    boolean handled = NeoCsvExportService.tryExport(flatEnvelope(rows),
        params("export", "csv",
            "columns", "productType:tipo|name:nombre|blank:vacio",
            "valueMaps", "{\"productType\":{\"I\":\"Articulo\"},\"blank\":{\"\":\"NO\"}}"),
        cap.response);

    assertTrue(handled);
    String csv = cap.csv();
    // 'R' has no entry in the supplied map, so it is written as-is rather than blanked.
    assertTrue(csv.contains("\"R\",\"Tornillo\",\"\""), csv);
    assertFalse(csv.contains("NO"), csv);
  }

  // A malformed map must not cost the user their export: the file is still correct, just with
  // raw codes in the columns that would have been translated.
  @Test
  void ignoresAMalformedValueMapsParameter() throws Exception {
    CapturingResponse cap = new CapturingResponse();
    JSONArray rows = new JSONArray().put(new JSONObject().put("productType", "S"));

    boolean handled = NeoCsvExportService.tryExport(flatEnvelope(rows),
        params("export", "csv", "columns", "productType:tipo", "valueMaps", "{not json"),
        cap.response);

    assertTrue(handled);
    assertTrue(cap.csv().contains("\"S\""), cap.csv());
  }
}
