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

package com.etendoerp.go.payment;

/**
 * A demo trial notice together with who receives it and in which language (ETP-5453).
 */
public final class DemoTrialNoticeRecord {

  private final String clientId;
  private final String environmentName;
  private final String language;
  private final String ownerName;
  private final String ownerEmail;
  private final DemoTrialNotice notice;

  /**
   * Creates the record.
   *
   * @param clientId the environment's {@code AD_Client_ID}
   * @param environmentName the environment's display name
   * @param language the environment's language code, such as {@code es_ES}; may be {@code null}
   * @param ownerName the owning platform account's name
   * @param ownerEmail the owning platform account's email
   * @param notice the owed notice
   */
  public DemoTrialNoticeRecord(String clientId, String environmentName, String language,
      String ownerName, String ownerEmail, DemoTrialNotice notice) {
    this.clientId = clientId;
    this.environmentName = environmentName;
    this.language = language;
    this.ownerName = ownerName;
    this.ownerEmail = ownerEmail;
    this.notice = notice;
  }

  public String getClientId() {
    return clientId;
  }

  public String getEnvironmentName() {
    return environmentName;
  }

  /** @return the environment's language, or {@code null} when the client has none */
  public String getLanguage() {
    return language;
  }

  public String getOwnerName() {
    return ownerName;
  }

  public String getOwnerEmail() {
    return ownerEmail;
  }

  public DemoTrialNotice getNotice() {
    return notice;
  }
}
