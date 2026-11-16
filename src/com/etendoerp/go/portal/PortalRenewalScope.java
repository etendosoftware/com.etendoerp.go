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

package com.etendoerp.go.portal;

import java.util.function.Supplier;

/**
 * Marks the current thread as running a portal link renewal (ETP-6209).
 *
 * <p>The {@code portal-link-renewal} email contract accepts a command only inside this scope. The
 * contract is registered in the same registry the {@code /sws/neo/email-contracts/{contract}/send}
 * endpoint dispatches to, so without this marker any signed-in tenant user could ask for a renewal
 * email by naming the contract. The marker is set by {@link PortalLinkService#renew} alone, after
 * it has validated the expired link and applied every blocking check and limit.
 */
public final class PortalRenewalScope {

  private static final ThreadLocal<Boolean> ACTIVE = new ThreadLocal<>();

  private PortalRenewalScope() {
  }

  /**
   * @return {@code true} while {@link #call} is running on this thread
   */
  public static boolean isActive() {
    return Boolean.TRUE.equals(ACTIVE.get());
  }

  static <T> T call(Supplier<T> body) {
    ACTIVE.set(Boolean.TRUE);
    try {
      return body.get();
    } finally {
      ACTIVE.remove();
    }
  }
}
