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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

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

  @Test
  public void sanitize_uniqueViolationSqlState_returnsDuplicateKeyError() {
    java.sql.SQLException uniqueViolation = new java.sql.SQLException(
        "duplicate key value violates unique constraint \"c_bpartner_value\"", "23505");
    // Precedence: a real SQLException also matches the generic name-based isDbException
    // check (its class name contains "SQLException"), but the more specific
    // unique-violation check must win over the generic fallback.
    assertEquals(NeoErrorSanitizer.DUPLICATE_KEY_ERROR, NeoErrorSanitizer.sanitize(uniqueViolation));
  }

  @Test
  public void sanitize_nonUniqueViolationSqlState_fallsBackToGeneric() {
    java.sql.SQLException foreignKeyViolation = new java.sql.SQLException(
        "insert or update on table violates foreign key constraint", "23503");
    assertEquals(GENERIC, NeoErrorSanitizer.sanitize(foreignKeyViolation));
  }

  @Test
  public void sanitize_uniqueViolationWrappedInRuntimeException_walksChain() {
    java.sql.SQLException uniqueViolation = new java.sql.SQLException(
        "duplicate key value violates unique constraint \"c_bpartner_value\"", "23505");
    RuntimeException wrapper = new RuntimeException("outer message", uniqueViolation);
    assertEquals(NeoErrorSanitizer.DUPLICATE_KEY_ERROR, NeoErrorSanitizer.sanitize(wrapper));
  }

  @Test
  public void duplicateKeyError_containsMustBeUniquePhrase() {
    // NeoCrudHandler picks HTTP 409 vs 500 using isDuplicateKeyViolation(), but the import
    // UI's own classification (importEngine.js's isDuplicateKeyError) matches this exact
    // message text against /must be unique/i — if this phrase ever drifts, a duplicate row
    // hitting this fallback path would be misclassified as a hard failure instead of a
    // skippable "already exists" duplicate.
    assertTrue(NeoErrorSanitizer.DUPLICATE_KEY_ERROR.toLowerCase().contains("must be unique"));
  }

  @Test
  public void isDuplicateKeyViolation_null_returnsFalse() {
    assertFalse(NeoErrorSanitizer.isDuplicateKeyViolation(null));
  }

  @Test
  public void isDuplicateKeyViolation_plainException_returnsFalse() {
    assertFalse(NeoErrorSanitizer.isDuplicateKeyViolation(new RuntimeException("boom")));
  }

  @Test
  public void isDuplicateKeyViolation_uniqueViolationSqlState_returnsTrue() {
    java.sql.SQLException uniqueViolation = new java.sql.SQLException(
        "duplicate key value violates unique constraint \"c_bpartner_value\"", "23505");
    assertTrue(NeoErrorSanitizer.isDuplicateKeyViolation(uniqueViolation));
  }

  @Test
  public void isDuplicateKeyViolation_nonUniqueViolationSqlState_returnsFalse() {
    java.sql.SQLException foreignKeyViolation = new java.sql.SQLException(
        "insert or update on table violates foreign key constraint", "23503");
    assertFalse(NeoErrorSanitizer.isDuplicateKeyViolation(foreignKeyViolation));
  }

  @Test
  public void isDuplicateKeyViolation_wrappedInRuntimeException_walksChain() {
    java.sql.SQLException uniqueViolation = new java.sql.SQLException(
        "duplicate key value violates unique constraint \"c_bpartner_value\"", "23505");
    RuntimeException wrapper = new RuntimeException("outer message", uniqueViolation);
    assertTrue(NeoErrorSanitizer.isDuplicateKeyViolation(wrapper));
  }

  // ── redactObjectReferences — object-toString leak stripping (ETP-4668) ──────

  @Test
  public void redactObjectReferences_null_returnsNull() {
    assertNull(NeoErrorSanitizer.redactObjectReferences(null));
  }

  @Test
  public void redactObjectReferences_listReferenceLeak_isStripped() {
    String leaked = "value is not valid, it should be one of the following values: "
        + "com.etendoerp.redis.interfaces.CachedSet@55b0cf12 but it is value 0";
    String safe = NeoErrorSanitizer.redactObjectReferences(leaked);
    assertFalse("raw object reference must not survive", safe.contains("CachedSet@55b0cf12"));
    assertFalse("package path must not survive", safe.contains("com.etendoerp.redis"));
    assertTrue("surrounding message must be preserved",
        safe.contains("one of the following values:") && safe.contains("but it is value 0"));
    assertEquals("value is not valid, it should be one of the following values: "
        + NeoErrorSanitizer.REDACTED_OBJECT + " but it is value 0", safe);
  }

  @Test
  public void redactObjectReferences_innerClassLeak_isStripped() {
    String leaked = "rejected: org.openbravo.dal.core.OBContext$Session@1a2b3c4d";
    String safe = NeoErrorSanitizer.redactObjectReferences(leaked);
    assertFalse(safe.contains("@1a2b3c4d"));
    assertEquals("rejected: " + NeoErrorSanitizer.REDACTED_OBJECT, safe);
  }

  @Test
  public void redactObjectReferences_cleanMessage_isUnchanged() {
    String clean = "Business Partner value must be unique.";
    assertEquals(clean, NeoErrorSanitizer.redactObjectReferences(clean));
  }

  @Test
  public void redactObjectReferences_emailAndVersion_notFalsePositives() {
    String msg = "contact user@example.com about release 1.2.3 built earlier";
    assertEquals(msg, NeoErrorSanitizer.redactObjectReferences(msg));
  }

  @Test
  public void sanitize_plainExceptionWithObjectLeak_isRedacted() {
    String leaked = "should be one of the following values: "
        + "com.etendoerp.redis.interfaces.CachedSet@55b0cf12 but it is value 0";
    String safe = NeoErrorSanitizer.sanitize(new RuntimeException(leaked));
    assertFalse(safe.contains("CachedSet@55b0cf12"));
    assertTrue(safe.contains(NeoErrorSanitizer.REDACTED_OBJECT));
  }

  // ─── not-null violation detection (ETP-4793 / IMP-17, absorbing IMP-23 §9.4) ───

  private static final String NOT_NULL_MESSAGE =
      "ERROR: null value in column \"c_bpartner_location_id\" of relation \"c_invoice\" "
          + "violates not-null constraint";

  @Test
  public void isNotNullViolation_sqlStateInCauseChain_isDetected() {
    Throwable root = new java.sql.SQLException("boom", "23502");
    assertTrue(NeoErrorSanitizer.isNotNullViolation(new RuntimeException("wrapper", root)));
  }

  @Test
  public void isNotNullViolation_otherSqlState_isNotDetected() {
    assertFalse(NeoErrorSanitizer.isNotNullViolation(
        new java.sql.SQLException("duplicate", "23505")));
  }

  @Test
  public void isNotNullViolation_null_isFalse() {
    assertFalse(NeoErrorSanitizer.isNotNullViolation(null));
  }

  /**
   * The SQLState is language-independent, so it is checked first; the English wording is a
   * best-effort refinement for the swallowed-exception path, where only a message survives.
   */
  @Test
  public void isNotNullViolationMessage_sqlStateOrWording_isDetected() {
    assertTrue(NeoErrorSanitizer.isNotNullViolationMessage(NOT_NULL_MESSAGE));
    assertTrue(NeoErrorSanitizer.isNotNullViolationMessage("SQL error 23502 on insert"));
  }

  @Test
  public void isNotNullViolationMessage_unrelatedMessage_isNotDetected() {
    assertFalse(NeoErrorSanitizer.isNotNullViolationMessage("Business Partner must be unique."));
    assertFalse(NeoErrorSanitizer.isNotNullViolationMessage(null));
  }

  @Test
  public void notNullViolationColumn_fromMessage_isLowerCased() {
    assertEquals("c_bpartner_location_id",
        NeoErrorSanitizer.notNullViolationColumn(NOT_NULL_MESSAGE));
    assertEquals("c_bpartner_location_id", NeoErrorSanitizer.notNullViolationColumn(
        "null value in column \"C_BPartner_Location_ID\" violates not-null constraint"));
  }

  /**
   * The message core actually hands us is HTML-escaped, so the quote around the column name arrives
   * as {@code &quot;}. This is not a hypothetical: the first live probe of the IMP-23 §9.4 vector
   * returned the 500 it was meant to replace, because the pattern required a bare {@code "}, found
   * no column, could not resolve a field, and fell through. §9.4 had even recorded the escaping —
   * it just was not read as load-bearing. Verbatim from that response.
   */
  @Test
  public void notNullViolationColumn_htmlEscapedQuotes_isFound() {
    String live = "ERROR: null value in column &quot;c_bpartner_location_id&quot; of relation "
        + "&quot;c_invoice&quot; violates not-null constraint\n  Detail: Failing row contains (…).";
    assertEquals("c_bpartner_location_id", NeoErrorSanitizer.notNullViolationColumn(live));
    assertTrue(NeoErrorSanitizer.isNotNullViolationMessage(live));
  }

  @Test
  public void notNullViolationColumn_messageNamingNoColumn_isNull() {
    assertNull(NeoErrorSanitizer.notNullViolationColumn("something else failed"));
    assertNull(NeoErrorSanitizer.notNullViolationColumn((String) null));
  }

  /**
   * The throwable overload exists because {@link NeoErrorSanitizer#sanitize} maps any DB exception to
   * a generic message: the raw cause chain is the only place the column name survives.
   */
  @Test
  public void notNullViolationColumn_fromCauseChain_isFound() {
    Throwable root = new java.sql.SQLException(NOT_NULL_MESSAGE, "23502");
    assertEquals("c_bpartner_location_id",
        NeoErrorSanitizer.notNullViolationColumn(new RuntimeException("insert failed", root)));
    assertNull(NeoErrorSanitizer.notNullViolationColumn((Throwable) null));
  }

  // ─── row-dump stripping (ETP-4793 / IMP-17, absorbing IMP-23 §9.4) ───

  @Test
  public void stripRowDump_failingRowDetail_isReplaced() {
    String dumped = "not-null violation  Detail: Failing row contains ("
        + "0123456789".repeat(30) + ").";
    String safe = NeoErrorSanitizer.stripRowDump(dumped);
    assertFalse(safe.contains("0123456789"));
    assertTrue(safe.contains(NeoErrorSanitizer.REDACTED_ROW));
  }

  /**
   * Keyed on the shape of the leak, not on the localised {@code Failing row contains} lead-in — but a
   * short parenthetical is ordinary prose and must survive.
   */
  @Test
  public void stripRowDump_shortParenthetical_isUnchanged() {
    String clean = "(Client, Organization, Search Key) must be unique.";
    assertEquals(clean, NeoErrorSanitizer.stripRowDump(clean));
    assertNull(NeoErrorSanitizer.stripRowDump(null));
  }

  @Test
  public void sanitize_nonDbExceptionCarryingRowDump_isStripped() {
    String dumped = "insert failed  Detail: Failing row contains (" + "x".repeat(250) + ").";
    String safe = NeoErrorSanitizer.sanitize(new RuntimeException(dumped));
    assertFalse(safe.contains("xxxx"));
    assertTrue(safe.contains(NeoErrorSanitizer.REDACTED_ROW));
  }

  // ─── optimistic-locking code detection (ETP-5073 / DOC-04) ───

  /**
   * The literal code is spelled out here rather than read off the class: what this method has to
   * survive is core emitting exactly this token, so the test pins the token, not our copy of it.
   */
  private static final String STALE_CODE = "OBJSON_StaleDate";

  @Test
  public void isStaleRecordMessage_rawCode_matches() {
    assertTrue(NeoErrorSanitizer.isStaleRecordMessage(STALE_CODE));
  }

  /**
   * On the one path where the code survives — a stateless request, where
   * {@code convertExceptionToJson} skips translation — it arrives embedded in the exception's own
   * message rather than alone, so a substring match is what is needed.
   */
  @Test
  public void isStaleRecordMessage_codeEmbeddedInABody_matches() {
    assertTrue(NeoErrorSanitizer.isStaleRecordMessage(
        "org.openbravo.base.exception.OBStaleObjectException: OBJSON_StaleDate"));
  }

  /**
   * Deliberately narrow: the method must NOT try to recognise the translated sentence. Matching
   * prose is what failed twice against a live server, and it would break the moment the session
   * language changes — which is precisely the situation this check guards. The real detection now
   * lives in {@code NeoRecordVersion}, which compares timestamps and needs no message at all.
   */
  @Test
  public void isStaleRecordMessage_translatedProse_doesNotMatch() {
    assertFalse(NeoErrorSanitizer.isStaleRecordMessage(
        "The record has been changed by another user. Please refresh and try again."));
    assertFalse(NeoErrorSanitizer.isStaleRecordMessage(
        "El registro ha sido modificado por otro usuario."));
  }

  @Test
  public void isStaleRecordMessage_nullOrUnrelated_doesNotMatch() {
    assertFalse(NeoErrorSanitizer.isStaleRecordMessage(null));
    assertFalse(NeoErrorSanitizer.isStaleRecordMessage(""));
    assertFalse(NeoErrorSanitizer.isStaleRecordMessage("duplicate key value violates unique constraint"));
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
