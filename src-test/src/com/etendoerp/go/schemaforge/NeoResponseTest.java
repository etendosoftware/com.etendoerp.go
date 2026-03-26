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

import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.junit.Test;

/**
 * Unit tests for {@link NeoResponse}.
 */
public class NeoResponseTest {

  @Test
  public void testOkFactoryMethod() {
    JSONObject data = new JSONObject();
    NeoResponse response = NeoResponse.ok(data);

    assertEquals(200, response.getHttpStatus());
    assertNotNull(response.getBody());
    assertTrue(response.getHeaders().isEmpty());
  }

  @Test
  public void testCreatedFactoryMethod() {
    JSONObject data = new JSONObject();
    NeoResponse response = NeoResponse.created(data);

    assertEquals(201, response.getHttpStatus());
    assertNotNull(response.getBody());
  }

  @Test
  public void testNoContentFactoryMethod() {
    NeoResponse response = NeoResponse.noContent();

    assertEquals(204, response.getHttpStatus());
    assertNull(response.getBody());
  }

  @Test
  public void testErrorFactoryMethod() throws JSONException {
    NeoResponse response = NeoResponse.error(404, "Not Found");

    assertEquals(404, response.getHttpStatus());
    assertNotNull(response.getBody());

    JSONObject error = response.getBody().getJSONObject("error");
    assertEquals("Not Found", error.getString("message"));
    assertEquals(404, error.getInt("status"));
  }

  @Test
  public void testErrorWithDifferentStatusCodes() throws JSONException {
    NeoResponse badRequest = NeoResponse.error(400, "Bad Request");
    assertEquals(400, badRequest.getHttpStatus());
    assertEquals("Bad Request", badRequest.getBody().getJSONObject("error").getString("message"));

    NeoResponse serverError = NeoResponse.error(500, "Internal Server Error");
    assertEquals(500, serverError.getHttpStatus());
    assertEquals("Internal Server Error",
        serverError.getBody().getJSONObject("error").getString("message"));

    NeoResponse unauthorized = NeoResponse.error(401, "Unauthorized");
    assertEquals(401, unauthorized.getHttpStatus());
  }

  @Test
  public void testWithHeader() {
    NeoResponse response = NeoResponse.ok(new JSONObject());
    NeoResponse result = response.withHeader("X-Custom", "value1");

    // Fluent API — returns the same instance
    assertEquals(response, result);
    assertEquals("value1", response.getHeaders().get("X-Custom"));
  }

  @Test
  public void testMultipleHeaders() {
    NeoResponse response = NeoResponse.ok(new JSONObject())
        .withHeader("X-One", "1")
        .withHeader("X-Two", "2")
        .withHeader("X-Three", "3");

    assertEquals(3, response.getHeaders().size());
    assertEquals("1", response.getHeaders().get("X-One"));
    assertEquals("2", response.getHeaders().get("X-Two"));
    assertEquals("3", response.getHeaders().get("X-Three"));
  }

  @Test
  public void testHeaderOverwrite() {
    NeoResponse response = NeoResponse.ok(new JSONObject())
        .withHeader("X-Key", "old")
        .withHeader("X-Key", "new");

    assertEquals(1, response.getHeaders().size());
    assertEquals("new", response.getHeaders().get("X-Key"));
  }

  @Test
  public void testConstructorDirectly() {
    JSONObject body = new JSONObject();
    NeoResponse response = new NeoResponse(202, body);

    assertEquals(202, response.getHttpStatus());
    assertEquals(body, response.getBody());
    assertNotNull(response.getHeaders());
    assertTrue(response.getHeaders().isEmpty());
  }

  @Test
  public void testOkWithNullBody() {
    NeoResponse response = NeoResponse.ok(null);
    assertEquals(200, response.getHttpStatus());
    assertNull(response.getBody());
  }
}
