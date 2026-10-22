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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.etendoerp.go.schemaforge.util.StructuredAddressComposer.ComposedAddress;

/**
 * Unit tests for {@link StructuredAddressComposer} (ETP-5806).
 *
 * <p>{@link #composeAddressLines} is an exact port of Classic's
 * {@code LocationSEPA.java::fillAddressLines}: these tests pin that algorithm (everything fits
 * on line 1, overflow to line 2 with a truncated building name, a street alone longer than the
 * line gets truncated, separators), not a reinterpretation of the ticket's simplified wording.
 *
 * @covers com.etendoerp.go.schemaforge.util.StructuredAddressComposer
 */
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
