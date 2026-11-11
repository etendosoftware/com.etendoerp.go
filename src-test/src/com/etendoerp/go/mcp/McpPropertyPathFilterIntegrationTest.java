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

package com.etendoerp.go.mcp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Collections;
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
import org.openbravo.model.ad.datamodel.Table;
import org.openbravo.model.ad.ui.Tab;
import org.openbravo.service.json.DefaultJsonDataService;
import org.openbravo.service.json.JsonConstants;

import com.etendoerp.go.schemaforge.NeoPropertyPaths;
import com.etendoerp.go.schemaforge.NeoPropertyPathsTestSupport;
import com.etendoerp.go.schemaforge.data.SFEntity;

/**
 * Real-database pin of the documented limitation of neo-headless.md §4.3a (ETP-6148): an MCP
 * filter on a property-path field is rendered as an implicit join ({@code e.<path>…}, an inner
 * join), unlike the REST grid criteria, which core left-joins. A row whose path is null is
 * therefore excluded by the MCP filter — and, because every MCP operator (equality, {@code in},
 * {@code contains}, the ranges) is false on a null, the REST criteria with the same operator
 * exclude it too. This test proves the two channels return the same rows, on the real query.
 * The operator set that keeps this true is guarded by {@code McpBusinessFiltersTest}.
 *
 * <p>A class of its own, in the {@code mcp} package: it drives the package-private
 * {@code McpQuerySupport.buildWhereFromFilters} and the real {@code DefaultJsonDataService}
 * (hence {@link WeldBaseTest}); the REST half of the comparison is
 * {@code NeoPropertyPathsQueryIntegrationTest}'s.
 *
 * <p>Fixture as there: {@code ADUser} of the F&B sample client, path {@code businessPartner.name},
 * one user's business partner set to null with native SQL inside the test transaction, which
 * every test rolls back.
 *
 * @covers com.etendoerp.go.mcp.McpQuerySupport
 */
public class McpPropertyPathFilterIntegrationTest extends WeldBaseTest {

  /** «Javier Martín», whose business partner the test nulls. */
  private static final String USER_WITHOUT_PARTNER = "C3503BEFB3CB4848A674284A656163B9";
  /** «Joe Matt», partner «Moon Light Restaurants, Co.». */
  private static final String USER_WITH_PARTNER = "2748452130E84FF0B1A8292D88570F8F";
  private static final String KEY = "partnerName";

  private NeoPropertyPaths paths;
  private Tab userTab;
  private SFEntity sfEntity;

  @Before
  public void nullOneUsersPartner() {
    setTestAdminContext();
    SessionHandler.getInstance().setDoRollback(true);
    OBContext.setAdminMode(true);
    paths = NeoPropertyPathsTestSupport.paths(ModelProvider.getInstance().getEntity("ADUser"),
        KEY, "businessPartner.name");
    assertFalse(paths.isEmpty());

    Table table = mock(Table.class);
    when(table.getDBTableName()).thenReturn("AD_User");
    userTab = mock(Tab.class);
    when(userTab.getTable()).thenReturn(table);
    sfEntity = mock(SFEntity.class);
    when(sfEntity.getId()).thenReturn("IT-NO-SF-ENTITY");
    when(sfEntity.getName()).thenReturn("users");

    assertEquals(1, OBDal.getInstance().getSession()
        .createNativeQuery("update ad_user set c_bpartner_id = null where ad_user_id = :id")
        .setParameter("id", USER_WITHOUT_PARTNER)
        .executeUpdate());
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
  public void theMcpPathFilterIsAnImplicitJoin() throws Exception {
    String where = mcpWhere(new JSONObject().put(KEY, "Moon Light Restaurants, Co."));

    assertEquals("e.businessPartner.name='Moon Light Restaurants, Co.'", where);
    assertFalse(where.toLowerCase().contains("join"));
  }

  @Test
  public void equalityExcludesTheNullRowOnBothChannels() throws Exception {
    assertSameRows(new JSONObject().put(KEY, "Moon Light Restaurants, Co."),
        criterion("equals", "Moon Light Restaurants, Co."));
  }

  @Test
  public void containsExcludesTheNullRowOnBothChannels() throws Exception {
    assertSameRows(new JSONObject().put(KEY, new JSONObject().put("contains", "moon")),
        criterion("iContains", "moon"));
  }

  @Test
  public void inExcludesTheNullRowOnBothChannels() throws Exception {
    assertSameRows(new JSONObject().put(KEY, new JSONObject().put("in", new JSONArray()
        .put("Moon Light Restaurants, Co.").put("Javier Martín"))),
        criterion("equals", "Moon Light Restaurants, Co."));
  }

  @Test
  public void aRangeExcludesTheNullRowOnBothChannels() throws Exception {
    assertSameRows(new JSONObject().put(KEY, new JSONObject().put("gte", "A")),
        criterion("greaterOrEqual", "A"));
  }

  // ---------------------------------------------------------------- helpers

  private String mcpWhere(JSONObject filters) throws Exception {
    return McpQuerySupport.buildWhereFromFilters(filters, userTab, sfEntity,
        Collections.emptyMap(), null, paths, null);
  }

  private static JSONObject criterion(String operator, String value) throws Exception {
    return new JSONObject().put("fieldName", KEY).put("operator", operator).put("value", value);
  }

  /**
   * The MCP filter (where clause on the implicit join) and the REST criteria (rewritten by
   * {@code applyToReadParams}, left-joined by core) return the same users, without the one whose
   * path is null.
   */
  private void assertSameRows(JSONObject mcpFilters, JSONObject restCriterion) throws Exception {
    Map<String, String> mcp = baseParams();
    mcp.put(JsonConstants.WHERE_AND_FILTER_CLAUSE, mcpWhere(mcpFilters));
    mcp.put(JsonConstants.USE_ALIAS, "true");
    List<String> mcpIds = ids(mcp);

    Map<String, String> rest = baseParams();
    rest.put(JsonConstants.USE_ALIAS, "true");
    rest.put("criteria", new JSONArray().put(nameStartsWithJ()).put(restCriterion).toString());
    paths.applyToReadParams(rest);
    List<String> restIds = ids(rest);

    assertFalse("the MCP filter excludes the null-path row", mcpIds.contains(USER_WITHOUT_PARTNER));
    assertFalse("so do the REST criteria", restIds.contains(USER_WITHOUT_PARTNER));
    assertTrue("the row with the value is returned", mcpIds.contains(USER_WITH_PARTNER));
    Collections.sort(mcpIds);
    Collections.sort(restIds);
    assertEquals("both channels return the same rows", restIds, mcpIds);
  }

  private Map<String, String> baseParams() throws Exception {
    Map<String, String> params = new HashMap<>();
    params.put(JsonConstants.ENTITYNAME, "ADUser");
    params.put(JsonConstants.STARTROW_PARAMETER, "0");
    params.put(JsonConstants.ENDROW_PARAMETER, "100");
    params.put(JsonConstants.NOCOUNT_PARAMETER, "true");
    params.put("criteria", new JSONArray().put(nameStartsWithJ()).toString());
    return params;
  }

  private static JSONObject nameStartsWithJ() throws Exception {
    return new JSONObject().put("fieldName", "name").put("operator", "startsWith")
        .put("value", "J");
  }

  private static List<String> ids(Map<String, String> params) throws Exception {
    String result = DefaultJsonDataService.getInstance().fetch(params, false);
    JSONObject response = new JSONObject(result).getJSONObject("response");
    assertEquals(result, 0, response.getInt("status"));
    JSONArray rows = response.getJSONArray("data");
    List<String> ids = new ArrayList<>();
    for (int i = 0; i < rows.length(); i++) {
      ids.add(rows.getJSONObject(i).getString("id"));
    }
    return ids;
  }
}
