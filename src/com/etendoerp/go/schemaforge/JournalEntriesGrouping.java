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

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure post-processing for {@link JournalEntriesReportHandler} (ETP-5483 slice 3): folds the
 * flat, line-grain rows {@code JournalEntriesReportHandler}'s SQL returns — already ordered by
 * {@code dateacct, fact_acct_group_id, min_seqno} — into one {@link Entry} per journal entry
 * ({@code fact_acct_group_id}), each carrying its header fields once and its account lines as a
 * nested list.
 *
 * <p><b>Why this exists as its own pure class.</b> Same rationale as {@link TrialBalanceFolding}:
 * no DB, no OBContext, no JSON — plain data in, plain data out — so the nesting and truncation
 * rules can be unit tested directly without spinning up Etendo. Unlike {@code report-grouping.js}
 * (whose {@code buildNestedGroups}/{@code resolveGrouping} are gated {@code type !==
 * 'grouped-listing'} and therefore never run for this report, {@code report-trial-balance}'s own
 * SQL type), the SPA renders Libro de Diario's {@code grouped-listing} contract with its OWN,
 * simpler nesting baked into the report templates — there is no shared JS module to mirror
 * byte-for-byte here. This class is this handler's own design for the MCP shape, following the
 * task's explicit "nested: one object per journal entry with header fields and a lines array"
 * instruction, not a port of existing JS.</p>
 *
 * <p><b>Grouping key.</b> Rows are grouped by {@code fact_acct_group_id} alone, relying on the
 * SQL's own {@code ORDER BY dateacct, fact_acct_group_id, min_seqno} to keep every row of the
 * same entry contiguous — exactly like {@code TrialBalanceFolding}'s fold key relies on its
 * caller's SQL ordering. A change to that ORDER BY breaks this grouping silently; see
 * {@link JournalEntriesReportHandler}'s own SQL javadoc.</p>
 */
final class JournalEntriesGrouping {

  private JournalEntriesGrouping() {
    // Static utility, never instantiated.
  }

  /** One flat, line-grain input row — the shape {@link JournalEntriesReportHandler}'s SQL returns. */
  static final class Row {
    final String dateacct;
    final long entryNo;
    final String documentType;
    final String docbasetype;
    final boolean isReturn;
    final String docWindow;
    final String docRecordId;
    final String docQueryKey;
    final String docQueryValue;
    final String entryDescription;
    final String bpname;
    final String productname;
    final String projectname;
    final String costcentername;
    final String factAcctGroupId;
    final String recordId;
    final String adTableId;
    final String accountNo;
    final String accountName;
    final BigDecimal amtacctdr;
    final BigDecimal amtacctcr;

    Row(String dateacct, long entryNo, String documentType, String docbasetype, boolean isReturn,
        String docWindow, String docRecordId, String docQueryKey, String docQueryValue,
        String entryDescription, String bpname, String productname, String projectname,
        String costcentername, String factAcctGroupId, String recordId, String adTableId,
        String accountNo, String accountName, BigDecimal amtacctdr, BigDecimal amtacctcr) {
      this.dateacct = dateacct;
      this.entryNo = entryNo;
      this.documentType = documentType;
      this.docbasetype = docbasetype;
      this.isReturn = isReturn;
      this.docWindow = docWindow;
      this.docRecordId = docRecordId;
      this.docQueryKey = docQueryKey;
      this.docQueryValue = docQueryValue;
      this.entryDescription = entryDescription;
      this.bpname = bpname;
      this.productname = productname;
      this.projectname = projectname;
      this.costcentername = costcentername;
      this.factAcctGroupId = factAcctGroupId;
      this.recordId = recordId;
      this.adTableId = adTableId;
      this.accountNo = accountNo;
      this.accountName = accountName;
      this.amtacctdr = amtacctdr == null ? BigDecimal.ZERO : amtacctdr;
      this.amtacctcr = amtacctcr == null ? BigDecimal.ZERO : amtacctcr;
    }
  }

  /** One account line of a journal entry. */
  static final class Line {
    final String accountNo;
    final String accountName;
    final BigDecimal amtacctdr;
    final BigDecimal amtacctcr;
    final String bpname;
    final String productname;
    final String projectname;
    final String costcentername;

    Line(String accountNo, String accountName, BigDecimal amtacctdr, BigDecimal amtacctcr,
        String bpname, String productname, String projectname, String costcentername) {
      this.accountNo = accountNo;
      this.accountName = accountName;
      this.amtacctdr = amtacctdr;
      this.amtacctcr = amtacctcr;
      this.bpname = bpname;
      this.productname = productname;
      this.projectname = projectname;
      this.costcentername = costcentername;
    }
  }

  /** One nested journal entry: header fields once, its account lines as a list. */
  static final class Entry {
    final String factAcctGroupId;
    final long entryNo;
    final String dateacct;
    final String documentType;
    final String docbasetype;
    final boolean isReturn;
    final String entryDescription;
    final String docWindow;
    final String docRecordId;
    final String docQueryKey;
    final String docQueryValue;
    final String recordId;
    final String adTableId;
    final List<Line> lines = new ArrayList<>();

    Entry(Row first) {
      this.factAcctGroupId = first.factAcctGroupId;
      this.entryNo = first.entryNo;
      this.dateacct = first.dateacct;
      this.documentType = first.documentType;
      this.docbasetype = first.docbasetype;
      this.isReturn = first.isReturn;
      this.entryDescription = first.entryDescription;
      this.docWindow = first.docWindow;
      this.docRecordId = first.docRecordId;
      this.docQueryKey = first.docQueryKey;
      this.docQueryValue = first.docQueryValue;
      this.recordId = first.recordId;
      this.adTableId = first.adTableId;
    }
  }

  /**
   * Nests {@code rows} into one {@link Entry} per {@code fact_acct_group_id}, in the order rows
   * first appear (which is the SQL's own {@code ORDER BY dateacct, fact_acct_group_id,
   * min_seqno}) — a {@link LinkedHashMap} keeps entry order stable without re-sorting, since the
   * rows arrive already correctly ordered.
   *
   * @param rows the flat, line-grain rows, already ordered by the caller's SQL
   * @return one {@link Entry} per distinct {@code fact_acct_group_id}, each with its lines in
   *     the order they appeared
   */
  static List<Entry> nest(List<Row> rows) {
    Map<String, Entry> byGroup = new LinkedHashMap<>();
    if (rows != null) {
      for (Row r : rows) {
        Entry entry = byGroup.computeIfAbsent(r.factAcctGroupId, k -> new Entry(r));
        entry.lines.add(new Line(r.accountNo, r.accountName, r.amtacctdr, r.amtacctcr,
            r.bpname, r.productname, r.projectname, r.costcentername));
      }
    }
    return new ArrayList<>(byGroup.values());
  }

  /**
   * Whether the response was cut short by the entries cap: {@code true} when the caller knows
   * there are more distinct entries in the period than the {@code returnedEntryCount} this
   * response actually carries. Pure boolean comparison, kept as its own method so the truncation
   * rule (never "cut an entry in half" — the SQL-level {@code entry_no <= limit} filter already
   * guarantees whole entries only) is unit-testable without a DB round trip.
   *
   * @param returnedEntryCount number of entries actually nested into this response
   * @param totalEntries       total distinct entries matching the filters, ignoring the cap
   * @return {@code true} when {@code totalEntries} exceeds what was actually returned
   */
  static boolean isTruncated(int returnedEntryCount, long totalEntries) {
    return totalEntries > returnedEntryCount;
  }
}
