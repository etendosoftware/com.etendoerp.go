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
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import org.hibernate.query.NativeQuery;
import org.openbravo.dal.service.OBDal;

import com.etendoerp.go.usageevents.UsageEvent;
import com.etendoerp.go.usageevents.UsageEventTypes;

/**
 * ETP-5993: reads one month of {@code document.pdf.generate} rows from {@code ETGO_USAGE_EVENT},
 * grouped by tenant, organization, document type, channel and template, for
 * {@link DocumentPdfMonthlyReport}. Every tenant: it runs as System, in the monthly process.
 *
 * <p>The month is the server's calendar month, the day boundaries the usage aggregation uses
 * ({@code UsageDayRange}). Rows of the Etendo support user ({@code "actor":"support"}) are not the
 * tenant's use and are left out, as every usage aggregation does.</p>
 */
class DocumentPdfMonthlyReader {

  private static final String PARAM_TYPE = "eventType";
  private static final String PARAM_FROM = "monthFrom";
  private static final String PARAM_TO = "monthTo";
  private static final String PARAM_OUTCOME = "outcome";

  /** Also the documented query of the baseline (docs/document-pdf-telemetry.md). */
  static final String MONTH_SQL = "select e.ad_client_id, e.ad_org_id, e.target, e.action,"
      + " coalesce(cast(e.properties as jsonb) ->> 'variant', ''), count(*)"
      + " from etgo_usage_event e"
      + " where e.event_type = :" + PARAM_TYPE
      + " and e.occurred_at >= :" + PARAM_FROM + " and e.occurred_at < :" + PARAM_TO
      + " and e.outcome = :" + PARAM_OUTCOME
      + " and coalesce(cast(e.properties as jsonb) ->> 'actor', '') <> 'support'"
      + " group by e.ad_client_id, e.ad_org_id, e.target, e.action,"
      + " coalesce(cast(e.properties as jsonb) ->> 'variant', '')";

  /**
   * @param month the calendar month
   * @return the month's grouped rows; empty when nothing was generated
   */
  List<DocumentPdfMonthlyReport.Row> read(YearMonth month) {
    @SuppressWarnings("unchecked")
    NativeQuery<Object[]> query = OBDal.getInstance().getSession().createNativeQuery(MONTH_SQL);
    query.setParameter(PARAM_TYPE, UsageEventTypes.DOCUMENT_PDF_GENERATE);
    query.setParameter(PARAM_FROM, startOf(month));
    query.setParameter(PARAM_TO, startOf(month.plusMonths(1)));
    query.setParameter(PARAM_OUTCOME, UsageEvent.OUTCOME_OK);
    List<DocumentPdfMonthlyReport.Row> rows = new ArrayList<>();
    for (Object[] row : query.list()) {
      rows.add(new DocumentPdfMonthlyReport.Row((String) row[0], (String) row[1],
          (String) row[2], (String) row[3], (String) row[4], ((Number) row[5]).longValue()));
    }
    return rows;
  }

  /** Local midnight of the month's first day. */
  static Date startOf(YearMonth month) {
    return Date.from(month.atDay(1).atStartOfDay(ZoneId.systemDefault()).toInstant());
  }
}
