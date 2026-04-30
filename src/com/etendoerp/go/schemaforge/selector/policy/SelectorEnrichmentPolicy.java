package com.etendoerp.go.schemaforge.selector.policy;

import java.util.Map;

import com.etendoerp.go.schemaforge.NeoResponse;
import com.etendoerp.go.schemaforge.selector.meta.SelectorMeta;

/** Contract for selector policies that enrich selector responses after query execution. */
public interface SelectorEnrichmentPolicy {
  boolean supports(SelectorMeta meta, Map<String, String> contextParams);

  NeoResponse enrich(NeoResponse response, SelectorMeta meta, Map<String, String> contextParams);
}
