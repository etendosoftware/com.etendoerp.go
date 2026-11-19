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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
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
        "form-action 'self'", "frame-ancestors 'none'", "img-src 'self' blob: data:",
        "frame-src 'self' blob:", "report-uri /sws/go/csp-report", "report-to csp-endpoint"}) {
      assertTrue(csp.contains(d), d);
    }
    assertTrue(csp.contains("connect-src 'self' https://dataplane.rum.eu-west-3.amazonaws.com"));
  }

  @Test
  void omitsUpgradeInsecureRequestsInReportOnly() {
    assertFalse(CspPolicy.reportOnlyValue("/r", List.of()).contains("upgrade-insecure-requests"));
  }

  @Test
  void reportingEndpointsHeaderNamesTheReportToGroup() {
    assertEquals("csp-endpoint=\"/sws/go/csp-report\"",
        CspPolicy.reportingEndpointsValue(CspPolicy.REPORT_PATH));
    assertThrows(IllegalArgumentException.class, () -> CspPolicy.reportingEndpointsValue("/r;x"));
  }

  @Test
  void nullOriginListFailsClosed() {
    assertThrows(IllegalArgumentException.class, () -> CspPolicy.reportOnlyValue("/r", null));
  }

  @Test
  void versionedDefinitionMatchesTheRenderedPolicy() throws Exception {
    Path file = Files.exists(Paths.get("docs/security/csp-report-only.json"))
        ? Paths.get("docs/security/csp-report-only.json")
        : Paths.get("modules/com.etendoerp.go/docs/security/csp-report-only.json");
    JSONObject def = new JSONObject(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
    List<String> directives = new ArrayList<>();
    JSONArray arr = def.getJSONArray("directives");
    for (int i = 0; i < arr.length(); i++) {
      directives.add(arr.getString(i));
    }
    directives.add("report-uri " + def.getString("reportPath"));
    directives.add("report-to " + def.getString("reportingGroup"));
    assertEquals(String.join("; ", directives),
        CspPolicy.reportOnlyValue(def.getString("reportPath"), List.of()));
    assertEquals(def.getString("reportPath"), CspPolicy.REPORT_PATH);
    assertEquals(def.getString("reportingGroup"), CspPolicy.REPORTING_GROUP);
    assertEquals(def.getString("permissionsPolicy"), CspPolicy.PERMISSIONS_POLICY);
    assertEquals(def.getString("header"), CspPolicy.REPORT_ONLY_HEADER);
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
