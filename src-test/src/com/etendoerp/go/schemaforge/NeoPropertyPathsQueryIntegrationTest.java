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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.openbravo.base.model.ModelProvider;
import org.openbravo.base.weld.test.WeldBaseTest;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.core.SessionHandler;
import org.openbravo.dal.service.OBDal;
import org.openbravo.service.json.DefaultJsonDataService;
import org.openbravo.service.json.JsonConstants;

/**
 * Real-database proof that a property-path field (ETP-6148) reaches core the way
 * {@link NeoPropertyPaths} assumes: the {@code $}-joined path it writes into {@code criteria} and
 * {@code _sortBy} is resolved by core's {@code AdvancedQueryBuilder}, and the join is a LEFT join,
 * so a row whose path is null is still returned when the list is sorted by the path or filtered on
 * another field. An inner join would silently drop it — nothing in a mock can show which one core
 * builds.
 *
 * <p>Mostly as the REST list sends them: with {@code _use_alias=true}. Without that parameter core
 * renders the sort path as an implicit join, which drops the null row; the MCP list sends no alias
 * when it has no where clause, so one case runs without it and relies on
 * {@code applyToReadParams} turning alias mode on.
 *
 * <p>Runs the real {@code DefaultJsonDataService.fetch} (hence {@link WeldBaseTest}) on
 * {@code ADUser} of the F&B sample client, with two paths: {@code businessPartner.name} (a value)
 * and {@code businessPartner.businessPartnerCategory} (a reference, sorted by its identifier). The
 * only fixture is one user's business partner set to null with native SQL inside the test
 * transaction; every test rolls back, so nothing reaches the shared sample data.
 *
 * @covers com.etendoerp.go.schemaforge.NeoPropertyPaths
 */
public class NeoPropertyPathsQueryIntegrationTest extends WeldBaseTest {

  /** «Javier Martín» of the F&B sample client, whose business partner the test nulls. */
  private static final String USER_WITHOUT_PARTNER = "C3503BEFB3CB4848A674284A656163B9";
  /** «Joe Matt», whose business partner is «Moon Light Restaurants, Co.». */
  private static final String USER_WITH_PARTNER = "2748452130E84FF0B1A8292D88570F8F";
  private static final String NAME_PREFIX = "J";

  private static final String KEY_PARTNER_NAME = "partnerName";
  private static final String KEY_PARTNER_CATEGORY = "partnerCategory";

  private NeoPropertyPaths paths;

  @Before
  public void nullOneUsersPartner() {
    setTestAdminContext();
    SessionHandler.getInstance().setDoRollback(true);
    OBContext.setAdminMode(true);
    NeoPropertyPaths.Builder builder = NeoPropertyPaths.builder(
        ModelProvider.getInstance().getEntity("ADUser"));
    assertTrue(builder.add(KEY_PARTNER_NAME, "businessPartner.name"));
    assertTrue(builder.add(KEY_PARTNER_CATEGORY, "businessPartner.businessPartnerCategory"));
    paths = builder.build(List.of());

    int updated = OBDal.getInstance().getSession()
        .createNativeQuery("update ad_user set c_bpartner_id = null where ad_user_id = :id")
        .setParameter("id", USER_WITHOUT_PARTNER)
        .executeUpdate();
    assertEquals("the sample user must exist", 1, updated);
  }

  @After
  public void rollbackEverything() {
    while (OBContext.getOBContext() != null
        && OBContext.getOBContext().isInAdministratorMode()) {
      OBContext.restorePreviousMode();
    }
    OBDal.getInstance().rollbackAndClose();
  }

  @Test
  public void sortingByAValuePathKeepsTheRowWhosePathIsNull() throws Exception {
    for (String sort : new String[] { KEY_PARTNER_NAME, "-" + KEY_PARTNER_NAME }) {
      Map<String, String> params = startsWithJ();
      params.put(JsonConstants.SORTBY_PARAMETER, sort);

      List<String> ids = ids(fetch(params));

      assertEquals(sort, expectedJUsers(), ids.size());
      assertTrue(sort + ": the user without partner is kept (LEFT join)",
          ids.contains(USER_WITHOUT_PARTNER));
    }
  }

  @Test
  public void sortingByAReferencePathSortsByItsIdentifierAndKeepsTheNullRow() throws Exception {
    Map<String, String> params = startsWithJ();
    params.put(JsonConstants.SORTBY_PARAMETER, "-" + KEY_PARTNER_CATEGORY);
    paths.applyToReadParams(params);
    assertEquals("-businessPartner$businessPartnerCategory$_identifier",
        params.get(JsonConstants.SORTBY_PARAMETER));

    List<String> ids = ids(fetchRaw(params));

    assertEquals(expectedJUsers(), ids.size());
    assertTrue(ids.contains(USER_WITHOUT_PARTNER));
  }

  /**
   * The MCP list shape (ETP-6148 fix): no where clause, hence no {@code _use_alias} from the caller.
   * Before the fix core sorted through an implicit (inner) join here and the user without partner
   * vanished; {@code applyToReadParams} now turns alias mode on when it rewrites a key to a path.
   */
  @Test
  public void withoutTheCallersAliasASortOnAPathStillKeepsTheNullRow() throws Exception {
    for (String sort : new String[] { KEY_PARTNER_NAME, "-" + KEY_PARTNER_CATEGORY }) {
      Map<String, String> params = startsWithJ();
      params.remove(JsonConstants.USE_ALIAS);
      params.put(JsonConstants.SORTBY_PARAMETER, sort);

      List<String> ids = ids(fetch(params));

      assertEquals(sort, "true", params.get(JsonConstants.USE_ALIAS));
      assertEquals(sort, expectedJUsers(), ids.size());
      assertTrue(sort + ": the user without partner is kept (LEFT join)",
          ids.contains(USER_WITHOUT_PARTNER));
    }
  }

  @Test
  public void filteringOnAnotherFieldReturnsThePathValueAndKeepsTheNullRow() throws Exception {
    JSONArray rows = fetch(startsWithJ());

    assertEquals(expectedJUsers(), rows.length());
    boolean sawNull = false;
    boolean sawPartner = false;
    for (int i = 0; i < rows.length(); i++) {
      JSONObject row = rows.getJSONObject(i);
      Map<String, Object> values = paths.capture(row);
      if (USER_WITHOUT_PARTNER.equals(row.getString("id"))) {
        Object name = values.get(KEY_PARTNER_NAME);
        sawNull = name == null || JSONObject.NULL.equals(name);
      } else if (USER_WITH_PARTNER.equals(row.getString("id"))) {
        sawPartner = "Moon Light Restaurants, Co.".equals(values.get(KEY_PARTNER_NAME));
      }
    }
    assertTrue("the user without partner is returned with no partner name", sawNull);
    assertTrue("the path value is read through _extraProperties", sawPartner);
  }

  @Test
  public void filteringOnThePathResolvesThroughTheJoin() throws Exception {
    Map<String, String> params = startsWithJ();
    params.put("criteria", new JSONArray()
        .put(nameStartsWithJ())
        .put(new JSONObject().put("fieldName", KEY_PARTNER_NAME).put("operator", "iContains")
            .put("value", "moon light"))
        .toString());

    List<String> ids = ids(fetch(params));

    assertEquals(List.of(USER_WITH_PARTNER), ids);
  }

  // ---------------------------------------------------------------- helpers

  /**
   * The list params: active F&B users whose name starts with J, one page, with
   * {@code _use_alias=true} as the REST list always sends it ({@code NeoCrudHandler}'s
   * {@code applyWhereClause}). The alias is what makes core render the sort path as a LEFT join;
   * without it core sorts through an implicit (inner) join.
   */
  private Map<String, String> startsWithJ() throws Exception {
    Map<String, String> params = new HashMap<>();
    params.put(JsonConstants.ENTITYNAME, "ADUser");
    params.put(JsonConstants.USE_ALIAS, "true");
    params.put(JsonConstants.STARTROW_PARAMETER, "0");
    params.put(JsonConstants.ENDROW_PARAMETER, "100");
    params.put(JsonConstants.NOCOUNT_PARAMETER, "true");
    params.put("criteria", new JSONArray().put(nameStartsWithJ()).toString());
    return params;
  }

  private static JSONObject nameStartsWithJ() throws Exception {
    return new JSONObject().put("fieldName", "name").put("operator", "startsWith")
        .put("value", NAME_PREFIX);
  }

  /** Applies the paths, as NeoCrudHandler does on a GET, then fetches. */
  private JSONArray fetch(Map<String, String> params) throws Exception {
    paths.applyToReadParams(params);
    return fetchRaw(params);
  }

  private JSONArray fetchRaw(Map<String, String> params) throws Exception {
    String result = DefaultJsonDataService.getInstance().fetch(params, false);
    JSONObject response = new JSONObject(result).getJSONObject("response");
    assertEquals(result, 0, response.getInt("status"));
    return response.getJSONArray("data");
  }

  private static List<String> ids(JSONArray rows) throws Exception {
    List<String> ids = new ArrayList<>();
    for (int i = 0; i < rows.length(); i++) {
      ids.add(rows.getJSONObject(i).getString("id"));
    }
    return ids;
  }

  /** The same population counted in SQL, in the same transaction. */
  private static int expectedJUsers() {
    Object count = OBDal.getInstance().getSession()
        .createNativeQuery("select count(*) from ad_user where ad_client_id = :client"
            + " and isactive = 'Y' and name like :prefix")
        .setParameter("client", TEST_CLIENT_ID)
        .setParameter("prefix", NAME_PREFIX + "%")
        .uniqueResult();
    int expected = ((Number) count).intValue();
    assertFalse("the sample client must have such users", expected < 2);
    return expected;
  }
}
