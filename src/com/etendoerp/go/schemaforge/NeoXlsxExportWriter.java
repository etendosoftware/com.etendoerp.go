package com.etendoerp.go.schemaforge;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;

import javax.servlet.http.HttpServletResponse;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;

/**
 * Streams a {@link NeoExportTable} as an `.xlsx` attachment (ETP-4997), the Excel twin of
 * {@code NeoCsvExportService}'s CSV branch. Window-agnostic: it serializes whatever table it is
 * handed and knows nothing about any entity.
 *
 * <p><b>Every cell is written as a string, and that is the load-bearing decision.</b> Measured
 * against the reader the import uses: a text cell round-trips byte-exactly — {@code '08018'}
 * keeps its leading zero, {@code '1.234,56'} keeps its separators, a date keeps the exact
 * {@code dd-MM-yyyy} the CSV writes — whereas a typed cell does not. Written as a number,
 * {@code 08018} comes back as {@code 8018} and the zero is gone for good; written as a date, it
 * comes back as an instant that Excel then renders in the reader's own locale. Since the entire
 * point of the export is the loop export → edit → import, typed cells would trade the feature's
 * only real guarantee for prettier sorting in Excel.
 *
 * <p>A second consequence, easy to get wrong in the other direction: the CSV writer neutralizes
 * spreadsheet formula injection by prefixing an apostrophe to a value starting with
 * {@code = + - @}. That must NOT happen here. An xlsx string cell is inert — a formula is a
 * different cell type entirely, so {@code =SUM(A1)} stored as a string is just text — and the
 * apostrophe would show up as a literal character in the user's spreadsheet. Verified by reading
 * a written workbook back: the string survives as {@code "=SUM(A1)"}, uninterpreted.
 *
 * <p>{@link SXSSFWorkbook} rather than XSSF: it keeps only a sliding window of rows in memory and
 * flushes the rest to a temp file, so the documented invariant that a 5000-row export never
 * materializes now holds in the JVM too, not only in the browser.
 */
final class NeoXlsxExportWriter {

  static final String CONTENT_TYPE =
      "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

  /**
   * Rows kept in memory before being flushed to disk. 100 is POI's own default and is ample: a
   * flushed row cannot be revisited, and nothing here writes backwards.
   */
  private static final int ROW_WINDOW = 100;

  /** Sheet name. Singular and fixed — {@code parseXlsx} rejects a workbook with two data sheets. */
  private static final String SHEET_NAME = "Export";

  private NeoXlsxExportWriter() {
  }

  /**
   * Writes {@code table} to the response as an xlsx attachment.
   *
   * <p>The bytes go to {@link HttpServletResponse#getOutputStream()}. That is not
   * interchangeable with {@code getWriter()}: a servlet response permits one or the other, and
   * calling both throws {@code IllegalStateException} at runtime. So this method must be the only
   * thing that touches the response body — the CSV branch keeps the writer, this one the stream.
   *
   * @param table the resolved rows and columns.
   * @param filename the download filename, already sanitized and suffixed.
   * @param response the servlet response to stream to.
   */
  static void write(NeoExportTable table, String filename, HttpServletResponse response)
      throws IOException {
    response.setStatus(HttpServletResponse.SC_OK);
    response.setContentType(CONTENT_TYPE);
    response.setHeader("Content-Disposition", "attachment; filename=\"" + filename + "\"");

    // try-with-resources is the whole cleanup story: SXSSF spills rows past its window to temp
    // files, and in POI 5.4 `close()` removes them — which is exactly why `dispose()` is
    // deprecated there. Calling both would be redundant and would compile with a deprecation
    // warning. What matters is that SOMETHING always runs: without it the temp files survive
    // until the JVM exits, so a client disconnecting mid-download would leak one per attempt.
    try (SXSSFWorkbook workbook = new SXSSFWorkbook(ROW_WINDOW)) {
      Sheet sheet = workbook.createSheet(SHEET_NAME);
      writeHeader(workbook, sheet, table.headers());
      writeRows(sheet, table);
      // The header stays visible while a user scrolls a long export — with 20 columns of
      // Contacts data, a scrolled-away header makes the file unreadable.
      sheet.createFreezePane(0, 1);
      OutputStream out = response.getOutputStream();
      workbook.write(out);
      out.flush();
    }
  }

  private static void writeHeader(SXSSFWorkbook workbook, Sheet sheet, List<String> headers) {
    CellStyle bold = workbook.createCellStyle();
    Font font = workbook.createFont();
    font.setBold(true);
    bold.setFont(font);

    Row header = sheet.createRow(0);
    for (int i = 0; i < headers.size(); i++) {
      Cell cell = header.createCell(i);
      cell.setCellValue(headers.get(i));
      cell.setCellStyle(bold);
    }
  }

  private static void writeRows(Sheet sheet, NeoExportTable table) {
    JSONArray rows = table.rows();
    int rowNum = 1;
    for (int r = 0; r < rows.length(); r++) {
      JSONObject row = rows.optJSONObject(r);
      if (!table.isKept(row)) {
        continue;
      }
      Row sheetRow = sheet.createRow(rowNum++);
      List<String> cells = table.cells(row);
      for (int c = 0; c < cells.size(); c++) {
        // setCellValue(String) produces a string cell. Never a typed setter, and never
        // setCellFormula — see the class comment.
        sheetRow.createCell(c).setCellValue(cells.get(c) == null ? "" : cells.get(c));
      }
    }
  }
}
