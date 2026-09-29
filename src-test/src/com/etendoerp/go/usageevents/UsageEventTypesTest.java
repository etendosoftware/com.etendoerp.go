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

package com.etendoerp.go.usageevents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit specs for {@link UsageEventTypes} (ETP-5462, decision D4).
 *
 * <p>The class javadoc warns that a constant missing from the set is "silently useless": every event
 * carrying it is dropped. That drift is exactly what these specs catch — they enumerate the public
 * {@code String} constants by reflection, so a new constant added without its {@code KNOWN} entry,
 * or with a shape the {@code EVENT_TYPE VARCHAR(60)} column cannot hold, fails here and not in
 * production.
 */
class UsageEventTypesTest {

  private static List<String> declaredTypeConstants() throws IllegalAccessException {
    List<String> values = new ArrayList<>();
    for (Field field : UsageEventTypes.class.getDeclaredFields()) {
      int mod = field.getModifiers();
      if (Modifier.isPublic(mod) && Modifier.isStatic(mod) && Modifier.isFinal(mod)
          && field.getType() == String.class) {
        values.add((String) field.get(null));
      }
    }
    return values;
  }

  @Test
  void everyConstantMatchesThePatternAndIsKnown() throws IllegalAccessException {
    List<String> constants = declaredTypeConstants();
    assertFalse(constants.isEmpty(), "expected at least one event type constant");
    for (String type : constants) {
      assertTrue(UsageEventTypes.PATTERN.matcher(type).matches(), "bad shape: " + type);
      assertTrue(UsageEventTypes.all().contains(type), "constant not in KNOWN: " + type);
      assertTrue(UsageEventTypes.isKnown(type));
    }
  }

  @Test
  void theSetHoldsNothingButTheConstants() throws IllegalAccessException {
    assertEquals(declaredTypeConstants().size(), UsageEventTypes.all().size());
  }

  @Test
  void allIsImmutable() {
    assertThrows(UnsupportedOperationException.class,
        () -> UsageEventTypes.all().add("rogue.event"));
  }

  @ParameterizedTest
  @ValueSource(strings = { "", "ai.agent.messages", "AI.AGENT.MESSAGE", " ai.agent.message",
      "ai.agent.message\n" })
  void unknownOrNearMissTypesAreNotKnown(String candidate) {
    assertFalse(UsageEventTypes.isKnown(candidate));
  }

  @Test
  void nullIsNotKnown() {
    assertFalse(UsageEventTypes.isKnown(null));
  }

  @ParameterizedTest
  @ValueSource(strings = { "a", "Ai.agent", "1ai.agent", "ai-agent", "ai agent", ".ai" })
  void patternRejectsMalformedTypes(String candidate) {
    assertFalse(UsageEventTypes.PATTERN.matcher(candidate).matches());
  }

  @Test
  void patternBoundsTheLengthToTheColumnWidth() {
    String sixty = "a" + "b".repeat(59);
    assertTrue(UsageEventTypes.PATTERN.matcher(sixty).matches());
    assertFalse(UsageEventTypes.PATTERN.matcher(sixty + "c").matches());
    assertTrue(UsageEventTypes.PATTERN.matcher("ab").matches());
  }
}
