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

import java.util.Map;

import javax.servlet.http.HttpServletResponse;

/**
 * The coded refusals of the bounce-pair actions (ETP-5581): {@code discardPair}, the
 * {@code bouncePair} groups of {@code applySuggestions} and {@code restorePair}. Each carries a
 * short {@code code} plus its {@code AD_MESSAGE} key in {@code error.messageKeys}; the shape is
 * {@link StatementLineDiscard#coded}. {@code LINE_DISCARDED} lives in {@link StatementLineDiscard}
 * because the reconcile paths answer it whatever the flag says.
 */
final class BankBounceErrors {

  /** The two lines do not have opposite signs. */
  static final String CODE_SIGN_MISMATCH = "SIGN_MISMATCH";
  /** The two lines do not have exactly the same amount. */
  static final String CODE_AMOUNT_MISMATCH = "AMOUNT_MISMATCH";
  /** A line of the pair is the bank movement of a real ERP payment. */
  static final String CODE_HAS_PAYMENT = "HAS_PAYMENT";
  /** A line is not in the pending pool: its statement is in draft, or it is part of a split group. */
  static final String CODE_LINE_NOT_PENDING = "LINE_NOT_PENDING";
  /**
   * The pair cannot be discarded at all: a line is reconciled, was not imported by the PSD2
   * synchronization, the bounce is outside the window, or the PSD2 discard is not installed.
   */
  static final String CODE_DISCARD_NOT_ALLOWED = "DISCARD_NOT_ALLOWED";
  /** The {@code bank-bounce-suggestions} flag is off for the account (403). */
  static final String CODE_SUGGESTIONS_OFF = "BOUNCE_SUGGESTIONS_OFF";

  /** {@code AD_MESSAGE} of every code. */
  private static final Map<String, String> MESSAGE_KEYS = Map.of(
      CODE_SIGN_MISMATCH, "ETGO_BounceSignMismatch",
      CODE_AMOUNT_MISMATCH, "ETGO_BounceAmountMismatch",
      CODE_HAS_PAYMENT, "ETGO_BounceHasPayment",
      CODE_LINE_NOT_PENDING, "ETGO_BounceLineNotPending",
      CODE_DISCARD_NOT_ALLOWED, "ETGO_BounceDiscardNotAllowed",
      CODE_SUGGESTIONS_OFF, "ETGO_BounceSuggestionsOff");

  /** Utility class: static members only, never instantiated. */
  private BankBounceErrors() {
  }

  /**
   * The 409 for a code of this class.
   *
   * @param code one of the {@code CODE_*} constants
   * @return the refusal
   */
  static NeoResponse conflict(String code) {
    return StatementLineDiscard.coded(HttpServletResponse.SC_CONFLICT, code,
        MESSAGE_KEYS.getOrDefault(code, code));
  }

  /**
   * The 403 answered when the flag is off for the account.
   *
   * @return the refusal
   */
  static NeoResponse suggestionsOff() {
    return StatementLineDiscard.coded(HttpServletResponse.SC_FORBIDDEN, CODE_SUGGESTIONS_OFF,
        MESSAGE_KEYS.get(CODE_SUGGESTIONS_OFF));
  }

  /**
   * The {@code AD_MESSAGE} key of a code.
   *
   * @param code one of the {@code CODE_*} constants
   * @return the key, or the code itself when unknown
   */
  static String messageKey(String code) {
    return MESSAGE_KEYS.getOrDefault(code, code);
  }
}
