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

/**
 * Interface for NEO Headless hook handlers.
 * Implementations are discovered via CDI using the Java_Qualifier
 * registered on ETGO_SF_Entity.
 *
 * Use {@code @Named("qualifierValue")} on your implementation to match
 * the Java_Qualifier configured on the entity record.
 *
 * Return a NeoResponse to take over the response, or null to continue
 * with default DataSourceServlet behavior.
 */
public interface NeoHandler {

  /**
   * Handle the request.
   * Return a NeoResponse to produce the full response, or null to
   * fall through to default DataSourceServlet handling.
   *
   * @param context the NeoContext carrying request metadata, entity info and OB session
   * @return a {@link NeoResponse} to send to the client, or {@code null} to fall through
   */
  NeoResponse handle(NeoContext context);

  /**
   * Post-hook: called AFTER the default service executed.
   * The context's previousResult contains the service result.
   * Return a NeoResponse to replace it, or null to keep the original.
   *
   * @param context the NeoContext whose {@code previousResult} holds the default service output
   * @return a {@link NeoResponse} to replace the default result, or {@code null} to keep it
   */
  default NeoResponse afterHandle(NeoContext context) {
    return null;
  }

  /**
   * Post-hook for callout requests: called AFTER {@code NeoCalloutService.executeCallout}
   * produced the REST response. The context's {@code previousResult} carries that response
   * and the {@code requestBody} carries the original callout payload (with {@code field},
   * {@code value} and {@code formState}).
   *
   * <p>Return a {@link NeoResponse} whose body provides additional fields to merge into
   * the existing {@code updates}/{@code combos} sections, or {@code null} to keep the
   * response unchanged. Implementations should NOT overwrite fields already set by the
   * underlying callout — the dispatcher merges only fields that are absent from the base
   * response.
   *
   * @param context the NeoContext whose {@code previousResult} holds the callout response
   *                and whose {@code requestBody} holds the original callout payload
   * @return a {@link NeoResponse} carrying the additions to merge, or {@code null} to skip
   */
  default NeoResponse afterCallout(NeoContext context) {
    return null;
  }

  /**
   * Declares whether this handler serves ACTION sub-endpoint requests
   * ({@code POST /{spec}/{entity}/{id}/action/{name}}), i.e. whether the entity it backs has
   * an {@code /action} route at all.
   *
   * <p><b>Why this exists (ETP-4254).</b> The MCP catalog hides a spec whose every included
   * entity is handler-backed (no {@code AD_Tab}), because the generic CRUD path cannot serve
   * it — that is how the dashboard's business widgets stay out of {@code neo_discover} and
   * out of the CRUD tool enums (gap G4, ETP-4284). But "no AD_Tab" alone is too broad: a
   * tab-less spec can still expose a genuine transactional action route — {@code
   * not-posted-documents} serves {@code post} / {@code bulk-post} — and hiding it would take
   * that action away from agents, which is the opposite of what ETP-4254 wants. There is no
   * action metadata on {@code ETGO_SF_ENTITY} (see {@code NeoActionSurface}), so the handler
   * is the only authority on whether it answers ACTION requests.</p>
   *
   * <p>Returns {@code false} by default: a handler that only serves or augments CRUD has no
   * action surface. <b>Override it and return {@code true} whenever your handler answers
   * {@code NeoEndpointType.ACTION}</b> — it is only consulted for tab-less specs today, but
   * declaring it keeps the catalog honest if the spec ever becomes tab-less.</p>
   *
   * @return {@code true} when this handler answers ACTION sub-endpoint requests
   */
  default boolean servesActions() {
    return false;
  }
}
