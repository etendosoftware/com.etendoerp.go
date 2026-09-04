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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * In-memory send history store, used by the DAL-free {@link TransactionalEmailService}
 * constructors and by tests that need to assert what would have been persisted.
 *
 * <p>Mirrors {@link InMemoryEmailSafetyStore}'s role for {@link EmailSafetyStore}.</p>
 */
public class InMemoryEmailSendLogStore implements EmailSendLogStore {

  private final List<EmailSendHistoryRecord> historyRecords = new ArrayList<>();

  @Override
  public void record(EmailSendHistoryRecord historyRecord) {
    historyRecords.add(historyRecord);
  }

  /**
   * Returns the history entries recorded so far, oldest first.
   *
   * @return unmodifiable view of the recorded entries
   */
  public List<EmailSendHistoryRecord> getHistoryRecords() {
    return Collections.unmodifiableList(historyRecords);
  }
}
