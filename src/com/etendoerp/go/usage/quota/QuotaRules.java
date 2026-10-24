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

package com.etendoerp.go.usage.quota;

import java.math.BigInteger;

import org.apache.commons.lang3.StringUtils;

/**
 * ETP-5051 — the one comparison between a consumption and a quota. Every enforcement point gets its
 * verdict from {@link #verdict}; nothing else in the module compares a consumed quantity with an
 * included quantity.
 *
 * <p>The values mirror the check constraints of {@code ETGO_PLAN_QUOTA}: {@code ENFORCEMENT_MODE IN
 * ('warn', 'block', 'off')} and {@code CONSUMPTION_SOURCE IS NULL OR CONSUMPTION_SOURCE IN
 * ('aggregate', 'live', 'sum')}, where {@code sum} is a legacy alias read as {@code aggregate}
 * ({@link ConsumptionSource#fromColumn}).
 */
public final class QuotaRules {

  /** The quota is informational only: no verdict, and no consumption query when enforcing. */
  public static final String MODE_OFF = "off";
  /** Reaching the quota warns; nothing is refused. */
  public static final String MODE_WARN = "warn";
  /** Reaching the quota refuses the NEO creates that would consume more. */
  public static final String MODE_BLOCK = "block";

  private static final BigInteger PERCENT = BigInteger.valueOf(100L);

  private QuotaRules() {
  }

  /**
   * Whether a quota in this mode is evaluated at all. An unknown mode cannot be stored (check
   * constraint) and is treated as {@code off} rather than guessed at.
   *
   * @param mode the {@code ENFORCEMENT_MODE} of the quota row
   * @return {@code true} for {@code warn} and {@code block}
   */
  public static boolean isEnforced(String mode) {
    return MODE_WARN.equals(mode) || MODE_BLOCK.equals(mode);
  }

  /**
   * The verdict for a consumption, per the ETP-5051 contract.
   *
   * <ul>
   *   <li>{@code block}: DENY when {@code consumed >= included}; else WARN when a threshold is set
   *       and {@code consumed >= included * threshold / 100}; else ALLOW.</li>
   *   <li>{@code warn}: WARN when {@code consumed >= included} or when the threshold is reached;
   *       else ALLOW.</li>
   *   <li>{@code off} (or an unknown mode): {@code null}, not evaluated.</li>
   * </ul>
   *
   * <p>The threshold test is done in exact integer arithmetic ({@code consumed * 100 >= included *
   * threshold}), so a threshold is never rounded away and a large quantity cannot overflow.
   *
   * <p>A null included quantity cannot be stored ({@code INCLUDED_QTY} is required with no default,
   * §5.1). Should one reach here it is not evaluated: reading it as zero would cap the resource at
   * nothing, which is exactly the failure §5.1 warns about.
   *
   * @param mode the quota's enforcement mode
   * @param included the included quantity
   * @param warningThreshold percentage of {@code included}, 1..100, or null for no threshold
   * @param consumed the consumption over the current period
   * @return the verdict, or {@code null} when the quota is not evaluated
   */
  public static QuotaVerdict verdict(String mode, Long included, Long warningThreshold,
      long consumed) {
    if (!isEnforced(mode) || included == null) {
      return null;
    }
    boolean reached = consumed >= included;
    if (reached) {
      return MODE_BLOCK.equals(mode) ? QuotaVerdict.DENY : QuotaVerdict.WARN;
    }
    if (warningThreshold != null && BigInteger.valueOf(consumed).multiply(PERCENT)
        .compareTo(BigInteger.valueOf(included).multiply(BigInteger.valueOf(warningThreshold)))
        >= 0) {
      return QuotaVerdict.WARN;
    }
    return QuotaVerdict.ALLOW;
  }

  /**
   * Normalises a stored enforcement mode: trimmed, lower case; blank reads as {@code off}.
   *
   * @param raw the stored value
   * @return the normalised mode
   */
  public static String normaliseMode(String raw) {
    String mode = StringUtils.lowerCase(StringUtils.trimToNull(raw));
    return mode == null ? MODE_OFF : mode;
  }
}
