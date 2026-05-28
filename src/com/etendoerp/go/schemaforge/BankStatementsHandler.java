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

import javax.inject.Named;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.advpaymentmngt.utility.FIN_BankStatementImport;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
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
          + "       bs.posted"
          + "  FROM fin_bankstatement bs"
          + " WHERE bs.fin_financial_account_id = ?"
          + "   AND bs.isactive = 'Y'"
          + " ORDER BY bs.importdate DESC";

  private static final String LINES_SQL =
      "SELECT bsl.fin_bankstatementline_id,"
          + "       bsl.lineno,"
          + "       bsl.transactiondate,"
          + "       bsl.description,"
          + "       bsl.referenceno,"
          + "       bsl.bpartnername,"
          + "       bsl.cramount,"
          + "       bsl.dramount,"
          + "       bsl.fin_finacc_transaction_id"
          + "  FROM fin_bankstatementline bsl"
          + " WHERE bsl.fin_bankstatement_id = ?"
          + "   AND bsl.isactive = 'Y'"
          + " ORDER BY bsl.lineno ASC";

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

      ByteArrayInputStream stream = new ByteArrayInputStream(fileBytes);
      int lineCount = parseC43(stream, statement);

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
    return statement;
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

  JSONArray loadStatements(String accountId) throws Exception {
    JSONArray arr = new JSONArray();
    Connection conn = OBDal.getInstance().getConnection();
    try (PreparedStatement ps = conn.prepareStatement(STATEMENTS_SQL)) {
      ps.setString(1, accountId);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          JSONObject row = new JSONObject();
          row.put("id", rs.getString("fin_bankstatement_id"));
          row.put("documentNo", StringUtils.trimToEmpty(rs.getString("documentno")));
          row.put("name", StringUtils.trimToEmpty(rs.getString("name")));
          row.put("fileName", StringUtils.trimToEmpty(rs.getString("filename")));
          row.put("importDate", formatDate(rs.getTimestamp("importdate")));
          row.put("transactionDate", formatDate(rs.getTimestamp("statementdate")));
          row.put("processed", StringUtils.trimToEmpty(rs.getString("processed")));
          row.put("posted", StringUtils.trimToEmpty(rs.getString("posted")));
          arr.put(row);
        }
      }
    }
    return arr;
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
          row.put("lineNo", rs.getLong("lineno"));
          row.put("date", formatDate(rs.getTimestamp("transactiondate")));
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

  static BigDecimal nullSafeBigDecimal(BigDecimal value) {
    return value == null ? BigDecimal.ZERO : value;
  }
}
