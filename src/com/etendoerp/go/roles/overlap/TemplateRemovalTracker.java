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
package com.etendoerp.go.roles.overlap;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Template role ids currently being removed via an in-flight {@code RoleInheritance} deletion
 * within THIS transaction (one set per thread — Openbravo/Tomcat threads process one request's
 * transaction at a time, never concurrently). Extracted from {@code
 * com.etendoerp.go.roles.WindowAccessOverlapCorruptionGuard}'s own private {@code
 * TEMPLATES_BEING_REMOVED} field (ETP-4906, Task B6, 5th round) so {@code
 * WindowAccessOverlapCorruptionGuard}, {@code ProcessAccessOverlapCorruptionGuard}, and {@code
 * ObuiappProcessAccessOverlapCorruptionGuard} share the SAME marker instead of each tracking its
 * own, separate one — a template being removed is being removed for every access type at once,
 * not independently per guard.
 *
 * <p>See the original field's own javadoc (git history, {@code WindowAccessOverlapCorruptionGuard}
 * before ETP-4830 item 7) for the exact empirically-confirmed race this closes: a template's own
 * {@code RoleInheritance} row is still DB-visible as {@code active=true} mid-flush, even while
 * being deleted in the SAME flush, because Hibernate's default action-queue execution order runs
 * entity Deletions LAST (after Insertions/Updates).
 *
 * <p>Deliberately NOT cleared by the guard method that populates it — by the time a nested CREATE
 * this field exists to protect against actually fires, that method's own stack frame has already
 * returned. Instead cleared once per transaction via each guard's own {@code
 * onTransactionComplete(TransactionCompletedEvent)} calling {@link #clear()} — safe because a
 * marker surviving until transaction end can only make callers MORE conservative, never less
 * correct. Multiple guards calling {@link #clear()} for the same transaction is harmless —
 * {@code ThreadLocal#remove()} is idempotent.
 */
public final class TemplateRemovalTracker {

  private static final ThreadLocal<Set<String>> BEING_REMOVED =
      ThreadLocal.withInitial(LinkedHashSet::new);

  private TemplateRemovalTracker() {
    // static utility
  }

  public static void markRemoved(String templateId) {
    BEING_REMOVED.get().add(templateId);
  }

  public static boolean isBeingRemoved(String templateId) {
    return BEING_REMOVED.get().contains(templateId);
  }

  public static void clear() {
    BEING_REMOVED.remove();
  }
}
