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
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.openbravo.model.ad.domain.Reference;
import org.openbravo.model.ad.ui.Process;
import org.openbravo.model.ad.ui.ProcessParameter;

import com.etendoerp.go.schemaforge.NeoSelectorService;

/**
 * ETP-5848: a Classic process button publishes the parameters its process reads, so an agent
 * pressing it knows the keys, which ones it must send and the values a list takes.
 */
class McpSchemaActionFieldsTest {

  private static ProcessParameter param(String column, long seq, boolean mandatory,
      String defaultValue, String listId) {
    ProcessParameter p = mock(ProcessParameter.class);
    when(p.isActive()).thenReturn(true);
    when(p.getDBColumnName()).thenReturn(column);
    when(p.getName()).thenReturn(column + " label");
    when(p.getSequenceNumber()).thenReturn(seq);
    when(p.isMandatory()).thenReturn(mandatory);
    when(p.getDefaultValue()).thenReturn(defaultValue);
    if (listId != null) {
      Reference list = mock(Reference.class);
      when(list.getId()).thenReturn(listId);
      when(p.getReferenceSearchKey()).thenReturn(list);
    }
    return p;
  }

  @Test
  @DisplayName("parameters in sequence order: required only when mandatory with no default,"
      + " list values as options, inactive ones left out")
  void describesTheProcessParameters() throws Exception {
    ProcessParameter inactive = param("Old", 5, true, null, null);
    when(inactive.isActive()).thenReturn(false);
    List<ProcessParameter> params = List.of(
        param("DateFrom", 20, true, "@#Date@", null),
        param("action", 10, true, null, "REF-ACTION"),
        inactive,
        param("Description", 30, false, null, null));
    Process process = mock(Process.class);
    when(process.getADProcessParameterList()).thenReturn(params);

    JSONObject button = new JSONObject();
    try (MockedStatic<NeoSelectorService> labels = mockStatic(NeoSelectorService.class)) {
      labels.when(() -> NeoSelectorService.getListLabels("REF-ACTION"))
          .thenReturn(Map.of("VO", "Void", "CO", "Complete"));
      McpSchemaActionFields.addProcessParameters(button, process);
    }

    JSONArray out = button.getJSONArray(McpSchemaActionFields.KEY_PROCESS_PARAMETERS);
    assertEquals(3, out.length(), out.toString());
    JSONObject action = out.getJSONObject(0);
    assertEquals("action", action.getString("name"));
    assertTrue(action.getBoolean("required"));
    assertEquals("CO", action.getJSONArray("options").getJSONObject(0).getString("value"));
    assertEquals("VO", action.getJSONArray("options").getJSONObject(1).getString("value"));

    JSONObject dateFrom = out.getJSONObject(1);
    assertEquals("DateFrom", dateFrom.getString("name"));
    assertFalse(dateFrom.getBoolean("required"), "a default fills a mandatory parameter");
    assertEquals("@#Date@", dateFrom.getString("default"));

    JSONObject description = out.getJSONObject(2);
    assertFalse(description.getBoolean("required"));
    assertFalse(description.has("options"));
  }

  @Test
  @DisplayName("a process with no active parameter adds no key")
  void noParametersNoKey() throws Exception {
    Process process = mock(Process.class);
    when(process.getADProcessParameterList()).thenReturn(List.of());
    JSONObject button = new JSONObject();
    McpSchemaActionFields.addProcessParameters(button, process);
    assertFalse(button.has(McpSchemaActionFields.KEY_PROCESS_PARAMETERS));
  }
}
