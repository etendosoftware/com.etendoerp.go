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
 * Resolved target metadata for a selector.
 * Holds entity name, display/value properties, where clause,
 * and (for rich OBUISEL selectors) grid fields, searchable properties,
 * custom HQL, and auxiliary field descriptors.
 *
 * <p>Use the {@link Builder} for rich (OBUISEL) selectors that require all fields.
 * The 3-argument constructor covers simple selectors (TableDir, Table, Search).
 */
public class SelectorMeta {
  public final String entityName;
  public final String displayProperty;
  public final String whereClause;
  public final boolean isRich;
  public final boolean isCustomQuery;
  public final String valueProperty;
  public final List<RichFieldMeta> gridFields;
  public final List<String> searchableProperties;
  public final String customHql;
  public final String entityAlias;
  public final List<AuxFieldMeta> auxFields;

  /** Constructor for simple selectors (TableDir, Table, Search). */
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

  /** Private full constructor — use {@link Builder} for rich selectors. */
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

  /** Builder for rich (OBUISEL) selectors. */
  public static final class Builder {
    // Required
    private final String entityName;
    private final String displayProperty;

    // Optional / defaulted
    private String whereClause;
    private boolean isRich;
    private boolean isCustomQuery;
    private String valueProperty = "id";
    private List<RichFieldMeta> gridFields = new ArrayList<>();
    private List<String> searchableProperties = new ArrayList<>();
    private String customHql;
    private String entityAlias = "e";
    private List<AuxFieldMeta> auxFields = new ArrayList<>();

    public Builder(String entityName, String displayProperty) {
      this.entityName = entityName;
      this.displayProperty = displayProperty;
    }

    public Builder whereClause(String val) { this.whereClause = val; return this; }
    public Builder isRich(boolean val) { this.isRich = val; return this; }
    public Builder isCustomQuery(boolean val) { this.isCustomQuery = val; return this; }
    public Builder valueProperty(String val) { this.valueProperty = val; return this; }
    public Builder gridFields(List<RichFieldMeta> val) { this.gridFields = val; return this; }
    public Builder searchableProperties(List<String> val) { this.searchableProperties = val; return this; }
    public Builder customHql(String val) { this.customHql = val; return this; }
    public Builder entityAlias(String val) { this.entityAlias = val; return this; }
    public Builder auxFields(List<AuxFieldMeta> val) { this.auxFields = val; return this; }

    public SelectorMeta build() {
      return new SelectorMeta(this);
    }
  }
}
