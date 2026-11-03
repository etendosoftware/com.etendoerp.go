/* Etendo License. */
package com.etendoerp.go.testsupport;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;
import org.openbravo.dal.core.OBContext;
import org.openbravo.database.ExternalConnectionPool;

/**
 * Finds the test that leaks JVM-wide DAL state, names it, and repairs the state so the leak is not
 * charged to whichever {@code OBBaseTest}/{@code WeldBaseTest} class Gradle schedules next.
 *
 * <p><b>Why.</b> {@code docs/test-jvm-isolation.md} describes two leaks that made DAL integration
 * tests fail when they shared a JVM with mocked tests, and that forced them into
 * {@code goIsolatedDalTest} ({@code forkEvery = 1}):</p>
 * <ul>
 * <li><b>Admin mode.</b> {@code OBContext.adminModeStack} is a static {@code ThreadLocal} that
 * {@code OBContext.setOBContext()} does not reset, and Gradle runs every class on one worker
 * thread. A test that calls {@code setAdminMode()} without its {@code restorePreviousMode()} fails
 * the next {@code OBBaseTest} in {@code TestWatcher.finished()}.</li>
 * <li><b>Connection pool.</b> {@code JdbcExternalConnectionPool.initPool()} assigns
 * {@code availableDataSources} <em>before</em> reading the pool properties. If that first read
 * throws (a test runs it with {@code OBPropertiesProvider} mocked), the map stays empty, every
 * later {@code getConnection()} skips the initialization and DAL setup dies with
 * {@code "datasource" is null}.</li>
 * </ul>
 *
 * <p><b>How.</b> Registered through
 * {@code META-INF/services/org.junit.platform.launcher.TestExecutionListener}, it runs on the test
 * worker thread after every test and container finishes, so the thread-local stacks it reads are
 * the ones the finished test left. Both checks use reflection on the real fields, never the static
 * methods, because a class-level {@code mockStatic(OBContext.class)} may still be open.</p>
 *
 * <p>This is not the defensive {@code clearAdminModeStack()} in {@code @Before} that the doc rules
 * out: an {@code OBBaseTest} still fails on its own leak, because its {@code TestWatcher} runs
 * before this listener. What changes is that a leak from a mocked test is reported against that
 * test, with the {@value #MARKER} marker, instead of failing an unrelated class.</p>
 *
 * <p>Guarded by {@code StaticStateLeakListenerTest}.</p>
 */
public class StaticStateLeakListener implements TestExecutionListener {

  /** Grep-able prefix of every leak report in the test logs. */
  public static final String MARKER = "STATIC-STATE-LEAK";

  private static final Logger log = LogManager.getLogger();

  private static final List<String> LEAKS = Collections.synchronizedList(new ArrayList<>());

  @Override
  public void executionFinished(TestIdentifier testIdentifier,
      TestExecutionResult testExecutionResult) {
    String source = testIdentifier.getLegacyReportingName() + " [" + testIdentifier.getUniqueId()
        + "]";
    checkAndRepair(source);
  }

  @Override
  public void testPlanExecutionFinished(TestPlan testPlan) {
    if (!LEAKS.isEmpty()) {
      log.warn("{} {} leak(s) repaired in this JVM: {}", MARKER, LEAKS.size(), LEAKS);
    }
  }

  /**
   * Repairs any leaked state left on the current thread and reports it against {@code source}.
   *
   * @return the leaks found, empty when the state was clean
   */
  static List<String> checkAndRepair(String source) {
    List<String> found = new ArrayList<>();
    try {
      if (repairAdminMode()) {
        found.add("admin mode left set (unbalanced setAdminMode/restorePreviousMode)");
      }
      if (repairConnectionPool()) {
        found.add("connection pool left without its default datasource");
      }
    } catch (Throwable t) { // NOSONAR a test-infrastructure hook must never break the JVM
      log.warn("Could not check static DAL state after {}", source, t);
    }
    for (String leak : found) {
      String report = source + ": " + leak;
      LEAKS.add(report);
      log.error("{} {}", MARKER, report);
    }
    return found;
  }

  /** Leaks reported so far in this JVM. */
  public static List<String> leaks() {
    synchronized (LEAKS) {
      return new ArrayList<>(LEAKS);
    }
  }

  private static boolean repairAdminMode() throws ReflectiveOperationException {
    boolean leaked = false;
    for (String stackField : new String[] { "adminModeStack", "crossOrgAdminModeStack" }) {
      Object stack = threadLocal(stackField).get();
      if (stack instanceof Collection && !((Collection<?>) stack).isEmpty()) {
        leaked = true;
      }
    }
    if (!leaked) {
      return false;
    }
    Object adminContext = staticField(OBContext.class, "adminContext");
    ThreadLocal<?> current = threadLocal("instance");
    if (adminContext != null && current.get() == adminContext) {
      // setAdminMode() with no context installs the admin one; restorePreviousMode() would drop it.
      current.remove();
    }
    for (String field : new String[] { "adminModeStack", "crossOrgAdminModeStack",
        "adminModeTrace", "crossOrgAdminModeTrace", "adminModeSet" }) {
      threadLocal(field).remove();
    }
    return true;
  }

  private static boolean repairConnectionPool() throws ReflectiveOperationException {
    Object pool = staticField(ExternalConnectionPool.class, "instance");
    if (pool == null) {
      return false;
    }
    Field dataSources = findField(pool.getClass(), "availableDataSources");
    if (dataSources == null) {
      return false;
    }
    Map<?, ?> map = (Map<?, ?>) dataSources.get(pool);
    if (map == null || map.containsKey(ExternalConnectionPool.DEFAULT_POOL)) {
      return false;
    }
    // Back to "never initialized": the next getConnection() runs initPool() again.
    dataSources.set(pool, null);
    Field defaultDataSource = findField(pool.getClass(), "defaultDataSource");
    if (defaultDataSource != null) {
      defaultDataSource.set(pool, null);
    }
    return true;
  }

  private static ThreadLocal<?> threadLocal(String name) throws ReflectiveOperationException {
    return (ThreadLocal<?>) staticField(OBContext.class, name);
  }

  private static Object staticField(Class<?> owner, String name)
      throws ReflectiveOperationException {
    Field field = owner.getDeclaredField(name);
    field.setAccessible(true);
    return field.get(null);
  }

  private static Field findField(Class<?> type, String name) {
    for (Class<?> c = type; c != null; c = c.getSuperclass()) {
      try {
        Field field = c.getDeclaredField(name);
        field.setAccessible(true);
        return field;
      } catch (NoSuchFieldException e) {
        // keep walking up the hierarchy
      }
    }
    return null;
  }
}
