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

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import javax.inject.Named;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.criterion.Restrictions;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.financialmgmt.payment.FIN_FinancialAccount;

import com.etendoerp.go.schemaforge.data.MatchRule;

/**
 * Validation pre-hook for the Bank Reconciliation <b>matching rules</b> catalog (T5).
 *
 * <p>The window is served by <b>generic NEO Headless W CRUD</b> — persistence (list,
 * create, update, inline patch, delete) is handled by {@link NeoCrudHandler} from the
 * AD tab / {@code ETGO_SF_FIELD} configuration. This handler is registered as a thin
 * pre-hook via {@code @Named("match-rule")} (matching {@code ETGO_SF_ENTITY.Java_Qualifier})
 * and runs <b>before</b> the generic CRUD: {@link #handle(NeoContext)} either returns
 * {@code null} to let the default CRUD proceed, or a {@link NeoResponse} error to reject
 * the write.
 *
 * <p>Business rules enforced (write methods only):
 * <ul>
 *   <li>{@code textCondition} must be one of Contains (C), Starts with (S), Regex (R) — HTTP 400</li>
 *   <li>{@code textPattern} is required — HTTP 400</li>
 *   <li>when the condition is Regex (R), the pattern is compiled and test-matched under a
 *       {@value #REGEX_TIMEOUT_MS} ms cap to reject catastrophic backtracking — HTTP 400</li>
 *   <li>{@code priority} must be unique within scope (same {@code FIN_Financial_Account_ID},
 *       or "all accounts" when none is set) — HTTP 409</li>
 * </ul>
 *
 * <p>The "next priority" suggestion (max + 10) is computed on the FRONTEND from the loaded
 * list, so there is no backend defaults endpoint here.
 */
@Named("match-rule")
public class MatchRuleHandler implements NeoHandler {

  private static final Logger log = LogManager.getLogger(MatchRuleHandler.class);

  private static final String SPEC = "match-rule";
  private static final String METHOD_POST = "POST";
  private static final String METHOD_PUT = "PUT";
  private static final String METHOD_PATCH = "PATCH";

  private static final String F_NAME = "name";
  private static final String F_PRIORITY = "priority";
  private static final String F_TEXT_CONDITION = "textCondition";
  private static final String F_TEXT_PATTERN = "textPattern";
  private static final String F_TRANSACTION_TYPE = "transactionType";
  private static final String F_FINANCIAL_ACCOUNT = "financialAccount";

  private static final int NAME_MAX_LENGTH = 60;
  private static final int PATTERN_MAX_LENGTH = 255;

  /** Allowed values for the closed lists (mirror of the AD list references). */
  private static final Set<String> TEXT_CONDITIONS = new HashSet<>(Arrays.asList("C", "S", "R"));
  private static final Set<String> TRANSACTION_TYPES = new HashSet<>(Arrays.asList("B", "T", "H"));
  private static final String COND_REGEX = "R";

  /** Cap for compiling + test-matching a user regex, to reject catastrophic patterns. */
  private static final long REGEX_TIMEOUT_MS = 200L;
  /** Benign-but-adversarial sample used to surface super-linear backtracking. */
  private static final String REGEX_PROBE = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa!";

  @Override
  public NeoResponse handle(NeoContext context) {
    if (!SPEC.equals(context.getSpecName())) {
      return null;
    }
    if (!isWriteMethod(context.getHttpMethod())) {
      // GET / DELETE flow straight through to generic CRUD — nothing to validate.
      return null;
    }
    JSONObject body = context.getRequestBody();
    if (body == null) {
      // Let the generic CRUD produce the canonical "missing body" error.
      return null;
    }

    try {
      enterAdminMode();
      return validateWrite(context, body);
    } catch (Exception e) {
      log.error("match-rule validation hook error", e);
      return NeoResponse.error(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Internal Server Error");
    } finally {
      exitAdminMode();
    }
  }

  /**
   * Validates a create (POST) or update (PUT) / inline patch (PATCH) before generic CRUD.
   * Returns {@code null} when the request is valid (CRUD proceeds), or a {@link NeoResponse}
   * error to reject it.
   */
  NeoResponse validateWrite(NeoContext context, JSONObject body) {
    final boolean isPatch = METHOD_PATCH.equals(context.getHttpMethod());
    final String recordId = context.getRecordId();

    // Full validation only applies when the relevant fields are present. A PATCH may carry
    // a single field (inline toggle of `active`, inline edit of `priority`); fields absent
    // from the body are not validated here.
    if (!isPatch || hasContentFields(body)) {
      NeoResponse invalid = validateContent(body);
      if (invalid != null) {
        return invalid;
      }
    }

    if (body.has(F_PRIORITY)) {
      NeoResponse conflict = validatePriorityScope(body, recordId);
      if (conflict != null) {
        return conflict;
      }
    }
    return null;
  }

  /** True when the body carries any of the content fields that require full validation. */
  private boolean hasContentFields(JSONObject body) {
    return body.has(F_NAME) || body.has(F_TEXT_CONDITION) || body.has(F_TEXT_PATTERN)
        || body.has(F_TRANSACTION_TYPE);
  }

  /**
   * Validates the rule content: name, text condition / pattern (incl. safe regex) and
   * transaction type. Returns {@code null} when valid.
   */
  NeoResponse validateContent(JSONObject body) {
    String name = optTrimmed(body, F_NAME);
    String textCondition = optTrimmed(body, F_TEXT_CONDITION);
    String textPattern = optTrimmed(body, F_TEXT_PATTERN);
    String transactionType = optTrimmed(body, F_TRANSACTION_TYPE);

    if (StringUtils.isBlank(name)) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, "Name is required");
    }
    if (name.length() > NAME_MAX_LENGTH) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, "Name is too long");
    }
    if (!TEXT_CONDITIONS.contains(textCondition)) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST,
          "Text condition must be Contains (C), Starts with (S) or Regex (R)");
    }
    if (StringUtils.isBlank(textPattern)) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, "Pattern is required");
    }
    if (textPattern.length() > PATTERN_MAX_LENGTH) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, "Pattern is too long");
    }
    if (transactionType != null && !TRANSACTION_TYPES.contains(transactionType)) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, "Invalid transaction type");
    }
    if (COND_REGEX.equals(textCondition)) {
      String regexError = validateRegex(textPattern);
      if (regexError != null) {
        return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, regexError);
      }
    }
    return null;
  }

  /**
   * Enforces priority uniqueness within scope. On update / patch the scope account comes
   * from the body when supplied, otherwise from the persisted rule. Returns {@code null}
   * when the priority is free, or an HTTP 409 when it collides.
   */
  NeoResponse validatePriorityScope(JSONObject body, String recordId) {
    long priority = body.optLong(F_PRIORITY);
    FIN_FinancialAccount account = resolveScopeAccount(body, recordId);
    if (priorityExists(priority, account, recordId)) {
      return NeoResponse.error(HttpServletResponse.SC_CONFLICT,
          "A rule with this priority already exists for the selected scope");
    }
    return null;
  }

  /**
   * Determines the financial-account scope for the priority check. Prefers the value in
   * the body; on a partial PATCH that omits it, falls back to the persisted rule's account.
   */
  FIN_FinancialAccount resolveScopeAccount(JSONObject body, String recordId) {
    if (body.has(F_FINANCIAL_ACCOUNT)) {
      String accountId = optTrimmed(body, F_FINANCIAL_ACCOUNT);
      return accountId == null ? null : OBDal.getInstance().get(FIN_FinancialAccount.class, accountId);
    }
    if (StringUtils.isNotBlank(recordId)) {
      MatchRule rule = OBDal.getInstance().get(MatchRule.class, recordId);
      if (rule != null) {
        return rule.getFinancialAccount();
      }
    }
    return null;
  }

  /**
   * Compiles and test-matches {@code pattern} under a {@value #REGEX_TIMEOUT_MS} ms cap.
   * Returns {@code null} when the regex is safe, or a human error message when it fails to
   * compile or exhibits catastrophic backtracking (so the caller can reject it with HTTP 400).
   */
  String validateRegex(String pattern) {
    ExecutorService executor = Executors.newSingleThreadExecutor();
    Future<Boolean> future = executor.submit((Callable<Boolean>) () -> {
      Pattern.compile(pattern).matcher(REGEX_PROBE).find();
      return Boolean.TRUE;
    });
    try {
      future.get(REGEX_TIMEOUT_MS, TimeUnit.MILLISECONDS);
      return null;
    } catch (TimeoutException e) {
      future.cancel(true);
      return "The regular expression is too complex (possible catastrophic backtracking)";
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return "Invalid regular expression";
    } catch (Exception e) {
      Throwable cause = e.getCause() != null ? e.getCause() : e;
      if (cause instanceof PatternSyntaxException) {
        return "Invalid regular expression: " + cause.getMessage();
      }
      return "Invalid regular expression";
    } finally {
      executor.shutdownNow();
    }
  }

  /**
   * True when another rule already uses {@code priority} within the same scope
   * (the same financial account, or "all accounts" when {@code account} is null).
   */
  boolean priorityExists(long priority, FIN_FinancialAccount account, String excludeId) {
    OBCriteria<MatchRule> criteria = OBDal.getInstance().createCriteria(MatchRule.class);
    criteria.add(Restrictions.eq(MatchRule.PROPERTY_PRIORITY, priority));
    criteria.add(account == null
        ? Restrictions.isNull(MatchRule.PROPERTY_FINANCIALACCOUNT)
        : Restrictions.eq(MatchRule.PROPERTY_FINANCIALACCOUNT, account));
    if (StringUtils.isNotBlank(excludeId)) {
      criteria.add(Restrictions.ne(MatchRule.PROPERTY_ID, excludeId));
    }
    criteria.setMaxResults(1);
    return !criteria.list().isEmpty();
  }

  private boolean isWriteMethod(String method) {
    return METHOD_POST.equals(method) || METHOD_PUT.equals(method) || METHOD_PATCH.equals(method);
  }

  /**
   * Reads a trimmed string field, treating absent, JSON-null and blank as {@code null}.
   * Jettison's {@code optString} returns the literal {@code "null"} for a JSON null value,
   * which would otherwise leak into validation (e.g. an empty optional transactionType on
   * edit becoming an "invalid" value) — this guard prevents that.
   */
  private static String optTrimmed(JSONObject body, String key) {
    if (!body.has(key) || body.isNull(key)) {
      return null;
    }
    return StringUtils.trimToNull(body.optString(key, ""));
  }

  void enterAdminMode() {
    OBContext.setAdminMode(true);
  }

  void exitAdminMode() {
    OBContext.restorePreviousMode();
  }
}
