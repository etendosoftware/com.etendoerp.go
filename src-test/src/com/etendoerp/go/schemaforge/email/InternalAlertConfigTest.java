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

package com.etendoerp.go.schemaforge.email;

import static org.junit.Assert.*;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.util.*;
import org.junit.Test;
import org.mockito.MockedStatic;
import com.etendoerp.go.common.ConfigPropertyReader;

/** Hermetic server configuration tests; ambient production settings are never consulted. */
public class InternalAlertConfigTest {
  @Test public void defaultsEnableBothResultsForBuildsInSpanish() {
    try (MockedStatic<ConfigPropertyReader> reader = mockStatic(ConfigPropertyReader.class)) {
      reader.when(() -> ConfigPropertyReader.readConfigValue(anyString(), anyString(), anyString()))
          .thenAnswer(i -> i.getArgument(2));
      InternalAlertConfig config = InternalAlertConfig.fromRuntime();
      assertTrue(config.accepts(InternalAlertEvent.Status.OK));
      assertTrue(config.accepts(InternalAlertEvent.Status.ERROR));
      assertEquals(List.of("builds@etendo.software"), config.getRecipients());
      assertEquals("es_ES", config.getLanguage());
    }
  }
  @Test public void runtimeErrorOnlyFilterAndExplicitDisableAreRespected() {
    try (MockedStatic<ConfigPropertyReader> reader = mockStatic(ConfigPropertyReader.class)) {
      reader.when(() -> ConfigPropertyReader.readConfigValue(anyString(), anyString(), anyString()))
          .thenAnswer(i -> i.getArgument(0).equals("etendo.go.internalAlerts.statuses")
              ? "error" : i.getArgument(2));
      InternalAlertConfig config = InternalAlertConfig.fromRuntime();
      assertFalse(config.accepts(InternalAlertEvent.Status.OK));
      assertTrue(config.accepts(InternalAlertEvent.Status.ERROR));
      reader.when(() -> ConfigPropertyReader.readConfigValue(eq("etendo.go.internalAlerts.enabled"), anyString(), anyString())).thenReturn("false");
      assertFalse(InternalAlertConfig.fromRuntime().accepts(InternalAlertEvent.Status.ERROR));
    }
  }
  @Test public void malformedStatusIsRejectedRatherThanBroadcasting() {
    try (MockedStatic<ConfigPropertyReader> reader = mockStatic(ConfigPropertyReader.class)) {
      reader.when(() -> ConfigPropertyReader.readConfigValue(anyString(), anyString(), anyString()))
          .thenAnswer(i -> i.getArgument(0).equals("etendo.go.internalAlerts.statuses")
              ? "EVERYTHING" : i.getArgument(2));
      assertThrows(IllegalArgumentException.class, InternalAlertConfig::fromRuntime);
    }
  }
  @Test public void recipientConfigurationIsValidatedAndCopied() {
    List<String> recipients = new ArrayList<>(List.of(" ops@example.test "));
    Set<InternalAlertEvent.Status> statuses = EnumSet.allOf(InternalAlertEvent.Status.class);
    InternalAlertConfig config = new InternalAlertConfig(true, recipients, statuses, "en_US");
    recipients.clear(); statuses.clear();
    assertEquals(List.of("ops@example.test"), config.getRecipients());
    assertTrue(config.accepts(InternalAlertEvent.Status.OK));
    assertThrows(UnsupportedOperationException.class, () -> config.getRecipients().clear());
    assertThrows(IllegalArgumentException.class, () -> new InternalAlertConfig(true,
        List.of("bad-address"), EnumSet.allOf(InternalAlertEvent.Status.class), "es_ES"));
    assertThrows(IllegalArgumentException.class, () -> new InternalAlertConfig(true,
        List.of(), EnumSet.allOf(InternalAlertEvent.Status.class), "es_ES"));
    assertThrows(IllegalArgumentException.class, () -> new InternalAlertConfig(true,
        Collections.nCopies(11, "ops@example.test"), EnumSet.allOf(InternalAlertEvent.Status.class), "es_ES"));
  }
  @Test public void successOnlyFilterRejectsErrors() {
    InternalAlertConfig config = new InternalAlertConfig(true, List.of("ops@example.test"),
        EnumSet.of(InternalAlertEvent.Status.OK), "en_US");
    assertTrue(config.accepts(InternalAlertEvent.Status.OK));
    assertFalse(config.accepts(InternalAlertEvent.Status.ERROR));
  }
  @Test public void disabledAndEmptyResultFiltersNeverSend() {
    InternalAlertConfig off = new InternalAlertConfig(false, List.of("ops@example.test"),
        EnumSet.allOf(InternalAlertEvent.Status.class), "es_ES");
    assertFalse(off.accepts(InternalAlertEvent.Status.OK));
    assertFalse(off.accepts(InternalAlertEvent.Status.ERROR));
    assertThrows(IllegalArgumentException.class, () -> new InternalAlertConfig(true,
        List.of("ops@example.test"), EnumSet.noneOf(InternalAlertEvent.Status.class), "es_ES"));
  }
}
