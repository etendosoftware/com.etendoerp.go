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

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.etendoerp.go.schemaforge.util.NeoActionContract;
import com.etendoerp.go.schemaforge.util.NeoReportParam;

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
   * Declares HQL predicates that every list read of this handler's entity must satisfy
   * (ETP-5009).
   *
   * <p><b>Why this exists.</b> A handler that hides rows by post-filtering {@code afterHandle}'s
   * {@code response.data} only hides them from the page it was handed: core already applied
   * {@code LIMIT}/{@code OFFSET} and counted {@code totalRows}, so pages come back short, the count
   * is wrong, and the {@code ?_distinct=} value fetch — which never reaches {@code afterHandle} at
   * all — keeps offering values only the hidden rows carry (the Product window's filter offered
   * the internal "Discounts" category). A predicate declared here goes into the query itself, so
   * list, count, paging and distinct values agree by construction.</p>
   *
   * <p><b>Where it is applied.</b> ANDed into the generic list query of every channel: the REST
   * list {@code GET} (and therefore its count and any CSV/XLSX export of it), the REST
   * {@code ?_distinct=<field>} value fetch, and MCP {@code etendo_list}. It is <b>not</b> applied to
   * a single-record read by id: core resolves that with its own {@code id = :id} query and ignores
   * the where clause, so a handler that must also hide a record from a direct read keeps doing so
   * in {@code afterHandle}. Nor is it applied to a read a handler serves itself from
   * {@link #handle}.</p>
   *
   * <p><b>Contract.</b> Each predicate is a complete HQL boolean expression over the alias
   * {@code e} (the entity being read). It is spliced into the HQL text verbatim — there is no
   * bind-parameter mechanism — so it must be a server-side constant or built only from values the
   * server validated; never from request input. It is resolved on its own instance, separately
   * from the one that runs {@link #handle}/{@link #afterHandle}, so it must not rely on
   * per-request state set by those. A predicate that throws fails the read rather than silently
   * returning the rows it was meant to hide.</p>
   *
   * @param context the read context: spec, entity, {@code GET}, no record id, the query params
   * @return the predicates to AND into the read; the default is empty (no restriction)
   */
  default List<String> readPredicates(NeoContext context) {
    return Collections.emptyList();
  }

  /**
   * Whether the current role may reach the surface this handler serves.
   *
   * <p>Only meaningful for handlers that own their own access rule — today the report handlers,
   * whose grant lives in a place the shared spec gate cannot evaluate: a classic
   * {@code AD_Process}, an OBUIAPP process definition, or a tab-less window. A spec of type
   * {@code R} with no linked process and no {@code AD_TAB_ID} reaches
   * {@code NeoAccessHelper.hasReportSpecAccess} with nothing to check, and the default there is
   * permissive, so without this declaration the catalogue advertises a report the role is then
   * refused when it calls it.</p>
   *
   * <p>Overriding it puts the answer in ONE place: {@code etendo_discover}, the publication of the
   * report tool and the execution itself all resolve through the same method, so a role that
   * cannot run a report no longer sees it offered. Implementations must answer for the surface
   * as a whole; a rule that varies per request (the aging report's receivables/payables split)
   * answers "may the role use this at all" here and keeps the exact check where it executes.</p>
   *
   * @return {@code true} when the current role may use this handler's surface; the default
   *     {@code true} preserves today's behaviour for every handler that does not gate
   */
  default boolean isAccessibleForCurrentRole() {
    return true;
  }

  /**
   * Declares DAL property names that the create-time callout cascade must not populate or
   * overwrite for this entity. The default is empty so handlers opt in only when a legacy
   * callout differs from the entity's NEO contract.
   *
   * @param context the create request context
   * @return protected DAL property names
   */
  default Set<String> protectedCreateCalloutFields(NeoContext context) {
    return Collections.emptySet();
  }

  /**
   * Declares the DAL property names this customization resolves server-side on create, so a
   * caller does not have to send them even though AD marks the column mandatory (ETP-5535).
   *
   * <p><b>Why the customization declares it.</b> {@code etendo_schema(view:"create")} learns what the
   * server fills from two generic sources: the values {@code etendo_defaults} resolves without any
   * input, and the selector policies' own wrapper fields. Neither can see a value the server derives
   * <em>from another field of the same body</em> — the create callout cascade, or the
   * customization's own pre-hook — because that derivation only runs once the caller has sent its
   * source. Without this declaration the schema lists the field as {@code required}, the agent goes
   * looking for a value the server would have chosen for it, and the server's choice and the
   * agent's can then disagree. The customization is the only place that knows the derivation
   * exists, so it is the one that says so.</p>
   *
   * <p>Reader: {@code etendo_schema(view:"create")} moves the names to {@code optional} with
   * {@code serverDefaulted:true}. The {@code etendo_create} mandatory pre-check does not skip them: it
   * runs after the create callout cascade and before this customization's pre-hook, so a field the
   * cascade derived is not missing there, and one it could not derive is reported with a precise
   * 422 rather than left to the DAL's NOT NULL check. A caller may still send a value; it is
   * honoured as on any other field.</p>
   *
   * <p>Consequently, declare only fields the <b>create callout cascade</b> derives. A field filled
   * only by this customization's own {@code handle()} would still be refused by the pre-check on
   * {@code etendo_create}, which runs first.</p>
   *
   * @return the property names resolved server-side on create; empty by default
   */
  default Set<String> serverResolvedCreateFields() {
    return Collections.emptySet();
  }

  /**
   * Declares whether this handler serves ACTION sub-endpoint requests
   * ({@code POST /{spec}/{entity}/{id}/action/{name}}), i.e. whether the entity it backs has
   * an {@code /action} route at all.
   *
   * <p><b>Why this exists (ETP-4254).</b> The MCP catalog hides a spec whose every included
   * entity is handler-backed (no {@code AD_Tab}), because the generic CRUD path cannot serve
   * it — that is how the dashboard's business widgets stay out of {@code etendo_discover} and
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
   * <p>ETP-5468: a handler that declares {@link #actionContracts()} serves actions by definition,
   * so the default answers {@code true} for it. For every other handler the default is unchanged
   * ({@code false}), because {@code actionContracts()} is empty unless overridden.</p>
   *
   * @return {@code true} when this handler answers ACTION sub-endpoint requests
   */
  default boolean servesActions() {
    return !actionContracts().isEmpty();
  }

  /**
   * Declares the named actions this handler serves through {@code etendo_action} / the ACTION
   * sub-endpoint, with the parameters each accepts (ETP-5468).
   *
   * <p>For handler-served actions that have no AD button column behind them — the configuration
   * cannot describe them, so the handler is the only authority (same argument as
   * {@link #servesActions()} and {@link #reportParameters()}). A non-empty declaration makes the MCP
   * layer publish the actions in {@code etendo_schema(view:"actions")} and {@code etendo_discover}, list
   * the spec in the {@code etendo_action}/{@code etendo_schema} enums, and lets the handler judge each call
   * with {@link NeoActionContract#validate} before running it.</p>
   *
   * <p>Returns an empty map by default: the handler declares no named actions.</p>
   *
   * @return the declared actions by name, in the order they should be presented
   */
  default Map<String, NeoActionContract> actionContracts() {
    return Collections.emptyMap();
  }

  /**
   * Names the actions this handler serves to the SPA but that an agent must never run
   * (ETP-5558), e.g. a bank-initiated payment that ends in an authorization only a person can give.
   *
   * <p>Read by the MCP only: {@code etendo_action} refuses them (405) before the handler runs, and
   * they are never advertised. REST and the SPA ignore it. The handler is the authority because it
   * is the one that serves them; a configuration row may add a second guard but must not be the
   * only one.</p>
   *
   * <p>Returns an empty set by default: nothing is excluded.</p>
   *
   * @return the excluded action names
   */
  default Set<String> agentExcludedActions() {
    return Collections.emptySet();
  }

  /**
   * Declares the input parameters this handler accepts when generating a report, i.e. the body
   * keys it actually reads.
   *
   * <p><b>Why this exists (ETP-4793 / IMP-19).</b> Report parameters are not AD columns —
   * {@code dateFrom}, {@code recOrPay} and {@code column1} exist only inside the handler's own
   * SQL — and every active report spec carries zero {@code ETGO_SF_FIELD} rows, so the tool
   * schema built from configuration degenerated to an untyped {@code parameters:{type:"object"}}
   * for all eight {@code generate_*} tools. An agent could see that a report took "parameters"
   * and nothing about which, of what type, or which were mandatory; the only feedback was the
   * handler's own 400. As with {@link #servesActions()}, the configuration cannot carry this, so
   * the handler is the only authority.</p>
   *
   * <p><b>It is also the callability signal.</b> {@code Optional.empty()} — the default — means
   * "I am not a report generator", and
   * {@code NeoReportCallability#isReportCallable(SFSpec)} then hides the spec's
   * {@code generate_*} tool. This closes a second half of the same defect: a spec was treated as
   * callable merely because <i>some</i> entity carried a {@code Java_Qualifier}, so five of the
   * eight published report tools were UI handlers that dispatch on an {@code action} query
   * param and could only ever answer 405 or 400 to the report route. Returning an <b>empty
   * list</b> is different and meaningful: it declares a real report that takes no inputs.</p>
   *
   * <p>Declare only parameters the handler demonstrably reads. A declared-but-ignored parameter
   * is the same silent lie as an undeclared-but-required one, with the failure moved to the
   * caller's side.</p>
   *
   * @return the declared parameters, or {@code Optional.empty()} when this handler does not
   *         generate reports
   */
  default Optional<List<NeoReportParam>> reportParameters() {
    return Optional.empty();
  }

  /**
   * Declares the output formats this handler actually serves, lowercase.
   *
   * <p>Defaults to JSON alone, which is what every NEO-native report handler returns today. The
   * tool schema previously advertised {@code "pdf, xlsx, csv (default: pdf)"} while the router
   * never read the {@code format} argument at all — so a request for a PDF was answered with
   * JSON and no indication that the format had been ignored. Override this only when the
   * handler genuinely branches on the format.</p>
   *
   * @return the supported format names; never empty
   */
  default List<String> reportFormats() {
    return List.of(NeoReportParam.FORMAT_JSON);
  }
}
