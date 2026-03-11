package com.etendoerp.go.schemaforge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.junit.Test;

/**
 * Unit tests for {@link NeoContext} builder and getters.
 */
public class NeoContextTest {

  @Test
  public void testBuilderWithAllFields() throws JSONException {
    JSONObject body = new JSONObject();
    body.put("name", "Test");

    Map<String, String> params = new HashMap<>();
    params.put("page", "1");
    params.put("size", "10");

    NeoContext ctx = NeoContext.builder()
        .specName("TestSpec")
        .entityName("Product")
        .httpMethod("GET")
        .recordId("ABC123")
        .requestBody(body)
        .queryParams(params)
        .build();

    assertEquals("TestSpec", ctx.getSpecName());
    assertEquals("Product", ctx.getEntityName());
    assertEquals("GET", ctx.getHttpMethod());
    assertEquals("ABC123", ctx.getRecordId());
    assertEquals(body, ctx.getRequestBody());
    assertEquals(params, ctx.getQueryParams());
    assertNull(ctx.getAdTab());
    assertNull(ctx.getObContext());
    assertNull(ctx.getPreviousResult());
  }

  @Test
  public void testBuilderWithMinimalFields() {
    NeoContext ctx = NeoContext.builder()
        .specName("Spec1")
        .entityName("Order")
        .httpMethod("POST")
        .build();

    assertEquals("Spec1", ctx.getSpecName());
    assertEquals("Order", ctx.getEntityName());
    assertEquals("POST", ctx.getHttpMethod());
    assertNull(ctx.getRecordId());
    assertNull(ctx.getRequestBody());
    assertNull(ctx.getQueryParams());
  }

  @Test
  public void testSetPreviousResult() {
    NeoContext ctx = NeoContext.builder()
        .specName("Spec1")
        .entityName("Order")
        .httpMethod("GET")
        .build();

    assertNull(ctx.getPreviousResult());

    NeoResponse prevResult = NeoResponse.ok(new JSONObject());
    ctx.setPreviousResult(prevResult);

    assertEquals(prevResult, ctx.getPreviousResult());
  }

  @Test
  public void testPreviousResultViaBuilder() {
    NeoResponse prevResult = NeoResponse.ok(new JSONObject());
    NeoContext ctx = NeoContext.builder()
        .specName("Spec1")
        .entityName("Order")
        .httpMethod("POST")
        .previousResult(prevResult)
        .build();

    assertEquals(prevResult, ctx.getPreviousResult());
  }

  @Test
  public void testToString() {
    NeoContext ctx = NeoContext.builder()
        .specName("MySpec")
        .entityName("Invoice")
        .httpMethod("PUT")
        .recordId("ID42")
        .build();

    String str = ctx.toString();
    assertTrue(str.contains("MySpec"));
    assertTrue(str.contains("Invoice"));
    assertTrue(str.contains("PUT"));
    assertTrue(str.contains("ID42"));
  }

  @Test
  public void testToStringWithNullId() {
    NeoContext ctx = NeoContext.builder()
        .specName("Spec")
        .entityName("Entity")
        .httpMethod("DELETE")
        .build();

    String str = ctx.toString();
    assertTrue(str.contains("null"));
  }

  @Test
  public void testEmptyQueryParams() {
    NeoContext ctx = NeoContext.builder()
        .specName("Spec")
        .entityName("Entity")
        .httpMethod("GET")
        .queryParams(Collections.emptyMap())
        .build();

    assertNotNull(ctx.getQueryParams());
    assertTrue(ctx.getQueryParams().isEmpty());
  }

  @Test
  public void testAllHttpMethods() {
    String[] methods = {"GET", "POST", "PUT", "PATCH", "DELETE"};
    for (String method : methods) {
      NeoContext ctx = NeoContext.builder()
          .specName("Spec")
          .entityName("Entity")
          .httpMethod(method)
          .build();
      assertEquals(method, ctx.getHttpMethod());
    }
  }
}
