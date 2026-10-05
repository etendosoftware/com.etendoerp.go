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

import java.math.BigDecimal;
import java.math.RoundingMode;

import org.codehaus.jettison.json.JSONObject;

/**
 * Value rules for the amounts of a commercial line, shared by the line customizations that derive
 * those amounts themselves (ETP-5528): {@link OrderLineDiscountSupport} (sales order and sales
 * quotation lines) and {@link InvoiceLineAmountSupport} (sales invoice lines).
 *
 * <p><b>Plain utility, no entity knowledge.</b> It names no entity, no table and no property: it only
 * compares values. Each customization decides which keys are its amounts and calls this explicitly
 * (ETP-5415 T12). It is not a write-path compensation and nothing in a shared service calls it.
 *
 * <p><b>The stale rule.</b> On a create the body cannot say whether an amount was sent by the caller
 * or derived by the server earlier in the pipeline ({@code neo_create} runs the defaults cascade
 * before the pre-hook). {@link #isStaleAmount} judges it by value: absent, zero, or already equal
 * (at 2 decimals) to what the server derives. Any other value is the caller's and is kept.
 */
final class LineAmountSupport {

  /** The scale amounts are compared at: the one every commercial amount is shown and posted with. */
  static final int AMOUNT_SCALE = 2;

  private LineAmountSupport() {
  }

  /**
   * Whether {@code current} may be replaced by {@code derived}: it is absent, zero, not a number,
   * or equal to {@code derived} at {@link #AMOUNT_SCALE} decimals. Any other value was chosen by
   * the caller and must be kept.
   *
   * @param current the amount the body carries now; may be {@code null}
   * @param derived the amount the server derives for the same line
   */
  static boolean isStaleAmount(Object current, Object derived) {
    return isZero(current) || sameAmountAtScale(current, derived);
  }

  /** Equality of two amounts rounded to {@link #AMOUNT_SCALE}; {@code false} when either is not a number. */
  static boolean sameAmountAtScale(Object a, Object b) {
    BigDecimal x = decimal(a);
    BigDecimal y = decimal(b);
    return x != null && y != null && x.setScale(AMOUNT_SCALE, RoundingMode.HALF_UP)
        .compareTo(y.setScale(AMOUNT_SCALE, RoundingMode.HALF_UP)) == 0;
  }

  /** Absent, null, zero, or not a number all read as zero. */
  static boolean isZero(Object value) {
    BigDecimal decimal = decimal(value);
    return decimal == null || decimal.signum() == 0;
  }

  /** The value as a {@link BigDecimal}, or {@code null} when it is absent or not a number. */
  static BigDecimal decimal(Object value) {
    if (value == null || JSONObject.NULL.equals(value)) {
      return null;
    }
    try {
      return new BigDecimal(String.valueOf(value).trim());
    } catch (NumberFormatException e) {
      return null;
    }
  }
}
