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

import java.util.List;

import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.ModelProvider;
import org.openbravo.base.model.Property;
import org.openbravo.erpCommon.utility.OBMessageUtils;
import org.openbravo.model.financialmgmt.payment.FIN_BankStatementLine;

/**
 * The logical discard of a bank statement line as the PSD2 module defines it (ETP-5581), read and
 * written from Etendo GO, plus the coded refusals of the reconciliation paths around it.
 *
 * <p><b>Coupled to the PSD2 module on purpose.</b> PSD2 owns the discard: the flag
 * {@code EM_PSD2_Is_Discarded} (Y/N, NOT NULL) and the button {@code EM_PSD2_Discard_Line}, whose
 * value always mirrors the flag ({@code N} = the button reads "Discard", {@code Y} = "Restore") so
 * Classic labels it correctly. PSD2's own action, {@code DiscardBankStatementLine.setDiscarded},
 * writes both together; {@link #setDiscarded} repeats exactly that, because PSD2 exposes no public
 * service for it and its method is package-private. If PSD2 changes how the two columns relate,
 * this class must change with it.
 *
 * <p><b>Read by column name, not by generated getter.</b> The properties are resolved through
 * {@link ModelProvider} from their column names, so this module compiles against a PSD2 build that
 * does not carry the discard columns yet, and the discard simply reports itself unsupported
 * ({@link #isSupported()}) at runtime instead of failing the build. The same holds for the four
 * {@code EM_ETGO_Bounce_*} columns of this module until the DAL is regenerated.
 *
 * <p><b>The primitives are not gated; the decisions are.</b> Reading and writing the columns works
 * whatever {@code bank-bounce-suggestions} says, because unconditional code needs it too: a
 * statement edit keeps the bank's transaction id and the PSD2 flag of a recreated line
 * ({@code BankStatementLineSet}), and the stored {@code EM_ETGO_DISCARDED_COUNT} is maintained
 * ({@code BankStatementAggregates}). What a discarded line MEANS in Etendo is decided behind the
 * flag: its exclusion and the {@code LINE_DISCARDED} refusal ({@link StatementLineFilter}), the
 * pair actions ({@code BankBouncePairService}) and the statements list
 * ({@code BankStatementsHandler.loadStatements}). With the flag off a discarded line is a plain
 * pending line.
 */
final class StatementLineDiscard {

  /** Column of the PSD2 discard flag. */
  static final String COL_IS_DISCARDED = "EM_PSD2_Is_Discarded";
  /** Column of the PSD2 Discard / Restore button, kept equal to the flag ({@code Y}/{@code N}). */
  static final String COL_DISCARD_LINE = "EM_PSD2_Discard_Line";
  /** Column of the bank's transaction id, set on every line the PSD2 synchronization imports. */
  static final String COL_TRANSACTION_IDENTIFIER = "EM_PSD2_Transaction_Identifier";

  /** Value of the PSD2 button when the line is discarded (the button then reads "Restore"). */
  static final String BUTTON_DISCARDED = "Y";
  /** Value of the PSD2 button when the line is not discarded (the button reads "Discard"). */
  static final String BUTTON_NOT_DISCARDED = "N";

  /** Row marker of the statement-lines view and state of the pending panel. */
  static final String KEY_DISCARDED = "discarded";

  /** Code of the 409 every reconcile path answers for an excluded line. */
  static final String CODE_LINE_DISCARDED = "LINE_DISCARDED";
  /** {@code AD_MESSAGE} of {@link #CODE_LINE_DISCARDED}. */
  static final String MSG_LINE_DISCARDED = "ETGO_StatementLineDiscarded";

  private static final String KEY_CODE = "code";
  private static final String KEY_MESSAGE = "message";
  private static final String KEY_ERROR = "error";
  private static final String KEY_STATUS = "status";

  /** Utility class: static members only, never instantiated. */
  private StatementLineDiscard() {
  }

  /**
   * Whether the PSD2 discard columns exist in the running model: without them nothing can be
   * discarded or restored, and the actions refuse instead of failing.
   *
   * @return {@code true} when both the flag and the button are mapped
   */
  static boolean isSupported() {
    return property(COL_IS_DISCARDED) != null && property(COL_DISCARD_LINE) != null;
  }

  /**
   * Whether the PSD2 flag marks the line as discarded. {@code false} when the column does not
   * exist.
   *
   * @param line the line
   * @return the value of the flag
   */
  static boolean isDiscarded(FIN_BankStatementLine line) {
    return Boolean.TRUE.equals(get(line, COL_IS_DISCARDED));
  }

  /**
   * Discards or restores the line exactly as PSD2's own action does: the flag and the button are
   * written together, so the Classic button label always matches the state. Not saved.
   *
   * @param line      the line
   * @param discarded the new state
   */
  static void setDiscarded(FIN_BankStatementLine line, boolean discarded) {
    set(line, COL_IS_DISCARDED, discarded);
    set(line, COL_DISCARD_LINE, discarded ? BUTTON_DISCARDED : BUTTON_NOT_DISCARDED);
  }

  /**
   * The bank's transaction id of the line, or {@code null} when it has none or the column does not
   * exist. A line without it was not imported by the PSD2 synchronization and can never be
   * discarded.
   *
   * @param line the line
   * @return the trimmed id, or {@code null}
   */
  static String transactionIdentifier(FIN_BankStatementLine line) {
    Object value = get(line, COL_TRANSACTION_IDENTIFIER);
    return value == null ? null : StringUtils.trimToNull(value.toString());
  }

  /**
   * The DAL property mapped to a column of {@code FIN_BankStatementLine}, or {@code null} when the
   * running model has no such column.
   *
   * @param columnName the database column name, case-insensitive
   * @return the property, or {@code null}
   */
  static Property property(String columnName) {
    Entity entity = ModelProvider.getInstance().getEntity(FIN_BankStatementLine.ENTITY_NAME);
    return entity == null ? null : entity.getPropertyByColumnName(columnName, false);
  }

  /**
   * The HQL name of a column of {@code FIN_BankStatementLine}, for queries; {@code null} when the
   * running model has no such column.
   *
   * @param columnName the database column name, case-insensitive
   * @return the property name, or {@code null}
   */
  static String propertyName(String columnName) {
    Property property = property(columnName);
    return property == null ? null : property.getName();
  }

  /**
   * Reads a column of the line through its DAL property.
   *
   * @param line       the line
   * @param columnName the database column name
   * @return the value, or {@code null} when the line is {@code null} or the column does not exist
   */
  static Object get(FIN_BankStatementLine line, String columnName) {
    Property property = property(columnName);
    return line == null || property == null ? null : line.get(property.getName());
  }

  /**
   * Writes a column of the line through its DAL property. Not saved.
   *
   * @param line       the line
   * @param columnName the database column name
   * @param value      the new value
   * @throws IllegalStateException when the running model has no such column
   */
  static void set(FIN_BankStatementLine line, String columnName, Object value) {
    Property property = property(columnName);
    if (property == null) {
      throw new IllegalStateException("FIN_BankStatementLine has no column " + columnName
          + " in the running model; regenerate the DAL after installing the module that owns it");
    }
    line.set(property.getName(), value);
  }

  /**
   * The 409 {@code LINE_DISCARDED}: the line is excluded from reconciliation (discarded by PSD2, or
   * excluded by another module's hook) and must be restored first. Only answered while
   * {@code bank-bounce-suggestions} is on ({@link StatementLineFilter#discardedLineError}).
   *
   * @return the refusal
   */
  static NeoResponse discardedError() {
    return coded(HttpServletResponse.SC_CONFLICT, CODE_LINE_DISCARDED, MSG_LINE_DISCARDED);
  }

  /**
   * A coded refusal: {@code {code, message, error:{message, status, code, messageKeys:[key]}}}.
   * The short {@code code} is what clients branch on (the {@code GL_ITEM_REQUIRED} precedent of the
   * reconciliation API); {@code error.messageKeys} carries the {@code AD_MESSAGE} key the MCP lifts
   * and the web client translates; the message is the {@code AD_MESSAGE} text in the session
   * language, falling back to the key when the dictionary does not know it.
   *
   * @param status     the HTTP status
   * @param code       the short code
   * @param messageKey the {@code AD_MESSAGE} value
   * @return the refusal
   */
  static NeoResponse coded(int status, String code, String messageKey) {
    String message = OBMessageUtils.messageBD(messageKey);
    if (StringUtils.isBlank(message)) {
      message = messageKey;
    }
    try {
      JSONObject error = new JSONObject();
      error.put(KEY_MESSAGE, message);
      error.put(KEY_STATUS, status);
      error.put(KEY_CODE, code);
      error.put(NeoProcessService.MESSAGE_KEYS, new JSONArray(List.of(messageKey)));
      JSONObject body = new JSONObject();
      body.put(KEY_ERROR, error);
      body.put(KEY_CODE, code);
      body.put(KEY_MESSAGE, message);
      return NeoResponse.error(status, body);
    } catch (JSONException e) {
      return NeoResponse.error(status, message);
    }
  }
}
