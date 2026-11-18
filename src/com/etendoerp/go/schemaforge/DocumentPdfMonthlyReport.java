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

import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * ETP-5993 (OKR O4.KR2): one month of generated document PDFs, aggregated from the
 * {@code document.pdf.generate} usage rows ({@link DocumentPdfUsage}). Pure: built from the grouped
 * rows {@link DocumentPdfMonthlyReader} reads, so every figure is testable without a database.
 *
 * <p>The two KPIs of the template personalization: <b>documents generated per template</b> (the
 * volume of each variant) and <b>organizations using the templates</b> (the distinct organizations
 * with at least one generated document, per variant and overall). An organization is identified by
 * its tenant and its own id together. A row whose variant is not one of the three is kept under
 * {@value #UNKNOWN} so the totals always add up to the rows read.</p>
 */
final class DocumentPdfMonthlyReport {

  private static final String CELL_SEP = " | ";
  private static final String ROW_START = "| ";
  private static final String ROW_END = " |\n";

  /** The bucket of a row whose stored variant is missing or not one of the three. */
  static final String UNKNOWN = "unknown";

  /** One group of identical rows: who, what, how and with which template, and how many. */
  static final class Row {
    private final String clientId;
    private final String orgId;
    private final String docType;
    private final String channel;
    private final String variant;
    private final long count;

    Row(String clientId, String orgId, String docType, String channel, String variant,
        long count) {
      this.clientId = clientId;
      this.orgId = orgId;
      this.docType = docType;
      this.channel = channel;
      this.variant = variant;
      this.count = count;
    }
  }

  private final YearMonth month;
  private long total;
  private final Set<String> tenants = new HashSet<>();
  private final Set<String> organizations = new HashSet<>();
  private final Map<String, Long> byVariant = new LinkedHashMap<>();
  private final Map<String, Set<String>> organizationsByVariant = new LinkedHashMap<>();
  private final Map<String, Map<String, Long>> byDocType = new TreeMap<>();
  private final Map<String, Long> byChannel = new LinkedHashMap<>();

  private DocumentPdfMonthlyReport(YearMonth month) {
    this.month = month;
    for (String variant : variants()) {
      byVariant.put(variant, 0L);
      organizationsByVariant.put(variant, new HashSet<>());
    }
    for (String channel : DocumentPdfUsage.CHANNELS) {
      byChannel.put(channel, 0L);
    }
  }

  /**
   * Aggregates one month.
   *
   * @param month the calendar month the rows belong to
   * @param rows  the grouped rows; a row with no count is ignored
   * @return the month's figures
   */
  static DocumentPdfMonthlyReport of(YearMonth month, List<Row> rows) {
    DocumentPdfMonthlyReport report = new DocumentPdfMonthlyReport(month);
    for (Row row : rows) {
      if (row.count > 0) {
        report.add(row);
      }
    }
    return report;
  }

  private void add(Row row) {
    String variant = DocTemplateConfigSupport.VARIANTS.contains(row.variant) ? row.variant
        : UNKNOWN;
    String organization = row.clientId + "|" + row.orgId;
    total += row.count;
    tenants.add(row.clientId);
    organizations.add(organization);
    byVariant.merge(variant, row.count, Long::sum);
    organizationsByVariant.get(variant).add(organization);
    byDocType.computeIfAbsent(String.valueOf(row.docType), k -> new LinkedHashMap<>())
        .merge(variant, row.count, Long::sum);
    byChannel.merge(String.valueOf(row.channel), row.count, Long::sum);
  }

  YearMonth getMonth() {
    return month;
  }

  /** Every generated document of the month. */
  long getTotal() {
    return total;
  }

  /** Distinct organizations with at least one generated document, whatever the template. */
  int getOrganizations() {
    return organizations.size();
  }

  /** Distinct tenants with at least one generated document. */
  int getTenants() {
    return tenants.size();
  }

  /** Documents generated with a template ({@value #UNKNOWN} included). */
  long getGenerated(String variant) {
    return byVariant.getOrDefault(variant, 0L);
  }

  /** Distinct organizations that generated at least one document with a template. */
  int getOrganizations(String variant) {
    Set<String> found = organizationsByVariant.get(variant);
    return found == null ? 0 : found.size();
  }

  /** Documents of one type generated with a template. */
  long getGenerated(String docType, String variant) {
    return byDocType.getOrDefault(docType, Collections.emptyMap()).getOrDefault(variant, 0L);
  }

  /** Documents generated through one channel. */
  long getByChannel(String channel) {
    return byChannel.getOrDefault(channel, 0L);
  }

  /**
   * The report as Markdown, the body of the published baseline: the KPIs first, then the volume
   * per document type and template, then per channel. Identifies nobody: only counts.
   *
   * @return the Markdown text
   */
  String toMarkdown() {
    List<String> shown = shownVariants();
    StringBuilder md = new StringBuilder();
    md.append("## Comprobantes generados — ").append(month).append("\n\n");
    md.append("- Comprobantes generados (sin previsualizaciones): ").append(total).append('\n');
    md.append("- Organizaciones únicas: ").append(getOrganizations()).append('\n');
    md.append("- Entornos (tenants) únicos: ").append(getTenants()).append("\n\n");

    md.append("### Por plantilla\n\n");
    md.append("| Plantilla | Comprobantes | Organizaciones únicas |\n|---|---:|---:|\n");
    for (String variant : shown) {
      md.append(ROW_START).append(variant).append(CELL_SEP).append(getGenerated(variant))
          .append(CELL_SEP).append(getOrganizations(variant)).append(ROW_END);
    }
    md.append("| **Total** | **").append(total).append("** | **").append(getOrganizations())
        .append("** |\n\n");

    md.append("### Por tipo de comprobante y plantilla\n\n");
    md.append("| Tipo de comprobante");
    for (String variant : shown) {
      md.append(CELL_SEP).append(variant);
    }
    md.append(" | Total |\n|---");
    for (int i = 0; i <= shown.size(); i++) {
      md.append("|---:");
    }
    md.append("|\n");
    for (Map.Entry<String, Map<String, Long>> type : byDocType.entrySet()) {
      md.append(ROW_START).append(type.getKey());
      long typeTotal = 0;
      for (String variant : shown) {
        long count = type.getValue().getOrDefault(variant, 0L);
        typeTotal += count;
        md.append(CELL_SEP).append(count);
      }
      md.append(CELL_SEP).append(typeTotal).append(ROW_END);
    }
    md.append('\n');

    md.append("### Por canal\n\n| Canal | Comprobantes |\n|---|---:|\n");
    for (Map.Entry<String, Long> channel : byChannel.entrySet()) {
      md.append(ROW_START).append(channel.getKey()).append(CELL_SEP).append(channel.getValue())
          .append(ROW_END);
    }
    return md.toString();
  }

  /** The three templates, plus {@value #UNKNOWN} only when some row needed it. */
  private List<String> shownVariants() {
    List<String> shown = new ArrayList<>(DocTemplateConfigSupport.VARIANTS);
    if (getGenerated(UNKNOWN) > 0) {
      shown.add(UNKNOWN);
    }
    return shown;
  }

  private static List<String> variants() {
    List<String> variants = new ArrayList<>(DocTemplateConfigSupport.VARIANTS);
    variants.add(UNKNOWN);
    return variants;
  }
}
