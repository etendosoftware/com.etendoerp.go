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

import java.util.HashMap;
import java.util.Map;

import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.core.OBContext;
import org.openbravo.model.ad.ui.Tab;

import com.etendoerp.go.schemaforge.data.SFEntity;

/**
 * Context object for NEO Headless requests.
 * Carries all relevant information for hook handlers.
 */
public class NeoContext {

  private final String specName;
  private final String entityName;
  private final String httpMethod;
  private final String recordId;
  private final JSONObject requestBody;
  private final Map<String, String> queryParams;
  private final Tab adTab;
  private final SFEntity sfEntity;
  private final OBContext obContext;
  private NeoResponse previousResult;
  /**
   * IMP-45: set by the create-path callout cascade when a callout resolved a different value for
   * a field the caller had sent, and was held back by ETP-4784's protected-fields rule. Mutable
   * and off the builder on purpose — it is produced deep inside the create, after the context was
   * built, and it is a diagnostic: nothing downstream branches on it.
   */
  private JSONObject supersededDefaults;
  private final NeoEndpointType endpointType;
  private final String fieldName;
  /**
   * ETP-5284 — {@code true} when this context was built by the MCP layer rather than by the REST
   * dispatcher. The two paths hand a handler its {@link #getRequestBody() request body} under
   * different field-naming conventions (REST uses the window's field names, MCP its own tool
   * names), so a handler that injects a value has to know which spelling the caller will
   * understand. Nothing else should branch on this: it marks a naming difference, not a
   * capability one.
   */
  private final boolean mcpOrigin;
  /**
   * ETP-5194 — per-request state a customization hands from its {@code handle()} pre-hook to its
   * own {@code afterHandle()} post-hook. Every channel (REST single, REST batch, MCP) passes the
   * same {@code NeoContext} instance to both phases, while a handler instance may be shared
   * across requests, so this is the only safe place for that state. Keys should be namespaced by
   * the owning class to avoid collisions.
   */
  private final Map<String, Object> attributes = new HashMap<>();

  private NeoContext(Builder builder) {
    this.specName = builder.specName;
    this.entityName = builder.entityName;
    this.httpMethod = builder.httpMethod;
    this.recordId = builder.recordId;
    this.requestBody = builder.requestBody;
    this.queryParams = builder.queryParams;
    this.adTab = builder.adTab;
    this.sfEntity = builder.sfEntity;
    this.obContext = builder.obContext;
    this.previousResult = builder.previousResult;
    this.endpointType = builder.endpointType;
    this.fieldName = builder.fieldName;
    this.mcpOrigin = builder.mcpOrigin;
  }

  /**
   * Whether this context was built by the MCP layer. See {@link #mcpOrigin}.
   *
   * @return {@code true} for an MCP-originated call, {@code false} for a REST one
   */
  public boolean isMcpOrigin() {
    return mcpOrigin;
  }

  public String getSpecName() {
    return specName;
  }

  public String getEntityName() {
    return entityName;
  }

  public String getHttpMethod() {
    return httpMethod;
  }

  public String getRecordId() {
    return recordId;
  }

  /**
   * ETP-5009: the id core will read this request by, resolved the way
   * {@code NeoCrudHandler#buildDalParams} hands it to core — the path id when there is one (it is
   * authoritative, ETP-5195), otherwise the query-string {@code id}. So
   * {@code GET /sws/neo/{spec}/{entity}?id=X} is a read by id too, not a list read.
   *
   * @return the id to read by, or {@code null} when the request names none
   */
  public String getReadId() {
    if (recordId != null) {
      return recordId;
    }
    return queryParams != null ? queryParams.get("id") : null;
  }

  /**
   * ETP-5009: whether this request is a {@code GET} by id (path or query-string {@code id}, see
   * {@link #getReadId}). The single definition shared by the list read predicates and by the
   * handlers that post-filter a single-record read; a blank id is a list read.
   *
   * @return {@code true} for a {@code GET} naming a non-blank id
   */
  public boolean isReadById() {
    if (!"GET".equals(httpMethod)) {
      return false;
    }
    String readId = getReadId();
    return readId != null && !readId.trim().isEmpty();
  }

  public JSONObject getRequestBody() {
    return requestBody;
  }

  public Map<String, String> getQueryParams() {
    return queryParams;
  }

  public Tab getAdTab() {
    return adTab;
  }

  public SFEntity getSfEntity() {
    return sfEntity;
  }

  public OBContext getObContext() {
    return obContext;
  }

  public NeoResponse getPreviousResult() {
    return previousResult;
  }

  public void setPreviousResult(NeoResponse previousResult) {
    this.previousResult = previousResult;
  }

  /**
   * @return the IMP-45 callout-vs-caller divergences recorded during this create, or {@code null}
   *     when the cascade recorded none
   */
  public JSONObject getSupersededDefaults() {
    return supersededDefaults;
  }

  /**
   * @param supersededDefaults the divergences recorded by the create-path callout cascade
   */
  public void setSupersededDefaults(JSONObject supersededDefaults) {
    this.supersededDefaults = supersededDefaults;
  }

  /**
   * Returns per-request state an earlier phase of this same request stored with {@link
   * #setAttribute}, typically a customization's {@code handle()} pre-hook handing a marker to its
   * own {@code afterHandle()}. See {@link #attributes}.
   *
   * @param key the attribute key, namespaced by the owning class
   * @return the value stored by an earlier phase of this same request, or {@code null}
   */
  public Object getAttribute(String key) {
    return attributes.get(key);
  }

  /**
   * Stores per-request state for a later phase of this same request. See {@link #attributes}.
   *
   * @param key the attribute key, namespaced by the owning class
   * @param value the value; {@code null} removes the key
   */
  public void setAttribute(String key, Object value) {
    if (value == null) {
      attributes.remove(key);
    } else {
      attributes.put(key, value);
    }
  }

  public NeoEndpointType getEndpointType() {
    return endpointType;
  }

  public String getFieldName() {
    return fieldName;
  }

  @Override
  public String toString() {
    return String.format("NeoContext{spec=%s, entity=%s, method=%s, id=%s, endpointType=%s}",
        specName, entityName, httpMethod, recordId, endpointType);
  }

  /**
   * Returns a new Builder instance for constructing a NeoContext.
   *
   * @return a new {@link Builder}
   */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * Builder for constructing {@link NeoContext} instances with a fluent API.
   */
  public static class Builder {
    private String specName;
    private String entityName;
    private String httpMethod;
    private String recordId;
    private JSONObject requestBody;
    private Map<String, String> queryParams;
    private Tab adTab;
    private SFEntity sfEntity;
    private OBContext obContext;
    private NeoResponse previousResult;
    private NeoEndpointType endpointType;
    private String fieldName;
    private boolean mcpOrigin;

    /**
     * Marks this context as MCP-originated and returns this builder. Defaults to {@code false},
     * so the REST dispatcher needs no change.
     *
     * @param mcpOrigin {@code true} when the MCP layer is building the context
     * @return this builder
     */
    public Builder mcpOrigin(boolean mcpOrigin) {
      this.mcpOrigin = mcpOrigin;
      return this;
    }

    /**
     * Sets the spec name and returns this builder.
     *
     * @param specName the spec name
     * @return this builder
     */
    public Builder specName(String specName) {
      this.specName = specName;
      return this;
    }

    /**
     * Sets the entity name and returns this builder.
     *
     * @param entityName the entity name
     * @return this builder
     */
    public Builder entityName(String entityName) {
      this.entityName = entityName;
      return this;
    }

    /**
     * Sets the HTTP method and returns this builder.
     *
     * @param httpMethod the HTTP method (GET, POST, PATCH, DELETE, etc.)
     * @return this builder
     */
    public Builder httpMethod(String httpMethod) {
      this.httpMethod = httpMethod;
      return this;
    }

    /**
     * Sets the record ID and returns this builder.
     *
     * @param recordId the record identifier
     * @return this builder
     */
    public Builder recordId(String recordId) {
      this.recordId = recordId;
      return this;
    }

    /**
     * Sets the request body and returns this builder.
     *
     * @param requestBody the JSON request body
     * @return this builder
     */
    public Builder requestBody(JSONObject requestBody) {
      this.requestBody = requestBody;
      return this;
    }

    /**
     * Sets the query parameters and returns this builder.
     *
     * @param queryParams map of query parameter names to values
     * @return this builder
     */
    public Builder queryParams(Map<String, String> queryParams) {
      this.queryParams = queryParams;
      return this;
    }

    /**
     * Sets the Etendo AD Tab and returns this builder.
     *
     * @param adTab the application dictionary tab
     * @return this builder
     */
    public Builder adTab(Tab adTab) {
      this.adTab = adTab;
      return this;
    }

    /**
     * Sets the Schema Forge entity and returns this builder.
     *
     * @param sfEntity the SF entity configuration
     * @return this builder
     */
    public Builder sfEntity(SFEntity sfEntity) {
      this.sfEntity = sfEntity;
      return this;
    }

    /**
     * Sets the Openbravo context and returns this builder.
     *
     * @param obContext the OB security/session context
     * @return this builder
     */
    public Builder obContext(OBContext obContext) {
      this.obContext = obContext;
      return this;
    }

    /**
     * Sets the previous pipeline result and returns this builder.
     *
     * @param previousResult the NeoResponse from a prior pipeline step
     * @return this builder
     */
    public Builder previousResult(NeoResponse previousResult) {
      this.previousResult = previousResult;
      return this;
    }

    /**
     * Sets the endpoint type and returns this builder.
     *
     * @param endpointType the NEO endpoint classification
     * @return this builder
     */
    public Builder endpointType(NeoEndpointType endpointType) {
      this.endpointType = endpointType;
      return this;
    }

    /**
     * Sets the field name (used in callout context) and returns this builder.
     *
     * @param fieldName the field name
     * @return this builder
     */
    public Builder fieldName(String fieldName) {
      this.fieldName = fieldName;
      return this;
    }

    /**
     * Builds and returns the {@link NeoContext} from the current builder state.
     *
     * @return a fully constructed NeoContext
     */
    public NeoContext build() {
      return new NeoContext(this);
    }
  }
}
