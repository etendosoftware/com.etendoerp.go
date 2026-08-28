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



package com.etendoerp.go.schemaforge.email.render;

import static org.junit.Assert.assertEquals;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.junit.Test;

/**
 * Unit tests for {@link ValidityWindow} (ETP-5003).
 */
public class ValidityWindowTest {

  private static final Instant NOW = Instant.parse("2026-08-25T15:24:52.906Z");

  @Test
  public void roundsUpTheWindowStampedMillisecondsEarlier() {
    // The exact case seen in an inbox: a seven-day invitation rendered as "valid for 6 days"
    // because the expiry had been stamped three milliseconds before the email was built.
    Instant expiresAt = NOW.plus(7, ChronoUnit.DAYS).minusMillis(3);

    assertEquals(7, ValidityWindow.daysUntil(NOW, expiresAt));
  }

  @Test
  public void reportsAnExactWindowUnchanged() {
    assertEquals(7, ValidityWindow.daysUntil(NOW, NOW.plus(7, ChronoUnit.DAYS)));
    assertEquals(1, ValidityWindow.daysUntil(NOW, NOW.plus(1, ChronoUnit.DAYS)));
  }

  @Test
  public void roundsAPartialDayUp() {
    assertEquals(2, ValidityWindow.daysUntil(NOW, NOW.plus(25, ChronoUnit.HOURS)));
  }

  @Test
  public void neverReportsLessThanOneDay() {
    assertEquals(1, ValidityWindow.daysUntil(NOW, NOW.plus(30, ChronoUnit.MINUTES)));
    assertEquals(1, ValidityWindow.daysUntil(NOW, NOW.minus(2, ChronoUnit.DAYS)));
  }

  @Test
  public void treatsAMissingExpiryAsOneDay() {
    assertEquals(1, ValidityWindow.daysUntil(NOW, null));
  }
}
