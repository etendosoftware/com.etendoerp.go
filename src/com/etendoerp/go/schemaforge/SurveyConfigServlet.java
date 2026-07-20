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

package com.etendoerp.go.schemaforge;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.http.entity.ContentType;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.query.NativeQuery;
import org.openbravo.base.HttpBaseServlet;
import org.openbravo.base.exception.OBException;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;

import com.etendoerp.go.common.CorsUtils;

/**
 * Survey Config Servlet.
 *
 * Mapped to /sws/survey-config/* via AD_MODEL_OBJECT registration.
 * Serves the schema_forge app-shell survey engine's global tuning parameters
 * (cooldowns, monthly cap, document thresholds) and predefined CSAT canned
 * responses, both maintained by the Etendo GO team via the "Survey
 * Configuration" backoffice window (ETGO_Survey_Config /
 * ETGO_Survey_Canned_Resp). Read-only, single global row — not client-scoped.
 *
 * URL:
 *   GET /sws/survey-config/
 *
 * Response:
 *   {
 *     "globalCooldownDays": 30, "dismissedCooldownDays": 21, "maxPerMonth": 2,
 *     "npsMinAgeDays": 60, "npsInactivityDays": 14, "responseCooldownDays": 90,
 *     "csatMinDocs": 5, "csatDocGap": 30,
 *     "canned": {
 *       "csat_invoicing": { "en_US": [{"icon":"...","text":"..."}], "es_ES": [...] },
 *       "csat_order": { "en_US": [...], "es_ES": [...] }
 *     }
 *   }
 *
 * Falls back to schema_forge's own VITE_SURVEY_* / hardcoded defaults when this
 * endpoint is unreachable or returns no config row — see survey-config.js.
 */
public class SurveyConfigServlet extends HttpBaseServlet {

  private static final Logger log = LogManager.getLogger(SurveyConfigServlet.class);

  private static final String CONFIG_QUERY =
      "SELECT global_cooldown_days, dismissed_cooldown_days, max_per_month,"
      + " nps_min_age_days, nps_inactivity_days, response_cooldown_days,"
      + " csat_min_docs, csat_doc_gap"
      + " FROM etgo_survey_config WHERE isactive='Y' ORDER BY created LIMIT 1";

  private static final String CANNED_QUERY =
      "SELECT survey_key, language, icon, response_text"
      + " FROM etgo_survey_canned_resp WHERE isactive='Y'"
      + " ORDER BY survey_key, language, line_no";

  @Override
  public void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
    CorsUtils.apply(request, response, "GET, OPTIONS", "Authorization, Content-Type", null, false);

    try {
      NeoServletSupport.authenticateJwt(request);
    } catch (OBException e) {
      log.warn("Unauthorized SurveyConfig request: {}", e.getMessage());
      sendError(response, HttpServletResponse.SC_UNAUTHORIZED, e.getMessage());
      return;
    } catch (Exception e) {
      log.warn("Unauthorized SurveyConfig request: {}", e.getMessage());
      sendError(response, HttpServletResponse.SC_UNAUTHORIZED, "Invalid or expired token");
      return;
    }

    try {
      OBContext.setAdminMode();
      JSONObject result = buildConfigResponse();
      response.setStatus(HttpServletResponse.SC_OK);
      response.setContentType(ContentType.APPLICATION_JSON.getMimeType());
      response.setCharacterEncoding(StandardCharsets.UTF_8.name());
      response.getWriter().write(result.toString());
    } catch (Exception e) {
      log.error("Error building survey config response: {}", e.getMessage(), e);
      sendError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
          "An internal error occurred while loading the survey configuration.");
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  @Override
  public void doOptions(HttpServletRequest request, HttpServletResponse response) throws IOException {
    CorsUtils.apply(request, response, "GET, OPTIONS", "Authorization, Content-Type", null, false);
    response.setStatus(HttpServletResponse.SC_NO_CONTENT);
  }

  @SuppressWarnings("unchecked")
  private JSONObject buildConfigResponse() throws JSONException {
    NativeQuery<Object[]> configQuery =
        OBDal.getInstance().getSession().createNativeQuery(CONFIG_QUERY);
    List<Object[]> configRows = configQuery.list();

    JSONObject result = new JSONObject();
    if (!configRows.isEmpty()) {
      Object[] row = configRows.get(0);
      result.put("globalCooldownDays", toInt(row[0]));
      result.put("dismissedCooldownDays", toInt(row[1]));
      result.put("maxPerMonth", toInt(row[2]));
      result.put("npsMinAgeDays", toInt(row[3]));
      result.put("npsInactivityDays", toInt(row[4]));
      result.put("responseCooldownDays", toInt(row[5]));
      result.put("csatMinDocs", toInt(row[6]));
      result.put("csatDocGap", toInt(row[7]));
    }

    NativeQuery<Object[]> cannedQuery =
        OBDal.getInstance().getSession().createNativeQuery(CANNED_QUERY);
    List<Object[]> cannedRows = cannedQuery.list();
    result.put("canned", groupCannedResponses(cannedRows));

    return result;
  }

  /** Groups flat canned-response rows into { surveyKey: { language: [{icon,text}] } }. */
  private JSONObject groupCannedResponses(List<Object[]> rows) throws JSONException {
    JSONObject bySurvey = new JSONObject();
    for (Object[] row : rows) {
      String surveyKey = toText(row[0]);
      String language = toText(row[1]);
      String icon = toText(row[2]);
      String text = toText(row[3]);

      JSONObject byLanguage = bySurvey.has(surveyKey)
          ? bySurvey.getJSONObject(surveyKey)
          : new JSONObject();
      JSONArray phrases = byLanguage.has(language)
          ? byLanguage.getJSONArray(language)
          : new JSONArray();

      JSONObject phrase = new JSONObject();
      phrase.put("icon", icon);
      phrase.put("text", text);
      phrases.put(phrase);

      byLanguage.put(language, phrases);
      bySurvey.put(surveyKey, byLanguage);
    }
    return bySurvey;
  }

  private int toInt(Object value) {
    return value != null ? ((Number) value).intValue() : 0;
  }

  private String toText(Object value) {
    return value != null ? value.toString() : "";
  }

  private void sendError(HttpServletResponse response, int status, String message)
      throws IOException {
    try {
      JSONObject body = new JSONObject();
      body.put("error", message);
      response.setStatus(status);
      response.setContentType(ContentType.APPLICATION_JSON.getMimeType());
      response.setCharacterEncoding(StandardCharsets.UTF_8.name());
      response.getWriter().write(body.toString());
    } catch (JSONException ex) {
      log.error("Failed to write error response", ex);
    }
  }
}
