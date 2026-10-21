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

package com.etendoerp.go.auth;

/**
 * The one wording of an OAuth2 scope refusal (ETP-5783): a {@code 403} whose message names the scope
 * the request needed, with the RFC 6750 §3.1 challenge. Shared by the authenticator and by the
 * checks that cannot be made from the path, so the two cannot drift apart.
 */
public final class ScopeRefusal {

  /** Response header carrying the challenge. */
  public static final String CHALLENGE_HEADER = "WWW-Authenticate";
  /** Challenge value: the token is valid, it lacks a scope. */
  public static final String CHALLENGE_VALUE = "Bearer error=\"insufficient_scope\"";

  private ScopeRefusal() {
  }

  /**
   * The client-safe text of an insufficient-scope refusal.
   *
   * @param requiredScope the scope the request needed
   * @return the client-safe refusal message
   */
  public static String message(String requiredScope) {
    return "Insufficient scope: this request requires " + requiredScope;
  }
}
