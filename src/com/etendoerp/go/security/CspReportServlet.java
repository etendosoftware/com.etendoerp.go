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
package com.etendoerp.go.security;

import java.io.IOException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.openbravo.base.HttpBaseServlet;

/**
 * Unauthenticated collector for CSP violation reports, mapped to {@code /sws/go/csp-report}
 * (browsers send reports without credentials). It always answers 204 so it reveals nothing about
 * what was accepted; size and rate limits and redaction live in {@link CspReportCollector}.
 */
public class CspReportServlet extends HttpBaseServlet {

  private static final long serialVersionUID = 1L;
  private static final int PER_CLIENT_PER_MINUTE = 30;
  private static final int GLOBAL_PER_MINUTE = 600;

  private final transient CspReportCollector collector = new CspReportCollector(
      new CspReportRateLimiter(PER_CLIENT_PER_MINUTE, GLOBAL_PER_MINUTE, 60_000L,
          System::currentTimeMillis));

  @Override
  public void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
    try {
      collector.collect(request.getRemoteAddr(), request.getReader());
    } catch (IOException e) {
      // unreadable body: nothing to collect, and nothing about it may reach the client
    }
    response.setStatus(HttpServletResponse.SC_NO_CONTENT);
  }
}
