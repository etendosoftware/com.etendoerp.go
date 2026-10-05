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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;

import javax.persistence.EntityNotFoundException;
import javax.persistence.OptimisticLockException;
import javax.persistence.PersistenceException;

import org.hibernate.StaleStateException;
import org.junit.jupiter.api.Test;
import org.openbravo.base.exception.OBException;

/**
 * ETP-5278 — {@link RoleWriteConflicts#isConcurrencyFailure(Throwable)} must recognise every
 * failure an overlapping write on the same user actually produced (local repro + production
 * logs), wrapped or not, and nothing else.
 */
class RoleWriteConflictsTest {

  @Test
  void recognisesTheFailuresObservedWhenTwoWritesOverlapped() {
    assertTrue(RoleWriteConflicts.isConcurrencyFailure(
        new StaleStateException("Batch update returned unexpected row count")));
    assertTrue(RoleWriteConflicts.isConcurrencyFailure(new OptimisticLockException("row gone")));
    assertTrue(RoleWriteConflicts.isConcurrencyFailure(
        new EntityNotFoundException("No row with the given identifier exists")));
  }

  @Test
  void recognisesARaceSqlStateAnywhereInTheCauseChain() {
    SQLException lockTimeout = new SQLException("canceling statement due to lock timeout",
        "55P03");
    assertTrue(RoleWriteConflicts.isConcurrencyFailure(
        new PersistenceException("wrapped", new RuntimeException("inner", lockTimeout))));
    assertTrue(RoleWriteConflicts.isConcurrencyFailure(
        new SQLException("duplicate key value violates unique constraint", "23505")));
  }

  @Test
  void doesNotClassifyUnrelatedFailures() {
    assertFalse(RoleWriteConflicts.isConcurrencyFailure(new RuntimeException("boom")));
    assertFalse(RoleWriteConflicts.isConcurrencyFailure(new OBException("not a template")));
    assertFalse(RoleWriteConflicts.isConcurrencyFailure(
        new SQLException("syntax error", "42601")));
    assertFalse(RoleWriteConflicts.isConcurrencyFailure(null));
  }
}
