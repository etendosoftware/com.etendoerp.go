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

import org.apache.commons.lang3.StringUtils;

/**
 * Public entry point (ETP-4829) letting other packages provision an {@code etgo_account} row
 * without depending on the package-private {@link EtendoGoJwtDalHelper}, which stays scoped to
 * this package on purpose (it owns account creation/auth invariants for {@link
 * EtendoGoJwtServlet}). Currently the only caller is {@code
 * com.etendoerp.go.schemaforge.handlers.UserRoleAssignmentHandler}, linking an admin-created
 * {@code AD_User} to a new platform account (see {@code docs/neo-headless.md}).
 */
public final class EtendoGoAccountProvisioning {

  private EtendoGoAccountProvisioning() {
  }

  /**
   * Ensures an {@code etgo_account} row exists for {@code email}, creating one in {@code pending}
   * status (no password, cannot log in) if none exists yet. No-op, returning the existing row,
   * if an account for this email is already registered. Never throws for a duplicate — callers
   * driving this from a best-effort post-hook (e.g. after saving an {@code AD_User}) must not
   * have their parent operation fail because of this side effect.
   */
  public static void ensurePendingAccount(String email, String name) {
    EtendoGoJwtDalHelper.createPendingAccount(email, name);
  }

  /**
   * ETP-4829: provisions the {@code etgo_account} for a freshly admin-created {@code AD_User},
   * with an optional temporary workaround for environments without ETP-4830's invite-email flow
   * yet — if the admin typed a password on the create form, the account is created {@code
   * active} with that password (hashed via {@link PasswordHasher#hash}, the same algorithm
   * self-service register/login use) instead of {@code pending}. {@code plainPassword} must
   * already have passed {@link PasswordPolicy#isStrong} — that check has to happen in the
   * caller's pre-hook, before the {@code AD_User} is even created, so a weak password is
   * rejected with 400 instead of leaving behind a created user with no usable account; this
   * method does not re-validate it. A blank/null {@code plainPassword} falls back to {@link
   * #ensurePendingAccount}.
   */
  public static void ensureAccountForCreatedUser(String email, String name,
      String plainPassword) {
    if (StringUtils.isBlank(plainPassword)) {
      ensurePendingAccount(email, name);
      return;
    }
    String passwordHash = PasswordHasher.hash(plainPassword);
    EtendoGoJwtDalHelper.createActiveAccount(email, passwordHash, name);
  }
}
