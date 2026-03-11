package com.etendoerp.go.schemaforge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;

import org.junit.Before;
import org.junit.Test;

/**
 * Unit tests for {@link NeoServlet} tab filtering logic.
 *
 * Tests the wrapWithTabFilters method which injects tabId, windowId,
 * and parent entity filters into the request before delegating to
 * DataSourceServlet.
 *
 * Note: Tests that require a running DAL (e.g., verifying HQL where clause
 * application or parent property resolution) must be run as integration tests
 * extending OBBaseTest against a live Etendo instance.
 */
public class NeoServletTabFilterTest {

  private NeoServlet servlet;

  @Before
  public void setUp() {
    servlet = new NeoServlet();
  }

  /**
   * When NeoContext has no AD_Tab, wrapWithTabFilters should return the
   * original request unchanged (no wrapping).
   */
  @Test
  public void testWrapWithTabFiltersReturnsOriginalWhenNoTab() {
    NeoContext context = NeoContext.builder()
        .specName("testSpec")
        .entityName("Product")
        .httpMethod("GET")
        .queryParams(Collections.emptyMap())
        .build();

    // Use a minimal stub request
    HttpServletRequest stubRequest = new StubHttpServletRequest();
    HttpServletRequest result = servlet.wrapWithTabFilters(stubRequest, context);

    // Should return the same reference when there is no tab
    assertSame("Should return original request when adTab is null",
        stubRequest, result);
  }

  /**
   * When NeoContext has no AD_Tab and queryParams include parentId,
   * the wrapper should still return the original request.
   */
  @Test
  public void testWrapWithTabFiltersIgnoresParentIdWhenNoTab() {
    Map<String, String> params = new HashMap<>();
    params.put("parentId", "ABC123");

    NeoContext context = NeoContext.builder()
        .specName("testSpec")
        .entityName("OrderLine")
        .httpMethod("GET")
        .queryParams(params)
        .build();

    HttpServletRequest stubRequest = new StubHttpServletRequest();
    HttpServletRequest result = servlet.wrapWithTabFilters(stubRequest, context);

    assertSame("Should return original request when adTab is null",
        stubRequest, result);
  }

  /**
   * When NeoContext has null queryParams, wrapWithTabFilters should not
   * throw a NullPointerException.
   */
  @Test
  public void testWrapWithTabFiltersHandlesNullQueryParams() {
    NeoContext context = NeoContext.builder()
        .specName("testSpec")
        .entityName("Product")
        .httpMethod("GET")
        .queryParams(null)
        .build();

    HttpServletRequest stubRequest = new StubHttpServletRequest();
    HttpServletRequest result = servlet.wrapWithTabFilters(stubRequest, context);

    // Should return original (no tab set)
    assertSame(stubRequest, result);
  }

  /**
   * Verify that buildParentWhereClause returns null when called with a null tab.
   * This tests the null safety of the method.
   */
  @Test
  public void testBuildParentWhereClauseWithNullTab() {
    // buildParentWhereClause catches all exceptions and returns null
    String result = servlet.buildParentWhereClause(null, "ABC123");
    assertEquals("Should return null when tab is null", null, result);
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
