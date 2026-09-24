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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Modifier;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Guardrail: every report handler must declare its own access rule (ETP-5335).
 *
 * <h2>Why this test exists</h2>
 * <p>{@link NeoHandler#isAccessibleForCurrentRole()} defaults to {@code true}. That default is
 * deliberate — it is what lets the method be added to an interface with ~90 implementations
 * without changing any of them — and it is also exactly what turns a forgotten override into an
 * open report instead of a compile error. It happened once already, during the review of this very
 * change: {@code InventoryStockReportHandler}'s inline check was replaced by a call to the new
 * method <i>before</i> the override was added, so the handler inherited the permissive default and
 * the report was readable by every role for a full deploy. Nothing failed; it was caught by
 * chance, reading the catalogue.</p>
 *
 * <p>No behavioural test can catch that: the handler still compiles, still serves, and its own
 * unit tests still pass. Only an enumeration over the report handlers can.</p>
 *
 * <h2>What counts as a report handler</h2>
 * <p>A handler that declares {@link NeoHandler#reportParameters()}. That is the platform's own
 * callability signal, not a name convention: {@code NeoReportCallability.isReportCallable} treats a
 * spec as callable precisely when its handler declares a report contract, and that is what
 * publishes the spec's {@code generate_*} MCP tool and lists it in {@code neo_discover}. So the set
 * this test walks is, by construction, the set of handlers reachable as a report — which is the set
 * that needs a rule. It is also why the criterion is not "class name ends in ReportHandler" (a
 * renamed class would escape) nor "is behind a {@code spec_type = 'R'} spec" (that lives in the
 * database, which this test deliberately does not need).</p>
 *
 * <p><b>When the fourth report is added</b> it is picked up automatically the moment it declares
 * {@code reportParameters()} — the same act that publishes its tool — and this test fails until it
 * also declares {@code isAccessibleForCurrentRole()}. The fix is to add the override, never to add
 * the class to an exemption list: there is none, on purpose. A report that genuinely should be
 * readable by any authenticated role still has to say so explicitly, with
 * {@code return true;} and a comment explaining why — the point is that the decision is made,
 * not that it is restrictive.</p>
 *
 * <h2>Reflection, not source text</h2>
 * <p>The question is "does this class override the method", which is a property of the compiled
 * class, so {@code getDeclaredMethod} answers it exactly. Classes are loaded with
 * {@code initialize = false} so no static initialiser runs: nothing here needs an
 * {@code OBContext}, a DAL or a servlet container.</p>
 */
class ReportHandlerAccessDeclarationTest {

  private static final String ACCESS_METHOD = "isAccessibleForCurrentRole";
  private static final String REPORT_CONTRACT_METHOD = "reportParameters";
  private static final String SCANNED_PACKAGE_PATH = "com/etendoerp/go";

  /**
   * The report handlers known at the time of writing. Their presence is asserted so a scan that
   * silently finds nothing — a moved classes directory, a package rename — fails loudly instead of
   * passing vacuously over an empty set. This module has already had 24 tests pass against dead
   * code once ({@code McpBillToInjectorTest}); an enumeration guard with no floor is the same
   * trap.
   */
  private static final Set<String> KNOWN_REPORT_HANDLERS = Set.of(
      TaxReportHandler.class.getName(),
      InventoryStockReportHandler.class.getName(),
      AgingReportHandler.class.getName(),
      AgingPayableReportHandler.class.getName(),
      TrialBalanceReportHandler.class.getName());

  @Test
  @DisplayName("every report handler overrides isAccessibleForCurrentRole")
  void everyReportHandlerDeclaresItsAccessRule() {
    List<String> undeclared = undeclaredAmong(discoverReportHandlers());

    assertTrue(undeclared.isEmpty(),
        "These report handlers inherit the permissive default of NeoHandler."
            + ACCESS_METHOD + "(), so the report they serve is readable by any authenticated "
            + "role — the catalogue offers it and nothing refuses the call. Override the method "
            + "with the grant the report should require (an AD_Process, an OBUIAPP process or a "
            + "window), or return true with a comment saying why the report needs no grant: "
            + undeclared);
  }

  @Test
  @DisplayName("the scan finds the report handlers it is meant to guard")
  void scanFindsTheKnownReportHandlers() {
    Set<String> found = discoverReportHandlers().stream()
        .map(Class::getName)
        .collect(Collectors.toCollection(TreeSet::new));

    for (String known : KNOWN_REPORT_HANDLERS) {
      assertTrue(found.contains(known),
          "The scan did not reach " + known + ", so the guardrail above is not actually "
              + "guarding anything. Classes found: " + found);
    }
  }

  /**
   * The default is what makes the guardrail necessary; asserting it here keeps the two facts
   * together. If the default ever became {@code false} (deny unless declared) this test is the
   * place that says the guardrail's reason has changed.
   */
  @Test
  @DisplayName("NeoHandler's default is permissive, which is why the guardrail exists")
  void theInheritedDefaultIsPermissive() {
    NeoHandler undeclaring = context -> null;

    assertTrue(undeclaring.isAccessibleForCurrentRole(),
        "A handler that declares nothing is allowed; that is the fail-open this test guards");
    assertFalse(declares(undeclaring.getClass(), ACCESS_METHOD));
  }

  /**
   * A report handler exactly as the regression produced it: it declares the report contract — so
   * its spec is callable and its {@code generate_*} tool is published — and says nothing about
   * access, silently inheriting the permissive default.
   *
   * <p>It lives here, in the test sources, rather than being simulated: test classes compile to
   * {@code src-test/build/classes} while {@link #discoverReportHandlers()} walks the module's own
   * output, so this class can be a faithful negative control without being picked up by the real
   * scan.</p>
   */
  static class UndeclaringReportHandler implements NeoHandler {
    @Override
    public NeoResponse handle(NeoContext context) {
      return null;
    }

    @Override
    public java.util.Optional<List<com.etendoerp.go.schemaforge.util.NeoReportParam>>
        reportParameters() {
      return java.util.Optional.of(List.of());
    }
  }

  /**
   * Negative control. Without it the guardrail could pass because it finds nothing wrong OR
   * because it cannot tell right from wrong, and those two look identical in a green run.
   */
  @Test
  @DisplayName("the guardrail flags a report handler that declares no access rule")
  void guardrailFlagsAnUndeclaringReportHandler() {
    assertTrue(declares(UndeclaringReportHandler.class, REPORT_CONTRACT_METHOD),
        "The control must be recognised as a report handler in the first place");

    List<String> undeclared = undeclaredAmong(List.<Class<?>>of(UndeclaringReportHandler.class));

    assertEquals(List.of(UndeclaringReportHandler.class.getName()), undeclared,
        "A report handler with no access declaration must be reported by the same check the "
            + "guardrail runs — this is the shape the inventory report shipped in for a deploy");
  }

  // ── discovery ────────────────────────────────────────────────────────────

  /** The report handlers that do not declare an access rule, by name. */
  private static List<String> undeclaredAmong(java.util.Collection<Class<?>> reportHandlers) {
    return reportHandlers.stream()
        .filter(c -> !declares(c, ACCESS_METHOD))
        .map(Class::getName)
        .sorted()
        .collect(Collectors.toList());
  }

  /** Handlers that declare a report contract, i.e. are reachable as a report. */
  private static List<Class<?>> discoverReportHandlers() {
    return loadModuleClasses().stream()
        .filter(NeoHandler.class::isAssignableFrom)
        .filter(c -> !c.isInterface() && !Modifier.isAbstract(c.getModifiers()))
        .filter(c -> declares(c, REPORT_CONTRACT_METHOD))
        .collect(Collectors.toList());
  }

  /** Whether the class itself declares the method, as opposed to inheriting the interface default. */
  private static boolean declares(Class<?> type, String methodName) {
    for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
      try {
        c.getDeclaredMethod(methodName);
        return true;
      } catch (NoSuchMethodException ignored) {
        // Keep walking up the concrete hierarchy: a shared base class may carry the override.
      }
    }
    return false;
  }

  /**
   * Every class compiled from this module's {@code com.etendoerp.go} tree, loaded without
   * initialisation. A class that cannot be loaded at all is skipped — some classes reference
   * optional dependencies — which is safe only because {@link #scanFindsTheKnownReportHandlers}
   * asserts the handlers this guard is about were in fact reached.
   */
  private static List<Class<?>> loadModuleClasses() {
    Path root = classesRoot();
    Path packageRoot = root.resolve(SCANNED_PACKAGE_PATH);
    if (!Files.isDirectory(packageRoot)) {
      fail("Compiled classes not found under " + packageRoot.toAbsolutePath()
          + " — the scan cannot run, so no report handler is being guarded.");
    }
    List<Class<?>> classes = new ArrayList<>();
    try (Stream<Path> files = Files.walk(packageRoot)) {
      for (Path file : files.filter(p -> p.toString().endsWith(".class")).collect(Collectors.toList())) {
        String name = root.relativize(file).toString()
            .replace(java.io.File.separatorChar, '.')
            .replaceAll("\\.class$", "");
        try {
          classes.add(Class.forName(name, false, ReportHandlerAccessDeclarationTest.class.getClassLoader()));
        } catch (Throwable ignored) {
          // Unloadable (optional dependency, generated stub): cannot be a report handler we can
          // inspect, and the known-handlers floor below would fail if one of ours landed here.
        }
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    assertFalse(classes.isEmpty(), "No class loaded from " + packageRoot.toAbsolutePath());
    return classes;
  }

  /**
   * The directory the module's classes were compiled into, taken from {@link NeoHandler} itself so
   * the scan does not depend on the working directory or on a hardcoded build layout.
   */
  private static Path classesRoot() {
    URL location = NeoHandler.class.getProtectionDomain().getCodeSource().getLocation();
    try {
      Path path = Paths.get(location.toURI());
      if (!Files.isDirectory(path)) {
        fail("NeoHandler was loaded from " + path + ", which is not a directory of class files; "
            + "the scan expects an exploded classes directory.");
      }
      return path;
    } catch (URISyntaxException e) {
      throw new IllegalStateException("Could not resolve the classes root from " + location, e);
    }
  }

  /** Sanity: the helper distinguishes a declared override from an inherited default. */
  @Test
  @DisplayName("declares() sees an override and not an inherited default")
  void declaresDistinguishesOverrideFromDefault() {
    assertTrue(declares(TaxReportHandler.class, ACCESS_METHOD));
    assertFalse(declares(((NeoHandler) context -> null).getClass(), ACCESS_METHOD));
    assertEquals(5, KNOWN_REPORT_HANDLERS.size());
  }
}
