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
 * All portions are Copyright (C) 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.usage;

import java.text.ParseException;
import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openbravo.base.session.OBPropertiesProvider;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.erpCommon.utility.OBError;
import org.openbravo.scheduling.ProcessBundle;
import org.openbravo.service.db.DalBaseProcess;

/**
 * Recomputes usage. With no parameters it covers the settling window, which is what the
 * nightly schedule runs; given a date range it backfills that range instead, which is what
 * makes shadow mode possible — a full past month can be reviewed before anyone is billed.
 *
 * <p>Re-running over the same range produces identical rows and no duplicates, so it is safe
 * to run repeatedly. It writes only to {@code ETGO_USAGE_DAILY} and calls no external
 * service, which is why it is safe to run in a real environment on day one.
 *
 * <p>Parameters (both optional): {@code DateFrom}, {@code DateTo}. Supplying one without the
 * other is rejected rather than guessed at. Either the instance's own display format (the
 * {@code dateFormat.java} property, which is what the parameter window sends) or
 * {@code yyyy-MM-dd} (what a scheduled request's JSON parameters naturally carry) is
 * accepted.
 */
public class UsageAggregationProcess extends DalBaseProcess {

  private static final Logger log = LogManager.getLogger(UsageAggregationProcess.class);

  static final String PARAM_DATE_FROM = "DateFrom";
  static final String PARAM_DATE_TO = "DateTo";
  private static final String DATE_FORMAT = "yyyy-MM-dd";

  @Override
  public void doExecute(ProcessBundle bundle) throws Exception {
    OBContext.setAdminMode(false);
    try {
      Date from = readDate(bundle, PARAM_DATE_FROM);
      Date to = readDate(bundle, PARAM_DATE_TO);
      if ((from == null) != (to == null)) {
        throw new IllegalArgumentException(
            "Supply both " + PARAM_DATE_FROM + " and " + PARAM_DATE_TO + ", or neither to"
                + " process the settling window");
      }
      if (from != null && from.after(to)) {
        throw new IllegalArgumentException(
            PARAM_DATE_FROM + " must not be after " + PARAM_DATE_TO);
      }

      UsageAggregationService service = new UsageAggregationService();
      UsageAggregationResult result =
          from == null ? service.runForSettlingWindow() : service.run(from, to);

      OBDal.getInstance().flush();

      OBError success = new OBError();
      success.setType(result.getResourcesFailed() > 0 ? "Warning" : "Success");
      success.setTitle("Usage Aggregation Complete");
      success.setMessage(result.toString());
      bundle.setResult(success);

    } catch (Exception e) {
      log.error("Error in UsageAggregationProcess", e);
      OBDal.getInstance().rollbackAndClose();
      OBError error = new OBError();
      error.setType("Error");
      error.setTitle("Usage Aggregation Failed");
      // Each resource-day commits on its own, so days completed before the failure are already
      // written. Saying so avoids reading this as "nothing happened"; every day is idempotent,
      // so re-running the same range is the fix.
      error.setMessage(e.getMessage()
          + " (days completed before the failure are already written; re-running the same"
          + " range is safe and will finish the rest)");
      bundle.setResult(error);
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /**
   * Reads an optional date parameter.
   *
   * <p>The lookup is case-insensitive on purpose. The AD parameter is defined with column name
   * {@code datefrom} and display name {@code DateFrom}, and which of the two reaches
   * {@code getParams()} depends on how the process was invoked — the parameter window, a
   * scheduled {@code ProcessRequest}, or a direct call. Matching exactly would silently read
   * null and quietly turn a requested backfill into an ordinary settling-window run.
   */
  private Date readDate(ProcessBundle bundle, String name) throws ParseException {
    Object raw = null;
    for (Map.Entry<String, Object> entry : bundle.getParams().entrySet()) {
      if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(name)) {
        raw = entry.getValue();
        break;
      }
    }
    if (raw == null) {
      return null;
    }
    if (raw instanceof Date) {
      return UsageDayRange.startOfDay((Date) raw);
    }
    String text = String.valueOf(raw).trim();
    if (StringUtils.isBlank(text)) {
      return null;
    }
    return UsageDayRange.startOfDay(parseDate(text, name));
  }

  /**
   * Parses a date the user or a scheduled request supplied.
   *
   * <p>Tries the instance's configured display format first, because that is what the
   * parameter window sends -- a Spanish instance sends {@code 08-09-2010}, and parsing that
   * as {@code yyyy-MM-dd} fails outright. Then falls back to ISO, which is what a scheduled
   * request's JSON parameters naturally carry and what this process's own documentation
   * promises.
   *
   * <p>Both attempts are <b>strict</b>. {@code OBDateUtils.getDate} is not, and a lenient
   * {@code dd-MM-yyyy} parse of {@code 2011-01-01} does not fail -- it silently rolls day
   * 2011 forward into a date years away, so a backfill would quietly cover the wrong range.
   * Strict parsing also removes any ambiguity between the two formats: a string that one
   * accepts, the other rejects.
   */
  private Date parseDate(String text, String name) throws ParseException {
    List<String> accepted = new ArrayList<>();
    String configured = configuredDateFormat();
    if (StringUtils.isNotBlank(configured)) {
      accepted.add(configured);
    }
    accepted.add(DATE_FORMAT);

    for (String pattern : accepted) {
      Date parsed = parseFully(text, pattern);
      if (parsed != null) {
        return parsed;
      }
    }
    throw new ParseException(name + " '" + text + "' is not a date. Accepted formats: "
        + String.join(", ", accepted) + ".", 0);
  }

  /**
   * Parses the WHOLE string, or answers null.
   *
   * <p>The whole string matters: {@code SimpleDateFormat.parse(String)} stops at the end of
   * the pattern and ignores whatever follows, so {@code 2011-01-01xyz} would parse happily as
   * 1 January 2011. Strictness governs field ranges, not trailing junk. For a backfill that
   * means a typed range could be accepted while meaning something the user did not write, and
   * a usage run that silently covers the wrong days is worse than one that refuses to start.
   */
  private Date parseFully(String text, String pattern) {
    SimpleDateFormat format = new SimpleDateFormat(pattern);
    format.setLenient(false);
    ParsePosition position = new ParsePosition(0);
    Date parsed = format.parse(text, position);
    return parsed != null && position.getIndex() == text.length() ? parsed : null;
  }

  /**
   * The instance's display date format, or null when the properties are not available -- in a
   * unit test, say, where ISO alone is enough.
   */
  private String configuredDateFormat() {
    try {
      return OBPropertiesProvider.getInstance()
          .getOpenbravoProperties()
          .getProperty("dateFormat.java");
    } catch (RuntimeException e) {
      log.debug("No configured date format available, falling back to {}", DATE_FORMAT);
      return null;
    }
  }
}
