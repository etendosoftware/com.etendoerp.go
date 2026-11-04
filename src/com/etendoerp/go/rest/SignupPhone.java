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

import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;

/**
 * The optional phone the register form asks for (ETP-6081): one value with its
 * international prefix, e.g. {@code +34 600123456}, as the account stores it and HubSpot receives
 * it.
 */
final class SignupPhone {

  /** {@code ETGO_ACCOUNT.PHONE} is VARCHAR(40). */
  static final int MAX_LENGTH = 40;

  private static final Pattern PHONE = Pattern.compile("\\+\\d{1,4} [\\d ().\\-]+");
  private static final int MIN_DIGITS = 5;

  private SignupPhone() {
  }

  /**
   * @param raw the value sent by the form
   * @return the phone, trimmed and with runs of spaces collapsed, or {@code null} when absent or
   *     not a phone: a prefix, a space, then digits and the usual separators
   */
  static String normalize(String raw) {
    String phone = StringUtils.normalizeSpace(raw);
    if (StringUtils.isEmpty(phone) || phone.length() > MAX_LENGTH
        || !PHONE.matcher(phone).matches()) {
      return null;
    }
    return phone.replaceAll("\\D", "").length() >= MIN_DIGITS ? phone : null;
  }
}
