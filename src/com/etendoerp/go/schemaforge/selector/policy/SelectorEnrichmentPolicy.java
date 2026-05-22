package com.etendoerp.go.schemaforge.selector.policy;

import java.util.Map;

import com.etendoerp.go.schemaforge.NeoResponse;
import com.etendoerp.go.schemaforge.selector.meta.SelectorMeta;

/** Contract for selector policies that enrich selector responses after query execution. */
public interface SelectorEnrichmentPolicy {
  /**
   * Determine whether this policy should enrich the selector result.
   *
   * @param meta resolved selector metadata
   * @param contextParams validated selector context parameters
   * @return {@code true} when the policy should run
   */
  boolean supports(SelectorMeta meta, Map<String, String> contextParams);

  /**
   * Enrich the selector response.
   *
   * @param response selector response to enrich
   * @param meta resolved selector metadata
   * @param contextParams validated selector context parameters
   * @return the enriched response
   */
  NeoResponse enrich(NeoResponse response, SelectorMeta meta, Map<String, String> contextParams);
}
