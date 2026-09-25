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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.openbravo.model.ad.system.Language;
import org.openbravo.model.ad.ui.Process;
import org.openbravo.model.ad.ui.Window;

import com.etendoerp.go.schemaforge.data.SFSpec;

class McpToolTitlesTest {

  private static final String[] FIXED_TOOLS = { "neo_discover", "neo_list", "neo_get",
      "neo_create", "neo_update", "neo_delete", "neo_selectors", "neo_defaults", "neo_schema",
      "neo_batch", "neo_action", "neo_widget", "neo_vector_search", "neo_upload_image",
      "neo_request_image_upload", "neo_get_image_upload", "neo_generate_amortization_plan",
      "neo_feedback", "docs" };

  @Test
  @DisplayName("Fixed tools resolve from the catalog in the user's language")
  void fixedToolsAreLocalized() {
    assertEquals("List records", McpToolTitles.of(McpConstants.TOOL_NEO_LIST, "en_US"));
    assertEquals("Listar registros", McpToolTitles.of(McpConstants.TOOL_NEO_LIST, "es_ES"));
    assertEquals("Buscar en la documentación", McpToolTitles.of(McpConstants.TOOL_DOCS, "es_ES"));
  }

  @Test
  @DisplayName("Every Spanish variant is served by the es catalog")
  void spanishVariantsShareTheCatalog() {
    assertEquals("Listar registros", McpToolTitles.of(McpConstants.TOOL_NEO_LIST, "es_AR"));
  }

  @Test
  @DisplayName("An unknown or blank language falls back to English")
  void unknownLanguageFallsBackToEnglish() {
    assertEquals("List records", McpToolTitles.of(McpConstants.TOOL_NEO_LIST, "pt_BR"));
    assertEquals("List records", McpToolTitles.of(McpConstants.TOOL_NEO_LIST, null));
    assertEquals("List records", McpToolTitles.of(McpConstants.TOOL_NEO_LIST, " "));
  }

  @ParameterizedTest
  @ValueSource(strings = { "en_US", "es_ES" })
  @DisplayName("Every fixed tool has a catalog title and none exposes the neo prefix")
  void everyFixedToolHasANeoFreeTitle(String language) {
    for (String tool : FIXED_TOOLS) {
      String title = McpToolTitles.of(tool, language);
      assertFalse(title.toLowerCase().contains("neo"), tool + " -> " + title);
      assertFalse(title.contains("_"), tool + " is not in the catalog: " + title);
    }
  }

  @Test
  @DisplayName("Tools missing from the catalog are humanized, dropping the neo prefix")
  void uncatalogedToolsAreHumanized() {
    assertEquals("Complete order", McpToolTitles.of("complete_order", "es_ES"));
    assertEquals("Generate tax report", McpToolTitles.of("generate_tax_report", "en_US"));
    assertEquals("Some new tool", McpToolTitles.of("neo_some_new_tool", "en_US"));
  }

  @Test
  @DisplayName("Null, blank and degenerate names do not throw")
  void degenerateNamesAreSafe() {
    assertEquals("", McpToolTitles.of(null, "es_ES"));
    assertEquals("", McpToolTitles.of("  ", "es_ES"));
    assertEquals("neo_", McpToolTitles.of("neo_", "es_ES"));
  }

  @Test
  @DisplayName("An explicit title on the definition wins over the catalog")
  void explicitTitleWins() {
    McpToolDefinition withTitle = new McpToolDefinition("complete_order", "d", null,
        "Completar pedido");
    McpToolDefinition withoutTitle = new McpToolDefinition(McpConstants.TOOL_NEO_GET, "d", null);
    assertEquals("Completar pedido", McpToolTitles.resolve(withTitle, "en_US"));
    assertEquals("Obtener registro", McpToolTitles.resolve(withoutTitle, "es_ES"));
  }

  @Test
  @DisplayName("A spec title is the translated AD_Process name, else the AD_Window name")
  void specTitleComesFromTheTranslatedAdName() {
    Language language = mock(Language.class);

    Process process = mock(Process.class);
    when(process.getId()).thenReturn("P1");
    when(process.get(Process.PROPERTY_NAME, language, "P1")).thenReturn(" Completar pedido ");
    SFSpec processSpec = mock(SFSpec.class);
    when(processSpec.getProcess()).thenReturn(process);
    assertEquals("Completar pedido", McpToolTitles.fromSpec(processSpec, language));

    Window window = mock(Window.class);
    when(window.getId()).thenReturn("W1");
    when(window.get(Window.PROPERTY_NAME, language, "W1")).thenReturn("Informe de impuestos");
    SFSpec windowSpec = mock(SFSpec.class);
    when(windowSpec.getADWindow()).thenReturn(window);
    assertEquals("Informe de impuestos", McpToolTitles.fromSpec(windowSpec, language));
  }

  @Test
  @DisplayName("A spec with no AD process or window, or a blank name, yields no title")
  void specWithoutAdNameYieldsNull() {
    assertNull(McpToolTitles.fromSpec(mock(SFSpec.class), null));

    Process process = mock(Process.class);
    when(process.getId()).thenReturn("P1");
    when(process.get(Process.PROPERTY_NAME, null, "P1")).thenReturn("  ");
    SFSpec spec = mock(SFSpec.class);
    when(spec.getProcess()).thenReturn(process);
    assertNull(McpToolTitles.fromSpec(spec, null));
  }
}
