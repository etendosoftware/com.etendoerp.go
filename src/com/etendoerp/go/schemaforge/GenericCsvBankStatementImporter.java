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
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
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

  public GenericCsvBankStatementImporter() {
    this(DEFAULT_DELIMITER, DEFAULT_DATE_FORMAT);
  }

  public GenericCsvBankStatementImporter(char delimiter, String dateFormatPattern) {
    this.delimiter = delimiter;
    this.dateFormat = new SimpleDateFormat(dateFormatPattern);
    this.dateFormat.setLenient(false);
  }

  /**
   * Parses {@code stream} and creates one {@link FIN_BankStatementLine} per CSV row,
   * attaching them to {@code statement}. Lines are also saved via {@link OBDal}.
   *
   * @return the number of lines parsed and saved
   */
  public int loadFile(InputStream stream, FIN_BankStatement statement) throws Exception {
    List<String[]> rows = readRows(stream);
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

      FIN_BankStatementLine line = OBProvider.getInstance().get(FIN_BankStatementLine.class);
      line.setBankStatement(statement);
      line.setClient(statement.getClient());
      line.setOrganization(statement.getOrganization());
      line.setLineNo(lineNo);

      line.setTransactionDate(dateFormat.parse(get(row, headerIdx, COL_DATE)));
      line.setDramount(parseAmount(get(row, headerIdx, COL_AMOUNT_OUT), decimalSep));
      line.setCramount(parseAmount(get(row, headerIdx, COL_AMOUNT_IN), decimalSep));

      String reference = get(row, headerIdx, COL_REFERENCE);
      line.setReferenceNo(StringUtils.isBlank(reference) ? "**" : truncate(reference, 30));

      String bp = get(row, headerIdx, COL_BPARTNER);
      if (StringUtils.isNotBlank(bp)) line.setBpartnername(truncate(bp, 60));

      String desc = get(row, headerIdx, COL_DESCRIPTION);
      if (StringUtils.isNotBlank(desc)) line.setDescription(truncate(desc, 2000));

      OBDal.getInstance().save(line);
      lineNo += 10L;
      count++;
    }
    return count;
  }

  // -------------------------------------------------------------------------
  // CSV tokenisation
  // -------------------------------------------------------------------------

  private List<String[]> readRows(InputStream stream) throws Exception {
    List<String[]> rows = new ArrayList<>();
    try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
      StringBuilder cell = new StringBuilder();
      List<String> currentRow = new ArrayList<>();
      boolean inQuotes = false;
      int c;
      while ((c = reader.read()) != -1) {
        char ch = (char) c;
        if (inQuotes) {
          if (ch == '"') {
            // Peek next char for doubled-quote escape
            reader.mark(1);
            int next = reader.read();
            if (next == '"') {
              cell.append('"');
            } else {
              inQuotes = false;
              if (next != -1) reader.reset();
            }
          } else {
            cell.append(ch);
          }
        } else {
          if (ch == '"') {
            inQuotes = true;
          } else if (ch == delimiter) {
            currentRow.add(cell.toString());
            cell.setLength(0);
          } else if (ch == '\n' || ch == '\r') {
            // Treat \r\n as one newline; swallow the \n that may follow a \r
            if (ch == '\r') {
              reader.mark(1);
              int next = reader.read();
              if (next != -1 && next != '\n') reader.reset();
            }
            currentRow.add(cell.toString());
            cell.setLength(0);
            rows.add(currentRow.toArray(new String[0]));
            currentRow = new ArrayList<>();
          } else {
            cell.append(ch);
          }
        }
      }
      // Flush the last cell/row if the file didn't end with newline
      if (cell.length() > 0 || !currentRow.isEmpty()) {
        currentRow.add(cell.toString());
        rows.add(currentRow.toArray(new String[0]));
      }
    }
    return rows;
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
        String raw = get(rows.get(r), headerIdx, col);
        if (StringUtils.isBlank(raw)) continue;
        boolean hasComma = raw.indexOf(',') >= 0;
        boolean hasDot = raw.indexOf('.') >= 0;
        if (hasComma && !hasDot) return ',';
        if (hasDot && !hasComma) return '.';
        if (hasComma && hasDot) {
          // Both present → the rightmost is the decimal separator
          return raw.lastIndexOf(',') > raw.lastIndexOf('.') ? ',' : '.';
        }
      }
    }
    return ',';
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
    } catch (Exception e) {
      throw new IllegalArgumentException("Impossible to parse number: " + raw, e);
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
