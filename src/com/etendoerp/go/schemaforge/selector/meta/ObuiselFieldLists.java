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

import java.util.List;

/**
 * Container for the three field lists produced while classifying OBUISEL selector fields.
 */
public class ObuiselFieldLists {
  /** Fields rendered as visible grid columns in the selector popup. */
  public final List<RichFieldMeta> gridFields;
  /** Property paths that participate in suggestion-box search. */
  public final List<String> searchableProps;
  /** Auxiliary output fields appended under {@code _aux}. */
  public final List<AuxFieldMeta> auxFields;

  /**
   * Create the grouped field lists for one selector.
   *
   * @param gridFields visible grid column metadata
   * @param searchableProps searchable property paths
   * @param auxFields auxiliary output metadata
   */
  public ObuiselFieldLists(List<RichFieldMeta> gridFields, List<String> searchableProps,
      List<AuxFieldMeta> auxFields) {
    this.gridFields = gridFields;
    this.searchableProps = searchableProps;
    this.auxFields = auxFields;
  }
}
