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
 * All portions are Copyright (C) 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */
package com.etendoerp.go.schemaforge;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Architecture regression test for ETP-5483.
 *
 * <p>{@link BalanceSheetReportHandler} built a Hibernate {@code NativeQuery} whose SQL text used
 * Postgres' {@code ::} cast shorthand (e.g. {@code NULL::varchar}, {@code x::text}). Hibernate
 * 5.6's native-query parser treats {@code :} as the start of a named-parameter marker, so
 * {@code ::type} is mangled and the query fails at runtime with
 * {@code PSQLException: ERROR: syntax error at or near ":"} — the MCP tool then returns a 500.
 * The fix was to use ANSI {@code CAST(x AS type)} instead. Neither the psql-based parity check
 * nor the existing unit tests (which mock {@code NativeQuery}) can catch this class of bug, so
 * this is a cheap source-reading guard instead.</p>
 *
 * <p>This test scans every {@code .java} file under the module's {@code schemaforge} sources
 * that calls {@code createNativeQuery} — this naturally covers
 * {@code TrialBalanceReportHandler}, {@code JournalEntriesReportHandler},
 * {@code BalanceSheetReportHandler} and any future report handler or other native-query user —
 * and fails if any STRING LITERAL in that file contains {@code ::}. Comments are stripped first
 * so a comment that explains the rule (as {@code BalanceSheetReportHandler} does) is never
 * mistaken for a violation.</p>
 *
 * <p>The test is a source-reading guard, not a behavioral one: it reads the {@code .java} files
 * from disk. Tests may run either from the Etendo root ({@code modules/com.etendoerp.go/src/...})
 * or from the module root itself ({@code src/...}), so both are supported.</p>
 */
@DisplayName("ETP-5483 — native-query SQL must not use Postgres '::' casts in string literals")
class NativeSqlNoDoubleColonCastTest {

  private static final String MODULE_SRC_FROM_ETENDO_ROOT = "modules/com.etendoerp.go/src";
  private static final String MODULE_SRC_FROM_MODULE_ROOT = "src";
  private static final String SCHEMAFORGE_PACKAGE = "com/etendoerp/go/schemaforge";

  private static final Pattern CREATE_NATIVE_QUERY = Pattern.compile("createNativeQuery");

  /**
   * Matches Java string literals ({@code "..."}), honoring {@code \"} escapes inside the
   * literal. Does not attempt to parse text blocks ({@code """..."""}); none of the scanned
   * sources use them as of ETP-5483.
   */
  private static final Pattern STRING_LITERAL = Pattern.compile("\"(?:\\\\.|[^\"\\\\])*\"");

  private static final Pattern DOUBLE_COLON = Pattern.compile("::");

  // ---------------------------------------------------------------------
  // Main guard
  // ---------------------------------------------------------------------

  @Test
  @DisplayName("no createNativeQuery source file has a string literal containing '::'")
  void nativeQuerySourcesHaveNoDoubleColonCastInStringLiterals() throws IOException {
    Path schemaforgeRoot = resolveSchemaforgeRoot();
    List<Path> candidates = listJavaFiles(schemaforgeRoot);

    assertFalse(candidates.isEmpty(),
        "Expected at least one .java file under " + schemaforgeRoot.toAbsolutePath()
            + " — the guard's source resolution is broken, not the code base.");

    List<Path> nativeQueryFiles = new ArrayList<>();
    for (Path file : candidates) {
      String raw = readFile(file);
      if (CREATE_NATIVE_QUERY.matcher(raw).find()) {
        nativeQueryFiles.add(file);
      }
    }

    assertFalse(nativeQueryFiles.isEmpty(),
        "Expected at least one createNativeQuery call site under "
            + schemaforgeRoot.toAbsolutePath()
            + " (e.g. BalanceSheetReportHandler, TrialBalanceReportHandler,"
            + " JournalEntriesReportHandler) — the scan target list is empty, which means the"
            + " scanner itself regressed.");

    List<String> violations = new ArrayList<>();
    for (Path file : nativeQueryFiles) {
      violations.addAll(findDoubleColonCastViolations(readFile(file), file));
    }

    if (!violations.isEmpty()) {
      fail("Postgres '::' cast shorthand found in native-query SQL string literal(s). "
          + "Hibernate's native-query parser treats ':' as a named-parameter marker and mangles"
          + " '::type', which fails at runtime (PSQLException: syntax error at or near \":\")."
          + " Use CAST(x AS type) instead of x::type.\n  "
          + String.join("\n  ", violations));
    }
  }

  // ---------------------------------------------------------------------
  // Scanner self-test
  // ---------------------------------------------------------------------

  @Test
  @DisplayName("scanner: flags '::' inside a string literal")
  void scannerFlagsDoubleColonInsideStringLiteral() {
    String source = "String sql = \"SELECT x::text FROM t\";";
    List<String> violations = findDoubleColonCastViolations(source, Paths.get("InMemory.java"));
    assertFalse(violations.isEmpty(), "Expected a violation for a literal containing '::'");
  }

  @Test
  @DisplayName("scanner: does not flag CAST(x AS type)")
  void scannerDoesNotFlagAnsiCast() {
    String source = "String sql = \"SELECT CAST(x AS text) FROM t\";";
    List<String> violations = findDoubleColonCastViolations(source, Paths.get("InMemory.java"));
    assertTrue(violations.isEmpty(), "CAST(x AS type) must not be flagged");
  }

  @Test
  @DisplayName("scanner: does not flag '::' appearing only inside a // comment")
  void scannerDoesNotFlagDoubleColonInLineComment() {
    String source = "// use CAST(x AS type) instead of x::text — Hibernate mangles '::'\n"
        + "String sql = \"SELECT CAST(x AS text) FROM t\";";
    List<String> violations = findDoubleColonCastViolations(source, Paths.get("InMemory.java"));
    assertTrue(violations.isEmpty(), "A '::' that only appears in a // comment must not be flagged");
  }

  @Test
  @DisplayName("scanner: does not flag '::' appearing only inside a block comment")
  void scannerDoesNotFlagDoubleColonInBlockComment() {
    String source = "/* explains why we avoid x::text here */\n"
        + "String sql = \"SELECT CAST(x AS text) FROM t\";";
    List<String> violations = findDoubleColonCastViolations(source, Paths.get("InMemory.java"));
    assertTrue(violations.isEmpty(), "A '::' that only appears in a block comment must not be flagged");
  }

  @Test
  @DisplayName("scanner: does not flag named parameters like :name")
  void scannerDoesNotFlagNamedParameters() {
    String source = "String sql = \"SELECT * FROM t WHERE t.id = :recordId\";";
    List<String> violations = findDoubleColonCastViolations(source, Paths.get("InMemory.java"));
    assertTrue(violations.isEmpty(), "A plain named parameter (:name) must not be flagged");
  }

  @Test
  @DisplayName("scanner: does not flag a method reference (Foo::bar) outside string literals")
  void scannerDoesNotFlagMethodReference() {
    String source = "list.stream().map(String::trim).filter(StringUtils::isNotBlank);";
    List<String> violations = findDoubleColonCastViolations(source, Paths.get("InMemory.java"));
    assertTrue(violations.isEmpty(), "A method reference outside string literals must not be flagged");
  }

  // ---------------------------------------------------------------------
  // Scanning logic
  // ---------------------------------------------------------------------

  /**
   * Finds string literals containing Postgres' {@code ::} cast shorthand in the given source.
   * Comments are stripped first so a documented mention of the rule is never mistaken for a
   * violation.
   *
   * <p><b>Known limitation:</b> comment stripping is regex-based, matching {@link
   * ReportPathNoJasperRegressionTest} and {@code McpSourceScanner} elsewhere in this test suite.
   * A {@code //} that appears inside a string literal earlier on the same line as a real {@code
   * //} comment could, in principle, confuse a purely comment-first strip. None of the scanned
   * production sources contain a {@code //} inside a SQL string literal as of ETP-5483.</p>
   *
   * @param source the file's text (or an in-memory snippet for the scanner self-tests)
   * @param file   the file path, used only to build a readable violation message
   * @return a list of human-readable violation messages, one per offending literal; empty if
   *     none
   */
  private static List<String> findDoubleColonCastViolations(String source, Path file) {
    String stripped = stripComments(source);
    List<String> violations = new ArrayList<>();

    Matcher literalMatcher = STRING_LITERAL.matcher(stripped);
    while (literalMatcher.find()) {
      String literal = literalMatcher.group();
      if (DOUBLE_COLON.matcher(literal).find()) {
        int line = 1 + countNewlines(stripped, literalMatcher.start());
        violations.add(file + ":" + line + " — string literal contains '::': " + literal
            + " — use CAST(x AS type) instead of x::type — Hibernate treats ':' as a"
            + " named-parameter marker in native queries.");
      }
    }
    return violations;
  }

  /** Remove block comments ({@code /* ... *}{@code /}) and line comments ({@code // ...}). */
  private static String stripComments(String source) {
    // Each block comment is replaced by its own newlines only, so every character after it keeps
    // its original line number and a violation is reported on the line it really is on.
    Matcher blocks = Pattern.compile("(?s)/\\*.*?\\*/").matcher(source);
    StringBuilder noBlocks = new StringBuilder();
    while (blocks.find()) {
      String newlines = blocks.group().replaceAll("[^\\n]", "");
      blocks.appendReplacement(noBlocks, " " + newlines);
    }
    blocks.appendTail(noBlocks);
    return noBlocks.toString().replaceAll("//[^\\n]*", " ");
  }

  private static int countNewlines(String text, int uptoExclusive) {
    int count = 0;
    for (int i = 0; i < uptoExclusive && i < text.length(); i++) {
      if (text.charAt(i) == '\n') {
        count++;
      }
    }
    return count;
  }

  // ---------------------------------------------------------------------
  // File resolution
  // ---------------------------------------------------------------------

  private static String readFile(Path file) {
    try {
      return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static List<Path> listJavaFiles(Path root) throws IOException {
    if (!Files.isDirectory(root)) {
      return new ArrayList<>();
    }
    try (Stream<Path> walk = Files.walk(root)) {
      List<Path> result = new ArrayList<>();
      walk.filter(Files::isRegularFile)
          .filter(p -> p.toString().endsWith(".java"))
          .forEach(result::add);
      return result;
    }
  }

  /**
   * Resolve the {@code com/etendoerp/go/schemaforge} source directory. Tests may run from the
   * Etendo root (working directory above {@code modules/}) or from the module root itself
   * (working directory is {@code modules/com.etendoerp.go}), so both are supported. Fails
   * loudly via the caller's assertion (not silently) if neither resolves to an existing
   * directory — this guard must never pass by scanning zero files.
   */
  private static Path resolveSchemaforgeRoot() {
    Path fromEtendoRoot = Paths.get(MODULE_SRC_FROM_ETENDO_ROOT, SCHEMAFORGE_PACKAGE);
    if (Files.isDirectory(fromEtendoRoot)) {
      return fromEtendoRoot;
    }
    Path fromModuleRoot = Paths.get(MODULE_SRC_FROM_MODULE_ROOT, SCHEMAFORGE_PACKAGE);
    if (Files.isDirectory(fromModuleRoot)) {
      return fromModuleRoot;
    }
    // Fallback: walk up from the current directory, matching the resolution strategy used by
    // ReportPathNoJasperRegressionTest and McpSourceScanner elsewhere in this test suite.
    Path dir = Paths.get("").toAbsolutePath();
    while (dir != null) {
      Path candidateFromEtendoRoot = dir.resolve(MODULE_SRC_FROM_ETENDO_ROOT).resolve(SCHEMAFORGE_PACKAGE);
      if (Files.isDirectory(candidateFromEtendoRoot)) {
        return candidateFromEtendoRoot;
      }
      Path candidateFromModuleRoot = dir.resolve(MODULE_SRC_FROM_MODULE_ROOT).resolve(SCHEMAFORGE_PACKAGE);
      if (Files.isDirectory(candidateFromModuleRoot)) {
        return candidateFromModuleRoot;
      }
      dir = dir.getParent();
    }
    // Return a non-existent path so the caller's assertFalse(candidates.isEmpty()) fails with a
    // clear "expected at least one .java file under <path>" message rather than an NPE.
    return fromEtendoRoot;
  }
}
