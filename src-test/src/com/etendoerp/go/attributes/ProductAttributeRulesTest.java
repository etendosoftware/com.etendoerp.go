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
 * All portions are Copyright (C) 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.attributes;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.math.BigDecimal;

import org.junit.Test;

/**
 * The pure rules of configurable product attributes (ETP-5977): which definition types the POC stores, which entity a
 * definition may target, and which value column a type accepts.
 *
 * @covers com.etendoerp.go.attributes.ProductAttributeRules
 */
public class ProductAttributeRulesTest {

  @Test
  public void textAndNumberAreTheSupportedTypes() {
    assertTrue(ProductAttributeRules.isSupportedType("T"));
    assertTrue(ProductAttributeRules.isSupportedType("N"));
  }

  @Test
  public void reservedAndUnknownTypesAreRefused() {
    for (String type : new String[] { "D", "L", "R", "X", "", null }) {
      assertFalse(String.valueOf(type), ProductAttributeRules.isSupportedType(type));
      assertNotNull(ProductAttributeRules.checkDefinitionType(type));
    }
  }

  @Test
  public void onlyProductIsAValidTargetAndTheNameIsCaseInsensitive() {
    assertNull(ProductAttributeRules.checkDefinitionTable("M_Product"));
    assertNull(ProductAttributeRules.checkDefinitionTable("m_product"));
    assertTrue(ProductAttributeRules.checkDefinitionTable("C_Order").contains("M_Product"));
  }

  @Test
  public void aTextDefinitionNeedsExactlyATextValue() {
    assertNull(ProductAttributeRules.checkValueColumns("T", "red", null));
    assertTrue(ProductAttributeRules.checkValueColumns("T", null, BigDecimal.ONE).contains("text value"));
    assertTrue(ProductAttributeRules.checkValueColumns("T", null, null).contains("text value"));
  }

  @Test
  public void aNumberDefinitionNeedsExactlyANumberValue() {
    assertNull(ProductAttributeRules.checkValueColumns("N", null, new BigDecimal("12.5")));
    assertNull(ProductAttributeRules.checkValueColumns("N", null, BigDecimal.ZERO));
    assertTrue(ProductAttributeRules.checkValueColumns("N", "x", null).contains("number value"));
    assertTrue(ProductAttributeRules.checkValueColumns("N", null, null).contains("number value"));
  }

  @Test
  public void bothValueColumnsSetIsRefusedWhateverTheType() {
    assertEquals("Only one of text value and number value may be set",
        ProductAttributeRules.checkValueColumns("T", "x", BigDecimal.ONE));
    assertEquals("Only one of text value and number value may be set",
        ProductAttributeRules.checkValueColumns("N", "x", BigDecimal.ONE));
  }

  @Test
  public void aValueForAReservedTypeIsRefusedAsAnUnsupportedType() {
    assertTrue(ProductAttributeRules.checkValueColumns("R", "x", null).contains("not supported"));
  }
}
