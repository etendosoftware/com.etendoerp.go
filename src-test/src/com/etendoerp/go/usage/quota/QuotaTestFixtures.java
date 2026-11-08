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

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Date;

/**
 * Shared fixtures for the quota tests: a mutable clock, local-time dates and quota definitions.
 *
 * <p>Dates are built in the JVM's default zone on purpose. {@link QuotaPeriod#getWindowFrom()}
 * truncates to local midnight (as the usage job does), so a fixture written in UTC would move by a
 * day on a machine east or west of it.
 */
final class QuotaTestFixtures {

  static final String CLIENT = "TENANT-A";
  static final String OTHER_CLIENT = "TENANT-B";

  /** A period that does not start on a month boundary: the 17th to the 17th. */
  static final Date PERIOD_START = local(2026, 10, 17, 9, 30);
  static final Date PERIOD_END = local(2026, 11, 17, 9, 30);
  /** Inside the period. */
  static final Date NOW = local(2026, 10, 24, 12, 0);

  private QuotaTestFixtures() {
  }

  static Date local(int year, int month, int day, int hour, int minute) {
    return Date.from(LocalDateTime.of(year, month, day, hour, minute)
        .atZone(ZoneId.systemDefault()).toInstant());
  }

  static Date midnight(int year, int month, int day) {
    return local(year, month, day, 0, 0);
  }

  /** A quota on a declarative resource; mode, included quantity and threshold vary per test. */
  static QuotaDefinition.Builder quota(String key, String mode, Long included, Long threshold) {
    return QuotaDefinition.builder()
        .resourceId("RES-" + key)
        .resourceKey(key)
        .resourceName("Name of " + key)
        .unitLabel("units")
        .countingMode("D")
        .countedEntity("Invoice")
        .dateProperty("invoiceDate")
        .enforcementMode(mode)
        .includedQty(included)
        .warningThreshold(threshold);
  }

  static TenantQuotas tenant(String clientId, QuotaDefinition... quotas) {
    return new TenantQuotas(clientId, "month", PERIOD_START, PERIOD_END, false,
        Arrays.asList(quotas));
  }

  static TenantQuotas tenantWithPeriod(String clientId, Date start, Date end, boolean closed,
      QuotaDefinition... quotas) {
    return new TenantQuotas(clientId, "month", start, end, closed, Arrays.asList(quotas));
  }

  /** A clock whose instant the test moves by hand. */
  static final class MutableClock extends Clock {
    private Instant instant;

    MutableClock(Date now) {
      this.instant = now.toInstant();
    }

    void set(Date now) {
      this.instant = now.toInstant();
    }

    void advanceMillis(long millis) {
      this.instant = instant.plusMillis(millis);
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return instant;
    }
  }
}
