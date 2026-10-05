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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.Property;
import org.openbravo.model.ad.datamodel.Column;
import org.openbravo.model.ad.datamodel.Table;
import org.openbravo.model.ad.domain.Reference;
import org.openbravo.model.ad.ui.Tab;

/**
 * ETP-5558 — {@code neo_selectors} on a column that is not a selector of the entity answered 500
 * ("Column not found in table") in blind run {@code 20261001T2331-local-8163}. It is the caller's
 * mistake, so it is now a 422 {@code unknown_selector_column} naming the columns that work, the
 * same shape as {@code neo_list}'s {@code unknown_filter_field}.
 */
@DisplayName("ETP-5558 — neo_selectors refuses an unknown column with 422")
class McpSelectorUnknownColumnTest {

  private static final Set<String> SELECTOR_REFS = Set.of("19", "18", "30", "95E2A8B50A254B2AAE6774B8C2F28120");

  @AfterEach
  void tearDown() {
    Mockito.framework().clearInlineMocks();
  }

  private static Column column(String dbName, String referenceId, boolean active) {
    Column column = mock(Column.class);
    when(column.getDBColumnName()).thenReturn(dbName);
    when(column.isActive()).thenReturn(active);
    Reference reference = mock(Reference.class);
    when(reference.getId()).thenReturn(referenceId);
    when(column.getReference()).thenReturn(reference);
    return column;
  }

  @Test
  @DisplayName("the selector columns are the active FK columns, by field name, sorted")
  void selectorColumnNames() {
    Tab tab = mock(Tab.class);
    Table table = mock(Table.class);
    when(tab.getTable()).thenReturn(table);
    List<Column> columns = new ArrayList<>();
    columns.add(column("C_Currency_ID", "19", true));
    columns.add(column("APRM_Glitem_Diff_ID", "30", true));
    columns.add(column("Name", "10", true));
    columns.add(column("C_Old_ID", "19", false));
    columns.add(column("C_Country_ID", "18", true));
    when(table.getADColumnList()).thenReturn(columns);
    Entity dal = mock(Entity.class);
    for (String[] p : new String[][] { { "C_Currency_ID", "currency" },
        { "APRM_Glitem_Diff_ID", "aprmGlitemDiff" } }) {
      Property property = mock(Property.class);
      when(property.getName()).thenReturn(p[1]);
      when(dal.getPropertyByColumnName(p[0])).thenReturn(property);
    }

    assertEquals(List.of("C_Country_ID", "aprmGlitemDiff", "currency"),
        McpSelectorContextHelper.selectorColumnNames(tab, dal, SELECTOR_REFS),
        "a column without a DAL property falls back to its DB name");
    assertTrue(McpSelectorContextHelper.selectorColumnNames(null, dal, SELECTOR_REFS).isEmpty());
  }

  @Test
  @DisplayName("the refusal is a 422 unknown_selector_column with the name and the alternatives")
  void envelope() throws Exception {
    JSONObject env = McpRoutingException.unknownSelectorColumn("glItemDifferenceId", "account",
        List.of("aprmGlitemDiff", "currency")).toEnvelope();
    assertEquals(422, env.getInt(McpConstants.KEY_STATUS));
    assertEquals("unknown_selector_column", env.getString(McpConstants.KEY_ERROR));
    assertEquals("glItemDifferenceId", env.getString(McpConstants.PARAM_FIELD));
    assertEquals(new JSONArray(List.of("aprmGlitemDiff", "currency")).toString(),
        env.getJSONArray(McpConstants.KEY_AVAILABLE).toString());
    assertTrue(env.getString(McpConstants.KEY_DETAIL).contains("'account'"));
  }

  @Test
  @DisplayName("a long list is truncated and the hint says where the rest is")
  void truncated() throws Exception {
    List<String> many = new ArrayList<>();
    for (int i = 0; i < McpConstants.MAX_AVAILABLE_NAMES + 5; i++) {
      many.add("c" + i);
    }
    JSONObject env = McpRoutingException.unknownSelectorColumn("x", "e", many).toEnvelope();
    assertEquals(McpConstants.MAX_AVAILABLE_NAMES,
        env.getJSONArray(McpConstants.KEY_AVAILABLE).length());
    assertTrue(env.getString(McpConstants.KEY_HINT).contains("truncated"));
  }

  @Test
  @DisplayName("handleSelectors throws it, with the tab's selector columns, not an IAE")
  void callSite() {
    String body = McpSourceScanner.stripComments(McpSourceScanner.methodBody(
        McpSourceScanner.read("com/etendoerp/go/mcp/McpToolRouter.java"), "handleSelectors"));
    assertFalse(body.contains("Column not found in table"), "the 500 must be gone");
    Matcher thrown = Pattern.compile("throw\\s+McpRoutingException\\s*\\.\\s*unknownSelectorColumn"
        + "\\s*\\([^;]*McpSelectorContextHelper\\s*\\.\\s*selectorColumnNames\\s*\\(\\s*adTab\\s*,"
        + "\\s*dalEntity\\s*,\\s*SELECTOR_REFS\\s*\\)").matcher(body);
    assertTrue(thrown.find(), body);
    Matcher virtual = Pattern.compile("NeoSelectorPolicy\\s*\\.\\s*resolveVirtualSelectorColumn")
        .matcher(body);
    assertTrue(virtual.find());
    assertTrue(virtual.start() < thrown.start(), "only after the virtual columns were tried");
  }
}
