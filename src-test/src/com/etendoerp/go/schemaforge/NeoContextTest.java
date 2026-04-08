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

  private static final String ENTITY = "Entity";

  @Test
  public void testBuilderWithAllFields() throws JSONException {
    JSONObject body = new JSONObject();
    body.put("name", "Test");

    Map<String, String> params = new HashMap<>();
    params.put("page", "1");
    params.put("size", "10");

    NeoContext ctx = NeoContext.builder()
        .specName(TEST_SPEC)
        .entityName(PRODUCT)
        .httpMethod("GET")
        .recordId(ABC123)
        .requestBody(body)
        .queryParams(params)
        .build();

      assertEquals(TEST_SPEC, ctx.getSpecName());
      assertEquals(PRODUCT, ctx.getEntityName());
    assertEquals("GET", ctx.getHttpMethod());
      assertEquals(ABC123, ctx.getRecordId());
    assertEquals(body, ctx.getRequestBody());
    assertEquals(params, ctx.getQueryParams());
    assertNull(ctx.getAdTab());
    assertNull(ctx.getObContext());
    assertNull(ctx.getPreviousResult());
  }

  @Test
  public void testBuilderWithMinimalFields() {
    NeoContext ctx = NeoContext.builder()
        .specName(SPEC_1)
        .entityName(ORDER)
        .httpMethod("POST")
        .build();

      assertEquals(SPEC_1, ctx.getSpecName());
      assertEquals(ORDER, ctx.getEntityName());
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
         .specName(MY_SPEC)
         .entityName(INVOICE)
        .httpMethod("PUT")
         .recordId(RECORD_ID)
        .build();

    String str = ctx.toString();
        assertTrue(str.contains(MY_SPEC));
        assertTrue(str.contains(INVOICE));
    assertTrue(str.contains("PUT"));
        assertTrue(str.contains(RECORD_ID));
  }

  @Test
  public void testToStringWithNullId() {
    NeoContext ctx = NeoContext.builder()
        .specName("Spec")
      .entityName(ENTITY)
        .httpMethod("DELETE")
        .build();

    String str = ctx.toString();
    assertTrue(str.contains("null"));
  }

  @Test
  public void testEmptyQueryParams() {
    NeoContext ctx = NeoContext.builder()
        .specName("Spec")
      .entityName(ENTITY)
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
          .entityName(ENTITY)
          .httpMethod(method)
          .build();
      assertEquals(method, ctx.getHttpMethod());
    }
  }

  @Test
  public void testEndpointTypeViaBuilder() {
    NeoContext ctx = NeoContext.builder()
        .specName("Spec")
      .entityName(ENTITY)
        .httpMethod("POST")
        .endpointType(NeoEndpointType.CALLOUT)
        .build();
    assertEquals(NeoEndpointType.CALLOUT, ctx.getEndpointType());
  }

  @Test
  public void testFieldNameViaBuilder() {
    NeoContext ctx = NeoContext.builder()
        .specName("Spec")
      .entityName(ENTITY)
        .httpMethod("GET")
        .fieldName("warehouse")
        .build();
    assertEquals("warehouse", ctx.getFieldName());
  }

  @Test
  public void testEndpointTypeDefaultsToNull() {
    NeoContext ctx = NeoContext.builder()
        .specName("Spec")
      .entityName(ENTITY)
        .httpMethod("GET")
        .build();
    assertNull(ctx.getEndpointType());
  }

  @Test
  public void testFieldNameDefaultsToNull() {
    NeoContext ctx = NeoContext.builder()
        .specName("Spec")
      .entityName(ENTITY)
        .httpMethod("GET")
        .build();
    assertNull(ctx.getFieldName());
  }

  @Test
  public void testEndpointTypeEnumValues() {
    NeoEndpointType[] values = NeoEndpointType.values();
    assertEquals(6, values.length);
    assertEquals(NeoEndpointType.CRUD, NeoEndpointType.valueOf("CRUD"));
    assertEquals(NeoEndpointType.SELECTOR, NeoEndpointType.valueOf("SELECTOR"));
    assertEquals(NeoEndpointType.ACTION, NeoEndpointType.valueOf("ACTION"));
    assertEquals(NeoEndpointType.EVALUATE_DISPLAY, NeoEndpointType.valueOf("EVALUATE_DISPLAY"));
    assertEquals(NeoEndpointType.CALLOUT, NeoEndpointType.valueOf("CALLOUT"));
    assertEquals(NeoEndpointType.DEFAULTS, NeoEndpointType.valueOf("DEFAULTS"));
  }

  @Test
  public void testNeoHandlerAfterHandleDefault() {
    NeoHandler handler = new NeoHandler() {
      @Override
      public NeoResponse handle(NeoContext context) {
        return null;
      }
    };
    NeoContext ctx = NeoContext.builder()
        .specName("Spec")
      .entityName(ENTITY)
        .httpMethod("GET")
        .build();
    assertNull(handler.afterHandle(ctx));
  }

  @Test
  public void testToStringIncludesEndpointType() {
    NeoContext ctx = NeoContext.builder()
        .specName("Spec")
      .entityName(ENTITY)
        .httpMethod("GET")
        .endpointType(NeoEndpointType.SELECTOR)
        .build();
    String str = ctx.toString();
    assertTrue(str.contains("SELECTOR"));
  }
}
