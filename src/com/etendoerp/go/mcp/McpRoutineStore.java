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
package com.etendoerp.go.mcp;

import java.util.List;

import org.openbravo.dal.core.OBContext;

/**
 * Persistence of {@code etendo_routine} (ETP-6236): saved routines, their versions and past
 * executions, in {@code ETGO_MCP_ROUTINE}, {@code ETGO_MCP_ROUTINE_VERSION} and
 * {@code ETGO_MCP_ROUTINE_EXEC}.
 *
 * <p>Every read and write is scoped to one {@link Owner}: a routine is personal (one user of one
 * client), so nothing here can reach another user's rows. The implementation writes on its own
 * connection, outside the call's business transaction, so saving a routine or recording an
 * execution never commits or rolls back the steps' data.</p>
 */
interface McpRoutineStore {

  /** Who the call runs as; every query is restricted to this client and user. */
  final class Owner {
    final String clientId;
    final String orgId;
    final String userId;
    final String roleId;

    Owner(String clientId, String orgId, String userId, String roleId) {
      this.clientId = clientId;
      this.orgId = orgId;
      this.userId = userId;
      this.roleId = roleId;
    }

    /** @return the user, client, organization and role of the current {@link OBContext} */
    static Owner current() {
      OBContext context = OBContext.getOBContext();
      return new Owner(context.getCurrentClient().getId(),
          context.getCurrentOrganization().getId(), context.getUser().getId(),
          context.getRole().getId());
    }
  }

  /** A saved routine, without its steps. */
  final class Routine {
    final String id;
    final String name;
    final String description;
    final int currentVersion;

    Routine(String id, String name, String description, int currentVersion) {
      this.id = id;
      this.name = name;
      this.description = description;
      this.currentVersion = currentVersion;
    }
  }

  /** One version of a routine: its declared parameters and its steps, both as JSON text. */
  final class Version {
    final int number;
    final String params;
    final String steps;
    final String created;

    Version(int number, String params, String steps, String created) {
      this.number = number;
      this.params = params;
      this.steps = steps;
      this.created = created;
    }
  }

  /** A past execution: what was sent and what every step answered, both as JSON text. */
  final class Execution {
    final String id;
    final String mode;
    final String status;
    final String routineName;
    final Integer version;
    final String steps;
    final String result;
    final String created;

    private Execution(String mode, String status, String routineName, Integer version,
        String steps, String result) {
      this.id = null;
      this.created = null;
      this.mode = mode;
      this.status = status;
      this.routineName = routineName;
      this.version = version;
      this.steps = steps;
      this.result = result;
    }

    private Execution(Execution base, String id, String created) {
      this.id = id;
      this.created = created;
      this.mode = base.mode;
      this.status = base.status;
      this.routineName = base.routineName;
      this.version = base.version;
      this.steps = base.steps;
      this.result = base.result;
    }

    /**
     * An execution about to be recorded: it has no id or date yet.
     *
     * @param mode        {@code exec} or {@code run}
     * @param status      {@code OK} or {@code FAILED}
     * @param routineName the routine that ran, {@code null} for an inline exec
     * @param version     its version, {@code null} for an inline exec
     * @param steps       the steps as sent, JSON text
     * @param result      every step's result or error, JSON text
     * @return the execution
     */
    static Execution toRecord(String mode, String status, String routineName, Integer version,
        String steps, String result) {
      return new Execution(mode, status, routineName, version, steps, result);
    }

    /**
     * This execution as read back from storage.
     *
     * @param storedId   the id it was stored under
     * @param storedDate when it was stored, ISO-8601
     * @return the execution with its id and date
     */
    Execution stored(String storedId, String storedDate) {
      return new Execution(this, storedId, storedDate);
    }
  }

  /**
   * Finds one of the owner's routines.
   *
   * @param owner the calling user
   * @param name  the routine name
   * @return the routine, or {@code null} when the owner has none with that name
   */
  Routine find(Owner owner, String name);

  /**
   * Lists the owner's routines.
   *
   * @param owner the calling user
   * @return the routines, by name
   */
  List<Routine> list(Owner owner);

  /**
   * Reads one version of a routine.
   *
   * @param routineId the routine
   * @param number    the version number
   * @return the version, or {@code null} when it does not exist
   */
  Version version(String routineId, int number);

  /**
   * Lists the version numbers of a routine.
   *
   * @param routineId the routine
   * @return the version numbers, ascending
   */
  List<Integer> versionNumbers(String routineId);

  /**
   * Saves a new routine at version 1.
   *
   * @param owner       the calling user, who owns it
   * @param name        its name, unique among the owner's routines
   * @param description what it does
   * @param params      the declared parameters, JSON text
   * @param steps       the steps, JSON text
   * @return the new routine
   */
  Routine create(Owner owner, String name, String description, String params, String steps);

  /**
   * Adds a version to a routine and makes it current.
   *
   * @param owner       the calling user
   * @param routine     the routine
   * @param description its description from now on
   * @param params      the version's declared parameters, JSON text
   * @param steps       the version's steps, JSON text
   * @return the new version number
   */
  int addVersion(Owner owner, Routine routine, String description, String params, String steps);

  /**
   * Deletes one of the owner's routines with all its versions.
   *
   * @param owner the calling user
   * @param name  the routine name
   * @return whether it existed
   */
  boolean delete(Owner owner, String name);

  /**
   * Records one execution.
   *
   * @param owner     the calling user
   * @param execution what ran and what it answered
   * @return its id, the {@code executionId} the agent receives
   */
  String recordExecution(Owner owner, Execution execution);

  /**
   * Reads one of the owner's executions.
   *
   * @param owner       the calling user
   * @param executionId the execution
   * @return the execution, or {@code null} when the owner has none with that id
   */
  Execution findExecution(Owner owner, String executionId);
}
