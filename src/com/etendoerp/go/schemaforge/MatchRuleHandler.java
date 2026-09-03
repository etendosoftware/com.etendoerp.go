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

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
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
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.core.OBContext;

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
 * </ul>
 *
 * <p>{@code priority} is an ordering/ranking key (the functional spec presents the
 * highest-priority match as the main suggestion and ties as alternatives), so it is NOT
 * required to be unique. The "next priority" suggestion (max + 10) is computed on the
 * FRONTEND from the loaded list, so there is no backend defaults endpoint here.
 */
@Named("match-rule")
public class MatchRuleHandler extends AbstractNeoHandler {

  private static final Logger log = LogManager.getLogger(MatchRuleHandler.class);

  private static final String SPEC = "match-rule";

  private static final String F_NAME = "name";
  private static final String F_TEXT_CONDITION = "textCondition";
  private static final String F_TEXT_PATTERN = "textPattern";
  private static final String F_ACCOUNTING_CONCEPT = "accountingConcept";
  private static final String F_PRIORITY = "priority";

  private static final String METHOD_GET = "GET";
  private static final String PARAM_ACTION = "action";
  /** Read-only action exposing which accounting dimensions the rule form may offer. */
  private static final String ACTION_ACTIVE_DIMENSIONS = "activeDimensions";
  private static final String KEY_RESPONSE = "response";
  private static final String KEY_DATA = "data";
  private static final String KEY_DIMENSIONS = "dimensions";

  /**
   * Rule fields that are accounting dimensions: wire field name → dimension key. A rule may only
   * carry a dimension active for the tenant in the chart of accounts, because that is exactly
   * what the transaction Automatch generates out of the rule can hold.
   */
  private static final Map<String, String> DIMENSION_FIELDS = dimensionFields();

  private static Map<String, String> dimensionFields() {
    Map<String, String> fields = new LinkedHashMap<>();
    fields.put("project", AccountingDimensionsSupport.DIM_PROJECT);
    fields.put("costCenter", AccountingDimensionsSupport.DIM_COSTCENTER);
    fields.put("product", AccountingDimensionsSupport.DIM_PRODUCT);
    return fields;
  }

  private static final int NAME_MAX_LENGTH = 60;
  private static final int PATTERN_MAX_LENGTH = 255;

  /**
   * Priority is a whole number, 1 or greater. Rules are evaluated {@code ORDER BY priority ASC}
   * (lower value = higher precedence) and ties are allowed on purpose, so nothing technically broke
   * with a zero or a negative — but the field had NO validation at all, and
   * {@code ETGO_MATCH_RULE.PRIORITY} is {@code DECIMAL(10,0)}, so a decimal was silently truncated
   * on the way in. The upper bound is what those ten integer digits can hold.
   */
  private static final BigDecimal PRIORITY_MIN = BigDecimal.ONE;
  private static final BigDecimal PRIORITY_MAX = new BigDecimal("9999999999");

  /** Allowed values for the closed lists (mirror of the AD list references). */
  private static final Set<String> TEXT_CONDITIONS = new HashSet<>(Arrays.asList("C", "S", "R"));
  private static final String COND_REGEX = "R";

  /** Cap for compiling + test-matching a user regex, to reject catastrophic patterns. */
  private static final long REGEX_TIMEOUT_MS = 200L;
  /** Benign-but-adversarial sample used to surface super-linear backtracking. */
  private static final String REGEX_PROBE = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa!";

  @Override
  public NeoResponse handle(NeoContext context) {
    if (SPEC.equals(context.getSpecName()) && METHOD_GET.equals(context.getHttpMethod())
        && ACTION_ACTIVE_DIMENSIONS.equals(queryParam(context, PARAM_ACTION))) {
      return buildActiveDimensions();
    }
    return runWriteHook(context, SPEC, log, body -> validateWrite(context, body));
  }

  private static String queryParam(NeoContext context, String key) {
    Map<String, String> params = context.getQueryParams();
    return params != null ? params.get(key) : null;
  }

  /**
   * {@code GET ?action=activeDimensions} — the accounting dimensions active in the current
   * tenant's chart of accounts ("Ledger Configuration"), in the canonical display order. The rule
   * form renders a dimension selector only when its dimension is listed here, so a dimension
   * switched off there disappears from the rule the same way it disappears from the New Movement
   * wizard — same single source of truth for both, see {@link AccountingDimensionsSupport}.
   */
  NeoResponse buildActiveDimensions() {
    try {
      enterAdminMode();
      String clientId = OBContext.getOBContext().getCurrentClient().getId();
      Set<String> active = AccountingDimensionsSupport.flatActiveDimensionsForClient(clientId);
      JSONArray arr = new JSONArray();
      for (String key : AccountingDimensionsSupport.DIM_ORDER) {
        if (active.contains(key)) {
          arr.put(key);
        }
      }
      JSONObject data = new JSONObject();
      data.put(KEY_DIMENSIONS, arr);
      JSONObject payload = new JSONObject();
      payload.put(KEY_DATA, data);
      JSONObject envelope = new JSONObject();
      envelope.put(KEY_RESPONSE, payload);
      return NeoResponse.ok(envelope);
    } catch (Exception e) {
      log.error("{} activeDimensions error", SPEC, e);
      return NeoResponse.error(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
          "Internal Server Error");
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

    stripInactiveDimensions(body);

    // Priority is validated on its own, independently of the content gate below: it has
    // `inlineEdit` in the contract, so a PATCH can carry priority and nothing else.
    NeoResponse invalidPriority = validatePriority(body, isPatch);
    if (invalidPriority != null) {
      return invalidPriority;
    }

    // Full validation only applies when the relevant content fields are present. A PATCH
    // may carry a single field (inline toggle of `active`); fields absent from the body
    // are not validated here. Priority is NOT required to be unique — per the functional
    // spec it is an ordering/ranking key (ties simply rank as alternatives), so duplicate
    // priorities within a scope are allowed.
    if (!isPatch || hasContentFields(body)) {
      NeoResponse invalid = validateContent(body);
      if (invalid != null) {
        return invalid;
      }
    }
    return null;
  }

  /**
   * Validates {@code priority}: a whole number from {@code 1} up to what {@code DECIMAL(10,0)}
   * holds. Absent is an error on create/update and a no-op on a partial patch.
   *
   * @param body    the request body
   * @param isPatch {@code true} for a PATCH, where an absent priority simply means "unchanged"
   * @return {@code null} when valid, or the HTTP 400 to reject the write with
   */
  NeoResponse validatePriority(JSONObject body, boolean isPatch) {
    String raw = optTrimmed(body, F_PRIORITY);
    if (raw == null) {
      return isPatch ? null
          : NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, "Priority is required");
    }
    BigDecimal priority;
    try {
      priority = new BigDecimal(raw);
    } catch (NumberFormatException e) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST,
          "Priority must be a whole number");
    }
    // stripTrailingZeros so "10.00" is accepted as the integer 10 the column would have stored,
    // while "10.5" — which DECIMAL(10,0) would have silently truncated — is rejected.
    if (priority.stripTrailingZeros().scale() > 0) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST,
          "Priority must be a whole number");
    }
    if (priority.compareTo(PRIORITY_MIN) < 0) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST,
          "Priority must be 1 or greater");
    }
    if (priority.compareTo(PRIORITY_MAX) > 0) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, "Priority is too large");
    }
    return null;
  }

  /**
   * Removes any accounting-dimension field whose dimension is not active for the tenant, so a rule
   * never persists a dimension the generated movement could not carry. The value is dropped from
   * the request, NOT cleared on the record: an existing value survives an unrelated save and starts
   * applying again if the dimension is re-enabled in the Accounting Schema (ETP-4950). Dropping
   * silently rather than rejecting matters because the clone and edit flows pre-fill the form from
   * a stored row, which may still hold a now-inactive dimension.
   */
  void stripInactiveDimensions(JSONObject body) {
    if (DIMENSION_FIELDS.keySet().stream().noneMatch(body::has)) {
      return;
    }
    Set<String> active;
    try {
      String clientId = OBContext.getOBContext().getCurrentClient().getId();
      active = AccountingDimensionsSupport.flatActiveDimensionsForClient(clientId);
    } catch (Exception e) {
      // Fail open: an unreadable accounting configuration must not block saving a rule.
      log.warn("Could not resolve active accounting dimensions; keeping the body as sent", e);
      return;
    }
    for (Map.Entry<String, String> field : DIMENSION_FIELDS.entrySet()) {
      if (body.has(field.getKey()) && !active.contains(field.getValue())) {
        body.remove(field.getKey());
      }
    }
  }

  /** True when the body carries any of the content fields that require full validation. */
  private boolean hasContentFields(JSONObject body) {
    return body.has(F_NAME) || body.has(F_TEXT_CONDITION) || body.has(F_TEXT_PATTERN);
  }

  /**
   * Validates the rule content: name, text condition / pattern (incl. safe regex), accounting
   * concept and transaction type. Returns {@code null} when valid.
   */
  NeoResponse validateContent(JSONObject body) {
    String name = optTrimmed(body, F_NAME);
    String textCondition = optTrimmed(body, F_TEXT_CONDITION);
    String textPattern = optTrimmed(body, F_TEXT_PATTERN);
    String accountingConcept = optTrimmed(body, F_ACCOUNTING_CONCEPT);

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
    // The accounting concept (GL item) is mandatory: it is the concept the automatch uses to
    // create the payment/transaction when a rule matches a line with no counterpart.
    if (StringUtils.isBlank(accountingConcept)) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, "Accounting concept is required");
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

}
