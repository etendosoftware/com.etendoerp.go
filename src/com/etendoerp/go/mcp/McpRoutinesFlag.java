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
package com.etendoerp.go.mcp;

import com.etendoerp.go.common.GoAccountResolver;
import com.etendoerp.go.featureflags.FeatureFlagContext;
import com.etendoerp.go.featureflags.GoFeatureFlags;

/**
 * ETP-6236 — the one gate of the {@value GoFeatureFlags#FLAG_MCP_ROUTINES} flag. Retiring the flag
 * is a grep for this class; never evaluate the key anywhere else.
 *
 * <p><b>Per account, per request.</b> Evaluated for the {@code ETGO_ACCOUNT} email of the request
 * user ({@link GoAccountResolver#currentAccountEmail()}); no account or a failed lookup reads
 * <b>off</b>. Never cached in a static: a targeting change applies to the next request.
 *
 * <p><b>Toggle points.</b> {@code ToolRegistry.generateTools} (whether {@code etendo_routine} is
 * listed) and {@code McpToolRouter} (whether a call to it is routed). With the flag off the MCP is
 * as before ETP-6236; the routine tables and their rows stay.
 */
public final class McpRoutinesFlag {

  private McpRoutinesFlag() {
  }

  /**
   * Whether {@code etendo_routine} is offered to the account of the user making the request. Never
   * throws: an unconfigured flag, an unreachable control plane and any evaluation error read
   * {@code false}.
   *
   * @return {@code true} only when the flag positively resolves true for that account
   */
  public static boolean isEnabled() {
    return GoFeatureFlags.isEnabled(GoFeatureFlags.FLAG_MCP_ROUTINES,
        FeatureFlagContext.forAccount(GoAccountResolver.currentAccountEmail()));
  }
}
