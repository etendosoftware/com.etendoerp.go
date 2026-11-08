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
package com.etendoerp.go.session;

/**
 * The support-access mark of a {@link GoSessionRecord} (ETP-5351), split out only to keep that
 * record small.
 */
public abstract class SupportMarkedSession {

  // ETP-5351: the ETGO_SUPPORT_ACCESS row a support session was opened for; null otherwise.
  private String supportAccessId;

  /**
   * The {@code ETGO_SUPPORT_ACCESS} row this session was opened for (ETP-5351).
   *
   * @return the support access id, or {@code null} for an ordinary session
   */
  public String getSupportAccessId() {
    return supportAccessId;
  }

  /**
   * Marks the session as a support session (ETP-5351). Rotation copies the mark.
   *
   * @param supportAccessId the {@code ETGO_SUPPORT_ACCESS} id, or {@code null}
   */
  public void setSupportAccessId(String supportAccessId) {
    this.supportAccessId = supportAccessId;
  }
}
