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

package com.etendoerp.go.usageevents;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.core.OBContext;

/**
 * One {@code ETGO_USAGE_EVENT} row, fully resolved on the calling thread before it is handed to
 * {@link UsageEventRecorder}.
 *
 * <p><b>Shape, never content.</b> Every component describes the <i>form</i> of what happened — which
 * event, which spec or report, which sub-action, did it fail, how long, how many tokens. None of them
 * may ever carry a business value (an amount, a name, a tax id), free text the user typed, or the
 * contents of a record. {@link #properties} is the event-specific extension point (D2) and the same
 * rule applies to it, key by key: {@code {"model":"kimi-k2.6","inputTokens":1200}} is shape, the
 * user's prompt is content. A record id belongs there only when a concrete event justifies it, for
 * example to count distinct documents (D6). If you find yourself putting a value the user would
 * recognise into any component, the change is wrong.</p>
 *
 * <p>Immutable, and deliberately free of any Etendo type: it is built on the request thread and read
 * on the writer thread, so it must not hold a DAL object, an {@code OBContext}, or a mutable map
 * whose validity or contents are bound to the caller. That is why {@link #properties} is already the
 * serialized JSON text, and why the who-columns are plain ids captured by
 * {@link Builder#fromContext()} — the writer thread has no {@code OBContext} of its own.</p>
 *
 * <p>Built through {@link #builder()} and never through the canonical constructor. With fifteen
 * components, almost all of them {@code String}, a positional call lets {@code target} and
 * {@code action} be swapped with the compiler saying nothing — and the symptom would be events
 * silently landing in the wrong column for months.</p>
 *
 * @param clientId   {@code AD_Client_ID} the event belongs to
 * @param orgId      {@code AD_Org_ID} the event belongs to
 * @param userId     {@code AD_User_ID} of the actor; also used for the audit columns
 * @param roleId     {@code AD_Role_ID} in use, or null
 * @param eventType  one of {@link UsageEventTypes}; any other value is dropped by the recorder
 * @param source     {@link #SOURCE_BACKEND}, {@link #SOURCE_UI}, {@link #SOURCE_AI_BFF} or
 *                   {@link #SOURCE_MCP} — the table's check constraint rejects anything else
 * @param sessionKey groups one session's events, or null
 * @param target     spec, report or process the event is about. Named {@code target} and not
 *                   {@code entity}/{@code entityName} because both generate accessors that
 *                   {@code BaseOBObject} already defines, and the DAL class would not compile
 * @param action     sub-action ({@code create}, {@code complete}, {@code export_csv}, …), or null
 * @param outcome    {@link #OUTCOME_OK}, {@link #OUTCOME_ERROR} or null
 * @param errorCode  canonical error code when the outcome is an error; never a message or trace
 * @param durationMs wall-clock duration, or null
 * @param occurredAt when the event happened; never null once built
 * @param appVersion UI build or module version, or null
 * @param properties event-specific attributes as a JSON object text of at most
 *                   {@value #PROPERTIES_MAX_BYTES} bytes, or null — see the class javadoc
 */
public record UsageEvent(
    String clientId,
    String orgId,
    String userId,
    String roleId,
    String eventType,
    String source,
    String sessionKey,
    String target,
    String action,
    String outcome,
    String errorCode,
    Long durationMs,
    Instant occurredAt,
    String appVersion,
    String properties) {

  public static final String SOURCE_BACKEND = "backend";
  public static final String SOURCE_UI = "ui";
  public static final String SOURCE_AI_BFF = "ai-bff";
  public static final String SOURCE_MCP = "mcp";

  public static final String OUTCOME_OK = "ok";
  public static final String OUTCOME_ERROR = "error";

  /**
   * Upper bound on the serialized {@link #properties}, in UTF-8 bytes. Past it the attributes are
   * replaced by a marker rather than cut: a clipped JSON text is not JSON, and every aggregation on
   * the column goes through {@code properties::jsonb}, which would fail on the whole query.
   */
  public static final int PROPERTIES_MAX_BYTES = 4_096;

  /** Stored instead of {@link #properties} when the attributes do not fit. Valid JSON on purpose. */
  static final String PROPERTIES_OVERSIZE_MARKER = "{\"_truncated\":true}";

  /** Etendo system user, used when there is no user in context. */
  static final String SYSTEM_USER = "100";
  static final String DEFAULT_CLIENT = "0";
  static final String DEFAULT_ORG = "0";

  private static final Logger log = LogManager.getLogger(UsageEvent.class);

  /**
   * Tell whether a source value would pass the table's check constraint.
   *
   * @param source candidate {@code SOURCE} value, possibly null
   * @return true when {@code source} is one the table's check constraint accepts
   */
  public static boolean isValidSource(String source) {
    return SOURCE_BACKEND.equals(source) || SOURCE_UI.equals(source)
        || SOURCE_AI_BFF.equals(source) || SOURCE_MCP.equals(source);
  }

  /**
   * Start a new event.
   *
   * @return an empty builder; see {@link Builder} for the defaults
   */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * Named-setter builder, so a fifteen-component row is assembled at a call site where each value is
   * spelled next to the column it lands in. Every component is optional; the ones the table declares
   * NOT NULL are defaulted either here ({@code source}, {@code occurredAt}) or by the writer
   * (client, org, audit user). {@link #build()} never throws.
   */
  public static final class Builder {
    private String clientId;
    private String orgId;
    private String userId;
    private String roleId;
    private String eventType;
    private String source = SOURCE_BACKEND;
    private String sessionKey;
    private String target;
    private String action;
    private String outcome;
    private String errorCode;
    private Long durationMs;
    private Instant occurredAt;
    private String appVersion;
    private final Map<String, Object> properties = new LinkedHashMap<>();

    private Builder() {
    }

    /**
     * Capture client, organization, user and role from the current {@link OBContext}. Must be called
     * on the thread that owns the context — the writer thread has none.
     *
     * <p>Total: a missing context, or any failure reading it, leaves client {@value #DEFAULT_CLIENT},
     * org {@value #DEFAULT_ORG}, user {@value #SYSTEM_USER} and no role. Recording an event is never
     * a reason for the caller to fail.</p>
     *
     * @return this builder
     */
    public Builder fromContext() {
      this.clientId = DEFAULT_CLIENT;
      this.orgId = DEFAULT_ORG;
      this.userId = SYSTEM_USER;
      this.roleId = null;
      try {
        OBContext context = OBContext.getOBContext();
        if (context == null) {
          return this;
        }
        if (context.getCurrentClient() != null) {
          this.clientId = context.getCurrentClient().getId();
        }
        if (context.getCurrentOrganization() != null) {
          this.orgId = context.getCurrentOrganization().getId();
        }
        if (context.getUser() != null) {
          this.userId = context.getUser().getId();
        }
        if (context.getRole() != null) {
          this.roleId = context.getRole().getId();
        }
      } catch (Throwable t) { // NOSONAR — capturing context must never fail the caller.
        log.debug("Could not read OBContext for a usage event; using defaults.", t);
      }
      return this;
    }

    /**
     * Set the {@code AD_Client_ID}; the writer defaults a null one.
     *
     * @param v the value, or null
     * @return this builder
     */
    public Builder clientId(String v) {
      this.clientId = v;
      return this;
    }

    /**
     * Set the {@code AD_Org_ID}; the writer defaults a null one.
     *
     * @param v the value, or null
     * @return this builder
     */
    public Builder orgId(String v) {
      this.orgId = v;
      return this;
    }

    /**
     * Set the {@code AD_User_ID}; the writer uses the system user for a null one.
     *
     * @param v the value, or null
     * @return this builder
     */
    public Builder userId(String v) {
      this.userId = v;
      return this;
    }

    /**
     * Set the {@code AD_Role_ID}, or null when there is none.
     *
     * @param v the value, or null
     * @return this builder
     */
    public Builder roleId(String v) {
      this.roleId = v;
      return this;
    }

    /**
     * Set the event type. One of {@link UsageEventTypes}; anything else is dropped by the recorder.
     *
     * @param v the value, or null
     * @return this builder
     */
    public Builder eventType(String v) {
      this.eventType = v;
      return this;
    }

    /**
     * Set the source of the event. Defaults to {@link UsageEvent#SOURCE_BACKEND}.
     *
     * @param v the value, or null
     * @return this builder
     */
    public Builder source(String v) {
      this.source = v;
      return this;
    }

    /**
     * Set the key that groups the events of one session or conversation.
     *
     * @param v the value, or null
     * @return this builder
     */
    public Builder sessionKey(String v) {
      this.sessionKey = v;
      return this;
    }

    /**
     * Set the target of the event. Spec, report or process the event is about. See the record javadoc for the name.
     *
     * @param v the value, or null
     * @return this builder
     */
    public Builder target(String v) {
      this.target = v;
      return this;
    }

    /**
     * Set the action taken on the target.
     *
     * @param v the value, or null
     * @return this builder
     */
    public Builder action(String v) {
      this.action = v;
      return this;
    }

    /**
     * Set the outcome, {@link UsageEvent#OUTCOME_OK} or {@link UsageEvent#OUTCOME_ERROR}.
     *
     * @param v the value, or null
     * @return this builder
     */
    public Builder outcome(String v) {
      this.outcome = v;
      return this;
    }

    /**
     * Set the error code. Canonical code only — never an exception message or a stack trace.
     *
     * @param v the value, or null
     * @return this builder
     */
    public Builder errorCode(String v) {
      this.errorCode = v;
      return this;
    }

    /**
     * Set how long the operation took.
     *
     * @param v duration in milliseconds, or null when not measured
     * @return this builder
     */
    public Builder durationMs(Long v) {
      this.durationMs = v;
      return this;
    }

    /**
     * Set when the event happened. Defaults to the moment {@link #build()} runs.
     *
     * @param v the value, or null
     * @return this builder
     */
    public Builder occurredAt(Instant v) {
      this.occurredAt = v;
      return this;
    }

    /**
     * Set the version of the application that produced the event.
     *
     * @param v the value, or null
     * @return this builder
     */
    public Builder appVersion(String v) {
      this.appVersion = v;
      return this;
    }

    /**
     * Add one event-specific attribute. Shape, never content — see the record javadoc. Strings,
     * numbers and booleans are stored as such; any other value is stored as its string form; a null
     * key or value is ignored.
     *
     * @param key   attribute name
     * @param value attribute value
     * @return this builder
     */
    public Builder property(String key, Object value) {
      if (key != null && value != null) {
        properties.put(key, value);
      }
      return this;
    }

    /**
     * Add every entry of {@code values} as by {@link #property(String, Object)}.
     *
     * @param values attributes to add; null adds nothing
     * @return this builder
     */
    public Builder properties(Map<String, ?> values) {
      if (values != null) {
        values.forEach(this::property);
      }
      return this;
    }

    /**
     * Assemble the event. Never throws.
     *
     * @return the immutable event, with {@code occurredAt} defaulted to now when unset
     */
    public UsageEvent build() {
      return new UsageEvent(clientId, orgId, userId, roleId, eventType, source, sessionKey, target,
          action, outcome, errorCode, durationMs, occurredAt != null ? occurredAt : Instant.now(),
          appVersion, serializeProperties(properties));
    }
  }

  /**
   * Serialize the attributes to a JSON object text, on the calling thread, so no mutable map crosses
   * to the writer. Total: an empty map gives null, an oversized one gives
   * {@link #PROPERTIES_OVERSIZE_MARKER}, a serialization failure gives null.
   */
  static String serializeProperties(Map<String, Object> values) {
    if (values == null || values.isEmpty()) {
      return null;
    }
    try {
      JSONObject json = new JSONObject();
      for (Map.Entry<String, Object> entry : values.entrySet()) {
        Object value = entry.getValue();
        boolean scalar = value instanceof String || value instanceof Number
            || value instanceof Boolean;
        json.put(entry.getKey(), scalar ? value : String.valueOf(value));
      }
      String text = json.toString();
      if (text.getBytes(StandardCharsets.UTF_8).length > PROPERTIES_MAX_BYTES) {
        log.debug("Usage event properties exceed {} bytes; storing the truncation marker.",
            PROPERTIES_MAX_BYTES);
        return PROPERTIES_OVERSIZE_MARKER;
      }
      return text;
    } catch (Throwable t) { // NOSONAR — a bad attribute must not cost the caller anything.
      log.debug("Could not serialize usage event properties; storing none.", t);
      return null;
    }
  }
}
