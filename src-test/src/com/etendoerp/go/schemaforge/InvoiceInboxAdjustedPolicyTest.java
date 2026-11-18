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
  void anAltaOfAProductMarksOnlyForAStoredValueChangeOrAReplacedLinkedOne() {
    // changed, previouslyLinked, nameDiffers, choiceDiffers -> marks; choiceDiffers is true only for
    // a field that had a stored value (filling an empty one is false: owner decision 2026-11-17)
    assertFalse(InvoiceInboxAdjustedPolicy.newProduct(true, false, false, false));
    assertTrue(InvoiceInboxAdjustedPolicy.newProduct(true, true, false, false));
    assertTrue(InvoiceInboxAdjustedPolicy.newProduct(true, false, true, false));
    assertTrue(InvoiceInboxAdjustedPolicy.newProduct(true, false, false, true));
    assertTrue(InvoiceInboxAdjustedPolicy.newProduct(true, true, true, true));
  }

  @Test
  void aProductAltaThatChangedNothingNeverMarks() {
    assertFalse(InvoiceInboxAdjustedPolicy.newProduct(false, false, false, false));
    assertFalse(InvoiceInboxAdjustedPolicy.newProduct(false, true, true, true));
  }

  @Test
  void theProductAltaTableIsExplicitForEveryCombination() {
    // changed, previouslyLinked, nameDiffers, choiceDiffers -> marks
    for (int bits = 0; bits < 16; bits++) {
      boolean changed = (bits & 8) != 0;
      boolean linked = (bits & 4) != 0;
      boolean name = (bits & 2) != 0;
      boolean choice = (bits & 1) != 0;
      boolean expected = changed && (linked || name || choice);

      assertEquals(expected, InvoiceInboxAdjustedPolicy.newProduct(changed, linked, name, choice),
          "changed=" + changed + " linked=" + linked + " name=" + name + " choice=" + choice);
    }
  }
}
