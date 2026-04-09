package com.etendoerp.go.schemaforge;

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
class SelectorMeta {
  final String entityName;
  final String displayProperty;
  final String whereClause;
  final boolean isRich;
  final boolean isCustomQuery;
  final String valueProperty;
  final List<RichFieldMeta> gridFields;
  final List<String> searchableProperties;
  final String customHql;
  final String entityAlias;
  final List<AuxFieldMeta> auxFields;

  /** Constructor for simple selectors (TableDir, Table, Search). */
  SelectorMeta(String entityName, String displayProperty, String whereClause) {
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
  static final class Builder {
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

    Builder(String entityName, String displayProperty) {
      this.entityName = entityName;
      this.displayProperty = displayProperty;
    }

    Builder whereClause(String val) { this.whereClause = val; return this; }
    Builder isRich(boolean val) { this.isRich = val; return this; }
    Builder isCustomQuery(boolean val) { this.isCustomQuery = val; return this; }
    Builder valueProperty(String val) { this.valueProperty = val; return this; }
    Builder gridFields(List<RichFieldMeta> val) { this.gridFields = val; return this; }
    Builder searchableProperties(List<String> val) { this.searchableProperties = val; return this; }
    Builder customHql(String val) { this.customHql = val; return this; }
    Builder entityAlias(String val) { this.entityAlias = val; return this; }
    Builder auxFields(List<AuxFieldMeta> val) { this.auxFields = val; return this; }

    SelectorMeta build() {
      return new SelectorMeta(this);
    }
  }
}
