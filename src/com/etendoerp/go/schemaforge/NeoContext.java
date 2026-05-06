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
  private final NeoEndpointType endpointType;
  private final String fieldName;

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
