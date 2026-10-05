/*
 * *************************************************************************
 * The contents of this file are subject to the Etendo License
 * (the "License").
 * *************************************************************************
 */
package com.etendoerp.go.mcp;

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
 * code (comments excluded, via {@link McpSourceScanner}) and the {@code MCP_CONFIG} /
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
  private static final Pattern STRING_LITERAL = Pattern.compile("\"(?:[^\"\\\\]|\\\\.)*\"");
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

  private static List<Path> javaSources(Path root) throws IOException {
    if (!Files.isDirectory(root)) {
      fail("source directory not found: " + root.toAbsolutePath());
    }
    try (Stream<Path> files = Files.walk(root)) {
      return files.filter(p -> p.toString().endsWith(".java")).collect(Collectors.toList());
    }
  }

  private static void collect(List<String> hits, Path file, Pattern pattern) throws IOException {
    String code = McpSourceScanner.stripComments(
        new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
    Matcher literal = STRING_LITERAL.matcher(code);
    while (literal.find()) {
      if (pattern.matcher(literal.group()).find()) {
        hits.add(file.getFileName() + ": " + literal.group());
      }
    }
  }
}
