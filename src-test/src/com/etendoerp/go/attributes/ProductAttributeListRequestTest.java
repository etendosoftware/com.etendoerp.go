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

package com.etendoerp.go.attributes;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

/**
 * Parsing and shape checks of the attribute part of a Product list request, and the HQL built from it (ETP-5977, H9).
 * Pure: definitions come from a stub, nothing touches the database or the session.
 *
 * @covers com.etendoerp.go.attributes.ProductAttributeListRequest
 * @covers com.etendoerp.go.attributes.ProductAttributeListHql
 */
public class ProductAttributeListRequestTest {

  private static final String NUM = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
  private static final String TXT = "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB";

  private static ProductAttributeListRequest.Definition stub(String id) {
    if (NUM.equals(id)) {
      return new ProductAttributeListRequest.Definition(NUM, true);
    }
    if (TXT.equals(id)) {
      return new ProductAttributeListRequest.Definition(TXT, false);
    }
    throw new IllegalArgumentException("unknown definition");
  }

  private static Map<String, String> params(String... kv) {
    Map<String, String> m = new HashMap<>();
    for (int i = 0; i < kv.length; i += 2) {
      m.put(kv[i], kv[i + 1]);
    }
    return m;
  }

  private static ProductAttributeListRequest parse(String... kv) {
    return ProductAttributeListRequest.parse(params(kv), ProductAttributeListRequestTest::stub);
  }

  private static void assertInvalid(String expected, String... kv) {
    try {
      parse(kv);
      fail("expected: " + expected);
    } catch (IllegalArgumentException e) {
      assertTrue(e.getMessage(), e.getMessage().contains(expected));
    }
  }

  @Test
  public void aRequestWithoutAttributeParametersIsNotTouched() {
    assertFalse(ProductAttributeListRequest.applies(null));
    assertFalse(ProductAttributeListRequest.applies(params("_startRow", "0", "_sortBy", "name")));
    assertTrue(ProductAttributeListRequest.applies(params("attrSortDef", NUM)));
    assertTrue(ProductAttributeListRequest.applies(params("attrFilterDef", NUM)));
  }

  @Test
  public void aNumericRangeIsParsedToDecimals() {
    ProductAttributeListRequest r = parse("attrFilterDef", NUM, "attrFilterMin", "100", "attrFilterMax", "110.5");
    assertEquals(new BigDecimal("100"), r.getMin());
    assertEquals(new BigDecimal("110.5"), r.getMax());
    assertNull(r.getPrefix());
  }

  @Test
  public void sortDefaultsToAscendingAndAcceptsDesc() {
    assertFalse(parse("attrSortDef", NUM).isDescending());
    assertTrue(parse("attrSortDef", NUM, "attrSortDir", "DESC").isDescending());
  }

  @Test
  public void malformedAndUnknownDefinitionsAreRefused() {
    assertInvalid("not a valid attribute definition id", "attrFilterDef", "' or 1=1 --", "attrFilterMin", "1");
    assertInvalid("not a valid attribute definition id", "attrSortDef", "");
    assertInvalid("unknown definition", "attrSortDef", "CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC");
  }

  @Test
  public void badValuesAreRefused() {
    assertInvalid("is not a number", "attrFilterDef", NUM, "attrFilterMin", "1;drop");
    assertInvalid("is not a number", "attrFilterDef", NUM, "attrFilterEq", "x");
    assertInvalid("must be asc or desc", "attrSortDef", NUM, "attrSortDir", "asc;drop");
  }

  @Test
  public void aFilterParameterOfTheWrongTypeIsRefused() {
    assertInvalid("text attributes only", "attrFilterDef", NUM, "attrFilterPrefix", "x");
    assertInvalid("numeric attributes only", "attrFilterDef", TXT, "attrFilterMin", "1");
  }

  @Test
  public void aFilterDefinitionWithoutAConditionIsRefused() {
    assertInvalid("needs attrFilterMin", "attrFilterDef", NUM);
  }

  @Test
  public void everyRequestValueIsABindParameterNotHql() {
    ProductAttributeListRequest r = parse("attrFilterDef", TXT, "attrFilterPrefix", "x' or '1'='1", "attrSortDef", NUM,
        "attrSortDir", "desc");
    ProductAttributeListHql hql = ProductAttributeListHql.build(r, "e.x = 1", List.of("C"), List.of("O"));
    assertFalse(hql.getIdsHql(), hql.getIdsHql().contains("x' or"));
    assertTrue(hql.getIdsHql(), hql.getIdsHql().contains("f.textValue like :fprefix escape '!'"));
    assertTrue(hql.getIdsHql(), hql.getIdsHql().contains("v.attributeDefinition.id = :sortDef"));
    assertTrue(hql.getIdsHql(), hql.getIdsHql().endsWith("order by v.numberValue desc nulls last, e.id"));
    assertEquals("x' or '1'='1%", hql.getIdsBinds().get("fprefix"));
    assertEquals(NUM, hql.getIdsBinds().get("sortDef"));
  }

  @Test
  public void theCountQueryHasTheFilterButNeitherJoinNorOrder() {
    ProductAttributeListRequest r = parse("attrFilterDef", NUM, "attrFilterMin", "1", "attrSortDef", NUM);
    ProductAttributeListHql hql = ProductAttributeListHql.build(r, null, List.of("C"), List.of("O"));
    assertTrue(hql.getCountHql(), hql.getCountHql().startsWith("select count(e.id) from Product e where "));
    assertTrue(hql.getCountHql(), hql.getCountHql().contains("f.numberValue >= :fmin"));
    assertFalse(hql.getCountHql(), hql.getCountHql().contains("left join"));
    assertFalse(hql.getCountHql(), hql.getCountHql().contains("order by"));
    assertFalse(hql.getCountBinds().containsKey("sortDef"));
  }

  @Test
  public void sessionTenantFiltersAndTheReadPredicateAreAlwaysApplied() {
    ProductAttributeListHql hql = ProductAttributeListHql.build(parse("attrSortDef", NUM), "e.y = 2", List.of("C"),
        List.of("O"));
    assertTrue(hql.getIdsHql(), hql.getIdsHql().contains("e.client.id in (:clients) and e.organization.id in (:orgs)"));
    assertTrue(hql.getIdsHql(), hql.getIdsHql().contains("(e.y = 2)"));
  }

  @Test
  public void likeWildcardsInAPrefixAreEscaped() {
    assertEquals("a!%b!_c!!", ProductAttributeListHql.escapeLike("a%b_c!"));
  }
}
