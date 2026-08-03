package com.etendoerp.go.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * ETP-4665 — the onboarding endpoints accepted values longer than the AD columns they are
 * written to, so the overflow only surfaced as a DAL ValidationException halfway through tenant
 * provisioning: the transaction rolled back and the caller got the opaque
 * {@code @CreateClientFailed@}.
 */
@DisplayName("OnboardingFieldLimits")
class OnboardingFieldLimitsTest {

  @Nested
  @DisplayName("limits")
  class Limits {

    @Test
    @DisplayName("match the AD columns the onboarding payload is written to")
    void matchAdColumns() {
      // AD_USER.USERNAME / AD_USER.NAME — the overflow reported in ETP-4665.
      assertEquals(60, OnboardingFieldLimits.EMAIL);
      // AD_USER.NAME, set as the tenant admin display name.
      assertEquals(60, OnboardingFieldLimits.FULL_NAME);
      // AD_CLIENT.VALUE / AD_ORG.VALUE bind before AD_CLIENT.NAME's 60.
      assertEquals(40, OnboardingFieldLimits.CLIENT_NAME);
      // C_LOCATION.ADDRESS1.
      assertEquals(60, OnboardingFieldLimits.ADDRESS);
      // ETGO_ACCOUNT.NAME is 255; pinned to FULL_NAME because the profile step pre-fills
      // its own field with this value.
      assertEquals(60, OnboardingFieldLimits.ACCOUNT_NAME);
      assertEquals(OnboardingFieldLimits.FULL_NAME, OnboardingFieldLimits.ACCOUNT_NAME);
      // No storage constraint: the stored hash is fixed-length and there is no bcrypt.
      assertEquals(128, OnboardingFieldLimits.PASSWORD);
    }

    @Test
    @DisplayName("leave room for the ' Admin' suffix AD_ROLE.NAME appends to the client name")
    void clientNameLeavesRoomForRoleSuffix() {
      // InitialClientSetup builds the admin role as clientName + " Admin" into AD_ROLE.NAME(60).
      assertTrue(OnboardingFieldLimits.CLIENT_NAME + " Admin".length() <= 60);
    }
  }

  @Nested
  @DisplayName("exceeds")
  class Exceeds {

    @Test
    @DisplayName("is inclusive of the limit itself")
    void inclusiveBoundary() {
      assertFalse(OnboardingFieldLimits.exceeds("a".repeat(40), 40));
      assertTrue(OnboardingFieldLimits.exceeds("a".repeat(41), 40));
    }

    @Test
    @DisplayName("treats null and empty as within any limit")
    void nullAndEmptyAreFine() {
      assertFalse(OnboardingFieldLimits.exceeds(null, 40));
      assertFalse(OnboardingFieldLimits.exceeds("", 40));
    }
  }

  @Nested
  @DisplayName("firstViolation")
  class FirstViolation {

    @Test
    @DisplayName("returns null when every value fits")
    void allWithinLimits() {
      assertNull(OnboardingFieldLimits.firstViolation(
          "clientName", "Acme SL", OnboardingFieldLimits.CLIENT_NAME,
          "fullName", "Ada Lovelace", OnboardingFieldLimits.FULL_NAME,
          "address", "Gran Via 1", OnboardingFieldLimits.ADDRESS));
    }

    @Test
    @DisplayName("reports the offending field and its limit")
    void reportsFieldAndMax() {
      OnboardingFieldLimits.LengthViolation violation = OnboardingFieldLimits.firstViolation(
          "clientName", "a".repeat(41), OnboardingFieldLimits.CLIENT_NAME,
          "address", "Gran Via 1", OnboardingFieldLimits.ADDRESS);

      assertNotNull(violation);
      assertEquals("clientName", violation.field());
      assertEquals(40, violation.max());
    }

    @Test
    @DisplayName("stops at the first violation so the user fixes one field at a time")
    void stopsAtFirst() {
      OnboardingFieldLimits.LengthViolation violation = OnboardingFieldLimits.firstViolation(
          "fullName", "a".repeat(61), OnboardingFieldLimits.FULL_NAME,
          "clientName", "b".repeat(41), OnboardingFieldLimits.CLIENT_NAME);

      assertNotNull(violation);
      assertEquals("fullName", violation.field());
    }

    @Test
    @DisplayName("catches the reported case: a 255-character email")
    void reportedEmailOverflow() {
      // The address from the ETP-4665 report: 64+64+64+63 characters.
      String email = "a".repeat(64) + "@" + "b".repeat(63) + "." + "c".repeat(63) + "." + "d".repeat(62);
      assertEquals(255, email.length());

      OnboardingFieldLimits.LengthViolation violation = OnboardingFieldLimits.firstViolation(
          "email", email, OnboardingFieldLimits.EMAIL);

      assertNotNull(violation);
      assertEquals("email", violation.field());
      assertEquals(60, violation.max());
    }

    @Test
    @DisplayName("rejects malformed varargs instead of silently skipping checks")
    void rejectsIncompleteTriples() {
      assertThrows(IllegalArgumentException.class,
          () -> OnboardingFieldLimits.firstViolation("clientName", "Acme"));
    }

    @Test
    @DisplayName("returns null for no checks at all")
    void noChecks() {
      assertNull(OnboardingFieldLimits.firstViolation());
    }
  }
}
