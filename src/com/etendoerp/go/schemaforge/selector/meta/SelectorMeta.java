/*
 *************************************************************************
 * The contents of this file are subject to the Etendo License
 * (the "License"), you may not use this file except in compliance
 * with the License.
 * You may obtain a copy of the License at
 * https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * Software distributed under the License is distributed on an
 * "AS IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
 * implied. See the License for the specific language governing rights
 * and limitations under the License.
 * All portions are Copyright (C) 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 *************************************************************************
 */
package com.etendoerp.go.schemaforge.selector.meta;

import java.util.ArrayList;
import java.util.List;

/**
 * Resolved selector descriptor used by the NEO selector execution pipeline.
 */
public class SelectorMeta {
  /** Target DAL entity name queried by the selector. */
  public final String entityName;
  /** Display property used as the selector label or default ordering key. */
  public final String displayProperty;
  /** Optional HQL where clause coming from selector metadata. */
  public final String whereClause;
  /** Whether the selector is an OBUISEL rich selector. */
  public final boolean isRich;
  /** Whether the rich selector is backed by a custom HQL definition. */
  public final boolean isCustomQuery;
  /** Property that supplies the selected value; defaults to {@code id}. */
  public final String valueProperty;
  /** Visible grid field metadata for rich selectors. */
  public final List<RichFieldMeta> gridFields;
  /** Searchable property fragments used to build the suggestion-box filter. */
  public final List<String> searchableProperties;
  /** Full custom HQL definition for selectors marked as custom query. */
  public final String customHql;
  /** Entity alias used by the selector HQL. */
  public final String entityAlias;
  /** Auxiliary output field metadata exposed under {@code _aux}. */
  public final List<AuxFieldMeta> auxFields;

  /**
   * Create metadata for simple selectors such as TableDir, Table, or Search.
   *
   * @param entityName target DAL entity name
   * @param displayProperty display property used for labels and ordering
   * @param whereClause optional HQL filter from dictionary metadata
   */
  public SelectorMeta(String entityName, String displayProperty, String whereClause) {
    this.entityName = entityName;
    this.displayProperty = displayProperty;
    this.whereClause = whereClause;
    this.isRich = false;
    this.isCustomQuery = false;
    this.valueProperty = "id";
    this.gridFields = new ArrayList<>();
    this.searchableProperties = new ArrayList<>();
    this.customHql = null;
    this.entityAlias = "e";
    this.auxFields = new ArrayList<>();
  }

  private SelectorMeta(Builder builder) {
    this.entityName = builder.entityName;
    this.displayProperty = builder.displayProperty;
    this.whereClause = builder.whereClause;
    this.isRich = builder.isRich;
    this.isCustomQuery = builder.isCustomQuery;
    this.valueProperty = builder.valueProperty;
    this.gridFields = builder.gridFields;
    this.searchableProperties = builder.searchableProperties;
    this.customHql = builder.customHql;
    this.entityAlias = builder.entityAlias;
    this.auxFields = builder.auxFields;
  }

  /** Builder for rich OBUISEL selector metadata. */
  public static final class Builder {
    private final String entityName;
    private final String displayProperty;
    private String whereClause;
    private boolean isRich;
    private boolean isCustomQuery;
    private String valueProperty = "id";
    private List<RichFieldMeta> gridFields = new ArrayList<>();
    private List<String> searchableProperties = new ArrayList<>();
    private String customHql;
    private String entityAlias = "e";
    private List<AuxFieldMeta> auxFields = new ArrayList<>();

    /**
     * Create a builder for one rich selector.
     *
     * @param entityName target DAL entity name
     * @param displayProperty default display property
     */
    public Builder(String entityName, String displayProperty) {
      this.entityName = entityName;
      this.displayProperty = displayProperty;
    }

    /** Set the optional HQL where clause. */
    public Builder whereClause(String val) { this.whereClause = val; return this; }
    /** Mark whether the selector is rich. */
    public Builder isRich(boolean val) { this.isRich = val; return this; }
    /** Mark whether the selector is a custom query selector. */
    public Builder isCustomQuery(boolean val) { this.isCustomQuery = val; return this; }
    /** Override the property used as the selected value. */
    public Builder valueProperty(String val) { this.valueProperty = val; return this; }
    /** Supply visible grid field metadata. */
    public Builder gridFields(List<RichFieldMeta> val) { this.gridFields = val; return this; }
    /** Supply searchable property fragments. */
    public Builder searchableProperties(List<String> val) { this.searchableProperties = val; return this; }
    /** Supply the raw custom HQL definition. */
    public Builder customHql(String val) { this.customHql = val; return this; }
    /** Supply the HQL entity alias. */
    public Builder entityAlias(String val) { this.entityAlias = val; return this; }
    /** Supply auxiliary output metadata. */
    public Builder auxFields(List<AuxFieldMeta> val) { this.auxFields = val; return this; }

    /** Build the immutable selector metadata instance. */
    public SelectorMeta build() {
      return new SelectorMeta(this);
    }
  }
}
