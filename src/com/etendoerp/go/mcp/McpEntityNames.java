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

package com.etendoerp.go.mcp;

import org.apache.commons.lang3.StringUtils;

import com.etendoerp.go.schemaforge.data.SFSpec;

/**
 * Entity names on MCP are case-insensitive when that is unambiguous (ETP-5714), and optional for a
 * spec's main record (ETP-5848).
 *
 * <p>The catalogue spells entities in lower camel case ({@code header}, {@code goodsReceipt}) and
 * the tool description used to give {@code 'Header'} as its example, so the first call of an agent
 * that followed it failed with {@code not_found}. The router retries a miss once with the spelling
 * this class finds; see {@code McpToolRouter#retryWithCanonicalEntity}.</p>
 *
 * <p>Each spec names its main entity its own way ({@code header}, {@code businessPartner},
 * {@code productCategory}), and an agent that had not run {@code etendo_discover} guessed
 * {@code header} and missed on every spec named otherwise (OKR O2 sweep, 2026-11-02). An omitted
 * entity now means the spec's primary entity: see {@link #primaryName}.</p>
 */
final class McpEntityNames {

  private McpEntityNames() {
  }

  /**
   * The entity name as the spec spells it, when the caller typed it with different capitals.
   *
   * <p>Used only after an exact lookup missed, so the normal path pays nothing. It looks among the
   * entities the caller may SEE ({@link McpSupportInternals#includedEntityNames}), so it can never
   * reveal one the role cannot reach. Names that differ only by case are ambiguous and answer
   * {@code null}: the caller then gets the usual {@code not_found} with the exact names.</p>
   *
   * @param specName the spec the entity belongs to
   * @param typed    the name the caller sent
   * @return the single visible entity whose name equals {@code typed} ignoring case and differs from
   *         it, else {@code null}
   */
  static String canonicalName(String specName, String typed) {
    if (StringUtils.isBlank(typed)) {
      return null;
    }
    SFSpec spec = McpToolRouterSupport.findActiveSpecByName(specName);
    String found = null;
    for (String name : McpSupportInternals.includedEntityNames(spec.getId())) {
      if (name.equalsIgnoreCase(typed) && !name.equals(typed)) {
        if (found != null) {
          return null;
        }
        found = name;
      }
    }
    return found;
  }

  /**
   * The spec's primary entity among those the caller may SEE: the one {@code etendo_discover}
   * reports as {@code primaryEntity} (its top-level tab), else the first visible one.
   *
   * <p>Used by the router to fill an omitted {@code entity}, so that "list the contacts" or "get
   * this invoice" works without knowing how the spec names its main record.</p>
   *
   * @param specName the spec the call addresses
   * @return the primary entity name, or {@code null} when the spec is unknown or shows the caller
   *         nothing (the call then fails with the usual missing-argument error)
   */
  static String primaryName(String specName) {
    if (StringUtils.isBlank(specName)) {
      return null;
    }
    SFSpec spec = McpToolRouterSupport.findActiveSpecByName(specName);
    if (spec == null) {
      return null;
    }
    return McpToolRouterSupport.resolvePrimaryEntityName(
        McpToolRouterSupport.listVisibleEntities(spec.getId()));
  }
}
