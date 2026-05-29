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
 * All portions are Copyright © 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.schemaforge;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Date;
import java.util.List;

import javax.inject.Named;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.advpaymentmngt.utility.FIN_BankStatementImport;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.enterprise.DocumentType;
import org.openbravo.model.financialmgmt.payment.FIN_BankStatement;
import org.openbravo.model.financialmgmt.payment.FIN_FinancialAccount;

/**
 * NeoHandler powering the bank-statements endpoint introduced by ETP-4121.
 *
 * <p>Serves three operations routed by HTTP method + {@code action} query param:
 * <ul>
 *   <li>List statements: {@code GET /sws/neo/bank-statements?FIN_Financial_Account_ID=<id>}
 *   <li>List lines:      {@code GET /sws/neo/bank-statements?action=lines&statementId=<id>}
 *   <li>Import C43:      {@code POST /sws/neo/bank-statements?action=import}
 *       body: {@code { FIN_Financial_Account_ID, fileName, contentBase64 }}
 * </ul>
 */
@Named("bank-statements")
public class BankStatementsHandler implements NeoHandler {

  private static final Logger log = LogManager.getLogger(BankStatementsHandler.class);

  private static final String METHOD_GET = "GET";
  private static final String METHOD_POST = "POST";
  private static final String ACTION_LINES = "lines";
  private static final String ACTION_IMPORT = "import";
  private static final String ACTION_PREVIEW = "preview";
  private static final String PARAM_ACCOUNT_ID = "FIN_Financial_Account_ID";
  private static final String PARAM_STATEMENT_ID = "statementId";
  private static final String PARAM_ACTION = "action";

  private static final String C43_CLASS_NAME =
      "org.openbravo.module.cuaderno43.es.utility.Cuaderno43";

  private static final DateTimeFormatter ISO_UTC =
      DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);

  private static final String STATEMENTS_SQL =
      "SELECT bs.fin_bankstatement_id,"
          + "       bs.documentno,"
          + "       bs.name,"
          + "       bs.filename,"
          + "       bs.importdate,"
          + "       bs.statementdate,"
          + "       bs.processed,"
          + "       bs.posted,"
          + "       agg.line_count,"
          + "       agg.matched_count,"
          + "       agg.total_amount,"
          + "       agg.period_from,"
          + "       agg.period_to"
          + "  FROM fin_bankstatement bs"
          + "  LEFT JOIN ("
          + "    SELECT bsl.fin_bankstatement_id,"
          + "           COUNT(*) AS line_count,"
          + "           SUM(CASE WHEN bsl.fin_finacc_transaction_id IS NOT NULL THEN 1 ELSE 0 END) AS matched_count,"
          + "           SUM(COALESCE(bsl.cramount,0) + COALESCE(bsl.dramount,0)) AS total_amount,"
          + "           MIN(bsl.datetrx) AS period_from,"
          + "           MAX(bsl.datetrx) AS period_to"
          + "      FROM fin_bankstatementline bsl"
          + "     WHERE bsl.isactive = 'Y'"
          + "     GROUP BY bsl.fin_bankstatement_id"
          + "  ) agg ON agg.fin_bankstatement_id = bs.fin_bankstatement_id"
          + " WHERE bs.fin_financial_account_id = ?"
          + "   AND bs.isactive = 'Y'"
          + " ORDER BY bs.importdate DESC";

  private static final String LINES_SQL =
      "SELECT bsl.fin_bankstatementline_id,"
          + "       bsl.line,"
          + "       bsl.datetrx,"
          + "       bsl.description,"
          + "       bsl.referenceno,"
          + "       bsl.bpartnername,"
          + "       bsl.cramount,"
          + "       bsl.dramount,"
          + "       bsl.fin_finacc_transaction_id"
          + "  FROM fin_bankstatementline bsl"
          + " WHERE bsl.fin_bankstatement_id = ?"
          + "   AND bsl.isactive = 'Y'"
          + " ORDER BY bsl.line ASC";

  @Override
  public NeoResponse handle(NeoContext context) {
    String method = context.getHttpMethod();
    String action = context.getQueryParams() != null
        ? context.getQueryParams().get(PARAM_ACTION)
        : null;

    if (METHOD_GET.equals(method)) {
      if (ACTION_LINES.equals(action)) {
        return handleGetLines(context);
      }
      return handleList(context);
    }

    if (METHOD_POST.equals(method) && ACTION_IMPORT.equals(action)) {
      return handleImport(context);
    }
    if (METHOD_POST.equals(method) && ACTION_PREVIEW.equals(action)) {
      return handlePreview(context);
    }

    return NeoResponse.error(405, "Method not allowed.");
  }

  private NeoResponse handleList(NeoContext context) {
    String accountId = context.getQueryParams() != null
        ? context.getQueryParams().get(PARAM_ACCOUNT_ID)
        : null;
    if (StringUtils.isBlank(accountId)) {
      return NeoResponse.error(400, "Missing required parameter: " + PARAM_ACCOUNT_ID);
    }
    try {
      OBContext.setAdminMode(true);
      JSONArray statements = loadStatements(accountId);
      JSONObject data = new JSONObject();
      data.put("statements", statements);
      JSONObject responseData = new JSONObject();
      responseData.put("data", data);
      JSONObject envelope = new JSONObject();
      envelope.put("response", responseData);
      return NeoResponse.ok(envelope);
    } catch (Exception e) {
      log.error("Error listing bank statements for account {}", accountId, e);
      return NeoResponse.error(500, "Internal Server Error");
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  private NeoResponse handleGetLines(NeoContext context) {
    String statementId = context.getQueryParams() != null
        ? context.getQueryParams().get(PARAM_STATEMENT_ID)
        : null;
    if (StringUtils.isBlank(statementId)) {
      return NeoResponse.error(400, "Missing required parameter: statementId");
    }
    try {
      OBContext.setAdminMode(true);
      JSONArray lines = loadLines(statementId);
      JSONObject data = new JSONObject();
      data.put("lines", lines);
      JSONObject responseData = new JSONObject();
      responseData.put("data", data);
      JSONObject envelope = new JSONObject();
      envelope.put("response", responseData);
      return NeoResponse.ok(envelope);
    } catch (Exception e) {
      log.error("Error loading lines for statement {}", statementId, e);
      return NeoResponse.error(500, "Internal Server Error");
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  private NeoResponse handleImport(NeoContext context) {
    JSONObject body = context.getRequestBody();
    if (body == null) {
      return NeoResponse.error(400, "Request body is required");
    }
    try {
      OBContext.setAdminMode(true);
      String accountId = body.optString(PARAM_ACCOUNT_ID, null);
      String fileName = body.optString("fileName", null);
      String contentBase64 = body.optString("contentBase64", null);

      if (StringUtils.isBlank(accountId)) {
        return NeoResponse.error(400, "Missing required field: " + PARAM_ACCOUNT_ID);
      }
      if (StringUtils.isBlank(fileName)) {
        return NeoResponse.error(400, "Missing required field: fileName");
      }
      if (StringUtils.isBlank(contentBase64)) {
        return NeoResponse.error(400, "Missing required field: contentBase64");
      }

      FIN_FinancialAccount account = OBDal.getInstance().get(FIN_FinancialAccount.class, accountId);
      if (account == null) {
        return NeoResponse.error(400, "Financial account not found: " + accountId);
      }

      byte[] fileBytes = Base64.getDecoder().decode(contentBase64);
      if (fileBytes.length == 0) {
        return NeoResponse.error(400, "File content is empty");
      }

      FIN_BankStatement statement = newBankStatement(account, fileName);
      OBDal.getInstance().save(statement);

      StatementFormat format = detectFormat(fileBytes);
      if (format == StatementFormat.UNKNOWN) {
        return NeoResponse.error(400,
            "Could not detect bank statement format. Expected either a Cuaderno 43 file"
                + " (80-char records starting with '11') or a generic CSV with"
                + " 'Transaction Date', 'Amount IN', 'Amount OUT' columns.");
      }
      ByteArrayInputStream stream = new ByteArrayInputStream(fileBytes);
      int lineCount = (format == StatementFormat.GENERIC_CSV)
          ? parseGenericCsv(stream, statement)
          : parseC43(stream, statement);

      processStatement(statement);

      OBDal.getInstance().flush();

      JSONObject result = new JSONObject();
      result.put("id", statement.getId());
      result.put("fileName", fileName);
      result.put("lineCount", lineCount);
      return NeoResponse.createdWithData(result);

    } catch (IllegalArgumentException e) {
      log.warn("Invalid base64 content in import request", e);
      return NeoResponse.error(400, "Invalid base64 content: " + e.getMessage());
    } catch (Exception e) {
      log.error("Error importing bank statement", e);
      OBDal.getInstance().rollbackAndClose();
      return NeoResponse.error(500, "Import failed: " + e.getMessage());
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /**
   * Parses the uploaded file in-memory and returns what would be imported,
   * WITHOUT persisting anything. Same body shape as `?action=import`:
   *   { FIN_Financial_Account_ID, fileName, contentBase64 }
   *
   * Used by the multi-step "Importar extracto" modal to show the
   * "Revisar líneas" preview before the user confirms.
   *
   * <p>Response data:
   * <pre>
   * {
   *   "format": "C43" | "GENERIC_CSV",
   *   "fileName": "...",
   *   "lineCount": 7,
   *   "totalIn":  64806.00,
   *   "totalOut": 13454.00,
   *   "periodFrom": "2026-01-15T00:00:00Z",
   *   "periodTo":   "2026-01-26T00:00:00Z",
   *   "lines": [
   *     { "date": "...", "description": "...", "bpartnerName": "...",
   *       "dramount": 0,    "cramount": 35000.00 }
   *   ]
   * }
   * </pre>
   *
   * <p>We always {@code rollbackAndClose()} the OBDal session at the end so
   * no statement/line rows leak into the DB even if the parser inserts them
   * along the way (Cuaderno43 / OpenCSV both call {@code save()} internally).
   */
  private NeoResponse handlePreview(NeoContext context) {
    JSONObject body = context.getRequestBody();
    if (body == null) return NeoResponse.error(400, "Request body is required");
    try {
      OBContext.setAdminMode(true);
      String accountId = body.optString(PARAM_ACCOUNT_ID, null);
      String fileName = body.optString("fileName", null);
      String contentBase64 = body.optString("contentBase64", null);

      if (StringUtils.isBlank(accountId)) {
        return NeoResponse.error(400, "Missing required field: " + PARAM_ACCOUNT_ID);
      }
      if (StringUtils.isBlank(fileName)) {
        return NeoResponse.error(400, "Missing required field: fileName");
      }
      if (StringUtils.isBlank(contentBase64)) {
        return NeoResponse.error(400, "Missing required field: contentBase64");
      }

      FIN_FinancialAccount account = OBDal.getInstance().get(FIN_FinancialAccount.class, accountId);
      if (account == null) {
        return NeoResponse.error(400, "Financial account not found: " + accountId);
      }

      byte[] fileBytes = Base64.getDecoder().decode(contentBase64);
      if (fileBytes.length == 0) {
        return NeoResponse.error(400, "File content is empty");
      }

      StatementFormat format = detectFormat(fileBytes);
      if (format == StatementFormat.UNKNOWN) {
        return NeoResponse.error(400, "Unsupported file format");
      }

      // Both parsers (Cuaderno43 by reflection, our GenericCsv) call
      // OBDal.save(line) per parsed line. For that to keep the lines
      // attached to the statement's collection (getFINBankStatementLineList)
      // we need the statement persisted first — otherwise Hibernate cascades
      // can drop them silently. We persist + flush, then rollback at the end
      // so the import is genuinely read-only.
      FIN_BankStatement transientStmt = newBankStatement(account, fileName);
      OBDal.getInstance().save(transientStmt);
      OBDal.getInstance().flush();

      ByteArrayInputStream stream = new ByteArrayInputStream(fileBytes);
      // We ignore the parser's reported line count: Cuaderno43's reflection
      // path returns 0 because statement.getFINBankStatementLineList() is
      // not refreshed after save(line). We re-read from the DB right below.
      if (format == StatementFormat.GENERIC_CSV) {
        parseGenericCsv(stream, transientStmt);
      } else {
        parseC43(stream, transientStmt);
      }
      OBDal.getInstance().flush();

      // Read the parsed lines straight from the DB instead of going through
      // statement.getFINBankStatementLineList(). The entity collection isn't
      // refreshed after save(line), so it can come back empty even when the
      // rows are physically there. We're still in the same transaction; the
      // rollback below discards everything anyway.
      JSONArray lines = readLinesForPreview(transientStmt.getId());

      // Use the SQL row count as the canonical lineCount — that's the only
      // value guaranteed to match what the user will actually see in step 2.
      JSONObject result = buildPreviewPayload(format, fileName, lines.length(), lines);

      // Drop everything we parsed — preview is read-only. rollbackAndClose
      // discards both the statement and the cascaded lines from the DB.
      OBDal.getInstance().rollbackAndClose();

      return NeoResponse.ok(envelope(result));
    } catch (IllegalArgumentException e) {
      log.warn("Invalid base64 content in preview request", e);
      OBDal.getInstance().rollbackAndClose();
      return NeoResponse.error(400, "Invalid base64 content: " + e.getMessage());
    } catch (Exception e) {
      log.error("Error generating bank-statement preview", e);
      OBDal.getInstance().rollbackAndClose();
      return NeoResponse.error(500, "Preview failed: " + e.getMessage());
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /**
   * Reads the parsed lines for a statement directly from the DB via the same
   * LINES_SQL used by the GET endpoint. We're still inside the preview
   * transaction (which the caller will roll back), so the rows are visible
   * without a commit and they go away cleanly afterwards.
   */
  JSONArray readLinesForPreview(String statementId) throws Exception {
    JSONArray arr = new JSONArray();
    Connection conn = OBDal.getInstance().getConnection();
    try (PreparedStatement ps = conn.prepareStatement(LINES_SQL)) {
      ps.setString(1, statementId);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          BigDecimal credit = nullSafeBd(rs.getBigDecimal("cramount"));
          BigDecimal debit = nullSafeBd(rs.getBigDecimal("dramount"));
          JSONObject row = new JSONObject();
          row.put("lineNo", rs.getLong("line"));
          row.put("date", formatDate(rs.getTimestamp("datetrx")));
          row.put("description", StringUtils.trimToEmpty(rs.getString("description")));
          row.put("bpartnerName", StringUtils.trimToEmpty(rs.getString("bpartnername")));
          row.put("reference", StringUtils.trimToEmpty(rs.getString("referenceno")));
          row.put("cramount", credit);
          row.put("dramount", debit);
          arr.put(row);
        }
      }
    }
    return arr;
  }

  /**
   * Builds the envelope JSON returned by {@code handlePreview}. Aggregates
   * totals (abonos / cargos) and the period (min/max transaction date) over
   * the {@code lines} array supplied by {@link #readLinesForPreview}.
   */
  private JSONObject buildPreviewPayload(StatementFormat format, String fileName,
                                         int lineCount, JSONArray lines) throws Exception {
    BigDecimal totalIn = BigDecimal.ZERO;
    BigDecimal totalOut = BigDecimal.ZERO;
    String periodFrom = "";
    String periodTo = "";

    for (int i = 0; i < lines.length(); i++) {
      JSONObject row = lines.getJSONObject(i);
      BigDecimal credit = row.has("cramount") && !row.isNull("cramount")
          ? new BigDecimal(row.getString("cramount")) : BigDecimal.ZERO;
      BigDecimal debit = row.has("dramount") && !row.isNull("dramount")
          ? new BigDecimal(row.getString("dramount")) : BigDecimal.ZERO;
      totalIn = totalIn.add(credit);
      totalOut = totalOut.add(debit);

      String d = row.optString("date", "");
      if (!d.isEmpty()) {
        if (periodFrom.isEmpty() || d.compareTo(periodFrom) < 0) periodFrom = d;
        if (periodTo.isEmpty()   || d.compareTo(periodTo)   > 0) periodTo = d;
      }
    }

    JSONObject result = new JSONObject();
    result.put("format", format.name());
    result.put("fileName", fileName);
    result.put("lineCount", lineCount);
    result.put("totalIn", totalIn);
    result.put("totalOut", totalOut);
    result.put("periodFrom", periodFrom);
    result.put("periodTo", periodTo);
    result.put("lines", lines);
    return result;
  }

  private static JSONObject envelope(JSONObject data) throws Exception {
    JSONObject responseData = new JSONObject();
    responseData.put("data", data);
    JSONObject env = new JSONObject();
    env.put("response", responseData);
    return env;
  }

  private static BigDecimal nullSafeBd(BigDecimal value) {
    return value == null ? BigDecimal.ZERO : value;
  }

  FIN_BankStatement newBankStatement(FIN_FinancialAccount account, String fileName) {
    FIN_BankStatement statement = OBProvider.getInstance().get(FIN_BankStatement.class);
    statement.setClient(account.getClient());
    statement.setOrganization(account.getOrganization());
    statement.setActive(true);
    statement.setAccount(account);
    statement.setName(fileName);
    statement.setFileName(fileName);
    statement.setImportdate(new Date());
    statement.setTransactionDate(new Date());
    statement.setProcessed(false);
    statement.setPosted("N");
    statement.setDocumentType(resolveBsfDocType(account));
    return statement;
  }

  void processStatement(FIN_BankStatement statement) {
    statement.setProcessNow(true);
    OBDal.getInstance().save(statement);
    OBDal.getInstance().flush();

    statement.setProcessed(true);
    statement.setAPRMProcessBankStatement("R");
    statement.setAPRMProcessBankStatementForce("R");
    OBDal.getInstance().save(statement);
    OBDal.getInstance().flush();

    statement.setProcessNow(false);
    OBDal.getInstance().save(statement);
  }

  private DocumentType resolveBsfDocType(FIN_FinancialAccount account) {
    OBCriteria<DocumentType> crit = OBDal.getInstance().createCriteria(DocumentType.class);
    crit.add(org.hibernate.criterion.Restrictions.eq(DocumentType.PROPERTY_DOCUMENTCATEGORY, "BSF"));
    crit.add(org.hibernate.criterion.Restrictions.eq(DocumentType.PROPERTY_CLIENT, account.getClient()));
    crit.setFilterOnReadableOrganization(false);
    crit.setMaxResults(1);
    List<DocumentType> results = crit.list();
    if (results.isEmpty()) {
      throw new IllegalStateException("No BSF document type found for client: " + account.getClient().getId());
    }
    return results.get(0);
  }

  /**
   * Parses the C43 file using the Cuaderno43 importer via reflection.
   * Package-private so unit tests can stub it.
   *
   * @return the number of lines parsed and saved
   */
  int parseC43(ByteArrayInputStream stream, FIN_BankStatement statement) throws Exception {
    FIN_BankStatementImport importer = resolveC43Importer();
    importer.init(statement.getAccount());
    importer.loadFile(stream, statement);
    int lineCount = statement.getFINBankStatementLineList() != null
        ? statement.getFINBankStatementLineList().size()
        : 0;
    for (var line : statement.getFINBankStatementLineList()) {
      OBDal.getInstance().save(line);
    }
    return lineCount;
  }

  FIN_BankStatementImport resolveC43Importer() throws Exception {
    Class<?> clazz = Class.forName(C43_CLASS_NAME);
    return (FIN_BankStatementImport) clazz.getDeclaredConstructor().newInstance();
  }

  /**
   * Parses a generic CSV bank statement file using {@link GenericCsvBankStatementImporter}
   * — same column layout as the upstream {@code org.openbravo.bankstatement.importer.generic.csv}
   * module, but ported here so we don't take it as a runtime dependency.
   */
  int parseGenericCsv(ByteArrayInputStream stream, FIN_BankStatement statement) throws Exception {
    return new GenericCsvBankStatementImporter().loadFile(stream, statement);
  }

  enum StatementFormat { C43, GENERIC_CSV, UNKNOWN }

  /**
   * Sniffs the first few lines of {@code fileBytes} to decide which parser to
   * dispatch — neither the file extension nor a user choice is taken into
   * account.
   *
   * <p>Heuristics, in priority order:
   * <ul>
   *   <li><b>Cuaderno 43</b>: at least one of the first non-blank lines is
   *       exactly 80 chars long and starts with one of the record markers
   *       {@code 11}, {@code 22}, {@code 33}, {@code 99}. The C43 spec
   *       mandates fixed-width 80-char records, so this is essentially zero
   *       false-positive against any plain-text or CSV file.</li>
   *   <li><b>Generic CSV</b>: the first non-blank line contains at least two
   *       of the known headers (case-insensitive): {@code Transaction Date},
   *       {@code Amount IN}, {@code Amount OUT}, {@code Reference No.},
   *       {@code Business Partner Name}, {@code Description}.</li>
   *   <li>Otherwise {@code UNKNOWN}.</li>
   * </ul>
   */
  static StatementFormat detectFormat(byte[] fileBytes) {
    if (fileBytes == null || fileBytes.length == 0) return StatementFormat.UNKNOWN;
    // Only the head of the file is needed; this also caps cost on large uploads.
    int sampleLen = Math.min(fileBytes.length, 4096);
    String head = new String(fileBytes, 0, sampleLen, java.nio.charset.StandardCharsets.UTF_8);

    String[] lines = head.split("\\r?\\n", -1);
    for (String line : lines) {
      if (StringUtils.isBlank(line)) continue;
      if (line.length() == 80 && line.length() >= 2) {
        String code = line.substring(0, 2);
        if ("11".equals(code) || "22".equals(code) || "33".equals(code) || "99".equals(code)) {
          return StatementFormat.C43;
        }
      }
      // First non-blank line is interesting for both formats — keep going to
      // check the CSV header only if no C43 marker is found.
      break;
    }

    // CSV header check: look at the first non-blank line again (the file
    // header) regardless of where it landed in the byte sample.
    for (String line : lines) {
      if (StringUtils.isBlank(line)) continue;
      String lower = line.toLowerCase(java.util.Locale.ROOT);
      int hits = 0;
      for (String hdr : new String[] {
          "transaction date", "amount in", "amount out",
          "reference no.", "business partner name", "description" }) {
        if (lower.contains(hdr)) hits++;
      }
      if (hits >= 2) return StatementFormat.GENERIC_CSV;
      break;
    }

    return StatementFormat.UNKNOWN;
  }

  JSONArray loadStatements(String accountId) throws Exception {
    JSONArray arr = new JSONArray();
    Connection conn = OBDal.getInstance().getConnection();
    try (PreparedStatement ps = conn.prepareStatement(STATEMENTS_SQL)) {
      ps.setString(1, accountId);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          int lineCount = rs.getInt("line_count");
          int matchedCount = rs.getInt("matched_count");
          String periodFrom = formatDate(rs.getTimestamp("period_from"));
          String periodTo = formatDate(rs.getTimestamp("period_to"));

          JSONObject row = new JSONObject();
          row.put("id", rs.getString("fin_bankstatement_id"));
          row.put("documentNo", StringUtils.trimToEmpty(rs.getString("documentno")));
          row.put("name", StringUtils.trimToEmpty(rs.getString("name")));
          row.put("fileName", StringUtils.trimToEmpty(rs.getString("filename")));
          row.put("importDate", formatDate(rs.getTimestamp("importdate")));
          row.put("transactionDate", formatDate(rs.getTimestamp("statementdate")));
          row.put("processed", StringUtils.trimToEmpty(rs.getString("processed")));
          row.put("posted", StringUtils.trimToEmpty(rs.getString("posted")));
          row.put("lineCount", lineCount);
          row.put("matchedCount", matchedCount);
          row.put("totalAmount", nullSafeBigDecimal(rs.getBigDecimal("total_amount")));
          row.put("periodFrom", periodFrom);
          row.put("periodTo", periodTo);
          row.put("status", deriveStatementStatus(lineCount, matchedCount));
          arr.put(row);
        }
      }
    }
    return arr;
  }

  /**
   * Three-state status derived from how many of the statement's lines are
   * already matched to a financial-account transaction:
   *   matched == 0           → PENDING
   *   0 < matched < total    → PARTIAL
   *   matched == total > 0   → RECONCILED
   *   total == 0             → PENDING (empty statement)
   */
  static String deriveStatementStatus(int lineCount, int matchedCount) {
    if (lineCount == 0 || matchedCount == 0) return "PENDING";
    if (matchedCount >= lineCount) return "RECONCILED";
    return "PARTIAL";
  }

  static BigDecimal nullSafeBigDecimal(BigDecimal value) {
    return value == null ? BigDecimal.ZERO : value;
  }

  JSONArray loadLines(String statementId) throws Exception {
    JSONArray arr = new JSONArray();
    Connection conn = OBDal.getInstance().getConnection();
    try (PreparedStatement ps = conn.prepareStatement(LINES_SQL)) {
      ps.setString(1, statementId);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          BigDecimal credit = nullSafeBigDecimal(rs.getBigDecimal("cramount"));
          BigDecimal debit = nullSafeBigDecimal(rs.getBigDecimal("dramount"));
          JSONObject row = new JSONObject();
          row.put("id", rs.getString("fin_bankstatementline_id"));
          row.put("lineNo", rs.getLong("line"));
          row.put("date", formatDate(rs.getTimestamp("datetrx")));
          row.put("description", StringUtils.trimToEmpty(rs.getString("description")));
          row.put("reference", StringUtils.trimToEmpty(rs.getString("referenceno")));
          row.put("bpartnerName", StringUtils.trimToEmpty(rs.getString("bpartnername")));
          row.put("amount", credit.subtract(debit));
          row.put("matched", rs.getString("fin_finacc_transaction_id") != null);
          arr.put(row);
        }
      }
    }
    return arr;
  }

  private String formatDate(Timestamp ts) {
    if (ts == null) return "";
    return ISO_UTC.format(Instant.ofEpochMilli(ts.getTime()));
  }
}
