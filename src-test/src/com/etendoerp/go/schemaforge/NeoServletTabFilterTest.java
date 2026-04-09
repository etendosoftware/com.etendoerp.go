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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.etendoerp.go.schemaforge.util.NeoTypeCoercionHelper;

/**
 * Unit tests for {@link NeoServlet} tab filtering logic.
 *
 * Note: Tests that require a running DAL (e.g., verifying HQL where clause
 * application or parent property resolution) must be run as integration tests
 * extending OBBaseTest against a live Etendo instance.
 */
public class NeoServletTabFilterTest {

  /**
   * buildParentWhereClause returns null when called with a null tab.
   * The method performs an explicit null check at the start and returns null immediately.
   */
  @Test
  public void testBuildParentWhereClauseWithNullTab() {
    NeoTypeCoercionHelper.ParentFilter result = NeoTypeCoercionHelper.buildParentWhereClause(null, "ABC123");
    assertNull(result, "Should return null when tab is null");
  }

  /**
   * NeoContext builder properly stores queryParams including parentId.
   */
  @Test
  public void testContextQueryParamsContainParentId() {
    Map<String, String> params = new HashMap<>();
    params.put("parentId", "PARENT-UUID-123");
    params.put("_startRow", "0");
    params.put("_endRow", "50");

    NeoContext context = NeoContext.builder()
        .specName("sales")
        .entityName("OrderLine")
        .httpMethod("GET")
        .queryParams(params)
        .build();

    assertNotNull(context.getQueryParams());
    assertEquals("PARENT-UUID-123", context.getQueryParams().get("parentId"));
    assertEquals("0", context.getQueryParams().get("_startRow"));
    assertEquals("50", context.getQueryParams().get("_endRow"));
  }
}
