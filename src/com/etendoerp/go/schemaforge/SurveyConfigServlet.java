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

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

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
 * Serves the schema_forge app-shell survey engine's tuning parameters
 * (cooldowns, monthly cap, per-survey eligibility thresholds) and predefined
 * CSAT canned responses, all maintained by the Etendo GO team via the "Survey
 * Configuration" backoffice window: a single global-settings row
 * (ETGO_Survey_Config), one header row per survey (ETGO_Survey_Type — adding a
 * new survey is a new row, not a new column), and canned responses as a child
 * of that header (ETGO_Survey_Canned_Resp), each with a score range. Read-only,
 * not client-scoped.
 *
 * URL:
 *   GET  /sws/survey-config/
 *   POST /sws/survey-config/response
 *
 * GET Response:
 *   {
 *     "globalCooldownDays": 30, "dismissedCooldownDays": 21, "maxPerMonth": 2,
 *     "perSurvey": {
 *       "nps": {"minAccountAgeDays":60,"inactivityGuardDays":14,"responseCooldownDays":90,"enabled":true},
 *       "csat_invoicing": {"minDocuments":5,"documentGap":30,"responseCooldownDays":90,"enabled":true},
 *       "csat_order": {"minDocuments":5,"documentGap":30,"responseCooldownDays":90,"enabled":false}
 *     },
 *     "canned": {
 *       "csat_invoicing": { "en_US": [{"icon":"...","text":"...","minScore":1,"maxScore":3}], "es_ES": [...] },
 *       "csat_order": { "en_US": [...], "es_ES": [...] }
 *     }
 *   }
 *
 * "enabled" mirrors ETGO_Survey_Type.isactive and is a hard kill switch, NOT a tuning fallback:
 * a row with isactive='N' is still returned (so the frontend can see it was explicitly disabled)
 * but with enabled=false, which the survey engine treats as "never show this survey" regardless
 * of what its local isEligible() would otherwise decide — see isSurveyTypeEnabled() in
 * survey-config.js. A survey key that has no row at all is NOT considered disabled (enabled
 * defaults to true client-side), since "not configured yet" is different from "explicitly
 * turned off".
 *
 * Falls back to schema_forge's own VITE_SURVEY_* / hardcoded defaults when this
 * endpoint is unreachable or returns no rows — see survey-config.js.
 *
 * POST /sws/survey-config/response (ETP-4352 GDPR remediation): persists the actual NPS/CSAT
 * free-text feedback server-side, in {@code ETGO_Survey_Response}, so product can still read it
 * without it ever reaching Mixpanel. Mirrors the same request/data shape the frontend used to
 * send to Mixpanel (score + feedback + tags), but keeps it here instead — the Mixpanel event now
 * only carries a {@code hasComment} boolean (see SURVEY_RESPONDED in events.js), the same pattern
 * {@code SupportConversationsServlet#handleSubmitRating} already applies for support-chat CSAT.
 *
 * Request body:
 *   { "surveyKey": "nps", "score": 9, "feedback": "free text, optional", "tags": ["fast","easy"] }
 *
 * Response: { "status": "ok" }
 */
public class SurveyConfigServlet extends HttpBaseServlet {

  private static final Logger log = LogManager.getLogger(SurveyConfigServlet.class);

  private static final String GLOBAL_QUERY =
      "SELECT global_cooldown_days, dismissed_cooldown_days, max_per_month"
      + " FROM etgo_survey_config WHERE isactive='Y' ORDER BY created LIMIT 1";

  // Not filtered by isactive: a disabled (isactive='N') survey type row must still be reported
  // to the frontend (as enabled=false in groupSurveyTypes) so it can be treated as a hard
  // disable — filtering it out here would make it silently fall back to default tuning instead,
  // which is the bug this endpoint exists to avoid.
  private static final String SURVEY_TYPES_QUERY =
      "SELECT survey_key, min_account_age_days, inactivity_guard_days,"
      + " min_documents, document_gap, response_cooldown_days, isactive"
      + " FROM etgo_survey_type";

  private static final String CANNED_QUERY =
      "SELECT st.survey_key, cr.language, cr.icon, cr.response_text, cr.min_score, cr.max_score"
      + " FROM etgo_survey_canned_resp cr"
      + " JOIN etgo_survey_type st ON st.etgo_survey_type_id = cr.etgo_survey_type_id"
      + " WHERE cr.isactive='Y'"
      + " ORDER BY st.survey_key, cr.language, cr.line_no";

  private static final String FIELD_SURVEY_KEY = "surveyKey";
  private static final String FIELD_SCORE = "score";
  private static final String FIELD_FEEDBACK = "feedback";
  private static final String FIELD_TAGS = "tags";
  private static final String FIELD_STATUS = "status";
  private static final String RESPONSE_PATH = "/response";

  @Override
  public void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
    CorsUtils.apply(request, response, "GET, POST, OPTIONS", "Authorization, Content-Type", null, false);

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
  public void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
    CorsUtils.apply(request, response, "GET, POST, OPTIONS", "Authorization, Content-Type", null, false);

    String pathInfo = request.getPathInfo();
    if (pathInfo == null || !(RESPONSE_PATH.equals(pathInfo) || (RESPONSE_PATH + "/").equals(pathInfo))) {
      sendError(response, HttpServletResponse.SC_NOT_FOUND, "Unknown endpoint: " + pathInfo);
      return;
    }

    OBContext ctx;
    try {
      ctx = NeoServletSupport.authenticateJwt(request);
    } catch (OBException e) {
      log.warn("Unauthorized SurveyConfig response submission: {}", e.getMessage());
      sendError(response, HttpServletResponse.SC_UNAUTHORIZED, e.getMessage());
      return;
    } catch (Exception e) {
      log.warn("Unauthorized SurveyConfig response submission: {}", e.getMessage());
      sendError(response, HttpServletResponse.SC_UNAUTHORIZED, "Invalid or expired token");
      return;
    }

    handleSubmitResponse(request, response, ctx);
  }

  @Override
  public void doOptions(HttpServletRequest request, HttpServletResponse response) throws IOException {
    CorsUtils.apply(request, response, "GET, POST, OPTIONS", "Authorization, Content-Type", null, false);
    response.setStatus(HttpServletResponse.SC_NO_CONTENT);
  }

  /**
   * Persists a submitted NPS/CSAT survey response — the free-text feedback is stored here and
   * ONLY here (never forwarded to Mixpanel, see useSurveyEngine.js's handleRespond, which now
   * sends a hasComment boolean to the analytics channel instead). Fire-and-forget from the
   * frontend's point of view, same as SupportConversationsServlet#handleSubmitRating persists the
   * support-chat CSAT rating/comment.
   */
  private void handleSubmitResponse(HttpServletRequest request, HttpServletResponse response, OBContext ctx)
      throws IOException {
    JSONObject body = parseBody(request, response);
    if (body == null) return;

    String surveyKey = body.optString(FIELD_SURVEY_KEY, "").trim();
    if (surveyKey.isEmpty()) {
      sendError(response, HttpServletResponse.SC_BAD_REQUEST, "Missing required field: " + FIELD_SURVEY_KEY);
      return;
    }

    Integer score = body.has(FIELD_SCORE) && !body.isNull(FIELD_SCORE) ? body.optInt(FIELD_SCORE) : null;
    String feedbackTrimmed = body.optString(FIELD_FEEDBACK, "").trim();
    String feedback = feedbackTrimmed.isEmpty() ? null : feedbackTrimmed;
    String tags = joinTags(body.optJSONArray(FIELD_TAGS));

    try {
      OBContext.setAdminMode();
      try {
        // score/feedback/tags are individually nullable (a CSAT response may have no comment,
        // a dismissed-then-reopened NPS may have no score yet). Hibernate's generic
        // setParameter(name, Object) cannot infer a JDBC type from a null value on a native
        // query, so absent fields are inlined as the SQL literal NULL instead of bound — the
        // inlined tokens are always one of the two fixed strings below, never request input.
        NativeQuery<?> insert = OBDal.getInstance().getSession()
            .createNativeQuery(buildInsertResponseSql(score, feedback, tags));
        insert.setParameter("id", UUID.randomUUID().toString().replace("-", ""));
        insert.setParameter("clientId", ctx.getCurrentClient().getId());
        insert.setParameter("orgId", ctx.getCurrentOrganization().getId());
        insert.setParameter("actorId", ctx.getUser().getId());
        insert.setParameter(FIELD_SURVEY_KEY, surveyKey);
        if (score != null) insert.setParameter(FIELD_SCORE, score);
        if (feedback != null) insert.setParameter(FIELD_FEEDBACK, feedback);
        if (tags != null) insert.setParameter(FIELD_TAGS, tags);
        insert.executeUpdate();
      } finally {
        OBContext.restorePreviousMode();
      }

      JSONObject result = new JSONObject();
      result.put(FIELD_STATUS, "ok");
      response.setStatus(HttpServletResponse.SC_CREATED);
      response.setContentType(ContentType.APPLICATION_JSON.getMimeType());
      response.setCharacterEncoding(StandardCharsets.UTF_8.name());
      response.getWriter().write(result.toString());
    } catch (Exception e) {
      log.error("Error persisting survey response for survey '{}': {}", surveyKey, e.getMessage(), e);
      sendError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
          "An internal error occurred while saving the survey response.");
    }
  }

  /** Builds the INSERT for one survey response, inlining a fixed {@code NULL} SQL literal (never
   * request-controlled) for each optional column that has no value, instead of binding null via
   * {@link NativeQuery#setParameter}. */
  private static String buildInsertResponseSql(Integer score, String feedback, String tags) {
    String scoreExpr = score != null ? ":" + FIELD_SCORE : "NULL";
    String feedbackExpr = feedback != null ? ":" + FIELD_FEEDBACK : "NULL";
    String tagsExpr = tags != null ? ":" + FIELD_TAGS : "NULL";
    return "INSERT INTO etgo_survey_response"
        + " (etgo_survey_response_id, ad_client_id, ad_org_id, isactive,"
        + "  created, createdby, updated, updatedby,"
        + "  survey_key, ad_user_id, score, feedback_text, tags, response_date)"
        + " VALUES"
        + " (:id, :clientId, :orgId, 'Y',"
        + "  now(), :actorId, now(), :actorId,"
        + "  :" + FIELD_SURVEY_KEY + ", :actorId, " + scoreExpr + ", " + feedbackExpr + ", " + tagsExpr + ", now())";
  }

  /** Flattens a JSON string array into a comma-separated value for the {@code tags} column (same
   * shape the frontend previously sent to Mixpanel as {@code tags.join(',')}). Returns null (not
   * an empty string) when absent/empty so the column stores a real NULL. */
  private static String joinTags(JSONArray tagsArr) {
    if (tagsArr == null || tagsArr.length() == 0) return null;
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < tagsArr.length(); i++) {
      if (sb.length() > 0) sb.append(',');
      sb.append(tagsArr.optString(i, ""));
    }
    return sb.length() > 0 ? sb.toString() : null;
  }

  /** Parse request body, writing a 400 response on malformed JSON (returns null in that case so
   * the caller can bail out immediately — mirrors SupportConversationsServlet#parseBody). */
  private JSONObject parseBody(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    StringBuilder sb = new StringBuilder();
    try (BufferedReader reader = request.getReader()) {
      String line;
      while ((line = reader.readLine()) != null) sb.append(line);
    }
    try {
      return new JSONObject(sb.toString());
    } catch (JSONException e) {
      sendError(response, HttpServletResponse.SC_BAD_REQUEST, "Invalid JSON body");
      return null;
    }
  }

  @SuppressWarnings("unchecked")
  private JSONObject buildConfigResponse() throws JSONException {
    NativeQuery<Object[]> globalQuery =
        OBDal.getInstance().getSession().createNativeQuery(GLOBAL_QUERY);
    List<Object[]> globalRows = globalQuery.list();

    JSONObject result = new JSONObject();
    if (!globalRows.isEmpty()) {
      Object[] row = globalRows.get(0);
      result.put("globalCooldownDays", toInt(row[0]));
      result.put("dismissedCooldownDays", toInt(row[1]));
      result.put("maxPerMonth", toInt(row[2]));
    }

    NativeQuery<Object[]> surveyTypesQuery =
        OBDal.getInstance().getSession().createNativeQuery(SURVEY_TYPES_QUERY);
    List<Object[]> surveyTypeRows = surveyTypesQuery.list();
    result.put("perSurvey", groupSurveyTypes(surveyTypeRows));

    NativeQuery<Object[]> cannedQuery =
        OBDal.getInstance().getSession().createNativeQuery(CANNED_QUERY);
    List<Object[]> cannedRows = cannedQuery.list();
    result.put("canned", groupCannedResponses(cannedRows));

    return result;
  }

  /**
   * Builds { surveyKey: {minAccountAgeDays,inactivityGuardDays,minDocuments,documentGap,
   * responseCooldownDays,enabled} }, omitting null tuning fields. "enabled" always comes back
   * (never omitted) — it's the isactive-derived kill switch, true unless the row is explicitly
   * isactive='N'.
   */
  private JSONObject groupSurveyTypes(List<Object[]> rows) throws JSONException {
    JSONObject bySurvey = new JSONObject();
    for (Object[] row : rows) {
      String surveyKey = toText(row[0]);
      JSONObject config = new JSONObject();
      putIfPresent(config, "minAccountAgeDays", row[1]);
      putIfPresent(config, "inactivityGuardDays", row[2]);
      putIfPresent(config, "minDocuments", row[3]);
      putIfPresent(config, "documentGap", row[4]);
      putIfPresent(config, "responseCooldownDays", row[5]);
      config.put("enabled", !"N".equals(toText(row[6])));
      bySurvey.put(surveyKey, config);
    }
    return bySurvey;
  }

  /** Groups flat canned-response rows into { surveyKey: { language: [{icon,text,minScore,maxScore}] } }. */
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
      phrase.put("minScore", toInt(row[4]));
      phrase.put("maxScore", toInt(row[5]));
      phrases.put(phrase);

      byLanguage.put(language, phrases);
      bySurvey.put(surveyKey, byLanguage);
    }
    return bySurvey;
  }

  private void putIfPresent(JSONObject target, String key, Object value) throws JSONException {
    if (value != null) {
      target.put(key, toInt(value));
    }
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
