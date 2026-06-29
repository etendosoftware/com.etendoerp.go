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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Architecture regression test for ETP-4255.
 *
 * <p>Etendo Go / NEO Headless / MCP must NEVER execute Jasper at runtime: no class on the
 * report request path may reference {@code ReportingUtils} or {@code exportJR} in executable
 * code. This test scans the source of every class that participates in serving a report
 * request and fails if any of those forbidden tokens appears outside a comment / Javadoc.</p>
 *
 * <p>Documented Javadoc/comment mentions are explicitly allowed (the {@link NeoReportService}
 * stub and {@code NeoReportCallability} both reference {@code ReportingUtils.exportJR} in
 * their class Javadoc to explain WHY the runtime path was removed). This test strips comments
 * before matching so those provenance mentions are not flagged.</p>
 *
 * <p>The test is a source-reading guard, not a behavioral one: it reads the {@code .java}
 * files from disk (tests run from the Etendo root, so sources live under
 * {@code modules/com.etendoerp.go/src/...}).</p>
 */
@DisplayName("ETP-4255 — report request path must not reference Jasper (ReportingUtils/exportJR)")
class ReportPathNoJasperRegressionTest {

  private static final String MODULE_SRC = "modules/com.etendoerp.go/src";

  /** Forbidden Jasper runtime tokens. */
  private static final Pattern FORBIDDEN = Pattern.compile("ReportingUtils|exportJR");

  /**
   * Every class that participates in serving an MCP or NEO HTTP report request. If any of
   * these reintroduces a Jasper runtime call, the test fails.
   */
  private static final String[] REPORT_PATH_CLASSES = {
      "com/etendoerp/go/schemaforge/NeoRequestRouter.java",
      "com/etendoerp/go/schemaforge/NeoReportService.java",
      "com/etendoerp/go/schemaforge/util/NeoReportCallability.java",
      "com/etendoerp/go/mcp/McpToolRouter.java",
      "com/etendoerp/go/mcp/McpToolRouterSupport.java",
      "com/etendoerp/go/mcp/McpResourceProvider.java",
      "com/etendoerp/go/mcp/ToolRegistry.java",
  };

  @Test
  @DisplayName("no report-path class references ReportingUtils/exportJR in executable code")
  void reportPathHasNoJasperRuntimeReference() throws IOException {
    Path srcRoot = resolveSrcRoot();
    List<String> violations = new ArrayList<>();

    for (String relative : REPORT_PATH_CLASSES) {
      Path file = srcRoot.resolve(relative);
      if (!Files.exists(file)) {
        // A class on the documented path went missing/renamed — fail loudly so the guard
        // list is kept in sync with the code base rather than silently passing.
        violations.add(relative + " — source file not found at " + file.toAbsolutePath());
        continue;
      }
      String stripped = stripComments(
          new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
      Matcher matcher = FORBIDDEN.matcher(stripped);
      if (matcher.find()) {
        violations.add(relative + " — forbidden Jasper token '" + matcher.group()
            + "' in executable code (ETP-4255 removed runtime Jasper)");
      }
    }

    if (!violations.isEmpty()) {
      fail("Report request path must not reference Jasper runtime APIs:\n  "
          + String.join("\n  ", violations));
    }
  }

  /**
   * Sanity check: the forbidden tokens DO still appear (in Javadoc only) in the two classes
   * that document the removal — this proves {@link #stripComments} is actually removing the
   * documented mentions rather than the guard passing because the tokens vanished entirely.
   */
  @Test
  @DisplayName("documented Javadoc mentions of ReportingUtils are preserved and ignored")
  void documentedMentionsAreStrippedNotFlagged() throws IOException {
    Path srcRoot = resolveSrcRoot();
    String[] documentingClasses = {
        "com/etendoerp/go/schemaforge/NeoReportService.java",
        "com/etendoerp/go/schemaforge/util/NeoReportCallability.java",
    };

    for (String relative : documentingClasses) {
      Path file = srcRoot.resolve(relative);
      String raw = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
      assertTrue(FORBIDDEN.matcher(raw).find(),
          relative + " should still document the removed Jasper path in Javadoc");
      assertTrue(!FORBIDDEN.matcher(stripComments(raw)).find(),
          relative + " Javadoc mention must not survive comment stripping");
    }
  }

  /**
   * Resolve the module {@code src} root. Tests run from the Etendo root, but fall back to a
   * walk up from the current directory so the test is robust to the working directory.
   */
  private Path resolveSrcRoot() {
    Path fromRoot = Paths.get(MODULE_SRC);
    if (Files.isDirectory(fromRoot)) {
      return fromRoot;
    }
    Path dir = Paths.get("").toAbsolutePath();
    while (dir != null) {
      Path candidate = dir.resolve(MODULE_SRC);
      if (Files.isDirectory(candidate)) {
        return candidate;
      }
      // Also handle being launched from inside the module itself.
      Path local = dir.resolve("src/com/etendoerp/go");
      if (Files.isDirectory(local)) {
        return dir.resolve("src");
      }
      dir = dir.getParent();
    }
    return fromRoot;
  }

  /**
   * Remove block comments ({@code /* ... *&#47;}) and line comments ({@code // ...}) so that
   * documented references to the removed Jasper APIs are not mistaken for executable code.
   * Deliberately simple: report-path sources contain no string literals carrying the
   * forbidden tokens, so a literal-aware parser is unnecessary.
   */
  private String stripComments(String source) {
    // Remove block comments (including Javadoc) first, then line comments.
    String noBlocks = source.replaceAll("(?s)/\\*.*?\\*/", " ");
    return noBlocks.replaceAll("//[^\\n]*", " ");
  }
}
