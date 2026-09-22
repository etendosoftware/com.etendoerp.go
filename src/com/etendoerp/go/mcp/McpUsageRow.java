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
 * All portions are Copyright (C) 2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.mcp;

/**
 * One {@code ETGO_MCP_USAGE} row, fully resolved on the request thread before it is handed to
 * {@link McpUsageLogger}.
 *
 * <p><b>Shape, never content.</b> Every component of this record describes the <i>form</i> of an MCP
 * call — which tool, which entity, which field <i>names</i>, how big, how long, did it fail. None of
 * them may ever carry a business value, a record id the user chose, or anything the user typed.
 * {@link #fieldsTouched} in particular is a comma-separated list of field <i>names</i>; if you find
 * yourself putting a value in it, the change is wrong. {@link #payload} is the single exception and
 * it is reserved for {@code row_type = 'feedback'} rows (Track B3, not yet built) — it stays null on
 * every {@code tool_call} row.</p>
 *
 * <p>Immutable, and deliberately free of any Etendo type: it is built in the request thread and read
 * on the writer thread, so it must not hold a DAL object, an {@code OBContext}, or anything else
 * whose validity is bound to the request. That boundary is the reason this type exists — it is not
 * a bag of getters that could be inlined away.</p>
 *
 * <p>Built through {@link #builder()} and never through the canonical constructor. With 17
 * components, almost all of them {@code String}, a positional call lets {@code verb} and
 * {@code targetEntity} be swapped with the compiler saying nothing — and the symptom would be
 * telemetry silently landing in the wrong column for months.</p>
 *
 * @param clientId      {@code AD_Client_ID} the call ran under
 * @param orgId         {@code AD_Org_ID} the call ran under
 * @param userId        {@code AD_User_ID} the call ran under, used for the audit columns
 * @param sessionKey    MCP session the call belongs to, so a sequence reads as one task
 * @param toolName      the MCP tool invoked ({@code neo_create}, {@code neo_list}, …)
 * @param verb          the CRUD/action verb the call resolved to
 * @param targetEntity  the spec, or {@code spec/entity}, the call addressed. Named
 *                      {@code targetEntity} and not {@code entity}/{@code entityName} because BOTH
 *                      generate accessors that {@code BaseOBObject} already defines
 *                      ({@code getEntity()}, {@code getEntityName()}), and the generated DAL class
 *                      then fails to compile
 * @param fieldsTouched the field NAMES the call carried, comma-separated. Never field values — see
 *                      the class javadoc
 * @param outcome       {@link #OUTCOME_OK} or {@link #OUTCOME_ERROR}
 * @param errorCode     the canonical MCP error code when the call failed, else null
 * @param durationMs    wall-clock duration of the call
 * @param reqBytes      size of the request payload
 * @param respBytes     size of the response payload
 * @param clientName    client name from the MCP {@code initialize} handshake
 * @param clientVersion client version from the MCP {@code initialize} handshake
 * @param rowType       {@link #ROW_TYPE_TOOL_CALL} or {@link #ROW_TYPE_FEEDBACK}
 * @param payload       reserved for feedback rows (Track B3); null on every tool-call row
 */
record McpUsageRow(
    String clientId,
    String orgId,
    String userId,
    String sessionKey,
    String toolName,
    String verb,
    String targetEntity,
    String fieldsTouched,
    String outcome,
    String errorCode,
    Long durationMs,
    Long reqBytes,
    Long respBytes,
    String clientName,
    String clientVersion,
    String rowType,
    String payload) {

  /** Discriminator for a row describing a single MCP tool call. */
  static final String ROW_TYPE_TOOL_CALL = "tool_call";
  /** Discriminator reserved for Track B3 in-band agent feedback. */
  static final String ROW_TYPE_FEEDBACK = "feedback";

  static final String OUTCOME_OK = "ok";
  static final String OUTCOME_ERROR = "error";

  static Builder builder() {
    return new Builder();
  }

  /**
   * Named-setter builder, so a 17-component row is assembled at a call site where each value is
   * spelled next to the field it lands in. Every component is optional except the ones the table
   * declares NOT NULL, which are defaulted here.
   */
  static final class Builder {
    private String clientId;
    private String orgId;
    private String userId;
    private String sessionKey;
    private String toolName;
    private String verb;
    private String targetEntity;
    private String fieldsTouched;
    private String outcome = OUTCOME_OK;
    private String errorCode;
    private Long durationMs;
    private Long reqBytes;
    private Long respBytes;
    private String clientName;
    private String clientVersion;
    private String rowType = ROW_TYPE_TOOL_CALL;
    private String payload;

    Builder clientId(String v) {
      this.clientId = v;
      return this;
    }

    Builder orgId(String v) {
      this.orgId = v;
      return this;
    }

    Builder userId(String v) {
      this.userId = v;
      return this;
    }

    Builder sessionKey(String v) {
      this.sessionKey = v;
      return this;
    }

    Builder toolName(String v) {
      this.toolName = v;
      return this;
    }

    Builder verb(String v) {
      this.verb = v;
      return this;
    }

    /**
     * The spec/entity addressed. Named {@code targetEntity} and not {@code entity}/{@code entityName}
     * because BOTH generate accessors that {@code BaseOBObject} already defines
     * ({@code getEntity()}, {@code getEntityName()}), and the generated DAL class will not compile.
     */
    Builder targetEntity(String v) {
      this.targetEntity = v;
      return this;
    }

    /** Comma-separated field NAMES. Never values — see the class javadoc. */
    Builder fieldsTouched(String v) {
      this.fieldsTouched = v;
      return this;
    }

    Builder outcome(String v) {
      this.outcome = v;
      return this;
    }

    Builder errorCode(String v) {
      this.errorCode = v;
      return this;
    }

    Builder durationMs(Long v) {
      this.durationMs = v;
      return this;
    }

    Builder reqBytes(Long v) {
      this.reqBytes = v;
      return this;
    }

    Builder respBytes(Long v) {
      this.respBytes = v;
      return this;
    }

    Builder clientName(String v) {
      this.clientName = v;
      return this;
    }

    Builder clientVersion(String v) {
      this.clientVersion = v;
      return this;
    }

    Builder rowType(String v) {
      this.rowType = v;
      return this;
    }

    /** Reserved for {@link #ROW_TYPE_FEEDBACK} rows (Track B3); null on every tool-call row. */
    Builder payload(String v) {
      this.payload = v;
      return this;
    }

    McpUsageRow build() {
      return new McpUsageRow(clientId, orgId, userId, sessionKey, toolName, verb, targetEntity,
          fieldsTouched, outcome, errorCode, durationMs, reqBytes, respBytes, clientName,
          clientVersion, rowType, payload);
    }
  }
}
