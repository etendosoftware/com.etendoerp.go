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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.etendoerp.go.schemaforge.data.SFEntity;
import com.etendoerp.go.schemaforge.data.SFSpec;

/**
 * ETP-5715 — the {@code attachments} section of {@code MCP_CONFIG}: the declaration that a window
 * has no Attachments tab in the application, so the MCP offers none either.
 *
 * @covers com.etendoerp.go.schemaforge.NeoAttachmentsConfig
 */
@DisplayName("ETP-5715 — NeoAttachmentsConfig")
class NeoAttachmentsConfigTest {

  private static final String OFF =
      "{\"attachments\":{\"enabled\":false,\"reason\":\"no tab in the app\"}}";

  private static SFEntity entity(String entityConfig, String specConfig) {
    SFSpec spec = mock(SFSpec.class);
    when(spec.getMCPConfig()).thenReturn(specConfig);
    SFEntity entity = mock(SFEntity.class);
    when(entity.getMCPConfig()).thenReturn(entityConfig);
    when(entity.getETGOSFSpec()).thenReturn(spec);
    return entity;
  }

  @Test
  @DisplayName("a root entity declaring enabled:false is refused with the application-parity message")
  void flaggedEntityIsRefused() {
    String refusal = NeoAttachmentsConfig.refusalFor(entity(OFF, null));

    assertEquals("Attachments are not available for this document type, as in the application.",
        refusal, "exactly this sentence: no internal detail reaches the agent");
  }

  @Test
  @DisplayName("no configuration, other sections only, or enabled:true leave attachments available")
  void unflaggedIsAvailable() {
    assertNull(NeoAttachmentsConfig.refusalFor(entity(null, null)));
    assertNull(NeoAttachmentsConfig.refusalFor(entity("  ", null)));
    assertNull(NeoAttachmentsConfig.refusalFor(entity("{\"verbs\":{\"create\":false}}", null)));
    assertNull(NeoAttachmentsConfig.refusalFor(
        entity("{\"attachments\":{\"enabled\":true,\"reason\":\"x\"}}", null)));
    assertNull(NeoAttachmentsConfig.refusalFor(null));
  }

  @Test
  @DisplayName("a spec-level declaration applies unless the entity declares its own (REPLACE)")
  void specLevelAndEntityOverride() {
    assertNotNull(NeoAttachmentsConfig.refusalFor(entity(null, OFF)));
    assertNull(NeoAttachmentsConfig.refusalFor(entity(
        "{\"attachments\":{\"enabled\":true,\"reason\":\"this one does\"}}", OFF)));
  }

  @Test
  @DisplayName("any enabled that is not exactly the boolean true refuses: \"false\", 0, null, absent")
  void onlyBooleanTrueOpensAttachments() {
    for (String enabled : new String[] {"\"false\"", "\"true\"", "0", "1", "null", "[]"}) {
      assertNotNull(NeoAttachmentsConfig.refusalFor(entity(
          "{\"attachments\":{\"enabled\":" + enabled + ",\"reason\":\"x\"}}", null)), enabled);
    }
    assertNotNull(NeoAttachmentsConfig.refusalFor(
        entity("{\"attachments\":{\"reason\":\"x\"}}", null)), "section without enabled");
    assertNull(NeoAttachmentsConfig.refusalFor(
        entity("{\"attachments\":{\"enabled\":true,\"reason\":\"x\"}}", null)));
  }

  @Test
  @DisplayName("an unreadable configuration fails CLOSED: attachments are refused, never opened")
  void malformedConfigurationFailsClosed() {
    assertEquals("Attachments are not available for this document type, as in the application.",
        NeoAttachmentsConfig.refusalFor(entity("{not json", null)));
    assertNotNull(NeoAttachmentsConfig.refusalFor(entity("{\"attachments\":\"false\"}", null)));
  }

  @Test
  @DisplayName("the section validator wants a boolean enabled and a reason, and no other key")
  void validatorRules() throws Exception {
    assertEquals(List.of(), NeoAttachmentsConfig.validate(new JSONObject(
        "{\"enabled\":false,\"reason\":\"x\"}")));
    assertEquals(1, NeoAttachmentsConfig.validate(new JSONObject("{\"reason\":\"x\"}")).size());
    assertEquals(1, NeoAttachmentsConfig.validate(new JSONObject(
        "{\"enabled\":\"false\",\"reason\":\"x\"}")).size());
    assertEquals(1, NeoAttachmentsConfig.validate(new JSONObject("{\"enabled\":false}")).size());
  }
}
