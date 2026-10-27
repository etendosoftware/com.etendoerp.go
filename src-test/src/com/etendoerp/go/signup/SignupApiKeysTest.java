/*
 *************************************************************************
 * The contents of this file are subject to the Etendo License
 * (the "License"), you may not use this file except in compliance
 * with the License.
 * You may obtain a copy of the License at
 * https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * Software distributed under the License is distributed on an
 * "AS IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
 * implied. See the License for the specific language governing rights
 * and limitations under the License.
 * All portions are Copyright (C) 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 *************************************************************************
 */
package com.etendoerp.go.signup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.HexFormat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Which channel a signup API key belongs to, from a configuration that holds only hashes.
 *
 * @covers com.etendoerp.go.signup.SignupApiKeys
 */
class SignupApiKeysTest {

  static String hashOf(String key) {
    return HexFormat.of().formatHex(SignupApiKeys.sha256(key));
  }

  @AfterEach
  void clearKeys() {
    System.clearProperty(SignupApiKeys.PROPERTY);
  }

  @Test
  void aConfiguredKeyNamesItsChannel() {
    System.setProperty(SignupApiKeys.PROPERTY,
        "web:" + hashOf("web-secret") + ", crm:" + hashOf("crm-secret"));

    assertEquals("web", SignupApiKeys.channelFor("web-secret"));
    assertEquals("crm", SignupApiKeys.channelFor(" crm-secret "));
  }

  @Test
  void theHashMayBeWrittenInUppercase() {
    System.setProperty(SignupApiKeys.PROPERTY, "agent:" + hashOf("agent-secret").toUpperCase());

    assertEquals("agent", SignupApiKeys.channelFor("agent-secret"));
  }

  @Test
  void anUnknownMissingOrBlankKeyHasNoChannel() {
    System.setProperty(SignupApiKeys.PROPERTY, "web:" + hashOf("web-secret"));

    assertNull(SignupApiKeys.channelFor("other-secret"));
    assertNull(SignupApiKeys.channelFor(null));
    assertNull(SignupApiKeys.channelFor("  "));
  }

  @Test
  void aMalformedEntryIsSkippedNotFatal() {
    System.setProperty(SignupApiKeys.PROPERTY,
        "broken, noHash:, short:abc, web:" + hashOf("web-secret"));

    assertEquals("web", SignupApiKeys.channelFor("web-secret"));
    assertNull(SignupApiKeys.channelFor("abc"));
  }

  @Test
  void theConfiguredKeyItselfIsNotAccepted() {
    // A configuration holds hashes: presenting the hash as the key must not work.
    String hash = hashOf("web-secret");
    System.setProperty(SignupApiKeys.PROPERTY, "web:" + hash);

    assertNull(SignupApiKeys.channelFor(hash));
  }
}
