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
 * All portions are Copyright (C) 2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */
package com.etendoerp.go.supportaccess;

import java.time.Instant;

/**
 * How and when an {@code ETGO_SUPPORT_ACCESS} row ended (ETP-5351): the closing columns of {@link
 * SupportAccessRecord}, split out only to keep that data holder small.
 */
public abstract class SupportAccessEnding {

  private Instant endedAt;
  private String endReason;
  private String endedByUserId;

  /** @return when the access ended, or {@code null} while open */
  public Instant getEndedAt() {
    return endedAt;
  }

  /** @param endedAt when the access ended */
  public void setEndedAt(Instant endedAt) {
    this.endedAt = endedAt;
  }

  /** @return the {@link SupportEndReason} code, or {@code null} while open */
  public String getEndReason() {
    return endReason;
  }

  /** @param endReason the {@link SupportEndReason} code */
  public void setEndReason(String endReason) {
    this.endReason = endReason;
  }

  /** @return the {@code AD_User_ID} that revoked the access, when it was revoked */
  public String getEndedByUserId() {
    return endedByUserId;
  }

  /** @param endedByUserId the {@code AD_User_ID} that revoked the access */
  public void setEndedByUserId(String endedByUserId) {
    this.endedByUserId = endedByUserId;
  }
}
