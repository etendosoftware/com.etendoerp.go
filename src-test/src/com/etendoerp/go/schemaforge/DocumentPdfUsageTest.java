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

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

import java.time.Instant;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.openbravo.dal.core.OBContext;

import com.etendoerp.go.supportaccess.SupportAccessGuard;
import com.etendoerp.go.usageevents.UsageEvent;
import com.etendoerp.go.usageevents.UsageEventRecorder;
import com.etendoerp.go.usageevents.UsageEventTypes;

/**
 * The shape of a generated document PDF event: what a client report keeps, what drops it, and the
 * server render's own event. Nothing of the document may survive the rebuild.
 *
 * @covers com.etendoerp.go.schemaforge.DocumentPdfUsage
 */
class DocumentPdfUsageTest {

  private static final Instant AT = Instant.parse("2026-11-03T10:15:00Z");

  private MockedStatic<OBContext> obContextStatic;

  @BeforeEach
  void setUp() {
    obContextStatic = mockStatic(OBContext.class);
    obContextStatic.when(OBContext::getOBContext).thenReturn(null);
  }

  @AfterEach
  void tearDown() {
    obContextStatic.close();
  }

  private static UsageEvent.Builder client(String docType, String channel) {
    return UsageEvent.builder()
        .clientId("C1").orgId("O1").userId("U1").roleId("R1")
        .eventType(UsageEventTypes.DOCUMENT_PDF_GENERATE)
        .source(UsageEvent.SOURCE_UI)
        .sessionKey("s1")
        .target(docType)
        .action(channel)
        .occurredAt(AT)
        .appVersion("1.2.3");
  }

  @Test
  void aClientReportKeepsTheWhoTheDateTheTypeTheChannelAndTheTemplateOnly() {
    UsageEvent sent = client(" sales-invoice ", "email")
        .errorCode("E1").durationMs(30L)
        .property("variant", "modern").property("documentNo", "FV-1").property("amount", 99.5)
        .build();

    UsageEvent kept = DocumentPdfUsage.fromClient(sent, (org, type) -> "compact");

    assertAll(
        () -> assertEquals("C1", kept.clientId()),
        () -> assertEquals("O1", kept.orgId()),
        () -> assertEquals("U1", kept.userId()),
        () -> assertEquals("R1", kept.roleId()),
        () -> assertEquals(UsageEvent.SOURCE_UI, kept.source()),
        () -> assertEquals("s1", kept.sessionKey()),
        () -> assertEquals(AT, kept.occurredAt()),
        () -> assertEquals("1.2.3", kept.appVersion()),
        () -> assertEquals("sales-invoice", kept.target()),
        () -> assertEquals("email", kept.action()),
        () -> assertEquals(UsageEvent.OUTCOME_OK, kept.outcome()),
        () -> assertNull(kept.errorCode()),
        () -> assertNull(kept.durationMs()),
        () -> assertEquals("{\"variant\":\"modern\"}", kept.properties()));
  }

  @Test
  void withoutAValidVariantTheSavedOneIsUsedAndTheDefaultWhenNoneIsSaved() {
    UsageEvent unnamed = client("sales-order", "print").property("variant", "fancy").build();

    assertEquals("{\"variant\":\"compact\"}",
        DocumentPdfUsage.fromClient(unnamed, (org, type) -> "compact").properties());
    assertEquals("{\"variant\":\"classic\"}",
        DocumentPdfUsage.fromClient(unnamed, (org, type) -> null).properties());
  }

  @Test
  void anUnreadableOrInvalidSavedTemplateDropsTheEvent() {
    UsageEvent unnamed = client("sales-order", "print").build();

    assertNull(DocumentPdfUsage.fromClient(unnamed, (org, type) -> {
      throw new IllegalStateException("db down");
    }));
    assertNull(DocumentPdfUsage.fromClient(unnamed, (org, type) -> "legacy"));
  }

  @ParameterizedTest
  @ValueSource(strings = { "preview", "server_render", "PRINT" })
  void aChannelAClientMayNotReportIsDropped(String channel) {
    assertNull(DocumentPdfUsage.fromClient(client("sales-invoice", channel).build(),
        (org, type) -> null));
  }

  @Test
  void aMissingTypeOrChannelOrAFailureIsDropped() {
    assertAll(
        () -> assertNull(DocumentPdfUsage.fromClient(null, (org, type) -> null)),
        () -> assertNull(DocumentPdfUsage.fromClient(client(null, "print").build(),
            (org, type) -> null)),
        () -> assertNull(DocumentPdfUsage.fromClient(client("sales-invoice", null).build(),
            (org, type) -> null)),
        () -> assertNull(DocumentPdfUsage.fromClient(client("listing", "print").build(),
            (org, type) -> null)),
        () -> assertNull(DocumentPdfUsage.fromClient(client("sales-invoice", "print")
            .outcome(UsageEvent.OUTCOME_ERROR).build(), (org, type) -> null)));
  }

  @Test
  void theServerRenderIsAnMcpEventInTheBaseDesign() {
    UsageEvent event = DocumentPdfUsage.serverRender("return-material-receipt");

    assertAll(
        () -> assertEquals(UsageEventTypes.DOCUMENT_PDF_GENERATE, event.eventType()),
        () -> assertEquals(UsageEvent.SOURCE_MCP, event.source()),
        () -> assertEquals("return-material-receipt", event.target()),
        () -> assertEquals(DocumentPdfUsage.CHANNEL_SERVER_RENDER, event.action()),
        () -> assertEquals(UsageEvent.OUTCOME_OK, event.outcome()),
        () -> assertEquals("{\"variant\":\"classic\"}", event.properties()));
  }

  @Test
  void aServerRenderOfNoKnownDocumentRecordsNothing() {
    assertNull(DocumentPdfUsage.serverRender(null));
    assertNull(DocumentPdfUsage.serverRender("listing"));
  }

  @Test
  void theDocumentTypesAreTheConfigurableOnesWithoutTheListPrint() {
    assertFalse(DocumentPdfUsage.DOC_TYPES.contains("listing"));
    assertEquals(DocTemplateConfigSupport.DOC_TYPES.size() - 1, DocumentPdfUsage.DOC_TYPES.size());
  }

  @Test
  void aVariantIsReadOnlyFromAWellFormedObject() {
    assertAll(
        () -> assertEquals("compact", DocumentPdfUsage.variantOf("{\"variant\":\"compact\"}")),
        () -> assertNull(DocumentPdfUsage.variantOf(null)),
        () -> assertNull(DocumentPdfUsage.variantOf("not json")),
        () -> assertNull(DocumentPdfUsage.variantOf("{\"variant\":3}")));
  }

  @Test
  void aSupportUsersClientReportAndServerRenderAreMarkedAsSupportActor() {
    try (MockedStatic<SupportAccessGuard> guard = mockStatic(SupportAccessGuard.class)) {
      guard.when(() -> SupportAccessGuard.isSupportUser("U1", "C1")).thenReturn(true);

      UsageEvent rebuilt = DocumentPdfUsage.fromClient(client("sales-invoice", "print").build(),
          (org, type) -> null);

      assertTrue(rebuilt.properties().contains("\"actor\":\"support\""));
      assertTrue(rebuilt.properties().contains("\"variant\":\"classic\""));
    }
  }

  @Test
  void recordServerRenderSubmitsAValidEventAndIgnoresInvalidTypesAndFailures() {
    try (MockedStatic<UsageEventRecorder> recorder = mockStatic(UsageEventRecorder.class)) {
      DocumentPdfUsage.recordServerRender("sales-invoice");
      DocumentPdfUsage.recordServerRender("listing");
      DocumentPdfUsage.recordServerRender(null);

      recorder.verify(() -> UsageEventRecorder.submit(any(UsageEvent.class)), times(1));

      recorder.when(() -> UsageEventRecorder.submit(any(UsageEvent.class)))
          .thenThrow(new IllegalStateException("queue down"));
      DocumentPdfUsage.recordServerRender("sales-order");
      recorder.verify(() -> UsageEventRecorder.submit(any(UsageEvent.class)), times(2));
    }
  }

  @Test
  void recordServerRenderOfAnUnknownTypeNeverReachesTheRecorder() {
    try (MockedStatic<UsageEventRecorder> recorder = mockStatic(UsageEventRecorder.class)) {
      DocumentPdfUsage.recordServerRender("nope");

      recorder.verify(() -> UsageEventRecorder.submit(any(UsageEvent.class)), never());
    }
  }
}
