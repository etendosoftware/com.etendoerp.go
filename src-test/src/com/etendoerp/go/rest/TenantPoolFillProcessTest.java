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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.openbravo.erpCommon.utility.OBError;
import org.openbravo.scheduling.ProcessBundle;

import com.etendoerp.go.onboarding.pool.FakeTenantPoolStore;
import com.etendoerp.go.onboarding.pool.TenantPoolFiller;

/** ETP-5389 — the filler process is inert while the flag is off. */
public class TenantPoolFillProcessTest {

  @Test
  public void flagOffDoesNothing() {
    ProcessBundle bundle = mock(ProcessBundle.class, org.mockito.Mockito.CALLS_REAL_METHODS);
    TestProcess process = new TestProcess(false);

    process.doExecute(bundle);

    assertFalse(process.fillerCreated);
    assertEquals("Tenant pool disabled", ((OBError) bundle.getResult()).getTitle());
  }

  @Test
  public void flagOnRunsTheFillerAndReportsItsOutcome() {
    ProcessBundle bundle = mock(ProcessBundle.class, org.mockito.Mockito.CALLS_REAL_METHODS);
    TestProcess process = new TestProcess(true);

    process.doExecute(bundle);

    assertTrue(process.fillerCreated);
    OBError result = (OBError) bundle.getResult();
    assertEquals("Success", result.getType());
    assertTrue(result.getMessage().contains("created=3"), result.getMessage());
  }

  private static final class TestProcess extends TenantPoolFillProcess {
    private final boolean enabled;
    boolean fillerCreated;

    TestProcess(boolean enabled) {
      this.enabled = enabled;
    }

    @Override
    boolean isPoolEnabled() {
      return enabled;
    }

    @Override
    TenantPoolFiller newFiller() {
      fillerCreated = true;
      return new TenantPoolFiller(new FakeTenantPoolStore(),
          name -> TenantPoolFiller.ProvisionOutcome.ok("C"));
    }
  }
}
