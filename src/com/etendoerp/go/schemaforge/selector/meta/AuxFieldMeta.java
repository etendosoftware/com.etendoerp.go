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

/**
 * Metadata for an auxiliary output field in an OBUISEL selector.
 * Auxiliary fields are defined with isOutField=Y and a SUFFIX (e.g., "_LOC").
 * They provide extra data (like a default location ID) alongside the selected value.
 */
public class AuxFieldMeta {
  public final String suffix;              // e.g., "_LOC", "_CON"
  public final String hqlAlias;            // e.g., "locationid" (from displayColumnAlias)
  public final String name;                // display name of the field
  public final String property;            // DAL property path (if available)

  public AuxFieldMeta(String suffix, String hqlAlias, String name, String property) {
    this.suffix = suffix;
    this.hqlAlias = hqlAlias;
    this.name = name;
    this.property = property;
  }
}
