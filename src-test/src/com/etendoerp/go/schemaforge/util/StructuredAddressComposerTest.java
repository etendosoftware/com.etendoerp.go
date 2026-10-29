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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mockStatic;

import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import com.etendoerp.go.featureflags.GoFeatureFlags;
import com.etendoerp.go.schemaforge.util.StructuredAddressComposer.ComposedAddress;

/**
 * Unit tests for {@link StructuredAddressComposer} (ETP-5806).
 *
 * <p>{@link #composeAddressLines} is an exact port of Classic's
 * {@code LocationSEPA.java::fillAddressLines}: these tests pin that algorithm (everything fits
 * on line 1, overflow to line 2 with a truncated building name, a street alone longer than the
 * line gets truncated, separators), not a reinterpretation of the ticket's simplified wording.
 * Also {@link StructuredAddressComposer#isEnabled(String)}, which mirrors
 * {@code GoFeatureFlags.isEnabled} for {@code FLAG_STRUCTURED_ADDRESS_FOR_SEPA}, stubbed directly
 * so the assertion is independent of the local {@code Openbravo.properties} toggle. Since the
 * ETP-5806 staged-rollout follow-up it takes the account email and targets the evaluation at it,
 * so the tests below also pin the targeting key reaching the provider.
 *
 * @covers com.etendoerp.go.schemaforge.util.StructuredAddressComposer
 */
// Test methods live in the @Nested inner classes below; S2187 only inspects
// the outer class for @Test methods, hence the suppression.
@SuppressWarnings("java:S2187")
class StructuredAddressComposerTest {

  @Nested
  class ComposeAddressLines {

    @Test
    void everythingFitsOnLine1() {
      ComposedAddress composed = StructuredAddressComposer.composeAddressLines(
          "Gran Via", "45", "Local 2");

      assertEquals("Gran Via, 45 - Local 2", composed.getAddress1());
      assertNull(composed.getAddress2());
    }

    @Test
    void noBuildingNameStillFitsOnLine1WithNoDanglingSeparator() {
      ComposedAddress composed = StructuredAddressComposer.composeAddressLines(
          "Calle Mayor", "10", null);

      assertEquals("Calle Mayor, 10", composed.getAddress1());
      assertNull(composed.getAddress2());
    }

    @Test
    void streetAlonePresentOnly() {
      ComposedAddress composed = StructuredAddressComposer.composeAddressLines(
          "Just Street", null, null);

      assertEquals("Just Street", composed.getAddress1());
      assertNull(composed.getAddress2());
    }

    @Test
    void allBlankProducesBothLinesNull() {
      ComposedAddress composed = StructuredAddressComposer.composeAddressLines(" ", "", null);

      assertNull(composed.getAddress1());
      assertNull(composed.getAddress2());
    }

    @Test
    void streetAndNumberFitButWholeAddressOverflowsToLine2WithBuildingNameTruncated() {
      String street = StringUtils.repeat('A', 50);
      String buildingName = StringUtils.repeat('C', 70);
      // streetAndNumber = 50 + ", " + 3 digits = 55 chars, fits on line 1 alone.
      // wholeAddress = 55 + " - " + 70 = 128 chars, overflows: line 2 gets the (truncated to 60)
      // building name.
      ComposedAddress composed = StructuredAddressComposer.composeAddressLines(
          street, "123", buildingName);

      String expectedLine1 = street + ", 123";
      assertEquals(55, expectedLine1.length());
      assertEquals(expectedLine1, composed.getAddress1());
      assertEquals(StringUtils.left(buildingName, 60), composed.getAddress2());
      assertEquals(60, composed.getAddress2().length());
    }

    @Test
    void streetAndNumberFitAndLine2BuildingNameNotLongEnoughToTruncate() {
      String street = StringUtils.repeat('A', 50);
      String buildingName = StringUtils.repeat('C', 20);
      ComposedAddress composed = StructuredAddressComposer.composeAddressLines(
          street, "123", buildingName);

      assertEquals(street + ", 123", composed.getAddress1());
      // Not truncated: the whole 20-char building name survives untouched on line 2.
      assertEquals(buildingName, composed.getAddress2());
    }

    @Test
    void streetAloneLongerThan60IsTruncatedOnLine1AndNumberAndNameGoOnLine2() {
      String street = StringUtils.repeat('D', 70);
      ComposedAddress composed = StructuredAddressComposer.composeAddressLines(
          street, "123", "E");

      assertEquals(StringUtils.left(street, 60), composed.getAddress1());
      assertEquals(60, composed.getAddress1().length());
      assertEquals("123 - E", composed.getAddress2());
    }

    @Test
    void streetAloneLongerThan60WithOversizedTailAlsoTruncatesLine2() {
      String street = StringUtils.repeat('D', 70);
      String buildingName = StringUtils.repeat('E', 70);
      ComposedAddress composed = StructuredAddressComposer.composeAddressLines(
          street, "123", buildingName);

      assertEquals(StringUtils.left(street, 60), composed.getAddress1());
      String expectedTail = "123 - " + buildingName;
      assertEquals(StringUtils.left(expectedTail, 60), composed.getAddress2());
      assertEquals(60, composed.getAddress2().length());
    }

    @Test
    void separatorsAreCommaSpaceBetweenStreetAndNumberAndSpaceDashSpaceBeforeBuildingName() {
      ComposedAddress composed = StructuredAddressComposer.composeAddressLines(
          "Street", "1", "Name");
      assertEquals("Street, 1 - Name", composed.getAddress1());
    }

    @Test
    void blankInputsAreTrimmedBeforeComposing() {
      ComposedAddress composed = StructuredAddressComposer.composeAddressLines(
          "  Gran Via  ", " 45 ", "  ");
      // Trailing/leading whitespace on street/number is folded in, and a whitespace-only
      // building name behaves exactly like an absent one (no dangling " - ").
      assertEquals("Gran Via, 45", composed.getAddress1());
      assertNull(composed.getAddress2());
    }
  }

  @Nested
  class MissingMandatoryFields {

    @Test
    void nothingMissingWhenAllFourArePresent() {
      List<String> missing = StructuredAddressComposer.missingMandatoryFields(
          "Gran Via", "45", "Madrid", "ES");
      assertTrue(missing.isEmpty());
    }

    @Test
    void reportsEachFieldMissingInStableOrder() {
      List<String> missing = StructuredAddressComposer.missingMandatoryFields(null, "", " ", null);
      assertEquals(List.of("streetName", "buildingNumber", "cityName", "country"), missing);
    }

    @Test
    void onlyCountryMissing() {
      List<String> missing = StructuredAddressComposer.missingMandatoryFields(
          "Gran Via", "45", "Madrid", null);
      assertEquals(List.of("country"), missing);
    }

    @Test
    void whitespaceOnlyValueCountsAsMissing() {
      List<String> missing = StructuredAddressComposer.missingMandatoryFields(
          "   ", "45", "Madrid", "ES");
      assertEquals(List.of("streetName"), missing);
    }
  }

  @Nested
  class IsEnabled {

    /** Reflects {@link GoFeatureFlags#isEnabled} resolving {@code true}. */
    @Test
    void reflectsTheFlagResolvingTrue() {
      try (MockedStatic<GoFeatureFlags> flags = mockStatic(GoFeatureFlags.class)) {
        flags.when(() -> GoFeatureFlags.isEnabled(
            eq(GoFeatureFlags.FLAG_STRUCTURED_ADDRESS_FOR_SEPA), any())).thenReturn(true);

        assertTrue(StructuredAddressComposer.isEnabled("lola@example.com"));
      }
    }

    /** Reflects {@link GoFeatureFlags#isEnabled} resolving {@code false}. */
    @Test
    void reflectsTheFlagResolvingFalse() {
      try (MockedStatic<GoFeatureFlags> flags = mockStatic(GoFeatureFlags.class)) {
        flags.when(() -> GoFeatureFlags.isEnabled(
            eq(GoFeatureFlags.FLAG_STRUCTURED_ADDRESS_FOR_SEPA), any())).thenReturn(false);

        assertFalse(StructuredAddressComposer.isEnabled("lola@example.com"));
      }
    }

    /**
     * Queries exactly {@code FLAG_STRUCTURED_ADDRESS_FOR_SEPA} with a non-null context — never a
     * different flag key, never a null context.
     */
    @Test
    void queriesExactlyTheStructuredAddressFlagKey() {
      try (MockedStatic<GoFeatureFlags> flags = mockStatic(GoFeatureFlags.class)) {
        flags.when(() -> GoFeatureFlags.isEnabled(
            eq(GoFeatureFlags.FLAG_STRUCTURED_ADDRESS_FOR_SEPA), any())).thenReturn(true);

        StructuredAddressComposer.isEnabled("lola@example.com");

        flags.verify(() -> GoFeatureFlags.isEnabled(
            eq(GoFeatureFlags.FLAG_STRUCTURED_ADDRESS_FOR_SEPA), any()));
      }
    }

    /**
     * ETP-5806 follow-up — the account email becomes the evaluation's targeting key, so a
     * provider-side rule written against one account can turn the backend's own
     * validate/persist/expose gating on for exactly that account.
     */
    @Test
    void targetsTheGivenAccountEmail() {
      try (MockedStatic<GoFeatureFlags> flags = mockStatic(GoFeatureFlags.class)) {
        flags.when(() -> GoFeatureFlags.isEnabled(
            eq(GoFeatureFlags.FLAG_STRUCTURED_ADDRESS_FOR_SEPA), any())).thenReturn(false);

        StructuredAddressComposer.isEnabled("lola@example.com");

        flags.verify(() -> GoFeatureFlags.isEnabled(
            eq(GoFeatureFlags.FLAG_STRUCTURED_ADDRESS_FOR_SEPA),
            argThat(ctx -> "lola@example.com".equals(ctx.getTargetingKey()))));
      }
    }

    /**
     * A targeted account resolves {@code true} while an untargeted one resolves {@code false} in
     * the very same environment — the staged rollout this gating exists for. A global, account-less
     * evaluation could not tell the two apart, which is what left the write path accepting per the
     * global default while the UI already rendered the fields for the targeted account.
     */
    @Test
    void resolvesPerAccountWithinTheSameEnvironment() {
      try (MockedStatic<GoFeatureFlags> flags = mockStatic(GoFeatureFlags.class)) {
        flags.when(() -> GoFeatureFlags.isEnabled(
            eq(GoFeatureFlags.FLAG_STRUCTURED_ADDRESS_FOR_SEPA),
            argThat(ctx -> "lola@example.com".equals(ctx.getTargetingKey())))).thenReturn(true);
        flags.when(() -> GoFeatureFlags.isEnabled(
            eq(GoFeatureFlags.FLAG_STRUCTURED_ADDRESS_FOR_SEPA),
            argThat(ctx -> !"lola@example.com".equals(ctx.getTargetingKey())))).thenReturn(false);

        assertTrue(StructuredAddressComposer.isEnabled("lola@example.com"));
        assertFalse(StructuredAddressComposer.isEnabled("someone@example.com"));
      }
    }

    /**
     * A blank or {@code null} email is "no account", never "everyone": the context carries no
     * targeting key at all, so an account-scoped rule cannot match it.
     */
    @Test
    void blankEmailYieldsAnUntargetedEvaluation() {
      try (MockedStatic<GoFeatureFlags> flags = mockStatic(GoFeatureFlags.class)) {
        flags.when(() -> GoFeatureFlags.isEnabled(
            eq(GoFeatureFlags.FLAG_STRUCTURED_ADDRESS_FOR_SEPA), any())).thenReturn(false);

        assertFalse(StructuredAddressComposer.isEnabled(null));
        assertFalse(StructuredAddressComposer.isEnabled("   "));

        flags.verify(() -> GoFeatureFlags.isEnabled(
            eq(GoFeatureFlags.FLAG_STRUCTURED_ADDRESS_FOR_SEPA),
            argThat(ctx -> ctx.getTargetingKey() == null)), org.mockito.Mockito.times(2));
      }
    }
  }

  @Nested
  class FieldsExceedingMaxLength {

    @Test
    void nothingTooLongWhenAllAtOrBelowTheirLimit() {
      List<String> tooLong = StructuredAddressComposer.fieldsExceedingMaxLength(
          StringUtils.repeat('A', 70), StringUtils.repeat('B', 16), StringUtils.repeat('C', 35));
      assertTrue(tooLong.isEmpty());
    }

    @Test
    void nullValuesAreNeverTooLongRegardlessOfTheirWouldBeLength() {
      List<String> tooLong = StructuredAddressComposer.fieldsExceedingMaxLength(null, null, null);
      assertTrue(tooLong.isEmpty());
    }

    @Test
    void streetNameOneCharOverTheSeventyLimitIsFlagged() {
      List<String> tooLong = StructuredAddressComposer.fieldsExceedingMaxLength(
          StringUtils.repeat('A', 71), null, null);
      assertEquals(List.of("streetName"), tooLong);
    }

    @Test
    void buildingNumberOneCharOverTheSixteenLimitIsFlagged() {
      List<String> tooLong = StructuredAddressComposer.fieldsExceedingMaxLength(
          null, StringUtils.repeat('B', 17), null);
      assertEquals(List.of("buildingNumber"), tooLong);
    }

    @Test
    void buildingNameOneCharOverTheThirtyFiveLimitIsFlagged() {
      List<String> tooLong = StructuredAddressComposer.fieldsExceedingMaxLength(
          null, null, StringUtils.repeat('C', 36));
      assertEquals(List.of("buildingName"), tooLong);
    }

    @Test
    void allThreeOverTheirLimitAreReportedInStableOrder() {
      List<String> tooLong = StructuredAddressComposer.fieldsExceedingMaxLength(
          StringUtils.repeat('A', 71), StringUtils.repeat('B', 17), StringUtils.repeat('C', 36));
      assertEquals(List.of("streetName", "buildingNumber", "buildingName"), tooLong);
    }
  }
}
