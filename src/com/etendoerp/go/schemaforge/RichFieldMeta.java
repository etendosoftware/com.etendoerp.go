package com.etendoerp.go.schemaforge;

/**
 * Metadata for a single field in a rich (OBUISEL) selector grid.
 */
class RichFieldMeta {
  final String propertyKey;  // last segment of property path
  final String label;        // display name
  final String property;     // full DAL property path
  final long sortNo;

  RichFieldMeta(String propertyKey, String label, String property, long sortNo) {
    this.propertyKey = propertyKey;
    this.label = label;
    this.property = property;
    this.sortNo = sortNo;
  }
}
