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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.PrintWriter;
import java.io.StringWriter;

import org.junit.Test;
import org.openbravo.base.exception.OBException;
import org.openbravo.service.db.ImportResult;

import com.etendoerp.go.onboarding.OnboardingDatasetImportService;
import com.etendoerp.go.onboarding.OnboardingDefaultCustomerService;
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

  @Test
  public void testEnsureOnboardingDatasetSeedsDefaultCustomerAfterSequences() {
    CountingImportService importService = new CountingImportService();
    CountingSequenceGeneratorService sequenceService = new CountingSequenceGeneratorService();
    CountingDefaultCustomerService customerService = new CountingDefaultCustomerService();
    TestServlet servlet = new TestServlet(importService, sequenceService, customerService);
    StringWriter output = new StringWriter();

    boolean ready = servlet.ensureOnboardingDataset(new PrintWriter(output), "CLIENT-1", "ORG-1", true,
        "USER-1", "ROLE-1");

    String ndjson = output.toString();
    assertTrue(ready);
    assertEquals(1, importService.importCount);
    assertEquals(1, sequenceService.generateCount);
    assertEquals(1, customerService.seedCount);
    assertEquals("CLIENT-1", sequenceService.clientId);
    assertEquals("ORG-1", sequenceService.orgId);
    assertEquals("USER-1", sequenceService.userId);
    assertEquals("ROLE-1", sequenceService.roleId);
    assertEquals("CLIENT-1", customerService.clientId);
    assertEquals("ORG-1", customerService.orgId);
    assertEquals("USER-1", customerService.userId);
    assertEquals("ROLE-1", customerService.roleId);
    assertTrue(ndjson.contains("Organization sequences generated"));
    assertTrue(ndjson.contains("Default customer ready"));
    assertTrue(ndjson.indexOf("Organization sequences generated")
        < ndjson.indexOf("Default customer ready"));
  }

  @Test
  public void testEnsureOnboardingDatasetReturnsFinalFailureOnSequenceGenerationError() {
    TestServlet servlet = new TestServlet(new SuccessfulImportService(),
        new FailingSequenceGeneratorService("broken sequences"));
    StringWriter output = new StringWriter();

    boolean ready = servlet.ensureOnboardingDataset(new PrintWriter(output), "CLIENT-1", "ORG-1", true,
        "USER-1", "ROLE-1");

    String ndjson = output.toString();
    assertFalse(ready);
    assertTrue(ndjson.contains("\"step\":\"sequences\""));
    assertTrue(ndjson.contains("\"status\":\"error\""));
    assertTrue(ndjson.contains("broken sequences"));
    assertTrue(ndjson.contains("\"success\":false"));
  }

  @Test
  public void testEnsureOnboardingDatasetReturnsFinalFailureOnDefaultCustomerError() {
    TestServlet servlet = new TestServlet(new SuccessfulImportService(),
        new CountingSequenceGeneratorService(),
        new FailingDefaultCustomerService("broken customer"));
    StringWriter output = new StringWriter();

    boolean ready = servlet.ensureOnboardingDataset(new PrintWriter(output), "CLIENT-1", "ORG-1", true,
        "USER-1", "ROLE-1");

    String ndjson = output.toString();
    assertFalse(ready);
    assertTrue(ndjson.contains("\"step\":\"customer\""));
    assertTrue(ndjson.contains("\"status\":\"error\""));
    assertTrue(ndjson.contains("broken customer"));
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
  public void testEnsureOnboardingDatasetSkipsImportAndStillSeedsCustomer() {
    CountingImportService importService = new CountingImportService();
    CountingSequenceGeneratorService sequenceService = new CountingSequenceGeneratorService();
    CountingDefaultCustomerService customerService = new CountingDefaultCustomerService();
    TestServlet servlet = new TestServlet(importService, sequenceService, customerService);
    StringWriter output = new StringWriter();

    boolean ready = servlet.ensureOnboardingDataset(new PrintWriter(output), "CLIENT-1", "ORG-1", false,
        "USER-1", "ROLE-1");

    String ndjson = output.toString();
    assertTrue(ready);
    assertEquals(0, importService.importCount);
    assertEquals(1, sequenceService.generateCount);
    assertEquals(1, customerService.seedCount);
    assertTrue(ndjson.contains("\"step\":\"dataset\""));
    assertTrue(ndjson.contains("\"status\":\"done\""));
    assertTrue(ndjson.contains("skipping onboarding dataset import"));
    assertTrue(ndjson.contains("Default customer ready"));
  }

  @Test
  public void testEnsureOnboardingDatasetMarksOrgReadyAfterSequences() {
    CountingMarkOrgReadyService markReadyService = new CountingMarkOrgReadyService();
    TestServlet servlet = new TestServlet(new SuccessfulImportService(),
        new CountingSequenceGeneratorService(), markReadyService,
        new CountingFiscalDataSetupService(), new CountingDefaultCustomerService());
    StringWriter output = new StringWriter();

    boolean ready = servlet.ensureOnboardingDataset(new PrintWriter(output), "CLIENT-1", "ORG-1", true,
        "USER-1", "ROLE-1");

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
        new CountingFiscalDataSetupService(), new CountingDefaultCustomerService());
    StringWriter output = new StringWriter();

    boolean ready = servlet.ensureOnboardingDataset(new PrintWriter(output), "CLIENT-1", "ORG-1", true,
        "USER-1", "ROLE-1");

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
        fiscalService, new CountingDefaultCustomerService());
    StringWriter output = new StringWriter();

    boolean ready = servlet.ensureOnboardingDataset(new PrintWriter(output), "CLIENT-1", "ORG-1", true,
        "USER-1", "ROLE-1");

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
    assertTrue(ndjson.indexOf("Fiscal data ready")
        < ndjson.indexOf("Default customer ready"));
  }

  @Test
  public void testEnsureOnboardingDatasetReturnsFinalFailureOnFiscalDataError() {
    TestServlet servlet = new TestServlet(new SuccessfulImportService(),
        new CountingSequenceGeneratorService(), new CountingMarkOrgReadyService(),
        new FailingFiscalDataSetupService("broken fiscal"),
        new CountingDefaultCustomerService());
    StringWriter output = new StringWriter();

    boolean ready = servlet.ensureOnboardingDataset(new PrintWriter(output), "CLIENT-1", "ORG-1", true,
        "USER-1", "ROLE-1");

    String ndjson = output.toString();
    assertFalse(ready);
    assertTrue(ndjson.contains("\"step\":\"fiscal\""));
    assertTrue(ndjson.contains("\"status\":\"error\""));
    assertTrue(ndjson.contains("broken fiscal"));
    assertTrue(ndjson.contains("\"success\":false"));
  }

  @Test
  public void testEnsureOnboardingDatasetSkipsFiscalDataWhenSequencesFail() {
    CountingFiscalDataSetupService fiscalService = new CountingFiscalDataSetupService();
    TestServlet servlet = new TestServlet(new SuccessfulImportService(),
        new FailingSequenceGeneratorService("broken sequences"), new CountingMarkOrgReadyService(),
        fiscalService, new CountingDefaultCustomerService());
    StringWriter output = new StringWriter();

    boolean ready = servlet.ensureOnboardingDataset(new PrintWriter(output), "CLIENT-1", "ORG-1", true,
        "USER-1", "ROLE-1");

    assertFalse(ready);
    assertEquals(0, fiscalService.setupCount);
  }

  private static final class TestServlet extends EtendoGoJwtServlet {
    private TestServlet(OnboardingDatasetImportService importService) {
      this(importService, new CountingSequenceGeneratorService(), new CountingMarkOrgReadyService(),
          new CountingFiscalDataSetupService(), new CountingDefaultCustomerService());
    }

    private TestServlet(OnboardingDatasetImportService importService,
        OnboardingSequenceGeneratorService sequenceGeneratorService) {
      this(importService, sequenceGeneratorService, new CountingMarkOrgReadyService(),
          new CountingFiscalDataSetupService(), new CountingDefaultCustomerService());
    }

    private TestServlet(OnboardingDatasetImportService importService,
        OnboardingSequenceGeneratorService sequenceGeneratorService,
        OnboardingDefaultCustomerService defaultCustomerService) {
      this(importService, sequenceGeneratorService, new CountingMarkOrgReadyService(),
          new CountingFiscalDataSetupService(), defaultCustomerService);
    }

    private TestServlet(OnboardingDatasetImportService importService,
        OnboardingSequenceGeneratorService sequenceGeneratorService,
        OnboardingMarkOrgReadyService markOrgReadyService,
        OnboardingFiscalDataSetupService fiscalDataSetupService,
        OnboardingDefaultCustomerService defaultCustomerService) {
      this.onboardingDatasetImportService = importService;
      this.onboardingSequenceGeneratorService = sequenceGeneratorService;
      this.onboardingMarkOrgReadyService = markOrgReadyService;
      this.onboardingFiscalDataSetupService = fiscalDataSetupService;
      this.onboardingDefaultCustomerService = defaultCustomerService;
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

  private static class CountingDefaultCustomerService extends OnboardingDefaultCustomerService {
    private int seedCount;
    private String clientId;
    private String orgId;
    private String userId;
    private String roleId;

    @Override
    public String ensureDefaultCustomer(String clientId, String orgId, String userId, String roleId) {
      seedCount++;
      this.clientId = clientId;
      this.orgId = orgId;
      this.userId = userId;
      this.roleId = roleId;
      return "BP-1";
    }
  }

  private static final class FailingDefaultCustomerService extends OnboardingDefaultCustomerService {
    private final String message;

    private FailingDefaultCustomerService(String message) {
      this.message = message;
    }

    @Override
    public String ensureDefaultCustomer(String clientId, String orgId, String userId, String roleId) {
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
}
