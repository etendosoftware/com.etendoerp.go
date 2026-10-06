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
  /** Result an alert reports. */
  public enum Status { OK, ERROR }

  /** Which environment the alert is about: its type, client (optional) and provisioning path. */
  public static final class Target {
    private final String environmentType;
    private final String clientId;
    private final String path;

    /**
     * Identifies the environment an alert is about.
     *
     * @param environmentType environment type token, e.g. {@code DEMO} or {@code PRODUCTIVE}
     * @param clientId client the attempt built, or {@code null} when none exists yet
     * @param path provisioning path token, e.g. {@code POOL} or {@code CLASSIC}
     */
    public Target(String environmentType, String clientId, String path) {
      this.environmentType = environmentType;
      this.clientId = clientId;
      this.path = path;
    }
  }

  private final String event;
  private final Status status;
  private final String attemptId;
  private final String environmentType;
  private final String clientId;
  private final String path;
  private final String stage;
  private final String failureCategory;

  /**
   * Builds an event from operational tokens only; anything that is not a short identifier is
   * rejected, so request bodies, messages or secrets can never reach an alert.
   *
   * @param event event name token
   * @param status reported result
   * @param attemptId provisioning attempt token
   * @param target environment the attempt is about
   * @param stage last stage the attempt reached
   * @param failureCategory failure class name, or {@code null}
   * @throws IllegalArgumentException when a value is not a valid token
   */
  public InternalAlertEvent(String event, Status status, String attemptId, Target target,
      String stage, String failureCategory) {
    Objects.requireNonNull(target, "target");
    this.path = token(target.path, "path");
    this.event = token(event, "event");
    this.status = Objects.requireNonNull(status, "status");
    this.attemptId = token(attemptId, "attemptId");
    this.environmentType = token(target.environmentType, "environmentType");
    this.clientId = optionalToken(target.clientId);
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
