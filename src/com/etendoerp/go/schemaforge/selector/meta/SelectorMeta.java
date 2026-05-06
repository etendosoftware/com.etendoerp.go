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

    /**
     * Set the optional HQL where clause.
     *
     * @param val HQL filter clause from selector metadata
     * @return this builder
     */
    public Builder whereClause(String val) { this.whereClause = val; return this; }

    /**
     * Mark whether the selector is rich.
     *
     * @param val {@code true} for a rich selector
     * @return this builder
     */
    public Builder isRich(boolean val) { this.isRich = val; return this; }

    /**
     * Mark whether the selector is backed by custom HQL.
     *
     * @param val {@code true} when the selector uses custom query mode
     * @return this builder
     */
    public Builder isCustomQuery(boolean val) { this.isCustomQuery = val; return this; }

    /**
     * Override the property used as the selected value.
     *
     * @param val DAL property path used as the selector value
     * @return this builder
     */
    public Builder valueProperty(String val) { this.valueProperty = val; return this; }

    /**
     * Supply visible grid field metadata.
     *
     * @param val grid field metadata in display order
     * @return this builder
     */
    public Builder gridFields(List<RichFieldMeta> val) { this.gridFields = val; return this; }

    /**
     * Supply searchable property fragments.
     *
     * @param val searchable property fragments used for suggestion-box filtering
     * @return this builder
     */
    public Builder searchableProperties(List<String> val) { this.searchableProperties = val; return this; }

    /**
     * Supply the raw custom HQL definition.
     *
     * @param val custom selector HQL
     * @return this builder
     */
    public Builder customHql(String val) { this.customHql = val; return this; }

    /**
     * Supply the HQL entity alias.
     *
     * @param val selector HQL alias
     * @return this builder
     */
    public Builder entityAlias(String val) { this.entityAlias = val; return this; }

    /**
     * Supply auxiliary output metadata.
     *
     * @param val auxiliary output field metadata
     * @return this builder
     */
    public Builder auxFields(List<AuxFieldMeta> val) { this.auxFields = val; return this; }

    /**
     * Build the immutable selector metadata instance.
     *
     * @return resolved selector metadata
     */
    public SelectorMeta build() {
      return new SelectorMeta(this);
    }
  }
}
