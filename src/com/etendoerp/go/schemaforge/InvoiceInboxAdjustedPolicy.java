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
   * Creating a new product from a line. ETP-6051 reversed the N1 decision (every
   * {@code newProduct} marks): the worker pre-fills the category and the unit, so creating the
   * product with what was pre-filled does not mark. The category and the unit are compared against
   * the stored base: the {@code new} block when the line already was a new product, else what the
   * worker would pre-fill for it (the printed unit or the default).
   *
   * <p>Owner decision (2026-11-17): a mandatory value the OCR could not define does not count as
   * adjusted when the user fills it. So {@code choiceDiffers} is true only when a field that HAD a
   * stored value now differs (another value, or cleared); an empty -> value fill never counts. The
   * name keeps the plain rule (it differs from the stored one), and replacing a linked product
   * marks.</p>
   *
   * @param changed          whether the operation changed anything stored
   * @param previouslyLinked whether the product was linked before
   * @param nameDiffers      whether the name differs from the stored new name (the line
   *                         description the first time)
   * @param choiceDiffers    whether the category or the unit, having had a stored value, now differs from it
   *                         (another value or cleared); filling an empty one does not count
   * @return whether it marks
   */
  static boolean newProduct(boolean changed, boolean previouslyLinked, boolean nameDiffers,
      boolean choiceDiffers) {
    return changed && (previouslyLinked || nameDiffers || choiceDiffers);
  }
}
