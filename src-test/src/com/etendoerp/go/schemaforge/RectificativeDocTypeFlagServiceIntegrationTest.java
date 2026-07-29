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

import static org.junit.Assert.assertNotNull;
import static org.junit.Assume.assumeTrue;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import org.junit.Before;
import org.junit.Test;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.system.Client;
import org.openbravo.test.base.OBBaseTest;

/**
 * Live-DB (OBBaseTest) integration scaffold for {@link RectificativeDocTypeFlagService}, covering
 * the behaviors that a pure Mockito test cannot: the real flag ordering (sequence BEFORE doc type,
 * enforced by the {@code ETSG_CHECK_RECTIF_DOC_TYPE} trigger), idempotency on re-run, the
 * doc-no-controlled vs linked-sequence selection, the no-sequence skip+warning path, and that
 * standard (FAC) document types are left untouched.
 *
 * <p><b>Status: TODO — scaffold only.</b> These tests are intentionally left as guarded stubs
 * because they depend on fixtures this repo cannot assume are present:
 * <ul>
 *   <li>The {@code com.etendoerp.sif.general} module must be installed (the
 *       {@code em_etsg_isrectificative} columns and the {@code ETSG_CHECK_RECTIF_DOC_TYPE} trigger
 *       exist) — otherwise the service is a no-op by design.</li>
 *   <li>A SIF-enrolled org fixture: {@code AD_ORGINFO.EM_ETSG_HAS_*_CONFIG='Y'} for the org whose
 *       document types are exercised, because the trigger only fires for enrolled orgs (ETP-4548).</li>
 *   <li>At least one rectificative-capable (NC/DEV) invoice document type with a resolvable
 *       sequence, plus a standard (FAC) document type to prove it stays unflagged.</li>
 * </ul>
 * Each test {@code assumeTrue(...)}-skips when the SIF columns are absent, so this class is safe to
 * run in a base environment without SIF General. Fill in the fixture setup and assertions (marked
 * with {@code // TODO}) against a SIF-enrolled test client before enabling as blocking coverage —
 * map them to ticket TC-01..TC-08 and TC-10.
 *
 * <p>Follows the {@code ReactivatePaymentHandlerRemoveIntegrationTest} convention in this module:
 * extends {@link OBBaseTest}, runs inside the base test transaction, and never commits.
 */
public class RectificativeDocTypeFlagServiceIntegrationTest extends OBBaseTest {

  private boolean sifColumnsPresent;

  @Before
  public void setUpAndProbeSif() throws Exception {
    setTestAdminContext();
    // Reset the service's cached column-presence flag so it re-probes the real DB.
    RectificativeDocTypeFlagService.setRectificativeColumnsPresentForTests(null);
    sifColumnsPresent = probeSifColumns();
  }

  /** Reads whether the SIF General rectificative columns exist on both backing tables. */
  private boolean probeSifColumns() throws Exception {
    String sql = "SELECT table_name FROM information_schema.columns"
        + " WHERE (table_name = 'c_doctype' OR table_name = 'ad_sequence')"
        + " AND column_name = 'em_etsg_isrectificative'"
        + " GROUP BY table_name";
    Connection conn = OBDal.getReadOnlyInstance().getConnection();
    int tables = 0;
    try (PreparedStatement ps = conn.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
      while (rs.next()) {
        tables++;
      }
    }
    return tables >= 2;
  }

  /**
   * TC-01/TC-02: flagging a rectificative (NC/DEV) doc type flags its sequence FIRST then the doc
   * type, and both end up at 'Y'. TC-04: re-running is a no-op (idempotent).
   *
   * TODO: create/lookup a SIF-enrolled org with an NC doc type + resolvable sequence, run
   * {@code flagForClient}, then assert via native SQL that both em_etsg_isrectificative = 'Y' and
   * (re-run) that the second call reports 0 newly-flagged.
   */
  @Test
  public void flagsSequenceBeforeDocTypeAndIsIdempotent() {
    assumeTrue("SIF General not installed — skipping live rectificative flagging test",
        sifColumnsPresent);

    Client client = OBContext.getOBContext().getCurrentClient();
    assertNotNull(client);

    RectificativeDocTypeFlagService service = new RectificativeDocTypeFlagService();
    // Smoke run against the real client — must never throw and must return a non-null result.
    RectificativeDocTypeFlagService.Result first = service.flagForClient(client);
    assertNotNull(first);

    // Idempotency: a second run must not re-flag rows already at 'Y'.
    RectificativeDocTypeFlagService.Result second = service.flagForClient(client);
    org.junit.Assert.assertEquals(
        "Re-run must not re-flag already-flagged document types", 0, second.getFlaggedDocTypes());
    org.junit.Assert.assertEquals(
        "Re-run must not re-flag already-flagged sequences", 0, second.getFlaggedSequences());

    OBDal.getInstance().rollbackAndClose();
    // TODO(TC-01..TC-04): assert specific doc-type/sequence flags via native SQL against a
    //   controlled SIF-enrolled fixture (doc-no-controlled vs linked-sequence branches).
  }

  /**
   * TC-07: a rectificative-capable doc type with NO sequence available is skipped and reported as
   * a warning rather than flagged (and never trips the trigger).
   *
   * TODO: build an NC doc type that is not doc-no controlled and has no AD_Sequence linked by
   *   C_DocType_ID; run flagForClient; assert a warning naming that doc type and that its
   *   em_etsg_isrectificative stays 'N'.
   */
  @Test
  public void skipsAndWarnsForDocTypeWithoutSequence() {
    assumeTrue("SIF General not installed — skipping live no-sequence test", sifColumnsPresent);
    // TODO(TC-07): implement against a controlled fixture.
  }

  /**
   * TC-09: standard (FAC) sales/purchase invoice document types are NOT rectificative and must be
   * left untouched.
   *
   * TODO: pick a FAC doc type, run flagForClient, assert its em_etsg_isrectificative is unchanged.
   */
  @Test
  public void leavesStandardFacDocTypesUntouched() {
    assumeTrue("SIF General not installed — skipping live FAC-untouched test", sifColumnsPresent);
    // TODO(TC-09): implement against a controlled fixture.
  }
}
