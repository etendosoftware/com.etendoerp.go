/*
 *************************************************************************
 * The contents of this file are subject to the Etendo License
 * (the "License"), you may not use this file except in compliance
 * with the License.
 * You may obtain a copy of the License at
 * https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * Software distributed under the License is distributed on an
 * "AS IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
 * implied. See the License for the specific language governing rights
 * and limitations under the License.
 * All portions are Copyright (C) 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 *************************************************************************
 */
package com.etendoerp.go.schemaforge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link CalloutRequestBuilder#isoToEtendoDate(String, String)}.
 *
 * <p>These tests pin the safety contract of the ISO→Etendo date reformatting added for
 * ETP-4244: legacy AD callouts re-parse date params through Postgres {@code to_date()}
 * (format {@code dateFormat()='DD-MM-YYYY'}), but NEO sends ISO {@code yyyy-MM-dd}. The
 * conversion must (a) convert genuine ISO dates, and (b) leave EVERYTHING else untouched —
 * which is what guarantees other date fields/callouts cannot be broken by the change.</p>
 */
class CalloutRequestBuilderDateTest {

  private static final String ETENDO = "dd-MM-yyyy";

  @Test
  @DisplayName("converts an ISO date to the Etendo UI format")
  void convertsIsoDate() {
    assertEquals("16-06-2026", CalloutRequestBuilder.isoToEtendoDate("2026-06-16", ETENDO));
  }

  @Test
  @DisplayName("strips a trailing time component and converts the date part")
  void convertsIsoDateTime() {
    assertEquals("16-06-2026", CalloutRequestBuilder.isoToEtendoDate("2026-06-16T00:00:00", ETENDO));
  }

  @Test
  @DisplayName("leaves an already-Etendo-formatted date untouched (idempotent / no double convert)")
  void leavesEtendoFormattedUntouched() {
    // Returns null → caller keeps the original value, so a dd-MM-yyyy value is never re-parsed.
    assertNull(CalloutRequestBuilder.isoToEtendoDate("16-06-2026", ETENDO));
  }

  @Test
  @DisplayName("leaves null/empty/short values untouched")
  void leavesNullEmptyShortUntouched() {
    assertNull(CalloutRequestBuilder.isoToEtendoDate(null, ETENDO));
    assertNull(CalloutRequestBuilder.isoToEtendoDate("", ETENDO));
    assertNull(CalloutRequestBuilder.isoToEtendoDate("2026-06", ETENDO));
  }

  @Test
  @DisplayName("leaves a non-date 10-char string untouched")
  void leavesNonDateUntouched() {
    assertNull(CalloutRequestBuilder.isoToEtendoDate("not-a-date", ETENDO));
    assertNull(CalloutRequestBuilder.isoToEtendoDate("2026-13-40", ETENDO));
  }

  @Test
  @DisplayName("honors the configured target pattern")
  void honorsConfiguredPattern() {
    assertEquals("2026/06/16", CalloutRequestBuilder.isoToEtendoDate("2026-06-16", "yyyy/MM/dd"));
  }
}
