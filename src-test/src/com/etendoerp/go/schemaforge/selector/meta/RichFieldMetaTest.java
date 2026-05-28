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
 * Unit tests for {@link RichFieldMeta}.
 */
class RichFieldMetaTest {

  @Test
  @DisplayName("Constructor stores all fields correctly")
  void constructorStoresFields() {
    RichFieldMeta meta = new RichFieldMeta("name", "Name", "businessPartner.name", 10L);
    assertEquals("name", meta.propertyKey);
    assertEquals("Name", meta.label);
    assertEquals("businessPartner.name", meta.property);
    assertEquals(10L, meta.sortNo);
  }

  @Test
  @DisplayName("Null values are stored as-is")
  void nullFieldsAreAccepted() {
    RichFieldMeta meta = new RichFieldMeta(null, null, null, 0L);
    assertNull(meta.propertyKey);
    assertNull(meta.label);
    assertNull(meta.property);
    assertEquals(0L, meta.sortNo);
  }
}
