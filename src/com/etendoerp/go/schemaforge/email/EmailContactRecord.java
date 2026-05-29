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

import java.util.Objects;

import org.apache.commons.lang3.StringUtils;

/**
 * Contact data resolved from a trusted server-side record for email contracts.
 */
public final class EmailContactRecord {

  private final String name;
  private final String email;

  /**
   * Creates a normalized contact record.
   *
   * @param name display name for the contact
   * @param email email address for the contact
   */
  public EmailContactRecord(String name, String email) {
    this.name = StringUtils.trimToNull(name);
    this.email = StringUtils.trimToNull(email);
  }

  public String getName() {
    return name;
  }

  public String getEmail() {
    return email;
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof EmailContactRecord)) {
      return false;
    }
    EmailContactRecord that = (EmailContactRecord) other;
    return Objects.equals(name, that.name) && Objects.equals(email, that.email);
  }

  @Override
  public int hashCode() {
    return Objects.hash(name, email);
  }
}
