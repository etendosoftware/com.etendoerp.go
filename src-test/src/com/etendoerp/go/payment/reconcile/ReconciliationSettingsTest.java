/*
 * *************************************************************************
 * Etendo License. See https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * *************************************************************************
 */
package com.etendoerp.go.payment.reconcile;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mockStatic;

import java.util.Map;

import org.junit.Test;
import org.mockito.MockedStatic;

import com.etendoerp.go.common.GoRuntimeProperties;

/**
 * Specs for {@link ReconciliationSettings}: the defaults of {@code docs/billing-reconciliation.md}
 * §4 and the floors that keep a misconfiguration from making the reconciliation unsafe — above
 * all the 48 h minimum of the abandoned window, enforced even when a lower value is configured.
 *
 * @covers com.etendoerp.go.payment.reconcile.ReconciliationSettings
 */
public class ReconciliationSettingsTest {

  private static final double DELTA = 1e-9;

  @Test
  public void defaultsAreTheDesignValues() {
    ReconciliationSettings settings = ReconciliationSettings.defaults();

    assertEquals(60, settings.getIntervalMinutes());
    assertEquals(50, settings.getBatchSize());
    assertEquals(48, settings.getAbandonedHours());
    assertEquals(15, settings.getPaidGraceMinutes());
    assertEquals(60, settings.getReceivedStaleMinutes());
    assertEquals(0.25d, settings.getAbsentMaxShare(), DELTA);
    assertEquals(5, settings.getMaxProvisioningAttempts());
  }

  @Test
  public void anAbandonedWindowBelowFortyEightHoursIsRaisedToFortyEight() {
    ReconciliationSettings settings = new ReconciliationSettings(60, 50, 12, 15, 60, 0.25d, 5);

    assertEquals(ReconciliationSettings.MIN_ABANDONED_HOURS, settings.getAbandonedHours());
  }

  @Test
  public void anAbandonedWindowAboveTheMinimumIsKept() {
    assertEquals(72, new ReconciliationSettings(60, 50, 72, 15, 60, 0.25d, 5)
        .getAbandonedHours());
  }

  @Test
  public void theOtherTunablesHaveFloorsToo() {
    ReconciliationSettings settings = new ReconciliationSettings(0, 0, 0, 0, 0, 0.25d, 0);

    assertEquals(5, settings.getIntervalMinutes());
    assertEquals(1, settings.getBatchSize());
    assertEquals(1, settings.getPaidGraceMinutes());
    assertEquals(5, settings.getReceivedStaleMinutes());
    assertEquals(1, settings.getMaxProvisioningAttempts());
  }

  @Test
  public void anAbsentShareOutsideZeroToOneFallsBackToTheDefault() {
    assertEquals(0.25d, new ReconciliationSettings(60, 50, 48, 15, 60, -0.1d, 5)
        .getAbsentMaxShare(), DELTA);
    assertEquals(0.25d, new ReconciliationSettings(60, 50, 48, 15, 60, 1.5d, 5)
        .getAbsentMaxShare(), DELTA);
    assertEquals(0d, new ReconciliationSettings(60, 50, 48, 15, 60, 0d, 5)
        .getAbsentMaxShare(), DELTA);
  }

  @Test
  public void fromConfigurationReadsEveryPropertyAndStillEnforcesTheAbandonedMinimum() {
    Map<String, Integer> configured = Map.of(
        ReconciliationSettings.INTERVAL_MINUTES_PROPERTY, 30,
        ReconciliationSettings.BATCH_SIZE_PROPERTY, 7,
        ReconciliationSettings.ABANDONED_HOURS_PROPERTY, 24,
        ReconciliationSettings.PAID_GRACE_MINUTES_PROPERTY, 20,
        ReconciliationSettings.RECEIVED_STALE_MINUTES_PROPERTY, 90,
        ReconciliationSettings.MAX_ATTEMPTS_PROPERTY, 3);
    try (MockedStatic<GoRuntimeProperties> properties = mockStatic(GoRuntimeProperties.class)) {
      properties.when(() -> GoRuntimeProperties.readInt(anyString(), anyString(), anyInt()))
          .thenAnswer(call -> configured.getOrDefault(call.getArgument(0, String.class),
              call.getArgument(2, Integer.class)));
      properties.when(() -> GoRuntimeProperties.readValue(
          eq(ReconciliationSettings.ABSENT_MAX_SHARE_PROPERTY), anyString(), anyString()))
          .thenReturn(" 0.4 ");

      ReconciliationSettings settings = ReconciliationSettings.fromConfiguration();

      assertEquals(30, settings.getIntervalMinutes());
      assertEquals(7, settings.getBatchSize());
      assertEquals("24 h is configured, 48 h is the floor", 48, settings.getAbandonedHours());
      assertEquals(20, settings.getPaidGraceMinutes());
      assertEquals(90, settings.getReceivedStaleMinutes());
      assertEquals(0.4d, settings.getAbsentMaxShare(), DELTA);
      assertEquals(3, settings.getMaxProvisioningAttempts());
    }
  }

  @Test
  public void aMalformedOrBlankShareFallsBackToTheDefault() {
    for (String raw : new String[] { "a quarter", "  " }) {
      try (MockedStatic<GoRuntimeProperties> properties = mockStatic(GoRuntimeProperties.class)) {
        properties.when(() -> GoRuntimeProperties.readInt(anyString(), anyString(), anyInt()))
            .thenAnswer(call -> call.getArgument(2, Integer.class));
        properties.when(() -> GoRuntimeProperties.readValue(anyString(), anyString(),
            anyString())).thenReturn(raw);

        assertEquals(raw, 0.25d, ReconciliationSettings.fromConfiguration().getAbsentMaxShare(),
            DELTA);
      }
    }
  }
}
