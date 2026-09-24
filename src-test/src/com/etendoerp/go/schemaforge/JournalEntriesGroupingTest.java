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
package com.etendoerp.go.schemaforge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link JournalEntriesGrouping} — the pure nesting/truncation logic backing
 * {@link JournalEntriesReportHandler} (ETP-5483 slice 3). No DB, no OBContext: plain data in,
 * plain data out.
 */
class JournalEntriesGroupingTest {

  private static JournalEntriesGrouping.Row row(String factAcctGroupId, String dateacct,
      long entryNo, String accountNo, String accountName, String debit, String credit) {
    return new JournalEntriesGrouping.Row(dateacct, entryNo, "Sales Invoice", "ARI", false,
        "sales-invoice", "doc-1", null, null, "Some description", "Acme", "Widget", "Proj A",
        "CC1", factAcctGroupId, "record-1", "table-1", accountNo, accountName,
        new BigDecimal(debit), new BigDecimal(credit));
  }

  // -------------------------------------------------------------------------
  // Nesting
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("nests multiple lines of the same entry into one Entry with a lines array")
  void nestsLinesOfSameEntry() {
    List<JournalEntriesGrouping.Row> rows = List.of(
        row("grp-1", "2026-01-05", 1, "430", "Customers", "100", "0"),
        row("grp-1", "2026-01-05", 1, "700", "Sales", "0", "100"));

    List<JournalEntriesGrouping.Entry> entries = JournalEntriesGrouping.nest(rows);

    assertEquals(1, entries.size());
    JournalEntriesGrouping.Entry entry = entries.get(0);
    assertEquals("grp-1", entry.factAcctGroupId);
    assertEquals(2, entry.lines.size());
    assertEquals("430", entry.lines.get(0).accountNo);
    assertEquals("700", entry.lines.get(1).accountNo);
  }

  @Test
  @DisplayName("two different entries produce two separate Entry objects, in row order")
  void separatesDifferentEntries() {
    List<JournalEntriesGrouping.Row> rows = List.of(
        row("grp-1", "2026-01-05", 1, "430", "Customers", "100", "0"),
        row("grp-1", "2026-01-05", 1, "700", "Sales", "0", "100"),
        row("grp-2", "2026-01-06", 2, "570", "Cash", "50", "0"),
        row("grp-2", "2026-01-06", 2, "430", "Customers", "0", "50"));

    List<JournalEntriesGrouping.Entry> entries = JournalEntriesGrouping.nest(rows);

    assertEquals(2, entries.size());
    assertEquals("grp-1", entries.get(0).factAcctGroupId);
    assertEquals("grp-2", entries.get(1).factAcctGroupId);
    assertEquals(2, entries.get(0).lines.size());
    assertEquals(2, entries.get(1).lines.size());
  }

  @Test
  @DisplayName("header fields come from the first row of the entry")
  void headerFieldsComeFromFirstRow() {
    List<JournalEntriesGrouping.Row> rows = List.of(
        row("grp-1", "2026-01-05", 1, "430", "Customers", "100", "0"));

    JournalEntriesGrouping.Entry entry = JournalEntriesGrouping.nest(rows).get(0);

    assertEquals("2026-01-05", entry.dateacct);
    assertEquals(1L, entry.entryNo);
    assertEquals("Sales Invoice", entry.documentType);
    assertEquals("ARI", entry.docbasetype);
    assertEquals("sales-invoice", entry.docWindow);
    assertEquals("doc-1", entry.docRecordId);
  }

  @Test
  @DisplayName("a line carries its dimension names")
  void lineCarriesDimensionNames() {
    List<JournalEntriesGrouping.Row> rows = List.of(
        row("grp-1", "2026-01-05", 1, "430", "Customers", "100", "0"));

    JournalEntriesGrouping.Line line = JournalEntriesGrouping.nest(rows).get(0).lines.get(0);

    assertEquals("Acme", line.bpname);
    assertEquals("Widget", line.productname);
    assertEquals("Proj A", line.projectname);
    assertEquals("CC1", line.costcentername);
  }

  @Test
  @DisplayName("null/empty input never throws")
  void nullOrEmptyInputIsSafe() {
    assertTrue(JournalEntriesGrouping.nest(null).isEmpty());
    assertTrue(JournalEntriesGrouping.nest(List.of()).isEmpty());
  }

  // -------------------------------------------------------------------------
  // Truncation
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("not truncated when returned count matches total")
  void notTruncatedWhenCountsMatch() {
    assertFalse(JournalEntriesGrouping.isTruncated(200, 200));
  }

  @Test
  @DisplayName("not truncated when total is somehow lower (defensive)")
  void notTruncatedWhenTotalIsLower() {
    assertFalse(JournalEntriesGrouping.isTruncated(200, 150));
  }

  @Test
  @DisplayName("truncated when total exceeds what was returned")
  void truncatedWhenTotalExceedsReturned() {
    assertTrue(JournalEntriesGrouping.isTruncated(200, 350));
  }
}
