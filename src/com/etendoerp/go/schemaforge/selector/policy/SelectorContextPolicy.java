package com.etendoerp.go.schemaforge.selector.policy;

import java.util.Map;

/** Contract for selector policies that derive HQL filters from validated context parameters. */
public interface SelectorContextPolicy {
  /**
   * Determine whether this policy applies to the given target entity.
   *
   * @param entityName target DAL entity name
   * @return {@code true} when the policy can resolve filters for the entity
   */
  boolean supports(String entityName);

  /**
   * Resolve an HQL filter fragment for the given entity and context.
   *
   * @param entityName target DAL entity name
   * @param contextParams validated selector context parameters
   * @param alias HQL alias used by the selector query
   * @return an HQL filter fragment, or {@code null} when the policy does not contribute a filter.
   *         Implementations must ensure that values from {@code contextParams} are handled safely
   *         to prevent HQL injection (e.g., using named parameter binding).
   */
  String resolveFilter(String entityName, Map<String, String> contextParams, String alias);
}
