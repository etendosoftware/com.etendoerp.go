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
 * All portions are Copyright © 2021–2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.schemaforge;

/**
 * Tells, from the request path alone, whether it is a NEO action route
 * ({@code /{spec}/{entity}/{recordId}/action/{name}}) — the routes that run a document action or
 * a process on a record (ETP-5783).
 *
 * <p>It asks {@link NeoServletSupport#parsePath} — the parser {@code NeoServlet} dispatches with —
 * rather than re-reading the path, so what the scope check calls an action and what the servlet
 * runs as one cannot drift apart. Structure only: it names no spec, entity or action.</p>
 */
public final class NeoActionPath {

  private NeoActionPath() {
  }

  /**
   * Whether a NEO path is an action route, decided by the same parser the servlet dispatches with.
   *
   * @param pathInfo the servlet path info ({@code /sws/neo} stripped); may be {@code null}
   * @return {@code true} when {@code NeoServlet} would dispatch it as an action route
   */
  public static boolean isActionPath(String pathInfo) {
    try {
      return NeoServletSupport.parsePath(pathInfo).isAction;
    } catch (RuntimeException e) {
      // The servlet answers an unparseable path 400 itself; for the scope question it is no action.
      return false;
    }
  }
}
