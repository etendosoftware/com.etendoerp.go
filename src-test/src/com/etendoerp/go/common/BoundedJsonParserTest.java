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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.stream.Stream;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit specs for {@link BoundedJsonParser}: the one place untrusted JSON text is handed to jettison.
 *
 * <p>jettison 1.3 answers some inputs with an {@link Error} instead of a {@link JSONException}: a
 * body that stops inside an array recurses forever ({@code StackOverflowError}), one that stops
 * after a comma inside an array loops adding nulls ({@code OutOfMemoryError}), and deep nesting
 * recurses once per level. None of them reaches a {@code catch (Exception)}. Every case below must
 * come back as a {@link MalformedJsonException}, without jettison ever seeing the text.</p>
 *
 * @covers com.etendoerp.go.common.BoundedJsonParser
 * @covers com.etendoerp.go.common.MalformedJsonException
 */
@SuppressWarnings("java:S2187") // test methods live in the @Nested classes
public class BoundedJsonParserTest {

  /**
   * Bodies that break jettison 1.3 itself. Shared with the endpoint tests, so every entry point is
   * proven against the same inputs.
   */
  public static Stream<String> stackBreakingBodies() {
    return Stream.of("{\"x\":[", "{\"x\":[1,", "[", "[[",
        "{\"x\":" + "[".repeat(20_000) + "]".repeat(20_000) + "}");
  }

  @Nested
  @DisplayName("bodies that break the parser")
  class ParserBreaking {

    @ParameterizedTest
    @MethodSource("com.etendoerp.go.common.BoundedJsonParserTest#stackBreakingBodies")
    void anObjectBodyThatBreaksTheParserIsMalformed(String body) {
      assertThrows(MalformedJsonException.class, () -> BoundedJsonParser.parseObject(body));
    }

    @ParameterizedTest
    @MethodSource("com.etendoerp.go.common.BoundedJsonParserTest#stackBreakingBodies")
    void anArrayBodyThatBreaksTheParserIsMalformed(String body) {
      assertThrows(MalformedJsonException.class, () -> BoundedJsonParser.parseArray(body));
    }

    @Test
    void aMalformedBodyIsStillAJsonException() {
      // So every caller that already answers a JSONException with a 400 keeps doing so.
      assertThrows(JSONException.class, () -> BoundedJsonParser.parseObject("{\"x\":["));
    }

    /** A comment hides brackets from the scan while jettison skips it: rejected outright. */
    @ParameterizedTest
    @ValueSource(strings = { "/**", "[#,,>;/]* =,", "{\"a\":1/*x*/}", "{\"a\":1}#" })
    void aCommentOutsideAStringIsMalformed(String body) {
      assertThrows(MalformedJsonException.class, () -> BoundedJsonParser.parseObject(body));
    }

    @ParameterizedTest
    @ValueSource(strings = { "{\"a\":[}", "{\"a\":{]}", "]", "{}}", "{\"a\":\"open}",
        "{'a':'open}" })
    void unbalancedOrUnterminatedInputIsMalformed(String body) {
      assertThrows(MalformedJsonException.class, () -> BoundedJsonParser.parseObject(body));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = { "   ", "not json", "[1,2]" })
    void inputThatIsNotAnObjectIsMalformed(String body) {
      assertThrows(MalformedJsonException.class, () -> BoundedJsonParser.parseObject(body));
    }

    /**
     * jettison opens a quoted string only where a token starts; inside a bare token a quote is an
     * ordinary character. A scanner that opened a string there would see different brackets than
     * jettison — here it would hide {@code ,[1,x} and let jettison end on a trailing comma (OOM).
     */
    @ParameterizedTest
    @ValueSource(strings = { "[a',[1,x'],", "{\"a\":b'c'}", "[1\"2\"]", "{\"a\":\"b\"\"c\"}",
        "[\u2028',[1,x']]", "[a>'x']", "[a= >'x']", ">'x'" })
    void aQuoteInsideABareTokenIsMalformed(String body) {
      assertThrows(MalformedJsonException.class, () -> BoundedJsonParser.parseArray(body));
      assertThrows(MalformedJsonException.class, () -> BoundedJsonParser.parseObject(body));
      assertFalse(BoundedJsonParser.isWithinLimits(body, 8));
    }

    @ParameterizedTest
    @ValueSource(strings = { "{\"a\":1}x", "[1],", "{} {}", "[1]]" })
    void contentAfterTheRootClosesIsMalformed(String body) {
      assertFalse(BoundedJsonParser.isWithinLimits(body, 8));
    }

    @Test
    void aMalformedBodyIsNotEchoedInTheMessage() {
      MalformedJsonException e = assertThrows(MalformedJsonException.class,
          () -> BoundedJsonParser.parseObject("{\"password\":\"hunter2\":1}"));
      assertFalse(e.getMessage().contains("hunter2"), e.getMessage());
      assertFalse(e.getMessage().contains(" at character"), e.getMessage());
    }

    @Test
    void inputThatIsNotAnArrayIsMalformed() {
      assertThrows(MalformedJsonException.class, () -> BoundedJsonParser.parseArray("{}"));
    }
  }

  @Nested
  @DisplayName("depth limit")
  class DepthLimit {

    @Test
    void nestingExactlyAtTheLimitParses() throws Exception {
      int depth = BoundedJsonParser.DEFAULT_MAX_DEPTH;
      // The outer object is level 1, so depth - 1 arrays sit inside it.
      String body = "{\"x\":" + "[".repeat(depth - 1) + "]".repeat(depth - 1) + "}";
      assertEquals(1, BoundedJsonParser.parseObject(body).length());
    }

    @Test
    void nestingOneLevelPastTheLimitIsMalformed() {
      int depth = BoundedJsonParser.DEFAULT_MAX_DEPTH;
      String body = "{\"x\":" + "[".repeat(depth) + "]".repeat(depth) + "}";
      assertThrows(MalformedJsonException.class, () -> BoundedJsonParser.parseObject(body));
    }

    @Test
    void aNegativeLimitAcceptsNothing() {
      assertFalse(BoundedJsonParser.isWithinLimits("{}", -1));
    }

    @Test
    void anExplicitLimitIsHonoured() throws Exception {
      assertEquals(1, BoundedJsonParser.parseObject("{\"a\":{\"b\":1}}", 2).length());
      assertThrows(MalformedJsonException.class,
          () -> BoundedJsonParser.parseObject("{\"a\":{\"b\":[1]}}", 2));
    }

    @Test
    void bracketsInsideStringsAreNotCounted() throws Exception {
      String deepText = "[".repeat(20_000) + "{".repeat(20_000);
      JSONObject parsed = BoundedJsonParser.parseObject(
          new JSONObject().put("text", deepText).toString());
      assertEquals(deepText, parsed.getString("text"));
    }

    @Test
    void anEscapedQuoteDoesNotCloseTheString() throws Exception {
      JSONObject parsed = BoundedJsonParser.parseObject("{\"a\":\"say \\\"[[\\\" ]\"}");
      assertEquals("say \"[[\" ]", parsed.getString("a"));
    }

    @Test
    void anEscapedBackslashBeforeTheClosingQuoteEndsTheString() throws Exception {
      JSONObject parsed = BoundedJsonParser.parseObject("{\"a\":\"c:\\\\\",\"b\":[1]}");
      assertEquals("c:\\", parsed.getString("a"));
      assertEquals(1, parsed.getJSONArray("b").length());
    }
  }

  @Nested
  @DisplayName("valid JSON")
  class ValidJson {

    @Test
    void anObjectParsesAsJettisonWould() throws Exception {
      String body = "{\"name\":\"a/b#c\",\"lines\":[{\"qty\":2},{\"qty\":3}],\"flag\":true}";
      assertEquals(new JSONObject(body).toString(), BoundedJsonParser.parseObject(body).toString());
    }

    @Test
    void anArrayParsesAsJettisonWould() throws Exception {
      String body = "[{\"fieldName\":\"posted\",\"operator\":\"equals\",\"value\":\"Y\"}]";
      JSONArray parsed = BoundedJsonParser.parseArray(body);
      assertEquals(new JSONArray(body).toString(), parsed.toString());
    }

    @Test
    void unicodeEscapesParse() throws Exception {
      assertEquals("[A", BoundedJsonParser.parseObject("{\"a\":\"\\u005b\\u0041\"}").getString("a"));
    }

    @Test
    void anArrowSeparatorStillOpensAString() throws Exception {
      assertEquals("v", BoundedJsonParser.parseObject("{'k'=>'v'}").getString("k"));
    }

    @Test
    void whitespaceAroundTheRootParses() throws Exception {
      assertEquals(1, BoundedJsonParser.parseObject(" \n{\"a\" : [ 1 , 'x' ] }\t ").length());
    }

    @Test
    void singleQuotedStringsStillParse() throws Exception {
      assertEquals("[[", BoundedJsonParser.parseObject("{'a':'[['}").getString("a"));
    }
  }

  @Nested
  @DisplayName("isWithinLimits")
  class IsWithinLimits {

    @Test
    void balancedShallowTextIsWithinLimits() {
      assertTrue(BoundedJsonParser.isWithinLimits("{\"a\":[1,{\"b\":\"]\"}]}", 3));
    }

    @Test
    void tooDeepTextIsNotWithinLimits() {
      assertFalse(BoundedJsonParser.isWithinLimits("[[[1]]]", 2));
    }

    @Test
    void truncatedTextIsNotWithinLimits() {
      assertFalse(BoundedJsonParser.isWithinLimits("{\"a\":[", 32));
    }
  }
}
