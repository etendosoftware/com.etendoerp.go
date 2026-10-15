/* Etendo License. */
package com.etendoerp.go.payment;

import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;

/**
 * The failure codes of a provisioning attempt and their encoding in
 * {@code ETGO_CHECKOUT_REQUEST.FAILURE_REASON} (ETP-5548).
 *
 * <p>The code travels as a {@code CODE: message} prefix of the existing {@code FAILURE_REASON}
 * column rather than in a column of its own: the reason is an operational annotation of a row whose
 * lifecycle stays {@code PROVISIONING}, and rows written before codes existed simply read back as
 * {@link #CODE_PROVISIONING_FAILED}. The raw message is kept for operations only; the customer is
 * shown the code and its fixed {@link #safeDescription description}, never the message.
 *
 * <p>{@link CheckoutRequestStore} owns the lifecycle that consults these codes (retry decisions and
 * the fenced reclaim); this class only defines and reads them.
 */
public final class ProvisioningFailureReason {

  /** Failure code of a provisioning attempt whose cause is not known more precisely. */
  public static final String CODE_PROVISIONING_FAILED = "PROVISIONING_FAILED";
  /**
   * The account already has a productive environment with the requested company name (ETP-5548;
   * before it, the name belonged to another account's environment).
   */
  public static final String CODE_CLIENT_NAME_IN_USE = "CLIENT_NAME_IN_USE";

  /**
   * Failure codes a retry cannot fix. The checkout request fixes the company name, so a name
   * collision fails again on every attempt; offering a retry would only loop the customer.
   */
  private static final Set<String> NON_RETRYABLE_CODES = Set.of(CODE_CLIENT_NAME_IN_USE);

  /**
   * Customer-safe description of each failure code. The persisted reason keeps the raw cause for
   * operations; it can carry exception text, internal ids or SQL, so it never leaves the backend.
   */
  private static final Map<String, String> SAFE_DESCRIPTIONS = Map.of(
      CODE_CLIENT_NAME_IN_USE,
      "The account already has a productive environment with this company name",
      CODE_PROVISIONING_FAILED, "The environment setup did not complete");

  private static final Pattern CODE_PREFIX = Pattern.compile("^([A-Z][A-Z0-9_]*): ");

  private ProvisioningFailureReason() {
  }

  /**
   * Encodes a failure as {@code CODE: message}, the format {@link #codeOf} reads back.
   *
   * @param code stable failure code, {@code null} for {@link #CODE_PROVISIONING_FAILED}
   * @param message raw operational cause, kept for diagnostics only
   * @return the value to persist
   */
  public static String encode(String code, String message) {
    String safeCode = StringUtils.defaultIfBlank(code, CODE_PROVISIONING_FAILED);
    return safeCode + ": " + StringUtils.defaultIfBlank(StringUtils.normalizeSpace(message),
        "Provisioning did not complete");
  }

  /**
   * Reads the failure code back from a persisted reason written by {@link #encode}.
   *
   * @param failureReason persisted {@code FAILURE_REASON}
   * @return its failure code; {@code null} when there is no failure, and
   *     {@link #CODE_PROVISIONING_FAILED} for a reason written without one
   */
  public static String codeOf(String failureReason) {
    if (StringUtils.isBlank(failureReason)) {
      return null;
    }
    Matcher matcher = CODE_PREFIX.matcher(failureReason);
    return matcher.find() ? matcher.group(1) : CODE_PROVISIONING_FAILED;
  }

  /**
   * Maps a failure code to its fixed customer-facing description; unknown codes get the generic
   * one.
   *
   * @param failureCode a code from {@link #codeOf}
   * @return a fixed description of it that is safe to return to the customer
   */
  public static String safeDescription(String failureCode) {
    return SAFE_DESCRIPTIONS.getOrDefault(failureCode,
        SAFE_DESCRIPTIONS.get(CODE_PROVISIONING_FAILED));
  }

  /**
   * Tells whether a retry can fix the failure recorded in {@code failureReason}.
   *
   * @param failureReason persisted {@code FAILURE_REASON}
   * @return {@code false} for a deterministic failure (see {@link #NON_RETRYABLE_CODES})
   */
  static boolean isRetryable(String failureReason) {
    return !NON_RETRYABLE_CODES.contains(codeOf(failureReason));
  }
}
