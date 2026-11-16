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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.Optional;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openbravo.database.ConnectionProvider;
import org.openbravo.modulescript.ModuleScript;

/**
 * Unit tests for {@code EnsureDocumentStatusTranslationsScript}, the module script that writes the
 * Spanish names of the GO document statuses ({@code ETGO_CI}, {@code ETGO_AC}) on every
 * {@code update.database}.
 *
 * <p>Module scripts are not on the test classpath, so, like {@code EnsureLegacyPlanScriptTest},
 * this class compiles the script's real source (and the {@code EnsureSupportAccessSeedScript} it
 * shares the {@code _TRL} SQL with) in memory and drives it with a mocked
 * {@link ConnectionProvider}.</p>
 *
 * @covers com.etendoerp.go.modulescript.EnsureDocumentStatusTranslationsScript
 */
class EnsureDocumentStatusTranslationsScriptTest {

  private static final Path SCRIPTS = Paths.get("src-util", "modulescript", "src", "com",
      "etendoerp", "go", "modulescript");
  private static final String ACCEPTED_ID = "7BD5D4F5C69F426C879D5F22823C6C3D";
  private static final String INVOICE_CREATED_ID = "359305FF926B499EA3E6D64C86039B18";

  @TempDir
  static Path classesDir;

  private static Path moduleRoot;
  private static ClassLoader loader;

  @BeforeAll
  static void compileScripts() throws Exception {
    Path here = Paths.get("");
    Optional<Path> root = List.of(here, here.resolve(Paths.get("modules", "com.etendoerp.go")))
        .stream()
        .filter(candidate -> Files.isRegularFile(candidate.resolve(SCRIPTS)
            .resolve("EnsureDocumentStatusTranslationsScript.java")))
        .findFirst();
    Assumptions.assumeTrue(root.isPresent(),
        () -> "Skipping: com.etendoerp.go module root not found from " + here.toAbsolutePath());
    moduleRoot = root.orElseThrow();
    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    Assumptions.assumeTrue(compiler != null, "Skipping: no system Java compiler (JRE only)");

    int status = compiler.run(null, null, null, "-proc:none", "-encoding", "UTF-8", "-classpath",
        System.getProperty("java.class.path"), "-d", classesDir.toString(),
        moduleRoot.resolve(SCRIPTS).resolve("EnsureDocumentStatusTranslationsScript.java")
            .toString(),
        moduleRoot.resolve(SCRIPTS).resolve("EnsureSupportAccessSeedScript.java").toString());
    assertEquals(0, status, "the module scripts must compile");
    loader = new URLClassLoader(new URL[] { classesDir.toUri().toURL() },
        EnsureDocumentStatusTranslationsScriptTest.class.getClassLoader());
  }

  @Test
  void writesBothStatusNamesForEachSpanishLanguage() throws Exception {
    ConnectionProvider cp = mock(ConnectionProvider.class);
    PreparedStatement insert = mock(PreparedStatement.class);
    PreparedStatement update = mock(PreparedStatement.class);
    when(cp.getPreparedStatement(argThat(sql -> sql != null && sql.startsWith("INSERT"))))
        .thenReturn(insert);
    when(cp.getPreparedStatement(argThat(sql -> sql != null && sql.startsWith("UPDATE"))))
        .thenReturn(update);

    run(cp);

    // 2 languages x 2 statuses, each an insert-if-missing and an update-if-not-user-edited.
    verify(insert, times(4)).executeUpdate();
    verify(update, times(4)).executeUpdate();
    for (String language : List.of("es_ES", "es_AR")) {
      verify(insert, times(2)).setString(2, language);
      verify(update, times(2)).setString(3, language);
    }
    verify(insert, times(2)).setString(1, "Aceptado");
    verify(insert, times(2)).setString(3, ACCEPTED_ID);
    verify(insert, times(2)).setString(1, "Cerrado - Factura creada");
    verify(insert, times(2)).setString(3, INVOICE_CREATED_ID);
    verify(update, times(2)).setString(2, ACCEPTED_ID);
    // The update only writes a text that differs (fourth parameter).
    verify(update, times(2)).setString(4, "Aceptado");
  }

  @Test
  void insertsOnlyForASystemLanguageAndNeverOverwritesAUserTranslation() throws Exception {
    Object refList = refListTable();
    String insert = (String) field(refList, "insertSql");
    String update = (String) field(refList, "updateSql");

    assertTrue(insert.startsWith("INSERT INTO ad_ref_list_trl"), insert);
    assertTrue(insert.contains("l.issystemlanguage = 'Y'"), insert);
    assertTrue(insert.contains("ON CONFLICT DO NOTHING"), insert);
    assertTrue(update.startsWith("UPDATE ad_ref_list_trl"), update);
    assertTrue(update.contains("(istranslated = 'N' OR updatedby = '0')"),
        "a translation edited by a user is never overwritten: " + update);
  }

  @Test
  void theIdsAreTheStatusesTheScriptNames() throws Exception {
    String refList = Files.readString(moduleRoot.resolve(Paths.get("src-db", "database",
        "sourcedata", "AD_REF_LIST.xml")), StandardCharsets.UTF_8);
    assertTrue(refList.contains("<!--" + ACCEPTED_ID + "-->  <VALUE><![CDATA[ETGO_AC]]>"));
    assertTrue(refList.contains("<!--" + INVOICE_CREATED_ID + "-->  <VALUE><![CDATA[ETGO_CI]]>"));
  }

  private static void run(ConnectionProvider cp) throws Exception {
    Class<?> scriptClass = Class.forName(
        "com.etendoerp.go.modulescript.EnsureDocumentStatusTranslationsScript", true, loader);
    ModuleScript script = (ModuleScript) scriptClass.getDeclaredConstructor().newInstance();
    // getConnectionProvider() returns the cached provider when one is set.
    Field cached = findField(script.getClass(), "cp");
    cached.setAccessible(true);
    cached.set(script, cp);
    script.execute();
  }

  private static Object refListTable() throws Exception {
    Class<?> tables = Class.forName(
        "com.etendoerp.go.modulescript.EnsureSupportAccessSeedScript$TrlTable", true, loader);
    for (Object constant : tables.getEnumConstants()) {
      if ("REF_LIST".equals(((Enum<?>) constant).name())) {
        return constant;
      }
    }
    throw new AssertionError("TrlTable.REF_LIST not found");
  }

  private static Object field(Object target, String name) throws Exception {
    Field field = target.getClass().getDeclaredField(name);
    field.setAccessible(true);
    return field.get(target);
  }

  private static Field findField(Class<?> type, String name) throws NoSuchFieldException {
    for (Class<?> current = type; current != null; current = current.getSuperclass()) {
      try {
        return current.getDeclaredField(name);
      } catch (NoSuchFieldException e) {
        // keep looking up the hierarchy
      }
    }
    throw new NoSuchFieldException(name);
  }
}
