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
package com.etendoerp.go.onboarding;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openbravo.base.exception.OBException;
import org.openbravo.dal.service.OBDal;
import org.openbravo.erpCommon.utility.SequenceIdData;

/**
 * Seeds visible defaults for the "Survey Configuration" backoffice window — ETP-5870, gap Q1.
 *
 * <h3>Root cause</h3>
 * Today the window ({@code ETGO_Survey_Config} / {@code ETGO_Survey_Type} /
 * {@code ETGO_Survey_Canned_Resp}) starts completely empty for every tenant. All NPS/CSAT survey
 * behavior (cooldowns, min documents, canned response chips) is governed instead by hardcoded
 * defaults in schema_forge's {@code survey-config.js}/{@code surveys.js}, invisible to the admin
 * unless they read source code. {@code csat_onboarding} is the worst case — it has no row at all,
 * so there is not even a way to discover it is tunable.
 *
 * <h3>Scope: System pseudo-tenant singleton, not per-client</h3>
 * {@code SurveyConfigServlet}'s three read queries ({@code GLOBAL_QUERY}, {@code
 * SURVEY_TYPES_QUERY}, {@code CANNED_QUERY}) carry NO {@code ad_client_id} filter at all — survey
 * tuning is a single install-wide singleton (one physical Etendo GO install = one SaaS tenant),
 * not per-{@code AD_Client} data, unlike the chart-of-accounts/org-tree class of gap. This service
 * therefore seeds rows under {@code AD_Client_ID='0'} / {@code AD_Org_ID='0'} and gates every
 * insert on "does this row exist ANYWHERE in the install" rather than on the {@code clientId}
 * being onboarded — confirmed by reading {@code SurveyConfigServlet} before writing this service,
 * per the mandatory scoping check for this gap.
 *
 * <h3>Idempotency</h3>
 * Every insert is independently {@code NOT EXISTS}-guarded: the config row by "any row at all"
 * (the table has no uniqueness constraint and the Config tab allows creating extra rows — see
 * {@code SurveyConfigServlet}'s own comment on {@code GLOBAL_QUERY}), each survey type row by
 * {@code survey_key}, each canned response row by {@code (etgo_survey_type_id, language,
 * line_no)}. The survey type's REAL id is always re-read after its guarded insert (whether or not
 * it fired) so canned responses never reference a stale id generated on a run where the type row
 * already existed.
 *
 * <h3>No CUT bump</h3>
 * {@code ONBOARDING_PROVISIONED_THROUGH} (in {@link OnboardingBaselineService}) is deliberately
 * NOT bumped for this step — same reasoning as gaps O1/U1 (see
 * {@code docs/etendo-ad/onboarding-gaps.md}). The watermark mechanism is keyed per REAL tenant
 * ({@code remediated_client_id}) and only ever gates the per-tenant corrective sweep; this gap's
 * data lives at the System pseudo-tenant, which that sweep never reaches (the runner's tenant
 * universe query excludes {@code ad_client_id='0'}) and which its corrective twin only ever runs
 * against manually ({@code --fix ... --client 0}, which itself ignores the baseline cutoff).
 * Bumping the CUT here would therefore do nothing for this gap, while risking silently skipping
 * every OTHER per-tenant corrective fix dated between the current cutoff
 * ({@code 2026-09-02T12:00:00Z}) and this one for brand-new tenants — several of which (R41..R50)
 * are deliberately not yet closed on their own preventive front.
 *
 * <h3>Canned responses</h3>
 * Seeded only for the 3 survey keys that have a hardcoded fallback list today ({@code nps},
 * {@code csat_invoicing}, {@code csat_order}) — {@code csat_onboarding} has no fallback and none
 * is seeded for it. Text is the EXACT current {@code en_US}/{@code es_ES} locale strings
 * (schema_forge {@code survey-config.js}/{@code surveys.js}), not the locale keys. CSAT rows are
 * explicitly written with {@code MIN_SCORE=1, MAX_SCORE=5} (the full CSAT range) rather than
 * leaving the column defaults ({@code 1-3}) — the hardcoded fallback list has no score band at
 * all (always shown), and the column defaults would silently restrict those chips to 1-3 star
 * responses once a backend row exists. {@code nps}'s 6 generic chips use the real {@code 0-10}
 * range; only {@code surveyChipAI} keeps its narrower {@code 8-10} band.
 */
public class OnboardingSurveyConfigService {

  private static final Logger log = LogManager.getLogger(OnboardingSurveyConfigService.class);

  // ---- ETGO_SURVEY_CONFIG (global tunables) ---------------------------------------------------

  private static final int GLOBAL_COOLDOWN_DAYS = 30;
  private static final int DISMISSED_COOLDOWN_DAYS = 21;
  private static final int MAX_PER_MONTH = 2;

  private static final String CONFIG_INSERT_SQL = ""
      + "INSERT INTO etgo_survey_config ("
      + "etgo_survey_config_id, ad_client_id, ad_org_id, isactive, created, createdby,"
      + " updated, updatedby, global_cooldown_days, dismissed_cooldown_days, max_per_month)"
      + " SELECT :id, '0', '0', 'Y', now(), '0', now(), '0', " + GLOBAL_COOLDOWN_DAYS + ","
      + " " + DISMISSED_COOLDOWN_DAYS + ", " + MAX_PER_MONTH
      + " WHERE NOT EXISTS (SELECT 1 FROM etgo_survey_config)";

  // ---- ETGO_SURVEY_TYPE ------------------------------------------------------------------------
  // One explicit INSERT per survey key (built from the template below by substituting each
  // nullable tuning field with either its literal value or a literal NULL) rather than one
  // generic parameterized statement -- native-query null binding needs an explicit JDBC type per
  // parameter, and a literal NULL per key sidesteps that entirely while keeping each row's "which
  // fields are not applicable for this survey" shape readable at a glance in the generated SQL.

  private static final String SURVEY_TYPE_INSERT_TEMPLATE = ""
      + "INSERT INTO etgo_survey_type ("
      + "etgo_survey_type_id, ad_client_id, ad_org_id, isactive, created, createdby,"
      + " updated, updatedby, survey_key, min_account_age_days, inactivity_guard_days,"
      + " min_documents, document_gap, response_cooldown_days)"
      + " SELECT :id, '0', '0', 'Y', now(), '0', now(), '0', :surveyKey, %s, %s, %s, %s, %s"
      + " WHERE NOT EXISTS (SELECT 1 FROM etgo_survey_type WHERE survey_key = :surveyKey)";

  private static final String SURVEY_TYPE_ID_LOOKUP_SQL =
      "SELECT etgo_survey_type_id FROM etgo_survey_type WHERE survey_key = :surveyKey"
          + " ORDER BY created ASC LIMIT 1";

  /** One row per {@code SURVEY_KEY} — exact defaults from schema_forge's survey-config.js. */
  private record SurveyTypeSeed(String surveyKey, Integer minAccountAgeDays,
      Integer inactivityGuardDays, Integer minDocuments, Integer documentGap,
      Integer responseCooldownDays) {
  }

  private static final List<SurveyTypeSeed> SURVEY_TYPES = List.of(
      new SurveyTypeSeed("nps", 60, 14, null, null, 90),
      // csat_onboarding's MIN_ACCOUNT_AGE_DAYS reuses the same column with a DIFFERENT meaning
      // ("days since onboarding completed", not "days since account creation") and its own
      // special-cased default of 1, NOT 60 -- see getSurveyTypeConfig() in survey-config.js.
      new SurveyTypeSeed("csat_onboarding", 1, null, null, null, null),
      new SurveyTypeSeed("csat_invoicing", null, null, 5, 30, 90),
      new SurveyTypeSeed("csat_order", null, null, 5, 30, 90));

  // ---- ETGO_SURVEY_CANNED_RESP ------------------------------------------------------------------

  private static final String CANNED_INSERT_NO_ICON_SQL = ""
      + "INSERT INTO etgo_survey_canned_resp ("
      + "etgo_survey_canned_resp_id, ad_client_id, ad_org_id, isactive, created, createdby,"
      + " updated, updatedby, language, icon, response_text, line_no, etgo_survey_type_id,"
      + " min_score, max_score)"
      + " SELECT :id, '0', '0', 'Y', now(), '0', now(), '0', :language, NULL, :text, :lineNo,"
      + " :surveyTypeId, :minScore, :maxScore"
      + " WHERE NOT EXISTS ("
      + "   SELECT 1 FROM etgo_survey_canned_resp"
      + "   WHERE etgo_survey_type_id = :surveyTypeId AND language = :language"
      + "     AND line_no = :lineNo)";

  private static final String CANNED_INSERT_WITH_ICON_SQL = ""
      + "INSERT INTO etgo_survey_canned_resp ("
      + "etgo_survey_canned_resp_id, ad_client_id, ad_org_id, isactive, created, createdby,"
      + " updated, updatedby, language, icon, response_text, line_no, etgo_survey_type_id,"
      + " min_score, max_score)"
      + " SELECT :id, '0', '0', 'Y', now(), '0', now(), '0', :language, :icon, :text, :lineNo,"
      + " :surveyTypeId, :minScore, :maxScore"
      + " WHERE NOT EXISTS ("
      + "   SELECT 1 FROM etgo_survey_canned_resp"
      + "   WHERE etgo_survey_type_id = :surveyTypeId AND language = :language"
      + "     AND line_no = :lineNo)";

  /** One canned chip, both languages' text together so the EN/ES pair never drifts apart. */
  private record CannedSeed(String icon, String textEn, String textEs, int minScore,
      int maxScore) {
  }

  // nps: no icon, 0-10 band except surveyChipAI's narrower 8-10 -- see surveys.js.
  private static final List<CannedSeed> NPS_CANNED = List.of(
      new CannedSeed(null, "Speed", "Velocidad", 0, 10),
      new CannedSeed(null, "Design", "Diseño", 0, 10),
      new CannedSeed(null, "Features", "Funcionalidad", 0, 10),
      new CannedSeed(null, "Support", "Soporte", 0, 10),
      new CannedSeed(null, "Pricing", "Precio", 0, 10),
      new CannedSeed(null, "Documentation", "Documentación", 0, 10),
      new CannedSeed(null, "AI / Copilot", "IA / Copilot", 8, 10));

  // csat_invoicing / csat_order: MIN_SCORE/MAX_SCORE forced to 1-5 (full CSAT range) -- see the
  // class javadoc's "Canned responses" section for why the column default (1-3) must NOT be used.
  private static final List<CannedSeed> CSAT_INVOICING_CANNED = List.of(
      new CannedSeed("🐢", "Too slow", "Es lento", 1, 5),
      new CannedSeed("🤔", "Hard to use", "Es difícil de usar", 1, 5),
      new CannedSeed("📄", "Need more templates", "Necesito más plantillas", 1, 5),
      new CannedSeed("🧾", "VAT/tax handling is confusing", "El IVA/impuestos es confuso",
          1, 5),
      new CannedSeed("📤", "Trouble sending to the client",
          "Problemas al enviar al cliente", 1, 5),
      new CannedSeed("🐛", "Ran into a bug", "Tuve un error", 1, 5));

  private static final List<CannedSeed> CSAT_ORDER_CANNED = List.of(
      new CannedSeed("🐢", "Too slow", "Es lento", 1, 5),
      new CannedSeed("🤔", "Hard to use", "Es difícil de usar", 1, 5),
      new CannedSeed("🔍", "Couldn't find the product", "No encontré el producto", 1, 5),
      new CannedSeed("📋", "Hard to build order lines",
          "Difícil armar las líneas de pedido", 1, 5),
      new CannedSeed("✅", "Trouble confirming the order", "Problemas al confirmar el pedido",
          1, 5),
      new CannedSeed("🐛", "Ran into a bug", "Tuve un error", 1, 5));

  /** Survey keys with no canned fallback today ({@code csat_onboarding}) are simply absent here. */
  private static final Map<String, List<CannedSeed>> CANNED_BY_SURVEY_KEY = Map.of(
      "nps", NPS_CANNED,
      "csat_invoicing", CSAT_INVOICING_CANNED,
      "csat_order", CSAT_ORDER_CANNED);

  /**
   * Seeds the global config row, the 4 survey type rows and their canned responses, if and only
   * if each is missing. Safe to call on every onboarding run (idempotent) and safe to call for a
   * second {@code AD_Client} onboarded into an install that already has another tenant (the rows
   * are System-owned and shared, not duplicated per client).
   *
   * @throws OBException if a genuine SQL error occurs, so the caller can convert it into a
   *     graceful onboarding failure
   */
  public void seedSurveyConfig() {
    try {
      insertConfig();

      Map<String, String> surveyTypeIds = new LinkedHashMap<>();
      for (SurveyTypeSeed seed : SURVEY_TYPES) {
        insertSurveyType(seed);
        surveyTypeIds.put(seed.surveyKey(), resolveSurveyTypeId(seed.surveyKey()));
      }

      for (Map.Entry<String, String> entry : surveyTypeIds.entrySet()) {
        insertCannedResponses(entry.getKey(), entry.getValue());
      }

      log.info("Survey Configuration defaults ensured (System pseudo-tenant, install-wide "
          + "singleton): {} survey type(s) checked", surveyTypeIds.size());
    } catch (Exception e) {
      throw new OBException("Failed to seed Survey Configuration defaults: " + e.getMessage(), e);
    }
  }

  private void insertConfig() {
    int rows = OBDal.getInstance().getSession().createNativeQuery(CONFIG_INSERT_SQL)
        .setParameter("id", SequenceIdData.getUUID())
        .executeUpdate();
    if (rows > 0) {
      log.debug("Seeded ETGO_Survey_Config singleton row");
    }
  }

  private void insertSurveyType(SurveyTypeSeed seed) {
    String sql = String.format(SURVEY_TYPE_INSERT_TEMPLATE,
        sqlLiteral(seed.minAccountAgeDays()), sqlLiteral(seed.inactivityGuardDays()),
        sqlLiteral(seed.minDocuments()), sqlLiteral(seed.documentGap()),
        sqlLiteral(seed.responseCooldownDays()));
    int rows = OBDal.getInstance().getSession().createNativeQuery(sql)
        .setParameter("id", SequenceIdData.getUUID())
        .setParameter("surveyKey", seed.surveyKey())
        .executeUpdate();
    if (rows > 0) {
      log.debug("Seeded ETGO_Survey_Type row for '{}'", seed.surveyKey());
    }
  }

  private String resolveSurveyTypeId(String surveyKey) {
    Object id = OBDal.getInstance().getSession().createNativeQuery(SURVEY_TYPE_ID_LOOKUP_SQL)
        .setParameter("surveyKey", surveyKey)
        .uniqueResult();
    if (id == null) {
      throw new OBException(
          "ETGO_Survey_Type row for '" + surveyKey + "' not found right after seeding it");
    }
    return id.toString();
  }

  private void insertCannedResponses(String surveyKey, String surveyTypeId) {
    List<CannedSeed> seeds = CANNED_BY_SURVEY_KEY.get(surveyKey);
    if (seeds == null) {
      // csat_onboarding: no hardcoded fallback list exists today, so nothing is seeded.
      return;
    }
    int lineNo = 10;
    int inserted = 0;
    for (CannedSeed seed : seeds) {
      inserted += insertCannedRow(surveyTypeId, "en_US", seed.icon(), seed.textEn(), lineNo,
          seed.minScore(), seed.maxScore());
      inserted += insertCannedRow(surveyTypeId, "es_ES", seed.icon(), seed.textEs(), lineNo,
          seed.minScore(), seed.maxScore());
      lineNo += 10;
    }
    if (inserted > 0) {
      log.debug("Seeded {} ETGO_Survey_Canned_Resp row(s) for '{}'", inserted, surveyKey);
    }
  }

  private int insertCannedRow(String surveyTypeId, String language, String icon, String text,
      int lineNo, int minScore, int maxScore) {
    String sql = icon != null ? CANNED_INSERT_WITH_ICON_SQL : CANNED_INSERT_NO_ICON_SQL;
    var query = OBDal.getInstance().getSession().createNativeQuery(sql)
        .setParameter("id", SequenceIdData.getUUID())
        .setParameter("surveyTypeId", surveyTypeId)
        .setParameter("language", language)
        .setParameter("text", text)
        .setParameter("lineNo", lineNo)
        .setParameter("minScore", minScore)
        .setParameter("maxScore", maxScore);
    if (icon != null) {
      query.setParameter("icon", icon);
    }
    return query.executeUpdate();
  }

  private static String sqlLiteral(Integer value) {
    return value != null ? String.valueOf(value) : "NULL";
  }
}
