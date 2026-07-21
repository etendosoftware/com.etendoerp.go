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

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * Small, self-contained, server-side evaluator for precondition {@code requiredWhen}
 * expressions. This is intentionally NOT the browser-side
 * {@code org.openbravo.client.application.DynamicExpressionParser} (which emits
 * JavaScript for client-side display logic); this evaluator resolves the boolean
 * value of an expression directly against a record on the server.
 *
 * <p>Supported grammar (precedence low → high):</p>
 * <pre>
 *   expr     := or
 *   or       := and ( '||' and )*
 *   and      := cmp ( ('&amp;&amp;' | '&amp;') cmp )*
 *   cmp      := operand ( ('==' | '!=') operand )?
 *   operand  := '@' prop '@' | "'" literal "'" | '"' literal '"' | bareToken
 * </pre>
 *
 * <p>Field references {@code @prop@} are resolved through the supplied resolver
 * (record DAL property name → String value, or {@code null} when absent).
 * Comparisons are string-based. A {@code null} field value is never equal to a
 * (non-null) literal. A comparison term with no operator is evaluated as a
 * truthiness check (non-empty and not {@code "false"}/{@code "N"}).</p>
 */
final class PreconditionConditionEvaluator {

  private PreconditionConditionEvaluator() {
  }

  /**
   * Evaluates {@code expression} against the values provided by {@code resolver}.
   * A blank or {@code null} expression evaluates to {@code true} (unconditional).
   *
   * @param expression the {@code requiredWhen} condition
   * @param resolver   resolves a {@code @prop@} name to its String value (may return null)
   * @return the boolean value of the expression
   */
  static boolean evaluate(String expression, Function<String, String> resolver) {
    if (expression == null) {
      return true;
    }
    String expr = expression.trim();
    if (expr.isEmpty()) {
      return true;
    }
    return evaluateOr(expr, resolver);
  }

  private static boolean evaluateOr(String expr, Function<String, String> resolver) {
    for (String part : splitTopLevel(expr, "||")) {
      if (evaluateAnd(part, resolver)) {
        return true;
      }
    }
    return false;
  }

  private static boolean evaluateAnd(String expr, Function<String, String> resolver) {
    for (String part : splitTopLevel(expr, "&&", "&")) {
      if (!evaluateComparison(part, resolver)) {
        return false;
      }
    }
    return true;
  }

  private static boolean evaluateComparison(String part, Function<String, String> resolver) {
    String p = part.trim();
    if (p.isEmpty()) {
      // Neutral term (e.g. a trailing operator artifact) — do not fail the AND chain.
      return true;
    }
    Operator op = findOperator(p);
    if (op == null) {
      String value = resolveOperand(p, resolver);
      return isTruthy(value);
    }
    String left = resolveOperand(p.substring(0, op.index), resolver);
    String right = resolveOperand(p.substring(op.index + op.token.length()), resolver);
    boolean equal = valuesEqual(left, right);
    return "==".equals(op.token) ? equal : !equal;
  }

  private static boolean isTruthy(String value) {
    return value != null && !value.trim().isEmpty()
        && !"false".equalsIgnoreCase(value.trim())
        && !"N".equalsIgnoreCase(value.trim());
  }

  private static boolean valuesEqual(String left, String right) {
    String l = left == null ? null : left.trim();
    String r = right == null ? null : right.trim();
    return Objects.equals(l, r);
  }

  private static String resolveOperand(String token, Function<String, String> resolver) {
    String t = token.trim();
    if (t.length() >= 2
        && ((t.charAt(0) == '\'' && t.charAt(t.length() - 1) == '\'')
            || (t.charAt(0) == '"' && t.charAt(t.length() - 1) == '"'))) {
      return t.substring(1, t.length() - 1);
    }
    if (t.length() >= 2 && t.charAt(0) == '@' && t.charAt(t.length() - 1) == '@') {
      String prop = t.substring(1, t.length() - 1);
      return resolver == null ? null : resolver.apply(prop);
    }
    return t;
  }

  /**
   * Finds the first top-level {@code ==} or {@code !=} operator outside quoted regions.
   */
  private static Operator findOperator(String expr) {
    char quote = 0;
    for (int i = 0; i < expr.length() - 1; i++) {
      char c = expr.charAt(i);
      if (quote != 0) {
        if (c == quote) {
          quote = 0;
        }
        continue;
      }
      if (c == '\'' || c == '"') {
        quote = c;
        continue;
      }
      char next = expr.charAt(i + 1);
      if ((c == '=' || c == '!') && next == '=') {
        return new Operator(c == '=' ? "==" : "!=", i);
      }
    }
    return null;
  }

  /**
   * Splits {@code expr} on any of the given delimiter strings that appear outside
   * quoted regions. When multiple delimiters match at a position, the longest wins
   * (so {@code &&} is preferred over {@code &}).
   */
  private static List<String> splitTopLevel(String expr, String... delimiters) {
    List<String> parts = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    char quote = 0;
    int i = 0;
    while (i < expr.length()) {
      char c = expr.charAt(i);
      if (quote != 0) {
        current.append(c);
        if (c == quote) {
          quote = 0;
        }
        i++;
        continue;
      }
      if (c == '\'' || c == '"') {
        quote = c;
        current.append(c);
        i++;
        continue;
      }
      String matched = longestMatch(expr, i, delimiters);
      if (matched != null) {
        parts.add(current.toString());
        current.setLength(0);
        i += matched.length();
      } else {
        current.append(c);
        i++;
      }
    }
    parts.add(current.toString());
    return parts;
  }

  private static String longestMatch(String expr, int index, String... delimiters) {
    String matched = null;
    for (String d : delimiters) {
      if (expr.regionMatches(index, d, 0, d.length())
          && (matched == null || d.length() > matched.length())) {
        matched = d;
      }
    }
    return matched;
  }

  private static final class Operator {
    private final String token;
    private final int index;

    private Operator(String token, int index) {
      this.token = token;
      this.index = index;
    }
  }
}
