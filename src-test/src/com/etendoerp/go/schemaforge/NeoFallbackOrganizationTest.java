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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.ModelProvider;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.security.OrganizationStructureProvider;
import org.openbravo.model.ad.datamodel.Column;
import org.openbravo.model.ad.ui.Tab;
import org.openbravo.model.common.enterprise.Organization;

import com.etendoerp.go.schemaforge.data.SFEntity;
import com.etendoerp.go.schemaforge.selector.meta.SelectorContextResolver;

/**
 * ETP-5086: organization context and org-proximity ordering of the combo first-option fallback.
 * The organization follows Classic's {@code AD_Org_ID} resolution: the record's own, else the
 * parent record's, else the session's.
 *
 * @covers com.etendoerp.go.schemaforge.OrgPreference
 */
public class NeoFallbackOrganizationTest {

  private static final String AD_ORG_ID = "AD_Org_ID";
  private static final SFEntity CHILD_ENTITY = mock(SFEntity.class);

  private static NeoContext ctx(String bodyOrg, String queryParentId, String sessionOrg)
      throws Exception {
    NeoContext ctx = mock(NeoContext.class);
    JSONObject body = new JSONObject();
    if (bodyOrg != null) {
      body.put("organization", bodyOrg);
    }
    when(ctx.getRequestBody()).thenReturn(body);
    Map<String, String> query = new HashMap<>();
    if (queryParentId != null) {
      query.put("parentId", queryParentId);
    }
    when(ctx.getQueryParams()).thenReturn(query);
    when(ctx.getSfEntity()).thenReturn(CHILD_ENTITY);
    OBContext obContext = mock(OBContext.class);
    if (sessionOrg != null) {
      Organization org = mock(Organization.class);
      when(org.getId()).thenReturn(sessionOrg);
      when(obContext.getCurrentOrganization()).thenReturn(org);
    }
    when(ctx.getObContext()).thenReturn(obContext);
    return ctx;
  }

  /** Runs {@code putFallbackOrganization} with the parent record {@code P1} in {@code parentOrg}. */
  private static Map<String, String> fallbackParams(NeoContext ctx, String parentId,
      String parentOrg) {
    Map<String, String> params = new HashMap<>();
    try (MockedStatic<SelectorContextResolver> resolver =
        Mockito.mockStatic(SelectorContextResolver.class)) {
      resolver.when(() -> SelectorContextResolver.resolveOrgFromParentRecord(CHILD_ENTITY, "P1"))
          .thenReturn(parentOrg);
      OrgPreference.putFallbackOrganization(params, ctx, parentId);
    }
    return params;
  }

  @Test
  public void recordOrgWinsOverParentAndSession() throws Exception {
    Map<String, String> params = fallbackParams(ctx("ORG1", "P1", "SESSION"), "P1", "PARENT_ORG");
    assertEquals("ORG1", params.get(AD_ORG_ID));
    assertEquals("Y", params.get(OrgPreference.PARAM_PREFER_CONTEXT_ORG));
  }

  @Test
  public void parentOrgIsUsedWithoutRecordOrg() throws Exception {
    Map<String, String> params = fallbackParams(ctx(null, null, "SESSION"), "P1", "PARENT_ORG");
    assertEquals("PARENT_ORG", params.get(AD_ORG_ID));
    assertEquals("Y", params.get(OrgPreference.PARAM_PREFER_CONTEXT_ORG));
    assertFalse("the parent is resolved here, not forwarded to the selector",
        params.containsKey("parentId"));
  }

  @Test
  public void parentIdFromQueryParamsIsUsedWhenNotPassed() throws Exception {
    Map<String, String> params = fallbackParams(ctx(null, "P1", "SESSION"), null, "PARENT_ORG");
    assertEquals("PARENT_ORG", params.get(AD_ORG_ID));
  }

  @Test
  public void sessionOrgIsUsedWithoutRecordOrParent() throws Exception {
    Map<String, String> params = fallbackParams(ctx(null, null, "SESSION"), null, null);
    assertEquals("SESSION", params.get(AD_ORG_ID));
    assertEquals("Y", params.get(OrgPreference.PARAM_PREFER_CONTEXT_ORG));
  }

  @Test
  public void sessionOrgIsUsedWhenTheParentOrgCannotBeResolved() throws Exception {
    Map<String, String> params = fallbackParams(ctx(null, null, "SESSION"), "P1", null);
    assertEquals("SESSION", params.get(AD_ORG_ID));
  }

  @Test
  public void recordInOrgStarAddsNoRestriction() throws Exception {
    Map<String, String> params = fallbackParams(ctx("0", null, "SESSION"), "P1", "PARENT_ORG");
    assertTrue("org * has every org in its natural tree, as in Classic", params.isEmpty());
  }

  @Test
  public void parentInOrgStarAddsNoRestriction() throws Exception {
    Map<String, String> params = fallbackParams(ctx(null, null, "SESSION"), "P1", "0");
    assertTrue(params.isEmpty());
  }

  @Test
  public void noOrganizationAnywhereAddsNothing() throws Exception {
    Map<String, String> params = fallbackParams(ctx(null, null, null), null, null);
    assertTrue(params.isEmpty());
  }

  @Test
  public void existingAdOrgIdIsNotOverridden() throws Exception {
    Map<String, String> params = new HashMap<>();
    params.put(AD_ORG_ID, "CALLER");
    OrgPreference.putFallbackOrganization(params, ctx("ORG1", "P1", "SESSION"), "P1");
    assertEquals("CALLER", params.get(AD_ORG_ID));
    assertFalse(params.containsKey(OrgPreference.PARAM_PREFER_CONTEXT_ORG));
  }

  /**
   * The fallback really reaches the selector with the parent's organization: a child record created
   * without an organization must not pick among every readable organization.
   */
  @Test
  @SuppressWarnings("unchecked")
  public void firstComboOptionQueriesTheSelectorWithTheParentOrganization() throws Exception {
    Column col = mock(Column.class);
    when(col.getDBColumnName()).thenReturn("C_Calendar_ID");
    NeoContext ctx = ctx(null, null, "SESSION");
    JSONObject body = new JSONObject();
    body.put("items", new JSONArray().put(new JSONObject().put("id", "CAL1")));
    NeoResponse ok = mock(NeoResponse.class);
    when(ok.getHttpStatus()).thenReturn(200);
    when(ok.getBody()).thenReturn(body);
    ArgumentCaptor<Map<String, String>> sent = ArgumentCaptor.forClass(Map.class);
    try (MockedStatic<NeoSelectorService> selector = Mockito.mockStatic(NeoSelectorService.class);
        MockedStatic<SelectorContextResolver> resolver =
            Mockito.mockStatic(SelectorContextResolver.class)) {
      selector.when(() -> NeoSelectorService.getBaseReferenceId(col))
          .thenReturn(NeoSelectorService.REF_TABLEDIR);
      selector.when(() -> NeoSelectorService.hasObuiselSelector(col)).thenReturn(false);
      selector.when(() -> NeoSelectorService.querySelectorByColumn(eq(col), anyString(), isNull(),
          eq(1), eq(0), sent.capture())).thenReturn(ok);
      resolver.when(() -> SelectorContextResolver.resolveOrgFromParentRecord(any(), eq("P1")))
          .thenReturn("PARENT_ORG");

      assertEquals("CAL1", NeoDefaultsService.resolveFirstComboOption(col, ctx, "P1"));
    }
    assertEquals("PARENT_ORG", sent.getValue().get(AD_ORG_ID));
    assertEquals("Y", sent.getValue().get(OrgPreference.PARAM_PREFER_CONTEXT_ORG));
  }

  /** Organization default of a new record in a tab of {@code tabLevel}, created under {@code P1}. */
  private static Object organizationDefault(long tabLevel, String defaultExpr, String parentId)
      throws Exception {
    Column orgColumn = mock(Column.class);
    when(orgColumn.getDBColumnName()).thenReturn(AD_ORG_ID);
    Tab tab = mock(Tab.class);
    when(tab.getTabLevel()).thenReturn(tabLevel);
    NeoContext ctx = ctx(null, null, "SESSION");
    when(ctx.getAdTab()).thenReturn(tab);
    NeoDefaultsService.FieldDefaultRequest request = new NeoDefaultsService.FieldDefaultRequest(
        orgColumn, parentId, null, null, null, ctx);
    Method method = NeoDefaultsService.class.getDeclaredMethod("resolveParentOrganizationDefault",
        NeoDefaultsService.FieldDefaultRequest.class, String.class);
    method.setAccessible(true);
    try (MockedStatic<SelectorContextResolver> resolver =
        Mockito.mockStatic(SelectorContextResolver.class)) {
      resolver.when(() -> SelectorContextResolver.resolveOrgFromParentRecord(CHILD_ENTITY, "P1"))
          .thenReturn("PARENT_ORG");
      return method.invoke(null, request, defaultExpr);
    }
  }

  @Test
  public void childRecordInheritsTheParentOrganizationLikeClassic() throws Exception {
    assertEquals("PARENT_ORG", organizationDefault(1L, "@AD_Org_ID@", "P1"));
    assertEquals("the token is case-insensitive, as AD stores both spellings", "PARENT_ORG",
        organizationDefault(2L, "@AD_ORG_ID@", "P1"));
  }

  @Test
  public void sessionOrganizationTokenIsNotInherited() throws Exception {
    assertNull(organizationDefault(1L, "@#AD_Org_ID@", "P1"));
  }

  @Test
  public void headerRecordDoesNotInheritAnOrganization() throws Exception {
    assertNull(organizationDefault(0L, "@AD_Org_ID@", "P1"));
  }

  @Test
  public void childRecordWithoutParentKeepsTheSessionDefault() throws Exception {
    assertNull(organizationDefault(1L, "@AD_Org_ID@", null));
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
    String withOrg = OrgPreference.orderBy(order, "e", "name");
    assertTrue(withOrg, withOrg.startsWith(" ORDER BY " + order.getHql() + ", e.name, e.id"));
  }

  @Test
  public void orderByIsUnchangedWithoutPreference() {
    assertEquals(" ORDER BY e.name, e.id", OrgPreference.orderBy(null, "e", "name"));
  }

  @Test
  public void selectorServiceForwardsPreferenceOnlyWithFlagAndOrg() {
    Map<String, Object> filter = new HashMap<>();
    Map<String, String> flagged = new HashMap<>();
    flagged.put(OrgPreference.PARAM_PREFER_CONTEXT_ORG, "Y");
    OrgPreference.putOrgPreference(filter, flagged, "ORG1");
    assertEquals("ORG1", filter.get(OrgPreference.PARAM_PREFER_ORG));

    Map<String, Object> noOrg = new HashMap<>();
    OrgPreference.putOrgPreference(noOrg, flagged, null);
    assertTrue(noOrg.isEmpty());

    Map<String, Object> noFlag = new HashMap<>();
    OrgPreference.putOrgPreference(noFlag, new HashMap<>(), "ORG1");
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
