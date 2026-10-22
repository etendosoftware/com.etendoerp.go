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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.hibernate.query.NativeQuery;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.openbravo.dal.service.OBDal;
import org.openbravo.erpCommon.utility.SequenceIdData;
import org.openbravo.test.base.OBBaseTest;

/**
 * Real-database specs for {@link OnboardingSurveyConfigService} — ETP-5870, gap Q1.
 *
 * <p>Everything here needs a real database on purpose. The behaviors this class guards are all
 * properties of what is <em>committed</em> by raw native SQL, which a mock cannot observe:
 * exact row counts produced from an empty install, that a second run never duplicates a row, the
 * literal tuning values persisted per {@code survey_key} (including the {@code csat_onboarding}
 * special case), and that the CSAT canned responses actually land with the explicit
 * {@code 1-5} score band rather than silently falling back to the column default ({@code 1-3}) —
 * exactly the trap described in the service's own Javadoc.
 *
 * <p><b>Isolation strategy.</b> {@link OnboardingSurveyConfigService#seedSurveyConfig()} is a
 * System pseudo-tenant singleton: its guards are "does this row exist ANYWHERE in the install",
 * not scoped to any {@code AD_Client_ID}. To make the "produces exactly 43 rows" assertion
 * deterministic regardless of what the shared test database already holds, {@link #wipeSingleton()}
 * deletes the 3 tables' rows (child-first, respecting the {@code ETGO_SURVEY_CANNED_RESP} →
 * {@code ETGO_SURVEY_TYPE} FK) inside the test's own uncommitted session. The service itself never
 * commits — every insert is a plain native {@code executeUpdate()} against the session's open
 * transaction — so {@link OBDal#rollbackAndClose()} in {@link #tearDown()} discards both the wipe
 * and the seeded rows together, leaving whatever the database held before the test completely
 * untouched.
 *
 * @covers com.etendoerp.go.onboarding.OnboardingSurveyConfigService
 */
public class OnboardingSurveyConfigServiceIntegrationTest extends OBBaseTest {

  private final OnboardingSurveyConfigService service = new OnboardingSurveyConfigService();

  @Before
  public void wipeSingleton() {
    setSystemAdministratorContext();
    exec("DELETE FROM etgo_survey_canned_resp");
    exec("DELETE FROM etgo_survey_type");
    exec("DELETE FROM etgo_survey_config");
  }

  @After
  public void tearDown() {
    // Nothing above or in the service commits, so this discards the wipe and every seeded row —
    // the real database is left exactly as it was before this test ran.
    OBDal.getInstance().rollbackAndClose();
  }

  // -----------------------------------------------------------------------------------------
  // Row counts from an empty install
  // -----------------------------------------------------------------------------------------

  @Test
  public void testSeedSurveyConfigFromEmptyDatabaseInsertsExactly43Rows() {
    service.seedSurveyConfig();

    assertEquals("Exactly one global config singleton row", 1, countRows("etgo_survey_config"));
    assertEquals("Exactly 4 survey type rows (nps, csat_onboarding, csat_invoicing, csat_order)",
        4, countRows("etgo_survey_type"));
    // nps 7 chips x2 languages + csat_invoicing 6x2 + csat_order 6x2 = 14 + 12 + 12 = 38.
    assertEquals("Exactly 38 canned response rows (nps 7x2, csat_invoicing 6x2, csat_order 6x2)",
        38, countRows("etgo_survey_canned_resp"));
  }

  // -----------------------------------------------------------------------------------------
  // Idempotency — a second run must not duplicate anything
  // -----------------------------------------------------------------------------------------

  @Test
  public void testSeedSurveyConfigIsIdempotentOnASecondRun() {
    service.seedSurveyConfig();
    service.seedSurveyConfig();

    assertEquals(1, countRows("etgo_survey_config"));
    assertEquals(4, countRows("etgo_survey_type"));
    assertEquals(38, countRows("etgo_survey_canned_resp"));
  }

  /**
   * ETP-5870 robustness case: a survey type row that already existed before this run (e.g. a
   * previous partial run, or a row an admin tweaked by hand) must keep its own tuning values —
   * the guard is "does a row for this {@code survey_key} exist", not "does it match the
   * defaults" — and its canned responses must still be seeded, referencing that pre-existing
   * row's REAL id rather than a stale one generated before the guard found it.
   */
  @Test
  public void testSeedSurveyConfigDoesNotOverwriteAPreexistingSurveyTypeRow() {
    String preexistingId = SequenceIdData.getUUID();
    exec("INSERT INTO etgo_survey_type (etgo_survey_type_id, ad_client_id, ad_org_id, isactive,"
        + " created, createdby, updated, updatedby, survey_key, min_account_age_days,"
        + " inactivity_guard_days, min_documents, document_gap, response_cooldown_days)"
        + " VALUES ('" + preexistingId + "', '0', '0', 'Y', now(), '0', now(), '0', 'nps',"
        + " 999, 999, NULL, NULL, 999)");

    service.seedSurveyConfig();

    assertEquals("A pre-existing survey type row must not be duplicated", 4,
        countRows("etgo_survey_type"));
    Number[] npsRow = fetchSurveyType("nps");
    assertEquals("The pre-existing row's own tuning value must survive untouched", 999,
        npsRow[0].intValue());
    assertEquals("The canned responses must still be seeded for the pre-existing row", 14L,
        countCannedForSurveyTypeId(preexistingId));
  }

  // -----------------------------------------------------------------------------------------
  // Exact tuning values per survey_key (the csat_onboarding special case)
  // -----------------------------------------------------------------------------------------

  @Test
  public void testNpsUsesTheGenericSixtyDayAccountAgeDefault() {
    service.seedSurveyConfig();

    Number[] row = fetchSurveyType("nps");
    assertEquals(60, row[0].intValue());
    assertEquals(14, row[1].intValue());
    assertNull("minDocuments does not apply to nps", row[2]);
    assertNull("documentGap does not apply to nps", row[3]);
    assertEquals(90, row[4].intValue());
  }

  /**
   * The score-band-style trap for this table: {@code csat_onboarding} reuses
   * {@code MIN_ACCOUNT_AGE_DAYS} with a DIFFERENT meaning ("days since onboarding completed") and
   * must get its own special-cased default of {@code 1}, never the generic {@code nps} default
   * of {@code 60}.
   */
  @Test
  public void testCsatOnboardingUsesOneDayNotTheNpsSixtyDayDefault() {
    service.seedSurveyConfig();

    Number[] row = fetchSurveyType("csat_onboarding");
    assertEquals("csat_onboarding must get its own 1-day default, not nps's 60-day one", 1,
        row[0].intValue());
    assertNull("inactivityGuardDays does not apply to csat_onboarding", row[1]);
    assertNull("minDocuments does not apply to csat_onboarding", row[2]);
    assertNull("documentGap does not apply to csat_onboarding", row[3]);
    assertNull("responseCooldownDays does not apply to csat_onboarding", row[4]);
  }

  @Test
  public void testCsatInvoicingAndCsatOrderLeaveAccountAgeAndInactivityGuardNull() {
    service.seedSurveyConfig();

    for (String key : new String[] { "csat_invoicing", "csat_order" }) {
      Number[] row = fetchSurveyType(key);
      assertNull(key + ": minAccountAgeDays does not apply", row[0]);
      assertNull(key + ": inactivityGuardDays does not apply", row[1]);
      assertEquals(key + ": minDocuments", 5, row[2].intValue());
      assertEquals(key + ": documentGap", 30, row[3].intValue());
      assertEquals(key + ": responseCooldownDays", 90, row[4].intValue());
    }
  }

  @Test
  public void testCsatOnboardingHasNoCannedResponsesSeeded() {
    service.seedSurveyConfig();

    Number[] row = fetchSurveyType("csat_onboarding");
    assertEquals("csat_onboarding has no hardcoded fallback list, so none is seeded", 0L,
        countCannedForSurveyTypeId(fetchSurveyTypeId("csat_onboarding")));
    assertTrue("sanity: the row itself must exist", row != null);
  }

  // -----------------------------------------------------------------------------------------
  // Canned response score bands — the column-default trap (MIN_SCORE/MAX_SCORE default to 1-3)
  // -----------------------------------------------------------------------------------------

  @Test
  public void testCsatCannedResponsesGetTheFullOneToFiveBandNotTheColumnDefault() {
    service.seedSurveyConfig();

    for (String key : new String[] { "csat_invoicing", "csat_order" }) {
      List<Object[]> rows = fetchCannedScoreBands(key);
      assertEquals(key + ": 6 chips x 2 languages", 12, rows.size());
      for (Object[] bandRow : rows) {
        assertEquals(key + ": MIN_SCORE must be the explicit full-range 1, not the column "
            + "default", 1, ((Number) bandRow[0]).intValue());
        assertEquals(key + ": MAX_SCORE must be the explicit full-range 5, not the column "
            + "default of 3", 5, ((Number) bandRow[1]).intValue());
      }
    }
  }

  @Test
  public void testNpsCannedResponsesUseZeroToTenExceptTheAiChipsNarrowerBand() {
    service.seedSurveyConfig();

    List<Object[]> rows = fetchCannedScoreBandsWithText("nps");
    assertEquals("7 chips x 2 languages", 14, rows.size());
    for (Object[] row : rows) {
      int minScore = ((Number) row[0]).intValue();
      int maxScore = ((Number) row[1]).intValue();
      String text = (String) row[2];
      if ("AI / Copilot".equals(text) || "IA / Copilot".equals(text)) {
        assertEquals("The AI/Copilot chip keeps its narrower band", 8, minScore);
        assertEquals("The AI/Copilot chip keeps its narrower band", 10, maxScore);
      } else {
        assertEquals("Every other nps chip uses the real 0-10 range", 0, minScore);
        assertEquals("Every other nps chip uses the real 0-10 range", 10, maxScore);
      }
    }
  }

  // -----------------------------------------------------------------------------------------
  // Scope — System pseudo-tenant singleton, not the onboarding tenant's AD_Client
  // -----------------------------------------------------------------------------------------

  @Test
  public void testEverySeededRowIsScopedToTheSystemPseudoTenant() {
    service.seedSurveyConfig();

    assertScopedToSystem("etgo_survey_config");
    assertScopedToSystem("etgo_survey_type");
    assertScopedToSystem("etgo_survey_canned_resp");
  }

  // -----------------------------------------------------------------------------------------
  // Fixtures / committed-state readers (native SQL — the service has no DAL mapping)
  // -----------------------------------------------------------------------------------------

  private void assertScopedToSystem(String table) {
    NativeQuery<Object[]> query = OBDal.getInstance().getSession().createNativeQuery(
        "SELECT DISTINCT ad_client_id, ad_org_id FROM " + table);
    List<Object[]> rows = query.list();
    assertTrue(table + " must have at least one row to check scope for", !rows.isEmpty());
    for (Object[] row : rows) {
      assertEquals(table + ": AD_Client_ID must be the System pseudo-tenant, never the onboarding "
          + "tenant's client", "0", row[0]);
      assertEquals(table + ": AD_Org_ID must be the System pseudo-tenant", "0", row[1]);
    }
  }

  private long countRows(String table) {
    Number count = (Number) OBDal.getInstance().getSession()
        .createNativeQuery("SELECT count(*) FROM " + table)
        .getSingleResult();
    return count.longValue();
  }

  private long countCannedForSurveyTypeId(String surveyTypeId) {
    Number count = (Number) OBDal.getInstance().getSession()
        .createNativeQuery("SELECT count(*) FROM etgo_survey_canned_resp"
            + " WHERE etgo_survey_type_id = :surveyTypeId")
        .setParameter("surveyTypeId", surveyTypeId)
        .getSingleResult();
    return count.longValue();
  }

  private String fetchSurveyTypeId(String surveyKey) {
    return (String) OBDal.getInstance().getSession()
        .createNativeQuery("SELECT etgo_survey_type_id FROM etgo_survey_type"
            + " WHERE survey_key = :surveyKey")
        .setParameter("surveyKey", surveyKey)
        .getSingleResult();
  }

  /**
   * @param surveyKey the {@code SURVEY_KEY} to look up
   * @return {@code [minAccountAgeDays, inactivityGuardDays, minDocuments, documentGap,
   *     responseCooldownDays]}, any element possibly {@code null}
   */
  @SuppressWarnings("unchecked")
  private Number[] fetchSurveyType(String surveyKey) {
    NativeQuery<Object[]> query = OBDal.getInstance().getSession().createNativeQuery(
        "SELECT min_account_age_days, inactivity_guard_days, min_documents, document_gap,"
            + " response_cooldown_days FROM etgo_survey_type WHERE survey_key = :surveyKey");
    query.setParameter("surveyKey", surveyKey);
    Object[] row = query.getSingleResult();
    return new Number[] { (Number) row[0], (Number) row[1], (Number) row[2], (Number) row[3],
        (Number) row[4] };
  }

  /** @return each canned response's {@code [minScore, maxScore]} for the given survey key. */
  @SuppressWarnings("unchecked")
  private List<Object[]> fetchCannedScoreBands(String surveyKey) {
    NativeQuery<Object[]> query = OBDal.getInstance().getSession().createNativeQuery(
        "SELECT cr.min_score, cr.max_score FROM etgo_survey_canned_resp cr"
            + " JOIN etgo_survey_type st ON st.etgo_survey_type_id = cr.etgo_survey_type_id"
            + " WHERE st.survey_key = :surveyKey");
    query.setParameter("surveyKey", surveyKey);
    return query.list();
  }

  /** @return each canned response's {@code [minScore, maxScore, responseText]} for the key. */
  @SuppressWarnings("unchecked")
  private List<Object[]> fetchCannedScoreBandsWithText(String surveyKey) {
    NativeQuery<Object[]> query = OBDal.getInstance().getSession().createNativeQuery(
        "SELECT cr.min_score, cr.max_score, cr.response_text FROM etgo_survey_canned_resp cr"
            + " JOIN etgo_survey_type st ON st.etgo_survey_type_id = cr.etgo_survey_type_id"
            + " WHERE st.survey_key = :surveyKey");
    query.setParameter("surveyKey", surveyKey);
    return query.list();
  }

  private void exec(String sql) {
    OBDal.getInstance().getSession().createNativeQuery(sql).executeUpdate();
  }
}
