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
 * All portions are Copyright (C) 2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */
package com.etendoerp.go.security;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Builds the Content-Security-Policy-Report-Only value and exposes the Permissions-Policy value
 * (ADR-0002 D3/D4). The baseline is the strictest start; each extra {@code connect-src} origin
 * must come from validated deployment configuration and anything invalid fails closed instead of
 * producing a permissive policy.
 */
public final class CspPolicy {

  public static final String REPORT_ONLY_HEADER = "Content-Security-Policy-Report-Only";
  public static final String PERMISSIONS_POLICY_HEADER = "Permissions-Policy";
  public static final String PERMISSIONS_POLICY = "camera=(), geolocation=(), microphone=()";
  /** Hardcoded AWS RUM data plane (app-shell {@code lib/rum.js}). */
  public static final String AWS_RUM_ORIGIN = "https://dataplane.rum.eu-west-3.amazonaws.com";

  private static final Pattern HTTPS_ORIGIN = Pattern.compile(
      "https://[A-Za-z0-9]([A-Za-z0-9.-]*[A-Za-z0-9])?(:[0-9]{1,5})?");
  private static final Pattern REPORT_URI = Pattern.compile("/?[A-Za-z0-9._~/-]+");

  private CspPolicy() {
  }

  /**
   * @param reportUri same-origin path of the collector
   * @param extraConnectOrigins deployment-specific https origins (e.g. the Sentry/GlitchTip host)
   * @throws IllegalArgumentException when any input is not a plain path / https origin
   */
  public static String reportOnlyValue(String reportUri, List<String> extraConnectOrigins) {
    if (reportUri == null || !REPORT_URI.matcher(reportUri).matches()) {
      throw new IllegalArgumentException("Invalid CSP report-uri");
    }
    List<String> connect = new ArrayList<>();
    connect.add("'self'");
    connect.add(AWS_RUM_ORIGIN);
    for (String origin : extraConnectOrigins) {
      if (origin == null || !HTTPS_ORIGIN.matcher(origin).matches()) {
        throw new IllegalArgumentException("Invalid CSP connect-src origin: " + origin);
      }
      connect.add(origin);
    }
    return "default-src 'self'; object-src 'none'; base-uri 'self'; form-action 'self'; "
        + "frame-ancestors 'none'; connect-src " + String.join(" ", connect)
        + "; upgrade-insecure-requests; report-uri " + reportUri;
  }
}
