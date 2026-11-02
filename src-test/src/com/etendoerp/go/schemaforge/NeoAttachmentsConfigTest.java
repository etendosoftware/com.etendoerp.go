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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.util.List;

import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.MockedStatic;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.system.Language;

import com.etendoerp.go.schemaforge.data.SFEntity;
import com.etendoerp.go.schemaforge.data.SFSpec;
import com.etendoerp.go.schemaforge.util.NeoLanguage;

/**
 * The {@code attachments} section of {@code MCP_CONFIG}: the declaration that a window has no
 * Attachments tab in the application, so the MCP offers none either (ETP-5715), and the read-only
 * mode that keeps list and download and refuses every write with a declared, translated message
 * (ETP-6013).
 *
 * @covers com.etendoerp.go.schemaforge.NeoAttachmentsConfig
 */
@DisplayName("NeoAttachmentsConfig")
class NeoAttachmentsConfigTest {

  private static final String OFF =
      "{\"attachments\":{\"enabled\":false,\"reason\":\"no tab in the app\"}}";
  private static final String READ_ONLY_TAX = "{\"attachments\":{\"readOnly\":true,"
      + "\"reason\":\"product decision\",\"messageKey\":\"ETGO_TaxNotModifiable\"}}";
  private static final String READ_ONLY_DEFAULT =
      "{\"attachments\":{\"readOnly\":true,\"reason\":\"product decision\"}}";
  private static final String UNAVAILABLE =
      "Attachments are not available for this document type, as in the application.";

  private static SFEntity entity(String entityConfig, String specConfig) {
    SFSpec spec = mock(SFSpec.class);
    when(spec.getMCPConfig()).thenReturn(specConfig);
    SFEntity entity = mock(SFEntity.class);
    when(entity.getMCPConfig()).thenReturn(entityConfig);
    when(entity.getETGOSFSpec()).thenReturn(spec);
    return entity;
  }

  /** The refusal text of a write, or {@code null} when the write is available. */
  private static String writeRefusal(SFEntity entity) {
    NeoAttachmentsConfig.Refusal refusal = NeoAttachmentsConfig.refusalFor(entity, true);
    return refusal == null ? null : refusal.getMessage();
  }

  /**
   * Runs the check with {@code language} as the request language and no usable database, so every
   * declared message comes from the versioned {@code neo_hints_<lang>} catalog.
   */
  private static NeoAttachmentsConfig.Refusal inLanguage(String language, SFEntity entity,
      boolean write) {
    OBDal dal = mock(OBDal.class);
    when(dal.getConnection()).thenThrow(new IllegalStateException("no database in this test"));
    OBContext context = contextIn(language);
    // OBContext is mocked so setAdminMode/restorePreviousMode never run for real without a context.
    try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class);
         MockedStatic<NeoLanguage> lang = mockStatic(NeoLanguage.class);
         MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      obContext.when(OBContext::getOBContext).thenReturn(context);
      lang.when(NeoLanguage::currentCode).thenReturn(language);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      return NeoAttachmentsConfig.refusalFor(entity, write);
    }
  }

  /** A session context whose language is {@code language}. */
  private static OBContext contextIn(String language) {
    Language adLanguage = mock(Language.class);
    when(adLanguage.getLanguage()).thenReturn(language);
    OBContext context = mock(OBContext.class);
    when(context.getLanguage()).thenReturn(adLanguage);
    return context;
  }

  @Test
  @DisplayName("enabled:false refuses reads and writes with the application-parity message")
  void flaggedEntityIsRefused() {
    for (boolean write : new boolean[] {true, false}) {
      NeoAttachmentsConfig.Refusal refusal = NeoAttachmentsConfig.refusalFor(entity(OFF, null),
          write);
      assertEquals(UNAVAILABLE, refusal.getMessage(),
          "exactly this sentence: no internal detail reaches the agent");
      assertFalse(refusal.isReadOnly(), "enabled:false refuses reads too: not a read-only refusal");
    }
  }

  @Test
  @DisplayName("no configuration, other sections only, or enabled:true leave attachments available")
  void unflaggedIsAvailable() {
    assertNull(writeRefusal(entity(null, null)));
    assertNull(writeRefusal(entity("  ", null)));
    assertNull(writeRefusal(entity("{\"verbs\":{\"create\":false}}", null)));
    assertNull(writeRefusal(entity("{\"attachments\":{\"enabled\":true,\"reason\":\"x\"}}", null)));
    assertNull(writeRefusal(null));
  }

  @Test
  @DisplayName("a spec-level declaration applies unless the entity declares its own (REPLACE)")
  void specLevelAndEntityOverride() {
    assertNotNull(writeRefusal(entity(null, OFF)));
    assertNull(writeRefusal(entity(
        "{\"attachments\":{\"enabled\":true,\"reason\":\"this one does\"}}", OFF)));
    assertNull(NeoAttachmentsConfig.refusalFor(entity(null, READ_ONLY_DEFAULT), false),
        "a spec-level readOnly keeps the reads");
  }

  @Test
  @DisplayName("any enabled that is not exactly the boolean true refuses: \"false\", 0, null, absent")
  void onlyBooleanTrueOpensAttachments() {
    for (String enabled : new String[] {"\"false\"", "\"true\"", "0", "1", "null", "[]"}) {
      assertNotNull(writeRefusal(entity(
          "{\"attachments\":{\"enabled\":" + enabled + ",\"reason\":\"x\"}}", null)), enabled);
    }
    assertNotNull(writeRefusal(entity("{\"attachments\":{\"reason\":\"x\"}}", null)),
        "section without enabled");
  }

  @Test
  @DisplayName("an unreadable configuration fails CLOSED: attachments are refused, never opened")
  void malformedConfigurationFailsClosed() {
    assertEquals(UNAVAILABLE, writeRefusal(entity("{not json", null)));
    assertNotNull(writeRefusal(entity("{\"attachments\":\"false\"}", null)));
    assertNotNull(NeoAttachmentsConfig.refusalFor(entity("{not json", null), false),
        "a read on an unreadable configuration is refused too");
  }

  /**
   * ETP-6013 — the read-only mode, row by row: which sections keep the reads, which refuse the
   * write, and which are not a well-formed read-only mode and close everything (fail-closed).
   */
  @ParameterizedTest(name = "{0} -> read refused={1}, write refused={2}, read-only refusal={3}")
  @CsvSource(delimiter = '|', value = {
      "{\"readOnly\":true,\"reason\":\"x\"}                   | false | true  | true",
      "{\"readOnly\":true,\"enabled\":true,\"reason\":\"x\"}  | false | true  | true",
      "{\"readOnly\":true,\"enabled\":false,\"reason\":\"x\"} | true  | true  | false",
      "{\"readOnly\":\"true\",\"reason\":\"x\"}               | true  | true  | false",
      "{\"readOnly\":false,\"reason\":\"x\"}                  | true  | true  | false",
      "{\"readOnly\":false,\"enabled\":true,\"reason\":\"x\"} | false | false | false",
  })
  @DisplayName("readOnly:true keeps list/download and refuses writes; anything malformed closes")
  void readOnlyMode(String section, boolean readRefused, boolean writeRefused,
      boolean readOnlyRefusal) {
    SFEntity entity = entity("{\"attachments\":" + section + "}", null);
    NeoAttachmentsConfig.Refusal read = inLanguage("en_US", entity, false);
    NeoAttachmentsConfig.Refusal write = inLanguage("en_US", entity, true);

    assertEquals(readRefused, read != null, "read");
    assertEquals(writeRefused, write != null, "write");
    if (write != null) {
      assertEquals(readOnlyRefusal, write.isReadOnly(), "kind of the write refusal");
    }
  }

  /**
   * ETP-6013 — a write on a read-only type answers the declared {@code messageKey} in the request
   * language (the tax decision), or the default read-only message when none is declared.
   */
  @ParameterizedTest(name = "{0} / {1}")
  @CsvSource(delimiter = '|', value = {
      "tax     | es_ES | Los impuestos no se pueden modificar.",
      "tax     | en_US | Taxes cannot be modified.",
      "default | es_ES | Los adjuntos de este tipo de documento no se pueden modificar.",
      "default | en_US | The attachments of this document type cannot be modified.",
  })
  @DisplayName("a read-only refusal answers the declared message key, translated")
  void readOnlyRefusalIsTranslated(String config, String language, String expected) {
    SFEntity entity = entity("tax".equals(config) ? READ_ONLY_TAX : READ_ONLY_DEFAULT, null);

    NeoAttachmentsConfig.Refusal refusal = inLanguage(language, entity, true);

    assertEquals(expected, refusal.getMessage());
    assertTrue(refusal.isReadOnly());
  }

  @Test
  @DisplayName("the section validator wants a mode, a reason and a key-shaped messageKey")
  void validatorRules() throws Exception {
    assertEquals(List.of(), NeoAttachmentsConfig.validate(new JSONObject(
        "{\"enabled\":false,\"reason\":\"x\"}")));
    assertEquals(List.of(), NeoAttachmentsConfig.validate(new JSONObject(
        "{\"readOnly\":true,\"reason\":\"x\",\"messageKey\":\"ETGO_TaxNotModifiable\"}")));
    String[] oneProblem = {
        "{\"reason\":\"x\"}",
        "{\"enabled\":\"false\",\"reason\":\"x\"}",
        "{\"enabled\":false}",
        "{\"readOnly\":true,\"enabled\":false,\"reason\":\"x\"}",
        "{\"readOnly\":false,\"reason\":\"x\"}",
        "{\"readOnly\":true,\"reason\":\"x\",\"messageKey\":\"Taxes cannot be modified.\"}",
        "{\"readOnly\":true,\"reason\":\"x\",\"messageKey\":42}",
    };
    for (String body : oneProblem) {
      assertEquals(1, NeoAttachmentsConfig.validate(new JSONObject(body)).size(), body);
    }
  }
}
