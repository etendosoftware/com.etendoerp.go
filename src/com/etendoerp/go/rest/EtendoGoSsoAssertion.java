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

import java.util.Objects;

/**
 * Immutable account identity resolved from a verified SSO provider assertion.
 */
final class EtendoGoSsoAssertion {

  private final String provider;
  private final String subject;
  private final String email;
  private final String name;
  private final boolean emailAuthoritative;

  EtendoGoSsoAssertion(String provider, String subject, String email, String name) {
    this(provider, subject, email, name, false);
  }

  EtendoGoSsoAssertion(String provider, String subject, String email, String name,
      boolean emailAuthoritative) {
    this.provider = Objects.requireNonNull(provider, "provider");
    this.subject = Objects.requireNonNull(subject, "subject");
    this.email = Objects.requireNonNull(email, "email");
    this.name = name;
    this.emailAuthoritative = emailAuthoritative;
  }

  String getProvider() {
    return provider;
  }

  String getSubject() {
    return subject;
  }

  String getEmail() {
    return email;
  }

  String getName() {
    return name;
  }

  boolean isEmailAuthoritative() {
    return emailAuthoritative;
  }
}
