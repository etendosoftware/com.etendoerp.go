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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.YearMonth;
import java.util.Arrays;
import java.util.Collections;

import org.junit.jupiter.api.Test;

import com.etendoerp.go.schemaforge.DocumentPdfMonthlyReport.Row;

/**
 * The monthly figures of generated document PDFs: volume per template, document type and channel,
 * and unique organizations per template and overall.
 *
 * @covers com.etendoerp.go.schemaforge.DocumentPdfMonthlyReport
 */
class DocumentPdfMonthlyReportTest {

  private static final YearMonth NOVEMBER = YearMonth.of(2026, 11);

  private static DocumentPdfMonthlyReport sample() {
    return DocumentPdfMonthlyReport.of(NOVEMBER, Arrays.asList(
        new Row("C1", "O1", "sales-invoice", "download", "classic", 10),
        new Row("C1", "O1", "sales-invoice", "email", "classic", 5),
        new Row("C1", "O2", "sales-invoice", "print", "modern", 3),
        // The same organization id in another tenant is another organization.
        new Row("C2", "O1", "goods-shipment", "server_render", "classic", 2),
        new Row("C2", "O3", "sales-order", "download", "compact", 4),
        new Row("C3", "O4", "sales-order", "download", "", 1),
        new Row("C3", "O5", "sales-order", "download", "compact", 0)));
  }

  @Test
  void theVolumeIsSplitByTemplateTypeAndChannel() {
    DocumentPdfMonthlyReport report = sample();

    assertAll(
        () -> assertEquals(NOVEMBER, report.getMonth()),
        () -> assertEquals(25, report.getTotal()),
        () -> assertEquals(17, report.getGenerated("classic")),
        () -> assertEquals(4, report.getGenerated("compact")),
        () -> assertEquals(3, report.getGenerated("modern")),
        () -> assertEquals(1, report.getGenerated(DocumentPdfMonthlyReport.UNKNOWN)),
        () -> assertEquals(15, report.getGenerated("sales-invoice", "classic")),
        () -> assertEquals(3, report.getGenerated("sales-invoice", "modern")),
        () -> assertEquals(0, report.getGenerated("payment-in", "classic")),
        () -> assertEquals(15, report.getByChannel("download")),
        () -> assertEquals(5, report.getByChannel("email")),
        () -> assertEquals(3, report.getByChannel("print")),
        () -> assertEquals(2, report.getByChannel("server_render")));
  }

  @Test
  void organizationsAreCountedOncePerTemplateAndOverall() {
    DocumentPdfMonthlyReport report = sample();

    assertAll(
        () -> assertEquals(5, report.getOrganizations()),
        () -> assertEquals(3, report.getTenants()),
        () -> assertEquals(2, report.getOrganizations("classic")),
        () -> assertEquals(1, report.getOrganizations("compact")),
        () -> assertEquals(1, report.getOrganizations("modern")),
        () -> assertEquals(0, report.getOrganizations("fancy")));
  }

  @Test
  void theMarkdownCarriesTheKpisAndNamesNoOrganization() {
    String markdown = sample().toMarkdown();

    assertAll(
        () -> assertTrue(markdown.contains("## Comprobantes generados — 2026-11")),
        () -> assertTrue(markdown.contains("(sin previsualizaciones): 25")),
        () -> assertTrue(markdown.contains("Organizaciones únicas: 5")),
        () -> assertTrue(markdown.contains("| classic | 17 | 2 |")),
        () -> assertTrue(markdown.contains("| unknown | 1 | 1 |")),
        () -> assertTrue(markdown.contains("| **Total** | **25** | **5** |")),
        () -> assertTrue(markdown.contains("| sales-invoice | 15 | 0 | 3 | 0 | 18 |")),
        () -> assertTrue(markdown.contains("| server_render | 2 |")),
        () -> assertFalse(markdown.contains("C1")),
        () -> assertFalse(markdown.contains("O1")));
  }

  @Test
  void anEmptyMonthReportsZerosAndNoUnknownColumn() {
    DocumentPdfMonthlyReport report = DocumentPdfMonthlyReport.of(NOVEMBER,
        Collections.emptyList());
    String markdown = report.toMarkdown();

    assertAll(
        () -> assertEquals(0, report.getTotal()),
        () -> assertEquals(0, report.getOrganizations()),
        () -> assertTrue(markdown.contains("| modern | 0 | 0 |")),
        () -> assertFalse(markdown.contains("unknown")));
  }
}
