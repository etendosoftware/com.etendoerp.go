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

import java.math.BigDecimal;
import java.util.stream.Stream;

import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * {@link LineAmountSupport#isStaleAmount} — the one value rule that decides, on a create, whether
 * an amount in the body is the caller's (kept) or a server-derived one (replaced) (ETP-5528). Both
 * the order-line and the invoice-line customizations delegate to it, so its table is pinned here
 * once instead of through each caller.
 */
class LineAmountSupportTest {

  static Stream<Arguments> staleAmounts() {
    return Stream.of(
        Arguments.of("absent (null) is stale", null, 180, true),
        Arguments.of("JSON null is stale", JSONObject.NULL, 180, true),
        Arguments.of("not a number reads as zero", "abc", 180, true),
        Arguments.of("zero is stale", 0, 180, true),
        Arguments.of("zero at scale is stale", "0.00", new BigDecimal("180"), true),
        Arguments.of("equal at 2 decimals is stale", 180, 180.004, true),
        Arguments.of("different at 2 decimals is the caller's", 180, 180.01, false),
        Arguments.of("a different amount is the caller's", 200, 180, false),
        Arguments.of("a negative amount equal to the derived one is stale", "-180.00", -180,
            true),
        Arguments.of("a negative amount is the caller's when it differs", -180, 180, false),
        Arguments.of("nothing derived keeps a non-zero value", 180, null, false));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("staleAmounts")
  void isStaleAmount(String label, Object current, Object derived, boolean expected) {
    assertEquals(expected, LineAmountSupport.isStaleAmount(current, derived), label);
  }
}
