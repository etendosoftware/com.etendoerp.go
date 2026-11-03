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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.openbravo.model.ad.datamodel.Column;

/**
 * ETP-5848: a text field says how many characters it takes; nothing else does.
 *
 * @covers com.etendoerp.go.mcp.McpSchemaFieldBuilder
 */
class McpSchemaMaxLengthTest {

  private static Column column(Long length) {
    Column col = mock(Column.class);
    when(col.getLength()).thenReturn(length);
    return col;
  }

  @Test
  @DisplayName("a text field carries its column length")
  void textFieldCarriesItsLength() throws Exception {
    JSONObject field = new JSONObject();
    McpSchemaFieldBuilder.addMaxLength(field, column(40L), McpConstants.TYPE_STRING);
    assertEquals(40L, field.getLong(McpSchemaFieldBuilder.KEY_MAX_LENGTH));
  }

  @Test
  @DisplayName("an id, a number or a text column with no length carries none")
  void otherFieldsCarryNone() throws Exception {
    for (String type : new String[] { "foreignKey", "number", "date", "list" }) {
      JSONObject field = new JSONObject();
      McpSchemaFieldBuilder.addMaxLength(field, column(32L), type);
      assertFalse(field.has(McpSchemaFieldBuilder.KEY_MAX_LENGTH), type);
    }
    for (Long length : new Long[] { null, 0L }) {
      JSONObject field = new JSONObject();
      McpSchemaFieldBuilder.addMaxLength(field, column(length), McpConstants.TYPE_STRING);
      assertFalse(field.has(McpSchemaFieldBuilder.KEY_MAX_LENGTH), String.valueOf(length));
    }
  }
}
