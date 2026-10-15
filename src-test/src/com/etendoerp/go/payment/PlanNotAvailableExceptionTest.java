/*
 * *************************************************************************
 * Etendo License. See https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * *************************************************************************
 */
package com.etendoerp.go.payment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The rejected plan key is untrusted browser input that ends up in a log line, so the exception
 * message must carry it single-line and bounded.
 */
class PlanNotAvailableExceptionTest {

  @Test
  void aNormalKeyIsLoggedVerbatim() {
    assertEquals("No active plan is available under the key 'pro-monthly'",
        new PlanNotAvailableException("pro-monthly").getMessage());
  }

  @Test
  void aMissingKeyIsNamedAsSuch() {
    assertEquals("No active plan is available under the key '<none>'",
        new PlanNotAvailableException(null).getMessage());
  }

  @Test
  void lineBreaksAndControlCharactersCannotForgeALogLine() {
    String message = new PlanNotAvailableException(
        "pro\r\n2026-09-24 INFO forged line\u0000x\t").getMessage();

    assertFalse(message.contains("\n"));
    assertFalse(message.contains("\r"));
    assertFalse(message.contains(" "));
    assertFalse(message.contains("\u0000"));
    assertFalse(message.contains("\t"));
    assertTrue(message.contains("pro??2026-09-24 INFO forged?line?x?"), message);
  }

  @Test
  void anOversizedKeyIsCutAndItsLengthReported() {
    String key = "k".repeat(10_000);

    String loggable = PlanNotAvailableException.loggable(key);

    assertEquals("k".repeat(PlanNotAvailableException.MAX_LOGGED_KEY_LENGTH) + "...(10000 chars)",
        loggable);
  }

  @Test
  void aKeyAtTheLimitIsNotCut() {
    String key = "k".repeat(PlanNotAvailableException.MAX_LOGGED_KEY_LENGTH);

    assertEquals(key, PlanNotAvailableException.loggable(key));
  }
}
