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

package com.etendoerp.go.schemaforge.handlers;

import java.util.function.Supplier;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openbravo.dal.core.TriggerHandler;
import org.openbravo.dal.service.OBDal;

/**
 * ETP-6365: runs the writes of the {@code conversion-rates} customization with the database
 * triggers disabled, through core's {@link TriggerHandler}.
 *
 * <p><b>Why.</b> Core's {@code C_CONVERSION_RATE_TRG} refuses ({@code @20506@}) to update or delete
 * a range whose dates contain postings of the pair. The design lets a user change or delete any
 * manual range — a change only affects documents that look the rate up afterwards; a posted
 * invoice keeps the rate it was posted with ({@code C_Conversion_Rate_Document}, the header rate).
 * The writes of this customization therefore bypass the trigger, and {@link ConversionRateWrites}
 * re-checks what the trigger enforced and is still wanted (see there).</p>
 *
 * <p><b>Scope of the switch.</b> On PostgreSQL {@link TriggerHandler#disable()} runs
 * {@code set_config('my.triggers_disabled','Y', true)}: {@code is_local = true}, so the setting
 * belongs to the current database TRANSACTION of the current DAL connection and is undone by its
 * commit or rollback — no other connection, request or tenant sees it. Every Etendo trigger reads it
 * through {@code AD_isTriggerEnabled()}. The handler also remembers it in a thread-local, which is
 * why {@link TriggerHandler#clear()} must run when the transaction is rolled back.</p>
 *
 * <p><b>Discipline.</b> The work is flushed before the triggers are enabled again (Hibernate
 * defers the SQL: an unflushed write would reach the database after the enable, with the triggers
 * on). Enabling always runs in {@code finally}; if it fails (the transaction is already aborted
 * after a database error) the thread-local is cleared, and the rollback the caller performs undoes
 * the setting itself. When the triggers are already disabled by an outer owner — a process that
 * disabled them and then reached this code — the switch is left alone and stays the owner's.</p>
 *
 * <p><b>Batch.</b> Inside {@code /sws/neo/batch} the transaction is the batch's: the setting is
 * enabled again before this operation returns, so the following operations of the batch run with
 * the triggers on; a failure rolls the whole batch back, which also discards the setting.</p>
 */
class ConversionRateTriggerBypass {

  private static final Logger log = LogManager.getLogger(ConversionRateTriggerBypass.class);

  /**
   * Runs {@code work} with the triggers disabled, flushes it, and enables them again.
   *
   * @param work the writes
   * @param <T>  the result type
   * @return what {@code work} returned
   */
  <T> T run(Supplier<T> work) {
    TriggerHandler triggers = triggerHandler();
    boolean owner = !triggers.isDisabled();
    if (owner) {
      triggers.disable();
    }
    try {
      T result = work.get();
      OBDal.getInstance().flush();
      return result;
    } finally {
      if (owner) {
        restore(triggers);
      }
    }
  }

  private static void restore(TriggerHandler triggers) {
    try {
      triggers.enable();
    } catch (RuntimeException e) {
      // The transaction is aborted: the caller's rollback undoes the setting. The thread-local is
      // what would leak to the next request on this thread, so it is cleared here.
      triggers.clear();
      log.warn("[ETP-6365] Could not enable the triggers again; cleared: {}", e.getMessage());
    }
  }

  /** Core's handler; a seam for unit tests. */
  TriggerHandler triggerHandler() {
    return TriggerHandler.getInstance();
  }
}
