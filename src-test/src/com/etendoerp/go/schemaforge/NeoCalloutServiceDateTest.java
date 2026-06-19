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
 * Unit tests for {@link NeoCalloutService#etendoToIsoDate(String, String)} — the
 * symmetric counterpart that converts callout-returned dates back to ISO for the REST
 * response. Pins the safety contract: convert genuine Etendo-format dates, leave
 * everything else (including already-ISO values) untouched (ETP-4244).
 */
class NeoCalloutServiceDateTest {

  private static final String ETENDO = "dd-MM-yyyy";

  @Test
  @DisplayName("converts an Etendo-format date back to ISO")
  void convertsEtendoToIso() {
    assertEquals("2026-06-16", NeoCalloutService.etendoToIsoDate("16-06-2026", ETENDO));
  }

  @Test
  @DisplayName("round-trips with the input-side reformatting")
  void roundTrips() {
    String etendo = CalloutRequestBuilder.isoToEtendoDate("2026-06-16", ETENDO);
    assertEquals("16-06-2026", etendo);
    assertEquals("2026-06-16", NeoCalloutService.etendoToIsoDate(etendo, ETENDO));
  }

  @Test
  @DisplayName("leaves an already-ISO value untouched (idempotent)")
  void leavesIsoUntouched() {
    assertNull(NeoCalloutService.etendoToIsoDate("2026-06-16", ETENDO));
  }

  @Test
  @DisplayName("leaves null/empty/invalid values untouched")
  void leavesNullEmptyInvalidUntouched() {
    assertNull(NeoCalloutService.etendoToIsoDate(null, ETENDO));
    assertNull(NeoCalloutService.etendoToIsoDate("", ETENDO));
    assertNull(NeoCalloutService.etendoToIsoDate("garbage", ETENDO));
    assertNull(NeoCalloutService.etendoToIsoDate("40-13-2026", ETENDO));
  }
}
