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

package com.etendoerp.go.payment.invoicing;

import java.util.Locale;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;

/**
 * The invoicing state of a payment ({@code ETGO_PAYMENT.STATUS}) and the events the ERP reports
 * on it (ETP-6322).
 *
 * <pre>
 * PENDING_INVOICE ─(number)─▶ INVOICED ─▶ MAIL_SENT ─▶ COMPLETED (final)
 *   any state but COMPLETED ─error─▶ ERROR ─(the failed step, again)─▶ forward
 * </pre>
 *
 * <ul>
 *   <li>{@code invoiced}, {@code mail_sent} and {@code completed} need the invoice number: the ERP
 *       reports it first ({@code PATCH}).
 *   <li>{@code error} needs the step that failed and why, and is accepted until the payment is
 *       completed. From {@code ERROR} the ERP retries and reports the step it reaches.
 *   <li>{@code COMPLETED} is final.
 *   <li>Reporting a state the payment already has is accepted again (a retried call), and recorded.
 *   <li>A step back is refused: a payment whose mail was sent cannot go back to invoiced.
 * </ul>
 *
 * <p>The API speaks lower case ({@code pending_invoice}); the column holds upper case.
 */
public final class PaymentStatus {

  public static final String PENDING_INVOICE = "PENDING_INVOICE";
  public static final String INVOICED = "INVOICED";
  public static final String MAIL_SENT = "MAIL_SENT";
  public static final String COMPLETED = "COMPLETED";
  public static final String ERROR = "ERROR";

  /** {@code ETGO_PAYMENT_EVENT.EVENT_TYPE} of the row written when the payment is recorded. */
  public static final String EVENT_RECORDED = "RECORDED";

  /** Every status, as the {@code status} filter of the list accepts them. */
  static final Set<String> ALL = Set.of(PENDING_INVOICE, INVOICED, MAIL_SENT, COMPLETED, ERROR);

  /** The events the ERP may report through {@code POST /payments/{id}/events}. */
  static final Set<String> REPORTABLE = Set.of(INVOICED, MAIL_SENT, COMPLETED, ERROR);

  /** Why a reported event was refused. */
  public enum Refusal {
    /** The payment is completed; nothing more can be reported on it. */
    COMPLETED,
    /** The event needs the invoice number, which was not reported yet. */
    INVOICE_NUMBER_MISSING,
    /** The event would take the payment a step back. */
    INVALID_TRANSITION
  }

  private PaymentStatus() {
  }

  /**
   * Reads a status or an event as the API sends it.
   *
   * @param value e.g. {@code pending_invoice} or {@code mail_sent}, in any case
   * @return the column value, or {@code null} when it is none of {@link #ALL}
   */
  static String parse(String value) {
    String normalized = StringUtils.upperCase(StringUtils.trimToEmpty(value), Locale.ROOT);
    return ALL.contains(normalized) ? normalized : null;
  }

  /**
   * The API form of a status.
   *
   * @param status the column value
   * @return its lower-case form
   */
  static String toApi(String status) {
    return StringUtils.lowerCase(status, Locale.ROOT);
  }

  /**
   * Whether an event may be reported on a payment.
   *
   * @param current          the payment's status
   * @param hasInvoiceNumber whether its invoice number was reported
   * @param event            one of {@link #REPORTABLE}
   * @return why it is refused, or {@code null} when it is accepted; the payment then takes
   *     {@code event} as its status
   */
  static Refusal refusalFor(String current, boolean hasInvoiceNumber, String event) {
    if (COMPLETED.equals(current)) {
      return COMPLETED.equals(event) ? null : Refusal.COMPLETED;
    }
    if (ERROR.equals(event)) {
      return null;
    }
    if (!hasInvoiceNumber) {
      return Refusal.INVOICE_NUMBER_MISSING;
    }
    if (ERROR.equals(current) || PENDING_INVOICE.equals(current)) {
      return null;
    }
    return rank(event) >= rank(current) ? null : Refusal.INVALID_TRANSITION;
  }

  /** The order of the forward path; ERROR and PENDING_INVOICE are handled before. */
  private static int rank(String status) {
    switch (status) {
      case INVOICED:
        return 1;
      case MAIL_SENT:
        return 2;
      case COMPLETED:
        return 3;
      default:
        return 0;
    }
  }
}
