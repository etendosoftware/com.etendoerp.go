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
 * Metadata for a single field in a rich (OBUISEL) selector grid.
 */
public class RichFieldMeta {
  public final String propertyKey;  // last segment of property path
  public final String label;        // display name
  public final String property;     // full DAL property path
  public final long sortNo;

  public RichFieldMeta(String propertyKey, String label, String property, long sortNo) {
    this.propertyKey = propertyKey;
    this.label = label;
    this.property = property;
    this.sortNo = sortNo;
  }
}
