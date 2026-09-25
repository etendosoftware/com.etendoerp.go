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

/** The credential a request authenticated with (ETP-5455). */
public enum AuthScheme {
  /** The {@code __Host-go_session} cookie (ADR-0001). */
  COOKIE,
  /** A legacy {@code Authorization: Bearer} Etendo JWT, gated by {@code GoLegacyBearer}. */
  JWT,
  /** An opaque OAuth2 client-credentials token (MCP connectors); NOT gated by the legacy switch. */
  OAUTH2
}
