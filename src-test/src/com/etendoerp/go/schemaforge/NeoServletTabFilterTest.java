package com.etendoerp.go.schemaforge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link NeoServlet} tab filtering logic.
 *
 * Note: Tests that require a running DAL (e.g., verifying HQL where clause
 * application or parent property resolution) must be run as integration tests
 * extending OBBaseTest against a live Etendo instance.
 */
public class NeoServletTabFilterTest {

  private NeoServlet servlet;

  @BeforeEach
  public void setUp() {
    servlet = new NeoServlet();
  }

  /**
   * buildParentWhereClause returns null when called with a null tab.
   * The method performs an explicit null check at the start and returns null immediately.
   */
  @Test
  public void testBuildParentWhereClauseWithNullTab() {
    String result = servlet.buildParentWhereClause(null, "ABC123");
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
