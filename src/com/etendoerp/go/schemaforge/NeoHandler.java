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

import org.openbravo.base.structure.BaseOBObject;

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
   * Declares list-filter keys that must be evaluated on another property of the same entity
   * (ETP-5705).
   *
   * <p><b>Why this exists.</b> A handler that rewrites a value in {@link #afterHandle} changes
   * what the caller sees, but not what the query filters on: the filter still runs against the
   * raw database value. When the two differ, a filter matches rows whose displayed value does not
   * satisfy it, and misses rows whose displayed value does — with a 200 and nothing to say so. A
   * customization that keeps a second property holding the displayed value (canonically a stored
   * computed twin of a virtual column) maps the displayed property to it here, and the filter is
   * evaluated on that property instead.</p>
   *
   * <p><b>Contract.</b> Keys and values are DAL property names of the entity being read; the
   * caller may still spell the key as its DB column name. Both properties must have the same type.
   * The filter is rendered as {@code coalesce(<target>, <key>)}, so a row whose target has not
   * been computed yet falls back to the key's own value rather than dropping out of the result.
   * The target is not checked against the spec's excluded fields: the condition only ever
   * compares the value the caller is already shown.</p>
   *
   * <p><b>Where it is applied.</b> MCP {@code etendo_list} only, and the response says so when it
   * happens, in a {@code filterAliases} note that names the target only when the spec exposes it
   * (an excluded target is never named: the caller could not filter on it). The REST list does
   * not consult it — the SPA filters on the stored columns directly — a divergence declared in
   * {@code docs/neo-headless.md} §4.12.9.</p>
   *
   * @param context the read context: spec, entity, {@code GET}, no record id, the query params
   * @return displayed property name to the property the filter is evaluated on; empty by default
   */
  default Map<String, String> filterAliases(NeoContext context) {
    return Collections.emptyMap();
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
   * Declares the DAL property names this customization requires the caller to send on create
   * although nothing generic says so — AD does not mark the column mandatory, or
   * {@code etendo_defaults} answers a value for it (ETP-5473).
   *
   * <p><b>Why the customization declares it.</b> {@code etendo_schema(view:"create")} builds its
   * {@code required} list from AD mandatoriness and demotes to {@code optional} with
   * {@code serverDefaulted:true} every field {@code etendo_defaults} resolves. A default the
   * customization offers only as a <em>suggestion</em> for a form — while its own pre-hook refuses a
   * create that omits the field — therefore reaches the agent as "the server fills this", and the
   * agent's first create is refused. Only the customization knows its pre-hook enforces the field,
   * so it is the one that says so; shared code never names the entity.</p>
   *
   * <p>Reader: {@code etendo_schema} flags the names {@code userRequired:true} (every view) and
   * {@code view:"create"} lists them under {@code required}, never {@code serverDefaulted}. It is a
   * declaration of what the pre-hook already enforces; it adds no check of its own.</p>
   *
   * @return the property names a create must carry; empty by default
   */
  default Set<String> callerRequiredCreateFields() {
    return Collections.emptySet();
  }

  /**
   * Declares the DAL property names this customization leaves empty on create unless the caller
   * sends them, although {@code etendo_defaults} answers a value for them (ETP-5852, M-106).
   *
   * <p>The app's form prefills such a field and then clears it before saving the new record (the
   * contact's billing preferences are filled in after it exists). {@code etendo_schema(view:"create")}
   * would otherwise mark them {@code serverDefaulted} — "the server fills this" — while the record
   * is saved with them empty. Reader: the MCP layer, which does not call them server-defaulted and
   * drops them from its {@code etendo_defaults} answer. The customization decides, on its own
   * channel, what it does with a value the caller sends.</p>
   *
   * @return the property names left empty on create; empty by default
   */
  default Set<String> createUnsetFields() {
    return Collections.emptySet();
  }

  /**
   * Fills create-body defaults that this customization can derive from the body alone, on the
   * channels whose mandatory-field pre-check runs <b>before</b> the pre-hook (ETP-5710).
   *
   * <p>MCP {@code etendo_create} validates mandatory fields before it dispatches the pre-hook
   * ({@link #handle}), so a default that only {@code handle()} fills is refused there with a 422
   * the REST channels never produce. The MCP create path therefore calls this method on the
   * resolved customization right before that pre-check. The default is a no-op, so an entity that
   * does not override it behaves exactly as before.</p>
   *
   * <p>Contract: <b>idempotent</b> (the same logic may also run later from {@code handle()}),
   * <b>never overwrites</b> a value the caller sent, and <b>never throws</b> for missing data —
   * when the value cannot be established it abstains and leaves the body untouched, so the
   * pre-check still reports the field. The body is {@link NeoContext#getRequestBody()}, mutated
   * in place.</p>
   *
   * @param context a CRUD {@code POST} create context carrying the live request body
   */
  default void resolveServerCreateFields(NeoContext context) {
    // no-op by default
  }

  /**
   * Declares the create-body directives this customization reads and removes in its pre-hook,
   * each with a one-line description for an agent (ETP-5676).
   *
   * <p>A directive is a request option, not a field: an underscore-prefixed body key that tunes
   * what the customization does around the write (e.g. the product's {@code _skipPricesSeed}).
   * The customization strips it in {@link #handle} so it never reaches the field filter or the
   * DAL. Declaring it here is what lets the MCP write verbs accept it without reporting it in
   * {@code unknownFields}, and {@code etendo_schema(view:"create")} publish it.</p>
   *
   * @return directive name to description; empty by default
   */
  default Map<String, String> createDirectives() {
    return Collections.emptyMap();
  }

  /**
   * Declares the response keys this handler adds to every GET record in {@link #afterHandle} —
   * keys that are not {@code ETGO_SF_FIELD} columns, so {@code NeoFieldFilter} does not know them
   * (ETP-5576, MCP observation 11).
   *
   * <p><b>Why this exists.</b> {@code etendo_get}/{@code etendo_list} validate a caller's
   * {@code fields:[…]} whitelist against what the entity can emit and report the rest in
   * {@code unknownFields} (IMP-18). That set came from the spec's fields only, so a key injected
   * here was reported unknown while the very same response carried its value — the
   * self-contradiction ETP-5073 removed for the audit keys. The handler is the only place that
   * knows what it injects, so it is the one that says so.</p>
   *
   * <p>Reader: the MCP projection validator only. Nothing is filtered, renamed or written on
   * account of it. Declare only keys injected on every record (or on every record of a status);
   * a name declared here is never reported unknown.</p>
   *
   * @return the injected response keys; empty by default
   */
  default Set<String> responseEnrichedFields() {
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
   * Declares, for one concrete record, the actions that do not apply to it for a reason a column
   * value cannot express (ETP-5714).
   *
   * <p>The static part of availability ({@code NeoActionContract#availableWhen}, the
   * {@code MCP_CONFIG.actions} {@code availableWhen}) compares record properties with allowed
   * values. Some rules depend on other rows: a return can be invoiced only while it has no
   * corrective invoice yet, an order can be reactivated only while nothing is linked to it. The
   * customization that owns the rule answers here, with one sentence per action.</p>
   *
   * <p>Advisory, like the rest of availability: {@code etendo_schema(view:"actions")} reports the
   * entry as {@code availableNow:false} with the sentence as {@code unavailableReason} when it is
   * asked about a record. It never refuses a call, so the handler's own guard stays the
   * enforcement. An implementation omits an action it cannot judge; one whose lookup fails may let
   * the failure propagate, because the caller ({@code McpDeclaredActions#unavailableFor}) catches
   * a {@link RuntimeException}, logs it and answers "nothing derived" for the whole record.</p>
   *
   * @param subject the record the agent asked about
   * @return {@code action name → reason}; empty by default
   */
  default Map<String, String> unavailableActions(BaseOBObject subject) {
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
