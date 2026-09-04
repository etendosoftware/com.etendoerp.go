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

/**
 * Store for the readable per-document email send history.
 *
 * <p>Separate from {@link EmailSafetyStore} on purpose: that one is the anti-abuse ledger
 * (hashed recipients, no subject, no body, client-0 rows) and its privacy invariant is not
 * relaxed. This one persists what the operator needs to read back.</p>
 */
public interface EmailSendLogStore {

  /**
   * Records one send history entry.
   *
   * @param historyRecord history entry for a single send attempt
   */
  void recordSend(EmailSendHistoryRecord historyRecord);
}
