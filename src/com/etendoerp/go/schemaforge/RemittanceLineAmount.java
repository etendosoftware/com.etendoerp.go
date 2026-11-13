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
 * All portions are Copyright © 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.schemaforge;

import java.math.BigDecimal;

import org.apache.commons.lang3.StringUtils;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;

import com.etendoerp.go.schemaforge.util.NeoActionContract;

/**
 * ETP-6148 — the one rule for the amount a remittance takes from an invoice due («A remesar»),
 * shared by the line edit ({@link RemittanceLinesHandler}) and the picker sync
 * ({@link RemittanceDueItems}).
 *
 * <p>The amount must have the sign of the due and {@code 0 < |amount| ≤ |outstanding|}: a part of
 * the due, never more. For a line, the outstanding is the amount of the payment schedule detail it
 * points to — exactly what REM checks before processing ({@code REM_OutOfRangeAmount}), so a value
 * accepted here is one REM processes. For a due in the picker it is the sum of its open details.</p>
 */
final class RemittanceLineAmount {

  static final String MSG_INVALID = "ETGO_RemittanceLineAmountInvalid";
  static final String MSG_NOT_EDITABLE = "ETGO_RemittanceLineAmountNotEditable";
  static final String KEY_MAX = "max";

  private RemittanceLineAmount() {
  }

  /**
   * @param value a JSON value: a number or a numeric string
   * @return the amount, or {@code null} when {@code value} is not a number
   */
  static BigDecimal parse(Object value) {
    if (value == null || JSONObject.NULL.equals(value)) {
      return null;
    }
    String raw = StringUtils.trimToNull(value.toString());
    if (raw == null) {
      return null;
    }
    try {
      return new BigDecimal(raw);
    } catch (NumberFormatException e) {
      return null;
    }
  }

  /**
   * @param amount      the amount to remit, may be {@code null}
   * @param outstanding the outstanding amount of the due, may be {@code null}
   * @return whether {@code amount} is a valid part of {@code outstanding}
   */
  static boolean isValid(BigDecimal amount, BigDecimal outstanding) {
    if (amount == null || outstanding == null || outstanding.signum() == 0) {
      return false;
    }
    return amount.signum() == outstanding.signum() && amount.abs().compareTo(outstanding.abs()) <= 0;
  }

  /**
   * The refusal: 422 {@value #MSG_INVALID}, its {@code {0}} and {@code error.max} the outstanding.
   *
   * @param max the outstanding amount, or {@code null} when not known
   * @return the response
   */
  static NeoResponse invalid(BigDecimal max) {
    String shown = max != null ? max.toPlainString() : "-";
    NeoResponse refusal = NeoMessageText.error(NeoActionContract.SC_UNPROCESSABLE, MSG_INVALID,
        shown);
    JSONObject error = refusal.getBody() != null ? refusal.getBody().optJSONObject("error") : null;
    if (error != null && max != null) {
      try {
        error.put(KEY_MAX, max);
      } catch (JSONException e) {
        // The message already carries the value; the extra field is a convenience.
      }
    }
    return refusal;
  }
}
