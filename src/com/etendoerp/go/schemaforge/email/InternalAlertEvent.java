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

package com.etendoerp.go.schemaforge.email;

import java.util.Objects;

/** Immutable, deliberately limited operational facts; never carries request bodies or secrets. */
public final class InternalAlertEvent {
  public enum Status { OK, ERROR }
  private final String event;
  private final Status status;
  private final String attemptId;
  private final String environmentType;
  private final String clientId;
  private final String path;
  private final String stage;
  private final String failureCategory;

  public InternalAlertEvent(String event, Status status, String attemptId,
      String environmentType, String clientId, String stage, String failureCategory) {
    this(event, status, attemptId, environmentType, clientId, "UNKNOWN", stage, failureCategory);
  }
  public InternalAlertEvent(String event, Status status, String attemptId,
      String environmentType, String clientId, String path, String stage, String failureCategory) {
    this.path = token(path, "path");
    this.event = token(event, "event");
    this.status = Objects.requireNonNull(status, "status");
    this.attemptId = token(attemptId, "attemptId");
    this.environmentType = token(environmentType, "environmentType");
    this.clientId = optionalToken(clientId);
    this.stage = token(stage, "stage");
    this.failureCategory = optionalToken(failureCategory);
  }
  private static String token(String value, String field) {
    if (value == null || !value.matches("[A-Za-z0-9_.:-]{1,128}")) {
      throw new IllegalArgumentException("Invalid internal alert " + field);
    }
    return value;
  }
  private static String optionalToken(String value) {
    return value == null || value.isEmpty() ? null : token(value, "metadata");
  }
  public String getEvent() { return event; }
  public Status getStatus() { return status; }
  public String getAttemptId() { return attemptId; }
  public String getEnvironmentType() { return environmentType; }
  public String getClientId() { return clientId; }
  public String getPath() { return path; }
  public String getStage() { return stage; }
  public String getFailureCategory() { return failureCategory; }
}
