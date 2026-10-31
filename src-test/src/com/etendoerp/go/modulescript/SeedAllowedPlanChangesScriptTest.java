/*
 * *************************************************************************
 * Etendo License. See https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * *************************************************************************
 */
package com.etendoerp.go.modulescript;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openbravo.database.ConnectionProvider;

/**
 * ETP-5053 — the Allowed plan changes seed runs once, into an empty table, and never over an
 * operator's rows.
 *
 * <p>Like {@code EnsureLegacyPlanScriptTest}, the script is compiled from source into a temp
 * directory and driven through a mocked {@link ConnectionProvider}: no database, no build step.
 *
 * @covers com.etendoerp.go.modulescript.SeedAllowedPlanChangesScript
 */
class SeedAllowedPlanChangesScriptTest {

  private static final Path SCRIPT_RELATIVE = Paths.get("src-util", "modulescript", "src", "com",
      "etendoerp", "go", "modulescript", "SeedAllowedPlanChangesScript.java");

  @TempDir
  static Path classesDir;

  private static Path moduleRoot;
  private static Class<?> scriptClass;

  @BeforeAll
  static void compileScript() throws Exception {
    Path here = Paths.get("");
    Optional<Path> root = List.of(here, here.resolve(Paths.get("modules", "com.etendoerp.go")))
        .stream()
        .filter(candidate -> Files.isRegularFile(candidate.resolve(SCRIPT_RELATIVE)))
        .findFirst();
    Assumptions.assumeTrue(root.isPresent(),
        () -> "Skipping: com.etendoerp.go module root not found from " + here.toAbsolutePath());
    moduleRoot = root.orElseThrow();
    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    Assumptions.assumeTrue(compiler != null, "Skipping: no system Java compiler (JRE only)");

    int status = compiler.run(null, null, null, "-proc:none", "-encoding", "UTF-8", "-classpath",
        System.getProperty("java.class.path"), "-d", classesDir.toString(),
        moduleRoot.resolve(SCRIPT_RELATIVE).toString());
    assertEquals(0, status, "SeedAllowedPlanChangesScript.java must compile");

    URLClassLoader loader = new URLClassLoader(new URL[] { classesDir.toUri().toURL() },
        SeedAllowedPlanChangesScriptTest.class.getClassLoader());
    scriptClass = Class.forName("com.etendoerp.go.modulescript.SeedAllowedPlanChangesScript",
        true, loader);
  }

  private static ConnectionProvider providerWithRows(long existing) throws Exception {
    ConnectionProvider cp = mock(ConnectionProvider.class);
    PreparedStatement count = mock(PreparedStatement.class);
    ResultSet counted = mock(ResultSet.class);
    when(cp.getPreparedStatement(constant("COUNT_SQL"))).thenReturn(count);
    when(count.executeQuery()).thenReturn(counted);
    when(counted.next()).thenReturn(true);
    when(counted.getLong(1)).thenReturn(existing);
    return cp;
  }

  @Test
  void seedsAnEmptyTableFromTheCatalog() throws Exception {
    ConnectionProvider cp = providerWithRows(0L);
    PreparedStatement insert = mock(PreparedStatement.class);
    when(cp.getPreparedStatement(constant("SEED_SQL"))).thenReturn(insert);
    when(insert.executeUpdate()).thenReturn(4);

    assertEquals(4, seed(cp));

    verify(insert).executeUpdate();
  }

  @Test
  void leavesATableWithRowsAloneWhateverTheyAre() throws Exception {
    // One operator row — active or not — and the seed never adds, removes or re-adds anything.
    ConnectionProvider cp = providerWithRows(1L);

    assertEquals(0, seed(cp));

    verify(cp, never()).getPreparedStatement(constant("SEED_SQL"));
  }

  @Test
  void aCatalogWithNoTransitionSeedsNothingAndDoesNotFail() throws Exception {
    ConnectionProvider cp = providerWithRows(0L);
    PreparedStatement insert = mock(PreparedStatement.class);
    when(cp.getPreparedStatement(constant("SEED_SQL"))).thenReturn(insert);
    when(insert.executeUpdate()).thenReturn(0);

    assertEquals(0, seed(cp));
  }

  @Test
  void aFailingStatementPropagatesSoTheBuildFails() throws Exception {
    ConnectionProvider cp = providerWithRows(0L);
    PreparedStatement insert = mock(PreparedStatement.class);
    when(cp.getPreparedStatement(constant("SEED_SQL"))).thenReturn(insert);
    when(insert.executeUpdate()).thenThrow(new SQLException("relation does not exist"));

    InvocationTargetException thrown = Assertions.assertThrows(InvocationTargetException.class,
        () -> seedReflective(cp));
    assertTrue(thrown.getCause() instanceof SQLException);
  }

  @Test
  void theSeedFollowsTheTicketsRuleAndIsGuardedAgainstExistingRows() throws Exception {
    String sql = constant("SEED_SQL").replaceAll("\\s+", " ");
    assertTrue(sql.contains("AND NOT EXISTS (SELECT 1 FROM etgo_plan_change)"),
        "the insert must be a no-op once any row exists: " + sql);
    // Same interval: a lower tier is a downgrade (period_end), anything else seeded is immediate.
    assertTrue(sql.contains("f.billing_interval = t.billing_interval AND t.tier < f.tier"
        + " THEN 'period_end' ELSE 'immediate'"), sql);
    // Monthly to annual of the same or a higher tier; never annual to monthly.
    assertTrue(sql.contains("f.billing_interval = 'month' AND t.billing_interval = 'year'"
        + " AND t.tier >= f.tier"), sql);
    assertFalse(sql.contains("f.billing_interval = 'year' AND t.billing_interval = 'month'"), sql);
    // Only priced, active plans of the same currency: the legacy plan has no price.
    assertTrue(sql.contains("p.isactive = 'Y' AND p.provider_price_id IS NOT NULL"), sql);
    assertTrue(sql.contains("t.currency_code = f.currency_code"), sql);
    assertTrue(sql.contains("t.etgo_plan_id <> f.etgo_plan_id"), sql);
  }

  @Test
  void theCompiledScriptIsCommittedSoUpdateDatabaseRunsIt() {
    Path compiled = moduleRoot.resolve(Paths.get("build", "classes", "com", "etendoerp", "go",
        "modulescript", "SeedAllowedPlanChangesScript.class"));
    assertTrue(Files.isRegularFile(compiled),
        "update.database only runs compiled module scripts and a deploy never compiles them: run "
            + "./gradlew compile.modulescript -Dmodule=com.etendoerp.go and git add -f " + compiled);
  }

  private static String constant(String name) throws Exception {
    Field field = scriptClass.getDeclaredField(name);
    field.setAccessible(true);
    return (String) field.get(null);
  }

  private static int seed(ConnectionProvider cp) throws Exception {
    try {
      return seedReflective(cp);
    } catch (InvocationTargetException e) {
      throw (Exception) e.getCause();
    }
  }

  private static int seedReflective(ConnectionProvider cp) throws Exception {
    Object script = scriptClass.getDeclaredConstructor().newInstance();
    Method method = scriptClass.getDeclaredMethod("seed", ConnectionProvider.class);
    method.setAccessible(true);
    return (int) method.invoke(script, cp);
  }
}
