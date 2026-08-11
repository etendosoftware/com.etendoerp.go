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
   * A required tool argument is absent (ETP-4793 / IMP-17).
   *
   * @param detail the message naming the missing argument
   * @param field  the argument name, or {@code null} when the whole argument object is missing
   * @return the exception to throw
   */
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
    return envelope;
  }
}
