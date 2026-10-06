/*
 * *************************************************************************
 * The contents of this file are subject to the Etendo License
 * (the "License").
 * *************************************************************************
 */
package com.etendoerp.go.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Guards the agent-facing terminology of ETP-5602: the MCP tools are {@code etendo_<x>}, and the
 * agent never reads "NEO".
 *
 * <p>The rename touched hundreds of literals and two kinds of data, and any one of them reverting
 * is silent — an agent told to "call neo_schema first" simply gets the renamed-tool error and
 * loses a turn. So the guard reads what the agent reads: the string literals of the module's Java
 * code (comments and char literals skipped by {@link #stringLiterals}) and the {@code MCP_CONFIG} /
 * {@code AGENT_PROMPT} sourcedata. The prefix itself stays legal — {@code "neo_"} names the
 * removed tools in {@link McpConstants#LEGACY_TOOL_PREFIX} — only a full old tool name is
 * refused.</p>
 *
 * @covers com.etendoerp.go.mcp.ToolRegistry
 * @covers com.etendoerp.go.mcp.McpToolRouter
 * @covers com.etendoerp.go.mcp.McpResourceProvider
 */
class McpAgentTerminologyGuardTest {

  /** A removed tool name: {@code neo_} followed by the rest of a current fixed tool name. */
  private static final Pattern OLD_TOOL_NAME = Pattern.compile("\\bneo_("
      + McpConstants.TOOLS_RENAMED_FROM_NEO.stream()
          .map(name -> Pattern.quote(name.substring("etendo_".length())))
          .collect(Collectors.joining("|"))
      + ")\\b");
  private static final Pattern NEO_WORD = Pattern.compile("\\bNEO\\b");
  private static final Pattern AGENT_TEXT = Pattern.compile(
      "<!--([0-9A-Fa-f]{32})-->\\s*<(MCP_CONFIG|AGENT_PROMPT)><!\\[CDATA\\[(.*?)]]></\\2>",
      Pattern.DOTALL);

  @Test
  @DisplayName("no Java string literal of the module names a removed neo_<tool>")
  void noJavaLiteralNamesARemovedTool() throws IOException {
    List<String> hits = new ArrayList<>();
    for (Path file : javaSources(Paths.get(McpSourceScanner.srcRootForTests()))) {
      collect(hits, file, OLD_TOOL_NAME);
    }
    assertTrue(hits.isEmpty(), "removed tool names in agent-visible literals: " + hits);
  }

  @Test
  @DisplayName("no Java string literal of the MCP package says NEO")
  void noMcpLiteralSaysNeo() throws IOException {
    Path mcp = Paths.get(McpSourceScanner.srcRootForTests(), "com", "etendoerp", "go", "mcp");
    List<String> hits = new ArrayList<>();
    for (Path file : javaSources(mcp)) {
      collect(hits, file, NEO_WORD);
    }
    assertTrue(hits.isEmpty(), "NEO in MCP literals: " + hits);
  }

  @Test
  @DisplayName("MCP_CONFIG and AGENT_PROMPT sourcedata name no removed tool and never say NEO")
  void sourcedataAgentTextIsClean() throws IOException {
    Path dir = McpConfigSourcedataTest.sourcedataDir();
    if (!Files.isDirectory(dir)) {
      fail("sourcedata directory not found from " + Paths.get("").toAbsolutePath());
    }
    List<String> hits = new ArrayList<>();
    int rows = 0;
    for (String file : new String[] { "ETGO_SF_SPEC.xml", "ETGO_SF_ENTITY.xml",
        "ETGO_SF_FIELD.xml" }) {
      String xml = new String(Files.readAllBytes(dir.resolve(file)), StandardCharsets.UTF_8);
      Matcher row = AGENT_TEXT.matcher(xml);
      while (row.find()) {
        rows++;
        String text = row.group(3);
        if (OLD_TOOL_NAME.matcher(text).find() || NEO_WORD.matcher(text).find()) {
          hits.add(file + "#" + row.group(1) + " " + row.group(2));
        }
      }
    }
    assertTrue(rows > 0, "no MCP_CONFIG / AGENT_PROMPT rows were read — the guard checked nothing");
    assertTrue(hits.isEmpty(), "old terminology in agent-facing sourcedata: " + hits);
  }

  @Test
  @DisplayName("a quote inside a char literal does not flip the literal scan, even on long code")
  void charLiteralQuoteDoesNotFlipTheScan() {
    StringBuilder code = new StringBuilder("boolean q = first == '\\'' || first == '\"';\n")
        .append("char e = '\\\"';\n");
    while (code.length() < 6000) {
      code.append("int neo_list_count = compute(a, b) + other.value(); // neo_list\n");
    }
    code.append("/* \"neo_list\" */ log.debug(\"done\");\n");
    List<String> literals = stringLiterals(code.toString());
    assertEquals(List.of("\"done\""), literals);
    assertFalse(literals.stream().anyMatch(l -> OLD_TOOL_NAME.matcher(l).find()));
  }

  @Test
  @DisplayName("a removed tool name inside a string literal is still detected")
  void removedToolNameInLiteralIsDetected() {
    List<String> literals = stringLiterals(
        "char c = '\"'; String s = \"call neo_list first, say \\\"hi\\\"\"; String t = \"ok\";");
    assertEquals(2, literals.size());
    assertTrue(OLD_TOOL_NAME.matcher(literals.get(0)).find(), literals.get(0));
    assertFalse(OLD_TOOL_NAME.matcher(literals.get(1)).find());
  }

  private static List<Path> javaSources(Path root) throws IOException {
    if (!Files.isDirectory(root)) {
      fail("source directory not found: " + root.toAbsolutePath());
    }
    try (Stream<Path> files = Files.walk(root)) {
      return files.filter(p -> p.toString().endsWith(".java")).collect(Collectors.toList());
    }
  }

  private static void collect(List<String> hits, Path file, Pattern pattern) throws IOException {
    String source = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    for (String literal : stringLiterals(source)) {
      if (pattern.matcher(literal).find()) {
        hits.add(file.getFileName() + ": " + literal);
      }
    }
  }

  /**
   * The string literals (text blocks included) of a Java source, quotes kept, in one linear pass.
   *
   * <p>A regex over comment-stripped code is not enough: it recursed once per character (a
   * {@code StackOverflowError} on a long span) and a quote inside a char literal such as
   * {@code '"'} flipped its parity, turning real code into a "literal". This walks the source
   * once, skipping comments and char literals, so a quote is only an opening quote when it is
   * one.</p>
   */
  static List<String> stringLiterals(String source) {
    List<String> literals = new ArrayList<>();
    int n = source.length();
    int i = 0;
    while (i < n) {
      if (source.startsWith("//", i)) {
        int eol = source.indexOf('\n', i);
        i = eol < 0 ? n : eol + 1;
      } else if (source.startsWith("/*", i)) {
        int end = source.indexOf("*/", i + 2);
        i = end < 0 ? n : end + 2;
      } else if (source.charAt(i) == '\'') {
        i = skipQuoted(source, i + 1, "'");
      } else if (source.startsWith("\"\"\"", i)) {
        int end = skipQuoted(source, i + 3, "\"\"\"");
        literals.add(source.substring(i, end));
        i = end;
      } else if (source.charAt(i) == '"') {
        int end = skipQuoted(source, i + 1, "\"");
        literals.add(source.substring(i, end));
        i = end;
      } else {
        i++;
      }
    }
    return literals;
  }

  /** Index just past {@code close}, scanning from {@code from} and honouring backslash escapes. */
  private static int skipQuoted(String source, int from, String close) {
    int i = from;
    while (i < source.length()) {
      if (source.charAt(i) == '\\') {
        i += 2;
      } else if (source.startsWith(close, i)) {
        return i + close.length();
      } else {
        i++;
      }
    }
    return source.length();
  }
}
