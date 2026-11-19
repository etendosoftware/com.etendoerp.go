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
 * Marks the thread while the customer portal sends a quotation-decision email (ETP-6209 Part C):
 * the confirmation to the customer and the notice to the seller. Both contracts sit in the same
 * registry the {@code /sws/neo/email-contracts/{contract}/send} endpoint dispatches to; without this
 * caller check any signed-in tenant user could send them by naming them. Set only by
 * {@link PortalDecisionService}, after its own scope and state checks — the
 * {@link PortalRenewalScope} pattern.
 */
public final class PortalDecisionScope {

  private static final ThreadLocal<Boolean> ACTIVE = new ThreadLocal<>();

  private PortalDecisionScope() {
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
