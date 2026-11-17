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

package com.etendoerp.go.schemaforge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * When an edit of the review panel marks the document {@code ADJUSTED} (ETP-6050, matrix of the
 * plan 9.3). Pure decisions over facts the caller measured.
 *
 * @covers com.etendoerp.go.schemaforge.InvoiceInboxAdjustedPolicy
 */
class InvoiceInboxAdjustedPolicyTest {

  @Test
  void aValueEditMarksOnlyWhenTheValueChanged() {
    assertTrue(InvoiceInboxAdjustedPolicy.valueEdit(true));
    assertFalse(InvoiceInboxAdjustedPolicy.valueEdit(false));
  }

  @Test
  void aSelectionMarksExceptReselectingTheLinkedId() {
    assertTrue(InvoiceInboxAdjustedPolicy.selection(false));
    assertFalse(InvoiceInboxAdjustedPolicy.selection(true));
  }

  @Test
  void anAltaOfAVendorMarksOnlyWhenModifiedOrReplacingALinkedOne() {
    assertFalse(InvoiceInboxAdjustedPolicy.newVendor(false, false));
    assertTrue(InvoiceInboxAdjustedPolicy.newVendor(false, true));
    assertTrue(InvoiceInboxAdjustedPolicy.newVendor(true, false));
    assertTrue(InvoiceInboxAdjustedPolicy.newVendor(true, true));
  }

  @Test
  void n1TheDecisionIsInForceAndAnAltaOfAProductAlwaysMarksWhenItChangedSomething() {
    assertTrue(InvoiceInboxAdjustedPolicy.NEW_PRODUCT_ALWAYS_MARKS);
    for (boolean linked : new boolean[] { false, true }) {
      for (boolean nameDiffers : new boolean[] { false, true }) {
        assertTrue(InvoiceInboxAdjustedPolicy.newProduct(true, linked, nameDiffers));
      }
    }
  }

  @Test
  void aProductAltaThatChangedNothingNeverMarksWhateverTheFlag() {
    for (boolean linked : new boolean[] { false, true }) {
      for (boolean nameDiffers : new boolean[] { false, true }) {
        assertFalse(InvoiceInboxAdjustedPolicy.newProduct(false, linked, nameDiffers));
      }
    }
  }

  @Test
  void theFlagFalseReadingIsThePlansMatrixAndIsWhatTheFlagSwitches() {
    // With the flag off (the plan's reading) only a changed name or replacing a linked product
    // marks. The flag is a compile-time constant, so the plan's table is checked as a function of
    // it: under N1 every row of the table below is true.
    boolean flag = InvoiceInboxAdjustedPolicy.NEW_PRODUCT_ALWAYS_MARKS;
    boolean[][] table = { { false, false }, { false, true }, { true, false }, { true, true } };
    for (boolean[] row : table) {
      boolean plan = row[0] || row[1];
      assertEquals(flag || plan, InvoiceInboxAdjustedPolicy.newProduct(true, row[0], row[1]));
    }
  }
}
