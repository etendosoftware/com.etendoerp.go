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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads one method body out of a module source file, for the structural regression guards in this
 * package.
 *
 * <h2>Why source reading is the right tool here, twice</h2>
 * <p>Both callers guard a <b>call site</b>, and a missing call site is invisible to every other
 * kind of test: the unit tests of the thing that should have been called keep passing, and no
 * signature or type changes. {@code McpWriteVerbCoercionCallSiteTest} is the precedent —
 * {@code neo_update} corrupted dates for a release because {@code handleUpdate} never invoked the
 * coercer that {@code handleCreate} did. The methods guarded here need an {@code OBContext}, a live
 * DAL and an {@code AD_Tab}, so the call site cannot be asserted behaviourally at all.</p>
 *
 * <p>Shared rather than copied into each test: two independent copies of a scanner would be free to
 * disagree about what counts as a method body, and then one guard could pass on a file the other
 * reads differently.</p>
 */
final class McpSourceScanner {

  private static final String MODULE_SRC = "modules/com.etendoerp.go/src";

  private McpSourceScanner() {
  }

  /**
   * Read a module source file.
   *
   * @param relativePath the path under the module's {@code src}, e.g.
   *                     {@code com/etendoerp/go/mcp/McpToolRouter.java}
   * @return the file's text
   * @throws UncheckedIOException if the file cannot be read
   * @throws IllegalStateException if the file does not exist
   */
  static String read(String relativePath) {
    Path file = srcRoot().resolve(relativePath);
    if (!Files.exists(file)) {
      throw new IllegalStateException("Source not found at " + file.toAbsolutePath());
    }
    try {
      return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * Extract one named method's body, comments already removed.
   *
   * <p>The declaration is matched as {@code name(...)} followed by an opening brace, so a call to
   * the same method elsewhere in the file — which ends in {@code ;} — cannot be mistaken for it.
   * String and char literals are skipped while counting brace depth, since these files carry log
   * formats and JSON snippets with unbalanced braces. Comments go first so that a documented
   * mention of a call is never read as the call itself: the javadoc of the very methods being
   * guarded names them.</p>
   *
   * @param source the file text
   * @param name   the method's declared name; overloads resolve to the first declaration
   * @return the body including its braces
   * @throws IllegalStateException if the declaration cannot be located or the braces are unbalanced
   */
  static String methodBody(String source, String name) {
    String stripped = stripComments(source);
    Pattern declaration = Pattern.compile("\\b" + Pattern.quote(name) + "\\s*\\([^;{)]*\\)\\s*"
        + "(?:throws\\s[^{;]*)?\\{");
    Matcher matcher = declaration.matcher(stripped);
    if (!matcher.find()) {
      throw new IllegalStateException("Could not locate the declaration of " + name
          + " — the scanner, not the source, is probably what needs fixing.");
    }
    int open = matcher.end() - 1;
    int depth = 0;
    int i = open;
    while (i < stripped.length()) {
      char c = stripped.charAt(i);
      if (c == '"' || c == '\'') {
        i = skipLiteral(stripped, i);
        continue;
      }
      if (c == '{') {
        depth++;
      } else if (c == '}') {
        depth--;
        if (depth == 0) {
          return stripped.substring(open, i + 1);
        }
      }
      i++;
    }
    throw new IllegalStateException("Unbalanced braces while reading " + name);
  }

  /** Remove block and line comments. */
  static String stripComments(String source) {
    String noBlocks = source.replaceAll("(?s)/\\*.*?\\*/", " ");
    return noBlocks.replaceAll("//[^\\n]*", " ");
  }

  /** Returns the index just past the string/char literal starting at {@code start}. */
  private static int skipLiteral(String source, int start) {
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

  /**
   * Resolve the module {@code src} root. Tests run from the Etendo root, but fall back to a walk up
   * from the current directory so the scanner is robust to the working directory.
   */
  private static Path srcRoot() {
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
}
