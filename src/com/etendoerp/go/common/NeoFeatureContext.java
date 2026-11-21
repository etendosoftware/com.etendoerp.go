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

package com.etendoerp.go.common;

import java.util.regex.Pattern;

/**
 * ETP-5646 — the bounded feature context a first-party NEO request may carry in
 * {@link #HEADER}: a stable key naming an actionable menu entry (for example
 * {@code window.sales-order}, {@code report.tax-report}).
 *
 * <p>It is attribution only. The user and the tenant of a request always come from the
 * authenticated session, never from this header, and a value that does not match
 * {@link #PATTERN} is dropped (never repaired), so log injection, unbounded cardinality and
 * payloads cannot ride on it.</p>
 */
public final class NeoFeatureContext {

  /** Request header the SPA sends the feature key in. */
  public static final String HEADER = "X-Etendo-Feature";
  /** Longest accepted key. */
  public static final int MAX_LENGTH = 80;

  private static final Pattern PATTERN =
      Pattern.compile("^(window|process|report|custom)\\.[a-z0-9][a-z0-9-]{0,62}$");

  private NeoFeatureContext() {
  }

  /**
   * Validates a feature key.
   *
   * @param value the raw header value
   * @return the key when well formed, otherwise {@code null}
   */
  public static String validate(String value) {
    if (value == null || value.length() > MAX_LENGTH || !PATTERN.matcher(value).matches()) {
      return null;
    }
    return value;
  }
}
