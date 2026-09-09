package com.etendoerp.go.schemaforge;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;

/**
 * The tabular view of a NEO list response that an export serializes — column spec, id filter,
 * value translation and per-cell resolution — with no knowledge of any file format.
 *
 * <p>Extracted (ETP-4997) when xlsx joined CSV, and the reason is the whole point of the class:
 * the two formats MUST agree cell for cell, because the file a user exports is the file they
 * edit and re-import, and the import has one parser contract for both. Two writers each doing
 * their own dotted-path resolution, date reformatting and value mapping would be two chances to
 * disagree, and the disagreement would surface as a re-import that silently maps a column
 * differently depending on which format the user happened to pick. Sharing the projection makes
 * the equivalence structural rather than something a test has to keep catching.
 *
 * <p>What deliberately does NOT live here is escaping. CSV quotes its fields and neutralizes
 * spreadsheet formula injection by prefixing an apostrophe; xlsx does neither, because a text
 * cell in a workbook is inert — it is not a formula, so the prefix would be visible garbage in
 * the cell. Escaping is a property of the format, so it stays with each writer; the values this
 * class hands out are the raw resolved strings.
 *
 * <p>Rows are exposed for iteration rather than materialized into a list, so a 5000-row export
 * still never holds the whole table in memory.
 */
final class NeoExportTable {

  private static final Logger log = LogManager.getLogger(NeoExportTable.class);

  private static final String PARAM_IDS = "ids";
  private static final String PARAM_COLUMNS = "columns";
  private static final String PARAM_VALUE_MAPS = "valueMaps";
  private static final String TYPE_DATE = "date";
  private static final String FIELD_ID = "id";

  private final JSONArray rows;
  private final List<Column> columns;
  private final Set<String> idFilter;
  private final Map<String, Map<String, String>> valueMaps;

  private NeoExportTable(JSONArray rows, List<Column> columns, Set<String> idFilter,
      Map<String, Map<String, String>> valueMaps) {
    this.rows = rows;
    this.columns = columns;
    this.idFilter = idFilter;
    this.valueMaps = valueMaps;
  }

  /**
   * Builds the table from a list response's rows and the export query params.
   *
   * @param rows the row array located in the response envelope.
   * @param queryParams the request's query params ({@code columns}, {@code ids},
   *     {@code valueMaps}).
   */
  static NeoExportTable of(JSONArray rows, Map<String, String> queryParams) {
    Set<String> ids = parseIds(queryParams.get(PARAM_IDS));
    List<Column> cols = parseColumns(queryParams.get(PARAM_COLUMNS));
    if (cols.isEmpty()) {
      cols = deriveColumns(rows, ids);
    }
    return new NeoExportTable(rows, cols, ids, parseValueMaps(queryParams.get(PARAM_VALUE_MAPS)));
  }

  /** Column headers, in order. */
  List<String> headers() {
    List<String> labels = new ArrayList<>(columns.size());
    for (Column column : columns) {
      labels.add(column.label);
    }
    return labels;
  }

  /** Number of columns, so a writer can size a row up front. */
  int width() {
    return columns.size();
  }

  /** The raw row array, for the writer to iterate. */
  JSONArray rows() {
    return rows;
  }

  /** Whether this row survives the {@code ids} filter. An empty filter keeps everything. */
  boolean isKept(JSONObject row) {
    if (row == null) {
      return false;
    }
    if (idFilter.isEmpty()) {
      return true;
    }
    String id = row.optString(FIELD_ID, null);
    return id != null && idFilter.contains(id);
  }

  /** One row's cells, resolved, date-formatted and value-translated but NOT escaped. */
  List<String> cells(JSONObject row) {
    List<String> values = new ArrayList<>(columns.size());
    for (Column column : columns) {
      String value = formatValue(resolveValue(row, column.key), column.type);
      values.add(translateValue(value, valueMaps.get(column.key)));
    }
    return values;
  }

  /** Resolves a flat key or a dotted path ({@code etgoChildData.city}) on a row. */
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

  /**
   * Reformats an ISO {@code yyyy-MM-dd[...]} value to {@code dd-MM-yyyy}.
   *
   * <p>Pure string surgery on the date-only prefix, deliberately: parsing into a {@code Date} and
   * formatting it back would re-introduce a timezone into a value that has none, which is the
   * ETP-4031 / ETP-4850 off-by-one-day bug class. The shape it produces is the shape
   * {@code parseXlsx} writes for a real Excel date cell, which is what keeps
   * export → edit → import closed across both formats.
   */
  private static String formatDateDayMonthYear(String iso) {
    if (StringUtils.isBlank(iso) || iso.length() < 10 || iso.charAt(4) != '-'
        || iso.charAt(7) != '-') {
      return iso;
    }
    return iso.substring(8, 10) + "-" + iso.substring(5, 7) + "-" + iso.substring(0, 4);
  }

  /**
   * Replaces a cell with the label the caller mapped it to, or leaves it as it is.
   *
   * <p>A blank cell is never translated: an empty value means "this row says nothing about the
   * field", and the import reads it back the same way (see {@code resolveCodedValue}'s blank
   * status). Mapping it would turn silence into an assertion.
   */
  private static String translateValue(String value, Map<String, String> labels) {
    if (labels == null || StringUtils.isEmpty(value)) {
      return value;
    }
    String label = labels.get(value);
    return label != null ? label : value;
  }

  /**
   * Parses the {@code valueMaps} param: a JSON object of {@code {columnKey: {raw: label}}}.
   *
   * <p>Malformed JSON degrades to "no translation" rather than failing the export — the file
   * is still correct, just with raw codes in the mapped columns, which is what the export
   * produced before this param existed.
   */
  private static Map<String, Map<String, String>> parseValueMaps(String spec) {
    Map<String, Map<String, String>> maps = new HashMap<>();
    if (StringUtils.isBlank(spec)) {
      return maps;
    }
    try {
      JSONObject root = new JSONObject(spec);
      for (Iterator<String> cols = root.keys(); cols.hasNext();) {
        String column = cols.next();
        JSONObject entries = root.optJSONObject(column);
        if (entries == null) {
          continue;
        }
        Map<String, String> labels = new HashMap<>();
        for (Iterator<String> raw = entries.keys(); raw.hasNext();) {
          String key = raw.next();
          labels.put(key, entries.optString(key, key));
        }
        maps.put(column, labels);
      }
    } catch (JSONException e) {
      log.warn("Ignoring malformed valueMaps parameter: {}", e.getMessage());
    }
    return maps;
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
      if (row == null) {
        continue;
      }
      if (idFilter.isEmpty()) {
        return row;
      }
      String id = row.optString(FIELD_ID, null);
      if (id != null && idFilter.contains(id)) {
        return row;
      }
    }
    return null;
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

  /** One column: source {@code key} (dotted path allowed), header {@code label}, optional {@code type}. */
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
