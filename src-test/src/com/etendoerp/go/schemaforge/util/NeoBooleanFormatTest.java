/*
 * *************************************************************************
 * The contents of this file are subject to the Etendo License
 * (the "License"), you may not use this file except in compliance with
 * the License.
 * You may obtain a copy of the License at
 * https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * Software distributed under the License is distributed on an
 * "AS IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
 * implied. See the License for the specific language governing rights
 * and limitations under the License.
 * All portions are Copyright © 2021–2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.schemaforge.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.openbravo.base.model.Property;

/**
 * Unit tests for {@link NeoBooleanFormat} — the canonical NEO boolean format (ETP-4793).
 *
 * <p>These tests pin a wire-contract guarantee, not a style choice. Etendo stores booleans as
 * {@code char(1) 'Y'/'N'} and the legacy producers that feed {@code /defaults} pass those raw
 * strings through, so the response used to mix JSON booleans and {@code "Y"}/{@code "N"} strings
 * for the same column. In JavaScript {@code "N"} is <b>truthy</b>, so an agent reading
 * {@code {"printDiscount": "N"}} concludes the opposite of what the ERP said.
 *
 * <p>The values below are the ones actually observed: on {@code sales-invoice/header}
 * {@code printDiscount} came back as {@code true} while {@code etvfacSentToVerifac} came back as
 * {@code "N"}; on {@code purchase-invoice/header} the two were the other way round. They appear
 * here as regression cases, not as illustrations.
 *
 * <p>The other half of the contract is what must <b>not</b> happen on the read path: an
 * unrecognised value has to yield {@code null} so the caller leaves it verbatim. Defaulting an
 * unknown string to {@code false} would state something the ERP never stated.
 */
class NeoBooleanFormatTest {

  /**
   * Builds a mock DAL property whose primitive Java type is {@code type}. A {@code null}
   * {@code type} models a property whose primitive object type is not resolvable.
   */
  private Property primitiveProperty(Class<?> type) {
    Property prop = mock(Property.class);
    when(prop.isPrimitive()).thenReturn(true);
    // getPrimitiveObjectType() returns Class<?> — doReturn avoids the wildcard compile error.
    doReturn(type).when(prop).getPrimitiveObjectType();
    return prop;
  }

  @Nested
  @DisplayName("toCanonical — strict read-path parse")
  class Canonical {

    @Test
    @DisplayName("the storage encoding 'Y'/'N' becomes a real boolean")
    void storageEncoding() {
      assertEquals(Boolean.TRUE, NeoBooleanFormat.toCanonical("Y"));
      assertEquals(Boolean.FALSE, NeoBooleanFormat.toCanonical("N"));
    }

    @Test
    @DisplayName("stringified booleans are accepted too")
    void stringifiedBooleans() {
      assertEquals(Boolean.TRUE, NeoBooleanFormat.toCanonical("true"));
      assertEquals(Boolean.FALSE, NeoBooleanFormat.toCanonical("false"));
    }

    @Test
    @DisplayName("case and surrounding whitespace are irrelevant")
    void caseAndWhitespaceInsensitive() {
      assertEquals(Boolean.TRUE, NeoBooleanFormat.toCanonical("y"));
      assertEquals(Boolean.FALSE, NeoBooleanFormat.toCanonical("n"));
      assertEquals(Boolean.TRUE, NeoBooleanFormat.toCanonical("TRUE"));
      assertEquals(Boolean.FALSE, NeoBooleanFormat.toCanonical("False"));
      assertEquals(Boolean.TRUE, NeoBooleanFormat.toCanonical("  Y  "));
    }

    @Test
    @DisplayName("an unrecognised value is refused, never guessed as false")
    void unrecognisedIsRefused() {
      assertNull(NeoBooleanFormat.toCanonical("Yes"));
      assertNull(NeoBooleanFormat.toCanonical("1"));
      assertNull(NeoBooleanFormat.toCanonical("0"));
      assertNull(NeoBooleanFormat.toCanonical("banana"));
      assertNull(NeoBooleanFormat.toCanonical(""));
      assertNull(NeoBooleanFormat.toCanonical("   "));
      assertNull(NeoBooleanFormat.toCanonical(null));
    }
  }

  @Nested
  @DisplayName("toLenientBoolean — write-path parse")
  class Lenient {

    @Test
    @DisplayName("recognised-true yields true")
    void recognisedTrue() {
      assertTrue(NeoBooleanFormat.toLenientBoolean("Y"));
      assertTrue(NeoBooleanFormat.toLenientBoolean("true"));
    }

    @Test
    @DisplayName("case no longer decides the outcome — the two coercers used to disagree on 'y'")
    void caseInsensitiveOnBothPaths() {
      assertTrue(NeoBooleanFormat.toLenientBoolean("y"));
      assertTrue(NeoBooleanFormat.toLenientBoolean("TRUE"));
    }

    @Test
    @DisplayName("anything not recognised as true stays false — historical write behaviour")
    void everythingElseIsFalse() {
      assertFalse(NeoBooleanFormat.toLenientBoolean("N"));
      assertFalse(NeoBooleanFormat.toLenientBoolean("false"));
      assertFalse(NeoBooleanFormat.toLenientBoolean("banana"));
      assertFalse(NeoBooleanFormat.toLenientBoolean(""));
      assertFalse(NeoBooleanFormat.toLenientBoolean(null));
    }
  }

  @Nested
  @DisplayName("isBooleanProperty — eligibility")
  class Eligibility {

    @Test
    @DisplayName("a primitive Boolean property is eligible")
    void booleanProperty() {
      assertTrue(NeoBooleanFormat.isBooleanProperty(primitiveProperty(Boolean.class)));
    }

    @Test
    @DisplayName("other primitive types are not")
    void otherPrimitiveTypes() {
      assertFalse(NeoBooleanFormat.isBooleanProperty(primitiveProperty(String.class)));
      assertFalse(NeoBooleanFormat.isBooleanProperty(primitiveProperty(Long.class)));
      assertFalse(NeoBooleanFormat.isBooleanProperty(primitiveProperty(java.util.Date.class)));
    }

    @Test
    @DisplayName("an unresolvable primitive type is not eligible")
    void nullPrimitiveType() {
      assertFalse(NeoBooleanFormat.isBooleanProperty(primitiveProperty(null)));
    }

    @Test
    @DisplayName("a non-primitive property (an FK) is not eligible")
    void nonPrimitiveProperty() {
      Property prop = mock(Property.class);
      when(prop.isPrimitive()).thenReturn(false);
      assertFalse(NeoBooleanFormat.isBooleanProperty(prop));
    }

    @Test
    @DisplayName("a null property is not eligible")
    void nullProperty() {
      assertFalse(NeoBooleanFormat.isBooleanProperty(null));
    }
  }
}
