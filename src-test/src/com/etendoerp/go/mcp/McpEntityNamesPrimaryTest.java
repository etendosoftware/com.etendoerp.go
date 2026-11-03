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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
import org.openbravo.model.ad.ui.Tab;

import com.etendoerp.go.schemaforge.data.SFEntity;
import com.etendoerp.go.schemaforge.data.SFSpec;

/**
 * {@link McpEntityNames#primaryName} (ETP-5848): an omitted entity means the spec's main record,
 * chosen among the entities the caller may see, the same one etendo_discover reports as
 * primaryEntity.
 *
 * @covers com.etendoerp.go.mcp.McpEntityNames
 */
@DisplayName("McpEntityNames.primaryName (ETP-5848)")
class McpEntityNamesPrimaryTest {

  private MockedStatic<McpToolRouterSupport> support;

  @BeforeEach
  void setUp() {
    support = mockStatic(McpToolRouterSupport.class, org.mockito.Mockito.CALLS_REAL_METHODS);
    SFSpec spec = mock(SFSpec.class);
    when(spec.getId()).thenReturn("spec-1");
    support.when(() -> McpToolRouterSupport.findActiveSpecByName("contacts")).thenReturn(spec);
    support.when(() -> McpToolRouterSupport.findActiveSpecByName("nothing")).thenReturn(null);
  }

  @AfterEach
  void tearDown() {
    support.close();
  }

  private static SFEntity entity(String name, long tabLevel) {
    SFEntity entity = mock(SFEntity.class);
    Tab tab = mock(Tab.class);
    when(tab.getTabLevel()).thenReturn(tabLevel);
    when(entity.getName()).thenReturn(name);
    when(entity.getADTab()).thenReturn(tab);
    return entity;
  }

  private void visible(SFEntity... entities) {
    List<SFEntity> list = new ArrayList<>(List.of(entities));
    support.when(() -> McpToolRouterSupport.listVisibleEntities("spec-1")).thenReturn(list);
  }

  @Test
  @DisplayName("the top-level entity is the primary one, whatever its name or position")
  void theTopLevelEntityIsThePrimaryOne() {
    visible(entity("contact", 1L), entity("businessPartner", 0L), entity("locationAddress", 1L));

    assertEquals("businessPartner", McpEntityNames.primaryName("contacts"));
  }

  @Test
  @DisplayName("an unknown spec or one with nothing visible has no primary entity")
  void anUnknownOrEmptySpecHasNone() {
    visible();

    assertNull(McpEntityNames.primaryName("contacts"));
    assertNull(McpEntityNames.primaryName("nothing"));
    assertNull(McpEntityNames.primaryName(null));
  }

  @Test
  @DisplayName("only tools with a top-level entity argument get it filled")
  void onlySingleEntityToolsTakeIt() {
    assertTrue(McpEntityNames.takesEntity(McpConstants.TOOL_NEO_LIST));
    assertTrue(McpEntityNames.takesEntity("etendo_action"));
    assertFalse(McpEntityNames.takesEntity("etendo_batch"), "each batch operation names its own");
    assertFalse(McpEntityNames.takesEntity(McpConstants.TOOL_NEO_DISCOVER));
  }
}
