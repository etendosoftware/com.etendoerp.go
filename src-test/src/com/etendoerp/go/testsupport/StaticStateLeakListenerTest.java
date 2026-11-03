/* Etendo License. */
package com.etendoerp.go.testsupport;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Stack;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.openbravo.apachejdbcconnectionpool.JdbcExternalConnectionPool;
import org.openbravo.base.session.OBPropertiesProvider;
import org.openbravo.dal.core.OBContext;
import org.openbravo.database.ExternalConnectionPool;

/**
 * Reproduces the two JVM-wide leaks that forced DAL integration tests into their own JVM, and
 * checks that the listener names the leaking test and leaves the state as a fresh JVM would.
 *
 * @covers com.etendoerp.go.testsupport.StaticStateLeakListener
 */
public class StaticStateLeakListenerTest {

  private Object originalPool;

  @Before
  public void rememberPool() throws ReflectiveOperationException {
    originalPool = poolInstanceField().get(null);
  }

  @After
  public void restorePool() throws ReflectiveOperationException {
    poolInstanceField().set(null, originalPool);
    clearAdminStack();
  }

  @Test
  public void cleanStateReportsNothing() {
    assertTrue(StaticStateLeakListener.checkAndRepair("clean").isEmpty());
  }

  @Test
  public void unbalancedAdminModeIsReportedAgainstTheLeakingTestAndCleared()
      throws ReflectiveOperationException {
    Stack<Object> leaked = new Stack<>();
    leaked.push(new Object());
    adminStack().set(leaked);

    List<String> found = StaticStateLeakListener.checkAndRepair("LeakyTest#leaks");

    assertEquals(1, found.size());
    assertTrue(found.get(0).contains("admin mode"));
    assertNull("admin-mode stack still set on the worker thread", adminStack().get());
    assertTrue(StaticStateLeakListener.leaks().stream()
        .anyMatch(leak -> leak.startsWith("LeakyTest#leaks: admin mode")));
  }

  @Test
  public void poolPoisonedByAFirstInitUnderMockedPropertiesIsResetToUninitialized()
      throws ReflectiveOperationException {
    JdbcExternalConnectionPool pool = new JdbcExternalConnectionPool();
    poolInstanceField().set(null, pool);

    // The first initialization of the JVM runs while a test has OBPropertiesProvider mocked.
    try (MockedStatic<OBPropertiesProvider> provider = mockStatic(OBPropertiesProvider.class)) {
      provider.when(OBPropertiesProvider::getInstance).thenReturn(mock(OBPropertiesProvider.class));
      assertThrows(RuntimeException.class, pool::getConnection);
    }
    Field dataSources = JdbcExternalConnectionPool.class.getDeclaredField("availableDataSources");
    dataSources.setAccessible(true);
    Map<?, ?> poisoned = (Map<?, ?>) dataSources.get(pool);
    assertTrue("initPool() should leave an empty map behind: the bug this listener repairs",
        poisoned != null && poisoned.isEmpty());

    List<String> found = StaticStateLeakListener.checkAndRepair("PoisoningTest#firstConnection");

    assertEquals(1, found.size());
    assertTrue(found.get(0).contains("connection pool"));
    assertNull("the next getConnection() must run initPool() again", dataSources.get(pool));
  }

  @Test
  public void initializedPoolIsLeftAlone() throws ReflectiveOperationException {
    JdbcExternalConnectionPool pool = new JdbcExternalConnectionPool();
    poolInstanceField().set(null, pool);
    Field dataSources = JdbcExternalConnectionPool.class.getDeclaredField("availableDataSources");
    dataSources.setAccessible(true);
    Map<String, Object> healthy = new java.util.HashMap<>();
    healthy.put(ExternalConnectionPool.DEFAULT_POOL, new Object());
    dataSources.set(pool, healthy);

    assertFalse(StaticStateLeakListener.checkAndRepair("healthy").stream()
        .anyMatch(leak -> leak.contains("connection pool")));
    assertEquals(healthy, dataSources.get(pool));
  }

  private static Field poolInstanceField() throws NoSuchFieldException {
    Field field = ExternalConnectionPool.class.getDeclaredField("instance");
    field.setAccessible(true);
    return field;
  }

  @SuppressWarnings("unchecked")
  private static ThreadLocal<Object> adminStack() throws ReflectiveOperationException {
    Field field = OBContext.class.getDeclaredField("adminModeStack");
    field.setAccessible(true);
    return (ThreadLocal<Object>) field.get(null);
  }

  private static void clearAdminStack() throws ReflectiveOperationException {
    adminStack().remove();
  }
}
