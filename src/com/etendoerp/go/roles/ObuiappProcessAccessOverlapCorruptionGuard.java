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
import org.openbravo.client.application.Process;
import org.openbravo.client.application.ProcessAccess;
import org.openbravo.model.ad.access.Role;

/**
 * ETP-4830 item 7 (full-parity expansion) — extends {@link WindowAccessOverlapCorruptionGuard}'s
 * and {@link ProcessAccessOverlapCorruptionGuard}'s full trigger set to {@code
 * OBUIAPP_Process_Access}.
 *
 * <p><b>Why this guard uses the SAME repoint-in-place mechanism as {@link
 * ProcessAccessOverlapCorruptionGuard}, not a lighter cleanup-only sweep</b> — see the
 * ETP-4830 item 7 full-parity design doc's own "Why OBUIAPP_Process_Access's guard is the same
 * mechanism" section for the full rationale, summarized here: the {@code OBSecurityException}
 * ownership-corruption crash is triggered by ANY entity write with the wrong {@code client}/
 * {@code organization} — it does not depend on the table having a unique constraint. {@code
 * OBUIAPP_Process_Access} (confirmed via {@code modules_core/org.openbravo.client.application/
 * src-db/database/model/tables/OBUIAPP_PROCESS_ACCESS.xml}: only non-unique indexes) only differs
 * from {@code AD_Process_Access} on the ONE sub-case where 2+ competing {@code copyRoleAccess}
 * INSERTs in the REMOVE-path's un-flushed multi-template walk would crash (Process: yes, via
 * {@code AD_PROCESS_ACCESS_UN_KEY}) vs. silently duplicate (OBUIAPP: no unique constraint) — and
 * that sub-case is already handled identically for both via repoint-in-place, which prevents the
 * duplicate from ever being created, making the distinction moot in practice.
 *
 * <p><b>Scope: full ADD/UPDATE/REMOVE-path parity with {@code
 * WindowAccessOverlapCorruptionGuard}/{@link ProcessAccessOverlapCorruptionGuard}.</b> All three
 * mirror the SAME failure signatures ({@code OBSecurityException} or a silently wrong access
 * level) that {@code WindowAccessOverlapCorruptionGuard} closes for {@code AD_Window_Access}.
 *
 * <p><b>ETP-4830 base-class extraction (2026-08-24/25).</b> This class's own event-handling method
 * bodies (previously mirroring {@code ProcessAccessOverlapCorruptionGuard}'s own, type-substituted)
 * are now implemented exactly once in {@link AbstractAccessOverlapCorruptionGuard}, shared with
 * {@code WindowAccessOverlapCorruptionGuard} and {@code ProcessAccessOverlapCorruptionGuard} — see
 * that class's own javadoc for the full shared mechanism write-up. This class supplies only the
 * {@code ProcessAccess}/{@code Process} (OBUIAPP, {@code
 * org.openbravo.client.application.ProcessAccess}/{@code org.openbravo.client.application.Process}
 * — a DIFFERENT, unrelated pair from the classic {@code
 * org.openbravo.model.ad.access.ProcessAccess}/{@code org.openbravo.model.ad.ui.Process} {@link
 * ProcessAccessOverlapCorruptionGuard} binds — see this class's own imports, which reference ONLY
 * the {@code org.openbravo.client.application} package for both type arguments) type-specific
 * accessors.
 */
public class ObuiappProcessAccessOverlapCorruptionGuard
    extends AbstractAccessOverlapCorruptionGuard<ProcessAccess, Process> {

  private static final Logger log =
      LogManager.getLogger(ObuiappProcessAccessOverlapCorruptionGuard.class);

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
    return ProcessAccess.PROPERTY_OBUIAPPPROCESS;
  }

  @Override
  protected Role getRole(ProcessAccess access) {
    return access.getRole();
  }

  @Override
  protected Process getGrantedItem(ProcessAccess access) {
    return access.getObuiappProcess();
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
    owner.getOBUIAPPProcessAccessList().remove(access);
  }

  @Override
  protected String entityLogLabel() {
    return "OBUIAPP_Process_Access";
  }

  @Override
  protected String itemLogLabel() {
    return "process";
  }
}
