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

package com.etendoerp.go.payment;

import com.etendoerp.go.featureflags.FeatureFlagContext;
import com.etendoerp.go.featureflags.GoFeatureFlags;

/**
 * ETP-5443 — the one gate of the demo-to-productive data transfer (flag
 * {@value GoFeatureFlags#FLAG_DEMO_DATA_TRANSFER}, off by default).
 *
 * <p>Every toggle point calls {@link #isEnabled()} and nothing else, so retiring the flag is a grep
 * for this class. With the flag off the servlet behaves as it did before ETP-5364: the checkout
 * selection is not recorded, a paid onboarding starts no transfer, and the status/retry endpoints
 * answer 404 "Unknown endpoint". The worker thread is created lazily by
 * {@link DemoDataTransferService}, so an instance with the flag off never starts one.
 *
 * <p>Evaluated with an account-less context on purpose: the endpoints are routed before any
 * credential is read, and a start and a status read that resolved the flag against different
 * identities could disagree about one tenant. Locally the flag is
 * {@code etendo.go.flags.demo-data-transfer} / {@code ETGO_FLAG_DEMO_DATA_TRANSFER}.
 */
public final class DemoDataTransferFlag {

  private DemoDataTransferFlag() {
  }

  /**
   * @return {@code true} only when the control plane positively resolves the flag to true
   */
  public static boolean isEnabled() {
    return GoFeatureFlags.isEnabled(GoFeatureFlags.FLAG_DEMO_DATA_TRANSFER,
        FeatureFlagContext.forAccount(null));
  }
}
