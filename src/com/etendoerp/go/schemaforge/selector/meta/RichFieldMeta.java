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

/** Metadata for one visible column in a rich OBUISEL selector grid. */
public class RichFieldMeta {
  /** Last segment of the DAL property path, used as the JSON key. */
  public final String propertyKey;
  /** Human-readable label shown in selector metadata. */
  public final String label;
  /** Full DAL property path used to fetch the value from the selected entity. */
  public final String property;
  /** Sort position from selector metadata. */
  public final long sortNo;

  /**
   * Create grid metadata for one selector field.
   *
   * @param propertyKey last segment of the property path
   * @param label display label
   * @param property full DAL property path
   * @param sortNo selector sort order
   */
  public RichFieldMeta(String propertyKey, String label, String property, long sortNo) {
    this.propertyKey = propertyKey;
    this.label = label;
    this.property = property;
    this.sortNo = sortNo;
  }
}
