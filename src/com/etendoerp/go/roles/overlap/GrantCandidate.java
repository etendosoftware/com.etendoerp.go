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
 * One remaining template's active grant for a single window/process/report item, as seen by
 * {@link OverlapReconciliationCore#computeWinner(java.util.List)}. Pure data — no Hibernate, no
 * entity reference — so the winner/level decision itself is unit-testable without a DB. See
 * {@code com.etendoerp.go.roles.WindowAccessOverlapCorruptionGuard}'s class javadoc for the full
 * root-cause write-up this type's shape comes from (ETP-4830 item 7 extraction).
 */
public final class GrantCandidate {

  private final String templateId;
  private final boolean fullAccess;

  /**
   * Creates a candidate recording one remaining template's active grant for a single item.
   *
   * @param templateId
   *          the id of the remaining template this candidate represents
   * @param fullAccess
   *          whether this template currently grants full ("&#x2713;") access to the item
   */
  public GrantCandidate(String templateId, boolean fullAccess) {
    this.templateId = templateId;
    this.fullAccess = fullAccess;
  }

  public String getTemplateId() {
    return templateId;
  }

  public boolean isFullAccess() {
    return fullAccess;
  }
}
