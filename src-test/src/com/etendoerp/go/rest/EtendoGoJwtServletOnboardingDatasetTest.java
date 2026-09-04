/*
 *************************************************************************
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
 *************************************************************************
 */
package com.etendoerp.go.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.PrintWriter;
import java.io.StringWriter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.openbravo.base.exception.OBException;
import org.openbravo.service.db.ImportResult;

import com.etendoerp.go.onboarding.OnboardingAccountingWiringService;
import com.etendoerp.go.onboarding.OnboardingAcctdimCentrallyMaintainedService;
import com.etendoerp.go.onboarding.OnboardingAdminIdentityService;
import com.etendoerp.go.onboarding.OnboardingDatasetImportService;
import com.etendoerp.go.onboarding.OnboardingBaselineService;
import com.etendoerp.go.onboarding.OnboardingOrgInfoService;
import com.etendoerp.go.onboarding.OnboardingPeriodControlService;
import com.etendoerp.go.onboarding.OnboardingFiscalDataSetupService;
import com.etendoerp.go.onboarding.OnboardingMarkOrgReadyService;
import com.etendoerp.go.onboarding.OnboardingSequenceGeneratorService;

public class EtendoGoJwtServletOnboardingDatasetTest {

  @Test
  public void testImportOnboardingDatasetReportsProgressOnSuccess() {
    TestServlet servlet = new TestServlet(new SuccessfulImportService());
    StringWriter output = new StringWriter();

    boolean imported = servlet.importOnboardingDataset(new PrintWriter(output), "CLIENT-1", "ORG-1");

    String ndjson = output.toString();
    assertTrue(imported);
    assertTrue(ndjson.contains("\"step\":\"dataset\""));
    assertTrue(ndjson.contains("\"status\":\"in_progress\""));
    assertTrue(ndjson.contains("\"status\":\"done\""));
    assertFalse(ndjson.contains("\"success\":false"));
    assertTrue(ndjson.indexOf("\"status\":\"in_progress\"")
        < ndjson.indexOf("\"status\":\"done\""));
  }

  /**
   * ETP-5079: this used to assert the default-customer step ran between sequence generation and the
   * baseline stamp. That step no longer exists — onboarding provisions no business partner at all —
   * so what remains under test is the surviving ordering invariant: sequences are generated before
   * the data-fix baseline is stamped, and every step receives the same client/org/user/role.
   */
  @Test
  public void testEnsureOnboardingDatasetGeneratesSequencesBeforeBaseline() {
    CountingImportService importService = new CountingImportService();
    CountingSequenceGeneratorService sequenceService = new CountingSequenceGeneratorService();
    CountingBaselineService baselineService = new CountingBaselineService();
    TestServlet servlet = new TestServlet(importService, sequenceService,
        new CountingMarkOrgReadyService(), new CountingFiscalDataSetupService(),
        baselineService);
    StringWriter output = new StringWriter();

    boolean ready = servlet.ensureOnboardingDataset(new PrintWriter(output), "CLIENT-1", "ORG-1",
        "USER-1", "ROLE-1", null);

    String ndjson = output.toString();
    assertTrue(ready);
    assertEquals(1, importService.importCount);
    assertEquals(1, sequenceService.generateCount);
    assertEquals("CLIENT-1", sequenceService.clientId);
    assertEquals("ORG-1", sequenceService.orgId);
    assertEquals("USER-1", sequenceService.userId);
    assertEquals("ROLE-1", sequenceService.roleId);
    assertEquals(1, baselineService.registerCount);
    assertEquals("CLIENT-1", baselineService.clientId);
    assertTrue(ndjson.contains("Organization sequences generated"));
    assertTrue(ndjson.contains("Data-fix baseline registered"));
    assertFalse(ndjson.contains("Default customer ready"));
    assertTrue(ndjson.indexOf("Organization sequences generated")
        < ndjson.indexOf("Data-fix baseline registered"));
  }

  @Test
  public void testEnsureOnboardingDatasetReturnsFinalFailureOnSequenceGenerationError() {
    TestServlet servlet = new TestServlet(new SuccessfulImportService(),
        new FailingSequenceGeneratorService("broken sequences"));
    StringWriter output = new StringWriter();

    boolean ready = servlet.ensureOnboardingDataset(new PrintWriter(output), "CLIENT-1", "ORG-1",
        "USER-1", "ROLE-1", null);

    String ndjson = output.toString();
    assertFalse(ready);
    assertTrue(ndjson.contains("\"step\":\"sequences\""));
    assertTrue(ndjson.contains("\"status\":\"error\""));
    assertTrue(ndjson.contains("broken sequences"));
    assertTrue(ndjson.contains("\"success\":false"));
  }

  @Test
  public void testImportOnboardingDatasetReturnsFinalFailureOnImportError() {
    TestServlet servlet = new TestServlet(new FailingImportService("broken import"));
    StringWriter output = new StringWriter();

    boolean imported = servlet.importOnboardingDataset(new PrintWriter(output), "CLIENT-1", "ORG-1");

    String ndjson = output.toString();
    assertFalse(imported);
    assertTrue(ndjson.contains("\"step\":\"dataset\""));
    assertTrue(ndjson.contains("\"status\":\"error\""));
    assertTrue(ndjson.contains("broken import"));
    assertTrue(ndjson.contains("\"success\":false"));
  }

  @Test
  @DisplayName("reconcile model always runs the dataset import (ETP-4428)")
  public void testEnsureOnboardingDatasetAlwaysRunsImportUnderReconcile() {
    CountingImportService importService = new CountingImportService();
    CountingSequenceGeneratorService sequenceService = new CountingSequenceGeneratorService();
    CountingBaselineService baselineService = new CountingBaselineService();
    TestServlet servlet = new TestServlet(importService, sequenceService,
        new CountingMarkOrgReadyService(), new CountingFiscalDataSetupService(),
        baselineService);
    StringWriter output = new StringWriter();

    boolean ready = servlet.ensureOnboardingDataset(new PrintWriter(output), "CLIENT-1", "ORG-1",
        "USER-1", "ROLE-1", null);

    String ndjson = output.toString();
    assertTrue(ready);
    // ETP-4428: there is no flag-based skip anymore — the servlet always invokes the import, and
    // idempotency (skip-if-already-present) is handled inside OnboardingDatasetImportService.
    assertEquals(1, importService.importCount);
    assertEquals(1, sequenceService.generateCount);
    assertEquals(1, baselineService.registerCount);
    assertTrue(ndjson.contains("\"step\":\"dataset\""));
    assertTrue(ndjson.contains("\"status\":\"done\""));
    assertFalse(ndjson.contains("skipping onboarding dataset import"));
    assertTrue(ndjson.contains("Data-fix baseline registered"));
  }

  @Test
  public void testEnsureOnboardingDatasetMarksOrgReadyAfterSequences() {
    CountingMarkOrgReadyService markReadyService = new CountingMarkOrgReadyService();
    TestServlet servlet = new TestServlet(new SuccessfulImportService(),
        new CountingSequenceGeneratorService(), markReadyService,
        new CountingFiscalDataSetupService());
    StringWriter output = new StringWriter();

    boolean ready = servlet.ensureOnboardingDataset(new PrintWriter(output), "CLIENT-1", "ORG-1",
        "USER-1", "ROLE-1", null);

    String ndjson = output.toString();
    assertTrue(ready);
    assertEquals(1, markReadyService.markCount);
    assertEquals("CLIENT-1", markReadyService.clientId);
    assertEquals("ORG-1", markReadyService.orgId);
    assertTrue(ndjson.contains("\"step\":\"orgReady\""));
    assertTrue(ndjson.contains("Organization is ready"));
    assertTrue(ndjson.indexOf("Organization sequences generated")
        < ndjson.indexOf("Organization is ready"));
    assertTrue(ndjson.indexOf("Organization is ready")
        < ndjson.indexOf("Fiscal data ready"));
  }

  @Test
  public void testEnsureOnboardingDatasetReturnsFinalFailureOnMarkOrgReadyError() {
    TestServlet servlet = new TestServlet(new SuccessfulImportService(),
        new CountingSequenceGeneratorService(),
        new FailingMarkOrgReadyService("broken mark ready"),
        new CountingFiscalDataSetupService());
    StringWriter output = new StringWriter();

    boolean ready = servlet.ensureOnboardingDataset(new PrintWriter(output), "CLIENT-1", "ORG-1",
        "USER-1", "ROLE-1", null);

    String ndjson = output.toString();
    assertFalse(ready);
    assertTrue(ndjson.contains("\"step\":\"orgReady\""));
    assertTrue(ndjson.contains("\"status\":\"error\""));
    assertTrue(ndjson.contains("broken mark ready"));
    assertTrue(ndjson.contains("\"success\":false"));
  }

  @Test
  public void testEnsureOnboardingDatasetSeedsFiscalDataAfterSequences() {
    CountingFiscalDataSetupService fiscalService = new CountingFiscalDataSetupService();
    TestServlet servlet = new TestServlet(new SuccessfulImportService(),
        new CountingSequenceGeneratorService(), new CountingMarkOrgReadyService(),
        fiscalService);
    StringWriter output = new StringWriter();

    boolean ready = servlet.ensureOnboardingDataset(new PrintWriter(output), "CLIENT-1", "ORG-1",
        "USER-1", "ROLE-1", null);

    String ndjson = output.toString();
    assertTrue(ready);
    assertEquals(1, fiscalService.setupCount);
    assertEquals("CLIENT-1", fiscalService.clientId);
    assertEquals("ORG-1", fiscalService.orgId);
    assertEquals("USER-1", fiscalService.userId);
    assertEquals("ROLE-1", fiscalService.roleId);
    assertTrue(ndjson.contains("\"step\":\"fiscal\""));
    assertTrue(ndjson.contains("Fiscal data ready"));
    assertTrue(ndjson.indexOf("Organization is ready")
        < ndjson.indexOf("Fiscal data ready"));
    // ETP-5079: the default-customer step used to sit between "fiscal" and the baseline; with it
    // gone the surviving downstream ordering anchor is the baseline stamp, which is always last.
    assertTrue(ndjson.indexOf("Fiscal data ready")
        < ndjson.indexOf("Data-fix baseline registered"));
  }

  @Test
  public void testEnsureOnboardingDatasetRegistersBaselineLast() {
    CountingBaselineService baselineService = new CountingBaselineService();
    TestServlet servlet = new TestServlet(new SuccessfulImportService(),
        new CountingSequenceGeneratorService(), new CountingMarkOrgReadyService(),
        new CountingFiscalDataSetupService(),
        baselineService);
    StringWriter output = new StringWriter();

    boolean ready = servlet.ensureOnboardingDataset(new PrintWriter(output), "CLIENT-1", "ORG-1",
        "USER-1", "ROLE-1", null);

    String ndjson = output.toString();
    assertTrue(ready);
    assertEquals(1, baselineService.registerCount);
    assertEquals("CLIENT-1", baselineService.clientId);
    assertTrue(ndjson.contains("\"step\":\"baseline\""));
    assertTrue(ndjson.contains("Registering data-fix baseline"));
    assertTrue(ndjson.contains("Data-fix baseline registered"));
    assertTrue(ndjson.indexOf("Fiscal data ready")
        < ndjson.indexOf("Data-fix baseline registered"));
  }

  @Test
  public void testEnsureOnboardingDatasetPropagatesBaselineFailure() {
    TestServlet servlet = new TestServlet(new SuccessfulImportService(),
        new CountingSequenceGeneratorService(), new CountingMarkOrgReadyService(),
        new CountingFiscalDataSetupService(),
        new FailingBaselineService("broken baseline"));
    StringWriter output = new StringWriter();

    try {
      servlet.ensureOnboardingDataset(new PrintWriter(output), "CLIENT-1", "ORG-1",
          "USER-1", "ROLE-1", null);
      fail("Expected baseline failure to propagate");
    } catch (OBException e) {
      assertEquals("broken baseline", e.getMessage());
    }

    String ndjson = output.toString();
    assertTrue(ndjson.contains("\"step\":\"baseline\""));
    assertTrue(ndjson.contains("\"status\":\"in_progress\""));
    assertFalse(ndjson.contains("Data-fix baseline registered"));
    assertFalse(ndjson.contains("\"success\":false"));
  }

  @Test
  public void testEnsureOnboardingDatasetPatchesBpGroupAcctBeforeBaseline() {
    CountingBaselineService baselineService = new CountingBaselineService();
    CountingAccountingWiringService accountingService = new CountingAccountingWiringService();
    TestServlet servlet = new TestServlet(new SuccessfulImportService(),
        new CountingSequenceGeneratorService(), new CountingMarkOrgReadyService(),
        new CountingFiscalDataSetupService(),
        baselineService);
    servlet.onboardingAccountingWiringService = accountingService;
    StringWriter output = new StringWriter();

    boolean ready = servlet.ensureOnboardingDataset(new PrintWriter(output), "CLIENT-1", "ORG-1",
        "USER-1", "ROLE-1", null);

    String ndjson = output.toString();
    assertTrue(ready);
    assertEquals(1, accountingService.patchCount);
    assertEquals("CLIENT-1", accountingService.clientId);
    assertEquals("ORG-1", accountingService.orgId);
    assertEquals("USER-1", accountingService.userId);
    assertEquals("ROLE-1", accountingService.roleId);
    assertEquals(1, baselineService.registerCount);
    assertTrue(ndjson.contains("\"step\":\"bpGroupAcctPatch\""));
    assertTrue(ndjson.contains("Business-partner group posting accounts patched"));
    assertTrue(ndjson.indexOf("Business-partner group posting accounts patched")
        < ndjson.indexOf("Data-fix baseline registered"));
  }

  @Test
  public void testEnsureOnboardingDatasetSkipsBaselineWhenBpGroupAcctPatchFails() {
    CountingBaselineService baselineService = new CountingBaselineService();
    TestServlet servlet = new TestServlet(new SuccessfulImportService(),
        new CountingSequenceGeneratorService(), new CountingMarkOrgReadyService(),
        new CountingFiscalDataSetupService(),
        baselineService);
    servlet.onboardingAccountingWiringService =
        new FailingAccountingWiringService("broken bp-group-acct patch");
    StringWriter output = new StringWriter();

    boolean ready = servlet.ensureOnboardingDataset(new PrintWriter(output), "CLIENT-1", "ORG-1",
        "USER-1", "ROLE-1", null);

    String ndjson = output.toString();
    assertFalse(ready);
    assertEquals(0, baselineService.registerCount);
    assertTrue(ndjson.contains("\"step\":\"bpGroupAcctPatch\""));
    assertTrue(ndjson.contains("\"status\":\"error\""));
    assertTrue(ndjson.contains("broken bp-group-acct patch"));
    assertTrue(ndjson.contains("\"success\":false"));
  }

  @Test
  public void testEnsureOnboardingDatasetForcesFlatAcctdimVisibilityBeforeBaseline() {
    CountingBaselineService baselineService = new CountingBaselineService();
    CountingAccountingWiringService accountingService = new CountingAccountingWiringService();
    CountingAcctdimCentrallyMaintainedService acctdimService =
        new CountingAcctdimCentrallyMaintainedService();
    TestServlet servlet = new TestServlet(new SuccessfulImportService(),
        new CountingSequenceGeneratorService(), new CountingMarkOrgReadyService(),
        new CountingFiscalDataSetupService(),
        baselineService);
    servlet.onboardingAccountingWiringService = accountingService;
    servlet.onboardingAcctdimCentrallyMaintainedService = acctdimService;
    StringWriter output = new StringWriter();

    boolean ready = servlet.ensureOnboardingDataset(new PrintWriter(output), "CLIENT-1", "ORG-1",
        "USER-1", "ROLE-1", null);

    String ndjson = output.toString();
    assertTrue(ready);
    assertEquals(1, acctdimService.forceFlatCount);
    assertEquals("CLIENT-1", acctdimService.clientId);
    assertEquals(1, baselineService.registerCount);
    assertTrue(ndjson.contains("\"step\":\"acctdimVisibility\""));
    assertTrue(ndjson.contains("Accounting-dimension visibility configured"));
    assertTrue(ndjson.indexOf("Business-partner group posting accounts patched")
        < ndjson.indexOf("Accounting-dimension visibility configured"));
    assertTrue(ndjson.indexOf("Accounting-dimension visibility configured")
        < ndjson.indexOf("Data-fix baseline registered"));
  }

  @Test
  public void testEnsureOnboardingDatasetSkipsBaselineWhenAcctdimVisibilityPatchFails() {
    CountingBaselineService baselineService = new CountingBaselineService();
    TestServlet servlet = new TestServlet(new SuccessfulImportService(),
        new CountingSequenceGeneratorService(), new CountingMarkOrgReadyService(),
        new CountingFiscalDataSetupService(),
        baselineService);
    servlet.onboardingAcctdimCentrallyMaintainedService =
        new FailingAcctdimCentrallyMaintainedService("broken acctdim visibility");
    StringWriter output = new StringWriter();

    boolean ready = servlet.ensureOnboardingDataset(new PrintWriter(output), "CLIENT-1", "ORG-1",
        "USER-1", "ROLE-1", null);

    String ndjson = output.toString();
    assertFalse(ready);
    assertEquals(0, baselineService.registerCount);
    assertTrue(ndjson.contains("\"step\":\"acctdimVisibility\""));
    assertTrue(ndjson.contains("\"status\":\"error\""));
    assertTrue(ndjson.contains("broken acctdim visibility"));
    assertTrue(ndjson.contains("\"success\":false"));
  }

  @Test
  public void testEnsureOnboardingDatasetSkipsBaselineWhenFiscalDataFails() {
    CountingBaselineService baselineService = new CountingBaselineService();
    TestServlet servlet = new TestServlet(new SuccessfulImportService(),
        new CountingSequenceGeneratorService(), new CountingMarkOrgReadyService(),
        new FailingFiscalDataSetupService("broken fiscal"), baselineService);
    StringWriter output = new StringWriter();

    boolean ready = servlet.ensureOnboardingDataset(new PrintWriter(output), "CLIENT-1", "ORG-1",
        "USER-1", "ROLE-1", null);

    assertFalse(ready);
    assertEquals(0, baselineService.registerCount);
  }

  @Test
  public void testEnsureOnboardingDatasetReturnsFinalFailureOnFiscalDataError() {
    TestServlet servlet = new TestServlet(new SuccessfulImportService(),
        new CountingSequenceGeneratorService(), new CountingMarkOrgReadyService(),
        new FailingFiscalDataSetupService("broken fiscal"));
    StringWriter output = new StringWriter();

    boolean ready = servlet.ensureOnboardingDataset(new PrintWriter(output), "CLIENT-1", "ORG-1",
        "USER-1", "ROLE-1", null);

    String ndjson = output.toString();
    assertFalse(ready);
    assertTrue(ndjson.contains("\"step\":\"fiscal\""));
    assertTrue(ndjson.contains("\"status\":\"error\""));
    assertTrue(ndjson.contains("broken fiscal"));
    assertTrue(ndjson.contains("\"success\":false"));
  }

  @Test
  public void testEnsureOnboardingDatasetWiresAdminIdentityBeforeBaseline() {
    CountingBaselineService baselineService = new CountingBaselineService();
    CountingAdminIdentityService adminIdentityService = new CountingAdminIdentityService();
    TestServlet servlet = new TestServlet(new SuccessfulImportService(),
        new CountingSequenceGeneratorService(), new CountingMarkOrgReadyService(),
        new CountingFiscalDataSetupService(),
        baselineService);
    servlet.onboardingAdminIdentityService = adminIdentityService;
    StringWriter output = new StringWriter();

    boolean ready = servlet.ensureOnboardingDataset(new PrintWriter(output), "CLIENT-1", "ORG-1",
        "USER-1", "ROLE-1", null);

    String ndjson = output.toString();
    assertTrue(ready);
    assertEquals(1, adminIdentityService.wireCount);
    assertEquals("CLIENT-1", adminIdentityService.clientId);
    assertEquals("ORG-1", adminIdentityService.orgId);
    assertEquals("USER-1", adminIdentityService.userId);
    assertEquals("ROLE-1", adminIdentityService.roleId);
    assertEquals(1, baselineService.registerCount);
    assertTrue(ndjson.contains("\"step\":\"adminIdentity\""));
    assertTrue(ndjson.contains("Admin identity wired"));
    assertTrue(ndjson.indexOf("Admin identity wired")
        < ndjson.indexOf("Data-fix baseline registered"));
  }

  @Test
  public void testEnsureOnboardingDatasetSkipsBaselineWhenAdminIdentityWiringFails() {
    CountingBaselineService baselineService = new CountingBaselineService();
    TestServlet servlet = new TestServlet(new SuccessfulImportService(),
        new CountingSequenceGeneratorService(), new CountingMarkOrgReadyService(),
        new CountingFiscalDataSetupService(),
        baselineService);
    servlet.onboardingAdminIdentityService =
        new FailingAdminIdentityService("broken admin identity");
    StringWriter output = new StringWriter();

    boolean ready = servlet.ensureOnboardingDataset(new PrintWriter(output), "CLIENT-1", "ORG-1",
        "USER-1", "ROLE-1", null);

    String ndjson = output.toString();
    assertFalse(ready);
    assertEquals(0, baselineService.registerCount);
    assertTrue(ndjson.contains("\"step\":\"adminIdentity\""));
    assertTrue(ndjson.contains("\"status\":\"error\""));
    assertTrue(ndjson.contains("broken admin identity"));
    assertTrue(ndjson.contains("\"success\":false"));
  }

  @Test
  public void testEnsureOnboardingDatasetSkipsFiscalDataWhenSequencesFail() {
    CountingFiscalDataSetupService fiscalService = new CountingFiscalDataSetupService();
    TestServlet servlet = new TestServlet(new SuccessfulImportService(),
        new FailingSequenceGeneratorService("broken sequences"), new CountingMarkOrgReadyService(),
        fiscalService);
    StringWriter output = new StringWriter();

    boolean ready = servlet.ensureOnboardingDataset(new PrintWriter(output), "CLIENT-1", "ORG-1",
        "USER-1", "ROLE-1", null);

    assertFalse(ready);
    assertEquals(0, fiscalService.setupCount);
  }

  private static final class TestServlet extends EtendoGoJwtServlet {
    private TestServlet(OnboardingDatasetImportService importService) {
      this(importService, new CountingSequenceGeneratorService(), new CountingMarkOrgReadyService(),
          new CountingFiscalDataSetupService(), new CountingBaselineService());
    }

    private TestServlet(OnboardingDatasetImportService importService,
        OnboardingSequenceGeneratorService sequenceGeneratorService) {
      this(importService, sequenceGeneratorService, new CountingMarkOrgReadyService(),
          new CountingFiscalDataSetupService(), new CountingBaselineService());
    }

    private TestServlet(OnboardingDatasetImportService importService,
        OnboardingSequenceGeneratorService sequenceGeneratorService,
        OnboardingMarkOrgReadyService markOrgReadyService,
        OnboardingFiscalDataSetupService fiscalDataSetupService) {
      this(importService, sequenceGeneratorService, markOrgReadyService, fiscalDataSetupService,
          new CountingBaselineService());
    }

    private TestServlet(OnboardingDatasetImportService importService,
        OnboardingSequenceGeneratorService sequenceGeneratorService,
        OnboardingMarkOrgReadyService markOrgReadyService,
        OnboardingFiscalDataSetupService fiscalDataSetupService,
        OnboardingBaselineService baselineService) {
      this.onboardingDatasetImportService = importService;
      this.onboardingSequenceGeneratorService = sequenceGeneratorService;
      this.onboardingMarkOrgReadyService = markOrgReadyService;
      this.onboardingFiscalDataSetupService = fiscalDataSetupService;
      this.onboardingBaselineService = baselineService;
      // The accounting/period/org-info provisioning steps touch the DAL and are exercised by their
      // own dedicated unit tests; here they are stubbed to no-ops so the dataset orchestration under
      // test runs without a database while keeping the servlet's own progress/flow logic intact.
      this.onboardingAccountingWiringService = new NoOpAccountingWiringService();
      this.onboardingPeriodControlService = new NoOpPeriodControlService();
      this.onboardingOrgInfoService = new NoOpOrgInfoService();
      // ETP-4854: forceFlatAccountingDimensionVisibility also touches the DAL directly (raw SQL on
      // OBDal's shared connection) and is exercised by its own dedicated unit test
      // (OnboardingAcctdimCentrallyMaintainedServiceTest); stub it to a no-op here for the same
      // reason as the services above.
      this.onboardingAcctdimCentrallyMaintainedService = new NoOpAcctdimCentrallyMaintainedService();
      // ETP-4999: wireAdminIdentity also touches the DAL directly (OBDal.getInstance().get(...) on
      // User/Role/Organization/Client) and is exercised by its own dedicated unit test
      // (OnboardingAdminIdentityServiceTest); stub it to a no-op here for the same reason as the
      // services above. Without this, the field defaults to the real service instantiated by
      // EtendoGoJwtServlet's own field initializer, which throws with no DAL/DB available in this
      // no-database unit test, making every ensureOnboardingDataset() call return false.
      this.onboardingAdminIdentityService = new NoOpAdminIdentityService();
    }
  }

  private static class NoOpAccountingWiringService extends OnboardingAccountingWiringService {
    @Override
    public void wire(String clientId, String orgId, String adminUserId, String adminRoleId) {
      // no-op: DAL wiring is covered by OnboardingAccountingWiringServiceTest
    }

    @Override
    public void patchBpGroupAcctMissingColumns(String clientId, String orgId, String adminUserId,
        String adminRoleId) {
      // no-op: DAL wiring is covered by OnboardingAccountingWiringServiceTest
    }
  }

  /**
   * ETP-4720 — counts {@code patchBpGroupAcctMissingColumns} invocations and captures its arguments,
   * so the servlet-level wiring (order relative to the other steps, argument pass-through) can be
   * asserted without touching the DAL. {@code wire} stays a no-op.
   */
  private static class CountingAccountingWiringService extends NoOpAccountingWiringService {
    private int patchCount;
    private String clientId;
    private String orgId;
    private String userId;
    private String roleId;

    @Override
    public void patchBpGroupAcctMissingColumns(String clientId, String orgId, String adminUserId,
        String adminRoleId) {
      patchCount++;
      this.clientId = clientId;
      this.orgId = orgId;
      this.userId = adminUserId;
      this.roleId = adminRoleId;
    }
  }

  /** ETP-4720 — makes {@code patchBpGroupAcctMissingColumns} fail, to test the chain's short-circuit. */
  private static final class FailingAccountingWiringService extends NoOpAccountingWiringService {
    private final String message;

    private FailingAccountingWiringService(String message) {
      this.message = message;
    }

    @Override
    public void patchBpGroupAcctMissingColumns(String clientId, String orgId, String adminUserId,
        String adminRoleId) {
      throw new OBException(message);
    }
  }

  private static class NoOpAcctdimCentrallyMaintainedService
      extends OnboardingAcctdimCentrallyMaintainedService {
    @Override
    public void forceFlatAccountingDimensionVisibility(String clientId) {
      // no-op: DAL wiring is covered by OnboardingAcctdimCentrallyMaintainedServiceTest
    }
  }

  /**
   * ETP-4854 — counts {@code forceFlatAccountingDimensionVisibility} invocations and captures its
   * argument, so the servlet-level wiring (order relative to the other steps, argument
   * pass-through) can be asserted without touching the DAL.
   */
  private static final class CountingAcctdimCentrallyMaintainedService
      extends NoOpAcctdimCentrallyMaintainedService {
    private int forceFlatCount;
    private String clientId;

    @Override
    public void forceFlatAccountingDimensionVisibility(String clientId) {
      forceFlatCount++;
      this.clientId = clientId;
    }
  }

  /**
   * ETP-4854 — makes {@code forceFlatAccountingDimensionVisibility} fail, to test the chain's
   * short-circuit.
   */
  private static final class FailingAcctdimCentrallyMaintainedService
      extends NoOpAcctdimCentrallyMaintainedService {
    private final String message;

    private FailingAcctdimCentrallyMaintainedService(String message) {
      this.message = message;
    }

    @Override
    public void forceFlatAccountingDimensionVisibility(String clientId) {
      throw new OBException(message);
    }
  }

  private static class NoOpAdminIdentityService extends OnboardingAdminIdentityService {
    @Override
    public void wireAdminIdentity(String clientId, String orgId, String adminUserId,
        String adminRoleId) {
      // no-op: DAL wiring is covered by OnboardingAdminIdentityServiceTest
    }
  }

  /**
   * ETP-4999 — counts {@code wireAdminIdentity} invocations and captures its arguments, so the
   * servlet-level wiring (order relative to the other steps, argument pass-through) can be
   * asserted without touching the DAL.
   */
  private static final class CountingAdminIdentityService extends NoOpAdminIdentityService {
    private int wireCount;
    private String clientId;
    private String orgId;
    private String userId;
    private String roleId;

    @Override
    public void wireAdminIdentity(String clientId, String orgId, String adminUserId,
        String adminRoleId) {
      wireCount++;
      this.clientId = clientId;
      this.orgId = orgId;
      this.userId = adminUserId;
      this.roleId = adminRoleId;
    }
  }

  /** ETP-4999 — makes {@code wireAdminIdentity} fail, to test the chain's short-circuit. */
  private static final class FailingAdminIdentityService extends NoOpAdminIdentityService {
    private final String message;

    private FailingAdminIdentityService(String message) {
      this.message = message;
    }

    @Override
    public void wireAdminIdentity(String clientId, String orgId, String adminUserId,
        String adminRoleId) {
      throw new OBException(message);
    }
  }

  private static final class NoOpPeriodControlService extends OnboardingPeriodControlService {
    @Override
    public void wire(String clientId, String orgId, String adminUserId, String adminRoleId) {
      // no-op: DAL wiring is covered by OnboardingPeriodControlServiceTest
    }
  }

  private static final class NoOpOrgInfoService extends OnboardingOrgInfoService {
    @Override
    public void ensureOrgInfo(String clientId, String orgId, String adminUserId, String adminRoleId,
        String countryIso, String address, String taxId) {
      // no-op: DAL wiring is covered by OnboardingOrgInfoServiceTest
    }
  }

  private static final class SuccessfulImportService extends OnboardingDatasetImportService {
    @Override
    public ImportResult importDataset(String clientId, String orgId) {
      return new ImportResult();
    }
  }

  private static final class CountingImportService extends OnboardingDatasetImportService {
    private int importCount;

    @Override
    public ImportResult importDataset(String clientId, String orgId) {
      importCount++;
      return new ImportResult();
    }
  }

  private static final class FailingImportService extends OnboardingDatasetImportService {
    private final String message;

    private FailingImportService(String message) {
      this.message = message;
    }

    @Override
    public ImportResult importDataset(String clientId, String orgId) {
      throw new OBException(message);
    }
  }

  private static class CountingSequenceGeneratorService extends OnboardingSequenceGeneratorService {
    private int generateCount;
    private String clientId;
    private String orgId;
    private String userId;
    private String roleId;

    @Override
    public int generateSequences(String clientId, String orgId, String userId, String roleId) {
      generateCount++;
      this.clientId = clientId;
      this.orgId = orgId;
      this.userId = userId;
      this.roleId = roleId;
      return 3;
    }
  }

  private static final class FailingSequenceGeneratorService extends OnboardingSequenceGeneratorService {
    private final String message;

    private FailingSequenceGeneratorService(String message) {
      this.message = message;
    }

    @Override
    public int generateSequences(String clientId, String orgId, String userId, String roleId) {
      throw new OBException(message);
    }
  }

  private static class CountingFiscalDataSetupService extends OnboardingFiscalDataSetupService {
    private int setupCount;
    private String clientId;
    private String orgId;
    private String userId;
    private String roleId;

    @Override
    public void setup(String clientId, String orgId, String userId, String roleId) {
      setupCount++;
      this.clientId = clientId;
      this.orgId = orgId;
      this.userId = userId;
      this.roleId = roleId;
    }
  }

  private static final class FailingFiscalDataSetupService extends OnboardingFiscalDataSetupService {
    private final String message;

    private FailingFiscalDataSetupService(String message) {
      this.message = message;
    }

    @Override
    public void setup(String clientId, String orgId, String userId, String roleId) {
      throw new OBException(message);
    }
  }

  private static class CountingMarkOrgReadyService extends OnboardingMarkOrgReadyService {
    private int markCount;
    private String clientId;
    private String orgId;

    @Override
    public void markOrgReady(String clientId, String orgId, String userId, String roleId) {
      markCount++;
      this.clientId = clientId;
      this.orgId = orgId;
    }
  }

  private static final class FailingMarkOrgReadyService extends OnboardingMarkOrgReadyService {
    private final String message;

    private FailingMarkOrgReadyService(String message) {
      this.message = message;
    }

    @Override
    public void markOrgReady(String clientId, String orgId, String userId, String roleId) {
      throw new OBException(message);
    }
  }

  private static class CountingBaselineService extends OnboardingBaselineService {
    private int registerCount;
    private String clientId;

    @Override
    public void registerBaseline(String clientId) {
      registerCount++;
      this.clientId = clientId;
    }
  }

  private static final class FailingBaselineService extends OnboardingBaselineService {
    private final String message;

    private FailingBaselineService(String message) {
      this.message = message;
    }

    @Override
    public void registerBaseline(String clientId) {
      throw new OBException(message);
    }
  }
}
