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

import com.etendoerp.go.schemaforge.data.BillingResource;

/**
 * What a {@link UsageResourceCounter} is asked to count: one resource over a half-open day
 * range, optionally narrowed to a single tenant.
 *
 * <p>A null {@link #getClientId()} means "every tenant" — the strategy is then expected to
 * return one {@link DailyCount} per tenant per day that has a value.
 */
public final class UsageCountRequest {

  private final BillingResource resource;
  private final Date from;
  private final Date to;
  private final String clientId;

  /**
   * Creates a request to count one resource over an inclusive day range.
   *
   * @param resource the billing resource to count
   * @param from inclusive lower bound; defensively copied
   * @param to inclusive upper bound; defensively copied
   * @param clientId the tenant to count for, or null for every tenant
   */
  public UsageCountRequest(BillingResource resource, Date from, Date to, String clientId) {
    this.resource = Objects.requireNonNull(resource, "resource");
    this.from = new Date(Objects.requireNonNull(from, "from").getTime());
    this.to = new Date(Objects.requireNonNull(to, "to").getTime());
    this.clientId = clientId;
  }

  public BillingResource getResource() {
    return resource;
  }

  /** @return inclusive lower bound; a defensive copy */
  public Date getFrom() {
    return new Date(from.getTime());
  }

  /** @return exclusive upper bound; a defensive copy */
  public Date getTo() {
    return new Date(to.getTime());
  }

  /** @return the tenant to narrow to, or null to count every tenant */
  public String getClientId() {
    return clientId;
  }

  public boolean isAllTenants() {
    return clientId == null;
  }
}
