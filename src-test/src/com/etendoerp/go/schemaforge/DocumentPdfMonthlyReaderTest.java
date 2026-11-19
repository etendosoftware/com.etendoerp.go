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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import org.hibernate.Session;
import org.hibernate.query.NativeQuery;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.openbravo.dal.service.OBDal;

import com.etendoerp.go.schemaforge.DocumentPdfMonthlyReport.Row;
import com.etendoerp.go.usageevents.UsageEvent;
import com.etendoerp.go.usageevents.UsageEventTypes;

/**
 * The month read of generated document PDFs: the month's day boundaries, the bound query
 * parameters, the support-user exclusion and the row mapping.
 *
 * @covers com.etendoerp.go.schemaforge.DocumentPdfMonthlyReader
 */
class DocumentPdfMonthlyReaderTest {

  private static final YearMonth NOVEMBER = YearMonth.of(2026, 11);

  private static Date midnight(int year, int month, int day) {
    return Date.from(LocalDate.of(year, month, day).atStartOfDay(ZoneId.systemDefault())
        .toInstant());
  }

  @Test
  void aMonthStartsAtLocalMidnightOfItsFirstDay() {
    assertAll(
        () -> assertEquals(midnight(2026, 11, 1), DocumentPdfMonthlyReader.startOf(NOVEMBER)),
        () -> assertEquals(midnight(2027, 1, 1),
            DocumentPdfMonthlyReader.startOf(YearMonth.of(2026, 12).plusMonths(1))));
  }

  @Test
  void theQueryLeavesOutSupportRowsAndCountsOnlyOkGenerations() {
    assertAll(
        () -> assertTrue(DocumentPdfMonthlyReader.MONTH_SQL
            .contains("<> 'support'")),
        () -> assertTrue(DocumentPdfMonthlyReader.MONTH_SQL.contains("e.outcome = :outcome")),
        () -> assertTrue(DocumentPdfMonthlyReader.MONTH_SQL
            .contains("e.occurred_at >= :monthFrom and e.occurred_at < :monthTo")));
  }

  @Test
  @SuppressWarnings("unchecked")
  void theMonthIsBoundHalfOpenAndRowsAreMapped() {
    OBDal dal = mock(OBDal.class);
    Session session = mock(Session.class);
    NativeQuery<Object[]> query = mock(NativeQuery.class);
    when(dal.getSession()).thenReturn(session);
    when(session.createNativeQuery(anyString())).thenReturn((NativeQuery) query);
    List<Object[]> result = Collections.singletonList(
        new Object[] { "C1", "O1", "sales-invoice", "download", "modern", 7L });
    when(query.list()).thenReturn(result);

    try (MockedStatic<OBDal> obdal = mockStatic(OBDal.class)) {
      obdal.when(OBDal::getInstance).thenReturn(dal);

      List<Row> rows = new DocumentPdfMonthlyReader().read(NOVEMBER);

      verify(session).createNativeQuery(DocumentPdfMonthlyReader.MONTH_SQL);
      verify(query).setParameter("eventType", UsageEventTypes.DOCUMENT_PDF_GENERATE);
      verify(query).setParameter("monthFrom", midnight(2026, 11, 1));
      verify(query).setParameter("monthTo", midnight(2026, 12, 1));
      verify(query).setParameter("outcome", UsageEvent.OUTCOME_OK);
      assertEquals(1, rows.size());
      DocumentPdfMonthlyReport report = DocumentPdfMonthlyReport.of(NOVEMBER, rows);
      assertAll(
          () -> assertEquals(7, report.getTotal()),
          () -> assertEquals(7, report.getGenerated("sales-invoice", "modern")),
          () -> assertEquals(7, report.getByChannel("download")));
    }
  }
}
