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

    Execution(String id, String mode, String status, String routineName, Integer version,
        String steps, String result, String created) {
      this.id = id;
      this.mode = mode;
      this.status = status;
      this.routineName = routineName;
      this.version = version;
      this.steps = steps;
      this.result = result;
      this.created = created;
    }
  }

  /** @return the owner's routine with that name, or {@code null} */
  Routine find(Owner owner, String name);

  /** @return the owner's routines, by name */
  List<Routine> list(Owner owner);

  /** @return one version of a routine, or {@code null} when it does not exist */
  Version version(String routineId, int number);

  /** @return the version numbers of a routine, ascending */
  List<Integer> versionNumbers(String routineId);

  /**
   * Saves a new routine at version 1.
   *
   * @return the new routine
   */
  Routine create(Owner owner, String name, String description, String params, String steps);

  /**
   * Adds a version to a routine and makes it current.
   *
   * @return the new version number
   */
  int addVersion(Owner owner, Routine routine, String description, String params, String steps);

  /** Deletes the owner's routine with all its versions; @return whether it existed */
  boolean delete(Owner owner, String name);

  /**
   * Records one execution.
   *
   * @return its id, the {@code executionId} the agent receives
   */
  String recordExecution(Owner owner, Execution execution);

  /** @return the owner's execution with that id, or {@code null} */
  Execution findExecution(Owner owner, String executionId);
}
