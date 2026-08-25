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
 * The verdict {@link OverlapReconciliationCore#computeWinner(java.util.List)} returns for one
 * window/process/report item: which template becomes {@code InheritedFrom}, and which access
 * level the dependent's row should end up at.
 */
public final class OverlapWinner {

  private final String winnerTemplateId;
  private final boolean winnerLevel;

  /**
   * Creates the verdict for one window/process/report item.
   *
   * @param winnerTemplateId
   *          the id of the template that becomes {@code InheritedFrom}
   * @param winnerLevel
   *          the most-permissive-wins access level the dependent's row should end up at
   */
  public OverlapWinner(String winnerTemplateId, boolean winnerLevel) {
    this.winnerTemplateId = winnerTemplateId;
    this.winnerLevel = winnerLevel;
  }

  public String getWinnerTemplateId() {
    return winnerTemplateId;
  }

  public boolean isWinnerLevel() {
    return winnerLevel;
  }
}
