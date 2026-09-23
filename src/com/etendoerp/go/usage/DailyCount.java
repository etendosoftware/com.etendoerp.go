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

package com.etendoerp.go.usage;

import java.util.Date;
import java.util.Objects;

/**
 * One counted value for a tenant, on a day, for a single resource. A day that produces no
 * {@code DailyCount} counts as zero — absence of a row is the zero.
 */
public final class DailyCount {

  private final String clientId;
  private final Date day;
  private final long quantity;

  /**
   * Creates one counted value for a tenant on a day.
   *
   * @param clientId the tenant this count belongs to
   * @param day the day counted; defensively copied
   * @param quantity the counted value
   */
  public DailyCount(String clientId, Date day, long quantity) {
    this.clientId = Objects.requireNonNull(clientId, "clientId");
    this.day = new Date(Objects.requireNonNull(day, "day").getTime());
    this.quantity = quantity;
  }

  /** @return the tenant this count belongs to */
  public String getClientId() {
    return clientId;
  }

  /** @return a defensive copy; {@code Date} is mutable */
  public Date getDay() {
    return new Date(day.getTime());
  }

  public long getQuantity() {
    return quantity;
  }

  @Override
  public String toString() {
    return "DailyCount[client=" + clientId + ", day=" + day + ", qty=" + quantity + ']';
  }
}
