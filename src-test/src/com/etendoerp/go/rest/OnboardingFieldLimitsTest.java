package com.etendoerp.go.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ETP-4665 — the onboarding endpoints accepted values longer than the AD columns they are
 * written to, so the overflow only surfaced as a DAL ValidationException halfway through tenant
 * provisioning: the transaction rolled back and the caller got the opaque
 * {@code @CreateClientFailed@}.
 */
@DisplayName("OnboardingFieldLimits")
class OnboardingFieldLimitsTest {

  @Test
  @DisplayName("limits match the AD columns the onboarding payload is written to")
  void limitsMatchAdColumns() {
    // AD_USER.USERNAME / AD_USER.NAME — the overflow reported in ETP-4665.
    assertEquals(60, OnboardingFieldLimits.EMAIL);
    // AD_USER.NAME, set as the tenant admin display name.
    assertEquals(60, OnboardingFieldLimits.FULL_NAME);
    // AD_CLIENT.VALUE / AD_ORG.VALUE bind before AD_CLIENT.NAME's 60.
    assertEquals(40, OnboardingFieldLimits.CLIENT_NAME);
    // C_LOCATION.ADDRESS1.
    assertEquals(60, OnboardingFieldLimits.ADDRESS);
    // No storage constraint: the stored hash is fixed-length and there is no bcrypt.
    assertEquals(128, OnboardingFieldLimits.PASSWORD);
  }

  @Test
  @DisplayName("the register name is pinned to the profile limit, which pre-fills from it")
  void accountNamePinnedToFullName() {
    // ETGO_ACCOUNT.NAME is 255, so storage does not constrain it. The profile step pre-fills
    // its own field with this value and maxLength does not truncate a programmatically
    // assigned value, so a looser cap here would pre-fill a field already in error.
    assertEquals(60, OnboardingFieldLimits.ACCOUNT_NAME);
    assertEquals(OnboardingFieldLimits.FULL_NAME, OnboardingFieldLimits.ACCOUNT_NAME);
  }

  @Test
  @DisplayName("the client name leaves room for the ' Admin' suffix AD_ROLE.NAME appends")
  void clientNameLeavesRoomForRoleSuffix() {
    // InitialClientSetup builds the admin role as clientName + " Admin" into AD_ROLE.NAME(60).
    assertTrue(OnboardingFieldLimits.CLIENT_NAME + " Admin".length() <= 60);
  }

  @Test
  @DisplayName("exceeds is inclusive of the limit itself")
  void exceedsIsInclusiveBoundary() {
    assertFalse(OnboardingFieldLimits.exceeds("a".repeat(40), 40));
    assertTrue(OnboardingFieldLimits.exceeds("a".repeat(41), 40));
  }

  @Test
  @DisplayName("exceeds treats null and empty as within any limit")
  void exceedsAcceptsNullAndEmpty() {
    assertFalse(OnboardingFieldLimits.exceeds(null, 40));
    assertFalse(OnboardingFieldLimits.exceeds("", 40));
  }

  @Test
  @DisplayName("firstViolation returns null when every value fits")
  void firstViolationAllWithinLimits() {
    assertNull(OnboardingFieldLimits.firstViolation(
        "clientName", "Acme SL", OnboardingFieldLimits.CLIENT_NAME,
        "fullName", "Ada Lovelace", OnboardingFieldLimits.FULL_NAME,
        "address", "Gran Via 1", OnboardingFieldLimits.ADDRESS));
  }

  @Test
  @DisplayName("firstViolation reports the offending field and its limit")
  void firstViolationReportsFieldAndMax() {
    OnboardingFieldLimits.LengthViolation violation = OnboardingFieldLimits.firstViolation(
        "clientName", "a".repeat(41), OnboardingFieldLimits.CLIENT_NAME,
        "address", "Gran Via 1", OnboardingFieldLimits.ADDRESS);

    assertNotNull(violation);
    assertEquals("clientName", violation.field());
    assertEquals(40, violation.max());
  }

  @Test
  @DisplayName("firstViolation stops at the first violation so the user fixes one field at a time")
  void firstViolationStopsAtFirst() {
    OnboardingFieldLimits.LengthViolation violation = OnboardingFieldLimits.firstViolation(
        "fullName", "a".repeat(61), OnboardingFieldLimits.FULL_NAME,
        "clientName", "b".repeat(41), OnboardingFieldLimits.CLIENT_NAME);

    assertNotNull(violation);
    assertEquals("fullName", violation.field());
  }

  @Test
  @DisplayName("firstViolation catches the reported case: a 255-character email")
  void firstViolationCatchesReportedEmailOverflow() {
    // The address from the ETP-4665 report: 64+63+63+62 characters plus separators.
    String email = "a".repeat(64) + "@" + "b".repeat(63) + "." + "c".repeat(63) + "."
        + "d".repeat(62);
    assertEquals(255, email.length());

    OnboardingFieldLimits.LengthViolation violation = OnboardingFieldLimits.firstViolation(
        "email", email, OnboardingFieldLimits.EMAIL);

    assertNotNull(violation);
    assertEquals("email", violation.field());
    assertEquals(60, violation.max());
  }

  @Test
  @DisplayName("firstViolation rejects malformed varargs instead of silently skipping checks")
  void firstViolationRejectsIncompleteTriples() {
    assertThrows(IllegalArgumentException.class,
        () -> OnboardingFieldLimits.firstViolation("clientName", "Acme"));
  }

  @Test
  @DisplayName("firstViolation returns null for no checks at all")
  void firstViolationWithNoChecks() {
    assertNull(OnboardingFieldLimits.firstViolation());
  }
}
