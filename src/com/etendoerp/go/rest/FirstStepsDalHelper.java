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
 * All portions are Copyright (C) 2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.rest;

import org.openbravo.dal.service.OBDal;

import com.etendoerp.go.schemaforge.data.Account;

/**
 * ETP-5190 — DAL access for the post-signup First Steps checklist state.
 *
 * <p>Its own class rather than two more methods on {@link EtendoGoJwtDalHelper}, for the same
 * reason {@code EmailVerificationDalHelper} and {@code CompanyInvitationDalHelper} exist: that
 * class already carries every other account query and this pair pushed it to 36 methods, one over
 * the limit. The checklist is a self-contained concern — one JSON column, read by the checklist
 * endpoint and written when a step is ticked — so it splits off cleanly.
 *
 * <p><b>Headroom note.</b> Moving this pair out leaves {@link EtendoGoJwtDalHelper} at 34 methods,
 * so the next concern that needs two of them trips the same limit again. The natural next
 * extraction is the local-password / reset-token cluster there (ten cohesive methods:
 * {@code hasLocalPassword} through {@code changePassword}); it was left in place here only because
 * {@code EtendoGoJwtServletTest} stubs several of them through
 * {@code mockStatic(EtendoGoJwtDalHelper.class)}, so moving them is a test change too and had no
 * business riding along with a checklist ticket.
 */
final class FirstStepsDalHelper {

  private FirstStepsDalHelper() {
  }

  /**
   * Returns the stored First Steps checklist JSON for the given account, or {@code null} when the
   * account is unknown or nothing has been persisted yet.
   *
   * @param account the account to read, may be {@code null}
   * @return the stored JSON, or {@code null} when there is none
   */
  static String getFirstSteps(Account account) {
    return account == null ? null : account.getFirstSteps();
  }

  /**
   * Persists the First Steps checklist JSON for the given account, or clears it when
   * {@code firstStepsJson} is {@code null}. Flushes and commits internally, so callers must not
   * add a second commit.
   *
   * @param account        the account to write, may be {@code null} (then this is a no-op)
   * @param firstStepsJson the JSON to store, or {@code null} to clear it
   */
  static void updateFirstSteps(Account account, String firstStepsJson) {
    if (account == null) {
      return;
    }
    account.setFirstSteps(firstStepsJson);
    OBDal.getInstance().save(account);
    flushAndCommit();
  }

  private static void flushAndCommit() {
    OBDal.getInstance().flush();
    OBDal.getInstance().commitAndClose();
  }
}
