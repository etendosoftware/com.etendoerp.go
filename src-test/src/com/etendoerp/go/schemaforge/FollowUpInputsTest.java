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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.stream.Stream;

import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Unit tests for {@link FollowUpInputs#fromRequestBody}: the caller's optional choices read from
 * a follow-up POST body. A missing, empty, {@code null} or blank member is no choice; any other
 * value is kept trimmed, as text.
 *
 * @covers com.etendoerp.go.schemaforge.FollowUpInputs
 */
class FollowUpInputsTest {

  static Stream<Arguments> noChoiceBodies() throws Exception {
    return Stream.of(
        Arguments.of("absent body", null),
        Arguments.of("empty body", new JSONObject()),
        Arguments.of("null member", new JSONObject().put("warehouseId", JSONObject.NULL)),
        Arguments.of("blank member", new JSONObject().put("warehouseId", "   ")));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("noChoiceBodies")
  void aBodyWithoutAnyRealValueIsNone(String label, JSONObject body) {
    FollowUpInputs inputs = FollowUpInputs.fromRequestBody(body);

    assertSame(FollowUpInputs.none(), inputs);
    assertTrue(inputs.isEmpty());
    assertNull(inputs.get("warehouseId"));
  }

  static Stream<Arguments> keptValues() throws Exception {
    return Stream.of(
        Arguments.of("trimmed string", new JSONObject().put("warehouseId", "  wh-1 "), "wh-1"),
        Arguments.of("number as text", new JSONObject().put("warehouseId", 42), "42"),
        Arguments.of("boolean as text", new JSONObject().put("warehouseId", true), "true"));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("keptValues")
  void aRealValueIsKeptTrimmedAsText(String label, JSONObject body, String expected) {
    FollowUpInputs inputs = FollowUpInputs.fromRequestBody(body);

    assertFalse(inputs.isEmpty());
    assertEquals(expected, inputs.get("warehouseId"));
  }

  @Test
  void aBlankMemberIsDroppedWhileTheOthersAreKept() throws Exception {
    JSONObject body = new JSONObject().put("warehouseId", "").put("note", "x");

    FollowUpInputs inputs = FollowUpInputs.fromRequestBody(body);

    assertNull(inputs.get("warehouseId"));
    assertEquals("x", inputs.get("note"));
  }
}
