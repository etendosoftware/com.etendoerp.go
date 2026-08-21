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

package com.etendoerp.go.mcp;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Architecture regression test for ETP-4793 / IMP-16 — every MCP write verb must coerce.
 *
 * <p>IMP-16 shipped a correct date coercer and {@code neo_update} kept corrupting dates anyway:
 * {@code orderDate: "09-08-2026"} was accepted under {@code status: 0} and stored as
 * {@code 0015-02-16}, because {@code handleUpdate} never called {@code coerceFieldTypes}. The
 * coercer was reachable from {@code handleCreate} only. Nothing in a signature, a type or a unit
 * test on the coercer itself could catch that — the defect was a <b>missing call site</b>, and the
 * unit tests for both coercers passed throughout.</p>
 *
 * <p>So this guard is deliberately structural: any method of {@link McpToolRouter} that persists
 * through {@code DefaultJsonDataService} ({@code jsonService.add} / {@code jsonService.update})
 * must also invoke {@code coerceFieldTypes} on the body it is about to persist. A new write verb
 * that forgets the pass fails here instead of silently writing year 0015 to the database.</p>
 *
 * <p>Why source-reading rather than behavioral: the write verbs are private, require an
 * {@code OBContext}, a live DAL and an {@code AD_Tab}, so the call site cannot be asserted from a
 * unit test. Reading the source is the cheap check that maps exactly onto the failure mode.</p>
 */
@DisplayName("ETP-4793 / IMP-16 — every MCP persist path must run coerceFieldTypes")
class McpWriteVerbCoercionCallSiteTest {

  private static final String MODULE_SRC = "modules/com.etendoerp.go/src";
  private static final String ROUTER = "com/etendoerp/go/mcp/McpToolRouter.java";

  /** The DAL persist calls that must never be reached with an uncoerced body. */
  private static final Pattern PERSIST_CALL =
      Pattern.compile("jsonService\\s*\\.\\s*(add|update)\\s*\\(");

  /** The coercion pass that repairs types and canonicalizes dates (IMP-16). */
  private static final Pattern COERCE_CALL = Pattern.compile("coerceFieldTypes\\s*\\(");

  /**
   * Matches the tail of a method signature — the declared name immediately before the body's
   * opening brace — so a violation can be reported by method name rather than by offset.
   */
  private static final Pattern SIGNATURE_TAIL =
      Pattern.compile("([A-Za-z_$][A-Za-z0-9_$]*)\\s*\\([^()]*\\)\\s*(?:throws\\s[^{]*)?$");

  @Test
  @DisplayName("every method that persists through the DAL also coerces the body first")
  void everyPersistingMethodCoercesItsBody() throws IOException {
    String source = stripComments(readRouterSource());
    Map<String, String> methods = extractMethodBodies(source);

    List<String> persisting = new ArrayList<>();
    List<String> violations = new ArrayList<>();
    for (Map.Entry<String, String> method : methods.entrySet()) {
      String body = method.getValue();
      if (!PERSIST_CALL.matcher(body).find()) {
        continue;
      }
      persisting.add(method.getKey());
      if (!COERCE_CALL.matcher(body).find()) {
        violations.add(method.getKey());
      }
    }

    // A guard that stops finding the calls it guards is not passing, it is mute. handleCreate and
    // handleUpdate are the two known persist paths; fewer than two means the scan broke (renamed
    // service field, refactor into a helper) and the assertion below would pass vacuously.
    assertTrue(persisting.size() >= 2,
        "Expected at least 2 persisting methods in McpToolRouter (handleCreate, handleUpdate), found "
            + persisting.size() + ": " + persisting
            + ". The scan, not the code, is probably what changed — fix this test.");

    if (!violations.isEmpty()) {
      fail("MCP write verbs persist an uncoerced body: " + violations
          + ". Call coerceFieldTypes(body, dalEntity) before wrapping. Without it the DAL's lenient"
          + " date parser turns dd-MM-yyyy into a first-century date under status 0"
          + " (ETP-4793 / IMP-16), and String values reach OBDal untyped.");
    }
  }

  private String readRouterSource() throws IOException {
    Path file = resolveSrcRoot().resolve(ROUTER);
    assertTrue(Files.exists(file), "Router source not found at " + file.toAbsolutePath());
    return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
  }

  /**
   * Resolve the module {@code src} root. Tests run from the Etendo root, but fall back to a walk
   * up from the current directory so the test is robust to the working directory.
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
      Path local = dir.resolve("src/com/etendoerp/go");
      if (Files.isDirectory(local)) {
        return dir.resolve("src");
      }
      dir = dir.getParent();
    }
    return fromRoot;
  }

  /**
   * Split the class into its method bodies, keyed by method name.
   *
   * <p>Brace depth 1 is the class body, so every block that opens at depth 1 and closes back to it
   * is a member body — which is exactly the granularity the assertion needs ("does <i>this</i>
   * method coerce before persisting?"). String and char literals are skipped while counting, since
   * the router's log formats and JSON snippets contain unbalanced braces.</p>
   */
  private Map<String, String> extractMethodBodies(String source) {
    Map<String, String> bodies = new LinkedHashMap<>();
    int depth = 0;
    int bodyStart = -1;
    String pendingName = null;
    int i = 0;
    while (i < source.length()) {
      char c = source.charAt(i);
      if (c == '"' || c == '\'') {
        i = skipLiteral(source, i);
        continue;
      }
      if (c == '{') {
        if (depth == 1) {
          pendingName = methodNameBefore(source, i);
          bodyStart = i;
        }
        depth++;
      } else if (c == '}') {
        depth--;
        if (depth == 1 && bodyStart >= 0) {
          String name = pendingName != null ? pendingName : "<anonymous@" + bodyStart + ">";
          // Overloads share a name; keep them distinct so neither can mask the other.
          String key = bodies.containsKey(name) ? name + "@" + bodyStart : name;
          bodies.put(key, source.substring(bodyStart, Math.min(i + 1, source.length())));
          bodyStart = -1;
          pendingName = null;
        }
      }
      i++;
    }
    return bodies;
  }

  /** Returns the index just past the string/char literal starting at {@code start}. */
  private int skipLiteral(String source, int start) {
    char quote = source.charAt(start);
    int i = start + 1;
    while (i < source.length()) {
      char c = source.charAt(i);
      if (c == '\\') {
        i += 2;
        continue;
      }
      if (c == quote) {
        return i + 1;
      }
      i++;
    }
    return source.length();
  }

  /** Extracts the declared name from the signature text preceding a body's opening brace. */
  private String methodNameBefore(String source, int braceIndex) {
    int from = Math.max(0, braceIndex - 400);
    String preceding = source.substring(from, braceIndex).trim();
    Matcher matcher = SIGNATURE_TAIL.matcher(preceding);
    return matcher.find() ? matcher.group(1) : null;
  }

  /**
   * Remove block and line comments so that a documented mention of a persist or coercion call is
   * not mistaken for the call itself — the Javadoc on these very methods names both.
   */
  private String stripComments(String source) {
    String noBlocks = source.replaceAll("(?s)/\\*.*?\\*/", " ");
    return noBlocks.replaceAll("//[^\\n]*", " ");
  }
}
