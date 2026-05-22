/*
 * *************************************************************************
 * The contents of this file are subject to the Etendo License
 * (the "License"), you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.LinkedHashSet;
import java.util.Set;

import org.codehaus.jettison.json.JSONObject;
import org.junit.Test;
import org.openbravo.base.exception.OBException;
import org.openbravo.model.ad.system.Client;

import com.etendoerp.sequences.SequencesGenerator;

public class OnboardingSequenceGeneratorServiceTest {

  @Test
  public void testGenerateSequencesFailsWhenAdminUserIsMissing() throws Exception {
    TestableService service = new TestableService();

    try {
      service.generateSequences("CLIENT-1", "ORG-1", null, "ROLE-1");
      fail("Expected missing admin user to fail");
    } catch (OBException e) {
      assertTrue(e.getMessage().contains("Missing admin user"));
    }
  }

  @Test
  public void testGenerateSequencesFailsWhenAdminRoleIsMissing() throws Exception {
    TestableService service = new TestableService();

    try {
      service.generateSequences("CLIENT-1", "ORG-1", "USER-1", "");
      fail("Expected missing admin role to fail");
    } catch (OBException e) {
      assertTrue(e.getMessage().contains("Missing admin role"));
    }
  }

  @Test
  public void testGenerateSequencesRestoresPreviousContextAfterFailure() throws Exception {
    TestableService service = new TestableService();
    service.throwOnGenerate = true;

    Object previous = new Object();
    service.previousContext = previous;

    try {
      service.generateSequences("CLIENT-1", "ORG-1", "USER-1", "ROLE-1");
      fail("Expected delegated generator failure");
    } catch (OBException e) {
      assertEquals("boom", e.getMessage());
    }

    assertSame(previous, service.restoredContext);
  }

  @Test
  public void testGenerateSequencesPassesResolvedOrganizationsAndParameters() throws Exception {
    TestableService service = new TestableService();

    int generated = service.generateSequences("CLIENT-1", "ORG-1", "USER-1", "ROLE-1");

    assertEquals(7, generated);
    assertEquals("CLIENT-1", service.capturedClient.getId());
    assertEquals(Set.of("ORG-1", "ORG-1-CHILD"), service.capturedOrganizations);
    assertEquals("ORG-1", service.capturedParameters.getString("ad_org_id"));
    assertTrue(service.generatorCreated);
    assertTrue(service.adminModeEntered);
    assertTrue(service.adminModeExited);
    assertEquals(2, service.flushCount);
  }

  private static final class TestableService extends OnboardingSequenceGeneratorService {
    private final Client client = mock(Client.class);
    private final Set<String> organizations = new LinkedHashSet<>();
    private Client capturedClient;
    private Set<String> capturedOrganizations;
    private JSONObject capturedParameters;
    private boolean generatorCreated;
    private boolean throwOnGenerate;
    private int flushCount;

    private TestableService() {
      when(client.getId()).thenReturn("CLIENT-1");
      organizations.add("ORG-1");
      organizations.add("ORG-1-CHILD");
    }

    private Object previousContext = new Object();
    private Object restoredContext;
    private boolean adminModeEntered;
    private boolean adminModeExited;

    @Override
    protected Object captureCurrentContext() {
      return previousContext;
    }

    @Override
    protected void applyExecutionContext(String adminUserId, String adminRoleId, String clientId,
        String orgId) {
      // Avoid static OBContext mutation in unit tests.
    }

    @Override
    protected void restoreExecutionContext(Object previousContext) {
      restoredContext = previousContext;
    }

    @Override
    protected void flushChanges() {
      flushCount++;
    }

    @Override
    protected void enterAdminMode() {
      adminModeEntered = true;
    }

    @Override
    protected void exitAdminMode() {
      adminModeExited = true;
    }

    @Override
    protected Client resolveClient(String clientId) {
      return client;
    }

    @Override
    protected Set<String> resolveOrganizations(String orgId) {
      return organizations;
    }

    @Override
    protected SequencesGenerator createSequencesGenerator() {
      generatorCreated = true;
      return mock(SequencesGenerator.class);
    }

    @Override
    protected int generateSequenceCombination(SequencesGenerator generator, Client client,
        Set<String> organizations, JSONObject parameters) throws Exception {
      if (throwOnGenerate) {
        throw new OBException("boom");
      }
      this.capturedClient = client;
      this.capturedOrganizations = organizations;
      this.capturedParameters = parameters;
      return 7;
    }
  }
}
