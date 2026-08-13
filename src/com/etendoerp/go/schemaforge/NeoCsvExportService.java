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

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;

/**
 * Generic CSV export for any NEO list GET. When a list request carries
 * {@code export=csv}, the servlet delegates here instead of writing JSON: the
 * rows the handler already produced are serialized to CSV and streamed as a
 * file attachment. This is window-agnostic — it operates on the standard JSON
 * envelope ({@code {response:{data:{<key>:[...]}}}}) that any list handler
 * returns, so it works uniformly for generic CRUD lists and custom handlers
 * (bank statements, movements, …) without per-window code.
 *
 * <p>Supported query params (all optional except {@code export}):
 * <ul>
 *   <li>{@code export=csv} — opt into CSV streaming.
 *   <li>{@code ids=a,b,c} — keep only rows whose {@code id} is in the set.
 *       The client sends the already-filtered ids so server-side export honors
 *       the on-screen (client-side) filters without re-implementing them.
 *   <li>{@code columns=key:Label:type|key2:Label2} — ordered column spec.
 *       {@code key} may be a dotted path into nested values (e.g.
 *       {@code txns.0.documentNo}). {@code type=date} reformats an ISO date to
 *       {@code dd-MM-yyyy}. When omitted, every key of the first row is used.
 *   <li>{@code filename=Name} — download filename (".csv" appended if missing).
 * </ul>
 */
final class NeoCsvExportService {

  private static final Logger log = LogManager.getLogger(NeoCsvExportService.class);

  static final String EXPORT_PARAM = "export";
  private static final String EXPORT_CSV = "csv";
  private static final String PARAM_IDS = "ids";
  private static final String PARAM_COLUMNS = "columns";
  private static final String PARAM_FILENAME = "filename";
  private static final String TYPE_DATE = "date";
  private static final String FIELD_ID = "id";
  private static final String DEFAULT_FILENAME = "export";
  // UTF-8 BOM so spreadsheet apps (Excel) detect the encoding and render accents.
  private static final String UTF8_BOM = "\uFEFF";
  private static final String CRLF = "\r\n";
  // Spreadsheet formula triggers per CWE-1236; a leading match is neutralized with an apostrophe.
  private static final String FORMULA_TRIGGER_CHARS = "=+-@";

  private NeoCsvExportService() {
  }

  /**
   * If {@code queryParams} requests a CSV export, serialize the rows contained
   * in {@code neoResponse} to CSV, stream them as an attachment, and return
   * {@code true}. Otherwise return {@code false} so the caller writes the normal
   * JSON response.
   */
  static boolean tryExport(NeoResponse neoResponse, Map<String, String> queryParams,
      HttpServletResponse response) throws IOException {
    if (queryParams == null || !EXPORT_CSV.equalsIgnoreCase(queryParams.get(EXPORT_PARAM))) {
      return false;
    }
    if (neoResponse == null || neoResponse.getBody() == null) {
      return false;
    }
    JSONArray rows = locateRows(neoResponse.getBody());
    if (rows == null) {
      log.warn("export=csv requested but no rows array was found in the response envelope");
      return false;
    }

    List<Column> columns = parseColumns(queryParams.get(PARAM_COLUMNS));
    Set<String> idFilter = parseIds(queryParams.get(PARAM_IDS));
    String filename = sanitizeFilename(queryParams.get(PARAM_FILENAME));

    response.setStatus(HttpServletResponse.SC_OK);
    response.setContentType("text/csv; charset=UTF-8");
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    response.setHeader("Content-Disposition", "attachment; filename=\"" + filename + "\"");

    PrintWriter writer = response.getWriter();
    writer.write(UTF8_BOM);
    writeCsv(writer, rows, columns, idFilter);
    writer.flush();
    return true;
  }

  /** Navigates {@code response.data} and returns the first JSONArray it finds. */
  private static JSONArray locateRows(JSONObject body) {
    JSONObject responseObj = body.optJSONObject("response");
    JSONObject data = responseObj != null ? responseObj.optJSONObject("data") : null;
    if (data == null) {
      return null;
    }
    for (Iterator<String> it = data.keys(); it.hasNext();) {
      Object value = data.opt(it.next());
      if (value instanceof JSONArray) {
        return (JSONArray) value;
      }
    }
    return null;
  }

  private static void writeCsv(PrintWriter writer, JSONArray rows, List<Column> columns,
      Set<String> idFilter) {
    List<Column> cols = columns.isEmpty() ? deriveColumns(rows, idFilter) : columns;

    StringBuilder header = new StringBuilder();
    for (int i = 0; i < cols.size(); i++) {
      if (i > 0) {
        header.append(',');
      }
      header.append(csvField(cols.get(i).label));
    }
    writer.write(header.toString());
    writer.write(CRLF);

    for (int r = 0; r < rows.length(); r++) {
      JSONObject row = rows.optJSONObject(r);
      if (row == null || isFilteredOut(row, idFilter)) {
        continue;
      }
      StringBuilder line = new StringBuilder();
      for (int i = 0; i < cols.size(); i++) {
        if (i > 0) {
          line.append(',');
        }
        Column col = cols.get(i);
        line.append(csvField(formatValue(resolveValue(row, col.key), col.type)));
      }
      writer.write(line.toString());
      writer.write(CRLF);
    }
  }

  private static boolean isFilteredOut(JSONObject row, Set<String> idFilter) {
    if (idFilter.isEmpty()) {
      return false;
    }
    String id = row.optString(FIELD_ID, null);
    return id == null || !idFilter.contains(id);
  }

  /** When no column spec is given, fall back to all keys of the first kept row. */
  private static List<Column> deriveColumns(JSONArray rows, Set<String> idFilter) {
    List<Column> cols = new ArrayList<>();
    JSONObject sample = firstKeptRow(rows, idFilter);
    if (sample != null) {
      for (Iterator<String> it = sample.keys(); it.hasNext();) {
        String key = it.next();
        cols.add(new Column(key, key, ""));
      }
    }
    return cols;
  }

  /** First row that passes the id filter, or {@code null} when there is none. */
  private static JSONObject firstKeptRow(JSONArray rows, Set<String> idFilter) {
    for (int r = 0; r < rows.length(); r++) {
      JSONObject row = rows.optJSONObject(r);
      if (row != null && !isFilteredOut(row, idFilter)) {
        return row;
      }
    }
    return null;
  }

  /** Resolves a flat key or a dotted path ({@code txns.0.documentNo}) on a row. */
  private static Object resolveValue(JSONObject row, String path) {
    if (path.indexOf('.') < 0) {
      return row.opt(path);
    }
    Object current = row;
    for (String segment : path.split("\\.")) {
      if (current instanceof JSONObject) {
        current = ((JSONObject) current).opt(segment);
      } else if (current instanceof JSONArray) {
        try {
          current = ((JSONArray) current).opt(Integer.parseInt(segment));
        } catch (NumberFormatException e) {
          return null;
        }
      } else {
        return null;
      }
    }
    return current;
  }

  private static String formatValue(Object value, String type) {
    if (value == null || JSONObject.NULL.equals(value)) {
      return "";
    }
    String str = String.valueOf(value);
    if (TYPE_DATE.equals(type)) {
      return formatDateDayMonthYear(str);
    }
    return str;
  }

  /** Reformats an ISO {@code yyyy-MM-dd[...]} value to {@code dd-MM-yyyy}. */
  private static String formatDateDayMonthYear(String iso) {
    if (StringUtils.isBlank(iso) || iso.length() < 10 || iso.charAt(4) != '-'
        || iso.charAt(7) != '-') {
      return iso;
    }
    return iso.substring(8, 10) + "-" + iso.substring(5, 7) + "-" + iso.substring(0, 4);
  }

  /**
   * RFC 4180 field: always quoted, with inner quotes doubled. Neutralizes spreadsheet formula
   * injection by prepending a single quote when starting with formula trigger characters.
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

  private static List<Column> parseColumns(String spec) {
    List<Column> cols = new ArrayList<>();
    if (StringUtils.isBlank(spec)) {
      return cols;
    }
    for (String part : spec.split("\\|")) {
      if (StringUtils.isBlank(part)) {
        continue;
      }
      String[] fields = part.split(":", 3);
      String key = fields[0].trim();
      String label = fields.length > 1 && StringUtils.isNotBlank(fields[1]) ? fields[1].trim() : key;
      String type = fields.length > 2 ? fields[2].trim() : "";
      cols.add(new Column(key, label, type));
    }
    return cols;
  }

  /** Parses the comma-separated id filter. Empty set means "no filter". */
  private static Set<String> parseIds(String spec) {
    Set<String> ids = new HashSet<>();
    if (StringUtils.isBlank(spec)) {
      return ids;
    }
    for (String id : spec.split(",")) {
      if (StringUtils.isNotBlank(id)) {
        ids.add(id.trim());
      }
    }
    return ids;
  }

  private static String sanitizeFilename(String name) {
    String base = StringUtils.isBlank(name) ? DEFAULT_FILENAME : name.trim();
    base = base.replaceAll("[^\\w.\\-]+", "_");
    if (!StringUtils.endsWithIgnoreCase(base, ".csv")) {
      base = base + ".csv";
    }
    return base;
  }

  /** One CSV column: source {@code key} (dotted path allowed), header {@code label}, optional {@code type}. */
  private static final class Column {
    private final String key;
    private final String label;
    private final String type;

    Column(String key, String label, String type) {
      this.key = key;
      this.label = label;
      this.type = type;
    }
  }
}
