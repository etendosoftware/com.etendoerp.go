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
package com.etendoerp.go.onboarding;

import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.Test;
import org.mockito.MockedStatic;
import org.openbravo.base.exception.OBException;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.enterprise.Organization;

/**
 * Unit tests for {@link OnboardingContextSupport}, the shared DAL/execution-context scaffolding for
 * the onboarding provisioning services.
 *
 * <p>The class is abstract, so a minimal concrete {@code Probe} subclass supplies a
 * {@link OnboardingContextSupport#contextSubject() contextSubject} and exposes the otherwise
 * {@code protected} seam methods to the test through inheritance. Static {@link OBContext} and
 * {@link OBDal} interactions are exercised with {@code mockStatic}, matching the convention used
 * across the onboarding test suite.
 */
public class OnboardingContextSupportTest {

  /** Minimal concrete subclass used to drive the inherited protected seams. */
  private static final class Probe extends OnboardingContextSupport {
    @Override
    protected String contextSubject() {
      return "probe subject";
    }
  }

  // ---------------------------------------------------------------------------------------------
  // resolveOrganization() / flushChanges() — OBDal delegation
  // ---------------------------------------------------------------------------------------------

  @Test
  public void testResolveOrganizationDelegatesToObDalGet() {
    Probe probe = new Probe();
    Organization org = mock(Organization.class);

    OBDal dal = mock(OBDal.class);
    when(dal.get(Organization.class, "ORG-1")).thenReturn(org);

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      obDal.when(OBDal::getInstance).thenReturn(dal);
      assertSame(org, probe.resolveOrganization("ORG-1"));
    }
  }

  @Test
  public void testFlushChangesDelegatesToObDalFlush() {
    Probe probe = new Probe();

    OBDal dal = mock(OBDal.class);
    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      obDal.when(OBDal::getInstance).thenReturn(dal);
      probe.flushChanges();
    }

    verify(dal).flush();
  }

  // ---------------------------------------------------------------------------------------------
  // captureCurrentContext() — returns the live OBContext
  // ---------------------------------------------------------------------------------------------

  @Test
  public void testCaptureCurrentContextReturnsCurrentObContext() {
    Probe probe = new Probe();
    OBContext current = mock(OBContext.class);

    try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class)) {
      ctxMock.when(OBContext::getOBContext).thenReturn(current);
      assertSame(current, probe.captureCurrentContext());
    }
  }

  // ---------------------------------------------------------------------------------------------
  // applyExecutionContext() / restoreExecutionContext() — static OBContext.setOBContext swaps
  // ---------------------------------------------------------------------------------------------

  @Test
  public void testApplyExecutionContextSetsTargetContext() {
    Probe probe = new Probe();

    try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class)) {
      probe.applyExecutionContext("USER-1", "ROLE-1", "CLIENT-1", "ORG-1");
      ctxMock.verify(() -> OBContext.setOBContext("USER-1", "ROLE-1", "CLIENT-1", "ORG-1"));
    }
  }

  @Test
  public void testRestoreExecutionContextRestoresPreviousContext() {
    Probe probe = new Probe();
    OBContext previous = mock(OBContext.class);

    try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class)) {
      probe.restoreExecutionContext(previous);
      ctxMock.verify(() -> OBContext.setOBContext(previous));
    }
  }

  // ---------------------------------------------------------------------------------------------
  // enterAdminMode() / exitAdminMode() — static admin-mode stack
  // ---------------------------------------------------------------------------------------------

  @Test
  public void testEnterAdminModeEntersAdminMode() {
    Probe probe = new Probe();

    try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class)) {
      probe.enterAdminMode();
      ctxMock.verify(() -> OBContext.setAdminMode(true));
    }
  }

  @Test
  public void testExitAdminModeRestoresPreviousMode() {
    Probe probe = new Probe();

    try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class)) {
      probe.exitAdminMode();
      ctxMock.verify(OBContext::restorePreviousMode);
    }
  }

  // ---------------------------------------------------------------------------------------------
  // validateContext() — every required-field branch (via the public guard)
  // ---------------------------------------------------------------------------------------------

  @Test
  public void testValidateContextPassesWhenAllPresent() {
    // Should not throw for fully populated context.
    new Probe().validateContext("CLIENT-1", "ORG-1", "USER-1", "ROLE-1");
  }

  @Test
  public void testValidateContextFailsOnNullClient() {
    assertMissing(() -> new Probe().validateContext(null, "ORG-1", "USER-1", "ROLE-1"), "client");
  }

  @Test
  public void testValidateContextFailsOnEmptyOrganization() {
    assertMissing(() -> new Probe().validateContext("CLIENT-1", "", "USER-1", "ROLE-1"),
        "organization");
  }

  @Test
  public void testValidateContextFailsOnNullAdminUser() {
    assertMissing(() -> new Probe().validateContext("CLIENT-1", "ORG-1", null, "ROLE-1"),
        "admin user");
  }

  @Test
  public void testValidateContextFailsOnEmptyAdminRole() {
    assertMissing(() -> new Probe().validateContext("CLIENT-1", "ORG-1", "USER-1", ""),
        "admin role");
  }

  @Test
  public void testValidateContextMessageCarriesContextSubject() {
    try {
      new Probe().validateContext(null, "ORG-1", "USER-1", "ROLE-1");
      fail("Expected OBException");
    } catch (OBException e) {
      assertTrue("message must include the contextSubject()",
          e.getMessage().contains("probe subject"));
    }
  }

  private static void assertMissing(Runnable action, String label) {
    try {
      action.run();
      fail("Expected OBException for missing " + label);
    } catch (OBException e) {
      assertTrue(e.getMessage().contains("Missing " + label));
    }
  }
}
