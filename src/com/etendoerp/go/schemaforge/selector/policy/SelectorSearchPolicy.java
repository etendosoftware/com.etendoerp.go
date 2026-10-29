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
package com.etendoerp.go.schemaforge.selector.policy;

import java.util.Map;
import java.util.Set;

import com.etendoerp.go.schemaforge.selector.meta.SelectorMeta;

/**
 * Contract for selector policies that widen the search-as-you-type match (ETP-5948).
 *
 * <p>The base search only matches the selector's display column. A policy that shows a value
 * the user reads and types (e.g. a tax rate rendered before the label) returns the ids of the
 * records that value matches; they are OR'ed into the search predicate, inside every other
 * filter of the query, so nothing outside what the selector already offers can come back.
 */
public interface SelectorSearchPolicy {
  /**
   * Determine whether this policy should widen the search of the selector.
   *
   * @param meta resolved selector metadata
   * @param contextParams validated selector context parameters
   * @return {@code true} when the policy should run
   */
  boolean supports(SelectorMeta meta, Map<String, String> contextParams);

  /**
   * Ids of the records the search term also matches.
   *
   * @param meta resolved selector metadata
   * @param contextParams validated selector context parameters
   * @param search the raw search term, never blank
   * @return matching record ids, empty when the term means nothing to this policy
   */
  Set<String> searchMatchIds(SelectorMeta meta, Map<String, String> contextParams, String search);
}
