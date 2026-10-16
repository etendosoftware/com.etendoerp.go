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
 * All portions are Copyright (C) 2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.common;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;

/**
 * Parses JSON text that came from a client (a request body or a JSON-valued request parameter).
 * Every such text goes through here, never straight into {@code new JSONObject(String)}.
 *
 * <p><b>Why.</b> jettison 1.3 does not fail cleanly on all malformed input. A text that stops
 * inside an array ({@code {"x":[}) recurses until {@link StackOverflowError}; one that stops after
 * a comma inside an array ({@code {"x":[1,}) loops appending nulls until
 * {@link OutOfMemoryError}; and a valid text nested ~20,000 levels deep (40 KB) recurses once per
 * level into a {@code StackOverflowError}. Both are {@link Error}s: no {@code catch (Exception)}
 * sees them, so the client got a container 500 while the request thread burned CPU, or the whole
 * JVM ran short of heap. Several of the affected endpoints answer without a token.</p>
 *
 * <p><b>How.</b> Before jettison runs, a linear scan ({@link #isWithinLimits}) refuses any text
 * whose brackets are unbalanced or mismatched, whose string is never closed, whose nesting is
 * deeper than the limit, that carries a comment outside a string, that puts a quote inside a bare
 * token, or that goes on after its root closes. The scan reads strings and whitespace exactly where
 * jettison does, so both see the same brackets: comments are refused because jettison skips them,
 * and a quote inside a bare token because jettison does not open a string there. None of these
 * shapes is valid JSON. Only a text that passes reaches jettison, and anything it still throws is
 * reported as a {@link MalformedJsonException} whose message never echoes the text. The scan was
 * fuzzed against jettison 1.3 (character- and token-level): no text that passes it makes jettison
 * throw an {@code Error} or hang.</p>
 *
 * <p>Valid JSON is parsed exactly as {@code new JSONObject(String)} / {@code new JSONArray(String)}
 * would parse it, as long as it is nested no deeper than the limit.</p>
 */
public final class BoundedJsonParser {

  /**
   * Deepest array/object nesting accepted. The deepest legitimate payload (an MCP envelope around
   * nested advanced criteria) stays well under it; jettison's stack lasts for thousands.
   */
  public static final int DEFAULT_MAX_DEPTH = 64;

  /**
   * Characters after which jettison reads a new key or value, so a quote there opens a string.
   * {@code >} counts only as the end of {@code =>}.
   */
  private static final String TOKEN_STARTERS = "{[,:;=>";

  private BoundedJsonParser() {
  }

  /**
   * Parses {@code text} as a JSON object, nested at most {@link #DEFAULT_MAX_DEPTH} levels.
   *
   * @throws MalformedJsonException when {@code text} is null, malformed, too deep, or not an object
   */
  public static JSONObject parseObject(String text) throws MalformedJsonException {
    return parseObject(text, DEFAULT_MAX_DEPTH);
  }

  /**
   * Parses {@code text} as a JSON object, nested at most {@code maxDepth} levels.
   *
   * @throws MalformedJsonException when {@code text} is null, malformed, too deep, or not an object
   */
  public static JSONObject parseObject(String text, int maxDepth) throws MalformedJsonException {
    return parse(text, maxDepth, JSONObject::new);
  }

  /**
   * Parses {@code text} as a JSON array, nested at most {@link #DEFAULT_MAX_DEPTH} levels.
   *
   * @throws MalformedJsonException when {@code text} is null, malformed, too deep, or not an array
   */
  public static JSONArray parseArray(String text) throws MalformedJsonException {
    return parseArray(text, DEFAULT_MAX_DEPTH);
  }

  /**
   * Parses {@code text} as a JSON array, nested at most {@code maxDepth} levels.
   *
   * @throws MalformedJsonException when {@code text} is null, malformed, too deep, or not an array
   */
  public static JSONArray parseArray(String text, int maxDepth) throws MalformedJsonException {
    return parse(text, maxDepth, JSONArray::new);
  }

  /**
   * Linear, allocation-light scan of {@code text}: {@code true} when its brackets are balanced and
   * correctly paired, every string ({@code "} or {@code '}, escapes honoured) is closed, nesting
   * never exceeds {@code maxDepth}, no comment ({@code /} or {@code #}) appears outside a string,
   * no quote appears inside a bare token, and nothing but whitespace follows the closed root. It
   * does not validate the JSON otherwise; jettison does that afterwards.
   *
   * <p>The quote rule mirrors jettison: it opens a quoted string only where a token starts (after
   * a structural character or at the start), while inside a bare token a quote is an ordinary
   * character. Opening a string there would make the scan see different brackets than jettison.
   * </p>
   */
  public static boolean isWithinLimits(String text, int maxDepth) {
    if (text == null || maxDepth < 0) {
      return false;
    }
    char[] open = new char[maxDepth];
    int depth = 0;
    boolean rootClosed = false;
    // Last non-whitespace character outside a string; 0 at the start of the text.
    char previous = 0;
    int i = 0;
    while (i < text.length()) {
      char c = text.charAt(i);
      // jettison's own whitespace rule (JSONTokener#nextClean); not Character.isWhitespace, which
      // would skip characters such as U+2028 that jettison reads as the start of a bare token.
      if (c <= ' ') {
        i++;
        continue;
      }
      if (rootClosed || c == '/' || c == '#') {
        return false;
      }
      if (c == '"' || c == '\'') {
        if (!startsToken(previous)) {
          return false;
        }
        i = endOfString(text, i);
        if (i == text.length()) {
          return false;
        }
      } else if (c == '[' || c == '{') {
        if (depth == maxDepth) {
          return false;
        }
        open[depth++] = c;
      } else if (c == ']' || c == '}') {
        if (depth == 0 || open[--depth] != (c == ']' ? '[' : '{')) {
          return false;
        }
        rootClosed = depth == 0;
      }
      // "=>" separates a key from its value only as one unit; a lone '>' is part of a bare token.
      previous = c == '>' && (i == 0 || text.charAt(i - 1) != '=') ? 'x' : c;
      i++;
    }
    return depth == 0;
  }

  /** Whether jettison starts a new token after {@code previous} (0: the start of the text). */
  private static boolean startsToken(char previous) {
    return previous == 0 || TOKEN_STARTERS.indexOf(previous) >= 0;
  }

  /** jettison's {@code JSONObject(String)} / {@code JSONArray(String)} constructor. */
  @FunctionalInterface
  private interface JettisonConstructor<T> {
    T parse(String text) throws JSONException;
  }

  private static <T> T parse(String text, int maxDepth, JettisonConstructor<T> constructor)
      throws MalformedJsonException {
    if (!isWithinLimits(text, maxDepth)) {
      throw new MalformedJsonException("Malformed JSON: unbalanced, unterminated, commented"
          + " or nested deeper than " + maxDepth + " levels");
    }
    try {
      return constructor.parse(text);
    } catch (JSONException e) {
      throw new MalformedJsonException(withoutSource(e.getMessage()));
    } catch (RuntimeException | StackOverflowError e) { // NOSONAR — see the class javadoc.
      // Not expected once the scan passed (fuzzed), kept so a parser surprise is still a 400.
      throw new MalformedJsonException("Malformed JSON");
    }
  }

  /**
   * jettison's syntax errors end in {@code " at character N of <the whole text>"}; the text is the
   * client's body, which an error response must not echo back (it can be large, or hold a
   * password). Keeps only the description.
   */
  static String withoutSource(String message) {
    if (message == null) {
      return "Malformed JSON";
    }
    int at = message.indexOf(" at character ");
    return at >= 0 ? message.substring(0, at) : message;
  }

  /**
   * @return the index of the quote closing the string opened at {@code open} with the same quote
   *         character (escapes honoured), or {@code text.length()} when it is never closed
   */
  private static int endOfString(String text, int open) {
    char quote = text.charAt(open);
    int i = open + 1;
    while (i < text.length()) {
      char c = text.charAt(i);
      if (c == quote) {
        return i;
      }
      // A backslash escapes the next character, so it is skipped too.
      i += c == '\\' ? 2 : 1;
    }
    return text.length();
  }
}
