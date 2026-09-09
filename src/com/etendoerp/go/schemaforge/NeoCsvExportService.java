package com.etendoerp.go.schemaforge;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;

/**
 * Generic file export for any NEO list GET. When a list request carries {@code export=csv} or
 * {@code export=xlsx}, the servlet delegates here instead of writing JSON: the rows the handler
 * already produced are serialized to a file and streamed as an attachment. This is
 * window-agnostic — it operates on the JSON envelope any list GET returns, in both of its shapes:
 * {@code {response:{data:[...]}}} for a generic CRUD list (the standard Openbravo envelope from
 * {@code DefaultJsonDataService}) and {@code {response:{data:{<key>:[...]}}}} for a custom handler
 * that nests named collections (bank statements, movements, …). See {@link #locateRows}. No
 * per-window code either way.
 *
 * <p>The class keeps its name for its CSV heritage, but it is now the entry point for both
 * formats: it locates the rows, resolves the download filename, and hands the work to the writer
 * for the requested format. Everything about WHICH cells go in the file — column spec, dotted-path
 * resolution, date reformatting, value translation, the id filter — lives in
 * {@link NeoExportTable}, shared by both writers so the two formats cannot disagree cell for cell.
 * The xlsx mechanics live in {@link NeoXlsxExportWriter}.
 *
 * <p>Supported query params (all optional except {@code export}):
 * <ul>
 *   <li>{@code export=csv|xlsx} — opt into file streaming, and pick the format.
 *   <li>{@code ids=a,b,c} — keep only rows whose {@code id} is in the set.
 *       The client sends the already-filtered ids so server-side export honors
 *       the on-screen (client-side) filters without re-implementing them.
 *   <li>{@code columns=key:Label:type|key2:Label2} — ordered column spec.
 *       {@code key} may be a dotted path into nested values (e.g.
 *       {@code txns.0.documentNo}). {@code type=date} reformats an ISO date to
 *       {@code dd-MM-yyyy}. When omitted, every key of the first row is used.
 *   <li>{@code filename=Name} — download filename (the format's extension is appended if
 *       missing).
 *   <li>{@code valueMaps={"col":{"raw":"Label"}}} — per-column value translation, applied
 *       after the value is read and formatted. Lets a caller export an AD-coded column as
 *       the word a human reads (and, for an import round trip, re-types) instead of its
 *       stored code. A value with no entry is written unchanged.
 * </ul>
 */
final class NeoCsvExportService {

  private static final Logger log = LogManager.getLogger(NeoCsvExportService.class);

  static final String EXPORT_PARAM = "export";
  private static final String EXPORT_CSV = "csv";
  private static final String EXPORT_XLSX = "xlsx";
  private static final String PARAM_FILENAME = "filename";
  private static final String DEFAULT_FILENAME = "export";
  private static final String UTF8_BOM = "\uFEFF";
  private static final String CRLF = "\r\n";
  private static final String FORMULA_TRIGGER_CHARS = "=+-@";

  private NeoCsvExportService() {
  }

  /**
   * If {@code queryParams} requests a file export, serialize the rows contained in
   * {@code neoResponse} to the requested format, stream them as an attachment, and return
   * {@code true}. Otherwise return {@code false} so the caller writes the normal JSON response.
   *
   * <p>An unrecognized {@code export} value declines rather than guessing a format: the caller
   * then writes JSON, which is the same behaviour as before the param existed.
   */
  static boolean tryExport(NeoResponse neoResponse, Map<String, String> queryParams,
      HttpServletResponse response) throws IOException {
    if (queryParams == null) {
      return false;
    }
    String format = StringUtils.lowerCase(StringUtils.trimToEmpty(queryParams.get(EXPORT_PARAM)));
    if (!EXPORT_CSV.equals(format) && !EXPORT_XLSX.equals(format)) {
      return false;
    }
    if (neoResponse == null || neoResponse.getBody() == null) {
      return false;
    }
    JSONArray rows = locateRows(neoResponse.getBody());
    if (rows == null) {
      log.warn("export={} requested but no rows array was found in the response envelope", format);
      return false;
    }

    NeoExportTable table = NeoExportTable.of(rows, queryParams);
    String filename = sanitizeFilename(queryParams.get(PARAM_FILENAME), format);

    if (EXPORT_XLSX.equals(format)) {
      NeoXlsxExportWriter.write(table, filename, response);
      return true;
    }
    writeCsvResponse(table, filename, response);
    return true;
  }

  /**
   * Navigates {@code response.data} and returns the rows, in either of the two shapes a NEO list
   * GET produces.
   *
   * <p>A generic CRUD list is served by {@code DefaultJsonDataService.fetch}, whose standard
   * Openbravo envelope puts the rows in {@code response.data} <b>as the array itself</b>. Custom
   * handlers (bank statements, movements) instead nest one or more named collections under
   * {@code response.data}, e.g. {@code {response:{data:{statements:[…]}}}}.
   *
   * <p>Only the nested form used to be recognized, so every generic list export silently fell
   * through to the JSON response — {@code tryExport} returned false and the user downloaded a
   * {@code .csv} file containing the raw JSON envelope (ETP-4997). The failure was silent because
   * a missing rows array is a legitimate "this is not an exportable response" answer for a
   * non-list GET, so it can only warn and decline.
   */
  private static JSONArray locateRows(JSONObject body) {
    JSONObject responseObj = body.optJSONObject("response");
    Object data = responseObj != null ? responseObj.opt("data") : null;
    if (data instanceof JSONArray) {
      return (JSONArray) data;
    }
    if (!(data instanceof JSONObject)) {
      return null;
    }
    JSONObject dataObj = (JSONObject) data;
    for (Iterator<String> it = dataObj.keys(); it.hasNext();) {
      Object value = dataObj.opt(it.next());
      if (value instanceof JSONArray) {
        return (JSONArray) value;
      }
    }
    return null;
  }

  /**
   * Streams the table as CSV.
   *
   * <p>Uses {@code response.getWriter()}. That is mutually exclusive with
   * {@code getOutputStream()}, which is what {@link NeoXlsxExportWriter} uses — a servlet
   * response permits one or the other and throws {@code IllegalStateException} if both are
   * touched. Only one of the two branches in {@link #tryExport} ever runs, which is what keeps
   * that safe.
   */
  private static void writeCsvResponse(NeoExportTable table, String filename,
      HttpServletResponse response) throws IOException {
    response.setStatus(HttpServletResponse.SC_OK);
    response.setContentType("text/csv; charset=UTF-8");
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    response.setHeader("Content-Disposition", "attachment; filename=\"" + filename + "\"");

    PrintWriter writer = response.getWriter();
    // Excel will not detect UTF-8 in a CSV without the BOM and mangles every accent.
    writer.write(UTF8_BOM);
    writeCsv(writer, table);
    writer.flush();
  }

  private static void writeCsv(PrintWriter writer, NeoExportTable table) {
    writer.write(joinCsv(table.headers()));
    writer.write(CRLF);

    JSONArray rows = table.rows();
    for (int r = 0; r < rows.length(); r++) {
      JSONObject row = rows.optJSONObject(r);
      if (!table.isKept(row)) {
        continue;
      }
      writer.write(joinCsv(table.cells(row)));
      writer.write(CRLF);
    }
  }

  private static String joinCsv(List<String> values) {
    StringBuilder line = new StringBuilder();
    for (int i = 0; i < values.size(); i++) {
      if (i > 0) {
        line.append(',');
      }
      line.append(csvField(values.get(i)));
    }
    return line.toString();
  }

  /**
   * RFC 4180 field: always quoted, with inner quotes doubled. Neutralizes spreadsheet formula
   * injection by prepending a single quote when starting with formula trigger characters.
   *
   * <p>CSV-only, deliberately. The xlsx writer must not do this: a workbook string cell is inert
   * (a formula is a different cell type), so the apostrophe would be a literal character in the
   * user's spreadsheet rather than a defence against anything.
   */
  private static String csvField(String value) {
    String safe = value == null ? "" : value;
    if (isFormulaInjection(safe)) {
      safe = "'" + safe;
    }
    return "\"" + safe.replace("\"", "\"\"") + "\"";
  }

  /**
   * A cell is formula-sensitive when its first non-whitespace character is a spreadsheet
   * formula trigger ({@code = + - @}). Leading whitespace/control characters (space, tab, CR,
   * LF) are skipped first so a marker cannot hide behind them.
   */
  private static boolean isFormulaInjection(String value) {
    int i = 0;
    while (i < value.length() && Character.isWhitespace(value.charAt(i))) {
      i++;
    }
    return i < value.length() && FORMULA_TRIGGER_CHARS.indexOf(value.charAt(i)) >= 0;
  }

  /** Sanitizes the requested filename and guarantees the extension matching the format. */
  private static String sanitizeFilename(String name, String format) {
    String extension = "." + format;
    String base = StringUtils.isBlank(name) ? DEFAULT_FILENAME : name.trim();
    base = base.replaceAll("[^\\w.\\-]+", "_");
    // A caller that asked for "contacts-export.csv" but requested xlsx gets
    // "contacts-export.xlsx", not a .csv name on a workbook — the extension follows the format,
    // never the request. The frontend sends one filename for both formats for exactly this
    // reason.
    if (StringUtils.endsWithIgnoreCase(base, ".csv") || StringUtils.endsWithIgnoreCase(base, ".xlsx")) {
      base = base.substring(0, base.lastIndexOf('.'));
    }
    return base + extension;
  }
}
