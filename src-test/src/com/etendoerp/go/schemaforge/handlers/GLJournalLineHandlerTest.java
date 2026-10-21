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
 * All portions are Copyright © 2021–2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */
package com.etendoerp.go.schemaforge.handlers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.util.Map;

import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.openbravo.model.financialmgmt.accounting.coa.AcctSchema;
import org.openbravo.model.financialmgmt.gl.GLJournal;
import org.openbravo.model.financialmgmt.gl.GLJournalLine;

import com.etendoerp.go.schemaforge.NeoActionRecordGuard;
import com.etendoerp.go.schemaforge.NeoContext;

/**
 * ETP-5693: where a simple G/L journal line's ledger comes from — the parent journal, found from
 * whichever source the channel supplies.
 *
 * @covers com.etendoerp.go.schemaforge.handlers.GLJournalLineHandler
 */
class GLJournalLineHandlerTest {

  private MockedStatic<NeoActionRecordGuard> guard;
  private AcctSchema ledger;

  @BeforeEach
  void setUp() {
    guard = mockStatic(NeoActionRecordGuard.class);
    ledger = mock(AcctSchema.class);
    GLJournal journal = mock(GLJournal.class);
    when(journal.getAccountingSchema()).thenReturn(ledger);
    guard.when(() -> NeoActionRecordGuard.loadOwned(GLJournal.class, "journal-1")).thenReturn(journal);
  }

  @AfterEach
  void tearDown() {
    guard.close();
  }

  private static NeoContext ctx(JSONObject body, Map<String, String> query) {
    return NeoContext.builder().requestBody(body).queryParams(query).build();
  }

  private AcctSchema resolve(NeoContext context, GLJournalLine stored, Map<String, Object> body) {
    return GLJournalLineHandler.JOURNAL_LEDGER.resolve(context, stored, body);
  }

  @Test
  @DisplayName("Update: the stored line's journal")
  void storedLine() {
    GLJournal journal = mock(GLJournal.class);
    when(journal.getAccountingSchema()).thenReturn(ledger);
    GLJournalLine line = mock(GLJournalLine.class);
    when(line.getJournalEntry()).thenReturn(journal);
    assertSame(ledger, resolve(ctx(new JSONObject(), null), line, Map.of()));
  }

  @Test
  @DisplayName("MCP: the journalEntry FK property in the body")
  void bodyForeignKey() {
    assertSame(ledger, resolve(ctx(new JSONObject(), null), null,
        Map.of(GLJournalLine.PROPERTY_JOURNALENTRY, "journal-1")));
  }

  @Test
  @DisplayName("Batch: parentId in the body")
  void bodyParentId() throws Exception {
    assertSame(ledger, resolve(ctx(new JSONObject().put("parentId", "journal-1"), null), null, Map.of()));
  }

  @Test
  @DisplayName("REST: parentId in the query")
  void queryParentId() {
    assertSame(ledger, resolve(ctx(new JSONObject(), Map.of("parentId", "journal-1")), null, Map.of()));
  }

  @Test
  @DisplayName("No source (MCP has no query params): no ledger, the ledger check is skipped")
  void noSource() {
    assertNull(resolve(ctx(new JSONObject(), null), null, Map.of()));
  }

  @Test
  @DisplayName("The line's journal cannot change on update")
  void journalIsTheLedgerProperty() {
    assertEquals(GLJournalLine.PROPERTY_JOURNALENTRY, GLJournalLineHandler.JOURNAL_LEDGER.ledgerProperty());
  }
}
