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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Contract of the Report-Only CSP baseline and the Permissions-Policy.
 *
 * @covers com.etendoerp.go.security.CspPolicy
 */
class CspPolicyTest {

  @Test
  void baselineHasTheRequiredDirectives() {
    String csp = CspPolicy.reportOnlyValue("/sws/go/csp-report", List.of());
    for (String d : new String[] {"default-src 'self'", "object-src 'none'", "base-uri 'self'",
        "form-action 'self'", "frame-ancestors 'none'", "upgrade-insecure-requests",
        "report-uri /sws/go/csp-report"}) {
      assertTrue(csp.contains(d), d);
    }
    assertTrue(csp.contains("connect-src 'self' https://dataplane.rum.eu-west-3.amazonaws.com"));
  }

  @Test
  void neverEmitsPermissiveSources() {
    String csp = CspPolicy.reportOnlyValue("/r", List.of("https://glitchtip.example.com"));
    assertFalse(csp.contains("unsafe-eval"));
    assertFalse(csp.contains("*"));
    assertFalse(csp.matches("(?s).*script-src[^;]*unsafe-inline.*"));
    assertFalse(csp.contains("connect-src 'self' blob:"));
    assertTrue(csp.contains("https://glitchtip.example.com"));
  }

  @Test
  void failsClosedOnInvalidConnectSources() {
    for (String bad : new String[] {"*", "http://x.test", "https://x.test; script-src *",
        "https://*.x.test", "https://x.test/path", "javascript:alert(1)", "", "https://a b"}) {
      assertThrows(IllegalArgumentException.class,
          () -> CspPolicy.reportOnlyValue("/r", List.of(bad)), bad);
    }
    assertThrows(IllegalArgumentException.class,
        () -> CspPolicy.reportOnlyValue("/r;x", List.of()));
  }

  @Test
  void permissionsPolicyDeniesCameraGeolocationMicrophone() {
    assertEquals("camera=(), geolocation=(), microphone=()", CspPolicy.PERMISSIONS_POLICY);
  }
}
