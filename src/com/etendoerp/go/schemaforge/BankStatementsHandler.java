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

import static com.etendoerp.go.schemaforge.BankStatementFormatDetector.detectFormat;
import static com.etendoerp.go.schemaforge.BankStatementsSupport.deriveStatementStatus;
import static com.etendoerp.go.schemaforge.BankStatementsSupport.formatDate;
import static com.etendoerp.go.schemaforge.BankStatementsSupport.nullSafeBigDecimal;
import static com.etendoerp.go.schemaforge.BankStatementsSupport.parseAmount;
import static com.etendoerp.go.schemaforge.BankStatementsSupport.parseIsoDate;
import static com.etendoerp.go.schemaforge.BankStatementsSupport.truncate;

import com.etendoerp.go.schemaforge.BankStatementFormatDetector.StatementFormat;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
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
import org.openbravo.base.exception.OBException;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.businesspartner.BusinessPartner;
import org.openbravo.model.common.enterprise.DocumentType;
import org.openbravo.model.financialmgmt.gl.GLItem;
import org.openbravo.model.financialmgmt.payment.FIN_BankStatement;
import org.openbravo.model.financialmgmt.payment.FIN_BankStatementLine;
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
  private static final String ACTION_CREATE = "create";
  private static final String ACTION_PROCESS = "process";
  private static final String ACTION_UPDATE = "update";
  private static final String ACTION_DELETE = "delete";
  private static final String ACTION_REACTIVATE = "reactivate";
  private static final String PARAM_ACCOUNT_ID = "FIN_Financial_Account_ID";
  private static final String PARAM_STATEMENT_ID = "statementId";
  private static final String PARAM_ACTION = "action";

  private static final String C43_CLASS_NAME =
      "org.openbravo.module.cuaderno43.es.utility.Cuaderno43";

  // JSON / SQL field keys reused across the handler (kept here to avoid
  // duplicating string literals — flagged by Sonar S1192).
  private static final String JSON_RESPONSE = "response";
  private static final String JSON_DATA = "data";
  private static final String KEY_STATEMENT = "statement";
  private static final String FIELD_FILE_NAME = "fileName";
  private static final String FIELD_LINE_COUNT = "lineCount";
  private static final String FIELD_DESCRIPTION = "description";
  private static final String FIELD_CRAMOUNT = "cramount";
  private static final String FIELD_DRAMOUNT = "dramount";
  private static final String FIELD_CONTENT_BASE64 = "contentBase64";
  private static final String FIELD_NAME = "name";
  private static final String FIELD_NOTES = "notes";
  private static final String FIELD_LINES = "lines";
  private static final String FIELD_BPARTNER_NAME = "bpartnerName";
  private static final String FIELD_BPARTNER_ID = "bpartnerId";
  private static final String FIELD_GLITEM_ID = "glItemId";
  private static final String FIELD_REFERENCE = "reference";
  private static final String FIELD_PROCESS = "process";
  private static final String FIELD_PROCESSED = "processed";
  private static final String FIELD_TRANSACTION_DATE = "transactionDate";
  private static final String FIELD_IMPORT_DATE = "importDate";
  private static final String FIELD_ID = "id";
  private static final String DEFAULT_REFERENCE = "**";
  private static final String MSG_MISSING_FIELD = "Missing required field: ";
  private static final String MSG_BODY_REQUIRED = "Request body is required";
  private static final String MSG_STATEMENT_NOT_FOUND = "Bank statement not found: ";
  private static final String MSG_NOT_DRAFT = "Only draft (unprocessed) statements can be modified";
  private static final String MSG_NOT_PROCESSED = "Only processed statements can be reactivated";
  private static final String MSG_POSTED = "The statement is posted and cannot be reactivated";
  private static final String MSG_HAS_RECONCILED =
      "The statement has reconciled lines; unreconcile them first";
  private static final String MSG_LINE_REQUIRED = "At least one line is required";

  /**
   * AutoCloseable wrapper around {@link OBContext#setAdminMode}. Lets us drop
   * try/finally blocks in favour of try-with-resources, which Sonar's S2093
   * rule prefers.
   */
  private static final class AdminMode implements AutoCloseable {
    AdminMode() { OBContext.setAdminMode(true); }
    @Override public void close() { OBContext.restorePreviousMode(); }
  }

  /**
   * Shared, validated payload extracted from the request body of
   * {@code ?action=import} and {@code ?action=preview}. {@link #error} is non
   * null when validation failed — callers should return that response and
   * skip the parser entirely.
   */
  private static final class UploadInput {
    NeoResponse error;
    String fileName;
    byte[] fileBytes;
    FIN_FinancialAccount account;
    StatementFormat format;

    static UploadInput fail(NeoResponse r) {
      UploadInput u = new UploadInput();
      u.error = r;
      return u;
    }
  }

  /**
   * Parses + validates the body shared by {@code handleImport} and
   * {@code handlePreview}. On failure returns an {@link UploadInput} whose
   * {@code error} field carries the 400 response; on success the wrapper
   * carries the resolved account, decoded bytes and detected format.
   *
   * @param verboseUnknownFormat when true, the "Unknown format" error includes
   *                             a long hint listing both formats; preview uses
   *                             the short variant.
   */
  private UploadInput parseUploadInput(JSONObject body, boolean verboseUnknownFormat) {
    String accountId = body.optString(PARAM_ACCOUNT_ID, null);
    String fileName = body.optString(FIELD_FILE_NAME, null);
    String contentBase64 = body.optString(FIELD_CONTENT_BASE64, null);

    if (StringUtils.isBlank(accountId)) {
      return UploadInput.fail(NeoResponse.error(400, MSG_MISSING_FIELD + PARAM_ACCOUNT_ID));
    }
    if (StringUtils.isBlank(fileName)) {
      return UploadInput.fail(NeoResponse.error(400, MSG_MISSING_FIELD + FIELD_FILE_NAME));
    }
    if (StringUtils.isBlank(contentBase64)) {
      return UploadInput.fail(NeoResponse.error(400, MSG_MISSING_FIELD + FIELD_CONTENT_BASE64));
    }

    FIN_FinancialAccount account = OBDal.getInstance().get(FIN_FinancialAccount.class, accountId);
    if (account == null) {
      return UploadInput.fail(NeoResponse.error(400, "Financial account not found: " + accountId));
    }

    byte[] fileBytes = Base64.getDecoder().decode(contentBase64);
    if (fileBytes.length == 0) {
      return UploadInput.fail(NeoResponse.error(400, "File content is empty"));
    }

    StatementFormat format = detectFormat(fileBytes);
    if (format == StatementFormat.UNKNOWN) {
      String msg = verboseUnknownFormat
          ? "Could not detect bank statement format. Expected either a Cuaderno 43 file"
              + " (80-char records starting with '11') or a generic CSV with"
              + " 'Transaction Date', 'Amount IN', 'Amount OUT' columns."
          : "Unsupported file format";
      return UploadInput.fail(NeoResponse.error(400, msg));
    }

    UploadInput u = new UploadInput();
    u.fileName = fileName;
    u.fileBytes = fileBytes;
    u.account = account;
    u.format = format;
    return u;
  }

  // The line count / matched count / totals / status are read straight from the
  // persisted EM_ETGO_* columns (maintained by BankStatementAggregates) — single
  // source of truth, no on-the-fly SUM/COUNT. The slim subquery only derives the
  // period (min/max transaction date), which is still computed from the lines and
  // used by the name fallback below.
  private static final String STATEMENTS_SQL =
      "SELECT bs.fin_bankstatement_id,"
          + "       bs.documentno,"
          + "       bs.name,"
          + "       bs.filename,"
          + "       bs.notes,"
          + "       bs.importdate,"
          + "       bs.statementdate,"
          + "       bs.processed,"
          + "       bs.posted,"
          + "       bs.em_etgo_line_count,"
          + "       bs.em_etgo_matched_count,"
          + "       bs.em_etgo_total_in,"
          + "       bs.em_etgo_total_out,"
          + "       bs.em_etgo_status,"
          + "       agg.period_from,"
          + "       agg.period_to"
          + "  FROM fin_bankstatement bs"
          + "  LEFT JOIN ("
          + "    SELECT bsl.fin_bankstatement_id,"
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
          + "       bsl.c_bpartner_id,"
          + "       bp.name AS bpartner_fk_name,"
          + "       bsl.c_glitem_id,"
          + "       gl.name AS glitem_name,"
          + "       bsl.cramount,"
          + "       bsl.dramount,"
          + "       bsl.fin_finacc_transaction_id"
          + "  FROM fin_bankstatementline bsl"
          + "  LEFT JOIN c_bpartner bp ON bp.c_bpartner_id = bsl.c_bpartner_id"
          + "  LEFT JOIN c_glitem gl ON gl.c_glitem_id = bsl.c_glitem_id"
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
      return ACTION_LINES.equals(action) ? handleGetLines(context) : handleList(context);
    }
    if (METHOD_POST.equals(method)) {
      return handlePost(action, context);
    }
    return NeoResponse.error(405, "Method not allowed.");
  }

  /** Routes the POST {@code action} values to their handlers. */
  private NeoResponse handlePost(String action, NeoContext context) {
    if (ACTION_IMPORT.equals(action))  return handleImport(context);
    if (ACTION_PREVIEW.equals(action)) return handlePreview(context);
    if (ACTION_CREATE.equals(action))  return handleCreate(context);
    if (ACTION_PROCESS.equals(action)) return handleProcess(context);
    if (ACTION_UPDATE.equals(action))  return handleUpdate(context);
    if (ACTION_DELETE.equals(action))  return handleDelete(context);
    if (ACTION_REACTIVATE.equals(action)) return handleReactivate(context);
    return NeoResponse.error(405, "Method not allowed.");
  }

  private NeoResponse handleList(NeoContext context) {
    String accountId = context.getQueryParams() != null
        ? context.getQueryParams().get(PARAM_ACCOUNT_ID)
        : null;
    if (StringUtils.isBlank(accountId)) {
      return NeoResponse.error(400, "Missing required parameter: " + PARAM_ACCOUNT_ID);
    }
    try (AdminMode ignored = new AdminMode()) {
      return NeoResponse.ok(wrapInEnvelope("statements", loadStatements(accountId)));
    } catch (Exception e) {
      log.error("Error listing bank statements for account {}", accountId, e);
      return NeoResponse.error(500, "Internal Server Error");
    }
  }

  private NeoResponse handleGetLines(NeoContext context) {
    String statementId = context.getQueryParams() != null
        ? context.getQueryParams().get(PARAM_STATEMENT_ID)
        : null;
    if (StringUtils.isBlank(statementId)) {
      return NeoResponse.error(400, "Missing required parameter: statementId");
    }
    try (AdminMode ignored = new AdminMode()) {
      return NeoResponse.ok(wrapInEnvelope(ACTION_LINES, loadLines(statementId)));
    } catch (Exception e) {
      log.error("Error loading lines for statement {}", statementId, e);
      return NeoResponse.error(500, "Internal Server Error");
    }
  }

  /**
   * Builds the {@code { "response": { "data": { <key>: <payload> } } }} envelope
   * shared by every successful GET endpoint here. Pulled out so the literal
   * keys "response" / "data" only appear once (S1192).
   */
  private static JSONObject wrapInEnvelope(String key, Object payload) throws Exception {
    JSONObject data = new JSONObject();
    data.put(key, payload);
    JSONObject responseData = new JSONObject();
    responseData.put(JSON_DATA, data);
    JSONObject env = new JSONObject();
    env.put(JSON_RESPONSE, responseData);
    return env;
  }

  private NeoResponse handleImport(NeoContext context) {
    JSONObject body = context.getRequestBody();
    if (body == null) {
      return NeoResponse.error(400, MSG_BODY_REQUIRED);
    }
    try (AdminMode ignored = new AdminMode()) {
      // Suppress the per-line observer for this bulk import — the aggregates are
      // recomputed once below instead of once per parsed line.
      BankStatementLineAggregateHandler.suppress();
      UploadInput in = parseUploadInput(body, true);
      if (in.error != null) return in.error;

      FIN_BankStatement statement = newBankStatement(in.account, in.fileName);
      OBDal.getInstance().save(statement);

      int lineCount = runParser(in.format, in.fileBytes, statement);
      processStatement(statement);
      BankStatementAggregates.recompute(statement);
      OBDal.getInstance().flush();

      JSONObject result = new JSONObject();
      result.put("id", statement.getId());
      result.put(FIELD_FILE_NAME, in.fileName);
      result.put(FIELD_LINE_COUNT, lineCount);
      return NeoResponse.createdWithData(result);

    } catch (IllegalArgumentException e) {
      log.warn("Invalid base64 content in import request", e);
      return NeoResponse.error(400, "Invalid base64 content: " + e.getMessage());
    } catch (Exception e) {
      log.error("Error importing bank statement", e);
      OBDal.getInstance().rollbackAndClose();
      return NeoResponse.error(500, "Import failed: " + e.getMessage());
    } finally {
      BankStatementLineAggregateHandler.resume();
    }
  }

  /**
   * Handles {@code POST ?action=create} — creates a bank statement by hand
   * (header + lines) without a file, for accounts that receive statements
   * outside the supported file formats. Mirrors the file-import path: it builds
   * the {@link FIN_BankStatement}, one {@link FIN_BankStatementLine} per
   * non-blank line, then runs {@link #processStatement} so the lines become
   * available for reconciliation exactly like an imported statement.
   *
   * <p>Body shape:
   * <pre>
   * {
   *   "FIN_Financial_Account_ID": "...",
   *   "name": "Extracto BBVA · junio 2026",
   *   "transactionDate": "2026-06-04T00:00:00Z",
   *   "importDate":      "2026-06-04T00:00:00Z",
   *   "lines": [
   *     { "date": "2026-06-02T00:00:00Z", "description": "...",
   *       "bpartnerName": "...", "in": 3500.00, "out": 0 }
   *   ]
   * }
   * </pre>
   */
  private NeoResponse handleCreate(NeoContext context) {
    JSONObject body = context.getRequestBody();
    if (body == null) return NeoResponse.error(400, MSG_BODY_REQUIRED);
    try (AdminMode ignored = new AdminMode()) {
      BankStatementLineAggregateHandler.suppress();
      NeoResponse validation = validateCreateBody(body);
      if (validation != null) return validation;

      String accountId = body.optString(PARAM_ACCOUNT_ID, null);
      FIN_FinancialAccount account = OBDal.getInstance().get(FIN_FinancialAccount.class, accountId);
      if (account == null) {
        return NeoResponse.error(400, "Financial account not found: " + accountId);
      }

      String name = body.optString(FIELD_NAME, null);
      FIN_BankStatement statement = newManualBankStatement(account, body);
      OBDal.getInstance().save(statement);

      int lineCount = createLines(statement, body.optJSONArray(FIELD_LINES));
      // "Save and process" runs the statement like an import so its lines become
      // reconcilable; "Save as draft" (process=false) just persists it.
      boolean process = body.optBoolean(FIELD_PROCESS, true);
      if (process) {
        processStatement(statement);
      }
      // Flush the freshly created lines BEFORE recomputing — the draft path
      // (process == false) does not call processStatement, so without this the
      // recompute query would not see the just-saved lines and would store 0.
      OBDal.getInstance().flush();
      BankStatementAggregates.recompute(statement);
      OBDal.getInstance().flush();

      JSONObject result = new JSONObject();
      result.put("id", statement.getId());
      result.put(FIELD_NAME, name);
      result.put(FIELD_LINE_COUNT, lineCount);
      result.put(FIELD_PROCESSED, process);
      return NeoResponse.createdWithData(result);

    } catch (OBException e) {
      log.warn("Manual statement validation failed: {}", e.getMessage());
      OBDal.getInstance().rollbackAndClose();
      return NeoResponse.error(400, e.getMessage());
    } catch (Exception e) {
      // Never echo e.getMessage() — it can leak DB constraint names. Log the
      // full trace server-side and return a generic message to the client.
      log.error("Error creating manual bank statement", e);
      OBDal.getInstance().rollbackAndClose();
      return NeoResponse.error(500, "Could not create the statement. Please check logs for details.");
    } finally {
      BankStatementLineAggregateHandler.resume();
    }
  }

  /**
   * {@code ?action=process} — runs a draft statement so its lines become
   * reconcilable, mirroring "Save and process" on the create flow. Only drafts
   * (unprocessed) can be processed. Body: {@code { "id": "..." }}.
   */
  private NeoResponse handleProcess(NeoContext context) {
    JSONObject body = context.getRequestBody();
    if (body == null) return NeoResponse.error(400, MSG_BODY_REQUIRED);
    try (AdminMode ignored = new AdminMode()) {
      BankStatementLineAggregateHandler.suppress();
      FIN_BankStatement statement = requireDraft(body.optString(FIELD_ID, null));
      processStatement(statement);
      BankStatementAggregates.recompute(statement);
      OBDal.getInstance().flush();

      JSONObject result = new JSONObject();
      result.put(FIELD_ID, statement.getId());
      result.put(FIELD_PROCESSED, true);
      return NeoResponse.ok(wrapInEnvelope(KEY_STATEMENT, result));
    } catch (OBException e) {
      log.warn("Process statement failed: {}", e.getMessage());
      OBDal.getInstance().rollbackAndClose();
      return NeoResponse.error(400, e.getMessage());
    } catch (Exception e) {
      log.error("Error processing bank statement", e);
      OBDal.getInstance().rollbackAndClose();
      return NeoResponse.error(500, "Could not process the statement. Please check logs for details.");
    } finally {
      BankStatementLineAggregateHandler.resume();
    }
  }

  /**
   * {@code ?action=reactivate} — returns a processed statement to draft so it can
   * be edited or deleted again, mirroring the core "Reactivate" action of
   * {@code FIN_BankStatementProcess}. Only processed statements qualify; the
   * statement must not be posted and must have no reconciled lines (reactivating
   * does NOT reverse reconciliations — the user must unreconcile first).
   * Body: {@code { "id": "..." }}.
   */
  private NeoResponse handleReactivate(NeoContext context) {
    JSONObject body = context.getRequestBody();
    if (body == null) return NeoResponse.error(400, MSG_BODY_REQUIRED);
    try (AdminMode ignored = new AdminMode()) {
      BankStatementLineAggregateHandler.suppress();
      FIN_BankStatement statement = requireProcessed(body.optString(FIELD_ID, null));
      if ("Y".equals(statement.getPosted())) {
        return NeoResponse.error(400, MSG_POSTED);
      }
      if (hasReconciledLines(statement)) {
        return NeoResponse.error(400, MSG_HAS_RECONCILED);
      }
      reactivateStatement(statement);
      OBDal.getInstance().flush();
      BankStatementAggregates.recompute(statement);
      OBDal.getInstance().flush();

      JSONObject result = new JSONObject();
      result.put(FIELD_ID, statement.getId());
      result.put(FIELD_PROCESSED, false);
      return NeoResponse.ok(wrapInEnvelope(KEY_STATEMENT, result));
    } catch (OBException e) {
      log.warn("Reactivate statement failed: {}", e.getMessage());
      OBDal.getInstance().rollbackAndClose();
      return NeoResponse.error(400, e.getMessage());
    } catch (Exception e) {
      log.error("Error reactivating bank statement", e);
      OBDal.getInstance().rollbackAndClose();
      return NeoResponse.error(500, "Could not reactivate the statement. Please check logs for details.");
    } finally {
      BankStatementLineAggregateHandler.resume();
    }
  }

  /**
   * {@code ?action=update} — edits a draft statement's header and replaces all
   * its lines with the ones in the body. Same body shape as create plus the
   * {@code "id"} of the statement to edit. Only drafts can be edited; passing
   * {@code "process": true} also runs it after saving.
   */
  private NeoResponse handleUpdate(NeoContext context) {
    JSONObject body = context.getRequestBody();
    if (body == null) return NeoResponse.error(400, MSG_BODY_REQUIRED);
    try (AdminMode ignored = new AdminMode()) {
      BankStatementLineAggregateHandler.suppress();
      FIN_BankStatement statement = requireDraft(body.optString(FIELD_ID, null));
      if (StringUtils.isBlank(body.optString(FIELD_NAME, null))) {
        return NeoResponse.error(400, MSG_MISSING_FIELD + FIELD_NAME);
      }
      JSONArray bodyLines = body.optJSONArray(FIELD_LINES);
      if (bodyLines == null || bodyLines.length() == 0) {
        return NeoResponse.error(400, MSG_LINE_REQUIRED);
      }

      applyEditableHeader(statement, body);
      OBDal.getInstance().save(statement);

      deleteLines(statement);
      int lineCount = createLines(statement, bodyLines);

      boolean process = body.optBoolean(FIELD_PROCESS, false);
      if (process) {
        processStatement(statement);
      }
      // Flush the rebuilt lines BEFORE recomputing — update defaults to
      // process == false, so without this the recompute query would not see the
      // just-saved lines and would store 0 (same reason as handleCreate).
      OBDal.getInstance().flush();
      BankStatementAggregates.recompute(statement);
      OBDal.getInstance().flush();

      JSONObject result = new JSONObject();
      result.put(FIELD_ID, statement.getId());
      result.put(FIELD_NAME, statement.getName());
      result.put(FIELD_LINE_COUNT, lineCount);
      result.put(FIELD_PROCESSED, process);
      return NeoResponse.ok(wrapInEnvelope(KEY_STATEMENT, result));
    } catch (OBException e) {
      log.warn("Update statement failed: {}", e.getMessage());
      OBDal.getInstance().rollbackAndClose();
      return NeoResponse.error(400, e.getMessage());
    } catch (Exception e) {
      log.error("Error updating bank statement", e);
      OBDal.getInstance().rollbackAndClose();
      return NeoResponse.error(500, "Could not update the statement. Please check logs for details.");
    } finally {
      BankStatementLineAggregateHandler.resume();
    }
  }

  /**
   * {@code ?action=delete} — permanently removes a draft statement and its
   * lines. Only drafts can be deleted; processed statements are protected.
   * Body: {@code { "id": "..." }}.
   */
  private NeoResponse handleDelete(NeoContext context) {
    JSONObject body = context.getRequestBody();
    if (body == null) return NeoResponse.error(400, MSG_BODY_REQUIRED);
    try (AdminMode ignored = new AdminMode()) {
      // The statement is removed below, so there is nothing to recompute; we only
      // suppress the per-line observer so deleting its lines doesn't try to update
      // a statement that is about to vanish.
      BankStatementLineAggregateHandler.suppress();
      FIN_BankStatement statement = requireDraft(body.optString(FIELD_ID, null));
      String id = statement.getId();
      deleteLines(statement);
      OBDal.getInstance().remove(statement);
      OBDal.getInstance().flush();

      JSONObject result = new JSONObject();
      result.put(FIELD_ID, id);
      return NeoResponse.ok(wrapInEnvelope(KEY_STATEMENT, result));
    } catch (OBException e) {
      log.warn("Delete statement failed: {}", e.getMessage());
      OBDal.getInstance().rollbackAndClose();
      return NeoResponse.error(400, e.getMessage());
    } catch (Exception e) {
      log.error("Error deleting bank statement", e);
      OBDal.getInstance().rollbackAndClose();
      return NeoResponse.error(500, "Could not delete the statement. Please check logs for details.");
    } finally {
      BankStatementLineAggregateHandler.resume();
    }
  }

  /**
   * Loads a statement by id and guards that it is an editable draft. Throws
   * {@link OBException} (mapped to 400 by the callers) when the id is blank, the
   * statement does not exist, or it has already been processed. Centralises the
   * checks shared by the process, update and delete actions.
   */
  private FIN_BankStatement requireDraft(String id) {
    if (StringUtils.isBlank(id)) {
      throw new OBException(MSG_MISSING_FIELD + FIELD_ID);
    }
    FIN_BankStatement statement = OBDal.getInstance().get(FIN_BankStatement.class, id);
    if (statement == null) {
      throw new OBException(MSG_STATEMENT_NOT_FOUND + id);
    }
    if (Boolean.TRUE.equals(statement.isProcessed())) {
      throw new OBException(MSG_NOT_DRAFT);
    }
    return statement;
  }

  /**
   * Loads a statement by id and guards that it is processed (the only state that
   * can be reactivated). Throws {@link OBException} (mapped to 400) when the id is
   * blank, the statement does not exist, or it is still a draft.
   */
  private FIN_BankStatement requireProcessed(String id) {
    if (StringUtils.isBlank(id)) {
      throw new OBException(MSG_MISSING_FIELD + FIELD_ID);
    }
    FIN_BankStatement statement = OBDal.getInstance().get(FIN_BankStatement.class, id);
    if (statement == null) {
      throw new OBException(MSG_STATEMENT_NOT_FOUND + id);
    }
    if (!Boolean.TRUE.equals(statement.isProcessed())) {
      throw new OBException(MSG_NOT_PROCESSED);
    }
    return statement;
  }

  /**
   * Whether {@code statement} has at least one active line already reconciled
   * (linked to a financial-account transaction). Reactivation is blocked in that
   * case — mirrors the core {@code FIN_BankStatementProcess} guard. Package-private
   * so it can be stubbed in unit tests.
   */
  boolean hasReconciledLines(FIN_BankStatement statement) {
    OBCriteria<FIN_BankStatementLine> crit =
        OBDal.getInstance().createCriteria(FIN_BankStatementLine.class);
    crit.add(org.hibernate.criterion.Restrictions.eq(
        FIN_BankStatementLine.PROPERTY_BANKSTATEMENT, statement));
    crit.add(org.hibernate.criterion.Restrictions.eq(
        FIN_BankStatementLine.PROPERTY_ACTIVE, true));
    crit.add(org.hibernate.criterion.Restrictions.isNotNull(
        FIN_BankStatementLine.PROPERTY_FINANCIALACCOUNTTRANSACTION));
    crit.setFilterOnReadableOrganization(false);
    crit.setMaxResults(1);
    return !crit.list().isEmpty();
  }

  /**
   * Returns a processed statement to draft, mirroring the core "Reactivate"
   * action: clears the Processed flag and flips the APRM process selector back to
   * "P". Callers must have already validated it is processed, not posted and has
   * no reconciled lines.
   */
  void reactivateStatement(FIN_BankStatement statement) {
    statement.setProcessNow(true);
    OBDal.getInstance().save(statement);
    OBDal.getInstance().flush();

    statement.setProcessed(false);
    statement.setAPRMProcessBankStatement("P");
    statement.setAPRMProcessBankStatementForce("P");
    OBDal.getInstance().save(statement);
    OBDal.getInstance().flush();

    statement.setProcessNow(false);
    OBDal.getInstance().save(statement);
  }

  /** Removes every line of {@code statement} so {@link #createLines} can rebuild them. */
  private void deleteLines(FIN_BankStatement statement) {
    OBCriteria<FIN_BankStatementLine> crit =
        OBDal.getInstance().createCriteria(FIN_BankStatementLine.class);
    crit.add(org.hibernate.criterion.Restrictions.eq(
        FIN_BankStatementLine.PROPERTY_BANKSTATEMENT, statement));
    for (FIN_BankStatementLine line : crit.list()) {
      OBDal.getInstance().remove(line);
    }
    OBDal.getInstance().flush();
  }

  /**
   * Validates the {@code ?action=create} body. Returns {@code null} when valid,
   * or the appropriate 400 {@link NeoResponse}. Extracted so {@link #handleCreate}
   * stays under Sonar's cognitive-complexity threshold.
   */
  private static NeoResponse validateCreateBody(JSONObject body) {
    if (StringUtils.isBlank(body.optString(PARAM_ACCOUNT_ID, null))) {
      return NeoResponse.error(400, MSG_MISSING_FIELD + PARAM_ACCOUNT_ID);
    }
    if (StringUtils.isBlank(body.optString(FIELD_NAME, null))) {
      return NeoResponse.error(400, MSG_MISSING_FIELD + FIELD_NAME);
    }
    JSONArray lines = body.optJSONArray(FIELD_LINES);
    if (lines == null || lines.length() == 0) {
      return NeoResponse.error(400, MSG_LINE_REQUIRED);
    }
    return null;
  }

  /**
   * Builds a {@link FIN_BankStatement} for the manual-create flow from the
   * request body. Same header fields as Classic's manual statement: name,
   * import/transaction dates, file name and notes (all but name optional). The
   * document type is always the account's BSF type.
   */
  FIN_BankStatement newManualBankStatement(FIN_FinancialAccount account, JSONObject body) {
    FIN_BankStatement statement = OBProvider.getInstance().get(FIN_BankStatement.class);
    statement.setClient(account.getClient());
    statement.setOrganization(account.getOrganization());
    statement.setActive(true);
    statement.setAccount(account);
    applyEditableHeader(statement, body);
    statement.setProcessed(false);
    statement.setPosted("N");
    statement.setDocumentType(resolveBsfDocType(account));
    return statement;
  }

  /**
   * Applies the user-editable header fields (name, import/transaction dates,
   * file name and notes) from the request body onto a statement. Shared by the
   * manual create and update flows so both treat the header identically. Blank
   * file name / notes clear the field, mirroring an edit that removed them.
   */
  private void applyEditableHeader(FIN_BankStatement statement, JSONObject body) {
    statement.setName(body.optString(FIELD_NAME, null));
    statement.setImportdate(parseIsoDate(body.optString(FIELD_IMPORT_DATE, null), new Date()));
    statement.setTransactionDate(parseIsoDate(body.optString(FIELD_TRANSACTION_DATE, null), new Date()));
    String fileName = body.optString(FIELD_FILE_NAME, null);
    statement.setFileName(StringUtils.isNotBlank(fileName) ? truncate(fileName, 60) : null);
    String notes = body.optString(FIELD_NOTES, null);
    statement.setNotes(StringUtils.isNotBlank(notes) ? truncate(notes, 2000) : null);
  }

  /**
   * Creates and persists one {@link FIN_BankStatementLine} per non-blank entry
   * in {@code lines}, numbering them 10, 20, 30… Fully-blank rows (no date, no
   * description, no counterparty and zero amounts) are skipped. Throws when no
   * usable line remains so the caller can map it to a 400.
   *
   * @return the number of lines actually created
   */
  private int createLines(FIN_BankStatement statement, JSONArray lines) throws Exception {
    int count = 0;
    long lineNo = 10L;
    for (int i = 0; i < lines.length(); i++) {
      JSONObject l = lines.getJSONObject(i);
      if (isBlankLine(l)) continue;
      FIN_BankStatementLine line = OBProvider.getInstance().get(FIN_BankStatementLine.class);
      line.setBankStatement(statement);
      line.setClient(statement.getClient());
      line.setOrganization(statement.getOrganization());
      line.setLineNo(lineNo);
      line.setTransactionDate(parseIsoDate(l.optString("date", null), statement.getTransactionDate()));
      line.setCramount(parseAmount(l.optString("in", null)));
      line.setDramount(parseAmount(l.optString("out", null)));

      String bpName = l.optString(FIELD_BPARTNER_NAME, null);
      if (StringUtils.isNotBlank(bpName)) line.setBpartnername(truncate(bpName, 60));
      String desc = l.optString(FIELD_DESCRIPTION, null);
      if (StringUtils.isNotBlank(desc)) line.setDescription(truncate(desc, 2000));
      String ref = l.optString(FIELD_REFERENCE, null);
      line.setReferenceNo(StringUtils.isBlank(ref) ? DEFAULT_REFERENCE : truncate(ref, 30));

      resolveLineReferences(line, l);

      OBDal.getInstance().save(line);
      lineNo += 10L;
      count++;
    }
    if (count == 0) {
      throw new OBException("At least one non-empty line is required");
    }
    return count;
  }

  /**
   * Resolves and attaches the optional FK references of a line — the business
   * partner ({@code bpartnerId}) and the G/L item ({@code glItemId}). Missing or
   * unresolvable ids are silently skipped, mirroring Classic where both are
   * optional on a bank-statement line. Extracted from {@link #createLines} to
   * keep its loop body under Sonar's cognitive-complexity threshold.
   */
  private void resolveLineReferences(FIN_BankStatementLine line, JSONObject l) {
    String bpId = l.optString(FIELD_BPARTNER_ID, null);
    if (StringUtils.isNotBlank(bpId)) {
      BusinessPartner bp = OBDal.getInstance().get(BusinessPartner.class, bpId);
      if (bp != null) line.setBusinessPartner(bp);
    }
    String glId = l.optString(FIELD_GLITEM_ID, null);
    if (StringUtils.isNotBlank(glId)) {
      GLItem gl = OBDal.getInstance().get(GLItem.class, glId);
      if (gl != null) line.setGLItem(gl);
    }
  }

  /**
   * A line is blank when it carries no description, counterparty, reference or
   * FK and both amounts are zero — such rows come from the trailing empty row of
   * the editable grid and must not be persisted. The transaction date is
   * ignored on purpose: the UI pre-fills it with today, so a row with only that
   * default date still counts as empty.
   */
  private static boolean isBlankLine(JSONObject l) {
    return StringUtils.isBlank(l.optString(FIELD_DESCRIPTION, null))
        && StringUtils.isBlank(l.optString(FIELD_BPARTNER_NAME, null))
        && StringUtils.isBlank(l.optString(FIELD_BPARTNER_ID, null))
        && StringUtils.isBlank(l.optString(FIELD_GLITEM_ID, null))
        && StringUtils.isBlank(l.optString(FIELD_REFERENCE, null))
        && parseAmount(l.optString("in", null)).signum() == 0
        && parseAmount(l.optString("out", null)).signum() == 0;
  }

  /**
   * Dispatches to the right parser by {@link StatementFormat} and returns the
   * line count. Centralises the CSV/C43 branching so both endpoints stay free
   * of conditional duplication.
   */
  private int runParser(StatementFormat format, byte[] fileBytes, FIN_BankStatement statement) throws Exception {
    ByteArrayInputStream stream = new ByteArrayInputStream(fileBytes);
    return format == StatementFormat.GENERIC_CSV
        ? parseGenericCsv(stream, statement)
        : parseC43(stream, statement);
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
    if (body == null) return NeoResponse.error(400, MSG_BODY_REQUIRED);
    try (AdminMode ignored = new AdminMode()) {
      UploadInput in = parseUploadInput(body, false);
      if (in.error != null) return in.error;

      // Both parsers (Cuaderno43 by reflection, our GenericCsv) call
      // OBDal.save(line) per parsed line. For that to keep the lines
      // attached to the statement's collection (getFINBankStatementLineList)
      // we need the statement persisted first — otherwise Hibernate cascades
      // can drop them silently. We persist + flush, then rollback at the end
      // so the import is genuinely read-only.
      FIN_BankStatement transientStmt = newBankStatement(in.account, in.fileName);
      OBDal.getInstance().save(transientStmt);
      OBDal.getInstance().flush();

      // We ignore the parser's reported line count: Cuaderno43's reflection
      // path returns 0 because statement.getFINBankStatementLineList() is
      // not refreshed after save(line). We re-read from the DB right below.
      runParser(in.format, in.fileBytes, transientStmt);
      OBDal.getInstance().flush();

      // Read the parsed lines straight from the DB instead of going through
      // statement.getFINBankStatementLineList(). The entity collection isn't
      // refreshed after save(line), so it can come back empty even when the
      // rows are physically there. We're still in the same transaction; the
      // rollback below discards everything anyway.
      JSONArray lines = readLinesForPreview(transientStmt.getId());

      // Use the SQL row count as the canonical lineCount — that's the only
      // value guaranteed to match what the user will actually see in step 2.
      JSONObject result = BankStatementPreview.buildPayload(in.format, in.fileName, lines.length(), lines);

      // Drop everything we parsed — preview is read-only. rollbackAndClose
      // discards both the statement and the cascaded lines from the DB.
      OBDal.getInstance().rollbackAndClose();

      JSONObject responseData = new JSONObject();
      responseData.put(JSON_DATA, result);
      JSONObject env = new JSONObject();
      env.put(JSON_RESPONSE, responseData);
      return NeoResponse.ok(env);
    } catch (IllegalArgumentException e) {
      log.warn("Invalid base64 content in preview request", e);
      OBDal.getInstance().rollbackAndClose();
      return NeoResponse.error(400, "Invalid base64 content: " + e.getMessage());
    } catch (Exception e) {
      log.error("Error generating bank-statement preview", e);
      OBDal.getInstance().rollbackAndClose();
      return NeoResponse.error(500, "Preview failed: " + e.getMessage());
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
    // NOTE: the Connection returned by OBDal is managed by Hibernate's
    // Session — DO NOT close it here. Only the PreparedStatement and
    // ResultSet go inside try-with-resources.
    Connection conn = OBDal.getInstance().getConnection();
    try (PreparedStatement ps = conn.prepareStatement(LINES_SQL)) {
      ps.setString(1, statementId);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          BigDecimal credit = nullSafeBigDecimal(rs.getBigDecimal(FIELD_CRAMOUNT));
          BigDecimal debit = nullSafeBigDecimal(rs.getBigDecimal(FIELD_DRAMOUNT));
          JSONObject row = new JSONObject();
          row.put("lineNo", rs.getLong("line"));
          row.put("date", formatDate(rs.getTimestamp("datetrx")));
          row.put(FIELD_DESCRIPTION, StringUtils.trimToEmpty(rs.getString(FIELD_DESCRIPTION)));
          row.put(FIELD_BPARTNER_NAME, StringUtils.trimToEmpty(rs.getString("bpartnername")));
          row.put(FIELD_REFERENCE, StringUtils.trimToEmpty(rs.getString("referenceno")));
          row.put(FIELD_CRAMOUNT, credit);
          row.put(FIELD_DRAMOUNT, debit);
          arr.put(row);
        }
      }
    }
    return arr;
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
      throw new OBException("No BSF document type found for client: " + account.getClient().getId());
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

  JSONArray loadStatements(String accountId) throws Exception {
    JSONArray arr = new JSONArray();
    // Connection is managed by the DAL's Hibernate Session; don't close it.
    Connection conn = OBDal.getInstance().getConnection();
    try (PreparedStatement ps = conn.prepareStatement(STATEMENTS_SQL)) {
      ps.setString(1, accountId);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          int lineCount = rs.getInt("em_etgo_line_count");
          int matchedCount = rs.getInt("em_etgo_matched_count");
          BigDecimal totalIn = nullSafeBigDecimal(rs.getBigDecimal("em_etgo_total_in"));
          BigDecimal totalOut = nullSafeBigDecimal(rs.getBigDecimal("em_etgo_total_out"));
          String periodFrom = formatDate(rs.getTimestamp("period_from"));
          String periodTo = formatDate(rs.getTimestamp("period_to"));

          JSONObject row = new JSONObject();
          row.put("id", rs.getString("fin_bankstatement_id"));
          row.put("documentNo", StringUtils.trimToEmpty(rs.getString("documentno")));
          row.put("name", StringUtils.trimToEmpty(rs.getString("name")));
          row.put(FIELD_FILE_NAME, StringUtils.trimToEmpty(rs.getString("filename")));
          row.put(FIELD_NOTES, StringUtils.trimToEmpty(rs.getString(FIELD_NOTES)));
          row.put(FIELD_IMPORT_DATE, formatDate(rs.getTimestamp("importdate")));
          row.put(FIELD_TRANSACTION_DATE, formatDate(rs.getTimestamp("statementdate")));
          boolean processed = "Y".equalsIgnoreCase(rs.getString(FIELD_PROCESSED));
          row.put(FIELD_PROCESSED, StringUtils.trimToEmpty(rs.getString(FIELD_PROCESSED)));
          row.put("posted", StringUtils.trimToEmpty(rs.getString("posted")));
          row.put(FIELD_LINE_COUNT, lineCount);
          row.put("matchedCount", matchedCount);
          // totalAmount is kept for the existing conditional filter; it is just the
          // sum of the two persisted columns, derived here so we store one less column.
          row.put("totalAmount", totalIn.add(totalOut));
          row.put("totalIn", totalIn);
          row.put("totalOut", totalOut);
          row.put("periodFrom", periodFrom);
          row.put("periodTo", periodTo);
          // Read the persisted status; fall back to deriving it for rows that
          // predate the column and have not been backfilled yet.
          String status = StringUtils.trimToEmpty(rs.getString("em_etgo_status"));
          if (StringUtils.isBlank(status)) {
            status = deriveStatementStatus(processed, lineCount, matchedCount);
          }
          row.put("status", status);
          arr.put(row);
        }
      }
    }
    return arr;
  }

  JSONArray loadLines(String statementId) throws Exception {
    JSONArray arr = new JSONArray();
    // Connection is managed by the DAL's Hibernate Session; don't close it.
    Connection conn = OBDal.getInstance().getConnection();
    try (PreparedStatement ps = conn.prepareStatement(LINES_SQL)) {
      ps.setString(1, statementId);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          BigDecimal credit = nullSafeBigDecimal(rs.getBigDecimal(FIELD_CRAMOUNT));
          BigDecimal debit = nullSafeBigDecimal(rs.getBigDecimal(FIELD_DRAMOUNT));
          JSONObject row = new JSONObject();
          row.put("id", rs.getString("fin_bankstatementline_id"));
          row.put("lineNo", rs.getLong("line"));
          row.put("date", formatDate(rs.getTimestamp("datetrx")));
          row.put(FIELD_DESCRIPTION, StringUtils.trimToEmpty(rs.getString(FIELD_DESCRIPTION)));
          row.put("reference", StringUtils.trimToEmpty(rs.getString("referenceno")));
          row.put("bpartnerName", StringUtils.trimToEmpty(rs.getString("bpartnername")));
          row.put(FIELD_BPARTNER_ID, StringUtils.trimToEmpty(rs.getString("c_bpartner_id")));
          row.put("bpartnerFkName", StringUtils.trimToEmpty(rs.getString("bpartner_fk_name")));
          row.put(FIELD_GLITEM_ID, StringUtils.trimToEmpty(rs.getString("c_glitem_id")));
          row.put("glItemName", StringUtils.trimToEmpty(rs.getString("glitem_name")));
          row.put("in", credit);
          row.put("out", debit);
          row.put("amount", credit.subtract(debit));
          row.put("matched", rs.getString("fin_finacc_transaction_id") != null);
          arr.put(row);
        }
      }
    }
    return arr;
  }
}
