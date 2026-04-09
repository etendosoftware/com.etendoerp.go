package com.etendoerp.go.schemaforge;

import java.util.List;

/**
 * Container for the three field lists produced by classifying OBUISEL selector fields:
 * grid display columns, searchable properties, and auxiliary output fields.
 */
class ObuiselFieldLists {
  final List<RichFieldMeta> gridFields;
  final List<String> searchableProps;
  final List<AuxFieldMeta> auxFields;

  ObuiselFieldLists(List<RichFieldMeta> gridFields, List<String> searchableProps,
      List<AuxFieldMeta> auxFields) {
    this.gridFields = gridFields;
    this.searchableProps = searchableProps;
    this.auxFields = auxFields;
  }
}
