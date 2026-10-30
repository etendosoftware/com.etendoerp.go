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

import java.util.stream.Stream;

import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import com.etendoerp.go.schemaforge.util.NeoMessageTranslator;

/**
 * ETP-5945 (in ETP-5852): every etendo_action result carries {@code success} and {@code message},
 * whatever shape the action answered with; the original keys stay.
 *
 * @covers com.etendoerp.go.mcp.McpActionEnvelope
 */
class McpActionEnvelopeTest {

  static Stream<Arguments> shapes() {
    return Stream.of(
        Arguments.of("generic button success",
            "{\"processResult\":\"success\",\"processMessage\":\"Process executed\"}", false,
            true, "Process executed"),
        Arguments.of("generic button failure",
            "{\"processResult\":\"error\",\"processMessage\":\"No lines\"}", true, false,
            "No lines"),
        Arguments.of("unknown action",
            "{\"processResult\":\"404\",\"processMessage\":\"Action not found: foo\"}", true, false,
            "Action not found: foo"),
        Arguments.of("customization success with a message key and an empty message",
            "{\"status\":\"success\",\"title\":\"@Success@\",\"message\":\"\"}", false, true,
            "T(@Success@)"),
        Arguments.of("customization success:true",
            "{\"success\":true,\"message\":\"Document posted\"}", false, true, "Document posted"),
        Arguments.of("customization refusal not flagged as error",
            "{\"success\":false,\"message\":\"No valid period\"}", false, false, "No valid period"),
        Arguments.of("routing refusal", "{\"status\":422,\"error\":\"validation_error\","
            + "\"detail\":\"Bad parameter\"}", true, false, "Bad parameter"));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("shapes")
  void addsSuccessAndMessage(String label, String body, boolean isError, boolean success,
      String message) throws Exception {
    JSONObject result = isError ? McpToolRouter.wrapAsErrorContent(body)
        : McpToolRouter.wrapAsTextContent(body);

    try (MockedStatic<NeoMessageTranslator> translator = Mockito.mockStatic(
        NeoMessageTranslator.class)) {
      translator.when(() -> NeoMessageTranslator.safeParseTranslation(Mockito.anyString()))
          .thenAnswer(inv -> {
            String text = inv.getArgument(0);
            return text.startsWith("@") ? "T(" + text + ")" : text;
          });
      McpActionEnvelope.normalize(result);
    }

    JSONObject out = new JSONObject(
        result.getJSONArray("content").getJSONObject(0).getString("text"));
    assertEquals(success, out.getBoolean("success"), label);
    assertEquals(message, out.getString("message"), label);
    JSONObject original = new JSONObject(body);
    for (java.util.Iterator<?> keys = original.keys(); keys.hasNext();) {
      String key = (String) keys.next();
      if (!"success".equals(key) && !"message".equals(key)) {
        assertEquals(String.valueOf(original.get(key)), String.valueOf(out.get(key)),
            label + " keeps " + key);
      }
    }
  }
}
