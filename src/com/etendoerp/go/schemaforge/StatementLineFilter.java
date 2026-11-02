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

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.advpaymentmngt.hook.BankStatementLineFilterContext;
import org.openbravo.advpaymentmngt.hook.BankStatementLineFilterContext.Usage;
import org.openbravo.advpaymentmngt.utility.BankStatementLineFilterHelper;
import org.openbravo.dal.service.OBDal;
import org.openbravo.dal.service.OBQuery;
import org.openbravo.model.financialmgmt.payment.FIN_BankStatement;
import org.openbravo.model.financialmgmt.payment.FIN_BankStatementLine;
import org.openbravo.model.financialmgmt.payment.FIN_FinancialAccount;

/**
 * The Etendo GO side of APRM's {@link org.openbravo.advpaymentmngt.hook.BankStatementLineFilterHook}
 * (ETP-5581): which bank statement lines a module has excluded from matching and reconciliation.
 * The PSD2 module excludes the lines it marks as discarded; any other module may exclude lines too,
 * and every caller here honours all of them, because nothing in this class names a module, a column
 * or an entity — it only runs the conditions the hooks contribute.
 *
 * <p><b>Not gated by any feature flag.</b> With no hook installed, or with no excluded line, every
 * method here answers "nothing is excluded" and the callers behave exactly as before.
 *
 * <p><b>The hook's contract.</b> An excluded line must never be reconciled: a write path that finds
 * one answers the coded 409 {@code LINE_DISCARDED} ({@link StatementLineDiscard#discardedError()}).
 *
 * <p><b>Which {@link Usage} each caller passes</b>, so a module can tell the queries apart:
 * {@link Usage#AUTO_MATCH_CANDIDATES} for the auto-match candidates, the bounce detection and the
 * {@code applySuggestions} guard; {@link Usage#MATCH_STATEMENT_GRID} for the pending-lines panel,
 * the statement-lines view and the manual write guards. No new usage is needed.
 */
final class StatementLineFilter {

  private static final Logger log = LogManager.getLogger(StatementLineFilter.class);

  /** Alias of the bank statement line in every query this class builds. */
  static final String ALIAS = "bsl";

  /**
   * Named parameter of the account. Prefixed so it can never collide with a key a hook adds:
   * {@link BankStatementLineFilterHelper} rejects a repeated key with an exception.
   */
  static final String PARAM_ACCOUNT_ID = "etgoAccountId";

  /** Named parameter of a single line, prefixed for the same reason as {@link #PARAM_ACCOUNT_ID}. */
  private static final String PARAM_LINE_ID = "etgoLineId";

  /** {@code pendingLines?status=discarded}: list only the excluded lines. */
  static final String STATUS_DISCARDED = StatementLineDiscard.KEY_DISCARDED;

  /** Link of a discarded row to the pair it was discarded with ({@code null} when none). */
  static final String KEY_BOUNCE_LINK = "bounceLink";

  /** Utility class: static members only, never instantiated. */
  private StatementLineFilter() {
  }

  /**
   * The condition every hook contributes for {@code alias}, ready to append to a where clause.
   *
   * @param alias   the alias of {@code FIN_BankStatementLine} in the caller's query
   * @param account the account whose lines are queried; never {@code null}
   * @param usage   what the query is for
   * @param params  the query's named parameters; the hooks' parameters are added to it
   * @return {@code ""} when no hook restricts the lines, else {@code " and (c1) and (c2)"}
   */
  static String hql(String alias, FIN_FinancialAccount account, Usage usage,
      Map<String, Object> params) {
    return BankStatementLineFilterHelper.buildHqlCondition(alias,
        new BankStatementLineFilterContext(account, null, usage), params);
  }

  /**
   * The ids of the account's unmatched, active lines that a hook excludes for {@code usage}. One
   * query, and none at all when no hook contributes a condition. Only unmatched lines are read
   * because a hook must never exclude a reconciled line (the hook's own contract).
   *
   * @param account the account; {@code null} answers an empty set
   * @param usage   what the caller uses the answer for
   * @return the excluded line ids, never {@code null}
   */
  static Set<String> excludedLineIds(FIN_FinancialAccount account, Usage usage) {
    if (account == null) {
      return Collections.emptySet();
    }
    Map<String, Object> params = new HashMap<>();
    String condition = hql(ALIAS, account, usage, params);
    if (StringUtils.isBlank(condition)) {
      return Collections.emptySet();
    }
    params.put(PARAM_ACCOUNT_ID, account.getId());
    OBQuery<FIN_BankStatementLine> query = OBDal.getInstance().createQuery(
        FIN_BankStatementLine.class,
        "as " + ALIAS + " where " + ALIAS + "." + FIN_BankStatementLine.PROPERTY_BANKSTATEMENT + "."
            + FIN_BankStatement.PROPERTY_ACCOUNT + ".id = :" + PARAM_ACCOUNT_ID
            + " and " + ALIAS + "." + FIN_BankStatementLine.PROPERTY_FINANCIALACCOUNTTRANSACTION
            + " is null and not (1 = 1" + condition + ")");
    query.setNamedParameters(params);
    query.setSelectClause(ALIAS + ".id");
    List<String> ids = query.createQuery(String.class).list();
    return new HashSet<>(ids);
  }

  /**
   * Whether a hook excludes this one line for {@code usage}. One query when a hook contributes a
   * condition, none otherwise.
   *
   * @param line  the line; {@code null} is never excluded
   * @param usage what the caller is about to do with the line
   * @return {@code true} when the line must not be matched or reconciled
   */
  static boolean isExcluded(FIN_BankStatementLine line, Usage usage) {
    FIN_FinancialAccount account = accountOf(line);
    if (account == null) {
      return false;
    }
    Map<String, Object> params = new HashMap<>();
    String condition = hql(ALIAS, account, usage, params);
    if (StringUtils.isBlank(condition)) {
      return false;
    }
    params.put(PARAM_LINE_ID, line.getId());
    OBQuery<FIN_BankStatementLine> query = OBDal.getInstance().createQuery(
        FIN_BankStatementLine.class,
        "as " + ALIAS + " where " + ALIAS + ".id = :" + PARAM_LINE_ID
            + " and not (1 = 1" + condition + ")");
    query.setNamedParameters(params);
    // The line was already resolved by its caller; activity is not what this asks.
    query.setFilterOnActive(false);
    return query.count() > 0;
  }

  /**
   * The coded 409 {@code LINE_DISCARDED} when a hook excludes the line, {@code null} otherwise:
   * the guard every reconcile path runs next to its draft-statement check.
   *
   * @param line  the line about to be reconciled
   * @param usage what the caller is about to do with the line
   * @return the refusal, or {@code null} when the line may be reconciled
   */
  static NeoResponse discardedLineError(FIN_BankStatementLine line, Usage usage) {
    return isExcluded(line, usage) ? StatementLineDiscard.discardedError() : null;
  }

  /**
   * Sets {@code discarded} ({@code true}/{@code false}) on every row of the statement-lines view,
   * so a discarded line is still listed and visibly marked. Rows are matched by {@code id}; the
   * excluded ids are read once per financial account of the listed statements. Never fails the
   * listing: on any error every row is left unmarked and the error is logged.
   *
   * @param rows         the rows of {@code bank-statements?action=lines}
   * @param statementIds the tenant-resolved statements the rows come from
   */
  static void markDiscarded(JSONArray rows, Collection<String> statementIds) {
    try {
      Map<String, FIN_FinancialAccount> accounts = new LinkedHashMap<>();
      for (String statementId : statementIds) {
        // tenant-ok: the caller resolved every statement id through owns() before listing it
        FIN_BankStatement statement =
            OBDal.getInstance().get(FIN_BankStatement.class, statementId);
        if (statement != null && statement.getAccount() != null) {
          accounts.putIfAbsent(statement.getAccount().getId(), statement.getAccount());
        }
      }
      Set<String> excluded = new HashSet<>();
      for (FIN_FinancialAccount account : accounts.values()) {
        excluded.addAll(excludedLineIds(account, Usage.MATCH_STATEMENT_GRID));
      }
      markRows(rows, excluded);
    } catch (Exception e) {
      log.warn("Could not mark the discarded statement lines: {}", e.getMessage());
    }
  }

  /**
   * Puts {@code discarded} on every row of {@code rows}, {@code true} for the ids in
   * {@code excluded}.
   *
   * @param rows     the rows, each with an {@code id}
   * @param excluded the excluded line ids
   * @throws JSONException when a row cannot be written
   */
  static void markRows(JSONArray rows, Set<String> excluded) throws JSONException {
    for (int i = 0; i < rows.length(); i++) {
      JSONObject row = rows.optJSONObject(i);
      if (row != null) {
        row.put(StatementLineDiscard.KEY_DISCARDED,
            excluded.contains(row.optString(ReconciliationHandler.KEY_ID, "")));
      }
    }
  }

  /**
   * Splits the raw rows of {@code pendingLines} into the rows to show and the excluded ones.
   * Order is kept in both.
   *
   * @param rows     the raw rows, each with an {@code id}
   * @param excluded the excluded line ids
   * @return {@code [kept, excluded]}
   */
  static JSONArray[] splitRows(JSONArray rows, Set<String> excluded) {
    JSONArray kept = new JSONArray();
    JSONArray dropped = new JSONArray();
    for (int i = 0; i < rows.length(); i++) {
      JSONObject row = rows.optJSONObject(i);
      if (row == null) {
        continue;
      }
      if (excluded.contains(row.optString(ReconciliationHandler.KEY_ID, ""))) {
        dropped.put(row);
      } else {
        kept.put(row);
      }
    }
    return new JSONArray[] { kept, dropped };
  }

  /**
   * The {@code pendingLines?status=discarded} data: only the excluded rows, each with
   * {@code state} and {@code status} {@value #STATUS_DISCARDED}, nothing reconciled, and the pair
   * it was discarded with ({@value #KEY_BOUNCE_LINK}, {@code null} when it was discarded on its
   * own); their {@code total}; and {@code counts} carrying only {@code discarded} — the other
   * filters keep the counts of the default request, so this one does not run the matching engine.
   *
   * @param rows the excluded raw rows
   * @return the data body
   * @throws JSONException when a row cannot be written
   */
  static JSONObject discardedLinesData(JSONArray rows) throws JSONException {
    BigDecimal total = BigDecimal.ZERO;
    for (int i = 0; i < rows.length(); i++) {
      JSONObject row = rows.getJSONObject(i);
      total = total.add(ReconciliationSupport.nullSafe(
          new BigDecimal(row.optString(ReconciliationHandler.KEY_AMOUNT, "0"))));
      row.put("state", STATUS_DISCARDED);
      row.put(ReconciliationHandler.KEY_STATUS, STATUS_DISCARDED);
      row.put(StatementLineDiscard.KEY_DISCARDED, true);
      row.put("reconciledAmount", BigDecimal.ZERO);
      row.put("reconciledPct", 0);
      // tenant-ok: the id comes from PENDING_LINES_SQL, scoped by client and organization
      FIN_BankStatementLine line = OBDal.getInstance().get(FIN_BankStatementLine.class,
          row.optString(ReconciliationHandler.KEY_ID, ""));
      row.put(KEY_BOUNCE_LINK, BankBouncePairService.linkJson(line));
    }
    JSONObject counts = new JSONObject();
    counts.put(STATUS_DISCARDED, rows.length());
    JSONObject data = new JSONObject();
    data.put("lines", rows);
    data.put(ReconciliationHandler.KEY_TOTAL, total);
    data.put(ReconciliationHandler.KEY_COUNTS, counts);
    return data;
  }

  /**
   * The financial account of a line, or {@code null} when the line or its statement has none.
   *
   * @param line the line
   * @return its account
   */
  private static FIN_FinancialAccount accountOf(FIN_BankStatementLine line) {
    if (line == null || line.getBankStatement() == null) {
      return null;
    }
    return line.getBankStatement().getAccount();
  }
}
