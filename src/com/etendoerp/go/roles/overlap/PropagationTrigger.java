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

/**
 * Which core propagation method will run AFTER a guard's own dependents-reconciliation method
 * returns, for the SAME access-grant event — determines whether it is safe to unconditionally
 * delete a dependent's conflicting row. Extracted from {@code
 * com.etendoerp.go.roles.WindowAccessOverlapCorruptionGuard}'s own private enum of this shape
 * (ETP-4906, Task B6, 7th round, finding "[B7]" — see that class's own class javadoc, "The
 * seventh trigger's own gap, found in REVIEW" section, for the full root-cause write-up) so
 * {@code ProcessAccessOverlapCorruptionGuard} and {@code ObuiappProcessAccessOverlapCorruption
 * Guard} (ETP-4830 item 7) share the identical reasoning instead of re-deriving it — the
 * reasoning depends only on which core method is about to run, never on which access-type table
 * is involved.
 */
public enum PropagationTrigger {
  /**
   * A template GAINED a brand-new grant. Core propagates via {@code RoleInheritanceManager
   * #propagateNewAccess}, which ALWAYS falls back to a CREATE when it finds no existing row for a
   * dependent — so unconditionally deleting a dependent's conflicting row first is always safe.
   */
  NEW_GRANT,
  /**
   * A template's OWN EXISTING grant had its access level changed. Core propagates via {@code
   * RoleInheritanceManager#propagateUpdatedAccess}, which has NO create fallback: it only ever
   * UPDATEs a dependent's row it can find, and silently does nothing otherwise. Unconditionally
   * deleting here would permanently lose the dependent's access with nothing left to restore it.
   */
  UPDATED_GRANT
}
