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

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import javax.servlet.http.HttpServletRequest;

import com.etendoerp.go.common.PublicUrlResolver;

final class EtendoGoAuthLinkBuilder {

  private static final String ONBOARDING_PATH = "onboarding";
  private static final String DASHBOARD_PATH = "dashboard";

  private EtendoGoAuthLinkBuilder() {
  }

  static String onboardingLink(HttpServletRequest request) {
    return PublicUrlResolver.appendPath(PublicUrlResolver.resolveAppBaseUrl(request),
        ONBOARDING_PATH);
  }

  static String dashboardLink(HttpServletRequest request) {
    return PublicUrlResolver.appendPath(PublicUrlResolver.resolveAppBaseUrl(request),
        DASHBOARD_PATH);
  }

  static String resetPasswordLink(HttpServletRequest request, String resetToken) {
    String onboardingLink = onboardingLink(request);
    if (onboardingLink == null) {
      return null;
    }
    return onboardingLink + "?resetToken=" + encode(resetToken);
  }

  private static String encode(String value) {
    try {
      return URLEncoder.encode(value, StandardCharsets.UTF_8.name());
    } catch (UnsupportedEncodingException e) {
      throw new IllegalStateException("UTF-8 encoding is not available", e);
    }
  }
}
