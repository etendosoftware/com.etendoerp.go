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
 * All portions are Copyright (C) 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.schemaforge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;

import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Unit tests for {@link RemittanceLineAmount}, the one rule for «A remesar» shared by the line edit
 * and the picker sync: the amount has the sign of the due and {@code 0 < |amount| ≤ |outstanding|};
 * how a JSON value is read as an amount; and the 422 refusal, which carries the outstanding as
 * {@code error.max} when it is known.
 *
 * <p>A class of its own: {@code RemittanceLineAmount} is a new production class used by both
 * {@code RemittanceLinesHandler} and {@code RemittanceDueItems}; their tests pin how each applies
 * the rule, this one pins the rule.
 *
 * @covers com.etendoerp.go.schemaforge.RemittanceLineAmount
 */
class RemittanceLineAmountTest {

  // ---------------------------------------------------------------- isValid

  @ParameterizedTest(name = "{0} of {1} -> {2}")
  @CsvSource({
      // a part of a positive due, up to all of it
      "50, 133.10, true",
      "133.10, 133.10, true",
      "133.1, 133.10, true",
      "0.01, 133.10, true",
      // above the due, zero, or the other sign
      "133.11, 133.10, false",
      "0, 133.10, false",
      "-10, 133.10, false",
      // a negative due (a credit memo): a negative part of it
      "-10, -50, true",
      "-50, -50, true",
      "-50.01, -50, false",
      "10, -50, false",
      // a due with nothing outstanding takes nothing
      "0, 0, false",
      "1, 0, false", })
  void isValidAcceptsAPartOfTheDueOfItsSignAndNeverMore(String amount, String outstanding,
      boolean expected) {
    assertEquals(expected, RemittanceLineAmount.isValid(new BigDecimal(amount),
        new BigDecimal(outstanding)));
  }

  @Test
  void isValidIsFalseWhenEitherSideIsMissing() {
    assertFalse(RemittanceLineAmount.isValid(null, new BigDecimal("10")));
    assertFalse(RemittanceLineAmount.isValid(new BigDecimal("10"), null));
    assertFalse(RemittanceLineAmount.isValid(null, null));
  }

  // ---------------------------------------------------------------- parse

  @Test
  void parseReadsNumbersAndNumericStrings() {
    assertEquals(0, new BigDecimal("50").compareTo(RemittanceLineAmount.parse(50)));
    assertEquals(0, new BigDecimal("12.5").compareTo(RemittanceLineAmount.parse(12.5d)));
    assertEquals(new BigDecimal("133.10"), RemittanceLineAmount.parse("133.10"));
    assertEquals(new BigDecimal("-7"), RemittanceLineAmount.parse(" -7 "));
    assertEquals(new BigDecimal("9.99"), RemittanceLineAmount.parse(new BigDecimal("9.99")));
  }

  @Test
  void parseAnswersNullForAnythingThatIsNotANumber() {
    assertNull(RemittanceLineAmount.parse(null));
    assertNull(RemittanceLineAmount.parse(JSONObject.NULL));
    assertNull(RemittanceLineAmount.parse(""));
    assertNull(RemittanceLineAmount.parse("   "));
    assertNull(RemittanceLineAmount.parse("abc"));
    assertNull(RemittanceLineAmount.parse("12,50"), "a decimal comma is not a JSON number");
    assertNull(RemittanceLineAmount.parse(true));
  }

  // ---------------------------------------------------------------- invalid

  @Test
  void theRefusalIs422WithItsKeyAndCarriesTheOutstandingAsMax() throws Exception {
    NeoResponse refusal = RemittanceLineAmount.invalid(new BigDecimal("133.10"));

    assertEquals(422, refusal.getHttpStatus());
    JSONObject error = refusal.getBody().getJSONObject("error");
    assertEquals("ETGO_RemittanceLineAmountInvalid",
        error.getJSONArray("messageKeys").getString(0));
    assertEquals(RemittanceLineAmount.MSG_INVALID, error.getJSONArray("messageKeys").getString(0));
    assertEquals(0, new BigDecimal("133.10").compareTo(new BigDecimal(
        error.get(RemittanceLineAmount.KEY_MAX).toString())));
    assertEquals("max", RemittanceLineAmount.KEY_MAX);
    assertTrue(error.getString("message").contains("133.10"), error.getString("message"));
  }

  @Test
  void aRefusalWithoutAKnownOutstandingHasNoMax() throws Exception {
    NeoResponse refusal = RemittanceLineAmount.invalid(null);

    assertEquals(422, refusal.getHttpStatus());
    JSONObject error = refusal.getBody().getJSONObject("error");
    assertEquals(RemittanceLineAmount.MSG_INVALID, error.getJSONArray("messageKeys").getString(0));
    assertFalse(error.has(RemittanceLineAmount.KEY_MAX));
  }

  @Test
  void theNotEditableKeyIsTheOneTheSpaMaps() {
    assertEquals("ETGO_RemittanceLineAmountNotEditable", RemittanceLineAmount.MSG_NOT_EDITABLE);
  }
}
