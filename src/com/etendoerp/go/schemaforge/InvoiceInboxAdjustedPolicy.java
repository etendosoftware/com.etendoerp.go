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

package com.etendoerp.go.schemaforge;

/**
 * The one place that decides when an edit of the review panel marks the document {@code ADJUSTED}
 * (ETP-6050, matrix of the plan 9.3). The flag is sticky: only ever set, never cleared, so an
 * adjustment that is later reverted still counts ("se activa en el primer ajuste del usuario
 * después de la extracción y no se desactiva").
 *
 * <p>Every method takes facts the caller measured against the stored document (what changed, what
 * was linked before) and answers whether the operation marks. An operation that changes nothing
 * stored is a no-op that writes nothing and never marks, whatever these methods say.</p>
 */
final class InvoiceInboxAdjustedPolicy {

  /**
   * N1, decided by the product owner on 2026-11-13: choosing the category and/or the unit of a NEW
   * product counts as modifying the alta. Neither is extracted by the OCR, so the user always
   * chooses them, and therefore {@code newProduct} always marks. Flip this to {@code false} to go
   * back to the plan's reading (only a changed name, or replacing a linked product, marks).
   */
  static final boolean NEW_PRODUCT_ALWAYS_MARKS = true;

  private InvoiceInboxAdjustedPolicy() {
  }

  /**
   * A header or line value (number, date, description, tax rate, discount, add or remove a line).
   *
   * @param changed whether the value differs from the stored one (compared at the stored scale)
   * @return whether it marks
   */
  static boolean valueEdit(boolean changed) {
    return changed;
  }

  /**
   * Choosing an existing vendor or product, among the candidates, replacing a linked one or
   * replacing a none/new one.
   *
   * @param alreadyLinkedToIt whether that very id is already the linked one
   * @return whether it marks
   */
  static boolean selection(boolean alreadyLinkedToIt) {
    return !alreadyLinkedToIt;
  }

  /**
   * Creating a new vendor from the extracted data. Unmodified (from ambiguous, none or new) it does
   * not mark: the alta follows the extraction. It marks when any field differs from the extracted
   * value, or when it replaces an auto-linked vendor.
   *
   * @param previouslyLinked whether the vendor was linked before
   * @param anyFieldDiffers  whether any given field differs from the stored extracted value
   * @return whether it marks
   */
  static boolean newVendor(boolean previouslyLinked, boolean anyFieldDiffers) {
    return previouslyLinked || anyFieldDiffers;
  }

  /**
   * Creating a new product from a line. See {@link #NEW_PRODUCT_ALWAYS_MARKS}.
   *
   * @param changed          whether the operation changed anything stored
   * @param previouslyLinked whether the product was linked before
   * @param nameDiffers      whether the name differs from the stored new name (the line
   *                         description the first time)
   * @return whether it marks
   */
  static boolean newProduct(boolean changed, boolean previouslyLinked, boolean nameDiffers) {
    return changed && (NEW_PRODUCT_ALWAYS_MARKS || previouslyLinked || nameDiffers);
  }
}
