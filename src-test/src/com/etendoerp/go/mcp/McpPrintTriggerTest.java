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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import com.etendoerp.go.schemaforge.NeoPrintAfterComplete;
import com.etendoerp.go.schemaforge.data.SFEntity;
import com.etendoerp.go.schemaforge.data.SFSpec;

/**
 * ETP-6013 — the MCP action path queues the document's server-side PDF only after a success, in the
 * MCP's PDF language, and never changes the action's answer.
 *
 * @covers com.etendoerp.go.mcp.McpPrintTrigger
 */
@DisplayName("ETP-6013 — McpPrintTrigger")
class McpPrintTriggerTest {

  private static final String SPEC = "sales-invoice";

  private static JSONObject args() throws Exception {
    return new JSONObject().put("entity", "header").put("id", "INV1").put("action", "DocAction");
  }

  @Test
  @DisplayName("a successful action queues the PDF of its document, in the email tool's default language")
  void successQueuesThePdf() throws Exception {
    JSONObject answer = new JSONObject().put("success", true);
    SFSpec spec = mock(SFSpec.class);
    when(spec.getId()).thenReturn("SPEC1");
    SFEntity entity = mock(SFEntity.class);
    try (MockedStatic<McpToolRouterSupport> support = mockStatic(McpToolRouterSupport.class);
        MockedStatic<NeoPrintAfterComplete> print = mockStatic(NeoPrintAfterComplete.class)) {
      support.when(() -> McpToolRouterSupport.findActiveSpecByName(SPEC)).thenReturn(spec);
      support.when(() -> McpToolRouterSupport.findIncludedEntity("SPEC1", "header"))
          .thenReturn(entity);

      JSONObject result = McpPrintTrigger.queueingServerPdf((s, a) -> answer).run(SPEC, args());

      assertSame(answer, result, "the action's answer is returned untouched");
      print.verify(() -> NeoPrintAfterComplete.afterAction(entity, "INV1", "DocAction", true,
          McpDocumentEmailRequest.DEFAULT_LANGUAGE));
    }
  }

  @Test
  @DisplayName("an action that answered an error queues nothing")
  void errorQueuesNothing() throws Exception {
    JSONObject answer = new JSONObject().put("isError", true);
    try (MockedStatic<NeoPrintAfterComplete> print = mockStatic(NeoPrintAfterComplete.class)) {
      assertSame(answer, McpPrintTrigger.queueingServerPdf((s, a) -> answer).run(SPEC, args()));
      print.verify(() -> NeoPrintAfterComplete.afterAction(any(), anyString(), anyString(),
          anyBoolean(), anyString()), never());
    }
  }

  @Test
  @DisplayName("a spec or entity that cannot be resolved never reaches the action's answer")
  void lookupFailureIsSwallowed() throws Exception {
    try (MockedStatic<McpToolRouterSupport> support = mockStatic(McpToolRouterSupport.class)) {
      support.when(() -> McpToolRouterSupport.findActiveSpecByName(SPEC))
          .thenThrow(new IllegalStateException("no spec"));

      assertDoesNotThrow(() -> McpPrintTrigger.queue(SPEC, args()));
    }
  }
}
