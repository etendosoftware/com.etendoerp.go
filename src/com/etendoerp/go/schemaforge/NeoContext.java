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

  @Override
  public String toString() {
    return String.format("NeoContext{spec=%s, entity=%s, method=%s, id=%s}",
        specName, entityName, httpMethod, recordId);
  }

  public static Builder builder() {
    return new Builder();
  }

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

    public Builder specName(String specName) {
      this.specName = specName;
      return this;
    }

    public Builder entityName(String entityName) {
      this.entityName = entityName;
      return this;
    }

    public Builder httpMethod(String httpMethod) {
      this.httpMethod = httpMethod;
      return this;
    }

    public Builder recordId(String recordId) {
      this.recordId = recordId;
      return this;
    }

    public Builder requestBody(JSONObject requestBody) {
      this.requestBody = requestBody;
      return this;
    }

    public Builder queryParams(Map<String, String> queryParams) {
      this.queryParams = queryParams;
      return this;
    }

    public Builder adTab(Tab adTab) {
      this.adTab = adTab;
      return this;
    }

    public Builder sfEntity(SFEntity sfEntity) {
      this.sfEntity = sfEntity;
      return this;
    }

    public Builder obContext(OBContext obContext) {
      this.obContext = obContext;
      return this;
    }

    public Builder previousResult(NeoResponse previousResult) {
      this.previousResult = previousResult;
      return this;
    }

    public NeoContext build() {
      return new NeoContext(this);
    }
  }
}
