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

import static org.junit.Assert.assertNull;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.Test;

/**
 * Unit tests for {@link InternalConsumptionLineHandler}.
 *
 * <p>The handler is now an intentional no-op: warehouse-name enrichment for locator FKs is
 * handled generically for all windows by the shared selector and CRUD pipelines
 * ({@code NeoLocatorSelectorHelper} / {@code NeoLocatorIdentifierHelper}). These tests only
 * pin the no-op contract so the handler never double-rewrites or short-circuits the response.
 */
public class InternalConsumptionLineHandlerTest {

  private static final InternalConsumptionLineHandler HANDLER = new InternalConsumptionLineHandler();

  /**
   * handle() must always return null so the default CRUD path runs.
   */
  @Test
  public void testHandleAlwaysReturnsNull() {
    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.SELECTOR)
        .fieldName("M_Locator_ID")
        .build();
    assertNull(HANDLER.handle(ctx));
  }

  /**
   * handle() must return null even for CRUD endpoints.
   */
  @Test
  public void testHandleReturnsNullForCrudEndpoint() {
    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.CRUD)
        .httpMethod("GET")
        .build();
    assertNull(HANDLER.handle(ctx));
  }

  /**
   * afterHandle() must always return null (no-op) even for the storage-bin selector, since
   * label enrichment now happens generically upstream.
   */
  @Test
  public void testAfterHandleIsNoOpForStorageBinSelector() throws Exception {
    JSONArray items = new JSONArray();
    items.put(new JSONObject().put("id", "loc-1").put("label", "Bin-A"));
    JSONObject body = new JSONObject().put("items", items);

    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.SELECTOR)
        .fieldName("M_Locator_ID")
        .previousResult(NeoResponse.ok(body))
        .build();

    assertNull(HANDLER.afterHandle(ctx));
  }

  /**
   * afterHandle() must return null for any other endpoint/field too.
   */
  @Test
  public void testAfterHandleIsNoOpForOtherEndpoints() {
    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.CRUD)
        .fieldName("C_BPartner_ID")
        .build();
    assertNull(HANDLER.afterHandle(ctx));
  }
}
