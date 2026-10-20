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
package com.etendoerp.go.modulescript;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
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

import com.etendoerp.go.payment.PlanCatalogService;

/**
 * Unit tests for {@code EnsureLegacyPlanScript}, the module script that seeds the grandfathered
 * {@code legacy-productive} plan now that {@code ETGO_PLAN} is no longer an AD dataset table.
 *
 * <p>Module scripts are compiled into {@code build/classes} by {@code compile.modulescript}, which
 * is not on the test classpath. So this class compiles the script's real source in memory against
 * the test classpath and drives it with a mocked {@link ConnectionProvider} — no DB, no build
 * step, and a compile error in the script fails here.</p>
 */
class EnsureLegacyPlanScriptTest {

  private static final Path SCRIPT_RELATIVE = Paths.get("src-util", "modulescript", "src", "com",
      "etendoerp", "go", "modulescript", "EnsureLegacyPlanScript.java");
  private static final String ETGO_PLAN_TABLE_ID = "5E186369E6CF46ADA79889C8FFD4D3C4";

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
    assertEquals(0, status, "EnsureLegacyPlanScript.java must compile");

    URLClassLoader loader = new URLClassLoader(new URL[] { classesDir.toUri().toURL() },
        EnsureLegacyPlanScriptTest.class.getClassLoader());
    scriptClass = Class.forName("com.etendoerp.go.modulescript.EnsureLegacyPlanScript", true,
        loader);
  }

  @Test
  void insertsTheLegacyPlanWhenItIsMissing() throws Exception {
    ConnectionProvider cp = mock(ConnectionProvider.class);
    PreparedStatement insert = mock(PreparedStatement.class);
    when(cp.getPreparedStatement(constant("INSERT_IF_MISSING_SQL"))).thenReturn(insert);
    when(insert.executeUpdate()).thenReturn(1);

    assertEquals(1, ensureLegacyPlan(cp));

    verify(insert, times(1)).executeUpdate();
    verify(insert).setString(1, "219D5C8E15C64E97B2F553B228D30DD0");
    verify(insert).setString(2, PlanCatalogService.LEGACY_PLAN_KEY);
    verify(insert).setString(3, "Legacy Productive (grandfathered)");
    verify(insert).setString(4, constant("LEGACY_PLAN_DESCRIPTION"));
    verify(insert).setString(5, PlanCatalogService.LEGACY_PLAN_KEY);
    verify(insert).setString(6, "219D5C8E15C64E97B2F553B228D30DD0");
    verify(cp, never()).getPreparedStatement(constant("KEY_EXISTS_SQL"));
  }

  @Test
  void leavesAnExistingLegacyPlanAlone() throws Exception {
    ConnectionProvider cp = mock(ConnectionProvider.class);
    PreparedStatement insert = mock(PreparedStatement.class);
    PreparedStatement keyLookup = mock(PreparedStatement.class);
    ResultSet found = mock(ResultSet.class);
    when(cp.getPreparedStatement(constant("INSERT_IF_MISSING_SQL"))).thenReturn(insert);
    when(cp.getPreparedStatement(constant("KEY_EXISTS_SQL"))).thenReturn(keyLookup);
    when(insert.executeUpdate()).thenReturn(0);
    when(keyLookup.executeQuery()).thenReturn(found);
    when(found.next()).thenReturn(true);

    assertEquals(0, ensureLegacyPlan(cp));

    verify(insert, times(1)).executeUpdate();
    verify(keyLookup).setString(1, PlanCatalogService.LEGACY_PLAN_KEY);
  }

  @Test
  void aFailingStatementPropagatesSoTheBuildFails() throws Exception {
    ConnectionProvider cp = mock(ConnectionProvider.class);
    PreparedStatement insert = mock(PreparedStatement.class);
    when(cp.getPreparedStatement(anyString())).thenReturn(insert);
    when(insert.executeUpdate()).thenThrow(new SQLException("relation does not exist"));

    InvocationTargetException thrown = Assertions.assertThrows(InvocationTargetException.class,
        () -> ensureLegacyPlanReflective(cp));
    assertTrue(thrown.getCause() instanceof SQLException);
  }

  @Test
  void theInsertIsGuardedOnBothTheKeyAndTheId() throws Exception {
    String sql = constant("INSERT_IF_MISSING_SQL").replaceAll("\\s+", " ");
    assertTrue(sql.contains("WHERE NOT EXISTS (SELECT 1 FROM ETGO_Plan WHERE Value = ? OR "
        + "ETGO_Plan_ID = ?)"), "the insert must never hit ETGO_PLAN_VALUE_UQ or the PK: " + sql);
    assertFalse(sql.toLowerCase().contains("provider_price_id"),
        "the grandfathered plan is unpriced by design");
  }

  @Test
  void theScriptKeyMatchesTheCanonicalLegacyPlanKey() throws Exception {
    assertEquals(PlanCatalogService.LEGACY_PLAN_KEY, constant("LEGACY_PLAN_KEY"),
        "EnsureLegacyPlanScript carries a literal copy of PlanCatalogService.LEGACY_PLAN_KEY");
  }

  @Test
  void etgoPlanIsNeverShippedAsSourcedata() throws Exception {
    Path sourcedata = moduleRoot.resolve(Paths.get("src-db", "database", "sourcedata"));
    String datasetTables = Files.readString(sourcedata.resolve("AD_DATASET_TABLE.xml"),
        StandardCharsets.UTF_8);
    assertFalse(datasetTables.contains(ETGO_PLAN_TABLE_ID),
        "ETGO_PLAN must never be an AD dataset table: runtime plans would block update.database "
            + "and -Dforce would delete them. EnsureLegacyPlanScript seeds the legacy row.");
    assertFalse(Files.exists(sourcedata.resolve("ETGO_PLAN.xml")),
        "ETGO_PLAN rows must not ship as sourcedata");
  }

  @Test
  void theCompiledScriptIsCommittedSoUpdateDatabaseRunsIt() {
    Path compiled = moduleRoot.resolve(Paths.get("build", "classes", "com", "etendoerp", "go",
        "modulescript", "EnsureLegacyPlanScript.class"));
    assertTrue(Files.isRegularFile(compiled),
        "update.database only runs compiled module scripts and a deploy never compiles them: run "
            + "./gradlew compile.modulescript -Dmodule=com.etendoerp.go and git add -f " + compiled);
  }

  private static String constant(String name) throws Exception {
    Field field = scriptClass.getDeclaredField(name);
    field.setAccessible(true);
    return (String) field.get(null);
  }

  private static int ensureLegacyPlan(ConnectionProvider cp) throws Exception {
    try {
      return ensureLegacyPlanReflective(cp);
    } catch (InvocationTargetException e) {
      throw (Exception) e.getCause();
    }
  }

  private static int ensureLegacyPlanReflective(ConnectionProvider cp) throws Exception {
    Object script = scriptClass.getDeclaredConstructor().newInstance();
    Method method = scriptClass.getDeclaredMethod("ensureLegacyPlan", ConnectionProvider.class);
    method.setAccessible(true);
    return (int) method.invoke(script, cp);
  }
}
