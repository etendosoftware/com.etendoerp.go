package com.etendoerp.go.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ProvisioningClientNameTest {

  private static final String ACCOUNT_ID = "8A2C4F0E1B3D4C5E9F60718293A4B5C6";
  private static final String REQUEST_ID = "92359f3a-17b2-448b-9f68-40b0e9ebcad9";

  @Test
  @DisplayName("a paid attempt is keyed by its checkout request, the free one by the account")
  void keyedByCheckoutRequestThenAccount() {
    assertEquals("PEND-92359F3A17B2448B9F6840B0E9EBCAD9",
        ProvisioningClientName.of(ACCOUNT_ID, " " + REQUEST_ID + " "));
    assertEquals("PEND-" + ACCOUNT_ID, ProvisioningClientName.of(ACCOUNT_ID, ""));
    assertEquals("PEND-" + ACCOUNT_ID, ProvisioningClientName.of(ACCOUNT_ID, null));
  }

  @Test
  @DisplayName("any other key is hashed to the same 37-character shape, deterministically")
  void otherKeysAreHashedToTheSameShape() {
    String fixture = ProvisioningClientName.of(ACCOUNT_ID, "e2e-fixture-" + "x".repeat(80));

    assertEquals(37, fixture.length());
    assertTrue(ProvisioningClientName.matches(fixture));
    assertEquals(fixture, ProvisioningClientName.of(ACCOUNT_ID, "e2e-fixture-" + "x".repeat(80)));
    assertNotEquals(fixture, ProvisioningClientName.of(ACCOUNT_ID, "e2e-fixture-other"));
  }

  @Test
  @DisplayName("matches only the exact shape, ignoring case and blanks")
  void matchesOnlyTheExactShape() {
    assertTrue(ProvisioningClientName.matches(ProvisioningClientName.of(ACCOUNT_ID, REQUEST_ID)));
    assertTrue(ProvisioningClientName.matches("  pend-92359f3a17b2448b9f6840b0e9ebcad9 "));
    assertFalse(ProvisioningClientName.matches("Pend-Ingenieria SL"));
    assertFalse(ProvisioningClientName.matches("PEND-92359F3A17B2448B9F6840B0E9EBCAD9 SL"));
    assertFalse(ProvisioningClientName.matches("POOL-92359F3A17B2448B9F6840B0E9EBCAD9"));
    assertFalse(ProvisioningClientName.matches(null));
  }
}
