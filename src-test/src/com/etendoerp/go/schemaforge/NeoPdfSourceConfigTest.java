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
package com.etendoerp.go.schemaforge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.etendoerp.go.schemaforge.NeoPdfSourceConfig.Source;
import com.etendoerp.go.schemaforge.data.SFEntity;
import com.etendoerp.go.schemaforge.data.SFSpec;

/**
 * ETP-5715 — the {@code pdf} section of {@code MCP_CONFIG}: whether a window's PDF is one the
 * application renders (the default) or the document the user uploads as main (a purchase invoice).
 *
 * @covers com.etendoerp.go.schemaforge.NeoPdfSourceConfig
 */
@DisplayName("ETP-5715 — NeoPdfSourceConfig")
class NeoPdfSourceConfigTest {

  private static final String SUPPLIER =
      "{\"pdf\":{\"source\":\"supplier\",\"reason\":\"no printable\"}}";
  private static final String RENDERED =
      "{\"pdf\":{\"source\":\"rendered\",\"reason\":\"has a printable\"}}";

  private static SFEntity entity(String entityConfig, String specConfig) {
    SFSpec spec = mock(SFSpec.class);
    when(spec.getMCPConfig()).thenReturn(specConfig);
    SFEntity entity = mock(SFEntity.class);
    when(entity.getMCPConfig()).thenReturn(entityConfig);
    when(entity.getETGOSFSpec()).thenReturn(spec);
    return entity;
  }

  private static JSONObject body(String json) throws Exception {
    return new JSONObject(json).getJSONObject("pdf");
  }

  @Test
  @DisplayName("an entity declaring source:supplier is a supplier-document entity")
  void declaredSupplier() {
    assertEquals(Source.SUPPLIER, NeoPdfSourceConfig.sourceOf(entity(SUPPLIER, null)));
  }

  @Test
  @DisplayName("no configuration, other sections only, a null entity: rendered, the default")
  void defaultIsRendered() {
    assertEquals(Source.RENDERED, NeoPdfSourceConfig.sourceOf(entity(null, null)));
    assertEquals(Source.RENDERED, NeoPdfSourceConfig.sourceOf(entity("  ", null)));
    assertEquals(Source.RENDERED,
        NeoPdfSourceConfig.sourceOf(entity("{\"verbs\":{\"create\":false}}", null)));
    assertEquals(Source.RENDERED, NeoPdfSourceConfig.sourceOf(null));
    assertEquals(Source.RENDERED, NeoPdfSourceConfig.sourceOf(entity(RENDERED, null)));
  }

  @Test
  @DisplayName("a spec-level declaration applies unless the entity declares its own (REPLACE)")
  void specLevelAndEntityOverride() {
    assertEquals(Source.SUPPLIER, NeoPdfSourceConfig.sourceOf(entity(null, SUPPLIER)));
    assertEquals(Source.RENDERED, NeoPdfSourceConfig.sourceOf(entity(RENDERED, SUPPLIER)));
  }

  @Test
  @DisplayName("an unreadable configuration reads as rendered: never hand out a stale PDF as current")
  void unreadableIsRendered() {
    assertEquals(Source.RENDERED, NeoPdfSourceConfig.sourceOf(entity("{not json", null)));
    assertEquals(Source.RENDERED,
        NeoPdfSourceConfig.sourceOf(entity("{\"pdf\":{\"source\":\"Supplier\"}}", null)));
    assertEquals(Source.RENDERED,
        NeoPdfSourceConfig.sourceOf(entity("{\"pdf\":{\"source\":true}}", null)));
  }

  @Test
  @DisplayName("validation: a well-formed body is accepted, rendered and supplier alike")
  void validBodies() throws Exception {
    assertTrue(NeoPdfSourceConfig.validate(body(SUPPLIER)).isEmpty());
    assertTrue(NeoPdfSourceConfig.validate(body(RENDERED)).isEmpty());
  }

  @Test
  @DisplayName("validation: a missing, misspelt or non-string source is rejected, naming the legal values")
  void badSourceIsRejected() throws Exception {
    for (String json : new String[] {"{\"pdf\":{\"reason\":\"x\"}}",
        "{\"pdf\":{\"source\":\"suplier\",\"reason\":\"x\"}}",
        "{\"pdf\":{\"source\":true,\"reason\":\"x\"}}",
        "{\"pdf\":{\"source\":\"SUPPLIER\",\"reason\":\"x\"}}"}) {
      assertTrue(NeoPdfSourceConfig.validate(body(json)).toString().contains("supplier"), json);
    }
  }

  @Test
  @DisplayName("validation: the reason is mandatory so every declaration is auditable")
  void reasonIsMandatory() throws Exception {
    assertTrue(NeoPdfSourceConfig.validate(body("{\"pdf\":{\"source\":\"supplier\"}}"))
        .toString().contains("reason"));
    assertTrue(NeoPdfSourceConfig.validate(
        body("{\"pdf\":{\"source\":\"supplier\",\"reason\":\" \"}}")).toString()
        .contains("reason"));
  }
}
