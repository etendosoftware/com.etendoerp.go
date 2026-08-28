/*
 *************************************************************************
 * The contents of this file are subject to the Etendo License
 * (the "License"), you may not use this file except in compliance with
 * the License.
 * You may obtain a copy of the License at
 * https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * Software distributed under the License is distributed on an "AS IS"
 * BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied. See the License for the specific language governing permissions
 * and limitations under the License.
 * All portions are Copyright © 2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 *************************************************************************
 */

package com.etendoerp.go.psd2;

import com.etendoerp.go.common.GoRuntimeProperties;

/** Server-side settings for the PSD2 Salt Edge provisioning client. */
public final class Psd2ApiKeyConfiguration {

  private static final String PROXY_URL_PROPERTY = "psd2.salt.edge.proxy.url";
  private static final String PROXY_URL_ENV = "PSD2_SALT_EDGE_PROXY_URL";
  private static final String DEFAULT_PROXY_URL = "https://psd2.etendo.cloud";
  private static final String ADMIN_KEY_PROPERTY =
      "etendo.go.psd2.saltedge.proxy.admin.key";
  private static final String ADMIN_KEY_ENV = "ETGO_PSD2_SALTEDGE_PROXY_ADMIN_KEY";

  private Psd2ApiKeyConfiguration() {
  }

  /** @return the Salt Edge proxy provisioning endpoint */
  public static String provisioningUrl() {
    return proxyBaseUrl() + "/internal/provision";
  }

  private static String proxyBaseUrl() {
    String configured = GoRuntimeProperties.readValue(PROXY_URL_PROPERTY, PROXY_URL_ENV,
        DEFAULT_PROXY_URL);
    return configured.replaceAll("/+$", "");
  }

  /**
   * Reads the provisioning credential from runtime configuration. It must never be committed to
   * source control or included in application logs.
   *
   * @return provisioning credential, or an empty value when not configured
   */
  public static String provisioningAdminKey() {
    return GoRuntimeProperties.readValue(ADMIN_KEY_PROPERTY, ADMIN_KEY_ENV, "");
  }
}
