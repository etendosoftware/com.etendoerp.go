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

/**
 * Endpoint-type predicates shared by {@link NeoHandler} hooks.
 *
 * <p>Extracted from {@link FinancialAccountHandler} (ETP-5468) so that handler stays under the
 * Sonar per-class method-count limit (java:S1448). Behavior is unchanged.</p>
 */
final class NeoEndpointTypes {

  private NeoEndpointTypes() {
    // utility class — no instances
  }

  /**
   * Whether this hook invocation is a record write/read on the entity itself, as opposed to one
   * of its sub-endpoints (a button action, a callout, a display-logic evaluation, a selector).
   *
   * <p>ETP-5468: the sub-endpoints also reach this hook with {@code httpMethod=POST} — a REST
   * {@code POST /account/{id}/action/<button>} and an MCP {@code neo_action} both do — so keying
   * the create validation on the HTTP method alone ran every button call through
   * {@link FinancialAccountHandler#validateAndEnrichCreate}: an agent had to invent a unique
   * {@code name} and a {@code currency} before its button even reached the process. A {@code null}
   * endpoint type is treated as CRUD because several internal callers (batch, clone) build the
   * context without one.</p>
   *
   * @param context the hook context
   * @return {@code true} for the entity's own CRUD, {@code false} for any sub-endpoint
   */
  static boolean isCrud(NeoContext context) {
    NeoEndpointType type = context.getEndpointType();
    return type == null || NeoEndpointType.CRUD.equals(type);
  }
}
