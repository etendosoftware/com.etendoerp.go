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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import java.util.Map;

import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.Test;

import com.etendoerp.go.schemaforge.selector.meta.SelectorMeta;
import com.etendoerp.go.schemaforge.selector.policy.SelectorContextPolicy;
import com.etendoerp.go.schemaforge.selector.policy.SelectorEnrichmentPolicy;
import com.etendoerp.go.schemaforge.selector.policy.SelectorPolicyRegistry;

class SelectorPolicyRegistryTest {

  @Test
  void testResolveContextFilterReturnsMatchingPolicyResult() {
    SelectorPolicyRegistry registry = new SelectorPolicyRegistry(
        List.of(
            new StubContextPolicy("BusinessPartner", "bp.customer = true"),
            new StubContextPolicy("Product", "prod.active = true")),
        List.of());

    assertEquals("bp.customer = true",
        registry.resolveContextFilter("BusinessPartner", Map.of("isCustomer", "Y"), "bp"));
  }

  @Test
  void testResolveContextFilterReturnsNullWhenNoPolicyMatches() {
    SelectorPolicyRegistry registry = new SelectorPolicyRegistry(
        List.of(new StubContextPolicy("BusinessPartner", "bp.customer = true")),
        List.of());

    assertNull(registry.resolveContextFilter("Order", Map.of(), "o"));
  }

  @Test
  void testEnrichSelectorResultAppliesMatchingPolicy() throws Exception {
    SelectorMeta meta = new SelectorMeta("Product", "name", null);
    JSONObject body = new JSONObject();
    body.put("items", new org.codehaus.jettison.json.JSONArray());
    NeoResponse response = NeoResponse.ok(body);

    SelectorPolicyRegistry registry = new SelectorPolicyRegistry(
        List.of(),
        List.of(new StubEnrichmentPolicy("Product")));

    NeoResponse enriched = registry.enrichSelectorResult(response, meta, Map.of("priceList", "PL"));

    assertSame(response, enriched);
    assertEquals(true, enriched.getBody().getBoolean("enriched"));
  }

  @Test
  void testEnrichSelectorResultReturnsOriginalWhenNoPolicyMatches() throws Exception {
    SelectorMeta meta = new SelectorMeta("Order", "documentNo", null);
    JSONObject body = new JSONObject();
    NeoResponse response = NeoResponse.ok(body);

    SelectorPolicyRegistry registry = new SelectorPolicyRegistry(
        List.of(),
        List.of(new StubEnrichmentPolicy("Product")));

    assertSame(response, registry.enrichSelectorResult(response, meta, Map.of()));
  }

  private static final class StubContextPolicy implements SelectorContextPolicy {
    private final String supportedEntity;
    private final String filter;

    private StubContextPolicy(String supportedEntity, String filter) {
      this.supportedEntity = supportedEntity;
      this.filter = filter;
    }

    @Override
    public boolean supports(String entityName) {
      return supportedEntity.equals(entityName);
    }

    @Override
    public String resolveFilter(String entityName, Map<String, String> contextParams, String alias) {
      return filter;
    }
  }

  private static final class StubEnrichmentPolicy implements SelectorEnrichmentPolicy {
    private final String supportedEntity;

    private StubEnrichmentPolicy(String supportedEntity) {
      this.supportedEntity = supportedEntity;
    }

    @Override
    public boolean supports(SelectorMeta meta, Map<String, String> contextParams) {
      return supportedEntity.equals(meta.entityName);
    }

    @Override
    public NeoResponse enrich(NeoResponse response, SelectorMeta meta, Map<String, String> contextParams) {
      try {
        response.getBody().put("enriched", true);
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
      return response;
    }
  }
}
