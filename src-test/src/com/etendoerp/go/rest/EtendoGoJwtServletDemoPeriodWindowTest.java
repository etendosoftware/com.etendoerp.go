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
package com.etendoerp.go.rest;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.MockedStatic;
import org.openbravo.base.exception.OBException;
import org.openbravo.dal.service.OBDal;

import com.etendoerp.go.onboarding.OnboardingInitialPeriodStateService;

/**
 * ETP-5575 / ETP-5577 — the post-commit, best-effort step that applies a new tenant's initial
 * period state, and its retry on environment login. It must commit on its own and never let a
 * failure (its own or its commit's) escape to the onboarding or the login.
 *
 * @covers com.etendoerp.go.rest.EtendoGoJwtServlet
 */
class EtendoGoJwtServletDemoPeriodWindowTest {

  private EtendoGoJwtServlet servlet;
  private OnboardingInitialPeriodStateService initialState;

  @BeforeEach
  void setUp() {
    servlet = new EtendoGoJwtServlet(mock(TransactionalAuthEmailSender.class));
    initialState = mock(OnboardingInitialPeriodStateService.class);
    servlet.onboardingInitialPeriodStateService = initialState;
  }

  @Test
  void appliesThePendingStateAndCommitsIt() {
    when(initialState.applyIfPending("CLIENT")).thenReturn(true);
    OBDal dal = mock(OBDal.class);

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      obDal.when(OBDal::getInstance).thenReturn(dal);
      servlet.applyInitialPeriodStateBestEffort("CLIENT");
    }

    InOrder order = inOrder(initialState, dal);
    order.verify(initialState).applyIfPending("CLIENT");
    order.verify(dal).commitAndClose();
    verify(dal, never()).rollbackAndClose();
  }

  @Test
  void nothingPendingCommitsNothing() {
    OBDal dal = mock(OBDal.class);

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      obDal.when(OBDal::getInstance).thenReturn(dal);
      servlet.applyInitialPeriodStateBestEffort("CLIENT");
    }

    verify(dal, never()).commitAndClose();
  }

  @Test
  void aFailingStepIsRolledBackAndNeverEscapes() {
    when(initialState.applyIfPending("CLIENT")).thenThrow(new OBException("boom"));
    OBDal dal = mock(OBDal.class);

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      obDal.when(OBDal::getInstance).thenReturn(dal);
      assertDoesNotThrow(() -> servlet.applyInitialPeriodStateBestEffort("CLIENT"));
    }

    verify(dal, never()).commitAndClose();
    verify(dal).rollbackAndClose();
  }

  @Test
  void aFailingCommitIsRolledBackAndNeverEscapes() {
    when(initialState.applyIfPending("CLIENT")).thenReturn(true);
    OBDal dal = mock(OBDal.class);
    doThrow(new IllegalStateException("commit boom")).when(dal).commitAndClose();

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      obDal.when(OBDal::getInstance).thenReturn(dal);
      assertDoesNotThrow(() -> servlet.applyInitialPeriodStateBestEffort("CLIENT"));
    }

    verify(dal).rollbackAndClose();
  }

  @Test
  void loginWithNothingPendingTouchesNoTransaction() {
    OBDal dal = mock(OBDal.class);

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      obDal.when(OBDal::getInstance).thenReturn(dal);
      servlet.retryInitialPeriodStateOnLogin("CLIENT");
    }

    verify(dal, never()).commitAndClose();
    verify(initialState, never()).applyIfPending(anyString());
  }

  @Test
  void loginCommitsItsOwnWorkBeforeRetryingAPendingState() {
    when(initialState.isPending("CLIENT")).thenReturn(true);
    when(initialState.applyIfPending("CLIENT")).thenThrow(new OBException("boom"));
    OBDal dal = mock(OBDal.class);

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      obDal.when(OBDal::getInstance).thenReturn(dal);
      assertDoesNotThrow(() -> servlet.retryInitialPeriodStateOnLogin("CLIENT"));
    }

    InOrder order = inOrder(dal, initialState);
    order.verify(dal).commitAndClose();
    order.verify(initialState).applyIfPending("CLIENT");
    order.verify(dal).rollbackAndClose();
  }

  @Test
  void loginWithoutAClientDoesNothing() {
    assertDoesNotThrow(() -> servlet.retryInitialPeriodStateOnLogin(null));
    verify(initialState, never()).isPending(anyString());
  }
}
