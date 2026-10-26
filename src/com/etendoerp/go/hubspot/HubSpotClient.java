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

package com.etendoerp.go.hubspot;

import org.codehaus.jettison.json.JSONObject;

/** One authenticated call to the HubSpot CRM API. */
public interface HubSpotClient {

  /**
   * Sends one request.
   *
   * @param method {@code GET}, {@code POST} or {@code PATCH}
   * @param path   the path after the API root, starting with {@code /}
   * @param body   the JSON body, or {@code null} for none
   * @return HubSpot's answer, whatever its status
   * @throws HubSpotUnreachableException when no answer came back at all
   */
  Response send(String method, String path, JSONObject body) throws HubSpotUnreachableException;

  /**
   * HubSpot's answer.
   *
   * @param status the HTTP status
   * @param body   the raw body, never {@code null}
   */
  record Response(int status, String body) {

    /** @return whether HubSpot accepted the request */
    public boolean isSuccess() {
      return status / 100 == 2;
    }

    /**
     * Whether the same request may succeed later without anybody changing it: throttling, an
     * outage, or a token missing or lacking a scope, which an administrator fixes on their side.
     *
     * @return {@code true} for 401, 403, 429 and 5xx
     */
    public boolean isRetryable() {
      return status == 401 || status == 403 || status == 429 || status >= 500;
    }
  }

  /** No answer came back: a timeout, a DNS or TLS failure, a dropped connection. */
  class HubSpotUnreachableException extends Exception {
    private static final long serialVersionUID = 1L;

    public HubSpotUnreachableException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
