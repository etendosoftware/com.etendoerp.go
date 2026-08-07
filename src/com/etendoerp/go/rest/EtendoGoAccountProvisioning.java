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

/**
 * Public entry point (ETP-4829) letting other packages provision an {@code etgo_account} row
 * without depending on the package-private {@link EtendoGoJwtDalHelper}, which stays scoped to
 * this package on purpose (it owns account creation/auth invariants for {@link
 * EtendoGoJwtServlet}). Currently the only caller is {@code
 * com.etendoerp.go.schemaforge.handlers.UserRoleAssignmentHandler}, linking an admin-created
 * {@code AD_User} to a new pending platform account (see {@code docs/neo-headless.md}).
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
}
