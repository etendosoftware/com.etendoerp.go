package com.etendoerp.go.schemaforge.selector.policy;

import java.util.List;
import java.util.Map;

import com.etendoerp.go.schemaforge.NeoResponse;
import com.etendoerp.go.schemaforge.selector.meta.SelectorMeta;

/** Registry for selector policies used by generic selector execution. */
public final class SelectorPolicyRegistry {
  private final List<SelectorContextPolicy> contextPolicies;
  private final List<SelectorEnrichmentPolicy> enrichmentPolicies;

  public SelectorPolicyRegistry(List<SelectorContextPolicy> contextPolicies,
      List<SelectorEnrichmentPolicy> enrichmentPolicies) {
    this.contextPolicies = contextPolicies;
    this.enrichmentPolicies = enrichmentPolicies;
  }

  public String resolveContextFilter(String entityName,
      Map<String, String> contextParams, String alias) {
    if (contextPolicies == null) {
      return null;
    }
    for (SelectorContextPolicy policy : contextPolicies) {
      if (policy != null && policy.supports(entityName)) {
        String filter = policy.resolveFilter(entityName, contextParams, alias);
        if (filter != null) {
          return filter;
        }
      }
    }
    return null;
  }

  public NeoResponse enrichSelectorResult(NeoResponse response,
      SelectorMeta meta, Map<String, String> contextParams) {
    if (enrichmentPolicies == null) {
      return response;
    }
    NeoResponse current = response;
    for (SelectorEnrichmentPolicy policy : enrichmentPolicies) {
      if (policy != null && policy.supports(meta, contextParams)) {
        current = policy.enrich(current, meta, contextParams);
      }
    }
    return current;
  }
}
