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

import org.apache.commons.lang3.StringUtils;

import com.etendoerp.go.common.GoRuntimeProperties;

/**
 * ETP-6363 — the HubSpot form every self-serve sign-up is submitted to, through the Forms API
 * ({@code secure/submit}, authenticated with the sync's token), so HubSpot creates the contact
 * with its original source instead of "Offline" (marketing's "Atribución de leads").
 *
 * <p>Read like {@link HubSpotConfiguration}: a JVM system property, then
 * {@code Openbravo.properties}, then an environment variable. The defaults are production's
 * portal and its "Alta app – onboarding" form; {@code none} as the form disables it, and the
 * contact is then created through the CRM API as before.
 *
 * @param baseUrl  the Forms API root, without a trailing slash
 * @param portalId the HubSpot portal
 * @param formId   the form's {@code formGuid}, {@code null} when disabled
 */
public record HubSpotSignupForm(String baseUrl, String portalId, String formId) {

  static final String DEFAULT_BASE_URL = "https://api.hsforms.com";
  /** Production's portal and form, from marketing (2026-11-17). */
  static final String DEFAULT_PORTAL = "52086088";
  static final String DEFAULT_FORM = "7b98a4a2-a93d-412a-87d0-0d7c2bdc4925";
  static final String DISABLED_VALUE = "none";

  /** No form: sign-ups create the contact through the CRM API. */
  static final HubSpotSignupForm DISABLED = new HubSpotSignupForm(DEFAULT_BASE_URL,
      DEFAULT_PORTAL, null);

  /**
   * Reads the form as currently set on this server.
   *
   * @return the form
   */
  public static HubSpotSignupForm fromRuntime() {
    return from((property, env) -> GoRuntimeProperties.readValue(property, env, null));
  }

  static HubSpotSignupForm from(HubSpotConfiguration.Settings settings) {
    String form = read(settings, "form", "FORM", DEFAULT_FORM);
    return new HubSpotSignupForm(
        StringUtils.removeEnd(read(settings, "forms.base.url", "FORMS_BASE_URL",
            DEFAULT_BASE_URL), "/"),
        read(settings, "portal", "PORTAL", DEFAULT_PORTAL),
        DISABLED_VALUE.equalsIgnoreCase(form) ? null : form);
  }

  private static String read(HubSpotConfiguration.Settings settings, String suffix,
      String envSuffix, String defaultValue) {
    String value = settings.read("etendo.go.hubspot.signup." + suffix,
        "ETGO_HUBSPOT_SIGNUP_" + envSuffix);
    return StringUtils.defaultIfBlank(StringUtils.trim(value), defaultValue);
  }

  /**
   * Whether sign-ups go through the form.
   *
   * @return whether a form is configured
   */
  public boolean isEnabled() {
    return StringUtils.isNotBlank(formId) && StringUtils.isNotBlank(portalId);
  }

  /**
   * The submission path, after {@link #baseUrl()}.
   *
   * @return it
   */
  public String submitPath() {
    return "/submissions/v3/integration/secure/submit/" + portalId + "/" + formId;
  }
}
