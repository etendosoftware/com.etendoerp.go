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

  private final int status;
  private final String errorCode;
  private final String field;
  private final List<String> available;
  private final String hint;
  private final String seeAlso;
  private final JSONObject extras;

  private McpRoutingException(String detail, int status, String errorCode, String field,
      List<String> available, String hint, String seeAlso) {
    this(detail, status, errorCode, field, available, hint, seeAlso, null);
  }

  private McpRoutingException(String detail, int status, String errorCode, String field,
      List<String> available, String hint, String seeAlso, JSONObject extras) {
    super(detail);
    this.status = status;
    this.errorCode = errorCode;
    this.field = field;
    this.available = available == null ? List.of() : List.copyOf(available);
    this.hint = hint;
    this.seeAlso = seeAlso;
    this.extras = extras;
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
            : "Retry with one of the names in 'available'.",
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
        "Retry with one of the names in 'available'.", McpConstants.SEE_ALSO_READING);
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
        "Unknown filter field '" + key + "' on entity '" + entityName + "'",
        McpConstants.STATUS_UNPROCESSABLE, McpConstants.ERROR_UNKNOWN_FILTER_FIELD, key, names,
        truncated
            ? "Retry with one of the names in 'available'. That list is truncated — call "
                + "neo_schema for this entity to see every filterable field."
            : "Retry with one of the names in 'available'.",
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
        McpConstants.SEE_ALSO_READING, extras);
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
