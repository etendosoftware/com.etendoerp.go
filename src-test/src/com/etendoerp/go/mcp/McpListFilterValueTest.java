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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Set;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.openbravo.base.model.Property;

/**
 * ETP-5850: a filter on a list field (a column with a list reference) with a value outside its
 * codes matches no row, so it is refused naming the codes instead of answering an empty list. The
 * invoice inbox filtered on {@code status: "pending"} answered "no documents" while one was pending.
 *
 * @covers com.etendoerp.go.mcp.McpHqlConditions
 */
class McpListFilterValueTest {

  private static final String KEY = "status";
  private static final String PATH = "e.status";

  private static Property listProperty() {
    Property prop = mock(Property.class);
    when(prop.isPrimitive()).thenReturn(true);
    when(prop.getPrimitiveObjectType()).thenReturn((Class) String.class);
    when(prop.getAllowedValues()).thenReturn(Set.of("PENDING_REVIEW", "READY", "ERROR"));
    return prop;
  }

  @Test
  @DisplayName("refuses an equality on a code the list does not have, naming the codes")
  void refusesAnUnknownCode() {
    McpRoutingException refusal = assertThrows(McpRoutingException.class,
        () -> McpHqlConditions.appendEqualityAt(new StringBuilder(), KEY, listProperty(), PATH,
            PATH + ".id", "pending"));

    assertEquals(McpConstants.ERROR_VALIDATION, refusal.getErrorCode());
    assertEquals(KEY, refusal.getField());
    assertTrue(refusal.getMessage().contains("'pending'"), refusal.getMessage());
  }

  @Test
  @DisplayName("refuses an 'in' as soon as one of its values is not a code")
  void refusesAnInWithOneUnknownCode() {
    JSONObject operators = new JSONObject();
    assertThrows(McpRoutingException.class, () -> {
      operators.put("in", new JSONArray().put("READY").put("Ready"));
      McpHqlConditions.appendOperatorConditionsAt(new StringBuilder(), listProperty(), PATH,
          PATH + ".id", KEY, operators);
    });
  }

  @Test
  @DisplayName("filters on the codes the list has, by equality and by 'in'")
  void acceptsTheListCodes() throws Exception {
    StringBuilder where = new StringBuilder();
    McpHqlConditions.appendEqualityAt(where, KEY, listProperty(), PATH, PATH + ".id", "ERROR");
    McpHqlConditions.appendOperatorConditionsAt(where, listProperty(), PATH, PATH + ".id", KEY,
        new JSONObject().put("in", new JSONArray().put("PENDING_REVIEW").put("READY")));

    assertTrue(where.toString().contains("e.status='ERROR'"), where.toString());
    assertTrue(where.toString().contains("PENDING_REVIEW"), where.toString());
  }

  @Test
  @DisplayName("leaves a free-text field alone: any value may match")
  void leavesFreeTextAlone() {
    Property text = mock(Property.class);
    when(text.isPrimitive()).thenReturn(true);
    when(text.getPrimitiveObjectType()).thenReturn((Class) String.class);
    when(text.getAllowedValues()).thenReturn(Set.of());
    StringBuilder where = new StringBuilder();

    McpHqlConditions.appendEqualityAt(where, "name", text, "e.name", "e.name.id", "anything");

    assertTrue(where.toString().contains("e.name="), where.toString());
  }
}
