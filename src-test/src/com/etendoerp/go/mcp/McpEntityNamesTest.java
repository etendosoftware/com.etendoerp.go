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

package com.etendoerp.go.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import com.etendoerp.go.schemaforge.data.SFEntity;
import com.etendoerp.go.schemaforge.data.SFSpec;

/**
 * {@link McpEntityNames#canonicalName} (ETP-5714): maps an entity typed with other capitals onto
 * the one spelling the spec uses, among the entities the caller may SEE, and answers {@code null}
 * unless the match is unique and different.
 *
 * @covers com.etendoerp.go.mcp.McpEntityNames
 */
@DisplayName("McpEntityNames (ETP-5714)")
class McpEntityNamesTest {

  private MockedStatic<McpToolRouterSupport> support;

  @BeforeEach
  void setUp() {
    support = mockStatic(McpToolRouterSupport.class, org.mockito.Mockito.CALLS_REAL_METHODS);
    SFSpec spec = mock(SFSpec.class);
    when(spec.getId()).thenReturn("spec-1");
    support.when(() -> McpToolRouterSupport.findActiveSpecByName("sales-order")).thenReturn(spec);
  }

  @AfterEach
  void tearDown() {
    support.close();
  }

  private void visible(String... names) {
    List<SFEntity> entities = new ArrayList<>();
    for (String name : names) {
      SFEntity entity = mock(SFEntity.class);
      when(entity.getName()).thenReturn(name);
      entities.add(entity);
    }
    support.when(() -> McpToolRouterSupport.listVisibleEntities("spec-1")).thenReturn(entities);
  }

  @Test
  @DisplayName("maps a different case onto the spec's spelling")
  void mapsADifferentCaseOntoTheSpecsSpelling() {
    visible("header", "lines");

    assertEquals("header", McpEntityNames.canonicalName("sales-order", "Header"));
    assertEquals("lines", McpEntityNames.canonicalName("sales-order", "LINES"));
  }

  @Test
  @DisplayName("answers null when there is nothing to correct")
  void answersNullWhenNothingToCorrect() {
    visible("header", "lines");

    assertNull(McpEntityNames.canonicalName("sales-order", "header"),
        "an exact name has nothing to correct");
    assertNull(McpEntityNames.canonicalName("sales-order", "nothing"));
    assertNull(McpEntityNames.canonicalName("sales-order", " "));
    assertNull(McpEntityNames.canonicalName("sales-order", null));
  }

  @Test
  @DisplayName("an ambiguous match is not guessed")
  void anAmbiguousMatchIsNotGuessed() {
    visible("lineTax", "linetax");

    assertNull(McpEntityNames.canonicalName("sales-order", "LINETAX"));
  }

  @Test
  @DisplayName("an entity the caller cannot see is never revealed")
  void anEntityTheCallerCannotSeeIsNeverRevealed() {
    visible("header");

    assertNull(McpEntityNames.canonicalName("sales-order", "Accounting"));
  }
}
