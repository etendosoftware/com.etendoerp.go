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

package com.etendoerp.go.schemaforge.email;

/**
 * Provider response with secret-free metadata.
 */
public final class EmailProviderResponse {

  private final int statusCode;
  private final String body;

  /**
   * Creates provider response metadata safe to expose through the executor.
   *
   * @param statusCode provider HTTP status code
   * @param body provider response body
   */
  public EmailProviderResponse(int statusCode, String body) {
    this.statusCode = statusCode;
    this.body = body;
  }

  public boolean isSuccessful() {
    return statusCode >= 200 && statusCode < 300;
  }

  public int getStatusCode() {
    return statusCode;
  }

  public String getBody() {
    return body;
  }
}
