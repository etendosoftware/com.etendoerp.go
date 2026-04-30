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
 * Auxiliary fields are defined with isOutField=Y and a suffix such as {@code _LOC}.
 */
public class AuxFieldMeta {
  /** Suffix appended to the selector field name in NEO responses. */
  public final String suffix;
  /** Lowercase alias exposed by custom HQL selectors when the value is not DAL-addressable. */
  public final String hqlAlias;
  /** Human-readable field name from selector metadata. */
  public final String name;
  /** DAL property path used to navigate the selected entity when available. */
  public final String property;

  /**
   * Create auxiliary field metadata.
   *
   * @param suffix response suffix appended to the selector field key
   * @param hqlAlias alias used in custom HQL result sets, or {@code null}
   * @param name display name of the auxiliary field
   * @param property DAL property path used for direct entity navigation, or {@code null}
   */
  public AuxFieldMeta(String suffix, String hqlAlias, String name, String property) {
    this.suffix = suffix;
    this.hqlAlias = hqlAlias;
    this.name = name;
    this.property = property;
  }
}
