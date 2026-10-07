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
 * All portions are Copyright (C) 2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */
package com.etendoerp.go.supportaccess.classic;

import java.util.Map;
import java.util.function.Supplier;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openbravo.client.application.FilterExpression;

import com.etendoerp.go.supportaccess.SupportAccessService;

/**
 * ETP-5351 — default of the "Duration" parameter of "Access as Support"
 * ({@code OB.getFilterExpression(...)} in its {@code DEFAULTVALUE}): the
 * {@code ETGO_SupportSessionDefaultMinutes} preference, snapped to the list options and capped
 * at {@code ETGO_SupportSessionMaxMinutes}.
 */
public class SupportDurationDefaultExpression implements FilterExpression {

  private static final Logger log = LogManager.getLogger(SupportDurationDefaultExpression.class);

  private final Supplier<SupportAccessService> serviceFactory;

  /** Production wiring. */
  public SupportDurationDefaultExpression() {
    this(SupportAccessService::new);
  }

  /**
   * Explicit wiring, for tests.
   *
   * @param serviceFactory creates the service that reads the preferences
   */
  SupportDurationDefaultExpression(Supplier<SupportAccessService> serviceFactory) {
    this.serviceFactory = serviceFactory;
  }

  @Override
  public String getExpression(Map<String, String> requestMap) {
    int preferred = SupportAccessService.FALLBACK_DEFAULT_MINUTES;
    int max = SupportAccessService.FALLBACK_MAX_MINUTES;
    try {
      SupportAccessService service = serviceFactory.get();
      preferred = service.getDefaultDurationMinutes();
      max = service.getMaxDurationMinutes();
    } catch (RuntimeException e) {
      log.warn("Could not read the support session duration preferences; using defaults", e);
    }
    return String.valueOf(pickOption(preferred, max));
  }

  /**
   * The list option to preselect: the longest one not above the preferred duration nor the
   * maximum; the shortest one when every option is above them.
   *
   * @param preferredMinutes the configured default
   * @param maxMinutes       the configured maximum
   * @return one of {@link SupportAccessClassicMetadata#durationOptions()}
   */
  static int pickOption(int preferredMinutes, int maxMinutes) {
    int cap = Math.min(preferredMinutes, maxMinutes);
    int[] options = SupportAccessClassicMetadata.durationOptions();
    int picked = options[0];
    for (int option : options) {
      if (option <= cap) {
        picked = option;
      }
    }
    return picked;
  }
}
