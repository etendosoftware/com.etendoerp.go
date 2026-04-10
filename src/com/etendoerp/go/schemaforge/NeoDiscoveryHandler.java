/*
 *************************************************************************
 * The contents of this file are subject to the Etendo License
 * (the "License"), you may not use this file except in compliance
 * with the License.
 * You may obtain a copy of the License at
 * https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * Software distributed under the License is distributed on an
 * "AS IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
 * implied. See the License for the specific language governing rights
 * and limitations under the License.
 * All portions are Copyright (C) 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 *************************************************************************
 */
package com.etendoerp.go.schemaforge;

import java.io.IOException;

import javax.servlet.http.HttpServletResponse;

import com.etendoerp.go.schemaforge.data.SFSpec;
import com.etendoerp.go.schemaforge.util.NeoDiscoveryHelper;

/**
 * Handles discovery and spec-describe endpoints for NEO Headless.
 *
 * <p>GET /sws/neo/             — list all specs the current user can access
 * <p>GET /sws/neo/{specName}  — describe a window spec with entities and fields
 */
class NeoDiscoveryHandler {

  private final NeoServlet servlet;

  NeoDiscoveryHandler(NeoServlet servlet) {
    this.servlet = servlet;
  }

  /**
   * Handle GET /sws/neo/ — list all active specs the current user can access.
   */
  void handleDiscovery(HttpServletResponse response) throws IOException {
    servlet.writeResponse(response, NeoDiscoveryHelper.handleDiscovery());
  }

  /**
   * Handle GET /sws/neo/{specName} — describe a window spec with entities and fields.
   */
  void handleSpecDescribe(HttpServletResponse response, SFSpec spec) throws IOException {
    servlet.writeResponse(response, NeoDiscoveryHelper.handleSpecDescribe(spec));
  }
}
