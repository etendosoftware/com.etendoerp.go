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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;

import org.codehaus.jettison.json.JSONObject;
import org.hibernate.stat.Statistics;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Assume;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.openbravo.base.session.SessionFactoryController;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.datamodel.Column;
import org.openbravo.test.base.OBBaseTest;
import org.openbravo.test.base.SystemLanguageFixture;
import org.openbravo.test.base.TestConstants.Clients;
import org.openbravo.test.base.TestConstants.Languages;
import org.openbravo.test.base.TestConstants.Orgs;
import org.openbravo.test.base.TestConstants.Roles;
import org.openbravo.test.base.TestConstants.Users;

/**
 * ETP-6103 — the SQL statements of a selector page must not grow with its rows. Each label used to
 * look up its translation with its own query, about 2.8 ms per row in production; the translations
 * are now preloaded once per page.
 *
 * <p>The per-row lookup only happens when a translation is installed, so the fixture installs
 * Spanish next to the base language and runs in Spanish. Without that the test could not fail.
 * There is no fixed formula: each referenced entity loads in its own batches, so the test only
 * asserts that the statement difference between two page sizes stays well below the row
 * difference.</p>
 *
 * @covers com.etendoerp.go.schemaforge.SelectorQueryExecutor
 */
public class NeoSelectorStatementCountIntegrationTest extends OBBaseTest {
  /** GL_JournalLine.C_ValidCombination_ID: the "Valid Combination" selector, no grid fields. */
  private static final String VALID_COMBINATION_COLUMN = "5955";
  /** M_MovementLine.M_Product_ID: the "Product Complete" selector, with grid fields. */
  private static final String PRODUCT_COMPLETE_COLUMN = "3593";
  private static final int SMALL_PAGE = 10;
  private static final int LARGE_PAGE = 100;

  private Statistics statistics;

  private static final SystemLanguageFixture SPANISH = new SystemLanguageFixture(
      Languages.ES_ES_LANG_ID);

  @BeforeClass
  public static void installSpanish() {
    SPANISH.install();
  }

  @AfterClass
  public static void restoreSpanish() {
    SPANISH.restore();
  }

  @Before
  public void setUpSpanishContext() {
    OBContext.setOBContext(Users.ADMIN, Roles.FB_GRP_ADMIN, Clients.FB_GRP, Orgs.MAIN,
        Languages.ES_ES_ISOCODE);
    // The test role cannot read OBUISEL_Selector, so run in administrator mode.
    OBContext.setAdminMode(false);
    assertTrue("precondition: a translation is installed", OBContext.hasTranslationInstalled());
    statistics = SessionFactoryController.getInstance().getSessionFactory().getStatistics();
    statistics.setStatisticsEnabled(true);
  }

  @After
  public void tearDown() {
    statistics.setStatisticsEnabled(false);
    OBDal.getInstance().rollbackAndClose();
    OBContext.restorePreviousMode();
  }

  @Test
  public void selectorPageStatementsDoNotGrowWithTheRows() throws Exception {
    assertStatementsDoNotGrowWithTheRows(VALID_COMBINATION_COLUMN);
  }

  @Test
  public void richSelectorWithGridFieldsStatementsDoNotGrowWithTheRows() throws Exception {
    assertStatementsDoNotGrowWithTheRows(PRODUCT_COMPLETE_COLUMN);
  }

  private void assertStatementsDoNotGrowWithTheRows(String columnId) throws Exception {
    Page small = page(columnId, SMALL_PAGE);
    Page large = page(columnId, LARGE_PAGE);
    Assume.assumeTrue("the test client needs more rows than the small page",
        large.rows > small.rows + SMALL_PAGE);

    long extraRows = (long) large.rows - small.rows;
    long extraStatements = large.statements - small.statements;
    assertTrue("selector " + columnId + ": " + extraStatements + " more statements for "
        + extraRows + " more rows (" + small + " / " + large + ")",
        extraStatements * 4 < extraRows);
  }

  private Page page(String columnId, int limit) throws Exception {
    OBDal.getInstance().getSession().clear();
    Column column = OBDal.getInstance().get(Column.class, columnId);
    assertNotNull("AD column " + columnId + " must exist", column);
    statistics.clear();
    NeoResponse response = NeoSelectorService.querySelectorByColumn(column,
        column.getDBColumnName(), null, limit, 0, new HashMap<>());
    long statements = statistics.getPrepareStatementCount();
    assertEquals("selector must answer, not fail: " + response.getBody(), 200,
        response.getHttpStatus());
    JSONObject body = response.getBody();
    return new Page(body.getJSONArray("items").length(), statements);
  }

  private static final class Page {
    final int rows;
    final long statements;

    Page(int rows, long statements) {
      this.rows = rows;
      this.statements = statements;
    }

    @Override
    public String toString() {
      return rows + " rows, " + statements + " statements";
    }
  }
}
