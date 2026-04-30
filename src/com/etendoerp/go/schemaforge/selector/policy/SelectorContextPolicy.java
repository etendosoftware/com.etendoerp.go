package com.etendoerp.go.schemaforge.selector.policy;

import java.util.Map;

/** Contract for selector policies that derive HQL filters from validated context parameters. */
public interface SelectorContextPolicy {
  boolean supports(String entityName);

  String resolveFilter(String entityName, Map<String, String> contextParams, String alias);
}
