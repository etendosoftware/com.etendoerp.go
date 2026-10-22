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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.criterion.Restrictions;
import com.etendoerp.go.schemaforge.selector.meta.SelectorMeta;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.access.RoleOrganization;
import org.openbravo.model.ad.access.UserRoles;
import org.openbravo.model.ad.datamodel.Column;
import org.openbravo.test.base.OBBaseTest;

/**
 * Runs view-backed rich selectors through {@link NeoSelectorService#querySelectorByColumn} against
 * a real database. The value-paged flow of {@link SelectorQueryExecutor} extends an HQL that
 * {@code OBQuery} generated and re-binds its named parameters on a Hibernate session query; the
 * readable client/organization filters are {@code String[]} parameters there, and binding one as a
 * single value made every affected selector answer 500. Only real parameter binding can vouch for
 * that, no mock can.
 *
 * <p>Read-only: no data is created, nothing needs cleaning up. The expected values are computed
 * with plain DAL reads of the same tables (so the same readable filters apply), never hard-coded.
 * The service takes an AD column, so whether the owning entity is exposed over REST is irrelevant.
 *
 * <p>Not covered here: a fixture of 2 products x 3 warehouses on {@code M_Product_Price_Warehouse_V}
 * (that view is a join over {@code M_ProductPrice} x {@code M_Warehouse} of a client, so a fixture
 * means creating products, price-list versions and prices); the Product selector is checked for
 * paging consistency over the data the database holds instead.
 *
 * @covers com.etendoerp.go.schemaforge.SelectorQueryExecutor
 */
public class NeoSelectorServiceValuePagingIntegrationTest extends OBBaseTest {

  /** ETRX_Rx_Services_Access.Defaultorg: selector ETRX_OrgsByRole (AD_Role_OrgAccess, value organization.id). */
  private static final String DEFAULT_ORG_COLUMN = "AE0AD079BB684695AFEC7B2C53844B4A";
  /** ETRX_Rx_Services_Access.Defaultrole: selector ETRX_RolesByUser (AD_User_Roles, value role.id). */
  private static final String DEFAULT_ROLE_COLUMN = "5B75F08D1B784BE79C5A600053A01718";
  /** C_OrderLine.M_Product_ID: the Product selector over M_Product_Price_Warehouse_V. */
  private static final String ORDER_LINE_PRODUCT_COLUMN = "2221";

  private static final int SMALL_PAGE = 7;

  @Before
  public void setUpAdminContext() {
    setTestAdminContext();
    // The test role cannot read OBUISEL_Selector; the selector lookup swallows that
    // OBSecurityException and answers "not a FK reference", so run in administrator mode.
    OBContext.setAdminMode(false);
  }

  @After
  public void tearDown() {
    OBDal.getInstance().rollbackAndClose();
    OBContext.restorePreviousMode();
  }

  /**
   * The column, checked to resolve to a rich OBUISEL selector whose value field is not the PK: the
   * only selectors the value-paged flow serves. Without this check a column that falls back to
   * another resolution path would pass for the wrong reason.
   */
  private static Column column(String id) {
    Column column = OBDal.getInstance().get(Column.class, id);
    assertNotNull("AD column " + id + " must exist", column);
    assertTrue("column " + id + " must resolve to an OBUISEL selector",
        NeoSelectorService.hasObuiselSelector(column));
    SelectorMeta meta = NeoSelectorService.resolveTarget(column,
        NeoSelectorService.getBaseReferenceId(column));
    assertNotNull("selector metadata must resolve for column " + id, meta);
    assertTrue("selector of column " + id + " must be rich", meta.isRich);
    assertFalse("selector of column " + id + " must have a value field other than the PK",
        meta.valueProperty == null || "id".equals(meta.valueProperty));
    return column;
  }

  private static JSONObject query(Column column, int limit, int offset) throws Exception {
    NeoResponse response = NeoSelectorService.querySelectorByColumn(column,
        column.getDBColumnName(), null, limit, offset, new HashMap<>());
    assertEquals("selector must answer, not fail: " + response.getBody(), 200,
        response.getHttpStatus());
    return response.getBody();
  }

  /** The ids of every item over all pages of {@code limit}, in the order they arrive. */
  private static List<String> walk(Column column, int limit, int expectedTotal) throws Exception {
    List<String> ids = new ArrayList<>();
    int offset = 0;
    while (true) {
      JSONObject page = query(column, limit, offset);
      assertEquals("totalCount is the same on every page", expectedTotal,
          page.getInt("totalCount"));
      JSONArray items = page.getJSONArray("items");
      assertTrue("a page never holds more than limit", items.length() <= limit);
      for (int i = 0; i < items.length(); i++) {
        ids.add(items.getJSONObject(i).getString("id"));
      }
      boolean hasMore = page.getBoolean("hasMore");
      assertEquals("hasMore says whether another page exists", offset + limit < expectedTotal,
          hasMore);
      if (!hasMore) {
        return ids;
      }
      assertEquals("every page but the last is full", limit, items.length());
      offset += limit;
    }
  }

  private static void assertDistinct(List<String> ids) {
    assertEquals("no id repeats across the pages: " + ids, ids.size(), new HashSet<>(ids).size());
  }

  /**
   * The org selector's where ({@code e.role.id=@inpdefaultrole@}) has an unknown placeholder, so
   * the clause is dropped and it returns the whole AD_Role_OrgAccess table, many rows per
   * organization. It must still answer one item per organization, a totalCount of distinct
   * organizations and pages that neither repeat nor skip one.
   */
  @Test
  public void defaultOrgSelectorPagesByOrganization() throws Exception {
    Set<String> expectedOrgs = new HashSet<>();
    List<RoleOrganization> rows = OBDal.getInstance().createCriteria(RoleOrganization.class).list();
    for (RoleOrganization row : rows) {
      expectedOrgs.add(row.getOrganization().getId());
    }
    assertFalse("the database must hold role-organization access rows", expectedOrgs.isEmpty());
    Column column = column(DEFAULT_ORG_COLUMN);

    JSONObject first = query(column, 100, 0);
    assertEquals(expectedOrgs.size(), first.getInt("totalCount"));

    List<String> walked = walk(column, SMALL_PAGE, expectedOrgs.size());
    assertDistinct(walked);
    assertEquals("the pages together are exactly the organizations", expectedOrgs,
        new HashSet<>(walked));
    assertEquals(expectedOrgs.size(), walked.size());
  }

  /**
   * The role selector is bound to the context user ({@code e.userContact.id=@AD_User_ID@}): its
   * items are exactly the distinct active roles of that user.
   */
  @Test
  public void defaultRoleSelectorReturnsTheDistinctRolesOfTheContextUser() throws Exception {
    OBCriteria<UserRoles> criteria = OBDal.getInstance().createCriteria(UserRoles.class);
    criteria.add(Restrictions.eq("userContact.id", OBContext.getOBContext().getUser().getId()));
    Set<String> expectedRoles = new HashSet<>();
    for (UserRoles userRole : criteria.list()) {
      expectedRoles.add(userRole.getRole().getId());
    }
    assertFalse("the context user must have roles", expectedRoles.isEmpty());
    Column column = column(DEFAULT_ROLE_COLUMN);

    JSONObject first = query(column, 100, 0);
    assertEquals(expectedRoles.size(), first.getInt("totalCount"));

    List<String> walked = walk(column, SMALL_PAGE, expectedRoles.size());
    assertDistinct(walked);
    assertEquals(expectedRoles, new HashSet<>(walked));
  }

  /**
   * The Product selector (view with one row per product x warehouse): a page of one is one item,
   * the next offset is the next item, and the pages together hold each product once, as many as
   * totalCount says.
   */
  @Test
  public void productSelectorPagesByProductNotByViewRow() throws Exception {
    Column column = column(ORDER_LINE_PRODUCT_COLUMN);

    JSONObject firstOfOne = query(column, 1, 0);
    int total = firstOfOne.getInt("totalCount");
    assertEquals(Math.min(1, total), firstOfOne.getJSONArray("items").length());
    if (total > 1) {
      JSONObject secondOfOne = query(column, 1, 1);
      assertEquals(1, secondOfOne.getJSONArray("items").length());
      assertFalse("offset 1 is the next product, not the first again",
          firstOfOne.getJSONArray("items").getJSONObject(0).getString("id")
              .equals(secondOfOne.getJSONArray("items").getJSONObject(0).getString("id")));
    }

    List<String> walked = walk(column, 100, total);
    assertDistinct(walked);
    assertEquals("one item per product: as many as totalCount says", total, walked.size());
  }
}
