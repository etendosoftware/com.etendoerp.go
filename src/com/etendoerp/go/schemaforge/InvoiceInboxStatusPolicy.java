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
 * All portions are Copyright © 2021–2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.schemaforge;

import java.util.Set;

import com.etendoerp.go.schemaforge.data.IncomingDocument;

/**
 * Moves an inbox row between {@code PENDING_REVIEW} and the validation errors (ETP-6048), and only
 * there. The worker applies it after matching; the review panel applies it after every edit
 * (ETP-6050).
 *
 * <p>It never touches a row that is {@code PROCESSING}, {@code READY}, {@code CONFIRMED} or
 * {@code DISCARDED}, nor an {@code ERROR} whose code is not a validation one
 * ({@code UNREADABLE}, {@code SERVICE_FAILURE}, {@code CONFIRM_FAILED}): those are decided
 * elsewhere.</p>
 */
public final class InvoiceInboxStatusPolicy {

  static final String PENDING_REVIEW = "PENDING_REVIEW";
  static final String ERROR = "ERROR";

  private static final Set<String> VALIDATION_ERRORS = Set.of(
      InvoiceInboxValidator.FOREIGN_RECEIVER, InvoiceInboxValidator.REFERENCE_TOO_LONG,
      InvoiceInboxValidator.MISSING_FIELDS);

  private InvoiceInboxStatusPolicy() {
  }

  /**
   * Applies a verdict to a row when its state allows it.
   *
   * @param row    the inbox row
   * @param result the validation verdict
   * @return {@code true} when the row was changed
   */
  public static boolean apply(IncomingDocument row, InvoiceInboxValidator.Result result) {
    if (!isMovable(row)) {
      return false;
    }
    String status = result.isValid() ? PENDING_REVIEW : ERROR;
    String code = result.getErrorCode();
    boolean changed = !status.equals(row.getStatus())
        || (code == null ? row.getErrorCode() != null : !code.equals(row.getErrorCode()));
    row.setStatus(status);
    row.setErrorCode(code);
    return changed;
  }

  private static boolean isMovable(IncomingDocument row) {
    String status = row.getStatus();
    if (PENDING_REVIEW.equals(status)) {
      return true;
    }
    return ERROR.equals(status) && (row.getErrorCode() == null
        || VALIDATION_ERRORS.contains(row.getErrorCode()));
  }
}
