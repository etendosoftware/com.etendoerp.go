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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.Month;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;
import org.openbravo.model.financialmgmt.payment.FIN_PaymentMethod;
import org.openbravo.module.remittance.RemittanceType;

import com.etendoerp.go.schemaforge.RemittanceNameSuggestion.Kind;

/**
 * Unit tests for {@link RemittanceNameSuggestion}, the suggested name of a remittance: the kind of
 * a remittance type, the catalog wording in Spanish and English with the JDK month name, the
 * 60-character cap, the recognition of a suggestion (any supported language, kind, month and year)
 * against a typed name, and the day parser.
 *
 * <p>A class of its own, next to {@code RemittanceHeaderHandlerTest}: the helper is a separate,
 * DAL-free production class whose rules are exhaustively enumerable (12 months × 3 kinds × 2
 * languages); the handler test pins only how the callout and the create hooks use it.
 *
 * @covers com.etendoerp.go.schemaforge.RemittanceNameSuggestion
 */
class RemittanceNameSuggestionTest {

  private static final LocalDate NOVEMBER_2026 = LocalDate.of(2026, 11, 15);

  @AfterEach
  void tearDown() {
    Mockito.framework().clearInlineMocks();
  }

  // ---------------------------------------------------------------- kindOf

  @ParameterizedTest(name = "payin={0} payout={1} -> {2}")
  @CsvSource({ "true, false, COLLECTION", "false, true, PAYMENT", "true, true, GENERIC",
      "false, false, GENERIC" })
  void kindOfFollowsTheDirectionsOfThePaymentMethod(boolean payIn, boolean payOut, Kind expected) {
    assertEquals(expected, RemittanceNameSuggestion.kindOf(type(payIn, payOut)));
  }

  @Test
  void kindOfIsGenericWithoutATypeOrAMethod() {
    assertEquals(Kind.GENERIC, RemittanceNameSuggestion.kindOf(null));
    assertEquals(Kind.GENERIC, RemittanceNameSuggestion.kindOf(mock(RemittanceType.class)));
  }

  // ---------------------------------------------------------------- suggest

  @ParameterizedTest(name = "{0} {1} -> {2}")
  @CsvSource({
      "GENERIC, es_ES, Remesa noviembre 2026",
      "COLLECTION, es_ES, Cobros noviembre 2026",
      "PAYMENT, es_ES, Pagos noviembre 2026",
      "COLLECTION, es_AR, Cobros noviembre 2026",
      "GENERIC, en_US, Remittance November 2026",
      "COLLECTION, en_US, Collections November 2026",
      "PAYMENT, en_US, Payments November 2026",
  })
  void suggestWordsTheKindMonthAndYearInTheRequestLanguage(Kind kind, String language,
      String expected) {
    assertEquals(expected, RemittanceNameSuggestion.suggest(NOVEMBER_2026, kind, language));
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = { "  " })
  void aBlankLanguageMeansEnglish(String language) {
    assertEquals("Collections November 2026",
        RemittanceNameSuggestion.suggest(NOVEMBER_2026, Kind.COLLECTION, language));
  }

  @Test
  void everySuggestionFitsTheNameColumnAndIsRecognizedAsOne() {
    for (String language : new String[] { "es_ES", "es_AR", "en_US" }) {
      for (Kind kind : Kind.values()) {
        for (Month month : Month.values()) {
          String name = RemittanceNameSuggestion.suggest(LocalDate.of(2026, month, 1), kind,
              language);
          assertTrue(name.length() <= RemittanceNameSuggestion.NAME_MAX_LENGTH, name);
          assertTrue(RemittanceNameSuggestion.isSuggestion(name), name);
        }
      }
    }
  }

  // ---------------------------------------------------------------- isSuggestion

  @ParameterizedTest
  @ValueSource(strings = { "Remesa enero 1999", "Cobros diciembre 2100", "Pagos septiembre 2026",
      "Remittance March 2030", "Collections February 2026", "Payments December 1990",
      "  Cobros noviembre 2026  " })
  void isSuggestionRecognizesEveryLanguageKindMonthAndYear(String name) {
    assertTrue(RemittanceNameSuggestion.isSuggestion(name), name);
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = { "  ", "Mi remesa", "Cobros noviembre", "Cobros noviembre 26",
      "Cobros brumario 2026", "Remesa de noviembre 2026", "Cobros noviembre 2026 BBVA" })
  void isSuggestionRejectsAnythingTheUserTyped(String name) {
    assertFalse(RemittanceNameSuggestion.isSuggestion(name), String.valueOf(name));
  }

  // ---------------------------------------------------------------- parseDay

  @ParameterizedTest
  @ValueSource(strings = { "2026-11-15", "2026-11-15T10:30:00", "2026-11-15 00:00:00.0",
      " 2026-11-15 " })
  void parseDayReadsTheCalendarDayAtTheStart(String raw) {
    assertEquals(NOVEMBER_2026, RemittanceNameSuggestion.parseDay(raw));
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = { "  ", "2026-1-5", "15/11/2026", "2026-13-01", "not a date" })
  void parseDayAnswersNullForAnythingElse(String raw) {
    assertNull(RemittanceNameSuggestion.parseDay(raw));
  }

  // ---------------------------------------------------------------- helpers

  private static RemittanceType type(boolean payIn, boolean payOut) {
    FIN_PaymentMethod method = mock(FIN_PaymentMethod.class);
    when(method.isPayinAllow()).thenReturn(payIn);
    when(method.isPayoutAllow()).thenReturn(payOut);
    RemittanceType type = mock(RemittanceType.class);
    when(type.getPaymentMethod()).thenReturn(method);
    return type;
  }
}
