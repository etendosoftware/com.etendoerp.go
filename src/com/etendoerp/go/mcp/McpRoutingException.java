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
 * All portions are Copyright © 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.mcp;

import java.util.List;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.base.exception.OBException;

import com.etendoerp.go.schemaforge.util.NeoMethodPolicy;

/**
 * A routing failure that already knows its own IMP-5 envelope (ETP-4793 / IMP-17).
 *
 * <p>Every failure raised while resolving <em>which</em> spec or entity a tool call means used to be
 * a plain {@link OBException}, and {@code McpToolRouter#route}'s catch-all turned it into one prose
 * line: {@code "Error executing etendo_list: Entity not found: header"} (evidence B20). No {@code
 * status}, no machine-detectable code, no field, and — worst for an agent that guessed a name — no
 * list of the names that would have worked, even though the router had just queried them.</p>
 *
 * <p>Extending {@code OBException} keeps the throw sites and every existing {@code catch (Exception)}
 * unchanged; only {@code route} looks for this subtype and returns {@link #toEnvelope()} instead of
 * the prose line. The envelope shape is IMP-5's, and {@code available} is IMP-3's self-correcting
 * list — the same pattern that made an unknown named filter answer
 * {@code "Available: completed, pending, partial"} (evidence B19) rather than just failing.</p>
 */
class McpRoutingException extends OBException {

  private static final long serialVersionUID = 1L;

  /** Repeated by three factories; Sonar java:S1192 and one place to reword it. */
  private static final String RETRY_WITH_AVAILABLE = "Retry with one of the names in 'available'.";

  /** Opens every message that names the offending field, so the three read the same way. */
  private static final String FIELD_PREFIX = "Field '";

  /** Joins a quoted entity (or action) to the quoted spec it belongs to. */
  private static final String QUOTED_OF = "' of '";

  /** Closes the {@code spec:} argument of a suggested call and opens its {@code entity:}. */
  private static final String ENTITY_ARG = "', entity:'";

  private final int status;
  private final String errorCode;
  private final String field;
  private final List<String> available;
  private final String hint;
  private final String seeAlso;
  /**
   * Extra envelope keys this failure carries beyond the IMP-5 shape, or {@code null}.
   *
   * <p>Not a constructor parameter: an eighth one crossed Sonar's java:S107 limit, and the honest
   * reading is that it does not belong with the other seven. Those describe the failure itself;
   * this carries whatever the caller needs to correct it, which today is only {@code parentEntity}
   * and {@code parentField}. Set through {@link #withExtras} at the one factory that has any.</p>
   */
  private JSONObject extras;

  private McpRoutingException(String detail, int status, String errorCode, String field,
      List<String> available, String hint, String seeAlso) {
    super(detail);
    this.status = status;
    this.errorCode = errorCode;
    this.field = field;
    this.available = available == null ? List.of() : List.copyOf(available);
    this.hint = hint;
    this.seeAlso = seeAlso;
  }

  /**
   * The machine-readable code of this refusal ({@code read_only_field}, {@code parent_required},
   * ...), the same value the envelope carries under {@code error}.
   *
   * @return the error code
   */
  String getErrorCode() {
    return errorCode;
  }

  /**
   * The single WARN line this refusal leaves in the router's log, built from its error code
   * (ETP-5639).
   *
   * @param toolName the tool that was called
   * @return e.g. {@code MCP tool 'etendo_update' rejected (read_only_field): Field 'x' is read-only…
   *         session=<key>}
   */
  String logLine(String toolName) {
    return "MCP tool '" + toolName + "' rejected (" + errorCode + "): " + getMessage()
        + " session=" + McpUsageTelemetry.sessionForLog();
  }

  /**
   * Attach extra envelope keys and return {@code this}, so a factory reads as one expression.
   *
   * @param extraKeys the keys to merge into {@link #toEnvelope()}
   * @return this exception
   */
  private McpRoutingException withExtras(JSONObject extraKeys) {
    this.extras = extraKeys;
    return this;
  }

  /**
   * The current name of a fixed tool called by its pre-ETP-5602 {@code neo_<x>} name.
   *
   * @param toolName the name the caller used
   * @return the {@code etendo_<x>} name, or {@code null} when the name is not a renamed tool
   */
  static String renamedToolName(String toolName) {
    if (toolName == null || !toolName.startsWith(McpConstants.LEGACY_TOOL_PREFIX)) {
      return null;
    }
    String candidate = "etendo_" + toolName.substring(McpConstants.LEGACY_TOOL_PREFIX.length());
    return McpConstants.TOOLS_RENAMED_FROM_NEO.contains(candidate) ? candidate : null;
  }

  /**
   * The tool was called by the name it carried before ETP-5602 renamed the fixed tools from
   * {@code neo_<x>} to {@code etendo_<x>}. Answered with the new name instead of falling through
   * to the process-tool branch, which would have asked for a scope or a spec that has nothing to
   * do with the mistake. Nothing is executed: this is not an alias.
   *
   * @param oldName the removed tool name the caller used
   * @param newName the current name of the same tool
   * @return the exception to throw
   */
  static McpRoutingException toolRenamed(String oldName, String newName) {
    return new McpRoutingException("Tool '" + oldName + "' was renamed to '" + newName + "'",
        McpConstants.STATUS_NOT_FOUND, McpConstants.ERROR_NOT_FOUND, "name", List.of(newName),
        "Call '" + newName + "' with the same arguments. Run tools/list to refresh the catalog.",
        null);
  }

  /**
   * The spec named by the tool call does not exist, is inactive, or is not exposed to MCP.
   *
   * <p>No {@code available} list here on purpose: the catalog can hold dozens of specs, and dumping
   * them into every mistyped call is a context cost the agent did not ask for (ACE). {@code
   * etendo_discover} is the tool that enumerates them, so the hint points there instead.</p>
   *
   * @param specName the spec name that matched nothing
   * @return the exception to throw
   */
  static McpRoutingException specNotFound(String specName) {
    return new McpRoutingException("Spec not found: " + specName,
        McpConstants.STATUS_NOT_FOUND, McpConstants.ERROR_NOT_FOUND, McpConstants.PARAM_SPEC,
        List.of(),
        "Call etendo_discover to list the specs this role can reach, with their exact names.",
        McpConstants.SEE_ALSO_READING);
  }

  /**
   * {@code etendo_discover(spec)} named a spec this role does not reach — unknown, inactive, hidden
   * from MCP or denied by window access, which are deliberately indistinguishable (IMP-53).
   *
   * <p>Unlike {@link #specNotFound}, the list <em>is</em> carried: the agent called the catalog tool
   * precisely to learn the names, the handler already has the reachable ones in hand, and the list
   * of names is a fraction of the full catalog the narrowed call exists to avoid.</p>
   *
   * @param unknown   every requested name that is not reachable, in request order — all of them,
   *                  so one retry can fix an array call
   * @param available the names of every spec this role reaches
   * @return the exception to throw
   */
  static McpRoutingException unknownDiscoverSpec(List<String> unknown, List<String> available) {
    return new McpRoutingException((unknown.size() == 1 ? "Unknown spec '" : "Unknown specs '")
        + String.join("', '", unknown) + "' for etendo_discover",
        McpConstants.STATUS_UNPROCESSABLE, McpConstants.ERROR_VALIDATION, McpConstants.PARAM_SPEC,
        available,
        available.isEmpty()
            ? "This role reaches no spec. Call etendo_discover without arguments to confirm."
            : RETRY_WITH_AVAILABLE + " Omit 'spec' to get the whole catalog.",
        McpConstants.SEE_ALSO_READING);
  }

  /**
   * The entity named by the tool call is not an included entity of the resolved spec.
   *
   * <p>Here the list <em>is</em> carried: a spec exposes a handful of entities, the router has them
   * in hand at the point of failure, and they are the whole answer to the agent's next question.
   * Evidence B20 was exactly this call — {@code etendo_list(product, header)} against a spec whose
   * entity is not called {@code header}.</p>
   *
   * @param entityName the entity name that matched nothing
   * @param specName   the spec that was searched
   * @param available  the included entity names of that spec
   * @return the exception to throw
   */
  static McpRoutingException entityNotFound(String entityName, String specName,
      List<String> available) {
    return new McpRoutingException(
        "No entity '" + entityName + "' in spec '" + specName + "'",
        McpConstants.STATUS_NOT_FOUND, McpConstants.ERROR_NOT_FOUND, McpConstants.PARAM_ENTITY,
        available,
        available.isEmpty()
            ? "This spec exposes no entities. Call etendo_discover to find one that does."
            : RETRY_WITH_AVAILABLE,
        McpConstants.SEE_ALSO_READING);
  }

  /**
   * The spec exists but exposes no CRUD entities, so the tool the agent reached for is the wrong one
   * (a report-type spec, ETP-4257).
   *
   * <p>{@code validation_error} rather than {@code not_found}: nothing the agent named is missing —
   * the call is well-formed against the wrong surface, and the message already says which surface to
   * use, so a retry can succeed.</p>
   *
   * @param detail the explanation already built by the caller
   * @return the exception to throw
   */
  static McpRoutingException notCrudCapable(String detail) {
    return new McpRoutingException(detail, McpConstants.STATUS_UNPROCESSABLE,
        McpConstants.ERROR_VALIDATION, McpConstants.PARAM_SPEC, List.of(), null,
        McpConstants.SEE_ALSO_READING);
  }

  /**
   * The entity has no AD tab, so no tool that works off one can serve it (ETP-5405).
   *
   * <p>Sixteen active, included entities are backed by a handler rather than by a window and carry
   * {@code ad_tab_id IS NULL}. {@code etendo_list} and {@code etendo_get} route those through the handler
   * and never reach this; the tools that genuinely need a tab to answer — {@code etendo_schema} and
   * the write and defaults paths, which describe or fill a tab's fields — cannot, and used to say
   * so as a bare {@code IllegalArgumentException} that the catch-all rendered as
   * {@code 500 server_error "No AD_Tab linked to entity: header"}.</p>
   *
   * <p>A 500 is the wrong answer twice over: it reads as an instance fault rather than a
   * configuration one, and the blind-agent run on ETP-5405 showed what it costs — the agent took
   * the 500 plus its "re-sending will not help" hint as proof the capability was broken, abandoned
   * the spec that was built for its question, and spent eight further calls reassembling the answer
   * by hand. The distinction this draws is the one the agent needed: with a handler the entity is
   * readable and only this tool is wrong, so the hint names the tools that do work; with no handler
   * it is genuinely unserviceable and the configuration is at fault.</p>
   *
   * @param entityName the entity that carries no tab
   * @param hasHandler whether a {@code NeoHandler} is registered for it
   * @return the exception to throw
   */
  static McpRoutingException entityHasNoTab(String entityName, boolean hasHandler) {
    if (hasHandler) {
      return new McpRoutingException(
          "Entity '" + entityName + "' is served by a dedicated handler, not by a window, so this "
              + "tool has no field metadata to work from",
          McpConstants.STATUS_METHOD_NOT_ALLOWED, McpConstants.ERROR_METHOD_NOT_ALLOWED, null,
          List.of(),
          "Read it with etendo_list or etendo_get, which route through the handler. There is no field "
              + "schema to fetch and it cannot be written through the generic tools.",
          McpConstants.SEE_ALSO_READING);
    }
    return new McpRoutingException(
        "Entity '" + entityName + "' has no window and no handler behind it, so nothing can serve "
            + "it",
        McpConstants.STATUS_UNPROCESSABLE, McpConstants.ERROR_VALIDATION, null, List.of(),
        "This is a configuration fault in the entity, not in the call. Call etendo_discover for an "
            + "entity that is serviceable.",
        McpConstants.SEE_ALSO_READING);
  }

  /**
   * The entity does not enable the HTTP verb this tool maps to (ETP-4254).
   *
   * <p>Kept out of the {@code validation_error} bucket for the reason {@link
   * McpConstants#ERROR_METHOD_NOT_ALLOWED} exists: the request is correct and the configuration
   * forbids it, so no amount of correcting values will make the call work.</p>
   *
   * @param detail the explanation already built by {@code NeoMethodPolicy}
   * @return the exception to throw
   */
  static McpRoutingException methodNotAllowed(String detail) {
    return new McpRoutingException(detail, McpConstants.STATUS_METHOD_NOT_ALLOWED,
        McpConstants.ERROR_METHOD_NOT_ALLOWED, null, List.of(), null, null);
  }

  /**
   * A write verb {@code MCP_CONFIG.verbs} hides from the MCP (ETP-5558).
   *
   * <p>Same status and code as a method the {@code ETGO_SF_ENTITY} flags disable — to the agent both
   * mean "this tool cannot do this here" — but the answer carries the operator's {@code reason} and
   * the way to get the job done, because a hidden verb is never a dead end: the UI does the same
   * thing through an action, and the agent is pointed at it.</p>
   *
   * @param specName   the spec being written
   * @param entityName the entity
   * @param method     the HTTP-method equivalent of the refused operation
   * @param reason     the declared reason, never blank
   * @param instead    the declared replacement call, or {@code null} to point at the entity's
   *                   actions
   * @return the exception to throw
   */
  static McpRoutingException verbHidden(String specName, String entityName, String method,
      String reason, String instead) {
    return new McpRoutingException(
        "'" + entityName + QUOTED_OF + specName + "' does not accept " + verbName(method)
            + " through MCP: " + reason + ". Nothing was written.",
        McpConstants.STATUS_METHOD_NOT_ALLOWED, McpConstants.ERROR_METHOD_NOT_ALLOWED, null,
        List.of(),
        instead != null
            ? "Do not retry this call. Use " + instead + " instead."
            : "Do not retry this call. Call etendo_schema(spec:'" + specName + ENTITY_ARG
                + entityName + "', view:'actions') and use the action that does this.",
        McpConstants.SEE_ALSO_WRITING);
  }

  private static String verbName(String method) {
    if (NeoMethodPolicy.METHOD_POST.equals(method)) {
      return "create";
    }
    if (NeoMethodPolicy.METHOD_DELETE.equals(method)) {
      return "delete";
    }
    return "update";
  }

  /**
   * The {@code status} filter names a business state the entity does not declare (ETP-4793 / IMP-17,
   * evidence C14).
   *
   * <p>IMP-3 already made this failure self-correcting by naming the valid states in its message, and
   * that is the reason it must not fall through to the catch-all: a {@code server_error} would tell
   * an agent to stop retrying a call that one corrected word would fix. The names move from prose into
   * {@code available}, the same key an unknown entity name uses.</p>
   *
   * @param status      the state name that matched nothing
   * @param entityName  the entity whose filters were searched
   * @param available   the named filters the entity declares
   * @return the exception to throw
   */
  static McpRoutingException unknownNamedFilter(String status, String entityName,
      List<String> available) {
    return new McpRoutingException(
        "Unknown status '" + status + "' for entity '" + entityName + "'",
        McpConstants.STATUS_UNPROCESSABLE, McpConstants.ERROR_VALIDATION, "status", available,
        RETRY_WITH_AVAILABLE, McpConstants.SEE_ALSO_READING);
  }

  /**
   * {@link #unknownNamedFilter(String, String, List)} plus what each valid name means (IMP-50):
   * {@code available} keeps its contract — the bare names — and {@code namedFilters} carries the
   * same names with their short descriptions, in the shape the {@code etendo_list} response uses,
   * so the agent can pick the right one rather than the first plausible one.
   *
   * @param described the entity's filters as {@code McpNamedFilters#summarize} renders them
   * @return the exception to throw
   */
  static McpRoutingException unknownNamedFilter(String status, String entityName,
      List<String> available, JSONArray described) {
    McpRoutingException refusal = unknownNamedFilter(status, entityName, available);
    if (described == null || described.length() == 0) {
      return refusal;
    }
    JSONObject extras = new JSONObject();
    try {
      extras.put(McpNamedFilters.KEY_NAMED_FILTERS, described);
    } catch (JSONException e) {
      return refusal;
    }
    return refusal.withExtras(extras);
  }

  /**
   * A required tool argument is absent (ETP-4793 / IMP-17).
   *
   * @param detail the message naming the missing argument
   * @param field  the argument name, or {@code null} when the whole argument object is missing
   * @return the exception to throw
   */
  /**
   * A filter key resolved to no property on the entity (ETP-5184).
   *
   * <p>Before this, {@code appendEqualityCondition} and {@code appendOperatorConditions} each
   * logged the unresolved key at WARN and returned — dropping that one condition and running the
   * query with whatever conditions remained. Filtering on a single misspelled key therefore
   * answered 200 with the whole table, which an agent cannot tell apart from a filter that legally
   * matched everything. Two call sites, one silent drop each; both now throw this.</p>
   *
   * <p>{@code available} is capped at {@link McpConstants#MAX_AVAILABLE_NAMES}: enough to reveal a
   * typo, not enough to bill the caller for a 150-property entity. The hint names {@code
   * etendo_schema} for the full list when the cap bites.</p>
   *
   * @param key        the filter key that matched no property
   * @param entityName the entity the filter was aimed at, for the message
   * @param available  the filterable property names; caller need not pre-sort or pre-truncate
   * @return the exception to throw
   */
  static McpRoutingException unknownFilterField(String key, String entityName,
      List<String> available) {
    List<String> names = available == null ? List.of() : available;
    boolean truncated = names.size() > McpConstants.MAX_AVAILABLE_NAMES;
    if (truncated) {
      names = names.subList(0, McpConstants.MAX_AVAILABLE_NAMES);
    }
    return new McpRoutingException(
        // IMP-39: deliberately says "not available", never "unknown". The same refusal answers a
        // name that does not exist and a name the spec excludes, because two distinguishable
        // answers would let a caller enumerate the underlying AD table by probing keys and reading
        // which refusal came back. The message neither asserts nor denies that such a column
        // exists; 'available' says what this entity does expose, which is what the caller is
        // entitled to know.
        FIELD_PREFIX + key + "' is not available for filtering on entity '" + entityName + "'",
        McpConstants.STATUS_UNPROCESSABLE, McpConstants.ERROR_UNKNOWN_FILTER_FIELD, key, names,
        truncated
            ? "Retry with one of the names in 'available'. That list is truncated — call "
                + "etendo_schema with view:\"full\" for this entity to see every filterable "
                + "field."
            : RETRY_WITH_AVAILABLE,
        McpConstants.SEE_ALSO_READING);
  }

  /**
   * {@code etendo_selectors} named a column that is not a selector (foreign-key) column of the entity
   * (ETP-5558). It used to escape as an {@code IllegalArgumentException} — a 500 telling the agent
   * the server had failed, when it was the agent's argument that was wrong. Blind run
   * {@code 20261001T2331-local-8163} asked for {@code glItemDifferenceId}, a name the account's
   * handler adds to its rows, not a column. Same shape as {@link #unknownFilterField}: 422, the
   * name back in {@code field}, the selector columns in {@code available}.
   *
   * @param column     the column as the caller spelled it
   * @param entityName the entity, for the message
   * @param available  the entity's selector column names
   * @return the exception to throw
   */
  static McpRoutingException unknownSelectorColumn(String column, String entityName,
      List<String> available) {
    List<String> names = available == null ? List.of() : available;
    boolean truncated = names.size() > McpConstants.MAX_AVAILABLE_NAMES;
    if (truncated) {
      names = names.subList(0, McpConstants.MAX_AVAILABLE_NAMES);
    }
    return new McpRoutingException(
        "Column '" + column + "' is not a selector column of entity '" + entityName + "'",
        McpConstants.STATUS_UNPROCESSABLE, McpConstants.ERROR_UNKNOWN_SELECTOR_COLUMN, column,
        names,
        truncated
            ? "Retry with one of the names in 'available'. That list is truncated — etendo_schema "
                + "with view:\"full\" marks every field that has a selector."
            : RETRY_WITH_AVAILABLE,
        McpConstants.SEE_ALSO_READING);
  }

  /**
   * A filter used a range operator that is not one of the recognized keys (ETP-5184).
   *
   * <p>Third of the three silent drops in {@code appendOperatorConditions}: an unrecognized
   * operator was logged and skipped, so {@code {"amount":{"greaterThan":100}}} ran as no condition
   * at all and returned every row — a 200 that looks like a successful narrowing.</p>
   *
   * @param key       the filter key the operator was written under
   * @param operator  the operator key that matched nothing
   * @param available the recognized operator keys
   * @return the exception to throw
   */
  /**
   * A tool call carried a top-level argument the tool does not declare (IMP-40).
   *
   * <p>Such an argument used to be dropped in silence, and on a read tool that is the dangerous
   * direction: an argument the caller believed was narrowing the result — a {@code parentId} on
   * {@code etendo_list}, say — simply vanished, and the unnarrowed answer came back looking exactly
   * like a correct one. A caller cannot detect that from the response, so it acts on rows it never
   * asked for. Refusing costs one retry; the silence cost correctness.</p>
   *
   * @param argument  the argument name that is not declared
   * @param toolName  the tool it was sent to
   * @param available the argument names the tool does declare
   * @return the exception to throw
   */
  /**
   * A write carried a field the spec does not expose on this entity (IMP-39).
   *
   * <p>The write path used to map the caller's keys straight onto the DAL model and pass anything
   * it could not map through untouched, with an explicit comment saying the MCP deliberately
   * accepts "all valid table columns from AI agents, not just SF-configured ones". That is what
   * let {@code orderReference} — curated out of the sales-order window — be written and filtered
   * while {@code etendo_get} denied it existed: three tools, three answers, and a caller that sets a
   * value, receives 200, and can never read it back.</p>
   *
   * <p><b>The wording is the security property.</b> It says the field is not allowed here and
   * stops. It does not say the field exists, does not say it was curated out, and does not say it
   * is unknown — because a caller able to tell those apart could enumerate the columns of the
   * underlying AD table by sending keys and reading which refusal came back. {@code available}
   * carries what this entity does expose, which is the only part the caller is entitled to.</p>
   *
   * @param field      the field name that is not allowed
   * @param entityName the entity the write was aimed at
   * @param available  the field names this entity does expose; need not be pre-sorted or truncated
   * @return the exception to throw
   */
  /**
   * A write carried a value for a field the spec exposes as read-only (IMP-48).
   *
   * <p>The MCP write path had no read-only gate at all. {@code NeoFieldFilter.filterCreateRequest}
   * has rejected these since IMP-28 — its javadoc argues the case: <em>"an agent that had just been
   * told that this field is read-only should never send it in the first place; if it does anyway,
   * the honest response is a rejection, not a silent no-op"</em> — but {@code McpToolRouter} builds
   * a {@code NeoFieldFilter} only to project GET responses and never calls it on a write. So the
   * protection existed and the MCP was outside it, and whether a caller's value was dropped or
   * persisted was decided by AD's {@code isUpdatable} alone: on {@code sales-order/header} five of
   * the seven curated read-only fields are barred by AD, while {@code DocumentNo} and
   * {@code InvoiceStatus} are not.</p>
   *
   * <p>Unlike {@link #fieldNotAllowed}, this names the reason. That costs nothing: the field is
   * published by {@code etendo_schema} carrying {@code readOnly: true}, so the refusal repeats what
   * the caller was already told rather than revealing anything the surface hid.</p>
   *
   * @param field      the field name the caller tried to write
   * @param entityName the entity the write was aimed at
   * @return the exception to throw
   */
  static McpRoutingException readOnlyField(String field, String entityName) {
    return new McpRoutingException(
        FIELD_PREFIX + field + "' is read-only on entity '" + entityName + "' and cannot be written",
        McpConstants.STATUS_UNPROCESSABLE, McpConstants.ERROR_READ_ONLY_FIELD, field, List.of(),
        "Remove it from 'fields' and retry. etendo_schema reports this field with readOnly:true; the "
            + "server maintains its value.",
        McpConstants.SEE_ALSO_WRITING);
  }

  static McpRoutingException fieldNotAllowed(String field, String entityName,
      List<String> available) {
    List<String> names = available == null ? List.of() : available;
    boolean truncated = names.size() > McpConstants.MAX_AVAILABLE_NAMES;
    if (truncated) {
      names = names.subList(0, McpConstants.MAX_AVAILABLE_NAMES);
    }
    return new McpRoutingException(
        FIELD_PREFIX + field + "' is not allowed on entity '" + entityName + "'",
        McpConstants.STATUS_UNPROCESSABLE, McpConstants.ERROR_FIELD_NOT_ALLOWED, field, names,
        truncated
            ? "Send only fields listed in 'available'. That list is truncated — call etendo_schema "
                + "with view:\"create\" for this entity to see every field you may send."
            : "Send only fields listed in 'available'.",
        McpConstants.SEE_ALSO_WRITING);
  }

  /**
   * IMP-44: {@code etendo_schema} requires an explicit {@code view}. The default used to be the full
   * field dump — 39.5 kB on {@code sales-order/header} against 5.4 kB for {@code view:"create"} —
   * and the caller learned of the cheaper projection from a hint at the bottom of the response it
   * had already paid for. The tool description has recommended {@code view:"create"} since
   * 2026-08-06 and three independent blind agents still took the full route, so the projection is
   * now a decision the caller states rather than one it inherits.
   *
   * <p>The same refusal covers an unrecognised value. {@code view:"summary"} is a real view on
   * etendo_list and etendo_get and was silently ignored here, returning the full dump to a caller that
   * had explicitly asked for less.</p>
   *
   * @param supplied the value the caller sent, or {@code null}/blank when the argument was absent
   * @return the exception to throw
   */
  static McpRoutingException schemaViewRequired(String supplied) {
    boolean absent = supplied == null || supplied.trim().isEmpty();
    return new McpRoutingException(
        absent
            ? "etendo_schema requires a 'view'"
            : "Unknown view '" + supplied + "' for etendo_schema",
        McpConstants.STATUS_UNPROCESSABLE, McpConstants.ERROR_VIEW_REQUIRED,
        McpActionsView.PARAM_VIEW,
        List.of(McpSchemaCreateView.VIEW_CREATE, McpSchemaCreateView.VIEW_FULL,
            McpActionsView.VIEW_ACTIONS),
        "Pick one: view:\"create\" before etendo_create/etendo_update (only the fields you may send, "
            + "split required/optional — the smallest and the one you want most of the time); "
            + "view:\"actions\" for the callable buttons/processes; view:\"full\" for every "
            + "field including read-only and system ones, which is several times larger.",
        McpConstants.SEE_ALSO_READING);
  }

  static McpRoutingException unknownArgument(String argument, String toolName,
      List<String> available) {
    return new McpRoutingException(
        "Unknown argument '" + argument + "' for tool '" + toolName + "'",
        McpConstants.STATUS_UNPROCESSABLE, McpConstants.ERROR_UNKNOWN_ARGUMENT, argument,
        available == null ? List.of() : available,
        "This argument was ignored, not applied — if you meant it to narrow or change the result, "
            + "the result you would have got is not the one you asked for. Retry using only the "
            + "names in 'available'.",
        McpConstants.SEE_ALSO_READING);
  }

  static McpRoutingException unknownFilterOperator(String key, String operator,
      List<String> available) {
    return new McpRoutingException(
        "Unknown filter operator '" + operator + "' on key '" + key + "'",
        McpConstants.STATUS_UNPROCESSABLE, McpConstants.ERROR_UNKNOWN_FILTER_FIELD, key, available,
        "Retry with one of the operators in 'available'.", McpConstants.SEE_ALSO_READING);
  }

  /**
   * A filter used a recognized operator but gave it a value of the wrong shape (ETP-5184).
   *
   * <p>Only {@code between} can currently fail this way — it needs a two-element array, and a
   * one-element or non-array value used to be logged and the condition dropped.</p>
   *
   * @param key      the filter key
   * @param operator the operator whose value was malformed
   * @param expected one clause describing the shape that was expected
   * @return the exception to throw
   */
  static McpRoutingException malformedFilterOperator(String key, String operator, String expected) {
    return new McpRoutingException(
        "Filter '" + key + "' operator '" + operator + "' has a malformed value: " + expected,
        McpConstants.STATUS_UNPROCESSABLE, McpConstants.ERROR_VALIDATION, key, List.of(),
        "Correct the operator's value and retry.", McpConstants.SEE_ALSO_READING);
  }

  /**
   * A call on a child entity did not name its parent record (ETP-5184).
   *
   * <p>The detail is written for an agent that does not know Etendo's data model: it says why there
   * is no global list to return, not merely that an argument is missing. {@code parentEntity} and
   * {@code parentField} ride along as {@code extras} so the correction is mechanical rather than a
   * second round of discovery.</p>
   *
   * @param specName    the spec being called, used to phrase the follow-up {@code etendo_list}
   * @param entityName  the child entity
   * @param parentEntity the parent entity's name, or {@code null} when it could not be named
   * @param parentField the DAL property holding the parent link, or {@code null}
   * @return the exception to throw
   */
  static McpRoutingException parentRequired(String specName, String entityName,
      String parentEntity, String parentField) {
    // "the id of its parent record" when the parent cannot be named — splicing "its parent" into
    // "the parent … record" read "the id of the parent its parent record" (ETP-5639).
    String parentRecord = parentEntity == null ? "its parent record"
        : "the parent " + parentEntity + " record";
    JSONObject extras = new JSONObject();
    try {
      if (parentEntity != null) {
        extras.put("parentEntity", parentEntity);
      }
      if (parentField != null) {
        extras.put("parentField", parentField);
      }
    } catch (JSONException ignored) {
      // Putting a non-null String under a constant key cannot fail; nothing to recover from.
    }
    return new McpRoutingException(
        "'" + entityName + "' is a child entity of '" + specName
            + "'. In Etendo you browse its records inside one parent record — there is no global "
            + "list. Pass parentId with the id of " + parentRecord + ".",
        McpConstants.STATUS_UNPROCESSABLE, McpConstants.ERROR_PARENT_REQUIRED,
        McpConstants.PARAM_PARENT_ID, List.of(),
        parentEntity == null
            ? "Look up the parent record, then pass its id as parentId."
            : "Call etendo_list(spec:'" + specName + ENTITY_ARG + parentEntity
                + "') to find the parent first, then repeat this call with parentId:'<thatId>'.",
        McpConstants.SEE_ALSO_READING).withExtras(extras);
  }

  /**
   * A child create on an entity whose parent cannot be identified (ETP-5558).
   *
   * <p>Raised whether or not the caller sent {@code parentId}: with the parent unmappable, the
   * mandatory-defaults pass fills the link on its own and the record lands under a parent the caller
   * never chose — a {@code payment-out} line ended up on an unrelated, processed collection that
   * way. {@code problem} is {@link McpParentScope}'s own explanation, which names the tab and the
   * columns it looked at, so the refusal says what is wrong with the entity and not only that
   * something is.</p>
   *
   * <p>The hint deliberately does not suggest setting the link field by hand: on these entities it
   * points at an intermediate record (a payment detail, a payment schedule) the agent has no safe
   * way to choose, so that advice would lead straight back to a wrong parent.</p>
   *
   * @param specName     the spec being written
   * @param entityName   the child entity
   * @param parentEntity the parent entity's name, or {@code null} when it could not be named — the
   *                     hint then sends the agent to {@code etendo_discover} instead
   * @param problem      why the parent cannot be mapped, or {@code null} when the scope gives none
   * @return the exception to throw
   */
  static McpRoutingException parentUnresolvable(String specName, String entityName,
      String parentEntity, String problem) {
    String why = problem == null ? "the entity declares no field that links it to its parent"
        : problem;
    return new McpRoutingException(
        "Cannot create '" + entityName + QUOTED_OF + specName + "' through MCP: its parent cannot "
            + "be identified (" + why + "), so the record would be attached to a parent nobody "
            + "chose. Nothing was written.",
        McpConstants.STATUS_UNPROCESSABLE, McpConstants.ERROR_PARENT_UNRESOLVABLE,
        McpConstants.PARAM_PARENT_ID, List.of(),
        parentEntity == null
            ? "Do not retry this create. Call etendo_discover to find the parent entity of '"
                + entityName + "', then etendo_schema on it with view:'actions' and use the action "
                + "that creates this record."
            : "Do not retry this create. Call etendo_schema(spec:'" + specName + ENTITY_ARG
                + parentEntity + "', view:'actions') and use the action that creates this record.",
        McpConstants.SEE_ALSO_WRITING);
  }

  /**
   * An action {@code MCP_CONFIG.actions} hides from the MCP (ETP-5558).
   *
   * <p>The customization would serve it — the SPA calls it — so this is a refusal of the channel,
   * not of the action: the same 405 a hidden write verb answers, carrying the operator's reason.</p>
   *
   * @param specName   the spec
   * @param entityName the entity
   * @param action     the refused action
   * @param reason     the declared reason (or the unusable-configuration reason)
   * @return the exception to throw
   */
  static McpRoutingException actionHidden(String specName, String entityName, String action,
      String reason) {
    return new McpRoutingException(
        "Action '" + action + QUOTED_OF + entityName + "' (" + specName + ") is not available "
            + "through MCP: " + reason + ". Nothing was run.",
        McpConstants.STATUS_METHOD_NOT_ALLOWED, McpConstants.ERROR_METHOD_NOT_ALLOWED, null,
        List.of(),
        "Do not retry this call. Call etendo_schema(spec:'" + specName + ENTITY_ARG + entityName
            + "', view:'actions') for the actions this entity offers.",
        McpConstants.SEE_ALSO_WRITING);
  }

  /**
   * A button {@code MCP_CONFIG.actions} redirects to another action (ETP-5558).
   *
   * @param specName   the spec
   * @param entityName the entity
   * @param action     the refused button
   * @param instead    the action to call instead
   * @param reason     the declared reason
   * @return the exception to throw
   */
  static McpRoutingException actionRedirected(String specName, String entityName, String action,
      String instead, String reason) {
    return new McpRoutingException(
        "Action '" + action + QUOTED_OF + entityName + "' (" + specName + ") is not run through "
            + "MCP: " + reason + ". Nothing was run.",
        McpConstants.STATUS_METHOD_NOT_ALLOWED, McpConstants.ERROR_METHOD_NOT_ALLOWED, null,
        List.of(),
        "Do not retry this call. Use etendo_action(spec:'" + specName + ENTITY_ARG + entityName
            + "', action:'" + instead + "') instead; etendo_schema(view:'actions') gives its "
            + "parameters.",
        McpConstants.SEE_ALSO_WRITING);
  }

  /**
   * A declared action called with parameters its contract refuses (ETP-5558): an undeclared key, a
   * missing required one, or a value of the wrong shape. Judged before the customization runs.
   *
   * @param specName   the spec
   * @param entityName the entity
   * @param action     the action
   * @param error      the {@code error} object {@code NeoActionContract.validate} built; its
   *                   {@code message} becomes the detail and its correction keys
   *                   ({@code unknownParameters}, {@code missingParameters}, ...) are carried over
   * @return the exception to throw
   * @throws JSONException if the error object cannot be read
   */
  static McpRoutingException actionParametersInvalid(String specName, String entityName,
      String action, JSONObject error) throws JSONException {
    JSONObject extras = new JSONObject();
    for (java.util.Iterator<?> it = error.keys(); it.hasNext();) {
      String key = String.valueOf(it.next());
      if (!"message".equals(key) && !McpConstants.KEY_STATUS.equals(key)
          && !McpConstants.PARAM_FIELD.equals(key)) {
        extras.put(key, error.get(key));
      }
    }
    String message = error.optString("message", "Invalid parameters for action '" + action + "'.");
    return new McpRoutingException(message + " Nothing was run.",
        McpConstants.STATUS_UNPROCESSABLE, McpConstants.ERROR_VALIDATION,
        error.optString(McpConstants.PARAM_FIELD, null), List.of(),
        "Correct the parameters and retry. etendo_schema(spec:'" + specName + ENTITY_ARG
            + entityName + "', view:'actions') gives the parameter schema of '" + action + "'.",
        McpConstants.SEE_ALSO_WRITING).withExtras(extras);
  }

  static McpRoutingException missingArgument(String detail, String field) {
    return new McpRoutingException(detail, McpConstants.STATUS_UNPROCESSABLE,
        McpConstants.ERROR_VALIDATION, field, List.of(),
        "Supply the argument named in 'field'. etendo_schema lists what each tool accepts.",
        McpConstants.SEE_ALSO_READING);
  }

  /**
   * Render the failure as the flat IMP-5 envelope.
   *
   * @return the envelope object
   * @throws JSONException never in practice (all values are plain strings/ints)
   */
  JSONObject toEnvelope() throws JSONException {
    JSONObject envelope = new JSONObject();
    envelope.put(McpConstants.KEY_STATUS, status);
    envelope.put(McpConstants.KEY_ERROR, errorCode);
    envelope.put(McpConstants.KEY_DETAIL, getMessage());
    if (field != null) {
      envelope.put(McpConstants.PARAM_FIELD, field);
    }
    if (!available.isEmpty()) {
      envelope.put(McpConstants.KEY_AVAILABLE, new JSONArray(available));
    }
    if (hint != null) {
      envelope.put(McpConstants.KEY_HINT, hint);
    }
    if (seeAlso != null) {
      envelope.put(McpConstants.KEY_SEE_ALSO, seeAlso);
    }
    if (extras != null) {
      java.util.Iterator<String> keys = extras.keys();
      while (keys.hasNext()) {
        String key = keys.next();
        envelope.put(key, extras.get(key));
      }
    }
    return envelope;
  }
}
