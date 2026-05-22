package com.etendoerp.go.schemaforge.selector.policy;

import java.util.List;
import java.util.Map;

import com.etendoerp.go.schemaforge.NeoResponse;
import com.etendoerp.go.schemaforge.selector.meta.SelectorMeta;

/** Registry for selector policies used by generic selector execution. */
public final class SelectorPolicyRegistry {
  private final List<SelectorContextPolicy> contextPolicies;
  private final List<SelectorEnrichmentPolicy> enrichmentPolicies;

  /**
   * Create a registry with the given policy lists.
   *
   * @param contextPolicies context-filter policies evaluated in order
   * @param enrichmentPolicies response-enrichment policies evaluated in order
   */
  public SelectorPolicyRegistry(List<SelectorContextPolicy> contextPolicies,
      List<SelectorEnrichmentPolicy> enrichmentPolicies) {
    this.contextPolicies = contextPolicies;
    this.enrichmentPolicies = enrichmentPolicies;
  }

  /**
   * Resolve the first non-null context filter produced by a matching policy.
   *
   * @param entityName target DAL entity name
   * @param contextParams validated selector context parameters
   * @param alias HQL alias used by the selector query
   * @return the first non-null filter returned by a matching policy, or {@code null}
   */
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

  /**
   * Run all matching enrichment policies against the selector response.
   *
   * @param response selector response to enrich
   * @param meta resolved selector metadata
   * @param contextParams validated selector context parameters
   * @return the selector response after applying all matching enrichment policies
   */
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
