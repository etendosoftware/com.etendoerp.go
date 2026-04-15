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
  public void testEnsureOnboardingDatasetSkipsImportForExistingOrganization() {
    CountingImportService importService = new CountingImportService();
    TestServlet servlet = new TestServlet(importService);
    StringWriter output = new StringWriter();

    boolean imported = servlet.ensureOnboardingDataset(new PrintWriter(output), "CLIENT-1", "ORG-1", false);

    String ndjson = output.toString();
    assertTrue(imported);
    assertEquals(0, importService.importCount);
    assertTrue(ndjson.contains("\"step\":\"dataset\""));
    assertTrue(ndjson.contains("\"status\":\"done\""));
    assertTrue(ndjson.contains("skipping onboarding dataset import"));
    assertFalse(ndjson.contains("\"status\":\"in_progress\""));
  }

  private static final class TestServlet extends EtendoGoJwtServlet {
    private final OnboardingDatasetImportService importService;

    private TestServlet(OnboardingDatasetImportService importService) {
      this.importService = importService;
    }

    @Override
    OnboardingDatasetImportService createOnboardingDatasetImportService() {
      return importService;
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
}
