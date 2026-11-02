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
package com.etendoerp.go.schemaforge.email;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mockStatic;

import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import com.etendoerp.go.schemaforge.NeoMessageText;
import com.etendoerp.go.schemaforge.util.NeoLanguage;

/**
 * ETP-6013 — the status gate a document email goes through, extracted from
 * {@code DefaultDocumentSendEmailContract}: sendable or unknown status passes, any other is refused
 * with the contract's message, the document number and the status name, in the right language.
 *
 * @covers com.etendoerp.go.schemaforge.email.DocumentSendStatusGate
 */
@DisplayName("ETP-6013 — DocumentSendStatusGate")
class DocumentSendStatusGateTest {

  private static final String KEY = "ETGO_SomeStatusRefusal";

  private static EmailDocumentRecord document(String status) {
    return new EmailDocumentRecord("Cliente", "c@example.com", "ID1", "1000123", null,
        "https://example.test/d", "client-1").withDocumentStatus(status);
  }

  @Test
  @DisplayName("a sendable status, or no status at all, is not refused")
  void sendableOrUnknownPasses() {
    assertFalse(DocumentSendStatusGate.refusal(document("CO"), "es_ES", "CO"::equals, KEY)
        .isPresent());
    assertFalse(DocumentSendStatusGate.refusal(document(null), "es_ES", "CO"::equals, KEY)
        .isPresent());
  }

  @Test
  @DisplayName("another status is refused with the contract's message, the number and the status name")
  void otherStatusIsRefused() {
    try (MockedStatic<DocumentStatusLabel> label = mockStatic(DocumentStatusLabel.class);
        MockedStatic<NeoMessageText> text = mockStatic(NeoMessageText.class)) {
      label.when(() -> DocumentStatusLabel.of("DR", "en_US")).thenReturn("Draft");
      text.when(() -> NeoMessageText.text(KEY, "en_US", "1000123", "Draft"))
          .thenReturn("1000123 is in Draft");

      Optional<String> refusal = DocumentSendStatusGate.refusal(document("DR"), "en_US",
          "CO"::equals, KEY);

      assertEquals(Optional.of("1000123 is in Draft"), refusal);
    }
  }

  @Test
  @DisplayName("no language asked: the request language words it")
  void blankLanguageFallsBackToTheRequest() {
    try (MockedStatic<NeoLanguage> language = mockStatic(NeoLanguage.class);
        MockedStatic<DocumentStatusLabel> label = mockStatic(DocumentStatusLabel.class);
        MockedStatic<NeoMessageText> text = mockStatic(NeoMessageText.class)) {
      language.when(NeoLanguage::currentCode).thenReturn("es_ES");
      label.when(() -> DocumentStatusLabel.of("DR", "es_ES")).thenReturn("Borrador");
      text.when(() -> NeoMessageText.text(KEY, "es_ES", "1000123", "Borrador"))
          .thenReturn("1000123 está en Borrador");

      assertEquals(Optional.of("1000123 está en Borrador"),
          DocumentSendStatusGate.refusal(document("DR"), " ", "CO"::equals, KEY));
      language.when(NeoLanguage::currentCode).thenThrow(new IllegalStateException("no context"));
      assertEquals(null, DocumentSendStatusGate.currentLanguage());
    }
  }
}
