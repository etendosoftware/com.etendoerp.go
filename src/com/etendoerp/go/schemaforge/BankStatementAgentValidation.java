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
 * All portions are Copyright (C) 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.schemaforge;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.dal.core.OBContext;
import org.openbravo.model.common.businesspartner.BusinessPartner;
import org.openbravo.model.financialmgmt.gl.GLItem;

import com.etendoerp.go.schemaforge.util.NeoActionContract;

/**
 * The checks the Etendo GO UI applies to a bank statement BEFORE it sends it, applied to the same
 * input when it comes from an agent (ETP-5469, agent path only).
 *
 * <p><b>Why they live here and not in {@link BankStatementsHandler}.</b> The UI never sends a
 * request that fails them — {@code ManualStatementModal} refuses a line without a date or with an
 * amount on neither/both sides, and its inputs cap the reference and description lengths — so the
 * handler's own route leans on the client and fills the gaps silently: a missing line date becomes
 * the statement date, an unparseable amount becomes 0, an over-long text is truncated, an unknown
 * contact or G/L item id is dropped. Tightening the handler would change what the SPA route accepts;
 * applying the UI's checks to the agent's input gives the agent the same guarantees the UI gives a
 * user, with a 422 naming the offending line and field instead of a silent substitution.</p>
 *
 * <p>The amount rules themselves (no negatives, not both sides, not neither) are the handler's
 * {@code BankStatementsSupport#validateLineAmounts}; they are repeated here only so the agent gets
 * the line index and a 422 rather than the handler's bare 400.</p>
 */
final class BankStatementAgentValidation {

  /** {@code FIN_BankStatement.Name} / {@code FIN_BankStatementLine.BPartnerName} length. */
  static final int MAX_NAME = 60;
  /** {@code FIN_BankStatement.FileName} / {@code Notes} length. */
  static final int MAX_TEXT = 255;
  /** {@code FIN_BankStatementLine.ReferenceNo} length (UI: statementLineReference). */
  static final int MAX_REFERENCE = 30;
  /** {@code FIN_BankStatementLine.Description} length (UI: statementLineDescription). */
  static final int MAX_DESCRIPTION = 2000;

  /**
   * Largest file {@code importStatement} accepts through an agent: 1 MiB, about 13,000 Cuaderno 43
   * records. The file travels base64-encoded inside the agent's context window, so a larger one is
   * not a realistic agent call; it is refused before it is decoded. The UI import is not limited.
   */
  static final int MAX_IMPORT_BYTES = 1024 * 1024;
  /** {@link #MAX_IMPORT_BYTES} expressed as base64 characters (4 per 3 bytes, padded). */
  static final int MAX_IMPORT_BASE64_CHARS = 4 * ((MAX_IMPORT_BYTES + 2) / 3);

  private static final String L_DATE = "date";
  private static final String L_IN = "in";
  private static final String L_OUT = "out";
  private static final String L_DESCRIPTION = "description";
  private static final String L_REFERENCE = "reference";
  private static final String L_BPARTNER_NAME = "bpartnerName";
  private static final String L_BPARTNER_ID = "bpartnerId";
  private static final String L_GLITEM_ID = "glItemId";
  private static final Set<String> LINE_KEYS = Set.of(L_DATE, L_IN, L_OUT, L_DESCRIPTION,
      L_REFERENCE, L_BPARTNER_NAME, L_BPARTNER_ID, L_GLITEM_ID);
  private static final String LINE_PREFIX = "lines[";

  private BankStatementAgentValidation() {
  }

  /**
   * Validates an action's parameters beyond what the declared contract can express.
   *
   * @param handler the handler whose {@code owns} seam resolves referenced ids
   * @param action  the (already contract-validated) action
   * @param params  its parameters
   * @return {@code null} when the call may run, otherwise a 422 naming what is wrong
   */
  static NeoResponse check(BankStatementsHandler handler, String action, JSONObject params) {
    try {
      if (BankStatementAgentActions.CREATE_STATEMENT.equals(action)) {
        return checkCreate(handler, params);
      }
      if (BankStatementAgentActions.IMPORT_STATEMENT.equals(action)) {
        return checkImportSize(params.optString(BankStatementAgentActions.P_CONTENT_BASE64, ""));
      }
      return null;
    } catch (JSONException e) {
      return refuse("Invalid action parameters: " + e.getMessage());
    }
  }

  static NeoResponse checkImportSize(String contentBase64) {
    int length = contentBase64.length();
    if (length <= MAX_IMPORT_BASE64_CHARS) {
      return null;
    }
    return refuse("contentBase64 is " + length + " characters; importStatement accepts at most "
        + MAX_IMPORT_BASE64_CHARS + " (a " + MAX_IMPORT_BYTES / 1024 + " KB file). Split the "
        + "statement into smaller files, or import it from the Etendo GO UI.");
  }

  private static NeoResponse checkCreate(BankStatementsHandler handler, JSONObject params)
      throws JSONException {
    String header = firstNonNull(
        checkLength(BankStatementAgentActions.P_NAME, params, MAX_NAME),
        checkLength(BankStatementAgentActions.P_FILE_NAME, params, MAX_TEXT),
        checkLength(BankStatementAgentActions.P_NOTES, params, MAX_TEXT),
        checkDate(BankStatementAgentActions.P_TRANSACTION_DATE, params, false),
        checkDate(BankStatementAgentActions.P_IMPORT_DATE, params, false));
    if (header != null) {
      return refuse(header);
    }
    JSONArray lines = params.getJSONArray(BankStatementAgentActions.P_LINES);
    for (int i = 0; i < lines.length(); i++) {
      String problem = checkLine(handler, lines.getJSONObject(i));
      if (problem != null) {
        return refuse(LINE_PREFIX + i + "]: " + problem);
      }
    }
    return null;
  }

  /** @return what is wrong with one line, or {@code null} when it is what the UI would send */
  static String checkLine(BankStatementsHandler handler, JSONObject line) throws JSONException {
    String unknown = unknownKeys(line);
    if (unknown != null) {
      return unknown;
    }
    String shape = firstNonNull(
        checkDate(L_DATE, line, true),
        checkLength(L_DESCRIPTION, line, MAX_DESCRIPTION),
        checkLength(L_REFERENCE, line, MAX_REFERENCE),
        checkLength(L_BPARTNER_NAME, line, MAX_NAME),
        checkLength(L_BPARTNER_ID, line, Integer.MAX_VALUE),
        checkLength(L_GLITEM_ID, line, Integer.MAX_VALUE));
    if (shape != null) {
      return shape;
    }
    String amounts = checkAmounts(line);
    if (amounts != null) {
      return amounts;
    }
    return firstNonNull(
        checkReference(handler, line, L_BPARTNER_ID, BusinessPartner.class, "contact"),
        checkReference(handler, line, L_GLITEM_ID, GLItem.class, "G/L item"));
  }

  private static String unknownKeys(JSONObject line) {
    List<String> unknown = new ArrayList<>();
    for (Iterator<?> it = line.keys(); it.hasNext();) {
      String key = String.valueOf(it.next());
      if (!LINE_KEYS.contains(key)) {
        unknown.add(key);
      }
    }
    if (unknown.isEmpty()) {
      return null;
    }
    return "unknown field(s) " + String.join(", ", unknown) + ". A line accepts only "
        + String.join(", ", List.of(L_DATE, L_IN, L_OUT, L_DESCRIPTION, L_REFERENCE,
            L_BPARTNER_NAME, L_BPARTNER_ID, L_GLITEM_ID)) + ".";
  }

  /**
   * Same rule as the UI's {@code isLineComplete} and the handler's {@code validateLineAmounts}:
   * one side above zero, the other empty or zero, none negative.
   */
  static String checkAmounts(JSONObject line) {
    BigDecimal in;
    BigDecimal out;
    try {
      in = amount(line, L_IN);
      out = amount(line, L_OUT);
    } catch (NumberFormatException e) {
      return e.getMessage();
    }
    if (in.signum() < 0 || out.signum() < 0) {
      return "amounts cannot be negative: use in for money received and out for money paid.";
    }
    if (in.signum() == 0 && out.signum() == 0) {
      return "needs an amount in either in or out.";
    }
    if (in.signum() != 0 && out.signum() != 0) {
      return "has an amount in both in and out; a line is money in OR money out.";
    }
    return null;
  }

  private static BigDecimal amount(JSONObject line, String key) {
    if (!line.has(key) || line.isNull(key)) {
      return BigDecimal.ZERO;
    }
    Object raw = line.opt(key);
    String text = raw instanceof Number || raw instanceof String ? String.valueOf(raw).trim() : null;
    if (text == null) {
      throw new NumberFormatException(key + " must be a number.");
    }
    if (text.isEmpty()) {
      return BigDecimal.ZERO;
    }
    try {
      return new BigDecimal(text);
    } catch (NumberFormatException e) {
      throw new NumberFormatException(key + " must be a number with a dot decimal separator "
          + "(got '" + text + "').");
    }
  }

  private static String checkDate(String key, JSONObject source, boolean required) {
    if (!source.has(key) || source.isNull(key)) {
      return required ? key + " is required (yyyy-MM-dd)." : null;
    }
    Object raw = source.opt(key);
    if (!(raw instanceof String)) {
      return invalidDate(key, raw);
    }
    try {
      LocalDate.parse((String) raw);
      return null;
    } catch (DateTimeParseException e) {
      return invalidDate(key, raw);
    }
  }

  private static String invalidDate(String key, Object raw) {
    return key + " must be a valid date in yyyy-MM-dd format (got '" + raw + "').";
  }

  private static String checkLength(String key, JSONObject source, int max) {
    if (!source.has(key) || source.isNull(key)) {
      return null;
    }
    Object raw = source.opt(key);
    if (!(raw instanceof String)) {
      return key + " must be a string.";
    }
    // The raw length, not the trimmed one: the handler truncates the value as sent
    // (applyEditableHeader / createLines), so surrounding blanks count toward the column.
    int length = ((String) raw).length();
    return length > max
        ? key + " is " + length + " characters; at most " + max + " are allowed." : null;
  }

  /**
   * The UI picks contacts and G/L items from a selector, so their ids always resolve; the handler
   * silently drops one that does not. An agent's id is refused instead.
   */
  private static String checkReference(BankStatementsHandler handler, JSONObject line,
      String key, Class<? extends BaseOBObject> entityClass, String label) {
    String id = line.optString(key, "").trim();
    if (id.isEmpty()) {
      return null;
    }
    OBContext.setAdminMode(true);
    try {
      return handler.owns(entityClass, id) ? null : key + " '" + id + "' is not a " + label
          + " of this company.";
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  private static String firstNonNull(String... values) {
    for (String v : values) {
      if (v != null) {
        return v;
      }
    }
    return null;
  }

  private static NeoResponse refuse(String message) {
    return NeoResponse.error(NeoActionContract.SC_UNPROCESSABLE, message);
  }
}
