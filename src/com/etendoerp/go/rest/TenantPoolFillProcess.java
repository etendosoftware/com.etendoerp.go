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

import java.time.Instant;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openbravo.erpCommon.utility.OBError;
import org.openbravo.scheduling.ProcessBundle;
import org.openbravo.service.db.DalBaseProcess;

import com.etendoerp.go.onboarding.pool.TenantPoolConfig;
import com.etendoerp.go.onboarding.pool.TenantPoolFiller;
import com.etendoerp.go.onboarding.pool.TenantPoolStore;

/**
 * Background process "Tenant Pool Filler" (ETP-5389): keeps {@code ETGO_TENANT_POOL} topped up to
 * {@link TenantPoolConfig#poolSize()} READY tenants.
 *
 * <p>Scheduled for the System client by {@code TenantPoolScheduleStartup} and runs whatever the
 * flag says, so switching the flag on or off takes effect without a restart: with the flag off a
 * run does nothing. The {@code AD_Process} is {@code PREVENTCONCURRENT='Y'}; {@link
 * TenantPoolFiller} adds an in-JVM guard on top.
 */
public class TenantPoolFillProcess extends DalBaseProcess {

  private static final Logger log = LogManager.getLogger(TenantPoolFillProcess.class);

  /** {@code AD_PROCESS_ID} of this process. */
  public static final String PROCESS_ID = "B4C1BDC7106D46E1BF5337B46AB56300";

  @Override
  protected void doExecute(ProcessBundle bundle) {
    OBError result = new OBError();
    if (!isPoolEnabled()) {
      result.setType("Success");
      result.setTitle("Tenant pool disabled");
      result.setMessage("Flag onboarding-tenant-pool is off; nothing to do");
      bundle.setResult(result);
      return;
    }
    TenantPoolFiller.FillReport report = newFiller().fill(TenantPoolConfig.poolSize(),
        OnboardingProvisioningChain.provisioningVersion(), Instant.now());
    log.info("Tenant pool filler: {}", report);
    result.setType(report.failed() > 0 ? "Warning" : "Success");
    result.setTitle("Tenant pool filler");
    result.setMessage(report.toString());
    bundle.setResult(result);
  }

  boolean isPoolEnabled() {
    return TenantPoolConfig.isEnabled(null);
  }

  TenantPoolFiller newFiller() {
    return new TenantPoolFiller(new TenantPoolStore(), new TenantPoolProvisioner());
  }
}
