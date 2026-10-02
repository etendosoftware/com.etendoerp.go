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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.openbravo.base.exception.OBException;

import com.etendoerp.go.onboarding.OnboardingSampleDataService;

/**
 * ETP-5426 — the servlet wiring of the optional sample data: the post-commit best-effort step and
 * the onboarding draft keeping the checkbox value.
 */
class EtendoGoJwtServletSampleDataTest {

  private static final String CLIENT_ID = "client";
  private static final String ORG_ID = "org";
  private static final String REQUEST_CLASS =
      "com.etendoerp.go.rest.EtendoGoJwtServlet$OnboardingRequestData";

  private final EtendoGoJwtServlet servlet = new EtendoGoJwtServlet();
  private final OnboardingSampleDataService sampleDataService =
      mock(OnboardingSampleDataService.class);
  private final OnboardingProvisioningChain.AdminContext adminContext =
      new OnboardingProvisioningChain.AdminContext();
  private final StringWriter output = new StringWriter();

  @BeforeEach
  void setUp() {
    servlet.onboardingSampleDataService = sampleDataService;
    adminContext.adminUserId = "admin-user";
    adminContext.adminRoleId = "admin-role";
  }

  @Test
  void aSignupThatDidNotOptInGetsNoSampleDataAndNoProgressLine() throws Exception {
    runStep(request(false, "ES", "EUR"), false);

    verifyNoInteractions(sampleDataService);
    assertEquals("", output.toString());
  }

  @Test
  void anIneligibleOptInIsIgnoredSilently() throws Exception {
    runStep(request(true, "AR", "EUR"), false);
    runStep(request(true, "ES", "EUR"), true);

    verifyNoInteractions(sampleDataService);
    assertEquals("", output.toString());
  }

  @Test
  void anEligibleOptInImportsCommitsAndReportsDone() throws Exception {
    try (MockedStatic<EtendoGoDalHelper> dal = mockStatic(EtendoGoDalHelper.class)) {
      runStep(request(true, "ES", "EUR"), false);

      verify(sampleDataService)
          .importSampleData(CLIENT_ID, ORG_ID, "admin-user", "admin-role");
      dal.verify(() -> EtendoGoDalHelper.commitDalChanges(eq("onboarding sample data"), any()));
      dal.verify(() -> EtendoGoDalHelper.rollbackDalChanges(anyString(), any(), any()), never());
    }
    String lines = output.toString();
    assertTrue(lines.contains("\"sampleData\""), lines);
    assertTrue(lines.contains("\"in_progress\""), lines);
    assertTrue(lines.contains("\"done\""), lines);
  }

  @Test
  void aFailedImportRollsBackAndReportsAWarningWithoutFailingTheSignup() throws Exception {
    when(sampleDataService.importSampleData(anyString(), anyString(), anyString(), anyString()))
        .thenThrow(new OBException("Referenced object not present"));

    try (MockedStatic<EtendoGoDalHelper> dal = mockStatic(EtendoGoDalHelper.class)) {
      runStep(request(true, "ES", "EUR"), false);

      dal.verify(() -> EtendoGoDalHelper.rollbackDalChanges(eq("onboarding sample data"), any(),
          any()));
      dal.verify(() -> EtendoGoDalHelper.commitDalChanges(anyString(), any()), never());
    }
    String lines = output.toString();
    assertTrue(lines.contains("\"warning\""), lines);
    assertFalse(lines.contains("\"error\""), "a sample-data failure is never a provisioning error");
  }

  @Test
  void theDraftKeepsTheSampleDataCheckboxAsABoolean() throws Exception {
    JSONObject form = new JSONObject()
        .put("clientName", "Acme")
        .put("includeSampleData", true)
        .put("sector", Boolean.TRUE);
    JSONObject draft = new JSONObject().put("step", 2).put("form", form);

    JSONObject clean = sanitizeDraft(draft).getJSONObject("form");

    assertEquals(Boolean.TRUE, clean.get("includeSampleData"));
    assertEquals("Acme", clean.get("clientName"));
    assertFalse(clean.has("sector"), "only the sample-data opt-in may be a boolean");
  }

  // ── internals ──────────────────────────────────────────────────────────────

  private void runStep(Object request, boolean paidUpgrade) throws Exception {
    Class<?> requestClass = Class.forName(REQUEST_CLASS);
    Method step = EtendoGoJwtServlet.class.getDeclaredMethod("importSampleDataBestEffort",
        PrintWriter.class, String.class, String.class,
        OnboardingProvisioningChain.AdminContext.class, requestClass, boolean.class);
    step.setAccessible(true);
    PrintWriter writer = new PrintWriter(output);
    step.invoke(servlet, writer, CLIENT_ID, ORG_ID, adminContext, request, paidUpgrade);
    writer.flush();
  }

  private Object request(boolean includeSampleData, String countryCode, String currencyIso)
      throws Exception {
    Class<?> requestClass = Class.forName(REQUEST_CLASS);
    Constructor<?> constructor = requestClass.getDeclaredConstructor();
    constructor.setAccessible(true);
    Object request = constructor.newInstance();
    set(requestClass, request, "includeSampleData", includeSampleData);
    set(requestClass, request, "countryCode", countryCode);
    set(requestClass, request, "currencyIso", currencyIso);
    return request;
  }

  private void set(Class<?> type, Object target, String name, Object value) throws Exception {
    Field field = type.getDeclaredField(name);
    field.setAccessible(true);
    field.set(target, value);
  }

  private JSONObject sanitizeDraft(JSONObject draft) throws Exception {
    Method sanitize = EtendoGoJwtServlet.class.getDeclaredMethod("sanitizeOnboardingDraft",
        JSONObject.class);
    sanitize.setAccessible(true);
    return (JSONObject) sanitize.invoke(servlet, draft);
  }
}
