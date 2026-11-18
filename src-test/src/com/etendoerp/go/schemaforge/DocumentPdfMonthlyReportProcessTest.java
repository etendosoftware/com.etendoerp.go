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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.openbravo.base.exception.OBException;
import org.openbravo.dal.core.OBContext;
import org.openbravo.erpCommon.utility.OBError;
import org.openbravo.scheduling.ProcessBundle;

import com.etendoerp.go.schemaforge.DocumentPdfMonthlyReport.Row;

/**
 * Which month the monthly process reports, and what it answers.
 *
 * @covers com.etendoerp.go.schemaforge.DocumentPdfMonthlyReportProcess
 */
class DocumentPdfMonthlyReportProcessTest {

  private static final YearMonth NOW = YearMonth.of(2026, 11);

  @Test
  void withoutAMonthThePreviousOneIsReported() {
    assertAll(
        () -> assertEquals(YearMonth.of(2026, 10),
            DocumentPdfMonthlyReportProcess.monthOf(null, null, NOW)),
        () -> assertEquals(YearMonth.of(2026, 10),
            DocumentPdfMonthlyReportProcess.monthOf("  ", "dd-MM-yyyy", NOW)),
        () -> assertEquals(YearMonth.of(2025, 12),
            DocumentPdfMonthlyReportProcess.monthOf(null, null, YearMonth.of(2026, 1))));
  }

  @Test
  void anyDateOfTheMonthNamesIt() {
    Date date = Date.from(LocalDate.of(2026, 10, 31).atStartOfDay(ZoneId.systemDefault())
        .toInstant());
    assertAll(
        () -> assertEquals(YearMonth.of(2026, 10),
            DocumentPdfMonthlyReportProcess.monthOf(date, null, NOW)),
        () -> assertEquals(YearMonth.of(2026, 9),
            DocumentPdfMonthlyReportProcess.monthOf("2026-09-15", null, NOW)),
        () -> assertEquals(YearMonth.of(2026, 9),
            DocumentPdfMonthlyReportProcess.monthOf("2026-09", null, NOW)),
        () -> assertEquals(YearMonth.of(2026, 8),
            DocumentPdfMonthlyReportProcess.monthOf("20-08-2026", "dd-MM-yyyy", NOW)));
  }

  @Test
  void aTextThatIsNotWhollyADateIsRefused() {
    assertThrows(IllegalArgumentException.class,
        () -> DocumentPdfMonthlyReportProcess.monthOf("2026-13-01", null, NOW));
    assertThrows(IllegalArgumentException.class,
        () -> DocumentPdfMonthlyReportProcess.monthOf("20-08-2026x", "dd-MM-yyyy", NOW));
  }

  @Test
  void theRunAnswersTheMonthsReportAsItsMessage() throws Exception {
    DocumentPdfMonthlyReader reader = mock(DocumentPdfMonthlyReader.class);
    when(reader.read(YearMonth.of(2026, 9))).thenReturn(Collections.singletonList(
        new Row("C1", "O1", "sales-invoice", "download", "modern", 7)));
    ProcessBundle bundle = bundleWith("datefrom", "2026-09-01");

    try (MockedStatic<OBContext> context = mockStatic(OBContext.class)) {
      new DocumentPdfMonthlyReportProcess(reader).doExecute(bundle);
    }

    ArgumentCaptor<OBError> result = ArgumentCaptor.forClass(OBError.class);
    verify(bundle).setResult(result.capture());
    assertAll(
        () -> assertEquals("Success", result.getValue().getType()),
        () -> assertEquals("Document PDF Report 2026-09", result.getValue().getTitle()),
        () -> assertTrue(result.getValue().getMessage().contains("| modern | 7 | 1 |<br/>")));
  }

  @Test
  void anInvalidMonthFailsWithoutReading() throws Exception {
    DocumentPdfMonthlyReader reader = mock(DocumentPdfMonthlyReader.class);
    ProcessBundle bundle = bundleWith("DateFrom", "someday");

    DocumentPdfMonthlyReportProcess process = new DocumentPdfMonthlyReportProcess(reader);
    assertThrows(OBException.class, () -> process.doExecute(bundle));

    verify(reader, never()).read(any());
    ArgumentCaptor<OBError> result = ArgumentCaptor.forClass(OBError.class);
    verify(bundle).setResult(result.capture());
    assertEquals("Error", result.getValue().getType());
  }

  @Test
  void theMessageIsEscaped() {
    assertEquals("a &lt;b&gt; &amp;<br/>c", DocumentPdfMonthlyReportProcess.toHtml("a <b> &\nc"));
  }

  private static ProcessBundle bundleWith(String key, Object value) {
    ProcessBundle bundle = mock(ProcessBundle.class);
    Map<String, Object> params = new HashMap<>();
    params.put(key, value);
    when(bundle.getParams()).thenReturn(params);
    return bundle;
  }
}
