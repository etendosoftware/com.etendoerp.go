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

package com.etendoerp.go.schemaforge;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers the two Salt Edge status gates in {@link PisPaymentService}.
 *
 * <p>Both used to carry a partial, hand-copied subset of the AD ref-list "PIS Payment Status"
 * ({@code AD_REFERENCE_ID D5483E7D91134499B42BBD963BC2F9CC}), which has exactly 8 values.
 * {@code initiated_info_required} was missing from the cancellable set, so a transfer sitting in
 * that ordinary pre-authorization state could not be undone — reproduced live against Salt Edge,
 * where cancelling returned <em>"The bank transfer is already in progress and can no longer be
 * undone from here"</em> and left an orphan payment behind (ETP-4895).
 */
class PisPaymentStatusGatesTest {

  private static boolean invokeGate(String name, String status) throws Exception {
    Method m = PisPaymentService.class.getDeclaredMethod(name, String.class);
    m.setAccessible(true);
    return (boolean) m.invoke(null, status);
  }

  private static boolean isCancellable(String status) throws Exception {
    return invokeGate("isCancellablePisStatus", status);
  }

  private static boolean isTerminal(String status) throws Exception {
    return invokeGate("isTerminalPisStatus", status);
  }

  @Test
  @DisplayName("every pre-authorization status can still be undone")
  void preAuthorizationStatusesAreCancellable() throws Exception {
    // No money has moved yet in any of these, so undoing is always safe.
    assertTrue(isCancellable("requested"));
    assertTrue(isCancellable("initiated"));
    assertTrue(isCancellable("authorizing"));
    assertTrue(isCancellable("failed"));
  }

  @Test
  @DisplayName("initiated_info_required can be undone — the regression that stranded a payment")
  void initiatedInfoRequiredIsCancellable() throws Exception {
    assertTrue(isCancellable("initiated_info_required"));
  }

  @Test
  @DisplayName("a blank status is treated as freshly created and can be undone")
  void blankStatusIsCancellable() throws Exception {
    assertTrue(isCancellable(null));
    assertTrue(isCancellable(""));
  }

  @Test
  @DisplayName("nothing past authorization can be undone")
  void authorizedAndBeyondIsNotCancellable() throws Exception {
    // From 'authorized' on, the user approved the transfer at the bank: undoing it here would
    // reverse a payment whose money is already moving.
    assertFalse(isCancellable("authorized"));
    assertFalse(isCancellable("executed"));
    assertFalse(isCancellable("settled"));
  }

  @Test
  @DisplayName("only the three final statuses stop the status polling")
  void terminalStatusesMatchTheRefList() throws Exception {
    assertTrue(isTerminal("executed"));
    assertTrue(isTerminal("settled"));
    assertTrue(isTerminal("failed"));

    assertFalse(isTerminal("authorized"));
    assertFalse(isTerminal("authorizing"));
    assertFalse(isTerminal("initiated_info_required"));
    // An unknown value must keep polling rather than be mistaken for a finished transfer.
    assertFalse(isTerminal("some_future_saltedge_status"));
  }
}
