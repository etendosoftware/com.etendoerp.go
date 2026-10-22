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

package com.etendoerp.go.signup;

/**
 * A refusal of the signup API, carrying everything its error body states: the HTTP status, the
 * stable {@code code} the form translates, the {@code field} to point at, and the {@code max}
 * length of a {@code FIELD_TOO_LONG}. The codes are the public contract
 * ({@code docs/signup-api.md}); never rename one.
 */
final class SignupException extends Exception {

  private static final long serialVersionUID = 1L;

  private final int status;
  private final String code;
  private final String field;
  private final Integer max;

  SignupException(int status, String code, String field, String message) {
    this(status, code, field, null, message);
  }

  SignupException(int status, String code, String field, Integer max, String message) {
    super(message);
    this.status = status;
    this.code = code;
    this.field = field;
    this.max = max;
  }

  int status() {
    return status;
  }

  String code() {
    return code;
  }

  String field() {
    return field;
  }

  Integer max() {
    return max;
  }
}
