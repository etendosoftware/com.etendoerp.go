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

  private static final int NAME_MAX_LENGTH = 60;
  private static final int PATTERN_MAX_LENGTH = 255;

  /** Allowed values for the closed lists (mirror of the AD list references). */
  private static final Set<String> TEXT_CONDITIONS = new HashSet<>(Arrays.asList("C", "S", "R"));
  private static final String COND_REGEX = "R";

  /** Cap for compiling + test-matching a user regex, to reject catastrophic patterns. */
  private static final long REGEX_TIMEOUT_MS = 200L;
  /** Benign-but-adversarial sample used to surface super-linear backtracking. */
  private static final String REGEX_PROBE = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa!";

  @Override
  public NeoResponse handle(NeoContext context) {
    return runWriteHook(context, SPEC, log, body -> validateWrite(context, body));
  }

  /**
   * Validates a create (POST) or update (PUT) / inline patch (PATCH) before generic CRUD.
   * Returns {@code null} when the request is valid (CRUD proceeds), or a {@link NeoResponse}
   * error to reject it.
   */
  NeoResponse validateWrite(NeoContext context, JSONObject body) {
    final boolean isPatch = METHOD_PATCH.equals(context.getHttpMethod());

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

  /** True when the body carries any of the content fields that require full validation. */
  private boolean hasContentFields(JSONObject body) {
    return body.has(F_NAME) || body.has(F_TEXT_CONDITION) || body.has(F_TEXT_PATTERN);
  }

  /**
   * Validates the rule content: name, text condition / pattern (incl. safe regex) and
   * transaction type. Returns {@code null} when valid.
   */
  NeoResponse validateContent(JSONObject body) {
    String name = optTrimmed(body, F_NAME);
    String textCondition = optTrimmed(body, F_TEXT_CONDITION);
    String textPattern = optTrimmed(body, F_TEXT_PATTERN);

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
