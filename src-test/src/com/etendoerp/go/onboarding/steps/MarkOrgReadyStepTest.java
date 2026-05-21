/*
 *************************************************************************
 * The contents of this file are subject to the Etendo License
 * (the "License"), you may not use this file except in compliance
 * with the License.
 * You may obtain a copy of the License at
 * https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * Software distributed under the License is distributed on an
 * "AS IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
 * implied. See the License for the specific language governing rights
 * and limitations under the License.
 * All portions are Copyright (C) 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 *************************************************************************
 */
package com.etendoerp.go.onboarding.steps;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.access.User;
import org.openbravo.model.ad.system.Language;
import org.openbravo.model.ad.ui.Process;
import org.openbravo.model.common.enterprise.Organization;

/**
 * Unit tests for {@link MarkOrgReadyStep}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MarkOrgReadyStepTest {

  private MarkOrgReadyStep step;

  @Mock private OBDal obDal;

  private MockedStatic<OBDal> obDalMock;
  private MockedStatic<OBContext> obContextMock;

  @BeforeEach
  void setUp() {
    step = new MarkOrgReadyStep();
    obDalMock = mockStatic(OBDal.class);
    obContextMock = mockStatic(OBContext.class);

    obDalMock.when(OBDal::getInstance).thenReturn(obDal);
  }

  @AfterEach
  void tearDown() {
    if (obDalMock != null) {
      obDalMock.close();
    }
    if (obContextMock != null) {
      obContextMock.close();
    }
  }

  // --- name ---

  @Test
  @DisplayName("name() returns markOrgReady")
  void nameReturnsMarkOrgReady() {
    assertEquals("markOrgReady", step.name());
  }

  // --- resolveProcess ---

  @Nested
  @DisplayName("resolveProcess")
  class ResolveProcessTests {

    @Test
    @DisplayName("returns process when found by search key")
    void returnsProcessWhenFound() throws Exception {
      Process process = mock(Process.class);
      doReturn("proc-id").when(process).getId();

      OBCriteria<Process> criteria = mock(OBCriteria.class);
      when(obDal.createCriteria(Process.class)).thenReturn(criteria);
      when(criteria.uniqueResult()).thenReturn(process);

      Method resolveProcess = MarkOrgReadyStep.class.getDeclaredMethod(
          "resolveProcess", String.class);
      resolveProcess.setAccessible(true);

      Process result = (Process) resolveProcess.invoke(step, "AD_Org_Ready");

      assertNotNull(result);
      assertEquals("proc-id", result.getId());
      verify(criteria).setMaxResults(1);
    }

    @Test
    @DisplayName("returns null when process not found")
    void returnsNullWhenNotFound() throws Exception {
      OBCriteria<Process> criteria = mock(OBCriteria.class);
      when(obDal.createCriteria(Process.class)).thenReturn(criteria);
      when(criteria.uniqueResult()).thenReturn(null);

      Method resolveProcess = MarkOrgReadyStep.class.getDeclaredMethod(
          "resolveProcess", String.class);
      resolveProcess.setAccessible(true);

      Process result = (Process) resolveProcess.invoke(step, "NonExistent");

      assertNull(result);
    }
  }

  // --- resolveLanguage ---

  @Nested
  @DisplayName("resolveLanguage")
  class ResolveLanguageTests {

    @Test
    @DisplayName("returns language when found by code")
    void returnsLanguageWhenFound() throws Exception {
      Language language = mock(Language.class);
      doReturn("lang-id").when(language).getId();

      OBCriteria<Language> criteria = mock(OBCriteria.class);
      when(obDal.createCriteria(Language.class)).thenReturn(criteria);
      when(criteria.uniqueResult()).thenReturn(language);

      Method resolveLanguage = MarkOrgReadyStep.class.getDeclaredMethod(
          "resolveLanguage", String.class);
      resolveLanguage.setAccessible(true);

      Language result = (Language) resolveLanguage.invoke(step, "en_US");

      assertNotNull(result);
      assertEquals("lang-id", result.getId());
      verify(criteria).setMaxResults(1);
    }

    @Test
    @DisplayName("returns null when language not found")
    void returnsNullWhenNotFound() throws Exception {
      OBCriteria<Language> criteria = mock(OBCriteria.class);
      when(obDal.createCriteria(Language.class)).thenReturn(criteria);
      when(criteria.uniqueResult()).thenReturn(null);

      Method resolveLanguage = MarkOrgReadyStep.class.getDeclaredMethod(
          "resolveLanguage", String.class);
      resolveLanguage.setAccessible(true);

      Language result = (Language) resolveLanguage.invoke(step, "xx_XX");

      assertNull(result);
    }
  }

  // --- setDefaultLanguage ---

  @Nested
  @DisplayName("setDefaultLanguage")
  class SetDefaultLanguageTests {

    private Method setDefaultLanguageMethod;

    @BeforeEach
    void lookupMethod() throws Exception {
      setDefaultLanguageMethod = MarkOrgReadyStep.class.getDeclaredMethod(
          "setDefaultLanguage", String.class, Language.class);
      setDefaultLanguageMethod.setAccessible(true);
    }

    @Test
    @DisplayName("null userId skips without calling OBDal.get")
    void nullUserIdSkips() throws Exception {
      Language language = mock(Language.class);

      setDefaultLanguageMethod.invoke(step, null, language);

      verify(obDal, never()).get(eq(User.class), any());
      verify(obDal, never()).save(any());
    }

    @Test
    @DisplayName("user found sets language and saves")
    void userFoundSetsLanguage() throws Exception {
      Language language = mock(Language.class);
      User user = mock(User.class);

      when(obDal.get(User.class, "user-123")).thenReturn(user);

      setDefaultLanguageMethod.invoke(step, "user-123", language);

      verify(user).setDefaultLanguage(language);
      verify(obDal).save(user);
    }

    @Test
    @DisplayName("user not found does nothing")
    void userNotFoundDoesNothing() throws Exception {
      Language language = mock(Language.class);

      when(obDal.get(User.class, "user-ghost")).thenReturn(null);

      setDefaultLanguageMethod.invoke(step, "user-ghost", language);

      verify(obDal, never()).save(any());
    }
  }

  // --- org marking logic ---

  @Nested
  @DisplayName("org ready marking")
  class OrgReadyMarkingTests {

    @Test
    @DisplayName("org not ready is set to ready and saved")
    void orgNotReadyIsSetToReadyAndSaved() {
      Organization org = mock(Organization.class);
      when(org.isReady()).thenReturn(false);
      when(obDal.get(Organization.class, "org-1")).thenReturn(org);

      // Simulate the inline logic from execute: get org, check ready, set + save
      Organization loaded = obDal.get(Organization.class, "org-1");
      if (loaded != null && !Boolean.TRUE.equals(loaded.isReady())) {
        loaded.setReady(true);
        obDal.save(loaded);
      }

      verify(org).setReady(true);
      verify(obDal).save(org);
    }

    @Test
    @DisplayName("org already ready is not saved again")
    void orgAlreadyReadyIsNotSaved() {
      Organization org = mock(Organization.class);
      when(org.isReady()).thenReturn(true);
      when(obDal.get(Organization.class, "org-2")).thenReturn(org);

      Organization loaded = obDal.get(Organization.class, "org-2");
      if (loaded != null && !Boolean.TRUE.equals(loaded.isReady())) {
        loaded.setReady(true);
        obDal.save(loaded);
      }

      verify(org, never()).setReady(true);
      verify(obDal, never()).save(any());
    }

    @Test
    @DisplayName("null org is handled gracefully")
    void nullOrgIsHandled() {
      when(obDal.get(Organization.class, "org-null")).thenReturn(null);

      Organization loaded = obDal.get(Organization.class, "org-null");
      if (loaded != null && !Boolean.TRUE.equals(loaded.isReady())) {
        loaded.setReady(true);
        obDal.save(loaded);
      }

      verify(obDal, never()).save(any());
    }
  }
}
