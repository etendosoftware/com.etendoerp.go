/*
 *************************************************************************
 * The contents of this file are subject to the Etendo License
 * (the "License"), you may not use this file except in compliance
 * with the License.
 * You may obtain a copy of the License at
 * https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * Software distributed under the License is distributed on an
 * "AS IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
 * implied. See the License for the specific language governing rights
 * and limitations under the License.
 * All portions are Copyright (C) 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 *************************************************************************
 */
package com.etendoerp.go.schemaforge;

import java.util.Collections;
import java.util.List;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.base.exception.OBException;

/**
 * An annual declaration (Modelo 390) over a fiscal year whose calendar does not have one period
 * per month 1-12 (ETP-5716). Raised by {@link AbstractFiscalHandler#requireFullYear}; recognised
 * anywhere in a cause chain ({@link #find}) so every endpoint that computes the exercise answers
 * the same structured error, {@value #CODE}, instead of a raw text:
 *
 * <pre>
 * {"error":{"status":422,"code":"INCOMPLETE_FISCAL_YEAR","message":"...",
 *           "year":2025,"missingPeriods":[1,2,...,12]}}
 * </pre>
 *
 * The exception message (with the organization id) is for the server log only; {@link #userText}
 * — the English fallback sent to the client — never carries it.
 */
public class IncompleteFiscalYearException extends OBException {

  private static final long serialVersionUID = 1L;

  /** Error code of the structured answer (and of the Modelo 390 incident). */
  public static final String CODE = "INCOMPLETE_FISCAL_YEAR";

  /** HTTP status of the structured answer: the request is well-formed, the data is not ready. */
  public static final int HTTP_STATUS = 422;

  private final int year;
  private final transient List<Long> missingPeriods;

  IncompleteFiscalYearException(String orgId, int year, List<Long> missingPeriods) {
    super("The fiscal year " + year + " of org=" + orgId
        + " is incomplete for an annual declaration: missing period(s) " + missingPeriods
        + ". Create its 12 monthly periods before computing it.");
    this.year = year;
    this.missingPeriods = Collections.unmodifiableList(missingPeriods);
  }

  public int getYear() {
    return year;
  }

  /** The months (1-12) that have no period, ascending. */
  public List<Long> getMissingPeriods() {
    return missingPeriods;
  }

  /** The English text sent to the client: no organization id. */
  public String userText() {
    return "The fiscal year " + year + " has no period for month(s) " + missingPeriods
        + " in the fiscal calendar. Create its 12 monthly periods before computing it.";
  }

  /** The {@code missingPeriods} as a JSON array. */
  public JSONArray missingPeriodsJson() {
    JSONArray arr = new JSONArray();
    for (Long month : missingPeriods) {
      arr.put(month);
    }
    return arr;
  }

  /** The {@code {"error":{...}}} body of the structured answer. */
  public JSONObject toErrorBody() throws JSONException {
    JSONObject error = new JSONObject();
    error.put("status", HTTP_STATUS);
    error.put("code", CODE);
    error.put("message", userText());
    error.put("year", year);
    error.put("missingPeriods", missingPeriodsJson());
    JSONObject body = new JSONObject();
    body.put("error", error);
    return body;
  }

  /** The {@link IncompleteFiscalYearException} in {@code t}'s cause chain, or {@code null}. */
  public static IncompleteFiscalYearException find(Throwable t) {
    Throwable cur = t;
    for (int depth = 0; cur != null && depth < 10; depth++) {
      if (cur instanceof IncompleteFiscalYearException) {
        return (IncompleteFiscalYearException) cur;
      }
      cur = cur.getCause();
    }
    return null;
  }
}
