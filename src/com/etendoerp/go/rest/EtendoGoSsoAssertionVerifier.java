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
package com.etendoerp.go.rest;

import javax.servlet.http.HttpServletRequest;

interface EtendoGoSsoAssertionVerifier {

  /**
   * Verifies a provider-specific SSO request and returns the trusted account assertion.
   *
   * @param request servlet request containing provider headers, cookies, and metadata.
   * @param rawBody raw request body submitted by the browser.
   * @return trusted SSO assertion resolved from the provider response.
   * @throws EtendoGoSsoAssertionException when the request cannot be trusted.
   */
  EtendoGoSsoAssertion verify(HttpServletRequest request, String rawBody)
      throws EtendoGoSsoAssertionException;
}
