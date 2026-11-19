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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.codehaus.jettison.json.JSONObject;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.ModelProvider;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.security.OrganizationStructureProvider;

/**
 * ETP-5086: organization context and org-proximity ordering of the combo first-option fallback.
 */
public class NeoFallbackOrganizationTest {

  private static NeoContext ctx(String bodyOrg, String parentId) throws Exception {
    NeoContext ctx = mock(NeoContext.class);
    JSONObject body = new JSONObject();
    if (bodyOrg != null) {
      body.put("organization", bodyOrg);
    }
    when(ctx.getRequestBody()).thenReturn(body);
    Map<String, String> query = new HashMap<>();
    if (parentId != null) {
      query.put("parentId", parentId);
    }
    when(ctx.getQueryParams()).thenReturn(query);
    return ctx;
  }

  @Test
  public void bodyOrgIsUsed() throws Exception {
    Map<String, String> params = new HashMap<>();
    NeoDefaultsService.putFallbackOrganization(params, ctx("ORG1", "P1"));
    assertEquals("ORG1", params.get("AD_Org_ID"));
    assertFalse(params.containsKey("parentId"));
    assertEquals("Y", params.get(NeoDefaultsService.PARAM_PREFER_CONTEXT_ORG));
  }

  @Test
  public void parentIdIsUsedWithoutBodyOrg() throws Exception {
    Map<String, String> params = new HashMap<>();
    NeoDefaultsService.putFallbackOrganization(params, ctx(null, "P1"));
    assertEquals("P1", params.get("parentId"));
    assertFalse(params.containsKey("AD_Org_ID"));
    assertEquals("Y", params.get(NeoDefaultsService.PARAM_PREFER_CONTEXT_ORG));
  }

  @Test
  public void orgZeroFallsBackToParentId() throws Exception {
    Map<String, String> params = new HashMap<>();
    NeoDefaultsService.putFallbackOrganization(params, ctx("0", "P1"));
    assertEquals("P1", params.get("parentId"));
    assertFalse(params.containsKey("AD_Org_ID"));
  }

  @Test
  public void orgZeroWithoutParentAddsNothing() throws Exception {
    Map<String, String> params = new HashMap<>();
    NeoDefaultsService.putFallbackOrganization(params, ctx("0", null));
    assertTrue(params.isEmpty());
  }

  @Test
  public void noSourceKeepsTodaysBehaviour() throws Exception {
    Map<String, String> params = new HashMap<>();
    NeoDefaultsService.putFallbackOrganization(params, ctx(null, null));
    assertTrue(params.isEmpty());
  }

  @Test
  public void existingAdOrgIdIsNotOverridden() throws Exception {
    Map<String, String> params = new HashMap<>();
    params.put("AD_Org_ID", "CALLER");
    NeoDefaultsService.putFallbackOrganization(params, ctx("ORG1", "P1"));
    assertEquals("CALLER", params.get("AD_Org_ID"));
    assertFalse(params.containsKey("parentId"));
    assertFalse(params.containsKey(NeoDefaultsService.PARAM_PREFER_CONTEXT_ORG));
  }

  @Test
  public void proximityOrderRanksOrgThenAncestorThenStar() {
    SelectorQueryBuilder.HqlWithParams order = SelectorOrgFilter.buildOrgProximityOrder("e",
        Arrays.asList("CHILD", "PARENT", "0"));
    assertEquals("CASE WHEN e.organization.id = :selectorPrefOrg0 THEN 0"
        + " WHEN e.organization.id = :selectorPrefOrg1 THEN 1"
        + " WHEN e.organization.id = '0' THEN 3 ELSE 2 END", order.getHql());
    assertEquals("CHILD", order.getParams().get("selectorPrefOrg0"));
    assertEquals("PARENT", order.getParams().get("selectorPrefOrg1"));
  }

  @Test
  public void proximityOrderIsNullWithoutOrg() {
    assertNull(SelectorOrgFilter.buildOrgProximityOrder("e", Collections.singletonList("0")));
    assertNull(SelectorOrgFilter.buildOrgProximityOrder("e", Collections.emptyList()));
  }

  @Test
  public void orderByPutsProximityBeforeDisplayName() {
    SelectorQueryBuilder.HqlWithParams order = SelectorOrgFilter.buildOrgProximityOrder("e",
        Arrays.asList("CHILD", "PARENT"));
    String withOrg = SelectorQueryExecutor.orderBy(order, "e", "name");
    assertTrue(withOrg, withOrg.startsWith(" ORDER BY " + order.getHql() + ", e.name, e.id"));
  }

  @Test
  public void orderByIsUnchangedWithoutPreference() {
    assertEquals(" ORDER BY e.name, e.id", SelectorQueryExecutor.orderBy(null, "e", "name"));
  }

  @Test
  public void selectorServiceForwardsPreferenceOnlyWithFlagAndOrg() {
    Map<String, Object> filter = new HashMap<>();
    Map<String, String> flagged = new HashMap<>();
    flagged.put(NeoDefaultsService.PARAM_PREFER_CONTEXT_ORG, "Y");
    NeoSelectorService.putOrgPreference(filter, flagged, "ORG1");
    assertEquals("ORG1", filter.get(SelectorQueryExecutor.PARAM_PREFER_ORG));

    Map<String, Object> noOrg = new HashMap<>();
    NeoSelectorService.putOrgPreference(noOrg, flagged, null);
    assertTrue(noOrg.isEmpty());

    Map<String, Object> noFlag = new HashMap<>();
    NeoSelectorService.putOrgPreference(noFlag, new HashMap<>(), "ORG1");
    assertTrue(noFlag.isEmpty());
  }

  @Test
  public void proximityOrderReadsTheParentListOfTheOrg() {
    OBContext obContext = mock(OBContext.class);
    OrganizationStructureProvider osp = mock(OrganizationStructureProvider.class);
    when(obContext.getOrganizationStructureProvider()).thenReturn(osp);
    when(osp.getParentList("CHILD", true)).thenReturn(Arrays.asList("CHILD", "PARENT", "0"));
    ModelProvider modelProvider = mock(ModelProvider.class);
    Entity entity = mock(Entity.class);
    when(modelProvider.getEntity("Calendar")).thenReturn(entity);
    when(entity.hasProperty("organization")).thenReturn(true);
    try (MockedStatic<OBContext> ob = Mockito.mockStatic(OBContext.class);
        MockedStatic<ModelProvider> mp = Mockito.mockStatic(ModelProvider.class)) {
      ob.when(OBContext::getOBContext).thenReturn(obContext);
      mp.when(ModelProvider::getInstance).thenReturn(modelProvider);
      SelectorQueryBuilder.HqlWithParams order = SelectorOrgFilter.buildOrgProximityOrder("Calendar",
          "e", "CHILD");
      assertEquals("CHILD", order.getParams().get("selectorPrefOrg0"));
      assertEquals("PARENT", order.getParams().get("selectorPrefOrg1"));
      assertNull(SelectorOrgFilter.buildOrgProximityOrder("Calendar", "e", "0"));
    }
  }
}
