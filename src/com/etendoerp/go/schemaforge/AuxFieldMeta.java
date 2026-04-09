package com.etendoerp.go.schemaforge;

/**
 * Metadata for an auxiliary output field in an OBUISEL selector.
 * Auxiliary fields are defined with isOutField=Y and a SUFFIX (e.g., "_LOC").
 * They provide extra data (like a default location ID) alongside the selected value.
 */
class AuxFieldMeta {
  final String suffix;              // e.g., "_LOC", "_CON"
  final String hqlAlias;            // e.g., "locationid" (from displayColumnAlias)
  final String name;                // display name of the field
  final String property;            // DAL property path (if available)

  AuxFieldMeta(String suffix, String hqlAlias, String name, String property) {
    this.suffix = suffix;
    this.hqlAlias = hqlAlias;
    this.name = name;
    this.property = property;
  }
}
