/* Etendo License. */
package com.etendoerp.go.testsupport;

import java.util.concurrent.atomic.AtomicInteger;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.platform.launcher.LauncherSession;
import org.junit.platform.launcher.LauncherSessionListener;
import org.openbravo.base.provider.OBConfigFileProvider;
import org.openbravo.base.session.OBPropertiesProvider;

/**
 * Reads {@code Openbravo.properties} with the real providers once per test JVM, before any test
 * runs, so that no test can be the first one to read them under a static mock.
 *
 * <p><b>Why.</b> {@link OBPropertiesProvider#getOpenbravoProperties()} loads the file only once
 * ({@code if (obProperties == null)}): it stores the properties first and only then calls
 * {@code OBConfigFileProvider.getInstance().setFileLocation(...)}. {@link OBConfigFileProvider}
 * resolves itself through {@code OBProvider.getInstance()} and caches the result statically. When
 * the first properties read of a JVM happens inside a {@code mockStatic(OBProvider.class)} — which
 * code such as {@code ConfigPropertyReader}, {@code GoRuntimeProperties} or {@code GoFeatureFlags}
 * triggers and whose exception it swallows — either the location is never set (the mocked
 * provider returns {@code null}, the NPE is swallowed, and the properties are already marked as
 * loaded so nothing retries) or the cached {@code OBConfigFileProvider} is a mock. From then on
 * every {@code OBBaseTest}/{@code WeldBaseTest} forked into that JVM fails in {@code classSetUp}
 * with an NPE at {@code OBBaseTest.initializeDisabledTestCases} ({@code Paths.get(null, ...)}),
 * reported as {@code initializationError} of an innocent class. Which class pays depends only on
 * how Gradle distributes classes across workers, so it surfaces at random.</p>
 *
 * <p><b>How.</b> This listener is registered through
 * {@code META-INF/services/org.junit.platform.launcher.LauncherSessionListener} in this module's
 * test resources. The JUnit Platform launcher discovers it with the {@code ServiceLoader} of the
 * test runtime classpath and calls {@link #launcherSessionOpened} once per launcher session, which
 * in a Gradle test worker means once per JVM, before any test class is loaded. It therefore
 * protects <em>every</em> test run by the Etendo root {@code test} task (and its forked
 * isolated-test tasks), not only the tests of this module.</p>
 *
 * <p>It never fails a test JVM: on a machine without {@code Openbravo.properties} the provider
 * already no-ops, and any other problem is caught and logged.</p>
 *
 * <p>Guarded by {@code OpenbravoPropertiesPreloadListenerTest}.</p>
 */
public class OpenbravoPropertiesPreloadListener implements LauncherSessionListener {

  private static final Logger log = LogManager.getLogger();

  private static final AtomicInteger PRELOADS = new AtomicInteger();

  @Override
  public void launcherSessionOpened(LauncherSession session) {
    preload();
  }

  /** Runs the preload at most once per JVM; any failure is logged and swallowed. */
  static void preload() {
    if (PRELOADS.get() > 0) {
      return;
    }
    try {
      OBPropertiesProvider.getInstance().getOpenbravoProperties();
      // Pin the real config-file provider in the static cache as well.
      OBConfigFileProvider.getInstance();
      PRELOADS.incrementAndGet();
    } catch (Throwable t) { // NOSONAR a test-infrastructure hook must never break the JVM
      log.warn("Could not preload Openbravo.properties before the test session", t);
    }
  }

  /** How many times the preload completed in this JVM: 1 once the listener ran, 0 otherwise. */
  public static int preloadCount() {
    return PRELOADS.get();
  }
}
