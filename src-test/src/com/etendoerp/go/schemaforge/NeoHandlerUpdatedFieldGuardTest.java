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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Source-reading ratchet for the {@code updated}-field regression class (ETP-5220).
 *
 * <p><b>The bug this guards.</b> A {@code NeoHandler} whose {@code handle()} answers a GET-list
 * request with its own native-SQL query (bypassing the generic {@code NeoCrudHandler} read path)
 * can build each JSON row without ever selecting or emitting {@code updated}. Every PUT/PATCH
 * through the generic write path is required to echo {@code updated} back for the optimistic-
 * concurrency check ({@code NeoCrudHandler#validateUpdateRequest}, see {@code docs/neo-headless.md}
 * §4.3.3) — so a handler missing it 400s every edit as {@code missing_updated}, no matter how
 * freshly the record was just read. This has shipped twice as a real regression, not a hypothetical:
 * {@code ChartOfAccountsHandler} (ETP-5101) and {@code ProductPriceHandler} (ETP-5219). This test
 * exists to catch the third one before it reaches a user.
 *
 * <p><b>The rule.</b> Every {@code .java} file under {@code src/com/etendoerp/go/schemaforge}
 * (recursively — including the {@code handlers} subpackage) that both implements {@code NeoHandler}
 * and hand-rolls its own native-SQL GET list (a {@code createNativeQuery}/{@code createSQLQuery}
 * call alongside a {@code "GET".equals(...)} branch) must contain the literal {@code "updated"}
 * somewhere in its source — the JSON key every fixed handler puts via {@code FIELD_UPDATED} /
 * {@code entry.put("updated", ...)} / {@code item.put("updated", ...)}. A handler that doesn't is a
 * violation, unless it is named in {@link #ALLOWLIST} with a reason and an owning ticket.
 *
 * <p>This is a deliberately textual/source-scanning check (no compilation, no mocking) — same shape
 * as {@code TenantIsolationPolicyTest} in this same package. Adding a class to {@link #ALLOWLIST} to
 * make a failure go away is itself the thing to flag in review: it means a handler shipped with the
 * same bug and nobody fixed it yet — the allowlist is a ratchet, not a place to file things away.
 */
@DisplayName("NeoHandler updated-field guard")
class NeoHandlerUpdatedFieldGuardTest {

  private static final String SOURCE_ROOT = "src/com/etendoerp/go/schemaforge";
  private static final String NEO_HANDLER_MARKER = "implements NeoHandler";
  private static final String GET_BRANCH_MARKER = "\"GET\".equals(";
  private static final String UPDATED_LITERAL = "\"updated\"";

  /**
   * Handlers currently known to hand-roll a native-SQL GET list without emitting {@code updated}.
   * Every entry needs a one-line reason and the ticket that should audit/resolve it.
   */
  private static final List<String> ALLOWLIST = Arrays.asList(
      // ETP-5220: read-only dashboard/contact aggregation handlers — no PUT/PATCH branch
      // observed in their own handle(). NOT verified against the generic NeoCrudHandler write
      // path yet (a client could still PATCH the underlying entity through the generic CRUD
      // servlet using the id these expose). Audit before assuming safe.
      "ContactsBpStatsHandler",
      "WidgetPendingAmountsHandler",
      "ContactsBpTrendHandler",
      "WidgetPendingTasksHandler",
      "WidgetKpisHandler",
      "WidgetRevenueTrendHandler",
      "ProductStockWarehouseHandler",
      "WidgetActivityHandler",
      // ETP-5220: fix already exists on feature/ETP-5219 (adds pp.updated to PRICE_LIST_SQL +
      // row mapping), not yet merged to develop as of this branch's base. Remove this entry
      // once ETP-5219 lands on develop — the file will then genuinely contain "updated" and
      // stop matching as a violation regardless, so a stale entry here is harmless, just untidy.
      "ProductPriceHandler");

  /** Resolves the source root whether tests run from the module root or the workspace root. */
  private static Path sourceRoot() {
    Path fromModule = Paths.get(SOURCE_ROOT);
    if (Files.isDirectory(fromModule)) {
      return fromModule;
    }
    return Paths.get("modules/com.etendoerp.go", SOURCE_ROOT);
  }

  private static List<Path> allJavaFiles() throws IOException {
    Path root = sourceRoot();
    assertTrue(Files.isDirectory(root), "source root not found: " + root);
    try (Stream<Path> walk = Files.walk(root)) {
      return walk.filter(p -> p.toString().endsWith(".java")).collect(Collectors.toList());
    }
  }

  private static String sourceOf(Path file) throws IOException {
    return String.join("\n", Files.readAllLines(file, StandardCharsets.UTF_8));
  }

  private static String simpleClassName(Path file) {
    String name = file.getFileName().toString();
    return name.substring(0, name.length() - ".java".length());
  }

  /** Whether a file both implements {@code NeoHandler} and hand-rolls a native-SQL GET list. */
  private static boolean isInScope(String source) {
    boolean handlesNativeSql = source.contains("createNativeQuery") || source.contains("createSQLQuery");
    return source.contains(NEO_HANDLER_MARKER) && handlesNativeSql
        && source.contains(GET_BRANCH_MARKER);
  }

  private static Path findByClassName(String className) throws IOException {
    for (Path file : allJavaFiles()) {
      if (simpleClassName(file).equals(className)) {
        return file;
      }
    }
    throw new IllegalStateException("class not found under " + SOURCE_ROOT + ": " + className);
  }

  /**
   * Every in-scope handler either emits {@code updated} or is named (with a reason) in {@link
   * #ALLOWLIST}. Fails listing every unlisted violation, so a newly introduced handler with the
   * same bug is caught instead of silently merged.
   *
   * @throws IOException if a source file under {@link #SOURCE_ROOT} cannot be read
   */
  @Test
  @DisplayName("Every native-SQL GET-list handler emits updated, or is allowlisted")
  void testEveryInScopeHandlerEmitsUpdatedOrIsAllowlisted() throws IOException {
    List<String> violations = new ArrayList<>();
    for (Path file : allJavaFiles()) {
      String source = sourceOf(file);
      if (!isInScope(source)) {
        continue;
      }
      String className = simpleClassName(file);
      if (source.contains(UPDATED_LITERAL) || ALLOWLIST.contains(className)) {
        continue;
      }
      violations.add(className + " (" + file + ")");
    }
    if (!violations.isEmpty()) {
      fail("Handler(s) answer a native-SQL GET list without ever emitting \"updated\" — every "
          + "PUT/PATCH through them will 400 as missing_updated (see docs/neo-headless.md §4.3.3, "
          + "ETP-5101, ETP-5219). Add the field (mirroring ChartOfAccountsHandler / "
          + "ProductPriceHandler) or add a reasoned entry to ALLOWLIST:\n  "
          + String.join("\n  ", violations));
    }
  }

  /**
   * Sanity check: the scanner finds {@code ChartOfAccountsHandler} clean on its own merits (it
   * already contains the {@code updated} literal), not merely because it happens to be allowlisted.
   *
   * @throws IOException if the source file cannot be read
   */
  @Test
  @DisplayName("ChartOfAccountsHandler is genuinely clean, not merely allowlisted")
  void testChartOfAccountsHandlerIsGenuinelyClean() throws IOException {
    Path file = findByClassName("ChartOfAccountsHandler");
    String source = sourceOf(file);
    assertTrue(isInScope(source), "expected ChartOfAccountsHandler to match the in-scope pattern");
    assertFalse(ALLOWLIST.contains("ChartOfAccountsHandler"),
        "ChartOfAccountsHandler should not need an allowlist entry — it already emits updated");
    assertTrue(source.contains(UPDATED_LITERAL),
        "ChartOfAccountsHandler must contain the updated literal for this sanity check to hold");
  }

  /**
   * Sanity check: {@code ProductPriceHandler} is a genuine violation on THIS branch — its ETP-5219
   * fix lives on a separate, not-yet-merged branch — and is covered only via the allowlist, same as
   * the other 8 handlers, not because its source already contains {@code updated}.
   *
   * @throws IOException if the source file cannot be read
   */
  @Test
  @DisplayName("ProductPriceHandler is covered by the allowlist, same as the other 8")
  void testProductPriceHandlerIsCoveredByAllowlist() throws IOException {
    Path file = findByClassName("ProductPriceHandler");
    String source = sourceOf(file);
    assertTrue(isInScope(source), "expected ProductPriceHandler to match the in-scope pattern");
    assertTrue(ALLOWLIST.contains("ProductPriceHandler"),
        "ProductPriceHandler lacks \"updated\" on this branch (fix lives on feature/ETP-5219, not "
            + "yet merged) and must be allowlisted, not silently excluded");
  }
}
