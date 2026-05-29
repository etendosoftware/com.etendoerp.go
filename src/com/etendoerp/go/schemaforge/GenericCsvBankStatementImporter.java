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
 * All portions are Copyright © 2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.schemaforge;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.openbravo.base.exception.OBException;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.financialmgmt.payment.FIN_BankStatement;
import org.openbravo.model.financialmgmt.payment.FIN_BankStatementLine;

/**
 * Ports the logic of the {@code org.openbravo.bankstatement.importer.generic.csv}
 * module so that Etendo Go can import a generic CSV bank statement without
 * adding the upstream module as a compile/runtime dependency.
 *
 * <p>Expected CSV shape (first row is the header):
 * <pre>
 *   Transaction Date,Reference No.,Business Partner Name,Amount OUT,Amount IN,Description
 *   20/10/2012,1,Foo Bar,0,"150,65",
 *   01/12/2012,"25,123",My Business Partner,2222,0,A description for this line
 * </pre>
 *
 * <p>The parser is intentionally self-contained: it implements minimal CSV
 * tokenisation (quoted fields, doubled-quote escape, configurable delimiter)
 * so we do not depend on opencsv. Decimal separator is auto-detected per file
 * (comma vs dot) by inspecting the first non-empty amount. Date format
 * defaults to {@code dd/MM/yyyy} which matches the example file shipped by
 * the upstream module.
 */
public class GenericCsvBankStatementImporter {

  private static final String DEFAULT_DATE_FORMAT = "dd/MM/yyyy";
  private static final char DEFAULT_DELIMITER = ',';
  private static final String COL_DATE        = "Transaction Date";
  private static final String COL_REFERENCE   = "Reference No.";
  private static final String COL_BPARTNER    = "Business Partner Name";
  private static final String COL_AMOUNT_OUT  = "Amount OUT";
  private static final String COL_AMOUNT_IN   = "Amount IN";
  private static final String COL_DESCRIPTION = "Description";

  private final char delimiter;
  private final SimpleDateFormat dateFormat;

  /**
   * Creates an importer with the upstream module's defaults: comma as field
   * delimiter and {@code dd/MM/yyyy} as date format. Decimal separator is
   * autodetected per file at parse time.
   */
  public GenericCsvBankStatementImporter() {
    this(DEFAULT_DELIMITER, DEFAULT_DATE_FORMAT);
  }

  /**
   * Creates an importer that overrides the defaults. Useful for callers
   * (or tests) targeting banks that ship statements with a different field
   * separator or date pattern.
   *
   * @param delimiter         single-character CSV field separator (e.g. {@code ','}, {@code ';'})
   * @param dateFormatPattern {@link SimpleDateFormat} pattern used to parse the {@code Transaction Date} column
   */
  public GenericCsvBankStatementImporter(char delimiter, String dateFormatPattern) {
    this.delimiter = delimiter;
    this.dateFormat = new SimpleDateFormat(dateFormatPattern);
    this.dateFormat.setLenient(false);
  }

  /**
   * Parses {@code stream} and creates one {@link FIN_BankStatementLine} per CSV row,
   * attaching them to {@code statement}. Lines are also saved via {@link OBDal}.
   *
   * @param stream    input stream pointing at the CSV file content (UTF-8); fully consumed by this method
   * @param statement persisted statement the new lines will be linked to
   * @return the number of lines parsed and saved
   * @throws CsvParseException (unchecked) when a required column is missing, a date or number
   *                           can't be parsed, or the underlying I/O fails
   */
  public int loadFile(InputStream stream, FIN_BankStatement statement) {
    List<String[]> rows;
    try {
      rows = readRows(stream);
    } catch (IOException e) {
      throw new CsvParseException("Failed to read CSV stream", e);
    }
    if (rows.isEmpty()) return 0;

    Map<String, Integer> headerIdx = indexHeaders(rows.get(0));
    require(headerIdx, COL_DATE);
    require(headerIdx, COL_AMOUNT_OUT);
    require(headerIdx, COL_AMOUNT_IN);

    char decimalSep = detectDecimalSeparator(rows, headerIdx);
    long lineNo = nextLineSeed(statement);
    int count = 0;

    for (int r = 1; r < rows.size(); r++) {
      String[] row = rows.get(r);
      if (isBlankRow(row)) continue;
      saveLine(statement, row, headerIdx, decimalSep, lineNo);
      lineNo += 10L;
      count++;
    }
    return count;
  }

  /**
   * Maps a single CSV row to a {@link FIN_BankStatementLine} and persists it.
   * Extracted from {@link #loadFile} so the loop body stays small enough for
   * Sonar's cognitive-complexity check.
   */
  private void saveLine(FIN_BankStatement statement, String[] row,
                        Map<String, Integer> headerIdx, char decimalSep, long lineNo) {
    FIN_BankStatementLine line = OBProvider.getInstance().get(FIN_BankStatementLine.class);
    line.setBankStatement(statement);
    line.setClient(statement.getClient());
    line.setOrganization(statement.getOrganization());
    line.setLineNo(lineNo);

    String rawDate = get(row, headerIdx, COL_DATE);
    try {
      line.setTransactionDate(dateFormat.parse(rawDate));
    } catch (ParseException e) {
      throw new CsvParseException("Invalid date in CSV row: " + rawDate, e);
    }
    line.setDramount(parseAmount(get(row, headerIdx, COL_AMOUNT_OUT), decimalSep));
    line.setCramount(parseAmount(get(row, headerIdx, COL_AMOUNT_IN), decimalSep));

    String reference = get(row, headerIdx, COL_REFERENCE);
    line.setReferenceNo(StringUtils.isBlank(reference) ? "**" : truncate(reference, 30));

    String bp = get(row, headerIdx, COL_BPARTNER);
    if (StringUtils.isNotBlank(bp)) line.setBpartnername(truncate(bp, 60));

    String desc = get(row, headerIdx, COL_DESCRIPTION);
    if (StringUtils.isNotBlank(desc)) line.setDescription(truncate(desc, 2000));

    OBDal.getInstance().save(line);
  }

  /**
   * Runtime exception thrown for any failure during CSV parsing — I/O, missing
   * columns, malformed dates or amounts. Extends {@link OBException} per
   * Etendo's standard so callers don't have to declare it and so the framework
   * picks it up consistently (S112).
   */
  public static class CsvParseException extends OBException {
    private static final long serialVersionUID = 1L;

    /**
     * Builds a CSV parse failure carrying only a human-readable explanation.
     * Use this overload when the caller didn't catch an underlying exception
     * (e.g. a validation error like "missing required column").
     *
     * @param message description of the parse failure
     */
    public CsvParseException(String message) {
      super(message);
    }

    /**
     * Builds a CSV parse failure that wraps a lower-level error (typically a
     * {@link java.text.ParseException} or an {@link java.io.IOException}) so
     * its stack trace is preserved in the logs.
     *
     * @param message description of the parse failure
     * @param cause   the underlying exception that triggered this failure
     */
    public CsvParseException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  // -------------------------------------------------------------------------
  // CSV tokenisation
  // -------------------------------------------------------------------------

  /**
   * Mutable state shared between {@link #readRows} and its two sub-readers.
   * Wrapping the iteration state in a tiny holder keeps {@link #readRows}
   * focused on the loop while the actual branching lives in
   * {@link #consumeInQuotes} and {@link #consumeOutOfQuotes}.
   */
  private static final class CsvState {
    final List<String[]> rows = new ArrayList<>();
    final StringBuilder cell = new StringBuilder();
    List<String> currentRow = new ArrayList<>();
    boolean inQuotes;

    void endCell() {
      currentRow.add(cell.toString());
      cell.setLength(0);
    }

    void endRow() {
      endCell();
      rows.add(currentRow.toArray(new String[0]));
      currentRow = new ArrayList<>();
    }
  }

  private List<String[]> readRows(InputStream stream) throws IOException {
    CsvState st = new CsvState();
    try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
      int c;
      while ((c = reader.read()) != -1) {
        char ch = (char) c;
        if (st.inQuotes) {
          consumeInQuotes(reader, ch, st);
        } else {
          consumeOutOfQuotes(reader, ch, st);
        }
      }
      // Flush the last cell/row if the file didn't end with newline
      if (st.cell.length() > 0 || !st.currentRow.isEmpty()) {
        st.endRow();
      }
    }
    return st.rows;
  }

  /**
   * Handles a character read while inside a quoted field. A lone {@code "}
   * exits the quoted state; a doubled {@code ""} appends a literal quote and
   * stays inside. Everything else accumulates into the current cell.
   */
  private static void consumeInQuotes(BufferedReader reader, char ch, CsvState st) throws IOException {
    if (ch != '"') {
      st.cell.append(ch);
      return;
    }
    // Peek next char for doubled-quote escape
    reader.mark(1);
    int next = reader.read();
    if (next == '"') {
      st.cell.append('"');
    } else {
      st.inQuotes = false;
      if (next != -1) reader.reset();
    }
  }

  /**
   * Handles a character read while OUTSIDE a quoted field. Opening quote,
   * delimiter and CR/LF each trigger their own transition; everything else
   * just appends to the running cell.
   */
  private void consumeOutOfQuotes(BufferedReader reader, char ch, CsvState st) throws IOException {
    if (ch == '"') {
      st.inQuotes = true;
    } else if (ch == delimiter) {
      st.endCell();
    } else if (ch == '\n' || ch == '\r') {
      // Treat \r\n as one newline; swallow the \n that may follow a \r
      if (ch == '\r') {
        reader.mark(1);
        int next = reader.read();
        if (next != -1 && next != '\n') reader.reset();
      }
      st.endRow();
    } else {
      st.cell.append(ch);
    }
  }

  // -------------------------------------------------------------------------
  // Helpers
  // -------------------------------------------------------------------------

  private static Map<String, Integer> indexHeaders(String[] header) {
    Map<String, Integer> idx = new HashMap<>();
    for (int i = 0; i < header.length; i++) {
      idx.put(header[i].trim(), i);
    }
    return idx;
  }

  private static void require(Map<String, Integer> idx, String col) {
    if (!idx.containsKey(col)) {
      throw new IllegalArgumentException("Missing required CSV column: " + col);
    }
  }

  private static String get(String[] row, Map<String, Integer> idx, String col) {
    Integer i = idx.get(col);
    if (i == null || i >= row.length) return "";
    return row[i] == null ? "" : row[i].trim();
  }

  private static boolean isBlankRow(String[] row) {
    if (row == null || row.length == 0) return true;
    for (String c : row) if (StringUtils.isNotBlank(c)) return false;
    return true;
  }

  private static String truncate(String s, int max) {
    return s.length() > max ? s.substring(0, max) : s;
  }

  /**
   * Reads amount columns of the first non-blank data row to detect whether the
   * file uses {@code ,} or {@code .} as the decimal separator. Falls back to
   * comma when undecidable so that Spanish files (the default for the upstream
   * module) parse correctly.
   */
  private static char detectDecimalSeparator(List<String[]> rows, Map<String, Integer> headerIdx) {
    for (int r = 1; r < rows.size(); r++) {
      for (String col : Arrays.asList(COL_AMOUNT_OUT, COL_AMOUNT_IN)) {
        Character separator = detectSeparatorIn(get(rows.get(r), headerIdx, col));
        if (separator != null) return separator;
      }
    }
    return ',';
  }

  /**
   * Returns {@code ','}, {@code '.'} or {@code null} (undecidable) for a
   * single raw amount cell. Extracted so {@link #detectDecimalSeparator}
   * stays under Sonar's cognitive-complexity threshold.
   */
  private static Character detectSeparatorIn(String raw) {
    if (StringUtils.isBlank(raw)) return null;
    boolean hasComma = raw.indexOf(',') >= 0;
    boolean hasDot = raw.indexOf('.') >= 0;
    if (hasComma && !hasDot) return ',';
    if (hasDot && !hasComma) return '.';
    if (hasComma && hasDot) {
      // Both present → the rightmost is the decimal separator
      return raw.lastIndexOf(',') > raw.lastIndexOf('.') ? ',' : '.';
    }
    return null;
  }

  private static BigDecimal parseAmount(String raw, char decimalSep) {
    if (StringUtils.isBlank(raw)) return BigDecimal.ZERO;
    DecimalFormatSymbols symbols = new DecimalFormatSymbols(Locale.US);
    symbols.setDecimalSeparator(decimalSep);
    symbols.setGroupingSeparator(decimalSep == ',' ? '.' : ',');
    DecimalFormat df = new DecimalFormat();
    df.setDecimalFormatSymbols(symbols);
    df.setParseBigDecimal(true);
    try {
      return (BigDecimal) df.parse(raw);
    } catch (ParseException e) {
      throw new CsvParseException("Impossible to parse number: " + raw, e);
    }
  }

  private static long nextLineSeed(FIN_BankStatement statement) {
    if (statement.getFINBankStatementLineList() == null
        || statement.getFINBankStatementLineList().isEmpty()) {
      return 10L;
    }
    long max = 0L;
    for (FIN_BankStatementLine l : statement.getFINBankStatementLineList()) {
      if (l.getLineNo() != null && l.getLineNo() > max) max = l.getLineNo();
    }
    return max + 10L;
  }
}
