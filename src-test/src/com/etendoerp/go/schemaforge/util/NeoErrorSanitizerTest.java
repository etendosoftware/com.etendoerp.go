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

package com.etendoerp.go.schemaforge.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/**
 * Unit tests for {@link NeoErrorSanitizer}.
 *
 * <p>DB-pattern detection relies on {@link Class#getName()}, so fake inner
 * classes whose names contain the relevant substrings are used in place of
 * real driver/ORM dependencies.</p>
 */
public class NeoErrorSanitizerTest {

  private static final String GENERIC = NeoErrorSanitizer.GENERIC_DB_ERROR;

  @Test
  public void sanitize_null_returnsGeneric() {
    assertEquals(GENERIC, NeoErrorSanitizer.sanitize(null));
  }

  @Test
  public void sanitize_plainException_returnsOriginalMessage() {
    assertEquals("boom", NeoErrorSanitizer.sanitize(new RuntimeException("boom")));
  }

  @Test
  public void sanitize_nullMessage_returnsNull() {
    assertNull(NeoErrorSanitizer.sanitize(new RuntimeException()));
  }

  @Test
  public void sanitize_sqlException_returnsGeneric() {
    assertEquals(GENERIC, NeoErrorSanitizer.sanitize(new FakeSQLException()));
  }

  @Test
  public void sanitize_psqlException_returnsGeneric() {
    assertEquals(GENERIC, NeoErrorSanitizer.sanitize(new FakePSQLException()));
  }

  @Test
  public void sanitize_hibernateException_returnsGeneric() {
    assertEquals(GENERIC, NeoErrorSanitizer.sanitize(new FakeHibernateException()));
  }

  @Test
  public void sanitize_jdbcException_returnsGeneric() {
    assertEquals(GENERIC, NeoErrorSanitizer.sanitize(new FakeJDBCException()));
  }

  @Test
  public void sanitize_persistenceException_returnsGeneric() {
    assertEquals(GENERIC, NeoErrorSanitizer.sanitize(new FakePersistenceException()));
  }

  @Test
  public void sanitize_dataAccessException_returnsGeneric() {
    assertEquals(GENERIC, NeoErrorSanitizer.sanitize(new FakeDataAccessException()));
  }

  @Test
  public void sanitize_transactionException_returnsGeneric() {
    assertEquals(GENERIC, NeoErrorSanitizer.sanitize(new FakeTransactionException()));
  }

  @Test
  public void sanitize_dbExceptionWrappedInRuntimeException_walksChain() {
    RuntimeException wrapper = new RuntimeException("outer message", new FakeSQLException());
    assertEquals(GENERIC, NeoErrorSanitizer.sanitize(wrapper));
  }

  @Test
  public void sanitize_deeplyNestedDbException_walksFullChain() {
    RuntimeException level3 = new RuntimeException("level3", new FakeHibernateException());
    RuntimeException level2 = new RuntimeException("level2", level3);
    RuntimeException level1 = new RuntimeException("level1", level2);
    assertEquals(GENERIC, NeoErrorSanitizer.sanitize(level1));
  }

  // Inner classes whose names contain the patterns checked by isDbException.
  // getName() returns the binary name, e.g. "...NeoErrorSanitizerTest$FakeSQLException",
  // which contains "SQLException" as a substring.
  private static class FakeSQLException extends RuntimeException {}
  private static class FakePSQLException extends RuntimeException {}
  private static class FakeHibernateException extends RuntimeException {}
  private static class FakeJDBCException extends RuntimeException {}
  private static class FakePersistenceException extends RuntimeException {}
  private static class FakeDataAccessException extends RuntimeException {}
  private static class FakeTransactionException extends RuntimeException {}
}
