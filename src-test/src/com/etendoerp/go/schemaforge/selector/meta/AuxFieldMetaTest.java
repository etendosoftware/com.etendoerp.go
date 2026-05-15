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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link AuxFieldMeta}.
 */
class AuxFieldMetaTest {

  @Test
  @DisplayName("Constructor stores all fields correctly")
  void constructorStoresFields() {
    AuxFieldMeta meta = new AuxFieldMeta("_LOC", "loc", "Location", "location.name");
    assertEquals("_LOC", meta.suffix);
    assertEquals("loc", meta.hqlAlias);
    assertEquals("Location", meta.name);
    assertEquals("location.name", meta.property);
  }

  @Test
  @DisplayName("Null hqlAlias and property are accepted")
  void nullOptionalFieldsAccepted() {
    AuxFieldMeta meta = new AuxFieldMeta("_X", null, "Extra", null);
    assertEquals("_X", meta.suffix);
    assertNull(meta.hqlAlias);
    assertEquals("Extra", meta.name);
    assertNull(meta.property);
  }
}
