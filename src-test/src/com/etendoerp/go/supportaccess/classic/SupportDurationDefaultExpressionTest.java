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
 * All portions are Copyright (C) 2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */
package com.etendoerp.go.supportaccess.classic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.HashMap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.etendoerp.go.supportaccess.SupportAccessService;

/**
 * The "Duration" parameter defaults to the configured duration, snapped to a list option and
 * capped at the configured maximum.
 *
 * @covers com.etendoerp.go.supportaccess.classic.SupportDurationDefaultExpression
 */
class SupportDurationDefaultExpressionTest {

  @ParameterizedTest
  @CsvSource({
      "60, 480, 60",
      "30, 480, 30",
      "480, 480, 480",
      "90, 480, 60",
      "1000, 480, 480",
      "480, 240, 240",
      "60, 45, 30",
      "10, 480, 30",
      "10, 5, 30" })
  void picksTheLongestOptionWithinPreferenceAndMaximum(int preferred, int max, int expected) {
    assertEquals(expected, SupportDurationDefaultExpression.pickOption(preferred, max));
  }

  @Test
  void readsBothPreferencesFromTheService() {
    SupportAccessService service = mock(SupportAccessService.class);
    when(service.getDefaultDurationMinutes()).thenReturn(120);
    when(service.getMaxDurationMinutes()).thenReturn(480);

    assertEquals("120",
        new SupportDurationDefaultExpression(() -> service).getExpression(new HashMap<>()));
  }

  @Test
  void fallsBackToTheBuiltInDefaultWhenThePreferencesCannotBeRead() {
    SupportAccessService service = mock(SupportAccessService.class);
    when(service.getDefaultDurationMinutes()).thenThrow(new IllegalStateException("no db"));

    assertEquals(String.valueOf(SupportAccessService.FALLBACK_DEFAULT_MINUTES),
        new SupportDurationDefaultExpression(() -> service).getExpression(new HashMap<>()));
  }

  @Test
  void optionsMatchTheDurationListReference() {
    int[] options = SupportAccessClassicMetadata.durationOptions();
    assertEquals("[30, 60, 120, 240, 480]", java.util.Arrays.toString(options));
    options[0] = 1;
    assertEquals(30, SupportAccessClassicMetadata.durationOptions()[0]);
  }
}
