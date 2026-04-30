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
 * Container for the three field lists produced by classifying OBUISEL selector fields:
 * grid display columns, searchable properties, and auxiliary output fields.
 */
public class ObuiselFieldLists {
  public final List<RichFieldMeta> gridFields;
  public final List<String> searchableProps;
  public final List<AuxFieldMeta> auxFields;

  public ObuiselFieldLists(List<RichFieldMeta> gridFields, List<String> searchableProps,
      List<AuxFieldMeta> auxFields) {
    this.gridFields = gridFields;
    this.searchableProps = searchableProps;
    this.auxFields = auxFields;
  }
}
