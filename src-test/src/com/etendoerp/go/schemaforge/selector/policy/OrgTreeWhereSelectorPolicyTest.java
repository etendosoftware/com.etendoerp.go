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

package com.etendoerp.go.schemaforge.selector.policy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

/**
 * Unit tests for {@link OrgTreeWhereSelectorPolicy}, driven through its injectable resolver so no
 * database is needed.
 *
 * @covers com.etendoerp.go.schemaforge.selector.policy.OrgTreeWhereSelectorPolicy
 */
public class OrgTreeWhereSelectorPolicyTest {

  private static final String ENTITY = "ProductByPriceAndWarehouse";
  private static final String WH_FN =
      "AD_ORG_ISINNATURALTREE(e.orgwarehouse, :obuiselParam0, :obuiselParam1)='Y'";
  private static final String ORG_FN =
      "AD_ORG_ISINNATURALTREE(e.productPrice.organization.id, :obuiselParam2, :obuiselParam3)='Y'";
  private static final String REAL_CLAUSE = "e.active='Y' AND (" + WH_FN
      + ") AND e.productPrice.priceListVersion.active='Y' AND (" + ORG_FN + ")";

  /** Records the arguments it is called with and answers fixed lists. */
  private static final class FakeResolver implements OrgTreeWhereSelectorPolicy.OrgTreeResolver {
    List<String> warehouses = Arrays.asList("W1", "W2");
    List<String> orgs = Arrays.asList("O1", "O2", "O3");
    final List<String> warehouseCalls = new ArrayList<>();
    final List<String> orgCalls = new ArrayList<>();

    @Override
    public List<String> warehouseIdsInTree(String orgId, String clientId) {
      warehouseCalls.add(orgId + "|" + clientId);
      return warehouses;
    }

    @Override
    public List<String> orgIdsInTree(String orgId, String clientId) {
      orgCalls.add(orgId + "|" + clientId);
      return orgs;
    }
  }

  private static Map<String, Object> realParams() {
    Map<String, Object> params = new HashMap<>();
    params.put("obuiselParam0", "ORG-A");
    params.put("obuiselParam1", "CLIENT-A");
    params.put("obuiselParam2", "ORG-B");
    params.put("obuiselParam3", "CLIENT-B");
    return params;
  }

  @Test
  public void rewritesBothPredicatesOfTheRealClause() {
    FakeResolver resolver = new FakeResolver();

    OrgTreeWhereSelectorPolicy.Rewrite rewrite = OrgTreeWhereSelectorPolicy.rewrite(ENTITY, "e",
        REAL_CLAUSE, realParams(), resolver);

    assertEquals("e.active='Y' AND (e.warehouse.id in (:sfOrgTreeWarehouseIds0))"
        + " AND e.productPrice.priceListVersion.active='Y'"
        + " AND (e.productPrice.organization.id in (:sfOrgTreeOrgIds0))", rewrite.getHql());
    Map<String, Object> params = rewrite.getParams();
    assertEquals(2, params.size());
    assertEquals(Arrays.asList("W1", "W2"), params.get("sfOrgTreeWarehouseIds0"));
    assertEquals(Arrays.asList("O1", "O2", "O3"), params.get("sfOrgTreeOrgIds0"));
    assertFalse(params.containsKey("obuiselParam0"));
    assertFalse(params.containsKey("obuiselParam3"));
  }

  @Test
  public void resolverReceivesTheOrgAndClientBoundToEachPredicate() {
    FakeResolver resolver = new FakeResolver();

    OrgTreeWhereSelectorPolicy.rewrite(ENTITY, "e", REAL_CLAUSE, realParams(), resolver);

    assertEquals(Collections.singletonList("ORG-A|CLIENT-A"), resolver.warehouseCalls);
    assertEquals(Collections.singletonList("ORG-B|CLIENT-B"), resolver.orgCalls);
  }

  @Test
  public void otherEntityIsNotRewritten() {
    assertNull(OrgTreeWhereSelectorPolicy.rewrite("Product", "e", REAL_CLAUSE, realParams(),
        new FakeResolver()));
  }

  @Test
  public void clauseWithoutTheFunctionBlankHqlOrNullParamsIsNotRewritten() {
    FakeResolver resolver = new FakeResolver();

    assertNull(OrgTreeWhereSelectorPolicy.rewrite(ENTITY, "e", "e.active='Y'", realParams(),
        resolver));
    assertNull(OrgTreeWhereSelectorPolicy.rewrite(ENTITY, "e", "  ", realParams(), resolver));
    assertNull(OrgTreeWhereSelectorPolicy.rewrite(ENTITY, "e", null, realParams(), resolver));
    assertNull(OrgTreeWhereSelectorPolicy.rewrite(ENTITY, "e", REAL_CLAUSE, null, resolver));
    assertTrue(resolver.warehouseCalls.isEmpty());
    assertTrue(resolver.orgCalls.isEmpty());
  }

  @Test
  public void emptyResolvedListBecomesAlwaysFalseWithoutAListParam() {
    FakeResolver resolver = new FakeResolver();
    resolver.warehouses = Collections.emptyList();

    OrgTreeWhereSelectorPolicy.Rewrite rewrite = OrgTreeWhereSelectorPolicy.rewrite(ENTITY, "e",
        REAL_CLAUSE, realParams(), resolver);

    assertTrue(rewrite.getHql().contains("(1 = 0)"));
    assertTrue(rewrite.getHql().contains("e.productPrice.organization.id in (:sfOrgTreeOrgIds0)"));
    assertFalse(rewrite.getParams().containsKey("sfOrgTreeWarehouseIds0"));
    assertTrue(rewrite.getParams().containsKey("sfOrgTreeOrgIds0"));
  }

  @Test
  public void predicateWithMissingParamIsLeftUntouched() {
    Map<String, Object> params = realParams();
    params.remove("obuiselParam1");

    OrgTreeWhereSelectorPolicy.Rewrite rewrite = OrgTreeWhereSelectorPolicy.rewrite(ENTITY, "e",
        REAL_CLAUSE, params, new FakeResolver());

    assertTrue(rewrite.getHql().contains(WH_FN));
    assertTrue(rewrite.getHql().contains("e.productPrice.organization.id in (:sfOrgTreeOrgIds0)"));
    assertTrue(rewrite.getParams().containsKey("obuiselParam0"));
    assertFalse(rewrite.getParams().containsKey("obuiselParam2"));
  }

  @Test
  public void predicateWithNonStringParamIsLeftUntouched() {
    Map<String, Object> params = realParams();
    params.put("obuiselParam0", 42);
    FakeResolver resolver = new FakeResolver();

    OrgTreeWhereSelectorPolicy.Rewrite rewrite = OrgTreeWhereSelectorPolicy.rewrite(ENTITY, "e",
        REAL_CLAUSE, params, resolver);

    assertTrue(rewrite.getHql().contains(WH_FN));
    assertTrue(resolver.warehouseCalls.isEmpty());
    assertEquals(42, rewrite.getParams().get("obuiselParam0"));
  }

  @Test
  public void nothingRewrittenWhenEveryPredicateIsUnreadable() {
    assertNull(OrgTreeWhereSelectorPolicy.rewrite(ENTITY, "e", REAL_CLAUSE, new HashMap<>(),
        new FakeResolver()));
  }

  @Test
  public void parameterStillUsedElsewhereInTheClauseIsKept() {
    String hql = REAL_CLAUSE + " AND e.client.id = :obuiselParam1";

    OrgTreeWhereSelectorPolicy.Rewrite rewrite = OrgTreeWhereSelectorPolicy.rewrite(ENTITY, "e",
        hql, realParams(), new FakeResolver());

    assertTrue(rewrite.getParams().containsKey("obuiselParam1"));
    assertFalse(rewrite.getParams().containsKey("obuiselParam0"));
    assertFalse(rewrite.getParams().containsKey("obuiselParam2"));
  }

  @Test
  public void functionNameIsCaseInsensitiveAndWhitespaceTolerant() {
    String hql = "ad_org_isinnaturaltree(  e.orgwarehouse ,:obuiselParam0 ,  :obuiselParam1 )  =  'Y'";

    OrgTreeWhereSelectorPolicy.Rewrite rewrite = OrgTreeWhereSelectorPolicy.rewrite(ENTITY, "e",
        hql, realParams(), new FakeResolver());

    assertEquals("e.warehouse.id in (:sfOrgTreeWarehouseIds0)", rewrite.getHql());
  }

  @Test
  public void aliasOtherThanEIsHonoured() {
    String hql = "AD_ORG_ISINNATURALTREE(x.orgwarehouse, :obuiselParam0, :obuiselParam1)='Y'"
        + " AND AD_ORG_ISINNATURALTREE(x.productPrice.organization.id, :obuiselParam2,"
        + " :obuiselParam3)='Y'";

    OrgTreeWhereSelectorPolicy.Rewrite rewrite = OrgTreeWhereSelectorPolicy.rewrite(ENTITY, "x",
        hql, realParams(), new FakeResolver());

    assertEquals("x.warehouse.id in (:sfOrgTreeWarehouseIds0)"
        + " AND x.productPrice.organization.id in (:sfOrgTreeOrgIds0)", rewrite.getHql());
    // The same clause written over alias "e" does not match when the alias is "x".
    assertNull(OrgTreeWhereSelectorPolicy.rewrite(ENTITY, "x", REAL_CLAUSE, realParams(),
        new FakeResolver()));
  }
}
