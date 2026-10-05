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
 * All portions are Copyright (C) 2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 *************************************************************************
 */
package com.etendoerp.go.mcp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.junit.Test;

/**
 * Guards the shape of the {@code ETGO_MCP_USAGE} INSERT issued by {@link McpUsageLogger}.
 *
 * <p>{@code STATUS} and {@code REVIEWED_BY} (ETP-5594) belong to whoever reviews a row, not to the
 * writer: a new row is pending, which is NULL. The INSERT therefore names its columns explicitly
 * and must never list either of them.</p>
 */
public class McpUsageLoggerInsertSqlTest {

  private static String insertSql() throws Exception {
    Field field = McpUsageLogger.class.getDeclaredField("INSERT_SQL");
    field.setAccessible(true);
    return (String) field.get(null);
  }

  private static List<String> split(String csv) {
    List<String> parts = new ArrayList<>();
    for (String part : csv.split(",")) {
      parts.add(part.trim().toLowerCase(Locale.ROOT));
    }
    return parts;
  }

  private static List<String> insertedColumns(String sql) {
    return split(sql.substring(sql.indexOf('(') + 1, sql.indexOf(')')));
  }

  private static List<String> insertedValues(String sql) {
    String values = sql.substring(sql.toUpperCase(Locale.ROOT).indexOf("VALUES"));
    return split(values.substring(values.indexOf('(') + 1, values.lastIndexOf(')')));
  }

  @Test
  public void insertLeavesTheReviewStatusPending() throws Exception {
    List<String> columns = insertedColumns(insertSql());

    assertFalse("the writer must not set STATUS; a new row is pending (NULL)",
        columns.contains("status"));
    assertFalse("the writer must not set REVIEWED_BY", columns.contains("reviewed_by"));
  }

  @Test
  public void insertNamesAsManyColumnsAsItBindsValues() throws Exception {
    String sql = insertSql();

    assertEquals(insertedColumns(sql).size(), insertedValues(sql).size());
  }
}
