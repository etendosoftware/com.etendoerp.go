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

package com.etendoerp.go.oauth2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OAuth2ServletTest {

  @BeforeEach
  @AfterEach
  void resetRateLimiterState() {
    System.clearProperty(OAuth2Servlet.DCR_RATE_LIMIT_MAX_REQUESTS_PROPERTY);
    System.clearProperty(OAuth2Servlet.DCR_RATE_LIMIT_WINDOW_SECONDS_PROPERTY);
    OAuth2Servlet.resetDcrRegistrationRateLimiter();
  }

  @Test
  void normalizeDcrClientNameUsesDefaultAndTrimsWhitespace() {
    assertEquals("MCP Client", OAuth2Servlet.normalizeDcrClientName(null));
    assertEquals("MCP Client", OAuth2Servlet.normalizeDcrClientName("   "));
    assertEquals("Example Client", OAuth2Servlet.normalizeDcrClientName("  Example Client  "));
  }

  @Test
  void normalizeDcrClientNameRejectsInvalidValues() {
    IllegalArgumentException tooLong = assertThrows(IllegalArgumentException.class,
        () -> OAuth2Servlet.normalizeDcrClientName("a".repeat(256)));
    IllegalArgumentException controlChars = assertThrows(IllegalArgumentException.class,
        () -> OAuth2Servlet.normalizeDcrClientName("bad\nname"));

    assertTrue(tooLong.getMessage().contains("client_name"));
    assertTrue(controlChars.getMessage().contains("client_name"));
  }

  @Test
  void tryConsumeDcrRegistrationSlotBlocksAfterConfiguredLimit() {
    System.setProperty(OAuth2Servlet.DCR_RATE_LIMIT_MAX_REQUESTS_PROPERTY, "2");
    System.setProperty(OAuth2Servlet.DCR_RATE_LIMIT_WINDOW_SECONDS_PROPERTY, "60");

    assertTrue(OAuth2Servlet.tryConsumeDcrRegistrationSlot("127.0.0.1", 1_000L));
    assertTrue(OAuth2Servlet.tryConsumeDcrRegistrationSlot("127.0.0.1", 2_000L));
    assertFalse(OAuth2Servlet.tryConsumeDcrRegistrationSlot("127.0.0.1", 3_000L));
    assertTrue(OAuth2Servlet.tryConsumeDcrRegistrationSlot("127.0.0.2", 3_000L));
  }

  @Test
  void tryConsumeDcrRegistrationSlotAllowsRequestsAfterWindowExpires() {
    System.setProperty(OAuth2Servlet.DCR_RATE_LIMIT_MAX_REQUESTS_PROPERTY, "1");
    System.setProperty(OAuth2Servlet.DCR_RATE_LIMIT_WINDOW_SECONDS_PROPERTY, "60");

    assertTrue(OAuth2Servlet.tryConsumeDcrRegistrationSlot("127.0.0.1", 1_000L));
    assertFalse(OAuth2Servlet.tryConsumeDcrRegistrationSlot("127.0.0.1", 2_000L));
    assertTrue(OAuth2Servlet.tryConsumeDcrRegistrationSlot("127.0.0.1", 61_001L));
  }
}
