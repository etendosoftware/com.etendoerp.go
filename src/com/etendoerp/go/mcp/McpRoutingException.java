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

/**
 * A routing failure that already knows its own IMP-5 envelope (ETP-4793 / IMP-17).
 *
 * <p>Every failure raised while resolving <em>which</em> spec or entity a tool call means used to be
 * a plain {@link OBException}, and {@code McpToolRouter#route}'s catch-all turned it into one prose
 * line: {@code "Error executing neo_list: Entity not found: header"} (evidence B20). No {@code
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
   * The spec named by the tool call does not exist, is inactive, or is not exposed to MCP.
   *
   * <p>No {@code available} list here on purpose: the catalog can hold dozens of specs, and dumping
   * them into every mistyped call is a context cost the agent did not ask for (ACE). {@code
   * neo_discover} is the tool that enumerates them, so the hint points there instead.</p>
   *
   * @param specName the spec name that matched nothing
   * @return the exception to throw
   */
  static McpRoutingException specNotFound(String specName) {
    return new McpRoutingException("Spec not found: " + specName,
        McpConstants.STATUS_NOT_FOUND, McpConstants.ERROR_NOT_FOUND, McpConstants.PARAM_SPEC,
        List.of(),
        "Call neo_discover to list the specs this role can reach, with their exact names.",
        McpConstants.SEE_ALSO_READING);
  }

  /**
   * The entity named by the tool call is not an included entity of the resolved spec.
   *
   * <p>Here the list <em>is</em> carried: a spec exposes a handful of entities, the router has them
   * in hand at the point of failure, and they are the whole answer to the agent's next question.
   * Evidence B20 was exactly this call — {@code neo_list(product, header)} against a spec whose
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
            ? "This spec exposes no entities. Call neo_discover to find one that does."
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
   * neo_schema} for the full list when the cap bites.</p>
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
                + "neo_schema with view:\"full\" for this entity to see every filterable "
                + "field."
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
   * {@code neo_list}, say — simply vanished, and the unnarrowed answer came back looking exactly
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
   * while {@code neo_get} denied it existed: three tools, three answers, and a caller that sets a
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
   * published by {@code neo_schema} carrying {@code readOnly: true}, so the refusal repeats what
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
        "Remove it from 'fields' and retry. neo_schema reports this field with readOnly:true; the "
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
            ? "Send only fields listed in 'available'. That list is truncated — call neo_schema "
                + "with view:\"create\" for this entity to see every field you may send."
            : "Send only fields listed in 'available'.",
        McpConstants.SEE_ALSO_WRITING);
  }

  /**
   * IMP-44: {@code neo_schema} requires an explicit {@code view}. The default used to be the full
   * field dump — 39.5 kB on {@code sales-order/header} against 5.4 kB for {@code view:"create"} —
   * and the caller learned of the cheaper projection from a hint at the bottom of the response it
   * had already paid for. The tool description has recommended {@code view:"create"} since
   * 2026-08-06 and three independent blind agents still took the full route, so the projection is
   * now a decision the caller states rather than one it inherits.
   *
   * <p>The same refusal covers an unrecognised value. {@code view:"summary"} is a real view on
   * neo_list and neo_get and was silently ignored here, returning the full dump to a caller that
   * had explicitly asked for less.</p>
   *
   * @param supplied the value the caller sent, or {@code null}/blank when the argument was absent
   * @return the exception to throw
   */
  static McpRoutingException schemaViewRequired(String supplied) {
    boolean absent = supplied == null || supplied.trim().isEmpty();
    return new McpRoutingException(
        absent
            ? "neo_schema requires a 'view'"
            : "Unknown view '" + supplied + "' for neo_schema",
        McpConstants.STATUS_UNPROCESSABLE, McpConstants.ERROR_VIEW_REQUIRED,
        McpActionsView.PARAM_VIEW,
        List.of(McpSchemaCreateView.VIEW_CREATE, McpSchemaCreateView.VIEW_FULL,
            McpActionsView.VIEW_ACTIONS),
        "Pick one: view:\"create\" before neo_create/neo_update (only the fields you may send, "
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
   * @param specName    the spec being called, used to phrase the follow-up {@code neo_list}
   * @param entityName  the child entity
   * @param parentEntity the parent entity's name, or {@code null} when it could not be named
   * @param parentField the DAL property holding the parent link, or {@code null}
   * @return the exception to throw
   */
  static McpRoutingException parentRequired(String specName, String entityName,
      String parentEntity, String parentField) {
    String parent = parentEntity == null ? "its parent" : parentEntity;
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
            + "list. Pass parentId with the id of the parent " + parent + " record.",
        McpConstants.STATUS_UNPROCESSABLE, McpConstants.ERROR_PARENT_REQUIRED,
        McpConstants.PARAM_PARENT_ID, List.of(),
        parentEntity == null
            ? "Look up the parent record, then pass its id as parentId."
            : "Call neo_list(spec:'" + specName + "', entity:'" + parentEntity
                + "') to find the parent first, then repeat this call with parentId:'<thatId>'.",
        McpConstants.SEE_ALSO_READING).withExtras(extras);
  }

  static McpRoutingException missingArgument(String detail, String field) {
    return new McpRoutingException(detail, McpConstants.STATUS_UNPROCESSABLE,
        McpConstants.ERROR_VALIDATION, field, List.of(),
        "Supply the argument named in 'field'. neo_schema lists what each tool accepts.",
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
