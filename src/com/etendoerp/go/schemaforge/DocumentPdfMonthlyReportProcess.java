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

import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.Date;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openbravo.base.exception.OBException;
import org.openbravo.base.session.OBPropertiesProvider;
import org.openbravo.dal.core.OBContext;
import org.openbravo.erpCommon.utility.OBError;
import org.openbravo.scheduling.ProcessBundle;
import org.openbravo.service.db.DalBaseProcess;

/**
 * ETP-5993 (OKR O4.KR2): the monthly aggregation of generated document PDFs — documents generated
 * per template and organizations using them — over every tenant. Read-only: it writes nothing, so it
 * is safe to run at any time and as often as wanted.
 *
 * <p>One optional parameter, {@code Month} (keyed {@code datefrom}): any day of the month to report.
 * Without it the process reports the previous calendar month, which is what a monthly Process
 * Request scheduled for the first days of each month needs. The result is the report as Markdown
 * ({@link DocumentPdfMonthlyReport#toMarkdown}), shown in the process message and written to the log,
 * ready to be pasted into the published baseline.</p>
 */
public class DocumentPdfMonthlyReportProcess extends DalBaseProcess {

  private static final Logger log = LogManager.getLogger(DocumentPdfMonthlyReportProcess.class);

  /** The AD column name of the parameter; the lookup ignores case. */
  static final String PARAM_MONTH = "DateFrom";
  private static final String ISO_DATE = "yyyy-MM-dd";

  private final DocumentPdfMonthlyReader reader;

  /** The production process. */
  public DocumentPdfMonthlyReportProcess() {
    this(new DocumentPdfMonthlyReader());
  }

  DocumentPdfMonthlyReportProcess(DocumentPdfMonthlyReader reader) {
    this.reader = reader;
  }

  /**
   * Reads the month's generated document PDFs and answers the report as the process result.
   *
   * @param bundle the process bundle; its {@code DateFrom} parameter picks the month
   * @throws Exception when the month parameter is invalid or the read fails
   */
  @Override
  public void doExecute(ProcessBundle bundle) throws Exception {
    YearMonth month;
    try {
      month = monthOf(param(bundle.getParams()), configuredDateFormat(),
          YearMonth.now(ZoneId.systemDefault()));
    } catch (IllegalArgumentException e) {
      bundle.setResult(message("Error", "Document PDF Report Not Run", e.getMessage()));
      throw new OBException(e.getMessage());
    }
    OBContext.setAdminMode(true);
    try {
      DocumentPdfMonthlyReport report = DocumentPdfMonthlyReport.of(month, reader.read(month));
      String markdown = report.toMarkdown();
      log.info("Document PDF monthly report {}: {} generated, {} organizations\n{}", month,
          report.getTotal(), report.getOrganizations(), markdown);
      bundle.setResult(message("Success", "Document PDF Report " + month, toHtml(markdown)));
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /**
   * The month to report.
   *
   * @param raw              the parameter: a date, a {@code yyyy-MM-dd} or {@code yyyy-MM} text, a
   *                         text in the instance's display format, or null/blank
   * @param configuredFormat the instance's {@code dateFormat.java}, or null
   * @param current          the current month
   * @return the parameter's month, or the month before {@code current} when there is none
   * @throws IllegalArgumentException when the parameter is not a date
   */
  static YearMonth monthOf(Object raw, String configuredFormat, YearMonth current) {
    if (raw instanceof Date) {
      return YearMonth.from(((Date) raw).toInstant().atZone(ZoneId.systemDefault()));
    }
    String text = raw == null ? null : StringUtils.trimToNull(String.valueOf(raw));
    if (text == null) {
      return current.minusMonths(1);
    }
    YearMonth parsed = parseIso(text);
    if (parsed == null && StringUtils.isNotBlank(configuredFormat)) {
      parsed = parseStrict(text, configuredFormat);
    }
    if (parsed == null) {
      throw new IllegalArgumentException("Month '" + StringUtils.abbreviate(text, 40)
          + "' is not a date. Accepted formats: " + ISO_DATE + ", yyyy-MM"
          + (StringUtils.isBlank(configuredFormat) ? "" : ", " + configuredFormat) + ".");
    }
    return parsed;
  }

  private static YearMonth parseIso(String text) {
    try {
      return text.length() == 7 ? YearMonth.parse(text) : YearMonth.from(LocalDate.parse(text));
    } catch (DateTimeParseException e) {
      return null;
    }
  }

  /** The whole text, strictly: a lenient or partial parse would report the wrong month. */
  private static YearMonth parseStrict(String text, String pattern) {
    try {
      SimpleDateFormat format = new SimpleDateFormat(pattern);
      format.setLenient(false);
      ParsePosition position = new ParsePosition(0);
      Date parsed = format.parse(text, position);
      return parsed != null && position.getIndex() == text.length()
          ? YearMonth.from(parsed.toInstant().atZone(ZoneId.systemDefault())) : null;
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  private static Object param(Map<String, Object> params) {
    if (params == null) {
      return null;
    }
    for (Map.Entry<String, Object> entry : params.entrySet()) {
      if (PARAM_MONTH.equalsIgnoreCase(entry.getKey())) {
        return entry.getValue();
      }
    }
    return null;
  }

  private static String configuredDateFormat() {
    try {
      return OBPropertiesProvider.getInstance().getOpenbravoProperties()
          .getProperty("dateFormat.java");
    } catch (RuntimeException e) {
      return null;
    }
  }

  /** The Markdown shown as is in the process message: escaped, one line per line. */
  static String toHtml(String markdown) {
    return markdown.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\n", "<br/>");
  }

  private static OBError message(String type, String title, String text) {
    OBError result = new OBError();
    result.setType(type);
    result.setTitle(title);
    result.setMessage(text);
    return result;
  }
}
