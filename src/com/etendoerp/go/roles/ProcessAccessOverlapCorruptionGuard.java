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
package com.etendoerp.go.roles;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openbravo.model.ad.access.ProcessAccess;
import org.openbravo.model.ad.access.Role;
import org.openbravo.model.ad.ui.Process;

/**
 * ETP-4830 item 7 — extends {@link WindowAccessOverlapCorruptionGuard}'s proven REMOVE-path
 * ("sixth trigger") fix from {@code AD_Window_Access} to {@code AD_Process_Access}, which carries
 * the identical {@code AD_PROCESS_ACCESS_UN_KEY} unique constraint on {@code (AD_Role_ID,
 * AD_Process_ID)} — confirmed via {@code src-db/database/model/tables/AD_PROCESS_ACCESS.xml} — so
 * the same duplicate-INSERT race {@code WindowAccessOverlapCorruptionGuard}'s own class javadoc
 * documents ("A sixth trigger") is structurally reachable here too: core's {@code
 * RoleInheritanceManager#applyRemoveInheritance}/{@code calculateAccesses} is generic across every
 * {@code AccessTypeInjector} (window/process/OBUIAPP-process), walking every REMAINING template in
 * one un-flushed pass regardless of which access type it is reconciling.
 *
 * <p><b>Scope: full ADD/UPDATE/REMOVE-path parity with {@code
 * WindowAccessOverlapCorruptionGuard}.</b> The ADD path (ownership correction for a
 * newly-inherited dependent row, most-permissive-wins widening, and unconditional
 * dependent-clearing when a template gains a brand-new grant or a role gains a new inheritance),
 * UPDATE path (repointing an already-correctly-sourced dependent row in place, rather than
 * deleting it, when a template's own existing grant changes level), and REMOVE path (the original
 * duplicate-INSERT race documented above) all mirror the SAME failure signatures ({@code
 * OBSecurityException}, {@code ConstraintViolationException} on {@code
 * AD_PROCESS_ACCESS_UN_KEY}, or a silently wrong access level) that {@code
 * WindowAccessOverlapCorruptionGuard} closes for {@code AD_Window_Access}.
 *
 * <p><b>ETP-4830 base-class extraction (2026-08-24/25).</b> This class's own event-handling method
 * bodies (previously mirroring {@code WindowAccessOverlapCorruptionGuard}'s own, type-substituted)
 * are now implemented exactly once in {@link AbstractAccessOverlapCorruptionGuard}, shared with
 * {@code WindowAccessOverlapCorruptionGuard} and {@link
 * ObuiappProcessAccessOverlapCorruptionGuard} — see that class's own javadoc for the full shared
 * mechanism write-up. This class supplies only the {@code ProcessAccess}/{@code Process}
 * (classic, {@code org.openbravo.model.ad.access.ProcessAccess}/{@code
 * org.openbravo.model.ad.ui.Process}) type-specific accessors.
 *
 * <p>Reuses, rather than re-derives, the exact winner/level algorithm ({@link
 * com.etendoerp.go.roles.overlap.OverlapReconciliationCore#computeWinner(java.util.List)}) and the
 * "which templates does this role actively inherit from" query ({@link
 * com.etendoerp.go.roles.overlap.ActiveTemplateInheritance}) already proven for window access —
 * see those classes' own javadoc for the full root-cause write-up.
 */
public class ProcessAccessOverlapCorruptionGuard
    extends AbstractAccessOverlapCorruptionGuard<ProcessAccess, Process> {

  private static final Logger log = LogManager.getLogger(ProcessAccessOverlapCorruptionGuard.class);

  @Override
  protected Logger log() {
    return log;
  }

  @Override
  protected Class<ProcessAccess> accessClass() {
    return ProcessAccess.class;
  }

  @Override
  protected String accessEntityName() {
    return ProcessAccess.ENTITY_NAME;
  }

  @Override
  protected String itemProperty() {
    return ProcessAccess.PROPERTY_PROCESS;
  }

  @Override
  protected Role getRole(ProcessAccess access) {
    return access.getRole();
  }

  @Override
  protected Process getGrantedItem(ProcessAccess access) {
    return access.getProcess();
  }

  @Override
  protected Role getInheritedFrom(ProcessAccess access) {
    return access.getInheritedFrom();
  }

  @Override
  protected Boolean getEditableField(ProcessAccess access) {
    return access.isEditableField();
  }

  @Override
  protected void removeFromOwnerCollection(Role owner, ProcessAccess access) {
    owner.getADProcessAccessList().remove(access);
  }

  @Override
  protected String entityLogLabel() {
    return "AD_Process_Access";
  }

  @Override
  protected String itemLogLabel() {
    return "process";
  }
}
